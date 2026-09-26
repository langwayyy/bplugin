package com.kkk.bplugin

import junit.framework.TestCase
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate

class ForwardTestingTest : TestCase() {
    fun testShadowOcoCompletesWithoutTradingService() {
        val rule = rule().copy(orderTemplate = StrategyOrderTemplate.OCO, targetPercent = BigDecimal("3"), stopPercent = BigDecimal("2"))
        var session = session(rule)
        val signal = ForwardEngine.signal(session, quote("100", "100", "100", 1_000), "exec", 10, 2)
        session = signal.session
        assertEquals(1, session.orders)
        assertEquals(1, session.fills)
        assertTrue(session.quantity.signum() > 0)

        val exit = ForwardEngine.market(session, quote("103", "104", "99", 2_000), 10, 2)
        assertEquals(0, exit.session.quantity.compareTo(BigDecimal.ZERO))
        assertEquals(2, exit.session.fills)
        assertTrue(exit.session.realizedPnl.signum() > 0)
        assertEquals(1, exit.session.closedTrades)
        assertEquals(1, exit.session.winningTrades)
        assertTrue(ForwardEngine.metrics(exit.session).profitFactor > BigDecimal.ONE)
        assertTrue(exit.events.any { it.type == ForwardEventType.EXIT })
    }

    fun testLimitSignalWaitsForLaterMarketTouch() {
        val rule = rule().copy(orderTemplate = StrategyOrderTemplate.LIMIT, limitOffsetPercent = BigDecimal("-1"))
        val submitted = ForwardEngine.signal(session(rule), quote("100", "100", "100", 1_000), "exec", 0, 0).session
        assertEquals(0, submitted.pendingPrice!!.compareTo(BigDecimal("99")))
        assertEquals(0, submitted.fills)
        val filled = ForwardEngine.market(submitted, quote("100", "101", "98", 2_000), 0, 0).session
        assertEquals(1, filled.fills)
        assertNull(filled.pendingPrice)
    }

    fun testPromotionRequiresCompletedReliableSample() {
        val source = session(rule()).copy(status = ForwardSessionStatus.COMPLETED, endedAt = 20_000,
            signals = 20, orders = 10, fills = 9, errors = 1, startedAt = 10_000, gapMillis = 0,
            maxDrawdownPercent = BigDecimal("5"))
        assertTrue(ForwardEngine.promotion(source, ForwardGateConfig()).allowed)
        val unreliable = source.copy(gapMillis = 9_000)
        assertFalse(ForwardEngine.promotion(unreliable, ForwardGateConfig()).allowed)
    }

    fun testJournalAppendsDailyJsonAndPurgesExpiredFiles() {
        val root = Files.createTempDirectory("quiet-forward")
        try {
            val journal = ForwardEventJournal(root, 30)
            val old = root.resolve("2020-01-01.jsonl"); Files.writeString(old, "old")
            val event = ForwardEvent(sessionId = "s", time = System.currentTimeMillis(), type = ForwardEventType.SIGNAL,
                stage = ForwardStage.SHADOW, strategyId = "r", strategyName = "safe", symbol = "BTCUSDT")
            journal.append(event); journal.purge(LocalDate.now())
            assertFalse(Files.exists(old))
            val today = root.resolve("${LocalDate.now()}.jsonl")
            assertTrue(Files.readString(today).contains("\"SIGNAL\""))
            journal.append(event)
            Files.writeString(today, "not-json\n", java.nio.file.StandardOpenOption.APPEND)
            val loaded = journal.read(LocalDate.now(), LocalDate.now(), sessionId = "s")
            assertEquals(1, loaded.size)
            assertEquals(ForwardEventType.SIGNAL, loaded.single().type)
        } finally { root.toFile().deleteRecursively() }
    }

    fun testHealthPolicyPausesOnlyAtConfiguredThresholds() {
        val policy = ForwardHealthPolicy(maxSingleGapMillis = 60_000, maxConsecutiveErrors = 3)
        assertNull(forwardHealthReason(policy, singleGapMillis = 59_999, consecutiveErrors = 2))
        assertTrue(forwardHealthReason(policy, singleGapMillis = 60_000)!!.contains("行情断档"))
        assertTrue(forwardHealthReason(policy, consecutiveErrors = 3)!!.contains("订单错误"))
        assertNull(forwardHealthReason(policy.copy(autoPause = false), singleGapMillis = 600_000, consecutiveErrors = 10))
    }

    fun testStaleHealthClockUsesResumeBaselineBeforeFirstMarketUpdate() {
        val source = session(rule()).copy(startedAt = 1_000, monitoringStartedAt = 50_000,
            expectedIntervalMillis = 10_000, lastMarketAt = 0)
        assertEquals(0, forwardStaleMillis(source, 59_999))
        assertEquals(5_000, forwardStaleMillis(source, 65_000))
        assertEquals(2_000, forwardStaleMillis(source.copy(lastMarketAt = 60_000), 72_000))
    }

    fun testStrategySnapshotFingerprintIsStableAndSensitiveToExecutionFields() {
        val source = rule()
        assertEquals(forwardRuleFingerprint(source), forwardRuleFingerprint(source.copy()))
        assertFalse(forwardRuleFingerprint(source).isBlank())
        assertFalse(forwardRuleFingerprint(source) == forwardRuleFingerprint(source.copy(budgetUsdt = BigDecimal("1001"))))
    }

    fun testLateFeeAttributionReducesEquityWithoutCreatingAnotherFill() {
        val bought = ForwardEngine.attributeFill(session(rule()), PaperOrderSide.BUY, BigDecimal("100"), BigDecimal.ONE)
        val adjusted = ForwardEngine.attributeFee(bought, PaperOrderSide.BUY, BigDecimal("0.1"))
        assertEquals(0, adjusted.totalFees.compareTo(BigDecimal("0.1")))
        assertEquals(0, adjusted.currentEquity.compareTo(bought.currentEquity - BigDecimal("0.1")))
        assertTrue(adjusted.averageCost > bought.averageCost)
    }

    fun testZeroFeeStreamUpdateDoesNotResetReconciledCumulativeFee() {
        val delta = forwardExecutionDelta(BigDecimal("1"), BigDecimal("2"), BigDecimal("0.1"), BigDecimal.ZERO)
        assertEquals(0, delta.quantity.compareTo(BigDecimal.ONE))
        assertEquals(0, delta.fee.compareTo(BigDecimal.ZERO))
        assertEquals(0, delta.recordedFee.compareTo(BigDecimal("0.1")))
    }

    fun testLateSellFeeAdjustsExistingExecutionStatistics() {
        val initial = session(rule()).copy(quantity = BigDecimal.ONE, averageCost = BigDecimal("100"))
        val sold = ForwardEngine.attributeFill(initial, PaperOrderSide.SELL, BigDecimal("110"), BigDecimal.ONE)
        val tracked = ForwardEngine.attributeExecutionPnl(sold, "exec", sold.realizedPnl - initial.realizedPnl)
        val charged = ForwardEngine.attributeFee(tracked, PaperOrderSide.SELL, BigDecimal.ONE)
        val reconciled = ForwardEngine.attributeExecutionPnl(charged, "exec", BigDecimal.ONE.negate())
        assertEquals(1, reconciled.closedTrades)
        assertEquals(0, reconciled.grossProfit.compareTo(BigDecimal("9")))
        assertEquals(0, ForwardEngine.metrics(reconciled).expectancy.compareTo(BigDecimal("9")))
    }

    fun testExportContainsMetricsButNoCredentialFields() {
        val session = session(rule()).copy(status = ForwardSessionStatus.COMPLETED, endedAt = 2_000)
        val event = ForwardEvent(sessionId = session.id, time = 1, type = ForwardEventType.ERROR, stage = ForwardStage.SHADOW,
            strategyId = session.strategyId, strategyName = session.strategyName, symbol = session.symbol,
            message = "apiKey=very-secret-value Secret: another-secret")
        val html = ForwardReportExporter.html(session, listOf(event))
        assertTrue(html.contains("资金曲线"))
        assertFalse(html.contains("very-secret-value"))
        assertFalse(html.contains("another-secret"))
    }

    private fun session(rule: CryptoStrategyRule) = ForwardSession(strategyId = rule.id, strategyName = rule.name,
        symbol = rule.symbol, stage = ForwardStage.SHADOW, ruleSnapshot = rule, startedAt = 0, expectedIntervalMillis = 10_000)
    private fun rule() = CryptoStrategyRule(name = "forward", symbol = "BTCUSDT", condition = StrategyCondition.PRICE_ABOVE,
        threshold = BigDecimal("100"), side = PaperOrderSide.BUY, budgetUsdt = BigDecimal("1000"))
    private fun quote(price: String, high: String, low: String, time: Long) = CryptoQuote("BTCUSDT", BigDecimal(price), BigDecimal.ZERO,
        BigDecimal(high), BigDecimal(low), BigDecimal.ZERO, Instant.ofEpochMilli(time))
}

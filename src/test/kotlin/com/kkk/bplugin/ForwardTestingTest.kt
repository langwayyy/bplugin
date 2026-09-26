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
            maxDrawdownPercent = BigDecimal("5"), closedTrades = 5, winningTrades = 3, losingTrades = 2,
            grossProfit = BigDecimal("30"), grossLoss = BigDecimal("20"), realizedPnl = BigDecimal("10"))
        assertTrue(ForwardEngine.promotion(source, ForwardGateConfig()).allowed)
        val unreliable = source.copy(gapMillis = 9_000)
        assertFalse(ForwardEngine.promotion(unreliable, ForwardGateConfig()).allowed)
        assertFalse(ForwardEngine.promotion(source.copy(closedTrades = 4), ForwardGateConfig()).allowed)
        assertFalse(ForwardEngine.promotion(source.copy(grossProfit = BigDecimal("10"), grossLoss = BigDecimal("20")), ForwardGateConfig()).allowed)
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
        assertTrue(forwardHealthReason(policy.copy(maxOrderLatencyMillis = 2_000), orderLatencyMillis = 2_000)!!.contains("响应耗时"))
        assertTrue(forwardHealthReason(policy.copy(maxOrdersPerSession = 5), orderCount = 5)!!.contains("订单数"))
        assertTrue(forwardHealthReason(policy.copy(maxSessionDurationMillis = 86_400_000), sessionRuntimeMillis = 86_400_000)!!.contains("运行已达"))
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

    fun testRecentAndHistoricalEventsMergeWithoutDuplicates() {
        val base = ForwardEvent(id = "same", sessionId = "s", time = 1, type = ForwardEventType.SIGNAL,
            stage = ForwardStage.SHADOW, strategyId = "r", strategyName = "rule", symbol = "BTCUSDT")
        val newer = base.copy(id = "new", time = 2)
        assertEquals(listOf("new", "same"), mergeForwardEvents(listOf(newer, base), listOf(base)).map(ForwardEvent::id))
    }

    fun testSessionRetentionNeverDropsActiveSessions() {
        val active = (1..3).map { session(rule().copy(id = "a$it")).copy(id = "active-$it") }
        val completed = (1..10).map { session(rule().copy(id = "c$it")).copy(id = "done-$it",
            status = ForwardSessionStatus.COMPLETED) }
        val retained = retainForwardSessions(completed + active, limit = 5)
        assertEquals(5, retained.size)
        assertTrue(active.all { candidate -> retained.any { it.id == candidate.id } })
        assertEquals(2, retained.count { it.status == ForwardSessionStatus.COMPLETED })
    }

    fun testEmergencyPauseOnlyChangesRunningSessions() {
        val running = session(rule()).copy(id = "running")
        val alreadyPaused = session(rule()).copy(id = "paused", status = ForwardSessionStatus.PAUSED)
        val completed = session(rule()).copy(id = "done", status = ForwardSessionStatus.COMPLETED)
        val (all, changed) = pauseRunningForwardSessions(listOf(running, alreadyPaused, completed), "人工熔断", 1_000)
        assertEquals(listOf("running"), changed.map(ForwardSession::id))
        assertEquals(ForwardSessionStatus.PAUSED, all.first { it.id == "running" }.status)
        assertEquals("人工熔断", all.first { it.id == "running" }.healthPauseReason)
        assertEquals(ForwardSessionStatus.COMPLETED, all.first { it.id == "done" }.status)
    }

    fun testActiveDurationExcludesPausedTime() {
        val running = session(rule()).copy(startedAt = 1_000, runStartedAt = 1_000, activeMillis = 100)
        assertEquals(600, forwardActiveMillis(running, 1_500))
        val paused = pauseForwardSession(running, "pause", 1_500)
        assertEquals(600, forwardActiveMillis(paused, 10_000))
        val resumed = paused.copy(status = ForwardSessionStatus.RUNNING, runStartedAt = 2_000)
        assertEquals(1_000, forwardActiveMillis(resumed, 2_400))
    }

    fun testJournalReadKeepsNewestEventsWithinLimit() {
        val root = Files.createTempDirectory("quiet-forward-limit")
        try {
            val journal = ForwardEventJournal(root)
            val base = LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            (1L..20L).forEach { time -> journal.append(ForwardEvent(id = "e$time", sessionId = "s", time = base + time,
                type = ForwardEventType.SIGNAL, stage = ForwardStage.SHADOW, strategyId = "r",
                strategyName = "rule", symbol = "BTCUSDT")) }
            val loaded = journal.read(LocalDate.now(), LocalDate.now(), limit = 3)
            assertEquals(listOf("e20", "e19", "e18"), loaded.map(ForwardEvent::id))
        } finally { root.toFile().deleteRecursively() }
    }

    fun testJournalCanReadWhileNewEventsAreAppended() {
        val root = Files.createTempDirectory("quiet-forward-concurrent")
        try {
            val journal = ForwardEventJournal(root)
            val now = System.currentTimeMillis()
            val writer = Thread {
                (1..100).forEach { index -> journal.append(ForwardEvent(id = "c$index", sessionId = "s", time = now,
                    type = ForwardEventType.SIGNAL, stage = ForwardStage.SHADOW, strategyId = "r",
                    strategyName = "rule", symbol = "BTCUSDT")) }
            }
            writer.start()
            repeat(10) { journal.read(LocalDate.now(), LocalDate.now(), limit = 10) }
            writer.join()
            assertEquals(100, journal.read(LocalDate.now(), LocalDate.now(), limit = 200).size)
        } finally { root.toFile().deleteRecursively() }
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

    fun testJsonAuditBundleIsRedactedAndTamperEvident() {
        val session = session(rule())
        val event = ForwardEvent(sessionId = session.id, time = 1, type = ForwardEventType.ERROR,
            stage = session.stage, strategyId = session.strategyId, strategyName = session.strategyName,
            symbol = session.symbol, message = "secret=never-export-this")
        val json = ForwardReportExporter.json(session, listOf(event), generatedAt = 123)
        assertTrue(ForwardReportExporter.verifyJson(json))
        assertFalse(json.contains("never-export-this"))
        assertFalse(ForwardReportExporter.verifyJson(json.replace("BTCUSDT", "ETHUSDT")))
    }

    private fun session(rule: CryptoStrategyRule) = ForwardSession(strategyId = rule.id, strategyName = rule.name,
        symbol = rule.symbol, stage = ForwardStage.SHADOW, ruleSnapshot = rule, startedAt = 0, expectedIntervalMillis = 10_000)
    private fun rule() = CryptoStrategyRule(name = "forward", symbol = "BTCUSDT", condition = StrategyCondition.PRICE_ABOVE,
        threshold = BigDecimal("100"), side = PaperOrderSide.BUY, budgetUsdt = BigDecimal("1000"))
    private fun quote(price: String, high: String, low: String, time: Long) = CryptoQuote("BTCUSDT", BigDecimal(price), BigDecimal.ZERO,
        BigDecimal(high), BigDecimal(low), BigDecimal.ZERO, Instant.ofEpochMilli(time))
}

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

    private fun session(rule: CryptoStrategyRule) = ForwardSession(strategyId = rule.id, strategyName = rule.name,
        symbol = rule.symbol, stage = ForwardStage.SHADOW, ruleSnapshot = rule, startedAt = 0, expectedIntervalMillis = 10_000)
    private fun rule() = CryptoStrategyRule(name = "forward", symbol = "BTCUSDT", condition = StrategyCondition.PRICE_ABOVE,
        threshold = BigDecimal("100"), side = PaperOrderSide.BUY, budgetUsdt = BigDecimal("1000"))
    private fun quote(price: String, high: String, low: String, time: Long) = CryptoQuote("BTCUSDT", BigDecimal(price), BigDecimal.ZERO,
        BigDecimal(high), BigDecimal(low), BigDecimal.ZERO, Instant.ofEpochMilli(time))
}

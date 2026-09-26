package com.kkk.bplugin

import junit.framework.TestCase
import java.math.BigDecimal
import java.math.RoundingMode

class TestnetAnalyticsTest : TestCase() {
    fun testTradeFeeConvertsBaseAndQuoteAssetsToUsdt() {
        assertEquals(0, testnetTradeFeeUsdt(trade(1, true, "100", "1", "100", "0.2", "USDT"), emptyMap())!!
            .compareTo(BigDecimal("0.2")))
        assertEquals(0, testnetTradeFeeUsdt(trade(2, true, "100", "1", "100", "0.001", "BTC"), emptyMap())!!
            .compareTo(BigDecimal("0.1")))
        assertEquals(0, testnetTradeFeeUsdt(trade(3, true, "100", "1", "100", "0.01", "BNB"),
            mapOf("BNBUSDT" to BigDecimal("300")))!!.compareTo(BigDecimal("3")))
    }
    fun testPerformanceUsesNetFeesAndWeightedCost() {
        val trades = listOf(
            trade(1, true, "100", "2", "200", "0.01", "BTC"),
            trade(2, true, "120", "1", "120", "1", "USDT"),
            trade(3, false, "150", "1", "150", "0.5", "USDT"),
        )
        val result = analyzeTestnetTrades("BTCUSDT", trades, BigDecimal("140"))
        assertEquals(BigDecimal("1.99"), result.position)
        assertEquals(BigDecimal("42.14214047"), result.realizedPnl.setScale(8, RoundingMode.HALF_UP))
        assertTrue(result.unrealizedPnl > BigDecimal.ZERO)
        assertEquals(BigDecimal("0.01"), result.fees["BTC"])
        assertEquals(BigDecimal("1.5"), result.fees["USDT"])
    }

    fun testRiskRejectsNotionalPositionAndPriceDeviation() {
        val balances = listOf(TestnetBalance("USDT", BigDecimal("1000"), BigDecimal.ZERO))
        assertNotNull(validateTestnetRisk("BTCUSDT", PaperOrderSide.BUY, BigDecimal("2"), BigDecimal("100"), BigDecimal("100"),
            balances, TestnetRiskPolicy(100, BigDecimal("100"), 5)))
        assertNotNull(validateTestnetRisk("BTCUSDT", PaperOrderSide.BUY, BigDecimal("6"), BigDecimal("100"), BigDecimal("100"),
            balances, TestnetRiskPolicy(50, BigDecimal("1000"), 5)))
        assertNotNull(validateTestnetRisk("BTCUSDT", PaperOrderSide.BUY, BigDecimal("1"), BigDecimal("110"), BigDecimal("100"),
            balances, TestnetRiskPolicy(100, BigDecimal("1000"), 5)))
        assertNull(validateTestnetRisk("BTCUSDT", PaperOrderSide.BUY, BigDecimal("1"), BigDecimal("102"), BigDecimal("100"),
            balances, TestnetRiskPolicy(100, BigDecimal("1000"), 5)))
    }

    fun testRiskCountsLockedBalanceAsPendingExposure() {
        val balances = listOf(TestnetBalance("USDT", BigDecimal("600"), BigDecimal("400")))
        val result = validateTestnetRisk("BTCUSDT", PaperOrderSide.BUY, BigDecimal("2"), BigDecimal("100"), BigDecimal("100"),
            balances, TestnetRiskPolicy(50, BigDecimal("1000"), 5))
        assertNotNull(result)
        assertTrue(result!!.contains("未完成委托"))
    }

    fun testConditionalAndOcoPriceDirections() {
        val current = BigDecimal("100")
        assertNull(validateConditionalTrigger(TestnetOrderKind.STOP_LOSS_LIMIT, PaperOrderSide.SELL, BigDecimal("95"), current))
        assertNotNull(validateConditionalTrigger(TestnetOrderKind.STOP_LOSS_LIMIT, PaperOrderSide.SELL, BigDecimal("105"), current))
        assertNull(validateConditionalTrigger(TestnetOrderKind.TAKE_PROFIT_LIMIT, PaperOrderSide.SELL, BigDecimal("110"), current))
        assertNull(validateOcoPrices(PaperOrderSide.SELL, current, BigDecimal("110"), BigDecimal("95"), BigDecimal("94")))
        assertNotNull(validateOcoPrices(PaperOrderSide.SELL, current, BigDecimal("90"), BigDecimal("95"), BigDecimal("94")))
        assertNotNull(validateOcoPrices(PaperOrderSide.SELL, current, BigDecimal("110"), BigDecimal("95"), BigDecimal("96")))
        assertNull(validateOcoPrices(PaperOrderSide.BUY, current, BigDecimal("90"), BigDecimal("105"), BigDecimal("106")))
    }

    fun testEquitySkipsAssetsWithoutUsdtPrice() {
        val balances = listOf(TestnetBalance("USDT", BigDecimal("100"), BigDecimal("5")),
            TestnetBalance("BTC", BigDecimal("0.1"), BigDecimal.ZERO), TestnetBalance("UNKNOWN", BigDecimal.TEN, BigDecimal.ZERO))
        assertEquals(BigDecimal("115.0"), testnetEquityUsdt(balances, mapOf("BTCUSDT" to BigDecimal("100"))))
    }

    private fun trade(id: Long, buyer: Boolean, price: String, qty: String, quote: String, commission: String, asset: String) =
        TestnetTrade(id, id, "BTCUSDT", BigDecimal(price), BigDecimal(qty), BigDecimal(quote), BigDecimal(commission), asset, id, buyer)
}

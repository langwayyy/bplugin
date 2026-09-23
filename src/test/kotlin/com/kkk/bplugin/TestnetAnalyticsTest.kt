package com.kkk.bplugin

import junit.framework.TestCase
import java.math.BigDecimal
import java.math.RoundingMode

class TestnetAnalyticsTest : TestCase() {
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

    fun testEquitySkipsAssetsWithoutUsdtPrice() {
        val balances = listOf(TestnetBalance("USDT", BigDecimal("100"), BigDecimal("5")),
            TestnetBalance("BTC", BigDecimal("0.1"), BigDecimal.ZERO), TestnetBalance("UNKNOWN", BigDecimal.TEN, BigDecimal.ZERO))
        assertEquals(BigDecimal("115.0"), testnetEquityUsdt(balances, mapOf("BTCUSDT" to BigDecimal("100"))))
    }

    private fun trade(id: Long, buyer: Boolean, price: String, qty: String, quote: String, commission: String, asset: String) =
        TestnetTrade(id, id, "BTCUSDT", BigDecimal(price), BigDecimal(qty), BigDecimal(quote), BigDecimal(commission), asset, id, buyer)
}

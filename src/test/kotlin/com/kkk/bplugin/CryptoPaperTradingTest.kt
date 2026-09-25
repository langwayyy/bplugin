package com.kkk.bplugin

import junit.framework.TestCase
import java.math.BigDecimal

class CryptoPaperTradingTest : TestCase() {
    fun testMarketRoundTripIncludesFeesAndRealizedProfit() {
        val book = PaperTradingBook(PaperAccount(BigDecimal("10000"), BigDecimal("10000")), feeBps = 10, slippageBps = 0)

        assertTrue(book.place("BTCUSDT", PaperOrderSide.BUY, PaperOrderType.MARKET,
            BigDecimal.ONE, null, BigDecimal("100")).accepted)
        assertEquals("9899.9", book.account.cash.toPlainString())
        assertEquals("100.1", book.account.positions.single().averageCost.toPlainString())

        assertTrue(book.place("BTCUSDT", PaperOrderSide.SELL, PaperOrderType.MARKET,
            BigDecimal.ONE, null, BigDecimal("110")).accepted)
        assertEquals("10009.79", book.account.cash.toPlainString())
        assertEquals("9.79", book.account.realizedPnl.toPlainString())
        assertTrue(book.account.positions.isEmpty())
    }

    fun testLimitOrderReservesCashAndFillsWhenPriceCrosses() {
        val book = PaperTradingBook(PaperAccount(), feeBps = 10, slippageBps = 0)
        val result = book.place("ETHUSDT", PaperOrderSide.BUY, PaperOrderType.LIMIT,
            BigDecimal("2"), BigDecimal("90"), BigDecimal("100"))

        assertTrue(result.accepted)
        assertEquals(0, BigDecimal("9819.820").compareTo(book.availableCash()))
        assertFalse(book.onPrice("ETHUSDT", BigDecimal("95")))
        assertTrue(book.onPrice("ETHUSDT", BigDecimal("89")))
        assertEquals(PaperOrderStatus.FILLED, book.account.orders.single().status)
        assertEquals("89", book.account.orders.single().fillPrice!!.toPlainString())
        assertEquals("9821.822", book.account.cash.toPlainString())
    }

    fun testOpenSellOrdersReservePositionAndCanBeCancelled() {
        val book = PaperTradingBook(PaperAccount(), feeBps = 0, slippageBps = 0)
        book.place("BTCUSDT", PaperOrderSide.BUY, PaperOrderType.MARKET, BigDecimal("2"), null, BigDecimal("100"))
        val order = book.place("BTCUSDT", PaperOrderSide.SELL, PaperOrderType.LIMIT,
            BigDecimal("1.5"), BigDecimal("120"), BigDecimal("100")).order!!

        assertEquals("0.5", book.availableQuantity("BTCUSDT").toPlainString())
        assertFalse(book.place("BTCUSDT", PaperOrderSide.SELL, PaperOrderType.MARKET,
            BigDecimal.ONE, null, BigDecimal("100")).accepted)
        assertTrue(book.cancel(order.id))
        assertEquals("2", book.availableQuantity("BTCUSDT").toPlainString())
    }

    fun testCrossingLimitOrderFillsImmediately() {
        val book = PaperTradingBook(PaperAccount(), feeBps = 0, slippageBps = 0)
        val result = book.place("BTCUSDT", PaperOrderSide.BUY, PaperOrderType.LIMIT,
            BigDecimal.ONE, BigDecimal("101"), BigDecimal("100"))

        assertTrue(result.accepted)
        val filled = requireNotNull(result.order)
        assertEquals(PaperOrderStatus.FILLED, filled.status)
        assertEquals("100", filled.fillPrice!!.toPlainString())
    }

    fun testRejectsNonUsdtAndMissingMarketPrice() {
        val book = PaperTradingBook()
        assertFalse(book.place("ETHBTC", PaperOrderSide.BUY, PaperOrderType.MARKET,
            BigDecimal.ONE, null, BigDecimal.ONE).accepted)
        assertFalse(book.place("BTCUSDT", PaperOrderSide.BUY, PaperOrderType.MARKET,
            BigDecimal.ONE, null, null).accepted)
    }

    fun testSellOcoFillsOneLegAndCancelsSibling() {
        val book = PaperTradingBook(PaperAccount(), feeBps = 0, slippageBps = 0)
        book.place("BTCUSDT", PaperOrderSide.BUY, PaperOrderType.MARKET, BigDecimal.ONE, null, BigDecimal("100"))
        val result = book.placeOco("BTCUSDT", PaperOrderSide.SELL, BigDecimal.ONE,
            BigDecimal("110"), BigDecimal("95"), BigDecimal("94"), BigDecimal("100"))
        assertTrue(result.accepted)
        assertEquals(0, BigDecimal.ZERO.compareTo(book.availableQuantity("BTCUSDT")))
        assertTrue(book.onPrice("BTCUSDT", BigDecimal("111")))
        val group = book.account.orders.filter { it.ocoGroupId == result.order!!.ocoGroupId }
        assertEquals(1, group.count { it.status == PaperOrderStatus.FILLED })
        assertEquals(1, group.count { it.status == PaperOrderStatus.CANCELLED })
        assertTrue(book.account.positions.isEmpty())
    }

    fun testCancellingOcoCancelsBothLegsAndReleasesPosition() {
        val book = PaperTradingBook(PaperAccount(), feeBps = 0, slippageBps = 0)
        book.place("BTCUSDT", PaperOrderSide.BUY, PaperOrderType.MARKET, BigDecimal.ONE, null, BigDecimal("100"))
        val order = book.placeOco("BTCUSDT", PaperOrderSide.SELL, BigDecimal.ONE,
            BigDecimal("110"), BigDecimal("95"), BigDecimal("94"), BigDecimal("100")).order!!
        assertTrue(book.cancel(order.id))
        assertEquals(2, book.account.orders.count { it.ocoGroupId == order.ocoGroupId && it.status == PaperOrderStatus.CANCELLED })
        assertEquals(0, BigDecimal.ONE.compareTo(book.availableQuantity("BTCUSDT")))
    }

    fun testBarFillPolicyMatchesBacktestAmbiguityRule() {
        fun result(policy: IntrabarFillPolicy): BigDecimal {
            val book = PaperTradingBook(PaperAccount(), feeBps = 0, slippageBps = 0)
            book.place("BTCUSDT", PaperOrderSide.BUY, PaperOrderType.MARKET, BigDecimal.ONE, null, BigDecimal("100"))
            book.placeOco("BTCUSDT", PaperOrderSide.SELL, BigDecimal.ONE, BigDecimal("110"), BigDecimal("90"), BigDecimal("90"), BigDecimal("100"))
            book.onBar("BTCUSDT", KlineBar(1, 100.0, 115.0, 85.0, 105.0, 10.0), policy)
            return book.account.realizedPnl
        }
        assertTrue(result(IntrabarFillPolicy.OPTIMISTIC) > result(IntrabarFillPolicy.CONSERVATIVE))
        assertEquals(result(IntrabarFillPolicy.CONSERVATIVE), result(IntrabarFillPolicy.PRICE_PATH))
    }
}

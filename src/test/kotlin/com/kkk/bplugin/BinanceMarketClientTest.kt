package com.kkk.bplugin

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class BinanceMarketClientTest {
    @Test fun `small prices retain meaningful precision`() {
        assertEquals("0.00000012", marketPrice(BigDecimal("0.000000120000")))
        assertEquals("64250.1", marketPrice(BigDecimal("64250.10000000")))
        assertEquals("1.24", marketPrice(BigDecimal("1.235"), 2))
    }
    @Test fun `ticker keeps decimal values and exchange time`() {
        val quote = BinanceMarketClient.parseQuotes("""[{"symbol":"SHIBUSDT","lastPrice":"0.0000123400","priceChangePercent":"-2.500","highPrice":"0.000015","lowPrice":"0.000011","quoteVolume":"123456789.12345678","closeTime":1700000000000}]""").single()
        assertEquals(BigDecimal("0.0000123400"), quote.price)
        assertEquals(BigDecimal("123456789.12345678"), quote.turnover)
        assertEquals(1700000000000L, quote.updatedAt.toEpochMilli())
        assertTrue(quote.change.signum() < 0)
    }
    @Test fun `candles preserve epoch milliseconds and sort chronologically`() {
        val bars = BinanceMarketClient.parseKlines("""[[1700000060000,"2","3","1","2.5","100"],[1700000000000,"1","2","0.5","1.5","50"],[1700000000000,"1","2","0.5","1.5","50"]]""")
        assertEquals(2, bars.size)
        assertEquals(1700000000000L, bars.first().timestamp)
        assertEquals(2.5, bars.last().close, 0.000001)
        assertEquals(100.0, bars.last().volume, 0.000001)
    }
    @Test fun `normalization preserves digits in coin symbols`() {
        assertEquals("1000SATSUSDT", normalizeMarketSymbol(" 1000sats/usdt "))
        assertTrue(isCryptoSymbol("BTC/USDT"))
        assertFalse(isCryptoSymbol("BTCUSDT&limit=1000"))
        assertFalse(isCryptoSymbol("600519"))
    }
    @Test(expected = IllegalArgumentException::class)
    fun `malformed candle cannot enter chart renderer`() {
        BinanceMarketClient.parseKlines("""[[1700000000000,"2","1","0.5","2.5","100"]]""")
    }
    @Test fun `websocket ticker updates exact decimal values`() {
        val update = BinanceStreamClient.parseStreamUpdate("""{"stream":"btcusdt@ticker","data":{"e":"24hrTicker","E":1700000000123,"s":"BTCUSDT","c":"64250.12000000","P":"2.36","h":"65000","l":"62000","q":"123456789.12"}}""")
        assertEquals("BTCUSDT", update.quote?.symbol)
        assertEquals(BigDecimal("64250.12000000"), update.quote?.price)
        assertNull(update.candle)
    }
    @Test fun `websocket candle maps interval and close state`() {
        val update = BinanceStreamClient.parseStreamUpdate("""{"data":{"e":"kline","E":1700000001000,"s":"ETHUSDT","k":{"t":1700000000000,"s":"ETHUSDT","i":"1h","o":"3500","h":"3520","l":"3490","c":"3510","v":"120.5","x":false}}}""")
        assertEquals(KlinePeriod.HOUR, update.candle?.period)
        assertEquals(3510.0, update.candle?.bar?.close ?: 0.0, 0.0001)
        assertFalse(update.candle?.closed ?: true)
    }
}

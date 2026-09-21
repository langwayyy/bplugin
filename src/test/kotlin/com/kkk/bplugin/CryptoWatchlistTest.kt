package com.kkk.bplugin

import org.junit.Assert.*
import org.junit.Test

class CryptoWatchlistTest {
    @Test fun `tab separated export round trips groups notes and symbols with digits`() {
        val entries = listOf(
            CryptoWatchMetadata("BTCUSDT", "主流币", "长期观察"),
            CryptoWatchMetadata("1000SATSUSDT", "观察", "小价格,含逗号"),
        )
        assertEquals(entries, WatchlistTransfer.decode(WatchlistTransfer.encode(entries)))
    }

    @Test fun `legacy one-symbol-per-line import uses default group and removes duplicates`() {
        val decoded = WatchlistTransfer.decode("BTCUSDT\nbtc/usdt\nETHUSDT\ninvalid symbol")
        assertEquals(listOf("BTCUSDT", "ETHUSDT"), decoded.map(CryptoWatchMetadata::symbol))
        assertTrue(decoded.all { it.group == DEFAULT_WATCH_GROUP })
    }

    @Test fun `metadata normalization removes line breaking separators and caps lengths`() {
        assertEquals("主流 币", normalizeWatchGroup("  主流\t币\n"))
        assertEquals(200, normalizeWatchNote("x".repeat(250)).length)
    }
}

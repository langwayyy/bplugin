package com.kkk.bplugin

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class CryptoAlertEngineTest {
    private fun quote(price: String, change: String, at: Instant) = CryptoQuote(
        "BTCUSDT", BigDecimal(price), BigDecimal(change), BigDecimal(price), BigDecimal(price), BigDecimal("1000"), at,
    )

    @Test fun `price alert fires only on crossing and respects cooldown`() {
        val engine = CryptoAlertEngine()
        val rule = CryptoAlertRule(id = "price", symbol = "BTCUSDT", condition = AlertCondition.PRICE_ABOVE, threshold = BigDecimal("100"))
        val start = Instant.parse("2026-01-01T00:00:00Z")
        assertTrue(engine.evaluate(quote("99", "0", start), listOf(rule), 30, start).isEmpty())
        assertEquals(1, engine.evaluate(quote("101", "0", start.plusSeconds(60)), listOf(rule), 30, start.plusSeconds(60)).size)
        assertTrue(engine.evaluate(quote("99", "0", start.plusSeconds(120)), listOf(rule), 30, start.plusSeconds(120)).isEmpty())
        assertTrue(engine.evaluate(quote("101", "0", start.plusSeconds(180)), listOf(rule), 30, start.plusSeconds(180)).isEmpty())
        assertEquals(1, engine.evaluate(quote("99", "0", start.plusSeconds(1_900)), listOf(rule), 30, start.plusSeconds(1_900)).let {
            engine.evaluate(quote("101", "0", start.plusSeconds(2_000)), listOf(rule), 30, start.plusSeconds(2_000))
        }.size)
    }

    @Test fun `negative change threshold fires when loss crosses downward`() {
        val engine = CryptoAlertEngine()
        val rule = CryptoAlertRule(id = "loss", symbol = "BTCUSDT", condition = AlertCondition.CHANGE_BELOW, threshold = BigDecimal("-5"))
        val start = Instant.parse("2026-01-01T00:00:00Z")
        engine.evaluate(quote("100", "-4.9", start), listOf(rule), 30, start)
        val event = engine.evaluate(quote("99", "-5.1", start.plusSeconds(10)), listOf(rule), 30, start.plusSeconds(10)).single()
        assertTrue(event.message.contains("-5%"))
    }

    @Test fun `older REST quote cannot retrigger after a newer stream quote`() {
        val engine = CryptoAlertEngine()
        val rule = CryptoAlertRule(id = "ordered", symbol = "BTCUSDT", condition = AlertCondition.PRICE_ABOVE, threshold = BigDecimal("100"))
        val start = Instant.parse("2026-01-01T00:00:00Z")
        engine.evaluate(quote("99", "0", start), listOf(rule), 1, start)
        assertEquals(1, engine.evaluate(quote("101", "0", start.plusSeconds(2)), listOf(rule), 1, start.plusSeconds(2)).size)
        assertTrue(engine.evaluate(quote("98", "0", start.plusSeconds(1)), listOf(rule), 1, start.plusSeconds(70)).isEmpty())
        assertTrue(engine.evaluate(quote("102", "0", start.plusSeconds(3)), listOf(rule), 1, start.plusSeconds(80)).isEmpty())
    }
}

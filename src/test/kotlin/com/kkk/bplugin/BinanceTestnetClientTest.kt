package com.kkk.bplugin

import junit.framework.TestCase
import java.math.BigDecimal

class BinanceTestnetClientTest : TestCase() {
    fun testHmacSignatureMatchesStandardVector() {
        assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            BinanceTestnetClient.sign("The quick brown fox jumps over the lazy dog", "key"))
    }

    fun testSymbolRulesValidateQuantityStepAndNotional() {
        val rules = TestnetSymbolRules(BigDecimal("0.001"), BigDecimal("100"), BigDecimal("0.001"), BigDecimal("10"),
            BigDecimal("0.01"), BigDecimal("1000000"), BigDecimal("0.01"))
        assertNotNull(rules.validate(BigDecimal("0.0001"), BigDecimal("50000")))
        assertNotNull(rules.validate(BigDecimal("0.0015"), BigDecimal("50000")))
        assertNotNull(rules.validate(BigDecimal("0.001"), BigDecimal("100")))
        assertNotNull(rules.validate(BigDecimal("0.002"), BigDecimal("50000.005")))
        assertNull(rules.validate(BigDecimal("0.002"), BigDecimal("50000")))
    }

    fun testSymbolRulesNormalizeQuantityAndPriceDownToExchangeSteps() {
        val rules = TestnetSymbolRules(BigDecimal("0.001"), BigDecimal("2"), BigDecimal("0.001"), BigDecimal("10"),
            BigDecimal("0.01"), BigDecimal("1000000"), BigDecimal("0.01"))
        assertEquals(BigDecimal("0.123"), rules.normalizeQuantity(BigDecimal("0.123987")))
        assertEquals(BigDecimal("2"), rules.normalizeQuantity(BigDecimal("3.5")))
        assertEquals(BigDecimal("64250.12"), rules.normalizePrice(BigDecimal("64250.129")))
    }

    fun testMinimumQuantityRoundsUpToNotionalAndStep() {
        val rules = TestnetSymbolRules(BigDecimal("0.001"), BigDecimal("100"), BigDecimal("0.001"), BigDecimal("10"))
        assertEquals(BigDecimal("0.102"), rules.minimumQuantity(BigDecimal("99")))
    }
}

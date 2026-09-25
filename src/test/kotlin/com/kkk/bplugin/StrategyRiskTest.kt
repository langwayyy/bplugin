package com.kkk.bplugin

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class StrategyRiskTest {
    private val config = PortfolioRiskConfig(80, 30, 5, 15, 3)
    @Test fun `portfolio and symbol exposure are enforced before order`() {
        val total = PortfolioRiskEvaluator.evaluate(config, PortfolioRiskRuntime(),
            PortfolioSnapshot(BigDecimal("10000"), BigDecimal("7900"), BigDecimal("1000"), false), BigDecimal("500"), "2026-09-25")
        assertFalse(total.allowed); assertTrue(total.reason.contains("总敞口"))
        val symbol = PortfolioRiskEvaluator.evaluate(config, PortfolioRiskRuntime(),
            PortfolioSnapshot(BigDecimal("10000"), BigDecimal("1000"), BigDecimal("2900"), false), BigDecimal("500"), "2026-09-25")
        assertFalse(symbol.allowed); assertTrue(symbol.reason.contains("单币种"))
    }

    @Test fun `daily loss pauses and next day resets risk state`() {
        val before = PortfolioRiskRuntime("2026-09-25", BigDecimal("10000"), BigDecimal("10500"), 0)
        val blocked = PortfolioRiskEvaluator.evaluate(config, before,
            PortfolioSnapshot(BigDecimal("9400"), BigDecimal.ZERO, BigDecimal.ZERO, false), BigDecimal.ZERO, "2026-09-25")
        assertFalse(blocked.allowed); assertTrue(blocked.runtime.pausedReason.contains("每日最大亏损"))
        val reset = PortfolioRiskEvaluator.evaluate(config, blocked.runtime,
            PortfolioSnapshot(BigDecimal("9400"), BigDecimal.ZERO, BigDecimal.ZERO, false), BigDecimal.ZERO, "2026-09-26")
        assertTrue(reset.allowed); assertEquals("", reset.runtime.pausedReason)
    }

    @Test fun `duplicate active symbol is blocked without pausing portfolio`() {
        val decision = PortfolioRiskEvaluator.evaluate(config, PortfolioRiskRuntime(),
            PortfolioSnapshot(BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO, true), BigDecimal("100"), "2026-09-25")
        assertFalse(decision.allowed); assertTrue(decision.reason.contains("已有策略订单")); assertEquals("", decision.runtime.pausedReason)
    }
}

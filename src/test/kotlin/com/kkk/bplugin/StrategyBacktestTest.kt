package com.kkk.bplugin

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class StrategyBacktestTest {
    private fun bars(): List<KlineBar> = buildList {
        repeat(20) { i -> add(KlineBar(i * 60_000L, 99.0, 99.5, 98.5, 99.0, 100.0)) }
        add(KlineBar(20 * 60_000L, 99.0, 101.5, 98.8, 101.0, 120.0))
        add(KlineBar(21 * 60_000L, 101.0, 106.0, 100.0, 104.0, 150.0))
    }
    private val rule = CryptoStrategyRule(name = "backtest", symbol = "BTCUSDT", condition = StrategyCondition.PRICE_ABOVE,
        threshold = BigDecimal("100"), action = StrategyAction.PAPER, side = PaperOrderSide.BUY,
        orderTemplate = StrategyOrderTemplate.OCO, budgetUsdt = BigDecimal("1000"), targetPercent = BigDecimal("3"), stopPercent = BigDecimal("2"))

    @Test fun `backtest is deterministic and OCO closes one position`() {
        val first = StrategyBacktester.run(rule, bars(), BacktestConfig(feeBps = 0, slippageBps = 0))
        val second = StrategyBacktester.run(rule, bars(), BacktestConfig(feeBps = 0, slippageBps = 0))
        assertEquals(first, second)
        assertEquals(1, first.triggers)
        assertEquals(listOf(PaperOrderSide.BUY, PaperOrderSide.SELL), first.trades.map(BacktestTrade::side))
        assertTrue(first.totalReturnPercent.signum() > 0)
        assertEquals("OCO 止盈", first.trades.last().reason)
    }

    @Test fun `fees reduce backtest final equity`() {
        val free = StrategyBacktester.run(rule, bars(), BacktestConfig(feeBps = 0, slippageBps = 0))
        val charged = StrategyBacktester.run(rule, bars(), BacktestConfig(feeBps = 10, slippageBps = 2))
        assertTrue(charged.finalEquity < free.finalEquity)
    }
}

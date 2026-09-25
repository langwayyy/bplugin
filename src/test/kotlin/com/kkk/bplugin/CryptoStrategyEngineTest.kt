package com.kkk.bplugin

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

class CryptoStrategyEngineTest {
    private val start = Instant.parse("2026-09-23T00:00:00Z")
    private val startDate = start.atZone(ZoneId.systemDefault()).toLocalDate().toString()
    private fun quote(price: String, change: String = "0", turnover: String = "1000", at: Instant = start) =
        CryptoQuote("BTCUSDT", BigDecimal(price), BigDecimal(change), BigDecimal(price), BigDecimal(price), BigDecimal(turnover), at)
    private fun rule(condition: StrategyCondition = StrategyCondition.PRICE_ABOVE, threshold: String = "100") =
        CryptoStrategyRule(id = "test", name = "test", symbol = "BTCUSDT", condition = condition,
            threshold = BigDecimal(threshold), cooldownMinutes = 30, maxExecutionsPerDay = 3)

    @Test fun `edge trigger does not repeat while condition stays true`() {
        val item = rule()
        var runtime = StrategyRuntime()
        runtime = StrategyEvaluator.evaluate(item, quote("99"), emptyList(), runtime, false, 0, 20, start).runtime
        val first = StrategyEvaluator.evaluate(item, quote("101", at = start.plusSeconds(1)), emptyList(), runtime, false, 0, 20, start.plusSeconds(1))
        assertTrue(first.ready)
        val held = StrategyEvaluator.evaluate(item, quote("102", at = start.plusSeconds(2)), emptyList(), first.runtime, false, 1, 20, start.plusSeconds(2))
        assertFalse(held.crossed)
    }

    @Test fun `cooldown blocks a fresh crossing and records reason`() {
        val item = rule()
        val runtime = StrategyRuntime(previousMetric = "99", lastTriggeredAt = start.toEpochMilli(),
            executionDate = startDate, executionsToday = 1)
        val result = StrategyEvaluator.evaluate(item, quote("101", at = start.plusSeconds(60)), emptyList(), runtime, false, 1, 20, start.plusSeconds(60))
        assertTrue(result.crossed); assertFalse(result.ready); assertEquals("策略冷却中", result.reason)
    }

    @Test fun `daily and global controls block execution`() {
        val item = rule().copy(maxExecutionsPerDay = 1)
        val runtime = StrategyRuntime(previousMetric = "99", executionDate = startDate, executionsToday = 1)
        assertEquals("已达到单策略每日执行上限", StrategyEvaluator.evaluate(item, quote("101"), emptyList(), runtime, false, 1, 20, start).reason)
        assertEquals("策略中心已暂停", StrategyEvaluator.evaluate(item, quote("101"), emptyList(), runtime.copy(executionsToday = 0), true, 0, 20, start).reason)
        assertEquals("已达到全局每日执行上限", StrategyEvaluator.evaluate(item, quote("101"), emptyList(), runtime.copy(executionsToday = 0), false, 20, 20, start).reason)
    }

    @Test fun `moving average cross uses the configured windows`() {
        val item = rule(StrategyCondition.MA_CROSS_ABOVE).copy(fastWindow = 2, slowWindow = 5)
        val below = listOf("10", "10", "10", "10", "9").map(::BigDecimal)
        val first = StrategyEvaluator.evaluate(item, quote("9"), below, StrategyRuntime(), false, 0, 20, start)
        assertFalse(first.crossed)
        val above = listOf("10", "10", "10", "9", "12").map(::BigDecimal)
        val crossed = StrategyEvaluator.evaluate(item, quote("12", at = start.plusSeconds(1)), above, first.runtime, false, 0, 20, start.plusSeconds(1))
        assertTrue(crossed.ready)
    }

    @Test fun `strategy and runtime state survive serialization`() {
        val item = rule().copy(name = "persisted", action = StrategyAction.TESTNET_DRAFT)
        val runtime = StrategyRuntime(previousMetric = "101", lastTriggeredAt = 1234, executionDate = "2026-09-23", executionsToday = 2)
        val execution = StrategyExecution("exec", item.id, item.name, item.symbol, 1234, StrategyExecutionState.SUBMITTING)
        val service = CryptoStrategyService()
        service.loadState(CryptoStrategyService.StoredState(strategiesJson = Gson().toJson(listOf(item)),
            runtimesJson = Gson().toJson(mapOf(item.id to runtime)), executionsJson = Gson().toJson(listOf(execution)), paused = true, testnetAutoEnabled = true))
        assertEquals(item, service.strategies().single())
        assertTrue(service.isPaused()); assertTrue(service.isTestnetAutoEnabled())
        val stored = service.state
        assertTrue(stored.strategiesJson.contains("persisted")); assertTrue(stored.runtimesJson.contains("1234"))
        assertEquals(StrategyExecutionState.UNKNOWN, service.executions().single().state)
    }
}

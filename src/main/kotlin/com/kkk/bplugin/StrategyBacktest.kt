package com.kkk.bplugin

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.math.pow

data class BacktestConfig(val initialBalance: BigDecimal = BigDecimal("10000"), val feeBps: Int = 10, val slippageBps: Int = 2)
data class BacktestTrade(val time: Long, val side: PaperOrderSide, val price: BigDecimal, val quantity: BigDecimal,
                         val fee: BigDecimal, val pnl: BigDecimal? = null, val reason: String)
data class EquityPoint(val time: Long, val equity: BigDecimal, val drawdownPercent: BigDecimal)
data class BacktestReport(
    val rule: CryptoStrategyRule, val bars: Int, val from: Long, val to: Long, val initialBalance: BigDecimal,
    val finalEquity: BigDecimal, val totalReturnPercent: BigDecimal, val annualizedReturnPercent: BigDecimal,
    val maxDrawdownPercent: BigDecimal, val winRatePercent: BigDecimal, val profitFactor: BigDecimal,
    val averageHoldingMinutes: Long, val maxConsecutiveLosses: Int, val triggers: Int,
    val trades: List<BacktestTrade>, val equityCurve: List<EquityPoint>,
)
object StrategyBacktestStore { @Volatile var latest: BacktestReport? = null }

/** Pure deterministic backtester. It has no account, credential, or order-client dependency. */
internal object StrategyBacktester {
    private val BPS = BigDecimal("10000")
    fun run(rule: CryptoStrategyRule, bars: List<KlineBar>, config: BacktestConfig): BacktestReport {
        require(bars.size >= maxOf(2, rule.slowWindow)) { "K线数量不足" }
        val ordered = bars.distinctBy(KlineBar::timestamp).sortedBy(KlineBar::timestamp)
        var cash = config.initialBalance
        var quantity = BigDecimal.ZERO
        var averageCost = BigDecimal.ZERO
        var runtime = StrategyRuntime()
        var triggers = 0
        var peak = config.initialBalance
        var maxDrawdown = BigDecimal.ZERO
        var openAt: Long? = null
        var holdingMillis = 0L
        val trades = mutableListOf<BacktestTrade>()
        val curve = mutableListOf<EquityPoint>()
        val realized = mutableListOf<BigDecimal>()
        val prices = mutableListOf<BigDecimal>()
        val slip = BigDecimal(config.slippageBps).divide(BPS, 12, RoundingMode.HALF_UP)
        val feeRate = BigDecimal(config.feeBps).divide(BPS, 12, RoundingMode.HALF_UP)
        var bracket: Pair<BigDecimal, BigDecimal>? = null
        var pending: Triple<PaperOrderSide, BigDecimal, String>? = null
        var dayIndex = 0
        var rollingTurnover = BigDecimal.ZERO

        fun buy(time: Long, rawPrice: BigDecimal, reason: String) {
            val price = rawPrice * (BigDecimal.ONE + slip)
            val wanted = rule.budgetUsdt.divide(price, 16, RoundingMode.DOWN)
            val max = cash.divide(price * (BigDecimal.ONE + feeRate), 16, RoundingMode.DOWN)
            val amount = minOf(wanted, max)
            if (amount.signum() <= 0) return
            val gross = amount * price; val fee = gross * feeRate
            val next = quantity + amount
            averageCost = (averageCost * quantity + gross + fee).divide(next, 16, RoundingMode.HALF_UP)
            cash -= gross + fee; quantity = next; if (openAt == null) openAt = time
            trades += BacktestTrade(time, PaperOrderSide.BUY, price, amount, fee, reason = reason)
            if (rule.orderTemplate == StrategyOrderTemplate.OCO) bracket =
                price * (BigDecimal.ONE + rule.targetPercent.divide(BigDecimal(100))) to
                    price * (BigDecimal.ONE - rule.stopPercent.divide(BigDecimal(100)))
        }
        fun sell(time: Long, rawPrice: BigDecimal, amount: BigDecimal, reason: String) {
            if (amount.signum() <= 0 || quantity.signum() <= 0) return
            val sold = minOf(amount, quantity); val price = rawPrice * (BigDecimal.ONE - slip)
            val gross = sold * price; val fee = gross * feeRate
            val pnl = gross - fee - averageCost * sold
            cash += gross - fee; quantity -= sold; realized += pnl
            trades += BacktestTrade(time, PaperOrderSide.SELL, price, sold, fee, pnl, reason)
            if (quantity.signum() == 0) { openAt?.let { holdingMillis += time - it }; openAt = null; averageCost = BigDecimal.ZERO; bracket = null }
        }

        ordered.forEachIndexed { index, bar ->
            val close = BigDecimal.valueOf(bar.close); prices += close
            rollingTurnover += BigDecimal.valueOf(bar.volume) * close
            while (dayIndex < index && bar.timestamp - ordered[dayIndex].timestamp > 86_400_000L) {
                rollingTurnover -= BigDecimal.valueOf(ordered[dayIndex].volume * ordered[dayIndex].close); dayIndex++
            }
            bracket?.let { (target, stop) ->
                val hitStop = BigDecimal.valueOf(bar.low) <= stop
                val hitTarget = BigDecimal.valueOf(bar.high) >= target
                if (hitStop || hitTarget) sell(bar.timestamp, if (hitStop) stop else target, quantity,
                    if (hitStop) "OCO 止损" else "OCO 止盈")
            }
            pending?.let { (side, limit, reason) ->
                if (limit in BigDecimal.valueOf(bar.low)..BigDecimal.valueOf(bar.high)) {
                    if (side == PaperOrderSide.BUY) buy(bar.timestamp, limit, reason) else sell(bar.timestamp, limit, rule.budgetUsdt.divide(limit, 16, RoundingMode.DOWN), reason)
                    pending = null
                }
            }
            val base = BigDecimal.valueOf(ordered[dayIndex].close)
            val change = if (base.signum() == 0) BigDecimal.ZERO else (close - base).multiply(BigDecimal(100)).divide(base, 8, RoundingMode.HALF_UP)
            val quote = CryptoQuote(rule.symbol, close, change, BigDecimal.valueOf(bar.high), BigDecimal.valueOf(bar.low),
                rollingTurnover, Instant.ofEpochMilli(bar.timestamp))
            val evaluation = StrategyEvaluator.evaluate(rule, quote, prices, runtime, false, 0, Int.MAX_VALUE, quote.updatedAt)
            runtime = evaluation.runtime
            if (evaluation.crossed) {
                triggers++
                val raw = if (rule.orderTemplate == StrategyOrderTemplate.LIMIT)
                    close * (BigDecimal.ONE + rule.limitOffsetPercent.divide(BigDecimal(100), 12, RoundingMode.HALF_UP)) else close
                val touched = rule.orderTemplate != StrategyOrderTemplate.LIMIT || raw in BigDecimal.valueOf(bar.low)..BigDecimal.valueOf(bar.high)
                if (touched) {
                    if (rule.side == PaperOrderSide.BUY) buy(bar.timestamp, raw, rule.orderTemplate.label)
                    else sell(bar.timestamp, raw, rule.budgetUsdt.divide(raw, 16, RoundingMode.DOWN), rule.orderTemplate.label)
                } else if (rule.orderTemplate == StrategyOrderTemplate.LIMIT && pending == null) pending = Triple(rule.side, raw, "限价延迟成交")
            }
            val equity = cash + quantity * close
            peak = maxOf(peak, equity)
            val drawdown = if (peak.signum() == 0) BigDecimal.ZERO else (peak - equity) * BigDecimal(100) / peak
            maxDrawdown = maxOf(maxDrawdown, drawdown)
            curve += EquityPoint(bar.timestamp, equity, drawdown)
        }
        val last = BigDecimal.valueOf(ordered.last().close)
        val finalEquity = cash + quantity * last
        val total = (finalEquity - config.initialBalance) * BigDecimal(100) / config.initialBalance
        val days = ((ordered.last().timestamp - ordered.first().timestamp).coerceAtLeast(1) / 86_400_000.0).coerceAtLeast(1.0 / 24)
        val ratio = finalEquity.divide(config.initialBalance, 12, RoundingMode.HALF_UP).toDouble().coerceAtLeast(0.000001)
        val annualized = BigDecimal.valueOf((ratio.pow(365.0 / days) - 1.0) * 100).coerceIn(BigDecimal("-1000000"), BigDecimal("1000000"))
        val wins = realized.count { it.signum() > 0 }; val losses = realized.filter { it.signum() < 0 }
        val winRate = if (realized.isEmpty()) BigDecimal.ZERO else BigDecimal(wins * 100).divide(BigDecimal(realized.size), 4, RoundingMode.HALF_UP)
        val gain = realized.filter { it.signum() > 0 }.fold(BigDecimal.ZERO, BigDecimal::add)
        val loss = losses.fold(BigDecimal.ZERO) { a, b -> a + b.abs() }
        val factor = if (loss.signum() == 0) if (gain.signum() > 0) BigDecimal("999") else BigDecimal.ZERO else gain.divide(loss, 4, RoundingMode.HALF_UP)
        var streak = 0; var maxStreak = 0
        realized.forEach { if (it.signum() < 0) { streak++; maxStreak = maxOf(maxStreak, streak) } else streak = 0 }
        return BacktestReport(rule, ordered.size, ordered.first().timestamp, ordered.last().timestamp, config.initialBalance,
            finalEquity, total, annualized, maxDrawdown, winRate, factor,
            if (realized.isEmpty()) 0 else holdingMillis / realized.size / 60_000, maxStreak, triggers, trades, curve)
    }
}

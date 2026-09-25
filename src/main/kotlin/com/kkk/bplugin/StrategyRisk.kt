package com.kkk.bplugin

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

data class PortfolioRiskConfig(
    val maxTotalExposurePercent: Int = 80,
    val maxSymbolExposurePercent: Int = 30,
    val dailyLossLimitPercent: Int = 5,
    val maxDrawdownPercent: Int = 15,
    val maxConsecutiveLosses: Int = 3,
)
data class PortfolioSnapshot(val equity: BigDecimal, val totalExposure: BigDecimal, val symbolExposure: BigDecimal, val duplicateActive: Boolean)
data class PortfolioRiskRuntime(
    val date: String = "", val dailyStartEquity: BigDecimal = BigDecimal.ZERO, val peakEquity: BigDecimal = BigDecimal.ZERO,
    val consecutiveLosses: Int = 0, val pausedReason: String = "",
)
data class PortfolioRiskDecision(val runtime: PortfolioRiskRuntime, val allowed: Boolean, val reason: String = "")

internal object PortfolioRiskEvaluator {
    fun evaluate(config: PortfolioRiskConfig, before: PortfolioRiskRuntime, snapshot: PortfolioSnapshot,
                 addedExposure: BigDecimal, today: String = LocalDate.now().toString()): PortfolioRiskDecision {
        if (snapshot.equity.signum() <= 0) return PortfolioRiskDecision(before, false, "组合权益不可用")
        val reset = before.date != today
        val start = if (reset || before.dailyStartEquity.signum() <= 0) snapshot.equity else before.dailyStartEquity
        val peak = if (reset) snapshot.equity else maxOf(before.peakEquity, snapshot.equity)
        val losses = if (reset) 0 else before.consecutiveLosses
        var runtime = before.copy(date = today, dailyStartEquity = start, peakEquity = peak, consecutiveLosses = losses,
            pausedReason = if (reset) "" else before.pausedReason)
        fun percent(value: BigDecimal, base: BigDecimal) = value.multiply(BigDecimal(100)).divide(base, 8, RoundingMode.HALF_UP)
        val dailyLoss = percent((start - snapshot.equity).max(BigDecimal.ZERO), start)
        val drawdown = percent((peak - snapshot.equity).max(BigDecimal.ZERO), peak)
        val total = percent(snapshot.totalExposure + addedExposure, snapshot.equity)
        val symbol = percent(snapshot.symbolExposure + addedExposure, snapshot.equity)
        val reason = when {
            runtime.pausedReason.isNotBlank() -> runtime.pausedReason
            dailyLoss >= BigDecimal(config.dailyLossLimitPercent) -> "已达到每日最大亏损 ${config.dailyLossLimitPercent}%"
            drawdown >= BigDecimal(config.maxDrawdownPercent) -> "已达到组合最大回撤 ${config.maxDrawdownPercent}%"
            losses >= config.maxConsecutiveLosses -> "已达到连续亏损 ${config.maxConsecutiveLosses} 次"
            snapshot.duplicateActive -> "同一交易对已有策略订单处理中"
            total > BigDecimal(config.maxTotalExposurePercent) -> "组合总敞口将超过 ${config.maxTotalExposurePercent}%"
            symbol > BigDecimal(config.maxSymbolExposurePercent) -> "单币种敞口将超过 ${config.maxSymbolExposurePercent}%"
            else -> ""
        }
        if (reason.isNotBlank() && reason != "同一交易对已有策略订单处理中" && !reason.contains("敞口")) runtime = runtime.copy(pausedReason = reason)
        return PortfolioRiskDecision(runtime, reason.isBlank(), reason)
    }
}

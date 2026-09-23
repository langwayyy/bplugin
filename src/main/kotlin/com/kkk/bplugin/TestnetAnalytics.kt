package com.kkk.bplugin

import java.math.BigDecimal
import java.math.RoundingMode

data class TestnetPerformance(
    val position: BigDecimal = BigDecimal.ZERO,
    val averageCost: BigDecimal = BigDecimal.ZERO,
    val realizedPnl: BigDecimal = BigDecimal.ZERO,
    val unrealizedPnl: BigDecimal = BigDecimal.ZERO,
    val fees: Map<String, BigDecimal> = emptyMap(),
)

data class TestnetRiskPolicy(val maxPositionPercent: Int, val maxNotional: BigDecimal, val maxPriceDeviationPercent: Int)

internal fun validateTestnetRisk(symbol: String, side: PaperOrderSide, quantity: BigDecimal, orderPrice: BigDecimal,
                                 currentPrice: BigDecimal, balances: List<TestnetBalance>, policy: TestnetRiskPolicy): String? {
    val normalized = normalizeMarketSymbol(symbol)
    val notional = quantity * orderPrice
    if (notional > policy.maxNotional) return "订单金额 ${marketPrice(notional, 2)} USDT 超过风控上限 ${marketPrice(policy.maxNotional, 2)} USDT"
    val available = if (side == PaperOrderSide.BUY) balances.firstOrNull { it.asset == "USDT" }?.free ?: BigDecimal.ZERO
    else balances.firstOrNull { it.asset == normalized.removeSuffix("USDT") }?.free ?: BigDecimal.ZERO
    val required = if (side == PaperOrderSide.BUY) notional else quantity
    if (available.signum() <= 0 || required > available) return if (side == PaperOrderSide.BUY) "USDT 可用余额不足" else "可卖资产余额不足"
    val ratio = required.multiply(BigDecimal(100)).divide(available, 4, RoundingMode.HALF_UP)
    if (ratio > BigDecimal(policy.maxPositionPercent)) return "订单占可用余额 ${marketPrice(ratio, 2)}%，超过风控上限 ${policy.maxPositionPercent}%"
    if (currentPrice.signum() > 0) {
        val deviation = orderPrice.subtract(currentPrice).abs().multiply(BigDecimal(100)).divide(currentPrice, 4, RoundingMode.HALF_UP)
        if (deviation > BigDecimal(policy.maxPriceDeviationPercent))
            return "限价偏离市场价 ${marketPrice(deviation, 2)}%，超过风控上限 ${policy.maxPriceDeviationPercent}%"
    }
    return null
}

internal fun analyzeTestnetTrades(symbol: String, trades: List<TestnetTrade>, marketPrice: BigDecimal): TestnetPerformance {
    val normalized = normalizeMarketSymbol(symbol)
    val quote = listOf("USDT", "USDC", "BTC", "ETH").firstOrNull(normalized::endsWith).orEmpty()
    val base = normalized.removeSuffix(quote)
    var position = BigDecimal.ZERO
    var cost = BigDecimal.ZERO
    var realized = BigDecimal.ZERO
    val fees = linkedMapOf<String, BigDecimal>()
    trades.filter { it.symbol == normalized }.sortedBy(TestnetTrade::time).forEach { trade ->
        if (trade.commission.signum() > 0 && trade.commissionAsset.isNotBlank())
            fees[trade.commissionAsset] = fees.getOrDefault(trade.commissionAsset, BigDecimal.ZERO) + trade.commission
        val baseFee = trade.commission.takeIf { trade.commissionAsset == base } ?: BigDecimal.ZERO
        val quoteFee = trade.commission.takeIf { trade.commissionAsset == quote } ?: BigDecimal.ZERO
        if (trade.buyer) {
            val acquired = (trade.quantity - baseFee).max(BigDecimal.ZERO)
            position += acquired
            cost += trade.quoteQuantity + quoteFee
        } else if (position.signum() > 0) {
            val disposed = trade.quantity + baseFee
            val matched = minOf(position, disposed)
            val average = cost.divide(position, 16, RoundingMode.HALF_UP)
            val proceeds = (trade.quoteQuantity - quoteFee).multiply(matched)
                .divide(disposed, 16, RoundingMode.HALF_UP)
            realized += proceeds - average * matched
            position -= matched
            cost -= average * matched
            if (position.signum() == 0) cost = BigDecimal.ZERO
        }
    }
    val average = if (position.signum() > 0) cost.divide(position, 16, RoundingMode.HALF_UP) else BigDecimal.ZERO
    return TestnetPerformance(position.stripTrailingZeros(), average.stripTrailingZeros(), realized.stripTrailingZeros(),
        (position * marketPrice - cost).stripTrailingZeros(), fees.mapValues { it.value.stripTrailingZeros() })
}

internal fun testnetEquityUsdt(balances: List<TestnetBalance>, prices: Map<String, BigDecimal>): BigDecimal =
    balances.fold(BigDecimal.ZERO) { total, balance ->
        total + when (balance.asset) {
            "USDT" -> balance.total
            else -> prices["${balance.asset}USDT"]?.multiply(balance.total) ?: BigDecimal.ZERO
        }
    }

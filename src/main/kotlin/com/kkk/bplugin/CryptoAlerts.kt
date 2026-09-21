package com.kkk.bplugin

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class AlertCondition(val displayName: String) {
    PRICE_ABOVE("价格上穿"),
    PRICE_BELOW("价格下穿"),
    CHANGE_ABOVE("24h涨幅达到"),
    CHANGE_BELOW("24h跌幅达到"),
}

data class CryptoAlertRule(
    val id: String = UUID.randomUUID().toString(),
    val symbol: String,
    val condition: AlertCondition,
    val threshold: BigDecimal,
    val enabled: Boolean = true,
) {
    fun description() = "$symbol ${condition.displayName} ${marketPrice(threshold)}${if (condition.name.startsWith("CHANGE")) "%" else ""}"
}

data class CryptoAlertEvent(val ruleId: String, val symbol: String, val message: String, val triggeredAt: Instant)

internal class CryptoAlertEngine {
    private val previous = mutableMapOf<String, CryptoQuote>()
    private val firedAt = mutableMapOf<String, Instant>()

    fun evaluate(quote: CryptoQuote, rules: List<CryptoAlertRule>, cooldownMinutes: Int, now: Instant = Instant.now()): List<CryptoAlertEvent> {
        val before = previous[quote.symbol]
        if (before != null && !quote.updatedAt.isAfter(before.updatedAt)) return emptyList()
        previous[quote.symbol] = quote
        if (before == null) return emptyList()
        return rules.asSequence().filter { it.enabled && it.symbol == quote.symbol }.filter { rule ->
            val last = firedAt[rule.id]
            last == null || Duration.between(last, now).toMinutes() >= cooldownMinutes
        }.mapNotNull { rule ->
            val crossed = when (rule.condition) {
                AlertCondition.PRICE_ABOVE -> before.price < rule.threshold && quote.price >= rule.threshold
                AlertCondition.PRICE_BELOW -> before.price > rule.threshold && quote.price <= rule.threshold
                AlertCondition.CHANGE_ABOVE -> before.change < rule.threshold && quote.change >= rule.threshold
                AlertCondition.CHANGE_BELOW -> before.change > rule.threshold && quote.change <= rule.threshold
            }
            if (!crossed) null else {
                firedAt[rule.id] = now
                val value = if (rule.condition.name.startsWith("PRICE")) marketPrice(quote.price) else "${marketPrice(quote.change, 2)}%"
                CryptoAlertEvent(rule.id, quote.symbol, "${rule.description()} · 当前 $value", now)
            }
        }.toList()
    }
}

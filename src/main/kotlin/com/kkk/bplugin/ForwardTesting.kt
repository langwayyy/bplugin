package com.kkk.bplugin

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

enum class ForwardStage(val label: String) {
    SHADOW("影子"), PAPER("模拟盘"), TESTNET_MANUAL("测试网手动"), TESTNET_AUTO("测试网自动");
    override fun toString() = label
    fun next(): ForwardStage? = entries.getOrNull(ordinal + 1)
}
enum class ForwardSessionStatus(val label: String) { RUNNING("运行中"), PAUSED("已暂停"), RECOVERY_REQUIRED("待恢复"), COMPLETED("已结束") }
enum class ForwardEventType { SESSION_START, SESSION_PAUSE, SESSION_RESUME, SESSION_END, RECOVERY, HEALTH_PAUSE, MARKET_GAP, SIGNAL, BLOCKED, ORDER, FILL, EXIT, ERROR, PROMOTION }

data class ForwardPoint(val time: Long, val equity: BigDecimal)
data class ForwardSession(
    val id: String = UUID.randomUUID().toString(),
    val strategyId: String,
    val strategyName: String,
    val symbol: String,
    val stage: ForwardStage,
    val ruleSnapshot: CryptoStrategyRule,
    val lineageId: String = id,
    val parentSessionId: String? = null,
    val snapshotHash: String = forwardRuleFingerprint(ruleSnapshot),
    val status: ForwardSessionStatus = ForwardSessionStatus.RUNNING,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    val expectedIntervalMillis: Long = 120_000,
    val initialEquity: BigDecimal = BigDecimal("10000"),
    val currentEquity: BigDecimal = initialEquity,
    val peakEquity: BigDecimal = initialEquity,
    val maxDrawdownPercent: BigDecimal = BigDecimal.ZERO,
    val cash: BigDecimal = initialEquity,
    val quantity: BigDecimal = BigDecimal.ZERO,
    val averageCost: BigDecimal = BigDecimal.ZERO,
    val realizedPnl: BigDecimal = BigDecimal.ZERO,
    val closedTrades: Int = 0,
    val winningTrades: Int = 0,
    val losingTrades: Int = 0,
    val grossProfit: BigDecimal = BigDecimal.ZERO,
    val grossLoss: BigDecimal = BigDecimal.ZERO,
    val totalFees: BigDecimal = BigDecimal.ZERO,
    val totalSlippage: BigDecimal = BigDecimal.ZERO,
    val signals: Int = 0,
    val orders: Int = 0,
    val fills: Int = 0,
    val blocked: Int = 0,
    val errors: Int = 0,
    val consecutiveErrors: Int = 0,
    val healthPauseReason: String = "",
    val monitoringStartedAt: Long = startedAt,
    val lastMarketAt: Long = 0,
    val gapMillis: Long = 0,
    val pendingSide: PaperOrderSide? = null,
    val pendingPrice: BigDecimal? = null,
    val pendingQuantity: BigDecimal = BigDecimal.ZERO,
    val targetPrice: BigDecimal? = null,
    val stopPrice: BigDecimal? = null,
    val executionQuantities: Map<String, String>? = emptyMap(),
    val executionFees: Map<String, String>? = emptyMap(),
    val executionPnls: Map<String, String>? = emptyMap(),
    val terminalExecutions: Set<String>? = emptySet(),
    val equityCurve: List<ForwardPoint> = emptyList(),
)

data class ForwardEvent(
    val id: String = UUID.randomUUID().toString(), val sessionId: String, val time: Long,
    val type: ForwardEventType, val stage: ForwardStage, val strategyId: String, val strategyName: String,
    val symbol: String, val executionId: String = "", val price: BigDecimal? = null,
    val quantity: BigDecimal? = null, val fee: BigDecimal? = null, val pnl: BigDecimal? = null,
    val latencyMillis: Long? = null, val message: String = "",
)

data class ForwardGateConfig(
    val minSignals: Int = 20,
    val maxDrawdownPercent: BigDecimal = BigDecimal("10"),
    val maxErrorRatePercent: BigDecimal = BigDecimal("10"),
    val minUptimePercent: BigDecimal = BigDecimal("95"),
    val minClosedTrades: Int? = 5,
    val minProfitFactor: BigDecimal? = BigDecimal.ONE,
    val minExpectancy: BigDecimal? = BigDecimal.ZERO,
)
data class ForwardHealthPolicy(
    val autoPause: Boolean = true,
    val maxSingleGapMillis: Long = 600_000,
    val maxConsecutiveErrors: Int = 3,
)
internal fun forwardHealthReason(policy: ForwardHealthPolicy, singleGapMillis: Long = 0,
                                 consecutiveErrors: Int = 0): String? = when {
    !policy.autoPause -> null
    singleGapMillis >= policy.maxSingleGapMillis -> "单次行情断档 ${singleGapMillis / 1000} 秒，达到自动暂停阈值"
    consecutiveErrors >= policy.maxConsecutiveErrors -> "连续订单错误 $consecutiveErrors 次，已自动暂停"
    else -> null
}
internal fun forwardStaleMillis(session: ForwardSession, now: Long): Long {
    val reference = session.lastMarketAt.takeIf { it > 0 } ?: maxOf(session.startedAt, session.monitoringStartedAt)
    return (now - reference - session.expectedIntervalMillis).coerceAtLeast(0)
}
internal fun forwardRuleFingerprint(rule: CryptoStrategyRule): String {
    val canonical = listOf(rule.id, rule.name, rule.symbol, rule.condition.name, rule.threshold.toPlainString(),
        rule.fastWindow, rule.slowWindow, rule.action.name, rule.side.name, rule.orderTemplate.name,
        rule.budgetUsdt.toPlainString(), rule.limitOffsetPercent.toPlainString(), rule.targetPercent.toPlainString(),
        rule.stopPercent.toPlainString(), rule.cooldownMinutes, rule.maxExecutionsPerDay, rule.enabled,
        (rule.triggerMode ?: StrategyTriggerMode.INTRABAR).name).joinToString("\u001f")
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
data class ForwardPromotionDecision(val allowed: Boolean, val nextStage: ForwardStage?, val reasons: List<String>)
data class ForwardMetrics(
    val pnl: BigDecimal, val returnPercent: BigDecimal, val uptimePercent: BigDecimal,
    val errorRatePercent: BigDecimal, val fillRatePercent: BigDecimal, val winRatePercent: BigDecimal,
    val profitFactor: BigDecimal, val expectancy: BigDecimal,
)
data class ForwardTransition(val session: ForwardSession, val events: List<ForwardEvent> = emptyList())
data class ForwardExecutionDelta(val quantity: BigDecimal, val fee: BigDecimal, val recordedFee: BigDecimal)
internal fun mergeForwardEvents(recent: List<ForwardEvent>, historical: List<ForwardEvent>, limit: Int = 5_000): List<ForwardEvent> =
    (recent + historical).distinctBy(ForwardEvent::id).sortedByDescending(ForwardEvent::time).take(limit)
internal fun forwardExecutionDelta(previousQuantity: BigDecimal, executedQuantity: BigDecimal,
                                   previousFee: BigDecimal, cumulativeFee: BigDecimal) = ForwardExecutionDelta(
    (executedQuantity - previousQuantity).max(BigDecimal.ZERO),
    (cumulativeFee - previousFee).max(BigDecimal.ZERO),
    maxOf(previousFee, cumulativeFee))

internal object ForwardEngine {
    private val HUNDRED = BigDecimal("100")
    private val BPS = BigDecimal("10000")

    fun market(source: ForwardSession, quote: CryptoQuote, feeBps: Int, slippageBps: Int): ForwardTransition {
        if (source.status != ForwardSessionStatus.RUNNING || quote.updatedAt.toEpochMilli() <= source.lastMarketAt) return ForwardTransition(source)
        val now = quote.updatedAt.toEpochMilli()
        val gap = if (source.lastMarketAt > 0) (now - source.lastMarketAt - source.expectedIntervalMillis).coerceAtLeast(0) else 0
        var session = source.copy(lastMarketAt = now, gapMillis = source.gapMillis + gap)
        val events = mutableListOf<ForwardEvent>()
        if (gap > 0) events += event(session, now, ForwardEventType.MARKET_GAP, quote.price, message = "行情间隔超出预期 ${gap / 1000} 秒")
        if (session.stage == ForwardStage.SHADOW) {
            val pendingPrice = session.pendingPrice
            if (pendingPrice != null && pendingPrice >= quote.low && pendingPrice <= quote.high) {
                session = fill(session, session.pendingSide ?: PaperOrderSide.BUY, pendingPrice, session.pendingQuantity, feeBps, slippageBps, now, events, "影子限价成交")
                    .copy(pendingSide = null, pendingPrice = null, pendingQuantity = BigDecimal.ZERO)
            }
            if (session.quantity.signum() > 0 && session.targetPrice != null && session.stopPrice != null) {
                val hitStop = quote.low <= session.stopPrice
                val hitTarget = quote.high >= session.targetPrice
                if (hitStop || hitTarget) {
                    val raw = if (hitStop) session.stopPrice else session.targetPrice
                    session = fill(session, PaperOrderSide.SELL, requireNotNull(raw), session.quantity, feeBps, slippageBps, now, events,
                        if (hitStop) "影子止损" else "影子止盈").copy(targetPrice = null, stopPrice = null)
                }
            }
        }
        session = mark(session, quote.price, now)
        return ForwardTransition(session, events)
    }

    fun signal(source: ForwardSession, quote: CryptoQuote, executionId: String, feeBps: Int, slippageBps: Int): ForwardTransition {
        var session = source
        val events = mutableListOf<ForwardEvent>()
        if (session.stage != ForwardStage.SHADOW) return ForwardTransition(session, events)
        val rule = session.ruleSnapshot
        val quantity = rule.budgetUsdt.divide(quote.price, 16, RoundingMode.DOWN)
        session = session.copy(orders = session.orders + 1)
        events += event(session, quote.updatedAt.toEpochMilli(), ForwardEventType.ORDER, quote.price, quantity, executionId, message = "影子订单")
        if (rule.orderTemplate == StrategyOrderTemplate.LIMIT) {
            val limit = quote.price.multiply(BigDecimal.ONE + rule.limitOffsetPercent.divide(HUNDRED, 12, RoundingMode.HALF_UP))
            return ForwardTransition(session.copy(pendingSide = rule.side, pendingPrice = limit, pendingQuantity = quantity), events)
        }
        session = fill(session, rule.side, quote.price, quantity, feeBps, slippageBps, quote.updatedAt.toEpochMilli(), events, "影子市价成交")
        if (rule.orderTemplate == StrategyOrderTemplate.OCO && rule.side == PaperOrderSide.BUY && session.quantity.signum() > 0) {
            session = session.copy(
                targetPrice = quote.price.multiply(BigDecimal.ONE + rule.targetPercent.divide(HUNDRED, 12, RoundingMode.HALF_UP)),
                stopPrice = quote.price.multiply(BigDecimal.ONE - rule.stopPercent.divide(HUNDRED, 12, RoundingMode.HALF_UP)))
        }
        return ForwardTransition(mark(session, quote.price, quote.updatedAt.toEpochMilli()), events)
    }

    fun markExternal(source: ForwardSession, equity: BigDecimal, now: Long): ForwardSession {
        if (source.status != ForwardSessionStatus.RUNNING || equity.signum() <= 0) return source
        val peak = maxOf(source.peakEquity, equity)
        val drawdown = if (peak.signum() == 0) BigDecimal.ZERO else (peak - equity).multiply(HUNDRED).divide(peak, 8, RoundingMode.HALF_UP)
        return source.copy(currentEquity = equity, peakEquity = peak, maxDrawdownPercent = maxOf(source.maxDrawdownPercent, drawdown),
            equityCurve = appendPoint(source.equityCurve, ForwardPoint(now, equity)))
    }

    fun metrics(session: ForwardSession, now: Long = System.currentTimeMillis()): ForwardMetrics {
        val pnl = if (session.stage == ForwardStage.SHADOW) session.currentEquity - session.initialEquity else session.realizedPnl
        val returns = if (session.initialEquity.signum() == 0) BigDecimal.ZERO else pnl.multiply(HUNDRED).divide(session.initialEquity, 8, RoundingMode.HALF_UP)
        val elapsed = ((session.endedAt ?: now) - session.startedAt).coerceAtLeast(1)
        val uptime = BigDecimal(elapsed - session.gapMillis.coerceAtMost(elapsed)).multiply(HUNDRED).divide(BigDecimal(elapsed), 4, RoundingMode.HALF_UP)
        val errorRate = if (session.orders == 0) BigDecimal.ZERO else BigDecimal(session.errors).multiply(HUNDRED).divide(BigDecimal(session.orders), 4, RoundingMode.HALF_UP)
        val fillRate = if (session.orders == 0) BigDecimal.ZERO else BigDecimal(session.fills).multiply(HUNDRED).divide(BigDecimal(session.orders), 4, RoundingMode.HALF_UP)
        val winRate = if (session.closedTrades == 0) BigDecimal.ZERO else BigDecimal(session.winningTrades).multiply(HUNDRED)
            .divide(BigDecimal(session.closedTrades), 4, RoundingMode.HALF_UP)
        val profitFactor = when {
            session.grossLoss.signum() > 0 -> session.grossProfit.divide(session.grossLoss, 8, RoundingMode.HALF_UP)
            session.grossProfit.signum() > 0 -> BigDecimal("999")
            else -> BigDecimal.ZERO
        }
        val expectancy = if (session.closedTrades == 0) BigDecimal.ZERO else session.realizedPnl
            .divide(BigDecimal(session.closedTrades), 8, RoundingMode.HALF_UP)
        return ForwardMetrics(pnl, returns, uptime, errorRate, fillRate, winRate, profitFactor, expectancy)
    }

    fun promotion(session: ForwardSession, gate: ForwardGateConfig): ForwardPromotionDecision {
        val next = session.stage.next() ?: return ForwardPromotionDecision(false, null, listOf("已是最高阶段"))
        val metrics = metrics(session)
        val reasons = buildList {
            if (session.status != ForwardSessionStatus.COMPLETED) add("请先结束当前会话")
            if (session.signals < gate.minSignals) add("信号样本 ${session.signals}/${gate.minSignals}")
            val requiredTrades = gate.minClosedTrades ?: 5
            val requiredProfitFactor = gate.minProfitFactor ?: BigDecimal.ONE
            val requiredExpectancy = gate.minExpectancy ?: BigDecimal.ZERO
            if (session.closedTrades < requiredTrades) add("平仓样本 ${session.closedTrades}/$requiredTrades")
            if (metrics.profitFactor < requiredProfitFactor) add("Profit Factor ${metrics.profitFactor} 低于 $requiredProfitFactor")
            if (metrics.expectancy < requiredExpectancy) add("单笔期望 ${metrics.expectancy} 低于 $requiredExpectancy")
            if (session.maxDrawdownPercent > gate.maxDrawdownPercent) add("最大回撤 ${session.maxDrawdownPercent}% 超过 ${gate.maxDrawdownPercent}%")
            if (metrics.errorRatePercent > gate.maxErrorRatePercent) add("错误率 ${metrics.errorRatePercent}% 超过 ${gate.maxErrorRatePercent}%")
            if (metrics.uptimePercent < gate.minUptimePercent) add("数据在线率 ${metrics.uptimePercent}% 低于 ${gate.minUptimePercent}%")
        }
        return ForwardPromotionDecision(reasons.isEmpty(), next, reasons)
    }

    fun attributeFill(source: ForwardSession, side: PaperOrderSide, price: BigDecimal, quantity: BigDecimal,
                      fee: BigDecimal = BigDecimal.ZERO, now: Long = System.currentTimeMillis()): ForwardSession {
        if (quantity.signum() <= 0 || price.signum() <= 0) return source
        if (side == PaperOrderSide.BUY) {
            val nextQuantity = source.quantity + quantity
            val average = (source.averageCost * source.quantity + price * quantity + fee).divide(nextQuantity, 16, RoundingMode.HALF_UP)
            return markExternal(source.copy(cash = source.cash - price * quantity - fee, quantity = nextQuantity,
                averageCost = average, totalFees = source.totalFees + fee), (source.currentEquity - fee).max(BigDecimal.ZERO), now)
        }
        val sold = minOf(quantity, source.quantity)
        if (sold.signum() <= 0) return source
        val pnl = price * sold - fee - source.averageCost * sold
        val remaining = source.quantity - sold
        val next = source.copy(cash = source.cash + price * sold - fee, quantity = remaining,
            averageCost = if (remaining.signum() == 0) BigDecimal.ZERO else source.averageCost,
            realizedPnl = source.realizedPnl + pnl, totalFees = source.totalFees + fee)
        return markExternal(next, source.initialEquity + next.realizedPnl, now)
    }

    fun attributeExecutionPnl(source: ForwardSession, executionId: String, pnlDelta: BigDecimal): ForwardSession {
        val values = source.executionPnls.orEmpty().toMutableMap()
        val total = (values[executionId]?.toBigDecimalOrNull() ?: BigDecimal.ZERO) + pnlDelta
        values[executionId] = total.toPlainString()
        val pnls = values.values.mapNotNull(String::toBigDecimalOrNull)
        return source.copy(executionPnls = values, closedTrades = pnls.size,
            winningTrades = pnls.count { it.signum() > 0 }, losingTrades = pnls.count { it.signum() < 0 },
            grossProfit = pnls.fold(BigDecimal.ZERO) { sum, value -> sum + value.max(BigDecimal.ZERO) },
            grossLoss = pnls.fold(BigDecimal.ZERO) { sum, value -> sum + value.negate().max(BigDecimal.ZERO) })
    }

    fun attributeFee(source: ForwardSession, side: PaperOrderSide, fee: BigDecimal,
                     now: Long = System.currentTimeMillis()): ForwardSession {
        if (fee.signum() <= 0) return source
        val next = if (side == PaperOrderSide.BUY && source.quantity.signum() > 0) {
            source.copy(cash = (source.cash - fee).max(BigDecimal.ZERO),
                averageCost = (source.averageCost * source.quantity + fee).divide(source.quantity, 16, RoundingMode.HALF_UP),
                totalFees = source.totalFees + fee)
        } else source.copy(cash = (source.cash - fee).max(BigDecimal.ZERO), realizedPnl = source.realizedPnl - fee,
            totalFees = source.totalFees + fee)
        return markExternal(next, (source.currentEquity - fee).max(BigDecimal.ZERO), now)
    }

    private fun fill(source: ForwardSession, side: PaperOrderSide, rawPrice: BigDecimal, requested: BigDecimal,
                     feeBps: Int, slippageBps: Int, now: Long, events: MutableList<ForwardEvent>, reason: String): ForwardSession {
        val slip = BigDecimal(slippageBps).divide(BPS, 12, RoundingMode.HALF_UP)
        val price = rawPrice.multiply(if (side == PaperOrderSide.BUY) BigDecimal.ONE + slip else BigDecimal.ONE - slip)
        val feeRate = BigDecimal(feeBps).divide(BPS, 12, RoundingMode.HALF_UP)
        if (side == PaperOrderSide.BUY) {
            val maximum = source.cash.divide(price.multiply(BigDecimal.ONE + feeRate), 16, RoundingMode.DOWN)
            val quantity = minOf(requested, maximum)
            if (quantity.signum() <= 0) return source.copy(blocked = source.blocked + 1).also {
                events += event(it, now, ForwardEventType.BLOCKED, rawPrice, message = "影子账户余额不足")
            }
            val gross = quantity * price; val fee = gross * feeRate; val nextQuantity = source.quantity + quantity
            val average = (source.averageCost * source.quantity + gross + fee).divide(nextQuantity, 16, RoundingMode.HALF_UP)
            val next = source.copy(cash = source.cash - gross - fee, quantity = nextQuantity, averageCost = average,
                fills = source.fills + 1, totalFees = source.totalFees + fee,
                totalSlippage = source.totalSlippage + price.subtract(rawPrice).abs() * quantity)
            events += event(next, now, ForwardEventType.FILL, price, quantity, fee = fee, message = reason)
            return next
        }
        val quantity = minOf(requested, source.quantity)
        if (quantity.signum() <= 0) return source.copy(blocked = source.blocked + 1).also {
            events += event(it, now, ForwardEventType.BLOCKED, rawPrice, message = "影子账户无可卖持仓")
        }
        val gross = quantity * price; val fee = gross * feeRate; val pnl = gross - fee - source.averageCost * quantity
        val remaining = source.quantity - quantity
        val next = withTradeResult(source.copy(cash = source.cash + gross - fee, quantity = remaining,
            averageCost = if (remaining.signum() == 0) BigDecimal.ZERO else source.averageCost,
            realizedPnl = source.realizedPnl + pnl, fills = source.fills + 1, totalFees = source.totalFees + fee,
            totalSlippage = source.totalSlippage + price.subtract(rawPrice).abs() * quantity), pnl)
        events += event(next, now, ForwardEventType.EXIT, price, quantity, fee = fee, pnl = pnl, message = reason)
        return next
    }

    private fun mark(source: ForwardSession, price: BigDecimal, now: Long): ForwardSession {
        val equity = source.cash + source.quantity * price
        return markExternal(source, equity, now)
    }
    private fun withTradeResult(source: ForwardSession, pnl: BigDecimal): ForwardSession = source.copy(
        closedTrades = source.closedTrades + 1,
        winningTrades = source.winningTrades + if (pnl.signum() > 0) 1 else 0,
        losingTrades = source.losingTrades + if (pnl.signum() < 0) 1 else 0,
        grossProfit = source.grossProfit + pnl.max(BigDecimal.ZERO),
        grossLoss = source.grossLoss + pnl.negate().max(BigDecimal.ZERO))
    private fun appendPoint(points: List<ForwardPoint>, point: ForwardPoint): List<ForwardPoint> =
        (if (points.lastOrNull()?.time == point.time) points.dropLast(1) else points).plus(point).takeLast(500)
    private fun event(session: ForwardSession, time: Long, type: ForwardEventType, price: BigDecimal? = null,
                      quantity: BigDecimal? = null, executionId: String = "", fee: BigDecimal? = null,
                      pnl: BigDecimal? = null, message: String = "") = ForwardEvent(sessionId = session.id, time = time,
        type = type, stage = session.stage, strategyId = session.strategyId, strategyName = session.strategyName,
        symbol = session.symbol, executionId = executionId, price = price, quantity = quantity, fee = fee, pnl = pnl, message = message.take(240))
}

internal class ForwardEventJournal(private val root: Path, private val retentionDays: Long = 30) {
    private val gson = Gson()
    @Synchronized fun append(event: ForwardEvent) {
        Files.createDirectories(root)
        val date = Instant.ofEpochMilli(event.time).atZone(ZoneId.systemDefault()).toLocalDate()
        Files.writeString(root.resolve("$date.jsonl"), gson.toJson(event) + System.lineSeparator(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
    fun purge(today: LocalDate = LocalDate.now()) {
        if (!Files.exists(root)) return
        Files.list(root).use { paths -> paths.filter { it.fileName.toString().endsWith(".jsonl") }.forEach { path ->
            val date = path.fileName.toString().removeSuffix(".jsonl").let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (date != null && date.isBefore(today.minusDays(retentionDays))) Files.deleteIfExists(path)
        } }
    }
    @Synchronized fun read(from: LocalDate, to: LocalDate, sessionId: String? = null,
                           types: Set<ForwardEventType> = emptySet(), limit: Int = 5_000): List<ForwardEvent> {
        if (!Files.exists(root) || to.isBefore(from) || limit <= 0) return emptyList()
        val result = mutableListOf<ForwardEvent>()
        val seen = mutableSetOf<String>()
        var date = to
        while (!date.isBefore(from) && result.size < limit) {
            val path = root.resolve("$date.jsonl")
            if (Files.isRegularFile(path)) Files.readAllLines(path, StandardCharsets.UTF_8).asReversed().forEach { line ->
                if (result.size >= limit) return@forEach
                val event = runCatching { gson.fromJson(line, ForwardEvent::class.java) }.getOrNull() ?: return@forEach
                if (event.id !in seen && (sessionId == null || event.sessionId == sessionId) &&
                    (types.isEmpty() || event.type in types)) { seen += event.id; result += event }
            }
            date = date.minusDays(1)
        }
        return result
    }
}

object ForwardReportExporter {
    fun csv(session: ForwardSession, events: List<ForwardEvent>): String = buildString {
        appendLine("time,type,stage,symbol,price,quantity,fee,pnl,latency_ms,message")
        events.filter { it.sessionId == session.id }.sortedBy(ForwardEvent::time).forEach { e ->
            appendLine("${e.time},${e.type},${e.stage},${e.symbol},${e.price ?: ""},${e.quantity ?: ""},${e.fee ?: ""},${e.pnl ?: ""},${e.latencyMillis ?: ""},\"${redactForwardMessage(e.message).replace("\"", "\"\"")}\"")
        }
    }
    fun html(session: ForwardSession, events: List<ForwardEvent>): String {
        fun esc(value: Any?) = value.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val metrics = ForwardEngine.metrics(session)
        val curve = session.equityCurve
        val points = if (curve.isEmpty()) "" else curve.mapIndexed { index, point ->
            val x = if (curve.size <= 1) 0 else index * 900 / (curve.size - 1)
            val min = curve.minOf(ForwardPoint::equity); val max = curve.maxOf(ForwardPoint::equity); val range = (max - min).takeIf { it.signum() > 0 } ?: BigDecimal.ONE
            "$x,${220 - (point.equity - min).multiply(BigDecimal(200)).divide(range, 8, RoundingMode.HALF_UP).toInt()}"
        }.joinToString(" ")
        val rows = events.filter { it.sessionId == session.id }.sortedByDescending(ForwardEvent::time).joinToString("") {
            "<tr><td>${it.time}</td><td>${it.type}</td><td>${esc(redactForwardMessage(it.message))}</td><td>${it.price ?: "—"}</td><td>${it.pnl ?: "—"}</td></tr>"
        }
        return """<!doctype html><html><head><meta charset="utf-8"><title>${esc(session.strategyName)} 前向验证</title><style>body{font:14px sans-serif;margin:28px;color:#222}.metrics{display:grid;grid-template-columns:repeat(3,1fr);gap:10px}.card{background:#f3f5f7;padding:12px;border-radius:6px}table{border-collapse:collapse;width:100%}td,th{border:1px solid #ccc;padding:6px}.audit{font-family:monospace;color:#555}</style></head><body><h1>${esc(session.strategyName)} · ${session.stage.label}</h1><p class="audit">Lineage ${esc(session.lineageId)} · Session ${esc(session.id)} · Snapshot ${esc(session.snapshotHash)}</p><div class="metrics"><div class="card">归因盈亏 ${metrics.pnl}</div><div class="card">收益 ${metrics.returnPercent}%</div><div class="card">回撤 ${session.maxDrawdownPercent}%</div><div class="card">在线率 ${metrics.uptimePercent}%</div><div class="card">胜率 ${metrics.winRatePercent}%</div><div class="card">Profit Factor ${metrics.profitFactor}</div><div class="card">单笔期望 ${metrics.expectancy}</div><div class="card">手续费 ${session.totalFees}</div><div class="card">滑点成本 ${session.totalSlippage}</div></div><h2>资金曲线</h2><svg viewBox="0 0 900 240" width="100%"><polyline fill="none" stroke="#3978b8" stroke-width="2" points="$points"/></svg><h2>事件</h2><table><tr><th>时间</th><th>类型</th><th>说明</th><th>价格</th><th>盈亏</th></tr>$rows</table><p>报告不包含 API Key、Secret 或账户凭据。</p></body></html>"""
    }
}

@Service(Service.Level.APP)
@State(name = "QuietCryptoForwardTesting", storages = [Storage("quiet-crypto-forward.xml")])
class ForwardTestService : PersistentStateComponent<ForwardTestService.StoredState> {
    data class StoredState(var sessionsJson: String = "[]", var eventsJson: String = "[]", var gateJson: String = "",
                           var healthPolicyJson: String = "", var pendingJournalJson: String = "[]")
    private val gson = Gson()
    private var stored = StoredState()
    private var sessions = mutableListOf<ForwardSession>()
    private var events = mutableListOf<ForwardEvent>()
    private var gate = ForwardGateConfig()
    private var healthPolicy = ForwardHealthPolicy()
    private var pendingJournal = mutableListOf<ForwardEvent>()
    private var journalError: String? = null
    private var lastEncodedAt = 0L
    private val journal by lazy { ForwardEventJournal(Path.of(PathManager.getSystemPath(), "quiet-crypto", "forward-events")) }

    init { runCatching { journal.purge() } }
    @Synchronized override fun getState(): StoredState = stored.apply { encode() }
    @Synchronized override fun loadState(state: StoredState) {
        stored = state
        sessions = decodeList<ForwardSession>(state.sessionsJson).take(100).map { session ->
            session.copy(healthPauseReason = runCatching { session.healthPauseReason }.getOrNull().orEmpty(),
                executionQuantities = session.executionQuantities.orEmpty(), terminalExecutions = session.terminalExecutions.orEmpty(),
                executionFees = session.executionFees.orEmpty(),
                executionPnls = session.executionPnls.orEmpty(),
                lineageId = runCatching { session.lineageId }.getOrNull().orEmpty().ifBlank { session.id },
                snapshotHash = runCatching { session.snapshotHash }.getOrNull().orEmpty().ifBlank { forwardRuleFingerprint(session.ruleSnapshot) })
        }.toMutableList()
        events = decodeList<ForwardEvent>(state.eventsJson).take(1000).toMutableList()
        pendingJournal = decodeList<ForwardEvent>(state.pendingJournalJson).take(500).toMutableList()
        gate = runCatching { gson.fromJson(state.gateJson, ForwardGateConfig::class.java) }.getOrNull() ?: ForwardGateConfig()
        healthPolicy = runCatching { gson.fromJson(state.healthPolicyJson, ForwardHealthPolicy::class.java) }.getOrNull() ?: ForwardHealthPolicy()
        val interrupted = sessions.filter { it.status == ForwardSessionStatus.RUNNING }
        interrupted.forEach { source ->
            val next = source.copy(status = ForwardSessionStatus.RECOVERY_REQUIRED, lastMarketAt = 0,
                healthPauseReason = "插件重启后需人工确认恢复")
            replace(next); record(event(next, ForwardEventType.RECOVERY, next.healthPauseReason))
        }
        flushPendingJournal()
        if (interrupted.isNotEmpty() || pendingJournal.isNotEmpty()) encode()
    }
    @Synchronized fun sessions() = sessions.toList()
    @Synchronized fun events() = events.toList()
    fun historicalEvents(sessionId: String? = null, days: Long = 30, limit: Int = 5_000): List<ForwardEvent> {
        val today = LocalDate.now()
        return journal.read(today.minusDays(days.coerceIn(1, 30) - 1), today, sessionId, limit = limit.coerceIn(1, 20_000))
    }
    @Synchronized fun trackedSymbols() = sessions.filter { it.status != ForwardSessionStatus.COMPLETED }.map(ForwardSession::symbol).distinct()
    @Synchronized fun diagnostics(): String {
        val active = sessions.count { it.status != ForwardSessionStatus.COMPLETED }
        val gaps = sessions.sumOf(ForwardSession::gapMillis)
        val recovery = sessions.count { it.status == ForwardSessionStatus.RECOVERY_REQUIRED }
        val unhealthy = sessions.count { it.healthPauseReason.isNotBlank() }
        val journalState = journalError?.let { "日志待重试 ${pendingJournal.size} 条 (${redactForwardMessage(it)})" } ?: "日志正常"
        return "前向验证: $active 个活动会话 / ${sessions.size} 个会话, ${events.size} 条近期事件, 待恢复 $recovery, 健康暂停 $unhealthy, 累计数据缺口 ${gaps / 1000} 秒, $journalState\n"
    }
    @Synchronized fun gate() = gate
    @Synchronized fun healthPolicy() = healthPolicy
    @Synchronized fun setHealthPolicy(value: ForwardHealthPolicy) {
        healthPolicy = value.copy(maxSingleGapMillis = value.maxSingleGapMillis.coerceIn(30_000, 86_400_000),
            maxConsecutiveErrors = value.maxConsecutiveErrors.coerceIn(1, 100)); encode()
    }
    @Synchronized fun setGate(value: ForwardGateConfig) {
        gate = value.copy(minSignals = value.minSignals.coerceIn(1, 10_000),
            maxDrawdownPercent = value.maxDrawdownPercent.coerceIn(BigDecimal("0.1"), BigDecimal("100")),
            maxErrorRatePercent = value.maxErrorRatePercent.coerceIn(BigDecimal.ZERO, BigDecimal("100")),
            minUptimePercent = value.minUptimePercent.coerceIn(BigDecimal.ZERO, BigDecimal("100")),
            minClosedTrades = (value.minClosedTrades ?: 5).coerceIn(1, 10_000),
            minProfitFactor = (value.minProfitFactor ?: BigDecimal.ONE).coerceIn(BigDecimal.ZERO, BigDecimal("999")),
            minExpectancy = (value.minExpectancy ?: BigDecimal.ZERO).coerceIn(BigDecimal("-1000000"), BigDecimal("1000000"))); encode()
    }
    @Synchronized fun sessionFor(strategyId: String): ForwardSession? = sessions.firstOrNull {
        it.strategyId == strategyId && it.status != ForwardSessionStatus.COMPLETED
    }
    @Synchronized fun start(rule: CryptoStrategyRule, stage: ForwardStage, parentSessionId: String? = null,
                            lineageId: String? = null): Result<ForwardSession> = runCatching {
        require(sessionFor(rule.id) == null) { "该策略已有未结束的前向会话" }
        require(sessions.none { it.status != ForwardSessionStatus.COMPLETED && it.symbol == rule.symbol && it.stage == stage }) {
            "同一交易对在该阶段已有活动会话，无法准确归因"
        }
        val now = System.currentTimeMillis()
        val measured = environmentEquity(stage)
        if (stage in setOf(ForwardStage.TESTNET_MANUAL, ForwardStage.TESTNET_AUTO))
            require(measured.signum() > 0) { "测试网资产尚未同步，不能开始前向会话" }
        val equity = measured.takeIf { it.signum() > 0 } ?: BigDecimal("10000")
        val session = ForwardSession(strategyId = rule.id, strategyName = rule.name, symbol = rule.symbol, stage = stage,
            ruleSnapshot = rule, parentSessionId = parentSessionId, lineageId = lineageId ?: UUID.randomUUID().toString(),
            startedAt = now, expectedIntervalMillis = expectedInterval(rule), initialEquity = equity,
            currentEquity = equity, peakEquity = equity, cash = equity, equityCurve = listOf(ForwardPoint(now, equity)))
        sessions.add(0, session); record(event(session, ForwardEventType.SESSION_START, "开始 ${stage.label} 前向验证")); encode(); session
    }
    @Synchronized fun pause(id: String) = changeStatus(id, ForwardSessionStatus.PAUSED, ForwardEventType.SESSION_PAUSE, "会话已暂停")
    @Synchronized fun resume(id: String): Result<ForwardSession> = runCatching {
        val source = sessions.firstOrNull { it.id == id && it.status != ForwardSessionStatus.COMPLETED } ?: error("会话不存在或已结束")
        val quote = CryptoMarketService.getInstance().quotes[source.symbol] ?: error("${source.symbol} 尚无行情，不能恢复")
        val age = System.currentTimeMillis() - quote.updatedAt.toEpochMilli()
        require(age <= maxOf(180_000, healthPolicy.maxSingleGapMillis)) { "${source.symbol} 行情已过期，不能恢复" }
        val unresolved = CryptoStrategyService.getInstance().executions().count {
            it.strategyId == source.strategyId && it.state in setOf(StrategyExecutionState.SUBMITTING, StrategyExecutionState.UNKNOWN)
        }
        require(unresolved == 0) { "仍有 $unresolved 笔结果未知的订单，请先完成订单恢复与对账" }
        if (source.stage in setOf(ForwardStage.TESTNET_MANUAL, ForwardStage.TESTNET_AUTO)) {
            require(CryptoTestnetTradingService.getInstance().hasCredentials()) { "测试网凭据不可用，不能恢复" }
            require(environmentEquity(source.stage).signum() > 0) { "测试网资产尚未同步，不能恢复" }
        }
        val next = source.copy(status = ForwardSessionStatus.RUNNING, lastMarketAt = 0,
            monitoringStartedAt = System.currentTimeMillis(), healthPauseReason = "", consecutiveErrors = 0)
        replace(next); record(event(next, ForwardEventType.SESSION_RESUME, "恢复校验通过，会话已恢复")); encode(); next
    }
    @Synchronized fun finish(id: String): Boolean {
        val session = sessions.firstOrNull { it.id == id && it.status != ForwardSessionStatus.COMPLETED } ?: return false
        replace(session.copy(status = ForwardSessionStatus.COMPLETED, endedAt = System.currentTimeMillis()))
        record(event(session, ForwardEventType.SESSION_END, "会话已结束")); encode(); return true
    }
    @Synchronized fun promotion(id: String): ForwardPromotionDecision = sessions.firstOrNull { it.id == id }
        ?.let { ForwardEngine.promotion(it, gate) } ?: ForwardPromotionDecision(false, null, listOf("会话不存在"))
    @Synchronized fun promote(id: String): Result<ForwardSession> = runCatching {
        val source = sessions.firstOrNull { it.id == id } ?: error("会话不存在")
        val decision = ForwardEngine.promotion(source, gate)
        require(decision.allowed) { decision.reasons.joinToString("；") }
        val next = requireNotNull(decision.nextStage)
        val created = start(source.ruleSnapshot, next, source.id, source.lineageId).getOrThrow()
        record(event(created, ForwardEventType.PROMOTION, "由 ${source.stage.label} 晋级")); encode(); created
    }
    @Synchronized fun onMarket(quote: CryptoQuote, closedCandle: Boolean = false) {
        val safeQuote = if (closedCandle) quote else quote.copy(high = quote.price, low = quote.price)
        sessions.filter { source -> source.status == ForwardSessionStatus.RUNNING && source.symbol == quote.symbol &&
            (((source.ruleSnapshot.triggerMode ?: StrategyTriggerMode.INTRABAR) == StrategyTriggerMode.CLOSE) == closedCandle) }.toList().forEach { source ->
            val transition = ForwardEngine.market(source, safeQuote, settings().paperFeeBps, settings().paperSlippageBps)
            var next = transition.session
            transition.events.forEach(::record)
            val newGap = next.gapMillis - source.gapMillis
            forwardHealthReason(healthPolicy, singleGapMillis = newGap)?.let { reason ->
                next = next.copy(status = ForwardSessionStatus.PAUSED, healthPauseReason = reason, lastMarketAt = 0)
                record(event(next, ForwardEventType.HEALTH_PAUSE, reason))
                CryptoNotifications.warn("前向验证已自动暂停：${next.strategyName}", reason)
            }
            replace(next)
        }
        encode(false)
    }
    @Synchronized fun checkHealth(now: Long = System.currentTimeMillis()) {
        var changed = false
        sessions.filter { it.status == ForwardSessionStatus.RUNNING }.toList().forEach { source ->
            val stale = forwardStaleMillis(source, now)
            forwardHealthReason(healthPolicy, singleGapMillis = stale)?.let { reason ->
                val next = source.copy(status = ForwardSessionStatus.PAUSED, healthPauseReason = reason,
                    gapMillis = source.gapMillis + stale, lastMarketAt = 0)
                replace(next); record(event(next, ForwardEventType.HEALTH_PAUSE, "$reason（健康巡检）"))
                CryptoNotifications.warn("前向验证已自动暂停：${next.strategyName}", reason)
                changed = true
            }
        }
        if (changed) encode()
    }
    @Synchronized fun recordSignal(rule: CryptoStrategyRule, quote: CryptoQuote, executionId: String, ready: Boolean, reason: String?) {
        val source = sessionFor(rule.id) ?: return
        val accepted = ready && source.status == ForwardSessionStatus.RUNNING
        val next = source.copy(signals = source.signals + 1, blocked = source.blocked + if (accepted) 0 else 1)
        replace(next); record(event(next, if (accepted) ForwardEventType.SIGNAL else ForwardEventType.BLOCKED,
            reason ?: if (source.status == ForwardSessionStatus.PAUSED) "前向会话已暂停" else if (accepted) "策略信号" else "信号被阻止",
            executionId, quote.price)); encode()
    }
    @Synchronized fun executeShadow(rule: CryptoStrategyRule, quote: CryptoQuote, executionId: String): Result<String> = runCatching {
        val source = sessionFor(rule.id) ?: error("影子会话不存在")
        require(source.status == ForwardSessionStatus.RUNNING && source.stage == ForwardStage.SHADOW) { "影子会话未运行" }
        val transition = ForwardEngine.signal(source, quote, executionId, settings().paperFeeBps, settings().paperSlippageBps)
        replace(transition.session); transition.events.forEach(::record); encode()
        transition.events.lastOrNull()?.message ?: "影子信号已记录"
    }
    @Synchronized fun recordOrder(strategyId: String, executionId: String, success: Boolean, message: String,
                                  price: BigDecimal?, quantity: BigDecimal?, latencyMillis: Long? = null, filled: Boolean = success,
                                  side: PaperOrderSide? = null, fee: BigDecimal = BigDecimal.ZERO) {
        val source = sessionFor(strategyId) ?: return
        var next = source.copy(orders = source.orders + 1, fills = source.fills + if (filled) 1 else 0,
            errors = source.errors + if (success) 0 else 1,
            consecutiveErrors = if (success) 0 else source.consecutiveErrors + 1)
        if (filled && side != null && price != null && quantity != null) {
            next = ForwardEngine.attributeFill(next, side, price, quantity, fee).copy(
                executionQuantities = next.executionQuantities.orEmpty() + (executionId to quantity.toPlainString()))
            if (side == PaperOrderSide.SELL) next = ForwardEngine.attributeExecutionPnl(next, executionId,
                next.realizedPnl - source.realizedPnl)
        }
        if (!success) next = next.copy(terminalExecutions = next.terminalExecutions.orEmpty() + executionId)
        if (!success) forwardHealthReason(healthPolicy, consecutiveErrors = next.consecutiveErrors)?.let { reason ->
            next = next.copy(status = ForwardSessionStatus.PAUSED, healthPauseReason = reason, lastMarketAt = 0)
            record(event(next, ForwardEventType.HEALTH_PAUSE, reason, executionId, price, quantity))
            CryptoNotifications.warn("前向验证已自动暂停：${next.strategyName}", reason)
        }
        val pnl = (next.realizedPnl - source.realizedPnl).takeIf { filled && side == PaperOrderSide.SELL }
        replace(next); record(event(next, if (!success) ForwardEventType.ERROR else if (filled && side == PaperOrderSide.SELL) ForwardEventType.EXIT
            else if (filled) ForwardEventType.FILL else ForwardEventType.ORDER,
            message, executionId, price, quantity, latencyMillis, pnl)); encode()
    }
    @Synchronized fun recordBlocked(strategyId: String, executionId: String, message: String, price: BigDecimal? = null) {
        val source = sessionFor(strategyId) ?: return
        val next = source.copy(blocked = source.blocked + 1)
        replace(next); record(event(next, ForwardEventType.BLOCKED, message, executionId, price)); encode()
    }
    @Synchronized fun recordExecutionUpdate(strategyId: String, executionId: String, status: String, side: PaperOrderSide,
                                            price: BigDecimal?, executedQuantity: BigDecimal, fee: BigDecimal = BigDecimal.ZERO,
                                            sourceLabel: String = "测试网") {
        val source = sessionFor(strategyId) ?: return
        val previous = source.executionQuantities.orEmpty()[executionId]?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val previousFee = source.executionFees.orEmpty()[executionId]?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val deltaResult = forwardExecutionDelta(previous, executedQuantity, previousFee, fee)
        val delta = deltaResult.quantity
        val feeDelta = deltaResult.fee
        val recordedFee = deltaResult.recordedFee
        var next = source
        if (delta.signum() > 0) {
            next = next.copy(fills = next.fills + if (previous.signum() == 0) 1 else 0,
                executionQuantities = next.executionQuantities.orEmpty() + (executionId to executedQuantity.toPlainString()),
                executionFees = next.executionFees.orEmpty() + (executionId to recordedFee.toPlainString()))
            if (price != null) next = ForwardEngine.attributeFill(next, side, price, delta, feeDelta)
            val pnl = (next.realizedPnl - source.realizedPnl).takeIf { side == PaperOrderSide.SELL }
            if (side == PaperOrderSide.SELL) next = ForwardEngine.attributeExecutionPnl(next, executionId, pnl ?: BigDecimal.ZERO)
            replace(next); record(event(next, if (side == PaperOrderSide.SELL) ForwardEventType.EXIT else ForwardEventType.FILL,
                "$sourceLabel 成交：$status", executionId, price, delta, pnl = pnl, fee = feeDelta))
        } else if (feeDelta.signum() > 0) {
            next = ForwardEngine.attributeFee(next, side, feeDelta).copy(
                executionFees = next.executionFees.orEmpty() + (executionId to recordedFee.toPlainString()))
            if (side == PaperOrderSide.SELL) next = ForwardEngine.attributeExecutionPnl(next, executionId, feeDelta.negate())
            replace(next); record(event(next, if (side == PaperOrderSide.SELL) ForwardEventType.EXIT else ForwardEventType.FILL,
                "$sourceLabel 手续费补记", executionId, price, pnl = feeDelta.negate().takeIf { side == PaperOrderSide.SELL }, fee = feeDelta))
        }
        if (status == "REJECTED" && executionId !in next.terminalExecutions.orEmpty()) {
            next = next.copy(errors = next.errors + 1, consecutiveErrors = next.consecutiveErrors + 1,
                terminalExecutions = next.terminalExecutions.orEmpty() + executionId)
            forwardHealthReason(healthPolicy, consecutiveErrors = next.consecutiveErrors)?.let { reason ->
                next = next.copy(status = ForwardSessionStatus.PAUSED, healthPauseReason = reason, lastMarketAt = 0)
                record(event(next, ForwardEventType.HEALTH_PAUSE, reason, executionId, price))
                CryptoNotifications.warn("前向验证已自动暂停：${next.strategyName}", reason)
            }
            replace(next); record(event(next, ForwardEventType.ERROR, "$sourceLabel 订单被拒绝", executionId, price))
        }
        encode()
    }
    @Synchronized fun clearCompleted() { sessions.removeIf { it.status == ForwardSessionStatus.COMPLETED }; encode() }

    private fun changeStatus(id: String, status: ForwardSessionStatus, type: ForwardEventType, message: String): Boolean {
        val source = sessions.firstOrNull { it.id == id && it.status != ForwardSessionStatus.COMPLETED } ?: return false
        replace(source.copy(status = status, lastMarketAt = if (status == ForwardSessionStatus.RUNNING) 0 else source.lastMarketAt,
            monitoringStartedAt = if (status == ForwardSessionStatus.RUNNING) System.currentTimeMillis() else source.monitoringStartedAt,
            healthPauseReason = if (status == ForwardSessionStatus.RUNNING) "" else source.healthPauseReason,
            consecutiveErrors = if (status == ForwardSessionStatus.RUNNING) 0 else source.consecutiveErrors))
        record(event(source, type, message)); encode(); return true
    }
    private fun replace(value: ForwardSession) { sessions = sessions.map { if (it.id == value.id) value else it }.toMutableList() }
    private fun record(value: ForwardEvent) {
        val safe = value.copy(message = redactForwardMessage(value.message))
        events.add(0, safe); events = events.take(1000).toMutableList()
        pendingJournal += safe
        if (pendingJournal.size > 500) pendingJournal = pendingJournal.takeLast(500).toMutableList()
        flushPendingJournal()
    }
    private fun flushPendingJournal() {
        while (pendingJournal.isNotEmpty()) {
            val next = pendingJournal.first()
            val failure = runCatching { journal.append(next) }.exceptionOrNull()
            if (failure != null) { journalError = failure.message ?: failure.javaClass.simpleName; return }
            pendingJournal.removeAt(0)
        }
        journalError = null
    }
    private fun event(session: ForwardSession, type: ForwardEventType, message: String, executionId: String = "",
                      price: BigDecimal? = null, quantity: BigDecimal? = null, latencyMillis: Long? = null,
                      pnl: BigDecimal? = null, fee: BigDecimal? = null) = ForwardEvent(sessionId = session.id, time = System.currentTimeMillis(), type = type,
        stage = session.stage, strategyId = session.strategyId, strategyName = session.strategyName, symbol = session.symbol,
        executionId = executionId, price = price, quantity = quantity, latencyMillis = latencyMillis, pnl = pnl, fee = fee, message = message.take(240))
    private fun environmentEquity(stage: ForwardStage): BigDecimal = when (stage) {
        ForwardStage.SHADOW -> BigDecimal("10000")
        ForwardStage.PAPER -> CryptoPaperTradingService.getInstance().summary(CryptoMarketService.getInstance().quotes.mapValues { it.value.price }).equity
        ForwardStage.TESTNET_MANUAL, ForwardStage.TESTNET_AUTO -> testnetEquityUsdt(CryptoTestnetTradingService.getInstance().snapshot.balances,
            CryptoMarketService.getInstance().quotes.mapValues { it.value.price })
    }
    private fun expectedInterval(rule: CryptoStrategyRule): Long = if ((rule.triggerMode ?: StrategyTriggerMode.INTRABAR) == StrategyTriggerMode.INTRABAR) 120_000 else when (CryptoSettings.getInstance().period()) {
        KlinePeriod.INTRADAY -> 120_000; KlinePeriod.MINUTE5 -> 600_000; KlinePeriod.MINUTE15 -> 1_800_000; KlinePeriod.MINUTE30 -> 3_600_000
        KlinePeriod.HOUR -> 7_200_000; KlinePeriod.HOUR2 -> 14_400_000; KlinePeriod.HOUR4 -> 28_800_000; KlinePeriod.HOUR6 -> 43_200_000
        KlinePeriod.HOUR12 -> 86_400_000; KlinePeriod.DAY -> 172_800_000; KlinePeriod.WEEK -> 1_209_600_000
    }
    private fun settings() = CryptoSettings.getInstance().state
    private fun encode(force: Boolean = true) {
        val now = System.currentTimeMillis()
        if (!force && now - lastEncodedAt < 5_000) return
        stored.sessionsJson = gson.toJson(sessions.take(100)); stored.eventsJson = gson.toJson(events.take(1000)); stored.gateJson = gson.toJson(gate)
        stored.healthPolicyJson = gson.toJson(healthPolicy)
        stored.pendingJournalJson = gson.toJson(pendingJournal.takeLast(500))
        lastEncodedAt = now
    }
    private inline fun <reified T> decodeList(json: String): List<T> = runCatching {
        gson.fromJson<List<T>>(json, object : TypeToken<List<T>>() {}.type)
    }.getOrDefault(emptyList())

    companion object { fun getInstance() = ApplicationManager.getApplication().getService(ForwardTestService::class.java) }
}

internal fun redactForwardMessage(value: String): String = value
    .replace(Regex("(?i)(api[-_ ]?key|secret)\\s*[:=]\\s*[^\\s,;]+"), "$1=[REDACTED]")
    .take(240)

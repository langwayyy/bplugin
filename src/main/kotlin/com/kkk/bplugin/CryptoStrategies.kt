package com.kkk.bplugin

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class StrategyCondition(val label: String) {
    PRICE_ABOVE("价格上穿"), PRICE_BELOW("价格下穿"), CHANGE_ABOVE("24h涨幅达到"), CHANGE_BELOW("24h跌幅达到"),
    VOLUME_ABOVE("24h成交额上穿"), MA_CROSS_ABOVE("短均线上穿长均线"), MA_CROSS_BELOW("短均线下穿长均线");
    override fun toString() = label
}
enum class StrategyAction(val label: String) {
    NOTIFY("本地提醒"), PAPER("本地模拟下单"), TESTNET_DRAFT("测试网订单草稿"), TESTNET_AUTO("测试网自动下单");
    override fun toString() = label
}
enum class StrategyOrderTemplate(val label: String) {
    MARKET("市价"), LIMIT("限价"), OCO("OCO 止盈止损");
    override fun toString() = label
}

data class CryptoStrategyRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val symbol: String,
    val condition: StrategyCondition,
    val threshold: BigDecimal = BigDecimal.ZERO,
    val fastWindow: Int = 5,
    val slowWindow: Int = 20,
    val action: StrategyAction = StrategyAction.NOTIFY,
    val side: PaperOrderSide = PaperOrderSide.BUY,
    val orderTemplate: StrategyOrderTemplate = StrategyOrderTemplate.MARKET,
    val budgetUsdt: BigDecimal = BigDecimal("100"),
    val limitOffsetPercent: BigDecimal = BigDecimal.ZERO,
    val targetPercent: BigDecimal = BigDecimal("3"),
    val stopPercent: BigDecimal = BigDecimal("2"),
    val cooldownMinutes: Int = 30,
    val maxExecutionsPerDay: Int = 3,
    val enabled: Boolean = true,
) {
    fun description() = "$symbol · ${condition.label}${if (condition in setOf(StrategyCondition.MA_CROSS_ABOVE, StrategyCondition.MA_CROSS_BELOW)) " $fastWindow/$slowWindow" else " ${marketPrice(threshold)}"} · ${action.label}"
}

data class StrategyRuntime(
    val previousMetric: String? = null,
    val previousFastAbove: Boolean? = null,
    val lastTriggeredAt: Long = 0,
    val executionDate: String = "",
    val executionsToday: Int = 0,
)
data class StrategyLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val strategyId: String,
    val strategyName: String,
    val symbol: String,
    val time: Long,
    val status: String,
    val message: String,
    val price: BigDecimal,
    val orderId: String = "",
)
data class StrategyOrderDraft(
    val id: String = UUID.randomUUID().toString(),
    val rule: CryptoStrategyRule,
    val price: BigDecimal,
    val createdAt: Long,
)
data class StrategyEvaluation(val runtime: StrategyRuntime, val crossed: Boolean, val ready: Boolean, val reason: String? = null)

internal object StrategyEvaluator {
    fun evaluate(rule: CryptoStrategyRule, quote: CryptoQuote, history: List<BigDecimal>, before: StrategyRuntime,
                 paused: Boolean, globalExecutions: Int, globalLimit: Int, now: Instant): StrategyEvaluation {
        val metric = when (rule.condition) {
            StrategyCondition.PRICE_ABOVE, StrategyCondition.PRICE_BELOW -> quote.price
            StrategyCondition.CHANGE_ABOVE, StrategyCondition.CHANGE_BELOW -> quote.change
            StrategyCondition.VOLUME_ABOVE -> quote.turnover
            else -> null
        }
        val fastAbove = if (rule.condition in setOf(StrategyCondition.MA_CROSS_ABOVE, StrategyCondition.MA_CROSS_BELOW) &&
            history.size >= rule.slowWindow) {
            val fast = history.takeLast(rule.fastWindow).fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(rule.fastWindow), 16, RoundingMode.HALF_UP)
            val slow = history.takeLast(rule.slowWindow).fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(rule.slowWindow), 16, RoundingMode.HALF_UP)
            fast > slow
        } else null
        val previous = before.previousMetric?.toBigDecimalOrNull()
        val crossed = when (rule.condition) {
            StrategyCondition.PRICE_ABOVE, StrategyCondition.CHANGE_ABOVE, StrategyCondition.VOLUME_ABOVE -> previous != null && previous < rule.threshold && metric!! >= rule.threshold
            StrategyCondition.PRICE_BELOW, StrategyCondition.CHANGE_BELOW -> previous != null && previous > rule.threshold && metric!! <= rule.threshold
            StrategyCondition.MA_CROSS_ABOVE -> before.previousFastAbove == false && fastAbove == true
            StrategyCondition.MA_CROSS_BELOW -> before.previousFastAbove == true && fastAbove == false
        }
        val date = LocalDate.now().toString()
        val count = if (before.executionDate == date) before.executionsToday else 0
        var next = before.copy(previousMetric = metric?.toPlainString(), previousFastAbove = fastAbove,
            executionDate = date, executionsToday = count)
        if (!crossed) return StrategyEvaluation(next, false, false)
        val reason = when {
            paused -> "策略中心已暂停"
            before.lastTriggeredAt > 0 && now.toEpochMilli() - before.lastTriggeredAt < rule.cooldownMinutes * 60_000L -> "策略冷却中"
            count >= rule.maxExecutionsPerDay -> "已达到单策略每日执行上限"
            globalExecutions >= globalLimit -> "已达到全局每日执行上限"
            else -> null
        }
        if (reason == null) next = next.copy(lastTriggeredAt = now.toEpochMilli(), executionsToday = count + 1)
        return StrategyEvaluation(next, true, reason == null, reason)
    }
}

@Service(Service.Level.APP)
@State(name = "QuietCryptoStrategies", storages = [Storage("quiet-crypto-strategies.xml")])
class CryptoStrategyService : PersistentStateComponent<CryptoStrategyService.StoredState> {
    data class StoredState(
        var strategiesJson: String = "[]", var runtimesJson: String = "{}", var logsJson: String = "[]",
        var draftsJson: String = "[]", var historyJson: String = "{}", var quoteTimesJson: String = "{}",
        var paused: Boolean = false, var testnetAutoEnabled: Boolean = false,
        var globalMaxExecutionsPerDay: Int = 20, var maxOpenStrategyOrders: Int = 5,
    )
    private val gson = Gson()
    private var stored = StoredState()
    private var strategies = mutableListOf<CryptoStrategyRule>()
    private var runtimes = mutableMapOf<String, StrategyRuntime>()
    private var logs = mutableListOf<StrategyLogEntry>()
    private var drafts = mutableListOf<StrategyOrderDraft>()
    private var history = mutableMapOf<String, MutableList<BigDecimal>>()
    private var quoteTimes = mutableMapOf<String, Long>()

    @Synchronized override fun getState(): StoredState = stored.apply { encode() }
    @Synchronized override fun loadState(state: StoredState) {
        stored = state.apply {
            globalMaxExecutionsPerDay = globalMaxExecutionsPerDay.coerceIn(1, 500)
            maxOpenStrategyOrders = maxOpenStrategyOrders.coerceIn(1, 100)
        }
        strategies = decodeList<CryptoStrategyRule>(state.strategiesJson).filter { isCryptoSymbol(it.symbol) }.take(100).toMutableList()
        runtimes = decodeMap<StrategyRuntime>(state.runtimesJson).toMutableMap()
        logs = decodeList<StrategyLogEntry>(state.logsJson).take(500).toMutableList()
        drafts = decodeList<StrategyOrderDraft>(state.draftsJson).take(100).toMutableList()
        val rawHistory: Map<String, List<String>> = decodeMap(state.historyJson)
        history = rawHistory.mapValues { (_, values) -> values.mapNotNull(String::toBigDecimalOrNull).takeLast(100).toMutableList() }.toMutableMap()
        quoteTimes = decodeMap<Long>(state.quoteTimesJson).toMutableMap()
    }

    @Synchronized fun strategies(): List<CryptoStrategyRule> = strategies.toList()
    @Synchronized fun logs(): List<StrategyLogEntry> = logs.toList()
    @Synchronized fun drafts(): List<StrategyOrderDraft> = drafts.toList()
    @Synchronized fun isPaused() = stored.paused
    @Synchronized fun isTestnetAutoEnabled() = stored.testnetAutoEnabled
    @Synchronized fun setPaused(value: Boolean) { stored.paused = value; encode() }
    @Synchronized fun setTestnetAutoEnabled(value: Boolean) { stored.testnetAutoEnabled = value; encode() }
    @Synchronized fun limits() = stored.globalMaxExecutionsPerDay to stored.maxOpenStrategyOrders
    @Synchronized fun setLimits(executions: Int, openOrders: Int) {
        stored.globalMaxExecutionsPerDay = executions.coerceIn(1, 500); stored.maxOpenStrategyOrders = openOrders.coerceIn(1, 100); encode()
    }
    @Synchronized fun save(rule: CryptoStrategyRule) {
        val clean = rule.copy(name = rule.name.trim().take(60).ifBlank { "未命名策略" }, symbol = normalizeMarketSymbol(rule.symbol),
            fastWindow = rule.fastWindow.coerceIn(2, 50), slowWindow = rule.slowWindow.coerceIn(3, 100),
            cooldownMinutes = rule.cooldownMinutes.coerceIn(1, 10_080), maxExecutionsPerDay = rule.maxExecutionsPerDay.coerceIn(1, 100),
            budgetUsdt = rule.budgetUsdt.max(BigDecimal("0.01")))
        strategies = (strategies.filterNot { it.id == clean.id } + clean).take(100).toMutableList(); encode()
        CryptoMarketService.getInstance().refresh()
    }
    @Synchronized fun remove(id: String) {
        strategies.removeIf { it.id == id }; runtimes.remove(id); drafts.removeIf { it.rule.id == id }; encode()
        CryptoMarketService.getInstance().refresh()
    }
    @Synchronized fun toggle(id: String) {
        strategies = strategies.map { if (it.id == id) it.copy(enabled = !it.enabled) else it }.toMutableList(); encode()
        CryptoMarketService.getInstance().refresh()
    }
    @Synchronized fun clearLogs() { logs.clear(); encode() }
    @Synchronized fun discardDraft(id: String) { drafts.removeIf { it.id == id }; encode() }

    @Synchronized fun trackedSymbols(): List<String> = strategies.filter(CryptoStrategyRule::enabled).map(CryptoStrategyRule::symbol).distinct()

    @Synchronized fun onQuote(quote: CryptoQuote) {
        if (quoteTimes[quote.symbol]?.let { quote.updatedAt.toEpochMilli() <= it } == true) return
        quoteTimes[quote.symbol] = quote.updatedAt.toEpochMilli()
        val prices = history.getOrPut(quote.symbol) { mutableListOf() }
        prices.add(quote.price); while (prices.size > 100) prices.removeAt(0)
        val today = LocalDate.now().toString()
        var globalExecutions = runtimes.values.filter { it.executionDate == today }.sumOf(StrategyRuntime::executionsToday)
        strategies.filter { it.enabled && it.symbol == quote.symbol }.forEach { rule ->
            val result = StrategyEvaluator.evaluate(rule, quote, prices, runtimes[rule.id] ?: StrategyRuntime(),
                stored.paused, globalExecutions, stored.globalMaxExecutionsPerDay, quote.updatedAt)
            runtimes[rule.id] = result.runtime
            if (result.crossed) {
                if (result.ready) { globalExecutions++; execute(rule, quote) }
                else addLog(rule, quote, "跳过", result.reason.orEmpty())
            }
        }
        encode()
    }

    @Synchronized fun confirmDraft(id: String) {
        val draft = drafts.firstOrNull { it.id == id } ?: return
        drafts.removeIf { it.id == id }
        executeTestnet(draft.rule, draft.price, "草稿确认")
        encode()
    }

    fun replay(rule: CryptoStrategyRule, bars: List<KlineBar>): Int {
        var runtime = StrategyRuntime()
        val prices = mutableListOf<BigDecimal>()
        var count = 0
        bars.sortedBy(KlineBar::timestamp).forEach { bar ->
            val price = BigDecimal.valueOf(bar.close); prices += price
            val quote = CryptoQuote(rule.symbol, price, BigDecimal.ZERO, price, price, BigDecimal.valueOf(bar.volume), Instant.ofEpochMilli(bar.timestamp))
            val evaluation = StrategyEvaluator.evaluate(rule, quote, prices, runtime, false, 0, Int.MAX_VALUE, quote.updatedAt)
            runtime = evaluation.runtime
            if (evaluation.crossed) count++
        }
        return count
    }

    private fun execute(rule: CryptoStrategyRule, quote: CryptoQuote) {
        when (rule.action) {
            StrategyAction.NOTIFY -> {
                CryptoMarketService.getInstance().publishStrategyAlert(rule, quote)
                addLog(rule, quote, "已触发", "条件成立，已显示本地提醒")
            }
            StrategyAction.PAPER -> executePaper(rule, quote)
            StrategyAction.TESTNET_DRAFT -> createDraft(rule, quote, "已生成测试网草稿")
            StrategyAction.TESTNET_AUTO -> if (stored.testnetAutoEnabled) executeTestnet(rule, quote.price, "自动执行")
                else createDraft(rule, quote, "测试网自动交易未开启，已生成草稿")
        }
    }
    private fun executePaper(rule: CryptoStrategyRule, quote: CryptoQuote) {
        val open = CryptoPaperTradingService.getInstance().account().orders.count { it.status == PaperOrderStatus.OPEN }
        if (open >= stored.maxOpenStrategyOrders) return addLog(rule, quote, "跳过", "已达到最大未完成订单数")
        if (rule.orderTemplate == StrategyOrderTemplate.OCO) return addLog(rule, quote, "失败", "本地模拟盘暂不支持 OCO")
        val quantity = rule.budgetUsdt.divide(quote.price, 16, RoundingMode.DOWN)
        val type = if (rule.orderTemplate == StrategyOrderTemplate.MARKET) PaperOrderType.MARKET else PaperOrderType.LIMIT
        val limit = if (type == PaperOrderType.LIMIT) offsetPrice(quote.price, rule.limitOffsetPercent) else null
        val result = CryptoPaperTradingService.getInstance().place(rule.symbol, rule.side, type, quantity, limit, quote.price)
        addLog(rule, quote, if (result.accepted) "已执行" else "失败", result.message, result.order?.id.orEmpty())
    }
    private fun createDraft(rule: CryptoStrategyRule, quote: CryptoQuote, message: String) {
        drafts.add(0, StrategyOrderDraft(rule = rule, price = quote.price, createdAt = quote.updatedAt.toEpochMilli()))
        drafts = drafts.distinctBy { "${it.rule.id}:${it.price}:${it.createdAt}" }.take(100).toMutableList()
        addLog(rule, quote, "待确认", message)
    }
    private fun executeTestnet(rule: CryptoStrategyRule, price: BigDecimal, source: String) {
        val quote = CryptoQuote(rule.symbol, price, BigDecimal.ZERO, price, price, BigDecimal.ZERO, Instant.now())
        val open = CryptoTestnetTradingService.getInstance().snapshot.openOrders.size
        if (open >= stored.maxOpenStrategyOrders) return addLog(rule, quote, "跳过", "已达到最大未完成订单数")
        val rawQuantity = rule.budgetUsdt.divide(price, 16, RoundingMode.DOWN)
        if (rule.orderTemplate == StrategyOrderTemplate.OCO) {
            val target = if (rule.side == PaperOrderSide.SELL) percent(price, rule.targetPercent) else percent(price, -rule.targetPercent)
            val stop = if (rule.side == PaperOrderSide.SELL) percent(price, -rule.stopPercent) else percent(price, rule.stopPercent)
            val stopLimit = if (rule.side == PaperOrderSide.SELL) percent(stop, BigDecimal("-0.1")) else percent(stop, BigDecimal("0.1"))
            CryptoTestnetTradingService.getInstance().prepareOco(rule.symbol, rawQuantity, target, stop, stopLimit) { prepared ->
                prepared.onFailure { addLog(rule, quote, "失败", "$source：${it.message}") }
                prepared.onSuccess { draft ->
                    CryptoTestnetTradingService.getInstance().placeOco(rule.symbol, rule.side, draft.quantity,
                        draft.targetPrice, draft.stopPrice, draft.stopLimitPrice) { result ->
                        addLog(rule, quote, if (result.isSuccess) "已执行" else "失败", "$source：${result.fold({ "OCO 已提交" }, { it.message.orEmpty() })}", result.getOrNull()?.id?.toString().orEmpty())
                    }
                }
            }
            return
        }
        val sizingType = if (rule.orderTemplate == StrategyOrderTemplate.MARKET) PaperOrderType.MARKET else PaperOrderType.LIMIT
        val limit = if (sizingType == PaperOrderType.LIMIT) offsetPrice(price, rule.limitOffsetPercent) else null
        CryptoTestnetTradingService.getInstance().prepareOrder(rule.symbol, rule.side, sizingType, rawQuantity, limit) { prepared ->
            prepared.onFailure { addLog(rule, quote, "失败", "$source：${it.message}") }
            prepared.onSuccess { draft ->
                when (rule.orderTemplate) {
                    StrategyOrderTemplate.MARKET, StrategyOrderTemplate.LIMIT -> CryptoTestnetTradingService.getInstance().place(
                        rule.symbol, rule.side, sizingType, draft.quantity, draft.price) { result ->
                        addLog(rule, quote, if (result.isSuccess) "已执行" else "失败", "$source：${result.fold({ "订单已提交" }, { it.message.orEmpty() })}", result.getOrNull()?.id?.toString().orEmpty())
                    }
                    StrategyOrderTemplate.OCO -> Unit
                }
            }
        }
    }
    @Synchronized private fun addLog(rule: CryptoStrategyRule, quote: CryptoQuote, status: String, message: String, orderId: String = "") {
        logs.add(0, StrategyLogEntry(strategyId = rule.id, strategyName = rule.name, symbol = rule.symbol,
            time = quote.updatedAt.toEpochMilli(), status = status, message = message, price = quote.price, orderId = orderId))
        logs = logs.take(500).toMutableList(); encode()
    }
    private fun offsetPrice(price: BigDecimal, offset: BigDecimal) = percent(price, offset)
    private fun percent(price: BigDecimal, value: BigDecimal) = price.multiply(BigDecimal.ONE + value.divide(BigDecimal(100), 12, RoundingMode.HALF_UP))
    private fun encode() {
        stored.strategiesJson = gson.toJson(strategies); stored.runtimesJson = gson.toJson(runtimes); stored.logsJson = gson.toJson(logs)
        stored.draftsJson = gson.toJson(drafts); stored.historyJson = gson.toJson(history.mapValues { it.value.map(BigDecimal::toPlainString) })
        stored.quoteTimesJson = gson.toJson(quoteTimes)
    }
    private inline fun <reified T> decodeList(json: String): List<T> = runCatching {
        gson.fromJson<List<T>>(json, object : TypeToken<List<T>>() {}.type)
    }.getOrDefault(emptyList())
    private inline fun <reified V> decodeMap(json: String): Map<String, V> = runCatching {
        gson.fromJson<Map<String, V>>(json, object : TypeToken<Map<String, V>>() {}.type)
    }.getOrDefault(emptyMap())
    companion object { fun getInstance() = ApplicationManager.getApplication().getService(CryptoStrategyService::class.java) }
}

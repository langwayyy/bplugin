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
import java.time.ZoneId
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
enum class StrategyTriggerMode(val label: String) { INTRABAR("盘中触发"), CLOSE("收盘确认"); override fun toString() = label }

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
    val triggerMode: StrategyTriggerMode? = StrategyTriggerMode.INTRABAR,
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
    val executionId: String? = null,
)
data class StrategyOrderDraft(
    val id: String = UUID.randomUUID().toString(),
    val rule: CryptoStrategyRule,
    val price: BigDecimal,
    val createdAt: Long,
    val executionId: String? = null,
)
enum class StrategyExecutionState { TRIGGERED, WAITING_CONFIRMATION, SUBMITTING, COMPLETED, FAILED, BLOCKED, UNKNOWN }
data class StrategyExecution(val id: String, val strategyId: String, val strategyName: String, val symbol: String,
                             val createdAt: Long, val state: StrategyExecutionState, val orderId: String = "", val message: String = "",
                             val clientOrderId: String = "")
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
        val date = now.atZone(ZoneId.systemDefault()).toLocalDate().toString()
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
    data class StrategyExport(val version: Int = 1, val exportedAt: Long = System.currentTimeMillis(), val strategies: List<CryptoStrategyRule>)
    data class StoredState(
        var strategiesJson: String = "[]", var runtimesJson: String = "{}", var logsJson: String = "[]",
        var draftsJson: String = "[]", var historyJson: String = "{}", var quoteTimesJson: String = "{}", var executionsJson: String = "[]",
        var paused: Boolean = false, var testnetAutoEnabled: Boolean = false,
        var globalMaxExecutionsPerDay: Int = 20, var maxOpenStrategyOrders: Int = 5,
        var portfolioRiskJson: String = "", var portfolioRiskRuntimeJson: String = "",
    )
    private val gson = Gson()
    private var stored = StoredState()
    private var strategies = mutableListOf<CryptoStrategyRule>()
    private var runtimes = mutableMapOf<String, StrategyRuntime>()
    private var logs = mutableListOf<StrategyLogEntry>()
    private var drafts = mutableListOf<StrategyOrderDraft>()
    private var history = mutableMapOf<String, MutableList<BigDecimal>>()
    private var quoteTimes = mutableMapOf<String, Long>()
    private var executions = mutableListOf<StrategyExecution>()
    private val candleBars = mutableMapOf<String, MutableList<KlineBar>>()
    private val recoveryRequested = mutableSetOf<String>()
    private var portfolioRisk = PortfolioRiskConfig()
    private var portfolioRuntime = PortfolioRiskRuntime()

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
        executions = decodeList<StrategyExecution>(state.executionsJson).map {
            if (it.state == StrategyExecutionState.SUBMITTING) it.copy(state = StrategyExecutionState.UNKNOWN, message = "插件重启时订单结果未知，已阻止重复提交") else it
        }.take(500).toMutableList()
        portfolioRisk = runCatching { gson.fromJson(state.portfolioRiskJson, PortfolioRiskConfig::class.java) }.getOrNull() ?: PortfolioRiskConfig()
        portfolioRuntime = runCatching { gson.fromJson(state.portfolioRiskRuntimeJson, PortfolioRiskRuntime::class.java) }.getOrNull() ?: PortfolioRiskRuntime()
    }

    @Synchronized fun strategies(): List<CryptoStrategyRule> = strategies.toList()
    @Synchronized fun logs(): List<StrategyLogEntry> = logs.toList()
    @Synchronized fun drafts(): List<StrategyOrderDraft> = drafts.toList()
    @Synchronized fun executions(): List<StrategyExecution> = executions.toList()
    @Synchronized fun runtime(id: String): StrategyRuntime? = runtimes[id]
    @Synchronized fun lastChecked(symbol: String): Long? = quoteTimes[symbol]
    @Synchronized fun diagnostics(): String = buildString {
        append(CryptoMarketService.getInstance().diagnostics())
        val testnet = CryptoTestnetTradingService.getInstance()
        appendLine("测试网凭据: ${if (testnet.hasCredentials()) "已配置" else "未配置"}")
        val snapshotAge = testnet.snapshot.updatedAt?.let { java.time.Duration.between(it, Instant.now()).seconds.coerceAtLeast(0) }
        appendLine("测试网快照: ${testnet.snapshot.updatedAt ?: "—"}${snapshotAge?.let { " (${it}秒前)" }.orEmpty()}, 未完成订单: ${testnet.snapshot.openOrders.size}, 订单组: ${testnet.snapshot.orderLists.size}")
        appendLine("测试网订单账本: ${testnet.managedOrders().size}, 待对账: ${testnet.managedOrders().count { it.state == ManagedOrderState.UNCERTAIN }}, 最后对账: ${testnet.lastReconciledAt().takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) } ?: "—"}")
        appendLine("测试网安全状态: ${if (testnet.tradingEnabled()) "允许新订单" else "已停止新订单"}${if (testnet.closeOnly()) "，仅减仓" else ""}")
        appendLine("策略: ${strategies.size}（启用 ${strategies.count(CryptoStrategyRule::enabled)}）, 待确认: ${drafts.size}")
        appendLine("执行: ${executions.size}, 结果未知: ${executions.count { it.state == StrategyExecutionState.UNKNOWN }}")
        appendLine("全局暂停: ${stored.paused}, 自动测试网: ${stored.testnetAutoEnabled}")
        appendLine("组合风控: ${portfolioRuntime.pausedReason.ifBlank { "正常" }}, 连续亏损: ${portfolioRuntime.consecutiveLosses}")
        append(ForwardTestService.getInstance().diagnostics())
        appendLine("说明: 诊断信息不包含 API Key、Secret 或 PasswordSafe 内容")
    }
    @Synchronized fun reconcileTestnetExecutions() {
        val trading = CryptoTestnetTradingService.getInstance()
        val snapshot = trading.snapshot
        val updated = executions.map { execution ->
            if (execution.state !in setOf(StrategyExecutionState.SUBMITTING, StrategyExecutionState.UNKNOWN) || execution.clientOrderId.isBlank()) execution
            else {
                val order = (snapshot.openOrders + snapshot.history).firstOrNull { it.clientOrderId == execution.clientOrderId || it.clientOrderId.startsWith("${execution.clientOrderId}-") }
                val list = snapshot.orderLists.firstOrNull { it.clientOrderId == execution.clientOrderId }
                when {
                    order != null -> {
                        ForwardTestService.getInstance().recordExecutionUpdate(execution.strategyId, execution.id, order.status, order.side,
                            order.averagePrice ?: order.price.takeIf { it.signum() > 0 }, order.executedQuantity)
                        execution.copy(state = StrategyExecutionState.COMPLETED, orderId = order.id.toString(), message = "已通过客户端订单 ID 恢复")
                    }
                    list != null -> execution.copy(state = StrategyExecutionState.COMPLETED, orderId = list.id.toString(), message = "已通过客户端订单 ID 恢复 OCO")
                    else -> execution
                }
            }
        }.toMutableList()
        if (updated != executions) { executions = updated; encode() }
        if (trading.hasCredentials()) updated.filter { it.state == StrategyExecutionState.UNKNOWN && it.clientOrderId.isNotBlank() }
            .map(StrategyExecution::symbol).distinct().filter(recoveryRequested::add).forEach(trading::refresh)
    }
    @Synchronized fun isPaused() = stored.paused
    @Synchronized fun isTestnetAutoEnabled() = stored.testnetAutoEnabled
    @Synchronized fun setPaused(value: Boolean) { stored.paused = value; encode() }
    @Synchronized fun setTestnetAutoEnabled(value: Boolean) { stored.testnetAutoEnabled = value; encode() }
    @Synchronized fun limits() = stored.globalMaxExecutionsPerDay to stored.maxOpenStrategyOrders
    @Synchronized fun portfolioRisk() = portfolioRisk
    @Synchronized fun portfolioRuntime() = portfolioRuntime
    @Synchronized fun setPortfolioRisk(value: PortfolioRiskConfig) { portfolioRisk = value.copy(
        maxTotalExposurePercent = value.maxTotalExposurePercent.coerceIn(1, 100), maxSymbolExposurePercent = value.maxSymbolExposurePercent.coerceIn(1, 100),
        dailyLossLimitPercent = value.dailyLossLimitPercent.coerceIn(1, 100), maxDrawdownPercent = value.maxDrawdownPercent.coerceIn(1, 100),
        maxConsecutiveLosses = value.maxConsecutiveLosses.coerceIn(1, 100)); encode() }
    @Synchronized fun resumePortfolioRisk() { portfolioRuntime = portfolioRuntime.copy(pausedReason = "", consecutiveLosses = 0); encode() }
    @Synchronized fun setLimits(executions: Int, openOrders: Int) {
        stored.globalMaxExecutionsPerDay = executions.coerceIn(1, 500); stored.maxOpenStrategyOrders = openOrders.coerceIn(1, 100); encode()
    }
    @Synchronized fun save(rule: CryptoStrategyRule) {
        val clean = rule.copy(name = rule.name.trim().take(60).ifBlank { "未命名策略" }, symbol = normalizeMarketSymbol(rule.symbol),
            fastWindow = rule.fastWindow.coerceIn(2, 50), slowWindow = rule.slowWindow.coerceIn(3, 100),
            cooldownMinutes = rule.cooldownMinutes.coerceIn(1, 10_080), maxExecutionsPerDay = rule.maxExecutionsPerDay.coerceIn(1, 100),
            budgetUsdt = rule.budgetUsdt.max(BigDecimal("0.01")), triggerMode = rule.triggerMode ?: StrategyTriggerMode.INTRABAR)
        strategies = (strategies.filterNot { it.id == clean.id } + clean).take(100).toMutableList(); encode()
        CryptoMarketService.getInstance().refresh()
    }
    @Synchronized fun remove(id: String) {
        ForwardTestService.getInstance().sessionFor(id)?.let { ForwardTestService.getInstance().finish(it.id) }
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
    @Synchronized fun exportRules(): String = gson.toJson(StrategyExport(strategies = strategies))
    @Synchronized fun importRules(json: String): Result<Int> = runCatching {
        val bundle = gson.fromJson(json, StrategyExport::class.java) ?: error("策略文件为空")
        require(bundle.version == 1) { "不支持的策略文件版本 ${bundle.version}" }
        require(bundle.strategies.size <= 100) { "策略数量超过 100" }
        val valid = bundle.strategies.filter { isCryptoSymbol(it.symbol) && it.budgetUsdt.signum() > 0 && it.fastWindow in 2..50 && it.slowWindow in 3..100 && it.fastWindow < it.slowWindow }
        require(valid.size == bundle.strategies.size) { "策略文件包含无效参数" }
        val existing = strategies.map(CryptoStrategyRule::id).toMutableSet()
        val imported = valid.map { rule -> (if (rule.id in existing) rule.copy(id = UUID.randomUUID().toString()) else rule)
            .copy(triggerMode = rule.triggerMode ?: StrategyTriggerMode.INTRABAR) }.take(100 - strategies.size)
        strategies.addAll(imported); encode(); CryptoMarketService.getInstance().refresh(); imported.size
    }

    @Synchronized fun onQuote(quote: CryptoQuote) {
        if (quoteTimes[quote.symbol]?.let { quote.updatedAt.toEpochMilli() <= it } == true) return
        quoteTimes[quote.symbol] = quote.updatedAt.toEpochMilli()
        ForwardTestService.getInstance().onMarket(quote, false)
        val prices = history.getOrPut(quote.symbol) { mutableListOf() }
        prices.add(quote.price); while (prices.size > 100) prices.removeAt(0)
        val today = LocalDate.now().toString()
        var globalExecutions = runtimes.values.filter { it.executionDate == today }.sumOf(StrategyRuntime::executionsToday)
        effectiveStrategies().filter { it.enabled && it.symbol == quote.symbol && (it.triggerMode ?: StrategyTriggerMode.INTRABAR) == StrategyTriggerMode.INTRABAR }.forEach { rule ->
            val result = StrategyEvaluator.evaluate(rule, quote, prices, runtimes[rule.id] ?: StrategyRuntime(),
                stored.paused, globalExecutions, stored.globalMaxExecutionsPerDay, quote.updatedAt)
            runtimes[rule.id] = result.runtime
            if (result.crossed) {
                val executionId = UUID.randomUUID().toString()
                executions.add(0, StrategyExecution(executionId, rule.id, rule.name, rule.symbol, quote.updatedAt.toEpochMilli(),
                    if (result.ready) StrategyExecutionState.TRIGGERED else StrategyExecutionState.BLOCKED, message = result.reason.orEmpty()))
                executions = executions.take(500).toMutableList()
                ForwardTestService.getInstance().recordSignal(rule, quote, executionId, result.ready, result.reason)
                if (result.ready) {
                    val risk = checkPortfolioRisk(rule)
                    if (risk == null) { globalExecutions++; execute(rule, quote, executionId) }
                    else { updateExecution(executionId, StrategyExecutionState.BLOCKED, risk); ForwardTestService.getInstance().recordBlocked(rule.id, executionId, risk, quote.price); addLog(rule, quote, "风控阻止", risk, executionId = executionId) }
                }
                else addLog(rule, quote, "跳过", result.reason.orEmpty(), executionId = executionId)
            }
        }
        encode()
    }

    @Synchronized fun onClosedCandle(symbol: String, bar: KlineBar) {
        val matching = effectiveStrategies().filter { it.enabled && it.symbol == symbol && (it.triggerMode ?: StrategyTriggerMode.INTRABAR) == StrategyTriggerMode.CLOSE }
        if (matching.isEmpty()) return
        val persistedPrices = history["candle:$symbol"].orEmpty()
        val bars = candleBars.getOrPut(symbol) { mutableListOf() }
        bars.removeIf { it.timestamp == bar.timestamp }; bars.add(bar); bars.sortBy(KlineBar::timestamp)
        val cutoff = bar.timestamp - 86_400_000L
        bars.removeIf { it.timestamp < cutoff }; while (bars.size > 1_500) bars.removeAt(0)
        val prices = if (bars.size == 1 && persistedPrices.isNotEmpty()) (persistedPrices + BigDecimal.valueOf(bar.close)).takeLast(100).toMutableList()
            else bars.takeLast(100).map { BigDecimal.valueOf(it.close) }.toMutableList()
        history["candle:$symbol"] = prices
        val today = Instant.ofEpochMilli(bar.timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        var globalExecutions = runtimes.values.filter { it.executionDate == today }.sumOf(StrategyRuntime::executionsToday)
        val base = BigDecimal.valueOf(bars.firstOrNull()?.close ?: bar.close)
        val close = BigDecimal.valueOf(bar.close)
        val change = if (base.signum() == 0) BigDecimal.ZERO else (close - base).multiply(BigDecimal(100)).divide(base, 8, RoundingMode.HALF_UP)
        val quote = CryptoQuote(symbol, close, change, BigDecimal.valueOf(bar.high), BigDecimal.valueOf(bar.low),
            bars.fold(BigDecimal.ZERO) { total, item -> total + BigDecimal.valueOf(item.volume * item.close) }, Instant.ofEpochMilli(bar.timestamp))
        ForwardTestService.getInstance().onMarket(quote, true)
        matching.forEach { rule ->
            val result = StrategyEvaluator.evaluate(rule, quote, prices, runtimes[rule.id] ?: StrategyRuntime(),
                stored.paused, globalExecutions, stored.globalMaxExecutionsPerDay, quote.updatedAt)
            runtimes[rule.id] = result.runtime
            if (result.crossed) {
                val executionId = UUID.randomUUID().toString()
                executions.add(0, StrategyExecution(executionId, rule.id, rule.name, rule.symbol, quote.updatedAt.toEpochMilli(),
                    if (result.ready) StrategyExecutionState.TRIGGERED else StrategyExecutionState.BLOCKED, message = result.reason.orEmpty()))
                executions = executions.take(500).toMutableList()
                ForwardTestService.getInstance().recordSignal(rule, quote, executionId, result.ready, result.reason)
                if (result.ready) {
                    val risk = checkPortfolioRisk(rule)
                    if (risk == null) { globalExecutions++; execute(rule, quote, executionId) }
                    else { updateExecution(executionId, StrategyExecutionState.BLOCKED, risk); ForwardTestService.getInstance().recordBlocked(rule.id, executionId, risk, quote.price); addLog(rule, quote, "风控阻止", risk, executionId = executionId) }
                }
                else addLog(rule, quote, "跳过", result.reason.orEmpty(), executionId = executionId)
            }
        }
        encode()
    }

    @Synchronized fun confirmDraft(id: String) {
        val draft = drafts.firstOrNull { it.id == id } ?: return
        drafts.removeIf { it.id == id }
        val risk = checkPortfolioRisk(draft.rule.copy(action = StrategyAction.TESTNET_AUTO))
        if (risk != null) {
            val quote = CryptoQuote(draft.rule.symbol, draft.price, BigDecimal.ZERO, draft.price, draft.price, BigDecimal.ZERO, Instant.now())
            draft.executionId?.let { updateExecution(it, StrategyExecutionState.BLOCKED, risk) }
            addLog(draft.rule, quote, "风控阻止", risk, executionId = draft.executionId); encode(); return
        }
        executeTestnet(draft.rule, draft.price, "草稿确认", draft.executionId ?: UUID.randomUUID().toString())
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

    private fun execute(rule: CryptoStrategyRule, quote: CryptoQuote, executionId: String) {
        val forward = ForwardTestService.getInstance()
        val session = forward.sessionFor(rule.id)
        if (session != null) {
            if (session.status == ForwardSessionStatus.PAUSED) {
                updateExecution(executionId, StrategyExecutionState.BLOCKED, "前向验证会话已暂停")
                return addLog(rule, quote, "跳过", "前向验证会话已暂停", executionId = executionId)
            }
            when (session.stage) {
                ForwardStage.SHADOW -> {
                    val result = forward.executeShadow(rule, quote, executionId)
                    val message = result.getOrElse { it.message ?: "影子执行失败" }
                    updateExecution(executionId, if (result.isSuccess) StrategyExecutionState.COMPLETED else StrategyExecutionState.FAILED, message)
                    addLog(rule, quote, if (result.isSuccess) "影子执行" else "失败", message, executionId = executionId)
                }
                ForwardStage.PAPER -> executePaper(rule, quote, executionId)
                ForwardStage.TESTNET_MANUAL -> createDraft(rule, quote, "前向验证：已生成测试网草稿", executionId)
                ForwardStage.TESTNET_AUTO -> if (stored.testnetAutoEnabled) executeTestnet(rule, quote.price, "前向自动执行", executionId)
                    else createDraft(rule, quote, "测试网自动交易未开启，已生成草稿", executionId)
            }
            return
        }
        when (rule.action) {
            StrategyAction.NOTIFY -> {
                CryptoMarketService.getInstance().publishStrategyAlert(rule, quote)
                CryptoNotifications.info("策略已触发：${rule.name}", "${rule.symbol} 当前 ${marketPrice(quote.price)}")
                updateExecution(executionId, StrategyExecutionState.COMPLETED, "本地提醒已显示")
                addLog(rule, quote, "已触发", "条件成立，已显示本地提醒", executionId = executionId)
            }
            StrategyAction.PAPER -> executePaper(rule, quote, executionId)
            StrategyAction.TESTNET_DRAFT -> createDraft(rule, quote, "已生成测试网草稿", executionId)
            StrategyAction.TESTNET_AUTO -> if (stored.testnetAutoEnabled) executeTestnet(rule, quote.price, "自动执行", executionId)
                else createDraft(rule, quote, "测试网自动交易未开启，已生成草稿", executionId)
        }
    }
    private fun executePaper(rule: CryptoStrategyRule, quote: CryptoQuote, executionId: String) {
        val beforeRealized = CryptoPaperTradingService.getInstance().account().realizedPnl
        val open = CryptoPaperTradingService.getInstance().account().orders.count { it.status == PaperOrderStatus.OPEN }
        if (open >= stored.maxOpenStrategyOrders) { updateExecution(executionId, StrategyExecutionState.BLOCKED, "已达到最大未完成订单数"); return addLog(rule, quote, "跳过", "已达到最大未完成订单数", executionId = executionId) }
        val quantity = rule.budgetUsdt.divide(quote.price, 16, RoundingMode.DOWN)
        if (rule.orderTemplate == StrategyOrderTemplate.OCO) {
            val target = if (rule.side == PaperOrderSide.SELL) percent(quote.price, rule.targetPercent) else percent(quote.price, -rule.targetPercent)
            val stop = if (rule.side == PaperOrderSide.SELL) percent(quote.price, -rule.stopPercent) else percent(quote.price, rule.stopPercent)
            val stopLimit = if (rule.side == PaperOrderSide.SELL) percent(stop, BigDecimal("-0.1")) else percent(stop, BigDecimal("0.1"))
            val result = CryptoPaperTradingService.getInstance().placeOco(rule.symbol, rule.side, quantity, target, stop, stopLimit, quote.price)
            ForwardTestService.getInstance().recordOrder(rule.id, executionId, result.accepted, result.message, quote.price, quantity,
                filled = result.order?.status == PaperOrderStatus.FILLED, side = rule.side, fee = result.order?.fee ?: BigDecimal.ZERO)
            updateExecution(executionId, if (result.accepted) StrategyExecutionState.COMPLETED else StrategyExecutionState.FAILED, result.message, result.order?.ocoGroupId.orEmpty())
            return addLog(rule, quote, if (result.accepted) "已执行" else "失败", result.message, result.order?.ocoGroupId.orEmpty(), executionId)
        }
        val type = if (rule.orderTemplate == StrategyOrderTemplate.MARKET) PaperOrderType.MARKET else PaperOrderType.LIMIT
        val limit = if (type == PaperOrderType.LIMIT) offsetPrice(quote.price, rule.limitOffsetPercent) else null
        val result = CryptoPaperTradingService.getInstance().place(rule.symbol, rule.side, type, quantity, limit, quote.price)
        ForwardTestService.getInstance().recordOrder(rule.id, executionId, result.accepted, result.message, limit ?: quote.price, quantity,
            filled = result.order?.status == PaperOrderStatus.FILLED, side = rule.side, fee = result.order?.fee ?: BigDecimal.ZERO)
        recordPaperOutcome(CryptoPaperTradingService.getInstance().account().realizedPnl - beforeRealized)
        updateExecution(executionId, if (result.accepted) StrategyExecutionState.COMPLETED else StrategyExecutionState.FAILED, result.message, result.order?.id.orEmpty())
        addLog(rule, quote, if (result.accepted) "已执行" else "失败", result.message, result.order?.id.orEmpty(), executionId)
    }
    private fun createDraft(rule: CryptoStrategyRule, quote: CryptoQuote, message: String, executionId: String) {
        drafts.add(0, StrategyOrderDraft(rule = rule, price = quote.price, createdAt = quote.updatedAt.toEpochMilli(), executionId = executionId))
        drafts = drafts.distinctBy { "${it.rule.id}:${it.price}:${it.createdAt}" }.take(100).toMutableList()
        updateExecution(executionId, StrategyExecutionState.WAITING_CONFIRMATION, message)
        addLog(rule, quote, "待确认", message, executionId = executionId)
        ForwardTestService.getInstance().recordOrder(rule.id, executionId, true, message, quote.price,
            rule.budgetUsdt.divide(quote.price, 16, RoundingMode.DOWN), filled = false)
    }
    private fun executeTestnet(rule: CryptoStrategyRule, price: BigDecimal, source: String, executionId: String) {
        val startedAt = System.currentTimeMillis()
        val quote = CryptoQuote(rule.symbol, price, BigDecimal.ZERO, price, price, BigDecimal.ZERO, Instant.now())
        val open = CryptoTestnetTradingService.getInstance().snapshot.openOrders.size
        if (open >= stored.maxOpenStrategyOrders) { updateExecution(executionId, StrategyExecutionState.BLOCKED, "已达到最大未完成订单数"); return addLog(rule, quote, "跳过", "已达到最大未完成订单数", executionId = executionId) }
        val requestedClientId = "qc-s-${executionId.replace("-", "").take(26)}"
        updateExecution(executionId, StrategyExecutionState.SUBMITTING, "$source：正在提交", clientOrderId = requestedClientId)
        val rawQuantity = rule.budgetUsdt.divide(price, 16, RoundingMode.DOWN)
        if (rule.orderTemplate == StrategyOrderTemplate.OCO) {
            val target = if (rule.side == PaperOrderSide.SELL) percent(price, rule.targetPercent) else percent(price, -rule.targetPercent)
            val stop = if (rule.side == PaperOrderSide.SELL) percent(price, -rule.stopPercent) else percent(price, rule.stopPercent)
            val stopLimit = if (rule.side == PaperOrderSide.SELL) percent(stop, BigDecimal("-0.1")) else percent(stop, BigDecimal("0.1"))
            CryptoTestnetTradingService.getInstance().prepareOco(rule.symbol, rawQuantity, target, stop, stopLimit) { prepared ->
                prepared.onFailure { updateExecution(executionId, StrategyExecutionState.FAILED, it.message.orEmpty()); addLog(rule, quote, "失败", "$source：${it.message}", executionId = executionId) }
                prepared.onSuccess { draft ->
                    CryptoTestnetTradingService.getInstance().placeOco(rule.symbol, rule.side, draft.quantity,
                        draft.targetPrice, draft.stopPrice, draft.stopLimitPrice, callback = { result ->
                        val orderId = result.getOrNull()?.id?.toString().orEmpty(); val message = result.fold({ "OCO 已提交" }, { it.message.orEmpty() })
                        updateExecution(executionId, if (result.isSuccess) StrategyExecutionState.COMPLETED else StrategyExecutionState.FAILED, message, orderId)
                        addLog(rule, quote, if (result.isSuccess) "已执行" else "失败", "$source：$message", orderId, executionId)
                        ForwardTestService.getInstance().recordOrder(rule.id, executionId, result.isSuccess, message, price, draft.quantity,
                            System.currentTimeMillis() - startedAt, filled = false)
                    }, requestedClientOrderId = requestedClientId)
                }
            }
            return
        }
        val sizingType = if (rule.orderTemplate == StrategyOrderTemplate.MARKET) PaperOrderType.MARKET else PaperOrderType.LIMIT
        val limit = if (sizingType == PaperOrderType.LIMIT) offsetPrice(price, rule.limitOffsetPercent) else null
        CryptoTestnetTradingService.getInstance().prepareOrder(rule.symbol, rule.side, sizingType, rawQuantity, limit) { prepared ->
            prepared.onFailure { updateExecution(executionId, StrategyExecutionState.FAILED, it.message.orEmpty()); addLog(rule, quote, "失败", "$source：${it.message}", executionId = executionId) }
            prepared.onSuccess { draft ->
                when (rule.orderTemplate) {
                    StrategyOrderTemplate.MARKET, StrategyOrderTemplate.LIMIT -> CryptoTestnetTradingService.getInstance().place(
                        rule.symbol, rule.side, sizingType, draft.quantity, draft.price, callback = { result ->
                        val orderId = result.getOrNull()?.id?.toString().orEmpty(); val message = result.fold({ "订单已提交" }, { it.message.orEmpty() })
                        updateExecution(executionId, if (result.isSuccess) StrategyExecutionState.COMPLETED else StrategyExecutionState.FAILED, message, orderId)
                        addLog(rule, quote, if (result.isSuccess) "已执行" else "失败", "$source：$message", orderId, executionId)
                        ForwardTestService.getInstance().recordOrder(rule.id, executionId, result.isSuccess, message, draft.price ?: price, draft.quantity,
                            System.currentTimeMillis() - startedAt, filled = result.getOrNull()?.status == "FILLED", side = rule.side)
                    }, requestedClientOrderId = requestedClientId)
                    StrategyOrderTemplate.OCO -> Unit
                }
            }
        }
    }
    @Synchronized private fun updateExecution(id: String, state: StrategyExecutionState, message: String, orderId: String = "", clientOrderId: String = "") {
        executions = executions.map { if (it.id == id) it.copy(state = state, message = message, orderId = orderId.ifBlank { it.orderId },
            clientOrderId = clientOrderId.ifBlank { it.clientOrderId }) else it }.toMutableList(); encode()
    }
    @Synchronized private fun addLog(rule: CryptoStrategyRule, quote: CryptoQuote, status: String, message: String, orderId: String = "", executionId: String? = null) {
        logs.add(0, StrategyLogEntry(strategyId = rule.id, strategyName = rule.name, symbol = rule.symbol,
            time = quote.updatedAt.toEpochMilli(), status = status, message = message, price = quote.price, orderId = orderId, executionId = executionId))
        logs = logs.take(500).toMutableList(); encode()
        if (status in setOf("失败", "风控阻止")) CryptoNotifications.warn("策略 $status：${rule.name}", message)
    }
    private fun offsetPrice(price: BigDecimal, offset: BigDecimal) = percent(price, offset)
    private fun percent(price: BigDecimal, value: BigDecimal) = price.multiply(BigDecimal.ONE + value.divide(BigDecimal(100), 12, RoundingMode.HALF_UP))
    @Synchronized private fun checkPortfolioRisk(rule: CryptoStrategyRule): String? {
        val market = CryptoMarketService.getInstance()
        val forwardSession = ForwardTestService.getInstance().sessionFor(rule.id)
        val stage = forwardSession?.stage
        val local = stage in setOf(ForwardStage.SHADOW, ForwardStage.PAPER) ||
            (stage == null && rule.action in setOf(StrategyAction.PAPER, StrategyAction.NOTIFY, StrategyAction.TESTNET_DRAFT))
        val snapshot = if (stage == ForwardStage.SHADOW) {
            val price = market.quotes[rule.symbol]?.price ?: BigDecimal.ZERO
            val symbolExposure = forwardSession.quantity * price
            PortfolioSnapshot(forwardSession.currentEquity, symbolExposure, symbolExposure, false)
        } else if (local) {
            val paper = CryptoPaperTradingService.getInstance(); val summary = paper.summary(market.quotes.mapValues { it.value.price })
            val symbolExposure = paper.account().positions.firstOrNull { it.symbol == rule.symbol }?.let { position ->
                position.quantity * (market.quotes[rule.symbol]?.price ?: position.averageCost)
            } ?: BigDecimal.ZERO
            PortfolioSnapshot(summary.equity, summary.marketValue, symbolExposure, false)
        } else {
            val account = CryptoTestnetTradingService.getInstance().snapshot
            var equity = BigDecimal.ZERO; var exposure = BigDecimal.ZERO; var symbolExposure = BigDecimal.ZERO; var missingPrice = false
            account.balances.forEach { balance ->
                if (balance.asset == "USDT") equity += balance.total else {
                    val pair = "${balance.asset}USDT"; val quote = market.quotes[pair]?.price
                    if (balance.total.signum() > 0 && quote == null) missingPrice = true
                    val value = quote?.let { it * balance.total } ?: BigDecimal.ZERO
                    equity += value; exposure += value; if (pair == rule.symbol) symbolExposure += value
                }
            }
            if (missingPrice) return "测试网资产估值行情尚未就绪"
            PortfolioSnapshot(equity, exposure, symbolExposure, false)
        }
        val duplicate = executions.any { it.symbol == rule.symbol && it.state in setOf(StrategyExecutionState.SUBMITTING, StrategyExecutionState.WAITING_CONFIRMATION) }
        val added = if (rule.side == PaperOrderSide.BUY && (stage != null || rule.action != StrategyAction.NOTIFY)) rule.budgetUsdt else BigDecimal.ZERO
        val decision = PortfolioRiskEvaluator.evaluate(portfolioRisk, portfolioRuntime,
            snapshot.copy(duplicateActive = duplicate), added)
        portfolioRuntime = decision.runtime; encode()
        return decision.reason.takeIf(String::isNotBlank)
    }
    @Synchronized fun recordTestnetOrderUpdate(order: TestnetOrder) {
        val execution = executions.firstOrNull { it.clientOrderId.isNotBlank() &&
            (order.clientOrderId == it.clientOrderId || order.clientOrderId.startsWith("${it.clientOrderId}-")) } ?: return
        ForwardTestService.getInstance().recordExecutionUpdate(execution.strategyId, execution.id, order.status, order.side,
            order.averagePrice ?: order.price.takeIf { it.signum() > 0 }, order.executedQuantity)
    }
    @Synchronized fun recordPaperOrderUpdate(order: PaperOrder) {
        if (order.status != PaperOrderStatus.FILLED || order.fillPrice == null) return
        val execution = executions.firstOrNull {
            it.orderId == order.id || (!order.ocoGroupId.isNullOrBlank() && it.orderId == order.ocoGroupId)
        } ?: return
        ForwardTestService.getInstance().recordExecutionUpdate(execution.strategyId, execution.id, "FILLED", order.side,
            order.fillPrice, order.quantity, order.fee, "模拟盘")
    }
    @Synchronized fun recordPaperOutcome(pnl: BigDecimal) {
        if (pnl.signum() == 0) return
        portfolioRuntime = portfolioRuntime.copy(consecutiveLosses = if (pnl.signum() < 0) portfolioRuntime.consecutiveLosses + 1 else 0)
        encode()
    }
    private fun effectiveStrategies(): List<CryptoStrategyRule> {
        val forward = ForwardTestService.getInstance()
        return strategies.map { saved -> forward.sessionFor(saved.id)?.ruleSnapshot?.copy(enabled = saved.enabled) ?: saved }
    }
    private fun encode() {
        stored.strategiesJson = gson.toJson(strategies); stored.runtimesJson = gson.toJson(runtimes); stored.logsJson = gson.toJson(logs)
        stored.draftsJson = gson.toJson(drafts); stored.historyJson = gson.toJson(history.mapValues { it.value.map(BigDecimal::toPlainString) })
        stored.quoteTimesJson = gson.toJson(quoteTimes)
        stored.executionsJson = gson.toJson(executions)
        stored.portfolioRiskJson = gson.toJson(portfolioRisk); stored.portfolioRiskRuntimeJson = gson.toJson(portfolioRuntime)
    }
    private inline fun <reified T> decodeList(json: String): List<T> = runCatching {
        gson.fromJson<List<T>>(json, object : TypeToken<List<T>>() {}.type)
    }.getOrDefault(emptyList())
    private inline fun <reified V> decodeMap(json: String): Map<String, V> = runCatching {
        gson.fromJson<Map<String, V>>(json, object : TypeToken<Map<String, V>>() {}.type)
    }.getOrDefault(emptyMap())
    companion object { fun getInstance() = ApplicationManager.getApplication().getService(CryptoStrategyService::class.java) }
}

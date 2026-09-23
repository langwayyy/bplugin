package com.kkk.bplugin

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.util.concurrency.AppExecutorUtil
import java.time.Instant
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.APP)
class CryptoMarketService : Disposable {
    @Volatile var pairs: List<CryptoPair> = emptyList(); internal set
    @Volatile var quotes: Map<String, CryptoQuote> = emptyMap(); internal set
    @Volatile var error: String? = null; private set
    @Volatile var updated: Instant? = null; private set
    @Volatile var chart: ChartSnapshot? = null; internal set
    @Volatile var chartError: String? = null; private set
    @Volatile var streamConnected = false; private set
    @Volatile var streamMessage: String? = null; private set
    @Volatile var activeAlerts: List<CryptoAlertEvent> = emptyList(); private set
    private val busy = AtomicBoolean()
    private val chartBusy = AtomicBoolean()
    private var nextRefresh = 0L
    private var nextRotation = 0L
    private var failures = 0
    private var refreshPending = false
    private var catalogUpdated = Instant.EPOCH
    private var streamFailures = 0
    private var streamRetryAt = Instant.EPOCH
    private val alertEngine = CryptoAlertEngine()
    @Volatile private var disposed = false
    private val stream = BinanceStreamClient(
        onUpdate = { update -> ApplicationManager.getApplication().invokeLater { applyStreamUpdate(update) } },
        onState = { connected, message -> ApplicationManager.getApplication().invokeLater { applyStreamState(connected, message) } },
    )
    private val schedule = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
        ApplicationManager.getApplication().invokeLater {
            if (disposed) return@invokeLater
            val s = CryptoSettings.getInstance().state
            if (!s.enabled) { stopRealtime(null); return@invokeLater }
            if (s.pauseInactive && !ApplicationManager.getApplication().isActive) { stopRealtime("后台刷新已暂停"); return@invokeLater }
            val now = System.currentTimeMillis()
            activeAlerts = activeAlerts.filter { Duration.between(it.triggeredAt, Instant.now()).toMinutes() < 60 }
            if (s.autoRotate && s.rotation.isNotEmpty() && now >= nextRotation) {
                s.selected = s.rotation[(s.rotation.indexOf(s.selected) + 1).mod(s.rotation.size)]
                nextRotation = now + 30_000
            }
            ensureRealtime()
            if (s.interval != 0 && now >= nextRefresh) {
                val seconds = if (streamConnected) maxOf(s.interval, 60) else s.interval
                nextRefresh = now + seconds * 1_000L
                refresh()
                if (s.background || chartViewers > 0) refreshChart()
            }
        }
    }, 1, 1, TimeUnit.SECONDS)
    var chartViewers = 0
    val loading get() = busy.get()

    fun refresh() {
        val s = CryptoSettings.getInstance().state
        if (!s.enabled || disposed) return
        if (!busy.compareAndSet(false, true)) { refreshPending = true; return }
        val symbols = (s.watchlist + s.statusSymbols + s.selected + CryptoPaperTradingService.getInstance().trackedSymbols() +
            CryptoStrategyService.getInstance().trackedSymbols()).filter(::isCryptoSymbol).distinct()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val catalog = if (pairs.isEmpty() || catalogUpdated.plusSeconds(3600).isBefore(Instant.now())) BinanceMarketClient.shared.pairs() else pairs
                val active = catalog.map { it.symbol }.toSet()
                Triple(catalog, BinanceMarketClient.shared.quotes(symbols.filter { it in active }), symbols.filterNot { it in active })
            }
            ApplicationManager.getApplication().invokeLater {
                if (!disposed && CryptoSettings.getInstance().state.enabled) result.onSuccess { (catalog, data, missing) ->
                    if (pairs !== catalog) catalogUpdated = Instant.now()
                    data.forEach(::evaluateAlerts)
                    pairs = catalog
                    val merged = quotes.toMutableMap()
                    data.forEach { quote ->
                        if (merged[quote.symbol]?.updatedAt?.isAfter(quote.updatedAt) != true) merged[quote.symbol] = quote
                    }
                    quotes = merged; updated = Instant.now()
                    error = missing.takeIf { it.isNotEmpty() }?.let { "交易对不可用：${it.joinToString()}" }; failures = 0
                }.onFailure {
                    error = it.message ?: "币安行情连接失败"
                    failures = (failures + 1).coerceAtMost(6)
                    nextRefresh = System.currentTimeMillis() + (5_000L shl failures).coerceAtMost(300_000)
                }
                busy.set(false)
                if (refreshPending) { refreshPending = false; if (result.isSuccess) refresh() }
            }
        }
    }
    fun select(symbol: String) {
        CryptoSettings.getInstance().state.selected = normalizeMarketSymbol(symbol)
        stopRealtime("正在切换实时订阅")
        refreshChart()
    }
    fun refreshChart() {
        val s = CryptoSettings.getInstance().state
        if (!s.enabled || disposed || !chartBusy.compareAndSet(false, true)) return
        val symbol = s.selected
        val period = CryptoSettings.getInstance().period()
        chartError = null
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { BinanceMarketClient.shared.klines(symbol, period).also { check(it.isNotEmpty()) { "币安未返回有效 K线" } } }
            ApplicationManager.getApplication().invokeLater {
                chartBusy.set(false)
                if (disposed || !CryptoSettings.getInstance().state.enabled) return@invokeLater
                val current = CryptoSettings.getInstance()
                if (current.state.selected != symbol || current.period() != period) { refreshChart(); return@invokeLater }
                result.onSuccess { chart = ChartSnapshot(symbol, period, it, Instant.now()) }
                    .onFailure { chartError = it.message ?: "K线刷新失败" }
            }
        }
    }
    fun pair(symbol: String) = pairs.firstOrNull { it.symbol == symbol }
    private fun ensureRealtime() {
        val s = CryptoSettings.getInstance().state
        if (!s.realtime || Instant.now().isBefore(streamRetryAt)) { if (!s.realtime) stopRealtime(null); return }
        val stale = stream.lastMessageAt?.let { Duration.between(it, Instant.now()).seconds > 45 } == true
        val aged = stream.connectedAt?.let { Duration.between(it, Instant.now()).toMinutes() >= 1_435 } == true
        if (stale || aged) stream.disconnect(if (stale) "实时行情超时，正在重连" else "实时连接定期重建")
        val symbols = (s.watchlist + s.statusSymbols + s.selected + CryptoPaperTradingService.getInstance().trackedSymbols() +
            CryptoStrategyService.getInstance().trackedSymbols()).filter(::isCryptoSymbol).distinct().take(100)
        if (symbols.isNotEmpty()) stream.ensure(StreamSpec(symbols, s.selected, CryptoSettings.getInstance().period()))
    }
    private fun stopRealtime(message: String?) {
        stream.disconnect()
        streamConnected = false
        streamMessage = message
    }
    private fun applyStreamState(connected: Boolean, message: String?) {
        if (disposed) return
        streamConnected = connected
        streamMessage = message
        if (connected) {
            streamFailures = 0; streamRetryAt = Instant.EPOCH
        } else if (message != null && !message.contains("正在")) {
            streamFailures = (streamFailures + 1).coerceAtMost(6)
            streamRetryAt = Instant.now().plusSeconds((2L shl streamFailures).coerceAtMost(120))
        }
    }
    private fun applyStreamUpdate(update: StreamUpdate) {
        if (disposed || !CryptoSettings.getInstance().state.enabled) return
        update.quote?.let { quote ->
            evaluateAlerts(quote)
            quotes = quotes.toMutableMap().apply { put(quote.symbol, quote) }
            updated = quote.updatedAt; error = null
        }
        update.candle?.let { candle ->
            val current = chart
            if (current?.symbol == candle.symbol && current.period == candle.period) {
                val bars = current.bars.toMutableList()
                val existing = bars.indexOfLast { it.timestamp == candle.bar.timestamp }
                if (existing >= 0) bars[existing] = candle.bar else bars.add(candle.bar)
                chart = current.copy(bars = bars.sortedBy(KlineBar::timestamp).takeLast(350), updatedAt = Instant.now())
                chartError = null
            }
        }
    }
    private fun evaluateAlerts(quote: CryptoQuote) {
        CryptoPaperTradingService.getInstance().onQuote(quote)
        CryptoStrategyService.getInstance().onQuote(quote)
        val settings = CryptoSettings.getInstance()
        val rules = if (settings.state.alertsEnabled) settings.alertRules() else emptyList()
        val events = alertEngine.evaluate(quote, rules, settings.state.alertCooldownMinutes)
        if (events.isNotEmpty()) activeAlerts = (events + activeAlerts).distinctBy(CryptoAlertEvent::ruleId).take(10)
    }
    fun dismissAlerts() { activeAlerts = emptyList() }
    fun publishStrategyAlert(rule: CryptoStrategyRule, quote: CryptoQuote) {
        val event = CryptoAlertEvent("strategy:${rule.id}", quote.symbol,
            "策略 ${rule.name} 已触发 · 当前 ${marketPrice(quote.price)}", quote.updatedAt)
        activeAlerts = (listOf(event) + activeAlerts).distinctBy(CryptoAlertEvent::ruleId).take(10)
    }
    fun status(): String {
        val s = CryptoSettings.getInstance().state
        if (!s.enabled) return "币安行情已停用，可在设置中启用"
        if (loading) return "正在刷新币安现货…"
        val time = updated?.atZone(java.time.ZoneId.systemDefault())?.toLocalTime()?.withNano(0)
        val mode = when {
            s.pauseInactive && !ApplicationManager.getApplication().isActive -> "后台刷新已暂停"
            s.realtime && streamConnected -> "实时"
            s.realtime && streamMessage != null -> "实时重连中"
            s.interval == 0 -> "手动刷新"
            else -> "${s.interval}秒刷新"
        }
        return error?.let { "$it · 数据已过期 · 最后更新 ${time ?: "—"}" }
            ?: "币安现货 · $mode · 更新于 ${time ?: "—"}"
    }
    override fun dispose() { disposed = true; schedule.cancel(false); stream.close() }
    companion object { fun getInstance() = ApplicationManager.getApplication().getService(CryptoMarketService::class.java) }
}
data class ChartSnapshot(val symbol: String, val period: KlinePeriod, val bars: List<KlineBar>, val updatedAt: Instant)

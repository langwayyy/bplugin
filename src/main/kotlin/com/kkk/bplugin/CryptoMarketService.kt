package com.kkk.bplugin

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.util.concurrency.AppExecutorUtil
import java.time.Instant
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
    private val busy = AtomicBoolean()
    private val chartBusy = AtomicBoolean()
    private var nextRefresh = 0L
    private var nextRotation = 0L
    private var failures = 0
    private var refreshPending = false
    private var catalogUpdated = Instant.EPOCH
    @Volatile private var disposed = false
    private val schedule = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
        ApplicationManager.getApplication().invokeLater {
            if (disposed) return@invokeLater
            val s = CryptoSettings.getInstance().state
            if (!s.enabled || s.interval == 0 || (s.pauseInactive && !ApplicationManager.getApplication().isActive)) return@invokeLater
            val now = System.currentTimeMillis()
            if (s.autoRotate && s.rotation.isNotEmpty() && now >= nextRotation) {
                s.selected = s.rotation[(s.rotation.indexOf(s.selected) + 1).mod(s.rotation.size)]
                nextRotation = now + 30_000
            }
            if (now >= nextRefresh) {
                nextRefresh = now + s.interval * 1_000L
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
        val symbols = (s.watchlist + s.statusSymbols + s.selected).filter(::isCryptoSymbol).distinct()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val catalog = if (pairs.isEmpty() || catalogUpdated.plusSeconds(3600).isBefore(Instant.now())) BinanceMarketClient.shared.pairs() else pairs
                val active = catalog.map { it.symbol }.toSet()
                Triple(catalog, BinanceMarketClient.shared.quotes(symbols.filter { it in active }), symbols.filterNot { it in active })
            }
            ApplicationManager.getApplication().invokeLater {
                if (!disposed && CryptoSettings.getInstance().state.enabled) result.onSuccess { (catalog, data, missing) ->
                    if (pairs !== catalog) catalogUpdated = Instant.now()
                    pairs = catalog; quotes = data.associateBy { it.symbol }; updated = Instant.now()
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
    fun status(): String {
        val s = CryptoSettings.getInstance().state
        if (!s.enabled) return "币安行情已停用，可在设置中启用"
        if (loading) return "正在刷新币安现货…"
        val time = updated?.atZone(java.time.ZoneId.systemDefault())?.toLocalTime()?.withNano(0)
        val mode = if (s.interval == 0) "手动刷新" else if (s.pauseInactive && !ApplicationManager.getApplication().isActive) "后台刷新已暂停" else "${s.interval}秒刷新"
        return error?.let { "$it · 数据已过期 · 最后更新 ${time ?: "—"}" }
            ?: "币安现货 · $mode · 更新于 ${time ?: "—"}"
    }
    override fun dispose() { disposed = true; schedule.cancel(false) }
    companion object { fun getInstance() = ApplicationManager.getApplication().getService(CryptoMarketService::class.java) }
}
data class ChartSnapshot(val symbol: String, val period: KlinePeriod, val bars: List<KlineBar>, val updatedAt: Instant)

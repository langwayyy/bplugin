package com.kkk.bplugin

import com.intellij.openapi.components.*
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.*
import com.intellij.util.ui.FormBuilder
import javax.swing.*
import java.awt.FlowLayout
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

enum class KlinePeriod(val label: String) {
    INTRADAY("1分"), MINUTE5("5分"), MINUTE15("15分"), MINUTE30("30分"),
    HOUR("1时"), HOUR2("2时"), HOUR4("4时"), HOUR6("6时"), HOUR12("12时"), DAY("日"), WEEK("周");
    override fun toString() = label
}
data class KlineBar(val timestamp: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double)

@Service(Service.Level.APP)
@State(name = "QuietCryptoSettings", storages = [Storage("quiet-crypto.xml")])
class CryptoSettings : PersistentStateComponent<CryptoSettings.Options> {
    data class Options(
        var enabled: Boolean = true,
        var interval: Int = 10,
        var realtime: Boolean = true,
        var pauseInactive: Boolean = true,
        var quote: String = "USDT",
        var decimals: Int = -1,
        var watchlist: MutableList<String> = mutableListOf(),
        var statusSymbols: MutableList<String> = mutableListOf(),
        var statusStyle: String = "名称 + 价格",
        var color: Boolean = false,
        var selected: String = "BTCUSDT",
        var period: String = "HOUR",
        var background: Boolean = false,
        var opacity: Int = 12,
        var rotation: MutableList<String> = mutableListOf(),
        var autoRotate: Boolean = false,
        var alertsEnabled: Boolean = true,
        var alertCooldownMinutes: Int = 30,
        var alertRulesJson: String = "[]",
        var watchMetadataJson: String = "[]",
    )
    private var options = Options()
    override fun getState() = options
    override fun loadState(state: Options) {
        options = state.apply {
            interval = interval.takeIf { it in listOf(0, 5, 10, 30) } ?: 10
            decimals = decimals.coerceIn(-1, 12)
            opacity = opacity.coerceIn(1, 50)
            alertCooldownMinutes = alertCooldownMinutes.coerceIn(1, 1_440)
            selected = normalizeMarketSymbol(selected).takeIf(::isCryptoSymbol) ?: "BTCUSDT"
            period = period.takeIf { p -> KlinePeriod.entries.any { it.name == p } } ?: "HOUR"
            quote = quote.takeIf { it in listOf("USDT", "USDC", "BTC", "ETH") } ?: "USDT"
            statusStyle = statusStyle.takeIf { it in listOf("仅价格", "名称 + 价格", "名称 + 涨跌幅", "完整") } ?: "名称 + 价格"
            watchlist = watchlist.map(::normalizeMarketSymbol).filter(::isCryptoSymbol).distinct().take(100).toMutableList()
            statusSymbols = statusSymbols.map(::normalizeMarketSymbol).filter(::isCryptoSymbol).distinct().take(6).toMutableList()
            rotation = rotation.map(::normalizeMarketSymbol).filter(::isCryptoSymbol).distinct().toMutableList()
        }
    }
    fun period() = KlinePeriod.entries.firstOrNull { it.name == options.period } ?: KlinePeriod.HOUR
    fun alertRules(): List<CryptoAlertRule> = runCatching {
        Gson().fromJson<List<CryptoAlertRule>>(options.alertRulesJson, object : TypeToken<List<CryptoAlertRule>>() {}.type)
    }.getOrDefault(emptyList()).filter { rule ->
        isCryptoSymbol(rule.symbol) && when (rule.condition) {
            AlertCondition.PRICE_ABOVE, AlertCondition.PRICE_BELOW -> rule.threshold.signum() > 0
            AlertCondition.CHANGE_ABOVE -> rule.threshold.signum() >= 0
            AlertCondition.CHANGE_BELOW -> rule.threshold.signum() <= 0
        }
    }.take(100)
    fun saveAlertRules(rules: List<CryptoAlertRule>) { options.alertRulesJson = Gson().toJson(rules.distinctBy(CryptoAlertRule::id).take(100)) }
    fun watchMetadata(): List<CryptoWatchMetadata> {
        val saved = runCatching {
            Gson().fromJson<List<CryptoWatchMetadata>>(options.watchMetadataJson, object : TypeToken<List<CryptoWatchMetadata>>() {}.type)
        }.getOrDefault(emptyList()).associateBy { normalizeMarketSymbol(it.symbol) }
        return options.watchlist.map { symbol ->
            saved[symbol]?.let { CryptoWatchMetadata(symbol, normalizeWatchGroup(it.group), normalizeWatchNote(it.note)) }
                ?: CryptoWatchMetadata(symbol)
        }
    }
    fun watchMetadata(symbol: String): CryptoWatchMetadata = watchMetadata().firstOrNull { it.symbol == normalizeMarketSymbol(symbol) }
        ?: CryptoWatchMetadata(normalizeMarketSymbol(symbol))
    fun saveWatchMetadata(entries: List<CryptoWatchMetadata>) {
        val allowed = options.watchlist.toSet()
        options.watchMetadataJson = Gson().toJson(entries.filter { it.symbol in allowed }.distinctBy(CryptoWatchMetadata::symbol).take(100))
    }
    fun addWatchSymbol(symbol: String, group: String = DEFAULT_WATCH_GROUP, note: String = "") {
        val normalized = normalizeMarketSymbol(symbol)
        if (!isCryptoSymbol(normalized)) return
        if (normalized !in options.watchlist && options.watchlist.size < 100) options.watchlist.add(normalized)
        updateWatchMetadata(normalized, group, note.ifBlank { watchMetadata(normalized).note })
    }
    fun removeWatchSymbol(symbol: String) {
        val normalized = normalizeMarketSymbol(symbol)
        options.watchlist.remove(normalized)
        saveWatchMetadata(watchMetadata().filterNot { it.symbol == normalized })
    }
    fun updateWatchMetadata(symbol: String, group: String, note: String) {
        val normalized = normalizeMarketSymbol(symbol)
        val entries = watchMetadata().filterNot { it.symbol == normalized } +
            CryptoWatchMetadata(normalized, normalizeWatchGroup(group), normalizeWatchNote(note))
        saveWatchMetadata(entries)
    }
    fun watchGroups(): List<String> = watchMetadata().map(CryptoWatchMetadata::group).distinct().sorted()
    companion object { fun getInstance() = ApplicationManager.getApplication().getService(CryptoSettings::class.java) }
}

class CryptoConfigurable : Configurable {
    private val enabled = JBCheckBox("启用币安现货公共行情")
    private val pause = JBCheckBox("IDEA 不在前台时暂停自动刷新")
    private val interval = ComboBox(arrayOf("手动", "5秒", "10秒", "30秒"))
    private val realtime = JBCheckBox("启用 WebSocket 实时价格与当前 K线")
    private val quote = ComboBox(arrayOf("USDT", "USDC", "BTC", "ETH"))
    private val decimals = JSpinner(SpinnerNumberModel(-1, -1, 12, 1))
    private val style = ComboBox(arrayOf("仅价格", "名称 + 价格", "名称 + 涨跌幅", "完整"))
    private val color = JBCheckBox("行情按涨跌着色（涨红跌绿）")
    private val background = JBCheckBox("启用编辑器 K线背景")
    private val opacity = JSpinner(SpinnerNumberModel(12, 1, 50, 1))
    private val rotate = JBCheckBox("每30秒轮播本地轮播列表")
    private val alerts = JBCheckBox("启用本地静默提醒（状态栏与行情页）")
    private val alertCooldown = JSpinner(SpinnerNumberModel(30, 1, 1_440, 5))
    private val alertCount = JBLabel()
    private val connection = JBLabel("公共行情无需账号、API Key 或 Cookie")
    private val test = JButton("测试连接").apply { addActionListener {
        isEnabled = false
        connection.text = "正在连接币安…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { BinanceMarketClient.shared.ping() }
            ApplicationManager.getApplication().invokeLater {
                isEnabled = true
                connection.text = result.fold({ "币安行情连接正常" }, { "连接失败：${it.message}" })
            }
        }
    } }
    override fun getDisplayName() = "Quiet Crypto"
    override fun createComponent(): JComponent {
        reset()
        return FormBuilder.createFormBuilder().addComponent(enabled)
            .addLabeledComponent("默认报价币", quote).addComponent(realtime).addLabeledComponent("REST 校准间隔", interval)
            .addComponent(pause).addLabeledComponent("价格小数位（-1 自动）", decimals)
            .addSeparator().addLabeledComponent("状态栏显示", style).addComponent(color)
            .addSeparator().addComponent(background).addLabeledComponent("背景不透明度 %", opacity)
            .addComponent(rotate).addSeparator().addComponent(alerts)
            .addLabeledComponent("提醒冷却时间（分钟）", alertCooldown)
            .addComponent(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(alertCount); add(JButton("清空提醒").apply { addActionListener {
                    CryptoSettings.getInstance().saveAlertRules(emptyList()); updateAlertCount()
                } })
            }).addSeparator().addComponent(test).addComponent(connection)
            .addComponentFillVertically(JPanel(), 0).panel
    }
    private fun value() = CryptoSettings.getInstance().state.copy(
        enabled = enabled.isSelected, realtime = realtime.isSelected, interval = listOf(0, 5, 10, 30)[interval.selectedIndex],
        quote = quote.selectedItem as String, pauseInactive = pause.isSelected,
        decimals = decimals.value as Int, statusStyle = style.selectedItem as String,
        color = color.isSelected, background = background.isSelected, opacity = opacity.value as Int, autoRotate = rotate.isSelected,
        alertsEnabled = alerts.isSelected, alertCooldownMinutes = alertCooldown.value as Int)
    override fun isModified() = value() != CryptoSettings.getInstance().state
    override fun apply() {
        CryptoSettings.getInstance().loadState(value())
        CryptoMarketService.getInstance().refresh()
        CryptoBackgrounds.getInstance().sync()
    }
    override fun reset() {
        val s = CryptoSettings.getInstance().state
        enabled.isSelected = s.enabled; realtime.isSelected = s.realtime; pause.isSelected = s.pauseInactive
        interval.selectedIndex = listOf(0, 5, 10, 30).indexOf(s.interval).coerceAtLeast(0)
        quote.selectedItem = s.quote; decimals.value = s.decimals; style.selectedItem = s.statusStyle
        color.isSelected = s.color; background.isSelected = s.background; opacity.value = s.opacity; rotate.isSelected = s.autoRotate
        alerts.isSelected = s.alertsEnabled; alertCooldown.value = s.alertCooldownMinutes; updateAlertCount()
    }
    private fun updateAlertCount() { alertCount.text = "已配置 ${CryptoSettings.getInstance().alertRules().size} 条提醒  " }
}

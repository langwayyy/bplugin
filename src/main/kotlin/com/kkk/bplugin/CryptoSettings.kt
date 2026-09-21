package com.kkk.bplugin

import com.intellij.openapi.components.*
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.*
import com.intellij.util.ui.FormBuilder
import javax.swing.*

enum class KlinePeriod(val label: String) {
    INTRADAY("1分"), MINUTE5("5分"), MINUTE15("15分"), HOUR("1时"), HOUR4("4时"), DAY("日"), WEEK("周");
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
    )
    private var options = Options()
    override fun getState() = options
    override fun loadState(state: Options) {
        options = state.apply {
            interval = interval.takeIf { it in listOf(0, 5, 10, 30) } ?: 10
            decimals = decimals.coerceIn(-1, 12)
            opacity = opacity.coerceIn(1, 50)
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
            .addComponent(rotate).addSeparator().addComponent(test).addComponent(connection)
            .addComponentFillVertically(JPanel(), 0).panel
    }
    private fun value() = CryptoSettings.getInstance().state.copy(
        enabled = enabled.isSelected, realtime = realtime.isSelected, interval = listOf(0, 5, 10, 30)[interval.selectedIndex],
        quote = quote.selectedItem as String, pauseInactive = pause.isSelected,
        decimals = decimals.value as Int, statusStyle = style.selectedItem as String,
        color = color.isSelected, background = background.isSelected, opacity = opacity.value as Int, autoRotate = rotate.isSelected)
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
    }
}

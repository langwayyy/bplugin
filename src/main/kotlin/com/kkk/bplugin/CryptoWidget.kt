package com.kkk.bplugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.*
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class CryptoWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = "com.kkk.bplugin.ticker"
    override fun getDisplayName() = "Quiet Crypto"
    override fun isAvailable(project: Project) = true
    override fun createWidget(project: Project): StatusBarWidget = CryptoWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) = widget.dispose()
    override fun canBeEnabledOn(statusBar: StatusBar) = true
}
class CryptoWidget(private val project: Project) : CustomStatusBarWidget {
    private val panel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
    private val timer = Timer(1_000) { render() }
    private var signature = ""
    init { render(); timer.start() }
    override fun ID() = "com.kkk.bplugin.ticker"
    override fun getComponent(): JComponent = panel
    override fun getPresentation(): StatusBarWidget.WidgetPresentation? = null
    override fun install(statusBar: StatusBar) = Unit
    override fun dispose() { timer.stop() }
    private fun render() {
        val s = CryptoSettings.getInstance().state
        val service = CryptoMarketService.getInstance()
        val next = "${s.statusSymbols}|${s.enabled}|${s.statusStyle}|${s.decimals}|${s.color}|${service.quotes}|${service.status()}"
        if (next == signature) return
        signature = next; panel.removeAll()
        val symbols = s.statusSymbols.take(6).ifEmpty { listOf("") }
        symbols.forEach { symbol ->
            val pair = service.pair(symbol)
            val q = service.quotes[symbol]
            val name = pair?.base ?: symbol
            val price = q?.let { marketPrice(it.price, s.decimals) } ?: "—"
            val change = q?.let { (if (it.change.signum() > 0) "+" else "") + marketPrice(it.change, 2) + "%" } ?: "—"
            val text = if (symbol.isEmpty()) "Crypto" else when (s.statusStyle) {
                "仅价格" -> price
                "名称 + 涨跌幅" -> "$name $change"
                "完整" -> "$name $price $change"
                else -> "$name $price"
            }
            panel.add(JBLabel(text + if ((!s.enabled || service.error != null) && symbol.isNotEmpty()) " ·" else "").apply {
                border = JBUI.Borders.empty(0, 2); cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                foreground = if (s.color && q != null) { if (q.change.signum() > 0) JBColor(0xD64242, 0xFF6B6B) else if (q.change.signum() < 0) JBColor(0x2A9955, 0x62C985) else JBColor.GRAY } else JBColor.GRAY
                toolTipText = if (symbol.isEmpty()) "打开 Quiet Crypto" else "$pair · $price ${pair?.quote.orEmpty()} · 24h $change · 最高 ${q?.high ?: "—"} · 最低 ${q?.low ?: "—"} · ${service.status()}"
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (e.button == MouseEvent.BUTTON1) {
                            if (symbol.isEmpty()) CryptoPopup.show(project) else { service.select(symbol); CryptoChartWindow.getInstance(project).show() }
                        }
                    }
                    override fun mousePressed(e: MouseEvent) { menu(e) }
                    override fun mouseReleased(e: MouseEvent) { menu(e) }
                    private fun menu(e: MouseEvent) { if (e.isPopupTrigger) JPopupMenu().apply {
                        add(JMenuItem("打开行情列表").apply { addActionListener { CryptoPopup.show(project) } })
                        add(JMenuItem("刷新").apply { addActionListener { service.refresh() } })
                        if (symbol.isNotEmpty()) add(JMenuItem("移除此状态栏条目").apply { addActionListener { s.statusSymbols.remove(symbol); render() } })
                    }.show(e.component, e.x, e.y) }
                })
            })
        }
        panel.revalidate(); panel.repaint()
    }
}
class ShowCryptoAction : AnAction(), DumbAware { override fun actionPerformed(e: AnActionEvent) = CryptoPopup.show(e.project) }
class ShowChartAction : AnAction(), DumbAware { override fun actionPerformed(e: AnActionEvent) { e.project?.let { CryptoChartWindow.getInstance(it).show() } } }
class ToggleBackgroundAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val s = CryptoSettings.getInstance().state; s.background = !s.background
        CryptoBackgrounds.getInstance().sync()
        if (s.background) CryptoMarketService.getInstance().refreshChart()
    }
}

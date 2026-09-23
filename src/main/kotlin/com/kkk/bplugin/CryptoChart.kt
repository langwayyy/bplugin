package com.kkk.bplugin

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.*
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseMotionAdapter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*
import kotlin.math.*

@Service(Service.Level.PROJECT)
class CryptoChartWindow(private val project: Project) : Disposable {
    private var popup: JBPopup? = null
    private var panel: CryptoChartPanel? = null
    fun show() {
        if (popup?.isDisposed == false) return
        val content = CryptoChartPanel(::close)
        panel = content
        val created = JBPopupFactory.getInstance().createComponentPopupBuilder(content, null)
            .setTitle("Quiet Crypto · 固定 K线浮窗").setProject(project)
            .setFocusable(true).setRequestFocus(true).setMovable(true).setResizable(true)
            .setCancelOnClickOutside(false).setCancelOnOtherWindowOpen(false).setCancelOnWindowDeactivation(false)
            .setCancelKeyEnabled(true)
            .setBelongsToGlobalPopupStack(false).setDimensionServiceKey(project, "QuietCrypto.Chart", true)
            .setMinSize(JBUI.size(520, 320)).addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    content.stop()
                    if (panel === content) { panel = null; popup = null }
                }
            }).createPopup()
        popup = created
        try { created.showCenteredInCurrentWindow(project) } catch (error: Throwable) { close(); throw error }
    }
    fun close() {
        val current = popup
        val content = panel
        popup = null
        panel = null
        try { current?.cancel() } finally { content?.stop() }
    }
    override fun dispose() = close()
    companion object { fun getInstance(project: Project) = project.getService(CryptoChartWindow::class.java) }
}

internal class CryptoChartPanel(private val closeWindow: () -> Unit) : JPanel(BorderLayout(0, 6)) {
    private val service = CryptoMarketService.getInstance()
    private val settings get() = CryptoSettings.getInstance().state
    private val chart = CryptoChartCanvas()
    private val symbols = ComboBox<String>()
    private val period = ComboBox(KlinePeriod.entries.toTypedArray())
    private val status = JBLabel()
    private var syncing = false
    private var stopped = false
    private val timer = Timer(1_000) { sync() }
    init {
        preferredSize = JBUI.size(780, 460); border = JBUI.Borders.empty(8)
        add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(symbols); add(JBLabel("币安·现货")); add(period)
            add(JButton("刷新").apply { addActionListener { service.refreshChart(); service.refresh() } })
            add(JButton("◀").apply { addActionListener { cycle(-1) } })
            add(JButton("▶").apply { addActionListener { cycle(1) } })
            add(JButton("背景开关").apply { addActionListener { settings.background = !settings.background; CryptoBackgrounds.getInstance().sync() } })
            add(JButton("重置视图").apply { addActionListener { chart.resetViewport() } })
        }, BorderLayout.NORTH)
        add(chart, BorderLayout.CENTER)
        add(JPanel(BorderLayout(8, 0)).apply {
            add(status, BorderLayout.CENTER)
            add(JButton("关闭").apply {
                toolTipText = "关闭 K线浮窗（Esc）"
                addActionListener { closeWindow() }
            }, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("ESCAPE"), "closeChart")
        actionMap.put("closeChart", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) { closeWindow() }
        })
        symbols.addActionListener { if (!syncing) (symbols.selectedItem as? String)?.let(service::select) }
        period.addActionListener { if (!syncing) { settings.period = (period.selectedItem as KlinePeriod).name; service.refreshChart() } }
        service.chartViewers++; sync(); timer.start(); service.refreshChart(); service.refresh()
    }
    fun stop() { if (!stopped) { stopped = true; timer.stop(); service.chartViewers-- } }
    private fun cycle(direction: Int) {
        val choices = settings.rotation.ifEmpty { settings.watchlist }
        if (choices.isNotEmpty()) service.select(choices[(choices.indexOf(settings.selected) + direction).mod(choices.size)])
    }
    private fun sync() {
        syncing = true
        val choices = (settings.watchlist + settings.selected).distinct()
        if ((0 until symbols.itemCount).map(symbols::getItemAt) != choices) symbols.model = DefaultComboBoxModel(choices.toTypedArray())
        symbols.selectedItem = settings.selected; period.selectedItem = CryptoSettings.getInstance().period()
        syncing = false
        val q = service.quotes[settings.selected]
        status.text = if (!settings.enabled) "币安行情已停用" else service.chartError?.let { "$it · 保留上次数据" }
            ?: buildString {
                append("${service.pair(settings.selected) ?: settings.selected} · ")
                append(q?.let { "最新 ${marketPrice(it.price, settings.decimals)} · 24h ${marketPrice(it.change, 2)}% · " } ?: "")
                append("K线更新 ${service.chart?.takeIf { it.symbol == settings.selected && it.period == CryptoSettings.getInstance().period() }?.updatedAt?.let(::chartTime) ?: "—"}")
            }
        chart.repaint()
    }
}

/** Shared lightweight renderer for the floating chart and non-interactive editor watermark. */
class CryptoChartCanvas(private val watermark: Boolean = false) : JComponent() {
    private var visibleBars: List<KlineBar> = emptyList()
    private var crosshair: Point? = null
    private var visibleCount = 90
    private var historyOffset = 0
    private var dragAnchorX: Int? = null
    private var dragAnchorOffset = 0
    private var lastStride = 1.0
    private var lastDataSize = 0
    private var lastDataKey = ""
    init {
        isOpaque = false
        if (!watermark) {
            toolTipText = "K线"
            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseMoved(event: MouseEvent) { crosshair = event.point; repaint() }
                override fun mouseDragged(event: MouseEvent) {
                    crosshair = event.point
                    dragAnchorX?.let { anchor ->
                        historyOffset = (dragAnchorOffset + ((event.x - anchor) / lastStride).roundToInt())
                            .coerceIn(0, (lastDataSize - 20).coerceAtLeast(0))
                    }
                    repaint()
                }
            })
            addMouseListener(object : MouseAdapter() {
                override fun mouseExited(event: MouseEvent) { crosshair = null; dragAnchorX = null; cursor = Cursor.getDefaultCursor(); repaint() }
                override fun mousePressed(event: MouseEvent) {
                    if (event.button == MouseEvent.BUTTON1) {
                        dragAnchorX = event.x; dragAnchorOffset = historyOffset
                        cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                    }
                }
                override fun mouseReleased(event: MouseEvent) { dragAnchorX = null; cursor = Cursor.getDefaultCursor() }
            })
            addMouseWheelListener { event ->
                visibleCount = (visibleCount + event.wheelRotation * 10).coerceIn(20, 240)
                historyOffset = historyOffset.coerceAtMost((lastDataSize - visibleCount.coerceAtMost(lastDataSize)).coerceAtLeast(0))
                repaint()
            }
        }
    }
    fun resetViewport() { visibleCount = 90; historyOffset = 0; crosshair = null; repaint() }
    internal fun viewportState() = visibleCount to historyOffset
    override fun contains(x: Int, y: Int) = !watermark && super.contains(x, y)
    override fun getToolTipText(event: MouseEvent): String? {
        val plotWidth = (width - 108).coerceAtLeast(1)
        val index = (((event.x - 12).toDouble() / plotWidth) * visibleBars.size).toInt()
        val bar = visibleBars.getOrNull(index) ?: return null
        return "${chartTime(Instant.ofEpochMilli(bar.timestamp))} · 开 ${price(bar.open)} · 高 ${price(bar.high)} · 低 ${price(bar.low)} · 收 ${price(bar.close)} · 量 ${price(bar.volume)}"
    }
    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val s = CryptoSettings.getInstance().state
            if (watermark) g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, s.opacity / 100f)
            g.font = font ?: UIManager.getFont("Label.font")
            g.color = JBColor.foreground()
            val service = CryptoMarketService.getInstance()
            val data = service.chart?.takeIf { it.symbol == s.selected && it.period == CryptoSettings.getInstance().period() }
            if (data == null || data.bars.isEmpty()) {
                visibleBars = emptyList()
                g.drawString("${s.selected} · ${service.chartError ?: if (s.enabled) "正在加载 K线…" else "行情已停用"}", 12, 24)
                return
            }
            val key = "${data.symbol}:${data.period}"
            if (key != lastDataKey) { lastDataKey = key; resetViewport() }
            else if (historyOffset > 0 && data.bars.size > lastDataSize) historyOffset += data.bars.size - lastDataSize
            lastDataSize = data.bars.size
            val count = visibleCount.coerceIn(1, minOf(240, data.bars.size))
            val offset = historyOffset.coerceIn(0, (data.bars.size - count).coerceAtLeast(0))
            val endIndex = data.bars.size - offset
            val bars = data.bars.subList((endIndex - count).coerceAtLeast(0), endIndex); visibleBars = bars
            val plotWidth = (width - 108).coerceAtLeast(1)
            val top = 52
            val plotHeight = ((height - 90) * 0.76).toInt().coerceAtLeast(1)
            val volumeTop = top + plotHeight + 16
            val volumeHeight = (height - volumeTop - 30).coerceAtLeast(1)
            val low = bars.minOf { it.low }; val high = bars.maxOf { it.high }
            val range = (high - low).takeIf { it > 0 } ?: (abs(high) * 0.01).coerceAtLeast(1e-12)
            fun y(value: Double) = top + ((high - value) / range * plotHeight).roundToInt()
            val title = "${service.pair(s.selected) ?: s.selected} · ${data.period.label} · ${price(bars.last().close)}"
            g.drawString(title, 12, 22)
            g.color = JBColor(0xD08A22, 0xF2B84B); g.drawString("MA5", 12, 40)
            g.color = JBColor(0x5479B8, 0x83A9E8); g.drawString("MA10", 52, 40)
            g.color = JBColor(0x8B5FA8, 0xBE8CDB); g.drawString("MA20", 100, 40)
            if (service.chartError != null) g.drawString("数据已过期", (width - 100).coerceAtLeast(12), 22)
            repeat(5) { step ->
                val y = top + plotHeight * step / 4
                g.color = JBColor.GRAY; g.drawString(price(high - range * step / 4), plotWidth + 22, y + 4)
                g.color = JBColor(0xDDDDDD, 0x404040); g.drawLine(12, y, plotWidth + 12, y)
            }
            val maxVolume = bars.maxOf { it.volume }.coerceAtLeast(1e-12)
            val stride = plotWidth.toDouble() / bars.size
            lastStride = stride
            val bodyWidth = (stride * 0.65).toInt().coerceAtLeast(1)
            bars.forEachIndexed { index, bar ->
                val x = 12 + ((index + 0.5) * stride).toInt()
                g.color = if (!s.color || watermark) JBColor.GRAY else if (bar.close >= bar.open) JBColor(0xD64242, 0xFF6B6B) else JBColor(0x2A9955, 0x62C985)
                g.drawLine(x, y(bar.high), x, y(bar.low))
                g.fillRect(x - bodyWidth / 2, min(y(bar.open), y(bar.close)), bodyWidth, abs(y(bar.open) - y(bar.close)).coerceAtLeast(1))
                val volume = (bar.volume / maxVolume * volumeHeight).toInt()
                g.fillRect(x - bodyWidth / 2, volumeTop + volumeHeight - volume, bodyWidth, volume)
            }
            fun drawAverage(window: Int, color: Color) {
                g.color = color
                g.stroke = BasicStroke(if (watermark) 1f else 1.4f)
                val path = java.awt.geom.Path2D.Double()
                var started = false
                bars.indices.forEach { index ->
                    if (index + 1 < window) return@forEach
                    val average = bars.subList(index + 1 - window, index + 1).sumOf(KlineBar::close) / window
                    val x = 12 + ((index + 0.5) * stride)
                    if (!started) { path.moveTo(x, y(average).toDouble()); started = true } else path.lineTo(x, y(average).toDouble())
                }
                if (started) g.draw(path)
            }
            drawAverage(5, JBColor(0xD08A22, 0xF2B84B))
            drawAverage(10, JBColor(0x5479B8, 0x83A9E8))
            drawAverage(20, JBColor(0x8B5FA8, 0xBE8CDB))
            if (!watermark) {
                val markers = CryptoStrategyService.getInstance().logs()
                    .filter { it.symbol == s.selected && it.status in setOf("已触发", "已执行", "待确认") && it.time in bars.first().timestamp..bars.last().timestamp }
                    .distinctBy { "${it.strategyId}:${it.time}" }.take(30)
                markers.forEach { event ->
                    val index = bars.indices.minByOrNull { kotlin.math.abs(bars[it].timestamp - event.time) } ?: return@forEach
                    val x = 12 + ((index + 0.5) * stride).toInt()
                    val markerY = (y(bars[index].high) - 9).coerceAtLeast(top + 2)
                    g.color = if (event.status == "待确认") JBColor(0xD08A22, 0xF2B84B) else JBColor(0x7A4FA3, 0xC18AE5)
                    g.fillPolygon(intArrayOf(x, x - 5, x + 5), intArrayOf(markerY + 7, markerY, markerY), 3)
                    g.drawString("策", x + 7, markerY + 7)
                }
            }
            if (!watermark && s.tradingMode == TradingAccountMode.TESTNET.name) {
                val orders = CryptoTestnetTradingService.getInstance().snapshot.openOrders.filter { it.symbol == s.selected }
                val lines = orders.flatMap { order -> buildList {
                    order.price.takeIf { it.signum() > 0 }?.let { add(Triple(it.toDouble(), "委托 #${order.id}", false)) }
                    order.stopPrice.takeIf { it.signum() > 0 }?.let { add(Triple(it.toDouble(), "触发 #${order.id}", true)) }
                } }.distinctBy { "${it.first}:${it.second}" }
                lines.forEach { (value, label, trigger) ->
                    if (value in low..high) {
                        val lineY = y(value)
                        g.color = if (trigger) JBColor(0xC54B4B, 0xFF7373) else JBColor(0x3B78B4, 0x75AADB)
                        g.stroke = BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(5f, 4f), 0f)
                        g.drawLine(12, lineY, plotWidth + 12, lineY)
                        g.drawString("$label ${price(value)}", 16, (lineY - 3).coerceAtLeast(top + 10))
                    }
                }
            }
            if (!watermark) crosshair?.let { point ->
                if (point.x in 12..(plotWidth + 12) && point.y in top..(top + plotHeight)) {
                    val index = (((point.x - 12) / stride).toInt()).coerceIn(0, bars.lastIndex)
                    val bar = bars[index]
                    val x = 12 + ((index + 0.5) * stride).toInt()
                    g.color = JBColor(0x777777, 0xAAAAAA)
                    g.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(4f, 4f), 0f)
                    g.drawLine(x, top, x, volumeTop + volumeHeight)
                    g.drawLine(12, point.y, plotWidth + 12, point.y)
                    val detail = "${chartTime(Instant.ofEpochMilli(bar.timestamp))}  开 ${price(bar.open)}  高 ${price(bar.high)}  低 ${price(bar.low)}  收 ${price(bar.close)}  量 ${price(bar.volume)}"
                    val boxWidth = (g.fontMetrics.stringWidth(detail) + 14).coerceAtMost(width - 24)
                    g.color = JBColor(0xF3F3F3, 0x343434)
                    g.fillRoundRect(12, (height - 31).coerceAtLeast(0), boxWidth, 22, 6, 6)
                    g.color = JBColor.foreground()
                    g.drawString(detail, 19, height - 16)
                }
            }
            g.color = JBColor.GRAY
            g.drawString(chartTime(Instant.ofEpochMilli(bars.first().timestamp)), 12, height - 8)
            val end = chartTime(Instant.ofEpochMilli(bars.last().timestamp))
            g.drawString(end, (plotWidth + 12 - g.fontMetrics.stringWidth(end)).coerceAtLeast(12), height - 8)
        } finally { g.dispose() }
    }
    private fun price(value: Double): String {
        val decimals = CryptoSettings.getInstance().state.decimals
        val decimal = java.math.BigDecimal.valueOf(value)
        return marketPrice(if (decimals < 0) decimal.round(java.math.MathContext(8)) else decimal, decimals)
    }
}

private fun chartTime(time: Instant) = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(time)

@Service(Service.Level.APP)
class CryptoBackgrounds : Disposable {
    private data class Attached(val canvas: CryptoChartCanvas, val resize: ComponentAdapter)
    private val overlays = mutableMapOf<Editor, Attached>()
    private val timer = Timer(1_000) { sync() }.apply { start() }
    fun sync() {
        if (!ApplicationManager.getApplication().isDispatchThread) { ApplicationManager.getApplication().invokeLater { sync() }; return }
        val enabled = CryptoSettings.getInstance().state.background
        overlays.keys.toList().filter { it.isDisposed || !enabled }.forEach(::detach)
        if (!enabled) return
        EditorFactory.getInstance().allEditors.filter { !it.isDisposed && it.editorKind == EditorKind.MAIN_EDITOR && !it.isOneLineMode }.forEach { editor ->
            if (editor !in overlays) {
                val canvas = CryptoChartCanvas(true)
                val listener = object : ComponentAdapter() { override fun componentResized(e: ComponentEvent) { position(editor, canvas) } }
                editor.contentComponent.add(canvas); editor.contentComponent.addComponentListener(listener)
                overlays[editor] = Attached(canvas, listener); position(editor, canvas)
                if (CryptoMarketService.getInstance().chart == null) CryptoMarketService.getInstance().refreshChart()
            }
            overlays[editor]?.canvas?.let { position(editor, it); it.repaint() }
        }
    }
    private fun position(editor: Editor, canvas: CryptoChartCanvas) {
        val host = editor.contentComponent
        val rect = editor.scrollingModel.visibleArea
        val w = (rect.width * 0.65).toInt().coerceAtLeast(100)
        val h = (rect.height * 0.55).toInt().coerceAtLeast(100)
        canvas.setBounds(rect.x + (rect.width - w) / 2, rect.y + (rect.height - h) / 2, w.coerceAtMost(host.width), h)
    }
    fun detach(editor: Editor) { overlays.remove(editor)?.let { editor.contentComponent.removeComponentListener(it.resize); editor.contentComponent.remove(it.canvas); editor.contentComponent.repaint() } }
    override fun dispose() { timer.stop(); overlays.keys.toList().forEach(::detach) }
    companion object { fun getInstance() = ApplicationManager.getApplication().getService(CryptoBackgrounds::class.java) }
}
class CryptoEditorListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) { CryptoBackgrounds.getInstance().sync() }
    override fun editorReleased(event: EditorFactoryEvent) { CryptoBackgrounds.getInstance().detach(event.editor) }
}

package com.kkk.bplugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import javax.imageio.ImageIO
import javax.swing.JComponent

/** Exercises real IntelliJ services and Swing construction without network requests. */
class CryptoUiTest : BasePlatformTestCase() {
    fun testChartCloseButtonEscapeAndReopenReleaseViewers() {
        verifyChartCloseLifecycle()
    }
    private fun verifyChartCloseLifecycle() {
        val settings = CryptoSettings.getInstance()
        val before = settings.state.copy()
        settings.loadState(CryptoSettings.Options(enabled = false))
        try {
            UIUtil.invokeAndWaitIfNeeded {
                val service = CryptoMarketService.getInstance()
                val viewers = service.chartViewers
                repeat(2) { attempt ->
                    lateinit var panel: CryptoChartPanel
                    var closed = false
                    panel = CryptoChartPanel { closed = true; panel.stop() }
                    try {
                        assertEquals(viewers + 1, service.chartViewers)
                        if (attempt == 0) {
                            fun buttons(c: Container): List<javax.swing.JButton> = c.components.flatMap {
                                when (it) { is javax.swing.JButton -> listOf(it); is Container -> buttons(it); else -> emptyList() }
                            }
                            buttons(panel).single { it.text == "关闭" }.doClick()
                        } else {
                            val action = panel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(javax.swing.KeyStroke.getKeyStroke("ESCAPE"))
                            assertNotNull(action)
                            panel.actionMap.get(action).actionPerformed(java.awt.event.ActionEvent(panel, 0, "escape"))
                        }
                        assertTrue(closed)
                        assertEquals(viewers, service.chartViewers)
                        panel.stop()
                        assertEquals(viewers, service.chartViewers)
                    } finally { panel.stop() }
                }
            }
        } finally { settings.loadState(before) }
    }
    fun testScreensRenderAndSettingsRemainIndependent() {
        val settings = CryptoSettings.getInstance()
        val before = settings.state.copy()
        try {
            settings.loadState(CryptoSettings.Options(enabled = false,
                watchlist = mutableListOf("BTCUSDT", "ETHUSDT", "SHIBUSDT"),
                statusSymbols = mutableListOf("BTCUSDT", "SHIBUSDT")))
            settings.updateWatchMetadata("BTCUSDT", "主流币", "核心观察")
            settings.updateWatchMetadata("ETHUSDT", "主流币", "等待突破")
            settings.updateWatchMetadata("SHIBUSDT", "高波动", "控制仓位")
            val service = CryptoMarketService.getInstance()
            service.pairs = listOf(CryptoPair("BTCUSDT", "BTC", "USDT"), CryptoPair("ETHUSDT", "ETH", "USDT"), CryptoPair("SHIBUSDT", "SHIB", "USDT"))
            service.quotes = listOf("BTCUSDT" to "64250.10", "ETHUSDT" to "3520.80", "SHIBUSDT" to "0.00001234").associate { (symbol, price) ->
                symbol to CryptoQuote(symbol, BigDecimal(price), BigDecimal("2.36"), BigDecimal(price).multiply(BigDecimal("1.03")), BigDecimal(price).multiply(BigDecimal("0.97")), BigDecimal("1280000000"), Instant.now())
            }
            service.chart = ChartSnapshot("BTCUSDT", KlinePeriod.HOUR, (0..89).map {
                val open = 62000.0 + it * 25 + kotlin.math.sin(it * 0.3) * 700
                KlineBar(1700000000000L + it * 3600000L, open, open + 180, open - 140, open + kotlin.math.cos(it.toDouble()) * 120, 100.0 + it * 3)
            }, Instant.now())
            UIUtil.invokeAndWaitIfNeeded {
                render(CryptoPanel(project), "watchlist", 1000, 540)
                val chart = CryptoChartCanvas()
                render(chart, "chart", 780, 430)
                val initialViewport = chart.viewportState()
                chart.dispatchEvent(java.awt.event.MouseWheelEvent(chart, java.awt.event.MouseEvent.MOUSE_WHEEL,
                    System.currentTimeMillis(), 0, 400, 200, 0, false,
                    java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -1))
                assertTrue(chart.viewportState().first < initialViewport.first)
                chart.dispatchEvent(java.awt.event.MouseEvent(chart, java.awt.event.MouseEvent.MOUSE_PRESSED,
                    System.currentTimeMillis(), 0, 300, 200, 1, false, java.awt.event.MouseEvent.BUTTON1))
                chart.dispatchEvent(java.awt.event.MouseEvent(chart, java.awt.event.MouseEvent.MOUSE_DRAGGED,
                    System.currentTimeMillis(), java.awt.event.MouseEvent.BUTTON1_DOWN_MASK, 420, 200, 0, false, java.awt.event.MouseEvent.BUTTON1))
                chart.dispatchEvent(java.awt.event.MouseEvent(chart, java.awt.event.MouseEvent.MOUSE_RELEASED,
                    System.currentTimeMillis(), 0, 420, 200, 1, false, java.awt.event.MouseEvent.BUTTON1))
                assertTrue(chart.viewportState().second > 0)
                chart.dispatchEvent(java.awt.event.MouseEvent(chart, java.awt.event.MouseEvent.MOUSE_MOVED,
                    System.currentTimeMillis(), 0, 420, 210, 0, false))
                render(chart, "chart-crosshair", 780, 430)
                val configurable = CryptoConfigurable()
                render(configurable.createComponent(), "settings", 720, 550)
                assertFalse(configurable.isModified)
                val widget = CryptoWidget(project)
                try { render(widget.component, "status", 420, 32) } finally { widget.dispose() }
            }
            assertEquals("0.00001234", marketPrice(service.quotes.getValue("SHIBUSDT").price))
            assertEquals(3, settings.state.watchlist.size)
        } finally { settings.loadState(before) }
    }
    private fun render(component: JComponent, name: String, width: Int, height: Int) {
        component.setSize(width, height)
        fun layout(c: Component) { if (c is Container) { c.doLayout(); c.components.forEach(::layout) } }
        layout(component)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try { g.color = com.intellij.ui.JBColor.PanelBackground; g.fillRect(0, 0, width, height); component.printAll(g) } finally { g.dispose() }
        val directory = File("build/ui-previews").apply { mkdirs() }
        ImageIO.write(image, "png", File(directory, "$name.png"))
        assertTrue(File(directory, "$name.png").length() > 100)
    }
}

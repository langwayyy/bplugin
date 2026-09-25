package com.kkk.bplugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.*
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.table.AbstractTableModel
import kotlin.math.roundToInt

class StrategyBacktestPanel(private val project: Project?) : JPanel(BorderLayout(0, JBUI.scale(8))) {
    private val summary = JBLabel("选择策略后点击“完整回测”；回测不会调用任何交易接口")
    private val curve = BacktestCurve()
    private val policy = ComboBox(IntrabarFillPolicy.entries.toTypedArray()).apply { selectedItem = IntrabarFillPolicy.CONSERVATIVE }
    private val nextBar = JCheckBox("下一根 K线成交", true)
    private var selectedReport: BacktestReport? = null
    private var reports = emptyList<BacktestReport>()
    private var trades = emptyList<BacktestTrade>()
    private var months = emptyList<Pair<YearMonth, BigDecimal>>()
    private val reportModel = object : AbstractTableModel() {
        override fun getRowCount() = reports.size
        override fun getColumnCount() = 9
        override fun getColumnName(c: Int) = arrayOf("策略", "区间", "K线", "触发", "成交", "总收益 %", "最大回撤 %", "胜率 %", "盈亏比")[c]
        override fun getValueAt(r: Int, c: Int): Any { val it = reports[r]; return when(c) {
            0 -> it.rule.name; 1 -> "${date(it.from)} — ${date(it.to)}"; 2 -> it.bars; 3 -> it.triggers; 4 -> it.trades.size
            5 -> marketPrice(it.totalReturnPercent, 2); 6 -> marketPrice(it.maxDrawdownPercent, 2)
            7 -> marketPrice(it.winRatePercent, 2); else -> marketPrice(it.profitFactor, 2) } }
    }
    private val tradeModel = object : AbstractTableModel() {
        override fun getRowCount() = trades.size
        override fun getColumnCount() = 7
        override fun getColumnName(c: Int) = arrayOf("时间", "方向", "价格", "数量", "手续费", "已实现盈亏", "原因")[c]
        override fun getValueAt(r: Int, c: Int): Any { val it = trades[r]; return when(c) {
            0 -> TIME.format(Instant.ofEpochMilli(it.time)); 1 -> it.side.label; 2 -> marketPrice(it.price); 3 -> marketPrice(it.quantity)
            4 -> marketPrice(it.fee, 4); 5 -> it.pnl?.let { p -> marketPrice(p, 2) } ?: "—"; else -> it.reason } }
    }
    private val monthModel = object : AbstractTableModel() {
        override fun getRowCount() = months.size
        override fun getColumnCount() = 2
        override fun getColumnName(c: Int) = if (c == 0) "月份" else "收益 %"
        override fun getValueAt(r: Int, c: Int): Any = if (c == 0) months[r].first.toString() else marketPrice(months[r].second, 2)
    }
    private val reportTable = JBTable(reportModel).apply { rowHeight = JBUI.scale(27); setSelectionMode(ListSelectionModel.SINGLE_SELECTION) }
    private val tradeTable = JBTable(tradeModel).apply { rowHeight = JBUI.scale(27) }
    private val monthTable = JBTable(monthModel).apply {
        rowHeight = JBUI.scale(28)
        setDefaultRenderer(Any::class.java, object : javax.swing.table.DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): Component {
                super.getTableCellRendererComponent(table, value, selected, focus, row, column)
                if (!selected && column == 1) background = when (months.getOrNull(row)?.second?.signum()) {
                    1 -> JBColor(0xFFD8D8, 0x6B3030); -1 -> JBColor(0xD8F2DF, 0x285A3C); else -> table.background
                }
                return this
            }
        })
    }
    init {
        add(JPanel(BorderLayout()).apply {
            add(summary, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                add(JLabel("同柱规则")); add(policy); add(nextBar)
                add(JButton("导出 CSV").apply { addActionListener { export(false) } }); add(JButton("导出 HTML").apply { addActionListener { export(true) } })
            }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(JSplitPane(JSplitPane.VERTICAL_SPLIT, JPanel(BorderLayout()).apply {
            add(JBScrollPane(reportTable), BorderLayout.NORTH); add(curve, BorderLayout.CENTER)
        }, JTabbedPane().apply { addTab("逐笔成交", JBScrollPane(tradeTable)); addTab("月度收益", JBScrollPane(monthTable)) })
            .apply { resizeWeight = 0.62; dividerLocation = 330 }, BorderLayout.CENTER)
        reportTable.selectionModel.addListSelectionListener {
            val report = reportTable.selectedRow.takeIf { it >= 0 }?.let { reports.getOrNull(reportTable.convertRowIndexToModel(it)) }
            if (report != null) show(report)
        }
    }
    fun run(rule: CryptoStrategyRule) {
        runRules(listOf(rule))
    }
    fun compare(rule: CryptoStrategyRule) {
        val variants = if (rule.condition in setOf(StrategyCondition.MA_CROSS_ABOVE, StrategyCondition.MA_CROSS_BELOW))
            listOf(3 to 10, 5 to 20, 10 to 30).map { (fast, slow) -> rule.copy(name = "${rule.name} $fast/$slow", fastWindow = fast, slowWindow = slow) }
        else listOf(BigDecimal("0.9"), BigDecimal.ONE, BigDecimal("1.1")).map { factor ->
            rule.copy(name = "${rule.name} ×${factor.stripTrailingZeros().toPlainString()}", threshold = rule.threshold * factor)
        }
        runRules(variants)
    }
    fun walkForward(rule: CryptoStrategyRule) {
        val days = Messages.showInputDialog(project, "样本外验证最近多少天（训练 70%，验证 30%）", "Walk Forward 验证", null, "180", null)
            ?.toIntOrNull()?.takeIf { it in 7..3650 } ?: return
        val fill = policy.selectedItem as IntrabarFillPolicy; val delayed = nextBar.isSelected
        summary.text = "正在加载 ${rule.symbol} 的训练集和样本外验证集…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val end = System.currentTimeMillis(); val start = end - days * 86_400_000L
            val result = runCatching {
                val bars = BinanceMarketClient.shared.historicalKlines(rule.symbol, CryptoSettings.getInstance().period(), start, end)
                require(bars.size >= rule.slowWindow * 4) { "K线数量不足以拆分训练和验证区间" }
                val split = (bars.size * 0.7).toInt(); val training = bars.take(split); val validation = bars.drop((split - rule.slowWindow).coerceAtLeast(0))
                val variants = if (rule.condition in setOf(StrategyCondition.MA_CROSS_ABOVE, StrategyCondition.MA_CROSS_BELOW))
                    listOf(3 to 10, 5 to 20, 10 to 30).map { (f, s) -> rule.copy(name = "${rule.name} $f/$s", fastWindow = f, slowWindow = s) }
                else listOf(BigDecimal("0.9"), BigDecimal.ONE, BigDecimal("1.1")).map { rule.copy(name = "${rule.name} ×$it", threshold = rule.threshold * it) }
                val settings = CryptoSettings.getInstance().state
                val config = BacktestConfig(settings.paperInitialBalance.toBigDecimal(), settings.paperFeeBps, settings.paperSlippageBps, fill, delayed)
                val trained = variants.map { StrategyBacktester.run(it, training, config) }
                val best = trained.maxBy { it.totalReturnPercent }
                val out = StrategyBacktester.run(best.rule.copy(name = "${best.rule.name} · 样本外"), validation, config)
                best.copy(rule = best.rule.copy(name = "${best.rule.name} · 训练")) to out
            }
            ApplicationManager.getApplication().invokeLater {
                result.onSuccess { (training, validation) ->
                    reports = (listOf(validation, training) + reports).take(20); reportModel.fireTableDataChanged(); reportTable.setRowSelectionInterval(0, 0); show(validation)
                    val overfit = training.totalReturnPercent.signum() > 0 && validation.totalReturnPercent < training.totalReturnPercent.multiply(BigDecimal("0.4"))
                    summary.text += if (overfit) " · ⚠ 样本外表现显著衰减，疑似过拟合" else " · 样本外表现未见明显过拟合"
                }.onFailure { summary.text = "样本外验证失败：${it.message}" }
            }
        }
    }
    private fun runRules(rules: List<CryptoStrategyRule>) {
        val rule = rules.first()
        val days = Messages.showInputDialog(project, "回测最近多少天（1-3650，最多加载 20000 根 K线）", "完整历史回测", null, "90", null)
            ?.toIntOrNull()?.takeIf { it in 1..3650 } ?: return
        summary.text = "正在下载 ${rule.symbol} 最近 $days 天的 ${CryptoSettings.getInstance().period().label} K线…"
        val fill = policy.selectedItem as IntrabarFillPolicy; val delayed = nextBar.isSelected
        ApplicationManager.getApplication().executeOnPooledThread {
            val end = System.currentTimeMillis(); val start = end - days * 86_400_000L
            val result = runCatching {
                val bars = BinanceMarketClient.shared.historicalKlines(rule.symbol, CryptoSettings.getInstance().period(), start, end)
                val s = CryptoSettings.getInstance().state
                rules.map { StrategyBacktester.run(it, bars, BacktestConfig(s.paperInitialBalance.toBigDecimal(), s.paperFeeBps, s.paperSlippageBps,
                    fill, delayed)) }
            }
            ApplicationManager.getApplication().invokeLater {
                result.onSuccess { next -> reports = (next + reports).take(20); reportModel.fireTableDataChanged(); reportTable.setRowSelectionInterval(0, 0); show(next.first()) }
                    .onFailure { summary.text = "回测失败：${it.message}" }
            }
        }
    }
    private fun show(report: BacktestReport) {
        selectedReport = report
        StrategyBacktestStore.latest = report
        trades = report.trades; tradeModel.fireTableDataChanged(); curve.report = report; curve.repaint()
        months = report.equityCurve.groupBy { YearMonth.from(Instant.ofEpochMilli(it.time).atZone(ZoneId.systemDefault())) }.map { (month, points) ->
            val first = points.first().equity; month to if (first.signum() == 0) BigDecimal.ZERO else (points.last().equity - first).multiply(BigDecimal(100)).divide(first, 4, java.math.RoundingMode.HALF_UP)
        }.sortedBy(Pair<YearMonth, BigDecimal>::first); monthModel.fireTableDataChanged()
        summary.text = "${report.rule.name} · 初始 ${marketPrice(report.initialBalance, 2)} → ${marketPrice(report.finalEquity, 2)} USDT · " +
            "总收益 ${marketPrice(report.totalReturnPercent, 2)}% · 基准 ${marketPrice(report.benchmarkReturnPercent, 2)}% · 年化 ${marketPrice(report.annualizedReturnPercent, 2)}% · 最大回撤 ${marketPrice(report.maxDrawdownPercent, 2)}% · " +
            "最大连续亏损 ${report.maxConsecutiveLosses} · 平均持仓 ${report.averageHoldingMinutes} 分钟"
    }
    private fun export(html: Boolean) {
        val report = selectedReport ?: return
        val chooser = JFileChooser().apply { selectedFile = java.io.File("${report.rule.symbol}-${if (html) "backtest.html" else "trades.csv"}") }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        chooser.selectedFile.writeText(if (html) BacktestReportExporter.html(report) else BacktestReportExporter.csv(report), Charsets.UTF_8)
        summary.text = "报告已导出：${chooser.selectedFile.absolutePath}"
    }
    private class BacktestCurve : JComponent() {
        var report: BacktestReport? = null
        init { preferredSize = JBUI.size(800, 240); isOpaque = false }
        override fun paintComponent(graphics: Graphics) {
            val data = report?.equityCurve.orEmpty(); if (data.size < 2) return
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val left = 50; val top = 20; val w = (width - 70).coerceAtLeast(1); val h = (height - 45).coerceAtLeast(1)
                val low = data.minOf { it.equity }; val high = data.maxOf { it.equity }; val range = (high - low).takeIf { it.signum() > 0 } ?: BigDecimal.ONE
                g.color = JBColor(0xE3E3E3, 0x444444); repeat(5) { i -> val y = top + h * i / 4; g.drawLine(left, y, left + w, y) }
                val path = java.awt.geom.Path2D.Double()
                data.forEachIndexed { i, p ->
                    val x = left + i.toDouble() / (data.size - 1) * w
                    val y = top + (high - p.equity).divide(range, 10, java.math.RoundingMode.HALF_UP).toDouble() * h
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                g.color = JBColor(0x3B78B4, 0x75AADB); g.stroke = BasicStroke(2f); g.draw(path)
                g.color = JBColor.foreground(); g.drawString("资金曲线", left, 14); g.drawString(marketPrice(high, 2), 2, top + 5); g.drawString(marketPrice(low, 2), 2, top + h)
            } finally { g.dispose() }
        }
    }
    companion object {
        private val TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
        private fun date(value: Long) = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))
    }
}

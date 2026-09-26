package com.kkk.bplugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.table.AbstractTableModel

class ForwardTestingPanel(private val project: Project?, private val ruleProvider: () -> CryptoStrategyRule?) : JPanel(BorderLayout(0, JBUI.scale(6))) {
    private val service = ForwardTestService.getInstance()
    private var sessions = emptyList<ForwardSession>()
    private var events = emptyList<ForwardEvent>()
    private var visibleEvents = emptyList<ForwardEvent>()
    private var historicalCache = emptyList<ForwardEvent>()
    private val stage = ComboBox(ForwardStage.entries.toTypedArray())
    private val summary = JBLabel("尚无前向验证会话")
    private val eventType = ComboBox(arrayOf("全部") + ForwardEventType.entries.map(ForwardEventType::name).toTypedArray())
    private val eventSearch = JTextField(12)
    private val historyButton = JButton("加载30天日志").apply { addActionListener { toggleHistory() } }
    private val chart = ForwardEquityChart { selectedSession() }
    private val sessionModel = object : AbstractTableModel() {
        override fun getRowCount() = sessions.size
        override fun getColumnCount() = 12
        override fun getColumnName(column: Int) = arrayOf("状态", "阶段", "策略", "交易对", "开始", "信号", "订单", "成交", "盈亏", "收益率", "最大回撤", "在线率")[column]
        override fun getValueAt(row: Int, column: Int): Any {
            val item = sessions[row]; val metrics = ForwardEngine.metrics(item)
            return when (column) {
                0 -> item.status.label; 1 -> item.stage.label; 2 -> item.strategyName; 3 -> item.symbol; 4 -> TIME.format(Instant.ofEpochMilli(item.startedAt))
                5 -> item.signals; 6 -> item.orders; 7 -> item.fills; 8 -> marketPrice(metrics.pnl, 2)
                9 -> "${marketPrice(metrics.returnPercent, 2)}%"; 10 -> "${marketPrice(item.maxDrawdownPercent, 2)}%"
                else -> "${marketPrice(metrics.uptimePercent, 2)}%"
            }
        }
    }
    private val eventModel = object : AbstractTableModel() {
        override fun getRowCount() = visibleEvents.size
        override fun getColumnCount() = 9
        override fun getColumnName(column: Int) = arrayOf("时间", "类型", "阶段", "策略", "交易对", "价格", "数量", "盈亏", "说明")[column]
        override fun getValueAt(row: Int, column: Int): Any = visibleEvents[row].let { item -> when (column) {
            0 -> TIME.format(Instant.ofEpochMilli(item.time)); 1 -> item.type.name; 2 -> item.stage.label; 3 -> item.strategyName; 4 -> item.symbol
            5 -> item.price?.let(::marketPrice) ?: "—"; 6 -> item.quantity?.let(::marketPrice) ?: "—"
            7 -> item.pnl?.let { marketPrice(it, 2) } ?: "—"; else -> item.message
        } }
    }
    private val sessionTable = table(sessionModel)
    private val eventTable = table(eventModel)
    private var fingerprint = ""
    private var includeHistory = false

    init {
        add(JPanel(BorderLayout()).apply {
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                    add(JBLabel("新会话阶段")); add(stage)
                    add(JButton("开始").apply { addActionListener { start() } })
                    add(JButton("暂停 / 恢复").apply { addActionListener { togglePause() } })
                    add(JButton("结束").apply { addActionListener { finish() } })
                    add(JButton("晋级下一阶段").apply { addActionListener { promote() } })
                })
                add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                    add(JButton("晋级门槛").apply { addActionListener { editGate() } })
                    add(JButton("健康策略").apply { addActionListener { editHealthPolicy() } })
                    add(historyButton)
                    add(JBLabel("事件")); add(eventType); add(JBLabel("搜索")); add(eventSearch)
                    add(JButton("筛选").apply { addActionListener { updateEventView() } })
                    add(JButton("导出 CSV").apply { addActionListener { export(false) } })
                    add(JButton("导出 HTML").apply { addActionListener { export(true) } })
                })
            }, BorderLayout.NORTH)
            add(summary, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(JTabbedPane().apply {
            addTab("会话对比", scroll(sessionTable))
            addTab("事件追踪", scroll(eventTable))
            addTab("资金曲线", chart)
        }, BorderLayout.CENTER)
        sessionTable.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) { updateEventView(); chart.repaint(); updateSummary() } }
        refresh(true)
    }

    fun refresh(force: Boolean = false) {
        val nextSessions = service.sessions()
        val recent = service.events()
        val nextEvents = if (includeHistory) mergeForwardEvents(recent, historicalCache) else recent
        val next = "${nextSessions.take(50)}|${nextEvents.take(50)}|${service.gate()}|${service.healthPolicy()}|$includeHistory"
        if (!force && next == fingerprint) return
        fingerprint = next; sessions = nextSessions; events = nextEvents
        sessionModel.fireTableDataChanged(); updateEventView(); chart.repaint(); updateSummary()
    }
    private fun selectedSession(): ForwardSession? {
        val row = sessionTable.selectedRow
        return if (row >= 0) sessions.getOrNull(sessionTable.convertRowIndexToModel(row)) else sessions.firstOrNull()
    }
    private fun updateEventView() {
        val selectedType = (eventType.selectedItem as? String)?.takeUnless { it == "全部" }
        val query = eventSearch.text.trim()
        visibleEvents = events.asSequence()
            .filter { selectedSession()?.id?.let { id -> it.sessionId == id } ?: true }
            .filter { selectedType == null || it.type.name == selectedType }
            .filter { query.isBlank() || listOf(it.message, it.executionId, it.strategyName, it.symbol).any { value -> value.contains(query, true) } }
            .toList()
        eventModel.fireTableDataChanged()
    }
    private fun toggleHistory() {
        if (includeHistory) {
            includeHistory = false; historicalCache = emptyList(); historyButton.text = "加载30天日志"; refresh(true); return
        }
        historyButton.isEnabled = false; message("正在后台读取最近30天事件…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { service.historicalEvents() }
            ApplicationManager.getApplication().invokeLater {
                historyButton.isEnabled = true
                result.onSuccess {
                    historicalCache = it; includeHistory = true; historyButton.text = "返回近期事件"; refresh(true)
                    message("已加载 ${it.size} 条磁盘事件")
                }.onFailure { message("历史事件读取失败：${it.message}") }
            }
        }
    }
    private fun start() {
        val rule = ruleProvider() ?: return message("请先在“策略”页选择一个策略")
        val target = stage.selectedItem as ForwardStage
        if (target == ForwardStage.TESTNET_AUTO && Messages.showYesNoDialog(project,
                "该阶段允许策略在测试网自动下单，仍受测试网安全开关和风控约束。确认开始？", "测试网自动前向验证", null) != Messages.YES) return
        service.start(rule, target).onSuccess { message("已开始 ${target.label} 会话，策略参数已冻结为快照"); refresh(true) }
            .onFailure { message(it.message ?: "无法开始会话") }
    }
    private fun togglePause() {
        val item = selectedSession() ?: return message("请选择会话")
        if (item.status == ForwardSessionStatus.COMPLETED) return message("已结束的会话不能恢复")
        if (item.status == ForwardSessionStatus.RUNNING) { service.pause(item.id); refresh(true) }
        else service.resume(item.id).onSuccess { refresh(true) }.onFailure { message(it.message ?: "恢复校验失败") }
    }
    private fun finish() {
        val item = selectedSession() ?: return message("请选择会话")
        if (item.status == ForwardSessionStatus.COMPLETED) return
        if (Messages.showYesNoDialog(project, "结束“${item.strategyName}”的 ${item.stage.label} 会话？", "结束前向验证", null) == Messages.YES) {
            service.finish(item.id); refresh(true)
        }
    }
    private fun promote() {
        val item = selectedSession() ?: return message("请选择已结束会话")
        val decision = service.promotion(item.id)
        if (!decision.allowed) return message("暂不能晋级：${decision.reasons.joinToString("；")}")
        service.promote(item.id).onSuccess { message("已晋级到 ${it.stage.label}"); refresh(true) }
            .onFailure { message("晋级失败：${it.message}") }
    }
    private fun editGate() {
        val current = service.gate()
        val signals = JTextField(current.minSignals.toString(), 7)
        val drawdown = JTextField(current.maxDrawdownPercent.toPlainString(), 7)
        val errors = JTextField(current.maxErrorRatePercent.toPlainString(), 7)
        val uptime = JTextField(current.minUptimePercent.toPlainString(), 7)
        val closedTrades = JTextField((current.minClosedTrades ?: 5).toString(), 7)
        val profitFactor = JTextField((current.minProfitFactor ?: BigDecimal.ONE).toPlainString(), 7)
        val expectancy = JTextField((current.minExpectancy ?: BigDecimal.ZERO).toPlainString(), 7)
        val panel = JPanel(java.awt.GridLayout(0, 2, 8, 6)).apply {
            add(JLabel("最少信号数")); add(signals); add(JLabel("最大回撤 %")); add(drawdown)
            add(JLabel("最大错误率 %")); add(errors); add(JLabel("最低在线率 %")); add(uptime)
            add(JLabel("最少平仓样本")); add(closedTrades); add(JLabel("最低 Profit Factor")); add(profitFactor)
            add(JLabel("最低单笔期望")); add(expectancy)
        }
        if (JOptionPane.showConfirmDialog(this, panel, "前向验证晋级门槛", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return
        val value = ForwardGateConfig(signals.text.toIntOrNull() ?: return message("最少信号数格式无效"),
            drawdown.text.toBigDecimalOrNull() ?: return message("最大回撤格式无效"),
            errors.text.toBigDecimalOrNull() ?: return message("错误率格式无效"),
            uptime.text.toBigDecimalOrNull() ?: return message("在线率格式无效"),
            closedTrades.text.toIntOrNull() ?: return message("平仓样本格式无效"),
            profitFactor.text.toBigDecimalOrNull() ?: return message("Profit Factor 格式无效"),
            expectancy.text.toBigDecimalOrNull() ?: return message("单笔期望格式无效"))
        service.setGate(value); refresh(true)
    }
    private fun editHealthPolicy() {
        val current = service.healthPolicy()
        val enabled = JCheckBox("达到阈值时自动暂停", current.autoPause)
        val gapSeconds = JTextField((current.maxSingleGapMillis / 1000).toString(), 8)
        val errors = JTextField(current.maxConsecutiveErrors.toString(), 8)
        val panel = JPanel(java.awt.GridLayout(0, 2, 8, 6)).apply {
            add(enabled); add(JLabel("")); add(JLabel("单次行情断档阈值（秒）")); add(gapSeconds)
            add(JLabel("连续订单错误阈值")); add(errors)
        }
        if (JOptionPane.showConfirmDialog(this, panel, "前向验证健康策略", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return
        val seconds = gapSeconds.text.toLongOrNull() ?: return message("断档阈值格式无效")
        val count = errors.text.toIntOrNull() ?: return message("错误阈值格式无效")
        service.setHealthPolicy(ForwardHealthPolicy(enabled.isSelected, seconds * 1000, count)); refresh(true)
    }
    private fun export(html: Boolean) {
        val item = selectedSession() ?: return message("请选择会话")
        val chooser = JFileChooser().apply { selectedFile = File("${item.symbol}-${item.stage.name.lowercase()}-${if (html) "forward.html" else "events.csv"}") }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        chooser.selectedFile.writeText(if (html) ForwardReportExporter.html(item, events) else ForwardReportExporter.csv(item, events), Charsets.UTF_8)
        message("报告已导出：${chooser.selectedFile.absolutePath}")
    }
    private fun updateSummary() {
        val item = selectedSession()
        if (item == null) { summary.text = "尚无前向验证会话"; return }
        val metrics = ForwardEngine.metrics(item)
        val health = item.healthPauseReason.takeIf(String::isNotBlank)?.let { " · 暂停原因 $it" }.orEmpty()
        summary.text = "${item.strategyName} · ${item.stage.label} · ${item.status.label} · 快照 ${item.snapshotHash.take(12)} · 归因盈亏 ${marketPrice(metrics.pnl, 2)} · 胜率 ${marketPrice(metrics.winRatePercent, 2)}% · PF ${marketPrice(metrics.profitFactor, 2)} · 单笔期望 ${marketPrice(metrics.expectancy, 2)} · 手续费 ${marketPrice(item.totalFees, 2)} · 回撤 ${marketPrice(item.maxDrawdownPercent, 2)}% · 在线率 ${marketPrice(metrics.uptimePercent, 2)}% · 错误率 ${marketPrice(metrics.errorRatePercent, 2)}%$health"
    }
    private fun message(value: String) { summary.text = value }
    private fun table(model: AbstractTableModel) = JBTable(model).apply { rowHeight = JBUI.scale(28); setShowGrid(false); autoCreateRowSorter = true; setSelectionMode(ListSelectionModel.SINGLE_SELECTION) }
    private fun scroll(table: JBTable) = JBScrollPane(table).apply { setColumnHeaderView(table.tableHeader) }
    companion object { private val TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()) }
}

private class ForwardEquityChart(private val session: () -> ForwardSession?) : JPanel() {
    init { preferredSize = JBUI.size(900, 260); border = JBUI.Borders.empty(16) }
    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val points = session()?.equityCurve.orEmpty()
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = JBColor(Color(0xD8DDE5), Color(0x40444B)); g.drawRect(40, 15, width - 60, height - 45)
            if (points.size < 2) { g.color = foreground; g.drawString("积累至少两个权益点后显示资金曲线", 56, 48); return }
            val min = points.minOf(ForwardPoint::equity); val max = points.maxOf(ForwardPoint::equity)
            val range = (max - min).takeIf { it.signum() > 0 } ?: BigDecimal.ONE
            g.color = JBColor(Color(0x3978B8), Color(0x6CA9E8))
            var previousX = 40; var previousY = height - 30
            points.forEachIndexed { index, point ->
                val x = 40 + index * (width - 60) / (points.size - 1)
                val y = 15 + (height - 45) - (point.equity - min).multiply(BigDecimal(height - 45)).divide(range, 8, java.math.RoundingMode.HALF_UP).toInt()
                if (index > 0) g.drawLine(previousX, previousY, x, y)
                previousX = x; previousY = y
            }
            g.color = foreground; g.drawString("${marketPrice(min, 2)}", 4, height - 28); g.drawString("${marketPrice(max, 2)}", 4, 22)
        } finally { g.dispose() }
    }
}

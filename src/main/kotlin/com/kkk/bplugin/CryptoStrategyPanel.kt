package com.kkk.bplugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.table.AbstractTableModel

object CryptoStrategyPopup {
    fun show(project: Project?) {
        JBPopupFactory.getInstance().createComponentPopupBuilder(CryptoStrategyPanel(project), null)
            .setTitle("Quiet Crypto · 策略中心").setFocusable(true).setRequestFocus(true)
            .setResizable(true).setMovable(true).setDimensionServiceKey(project, "QuietCrypto.Strategies", true)
            .createPopup().showInFocusCenter()
    }
}

class CryptoStrategyPanel(private val project: Project?) : JPanel(BorderLayout(0, JBUI.scale(8))) {
    private val service = CryptoStrategyService.getInstance()
    private val market = CryptoMarketService.getInstance()
    private val status = JBLabel()
    private val hint = JBLabel("价格、涨跌幅、成交额使用边沿触发；条件持续成立时不会重复执行")
    private var rules = emptyList<CryptoStrategyRule>()
    private var logs = emptyList<StrategyLogEntry>()
    private var drafts = emptyList<StrategyOrderDraft>()
    private var executions = emptyList<StrategyExecution>()
    private val logStatus = ComboBox(arrayOf("全部状态", "已触发", "已执行", "待确认", "风控阻止", "失败", "跳过"))
    private val logRange = ComboBox(arrayOf("全部日期", "今天", "最近7天"))
    private val logSearch = JBTextField().apply { columns = 14; emptyText.text = "策略或交易对" }
    private var fingerprint = ""
    private val ruleModel = object : AbstractTableModel() {
        override fun getRowCount() = rules.size
        override fun getColumnCount() = 12
        override fun getColumnName(column: Int) = arrayOf("启用", "名称", "交易对", "触发条件", "动作", "订单模板", "预算 USDT", "冷却", "每日上限", "最后检查", "最后触发", "今日执行")[column]
        override fun getValueAt(row: Int, column: Int): Any {
            val it = rules[row]
            return when (column) {
                0 -> if (it.enabled) "是" else "否"
                1 -> it.name
                2 -> it.symbol
                3 -> if (it.condition in setOf(StrategyCondition.MA_CROSS_ABOVE, StrategyCondition.MA_CROSS_BELOW)) "${it.condition.label} ${it.fastWindow}/${it.slowWindow}" else "${it.condition.label} ${marketPrice(it.threshold)}"
                4 -> it.action.label
                5 -> it.orderTemplate.label
                6 -> marketPrice(it.budgetUsdt, 2)
                7 -> "${it.cooldownMinutes} 分钟"
                8 -> "${it.maxExecutionsPerDay} 次"
                9 -> service.lastChecked(it.symbol)?.let(::time) ?: "—"
                10 -> service.runtime(it.id)?.lastTriggeredAt?.takeIf { value -> value > 0 }?.let(::time) ?: "—"
                else -> service.runtime(it.id)?.takeIf { runtime -> runtime.executionDate == java.time.LocalDate.now().toString() }?.executionsToday ?: 0
            }
        }
    }
    private val logModel = object : AbstractTableModel() {
        override fun getRowCount() = logs.size
        override fun getColumnCount() = 8
        override fun getColumnName(column: Int) = arrayOf("时间", "策略", "交易对", "状态", "触发价", "说明", "订单 ID", "执行 ID")[column]
        override fun getValueAt(row: Int, column: Int): Any {
            val it = logs[row]
            return when (column) { 0 -> time(it.time); 1 -> it.strategyName; 2 -> it.symbol; 3 -> it.status
                4 -> marketPrice(it.price); 5 -> it.message; 6 -> it.orderId.ifBlank { "—" }; else -> it.executionId?.take(12) ?: "—" }
        }
    }
    private val draftModel = object : AbstractTableModel() {
        override fun getRowCount() = drafts.size
        override fun getColumnCount() = 7
        override fun getColumnName(column: Int) = arrayOf("时间", "策略", "交易对", "方向", "模板", "预算 USDT", "参考价")[column]
        override fun getValueAt(row: Int, column: Int): Any {
            val it = drafts[row]
            return when (column) { 0 -> time(it.createdAt); 1 -> it.rule.name; 2 -> it.rule.symbol; 3 -> it.rule.side.label
                4 -> it.rule.orderTemplate.label; 5 -> marketPrice(it.rule.budgetUsdt, 2); else -> marketPrice(it.price) }
        }
    }
    private val executionModel = object : AbstractTableModel() {
        override fun getRowCount() = executions.size
        override fun getColumnCount() = 8
        override fun getColumnName(column: Int) = arrayOf("时间", "执行 ID", "策略", "交易对", "状态", "客户端订单 ID", "订单 ID", "说明")[column]
        override fun getValueAt(row: Int, column: Int): Any { val it = executions[row]; return when (column) {
            0 -> time(it.createdAt); 1 -> it.id.take(12); 2 -> it.strategyName; 3 -> it.symbol; 4 -> it.state.name
            5 -> it.clientOrderId.ifBlank { "—" }; 6 -> it.orderId.ifBlank { "—" }; else -> it.message } }
    }
    private val ruleTable = table(ruleModel)
    private val logTable = table(logModel)
    private val draftTable = table(draftModel)
    private val executionTable = table(executionModel)
    private val timer = Timer(1_000) { render() }
    private val backtest = StrategyBacktestPanel(project)
    private val forward = ForwardTestingPanel(project) { selectedRule() }
    private val diagnostics = JTextArea().apply { isEditable = false; lineWrap = false; font = UIManager.getFont("TextArea.font") }

    init {
        border = JBUI.Borders.empty(12); preferredSize = JBUI.size(1120, 650)
        add(JPanel(BorderLayout()).apply {
            add(JPanel(GridLayout(0, 1)).apply {
                add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                    add(status); add(JButton("新建").apply { addActionListener { edit(null) } }); add(JButton("编辑").apply { addActionListener { selectedRule()?.let(::edit) ?: showHint("请先选择策略") } })
                    add(JButton("启用 / 停用").apply { addActionListener { selectedRule()?.let { service.toggle(it.id); render(true) } ?: showHint("请先选择策略") } }); add(JButton("删除").apply { addActionListener { removeSelected() } })
                    add(JButton("全局暂停 / 恢复").apply { addActionListener { service.setPaused(!service.isPaused()); render(true) } }); add(JButton("自动测试网开关").apply { addActionListener { toggleAuto() } })
                })
                add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                    add(JButton("历史回放").apply { addActionListener { replaySelected() } }); add(JButton("完整回测").apply { addActionListener { selectedRule()?.let { backtest.run(it) } ?: showHint("请先选择策略") } })
                    add(JButton("参数对比").apply { addActionListener { selectedRule()?.let { backtest.compare(it) } ?: showHint("请先选择策略") } })
                    add(JButton("样本外验证").apply { addActionListener { selectedRule()?.let { backtest.walkForward(it) } ?: showHint("请先选择策略") } })
                    add(JButton("风控上限").apply { addActionListener { editLimits() } }); add(JButton("导入").apply { addActionListener { importRules() } }); add(JButton("导出").apply { addActionListener { exportRules() } })
                    add(JButton("组合风控").apply { addActionListener { editPortfolioRisk() } })
                })
            }, BorderLayout.CENTER)
            add(hint, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(JTabbedPane().apply {
            addTab("策略", JBScrollPane(ruleTable))
            addTab("运行日志", JPanel(BorderLayout()).apply {
                add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(logStatus); add(logRange); add(logSearch); add(JButton("筛选").apply { addActionListener { render(true) } }) }, BorderLayout.NORTH)
                add(JBScrollPane(logTable), BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(JButton("清空日志").apply { addActionListener { service.clearLogs(); render(true) } }) }, BorderLayout.SOUTH)
            })
            addTab("待确认订单", JPanel(BorderLayout()).apply {
                add(JBScrollPane(draftTable), BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                    add(JButton("放弃").apply { addActionListener { selectedDraft()?.let { service.discardDraft(it.id); render(true) } ?: showHint("请先选择草稿") } })
                    add(JButton("确认提交测试网").apply { addActionListener { confirmDraft() } })
                }, BorderLayout.SOUTH)
            })
            addTab("执行状态", JBScrollPane(executionTable))
            addTab("回测报告", backtest)
            addTab("前向验证", forward)
            addTab("运行诊断", JPanel(BorderLayout()).apply {
                add(JBScrollPane(diagnostics), BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                    add(JButton("刷新诊断").apply { addActionListener { diagnostics.text = service.diagnostics() } })
                    add(JButton("复制脱敏诊断").apply { addActionListener { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(service.diagnostics()), null); hint.text = "脱敏诊断已复制" } })
                }, BorderLayout.SOUTH)
            })
        }, BorderLayout.CENTER)
        render(true)
    }
    override fun addNotify() { super.addNotify(); timer.start() }
    override fun removeNotify() { timer.stop(); super.removeNotify() }
    private fun selectedRule() = ruleTable.selectedRow.takeIf { it >= 0 }?.let { rules.getOrNull(ruleTable.convertRowIndexToModel(it)) }
    private fun selectedDraft() = draftTable.selectedRow.takeIf { it >= 0 }?.let { drafts.getOrNull(draftTable.convertRowIndexToModel(it)) }
    private fun edit(rule: CryptoStrategyRule?) {
        val dialog = StrategyDialog(project, rule)
        if (dialog.showAndGet()) { service.save(dialog.rule()); hint.text = "策略已保存并开始监听行情"; render(true) }
    }
    private fun removeSelected() {
        val item = selectedRule() ?: return showHint("请先选择策略")
        if (Messages.showYesNoDialog(project, "删除策略“${item.name}”？相关运行日志会保留。", "删除策略", null) == Messages.YES) {
            service.remove(item.id); render(true)
        }
    }
    private fun replaySelected() {
        val item = selectedRule() ?: return showHint("请先选择策略")
        val chart = market.chart?.takeIf { it.symbol == item.symbol }
        if (chart == null) { market.select(item.symbol); hint.text = "正在加载 ${item.symbol} K线，请稍后再次回放"; return }
        val count = service.replay(item, chart.bars)
        hint.text = "${item.name} 在当前 ${chart.bars.size} 根 ${chart.period.label} K线中触发 $count 次（仅回放，不下单）"
    }
    private fun toggleAuto() {
        if (service.isTestnetAutoEnabled()) {
            service.setTestnetAutoEnabled(false); hint.text = "测试网自动交易已关闭"; render(true); return
        }
        if (Messages.showYesNoDialog(project, "开启后，TESTNET_AUTO 策略触发时会向币安现货测试网自动提交订单，并继续受测试网风控限制。确认开启？",
                "开启测试网自动交易", "确认开启", "取消", null) == Messages.YES) {
            service.setTestnetAutoEnabled(true); hint.text = "测试网自动交易已开启"; render(true)
        }
    }
    private fun editLimits() {
        val current = service.limits()
        val daily = Messages.showInputDialog(project, "全局每日最多执行次数（1-500）", "策略风控", null, current.first.toString(), null)?.toIntOrNull() ?: return
        val open = Messages.showInputDialog(project, "策略允许的最大未完成订单数（1-100）", "策略风控", null, current.second.toString(), null)?.toIntOrNull() ?: return
        service.setLimits(daily, open); render(true)
    }
    private fun editPortfolioRisk() {
        val current = service.portfolioRisk()
        fun ask(label: String, value: Int) = Messages.showInputDialog(project, label, "组合风控", null, value.toString(), null)?.toIntOrNull()
        val total = ask("组合最大总敞口 %（1-100）", current.maxTotalExposurePercent) ?: return
        val symbol = ask("单币种最大敞口 %（1-100）", current.maxSymbolExposurePercent) ?: return
        val daily = ask("每日最大亏损 %（1-100）", current.dailyLossLimitPercent) ?: return
        val drawdown = ask("组合最大回撤 %（1-100）", current.maxDrawdownPercent) ?: return
        val losses = ask("连续亏损暂停次数（1-100）", current.maxConsecutiveLosses) ?: return
        service.setPortfolioRisk(PortfolioRiskConfig(total, symbol, daily, drawdown, losses))
        if (service.portfolioRuntime().pausedReason.isNotBlank() && Messages.showYesNoDialog(project,
                "当前组合风控已暂停：${service.portfolioRuntime().pausedReason}。是否同时手动恢复？", "组合风控", null) == Messages.YES) service.resumePortfolioRisk()
        render(true)
    }
    private fun confirmDraft() {
        val item = selectedDraft() ?: return showHint("请先选择草稿")
        if (Messages.showYesNoDialog(project, "将按当前测试网账户和风控规则提交 ${item.rule.symbol} ${item.rule.orderTemplate.label} 订单。",
                "确认测试网订单", null) == Messages.YES) {
            service.confirmDraft(item.id); hint.text = "订单正在提交，结果会写入运行日志"; render(true)
        }
    }
    private fun exportRules() {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(service.exportRules()), null)
        hint.text = "已将 ${service.strategies().size} 条策略复制到剪贴板；不包含密钥、账户和日志"
    }
    private fun importRules() {
        val text = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor)?.toString() }.getOrNull()
        if (text.isNullOrBlank()) return showHint("剪贴板没有策略 JSON")
        service.importRules(text).onSuccess { hint.text = "已导入 $it 条策略，自动测试网开关未被修改"; render(true) }
            .onFailure { hint.text = "导入失败：${it.message}" }
    }
    private fun render(force: Boolean = false) {
        service.reconcileTestnetExecutions()
        val nextRules = service.strategies(); val allLogs = service.logs(); val nextDrafts = service.drafts(); val nextExecutions = service.executions()
        val statusFilter = logStatus.selectedItem?.toString().orEmpty(); val rangeFilter = logRange.selectedItem?.toString().orEmpty(); val query = logSearch.text.trim()
        val cutoff = when (rangeFilter) { "今天" -> java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            "最近7天" -> System.currentTimeMillis() - 7 * 86_400_000L; else -> Long.MIN_VALUE }
        val nextLogs = allLogs.filter { (statusFilter == "全部状态" || it.status == statusFilter) && it.time >= cutoff &&
            (query.isBlank() || it.strategyName.contains(query, true) || it.symbol.contains(query, true)) }
        val next = "$nextRules|${allLogs.take(30)}|$statusFilter|$rangeFilter|$query|$nextDrafts|${nextExecutions.take(30)}|${service.isPaused()}|${service.isTestnetAutoEnabled()}|${service.limits()}"
        if (!force && next == fingerprint) return
        fingerprint = next; rules = nextRules; logs = nextLogs; drafts = nextDrafts; executions = nextExecutions
        ruleModel.fireTableDataChanged(); logModel.fireTableDataChanged(); draftModel.fireTableDataChanged(); executionModel.fireTableDataChanged()
        val limits = service.limits()
        val riskPause = service.portfolioRuntime().pausedReason
        status.text = "${if (service.isPaused()) "已暂停" else if (riskPause.isNotBlank()) "风控暂停：$riskPause" else "运行中"} · 自动测试网 ${if (service.isTestnetAutoEnabled()) "已开启" else "已关闭"} · 每日 ${limits.first} 次 · 未完成订单 ${limits.second} 个"
        status.foreground = if (service.isPaused()) JBColor(0xB05A00, 0xE6A04A) else JBColor(0x2A7D4F, 0x63C68B)
        diagnostics.text = service.diagnostics()
        forward.refresh()
    }
    private fun showHint(value: String) { hint.text = value }
    private fun table(model: AbstractTableModel) = JBTable(model).apply { rowHeight = JBUI.scale(28); setShowGrid(false); autoCreateRowSorter = true; setSelectionMode(ListSelectionModel.SINGLE_SELECTION) }
    private fun time(value: Long) = TIME.format(Instant.ofEpochMilli(value))
    companion object { private val TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()) }
}

private class StrategyDialog(project: Project?, source: CryptoStrategyRule?) : DialogWrapper(project) {
    private val existing = source
    private val name = JBTextField(source?.name ?: "价格突破策略")
    private val symbol = JBTextField(source?.symbol ?: CryptoSettings.getInstance().state.selected)
    private val condition = ComboBox(StrategyCondition.entries.toTypedArray()).apply { selectedItem = source?.condition ?: StrategyCondition.PRICE_ABOVE }
    private val threshold = JBTextField(source?.threshold?.toPlainString() ?: "0")
    private val fast = JBTextField((source?.fastWindow ?: 5).toString())
    private val slow = JBTextField((source?.slowWindow ?: 20).toString())
    private val action = ComboBox(StrategyAction.entries.toTypedArray()).apply { selectedItem = source?.action ?: StrategyAction.NOTIFY }
    private val side = ComboBox(PaperOrderSide.entries.toTypedArray()).apply { selectedItem = source?.side ?: PaperOrderSide.BUY }
    private val template = ComboBox(StrategyOrderTemplate.entries.toTypedArray()).apply { selectedItem = source?.orderTemplate ?: StrategyOrderTemplate.MARKET }
    private val budget = JBTextField(source?.budgetUsdt?.toPlainString() ?: "100")
    private val limitOffset = JBTextField(source?.limitOffsetPercent?.toPlainString() ?: "0")
    private val target = JBTextField(source?.targetPercent?.toPlainString() ?: "3")
    private val stop = JBTextField(source?.stopPercent?.toPlainString() ?: "2")
    private val cooldown = JBTextField((source?.cooldownMinutes ?: 30).toString())
    private val daily = JBTextField((source?.maxExecutionsPerDay ?: 3).toString())
    private val triggerMode = ComboBox(StrategyTriggerMode.entries.toTypedArray()).apply { selectedItem = source?.triggerMode ?: StrategyTriggerMode.INTRABAR }
    init { title = if (source == null) "新建策略" else "编辑策略"; init() }
    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("名称", name).addLabeledComponent("交易对", symbol)
        .addLabeledComponent("条件", condition).addLabeledComponent("触发时机", triggerMode).addLabeledComponent("阈值", threshold)
        .addLabeledComponent("短 / 长均线", JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { add(fast); add(JLabel("/")); add(slow) })
        .addLabeledComponent("动作", action).addLabeledComponent("方向", side).addLabeledComponent("订单模板", template)
        .addLabeledComponent("单次预算 USDT", budget).addLabeledComponent("限价偏移 %", limitOffset)
        .addLabeledComponent("止盈 / 止损 %", JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { add(target); add(JLabel("/")); add(stop) })
        .addLabeledComponent("冷却分钟", cooldown).addLabeledComponent("每日执行上限", daily).panel.apply { preferredSize = JBUI.size(520, 520) }
    override fun doValidate(): ValidationInfo? {
        if (!isCryptoSymbol(normalizeMarketSymbol(symbol.text))) return ValidationInfo("请输入有效交易对", symbol)
        if (name.text.isBlank()) return ValidationInfo("请输入策略名称", name)
        if (threshold.text.toBigDecimalOrNull() == null) return ValidationInfo("请输入有效阈值", threshold)
        val f = fast.text.toIntOrNull(); val s = slow.text.toIntOrNull()
        if (f == null || s == null || f !in 2..50 || s !in 3..100 || f >= s) return ValidationInfo("均线窗口需满足 2 ≤ 短线 < 长线 ≤ 100", fast)
        if (budget.text.toBigDecimalOrNull()?.signum() != 1) return ValidationInfo("预算必须大于 0", budget)
        if (cooldown.text.toIntOrNull()?.let { it in 1..10_080 } != true) return ValidationInfo("冷却时间需为 1-10080 分钟", cooldown)
        if (daily.text.toIntOrNull()?.let { it in 1..100 } != true) return ValidationInfo("每日上限需为 1-100", daily)
        if (listOf(limitOffset, target, stop).any { it.text.toBigDecimalOrNull() == null }) return ValidationInfo("订单百分比格式无效", limitOffset)
        return null
    }
    fun rule() = CryptoStrategyRule(id = existing?.id ?: java.util.UUID.randomUUID().toString(), name = name.text,
        symbol = normalizeMarketSymbol(symbol.text), condition = condition.selectedItem as StrategyCondition,
        threshold = threshold.text.toBigDecimal(), fastWindow = fast.text.toInt(), slowWindow = slow.text.toInt(),
        action = action.selectedItem as StrategyAction, side = side.selectedItem as PaperOrderSide,
        orderTemplate = template.selectedItem as StrategyOrderTemplate, budgetUsdt = budget.text.toBigDecimal(),
        limitOffsetPercent = limitOffset.text.toBigDecimal(), targetPercent = target.text.toBigDecimal(), stopPercent = stop.text.toBigDecimal(),
        cooldownMinutes = cooldown.text.toInt(), maxExecutionsPerDay = daily.text.toInt(), enabled = existing?.enabled ?: true,
        triggerMode = triggerMode.selectedItem as StrategyTriggerMode)
}

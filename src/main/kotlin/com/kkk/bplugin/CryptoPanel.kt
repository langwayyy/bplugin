package com.kkk.bplugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.math.BigDecimal
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

object CryptoPopup {
    fun show(project: Project?) {
        JBPopupFactory.getInstance().createComponentPopupBuilder(CryptoPanel(project), null)
            .setTitle("Quiet Crypto · 加密货币").setFocusable(true).setRequestFocus(true)
            .setResizable(true).setMovable(true).setDimensionServiceKey(project, "QuietCrypto.Watchlist", true)
            .createPopup().showInFocusCenter()
    }
}

class CryptoPanel(private val project: Project?) : JPanel(BorderLayout(0, JBUI.scale(8))) {
    private val service = CryptoMarketService.getInstance()
    private val settings get() = CryptoSettings.getInstance().state
    private val settingsService get() = CryptoSettings.getInstance()
    private val search = JBTextField().apply { columns = 22; emptyText.text = "搜索 BTC、ETH 或交易对" }
    private val quote = ComboBox(arrayOf("USDT", "USDC", "BTC", "ETH", "全部")).apply { selectedItem = settings.quote }
    private val matches = ComboBox<CryptoPair>().apply { preferredSize = JBUI.size(160, 28) }
    private val add = JButton("＋添加")
    private val groupFilter = ComboBox(arrayOf(ALL_WATCH_GROUPS)).apply { preferredSize = JBUI.size(110, 28) }
    private val footer = JBLabel()
    private val message = JBLabel("搜索并添加关注的交易对；双击查看 K线")
    private var rows = settings.watchlist.toList()
    private val model = object : AbstractTableModel() {
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 6
        override fun getColumnName(column: Int) = arrayOf("交易对", "最新价", "24h涨跌 %", "24h成交额（报价币）", "分组", "备注")[column]
        override fun getColumnClass(column: Int): Class<*> = if (column in 1..3) BigDecimal::class.java else String::class.java
        override fun getValueAt(row: Int, column: Int): Any? {
            val symbol = rows[row]
            val q = service.quotes[symbol]
            val metadata = settingsService.watchMetadata(symbol)
            return when (column) {
                0 -> service.pair(symbol)?.toString() ?: symbol
                1 -> q?.price
                2 -> q?.change
                3 -> q?.turnover
                4 -> metadata.group
                else -> metadata.note
            }
        }
    }
    private val table = object : JBTable(model) {
        override fun getToolTipText(event: MouseEvent): String? {
            val row = rowAtPoint(event.point)
            if (row < 0) return null
            val symbol = rows[convertRowIndexToModel(row)]
            val q = service.quotes[symbol]
            val metadata = settingsService.watchMetadata(symbol)
            return buildString {
                append("${service.pair(symbol) ?: symbol} · ${metadata.group}")
                if (metadata.note.isNotBlank()) append(" · ${metadata.note}")
                if (q != null) append(" · 24h最高 ${q.high} · 最低 ${q.low} · 行情时间 ${q.updatedAt}") else append(" · 尚无行情")
            }
        }
    }.apply {
        rowHeight = JBUI.scale(30); setShowGrid(false); setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        emptyText.text = "搜索并添加交易对，或点击下方 BTC / ETH 快捷添加"
        rowSorter = TableRowSorter(model)
        setDefaultRenderer(String::class.java, object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, col: Int): Component {
                super.getTableCellRendererComponent(t, value, selected, focus, row, col)
                border = JBUI.Borders.empty(0, 6)
                return this
            }
        })
        setDefaultRenderer(BigDecimal::class.java, object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, col: Int): Component {
                super.getTableCellRendererComponent(t, value, selected, focus, row, col)
                horizontalAlignment = RIGHT
                border = JBUI.Borders.empty(0, 6)
                text = (value as? BigDecimal)?.let {
                    if (col == 2) (if (it.signum() > 0) "+" else "") + marketPrice(it, 2)
                    else marketPrice(it, if (col == 1) settings.decimals else 2)
                } ?: "—"
                if (!selected) foreground = if (settings.color && col == 2 && value is BigDecimal) {
                    if (value.signum() > 0) JBColor(0xD64242, 0xFF6B6B) else if (value.signum() < 0) JBColor(0x2A9955, 0x62C985) else JBColor.foreground()
                } else JBColor.foreground()
                return this
            }
        })
        listOf(130, 120, 110, 180, 110, 230).forEachIndexed { column, width ->
            columnModel.getColumn(column).preferredWidth = JBUI.scale(width)
        }
    }
    private var fingerprint = ""
    private var catalog: List<CryptoPair>? = null
    private var syncingGroups = false
    private val timer = Timer(1_000) { render() }
    init {
        border = JBUI.Borders.empty(12); preferredSize = JBUI.size(1080, 540)
        add(JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(search); add(quote); add(matches); add(add); add(JBLabel("分组")); add(groupFilter)
                add(JButton("交易账户").apply { addActionListener { CryptoPaperPopup.show(project) } })
                add(JButton("刷新").apply { addActionListener { service.refresh() } })
                add(JButton("设置").apply { addActionListener { ShowSettingsUtil.getInstance().showSettingsDialog(project, CryptoConfigurable::class.java) } })
            }, BorderLayout.NORTH)
            add(message, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(JBScrollPane(table).apply { setColumnHeaderView(table.tableHeader) }, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                listOf("BTCUSDT", "ETHUSDT").forEach { symbol -> add(JButton("＋${symbol.removeSuffix("USDT")}").apply { addActionListener { addSymbol(symbol) } }) }
                add(JButton("上移").apply { addActionListener { move(-1) } })
                add(JButton("下移").apply { addActionListener { move(1) } })
                add(JButton("移除自选").apply { addActionListener { selected()?.let { settingsService.removeWatchSymbol(it); render() } } })
                add(JButton("导入").apply { toolTipText = "从剪贴板导入交易对、分组和备注"; addActionListener { importWatchlist() } })
                add(JButton("导出").apply { toolTipText = "复制制表符分隔的自选列表到剪贴板"; addActionListener { exportWatchlist() } })
            }, BorderLayout.NORTH)
            add(footer, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        search.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = filter()
            override fun removeUpdate(e: DocumentEvent) = filter()
            override fun changedUpdate(e: DocumentEvent) = filter()
        })
        quote.addActionListener { filter() }
        groupFilter.addActionListener { if (!syncingGroups) { fingerprint = ""; render() } }
        add.addActionListener { (matches.selectedItem as? CryptoPair)?.let { addSymbol(it.symbol) } }
        search.addActionListener { (matches.selectedItem as? CryptoPair)?.let { addSymbol(it.symbol) } }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.button == MouseEvent.BUTTON1 && e.clickCount == 2) selected()?.let { openChart(it) } }
            override fun mousePressed(e: MouseEvent) { if (e.isPopupTrigger) menu(e) }
            override fun mouseReleased(e: MouseEvent) { if (e.isPopupTrigger) menu(e) }
        })
        render(); service.refresh()
    }
    override fun addNotify() { super.addNotify(); timer.start() }
    override fun removeNotify() { timer.stop(); super.removeNotify() }
    private fun selected() = table.selectedRow.takeIf { it >= 0 }?.let { rows.getOrNull(table.convertRowIndexToModel(it)) }
    private fun filter() {
        val input = normalizeMarketSymbol(search.text)
        val found = service.pairs.filter { (quote.selectedItem == "全部" || it.quote == quote.selectedItem) && it.symbol.contains(input) }.take(100)
        matches.model = DefaultComboBoxModel(found.toTypedArray()); add.isEnabled = found.isNotEmpty()
    }
    private fun addSymbol(symbol: String) {
        if (service.pair(symbol) == null) { message.text = "请先连接币安并加载交易对后再添加"; service.refresh(); return }
        val targetGroup = (groupFilter.selectedItem as? String).takeUnless { it == ALL_WATCH_GROUPS } ?: DEFAULT_WATCH_GROUP
        if (symbol !in settings.watchlist) settingsService.addWatchSymbol(symbol, targetGroup)
        message.text = "已关注 ${service.pair(symbol)}"; service.refresh(); render()
    }
    private fun move(delta: Int) {
        val symbol = selected() ?: return
        val visibleIndex = rows.indexOf(symbol)
        val neighbor = rows.getOrNull(visibleIndex + delta) ?: return
        val from = settings.watchlist.indexOf(symbol)
        val to = settings.watchlist.indexOf(neighbor)
        java.util.Collections.swap(settings.watchlist, from, to)
        table.rowSorter.sortKeys = emptyList(); render()
    }
    private fun openChart(symbol: String) { service.select(symbol); project?.let { CryptoChartWindow.getInstance(it).show() } }
    private fun menu(e: MouseEvent) {
        val row = table.rowAtPoint(e.point); if (row < 0) return
        table.setRowSelectionInterval(row, row)
        val symbol = selected() ?: return
        JPopupMenu().apply {
            fun item(label: String, action: () -> Unit) { add(JMenuItem(label).apply { addActionListener { action() } }) }
            item("打开 K线浮窗") { openChart(symbol) }
            item(if (symbol in settings.statusSymbols) "从状态栏移除" else "显示在状态栏") {
                if (symbol in settings.statusSymbols) settings.statusSymbols.remove(symbol)
                else if (settings.statusSymbols.size < 6) settings.statusSymbols.add(symbol)
                else message.text = "状态栏最多显示6个交易对"
                service.refresh()
            }
            item("设为背景 K线") { settings.background = true; service.select(symbol); CryptoBackgrounds.getInstance().sync() }
            item(if (symbol in settings.rotation) "移出轮播" else "加入轮播") {
                if (symbol in settings.rotation) settings.rotation.remove(symbol) else settings.rotation.add(symbol)
                message.text = "轮播：${settings.rotation.joinToString()}（在设置中启用自动轮播）"
            }
            item("移动到分组…") { editGroup(symbol) }
            item("编辑备注…") { editNote(symbol) }
            item("交易买入…") { CryptoPaperPopup.show(project, symbol, PaperOrderSide.BUY) }
            item("交易卖出…") { CryptoPaperPopup.show(project, symbol, PaperOrderSide.SELL) }
            add(JMenu("设置静默提醒").apply {
                AlertCondition.entries.forEach { condition ->
                    add(JMenuItem(condition.displayName).apply { addActionListener { addAlert(symbol, condition) } })
                }
            })
            val existingRules = CryptoSettings.getInstance().alertRules().filter { it.symbol == symbol }
            if (existingRules.isNotEmpty()) add(JMenu("删除提醒").apply {
                existingRules.forEach { rule -> add(JMenuItem(rule.description()).apply { addActionListener {
                    CryptoSettings.getInstance().saveAlertRules(CryptoSettings.getInstance().alertRules().filterNot { it.id == rule.id })
                    message.text = "已删除提醒：${rule.description()}"
                } }) }
            })
            item("移除自选") { settingsService.removeWatchSymbol(symbol); render() }
        }.show(table, e.x, e.y)
    }
    private fun addAlert(symbol: String, condition: AlertCondition) {
        val quote = service.quotes[symbol]
        val initial = when (condition) {
            AlertCondition.PRICE_ABOVE, AlertCondition.PRICE_BELOW -> quote?.price?.let(::marketPrice).orEmpty()
            AlertCondition.CHANGE_ABOVE -> "5"
            AlertCondition.CHANGE_BELOW -> "-5"
        }
        val raw = Messages.showInputDialog(project, "输入提醒阈值${if (condition.name.startsWith("CHANGE")) "（%）" else ""}",
            "${service.pair(symbol) ?: symbol} · ${condition.displayName}", null, initial, null) ?: return
        val threshold = raw.trim().toBigDecimalOrNull()
        val valid = threshold != null && when (condition) {
            AlertCondition.PRICE_ABOVE, AlertCondition.PRICE_BELOW -> threshold.signum() > 0
            AlertCondition.CHANGE_ABOVE -> threshold.signum() >= 0
            AlertCondition.CHANGE_BELOW -> threshold.signum() <= 0
        }
        if (!valid) { Messages.showErrorDialog(project, "请输入有效阈值；跌幅提醒使用负数，例如 -5。", "无法添加提醒"); return }
        val rule = CryptoAlertRule(symbol = symbol, condition = condition, threshold = threshold)
        val serviceSettings = CryptoSettings.getInstance()
        serviceSettings.saveAlertRules(serviceSettings.alertRules() + rule)
        message.text = "已添加提醒：${rule.description()}"
    }
    private fun render() {
        footer.text = service.status() + service.activeAlerts.firstOrNull()?.let { " · ! ${it.message}" }.orEmpty()
        if (catalog !== service.pairs) { catalog = service.pairs; filter() }
        syncGroups()
        val selectedGroup = groupFilter.selectedItem as? String ?: ALL_WATCH_GROUPS
        val metadata = settingsService.watchMetadata()
        val next = "${settings.watchlist}|$metadata|$selectedGroup|${service.quotes}|${settings.decimals}|${settings.color}"
        if (next == fingerprint) return
        fingerprint = next
        val selection = selected()
        val metadataBySymbol = metadata.associateBy(CryptoWatchMetadata::symbol)
        rows = settings.watchlist.filter { selectedGroup == ALL_WATCH_GROUPS || metadataBySymbol[it]?.group == selectedGroup }
        model.fireTableDataChanged()
        rows.indexOf(selection).takeIf { it >= 0 }?.let { table.convertRowIndexToView(it) }?.takeIf { it >= 0 }?.let { table.setRowSelectionInterval(it, it) }
    }
    private fun syncGroups() {
        val current = groupFilter.selectedItem as? String ?: ALL_WATCH_GROUPS
        val groups = listOf(ALL_WATCH_GROUPS) + settingsService.watchGroups()
        if ((0 until groupFilter.itemCount).map(groupFilter::getItemAt) == groups) return
        syncingGroups = true
        groupFilter.model = DefaultComboBoxModel(groups.toTypedArray())
        groupFilter.selectedItem = current.takeIf { it in groups } ?: ALL_WATCH_GROUPS
        syncingGroups = false
    }
    private fun editGroup(symbol: String) {
        val metadata = settingsService.watchMetadata(symbol)
        val value = Messages.showInputDialog(project, "输入分组名称", "移动自选分组", null, metadata.group, null) ?: return
        settingsService.updateWatchMetadata(symbol, value, metadata.note)
        message.text = "${service.pair(symbol) ?: symbol} 已移至 ${normalizeWatchGroup(value)}"
        fingerprint = ""; render()
    }
    private fun editNote(symbol: String) {
        val metadata = settingsService.watchMetadata(symbol)
        val value = Messages.showInputDialog(project, "输入备注（最多200字）", "编辑自选备注", null, metadata.note, null) ?: return
        settingsService.updateWatchMetadata(symbol, metadata.group, value)
        message.text = "已保存 ${service.pair(symbol) ?: symbol} 的备注"
        fingerprint = ""; render()
    }
    private fun exportWatchlist() {
        val text = WatchlistTransfer.encode(settingsService.watchMetadata())
        message.text = runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
            "已复制 ${settings.watchlist.size} 个自选到剪贴板"
        }.getOrElse { "无法访问系统剪贴板，请稍后重试" }
    }
    private fun importWatchlist() {
        val text = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String }.getOrNull()
        if (text.isNullOrBlank()) { message.text = "剪贴板中没有可导入的文本"; return }
        val entries = WatchlistTransfer.decode(text)
        entries.forEach { entry -> settingsService.addWatchSymbol(entry.symbol, entry.group, entry.note) }
        service.refresh(); fingerprint = ""; render()
        message.text = "已从剪贴板导入 ${entries.size} 个交易对"
    }
}

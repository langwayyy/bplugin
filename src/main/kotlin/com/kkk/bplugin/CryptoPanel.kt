package com.kkk.bplugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.options.ShowSettingsUtil
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
    private val search = JBTextField().apply { columns = 22; emptyText.text = "搜索 BTC、ETH 或交易对" }
    private val quote = ComboBox(arrayOf("USDT", "USDC", "BTC", "ETH", "全部")).apply { selectedItem = settings.quote }
    private val matches = ComboBox<CryptoPair>().apply { preferredSize = JBUI.size(160, 28) }
    private val add = JButton("＋添加")
    private val footer = JBLabel()
    private val message = JBLabel("搜索并添加关注的交易对；双击查看 K线")
    private var rows = settings.watchlist.toList()
    private val model = object : AbstractTableModel() {
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 4
        override fun getColumnName(column: Int) = arrayOf("交易对", "最新价", "24h涨跌 %", "24h成交额（报价币）")[column]
        override fun getColumnClass(column: Int): Class<*> = if (column == 0) String::class.java else BigDecimal::class.java
        override fun getValueAt(row: Int, column: Int): Any? {
            val symbol = rows[row]
            val q = service.quotes[symbol]
            return when (column) { 0 -> service.pair(symbol)?.toString() ?: symbol; 1 -> q?.price; 2 -> q?.change; else -> q?.turnover }
        }
    }
    private val table = object : JBTable(model) {
        override fun getToolTipText(event: MouseEvent): String? {
            val row = rowAtPoint(event.point)
            if (row < 0) return null
            val symbol = rows[convertRowIndexToModel(row)]
            val q = service.quotes[symbol] ?: return "尚无行情"
            return "${service.pair(symbol) ?: symbol} · 24h最高 ${q.high} · 最低 ${q.low} · 行情时间 ${q.updatedAt}"
        }
    }.apply {
        rowHeight = JBUI.scale(30); setShowGrid(false); setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        emptyText.text = "搜索并添加交易对，或点击下方 BTC / ETH 快捷添加"
        rowSorter = TableRowSorter(model)
        setDefaultRenderer(BigDecimal::class.java, object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, col: Int): Component {
                super.getTableCellRendererComponent(t, value, selected, focus, row, col)
                horizontalAlignment = RIGHT
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
    }
    private var fingerprint = ""
    private var catalog: List<CryptoPair>? = null
    private val timer = Timer(1_000) { render() }
    init {
        border = JBUI.Borders.empty(12); preferredSize = JBUI.size(900, 520)
        add(JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(search); add(quote); add(matches); add(add)
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
                add(JButton("移除自选").apply { addActionListener { selected()?.let { settings.watchlist.remove(it); render() } } })
            }, BorderLayout.NORTH)
            add(footer, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        search.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = filter()
            override fun removeUpdate(e: DocumentEvent) = filter()
            override fun changedUpdate(e: DocumentEvent) = filter()
        })
        quote.addActionListener { filter() }
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
        if (symbol !in settings.watchlist && settings.watchlist.size < 100) settings.watchlist.add(symbol)
        message.text = "已关注 ${service.pair(symbol)}"; service.refresh(); render()
    }
    private fun move(delta: Int) {
        val symbol = selected() ?: return
        val from = settings.watchlist.indexOf(symbol)
        val to = (from + delta).coerceIn(0, settings.watchlist.lastIndex)
        java.util.Collections.swap(settings.watchlist, from, to)
        table.rowSorter.sortKeys = emptyList(); render(); table.setRowSelectionInterval(to, to)
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
            item("移除自选") { settings.watchlist.remove(symbol); render() }
        }.show(table, e.x, e.y)
    }
    private fun render() {
        footer.text = service.status()
        if (catalog !== service.pairs) { catalog = service.pairs; filter() }
        val next = "${settings.watchlist}|${service.quotes}|${settings.decimals}|${settings.color}"
        if (next == fingerprint) return
        fingerprint = next
        val selection = selected()
        rows = settings.watchlist.toList(); model.fireTableDataChanged()
        rows.indexOf(selection).takeIf { it >= 0 }?.let { table.convertRowIndexToView(it) }?.takeIf { it >= 0 }?.let { table.setRowSelectionInterval(it, it) }
    }
}

package com.kkk.bplugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.table.AbstractTableModel

object CryptoPaperPopup {
    fun show(project: Project?, symbol: String? = null, side: PaperOrderSide = PaperOrderSide.BUY) {
        JBPopupFactory.getInstance().createComponentPopupBuilder(CryptoPaperPanel(project, symbol, side), null)
            .setTitle("Quiet Crypto · 本地模拟交易").setFocusable(true).setRequestFocus(true)
            .setResizable(true).setMovable(true).setDimensionServiceKey(project, "QuietCrypto.PaperTrading", true)
            .createPopup().showInFocusCenter()
    }
}

class CryptoPaperPanel(private val project: Project?, initialSymbol: String?, initialSide: PaperOrderSide) : JPanel(BorderLayout(0, JBUI.scale(8))) {
    private val trading = CryptoPaperTradingService.getInstance()
    private val market = CryptoMarketService.getInstance()
    private val balance = JBLabel()
    private val equity = JBLabel()
    private val pnl = JBLabel()
    private val symbol = ComboBox<String>().apply { isEditable = true; preferredSize = JBUI.size(150, 28) }
    private val side = ComboBox(PaperOrderSide.entries.toTypedArray()).apply { selectedItem = initialSide }
    private val type = ComboBox(PaperOrderType.entries.toTypedArray())
    private val quantity = JBTextField().apply { columns = 10; emptyText.text = "数量" }
    private val price = JBTextField().apply { columns = 12; emptyText.text = "限价"; isEnabled = false }
    private val message = JBLabel("模拟盘完全保存在本地，不会向币安发送订单")
    private var positions = emptyList<PaperPosition>()
    private var openOrders = emptyList<PaperOrder>()
    private var history = emptyList<PaperOrder>()
    private var fingerprint = ""

    private val positionModel = object : AbstractTableModel() {
        override fun getRowCount() = positions.size
        override fun getColumnCount() = 6
        override fun getColumnName(column: Int) = arrayOf("交易对", "持仓数量", "成本价", "最新价", "市值 USDT", "浮动盈亏")[column]
        override fun getValueAt(row: Int, column: Int): Any {
            val item = positions[row]
            val latest = market.quotes[item.symbol]?.price ?: item.averageCost
            return when (column) {
                0 -> market.pair(item.symbol)?.toString() ?: item.symbol
                1 -> marketPrice(item.quantity)
                2 -> displayPrice(item.averageCost)
                3 -> displayPrice(latest)
                4 -> marketPrice(item.quantity * latest, 2)
                else -> signedMoney(item.quantity * (latest - item.averageCost))
            }
        }
    }
    private val orderModel = object : AbstractTableModel() {
        override fun getRowCount() = openOrders.size
        override fun getColumnCount() = 6
        override fun getColumnName(column: Int) = arrayOf("时间", "交易对", "方向", "类型", "数量", "限价")[column]
        override fun getValueAt(row: Int, column: Int): Any {
            val item = openOrders[row]
            return when (column) {
                0 -> time(item.createdAt)
                1 -> market.pair(item.symbol)?.toString() ?: item.symbol
                2 -> item.side.label
                3 -> item.type.label
                4 -> marketPrice(item.quantity)
                else -> item.limitPrice?.let(::marketPrice) ?: "—"
            }
        }
    }
    private val historyModel = object : AbstractTableModel() {
        override fun getRowCount() = history.size
        override fun getColumnCount() = 8
        override fun getColumnName(column: Int) = arrayOf("时间", "交易对", "方向", "类型", "数量", "成交价", "手续费", "状态")[column]
        override fun getValueAt(row: Int, column: Int): Any {
            val item = history[row]
            return when (column) {
                0 -> time(item.filledAt ?: item.createdAt)
                1 -> market.pair(item.symbol)?.toString() ?: item.symbol
                2 -> item.side.label
                3 -> item.type.label
                4 -> marketPrice(item.quantity)
                5 -> item.fillPrice?.let(::displayPrice) ?: "—"
                6 -> marketPrice(item.fee, 4)
                else -> item.status.label
            }
        }
    }
    private val orderTable = table(orderModel)
    private val positionTable = table(positionModel)
    private val historyTable = table(historyModel)
    private val timer = Timer(1_000) { render() }

    init {
        border = JBUI.Borders.empty(12)
        preferredSize = JBUI.size(1000, 620)
        add(JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, 16, 0)).apply {
                add(balance); add(equity); add(pnl)
            }, BorderLayout.CENTER)
            add(JButton("重置模拟账户").apply { addActionListener { resetAccount() } }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JBLabel("交易对")); add(symbol); add(side); add(type)
                add(JBLabel("数量")); add(quantity); add(JBLabel("价格")); add(price)
                add(JButton("提交模拟订单").apply { addActionListener { submit() } })
            }, BorderLayout.NORTH)
            add(JTabbedPane().apply {
                addTab("持仓", scroll(positionTable))
                addTab("当前委托", JPanel(BorderLayout()).apply {
                    add(scroll(orderTable), BorderLayout.CENTER)
                    add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                        add(JButton("撤销所选委托").apply { addActionListener { cancelSelected() } })
                    }, BorderLayout.SOUTH)
                })
                addTab("成交与撤单", scroll(historyTable))
            }, BorderLayout.CENTER)
            add(message, BorderLayout.SOUTH)
        }, BorderLayout.CENTER)
        type.addActionListener { price.isEnabled = type.selectedItem == PaperOrderType.LIMIT }
        side.addActionListener { updateAvailableHint() }
        symbol.addActionListener { updateAvailableHint() }
        syncSymbols(initialSymbol)
        render()
        market.refresh()
    }

    override fun addNotify() { super.addNotify(); timer.start() }
    override fun removeNotify() { timer.stop(); super.removeNotify() }

    private fun submit() {
        val selectedSymbol = normalizeMarketSymbol(symbol.editor.item?.toString().orEmpty())
        val amount = quantity.text.trim().toBigDecimalOrNull()
        val orderType = type.selectedItem as PaperOrderType
        val limit = price.text.trim().toBigDecimalOrNull()
        if (amount == null) { message.text = "请输入有效数量"; return }
        val result = trading.place(selectedSymbol, side.selectedItem as PaperOrderSide, orderType, amount,
            limit, market.quotes[selectedSymbol]?.price)
        message.text = result.message
        if (result.accepted) { quantity.text = ""; if (orderType == PaperOrderType.MARKET) price.text = ""; market.refresh() }
        fingerprint = ""
        render()
    }

    private fun cancelSelected() {
        val row = orderTable.selectedRow
        val order = openOrders.getOrNull(orderTable.convertRowIndexToModel(row)) ?: run { message.text = "请先选择一条当前委托"; return }
        message.text = if (trading.cancel(order.id)) "已撤销 ${order.symbol} 模拟委托" else "委托状态已变化"
        fingerprint = ""
        render()
    }

    private fun resetAccount() {
        if (Messages.showYesNoDialog(project, "将清空全部模拟持仓和订单，并按设置中的初始资金重新开始。", "重置模拟账户", null) != Messages.YES) return
        trading.reset()
        fingerprint = ""
        message.text = "模拟账户已重置"
        render()
    }

    private fun render() {
        syncSymbols(null)
        val account = trading.account()
        val prices = market.quotes.mapValues { it.value.price }
        val summary = trading.summary(prices)
        val next = "$account|$prices"
        if (next == fingerprint) return
        fingerprint = next
        balance.text = "现金：${marketPrice(summary.cash, 2)} USDT（可用 ${marketPrice(summary.availableCash, 2)}）"
        equity.text = "总资产：${marketPrice(summary.equity, 2)} USDT"
        pnl.text = "浮动：${signedMoney(summary.unrealizedPnl)} · 已实现：${signedMoney(summary.realizedPnl)} · 总收益：${signedMoney(summary.totalReturn)}"
        pnl.foreground = when { summary.totalReturn.signum() > 0 -> JBColor(0xD64242, 0xFF6B6B); summary.totalReturn.signum() < 0 -> JBColor(0x2A9955, 0x62C985); else -> JBColor.foreground() }
        positions = account.positions
        openOrders = account.orders.filter { it.status == PaperOrderStatus.OPEN }
        history = account.orders.filter { it.status != PaperOrderStatus.OPEN }
        positionModel.fireTableDataChanged(); orderModel.fireTableDataChanged(); historyModel.fireTableDataChanged()
        updateAvailableHint()
    }

    private fun syncSymbols(preferred: String?) {
        val current = preferred ?: symbol.editor.item?.toString()
        val symbols = (CryptoSettings.getInstance().state.watchlist + market.pairs.filter { it.quote == "USDT" }.take(100).map(CryptoPair::symbol))
            .filter { it.endsWith("USDT") }.distinct()
        if ((0 until symbol.itemCount).map(symbol::getItemAt) != symbols) symbol.model = DefaultComboBoxModel(symbols.toTypedArray())
        symbol.selectedItem = normalizeMarketSymbol(current.orEmpty()).takeIf { it in symbols } ?: symbols.firstOrNull() ?: "BTCUSDT"
    }

    private fun updateAvailableHint() {
        val selectedSymbol = normalizeMarketSymbol(symbol.editor.item?.toString().orEmpty())
        message.toolTipText = if (side.selectedItem == PaperOrderSide.SELL)
            "可卖 ${marketPrice(trading.availableQuantity(selectedSymbol))}" else "买入占用本地虚拟 USDT"
    }

    private fun table(model: AbstractTableModel) = JBTable(model).apply {
        rowHeight = JBUI.scale(28); setShowGrid(false); autoCreateRowSorter = true
        emptyText.text = "暂无记录"
    }
    private fun scroll(table: JBTable) = JBScrollPane(table).apply { setColumnHeaderView(table.tableHeader) }
    private fun signedMoney(value: BigDecimal) = (if (value.signum() > 0) "+" else "") + marketPrice(value, 2) + " USDT"
    private fun displayPrice(value: BigDecimal) = marketPrice(value, if (value >= BigDecimal.ONE) 4 else -1)
    private fun time(epochMillis: Long) = TIME.format(Instant.ofEpochMilli(epochMillis))

    companion object { private val TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()) }
}

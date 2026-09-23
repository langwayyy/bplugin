package com.kkk.bplugin

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel

class CryptoTradingPanel(project: Project?, initialSymbol: String?, initialSide: PaperOrderSide) : JPanel(BorderLayout(0, JBUI.scale(6))) {
    private val settings = CryptoSettings.getInstance()
    private val testnet = CryptoTestnetTradingService.getInstance()
    private val mode = ComboBox(TradingAccountMode.entries.toTypedArray())
    private val state = JBLabel()
    private val cards = JPanel(CardLayout())
    private val timer = Timer(1_000) { updateState() }

    init {
        preferredSize = JBUI.size(1040, 680)
        border = JBUI.Borders.empty(8)
        mode.selectedItem = TradingAccountMode.entries.firstOrNull { it.name == settings.state.tradingMode } ?: TradingAccountMode.LOCAL
        add(JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(JBLabel("账户")); add(mode); add(state) }, BorderLayout.WEST)
            add(JButton("账户设置").apply { addActionListener { ShowSettingsUtil.getInstance().showSettingsDialog(project, CryptoConfigurable::class.java) } }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        cards.add(CryptoPaperPanel(project, initialSymbol, initialSide), TradingAccountMode.LOCAL.name)
        cards.add(CryptoTestnetPanel(project, initialSymbol, initialSide), TradingAccountMode.TESTNET.name)
        add(cards, BorderLayout.CENTER)
        mode.addActionListener {
            val selected = mode.selectedItem as TradingAccountMode
            settings.state.tradingMode = selected.name
            (cards.layout as CardLayout).show(cards, selected.name)
            testnet.accountModeChanged(selected)
            if (selected == TradingAccountMode.TESTNET) testnet.refresh(initialSymbol)
            updateState()
        }
        (cards.layout as CardLayout).show(cards, (mode.selectedItem as TradingAccountMode).name)
        if (mode.selectedItem == TradingAccountMode.TESTNET) testnet.refresh(initialSymbol)
        updateState()
    }
    override fun addNotify() { super.addNotify(); timer.start() }
    override fun removeNotify() { timer.stop(); super.removeNotify() }
    private fun updateState() {
        state.text = when (mode.selectedItem as TradingAccountMode) {
            TradingAccountMode.LOCAL -> "● 本地数据 · 不发送订单"
            TradingAccountMode.TESTNET -> when {
                !testnet.hasCredentials() -> "○ 未配置测试网凭据"
                testnet.streamConnected -> "● 测试网已连接 · 用户数据实时同步"
                else -> "◐ ${testnet.streamMessage ?: testnet.error ?: "测试网 REST 同步"}"
            }
        }
    }
}

class CryptoTestnetPanel(private val project: Project?, initialSymbol: String?, initialSide: PaperOrderSide) : JPanel(BorderLayout(0, JBUI.scale(8))) {
    private val service = CryptoTestnetTradingService.getInstance()
    private val market = CryptoMarketService.getInstance()
    private val assetSummary = JBLabel()
    private val performanceSummary = JBLabel("近100笔成交估算：等待同步")
    private val updated = JBLabel()
    private val symbol = ComboBox<String>().apply { isEditable = true; preferredSize = JBUI.size(150, 28) }
    private val side = ComboBox(PaperOrderSide.entries.toTypedArray()).apply { selectedItem = initialSide }
    private val type = ComboBox(TestnetOrderKind.entries.toTypedArray())
    private val quantity = JBTextField().apply { columns = 10; emptyText.text = "数量" }
    private val price = JBTextField().apply { columns = 12; emptyText.text = "限价"; isEnabled = false }
    private val triggerPrice = JBTextField().apply { columns = 11; emptyText.text = "触发价"; isEnabled = false }
    private val targetPrice = JBTextField().apply { columns = 11; emptyText.text = "OCO 目标价"; isEnabled = false }
    private val submit = JButton("提交测试网订单")
    private val cancel = JButton("撤销所选委托")
    private val cancelAll = JButton("撤销该交易对全部委托")
    private val cancelList = JButton("撤销所选订单组")
    private val normalize = JButton("按规则取整")
    private val estimate = JBLabel("预计金额：—")
    private val message = JBLabel("测试网使用虚拟资产，订单会发送至 Binance Spot Testnet")
    private var balances = emptyList<TestnetBalance>()
    private var openOrders = emptyList<TestnetOrder>()
    private var history = emptyList<TestnetOrder>()
    private var trades = emptyList<TestnetTrade>()
    private var orderLists = emptyList<TestnetOrderList>()
    private var fingerprint = ""
    private val balanceModel = object : AbstractTableModel() {
        override fun getRowCount() = balances.size
        override fun getColumnCount() = 4
        override fun getColumnName(column: Int) = arrayOf("资产", "可用", "冻结", "合计")[column]
        override fun getValueAt(row: Int, column: Int): Any = balances[row].let {
            when (column) { 0 -> it.asset; 1 -> marketPrice(it.free); 2 -> marketPrice(it.locked); else -> marketPrice(it.total) }
        }
    }
    private val orderModel = object : AbstractTableModel() {
        override fun getRowCount() = openOrders.size
        override fun getColumnCount() = 8
        override fun getColumnName(column: Int) = arrayOf("时间", "交易对", "方向", "类型", "数量", "已成交", "价格", "状态")[column]
        override fun getValueAt(row: Int, column: Int): Any = orderCell(openOrders[row], column)
    }
    private val historyModel = object : AbstractTableModel() {
        override fun getRowCount() = history.size
        override fun getColumnCount() = 8
        override fun getColumnName(column: Int) = arrayOf("时间", "交易对", "方向", "类型", "数量", "已成交", "成交均价", "状态")[column]
        override fun getValueAt(row: Int, column: Int): Any = orderCell(history[row], column)
    }
    private val tradeModel = object : AbstractTableModel() {
        override fun getRowCount() = trades.size
        override fun getColumnCount() = 8
        override fun getColumnName(column: Int) = arrayOf("时间", "方向", "数量", "成交价", "成交额", "手续费", "手续费资产", "订单 ID")[column]
        override fun getValueAt(row: Int, column: Int): Any = trades[row].let { trade -> when (column) {
            0 -> TIME.format(Instant.ofEpochMilli(trade.time)); 1 -> if (trade.buyer) "买入" else "卖出"
            2 -> marketPrice(trade.quantity); 3 -> marketPrice(trade.price); 4 -> marketPrice(trade.quoteQuantity)
            5 -> marketPrice(trade.commission); 6 -> trade.commissionAsset; else -> trade.orderId
        } }
    }
    private val listModel = object : AbstractTableModel() {
        override fun getRowCount() = orderLists.size
        override fun getColumnCount() = 6
        override fun getColumnName(column: Int) = arrayOf("订单组 ID", "时间", "交易对", "策略", "状态", "子订单")[column]
        override fun getValueAt(row: Int, column: Int): Any = orderLists[row].let { list -> when (column) {
            0 -> list.id; 1 -> TIME.format(Instant.ofEpochMilli(list.time)); 2 -> list.symbol; 3 -> list.contingencyType
            4 -> list.status; else -> list.orderIds.joinToString(", ")
        } }
    }
    private val orderTable = table(orderModel)
    private val historyTable = table(historyModel)
    private val listTable = table(listModel)
    private val timer = Timer(1_000) { render() }

    init {
        border = JBUI.Borders.empty(4)
        add(JPanel(BorderLayout()).apply {
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JPanel(FlowLayout(FlowLayout.LEFT, 16, 0)).apply { add(assetSummary); add(updated) })
                add(JPanel(FlowLayout(FlowLayout.LEFT, 16, 0)).apply { add(performanceSummary) })
            }, BorderLayout.WEST)
            add(JButton("同步账户").apply { addActionListener { service.refresh(selectedSymbol()); message.text = "正在同步测试网账户…" } }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                    add(JBLabel("交易对")); add(symbol); add(side); add(type); add(JBLabel("数量")); add(quantity)
                    add(JBLabel("价格")); add(price); add(submit)
                })
                add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                    add(JBLabel("触发价")); add(triggerPrice); add(JBLabel("OCO 目标价")); add(targetPrice)
                    add(JBLabel("提示：OCO 中“价格”为止损限价"))
                })
                add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                    add(JBLabel("快捷仓位"))
                    listOf(25, 50, 75, 100).forEach { percent ->
                        add(JButton("$percent%").apply { addActionListener { sizeByPercent(percent) } })
                    }
                    add(normalize); add(Box.createHorizontalStrut(JBUI.scale(12))); add(estimate)
                })
            }, BorderLayout.NORTH)
            add(JTabbedPane().apply {
                addTab("测试网资产", scroll(table(balanceModel)))
                addTab("当前委托", JPanel(BorderLayout()).apply {
                    add(scroll(orderTable), BorderLayout.CENTER)
                    add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(cancelAll); add(cancel) }, BorderLayout.SOUTH)
                })
                addTab("订单记录", scroll(historyTable))
                addTab("成交明细", scroll(table(tradeModel)))
                addTab("订单组", JPanel(BorderLayout()).apply {
                    add(scroll(listTable), BorderLayout.CENTER)
                    add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(cancelList) }, BorderLayout.SOUTH)
                })
            }, BorderLayout.CENTER)
            add(message, BorderLayout.SOUTH)
        }, BorderLayout.CENTER)
        type.addActionListener { updateOrderKind() }
        side.addActionListener { updateEstimate() }
        submit.addActionListener { submit() }
        cancel.addActionListener { cancelSelected() }
        cancelAll.addActionListener { cancelAll() }
        cancelList.addActionListener { cancelSelectedList() }
        normalize.addActionListener { prepareCurrent() }
        symbol.addActionListener { service.refresh(selectedSymbol()); updateEstimate() }
        val documentListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = updateEstimate()
            override fun removeUpdate(e: DocumentEvent?) = updateEstimate()
            override fun changedUpdate(e: DocumentEvent?) = updateEstimate()
        }
        quantity.document.addDocumentListener(documentListener)
        price.document.addDocumentListener(documentListener)
        triggerPrice.document.addDocumentListener(documentListener)
        targetPrice.document.addDocumentListener(documentListener)
        orderTable.addMouseListener(orderDetailsListener(orderTable) { openOrders })
        historyTable.addMouseListener(orderDetailsListener(historyTable) { history })
        syncSymbols(initialSymbol)
        updateOrderKind()
        render()
    }
    override fun addNotify() { super.addNotify(); timer.start() }
    override fun removeNotify() { timer.stop(); super.removeNotify() }

    private fun submit() {
        val amount = quantity.text.trim().toBigDecimalOrNull()
        val orderKind = type.selectedItem as TestnetOrderKind
        val limit = price.text.trim().toBigDecimalOrNull()
        val trigger = triggerPrice.text.trim().toBigDecimalOrNull()
        val target = targetPrice.text.trim().toBigDecimalOrNull()
        if (amount == null || amount.signum() <= 0) { message.text = "请输入有效数量"; return }
        if (orderKind != TestnetOrderKind.MARKET && (limit == null || limit.signum() <= 0)) { message.text = "请输入有效限价"; return }
        if (orderKind in setOf(TestnetOrderKind.STOP_LOSS_LIMIT, TestnetOrderKind.TAKE_PROFIT_LIMIT, TestnetOrderKind.OCO) &&
            (trigger == null || trigger.signum() <= 0)) { message.text = "请输入有效触发价"; return }
        if (orderKind == TestnetOrderKind.OCO && (target == null || target.signum() <= 0)) { message.text = "请输入有效 OCO 目标价"; return }
        val selected = selectedSymbol()
        val sideValue = side.selectedItem as PaperOrderSide
        val notional = amount * (limit ?: market.quotes[selected]?.price ?: BigDecimal.ZERO)
        val risk = CryptoSettings.getInstance().state
        val warningAt = risk.testnetMaxOrderNotional.toBigDecimalOrNull()?.multiply(BigDecimal("0.8")) ?: BigDecimal.ZERO
        val warning = if (warningAt.signum() > 0 && notional >= warningAt)
            "\n⚠ 订单金额接近风控上限" else ""
        val detail = "$selected · ${sideValue.label} · ${orderKind.label} · 数量 ${marketPrice(amount)}" +
            (limit?.let { " · 限价 ${marketPrice(it)}" } ?: "") + (trigger?.let { " · 触发 ${marketPrice(it)}" } ?: "") +
            (target?.let { " · 目标 ${marketPrice(it)}" } ?: "") + riskRewardText(sideValue, amount, target, trigger)
        if (Messages.showYesNoDialog(project, "确认向 Binance Spot Testnet 提交：\n$detail$warning", "确认测试网订单", null) != Messages.YES) return
        submit.isEnabled = false; message.text = "正在提交测试网订单…"
        val complete: (Result<*>) -> Unit = { result ->
            submit.isEnabled = true
            message.text = result.fold({ value -> when (value) {
                is TestnetOrder -> "测试网订单已接受：#${value.id} · ${statusLabel(value.status)}"
                is TestnetOrderList -> "测试网 OCO 已接受：订单组 #${value.id}"
                else -> "测试网订单已接受"
            } }, { "提交失败：${it.message}" })
            if (result.isSuccess) { quantity.text = ""; fingerprint = "" }
        }
        when (orderKind) {
            TestnetOrderKind.MARKET -> service.place(selected, sideValue, PaperOrderType.MARKET, amount, null, complete)
            TestnetOrderKind.LIMIT -> service.place(selected, sideValue, PaperOrderType.LIMIT, amount, limit, complete)
            TestnetOrderKind.STOP_LOSS_LIMIT, TestnetOrderKind.TAKE_PROFIT_LIMIT ->
                service.placeConditional(selected, sideValue, orderKind, amount, trigger!!, limit!!, complete)
            TestnetOrderKind.OCO -> service.placeOco(selected, sideValue, amount, target!!, trigger!!, limit!!, complete)
        }
    }
    private fun cancelSelected() {
        val view = orderTable.selectedRow
        val order = openOrders.getOrNull(view.takeIf { it >= 0 }?.let(orderTable::convertRowIndexToModel) ?: -1)
            ?: run { message.text = "请先选择一条当前委托"; return }
        cancel.isEnabled = false
        service.cancel(order) { result -> cancel.isEnabled = true; message.text = result.fold({ "已撤销测试网委托 #${it.id}" }, { "撤单失败：${it.message}" }); fingerprint = "" }
    }
    private fun cancelAll() {
        val selected = selectedSymbol()
        val count = openOrders.count { it.symbol == selected }
        if (count == 0) { message.text = "$selected 暂无可撤委托"; return }
        if (Messages.showYesNoDialog(project, "确认撤销 $selected 的全部 $count 条测试网委托？", "确认批量撤单", null) != Messages.YES) return
        cancelAll.isEnabled = false; message.text = "正在撤销 $selected 的全部委托…"
        service.cancelAll(selected) { result ->
            cancelAll.isEnabled = true; fingerprint = ""
            message.text = result.fold({ "已撤销 $selected 的 ${it.size} 条委托" }, { "批量撤单失败：${it.message}" })
        }
    }
    private fun cancelSelectedList() {
        val row = listTable.selectedRow.takeIf { it >= 0 }?.let(listTable::convertRowIndexToModel) ?: run {
            message.text = "请先选择一个活动订单组"; return
        }
        val selected = orderLists.getOrNull(row) ?: return
        if (Messages.showYesNoDialog(project, "确认撤销订单组 #${selected.id}？", "确认撤销订单组", null) != Messages.YES) return
        cancelList.isEnabled = false
        service.cancelOrderList(selected) { result ->
            cancelList.isEnabled = true; fingerprint = ""
            message.text = result.fold({ "已撤销订单组 #${it.id}" }, { "订单组撤销失败：${it.message}" })
        }
    }
    private fun sizeByPercent(percent: Int) {
        prepare(BigDecimal(percent).movePointLeft(2), null)
    }
    private fun prepareCurrent() {
        val amount = quantity.text.trim().toBigDecimalOrNull()
        if (amount == null || amount.signum() <= 0) { message.text = "请输入需要取整的数量"; return }
        prepare(null, amount)
    }
    private fun prepare(fraction: BigDecimal?, amount: BigDecimal?) {
        val orderKind = type.selectedItem as TestnetOrderKind
        val orderType = if (orderKind == TestnetOrderKind.MARKET || orderKind == TestnetOrderKind.OCO) PaperOrderType.MARKET else PaperOrderType.LIMIT
        val limit = price.text.trim().toBigDecimalOrNull()
        normalize.isEnabled = false; message.text = if (fraction == null) "正在按测试网规则取整…" else "正在计算 ${fraction.movePointRight(2).toInt()}% 仓位…"
        service.prepareOrder(selectedSymbol(), side.selectedItem as PaperOrderSide, orderType, amount, limit, fraction) { result ->
            normalize.isEnabled = true
            result.onSuccess { draft ->
                quantity.text = draft.quantity.toPlainString()
                if (draft.price != null && orderKind != TestnetOrderKind.OCO) price.text = draft.price.toPlainString()
                message.text = "已按测试网规则填写 · 预计 ${marketPrice(draft.notional, 2)} USDT"
            }.onFailure { message.text = "计算失败：${it.message}" }
        }
    }
    private fun updateEstimate() {
        val amount = quantity.text.trim().toBigDecimalOrNull()
        val selected = selectedSymbol()
        val reference = if (type.selectedItem != TestnetOrderKind.MARKET) price.text.trim().toBigDecimalOrNull()
            else market.quotes[selected]?.price
        val available = if (side.selectedItem == PaperOrderSide.BUY) balances.firstOrNull { it.asset == "USDT" }?.free
        else balances.firstOrNull { it.asset == selected.removeSuffix("USDT") }?.free
        val unit = if (side.selectedItem == PaperOrderSide.BUY) "USDT" else selected.removeSuffix("USDT")
        estimate.text = "预计金额：${if (amount != null && reference != null) marketPrice(amount * reference, 2) + " USDT" else "—"}" +
            " · 可用：${available?.let(::marketPrice) ?: "—"} $unit"
    }
    private fun updateOrderKind() {
        val kind = type.selectedItem as TestnetOrderKind
        price.isEnabled = kind != TestnetOrderKind.MARKET
        triggerPrice.isEnabled = kind in setOf(TestnetOrderKind.STOP_LOSS_LIMIT, TestnetOrderKind.TAKE_PROFIT_LIMIT, TestnetOrderKind.OCO)
        targetPrice.isEnabled = kind == TestnetOrderKind.OCO
        updateEstimate()
    }
    private fun riskRewardText(side: PaperOrderSide, amount: BigDecimal, target: BigDecimal?, stop: BigDecimal?): String {
        val current = market.quotes[selectedSymbol()]?.price ?: return ""
        if (side != PaperOrderSide.SELL || target == null || stop == null || target <= current || stop >= current) return ""
        val reward = (target - current) * amount
        val loss = (current - stop) * amount
        val ratio = if (loss.signum() > 0) reward.divide(loss, 2, java.math.RoundingMode.HALF_UP) else BigDecimal.ZERO
        return "\n潜在收益 ${marketPrice(reward, 2)} USDT · 最大价格风险 ${marketPrice(loss, 2)} USDT · 盈亏比 $ratio"
    }
    private fun orderDetailsListener(table: JBTable, source: () -> List<TestnetOrder>) = object : java.awt.event.MouseAdapter() {
        override fun mouseClicked(event: java.awt.event.MouseEvent) {
            if (event.clickCount != 2) return
            val row = table.selectedRow.takeIf { it >= 0 }?.let(table::convertRowIndexToModel) ?: return
            source().getOrNull(row)?.let(::showOrderDetails)
        }
    }
    private fun showOrderDetails(order: TestnetOrder) {
        val detail = """
            订单 ID：${order.id}
            交易对：${order.symbol}
            方向 / 类型：${order.side.label} / ${rawTypeLabel(order.rawType)}
            状态：${statusLabel(order.status)}
            订单组 ID：${order.orderListId.takeIf { it >= 0 } ?: "—"}
            客户端 ID：${order.clientOrderId.ifBlank { "—" }}
            委托数量：${marketPrice(order.quantity)}
            已成交：${marketPrice(order.executedQuantity)}
            委托价格：${order.price.takeIf { it.signum() > 0 }?.let(::marketPrice) ?: "市价"}
            触发价格：${order.stopPrice.takeIf { it.signum() > 0 }?.let(::marketPrice) ?: "—"}
            成交均价：${order.averagePrice?.let(::marketPrice) ?: "—"}
            更新时间：${TIME.format(Instant.ofEpochMilli(order.time))}
        """.trimIndent()
        Messages.showInfoMessage(project, detail, "测试网订单详情")
    }
    private fun render() {
        syncSymbols(null)
        updateEstimate()
        val snapshot = service.snapshot
        val next = "$snapshot|${service.error}|${service.streamConnected}"
        if (next == fingerprint) return
        fingerprint = next
        balances = snapshot.balances.sortedWith(compareByDescending<TestnetBalance> { it.asset == "USDT" }.thenBy(TestnetBalance::asset))
        openOrders = snapshot.openOrders
        history = snapshot.history
        trades = snapshot.trades
        orderLists = snapshot.orderLists
        balanceModel.fireTableDataChanged(); orderModel.fireTableDataChanged(); historyModel.fireTableDataChanged(); tradeModel.fireTableDataChanged(); listModel.fireTableDataChanged()
        val usdt = balances.firstOrNull { it.asset == "USDT" }
        val prices = market.quotes.mapValues { it.value.price }
        val equity = testnetEquityUsdt(balances, prices)
        assetSummary.text = "测试网权益：${marketPrice(equity, 2)} USDT · ${marketPrice(usdt?.free ?: BigDecimal.ZERO, 2)} 可用 · ${marketPrice(usdt?.locked ?: BigDecimal.ZERO, 2)} 冻结"
        val selected = selectedSymbol()
        val current = market.quotes[selected]?.price ?: trades.firstOrNull()?.price ?: BigDecimal.ZERO
        val performance = analyzeTestnetTrades(selected, trades, current)
        val feeText = performance.fees.entries.joinToString(" + ") { "${marketPrice(it.value)} ${it.key}" }.ifBlank { "0" }
        performanceSummary.text = "近100笔成交估算：持仓 ${marketPrice(performance.position)} · 成本 ${marketPrice(performance.averageCost)} · " +
            "已实现 ${signedPrice(performance.realizedPnl)} USDT · 未实现 ${signedPrice(performance.unrealizedPnl)} USDT · 手续费 $feeText"
        updated.text = snapshot.updatedAt?.let { "更新：${TIME.format(it)}" } ?: "尚未同步"
        updateEstimate()
        service.error?.let { message.text = "同步失败：$it" }
    }
    private fun syncSymbols(preferred: String?) {
        val current = preferred ?: symbol.editor.item?.toString()
        val symbols = (CryptoSettings.getInstance().state.watchlist + market.pairs.filter { it.quote == "USDT" }.take(100).map(CryptoPair::symbol))
            .filter { it.endsWith("USDT") }.distinct()
        if ((0 until symbol.itemCount).map(symbol::getItemAt) != symbols) symbol.model = DefaultComboBoxModel(symbols.toTypedArray())
        symbol.selectedItem = normalizeMarketSymbol(current.orEmpty()).takeIf { it in symbols } ?: symbols.firstOrNull() ?: "BTCUSDT"
    }
    private fun selectedSymbol() = normalizeMarketSymbol(symbol.editor.item?.toString().orEmpty())
    private fun orderCell(order: TestnetOrder, column: Int): Any = when (column) {
        0 -> TIME.format(Instant.ofEpochMilli(order.time)); 1 -> order.symbol; 2 -> order.side.label; 3 -> rawTypeLabel(order.rawType)
        4 -> marketPrice(order.quantity); 5 -> marketPrice(order.executedQuantity)
        6 -> order.averagePrice?.let(::marketPrice) ?: order.price.takeIf { it.signum() > 0 }?.let(::marketPrice) ?: "—"
        else -> statusLabel(order.status)
    }
    private fun table(model: AbstractTableModel) = JBTable(model).apply { rowHeight = JBUI.scale(28); setShowGrid(false); autoCreateRowSorter = true; emptyText.text = "暂无数据" }
    private fun scroll(table: JBTable) = JBScrollPane(table).apply { setColumnHeaderView(table.tableHeader) }
    private fun statusLabel(status: String) = when (status) {
        "NEW" -> "挂单中"; "PARTIALLY_FILLED" -> "部分成交"; "FILLED" -> "已成交"; "CANCELED" -> "已撤销"
        "REJECTED" -> "已拒绝"; "EXPIRED" -> "已过期"; else -> status
    }
    private fun rawTypeLabel(type: String) = when (type) {
        "MARKET" -> "市价"; "LIMIT" -> "限价"; "LIMIT_MAKER" -> "只挂单"
        "STOP_LOSS", "STOP_LOSS_LIMIT" -> "止损限价"; "TAKE_PROFIT", "TAKE_PROFIT_LIMIT" -> "止盈限价"; else -> type
    }
    private fun signedPrice(value: BigDecimal) = (if (value.signum() > 0) "+" else "") + marketPrice(value, 2)
    companion object { private val TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()) }
}

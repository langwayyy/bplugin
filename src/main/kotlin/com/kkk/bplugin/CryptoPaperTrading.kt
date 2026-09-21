package com.kkk.bplugin

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

private val BPS = BigDecimal("10000")
private val ZERO = BigDecimal.ZERO

enum class PaperOrderSide(val label: String) { BUY("买入"), SELL("卖出"); override fun toString() = label }
enum class PaperOrderType(val label: String) { MARKET("市价"), LIMIT("限价"); override fun toString() = label }
enum class PaperOrderStatus(val label: String) { OPEN("挂单中"), FILLED("已成交"), CANCELLED("已撤销"); override fun toString() = label }

data class PaperPosition(
    val symbol: String,
    val quantity: BigDecimal,
    val averageCost: BigDecimal,
)

data class PaperOrder(
    val id: String = UUID.randomUUID().toString(),
    val symbol: String,
    val side: PaperOrderSide,
    val type: PaperOrderType,
    val quantity: BigDecimal,
    val limitPrice: BigDecimal? = null,
    val status: PaperOrderStatus = PaperOrderStatus.OPEN,
    val createdAt: Long = System.currentTimeMillis(),
    val filledAt: Long? = null,
    val fillPrice: BigDecimal? = null,
    val fee: BigDecimal = ZERO,
)

data class PaperAccount(
    val startingBalance: BigDecimal = BigDecimal("10000"),
    val cash: BigDecimal = startingBalance,
    val realizedPnl: BigDecimal = ZERO,
    val positions: List<PaperPosition> = emptyList(),
    val orders: List<PaperOrder> = emptyList(),
)

data class PaperAccountSummary(
    val cash: BigDecimal,
    val availableCash: BigDecimal,
    val marketValue: BigDecimal,
    val equity: BigDecimal,
    val unrealizedPnl: BigDecimal,
    val realizedPnl: BigDecimal,
    val totalReturn: BigDecimal,
)

data class PaperTradeResult(val accepted: Boolean, val message: String, val order: PaperOrder? = null)

/** Deterministic local spot simulator. It never sends account or order data over the network. */
internal class PaperTradingBook(
    initial: PaperAccount = PaperAccount(),
    private val feeBps: Int = 10,
    private val slippageBps: Int = 2,
) {
    var account: PaperAccount = sanitize(initial)
        private set

    fun place(symbol: String, side: PaperOrderSide, type: PaperOrderType, quantity: BigDecimal,
              limitPrice: BigDecimal?, marketPrice: BigDecimal?): PaperTradeResult {
        val normalized = normalizeMarketSymbol(symbol)
        if (!isCryptoSymbol(normalized) || !normalized.endsWith("USDT")) return rejected("模拟盘目前仅支持 USDT 现货交易对")
        if (quantity.signum() <= 0) return rejected("数量必须大于 0")
        if (type == PaperOrderType.LIMIT && (limitPrice == null || limitPrice.signum() <= 0)) return rejected("请输入有效限价")
        if (type == PaperOrderType.MARKET && (marketPrice == null || marketPrice.signum() <= 0)) return rejected("当前没有可用行情")

        val checkPrice = if (type == PaperOrderType.LIMIT) limitPrice!! else slipped(marketPrice!!, side)
        if (side == PaperOrderSide.BUY) {
            val required = feeAdjusted(quantity.multiply(checkPrice), add = true)
            if (required > availableCash()) return rejected("可用 USDT 不足，需要 ${money(required)}")
        } else if (quantity > availableQuantity(normalized)) {
            return rejected("可卖数量不足，当前可用 ${marketPrice(availableQuantity(normalized))}")
        }

        val order = PaperOrder(symbol = normalized, side = side, type = type,
            quantity = quantity.stripTrailingZeros(), limitPrice = limitPrice?.stripTrailingZeros())
        account = account.copy(orders = (listOf(order) + account.orders).take(500))
        if (type == PaperOrderType.MARKET) fill(order.id, slipped(marketPrice!!, side))
        return PaperTradeResult(true, if (type == PaperOrderType.MARKET) "模拟订单已成交" else "模拟限价单已提交", account.orders.first { it.id == order.id })
    }

    fun onPrice(symbol: String, price: BigDecimal): Boolean {
        if (price.signum() <= 0) return false
        val ids = account.orders.filter { order -> order.status == PaperOrderStatus.OPEN && order.symbol == symbol && order.type == PaperOrderType.LIMIT &&
            if (order.side == PaperOrderSide.BUY) price <= order.limitPrice!! else price >= order.limitPrice!!
        }.sortedBy(PaperOrder::createdAt).map(PaperOrder::id)
        ids.forEach { id ->
            val order = account.orders.first { it.id == id }
            val slipped = slipped(price, order.side)
            val fillPrice = if (order.side == PaperOrderSide.BUY) minOf(slipped, order.limitPrice!!) else maxOf(slipped, order.limitPrice!!)
            fill(id, fillPrice)
        }
        return ids.isNotEmpty()
    }

    fun cancel(id: String): Boolean {
        val target = account.orders.firstOrNull { it.id == id && it.status == PaperOrderStatus.OPEN } ?: return false
        account = account.copy(orders = account.orders.map { if (it.id == target.id) it.copy(status = PaperOrderStatus.CANCELLED) else it })
        return true
    }

    fun availableCash(): BigDecimal = account.orders.asSequence()
        .filter { it.status == PaperOrderStatus.OPEN && it.side == PaperOrderSide.BUY }
        .map { feeAdjusted(it.quantity.multiply(it.limitPrice!!), add = true) }
        .fold(account.cash, BigDecimal::subtract).max(ZERO)

    fun availableQuantity(symbol: String): BigDecimal {
        val held = account.positions.firstOrNull { it.symbol == symbol }?.quantity ?: ZERO
        val reserved = account.orders.asSequence().filter { it.status == PaperOrderStatus.OPEN && it.side == PaperOrderSide.SELL && it.symbol == symbol }
            .map(PaperOrder::quantity).fold(ZERO, BigDecimal::add)
        return held.subtract(reserved).max(ZERO)
    }

    fun summary(prices: Map<String, BigDecimal>): PaperAccountSummary {
        val marketValue = account.positions.fold(ZERO) { sum, position ->
            sum + position.quantity.multiply(prices[position.symbol] ?: position.averageCost)
        }
        val unrealized = account.positions.fold(ZERO) { sum, position ->
            sum + position.quantity.multiply((prices[position.symbol] ?: position.averageCost).subtract(position.averageCost))
        }
        val equity = account.cash + marketValue
        return PaperAccountSummary(account.cash, availableCash(), marketValue, equity, unrealized, account.realizedPnl,
            equity.subtract(account.startingBalance))
    }

    private fun fill(id: String, price: BigDecimal) {
        val order = account.orders.first { it.id == id }
        val gross = order.quantity.multiply(price)
        val fee = gross.multiply(BigDecimal(feeBps)).divide(BPS, 12, RoundingMode.HALF_UP).stripTrailingZeros()
        var cash = account.cash
        var realized = account.realizedPnl
        val positions = account.positions.toMutableList()
        val index = positions.indexOfFirst { it.symbol == order.symbol }
        val current = positions.getOrNull(index)
        if (order.side == PaperOrderSide.BUY) {
            cash = cash.subtract(gross).subtract(fee)
            val oldQuantity = current?.quantity ?: ZERO
            val newQuantity = oldQuantity + order.quantity
            val oldCost = current?.averageCost?.multiply(oldQuantity) ?: ZERO
            val average = oldCost.add(gross).add(fee).divide(newQuantity, 16, RoundingMode.HALF_UP).stripTrailingZeros()
            val next = PaperPosition(order.symbol, newQuantity.stripTrailingZeros(), average)
            if (index >= 0) positions[index] = next else positions.add(next)
        } else {
            check(current != null && current.quantity >= order.quantity)
            cash = cash.add(gross).subtract(fee)
            realized = realized.add(gross.subtract(fee).subtract(current.averageCost.multiply(order.quantity)))
            val remaining = current.quantity.subtract(order.quantity)
            if (remaining.signum() == 0) positions.removeAt(index) else positions[index] = current.copy(quantity = remaining.stripTrailingZeros())
        }
        val filled = order.copy(status = PaperOrderStatus.FILLED, filledAt = System.currentTimeMillis(),
            fillPrice = price.stripTrailingZeros(), fee = fee)
        account = account.copy(cash = cash.max(ZERO).stripTrailingZeros(), realizedPnl = realized.stripTrailingZeros(),
            positions = positions.sortedBy(PaperPosition::symbol), orders = account.orders.map { if (it.id == id) filled else it })
    }

    private fun slipped(price: BigDecimal, side: PaperOrderSide): BigDecimal {
        val ratio = BigDecimal(slippageBps).divide(BPS)
        return price.multiply(if (side == PaperOrderSide.BUY) BigDecimal.ONE + ratio else BigDecimal.ONE - ratio)
    }
    private fun feeAdjusted(gross: BigDecimal, add: Boolean) = gross.multiply(BigDecimal.ONE +
        BigDecimal(if (add) feeBps else -feeBps).divide(BPS))
    private fun rejected(message: String) = PaperTradeResult(false, message)
    private fun money(value: BigDecimal) = marketPrice(value, 2)
    private fun sanitize(raw: PaperAccount): PaperAccount {
        val starting = raw.startingBalance.takeIf { it.signum() > 0 } ?: BigDecimal("10000")
        return raw.copy(startingBalance = starting, cash = raw.cash.max(ZERO),
            positions = raw.positions.filter { isCryptoSymbol(it.symbol) && it.quantity.signum() > 0 && it.averageCost.signum() > 0 }.take(100),
            orders = raw.orders.filter { isCryptoSymbol(it.symbol) && it.quantity.signum() > 0 }.take(500))
    }
}

@Service(Service.Level.APP)
@State(name = "QuietCryptoPaperTrading", storages = [Storage("quiet-crypto-paper.xml")])
class CryptoPaperTradingService : PersistentStateComponent<CryptoPaperTradingService.StoredState> {
    data class StoredState(var accountJson: String = "")
    private var stored = StoredState()
    private var book = newBook(null)

    override fun getState(): StoredState = stored
    override fun loadState(state: StoredState) {
        stored = state
        book = newBook(runCatching { Gson().fromJson(state.accountJson, PaperAccount::class.java) }.getOrNull())
        save()
    }

    @Synchronized fun account(): PaperAccount = book.account
    @Synchronized fun summary(prices: Map<String, BigDecimal>): PaperAccountSummary = book.summary(prices)
    @Synchronized fun availableQuantity(symbol: String): BigDecimal = book.availableQuantity(normalizeMarketSymbol(symbol))
    @Synchronized fun place(symbol: String, side: PaperOrderSide, type: PaperOrderType, quantity: BigDecimal,
                            limitPrice: BigDecimal?, marketPrice: BigDecimal?): PaperTradeResult =
        book.place(symbol, side, type, quantity, limitPrice, marketPrice).also { if (it.accepted) save() }
    @Synchronized fun cancel(id: String): Boolean = book.cancel(id).also { if (it) save() }
    @Synchronized fun onQuote(quote: CryptoQuote) { if (book.onPrice(quote.symbol, quote.price)) save() }
    @Synchronized fun reset() { book = newBook(null); save() }

    private fun newBook(account: PaperAccount?): PaperTradingBook {
        val settings = CryptoSettings.getInstance().state
        val initial = settings.paperInitialBalance.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: BigDecimal("10000")
        return PaperTradingBook(account ?: PaperAccount(initial, initial), settings.paperFeeBps, settings.paperSlippageBps)
    }
    private fun save() { stored.accountJson = Gson().toJson(book.account) }

    companion object { fun getInstance() = ApplicationManager.getApplication().getService(CryptoPaperTradingService::class.java) }
}

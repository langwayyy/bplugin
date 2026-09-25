package com.kkk.bplugin

import java.math.BigDecimal

enum class ManagedOrderState(val label: String, val terminal: Boolean = false) {
    SUBMITTING("提交中"), NEW("挂单中"), PARTIALLY_FILLED("部分成交"), CANCEL_PENDING("撤单中"),
    FILLED("已成交", true), CANCELED("已撤销", true), REJECTED("已拒绝", true),
    EXPIRED("已过期", true), UNCERTAIN("待对账");
}

data class ManagedTestnetOrder(
    val clientOrderId: String,
    val exchangeOrderId: Long? = null,
    val symbol: String,
    val side: PaperOrderSide,
    val rawType: String,
    val quantity: BigDecimal,
    val executedQuantity: BigDecimal = BigDecimal.ZERO,
    val state: ManagedOrderState = ManagedOrderState.SUBMITTING,
    val source: String = "manual",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val message: String? = null,
)

internal class TestnetOrderLedger(initial: List<ManagedTestnetOrder> = emptyList()) {
    private val orders = initial.filter { it.clientOrderId.isNotBlank() }.associateBy { it.clientOrderId }.toMutableMap()

    fun snapshot(): List<ManagedTestnetOrder> = orders.values.sortedByDescending(ManagedTestnetOrder::updatedAt)
    fun find(clientOrderId: String) = orders[clientOrderId]
    fun active(): List<ManagedTestnetOrder> = orders.values.filterNot { it.state.terminal }

    fun submitting(clientOrderId: String, symbol: String, side: PaperOrderSide, rawType: String,
                   quantity: BigDecimal, source: String = "manual", now: Long = System.currentTimeMillis()) {
        val existing = orders[clientOrderId]
        orders[clientOrderId] = existing?.copy(updatedAt = now, message = null) ?:
            ManagedTestnetOrder(clientOrderId, symbol = normalizeMarketSymbol(symbol), side = side,
                rawType = rawType, quantity = quantity, source = source, createdAt = now, updatedAt = now)
        trim()
    }

    fun cancelPending(clientOrderId: String, now: Long = System.currentTimeMillis()) = update(clientOrderId) {
        it.copy(state = ManagedOrderState.CANCEL_PENDING, updatedAt = now, message = null)
    }

    fun uncertain(clientOrderId: String, message: String?, now: Long = System.currentTimeMillis()) = update(clientOrderId) {
        it.copy(state = ManagedOrderState.UNCERTAIN, updatedAt = now, message = message?.take(240))
    }

    fun rejected(clientOrderId: String, message: String?, now: Long = System.currentTimeMillis()) = update(clientOrderId) {
        it.copy(state = ManagedOrderState.REJECTED, updatedAt = now, message = message?.take(240))
    }

    fun merge(order: TestnetOrder): ManagedTestnetOrder {
        val id = order.clientOrderId.ifBlank { "exchange-${order.id}" }
        val previous = orders[id]
        val next = ManagedTestnetOrder(id, order.id, order.symbol, order.side, order.rawType, order.quantity,
            order.executedQuantity, managedState(order.status), previous?.source ?: "exchange",
            previous?.createdAt ?: order.time, order.time, null)
        orders[id] = next
        trim()
        return next
    }

    fun reconcile(remote: Collection<TestnetOrder>, now: Long = System.currentTimeMillis()) {
        remote.forEach(::merge)
        val remoteIds = remote.mapNotNull { it.clientOrderId.takeIf(String::isNotBlank) }.toSet()
        active().filter { it.clientOrderId !in remoteIds && now - it.updatedAt > 60_000 }.forEach {
            uncertain(it.clientOrderId, "币安活动订单与本地状态不一致，等待按订单号确认", now)
        }
        trim()
    }

    private fun update(id: String, block: (ManagedTestnetOrder) -> ManagedTestnetOrder) {
        orders[id]?.let { orders[id] = block(it) }
    }

    private fun trim() {
        if (orders.size <= 500) return
        orders.values.sortedByDescending(ManagedTestnetOrder::updatedAt).drop(500).forEach { orders.remove(it.clientOrderId) }
    }
}

internal fun managedState(status: String): ManagedOrderState = when (status) {
    "NEW", "PENDING_NEW" -> ManagedOrderState.NEW
    "PARTIALLY_FILLED" -> ManagedOrderState.PARTIALLY_FILLED
    "FILLED" -> ManagedOrderState.FILLED
    "CANCELED" -> ManagedOrderState.CANCELED
    "REJECTED" -> ManagedOrderState.REJECTED
    "EXPIRED", "EXPIRED_IN_MATCH" -> ManagedOrderState.EXPIRED
    else -> ManagedOrderState.UNCERTAIN
}

internal fun validateTestnetTradingGuard(enabled: Boolean, closeOnly: Boolean, side: PaperOrderSide): String? = when {
    !enabled -> "测试网新订单已停止，请先恢复下单"
    closeOnly && side == PaperOrderSide.BUY -> "当前为仅减仓模式，不能提交买入订单"
    else -> null
}

enum class TestnetFailureKind { RATE_LIMIT, CLOCK, AUTH, ORDER_REJECTED, NETWORK, UNKNOWN }

internal fun classifyTestnetFailure(error: Throwable): TestnetFailureKind = when {
    error is BinanceTestnetException && error.code == -1003 -> TestnetFailureKind.RATE_LIMIT
    error is BinanceTestnetException && error.code == -1021 -> TestnetFailureKind.CLOCK
    error is BinanceTestnetException && error.code in setOf(-1022, -2015) -> TestnetFailureKind.AUTH
    error is BinanceTestnetException && error.code in setOf(-1013, -2010, -2011, -2013) -> TestnetFailureKind.ORDER_REJECTED
    error is java.io.IOException || error.cause is java.io.IOException -> TestnetFailureKind.NETWORK
    else -> TestnetFailureKind.UNKNOWN
}

internal fun testnetRetryDelayMillis(error: Throwable): Long = when (classifyTestnetFailure(error)) {
    TestnetFailureKind.RATE_LIMIT -> 60_000
    TestnetFailureKind.AUTH -> 5 * 60_000
    TestnetFailureKind.CLOCK -> 1_000
    TestnetFailureKind.NETWORK -> 15_000
    TestnetFailureKind.ORDER_REJECTED, TestnetFailureKind.UNKNOWN -> 30_000
}

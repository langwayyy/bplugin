package com.kkk.bplugin

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.net.HttpConfigurable
import java.net.Authenticator
import java.net.PasswordAuthentication
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.math.BigDecimal
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class TradingAccountMode(val label: String) {
    LOCAL("本地模拟"), TESTNET("币安测试网");
    override fun toString() = label
}

enum class TestnetOrderKind(val label: String) {
    MARKET("市价"), LIMIT("限价"), STOP_LOSS_LIMIT("止损限价"), TAKE_PROFIT_LIMIT("止盈限价"), OCO("OCO 止盈止损");
    override fun toString() = label
}

data class TestnetSnapshot(
    val balances: List<TestnetBalance> = emptyList(),
    val openOrders: List<TestnetOrder> = emptyList(),
    val history: List<TestnetOrder> = emptyList(),
    val trades: List<TestnetTrade> = emptyList(),
    val orderLists: List<TestnetOrderList> = emptyList(),
    val updatedAt: Instant? = null,
)

data class TestnetOrderDraft(
    val quantity: BigDecimal,
    val price: BigDecimal?,
    val referencePrice: BigDecimal,
) {
    val notional: BigDecimal get() = quantity * referencePrice
}

data class TestnetExecutionEvent(
    val orderId: Long,
    val symbol: String,
    val status: String,
    val executedQuantity: BigDecimal,
    val cumulativeQuoteQuantity: BigDecimal,
    val time: Long,
)
data class TestnetOcoDraft(val quantity: BigDecimal, val targetPrice: BigDecimal, val stopPrice: BigDecimal, val stopLimitPrice: BigDecimal)
data class TestnetOrderListEvent(val orderListId: Long, val status: String, val time: Long)
data class TestnetValidationResult(val symbol: String, val clientOrderId: String, val orderId: Long?,
                                   val finalStatus: String, val message: String)

object BinanceTestnetCredentials {
    private val attributes = CredentialAttributes("QuietCrypto.BinanceSpotTestnet.Secret")
    fun apiKey(): String = PasswordSafe.instance.get(attributes)?.userName.orEmpty().takeUnless { it == "spot-testnet" }.orEmpty()
    fun secret(): String = PasswordSafe.instance.get(attributes)?.getPasswordAsString().orEmpty()
    fun save(apiKey: String, secret: String) {
        PasswordSafe.instance.set(attributes, if (apiKey.isNotBlank() && secret.isNotBlank()) Credentials(apiKey.trim(), secret) else null)
    }
    fun clear() { PasswordSafe.instance.set(attributes, null) }
}

@Service(Service.Level.APP)
@State(name = "QuietCryptoTestnetOrders", storages = [Storage("quiet-crypto-testnet-orders.xml")])
class CryptoTestnetTradingService : Disposable, PersistentStateComponent<CryptoTestnetTradingService.StoredState> {
    data class StoredState(var ordersJson: String = "[]", var lastReconciledAt: Long = 0)
    private val client = BinanceTestnetClient()
    private val busy = AtomicBoolean()
    @Volatile private var pendingSymbol: String? = null
    @Volatile private var nextRefresh = 0L
    @Volatile private var retryAt = 0L
    @Volatile var snapshot = TestnetSnapshot(); private set
    @Volatile var error: String? = null; private set
    @Volatile var streamConnected = false; private set
    @Volatile var streamMessage: String? = null; private set
    @Volatile private var disposed = false
    @Volatile private var lastOrderFingerprint = ""
    @Volatile private var lastOrderAt = 0L
    private var stored = StoredState()
    private var ledger = TestnetOrderLedger()
    private val stream = BinanceTestnetUserStream(
        onEvent = { event -> if (event == null || !applyExecutionEvent(event)) refresh(pendingSymbol) },
        onListEvent = { event -> if (!applyOrderListEvent(event)) refresh(pendingSymbol) },
        onState = { connected, message ->
            streamConnected = connected; streamMessage = message
            if (!connected) retryAt = System.currentTimeMillis() + 15_000
        },
    )
    private val schedule = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
        if (disposed) return@scheduleWithFixedDelay
        val settings = CryptoSettings.getInstance().state
        if (settings.tradingMode != TradingAccountMode.TESTNET.name || !hasCredentials()) { stream.disconnect(); return@scheduleWithFixedDelay }
        val now = System.currentTimeMillis()
        if (now >= retryAt) ensureStream()
        if (now >= nextRefresh) refresh(pendingSymbol)
    }, 2, 5, TimeUnit.SECONDS)

    fun hasCredentials() = credentials() != null
    @Synchronized fun managedOrders(): List<ManagedTestnetOrder> = ledger.snapshot()
    @Synchronized fun lastReconciledAt(): Long = stored.lastReconciledAt
    fun tradingEnabled() = CryptoSettings.getInstance().state.testnetTradingEnabled
    fun closeOnly() = CryptoSettings.getInstance().state.testnetCloseOnly
    fun setTradingEnabled(value: Boolean) { CryptoSettings.getInstance().state.testnetTradingEnabled = value }
    fun setCloseOnly(value: Boolean) { CryptoSettings.getInstance().state.testnetCloseOnly = value }
    fun trackedSymbols(): List<String> = snapshot.balances.filter { it.asset != "USDT" && it.total.signum() > 0 }
        .map { "${it.asset}USDT" }.filter(::isCryptoSymbol).distinct()

    fun credentialsChanged() {
        stream.disconnect()
        streamConnected = false
        retryAt = 0
        nextRefresh = 0
        if (hasCredentials()) refresh(pendingSymbol) else snapshot = TestnetSnapshot()
    }

    fun accountModeChanged(mode: TradingAccountMode) {
        if (mode == TradingAccountMode.TESTNET) credentialsChanged()
        else { stream.disconnect(); streamConnected = false; streamMessage = null }
    }

    fun testConnection(callback: (Result<List<TestnetBalance>>) -> Unit) {
        val credentials = credentials() ?: return callback(Result.failure(IllegalStateException("请先填写测试网 API Key 和 Secret")))
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { client.synchronizeTime(); client.account(credentials.first, credentials.second) }
            ApplicationManager.getApplication().invokeLater { callback(result) }
        }
    }

    fun validatePermissions(symbol: String, callback: (Result<TestnetValidationResult>) -> Unit) {
        val credentials = credentials() ?: return callback(Result.failure(IllegalStateException("请先填写测试网 API Key 和 Secret")))
        if (!busy.compareAndSet(false, true)) return callback(Result.failure(IllegalStateException("测试网请求正在处理中")))
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                client.synchronizeTime()
                client.account(credentials.first, credentials.second)
                val normalized = normalizeMarketSymbol(symbol)
                val current = client.tickerPrice(normalized)
                val rules = client.rules(normalized)
                val price = rules.normalizePrice(current.multiply(BigDecimal("0.99")))
                val quantity = rules.minimumQuantity(price)
                val id = clientId("test", "$normalized|$quantity|$price|${System.currentTimeMillis()}")
                client.testOrder(normalized, PaperOrderSide.BUY, quantity, price, id, credentials.first, credentials.second)
                TestnetValidationResult(normalized, id, null, "TEST_ACCEPTED", "签名、交易权限和交易规则预检通过，未创建订单")
            }
            busy.set(false)
            ApplicationManager.getApplication().invokeLater { callback(result) }
        }
    }

    fun validateOrderRoundTrip(symbol: String, callback: (Result<TestnetValidationResult>) -> Unit) {
        val credentials = credentials() ?: return callback(Result.failure(IllegalStateException("请先配置测试网凭据")))
        tradingGuard(PaperOrderSide.BUY)?.let { return callback(Result.failure(IllegalStateException(it))) }
        if (!busy.compareAndSet(false, true)) return callback(Result.failure(IllegalStateException("测试网请求正在处理中")))
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                requireFreshSnapshot()
                val normalized = normalizeMarketSymbol(symbol)
                val current = client.tickerPrice(normalized)
                val rules = client.rules(normalized)
                val deviation = minOf(CryptoSettings.getInstance().state.testnetMaxPriceDeviationPercent.toBigDecimal()
                    .divide(BigDecimal(2)), BigDecimal.ONE).max(BigDecimal("0.1"))
                val price = rules.normalizePrice(current.multiply(BigDecimal.ONE - deviation.movePointLeft(2)))
                val quantity = rules.minimumQuantity(price)
                validateRisk(normalized, PaperOrderSide.BUY, quantity, price, current)
                val id = clientId("verify", "$normalized|$quantity|$price|${System.currentTimeMillis()}")
                val placed = submitWithRecovery(id, normalized, PaperOrderSide.BUY, "LIMIT", quantity, credentials) {
                    client.placeOrder(normalized, PaperOrderSide.BUY, PaperOrderType.LIMIT, quantity, price,
                        credentials.first, credentials.second, id)
                }
                val queried = client.queryOrder(normalized, id, credentials.first, credentials.second)
                synchronized(this) { ledger.merge(queried); saveLedger() }
                val final = if (queried.status in setOf("NEW", "PARTIALLY_FILLED")) {
                    client.cancelOrder(normalized, placed.id, credentials.first, credentials.second).also {
                        synchronized(this) { ledger.merge(it); saveLedger() }
                    }
                } else queried
                TestnetValidationResult(normalized, id, final.id, final.status,
                    if (final.status == "FILLED") "验证订单已在撤销前成交" else "下单、按客户端 ID 查询和撤单链路通过")
            }
            busy.set(false)
            result.onSuccess { refresh(symbol) }.onFailure { error = it.message }
            ApplicationManager.getApplication().invokeLater { callback(result) }
        }
    }

    fun refresh(symbol: String? = null) {
        val normalized = symbol?.let(::normalizeMarketSymbol)?.takeIf(::isCryptoSymbol)
        if (normalized != null) pendingSymbol = normalized
        val credentials = credentials() ?: return
        if (!busy.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val balances = client.account(credentials.first, credentials.second)
                val orders = client.openOrders(credentials.first, credentials.second)
                val symbols = synchronized(this) { (ledger.active().map(ManagedTestnetOrder::symbol) +
                    listOfNotNull(pendingSymbol) + orders.map(TestnetOrder::symbol)).distinct().take(20) }
                val histories = symbols.associateWith { client.allOrders(it, credentials.first, credentials.second) }
                val history = pendingSymbol?.let { histories[it] }.orEmpty()
                val trades = pendingSymbol?.let { client.trades(it, credentials.first, credentials.second) }.orEmpty()
                val orderLists = client.openOrderLists(credentials.first, credentials.second)
                val next = TestnetSnapshot(balances, orders, history.filter { it.status !in setOf("NEW", "PARTIALLY_FILLED") }, trades, orderLists, Instant.now())
                val newUncertain = synchronized(this) {
                    val before = ledger.snapshot().count { it.state == ManagedOrderState.UNCERTAIN }
                    ledger.reconcile(orders + histories.values.flatten())
                    val count = (ledger.snapshot().count { it.state == ManagedOrderState.UNCERTAIN } - before).coerceAtLeast(0)
                    stored.lastReconciledAt = System.currentTimeMillis()
                    saveLedger()
                    count
                }
                if (newUncertain > 0) CryptoNotifications.warn("测试网订单需要核对", "$newUncertain 条本地订单未能在币安状态中确认")
                next
            }
            result.onSuccess { snapshot = it; error = null; retryAt = 0; nextRefresh = System.currentTimeMillis() + 30_000 }
                .onFailure { error = it.message ?: "测试网账户同步失败"; retryAt = System.currentTimeMillis() + testnetRetryDelayMillis(it) }
            busy.set(false)
        }
    }

    fun place(symbol: String, side: PaperOrderSide, type: PaperOrderType, quantity: java.math.BigDecimal,
              price: java.math.BigDecimal?, callback: (Result<TestnetOrder>) -> Unit, requestedClientOrderId: String? = null) {
        val credentials = credentials() ?: return callback(Result.failure(IllegalStateException("请先配置测试网凭据")))
        tradingGuard(side)?.let { return callback(Result.failure(IllegalStateException(it))) }
        if (!busy.compareAndSet(false, true)) return callback(Result.failure(IllegalStateException("测试网请求正在处理中")))
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val updated = snapshot.updatedAt ?: error("账户尚未完成同步")
                if (updated.plusSeconds(60).isBefore(Instant.now())) error("账户数据已超过 60 秒，请先同步账户")
                val currentPrice = client.tickerPrice(symbol)
                val referencePrice = if (type == PaperOrderType.MARKET) currentPrice else price
                client.rules(symbol, type).validate(quantity, referencePrice, type == PaperOrderType.LIMIT)?.let { error(it) }
                val settings = CryptoSettings.getInstance().state
                val policy = TestnetRiskPolicy(settings.testnetMaxOrderPercent,
                    settings.testnetMaxOrderNotional.toBigDecimal(), settings.testnetMaxPriceDeviationPercent)
                validateTestnetRisk(symbol, side, quantity, requireNotNull(referencePrice), currentPrice, snapshot.balances, policy)?.let { error(it) }
                val fingerprint = "${normalizeMarketSymbol(symbol)}|$side|$type|${quantity.stripTrailingZeros()}|${price?.stripTrailingZeros()}"
                val now = System.currentTimeMillis()
                if (fingerprint == lastOrderFingerprint && now - lastOrderAt < 5_000) error("检测到重复订单，请稍后重试")
                lastOrderFingerprint = fingerprint; lastOrderAt = now
                val id = requestedClientOrderId ?: clientId("ord", fingerprint)
                submitWithRecovery(id, symbol, side, type.name, quantity, credentials) {
                    client.placeOrder(symbol, side, type, quantity, price, credentials.first, credentials.second, id)
                }
            }
            busy.set(false)
            result.onSuccess { refresh(symbol) }.onFailure { error = it.message }
            ApplicationManager.getApplication().invokeLater { callback(result) }
        }
    }

    fun cancel(order: TestnetOrder, callback: (Result<TestnetOrder>) -> Unit) {
        val credentials = credentials() ?: return callback(Result.failure(IllegalStateException("请先配置测试网凭据")))
        if (!busy.compareAndSet(false, true)) return callback(Result.failure(IllegalStateException("测试网请求正在处理中")))
        ApplicationManager.getApplication().executeOnPooledThread {
            synchronized(this) { ledger.cancelPending(order.clientOrderId); saveLedger() }
            val result = runCatching { client.cancelOrder(order.symbol, order.id, credentials.first, credentials.second).also {
                synchronized(this) { ledger.merge(it); saveLedger() }
            } }.onFailure { synchronized(this) { ledger.uncertain(order.clientOrderId, it.message); saveLedger() } }
            busy.set(false)
            result.onSuccess { refresh(order.symbol) }.onFailure { error = it.message }
            ApplicationManager.getApplication().invokeLater { callback(result) }
        }
    }

    fun placeConditional(symbol: String, side: PaperOrderSide, kind: TestnetOrderKind, quantity: BigDecimal,
                         triggerPrice: BigDecimal, limitPrice: BigDecimal, callback: (Result<TestnetOrder>) -> Unit) {
        val credentials = credentials() ?: return callback(Result.failure(IllegalStateException("请先配置测试网凭据")))
        tradingGuard(side)?.let { return callback(Result.failure(IllegalStateException(it))) }
        if (kind !in setOf(TestnetOrderKind.STOP_LOSS_LIMIT, TestnetOrderKind.TAKE_PROFIT_LIMIT))
            return callback(Result.failure(IllegalArgumentException("条件订单类型无效")))
        if (!busy.compareAndSet(false, true)) return callback(Result.failure(IllegalStateException("测试网请求正在处理中")))
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                requireFreshSnapshot()
                val current = client.tickerPrice(symbol)
                validateConditionalTrigger(kind, side, triggerPrice, current)?.let { error(it) }
                val rules = client.rules(symbol)
                rules.validate(quantity, limitPrice)?.let { error(it) }
                rules.validate(quantity, triggerPrice)?.let { error("触发价：$it") }
                validateRisk(symbol, side, quantity, limitPrice, current)
                val fingerprint = "${normalizeMarketSymbol(symbol)}|$side|$kind|$quantity|$triggerPrice|$limitPrice"
                ensureUnique(fingerprint)
                val id = clientId("ord", fingerprint)
                submitWithRecovery(id, symbol, side, kind.name, quantity, credentials) {
                    client.placeConditionalOrder(symbol, side, kind.name, quantity, triggerPrice, limitPrice,
                        id, credentials.first, credentials.second)
                }
            }
            busy.set(false); result.onSuccess { refresh(symbol) }.onFailure { error = it.message }
            ApplicationManager.getApplication().invokeLater { callback(result) }
        }
    }

    fun placeOco(symbol: String, side: PaperOrderSide, quantity: BigDecimal, targetPrice: BigDecimal,
                 stopPrice: BigDecimal, stopLimitPrice: BigDecimal, callback: (Result<TestnetOrderList>) -> Unit,
                 requestedClientOrderId: String? = null) {
        val credentials = credentials() ?: return callback(Result.failure(IllegalStateException("请先配置测试网凭据")))
        tradingGuard(side)?.let { return callback(Result.failure(IllegalStateException(it))) }
        if (!busy.compareAndSet(false, true)) return callback(Result.failure(IllegalStateException("测试网请求正在处理中")))
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                requireFreshSnapshot()
                val current = client.tickerPrice(symbol)
                validateOcoPrices(side, current, targetPrice, stopPrice, stopLimitPrice)?.let { error(it) }
                val rules = client.rules(symbol)
                listOf(targetPrice, stopPrice, stopLimitPrice).forEach { value -> rules.validate(quantity, value)?.let { error(it) } }
                val riskPrice = if (side == PaperOrderSide.BUY) maxOf(targetPrice, stopLimitPrice) else current
                validateRisk(symbol, side, quantity, riskPrice, current)
                val fingerprint = "${normalizeMarketSymbol(symbol)}|$side|OCO|$quantity|$targetPrice|$stopPrice|$stopLimitPrice"
                ensureUnique(fingerprint)
                client.placeOco(symbol, side, quantity, targetPrice, stopPrice, stopLimitPrice,
                    requestedClientOrderId ?: clientId("list", fingerprint), credentials.first, credentials.second)
            }
            busy.set(false); result.onSuccess { refresh(symbol) }.onFailure { error = it.message }
            ApplicationManager.getApplication().invokeLater { callback(result) }
        }
    }

    fun cancelOrderList(orderList: TestnetOrderList, callback: (Result<TestnetOrderList>) -> Unit) {
        val credentials = credentials() ?: return callback(Result.failure(IllegalStateException("请先配置测试网凭据")))
        if (!busy.compareAndSet(false, true)) return callback(Result.failure(IllegalStateException("测试网请求正在处理中")))
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { client.cancelOrderList(orderList.symbol, orderList.id, credentials.first, credentials.second) }
            busy.set(false); result.onSuccess { refresh(orderList.symbol) }.onFailure { error = it.message }
            ApplicationManager.getApplication().invokeLater { callback(result) }
        }
    }

    fun prepareOrder(symbol: String, side: PaperOrderSide, type: PaperOrderType, quantity: BigDecimal?,
                     price: BigDecimal?, fraction: BigDecimal? = null, callback: (Result<TestnetOrderDraft>) -> Unit) {
        if (!busy.compareAndSet(false, true)) return callback(Result.failure(IllegalStateException("测试网请求正在处理中")))
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val normalized = normalizeMarketSymbol(symbol)
                val rules = client.rules(normalized, type)
                val reference = if (type == PaperOrderType.LIMIT && price != null && price.signum() > 0)
                    rules.normalizePrice(price) else client.tickerPrice(normalized)
                val rawQuantity = if (fraction != null) {
                    val free = if (side == PaperOrderSide.BUY) snapshot.balances.firstOrNull { it.asset == "USDT" }?.free
                    else snapshot.balances.firstOrNull { it.asset == normalized.removeSuffix("USDT") }?.free
                    require(free != null && free.signum() > 0) { if (side == PaperOrderSide.BUY) "USDT 可用余额不足" else "可卖资产余额不足" }
                    if (side == PaperOrderSide.BUY) free.multiply(fraction).divide(reference, 16, java.math.RoundingMode.DOWN)
                    else free * fraction
                } else requireNotNull(quantity) { "请输入数量" }
                val normalizedQuantity = rules.normalizeQuantity(rawQuantity)
                rules.validate(normalizedQuantity, reference, type == PaperOrderType.LIMIT)?.let { error(it) }
                TestnetOrderDraft(normalizedQuantity, if (type == PaperOrderType.LIMIT) reference else null, reference)
            }
            busy.set(false)
            ApplicationManager.getApplication().invokeLater { callback(result) }
        }
    }

    fun prepareOco(symbol: String, quantity: BigDecimal, targetPrice: BigDecimal, stopPrice: BigDecimal,
                   stopLimitPrice: BigDecimal, callback: (Result<TestnetOcoDraft>) -> Unit) {
        if (!busy.compareAndSet(false, true)) return callback(Result.failure(IllegalStateException("测试网请求正在处理中")))
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val normalized = normalizeMarketSymbol(symbol)
                val rules = client.rules(normalized, PaperOrderType.LIMIT)
                val draft = TestnetOcoDraft(rules.normalizeQuantity(quantity), rules.normalizePrice(targetPrice),
                    rules.normalizePrice(stopPrice), rules.normalizePrice(stopLimitPrice))
                listOf(draft.targetPrice, draft.stopPrice, draft.stopLimitPrice).forEach { value ->
                    rules.validate(draft.quantity, value)?.let { error(it) }
                }
                draft
            }
            busy.set(false)
            ApplicationManager.getApplication().invokeLater { callback(result) }
        }
    }

    fun cancelAll(symbol: String, callback: (Result<List<TestnetOrder>>) -> Unit) {
        val credentials = credentials() ?: return callback(Result.failure(IllegalStateException("请先配置测试网凭据")))
        if (!busy.compareAndSet(false, true)) return callback(Result.failure(IllegalStateException("测试网请求正在处理中")))
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { client.cancelOpenOrders(symbol, credentials.first, credentials.second) }
            busy.set(false)
            result.onSuccess { refresh(symbol) }.onFailure { error = it.message }
            ApplicationManager.getApplication().invokeLater { callback(result) }
        }
    }

    @Synchronized private fun applyExecutionEvent(event: TestnetExecutionEvent): Boolean {
        val existing = (snapshot.openOrders + snapshot.history).firstOrNull { it.id == event.orderId } ?: return false
        val average = event.cumulativeQuoteQuantity.takeIf { event.executedQuantity.signum() > 0 }
            ?.divide(event.executedQuantity, 16, java.math.RoundingMode.HALF_UP)?.stripTrailingZeros()
        val updated = existing.copy(status = event.status, executedQuantity = event.executedQuantity,
            averagePrice = average ?: existing.averagePrice, time = event.time)
        val active = event.status in setOf("NEW", "PARTIALLY_FILLED")
        snapshot = snapshot.copy(
            openOrders = (snapshot.openOrders.filterNot { it.id == event.orderId } + if (active) listOf(updated) else emptyList()).sortedByDescending(TestnetOrder::time),
            history = (snapshot.history.filterNot { it.id == event.orderId } + if (active) emptyList() else listOf(updated)).sortedByDescending(TestnetOrder::time),
        )
        ledger.merge(updated); saveLedger()
        CryptoStrategyService.getInstance().recordTestnetOrderUpdate(updated)
        return true
    }
    @Synchronized private fun applyOrderListEvent(event: TestnetOrderListEvent): Boolean {
        val existing = snapshot.orderLists.firstOrNull { it.id == event.orderListId } ?: return false
        val updated = existing.copy(status = event.status, time = event.time)
        snapshot = snapshot.copy(orderLists = if (event.status in setOf("ALL_DONE", "REJECT"))
            snapshot.orderLists.filterNot { it.id == event.orderListId }
        else snapshot.orderLists.map { if (it.id == event.orderListId) updated else it })
        return true
    }

    private fun requireFreshSnapshot() {
        val updated = snapshot.updatedAt ?: error("账户尚未完成同步")
        if (updated.plusSeconds(60).isBefore(Instant.now())) error("账户数据已超过 60 秒，请先同步账户")
    }
    private fun validateRisk(symbol: String, side: PaperOrderSide, quantity: BigDecimal, orderPrice: BigDecimal, current: BigDecimal) {
        val settings = CryptoSettings.getInstance().state
        validateTestnetRisk(symbol, side, quantity, orderPrice, current, snapshot.balances,
            TestnetRiskPolicy(settings.testnetMaxOrderPercent, settings.testnetMaxOrderNotional.toBigDecimal(),
                settings.testnetMaxPriceDeviationPercent))?.let { error(it) }
    }
    private fun tradingGuard(side: PaperOrderSide): String? {
        val settings = CryptoSettings.getInstance().state
        return validateTestnetTradingGuard(settings.testnetTradingEnabled, settings.testnetCloseOnly, side)
    }

    private fun submitWithRecovery(clientOrderId: String, symbol: String, side: PaperOrderSide, rawType: String,
                                   quantity: BigDecimal, credentials: Pair<String, String>, submit: () -> TestnetOrder): TestnetOrder {
        synchronized(this) { ledger.submitting(clientOrderId, symbol, side, rawType, quantity); saveLedger() }
        return try {
            submit().also { synchronized(this) { ledger.merge(it); saveLedger() } }
        } catch (failure: Throwable) {
            val recovered = runCatching { client.queryOrder(symbol, clientOrderId, credentials.first, credentials.second) }.getOrNull()
            if (recovered != null) {
                synchronized(this) { ledger.merge(recovered); saveLedger() }
                recovered
            } else {
                synchronized(this) {
                    if (failure is BinanceTestnetException && failure.code in setOf(-1013, -2010, -2015))
                        ledger.rejected(clientOrderId, failure.message)
                    else ledger.uncertain(clientOrderId, failure.message)
                    saveLedger()
                }
                throw failure
            }
        }
    }
    private fun ensureUnique(fingerprint: String) {
        val now = System.currentTimeMillis()
        if (fingerprint == lastOrderFingerprint && now - lastOrderAt < 5_000) error("检测到重复订单，请稍后重试")
        lastOrderFingerprint = fingerprint; lastOrderAt = now
    }
    private fun clientId(prefix: String, fingerprint: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(fingerprint.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(16)
        val minute = (System.currentTimeMillis() / 60_000).toString(36)
        return "qc-$prefix-$digest-$minute".take(36)
    }

    private fun credentials(): Pair<String, String>? {
        val settings = CryptoSettings.getInstance().state
        var key = BinanceTestnetCredentials.apiKey()
        val secret = BinanceTestnetCredentials.secret()
        val legacyKey = settings.testnetApiKey.trim()
        if (key.isBlank() && legacyKey.isNotBlank() && secret.isNotBlank()) {
            BinanceTestnetCredentials.save(legacyKey, secret)
            settings.testnetApiKey = ""
            key = legacyKey
        }
        return if (key.isBlank() || secret.isBlank()) null else key to secret
    }
    private fun ensureStream() {
        val credentials = credentials() ?: return
        if (stream.isActive()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { client.synchronizeTime(); stream.ensure(credentials.first, credentials.second, client.timestamp()) }
                .onFailure { streamConnected = false; streamMessage = it.message; retryAt = System.currentTimeMillis() + testnetRetryDelayMillis(it) }
        }
    }
    override fun dispose() { disposed = true; schedule.cancel(false); stream.close() }

    override fun getState(): StoredState = stored
    override fun loadState(state: StoredState) {
        stored = state
        val type = object : TypeToken<List<ManagedTestnetOrder>>() {}.type
        val restored = runCatching { Gson().fromJson<List<ManagedTestnetOrder>>(state.ordersJson, type) }.getOrDefault(emptyList())
            .filter { it.clientOrderId.isNotBlank() && isCryptoSymbol(it.symbol) }
        synchronized(this) { ledger = TestnetOrderLedger(restored) }
    }
    private fun saveLedger() { stored.ordersJson = Gson().toJson(ledger.snapshot()) }

    companion object { fun getInstance() = ApplicationManager.getApplication().getService(CryptoTestnetTradingService::class.java) }
}

/** Signed Spot Testnet user-data subscription. Events are followed by a REST state reconciliation. */
internal class BinanceTestnetUserStream(
    private val onEvent: (TestnetExecutionEvent?) -> Unit,
    private val onListEvent: (TestnetOrderListEvent) -> Unit,
    private val onState: (Boolean, String?) -> Unit,
) : AutoCloseable {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).apply {
        val proxy = HttpConfigurable.getInstance()
        proxy(proxy.onlyBySettingsSelector)
        if (proxy.PROXY_AUTHENTICATION) authenticator(object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(proxy.proxyLogin.orEmpty(), proxy.plainProxyPassword.orEmpty().toCharArray())
        })
    }.build()
    private val generation = AtomicLong()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var connecting = false
    @Volatile private var lastMessageAt: Instant? = null

    fun isActive() = connecting || (socket != null && lastMessageAt?.plusSeconds(45)?.isAfter(Instant.now()) != false)

    @Synchronized fun ensure(apiKey: String, secret: String, timestamp: Long) {
        if (isActive()) return
        disconnect()
        connecting = true
        val token = generation.incrementAndGet()
        val payload = "apiKey=$apiKey&timestamp=$timestamp"
        val signature = BinanceTestnetClient.sign(payload, secret)
        val subscription = JsonObject().apply {
            addProperty("id", "quiet-crypto-user-stream")
            addProperty("method", "userDataStream.subscribe.signature")
            add("params", JsonObject().apply {
                addProperty("apiKey", apiKey); addProperty("timestamp", timestamp); addProperty("signature", signature)
            })
        }.toString()
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
            .buildAsync(URI.create("wss://ws-api.testnet.binance.vision/ws-api/v3"), Listener(token, subscription))
            .whenComplete { webSocket, failure -> synchronized(this) {
                if (token != generation.get()) { webSocket?.abort(); return@whenComplete }
                connecting = false
                if (failure != null) { socket = null; onState(false, failure.cause?.message ?: failure.message) } else socket = webSocket
            } }
    }

    @Synchronized fun disconnect() {
        generation.incrementAndGet(); connecting = false; lastMessageAt = null
        socket?.sendClose(WebSocket.NORMAL_CLOSURE, "client refresh"); socket = null
    }
    override fun close() = disconnect()

    private inner class Listener(private val token: Long, private val subscription: String) : WebSocket.Listener {
        private val text = StringBuilder()
        override fun onOpen(webSocket: WebSocket) {
            if (token != generation.get()) { webSocket.abort(); return }
            socket = webSocket; lastMessageAt = Instant.now(); webSocket.sendText(subscription, true); webSocket.request(1)
        }
        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            if (token == generation.get()) {
                text.append(data)
                if (last) {
                    val root = runCatching { JsonParser.parseString(text.toString()).asJsonObject }.getOrNull(); text.setLength(0)
                    lastMessageAt = Instant.now()
                    val status = root?.get("status")?.asInt
                    if (status != null) {
                        onState(status == 200, if (status == 200) null else root.getAsJsonObject("error")?.get("msg")?.asString)
                        if (status != 200) { socket = null; webSocket.abort() }
                    }
                    else if (root?.has("event") == true || root?.has("subscriptionId") == true) {
                        onState(true, null)
                        val payload = root.getAsJsonObject("event")
                        val event = payload?.takeIf { it.get("e")?.asString == "executionReport" }?.let {
                            TestnetExecutionEvent(it.get("i").asLong, it.get("s").asString, it.get("X").asString,
                                it.get("z")?.asString?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                                it.get("Z")?.asString?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                                it.get("E")?.asLong ?: System.currentTimeMillis())
                        }
                        val listEvent = payload?.takeIf { it.get("e")?.asString == "listStatus" }?.let {
                            TestnetOrderListEvent(it.get("g").asLong, it.get("L")?.asString ?: it.get("l")?.asString ?: "EXECUTING",
                                it.get("E")?.asLong ?: System.currentTimeMillis())
                        }
                        if (listEvent != null) onListEvent(listEvent) else onEvent(event)
                    }
                }
            }
            webSocket.request(1); return null
        }
        override fun onPing(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*> {
            lastMessageAt = Instant.now(); webSocket.request(1); return webSocket.sendPong(message)
        }
        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            if (token == generation.get()) { socket = null; onState(false, "测试网用户流已断开") }; return null
        }
        override fun onError(webSocket: WebSocket, error: Throwable) {
            if (token == generation.get()) { socket = null; onState(false, error.message ?: "测试网用户流连接失败") }
        }
    }
}

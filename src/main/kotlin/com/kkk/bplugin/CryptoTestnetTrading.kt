package com.kkk.bplugin

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
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
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class TradingAccountMode(val label: String) {
    LOCAL("本地模拟"), TESTNET("币安测试网");
    override fun toString() = label
}

data class TestnetSnapshot(
    val balances: List<TestnetBalance> = emptyList(),
    val openOrders: List<TestnetOrder> = emptyList(),
    val history: List<TestnetOrder> = emptyList(),
    val updatedAt: Instant? = null,
)

object BinanceTestnetCredentials {
    private val attributes = CredentialAttributes("QuietCrypto.BinanceSpotTestnet.Secret")
    fun secret(): String = PasswordSafe.instance.get(attributes)?.getPasswordAsString().orEmpty()
    fun save(secret: String) { PasswordSafe.instance.set(attributes, secret.takeIf(String::isNotBlank)?.let { Credentials("spot-testnet", it) }) }
    fun clear() { PasswordSafe.instance.set(attributes, null) }
}

@Service(Service.Level.APP)
class CryptoTestnetTradingService : Disposable {
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
    private val stream = BinanceTestnetUserStream(
        onEvent = { refresh(pendingSymbol) },
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

    fun hasCredentials() = CryptoSettings.getInstance().state.testnetApiKey.isNotBlank() && BinanceTestnetCredentials.secret().isNotBlank()

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

    fun refresh(symbol: String? = null) {
        val normalized = symbol?.let(::normalizeMarketSymbol)?.takeIf(::isCryptoSymbol)
        if (normalized != null) pendingSymbol = normalized
        val credentials = credentials() ?: return
        if (!busy.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val balances = client.account(credentials.first, credentials.second)
                val orders = client.openOrders(credentials.first, credentials.second)
                val history = pendingSymbol?.let { client.allOrders(it, credentials.first, credentials.second) }.orEmpty()
                TestnetSnapshot(balances, orders, history.filter { it.status !in setOf("NEW", "PARTIALLY_FILLED") }, Instant.now())
            }
            result.onSuccess { snapshot = it; error = null; retryAt = 0; nextRefresh = System.currentTimeMillis() + 30_000 }
                .onFailure { error = it.message ?: "测试网账户同步失败"; retryAt = System.currentTimeMillis() + 15_000 }
            busy.set(false)
        }
    }

    fun place(symbol: String, side: PaperOrderSide, type: PaperOrderType, quantity: java.math.BigDecimal,
              price: java.math.BigDecimal?, callback: (Result<TestnetOrder>) -> Unit) {
        val credentials = credentials() ?: return callback(Result.failure(IllegalStateException("请先配置测试网凭据")))
        if (!busy.compareAndSet(false, true)) return callback(Result.failure(IllegalStateException("测试网请求正在处理中")))
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val referencePrice = if (type == PaperOrderType.MARKET) client.tickerPrice(symbol) else price
                client.rules(symbol, type).validate(quantity, referencePrice, type == PaperOrderType.LIMIT)?.let { error(it) }
                client.placeOrder(symbol, side, type, quantity, price, credentials.first, credentials.second)
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
            val result = runCatching { client.cancelOrder(order.symbol, order.id, credentials.first, credentials.second) }
            busy.set(false)
            result.onSuccess { refresh(order.symbol) }.onFailure { error = it.message }
            ApplicationManager.getApplication().invokeLater { callback(result) }
        }
    }

    private fun credentials(): Pair<String, String>? {
        val key = CryptoSettings.getInstance().state.testnetApiKey.trim()
        val secret = BinanceTestnetCredentials.secret()
        return if (key.isBlank() || secret.isBlank()) null else key to secret
    }
    private fun ensureStream() {
        val credentials = credentials() ?: return
        if (stream.isActive()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { client.synchronizeTime(); stream.ensure(credentials.first, credentials.second, client.timestamp()) }
                .onFailure { streamConnected = false; streamMessage = it.message; retryAt = System.currentTimeMillis() + 15_000 }
        }
    }
    override fun dispose() { disposed = true; schedule.cancel(false); stream.close() }

    companion object { fun getInstance() = ApplicationManager.getApplication().getService(CryptoTestnetTradingService::class.java) }
}

/** Signed Spot Testnet user-data subscription. Events are followed by a REST state reconciliation. */
internal class BinanceTestnetUserStream(
    private val onEvent: () -> Unit,
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
                    else if (root?.has("event") == true || root?.has("subscriptionId") == true) { onState(true, null); onEvent() }
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

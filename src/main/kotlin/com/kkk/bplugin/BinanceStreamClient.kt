package com.kkk.bplugin

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.Authenticator
import java.net.PasswordAuthentication
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicLong
import com.intellij.util.net.HttpConfigurable

data class StreamUpdate(val quote: CryptoQuote? = null, val candle: StreamCandle? = null)
data class StreamCandle(val symbol: String, val period: KlinePeriod, val bar: KlineBar, val closed: Boolean)

internal data class StreamSpec(val tickerSymbols: List<String>, val chartSymbol: String, val period: KlinePeriod) {
    fun streams(): List<String> = tickerSymbols.distinct().sorted().map { "${it.lowercase(Locale.ROOT)}@ticker" } +
        "${chartSymbol.lowercase(Locale.ROOT)}@kline_${period.binanceInterval()}"
}

internal fun KlinePeriod.binanceInterval() = when (this) {
    KlinePeriod.INTRADAY -> "1m"
    KlinePeriod.MINUTE5 -> "5m"
    KlinePeriod.MINUTE15 -> "15m"
    KlinePeriod.MINUTE30 -> "30m"
    KlinePeriod.HOUR -> "1h"
    KlinePeriod.HOUR2 -> "2h"
    KlinePeriod.HOUR4 -> "4h"
    KlinePeriod.HOUR6 -> "6h"
    KlinePeriod.HOUR12 -> "12h"
    KlinePeriod.DAY -> "1d"
    KlinePeriod.WEEK -> "1w"
}

/** One shared public market-data socket. A generation token drops callbacks from replaced connections. */
internal class BinanceStreamClient(
    private val onUpdate: (StreamUpdate) -> Unit,
    private val onState: (connected: Boolean, message: String?) -> Unit,
) : AutoCloseable {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).apply {
        val proxy = HttpConfigurable.getInstance()
        proxy(proxy.onlyBySettingsSelector)
        if (proxy.PROXY_AUTHENTICATION) authenticator(object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(
                proxy.proxyLogin.orEmpty(), proxy.plainProxyPassword.orEmpty().toCharArray(),
            )
        })
    }.build()
    private val generation = AtomicLong()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var spec: StreamSpec? = null
    @Volatile private var connecting = false
    @Volatile var connectedAt: Instant? = null; private set
    @Volatile var lastMessageAt: Instant? = null; private set

    @Synchronized fun ensure(next: StreamSpec) {
        if (spec == next && (socket != null || connecting)) return
        disconnect()
        spec = next
        connecting = true
        val token = generation.incrementAndGet()
        val streams = next.streams().joinToString("/")
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
            .buildAsync(URI.create("wss://data-stream.binance.vision/stream?streams=$streams"), Listener(token))
            .whenComplete { webSocket, error ->
                synchronized(this) {
                    if (token != generation.get()) { webSocket?.abort(); return@whenComplete }
                    connecting = false
                    if (error != null) {
                        socket = null
                        onState(false, error.cause?.message ?: error.message ?: "实时行情连接失败")
                    } else socket = webSocket
                }
            }
    }

    @Synchronized fun disconnect(reason: String? = null) {
        generation.incrementAndGet()
        connecting = false
        connectedAt = null
        lastMessageAt = null
        socket?.sendClose(WebSocket.NORMAL_CLOSURE, "client refresh")
        socket = null
        if (reason != null) onState(false, reason)
    }

    override fun close() { spec = null; disconnect() }

    private inner class Listener(private val token: Long) : WebSocket.Listener {
        private val text = StringBuilder()
        override fun onOpen(webSocket: WebSocket) {
            if (token != generation.get()) { webSocket.abort(); return }
            socket = webSocket
            connectedAt = Instant.now()
            lastMessageAt = connectedAt
            onState(true, null)
            webSocket.request(1)
        }
        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            if (token == generation.get()) {
                text.append(data)
                if (last) {
                    val payload = text.toString(); text.setLength(0)
                    lastMessageAt = Instant.now()
                    runCatching { parseStreamUpdate(payload) }.onSuccess(onUpdate).onFailure { onState(true, "实时数据格式异常") }
                }
            }
            webSocket.request(1)
            return null
        }
        override fun onPing(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*> {
            webSocket.request(1)
            return webSocket.sendPong(message)
        }
        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            if (token == generation.get()) {
                socket = null; connectedAt = null
                onState(false, "实时行情已断开${reason.takeIf(String::isNotBlank)?.let { "：$it" }.orEmpty()}")
            }
            return null
        }
        override fun onError(webSocket: WebSocket, error: Throwable) {
            if (token == generation.get()) {
                socket = null; connectedAt = null
                onState(false, error.message ?: "实时行情连接失败")
            }
        }
    }

    companion object {
        internal fun parseStreamUpdate(json: String): StreamUpdate {
            val root = JsonParser.parseString(json).asJsonObject
            val data = root.getAsJsonObject("data") ?: root
            return when (data.get("e")?.asString) {
                "24hrTicker" -> StreamUpdate(quote = parseTicker(data))
                "kline" -> StreamUpdate(candle = parseCandle(data))
                else -> StreamUpdate()
            }
        }
        private fun parseTicker(row: JsonObject): CryptoQuote {
            fun decimal(key: String) = row.get(key).asString.toBigDecimal()
            return CryptoQuote(row.get("s").asString, decimal("c"), decimal("P"), decimal("h"), decimal("l"), decimal("q"),
                Instant.ofEpochMilli(row.get("E").asLong))
        }
        private fun parseCandle(row: JsonObject): StreamCandle {
            val k = row.getAsJsonObject("k")
            fun number(key: String) = k.get(key).asString.toDouble()
            val bar = KlineBar(k.get("t").asLong, number("o"), number("h"), number("l"), number("c"), number("v"))
            require(listOf(bar.open, bar.high, bar.low, bar.close, bar.volume).all(Double::isFinite))
            val interval = k.get("i").asString
            val period = KlinePeriod.entries.firstOrNull { it.binanceInterval() == interval } ?: error("未知 K线周期")
            return StreamCandle(k.get("s").asString, period, bar, k.get("x").asBoolean)
        }
    }
}

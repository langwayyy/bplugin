package com.kkk.bplugin

import com.google.gson.JsonParser
import java.net.URLEncoder
import com.intellij.util.net.HttpConfigurable
import java.time.Instant
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

internal fun normalizeMarketSymbol(raw: String) = raw.trim().uppercase(Locale.ROOT).replace("/", "")
internal fun isCryptoSymbol(raw: String) = normalizeMarketSymbol(raw).let { it.length in 5..30 && it.all { c -> c.isLetterOrDigit() } && it.any(Char::isLetter) }
internal fun marketPrice(value: BigDecimal, decimals: Int = -1): String =
    (if (decimals >= 0) value.setScale(decimals, RoundingMode.HALF_UP) else value.stripTrailingZeros()).toPlainString()

data class CryptoPair(val symbol: String, val base: String, val quote: String) {
    override fun toString() = "$base/$quote"
}
data class CryptoQuote(val symbol: String, val price: BigDecimal, val change: BigDecimal,
    val high: BigDecimal, val low: BigDecimal, val turnover: BigDecimal, val updatedAt: Instant)

/** Public spot market data only; never accepts credentials or sends account headers. */
class BinanceMarketClient {
    @Volatile private var retryAt = Instant.EPOCH

    private fun get(path: String): String {
        check(!Instant.now().isBefore(retryAt)) { "币安请求暂缓，请稍后重试" }
        val connection = HttpConfigurable.getInstance().openHttpConnection("https://data-api.binance.vision$path")
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.instanceFollowRedirects = false
            val status = connection.responseCode
            if (status == 429 || status == 418) {
                retryAt = Instant.now().plusSeconds(connection.getHeaderField("Retry-After")?.toLongOrNull()?.coerceAtLeast(1) ?: 60)
            }
            check(status in 200..299) { "币安行情连接失败（HTTP $status）" }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { connection.disconnect() }
    }

    fun ping() { get("/api/v3/ping") }
    fun pairs(): List<CryptoPair> = JsonParser.parseString(get("/api/v3/exchangeInfo")).asJsonObject
        .getAsJsonArray("symbols").map { it.asJsonObject }.filter {
            it.get("status").asString == "TRADING" && it.get("isSpotTradingAllowed")?.asBoolean != false
        }.map { CryptoPair(it.get("symbol").asString, it.get("baseAsset").asString, it.get("quoteAsset").asString) }

    fun quotes(symbols: List<String>): List<CryptoQuote> = symbols.distinct().chunked(20).flatMap { batch ->
        val parameter = URLEncoder.encode(batch.joinToString(",", "[", "]") { "\"$it\"" }, Charsets.UTF_8)
        parseQuotes(get("/api/v3/ticker/24hr?symbols=$parameter"))
    }

    fun klines(symbol: String, period: KlinePeriod): List<KlineBar> {
        check(CryptoSettings.getInstance().state.enabled) { "请先在设置中启用币安行情" }
        require(isCryptoSymbol(symbol))
        val interval = period.binanceInterval()
        return parseKlines(get("/api/v3/klines?symbol=${URLEncoder.encode(symbol, Charsets.UTF_8)}&interval=$interval&limit=350"))
    }

    companion object {
        val shared = BinanceMarketClient()
        internal fun parseQuotes(json: String): List<CryptoQuote> = JsonParser.parseString(json).asJsonArray.map { element ->
            val row = element.asJsonObject
            fun decimal(key: String) = row.get(key).asString.toBigDecimal()
            CryptoQuote(row.get("symbol").asString, decimal("lastPrice"), decimal("priceChangePercent"),
                decimal("highPrice"), decimal("lowPrice"), decimal("quoteVolume"), Instant.ofEpochMilli(row.get("closeTime").asLong))
        }
        internal fun parseKlines(json: String): List<KlineBar> = JsonParser.parseString(json).asJsonArray.map {
            val row = it.asJsonArray
            KlineBar(row[0].asLong, row[1].asDouble, row[2].asDouble, row[3].asDouble, row[4].asDouble, row[5].asDouble)
                .also { bar -> require(listOf(bar.open, bar.high, bar.low, bar.close, bar.volume).all(Double::isFinite) &&
                    bar.low > 0 && bar.high >= maxOf(bar.open, bar.close) && bar.low <= minOf(bar.open, bar.close) && bar.volume >= 0) { "币安 K线数据无效" } }
        }.distinctBy { it.timestamp }.sortedBy { it.timestamp }
    }
}


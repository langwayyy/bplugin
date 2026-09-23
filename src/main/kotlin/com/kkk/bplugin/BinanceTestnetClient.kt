package com.kkk.bplugin

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.util.net.HttpConfigurable
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class TestnetBalance(val asset: String, val free: BigDecimal, val locked: BigDecimal) {
    val total: BigDecimal get() = free + locked
}

data class TestnetOrder(
    val id: Long,
    val symbol: String,
    val side: PaperOrderSide,
    val type: PaperOrderType,
    val quantity: BigDecimal,
    val executedQuantity: BigDecimal,
    val price: BigDecimal,
    val averagePrice: BigDecimal?,
    val status: String,
    val time: Long,
    val rawType: String = type.name,
    val stopPrice: BigDecimal = BigDecimal.ZERO,
    val orderListId: Long = -1,
    val clientOrderId: String = "",
)

data class TestnetOrderList(
    val id: Long,
    val symbol: String,
    val contingencyType: String,
    val status: String,
    val clientOrderId: String,
    val time: Long,
    val orderIds: List<Long>,
)

data class TestnetTrade(
    val id: Long,
    val orderId: Long,
    val symbol: String,
    val price: BigDecimal,
    val quantity: BigDecimal,
    val quoteQuantity: BigDecimal,
    val commission: BigDecimal,
    val commissionAsset: String,
    val time: Long,
    val buyer: Boolean,
)

data class TestnetSymbolRules(
    val minQuantity: BigDecimal,
    val maxQuantity: BigDecimal,
    val stepSize: BigDecimal,
    val minNotional: BigDecimal,
    val minPrice: BigDecimal = BigDecimal.ZERO,
    val maxPrice: BigDecimal = BigDecimal.ZERO,
    val tickSize: BigDecimal = BigDecimal.ZERO,
) {
    fun normalizeQuantity(value: BigDecimal): BigDecimal = normalizeStep(value, stepSize).let {
        if (maxQuantity.signum() > 0) minOf(it, maxQuantity) else it
    }
    fun normalizePrice(value: BigDecimal): BigDecimal = normalizeStep(value, tickSize)

    fun validate(quantity: BigDecimal, price: BigDecimal?, validatePriceStep: Boolean = true): String? {
        if (quantity < minQuantity) return "数量不能小于 ${marketPrice(minQuantity)}"
        if (maxQuantity.signum() > 0 && quantity > maxQuantity) return "数量不能大于 ${marketPrice(maxQuantity)}"
        if (stepSize.signum() > 0 && quantity.remainder(stepSize).stripTrailingZeros().signum() != 0)
            return "数量步进必须为 ${marketPrice(stepSize)}"
        if (validatePriceStep && price != null && minPrice.signum() > 0 && price < minPrice) return "价格不能小于 ${marketPrice(minPrice)}"
        if (validatePriceStep && price != null && maxPrice.signum() > 0 && price > maxPrice) return "价格不能大于 ${marketPrice(maxPrice)}"
        if (validatePriceStep && price != null && tickSize.signum() > 0 && price.remainder(tickSize).stripTrailingZeros().signum() != 0)
            return "价格步进必须为 ${marketPrice(tickSize)}"
        if (price != null && minNotional.signum() > 0 && quantity * price < minNotional)
            return "订单金额不能小于 ${marketPrice(minNotional)} USDT"
        return null
    }

    private fun normalizeStep(value: BigDecimal, step: BigDecimal): BigDecimal =
        if (step.signum() <= 0) value.stripTrailingZeros()
        else value.divide(step, 0, RoundingMode.DOWN).multiply(step).stripTrailingZeros()
}

class BinanceTestnetException(val code: Int?, message: String) : Exception(message)

/** HMAC REST client pinned to Binance Spot Testnet. It cannot address production trading hosts. */
class BinanceTestnetClient {
    @Volatile private var timeOffsetMillis = 0L

    fun synchronizeTime(): Long {
        val started = System.currentTimeMillis()
        val server = JsonParser.parseString(request("GET", "/api/v3/time")).asJsonObject.get("serverTime").asLong
        timeOffsetMillis = server - (started + System.currentTimeMillis()) / 2
        return timeOffsetMillis
    }
    internal fun timestamp() = System.currentTimeMillis() + timeOffsetMillis

    fun account(apiKey: String, secret: String): List<TestnetBalance> = signed("GET", "/api/v3/account", emptyMap(), apiKey, secret)
        .asJsonObject.getAsJsonArray("balances").map { row ->
            row.asJsonObject.let { TestnetBalance(it.get("asset").asString, it.decimal("free"), it.decimal("locked")) }
        }.filter { it.total.signum() != 0 }

    fun openOrders(apiKey: String, secret: String): List<TestnetOrder> = parseOrders(
        signed("GET", "/api/v3/openOrders", emptyMap(), apiKey, secret).asJsonArray)

    fun allOrders(symbol: String, apiKey: String, secret: String): List<TestnetOrder> = parseOrders(
        signed("GET", "/api/v3/allOrders", mapOf("symbol" to normalizeMarketSymbol(symbol), "limit" to "50"), apiKey, secret).asJsonArray)

    fun trades(symbol: String, apiKey: String, secret: String): List<TestnetTrade> =
        signed("GET", "/api/v3/myTrades", mapOf("symbol" to normalizeMarketSymbol(symbol), "limit" to "100"), apiKey, secret)
            .asJsonArray.map { row -> row.asJsonObject.let {
                TestnetTrade(it.get("id").asLong, it.get("orderId").asLong, it.get("symbol").asString,
                    it.decimal("price"), it.decimal("qty"), it.decimal("quoteQty"), it.decimal("commission"),
                    it.get("commissionAsset")?.asString.orEmpty(), it.get("time").asLong, it.get("isBuyer").asBoolean)
            } }.sortedByDescending(TestnetTrade::time)

    fun placeOrder(symbol: String, side: PaperOrderSide, type: PaperOrderType, quantity: BigDecimal,
                   price: BigDecimal?, apiKey: String, secret: String, clientOrderId: String? = null): TestnetOrder {
        val params = linkedMapOf("symbol" to normalizeMarketSymbol(symbol), "side" to side.name, "type" to type.name,
            "quantity" to quantity.stripTrailingZeros().toPlainString(), "newOrderRespType" to "FULL")
        clientOrderId?.let { params["newClientOrderId"] = it }
        if (type == PaperOrderType.LIMIT) {
            require(price != null && price.signum() > 0)
            params["timeInForce"] = "GTC"
            params["price"] = price.stripTrailingZeros().toPlainString()
        }
        return parseOrder(signed("POST", "/api/v3/order", params, apiKey, secret).asJsonObject)
    }

    fun placeConditionalOrder(symbol: String, side: PaperOrderSide, rawType: String, quantity: BigDecimal,
                              stopPrice: BigDecimal, limitPrice: BigDecimal, clientOrderId: String,
                              apiKey: String, secret: String): TestnetOrder {
        require(rawType in setOf("STOP_LOSS_LIMIT", "TAKE_PROFIT_LIMIT"))
        val params = linkedMapOf("symbol" to normalizeMarketSymbol(symbol), "side" to side.name, "type" to rawType,
            "quantity" to quantity.stripTrailingZeros().toPlainString(), "price" to limitPrice.stripTrailingZeros().toPlainString(),
            "stopPrice" to stopPrice.stripTrailingZeros().toPlainString(), "timeInForce" to "GTC",
            "newClientOrderId" to clientOrderId, "newOrderRespType" to "FULL")
        return parseOrder(signed("POST", "/api/v3/order", params, apiKey, secret).asJsonObject)
    }

    fun placeOco(symbol: String, side: PaperOrderSide, quantity: BigDecimal, targetPrice: BigDecimal,
                 stopPrice: BigDecimal, stopLimitPrice: BigDecimal, listClientOrderId: String,
                 apiKey: String, secret: String): TestnetOrderList {
        val params = linkedMapOf("symbol" to normalizeMarketSymbol(symbol), "side" to side.name,
            "quantity" to quantity.stripTrailingZeros().toPlainString(), "listClientOrderId" to listClientOrderId,
            "newOrderRespType" to "FULL")
        if (side == PaperOrderSide.SELL) {
            params.putAll(mapOf("aboveType" to "LIMIT_MAKER", "abovePrice" to targetPrice.stripTrailingZeros().toPlainString(),
                "aboveClientOrderId" to "$listClientOrderId-a", "belowType" to "STOP_LOSS_LIMIT",
                "belowStopPrice" to stopPrice.stripTrailingZeros().toPlainString(),
                "belowPrice" to stopLimitPrice.stripTrailingZeros().toPlainString(), "belowTimeInForce" to "GTC",
                "belowClientOrderId" to "$listClientOrderId-b"))
        } else {
            params.putAll(mapOf("aboveType" to "STOP_LOSS_LIMIT", "aboveStopPrice" to stopPrice.stripTrailingZeros().toPlainString(),
                "abovePrice" to stopLimitPrice.stripTrailingZeros().toPlainString(), "aboveTimeInForce" to "GTC",
                "aboveClientOrderId" to "$listClientOrderId-a", "belowType" to "LIMIT_MAKER",
                "belowPrice" to targetPrice.stripTrailingZeros().toPlainString(), "belowClientOrderId" to "$listClientOrderId-b"))
        }
        return parseOrderList(signed("POST", "/api/v3/orderList/oco", params, apiKey, secret).asJsonObject)
    }

    fun cancelOrder(symbol: String, orderId: Long, apiKey: String, secret: String): TestnetOrder = parseOrder(
        signed("DELETE", "/api/v3/order", mapOf("symbol" to normalizeMarketSymbol(symbol), "orderId" to orderId.toString()), apiKey, secret).asJsonObject)

    fun cancelOpenOrders(symbol: String, apiKey: String, secret: String): List<TestnetOrder> = parseOrders(
        signed("DELETE", "/api/v3/openOrders", mapOf("symbol" to normalizeMarketSymbol(symbol)), apiKey, secret).asJsonArray)

    fun openOrderLists(apiKey: String, secret: String): List<TestnetOrderList> =
        signed("GET", "/api/v3/openOrderList", emptyMap(), apiKey, secret).asJsonArray.map { parseOrderList(it.asJsonObject) }
            .sortedByDescending(TestnetOrderList::time)

    fun cancelOrderList(symbol: String, orderListId: Long, apiKey: String, secret: String): TestnetOrderList =
        parseOrderList(signed("DELETE", "/api/v3/orderList", mapOf("symbol" to normalizeMarketSymbol(symbol),
            "orderListId" to orderListId.toString()), apiKey, secret).asJsonObject)

    fun tickerPrice(symbol: String): BigDecimal = JsonParser.parseString(request("GET", "/api/v3/ticker/price",
        mapOf("symbol" to normalizeMarketSymbol(symbol)))).asJsonObject.decimal("price")

    fun rules(symbol: String, type: PaperOrderType = PaperOrderType.LIMIT): TestnetSymbolRules {
        val root = JsonParser.parseString(request("GET", "/api/v3/exchangeInfo", mapOf("symbol" to normalizeMarketSymbol(symbol)))).asJsonObject
        val row = root.getAsJsonArray("symbols").firstOrNull()?.asJsonObject ?: throw BinanceTestnetException(-1121, "测试网交易对不可用")
        val filters = row.getAsJsonArray("filters").associateBy { it.asJsonObject.get("filterType").asString }
        val lot = (if (type == PaperOrderType.MARKET) filters["MARKET_LOT_SIZE"] else null)?.asJsonObject
            ?.takeIf { it.decimal("stepSize").signum() > 0 } ?: filters["LOT_SIZE"]?.asJsonObject
        val price = filters["PRICE_FILTER"]?.asJsonObject
        val notional = (filters["NOTIONAL"] ?: filters["MIN_NOTIONAL"])?.asJsonObject
        return TestnetSymbolRules(lot?.decimal("minQty") ?: BigDecimal.ZERO, lot?.decimal("maxQty") ?: BigDecimal.ZERO,
            lot?.decimal("stepSize") ?: BigDecimal.ZERO, notional?.decimal("minNotional") ?: BigDecimal.ZERO,
            price?.decimal("minPrice") ?: BigDecimal.ZERO, price?.decimal("maxPrice") ?: BigDecimal.ZERO,
            price?.decimal("tickSize") ?: BigDecimal.ZERO)
    }

    internal fun signed(method: String, path: String, params: Map<String, String>, apiKey: String, secret: String) = try {
        signedOnce(method, path, params, apiKey, secret)
    } catch (error: BinanceTestnetException) {
        if (error.code != -1021) throw error
        synchronizeTime()
        signedOnce(method, path, params, apiKey, secret)
    }

    private fun signedOnce(method: String, path: String, params: Map<String, String>, apiKey: String, secret: String): com.google.gson.JsonElement {
        require(apiKey.isNotBlank() && secret.isNotBlank()) { "请先配置测试网 API Key 和 Secret" }
        val secured = params + mapOf("recvWindow" to "5000", "timestamp" to (System.currentTimeMillis() + timeOffsetMillis).toString())
        val payload = query(secured)
        val signed = "$payload&signature=${sign(payload, secret)}"
        return JsonParser.parseString(request(method, path, rawQuery = signed, apiKey = apiKey))
    }

    private fun request(method: String, path: String, params: Map<String, String> = emptyMap(), rawQuery: String? = null, apiKey: String? = null): String {
        val query = rawQuery ?: query(params)
        val url = "https://testnet.binance.vision$path${if (query.isBlank()) "" else "?$query"}"
        val connection = HttpConfigurable.getInstance().openHttpConnection(url)
        try {
            connection.requestMethod = method
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            apiKey?.let { connection.setRequestProperty("X-MBX-APIKEY", it) }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                throw BinanceTestnetException(json?.get("code")?.asInt, friendlyError(json?.get("code")?.asInt, json?.get("msg")?.asString, status))
            }
            return body
        } finally { connection.disconnect() }
    }

    private fun parseOrders(array: com.google.gson.JsonArray) = array.map { parseOrder(it.asJsonObject) }.sortedByDescending(TestnetOrder::time)
    private fun parseOrder(row: JsonObject): TestnetOrder {
        val executed = row.decimal("executedQty")
        val cumulative = row.get("cummulativeQuoteQty")?.asString?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val rawType = row.get("type")?.asString ?: "LIMIT"
        return TestnetOrder(row.get("orderId").asLong, row.get("symbol").asString,
            PaperOrderSide.valueOf(row.get("side").asString), parseType(rawType), row.decimal("origQty"), executed,
            row.decimal("price"), cumulative.takeIf { executed.signum() > 0 }?.divide(executed, 16, java.math.RoundingMode.HALF_UP)?.stripTrailingZeros(),
            row.get("status").asString, row.get("updateTime")?.asLong ?: row.get("transactTime")?.asLong ?: row.get("time")?.asLong ?: System.currentTimeMillis(),
            rawType, row.decimal("stopPrice"), row.get("orderListId")?.asLong ?: -1, row.get("clientOrderId")?.asString.orEmpty())
    }
    private fun parseOrderList(row: JsonObject): TestnetOrderList = TestnetOrderList(
        row.get("orderListId").asLong, row.get("symbol").asString, row.get("contingencyType")?.asString ?: "OCO",
        row.get("listOrderStatus")?.asString ?: row.get("listStatusType")?.asString ?: "EXECUTING",
        row.get("listClientOrderId")?.asString.orEmpty(),
        row.get("transactionTime")?.asLong ?: row.get("time")?.asLong ?: System.currentTimeMillis(),
        row.getAsJsonArray("orders")?.mapNotNull { it.asJsonObject.get("orderId")?.asLong } ?: emptyList())
    private fun parseType(value: String) = if (value.startsWith("MARKET")) PaperOrderType.MARKET else PaperOrderType.LIMIT

    companion object {
        private fun query(params: Map<String, String>) = params.toSortedMap().entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
        internal fun sign(payload: String, secret: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
            return mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
        private fun friendlyError(code: Int?, message: String?, status: Int) = when (code) {
            -1021 -> "本机时间与币安测试网不同步"
            -1022 -> "测试网签名无效，请检查 API Secret"
            -2010 -> "测试网拒绝订单：${message.orEmpty()}"
            -2011 -> "测试网委托不存在或已结束"
            -2015 -> "测试网 API Key 无效或权限不足"
            else -> message?.take(240)?.takeIf(String::isNotBlank) ?: "币安测试网请求失败（HTTP $status）"
        }
    }
}

private fun JsonObject.decimal(key: String) = get(key)?.asString?.toBigDecimalOrNull() ?: BigDecimal.ZERO

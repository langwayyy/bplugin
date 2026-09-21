package com.kkk.bplugin

data class CryptoWatchMetadata(val symbol: String, val group: String = DEFAULT_WATCH_GROUP, val note: String = "")

const val DEFAULT_WATCH_GROUP = "默认"
const val ALL_WATCH_GROUPS = "全部"

internal fun normalizeWatchGroup(raw: String): String = raw.trim().replace(Regex("[\\t\\r\\n]+"), " ").take(30).ifBlank { DEFAULT_WATCH_GROUP }
internal fun normalizeWatchNote(raw: String): String = raw.trim().replace(Regex("[\\t\\r\\n]+"), " ").take(200)

object WatchlistTransfer {
    fun encode(entries: List<CryptoWatchMetadata>): String = buildString {
        appendLine("symbol\tgroup\tnote")
        entries.forEach { appendLine("${it.symbol}\t${normalizeWatchGroup(it.group)}\t${normalizeWatchNote(it.note)}") }
    }.trimEnd()

    fun decode(text: String): List<CryptoWatchMetadata> {
        val unique = linkedMapOf<String, CryptoWatchMetadata>()
        text.lineSequence().map(String::trim).filter(String::isNotBlank).forEachIndexed { index, line ->
            val fields = if ('\t' in line) line.split('\t', limit = 3) else line.split(',', limit = 3)
            val symbol = normalizeMarketSymbol(fields.getOrNull(0).orEmpty())
            if ((index == 0 && symbol == "SYMBOL") || !isCryptoSymbol(symbol)) return@forEachIndexed
            unique[symbol] = CryptoWatchMetadata(symbol, normalizeWatchGroup(fields.getOrNull(1).orEmpty()), normalizeWatchNote(fields.getOrNull(2).orEmpty()))
        }
        return unique.values.take(100)
    }
}

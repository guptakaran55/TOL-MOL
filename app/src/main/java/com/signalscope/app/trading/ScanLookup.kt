package com.signalscope.app.trading

import android.content.Context
import com.signalscope.app.data.DiscoveryResultStore

/**
 * A company's NAME and last price from its market's latest finished scan.
 *
 * The AI's news search finds far more under "Siemens Energy" than under "ENR",
 * and a German ticker like "SAX" or "AIR" alone finds another company's news or
 * none. Read once per scan and kept in memory: the India scan is several
 * megabytes, and this is asked for one stock at a time.
 */
object ScanLookup {
    /** The scan each market's stocks come from. */
    val MARKET = mapOf("de" to "DAX 100", "us" to "NASDAQ 100", "in" to "NIFTY 500")

    /** One stock as the scan saw it: its daily MACD curve and phase too, for an alert's chart. */
    class Row(val name: String, val price: Double, val macd: List<Double>, val phase: String?)

    private class Memo(val at: Long, val rows: Map<String, Row>)
    private val memo = HashMap<String, Memo>()

    /** (name, price) for [symbol] in [space] — a space or an algo's namespace — or null. */
    fun of(ctx: Context, space: String, symbol: String): Pair<String, Double>? =
        row(ctx, space, symbol)?.let { it.name to it.price }

    fun row(ctx: Context, space: String, symbol: String): Row? {
        val base = Spaces.baseOf(space.lowercase())
        val market = MARKET[base] ?: return null
        val at = DiscoveryResultStore.lastCompleteAt(ctx, market)
        if (at <= 0L) return null
        val m = synchronized(memo) {
            memo[base]?.takeIf { it.at == at } ?: run {
                val scan = DiscoveryResultStore.loadLatestComplete(ctx, market) ?: return null
                // Gson can leave a missing field null whatever its declared type.
                Memo(at, scan.allStocks.associate {
                    QualityCheap.bare(it.symbol) to Row((it.name as String?).orEmpty(), it.price,
                        (it.macdCurve as List<Double>?).orEmpty(), it.macdPhase as String?)
                }).also { memo[base] = it }
            }
        }
        return m.rows[QualityCheap.bare(symbol)]
    }
}

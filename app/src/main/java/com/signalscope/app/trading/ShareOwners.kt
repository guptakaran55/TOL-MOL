package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.BucketStore
import com.signalscope.app.data.FloorAlgoStore
import com.signalscope.app.data.FloorLiveStore
import com.signalscope.app.data.ManualWatchStore
import com.signalscope.app.data.T212LiveStore
import com.signalscope.app.data.ZerodhaLiveStore

/**
 * WHO OWNS WHICH SHARES OF A STOCK (the user, 2 Nov 2026: every strategy and
 * section should show how much of a stock it manages).
 *
 * Trading 212 and Zerodha show one total per stock. The app keeps the parts:
 * each live algo's lots, the German built-in algos' real positions, and the
 * watchlist's buys. What is left over is yours — bought by hand, never recorded.
 */
object ShareOwners {
    private const val TAG = "ShareOwners"
    const val WATCHLIST = "watchlist"
    const val ALGO = "algo"

    /** One owner's shares of one stock. [cost] is 0 while its buying price is unknown. */
    data class Lot(val ns: String, val name: String, val kind: String, val symbol: String,
                   val qty: Double, val cost: Double, val keys: List<String>)

    /**
     * ON AUTOPILOT: an owner whose shares sell entirely on their own rules — the
     * German built-in algos (only their MACD-turns kind may trade real money, see
     * FloorLive.eligible): the algo's own stop, target, time limit and trend sell
     * the real shares too, with no decision of yours in it. Left out of the
     * Positions widget and the portfolio news scan (the user, 5 Nov 2026); the
     * slower algos and the watchlist stay, as they may need your eye.
     */
    fun onAutopilot(ctx: Context, ns: String): Boolean = try {
        FloorAlgoStore.load(ctx).algos.any { it.ns == ns }
    } catch (_: Exception) { false }

    /** The name an owner goes by on its card. */
    fun nameOf(ctx: Context, ns: String): String = try {
        when {
            ns == LiveTestBench.NS -> "the test bench (not an algo)"
            Spaces.bucketIdOf(ns) == ManualWatchStore.ID -> WATCHLIST
            else -> BucketStore.get(ctx, ns)?.name
                ?: FloorAlgoStore.load(ctx).algos.firstOrNull { it.ns == ns }?.name
                ?: "a deleted algo"
        }
    } catch (e: Exception) { "a deleted algo" }

    /**
     * Stock (upper case) → who in the app holds shares of it in [base]: "watchlist",
     * an algo's name, or several. A stock missing from the map is yours alone —
     * the only kind India's old snipers and daily trailing still touch (the user,
     * 12 Nov 2026): the watchlist and the algos keep their own stops and targets.
     */
    fun ownersBySymbol(ctx: Context, base: String): Map<String, String> = try {
        lots(ctx, base).groupBy { it.symbol.uppercase() }
            .mapValues { (_, l) -> l.map { it.name }.distinct().joinToString(", ") }
    } catch (e: Exception) { Log.w(TAG, "[$base] owners unreadable", e); emptyMap() }

    /** The German built-in algos' rows that hold shares in this market. */
    fun floorRows(ctx: Context, base: String): List<FloorLiveStore.Row> =
        FloorLiveStore.load(ctx).rows.filter { it.holds && Spaces.baseOf(it.ns) == base }

    /**
     * Every lot the app holds in one market — shares in hand only, a buy still
     * waiting at the broker holds nothing yet. The watchlist's are read from its
     * own book, which carries the price each was bought at; the broker rows
     * mirror the same lots, so reading both would count them twice.
     */
    fun lots(ctx: Context, base: String): List<Lot> {
        val out = ArrayList<Lot>()
        val names = HashMap<String, String>()
        fun name(ns: String) = names.getOrPut(ns) { nameOf(ctx, ns) }
        fun isWatch(ns: String) = Spaces.bucketIdOf(ns) == ManualWatchStore.ID
        try {
            ManualWatchStore.load(ctx, base).entries
                .filter { it.status == "OPEN" && it.filledQty > 0.0 }
                .forEach { e ->
                    out += Lot(ManualWatchStore.nsFor(base), WATCHLIST, WATCHLIST, e.symbol, e.filledQty,
                        if (e.filledPrice > 0.0) e.filledQty * e.filledPrice else 0.0,
                        listOf(e.symbol, e.t212Ticker))
                }
        } catch (e: Exception) { Log.w(TAG, "[$base] watchlist lots unreadable", e) }
        try {
            if (base == "in") {
                ZerodhaLiveStore.load(ctx).trades
                    .filter { Spaces.baseOf(it.ns) == base && !isWatch(it.ns) &&
                        (it.status == "OPEN" || it.status == "EXITING") && it.qty > 0 }
                    .forEach { r ->
                        out += Lot(r.ns, name(r.ns), ALGO, r.symbol, r.qty.toDouble(),
                            if (r.entryPrice > 0.0) r.qty * r.entryPrice else 0.0, listOf(r.symbol))
                    }
            } else {
                T212LiveStore.load(ctx).trades
                    .filter { Spaces.baseOf(it.ns) == base && !isWatch(it.ns) &&
                        (it.status == "OPEN" || it.status == "EXITING") }
                    .forEach { r ->
                        val q = if (r.liveQtyFilled > 0.0) minOf(r.qty, r.liveQtyFilled) else r.qty
                        if (q > 0.0) out += Lot(r.ns, name(r.ns), ALGO, r.symbol, q,
                            if (r.liveEntryPrice > 0.0) q * r.liveEntryPrice else 0.0,
                            listOf(r.symbol, r.t212Ticker))
                    }
            }
        } catch (e: Exception) { Log.w(TAG, "[$base] algo lots unreadable", e) }
        try {
            floorRows(ctx, base).filter { it.status != FloorLiveStore.BUYING && it.qty > 0.0 }.forEach { r ->
                out += Lot(r.ns, name(r.ns), ALGO, r.symbol, r.qty,
                    if (r.entryPrice > 0.0) r.qty * r.entryPrice else 0.0, listOf(r.symbol, r.ticker))
            }
        } catch (e: Exception) { Log.w(TAG, "[$base] built-in algo lots unreadable", e) }
        return out
    }
}

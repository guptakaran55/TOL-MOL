package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

/**
 * THE REAL POSITIONS OF THE BUILT-IN ALGOS — one row per paper trade copied to
 * Trading 212. See [com.signalscope.app.trading.FloorLive].
 *
 * Its own file, not [T212LiveStore]: every sweep over that file asks the main
 * algos' paper books and seats about each row, and a built-in algo has neither,
 * so those sweeps would read its positions as orphans. The one thing both files
 * must agree on — who owns which shares of a ticker — is shared through
 * [claimsQty], which [T212LiveStore.otherClaimsQty] adds in.
 */
object FloorLiveStore {
    private const val TAG = "FloorLiveStore"
    private const val FILE = "floor_live.json"
    private val gson = Gson()

    /** BUYING → OPEN → SELLING → CLOSED; SKIPPED or FAILED when nothing was bought. */
    const val BUYING = "BUYING"
    const val OPEN = "OPEN"
    const val SELLING = "SELLING"
    const val CLOSED = "CLOSED"
    const val SKIPPED = "SKIPPED"
    const val FAILED = "FAILED"

    data class Row(
        /** The algo's namespace ("de__<id>") and its paper trade's id — the key. */
        val ns: String = "",
        val tradeId: String = "",
        val symbol: String = "",
        /** Trading 212's instrument, once the buy has resolved it. */
        val ticker: String = "",
        val status: String = BUYING,
        /** Euros asked for. */
        val amount: Double = 0.0,
        /** The paper buy's price — what the real fill is measured against. */
        val paperPrice: Double = 0.0,
        /** Shares held: bought, less any sold. */
        val qty: Double = 0.0,
        /** Real average buying price; 0 until the broker reports it. */
        val entryPrice: Double = 0.0,
        val buyIds: List<Long> = emptyList(),
        val buyAt: Long = 0L,
        val soldQty: Double = 0.0,
        /** Real average selling price; 0 until the broker reports it. */
        val exitPrice: Double = 0.0,
        val sellIds: List<Long> = emptyList(),
        val sellAt: Long = 0L,
        /** The paper sale's reason (stop, target, time, trend) and price. */
        val exitReason: String = "",
        val paperExit: Double = 0.0,
        val tries: Int = 0,
        val note: String = ""
    ) {
        /** Shares this row owns at the broker, or is in the middle of buying or selling.
         *  An open row with no buy order behind it was never bought (S92, 4 Nov 2026:
         *  the whole holding recorded after an unreadable first look) and owns nothing. */
        val holds: Boolean get() = status == BUYING || status == SELLING || (status == OPEN && buyIds.isNotEmpty())
    }

    data class State(val rows: List<Row> = emptyList())

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun load(ctx: Context): State = try {
        val f = file(ctx)
        StoreCache.read(f) { gson.fromJson(it, State::class.java) } ?: State()   // parsed once, kept until the file changes
    } catch (e: Exception) { Log.w(TAG, "load failed: ${e.message}"); State() }

    @Synchronized
    private fun save(ctx: Context, s: State) {
        try {
            val f = file(ctx)
            StoreCache.write(f, gson.toJson(s))   // in one step: never an empty file to read
        } catch (e: Exception) { Log.e(TAG, "save failed", e) }
    }

    fun rowsFor(ctx: Context, ns: String): List<Row> = load(ctx).rows.filter { it.ns == ns }

    fun get(ctx: Context, ns: String, tradeId: String): Row? =
        load(ctx).rows.firstOrNull { it.ns == ns && it.tradeId == tradeId }

    /** Insert or replace by (namespace, paper trade). Ended rows beyond the newest 300 are dropped. */
    @Synchronized
    fun upsert(ctx: Context, row: Row) {
        val cur = load(ctx).rows.filterNot { it.ns == row.ns && it.tradeId == row.tradeId } + row
        val ended = cur.filter { !it.holds }
        val keep = if (ended.size <= 300) cur
            else cur.filter { it.holds } + ended.sortedBy { maxOf(it.sellAt, it.buyAt) }.takeLast(300)
        save(ctx, State(keep))
    }

    /** Change one row in place, if it is there. */
    @Synchronized
    fun update(ctx: Context, ns: String, tradeId: String, f: (Row) -> Row): Row? {
        val r = get(ctx, ns, tradeId) ?: return null
        val n = f(r)
        upsert(ctx, n)
        return n
    }

    /** Shares of [ticker] (or of [symbol], for a buy that has not resolved its
     *  ticker yet) held by these rows, except the one named. */
    fun claimsQty(ctx: Context, ticker: String, exceptNs: String = "", exceptId: String = "",
                  symbol: String = ""): Double =
        load(ctx).rows.filter { r ->
            r.holds && !(r.ns == exceptNs && r.tradeId == exceptId) &&
                ((ticker.isNotBlank() && r.ticker.equals(ticker, true)) ||
                    (symbol.isNotBlank() && r.ticker.isBlank() && r.symbol.equals(symbol, true)))
        }.sumOf { it.qty }

    /** Shares of [symbol] these rows hold — the watchlist's "taken by an algo" count. */
    fun heldQtyOf(ctx: Context, symbol: String): Double =
        load(ctx).rows.filter { it.holds && it.symbol.equals(symbol, true) }.sumOf { it.qty }
}

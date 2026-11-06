package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

/**
 * YOUR "BUY AT MY PRICE" ENTRIES ON TRADING 212 — armed in the app, not at the broker.
 *
 * A limit buy rests at Trading 212 and holds its cash back until it fills or is
 * cancelled, so the money is missing from the account all the while. These hold
 * nothing: the app watches the price, and when it reaches your level the buy
 * starts exactly as the stock page's Patient buy would — through the RV gate,
 * then the patient ladder. See [com.signalscope.app.trading.PriceEntries].
 *
 * One file, kept by [StoreCache]. Finished entries stay 30 days for the record.
 */
object PriceEntryStore {
    private const val TAG = "PriceEntryStore"
    private const val FILE = "price_entries.json"
    private const val KEEP_FINISHED_MS = 30L * 24 * 3_600_000L
    private val gson = Gson()

    data class Entry(
        val id: String = "",
        /** "DE" or "US". */
        val space: String = "",
        val symbol: String = "",
        /** Trading 212's instrument code, when it could be resolved. */
        val ticker: String = "",
        val yahooSymbol: String = "",
        /** What to invest when it starts. */
        val cash: Double = 0.0,
        /** The price that starts the buy. */
        val level: Double = 0.0,
        /** True: starts when the price RISES to [level] (it was set above the price);
         *  false: when it FALLS to it. Decided once, when it is set — as a GTT does. */
        val rising: Boolean = false,
        /** The price when it was set. */
        val setAt: Double = 0.0,
        val createdAt: Long = 0L,
        val expiresAt: Long = 0L,
        /** WAITING · TRIGGERED · CANCELLED · EXPIRED · FAILED */
        val status: String = "WAITING",
        /** The latest look at the price, and when. */
        val lastPrice: Double = 0.0,
        val lastCheckAt: Long = 0L,
        val triggeredAt: Long = 0L,
        val triggerPrice: Double = 0.0,
        /** What happened when it triggered — held by the RV gate, started, refused. */
        val result: String = "",
        /** Buys that went nowhere so far — armed again after each, up to
         *  PriceEntries.MAX_TRIES. */
        val tries: Int = 0,
        /** Re-armed after a buy that went nowhere: not looked at again before this. */
        val retryAt: Long = 0L
    )

    data class State(val entries: List<Entry> = emptyList())

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun load(ctx: Context): State = try {
        StoreCache.read(file(ctx)) { gson.fromJson(it, State::class.java) } ?: State()
    } catch (e: Exception) { Log.e(TAG, "load failed", e); State() }

    @Synchronized
    private fun save(ctx: Context, s: State) {
        try {
            val now = System.currentTimeMillis()
            val keep = s.entries.filter {
                it.status == "WAITING" || now - maxOf(it.createdAt, it.triggeredAt) < KEEP_FINISHED_MS
            }
            StoreCache.write(file(ctx), gson.toJson(State(keep)))
        } catch (e: Exception) { Log.e(TAG, "save failed", e) }
    }

    @Synchronized
    fun mutate(ctx: Context, f: (State) -> State): State {
        val next = f(load(ctx)); save(ctx, next); return next
    }

    fun waiting(ctx: Context): List<Entry> = load(ctx).entries.filter { it.status == "WAITING" }

    fun add(ctx: Context, e: Entry) { mutate(ctx) { it.copy(entries = it.entries + e) } }

    /** Change one entry. Null when there is no such entry. */
    @Synchronized
    fun update(ctx: Context, id: String, f: (Entry) -> Entry): Entry? {
        var out: Entry? = null
        mutate(ctx) { s -> s.copy(entries = s.entries.map { if (it.id == id) f(it).also { n -> out = n } else it }) }
        return out
    }

    /**
     * Move a WAITING entry on — to TRIGGERED, CANCELLED or EXPIRED — only if it is
     * still waiting. The one place an entry leaves WAITING, so a cancel and a
     * trigger arriving together cannot both win, and nothing triggers twice.
     * Null when it had already left WAITING.
     */
    @Synchronized
    fun leaveWaiting(ctx: Context, id: String, f: (Entry) -> Entry): Entry? {
        val cur = load(ctx).entries.firstOrNull { it.id == id } ?: return null
        if (cur.status != "WAITING") return null
        return update(ctx, id, f)
    }
}

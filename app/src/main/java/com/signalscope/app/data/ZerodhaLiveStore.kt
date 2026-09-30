package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

/**
 * REAL ZERODHA ORDERS PLACED BY AN ALGO — one row per paper trade mirrored.
 *
 * The Indian counterpart of the T212 live ledger, and deliberately a SEPARATE
 * store rather than a shared one: the two brokers disagree about almost
 * everything that matters here. Zerodha deals in whole shares, settles orders
 * synchronously enough to read back an average price, and offers server-side
 * conditional orders (GTT). Folding both into one record would mean a row where
 * half the fields are meaningless whichever broker filled it.
 *
 * WHAT A ROW IS FOR. It is the join between a paper trade and the real money
 * that followed it. Without it there is no way to answer "did the algo's exit
 * actually sell the shares its entry bought", which is the only question that
 * makes live performance measurable rather than assumed.
 *
 * THE GTT ID IS THE DANGEROUS FIELD. A protective GTT sits at the broker
 * waiting to sell. When the strategy decides to exit on its own, that trigger
 * MUST be deleted before the sell goes in, or the position is sold twice — once
 * by the algo and once by the trigger, the second becoming an accidental short.
 * Storing the id is what makes that deletion possible; losing it is what makes
 * the double-sell inevitable.
 */
object ZerodhaLiveStore {

    private const val TAG = "ZerodhaLiveStore"
    private const val FILE = "zerodha_live_trades.json"
    private val gson = Gson()

    data class Row(
        val ns: String = "",                 // which algo placed it
        val paperTradeId: String = "",       // the paper trade this mirrors
        val symbol: String = "",             // NSE trading symbol
        /** PENDING · OPEN · EXITING · CLOSED · ERROR · MANUAL_CLOSED */
        val status: String = "PENDING",
        val qty: Int = 0,                    // NSE equities are whole shares only
        val buyOrderId: String? = null,
        val sellOrderId: String? = null,
        /** The protective trigger resting at the broker. Must be deleted before
         *  any algo-initiated sell — see the class note. */
        val gttId: Long? = null,
        val entryPrice: Double = 0.0,        // average fill, once known
        val exitPrice: Double = 0.0,
        val paperEntryPrice: Double = 0.0,   // what the decision saw
        val paperExitPrice: Double = 0.0,
        val buyAt: Long = 0L,
        val sellAt: Long = 0L,
        val manualClosedAt: Long = 0L,
        val error: String = "",
        /** The strategy's exit could not go through and is waiting to be retried.
         *  False on every row written before this existed — see the retry sweep. */
        val exitHeld: Boolean = false,
        /** Retries of that exit so far; capped so a stock that cannot be sold is
         *  not churned every quarter hour. */
        val exitTries: Int = 0,
        /** When the account was first seen holding none of these shares; the row
         *  is closed only when a SECOND reading, a pass later, agrees. 0 = not seen. */
        val goneSeenAt: Long = 0L,
        /**
         * THE ADOPTED-EXIT VERDICT for a position the engine no longer tracks.
         *
         * Blank or null = nothing to say: either the strategy still holds this
         * trade itself, or the adopted rules were applied cleanly. A message means
         * the rules could NOT be applied, which leaves the position on its hard
         * stop alone — the one thing the user has to be told rather than left to
         * infer from an absence.
         *
         * NULLABLE for the reason every other late field here is: Gson builds
         * these through Unsafe and never runs a Kotlin default, so a row written
         * before this existed arrives with it absent.
         */
        val adoptedNote: String? = null,
    ) {
        val isManual: Boolean get() = status == "MANUAL_CLOSED"
        val isLive: Boolean get() = status == "OPEN" || status == "PENDING" || status == "EXITING"
    }

    data class State(val trades: List<Row> = emptyList())

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun load(ctx: Context): State = try {
        val f = file(ctx)
        if (!f.exists()) State() else gson.fromJson(f.readText(), State::class.java) ?: State()
    } catch (e: Exception) { Log.w(TAG, "load failed: ${e.message}"); State() }

    @Synchronized
    fun save(ctx: Context, s: State) {
        try {
            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(gson.toJson(s))
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (e: Exception) { Log.e(TAG, "save failed", e) }
    }

    /** Insert or replace by (namespace, paper trade) — the natural key. */
    @Synchronized
    fun upsert(ctx: Context, row: Row) {
        val cur = load(ctx).trades.toMutableList()
        val i = cur.indexOfFirst { it.ns == row.ns && it.paperTradeId == row.paperTradeId }
        if (i >= 0) cur[i] = row else cur.add(row)
        save(ctx, State(cur))
    }

    fun forTrade(ctx: Context, ns: String, paperTradeId: String): Row? =
        load(ctx).trades.firstOrNull { it.ns == ns && it.paperTradeId == paperTradeId }

    fun openFor(ctx: Context, ns: String): List<Row> =
        load(ctx).trades.filter { it.ns == ns && it.isLive }

    /** Whole shares of one symbol claimed by OTHER algos — so an exit never sells
     *  a sibling's position. The Indian analogue of the T212 shared-holding
     *  problem: one Zerodha account, several algos, one aggregate holding. */
    fun claimedByOthers(ctx: Context, ns: String, symbol: String): Int =
        load(ctx).trades
            .filter { it.ns != ns && it.symbol.equals(symbol, true) && it.isLive }
            .sumOf { it.qty }
}

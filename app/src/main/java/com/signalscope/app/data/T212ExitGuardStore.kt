package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

/**
 * THE SAFETY STOPS under sales that are waiting — one row per position.
 *
 * Its own file, for the same reason the 1-week-high targets have one: parts of
 * the app rewrite a ledger row whole from an older copy, which would quietly
 * drop an order id kept there and leave a real stop at the broker that nothing
 * remembers.
 *
 * Keyed like the ledger: (namespace, paper trade id).
 */
object T212ExitGuardStore {
    private const val TAG = "T212ExitGuardStore"
    private val gson = Gson()
    private const val KEEP_DONE_MS = 14L * 24 * 60 * 60_000L

    data class Guard(
        val ns: String = "",
        val paperTradeId: String = "",
        val symbol: String = "",
        val ticker: String = "",
        /** The stop price, fixed when first worked out. */
        val level: Double = 0.0,
        /** The price the strategy sold at on paper. */
        val decisionPx: Double = 0.0,
        /** The resting stop, 0 = none at the broker right now. */
        val orderId: Long = 0L,
        val orderQty: Double = 0.0,
        val placedAt: Long = 0L,
        /** Stops this guard rested as before — still matched against the
         *  broker's history so a fill reported late is not lost. */
        val prevIds: List<Long>? = null,
        val retryAfter: Long = 0L,
        /** What is happening, or why there is no stop. Nullable: Gson skips defaults. */
        val note: String? = null,
        val filledAt: Long = 0L,
        val fillPrice: Double = 0.0,
        val fillQty: Double = 0.0,
        val done: Boolean = false,
        val doneAt: Long = 0L,
        val announced: Boolean = false,
        val createdAt: Long = 0L
    ) {
        fun key() = T212ExitGuardStore.key(ns, paperTradeId)
    }

    data class State(val guards: List<Guard> = emptyList())

    fun key(ns: String, paperTradeId: String) = "$ns|$paperTradeId"

    private fun file(ctx: Context) = File(ctx.filesDir, "t212_exit_guards.json")

    fun load(ctx: Context): State = try {
        val f = file(ctx)
        if (!f.exists()) State() else gson.fromJson(f.readText(), State::class.java) ?: State()
    } catch (e: Exception) { Log.e(TAG, "load failed", e); State() }

    @Synchronized
    private fun save(ctx: Context, s: State) {
        try {
            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(gson.toJson(s))
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (e: Exception) { Log.e(TAG, "save failed", e) }
    }

    fun get(ctx: Context, ns: String, paperTradeId: String): Guard? =
        load(ctx).guards.firstOrNull { it.ns == ns && it.paperTradeId == paperTradeId }

    /** Read, change and write one row as a single step. */
    @Synchronized
    fun update(ctx: Context, ns: String, paperTradeId: String, seed: () -> Guard,
               change: (Guard) -> Guard): Guard {
        val s = load(ctx)
        val cur = s.guards.firstOrNull { it.ns == ns && it.paperTradeId == paperTradeId } ?: seed()
        val next = change(cur)
        val now = System.currentTimeMillis()
        val kept = s.guards.filterNot {
            (it.ns == ns && it.paperTradeId == paperTradeId) ||
                (it.done && it.doneAt > 0L && now - it.doneAt > KEEP_DONE_MS)
        }
        save(ctx, State(kept + next))
        return next
    }

    /** Every stop this feature has resting — the daily trailing pass must leave
     *  these alone. */
    fun restingIds(ctx: Context): Set<Long> =
        load(ctx).guards.filter { it.orderId > 0L }.map { it.orderId }.toSet()
}

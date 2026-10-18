package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

/**
 * THE 1-WEEK-HIGH TARGETS resting at Trading 212 — one row per position.
 *
 * Kept APART from the live ledger on purpose. Several parts of the app rewrite
 * a ledger row whole from a copy they read earlier, which is safe for the fields
 * they own and would quietly undo anyone else's: a target order id wiped that
 * way leaves a real sell resting at the broker that nothing here remembers. In
 * a file only the target keeper writes, that cannot happen.
 *
 * Keyed like the ledger: (namespace, paper trade id).
 */
object T212TargetStore {
    private const val TAG = "T212TargetStore"
    private val gson = Gson()

    /** Finished rows are kept this long for the card, then dropped. */
    private const val KEEP_DONE_MS = 14L * 24 * 60 * 60_000L

    data class Target(
        val ns: String = "",
        val paperTradeId: String = "",
        val symbol: String = "",
        val ticker: String = "",
        /** The price, fixed at the buy. 0 = none (see [note]). */
        val price: Double = 0.0,
        /** Which rule picked it: "week" or "month". Null on rows decided before
         *  there was a choice — those were all the week's high. */
        val kind: String? = null,
        /** The rule version that decided this row; null = the old week-high-only
         *  rule. A "no target" from that rule is decided again under the new one.
         *  Nullable because Gson never runs a Kotlin default. */
        val rule: String? = null,
        /** The fill it was measured against. */
        val paid: Double = 0.0,
        /** Why there is no target, or what went wrong last. Null = nothing to say.
         *  Nullable because Gson never runs a Kotlin default. */
        val note: String? = null,
        /** The resting order, 0 = none at the broker right now. */
        val orderId: Long = 0L,
        val orderQty: Double = 0.0,
        val placedAt: Long = 0L,
        /** First pass the order was missing without a reported fill. */
        val goneSince: Long = 0L,
        /** Orders this target rested as before being put back. Still matched
         *  against the broker's history, so a fill reported late is not lost. */
        val prevIds: List<Long>? = null,
        /** Not before this (epoch ms) — a refused order is not re-sent every pass. */
        val retryAfter: Long = 0L,
        /** You took it off. Never put back. */
        val removed: Boolean = false,
        /** The target sold the position. */
        val filledAt: Long = 0L,
        val fillPrice: Double = 0.0,
        val fillQty: Double = 0.0,
        /** The algo's paper record has been closed to match. */
        val booked: Boolean = false,
        /** Finished for good: sold, removed, or the position ended some other way. */
        val done: Boolean = false,
        val doneAt: Long = 0L,
        /** The "target is waiting" notice has been sent. */
        val announced: Boolean = false,
        val createdAt: Long = 0L
    ) {
        fun key() = T212TargetStore.key(ns, paperTradeId)
    }

    data class State(val targets: List<Target> = emptyList())

    fun key(ns: String, paperTradeId: String) = "$ns|$paperTradeId"

    private fun file(ctx: Context) = File(ctx.filesDir, "t212_week_targets.json")

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

    fun get(ctx: Context, ns: String, paperTradeId: String): Target? =
        load(ctx).targets.firstOrNull { it.ns == ns && it.paperTradeId == paperTradeId }

    /** Read, change and write one row as a single step. Creates it from [seed]
     *  when absent; returns the row as written. */
    @Synchronized
    fun update(ctx: Context, ns: String, paperTradeId: String, seed: () -> Target,
               change: (Target) -> Target): Target {
        val s = load(ctx)
        val cur = s.targets.firstOrNull { it.ns == ns && it.paperTradeId == paperTradeId } ?: seed()
        val next = change(cur)
        val now = System.currentTimeMillis()
        val kept = s.targets.filterNot {
            (it.ns == ns && it.paperTradeId == paperTradeId) ||
                (it.done && it.doneAt > 0L && now - it.doneAt > KEEP_DONE_MS)
        }
        save(ctx, State(kept + next))
        return next
    }

    /** Every order id this feature has resting — the daily trailing pass must
     *  leave these alone. */
    fun restingIds(ctx: Context): Set<Long> =
        load(ctx).targets.filter { it.orderId > 0L }.map { it.orderId }.toSet()
}

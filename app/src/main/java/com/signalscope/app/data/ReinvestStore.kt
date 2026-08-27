package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

// ════════════════════════════════════════════════════════════════════════════
// REINVEST STORE — persistent state for the monthly rescan→reinvest loop (§4)
// ════════════════════════════════════════════════════════════════════════════
// One file per lab namespace ("us" / "binance"). Holds the capital-migration
// state that ReinvestEngine drives, made durable: per-symbol
// balances (compounded by each closed trade's % return), when each was enrolled,
// the universe to rescan, and the last month a rebalance ran (so the monthly
// trigger fires once per calendar month). Atomic JSON, same pattern as the other
// stores. Never places orders — it only moves paper capital + drives enrollment.
// ════════════════════════════════════════════════════════════════════════════

object ReinvestStore {

    private const val TAG = "ReinvestStore"
    private val gson = Gson()

    /** One monthly rebalance event, for the history/audit view. */
    data class Event(
        val ts: Long,
        val month: String,               // "yyyy-MM" this rebalance was for
        val dropped: List<String>,
        val added: List<String>,
        val pool: Double,                // leavers' cash recycled
        val totalValue: Double,
        val compoundedPct: Double
    )

    data class State(
        val enabled: Boolean = false,
        val initialized: Boolean = false,
        val mode: String = "MIGRATE",            // MIGRATE | EQUAL_REBALANCE
        val notional: Double = 1000.0,
        val topN: Int = 20,
        val universe: List<String> = emptyList(),
        val balances: Map<String, Double> = emptyMap(),     // symbol -> compounded $ balance
        val enrolledSince: Map<String, Long> = emptyMap(),  // symbol -> epoch DAY of enrollment
        val injectedCapital: Double = 0.0,
        /** Freed capital with nowhere to redeploy — a rebalance dropped a name but
         *  no joiner exists to receive its cash (common on the 2-coin crypto book
         *  at hold-1). Held FLAT so it stays in the total (matches the Python
         *  cash-slot), and rejoins the distributable pool at the next rebalance.
         *  Dropping it instead would under-report returns whenever cash parks. */
        val idleCash: Double = 0.0,
        val lastRebalanceMonth: String = "",     // "yyyy-MM" — guards the once-a-month trigger
        val lastRebalanceAt: Long = 0L,
        val history: List<Event> = emptyList(),
        // Nullable (not emptyList) on purpose: Gson bypasses Kotlin defaults when
        // deserializing OLD state files, which would leave a non-null field null
        // at runtime. Treat null as empty at every use site.
        /** Symbols dropped by a rebalance while still holding an open position:
         *  they stay enrolled EXIT-ONLY until the algo closes the trade, then
         *  their capital is released to lastJoiners. Live positions are never
         *  dumped at rebalance time — exits happen at algo-chosen times. */
        val windingDown: List<String>? = null,
        /** Joiners of the most recent rebalance — recipients of wind-down cash. */
        val lastJoiners: List<String>? = null
    )

    private fun file(ctx: Context, ns: String): File =
        File(ctx.filesDir, "reinvest_${ns}.json")

    fun load(ctx: Context, ns: String): State {
        return try {
            val f = file(ctx, ns)
            if (!f.exists()) State() else gson.fromJson(f.readText(), State::class.java) ?: State()
        } catch (e: Exception) {
            Log.e(TAG, "load failed — returning empty", e); State()
        }
    }

    @Synchronized
    fun save(ctx: Context, ns: String, state: State) {
        try {
            val f = file(ctx, ns)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(gson.toJson(state))
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
        }
    }

    @Synchronized
    fun mutate(ctx: Context, ns: String, block: (State) -> State): State {
        val next = block(load(ctx, ns)); save(ctx, ns, next); return next
    }

    fun totalValue(s: State): Double = s.balances.values.sum() + s.idleCash
    fun compoundedPct(s: State): Double =
        if (s.injectedCapital > 0.0) (totalValue(s) / s.injectedCapital - 1.0) * 100.0 else 0.0

    fun setEnabled(ctx: Context, ns: String, on: Boolean): State =
        mutate(ctx, ns) { it.copy(enabled = on) }

    /** Full reset — clears the reinvest book (keeps nothing). */
    fun reset(ctx: Context, ns: String): State =
        mutate(ctx, ns) { State() }
}

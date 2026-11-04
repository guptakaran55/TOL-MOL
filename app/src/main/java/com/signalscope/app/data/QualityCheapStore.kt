package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

/**
 * WHAT THE "QUALITY · CHEAP · UPTREND" PAPER ALGOS KNOW BEYOND AN ORDINARY ALGO:
 * which quarter their company list is from, a new quarter's list waiting for your
 * approval, and the news veto's record — every verdict asked for, and each stock
 * the veto kept out, followed afterwards so the record shows whether it helped.
 * See [com.signalscope.app.trading.QualityCheap].
 *
 * Its own small file, keyed by the algo's namespace. The algo itself stays an
 * ordinary bucket; deleting it leaves an entry here that nothing reads.
 */
object QualityCheapStore {
    private const val TAG = "QualityCheapStore"
    private const val FILE = "quality_cheap.json"

    /** Most verdicts kept per algo — about a year at a few a day. */
    const val MAX_CHECKS = 400

    /** A new quarter's list, waiting for your yes or no. */
    data class Proposal(
        val quarter: String = "",
        val list: List<String> = emptyList(),
        val added: List<String> = emptyList(),
        val removed: List<String> = emptyList(),
        /** When the scan it came from finished. */
        val scanAt: Long = 0L,
        val madeAt: Long = 0L,
        /** True when you asked for it mid-quarter rather than the quarter ending. */
        val manual: Boolean = false
    )

    /** One news verdict asked for a stock about to take a seat. */
    data class Check(
        val day: String = "",
        val symbol: String = "",
        val verdict: String = "",
        val confidence: String = "",
        val reason: String = "",
        val price: Double = 0.0,
        val vetoed: Boolean = false
    )

    /**
     * A stock the veto kept out, followed from then on. One row per spell: the
     * same stock kept out on the days after only moves [lastDay]; kept out again
     * after it was let through is a new row.
     */
    data class Kept(
        val symbol: String = "",
        val since: String = "",
        val lastDay: String = "",
        val days: Int = 1,
        val verdict: String = "",
        val reason: String = "",
        /** The price when it was first kept out. */
        val price: Double = 0.0,
        /** Its latest daily close, refreshed by the algo's daily evaluation. */
        val nowPx: Double = 0.0,
        val nowDay: String = ""
    )

    data class Algo(
        val ns: String = "",
        /** The quarter the current list is from, "2026-Q3". */
        val listQuarter: String = "",
        val proposal: Proposal? = null,
        /** A quarter whose list you turned down — not offered again. */
        val dismissed: String? = null,
        /** What the last quarterly check found, in words, for the card. */
        val lastNote: String? = null,
        /** The news veto, on unless switched off. */
        val veto: Boolean? = null,
        val checks: List<Check>? = null,
        val kept: List<Kept>? = null
    )

    data class State(val algos: List<Algo> = emptyList())

    private val gson = Gson()
    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    @Synchronized
    fun load(ctx: Context): State {
        val f = file(ctx)
        if (!f.exists()) return State()
        return try {
            gson.fromJson(f.readText(), State::class.java) ?: State()
        } catch (e: Exception) {
            Log.e(TAG, "unreadable — set aside, starting afresh", e)
            try { f.renameTo(File(f.parentFile, "$FILE.bad-${System.currentTimeMillis()}")) } catch (_: Exception) {}
            State()
        }
    }

    @Synchronized
    private fun save(ctx: Context, s: State) {
        try {
            val f = file(ctx)
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(gson.toJson(s))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) { Log.e(TAG, "save failed", e) }
    }

    fun get(ctx: Context, ns: String): Algo? = load(ctx).algos.firstOrNull { it.ns == ns }

    /** Change one algo's entry, as one step. Nothing happens for an unknown one. */
    @Synchronized
    fun update(ctx: Context, ns: String, block: (Algo) -> Algo): Algo? {
        val s = load(ctx)
        val cur = s.algos.firstOrNull { it.ns == ns } ?: return null
        val next = block(cur).let { a ->
            a.copy(checks = a.checks?.takeLast(MAX_CHECKS), kept = a.kept?.takeLast(MAX_CHECKS))
        }
        save(ctx, s.copy(algos = s.algos.map { if (it.ns == ns) next else it }))
        return next
    }

    /** Marks a newly made algo as one of these, its list from [quarter]. */
    @Synchronized
    fun register(ctx: Context, ns: String, quarter: String) {
        val s = load(ctx)
        if (s.algos.any { it.ns == ns }) return
        save(ctx, s.copy(algos = s.algos + Algo(ns = ns, listQuarter = quarter)))
    }
}

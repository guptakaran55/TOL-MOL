package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

// ════════════════════════════════════════════════════════════════════════════
// PAPER-vs-REAL STOP DEVIATION
// ════════════════════════════════════════════════════════════════════════════
// Written at the moment a live position is stopped out, because it cannot be
// reconstructed afterwards. Once the trade is closed the two things that matter
// most — which stop actually fired, and where the OTHER one would have fired —
// exist nowhere: the paper stop's trigger price is derived from a signal price
// the ledger overwrites on close, and the real stop's from a fill price that
// only lives on the live row until the row is recycled.
//
// This is the dataset that answers the question the dual stop exists to raise:
// how far do real fills drift from signal prices, and what does that drift cost?
// A single stored row is worth more than any amount of after-the-fact inference,
// so it is written even when nothing looks interesting.
//
// Deliberately NOT part of SlippageStats: that object is a pure calculator over
// closed round trips and has no storage of its own. Keeping the record here
// leaves it a calculator.
// ════════════════════════════════════════════════════════════════════════════

object StopDeviationStore {

    private const val TAG = "StopDeviationStore"
    private val gson = Gson()
    private const val MAX = 300

    data class Deviation(
        val ns: String = "",
        val symbol: String = "",
        val at: Long = 0L,
        /** What the engine calls the entry — the signal bar's close. */
        val signalPrice: Double = 0.0,
        /** What the ladder actually paid, value-weighted across its rungs. */
        val fillPrice: Double = 0.0,
        /** fill vs signal, %. POSITIVE = paid more than the strategy assumed,
         *  which is the direction that tightens the real stop. */
        val gapPct: Double = 0.0,
        /** "real" or "paper" — which stop closed it. */
        val closedBy: String = "",
        val exitPrice: Double = 0.0,
        /** Where the OTHER stop would have fired. The counterfactual is the whole
         *  point: without it a row says a stop fired but not what it saved or cost. */
        val otherStopAt: Double = 0.0,
        /** Return the paper ledger was showing when the real stop fired. */
        val paperPct: Double = 0.0,
        /** Return on the money actually spent. */
        val realPct: Double = 0.0
    )

    data class State(val rows: List<Deviation> = emptyList())

    private fun file(ctx: Context) = File(ctx.filesDir, "stop_deviations.json")

    @Synchronized
    fun load(ctx: Context): State = try {
        val f = file(ctx)
        if (!f.exists()) State() else gson.fromJson(f.readText(), State::class.java) ?: State()
    } catch (e: Exception) { Log.e(TAG, "load failed", e); State() }

    @Synchronized
    fun record(ctx: Context, d: Deviation) {
        try {
            val next = (load(ctx).rows + d).takeLast(MAX)
            file(ctx).writeText(gson.toJson(State(next)))
            Log.i(TAG, "deviation ${d.symbol}: signal ${d.signalPrice} → fill ${d.fillPrice} " +
                "(${"%+.2f".format(d.gapPct)}%), closed by ${d.closedBy}, " +
                "paper ${"%+.2f".format(d.paperPct)}% vs real ${"%+.2f".format(d.realPct)}%")
        } catch (e: Exception) { Log.w(TAG, "record failed: ${e.message}") }
    }

    /** Mean fill-vs-signal gap across everything recorded, or null with no rows.
     *  This is the number that says whether the dual stop is earning its keep. */
    fun meanGapPct(ctx: Context): Double? {
        val rows = load(ctx).rows.filter { it.signalPrice > 0.0 && it.fillPrice > 0.0 }
        return if (rows.isEmpty()) null else rows.sumOf { it.gapPct } / rows.size
    }
}

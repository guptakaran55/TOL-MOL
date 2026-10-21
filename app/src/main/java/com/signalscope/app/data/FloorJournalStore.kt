package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File
import java.util.Locale

/**
 * THE SCAN JOURNAL OF THE BUILT-IN PAPER ALGOS — one row per stock per day,
 * every stock in the universe, bought or not.
 *
 * The algo's own record only keeps what it did. This keeps what it SAW, so the
 * rules can be judged later on the days nothing happened: how close the price
 * came to a floor order that never filled, which stocks dipped below their floor
 * but were kept out by the trend, which MACD turns were blocked by the 4-hour —
 * and, for each, what the price did over the next few days.
 *
 * Written the morning after each plan day, from that day's finished daily bar.
 * See [com.signalscope.app.trading.FloorJournal] for what goes in a row.
 *
 * A FILE PER ALGO, apart from the algos' own record: thirty rows a day add up,
 * and the record every pass reads and writes should stay small. The oldest days
 * fall off past [KEEP_DAYS].
 */
object FloorJournalStore {
    private const val TAG = "FloorJournalStore"
    private const val KEEP_DAYS = 160

    const val BOUGHT = "bought"
    const val SEATS_FULL = "seats full"
    const val NO_FILL = "no fill"
    const val NO_TURN = "no turn"
    const val H4_BLOCKED = "4h blocked"
    const val TREND_BLOCKED = "trend blocked"
    const val HELD = "held"
    const val NO_DATA = "no data"

    data class Row(
        /** The plan day, on the exchange's calendar (yyyy-mm-dd). */
        val day: String = "",
        val symbol: String = "",
        val name: String = "",
        /** Place in the universe by ROC (room to resistance), from 1. */
        val rank: Int = 0,
        /** Return on capital, on rows written while the list was picked by it. */
        val roce: Double? = null,
        /** ROC, the room to the nearest resistance, in percent. */
        val roc: Double? = null,
        /** Weekly and daily MACD slope that morning; the 4-hour at the last look (MACD turns). */
        val week: Int? = null,
        val dayTrend: Int? = null,
        val h4: Int? = null,
        /** What the day came to for this stock — one of the constants above. */
        val result: String = "",
        /** The floor (lowest price of the last few days) and the second order's price.
         *  0 for MACD turns, and where there were not enough days. */
        val floor: Double = 0.0,
        val second: Double = 0.0,
        /** The day's low and close, from the finished daily bar. */
        val low: Double = 0.0,
        val close: Double = 0.0,
        /** How near the low came to the floor, in percent: negative = traded below it. */
        val gap: Double? = null,
        /** Floor halves filled. */
        val halves: Int = 0,
        // MACD turns: looks that day, looks with the 4-hour rising, looks with the
        // 15-minute and hourly both rising, and when the turn came (0 = none).
        val looks: Int = 0,
        val h4Up: Int = 0,
        val bothUp: Int = 0,
        val turnSec: Long = 0L,
        /** The price a buy had, or would have had — what the returns are measured from. */
        val ref: Double = 0.0,
        /** Percent from [ref] to the close one, three and five trading days later. */
        val ret1: Double? = null,
        val ret3: Double? = null,
        val ret5: Double? = null,
        val done: Boolean = false
    )

    data class Journal(val rows: List<Row> = emptyList())

    private val gson = Gson()
    private fun file(ctx: Context, id: String) = File(ctx.filesDir, "floor_journal_$id.json")

    @Synchronized
    fun load(ctx: Context, id: String): Journal {
        val f = file(ctx, id)
        if (!f.exists()) return Journal()
        return try {
            gson.fromJson(f.readText(), Journal::class.java) ?: Journal()
        } catch (e: Exception) {
            Log.e(TAG, "journal $id unreadable — set aside, starting afresh", e)
            try { f.renameTo(File(f.parentFile, "${f.name}.bad-${System.currentTimeMillis()}")) } catch (_: Exception) {}
            Journal()
        }
    }

    /** Read, change and write back as one step; only the newest [KEEP_DAYS] days are kept. */
    @Synchronized
    fun mutate(ctx: Context, id: String, block: (List<Row>) -> List<Row>) {
        val rows = block(load(ctx, id).rows)
        val keep = rows.map { it.day }.distinct().sortedDescending().take(KEEP_DAYS).toSet()
        val tidy = rows.filter { it.day in keep }.sortedWith(compareBy({ it.day }, { it.rank }))
        try {
            val f = file(ctx, id)
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(gson.toJson(Journal(tidy)))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) { Log.e(TAG, "journal $id save failed", e) }
    }

    @Synchronized
    fun delete(ctx: Context, id: String) {
        try { file(ctx, id).delete() } catch (_: Exception) {}
    }

    /** The whole journal as a spreadsheet, oldest first. */
    fun csv(rows: List<Row>): String {
        fun n(v: Double?, d: Int = 2) = if (v == null || !v.isFinite()) "" else String.format(Locale.US, "%.${d}f", v)
        fun s(v: Int?) = v?.toString() ?: ""
        fun q(v: String) = if (v.contains(',') || v.contains('"')) "\"" + v.replace("\"", "\"\"") + "\"" else v
        val sb = StringBuilder()
        sb.append("day,symbol,name,rank,roc_pct,week,day_trend,h4,result,floor,second,low,close,gap_pct,halves," +
            "looks,h4_up,both_up,turn_utc,ref,ret1_pct,ret3_pct,ret5_pct\n")
        for (r in rows) {
            sb.append(listOf(r.day, r.symbol, q(r.name), r.rank.toString(), n(r.roc, 1), s(r.week), s(r.dayTrend), s(r.h4),
                r.result, if (r.floor > 0) n(r.floor, 4) else "", if (r.second > 0) n(r.second, 4) else "",
                n(r.low, 4), n(r.close, 4), n(r.gap), r.halves.toString(),
                r.looks.toString(), r.h4Up.toString(), r.bothUp.toString(),
                if (r.turnSec > 0) java.time.Instant.ofEpochSecond(r.turnSec).toString() else "",
                if (r.ref > 0) n(r.ref, 4) else "", n(r.ret1), n(r.ret3), n(r.ret5)).joinToString(","))
            sb.append('\n')
        }
        return sb.toString()
    }
}

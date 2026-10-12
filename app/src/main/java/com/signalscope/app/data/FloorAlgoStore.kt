package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

/**
 * THE BUILT-IN PAPER ALGOS — floor orders and MACD turns — paper only, built
 * into the app rather than read from a config file.
 *
 * The config-driven algos are a large family where the file picks among moves the
 * engine already knows. These strategies need moves that engine does not have (an
 * order resting at a price, a fill only below it, orders that die at the close,
 * a weekly-and-daily trend filter), so they have their own engine and record.
 * Their few numbers are settings on the algo instead.
 *
 * TWO KINDS, TWO SEPARATE STRATEGIES. Each algo is one or the other, with its
 * own seats and its own record, so the two entries can be compared on their own
 * results rather than competing for the same seats:
 *   • floor — two resting buy orders a day at the recent low;
 *   • macd  — buys at the next price once the 15-minute and hourly MACD turn up
 *             together, with the 4-hour rising.
 * They share the universe, the trend filter and every exit.
 *
 * NOT A BUCKET, ON PURPOSE. Everything that can place a real order — the live
 * mirrors, the arming switches, the broker audits — walks the bucket list. Kept
 * out of it, this algo cannot be armed or mirrored to a broker by any path, not
 * just by the absence of a switch.
 *
 * EVERY FIELD HAS A DEFAULT, so each class has a no-argument constructor and the
 * JSON reader fills a field missing from an older file with that default rather
 * than with an empty value.
 *
 * SAVED SAFELY: written to a temporary file and swapped in; an unreadable file is
 * set aside under another name, never overwritten with an empty list.
 */
object FloorAlgoStore {
    private const val TAG = "FloorAlgoStore"
    private const val FILE = "floor_algos.json"
    private const val MAX_TRADES = 500
    private const val MAX_LOG = 80

    /** Where these algos may run: the stock markets. Crypto never closes, so
     *  "cancel at the close" has no meaning there. */
    val SPACES = listOf("de", "us", "in")

    const val KIND_FLOOR = "floor"
    const val KIND_MACD = "macd"

    data class Settings(
        /** Paper money for the whole algo, in the market's currency. */
        val capital: Double = 1000.0,
        /** Stocks held at once. Each seat is capital ÷ seats; a floor order is half a seat. */
        val seats: Int = 5,
        /** Top of the Discovery list by ROC — room to the nearest resistance. */
        val universeSize: Int = 30,
        /** Floor orders: the first order rests at the lowest price of this many trading days. */
        val lowDays: Int = 3,
        /** Floor orders: the second order rests this many percent below the first. */
        val secondPct: Double = 2.0,
        /** Sold at the close this many trading days after the buy, if nothing else sold it first. */
        val holdDays: Int = 10,
        /** Sold if the price falls this many percent under the average buying price. 0 = no stop. */
        val stopPct: Double = 4.0,
        /** Sold if the price rises this many percent over the average buying price. 0 = no target. */
        val targetPct: Double = 7.0,
        /**
         * THE TREND FILTER, as a switch. On: only stocks with the weekly and daily
         * MACD both rising are bought, and a held stock is sold at the close of a
         * morning that finds either no longer rising. Off: every stock in the
         * universe is a candidate and the trend never sells — the entry and the
         * stop, target and time limit are tested on their own, on more stocks.
         * Off by default (the user's choice, 9 Oct 2026, after a week in which the
         * filter let through 0–6 stocks a day and sold dip buys the next morning).
         */
        val trendFilter: Boolean = false
    )

    /** A stock in the universe, and what the trend filter said on the plan day. */
    data class Member(
        val symbol: String = "",
        val name: String = "",
        /** Return on capital — what the list was first picked by. Kept only so
         *  older saved lists still read; nothing uses it now. */
        val roce: Double? = null,
        /** ROC: room to the nearest resistance from the Discovery scan, in percent —
         *  what the list is picked by. Null on a list saved before that. */
        val roc: Double? = null,
        /** Weekly and daily MACD slope: 1 rising, -1 falling, 0 flat, null unknown / not checked. */
        val week: Int? = null,
        val day: Int? = null,
        /** MACD turns only: the 4-hour slope at the last intraday look. */
        val h4: Int? = null,
        val note: String = "",
        // MACD turns only — the day so far, for the journal: how many looks,
        // how many with the 4-hour rising, how many with the 15-minute and hourly
        // both rising, and the first turn seen (bought, or blocked by the 4-hour).
        val looks: Int = 0,
        val h4Up: Int = 0,
        val bothUp: Int = 0,
        val turnSec: Long = 0L,
        val turnPx: Double = 0.0,
        val turnBlocked: Boolean = false
    )

    /** One half of the day's order on a stock. [filledSec] 0 = not filled. */
    data class Leg(
        val price: Double = 0.0,
        val amount: Double = 0.0,
        val filledSec: Long = 0L,
        val fillPrice: Double = 0.0
    )

    data class Order(
        val symbol: String = "",
        val name: String = "",
        /** The floor: the lowest price of the last few trading days. */
        val low: Double = 0.0,
        val first: Leg = Leg(),
        val second: Leg = Leg(),
        /** RESTING while the day is on; ENDED once the day is over (any half
         *  still unfilled then simply expired). */
        val status: String = "RESTING",
        /** Only prices from this moment on can fill it — never one that traded
         *  before the order existed. */
        val fromSec: Long = 0L
    )

    data class Trade(
        val id: String = "",
        val symbol: String = "",
        val name: String = "",
        /** The exchange's calendar day it was bought (yyyy-mm-dd). */
        val day: String = "",
        /** When the first half filled — the dip is measured from here. 0 = a MACD
         *  turn seen but not yet filled: it buys at the next fifteen-minute price. */
        val firstSec: Long = 0L,
        val qty: Double = 0.0,
        /** Money in. The average buying price is cost ÷ qty. */
        val cost: Double = 0.0,
        /** How many of the two halves filled. A MACD turn buys a whole seat as one. */
        val halves: Int = 0,
        /** The first fill's price, and when and at what the second half joined —
         *  so the stop and target can be replayed on the position as it stood at
         *  each moment. firstPrice 0 = a trade from before stops and targets
         *  existed: it keeps the rule it was bought under, sold on time only. */
        val firstPrice: Double = 0.0,
        val secondSec: Long = 0L,
        val secondPrice: Double = 0.0,
        /** MACD turns: when the turn was seen, and the first bar that may fill it. */
        val signalSec: Long = 0L,
        val fromSec: Long = 0L,
        /** OPEN | SOLD | MISSED — missed means every seat was taken when the
         *  order filled: followed for the record, never counted in the money. */
        val status: String = "OPEN",
        val sellDay: String? = null,
        val sellPrice: Double = 0.0,
        val sellSec: Long = 0L,
        /** What sold it: stop | target | time | trend. */
        val exitReason: String = "",
        /** The stop and target in force now, from the average buying price. 0 = none. */
        val stop: Double = 0.0,
        val target: Double = 0.0,
        /** The day the morning check found the daily or weekly MACD no longer
         *  rising — sold at that day's close unless the stop or target comes first. */
        val trendDay: String? = null,
        val pnl: Double = 0.0,
        val lastPrice: Double = 0.0,
        val lastSec: Long = 0L,
        // What happened next, in percent from the buying price; null until known.
        val ret1: Double? = null,
        val ret3: Double? = null,
        val ret5: Double? = null,
        val dip: Double? = null,
        val done: Boolean = false,
        val checkedMs: Long = 0L
    ) {
        val entry: Double get() = if (qty > 0.0) cost / qty else 0.0
    }

    data class Algo(
        val id: String = "",
        val name: String = "",
        /** [KIND_FLOOR] or [KIND_MACD]. Algos from before there were two are floor. */
        val kind: String = KIND_FLOOR,
        val space: String = "de",
        val enabled: Boolean = true,
        val notify: Boolean = true,
        val createdAt: Long = 0L,
        val settings: Settings = Settings(),
        val universe: List<Member> = emptyList(),
        /** The day the universe was last taken from Discovery, and that scan's time. */
        val universeDay: String = "",
        val universeFrom: Long = 0L,
        /** The day [orders] belong to — and, for MACD turns, the day the
         *  universe's trend was read for. */
        val planDay: String = "",
        /** The last day the held stocks' daily and weekly trend was checked. */
        val trendCheckDay: String = "",
        /** The last plan day written to the journal. See [FloorJournalStore]. */
        val journalDay: String = "",
        /** Whether [planDay] was planned with the trend filter on. Plans saved
         *  before the filter became a switch were — hence the default. When this
         *  and the setting disagree, today is planned again. */
        val planFilter: Boolean = true,
        val orders: List<Order> = emptyList(),
        val trades: List<Trade> = emptyList(),
        val log: List<String> = emptyList(),
        val lastRunMs: Long = 0L,
        val lastNote: String = ""
    ) {
        /** Namespace form, so a notification can open this card like any algo's. */
        val ns: String get() = "${space}__$id"
    }

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
            Log.e(TAG, "floor algos unreadable — set aside, starting afresh", e)
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
        } catch (e: Exception) { Log.e(TAG, "floor algos save failed", e) }
    }

    /** Read, change and write back as one step. */
    @Synchronized
    fun mutate(ctx: Context, block: (State) -> State): State {
        val next = block(load(ctx))
        save(ctx, next.copy(algos = next.algos.map { tidy(it) }))
        return next
    }

    private fun tidy(a: Algo): Algo {
        // Closed and finished trades fall off the old end first; open ones never do.
        val trades = if (a.trades.size <= MAX_TRADES) a.trades else {
            val keep = a.trades.filter { it.status == "OPEN" || !it.done }
            val old = a.trades.filterNot { it.status == "OPEN" || !it.done }
            old.takeLast((MAX_TRADES - keep.size).coerceAtLeast(0)) + keep
        }
        return a.copy(trades = trades.sortedBy { it.firstSec }, log = a.log.takeLast(MAX_LOG))
    }

    fun list(ctx: Context, space: String): List<Algo> = load(ctx).algos.filter { it.space == space }

    fun get(ctx: Context, id: String): Algo? = load(ctx).algos.firstOrNull { it.id == id }

    /** Switched on — or paused but still holding, or still following what
     *  happened after a buy. */
    fun hasWork(a: Algo): Boolean = a.enabled || a.trades.any { it.status == "OPEN" || !it.done }

    fun anyRunning(ctx: Context): Boolean = load(ctx).algos.any { hasWork(it) }

    fun create(ctx: Context, space: String, name: String, kind: String = KIND_FLOOR): Algo {
        val k = if (kind == KIND_MACD) KIND_MACD else KIND_FLOOR
        val a = Algo(
            id = "f" + java.lang.Long.toString(System.currentTimeMillis() % 1_000_000_000L, 36),
            name = name.trim().ifBlank { if (k == KIND_MACD) "MACD turns" else "Floor orders" }.take(40),
            kind = k, space = space, createdAt = System.currentTimeMillis(),
            log = listOf(stamp() + " · created"))
        mutate(ctx) { it.copy(algos = it.algos + a) }
        return a
    }

    fun update(ctx: Context, id: String, block: (Algo) -> Algo): Algo? {
        var out: Algo? = null
        mutate(ctx) { s -> s.copy(algos = s.algos.map { if (it.id == id) block(it).also { n -> out = n } else it }) }
        return out
    }

    fun delete(ctx: Context, id: String) {
        mutate(ctx) { s -> s.copy(algos = s.algos.filterNot { it.id == id }) }
        FloorJournalStore.delete(ctx, id)
    }

    fun stamp(ms: Long = System.currentTimeMillis()): String =
        java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.UK).format(java.util.Date(ms))
}

package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

/**
 * THE FLOOR-ORDER ALGOS — paper only, built into the app rather than read from a
 * config file.
 *
 * The config-driven algos are a large family where the file picks among moves the
 * engine already knows. This strategy needs moves that engine does not have (an
 * order resting at a price, a fill only below it, orders that die at the close,
 * a monthly-and-daily trend filter), so it has its own engine and its own record.
 * Its few numbers are settings on the algo instead.
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

    data class Settings(
        /** Paper money for the whole algo, in the market's currency. */
        val capital: Double = 1000.0,
        /** Stocks held at once. Each seat is capital ÷ seats; each order half a seat. */
        val seats: Int = 5,
        /** Top of the Discovery list by return on capital. */
        val universeSize: Int = 30,
        /** The first order rests at the lowest price of this many trading days. */
        val lowDays: Int = 3,
        /** The second order rests this many percent below the first. */
        val secondPct: Double = 2.0,
        /** Sold at the close this many trading days after the buy. */
        val holdDays: Int = 3
    )

    /** A stock in the universe, and what the trend filter said on the plan day. */
    data class Member(
        val symbol: String = "",
        val name: String = "",
        val roce: Double? = null,
        /** Monthly and daily MACD slope: 1 rising, -1 falling, 0 flat, null unknown / not checked. */
        val month: Int? = null,
        val day: Int? = null,
        val note: String = ""
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
        /** When the first half filled — the dip is measured from here. */
        val firstSec: Long = 0L,
        val qty: Double = 0.0,
        /** Money in. The average buying price is cost ÷ qty. */
        val cost: Double = 0.0,
        /** How many of the two halves filled. */
        val halves: Int = 0,
        /** OPEN | SOLD | MISSED — missed means every seat was taken when the
         *  order filled: followed for the record, never counted in the money. */
        val status: String = "OPEN",
        val sellDay: String? = null,
        val sellPrice: Double = 0.0,
        val sellSec: Long = 0L,
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
        val space: String = "de",
        val enabled: Boolean = true,
        val notify: Boolean = true,
        val createdAt: Long = 0L,
        val settings: Settings = Settings(),
        val universe: List<Member> = emptyList(),
        /** The day the universe was last taken from Discovery, and that scan's time. */
        val universeDay: String = "",
        val universeFrom: Long = 0L,
        /** The day [orders] belong to. */
        val planDay: String = "",
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

    fun create(ctx: Context, space: String, name: String): Algo {
        val a = Algo(
            id = "f" + java.lang.Long.toString(System.currentTimeMillis() % 1_000_000_000L, 36),
            name = name.trim().ifBlank { "Floor orders" }.take(40),
            space = space, createdAt = System.currentTimeMillis(),
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
    }

    fun stamp(ms: Long = System.currentTimeMillis()): String =
        java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.UK).format(java.util.Date(ms))
}

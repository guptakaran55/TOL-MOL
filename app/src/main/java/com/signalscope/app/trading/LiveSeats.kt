package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.BinanceLiveStore
import com.signalscope.app.data.T212LiveStore
import com.signalscope.app.data.ZerodhaLiveStore
import java.io.File

// ════════════════════════════════════════════════════════════════════════════
// A LIVE SEAT GROWS WITH WHAT THE ALGO REALLY MADE (23 Oct 2026)
// ════════════════════════════════════════════════════════════════════════════
// Every backtest of a 3b algo compounds: a seat that turns 400 into 440 buys its
// next stock with 440. The live algos did not — every real order was the budget
// divided by the seats, every time, and the profit sat in the account as spare
// cash. Same trades, same percentages, but less and less money working than the
// simulation the algo was chosen on.
//
// So the seat now carries the algo's REAL result: its budget plus what its real
// sales made or lost, shared over its seats. From the broker's own figures, never
// the paper record — slippage, skipped names and failed buys make the two drift.
//
// TWO FENCES, the user's choice:
//   • never above TWICE the starting seat — a long winning run cannot quietly
//     put more money at risk than was meant;
//   • counted from the day it began (one date per algo, kept here), not from
//     the whole history: older sales include hand-sold and retired positions
//     whose sale amount was never read, and a guess must not size a real order.
// A sale whose amount is not read yet is left out until it is — the seat waits
// for a figure rather than using an estimate.
// ════════════════════════════════════════════════════════════════════════════

object LiveSeats {
    private const val TAG = "LiveSeats"
    private const val FILE = "live_seats.json"

    /** A seat never grows past this many times its starting money. */
    const val CEILING_X = 2.0

    /** Real sales counted since the start: their result, how many, and how many
     *  closed with no sale amount read yet (left out). */
    data class Realised(val pnl: Double = 0.0, val trades: Int = 0, val unread: Int = 0)

    /** One seat's money now. Pure — tested. */
    fun perSeat(budget: Double, seats: Int, realised: Double): Double {
        if (budget <= 0.0) return 0.0
        val n = seats.coerceAtLeast(1)
        return ((budget + realised) / n).coerceIn(0.0, budget / n * CEILING_X)
    }

    /** Trading 212, in the account's currency: what the sale brought in against
     *  what those shares cost. Pure — tested. */
    fun t212Realised(rows: List<T212LiveStore.LiveTrade>, ns: String, fromMs: Long): Realised {
        var pnl = 0.0; var n = 0; var unread = 0
        for (t in rows) {
            if (t.ns != ns || (t.status != "CLOSED" && t.status != "MANUAL_CLOSED")) continue
            val soldAt = listOf(t.sellAt, t.manualClosedAt, (t.exitTs ?: 0L) * 1000L).firstOrNull { it > 0L } ?: 0L
            if (soldAt < fromMs || t.liveEntryValue <= 0.0) continue
            val got = t.ownExitValue()
            if (got <= 0.0) { unread++; continue }
            // A sale that left shares behind is measured on the shares it sold.
            val share = if (t.liveQtyFilled > 0.0) (t.ownExitQty() / t.liveQtyFilled).coerceIn(0.0, 1.0) else 1.0
            pnl += got - t.liveEntryValue * share
            n++
        }
        return Realised(pnl, n, unread)
    }

    /** Binance, in the quote coin: the result the sale recorded. Pure — tested. */
    fun binanceRealised(rows: List<BinanceLiveStore.LiveTrade>, fromMs: Long): Realised {
        var pnl = 0.0; var n = 0; var unread = 0
        for (t in rows) {
            if (t.status != "CLOSED" && t.status != "PARTIAL") continue
            if (t.sellAt < fromMs || t.sellOrderId <= 0L) continue
            if ((t.sellQuoteQty.toDoubleOrNull() ?: 0.0) <= 0.0) { unread++; continue }
            pnl += t.livePnlQuote
            n++
        }
        return Realised(pnl, n, unread)
    }

    /** Zerodha, in rupees, before charges: sold against paid, whole shares. Pure — tested. */
    fun zerodhaRealised(rows: List<ZerodhaLiveStore.Row>, ns: String, fromMs: Long): Realised {
        var pnl = 0.0; var n = 0; var unread = 0
        for (t in rows) {
            if (t.ns != ns || (t.status != "CLOSED" && t.status != "MANUAL_CLOSED")) continue
            val soldAt = if (t.sellAt > 0L) t.sellAt else t.manualClosedAt
            if (soldAt < fromMs || t.qty <= 0) continue
            if (t.entryPrice <= 0.0 || t.exitPrice <= 0.0) { unread++; continue }
            pnl += (t.exitPrice - t.entryPrice) * t.qty
            n++
        }
        return Realised(pnl, n, unread)
    }

    // ── the seats as the screen draws them ───────────────────────────────────

    const val LIVE = "LIVE"         // real money in it now
    const val PAPER = "PAPER"       // held on paper — an algo with no real money
    const val WAITING = "WAITING"   // a company holds the seat, nothing bought yet
    const val EMPTY = "EMPTY"       // no company — the seat is cash

    /** [seat] −1 = a position held on no seat (its seat moved on while it sells). */
    data class Tile(val seat: Int, val symbol: String, val state: String,
                    val flagged: Boolean = false, val paperHeld: Boolean = false)

    /** A real position: what it cost and, when a price is known, what it is worth. */
    data class Holding(val cost: Double, val value: Double?, val qty: Double) {
        operator fun plus(o: Holding) = Holding(cost + o.cost,
            if (value != null && o.value != null) value + o.value else null, qty + o.qty)
    }

    /**
     * Which company sits in each seat and what is in it. [slots] is the company
     * per seat in order (null = cash); seats past its end are cash; a position on
     * no seat is shown after them. [paperCounts]: an algo with no real money, whose
     * paper positions are what it holds. Pure — tested.
     */
    fun tiles(slots: List<String?>, seats: Int, live: Set<String>,
              flagged: Set<String> = emptySet(), paperOpen: Set<String> = emptySet(),
              paperCounts: Boolean = false): List<Tile> {
        fun stateOf(s: String) = when {
            s in live -> LIVE
            paperCounts && s in paperOpen -> PAPER
            else -> WAITING
        }
        val out = ArrayList<Tile>()
        val seen = HashSet<String>()
        slots.forEachIndexed { i, s0 ->
            val s = s0?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            if (s == null) out.add(Tile(i, "", EMPTY))
            else {
                seen += s
                out.add(Tile(i, s, stateOf(s), s in flagged, s in paperOpen))
            }
        }
        while (out.size < seats) out.add(Tile(out.size, "", EMPTY))
        val held = if (paperCounts) live + paperOpen else live
        held.filter { it !in seen }.sorted().forEach { out.add(Tile(-1, it, stateOf(it), paperHeld = it in paperOpen)) }
        return out
    }

    // ── the start date, one per algo ─────────────────────────────────────────

    /** When this algo's seats began to grow; set the first time anyone asks. */
    @Synchronized
    fun startOf(ctx: Context, ns: String): Long {
        val f = File(ctx.filesDir, FILE)
        val o = try { org.json.JSONObject(if (f.exists()) f.readText() else "{}") } catch (e: Exception) { org.json.JSONObject() }
        val have = o.optLong(ns, 0L)
        if (have > 0L) return have
        val now = System.currentTimeMillis()
        try {
            o.put(ns, now)
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(o.toString())
            if (f.exists()) f.delete()
            tmp.renameTo(f)
            Log.i(TAG, "[$ns] seats grow from now")
        } catch (e: Exception) { Log.w(TAG, "[$ns] could not keep the start date: ${e.message}") }
        return now
    }

    fun realisedFor(ctx: Context, ns: String): Realised {
        val from = startOf(ctx, ns)
        return try {
            when (Spaces.baseOf(ns)) {
                "de", "us" -> t212Realised(T212LiveStore.load(ctx).trades, ns, from)
                "in" -> zerodhaRealised(ZerodhaLiveStore.load(ctx).trades, ns, from)
                "binance" -> binanceRealised(BinanceLiveStore.load(ctx, ns).trades, from)
                else -> Realised()
            }
        } catch (e: Exception) {
            // An unreadable ledger sizes like before — the starting seat.
            Log.w(TAG, "[$ns] realised result unreadable: ${e.message}")
            Realised()
        }
    }

    /** The money one real order gets now. */
    fun perSeatFor(ctx: Context, ns: String, budget: Double, seats: Int): Double =
        perSeat(budget, seats, realisedFor(ctx, ns).pnl)
}

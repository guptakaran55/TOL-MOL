package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

/**
 * State for the daily-MACD enrollment gate (Mode 3b, SCREENER_REINVEST_KOTLIN_PORT §10).
 *
 * The gate holds exactly N SLOTS. Each slot carries capital and either a symbol
 * or cash (symbol == null). Cash slots are deliberate — when fewer than N names
 * have a rising daily MACD, the empty slots sit OUT of the market; that is
 * where the downside protection comes from (§10.5). injected = notional × N,
 * cash slots included.
 *
 * PAPER-ONLY driver: the gate automates which symbols are enrolled in the
 * Strategy Lab. Real money remains behind its own gates (Binance allowlist,
 * T212 freeze) exactly as before.
 */
object MacdGateStore {
    private const val TAG = "MacdGateStore"
    private val gson = Gson()

    data class Slot(
        val symbol: String? = null,     // null = idle cash
        val capital: Double = 0.0,
        val since: Long = 0L,           // epoch ms of enrollment
        val negDays: Int = 0,           // consecutive non-positive slope days
        val flagged: Boolean = false    // slope ≤ 0 but mid-trade → waiting for natural exit
    )

    /**
     * ONE COMPLETED OR RUNNING SEAT OCCUPANCY — the live counterpart of the
     * replay's enrollments file.
     *
     * Until this existed the live gate kept only the CURRENT occupant of each
     * seat plus a rolling text log, so the question "was this trade inside its
     * own seat's enrollment span?" had no data to be asked against. That is the
     * invariant that catches an ORPHAN — a position whose seat was released
     * underneath it — and it was unanswerable by construction, not by a missing
     * export. Every release path now closes a span and every fill opens one, so
     * the join is available continuously rather than reconstructable from prose.
     *
     * [toMs] null means still running. [ejectReason] is machine-readable on
     * purpose: it is the column the "no seat freed while its position was open"
     * check groups by.
     */
    data class Enrollment(
        val seat: Int,
        val symbol: String,
        val fromMs: Long,
        val toMs: Long? = null,
        val rank: Int = 0,
        val score: Double? = null,
        val slopeAtEntry: Double? = null,
        val ejectReason: String? = null
    )

    data class State(
        val enabled: Boolean = false,
        val holdN: Int = 5,
        val notionalPerSlot: Double = 1000.0,
        val smoothing: Int = 5,         // EMA span on the daily-MACD derivative (2 for crypto)
        val confirmDays: Int = 1,       // consecutive non-positive days before ejecting (spec default 1)
        /**
         * Whether the screener's "pick the best N of the universe" step runs at all.
         *
         * 3b is three separable parts: the daily-MACD slope GATE (a timing filter,
         * indifferent to universe size), the screener SELECTION, and seat
         * COMPOUNDING. On a 2-coin crypto universe selection is close to vacuous —
         * it picks 1 of 2 — and measured on BTC+ETH 240/120 over 4y it does not pay
         * for itself: gate-only returns +29.0%/yr at 23.0% MaxDD (Calmar 1.26)
         * against gate+selection's +33.9%/yr at 27.1% (Calmar 1.25). More return,
         * proportionally more drawdown, no risk-adjusted gain.
         *
         * When false, every symbol gets its own seat (see [effectiveHoldN]) so the
         * ranking is never consulted; the gate and compounding still run.
         */
        val useScreenerSelection: Boolean = true,
        val slots: List<Slot> = emptyList(),
        val injected: Double = 0.0,
        val lastEvalDay: String = "",   // UTC calendar day of the last evaluation ("2026-07-22")
        // Cached from the last evaluation so a trade-close can migrate capital
        // one-for-one into a replacement WITHOUT re-fetching the whole universe.
        val lastRanking: List<String> = emptyList(),
        val lastSlopes: Map<String, Double> = emptyMap(),
        // THE SCORE BEHIND THE RANK. Only the ORDER used to be kept, so a rotation
        // could be observed but never explained: #2 beat #6 for reasons that had
        // already been discarded. Nullable on purpose — Gson bypasses constructors,
        // so a default would never apply to a state file written before this field
        // existed, and the map would arrive as null regardless of what is declared.
        val lastScores: Map<String, Double>? = null,
        /** When the ranking was computed. The card shows a snapshot, and without a
         *  stamp it looks like it disagrees with a screen run seconds ago. */
        val lastRankedAt: Long = 0L,
        /** Seat-occupancy history. Nullable because Gson bypasses constructors —
         *  a default would never apply to a state file written before this field
         *  existed, and the list would arrive as null whatever is declared. */
        val enrollments: List<Enrollment>? = null,
        val log: List<String> = emptyList()
    )

    private fun file(ctx: Context, ns: String): File =
        File(ctx.filesDir, "macd_gate_${ns}.json")

    fun load(ctx: Context, ns: String): State {
        return try {
            val f = file(ctx, ns)
            if (!f.exists()) defaultsFor(ns)
            else gson.fromJson(f.readText(), State::class.java) ?: defaultsFor(ns)
        } catch (e: Exception) {
            Log.w(TAG, "load($ns) failed: ${e.message}"); defaultsFor(ns)
        }
    }

    fun save(ctx: Context, ns: String, state: State) {
        try { file(ctx, ns).writeText(gson.toJson(state)) }
        catch (e: Exception) { Log.e(TAG, "save($ns) failed", e) }
    }

    @Synchronized
    fun mutate(ctx: Context, ns: String, block: (State) -> State): State {
        val next = block(load(ctx, ns))
        save(ctx, ns, next)
        return next
    }

    /** Spec §12.2 defaults: crypto hold-1 / smoothing-2, stocks hold-5 / smoothing-5.
     *  Crypto additionally defaults selection OFF — with 2 coins, "top 1 of 2" is a
     *  claim the strategy cannot really support (see [State.useScreenerSelection]). */
    fun defaultsFor(ns: String): State =
        if (com.signalscope.app.trading.Spaces.baseOf(ns) == "binance")
            State(holdN = 1, smoothing = 2, useScreenerSelection = false)
        else State(holdN = 5, smoothing = 5)

    /**
     * Seats to run. With selection ON this is the configured [State.holdN]. With it
     * OFF every symbol gets a seat, so the ranking never decides anything and the
     * gate alone governs entry/exit per name.
     *
     * The LIVE path keeps total exposure fixed — bucketConfigure re-derives
     * notionalPerSlot as capital ÷ seats, so more seats means smaller positions,
     * not more money at risk. Only a backtest driven by a FIXED per-seat notional
     * sees `injected` scale with the seat count.
     */
    fun effectiveHoldN(s: State, universeSize: Int): Int =
        if (s.useScreenerSelection) s.holdN.coerceAtLeast(1)
        else universeSize.coerceAtLeast(1)

    fun totalValue(s: State): Double = s.slots.sumOf { it.capital }

    fun appendLog(s: State, line: String, max: Int = 120): State =
        s.copy(log = (s.log + line).takeLast(max))

    // ── Enrollment spans ────────────────────────────────────────────────────
    // Bounded, because the state file is rewritten on every mutation and an
    // unbounded trail on a 106-name algo would grow without limit.
    //
    // THE CAP IS A CORRECTNESS HAZARD, NOT JUST A SIZE ONE. The orphan test is
    // "this trade has no covering span". Once the oldest spans are evicted, the
    // trades that belonged to them look identical to genuine orphans, so the
    // safety net would degrade into false alarms exactly as history accumulates
    // — and German seats rotate fast enough to reach the old 400 in weeks.
    // Two defences: a cap high enough that eviction is rare, and an exported
    // oldest-retained-span stamp so anything older is reported UNKNOWN rather
    // than accused. See [StrategyLabRunner.liveLedgerCsv].
    private const val MAX_ENROLLMENTS = 2000

    /** Start of the oldest span still retained; 0 when there is no history.
     *  Trades entered before this cannot be judged — their span may simply have
     *  been evicted — so the export must not call them orphans. */
    fun oldestSpanStart(s: State): Long =
        s.enrollments?.minOfOrNull { it.fromMs } ?: 0L

    /** A seat took a name. Any span still open on that seat is closed first —
     *  two running spans on one seat is exactly what invariant 8 forbids, and
     *  tolerating it here would let a missed close corrupt every later join. */
    fun openEnrollment(s: State, seat: Int, symbol: String, rank: Int,
                       score: Double?, slope: Double?, atMs: Long): State {
        val cur = s.enrollments ?: emptyList()
        val closed = cur.map {
            if (it.seat == seat && it.toMs == null)
                it.copy(toMs = atMs, ejectReason = it.ejectReason ?: "superseded")
            else it
        }
        return s.copy(enrollments = (closed + Enrollment(
            seat = seat, symbol = symbol.uppercase(), fromMs = atMs,
            rank = rank, score = score, slopeAtEntry = slope
        )).takeLast(MAX_ENROLLMENTS))
    }

    /** A seat gave a name up. [reason] is the machine-readable cause, e.g.
     *  slope_negative / natural_close / sold_by_hand / seat_rebuild / paper_reset. */
    fun closeEnrollment(s: State, seat: Int, symbol: String,
                        reason: String, atMs: Long): State {
        val cur = s.enrollments ?: return s
        var hit = false
        val next = cur.map {
            if (!hit && it.seat == seat && it.toMs == null &&
                it.symbol.equals(symbol, true)) {
                hit = true; it.copy(toMs = atMs, ejectReason = reason)
            } else it
        }
        return if (hit) s.copy(enrollments = next) else s
    }

    /** Every running span ends at once — the bulk seat rebuilds. Without this a
     *  rebuild would leave spans that never end, and every trade after it would
     *  appear to sit inside an enrollment that had in fact been torn down. */
    fun closeAllEnrollments(s: State, reason: String, atMs: Long): State {
        val cur = s.enrollments ?: return s
        if (cur.none { it.toMs == null }) return s
        return s.copy(enrollments = cur.map {
            if (it.toMs == null) it.copy(toMs = atMs, ejectReason = reason) else it
        })
    }
}

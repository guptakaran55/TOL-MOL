package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.MacdGateStore
import com.signalscope.app.data.StrategyLabStore
import com.signalscope.app.screener.DailyBar
import com.signalscope.app.screener.ScreenerConfig
import com.signalscope.app.screener.ScreenerRunner
import com.signalscope.app.screener.StockScreener
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Daily-MACD enrollment gate — Mode 3b ("flag, exit at natural close") from
 * SCREENER_REINVEST_KOTLIN_PORT §10.7, the recommended mode (§12: best mean
 * rank 1.80; 3a wins on zero datasets once accounting is honest).
 *
 * One idea: hold a name ONLY while its own daily MACD slope is positive.
 *   slope[i] = EMA( diff(EMA(close,12) − EMA(close,26)), smoothing )
 * When the slope turns ≤ 0 the name is FLAGGED. A flagged name mid-trade keeps
 * its slot until the engine's own exit closes the position (nothing is ever
 * sold mid-position — that is 3b's structural advantage: zero forced
 * liquidations, every trade credited at its real return). A flagged name that
 * is FLAT ejects immediately (else it squats on capital through a downtrend).
 * Freed capital migrates ONE-FOR-ONE into the highest-ranked eligible
 * replacement; with none eligible the slot sits in cash.
 *
 * Evaluation cadence: once per UTC calendar day (the input — daily bars — only
 * changes once a day; §10.8). No-lookahead: only bars strictly BEFORE today
 * are used, so an in-progress session can never leak into the slope.
 *
 * "Flagged" is implemented with the reinvest layer's existing windingDown
 * (exit-only) mechanism: StrategyLabRunner already refuses NEW entries for
 * winding-down symbols while letting the open position ride to its natural
 * algo exit — exactly 3b's contract.
 */
object MacdGateEngine {
    private const val TAG = "MacdGateEngine"

    // Same 20-pair universe as the dashboard's crypto screener default.
    private val BN_UNIVERSE = listOf(
        "BTCUSDC","ETHUSDC","BNBUSDC","SOLUSDC","XRPUSDC","ADAUSDC","DOGEUSDC",
        "AVAXUSDC","LINKUSDC","DOTUSDC","LTCUSDC","TRXUSDC","ATOMUSDC","NEARUSDC",
        "APTUSDC","ARBUSDC","OPUSDC","FILUSDC","ICPUSDC","UNIUSDC")

    // A BUCKET gate ranks/gates only within the bucket's OWN symbol group (its
    // candidates to rotate between); a bare-space gate uses the full universe.
    private fun universeFor(ctx: Context, ns: String): List<String> {
        if (Spaces.isBucket(ns)) {
            val syms = com.signalscope.app.data.BucketStore.get(ctx, ns)?.symbols ?: emptyList()
            if (syms.isNotEmpty()) return syms
        }
        return when (Spaces.baseOf(ns)) {
            "binance" -> BN_UNIVERSE
            "de" -> com.signalscope.app.service.deScreenedUniverse(ctx).map { it.removeSuffix(".DE") }
            else -> com.signalscope.app.service.NASDAQ_100_SYMBOLS
        }
    }

    private fun spaceFor(ns: String): String = Spaces.dataSpaceOf(ns)
    private fun exchangeFor(ns: String): String = Spaces.exchangeOf(ns)

    // ── Slope math (§10.2) ──────────────────────────────────────────────────
    // EMA recipe matches StrategyLabEngine exactly: α = 2/(span+1), seeded with
    // the first value, span clamped <2→2. The clamp is WHY the spec's parity
    // check says smoothing 1 and 2 must produce identical output.
    private fun ema(x: DoubleArray, span: Int): DoubleArray {
        val s = if (span < 2) 2 else span
        val a = 2.0 / (s + 1.0)
        val out = DoubleArray(x.size)
        if (x.isEmpty()) return out
        out[0] = x[0]
        for (i in 1 until x.size) out[i] = a * x[i] + (1 - a) * out[i - 1]
        return out
    }

    /** Latest smoothed daily-MACD slope for a close series, or null if too short. */
    fun latestSlope(closes: DoubleArray, smoothing: Int): Double? {
        if (closes.size < 30) return null
        val e12 = ema(closes, 12); val e26 = ema(closes, 26)
        val macd = DoubleArray(closes.size) { e12[it] - e26[it] }
        val raw = DoubleArray(closes.size)
        raw[0] = 0.0
        for (i in 1 until macd.size) raw[i] = macd[i] - macd[i - 1]
        return ema(raw, smoothing).last()
    }

    /** Does [sym] currently have an OPEN paper trade in the lab ledger? */
    private fun inPosition(ctx: Context, ns: String, sym: String): Boolean =
        StrategyLabStore.load(ctx, ns).trades.any {
            it.symbol.equals(sym, true) && it.status == "OPEN"
        }

    private fun setWindingDown(ctx: Context, ns: String, sym: String, on: Boolean) {
        try {
            com.signalscope.app.data.ReinvestStore.mutate(ctx, ns) { s ->
                val cur = (s.windingDown ?: emptyList()).map { it.uppercase() }.toMutableSet()
                if (on) cur.add(sym.uppercase()) else cur.remove(sym.uppercase())
                s.copy(windingDown = cur.toList())
            }
        } catch (e: Exception) { Log.w(TAG, "windingDown($sym,$on) failed", e) }
    }

    private fun today(): String = LocalDate.now(ZoneOffset.UTC).toString()

    /** Progress sink for the daily evaluation, so the UI can show a real
     *  determinate overlay instead of appearing frozen: the universe fetch is
     *  one DAILY series per candidate (~1s each — minutes for 106 names).
     *  Installed once by MainActivity; null when headless. */
    @Volatile
    var progressSink: ((ns: String, phase: String, done: Int, total: Int) -> Unit)? = null

    private fun progress(ns: String, phase: String, done: Int, total: Int) {
        try { progressSink?.invoke(ns, phase, done, total) } catch (_: Exception) {}
    }

    // ── Seat/roster reconciliation ──────────────────────────────────────────
    /** What one reconciliation pass did — surfaced on the card, not just logged. */
    data class Reconcile(
        val reattached: List<String> = emptyList(),
        val swept: List<String> = emptyList(),
        val orphans: List<String> = emptyList()
    )

    /**
     * REPAIR THE SEAT ↔ ROSTER RELATIONSHIP BEFORE THE GATE ACTS.
     *
     * 3b's defining rule is that a seat is never released while its name holds an
     * open position. Every release path honours it. What no path honoured was the
     * SECOND half of the same promise: a name must not still be armed for entry
     * once its seat is gone. The bulk seat rebuilds — a settings change, a paper
     * reset — wipe all seats to cash but leave every dropped name on the watch
     * roster, and the roster is what the runner actually trades. A name that is
     * flat at rebuild time therefore survives as a live candidate with no seat
     * behind it, and can open a position days later. That is an ORPHAN: shares in
     * one place, an empty pocket in another, and no error anywhere.
     *
     * Sizing is what makes it invisible. A live order is sized as live budget ÷
     * seats, a flat figure that never consults the seat — so nothing in the entry
     * path is in a position to notice that no seat exists.
     *
     * Two repairs, in this order, and the order matters:
     *
     *  1. RE-ATTACH an unseated open position to an empty seat. Not merely
     *     tolerated — bound. An orphan that closes unseated has its P&L booked but
     *     never compounded, so it neither sizes the next position nor moves the
     *     value-vs-injected figure. That is silent capital leakage, and letting it
     *     run to its exit does not fix it. Re-attaching does.
     *  2. SWEEP the roster of names no seat owns and no position holds. Safe only
     *     in that order: sweeping first would drop the very name we want to seat,
     *     and unenrolling deletes open positions.
     *
     * Runs BEFORE the daily evaluation so a reclaimed seat is not handed to a
     * fresh pick first. A re-attached name comes back FLAGGED (exit-only): it lost
     * its seat once, so it rides its position out and is not re-armed until an
     * evaluation confirms its slope is still positive.
     */
    fun reconcileSeats(ctx: Context, ns: String): Reconcile {
        val st0 = MacdGateStore.load(ctx, ns)
        // Gate off ⇒ the user owns the roster by hand; sweeping it would delete a
        // basket they curated. Enrollment is only the gate's to manage while armed.
        if (!st0.enabled || st0.slots.isEmpty()) return Reconcile()

        val lab = try { StrategyLabStore.load(ctx, ns) } catch (e: Exception) {
            Log.w(TAG, "[$ns] reconcile: ledger unreadable", e); return Reconcile()
        }
        val openSyms = lab.trades.filter { it.status == "OPEN" }
            .map { it.symbol.uppercase() }.toSet()

        var st = st0
        val slots = st.slots.toMutableList()
        val seated = slots.mapNotNull { it.symbol?.uppercase() }.toMutableSet()
        val reattached = ArrayList<String>()
        val orphans = ArrayList<String>()
        var changed = false

        // 1. RE-ATTACH.
        // WHICH SEAT, when more than one is free — a written rule, not whichever
        // the iterator reached first. Seat identity is the unit of compounding, so
        // the choice moves money: the same trades landing on different seats
        // compound differently, and the Python lab measured a ~50 percentage-point
        // spread from seat assignment alone. Deterministic order:
        //   1. the seat this name last occupied, if it is free — the position
        //      resumes the balance its own history built, which is the only
        //      choice that keeps a re-attach neutral rather than a reshuffle;
        //   2. otherwise the lowest-numbered free seat.
        // Iterating openSyms in sorted order keeps step 2 stable too, so the same
        // starting state always produces the same assignment.
        for (sym in openSyms.sorted()) {
            if (sym in seated) continue
            val lastSeat = (st.enrollments ?: emptyList())
                .lastOrNull { it.symbol.equals(sym, true) }?.seat
            val free = lastSeat?.takeIf { it in slots.indices && slots[it].symbol == null }
                ?: slots.indexOfFirst { it.symbol == null }
            if (free < 0) {
                // Nowhere to put it. Keep it exit-only so it cannot compound the
                // problem by re-entering, and report it — this is the one case the
                // card must show, because it cannot be repaired automatically.
                orphans.add(sym)
                setWindingDown(ctx, ns, sym, true)
                continue
            }
            val now = System.currentTimeMillis()
            slots[free] = MacdGateStore.Slot(sym, slots[free].capital, now, 0, true)
            seated.add(sym)
            reattached.add(sym)
            changed = true
            setWindingDown(ctx, ns, sym, true)
            st = MacdGateStore.openEnrollment(st, free, sym,
                st.lastRanking.indexOfFirst { it.equals(sym, true) } + 1,
                st.lastScores?.entries?.firstOrNull { it.key.equals(sym, true) }?.value,
                st.lastSlopes.entries.firstOrNull { it.key.equals(sym, true) }?.value, now)
            st = MacdGateStore.appendLog(st,
                "${today()} $sym held shares with no seat — re-attached to seat ${free + 1} " +
                    "(exit-only until the next evaluation)")
            Log.w(TAG, "[$ns] re-attached orphan $sym to seat ${free + 1}")
        }

        // 2. SWEEP.
        val swept = ArrayList<String>()
        for (en in lab.enrolled) {
            val u = en.symbol.uppercase()
            if (u in seated || u in openSyms) continue
            try { StrategyLabStore.unenroll(ctx, u, ns); swept.add(u) }
            catch (e: Exception) { Log.w(TAG, "[$ns] sweep $u failed", e) }
        }
        if (swept.isNotEmpty()) {
            st = MacdGateStore.appendLog(st,
                "${today()} roster swept — ${swept.size} name${if (swept.size == 1) "" else "s"} " +
                    "with no seat dropped (${swept.take(6).joinToString(", ")}" +
                    (if (swept.size > 6) ", …" else "") + ")")
            changed = true
            Log.i(TAG, "[$ns] swept ${swept.size} unseated name(s)")
        }
        if (orphans.isNotEmpty()) {
            st = MacdGateStore.appendLog(st,
                "${today()} ⚠ ${orphans.joinToString(", ")} hold shares with no seat and every " +
                    "seat is taken — P&L will NOT compound until a seat frees")
            changed = true
        }

        if (changed || reattached.isNotEmpty())
            MacdGateStore.save(ctx, ns, st.copy(slots = slots))
        return Reconcile(reattached, swept, orphans)
    }

    // ── Daily evaluation (§10.7 steps EJECT + FILL) ─────────────────────────
    /** Runs at most once per UTC day. Called at the start of every runner pass;
     *  cheap no-op on all but the first pass of the day. */
    /**
     * COLLAPSE SEATS THAT NO LONGER CORRESPOND TO ANYTHING.
     *
     * With ranking off, the seat count IS the universe size — one per name. Shrink
     * the symbol list and the surplus seats are left behind holding cash, and the
     * headline return is then divided by a capital base the user never chose. A
     * one-coin algo showing two seats of €250 is reporting its performance against
     * twice the money it can actually deploy.
     *
     * ONLY EVER COLLAPSES EMPTY ONES, and the capital is CARRIED, never discarded:
     * the money in a retired seat is real and belongs to the algo. Dropping it
     * would silently book a loss that never happened.
     *
     * Never touches a seat holding a position — that is the reshape the save path
     * already refuses, and for the same reason.
     */
    private fun collapseSurplusSeats(ctx: Context, ns: String, universeSize: Int): Boolean {
        val st = MacdGateStore.load(ctx, ns)
        if (!st.enabled || st.slots.isEmpty()) return false
        val want = MacdGateStore.effectiveHoldN(st, universeSize.coerceAtLeast(1))
        if (st.slots.size <= want) return false
        val held = st.slots.count { it.symbol != null }
        if (held > want) return false          // cannot shrink past what is invested
        // Keep the held ones first, then whichever empties are needed to reach want.
        val keep = (st.slots.filter { it.symbol != null } +
                    st.slots.filter { it.symbol == null }).take(want)
        val retiredCash = st.slots.sumOf { it.capital } - keep.sumOf { it.capital }
        if (keep.isEmpty()) return false
        val merged = keep.toMutableList()
        merged[merged.size - 1] = merged.last().copy(capital = merged.last().capital + retiredCash)
        val note = "${today()} seats ${st.slots.size} → $want (universe is $universeSize " +
            "with ranking off) — ${"%.2f".format(retiredCash)} carried into the remaining seat"
        MacdGateStore.save(ctx, ns,
            MacdGateStore.appendLog(st.copy(slots = merged), note))
        Log.w(TAG, "[$ns] $note")
        return true
    }

    fun evaluate(ctx: Context, ns: String) {
        val st0 = MacdGateStore.load(ctx, ns)
        if (!st0.enabled) return
        // Done BEFORE the day guard, so a stale seat layout is corrected even on a
        // day the evaluation itself has already run — otherwise a universe shrunk
        // this morning keeps its surplus seat until tomorrow.
        try {
            if (collapseSurplusSeats(ctx, ns, universeFor(ctx, ns).size)) {
                // Re-read: the layout just changed underneath us.
                if (MacdGateStore.load(ctx, ns).lastEvalDay == today()) return
            }
        } catch (e: Exception) { Log.w(TAG, "[$ns] seat collapse failed", e) }
        if (st0.lastEvalDay == today()) return
        Log.i(TAG, "[$ns] daily gate evaluation starting")

        // 1. Fetch the universe's daily bars ONCE (adjusted closes for stocks).
        val universeSyms = universeFor(ctx, ns)
        progress(ns, "start", 0, universeSyms.size)
        val (universe, _) = ScreenerRunner.fetchUniverseBars(spaceFor(ns), universeSyms) { d, t ->
            progress(ns, "fetch", d, t)
        }
        if (universe.isEmpty()) {
            // Record WHY in the gate's own log — a silent return here looked
            // exactly like "Save & run does nothing" with no error anywhere.
            Log.w(TAG, "[$ns] no universe data — skipping eval")
            MacdGateStore.save(ctx, ns, MacdGateStore.appendLog(st0,
                "${today()} evaluation SKIPPED — no daily data returned for the universe"))
            progress(ns, "done", 0, 0)
            return
        }

        // NO-LOOKAHEAD: drop any bar from today (an in-progress session).
        val cutoff = LocalDate.now(ZoneOffset.UTC)
        val complete = LinkedHashMap<String, List<DailyBar>>()
        for ((sym, bars) in universe) {
            val b = bars.filter { it.date.isBefore(cutoff) }
            if (b.size >= 30) complete[sym] = b
        }
        if (complete.isEmpty()) { progress(ns, "done", 0, 0); return }
        val asOf = complete.values.flatten().maxOfOrNull { it.date }
            ?: run { progress(ns, "done", 0, 0); return }

        // 2. Rank (Part A) + slope per symbol from the SAME bars.
        // §10 — screenerLookbackDays from the config when present (config > default),
        // so a parity run is reproducible from the JSON alone.
        progress(ns, "rank", complete.size, complete.size)
        val scrCfg = StrategyLabStore.activeConfig(ctx, ns).screenerLookbackOverride
            ?.let { ScreenerConfig(lookbackDays = it.toLong()) } ?: ScreenerConfig()
        val rankedFull = StockScreener.screen(scrCfg, complete, asOf)
        val ranked = rankedFull.map { it.symbol }
        // Keep the SCORE, not just the order. It is what decided the rotation, and
        // discarding it left the card able to show that #2 beat #6 without being
        // able to say by how much or why.
        val scores = rankedFull.associate { it.symbol to it.score }
        val slopes = HashMap<String, Double>()
        for ((sym, bars) in complete) {
            latestSlope(DoubleArray(bars.size) { bars[it].close }, st0.smoothing)
                ?.let { slopes[sym] = it }
        }
        fun eligible(sym: String) = (slopes[sym] ?: -1.0) > 0.0

        var st = st0.copy(lastRanking = ranked, lastSlopes = slopes, lastScores = scores,
            lastRankedAt = System.currentTimeMillis(), lastEvalDay = today())
        val slots = st.slots.toMutableList()
        val held = slots.mapNotNull { it.symbol?.uppercase() }.toMutableSet()

        // 3. EJECT / FLAG pass over held slots.
        for (i in slots.indices) {
            val sym = slots[i].symbol ?: continue
            if (eligible(sym)) {
                if (slots[i].flagged) {
                    setWindingDown(ctx, ns, sym, false)
                    st = MacdGateStore.appendLog(st, "${today()} $sym slope recovered — unflagged")
                }
                slots[i] = slots[i].copy(negDays = 0, flagged = false)
                continue
            }
            val negDays = slots[i].negDays + 1
            if (inPosition(ctx, ns, sym)) {
                // 3b: mid-trade — flag (exit-only) and WAIT for the natural close.
                if (!slots[i].flagged) {
                    setWindingDown(ctx, ns, sym, true)
                    st = MacdGateStore.appendLog(st, "${today()} $sym slope ≤0 mid-trade — flagged (exit-only)")
                }
                slots[i] = slots[i].copy(negDays = negDays, flagged = true)
            } else if (negDays >= st.confirmDays) {
                // Flat + non-positive slope → eject now; capital stays in the slot.
                setWindingDown(ctx, ns, sym, false)
                try { StrategyLabStore.unenroll(ctx, sym, ns) } catch (e: Exception) { Log.w(TAG, "unenroll $sym", e) }
                held.remove(sym.uppercase())
                st = MacdGateStore.closeEnrollment(st, i, sym, "slope_negative", System.currentTimeMillis())
                slots[i] = MacdGateStore.Slot(null, slots[i].capital, System.currentTimeMillis(), 0, false)
                st = MacdGateStore.appendLog(st, "${today()} $sym ejected flat (slope ≤0) — slot to cash")
            } else {
                slots[i] = slots[i].copy(negDays = negDays)
            }
        }

        // 4. FILL empty slots: highest screener rank with a positive slope,
        //    never a name already held. None eligible → the slot stays cash.
        for (i in slots.indices) {
            if (slots[i].symbol != null) continue
            val pick = ranked.firstOrNull { it.uppercase() !in held && eligible(it) } ?: continue
            val now = System.currentTimeMillis()
            slots[i] = MacdGateStore.Slot(pick, slots[i].capital, now, 0, false)
            held.add(pick.uppercase())
            try { StrategyLabStore.enroll(ctx, pick, exchangeFor(ns), ns) }
            catch (e: Exception) { Log.w(TAG, "enroll $pick", e) }
            st = MacdGateStore.openEnrollment(st, i, pick, ranked.indexOf(pick) + 1,
                scores[pick], slopes[pick], now)
            st = MacdGateStore.appendLog(st, "${today()} $pick enrolled (rank #${ranked.indexOf(pick) + 1}, slope>0)")
        }

        MacdGateStore.save(ctx, ns, st.copy(slots = slots))
        Log.i(TAG, "[$ns] gate eval done — ${slots.count { it.symbol != null }}/${slots.size} slots filled")
        progress(ns, "done", 0, 0)
    }

    /**
     * FILL EMPTY SEATS NOW, from the ranking already in hand.
     *
     * Seats used to be assigned only inside [evaluate], which runs once per UTC
     * day and refuses to run twice. So a seat freed after it had run — by a name
     * ejected for a flat slope, or by you selling one by hand — sat as cash until
     * the next day rolled over. On a five-seat algo that is a fifth of the capital
     * idle for up to twenty-four hours, and the log shows it happening most days.
     *
     * COSTS NOTHING AND DECIDES NOTHING NEW. It re-uses the stored ranking, the
     * stored slopes and the same three rules the daily fill applies — highest
     * rank, slope above zero, not already held. No screen, no fetch, no new
     * opinion: it is the same answer that pass already computed, applied to a
     * seat that emptied afterwards.
     *
     * IT DOES NOT BUY. Seating a name enrols it; the strategy still has to fire
     * its own entry on a closed bar before any order exists. So this shortens the
     * queue, it does not pull a trade forward.
     *
     * REFUSES A STALE RANKING. A ranking is a fact about a particular day, and
     * seating from one two days old would be acting on evidence the app itself
     * would no longer stand behind — exactly the thing waiting for the next
     * evaluation was protecting against.
     *
     * @return how many seats were filled.
     */
    fun refillFreeSeats(ctx: Context, ns: String): Int {
        val st0 = MacdGateStore.load(ctx, ns)
        if (!st0.enabled) return 0
        if (st0.slots.none { it.symbol == null }) return 0
        if (st0.lastRanking.isEmpty()) return 0
        // Today's ranking, or yesterday's — the latter because the evaluation runs
        // on the UTC day and a European evening sits in the previous one, which is
        // precisely when seats free.
        val fresh = st0.lastEvalDay == today() ||
            st0.lastEvalDay == LocalDate.now(ZoneOffset.UTC).minusDays(1).toString()
        if (!fresh) return 0

        var st = st0
        val slots = st.slots.toMutableList()
        val held = slots.mapNotNull { it.symbol?.uppercase() }.toMutableSet()
        // Names this algo let go TODAY are not candidates to be re-seated today.
        // Without this a name ejected for a flat slope, or one you sold by hand,
        // would be handed straight back into the seat it just left — which reads
        // as the app arguing with your decision.
        val releasedToday = st.log.filter { it.startsWith(today()) }
            .mapNotNull { line ->
                Regex("""^\S+\s+(\S+)\s+(?:ejected flat|sold by hand)""").find(line)?.groupValues?.get(1)
            }.map { it.uppercase() }.toSet()

        var filled = 0
        for (i in slots.indices) {
            if (slots[i].symbol != null) continue
            val pick = st.lastRanking.firstOrNull {
                val u = it.uppercase()
                u !in held && u !in releasedToday && (st.lastSlopes[it] ?: -1.0) > 0.0
            } ?: continue
            val now = System.currentTimeMillis()
            slots[i] = MacdGateStore.Slot(pick, slots[i].capital, now, 0, false)
            held.add(pick.uppercase())
            try { StrategyLabStore.enroll(ctx, pick, exchangeFor(ns), ns) }
            catch (e: Exception) { Log.w(TAG, "enroll $pick", e) }
            st = MacdGateStore.openEnrollment(st, i, pick, st.lastRanking.indexOf(pick) + 1,
                st.lastScores?.get(pick), st.lastSlopes[pick], now)
            st = MacdGateStore.appendLog(st, "${today()} $pick took the freed seat " +
                "(rank #${st.lastRanking.indexOf(pick) + 1}, slope>0) — no waiting for the next evaluation")
            filled++
        }
        if (filled > 0) {
            MacdGateStore.save(ctx, ns, st.copy(slots = slots))
            Log.i(TAG, "[$ns] refilled $filled freed seat(s) from the stored ranking")
        }
        return filled
    }

    // ── Trade-close hook (§10.8 live translation) ───────────────────────────
    /** Compound the slot's capital by the trade's net return; if the symbol was
     *  flagged and is now flat with the slope still ≤0, release the slot and
     *  migrate its capital one-for-one into the best cached-eligible name. */
    fun onTradeClosed(ctx: Context, ns: String, symbol: String, netPctReturn: Double) {
        val st0 = MacdGateStore.load(ctx, ns)
        if (!st0.enabled) return
        val idx = st0.slots.indexOfFirst { it.symbol?.equals(symbol, true) == true }
        if (idx < 0) {
            // The trade closed but no seat owns the name, so nothing compounds and
            // the seat balances silently understate the loss (or gain). This is
            // legitimate — a name can be traded before the gate seats it, or after
            // an eject — but it was invisible, which made a seat showing one loss
            // after two closed trades look like an arithmetic bug. Say it happened.
            MacdGateStore.save(ctx, ns, MacdGateStore.appendLog(st0,
                "${today()} $symbol closed ${"%+.2f".format(netPctReturn)}% — no seat held it, not compounded"))
            return
        }

        var st = st0
        val slots = st.slots.toMutableList()
        val compounded = slots[idx].capital * (1.0 + netPctReturn / 100.0)
        slots[idx] = slots[idx].copy(capital = compounded)
        st = MacdGateStore.appendLog(st,
            "${today()} $symbol trade closed ${"%+.2f".format(netPctReturn)}% → slot ${"%.2f".format(compounded)}")

        val slope = st.lastSlopes[symbol.uppercase()] ?: st.lastSlopes[symbol]
        if (slots[idx].flagged && (slope == null || slope <= 0.0) && !inPosition(ctx, ns, symbol)) {
            // Natural close arrived and the slope is still non-positive → leave.
            setWindingDown(ctx, ns, symbol, false)
            try { StrategyLabStore.unenroll(ctx, symbol, ns) } catch (e: Exception) { Log.w(TAG, "unenroll $symbol", e) }
            val held = slots.mapNotNull { it.symbol?.uppercase() }.toMutableSet()
            held.remove(symbol.uppercase())
            val replacement = st.lastRanking.firstOrNull {
                it.uppercase() !in held && (st.lastSlopes[it] ?: -1.0) > 0.0
            }
            val now = System.currentTimeMillis()
            st = MacdGateStore.closeEnrollment(st, idx, symbol, "natural_close", now)
            slots[idx] = MacdGateStore.Slot(replacement, compounded, now, 0, false)
            if (replacement != null) {
                try { StrategyLabStore.enroll(ctx, replacement, exchangeFor(ns), ns) }
                catch (e: Exception) { Log.w(TAG, "enroll $replacement", e) }
                st = MacdGateStore.openEnrollment(st, idx, replacement,
                    st.lastRanking.indexOf(replacement) + 1,
                    st.lastScores?.get(replacement), st.lastSlopes[replacement], now)
                st = MacdGateStore.appendLog(st, "${today()} $symbol left at natural close → $replacement takes the slot")
            } else {
                st = MacdGateStore.appendLog(st, "${today()} $symbol left at natural close → slot to cash (none eligible)")
            }
        } else if (slots[idx].flagged && slope != null && slope > 0.0) {
            // Slope recovered before the close — the name simply stays (§10.7).
            setWindingDown(ctx, ns, symbol, false)
            slots[idx] = slots[idx].copy(flagged = false, negDays = 0)
            st = MacdGateStore.appendLog(st, "${today()} $symbol slope recovered at close — stays")
        }

        MacdGateStore.save(ctx, ns, st.copy(slots = slots))
    }
}

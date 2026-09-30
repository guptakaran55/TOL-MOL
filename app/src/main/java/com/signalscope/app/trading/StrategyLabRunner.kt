package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.CandleData
import com.signalscope.app.data.ManualWatchStore
import com.signalscope.app.data.StrategyLabStore
import com.signalscope.app.network.BinanceClient
import com.signalscope.app.network.YahooFinanceClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

// ════════════════════════════════════════════════════════════════════════════
// STRATEGY LAB RUNNER — Stage 2 live loop (PAPER ONLY)
// ════════════════════════════════════════════════════════════════════════════
// For each enrolled symbol: fetch 15-min candles, run the pure engine over the
// window, then reconcile its trades against the per-symbol watermark so only NEW
// actions (since the last run) are written to the paper ledger + audit.
//
// Namespaced by `ns` so multiple broker spaces run independently with their own
// store file, config and candle source:
//   ns = "us"      → Yahoo stock candles  (Trading 212 US space)
//   ns = "binance" → Binance spot klines  (crypto space)
//
// Bootstrap rule (spec §6): the FIRST time we see a symbol we adopt its current
// position silently (no historical replay) and set the watermark to the latest
// bar. Thereafter every fresh entry/exit on a bar past the watermark is recorded.
//
// HARD BOUNDARY: places no real orders. Emits paper decisions only.
// ════════════════════════════════════════════════════════════════════════════

object StrategyLabRunner {

    private const val TAG = "StrategyLabRunner"
    private val running = ConcurrentHashMap<String, AtomicBoolean>()
    private fun guard(ns: String) = running.getOrPut(ns) { AtomicBoolean(false) }

    /**
     * Progress sink for one run pass, so "Run now" can show a busy state and a
     * real percentage instead of a fire-and-forget toast. Phases:
     *   "start"  — pass accepted, total = enrolled symbol count
     *   "gate"   — the daily MACD evaluation is running (can take minutes)
     *   "symbol" — done/total as each enrolled symbol is evaluated
     *   "done"   — pass finished (ALWAYS fires, including on failure)
     * Installed once by MainActivity; null when headless.
     */
    @Volatile
    var progressSink: ((ns: String, phase: String, done: Int, total: Int) -> Unit)? = null

    private fun prog(ns: String, phase: String, done: Int, total: Int) {
        try { progressSink?.invoke(ns, phase, done, total) } catch (_: Exception) {}
    }

    /** Deliberately SEPARATE from [progressSink]: the what-if replay is a
     *  user-initiated one-off, and under mode 3b it now fetches the whole ranking
     *  universe (100+ names), so it needs its own channel. Borrowing the Run-now
     *  chip would make a what-if look like a live trading pass. */
    var whatIfSink: ((ns: String, done: Int, total: Int) -> Unit)? = null

    private fun wprog(ns: String, done: Int, total: Int) {
        try { whatIfSink?.invoke(ns, done, total) } catch (_: Exception) {}
    }

    // ── REPLAY RUNAWAY GUARDS ───────────────────────────────────────────────
    // Individual HTTP calls are capped at 45s, but NOTHING capped the total. On a
    // 106-name universe a provider that is rate-limiting or half-down makes every
    // symbol burn a 45s adjusted-fetch timeout AND a 45s fallback timeout — about
    // 2.8 hours of "still working", with a counter creeping along so it never even
    // looks broken. Three independent brakes, because they fail differently:
    //   • overall DEADLINE — the wall-clock answer to "this is taking forever"
    //   • consecutive-FAILURE breaker — the provider is refusing us; grinding
    //     through 90 more timeouts helps nobody and deepens the rate limit
    //   • user CANCEL — the honest escape hatch when the other two are too patient
    // Plus a busy flag: a second replay started while one is in flight would double
    // the request rate into the very limiter that caused the slowness.
    // THE DEADLINE HAS TO SCALE WITH THE UNIVERSE. A flat 8 minutes was sized
    // for ~106 German names; the Indian index is four times that, so the same
    // budget cut every India replay off partway and reported it as "took too
    // long" — a verdict on the app's own limit, not on the data.
    //
    // Roughly three seconds a symbol, which is generous against the ~1s a
    // healthy fetch takes and leaves room for retries, over a floor that keeps
    // small universes behaving exactly as before. The ceiling is what stops a
    // half-down provider from grinding for an hour: past half an hour the two
    // other brakes — the consecutive-failure breaker and Cancel — are the right
    // answer, not more patience.
    const val WHATIF_BUDGET_MS = 8 * 60 * 1000L          // floor, and the small-universe default
    private const val WHATIF_BUDGET_PER_SYMBOL_MS = 3_000L
    private const val WHATIF_BUDGET_MAX_MS = 30 * 60 * 1000L

    fun whatIfBudgetMsFor(symbolCount: Int): Long =
        (90_000L + WHATIF_BUDGET_PER_SYMBOL_MS * symbolCount.coerceAtLeast(0))
            .coerceIn(WHATIF_BUDGET_MS, WHATIF_BUDGET_MAX_MS)

    private const val MAX_CONSECUTIVE_FAILURES = 12

    private val whatIfBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var whatIfCancel = false

    /** Ask the in-flight replay to stop at the next symbol boundary. */
    fun cancelWhatIf() { whatIfCancel = true }

    fun whatIfRunning(): Boolean = whatIfBusy.get()

    private class Budget(symbolCount: Int = 0) {
        val startMs = System.currentTimeMillis()
        /** Sized from the universe — see [whatIfBudgetMsFor]. */
        val limitMs = whatIfBudgetMsFor(symbolCount)
        var consecutiveFailures = 0
        var reason: String? = null
        fun elapsedSec() = (System.currentTimeMillis() - startMs) / 1000
        /** True once the run must stop; [reason] then explains why, in the user's terms. */
        fun shouldStop(): Boolean {
            if (reason != null) return true
            if (whatIfCancel) { reason = "cancelled"; return true }
            if (System.currentTimeMillis() - startMs > limitMs) {
                reason = "took longer than ${limitMs / 60_000} minutes"; return true
            }
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                reason = "$MAX_CONSECUTIVE_FAILURES symbols failed in a row — the data provider " +
                         "is refusing or timing out"; return true
            }
            return false
        }
    }

    /** Fetch closed 15-min candles for a symbol via the namespace's data source. */
    /**
     * ENGINE feed, config-driven (FeedPlan / ANDROID_ADAPTIVE_FETCH_INSTRUCTIONS
     * §2–4). The interval is derived from the config's entry/exit frames instead
     * of the old hardcoded 15m: a 240min/120min champion now gets 60m/730d
     * (~2,186 four-hour bars) rather than 15m/60d (180 bars vs a 240 warmup),
     * which is why it could never open a position.
     *
     * Binance is intentionally left on its paged 15m feed (§Q3): it has no
     * history cap and 8000 bars already clears warmup with 2× headroom.
     */
    /** BOTH providers are config-planned. Binance pages by bar COUNT and Yahoo by
     *  calendar RANGE, but the interval comes from [plan] either way — this used
     *  to hardcode 15m for crypto, which silently capped every crypto window at
     *  83 days no matter what the algo's config asked for. */
    private fun fetchCandlesRaw(
        ns: String, symbol: String, exchange: String, plan: FeedPlan.Plan
    ): YahooFinanceClient.CandleResult =
        if (Spaces.baseOf(ns) == "binance")
            BinanceClient.fetchKlines(symbol, plan.interval,
                plan.targetBars.takeIf { it > 0 } ?: FeedPlan.BINANCE_TARGET_BARS)
        else YahooFinanceClient.fetchCandlesAt(symbol, exchange, plan.interval, plan.range)

    /**
     * Candles for one symbol, honouring the Indian feed choice.
     *
     * FALLS BACK RATHER THAN GOING DARK. Zerodha's history rides on a paid
     * subscription that can lapse between months, and the cost of stopping is far
     * higher than the cost of a second-best feed: no candles means no signal
     * check, which looks exactly like a market where nothing is happening.
     *
     * The fallback is LOUD. A silent one would hide a lapsed subscription for
     * weeks — the user would believe they were trading exchange bars while
     * quietly running on Yahoo.
     */
    private fun fetchCandlesFor(
        ctx: Context, ns: String, symbol: String, exchange: String, plan: FeedPlan.Plan
    ): YahooFinanceClient.CandleResult {
        val base = Spaces.baseOf(ns)
        if (base != "in") return fetchCandlesRaw(ns, symbol, exchange, plan)
        val cfg = com.signalscope.app.data.ConfigManager(ctx)
        if (!cfg.indiaCandleSource.equals("kite", true))
            return fetchCandlesRaw(ns, symbol, exchange, plan)
        if (!com.signalscope.app.network.KiteHistoryClient.supports(plan.interval)) {
            Log.w(TAG, "[$ns] Kite has no ${plan.interval} interval — using Yahoo for $symbol")
            return fetchCandlesRaw(ns, symbol, exchange, plan)
        }
        val days = (plan.capCalendarDays.takeIf { it > 0 } ?: 400)
        return when (val r = com.signalscope.app.network.KiteHistoryClient
            .fetchCandles(ctx, symbol, plan.interval, days)) {
            is com.signalscope.app.network.KiteHistoryClient.Result.Success ->
                YahooFinanceClient.CandleResult.Success(r.candles)
            is com.signalscope.app.network.KiteHistoryClient.Result.SessionExpired -> {
                Log.w(TAG, "[$ns] Kite session expired — log in to Zerodha again; using Yahoo for $symbol")
                fetchCandlesRaw(ns, symbol, exchange, plan)
            }
            is com.signalscope.app.network.KiteHistoryClient.Result.Unavailable -> {
                Log.w(TAG, "[$ns] Kite history unavailable for $symbol (${r.why}) — using Yahoo")
                fetchCandlesRaw(ns, symbol, exchange, plan)
            }
        }
    }

    /** Kick a background pass over all enrolled symbols. No-ops if disabled,
     *  nothing to do, or a pass is already in flight for this namespace. */
    fun runAll(ctx: Context, minIntervalMs: Long = 0L, ns: String = "us", onDone: (() -> Unit)? = null) {
        // Every early return below must clear the busy state, or a caller that
        // optimistically showed a spinner would leave it turning forever.
        fun bail() { prog(ns, "done", 0, 0); onDone?.invoke() }
        val state = StrategyLabStore.load(ctx, ns)
        if (!state.enabled) { bail(); return }
        // DEADLOCK GUARD: under Mode 3b the GATE owns enrollment — it enrolls a
        // name only when it fills a slot. So a freshly-armed gate legitimately
        // starts with ZERO enrolled symbols. Bailing on "nothing enrolled" would
        // skip MacdGateEngine.evaluate() (which runs inside this pass), so the
        // gate could never fill a slot, could never enroll, and would sit at
        // "not evaluated yet · 0/N slots" forever. Let the pass run whenever the
        // gate is armed, even with an empty roster.
        val gateArmed = try { com.signalscope.app.data.MacdGateStore.load(ctx, ns).enabled }
                        catch (e: Exception) { false }
        if (state.enrolled.isEmpty() && !gateArmed) { bail(); return }
        // Throttle background ticks; "Run now" passes 0 to force.
        if (minIntervalMs > 0 && System.currentTimeMillis() - state.lastRunAt < minIntervalMs) { bail(); return }
        val g = guard(ns)
        if (!g.compareAndSet(false, true)) { bail(); return }
        Thread {
            try {
                val cfg = StrategyLabStore.activeConfig(ctx, ns)
                // Resolve any live buys whose HTTP response was lost on a prior
                // tick BEFORE acting again — a filled-but-unconfirmed real
                // position must not stay unmanaged (recovers UNKNOWN -> OPEN/ERROR).
                // Re-price open positions on every pass, not only when the Algos tab
                // happens to be rendered. Otherwise a phone that sat locked for
                // hours shows prices hours old the moment it is opened.
                try { MarkToMarket.refreshAsync(ctx) }
                catch (e: Exception) { Log.w(TAG, "mark refresh failed", e) }
                // T212 fill backfill — READ ONLY, so it runs regardless of the live
                // freeze. Without it the paper prices sit next to bare order ids
                // forever and the drag metric can never acquire a sample.
                if (Spaces.baseOf(ns) == "de" || Spaces.baseOf(ns) == "us") {
                    try {
                        val n = T212LiveMirror.reconcileFills(ctx)
                        if (n > 0) Log.i(TAG, "[$ns] backfilled real fills for $n trade(s)")
                    } catch (e: Exception) { Log.w(TAG, "t212 fill reconcile failed", e) }
                    // POSITION RECONCILIATION. Also read-only, and skipped entirely
                    // when no live row is open — with nothing mirrored there is
                    // nothing to disagree about, and a portfolio read costs rate
                    // limit that the ladder needs. Only runs at most hourly: drift
                    // is caused by fills, and fills are rare compared to ticks.
                    try {
                        val anyOpen = com.signalscope.app.data.T212LiveStore.load(ctx)
                            .trades.any { it.status == "OPEN" && it.qty > 0.0 }
                        val lastAt = T212PositionAudit.last(ctx).checkedAt
                        if (anyOpen && System.currentTimeMillis() - lastAt > POSITION_AUDIT_MS) {
                            val rep = T212PositionAudit.run(ctx)
                            if (rep.hasCritical) LabNotifier.positionDrift(ctx, rep.critical.size)
                        }
                    } catch (e: Exception) { Log.w(TAG, "position audit failed", e) }
                }
                // POSITIONS THE ENGINE HAS LOST, given their rules back. Runs after
                // the fill reconcile above so a just-filled row has its real entry
                // price, and before the symbol loop so a sell it decides on lands
                // in the same pass rather than a tick later.
                try { manageOrphanedLivePositions(ctx, ns) }
                catch (e: Exception) { Log.w(TAG, "[$ns] orphan position sweep failed", e) }
                if (Spaces.baseOf(ns) == "binance") {
                    // Per-ledger: resolve UNKNOWN buys + late orphan exits for THIS
                    // ns; converge (missed-exit self-heal + global unmanaged audit).
                    try { BinanceLiveMirror.reconcile(ctx, ns) }
                    catch (e: Exception) { Log.w(TAG, "binance mirror reconcile failed", e) }
                    try { BinanceLiveMirror.converge(ctx, ns) }
                    catch (e: Exception) { Log.w(TAG, "binance convergence failed", e) }
                }
                // Daily-MACD gate (Mode 3b): at most one real evaluation per UTC
                // day (cheap no-op otherwise). Runs BEFORE the symbol loop so a
                // symbol enrolled today is evaluated in this same pass.
                prog(ns, "gate", 0, 0)
                // BEFORE the evaluation, not after: reconciliation hands an
                // unseated open position back a seat, and the evaluation would
                // otherwise give that seat away to a fresh pick first. It also
                // clears the roster of names no seat owns, so the symbol loop
                // below cannot open a position for a name the gate dropped.
                try { MacdGateEngine.reconcileSeats(ctx, ns) }
                catch (e: Exception) { Log.w(TAG, "seat reconcile failed", e) }
                try { MacdGateEngine.evaluate(ctx, ns) }
                catch (e: Exception) { Log.w(TAG, "macd gate eval failed", e) }
                // AND FILL ANYTHING THAT EMPTIED SINCE THAT RAN.
                //
                // The evaluation above refuses to run twice in one UTC day, so a
                // seat freed after it — a name ejected flat, or one you sold by
                // hand — used to hold cash until the day rolled over. On five
                // seats that is a fifth of the algo idle for up to a day, and the
                // gate's own log shows it happening most days.
                //
                // Re-uses the ranking that pass already computed and applies the
                // same three rules to it, so this is the same decision arriving
                // sooner rather than a different one. Costs no fetch, and seating
                // a name does not buy it — the strategy still has to fire.
                try { MacdGateEngine.refillFreeSeats(ctx, ns) }
                catch (e: Exception) { Log.w(TAG, "seat refill failed", e) }
                // Re-read AFTER the gate: a 3b evaluation may have just enrolled
                // the names this pass is about to evaluate.
                val roster = StrategyLabStore.load(ctx, ns).enrolled
                prog(ns, "start", 0, roster.size)
                for ((i, en) in roster.withIndex()) {
                    prog(ns, "symbol", i, roster.size)
                    try { runSymbol(ctx, cfg, en.symbol, en.exchange, ns) }
                    catch (e: Exception) { Log.w(TAG, "runSymbol ${en.symbol} failed", e) }
                    try { Thread.sleep(200) } catch (_: InterruptedException) {}
                }
                prog(ns, "symbol", roster.size, roster.size)
                // MANUAL rebalancing (user decision): the app only REMINDS once per
                // new calendar month — the user runs the screen and reallocates
                // themselves via "Rebalance now". No automatic capital moves.
                try { com.signalscope.app.screener.ReinvestEngine.maybeMonthlyReminder(ctx, ns) }
                catch (e: Exception) { Log.w(TAG, "reinvest reminder failed", e) }
                StrategyLabStore.markRun(ctx, ns)
            } finally {
                g.set(false)
                // ALWAYS clear the busy state, including on an exception — a stuck
                // spinner is worse than no spinner.
                prog(ns, "done", 0, 0)
                onDone?.invoke()
            }
        }.apply { isDaemon = true; name = "strategy-lab-runner-$ns" }.start()
    }

    // NOTE: there is deliberately no BAR_SEC constant any more. The base bar
    // period comes from FeedPlan, which derives the interval from the active
    // config — a 240min/120min config runs on a 60m feed, so the closed-bar gate
    // must wait 60 minutes, not 15. Hardcoding 15 min was what starved the
    // German engine of history and stopped it ever opening a position.
    private const val GRACE_SEC = 60L        // completion grace after the bar's close
    // Max age (minutes since the newest bar CLOSED) at which live ENTRIES may
    // still fire. 3×15-min bars: tolerant of alarm/Doze jitter, but a feed that
    // silently stopped updating can't trigger real buys on stale signals.
    private const val STALE_ENTRY_MAX_MIN = 45L
    // How often the ledger is diffed against T212's actual holdings. Drift is
    // caused by fills, which are rare next to ticks, and a portfolio read spends
    // rate limit the patient ladder needs during a chase.
    private const val POSITION_AUDIT_MS = 60 * 60 * 1000L

    private fun runSymbol(ctx: Context, cfg: StrategyLabConfig, symbol: String, exchange: String, ns: String) {
        val sym = symbol.uppercase()
        val now = System.currentTimeMillis()
        // Feed derived from THIS config's frames; barSec replaces the old fixed
        // 15-min BAR_SEC everywhere below (closed-bar gate, gap health, engine
        // source period), so a 60m feed is gated on 60-min bars, not 15-min ones.
        val plan = FeedPlan.plan(cfg, ns)
        val barSec = plan.sourceSec
        if (!plan.ok) {
            Log.w(TAG, "[$ns] $sym feed plan unusable: ${plan.problem}")
            StrategyLabStore.setSymbolStatus(ctx, StrategyLabStore.SymbolStatus(
                sym, now, ok = false, note = plan.problem ?: "config cannot clear warmup"), ns)
            return
        }
        // ── SKIP WHEN NO NEW CLOSED BAR CAN POSSIBLY EXIST ──────────────────
        // The tick runs every 15 min so that ANY frame is serviced promptly, but a
        // 240/120 config only reaches a new decision every 2 hours — so ~7 of every
        // 8 passes refetched a full series and recomputed the identical answer. With
        // six algos of five seats that was ~120 fetches an hour, ~90 of them
        // redundant, and it is what starved the mark-to-market poll of rate limit.
        //
        // The watermark is the last EXIT-FRAME bar the engine consumed (runMtf
        // reports the fine frame), so the next one cannot close before
        // wm + 2 frames. Skipping until then is behaviour-neutral: the engine only
        // ever acts on closed bars, so it would have reached the same conclusion.
        // Exits are NOT delayed — the skip expires exactly when a new bar closes.
        //
        // Two deliberate exceptions: never skip on bootstrap (no watermark yet), and
        // never skip while this symbol has no mark at all. Marks live in memory, so
        // after a process restart a skip would leave positions showing "unpriced"
        // until the next bar closed — a day, on a daily config.
        val frameSec = maxOf(
            (if (cfg.multiTimeframe) plan.exitMin else plan.entryMin).toLong() * 60L,
            plan.sourceSec)
        val wmNow = StrategyLabStore.load(ctx, ns).watermarks[sym] ?: 0L
        if (wmNow > 0L && MarkToMarket.get(sym) != null) {
            val nextClosedSec = wmNow + 2L * frameSec + GRACE_SEC + FeedDelay.secFor(ns)
            if (now / 1000L < nextClosedSec) {
                Log.d(TAG, "[$ns] $sym no new ${frameSec / 60}min bar yet — skipping fetch")
                return
            }
        }
        val res = fetchCandlesFor(ctx, ns, symbol, exchange, plan)
        if (res !is YahooFinanceClient.CandleResult.Success) {
            Log.w(TAG, "candle fetch for $symbol: ${res::class.simpleName}")
            StrategyLabStore.setSymbolStatus(ctx, StrategyLabStore.SymbolStatus(
                sym, now, ok = false, note = "no data — check the symbol"), ns)
            return
        }
        // Closed-bar gate: never act on the still-forming bar. A 15-min bar that
        // starts at ts closes at ts+900; require a small grace past that so a
        // partial bar can't fire a false signal that then gets watermarked away.
        val nowSec = now / 1000
        // AND BY THE DATA, NOT JUST THE CLOCK. See [FeedDelay]: a German bar is
        // judged only once Yahoo has actually delivered its last minutes.
        val feedDelay = FeedDelay.secFor(ns)
        val closedBars = res.candles.filter {
            FeedDelay.isComplete(it.timestamp, barSec, nowSec, GRACE_SEC, feedDelay)
        }
        // Data-quality guard: drop corrupt bars and measure freshness/gaps so the
        // engine never trades on a Yahoo bad tick, and the user can SEE whether
        // the feed is healthy before trusting the signals (esp. delayed Xetra data).
        val health = sanitizeCandles(closedBars, nowSec, barSec)
        val candles = health.clean
        // ── A DOWNLOAD THAT ENDS BEFORE WHAT WAS ALREADY PROCESSED ─────────────
        //
        // Ignored whole, rather than run. Running it moved the watermark BACK to
        // wherever that download happened to stop — and the next complete download
        // then found the day's trades "new" all over again: the alerts went out a
        // second time hours later, and every exit was applied to its seat again.
        // The Indian algos had nearly every closed trade compounded two to four
        // times this way (Indian bars come from Zerodha with Yahoo behind it, and
        // one of the two returned series missing the latest session). It also
        // dropped still-open paper trades as "phantoms", because a series that
        // ends yesterday cannot reproduce today's entries.
        //
        // Before the price mark, too: a short series would otherwise mark every
        // position at an older close than the one already shown.
        if (wmNow > 0L && candles.isNotEmpty() && candles.last().timestamp < wmNow) {
            Log.w(TAG, "[$ns] $sym: the price download ends at ${candles.last().timestamp}, before " +
                "bars already processed (to $wmNow) — a short feed, ignored this pass")
            StrategyLabStore.setSymbolStatus(ctx, StrategyLabStore.SymbolStatus(
                sym, now, ok = false, bars = candles.size,
                note = "price feed came back short — ignored this pass"), ns)
            return
        }
        // MARK FROM THE BARS WE JUST FETCHED — free, and always as fresh as the
        // engine itself. The separate 5-minute price poll was the wrong design: it
        // duplicated a request the pass had already made, and if it got rate-limited
        // (likely, right after the gate's 106-symbol daily sweep) positions showed
        // "unpriced" even though a perfectly good close was sitting right here.
        // Timestamp is the bar's CLOSE, not now, so staleness reflects the DATA:
        // a 60-min bar opened 09:00 is priced as of 10:00, not whenever we looked.
        candles.lastOrNull()?.let { last ->
            if (last.close > 0.0)
                MarkToMarket.onBarClose(sym, last.close, (last.timestamp + barSec) * 1000L, barSec * 1000L)
        }
        if (candles.size < 50) {  // not enough closed history to form a warmup window
            StrategyLabStore.setSymbolStatus(ctx, StrategyLabStore.SymbolStatus(
                sym, now, ok = false, bars = candles.size, note = "warming up — only ${candles.size} bars"), ns)
            return
        }

        // FRESH START AT THE SEAT'S TRACKING BOUNDARY. Read BEFORE the engine runs,
        // because it is now an input to it rather than a filter on its output.
        //
        // The old shape filtered the result — a replay that crossed the baseline
        // holding a position had that trade dropped from the ledger while the engine
        // stayed internally long, so the seat could open nothing until the phantom
        // closed by itself. Suppressing pre-baseline entries removes the phantom at
        // the source: the engine cannot be in a position it was never allowed to
        // open, so it reaches the baseline flat.
        //
        // 0 on bootstrap (no baseline stamped yet), which is exactly the historical
        // behaviour — and that pass records nothing anyway.
        val baselineTs = StrategyLabStore.baselineFor(ctx, sym, ns)
        val run = StrategyLabEngine.runLiveClosed(symbol, candles, cfg, barSec, baselineTs)
        if (run.error != null) {
            Log.w(TAG, "engine $symbol: ${run.error}")
            StrategyLabStore.setSymbolStatus(ctx, StrategyLabStore.SymbolStatus(
                sym, now, ok = false, bars = candles.size, note = run.error!!), ns)
            return
        }

        val st0 = StrategyLabStore.load(ctx, ns)
        val prevWm = st0.watermarks[sym] ?: 0L
        val bootstrap = prevWm == 0L
        val lastBarTs = candles.last().timestamp
        // Watermark + baseline must advance on the ENGINE's bar clock (the
        // resampled exit timeframe that entryTs/exitTs are stamped on), NOT the
        // raw 15-min fetch clock. The 15-min clock runs AHEAD of the resampled
        // clock: a 30-min bar starting at T completes at T+30, by which time the
        // 15-min watermark is already at T or later — so `entryTs > prevWm` was
        // false on every regular tick, the entry was never "new", and the live
        // mirror never fired (no error either — the block was simply skipped).
        val wmBar = if (run.lastBarTs > 0L) run.lastBarTs else lastBarTs
        // The same rule on the engine's own bar clock: the watermark never moves
        // backwards. See the short-feed check above.
        if (!bootstrap && wmBar < prevWm) {
            Log.w(TAG, "[$ns] $sym: engine's newest bar $wmBar is before the watermark $prevWm — ignored")
            return
        }

        if (bootstrap) {
            // FLAT START: the first run after enroll/reset does NOT adopt positions
            // the strategy is already in (those entered before you started). It
            // just stamps "tracking begins at this bar" and records nothing —
            // so "Reset + Run now" is a genuine clean start from today. Only
            // entries on bars AFTER this baseline are counted from here on.
            // WATERMARK ONE TICK BEHIND THE BASELINE, deliberately.
            //
            // The baseline is the first bar this seat may trade ON. If the watermark
            // were stamped at that same bar, an entry taken there would fail the
            // "newer than anything already processed" test that guards live orders —
            // so a brand-new algo's very first position would be booked on paper and
            // be structurally incapable of ever becoming real. It would have to wait
            // for that phantom-by-another-name to exit before real trading could
            // start at all, which is the same trap the fresh-start rule was written
            // to remove.
            //
            // One second earlier makes the bind bar itself eligible. Nothing is
            // loosened by it: the staleness gate still refuses to act on an old feed,
            // and now it RECORDS that refusal instead of the entry vanishing between
            // two tests that each thought the other had handled it.
            //
            // ── BUT ONLY WHILE THE FEED IS ACTUALLY LIVE ────────────────────
            //
            // That concession assumed the newest bar is a bar that just closed. On
            // a shut market it is not: the last US 240-minute bar closes at 22:00
            // and stays "newest" until the following afternoon. Making it eligible
            // meant every reset during those hours immediately re-adopted a
            // position on a bar that had closed long before the reset — and reset
            // again an hour later and it came straight back, because the bar had
            // not moved. Observed twice on this account, fourteen hours after the
            // close, which is the precise opposite of the clean start promised
            // three paragraphs above.
            //
            // The concession existed so a brand-new algo's FIRST position could
            // become real. On a stale feed it cannot anyway — the entry gate
            // refuses — so all it bought was a paper position nobody asked for.
            // Withdrawing it exactly where it pays nothing: with the market shut,
            // tracking starts at the NEXT bar and the book stays genuinely flat.
            val bindFresh = health.lastBarAgeMin <= STALE_ENTRY_MAX_MIN
            if (!bindFresh)
                Log.i(TAG, "[$ns] $sym bootstrap on a stale feed " +
                    "(${health.lastBarAgeMin}m) — tracking starts at the NEXT bar, " +
                    "so the book starts flat rather than adopting the last one")
            StrategyLabStore.applyRunResult(ctx, sym, emptyList(), emptyList(),
                if (bindFresh) (wmBar - 1L).coerceAtLeast(0L) else wmBar, ns,
                baseline = if (bindFresh) wmBar else wmBar + 1L)
        } else {
            // Ignore any trade whose ENTRY is before the baseline — including its
            // eventual exit — so a pre-start position can never leak in later.
            //
            // STRICTLY BEFORE, not at-or-before. The engine is now told the same
            // boundary and refuses to open anything earlier than it, so this is
            // mostly a belt-and-braces check on output the engine should no longer
            // produce. The two MUST agree on the boundary bar: if the engine may
            // enter AT the baseline (it may) while this discarded that entry, the
            // engine would stay long on a trade the ledger never saw — which is
            // precisely the phantom this change removes, reintroduced one bar wide.
            // Matches the desktop reference, which skips `entry < since`.
            val baseline = baselineTs
            // WIND-DOWN (exit-only): symbols dropped by a rebalance while in a
            // trade take no NEW entries — the existing position rides to its algo
            // exit, then ReinvestEngine.onTradeClosed releases the capital.
            val windDown = (com.signalscope.app.data.ReinvestStore.load(ctx, ns).windingDown ?: emptyList())
                .map { it.uppercase() }.toSet()
            // Accumulate all changes, then commit in ONE atomic write (no torn state).
            val newTrades = ArrayList<StrategyLabStore.LedgerTrade>()
            val newAudits = ArrayList<StrategyLabStore.Audit>()
            val known = st0.trades.associateBy { it.id }
            for (t in run.trades) {
                if (t.entryTs < baseline) continue   // entered before tracking began
                if (sym in windDown && t.entryTs > prevWm) continue  // exit-only: skip fresh entries entirely
                val id = "$sym-${t.entryTs}"
                // A TRADE THE BOOK HAS CLOSED IS FINAL. However the engine re-reads
                // it — a replay, or two feeds disagreeing about one bar — it is
                // never closed a second time (which re-applied its result to the
                // seat and re-sent the alert) and never reopened.
                val prior = known[id]
                if (prior?.status == "CLOSED") continue
                val isOpen = t.status == "OPEN"
                // Already in the book means already announced, already mirrored.
                val newEntry = t.entryTs > prevWm && prior == null
                val newExit = t.status == "CLOSED" && (t.exitTs ?: 0L) > prevWm
                if (newEntry) newAudits.add(StrategyLabStore.Audit(
                    id = "$id-BUY", symbol = sym, action = "BUY", price = t.entryPrice,
                    reason = t.entryReason, barTs = t.entryTs))
                if (isOpen || newEntry || newExit) newTrades.add(ledgerOf(id, sym, t))
                if (newExit) {
                    newAudits.add(StrategyLabStore.Audit(
                        id = "$sym-${t.exitTs}-SELL", symbol = sym, action = "SELL",
                        price = t.exitPrice ?: 0.0, reason = t.exitReason, barTs = t.exitTs ?: lastBarTs))
                    // Monthly reinvest: compound this symbol's capital by the closed
                    // trade's net % return (no-ops if the reinvest layer is off).
                    com.signalscope.app.screener.ReinvestEngine.onTradeClosed(ctx, ns, sym, t.entryTs, t.pctReturn)
                    // Daily-MACD gate (3b): compound the slot; if the name was
                    // flagged and this was its natural close with the slope still
                    // ≤0, the slot releases and its capital migrates one-for-one.
                    try { MacdGateEngine.onTradeClosed(ctx, ns, sym, t.pctReturn) }
                    catch (e: Exception) { Log.w(TAG, "macd gate close hook failed", e) }
                }
                // "Newest bar only": compare entry/exit against the engine's own
                // latest evaluated bar (run.lastBarTs), which is stamped on the
                // SAME (resampled) timeframe as entryTs/exitTs. Comparing against
                // the raw 15-min fetch granularity never matched, so live orders
                // never fired — this is the correct clock. Historical catch-up
                // entries (older than the newest bar) still stay paper-only.
                val freshBar = run.lastBarTs
                // STALENESS GATE (entries only): "newest bar" does NOT imply fresh
                // data — if the feed itself is stale, entryTs == freshBar still
                // matches within the same stale fetch, and we'd buy an old signal
                // at today's price. Exits are deliberately NOT gated: selling on
                // stale data is still risk-reducing (same asymmetry as the orphan
                // sweep). Threshold = 3 source bars, lenient enough for Doze jitter.
                val dataFresh = health.lastBarAgeMin <= STALE_ENTRY_MAX_MIN
                if (!dataFresh) Log.w(TAG, "$sym feed stale (${health.lastBarAgeMin}m) — live entries suppressed this tick")
                // Trade notifications: without them the user is blind to opens and
                // closes unless the app happens to be on screen. Paper channel is
                // default-importance; LIVE fills notify separately (high) from
                // inside each mirror when real money actually moves.
                //
                // EVERY SPACE, NOT JUST CRYPTO. This was `baseOf(ns) == "binance"`,
                // which made the per-space and per-algo notification switches a lie
                // everywhere else: India could be ticked on in Settings and ticked
                // on again on the algo's own bell, and still never produce a single
                // notification, because the only line that could raise one never ran
                // outside crypto. Whether to notify is the switches' decision and it
                // is taken inside the notifier — this line's job is only to ask.
                if (newEntry) LabNotifier.paperOpen(ctx, ns, sym, t.entryPrice)
                if (newExit) LabNotifier.paperClose(ctx, ns, sym, t.pctReturn, t.exitReason)
                val base = Spaces.baseOf(ns)
                // ASYMMETRIC GATING: entries stay newest-bar-only + fresh-data
                // (adding risk = paranoid). Exits fire on ANY new exit, however
                // stale — a tick that slept through the exit bar must still sell
                // (that gap is what orphaned a real BTC position; the convergence
                // sweep remains the backstop if even this is missed).
                //
                // CRYPTO: bare "binance" AND any binance BUCKET mirror to their
                // OWN ns-keyed ledger. The mirror's liveCfgFor enforces each
                // bucket's own live toggle / allowlist / budget — a paper bucket
                // simply no-ops. Shared-balance reservation stops a bucket's sell
                // from touching another bucket's coins.
                if (base == "binance" && freshBar > 0L) {
                    if (newEntry && t.entryTs == freshBar && dataFresh) {
                        BinanceLiveMirror.mirrorEntry(ctx, id, t, ns)
                    }
                    if (newExit) {
                        BinanceLiveMirror.mirrorExit(ctx, id, t, ns)
                    }
                }
                // T212 (US/DE): bare path AND buckets, exactly like crypto. Buckets
                // were excluded on the grounds that they were "paper-only and live
                // is frozen anyway" — but every German algo IS a bucket, so that
                // made the whole space unreachable by design rather than by choice.
                // The mirror's liveCfgFor enforces each bucket's own live toggle /
                // allowlist / budget, so a paper algo no-ops; LIVE_FROZEN is still
                // the outer gate. Ticker resolution follows the base space.
                if ((base == "us" || base == "de") && freshBar > 0L) {
                    val t212Space = if (base == "de") "DE" else "US"
                    if (newEntry && t.entryTs == freshBar && dataFresh) {
                        T212LiveMirror.mirrorEntry(ctx, id, t, t212Space, ns)
                    } else if (newEntry) {
                        // THE MOST CONFUSING DECLINE, so it is spelled out. A signal
                        // found on an older bar is deliberately paper-only: mirroring
                        // it would fire orders at prices hours stale, and a catch-up
                        // run would do it for every bar at once. From the card this
                        // is indistinguishable from a broken switch, which is exactly
                        // why the reason is written down rather than only logged.
                        com.signalscope.app.data.LiveSkipStore.note(ctx, ns, id, t.symbol,
                            if (!dataFresh)
                                "the price feed was stale on this pass — no real order placed"
                            else "signal came from an older bar, not the newest closed one — " +
                                 "catch-up entries stay on paper")
                    } else if (isOpen &&
                        com.signalscope.app.data.T212LiveStore.get(ctx, id, ns) == null) {
                        // AN OPEN POSITION THAT NEVER REACHED THE MIRROR AT ALL.
                        //
                        // newEntry is `entryTs > prevWm`, and it is FALSE whenever the
                        // entry bar sits behind the watermark. That is the normal shape
                        // of a position the FRESH-START boundary just unblocked: the
                        // seat was paralysed by a phantom for days while the watermark
                        // kept advancing, so the entry the engine now finds is already
                        // behind it. Such a trade enters the book through the isOpen
                        // branch above and never touches either mirror arm — so without
                        // this it shows as a held position with no order and NO STATED
                        // REASON, which is the one combination that reads exactly like a
                        // broken switch.
                        //
                        // Withholding the order is correct: buying a signal from days
                        // ago at today's price is precisely what the newest-bar rule
                        // exists to prevent. Only the silence was wrong.
                        // THE WORDING NAMES THE LIKELY CAUSE FIRST. It used to lead
                        // with a reset, and on a German position the user had not
                        // reset at all it read as an accusation with nothing behind
                        // it. The commonest cause was a bar judged on delayed prices
                        // and re-judged once the full bar arrived — see [FeedDelay],
                        // which now waits for the full bar and should make this rare.
                        com.signalscope.app.data.LiveSkipStore.note(ctx, ns, id, t.symbol,
                            "this signal was first seen AFTER its bar had already been " +
                            "checked, so no real order was placed — buying then would mean " +
                            "a price the signal no longer describes. Usually the price feed " +
                            "delivered that bar late and the signal appeared only once the " +
                            "full bar arrived; it also happens after a reset, or when a " +
                            "blocked seat is freed, because the replay rebuilds from " +
                            "history. Paper holds it, real money does not. It will trade " +
                            "for real on its next fresh signal.")
                    }
                    if (newExit) {
                        // A MANUAL position exits by its own route. The normal mirror
                        // is gated on the ALGO live-orders toggle, which is off — and
                        // rightly so, since it governs whether strategies may spend
                        // money unattended. It must not govern whether a position the
                        // user deliberately bought is allowed to be SOLD: that gate
                        // would leave a real holding with a stop that can never act.
                        // Exits reduce risk, so they are never withheld.
                        if (Spaces.isBucket(ns) &&
                            Spaces.bucketIdOf(ns) == com.signalscope.app.data.ManualWatchStore.ID) {
                            val out = T212LiveMirror.stopOutNow(ctx, ns, sym,
                                t.exitReason.ifBlank { "strategy exit" },
                                origin = "watchlist strategy exit")
                            if (!out.ok) Log.w(TAG, "[$ns] manual exit for $sym failed: ${out.error}")
                        } else {
                            T212LiveMirror.mirrorExit(ctx, id, t, t212Space, ns)
                        }
                    }
                }
                // INDIA: a plain order plus a broker-side GTT, rather than the
                // in-app ladder the T212 path needs. Same four gates, same
                // freshness rule — only a signal on the newest CLOSED bar may
                // open a position, so a catch-up replay can never buy.
                if (base == "in" && freshBar > 0L) {
                    if (newEntry && t.entryTs == freshBar && dataFresh) {
                        ZerodhaLiveMirror.mirrorEntry(ctx, id, t.symbol, t.entryPrice, ns)
                    } else if (newEntry) {
                        // INDIA DECLINED IN SILENCE UNTIL NOW. The T212 arm has
                        // written its reason down for a while; this one simply did
                        // nothing, so a withheld order was indistinguishable from a
                        // broken switch — which is exactly the complaint that found
                        // the market-order bug underneath it.
                        com.signalscope.app.data.LiveSkipStore.note(ctx, ns, id, t.symbol,
                            if (!dataFresh)
                                "the price feed was stale on this pass — no real order placed"
                            else "signal came from an older bar, not the newest closed one — " +
                                 "catch-up entries stay on paper")
                    }
                    if (newExit) {
                        ZerodhaLiveMirror.mirrorExit(ctx, id, t.symbol, t.exitPrice ?: 0.0, ns)
                    }
                }
            }
            // ── LEDGER RECONCILE (self-heal) ─────────────────────────────────
            // The fresh engine run is the source of truth for what is OPEN. The
            // feed returns a SLIDING window (a fixed BAR COUNT, not a fixed date
            // range) and the MACD EMAs seed from that window's FIRST bar — so a
            // borderline entry computed on an earlier tick can stop reproducing
            // once old bars drop off the front. Its id then never matches again
            // and, because the merge only adds/updates, the row stayed "OPEN"
            // forever (observed: ETHUSDC shown "in position" while the review
            // chart's fresh run showed flat, with no live position behind it).
            //
            // GUARD 1 — window: only judge trades whose entry lies INSIDE the
            // evaluated window. A position entered before the window's first bar
            // is simply invisible to this run and must never be dropped for that.
            // GUARD 2 — real money: never drop a row that still has an OPEN live
            // record. Dropping it would make the orphan sweep market-SELL a
            // genuine position, i.e. a cosmetic fix moving real money. Those are
            // left alone and logged instead.
            val windowStart = candles.first().timestamp
            val freshById = run.trades.filter { it.entryTs > baseline }
                .associateBy { "$sym-${it.entryTs}" }
            // GUARD 2 APPLIED IN EVERY MARKET, which it was not.
            //
            // This read the crypto ledger and, for every other space, an EMPTY SET
            // — so the one guard written to stop the app discarding a paper row
            // with real money behind it did nothing at all outside Binance. The
            // comment above described a protection that only a third of the app
            // actually had.
            //
            // What that costs is not cosmetic. When the sliding window moves on, a
            // borderline entry stops reproducing and its row is dropped as a
            // phantom — and with the row goes every strategy exit for a position
            // the broker is still holding. Two German positions were found in that
            // state: real, open, worth about €800, with no target, no momentum
            // exit and no holding-time limit. Only the stop survived, because that
            // one reads the live ledger directly rather than the paper book.
            //
            // PENDING and EXITING count as well as OPEN. An order still filling or
            // a sell in flight is real money committed, and a row dropped in that
            // window strands it exactly the same way.
            val liveOpenIds: Set<String> = try {
                when (Spaces.baseOf(ns)) {
                    "binance" -> com.signalscope.app.data.BinanceLiveStore.load(ctx, ns).trades
                        .filter { it.status == "OPEN" }.map { it.paperTradeId }.toHashSet()
                    "in" -> com.signalscope.app.data.ZerodhaLiveStore.load(ctx).trades
                        .filter { it.ns == ns && it.isLive }
                        .map { it.paperTradeId }.toHashSet()
                    else -> com.signalscope.app.data.T212LiveStore.load(ctx).trades
                        .filter { it.ns == ns &&
                            (it.status == "OPEN" || it.status == "PENDING" || it.status == "EXITING") }
                        .map { it.paperTradeId }.toHashSet()
                }
            } catch (e: Exception) {
                // UNREADABLE MEANS DROP NOTHING. The set is used only to protect
                // rows, so failing to build it must not quietly remove that
                // protection — an empty set here is precisely the bug above.
                Log.w(TAG, "[$ns] live ledger unreadable — skipping the phantom sweep this pass", e)
                null
            } ?: st0.trades.map { it.id }.toHashSet()
            val staleIds = HashSet<String>()
            for (row in st0.trades) {
                if (!row.symbol.equals(sym, true) || row.status != "OPEN") continue
                if (row.entryTs <= windowStart) continue      // outside this window — cannot judge
                if (row.id in liveOpenIds) {
                    Log.w(TAG, "reconcile $ns/$sym: ${row.id} looks stale but a REAL position is " +
                        "open against it — leaving it alone (never auto-sell from a display fix)")
                    continue
                }
                val fresh = freshById[row.id]
                when {
                    fresh == null -> {
                        staleIds.add(row.id)
                        Log.w(TAG, "reconcile $ns/$sym: dropping phantom OPEN ${row.id} — the fresh run no longer reports it")
                    }
                    // Engine has it CLOSED but the exit predates the watermark, so
                    // the loop above skipped it — record the close (keeps its P&L).
                    fresh.status == "CLOSED" && newTrades.none { it.id == row.id } -> {
                        newTrades.add(ledgerOf(row.id, sym, fresh))
                        Log.w(TAG, "reconcile $ns/$sym: ${row.id} is CLOSED in the fresh run — syncing the stale OPEN row")
                    }
                }
            }
            StrategyLabStore.applyRunResult(ctx, sym, newTrades, newAudits, wmBar, ns, removeIds = staleIds)
        }

        // REAL STOP — after the ledger write, on the same bar-close clock the paper
        // stop uses. See [checkRealStop].
        try { checkRealStop(ctx, ns, sym, cfg, candles.lastOrNull()?.close ?: 0.0, wmBar) }
        catch (e: Exception) { Log.w(TAG, "[$ns] $sym real-stop check failed", e) }

        // PRE-DECIDED GAP POLICY (point 2): if a live position is found gapped
        // ≥2× through the configured stop (downtime slipped past the bar-based
        // stop), sell IMMEDIATELY at market using the freshest price — including
        // the still-forming bar — instead of waiting for the next bar close.
        if (Spaces.baseOf(ns) == "binance" && cfg.exit.stopLossEnabled) {
            val cur = res.candles.lastOrNull()?.close ?: 0.0
            if (cur > 0.0) try { BinanceLiveMirror.emergencyStopCheck(ctx, sym, cur, cfg.exit.stopLossPct, ns) }
            catch (e: Exception) { Log.w(TAG, "emergency stop check failed for $sym", e) }
        }

        // Per-symbol diagnostics so the panel shows it IS evaluating, even when
        // bootstrap/quiet windows mean nothing lands in the ledger yet.
        //
        // "In position" must respect FLAT START: the engine may simulate an open
        // trade over the whole fetched window, but if that trade opened at/before
        // the baseline we deliberately did NOT adopt it (the ledger shows it
        // flat, and the real account was never bought). Showing "in position"
        // for such an un-adopted, pre-baseline position is exactly what made the
        // row disagree with both the ledger and Binance. Gate on the baseline so
        // the row reflects a TRACKED position only.
        val lastTrade = run.trades.lastOrNull()
        val baselineForNote = StrategyLabStore.baselineFor(ctx, sym, ns)
        val inPos = !bootstrap && lastTrade?.status == "OPEN" &&
            lastTrade.entryTs > baselineForNote
        val untrackedOpen = !bootstrap && lastTrade?.status == "OPEN" &&
            lastTrade.entryTs <= baselineForNote
        val gated = run.gates.values.sum()
        val baseNote = when {
            bootstrap -> "tracking from now · flat start (entries from here on)"
            inPos -> "in position"
            // The strategy would be long, but that entry predates enrollment —
            // flat-start means we wait for a FRESH entry before tracking/buying.
            untrackedOpen -> "flat · strategy long since before enroll — waiting for a fresh entry"
            run.signals > 0 -> "flat · ${run.signals} signals, $gated gated in window"
            else -> "flat · no entry signals in window"
        }
        // Surface Yahoo data health on the stock spaces (Binance klines are clean,
        // so the extra line would just be noise there).
        val note = if (Spaces.baseOf(ns) != "binance") "$baseNote · ${health.summary}" else baseNote
        StrategyLabStore.setSymbolStatus(ctx, StrategyLabStore.SymbolStatus(
            sym, now, ok = true, bars = candles.size, signals = run.signals,
            gated = gated, inPosition = inPos, note = note,
            // Free: the pass already computed these. Recorded so warmup and
            // timeframe can be audited from stored live state.
            baseSec = run.baseSec, entryTfSec = run.entryTfSec, exitTfSec = run.exitTfSec,
            entryBars = run.entryBars, warmBars = run.warmBars,
            firstTradableTs = run.firstTradableTs), ns)
    }

    // ── Yahoo data-quality guard ────────────────────────────────────────────
    // Drops only UNAMBIGUOUSLY corrupt bars (bad OHLC relationships) — never a
    // legitimate large move — and reports feed health so the user can judge
    // whether Yahoo is delivering fresh, complete Xetra data. Binance klines are
    // exchange-native and clean; this mostly guards the us/de (Yahoo) spaces.
    private data class CandleHealth(
        val clean: List<CandleData>, val dropped: Int, val intradayGaps: Int,
        val extremeMoves: Int, val lastBarAgeMin: Long
    ) {
        val summary: String get() = buildString {
            append("data: newest ${lastBarAgeMin}m old")
            if (dropped > 0) append(" · $dropped bad bars dropped")
            if (intradayGaps > 0) append(" · $intradayGaps intraday gaps")
            if (extremeMoves > 0) append(" · $extremeMoves extreme ticks")
        }
    }

    private fun sanitizeCandles(candles: List<CandleData>, nowSec: Long, barSec: Long): CandleHealth {
        if (candles.isEmpty()) return CandleHealth(candles, 0, 0, 0, 0L)
        val clean = ArrayList<CandleData>(candles.size)
        var dropped = 0
        for (c in candles) {
            val ok = c.open.isFinite() && c.high.isFinite() && c.low.isFinite() && c.close.isFinite() &&
                c.low > 0.0 && c.high >= c.low &&
                c.open >= c.low && c.open <= c.high && c.close >= c.low && c.close <= c.high
            if (ok) clean.add(c) else dropped++
        }
        if (clean.isEmpty()) return CandleHealth(clean, dropped, 0, 0, 0L)
        var intradayGaps = 0; var extreme = 0
        for (i in 1 until clean.size) {
            val dt = clean[i].timestamp - clean[i - 1].timestamp
            // count missing intraday bars only; ignore overnight/weekend gaps (> 4h)
            if (dt > barSec * 3 / 2 && dt < 4 * 3600)
                intradayGaps += ((dt / barSec) - 1).toInt().coerceAtLeast(1)
            val prev = clean[i - 1].close
            if (prev > 0.0 && kotlin.math.abs(clean[i].close - prev) / prev > 0.25) extreme++
        }
        val lastAge = ((nowSec - clean.last().timestamp - barSec) / 60L).coerceAtLeast(0L)
        return CandleHealth(clean, dropped, intradayGaps, extreme, lastAge)
    }

    private fun ledgerOf(id: String, sym: String, t: StrategyLabEngine.Trade): StrategyLabStore.LedgerTrade =
        StrategyLabStore.LedgerTrade(
            id = id, symbol = sym, status = t.status, entryTs = t.entryTs, entryPrice = t.entryPrice,
            entryReason = t.entryReason, exitTs = t.exitTs, exitPrice = t.exitPrice, exitReason = t.exitReason,
            pctReturn = t.pctReturn, pnlNotional = t.pnlNotional, barsHeld = t.barsHeld
        )

    private fun fetchClosed(
        ns: String, symbol: String, exchange: String, plan: FeedPlan.Plan
    ): List<CandleData> {
        val res = fetchCandlesRaw(ns, symbol, exchange, plan)
        if (res !is YahooFinanceClient.CandleResult.Success) return emptyList()
        val nowSec = System.currentTimeMillis() / 1000
        return res.candles.filter {
            FeedDelay.isComplete(it.timestamp, plan.sourceSec, nowSec, GRACE_SEC, FeedDelay.secFor(ns))
        }
    }

    /**
     * THE REAL STOP — the config's stop percentage measured from the price actually
     * PAID, alongside the paper stop measured from the signal price.
     *
     * WHY BOTH. Every price-anchored exit in the engine measures from `entryPrice`,
     * which is the signal bar's close — not what the ladder filled at. A fill above
     * the signal therefore shrinks the real protection silently: fill 3% high on a
     * 4% stop and the actual cushion is 1%. The paper stop is left EXACTLY as it is,
     * because it is the backtest-parity reference and the baseline every deviation
     * measurement is taken against. This is the same policy applied to the correct
     * price, not a new risk policy.
     *
     * ONLY THE STOP NEEDS THIS. The MACD-distance qualifier anchors to the MACD
     * value at the entry BAR, the slope-flip exit is pure indicator state, and
     * max-holding counts bars — none of them reference the entry price, so none are
     * touched.
     *
     * THE FILL PRICE IS [T212LiveStore.LiveTrade.liveEntryPrice], NOT refEntryPrice.
     * The latter is the anchor the ladder OPENED from, and a buy ladder concedes
     * upward from its anchor — so using it would put the stop BELOW where it
     * belongs, looser than reality, which is the exact failure this exists to fix.
     * When the real fill has not settled yet liveEntryPrice is 0 and this does
     * nothing: a missing measurement, never a guessed one.
     *
     * WHEN THE REAL STOP FIRES FIRST — which is the whole point, and happens
     * whenever the fill landed above the signal — the paper trade is closed TOO,
     * and the seat compounds by the REAL return. If paper stayed open the seat
     * would still believe it holds something, never release its capital, and later
     * compound by a gain nobody earned. The reinvest layer must follow real money.
     *
     * Evaluated on bar closes, deliberately NOT placed as a resting broker stop: a
     * broker stop triggers on any intraday tick, and the 4% was validated on bar
     * closes, so it would fire more often than the strategy was ever measured at.
     */
    /**
     * MANAGE REAL POSITIONS THE ENGINE NO LONGER TRACKS.
     *
     * A live row whose paper trade has gone is a genuine holding with nobody
     * running its exits. It happens when the sliding window moves on and a
     * borderline entry stops reproducing: the ledger row is dropped, and with it
     * the target, the momentum exit and the holding-time limit. Only the hard
     * stop survives, because that one reads this ledger rather than the book.
     *
     * The guard against creating them is fixed. This is the repair for the ones
     * already in that state, and for any future case the guard cannot cover —
     * a ledger reset, a reinstall, a config swap that invalidates the book.
     *
     * SAME RULES, DIFFERENT ANCHOR. It does not try to make the engine believe in
     * a trade it no longer reproduces. It asks the adopted-exit question instead —
     * "given that this position was opened at THIS price at THIS time, what would
     * the config do now?" — which is the same question, and the same code, the
     * watchlist has always used for stock bought by hand.
     *
     * THREE STATES, AND ONLY ONE OF THEM IS OURS:
     *   • paper row OPEN    — the strategy is managing it. Leave it alone.
     *   • paper row CLOSED  — the exit sweep re-sells it. Leave it alone, or two
     *                         sell orders race for the same shares.
     *   • paper row MISSING — nobody is managing it. This.
     */
    private fun manageOrphanedLivePositions(ctx: Context, ns: String) {
        val base = Spaces.baseOf(ns)
        if (base != "de" && base != "us" && base != "in") return
        val cfg = StrategyLabStore.activeConfig(ctx, ns)
        val paper = try { StrategyLabStore.load(ctx, ns).trades } catch (e: Exception) { return }
        val knownIds = paper.map { it.id }.toHashSet()
        val engineHolds = paper.filter { it.status == "OPEN" }.map { it.symbol.uppercase() }.toHashSet()
        // ── THE WATCHLIST MANAGES ITS OWN, AND MUST KEEP DOING SO ────────────
        //
        // The reserved watchlist namespace already runs this exact check, from a
        // better anchor: its book records the signal bar the position was opened
        // on, where a live row only knows when the order was sent. Two managers
        // asking the same question of the same shares is how one sells and the
        // other sells again into nothing.
        //
        // Skipped BY SYMBOL rather than by namespace, because the two do not
        // overlap completely: a live row in that namespace with no book entry
        // behind it — a hand-over that never landed — is covered by nobody, and
        // excluding the whole namespace would leave it that way. On this account
        // that was two real positions.
        val bookOwns: Set<String> =
            if (com.signalscope.app.trading.Spaces.bucketIdOf(ns) ==
                com.signalscope.app.data.ManualWatchStore.ID)
                try {
                    com.signalscope.app.data.ManualWatchStore.load(ctx, base).entries
                        .filter { it.status == "OPEN" || it.status == "PENDING" }
                        .map { it.symbol.uppercase() }.toHashSet()
                } catch (e: Exception) {
                    // UNREADABLE MEANS STAND BACK. Failing to see the watchlist's
                    // book is not evidence that it holds nothing, and acting on
                    // that reading is what would double-sell.
                    Log.w(TAG, "[$ns] watchlist book unreadable — orphan sweep stands down", e)
                    return
                }
            else emptySet()

        data class Orphan(val sym: String, val id: String, val paid: Double, val entryTs: Long)
        val orphans: List<Orphan> = try {
            if (base == "in")
                com.signalscope.app.data.ZerodhaLiveStore.load(ctx).trades
                    .filter { it.ns == ns && it.status == "OPEN" && it.qty > 0 }
                    .map { Orphan(it.symbol.uppercase(), it.paperTradeId,
                        it.entryPrice, it.paperTradeId.substringAfterLast('-').toLongOrNull() ?: 0L) }
            else
                com.signalscope.app.data.T212LiveStore.load(ctx).trades
                    .filter { it.ns == ns && it.status == "OPEN" && it.qty > 0.0 }
                    .map { Orphan(it.symbol.uppercase(), it.paperTradeId,
                        it.liveEntryPrice, it.entryTs) }
        } catch (e: Exception) {
            Log.w(TAG, "[$ns] live ledger unreadable — orphan sweep skipped", e); return
        }

        for (o in orphans) {
            if (o.id in knownIds) continue            // the strategy still owns this trade
            if (o.sym in engineHolds) continue        // it holds the NAME by another id
            if (o.sym in bookOwns) continue           // the watchlist's own manager has it
            // WITHOUT A REAL FILL PRICE THERE IS NOTHING HONEST TO MEASURE FROM.
            // Every adopted rule is anchored on what was actually paid; guessing
            // one would put a stop at a price nobody traded.
            if (o.paid <= 0.0 || o.entryTs <= 0L) {
                noteOrphan(ctx, ns, o.id, base,
                    "no real fill price recorded yet, so the rules cannot be anchored")
                continue
            }
            val v = try {
                adoptedExitCheck(ctx, ns, o.sym, Spaces.exchangeOf(ns), o.paid, o.entryTs)
            } catch (e: Exception) {
                Log.w(TAG, "[$ns] ${o.sym} orphan exit check failed", e); continue
            }
            noteOrphan(ctx, ns, o.id, base,
                if (v.ok) "" else v.note.ifBlank { "the exit rules could not be evaluated" })
            if (!v.ok || !v.fires) continue
            // SECOND LOCK ON THE SAME DOOR, for the same reason the watchlist has
            // one: the engine already refuses a verdict when the entry bar is
            // outside the window, but this is the line that spends real money and
            // does not take that on trust. A sell placed under a partial rule set
            // arrives wearing the name of a strategy that never specified it.
            if (!v.entryMacdKnown) {
                Log.w(TAG, "[$ns] ${o.sym} orphan exit SUPPRESSED — ${v.note}")
                continue
            }
            Log.w(TAG, "[$ns] ${o.sym} orphan exit fires: ${v.reason} at ${"%.2f".format(v.price)}")
            try {
                if (base == "in") {
                    ZerodhaLiveMirror.mirrorExit(ctx, o.id, o.sym, v.price, ns)
                } else {
                    val out = T212LiveMirror.stopOutNow(ctx, ns, o.sym, v.reason,
                        origin = "the config's exit, applied to a position the strategy had lost")
                    if (!out.ok) Log.w(TAG, "[$ns] ${o.sym} orphan exit failed: ${out.error}")
                }
            } catch (e: Exception) { Log.e(TAG, "[$ns] ${o.sym} orphan exit threw", e) }
        }
    }

    /** Record why an orphan could or could not be judged, on the row itself. */
    private fun noteOrphan(ctx: Context, ns: String, id: String, base: String, note: String) {
        try {
            if (base == "in") {
                com.signalscope.app.data.ZerodhaLiveStore.forTrade(ctx, ns, id)?.let {
                    if ((it.adoptedNote ?: "") != note)
                        com.signalscope.app.data.ZerodhaLiveStore.upsert(ctx, it.copy(adoptedNote = note))
                }
            } else {
                com.signalscope.app.data.T212LiveStore.get(ctx, id, ns)?.let {
                    if ((it.adoptedNote ?: "") != note)
                        com.signalscope.app.data.T212LiveStore.upsert(ctx, it.copy(adoptedNote = note))
                }
            }
        } catch (e: Exception) { Log.w(TAG, "[$ns] orphan note write failed", e) }
    }

    private fun checkRealStop(
        ctx: Context, ns: String, sym: String, cfg: StrategyLabConfig,
        lastClose: Double, barTs: Long
    ) {
        if (!cfg.exit.stopLossEnabled || lastClose <= 0.0) return
        val base = Spaces.baseOf(ns)
        if (base != "de" && base != "us") return
        val row = com.signalscope.app.data.T212LiveStore.load(ctx).trades.firstOrNull {
            it.ns == ns && it.symbol.equals(sym, true) && it.status == "OPEN" && it.qty > 0.0
        } ?: return
        val paid = row.liveEntryPrice
        if (paid <= 0.0) return
        val trigger = paid * (1 - cfg.exit.stopLossPct / 100.0)
        if (lastClose > trigger) return

        // The paper trade this position belongs to, captured BEFORE the sell so the
        // deviation record can state what the simulation thought it was worth.
        val paperOpen = StrategyLabStore.load(ctx, ns).trades
            .lastOrNull { it.symbol.equals(sym, true) && it.status == "OPEN" }
        val paperPct = paperOpen?.pctReturn ?: 0.0
        val signalPx = paperOpen?.entryPrice ?: 0.0
        val paperTrigger = if (signalPx > 0.0) signalPx * (1 - cfg.exit.stopLossPct / 100.0) else 0.0

        Log.w(TAG, "[$ns] REAL STOP $sym: close $lastClose ≤ $trigger (paid $paid) — " +
            "paper stop would be at $paperTrigger")
        val r = T212LiveMirror.stopOutNow(ctx, ns, sym,
            "real stop -${cfg.exit.stopLossPct}% from fill",
            origin = "stop measured from what you actually paid")
        if (!r.ok) return   // still open; retried on the next bar close

        // ── close the paper side and compound the seat by the REAL return ──
        val reason = "real stop (fill-anchored)"
        if (paperOpen != null) {
            StrategyLabStore.applyRunResult(ctx, sym,
                listOf(paperOpen.copy(status = "CLOSED", exitTs = barTs,
                    exitPrice = r.exitPrice, exitReason = reason, pctReturn = r.realPct)),
                listOf(StrategyLabStore.Audit(
                    id = "${paperOpen.id}-REALSTOP", symbol = sym, action = "SELL",
                    price = r.exitPrice, reason = reason, barTs = barTs)),
                // ADVANCE THE BASELINE, OR THE TRADE COMES BACK.
                //
                // This close happens OUT OF BAND: the engine still believes it is
                // long, because nothing about the price history changed. Leaving
                // the baseline where it was meant the very next pass replayed the
                // same bars, re-derived the same entry, and — since rows merge by
                // id — overwrote this CLOSED row back to OPEN. The position was
                // resurrected, and when the paper exit eventually fired the seat
                // compounded a second time on one real trade.
                //
                // Moving the boundary PAST the bar we stopped out on suppresses that
                // entry at the point of evaluation, so the replay arrives flat here
                // instead of holding a ghost.
                //
                // Past it, not onto it. The engine suppresses entries STRICTLY
                // before the boundary, so a boundary of exactly this bar still lets
                // the replay open a fresh position on the very candle the stop
                // fired on — re-entering, on the same close, a trade just exited
                // for falling too far. One second later excludes that bar and
                // leaves the next one free, which is the intent.
                barTs, ns, baseline = barTs + 1L)
        }
        try { MacdGateEngine.onTradeClosed(ctx, ns, sym, r.realPct) }
        catch (e: Exception) { Log.w(TAG, "[$ns] real-stop gate hook failed", e) }
        try { com.signalscope.app.screener.ReinvestEngine.onTradeClosed(
            ctx, ns, sym, paperOpen?.entryTs ?: 0L, r.realPct) }
        catch (e: Exception) { Log.w(TAG, "[$ns] real-stop reinvest hook failed", e) }

        // ── the paper-vs-real record, written at close because it cannot be
        //    reconstructed afterwards ──
        try {
            com.signalscope.app.data.StopDeviationStore.record(ctx,
                com.signalscope.app.data.StopDeviationStore.Deviation(
                    ns = ns, symbol = sym, at = System.currentTimeMillis(),
                    signalPrice = signalPx, fillPrice = r.paidPrice,
                    gapPct = if (signalPx > 0.0) (r.paidPrice / signalPx - 1.0) * 100.0 else 0.0,
                    closedBy = "real", exitPrice = r.exitPrice,
                    otherStopAt = paperTrigger,
                    paperPct = paperPct, realPct = r.realPct))
        } catch (e: Exception) { Log.w(TAG, "[$ns] deviation record failed", e) }
    }

    /** An entry the engine opened on the NEWEST closed bar — i.e. a signal that
     *  is live right now rather than one from history. */
    data class FreshEntry(
        val symbol: String,
        val entryTs: Long,          // epoch sec of the ENTRY bar (engine clock)
        val entryPrice: Double,     // that bar's close — what the exit measures from
        val entryReason: String,
        val lastBarTs: Long,
        val lastClose: Double,
        val entryTfMin: Int,
        val exitTfMin: Int
    )

    /**
     * READ-ONLY entry check for ONE symbol under [ns]'s config.
     *
     * Deliberately shares [fetchCandlesRaw], the closed-bar filter and
     * [sanitizeCandles] with the live loop instead of re-implementing them: a
     * watcher that fetched or cleaned bars even slightly differently would
     * disagree with the runner about whether a signal exists, and the whole point
     * of it is to report what the strategy would do.
     *
     * Writes NOTHING — no ledger, no watermark, no baseline, no mark. The symbol
     * does not need to be enrolled; the exchange falls back to the space default.
     *
     * Returns null when there is no data, not enough history, or the newest bar
     * did not open a position.
     */
    /**
     * What the replay is doing with a name right now — holding or flat, and if
     * holding, whether it opened on the NEWEST bar (a fresh signal) or earlier.
     *
     * A candidate whose strategy is already long simply never alerts, and from the
     * list that is indistinguishable from one nothing has ever happened to. The two
     * mean opposite things: one is a setup that has already played out and is
     * running, the other is still waiting. Worth telling apart on screen.
     */
    data class PosState(
        val symbol: String, val holding: Boolean, val entryTs: Long,
        val entryPrice: Double, val pctReturn: Double, val fresh: Boolean
    )

    /** Set as a side effect of the most recent [freshEntrySignal] call, read
     *  immediately after by the watcher. Single-threaded per pass — the watcher
     *  evaluates its candidates one at a time. */
    @Volatile
    var lastPositionState: PosState? = null
        private set

    /**
     * The position the replay reaches over the FULL history, with no fresh-start
     * boundary — "if this strategy had always been running on this name, would it
     * be holding it right now, and since when?"
     *
     * Kept apart from [lastPositionState] because they answer different questions
     * and routinely disagree. That one is what the watcher may ACT on; this one is
     * context for a person deciding whether a candidate is worth a real order.
     * Merging them would make a name the watcher cannot alert on look like one it
     * is about to.
     */
    @Volatile
    var lastHistoricalState: PosState? = null
        private set

    /** The newest bar the last [freshEntrySignal] evaluated, whatever it concluded.
     *  The watcher needs it to know where "from now on" starts for a new name. */
    @Volatile
    var lastRunBarTs: Long = 0L

    /**
     * The local turning points on the bars the last check ran over.
     *
     * Another side channel, for the same reason as the two above: the candles are
     * in hand for a fraction of a millisecond and re-fetching them to answer a
     * second question would double the watcher's bandwidth for arithmetic that
     * costs nothing. Read it exactly as the others are read — matched against the
     * symbol you asked about, because another thread may have run since.
     */
    var lastLevels: ManualWatchStore.Levels? = null
        private set

    /**
     * The closed candles the last [freshEntrySignal] ran on, with their bar
     * length. Another side channel for the same reason as the ones above: the
     * signal tracker needs this stock's hourly MACD and would otherwise download
     * the very same bars a second time, every pass, for every stock.
     */
    @Volatile
    var lastCandles: Triple<String, Long, List<CandleData>>? = null
        private set

    /**
     * @param entriesFromTs the watcher's own FRESH-START boundary — no entry may
     *   open before it, so the replay arrives flat at the moment the name joined
     *   the watch list rather than inheriting a position from weeks of history.
     *
     *   Without it a candidate the strategy happened to be long on could stay mute
     *   for weeks: the alert fires only on a position opened on the NEWEST bar, and
     *   a trade opened in August is never that. The algos already work this way —
     *   this is the same rule, applied to the same engine, for the same reason.
     */
    fun freshEntrySignal(
        ctx: Context, ns: String, symbol: String, exchange: String = "",
        entriesFromTs: Long = 0L
    ): FreshEntry? {
        lastPositionState = null
        lastHistoricalState = null
        lastLevels = null
        lastCandles = null
        val sym = symbol.trim().uppercase()
        if (sym.isBlank()) return null
        val cfg = StrategyLabStore.activeConfig(ctx, ns)
        val plan = FeedPlan.plan(cfg, ns)
        if (!plan.ok) { Log.w(TAG, "[$ns] $sym watcher feed unusable: ${plan.problem}"); return null }
        val ex = exchange.trim().ifBlank { Spaces.exchangeOf(ns) }
        val res = fetchCandlesFor(ctx, ns, sym, ex, plan)
        if (res !is YahooFinanceClient.CandleResult.Success) return null
        val nowSec = System.currentTimeMillis() / 1000
        val closed = res.candles.filter {
                FeedDelay.isComplete(it.timestamp, plan.sourceSec, nowSec, GRACE_SEC, FeedDelay.secFor(ns))
            }
        val candles = sanitizeCandles(closed, nowSec, plan.sourceSec).clean
        lastCandles = Triple(sym, plan.sourceSec, candles)
        if (candles.size < 50) return null
        // BEFORE THE ENGINE RUNS, so it survives every early return below. The
        // levels are a fact about the price history and do not depend on whether
        // a signal fired — and every one of those returns is a case where the
        // panel still has a row to draw.
        lastLevels = try {
            IntradayLevels.from(sym, candles, (plan.sourceSec / 60L).toInt())
        } catch (e: Exception) { null }
        val run = StrategyLabEngine.runLiveClosed(sym, candles, cfg, plan.sourceSec, entriesFromTs)
        lastRunBarTs = run.lastBarTs

        // ── WOULD IT BE IN THIS TRADE, HAD IT ALWAYS BEEN WATCHING? ─────────────
        //
        // A DIFFERENT QUESTION from the one above, and the answer often differs.
        // The run above starts flat at the moment this name joined the watch list,
        // deliberately — that is what stops a seat inheriting a phantom position.
        // But it also means the app cannot say whether the strategy would be
        // RIDING this name right now off the full history, which is the honest
        // macro read on a candidate: a name the strategy would have been long in
        // for weeks is a very different proposition from one it would be flat on.
        //
        // Costs no download. The candles are already fetched and sanitised; this
        // is a second pass of pure arithmetic over the same array, and it is
        // skipped entirely when there is no boundary to differ from.
        lastHistoricalState = try {
            val hr = if (entriesFromTs <= 0L) run
                     else StrategyLabEngine.runLiveClosed(sym, candles, cfg, plan.sourceSec, 0L)
            hr.trades.lastOrNull { it.status == "OPEN" }?.let {
                PosState(sym, true, it.entryTs, it.entryPrice, it.pctReturn,
                    fresh = (hr.lastBarTs > 0L && it.entryTs == hr.lastBarTs))
            }
        } catch (e: Exception) {
            Log.w(TAG, "[$ns] $sym full-history check failed", e); null
        }

        if (run.error != null) return null
        // FRESH means the position opened on the bar the engine just evaluated.
        // An OPEN trade from an older bar is a signal that already came and went
        // unacted-on; alerting on it every tick would be a standing nag, not news.
        val t = run.trades.lastOrNull { it.status == "OPEN" } ?: return null
        // NOT FRESH = the strategy is already long from an earlier bar, so there is
        // no new entry to announce. Recorded rather than silently dropped — see
        // [lastPositionState], which the watcher stores so the list can SHOW that a
        // name is mid-position instead of leaving it looking inexplicably quiet.
        lastPositionState = PosState(sym, true, t.entryTs, t.entryPrice, t.pctReturn,
            fresh = (run.lastBarTs > 0L && t.entryTs == run.lastBarTs))
        if (run.lastBarTs <= 0L || t.entryTs != run.lastBarTs) return null
        return FreshEntry(
            symbol = sym, entryTs = t.entryTs, entryPrice = t.entryPrice,
            entryReason = t.entryReason, lastBarTs = run.lastBarTs,
            lastClose = candles.last().close,
            entryTfMin = plan.entryMin, exitTfMin = plan.exitMin)
    }

    data class LatestBar(val ts: Long, val close: Double,
                        /** Computed from the same candles, because the sweep that
                         *  asks for this bar already pays for them. */
                        val levels: ManualWatchStore.Levels? = null)

    /**
     * The newest COMPLETED bar for a symbol on this config's feed.
     *
     * Exists for the forced buy, which has no signal to anchor to and therefore
     * needs some honest reference point. Deliberately the last CLOSED bar rather
     * than the live tick: every rule downstream is evaluated on completed bars,
     * and anchoring a position to a half-formed one would make its very first
     * exit check measure from a price that never finally existed.
     */
    fun latestClosedBar(ctx: Context, ns: String, symbol: String, exchange: String = ""): LatestBar? {
        val sym = symbol.trim().uppercase()
        if (sym.isBlank()) return null
        return try {
            val cfg = StrategyLabStore.activeConfig(ctx, ns)
            val plan = FeedPlan.plan(cfg, ns)
            if (!plan.ok) return null
            val ex = exchange.trim().ifBlank { Spaces.exchangeOf(ns) }
            val res = fetchCandlesFor(ctx, ns, sym, ex, plan)
            if (res !is YahooFinanceClient.CandleResult.Success) return null
            val nowSec = System.currentTimeMillis() / 1000
            val closed = res.candles.filter {
                FeedDelay.isComplete(it.timestamp, plan.sourceSec, nowSec, GRACE_SEC, FeedDelay.secFor(ns))
            }
            val candles = sanitizeCandles(closed, nowSec, plan.sourceSec).clean
            val last = candles.lastOrNull() ?: return null
            LatestBar(last.timestamp, last.close,
                try { IntradayLevels.from(sym, candles, (plan.sourceSec / 60L).toInt()) }
                catch (e: Exception) { null })
        } catch (e: Exception) {
            Log.w(TAG, "[$ns] $sym latest-bar lookup failed", e); null
        }
    }

    /**
     * Do this config's exit rules fire on a position the ENGINE did not open?
     *
     * The replay manages what it decided to buy. A stock bought by hand — because
     * the seats were full, or because you simply wanted it — is invisible to that:
     * the replay arrives flat, has nothing to exit, and the position keeps only
     * its hard stop. This asks the same rules about your position instead, using
     * your fill price and your entry time.
     *
     * Read-only. It reports a verdict; selling is the caller's decision, exactly
     * as it is for every other exit in this file.
     */
    fun adoptedExitCheck(
        ctx: Context, ns: String, symbol: String, exchange: String = "",
        entryPrice: Double, entryTs: Long
    ): StrategyLabEngine.AdoptedExit {
        val sym = symbol.trim().uppercase()
        if (sym.isBlank() || entryPrice <= 0.0 || entryTs <= 0L)
            return StrategyLabEngine.AdoptedExit(false, note = "no position to judge")
        return try {
            val cfg = StrategyLabStore.activeConfig(ctx, ns)
            val plan = FeedPlan.plan(cfg, ns)
            if (!plan.ok) return StrategyLabEngine.AdoptedExit(false, note = "feed unusable")
            val ex = exchange.trim().ifBlank { Spaces.exchangeOf(ns) }
            val res = fetchCandlesFor(ctx, ns, sym, ex, plan)
            if (res !is YahooFinanceClient.CandleResult.Success)
                return StrategyLabEngine.AdoptedExit(false, note = "no price data")
            val nowSec = System.currentTimeMillis() / 1000
            val closed = res.candles.filter {
                FeedDelay.isComplete(it.timestamp, plan.sourceSec, nowSec, GRACE_SEC, FeedDelay.secFor(ns))
            }
            val candles = sanitizeCandles(closed, nowSec, plan.sourceSec).clean
            if (candles.size < 50) return StrategyLabEngine.AdoptedExit(false, note = "warming up")
            StrategyLabEngine.adoptedExit(candles, cfg, entryPrice, entryTs, plan.sourceSec)
        } catch (e: Exception) {
            Log.w(TAG, "[$ns] $sym adopted exit check failed", e)
            StrategyLabEngine.AdoptedExit(false, note = e.message ?: "check failed")
        }
    }

    /** Build the per-bar "why did/didn't it trade" review for one enrolled symbol
     *  (recent window). Read-only; uses the same engine indicators as the live loop. */
    fun reviewPayload(ctx: Context, ns: String, symbol: String): StrategyLabEngine.ReviewPayload {
        val cfg = StrategyLabStore.activeConfig(ctx, ns)
        val en = StrategyLabStore.load(ctx, ns).enrolled.firstOrNull { it.symbol.equals(symbol, true) }
        val exchange = en?.exchange ?: Spaces.exchangeOf(ns)
        val plan = FeedPlan.plan(cfg, ns)
        val candles = fetchClosed(ns, symbol, exchange, plan)
        if (candles.size < 50)
            return StrategyLabEngine.ReviewPayload(symbol.uppercase(),
                error = if (candles.isEmpty()) "no data — check the symbol" else "warming up — only ${candles.size} bars")
        // Wider window (was 220) so the review chart's zoom/pan has real history
        // to explore — the on-device SVG defaults to a recent sub-window and lets
        // the user zoom out toward the full span.
        // Stamp the ledger's tracking start onto the payload. The engine cannot
        // know it — it replays price history and nothing else — but the chart is
        // misleading without it: a seated name can show a long unbroken green run
        // while its book is empty, because every one of those trades entered before
        // tracking began and was discarded. The line says which side of that
        // boundary you are looking at.
        // ── THE GATE'S SIDE OF THE STORY ────────────────────────────────────
        //
        // Recomputed here, not read from the gate's own records: it keeps a single
        // latest slope per name, never a history, so the only way to show WHEN a
        // name was admitted is to derive it the same way the gate does — the same
        // daily resample, the same slope series, the same smoothing.
        //
        // That makes it a RECONSTRUCTION, consistent with everything else on this
        // chart, and it is labelled as one rather than passed off as an audit.
        val gate = gateVerdictSeries(ctx, ns, candles, symbol)
        return StrategyLabEngine.reviewPayload(symbol.uppercase(), candles, cfg, plan.sourceSec, maxBars = 700)
            .copy(baselineTs = StrategyLabStore.baselineFor(ctx, symbol, ns),
                  gateApplies = gate.applies, gateDays = gate.days,
                  gateOpen = gate.open, gateState = gate.state, gateNote = gate.note,
                  realFills = realFillsFor(ctx, ns, symbol.uppercase()))
    }

    /**
     * The orders this algo actually placed for one name, from the broker ledger.
     *
     * Read per broker because each keeps its own, and deliberately NOT from the
     * paper ledger: that records what the strategy decided, and the whole point of
     * these markers is to separate a decision from a fill.
     */
    private fun realFillsFor(ctx: Context, ns: String, sym: String):
            List<StrategyLabEngine.RealFill> = try {
        val base = Spaces.baseOf(ns)
        val out = ArrayList<StrategyLabEngine.RealFill>()
        when (base) {
            "binance" -> com.signalscope.app.data.BinanceLiveStore.load(ctx, ns).trades
                .filter { it.symbol.equals(sym, true) && it.liveAvgBuyPrice > 0.0 }
                .forEach {
                    out.add(StrategyLabEngine.RealFill(
                        entryTs = it.entryTs, entryPrice = it.liveAvgBuyPrice,
                        exitTs = it.exitTs ?: 0L, exitPrice = it.liveAvgSellPrice,
                        pnl = it.livePnlQuote, open = it.status == "OPEN"))
                }
            "de", "us" -> com.signalscope.app.data.T212LiveStore.load(ctx).trades
                .filter {
                    Spaces.baseOf(it.ns) == base && it.ns == ns &&
                        it.symbol.equals(sym, true) && it.liveEntryPrice > 0.0
                }
                .forEach {
                    out.add(StrategyLabEngine.RealFill(
                        entryTs = it.entryTs, entryPrice = it.liveEntryPrice,
                        // The SELL time is when the order went, not a bar — good
                        // enough to place a marker, and the only time there is.
                        exitTs = if (it.liveExitPrice > 0.0) it.sellAt / 1000L else 0L,
                        exitPrice = it.liveExitPrice,
                        open = it.status == "OPEN"))
                }
            "in" -> com.signalscope.app.data.ZerodhaLiveStore.load(ctx).trades
                .filter { it.ns == ns && it.symbol.equals(sym, true) && it.entryPrice > 0.0 }
                .forEach {
                    out.add(StrategyLabEngine.RealFill(
                        entryTs = it.paperTradeId.substringAfterLast('-').toLongOrNull() ?: 0L,
                        entryPrice = it.entryPrice,
                        exitPrice = it.exitPrice,
                        open = it.status == "OPEN"))
                }
        }
        out.filter { it.entryTs > 0L }
    } catch (e: Exception) {
        Log.w(TAG, "[$ns] real fills for $sym failed", e); emptyList()
    }

    private data class GateSeries(val applies: Boolean, val days: List<Long>,
                                  val state: List<Int>, val note: String) {
        /** Kept for anything still reading a yes/no. */
        val open: List<Boolean> get() = state.map { it == 2 }
    }

    /**
     * Was this name admissible, day by day — and if not, WHY not?
     *
     * THE BAND USED TO BE WITHHELD FROM RANKED ALGOS, and the reason was sound:
     * with ranking on, "not held" can mean "out-ranked" rather than "refused",
     * and painting both red states the wrong reason with total confidence. But
     * withholding it meant the one question the chart exists to answer — could
     * this entry have been taken at all — went unanswered everywhere except the
     * handful of algos that rank nothing.
     *
     * The fix is a third colour rather than silence. A ranked algo can say
     * exactly which of the two it was, because the gate records WHEN each name
     * held a seat: the spans are a fact about what happened, not a reconstruction.
     *
     *   SEATED      the name held a seat that day — entries were possible
     *   OUT-RANKED  slope positive, but every seat was taken by a better name
     *   REFUSED     slope at or below zero — no entry, whatever the rules said
     *
     * WHAT IS RECONSTRUCTED AND WHAT IS RECORDED, kept distinct because they
     * deserve different trust. The slope is recomputed from today's prices, so it
     * is a reconstruction and may differ slightly from the number the gate saw at
     * the time. The seat spans are read from the gate's own enrollment history —
     * that part is a record.
     */
    private fun gateVerdictSeries(ctx: Context, ns: String,
                                  candles: List<CandleData>, symbol: String): GateSeries {
        val none = GateSeries(false, emptyList(), emptyList(), "")
        return try {
            if (!Spaces.isBucket(ns)) return none
            val b = com.signalscope.app.data.BucketStore.get(ctx, ns) ?: return none
            if (b.mode != com.signalscope.app.data.BucketStore.Mode.GATE_3B) return none
            val g = com.signalscope.app.data.MacdGateStore.load(ctx, ns)
            if (!g.enabled) return none
            val (days, closes) = GateBacktest.toDaily(candles)
            if (days.size < 30) return none
            val slopes = GateBacktest.slopeSeries(closes, g.smoothing)

            if (!g.useScreenerSelection) {
                // Every name owns a seat permanently, so the slope IS the whole
                // answer and the middle state cannot arise.
                return GateSeries(true, days.toList(), slopes.map { if (it > 0.0) 2 else 0 },
                    "Green = the daily gate would admit this name; red = slope at or below zero, " +
                    "so no entry could be taken however clearly the rules fired. " +
                    "Recomputed from the latest data, and it steps once a day.")
            }

            // ── RANKED: the seat spans say what the slope alone cannot ────────
            // Half-open intervals [from, to): a span that ended at noon did not
            // hold the seat for the following day, and counting it would paint a
            // green day on which nothing could have been bought.
            val sym = symbol.trim().uppercase()
            // orEmpty() DELIBERATELY: Gson builds this state through Unsafe and
            // never runs a Kotlin default, so a gate file written before the spans
            // existed arrives with them null however the type is declared. Reading
            // it unguarded would throw on exactly the oldest algos — the ones with
            // the most history worth drawing.
            val enrollments = g.enrollments.orEmpty()
            val spans = enrollments.filter { it.symbol.equals(sym, true) }
                .map { it.fromMs to (it.toMs ?: Long.MAX_VALUE) }
            val oldestSpanMs = enrollments.minOfOrNull { it.fromMs } ?: Long.MAX_VALUE
            val state = days.indices.map { i ->
                val dayStart = days[i] * 86400_000L
                val dayEnd = dayStart + 86400_000L
                val seated = spans.any { (f, t) -> f < dayEnd && t > dayStart }
                when {
                    seated -> 2
                    slopes[i] > 0.0 -> 1
                    else -> 0
                }
            }
            // BEFORE THE RECORD BEGINS, SAY SO RATHER THAN PAINT IT. The spans are
            // capped, so days older than the oldest retained one cannot be called
            // out-ranked — nothing is known about them. They keep the slope's own
            // verdict and the note carries the caveat.
            val truncated = days.firstOrNull()?.let { it * 86400_000L < oldestSpanMs } == true
            GateSeries(true, days.toList(), state,
                "Green = this name held a seat, so its entries could be taken. " +
                "Amber = the gate would have admitted it, but better-ranked names held every seat. " +
                "Red = slope at or below zero, so no entry was possible however clearly the rules fired. " +
                "Seat spans are the gate's own record; the slope is recomputed from the latest data." +
                (if (truncated) " Days before the retained seat history cannot be told apart from out-ranked, so the earliest stretch shows the slope's verdict only." else ""))
        } catch (e: Exception) {
            Log.w(TAG, "[$ns] gate verdict series failed", e); none
        }
    }

    // ── Confidence tools ────────────────────────────────────────────────────
    // PREVIEW: run the engine on each enrolled symbol's LIVE window and report
    // the trades + P&L it finds — read-only, the ledger is NOT touched.
    data class PreviewSymbol(
        val symbol: String, val ok: Boolean, val bars: Int,
        val closed: Int, val open: Int, val wins: Int,
        val totalPct: Double, val totalPnl: Double, val compoundedPct: Double,
        val signals: Int, val gated: Int, val note: String,
        val trades: List<StrategyLabEngine.Trade>
    )

    fun preview(ctx: Context, ns: String = "us"): List<PreviewSymbol> {
        val cfg = StrategyLabStore.activeConfig(ctx, ns)
        val plan = FeedPlan.plan(cfg, ns)
        val out = ArrayList<PreviewSymbol>()
        for (en in StrategyLabStore.load(ctx, ns).enrolled) {
            val sym = en.symbol.uppercase()
            val candles = fetchClosed(ns, en.symbol, en.exchange, plan)
            if (candles.size < 50) {
                out.add(PreviewSymbol(sym, false, candles.size, 0, 0, 0, 0.0, 0.0, 0.0, 0, 0,
                    if (candles.isEmpty()) "no data — check the symbol" else "warming up — only ${candles.size} bars", emptyList()))
                continue
            }
            val r = StrategyLabEngine.runLiveClosed(en.symbol, candles, cfg, plan.sourceSec)
            if (r.error != null) {
                out.add(PreviewSymbol(sym, false, candles.size, 0, 0, 0, 0.0, 0.0, 0.0, 0, 0, r.error!!, emptyList()))
                continue
            }
            val closed = r.trades.filter { it.status == "CLOSED" }
            var comp = 1.0; closed.sortedBy { it.entryTs }.forEach { comp *= 1.0 + it.pctReturn / 100.0 }
            out.add(PreviewSymbol(
                sym, true, candles.size, closed.size, r.trades.count { it.status == "OPEN" },
                closed.count { it.pctReturn > 0 }, closed.sumOf { it.pctReturn }, closed.sumOf { it.pnlNotional },
                (comp - 1.0) * 100.0, r.signals, r.gates.values.sum(), "ok", r.trades))
            try { Thread.sleep(150) } catch (_: InterruptedException) {}
        }
        return out
    }

    /**
     * Which (symbol, exchange) pairs a what-if / export should replay.
     *
     * Enrollment only happens on "Save & run", so a freshly created algo has its
     * companies listed but an EMPTY enrolled set — the tools then reported "no
     * symbols enrolled", which reads like a bug rather than "you haven't started
     * it yet". Fall back to the bucket's own symbol list where that list IS the
     * basket.
     *
     * NOT for GATE_3B: there the gate owns enrollment and the enrolled set is its
     * ≤holdN slot holders. Falling back to the candidate universe would replay
     * 106 names the strategy never actually held.
     */
    private fun replayTargets(ctx: Context, ns: String): Pair<List<Pair<String, String>>, String> {
        val enrolled = StrategyLabStore.load(ctx, ns).enrolled.map { it.symbol to it.exchange }
        if (enrolled.isNotEmpty()) return enrolled to ""
        val b = com.signalscope.app.data.BucketStore.get(ctx, ns)
        val isGate = b?.mode == com.signalscope.app.data.BucketStore.Mode.GATE_3B
        if (!isGate && b != null && b.symbols.isNotEmpty()) {
            val ex = Spaces.exchangeOf(ns)
            return b.symbols.map { it to ex } to ""
        }
        val why = if (isGate)
            "The daily gate hasn't filled any slots yet — press Save & run, then let the evaluation finish."
        else "This algo isn't started yet: add companies and press Save & run."
        return emptyList<Pair<String, String>>() to why
    }

    // ── TRADES CSV EXPORT (parity debugging) ────────────────────────────────
    // Flat, one-row-per-trade dump of what the engine did over the CURRENT live
    // window, so a disagreement with the Python backtester can be diffed trade
    // by trade instead of argued about from summary percentages.
    //
    // Deliberate choices:
    //  • UTC ISO timestamps ("2026-08-03 20:00:00+00:00") — pandas parses them
    //    unambiguously whatever the venue's local session is.
    //  • Config context (frames, slippage, warmup, notional, feed interval) is
    //    repeated on EVERY row, so the file stays self-describing once it leaves
    //    the phone and can't be diffed against the wrong settings.
    //  • Open trades are included with empty exit columns — a missing open
    //    position is itself a common source of mismatch.
    data class TradesCsv(
        val ok: Boolean, val note: String = "", val fileName: String = "",
        val content: String = "", val trades: Int = 0, val symbols: Int = 0,
        val fromTs: Long = 0L, val toTs: Long = 0L
    )

    private val UTC_FMT: java.time.format.DateTimeFormatter =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssxxx", java.util.Locale.US)

    private fun utcTime(tsSec: Long?): String =
        if (tsSec == null || tsSec <= 0L) ""
        else java.time.Instant.ofEpochSecond(tsSec).atZone(java.time.ZoneOffset.UTC).format(UTC_FMT)

    private fun csvCell(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n'))
            "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun csvNum(d: Double, dp: Int): String = String.format(java.util.Locale.US, "%.${dp}f", d)

    fun tradesCsv(ctx: Context, ns: String): TradesCsv {
        val cfg = StrategyLabStore.activeConfig(ctx, ns)
        val plan = FeedPlan.plan(cfg, ns)
        if (!plan.ok) return TradesCsv(false, plan.problem ?: "config cannot clear warmup")
        val (targets, why) = replayTargets(ctx, ns)
        if (targets.isEmpty()) return TradesCsv(false, why)
        val exitTf = if (cfg.multiTimeframe) cfg.exitBars else cfg.resample
        val algoMode = com.signalscope.app.data.BucketStore.get(ctx, ns)?.mode?.name ?: "BARE"

        val sb = StringBuilder()
        // data_from/data_to/bars_source/bars_engine ride on every row on purpose:
        // the single most diagnostic fact in a mismatch is "how much history did
        // each side actually see". Filename-only metadata is lost the moment rows
        // get pasted or concatenated, and the trade timestamps span only first→last
        // TRADE, which is narrower than the fetched window and cannot stand in for it.
        sb.append("symbol,status,entry_time,entry_ts,entry_price,exit_time,exit_ts,exit_price,")
        sb.append("pct_return,pnl_notional,bars_held,entry_reason,exit_reason,")
        sb.append("resample,exit_bars,slippage_pct,warmup_bars,notional,feed_interval,")
        sb.append("data_from,data_to,bars_source,bars_engine,algo_mode,layer\n")

        var n = 0; var syms = 0
        var first = Long.MAX_VALUE; var last = 0L
        for ((symbol, exchange) in targets) {
            val candles = fetchClosed(ns, symbol, exchange, plan)
            if (candles.size < 50) continue
            val r = StrategyLabEngine.runLiveClosed(symbol, candles, cfg, plan.sourceSec)
            if (r.error != null) continue
            syms++
            first = minOf(first, candles.first().timestamp)
            last = maxOf(last, candles.last().timestamp)
            // Per-symbol context: the window THIS symbol was replayed over, the raw
            // bars fetched, and the bars the engine evaluated after resampling.
            // `layer` is the one that stops a reader mis-diagnosing this file.
            // THIS EXPORT IS THE ENTRY/EXIT ENGINE ONLY — it replays each symbol
            // standalone at a FLAT cfg.notional. It deliberately does NOT run the
            // 3b portfolio layer (seats, per-seat compounding, daily screening,
            // slope gate, seat rotation), so:
            //   • pnl_notional will always equal pct_return × notional/100
            //   • only the CURRENTLY enrolled names appear (for 3b: today's seats)
            // Both of those look exactly like "mode 1 on a fixed symbol list" to an
            // outside reader, which is a wrong conclusion about how the app is
            // configured. algo_mode reports what the algo is ACTUALLY set to.
            val ctxCols = "," + csvCell(cfg.resample) + "," + csvCell(exitTf) + "," +
                cfg.slippagePct + "," + cfg.warmupBars + "," + cfg.notional + "," + plan.interval +
                "," + utcTime(candles.first().timestamp) + "," + utcTime(candles.last().timestamp) +
                "," + candles.size + "," + r.bars + "," + algoMode + ",engine_only"
            for (t in r.trades.sortedBy { it.entryTs }) {
                sb.append(csvCell(symbol.uppercase())).append(',')
                    .append(t.status).append(',')
                    .append(utcTime(t.entryTs)).append(',').append(t.entryTs).append(',')
                    .append(csvNum(t.entryPrice, 8)).append(',')
                    .append(utcTime(t.exitTs)).append(',').append(t.exitTs ?: "").append(',')
                    .append(t.exitPrice?.let { csvNum(it, 8) } ?: "").append(',')
                    .append(csvNum(t.pctReturn, 6)).append(',')
                    .append(csvNum(t.pnlNotional, 6)).append(',')
                    .append(t.barsHeld).append(',')
                    .append(csvCell(t.entryReason)).append(',')
                    .append(csvCell(t.exitReason))
                    .append(ctxCols).append('\n')
                n++
            }
            // A symbol that produced NOTHING still gets a row. "Android saw 83 days
            // of SAP and found 0 trades" is a finding; silently omitting the symbol
            // makes it look like it was never scanned at all.
            if (r.trades.isEmpty()) {
                // fields 3..13 (entry_time … exit_reason) are blank: 11 empty cells.
                sb.append(csvCell(symbol.uppercase())).append(",NO_TRADES")
                    .append(",".repeat(11))
                    .append(ctxCols).append('\n')
            }
            try { Thread.sleep(120) } catch (_: InterruptedException) {}
        }
        if (syms == 0) return TradesCsv(false, "No symbol had enough usable history to run.")
        val d = { ts: Long -> if (ts <= 0L) "na" else
            java.time.Instant.ofEpochSecond(ts).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString() }
        val fname = "signalscope_trades_${ns}_${d(if (first == Long.MAX_VALUE) 0L else first)}_${d(last)}_${plan.interval}.csv"
        return TradesCsv(true, "", fname, sb.toString(), n, syms,
            if (first == Long.MAX_VALUE) 0L else first, last)
    }

    // ── "What would this algo have made?" ───────────────────────────────────
    // Replays the CURRENT config over the SAME live window the engine actually
    // sees (whatever FeedPlan fetches — no CSV files needed), so the answer is
    // "had I switched this on when this data begins, where would I be now?".
    // Read-only: the ledger, watermarks and gate are untouched.
    //
    // Capital is split EQUALLY across the algo's names and each sleeve compounds
    // on its own, so the portfolio return is the mean of the per-symbol
    // compounded returns. Names that produced no trade still count (their sleeve
    // simply earned 0) — dropping them would flatter the result.
    data class Hypothetical(
        val ok: Boolean, val note: String = "",
        val symbols: Int = 0, val traded: Int = 0,
        val closed: Int = 0, val wins: Int = 0,
        val compoundedPct: Double = 0.0, val pnl: Double = 0.0, val capital: Double = 0.0,
        val fromTs: Long = 0L, val toTs: Long = 0L, val spanDays: Double = 0.0,
        val interval: String = "", val entryTf: String = "", val exitTf: String = "",
        val perSymbol: List<PerSym> = emptyList(),
        // ── PORTFOLIO LAYER (populated only when the algo runs mode 3b) ────────
        // "engine_only" = per-symbol returns averaged, no seats, no rotation —
        // which is all this panel used to report even for a 3b algo, and why its
        // number disagreed with the 3b backtest. "portfolio_3b" = the real
        // walk-forward: screener ranking, seat handover, per-seat compounding.
        val layer: String = "engine_only",
        val holdN: Int = 0,
        val cagrPct: Double = 0.0,
        val maxDrawdownPct: Double = 0.0,
        val injected: Double = 0.0,
        val finalValue: Double = 0.0,
        val cashDaysPct: Double = 0.0,
        val liquidations: Int = 0,
        val t0: String = "", val lastDay: String = "",
        val universe: Int = 0,
        val slotSeries: List<GateBacktest.SlotSample> = emptyList()
    )
    data class PerSym(val symbol: String, val closed: Int, val compoundedPct: Double, val note: String)

    fun hypothetical(ctx: Context, ns: String, capital: Double): Hypothetical {
        // One replay at a time. A second concurrent run doubles the request rate
        // into the same provider limiter that makes replays slow in the first
        // place, so both then crawl — the worst possible response to "it's slow".
        if (!whatIfBusy.compareAndSet(false, true))
            return Hypothetical(false,
                "A replay is already running. Wait for it to finish, or cancel it first.")
        whatIfCancel = false
        try {
            return hypotheticalInner(ctx, ns, capital)
        } finally {
            whatIfBusy.set(false)
            whatIfCancel = false
        }
    }

    private fun hypotheticalInner(ctx: Context, ns: String, capital: Double): Hypothetical {
        // Sized from THIS algo's universe, so a 487-name Indian replay gets the
        // time it needs while a 2-coin crypto one keeps the short deadline.
        val universeSize = try {
            com.signalscope.app.data.BucketStore.get(ctx, ns)?.symbols?.size ?: 0
        } catch (e: Exception) { 0 }
        val budget = Budget(universeSize)
        val cfg = StrategyLabStore.activeConfig(ctx, ns)
        val plan = FeedPlan.plan(cfg, ns)
        if (!plan.ok) return Hypothetical(false, plan.problem ?: "config cannot clear warmup")

        // A 3b algo is TWO layers: the engine decides WHEN, the gate decides WHICH.
        // Replaying only the engine (below) answers a question nobody asked — it
        // averages per-symbol returns over today's seats, with no rotation and no
        // compounding, which is why this panel used to disagree with the 3b
        // backtest. Run the real portfolio layer instead.
        val bucket = com.signalscope.app.data.BucketStore.get(ctx, ns)
        if (bucket?.mode == com.signalscope.app.data.BucketStore.Mode.GATE_3B)
            return hypothetical3b(ctx, ns, cfg, plan, bucket, capital, budget)

        val (syms, why) = replayTargets(ctx, ns)
        if (syms.isEmpty()) return Hypothetical(false, why)

        var first = Long.MAX_VALUE; var last = 0L
        var closedAll = 0; var winsAll = 0; var traded = 0
        val per = ArrayList<PerSym>()
        val comps = ArrayList<Double>()
        // Progress on THIS path too. It was only wired into the 3b walk, so a
        // Manual/Monthly algo — which can still be replaying 100+ names — showed a
        // bare spinner with no counter and looked stuck next to a 3b algo's "9/106".
        for ((i, pair) in syms.withIndex()) {
            if (budget.shouldStop()) break
            val (symbol, exchange) = pair
            wprog(ns, i, syms.size)
            val sym = symbol.uppercase()
            val candles = fetchClosed(ns, symbol, exchange, plan)
            if (candles.size < 50) {
                per.add(PerSym(sym, 0, 0.0, "not enough history"))
                budget.consecutiveFailures++; continue
            }
            val r = StrategyLabEngine.runLiveClosed(symbol, candles, cfg, plan.sourceSec)
            if (r.error != null) {
                per.add(PerSym(sym, 0, 0.0, r.error!!))
                budget.consecutiveFailures++; continue
            }
            budget.consecutiveFailures = 0
            first = minOf(first, candles.first().timestamp)
            last = maxOf(last, candles.last().timestamp)
            val closed = r.trades.filter { it.status == "CLOSED" }
            var comp = 1.0
            for (t in closed.sortedBy { it.entryTs }) comp *= 1.0 + t.pctReturn / 100.0
            val compPct = (comp - 1.0) * 100.0
            comps.add(compPct)
            closedAll += closed.size
            winsAll += closed.count { it.pctReturn > 0 }
            if (closed.isNotEmpty()) traded++
            per.add(PerSym(sym, closed.size, compPct, ""))
            try { Thread.sleep(120) } catch (_: InterruptedException) {}
        }
        budget.reason?.let { r ->
            return Hypothetical(false, "Stopped after ${budget.elapsedSec()}s — $r. " +
                "Got through ${comps.size} of ${syms.size} names.")
        }
        if (comps.isEmpty()) return Hypothetical(false, "No symbol had enough usable history.")
        val portfolioPct = comps.average()
        val spanDays = if (last > first) (last - first) / 86_400.0 else 0.0
        return Hypothetical(
            ok = true, symbols = comps.size, traded = traded,
            closed = closedAll, wins = winsAll,
            compoundedPct = portfolioPct, pnl = capital * portfolioPct / 100.0, capital = capital,
            fromTs = if (first == Long.MAX_VALUE) 0L else first, toTs = last, spanDays = spanDays,
            interval = plan.interval, entryTf = cfg.resample,
            exitTf = if (cfg.multiTimeframe) cfg.exitBars else cfg.resample,
            perSymbol = per)
    }

    /**
     * MODE 3B WHAT-IF on the LIVE feed — the same walk-forward the 🧪 backtest
     * runs, but fed by freshly fetched candles instead of stored CSVs. This is
     * what makes the slot-worth curve available outside the CSV path: slotSeries
     * is produced by GateBacktest.run, which nothing on the live side used to call.
     *
     * Ranking needs the WHOLE universe, not the seats currently filled. The
     * question is "what would these five seats have earned", and seats rotate —
     * replaying only today's holdings would bake in survivorship (they are today's
     * winners, chosen with hindsight) and would show no handovers at all, which is
     * precisely the curve we want to see.
     *
     * Memory discipline matches handleGateBacktest: exactly one symbol's intraday
     * series is alive at a time (fetch → engine → daily+slope → drop), so a 100+
     * name universe never holds 100 × 8000 bars simultaneously.
     */
    // ── PRE-FLIGHT FEASIBILITY ──────────────────────────────────────────────
    // Answered from local state ONLY (config + plan + stores, no network), so the
    // UI can refuse in milliseconds instead of after a full universe fetch.
    //
    // The gap this closes: FeedPlan validates the ENGINE's warmup and nothing
    // else. A 60min/30min config passes that check comfortably (240 warmup bars ÷
    // 9 bars/day ≈ 27 trading days needed, ~41 available) — but its 30-minute exit
    // frame pins the feed to Yahoo's 30m interval, which serves only 60 calendar
    // days. The 3b walk derives its DAILY screener series by squashing that same
    // engine feed into days, so it can never reach the 90-day screener lookback
    // and T0 never exists. The live gate is unaffected because it fetches its own
    // daily feed (ScreenerRunner.fetchUniverseBars) with years of history — which
    // is why the gate panel can look perfectly healthy while the replay cannot run.
    data class Feasibility(
        val ok: Boolean,               // false = cannot be computed at all
        val warn: Boolean = false,     // true = will run, but the answer is weak
        val title: String = "",
        val detail: String = "",
        val fix: String = ""
    )

    /** Runway beyond the screener lookback below which a 3b replay is technically
     *  computable but too short to read anything into. */
    private const val USEFUL_RUNWAY_DAYS = 90

    /** Join the non-null notes into one line (empty when there is nothing to say). */
    private fun joinNotes(vararg parts: String?): String =
        parts.filterNotNull().joinToString(" · ")

    fun portfolioFeasibility(ctx: Context, ns: String): Feasibility {
        val cfg = StrategyLabStore.activeConfig(ctx, ns)
        val plan = FeedPlan.plan(cfg, ns)
        if (!plan.ok) return Feasibility(false,
            title = "This config can't produce a signal",
            detail = plan.problem ?: "The configured frames need more history than the feed provides.",
            fix = "Coarsen the entry frame or lower the warmup.")

        val bucket = com.signalscope.app.data.BucketStore.get(ctx, ns)
        if (bucket == null || bucket.mode != com.signalscope.app.data.BucketStore.Mode.GATE_3B)
            return Feasibility(true)          // engine-only replay: no screener needed

        // The minimum only applies when the SCREENER is doing the choosing. 3b is
        // three separable parts — the daily gate, the selection, and per-seat
        // compounding — and with selection off the ranking is never consulted at
        // all: every symbol gets its own seat and the slope alone decides. That is
        // a legitimate way to run one company, and refusing it blocked a real
        // experiment for a rule belonging to a part that was switched off.
        val selectionOn = try {
            com.signalscope.app.data.MacdGateStore.load(ctx, ns).useScreenerSelection
        } catch (e: Exception) { true }
        if (selectionOn && bucket.symbols.distinct().size < 2) return Feasibility(false,
            title = "Not enough companies to rank",
            detail = "Mode 3b ranks a universe against itself; this algo has " +
                     "${bucket.symbols.distinct().size}.",
            fix = "Add a second company, or switch off \"Rank and pick the best\" on the " +
                  "card to run the daily gate on this one name alone.")

        // The screener is NEVER the blocker any more — when the engine feed is too
        // shallow to rank on, the replay fetches its own deep daily series exactly
        // as the live gate does. What remains genuinely bounded is the TRADES: they
        // can only come from the engine feed, so that is the measurable window.
        val span = plan.capCalendarDays
        val warmCalendar = Math.ceil(plan.warmupTradingDays * 365.0 / 252.0).toInt()
        val tradableCalendarDays = span - warmCalendar
        val tradableTradingDays = (tradableCalendarDays * 252.0 / 365.0).toInt()

        if (tradableCalendarDays < 5) return Feasibility(false,
            title = "No room left to trade",
            detail = "The ${plan.interval} feed spans $span days and this config burns about " +
                     "$warmCalendar of them warming up before it can signal at all.",
            fix = "Coarsen the entry frame or lower the warmup so signals have room to fire.")

        if (!engineFeedCoversScreener(plan)) return Feasibility(true, warn = true,
            title = "Short measurable window (~$tradableTradingDays trading days)",
            detail = "This config's ${plan.exitMin}-minute exit frame limits the price feed to $span " +
                     "days, and roughly $warmCalendar of those go on warmup — so only about " +
                     "$tradableTradingDays trading days of activity can be measured. Ranking is fine: " +
                     "the replay pulls its own multi-year daily feed for that, just like the live gate. " +
                     "It is the trades that are limited.",
            fix = "It will run, and no yearly rate will be shown — a span this short cannot support one. " +
                  "For a real track record use a config whose finer frame is 60 minutes or coarser " +
                  "(e.g. 240min/120min → 730 days).")

        return Feasibility(true)
    }

    // ── SEPARATE DAILY FEED FOR THE SCREENER ────────────────────────────────
    // The ranking window and the signal window are independent requirements. The
    // live gate already treats them that way; the replay used to squash the engine
    // candles into days, which capped the screener at whatever history the
    // intraday interval happened to offer.
    //
    // COST CONTROL — this is a SECOND request per symbol on a ~106-name universe,
    // so it is only paid when it actually buys something:
    //   1. Skipped entirely when the engine feed already spans the lookback (the
    //      240/120 German champion gets 730 days, so nothing extra is fetched).
    //   2. Cached in memory for [DAILY_CACHE_TTL_MS], so pressing the bulb and then
    //      the portfolio export costs ONE universe fetch, not two.
    //   3. Paced adaptively with backoff, and rate-limited names are retried once
    //      at the end rather than silently dropped.
    // The two passes are run SEQUENTIALLY, not interleaved: total requests are the
    // same either way, but one steady request per interval is gentler on Yahoo's
    // limiter than two back-to-back every interval.
    private const val DAILY_CACHE_TTL_MS = 6 * 60 * 60 * 1000L
    private const val DAILY_PACE_MIN_MS = 90L
    private const val DAILY_PACE_MAX_MS = 4_000L

    private val dailyCache = HashMap<String, Pair<Long, Map<String, List<com.signalscope.app.screener.DailyBar>>>>()

    /** Does this config's engine feed already cover the screener's lookback? */
    private fun engineFeedCoversScreener(plan: FeedPlan.Plan): Boolean {
        val lookback = com.signalscope.app.screener.ScreenerConfig().lookbackDays.toInt()
        return plan.capCalendarDays >= lookback + USEFUL_RUNWAY_DAYS
    }

    /**
     * Daily (split-adjusted) bars for the ranking universe. Returns an empty map if
     * nothing could be fetched, in which case callers fall back to squashing the
     * engine feed — a short screener window beats no answer at all.
     */
    private fun fetchDailyUniverse(
        ns: String, roster: List<String>, budget: Budget
    ): Map<String, List<com.signalscope.app.screener.DailyBar>> {
        val key = Spaces.baseOf(ns) + "|" + roster.sorted().joinToString(",")
        synchronized(dailyCache) {
            dailyCache[key]?.let { (at, bars) ->
                if (System.currentTimeMillis() - at < DAILY_CACHE_TTL_MS) return bars
            }
        }
        val space = when (Spaces.baseOf(ns)) {
            "binance" -> "BN"; "de" -> "DE"; "in" -> "IN"; else -> "US"
        }
        val out = LinkedHashMap<String, List<com.signalscope.app.screener.DailyBar>>()
        val retry = ArrayList<String>()
        var pace = DAILY_PACE_MIN_MS
        fun pull(sym: String, queueOnLimit: Boolean): Boolean {
            val r = try {
                com.signalscope.app.screener.ScreenerRunner.fetchDailyBarsFor(space, sym)
            } catch (e: Exception) {
                Log.w(TAG, "daily fetch $sym failed: ${e.message}"); null
            }
            if (r == null) {                       // rate limited or hard failure
                pace = (pace * 2).coerceAtMost(DAILY_PACE_MAX_MS)
                budget.consecutiveFailures++
                if (queueOnLimit) retry.add(sym)
                return false
            }
            if (r.size >= 10) out[sym.uppercase()] = r
            budget.consecutiveFailures = 0
            pace = maxOf(DAILY_PACE_MIN_MS, (pace * 0.95).toLong())
            return true
        }
        for ((i, sym) in roster.withIndex()) {
            if (budget.shouldStop()) break
            wprog(ns, i, roster.size * 2)          // first half of the progress bar
            pull(sym, queueOnLimit = true)
            try { Thread.sleep(pace) } catch (_: InterruptedException) {}
        }
        if (retry.isNotEmpty() && !budget.shouldStop()) {
            Log.i(TAG, "[$ns] daily feed: retrying ${retry.size} rate-limited names")
            try { Thread.sleep(3_000) } catch (_: InterruptedException) {}
            for (sym in retry) {
                if (budget.shouldStop()) break
                pull(sym, queueOnLimit = false)
                try { Thread.sleep(pace) } catch (_: InterruptedException) {}
            }
        }
        if (out.isNotEmpty()) synchronized(dailyCache) {
            dailyCache[key] = Pair(System.currentTimeMillis(), out)
        }
        return out
    }

    /** Fetched + engine-run universe, ready for the portfolio walk. */
    private class GateData(
        val data: List<GateBacktest.SymbolData>,
        val first: Long, val last: Long,
        val skipped: Int, val rosterSize: Int,
        val barsSource: Int, val barsDaily: Int,
        /** Earliest epoch-day the walk may deploy (engine data start + warmup), or
         *  null when the daily series came from the engine feed and no clamp is
         *  needed. */
        val walkFrom: Long?,
        /** True when the screener ran on its own deep daily feed. */
        val separateDailyFeed: Boolean
    )

    /**
     * Fetch every roster name, run the engine on it, reduce to daily+slope, and
     * DROP the candles — peak memory is one symbol's intraday series regardless of
     * universe size. Shared by the what-if panel and the portfolio CSV export so
     * the two can never diverge on what they fed the walk-forward.
     */
    private fun buildGateData(
        ctx: Context, ns: String, cfg: StrategyLabConfig, plan: FeedPlan.Plan,
        roster: List<String>, smoothing: Int, budget: Budget
    ): GateData {
        val ex = Spaces.exchangeOf(ns)
        // Only pay for a second feed when the engine's own is too shallow to rank on.
        val needDaily = !engineFeedCoversScreener(plan)
        val daily: Map<String, List<com.signalscope.app.screener.DailyBar>> =
            if (needDaily) fetchDailyUniverse(ns, roster, budget) else emptyMap()
        val usingDaily = daily.isNotEmpty()

        val data = ArrayList<GateBacktest.SymbolData>()
        var first = Long.MAX_VALUE; var last = 0L
        var skipped = 0; var barsSource = 0; var barsDaily = 0
        // When the daily pass ran it owned the first half of the progress bar.
        val base = if (needDaily) roster.size else 0
        val total = if (needDaily) roster.size * 2 else roster.size
        for ((i, sym) in roster.withIndex()) {
            if (budget.shouldStop()) break
            wprog(ns, base + i, total)
            try {
                val candles = fetchClosed(ns, sym, ex, plan)
                if (candles.size < 50) { skipped++; budget.consecutiveFailures++; continue }
                val r = StrategyLabEngine.runLiveClosed(sym, candles, cfg, plan.sourceSec)
                if (r.error != null) { skipped++; budget.consecutiveFailures++; continue }
                budget.consecutiveFailures = 0
                first = minOf(first, candles.first().timestamp)
                last = maxOf(last, candles.last().timestamp)
                barsSource += candles.size
                val ext = daily[sym.uppercase()]
                // The screener's series gets the same split repair as the engine's — an unadjusted
                // action corrupts trailing return, volatility and the daily-MACD kick-out slope.
                val sd = if (ext != null && ext.size >= 10)
                    GateBacktest.buildWithDaily(sym,
                        LongArray(ext.size) { ext[it].date.toEpochDay() },
                        StrategyLabEngine.backAdjustCloses(DoubleArray(ext.size) { ext[it].close }),
                        r.trades, smoothing)
                else GateBacktest.build(sym, StrategyLabEngine.backAdjust(candles), r.trades, smoothing)
                barsDaily += sd.days.size
                data.add(sd)
            } catch (e: Exception) { skipped++; budget.consecutiveFailures++ }
            try { Thread.sleep(120) } catch (_: InterruptedException) {}
        }
        wprog(ns, total, total)

        // With a deep daily series the screener can rank from years back, but the
        // engine still only has its own window — so confine the walk to that, plus
        // the bars the engine burns warming up before it can signal at all.
        // Without this the walk would report real gains spread over a span in which
        // most of the time no trade was even possible.
        val walkFrom: Long? = if (!usingDaily || first == Long.MAX_VALUE) null else {
            val warmCalendarDays = Math.ceil(plan.warmupTradingDays * 365.0 / 252.0).toLong()
            first / 86_400L + warmCalendarDays
        }
        return GateData(data, if (first == Long.MAX_VALUE) 0L else first, last,
            skipped, roster.size, barsSource, barsDaily, walkFrom, usingDaily)
    }

    private fun hypothetical3b(
        ctx: Context, ns: String, cfg: StrategyLabConfig, plan: FeedPlan.Plan,
        bucket: com.signalscope.app.data.BucketStore.Bucket, capital: Double, budget: Budget
    ): Hypothetical {
        // Defence in depth: the UI checks this before calling, but a stale WebView
        // must not be able to start a 100-name fetch that provably cannot finish.
        val feas = portfolioFeasibility(ctx, ns)
        if (!feas.ok) return Hypothetical(false, "${feas.title} — ${feas.detail} ${feas.fix}".trim())

        val gate = com.signalscope.app.data.MacdGateStore.load(ctx, ns)
        val roster = bucket.symbols.distinct()
        val gd = buildGateData(ctx, ns, cfg, plan, roster, gate.smoothing, budget)
        val data = gd.data
        val first = gd.first; val last = gd.last; val skipped = gd.skipped
        // An aborted run has a TRUNCATED universe. 3b ranks names against each
        // other, so scoring 40 of 106 is not a partial answer — it is a different
        // strategy. Refuse rather than return a plausible-looking wrong number.
        budget.reason?.let { r ->
            return Hypothetical(false, "Stopped after ${budget.elapsedSec()}s — $r. " +
                "Fetched ${data.size} of ${roster.size} companies; 3b ranks the whole " +
                "universe, so a partial fetch cannot be scored. Try again.")
        }
        // Same rule as the feasibility check: two names are needed to RANK, and
        // ranking is optional. With selection off the replay walks each name on
        // its own seat under the daily gate, which one company satisfies fine.
        val rankingOn = try {
            com.signalscope.app.data.MacdGateStore.load(ctx, ns).useScreenerSelection
        } catch (e: Exception) { true }
        if (data.isEmpty())
            return Hypothetical(false,
                "No company in this algo returned usable history.")
        if (rankingOn && data.size < 2)
            return Hypothetical(false,
                "Only ${data.size} of ${roster.size} companies returned usable history — " +
                "need 2+ to rank, or switch off \"Rank and pick the best\" to run the gate alone.")

        // Seat size follows the LIVE gate's own notional so the curve's euros mean
        // the same thing as the slots shown above it, not a re-derived guess.
        // Seat COUNT honours the screener-selection switch: with selection off every
        // ranked name gets a seat, so the gate alone decides.
        val seats = com.signalscope.app.data.MacdGateStore.effectiveHoldN(gate, data.size)
        val perSlot = if (gate.notionalPerSlot > 0) gate.notionalPerSlot
                      else capital / seats.coerceAtLeast(1)
        val rep = GateBacktest.run(data, GateBacktest.Params(
            mode = "3B", holdN = seats, smoothing = gate.smoothing,
            notional = perSlot, confirmDays = gate.confirmDays,
            useScreenerSelection = gate.useScreenerSelection,
            slippagePct = cfg.slippagePct), gd.walkFrom)
        if (rep.error != null) return Hypothetical(false, rep.error!!)

        // The WALK's span, not the fetch window: with a separate daily feed the
        // fetched history is far longer than the period actually measured.
        val spanDays = if (rep.spanDays > 0) rep.spanDays
                       else if (last > first) (last - first) / 86_400.0 else 0.0
        return Hypothetical(
            ok = true,
            note = joinNotes(
                if (skipped > 0) "$skipped of ${roster.size} names had no usable history" else null,
                if (gd.separateDailyFeed) "ranked on a separate multi-year daily feed" else null
            ),
            symbols = data.size,
            traded = rep.perSymbol.count { it.trades > 0 },
            closed = rep.closedCount,
            wins = Math.round(rep.closedCount * rep.winRatePct / 100.0).toInt(),
            compoundedPct = rep.compoundedPct,
            pnl = rep.finalValue - rep.injected,
            capital = rep.injected,
            fromTs = first, toTs = last, spanDays = spanDays,
            interval = plan.interval, entryTf = cfg.resample,
            exitTf = if (cfg.multiTimeframe) cfg.exitBars else cfg.resample,
            perSymbol = rep.perSymbol.map { PerSym(it.symbol, it.trades, it.sumPct, "") },
            layer = "portfolio_3b",
            holdN = seats,
            cagrPct = rep.cagrPct,
            maxDrawdownPct = rep.maxDrawdownPct,
            injected = rep.injected,
            finalValue = rep.finalValue,
            cashDaysPct = rep.cashDaysPct,
            liquidations = rep.liquidations,
            t0 = rep.t0, lastDay = rep.lastDay,
            universe = roster.size,
            slotSeries = rep.slotSeries)
    }

    // ── PORTFOLIO-LAYER CSV EXPORT ──────────────────────────────────────────
    // The engine-level `tradesCsv` cannot be used to audit 3b, and no amount of
    // extra columns would fix it: it replays each symbol independently over its
    // full history, so on the German run ~95% of its rows are trades the
    // portfolio never took, and the 5 names it covers contributed ~5% of the
    // portfolio's actual trades. Different question, different scope, different
    // capital model. Both exports are kept — this one describes the STRATEGY,
    // `tradesCsv` describes the SIGNAL and stays useful for engine debugging.
    data class CsvFile(val name: String, val content: String)
    data class PortfolioCsv(
        val ok: Boolean, val note: String = "", val runId: String = "",
        val files: List<CsvFile> = emptyList(),
        val trades: Int = 0, val enrollments: Int = 0, val equityDays: Int = 0,
        val universeSize: Int = 0, val seats: Int = 0, val symbolsTraded: Int = 0,
        val neverTraded: Int = 0
    )

    fun portfolioCsv(ctx: Context, ns: String): PortfolioCsv {
        val cfg = StrategyLabStore.activeConfig(ctx, ns)
        val plan = FeedPlan.plan(cfg, ns)
        if (!plan.ok) return PortfolioCsv(false, plan.problem ?: "config cannot clear warmup")
        val bucket = com.signalscope.app.data.BucketStore.get(ctx, ns)
            ?: return PortfolioCsv(false, "No algo configured for this space.")
        if (bucket.mode != com.signalscope.app.data.BucketStore.Mode.GATE_3B)
            return PortfolioCsv(false,
                "The portfolio export describes mode 3b; this algo runs ${bucket.mode.name}. " +
                "Use ⤓ Trades CSV for the engine-level dump.")
        val feas = portfolioFeasibility(ctx, ns)
        if (!feas.ok) return PortfolioCsv(false, "${feas.title} — ${feas.detail} ${feas.fix}".trim())

        val gate = com.signalscope.app.data.MacdGateStore.load(ctx, ns)
        val roster = bucket.symbols.distinct()
        // Same runaway guards as the replay — this export walks the identical
        // universe fetch and would otherwise grind for hours on a bad provider day.
        // Scaled by the roster for the same reason the replay is.
        val budget = Budget(roster.size)
        val gd = buildGateData(ctx, ns, cfg, plan, roster, gate.smoothing, budget)
        budget.reason?.let { r ->
            return PortfolioCsv(false, "Stopped after ${budget.elapsedSec()}s — $r. " +
                "Fetched ${gd.data.size} of ${roster.size} companies; a partial universe " +
                "cannot be ranked, so no file was written.")
        }
        val csvRankingOn = try { gate.useScreenerSelection } catch (e: Exception) { true }
        if (gd.data.isEmpty())
            return PortfolioCsv(false, "No company in this algo returned usable history.")
        if (csvRankingOn && gd.data.size < 2)
            return PortfolioCsv(false,
                "Only ${gd.data.size} of ${roster.size} names returned usable history — need 2+.")

        val seats = com.signalscope.app.data.MacdGateStore.effectiveHoldN(gate, gd.data.size)
        val perSeat = if (gate.notionalPerSlot > 0) gate.notionalPerSlot
                      else bucket.capital / seats.coerceAtLeast(1)
        val rep = GateBacktest.run(gd.data, GateBacktest.Params(
            mode = "3B", holdN = seats, smoothing = gate.smoothing,
            notional = perSeat, confirmDays = gate.confirmDays,
            useScreenerSelection = gate.useScreenerSelection,
            slippagePct = cfg.slippagePct), gd.walkFrom)
        if (rep.error != null) return PortfolioCsv(false, rep.error!!)

        fun dayStr(d: Long): String = java.time.LocalDate.ofEpochDay(d).toString()
        val dFrom = utcTime(gd.first).take(10)
        val dTo = utcTime(gd.last).take(10)
        val runId = ("sscope_${ns}_${dFrom}_${dTo}_${plan.interval}")
            .replace(Regex("[^A-Za-z0-9_.-]"), "-")
        // GateBacktest ranks with a default ScreenerConfig, so report the lookback
        // it ACTUALLY used rather than the config's override — a header that lies
        // about the run is worse than no header.
        val lookback = com.signalscope.app.screener.ScreenerConfig().lookbackDays

        // Config context repeated on every row: once rows are pasted or
        // concatenated, filename-only metadata is gone.
        val ctxCols = listOf(
            runId, "portfolio", bucket.mode.name, rep.universeSize.toString(), seats.toString(),
            gate.smoothing.toString(), gate.confirmDays.toString(), lookback.toString(),
            cfg.resample, (if (cfg.multiTimeframe) cfg.exitBars else cfg.resample),
            csvNum(cfg.slippagePct, 4), cfg.warmupBars.toString(),
            csvNum(perSeat, 2), csvNum(rep.injected, 2), plan.interval,
            dFrom, dTo, gd.barsSource.toString(), gd.barsDaily.toString()
        ).joinToString(",") { csvCell(it) }

        // ── 1. portfolio_trades ──
        val t = StringBuilder()
        t.append("run_id,layer,algo_mode,universe_size,top_n,slope_smoothing,confirm_days,lookback_days,")
        t.append("resample,exit_bars,slippage_pct,warmup_bars,notional_per_seat,injected,feed_interval,")
        t.append("data_from,data_to,bars_source,bars_engine,")
        t.append("seq,seat,symbol,enrolled_from,entry_time,entry_price,exit_time,exit_price,")
        t.append("pct_return,seat_cap_before,seat_cap_after,pnl,entry_reason,exit_reason,forced_liquidation\n")
        for (r in rep.portfolioTrades) {
            t.append(ctxCols).append(',')
            t.append(r.seq).append(',')
            t.append(r.seat + 1).append(',')                 // seats are 1..N in the file
            t.append(csvCell(r.symbol)).append(',')
            t.append(dayStr(r.enrolledFromDay)).append(',')
            t.append(csvCell(utcTime(r.entryTs))).append(',').append(csvNum(r.entryPrice, 6)).append(',')
            t.append(csvCell(utcTime(r.exitTs))).append(',').append(csvNum(r.exitPrice, 6)).append(',')
            t.append(csvNum(r.pctReturn, 4)).append(',')
            t.append(csvNum(r.capBefore, 2)).append(',').append(csvNum(r.capAfter, 2)).append(',')
            t.append(csvNum(r.capAfter - r.capBefore, 2)).append(',')
            t.append(csvCell(r.entryReason)).append(',').append(csvCell(r.exitReason)).append(',')
            t.append(if (r.forcedLiquidation) "true" else "false").append('\n')
        }

        // ── 2. enrollments ──
        val e = StringBuilder()
        e.append("run_id,seat,symbol,enrolled_from,enrolled_to,screener_rank,screener_score,")
        e.append("daily_slope_at_entry,eject_reason,trades_in_span,pnl_in_span\n")
        for (r in rep.enrollments) {
            e.append(csvCell(runId)).append(',')
            e.append(r.seat + 1).append(',').append(csvCell(r.symbol)).append(',')
            e.append(dayStr(r.fromDay)).append(',')
            e.append(r.toDay?.let { dayStr(it) } ?: "").append(',')
            e.append(r.screenerRank).append(',').append(csvNum(r.screenerScore, 6)).append(',')
            e.append(csvNum(r.slopeAtEntry, 8)).append(',')
            e.append(csvCell(r.ejectReason)).append(',')
            e.append(r.trades).append(',').append(csvNum(r.pnl, 2)).append('\n')
        }

        // ── 3. equity (drawdown cannot be derived from trades alone) ──
        val q = StringBuilder()
        q.append("run_id,date,total_value,cash_seats,seats_in_position")
        for (i in 1..seats) q.append(",seat").append(i).append("_cap")
        q.append('\n')
        for (r in rep.equity) {
            q.append(csvCell(runId)).append(',').append(dayStr(r.day)).append(',')
            q.append(csvNum(r.totalValue, 2)).append(',')
            q.append(r.cashSeats).append(',').append(r.seatsInPosition)
            for (i in 0 until seats) q.append(',').append(csvNum(r.seatCaps.getOrElse(i) { 0.0 }, 2))
            q.append('\n')
        }

        val traded = rep.portfolioTrades.map { it.symbol.uppercase() }.distinct().size
        val idle = rep.enrollments.count { it.trades == 0 }
        return PortfolioCsv(
            ok = true,
            note = if (gd.skipped > 0) "${gd.skipped} of ${roster.size} names had no usable history" else "",
            runId = runId,
            files = listOf(
                CsvFile("${runId}_portfolio_trades.csv", t.toString()),
                CsvFile("${runId}_enrollments.csv", e.toString()),
                CsvFile("${runId}_equity.csv", q.toString())),
            trades = rep.portfolioTrades.size,
            enrollments = rep.enrollments.size,
            equityDays = rep.equity.size,
            universeSize = rep.universeSize,
            seats = seats,
            symbolsTraded = traded,
            neverTraded = idle)
    }

    data class LiveLedgerCsv(
        val ok: Boolean, val note: String = "", val runId: String = "",
        val files: List<CsvFile> = emptyList(),
        val trades: Int = 0, val spans: Int = 0, val orphans: Int = 0,
        val unknown: Int = 0, val warmupViolations: Int = 0
    )

    /**
     * THE LIVE LEDGER AND ITS SEAT SPANS — what actually happened on this phone.
     *
     * Distinct from [portfolioCsv], and the distinction is the whole point.
     * [portfolioCsv] re-fetches history and runs a fresh simulation, taking only
     * PARAMETERS from the live gate; it can therefore never show a defect that
     * exists solely in the live path, because it never reads the live path. A live
     * orphan is invisible to it by construction.
     *
     * This export reads only stored state: the paper ledger and the seat-occupancy
     * spans. Columns deliberately mirror the replay's files so the same invariant
     * checks run unmodified — in particular "every trade sits inside its own
     * seat's enrollment span", which is exactly the orphan detector.
     *
     * TIMESTAMPS are the same ISO form the replay files use — "2025-01-23
     * 22:00:00+00:00", UTC, via the shared formatter. Spans are held in
     * milliseconds internally and the ledger in seconds; both are converted here
     * so a join across the files never has to reconcile units OR formats.
     *
     * ORPHAN IS THREE-VALUED, not two. A trade with no covering span is only an
     * orphan if it entered AFTER the oldest span we still retain; older than that
     * and its span may simply have been evicted by the cap, so it is reported
     * "unknown". Every row carries the retention boundary so a checker can make
     * the same distinction without guessing.
     */
    fun liveLedgerCsv(ctx: Context, ns: String): LiveLedgerCsv {
        val gate = com.signalscope.app.data.MacdGateStore.load(ctx, ns)
        val lab = StrategyLabStore.load(ctx, ns)
        val spans = gate.enrollments ?: emptyList()
        if (spans.isEmpty() && lab.trades.isEmpty())
            return LiveLedgerCsv(false,
                "Nothing recorded yet for this algo — no paper trades and no seat history.")
        val bucket = com.signalscope.app.data.BucketStore.get(ctx, ns)
        val runId = ("sscope_live_${ns}_${java.time.LocalDate.now()}")
            .replace(Regex("[^A-Za-z0-9_.-]"), "-")
        fun ms2s(ms: Long): Long = ms / 1000L

        // Which span, if any, was running for this symbol when the trade opened.
        fun spanFor(sym: String, entrySec: Long): com.signalscope.app.data.MacdGateStore.Enrollment? =
            spans.firstOrNull {
                val to = it.toMs
                it.symbol.equals(sym, true) &&
                    ms2s(it.fromMs) <= entrySec &&
                    (to == null || entrySec <= ms2s(to))
            }

        // ── 1. live trades, with the seat that owned them (blank ⇒ no span) ──
        // The retention boundary rides on every row so the orphan verdict can be
        // reproduced exactly, including which trades are simply too old to judge.
        val oldestMs = com.signalscope.app.data.MacdGateStore.oldestSpanStart(gate)
        val oldestSec = if (oldestMs > 0L) ms2s(oldestMs) else 0L
        var orphanCount = 0
        var unknownCount = 0
        val head = listOf(runId, "live", ns, bucket?.name ?: "",
            if (oldestSec > 0L) utcTime(oldestSec) else "")
            .joinToString(",") { csvCell(it) }
        // WARMUP, AUDITED WITHOUT COUNTING BARS. Each symbol's engine pass recorded
        // the timestamp of its first entry candle past warmup; a trade entered
        // before that is a violation and nothing else can explain it. Deriving the
        // same answer by counting bars between two dates needs session hours and
        // holidays — on a 6.25-hour India session that arithmetic overstates
        // elapsed bars badly, which is exactly how a warmup audit reaches a wrong
        // verdict about correct code.
        var warmupViolations = 0
        val t = StringBuilder()
        t.append("run_id,layer,ns,algo,oldest_span_start,seat,symbol,enrolled_from,entry_time,entry_price,")
        t.append("exit_time,exit_price,pct_return,pnl,bars_held,status,orphan,before_warmup,entry_reason,exit_reason\n")
        for (tr in lab.trades.sortedBy { it.entryTs }) {
            val sp = spanFor(tr.symbol, tr.entryTs)
            val ft = lab.statuses[tr.symbol.uppercase()]?.firstTradableTs ?: 0L
            val early = ft > 0L && tr.entryTs < ft
            if (early) warmupViolations++
            // Predates everything we kept ⇒ unjudgeable, not guilty.
            val tooOld = sp == null && (oldestSec <= 0L || tr.entryTs < oldestSec)
            if (sp == null) { if (tooOld) unknownCount++ else orphanCount++ }
            t.append(head).append(',')
            t.append(sp?.let { (it.seat + 1).toString() } ?: "").append(',')
            t.append(csvCell(tr.symbol)).append(',')
            t.append(sp?.let { utcTime(ms2s(it.fromMs)) } ?: "").append(',')
            t.append(csvCell(utcTime(tr.entryTs))).append(',').append(csvNum(tr.entryPrice, 6)).append(',')
            t.append(csvCell(utcTime(tr.exitTs))).append(',')
            t.append(csvNum(tr.exitPrice ?: 0.0, 6)).append(',')
            t.append(csvNum(tr.pctReturn, 4)).append(',')
            t.append(csvNum(tr.pnlNotional, 2)).append(',')
            t.append(tr.barsHeld).append(',')
            t.append(csvCell(tr.status)).append(',')
            t.append(if (sp != null) "false" else if (tooOld) "unknown" else "true").append(',')
            t.append(if (ft <= 0L) "unknown" else if (early) "true" else "false").append(',')
            t.append(csvCell(tr.entryReason)).append(',').append(csvCell(tr.exitReason)).append('\n')
        }

        // ── 2. seat spans, with what each one actually traded ──
        val e = StringBuilder()
        e.append("run_id,layer,ns,algo,oldest_span_start,seat,symbol,enrolled_from,enrolled_to,screener_rank,screener_score,")
        e.append("daily_slope_at_entry,eject_reason,still_open,trades_in_span,pnl_in_span\n")
        for (sp in spans.sortedBy { it.fromMs }) {
            val to = sp.toMs
            val inSpan = lab.trades.filter {
                it.symbol.equals(sp.symbol, true) &&
                    ms2s(sp.fromMs) <= it.entryTs &&
                    (to == null || it.entryTs <= ms2s(to))
            }
            e.append(head).append(',')
            e.append(sp.seat + 1).append(',').append(csvCell(sp.symbol)).append(',')
            e.append(csvCell(utcTime(ms2s(sp.fromMs)))).append(',')
            e.append(csvCell(to?.let { utcTime(ms2s(it)) } ?: "")).append(',')
            e.append(sp.rank).append(',')
            e.append(sp.score?.let { csvNum(it, 6) } ?: "").append(',')
            e.append(sp.slopeAtEntry?.let { csvNum(it, 8) } ?: "").append(',')
            e.append(csvCell(sp.ejectReason ?: "")).append(',')
            e.append(if (to == null) "true" else "false").append(',')
            e.append(inSpan.size).append(',')
            e.append(csvNum(inSpan.sumOf { it.pnlNotional }, 2)).append('\n')
        }

        // ── 3. seats as they stand right now ──
        val s = StringBuilder()
        s.append("run_id,layer,ns,algo,oldest_span_start,seat,symbol,capital,since,neg_days,flagged\n")
        gate.slots.forEachIndexed { i, sl ->
            s.append(head).append(',')
            s.append(i + 1).append(',').append(csvCell(sl.symbol ?: "")).append(',')
            s.append(csvNum(sl.capital, 2)).append(',')
            s.append(csvCell(utcTime(ms2s(sl.since)))).append(',')
            s.append(sl.negDays).append(',')
            s.append(if (sl.flagged) "true" else "false").append('\n')
        }

        // ── 4. the window the engine actually ran, per symbol ──
        // The CONFIGURED frame and the EFFECTIVE frame are separate columns on
        // purpose: a frame finer than the fetched data is clamped up to the data's
        // own granularity, so a run can legitimately happen on a coarser frame
        // than the config names. Until both were reported, the two were
        // indistinguishable — and every downstream bar count inherits the
        // difference.
        val w = StringBuilder()
        w.append("run_id,layer,ns,algo,symbol,configured_entry_frame,configured_exit_frame,")
        w.append("base_granularity_sec,effective_entry_tf_sec,effective_exit_tf_sec,")
        w.append("source_bars,entry_bars,warmup_bars,first_tradable_time,last_run,ok,note\n")
        val cfgNow = try { StrategyLabStore.activeConfig(ctx, ns) } catch (e: Exception) { null }
        for (stt in lab.statuses.values.sortedBy { it.symbol }) {
            w.append(head).append(',')
            w.append(csvCell(stt.symbol)).append(',')
            w.append(csvCell(cfgNow?.resample ?: "")).append(',')
            w.append(csvCell(cfgNow?.let { if (it.multiTimeframe) it.exitBars else it.resample } ?: "")).append(',')
            w.append(stt.baseSec).append(',')
            w.append(stt.entryTfSec).append(',').append(stt.exitTfSec).append(',')
            w.append(stt.bars).append(',').append(stt.entryBars).append(',').append(stt.warmBars).append(',')
            w.append(csvCell(if (stt.firstTradableTs > 0L) utcTime(stt.firstTradableTs) else "")).append(',')
            w.append(csvCell(if (stt.lastRunAt > 0L) utcTime(stt.lastRunAt / 1000L) else "")).append(',')
            w.append(if (stt.ok) "true" else "false").append(',')
            w.append(csvCell(stt.note)).append('\n')
        }

        val note = buildString {
            if (warmupViolations > 0)
                append("$warmupViolations trade(s) entered BEFORE their symbol's first " +
                    "tradable bar — warmup was not honoured. ")
            if (orphanCount > 0)
                append("$orphanCount trade(s) entered after the retained history began " +
                    "and sit outside every seat span — orphans. ")
            if (unknownCount > 0)
                append("$unknownCount trade(s) predate the oldest retained span and " +
                    "cannot be judged — reported unknown, NOT orphaned. ")
            if (spans.isEmpty())
                append("No seat history yet — spans are recorded from this build onward.")
        }.trim()
        return LiveLedgerCsv(true, note, runId,
            files = listOf(
                CsvFile("${runId}_live_trades.csv", t.toString()),
                CsvFile("${runId}_live_enrollments.csv", e.toString()),
                CsvFile("${runId}_live_seats.csv", s.toString()),
                CsvFile("${runId}_live_engine_window.csv", w.toString())),
            trades = lab.trades.size, spans = spans.size,
            orphans = orphanCount, unknown = unknownCount,
            warmupViolations = warmupViolations)
    }

    // BACKFILL: ingest the recent live window's trades into the ledger so P&L
    // shows immediately. These are dated in the past (clearly not live-forward);
    // the watermark is set to the latest bar so the forward loop continues cleanly.
    fun backfill(ctx: Context, ns: String = "us") {
        val cfg = StrategyLabStore.activeConfig(ctx, ns)
        val plan = FeedPlan.plan(cfg, ns)
        for (en in StrategyLabStore.load(ctx, ns).enrolled) {
            val sym = en.symbol.uppercase()
            val candles = fetchClosed(ns, en.symbol, en.exchange, plan)
            if (candles.size < 50) continue
            // THE SAME FRESH-START BOUNDARY THE LIVE LOOP USES.
            //
            // This writes to the ledger, so it has to respect the boundary or it
            // is a second door into the very state the boundary exists to prevent
            // — a seat holding trades it was never allowed to open.
            //
            // Both halves matter, exactly as in the live loop. Passing it to the
            // engine stops the replay ever opening a pre-boundary position (so it
            // arrives at the boundary flat, rather than long and unable to act);
            // the ledger skip below is the guard on what gets RECORDED.
            //
            // The skip is not redundant belt-and-braces here. The reconcile sweep
            // that repairs the live loop's mistakes only ever removes OPEN rows —
            // a pre-boundary CLOSED trade written here would never be reconsidered
            // by anything, and would sit in the record permanently.
            //
            // 0 when nothing is stamped yet (a symbol never tracked), which is the
            // original full-history behaviour and is what a first backfill wants.
            val baselineTs = StrategyLabStore.baselineFor(ctx, sym, ns)
            val r = StrategyLabEngine.runLiveClosed(en.symbol, candles, cfg, plan.sourceSec, baselineTs)
            if (r.error != null) continue
            val lastBarTs = candles.last().timestamp
            val newTrades = ArrayList<StrategyLabStore.LedgerTrade>()
            val newAudits = ArrayList<StrategyLabStore.Audit>()
            var suppressed = 0
            for (t in r.trades) {
                if (t.entryTs < baselineTs) { suppressed++; continue }   // entered before tracking began
                val id = "$sym-${t.entryTs}"
                newTrades.add(ledgerOf(id, sym, t))
                newAudits.add(StrategyLabStore.Audit("$id-BUY", symbol = sym, action = "BUY",
                    price = t.entryPrice, reason = t.entryReason, barTs = t.entryTs))
                if (t.status == "CLOSED") newAudits.add(StrategyLabStore.Audit("$sym-${t.exitTs}-SELL",
                    symbol = sym, action = "SELL", price = t.exitPrice ?: 0.0, reason = t.exitReason, barTs = t.exitTs ?: lastBarTs))
            }
            // Same engine-clock rule as the live loop: the watermark must sit on
            // the resampled bar clock or the next fresh entry reads as "old".
            StrategyLabStore.applyRunResult(ctx, sym, newTrades, newAudits,
                if (r.lastBarTs > 0L) r.lastBarTs else lastBarTs, ns)
            val inPos = r.trades.lastOrNull()?.status == "OPEN"
            StrategyLabStore.setSymbolStatus(ctx, StrategyLabStore.SymbolStatus(
                sym, System.currentTimeMillis(), ok = true, bars = candles.size, signals = r.signals,
                gated = r.gates.values.sum(), inPosition = inPos,
                // COUNTED FROM WHAT WAS WRITTEN, not from what the replay produced.
                // Those are no longer the same number once the boundary suppresses
                // some, and a note that reports the larger one would quietly claim
                // history the ledger does not contain.
                note = "backfilled ${newTrades.count { it.status == "CLOSED" }} closed trades" +
                    (if (suppressed > 0) " ($suppressed before tracking began, skipped)" else "")), ns)
            try { Thread.sleep(150) } catch (_: InterruptedException) {}
        }
        StrategyLabStore.markRun(ctx, ns)
    }
}

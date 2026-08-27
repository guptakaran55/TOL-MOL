package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.CandleData
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
            val nextClosedSec = wmNow + 2L * frameSec + GRACE_SEC
            if (now / 1000L < nextClosedSec) {
                Log.d(TAG, "[$ns] $sym no new ${frameSec / 60}min bar yet — skipping fetch")
                return
            }
        }
        val res = fetchCandlesRaw(ns, symbol, exchange, plan)
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
        val closedBars = res.candles.filter { it.timestamp + barSec + GRACE_SEC <= nowSec }
        // Data-quality guard: drop corrupt bars and measure freshness/gaps so the
        // engine never trades on a Yahoo bad tick, and the user can SEE whether
        // the feed is healthy before trusting the signals (esp. delayed Xetra data).
        val health = sanitizeCandles(closedBars, nowSec, barSec)
        val candles = health.clean
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

        val run = StrategyLabEngine.runLiveClosed(symbol, candles, cfg, barSec)
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

        if (bootstrap) {
            // FLAT START: the first run after enroll/reset does NOT adopt positions
            // the strategy is already in (those entered before you started). It
            // just stamps "tracking begins at this bar" and records nothing —
            // so "Reset + Run now" is a genuine clean start from today. Only
            // entries on bars AFTER this baseline are counted from here on.
            StrategyLabStore.applyRunResult(ctx, sym, emptyList(), emptyList(), wmBar, ns, baseline = wmBar)
        } else {
            // Ignore any trade whose ENTRY is at/before the baseline — including
            // its eventual exit — so a pre-start position can never leak in later.
            val baseline = StrategyLabStore.baselineFor(ctx, sym, ns)
            // WIND-DOWN (exit-only): symbols dropped by a rebalance while in a
            // trade take no NEW entries — the existing position rides to its algo
            // exit, then ReinvestEngine.onTradeClosed releases the capital.
            val windDown = (com.signalscope.app.data.ReinvestStore.load(ctx, ns).windingDown ?: emptyList())
                .map { it.uppercase() }.toSet()
            // Accumulate all changes, then commit in ONE atomic write (no torn state).
            val newTrades = ArrayList<StrategyLabStore.LedgerTrade>()
            val newAudits = ArrayList<StrategyLabStore.Audit>()
            for (t in run.trades) {
                if (t.entryTs <= baseline) continue   // entered before tracking began
                if (sym in windDown && t.entryTs > prevWm) continue  // exit-only: skip fresh entries entirely
                val id = "$sym-${t.entryTs}"
                val isOpen = t.status == "OPEN"
                val newEntry = t.entryTs > prevWm
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
                // Trade notifications (crypto): the user was blind to opens/closes
                // unless the app happened to be on screen. Paper channel is
                // default-importance; LIVE fills notify separately (high) from
                // inside BinanceLiveMirror when real money actually moves.
                if (Spaces.baseOf(ns) == "binance") {
                    if (newEntry) LabNotifier.paperOpen(ctx, ns, sym, t.entryPrice)
                    if (newExit) LabNotifier.paperClose(ctx, ns, sym, t.pctReturn, t.exitReason)
                }
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
                    }
                    if (newExit) {
                        T212LiveMirror.mirrorExit(ctx, id, t, t212Space, ns)
                    }
                }
                // INDIA: a plain order plus a broker-side GTT, rather than the
                // in-app ladder the T212 path needs. Same four gates, same
                // freshness rule — only a signal on the newest CLOSED bar may
                // open a position, so a catch-up replay can never buy.
                if (base == "in" && freshBar > 0L) {
                    if (newEntry && t.entryTs == freshBar && dataFresh) {
                        ZerodhaLiveMirror.mirrorEntry(ctx, id, t.symbol, t.entryPrice, ns)
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
            val liveOpenIds: Set<String> =
                if (Spaces.baseOf(ns) == "binance")
                    com.signalscope.app.data.BinanceLiveStore.load(ctx, ns).trades
                        .filter { it.status == "OPEN" }.map { it.paperTradeId }.toHashSet()
                else emptySet()
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
        return res.candles.filter { it.timestamp + plan.sourceSec + GRACE_SEC <= nowSec }
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
        return StrategyLabEngine.reviewPayload(symbol.uppercase(), candles, cfg, plan.sourceSec, maxBars = 700)
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
                val sd = if (ext != null && ext.size >= 10)
                    GateBacktest.buildWithDaily(sym,
                        LongArray(ext.size) { ext[it].date.toEpochDay() },
                        DoubleArray(ext.size) { ext[it].close },
                        r.trades, smoothing)
                else GateBacktest.build(sym, candles, r.trades, smoothing)
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
            val r = StrategyLabEngine.runLiveClosed(en.symbol, candles, cfg, plan.sourceSec)
            if (r.error != null) continue
            val lastBarTs = candles.last().timestamp
            val newTrades = ArrayList<StrategyLabStore.LedgerTrade>()
            val newAudits = ArrayList<StrategyLabStore.Audit>()
            for (t in r.trades) {
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
                note = "backfilled ${r.trades.count { it.status == "CLOSED" }} closed trades"), ns)
            try { Thread.sleep(150) } catch (_: InterruptedException) {}
        }
        StrategyLabStore.markRun(ctx, ns)
    }
}

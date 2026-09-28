package com.signalscope.app.trading

import com.signalscope.app.screener.DailyBar
import com.signalscope.app.screener.RankedSymbol
import com.signalscope.app.screener.ScreenerConfig
import com.signalscope.app.screener.StockScreener
import java.time.LocalDate

/**
 * Multi-symbol walk-forward backtest of the portfolio layers — Mode 3b (daily-
 * MACD gate, exit at natural close) and Mode 2 (monthly reinvest, MIGRATE) —
 * over locally stored CSV data. Purpose: PARITY with the user's Python lab
 * (SCREENER_REINVEST_KOTLIN_PORT §10.6–10.7, §4), so the Android port can be
 * "double-checked" against numbers the lab already produced.
 *
 * Parity rules supplied by the user (do not change without re-validating):
 *  - Daily bars are RESAMPLED FROM THE INTRADAY FILES: UTC calendar-day
 *    buckets (pandas resample("1D", label=left, closed=left) on UTC stamps),
 *    close = last bar's close, empty days dropped, and the PARTIAL TRAILING
 *    DAY IS KEPT. (The live gate deliberately DROPS the partial day — that is
 *    a no-lookahead guard for live decisions; the backtest keeps it because
 *    the Python numbers were produced that way. Both are correct.)
 *  - Native-daily datasets (bar spacing ≈ 1 day) are used as-is, no resample.
 *  - Slope = EMA(diff(EMA(c,12) − EMA(c,26)), smoothing) on daily closes,
 *    raw[0] = 0, EMA span clamped <2→2 (so smoothing 1 ≡ 2 — parity check).
 *  - §10.6.1: a Mode-2 leaver holding an open position at month-end is
 *    REALIZED at that day's close minus slippage. Mode 3b never sells
 *    mid-position — it must report ZERO liquidations (§13 self-check).
 *
 * KNOWN LIVE-vs-BACKTEST ASYMMETRIES (deliberate; recorded so nobody
 * "re-discovers" them as mystery diffs later):
 *  - RAW vs ADJUSTED closes: this harness derives daily bars from the stored
 *    CSVs (raw closes — Python parity). LIVE screening uses the split/dividend
 *    ADJUSTED Yahoo feed. Live ranks therefore differ from backtest ranks by a
 *    few tenths on dividend payers (measured: ADS, LHA). Correct on both sides.
 *  - LIVE Mode 2 does NOT flatten leavers at rebalance: a mid-trade leaver
 *    goes exit-only (windingDown) and its compounded cash is released at its
 *    NATURAL exit, split equally among the LAST rebalance's joiners
 *    (ReinvestEngine.onTradeClosed: lastJoiners, falling back to all
 *    non-winding-down holdings; no recipients → cash idles). This harness and
 *    the Python spec instead realize leavers AT month-end (the validated
 *    behaviour). Live monthly therefore diverges from its own backtest by
 *    design — a spec-compliant "flatten at rebalance" live option would need
 *    forced paper closes + real mirror sells, and must be an explicit choice.
 */
object GateBacktest {

    data class Params(
        /** False = gate only: one seat per symbol, ranking never consulted. */
        val useScreenerSelection: Boolean = true,
        val mode: String = "3B",          // "3B" or "MONTHLY"
        val holdN: Int = 5,
        val smoothing: Int = 5,
        val notional: Double = 1000.0,
        val confirmDays: Int = 1,
        val slippagePct: Double = 0.05,   // applied to mark-to-market liquidations
        val topN: Int = 20                // MONTHLY basket size
    )

    /** Per-symbol pre-processed inputs: daily series + the engine's trades. */
    class SymbolData(
        val symbol: String,
        val days: LongArray,              // epoch-day (UTC), ascending
        val closes: DoubleArray,          // daily close per day (resampled or native)
        val slope: DoubleArray,           // smoothed daily-MACD slope, aligned to days
        val trades: List<StrategyLabEngine.Trade>
    )

    data class SymbolStat(val symbol: String, val trades: Int, val sumPct: Double, val wins: Int)

    /**
     * One sample on a SLOT's equity curve — the money that seat is worth, and the
     * name it was holding at the time.
     *
     * This is the honest y-axis for an aggregate view: five seats holding a €7
     * stock and a €250 stock share no price axis, but they all share EUR. A seat
     * runs 1000 → 1034 → 1029 …, compounding across DIFFERENT companies, so the
     * curve is continuous while the ticker underneath it changes.
     *
     * Recorded only when something CHANGES (capital moved, or the seat handed over
     * to another name), plus the first/last day — a step series, not a daily dump,
     * so a 660-day run stays small enough to cross the JS bridge.
     */
    data class SlotSample(
        val day: Long,          // epoch day
        val slot: Int,          // 0-based seat index
        val symbol: String?,    // null = the seat is sitting in cash
        val capital: Double
    )

    // ════════════════════════════════════════════════════════════════════════
    // PORTFOLIO-LAYER AUDIT RECORDS
    // ════════════════════════════════════════════════════════════════════════
    // The engine-level export answers "when does MACD fire on this symbol?" and
    // is structurally unable to show what 3b did: on the German run only ~5% of
    // engine trades were ones the portfolio actually took, because 3b holds a
    // name only while it is enrolled. These three records are the portfolio's own
    // view — the trades it executed, the seat occupancies behind them, and the
    // daily equity needed to verify drawdown (which trades alone cannot give).

    /** One trade the PORTFOLIO executed — i.e. taken by a seat while it held the
     *  name. `capBefore`/`capAfter` must differ across the run; a flat notional
     *  means seats are not compounding. */
    data class PortfolioTrade(
        val seq: Int,                  // 1-based, ordered by exit
        val seat: Int,                 // 0-based seat index
        val symbol: String,
        val enrolledFromDay: Long,     // epoch day the seat took this symbol
        val entryTs: Long, val entryPrice: Double,
        val exitTs: Long, val exitPrice: Double,
        val pctReturn: Double,         // AFTER slippage, rounded once at creation
        val capBefore: Double, val capAfter: Double,
        val entryReason: String, val exitReason: String,
        val forcedLiquidation: Boolean // 3a mid-position ejection; always false in 3b
    )

    /** One seat-occupancy span. Spans that never fired ARE kept — they are a
     *  large share of enrollments and their absence is itself a bug signal. */
    data class Enrollment(
        val seat: Int,
        val symbol: String,
        val fromDay: Long,
        val toDay: Long?,              // null = still held at run end
        val screenerRank: Int,         // 1 = best of universeSize
        val screenerScore: Double,
        val slopeAtEntry: Double,      // the gate value that admitted it — must be > 0
        val ejectReason: String,       // slope_negative | still_held
        val trades: Int,
        val pnl: Double
    )

    /** One trading day of portfolio equity. `totalValue` includes idle-cash
     *  seats, so peak-relative drawdown is computed honestly from this column. */
    data class EquityDay(
        val day: Long,
        val totalValue: Double,
        val cashSeats: Int,
        val seatsInPosition: Int,
        val seatCaps: List<Double>
    )

    data class Report(
        val mode: String,
        val error: String? = null,
        val injected: Double = 0.0,
        val finalValue: Double = 0.0,         // closed-trade accounting only (§4.4)
        val openMtmAdj: Double = 0.0,         // extra value if open positions are marked at the last close
        val compoundedPct: Double = 0.0,
        val cagrPct: Double = 0.0,
        val maxDrawdownPct: Double = 0.0,     // peak-relative on the daily equity (§12.0)
        val t0: String = "", val lastDay: String = "",
        /** Calendar days the walk actually covered. Annualising a short span
         *  multiplies its noise, so the UI needs this to decide whether cagrPct is
         *  worth showing at all. */
        val spanDays: Double = 0.0,
        val closedCount: Int = 0, val winRatePct: Double = 0.0,
        val liquidations: Int = 0,            // MUST be 0 in 3B (§13)
        val liquidationAvgPct: Double = 0.0,
        val cashDaysPct: Double = 0.0,        // % of slot-days spent in cash (3B)
        val perSymbol: List<SymbolStat> = emptyList(),
        val log: List<String> = emptyList(),
        /** Per-seat equity curve in EUR, for the aggregate slot chart (3B only). */
        val slotSeries: List<SlotSample> = emptyList(),
        // ── portfolio-layer audit (3B only) ──
        val universeSize: Int = 0,               // symbols the screener RANKED, not traded
        val portfolioTrades: List<PortfolioTrade> = emptyList(),
        val enrollments: List<Enrollment> = emptyList(),
        val equity: List<EquityDay> = emptyList()
    )

    // ── shared math ─────────────────────────────────────────────────────────
    private fun ema(x: DoubleArray, span: Int): DoubleArray {
        val s = if (span < 2) 2 else span
        val a = 2.0 / (s + 1.0)
        val out = DoubleArray(x.size)
        if (x.isEmpty()) return out
        out[0] = x[0]
        for (i in 1 until x.size) out[i] = a * x[i] + (1 - a) * out[i - 1]
        return out
    }

    fun slopeSeries(closes: DoubleArray, smoothing: Int): DoubleArray {
        val e12 = ema(closes, 12); val e26 = ema(closes, 26)
        val macd = DoubleArray(closes.size) { e12[it] - e26[it] }
        val raw = DoubleArray(closes.size)
        for (i in 1 until macd.size) raw[i] = macd[i] - macd[i - 1]
        return ema(raw, smoothing)
    }

    /** UTC-day resample (close = last bar of the day; partial trailing day KEPT).
     *  Native-daily input (median spacing ≥ ~22h) is passed through unchanged. */
    fun toDaily(candles: List<com.signalscope.app.data.CandleData>): Pair<LongArray, DoubleArray> {
        if (candles.isEmpty()) return Pair(LongArray(0), DoubleArray(0))
        val gaps = (1 until candles.size).map { candles[it].timestamp - candles[it - 1].timestamp }.sorted()
        val nativeDaily = gaps.isNotEmpty() && gaps[gaps.size / 2] >= 22 * 3600L
        val map = LinkedHashMap<Long, Double>()
        for (c in candles) map[c.timestamp / 86_400L] = c.close   // last close per UTC day wins
        if (nativeDaily && map.size != candles.size) { /* duplicate-day daily data — last wins, same rule */ }
        val days = map.keys.toLongArray()
        val closes = DoubleArray(days.size); var i = 0
        for (v in map.values) closes[i++] = v
        return Pair(days, closes)
    }

    fun build(symbol: String, candles: List<com.signalscope.app.data.CandleData>,
              trades: List<StrategyLabEngine.Trade>, smoothing: Int): SymbolData {
        val (days, closes) = toDaily(candles)
        return SymbolData(symbol, days, closes, slopeSeries(closes, smoothing), trades)
    }

    /**
     * Same, but with the daily series supplied SEPARATELY instead of squashed out
     * of the engine candles.
     *
     * Why this exists: the screener's ranking window and the engine's signal
     * window are different requirements and should be fed independently — which is
     * exactly what the LIVE gate already does (it fetches its own daily series
     * while the engine runs on intraday bars). Deriving daily bars from the engine
     * feed silently capped the screener at whatever history the intraday interval
     * happened to offer, so a fine-grained config (30m ⇒ 60 days) could never
     * satisfy a 90-day lookback even though years of daily data were available for
     * the asking.
     *
     * The TRADES still come from the engine feed, so the measurable window is
     * bounded by it regardless — see the walkFrom parameter on [run].
     */
    fun buildWithDaily(symbol: String, dailyDays: LongArray, dailyCloses: DoubleArray,
                       trades: List<StrategyLabEngine.Trade>, smoothing: Int): SymbolData =
        SymbolData(symbol, dailyDays, dailyCloses, slopeSeries(dailyCloses, smoothing), trades)

    // as-of lookups (last index with day <= d), -1 if none
    private fun idxAsOf(days: LongArray, d: Long): Int {
        var lo = 0; var hi = days.size - 1; var ans = -1
        while (lo <= hi) { val m = (lo + hi) / 2; if (days[m] <= d) { ans = m; lo = m + 1 } else hi = m - 1 }
        return ans
    }

    private fun entryDay(t: StrategyLabEngine.Trade) = t.entryTs / 86_400L
    private fun exitDay(t: StrategyLabEngine.Trade): Long =
        if (t.status == "CLOSED" && t.exitTs != null) t.exitTs / 86_400L else Long.MAX_VALUE

    private fun d(day: Long): String = LocalDate.ofEpochDay(day).toString()

    // ── the walk-forward ────────────────────────────────────────────────────
    /**
     * [walkFrom] — earliest epoch-day the portfolio may be deployed, on top of the
     * screener-lookback threshold. Needed once the daily series is fetched
     * independently of the engine feed: the screener may then have 2 years of
     * history while the engine only has 60 days of candles, and starting the walk
     * at the daily series' beginning would show ~22 months of seats holding names
     * that cannot possibly trade — then divide the real gains over that whole span
     * and report a badly understated return. Passing the engine feed's own start
     * confines the measurement to the period where trades can actually exist.
     *
     * null (the default) reproduces the previous behaviour exactly, so the local
     * CSV backtest — which has one feed and is parity-verified against the Python
     * lab — is untouched.
     */
    /** @param onProgress walked-day ticks (done, total). The walk is one blocking call over
     *  hundreds of trading days; with no signal the UI cannot tell slow from hung, which is
     *  exactly how the 400-symbol NSE run read. Optional, so every existing caller is unchanged. */
    fun run(data: List<SymbolData>, p: Params, walkFrom: Long? = null,
            onProgress: ((Int, Int) -> Unit)? = null): Report {
        // Two names are needed to RANK them against each other. They are not
        // needed to run the gate: with selection off every symbol owns a seat and
        // the ranking is never consulted, which is a legitimate way to study one
        // company. The walk-forward itself is indifferent — it iterates seats.
        if (data.isEmpty()) return Report(p.mode, error = "No symbol data to run.")
        if (p.useScreenerSelection && data.size < 2)
            return Report(p.mode, error = "Need at least 2 symbol files to rank a universe.")
        val bySym = data.associateBy { it.symbol.uppercase() }
        val allDays = data.flatMap { it.days.toList() }.distinct().sorted()
        if (allDays.isEmpty()) return Report(p.mode, error = "No daily bars derived from the files.")

        // ── BACKTEST-ONLY PARITY ARTIFACT — do NOT replicate in live mode ──
        // The Python lab's pre-compute ranks only symbols that produced ≥1
        // CLOSED engine trade over the whole run. Live screening ranks the
        // FULL universe (MacdGateEngine does, correctly); this filter exists
        // here solely so the harness's rankings match the lab's bit-for-bit.
        val rankable = data.filter { sd -> sd.trades.any { t -> t.status == "CLOSED" } }
        // Screener universe (full history; computeTraits applies the as-of filter)
        val universe = LinkedHashMap<String, List<DailyBar>>()
        for (s in rankable) universe[s.symbol] =
            (s.days.indices).map { DailyBar(LocalDate.ofEpochDay(s.days[it]), s.closes[it]) }

        // PYTHON T0 CONVENTION: a FIXED deployment date — the first trading day
        // ≥ (earliest daily bar in the data + screener lookback calendar days).
        // Slots/basket start there regardless of eligibility; an all-cash
        // opening stretch counts in the CAGR span.
        val lookbackDays = ScreenerConfig().lookbackDays
        // Both conditions must hold before deployment: the screener has its
        // lookback warmed AND the engine has data to trade on.
        val t0Threshold = maxOf(allDays.first() + lookbackDays, walkFrom ?: Long.MIN_VALUE)
        // screen() returns the FULL ranked list (only the topN() helper truncates),
        // so rank + score are available for the enrollment audit — the old
        // .map { it.symbol } threw them away.
        // PREPARED ONCE, not per day. StockScreener.screen re-sorts every symbol's whole history
        // and rebuilds its SMA on every call; called once per trading day over a few hundred
        // symbols that is hundreds of thousands of redundant sorts, which is what made this look
        // hung rather than slow. Prepared does the same arithmetic off pre-sorted arrays and is
        // pinned to screen()'s exact output by StockScreenerPreparedTest.
        val screenCfg = ScreenerConfig(topN = p.topN)
        val prepared = StockScreener.Prepared(screenCfg, universe)
        fun rankedFullAsOf(day: Long): List<RankedSymbol> =
            prepared.screen(screenCfg, LocalDate.ofEpochDay(day))
        fun rankedAsOf(day: Long): List<String> = rankedFullAsOf(day).map { it.symbol }
        fun slopeAsOf(sym: String, day: Long): Double? {
            val s = bySym[sym.uppercase()] ?: return null
            val i = idxAsOf(s.days, day); return if (i >= 0) s.slope[i] else null
        }
        fun closeAsOf(sym: String, day: Long): Double? {
            val s = bySym[sym.uppercase()] ?: return null
            val i = idxAsOf(s.days, day); return if (i >= 0) s.closes[i] else null
        }
        fun inPosition(sym: String, day: Long, sinceDay: Long): Boolean =
            bySym[sym.uppercase()]?.trades?.any {
                entryDay(it) in sinceDay..day && day < exitDay(it)
            } ?: false

        val executed = LinkedHashMap<String, MutableList<Double>>()
        fun credit(sym: String, pct: Double) { executed.getOrPut(sym) { mutableListOf() }.add(pct) }
        val log = ArrayList<String>()
        fun logLine(s: String) { if (log.size < 400) log.add(s) }
        var liquidations = 0; var liqSum = 0.0
        var peak = 0.0; var maxDdPct = 0.0
        fun markEquity(v: Double) {
            if (v > peak) peak = v
            if (peak > 0) { val dd = (peak - v) / peak * 100.0; if (dd > maxDdPct) maxDdPct = dd }
        }

        var injected = 0.0
        var t0Day = -1L
        var finalValue: Double
        var cashSlotDays = 0L; var totalSlotDays = 0L

        if (p.mode.equals("MONTHLY", true)) {
            // ── Mode 2: last trading day of each month, MIGRATE (§4) ───────
            val screenDays = allDays.groupBy { LocalDate.ofEpochDay(it).let { d -> d.year * 100 + d.monthValue } }
                .values.map { it.max() }.sorted()
            val balances = LinkedHashMap<String, Double>()
            val since = HashMap<String, Long>()
            var prevScreen = -1L
            for (t in screenDays) {
                // Monthly T0 = first MONTH-END trading day ≥ the +lookback date
                // (not the first trading day — a deliberate Python convention).
                if (t < t0Threshold) continue
                if (t0Day < 0) {
                    val basket = rankedAsOf(t).take(p.topN)
                    if (basket.isEmpty()) continue
                    for (s in basket) { balances[s] = p.notional; since[s] = t }
                    injected = p.notional * basket.size; t0Day = t; prevScreen = t
                    logLine("${d(t)} INIT basket=${basket.size} injected=${"%.0f".format(injected)}")
                    markEquity(balances.values.sum()); continue
                }
                // credit trades that CLOSED in (prevScreen, t] on enrolled symbols
                for ((sym, bal0) in balances.toList()) {
                    var bal = bal0
                    val sd = since[sym] ?: continue
                    for (tr in bySym[sym.uppercase()]?.trades.orEmpty()) {
                        val xd = exitDay(tr)
                        if (tr.status != "CLOSED" || xd <= prevScreen || xd > t) continue
                        if (entryDay(tr) < sd) continue
                        bal *= 1.0 + tr.pctReturn / 100.0; credit(sym, tr.pctReturn)
                    }
                    balances[sym] = bal
                }
                markEquity(balances.values.sum())
                // rebalance at t
                val newBasket = rankedAsOf(t).take(p.topN)
                val newSet = newBasket.map { it.uppercase() }.toSet()
                val leavers = balances.keys.filter { it.uppercase() !in newSet }
                var pool = 0.0
                for (s in leavers) {
                    var bal = balances.remove(s)!!
                    val sd = since.remove(s) ?: t
                    // §10.6.1 (mode-2 flavour): an open position at month-end IS a sale.
                    val open = bySym[s.uppercase()]?.trades?.firstOrNull {
                        entryDay(it) >= sd && entryDay(it) <= t && t < exitDay(it)
                    }
                    if (open != null) {
                        val c = closeAsOf(s, t)
                        if (c != null && open.entryPrice > 0) {
                            val mtm = (c / open.entryPrice - 1.0) * 100.0 - p.slippagePct
                            bal *= 1.0 + mtm / 100.0
                            credit(s, mtm); liquidations++; liqSum += mtm
                            logLine("${d(t)} $s leaver LIQUIDATED open pos at ${"%+.2f".format(mtm)}%")
                        }
                    }
                    pool += bal
                }
                val joiners = newBasket.filter { nb -> balances.keys.none { it.equals(nb, true) } }
                if (joiners.isNotEmpty() && pool > 0) {
                    val share = pool / joiners.size
                    for (s in joiners) { balances[s] = share; since[s] = t }
                    logLine("${d(t)} rebalance: -${leavers.size} +${joiners.size} pool=${"%.0f".format(pool)}")
                }
                prevScreen = t
            }
            if (t0Day < 0) return Report(p.mode, error = "Never enough ranked symbols to initialize a basket.")
            // trailing credits after the last screen date
            for ((sym, bal0) in balances.toList()) {
                var bal = bal0; val sd = since[sym] ?: continue
                for (tr in bySym[sym.uppercase()]?.trades.orEmpty()) {
                    val xd = exitDay(tr)
                    if (tr.status != "CLOSED" || xd <= prevScreen) continue
                    if (entryDay(tr) < sd) continue
                    bal *= 1.0 + tr.pctReturn / 100.0; credit(sym, tr.pctReturn)
                }
                balances[sym] = bal
            }
            finalValue = balances.values.sum()
            markEquity(finalValue)
            var openAdj = 0.0
            for ((sym, bal) in balances) {
                val sd = since[sym] ?: continue
                bySym[sym.uppercase()]?.trades?.filter { it.status == "OPEN" && entryDay(it) >= sd }
                    ?.forEach { openAdj += bal * (it.pctReturn / 100.0) }
            }
            return finish(p, injected, finalValue, openAdj, t0Day, allDays.last(),
                executed, liquidations, liqSum, maxDdPct, 0.0, log)
        }

        // ── Mode 3B: daily loop (§10.6 order: CREDIT → EJECT/FLAG → FILL) ──
        class Slot(var symbol: String?, var capital: Double, var sinceDay: Long, var negDays: Int, var flagged: Boolean)
        val slots = ArrayList<Slot>()
        val held = HashSet<String>()
        val slotTrack = ArrayList<SlotSample>()
        val lastSlotState = HashMap<Int, Pair<String?, Double>>()
        // ── portfolio-layer audit state ──
        class OpenEnroll(val seat: Int, val symbol: String, val fromDay: Long,
                         val rank: Int, val score: Double, val slope: Double) {
            var trades = 0; var pnl = 0.0
        }
        val openEnroll = HashMap<Int, OpenEnroll>()
        val enrollDone = ArrayList<Enrollment>()
        val ptrades = ArrayList<PortfolioTrade>()
        val equityTrack = ArrayList<EquityDay>()
        fun closeEnroll(seat: Int, toDay: Long?, reason: String) {
            val e = openEnroll.remove(seat) ?: return
            enrollDone.add(Enrollment(e.seat, e.symbol, e.fromDay, toDay,
                e.rank, e.score, e.slope, reason, e.trades, e.pnl))
        }
        // Fixed T0 (Python convention): slots exist from the first trading day ≥
        // threshold even if nothing is eligible — they hold cash, and that
        // stretch counts in the CAGR span.
        val t0Fixed = allDays.firstOrNull { it >= t0Threshold }
            ?: return Report(p.mode, error =
                if (walkFrom != null && walkFrom > allDays.first() + lookbackDays)
                    "No overlap between the screener history and the engine's data window."
                else "Dataset shorter than the ${lookbackDays}-day screener lookback — no T0.")
        val walkDays = allDays.count { it >= t0Fixed }
        var walked = 0
        for (day in allDays) {
            if (day < t0Fixed) continue
            // Report every 10th day: frequent enough that a stall is obvious, rare enough that the
            // JS bridge hop is not itself a cost in a tight loop.
            if (onProgress != null && (++walked == 1 || walked % 10 == 0 || walked == walkDays)) onProgress(walked, walkDays)
            if (slots.isEmpty()) {
                repeat(p.holdN) { slots.add(Slot(null, p.notional, day, 0, false)) }
                injected = p.notional * p.holdN; t0Day = day
                markEquity(injected)
                logLine("${d(day)} INIT ${p.holdN} slots (cash) injected=${"%.0f".format(injected)}")
                // fall through — today's FILL step may enroll immediately
            }
            // 1. CREDIT closed trades of this day
            for ((si, s) in slots.withIndex()) {
                val sym = s.symbol ?: continue
                for (tr in bySym[sym.uppercase()]?.trades.orEmpty()) {
                    if (exitDay(tr) != day || tr.status != "CLOSED") continue
                    if (entryDay(tr) < s.sinceDay) continue
                    val before = s.capital
                    s.capital *= 1.0 + tr.pctReturn / 100.0; credit(sym, tr.pctReturn)
                    // This is the ONLY place a trade enters the portfolio's books:
                    // guarded by "seat holds this name" + "entered after enrollment",
                    // which is exactly the filter the engine-level export lacks.
                    ptrades.add(PortfolioTrade(
                        seq = ptrades.size + 1, seat = si, symbol = sym,
                        enrolledFromDay = s.sinceDay,
                        entryTs = tr.entryTs, entryPrice = tr.entryPrice,
                        exitTs = tr.exitTs ?: 0L, exitPrice = tr.exitPrice ?: 0.0,
                        pctReturn = tr.pctReturn,
                        capBefore = before, capAfter = s.capital,
                        entryReason = tr.entryReason, exitReason = tr.exitReason,
                        forcedLiquidation = false))
                    openEnroll[si]?.let { it.trades++; it.pnl += s.capital - before }
                }
            }
            // 2. MARK EQUITY
            markEquity(slots.sumOf { it.capital })
            // 3. EJECT / FLAG (3b: mid-trade waits for the natural close)
            for ((si, s) in slots.withIndex()) {
                val sym = s.symbol ?: continue
                val slope = slopeAsOf(sym, day)
                if (slope != null && slope > 0.0) { s.negDays = 0; s.flagged = false; continue }
                s.negDays++
                if (inPosition(sym, day, s.sinceDay)) { s.flagged = true; continue }   // wait
                if (s.negDays >= p.confirmDays) {
                    held.remove(sym.uppercase())
                    logLine("${d(day)} $sym left (slope ≤0, flat) cap=${"%.0f".format(s.capital)}")
                    closeEnroll(si, day, "slope_negative")
                    s.symbol = null; s.flagged = false; s.negDays = 0; s.sinceDay = day
                }
            }
            // 4. FILL empty slots from the ranking (never a name already held)
            if (slots.any { it.symbol == null }) {
                val ranked = rankedFullAsOf(day)
                for ((si, s) in slots.withIndex()) {
                    if (s.symbol != null) continue
                    val pickR = ranked.firstOrNull {
                        it.symbol.uppercase() !in held && (slopeAsOf(it.symbol, day) ?: -1.0) > 0.0
                    } ?: continue
                    val pick = pickR.symbol
                    s.symbol = pick; s.sinceDay = day; s.negDays = 0; s.flagged = false
                    held.add(pick.uppercase())
                    openEnroll[si] = OpenEnroll(si, pick, day, pickR.rank, pickR.score,
                        slopeAsOf(pick, day) ?: 0.0)
                    logLine("${d(day)} $pick enrolled cap=${"%.0f".format(s.capital)}")
                }
            }
            // ── slot equity curve: record only on CHANGE (capital moved or the
            // seat changed hands), so the series is a compact step function.
            for ((i, s) in slots.withIndex()) {
                val prev = lastSlotState[i]
                val changed = prev == null ||
                    prev.first != s.symbol ||
                    kotlin.math.abs(prev.second - s.capital) > 1e-9
                if (changed) {
                    slotTrack.add(SlotSample(day, i, s.symbol, s.capital))
                    lastSlotState[i] = Pair(s.symbol, s.capital)
                }
            }
            // Daily equity — one row per trading day. Drawdown CANNOT be derived
            // from the trade list alone (it moves while positions are open and
            // while seats sit in cash), so this is the column the reviewer needs.
            equityTrack.add(EquityDay(
                day = day,
                totalValue = slots.sumOf { it.capital },
                cashSeats = slots.count { it.symbol == null },
                seatsInPosition = slots.count { s -> s.symbol?.let { inPosition(it, day, s.sinceDay) } == true },
                seatCaps = slots.map { it.capital }))
            totalSlotDays += slots.size
            cashSlotDays += slots.count { it.symbol == null }
        }
        // Seats still occupied at the end are real enrollments — keep them, marked.
        for (i in slots.indices) closeEnroll(i, null, "still_held")
        // t0Day is always set: t0Fixed exists and slots are created on the first
        // day ≥ threshold regardless of eligibility (all-cash start is valid).
        if (t0Day < 0) return Report(p.mode, error = "internal: T0 never set despite fixed-T0 convention")
        finalValue = slots.sumOf { it.capital }
        var openAdj = 0.0
        for (s in slots) {
            val sym = s.symbol ?: continue
            bySym[sym.uppercase()]?.trades?.filter { it.status == "OPEN" && entryDay(it) >= s.sinceDay }
                ?.forEach { openAdj += s.capital * (it.pctReturn / 100.0) }
        }
        val cashPct = if (totalSlotDays > 0) cashSlotDays * 100.0 / totalSlotDays else 0.0
        // Close every seat's curve on the final day so the chart runs to the edge
        // of the data instead of stopping at each seat's last change.
        for ((i, s) in slots.withIndex()) slotTrack.add(SlotSample(allDays.last(), i, s.symbol, s.capital))
        return finish(p, injected, finalValue, openAdj, t0Day, allDays.last(),
            executed, liquidations, liqSum, maxDdPct, cashPct, log)
            .copy(slotSeries = slotTrack,
                  universeSize = universe.size,
                  portfolioTrades = ptrades.sortedBy { it.exitTs }
                      .mapIndexed { i, t -> t.copy(seq = i + 1) },
                  enrollments = enrollDone.sortedWith(compareBy({ it.fromDay }, { it.seat })),
                  equity = equityTrack)
    }

    private fun finish(
        p: Params, injected: Double, finalValue: Double, openAdj: Double,
        t0: Long, last: Long, executed: Map<String, List<Double>>,
        liq: Int, liqSum: Double, maxDd: Double, cashPct: Double, log: List<String>
    ): Report {
        val closed = executed.values.sumOf { it.size }
        val wins = executed.values.flatten().count { it > 0 }
        val comp = if (injected > 0) (finalValue / injected - 1.0) * 100.0 else 0.0
        val years = (last - t0) / 365.25
        val cagr = if (years > 0.038 && finalValue > 0 && injected > 0)
            (Math.pow(finalValue / injected, 1.0 / years) - 1.0) * 100.0 else 0.0
        return Report(
            mode = p.mode, injected = injected, finalValue = finalValue, openMtmAdj = openAdj,
            compoundedPct = comp, cagrPct = cagr, maxDrawdownPct = maxDd,
            t0 = d(t0), lastDay = d(last), spanDays = (last - t0).toDouble(),
            closedCount = closed,
            winRatePct = if (closed > 0) wins * 100.0 / closed else 0.0,
            liquidations = liq,
            liquidationAvgPct = if (liq > 0) liqSum / liq else 0.0,
            cashDaysPct = cashPct,
            perSymbol = executed.map { (s, l) -> SymbolStat(s, l.size, l.sum(), l.count { it > 0 }) }
                .sortedByDescending { it.sumPct },
            log = log
        )
    }
}

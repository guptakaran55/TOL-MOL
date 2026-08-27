package com.signalscope.app.trading

import kotlin.math.ceil

// ════════════════════════════════════════════════════════════════════════════
// CONFIG-DRIVEN ADAPTIVE DATA FETCHING — ANDROID_ADAPTIVE_FETCH_INSTRUCTIONS.md
// ════════════════════════════════════════════════════════════════════════════
// THE BUG THIS FIXES (§1): the runner used to fetch a hardcoded 15-min feed and
// run the 240min/120min champion on it. Yahoo caps 15m history at ~60 days →
// 60 × 3 = 180 four-hour bars, against a warmup of 240. runMtf bails with
// `cd.size <= warm`, so the German engine returned an empty result on EVERY
// tick, silently, forever. Not a strategy bug — a data-depth bug.
//
// PROOF from the one working case: Binance pages 8000 × 15m bars (no cap), i.e.
// 8000/16 = 500 four-hour bars > 240 warmup. That is precisely why crypto has
// been trading and German never did, on the identical 240/120 config.
//
// THE FIX (§2): Yahoo serves LESS history the finer the candle, from the same
// endpoint with one parameter changed. The engine only needs a base feed fine
// enough to BUILD its frames, so asking for 15m to make 4h candles burns 12× the
// available history on detail that gets blended away. Read the frames from the
// config, pick the coarsest interval that tiles evenly into them, request that.
// For 240/120 the answer is 60m → 730 days → ~2,186 four-hour bars.
//
// EVERY SPACE IS PLANNED, INCLUDING BINANCE. Crypto was initially left on a fixed
// 15m feed because it already cleared warmup. That confused "adequate" with
// "chosen". The shipped crypto champions run 60min entries / 30min exits, so the
// correct base is 30m — and the identical 8000-bar budget then covers 166 days
// instead of 83. The resampled 60/30 frames are bit-identical either way: a 60min
// bin has the same OHLC whether aggregated from 4×15m or 2×30m bars, and both
// complete at the same wall-clock instant (runLiveClosed drops partial bins, so
// source granularity cannot shift WHEN a decision fires). The 83-day ceiling on
// every crypto backtest was this exclusion, not a Binance limit — Binance caps
// nothing; only our own paging budget does.
// ════════════════════════════════════════════════════════════════════════════

object FeedPlan {

    /** interval name, minutes per bar, max history days the provider will serve. */
    data class Interval(val name: String, val minutes: Int, val capDays: Int)

    /** Yahoo's caps are imposed by the PROVIDER — ask for 15m and you get 60 days,
     *  full stop (§4.1). */
    val YAHOO_INTERVALS = listOf(
        Interval("1m",      1,      7),
        Interval("2m",      2,     60),
        Interval("5m",      5,     60),
        Interval("15m",    15,     60),
        Interval("30m",    30,     60),
        Interval("60m",    60,    730),   // the sweet spot
        Interval("90m",    90,     60),
        Interval("1d",   1440, 100_000)   // effectively unlimited
    )

    /** Klines the Binance pager gathers per symbol — the whole depth budget. */
    const val BINANCE_TARGET_BARS = 8000

    /** Calendar days that [BINANCE_TARGET_BARS] bars of [minutes] cover. Crypto
     *  trades 24/7, so 1440 minutes of bars = exactly one calendar day. */
    private fun binanceCapDays(minutes: Int): Int =
        maxOf(1, (BINANCE_TARGET_BARS.toLong() * minutes / 1440L).toInt())

    /** Binance imposes NO history cap — it serves years at any interval. The only
     *  bound is OUR paging budget, so depth is a pure function of bar size:
     *  8000 bars = 83 days at 15m, 166 at 30m, 333 at 1h, 666 at 2h, 1333 at 4h.
     *
     *  This space used to be excluded from planning, on the reasoning that
     *  "8000 × 15m already clears the 240 warmup, so selection would pick 15m
     *  anyway". Clearing warmup is not the same as choosing well, and the engine
     *  only ever needs a base fine enough to BUILD its frames — spending the
     *  budget on resolution that gets blended away is what pinned crypto to 83
     *  days. Note the selection is still bounded by the EXIT frame: a 60/30 config
     *  lands on 30m (166 days), and a 60/15 config correctly stays at 15m (83
     *  days) because nothing coarser can tile a 15-minute frame. */
    val BINANCE_INTERVALS = listOf(
        "1m" to 1, "3m" to 3, "5m" to 5, "15m" to 15, "30m" to 30,
        "1h" to 60, "2h" to 120, "4h" to 240, "6h" to 360,
        "8h" to 480, "12h" to 720, "1d" to 1440
    ).map { (name, min) -> Interval(name, min, binanceCapDays(min)) }

    fun tableFor(ns: String): List<Interval> =
        if (Spaces.baseOf(ns) == "binance") BINANCE_INTERVALS else YAHOO_INTERVALS

    const val DEFAULT_WARMUP = 240

    /**
     * §4.2 — minutes per bar for a config rule: "240min"→240, "4h"→240, "1D"→1440.
     * "" / "same" / "native" fall back to [default].
     */
    fun frameMinutes(rule: String?, default: Int?): Int? {
        if (rule == null) return default
        val r = rule.trim().lowercase()
        if (r.isEmpty() || r == "same" || r == "native") return default
        val digits = r.filter { it.isDigit() }
        return when {
            r.endsWith("d") || r.endsWith("day") -> 1440 * (digits.toIntOrNull() ?: 1).coerceAtLeast(1)
            r.endsWith("h") -> 60 * (digits.toIntOrNull() ?: 1).coerceAtLeast(1)
            else -> digits.toIntOrNull() ?: default
        }
    }

    /**
     * §4.3 — the base feed must tile EVENLY into both frames and be no coarser
     * than the finer one. Among valid candidates take the most history; break
     * ties by the coarsest (fewer rows to parse, gentler on rate limits).
     *
     * Requiring divisibility of BOTH frames (not just the finer) is stricter than
     * the spec's wording and identical on every row of its worked table — but it
     * stays correct for frames that are not integer multiples of each other
     * (e.g. 180/120, where only 60 divides both).
     *
     * [table] makes this provider-agnostic. On Yahoo the caps are irregular, so
     * the max-history rule genuinely matters (240/120 → 60m, NOT the finer 30m
     * and NOT 90m, which does not divide 240). On Binance capDays rises linearly
     * with bar size, so the same rule degenerates to "the coarsest interval that
     * tiles both frames" — which is precisely what we want there.
     */
    fun bestInterval(
        entryMin: Int, exitMin: Int, table: List<Interval> = YAHOO_INTERVALS
    ): Interval? {
        val finer = minOf(entryMin, exitMin)
        return table
            .filter { it.minutes <= finer && entryMin % it.minutes == 0 && exitMin % it.minutes == 0 }
            .maxByOrNull { it.capDays.toLong() * 100_000L + it.minutes }
    }

    /** §5.1 — session length is PER SPACE, never a global constant. */
    fun sessionMinutes(ns: String): Int = when (Spaces.baseOf(ns)) {
        "binance" -> 1440          // 24 h
        "de" -> 510                // Xetra 09:00–17:30 CET
        else -> 390                // US 09:30–16:00 ET
    }

    /**
     * §5.1 — bars per session at a frame. Do NOT "simplify" to a plain division:
     * ceil is what makes 240min give 3 bars/day on Xetra (measured 2.99), because
     * midnight-UTC bin alignment leaves a 1-hour partial leading bar (§6).
     * US measures 2.34 against ceil's 2 — running low is the SAFE direction for a
     * warmup check (it demands more history, not less), so it is left as is.
     */
    fun barsPerDay(frameMin: Int, ns: String): Int =
        if (frameMin >= 1440) 1
        else maxOf(1, ceil(sessionMinutes(ns).toDouble() / frameMin).toInt())

    /** Yahoo `range` for an interval's cap (§9). Binance ignores `range` entirely —
     *  it pages by bar count — so there the string is descriptive, for logs only. */
    private fun rangeFor(iv: Interval, binance: Boolean): String = when {
        binance -> "${BINANCE_TARGET_BARS}bars"
        iv.minutes >= 1440 -> "3y"
        else -> "${iv.capDays}d"
    }

    data class Plan(
        val interval: String,          // provider interval name ("60m" Yahoo, "2h" Binance)
        val range: String,             // Yahoo range string; descriptive on Binance
        val sourceSec: Long,           // seconds per BASE bar — replaces the old BAR_SEC
        val entryMin: Int,
        val exitMin: Int,
        val barsPerDay: Int,
        val warmupTradingDays: Double,
        val capTradingDays: Double,
        /** CALENDAR days the feed spans. The screener lookback is counted in
         *  calendar days, not trading days, so it must be compared against this —
         *  see StrategyLabRunner.portfolioFeasibility. */
        val capCalendarDays: Int = 0,
        val ok: Boolean,
        val problem: String? = null,
        /** Binance only: klines to page. 0 for range-based providers (Yahoo). */
        val targetBars: Int = 0
    )

    /**
     * Plan the ENGINE feed for a config + namespace. Raw closes (§9 CORRECTION:
     * the engine transacts at raw prices and the Python CSVs are raw — only the
     * screener/gate use the adjusted daily feed).
     */
    fun plan(cfg: StrategyLabConfig, ns: String): Plan {
        val entryMin = frameMinutes(cfg.resample, null) ?: 1440   // "" ⇒ native daily
        val exitMin = frameMinutes(cfg.exitBars, entryMin) ?: entryMin
        val warmup = cfg.warmupBars.coerceAtLeast(1)
        val perDay = barsPerDay(entryMin, ns)
        val warmupDays = warmup.toDouble() / perDay

        val binance = Spaces.baseOf(ns) == "binance"
        val table = tableFor(ns)
        val barBudget = if (binance) BINANCE_TARGET_BARS else 0
        val iv = bestInterval(entryMin, exitMin, table)
        if (iv == null) {
            val fb = table.firstOrNull { it.minutes == 15 } ?: Interval("15m", 15, 60)
            return Plan(fb.name, rangeFor(fb, binance), fb.minutes * 60L, entryMin, exitMin,
                perDay, warmupDays, 0.0, fb.capDays,
                ok = false,
                problem = "No feed interval tiles evenly into a ${entryMin}min/${exitMin}min " +
                          "frame pair — pick frames that are multiples of a supported interval.",
                targetBars = barBudget)
        }
        // §5.2 — capability check. capDays are CALENDAR days; warmup is counted in
        // TRADING days, so convert before comparing. The 252/365 ratio is a STOCK
        // calendar and must NOT be applied blindly: crypto trades every calendar
        // day, so its ratio is 1.0. Using 252/365 there understated Binance's real
        // depth by 31% — harmless in direction (it can only cause a false refusal,
        // never a silent pass) but wrong.
        val marketDaysPerYear = if (binance) 365.0 else 252.0
        val capTradingDays = iv.capDays * marketDaysPerYear / 365.0
        val ok = capTradingDays > warmupDays
        val problem = if (ok) null else
            "This config needs ${"%.0f".format(warmupDays)} trading days of history at its " +
            "${entryMin}min entry frame (warmup ${warmup} bars ÷ $perDay bars/day), but the " +
            "deepest usable feed (${iv.name}) only provides ${"%.0f".format(capTradingDays)}. " +
            "No signal could ever fire — coarsen the entry frame or lower the warmup."
        return Plan(iv.name, rangeFor(iv, binance), iv.minutes * 60L, entryMin, exitMin,
            perDay, warmupDays, capTradingDays, iv.capDays, ok, problem, targetBars = barBudget)
    }

    /** One-line human summary for logs and the Algos panel. */
    fun describe(p: Plan): String =
        "${p.interval}/${p.range} → ${p.entryMin}min entries, ${p.exitMin}min exits · " +
        "${p.barsPerDay} bars/day · warmup ${"%.0f".format(p.warmupTradingDays)}d of " +
        "${"%.0f".format(p.capTradingDays)}d available"
}

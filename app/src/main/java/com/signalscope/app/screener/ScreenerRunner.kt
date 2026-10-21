package com.signalscope.app.screener

import android.util.Log
import com.signalscope.app.data.CandleData
import com.signalscope.app.network.BinanceClient
import com.signalscope.app.network.YahooFinanceClient
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// ════════════════════════════════════════════════════════════════════════════
// SCREENER RUNNER — §7 integration adapter (data provider → StockScreener)
// ════════════════════════════════════════════════════════════════════════════
// Builds the per-symbol daily-bar universe from the app's existing fetchers and
// runs the pure screener over it:
//   space "US"/"DE"/"IN" → Yahoo SPLIT-ADJUSTED daily closes (fetchDailyAdjusted)
//   space "BN"           → Binance daily klines (crypto has no splits)
// Adjusted closes matter here: a split on raw prices would blow up the return
// and volatility traits. The trading engine keeps raw prices — only the
// screener is split-sensitive.
// ════════════════════════════════════════════════════════════════════════════

object ScreenerRunner {

    private const val TAG = "ScreenerRunner"

    data class Result(
        val ranked: List<RankedSymbol>,
        val skipped: List<String>,
        val asOf: String,
        val universeSize: Int
    )

    private fun toDailyBars(candles: List<CandleData>): List<DailyBar> =
        candles.map {
            DailyBar(
                Instant.ofEpochSecond(it.timestamp).atZone(ZoneOffset.UTC).toLocalDate(),
                it.close,
                // Guarded rather than trusted: a provider that omits the range
                // sends zeros, and a zero high would invert every channel built
                // on it. Falling back to the close makes such a bar flat, which
                // the squeeze scanner detects and refuses to score.
                high = if (it.high > 0.0) maxOf(it.high, it.close) else it.close,
                low = if (it.low > 0.0) minOf(it.low, it.close) else it.close,
                volume = it.volume.coerceAtLeast(0L).toDouble())
        }

    private fun fetchDaily(space: String, symbol: String): List<DailyBar> {
        return when (space.uppercase()) {
            "BN" -> {
                val r = BinanceClient.fetchDailyCandles(symbol, 365)
                if (r is YahooFinanceClient.CandleResult.Success) toDailyBars(r.candles) else emptyList()
            }
            else -> {
                val exchange = when (space.uppercase()) { "US" -> "NASDAQ"; "DE" -> "DE"; else -> "NSE" }
                // Screener uses SPLIT-ADJUSTED closes (a split on raw prices would
                // corrupt return/vol). Falls back to the raw cached daily feed if
                // the adjusted fetch fails, so a transient error still ranks.
                val adj = YahooFinanceClient.fetchDailyAdjusted(symbol, exchange)
                val r = if (adj is YahooFinanceClient.CandleResult.Success) adj
                        else YahooFinanceClient.fetchCandlesCached(symbol, exchange)
                if (r is YahooFinanceClient.CandleResult.Success) toDailyBars(r.candles) else emptyList()
            }
        }
    }

    /**
     * One symbol's daily bars, for callers that run their own pacing and backoff
     * (the portfolio replay fetches a ~106-name universe and must react to rate
     * limiting rather than plough on).
     *
     * Returns **null** when the provider rate-limited or errored — distinct from an
     * empty list, which means "responded, but no usable data". [fetchUniverseBars]
     * collapses both to empty because it has no backoff to feed.
     */
    fun fetchDailyBarsFor(space: String, symbol: String): List<DailyBar>? {
        return when (space.uppercase()) {
            "BN" -> when (val r = BinanceClient.fetchDailyCandles(symbol, 365)) {
                is YahooFinanceClient.CandleResult.Success -> toDailyBars(r.candles)
                is YahooFinanceClient.CandleResult.RateLimited -> null
                else -> emptyList()
            }
            else -> {
                val exchange = when (space.uppercase()) { "US" -> "NASDAQ"; "DE" -> "DE"; else -> "NSE" }
                when (val adj = YahooFinanceClient.fetchDailyAdjusted(symbol, exchange)) {
                    is YahooFinanceClient.CandleResult.Success -> toDailyBars(adj.candles)
                    is YahooFinanceClient.CandleResult.RateLimited -> null
                    // Same raw-close fallback as fetchDaily: a transient adjusted
                    // failure should still rank rather than drop the symbol.
                    else -> when (val raw = YahooFinanceClient.fetchCandlesCached(symbol, exchange)) {
                        is YahooFinanceClient.CandleResult.Success -> toDailyBars(raw.candles)
                        is YahooFinanceClient.CandleResult.RateLimited -> null
                        else -> emptyList()
                    }
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // SHARED DAILY-BAR CACHE — one download per symbol per UTC day, per space
    // ════════════════════════════════════════════════════════════════════════
    // Every 3b algo evaluates its gate once a UTC day, and each evaluation used to
    // pull its OWN copy of the universe's daily bars. Six German algos on the same
    // ~106-name DAX list meant ~636 downloads a day of byte-identical data, and
    // [fetchDailyAdjusted] has no cache of its own to absorb it.
    //
    // The bars are not what makes two algos rank differently — their SYMBOL GROUPS
    // and SEATS do. Ranking is pure computation over bars that are the same for
    // everyone. So the download is shared and the ranking is not: every caller
    // still screens its own subset and gets its own answer, from one fetch.
    //
    // Keyed by UTC day because a daily series only changes once a day. The gate
    // already discards the in-progress day before ranking, so serving a cached
    // partial today-bar cannot affect a gate decision.
    //
    // Double-checked locking per key, because namespaces are evaluated on
    // concurrent threads: without it, six algos starting together would all miss
    // and all fetch, which is the exact problem this removes. Serialising the
    // misses is a bonus — it is gentler on Yahoo's rate limit than six parallel
    // sweeps ever was.
    // ── IS A DAILY SERIES UP TO DATE? ───────────────────────────────────────
    //
    // The once-a-day steps (the 3b gates, the watcher ranking, the floor-order
    // plan) all run on the first pass of the UTC day — just after 02:00 in
    // Germany. Yahoo's daily series for German and US stocks did not yet hold the
    // previous session at that hour, so every one of those steps decided on the
    // close before last, and the cache kept serving that series all day. Seen on
    // 2 Oct 2026: both German gates and the US gate ranked on Wednesday's close
    // instead of Thursday's; the Indian ones, whose market shut hours earlier,
    // were current.
    //
    // CURRENT = the newest bar is from the last session that has already CLOSED,
    // on the exchange's own clock: today once today's close has passed, else the
    // weekday before. (Not simply "yesterday" — the US gate runs at about 20:00
    // New York time, when that same day's session is already over.)
    //
    // A holiday has no bar at all, so a missing session is accepted once it is
    // twelve hours past its close: a provider that is merely late has caught up
    // long before that, and a day with no trading never will.

    private const val HOLIDAY_GRACE_SEC = 12 * 3600L

    private fun prevWeekday(d: LocalDate): LocalDate {
        var x = d.minusDays(1)
        while (x.dayOfWeek.value >= 6) x = x.minusDays(1)
        return x
    }

    /** The last session that has closed by [now], on the exchange's calendar. */
    fun lastClosedSession(now: java.time.ZonedDateTime, close: java.time.LocalTime): LocalDate {
        val d = now.toLocalDate()
        return if (d.dayOfWeek.value <= 5 && !now.toLocalTime().isBefore(close)) d else prevWeekday(d)
    }

    /** Pure form of [dailyDataCurrent], for tests. */
    fun dailyDataCurrentAt(newest: LocalDate?, now: java.time.ZonedDateTime, close: java.time.LocalTime): Boolean {
        if (newest == null) return false
        val expected = lastClosedSession(now, close)
        if (!newest.isBefore(expected)) return true
        val closedAt = expected.atTime(close).atZone(now.zone).toEpochSecond()
        return now.toEpochSecond() >= closedAt + HOLIDAY_GRACE_SEC
    }

    /** Does a daily series ending at [newest] hold the last finished session for
     *  [space] ("DE"/"US"/"IN", or a namespace)? Always true for crypto. */
    fun dailyDataCurrent(space: String, newest: LocalDate?,
                         nowMs: Long = System.currentTimeMillis()): Boolean {
        val base = when (space.uppercase()) { "BN" -> "binance"; else -> space.lowercase() }
        val zone = com.signalscope.app.trading.MarketHours.zoneOf(base) ?: return newest != null
        val close = com.signalscope.app.trading.MarketHours.closeOf(base) ?: return newest != null
        return dailyDataCurrentAt(newest, Instant.ofEpochMilli(nowMs).atZone(zone), close)
    }

    /** A cached series that is NOT current is fetched again after this long, so
     *  the retry finds the corrected data without hammering Yahoo every pass. */
    private const val STALE_REFETCH_MS = 45 * 60 * 1000L

    private data class BarCacheEntry(val day: String, val bars: List<DailyBar>,
                                     val fetchedAt: Long = System.currentTimeMillis())
    private val barCache = java.util.concurrent.ConcurrentHashMap<String, BarCacheEntry>()
    private val barLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    @Volatile private var barCacheDay: String = ""

    private fun utcDay(): String = LocalDate.now(ZoneOffset.UTC).toString()

    /** Drop everything when the UTC day rolls over, so the map cannot grow without
     *  bound and a new day always re-fetches. */
    private fun pruneIfNewDay(day: String) {
        if (barCacheDay != day) {
            synchronized(barCache) {
                if (barCacheDay != day) { barCache.clear(); barLocks.clear(); barCacheDay = day }
            }
        }
    }

    /** Cached [fetchDaily]. Returns the bars and whether the network was actually
     *  hit, so the caller only paces itself on real requests. */
    private fun cachedDaily(space: String, symbol: String): Pair<List<DailyBar>, Boolean> {
        val day = utcDay()
        pruneIfNewDay(day)
        val key = space.uppercase() + "|" + symbol.uppercase()
        // A series cached before the provider had the last session is fetched
        // again once it has had time to catch up — see [dailyDataCurrent].
        fun usable(e: BarCacheEntry) = e.day == day &&
            (dailyDataCurrent(space, e.bars.lastOrNull()?.date) ||
                System.currentTimeMillis() - e.fetchedAt < STALE_REFETCH_MS)
        barCache[key]?.let { if (usable(it)) return Pair(it.bars, false) }
        val lock = barLocks.getOrPut(key) { Any() }
        synchronized(lock) {
            barCache[key]?.let { if (usable(it)) return Pair(it.bars, false) }
            val old = barCache[key]?.takeIf { it.day == day }
            val bars = fetchDaily(space, symbol)
            // Only a NON-EMPTY result is cached. Caching an empty list would pin a
            // transient failure (rate limit, blip) for the rest of the day and make
            // the symbol invisible to every later caller.
            if (bars.isNotEmpty()) barCache[key] = BarCacheEntry(day, bars)
            else if (old != null) {
                // A re-check that failed keeps the older series rather than losing
                // the symbol, and waits the full interval before trying again.
                barCache[key] = old.copy(fetchedAt = System.currentTimeMillis())
                return Pair(old.bars, true)
            }
            return Pair(bars, true)
        }
    }

    /** Clear the shared cache — for a manual "re-screen now" that must not reuse
     *  today's bars (e.g. after a mid-session correction on the provider's side). */
    fun invalidateDailyCache() {
        barCache.clear(); barLocks.clear(); barCacheDay = ""
        Log.i(TAG, "daily bar cache cleared")
    }

    /** Fetch the whole universe's daily bars — shared by the screener AND the
     *  daily-MACD gate so a gate evaluation fetches each series exactly once,
     *  and so SIX gate evaluations still fetch it exactly once between them. */
    fun fetchUniverseBars(
        space: String,
        symbols: List<String>,
        minBars: Int = 10,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Pair<LinkedHashMap<String, List<DailyBar>>, List<String>> {
        val cleaned = symbols.map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct()
        val universe = LinkedHashMap<String, List<DailyBar>>()
        val skipped = ArrayList<String>()
        var fetched = 0
        for ((i, raw) in cleaned.withIndex()) {
            val (bars, hitNetwork) = try { cachedDaily(space, raw) } catch (e: Exception) {
                Log.w(TAG, "daily fetch failed for $raw", e); Pair(emptyList<DailyBar>(), false)
            }
            if (bars.size >= minBars) universe[raw] = bars else skipped.add(raw)
            onProgress?.invoke(i + 1, cleaned.size)
            // Pace only real requests. A fully-cached sweep used to still burn 80ms
            // a name — 8s of sleeping for data already in hand.
            if (hitNetwork) { fetched++; try { Thread.sleep(80) } catch (_: InterruptedException) {} }
        }
        Log.i(TAG, "universe bars [$space]: ${universe.size} usable, $fetched fetched, " +
            "${cleaned.size - fetched} from cache")
        return Pair(universe, skipped)
    }

    /** Fetch the universe's daily bars, then rank. [onProgress] fires per symbol
     *  fetched (done, total) so the UI can show progress on large universes. */
    fun run(
        space: String,
        symbols: List<String>,
        cfg: ScreenerConfig = ScreenerConfig(),
        onProgress: ((Int, Int) -> Unit)? = null
    ): Result {
        val (universe, skipped) = fetchUniverseBars(space, symbols, cfg.smaMinPeriods, onProgress)
        val asOf = universe.values.flatten().maxOfOrNull { it.date } ?: LocalDate.now()
        val ranked = StockScreener.screen(cfg, universe, asOf)
        return Result(ranked, skipped, asOf.toString(), universe.size)
    }
}

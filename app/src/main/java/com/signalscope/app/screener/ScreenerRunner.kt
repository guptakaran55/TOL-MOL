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
        candles.map { DailyBar(Instant.ofEpochSecond(it.timestamp).atZone(ZoneOffset.UTC).toLocalDate(), it.close) }

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

    /** Fetch the whole universe's daily bars — shared by the screener AND the
     *  daily-MACD gate so a gate evaluation fetches each series exactly once. */
    fun fetchUniverseBars(
        space: String,
        symbols: List<String>,
        minBars: Int = 10,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Pair<LinkedHashMap<String, List<DailyBar>>, List<String>> {
        val cleaned = symbols.map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct()
        val universe = LinkedHashMap<String, List<DailyBar>>()
        val skipped = ArrayList<String>()
        for ((i, raw) in cleaned.withIndex()) {
            val bars = try { fetchDaily(space, raw) } catch (e: Exception) {
                Log.w(TAG, "daily fetch failed for $raw", e); emptyList()
            }
            if (bars.size >= minBars) universe[raw] = bars else skipped.add(raw)
            onProgress?.invoke(i + 1, cleaned.size)
            try { Thread.sleep(80) } catch (_: InterruptedException) {}
        }
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

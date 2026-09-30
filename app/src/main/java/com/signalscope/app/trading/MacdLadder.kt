package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.CandleData
import com.signalscope.app.network.YahooFinanceClient
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/**
 * THE FIVE MACD-SLOPE DOTS FOR ONE STOCK — gathered from wherever the data
 * lives, then judged by [MacdSlopes].
 *
 * One routine for every screen that shows them — the watcher alerts and the
 * stock detail view — so the same stock at the same moment can never show one
 * pattern in the notification and another in the app.
 *
 * Where each timescale comes from:
 *   • 15m, 1H, 4H — regular-session fifteen-minute candles (passed in when the
 *     caller already has them, fetched otherwise);
 *   • 1D — the watcher's own SLOPE figure when it ranks the stock, else the
 *     stored daily MACD curve if the caller has one, else daily closes up to
 *     yesterday, the same cut-off the watcher uses;
 *   • 1M — ten years of monthly bars, fetched at most once a day per stock.
 */
object MacdLadder {
    private const val TAG = "MacdLadder"

    fun bare(symbol: String) = symbol.trim().uppercase().removeSuffix(".NS").removeSuffix(".DE")

    /** The watcher config's slope smoothing — the one its SLOPE column uses. */
    fun smoothingFor(ctx: Context, base: String): Int = try {
        com.signalscope.app.data.StrategyLabStore
            .activeConfig(ctx, com.signalscope.app.data.ManualWatchStore.nsFor(base)).slopeSmoothingOverride
    } catch (e: Exception) { null } ?: if (base == "binance") 2 else 5

    // ── MONTHLY BARS, ONCE A DAY ──────────────────────────────────────────────
    // A monthly slope cannot change within a day, so asking Yahoo again is pure
    // cost — and the tracker looks at every watchlist stock, every pass.
    private val monthly = ConcurrentHashMap<String, Pair<String, List<CandleData>>>()
    private fun today() = LocalDate.now(ZoneOffset.UTC).toString()

    /** Cached monthly bars, or null when there are none for today and [allowFetch] is false. */
    fun monthlyBars(base: String, sym: String, allowFetch: Boolean = true): List<CandleData>? {
        val key = "$base|${bare(sym)}"
        monthly[key]?.let { if (it.first == today()) return it.second }
        if (!allowFetch) return null
        val bars = try {
            (YahooFinanceClient.fetchCandlesMonthly(bare(sym), Spaces.exchangeOf(base))
                as? YahooFinanceClient.CandleResult.Success)?.candles
        } catch (e: Exception) { null } ?: return null
        // Yesterday's entries are dead weight; drop them as today's arrive.
        val t = today()
        monthly.entries.removeIf { it.value.first != t }
        monthly[key] = t to bars
        return bars
    }

    /** The watcher's own daily SLOPE for this stock, when it ranks it. */
    fun rankedDailySlope(ctx: Context, base: String, sym: String): Double? = try {
        com.signalscope.app.data.ManualWatchStore.load(ctx, base).ranking
            .firstOrNull { it.symbol.equals(bare(sym), true) }?.slope
    } catch (e: Exception) { null }

    fun readings(ctx: Context, base: String, symbol: String,
                 c15In: List<CandleData>? = null,
                 storedDailyMacd: List<Double>? = null): List<MacdSlopes.Reading>? = try {
        val sym = bare(symbol)
        val exch = Spaces.exchangeOf(base)
        val smoothing = smoothingFor(ctx, base)

        val c15 = c15In ?: try {
            (YahooFinanceClient.fetchCandles15m(sym, exch, includePrePost = false)
                as? YahooFinanceClient.CandleResult.Success)?.candles
        } catch (e: Exception) { null }

        val daily = rankedDailySlope(ctx, base, sym)
            ?: storedDailyMacd?.takeIf { it.size >= 2 }?.let { MacdSlopes.latestFromMacd(it, smoothing) }
            ?: try {
                val cutoff = LocalDate.now(ZoneOffset.UTC)
                com.signalscope.app.screener.ScreenerRunner
                    .fetchDailyBarsFor(Spaces.dataSpaceOf(base), sym)
                    ?.filter { it.date.isBefore(cutoff) }
                    ?.let { bars -> MacdSlopes.latest(DoubleArray(bars.size) { bars[it].close }, smoothing) }
            } catch (e: Exception) { null }

        MacdSlopes.all(c15, monthlyBars(base, sym), daily, System.currentTimeMillis() / 1000L,
            FeedDelay.secFor(base), smoothing)
    } catch (e: Exception) { Log.w(TAG, "readings for $symbol failed", e); null }
}

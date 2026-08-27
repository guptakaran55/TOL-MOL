package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.StrategyLabStore
import com.signalscope.app.network.BinanceClient
import com.signalscope.app.network.YahooFinanceClient

// ════════════════════════════════════════════════════════════════════════════
// MARK-TO-MARKET — value OPEN positions on a clock of their own
// ════════════════════════════════════════════════════════════════════════════
// TWO CLOCKS, DELIBERATELY SEPARATE:
//
//   DECISION clock — entries and exits, driven by each config's own bar frames
//     (240min entries / 120min exits, say). It only ever acts on CLOSED bars,
//     because acting on a half-formed candle would invent trades that never
//     happened and break parity with the Python lab. Untouched by this file.
//
//   VALUATION clock — what an ALREADY-OPEN position is worth right now. This has
//     nothing to do with deciding anything, so it can run as fast as prices
//     arrive without any risk to trading behaviour.
//
// Before this existed, both jobs shared the decision clock. A 120-minute exit
// frame therefore meant an open position's value refreshed at most every two
// hours of session time — and since the algo card only ever summed CLOSED
// trades, it actually showed +0.0% indefinitely while three positions were open.
// "Is it broken or just slow?" was unanswerable from the screen.
//
// COST: near zero. Prices for streamed symbols arrive on the existing WebSocket
// (see MainActivity's onQuote), so those are free. Only symbols the stream does
// not cover are polled, deduplicated across every algo — six algos of five seats
// is typically ~15 unique names, not 30, because algos ranking the same universe
// tend to hold the same leaders.
// ════════════════════════════════════════════════════════════════════════════

object MarkToMarket {

    private const val TAG = "MarkToMarket"

    /** Default staleness before a poll is worth making. */
    const val DEFAULT_MAX_AGE_MS = 30 * 60 * 1000L

    /** A price older than this is shown as stale rather than quietly trusted. */
    private const val STALE_AFTER_MS = 45 * 60 * 1000L

    /** After a refresh where NOTHING could be priced, wait only this long before
     *  trying again instead of banking the full 30-minute window. */
    private const val FAILED_RETRY_MS = 3 * 60 * 1000L

    /** [barMs] = the bar size this price came from (0 for a live tick). Staleness
     *  has to be judged against it: a DAILY algo's newest close is legitimately
     *  ~18h old during a session, and flagging that red would be wrong. */
    data class Mark(val price: Double, val atMs: Long, val source: String, val barMs: Long = 0L)

    /** A mark is stale once it is older than 2 bars, floored at [STALE_AFTER_MS]
     *  so a fast feed still gets a sensible grace window. */
    private fun isStale(m: Mark, now: Long): Boolean =
        now - m.atMs > maxOf(STALE_AFTER_MS, m.barMs * 2)

    private val marks = HashMap<String, Mark>()
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastRefreshMs = 0L

    /**
     * Ledger symbols are bare ("SAP", "BTCUSDC"); stream symbols carry a venue
     * suffix ("SAP.DE", "BTC-USD"). Normalise to the bare ticker so a streamed
     * price can be found by the ledger's own key.
     */
    private fun key(symbol: String): String {
        val s = symbol.trim().uppercase()
        val dot = s.indexOf('.')
        return if (dot > 0) s.substring(0, dot) else s
    }

    /** Feed from the live WebSocket — free, no request of our own. */
    fun onQuote(symbol: String, price: Double, timeMs: Long = System.currentTimeMillis()) {
        if (price <= 0.0) return
        synchronized(marks) { marks[key(symbol)] = Mark(price, timeMs, "stream") }
    }

    /**
     * Feed from the engine's own candle fetch — also free, and the PRIMARY source
     * for stocks, which the quote stream generally does not cover.
     *
     * [atMs] must be the bar's CLOSE time so "how stale is this" describes the data
     * rather than the moment we happened to read it. Never overwrites a NEWER mark:
     * a live streamed tick beats a bar close, and passes can arrive out of order.
     */
    fun onBarClose(symbol: String, close: Double, atMs: Long, barMs: Long = 0L) {
        if (close <= 0.0) return
        val k = key(symbol)
        synchronized(marks) {
            val cur = marks[k]
            if (cur == null || atMs >= cur.atMs) marks[k] = Mark(close, atMs, "bar", barMs)
        }
    }

    fun get(symbol: String): Mark? = synchronized(marks) { marks[key(symbol)] }

    // ── valuation ───────────────────────────────────────────────────────────

    data class OpenValue(
        val symbol: String,
        val entryPrice: Double,
        val price: Double,
        /** Unrealised % since entry, BEFORE slippage (nothing has been paid yet). */
        val pctReturn: Double,
        val atMs: Long,
        val source: String,
        val stale: Boolean
    )

    data class Snapshot(
        val values: List<OpenValue> = emptyList(),
        /** Newest price timestamp used, so the UI can stamp "valued 14:06". */
        val markedAtMs: Long = 0L,
        val priced: Int = 0,
        val unpriced: Int = 0
    ) {
        /** Mean unrealised % across open positions — the per-position figure. */
        val avgPct: Double get() = if (values.isEmpty()) 0.0 else values.sumOf { it.pctReturn } / values.size
    }

    /**
     * Value every OPEN ledger row for [ns]. Falls back to the row's own stored
     * `pctReturn` (the engine's mark at the last CLOSED bar) when no live price is
     * known, so a symbol the stream does not carry still shows something honest —
     * flagged stale rather than silently presented as current.
     */
    fun snapshot(ctx: Context, ns: String): Snapshot {
        val open = try {
            StrategyLabStore.load(ctx, ns).trades.filter { it.status == "OPEN" }
        } catch (e: Exception) {
            Log.w(TAG, "ledger read failed: ${e.message}"); return Snapshot()
        }
        if (open.isEmpty()) return Snapshot()
        val now = System.currentTimeMillis()
        val out = ArrayList<OpenValue>(open.size)
        var newest = 0L; var priced = 0; var unpriced = 0
        for (t in open) {
            val m = get(t.symbol)
            if (m != null && t.entryPrice > 0.0) {
                val pct = (m.price / t.entryPrice - 1.0) * 100.0
                val stale = isStale(m, now)
                out.add(OpenValue(t.symbol, t.entryPrice, m.price, pct, m.atMs, m.source, stale))
                if (m.atMs > newest) newest = m.atMs
                priced++
            } else {
                // No live price — use the engine's own last-closed-bar mark.
                out.add(OpenValue(t.symbol, t.entryPrice, 0.0, t.pctReturn, 0L, "last-bar", true))
                unpriced++
            }
        }
        return Snapshot(out, newest, priced, unpriced)
    }

    // ── refresh ─────────────────────────────────────────────────────────────

    /** Enrolled symbols with an OPEN position, across every algo in every space. */
    private fun symbolsNeedingPrices(ctx: Context): Map<String, String> {
        val out = LinkedHashMap<String, String>()      // bare symbol -> ns that owns it
        val namespaces = try {
            com.signalscope.app.data.BucketStore.allNamespaces(ctx) + listOf("us", "de", "binance")
        } catch (e: Exception) { listOf("us", "de", "binance") }
        for (ns in namespaces.distinct()) {
            val open = try {
                StrategyLabStore.load(ctx, ns).trades.filter { it.status == "OPEN" }
            } catch (e: Exception) { emptyList() }
            for (t in open) out.putIfAbsent(key(t.symbol), ns)
        }
        return out
    }

    /**
     * Poll prices for open positions the stream has not supplied. Deduplicated
     * across algos, skipped entirely when nothing is stale, and non-blocking —
     * callers fire it and read [snapshot] whenever it lands.
     */
    /**
     * Fired when a refresh actually changed something. WITHOUT THIS the whole
     * mechanism looks broken: the refresh runs on a background thread, so the card
     * was already drawn with the OLD marks and nothing repaints when the new ones
     * land. Normally the live-quote stream repaints every ~900ms and hides the
     * problem — but once the German market closes there are no quotes, no repaint,
     * and the panel sits on a stale price indefinitely (exactly the "(stale price)"
     * rows seen at 17:55 CET, after Xetra's 17:30 close).
     */
    @Volatile var onRefreshed: (() -> Unit)? = null

    /** Why the last refresh did or didn't help — so the UI can EXPLAIN staleness
     *  instead of only flagging it. */
    @Volatile var lastAttempted = 0
    @Volatile var lastSucceeded = 0
    @Volatile var lastError: String? = null

    fun refreshAsync(ctx: Context, maxAgeMs: Long = DEFAULT_MAX_AGE_MS) {
        if (System.currentTimeMillis() - lastRefreshMs < maxAgeMs) return
        if (!refreshing.compareAndSet(false, true)) return
        Thread {
            try { refreshBlocking(ctx, maxAgeMs) }
            catch (e: Exception) { Log.w(TAG, "refresh failed: ${e.message}"); lastError = e.message }
            finally { refreshing.set(false) }
        }.apply { isDaemon = true; name = "mark-to-market" }.start()
    }

    private fun refreshBlocking(ctx: Context, maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        val wanted = symbolsNeedingPrices(ctx)
        // Only symbols with no price, or one older than the window. Streamed names
        // are almost always fresh, so this list is usually short or empty.
        val stale = wanted.filter { (sym, _) ->
            val m = synchronized(marks) { marks[sym] }
            m == null || now - m.atMs > maxAgeMs
        }
        if (stale.isEmpty()) { lastRefreshMs = now; return }
        Log.i(TAG, "valuing ${stale.size} open position(s) of ${wanted.size} (rest are stream-fresh)")
        var ok = 0
        var err: String? = null
        for ((sym, ns) in stale) {
            val price = try { lastPrice(ns, sym) } catch (e: Exception) {
                Log.w(TAG, "price fetch $sym failed: ${e.message}"); err = "$sym: ${e.message}"; null
            }
            if (price != null && price > 0.0) {
                synchronized(marks) { marks[sym] = Mark(price, System.currentTimeMillis(), "poll") }
                ok++
            } else if (err == null) err = "$sym: no price returned"
            try { Thread.sleep(150) } catch (_: InterruptedException) {}
        }
        lastAttempted = stale.size; lastSucceeded = ok; lastError = if (ok == stale.size) null else err
        // Do NOT bank the full window on a total failure — that would sit on stale
        // prices for another 30 minutes after, say, one rate-limited burst. Retry
        // soon instead, but not so soon that a persistently dead feed hammers it.
        lastRefreshMs = if (ok > 0) System.currentTimeMillis()
                        else System.currentTimeMillis() - maxAgeMs + FAILED_RETRY_MS
        if (ok > 0) try { onRefreshed?.invoke() } catch (_: Exception) {}
    }

    /** One symbol's latest traded price. Intraday so it reflects TODAY, not a
     *  daily bar that would leave the value a day behind. */
    private fun lastPrice(ns: String, symbol: String): Double? {
        if (Spaces.baseOf(ns) == "binance") {
            val r = BinanceClient.fetchKlines(symbol, "15m", 500)
            return (r as? YahooFinanceClient.CandleResult.Success)?.candles?.lastOrNull()?.close
        }
        val r = YahooFinanceClient.fetchCandlesAt(symbol, Spaces.exchangeOf(ns), "5m", "1d")
        return (r as? YahooFinanceClient.CandleResult.Success)?.candles?.lastOrNull()?.close
    }
}

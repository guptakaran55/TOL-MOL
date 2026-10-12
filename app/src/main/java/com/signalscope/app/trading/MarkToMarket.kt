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

    /**
     * Default staleness before a poll is worth making.
     *
     * TWELVE MINUTES, TO SIT JUST INSIDE THE TICK. The background pass runs every
     * fifteen, and this used to be thirty — so every second pass found the price
     * still "fresh enough", reused it, and the trailing stop evaluated twice on
     * one number. Half of every stop's work was therefore spent re-reading its
     * own previous answer, and a position could be acted on against a price up to
     * thirty-five minutes behind the market.
     *
     * Just under the interval rather than equal to it, because the alarm drifts:
     * at exactly fifteen a pass arriving a few seconds early would skip, and the
     * bug would come back on precisely the ticks that ran late.
     *
     * The cost is one quote per open position per pass — the same request that
     * used to be made every other pass, so at most double, on a list that is
     * usually a handful of names and is skipped entirely for anything the live
     * stream already covers.
     */
    const val DEFAULT_MAX_AGE_MS = 12 * 60 * 1000L

    /** A price older than this is shown as stale rather than quietly trusted. */
    private const val STALE_AFTER_MS = 45 * 60 * 1000L

    /** After a refresh where NOTHING could be priced, wait only this long before
     *  trying again instead of banking the full 30-minute window. */
    private const val FAILED_RETRY_MS = 3 * 60 * 1000L

    /** What a mark taken from a real-time venue is labelled, so the rules below
     *  can tell it apart from one that merely arrived recently. */
    const val SRC_REALTIME = "tradegate"

    /**
     * How long a real-time mark outranks an incoming streamed tick.
     *
     * THE POINT OF THE WHOLE EXERCISE, and it needs saying because the rule looks
     * backwards: a quote that arrives NOW can be older data than one fetched ten
     * minutes ago. Yahoo's German feed runs a quarter of an hour behind — measured,
     * not assumed — so every tick it delivers is fifteen-minute-old news. Letting
     * those overwrite a live venue price would undo the fix within seconds and
     * leave no trace of having done so.
     *
     * Fifteen minutes is exactly that delay: once a real-time mark is older than
     * the delay itself, the streamed tick genuinely is the better number and takes
     * over again. Self-correcting, and it needs no knowledge of which space a
     * symbol belongs to at the moment a tick lands.
     */
    private const val REALTIME_HOLDS_MS = 15 * 60 * 1000L

    /** [barMs] = the bar size this price came from (0 for a live tick). Staleness
     *  has to be judged against it: a DAILY algo's newest close is legitimately
     *  ~18h old during a session, and flagging that red would be wrong. */
    data class Mark(val price: Double, val atMs: Long, val source: String, val barMs: Long = 0L,
                    /**
                     * The newest print INCLUDING pre-market and after-hours, when
                     * one is known and differs from [price]. 0 otherwise.
                     *
                     * DELIBERATELY NOT [price]. Extended-hours prints are thin:
                     * a couple of hundred shares can set a level no real seller
                     * could have got, and a stop measured against that would sell
                     * a genuine position on someone else's odd lot. So this may
                     * RAISE a high-water mark — the trade did happen, and a high
                     * is a high — but it may never pull a trigger. Firing stays on
                     * the regular-session price, which is what [price] always is.
                     */
                    val extPrice: Double = 0.0)

    /**
     * Is this instant inside the US regular session?
     *
     * The split between "a price that may raise the peak" and "a price that may
     * fire the stop" has to be drawn somewhere, and the exchange's own hours are
     * the only honest line. Weekends and holidays fall outside it; a holiday is
     * treated as extended rather than missing, which is the safe direction — it
     * can lift a mark and cannot sell anything.
     */
    internal fun inUsRegularSession(tsMs: Long): Boolean = try {
        val et = java.time.Instant.ofEpochMilli(tsMs)
            .atZone(java.time.ZoneId.of("America/New_York"))
        if (et.dayOfWeek.value > 5) false
        else {
            val mins = et.hour * 60 + et.minute
            mins >= 9 * 60 + 30 && mins < 16 * 60
        }
    } catch (e: Exception) { true }

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

    /**
     * Feed from the live WebSocket — free, no request of our own.
     *
     * DOES NOT OVERWRITE A RECENT REAL-TIME MARK. See [REALTIME_HOLDS_MS]: on a
     * delayed feed "just arrived" and "current" are different things, and this is
     * the only place the difference can be defended.
     */
    fun onQuote(symbol: String, price: Double, timeMs: Long = System.currentTimeMillis()) {
        if (price <= 0.0) return
        val k = key(symbol)
        synchronized(marks) {
            val cur = marks[k]
            if (cur != null && cur.source == SRC_REALTIME &&
                System.currentTimeMillis() - cur.atMs < REALTIME_HOLDS_MS) return
            marks[k] = Mark(price, timeMs, "stream")
        }
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

    /**
     * The highest price that genuinely traded, extended hours included.
     *
     * FOR RAISING A HIGH-WATER MARK, AND NOTHING ELSE. A pre-market print is a
     * real trade and belongs in a peak; it is not a level a stop may sell into,
     * because the book behind it is thin enough that one small order sets it.
     * So this is deliberately a separate door from [freshPrice], and the trailing
     * stop uses each for exactly one job.
     *
     * Falls back to the regular price whenever there is no extended print, and
     * honours the same staleness rule — an old number is no more actionable for
     * being an out-of-hours one.
     */
    fun peakPrice(symbol: String, nowMs: Long = System.currentTimeMillis()): Double {
        val m = get(symbol) ?: return 0.0
        if (isStale(m, nowMs)) return 0.0
        val ext = m.extPrice
        return if (ext > 0.0) maxOf(ext, m.price) else m.price
    }

    /**
     * The price ONLY IF IT IS RECENT ENOUGH TO ACT ON. 0.0 otherwise.
     *
     * [get] hands back a mark of any age, which is right for a display — a stale
     * number shown as stale is useful. It is wrong for anything that spends money,
     * and the difference is not academic: a stop comparing last night's close
     * against a floor will fire into a market that is shut, on a price that has
     * had all night to be wrong.
     *
     * A missing quote and a four-hour-old quote are the same class of thing, and
     * the older one is the more dangerous because it looks like an answer. Both
     * come back as zero here, and every caller already treats zero as "no opinion".
     *
     * Judged against the BAR the price came from, not a flat clock, so a daily
     * algo's overnight close is not condemned for being eighteen hours old during
     * a session — that is exactly how old it is supposed to be.
     *
     * [maxAgeMs] ADDITIONALLY caps the age for a caller that means something
     * stricter by "current" than the general rule does. It only ever tightens:
     * the bar-aware test still has to pass as well.
     *
     * WHY IT EXISTS. A streamed tick carries no bar size, so the general rule
     * floors it at the 45-minute grace window — sensible for valuing a portfolio,
     * wrong for a stop whose setting is literally called "live ticks". Without
     * this, choosing the tick option and losing the stream meant selling real
     * stock on a forty-four-minute-old price while the screen said ticks.
     */
    fun freshPrice(symbol: String, nowMs: Long = System.currentTimeMillis(),
                   maxAgeMs: Long = 0L): Double {
        val m = get(symbol) ?: return 0.0
        if (m.price <= 0.0) return 0.0
        if (isStale(m, nowMs)) return 0.0
        if (maxAgeMs > 0L && nowMs - m.atMs > maxAgeMs) return 0.0
        return m.price
    }

    /**
     * Drop a symbol's mark so the runner's skip-fetch guard cannot skip it.
     *
     * That guard refuses to refetch until two exit frames have passed since the
     * watermark — which on a 120-minute exit frame is FOUR HOURS. It exempts a
     * symbol with no mark on purpose (see its own comment: a skip after a process
     * restart would leave positions unpriced until the next bar closed), and this
     * is that exemption used deliberately.
     *
     * Needed by the manual hand-over: a position bought on a FRESH signal stamps a
     * watermark only a couple of hours old, so the guard would skip the symbol for
     * the rest of the window and the engine would not pick up the position it was
     * just handed — leaving a real holding with nothing managing its exit for
     * hours. Forgetting the mark costs one refetch and is self-healing: the next
     * pass repopulates it from the bars it fetches anyway.
     */
    fun forget(symbol: String) {
        synchronized(marks) { marks.remove(key(symbol)) }
    }

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
        // Manual-entry namespaces are listed explicitly: they hold real positions
        // but are not BucketStore entries (by design — see ManualWatchStore), so
        // allNamespaces cannot see them and their holdings would show as unpriced.
        val manualNs = (com.signalscope.app.data.BucketStore.BUCKETABLE_SPACES +
            com.signalscope.app.trading.ManualEntryWatcher.WATCHED_SPACES).distinct()
            .map { com.signalscope.app.data.ManualWatchStore.nsFor(it) }
        val namespaces = try {
            com.signalscope.app.data.BucketStore.allNamespaces(ctx) + listOf("us", "de", "binance") + manualNs
        } catch (e: Exception) { listOf("us", "de", "binance") + manualNs }
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

    /**
     * PRICE THESE EXACT SYMBOLS, NOW, AND WAIT.
     *
     * The general refresh is a fire-and-forget sweep over everything: it is
     * throttled, it runs on its own thread, and a caller has no idea whether it
     * landed. That is right for a display — the card redraws when prices arrive —
     * and wrong for a stop, which has to decide at a particular instant.
     *
     * The trailing stop used to read whatever the sweep happened to have left
     * behind. When the sweep had not finished, the mark was a whole pass old, the
     * ten-minute tick rule refused it, and the stop declined to act — correctly,
     * but for the wrong reason. The stop was waiting on a thread rather than on
     * the market.
     *
     * So it asks for what it needs instead. Blocking, targeted, and untimed by
     * any throttle: a handful of quotes for a handful of held names, once per
     * pass, only where an armed stop actually depends on them.
     *
     * @return how many were refreshed.
     */
    fun ensureFresh(ctx: Context, ns: String, symbols: Collection<String>,
                    maxAgeMs: Long): Int {
        if (symbols.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val delayedFeed = Spaces.baseOf(ns) == "de"
        val stale = symbols.map { key(it) }.distinct().filter { k ->
            val m = synchronized(marks) { marks[k] }
            // Same rule as the background refresh: a German mark off the stream is
            // fifteen-minute-old news however recent its timestamp looks, so it
            // cannot be allowed to satisfy a freshness test. This is the call the
            // stop sweep makes before it judges anything, which makes it the place
            // a delayed price would do the most damage.
            val delayedGerman = delayedFeed && m != null && m.source == "stream"
            m == null || m.price <= 0.0 || delayedGerman || now - m.atMs > maxAgeMs
        }
        if (stale.isEmpty()) return 0
        var ok = 0
        for (k in stale) {
            val p = try { lastPrice(ctx, ns, k) } catch (e: Exception) {
                Log.w(TAG, "targeted price fetch $k failed: ${e.message}"); null
            }
            if (p != null && p.regular > 0.0) {
                synchronized(marks) {
                    marks[k] = Mark(p.regular, System.currentTimeMillis(),
                        if (p.realtime) SRC_REALTIME else "poll", extPrice = p.extended)
                }
                ok++
            }
            try { Thread.sleep(120) } catch (_: InterruptedException) { break }
        }
        if (ok > 0) {
            Log.i(TAG, "[$ns] priced $ok of ${stale.size} held name(s) for the stop sweep")
            try { onRefreshed?.invoke() } catch (_: Exception) {}
        }
        return ok
    }

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
        val stale = wanted.filter { (sym, ns) ->
            val m = synchronized(marks) { marks[sym] }
            // A GERMAN MARK THAT CAME FROM THE STREAM IS ALREADY A QUARTER OF AN
            // HOUR OLD, whatever its timestamp says, so it must not be allowed to
            // satisfy the freshness test — otherwise the live venue is never asked
            // and the delayed feed keeps the position looking priced.
            val delayedGerman = m != null && m.source == "stream" && Spaces.baseOf(ns) == "de"
            m == null || delayedGerman || now - m.atMs > maxAgeMs
        }
        if (stale.isEmpty()) { lastRefreshMs = now; return }
        Log.i(TAG, "valuing ${stale.size} open position(s) of ${wanted.size} (rest are stream-fresh)")
        var ok = 0
        var err: String? = null
        for ((sym, ns) in stale) {
            val price = try { lastPrice(ctx, ns, sym) } catch (e: Exception) {
                Log.w(TAG, "price fetch $sym failed: ${e.message}"); err = "$sym: ${e.message}"; null
            }
            if (price != null && price.regular > 0.0) {
                synchronized(marks) {
                    marks[sym] = Mark(price.regular, System.currentTimeMillis(),
                        if (price.realtime) SRC_REALTIME else "poll", extPrice = price.extended)
                }
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
    /** A polled price, split into what may fire a stop and what may only lift a peak. */
    private data class Polled(val regular: Double, val extended: Double,
                              /** Came from a live venue rather than a delayed feed. */
                              val realtime: Boolean = false)

    /** The latest regular-session price, fetched now — for an action taken at
     *  this moment rather than a display that can wait for the next poll. */
    fun priceNow(ctx: Context, ns: String, symbol: String): Double? = try {
        lastPrice(ctx, ns, symbol)?.regular?.takeIf { it > 0.0 && it.isFinite() }
    } catch (e: Exception) { null }

    private fun lastPrice(ctx: Context, ns: String, symbol: String): Polled? {
        if (Spaces.baseOf(ns) == "binance") {
            val r = BinanceClient.fetchKlines(symbol, "15m", 500)
            val c = (r as? YahooFinanceClient.CandleResult.Success)?.candles?.lastOrNull()?.close
            return c?.let { Polled(it, 0.0) }     // crypto has no session to be outside of
        }
        // ── GERMANY GETS THE LIVE VENUE WHERE IT CAN ────────────────────────
        // Tried before Yahoo, and only with a reference price already in hand:
        // the reader rejects a quote that strays too far from one, and that guard
        // is the entire defence against a mis-resolved instrument returning a
        // real price for the wrong company. No reference, no attempt.
        if (Spaces.baseOf(ns) == "de") {
            val ref = synchronized(marks) { marks[key(symbol)]?.price } ?: 0.0
            if (ref > 0.0) {
                val live = try { GermanRealtime.mid(ctx, symbol, ref) }
                           catch (e: Exception) { null }
                if (live != null && live > 0.0) return Polled(live, 0.0, realtime = true)
            }
        }
        val us = Spaces.baseOf(ns) == "us"
        // ONE REQUEST, BOTH ANSWERS. Asking for extended hours returns the regular
        // candles as well, so the regular-session close is still available by
        // filtering — no second call, and no need to choose between seeing the
        // pre-market and being able to act.
        val r = YahooFinanceClient.fetchCandlesAt(
            symbol, Spaces.exchangeOf(ns), "5m", "1d", includePrePost = us)
        val candles = (r as? YahooFinanceClient.CandleResult.Success)?.candles ?: return null
        if (candles.isEmpty()) return null
        if (!us) return Polled(candles.last().close, 0.0)
        val newest = candles.last()
        val lastRegular = candles.lastOrNull { inUsRegularSession(it.timestamp * 1000L) }
            ?: return Polled(newest.close, 0.0)
        val ext = if (newest.timestamp != lastRegular.timestamp) newest.close else 0.0
        return Polled(lastRegular.close, ext)
    }
}

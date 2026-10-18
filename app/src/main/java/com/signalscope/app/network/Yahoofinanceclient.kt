package com.signalscope.app.network


import android.util.Log
import com.signalscope.app.data.CandleData
import com.google.gson.JsonParser
import okhttp3.*
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Yahoo Finance data fetcher — mirrors Python's us_market.py / yfinance integration.
 *
 * Used to fetch historical candle data for Zerodha holdings, since Zerodha's
 * Kite API does not provide historical OHLCV data for portfolio holdings.
 *
 * For Indian stocks, Yahoo Finance uses the format: SYMBOL.NS (NSE) or SYMBOL.BO (BSE)
 * Example: RELIANCE -> RELIANCE.NS
 *
 * Calls Yahoo Finance v8 chart API directly (no library dependency),
 * matching the Python fetch_index_chart_data() approach.
 */
object YahooFinanceClient {

    private const val TAG = "YahooFinanceClient"
    private const val BASE_URL = "https://query1.finance.yahoo.com/v8/finance/chart"
    private val NIFTY_500_URLS = listOf(
        "https://www.niftyindices.com/IndexConstituent/ind_nifty500list.csv",
        "https://nsearchives.nseindia.com/content/indices/ind_nifty500list.csv"
    )

    // Cookie jar with proper domain matching (cookies from .yahoo.com apply to all subdomains)
    private val cookieJar = object : CookieJar {
        private val store = mutableListOf<Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(store) {
                cookies.forEach { newCookie ->
                    store.removeAll { it.name == newCookie.name && it.domain == newCookie.domain }
                    store.add(newCookie)
                }
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            synchronized(store) {
                return store.filter { it.matches(url) }  // OkHttp handles domain/path matching
            }
        }
    }

    // Custom DNS — prefer IPv4 addresses first (MIUI has flaky IPv6)
    private val ipv4PreferredDns = object : Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            val v4 = all.filter { it is java.net.Inet4Address }
            val v6 = all.filter { it !is java.net.Inet4Address }
            return v4 + v6  // v4 first, v6 as fallback
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)   // hard cap — prevents hanging on partial responses
        .retryOnConnectionFailure(true)
        .cookieJar(cookieJar)
        .followRedirects(true)
        .dns(ipv4PreferredDns)
        // Force HTTP/1.1 — HTTP/2 connections get stuck on MIUI with no error
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    // ── Crumb authentication for v10 API ──
    @Volatile private var cachedCrumb: String? = null
    @Volatile private var crumbFetchTime: Long = 0L
    private const val CRUMB_TTL_MS = 30 * 60 * 1000L  // refresh crumb every 30 min

    /**
     * Fetch a Yahoo Finance crumb token. Required for v10 quoteSummary API.
     * Steps: 1) Hit finance.yahoo.com to get session cookies  2) Fetch crumb with those cookies
     */
    private fun fetchCrumb(): String? {
        val now = System.currentTimeMillis()
        cachedCrumb?.let { crumb ->
            if (now - crumbFetchTime < CRUMB_TTL_MS) return crumb
        }

        return try {
            val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

            // Step 1: Hit fc.yahoo.com — returns 404 but sets A1/A3 cookies on .yahoo.com domain
            // This is the same approach used by Python yfinance library
            val seedReq = Request.Builder()
                .url("https://fc.yahoo.com")
                .addHeader("User-Agent", ua)
                .build()
            val (seedCode, seedHeaders) = client.newCall(seedReq).execute().use { seedResp ->
                seedResp.code to seedResp.headers("Set-Cookie")
            }
            Log.d(TAG, "Cookie seed: HTTP $seedCode, Set-Cookie headers: ${seedHeaders.size}")
            seedHeaders.forEach { Log.d(TAG, "  Set-Cookie: ${it.take(80)}") }

            // Debug: log what cookies would be sent to query2
            val crumbUrl = HttpUrl.Builder()
                .scheme("https").host("query2.finance.yahoo.com")
                .addPathSegments("v1/test/getcrumb").build()
            val matchedCookies = cookieJar.loadForRequest(crumbUrl)
            Log.d(TAG, "Cookies for query2: ${matchedCookies.size} — ${matchedCookies.map { "${it.name}=${it.value.take(10)}..." }}")

            // Step 2: Fetch crumb using session cookies
            val crumbReq = Request.Builder()
                .url(crumbUrl)
                .addHeader("User-Agent", ua)
                .build()
            val crumb = client.newCall(crumbReq).execute().use { crumbResp ->
                crumbResp.body?.string()?.trim()
            }

            if (!crumb.isNullOrEmpty() && crumb.length < 50 && !crumb.contains("error")) {
                cachedCrumb = crumb
                crumbFetchTime = now
                Log.d(TAG, "Yahoo crumb acquired: ${crumb.take(8)}...")
                crumb
            } else {
                Log.w(TAG, "Yahoo crumb response invalid: ${crumb?.take(200)}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch Yahoo crumb", e)
            null
        }
    }

    sealed class CandleResult {
        data class Success(val candles: List<CandleData>, val name: String? = null) : CandleResult()
        object RateLimited : CandleResult()
        object NoData : CandleResult()
        data class Error(val message: String) : CandleResult()
    }

    /** Fewest bars a chart reply may hold and still count as a series. */
    private const val MIN_BARS = 50

    /** The symbol a point-in-time request actually goes out under — the live
     *  line for one that moved on the feed, see [SymbolRenames.feedSuccessor]. */
    private fun onFeed(yahooSymbol: String): String =
        com.signalscope.app.data.SymbolRenames.feedSuccessor(yahooSymbol) ?: yahooSymbol

    /**
     * ONE CHART REQUEST — OR TWO, SPLICED, FOR A LINE THAT MOVED ON THE FEED.
     *
     * [fetch] makes a single request for the symbol it is handed, keeps only a
     * series of at least the length it is handed, and records feed health under
     * the name it is handed (none, for null).
     *
     * For an ordinary symbol that is exactly the request it always was. For one
     * the feed has moved, the live line supplies every bar it has and the frozen
     * line fills in the history the live one does not carry yet; the minimum is
     * applied to the joined series, since neither half is expected to reach it on
     * its own. Feed health is read off the LIVE line but filed under the name the
     * rest of the app asks by — the frozen line is known to be frozen, and that
     * is precisely the fact this splice exists to make irrelevant.
     */
    private fun chartFor(
        yahooSymbol: String,
        fetch: (requestSymbol: String, minBars: Int, observeAs: String?) -> CandleResult
    ): CandleResult {
        val next = com.signalscope.app.data.SymbolRenames.feedSuccessor(yahooSymbol)
            ?: return fetch(yahooSymbol, MIN_BARS, yahooSymbol)
        val newer = fetch(next, 0, yahooSymbol)
        if (newer is CandleResult.RateLimited) return newer
        val older = fetch(yahooSymbol, 0, null)
        val a = (older as? CandleResult.Success)?.candles.orEmpty()
        val b = (newer as? CandleResult.Success)?.candles.orEmpty()
        val joined = spliceLines(a, b)
        Log.d(TAG, "$yahooSymbol: ${a.size} bars on the frozen line + ${b.size} on $next -> ${joined.size}")
        if (joined.size < MIN_BARS) return if (older is CandleResult.RateLimited) older else CandleResult.NoData
        return CandleResult.Success(joined,
            (newer as? CandleResult.Success)?.name ?: (older as? CandleResult.Success)?.name)
    }

    /**
     * Two lines of the same share as one series: every bar of the [newer] line,
     * and the [older] line's bars at every time the newer one has none. Pure.
     *
     * Merged by time rather than cut at a date because Yahoo's move was not
     * clean — the new line's daily series has holes the old one still fills (12
     * to 14 Oct, for Pfisterer), while its intraday series has taken over
     * outright. Where both carry a bar the newer wins, since the old line is the
     * one that stopped.
     */
    internal fun spliceLines(older: List<CandleData>, newer: List<CandleData>): List<CandleData> {
        if (newer.isEmpty()) return older
        if (older.isEmpty()) return newer
        val taken = newer.mapTo(HashSet()) { it.timestamp }
        return (older.filter { it.timestamp !in taken } + newer).sortedBy { it.timestamp }
    }

    sealed class NiftyUniverseResult {
        data class Success(val symbols: List<String>, val sourceUrl: String) : NiftyUniverseResult()
        data class Failure(val message: String) : NiftyUniverseResult()
    }

    /**
     * Download the current NIFTY 500 constituents from the same official CSV
     * endpoints used by the earlier browser project. A result is accepted only
     * when it looks like a real NIFTY 500 universe (>400 symbols); otherwise the
     * caller should fall back to the embedded static list.
     */
    fun fetchNifty500Symbols(minAcceptedSymbols: Int = 400): NiftyUniverseResult {
        var lastError = ""
        for (url in NIFTY_500_URLS) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .addHeader(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0"
                    )
                    .addHeader("Accept", "text/csv,text/plain,*/*")
                    .addHeader("Accept-Language", "en-US,en;q=0.9")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = "$url returned HTTP ${response.code}"
                        Log.w(TAG, lastError)
                        return@use
                    }
                    val text = response.body?.string().orEmpty()
                    val symbols = parseNifty500Csv(text)
                    if (symbols.size > minAcceptedSymbols) {
                        Log.i(TAG, "NIFTY 500 universe: ${symbols.size} symbols from $url")
                        return NiftyUniverseResult.Success(symbols, url)
                    }
                    lastError = "$url had only ${symbols.size} symbols"
                    Log.w(TAG, lastError)
                }
            } catch (e: Exception) {
                lastError = "$url failed: ${e.message ?: e.javaClass.simpleName}"
                Log.w(TAG, lastError)
            }
        }
        return NiftyUniverseResult.Failure(lastError.ifBlank { "No NIFTY 500 source returned enough symbols" })
    }

    private fun parseNifty500Csv(text: String): List<String> {
        val lines = text
            .replace("\uFEFF", "")
            .lines()
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        val header = parseCsvLine(lines.first()).map { it.trim() }
        val symbolIdx = header.indexOfFirst { it.equals("Symbol", ignoreCase = true) }
        if (symbolIdx < 0) return emptyList()

        val validSymbol = Regex("^[A-Z0-9&-]+$")
        return lines.drop(1)
            .mapNotNull { line ->
                val cols = parseCsvLine(line)
                val raw = cols.getOrNull(symbolIdx)?.trim().orEmpty()
                val sym = raw.removeSuffix("-EQ").uppercase(Locale.US)
                sym.takeIf { it.isNotBlank() && validSymbol.matches(it) }
            }
            .distinct()
            .sorted()
    }

    private fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    cur.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    out.add(cur.toString())
                    cur.clear()
                }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out
    }

    // ── Candle cache ──
    // Caches 2y daily candles per yahoo symbol. On refresh, only fetches last 5 days
    // and merges with cached history — avoids re-downloading ~500 candles every 15 min.
    private data class CachedCandles(
        val candles: List<CandleData>,
        val fullFetchTime: Long  // millis when the full 2y fetch was done
    )
    private const val MAX_CACHE_SIZE = 150 // LRU eviction — prevents OOM during 500-stock discovery scans
    private val candleCache = object : LinkedHashMap<String, CachedCandles>(MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedCandles>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }
    private const val FULL_FETCH_INTERVAL_MS = 12 * 60 * 60 * 1000L  // re-fetch full 2y every 12 hours

    /** Clear all cached candle data (e.g. on service restart or day boundary). */
    fun clearCache() {
        candleCache.clear()
        Log.i(TAG, "Candle cache cleared")
    }

    /**
     * Cached version of fetchCandles for portfolio monitoring.
     * First call: full 2y fetch (same as fetchCandles). Subsequent calls within 12h:
     * fetches only last 5 days and merges with cached history — 99% less data.
     * Falls back to full fetch on any error.
     */
    fun fetchCandlesCached(symbol: String, exchange: String = "NSE"): CandleResult {
        val yahooSymbol = toYahooSymbol(symbol, exchange)
        val now = System.currentTimeMillis()
        val cached = candleCache[yahooSymbol]

        // Full fetch needed if: no cache, or cache is stale (>12h)
        if (cached == null || (now - cached.fullFetchTime) > FULL_FETCH_INTERVAL_MS) {
            val result = fetchCandles(symbol, exchange)
            if (result is CandleResult.Success) {
                candleCache[yahooSymbol] = CachedCandles(result.candles, now)
            }
            return result
        }

        // Incremental refresh: fetch last 5 days and merge with cached history
        val refreshResult = fetchRecentCandles(yahooSymbol)
        if (refreshResult is CandleResult.Success && refreshResult.candles.isNotEmpty()) {
            val recentTimestamps = refreshResult.candles.map { it.timestamp }.toSet()
            // Keep all cached candles except the ones we're replacing with fresh data
            val merged = cached.candles.filter { it.timestamp !in recentTimestamps } + refreshResult.candles
            val sorted = merged.sortedBy { it.timestamp }
            candleCache[yahooSymbol] = CachedCandles(sorted, cached.fullFetchTime)
            return CandleResult.Success(sorted)
        }

        // Refresh failed — return cached data (still valid, just slightly stale today candle)
        Log.w(TAG, "Incremental refresh failed for $yahooSymbol, using cached data")
        return CandleResult.Success(cached.candles)
    }

    /**
     * Fetch only the last 5 days of daily candles (for incremental cache refresh).
     */
    private fun fetchRecentCandles(yahooSymbol: String): CandleResult =
        chartFor(yahooSymbol) { s, min, obs -> fetchRecentOnce(s, min, obs) }

    private fun fetchRecentOnce(yahooSymbol: String, minBars: Int, observeAs: String?): CandleResult {
        return try {
            val url = HttpUrl.Builder()
                .scheme("https")
                .host("query1.finance.yahoo.com")
                .addPathSegments("v8/finance/chart/$yahooSymbol")
                .addQueryParameter("range", "5d")
                .addQueryParameter("interval", "1d")
                .addQueryParameter("includePrePost", "false")
                .addQueryParameter("events", "")
                .build()

            val request = Request.Builder()
                .url(url)
                .addHeader(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0"
                )
                .addHeader("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            response.use { resp ->
                when (resp.code) {
                    429 -> CandleResult.RateLimited
                    200 -> {
                        val body = resp.body?.string() ?: return CandleResult.NoData
                        parseYahooResponse(body, yahooSymbol, minBars, observeAs)
                    }
                    else -> CandleResult.NoData
                }
            }
        } catch (e: Exception) {
            CandleResult.Error(e.message ?: "Unknown error")
        }
    }

    /** Fetch 60 days of 15-minute candles for intraday paper simulations. */
    /**
     * @param includePrePost ask for pre-market and after-hours bars as well.
     *
     * WITHOUT IT THERE ARE NO BARS AFTER THE BELL, and a gate that reads this
     * series then concludes the newest completed bar is stale and holds — which
     * is exactly how enabling extended-hours entries changed nothing: the venue
     * was open, the order was allowed, and the trend check had no data to form
     * an opinion from, so it waited for a bar that was never going to arrive.
     */
    /** Ten years of monthly bars — for the monthly MACD dot on watcher alerts.
     *  The month in progress arrives as its last bar; callers drop it. */
    fun fetchCandlesMonthly(symbol: String, exchange: String = "NSE"): CandleResult =
        fetchCandlesInternal(symbol, exchange, range = "10y", interval = "1mo")

    /** Fifteen-minute bars over a SHORT range — ten days is enough for the
     *  15-minute and hourly MACD, at a sixth of the download of the usual 60. */
    fun fetchCandles15mRange(symbol: String, exchange: String, range: String): CandleResult =
        fetchCandlesInternal(symbol, exchange, range = range, interval = "15m", includePrePost = false)

    fun fetchCandles15m(symbol: String, exchange: String = "NSE",
                        includePrePost: Boolean = false): CandleResult =
        fetchCandlesInternal(symbol, exchange, range = "60d", interval = "15m",
            includePrePost = includePrePost)

    /**
     * Generic OHLCV fetch for the CONFIG-DRIVEN engine feed (FeedPlan). Yahoo
     * serves less history the finer the interval, so the planner picks the
     * coarsest interval that still tiles into the config's frames and asks for
     * that interval's full cap — e.g. 60m/730d for a 240min/120min champion
     * instead of 15m/60d, which could never clear a 240-bar warmup.
     *
     * RAW closes on purpose (ANDROID_ADAPTIVE_FETCH_INSTRUCTIONS §9 CORRECTION):
     * the engine transacts at raw prices and the Python reference CSVs are raw
     * (verified via DTE/ALV/MUV2/BAS ex-dividend gaps). Only the screener and the
     * daily-MACD gate use the ADJUSTED feed (fetchDailyAdjusted), because ranking
     * compares trailing returns ACROSS symbols where a dividend gap would make a
     * payer look like it fell.
     */
    fun fetchCandlesAt(
        symbol: String, exchange: String, interval: String, range: String,
        includePrePost: Boolean = false
    ): CandleResult = fetchCandlesInternal(symbol, exchange, range = range,
        interval = interval, includePrePost = includePrePost)

    /**
     * Fetch 2 years of daily candles for a stock.
     * For Indian stocks (NSE): appends ".NS" suffix automatically.
     * For US stocks (NASDAQ): uses symbol as-is.
     *
     * @param symbol  Trading symbol e.g. "RELIANCE" or "AAPL"
     * @param exchange "NSE", "BSE", "NASDAQ", or "DE"/"XETRA" — determines Yahoo suffix
     */
    fun fetchCandles(symbol: String, exchange: String = "NSE"): CandleResult =
        chartFor(toYahooSymbol(symbol, exchange)) { s, min, obs ->
            fetchCandlesOnce(symbol, exchange, s, min, obs)
        }

    private fun fetchCandlesOnce(symbol: String, exchange: String, yahooSymbol: String,
                                 minBars: Int, observeAs: String?): CandleResult {
        val maxAttempts = 3

        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                if (attempt > 1) {
                    Log.d(TAG, "Retry #$attempt for $yahooSymbol")
                    Thread.sleep((500L * attempt).coerceAtMost(2000L))
                } else {
                    Log.d(TAG, "Fetching Yahoo Finance data for $yahooSymbol")
                }

                val url = HttpUrl.Builder()
                    .scheme("https")
                    .host("query1.finance.yahoo.com")
                    .addPathSegments("v8/finance/chart/$yahooSymbol")
                    .addQueryParameter("range", "2y")
                    .addQueryParameter("interval", "1d")
                    .addQueryParameter("includePrePost", "false")
                    .addQueryParameter("events", "")
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .addHeader(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0"
                    )
                    .addHeader("Accept", "application/json")
                    .build()

                val response = client.newCall(request).execute()

                return response.use { resp ->
                    when (resp.code) {
                        429 -> {
                            Log.w(TAG, "Yahoo Finance rate limited for $yahooSymbol")
                            CandleResult.RateLimited
                        }
                        404 -> {
                            Log.w(TAG, "Symbol not found: $yahooSymbol")
                            val canTryIndianAlternate = !symbol.contains(".") &&
                                exchange.uppercase() in setOf("NSE", "BSE")
                            if (canTryIndianAlternate) tryAlternateSuffix(symbol, exchange) else CandleResult.NoData
                        }
                        200 -> {
                            val body = resp.body?.string() ?: return@use CandleResult.NoData
                            parseYahooResponse(body, yahooSymbol, minBars, observeAs)
                        }
                        else -> {
                            Log.w(TAG, "Yahoo Finance HTTP ${resp.code} for $yahooSymbol")
                            CandleResult.NoData
                        }
                    }
                }

            } catch (e: java.io.InterruptedIOException) {
                // Timeout — retry
                lastError = e
                Log.w(TAG, "Timeout on attempt $attempt for $yahooSymbol")
                continue
            } catch (e: java.net.SocketTimeoutException) {
                lastError = e
                Log.w(TAG, "SocketTimeout on attempt $attempt for $yahooSymbol")
                continue
            } catch (e: java.net.UnknownHostException) {
                // DNS failure — retry, phone might have flaky network
                lastError = e
                Log.w(TAG, "DNS failure on attempt $attempt for $yahooSymbol")
                continue
            } catch (e: Exception) {
                val msg = e.message?.lowercase() ?: ""
                if ("rate" in msg || "429" in msg || "too many" in msg) {
                    return CandleResult.RateLimited
                }
                Log.e(TAG, "Yahoo Finance error for $yahooSymbol", e)
                return CandleResult.Error(e.message ?: "Unknown error")
            }
        }

        Log.e(TAG, "All $maxAttempts attempts failed for $yahooSymbol", lastError)
        return CandleResult.Error(lastError?.message ?: "Timeout after $maxAttempts retries")
    }

    /**
     * Current market price from the chart endpoint's meta block. For US symbols
     * this is real-time (measured ~0s lag); for Xetra it carries the exchange's
     * ~15-minute delay — callers needing true DE real-time should prefer
     * Tradegate and treat this as fallback. Returns null on any failure.
     *
     * AND NULL FOR A FEED THAT HAS STOPPED (see [FeedHealth]). A frozen line
     * still answers with a price — the last one it ever had — and every caller
     * here treats the answer as the market: an order ladder anchored a week
     * behind could never reach a fill, and a sale built on it would rest above a
     * price long gone. No answer sends them to their other sources instead.
     */
    fun fetchLivePrice(yahooSymbol: String): Double? {
        return try {
            val url = HttpUrl.Builder()
                .scheme("https").host("query1.finance.yahoo.com")
                .addPathSegments("v8/finance/chart/${onFeed(yahooSymbol)}")
                .addQueryParameter("range", "1d")
                .addQueryParameter("interval", "1m")
                .addQueryParameter("includePrePost", "false")
                .build()
            val request = Request.Builder().url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0")
                .addHeader("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.code != 200) return null
                val body = resp.body?.string() ?: return null
                val meta = JSONObject(body).getJSONObject("chart")
                    .getJSONArray("result").getJSONObject(0).getJSONObject("meta")
                FeedHealth.observe(yahooSymbol, meta)
                if (FeedHealth.stoppedSince(yahooSymbol) > 0L) {
                    Log.w(TAG, "fetchLivePrice($yahooSymbol): the feed has stopped — no price given")
                    return null
                }
                val p = meta.optDouble("regularMarketPrice", Double.NaN)
                if (p.isNaN() || p <= 0.0) null else p
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchLivePrice($yahooSymbol) failed: ${e.message}")
            null
        }
    }

    /** The exchange's own regular-session bounds, epoch seconds. */
    data class Session(val start: Long, val end: Long) {
        fun isOpenAt(sec: Long): Boolean = sec >= start && sec < end
    }

    /**
     * The symbol's REGULAR session window as the exchange reports it.
     *
     * WHY THIS RATHER THAN A SESSION CALENDAR IN THE APP. Parking an order until
     * "the next open" needs a real answer to when that is, and the hand-written
     * version of this is a table of open/close times per market that is wrong on
     * every public holiday — which is precisely a day when an order would be
     * parked, wake into a shut market, and have to work out what to do next.
     * The exchange's own bounds handle time zones, half-days and holidays for
     * free, and cost nothing extra: [fetchMarketOpen] was already reading this
     * exact object and throwing away everything except the yes/no.
     *
     * NOTE THAT THE WINDOW MOVES. Yahoo reports the CURRENT period, so after a
     * close it keeps naming the session that just ended until it rolls forward
     * to the next one, some hours later. Callers must therefore treat a window
     * whose start is already past as "not yet known" and ask again later, rather
     * than computing tomorrow by adding 24 hours — that arithmetic is wrong
     * across weekends, which is the case it would meet first.
     */
    fun fetchRegularSession(yahooSymbol: String): Session? =
        fetchTradingWindow(yahooSymbol, includeExtended = false)

    /**
     * The window a venue will actually accept orders in.
     *
     * WITH [includeExtended] IT SPANS PRE-MARKET TO AFTER-HOURS, which for US
     * equities is roughly 10:00 to 01:00 CET rather than 15:30 to 22:00. Trading
     * 212 runs those sessions on its more liquid US names, so an entry that fires
     * on an evening bar has somewhere to go instead of waiting for tomorrow.
     *
     * FALLS BACK TO THE REGULAR BOUNDS WHERE A VENUE PUBLISHES NO EXTENDED ONES,
     * which is most of them — Xetra among them. That fallback is what keeps this
     * safe to call anywhere: asking for extended hours on a market that has none
     * returns exactly what asking for regular hours would.
     */
    fun fetchTradingWindow(yahooSymbol: String, includeExtended: Boolean): Session? {
        return try {
            val url = HttpUrl.Builder()
                .scheme("https").host("query1.finance.yahoo.com")
                .addPathSegments("v8/finance/chart/${onFeed(yahooSymbol)}")
                .addQueryParameter("range", "1d")
                .addQueryParameter("interval", "1m")
                .build()
            val request = Request.Builder().url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0")
                .addHeader("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.code != 200) return null
                val body = resp.body?.string() ?: return null
                val meta = JSONObject(body).getJSONObject("chart")
                    .getJSONArray("result").getJSONObject(0).getJSONObject("meta")
                // Free: the reply that answers "is it open" also says whether the
                // name has traded in the session it is open for.
                FeedHealth.observe(yahooSymbol, meta)
                val period = meta.getJSONObject("currentTradingPeriod")
                val regular = period.getJSONObject("regular")
                var start = regular.getLong("start")
                var end = regular.getLong("end")
                if (includeExtended) {
                    // Widened, never narrowed. A malformed or missing pre/post
                    // block must not be able to shrink the window below the
                    // regular session and quietly stop trading altogether.
                    period.optJSONObject("pre")?.optLong("start", 0L)
                        ?.takeIf { it in 1 until start }?.let { start = it }
                    period.optJSONObject("post")?.optLong("end", 0L)
                        ?.takeIf { it > end }?.let { end = it }
                }
                Session(start, end)
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchTradingWindow($yahooSymbol) failed: ${e.message}")
            null
        }
    }

    /**
     * Is the symbol's REGULAR trading session open right now? Uses the chart
     * meta's currentTradingPeriod (exchange-reported session bounds), so it
     * handles time zones and holidays for free. null = couldn't determine —
     * callers should treat null as "unknown", not "closed".
     */
    fun fetchMarketOpen(yahooSymbol: String, includeExtended: Boolean = false): Boolean? {
        return try {
            val url = HttpUrl.Builder()
                .scheme("https").host("query1.finance.yahoo.com")
                .addPathSegments("v8/finance/chart/${onFeed(yahooSymbol)}")
                .addQueryParameter("range", "1d")
                .addQueryParameter("interval", "1m")
                .build()
            val request = Request.Builder().url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0")
                .addHeader("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.code != 200) return null
                val body = resp.body?.string() ?: return null
                val meta = JSONObject(body).getJSONObject("chart")
                    .getJSONArray("result").getJSONObject(0).getJSONObject("meta")
                // Free: the reply that answers "is it open" also says whether the
                // name has traded in the session it is open for.
                FeedHealth.observe(yahooSymbol, meta)
                val period = meta.getJSONObject("currentTradingPeriod")
                val regular = period.getJSONObject("regular")
                var start = regular.getLong("start")
                var end = regular.getLong("end")
                if (includeExtended) {
                    period.optJSONObject("pre")?.optLong("start", 0L)
                        ?.takeIf { it in 1 until start }?.let { start = it }
                    period.optJSONObject("post")?.optLong("end", 0L)
                        ?.takeIf { it > end }?.let { end = it }
                }
                val now = System.currentTimeMillis() / 1000
                now >= start && now < end
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchMarketOpen($yahooSymbol) failed: ${e.message}")
            null
        }
    }

    /**
     * SCREENER-ONLY daily feed using SPLIT/DIVIDEND-ADJUSTED closes.
     *
     * The momentum screener measures trailing return, volatility, and %>SMA50 —
     * all of which a stock split would corrupt if computed on raw prices (a 2:1
     * split looks like a −50% crash). Yahoo's chart API returns an `adjclose`
     * series that already corrects splits/dividends; we read that instead of the
     * raw `close`. The TRADING engine deliberately keeps raw prices (intraday
     * entries need the actual traded price), so this is a separate path — only
     * the screener is split-sensitive. Falls back to raw close if adjclose is
     * absent (e.g. very new listings). ~2 years of history per the spec.
     */
    fun fetchDailyAdjusted(symbol: String, exchange: String): CandleResult =
        chartFor(toYahooSymbol(symbol, exchange)) { s, min, obs -> fetchDailyAdjustedOnce(s, min, obs) }

    private fun fetchDailyAdjustedOnce(yahooSymbol: String, minBars: Int, observeAs: String?): CandleResult {
        return try {
            val url = HttpUrl.Builder()
                .scheme("https").host("query1.finance.yahoo.com")
                .addPathSegments("v8/finance/chart/$yahooSymbol")
                .addQueryParameter("range", "2y")
                .addQueryParameter("interval", "1d")
                .addQueryParameter("includePrePost", "false")
                .addQueryParameter("events", "div,splits")   // makes Yahoo return the adjclose series
                .build()
            val req = Request.Builder().url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0")
                .addHeader("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 429) return CandleResult.RateLimited
                if (resp.code != 200) return CandleResult.NoData
                val body = resp.body?.string() ?: return CandleResult.NoData
                parseYahooAdjusted(body, yahooSymbol, minBars, observeAs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchDailyAdjusted($yahooSymbol) failed: ${e.message}")
            CandleResult.Error("Adjusted fetch error: ${e.message}")
        }
    }

    /**
     * Parse the chart response reading adjclose (split-adjusted).
     *
     * THE RANGE IS NOW CARRIED TOO. This used to set open, high and low all equal
     * to the adjusted close, on the grounds that the screener only reads closes —
     * true at the time, and it silently ruled out every study built on true range
     * for the whole universe. The squeeze scan refused all 105 German names on
     * its first run for exactly that reason: bars with no range at all.
     *
     * The high and low are ADJUSTED BY THE SAME FACTOR as the close, not taken
     * raw. Mixing a raw high with an adjusted close would put the day's range in
     * a different currency to the day's price across any split, and the error
     * would be invisible except as strange volatility years back.
     */
    private fun parseYahooAdjusted(body: String, symbol: String, minBars: Int = MIN_BARS,
                                   observeAs: String? = symbol): CandleResult {
        return try {
            val result = JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            if (observeAs != null) FeedHealth.observe(observeAs, result.optJSONObject("meta"))
            val timestamps = result.optJSONArray("timestamp") ?: return CandleResult.NoData
            val indicators = result.getJSONObject("indicators")
            val rawClose = indicators.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close")
            val adj = indicators.optJSONArray("adjclose")?.optJSONObject(0)?.optJSONArray("adjclose")
            if (minBars > 0 && timestamps.length() < 10) return CandleResult.NoData
            val candles = ArrayList<CandleData>()
            for (i in 0 until timestamps.length()) {
                // Prefer adjusted; fall back to raw when adjclose is null for a bar.
                val v = when {
                    adj != null && !adj.isNull(i) -> adj.optDouble(i)
                    rawClose != null && !rawClose.isNull(i) -> rawClose.optDouble(i)
                    else -> continue
                }
                if (v.isNaN() || v <= 0.0) continue
                // Scale the raw range onto the adjusted close. Falls back to a
                // flat bar when the provider gives no usable high/low, which the
                // squeeze scan detects and declines to score rather than reading
                // as zero volatility.
                val q = indicators.optJSONArray("quote")?.optJSONObject(0)
                val rc = rawClose?.takeIf { !it.isNull(i) }?.optDouble(i) ?: 0.0
                val f = if (rc > 0.0) v / rc else 1.0
                val rh = q?.optJSONArray("high")?.takeIf { !it.isNull(i) }?.optDouble(i) ?: 0.0
                val rl = q?.optJSONArray("low")?.takeIf { !it.isNull(i) }?.optDouble(i) ?: 0.0
                val ro = q?.optJSONArray("open")?.takeIf { !it.isNull(i) }?.optDouble(i) ?: 0.0
                val hi = if (rh > 0.0 && !rh.isNaN()) rh * f else v
                val lo = if (rl > 0.0 && !rl.isNaN()) rl * f else v
                val op = if (ro > 0.0 && !ro.isNaN()) ro * f else v
                val vol = q?.optJSONArray("volume")?.takeIf { !it.isNull(i) }?.optLong(i) ?: 0L
                candles.add(CandleData(timestamps.getLong(i), op,
                    maxOf(hi, v), minOf(lo, v), v, vol))
            }
            if (candles.isEmpty() || candles.size < minBars) CandleResult.NoData
            else CandleResult.Success(LatestSession.fill(candles, result.optJSONObject("meta")), null)
        } catch (e: Exception) {
            Log.e(TAG, "parseYahooAdjusted error for $symbol", e)
            CandleResult.Error("Parse error: ${e.message}")
        }
    }

    private fun fetchCandlesInternal(
        symbol: String,
        exchange: String,
        range: String,
        interval: String,
        /**
         * Include pre-market and after-hours prints.
         *
         * OPT-IN, AND ONLY FOR THE LIVE MARK. Strategy candles must stay
         * regular-session: every backtest, every MACD, every bar the engine has
         * ever reasoned about was built without extended hours, and quietly
         * mixing them in would change what the strategy sees rather than what the
         * stop sees. Those are different questions and only one of them is being
         * answered here.
         */
        includePrePost: Boolean = false
    ): CandleResult = chartFor(toYahooSymbol(symbol, exchange)) { s, min, obs ->
        fetchCandlesInternalOnce(symbol, exchange, range, interval, includePrePost, s, min, obs)
    }

    private fun fetchCandlesInternalOnce(
        symbol: String, exchange: String, range: String, interval: String,
        includePrePost: Boolean, yahooSymbol: String, minBars: Int, observeAs: String?
    ): CandleResult {
        val maxAttempts = 3

        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                if (attempt > 1) {
                    Log.d(TAG, "Retry #$attempt for $yahooSymbol ($range/$interval)")
                    Thread.sleep((500L * attempt).coerceAtMost(2000L))
                } else {
                    Log.d(TAG, "Fetching Yahoo Finance data for $yahooSymbol ($range/$interval)")
                }

                val url = HttpUrl.Builder()
                    .scheme("https")
                    .host("query1.finance.yahoo.com")
                    .addPathSegments("v8/finance/chart/$yahooSymbol")
                    .addQueryParameter("range", range)
                    .addQueryParameter("interval", interval)
                    .addQueryParameter("includePrePost", if (includePrePost) "true" else "false")
                    .addQueryParameter("events", "")
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .addHeader(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0"
                    )
                    .addHeader("Accept", "application/json")
                    .build()

                val response = client.newCall(request).execute()

                return response.use { resp ->
                    when (resp.code) {
                        429 -> {
                            Log.w(TAG, "Yahoo Finance rate limited for $yahooSymbol")
                            CandleResult.RateLimited
                        }
                        404 -> {
                            Log.w(TAG, "Symbol not found: $yahooSymbol")
                            val canTryIndianAlternate = !symbol.contains(".") &&
                                exchange.uppercase() in setOf("NSE", "BSE")
                            if (canTryIndianAlternate) {
                                tryAlternateSuffix(symbol, exchange, range, interval)
                            } else {
                                CandleResult.NoData
                            }
                        }
                        200 -> {
                            val body = resp.body?.string() ?: return@use CandleResult.NoData
                            parseYahooResponse(body, yahooSymbol, minBars, observeAs)
                        }
                        else -> {
                            Log.w(TAG, "Yahoo Finance HTTP ${resp.code} for $yahooSymbol")
                            CandleResult.NoData
                        }
                    }
                }
            } catch (e: java.io.InterruptedIOException) {
                lastError = e
                Log.w(TAG, "Timeout on attempt $attempt for $yahooSymbol")
                continue
            } catch (e: java.net.SocketTimeoutException) {
                lastError = e
                Log.w(TAG, "SocketTimeout on attempt $attempt for $yahooSymbol")
                continue
            } catch (e: java.net.UnknownHostException) {
                lastError = e
                Log.w(TAG, "DNS failure on attempt $attempt for $yahooSymbol")
                continue
            } catch (e: Exception) {
                val msg = e.message?.lowercase() ?: ""
                if ("rate" in msg || "429" in msg || "too many" in msg) {
                    return CandleResult.RateLimited
                }
                Log.e(TAG, "Yahoo Finance error for $yahooSymbol", e)
                return CandleResult.Error(e.message ?: "Unknown error")
            }
        }

        Log.e(TAG, "All $maxAttempts attempts failed for $yahooSymbol", lastError)
        return CandleResult.Error(lastError?.message ?: "Timeout after $maxAttempts retries")
    }

    private fun tryAlternateSuffix(
        symbol: String,
        exchange: String,
        range: String = "2y",
        interval: String = "1d"
    ): CandleResult {
        // If NSE didn't work, try BSE and vice versa
        val altSuffix = if (exchange == "NSE") ".BO" else ".NS"
        val altSymbol = "$symbol$altSuffix"

        return try {
            Log.d(TAG, "Trying alternate suffix: $altSymbol")

            val url = HttpUrl.Builder()
                .scheme("https")
                .host("query1.finance.yahoo.com")
                .addPathSegments("v8/finance/chart/$altSymbol")
                .addQueryParameter("range", range)
                .addQueryParameter("interval", interval)
                .addQueryParameter("includePrePost", "false")
                .addQueryParameter("events", "")
                .build()

            val request = Request.Builder()
                .url(url)
                .addHeader(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0"
                )
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code != 200) return CandleResult.NoData
                val body = response.body?.string() ?: return CandleResult.NoData
                parseYahooResponse(body, altSymbol)
            }

        } catch (e: Exception) {
            CandleResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Parse Yahoo Finance v8 chart API JSON response into CandleData list.
     * Mirrors Python's fetch_index_chart_data() parsing logic.
     */
    private fun parseYahooResponse(body: String, symbol: String, minBars: Int = MIN_BARS,
                                   observeAs: String? = symbol): CandleResult {
        return try {
            val json = JSONObject(body)
            val chart = json.getJSONObject("chart")

            val resultArray = chart.optJSONArray("result") ?: return CandleResult.NoData
            if (resultArray.length() == 0) return CandleResult.NoData

            val result = resultArray.getJSONObject(0)
            val meta = result.optJSONObject("meta")
            if (observeAs != null) FeedHealth.observe(observeAs, meta)
            fun metaName(key: String): String? {
                val value = meta?.optString(key, "")?.trim().orEmpty()
                return value.takeIf { it.isNotBlank() && !it.equals(symbol, ignoreCase = true) }
            }
            val displayName = metaName("longName") ?: metaName("shortName")

            val timestamps = result.optJSONArray("timestamp")
            val indicators = result.getJSONObject("indicators")
            val quoteArray = indicators.getJSONArray("quote")
            if (quoteArray.length() == 0) return CandleResult.NoData

            val quote = quoteArray.getJSONObject(0)
            val opens = quote.optJSONArray("open")
            val highs = quote.optJSONArray("high")
            val lows = quote.optJSONArray("low")
            val closes = quote.optJSONArray("close")
            val volumes = quote.optJSONArray("volume")

            if (closes == null || timestamps == null || (minBars > 0 && timestamps.length() < 10)) {
                Log.w(TAG, "Yahoo Finance: no usable timestamps for $symbol")
                return CandleResult.NoData
            }

            val candles = mutableListOf<CandleData>()
            for (i in 0 until timestamps.length()) {
                val c = if (closes.isNull(i)) null else closes.optDouble(i)
                if (c == null || c.isNaN()) continue

                candles.add(
                    CandleData(
                        timestamp = timestamps.getLong(i),
                        open = if (opens == null || opens.isNull(i)) c else opens.optDouble(i, c),
                        high = if (highs == null || highs.isNull(i)) c else highs.optDouble(i, c),
                        low = if (lows == null || lows.isNull(i)) c else lows.optDouble(i, c),
                        close = c,
                        volume = if (volumes == null || volumes.isNull(i)) 0L else volumes.optLong(i, 0L)
                    )
                )
            }

            if (candles.isEmpty() || candles.size < minBars) {
                Log.w(TAG, "Too few candles for $symbol: ${candles.size}")
                return CandleResult.NoData
            }

            Log.d(TAG, "Yahoo Finance: $symbol — ${candles.size} candles")
            CandleResult.Success(LatestSession.fill(candles, meta), displayName)

        } catch (e: Exception) {
            Log.e(TAG, "Yahoo Finance parse error for $symbol", e)
            CandleResult.Error("Parse error: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════
    // FUNDAMENTAL DATA (for Value Analysis)
    // ═══════════════════════════════════════════════════════

    /**
     * Fundamental data fetched from Yahoo Finance quoteSummary API.
     * All fields nullable — Yahoo may not have data for every stock.
     */
    data class FundamentalData(
        val trailingPe: Double? = null,
        val forwardPe: Double? = null,
        val priceToBook: Double? = null,
        val enterpriseToEbitda: Double? = null,
        val debtToEquity: Double? = null,
        val returnOnEquity: Double? = null,    // ROE as decimal (0.15 = 15%)
        val returnOnAssets: Double? = null,
        val revenueGrowth: Double? = null,      // as percentage
        val earningsGrowth: Double? = null,     // as percentage
        val grossMargins: Double? = null,       // as percentage
        val operatingMargins: Double? = null,   // as percentage
        val profitMargins: Double? = null,      // as percentage
        val dividendYield: Double? = null,      // as percentage
        val operatingCashflow: Double? = null,
        val freeCashflow: Double? = null,
        val totalRevenue: Double? = null,
        val netIncome: Double? = null,          // from incomeStatementHistory
        val totalCash: Double? = null,
        val totalDebt: Double? = null,
        val currentRatio: Double? = null,
        val annualRevenueGrowth: Double? = null,
        val annualNetIncomeGrowth: Double? = null,
        val fiftyTwoWeekLow: Double? = null,
        val fiftyTwoWeekHigh: Double? = null,
        val sharesOutstanding: Long? = null,
        val sector: String? = null,
        val industry: String? = null,
        val name: String? = null
    )

    // ── Symbol autocomplete (Yahoo's `/v1/finance/search` endpoint) ──────────
    // Used by the Discovery search box so the user can type a partial name
    // ("TATA") and pick from a dropdown of matches, instead of having to
    // know the exact ticker. Endpoint is unauthenticated but lightly rate-
    // limited; we cap quotesCount=10 to keep responses small.
    data class SearchHit(
        val symbol: String,         // raw Yahoo symbol incl. .NS / .BO suffix
        val name: String,           // shortname → longname fallback
        val exchange: String,       // exchDisp ("NSI", "NMS", "NYQ", …)
        val quoteType: String       // "EQUITY", "ETF", "MUTUALFUND", …
    )

    sealed class SearchResult {
        data class Success(val hits: List<SearchHit>) : SearchResult()
        object RateLimited : SearchResult()
        data class Error(val message: String) : SearchResult()
    }

    fun searchSymbols(query: String, region: String = "IN"): SearchResult {
        val q = query.trim()
        if (q.length < 2) return SearchResult.Success(emptyList())
        return try {
            val url = HttpUrl.Builder()
                .scheme("https")
                .host("query1.finance.yahoo.com")
                .addPathSegments("v1/finance/search")
                .addQueryParameter("q", q)
                .addQueryParameter("lang", "en-US")
                .addQueryParameter("region", region)
                .addQueryParameter("quotesCount", "10")
                .addQueryParameter("newsCount", "0")
                .addQueryParameter("enableFuzzyQuery", "true")
                .build()
            val request = Request.Builder()
                .url(url)
                .addHeader(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0"
                )
                .addHeader("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.code == 429) return SearchResult.RateLimited
                if (!resp.isSuccessful) return SearchResult.Error("HTTP ${resp.code}")
                val body = resp.body?.string() ?: return SearchResult.Error("empty response")
                val root = JsonParser.parseString(body).asJsonObject
                val arr = root.getAsJsonArray("quotes") ?: return SearchResult.Success(emptyList())
                val hits = arr.mapNotNull { el ->
                    try {
                        val o = el.asJsonObject
                        // Filter junk: only keep equities / ETFs (skip currencies, futures, indices)
                        val qt = o.get("quoteType")?.asString ?: return@mapNotNull null
                        if (qt !in setOf("EQUITY", "ETF", "MUTUALFUND")) return@mapNotNull null
                        val sym = o.get("symbol")?.asString ?: return@mapNotNull null
                        val name = o.get("shortname")?.asString
                            ?: o.get("longname")?.asString ?: sym
                        val ex = o.get("exchDisp")?.asString
                            ?: o.get("exchange")?.asString ?: ""
                        SearchHit(sym, name, ex, qt)
                    } catch (e: Exception) { null }
                }
                SearchResult.Success(hits)
            }
        } catch (e: Exception) {
            Log.e(TAG, "searchSymbols failed for '$q'", e)
            SearchResult.Error(e.message ?: "unknown")
        }
    }

    sealed class FundamentalResult {
        data class Success(val data: FundamentalData) : FundamentalResult()
        object RateLimited : FundamentalResult()
        object NoData : FundamentalResult()
        data class Error(val message: String) : FundamentalResult()
    }

    /**
     * Fetch fundamental data from Yahoo Finance quoteSummary API.
     * Modules include snapshot valuation, balance-sheet quality, and annual
     * income/cash-flow history for the Value Score.
     */
    fun fetchFundamentals(symbol: String, exchange: String = "NSE"): FundamentalResult {
        val yahooSymbol = toYahooSymbol(symbol, exchange)
        return try {
            // Acquire crumb (needed for v10 quoteSummary auth)
            val crumb = fetchCrumb()
            if (crumb == null) {
                Log.w(TAG, "No crumb available — cannot fetch fundamentals for $yahooSymbol")
                return FundamentalResult.NoData
            }

            val url = HttpUrl.Builder()
                .scheme("https")
                .host("query2.finance.yahoo.com")
                .addPathSegments("v10/finance/quoteSummary/$yahooSymbol")
                .addQueryParameter(
                    "modules",
                    "price,defaultKeyStatistics,financialData,summaryDetail,assetProfile," +
                            "incomeStatementHistory,cashflowStatementHistory,balanceSheetHistory,earningsTrend"
                )
                .addQueryParameter("crumb", crumb)
                .build()

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0")
                .addHeader("Accept", "application/json")
                .build()

            Log.d(TAG, "quoteSummary URL: $url")
            val response = client.newCall(request).execute()
            response.use { resp ->
                Log.d(TAG, "quoteSummary HTTP ${resp.code} for $yahooSymbol")
                when (resp.code) {
                    429 -> FundamentalResult.RateLimited
                    401, 403 -> {
                        Log.w(TAG, "quoteSummary auth failed (${resp.code}) for $yahooSymbol — clearing crumb")
                        cachedCrumb = null  // force re-fetch next time
                        FundamentalResult.NoData
                    }
                    404 -> {
                        Log.w(TAG, "quoteSummary 404 for $yahooSymbol — symbol not found")
                        FundamentalResult.NoData
                    }
                    200 -> {
                        val body = resp.body?.string()
                        if (body == null) {
                            Log.w(TAG, "quoteSummary empty body for $yahooSymbol")
                            return FundamentalResult.NoData
                        }
                        Log.d(TAG, "quoteSummary body preview for $yahooSymbol: ${body.take(200)}")
                        parseFundamentals(body, yahooSymbol)
                    }
                    else -> {
                        val errBody = resp.body?.string()?.take(300) ?: ""
                        Log.w(TAG, "quoteSummary HTTP ${resp.code} for $yahooSymbol: $errBody")
                        FundamentalResult.NoData
                    }
                }
            }
        } catch (e: Exception) {
            val msg = e.message?.lowercase() ?: ""
            if ("rate" in msg || "429" in msg) FundamentalResult.RateLimited
            else {
                Log.e(TAG, "quoteSummary error for $yahooSymbol", e)
                FundamentalResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun parseFundamentals(body: String, symbol: String): FundamentalResult {
        return try {
            val json = JSONObject(body)
            val summary = json.optJSONObject("quoteSummary") ?: return FundamentalResult.NoData
            val results = summary.optJSONArray("result")
            if (results == null || results.length() == 0) return FundamentalResult.NoData

            val r = results.getJSONObject(0)
            val keyStats = r.optJSONObject("defaultKeyStatistics")
            val finData = r.optJSONObject("financialData")
            val summaryDetail = r.optJSONObject("summaryDetail")
            val profile = r.optJSONObject("assetProfile")
            val price = r.optJSONObject("price")
            val incomeHistory = r.optJSONObject("incomeStatementHistory")
            val balanceHistory = r.optJSONObject("balanceSheetHistory")

            fun JSONObject?.rawVal(key: String): Double? {
                val obj = this?.optJSONObject(key) ?: return null
                val v = obj.optDouble("raw", Double.NaN)
                return if (v.isNaN()) null else v
            }

            fun JSONObject?.rawLong(key: String): Long? {
                val obj = this?.optJSONObject(key) ?: return null
                val v = obj.optLong("raw", -1)
                return if (v == -1L) null else v
            }

            fun statementValues(module: JSONObject?, arrayName: String, key: String): List<Double> {
                val arr = module?.optJSONArray(arrayName) ?: return emptyList()
                val values = mutableListOf<Double>()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i)
                    val v = item.rawVal(key)
                    if (v != null && !v.isNaN() && !v.isInfinite()) values += v
                }
                return values
            }

            fun longGrowthPct(values: List<Double>): Double? {
                if (values.size < 2) return null
                val latest = values.first()
                val oldest = values.last()
                if (oldest == 0.0) return null
                return ((latest - oldest) / kotlin.math.abs(oldest)) * 100.0
            }

            fun JSONObject?.cleanName(key: String): String? {
                val value = this?.optString(key, "")?.trim().orEmpty()
                return value.takeIf { it.isNotBlank() && !it.equals(symbol, ignoreCase = true) }
            }

            val annualRevenues = statementValues(incomeHistory, "incomeStatementHistory", "totalRevenue")
            val annualNetIncomes = statementValues(incomeHistory, "incomeStatementHistory", "netIncome")
            val displayName = price.cleanName("longName") ?: price.cleanName("shortName")

            val data = FundamentalData(
                trailingPe = summaryDetail.rawVal("trailingPE") ?: keyStats.rawVal("trailingPE"),
                forwardPe = summaryDetail.rawVal("forwardPE") ?: keyStats.rawVal("forwardPE"),
                priceToBook = keyStats.rawVal("priceToBook"),
                enterpriseToEbitda = keyStats.rawVal("enterpriseToEbitda"),
                debtToEquity = finData.rawVal("debtToEquity")?.let { it / 100.0 }, // Yahoo returns as %, normalize
                returnOnEquity = finData.rawVal("returnOnEquity"),
                returnOnAssets = finData.rawVal("returnOnAssets"),
                revenueGrowth = finData.rawVal("revenueGrowth")?.let { it * 100.0 },
                earningsGrowth = finData.rawVal("earningsGrowth")?.let { it * 100.0 },
                grossMargins = finData.rawVal("grossMargins")?.let { it * 100.0 },
                operatingMargins = finData.rawVal("operatingMargins")?.let { it * 100.0 },
                profitMargins = finData.rawVal("profitMargins")?.let { it * 100.0 },
                dividendYield = summaryDetail.rawVal("dividendYield")?.let { it * 100.0 }, // convert decimal → %
                operatingCashflow = finData.rawVal("operatingCashflow"),
                freeCashflow = finData.rawVal("freeCashflow"),
                totalRevenue = finData.rawVal("totalRevenue"),
                netIncome = finData.rawVal("netIncome") ?: keyStats.rawVal("netIncomeToCommon"),
                totalCash = finData.rawVal("totalCash") ?: balanceHistory
                    ?.optJSONArray("balanceSheetStatements")
                    ?.optJSONObject(0)
                    .rawVal("cash"),
                totalDebt = finData.rawVal("totalDebt") ?: balanceHistory
                    ?.optJSONArray("balanceSheetStatements")
                    ?.optJSONObject(0)
                    .rawVal("totalDebt"),
                currentRatio = finData.rawVal("currentRatio"),
                annualRevenueGrowth = longGrowthPct(annualRevenues),
                annualNetIncomeGrowth = longGrowthPct(annualNetIncomes),
                fiftyTwoWeekLow = summaryDetail.rawVal("fiftyTwoWeekLow"),
                fiftyTwoWeekHigh = summaryDetail.rawVal("fiftyTwoWeekHigh"),
                sharesOutstanding = keyStats.rawLong("sharesOutstanding"),
                sector = profile?.optString("sector", "")?.trim()?.takeIf { it.isNotBlank() },
                industry = profile?.optString("industry", "")?.trim()?.takeIf { it.isNotBlank() },
                name = displayName
            )

            Log.d(TAG, "Fundamentals: $symbol — PE=${data.trailingPe} PB=${data.priceToBook} D/E=${data.debtToEquity}")
            FundamentalResult.Success(data)

        } catch (e: Exception) {
            Log.e(TAG, "quoteSummary parse error for $symbol", e)
            FundamentalResult.Error("Parse error: ${e.message}")
        }
    }

    /**
     * Convert a trading symbol to Yahoo Finance format.
     * NSE stocks: RELIANCE -> RELIANCE.NS
     * BSE stocks: RELIANCE -> RELIANCE.BO
     * US stocks: AAPL -> AAPL (no suffix)
     * German stocks: SAP -> SAP.DE
     *
     * Some Indian stocks have special characters that Yahoo handles differently.
     */
    fun toYahooSymbol(symbol: String, exchange: String = "NSE"): String {
        // Already has a suffix or is an index symbol (^NSEBANK, ^CNXIT, etc.) — return as-is
        if (symbol.contains(".") || symbol.startsWith("^")) return symbol

        return when (exchange.uppercase()) {
            "BSE" -> "$symbol.BO"
            "NASDAQ", "NYSE", "US" -> com.signalscope.app.data.SymbolRenames.current(symbol, "US")
            // THE LAST LINE OF DEFENCE ON A RENAME. Every other place that could
            // hold a retired symbol has been corrected at the source, but this is
            // the single funnel a bare symbol passes through on its way to the
            // price feed — so anything that was written to disk under the old name
            // and missed still asks the feed the right question and comes back with
            // bars instead of "may be delisted". Costs a map lookup per call.
            "DE", "XETRA", "GERMANY" -> "${com.signalscope.app.data.SymbolRenames.currentDe(symbol)}.DE"
            else -> "$symbol.NS" // default to NSE
        }
    }
}

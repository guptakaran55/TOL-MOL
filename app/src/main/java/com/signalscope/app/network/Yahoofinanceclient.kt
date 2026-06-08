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
    private fun fetchRecentCandles(yahooSymbol: String): CandleResult {
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
                        parseYahooResponse(body, yahooSymbol)
                    }
                    else -> CandleResult.NoData
                }
            }
        } catch (e: Exception) {
            CandleResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Fetch 2 years of daily candles for a stock.
     * For Indian stocks (NSE): appends ".NS" suffix automatically.
     * For US stocks (NASDAQ): uses symbol as-is.
     *
     * @param symbol  Trading symbol e.g. "RELIANCE" or "AAPL"
     * @param exchange "NSE", "BSE", "NASDAQ", or "DE"/"XETRA" — determines Yahoo suffix
     */
    fun fetchCandles(symbol: String, exchange: String = "NSE"): CandleResult {
        val yahooSymbol = toYahooSymbol(symbol, exchange)
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
                            tryAlternateSuffix(symbol, exchange)
                        }
                        200 -> {
                            val body = resp.body?.string() ?: return@use CandleResult.NoData
                            parseYahooResponse(body, yahooSymbol)
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

    private fun tryAlternateSuffix(symbol: String, exchange: String): CandleResult {
        // If NSE didn't work, try BSE and vice versa
        val altSuffix = if (exchange == "NSE") ".BO" else ".NS"
        val altSymbol = "$symbol$altSuffix"

        return try {
            Log.d(TAG, "Trying alternate suffix: $altSymbol")

            val url = HttpUrl.Builder()
                .scheme("https")
                .host("query1.finance.yahoo.com")
                .addPathSegments("v8/finance/chart/$altSymbol")
                .addQueryParameter("range", "2y")
                .addQueryParameter("interval", "1d")
                .addQueryParameter("includePrePost", "false")
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
    private fun parseYahooResponse(body: String, symbol: String): CandleResult {
        return try {
            val json = JSONObject(body)
            val chart = json.getJSONObject("chart")

            val resultArray = chart.optJSONArray("result") ?: return CandleResult.NoData
            if (resultArray.length() == 0) return CandleResult.NoData

            val result = resultArray.getJSONObject(0)
            val meta = result.optJSONObject("meta")
            fun metaName(key: String): String? {
                val value = meta?.optString(key, "")?.trim().orEmpty()
                return value.takeIf { it.isNotBlank() && !it.equals(symbol, ignoreCase = true) }
            }
            val displayName = metaName("longName") ?: metaName("shortName")

            val timestamps = result.getJSONArray("timestamp")
            val indicators = result.getJSONObject("indicators")
            val quoteArray = indicators.getJSONArray("quote")
            if (quoteArray.length() == 0) return CandleResult.NoData

            val quote = quoteArray.getJSONObject(0)
            val opens = quote.optJSONArray("open")
            val highs = quote.optJSONArray("high")
            val lows = quote.optJSONArray("low")
            val closes = quote.optJSONArray("close")
            val volumes = quote.optJSONArray("volume")

            if (closes == null || timestamps.length() < 10) return CandleResult.NoData

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

            if (candles.size < 50) {
                Log.w(TAG, "Too few candles for $symbol: ${candles.size}")
                return CandleResult.NoData
            }

            Log.d(TAG, "Yahoo Finance: $symbol — ${candles.size} candles")
            CandleResult.Success(candles, displayName)

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
            "NASDAQ", "NYSE", "US" -> symbol
            "DE", "XETRA", "GERMANY" -> "$symbol.DE"
            else -> "$symbol.NS" // default to NSE
        }
    }
}

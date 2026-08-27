package com.signalscope.app.network

import android.util.Base64
import android.util.Log
import com.google.gson.JsonParser
import com.signalscope.app.data.ConfigManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit

/**
 * Trading 212 API client for live portfolio reads, order placement, pending
 * order fetches, and cancels. T212 has no native OCO/GTT, so the app stores
 * SL + target order ids and reconciles them in Trading212TrailingManager.
 *
 * Auth contract (verified against the working Python reference script):
 *   Authorization: Basic <base64(api_key:api_secret)>
 *   Content-Type:  application/json
 * Both api_key AND api_secret are mandatory — the API rejects basic-auth
 * with an empty secret as 401 Unauthorized. Earlier comments here said
 * "single bearer token" — that was wrong; corrected after the user's manual
 * Python script proved Basic auth is what actually works.
 *
 * Base URL is picked up from ConfigManager.t212BaseUrl and is always live.
 *
 * Rate limits per T212 docs:
 *   - Account / portfolio reads: 1 req / 5s
 *   - Instruments metadata: 1 req / 30s
 *   - Order endpoints: 1 req / 2s
 * The UI throttles bulk placement so order endpoints stay within limits.
 */
class Trading212Client(private val config: ConfigManager) {

    companion object {
        private const val TAG = "Trading212Client"
        private const val OPEN_ORDERS_CACHE_MS = 2_500L
        @Volatile private var openOrdersCacheBaseUrl: String = ""
        @Volatile private var openOrdersCacheJson: String = ""
        @Volatile private var openOrdersCacheAt: Long = 0L

        // Instrument metadata + reverse index MUST be companion-level: callers
        // construct a fresh Trading212Client per bridge call, so instance-level
        // caches lived exactly one call and died — every resolve re-downloaded
        // the full instrument list, and two resolves within 30s (e.g. the DE
        // dialog's Tradegate ISIN lookup followed by the actual buy seconds
        // later) collided with the 1-req/30s metadata rate limit, surfacing as
        // "instrument list not loaded yet" on German buys while US worked.
        @Volatile private var metaCache: Map<String, InstrumentMeta>? = null
        @Volatile private var metaFetchedAt: Long = 0L
        private const val META_TTL_MS = 6 * 60 * 60 * 1000L  // 6 h — names rarely change
        @Volatile private var reverseIdx: Map<String, String>? = null
        @Volatile private var reverseIdxAt: Long = 0L
        @Volatile private var reverseIdxSpace: String = ""
        /** Last good instrument list, persisted so resolution works from the
         *  first tap even after app restart + a rate-limited live fetch. */
        /**
         * VERSIONED because the cache is a serialised snapshot of [InstrumentMeta].
         *
         * Adding a field does not invalidate an existing file: Gson simply reads the
         * old JSON and leaves the new field null, so the app confidently reports
         * "not stated" for data T212 does publish. That happened the first time
         * minTradeQuantity was read — the answer came from a file written before the
         * field existed, and looked exactly like a real negative result.
         *
         * Bump the suffix whenever a field is added to InstrumentMeta.
         */
        private const val META_DISK_FILE = "t212_instruments_v2.json"
        private const val META_DISK_FILE_OLD = "t212_instruments.json"

        /**
         * T212's minimum, in CURRENCY — "one full unit (1.00 GBP, 1.00 EUR, 1.00
         * USD)", per Trading 212's own fractional-shares page. It is NOT a share
         * count, which is what the `min-opened-position-exceeded` error's bare
         * "at least 1.00" reads like and what an earlier version of this code
         * wrongly assumed. At €55 a share the difference is 55×.
         *
         * Applies to the order AND to what the order leaves behind: every observed
         * rejection was a sale that would have left a stub worth under €1 (0.01815
         * S92d_EQ ≈ €0.996), and every sale leaving exactly zero was accepted.
         */
        const val MIN_POSITION_VALUE = 1.0
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Build the HTTP Basic auth header value: `Basic <base64(key:secret)>`.
     *  Uses NO_WRAP so the encoded string doesn't contain newlines (which
     *  some HTTP stacks reject in header values). */
    private fun authHeader(): String {
        val creds = "${config.t212ApiKey}:${config.t212ApiSecret}"
        val encoded = Base64.encodeToString(creds.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "Basic $encoded"
    }

    /** Distinguishes "no key set" from "key set but rejected" so the UI can
     *  show the right CTA (configure vs reconnect). */
    sealed class T212Result<out T> {
        data class Success<T>(val data: T) : T212Result<T>()
        data class Failure(val message: String, val httpCode: Int = -1) : T212Result<Nothing>()
        object NotConfigured : T212Result<Nothing>()
    }

    // ── Account summary (used as the connection-test ping) ────────────────
    data class AccountSummary(
        val cash: Double,
        val freeFunds: Double,
        val totalValue: Double,
        val currencyCode: String
    )

    // Account base currency (EUR / USD / …). Cached — it never changes for an
    // account. T212's /equity/account/cash returns ONE consolidated figure in
    // this currency; it does NOT itemise per-currency (USD vs EUR) sub-balances.
    @Volatile private var accountCurrency: String? = null
    fun fetchAccountCurrency(): String {
        accountCurrency?.let { return it }
        if (!config.hasT212Credentials) return "USD"
        return try {
            val url = "${config.t212BaseUrl}/equity/account/info".toHttpUrlOrNull() ?: return "USD"
            val req = Request.Builder().url(url)
                .addHeader("Authorization", authHeader()).addHeader("Accept", "application/json").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return "USD"
                val o = JsonParser.parseString(resp.body?.string() ?: "{}").asJsonObject
                (o.get("currencyCode")?.asString ?: "USD").also { accountCurrency = it }
            }
        } catch (e: Exception) { "USD" }
    }

    fun fetchAccountSummary(): T212Result<AccountSummary> {
        if (!config.hasT212Credentials) return T212Result.NotConfigured
        return try {
            val url = "${config.t212BaseUrl}/equity/account/cash".toHttpUrlOrNull()
                ?: return T212Result.Failure("invalid base URL")
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", authHeader())
                .addHeader("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return T212Result.Failure("HTTP ${resp.code}", resp.code)
                }
                val body = resp.body?.string() ?: return T212Result.Failure("empty response")
                val o = JsonParser.parseString(body).asJsonObject
                T212Result.Success(
                    AccountSummary(
                        // T212 /equity/account/cash gives `free` (investable cash),
                        // `total` (account value), `invested`, `blocked`, `ppl`.
                        // There is NO `freeForStocks` field — that mapping always
                        // returned 0, which is why the FREE card read $0.
                        cash = o.get("free")?.asDouble ?: 0.0,
                        freeFunds = o.get("free")?.asDouble ?: 0.0,
                        totalValue = o.get("total")?.asDouble ?: 0.0,
                        currencyCode = fetchAccountCurrency()   // real account base currency (EUR/USD/…)
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchAccountSummary failed", e)
            T212Result.Failure(e.message ?: "unknown")
        }
    }

    // ── Portfolio (open positions) ─────────────────────────────────────────
    data class Position(
        val ticker: String,           // T212 internal ticker (e.g. "AAPL_US_EQ")
        val quantity: Double,
        val averagePrice: Double,
        val currentPrice: Double,
        val pplToday: Double,         // unrealised P/L today
        val pplTotal: Double          // unrealised P/L since open
    )

    // ── Instrument metadata (one shot, cached in memory) ────────────────────
    // T212's /equity/metadata/instruments returns ~10k instruments globally.
    // We fetch once, filter to the user's portfolio tickers, return as a map.
    // Rate limit: 1 req / 30s — we cache the parsed map for the lifetime of
    // this Trading212Client instance to stay well under it.
    data class InstrumentMeta(
        val ticker: String,
        val shortName: String,
        val currencyCode: String,
        val isin: String?,
        /**
         * Smallest order T212 will accept for this instrument, in SHARES.
         *
         * Per-instrument, not global: SRT3d_EQ happily traded 0.086 shares while
         * S92d_EQ rejects anything under 1.00 with
         * `min-opened-position-exceeded`. Ignoring it means opening positions the
         * app can never close through the API — the exit is refused every time and
         * the algo holds the name forever. Nullable because rows cached to disk
         * before this field existed deserialize without it.
         */
        val minTradeQuantity: Double? = null,
        /** Largest position T212 will let you hold in this instrument. Same source,
         *  same nullability caveat — read together they describe the legal size band. */
        val maxOpenQuantity: Double? = null
    )

    /**
     * Smallest order size T212 PUBLISHES for [ticker] — null when it doesn't.
     *
     * Measured 2026-08-07: it doesn't. `maxOpenQuantity` comes back populated
     * (6741 for S92d_EQ, 6231 for SAXd_EQ) while `minTradeQuantity` is absent for
     * the same instruments in the same response. So the metadata is being read
     * correctly and there simply is no published minimum. Use
     * [MIN_POSITION_VALUE] / [minSharesAt] for anything that has to decide.
     */
    fun minTradeQtyFor(ticker: String): Double? =
        (fetchInstrumentMetadata(setOf(ticker)) as? T212Result.Success)
            ?.data?.get(ticker)?.minTradeQuantity

    /**
     * Smallest number of SHARES that clears [MIN_POSITION_VALUE] at [price].
     *
     * The broker's floor is a cash amount, so the share equivalent depends entirely
     * on the price: €1 is 0.018 shares of a €55 stock and 0.004 shares of a €250
     * one. Any guard expressed in shares has to be derived here rather than
     * hardcoded — a fixed "1.00 shares" was 55× too strict and would have refused
     * ordinary trades.
     */
    fun minSharesAt(price: Double): Double =
        if (price > 0.0) MIN_POSITION_VALUE / price else 0.0

    private fun saveMetaToDisk(map: Map<String, InstrumentMeta>) {
        try {
            java.io.File(config.appContext.filesDir, META_DISK_FILE)
                .writeText(gson.toJson(map.values.toList()))
        } catch (e: Exception) { Log.w(TAG, "meta disk save failed", e) }
    }

    private fun loadMetaFromDisk(): Map<String, InstrumentMeta>? = try {
        // Retire the pre-versioning snapshot rather than leaving ~10k stale
        // instruments on disk forever.
        try {
            val old = java.io.File(config.appContext.filesDir, META_DISK_FILE_OLD)
            if (old.exists()) old.delete()
        } catch (e: Exception) { Log.w(TAG, "old meta cleanup failed", e) }
        val f = java.io.File(config.appContext.filesDir, META_DISK_FILE)
        if (!f.exists()) null
        else gson.fromJson(f.readText(), Array<InstrumentMeta>::class.java)
            ?.associateBy { it.ticker }?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) { Log.w(TAG, "meta disk load failed", e); null }

    /** How long ago the instrument list was last fetched LIVE, or null if never in
     *  this process. Lets a caller say "cached, possibly incomplete" instead of
     *  presenting a stale snapshot as though the broker had just answered. */
    fun metaAgeMs(): Long? = if (metaFetchedAt == 0L) null else System.currentTimeMillis() - metaFetchedAt

    /** A live fetch failed — serve ANY stale list (memory, then disk) rather
     *  than failing resolution: the instrument universe changes so rarely that
     *  yesterday's list is near-certainly correct, and a wrong "can't resolve"
     *  error is far more disruptive than a slightly stale display name. */
    private fun staleMetaOr(
        fail: T212Result<Map<String, InstrumentMeta>>, tickers: Set<String>?
    ): T212Result<Map<String, InstrumentMeta>> {
        val stale = metaCache ?: loadMetaFromDisk()?.also {
            metaCache = it   // memory-cache it; fetchedAt stays old so a live refresh is still attempted later
        }
        return if (stale != null)
            T212Result.Success(if (tickers == null) stale else stale.filterKeys { it in tickers })
        else fail
    }

    fun fetchInstrumentMetadata(tickers: Set<String>? = null): T212Result<Map<String, InstrumentMeta>> {
        if (!config.hasT212Credentials) return T212Result.NotConfigured
        // Serve from cache when fresh
        metaCache?.let {
            if (System.currentTimeMillis() - metaFetchedAt < META_TTL_MS) {
                val filtered = if (tickers == null) it else it.filterKeys { k -> k in tickers }
                return T212Result.Success(filtered)
            }
        }
        return try {
            val url = "${config.t212BaseUrl}/equity/metadata/instruments".toHttpUrlOrNull()
                ?: return T212Result.Failure("invalid base URL")
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", authHeader())
                .addHeader("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful)
                    return staleMetaOr(T212Result.Failure("HTTP ${resp.code}", resp.code), tickers)
                val body = resp.body?.string()
                    ?: return staleMetaOr(T212Result.Failure("empty response"), tickers)
                val arr = JsonParser.parseString(body).asJsonArray
                val map = mutableMapOf<String, InstrumentMeta>()
                for (el in arr) {
                    try {
                        val o = el.asJsonObject
                        val t = o.get("ticker")?.asString ?: continue
                        map[t] = InstrumentMeta(
                            ticker = t,
                            shortName = o.get("shortName")?.asString
                                ?: o.get("name")?.asString ?: t,
                            currencyCode = o.get("currencyCode")?.asString ?: "USD",
                            isin = o.get("isin")?.asString,
                            minTradeQuantity = o.get("minTradeQuantity")
                                ?.takeIf { !it.isJsonNull }?.asDouble,
                            maxOpenQuantity = o.get("maxOpenQuantity")
                                ?.takeIf { !it.isJsonNull }?.asDouble
                        )
                    } catch (e: Exception) { /* skip malformed */ }
                }
                metaCache = map
                metaFetchedAt = System.currentTimeMillis()
                saveMetaToDisk(map)
                Log.i(TAG, "Fetched ${map.size} T212 instrument names")
                val filtered = if (tickers == null) map else map.filterKeys { k -> k in tickers }
                T212Result.Success(filtered)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchInstrumentMetadata failed", e)
            staleMetaOr(T212Result.Failure(e.message ?: "unknown"), tickers)
        }
    }

    // ── Bulletproof Yahoo-symbol → T212-ticker resolution ───────────────────
    // Built from the real instrument list (not a guess): index every instrument
    // by its ticker-base (before "_") AND its shortName, preferring the venue
    // that matches the space. Resolve returns null if we can't be SURE — the
    // caller then errors out rather than sending a wrong-symbol order.
    // (reverseIdx lives in the companion — shared across the per-call client
    // instances — and is keyed by space, since venue preference differs.)

    /** T212 tags the venue on the ticker: US = "…_US_EQ", but European venues use
     *  a trailing lowercase letter on the base (Xetra = 'd', e.g. "MUV2d_EQ") —
     *  NOT "_DE_EQ" (which does not exist in T212). Match both to be safe. */
    private fun onWantedVenue(ticker: String, space: String): Boolean =
        if (space.uppercase() == "DE") ticker.endsWith("_DE_EQ") || ticker.endsWith("d_EQ")
        else ticker.endsWith("_US_EQ")

    private fun buildReverseIndex(meta: Map<String, InstrumentMeta>, space: String): Map<String, String> {
        val idx = HashMap<String, String>()
        fun consider(rawKey: String?, ticker: String) {
            val k = rawKey?.trim()?.uppercase() ?: return
            if (k.isBlank()) return
            val existing = idx[k]
            // Prefer a ticker on the wanted venue; otherwise first-wins.
            if (existing == null || (!onWantedVenue(existing, space) && onWantedVenue(ticker, space))) idx[k] = ticker
        }
        for ((ticker, m) in meta) {
            val base = ticker.substringBefore("_")
            consider(base, ticker)
            // Strip the trailing lowercase venue letter so a bare Yahoo symbol
            // ("MUV2") resolves to the Xetra ticker ("MUV2d_EQ").
            if (base.length > 1 && base.last().isLowerCase()) consider(base.dropLast(1), ticker)
            consider(m.shortName, ticker)
        }
        return idx
    }

    /** Resolve a Yahoo symbol to the exact T212 instrument ticker, or null if
     *  it can't be confirmed from the live instrument list. */
    fun resolveT212Ticker(yahooSymbol: String, space: String = "US"): String? {
        val sym = yahooSymbol.trim().uppercase().substringBefore(".")
        if (sym.isBlank()) return null
        val metaRes = fetchInstrumentMetadata()
        val meta = (metaRes as? T212Result.Success)?.data ?: metaCache ?: return null
        val isUs = space.uppercase() == "US"
        // 1) Exact ticker present (with the FB↔META style alias applied).
        val direct = if (space.uppercase() == "DE") listOf("${sym}d_EQ", "${sym}_DE_EQ")
                     else listOf("${aliasUsTicker(sym, isUs)}_US_EQ")
        for (d in direct) if (meta.containsKey(d)) return d
        // 2) Reverse index by base / shortName.
        // Space check matters now that the index is shared companion state: an
        // index built with DE venue preference must not serve a US resolve.
        val idx = reverseIdx?.takeIf {
            System.currentTimeMillis() - reverseIdxAt < META_TTL_MS && reverseIdxSpace == space.uppercase()
        } ?: buildReverseIndex(meta, space).also {
            reverseIdx = it; reverseIdxAt = System.currentTimeMillis(); reverseIdxSpace = space.uppercase()
        }
        for (c in listOf(sym, aliasUsTicker(sym, isUs))) idx[c.uppercase()]?.let { return it }
        return null
    }

    /** Human-readable reason a resolveT212Ticker() came back null, so a failed
     *  order tells the user WHICH problem it is: metadata unavailable (infra),
     *  the account has no equities on that venue (can't trade US at all), or the
     *  symbol simply isn't offered (delisted / different ticker). */
    fun resolveFailureReason(yahooSymbol: String, space: String): String {
        // Cache-ONLY — never trigger another metadata fetch here, or a failed
        // resolve doubles the load on the 1-req/30s endpoint and self-inflicts 429s.
        val meta = metaCache
        if (meta.isNullOrEmpty()) {
            return "T212 instrument list not loaded yet — the metadata endpoint is rate-limited (1 request / 30s). Wait ~30s and retry, OR type the exact T212 ticker (shown under each stock, e.g. \"MUV2d_EQ\") to bypass symbol resolution entirely."
        }
        val us = meta.keys.count { onWantedVenue(it, "US") }
        val de = meta.keys.count { onWantedVenue(it, "DE") }   // Xetra "d_EQ", not "_DE_EQ"
        val venue = if (space.uppercase() == "DE") "DE" else "US"
        val avail = if (venue == "US") us else de
        return if (avail == 0)
            "your T212 account exposes ${meta.size} instruments but 0 $venue-listed equities — this account cannot trade $venue stocks via the API (US=$us, DE=$de)."
        else
            "$avail $venue equities are available, but \"${yahooSymbol.trim().uppercase()}\" isn't one — likely delisted, not offered, or listed under a different ticker."
    }

    /** Cancel every PENDING order on [ticker] (so a fresh market order isn't
     *  refused by reserved shares). Returns how many were cancelled. */
    fun cancelOpenOrdersForTicker(ticker: String): Int {
        if (!config.hasT212Credentials) return 0
        val body = (fetchOpenOrders() as? T212Result.Success)?.data ?: return 0
        var n = 0
        try {
            val arr = JsonParser.parseString(body).asJsonArray
            for (el in arr) {
                val o = el.asJsonObject
                val t = o.get("ticker")?.asString ?: continue
                if (!t.equals(ticker, ignoreCase = true)) continue
                val id = o.get("id")?.asLong ?: continue
                if (cancelOrder(id) is T212Result.Success) n++
            }
        } catch (e: Exception) { Log.w(TAG, "cancelOpenOrdersForTicker parse failed", e) }
        openOrdersCacheAt = 0L
        return n
    }

    /** Convert a T212 ticker (e.g. "KO_US_EQ", "SAP_DE_EQ") to its Yahoo
     *  Finance equivalent ("KO", "SAP.DE") so we can pull candles + run
     *  StockAnalyzer on the position. Returns the bare ticker as fallback
     *  for unknown markets. */
    /** US tickers that changed symbol but which T212 still lists under the OLD
     *  internal ticker. Without this, resolving the old symbol on Yahoo returns
     *  a DIFFERENT security that reused the freed-up ticker.
     *    FB  → META  : Meta renamed FB→META (Jun 2022); "FB" is now a ProShares ETF.
     *  Extend cautiously — only for confirmed 1:1 US renames. */
    private val US_TICKER_ALIASES = mapOf(
        "FB" to "META"
    )

    private fun aliasUsTicker(base: String, isUs: Boolean): String =
        if (isUs) US_TICKER_ALIASES[base.uppercase()] ?: base else base

    // Inverse of US_TICKER_ALIASES for building a T212 order ticker from a Yahoo
    // symbol (e.g. our lab enrolls META → T212 still lists it as FB_US_EQ).
    private val YAHOO_TO_T212_US = US_TICKER_ALIASES.entries.associate { (k, v) -> v to k }

    /** Best-effort Yahoo symbol → T212 instrument ticker (e.g. "AAPL" → "AAPL_US_EQ",
     *  "META" → "FB_US_EQ"). Heuristic — T212 may list a name differently; the
     *  order is recorded as an error (never a wrong-symbol order) if T212 rejects it. */
    fun yahooToT212Ticker(symbol: String, space: String = "US"): String {
        val s = symbol.trim().uppercase().substringBefore(".")
        return when (space.uppercase()) {
            "DE" -> "${s}_DE_EQ"
            else -> "${YAHOO_TO_T212_US[s] ?: s}_US_EQ"
        }
    }

    fun t212ToYahooSymbol(t212Ticker: String): String {
        val parts = t212Ticker.split("_")
        if (parts.size < 2) return t212Ticker
        // Modern T212 Xetra format tags the venue as a trailing LOWERCASE 'd' on
        // the base itself ("PUMd_EQ" = Puma on Xetra) — there is no "_DE_"
        // segment to switch on. Without this branch the function returned the
        // bare base ("PUMd"), and Yahoo — being case-insensitive — resolved it
        // as the US OTC ticker PUMD (ProUroCare Medical), attaching a wrong
        // company to a German holding. Real ticker letters are uppercase, so a
        // lowercase 'd' tail is unambiguous.
        val rawBase = parts[0]
        if (rawBase.length > 1 && rawBase.last() == 'd') {
            return aliasUsTicker(rawBase.dropLast(1), false).uppercase() + ".DE"
        }
        val base = aliasUsTicker(parts[0], parts[1] == "US")
        return when (parts[1]) {
            "US"  -> base               // NASDAQ / NYSE — no suffix
            "DE"  -> "$base.DE"         // Xetra
            "L"   -> "$base.L"          // LSE
            "MI"  -> "$base.MI"         // Borsa Italiana
            "PA"  -> "$base.PA"         // Euronext Paris
            "AS"  -> "$base.AS"         // Euronext Amsterdam
            "MC"  -> "$base.MC"         // BME Madrid
            "BR"  -> "$base.BR"         // Euronext Brussels
            "LS"  -> "$base.LS"         // Euronext Lisbon
            "ST"  -> "$base.ST"         // Stockholm
            "HE"  -> "$base.HE"         // Helsinki
            "OL"  -> "$base.OL"         // Oslo
            "WA"  -> "$base.WA"         // Warsaw
            "SW"  -> "$base.SW"         // Swiss Exchange
            else  -> base
        }
    }

    fun t212ToYahooCandidates(t212Ticker: String, currencyCode: String? = null): List<String> {
        val parts = t212Ticker.split("_").map { it.trim().uppercase() }
        // Apply the US rename alias (e.g. FB→META) up front so EVERY derived
        // candidate — including the bare-base fallback — uses the correct symbol.
        val base = aliasUsTicker(t212Ticker.substringBefore("_").trim(), parts.contains("US"))
        if (base.isEmpty()) return emptyList()
        val candidates = linkedSetOf<String>()
        fun add(symbol: String) {
            val s = symbol.trim().uppercase()
            if (s.isNotBlank()) candidates.add(s)
        }

        add(t212ToYahooSymbol(t212Ticker))

        // ── Concatenated-short-form fix (e.g. TUI1D → TUI1.DE) ───────────────
        // T212 sometimes appends a single-letter venue tag DIRECTLY to the base
        // with no underscore separator. Examples seen in the wild:
        //   "TUI1D"            → Yahoo "TUI1.DE"   (Xetra, trailing D)
        //   "TUI1D_DE_EQ"      → Yahoo "TUI1.DE"   (full form also has the D)
        // In both cases the base 'TUI1D' carries a redundant trailing 'D' that
        // must be stripped before reattaching the Yahoo suffix. Without this
        // step the analyzer asks Yahoo for "TUI1D.DE" which doesn't exist and
        // every German concatenated-form holding gets "no scan" in Portfolio.
        // Conservative — only single letter 'D' (Xetra) is mapped here. False
        // positive cost is one extra (failed) Yahoo lookup per scan.
        val baseUpper = base.uppercase()
        if (baseUpper.length >= 3) {
            val shortSuffix = yahooSuffixForT212VenueShort(baseUpper.last().toString())
            if (shortSuffix != null) {
                val trimmed = baseUpper.dropLast(1)
                add(if (shortSuffix.isEmpty()) trimmed else "$trimmed$shortSuffix")
            }
        }

        parts.drop(1).firstNotNullOfOrNull { yahooSuffixForT212Venue(it) }?.let { suffix ->
            add(if (suffix.isEmpty()) base else "$base$suffix")
        }

        val knownVenue = parts.drop(1).any { yahooSuffixForT212Venue(it) != null }
        val ccy = currencyCode?.trim()?.uppercase().orEmpty()
        if (!parts.contains("US")) {
            when (ccy) {
                "EUR" -> {
                    add("$base.DE")
                    add("$base.PA")
                    add("$base.MI")
                    add("$base.AS")
                    add("$base.BR")
                    add("$base.LS")
                    add("$base.MC")
                }
                "GBP", "GBX" -> add("$base.L")
                "CHF" -> add("$base.SW")
                "SEK" -> add("$base.ST")
                "NOK" -> add("$base.OL")
                "PLN" -> add("$base.WA")
            }
            if (!knownVenue) add("$base.DE")
        }
        add(base)
        return candidates.toList()
    }

    /** Single-character venue tag → Yahoo suffix. Used for the
     *  concatenated-short-form mapping (e.g. trailing 'D' in TUI1D → .DE).
     *  Deliberately narrow to avoid false positives on legitimate ticker
     *  bases that happen to end in a single letter. Extend with caution
     *  after observing real T212 ticker formats in production. */
    private fun yahooSuffixForT212VenueShort(c: String): String? = when (c.uppercase()) {
        "D" -> ".DE"   // Xetra single-letter tag — TUI1D, etc.
        else -> null
    }

    private fun yahooSuffixForT212Venue(venue: String): String? {
        return when (venue.trim().uppercase()) {
            "US" -> ""
            "DE", "XETR", "XETRA", "GER", "FRA", "FWB" -> ".DE"
            "L", "LN", "LSE" -> ".L"
            "MI", "MIL" -> ".MI"
            "PA", "PAR" -> ".PA"
            "AS", "AMS" -> ".AS"
            "MC", "MAD" -> ".MC"
            "BR", "BRU" -> ".BR"
            "LS", "LIS" -> ".LS"
            "ST", "STO" -> ".ST"
            "HE", "HEL" -> ".HE"
            "OL", "OSL" -> ".OL"
            "WA", "WSE" -> ".WA"
            "SW", "SIX", "SWX" -> ".SW"
            else -> null
        }
    }

    fun fetchPortfolio(): T212Result<List<Position>> {
        if (!config.hasT212Credentials) return T212Result.NotConfigured
        return try {
            val url = "${config.t212BaseUrl}/equity/portfolio".toHttpUrlOrNull()
                ?: return T212Result.Failure("invalid base URL")
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", authHeader())
                .addHeader("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return T212Result.Failure("HTTP ${resp.code}", resp.code)
                }
                val body = resp.body?.string() ?: return T212Result.Failure("empty response")
                val arr = JsonParser.parseString(body).asJsonArray
                val positions = arr.mapNotNull { el ->
                    try {
                        val o = el.asJsonObject
                        Position(
                            ticker = o.get("ticker")?.asString ?: return@mapNotNull null,
                            quantity = o.get("quantity")?.asDouble ?: 0.0,
                            averagePrice = o.get("averagePrice")?.asDouble ?: 0.0,
                            currentPrice = o.get("currentPrice")?.asDouble ?: 0.0,
                            pplToday = o.get("ppl")?.asDouble ?: 0.0,
                            pplTotal = (o.get("currentPrice")?.asDouble ?: 0.0) -
                                       (o.get("averagePrice")?.asDouble ?: 0.0)
                        )
                    } catch (e: Exception) { null }
                }
                T212Result.Success(positions)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchPortfolio failed", e)
            T212Result.Failure(e.message ?: "unknown")
        }
    }

    // ── Order placement (Phase 2B.1) ───────────────────────────────────────
    // T212's order endpoints all expect JSON bodies. Quantity sign convention:
    //   positive = BUY, negative = SELL. We hide that from the caller via the
    //   `side` parameter and apply the sign here.
    //
    // Endpoints (per https://t212public-api-docs.redoc.ly/#tag/Equity-Orders):
    //   POST /equity/orders/limit       — limit order
    //   POST /equity/orders/market      — market order
    //   POST /equity/orders/stop        — stop (becomes market when triggered)
    //   POST /equity/orders/stop_limit  — stop-limit
    //   DELETE /equity/orders/{id}      — cancel
    //   GET    /equity/orders           — list pending
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val gson = com.google.gson.Gson()

    /**
     * Unified order-placement primitive. Returns the real T212 order id.
     *
     * @param side        "BUY" or "SELL" — converted to ± sign on quantity
     * @param orderType   "MARKET" / "LIMIT" / "STOP" / "STOP_LIMIT"
     * @param limitPrice  required for LIMIT and STOP_LIMIT
     * @param stopPrice   required for STOP and STOP_LIMIT
     * @param timeValidity  "DAY" or "GOOD_TILL_CANCEL". Default GOOD_TILL_CANCEL.
     */
    fun placeOrder(
        ticker: String,
        quantity: Double,
        side: String,
        orderType: String,
        limitPrice: Double? = null,
        stopPrice: Double? = null,
        timeValidity: String = "GOOD_TILL_CANCEL"
    ): T212Result<Long> {
        if (!config.hasT212Credentials) return T212Result.NotConfigured
        val signedQty = if (side.equals("SELL", ignoreCase = true)) -kotlin.math.abs(quantity) else kotlin.math.abs(quantity)

        val endpoint = when (orderType.uppercase()) {
            "MARKET"     -> "/equity/orders/market"
            "LIMIT"      -> "/equity/orders/limit"
            "STOP"       -> "/equity/orders/stop"
            "STOP_LIMIT" -> "/equity/orders/stop_limit"
            else -> return T212Result.Failure("unknown orderType: $orderType")
        }
        val body = mutableMapOf<String, Any?>(
            "ticker" to ticker,
            "quantity" to signedQty
        )
        if (!orderType.equals("MARKET", ignoreCase = true)) body["timeValidity"] = timeValidity
        if (limitPrice != null) body["limitPrice"] = limitPrice
        if (stopPrice  != null) body["stopPrice"]  = stopPrice
        val payload = gson.toJson(body)

        return try {
            val url = "${config.t212BaseUrl}$endpoint".toHttpUrlOrNull()
                ?: return T212Result.Failure("invalid base URL")
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", authHeader())
                .addHeader("Accept", "application/json")
                .post(payload.toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string()?.take(200)?.replace("\"","'") ?: ""
                    val hint = if (
                        errBody.contains("selling-equity-not-owned", ignoreCase = true) ||
                        errBody.contains("owned:0.0", ignoreCase = true) ||
                        errBody.contains("owned: 0.0", ignoreCase = true)
                    ) {
                        " Hint: T212 has no unreserved shares available, usually because another pending sell order is already reserving the position. Cancel that pending sell order first."
                    } else ""
                    Log.w(TAG, "T212 order rejected ${resp.code} endpoint=$endpoint payload=$payload body=$errBody")
                    return T212Result.Failure("HTTP ${resp.code} $errBody$hint payload=$payload", resp.code)
                }
                val respBody = resp.body?.string() ?: return T212Result.Failure("empty response")
                val o = JsonParser.parseString(respBody).asJsonObject
                val id = o.get("id")?.asLong ?: 0L
                openOrdersCacheAt = 0L
                // Audit-log the real placement
                try {
                    com.signalscope.app.data.GttActivityLog.append(
                        config.appContext, "create", symbol = ticker,
                        fields = mapOf(
                            "broker" to "t212",
                            "type" to orderType,
                            "side" to side,
                            "qty" to signedQty,
                            "limitPrice" to limitPrice,
                            "stopPrice" to stopPrice,
                            "tv" to timeValidity,
                            "id" to id
                        )
                    )
                } catch (_: Exception) {}
                Log.i(TAG, "T212 order placed id=$id $orderType $side $ticker")
                T212Result.Success(id)
            }
        } catch (e: Exception) {
            Log.e(TAG, "placeOrder failed", e)
            T212Result.Failure(e.message ?: "unknown")
        }
    }

    fun cancelOrder(orderId: Long): T212Result<Boolean> {
        if (!config.hasT212Credentials) return T212Result.NotConfigured
        return try {
            val url = "${config.t212BaseUrl}/equity/orders/$orderId".toHttpUrlOrNull()
                ?: return T212Result.Failure("invalid base URL")
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", authHeader())
                .delete()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string()?.take(200)?.replace("\"","'") ?: ""
                    return T212Result.Failure("HTTP ${resp.code} $errBody", resp.code)
                }
                openOrdersCacheAt = 0L
                try {
                    com.signalscope.app.data.GttActivityLog.append(
                        config.appContext, "delete",
                        fields = mapOf("broker" to "t212", "id" to orderId)
                    )
                } catch (_: Exception) {}
                T212Result.Success(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "cancelOrder failed", e)
            T212Result.Failure(e.message ?: "unknown")
        }
    }

    /** Returns the raw JSON array of pending orders so the caller can pick
     *  the fields they care about without us coupling to the schema. */
    /** One EXECUTED order as reported by T212's history — the real fill. */
    data class HistoricalFill(
        val orderId: Long,
        val ticker: String,
        val filledQuantity: Double,
        val fillPrice: Double,          // per-share, in the instrument's currency
        val filledValue: Double,        // total cash moved, account currency
        val side: String,               // BUY / SELL (inferred from qty sign if absent)
        val dateExecuted: String,
        val status: String,
        /** The limit we placed. fillPrice vs this IS the execution quality of the
         *  patient ladder — a fill better than the limit is price improvement. */
        val limitPrice: Double = 0.0
    )

    /**
     * Executed-order history — the ONLY source of the real fill price.
     *
     * placeOrder returns an order id, not a fill: the execution happens later, so
     * the price has to be read back afterwards. Without this the app stored
     * buyOrderId/sellOrderId next to the paper prices and could never say what the
     * order actually cost, which is why SlippageStats had no T212 samples to work
     * with even ignoring the entry freeze.
     *
     * DEFENSIVE PARSING ON PURPOSE: the exact history path and field names are not
     * verifiable from inside this codebase, and T212 has revised both. Several
     * candidate paths are tried in order, and each field accepts the plausible
     * aliases; anything unrecognised is skipped rather than guessed at. If every
     * path 404s the failure names them all so the fix is a one-line path change,
     * not an investigation.
     */
    fun fetchOrderHistory(limit: Int = 50, cursor: String? = null): T212Result<List<HistoricalFill>> {
        if (!config.hasT212Credentials) return T212Result.NotConfigured
        val paths = listOf("/equity/history/orders", "/history/orders", "/equity/orders/history")
        val tried = ArrayList<String>()
        for (p in paths) {
            val base = "${config.t212BaseUrl}$p".toHttpUrlOrNull() ?: continue
            val url = base.newBuilder()
                .addQueryParameter("limit", limit.coerceIn(1, 50).toString())
                .apply { if (!cursor.isNullOrBlank()) addQueryParameter("cursor", cursor) }
                .build()
            try {
                val req = Request.Builder().url(url)
                    .addHeader("Authorization", authHeader())
                    .addHeader("Accept", "application/json").build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code == 404) { tried.add("$p→404"); return@use }
                    if (!resp.isSuccessful) { tried.add("$p→${resp.code}"); return@use }
                    val body = resp.body?.string().orEmpty()
                    return T212Result.Success(parseFills(body))
                }
            } catch (e: Exception) {
                tried.add("$p→${e.message}")
            }
        }
        return T212Result.Failure("no history endpoint responded (${tried.joinToString(", ")})")
    }

    /**
     * The RAW history response, for diagnostics.
     *
     * "The endpoint responded but returned no executed orders" is ambiguous: it can
     * mean an empty account, the wrong path answering 200 with an empty array, or a
     * payload whose field names [parseFills] doesn't recognise so every row is
     * skipped. Guessing between those from here is impossible — this returns which
     * path answered, the HTTP code, and the first slice of the body so the shape can
     * be read off the screen instead of inferred.
     */
    fun fetchOrderHistoryRaw(limit: Int = 50): T212Result<String> {
        if (!config.hasT212Credentials) return T212Result.NotConfigured
        val paths = listOf("/equity/history/orders", "/history/orders", "/equity/orders/history")
        val log = StringBuilder()
        for (p in paths) {
            val base = "${config.t212BaseUrl}$p".toHttpUrlOrNull() ?: continue
            val url = base.newBuilder()
                .addQueryParameter("limit", limit.coerceIn(1, 50).toString()).build()
            try {
                val req = Request.Builder().url(url)
                    .addHeader("Authorization", authHeader())
                    .addHeader("Accept", "application/json").build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    log.append("$p → HTTP ${resp.code}, ${body.length} bytes\n")
                    if (resp.isSuccessful) {
                        log.append(body.take(1200))
                        if (body.length > 1200) log.append("\n…(truncated)")
                        return T212Result.Success(log.toString())
                    }
                    log.append(body.take(200)).append('\n')
                }
            } catch (e: Exception) { log.append("$p → ${e.message}\n") }
        }
        return T212Result.Failure(log.toString().ifBlank { "no response from any history path" })
    }

    private fun parseFills(body: String): List<HistoricalFill> {
        val out = ArrayList<HistoricalFill>()
        try {
            // Either a bare array or an object wrapping one under items/data/orders.
            val arr: org.json.JSONArray = when {
                body.trimStart().startsWith("[") -> org.json.JSONArray(body)
                else -> {
                    val o = org.json.JSONObject(body)
                    listOf("items", "data", "orders", "results")
                        .firstNotNullOfOrNull { o.optJSONArray(it) } ?: org.json.JSONArray()
                }
            }
            fun org.json.JSONObject.num(vararg keys: String): Double {
                for (k in keys) if (has(k) && !isNull(k)) {
                    val d = optDouble(k, Double.NaN)
                    if (!d.isNaN()) return d
                }
                return 0.0
            }
            fun org.json.JSONObject.str(vararg keys: String): String {
                for (k in keys) if (has(k) && !isNull(k)) {
                    val s = optString(k, "")
                    if (s.isNotBlank()) return s
                }
                return ""
            }
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                // VERIFIED SHAPE (live payload, 2026-08-06): each item NESTS the
                // request and the execution —
                //   {"order":{id,ticker,side,status,limitPrice,filledQuantity,…},
                //    "fill" :{id,quantity,price,filledAt,walletImpact:{netValue}}}
                // Reading fields off the top level found nothing, so every row was
                // skipped and the panel reported "no executed orders". A CANCELLED
                // rung has no "fill" object at all — correctly dropped here, which is
                // what a patient ladder's unfilled rungs should do. The flat fallback
                // keeps older/other endpoint shapes working.
                val order = row.optJSONObject("order") ?: row
                val fill = row.optJSONObject("fill")

                val qty = fill?.num("quantity", "filledQuantity")?.takeIf { it != 0.0 }
                    ?: order.num("filledQuantity", "quantity", "filledQty")
                // Price must come from the FILL. order.limitPrice is what we ASKED
                // for, not what we got — using it would silently report zero slippage.
                var price = fill?.num("price", "fillPrice")?.takeIf { it > 0.0 }
                    ?: order.num("fillPrice", "averagePrice", "avgPrice", "executedPrice")
                val value = fill?.optJSONObject("walletImpact")?.num("netValue")?.takeIf { it != 0.0 }
                    ?: order.num("filledValue", "fillCost", "value", "total")
                // Some payloads carry only the total — derive the per-share price
                // rather than dropping an otherwise usable fill.
                if (price <= 0.0 && value > 0.0 && kotlin.math.abs(qty) > 0.0)
                    price = kotlin.math.abs(value / qty)
                if (price <= 0.0 || kotlin.math.abs(qty) <= 0.0) continue   // unfilled/cancelled
                val id = order.num("id", "orderId", "fillId").toLong()
                val sideRaw = order.str("side", "direction").uppercase()
                val side = when {
                    sideRaw.contains("BUY") -> "BUY"
                    sideRaw.contains("SELL") -> "SELL"
                    qty < 0.0 -> "SELL"
                    else -> "BUY"
                }
                val inst = order.optJSONObject("instrument")
                out.add(HistoricalFill(
                    orderId = id,
                    ticker = order.str("ticker", "instrumentCode", "symbol")
                        .ifBlank { inst?.str("ticker", "name") ?: "" },
                    filledQuantity = kotlin.math.abs(qty),
                    fillPrice = price,
                    filledValue = if (value != 0.0) kotlin.math.abs(value) else price * kotlin.math.abs(qty),
                    side = side,
                    dateExecuted = fill?.str("filledAt", "dateExecuted")
                        ?: order.str("dateExecuted", "dateModified", "createdAt", "time"),
                    status = order.str("status", "state").ifBlank { "FILLED" },
                    // What we asked for, so execution quality is directly visible:
                    // your AIXA rung asked 38.68 and filled at 38.46.
                    limitPrice = order.num("limitPrice", "stopPrice")))
            }
        } catch (e: Exception) {
            Log.w(TAG, "order-history parse failed: ${e.message}")
        }
        return out
    }

    fun fetchOpenOrders(): T212Result<String> {
        if (!config.hasT212Credentials) return T212Result.NotConfigured
        return try {
            val now = System.currentTimeMillis()
            if (openOrdersCacheJson.isNotBlank() &&
                openOrdersCacheBaseUrl == config.t212BaseUrl &&
                now - openOrdersCacheAt < OPEN_ORDERS_CACHE_MS
            ) {
                return T212Result.Success(openOrdersCacheJson)
            }
            val url = "${config.t212BaseUrl}/equity/orders".toHttpUrlOrNull()
                ?: return T212Result.Failure("invalid base URL")
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", authHeader())
                .addHeader("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return T212Result.Failure("HTTP ${resp.code}", resp.code)
                val body = resp.body?.string() ?: "[]"
                openOrdersCacheBaseUrl = config.t212BaseUrl
                openOrdersCacheJson = body
                openOrdersCacheAt = now
                T212Result.Success(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchOpenOrders failed", e)
            T212Result.Failure(e.message ?: "unknown")
        }
    }
}

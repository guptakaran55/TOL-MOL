package com.signalscope.app.network

import android.util.Log
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.CandleData
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Binance public market-data fetcher (spot klines) for the crypto Strategy Lab.
 *
 * Only the PUBLIC market-data REST endpoint is used (`/api/v3/klines`) — it needs
 * NO API key/secret, which is exactly right for the PAPER-only crypto lab. Real
 * spot execution (later) will add a separate, explicitly-authenticated client.
 *
 * Mirrors the desktop `BinanceClient.get_klines(symbol, interval="15m")` used by
 * the validated Streamlit project: fetch 15-minute candles, hand them to the same
 * pure MACD engine the US lab uses, run it as a forward paper loop.
 *
 * Returns the shared [YahooFinanceClient.CandleResult] container so the
 * StrategyLabRunner can treat Yahoo (stocks) and Binance (crypto) identically.
 */
object BinanceClient {

    private const val TAG = "BinanceClient"
    private const val RECV_WINDOW_MS = 5_000L
    private const val RULES_TTL_MS = 6 * 60 * 60 * 1000L

    // Binance's signed-capable REST hosts. The data-api mirror is market-data
    // only, so it must never be used for account/order endpoints.
    private val SIGNED_HOSTS = listOf(
        "api.binance.com",
        "api-gcp.binance.com",
        "api1.binance.com",
        "api2.binance.com",
        "api3.binance.com",
        "api4.binance.com"
    )

    // Public market-data hosts. data-api.binance.vision is intentionally last:
    // useful for klines/exchangeInfo fallback, never for signed orders.
    private val MARKET_DATA_HOSTS = SIGNED_HOSTS + "data-api.binance.vision"

    // 15-min bars; 1500 is the Binance per-call max — gives enough warmup history
    // (≈15.6 days) for a 60-min resample with a deep warmup, plus forward room.
    private const val KLINE_LIMIT = 1500
    private val rulesCache = ConcurrentHashMap<String, CachedRules>()

    /** Quote assets the app trades against on spot. USDC is included so users
     *  who keep cash in USDC can trade *USDC pairs directly, with no implicit
     *  USDT conversion (which incurs a spread/fee). Longest suffix wins. */
    val SUPPORTED_QUOTES = listOf("USDT", "USDC")

    /** The quote asset of a pair (e.g. BTCUSDC → "USDC"), or null if unsupported. */
    fun quoteAssetOf(symbol: String): String? {
        val s = symbol.trim().uppercase()
        return SUPPORTED_QUOTES.firstOrNull { s.endsWith(it) }
    }

    /** The base asset of a pair (e.g. BTCUSDC → "BTC"), or null if unsupported. */
    fun baseAssetOf(symbol: String): String? {
        val s = symbol.trim().uppercase()
        return quoteAssetOf(s)?.let { s.removeSuffix(it) }
    }

    private val ipv4PreferredDns = object : Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            val v4 = all.filter { it is java.net.Inet4Address }
            val v6 = all.filter { it !is java.net.Inet4Address }
            return v4 + v6
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .dns(ipv4PreferredDns)
        .protocols(listOf(Protocol.HTTP_1_1))   // HTTP/2 stalls on MIUI — match YahooFinanceClient
        .build()

    sealed class BinanceResult<out T> {
        data class Success<T>(val data: T) : BinanceResult<T>()
        data class Failure(val message: String) : BinanceResult<Nothing>()
        object NotConfigured : BinanceResult<Nothing>()
    }

    data class Balance(
        val asset: String,
        val free: String,
        val locked: String
    )

    data class AccountSummary(
        val canTrade: Boolean,
        val accountType: String,
        val balances: Map<String, Balance>
    )

    private data class CachedRules(
        val rules: SymbolRules,
        val fetchedAt: Long
    )

    data class SymbolRules(
        val symbol: String,
        val status: String,
        val lotMinQty: BigDecimal,
        val lotMaxQty: BigDecimal,
        val lotStepSize: BigDecimal,
        val marketMinQty: BigDecimal,
        val marketMaxQty: BigDecimal,
        val marketStepSize: BigDecimal,
        val minNotional: BigDecimal,
        val minNotionalAppliesToMarket: Boolean,
        val notionalMin: BigDecimal,
        val notionalMax: BigDecimal,
        val notionalApplyMinToMarket: Boolean,
        val notionalApplyMaxToMarket: Boolean
    ) {
        val effectiveMarketStepSize: BigDecimal
            get() = marketStepSize.takeIf { it > BigDecimal.ZERO } ?: lotStepSize

        val effectiveMarketMinQty: BigDecimal
            get() = marketMinQty.takeIf { it > BigDecimal.ZERO } ?: lotMinQty

        val effectiveMarketMaxQty: BigDecimal
            get() = marketMaxQty.takeIf { it > BigDecimal.ZERO } ?: lotMaxQty

        val effectiveMarketMinNotional: BigDecimal
            get() {
                val candidates = ArrayList<BigDecimal>()
                if (minNotionalAppliesToMarket && minNotional > BigDecimal.ZERO) candidates.add(minNotional)
                if (notionalApplyMinToMarket && notionalMin > BigDecimal.ZERO) candidates.add(notionalMin)
                return candidates.maxOrNull() ?: BigDecimal.ZERO
            }

        val effectiveMarketMaxNotional: BigDecimal
            get() = if (notionalApplyMaxToMarket && notionalMax > BigDecimal.ZERO) notionalMax else BigDecimal.ZERO

        fun floorMarketQuantity(raw: BigDecimal): BigDecimal {
            val step = effectiveMarketStepSize
            if (step <= BigDecimal.ZERO) return raw.max(BigDecimal.ZERO).stripTrailingZeros()
            return raw.max(BigDecimal.ZERO)
                .divideToIntegralValue(step)
                .multiply(step)
                .setScale(step.scale().coerceAtLeast(0), RoundingMode.DOWN)
                .stripTrailingZeros()
        }
    }

    data class OrderResult(
        val symbol: String,
        val orderId: Long,
        val clientOrderId: String,
        val transactTime: Long,
        val status: String,
        val side: String,
        val type: String,
        val executedQty: String,
        val cummulativeQuoteQty: String,
        val raw: String,
        /** Exact fees Binance charged on this order, summed per asset from the
         *  FULL response's fills (e.g. {"ETH": 0.00016} on a buy, {"USDC": 0.29}
         *  on a sell, {"BNB": ...} with fee discount). Empty when the order was
         *  recovered via fetchOrderStatus, which doesn't return fills. */
        val commissions: Map<String, BigDecimal> = emptyMap()
    ) {
        val executedQtyDecimal: BigDecimal get() = executedQty.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val cummulativeQuoteDecimal: BigDecimal get() = cummulativeQuoteQty.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val averagePrice: Double
            get() = if (executedQtyDecimal > BigDecimal.ZERO)
                cummulativeQuoteDecimal.divide(executedQtyDecimal, 12, java.math.RoundingMode.HALF_UP).toDouble()
            else 0.0
        fun commissionIn(asset: String?): BigDecimal =
            asset?.let { commissions[it.uppercase()] } ?: BigDecimal.ZERO
    }

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0"
    // Engine feed budget: how many klines to page per symbol. The INTERVAL is no
    // longer fixed at 15m — FeedPlan derives it from each algo's own config, so
    // this same budget buys 83 days at 15m, 166 at 30m, 333 at 1h, 666 at 2h.
    // Request cost is identical in every case; only the calendar window changes.
    //
    // 8000 is sized for SLOW configs: a 240-min resample with warmup 240 needs
    // 240 × (240 / barMinutes) bars just to warm up — 3840 of them at 15m, which
    // at the old 5000 target left only ~12 days of decision window.
    const val TARGET_BARS = 8000

    /** Fetch [targetBars] klines at [interval] (paged) for the strategy engine.
     *  Take the interval from `FeedPlan.plan(cfg, ns).interval` — do NOT hardcode
     *  it; that is the bug this parameter exists to prevent. */
    fun fetchKlines(
        symbol: String,
        interval: String = "15m",
        targetBars: Int = TARGET_BARS
    ): YahooFinanceClient.CandleResult =
        fetchKlinesPaged(symbol, interval, targetBars.coerceIn(500, 20_000))

    /** Fetch ~1y of DAILY candles for the indicator/chart view (single page). */
    fun fetchDailyCandles(symbol: String, limit: Int = 365): YahooFinanceClient.CandleResult {
        val sym = symbol.trim().uppercase()
        if (sym.isBlank()) return YahooFinanceClient.CandleResult.NoData
        val page = klinePage(sym, "1d", limit.coerceIn(50, 1000), null)
        if (page.size < 50) return YahooFinanceClient.CandleResult.NoData
        return YahooFinanceClient.CandleResult.Success(page.sortedBy { it.timestamp }, sym)
    }

    /** Page backwards via endTime until [targetBars] gathered or history runs out. */
    private fun fetchKlinesPaged(symbol: String, interval: String, targetBars: Int): YahooFinanceClient.CandleResult {
        val sym = symbol.trim().uppercase()
        if (sym.isBlank()) return YahooFinanceClient.CandleResult.NoData
        val byOpen = java.util.TreeMap<Long, CandleData>()   // sorted + dedup by open time
        // Budget pages against a pessimistic ~900/page (Binance often returns that
        // even when KLINE_LIMIT=1500 is asked for) plus headroom. At the default
        // 8000 target this evaluates to 10 — the value that used to be hardcoded,
        // so existing behaviour is unchanged; it now scales if the target moves.
        val maxPages = (targetBars / 900 + 2).coerceIn(2, 40)
        var endTime: Long? = null
        var pages = 0
        while (byOpen.size < targetBars && pages < maxPages) {
            val page = klinePage(sym, interval, KLINE_LIMIT, endTime)
            if (page.isEmpty()) break
            for (c in page) byOpen[c.timestamp] = c
            val earliestSec = page.minOf { it.timestamp }
            val newEnd = earliestSec * 1000L - 1L
            if (endTime != null && newEnd >= endTime) break  // no backward progress → history exhausted
            endTime = newEnd
            // NOTE: do NOT stop just because page.size < KLINE_LIMIT — Binance
            // frequently returns ~1000 bars even when 1500 are requested, which
            // would otherwise cap us at one page. We stop on an empty page or
            // when the window stops moving backward (checks above) instead.
            pages++
            try { Thread.sleep(120) } catch (_: InterruptedException) {}
        }
        val out = byOpen.values.toList()
        if (out.size < 50) {
            Log.w(TAG, "Too few klines for $sym: ${out.size}")
            return YahooFinanceClient.CandleResult.NoData
        }
        Log.d(TAG, "Binance: $sym — ${out.size} klines ($interval, ${pages + 1} page(s))")
        return YahooFinanceClient.CandleResult.Success(out, sym)
    }

    /** One klines page (most-recent if endTimeMs null). Tries each host; [] on failure. */
    private fun klinePage(symbol: String, interval: String, limit: Int, endTimeMs: Long?): List<CandleData> {
        for (host in MARKET_DATA_HOSTS) {
            try {
                val b = HttpUrl.Builder().scheme("https").host(host)
                    .addPathSegments("api/v3/klines")
                    .addQueryParameter("symbol", symbol)
                    .addQueryParameter("interval", interval)
                    .addQueryParameter("limit", limit.toString())
                if (endTimeMs != null) b.addQueryParameter("endTime", endTimeMs.toString())
                val req = Request.Builder().url(b.build())
                    .addHeader("Accept", "application/json")
                    .addHeader("User-Agent", UA).build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code != 200) { Log.w(TAG, "klines $symbol $host HTTP ${resp.code}"); return@use }
                    val body = resp.body?.string() ?: return@use
                    return parseKlineArray(body)
                }
            } catch (e: Exception) {
                Log.w(TAG, "klines $symbol failed on $host", e)
            }
        }
        return emptyList()
    }

    fun fetchAccount(config: ConfigManager): BinanceResult<AccountSummary> {
        val obj = when (val r = signedJson(config, "GET", "/api/v3/account",
            listOf("omitZeroBalances" to "true"))) {
            is BinanceResult.Success -> r.data
            is BinanceResult.Failure -> return r
            is BinanceResult.NotConfigured -> return r
        }
        val balances = LinkedHashMap<String, Balance>()
        val arr = obj.optJSONArray("balances") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: continue
            val asset = b.optString("asset", "").uppercase()
            if (asset.isBlank()) continue
            balances[asset] = Balance(
                asset = asset,
                free = b.optString("free", "0"),
                locked = b.optString("locked", "0")
            )
        }
        return BinanceResult.Success(
            AccountSummary(
                canTrade = obj.optBoolean("canTrade", false),
                accountType = obj.optString("accountType", ""),
                balances = balances
            )
        )
    }

    fun fetchSymbolRules(symbol: String, force: Boolean = false): BinanceResult<SymbolRules> {
        val sym = symbol.trim().uppercase()
        if (sym.isBlank()) return BinanceResult.Failure("Blank Binance symbol.")
        val now = System.currentTimeMillis()
        rulesCache[sym]?.let { cached ->
            if (!force && now - cached.fetchedAt < RULES_TTL_MS) return BinanceResult.Success(cached.rules)
        }

        var lastError = ""
        for (host in MARKET_DATA_HOSTS) {
            try {
                val url = HttpUrl.Builder()
                    .scheme("https").host(host)
                    .addPathSegments("api/v3/exchangeInfo")
                    .addQueryParameter("symbol", sym)
                    .build()
                val req = Request.Builder()
                    .url(url)
                    .addHeader("Accept", "application/json")
                    .addHeader(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0"
                    )
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        lastError = parseError(body).ifBlank { "$host HTTP ${resp.code}" }
                        if (resp.code == 400) return BinanceResult.Failure("Invalid Binance symbol $sym")
                        Log.w(TAG, "exchangeInfo $sym failed on $host: $lastError")
                    } else {
                        val rules = parseSymbolRules(JSONObject(body), sym)
                        rulesCache[sym] = CachedRules(rules, now)
                        return BinanceResult.Success(rules)
                    }
                }
            } catch (e: Exception) {
                lastError = "$host: ${e.message ?: e.javaClass.simpleName}"
                Log.w(TAG, "exchangeInfo $sym failed on $host", e)
            }
        }
        return BinanceResult.Failure(lastError.ifBlank { "Could not fetch Binance exchange rules for $sym" })
    }

    fun placeMarketBuy(
        config: ConfigManager,
        symbol: String,
        quoteOrderQty: Double,
        clientOrderId: String
    ): BinanceResult<OrderResult> {
        val sym = symbol.trim().uppercase()
        if (quoteAssetOf(sym) == null) {
            return BinanceResult.Failure("Live orders support ${SUPPORTED_QUOTES.joinToString("/")} spot pairs only.")
        }
        val rules = when (val r = fetchSymbolRules(sym)) {
            is BinanceResult.Success -> r.data
            is BinanceResult.Failure -> return r
            is BinanceResult.NotConfigured -> return r
        }
        if (!rules.status.equals("TRADING", true)) {
            return BinanceResult.Failure("$sym is not TRADING on Binance (status=${rules.status}).")
        }
        // Quantize to cents, rounding DOWN. Compounded sleeve sizes carry a full
        // floating-point tail (e.g. 297.4715081…) and Binance rejects them with
        // -1111 "Parameter 'quoteOrderQty' has too much precision". DOWN also
        // guarantees we never ask for more than the checked free balance.
        val quote = BigDecimal.valueOf(quoteOrderQty).setScale(2, RoundingMode.DOWN).stripTrailingZeros()
        val minNotional = rules.effectiveMarketMinNotional
        if (minNotional > BigDecimal.ZERO && quote < minNotional) {
            return BinanceResult.Failure(
                "$sym live buy size ${quote.toPlainString()} USDT is below Binance market minimum " +
                    "${minNotional.stripTrailingZeros().toPlainString()} USDT"
            )
        }
        val maxNotional = rules.effectiveMarketMaxNotional
        if (maxNotional > BigDecimal.ZERO && quote > maxNotional) {
            return BinanceResult.Failure(
                "$sym live buy size ${quote.toPlainString()} USDT is above Binance market maximum " +
                    "${maxNotional.stripTrailingZeros().toPlainString()} USDT"
            )
        }
        return placeMarketOrder(
            config = config,
            symbol = sym,
            side = "BUY",
            quantity = null,
            quoteOrderQty = quote.toPlainString(),
            clientOrderId = clientOrderId
        )
    }

    fun placeMarketSell(
        config: ConfigManager,
        symbol: String,
        quantity: String,
        clientOrderId: String,
        estimatedPrice: BigDecimal? = null
    ): BinanceResult<OrderResult> {
        val sym = symbol.trim().uppercase()
        if (quoteAssetOf(sym) == null) {
            return BinanceResult.Failure("Live orders support ${SUPPORTED_QUOTES.joinToString("/")} spot pairs only.")
        }
        val requested = quantity.toBigDecimalOrNull()
            ?.toPlainString()
            ?.toBigDecimalOrNull()
            ?: return BinanceResult.Failure("Invalid sell quantity.")
        if (requested <= BigDecimal.ZERO) {
            return BinanceResult.Failure("Sell quantity is zero.")
        }
        val rules = when (val r = fetchSymbolRules(sym)) {
            is BinanceResult.Success -> r.data
            is BinanceResult.Failure -> return r
            is BinanceResult.NotConfigured -> return r
        }
        if (!rules.status.equals("TRADING", true)) {
            return BinanceResult.Failure("$sym is not TRADING on Binance (status=${rules.status}).")
        }
        val floored = rules.floorMarketQuantity(requested)
        val minQty = rules.effectiveMarketMinQty
        if (floored <= BigDecimal.ZERO || (minQty > BigDecimal.ZERO && floored < minQty)) {
            return BinanceResult.Failure(
                "$sym sell quantity ${requested.stripTrailingZeros().toPlainString()} floors to " +
                    "${floored.stripTrailingZeros().toPlainString()} below Binance market minQty " +
                    "${minQty.stripTrailingZeros().toPlainString()} (dust/residual left unsold)"
            )
        }
        val maxQty = rules.effectiveMarketMaxQty
        if (maxQty > BigDecimal.ZERO && floored > maxQty) {
            return BinanceResult.Failure(
                "$sym sell quantity ${floored.stripTrailingZeros().toPlainString()} exceeds Binance market maxQty " +
                    maxQty.stripTrailingZeros().toPlainString()
            )
        }
        val est = estimatedPrice?.takeIf { it > BigDecimal.ZERO }
        if (est != null) {
            val notional = floored.multiply(est)
            val minNotional = rules.effectiveMarketMinNotional
            if (minNotional > BigDecimal.ZERO && notional < minNotional) {
                return BinanceResult.Failure(
                    "$sym sell notional ≈${notional.setScale(4, RoundingMode.DOWN).stripTrailingZeros().toPlainString()} USDT " +
                        "is below Binance market minimum ${minNotional.stripTrailingZeros().toPlainString()} USDT " +
                        "(dust/residual left unsold)"
                )
            }
            val maxNotional = rules.effectiveMarketMaxNotional
            if (maxNotional > BigDecimal.ZERO && notional > maxNotional) {
                return BinanceResult.Failure(
                    "$sym sell notional ≈${notional.setScale(4, RoundingMode.DOWN).stripTrailingZeros().toPlainString()} USDT " +
                        "is above Binance market maximum ${maxNotional.stripTrailingZeros().toPlainString()} USDT"
                )
            }
        }
        return placeMarketOrder(
            config = config,
            symbol = sym,
            side = "SELL",
            quantity = floored.toPlainString(),
            quoteOrderQty = null,
            clientOrderId = clientOrderId
        )
    }

    private fun placeMarketOrder(
        config: ConfigManager,
        symbol: String,
        side: String,
        quantity: String?,
        quoteOrderQty: String?,
        clientOrderId: String
    ): BinanceResult<OrderResult> {
        val params = ArrayList<Pair<String, String>>()
        params.add("symbol" to symbol)
        params.add("side" to side)
        params.add("type" to "MARKET")
        if (quantity != null) params.add("quantity" to quantity)
        if (quoteOrderQty != null) params.add("quoteOrderQty" to quoteOrderQty)
        params.add("newClientOrderId" to clientOrderId.take(36))
        // FULL (not RESULT) so the response carries per-fill commissions — the
        // exchange's own exact fee per order, no estimating and no wallet-diff
        // races. fetchOrderStatus (GET /order) never returns fills, so recovered
        // orders simply have an empty commissions map (gross fallback).
        params.add("newOrderRespType" to "FULL")
        val obj = when (val r = signedJson(config, "POST", "/api/v3/order", params)) {
            is BinanceResult.Success -> r.data
            is BinanceResult.Failure -> return r
            is BinanceResult.NotConfigured -> return r
        }
        return BinanceResult.Success(orderResultOf(obj))
    }

    /**
     * Query one order by its (deterministic) clientOrderId — used to reconcile a
     * live BUY whose HTTP response was lost after the request was sent. The order
     * may actually exist and be filled on Binance even though placeMarketBuy
     * returned Failure. A plain signed GET, safe to retry across hosts.
     *
     *   Success(OrderResult) — the order exists (inspect status / executedQty)
     *   Success(null)        — Binance reports it does not exist (-2013): it never
     *                          reached the matching engine → a genuine failure
     *   Failure              — could not determine (network/other): caller should
     *                          keep the record UNKNOWN and re-check later
     */
    fun fetchOrderStatus(
        config: ConfigManager,
        symbol: String,
        clientOrderId: String
    ): BinanceResult<OrderResult?> {
        val sym = symbol.trim().uppercase()
        if (clientOrderId.isBlank()) return BinanceResult.Failure("Blank clientOrderId")
        return when (val r = signedJson(config, "GET", "/api/v3/order",
            listOf("symbol" to sym, "origClientOrderId" to clientOrderId.take(36)))) {
            is BinanceResult.Success -> BinanceResult.Success<OrderResult?>(orderResultOf(r.data))
            is BinanceResult.Failure ->
                // -2013 "Order does not exist" is a definitive "never placed".
                if (r.message.contains("-2013") || r.message.contains("does not exist", true))
                    BinanceResult.Success<OrderResult?>(null)
                else r
            is BinanceResult.NotConfigured -> BinanceResult.NotConfigured
        }
    }

    private fun signedJson(
        config: ConfigManager,
        method: String,
        path: String,
        params: List<Pair<String, String>> = emptyList()
    ): BinanceResult<JSONObject> {
        if (!config.hasBinanceCredentials) return BinanceResult.NotConfigured
        val normalizedMethod = method.uppercase()
        val isLiveOrderPost = normalizedMethod == "POST" && path == "/api/v3/order"
        var lastError = ""

        for ((idx, host) in SIGNED_HOSTS.withIndex()) {
            try {
                val signedParams = params + listOf(
                    "recvWindow" to RECV_WINDOW_MS.toString(),
                    "timestamp" to System.currentTimeMillis().toString()
                )
                val query = signedParams.joinToString("&") { "${it.first}=${encodeQueryValue(it.second)}" }
                val sig = hmacSha256(query, config.binanceApiSecret)
                val url = "https://$host$path?$query&signature=$sig"
                val builder = Request.Builder()
                    .url(url)
                    .addHeader("X-MBX-APIKEY", config.binanceApiKey)
                    .addHeader("Accept", "application/json")
                val req = when (normalizedMethod) {
                    "POST" -> builder.post(ByteArray(0).toRequestBody(null)).build()
                    "DELETE" -> builder.delete().build()
                    else -> builder.get().build()
                }
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) return BinanceResult.Success(JSONObject(body.ifBlank { "{}" }))

                    lastError = parseError(body).ifBlank { "$host HTTP ${resp.code}" }
                    Log.w(TAG, "signed Binance request failed on $host: $normalizedMethod $path -> $lastError")

                    // Never retry a live MARKET order after Binance replied. The
                    // response itself is authoritative enough to surface to the
                    // ledger, and retrying could duplicate a fill if status is
                    // ambiguous at the matching engine.
                    if (isLiveOrderPost) return BinanceResult.Failure(lastError)
                }
            } catch (e: Exception) {
                lastError = "$host: ${e.message ?: e.javaClass.simpleName}"
                Log.w(TAG, "signed Binance request failed on $host: $normalizedMethod $path", e)

                if (isLiveOrderPost && !isClearlyPreSendFailure(e)) {
                    return BinanceResult.Failure(
                        "Binance live order status unknown after network error on $host: " +
                            "${e.message ?: e.javaClass.simpleName}. Do not retry blindly; check Binance order history/clientOrderId first."
                    )
                }
            }

            if (idx < SIGNED_HOSTS.lastIndex) {
                Log.i(TAG, "Trying next Binance signed host for $normalizedMethod $path")
            }
        }

        return BinanceResult.Failure(lastError.ifBlank { "No Binance signed host responded" })
    }

    private fun isClearlyPreSendFailure(e: Exception): Boolean =
        e is UnknownHostException || e is ConnectException || e is NoRouteToHostException

    private fun parseSymbolRules(obj: JSONObject, symbol: String): SymbolRules {
        val arr = obj.optJSONArray("symbols") ?: JSONArray()
        val symObj = (0 until arr.length())
            .asSequence()
            .mapNotNull { arr.optJSONObject(it) }
            .firstOrNull { it.optString("symbol", "").equals(symbol, true) }
            ?: throw IllegalArgumentException("exchangeInfo did not include $symbol")

        val filters = symObj.optJSONArray("filters") ?: JSONArray()
        var lotMinQty = BigDecimal.ZERO
        var lotMaxQty = BigDecimal.ZERO
        var lotStepSize = BigDecimal.ZERO
        var marketMinQty = BigDecimal.ZERO
        var marketMaxQty = BigDecimal.ZERO
        var marketStepSize = BigDecimal.ZERO
        var minNotional = BigDecimal.ZERO
        var minNotionalAppliesToMarket = false
        var notionalMin = BigDecimal.ZERO
        var notionalMax = BigDecimal.ZERO
        var notionalApplyMinToMarket = false
        var notionalApplyMaxToMarket = false

        for (i in 0 until filters.length()) {
            val f = filters.optJSONObject(i) ?: continue
            when (f.optString("filterType", "")) {
                "LOT_SIZE" -> {
                    lotMinQty = dec(f, "minQty")
                    lotMaxQty = dec(f, "maxQty")
                    lotStepSize = dec(f, "stepSize")
                }
                "MARKET_LOT_SIZE" -> {
                    marketMinQty = dec(f, "minQty")
                    marketMaxQty = dec(f, "maxQty")
                    marketStepSize = dec(f, "stepSize")
                }
                "MIN_NOTIONAL" -> {
                    minNotional = dec(f, "minNotional")
                    minNotionalAppliesToMarket = f.optBoolean("applyToMarket", false)
                }
                "NOTIONAL" -> {
                    notionalMin = dec(f, "minNotional")
                    notionalMax = dec(f, "maxNotional")
                    notionalApplyMinToMarket = f.optBoolean("applyMinToMarket", false)
                    notionalApplyMaxToMarket = f.optBoolean("applyMaxToMarket", false)
                }
            }
        }
        return SymbolRules(
            symbol = symObj.optString("symbol", symbol).uppercase(),
            status = symObj.optString("status", ""),
            lotMinQty = lotMinQty,
            lotMaxQty = lotMaxQty,
            lotStepSize = lotStepSize,
            marketMinQty = marketMinQty,
            marketMaxQty = marketMaxQty,
            marketStepSize = marketStepSize,
            minNotional = minNotional,
            minNotionalAppliesToMarket = minNotionalAppliesToMarket,
            notionalMin = notionalMin,
            notionalMax = notionalMax,
            notionalApplyMinToMarket = notionalApplyMinToMarket,
            notionalApplyMaxToMarket = notionalApplyMaxToMarket
        )
    }

    private fun dec(obj: JSONObject, key: String): BigDecimal =
        obj.optString(key, "0").toBigDecimalOrNull() ?: BigDecimal.ZERO

    private fun orderResultOf(obj: JSONObject): OrderResult {
        // Sum per-fill commissions by asset (FULL responses only; GET /order has no fills).
        val commissions = HashMap<String, BigDecimal>()
        val fills = obj.optJSONArray("fills")
        if (fills != null) for (i in 0 until fills.length()) {
            val fill = fills.optJSONObject(i) ?: continue
            val asset = fill.optString("commissionAsset", "").uppercase()
            val amt = fill.optString("commission", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            if (asset.isNotBlank() && amt > BigDecimal.ZERO)
                commissions[asset] = (commissions[asset] ?: BigDecimal.ZERO).add(amt)
        }
        return OrderResult(
            symbol = obj.optString("symbol", ""),
            orderId = obj.optLong("orderId", 0L),
            clientOrderId = obj.optString("clientOrderId", ""),
            transactTime = obj.optLong("transactTime", obj.optLong("time", 0L)),
            status = obj.optString("status", ""),
            side = obj.optString("side", ""),
            type = obj.optString("type", ""),
            executedQty = obj.optString("executedQty", "0"),
            cummulativeQuoteQty = obj.optString("cummulativeQuoteQty", "0"),
            raw = obj.toString(),
            commissions = commissions
        )
    }

    private fun parseError(body: String): String {
        return try {
            val obj = JSONObject(body)
            val code = obj.optInt("code", 0)
            val msg = obj.optString("msg", body)
            if (code != 0) "Binance $code: $msg" else msg
        } catch (_: Exception) {
            body.take(240)
        }
    }

    private fun hmacSha256(payload: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun encodeQueryValue(v: String): String =
        java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    private fun decimalString(v: Double): String =
        BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()

    /** Parse a raw klines JSON array into candles (epoch seconds). [] on error. */
    private fun parseKlineArray(body: String): List<CandleData> {
        return try {
            val arr = JSONArray(body)
            val out = ArrayList<CandleData>(arr.length())
            for (i in 0 until arr.length()) {
                val k = arr.optJSONArray(i) ?: continue
                // [ openTimeMs, open, high, low, close, volume, closeTimeMs, ... ]
                val openTimeMs = k.optLong(0, 0L)
                val close = k.optString(4, "").toDoubleOrNull() ?: continue
                out.add(
                    CandleData(
                        timestamp = openTimeMs / 1000L,   // engine works in epoch seconds
                        open = k.optString(1, "").toDoubleOrNull() ?: close,
                        high = k.optString(2, "").toDoubleOrNull() ?: close,
                        low = k.optString(3, "").toDoubleOrNull() ?: close,
                        close = close,
                        volume = (k.optString(5, "").toDoubleOrNull() ?: 0.0).toLong()
                    )
                )
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "kline parse error", e)
            emptyList()
        }
    }
}

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
                        cash = o.get("free")?.asDouble ?: 0.0,
                        freeFunds = o.get("freeForStocks")?.asDouble ?: 0.0,
                        totalValue = o.get("total")?.asDouble ?: 0.0,
                        currencyCode = "USD"   // T212 returns positions in account ccy; default USD for stub
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
        val isin: String?
    )

    @Volatile private var metaCache: Map<String, InstrumentMeta>? = null
    @Volatile private var metaFetchedAt: Long = 0L
    private val META_TTL_MS = 6 * 60 * 60 * 1000L  // 6 h — names rarely change

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
                if (!resp.isSuccessful) return T212Result.Failure("HTTP ${resp.code}", resp.code)
                val body = resp.body?.string() ?: return T212Result.Failure("empty response")
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
                            isin = o.get("isin")?.asString
                        )
                    } catch (e: Exception) { /* skip malformed */ }
                }
                metaCache = map
                metaFetchedAt = System.currentTimeMillis()
                Log.i(TAG, "Fetched ${map.size} T212 instrument names")
                val filtered = if (tickers == null) map else map.filterKeys { k -> k in tickers }
                T212Result.Success(filtered)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchInstrumentMetadata failed", e)
            T212Result.Failure(e.message ?: "unknown")
        }
    }

    /** Convert a T212 ticker (e.g. "KO_US_EQ", "SAP_DE_EQ") to its Yahoo
     *  Finance equivalent ("KO", "SAP.DE") so we can pull candles + run
     *  StockAnalyzer on the position. Returns the bare ticker as fallback
     *  for unknown markets. */
    fun t212ToYahooSymbol(t212Ticker: String): String {
        val parts = t212Ticker.split("_")
        if (parts.size < 2) return t212Ticker
        val base = parts[0]
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

        return try {
            val url = "${config.t212BaseUrl}$endpoint".toHttpUrlOrNull()
                ?: return T212Result.Failure("invalid base URL")
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", authHeader())
                .addHeader("Accept", "application/json")
                .post(gson.toJson(body).toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string()?.take(200)?.replace("\"","'") ?: ""
                    return T212Result.Failure("HTTP ${resp.code} $errBody", resp.code)
                }
                val respBody = resp.body?.string() ?: return T212Result.Failure("empty response")
                val o = JsonParser.parseString(respBody).asJsonObject
                val id = o.get("id")?.asLong ?: 0L
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
    fun fetchOpenOrders(): T212Result<String> {
        if (!config.hasT212Credentials) return T212Result.NotConfigured
        return try {
            val url = "${config.t212BaseUrl}/equity/orders".toHttpUrlOrNull()
                ?: return T212Result.Failure("invalid base URL")
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", authHeader())
                .addHeader("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return T212Result.Failure("HTTP ${resp.code}", resp.code)
                T212Result.Success(resp.body?.string() ?: "[]")
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchOpenOrders failed", e)
            T212Result.Failure(e.message ?: "unknown")
        }
    }
}

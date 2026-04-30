package com.signalscope.app.network


import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.PortfolioHolding
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Zerodha Kite API client.
 * Matches the Python app's zerodha integration exactly:
 *   - ZERODHA_API_KEY + ZERODHA_API_SECRET from credentials
 *   - Access token exchanged from request_token after login
 *   - Fetches holdings from /portfolio/holdings
 */
class ZerodhaClient(private val config: ConfigManager) {

    companion object {
        private const val TAG = "ZerodhaClient"
        private const val BASE_URL = "https://api.kite.trade"
        private const val JSON_TYPE = "application/json"
    }

    private val gson = Gson()

    // Custom DNS — prefer IPv4 (MIUI has flaky IPv6)
    private val ipv4PreferredDns = object : Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            val v4 = all.filter { it is java.net.Inet4Address }
            val v6 = all.filter { it !is java.net.Inet4Address }
            return v4 + v6
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .dns(ipv4PreferredDns)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    // ═══════════════════════════════════════════════════════
    // AUTH
    // ═══════════════════════════════════════════════════════

    sealed class ZerodhaAuthResult {
        data class Success(val accessToken: String, val userName: String) : ZerodhaAuthResult()
        data class Failure(val message: String) : ZerodhaAuthResult()
    }

    /**
     * Exchange a request_token (from Zerodha login redirect) for an access_token.
     * Mirrors Python: hashlib.sha256(api_key + request_token + api_secret).hexdigest()
     */
    fun exchangeRequestToken(requestToken: String): ZerodhaAuthResult {
        if (config.zerodhaApiKey.isBlank() || config.zerodhaApiSecret.isBlank()) {
            return ZerodhaAuthResult.Failure("Zerodha API key/secret not configured")
        }

        return try {
            // Compute SHA-256 checksum exactly as Python does
            val raw = config.zerodhaApiKey + requestToken + config.zerodhaApiSecret
            val checksum = sha256(raw)

            val formBody = FormBody.Builder()
                .add("api_key", config.zerodhaApiKey)
                .add("request_token", requestToken)
                .add("checksum", checksum)
                .build()

            val request = Request.Builder()
                .url("$BASE_URL/session/token")
                .post(formBody)
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return ZerodhaAuthResult.Failure("Empty response")

                val json = JsonParser.parseString(body).asJsonObject
                if (json.get("status")?.asString != "success") {
                    val msg = json.get("message")?.asString ?: "Unknown error"
                    return ZerodhaAuthResult.Failure(msg)
                }

                val data = json.getAsJsonObject("data")
                val accessToken = data.get("access_token")?.asString ?: ""
                val userName = data.get("user_name")?.asString ?: ""

                config.zerodhaAccessToken = accessToken
                config.zerodhaUserName = userName

                Log.i(TAG, "Zerodha login successful: $userName")
                ZerodhaAuthResult.Success(accessToken, userName)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Zerodha token exchange error", e)
            ZerodhaAuthResult.Failure(e.message ?: "Unknown error")
        }
    }

    /**
     * Set a pre-existing access token (e.g. pasted by user) and verify it.
     */
    fun setAndVerifyAccessToken(accessToken: String): ZerodhaAuthResult {
        config.zerodhaAccessToken = accessToken
        return verifyToken()
    }

    fun verifyToken(): ZerodhaAuthResult {
        if (config.zerodhaAccessToken.isBlank() || config.zerodhaApiKey.isBlank()) {
            return ZerodhaAuthResult.Failure("Not configured")
        }
        return try {
            val request = Request.Builder()
                .url("$BASE_URL/user/profile")
                .addHeader("Authorization", "token ${config.zerodhaApiKey}:${config.zerodhaAccessToken}")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return ZerodhaAuthResult.Failure("Empty response")

                val json = JsonParser.parseString(body).asJsonObject
                if (json.get("status")?.asString != "success") {
                    config.zerodhaAccessToken = "" // clear invalid token
                    return ZerodhaAuthResult.Failure("Token invalid or expired")
                }

                val userName = json.getAsJsonObject("data")?.get("user_name")?.asString ?: ""
                config.zerodhaUserName = userName
                ZerodhaAuthResult.Success(config.zerodhaAccessToken, userName)
            }

        } catch (e: Exception) {
            ZerodhaAuthResult.Failure(e.message ?: "Unknown error")
        }
    }

    fun logout() {
        config.zerodhaAccessToken = ""
        config.zerodhaUserName = ""
    }

    val isConnected: Boolean
        get() = config.zerodhaAccessToken.isNotBlank() && config.zerodhaApiKey.isNotBlank()

    val loginUrl: String
        get() = if (config.zerodhaApiKey.isNotBlank())
            "https://kite.zerodha.com/connect/login?v=3&api_key=${config.zerodhaApiKey}"
        else ""

    // ═══════════════════════════════════════════════════════
    // HOLDINGS
    // ═══════════════════════════════════════════════════════

    sealed class HoldingsResult {
        data class Success(val holdings: List<PortfolioHolding>) : HoldingsResult()
        data class Expired(val message: String) : HoldingsResult()
        data class Failure(val message: String) : HoldingsResult()
    }

    /**
     * Fetch Zerodha portfolio holdings.
     * Returns holdings with quantity > 0, mapped to PortfolioHolding.
     * Mirrors Python's /api/zerodha/holdings endpoint logic.
     */
    fun fetchHoldings(): HoldingsResult {
        if (!isConnected) return HoldingsResult.Failure("Not connected to Zerodha")

        return try {
            val request = Request.Builder()
                .url("$BASE_URL/portfolio/holdings")
                .addHeader("Authorization", "token ${config.zerodhaApiKey}:${config.zerodhaAccessToken}")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return HoldingsResult.Failure("Empty response")

                val json = JsonParser.parseString(body).asJsonObject
                if (json.get("status")?.asString != "success") {
                    val msg = json.get("message")?.asString ?: "Unknown error"
                    if (msg.lowercase().contains("token") || response.code == 403) {
                        config.zerodhaAccessToken = "" // clear expired token
                        return HoldingsResult.Expired("Zerodha session expired. Please reconnect.")
                    }
                    return HoldingsResult.Failure(msg)
                }

                val rawData = json.getAsJsonArray("data") ?: return HoldingsResult.Success(emptyList())

                val holdings = rawData.mapNotNull { item ->
                    val obj = item.asJsonObject
                    val qty = obj.get("quantity")?.asInt ?: 0
                    if (qty <= 0) return@mapNotNull null

                    val tradingSymbol = obj.get("tradingsymbol")?.asString ?: return@mapNotNull null
                    val avgPrice = obj.get("average_price")?.asDouble ?: 0.0
                    val ltp = obj.get("last_price")?.asDouble ?: 0.0
                    val dayChange = obj.get("day_change")?.asDouble ?: 0.0
                    val dayChangePct = obj.get("day_change_percentage")?.asDouble ?: 0.0
                    val pnl = obj.get("pnl")?.asDouble ?: 0.0
                    val exchange = obj.get("exchange")?.asString ?: "NSE"

                    PortfolioHolding(
                        symbol = tradingSymbol,
                        token = "", // Zerodha doesn't use tokens the same way; Yahoo Finance uses symbol
                        quantity = qty,
                        avgPrice = avgPrice,
                        ltp = ltp,
                        pnl = pnl,
                        dayChange = dayChange,
                        dayChangePct = dayChangePct,
                        exchange = exchange,
                        source = "zerodha"
                    )
                }

                Log.i(TAG, "Zerodha holdings fetched: ${holdings.size} stocks")
                HoldingsResult.Success(holdings)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Zerodha holdings fetch error", e)
            HoldingsResult.Failure(e.message ?: "Unknown error")
        }
    }

    // ═══════════════════════════════════════════════════════
    // GTT TRIGGERS (Good-Till-Triggered orders)
    // ═══════════════════════════════════════════════════════
    // Kite GTT REST:  GET /gtt/triggers           → list all
    //                 PUT /gtt/triggers/{id}      → modify
    //                 DELETE /gtt/triggers/{id}   → cancel
    // Reference: https://kite.trade/docs/connect/v3/gtt/
    //
    // "Simple implementation" scope: list user's GTTs so the app can show them
    // inline on the portfolio tab, plus a one-shot "bump trigger up to next
    // round number" modify call for quickly raising a stop / target.

    data class GttTrigger(
        val id: Long,
        val tradingsymbol: String,
        val exchange: String,
        val triggerType: String,      // "single" or "two-leg"
        val status: String,           // "active" / "triggered" / "cancelled" / "disabled" / "expired"
        val triggerPrice: Double,     // first trigger value (SL leg for OCO by convention: lower value)
        val limitPrice: Double,       // first order limit price
        val quantity: Int,            // first order quantity
        val transactionType: String,  // "SELL" / "BUY"
        val lastPrice: Double,
        /** Second trigger value for two-leg OCO GTTs (the target leg); null for single-leg. */
        val triggerPriceAux: Double? = null
    )

    sealed class GttListResult {
        data class Success(val triggers: List<GttTrigger>) : GttListResult()
        data class Expired(val message: String) : GttListResult()
        data class Failure(val message: String) : GttListResult()
    }

    // GTT Execution Analysis (for calibration insights)
    data class GttExecutionStats(
        val symbol: String,
        val totalGtts: Int,
        val executedCount: Int,
        val activeCount: Int,
        val cancelledCount: Int,
        val executionRate: Double,  // executed / total
        val avgTriggerDeviation: Double?  // avg difference between trigger and last price
    )

    data class GttExecutionSummary(
        val bySymbol: List<GttExecutionStats>,  // per-stock breakdown
        val totalGtts: Int,
        val totalExecuted: Int,
        val overallExecutionRate: Double
    )

    sealed class GttModifyResult {
        data class Success(val id: Long, val newTriggerPrice: Double) : GttModifyResult()
        data class Failure(val message: String) : GttModifyResult()
    }

    sealed class GttDeleteResult {
        data class Success(val id: Long) : GttDeleteResult()
        data class Failure(val message: String) : GttDeleteResult()
    }

    /** Fetch all active GTT triggers for the authenticated user. */
    fun fetchGttTriggers(): GttListResult {
        if (!isConnected) return GttListResult.Failure("Not connected to Zerodha")
        return try {
            val request = Request.Builder()
                .url("$BASE_URL/gtt/triggers")
                .addHeader("Authorization", "token ${config.zerodhaApiKey}:${config.zerodhaAccessToken}")
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return GttListResult.Failure("Empty response")
                val json = JsonParser.parseString(body).asJsonObject
                if (json.get("status")?.asString != "success") {
                    val msg = json.get("message")?.asString ?: "Unknown error"
                    if (msg.lowercase().contains("token") || response.code == 403) {
                        config.zerodhaAccessToken = ""
                        return GttListResult.Expired("Zerodha session expired. Please reconnect.")
                    }
                    return GttListResult.Failure(msg)
                }
                val arr = json.getAsJsonArray("data") ?: return GttListResult.Success(emptyList())
                val list = arr.mapNotNull { el ->
                    try {
                        val o = el.asJsonObject
                        val cond = o.getAsJsonObject("condition") ?: return@mapNotNull null
                        val orders = o.getAsJsonArray("orders") ?: return@mapNotNull null
                        if (orders.size() == 0) return@mapNotNull null
                        val firstOrder = orders[0].asJsonObject
                        val triggerValues = cond.getAsJsonArray("trigger_values")
                        // For OCO the API order is [SL, target] = [lower, higher].
                        // Sort low→high so triggerPrice always = SL, triggerPriceAux = target,
                        // regardless of how the broker stored them.
                        val vals = mutableListOf<Double>()
                        if (triggerValues != null) for (k in 0 until triggerValues.size()) vals.add(triggerValues[k].asDouble)
                        vals.sort()
                        val triggerPrice = if (vals.isNotEmpty()) vals[0] else 0.0
                        val triggerPriceAux = if (vals.size > 1) vals[1] else null
                        GttTrigger(
                            id = o.get("id").asLong,
                            tradingsymbol = cond.get("tradingsymbol")?.asString ?: "",
                            exchange = cond.get("exchange")?.asString ?: "NSE",
                            triggerType = o.get("type")?.asString ?: "single",
                            status = o.get("status")?.asString ?: "",
                            triggerPrice = triggerPrice,
                            limitPrice = firstOrder.get("price")?.asDouble ?: triggerPrice,
                            quantity = firstOrder.get("quantity")?.asInt ?: 0,
                            transactionType = firstOrder.get("transaction_type")?.asString ?: "",
                            lastPrice = cond.get("last_price")?.asDouble ?: 0.0,
                            triggerPriceAux = triggerPriceAux
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "GTT parse skip: ${e.message}")
                        null
                    }
                }
                Log.i(TAG, "GTTs fetched: ${list.size}")
                GttListResult.Success(list)
            }
        } catch (e: Exception) {
            Log.e(TAG, "GTT fetch error", e)
            GttListResult.Failure(e.message ?: "Unknown error")
        }
    }

    /**
     * Analyze GTT execution history for trigger weight calibration.
     * Returns per-symbol stats: execution rate, trigger accuracy, etc.
     * Called from MainActivity to generate calibration insights.
     */
    fun analyzeGttExecutionHistory(): GttExecutionSummary? {
        val result = fetchGttTriggers()
        return when (result) {
            is GttListResult.Success -> {
                val gtts = result.triggers
                if (gtts.isEmpty()) {
                    return GttExecutionSummary(emptyList(), 0, 0, 0.0)
                }

                // Group by symbol and calculate stats
                val bySymbol = gtts.groupBy { it.tradingsymbol }.map { (symbol, gttList) ->
                    val executed = gttList.count { it.status == "triggered" }
                    val active = gttList.count { it.status == "active" }
                    val cancelled = gttList.count { it.status == "cancelled" || it.status == "expired" }

                    // Calculate avg deviation between trigger price and last price
                    // (indicates if trigger weights are hitting at expected levels)
                    val deviations = gttList.mapNotNull { gtt ->
                        if (gtt.status == "triggered") {
                            val pctDev = ((gtt.lastPrice - gtt.triggerPrice) / gtt.triggerPrice * 100)
                            pctDev
                        } else null
                    }
                    val avgDeviation = if (deviations.isNotEmpty()) deviations.average() else null

                    GttExecutionStats(
                        symbol = symbol,
                        totalGtts = gttList.size,
                        executedCount = executed,
                        activeCount = active,
                        cancelledCount = cancelled,
                        executionRate = if (gttList.isNotEmpty()) executed.toDouble() / gttList.size else 0.0,
                        avgTriggerDeviation = avgDeviation
                    )
                }.sortedByDescending { it.executionRate }

                val totalGtts = gtts.size
                val totalExecuted = gtts.count { it.status == "triggered" }

                GttExecutionSummary(
                    bySymbol = bySymbol,
                    totalGtts = totalGtts,
                    totalExecuted = totalExecuted,
                    overallExecutionRate = if (totalGtts > 0) totalExecuted.toDouble() / totalGtts else 0.0
                )
            }
            is GttListResult.Expired,
            is GttListResult.Failure -> null
        }
    }

    /**
     * Modify a GTT trigger (single-leg OR two-leg/OCO): bumps exactly ONE leg to
     * `newTriggerPrice` while preserving every other field of every other leg.
     *
     * For two-leg OCO, Kite REJECTS a PUT that sends only one trigger value with
     *   "Two leg condition triggers expect two trigger values."
     * So we must always echo back both `trigger_values` (and both `orders`),
     * touching only the leg being bumped.
     *
     * @param oldTriggerHint  the trigger value the caller saw on screen before
     *                        bumping. Used to identify WHICH leg to update on
     *                        two-leg GTTs (we pick the leg whose current value
     *                        is closest to this hint). Pass null for single-leg
     *                        GTTs and we'll just update leg 0.
     */
    fun modifyGttTriggerPrice(id: Long, newTriggerPrice: Double, oldTriggerHint: Double? = null): GttModifyResult {
        if (!isConnected) return GttModifyResult.Failure("Not connected to Zerodha")
        return try {
            // Fetch existing trigger first so we can PUT it back with a new price
            val getReq = Request.Builder()
                .url("$BASE_URL/gtt/triggers/$id")
                .addHeader("Authorization", "token ${config.zerodhaApiKey}:${config.zerodhaAccessToken}")
                .build()
            val existing = client.newCall(getReq).execute().use { resp ->
                val body = resp.body?.string() ?: return GttModifyResult.Failure("Empty response")
                val j = JsonParser.parseString(body).asJsonObject
                if (j.get("status")?.asString != "success") {
                    return GttModifyResult.Failure(j.get("message")?.asString ?: "Fetch failed")
                }
                j.getAsJsonObject("data")
            }
            val cond = existing.getAsJsonObject("condition")
            val orders = existing.getAsJsonArray("orders")
            val tradingsymbol = cond.get("tradingsymbol").asString
            val exchange = cond.get("exchange").asString
            val lastPrice = cond.get("last_price")?.asDouble ?: newTriggerPrice
            val triggerType = existing.get("type")?.asString ?: "single"

            // Existing trigger values — MUST be preserved (Kite rejects two-leg PUTs that drop one).
            val oldTriggerValues = cond.getAsJsonArray("trigger_values")
                .map { it.asDouble }
                .toMutableList()
            if (oldTriggerValues.isEmpty()) {
                return GttModifyResult.Failure("GTT has no trigger values — cannot modify")
            }
            // Identify which leg to bump. For single-leg it's always 0.
            // For two-leg OCO, pick the leg whose current value is closest to the
            // hint (the user tapped ↑ on the chip they could see, which matches
            // the value fetchGttTriggers surfaced — always trigger_values[0] today,
            // but using closest-match is future-proof if we ever show both legs).
            val bumpIdx = if (oldTriggerHint != null && oldTriggerValues.size > 1) {
                oldTriggerValues.indices.minByOrNull { kotlin.math.abs(oldTriggerValues[it] - oldTriggerHint) } ?: 0
            } else 0
            oldTriggerValues[bumpIdx] = newTriggerPrice

            // Build orders JSON — only orders[bumpIdx] gets the new limit price;
            // the other leg's price stays exactly as it was on the broker side.
            val ordersJson = StringBuilder("[")
            orders.forEachIndexed { idx, el ->
                if (idx > 0) ordersJson.append(",")
                val o = el.asJsonObject
                val existingPrice = o.get("price")?.asDouble ?: 0.0
                val priceForThisOrder = if (idx == bumpIdx) newTriggerPrice else existingPrice
                ordersJson.append("{")
                ordersJson.append("\"exchange\":\"").append(o.get("exchange").asString).append("\",")
                ordersJson.append("\"tradingsymbol\":\"").append(o.get("tradingsymbol").asString).append("\",")
                ordersJson.append("\"transaction_type\":\"").append(o.get("transaction_type").asString).append("\",")
                ordersJson.append("\"quantity\":").append(o.get("quantity").asInt).append(",")
                ordersJson.append("\"order_type\":\"").append(o.get("order_type")?.asString ?: "LIMIT").append("\",")
                ordersJson.append("\"product\":\"").append(o.get("product")?.asString ?: "CNC").append("\",")
                ordersJson.append("\"price\":").append(priceForThisOrder)
                ordersJson.append("}")
            }
            ordersJson.append("]")

            val triggerValuesJson = oldTriggerValues.joinToString(",")
            val formBody = FormBody.Builder()
                .add("type", triggerType)
                .add("condition", "{\"exchange\":\"$exchange\",\"tradingsymbol\":\"$tradingsymbol\",\"trigger_values\":[$triggerValuesJson],\"last_price\":$lastPrice}")
                .add("orders", ordersJson.toString())
                .build()

            val putReq = Request.Builder()
                .url("$BASE_URL/gtt/triggers/$id")
                .put(formBody)
                .addHeader("Authorization", "token ${config.zerodhaApiKey}:${config.zerodhaAccessToken}")
                .build()

            client.newCall(putReq).execute().use { resp ->
                val body = resp.body?.string() ?: return GttModifyResult.Failure("Empty response")
                val j = JsonParser.parseString(body).asJsonObject
                if (j.get("status")?.asString != "success") {
                    return GttModifyResult.Failure(j.get("message")?.asString ?: "Modify failed")
                }
                val outId = j.getAsJsonObject("data")?.get("trigger_id")?.asLong ?: id
                Log.i(TAG, "GTT $outId bumped to $newTriggerPrice")
                com.signalscope.app.data.GttActivityLog.append(
                    config.appContext, "bump",
                    fields = mapOf("id" to outId, "new" to newTriggerPrice, "oldHint" to oldTriggerHint)
                )
                GttModifyResult.Success(outId, newTriggerPrice)
            }
        } catch (e: Exception) {
            Log.e(TAG, "GTT modify error", e)
            GttModifyResult.Failure(e.message ?: "Unknown error")
        }
    }

    /**
     * Arbitrary-price GTT modify — lets the user edit either or both legs of an
     * OCO (or the single leg of a single-leg GTT) to any price, not just the
     * next round number. `newTriggerValues` must match the existing leg count
     * (1 for single, 2 for two-leg OCO) and for two-leg must be [SL, target]
     * with SL < last_price < target. Limit prices follow the same convention
     * as create: ~2% below SL trigger, ~1% below target trigger is what the
     * JS caller supplies.
     */
    fun setGttTriggerPrices(
        id: Long,
        newTriggerValues: List<Double>,
        newLimitPrices: List<Double>
    ): GttModifyResult {
        if (!isConnected) return GttModifyResult.Failure("Not connected to Zerodha")
        if (newTriggerValues.isEmpty() || newLimitPrices.size != newTriggerValues.size) {
            return GttModifyResult.Failure("Trigger/limit count mismatch")
        }
        return try {
            val getReq = Request.Builder()
                .url("$BASE_URL/gtt/triggers/$id")
                .addHeader("Authorization", "token ${config.zerodhaApiKey}:${config.zerodhaAccessToken}")
                .build()
            val existing = client.newCall(getReq).execute().use { resp ->
                val body = resp.body?.string() ?: return GttModifyResult.Failure("Empty response")
                val j = JsonParser.parseString(body).asJsonObject
                if (j.get("status")?.asString != "success") {
                    return GttModifyResult.Failure(j.get("message")?.asString ?: "Fetch failed")
                }
                j.getAsJsonObject("data")
            }
            val cond = existing.getAsJsonObject("condition")
            val orders = existing.getAsJsonArray("orders")
            val tradingsymbol = cond.get("tradingsymbol").asString
            val exchange = cond.get("exchange").asString
            val lastPrice = cond.get("last_price")?.asDouble ?: newTriggerValues.first()
            val triggerType = existing.get("type")?.asString ?: "single"

            if (orders.size() != newTriggerValues.size) {
                return GttModifyResult.Failure("GTT has ${orders.size()} legs, got ${newTriggerValues.size} new values")
            }

            val ordersJson = StringBuilder("[")
            orders.forEachIndexed { idx, el ->
                if (idx > 0) ordersJson.append(",")
                val o = el.asJsonObject
                ordersJson.append("{")
                ordersJson.append("\"exchange\":\"").append(o.get("exchange").asString).append("\",")
                ordersJson.append("\"tradingsymbol\":\"").append(o.get("tradingsymbol").asString).append("\",")
                ordersJson.append("\"transaction_type\":\"").append(o.get("transaction_type").asString).append("\",")
                ordersJson.append("\"quantity\":").append(o.get("quantity").asInt).append(",")
                ordersJson.append("\"order_type\":\"").append(o.get("order_type")?.asString ?: "LIMIT").append("\",")
                ordersJson.append("\"product\":\"").append(o.get("product")?.asString ?: "CNC").append("\",")
                ordersJson.append("\"price\":").append(newLimitPrices[idx])
                ordersJson.append("}")
            }
            ordersJson.append("]")

            val triggerValuesJson = newTriggerValues.joinToString(",")
            val formBody = FormBody.Builder()
                .add("type", triggerType)
                .add("condition", "{\"exchange\":\"$exchange\",\"tradingsymbol\":\"$tradingsymbol\",\"trigger_values\":[$triggerValuesJson],\"last_price\":$lastPrice}")
                .add("orders", ordersJson.toString())
                .build()

            val putReq = Request.Builder()
                .url("$BASE_URL/gtt/triggers/$id")
                .put(formBody)
                .addHeader("Authorization", "token ${config.zerodhaApiKey}:${config.zerodhaAccessToken}")
                .build()

            client.newCall(putReq).execute().use { resp ->
                val body = resp.body?.string() ?: return GttModifyResult.Failure("Empty response")
                val j = JsonParser.parseString(body).asJsonObject
                if (j.get("status")?.asString != "success") {
                    return GttModifyResult.Failure(j.get("message")?.asString ?: "Modify failed")
                }
                val outId = j.getAsJsonObject("data")?.get("trigger_id")?.asLong ?: id
                Log.i(TAG, "GTT $outId set to triggers=$newTriggerValues limits=$newLimitPrices")
                com.signalscope.app.data.GttActivityLog.append(
                    config.appContext, "modify",
                    fields = mapOf(
                        "id" to outId,
                        "trigs" to newTriggerValues.joinToString(","),
                        "limits" to newLimitPrices.joinToString(",")
                    )
                )
                GttModifyResult.Success(outId, newTriggerValues.first())
            }
        } catch (e: Exception) {
            Log.e(TAG, "GTT setTriggerPrices error", e)
            GttModifyResult.Failure(e.message ?: "Unknown error")
        }
    }

    /** Cancel/delete a GTT trigger by id. */
    fun deleteGttTrigger(id: Long): GttDeleteResult {
        if (!isConnected) return GttDeleteResult.Failure("Not connected to Zerodha")
        return try {
            val req = Request.Builder()
                .url("$BASE_URL/gtt/triggers/$id")
                .delete()
                .addHeader("Authorization", "token ${config.zerodhaApiKey}:${config.zerodhaAccessToken}")
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return GttDeleteResult.Failure("Empty response")
                val j = JsonParser.parseString(body).asJsonObject
                if (j.get("status")?.asString != "success") {
                    return GttDeleteResult.Failure(j.get("message")?.asString ?: "Delete failed")
                }
                Log.i(TAG, "GTT $id deleted")
                com.signalscope.app.data.GttActivityLog.append(
                    config.appContext, "delete",
                    fields = mapOf("id" to id)
                )
                GttDeleteResult.Success(id)
            }
        } catch (e: Exception) {
            Log.e(TAG, "GTT delete error", e)
            GttDeleteResult.Failure(e.message ?: "Unknown error")
        }
    }

    /**
     * Create a new GTT trigger. Supports both single-leg and two-leg (OCO) triggers.
     *
     * For a two-leg OCO SELL, pass:
     *   type = "two-leg"
     *   triggerValues = listOf(stopLossTrigger, targetTrigger)   // below & above LTP
     *   orders = listOf(
     *     GttOrderSpec(SELL, qty, stopLossLimitPrice),
     *     GttOrderSpec(SELL, qty, targetLimitPrice)
     *   )
     *
     * Kite rules enforced server-side:
     *   - trigger values must be within ±25% of last_price
     *   - for two-leg: first trigger < last_price < second trigger
     *   - app layer only enforces the Kite ±25% limit and strict-side-of-LTP,
     *     no arbitrary minimum-distance floor (user wants tight SLs).
     */
    data class GttOrderSpec(
        val transactionType: String, // "SELL" / "BUY"
        val quantity: Int,
        val price: Double,
        val product: String = "CNC",
        val orderType: String = "LIMIT"
    )

    sealed class GttCreateResult {
        data class Success(val id: Long) : GttCreateResult()
        data class Failure(val message: String) : GttCreateResult()
    }

    fun createGttTrigger(
        type: String,
        exchange: String,
        tradingsymbol: String,
        triggerValues: List<Double>,
        lastPrice: Double,
        orders: List<GttOrderSpec>
    ): GttCreateResult {
        if (!isConnected) return GttCreateResult.Failure("Not connected to Zerodha")
        if (triggerValues.isEmpty() || orders.isEmpty()) return GttCreateResult.Failure("Empty triggers/orders")
        return try {
            // Build `condition` JSON
            val trigValsStr = triggerValues.joinToString(",") { it.toString() }
            val condition = "{\"exchange\":\"$exchange\",\"tradingsymbol\":\"$tradingsymbol\",\"trigger_values\":[$trigValsStr],\"last_price\":$lastPrice}"

            // Build `orders` JSON array
            val ordersSb = StringBuilder("[")
            orders.forEachIndexed { i, o ->
                if (i > 0) ordersSb.append(",")
                ordersSb.append("{")
                ordersSb.append("\"exchange\":\"").append(exchange).append("\",")
                ordersSb.append("\"tradingsymbol\":\"").append(tradingsymbol).append("\",")
                ordersSb.append("\"transaction_type\":\"").append(o.transactionType).append("\",")
                ordersSb.append("\"quantity\":").append(o.quantity).append(",")
                ordersSb.append("\"order_type\":\"").append(o.orderType).append("\",")
                ordersSb.append("\"product\":\"").append(o.product).append("\",")
                ordersSb.append("\"price\":").append(o.price)
                ordersSb.append("}")
            }
            ordersSb.append("]")

            val formBody = FormBody.Builder()
                .add("type", type)
                .add("condition", condition)
                .add("orders", ordersSb.toString())
                .build()

            val req = Request.Builder()
                .url("$BASE_URL/gtt/triggers")
                .post(formBody)
                .addHeader("Authorization", "token ${config.zerodhaApiKey}:${config.zerodhaAccessToken}")
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return GttCreateResult.Failure("Empty response")
                val j = JsonParser.parseString(body).asJsonObject
                if (j.get("status")?.asString != "success") {
                    return GttCreateResult.Failure(j.get("message")?.asString ?: "Create failed")
                }
                val id = j.getAsJsonObject("data")?.get("trigger_id")?.asLong ?: 0L
                Log.i(TAG, "GTT created id=$id type=$type $tradingsymbol trigs=$triggerValues")
                com.signalscope.app.data.GttActivityLog.append(
                    config.appContext, "create", symbol = tradingsymbol,
                    fields = mapOf(
                        "id" to id,
                        "type" to type,
                        "trigs" to triggerValues.joinToString(","),
                        "lastPrice" to lastPrice
                    )
                )
                GttCreateResult.Success(id)
            }
        } catch (e: Exception) {
            Log.e(TAG, "GTT create error", e)
            GttCreateResult.Failure(e.message ?: "Unknown error")
        }
    }

    // ═══════════════════════════════════════════════════════
    // REGULAR ORDERS (for MACD watchdog exit ladder)
    // ═══════════════════════════════════════════════════════
    // POST /orders/regular  → place (variety=regular, product=CNC for delivery)
    // The watchdog's exit ladder:
    //   1. Limit sell at LTP × 0.998 (tight — catches normal fills)
    //   2. If not filled in 60s → cancel + re-place at LTP × 0.995
    //   3. If still unfilled in 5 min → market order fallback
    // We expose placeLimitSell + placeMarketSell here; the orchestrator
    // (DailyPortfolioManager) implements the ladder sleeps + cancel logic.

    sealed class OrderResult {
        data class Success(val orderId: String) : OrderResult()
        data class Failure(val message: String) : OrderResult()
    }

    /**
     * Place a regular CNC delivery order. Kite rejects MIS product for T+1
     * delivery stocks, and swing positions are CNC-held, so CNC is correct here.
     * For SELL of existing holdings the broker doesn't require margin checks.
     */
    fun placeOrder(
        exchange: String,
        tradingsymbol: String,
        transactionType: String,   // "BUY" / "SELL"
        quantity: Int,
        orderType: String,         // "LIMIT" / "MARKET"
        price: Double?             // required for LIMIT; ignored for MARKET
    ): OrderResult {
        if (!isConnected) return OrderResult.Failure("Not connected to Zerodha")
        if (quantity <= 0) return OrderResult.Failure("quantity must be > 0")
        if (orderType == "LIMIT" && (price == null || price <= 0)) {
            return OrderResult.Failure("LIMIT order requires price > 0")
        }
        return try {
            val builder = FormBody.Builder()
                .add("exchange", exchange)
                .add("tradingsymbol", tradingsymbol)
                .add("transaction_type", transactionType)
                .add("quantity", quantity.toString())
                .add("product", "CNC")
                .add("order_type", orderType)
                .add("validity", "DAY")
            if (orderType == "LIMIT" && price != null) {
                // Round to 0.05 tick
                val rounded = Math.round(price * 20.0) / 20.0
                builder.add("price", rounded.toString())
            }
            val req = Request.Builder()
                .url("$BASE_URL/orders/regular")
                .post(builder.build())
                .addHeader("Authorization", "token ${config.zerodhaApiKey}:${config.zerodhaAccessToken}")
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return OrderResult.Failure("Empty response")
                val j = JsonParser.parseString(body).asJsonObject
                if (j.get("status")?.asString != "success") {
                    return OrderResult.Failure(j.get("message")?.asString ?: "Place order failed")
                }
                val id = j.getAsJsonObject("data")?.get("order_id")?.asString
                    ?: return OrderResult.Failure("No order_id in response")
                Log.i(TAG, "Order placed id=$id $transactionType $quantity $tradingsymbol $orderType" +
                        (if (price != null) " @ $price" else ""))
                OrderResult.Success(id)
            }
        } catch (e: Exception) {
            Log.e(TAG, "placeOrder error", e)
            OrderResult.Failure(e.message ?: "Unknown error")
        }
    }

    /** Cancel a regular order by id. Used by the exit ladder to re-price. */
    fun cancelOrder(orderId: String): OrderResult {
        if (!isConnected) return OrderResult.Failure("Not connected to Zerodha")
        return try {
            val req = Request.Builder()
                .url("$BASE_URL/orders/regular/$orderId")
                .delete()
                .addHeader("Authorization", "token ${config.zerodhaApiKey}:${config.zerodhaAccessToken}")
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return OrderResult.Failure("Empty response")
                val j = JsonParser.parseString(body).asJsonObject
                if (j.get("status")?.asString != "success") {
                    return OrderResult.Failure(j.get("message")?.asString ?: "Cancel failed")
                }
                Log.i(TAG, "Order $orderId cancelled")
                OrderResult.Success(orderId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "cancelOrder error", e)
            OrderResult.Failure(e.message ?: "Unknown error")
        }
    }

    /** Fetch order status by id — returns "COMPLETE", "OPEN", "CANCELLED", etc. */
    fun fetchOrderStatus(orderId: String): String? {
        if (!isConnected) return null
        return try {
            val req = Request.Builder()
                .url("$BASE_URL/orders/$orderId")
                .addHeader("Authorization", "token ${config.zerodhaApiKey}:${config.zerodhaAccessToken}")
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return null
                val j = JsonParser.parseString(body).asJsonObject
                if (j.get("status")?.asString != "success") return null
                // data[] is a list of order revisions; last entry is the latest status
                val arr = j.getAsJsonArray("data") ?: return null
                if (arr.size() == 0) return null
                arr[arr.size() - 1].asJsonObject.get("status")?.asString
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchOrderStatus error", e)
            null
        }
    }

    // ═══════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
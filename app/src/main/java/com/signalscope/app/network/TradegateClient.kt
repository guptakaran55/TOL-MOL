package com.signalscope.app.network

import android.util.Log
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Real-time German equity quotes from Tradegate Exchange's public orderbook
 * page (tradegate.de/orderbuch.php?isin=...). This is NOT a published/
 * documented API — it's the AJAX endpoint that page polls to refresh its own
 * price display. Found by inspecting that page's network traffic, not from
 * any Tradegate documentation. It could change or start blocking us without
 * notice, which is why every caller MUST treat a null return as "fall back
 * to Yahoo" rather than an error to surface.
 *
 * Why we need it at all: Yahoo's German quotes (both REST chart endpoint and
 * the streaming websocket) carry Xetra's standard ~15-minute exchange delay.
 * Tradegate is a separate, real-time, regulated German venue — typically
 * within a few basis points of Xetra — so it's a live cross-check even though
 * it isn't the exact same order book T212 executes against.
 *
 * Gates below are all "quietly prefer Yahoo" checks, not user-facing errors:
 * malformed data, a crossed/absurd spread, or a big divergence from Yahoo's
 * own (stale) price all silently fall back rather than popping an alert —
 * a broken scrape should never be more visible to the user than a slightly
 * stale price would have been.
 */
object TradegateClient {
    private const val TAG = "TradegateClient"

    /**
     * THE VENUE MOVED HOUSE. tradegate.de now answers a permanent redirect to
     * tradegatebsx.com, and this asks the new address directly.
     *
     * It worked before only because the HTTP client quietly followed that
     * redirect — every quote paying an extra round trip, and the whole feature
     * one configuration change at their end away from going dark. Which it would
     * have done SILENTLY: a null here means "use Yahoo", by design, so a dead
     * scrape and a merely unavailable quote look identical from the outside.
     * That is the right failure mode and exactly why the address must not be
     * left to chance.
     */
    private const val BASE = "https://www.tradegatebsx.com"
    private const val MAX_SPREAD_FRAC = 0.06      // 6% bid/ask spread cap — wider looks like a bad/halted quote
    private const val MAX_YAHOO_DEVIATION = 0.08  // 8% vs Yahoo's (stale) price — wider looks like wrong instrument/garbage

    data class Quote(val bid: Double, val ask: Double, val last: Double, val mid: Double)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /** German-formatted numbers can arrive as either a JSON number (138.18) or a
     *  comma-decimal string ("51,50") in the same response — normalize both. */
    private fun numOf(el: com.google.gson.JsonElement?): Double? {
        if (el == null || el.isJsonNull) return null
        return try {
            if (el.isJsonPrimitive && el.asJsonPrimitive.isString) {
                el.asString.replace(",", ".").toDoubleOrNull()
            } else el.asDouble
        } catch (_: Exception) { null }
    }

    /**
     * Fetch a sanity-gated real-time quote for [isin]. [yahooRefPrice] (Yahoo's
     * — possibly stale — price) is used only as a plausibility cross-check, not
     * displayed by this function. Returns null on ANY failure or gate rejection;
     * callers must fall back to Yahoo silently, per design.
     */
    fun fetchQuote(isin: String, yahooRefPrice: Double): Quote? {
        if (isin.isBlank()) return null
        return try {
            val req = Request.Builder()
                .url("$BASE/refresh.php?isin=$isin")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Referer", "$BASE/orderbuch.php?isin=$isin")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { Log.d(TAG, "HTTP ${resp.code} for $isin"); return null }
                val body = resp.body?.string() ?: return null
                val o = JsonParser.parseString(body).asJsonObject
                val bid = numOf(o.get("bid")) ?: return null
                val ask = numOf(o.get("ask")) ?: return null
                val last = numOf(o.get("last")) ?: return null
                if (bid <= 0.0 || ask <= 0.0 || last <= 0.0) return null
                if (ask < bid * 0.999) { Log.d(TAG, "$isin crossed book bid=$bid ask=$ask — rejected"); return null }
                val mid = (bid + ask) / 2.0
                val spread = (ask - bid) / mid
                if (spread > MAX_SPREAD_FRAC) { Log.d(TAG, "$isin spread ${spread*100}% too wide — rejected"); return null }
                if (yahooRefPrice > 0.0) {
                    val deviation = Math.abs(mid - yahooRefPrice) / yahooRefPrice
                    if (deviation > MAX_YAHOO_DEVIATION) {
                        Log.d(TAG, "$isin deviates ${deviation*100}% from Yahoo ref $yahooRefPrice — rejected")
                        return null
                    }
                }
                Quote(bid, ask, last, mid)
            }
        } catch (e: Exception) {
            Log.d(TAG, "fetchQuote($isin) failed: ${e.message}")
            null
        }
    }
}

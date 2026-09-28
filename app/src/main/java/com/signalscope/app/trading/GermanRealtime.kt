package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.network.Trading212Client
import com.signalscope.app.network.TradegateClient

// ════════════════════════════════════════════════════════════════════════════
// REAL-TIME GERMAN PRICES
// ════════════════════════════════════════════════════════════════════════════
// Everything German in this app has been priced off Yahoo, and Yahoo's German
// quotes carry Xetra's standard fifteen-minute delay — on the price call and on
// the live websocket alike. Measured rather than assumed: on a normal trading
// afternoon Yahoo's newest one-minute bar for SAP, AIXTRON and Siemens Energy
// was in every case exactly fifteen minutes old.
//
// That is why the panel disagreed with the broker. A quarter of an hour of drift
// is worth roughly 0.3% on a quiet afternoon and far more on a moving one, and
// it was being presented as the current price.
//
// Tradegate is a separate regulated German venue that publishes its order book
// live and free — no account, no key, no login, which is the only reason this is
// possible at all. It is also, usefully, the venue Trading 212 actually quotes
// German stock on, so this moves the app onto the same clock AND the same book
// as the screen the user is comparing against.
//
// ── THE REFERENCE PRICE IS A SAFETY DEVICE, NOT A NICETY ────────────────────
// A quote is fetched by ISIN, and the wrong ISIN returns a confident, valid,
// completely wrong price. While testing this I fed it a bad ISIN for SMA Solar
// and got 1.82 against a true 61.75 — well formed, plausible on its face, and
// off by 97%. The underlying reader rejects a quote that strays more than 8%
// from a supplied reference, so THIS ASKS FOR NOTHING WITHOUT ONE. No reference,
// no Tradegate price: fall back to Yahoo and be fifteen minutes late instead of
// instantly wrong.
// ════════════════════════════════════════════════════════════════════════════

object GermanRealtime {

    private const val TAG = "GermanRealtime"

    /** Bare symbol -> ISIN. Small, and the broker's instrument list behind it is
     *  already cached across the whole app, so this is a memo rather than a store. */
    private val isins = HashMap<String, String>()

    /** Symbol -> when we last failed to place it. Stops a name the broker does not
     *  carry from re-walking the instrument list on every single pass. */
    private val misses = HashMap<String, Long>()
    private const val MISS_TTL_MS = 6 * 60 * 60 * 1000L

    private fun bare(symbol: String) = symbol.trim().uppercase().substringBefore('.')

    /**
     * The instrument's ISIN, via the broker's own instrument list.
     *
     * DELIBERATELY NOT GUESSED OR DERIVED. An ISIN looked up anywhere else is an
     * ISIN nobody has checked against the thing being traded, and the failure
     * mode is a real price for a different company — see the note above.
     */
    fun isinFor(ctx: Context, symbol: String): String? {
        val k = bare(symbol)
        if (k.isBlank()) return null
        synchronized(isins) { isins[k]?.let { return it } }
        val now = System.currentTimeMillis()
        synchronized(misses) {
            val at = misses[k] ?: 0L
            if (at > 0L && now - at < MISS_TTL_MS) return null
        }
        fun miss(): String? {
            synchronized(misses) { misses[k] = now }
            return null
        }
        return try {
            val cfg = ConfigManager(ctx)
            if (!cfg.hasT212Credentials) return miss()
            val client = Trading212Client(cfg)
            val ticker = client.resolveT212Ticker(k, "DE") ?: return miss()
            val isin = (client.fetchInstrumentMetadata(setOf(ticker))
                as? Trading212Client.T212Result.Success)?.data?.get(ticker)?.isin
            if (isin.isNullOrBlank()) return miss()
            synchronized(isins) { isins[k] = isin }
            Log.i(TAG, "$k -> $isin")
            isin
        } catch (e: Exception) {
            Log.d(TAG, "isin lookup for $k failed: ${e.message}")
            miss()
        }
    }

    /**
     * A live mid for a German symbol, or null.
     *
     * [referencePrice] is the plausibility anchor and is REQUIRED — a stale one
     * is fine, which is the whole point: a fifteen-minute-old price is a perfectly
     * good answer to "is this roughly the right company", and a useless answer to
     * "what is it worth now".
     *
     * Null on anything at all going wrong. Every caller falls back to Yahoo, and
     * none of them surfaces an error: a scrape that breaks should never be louder
     * to the user than the slightly stale price it replaced.
     */
    fun mid(ctx: Context, symbol: String, referencePrice: Double): Double? {
        if (referencePrice <= 0.0) return null
        val isin = isinFor(ctx, symbol) ?: return null
        val q = TradegateClient.fetchQuote(isin, referencePrice) ?: return null
        return q.mid.takeIf { it > 0.0 }
    }

    /** For tests and diagnostics: forget everything learned so far. */
    fun reset() {
        synchronized(isins) { isins.clear() }
        synchronized(misses) { misses.clear() }
    }
}

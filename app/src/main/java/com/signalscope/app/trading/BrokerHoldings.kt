package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.network.Trading212Client
import com.signalscope.app.network.ZerodhaClient

// ════════════════════════════════════════════════════════════════════════════
// WHAT THE ACCOUNT ACTUALLY HOLDS — read once, shared by everyone who asks
// ════════════════════════════════════════════════════════════════════════════
// Three different jobs now want this on every tick: the broker reconcile that
// retires sold rows, the stop-level watch, and the strategy pass that judges the
// whole portfolio. Each was making its own call — and for Trading 212 the German
// and US spaces each triggered a fetch of the SAME single account, so one tick
// meant three identical requests to a broker with tight rate limits.
//
// The third one usually lost. And it lost SILENTLY: a rate-limited reply came
// back as an empty holding list, which is indistinguishable from an account that
// holds nothing, so the strategy pass concluded there was nothing to judge and
// returned without a word. Nothing failed, nothing logged, the feature simply
// never ran.
//
// So: one read per broker per short window, and a result type that can say
// UNREADABLE. "I could not find out" and "there is nothing there" are different
// answers, and code that cannot tell them apart will eventually act on the wrong
// one — here by doing nothing, elsewhere by closing every position you own.
// ════════════════════════════════════════════════════════════════════════════

object BrokerHoldings {

    private const val TAG = "BrokerHoldings"

    /** Long enough that every consumer on one tick shares a single call, short
     *  enough that a position bought minutes ago is not missed for long. */
    private const val TTL_MS = 90_000L

    data class Holding(
        val symbol: String,
        val qty: Double,
        val avgCost: Double,
        val price: Double,
        /** Yahoo form, so callers can tell a German listing from a US one. Empty
         *  for brokers where the venue is not encoded in the symbol. */
        val yahoo: String = "",
        /** The broker's own instrument code (Trading 212 only). The one name for
         *  a holding that never depends on spelling a symbol out correctly. */
        val ticker: String = ""
    )

    sealed class Result {
        data class Ok(val holdings: List<Holding>) : Result()
        /** Not connected, rate limited, refused, or threw. Never treat as empty. */
        data class Unreadable(val why: String) : Result()
    }

    private data class Entry(val at: Long, val result: Result)
    private val cache = HashMap<String, Entry>()

    @Synchronized
    private fun cached(key: String): Result? {
        val e = cache[key] ?: return null
        // A failure is cached too, briefly. Hammering a broker that just rate
        // limited us is how a short refusal becomes a long one.
        return if (System.currentTimeMillis() - e.at < TTL_MS) e.result else null
    }

    @Synchronized
    private fun put(key: String, r: Result) { cache[key] = Entry(System.currentTimeMillis(), r) }

    // ── WHAT YOU HOLD, KEPT FOR THE PAGE ────────────────────────────────────
    // The page underlines every stock you hold, wherever its name appears. It
    // must not ask the broker on every repaint, so the last successful read of
    // each space is kept here and read back instantly. An unreadable reply never
    // overwrites it — "could not read" is not "holds nothing".
    private const val HELD_FILE = "held_symbols.json"

    @Synchronized
    private fun remember(ctx: Context, space: String, holdings: List<Holding>) {
        try {
            val f = java.io.File(ctx.filesDir, HELD_FILE)
            val o = try { org.json.JSONObject(if (f.exists()) f.readText() else "{}") } catch (e: Exception) { org.json.JSONObject() }
            o.put(space, org.json.JSONObject()
                .put("at", System.currentTimeMillis())
                .put("symbols", org.json.JSONArray(holdings.map { it.symbol.uppercase() }.distinct())))
            f.writeText(o.toString())
        } catch (e: Exception) { Log.w(TAG, "could not keep the held list: ${e.message}") }
    }

    /** The last known held symbols for [space] ("de", "us", "in"), no network. */
    fun heldSymbols(ctx: Context, space: String): List<String> = try {
        val f = java.io.File(ctx.filesDir, HELD_FILE)
        if (!f.exists()) emptyList() else {
            val a = org.json.JSONObject(f.readText()).optJSONObject(Spaces.baseOf(space.lowercase()))
                ?.optJSONArray("symbols")
            if (a == null) emptyList() else List(a.length()) { a.optString(it) }.filter { it.isNotBlank() }
        }
    } catch (e: Exception) { emptyList() }

    /** Everything in the Trading 212 account — BOTH venues, unfiltered. */
    fun t212(ctx: Context): Result {
        cached("t212")?.let { return it }
        val cfg = ConfigManager(ctx)
        val r = try {
            if (!cfg.hasT212Credentials) Result.Unreadable("no Trading 212 credentials")
            else {
                val client = Trading212Client(cfg)
                when (val p = client.fetchPortfolio()) {
                    is Trading212Client.T212Result.Success ->
                        Result.Ok(p.data.filter { it.quantity > 0.0 }.map {
                            val ys = client.t212ToYahooSymbol(it.ticker)
                            Holding(ys.substringBefore(".").uppercase(),
                                it.quantity, it.averagePrice, it.currentPrice, ys, it.ticker)
                        })
                    is Trading212Client.T212Result.Failure ->
                        Result.Unreadable("Trading 212 said: ${p.message}")
                    is Trading212Client.T212Result.NotConfigured ->
                        Result.Unreadable("Trading 212 not configured")
                }
            }
        } catch (e: Exception) {
            Result.Unreadable(e.message ?: "portfolio read threw")
        }
        if (r is Result.Unreadable) Log.w(TAG, "T212 holdings unreadable — ${r.why}")
        put("t212", r)
        if (r is Result.Ok) {
            remember(ctx, "de", r.holdings.filter { it.yahoo.endsWith(".DE", true) })
            remember(ctx, "us", r.holdings.filter { !it.yahoo.endsWith(".DE", true) })
        }
        return r
    }

    fun zerodha(ctx: Context): Result {
        cached("in")?.let { return it }
        val cfg = ConfigManager(ctx)
        val r = try {
            if (!cfg.isZerodhaConnected) Result.Unreadable("Zerodha not connected")
            else when (val h = ZerodhaClient(cfg).fetchHoldings()) {
                is ZerodhaClient.HoldingsResult.Success ->
                    Result.Ok(h.holdings.filter { it.quantity > 0 }.map {
                        Holding(it.symbol.uppercase(), it.quantity.toDouble(), it.avgPrice, it.ltp)
                    })
                else -> Result.Unreadable("Zerodha holdings call did not succeed")
            }
        } catch (e: Exception) {
            Result.Unreadable(e.message ?: "holdings read threw")
        }
        if (r is Result.Unreadable) Log.w(TAG, "Zerodha holdings unreadable — ${r.why}")
        put("in", r)
        if (r is Result.Ok) remember(ctx, "in", r.holdings)
        return r
    }

    /**
     * The holdings belonging to one space.
     *
     * Trading 212 keeps German and US stock in ONE account, so the venue on the
     * resolved ticker decides which space a holding belongs to. Without that
     * split every German name would be judged twice and reported under the wrong
     * market.
     */
    fun forSpace(ctx: Context, space: String): Result {
        val base = Spaces.baseOf(space.lowercase())
        return when (base) {
            "in" -> zerodha(ctx)
            "de", "us" -> when (val r = t212(ctx)) {
                is Result.Unreadable -> r
                is Result.Ok -> Result.Ok(r.holdings.filter {
                    val isDe = it.yahoo.endsWith(".DE", true)
                    (base == "de") == isDe
                })
            }
            else -> Result.Unreadable("no broker for space $base")
        }
    }

    /**
     * WHAT TRADING 212 ITSELF VALUES ONE OF ITS HOLDINGS AT, or 0.
     *
     * A price that does not come from the price feed, for the moment the feed is
     * the thing in doubt. The broker values the exact instrument an order would
     * trade — matched by its own code first, by the app symbol on that venue
     * after — so when the feed and a live venue disagree, this can say which of
     * them moved. Held names only; the account values nothing it does not hold.
     *
     * The shared read, so one portfolio call per [TTL_MS] however many ask.
     */
    fun t212Price(ctx: Context, symbolOrTicker: String, space: String): Double {
        val k = symbolOrTicker.trim()
        if (k.isBlank()) return 0.0
        val held = (forSpace(ctx, space) as? Result.Ok)?.holdings ?: return 0.0
        val bare = k.uppercase().substringBefore('.')
        val h = held.firstOrNull { it.ticker.equals(k, true) }
            ?: held.firstOrNull { it.symbol.equals(bare, true) }
        return h?.price?.takeIf { it > 0.0 && it.isFinite() } ?: 0.0
    }
}

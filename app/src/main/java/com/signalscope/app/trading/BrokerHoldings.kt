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
        val yahoo: String = ""
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
                                it.quantity, it.averagePrice, it.currentPrice, ys)
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
}

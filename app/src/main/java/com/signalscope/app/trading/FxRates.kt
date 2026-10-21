package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.ConfigManager
import java.util.concurrent.ConcurrentHashMap

// ════════════════════════════════════════════════════════════════════════════
// A EURO BUDGET, SPENT IN EUROS
// ════════════════════════════════════════════════════════════════════════════
// An algo's live budget is set in the account's currency — €1,000 over five
// seats is €200 an order — and the free-cash check compares it in euros too.
// But the order itself was sized in the INSTRUMENT's currency: €200 went to the
// broker as $200, which Trading 212 then charged as about €176. Every US order
// was an eighth smaller than the card said, and the gap moved with the dollar.
//
// So the amount is converted at the moment the order is placed, at a live rate,
// and nowhere else — the budget, the rows and the screens all stay in euros.
//
// A BAD RATE MUST NEVER MEAN A BIGGER ORDER. The conversion multiplies what is
// spent, so a garbage print could multiply it by a hundred. Two fences:
//   • an absolute range no pair between the account's currencies leaves;
//   • no more than a 10% move from the last rate that passed, which no real
//     session produces between two reads an hour apart.
// With no believable rate at all, the order goes out unconverted: the old,
// smaller size. Spending less than planned is recoverable; spending more is not.
// ════════════════════════════════════════════════════════════════════════════

object FxRates {
    private const val TAG = "FxRates"

    /** How long a fetched rate is reused before asking again. */
    const val TTL_MS = 60 * 60_000L
    /** How long a remembered rate stays usable when a fresh one cannot be had. */
    const val REMEMBER_MS = 7L * 24 * 60 * 60_000L
    /** Largest believable move from the last good rate. */
    const val MAX_JUMP = 0.10
    /** No rate between EUR, USD, GBP and CHF has ever left this range. */
    const val MIN_RATE = 0.25
    const val MAX_RATE = 4.0

    data class Quote(val rate: Double, val source: String)

    private val mem = ConcurrentHashMap<String, Pair<Double, Long>>()

    /** Is a freshly read rate believable? Pure, so it can be tested alone. */
    fun plausible(rate: Double, lastGood: Double?): Boolean =
        rate.isFinite() && rate in MIN_RATE..MAX_RATE &&
            (lastGood == null || kotlin.math.abs(rate / lastGood - 1.0) <= MAX_JUMP)

    /**
     * Units of [instrumentCur] per one unit of [accountCur] — 1.14 for a euro
     * account buying dollar stocks. Source says where it came from: "same" (no
     * conversion needed), "live", "remembered", or "none" (1.0, unconverted).
     */
    fun rate(ctx: Context, accountCur: String, instrumentCur: String): Quote {
        if (accountCur.equals(instrumentCur, true)) return Quote(1.0, "same")
        val pair = accountCur.uppercase() + instrumentCur.uppercase()
        val now = System.currentTimeMillis()
        mem[pair]?.let { if (now - it.second < TTL_MS) return Quote(it.first, "live") }
        val cfg = ConfigManager(ctx)
        val last = cfg.fxLastGood(pair)
        val fresh = try {
            com.signalscope.app.network.YahooFinanceClient.fetchLivePrice("$pair=X")
        } catch (e: Exception) { null }
        if (fresh != null && plausible(fresh, last?.first)) {
            mem[pair] = fresh to now
            cfg.setFxLastGood(pair, fresh, now)
            return Quote(fresh, "live")
        }
        if (fresh != null)
            Log.w(TAG, "$pair read $fresh — outside the believable range" +
                (last?.let { " (last good ${it.first})" } ?: "") + ", not used")
        if (last != null && now - last.second < REMEMBER_MS && plausible(last.first, null))
            return Quote(last.first, "remembered")
        Log.w(TAG, "$pair: no usable rate — order goes out unconverted (smaller, never larger)")
        return Quote(1.0, "none")
    }

    /**
     * A budget amount in the account's currency, converted into the currency the
     * order for this namespace's market is actually placed in.
     */
    fun orderCash(ctx: Context, amountInAccountCur: Double, ns: String): Double {
        val acct = try { ConfigManager(ctx).accountCurrency } catch (e: Exception) { "EUR" }
        val q = rate(ctx, acct, Spaces.currencyOf(ns))
        if (q.source != "same")
            Log.i(TAG, "[$ns] ${"%.2f".format(amountInAccountCur)} $acct → " +
                "${"%.2f".format(amountInAccountCur * q.rate)} ${Spaces.currencyOf(ns)} " +
                "at ${"%.4f".format(q.rate)} (${q.source})")
        return amountInAccountCur * q.rate
    }
}

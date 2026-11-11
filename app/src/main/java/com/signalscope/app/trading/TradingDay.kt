package com.signalscope.app.trading

import com.signalscope.app.network.YahooFinanceClient
import java.time.Instant

/**
 * HAS THIS EXCHANGE TRADED TODAY? (the user, 11 Nov 2026)
 *
 * The app knows each market's hours but not its holidays. On 10 Nov, NSE's
 * Diwali holiday, India's 09:16 scan and the news check ran anyway: they read
 * Monday's last prices as today's and alerted on them. Monday's whole-day volume,
 * measured as the first few minutes' worth, came out as "57.8× usual volume".
 *
 * The answer comes from the market's own index. Its last trade on today's date,
 * by the exchange's clock, means today is a trading day. On a holiday the last
 * trade is from the previous session.
 */
object TradingDay {
    private val INDEX = mapOf("in" to "^NSEI", "de" to "^GDAXI", "us" to "^GSPC")
    /** A "no" is asked again after this long: the first trade can be a minute late. */
    private const val RECHECK_MS = 10 * 60_000L

    private data class Known(val day: String, val traded: Boolean, val at: Long)
    private val known = HashMap<String, Known>()

    /** Pure: is [tradedAtMs] on today's date, by [base]'s exchange clock? */
    fun isToday(tradedAtMs: Long, base: String, nowMs: Long): Boolean {
        val zone = MarketHours.zoneOf(base) ?: return true
        if (tradedAtMs <= 0L) return false
        return Instant.ofEpochMilli(tradedAtMs).atZone(zone).toLocalDate() ==
            Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    }

    /**
     * Pure: from a batch of quotes, has the market traded today? Yes when at
     * least a fifth of those with a time traded today: a holiday leaves every
     * one on the previous session, while a normal day leaves only a few thin
     * names that have not traded yet.
     */
    fun tradedFromQuotes(tradedAt: List<Long>, base: String, nowMs: Long): Boolean? {
        val timed = tradedAt.filter { it > 0L }
        if (timed.size < 5) return null
        return timed.count { isToday(it, base, nowMs) } * 5 >= timed.size
    }

    /** Has [base] traded today? Null when it could not be asked: callers then go ahead as before. */
    fun tradedToday(base0: String, nowMs: Long = System.currentTimeMillis()): Boolean? {
        val base = Spaces.baseOf(base0.lowercase())
        val zone = MarketHours.zoneOf(base) ?: return true          // crypto: every day
        val day = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toString()
        synchronized(known) {
            known[base]?.let { k -> if (k.day == day && (k.traded || nowMs - k.at < RECHECK_MS)) return k.traded }
        }
        val sym = INDEX[base] ?: return null
        val last = YahooFinanceClient.fetchLastTradeMs(sym) ?: return null
        val traded = isToday(last, base, nowMs)
        synchronized(known) { known[base] = Known(day, traded, nowMs) }
        return traded
    }
}

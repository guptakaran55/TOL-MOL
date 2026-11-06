package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.signalscope.app.data.StoreCache
import com.signalscope.app.network.YahooFinanceClient
import java.io.File

/**
 * EARNINGS DATES (the user, 6 Nov 2026): when each stock next reports results.
 * The swing trades hold overnight, and a results day can gap straight past a
 * stop. Shown as a "📅 3d" chip on holdings, the watchlist and the stock page,
 * and said before your own buy when results are three days away or less.
 * Nothing here changes what the algos do — that waits for a replay on the PC.
 *
 * Keyed like [AiChips] ("US:NVDA", "DE:IFX", "IN:NETWEB"). Kept a day; a date
 * within the week is checked twice a day (companies move them); a stock with no
 * date known is asked again after three days.
 */
object EarningsDates {
    private const val TAG = "EarningsDates"
    private const val FILE = "earnings_dates.json"
    /** Most stocks asked of Yahoo in one go — the rest wait for the next call. */
    const val MAX_ASK = 30
    private val gson = Gson()

    data class Entry(val atMs: Long = 0L, val estimate: Boolean = false, val checkedAt: Long = 0L)
    private data class Saved(val m: Map<String, Entry>? = null)

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    @Synchronized
    private fun load(ctx: Context): Map<String, Entry> = try {
        StoreCache.read(file(ctx)) { gson.fromJson(it, Saved::class.java) }?.m.orEmpty()
    } catch (e: Exception) { Log.w(TAG, "unreadable", e); emptyMap() }

    @Synchronized
    private fun put(ctx: Context, add: Map<String, Entry>) {
        if (add.isEmpty()) return
        val now = System.currentTimeMillis()
        // Dates long gone are dropped, so the file does not grow for ever.
        val keep = (load(ctx) + add).filterValues { now - it.checkedAt < 30L * 86_400_000L }
        try { StoreCache.write(file(ctx), gson.toJson(Saved(keep))) } catch (e: Exception) { Log.w(TAG, "save failed", e) }
    }

    /** Pure: is the kept answer still good enough? */
    fun fresh(e: Entry?, now: Long): Boolean {
        if (e == null || e.checkedAt <= 0L) return false
        val age = now - e.checkedAt
        return when {
            e.atMs <= 0L -> age < 3L * 86_400_000L
            e.atMs < now - 86_400_000L -> false                       // passed: find the next one
            e.atMs - now < 7L * 86_400_000L -> age < 12L * 3_600_000L
            else -> age < 86_400_000L
        }
    }

    /** Pure: the Yahoo symbol for a key — "DE:IFX" → "IFX.DE", "IN:TCS" → "TCS.NS". */
    fun yahooOf(key: String): String {
        val i = key.indexOf(':'); if (i < 0) return key
        val sym = key.substring(i + 1)
        return when (key.substring(0, i)) { "DE" -> "$sym.DE"; "IN" -> "$sym.NS"; else -> sym }
    }

    /**
     * The dates for [keys], asking Yahoo for those not known or gone stale (at most
     * [MAX_ASK], one after another). Network — call off the main thread.
     * Returns key → {at, est}; at 0 = no date known.
     */
    fun forKeys(ctx: Context, keys: List<String>): Map<String, Map<String, Any>> {
        val now = System.currentTimeMillis()
        val want = keys.map { it.trim().uppercase() }.filter { it.contains(':') && !it.startsWith("XX:") }.distinct()
        val have = load(ctx)
        val add = HashMap<String, Entry>()
        for (k in want.filter { !fresh(have[it], now) }.take(MAX_ASK)) {
            val a = YahooFinanceClient.fetchEarningsDate(yahooOf(k))
            if (!a.ok) break                                  // Yahoo not answering: stop, try next time
            add[k] = Entry(a.atMs, a.estimate, now)
            try { Thread.sleep(250) } catch (_: InterruptedException) { break }
        }
        put(ctx, add)
        val all = have + add
        return want.mapNotNull { k -> all[k]?.let { k to mapOf("at" to it.atMs, "est" to it.estimate) } }.toMap()
    }

    /** What is already known, no network — for the buy check. */
    fun known(ctx: Context, key: String): Entry? = load(ctx)[key.trim().uppercase()]
}

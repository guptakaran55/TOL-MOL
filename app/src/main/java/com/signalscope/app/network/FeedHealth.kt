package com.signalscope.app.network

import android.content.Context
import android.util.Log
import org.json.JSONObject

// ════════════════════════════════════════════════════════════════════════════
// A PRICE FEED THAT HAS STOPPED, TOLD APART FROM A QUIET ONE
// ════════════════════════════════════════════════════════════════════════════
// Pfisterer is the case that made this necessary. Yahoo moved the share to a new
// symbol on 8 Oct 2026 and froze the old one — and the old one did not fail. It
// kept answering every request with a perfectly well-formed reply: a price of
// 66.60, a trading session for today, candles up to 7 Oct. Nothing errored, so
// nothing was flagged, and for a week the app bought on, valued, and pointed its
// stops at a number that was no longer the market. The live venue's price (59)
// was even thrown away for disagreeing with it.
//
// Every chart reply carries two facts that, together, give the game away: when
// the stock last traded, and the session the exchange is in now. A name that has
// not printed a single trade two hours into an open session has not gone quiet,
// its feed has stopped. That is judged here, from replies the app was fetching
// anyway — no extra request — for every name it asks about.
//
// ONCE STOPPED, STOPPED UNTIL A NEWER TRADE IS SEEN. The judgement can only be
// made while a session is open, but it has to hold after the close too: an
// evening order on a frozen name is no safer for the session having ended. So
// the verdict is kept, on disk, and lifted only by a reply showing a trade later
// than the one it froze on.
// ════════════════════════════════════════════════════════════════════════════

object FeedHealth {

    private const val TAG = "FeedHealth"
    private const val FILE = "feed_health.json"

    /**
     * How long a session must have been open with no trade at all before the
     * feed is called stopped.
     *
     * Two hours, because the names this app trades are liquid enough that a
     * morning without a single print is not a quiet morning. Shorter would catch
     * a genuinely thin name between trades; there is no cost to waiting, since
     * every pass before the verdict is also a pass the name's price did not move.
     */
    const val QUIET_GRACE_SEC = 2 * 60 * 60L

    /** A verdict older than this is dropped on load; a name still frozen is
     *  judged again on its next fetch inside a session. */
    private const val FORGET_AFTER_SEC = 14 * 24 * 60 * 60L

    /**
     * THE RULE, pure. True when the exchange is [QUIET_GRACE_SEC] or more into
     * the session it reports and the newest trade is from before that session
     * began.
     *
     * Only ever true INSIDE a session: before the open and after the close a
     * name that last traded yesterday is exactly as it should be.
     */
    fun stopped(lastTradeSec: Long, sessionStartSec: Long, sessionEndSec: Long, nowSec: Long): Boolean =
        lastTradeSec > 0L && sessionStartSec > 0L && sessionEndSec > sessionStartSec &&
            nowSec >= sessionStartSec + QUIET_GRACE_SEC && nowSec < sessionEndSec &&
            lastTradeSec < sessionStartSec

    /**
     * Pure: what a standing verdict becomes after one more reply. [frozenOn] is
     * the trade a verdict froze on (0 = none); the result is the same for the
     * new state. A new verdict needs an open session; lifting one needs only a
     * later trade, whenever it is seen.
     */
    fun next(frozenOn: Long, lastTradeSec: Long, sessionStartSec: Long,
             sessionEndSec: Long, nowSec: Long): Long = when {
        stopped(lastTradeSec, sessionStartSec, sessionEndSec, nowSec) -> lastTradeSec
        frozenOn > 0L && lastTradeSec > frozenOn -> 0L
        else -> frozenOn
    }

    private class Verdict(val lastTradeSec: Long, val noticedSec: Long, var alarmDay: String = "")

    private val verdicts = HashMap<String, Verdict>()
    @Volatile private var dir: java.io.File? = null
    @Volatile private var loaded = false

    private fun key(feedSymbol: String) = feedSymbol.trim().uppercase()
    private fun nowSec() = System.currentTimeMillis() / 1000L

    /** Where the verdicts are kept. Called once, at process start. */
    fun init(ctx: Context) {
        dir = ctx.applicationContext.filesDir
        load()
    }

    private fun load() {
        val d = dir ?: return
        synchronized(verdicts) {
            if (loaded) return
            loaded = true
            try {
                val f = java.io.File(d, FILE)
                if (!f.exists()) return
                val o = JSONObject(f.readText())
                val now = nowSec()
                for (k in o.keys()) {
                    val v = o.optJSONObject(k) ?: continue
                    val noticed = v.optLong("noticed", 0L)
                    if (now - noticed > FORGET_AFTER_SEC) continue
                    verdicts[k] = Verdict(v.optLong("lastTrade", 0L), noticed, v.optString("alarmDay", ""))
                }
            } catch (e: Exception) { Log.w(TAG, "load failed", e) }
        }
    }

    private fun save() {
        val d = dir ?: return
        try {
            val o = JSONObject()
            synchronized(verdicts) {
                for ((k, v) in verdicts) o.put(k, JSONObject()
                    .put("lastTrade", v.lastTradeSec).put("noticed", v.noticedSec)
                    .put("alarmDay", v.alarmDay))
            }
            java.io.File(d, FILE).writeText(o.toString())
        } catch (e: Exception) { Log.w(TAG, "save failed", e) }
    }

    /** Read the two facts off a chart reply's meta block. */
    fun observe(feedSymbol: String, meta: JSONObject?) {
        if (meta == null) return
        val reg = meta.optJSONObject("currentTradingPeriod")?.optJSONObject("regular")
        observe(feedSymbol, meta.optLong("regularMarketTime", 0L),
            reg?.optLong("start", 0L) ?: 0L, reg?.optLong("end", 0L) ?: 0L, nowSec())
    }

    fun observe(feedSymbol: String, lastTradeSec: Long, sessionStartSec: Long,
                sessionEndSec: Long, nowSec: Long) {
        if (lastTradeSec <= 0L) return
        load()
        val k = key(feedSymbol)
        var changed = false
        synchronized(verdicts) {
            val cur = verdicts[k]
            val was = cur?.lastTradeSec ?: 0L
            val now = next(was, lastTradeSec, sessionStartSec, sessionEndSec, nowSec)
            if (now != was) {
                changed = true
                if (now > 0L) {
                    verdicts[k] = Verdict(now, nowSec, cur?.alarmDay ?: "")
                    Log.w(TAG, "$k: no trade since $now although its session opened at " +
                        "$sessionStartSec — the feed has stopped")
                } else {
                    verdicts.remove(k)
                    Log.i(TAG, "$k: trading again (last trade $lastTradeSec)")
                }
            }
        }
        if (changed) save()
    }

    /** When a stopped feed last traded, epoch seconds; 0 when it is live or
     *  nothing has been judged. Memory and one small file — free anywhere. */
    fun stoppedSince(feedSymbol: String): Long {
        load()
        return synchronized(verdicts) { verdicts[key(feedSymbol)]?.lastTradeSec ?: 0L }
    }

    /** The same for an app symbol on an exchange ("PFSE", "DE"). */
    fun stoppedSince(symbol: String, exchange: String): Long =
        stoppedSince(YahooFinanceClient.toYahooSymbol(symbol.trim().uppercase(), exchange))

    /**
     * True once per stopped name per [day] — so the alarm about it is raised
     * once a day rather than once a pass. False for a name that is not stopped.
     */
    fun claimAlarm(feedSymbol: String, day: String): Boolean {
        load()
        val claimed = synchronized(verdicts) {
            val v = verdicts[key(feedSymbol)] ?: return false
            if (v.alarmDay == day) false else { v.alarmDay = day; true }
        }
        if (claimed) save()
        return claimed
    }

    /** "07.10 17:35", in the phone's zone — for the alarm and the refusals. */
    fun describe(lastTradeSec: Long): String =
        java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US)
            .format(java.util.Date(lastTradeSec * 1000L))
}

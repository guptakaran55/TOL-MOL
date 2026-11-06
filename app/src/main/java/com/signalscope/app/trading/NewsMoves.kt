package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.ManualWatchStore
import com.signalscope.app.network.StockAiClient
import com.signalscope.app.network.YahooFinanceClient
import java.time.ZonedDateTime

/**
 * NEWS MOVES (the user, 6 Nov 2026): "tell me the stocks with sudden positive
 * news so I can buy in early; keep the negative for later decisions."
 *
 * Reading the news of every stock all day would cost a lot and find little. The
 * price says first: a stock moving 5% or more on twice its usual volume (or 3%
 * around its results) almost always has news behind it. Only then are its
 * headlines — and its results, when it just reported — read, and:
 *   • your HOLDINGS: an alert either way (good news, or bad news you hold);
 *   • the WATCHLIST (top of the ranking, pins, open entries): an alert when up;
 *   • every move, up or down, goes into [ResultsBook] for good.
 *
 * It only tells. It buys and sells nothing — the alert opens the stock, and the
 * buy, if you make one, goes through the RV gate like any other.
 */
object NewsMoves {
    private const val TAG = "NewsMoves"
    /** Ranked watchlist names looked at, from the top. */
    private const val TOP = 60
    /** Most stocks whose news is read in one pass. */
    private const val MAX_READS = 6

    /** Pure: is this a move worth reading the news for? [volX] is volume against
     *  the usual for this time of day. */
    fun isMove(pct: Double, volX: Double, resultsNear: Boolean): Boolean =
        (kotlin.math.abs(pct) >= 5.0 && volX >= 2.0) ||
            (resultsNear && kotlin.math.abs(pct) >= 3.0 && volX >= 1.5)

    /** Pure: share of the session gone, floored so an early print is not inflated. */
    fun sessionShare(openMin: Int, closeMin: Int, nowMin: Int): Double {
        val len = (closeMin - openMin).coerceAtLeast(1)
        return ((nowMin - openMin).toDouble() / len).coerceIn(0.2, 1.0)
    }

    fun runOnce(ctx: Context, base0: String): String {
        val base = Spaces.baseOf(base0.lowercase())
        val now = System.currentTimeMillis()
        if (!MarketHours.isRegularSession(base, now)) return ""
        val sp = base.uppercase()

        // Who is looked at, and in what role.
        val role = LinkedHashMap<String, String>()                 // key → "held" | "watch"
        val label = HashMap<String, String>()                      // key → the symbol as shown
        when (val r = BrokerHoldings.forSpace(ctx, base)) {
            is BrokerHoldings.Result.Ok -> r.holdings.forEach { h ->
                val s = h.yahoo.ifBlank { h.symbol }
                val k = AiChips.keyOf(s, sp); role[k] = "held"; label[k] = h.symbol }
            is BrokerHoldings.Result.Unreadable -> {}
        }
        try {
            val st = ManualWatchStore.load(ctx, base)
            (st.ranking.take(TOP).map { it.symbol } + st.pinned +
                st.entries.filter { it.status != "CLOSED" }.map { it.symbol }).forEach { s ->
                val k = AiChips.keyOf(s, sp)
                if (k !in role) { role[k] = "watch"; label[k] = s }
            }
        } catch (e: Exception) { Log.w(TAG, "[$base] watchlist unreadable", e) }
        role.keys.removeAll { it.startsWith("XX:") }
        if (role.isEmpty()) return ""

        val ySym = role.keys.associateWith { EarningsDates.yahooOf(it) }
        val quotes = YahooFinanceClient.fetchQuotes(ySym.values.toList()) ?: return "quotes unreadable"

        val zone = MarketHours.zoneOf(base); val open = MarketHours.openOf(base); val close = MarketHours.closeOf(base)
        val share = if (zone != null && open != null && close != null) {
            val t = ZonedDateTime.now(zone)
            sessionShare(open.hour * 60 + open.minute, close.hour * 60 + close.minute, t.hour * 60 + t.minute)
        } else 1.0

        val said = ArrayList<String>()
        var reads = 0
        for ((k, rl) in role) {
            val q = quotes[ySym[k]!!.uppercase()] ?: continue
            if (q.avgVolume10d <= 0L || q.price <= 0.0) continue
            val volX = q.volume / (q.avgVolume10d * share)
            val rep = q.earningsAtMs.takeIf { it > 0L } ?: EarningsDates.known(ctx, k)?.atMs ?: 0L
            val resultsNear = rep in (now - 2 * 86_400_000L)..(now + 12 * 3_600_000L)
            if (!isMove(q.changePct, volX, resultsNear)) continue
            if (ResultsBook.movedToday(ctx, k, now)) continue
            if (reads >= MAX_READS) break
            reads++

            val ctxM = StockAiClient.MarketContext.fromSpace(sp)
            val story = try {
                StockAiClient.fetchStories(label[k] ?: k.substringAfter(':'), 4, ctxM, q.name.ifBlank { null })
                    .firstOrNull { (it.timeMs ?: 0L) > now - 3 * 86_400_000L }
            } catch (e: Exception) { null }
            val surprise = if (resultsNear) try {
                ResultsBook.freshSurprise(ResultsBook.read(ctx, k, force = true), now)
            } catch (e: Exception) { null } else null

            ResultsBook.addMove(ctx, k, ResultsBook.Move(now, q.changePct, volX,
                story?.title.orEmpty(), story?.source.orEmpty(), resultsNear, surprise),
                if (rep <= now) rep else 0L)
            val sym = label[k] ?: k.substringAfter(':')
            Log.i(TAG, "[$base] $sym ${"%+.1f".format(q.changePct)}% on ${"%.1f".format(volX)}× volume" +
                (if (resultsNear) " · results ${ResultsBook.word(surprise)}" else "") +
                (story?.let { " · ${it.title}" } ?: ""))
            val held = rl == "held"
            if (held || q.changePct > 0) {
                try { LabNotifier.newsMove(ctx, base, sym, q.changePct, volX, resultsNear, surprise, story?.title, story?.source, held) }
                catch (e: Exception) { Log.w(TAG, "alert failed", e) }
                said.add("$sym ${"%+.0f".format(q.changePct)}%")
            }
        }
        return if (said.isEmpty()) "" else "moves ${said.joinToString(", ")}"
    }
}

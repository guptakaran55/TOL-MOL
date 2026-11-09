package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.signalscope.app.data.ManualWatchStore
import com.signalscope.app.data.StoreCache
import com.signalscope.app.network.StockAiClient
import com.signalscope.app.network.YahooFinanceClient
import java.io.File
import java.time.ZonedDateTime

/**
 * NEWS MOVES (the user, 6 Nov 2026): big moves on news across each market's
 * WHOLE list — the same stocks the watchlist screens (DAX-and-more, Nasdaq-100,
 * NSE 500) plus your holdings and pins.
 *
 * Reading the news of every stock all day would cost a lot and find little. The
 * price says first: 5% or more on twice the usual volume (or 3% around results)
 * almost always has news behind it. Only then are its headlines — and its
 * results, when it just reported — read.
 *   • Every move goes to the News list in Notifications (per market, with a
 *     "holdings + watchlist" filter) and into that stock's [ResultsBook].
 *   • A phone alert only for YOUR stocks — holdings and the watchlist, up or
 *     down — so the rest of the market never buzzes the phone.
 * It buys and sells nothing.
 */
object NewsMoves {
    private const val TAG = "NewsMoves"
    private const val FILE = "news_moves.json"
    /** Ranked watchlist names counted as "watchlist", from the top. */
    private const val TOP = 60
    /** Most stocks whose news is read in one pass — yours first, then the biggest moves. */
    private const val MAX_READS = 10
    private const val KEEP_DAYS = 14
    private const val MAX_ITEMS = 800
    private val gson = Gson()

    data class Item(val at: Long = 0L, val space: String = "", val key: String = "", val symbol: String = "",
                    val name: String = "", val pct: Double = 0.0, val volX: Double = 0.0,
                    val results: Boolean = false, val surprise: Double? = null,
                    val headline: String = "", val outlet: String = "", val link: String = "",
                    /** "held", "watch" or "market" — when the move was seen. */
                    val role: String = "market")
    private data class Saved(val items: List<Item>? = null)

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    @Synchronized
    fun log(ctx: Context): List<Item> = try {
        StoreCache.read(file(ctx)) { gson.fromJson(it, Saved::class.java) }?.items.orEmpty()
    } catch (e: Exception) { Log.w(TAG, "unreadable", e); emptyList() }

    @Synchronized
    private fun append(ctx: Context, add: List<Item>) {
        if (add.isEmpty()) return
        val cut = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        val all = (log(ctx) + add).filter { it.at > cut }.sortedByDescending { it.at }.take(MAX_ITEMS)
        try { StoreCache.write(file(ctx), gson.toJson(Saved(all))) } catch (e: Exception) { Log.w(TAG, "save failed", e) }
    }

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

    /** Words too common in company names to identify one ("Deutsche", "Tata"):
     *  with these, the first TWO words of the name must both appear. */
    private val SHARED_FIRST = setOf("deutsche", "american", "united", "general", "first", "international",
        "national", "global", "new", "china", "india", "indian", "tata", "adani", "bajaj", "birla", "mahindra")

    /**
     * Pure: does this headline name the company? (the user, 9 Nov 2026: Net-Digital
     * was shown a phone-bundle story, Circus a basketball one — the search had matched
     * the ticker letters.) The first distinctive word of the cleaned name, as a whole
     * word; with no name, the ticker.
     */
    fun namesCompany(headline: String, companyName: String?, symbol: String): Boolean {
        val h = headline.lowercase()
        fun has(w: String) = Regex("(^|[^\\p{L}0-9])" + Regex.escape(w) + "($|[^\\p{L}0-9])").containsMatchIn(h)
        val words = com.signalscope.app.network.NewsDigest.searchName(companyName)
            ?.lowercase()?.split(Regex("[\\s/&,]+"))?.filter { it.length >= 2 }.orEmpty()
        if (words.isNotEmpty()) {
            val first = words.first()
            return if (first in SHARED_FIRST && words.size >= 2) has(first) && has(words[1])
                   else first.length >= 3 && has(first) || (first.length < 3 && words.size >= 2 && has("$first ${words[1]}"))
        }
        val sym = symbol.substringBefore('.').lowercase()
        return sym.length >= 3 && has(sym)
    }

    /** Pure: thinner than the Discovery floor? Price × 10-day average volume against
     *  the market's minimum daily turnover. Unknown (0) passes, as in Discovery. */
    fun tooThin(price: Double, avgVolume: Long, floor: Double): Boolean {
        if (floor <= 0.0) return false
        val t = price * avgVolume
        return t > 0.0 && t < floor
    }

    /** Takes thin market stocks off the list, and clears a headline that is about
     *  some other company (kept before the check existed). */
    @Synchronized
    private fun drop(ctx: Context, keys: Set<String>) {
        val cur = log(ctx)
        val keep = cur.filterNot { it.role == "market" && it.key in keys }.map {
            if (it.headline.isNotBlank() && !namesCompany(it.headline, it.name.ifBlank { null }, it.symbol))
                it.copy(headline = "", outlet = "", link = "") else it
        }
        if (keep != cur)
            try { StoreCache.write(file(ctx), gson.toJson(Saved(keep))) } catch (e: Exception) { Log.w(TAG, "save failed", e) }
    }

    /** Pure: the order news is read in — holdings, then watchlist, then the market;
     *  biggest move first within each. */
    fun readOrder(roles: Map<String, String>, pct: Map<String, Double>): List<String> {
        fun rank(r: String?) = when (r) { "held" -> 0; "watch" -> 1; else -> 2 }
        return pct.keys.sortedWith(compareBy<String>({ rank(roles[it]) }, { -kotlin.math.abs(pct[it] ?: 0.0) }))
    }

    fun runOnce(ctx: Context, base0: String): String {
        val base = Spaces.baseOf(base0.lowercase())
        val now = System.currentTimeMillis()
        if (!MarketHours.isRegularSession(base, now)) return ""
        val sp = base.uppercase()

        val role = LinkedHashMap<String, String>()                 // key → held | watch | market
        val label = HashMap<String, String>()                      // key → the symbol as shown
        when (val r = BrokerHoldings.forSpace(ctx, base)) {
            is BrokerHoldings.Result.Ok -> r.holdings.forEach { h ->
                val k = AiChips.keyOf(h.yahoo.ifBlank { h.symbol }, sp); role[k] = "held"; label[k] = h.symbol }
            is BrokerHoldings.Result.Unreadable -> {}
        }
        try {
            val st = ManualWatchStore.load(ctx, base)
            (st.ranking.take(TOP).map { it.symbol } + st.pinned +
                st.entries.filter { it.status != "CLOSED" }.map { it.symbol }).forEach { s ->
                val k = AiChips.keyOf(s, sp); if (k !in role) { role[k] = "watch"; label[k] = s }
            }
        } catch (e: Exception) { Log.w(TAG, "[$base] watchlist unreadable", e) }
        try {
            ManualEntryWatcher.universeFor(ctx, base).forEach { s ->
                val k = AiChips.keyOf(s, sp); if (k !in role) { role[k] = "market"; label[k] = s }
            }
        } catch (e: Exception) { Log.w(TAG, "[$base] universe unreadable", e) }
        role.keys.removeAll { it.startsWith("XX:") }
        if (role.isEmpty()) return ""

        val ySym = role.keys.associateWith { EarningsDates.yahooOf(it) }
        val quotes = YahooFinanceClient.fetchQuotes(ySym.values.toList()) ?: return "quotes unreadable"

        val zone = MarketHours.zoneOf(base); val open = MarketHours.openOf(base); val close = MarketHours.closeOf(base)
        val share = if (zone != null && open != null && close != null) {
            val t = ZonedDateTime.now(zone)
            sessionShare(open.hour * 60 + open.minute, close.hour * 60 + close.minute, t.hour * 60 + t.minute)
        } else 1.0

        // Every move today not yet read, then read yours first. The rest of the market
        // only when it trades enough to pass the Discovery floor (yours always count).
        val floor = try { com.signalscope.app.data.ConfigManager(ctx).minTurnoverFor(base) } catch (_: Exception) { 0.0 }
        data class Hit(val q: YahooFinanceClient.DayQuote, val volX: Double, val rep: Long, val near: Boolean)
        val hits = HashMap<String, Hit>()
        val thin = HashSet<String>()
        for (k in role.keys) {
            val q = quotes[ySym[k]!!.uppercase()] ?: continue
            if (q.avgVolume10d <= 0L || q.price <= 0.0) continue
            if (role[k] == "market" && tooThin(q.price, q.avgVolume10d, floor)) { thin.add(k); continue }
            val volX = q.volume / (q.avgVolume10d * share)
            val rep = q.earningsAtMs.takeIf { it > 0L } ?: EarningsDates.known(ctx, k)?.atMs ?: 0L
            val near = rep in (now - 2 * 86_400_000L)..(now + 12 * 3_600_000L)
            if (!isMove(q.changePct, volX, near) || ResultsBook.movedToday(ctx, k, now)) continue
            hits[k] = Hit(q, volX, rep, near)
        }
        drop(ctx, thin)                                  // and off the list if already on it
        val order = readOrder(role, hits.mapValues { it.value.q.changePct }).take(MAX_READS)

        val said = ArrayList<String>(); val items = ArrayList<Item>()
        for (k in order) {
            val h = hits[k]!!; val q = h.q; val rl = role[k] ?: "market"
            val sym = label[k] ?: k.substringAfter(':')
            val story = try {
                StockAiClient.fetchStories(sym, 6, StockAiClient.MarketContext.fromSpace(sp), q.name.ifBlank { null })
                    .firstOrNull { (it.timeMs ?: 0L) > now - 3 * 86_400_000L && namesCompany(it.title, q.name.ifBlank { null }, sym) }
            } catch (e: Exception) { null }
            val surprise = if (h.near) try {
                ResultsBook.freshSurprise(ResultsBook.read(ctx, k, force = true), now)
            } catch (e: Exception) { null } else null

            ResultsBook.addMove(ctx, k, ResultsBook.Move(now, q.changePct, h.volX,
                story?.title.orEmpty(), story?.source.orEmpty(), h.near, surprise), if (h.rep <= now) h.rep else 0L)
            items.add(Item(now, base, k, sym, q.name, q.changePct, h.volX, h.near, surprise,
                story?.title.orEmpty(), story?.source.orEmpty(), story?.link.orEmpty(), rl))
            Log.i(TAG, "[$base] $sym ($rl) ${"%+.1f".format(q.changePct)}% on ${"%.1f".format(h.volX)}× volume" +
                (if (h.near) " · results ${ResultsBook.word(surprise)}" else "") + (story?.let { " · ${it.title}" } ?: ""))
            if (rl != "market") {
                try { LabNotifier.newsMove(ctx, base, sym, q.changePct, h.volX, h.near, surprise, story?.title, story?.source, rl == "held") }
                catch (e: Exception) { Log.w(TAG, "alert failed", e) }
                said.add("$sym ${"%+.0f".format(q.changePct)}%")
            }
        }
        append(ctx, items)
        val more = items.count { it.role == "market" }
        return listOfNotNull(said.takeIf { it.isNotEmpty() }?.let { "moves ${it.joinToString(", ")}" },
            more.takeIf { it > 0 }?.let { "$it market moves listed" }).joinToString(" · ")
    }
}

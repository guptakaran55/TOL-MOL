package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.PortfolioResultStore
import com.signalscope.app.data.StoreCache
import com.signalscope.app.data.T212PortfolioStore
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * AI & CHIPS (the user, 6 Nov 2026): every AI and semiconductor stock is marked
 * the same way in every space, and the share of your holdings in them is added
 * up across Trading 212 (Germany and US) and Zerodha, so it is always in view.
 * A limit you set WARNS when a buy takes you over it — it never blocks one, and
 * it never stops an algo or the watchlist (the user's choice).
 *
 * WHICH STOCKS COUNT, in order:
 *   1. your own mark on the stock page (on or off) — always wins;
 *   2. the starting list below (AI names that are not chip makers, and the
 *      main chip names, so they count before any scan has seen them);
 *   3. any stock whose industry, as Yahoo gives it, starts "Semiconductor" —
 *      learned once seen, so a holding without scan data still counts.
 *
 * A stock is keyed by market and bare symbol — "US:NVDA", "DE:IFX", "IN:NETWEB" —
 * so the same letters in two markets never collide.
 */
object AiChips {
    private const val TAG = "AiChips"
    private const val FILE = "ai_chips.json"
    private val gson = Gson()

    /** The starting list. Your marks override it either way. */
    val BUILT_IN: Set<String> = (
        // US — chip makers and their equipment
        listOf("NVDA", "AMD", "AVGO", "TSM", "ASML", "ARM", "MU", "INTC", "QCOM", "MRVL", "AMAT", "LRCX",
            "KLAC", "SMCI", "MPWR", "TXN", "ADI", "ON", "NXPI", "MCHP", "SNPS", "CDNS").map { "US:$it" } +
        // US — AI that Yahoo files under software, internet or equipment
        listOf("MSFT", "GOOGL", "GOOG", "META", "AMZN", "ORCL", "PLTR", "AI", "SNOW", "CRWV", "NBIS",
            "VRT", "ANET", "DELL", "SOUN").map { "US:$it" } +
        // Germany
        listOf("IFX", "AIXA", "WAF", "ELG", "SMHN", "JEN").map { "DE:$it" } +
        // India
        listOf("NETWEB", "KAYNES", "MOSCHIP", "SPEL", "TATAELXSI").map { "IN:$it" }
    ).toSet()

    data class Last(val aiEur: Double = 0.0, val totalEur: Double = 0.0, val at: Long = 0L)

    private data class Saved(
        val on: List<String>? = null,
        val off: List<String>? = null,
        val learned: List<String>? = null,
        /** Warn above this share, in %. 0 = no limit set. */
        val limitPct: Double = 0.0,
        /** The last total worked out, moved on by each buy since. */
        val last: Last? = null
    )

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    @Synchronized
    private fun load(ctx: Context): Saved = try {
        StoreCache.read(file(ctx)) { gson.fromJson(it, Saved::class.java) } ?: Saved()
    } catch (e: Exception) { Log.w(TAG, "unreadable", e); Saved() }

    @Synchronized
    private fun save(ctx: Context, s: Saved) {
        try { StoreCache.write(file(ctx), gson.toJson(s)) } catch (e: Exception) { Log.w(TAG, "save failed", e) }
    }

    @Synchronized
    private fun change(ctx: Context, f: (Saved) -> Saved): Saved { val n = f(load(ctx)); save(ctx, n); return n }

    // ── pure ────────────────────────────────────────────────────────────────

    /** "US:NVDA" from "NVDA" in the US, "DE:IFX" from "IFX.DE", "IN:NETWEB" from
     *  "NETWEB.NS" or "NETWEB" in India. [space] is used only for a bare symbol. Pure. */
    fun keyOf(symbol: String, space: String = ""): String {
        val s = symbol.trim().uppercase()
        val dot = s.lastIndexOf('.')
        if (dot > 0) {
            val base = s.substring(0, dot)
            return when (s.substring(dot + 1)) {
                "NS", "BO" -> "IN:$base"
                "DE", "F" -> "DE:$base"
                else -> "XX:$s"
            }
        }
        return when (space.trim().uppercase()) { "IN" -> "IN:$s"; "DE" -> "DE:$s"; else -> "US:$s" }
    }

    fun isSemis(industry: String?): Boolean = industry?.trim()?.startsWith("Semiconductor", true) == true

    /** Pure: your mark, then the list, then what was learned, then the industry. */
    fun classify(key: String, industry: String?, on: Set<String>, off: Set<String>, learned: Set<String>): Boolean = when {
        key in on -> true
        key in off -> false
        else -> key in BUILT_IN || key in learned || isSemis(industry)
    }

    /** Pure: the share in %, after adding [addEur] (0 = none) to holdings worth [totalEur]. */
    fun shareAfter(aiEur: Double, totalEur: Double, addEur: Double, addIsAi: Boolean): Double {
        val t = totalEur + addEur
        if (t <= 0.0) return 0.0
        return (aiEur + if (addIsAi) addEur else 0.0) / t * 100.0
    }

    // ── marks, the limit, the page's view ───────────────────────────────────

    fun isAi(ctx: Context, symbol: String, space: String, industry: String? = null): Boolean {
        val s = load(ctx)
        return classify(keyOf(symbol, space), industry, s.on.orEmpty().toSet(), s.off.orEmpty().toSet(), s.learned.orEmpty().toSet())
    }

    /** [mode]: "on", "off", or "auto" (back to the list and the industry). */
    fun mark(ctx: Context, key: String, mode: String) {
        val k = key.trim().uppercase().takeIf { it.contains(':') } ?: return
        change(ctx) { s ->
            val on = s.on.orEmpty().toMutableSet().apply { remove(k) }
            val off = s.off.orEmpty().toMutableSet().apply { remove(k) }
            when (mode) { "on" -> on.add(k); "off" -> off.add(k) }
            s.copy(on = on.sorted(), off = off.sorted())
        }
    }

    fun setLimit(ctx: Context, pct: Double) {
        change(ctx) { it.copy(limitPct = if (pct.isFinite() && pct in 0.0..100.0) pct else 0.0) }
    }

    /** Chip stocks the page has seen with their industry — kept, so holdings without scan data count. */
    fun learn(ctx: Context, keys: Collection<String>) {
        val add = keys.map { it.trim().uppercase() }.filter { it.contains(':') }.toSet()
        if (add.isEmpty()) return
        val s = load(ctx)
        if (s.learned.orEmpty().containsAll(add)) return
        change(ctx) { it.copy(learned = (it.learned.orEmpty() + add).toSortedSet().toList()) }
    }

    fun config(ctx: Context): Map<String, Any?> {
        val s = load(ctx)
        return mapOf("builtIn" to BUILT_IN.sorted(), "on" to s.on.orEmpty(), "off" to s.off.orEmpty(),
            "learned" to s.learned.orEmpty(), "limit" to s.limitPct)
    }

    // ── the total ───────────────────────────────────────────────────────────

    private val inrMem = ConcurrentHashMap<String, Pair<Double, Long>>()

    /** Rupees per unit of [acct]. FxRates' fences stop at 4, so India gets its own:
     *  30–300, live for an hour, remembered for a week. 0 = none to be had. */
    private fun inrPer(ctx: Context, acct: String, network: Boolean): Double {
        if (acct.equals("INR", true)) return 1.0
        val pair = acct.uppercase() + "INR"
        val now = System.currentTimeMillis()
        inrMem[pair]?.let { if (now - it.second < FxRates.TTL_MS) return it.first }
        val cfg = ConfigManager(ctx)
        if (network) {
            val fresh = try { com.signalscope.app.network.YahooFinanceClient.fetchLivePrice("$pair=X") } catch (_: Exception) { null }
            if (fresh != null && fresh in 30.0..300.0) {
                inrMem[pair] = fresh to now; cfg.setFxLastGood(pair, fresh, now); return fresh
            }
        }
        return cfg.fxLastGood(pair)?.takeIf { now - it.second < FxRates.REMEMBER_MS && it.first in 30.0..300.0 }?.first ?: 0.0
    }

    /** Units of [cur] per unit of the account currency; 0 = unknown. */
    private fun rateOf(ctx: Context, acct: String, cur: String, network: Boolean): Double = when {
        cur.equals(acct, true) -> 1.0
        cur.equals("INR", true) -> inrPer(ctx, acct, network)
        network -> FxRates.rate(ctx, acct, cur).let { if (it.source == "none") 0.0 else it.rate }
        else -> ConfigManager(ctx).fxLastGood(acct.uppercase() + cur.uppercase())?.first ?: 0.0
    }

    /** Converts [amount] of [cur] to the account currency; null when no rate. */
    private fun toAcct(ctx: Context, acct: String, amount: Double, cur: String, network: Boolean): Double? {
        var c = cur.uppercase(); var a = amount
        if (c == "GBX") { c = "GBP"; a /= 100.0 }
        val r = rateOf(ctx, acct, c, network)
        return if (r > 0.0) a / r else null
    }

    /**
     * Every holding at both brokers, as last fetched, in the account's currency:
     * the share in AI & chips, and the AI & chips holdings themselves. Network
     * (exchange rates) — call off the main thread. Saves the result, so each
     * buy alert can say where a buy leaves it.
     */
    fun exposure(ctx: Context): Map<String, Any?> {
        val acct = try { ConfigManager(ctx).accountCurrency } catch (_: Exception) { "EUR" }
        val s = load(ctx)
        val on = s.on.orEmpty().toSet(); val off = s.off.orEmpty().toSet()
        val learned = s.learned.orEmpty().toMutableSet()
        val rows = ArrayList<Map<String, Any?>>()
        var total = 0.0; var ai = 0.0
        val missing = ArrayList<String>()
        var t212At = 0L; var inAt = 0L

        fun add(sym: String, key: String, name: String, value: Double, cur: String, industry: String?) {
            if (!(value > 0.0)) return
            val v = toAcct(ctx, acct, value, cur, true)
            if (v == null) { if (cur.uppercase() !in missing) missing.add(cur.uppercase()); return }
            if (isSemis(industry)) learned.add(key)
            total += v
            if (classify(key, industry, on, off, learned)) {
                ai += v
                rows.add(mapOf("key" to key, "symbol" to sym, "name" to name, "value" to v))
            }
        }

        try {
            T212PortfolioStore.load(ctx)?.let { raw ->
                val o = JSONObject(raw)
                t212At = o.optLong("capturedAt", 0L)
                val ps = o.optJSONArray("positions")
                for (i in 0 until (ps?.length() ?: 0)) {
                    val p = ps!!.optJSONObject(i) ?: continue
                    val ticker = p.optString("ticker", "")
                    val sym = p.optString("yahooSymbol", "").ifBlank { p.optString("symbol", "") }.ifBlank { ticker }
                    val space = if (ticker.contains("_US_")) "US" else "DE"
                    val qty = p.optDouble("quantity", 0.0); val px = p.optDouble("currentPrice", 0.0)
                    add(sym, keyOf(sym, space), p.optString("instrumentName", p.optString("name", sym)),
                        qty * px, p.optString("currency", "USD"), p.optString("industry", "").ifBlank { null })
                }
            }
        } catch (e: Exception) { Log.w(TAG, "Trading 212 copy unreadable", e) }
        try {
            PortfolioResultStore.load(ctx)?.let { snap ->
                inAt = snap.capturedAt
                val hs = JSONObject(snap.payloadJson).optJSONArray("holdings")
                for (i in 0 until (hs?.length() ?: 0)) {
                    val h = hs!!.optJSONObject(i) ?: continue
                    val sym = h.optString("symbol", "")
                    val v = h.optDouble("currentVal", 0.0).takeIf { it > 0.0 }
                        ?: (h.optDouble("quantity", 0.0) * h.optDouble("ltp", 0.0))
                    add(sym, keyOf(sym, "IN"), h.optString("name", sym), v, "INR", h.optString("industry", "").ifBlank { null })
                }
            }
        } catch (e: Exception) { Log.w(TAG, "Zerodha copy unreadable", e) }

        val now = System.currentTimeMillis()
        change(ctx) { it.copy(last = Last(ai, total, now),
            learned = if (learned.size > it.learned.orEmpty().size) learned.toSortedSet().toList() else it.learned) }
        val pct = if (total > 0.0) ai / total * 100.0 else 0.0
        return mapOf("ok" to true, "cur" to acct, "ai" to ai, "total" to total, "pct" to pct,
            "limit" to s.limitPct, "rows" to rows.sortedByDescending { it["value"] as Double },
            "missing" to missing, "t212At" to t212At, "inAt" to inAt,
            // Units of each currency per account unit, for the page's own sums.
            "fx" to mapOf("USD" to rateOf(ctx, acct, "USD", false), "EUR" to rateOf(ctx, acct, "EUR", false),
                "INR" to rateOf(ctx, acct, "INR", false)))
    }

    /**
     * A REAL BUY WENT THROUGH: moves the saved total on by it, and says — for the
     * buy's own alert — where it leaves AI & chips when that is over your limit.
     * "" otherwise. No network: remembered rates only. Never blocks anything.
     */
    fun afterBuy(ctx: Context, ns: String, symbol: String, cash: Double, cur: String): String = try {
        val space = Spaces.baseOf(ns).uppercase().let { if (it == "IN" || it == "DE") it else "US" }
        val acct = try { ConfigManager(ctx).accountCurrency } catch (_: Exception) { "EUR" }
        val v = toAcct(ctx, acct, cash, cur, false) ?: 0.0
        val isAi = isAi(ctx, symbol, space)
        val s = change(ctx) { st ->
            val l = st.last ?: return@change st
            if (v <= 0.0) st else st.copy(last = l.copy(aiEur = l.aiEur + if (isAi) v else 0.0, totalEur = l.totalEur + v))
        }
        val l = s.last
        if (!isAi || s.limitPct <= 0.0 || l == null || l.totalEur <= 0.0) ""
        else {
            val pct = l.aiEur / l.totalEur * 100.0
            if (pct > s.limitPct) "⚠ AI & chips ~${"%.0f".format(pct)}% · over your ${"%.0f".format(s.limitPct)}%" else ""
        }
    } catch (e: Exception) { Log.w(TAG, "after buy", e); "" }
}

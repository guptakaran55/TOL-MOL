package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.signalscope.app.data.FloorLiveStore
import com.signalscope.app.data.ManualWatchStore
import com.signalscope.app.data.StoreCache
import com.signalscope.app.data.T212ExitGuardStore
import com.signalscope.app.data.T212LiveStore
import com.signalscope.app.data.T212TargetStore
import com.signalscope.app.data.ZerodhaLiveStore
import java.io.File

/**
 * WHY EACH CLOSED TRADE WAS BOUGHT AND SOLD (the user, 6 Nov 2026) — the reasons
 * column of the closed-trades table.
 *
 * Each trade carries the broker order ids that made it. Each id is looked up in
 * the app's own records: the live algos' and the watchlist's ledgers, the German
 * built-in algos' real positions, the week/month targets and the safety stops.
 * An order none of them placed was yours, at the broker. A reason found once is
 * kept ([FILE]), because the target and stop records are dropped 14 days after
 * they finish.
 *
 * Buy:  the algo's name · "watchlist" · "manual on broker" · "app" (placed by the
 *       app with no owner — a plain Patient buy from a stock page).
 * Sell: "SL hit · set by X" · "weekly target · set by X" · the strategy's own exit
 *       "· X" · "set by hand" · "sold by hand".
 */
object TradeReasons {
    private const val TAG = "TradeReasons"
    private const val FILE = "trade_reasons.json"
    private val gson = Gson()

    data class Why(val buy: String, val sell: String)

    private data class Kept(val m: Map<String, String> = emptyMap())

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    @Synchronized
    private fun kept(ctx: Context): Map<String, String> = try {
        StoreCache.read(file(ctx)) { gson.fromJson(it, Kept::class.java) }?.m.orEmpty()
    } catch (e: Exception) { Log.w(TAG, "unreadable", e); emptyMap() }

    @Synchronized
    private fun keep(ctx: Context, add: Map<String, String>) {
        if (add.isEmpty()) return
        val cur = kept(ctx)
        if (add.all { cur[it.key] == it.value }) return
        try { StoreCache.write(file(ctx), gson.toJson(Kept(cur + add))) } catch (e: Exception) { Log.w(TAG, "save failed", e) }
    }

    // ── pure wording ────────────────────────────────────────────────────────

    /** "weekly", "monthly", … from a target record's level kind. Pure. */
    fun levelWord(kind: String?): String {
        val k = (kind ?: "").lowercase()
        return when {
            k.contains("1w") || k.contains("week") -> "weekly"
            k.contains("3m") || k.contains("3month") -> "3-month"
            k.contains("1m") || k.contains("month") -> "monthly"
            k.contains("1y") || k.contains("year") -> "yearly"
            k.contains("resist") -> "resistance"
            k.contains("fixed") -> "fixed"
            else -> ""
        }
    }

    private val TRAIL = Regex("""trailing stop -([0-9.]+)% from a high of ([0-9.]+)(?: \(([0-9.]+)× (half Bollinger width|ATR)\))?""")
    private val FIXED = Regex("""stop loss -([0-9.]+)%""")
    private val FROM_FILL = Regex("""real stop -([0-9.]+)% from fill""")
    private val TP = Regex("""take profit \+([0-9.]+)% from a fill""")
    private val NEAR_HIGH = Regex("""near its ([0-9]+)-(week|month|year) high""")
    private val WEEK_TGT = Regex("""reached its ([0-9]+)-(week|month) high target""")
    /** Stands for "the real drop from this high", filled in from the trade's sale price. */
    const val HIGH_MARK = "⟨H"

    private fun g(x: String): String = x.toDoubleOrNull()?.let { v ->
        if (v == Math.floor(v)) "%.0f".format(v) else "%.1f".format(v) } ?: x
    private fun basisWord(b: String?): String? {
        val (u, k) = (b ?: "").split(':').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        return when (u.uppercase()) { "BB" -> "${g(k)}×BB/2"; "ATR" -> "${g(k)}×ATR"; else -> null }
    }

    /**
     * WHAT SOLD IT, AND BY WHAT MECHANISM (the user, 9 Nov 2026): "SL −4% fixed",
     * "SL −9% from buy", "trail −3.4% from high (1.5×BB/2)", "TP +6%",
     * "target · 3-month high". [basis] is the watchlist entry's trail setting
     * ("BB:1.5"), [hardStopPct] the watchlist's hard floor. Pure.
     *
     * Sales recorded before 9 Nov wrote the trail's MULTIPLE as if it were the
     * drop ("-1.50%" for 1.5× the band); those get [HIGH_MARK] so the real drop
     * is worked out from the sale price ([fillHighMarks]).
     */
    fun exitLabel(note: String?, basis: String? = null, hardStopPct: Double? = null): String {
        val n = (note ?: "").trim()
        TRAIL.find(n)?.let { m ->
            val pct = m.groupValues[1]; val high = m.groupValues[2]
            if (m.groupValues[3].isNotEmpty())
                return "trail −${g(pct)}% from high (${g(m.groupValues[3])}×${if (m.groupValues[4] == "ATR") "ATR" else "BB/2"})"
            val bw = basisWord(basis)
            return if (bw != null) "trail $HIGH_MARK$high⟩ ($bw)" else "trail −${g(pct)}% from high"
        }
        FIXED.find(n)?.let { return "SL −${g(it.groupValues[1])}% fixed" }
        FROM_FILL.find(n)?.let { return "SL −${g(it.groupValues[1])}% from fill" }
        TP.find(n)?.let { return "TP +${g(it.groupValues[1])}% from fill" }
        NEAR_HIGH.find(n)?.let { return "target · ${it.groupValues[1]}-${it.groupValues[2]} high" }
        WEEK_TGT.find(n)?.let { return "target · ${it.groupValues[1]}-${it.groupValues[2]} high" }
        val l = n.lowercase()
        return when {
            l == "stop" -> hardStopPct?.takeIf { it > 0.0 }?.let { "SL −${g(it.toString())}% from buy" } ?: "SL from buy"
            l.contains("your own price") || l == "your own target" -> "your target"
            l == "range target" -> "target · range top"
            l.contains("manual take profit") || l.contains("take-profit tap") -> "take profit (you)"
            else -> shortExit(n)
        }
    }

    /** Pure: replaces each [HIGH_MARK] with the real drop from that high to [sellPrice]. */
    fun fillHighMarks(label: String, sellPrice: Double): String =
        Regex("""⟨H([0-9.]+)⟩""").replace(label) { m ->
            val h = m.groupValues[1].toDoubleOrNull() ?: 0.0
            if (h > 0.0 && sellPrice > 0.0) "−${"%.1f".format((1.0 - sellPrice / h) * 100.0)}% from high" else "from high"
        }

    /** A strategy's exit note, shortened for a table cell. Pure. */
    fun shortExit(note: String?): String {
        val n = (note ?: "").trim()
        if (n.isBlank()) return "sold"
        val l = n.lowercase()
        return when {
            l.contains("your sale from the stock") -> "your sale in the app"
            l.contains("your own order at trading 212") -> "set by hand"
            l.contains("stop") && l.contains("trail") -> "trailing stop"
            l.contains("stop") -> "stop"
            l.contains("slope") || l.contains("macd") -> "MACD turned down"
            l.contains("target") -> "target reached"
            l.contains("time") || l.contains("held") -> "time limit"
            l.contains("trend") -> "trend turned"
            else -> n.take(28)
        }
    }

    /** From Trading 212's own order fields ("TYPE|initiatedFrom"), when no record
     *  of the app's claims the order. Pure. */
    fun fromBroker(meta: String?, buy: Boolean): String {
        val (type, from) = (meta ?: "").split('|').let { (it.getOrNull(0) ?: "").uppercase() to (it.getOrNull(1) ?: "").uppercase() }
        if (type.isBlank() && from.isBlank()) return ""
        val app = from == "API"
        if (buy) return if (app) "app" else "manual on broker"
        return when {
            type.contains("STOP") -> if (app) "SL hit · app" else "SL hit · set by hand"
            type.contains("LIMIT") -> if (app) "sold · app" else "set by hand"
            else -> if (app) "sold · app" else "sold by hand"
        }
    }

    // ── Trading 212 ─────────────────────────────────────────────────────────

    fun t212(ctx: Context, base: String, trips: List<ClosedTrades.RoundTrip>): List<Why> {
        val names = HashMap<String, String>()
        fun name(ns: String) = names.getOrPut(ns) { ShareOwners.nameOf(ctx, ns).trim() }
        val buyBy = HashMap<String, String>()
        val sellBy = HashMap<String, String>()
        // The watchlist's own settings, for what its stops were made of.
        val watch = try { ManualWatchStore.load(ctx, base) } catch (_: Exception) { null }
        val trailOf = watch?.entries.orEmpty().filter { !it.exitReason.isNullOrBlank() }
            .associate { "${it.symbol.uppercase()}|${it.exitReason}" to it.trailBasis }
        val hardStop = watch?.hardStopPct
        try {
            T212LiveStore.load(ctx).trades.filter { Spaces.baseOf(it.ns) == base }.forEach { t ->
                t.allBuyIds().forEach { buyBy["$it"] = name(t.ns) }
                val note = t.exitReasonNote ?: t.exitOrigin
                val isWatch = Spaces.bucketIdOf(t.ns) == ManualWatchStore.ID
                val why = "${exitLabel(note, if (isWatch) trailOf["${t.symbol.uppercase()}|$note"] else null,
                    if (isWatch) hardStop else null)} · ${name(t.ns)}"
                t.allSellIds().forEach { sellBy["$it"] = why }
            }
        } catch (e: Exception) { Log.w(TAG, "ledger unreadable", e) }
        try {
            FloorLiveStore.load(ctx).rows.filter { Spaces.baseOf(it.ns) == base }.forEach { r ->
                r.buyIds.forEach { buyBy["$it"] = name(r.ns) }
                val st = try { com.signalscope.app.data.FloorAlgoStore.get(ctx, r.ns.substringAfter("__"))?.settings } catch (_: Exception) { null }
                r.sellIds.forEach { sellBy["$it"] = "${floorExit(r.exitReason, st?.stopPct, st?.stopUnit, st?.targetPct)} · ${name(r.ns)}" }
            }
        } catch (e: Exception) { Log.w(TAG, "built-in algos unreadable", e) }
        // Targets and stops last: they say more than the ledger's own exit note.
        try {
            T212TargetStore.load(ctx).targets.filter { Spaces.baseOf(it.ns) == base }.forEach { t ->
                val lv = levelWord(t.kind)
                val up = if (t.price > 0.0 && t.paid > 0.0) " +${"%.1f".format((t.price / t.paid - 1.0) * 100.0)}%" else ""
                val why = (if (lv.isNotBlank()) "$lv target$up" else "target reached$up") + " · set by ${name(t.ns)}"
                (listOf(t.orderId) + t.prevIds.orEmpty()).filter { it > 0L }.forEach { sellBy["$it"] = why }
            }
        } catch (e: Exception) { Log.w(TAG, "targets unreadable", e) }
        try {
            T212ExitGuardStore.load(ctx).guards.filter { Spaces.baseOf(it.ns) == base }.forEach { g ->
                // The safety stop under a sale still waiting: half a day's range under
                // the price the strategy sold at, 1–3% (ExitGuard).
                val pct = if (g.level > 0.0 && g.decisionPx > 0.0) " −${"%.1f".format((1.0 - g.level / g.decisionPx) * 100.0)}%" else ""
                (listOf(g.orderId) + g.prevIds.orEmpty()).filter { it > 0L }
                    .forEach { sellBy["$it"] = "safety SL$pct (½ day range) · set by ${name(g.ns)}" }
            }
        } catch (e: Exception) { Log.w(TAG, "stops unreadable", e) }
        val out = resolve(ctx, "t212", trips, buyBy, sellBy)
        return out.mapIndexed { i, w -> if (w.sell.contains(HIGH_MARK)) w.copy(sell = fillHighMarks(w.sell, trips[i].sellPrice)) else w }
    }

    /** Pure: a German built-in algo's exit with its own settings — "SL −4%",
     *  "SL 2×ATR", "TP +7%". */
    fun floorExit(reason: String?, stopPct: Double?, stopUnit: String?, targetPct: Double?): String = when (reason) {
        "stop" -> stopPct?.takeIf { it > 0.0 }?.let { p ->
            when ((stopUnit ?: "PCT").uppercase()) { "ATR" -> "SL ${g(p.toString())}×ATR"; "BB" -> "SL ${g(p.toString())}×BB/2"; else -> "SL −${g(p.toString())}%" }
        } ?: "SL hit"
        "target" -> targetPct?.takeIf { it > 0.0 }?.let { "TP +${g(it.toString())}%" } ?: "target reached"
        else -> shortExit(reason)
    }

    // ── India ───────────────────────────────────────────────────────────────

    fun india(ctx: Context, trips: List<ClosedTrades.RoundTrip>): List<Why> {
        val names = HashMap<String, String>()
        fun name(ns: String) = names.getOrPut(ns) { ShareOwners.nameOf(ctx, ns).trim() }
        val buyBy = HashMap<String, String>()
        val sellBy = HashMap<String, String>()
        val rows = try { ZerodhaLiveStore.load(ctx).trades.filter { Spaces.baseOf(it.ns) == "in" } } catch (_: Exception) { emptyList() }
        rows.forEach { r ->
            r.buyOrderId?.takeIf { it.isNotBlank() }?.let { buyBy[it] = name(r.ns) }
            r.sellOrderId?.takeIf { it.isNotBlank() }?.let { sellBy[it] = "sold · ${name(r.ns)}" }
        }
        val out = resolve(ctx, "kite", trips, buyBy, sellBy)
        // A SALE BY A RESTING TRIGGER carries an order id no record knew in advance:
        // found by the position it closed — same stock, bought near that price.
        return out.mapIndexed { i, w ->
            if (w.sell.isNotBlank() && w.buy.isNotBlank()) return@mapIndexed w
            val t = trips[i]
            val r = rows.firstOrNull { it.symbol.equals(t.symbol, true) && it.entryPrice > 0.0 &&
                Math.abs(it.entryPrice / t.buyPrice - 1.0) < 0.01 && it.gttId != null }
            if (r == null) w.copy(buy = w.buy.ifBlank { "manual on broker" }, sell = w.sell.ifBlank { "sold by hand" })
            else w.copy(buy = w.buy.ifBlank { name(r.ns) },
                sell = w.sell.ifBlank { (if (t.sellPrice >= t.buyPrice) "target reached" else "SL hit") + " · set by ${name(r.ns)}" })
        }
    }

    private fun resolve(ctx: Context, broker: String, trips: List<ClosedTrades.RoundTrip>,
                        buyBy: Map<String, String>, sellBy: Map<String, String>): List<Why> {
        val kept = kept(ctx)
        val add = HashMap<String, String>()
        val out = trips.map { t ->
            fun one(id: String?, meta: String?, live: Map<String, String>, buy: Boolean): String {
                val key = "$broker|${if (buy) "B" else "S"}|${id.orEmpty()}"
                if (!id.isNullOrBlank()) {
                    live[id]?.let { add[key] = it; return it }
                    kept[key]?.let { return it }
                }
                return fromBroker(meta, buy)
            }
            Why(one(t.buyOrderId, t.buyMeta, buyBy, true), one(t.sellOrderId, t.sellMeta, sellBy, false))
        }
        keep(ctx, add)
        return out
    }
}

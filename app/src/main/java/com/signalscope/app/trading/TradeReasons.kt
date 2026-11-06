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
        try {
            T212LiveStore.load(ctx).trades.filter { Spaces.baseOf(it.ns) == base }.forEach { t ->
                t.allBuyIds().forEach { buyBy["$it"] = name(t.ns) }
                val why = "${shortExit(t.exitReasonNote ?: t.exitOrigin)} · ${name(t.ns)}"
                t.allSellIds().forEach { sellBy["$it"] = why }
            }
        } catch (e: Exception) { Log.w(TAG, "ledger unreadable", e) }
        try {
            FloorLiveStore.load(ctx).rows.filter { Spaces.baseOf(it.ns) == base }.forEach { r ->
                r.buyIds.forEach { buyBy["$it"] = name(r.ns) }
                r.sellIds.forEach { sellBy["$it"] = "${shortExit(r.exitReason)} · ${name(r.ns)}" }
            }
        } catch (e: Exception) { Log.w(TAG, "built-in algos unreadable", e) }
        // Targets and stops last: they say more than the ledger's own exit note.
        try {
            T212TargetStore.load(ctx).targets.filter { Spaces.baseOf(it.ns) == base }.forEach { t ->
                val lv = levelWord(t.kind)
                val why = (if (lv.isNotBlank()) "$lv target" else "target reached") + " · set by ${name(t.ns)}"
                (listOf(t.orderId) + t.prevIds.orEmpty()).filter { it > 0L }.forEach { sellBy["$it"] = why }
            }
        } catch (e: Exception) { Log.w(TAG, "targets unreadable", e) }
        try {
            T212ExitGuardStore.load(ctx).guards.filter { Spaces.baseOf(it.ns) == base }.forEach { g ->
                (listOf(g.orderId) + g.prevIds.orEmpty()).filter { it > 0L }.forEach { sellBy["$it"] = "SL hit · set by ${name(g.ns)}" }
            }
        } catch (e: Exception) { Log.w(TAG, "stops unreadable", e) }
        return resolve(ctx, "t212", trips, buyBy, sellBy)
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

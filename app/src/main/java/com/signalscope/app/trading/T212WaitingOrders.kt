package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.T212LiveStore
import com.signalscope.app.network.Trading212Client
import java.io.File

/**
 * EVERY ORDER WAITING AT TRADING 212 — and what put it there.
 *
 * A resting buy holds its cash back from the account until it fills or is
 * cancelled: Trading 212 stops counting that money as free. The app read only
 * the free figure, so a limit buy placed in Trading 212 itself showed nowhere in
 * the app — while the algos' cash check, reading the smaller free figure, warned
 * that the account could not cover their seats, with no word about why.
 *
 * And a resting order is only half an answer without its cause. The app places
 * orders from several different places — an algo's ladder, a 1-week-high
 * target, a safety stop under a waiting sale, a stop or target you set on the
 * portfolio tab — and each is cancelled, moved or explained in a different
 * place. So every order is matched against the records those parts of the app
 * keep, and labelled with the one that placed it.
 *
 * WHO PLACED IT. Trading 212 records where every order came from: "API" is this
 * app; anything else came through Trading 212's own website or app.
 *
 * Read once a pass (one request) and kept here, so the waiting-orders list, the
 * cash card and the algos' cash check can all answer without asking the broker.
 */
object T212WaitingOrders {
    private const val TAG = "T212WaitingOrders"
    private const val FILE = "t212_waiting_orders.json"
    /** Trading 212's currency-conversion fee, paid on a foreign-currency buy. */
    private const val FX_FEE = 0.0015
    private val gson = Gson()

    /** What placed an order — one value per part of the app that places them. */
    object Source {
        const val ALGO_BUY = "algo_buy"            // an algo's buy ladder
        const val ALGO_SELL = "algo_sell"          // an algo's sale ladder
        const val WATCH_BUY = "watch_buy"          // a watchlist patient buy
        const val WATCH_SELL = "watch_sell"        // a watchlist patient sale
        const val WEEK_TARGET = "week_target"      // the 1-week-high target after a buy
        const val SAFETY_STOP = "safety_stop"      // the stop under a sale that is waiting
        const val YOUR_STOP = "your_stop"          // a stop you set on the portfolio tab (trailed by the app)
        const val YOUR_TARGET = "your_target"      // a target you set on the portfolio tab
        const val YOUR_BUY = "your_buy"            // a limit buy you set on the stock page
        const val LADDER_LEFTOVER = "ladder_left"  // a ladder rung that was never cancelled
        const val APP_OTHER = "app_other"          // this app, no record says which part
        const val OUTSIDE = "outside"              // Trading 212's website or app
    }

    data class Order(
        val id: Long,
        val ticker: String,
        val symbol: String,
        val name: String,
        /** "DE" or "US" — which tab the stock belongs to. */
        val space: String,
        /** "BUY" or "SELL". */
        val side: String = "BUY",
        val qty: Double,
        val limitPrice: Double,
        val stopPrice: Double,
        val type: String,
        val currency: String,
        /** Cash a BUY holds back, in the account's currency (converted, with the
         *  conversion fee for a foreign stock). 0 for a sell. */
        val held: Double,
        val createdAt: Long,
        /** "you" — placed outside this app; "app" — placed by this app. */
        val by: String,
        /** One of [Source]. */
        val source: String = "",
        /** The algo or watchlist the order belongs to, when known. */
        val owner: String = "",
        val ownerNs: String = "",
        /** An algo or watchlist ladder still working this order — shown as the
         *  ladder itself, so not again as an order. */
        val ladderRunning: Boolean = false
    ) {
        val isBuy get() = !side.equals("SELL", true)
    }

    data class Snapshot(
        val at: Long = 0L,
        val orders: List<Order> = emptyList(),
        val error: String = "",
        val accountCurrency: String = "EUR"
    )

    fun last(ctx: Context): Snapshot = try {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) Snapshot()
        else gson.fromJson(f.readText(), Snapshot::class.java)?.let {
            @Suppress("SENSELESS_COMPARISON")
            if (it.orders == null) it.copy(orders = emptyList()) else it
        } ?: Snapshot()
    } catch (e: Exception) { Snapshot(error = e.message ?: "unreadable") }

    private fun save(ctx: Context, s: Snapshot) {
        try { File(ctx.filesDir, FILE).writeText(gson.toJson(s)) }
        catch (e: Exception) { Log.w(TAG, "save failed", e) }
    }

    /** This tab's waiting orders, buys first, oldest first within each. */
    fun forSpace(ctx: Context, space: String): List<Order> =
        last(ctx).orders.filter { it.space.equals(space, true) }
            .sortedWith(compareBy<Order>({ !it.isBuy }, { it.createdAt }))

    /** Buys only, every tab — the orders that hold cash back. */
    fun buys(ctx: Context): List<Order> = last(ctx).orders.filter { it.isBuy && !it.ladderRunning }

    /** A cancelled (or already gone) order leaves the list at once, not next pass. */
    fun drop(ctx: Context, orderId: Long) {
        val s = last(ctx)
        if (s.orders.any { it.id == orderId }) save(ctx, s.copy(orders = s.orders.filter { it.id != orderId }))
    }

    /**
     * Read the account's open orders and its free cash. Network — off the main
     * thread. On a failed read the previous list is kept, with the error noted:
     * an empty list would claim nothing is waiting.
     * @return a few words for the pass summary, blank when no buy is waiting.
     */
    fun refresh(ctx: Context): String {
        val config = ConfigManager(ctx)
        if (!config.hasT212Credentials) return ""
        val client = Trading212Client(config)
        var raw = client.fetchOpenOrders()
        if (raw !is Trading212Client.T212Result.Success) {
            // The pass has just read the same list for the targets and stops;
            // the endpoint allows one read every few seconds.
            try { Thread.sleep(6_000L) } catch (_: InterruptedException) {}
            raw = client.fetchOpenOrders()
        }
        val prev = last(ctx)
        val body = (raw as? Trading212Client.T212Result.Success)?.data
        if (body == null) {
            val why = (raw as? Trading212Client.T212Result.Failure)?.message ?: "not configured"
            save(ctx, prev.copy(error = "Could not read Trading 212's open orders: $why"))
            return ""
        }
        val accountCur = config.accountCurrency.ifBlank { "EUR" }
        val orders = parse(ctx, body, accountCur)
        save(ctx, Snapshot(System.currentTimeMillis(), orders, "", accountCur))
        // Free cash while on the line — the cash check compares against it, and
        // reading it only hourly left the check comparing against an old figure.
        try {
            val s = client.fetchAccountSummary()
            if (s is Trading212Client.T212Result.Success) {
                config.t212FreeFundsCached = s.data.freeFunds
                config.t212FreeFundsAt = System.currentTimeMillis()
            }
        } catch (e: Exception) { Log.w(TAG, "free-cash read failed: ${e.message}") }
        val buys = orders.filter { it.isBuy && !it.ladderRunning }
        if (buys.isEmpty()) return ""
        return "⏳ ${buys.size} buy order(s) waiting at T212, holding " +
            "%.2f %s".format(buys.sumOf { it.held }, accountCur)
    }

    /** Where the app's own records say an order came from. */
    private class Origin(val source: String, val ns: String = "", val ladder: Boolean = false)

    private fun origins(ctx: Context): Map<Long, Origin> {
        val m = HashMap<Long, Origin>()
        fun isWatch(ns: String) = Spaces.bucketIdOf(ns) == com.signalscope.app.data.ManualWatchStore.ID
        try {
            T212LiveStore.load(ctx).trades.forEach { r ->
                val w = isWatch(r.ns)
                r.allBuyIds().forEach { id ->
                    m[id] = Origin(if (w) Source.WATCH_BUY else Source.ALGO_BUY, r.ns, r.status == "PENDING")
                }
                r.allSellIds().forEach { id ->
                    m[id] = Origin(if (w) Source.WATCH_SELL else Source.ALGO_SELL, r.ns, r.status == "EXITING")
                }
            }
        } catch (e: Exception) { Log.w(TAG, "live rows unreadable", e) }
        try {
            com.signalscope.app.data.T212TargetStore.load(ctx).targets.forEach { t ->
                (listOf(t.orderId) + t.prevIds.orEmpty()).filter { it > 0L }
                    .forEach { m[it] = Origin(Source.WEEK_TARGET, t.ns) }
            }
        } catch (e: Exception) { Log.w(TAG, "targets unreadable", e) }
        try {
            com.signalscope.app.data.T212ExitGuardStore.load(ctx).guards.forEach { g ->
                (listOf(g.orderId) + g.prevIds.orEmpty()).filter { it > 0L }
                    .forEach { m[it] = Origin(Source.SAFETY_STOP, g.ns) }
            }
        } catch (e: Exception) { Log.w(TAG, "safety stops unreadable", e) }
        try {
            com.signalscope.app.data.T212OcoStore.loadAll(ctx).forEach { p ->
                if (p.slOrderId > 0L) m.putIfAbsent(p.slOrderId, Origin(Source.YOUR_STOP))
                if (p.tgtOrderId > 0L) m.putIfAbsent(p.tgtOrderId, Origin(Source.YOUR_TARGET))
            }
        } catch (e: Exception) { Log.w(TAG, "stop/target pairs unreadable", e) }
        return m
    }

    private fun parse(ctx: Context, body: String, accountCur: String): List<Order> {
        val arr = try {
            val el = JsonParser.parseString(body)
            when {
                el.isJsonArray -> el.asJsonArray
                el.isJsonObject -> listOf("items", "orders", "data")
                    .firstNotNullOfOrNull { k -> el.asJsonObject.get(k)?.takeIf { it.isJsonArray }?.asJsonArray }
                else -> null
            }
        } catch (e: Exception) { null } ?: return emptyList()

        val origin = origins(ctx)
        val names = HashMap<String, String>()
        fun ownerName(ns: String): String = if (ns.isBlank()) "" else names.getOrPut(ns) {
            if (Spaces.bucketIdOf(ns) == com.signalscope.app.data.ManualWatchStore.ID) "your watchlist"
            else try {
                com.signalscope.app.data.BucketStore.get(ctx, ns)?.name ?: "an algo"
            } catch (e: Exception) { "an algo" }
        }
        val fx = HashMap<String, Double>()
        // The order log, read only if an app order is not in any record.
        val logged = HashMap<Unit, Map<Long, Logged>>()

        val out = ArrayList<Order>()
        for (el in arr) {
            val o = el as? JsonObject ?: continue
            fun s(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            fun d(k: String) = try { o.get(k)?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0 } catch (_: Exception) { 0.0 }
            val qtyRaw = d("quantity")
            val sideRaw = s("side").uppercase()
            val isBuy = if (sideRaw.isNotBlank()) sideRaw == "BUY" else qtyRaw > 0.0
            val qty = kotlin.math.abs(qtyRaw) - d("filledQuantity").coerceAtLeast(0.0)
            if (qty <= 0.0) continue
            val id = try { o.get("id")?.asLong ?: 0L } catch (_: Exception) { 0L }
            val ticker = s("ticker")
            val inst = o.getAsJsonObject("instrument")
            val name = inst?.get("name")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            val cur = s("currency").ifBlank {
                inst?.get("currency")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            }.ifBlank { if (T212ClosedTrades.spaceOf(ticker) == "DE") "EUR" else "USD" }
            val limit = d("limitPrice")
            val stop = d("stopPrice")
            // What a buy can cost: its limit, else its trigger.
            val held = if (!isBuy) 0.0 else {
                val px = when { limit > 0.0 -> limit; stop > 0.0 -> stop; else -> 0.0 }
                val inCur = if (px > 0.0) qty * px else d("value")
                if (cur.equals(accountCur, true)) inCur else {
                    val rate = fx.getOrPut(cur) {
                        try { FxRates.rate(ctx, accountCur, cur).rate.takeIf { it > 0.0 } ?: 1.0 } catch (_: Exception) { 1.0 }
                    }
                    inCur / rate * (1 + FX_FEE)
                }
            }
            val fromApi = s("initiatedFrom").equals("API", true)
            val og = origin[id]
            val type = s("type").uppercase()
            val source = og?.source ?: if (!fromApi) Source.OUTSIDE else
                logged.getOrPut(Unit) { fromLog(ctx) }[id]?.let { seen ->
                    when {
                        seen.edited == "tgt" -> Source.YOUR_TARGET
                        seen.edited == "sl" -> Source.YOUR_STOP
                        seen.ladder -> Source.LADDER_LEFTOVER
                        isBuy && type.contains("LIMIT") -> Source.YOUR_BUY
                        !isBuy && type.contains("STOP") -> Source.YOUR_STOP
                        !isBuy -> Source.YOUR_TARGET
                        else -> Source.APP_OTHER
                    }
                } ?: Source.APP_OTHER
            out.add(Order(
                id = id, ticker = ticker,
                symbol = ticker.substringBefore("_").removeSuffix("d").removeSuffix("l").ifBlank { ticker },
                name = name, space = T212ClosedTrades.spaceOf(ticker),
                side = if (isBuy) "BUY" else "SELL",
                qty = qty, limitPrice = limit, stopPrice = stop, type = type,
                currency = cur, held = held, createdAt = parseTime(s("createdAt").ifBlank { s("creationTime") }),
                // No "initiatedFrom" on an older reply: an id the app recorded is the
                // app's, anything else is outside it.
                by = if (fromApi || og != null) "app" else "you",
                source = source,
                owner = og?.ns?.let { ownerName(it) }.orEmpty(),
                ownerNs = og?.ns.orEmpty(),
                ladderRunning = og?.ladder == true))
        }
        return out
    }

    /** What the app's order log says about one order it sent. */
    private class Logged(var edited: String = "", var ladder: Boolean = false)

    /**
     * FOR AN APP ORDER NO RECORD CLAIMS — which in practice is a target or stop
     * you set from the portfolio tab (those are kept only at the broker), or a
     * ladder rung that was never taken down. The order log tells them apart: an
     * edit of the order is logged as a target or stop change, and a ladder rung
     * is one of several orders on the same stock placed within a minute or two.
     */
    private fun fromLog(ctx: Context): Map<Long, Logged> {
        val out = HashMap<Long, Logged>()
        try {
            val f = File(ctx.filesDir, "gtt_activity.jsonl")
            if (!f.exists()) return out
            val creates = HashMap<String, MutableList<Pair<Long, Long>>>()   // sym -> (epoch, id)
            f.forEachLine { line ->
                if (!line.contains("\"broker\":\"t212\"")) return@forEachLine
                val isCreate = line.contains("\"action\":\"create\"")
                val tgt = line.contains("\"action\":\"modify_tgt\"")
                val sl = line.contains("\"action\":\"modify_sl\"") || line.contains("\"action\":\"emergency_sl_create\"")
                if (!isCreate && !tgt && !sl) return@forEachLine
                val o = try { JsonParser.parseString(line).asJsonObject } catch (_: Exception) { return@forEachLine }
                val id = try { o.get("id")?.asLong ?: 0L } catch (_: Exception) { 0L }
                if (id <= 0L) return@forEachLine
                val rec = out.getOrPut(id) { Logged() }
                if (tgt) rec.edited = "tgt"
                if (sl) rec.edited = "sl"
                if (isCreate) {
                    val sym = o.get("sym")?.asString.orEmpty()
                    val at = try { o.get("epoch")?.asLong ?: 0L } catch (_: Exception) { 0L }
                    creates.getOrPut(sym) { ArrayList() }.add(at to id)
                }
            }
            // A ladder places its next rung within a minute or so of the last.
            for ((_, list) in creates) {
                val sorted = list.sortedBy { it.first }
                for (i in sorted.indices) {
                    val near = (i > 0 && sorted[i].first - sorted[i - 1].first < 120_000L) ||
                        (i + 1 < sorted.size && sorted[i + 1].first - sorted[i].first < 120_000L)
                    if (near) out[sorted[i].second]?.let { if (it.edited.isBlank()) it.ladder = true }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "order log unreadable", e) }
        return out
    }

    /** One line naming what placed the order — the waiting-orders list's label. */
    fun label(o: Order): String = when (o.source) {
        Source.ALGO_BUY -> "${o.owner.ifBlank { "An algo" }}'s buy"
        Source.ALGO_SELL -> "${o.owner.ifBlank { "An algo" }}'s sale"
        Source.WATCH_BUY -> "Watchlist patient buy"
        Source.WATCH_SELL -> "Watchlist patient sale"
        Source.WEEK_TARGET -> "1-week-high target" + (if (o.owner.isNotBlank()) " · ${o.owner}" else "")
        Source.SAFETY_STOP -> "Safety stop under a waiting sale" + (if (o.owner.isNotBlank()) " · ${o.owner}" else "")
        Source.YOUR_STOP -> "Your stop-loss, set from the portfolio tab"
        Source.YOUR_TARGET -> "Your target, set from the portfolio tab"
        Source.YOUR_BUY -> "Your limit buy, set from the stock page"
        Source.LADDER_LEFTOVER -> "Left over from a buy or sale ladder"
        Source.APP_OTHER -> "Placed by this app"
        else -> "Placed outside this app"
    }

    /** Where to change or remove it — app-managed orders are re-placed by the
     *  app if cancelled at the broker, so cancelling there is the wrong advice. */
    fun managedBy(o: Order): String = when (o.source) {
        Source.WEEK_TARGET -> "Kept by the app after the buy — remove it from the algo or watchlist card, not at the broker, or it is put back."
        Source.SAFETY_STOP -> "Kept by the app until the waiting sale goes through — it is put back if cancelled at the broker."
        Source.ALGO_SELL, Source.ALGO_BUY, Source.WATCH_BUY, Source.WATCH_SELL -> "Part of a ladder the app is working."
        Source.YOUR_STOP, Source.YOUR_TARGET -> "Set from the portfolio tab — change it there."
        Source.YOUR_BUY -> "Rests at Trading 212 until the price comes down to it, or you cancel it."
        Source.LADDER_LEFTOVER -> "One rung of a ladder the app did not take down afterwards — cancel it unless you still want it."
        Source.APP_OTHER -> "Sent by this app; no record says which part — safe to cancel if you do not want it."
        else -> "Trading 212 says this came from its own website or app, not from this app."
    }

    /** Cancelling from the list is offered only where nothing in the app would
     *  put the order straight back. */
    fun cancellable(o: Order): Boolean = o.source in setOf(Source.OUTSIDE, Source.APP_OTHER, Source.YOUR_TARGET, Source.YOUR_BUY,
            Source.YOUR_STOP, Source.LADDER_LEFTOVER) ||
        (o.isBuy && o.source != Source.ALGO_BUY && o.source != Source.WATCH_BUY)

    private fun parseTime(iso: String): Long = try {
        java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    } catch (_: Exception) {
        try { java.time.Instant.parse(iso).toEpochMilli() } catch (_: Exception) { 0L }
    }
}

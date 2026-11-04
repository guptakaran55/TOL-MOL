package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.ManualWatchStore
import com.signalscope.app.data.StrategyLabStore
import com.signalscope.app.data.ZerodhaLiveStore
import com.signalscope.app.data.ZerodhaTradeStore
import com.signalscope.app.network.ZerodhaClient

/**
 * INDIA'S WAITING BUYS, SETTLED — every pass.
 *
 * An India buy the broker did not fill at once is recorded as waiting: an
 * ordinary order (filled at the next open, or never), or a buy trigger resting at
 * Zerodha after an ordinary order was refused. Nothing ever went back to them. By
 * 26 Oct 2026 the Conservative algo had nine: CYIENT's 8 shares, bought on 22 Oct,
 * still read as waiting — no protective stop, no strategy sale — while triggers
 * for stocks it had long let go stayed live at Zerodha, able to buy at any time.
 *
 * Each waiting algo buy is settled from what the account says:
 *  • FILLED (a fill on record for its order, or for its trigger) — recorded as the
 *    position it is, at the real fill. Shares still held get the usual stop-and-
 *    target trigger; from then on the strategy's exit, or the rules for a position
 *    it has lost, sell it. Shares since sold: a trade the strategy still holds is
 *    left to [ZerodhaHandSales] in the same pass, which books the sale and hands the
 *    seat on; one it let go is closed here at the sale's price.
 *  • STILL WAITING, but the strategy no longer holds the trade — the trigger is
 *    cancelled at Zerodha. Nothing should buy a stock no strategy wants.
 *  • LAPSED — a trigger cancelled or expired, an ordinary order past its session,
 *    with no fill and no unexplained shares — closed, nothing bought.
 *
 * And an algo position recorded as held whose shares were sold, with its strategy
 * trade already ended, is closed at the sale's price.
 *
 * Anything it cannot tell — holdings unreadable, the stock traded today, Zerodha
 * not answering — is left for the next pass. A watchlist buy still waiting on the
 * watchlist is the watchlist's own and is never touched.
 */
object ZerodhaSettle {
    private const val TAG = "ZerodhaSettle"

    /** An ordinary order lives one session, an after-market one to the next open —
     *  four days covers a long weekend. */
    const val ORDER_LAPSE_MS = 4 * 24 * 3600_000L
    /** A row with no order and no trigger: the app stopped before either was placed. */
    const val NEVER_PLACED_MS = 24 * 3600_000L
    private val ENDED = setOf("cancelled", "expired", "deleted", "disabled", "rejected", "missing")

    // ── PURE — tested ────────────────────────────────────────────────────────

    /** One fill, as the settle step reads it. */
    data class Fill(val id: String, val orderId: String, val symbol: String, val side: String,
                    val qty: Double, val price: Double, val atMs: Long)

    /** What one waiting buy turned out to be: the order that filled it, its shares and average. */
    data class Bought(val orderId: String, val qty: Int, val price: Double, val atMs: Long)

    /**
     * The buy behind a waiting row, from the fills on record.
     *
     * By [orderId] when there is one — the row's own order, or the one a fired
     * trigger names — and then [anyQty]: a part-filled order bought fewer. Without
     * one, only the first BUY order of the stock after the row was placed with
     * exactly its share count, not already [claimed] by another row: a looser
     * match could take a buy you made by hand for the algo's.
     */
    fun boughtFor(fills: List<Fill>, symbol: String, sinceMs: Long, qty: Int, orderId: String?,
                  claimed: Set<String>, anyQty: Boolean): Bought? {
        val buys = fills.filter { it.symbol.equals(symbol, true) && it.side == "BUY" && it.qty > 0.0 }
        val orders = (if (!orderId.isNullOrBlank()) buys.filter { it.orderId == orderId }
                      else buys.filter { it.atMs >= sinceMs - 60_000L && it.orderId !in claimed })
            .groupBy { it.orderId }
            .map { (id, fs) ->
                val q = fs.sumOf { it.qty }
                Bought(id, q.toInt(), fs.sumOf { it.price * it.qty } / q, fs.minOf { it.atMs })
            }
            .filter { it.qty > 0 }
            .sortedBy { it.atMs }
        if (!orderId.isNullOrBlank()) return orders.firstOrNull()
        return orders.firstOrNull { it.qty == qty } ?: if (anyQty) orders.firstOrNull() else null
    }

    /**
     * What [qty] shares bought at [boughtMs] sold for, first in first out: the
     * earliest sales after it not already counted for another row ([used], fill
     * id to shares taken, updated here). Null when the sales on record do not
     * cover them. @return average price and the time of the last sale used.
     */
    fun soldFor(fills: List<Fill>, symbol: String, boughtMs: Long, qty: Int,
                used: MutableMap<String, Double>): Pair<Double, Long>? {
        var left = qty.toDouble()
        var value = 0.0; var last = 0L
        val take = HashMap<String, Double>()
        for (f in fills.filter { it.symbol.equals(symbol, true) && it.side == "SELL" && it.atMs >= boughtMs }
                .sortedBy { it.atMs }) {
            if (left <= 1e-9) break
            val free = f.qty - (used[f.id] ?: 0.0)
            if (free <= 1e-9) continue
            val n = minOf(free, left)
            take[f.id] = n; value += n * f.price; left -= n; last = f.atMs
        }
        if (left > 1e-9) return null
        take.forEach { (id, n) -> used[id] = (used[id] ?: 0.0) + n }
        return value / qty to last
    }

    /** How long after a wrong "sold outside the app" the shares are looked for. */
    const val STILL_HELD_WINDOW_MS = 10 * 24 * 3600_000L
    /** What the two outside-sale checks wrote when they closed a record. */
    private const val CLOSED_WATCH = "not held at the broker — closed outside the app"
    private const val CLOSED_ALGO = "Closed: no longer held at Zerodha and no sale on record"
    const val STILL_HELD = "still held — the app lost track of it the day after the buy (settling shares were " +
        "not counted); protected at Zerodha and left to you"

    /**
     * A record closed as sold outside the app that may never have been sold: one of
     * the two outside-sale checks closed it, within [STILL_HELD_WINDOW_MS]. Whether
     * the shares are still there is the pass's question, not this one's.
     */
    fun closedAsGone(r: ZerodhaLiveStore.Row, now: Long): Boolean =
        r.status == "MANUAL_CLOSED" && r.qty > 0 && r.entryPrice > 0.0 &&
            r.manualClosedAt > 0L && now - r.manualClosedAt in 0..STILL_HELD_WINDOW_MS &&
            (r.error == CLOSED_WATCH || r.error.startsWith(CLOSED_ALGO))

    // ── THE PASS ─────────────────────────────────────────────────────────────

    private class Pass(val ctx: Context) {
        val now = System.currentTimeMillis()
        val cfg = ConfigManager(ctx)
        val client = ZerodhaClient(cfg)
        val store = ZerodhaTradeStore.load(ctx)
        val fills = store.rows.mapNotNull { r ->
            val at = atMs(r.filledAt) ?: r.seenAt
            if (r.symbol.isBlank() || at <= 0L) null
            else Fill(r.tradeId.ifBlank { r.orderId + "-" + r.filledAt + "-" + r.qty }, r.orderId,
                r.symbol.uppercase(), r.side.uppercase(), r.qty, r.price, at)
        }
        /** The day the fills on record start; a buy before it may have filled unseen. */
        val coveredFrom: Long = try {
            java.time.LocalDate.parse(store.firstSeenDay).atStartOfDay(IndianSession.ZONE).toInstant().toEpochMilli()
        } catch (e: Exception) { Long.MAX_VALUE }
        /**
         * Zerodha's trigger list, id to state, read once. Asking for one old trigger
         * by its id goes unanswered (30 Oct 2026, all six asked), while the list
         * answers — and a trigger absent from a list that WAS read is not waiting
         * at Zerodha, so it cannot buy. Null when the list could not be read.
         */
        val gttStates: Map<Long, String>? by lazy {
            (client.fetchGttTriggers() as? ZerodhaClient.GttListResult.Success)
                ?.triggers?.associate { it.id to it.status.lowercase() }
        }
        /** Active sell triggers, read once; null when the list could not be read. */
        val sellTriggers: List<ZerodhaClient.GttTrigger>? by lazy {
            (client.fetchGttTriggers() as? ZerodhaClient.GttListResult.Success)?.triggers
                ?.filter { it.status.equals("active", true) && it.transactionType.equals("SELL", true) }
        }
        val claimed = HashSet<String>()
        val usedSells = HashMap<String, Double>()
        val paperOpen = HashMap<String, Set<String>>()
        val held = HashMap<String, Int?>()
        val found = ArrayList<String>(); val cancelled = ArrayList<String>(); val tidied = ArrayList<String>()

        fun atMs(kite: String): Long? = try {
            java.time.LocalDateTime.parse(kite, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .atZone(IndianSession.ZONE).toInstant().toEpochMilli()
        } catch (e: Exception) { null }

        fun wanted(r: ZerodhaLiveStore.Row): Boolean =
            r.paperTradeId in paperOpen.getOrPut(r.ns) {
                try { StrategyLabStore.load(ctx, r.ns).trades.filter { it.status == "OPEN" }.map { it.id }.toHashSet() }
                catch (e: Exception) { setOf(r.paperTradeId) }       // unreadable: assume wanted, touch nothing
            }

        /** Shares of [sym] in the account not claimed by any other position; null when it cannot be told. */
        fun freeFor(sym: String, except: ZerodhaLiveStore.Row): Int? {
            val total = held.getOrPut(sym) { ZerodhaLiveMirror.heldQuantity(ctx, sym) } ?: return null
            val others = ZerodhaLiveStore.load(ctx).trades.filter {
                it.symbol.equals(sym, true) && (it.status == "OPEN" || it.status == "EXITING") &&
                    !(it.ns == except.ns && it.paperTradeId == except.paperTradeId)
            }.sumOf { it.qty }
            return total - others
        }
    }

    private fun isAlgo(ns: String) =
        Spaces.baseOf(ns) == "in" && Spaces.isBucket(ns) && Spaces.bucketIdOf(ns) != ManualWatchStore.ID

    /** Every pass. Returns a few words for the pass summary, blank when idle. */
    fun sweep(ctx: Context): String {
        if (!ConfigManager(ctx).isZerodhaConnected) return ""
        val all = ZerodhaLiveStore.load(ctx).trades
        val watching = try {
            ManualWatchStore.load(ctx, "in").entries.filter { it.status == "PENDING" }
                .map { "${it.symbol.uppercase()}-${it.signalBarTs}" }.toHashSet()
        } catch (e: Exception) { return "" }                    // unreadable: stand back
        val waiting = all.filter { r ->
            r.status == "PENDING" &&
                (isAlgo(r.ns) || (Spaces.baseOf(r.ns) == "in" && r.paperTradeId !in watching))
        }.sortedBy { it.buyAt }
        val stale = all.filter { it.status == "OPEN" && isAlgo(it.ns) && !it.exitHeld && it.qty > 0 }
        if (waiting.isEmpty() && stale.isEmpty()) return ""
        val p = Pass(ctx)
        // Orders other rows already own are never matched to a waiting one.
        all.mapNotNullTo(p.claimed) { it.buyOrderId?.takeIf { id -> id.isNotBlank() && it.status != "PENDING" } }
        for (r in waiting) try { settleWaiting(p, r) } catch (e: Exception) {
            Log.w(TAG, "[${r.ns}] ${r.symbol} waiting buy not settled", e)
        }
        for (r in stale.filter { !p.wanted(it) }) try { closeIfSold(p, r) } catch (e: Exception) {
            Log.w(TAG, "[${r.ns}] ${r.symbol} held-position check failed", e)
        }
        report(p)
        return listOfNotNull(
            p.found.takeIf { it.isNotEmpty() }?.let { "${it.size} found bought" },
            p.cancelled.takeIf { it.isNotEmpty() }?.let { "${it.size} trigger(s) cancelled" },
            p.tidied.takeIf { it.isNotEmpty() }?.let { "${it.size} record(s) closed" }).joinToString(", ")
    }

    private fun settleWaiting(p: Pass, r: ZerodhaLiveStore.Row) {
        val sym = r.symbol.uppercase()
        val algo = isAlgo(r.ns)
        val gtt = r.gttId?.takeIf { it > 0L }
        val order = r.buyOrderId?.takeIf { it.isNotBlank() }
        val state = gtt?.let { id -> p.gttStates?.let { it[id] ?: "missing" } ?: p.client.fetchGttStatus(id) }
        if (gtt != null && state == null) {
            Log.w(TAG, "[${r.ns}] $sym: Zerodha did not say what became of trigger $gtt — left for the next pass")
            return
        }
        // A fired trigger names the order it placed; that order's fills are the buy.
        val fired = if (state == "triggered") gtt?.let { p.client.fetchGttOrderId(it) } else null
        val bought = p.fills.let {
            if (order != null) boughtFor(it, sym, r.buyAt, r.qty, order, p.claimed, anyQty = true)
            else if (fired != null) boughtFor(it, sym, r.buyAt, r.qty, fired, p.claimed, anyQty = true)
            else boughtFor(it, sym, r.buyAt, r.qty, null, p.claimed, anyQty = false)
        } ?: if (order != null && p.now - r.buyAt < ORDER_LAPSE_MS)
            // Today's order is still Zerodha's to describe.
            p.client.fetchOrderFill(order)?.takeIf { it.filledQty > 0 }
                ?.let { Bought(order, it.filledQty, it.avgPrice, p.now) }
          else null

        if (bought != null && algo) {
            p.claimed.add(bought.orderId)
            recordBought(p, r, bought)
            return
        }
        if (bought != null) return                              // a watchlist fill: the watchlist's own

        // NOT BOUGHT (on record).
        if (state == "active") {
            if (algo && p.wanted(r)) return                     // a wait the strategy still stands behind
            val res = ZerodhaLiveMirror.cancelBuyTrigger(p.ctx, r.ns, r.paperTradeId)
            if (res.ok) {
                p.cancelled.add(sym)
                Log.w(TAG, "[${r.ns}] $sym buy trigger $gtt was still waiting after its trade ended — cancelled")
                try { LabNotifier.zerodhaTriggerCancelled(p.ctx, r.ns, sym, r.qty, r.buyAt) } catch (_: Exception) {}
            } else Log.w(TAG, "[${r.ns}] $sym buy trigger $gtt could not be cancelled: ${res.error}")
            return
        }
        val gone = when {
            gtt != null -> state in ENDED || state == "triggered"
            order != null -> {
                if (p.now - r.buyAt < ORDER_LAPSE_MS) {
                    val f = p.client.fetchOrderFill(order) ?: return
                    f.status.uppercase() in setOf("CANCELLED", "REJECTED") && f.filledQty == 0
                } else true
            }
            else -> p.now - r.buyAt > NEVER_PLACED_MS
        }
        if (!gone) return
        // The fills on record must reach back to the buy, and no shares may be left
        // unexplained — either could be a buy that filled unseen.
        if (r.buyAt < p.coveredFrom) return
        val free = p.freeFor(sym, r) ?: return
        if (free >= r.qty) {
            Log.w(TAG, "[${r.ns}] $sym: ${r.qty} shares held that no record explains — waiting buy left as it is")
            return
        }
        ZerodhaLiveStore.upsert(p.ctx, r.copy(status = "CANCELLED", gttId = null,
            error = when {
                gtt != null -> "nothing was bought — " + when (state) {
                    "missing" -> "its trigger is no longer at Zerodha"
                    "triggered" -> "its trigger fired, but no fill came of it"
                    else -> "its trigger at Zerodha is $state"
                }
                order != null -> "nothing was bought — the order lapsed unfilled"
                else -> "nothing was bought — the order never reached Zerodha"
            }))
        p.tidied.add(sym)
        Log.w(TAG, "[${r.ns}] $sym waiting buy closed — nothing bought (${state ?: if (order != null) "order lapsed" else "never placed"})")
    }

    /** A waiting buy that did buy: the position it is, protected if still held. */
    private fun recordBought(p: Pass, r: ZerodhaLiveStore.Row, b: Bought) {
        val sym = r.symbol.uppercase()
        val asBought = r.copy(status = "OPEN", qty = b.qty, entryPrice = b.price, buyOrderId = b.orderId,
            buyAt = minOf(r.buyAt, b.atMs).takeIf { it > 0L } ?: b.atMs, gttId = null, error = "", goneSeenAt = 0L)
        val free = p.freeFor(sym, r) ?: return                   // traded today, or unreadable: next pass
        if (free >= b.qty) {
            ZerodhaLiveStore.upsert(p.ctx, asBought)
            ZerodhaLiveMirror.attachProtectionFor(p.ctx, r.ns, r.paperTradeId, sym, b.qty, b.price)
            p.found.add(sym)
            Log.w(TAG, "[${r.ns}] $sym waiting buy had FILLED — ${b.qty} at ${"%.2f".format(b.price)}, now held and protected")
            try { LabNotifier.zerodhaFoundBought(p.ctx, r.ns, sym, b.qty, b.price, held = true) } catch (_: Exception) {}
            return
        }
        if (p.wanted(r)) {
            // Bought, since sold, and the strategy still holds it: the outside-sale
            // step after this one books the sale and hands the seat on.
            ZerodhaLiveStore.upsert(p.ctx, asBought)
            p.found.add(sym)
            Log.w(TAG, "[${r.ns}] $sym waiting buy had FILLED (${b.qty} at ${"%.2f".format(b.price)}) and been sold — left to the sale check")
            return
        }
        closeSold(p, asBought, b.atMs)
    }

    /** An algo position held on record whose strategy trade has ended: closed if its shares were sold. */
    private fun closeIfSold(p: Pass, r: ZerodhaLiveStore.Row) {
        val free = p.freeFor(r.symbol.uppercase(), r) ?: return
        if (free >= r.qty) return
        closeSold(p, r, r.buyAt)
    }

    /** Closed at the price of the sales after it, first in first out — only when they are on record. */
    private fun closeSold(p: Pass, r: ZerodhaLiveStore.Row, boughtMs: Long) {
        val sold = soldFor(p.fills, r.symbol, boughtMs, r.qty, p.usedSells)
        if (sold == null) {
            Log.w(TAG, "[${r.ns}] ${r.symbol}: bought, no longer held, but no sale on record — left for now")
            return
        }
        ZerodhaLiveStore.upsert(p.ctx, r.copy(status = "CLOSED", exitPrice = sold.first, sellAt = sold.second,
            gttId = null, goneSeenAt = 0L, error = "sold outside the app — found when the record was settled"))
        p.tidied.add(r.symbol.uppercase())
        Log.w(TAG, "[${r.ns}] ${r.symbol}: ${r.qty} bought at ${"%.2f".format(r.entryPrice)} had been sold at " +
            "${"%.2f".format(sold.first)} — record closed")
    }

    private fun report(p: Pass) {
        if (p.tidied.isEmpty()) return
        try { LabNotifier.zerodhaRecordsTidied(p.ctx, p.tidied) } catch (_: Exception) {}
    }

    // ── WRITTEN OFF, STILL HELD ──────────────────────────────────────────────

    /**
     * SHARES THE APP CALLED SOLD THAT NEVER LEFT THE ACCOUNT — every pass.
     *
     * Until 31 Oct 2026 the app did not count shares still settling, so a buy read
     * as gone on the day after it and its record was closed as sold by you: SAILIFE
     * and PFOCUS on 28 Oct, WESTLIFE and ACMESOLAR on 30 Oct. The last two then
     * lost their stop and target to the stale-trigger cleanup, every share still
     * held. A record closed that way is looked at again while the shares are all
     * still there and no sale is on record:
     *  • no sell trigger on the stock at Zerodha → the usual stop and target go back on;
     *  • the strategy still holds the trade → the record reopens and it is managed
     *    again, exactly as before;
     *  • it does not (its exit came while the record was closed, or it was an algo's
     *    and its seat has moved on) → it stays your own holding, protected. Reopening
     *    it would have its exit rules judge it from scratch and sell at the next open
     *    without asking; the portfolio check asks instead.
     * Anything it cannot tell — holdings or the trigger list unreadable, the stock
     * traded today — is left for the next pass.
     */
    fun stillHeld(ctx: Context): String {
        if (!ConfigManager(ctx).isZerodhaConnected) return ""
        val now = System.currentTimeMillis()
        val rows = ZerodhaLiveStore.load(ctx).trades.filter { Spaces.baseOf(it.ns) == "in" && closedAsGone(it, now) }
        if (rows.isEmpty()) return ""
        val p = Pass(ctx)
        val reopened = ArrayList<String>(); val protectedAgain = ArrayList<String>(); val hadStop = ArrayList<String>()
        for (r in rows) try {
            val sym = r.symbol.uppercase()
            if (r.buyAt < p.coveredFrom) continue
            if (p.fills.any { it.symbol == sym && it.side == "SELL" && it.atMs >= r.buyAt }) continue
            val free = p.freeFor(sym, r) ?: continue
            if (free < r.qty) continue
            val triggers = p.sellTriggers ?: continue
            var id = triggers.firstOrNull { it.tradingsymbol.equals(sym, true) }?.id
            if (id == null) {
                ZerodhaLiveMirror.attachProtectionFor(ctx, r.ns, r.paperTradeId, sym, r.qty, r.entryPrice)
                id = ZerodhaLiveStore.forTrade(ctx, r.ns, r.paperTradeId)?.gttId
                if (id == null) {
                    Log.w(TAG, "[${r.ns}] $sym still held, but its stop and target could not be placed — next pass")
                    continue
                }
                protectedAgain.add(sym)
            } else hadStop.add(sym)
            // Read here, not through the pass's "assume held when unreadable": a
            // record is reopened only on the strategy's own word.
            val back = StrategyLabStore.load(ctx, r.ns).trades.any { it.id == r.paperTradeId && it.status == "OPEN" }
            val row =ZerodhaLiveStore.forTrade(ctx, r.ns, r.paperTradeId) ?: r
            ZerodhaLiveStore.upsert(ctx, if (back)
                row.copy(status = "OPEN", gttId = id, manualClosedAt = 0L, goneSeenAt = 0L, error = "")
                else row.copy(gttId = id, error = STILL_HELD))
            if (Spaces.bucketIdOf(r.ns) == ManualWatchStore.ID) {
                ManualWatchStore.load(ctx, "in").entries
                    .firstOrNull { "${it.symbol.uppercase()}-${it.signalBarTs}" == r.paperTradeId && it.status == "CLOSED" }
                    ?.let { e ->
                        ManualWatchStore.upsert(ctx, "in", if (back)
                            e.copy(status = "OPEN", exitTs = 0L, exitPrice = 0.0, exitQty = 0.0,
                                exitReason = "", closedAt = 0L, absentAt = 0L)
                            else e.copy(exitReason = STILL_HELD))
                    }
            }
            if (back) reopened.add(sym)
            Log.w(TAG, "[${r.ns}] $sym: ${r.qty} shares still held after being written off — " +
                (if (back) "reopened" else "left to you") + ", protected by trigger $id")
        } catch (e: Exception) {
            Log.w(TAG, "[${r.ns}] ${r.symbol} still-held check failed", e)
        }
        val all = (protectedAgain + hadStop).distinct()
        if (all.isEmpty()) return ""
        try { LabNotifier.zerodhaStillHeld(ctx, reopened, protectedAgain, all - reopened.toSet()) } catch (_: Exception) {}
        return "${all.size} written-off position(s) found still held"
    }
}

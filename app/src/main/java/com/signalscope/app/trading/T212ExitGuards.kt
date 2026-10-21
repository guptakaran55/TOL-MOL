package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.StrategyLabStore
import com.signalscope.app.data.T212ExitGuardStore
import com.signalscope.app.data.T212LiveStore
import com.signalscope.app.data.T212TargetStore
import com.signalscope.app.network.Trading212Client
import com.signalscope.app.network.YahooFinanceClient

/**
 * SAFETY STOPS UNDER SALES THAT ARE WAITING — [ExitGuard] holds the rule.
 *
 * A sale is WAITING when the strategy has closed the trade on paper but the
 * shares are still held: the decision came after the close, or the patient
 * ladder found no buyer above its floor. The exit retry sells it at the next
 * chance; until then nothing protects it, and the next chance may be the next
 * morning. This puts one stop at Trading 212 under it for that gap.
 *
 * Runs on every background pass, AFTER the exit retry. So:
 *   • a retry that sells clears the stop first (every app sale cancels the
 *     stock's waiting orders) and this then finds the position gone and tidies;
 *   • a retry that finds no buyer leaves nothing resting, and this puts the stop
 *     straight back on the same pass;
 *   • a stop that executed is found here — in the broker's history, by its order
 *     id — and the sale is recorded at its real price, everywhere.
 *
 * Only one sell order can wait per share at Trading 212, so the position's own
 * 1-week-high target is taken off first: the strategy has decided to sell, and a
 * target far above the price protects nothing. A sell order from anywhere else
 * is left alone — the stop then waits behind it, and the app's next sale attempt
 * clears both.
 */
object T212ExitGuards {
    private const val TAG = "T212ExitGuards"
    private const val ORIGIN = "its safety stop at Trading 212"
    private const val REFUSED_RETRY_MS = 60 * 60_000L
    private const val ORDER_SPACING_MS = 2500L
    private const val RATE_BACKOFF_MS = 6000L

    /**
     * The positions whose sale is waiting, as (ns|paperTradeId) keys. One read of
     * each algo's paper record per call. Also asked by the 1-week-high targets,
     * which must not put a target back on a position that is being sold.
     */
    fun waitingKeys(ctx: Context, rows: List<T212LiveStore.LiveTrade>): Set<String> {
        val closedByNs = HashMap<String, Set<String>>()
        return rows.filter { r ->
            r.status == "OPEN" && r.qty > 0.0 && !r.isManual() && T212WeekTargets.applies(r.ns)
        }.filter { r ->
            val closed = closedByNs.getOrPut(r.ns) {
                try {
                    StrategyLabStore.load(ctx, r.ns).trades
                        .filter { it.status == "CLOSED" }.map { it.id }.toHashSet()
                } catch (e: Exception) { emptySet() }
            }
            r.paperTradeId in closed
        }.map { T212ExitGuardStore.key(it.ns, it.paperTradeId) }.toSet()
    }

    private fun ownQty(r: T212LiveStore.LiveTrade): Double =
        if (r.liveQtyFilled > 0.0) minOf(r.qty, r.liveQtyFilled) else r.qty

    private fun short(msg: String): String =
        msg.substringBefore(" payload=").substringBefore(" Hint:").trim().let {
            if (it.length > 140) it.take(138) + "…" else it
        }

    /** One pass. Returns a few words for the pass summary, blank when idle. */
    fun keep(ctx: Context): String {
        val cfg = ConfigManager(ctx)
        if (!cfg.hasT212Credentials) return ""
        val now = System.currentTimeMillis()
        val ledger = T212LiveStore.load(ctx).trades.filter { T212WeekTargets.applies(it.ns) }
        val waiting = waitingKeys(ctx, ledger)
        val rows = ledger.filter { T212ExitGuardStore.key(it.ns, it.paperTradeId) in waiting }
        val guards = T212ExitGuardStore.load(ctx).guards.filter { !it.done }
        if (rows.isEmpty() && guards.isEmpty()) return ""

        val client = Trading212Client(cfg)
        val resting = when (val r = client.fetchOpenOrders()) {
            is Trading212Client.T212Result.Success -> T212WeekTargets.parseOpen(r.data) ?: return ""
            else -> { Log.w(TAG, "open orders unavailable"); return "" }
        }
        val fills: Map<Long, Trading212Client.HistoricalFill>? by lazy {
            (client.fetchOrderHistory(limit = 50) as? Trading212Client.T212Result.Success)
                ?.data?.filter { it.filledQuantity > 0.0 }?.associateBy { it.orderId }
        }
        fun filledUnder(g: T212ExitGuardStore.Guard): Trading212Client.HistoricalFill? {
            val ids = listOfNotNull(g.orderId.takeIf { it > 0L }) + (g.prevIds ?: emptyList())
            if (ids.isEmpty()) return null
            val f = fills ?: return null
            return ids.firstNotNullOfOrNull { f[it] }
        }

        var set = 0
        var sold = 0

        // ── SALES NO LONGER WAITING: sold by the stop, by the app, or by hand ──
        for (g in guards) {
            if (g.key() in waiting) continue
            val row = ledger.firstOrNull { it.ns == g.ns && it.paperTradeId == g.paperTradeId }
            if (row?.status == "EXITING") continue          // a sale is running: its call
            try {
                val cur = g.orderId > 0L && resting.containsKey(g.orderId)
                val hit = if (cur) null else filledUnder(g)
                if (hit != null && row != null &&
                    (row.status == "OPEN" || (row.status == "CLOSED" && row.allSellIds().isEmpty()))) {
                    recordFill(ctx, row, g, hit, now); sold++; continue
                }
                if (cur) {
                    val off = SellGuard.ifIdle(g.ticker) {
                        client.cancelOrder(g.orderId) is Trading212Client.T212Result.Success
                    } ?: false
                    if (!off) continue                         // next pass
                    Thread.sleep(ORDER_SPACING_MS)
                }
                T212ExitGuardStore.update(ctx, g.ns, g.paperTradeId, { g }) {
                    it.copy(orderId = 0L, done = true, doneAt = now,
                        note = if (it.filledAt > 0L) it.note else "the sale went through another way")
                }
            } catch (e: Exception) { Log.w(TAG, "[${g.ns}] ${g.symbol} guard tidy-up failed", e) }
        }

        // ── SALES STILL WAITING ───────────────────────────────────────────────
        for (row in rows) {
            try {
                val existing = T212ExitGuardStore.get(ctx, row.ns, row.paperTradeId)
                if (existing != null && existing.done) continue
                // Sold by the stop, and something has since written the row back
                // to open from an older copy. Say it again; place nothing.
                if (existing != null && existing.filledAt > 0L) {
                    T212LiveStore.get(ctx, row.paperTradeId, row.ns)?.takeIf { it.status == "OPEN" }?.let {
                        T212LiveStore.upsert(ctx, it.copy(status = "CLOSED"))
                    }
                    continue
                }
                if (existing != null && existing.orderId > 0L && resting.containsKey(existing.orderId)) continue

                // Gone from the open list: executed, or taken off (by the app's own
                // sale attempt, or by you). The history says which.
                var known: T212ExitGuardStore.Guard? = existing
                if (existing != null && (existing.orderId > 0L || !existing.prevIds.isNullOrEmpty())) {
                    val hit = filledUnder(existing)
                    if (hit != null) { recordFill(ctx, row, existing, hit, now); sold++; continue }
                    if (fills == null) continue                  // cannot tell yet
                    if (existing.orderId > 0L)
                        known = T212ExitGuardStore.update(ctx, row.ns, row.paperTradeId, { existing }) {
                            it.copy(prevIds = (it.prevIds ?: emptyList()) + it.orderId, orderId = 0L)
                        }
                }
                if (known != null && now < known.retryAfter) continue

                val want = ownQty(row)
                if (want <= 0.0) continue
                val priceNow = MarkToMarket.priceNow(ctx, row.ns, row.symbol)
                val guard = known?.takeIf { it.level > 0.0 }
                    ?: settle(ctx, row, priceNow, now) ?: continue
                val level = guard.level

                // Already under it: a stop would sell at once, at whatever the
                // market gives. The app's own sale is the better route, and it is
                // already trying at every pass.
                if (ExitGuard.wouldTriggerNow(level, priceNow)) {
                    if (guard.note.isNullOrBlank() || !guard.note.startsWith("the price is already"))
                        T212ExitGuardStore.update(ctx, row.ns, row.paperTradeId, { guard }) {
                            it.copy(note = "the price is already under the safety level — the app's own " +
                                "sale handles it at the next attempt")
                        }
                    continue
                }

                // The position's own 1-week-high target holds the shares; the
                // strategy is selling, so it comes off first.
                T212TargetStore.get(ctx, row.ns, row.paperTradeId)?.let { t ->
                    if (!t.done && t.orderId > 0L && resting.containsKey(t.orderId)) {
                        val off = SellGuard.ifIdle(row.t212Ticker) {
                            client.cancelOrder(t.orderId) is Trading212Client.T212Result.Success
                        } ?: false
                        if (!off) return@let
                        Thread.sleep(ORDER_SPACING_MS)
                        T212TargetStore.update(ctx, row.ns, row.paperTradeId, { t }) {
                            it.copy(prevIds = (it.prevIds ?: emptyList()) + it.orderId, orderId = 0L,
                                note = "taken off — the strategy is selling this position")
                        }
                    }
                }

                if (place(ctx, client, row, guard, level, want, now)) set++
            } catch (e: Exception) { Log.w(TAG, "[${row.ns}] ${row.symbol} guard pass failed", e) }
        }
        return listOfNotNull(
            if (set > 0) "🛡 $set safety stop${if (set == 1) "" else "s"} set" else null,
            if (sold > 0) "🛡 $sold sold by safety stop" else null).joinToString(" · ")
    }

    /** Work the stop price out once and record it. Null when there is no price
     *  to anchor on yet (asked again next pass). */
    private fun settle(ctx: Context, row: T212LiveStore.LiveTrade, priceNow: Double?,
                       now: Long): T212ExitGuardStore.Guard? {
        val base = Spaces.baseOf(row.ns)
        val decision = try {
            StrategyLabStore.load(ctx, row.ns).trades
                .firstOrNull { it.id == row.paperTradeId }?.exitPrice ?: 0.0
        } catch (e: Exception) { 0.0 }
        val anchor = listOf(decision, priceNow ?: 0.0, row.liveEntryPrice).firstOrNull { it > 0.0 } ?: 0.0
        val ys = YahooFinanceClient.toYahooSymbol(row.symbol, Spaces.exchangeOf(base))
        val atr = try { PatientExecutor.dailyAtr(ys, if (base == "de") "DE" else "US", anchor) }
                  catch (e: Exception) { 0.0 }
        val lv = ExitGuard.level(decision, priceNow, atr) ?: return null
        Log.i(TAG, "[${row.ns}] ${row.symbol} safety stop level $lv " +
            "(sold on paper at $decision, now ${priceNow ?: "?"}, daily range $atr)")
        return T212ExitGuardStore.update(ctx, row.ns, row.paperTradeId, {
            T212ExitGuardStore.Guard(ns = row.ns, paperTradeId = row.paperTradeId,
                symbol = row.symbol.uppercase(), ticker = row.t212Ticker, createdAt = now)
        }) { it.copy(level = lv, decisionPx = decision) }
    }

    /** Put the stop in, inside [SellGuard] and after re-reading the position,
     *  because a sale may have started since the pass began. */
    private fun place(ctx: Context, client: Trading212Client, row: T212LiveStore.LiveTrade,
                      g: T212ExitGuardStore.Guard, level: Double, want: Double, now: Long): Boolean {
        var placedId = 0L
        var placedQty = 0.0
        val err: String = SellGuard.ifIdle(row.t212Ticker) {
            val fresh = T212LiveStore.get(ctx, row.paperTradeId, row.ns)
            if (fresh == null || fresh.status != "OPEN") return@ifIdle ""
            var last = ""
            for (q in WeekTarget.qtyAttempts(want)) {
                var rate = 0
                var next = false
                while (!next) {
                    when (val r = client.placeOrder(row.t212Ticker, q, "SELL", "STOP",
                                                    limitPrice = null, stopPrice = level,
                                                    timeValidity = "GOOD_TILL_CANCEL")) {
                        is Trading212Client.T212Result.Success -> {
                            placedId = r.data; placedQty = q; return@ifIdle ""
                        }
                        is Trading212Client.T212Result.Failure -> {
                            if (r.httpCode == 429 && rate < 2) { rate++; Thread.sleep(RATE_BACKOFF_MS) }
                            else if (r.message.contains("precision", true)) { last = r.message; next = true }
                            else return@ifIdle r.message
                        }
                        is Trading212Client.T212Result.NotConfigured -> return@ifIdle "Trading 212 is not set up"
                    }
                }
            }
            last.ifBlank { "refused" }
        } ?: return false

        if (placedId > 0L) {
            val after = T212ExitGuardStore.update(ctx, row.ns, row.paperTradeId, { g }) {
                it.copy(orderId = placedId, orderQty = placedQty, placedAt = now, retryAfter = 0L,
                    note = null)
            }
            Log.w(TAG, "[${row.ns}] ${row.symbol} SAFETY STOP resting: sell $placedQty if it falls to $level " +
                "(order $placedId)")
            if (!after.announced) {
                try {
                    LabNotifier.exitGuardSet(ctx, row.ns, row.symbol, level, after.decisionPx, placedQty,
                        if (Spaces.baseOf(row.ns) == "de") "EUR" else "USD", row.paperTradeId)
                } catch (_: Exception) {}
                T212ExitGuardStore.update(ctx, row.ns, row.paperTradeId, { after }) { it.copy(announced = true) }
            }
            Thread.sleep(ORDER_SPACING_MS)
            return true
        }
        if (err.isNotBlank()) {
            val held = err.contains("selling-equity-not-owned", true) || err.contains("owned: 0", true)
            T212ExitGuardStore.update(ctx, row.ns, row.paperTradeId, { g }) {
                it.copy(retryAfter = now + REFUSED_RETRY_MS,
                    note = if (held)
                        "another sell order on this stock holds the shares at Trading 212, so the stop " +
                            "cannot go in beside it — the app's next sale attempt clears both. Checked hourly"
                    else "Trading 212 refused the safety stop: ${short(err)} — trying again within the hour")
            }
            Log.w(TAG, "[${row.ns}] ${row.symbol} safety stop refused: ${short(err)}")
        }
        return false
    }

    /** The stop executed: close the position everywhere at the real price. */
    private fun recordFill(ctx: Context, row: T212LiveStore.LiveTrade, g: T212ExitGuardStore.Guard,
                           fill: Trading212Client.HistoricalFill, now: Long) {
        val px = fill.fillPrice.takeIf { it > 0.0 } ?: g.level
        T212LiveStore.get(ctx, row.paperTradeId, row.ns)?.let { cur ->
            if (cur.status == "OPEN" || (cur.status == "CLOSED" && cur.allSellIds().isEmpty()))
                T212LiveStore.upsert(ctx, cur.copy(status = "CLOSED",
                    sellOrderId = fill.orderId, sellOrderIds = listOf(fill.orderId),
                    sellAt = if (cur.status == "CLOSED" && cur.sellAt > 0L) cur.sellAt else now,
                    refExitPrice = g.level, error = "", exitOrigin = ORIGIN,
                    exitReasonNote = "the strategy's sale was waiting; its safety stop at " +
                        "${"%.2f".format(g.level)} sold it first"))
        }
        T212ExitGuardStore.update(ctx, row.ns, row.paperTradeId, { g }) {
            it.copy(orderId = 0L, filledAt = now, fillPrice = px, fillQty = fill.filledQuantity,
                done = true, doneAt = now, note = null)
        }
        Log.w(TAG, "[${row.ns}] ${row.symbol} SOLD BY SAFETY STOP ${g.level}: ${fill.filledQuantity} @ $px")
        try { T212LiveMirror.reconcileFills(ctx) }
        catch (e: Exception) { Log.w(TAG, "fill read after the safety stop failed", e) }
    }

    /** What the algo card shows, keyed "ns|paperTradeId". */
    fun views(ctx: Context): Map<String, Map<String, Any?>> =
        T212ExitGuardStore.load(ctx).guards.associate { g ->
            g.key() to mapOf("level" to g.level, "resting" to (g.orderId > 0L),
                "note" to (g.note ?: ""), "filled" to (g.filledAt > 0L),
                "fillPrice" to g.fillPrice, "done" to g.done)
        }
}

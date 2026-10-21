package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.ManualWatchStore
import com.signalscope.app.data.StrategyLabStore
import com.signalscope.app.data.ZerodhaLiveStore
import com.signalscope.app.data.ZerodhaTradeStore

/**
 * INDIA'S HALF OF [T212HandSales] — an algo's Zerodha shares sold by something
 * other than the algo's own sell decision, brought into its books.
 *
 *  1. YOUR SALE FROM THE STOCK PAGE ([afterAppSale]): the algo positions it took
 *     are closed at the sale's own average price, booked like a take-profit, and
 *     each seat is handed on to the next contender.
 *
 *  2. GONE FROM THE ACCOUNT ([sweep], every pass): settled holdings show none
 *     of an algo's shares. The day's fills — which the app keeps as Kite empties
 *     its list overnight — say what sold them and at what price:
 *       • a sale at a PROFIT, or any sale by an order that is not the algo's own
 *         trigger — your target, your own sale in Kite — is booked like a
 *         take-profit, and the seat moves on;
 *       • the algo's own protective trigger selling at a LOSS is its stop: booked
 *         the same way, but the seat keeps the stock, exactly as after any stop.
 *     With no fill on record it waits for a second reading half an hour later,
 *     then closes the position with its result set aside and hands the seat on.
 *
 * Watchlist positions are left to the watchlist's own check, as on Trading 212.
 */
object ZerodhaHandSales {
    private const val TAG = "ZerodhaHandSales"
    private const val GONE_CONFIRM_MS = 30 * 60_000L
    const val REASON_YOURS = "your own order sold it"
    const val REASON_TRIGGER_STOP = "its protective trigger at Zerodha sold it"

    private fun isAlgo(ns: String) =
        Spaces.baseOf(ns) == "in" && Spaces.isBucket(ns) && Spaces.bucketIdOf(ns) != ManualWatchStore.ID

    /** Open algo positions whose paper trade is still open, one paper read per algo. */
    private fun heldRows(ctx: Context, symbol: String? = null): List<ZerodhaLiveStore.Row> {
        val openIds = HashMap<String, Set<String>>()
        return ZerodhaLiveStore.load(ctx).trades.filter {
            isAlgo(it.ns) && it.status == "OPEN" && !it.isManual && !it.exitHeld && it.qty > 0 &&
                (symbol == null || it.symbol.equals(symbol, true))
        }.filter { r ->
            r.paperTradeId in openIds.getOrPut(r.ns) {
                try { StrategyLabStore.load(ctx, r.ns).trades.filter { it.status == "OPEN" }.map { it.id }.toHashSet() }
                catch (e: Exception) { emptySet() }
            }
        }
    }

    /** Close one position at [px], book the paper side, and — unless [keepSeat] —
     *  hand the seat on. @return the stock that took the seat, or null. */
    private fun close(ctx: Context, r: ZerodhaLiveStore.Row, px: Double, reason: String,
                      keepSeat: Boolean, now: Long): String? {
        ZerodhaLiveStore.upsert(ctx, r.copy(status = "CLOSED", exitPrice = px, sellAt = now,
            gttId = null, exitHeld = false, goneSeenAt = 0L, error = ""))
        var seat: String? = null
        StrategyLabRunner.withAlgoHeld(r.ns, 90_000L) {
            val open = StrategyLabStore.load(ctx, r.ns).trades
                .firstOrNull { it.id == r.paperTradeId && it.status == "OPEN" }
            if (open != null) AlgoTakeProfit.bookPaperExit(ctx, r.ns, open, px, now, reason)
            if (!keepSeat) seat = try { MacdGateEngine.handOn(ctx, r.ns, r.symbol, reason) } catch (e: Exception) { null }
            true
        }
        Log.w(TAG, "[${r.ns}] ${r.symbol} closed at $px — $reason; seat → ${if (keepSeat) "kept" else seat ?: "-"}")
        return seat
    }

    /**
     * After your sale from the stock page. [heldBefore] is the holding the sale
     * started from, when known: shares beyond what the algos hold are counted as
     * sold first. Returns a sentence for the result dialog.
     */
    fun afterAppSale(ctx: Context, symbol: String, soldQty: Int, avgPrice: Double, heldBefore: Int?): String {
        if (soldQty <= 0 || avgPrice <= 0.0) return ""
        val rows = heldRows(ctx, symbol)
        if (rows.isEmpty()) return ""
        val managed = rows.sumOf { it.qty }
        var excess = if (heldBefore != null) managed - (heldBefore - soldQty) else minOf(soldQty, managed)
        if (excess <= 0) return ""
        val now = System.currentTimeMillis()
        val notes = ArrayList<String>()
        for (r in rows.sortedByDescending { it.buyAt }) {
            if (excess <= 0) break
            if (excess >= r.qty) {
                val seat = close(ctx, r, avgPrice, "sold by you", false, now)
                notes += "${label(ctx, r.ns)}'s ${r.symbol} position is closed at ₹${"%.2f".format(avgPrice)}" +
                    (if (seat != null) "; its seat goes to $seat." else ".")
                excess -= r.qty
            } else {
                ZerodhaLiveStore.upsert(ctx, r.copy(qty = r.qty - excess))
                notes += "${label(ctx, r.ns)} keeps ${r.qty - excess} ${r.symbol}."
                excess = 0
            }
        }
        return notes.joinToString(" ")
    }

    private fun label(ctx: Context, ns: String): String =
        try { com.signalscope.app.data.BucketStore.list(ctx, "in").firstOrNull { it.ns == ns }?.name }
        catch (e: Exception) { null } ?: "The algo"

    /** The day's SELL fills of [symbol] after [sinceMs], from the trade record. */
    private fun sellsAfter(ctx: Context, symbol: String, sinceMs: Long): List<ZerodhaTradeStore.Row> {
        val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        return try {
            ZerodhaTradeStore.load(ctx).rows.filter { t ->
                t.symbol.equals(symbol, true) && t.side.equals("SELL", true) && t.qty > 0.0 && t.price > 0.0 &&
                    try {
                        java.time.LocalDateTime.parse(t.filledAt, fmt).atZone(IndianSession.ZONE)
                            .toInstant().toEpochMilli() >= sinceMs - 60_000L
                    } catch (e: Exception) { t.seenAt >= sinceMs }
            }
        } catch (e: Exception) { emptyList() }
    }

    /** Every pass. Returns a few words for the pass summary, blank when idle. */
    fun sweep(ctx: Context): String {
        if (!ConfigManager(ctx).isZerodhaConnected) return ""
        val rows = heldRows(ctx)
        if (rows.isEmpty()) return ""
        val now = System.currentTimeMillis()
        var closed = 0
        for (r in rows) {
            try {
                // Null = unreadable, or traded today (not in holdings yet) — do nothing.
                val held = ZerodhaLiveMirror.heldQuantity(ctx, r.symbol) ?: continue
                if (held > 0) {
                    if (r.goneSeenAt > 0L) ZerodhaLiveStore.upsert(ctx, r.copy(goneSeenAt = 0L))
                    continue
                }
                val sells = sellsAfter(ctx, r.symbol, r.buyAt)
                if (sells.isNotEmpty()) {
                    val q = sells.sumOf { it.qty }
                    val px = sells.sumOf { it.price * it.qty } / q
                    val ownTriggerStop = r.gttId != null && r.entryPrice > 0.0 && px < r.entryPrice
                    val seat = close(ctx, r, px, if (ownTriggerStop) REASON_TRIGGER_STOP else REASON_YOURS,
                        keepSeat = ownTriggerStop, now = now)
                    closed++
                    if (!ownTriggerStop) try {
                        LabNotifier.yourSaleBooked(ctx, r.ns, r.symbol, px, r.entryPrice, seat, r.paperTradeId, "INR")
                    } catch (_: Exception) {}
                    continue
                }
                // No fill on record: the second reading decides.
                if (r.goneSeenAt <= 0L) {
                    ZerodhaLiveStore.upsert(ctx, r.copy(goneSeenAt = now))
                    continue
                }
                if (now - r.goneSeenAt < GONE_CONFIRM_MS) continue
                ZerodhaLiveStore.upsert(ctx, r.copy(status = "MANUAL_CLOSED", gttId = null,
                    manualClosedAt = now, goneSeenAt = 0L,
                    error = "Closed: no longer held at Zerodha and no sale on record — sold outside the app."))
                var seat: String? = null
                StrategyLabRunner.withAlgoHeld(r.ns, 60_000L) {
                    StrategyLabStore.unenroll(ctx, r.symbol, r.ns)
                    seat = try { MacdGateEngine.handOn(ctx, r.ns, r.symbol, "sold by you outside the app") }
                           catch (e: Exception) { null }
                    true
                }
                closed++
                try { LabNotifier.soldOutsideApp(ctx, r.ns, r.symbol, seat, r.paperTradeId) } catch (_: Exception) {}
            } catch (e: Exception) { Log.w(TAG, "[${r.ns}] ${r.symbol} hand-sale check failed", e) }
        }
        return if (closed > 0) "✋ $closed Indian position(s) sold outside the algo, closed" else ""
    }
}

package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.BinanceLiveStore
import com.signalscope.app.data.StrategyLabStore
import com.signalscope.app.data.T212LiveStore
import com.signalscope.app.data.ZerodhaLiveStore

/**
 * "TAKE PROFIT NOW" ON AN ALGO'S OPEN POSITION — the sale is yours, the record
 * stays the algo's.
 *
 * Different from "I sold this myself", which is for a sale already made in the
 * broker's app and deliberately DISCARDS the trade so the strategy carries
 * neither credit nor blame for an exit it did not choose. Here the sale goes
 * through the app, and the trade is kept: closed in the algo's paper record at
 * the price it sold for, with its result booked and the seat credited exactly as
 * for any other exit. Nothing else about the algo changes — same seat, same
 * name, same rules; it buys this stock again on its next fresh entry signal.
 *
 * THE REAL SALE COMES FIRST, AND THE PAPER ONLY FOLLOWS IT. If a real position
 * stands behind the paper one, it is sold through the same route the algo's own
 * exits use for that broker (the order ladder, the Zerodha trigger cancel, the
 * Binance shared-balance guard). If that sale does not go through — a shut market,
 * a refusal — nothing is changed anywhere, and you are told why. A paper record
 * that says "sold" over shares still held would be worse than no button at all.
 *
 * Held against the algo's own pass for its duration, so a pass running at the
 * same moment cannot write its older view of the position over this one.
 */
object AlgoTakeProfit {
    private const val TAG = "AlgoTakeProfit"
    const val REASON = "manual take profit"

    data class Result(
        val ok: Boolean,
        val error: String = "",
        val symbol: String = "",
        /** A real position was sold, not only the paper one. */
        val real: Boolean = false,
        val broker: String = "",
        val exitPrice: Double = 0.0,
        /** Net of the algo's own cost assumption, like every other closed trade. */
        val pctReturn: Double = 0.0,
        val pnl: Double = 0.0,
        val note: String = ""
    )

    private fun round4(x: Double) = Math.round(x * 10000.0) / 10000.0

    /** The paper trade, closed at [exitPrice]. Pure, so the arithmetic is tested. */
    fun closedTrade(open: StrategyLabStore.LedgerTrade, exitPrice: Double, exitSec: Long,
                    costPct: Double, notional: Double,
                    reason: String = REASON): StrategyLabStore.LedgerTrade {
        val gross = if (open.entryPrice > 0.0) (exitPrice / open.entryPrice - 1.0) * 100.0 else 0.0
        val net = round4(gross - costPct)
        return open.copy(status = "CLOSED", exitTs = exitSec, exitPrice = exitPrice,
            exitReason = reason, pctReturn = net, pnlNotional = round4(notional * net / 100.0))
    }

    /**
     * Close the algo's paper trade at a price a REAL sale achieved, and credit
     * it like any other exit. Shared by the take-profit tap and the 1-week-high
     * target that fills at Trading 212. The caller holds the algo.
     *
     * @return the closed trade, or null when the paper position changed first.
     */
    fun bookPaperExit(ctx: Context, ns: String, open: StrategyLabStore.LedgerTrade,
                      exitPx: Double, nowMs: Long, reason: String): StrategyLabStore.LedgerTrade? {
        val sym = open.symbol.uppercase()
        val cfg = StrategyLabStore.activeConfig(ctx, ns)
        val closed = closedTrade(open, exitPx, nowMs / 1000L,
            cfg.slippagePct + cfg.flatFeeRoundTripPct, cfg.notional, reason)
        val audit = StrategyLabStore.Audit(id = "${open.id}-SELL", symbol = sym, action = "SELL",
            price = exitPx, reason = reason, barTs = nowMs / 1000L)
        if (!StrategyLabStore.closeByHand(ctx, ns, closed, audit)) return null
        // Credited like any other exit: the seat compounds by it and the monthly
        // reinvest layer sees it.
        try { com.signalscope.app.screener.ReinvestEngine.onTradeClosed(ctx, ns, sym, open.entryTs, closed.pctReturn) }
        catch (e: Exception) { Log.w(TAG, "reinvest close hook failed", e) }
        try { MacdGateEngine.onTradeClosed(ctx, ns, sym, closed.pctReturn) }
        catch (e: Exception) { Log.w(TAG, "gate close hook failed", e) }
        return closed
    }

    fun takeNow(ctx: Context, ns: String, symbol: String): Result {
        val sym = symbol.trim().uppercase()
        return StrategyLabRunner.withAlgoHeld(ns, 90_000L) { takeHeld(ctx, ns, sym) }
            ?: Result(false, "This algo is in the middle of its own check. Try again in a minute — nothing was sold.", sym)
    }

    private fun takeHeld(ctx: Context, ns: String, sym: String): Result {
        val open = StrategyLabStore.load(ctx, ns).trades
            .lastOrNull { it.symbol.equals(sym, true) && it.status == "OPEN" }
            ?: return Result(false, "The algo no longer holds $sym on paper.", sym)
        val base = Spaces.baseOf(ns)
        val now = System.currentTimeMillis()

        var real = false
        var broker = ""
        var exitPx = 0.0
        var note = ""

        when (base) {
            "de", "us" -> {
                val row = T212LiveStore.get(ctx, open.id, ns)
                when (row?.status) {
                    "PENDING" -> return Result(false, "The real buy for $sym is still being placed — wait for it to finish.", sym)
                    "EXITING" -> return Result(false, "A sale of $sym is already under way.", sym)
                }
                if (row != null && row.status == "OPEN" && row.qty > 0.0) {
                    if (base == "de" && !MarketHours.isRegularSession("de", now))
                        return Result(false, "The German market is shut, so the real shares cannot be sold now. Nothing was changed.", sym)
                    real = true; broker = "Trading 212"
                    val so = T212LiveMirror.stopOutNow(ctx, ns, sym, REASON,
                        origin = "your take-profit tap", paperTradeId = open.id)
                    if (!so.ok)
                        return Result(false, "Not sold at Trading 212: ${so.error}. Nothing was changed — the position is still open.", sym)
                    exitPx = so.exitPrice
                    if ((T212LiveStore.get(ctx, open.id, ns)?.liveExitPrice ?: 0.0) <= 0.0)
                        note = "The broker's exact fill arrives a few minutes later; the paper record uses the ladder's price."
                }
            }
            "in" -> {
                val row = ZerodhaLiveStore.forTrade(ctx, ns, open.id)
                if (row != null && row.isLive) {
                    if (row.status != "OPEN")
                        return Result(false, "An order for $sym is still in progress at Zerodha — try again once it settles.", sym)
                    if (!MarketHours.isRegularSession("in", now))
                        return Result(false, "The Indian market is shut, so the real shares cannot be sold now. Nothing was changed.", sym)
                    val px = MarkToMarket.priceNow(ctx, ns, sym)
                        ?: return Result(false, "No current price for $sym — nothing was sold.", sym)
                    real = true; broker = "Zerodha"
                    ZerodhaLiveMirror.mirrorExit(ctx, open.id, sym, px, ns)
                    val after = ZerodhaLiveStore.forTrade(ctx, ns, open.id)
                    when (after?.status) {
                        "CLOSED" -> exitPx = px
                        "MANUAL_CLOSED" -> {
                            exitPx = px
                            note = "Zerodha's protective trigger had already sold these shares; the paper record is closed at the current price."
                        }
                        else -> {
                            // A refused exit is normally queued to be re-sold later. Not
                            // this one: the paper stays open, so a later automatic sale
                            // would leave the two disagreeing. Tap again when it can go.
                            after?.let { ZerodhaLiveStore.upsert(ctx, it.copy(exitHeld = false, exitTries = 0)) }
                            return Result(false, "Not sold at Zerodha: ${after?.error?.ifBlank { null } ?: "the order did not go through"}. " +
                                "The paper position is still open.", sym)
                        }
                    }
                }
            }
            "binance" -> {
                val live = BinanceLiveStore.openFor(ctx, open.id, ns)
                if (live != null) {
                    val px = MarkToMarket.priceNow(ctx, ns, sym)
                    real = true; broker = "Binance"
                    val synthetic = StrategyLabEngine.Trade(
                        symbol = sym, status = "CLOSED", entryTs = open.entryTs,
                        entryPrice = open.entryPrice, entryReason = open.entryReason,
                        exitTs = now / 1000L, exitPrice = px, exitReason = REASON,
                        pctReturn = 0.0, pnlNotional = 0.0, barsHeld = open.barsHeld)
                    BinanceLiveMirror.mirrorExit(ctx, open.id, synthetic, ns)
                    val after = BinanceLiveStore.get(ctx, open.id, ns)
                    if (after == null || (after.status != "CLOSED" && after.status != "PARTIAL")) {
                        // A failed sale marks the record "error" although the coins are
                        // still held; put it back as it was, since nothing was sold.
                        val why = after?.error?.ifBlank { null } ?: "the order did not go through"
                        BinanceLiveStore.upsert(ctx, live, ns)
                        return Result(false, "Not sold at Binance: $why. Nothing was changed.", sym)
                    }
                    exitPx = after.liveAvgSellPrice.takeIf { it > 0.0 } ?: (px ?: 0.0)
                    if (after.status == "PARTIAL") note = after.error
                }
            }
        }

        // Paper only: the price now.
        if (!real) {
            exitPx = MarkToMarket.priceNow(ctx, ns, sym)
                ?: return Result(false, "No current price for $sym — nothing was changed.", sym)
            note = "Paper only — no real position stood behind this one."
        }
        if (!(exitPx > 0.0))
            return Result(false, "Sold, but no exit price could be read — the paper position is left open for you to check.", sym, real, broker)

        // ── THE ALGO'S RECORD ────────────────────────────────────────────────
        val closed = bookPaperExit(ctx, ns, open, exitPx, now, REASON)
            ?: return Result(false, "The paper position changed while this ran — check the card.", sym, real, broker)

        Log.w(TAG, "[$ns] $sym taken profit by hand at $exitPx (${"%+.2f".format(closed.pctReturn)}%) " +
            if (real) "— real sale at $broker" else "— paper only")
        return Result(true, symbol = sym, real = real, broker = broker, exitPrice = exitPx,
            pctReturn = closed.pctReturn, pnl = closed.pnlNotional, note = note)
    }
}

package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.BucketStore
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.StrategyLabStore
import com.signalscope.app.data.ZerodhaLiveStore
import com.signalscope.app.network.ZerodhaClient

/**
 * PAPER SIGNAL → REAL ZERODHA ORDER, per algo.
 *
 * The Indian counterpart of [T212LiveMirror], and deliberately NOT a copy of it.
 * The patient ladder exists because Trading 212 has no server-side conditional
 * orders: the only way to hold out for a price there is to keep replacing a
 * limit order from inside the app, which means the protection dies whenever the
 * phone does. Zerodha has GTT — a trigger that lives at the broker — so the
 * Indian design is simpler and strictly safer:
 *
 *      entry  → one plain order
 *      then   → a GTT resting at the broker to protect it
 *      exit   → delete the GTT, then sell
 *
 * The protection survives the app being closed, the phone being off, and the
 * process being killed by Doze. That is not a small difference: the ladder's
 * whole failure mode is the app not being alive at the moment it matters.
 *
 * ── FOUR GATES, same as every other live path ─────────────────────────────
 *   1. master freeze          — one switch, defaults ON, blocks everything
 *   2. Settings mirror toggle — Zerodha algo orders enabled at all
 *   3. per-algo `live` flag   — this algo specifically
 *   4. per-algo allowlist     — this NAME specifically
 * All four must agree. Any one of them off means no order, silently and safely.
 *
 * ── WHOLE SHARES ──────────────────────────────────────────────────────────
 * NSE equities cannot be traded fractionally. A budget that does not reach one
 * share buys NOTHING, and the honest response is to say so rather than round up
 * into a position the algo never sized. This is the single biggest behavioural
 * difference from the German path, where €20 always buys something.
 */
object ZerodhaLiveMirror {

    private const val TAG = "ZerodhaLiveMirror"
    private const val EXCHANGE = "NSE"

    /** Master freeze. Defaults ON and is never flipped except by the user. */
    fun isFrozen(ctx: Context): Boolean = ConfigManager(ctx).zerodhaLiveFrozen

    data class LiveCfg(val budget: Double, val allow: Set<String>, val seats: Int)

    /**
     * The per-algo half of the gate. Returns null when this algo must not trade,
     * which is the default for every algo that has not been explicitly armed.
     */
    private fun liveCfgFor(ctx: Context, ns: String): LiveCfg? {
        val b = BucketStore.get(ctx, ns) ?: return null
        if (!b.live) return null
        if (b.liveBudget <= 0.0) return null
        val allow = b.liveAllowlist.map { it.uppercase() }.toSet()
        if (allow.isEmpty()) return null              // blank = NONE, the safe default
        return LiveCfg(b.liveBudget, allow, b.holdN.coerceAtLeast(1))
    }

    private fun gatesOpen(ctx: Context, ns: String, symbol: String): LiveCfg? {
        val cfg = ConfigManager(ctx)
        if (!cfg.isZerodhaConnected) return null
        if (!cfg.zerodhaLiveMirrorEnabled) return null
        if (isFrozen(ctx)) return null
        val live = liveCfgFor(ctx, ns) ?: return null
        if (symbol.uppercase() !in live.allow) return null
        return live
    }

    /**
     * A new paper BUY became real.
     *
     * Writes a PENDING row BEFORE placing anything. If the process dies between
     * the write and the broker's reply, the row is what stops the next pass
     * placing a second order for the same signal — the alternative is a
     * duplicate position with no record of the first.
     */
    fun mirrorEntry(ctx: Context, paperTradeId: String, symbol: String,
                    paperEntryPrice: Double, ns: String) {
        val sym = symbol.trim().uppercase()
        val live = gatesOpen(ctx, ns, sym) ?: return
        if (ZerodhaLiveStore.forTrade(ctx, ns, paperTradeId) != null) return   // already mirrored

        val cfg = ConfigManager(ctx)
        val client = ZerodhaClient(cfg)
        try {
            val ltp = when (val r = client.fetchLtp(EXCHANGE, sym)) {
                is ZerodhaClient.LtpResult.Success -> r.lastPrice
                else -> 0.0
            }
            val ref = if (ltp > 0.0) ltp else paperEntryPrice
            if (ref <= 0.0) {
                Log.w(TAG, "[$ns] $sym no price — skipping entry")
                return
            }
            // WHOLE SHARES ONLY. Truncate, never round: rounding up would spend
            // more than the seat was allocated, which quietly breaks the equal
            // -seat assumption the whole 3b model rests on.
            val perSeat = live.budget / live.seats
            val qty = Math.floor(perSeat / ref).toInt()
            if (qty < 1) {
                ZerodhaLiveStore.upsert(ctx, ZerodhaLiveStore.Row(
                    ns = ns, paperTradeId = paperTradeId, symbol = sym,
                    status = "ERROR", paperEntryPrice = paperEntryPrice,
                    buyAt = System.currentTimeMillis(),
                    error = "Seat budget ${"%.0f".format(perSeat)} is under one share " +
                            "at ${"%.2f".format(ref)} — NSE has no fractional shares."))
                Log.w(TAG, "[$ns] $sym seat too small for one share")
                return
            }

            ZerodhaLiveStore.upsert(ctx, ZerodhaLiveStore.Row(
                ns = ns, paperTradeId = paperTradeId, symbol = sym,
                status = "PENDING", qty = qty, paperEntryPrice = paperEntryPrice,
                buyAt = System.currentTimeMillis()))

            when (val res = client.placeOrder(EXCHANGE, sym, "BUY", qty, "MARKET", null)) {
                is ZerodhaClient.OrderResult.Success -> {
                    ZerodhaLiveStore.upsert(ctx, ZerodhaLiveStore.Row(
                        ns = ns, paperTradeId = paperTradeId, symbol = sym,
                        status = "OPEN", qty = qty, buyOrderId = res.orderId,
                        entryPrice = ref, paperEntryPrice = paperEntryPrice,
                        buyAt = System.currentTimeMillis()))
                    Log.w(TAG, "[$ns] REAL BUY $qty $sym (order ${res.orderId})")
                    attachProtectiveGtt(ctx, ns, paperTradeId, sym, qty, ref)
                }
                is ZerodhaClient.OrderResult.Failure -> {
                    ZerodhaLiveStore.upsert(ctx, ZerodhaLiveStore.Row(
                        ns = ns, paperTradeId = paperTradeId, symbol = sym,
                        status = "ERROR", qty = qty, paperEntryPrice = paperEntryPrice,
                        buyAt = System.currentTimeMillis(), error = res.message))
                    Log.e(TAG, "[$ns] BUY $sym refused: ${res.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$ns] mirrorEntry $sym failed", e)
        }
    }

    /**
     * The protective trigger. Placed AFTER the buy, and its failure is not fatal
     * to the position — an unprotected holding is worse than a protected one but
     * far better than one whose entry was rolled back on a secondary error.
     *
     * Uses the same planner as the India portfolio's own OCO placement, so an
     * algo position and a hand-placed one are protected by identical rules
     * rather than two drifting copies of the same idea.
     */
    private fun attachProtectiveGtt(ctx: Context, ns: String, paperTradeId: String,
                                    sym: String, qty: Int, entry: Double) {
        try {
            val cfg = ConfigManager(ctx)
            val client = ZerodhaClient(cfg)
            val plan = OcoPlanner.planSellOco(cfg, OcoPlanner.Input(
                ltp = entry, support = 0.0, resistance = 0.0,
                atr = entry * 0.02, avgPrice = entry,
                tickSize = 0.05, broker = OcoPlanner.Broker.ZERODHA))
            if (!plan.ok) return
            val res = client.createGttTrigger(
                type = "two-leg", exchange = EXCHANGE, tradingsymbol = sym,
                triggerValues = listOf(plan.sl, plan.target), lastPrice = entry,
                orders = listOf(
                    ZerodhaClient.GttOrderSpec("SELL", qty, plan.slLimit),
                    ZerodhaClient.GttOrderSpec("SELL", qty, plan.targetLimit)))
            if (res is ZerodhaClient.GttCreateResult.Success) {
                ZerodhaLiveStore.forTrade(ctx, ns, paperTradeId)?.let {
                    ZerodhaLiveStore.upsert(ctx, it.copy(gttId = res.id))
                }
                Log.w(TAG, "[$ns] GTT ${res.id} protecting $qty $sym " +
                    "(SL ${"%.2f".format(plan.sl)} / TGT ${"%.2f".format(plan.target)})")
            } else {
                Log.w(TAG, "[$ns] GTT for $sym not created — position is unprotected")
            }
        } catch (e: Exception) { Log.w(TAG, "GTT attach failed for $sym", e) }
    }

    /**
     * The strategy decided to exit.
     *
     * THE GTT IS DELETED FIRST, AND THE SELL ONLY HAPPENS IF THAT SUCCEEDS. A
     * resting trigger plus an algo sell is two sell orders against one holding:
     * the second fills against nothing and opens an accidental short. If the
     * delete fails, the safe move is to leave the position alone and say so —
     * the trigger is still protecting it, so nothing is at risk except tidiness.
     */
    fun mirrorExit(ctx: Context, paperTradeId: String, symbol: String,
                   paperExitPrice: Double, ns: String) {
        val sym = symbol.trim().uppercase()
        val row = ZerodhaLiveStore.forTrade(ctx, ns, paperTradeId) ?: return
        if (row.status != "OPEN") return
        if (row.isManual) return
        val cfg = ConfigManager(ctx)
        if (!cfg.isZerodhaConnected) return
        // NOTE: an exit is deliberately NOT gated on the freeze or the allowlist.
        // Those govern taking NEW risk. Blocking an exit would strand a real
        // position with no way out the moment the user hits the safety switch.

        val client = ZerodhaClient(cfg)
        try {
            row.gttId?.let { id ->
                val del = client.deleteGttTrigger(id)
                if (del !is ZerodhaClient.GttDeleteResult.Success) {
                    ZerodhaLiveStore.upsert(ctx, row.copy(
                        error = "Exit held back: could not cancel GTT $id, " +
                                "selling now would double up. The trigger still protects it."))
                    Log.e(TAG, "[$ns] GTT $id delete failed — exit aborted for $sym")
                    return
                }
            }
            // Never sell more than this algo owns: one account, several algos,
            // one aggregate holding at the broker.
            val others = ZerodhaLiveStore.claimedByOthers(ctx, ns, sym)
            val qty = row.qty
            if (qty < 1) return
            ZerodhaLiveStore.upsert(ctx, row.copy(status = "EXITING", gttId = null))

            when (val res = client.placeOrder(EXCHANGE, sym, "SELL", qty, "MARKET", null)) {
                is ZerodhaClient.OrderResult.Success -> {
                    ZerodhaLiveStore.upsert(ctx, row.copy(
                        status = "CLOSED", gttId = null, sellOrderId = res.orderId,
                        exitPrice = paperExitPrice, paperExitPrice = paperExitPrice,
                        sellAt = System.currentTimeMillis(), error = ""))
                    Log.w(TAG, "[$ns] REAL SELL $qty $sym (order ${res.orderId})" +
                        if (others > 0) " · $others left for other algos" else "")
                }
                is ZerodhaClient.OrderResult.Failure -> {
                    // Back to OPEN, not ERROR: the shares are still held, and the
                    // next pass should be free to try the exit again.
                    ZerodhaLiveStore.upsert(ctx, row.copy(
                        status = "OPEN", error = "Exit refused: ${res.message}"))
                    Log.e(TAG, "[$ns] SELL $sym refused: ${res.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$ns] mirrorExit $sym failed", e)
        }
    }

    /** Armed algos in the India space, for the gates panel. */
    fun armedAlgos(ctx: Context): List<Pair<String, String>> = try {
        BucketStore.list(ctx, "in")
            .filter { it.live && it.liveBudget > 0.0 && it.liveAllowlist.isNotEmpty() }
            .map { it.ns to it.name }
    } catch (e: Exception) { emptyList() }
}

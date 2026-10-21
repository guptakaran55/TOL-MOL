package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.T212LiveStore
import com.signalscope.app.network.YahooFinanceClient

/**
 * MANUALLY FIRE ONE REAL TRADE THROUGH THE ALGO'S OWN EXECUTION PATH.
 *
 * Before any algo is allowed to spend real money, four links in the chain have to
 * be proven, and none of them can be proven on paper:
 *
 *   1. the app can turn a symbol into a real T212 order that actually fills
 *   2. the fill comes back through the history endpoint and is READ correctly
 *   3. the weighted average across the ladder's rungs is right
 *   4. the app's ledger and T212's holdings agree afterwards, in size
 *
 * A test bench trade is a REAL trade with real money — the whole point is that
 * nothing is simulated. What makes it safe is that it is small, manual, one at a
 * time, and it writes into its own namespace so it can never be mistaken for an
 * algo position, counted in an algo's P&L, or closed by a strategy exit.
 *
 * It deliberately reuses [PatientExecutor] rather than a simplified copy: a bench
 * that tests a different code path from the one the algos use proves nothing.
 */
object LiveTestBench {
    private const val TAG = "LiveTestBench"

    /** Bench rows live here. Never a real algo namespace, so no strategy can see
     *  them and the position audit still covers them (it reads every OPEN row). */
    const val NS = "__testbench"

    /** Hard ceiling per bench order. The bench exists to prove plumbing, not to
     *  take a position; a fat-fingered amount here would be a real loss for no
     *  informational gain. */
    private const val MAX_AMOUNT = 100.0

    data class Outcome(
        val ok: Boolean,
        val stage: String,
        val error: String = "",
        val ticker: String = "",
        val symbol: String = "",
        val side: String = "",
        val refPrice: Double = 0.0,       // ladder anchor at order time
        val quotePrice: Double = 0.0,     // Yahoo quote when we started
        val filled: Double = 0.0,
        val remaining: Double = 0.0,
        val orderIds: List<Long> = emptyList(),
        val atr: Double = 0.0,
        val note: String = "",
        val paperTradeId: String = "",
        /** Where the signal price came from. A ladder anchored on yesterday's close
         *  behaves very differently from one anchored on a live tick, and reading a
         *  deviation without knowing which would be misleading. */
        val anchorSource: String = ""
    )

    /**
     * The EXCHANGE symbol, given either form of input.
     *
     * A T212 internal ticker is not a Yahoo symbol and must not be treated as one:
     * `SRT3d_EQ` fed to toYahooSymbol produces `SRT3D_EQ.DE`, which no feed knows,
     * and the bench then refuses with "no live price" for a name that quotes fine.
     * German T212 tickers carry a lowercase venue letter before `_EQ`; US ones
     * carry `_US`. Strip whichever is present.
     */
    private fun plainSymbolOf(input: String, space: String): String {
        val raw = input.trim()
        if (!raw.contains("_")) return raw.uppercase()
        val head = raw.substringBefore("_")
        return if (space.equals("DE", true) && head.length > 1 && head.last() == 'd')
            head.dropLast(1).uppercase() else head.uppercase()
    }

    data class Anchor(val price: Double, val source: String)

    /**
     * The "signal price" for the bench — the analogue of an algo's signal-bar close.
     *
     * Falls through three sources rather than demanding the first. Yahoo's intraday
     * coverage of Xetra is patchy, and refusing on that basis blocked a symbol the
     * ladder could have handled perfectly: [PatientExecutor] re-anchors internally
     * (Tradegate via ISIN for DE) and only uses this as its starting reference.
     * Refusing when a usable reference existed was the bug, not a safety feature.
     *
     * The source travels with the price so the bench can say what it anchored on —
     * a last-daily-close anchor on a fast-moving name is a fact worth seeing.
     */
    private fun anchorOf(plainSym: String, space: String): Anchor {
        val ex = if (space.equals("DE", true)) "DE" else "US"
        val ys = YahooFinanceClient.toYahooSymbol(plainSym, ex)
        try {
            YahooFinanceClient.fetchLivePrice(ys)?.let { if (it > 0.0) return Anchor(it, "live quote") }
        } catch (e: Exception) { Log.w(TAG, "live price failed for $ys", e) }
        val mark = MarkToMarket.get(plainSym)?.price ?: 0.0
        if (mark > 0.0) return Anchor(mark, "last mark")
        // Last resort: the most recent daily close. Enough to open the ladder, which
        // then re-anchors on a fresh quote at every rung anyway.
        try {
            val c =


                YahooFinanceClient.fetchDailyAdjusted(plainSym, ex)
            if (c is YahooFinanceClient.CandleResult.Success) {
                val close = c.candles.lastOrNull()?.close ?: 0.0
                if (close > 0.0) return Anchor(close, "last daily close")
            }
        } catch (e: Exception) { Log.w(TAG, "daily fallback failed for $plainSym", e) }
        return Anchor(0.0, "none")
    }

    /**
     * BUY a small real position through the patient ladder and record it as a
     * bench row. Blocking and slow by nature — a six-rung ladder dwells per rung —
     * so it must be called off the main thread.
     */
    fun buy(ctx: Context, symbol: String, space: String, amount: Double,
            onProgress: ((Map<String, Any?>) -> Unit)? = null): Outcome {
        val raw = symbol.trim()
        if (raw.isBlank()) return Outcome(false, "input", "Enter a symbol.")
        if (amount <= 0.0) return Outcome(false, "input", "Enter an amount greater than 0.")
        if (amount > MAX_AMOUNT)
            return Outcome(false, "input",
                "The bench is capped at ${"%.0f".format(MAX_AMOUNT)} per order — it is here to prove the plumbing, not to take a position.")
        val config = ConfigManager(ctx)
        if (!config.hasT212Credentials) return Outcome(false, "input", "No T212 API key saved.")

        // CASE MATTERS on a T212 ticker: German instruments are "SRT3d_EQ" with a
        // lowercase venue letter, and uppercasing the input turned it into a symbol
        // the broker does not have. Pass the ORIGINAL string to the executor (which
        // detects the "_" form itself) and the derived plain symbol to the feeds.
        val sym = plainSymbolOf(raw, space)
        val orderTarget = if (raw.contains("_")) raw else sym

        val a = anchorOf(sym, space)
        if (a.price <= 0.0)
            return Outcome(false, "quote",
                "No price at all for $sym — not a live quote, not a mark, not even a daily close. " +
                "Check the symbol: type the exchange ticker as it appears in the algo's universe (e.g. SRT3), not the T212 internal id.")

        // T212's floor is €1.00 of VALUE, so any bench order (minimum a few euro)
        // clears it comfortably, and so does whatever it leaves a sibling holding.
        // No entry guard is warranted: an earlier version refused when the order was
        // under 1.00 SHARES, which at €55 a share meant refusing every €20 buy for a
        // rule that does not exist. The real constraint is dust at EXIT time, and
        // PatientExecutor checks that where the price and the pool are both known.
        val exitCaveat = ""
        val ys = YahooFinanceClient.toYahooSymbol(sym, if (space.equals("DE", true)) "DE" else "US")
        val r = PatientExecutor.buy(config, orderTarget, ys, space, amount, a.price,
            onProgress = onProgress)
        val quote = a.price
        if (!r.ok || r.filled <= 0.0) {
            return Outcome(false, "execute", r.error ?: (r.note.ifBlank { "nothing filled" }),
                ticker = r.ticker, symbol = sym, side = "BUY",
                refPrice = r.refPrice, quotePrice = quote, filled = r.filled,
                remaining = r.remaining, orderIds = r.orderIds, atr = r.atr, note = r.note,
                anchorSource = a.source)
        }

        // Recorded with the QUOTE as the paper price. That is the honest analogue
        // of an algo's signal-bar close: the number the decision was made on,
        // before any execution happened. Every deviation the app reports is
        // measured from it.
        val id = "BENCH-$sym-${System.currentTimeMillis()}"
        T212LiveStore.upsert(ctx, T212LiveStore.LiveTrade(
            paperTradeId = id, ns = NS, symbol = sym, t212Ticker = r.ticker,
            status = "OPEN", entryTs = System.currentTimeMillis() / 1000L,
            paperEntryPrice = quote, amount = amount, qty = r.filled,
            buyOrderId = r.orderIds.firstOrNull() ?: 0L, buyAt = System.currentTimeMillis(),
            buyOrderIds = r.orderIds, refEntryPrice = r.refPrice))
        Log.i(TAG, "bench BUY $sym filled ${r.filled} over ${r.orderIds.size} rung(s), anchor ${r.refPrice}")
        return Outcome(true, "done", ticker = r.ticker, symbol = sym, side = "BUY",
            refPrice = r.refPrice, quotePrice = quote, filled = r.filled,
            remaining = r.remaining, orderIds = r.orderIds, atr = r.atr,
            note = r.note + exitCaveat, paperTradeId = id, anchorSource = a.source)
    }

    /** SELL an open bench row back out, completing the round trip so the
     *  assumed-vs-real deviation panel gains a real sample. */
    fun sell(ctx: Context, paperTradeId: String, space: String,
             onProgress: ((Map<String, Any?>) -> Unit)? = null): Outcome {
        val row = T212LiveStore.get(ctx, paperTradeId, NS)
            ?: return Outcome(false, "input", "No open bench trade with that id.")
        if (row.status != "OPEN") return Outcome(false, "input", "That bench trade is already closed.")
        val config = ConfigManager(ctx)
        if (!config.hasT212Credentials) return Outcome(false, "input", "No T212 API key saved.")

        val a = anchorOf(row.symbol, space)
        if (a.price <= 0.0) return Outcome(false, "quote", "No price for ${row.symbol} to anchor the sell.")
        val quote = a.price

        // Sell what actually FILLED, never what was ordered — the difference is
        // exactly the bug this bench exists to catch.
        val qty = if (row.liveQtyFilled > 0.0) row.liveQtyFilled else row.qty
        val others = T212LiveStore.otherClaimsQty(ctx, row.t212Ticker, NS, paperTradeId)
        if (others > 0.0)
            Log.i(TAG, "bench SELL ${row.symbol}: selling only our $qty — $others claimed by other rows")
        val ys = YahooFinanceClient.toYahooSymbol(row.symbol, if (space.equals("DE", true)) "DE" else "US")
        // targetQty BOUNDS the sale to this row's own shares. Without it the ladder
        // liquidates the pooled T212 position, taking manual holdings and other
        // algos' shares with it.
        val r = PatientExecutor.sell(config, row.t212Ticker, ys, space, qty, quote, onProgress,
            targetQty = qty, claimedByOthers = others)
        if (!r.ok || r.filled <= 0.0) {
            return Outcome(false, "execute", r.error ?: (r.note.ifBlank { "nothing filled" }),
                ticker = row.t212Ticker, symbol = row.symbol, side = "SELL",
                refPrice = r.refPrice, quotePrice = quote, filled = r.filled,
                remaining = r.remaining, orderIds = r.orderIds, atr = r.atr, note = r.note,
                anchorSource = a.source)
        }
        T212LiveStore.upsert(ctx, row.copy(
            status = "CLOSED", exitTs = System.currentTimeMillis() / 1000L,
            paperExitPrice = quote, sellOrderId = r.orderIds.firstOrNull() ?: 0L,
            sellAt = System.currentTimeMillis(), sellOrderIds = r.orderIds,
            refExitPrice = r.refPrice, sweptQty = r.sweptQty, error = ""))
        Log.i(TAG, "bench SELL ${row.symbol} filled ${r.filled} over ${r.orderIds.size} rung(s)")
        return Outcome(true, "done", ticker = row.t212Ticker, symbol = row.symbol, side = "SELL",
            refPrice = r.refPrice, quotePrice = quote, filled = r.filled,
            remaining = r.remaining, orderIds = r.orderIds, atr = r.atr,
            note = r.note, paperTradeId = paperTradeId, anchorSource = a.source)
    }

    fun rows(ctx: Context): List<T212LiveStore.LiveTrade> =
        T212LiveStore.load(ctx).trades.filter { it.ns == NS }.sortedByDescending { it.entryTs }
}

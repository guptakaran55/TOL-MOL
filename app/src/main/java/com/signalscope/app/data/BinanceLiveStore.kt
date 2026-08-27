package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

/**
 * Live Binance Spot mirror ledger.
 *
 * This is deliberately separate from StrategyLabStore: paper remains the source
 * of signal truth, while this store records which paper decisions were mirrored
 * into live Binance orders and how those live fills differed.
 */
object BinanceLiveStore {
    private const val TAG = "BinanceLiveStore"
    private val gson = Gson()

    data class LiveTrade(
        val paperTradeId: String,
        val symbol: String,
        val status: String,                  // OPEN / CLOSED / PARTIAL / UNKNOWN / ERROR
        val entryTs: Long,
        val paperEntryPrice: Double,
        val quoteOrderQty: Double,
        val buyOrderId: Long = 0L,
        val buyClientOrderId: String = "",
        val buyAt: Long = 0L,
        val executedQty: String = "0",
        val buyQuoteQty: String = "0",
        val liveAvgBuyPrice: Double = 0.0,
        val exitTs: Long? = null,
        val paperExitPrice: Double? = null,
        val sellOrderId: Long = 0L,
        val sellClientOrderId: String = "",
        val sellAt: Long = 0L,
        val sellExecutedQty: String = "0",
        val sellQuoteQty: String = "0",
        val liveAvgSellPrice: Double = 0.0,
        val livePnlQuote: Double = 0.0,
        val error: String = ""
    )

    data class State(
        val liveEnabledSeenAt: Long = 0L,
        val trades: List<LiveTrade> = emptyList(),
        // Convergence audit: coins actually held on Binance that neither an open
        // live record nor the strategy explains (e.g. manual buys). FLAGGED in
        // the UI, never auto-sold — they may be deliberate manual holdings.
        val unmanaged: List<String> = emptyList()
    )

    // Each bucket gets its own live ledger so real crypto positions never smear
    // across strategies. The bare "binance" ns keeps the ORIGINAL filename, so
    // the existing single-path ledger (and its real trade history) is untouched.
    fun setUnmanaged(ctx: Context, lines: List<String>, ns: String = "binance") {
        val s = load(ctx, ns)
        if (s.unmanaged == lines) return
        save(ctx, s.copy(unmanaged = lines), ns)
    }

    private fun file(ctx: Context, ns: String): File =
        File(ctx.filesDir, if (ns == "binance") "binance_live_mirror.json"
                           else "binance_live_mirror_${ns}.json")

    fun load(ctx: Context, ns: String = "binance"): State {
        return try {
            val f = file(ctx, ns)
            if (!f.exists()) State() else gson.fromJson(f.readText(), State::class.java) ?: State()
        } catch (e: Exception) {
            Log.e(TAG, "load($ns) failed — returning empty", e)
            State()
        }
    }

    @Synchronized
    private fun save(ctx: Context, state: State, ns: String) {
        try {
            val f = file(ctx, ns)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(gson.toJson(state))
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (e: Exception) {
            Log.e(TAG, "save($ns) failed", e)
        }
    }

    @Synchronized
    fun upsert(ctx: Context, trade: LiveTrade, ns: String = "binance", maxTrades: Int = 500): State {
        val s = load(ctx, ns)
        val merged = (s.trades.filterNot { it.paperTradeId == trade.paperTradeId } + trade)
            .sortedBy { it.entryTs }
            .takeLast(maxTrades)
        val next = s.copy(liveEnabledSeenAt = System.currentTimeMillis(), trades = merged)
        save(ctx, next, ns)
        return next
    }

    @Synchronized
    fun markError(ctx: Context, paperTradeId: String, symbol: String, entryTs: Long, message: String, ns: String = "binance"): State {
        val current = load(ctx, ns).trades.firstOrNull { it.paperTradeId == paperTradeId }
        val trade = current?.copy(status = "ERROR", error = message)
            ?: LiveTrade(
                paperTradeId = paperTradeId,
                symbol = symbol.uppercase(),
                status = "ERROR",
                entryTs = entryTs,
                paperEntryPrice = 0.0,
                quoteOrderQty = 0.0,
                error = message
            )
        return upsert(ctx, trade, ns)
    }

    fun get(ctx: Context, paperTradeId: String, ns: String = "binance"): LiveTrade? =
        load(ctx, ns).trades.firstOrNull { it.paperTradeId == paperTradeId }

    fun openFor(ctx: Context, paperTradeId: String, ns: String = "binance"): LiveTrade? =
        get(ctx, paperTradeId, ns)?.takeIf {
            it.status == "OPEN" && ((it.executedQty.toDoubleOrNull() ?: 0.0) > 0.0)
        }

    fun reset(ctx: Context, ns: String = "binance"): State {
        val s = State()
        save(ctx, s, ns)
        return s
    }

    /**
     * Drop ERROR records that never resulted in real coins — i.e. a buy that was
     * rejected (insufficient balance, blocked symbol, …) so nothing was bought.
     *
     * SAFETY: a failed *sell* also flips an OPEN record to ERROR, and that record
     * still represents coins genuinely held on the exchange. Deleting it would
     * lose track of a real position, so anything with a non-zero executedQty is
     * deliberately KEPT regardless of status. Returns the number removed.
     */
    fun clearFailedEntries(ctx: Context, ns: String = "binance"): Int {
        val s = load(ctx, ns)
        val keep = s.trades.filter { t ->
            val filled = (t.executedQty.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO) > java.math.BigDecimal.ZERO
            t.status != "ERROR" || filled
        }
        val removed = s.trades.size - keep.size
        if (removed > 0) save(ctx, s.copy(trades = keep), ns)
        return removed
    }
}

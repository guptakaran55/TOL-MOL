package com.signalscope.app.trading

import android.content.Context
import com.signalscope.app.data.BinanceLiveStore
import com.signalscope.app.network.BinanceClient
import java.time.Instant

// ════════════════════════════════════════════════════════════════════════════
// WHAT THE BINANCE ACCOUNT ACTUALLY MADE — the algos' real round trips
// ════════════════════════════════════════════════════════════════════════════
// The same closed-trades table the stock spaces have, for crypto. Built from the
// algos' own records of their real Binance orders — every algo, including ones
// since deleted, because a trade that happened stays real after its algo goes.
//
// THE FEE IS ALREADY IN THE RESULT. Binance takes its fee out of the coins on a
// buy and out of the proceeds on a sale, and the record keeps what actually
// arrived — so what came back minus what went in is the money made AFTER fees.
// The fee itself was not kept, so it is shown at Binance's standard spot rate
// and the gross figure is worked back from it. The net is exact; the split is
// an estimate, and the panel says so.
// ════════════════════════════════════════════════════════════════════════════

object BinanceClosedTrades {

    /** Every algo namespace that has ever placed a real Binance order. */
    private fun namespaces(ctx: Context): List<String> {
        val names = ctx.filesDir.list().orEmpty()
        val out = ArrayList<String>()
        if ("binance_live_mirror.json" in names) out.add("binance")
        names.filter { it.startsWith("binance_live_mirror_") && it.endsWith(".json") }
            .forEach { out.add(it.removePrefix("binance_live_mirror_").removeSuffix(".json")) }
        return out.distinct()
    }

    private fun iso(ms: Long): String = if (ms > 0L) Instant.ofEpochMilli(ms).toString() else ""

    fun report(ctx: Context): ClosedTrades.Report {
        val fee = TradingCosts.BINANCE_FEE_PCT_PER_SIDE_ESTIMATE / 100.0
        val trips = ArrayList<ClosedTrades.RoundTrip>()
        val stillOpen = HashMap<String, Double>()
        var quote = ""
        for (ns in namespaces(ctx)) {
            for (t in BinanceLiveStore.load(ctx, ns).trades) {
                val sym = t.symbol.uppercase()
                val base = BinanceClient.baseAssetOf(sym) ?: sym
                if (t.status == "OPEN") {
                    stillOpen[base] = (stillOpen[base] ?: 0.0) + (t.executedQty.toDoubleOrNull() ?: 0.0)
                    continue
                }
                if (t.status != "CLOSED" && t.status != "PARTIAL") continue
                if (t.sellAt <= 0L) continue
                val cashOut = t.sellQuoteQty.toDoubleOrNull() ?: 0.0          // net of the sell fee
                if (cashOut <= 0.0) continue
                // The cost the sale is measured against, exactly as the mirror
                // booked it (prorated on a partial close).
                val cashIn = cashOut - t.livePnlQuote
                if (cashIn <= 0.0) continue
                if (quote.isEmpty()) quote = BinanceClient.quoteAssetOf(sym) ?: ""
                val buyFee = cashIn * fee
                val sellFee = cashOut / (1.0 - fee) * fee
                val charges = buyFee + sellFee
                val gross = (cashOut - cashIn) + charges
                val bDay = iso(t.buyAt).take(10); val sDay = iso(t.sellAt).take(10)
                val held = try {
                    java.time.temporal.ChronoUnit.DAYS.between(
                        java.time.LocalDate.parse(bDay), java.time.LocalDate.parse(sDay)).toInt()
                } catch (e: Exception) { 0 }
                trips.add(ClosedTrades.RoundTrip(
                    ticker = sym, symbol = base,
                    qty = t.sellExecutedQty.toDoubleOrNull() ?: 0.0,
                    buyPrice = t.liveAvgBuyPrice, sellPrice = t.liveAvgSellPrice,
                    boughtAt = iso(t.buyAt), soldAt = iso(t.sellAt),
                    cashIn = cashIn, cashOut = cashOut,
                    pnl = gross, pctReturn = gross / cashIn * 100.0,
                    heldDays = held.coerceAtLeast(0),
                    charges = charges,
                    chargeParts = mapOf("Binance fee (buy)" to buyFee, "Binance fee (sale)" to sellFee),
                    sameDay = bDay.isNotEmpty() && bDay == sDay))
            }
        }
        val sorted = trips.sortedByDescending { it.soldAt }
        return ClosedTrades.Report(
            ok = true, checkedAt = System.currentTimeMillis(), fillsSeen = trips.size * 2,
            trips = sorted, stillOpen = stillOpen,
            totalPnl = trips.sumOf { it.pnl },
            wins = trips.count { it.netPnl > 0.0 }, losses = trips.count { it.netPnl < 0.0 },
            totalCharges = trips.sumOf { it.charges }, costed = true,
            currency = quote.ifBlank { "USDC" },
            firstFillAt = sorted.lastOrNull()?.boughtAt ?: "",
            lastFillAt = sorted.firstOrNull()?.soldAt ?: "",
            error = if (trips.isEmpty()) "No real Binance trades have closed yet." else "")
    }
}

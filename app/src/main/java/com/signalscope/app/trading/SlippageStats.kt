package com.signalscope.app.trading

/**
 * PAPER-vs-REAL execution drag ("slippage") — the single number that decides
 * whether a paper edge survives real fills.
 *
 * The lab's paper ledger assumes it filled at the signal-bar price. The live
 * mirror records what the exchange ACTUALLY filled at. The gap between them —
 * plus fees — is what real trading costs beyond the simulation. This was
 * derivable from stored data (paper price + live fill price sat side by side
 * in the live ledger) but was never turned into a metric; here it is.
 *
 * Sign convention: POSITIVE = worse for you (paid more on entry, received less
 * on exit, real P&L below paper P&L). One shared computation so every space
 * reports it identically — Binance has real fills today; T212 is frozen, so it
 * reports zero samples until live entries are unfrozen.
 */
object SlippageStats {

    /** One closed live trade's paper/real price pair. Prices in the instrument's
     *  quote currency; [quoteSpent] is the cash actually deployed on the buy. */
    data class Sample(
        val paperEntry: Double,
        val liveEntry: Double,
        val paperExit: Double,
        val liveExit: Double,
        val quoteSpent: Double,
        val livePnlQuote: Double
    )

    data class Result(
        val samples: Int = 0,
        val avgEntrySlipBps: Double = 0.0,   // (liveEntry−paperEntry)/paperEntry, +=paid more
        val avgExitSlipBps: Double = 0.0,    // (paperExit−liveExit)/paperExit, +=sold lower
        val totalDragQuote: Double = 0.0,    // Σ(paperPnl − livePnl), + = real underperformed
        val avgPaperPctPerTrade: Double = 0.0,
        val avgLivePctPerTrade: Double = 0.0,
        val avgDragPctPerTrade: Double = 0.0 // paper% − live%, the headline cost/trade
    )

    fun compute(samples: List<Sample>): Result {
        val valid = samples.filter {
            it.paperEntry > 0.0 && it.liveEntry > 0.0 &&
            it.paperExit > 0.0 && it.liveExit > 0.0 && it.quoteSpent > 0.0
        }
        if (valid.isEmpty()) return Result()
        val n = valid.size.toDouble()
        var entryBps = 0.0; var exitBps = 0.0
        var drag = 0.0; var paperPct = 0.0; var livePct = 0.0; var dragPct = 0.0
        for (s in valid) {
            entryBps += (s.liveEntry - s.paperEntry) / s.paperEntry * 10_000.0
            exitBps += (s.paperExit - s.liveExit) / s.paperExit * 10_000.0
            // Idealized paper P&L on the same cash the live buy deployed.
            val paperPnl = s.quoteSpent * (s.paperExit / s.paperEntry - 1.0)
            drag += paperPnl - s.livePnlQuote
            val pPct = (s.paperExit / s.paperEntry - 1.0) * 100.0
            val lPct = s.livePnlQuote / s.quoteSpent * 100.0
            paperPct += pPct; livePct += lPct; dragPct += (pPct - lPct)
        }
        return Result(
            samples = valid.size,
            avgEntrySlipBps = entryBps / n,
            avgExitSlipBps = exitBps / n,
            totalDragQuote = drag,
            avgPaperPctPerTrade = paperPct / n,
            avgLivePctPerTrade = livePct / n,
            avgDragPctPerTrade = dragPct / n
        )
    }

    /** Serializable map for the bridge state JSON. */
    fun toMap(r: Result): Map<String, Any?> = mapOf(
        "samples" to r.samples,
        "avgEntrySlipBps" to r.avgEntrySlipBps,
        "avgExitSlipBps" to r.avgExitSlipBps,
        "totalDragQuote" to r.totalDragQuote,
        "avgPaperPctPerTrade" to r.avgPaperPctPerTrade,
        "avgLivePctPerTrade" to r.avgLivePctPerTrade,
        "avgDragPctPerTrade" to r.avgDragPctPerTrade
    )
}

package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.signalscope.app.data.MacdGateStore
import com.signalscope.app.data.ManualWatchStore
import com.signalscope.app.data.StrategyLabStore
import com.signalscope.app.data.T212LiveStore
import java.io.File

/**
 * UNFULFILLED — algo buys the real account could not make, and what they would
 * have made.
 *
 * An algo that places real orders buys on paper first and then at Trading 212.
 * When the real buy fails for good — the price ran past its ceiling before it
 * could fill, fell under its floor, the broker refused, the attempts ran out —
 * the paper position used to stay, and in an algo with a fixed number of seats it
 * kept one taken. ADN1 and NEM held two of B_Conservative's five seats from 15 to
 * 19 Oct with nothing behind them in the account, so two fifths of the money sat
 * idle while the next-ranked names waited. The user's rule (19 Oct 2026): such a
 * trade leaves the book and its seat goes on at once.
 *
 * It is not forgotten. Each one is written here with the reason it could not
 * enter, and followed on with the algo's own rules — entry where the paper took
 * it, the strategy's exits — so the record shows what the miss cost (or saved):
 * "+2.8% so far, still open", or "+5.4%, closed 22 Oct · MACD slope flipped".
 *
 * Only algos with seats lose the paper position: in one without, a paper trade
 * blocks nothing, so it stays in the book and is listed here all the same.
 * The watchlist's own buys are left out — no seat is involved.
 */
object UnfulfilledEntries {
    private const val TAG = "Unfulfilled"
    private const val FILE = "unfulfilled_entries.json"
    /** Failed buys older than this when first seen are not picked up (the first run reaches 30 days back). */
    private const val WINDOW_MS = 30L * 24 * 3_600_000L
    /** A failure this fresh may still be followed by a fill being reconciled: wait. */
    private const val SETTLE_MS = 20 * 60_000L
    /** How often a still-open missed trade is followed up. */
    private const val RECHECK_MS = 60 * 60_000L
    /** How long a finished record is kept. */
    private const val KEEP_MS = 180L * 24 * 3_600_000L

    data class Row(
        val key: String,
        val ns: String,
        val symbol: String,
        val ticker: String = "",
        val paperTradeId: String,
        /** The bar the algo bought on, in seconds, and the price it took. */
        val entryTs: Long,
        val signalPrice: Double,
        /** The money the real buy was for. */
        val amount: Double = 0.0,
        /** When the real buy was attempted. */
        val decidedAt: Long,
        /** Why it could not enter, as the order record has it. */
        val reason: String,
        val recordedAt: Long,
        /** What happened to the paper position: dropped, already closed, kept, or already gone. */
        val paperWas: String = "",
        val seatTo: String? = null,
        // ── what it would have made, by the algo's own rules ──
        /** PENDING (not looked at yet), OPEN (would still be held), CLOSED, or UNKNOWN. */
        val shadowStatus: String = "PENDING",
        val shadowPct: Double? = null,
        val shadowExitTs: Long = 0L,
        val shadowExitPrice: Double = 0.0,
        val shadowExitReason: String = "",
        val shadowNote: String = "",
        val checkedAt: Long = 0L
    )

    private data class Book(val rows: List<Row> = emptyList())
    private val gson = Gson()

    @Synchronized
    fun all(ctx: Context): List<Row> = try {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) emptyList() else gson.fromJson(f.readText(), Book::class.java)?.rows.orEmpty()
    } catch (e: Exception) { Log.w(TAG, "unreadable", e); emptyList() }

    @Synchronized
    private fun save(ctx: Context, rows: List<Row>) {
        val now = System.currentTimeMillis()
        val keep = rows.filter { it.shadowStatus != "CLOSED" || now - it.recordedAt < KEEP_MS }
        try { File(ctx.filesDir, FILE).writeText(gson.toJson(Book(keep))) }
        catch (e: Exception) { Log.w(TAG, "save failed", e) }
    }

    @Synchronized
    private fun upsert(ctx: Context, row: Row) = save(ctx, all(ctx).filterNot { it.key == row.key } + row)

    /** This algo's unfulfilled buys, newest first. */
    fun forNs(ctx: Context, ns: String): List<Row> = all(ctx).filter { it.ns == ns }.sortedByDescending { it.decidedAt }

    private fun isWatcher(ns: String) = Spaces.bucketIdOf(ns) == ManualWatchStore.ID

    /**
     * Algo buys whose real order failed for good and that are not listed yet: a
     * finished failure (not still being retried), no share ever bought, attempted
     * at least [SETTLE_MS] ago and within [WINDOW_MS]. The watchlist's buys are
     * left out. Pure — tested.
     */
    internal fun failedForGood(trades: List<T212LiveStore.LiveTrade>, now: Long, have: Set<String>) =
        trades.filter { r ->
            !isWatcher(r.ns) && r.status == "ERROR" && r.liveQtyFilled <= 0.0 && r.qty <= 0.0 &&
                r.buyAt > 0L && now - r.buyAt >= SETTLE_MS && now - r.buyAt <= WINDOW_MS &&
                "${r.ns}|${r.paperTradeId}" !in have
        }

    /** The bar a paper trade id ("ADN1-1792072800") was bought on. */
    internal fun entryTsOf(paperTradeId: String): Long =
        paperTradeId.substringAfterLast('-').toLongOrNull() ?: 0L

    /**
     * Each pass: pick up real buys that failed for good, take them out of the
     * book (seat algos only) with the seat handed on, and follow up what the
     * ones already listed would have made. Returns a few words for the log.
     */
    fun sweep(ctx: Context): String {
        val now = System.currentTimeMillis()
        val have = all(ctx).map { it.key }.toHashSet()
        val failed = failedForGood(T212LiveStore.load(ctx).trades, now, have)
        var moved = 0
        if (failed.isNotEmpty()) {
            // Shares of the name in the account that no other position claims mean
            // a buy may have filled after all — such a row is left alone.
            val holdings = (BrokerHoldings.t212(ctx) as? BrokerHoldings.Result.Ok)?.holdings
            if (holdings != null) for (r in failed) {
                val held = holdings.filter { it.ticker.equals(r.t212Ticker, true) }.sumOf { it.qty }
                val claimed = try { T212LiveStore.otherClaimsQty(ctx, r.t212Ticker, r.ns, r.paperTradeId) } catch (e: Exception) { 0.0 }
                if (held - claimed > 1e-6) {
                    Log.w(TAG, "[${r.ns}] ${r.symbol}: the buy is marked failed but the account holds shares — left alone")
                    continue
                }
                val row = record(ctx, r, now) ?: continue
                upsert(ctx, row)
                moved++
                Log.w(TAG, "[${r.ns}] ${r.symbol} unfulfilled (${r.error.take(80)}) — ${row.paperWas}" +
                    (row.seatTo?.let { ", seat → $it" } ?: ""))
            }
        }
        // Follow up what each would have made.
        var followed = 0
        for (row in all(ctx).filter { it.shadowStatus != "CLOSED" && now - it.checkedAt >= RECHECK_MS }) {
            try { upsert(ctx, follow(ctx, row, now)); followed++ } catch (e: Exception) { Log.w(TAG, "follow-up ${row.symbol} failed", e) }
        }
        return listOfNotNull(if (moved > 0) "$moved unfulfilled" else null,
            if (followed > 0) "$followed followed up" else null).joinToString(", ")
    }

    /** Write one failed buy down — and, in a seat algo, free its seat. Null to try again next pass. */
    private fun record(ctx: Context, r: T212LiveStore.LiveTrade, now: Long): Row? {
        val lab = StrategyLabStore.load(ctx, r.ns)
        val paper = lab.trades.firstOrNull { it.id == r.paperTradeId }
        val seats = try { MacdGateStore.load(ctx, r.ns).enabled } catch (e: Exception) { false }
        var seatTo: String? = null
        val paperWas = when {
            paper?.status == "OPEN" && seats -> {
                // Out of the book, and the seat to the next contender — the same
                // steps as a position you closed by hand, with its own reason.
                val ok = StrategyLabRunner.withAlgoHeld(r.ns, 60_000L) {
                    StrategyLabStore.unenroll(ctx, r.symbol, r.ns)
                    seatTo = try {
                        MacdGateEngine.handOn(ctx, r.ns, r.symbol, "real buy failed — moved to Unfulfilled")
                    } catch (e: Exception) { null }
                    true
                } ?: return null   // the algo is mid-pass; next time
                if (ok) "taken out of the book" else return null
            }
            paper?.status == "OPEN" -> "kept in the book — this algo has no seats to free"
            paper?.status == "CLOSED" -> "the strategy had already closed it"
            else -> "already out of the book"
        }
        val base = Row(
            key = "${r.ns}|${r.paperTradeId}", ns = r.ns, symbol = r.symbol, ticker = r.t212Ticker,
            paperTradeId = r.paperTradeId, entryTs = entryTsOf(r.paperTradeId),
            signalPrice = r.paperEntryPrice, amount = r.amount, decidedAt = r.buyAt,
            reason = r.error.ifBlank { "the real buy did not fill" }, recordedAt = now,
            paperWas = paperWas, seatTo = seatTo)
        // A paper trade still in the book already says what it made.
        return if (paper != null && paperWas != "taken out of the book") fromPaper(base, paper, now) else base
    }

    private fun fromPaper(row: Row, p: StrategyLabStore.LedgerTrade, now: Long): Row =
        if (p.status == "CLOSED") row.copy(shadowStatus = "CLOSED", shadowPct = p.pctReturn,
            shadowExitTs = p.exitTs ?: 0L, shadowExitPrice = p.exitPrice ?: 0.0, shadowExitReason = p.exitReason,
            shadowNote = "", checkedAt = now)
        else row.copy(shadowStatus = "OPEN", shadowPct = p.pctReturn, shadowNote = "", checkedAt = now)

    /** What it would have made by now: the paper trade if the book still has it, else a replay of the algo's rules. */
    private fun follow(ctx: Context, row: Row, now: Long): Row {
        StrategyLabStore.load(ctx, row.ns).trades.firstOrNull { it.id == row.paperTradeId }?.let {
            return fromPaper(row, it, now)
        }
        val s = StrategyLabRunner.shadowTrade(ctx, row.ns, row.symbol, row.entryTs)
        return if (!s.ok) row.copy(shadowStatus = if (row.shadowPct == null) "UNKNOWN" else row.shadowStatus,
                                   shadowNote = s.note, checkedAt = now)
        else if (s.open) row.copy(shadowStatus = "OPEN", shadowPct = s.pct, shadowNote = "", checkedAt = now)
        else row.copy(shadowStatus = "CLOSED", shadowPct = s.pct, shadowExitTs = s.exitTs,
            shadowExitPrice = s.exitPrice, shadowExitReason = s.exitReason, shadowNote = "", checkedAt = now)
    }
}

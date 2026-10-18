package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

/**
 * Live Trading-212 mirror ledger (US automated lab).
 *
 * Separate from the paper StrategyLabStore: paper remains the source of signal
 * truth; this records which paper decisions were mirrored into REAL T212 market
 * orders. Unlike Binance, T212's order response gives only an order id (fills
 * settle asynchronously), so this stores the orders placed + requested size, not
 * realized fills/P&L — verify actual fills in the Trading 212 app.
 */
object T212LiveStore {
    private const val TAG = "T212LiveStore"
    private val gson = Gson()

    /** Fractional shares are stored to 5 dp, so quantity comparisons need a floor
     *  below which a difference is rounding rather than a missing fill. */
    private const val QTY_EPS = 1e-5

    data class LiveTrade(
        val paperTradeId: String,
        /**
         * WHICH ALGO placed this. Without it, attribution is impossible and orders
         * are silently lost: `paperTradeId` is "SYMBOL-entryTs", which is NOT
         * unique across algos — six German algos share one 106-name universe and
         * similar configs, so two of them entering SAP on the same 240-min bar
         * produce the identical id. The mirror's idempotency check would then treat
         * the second algo's order as a duplicate and skip it, and the one stored
         * row would have two owners. Empty string = a row written before this
         * field existed (bare/legacy space).
         */
        val ns: String = "",
        val symbol: String,
        val t212Ticker: String,
        /** OPEN / CLOSED / ERROR / PENDING / EXITING / MANUAL_CLOSED / RETRY.
         *  RETRY = wanted, not filled, still being attempted — see
         *  T212LiveMirror.retryPendingEntries. It holds NO shares, so it must never
         *  be counted as a position; only OPEN/PENDING/EXITING are exposure. */
        val status: String,
        val entryTs: Long,
        val paperEntryPrice: Double,
        val amount: Double,                  // account-currency notional requested
        val qty: Double,                     // shares requested on the BUY
        val buyOrderId: Long = 0L,
        val buyAt: Long = 0L,
        val exitTs: Long? = null,
        val paperExitPrice: Double? = null,
        val sellOrderId: Long = 0L,
        val sellAt: Long = 0L,
        val error: String = "",
        /** Attempts already made on a RETRY row. Capped so a name the market will
         *  simply not sell to us cannot be chased forever. */
        val retryCount: Int = 0,
        /** Epoch ms before which no further attempt should be made. */
        val retryAfter: Long = 0L,
        /** When the RV gate began holding this order back (0 = not waiting): a buy
         *  waiting for a better moment, or a sale held while the price rises. The
         *  two-hour limit is counted from here. See [com.signalscope.app.trading.RvGate]. */
        val rvSince: Long = 0L,
        /** The RV gate's last look at a held order — where the price sat in today's
         *  range, which way it was moving — as a small JSON note for the waiting-
         *  orders list. See [com.signalscope.app.trading.RvGate.snapshot]. */
        val rvNote: String? = null,
        // ── EVERY RUNG, not just the first ────────────────────────────────────
        // The patient ladder places up to six orders per leg (marketable limit,
        // then cancel-replace-chase at widening concessions). A single Long can
        // only ever name one of them, so the fill reader would price the whole
        // position off whichever rung happened to be stored — typically the first
        // and most optimistic — and report a slippage figure that never happened.
        // The scalar fields above stay for back-compat and hold the FIRST rung.
        //
        // NULLABLE on purpose. Gson instantiates via Unsafe and never runs a Kotlin
        // default, so every row written before this field existed deserializes with
        // it absent — as null, regardless of the declared type. Declaring these
        // non-null would satisfy the compiler and then throw on the first ledger
        // read after an upgrade. Always reach them through allBuyIds/allSellIds.
        val buyOrderIds: List<Long>? = null,
        val sellOrderIds: List<Long>? = null,
        // How many of the placed rungs the history endpoint has actually shown us.
        // A leg is only a valid measurement when seen == placed; until then the
        // weighted average is over a subset and would understate the true cost.
        val buyRungsSeen: Int = 0,
        val sellRungsSeen: Int = 0,
        // THE LADDER'S ANCHOR at order time — the third price in the chain.
        // paper signal price → ladder ref → actual fill. Without this, decision
        // latency (signal bar close vs the market when the order was sent) and
        // execution cost (ref vs fill) are fused into one number and cannot be
        // attacked separately: one is fixed by ticking faster, the other by
        // changing the ladder.
        val refEntryPrice: Double = 0.0,
        val refExitPrice: Double = 0.0,
        // ── REAL FILL PRICES, backfilled from T212's executed-order history ──
        // Order placement returns an id, not a fill, so these can only be learned
        // afterwards (T212LiveMirror.reconcileFills). Until then they are 0.0 and
        // the trade contributes no slippage sample — a missing measurement, never
        // a guessed one. Defaults keep older stored JSON deserializing unchanged.
        val liveEntryPrice: Double = 0.0,
        val liveExitPrice: Double = 0.0,
        val liveQtyFilled: Double = 0.0,
        val liveEntryValue: Double = 0.0,   // cash actually deployed on the buy
        val liveExitValue: Double = 0.0,    // cash actually received on the sell
        val liveExitQty: Double = 0.0,      // shares actually sold (may differ from bought)
        /**
         * Shares in [liveExitQty] that this row NEVER OWNED — unclaimed sub-€1 dust
         * the exit had to clear to be a legal order.
         *
         * Held separately because it is real cash but not strategy return. It was
         * never bought, so it has no cost basis in this app; booking its proceeds as
         * P&L would credit the algo with money it did not earn, inflate the seat it
         * compounds into, and quietly bias the very slippage measurement all of this
         * exists to get right. Subtracted wherever performance is computed, reported
         * on its own everywhere else.
         */
        val sweptQty: Double = 0.0,
        /**
         * SOLD BY HAND — set when the user declares they closed this position
         * themselves in the T212 app. Never inferred: a missing share has several
         * possible causes and only the user can say which.
         *
         * The cash was real, so the row is kept and reported; but the exit was not
         * the ladder's, so the trade contributes NO slippage sample and NO deviation
         * bar. Measuring your thumb and calling it execution quality would corrupt
         * the one figure the app exists to establish.
         */
        val manualClosedAt: Long = 0L,
        val manualExitPrice: Double = 0.0,
        val manualExitQty: Double = 0.0,
        val fillsSyncedAt: Long = 0L,
        /**
         * THE ADOPTED-EXIT VERDICT for a position the engine no longer tracks.
         *
         * Blank or null = nothing to say: either the strategy still holds this
         * trade itself, or the adopted rules were applied cleanly. A message means
         * the rules could NOT be applied, which leaves the position on its hard
         * stop alone — the one thing the user has to be told rather than left to
         * infer from an absence.
         *
         * NULLABLE for the reason every other late field here is: Gson builds
         * these through Unsafe and never runs a Kotlin default, so a row written
         * before this existed arrives with it absent.
         */
        val adoptedNote: String? = null,
        /**
         * WHICH SUBSYSTEM decided to sell, when that is not simply the algo named
         * by [ns] — the watchlist's minder for hand-bought stock, the fill-anchored
         * real stop, the re-sell of a position the paper side had already closed.
         *
         * Stored rather than passed along because the sale and the FILL PRICE are
         * separated by minutes: the alert that finally carries real money is sent
         * from the fill reader, long after whichever code path decided to sell has
         * returned. Without this the settled alert could only say which algo owns
         * the namespace, which for a hand-bought position is nobody.
         *
         * NULLABLE ON PURPOSE, exactly as [buyOrderIds] above documents: Gson
         * builds these through Unsafe and never runs a Kotlin default, so every row
         * written before this field existed arrives with it absent — as null, no
         * matter what the declared type says. A non-null String here would compile
         * and then throw on the first ledger read after the upgrade.
         */
        val exitOrigin: String? = null,
        /**
         * WHY it was sold, in the words the rule itself used ("MACD slope flipped
         * + to -"). Stored for the same reason as [exitOrigin] and nullable for
         * the same one: the settled alert is sent minutes later from the fill
         * reader, and an alert that REPLACES the provisional one must not quietly
         * drop the reason the provisional one gave. Without this the sharper
         * figure would arrive having lost the only part that explains it.
         */
        val exitReasonNote: String? = null,
        /**
         * This entry is waiting for its exchange to open rather than being retried.
         *
         * Held as state, not inferred from the due-time, for one reason: the digest
         * that tells the user what is lined up for the morning must fire when a name
         * FIRST parks and not on every pass afterwards. Without a flag to compare
         * against there is no transition to detect, and the choice is between an
         * evening of duplicate notifications and none at all.
         *
         * Boolean, so a row written before this existed deserializes to false and
         * simply announces itself once on its next park — the harmless direction.
         */
        val parkedForOpen: Boolean = false
    ) {
        /** Every buy rung, tolerating rows written before [buyOrderIds] existed. */
        fun allBuyIds(): List<Long> =
            buyOrderIds?.filter { it > 0L }?.takeIf { it.isNotEmpty() }
                ?: listOfNotNull(buyOrderId.takeIf { it > 0L })

        fun allSellIds(): List<Long> =
            sellOrderIds?.filter { it > 0L }?.takeIf { it.isNotEmpty() }
                ?: listOfNotNull(sellOrderId.takeIf { it > 0L })

        /**
         * Is this leg fully accounted for by the broker's history?
         *
         * NOT "every rung reported". That test was wrong and would have excluded
         * almost every real ladder: a rung that rests and is then CANCELLED never
         * appears in executed-order history, because nothing executed. A chase that
         * places six rungs and fills on the third has three ids that will never
         * report — so `rungsSeen >= rungsPlaced` could never be satisfied and the
         * trade stayed "reconciling" forever.
         *
         * The invariant that actually matters is QUANTITY: [qty] is what the ladder
         * told us it filled, so the history must account for that many shares. How
         * many orders it took is bookkeeping, not correctness.
         */
        fun buyComplete(): Boolean {
            if (allBuyIds().isEmpty()) return false
            if (liveEntryPrice <= 0.0 || liveQtyFilled <= 0.0) return false
            if (qty <= 0.0) return true          // legacy row with no recorded size
            return liveQtyFilled >= qty - QTY_EPS
        }

        /** Exit legs compare against what was OWNED, not what was ordered — a
         *  partial sell is genuinely incomplete and must stay out of the averages.
         *  Swept dust is excluded from both sides: it inflates the sold figure
         *  without belonging to the position. */
        fun sellComplete(): Boolean {
            if (allSellIds().isEmpty()) return false
            if (liveExitPrice <= 0.0 || liveExitQty <= 0.0) return false
            val owned = if (liveQtyFilled > 0.0) liveQtyFilled else qty
            if (owned <= 0.0) return true
            return ownExitQty() >= owned - QTY_EPS
        }

        /** Closed by the user's own order rather than by the strategy. */
        fun isManual(): Boolean = manualClosedAt > 0L

        /** Shares sold that this row actually owned. */
        fun ownExitQty(): Double = (liveExitQty - sweptQty).coerceAtLeast(0.0)

        /** Proceeds from this row's OWN shares, apportioned at the same average
         *  price the whole sale achieved — the swept dust went out in the same
         *  orders, so it has no separate price of its own. */
        fun ownExitValue(): Double =
            if (liveExitQty > 0.0) liveExitValue * (ownExitQty() / liveExitQty) else liveExitValue

        /** Cash the sweep brought in. Real money, but not strategy return. */
        fun sweptValue(): Double =
            if (liveExitQty > 0.0) liveExitValue * (sweptQty / liveExitQty) else 0.0

        /**
         * Shares of its OWN position this row failed to sell — residue it CREATED.
         *
         * The mirror image of a sweep, and the more diagnostic of the two. Sweeping
         * is cleaning up after drift; creating means this algo's exit ladder gave up
         * with part of the position still held. One algo doing that repeatedly is an
         * execution-quality signal (chase too tight, dwell too short, illiquid names)
         * long before it is an accounting one.
         */
        fun ownResidueQty(): Double {
            if (status != "CLOSED") return 0.0
            val owned = if (liveQtyFilled > 0.0) liveQtyFilled else qty
            if (owned <= 0.0 || liveExitQty <= 0.0) return 0.0
            return (owned - ownExitQty()).coerceAtLeast(0.0)
        }

        /** Value of that residue at the price the exit actually achieved. */
        fun ownResidueValue(): Double =
            ownResidueQty() * (if (liveExitPrice > 0.0) liveExitPrice else liveEntryPrice)
    }

    data class State(val trades: List<LiveTrade> = emptyList())

    private fun file(ctx: Context): File = File(ctx.filesDir, "t212_live_mirror.json")

    fun load(ctx: Context): State = try {
        val f = file(ctx)
        if (!f.exists()) State() else gson.fromJson(f.readText(), State::class.java) ?: State()
    } catch (e: Exception) { Log.e(TAG, "load failed", e); State() }

    @Synchronized
    private fun save(ctx: Context, state: State) {
        try {
            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(gson.toJson(state))
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (e: Exception) { Log.e(TAG, "save failed", e) }
    }

    /**
     * DOES A ROW WITH THIS NAMESPACE BELONG IN THIS LEDGER?
     *
     * Two things live here and only two: the German and US spaces, and the live
     * test bench — which places real Trading 212 orders under a namespace of its
     * own that belongs to no space at all.
     *
     * WRITTEN ONCE BECAUSE GETTING IT WRONG IS EXPENSIVE IN BOTH DIRECTIONS. Too
     * broad and a sweep acts on another broker's position: one would have sold an
     * Indian stock through Trading 212 as a US listing. Too narrow and the bench's
     * real orders stop being reconciled — and its namespace has no space prefix,
     * so the obvious "German or US" test silently excludes it. The first version
     * of the clean-up below made exactly that mistake and deleted five of its rows.
     */
    fun isT212Ns(ns: String): Boolean {
        if (ns == com.signalscope.app.trading.LiveTestBench.NS) return true
        val b = com.signalscope.app.trading.Spaces.baseOf(ns)
        return b == "de" || b == "us"
    }

    /**
     * REMOVE ROWS THAT WERE NEVER TRADING 212 POSITIONS, once.
     *
     * A writer bug put eight Zerodha holdings into this file on 8 September. The
     * writer was fixed; the rows it had already made were not, and they sat here
     * OPEN — asserting that a Trading 212 account held Indian stock.
     *
     * That was not merely untidy. Seven of the eight were already CLOSED in the
     * book that actually owns them, so they claimed exposure to positions that had
     * ended. And every sweep over this file that lacked a space filter would act
     * on them: one would have tried to sell an Indian stock through Trading 212 as
     * a US listing, and the reconciliation added later would have compared them to
     * a Trading 212 portfolio, found none of them, and closed eight real holdings.
     * Those sweeps are now guarded — but a ledger that needs every reader to know
     * which of its rows are lies is a ledger with a bug in it, not a convention.
     *
     * SAFE TO REMOVE, and checked rather than assumed before it was written: each
     * of the eight is an exact duplicate of an entry in the manual watch book for
     * its own market — same quantity, same fill price, same signal bar — and that
     * book is the record those positions are actually kept in. Nothing here is the
     * only copy of anything.
     *
     * A BACKUP IS WRITTEN FIRST regardless. This is a money ledger, the operation
     * deletes rows, and "the book has a copy" is a belief about data rather than a
     * property of it — if that belief is ever wrong for some future stray, the
     * file next to it is the way back.
     */
    @Synchronized
    fun retireForeignRows(ctx: Context): Int {
        val prefs = ctx.getSharedPreferences("signalscope_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("t212_foreign_rows_retired_v2", false)) return 0
        val s = load(ctx)
        val foreign = s.trades.filterNot { isT212Ns(it.ns) }
        // The flag is set whether or not anything was found, so a clean install
        // does not re-walk this for ever.
        prefs.edit().putBoolean("t212_foreign_rows_retired_v2", true).apply()
        if (foreign.isEmpty()) return 0
        try {
            val f = file(ctx)
            if (f.exists()) f.copyTo(File(f.parentFile, "t212_live_mirror.pre-retire.json"), overwrite = true)
        } catch (e: Exception) {
            // NO BACKUP, NO DELETE. The whole reason to take one is the case where
            // the assumption behind the delete turns out to be wrong.
            Log.e(TAG, "could not back up before retiring foreign rows — leaving them alone", e)
            prefs.edit().putBoolean("t212_foreign_rows_retired_v2", false).apply()
            return 0
        }
        foreign.forEach {
            Log.w(TAG, "retiring foreign row: ${it.symbol} (${it.ns}) status=${it.status} qty=${it.qty}")
        }
        save(ctx, s.copy(trades = s.trades - foreign.toSet()))
        Log.w(TAG, "retired ${foreign.size} row(s) that were never Trading 212 positions; " +
            "backup kept as t212_live_mirror.pre-retire.json")
        return foreign.size
    }

    @Synchronized
    fun upsert(ctx: Context, trade: LiveTrade, maxTrades: Int = 500): State {
        val s = load(ctx)
        // Replace on the (ns, paperTradeId) PAIR. Matching on paperTradeId alone
        // would let one algo's order overwrite another's on the same ticker+bar.
        val merged = (s.trades.filterNot {
            it.paperTradeId == trade.paperTradeId && it.ns == trade.ns
        } + trade).sortedBy { it.entryTs }.takeLast(maxTrades)
        val next = State(merged); save(ctx, next); return next
    }

    @Synchronized
    fun markError(ctx: Context, paperTradeId: String, symbol: String, ticker: String,
                  entryTs: Long, message: String, ns: String = ""): State {
        val current = load(ctx).trades.firstOrNull { it.paperTradeId == paperTradeId && it.ns == ns }
        val t = current?.copy(status = "ERROR", error = message)
            ?: LiveTrade(paperTradeId, ns, symbol.uppercase(), ticker, "ERROR", entryTs, 0.0, 0.0, 0.0, error = message)
        return upsert(ctx, t)
    }

    /** Identity is (ns, paperTradeId) — see [LiveTrade.ns]. */
    fun get(ctx: Context, paperTradeId: String, ns: String = ""): LiveTrade? =
        load(ctx).trades.firstOrNull { it.paperTradeId == paperTradeId && it.ns == ns }

    fun openFor(ctx: Context, paperTradeId: String, ns: String = ""): LiveTrade? =
        get(ctx, paperTradeId, ns)?.takeIf { it.status == "OPEN" && it.qty > 0.0 }

    /** Every algo namespace that has ever mirrored an order. */
    fun namespaces(ctx: Context): List<String> =
        load(ctx).trades.map { it.ns }.distinct()

    /**
     * How many shares of [t212Ticker] OTHER live rows still claim.
     *
     * T212 pools one position per instrument across every algo, the bench, and
     * anything traded by hand, so an exit has to know how much of the holding is
     * not its own. Used to log and warn when the pool cannot cover every claim —
     * never to expand a sale, only ever to bound it.
     */
    fun otherClaimsQty(ctx: Context, t212Ticker: String, selfNs: String, selfId: String): Double =
        load(ctx).trades
            .filter { it.status == "OPEN" || it.status == "PENDING" || it.status == "EXITING" }
            .filter { it.t212Ticker.equals(t212Ticker, true) }
            .filterNot { it.ns == selfNs && it.paperTradeId == selfId }
            .sumOf { if (it.liveQtyFilled > 0.0) it.liveQtyFilled else it.qty }

    fun reset(ctx: Context): State { val s = State(); save(ctx, s); return s }
}

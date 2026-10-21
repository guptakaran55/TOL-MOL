package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.ManualWatchStore
import com.signalscope.app.data.StrategyLabStore
import com.signalscope.app.data.T212LiveStore
import com.signalscope.app.network.Trading212Client

/**
 * SALES YOU MADE, BROUGHT INTO THE APP'S BOOKS — without a tap to confirm them.
 *
 * Two routes a position can leave an algo or the watchlist behind its back:
 *
 *  1. YOUR SALE THROUGH THE APP — "Patient sell" on a stock's page (from the
 *     portfolio or anywhere else). The app placed that order itself, so there is
 *     nothing to guess: [afterAppSale] works out how much of what the algos and
 *     the watchlist hold went with it, closes those positions at the price the
 *     sale achieved — booked like a take-profit, not discarded — and hands each
 *     algo seat on to the next contender. Shares you hold beyond what the app
 *     manages are taken first: if you sold only those, nothing changes.
 *
 *  2. YOUR OWN ORDER AT THE BROKER — a target or stop you set from the portfolio,
 *     a bulk target, or a sale in Trading 212's own app. When an algo's shares are
 *     gone and the broker's history shows a sale under an order the app's own
 *     machinery did not place, [sweep] books it at once, at that real price, like
 *     a take-profit, and hands the seat on. A stock you chose to sell is a stock
 *     you chose to sell, whichever button you used.
 *
 *  3. GONE WITH NO SALE ON RECORD (it fell off the history window, or never
 *     showed in it). Then it waits for evidence: ENTIRELY gone from the account
 *     on two reads at least half an hour apart. [sweep] closes it as "sold by
 *     you" with the result set aside — there is no price to book — and hands the
 *     seat on. A position only partly missing is still left to you: there is no
 *     telling which shares went.
 *
 *  4. PART OF A STOCK SOLD BY YOU AT THE BROKER (20 Oct 2026). Trading 212 shows
 *     one total per stock while the app keeps separate positions in it — your
 *     watchlist's lots and the algos' — so a sale of some of it in Trading 212's
 *     app used to leave the app counting shares that were gone: "ledger
 *     shortfall", with every stop and target sized on the wrong number. When the
 *     account holds fewer shares than the app's positions on two reads at least
 *     ten minutes apart, [absorbPartial] takes the difference off those positions
 *     in [saleOrder], closing the ones it uses up and trimming the next. It never
 *     places or cancels an order itself; the targets resize on their next check.
 *
 * Watchlist positions sold in the broker's app ENTIRELY are closed by the
 * watchlist's own two-read check, so [sweep] leaves those to it.
 */
object T212HandSales {
    private const val TAG = "T212HandSales"
    private const val PREFS = "t212_hand_sales"
    /** Gone on two reads at least this far apart before anything is closed. */
    private const val ABSENT_CONFIRM_MS = 30 * 60_000L
    /** A buy younger than this may simply not be in the holdings list yet. */
    private const val SETTLE_MS = 30 * 60_000L
    const val REASON = "sold by you"

    private fun ownQty(r: T212LiveStore.LiveTrade): Double =
        if (r.liveQtyFilled > 0.0) minOf(r.qty, r.liveQtyFilled) else r.qty

    private fun isWatcher(ns: String) = Spaces.bucketIdOf(ns) == ManualWatchStore.ID

    /** The average price the sale's own orders filled at, from the broker's
     *  history — null when the history has not caught up yet. */
    private fun avgFill(client: Trading212Client, orderIds: List<Long>): Double? {
        if (orderIds.isEmpty()) return null
        val fills = (client.fetchOrderHistory(limit = 50) as? Trading212Client.T212Result.Success)
            ?.data?.filter { it.orderId in orderIds && it.filledQuantity > 0.0 && it.fillPrice > 0.0 }
            ?: return null
        val q = fills.sumOf { it.filledQuantity }
        return if (q > 0.0) fills.sumOf { it.fillPrice * it.filledQuantity } / q else null
    }

    /**
     * After a sale you made through the app. Returns a sentence for the result
     * dialog, blank when no algo or watchlist position was involved.
     */
    fun afterAppSale(ctx: Context, ticker: String, orderIds: List<Long>, refPrice: Double): String {
        val rows = T212LiveStore.load(ctx).trades.filter {
            T212WeekTargets.applies(it.ns) && it.t212Ticker.equals(ticker, true) &&
                it.status == "OPEN" && it.qty > 0.0
        }
        if (rows.isEmpty()) return ""
        val client = Trading212Client(ConfigManager(ctx))
        // What is left in the account now. Unreadable = do nothing here; a full
        // exit is still caught by [sweep] later, from the broker's own word.
        var remaining: Double? = null
        for (attempt in 0..1) {
            val r = client.fetchPortfolio()
            if (r is Trading212Client.T212Result.Success) {
                remaining = r.data.firstOrNull { it.ticker.equals(ticker, true) }?.quantity ?: 0.0
                break
            }
            Thread.sleep(3000L)
        }
        val left = remaining ?: return ""
        val managed = rows.sumOf { ownQty(it) }
        val tol = maxOf(1e-4, managed * 0.005)
        var excess = managed - left
        if (excess <= tol) return ""                     // you sold shares the app does not manage
        val exitPx = avgFill(client, orderIds) ?: refPrice
        if (!(exitPx > 0.0)) return ""
        val now = System.currentTimeMillis()
        val notes = ArrayList<String>()
        // In [saleOrder]: your watchlist's positions first, oldest first, then an
        // algo's. (This took the newest first until 20 Oct; one order for every
        // sale of yours, whichever button made it.)
        for (cut in allocate(rows, excess)) {
            notes += if (cut.whole) closeOne(ctx, cut.row, orderIds, exitPx, now)
                     else trim(ctx, cut.row, cut.qty, "your sale from the stock's page")
        }
        try { T212LiveMirror.reconcileFills(ctx) }
        catch (e: Exception) { Log.w(TAG, "fill read after your sale failed", e) }
        return notes.filter { it.isNotBlank() }.joinToString(" ")
    }

    /**
     * The order a sale of yours is taken from the app's positions in one stock:
     * YOUR WATCHLIST'S first, then an algo's — a sale by hand is your decision and
     * should not come out of a strategy's position while yours can cover it —
     * and within each, the OLDEST first (the user's choice, 20 Oct 2026). Pure.
     */
    internal fun saleOrder(rows: List<T212LiveStore.LiveTrade>): List<T212LiveStore.LiveTrade> =
        rows.sortedWith(compareBy<T212LiveStore.LiveTrade>({ if (isWatcher(it.ns)) 0 else 1 }, { it.buyAt }))

    /** One position's share of a sale: [qty] shares off [row], all of it when [whole]. */
    internal data class Cut(val row: T212LiveStore.LiveTrade, val qty: Double, val whole: Boolean)

    /** How [sold] shares fall across [rows], in [saleOrder]. Pure. */
    internal fun allocate(rows: List<T212LiveStore.LiveTrade>, sold: Double): List<Cut> {
        val tol = maxOf(1e-4, rows.sumOf { ownQty(it) } * 0.005)
        val out = ArrayList<Cut>()
        var left = sold
        for (r in saleOrder(rows)) {
            if (left <= tol) break
            val q = ownQty(r)
            if (q <= 0.0) continue
            if (left >= q - tol) { out += Cut(r, q, true); left -= q }
            else { out += Cut(r, left, false); left = 0.0 }
        }
        return out
    }

    /**
     * Part of one position went: it keeps the rest, still managed. Recorded as
     * shares sold by you, which the fill readers subtract — without that they
     * rebuild the size from the buy orders and hand the sold shares back.
     */
    private fun trim(ctx: Context, r: T212LiveStore.LiveTrade, q: Double, how: String): String {
        val keep = (ownQty(r) - q).coerceAtLeast(0.0)
        T212LiveStore.upsert(ctx, r.copy(qty = keep,
            liveQtyFilled = if (r.liveQtyFilled > 0.0) keep else r.liveQtyFilled,
            handSoldQty = r.handSoldQty + q))
        if (isWatcher(r.ns)) ManualWatchStore.mutate(ctx, Spaces.baseOf(r.ns)) { s ->
            s.copy(entries = s.entries.map { e ->
                if (e.status == "OPEN" && "${e.symbol.uppercase()}-${e.signalBarTs}" == r.paperTradeId)
                    e.copy(filledQty = (e.filledQty - q).coerceAtLeast(0.0), soldQty = e.soldQty + q)
                else e
            })
        }
        Log.w(TAG, "[${r.ns}] ${r.symbol} ${"%.4f".format(q)} sold by you ($how) — position now $keep")
        return "${label(ctx, r.ns)} keeps ${"%.4f".format(keep)} ${r.symbol}."
    }

    private fun label(ctx: Context, ns: String): String =
        if (isWatcher(ns)) "Your watchlist"
        else try { com.signalscope.app.data.BucketStore.list(ctx, Spaces.baseOf(ns))
                .firstOrNull { it.ns == ns }?.name } catch (e: Exception) { null } ?: "The algo"

    /** Close one position everywhere at the sale's price, and move its seat on. */
    private fun closeOne(ctx: Context, r: T212LiveStore.LiveTrade, orderIds: List<Long>,
                         exitPx: Double, now: Long): String {
        T212LiveStore.upsert(ctx, r.copy(status = "CLOSED",
            sellOrderId = orderIds.firstOrNull() ?: 0L, sellOrderIds = orderIds,
            sellAt = now, refExitPrice = exitPx, error = "",
            exitOrigin = "your sale from the stock's page", exitReasonNote = REASON))
        val who = label(ctx, r.ns)
        if (isWatcher(r.ns)) {
            ManualWatchStore.mutate(ctx, Spaces.baseOf(r.ns)) { s ->
                s.copy(entries = s.entries.map { e ->
                    if (e.status == "OPEN" && "${e.symbol.uppercase()}-${e.signalBarTs}" == r.paperTradeId)
                        e.copy(status = "CLOSED", exitTs = now / 1000L, closedAt = now,
                            exitQty = e.filledQty, exitPrice = exitPx, exitReason = REASON)
                    else e
                })
            }
            Log.w(TAG, "[${r.ns}] ${r.symbol} sold by you — watchlist position closed at $exitPx")
            return "$who's ${r.symbol} position is closed."
        }
        var seat: String? = null
        val booked = StrategyLabRunner.withAlgoHeld(r.ns, 90_000L) {
            val open = StrategyLabStore.load(ctx, r.ns).trades
                .firstOrNull { it.id == r.paperTradeId && it.status == "OPEN" }
            if (open != null) AlgoTakeProfit.bookPaperExit(ctx, r.ns, open, exitPx, now, REASON)
            seat = try { MacdGateEngine.handOn(ctx, r.ns, r.symbol, REASON) }
                   catch (e: Exception) { Log.w(TAG, "seat hand-on failed", e); null }
            true
        } ?: false
        Log.w(TAG, "[${r.ns}] ${r.symbol} sold by you — booked=$booked at $exitPx, seat → ${seat ?: "-"}")
        return if (!booked) "$who's ${r.symbol} record was busy; it is closed at the next check."
               else "$who's ${r.symbol} position is closed at ${"%.2f".format(exitPx)}" +
                   (if (seat != null) "; its seat goes to $seat." else ".")
    }

    /** Was this execution after [sinceMs] (with a minute's slack)? Unknown = yes. */
    private fun executedAfter(iso: String, sinceMs: Long): Boolean = try {
        java.time.Instant.parse(iso).toEpochMilli() >= sinceMs - 60_000L
    } catch (e: Exception) {
        try { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() >= sinceMs - 60_000L }
        catch (e2: Exception) { true }
    }

    /**
     * A sale of an algo's shares by an order YOU placed, priced from the broker:
     * the position closed at that price, booked to the algo like a take-profit,
     * and the seat handed on. @return the stock that took the seat, or null.
     */
    private fun bookYourSale(ctx: Context, r: T212LiveStore.LiveTrade, ids: List<Long>,
                             px: Double, now: Long): String? {
        T212LiveStore.upsert(ctx, r.copy(status = "CLOSED",
            sellOrderId = ids.firstOrNull() ?: 0L, sellOrderIds = ids,
            sellAt = now, refExitPrice = px, error = "",
            exitOrigin = "your own order at Trading 212 (a target, stop or sale you set)",
            exitReasonNote = REASON_YOURS))
        var seat: String? = null
        StrategyLabRunner.withAlgoHeld(r.ns, 90_000L) {
            val open = StrategyLabStore.load(ctx, r.ns).trades
                .firstOrNull { it.id == r.paperTradeId && it.status == "OPEN" }
            if (open != null) AlgoTakeProfit.bookPaperExit(ctx, r.ns, open, px, now, REASON_YOURS)
            seat = try { MacdGateEngine.handOn(ctx, r.ns, r.symbol, REASON_YOURS) } catch (e: Exception) { null }
            true
        }
        try { T212LiveMirror.reconcileFills(ctx) } catch (_: Exception) {}
        Log.w(TAG, "[${r.ns}] ${r.symbol} sold by your own order at $px — booked, seat → ${seat ?: "-"}")
        return seat
    }

    const val REASON_YOURS = "your own order sold it"

    // ── PART OF A STOCK SOLD BY YOU AT TRADING 212 ─────────────────────────────

    private const val PART_PREFS = "t212_part_sales"
    /** A partial shortfall must last this long — two passes — before it is taken off. */
    private const val PART_CONFIRM_MS = 10 * 60_000L
    /** First seen this recently, the ledger check keeps quiet about it: it is being
     *  treated as a sale of yours, not as an error. See [pendingPartial]. */
    private const val PART_QUIET_MS = 45 * 60_000L

    /** Trading 212 tickers whose shortfall is being taken as a sale of yours right now. */
    fun pendingPartial(ctx: Context, now: Long = System.currentTimeMillis()): Set<String> =
        ctx.getSharedPreferences(PART_PREFS, Context.MODE_PRIVATE).all.mapNotNull { (k, v) ->
            (v as? Long)?.takeIf { now - it < PART_QUIET_MS }?.let { k.removePrefix("part|") }
        }.toSet()

    /**
     * Every pass: stocks the account holds FEWER of than the app's positions in
     * them — but not none — on two reads at least [PART_CONFIRM_MS] apart. The
     * difference was sold by you outside the app; it comes off the positions in
     * [saleOrder], at the price the broker's history shows for sales the app did
     * not place when it shows one. Returns a few words for the pass summary.
     */
    fun absorbPartial(ctx: Context): String {
        if (!ConfigManager(ctx).hasT212Credentials) return ""
        val now = System.currentTimeMillis()
        val p = ctx.getSharedPreferences(PART_PREFS, Context.MODE_PRIVATE)
        val ledger = T212LiveStore.load(ctx).trades.filter { T212WeekTargets.applies(it.ns) }
        val byTicker = ledger.filter { it.status == "OPEN" && ownQty(it) > 0.0 && it.t212Ticker.isNotBlank() }
            .groupBy { it.t212Ticker.uppercase() }
        p.all.keys.filter { it.removePrefix("part|") !in byTicker.keys }.forEach { p.edit().remove(it).apply() }
        if (byTicker.isEmpty()) return ""
        // Unreadable holdings = do nothing. Never act on a list that may be short.
        val holdings = (BrokerHoldings.t212(ctx) as? BrokerHoldings.Result.Ok)?.holdings ?: return ""
        val heldQty = holdings.groupBy { it.ticker.uppercase() }.mapValues { (_, v) -> v.sumOf { it.qty } }
        val moving = ledger.filter { it.status == "PENDING" || it.status == "EXITING" }
            .map { it.t212Ticker.uppercase() }.toSet()
        val selling = T212ExitGuards.waitingKeys(ctx, ledger)
        val ownIds: Set<Long> by lazy {
            val s = HashSet<Long>()
            try { T212LiveStore.load(ctx).trades.forEach { s.addAll(it.allBuyIds()); s.addAll(it.allSellIds()) } } catch (_: Exception) {}
            try { com.signalscope.app.data.T212TargetStore.load(ctx).targets.forEach {
                if (it.orderId > 0L) s.add(it.orderId); it.prevIds?.let { ids -> s.addAll(ids) } } } catch (_: Exception) {}
            try { com.signalscope.app.data.T212ExitGuardStore.load(ctx).guards.forEach {
                if (it.orderId > 0L) s.add(it.orderId); it.prevIds?.let { ids -> s.addAll(ids) } } } catch (_: Exception) {}
            s
        }
        val history: List<Trading212Client.HistoricalFill>? by lazy {
            (Trading212Client(ConfigManager(ctx)).fetchOrderHistory(limit = 50)
                as? Trading212Client.T212Result.Success)?.data
        }
        var absorbed = 0
        for ((tk, rows) in byTicker) {
            val key = "part|$tk"
            val managed = rows.sumOf { ownQty(it) }
            val held = heldQty[tk] ?: 0.0
            val short = managed - held
            val tol = maxOf(1e-4, managed * 0.005)
            if (short <= tol) { p.edit().remove(key).apply(); continue }
            // GONE ENTIRELY is the full-sale checks' business (two reads half an hour
            // apart). Marked all the same, so the ledger check stays quiet while they work.
            if (held <= 0.0) { if (p.getLong(key, 0L) == 0L) p.edit().putLong(key, now).apply(); continue }
            // HANDS OFF while anything could still be moving these shares, or a buy
            // may simply not be in the holdings list yet.
            if (tk in moving || rows.any { "${it.ns}|${it.paperTradeId}" in selling } ||
                rows.any { it.buyAt > 0L && now - it.buyAt < SETTLE_MS }) continue
            val first = p.getLong(key, 0L)
            if (first == 0L) {
                p.edit().putLong(key, now).apply()
                Log.w(TAG, "$tk: the account holds ${"%.4f".format(held)}, the app's positions " +
                    "${"%.4f".format(managed)} — checking again before taking it as a sale of yours")
                continue
            }
            if (now - first < PART_CONFIRM_MS) continue
            // WHAT IT SOLD FOR, when the broker's record shows sales the app did not make.
            val since = rows.minOf { it.buyAt }
            val yours = history?.filter {
                it.ticker.equals(rows.first().t212Ticker, true) && it.side.equals("SELL", true) &&
                    it.filledQuantity > 0.0 && it.fillPrice > 0.0 && it.orderId !in ownIds &&
                    executedAfter(it.dateExecuted, since)
            }.orEmpty()
            val px = if (yours.isEmpty()) 0.0
                     else yours.sumOf { it.fillPrice * it.filledQuantity } / yours.sumOf { it.filledQuantity }
            val notes = SellGuard.ifIdle(rows.first().t212Ticker) {
                allocate(rows, short).map { cut ->
                    if (cut.whole) closeYours(ctx, cut.row, yours.map { it.orderId }, px, now)
                    else trim(ctx, cut.row, cut.qty, "a sale at Trading 212")
                }
            } ?: continue                                   // a sale holds this stock right now
            p.edit().remove(key).apply()
            absorbed++
            Log.w(TAG, "$tk: ${"%.4f".format(short)} sold by you at Trading 212 — taken off the app's " +
                "positions" + (if (px > 0.0) " at $px" else ""))
            try {
                LabNotifier.partSoldOutsideApp(ctx, rows.first().ns, rows.first().symbol, short, px,
                    notes.filter { it.isNotBlank() }.joinToString(" "),
                    if (Spaces.baseOf(rows.first().ns) == "de") "EUR" else "USD")
            } catch (_: Exception) {}
        }
        return if (absorbed > 0) "✋ $absorbed part-sold outside the app, taken off" else ""
    }

    /** One position used up entirely by a sale of yours at Trading 212. */
    private fun closeYours(ctx: Context, r: T212LiveStore.LiveTrade, ids: List<Long>,
                           px: Double, now: Long): String {
        val who = label(ctx, r.ns)
        if (isWatcher(r.ns)) {
            T212LiveStore.upsert(ctx, r.copy(status = "MANUAL_CLOSED", manualClosedAt = now,
                manualExitPrice = px, manualExitQty = ownQty(r), error = "sold by you at Trading 212"))
            ManualWatchStore.mutate(ctx, Spaces.baseOf(r.ns)) { s ->
                val entries = s.entries.map { e ->
                    if (e.status == "OPEN" && "${e.symbol.uppercase()}-${e.signalBarTs}" == r.paperTradeId)
                        e.copy(status = "CLOSED", exitTs = now / 1000L, closedAt = now,
                            exitQty = e.filledQty, exitPrice = px,
                            exitReason = if (px > 0.0) "sold by you at Trading 212 at ${"%.2f".format(px)}"
                                         else "sold by you at Trading 212")
                    else e
                }
                val stillHeld = entries.any { it.status == "OPEN" && it.symbol.equals(r.symbol, true) }
                s.copy(entries = entries,
                    adoptedNote = if (stillHeld) s.adoptedNote else s.adoptedNote - r.symbol.uppercase())
            }
            Log.w(TAG, "[${r.ns}] ${r.symbol} position used up by your sale at Trading 212")
            return who + "'s " + r.symbol + " position bought " +
                java.text.SimpleDateFormat("dd.MM", java.util.Locale.US).format(java.util.Date(r.buyAt)) + " is closed."
        }
        if (px > 0.0) {
            val seat = bookYourSale(ctx, r, ids, px, now)
            return who + "'s " + r.symbol + " position is closed at " + "%.2f".format(px) +
                (if (seat != null) "; its seat goes to $seat." else ".")
        }
        val res = StrategyLabRunner.withAlgoHeld(r.ns, 60_000L) {
            ManualIntervention.markSoldByHand(ctx, r.ns, r.symbol)
        }
        return if (res?.ok == true) who + "'s " + r.symbol + " position is closed as sold by you" +
                (if (res.seatTo != null) "; its seat goes to ${res.seatTo}." else ".")
               else who + "'s " + r.symbol + " position is closed at the next check."
    }

    /**
     * Every pass: algo positions whose shares have gone from the account
     * entirely, confirmed on two reads half an hour apart. Returns a few words
     * for the pass summary, blank when idle.
     */
    fun sweep(ctx: Context): String {
        if (!ConfigManager(ctx).hasT212Credentials) return ""
        val now = System.currentTimeMillis()
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ledger = T212LiveStore.load(ctx).trades.filter { T212WeekTargets.applies(it.ns) }
        val rows = ledger.filter {
            !isWatcher(it.ns) && it.status == "OPEN" && it.qty > 0.0 &&
                it.buyAt > 0L && now - it.buyAt > SETTLE_MS
        }
        // Forget absences of rows that are no longer open.
        val keys = rows.map { "${it.ns}|${it.paperTradeId}" }.toSet()
        p.all.keys.filter { it !in keys }.forEach { p.edit().remove(it).apply() }
        if (rows.isEmpty()) return ""
        val holdings = (BrokerHoldings.t212(ctx) as? BrokerHoldings.Result.Ok)?.holdings ?: return ""
        val heldTickers = holdings.filter { it.qty > 0.0 }.map { it.ticker.uppercase() }.toSet()
        val heldQty = holdings.groupBy { it.ticker.uppercase() }.mapValues { (_, v) -> v.sumOf { it.qty } }
        // A sale the strategy has already decided is the exit retry's business.
        val selling = T212ExitGuards.waitingKeys(ctx, ledger)
        // Every order the app's own machinery placed — the algos' ladders, the
        // 1-week-high targets, the safety stops. A sale under any other order id
        // was yours: a target or stop you set, or a sale in the broker's app.
        val ownIds: Set<Long> by lazy {
            val s = HashSet<Long>()
            try { T212LiveStore.load(ctx).trades.forEach { s.addAll(it.allBuyIds()); s.addAll(it.allSellIds()) } } catch (_: Exception) {}
            try { com.signalscope.app.data.T212TargetStore.load(ctx).targets.forEach {
                if (it.orderId > 0L) s.add(it.orderId); it.prevIds?.let { ids -> s.addAll(ids) } } } catch (_: Exception) {}
            try { com.signalscope.app.data.T212ExitGuardStore.load(ctx).guards.forEach {
                if (it.orderId > 0L) s.add(it.orderId); it.prevIds?.let { ids -> s.addAll(ids) } } } catch (_: Exception) {}
            s
        }
        val history: List<Trading212Client.HistoricalFill>? by lazy {
            (Trading212Client(ConfigManager(ctx)).fetchOrderHistory(limit = 50)
                as? Trading212Client.T212Result.Success)?.data
        }
        var closed = 0
        for (r in rows) {
            val key = "${r.ns}|${r.paperTradeId}"
            if (key in selling) continue
            // A scrap left after a sale is not the position — see the watchlist's
            // check, where a €1 buy kept a sold HAG holding "held".
            val bq = heldQty[r.t212Ticker.uppercase()] ?: 0.0
            if (r.t212Ticker.uppercase() in heldTickers && bq >= ownQty(r) * 0.1) {
                p.edit().remove(key).apply(); continue
            }
            // ── YOUR ORDER SOLD IT, AND THE BROKER SAYS AT WHAT PRICE ────────────
            // Proof needs no second reading: book it now, as a take-profit.
            val yours = history?.filter {
                it.ticker.equals(r.t212Ticker, true) && it.side.equals("SELL", true) &&
                    it.filledQuantity > 0.0 && it.fillPrice > 0.0 && it.orderId !in ownIds &&
                    executedAfter(it.dateExecuted, r.buyAt)
            }.orEmpty()
            if (yours.isNotEmpty()) {
                val q = yours.sumOf { it.filledQuantity }
                val px = yours.sumOf { it.fillPrice * it.filledQuantity } / q
                val seat = bookYourSale(ctx, r, yours.map { it.orderId }, px, now)
                p.edit().remove(key).apply()
                closed++
                try { LabNotifier.yourSaleBooked(ctx, r.ns, r.symbol, px, r.liveEntryPrice, seat, r.paperTradeId,
                    if (Spaces.baseOf(r.ns) == "de") "EUR" else "USD") } catch (_: Exception) {}
                continue
            }
            val first = p.getLong(key, 0L)
            if (first == 0L) {
                p.edit().putLong(key, now).apply()
                Log.w(TAG, "[${r.ns}] ${r.symbol} not in the account — asking again in half an hour")
                continue
            }
            if (now - first < ABSENT_CONFIRM_MS) continue
            val res = StrategyLabRunner.withAlgoHeld(r.ns, 60_000L) {
                ManualIntervention.markSoldByHand(ctx, r.ns, r.symbol)
            } ?: continue
            if (!res.ok) continue
            p.edit().remove(key).apply()
            closed++
            Log.w(TAG, "[${r.ns}] ${r.symbol} gone from the account since ${java.util.Date(first)} — " +
                "closed as sold by you, seat → ${res.seatTo ?: "cash"}")
            try { LabNotifier.soldOutsideApp(ctx, r.ns, r.symbol, res.seatTo, r.paperTradeId) }
            catch (_: Exception) {}
        }
        return if (closed > 0) "✋ $closed sold outside the app, closed" else ""
    }
}

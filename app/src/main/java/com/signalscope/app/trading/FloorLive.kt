package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.FloorAlgoStore
import com.signalscope.app.data.FloorAlgoStore.Algo
import com.signalscope.app.data.FloorAlgoStore.Trade
import com.signalscope.app.data.FloorLiveStore
import com.signalscope.app.data.FloorLiveStore.Row
import com.signalscope.app.network.Trading212Client
import com.signalscope.app.network.YahooFinanceClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * REAL MONEY FOR GERMANY'S MACD TURNS — the paper algo decides, Trading 212 follows.
 *
 * Built 31 Oct 2026 for "Macd swing", the best record on the phone (40 sold, 25 won,
 * +2.4% a trade before charges) in the one market with no commission. The paper
 * engine is not touched: it still buys on the 15-minute and hourly MACD turning up
 * with the 4-hour rising, and sells on its stop, target and time limit. After each
 * pass this looks at the book and copies, with the money set on the card:
 *  • A NEW PAPER BUY — one made after real money was switched on — is bought for
 *    the budget ÷ seats with the patient buy ladder, never more than [MAX_CHASE_PCT]
 *    above the paper price. Seen more than [MAX_LATE_MIN] minutes after the paper
 *    buy, or outside the session, it is not chased: the row says so.
 *  • A PAPER SALE sells exactly the shares that row bought, at once — the stop does
 *    not wait for the RV gate, as no algo sale does. Switching real money off stops
 *    new buys only; what is held still sells with its paper trade.
 *  • The real prices are read from Trading 212's order history afterwards.
 * Nothing rests at Trading 212 between passes: the stop and the target are the
 * paper algo's, judged on its 15-minute prices — so a real sale comes up to half
 * an hour after the paper one, as it does for the other algos.
 *
 * Sales are sized by claim, like every other sale in the app: one account, and
 * the watchlist or another algo may hold the same stock.
 */
object FloorLive {
    private const val TAG = "FloorLive"

    /** A paper buy older than this when first seen is not chased. */
    const val MAX_LATE_MIN = 60L
    /** The buy ladder never bids more than this above the paper price. */
    const val MAX_CHASE_PCT = 2.0
    /** A buy or sale still marked in progress this long after it began was cut off. */
    const val STUCK_MS = 20 * 60_000L
    const val MIN_BUDGET = 20.0
    /** The smallest real buy worth making — Trading 212's floor is €1. */
    const val MIN_PER_BUY = 5.0
    const val MAX_BUDGET = 5000.0

    /** Only Germany's MACD turns can hold real money — the one tested for it. */
    fun eligible(a: Algo): Boolean = a.kind == FloorAlgoStore.KIND_MACD && a.space == "de" &&
        a.settings.universeMode != FloorAlgoStore.UNI_PORTFOLIO

    // ── PURE — tested ────────────────────────────────────────────────────────

    /** Euros per real buy: the budget spread over the seats. */
    fun perSeat(budget: Double, seats: Int): Double =
        if (budget > 0.0 && seats > 0) budget / seats else 0.0

    /** Paper buys made since real money went on, that have no real row yet. */
    fun buysDue(trades: List<Trade>, rows: List<Row>, liveSince: Long): List<Trade> {
        val seen = rows.map { it.tradeId }.toHashSet()
        return trades.filter {
            it.status == "OPEN" && it.firstSec > 0L && it.firstPrice > 0.0 &&
                liveSince > 0L && it.firstSec * 1000L >= liveSince && it.id !in seen
        }.sortedBy { it.firstSec }
    }

    /** Real positions whose paper trade has been sold — or is gone from the book. */
    fun salesDue(trades: List<Trade>, rows: List<Row>): List<Pair<Row, Trade?>> {
        val byId = trades.associateBy { it.id }
        return rows.filter { it.status == FloorLiveStore.OPEN && it.qty > 0.0 }.mapNotNull { r ->
            val t = byId[r.tradeId]
            if (t == null || t.status == "SOLD") r to t else null
        }
    }

    /** Worth under Trading 212's €1 floor: it can never be sold on its own. Pure. */
    fun isDust(qty: Double, price: Double): Boolean =
        qty > 0.0 && price > 0.0 && qty * price < Trading212Client.MIN_POSITION_VALUE

    /** First seen too long after the paper buy to be worth chasing. */
    fun tooLate(t: Trade, nowMs: Long): Boolean = nowMs - t.firstSec * 1000L > MAX_LATE_MIN * 60_000L

    /** Shares and average price of the fills on [ids]; null when none are in. */
    fun fillOf(fills: List<Trading212Client.HistoricalFill>, ids: List<Long>): Pair<Double, Double>? {
        val mine = fills.filter { it.orderId in ids && it.filledQuantity > 0.0 && it.fillPrice > 0.0 }
        if (mine.isEmpty()) return null
        val q = mine.sumOf { it.filledQuantity }
        return q to mine.sumOf { it.fillPrice * it.filledQuantity } / q
    }

    /**
     * START AFRESH FOR REAL MONEY (the user's ask, 2 Nov 2026). Switched on with
     * every paper seat full, real money had nothing to copy until the paper algo
     * sold — up to ten trading days. This sets the paper record so far aside as a
     * ledger of its own, inside the algo, and empties every seat, so the next
     * signal is bought on paper and for real together. Pure.
     */
    fun afresh(a: Algo, nowMs: Long, today: String, why: String): Algo {
        if (a.trades.isEmpty()) return a
        val ledger = FloorAlgoStore.PaperLedger(atMs = nowMs,
            fromDay = a.trades.map { it.day }.filter { it.isNotBlank() }.minOrNull() ?: today,
            toDay = today, why = why, trades = a.trades)
        return a.copy(trades = emptyList(),
            orders = a.orders.map { if (it.status == "RESTING") it.copy(status = "ENDED") else it },
            paperLedgers = (a.paperLedgers + ledger).takeLast(5))
    }

    /**
     * [afresh] on the phone's record — under the engine's lock, so a pass in flight
     * cannot write the old trades back. Refused while real shares are held: the
     * paper trades are what sell them. @return a sentence for the card.
     */
    fun startAfresh(ctx: Context, id: String, why: String): String {
        val a0 = FloorAlgoStore.get(ctx, id) ?: return "algo not found"
        if (!eligible(a0)) return "only Germany's MACD-turns algos hold real money"
        if (FloorLiveStore.rowsFor(ctx, a0.ns).any { it.holds })
            return "it holds real shares — they sell with their paper trades, so the paper record cannot be set aside now"
        return FloorAlgoEngine.underLock(ctx) {
            var said = ""
            FloorAlgoStore.update(ctx, id) { a ->
                if (FloorLiveStore.rowsFor(ctx, a.ns).any { it.holds }) { said = "it holds real shares now"; return@update a }
                val n = a.trades.size
                val today = java.time.LocalDate.now(MarketHours.zoneOf(a.space) ?: java.time.ZoneId.of("Europe/Berlin")).toString()
                val out = afresh(a, System.currentTimeMillis(), today, why)
                said = if (n == 0) "nothing to set aside — every seat is already empty"
                       else "$n paper trade(s) set aside as a ledger; all ${a.settings.seats} seats are empty"
                out.copy(log = out.log + "${FloorAlgoStore.stamp()} · started afresh for real money — $said")
            }
            said
        } ?: "a check is running — try again in a minute"
    }

    // ── THE PASS ─────────────────────────────────────────────────────────────

    /** One order ladder at a time, across every algo — they dwell for minutes. */
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "floor-live").apply { isDaemon = true } }
    /** Rows with a ladder queued or running, so a pass never starts a second. */
    private val busy = ConcurrentHashMap.newKeySet<String>()
    private fun key(r: Row) = "${r.ns}|${r.tradeId}"

    /** After each pass of [a]. Quiet — and cheap — when it has no real money and holds nothing. */
    fun sync(ctx: Context, a: Algo) {
        if (!eligible(a)) return
        val s = a.settings
        val rows = FloorLiveStore.rowsFor(ctx, a.ns)
        if (!s.liveOn && rows.none { it.holds || (it.status == FloorLiveStore.CLOSED && it.exitPrice <= 0.0) }) return
        val cfg = ConfigManager(ctx)
        if (!cfg.hasT212Credentials) return
        val now = System.currentTimeMillis()
        val open = MarketHours.isRegularSession("de", now)
        try { settlePrices(ctx, cfg, rows) } catch (e: Exception) { Log.w(TAG, "[${a.name}] prices not read", e) }
        try { recoverStuck(ctx, cfg, a, now) } catch (e: Exception) { Log.w(TAG, "[${a.name}] stuck check failed", e) }
        // A POSITION WITH NO BUY ORDER ON ITS RECORD, JUDGED BY TRADING 212'S FILLS
        // (4 Nov 2026). S92: the whole 11.958-share holding recorded as this algo's
        // after an unreadable first look — no fill exists, so it was never bought.
        // MTX: 0.029 really bought on 2 Nov, its order id simply never written —
        // found in the fills, the id goes back on and the position stands. Rows the
        // first version of this check cleared wrongly come back the same way.
        try { checkNoOrderRows(ctx, cfg, a, now) } catch (e: Exception) { Log.w(TAG, "[${a.name}] no-order check failed", e) }
        // Positions already down to under €1 — left by a sale before the rule above.
        for (r in FloorLiveStore.rowsFor(ctx, a.ns)) {
            if (r.status != FloorLiveStore.OPEN || r.qty <= 0.0 || key(r) in busy) continue
            val px = if (r.entryPrice > 0.0) r.entryPrice else r.paperPrice
            if (isDust(r.qty, px)) {
                FloorLiveStore.update(ctx, r.ns, r.tradeId) {
                    it.copy(status = FloorLiveStore.CLOSED, qty = 0.0,
                        note = "the last ${"%.4f".format(r.qty)} share(s) are worth under €1 — Trading 212 will not sell them alone; left in the account")
                }
                Log.w(TAG, "[${a.name}] ${r.symbol}: ${r.qty} left is under €1 — closed as dust")
            }
        }

        // SALES FIRST, money on or off — nothing bought may be stranded.
        for ((r, t) in salesDue(a.trades, FloorLiveStore.rowsFor(ctx, a.ns))) {
            if (key(r) in busy) continue
            if (!open) continue                      // the next pass in the session sells it
            sell(ctx, cfg, a, r, t)
        }

        if (!s.liveOn) return
        val per = perSeat(s.liveBudget, s.seats)
        if (per < Trading212Client.MIN_POSITION_VALUE * 1.1) return
        val current = FloorLiveStore.rowsFor(ctx, a.ns)
        var held = current.count { it.holds }
        for (t in buysDue(a.trades, current, s.liveSince)) {
            val skip = when {
                tooLate(t, now) -> "seen ${(now - t.firstSec * 1000L) / 60_000L} minutes after the paper buy — not chased"
                !open -> "the market was closed when it was seen — not chased"
                held >= s.seats -> "every real seat was taken"
                else -> null
            }
            if (skip != null) {
                FloorLiveStore.upsert(ctx, Row(ns = a.ns, tradeId = t.id, symbol = t.symbol,
                    status = FloorLiveStore.SKIPPED, amount = per, paperPrice = t.firstPrice, buyAt = now, note = skip))
                Log.i(TAG, "[${a.name}] ${t.symbol}: not bought for real — $skip")
                // Its paper alert is held back for the real one — so this is that alert.
                try { LabNotifier.floorLiveProblem(ctx, a.name, a.ns, t.symbol,
                    "bought on paper at €${"%.2f".format(t.firstPrice)}, not for real — $skip") } catch (_: Exception) {}
                continue
            }
            buy(ctx, cfg, a, t, per, now)
            held++
        }
    }

    private fun buy(ctx: Context, cfg: ConfigManager, a: Algo, t: Trade, amount: Double, now: Long) {
        val client = Trading212Client(cfg)
        val ticker = client.resolveT212Ticker(t.symbol, "DE")
        if (ticker == null) {
            FloorLiveStore.upsert(ctx, Row(ns = a.ns, tradeId = t.id, symbol = t.symbol, status = FloorLiveStore.SKIPPED,
                amount = amount, paperPrice = t.firstPrice, buyAt = now,
                note = "not found at Trading 212 (${client.resolveFailureReason(t.symbol, "DE")})"))
            try { LabNotifier.floorLiveProblem(ctx, a.name, a.ns, t.symbol,
                "bought on paper, not for real — not found at Trading 212") } catch (_: Exception) {}
            return
        }
        val free = (client.fetchAccountSummary() as? Trading212Client.T212Result.Success)?.data?.freeFunds
        if (free != null && amount > free + 0.001) {
            FloorLiveStore.upsert(ctx, Row(ns = a.ns, tradeId = t.id, symbol = t.symbol, ticker = ticker,
                status = FloorLiveStore.SKIPPED, amount = amount, paperPrice = t.firstPrice, buyAt = now,
                note = "not enough free cash at Trading 212 (${"%.2f".format(free)} for ${"%.2f".format(amount)})"))
            try { LabNotifier.floorLiveProblem(ctx, a.name, a.ns, t.symbol, "not bought — not enough free cash at Trading 212") } catch (_: Exception) {}
            return
        }
        // CLAIMED BEFORE ANYTHING IS PLACED: the ladder outlives this pass, and the
        // next one must find the row and leave it alone.
        val row = Row(ns = a.ns, tradeId = t.id, symbol = t.symbol, ticker = ticker, status = FloorLiveStore.BUYING,
            amount = amount, paperPrice = t.firstPrice, buyAt = now)
        FloorLiveStore.upsert(ctx, row)
        busy.add(key(row))
        val app = ctx.applicationContext
        worker.execute {
            try {
                val ys = YahooFinanceClient.toYahooSymbol(t.symbol, "DE")
                val r = PatientExecutor.buy(cfg, ticker, ys, "DE", amount, t.firstPrice,
                    maxPrice = t.firstPrice * (1.0 + MAX_CHASE_PCT / 100.0))
                if (r.filled > 0.0) {
                    FloorLiveStore.update(app, a.ns, t.id) {
                        it.copy(status = FloorLiveStore.OPEN, qty = r.filled, buyIds = r.orderIds, note = r.note.take(160))
                    }
                    Log.w(TAG, "[${a.name}] ${t.symbol}: bought ${r.filled} for real (${"%.2f".format(amount)} €)")
                    try { LabNotifier.floorLiveBought(app, a.name, a.ns, t.symbol, r.filled, amount, t.firstPrice) } catch (_: Exception) {}
                } else {
                    val why = (r.error ?: r.note).ifBlank { "the buy did not fill" }
                    FloorLiveStore.update(app, a.ns, t.id) {
                        it.copy(status = FloorLiveStore.FAILED, buyIds = r.orderIds, note = why.take(200))
                    }
                    Log.w(TAG, "[${a.name}] ${t.symbol}: real buy did not fill — $why")
                    try { LabNotifier.floorLiveProblem(app, a.name, a.ns, t.symbol, "not bought — $why") } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "[${a.name}] ${t.symbol} real buy threw", e)
                FloorLiveStore.update(app, a.ns, t.id) {
                    if (it.status == FloorLiveStore.BUYING) it.copy(note = "the buy stopped: ${e.message ?: "error"} — checked again from the order history")
                    else it
                }
            } finally { busy.remove(key(row)) }
        }
    }

    private fun sell(ctx: Context, cfg: ConfigManager, a: Algo, r: Row, t: Trade?) {
        val reason = t?.exitReason?.ifBlank { null } ?: "gone from the paper book"
        val now = System.currentTimeMillis()
        val selling = r.copy(status = FloorLiveStore.SELLING, sellAt = now, exitReason = reason,
            paperExit = t?.sellPrice ?: 0.0, tries = r.tries + 1)
        FloorLiveStore.upsert(ctx, selling)
        busy.add(key(r))
        val others = com.signalscope.app.data.T212LiveStore.otherClaimsQty(ctx, r.ticker, r.ns, r.tradeId)
        val app = ctx.applicationContext
        worker.execute {
            try {
                val ys = YahooFinanceClient.toYahooSymbol(r.symbol, "DE")
                val ref = t?.sellPrice?.takeIf { it > 0.0 } ?: t?.lastPrice?.takeIf { it > 0.0 } ?: r.paperPrice
                val res = PatientExecutor.sell(cfg, r.ticker, ys, "DE", r.qty, ref,
                    targetQty = r.qty, claimedByOthers = others)
                val sold = res.filled.coerceAtMost(r.qty)
                val left = r.qty - sold
                // A REMAINDER WORTH UNDER €1 IS DONE WITH (4 Nov 2026). Trading 212 will
                // not sell it on its own, so it was retried every pass for ever (AIXA,
                // 0.001 left after a 0.294 fill) while blocking the stock's other sells.
                // It stays in the account unclaimed, where the next sale or target sweeps it.
                val dust = left > 0.0 && left * (if (ref > 0.0) ref else r.entryPrice) < Trading212Client.MIN_POSITION_VALUE
                if (sold > 0.0 && (left <= r.qty * 0.001 || dust)) {
                    FloorLiveStore.update(app, r.ns, r.tradeId) {
                        it.copy(status = FloorLiveStore.CLOSED, qty = 0.0, soldQty = it.soldQty + sold,
                            sellIds = it.sellIds + res.orderIds, note = "")
                    }
                    Log.w(TAG, "[${a.name}] ${r.symbol}: sold $sold for real ($reason)")
                    try { LabNotifier.floorLiveSold(app, a.name, a.ns, r.symbol, sold, reason, t?.let { pct(it) }) } catch (_: Exception) {}
                } else if (sold > 0.0) {
                    FloorLiveStore.update(app, r.ns, r.tradeId) {
                        it.copy(status = FloorLiveStore.OPEN, qty = left, soldQty = it.soldQty + sold,
                            sellIds = it.sellIds + res.orderIds, note = "part sold — the rest on the next pass")
                    }
                } else {
                    // NOTHING SOLD. If the account no longer has the shares at all, they
                    // went another way — your own sale — and there is nothing to retry.
                    val pos = (Trading212Client(cfg).fetchPortfolio() as? Trading212Client.T212Result.Success)?.data
                    val gone = pos != null && pos.none { it.ticker.equals(r.ticker, true) && it.quantity > 0.0 }
                    val why = (res.error ?: res.note).ifBlank { "the sale did not fill" }
                    FloorLiveStore.update(app, r.ns, r.tradeId) {
                        if (gone) it.copy(status = FloorLiveStore.CLOSED, qty = 0.0,
                            note = "no longer in the account — sold outside the app")
                        else it.copy(status = FloorLiveStore.OPEN, note = "sale did not go through: ${why.take(160)} — tried again next pass")
                    }
                    if (!gone && r.tries + 1 >= 3)
                        try { LabNotifier.floorLiveProblem(app, a.name, a.ns, r.symbol, "the $reason sale did not go through: $why") } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "[${a.name}] ${r.symbol} real sale threw", e)
                FloorLiveStore.update(app, r.ns, r.tradeId) {
                    if (it.status == FloorLiveStore.SELLING) it.copy(note = "the sale stopped: ${e.message ?: "error"} — checked again from the order history")
                    else it
                }
            } finally { busy.remove(key(r)) }
        }
    }

    private fun pct(t: Trade): Double? {
        val e = if (t.secondPrice > 0.0 && t.halves >= 2) (t.firstPrice + t.secondPrice) / 2 else t.firstPrice
        return if (e > 0.0 && t.sellPrice > 0.0) (t.sellPrice / e - 1.0) * 100.0 else null
    }

    /** Real average prices, from the order history, for rows still without them. */
    private fun settlePrices(ctx: Context, cfg: ConfigManager, rows: List<Row>) {
        val need = rows.filter {
            (it.entryPrice <= 0.0 && it.buyIds.isNotEmpty()) || (it.exitPrice <= 0.0 && it.sellIds.isNotEmpty())
        }
        if (need.isEmpty()) return
        val fills = (Trading212Client(cfg).fetchOrderHistory(50) as? Trading212Client.T212Result.Success)?.data ?: return
        for (r in need) {
            val inP = if (r.entryPrice <= 0.0) fillOf(fills, r.buyIds)?.second else null
            val outP = if (r.exitPrice <= 0.0) fillOf(fills, r.sellIds)?.second else null
            if (inP == null && outP == null) continue
            FloorLiveStore.update(ctx, r.ns, r.tradeId) {
                it.copy(entryPrice = inP ?: it.entryPrice, exitPrice = outP ?: it.exitPrice)
            }
        }
    }

    /**
     * A ladder cut off by the app stopping leaves its row BUYING or SELLING. Twenty
     * minutes on, with nothing running, the order history says what happened: fills
     * of that stock on that side since it began, not already some other row's.
     */
    private const val CLEARED_NOTE = "no buy order was placed"

    /** See [sync]. Does nothing unless Trading 212's fills can be read. */
    private fun checkNoOrderRows(ctx: Context, cfg: ConfigManager, a: Algo, now: Long) {
        val rows = FloorLiveStore.rowsFor(ctx, a.ns).filter {
            key(it) !in busy && it.ticker.isNotBlank() && it.buyIds.isEmpty() &&
                ((it.status == FloorLiveStore.OPEN && it.qty > 0.0) ||
                    (it.status == FloorLiveStore.FAILED && it.note.startsWith(CLEARED_NOTE)))
        }
        if (rows.isEmpty()) return
        val archive = try { T212ClosedTrades.loadArchive(ctx) } catch (e: Exception) { null }
        val recent = (Trading212Client(cfg).fetchOrderHistory(50) as? Trading212Client.T212Result.Success)?.data
        val fills = ((archive?.fills ?: emptyList()) + (recent ?: emptyList())).distinctBy { it.orderId }
        if (recent == null && archive == null) return
        val claimed = HashSet<Long>()
        FloorLiveStore.load(ctx).rows.forEach { claimed.addAll(it.buyIds); claimed.addAll(it.sellIds) }
        com.signalscope.app.data.T212LiveStore.load(ctx).trades.forEach { t ->
            if (t.buyOrderId > 0L) claimed.add(t.buyOrderId)
            t.buyOrderIds?.let { claimed.addAll(it) }
        }
        fun at(f: Trading212Client.HistoricalFill) = try { java.time.Instant.parse(f.dateExecuted).toEpochMilli() } catch (e: Exception) { 0L }
        val newest = fills.maxOfOrNull { at(it) } ?: 0L
        for (r in rows) {
            val mine = fills.filter {
                it.ticker.equals(r.ticker, true) && it.side.equals("BUY", true) && it.filledQuantity > 0.0 &&
                    it.orderId !in claimed && at(it) in (r.buyAt - 2 * 60_000L)..(r.buyAt + STUCK_MS + 60_000L)
            }
            if (mine.isNotEmpty()) {
                val q = mine.sumOf { it.filledQuantity }
                val px = mine.sumOf { it.fillPrice * it.filledQuantity } / q
                val ids = mine.map { it.orderId }.distinct()
                claimed.addAll(ids)
                FloorLiveStore.update(ctx, r.ns, r.tradeId) {
                    it.copy(status = FloorLiveStore.OPEN, qty = (q - it.soldQty).coerceAtLeast(0.0), buyIds = ids,
                        entryPrice = if (it.entryPrice > 0.0) it.entryPrice else px,
                        note = "its buy order was found in Trading 212's history")
                }
                Log.w(TAG, "[${a.name}] ${r.symbol}: buy found in the fills (${ids.joinToString()}) — ${"%.4f".format(q)} kept")
            } else if (r.status == FloorLiveStore.OPEN && newest > r.buyAt + STUCK_MS) {
                // The fills reach past the buy's window and none is there: never bought.
                FloorLiveStore.update(ctx, r.ns, r.tradeId) {
                    it.copy(status = FloorLiveStore.FAILED, qty = 0.0,
                        note = "$CLEARED_NOTE — the ${"%.4f".format(r.qty)} shares recorded were the holding already there")
                }
                Log.w(TAG, "[${a.name}] ${r.symbol}: ${r.qty} recorded with no buy in the fills — not this algo's, cleared")
            }
        }
    }

    private fun recoverStuck(ctx: Context, cfg: ConfigManager, a: Algo, now: Long) {
        val stuck = FloorLiveStore.rowsFor(ctx, a.ns).filter {
            (it.status == FloorLiveStore.BUYING && now - it.buyAt > STUCK_MS ||
                it.status == FloorLiveStore.SELLING && now - it.sellAt > STUCK_MS) && key(it) !in busy
        }
        if (stuck.isEmpty()) return
        val fills = (Trading212Client(cfg).fetchOrderHistory(50) as? Trading212Client.T212Result.Success)?.data ?: return
        val claimed = HashSet<Long>()
        FloorLiveStore.load(ctx).rows.forEach { claimed.addAll(it.buyIds); claimed.addAll(it.sellIds) }
        com.signalscope.app.data.T212LiveStore.load(ctx).trades.forEach { t ->
            if (t.buyOrderId > 0L) claimed.add(t.buyOrderId)
            if (t.sellOrderId > 0L) claimed.add(t.sellOrderId)
            t.buyOrderIds?.let { claimed.addAll(it) }; t.sellOrderIds?.let { claimed.addAll(it) }
        }
        fun at(f: Trading212Client.HistoricalFill) = try { java.time.Instant.parse(f.dateExecuted).toEpochMilli() } catch (e: Exception) { 0L }
        for (r in stuck) {
            val buying = r.status == FloorLiveStore.BUYING
            val since = (if (buying) r.buyAt else r.sellAt) - 60_000L
            val mine = fills.filter {
                it.ticker.equals(r.ticker, true) && it.side.equals(if (buying) "BUY" else "SELL", true) &&
                    it.filledQuantity > 0.0 && it.orderId !in claimed && at(it) in since..(since + STUCK_MS + 60_000L)
            }
            val q = mine.sumOf { it.filledQuantity }
            val ids = mine.map { it.orderId }.distinct()
            claimed.addAll(ids)
            FloorLiveStore.update(ctx, r.ns, r.tradeId) {
                if (buying) {
                    if (q > 0.0) it.copy(status = FloorLiveStore.OPEN, qty = q, buyIds = ids, note = "the buy was cut off; its fills were found")
                    else it.copy(status = FloorLiveStore.FAILED, note = "the buy was cut off before anything filled")
                } else {
                    val left = (it.qty - q).coerceAtLeast(0.0)
                    if (q > 0.0 && left <= it.qty * 0.001) it.copy(status = FloorLiveStore.CLOSED, qty = 0.0,
                        soldQty = it.soldQty + q, sellIds = it.sellIds + ids, note = "the sale was cut off; its fills were found")
                    else it.copy(status = FloorLiveStore.OPEN, qty = left, soldQty = it.soldQty + q, sellIds = it.sellIds + ids,
                        note = "the sale was cut off — the rest is sold on the next pass")
                }
            }
            Log.w(TAG, "[${a.name}] ${r.symbol}: ${if (buying) "buy" else "sale"} cut off — ${q} found filled")
        }
    }
}

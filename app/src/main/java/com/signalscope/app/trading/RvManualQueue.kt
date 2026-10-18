package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.network.Trading212Client
import java.io.File

/**
 * YOUR PATIENT BUYS AND SALES, WAITING ON THE RV GATE.
 *
 * The patient buttons start at once. Ticking "Wait for the RV gate" beside them
 * asks for the same care the algos get: the order is held until the price is
 * near the right end of today's range or moving its way — at most two hours of
 * trading time — and then runs exactly what the button would have run. See
 * [RvGate] for the rule.
 *
 * Kept on disk, so a wait survives the app being closed; looked at every minute
 * by the service's RV check while anything waits, and by every pass. Each order
 * that runs, or times out and runs anyway, sends a notification — you are not
 * watching when it happens.
 *
 * Trading 212 only. India's orders rest at Zerodha as triggers, and the crypto
 * space trades around the clock with no daily range in the same sense.
 */
object RvManualQueue {
    private const val TAG = "RvManualQueue"
    private const val FILE = "rv_manual_orders.json"
    private val gson = Gson()

    /** What the button would have run. */
    object Kind {
        const val T212_SELL = "t212Sell"     // patient sale of a holding
        const val T212_BUY = "t212Buy"       // plain patient buy
        const val DETAIL_BUY = "detailBuy"   // buy from the stock page, handed to the watchlist
        const val WATCH_BUY = "watchBuy"     // the watchlist's Buy
        const val ALGO_TP = "algoTp"         // an algo's "take profit now"
    }

    data class Order(
        val id: String,
        val kind: String,
        /** "DE" or "US". */
        val space: String,
        val symbol: String,
        val ticker: String = "",
        val yahooSymbol: String = "",
        val qty: Double = 0.0,
        val cash: Double = 0.0,
        val refPrice: Double = 0.0,
        val force: Boolean = false,
        /** For a take profit: the algo whose position it is. */
        val algoNs: String = "",
        val createdAt: Long = 0L,
        /** The gate's last look — see [RvGate.snapshot]. */
        val rvNote: String = "",
        /** WAITING, STARTED, DONE, CANCELLED. */
        val status: String = "WAITING",
        val why: String = "",
        val result: String = ""
    ) {
        val isSell get() = kind == Kind.T212_SELL || kind == Kind.ALGO_TP
        val ns get() = if (space.equals("DE", true)) "de" else "us"
    }

    private data class Book(val orders: List<Order> = emptyList())

    @Synchronized
    fun all(ctx: Context): List<Order> = try {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) emptyList()
        else gson.fromJson(f.readText(), Book::class.java)?.orders.orEmpty()
    } catch (e: Exception) { Log.w(TAG, "unreadable", e); emptyList() }

    @Synchronized
    private fun save(ctx: Context, orders: List<Order>) {
        // Finished orders are kept a day for the record, then dropped.
        val cutoff = System.currentTimeMillis() - 24 * 3_600_000L
        val keep = orders.filter { it.status == "WAITING" || it.status == "STARTED" || it.createdAt > cutoff }
        try { File(ctx.filesDir, FILE).writeText(gson.toJson(Book(keep))) }
        catch (e: Exception) { Log.w(TAG, "save failed", e) }
    }

    @Synchronized
    private fun update(ctx: Context, id: String, f: (Order) -> Order) {
        save(ctx, all(ctx).map { if (it.id == id) f(it) else it })
    }

    fun waiting(ctx: Context): List<Order> = all(ctx).filter { it.status == "WAITING" }

    fun waitingFor(ctx: Context, space: String): List<Order> =
        waiting(ctx).filter { it.space.equals(space, true) }

    /** Any waiting order whose market is trading now — what keeps the minute check alive. */
    fun anyDue(ctx: Context, now: Long = System.currentTimeMillis()): Boolean =
        waiting(ctx).any { MarketHours.isRegularSession(it.ns, now) }

    /**
     * Asked when you tap with the box ticked: is now already a good moment?
     * GO means the page runs the button's normal order straight away; WAIT means
     * the order is queued here and the page says so.
     * @return (queued, why, startsBy) — startsBy is the latest it will start.
     */
    fun submit(ctx: Context, o: Order): Triple<Boolean, String, Long> {
        val config = ConfigManager(ctx)
        val now = System.currentTimeMillis()
        val open = MarketHours.isRegularSession(o.ns, now)
        if (open) {
            val ticker = o.ticker.ifBlank { resolve(config, o.symbol, o.space) }
            val (gate, rd) = try {
                RvGate.check(config, ticker, o.symbol, o.ns,
                    if (o.isSell) RvGate.Side.SELL else RvGate.Side.BUY, 0L)
            } catch (e: Exception) { RvGate.Decision(true, "gate failed: ${e.message}") to null }
            if (gate.go) return Triple(false, gate.why, 0L)
            val q = o.copy(id = "rv${now}${(Math.random() * 1000).toInt()}", createdAt = now,
                ticker = ticker, status = "WAITING", why = gate.why, rvNote = RvGate.snapshot(rd, gate))
            save(ctx, all(ctx) + q)
            RvGate.ensureTick(ctx)
            return Triple(true, gate.why, now + RvGate.MAX_WAIT_MS)
        }
        // A shut market: the order waits for the open, and the two hours start then.
        val q = o.copy(id = "rv${now}${(Math.random() * 1000).toInt()}", createdAt = now,
            status = "WAITING", why = "the market is closed — the RV gate starts looking at the open")
        save(ctx, all(ctx) + q)
        return Triple(true, q.why, 0L)
    }

    fun cancel(ctx: Context, id: String): Boolean {
        val o = all(ctx).firstOrNull { it.id == id && it.status == "WAITING" } ?: return false
        update(ctx, o.id) { it.copy(status = "CANCELLED", result = "called off by you") }
        return true
    }

    /** The latest a waiting order will start: two trading hours after it began
     *  waiting, counted from today's open at the earliest. */
    fun startsBy(o: Order, now: Long = System.currentTimeMillis()): Long {
        val open = RvGate.todayOpenMs(o.ns, now)
        return maxOf(o.createdAt, open) + RvGate.MAX_WAIT_MS
    }

    private fun resolve(config: ConfigManager, symbol: String, space: String): String = try {
        Trading212Client(config).resolveT212Ticker(symbol.uppercase().removeSuffix(".DE"), space) ?: ""
    } catch (e: Exception) { "" }

    /**
     * Every minute while anything waits, and every pass: start each waiting order
     * whose moment has come or whose two hours are up. Blocking — a ladder takes
     * minutes — so only ever called from the service.
     */
    fun process(ctx: Context) {
        val now = System.currentTimeMillis()
        val config = ConfigManager(ctx)
        for (o in waiting(ctx)) {
            if (!MarketHours.isRegularSession(o.ns, now)) continue
            val ticker = o.ticker.ifBlank { resolve(config, o.symbol, o.space) }
            val (gate, rd) = try {
                RvGate.check(config, ticker, o.symbol, o.ns,
                    if (o.isSell) RvGate.Side.SELL else RvGate.Side.BUY, o.createdAt)
            } catch (e: Exception) { RvGate.Decision(true, "gate failed: ${e.message}") to null }
            if (!gate.go) {
                update(ctx, o.id) { it.copy(why = gate.why, ticker = ticker, rvNote = RvGate.snapshot(rd, gate)) }
                continue
            }
            update(ctx, o.id) { it.copy(status = "STARTED", why = gate.why, ticker = ticker) }
            Log.i(TAG, "${o.symbol} ${o.kind} released by the RV gate: ${gate.why}")
            val result = try { run(ctx, config, o.copy(ticker = ticker)) }
                         catch (e: Exception) { Log.e(TAG, "${o.symbol} ${o.kind} failed", e); "failed: ${e.message}" }
            update(ctx, o.id) { it.copy(status = "DONE", result = result) }
            try { LabNotifier.rvManualDone(ctx, o.space, o.symbol, !o.isSell, gate.timedOut, gate.why, result) }
            catch (_: Exception) {}
        }
    }

    /** Exactly what the button would have run. Returns a sentence for the notice. */
    private fun run(ctx: Context, config: ConfigManager, o: Order): String {
        val ys = o.yahooSymbol.ifBlank {
            com.signalscope.app.network.YahooFinanceClient.toYahooSymbol(o.symbol, o.space)
        }
        return when (o.kind) {
            Kind.T212_SELL -> {
                val r = PatientExecutor.sell(config, o.ticker, ys, o.space, o.qty, o.refPrice)
                if (r.error != null) "nothing sold: ${r.error}" else {
                    val handled = if (r.filled > 0.0) try {
                        T212HandSales.afterAppSale(ctx, r.ticker, r.orderIds, r.refPrice)
                    } catch (e: Exception) { "" } else ""
                    (if (r.filled > 0.0) "sold ${"%.4f".format(r.filled)}" +
                        (if (r.remaining > 0.0) ", ${"%.4f".format(r.remaining)} left unsold" else "")
                     else "nothing sold: ${r.note.ifBlank { "the ladder found no buyer" }}") +
                        (if (handled.isNotBlank()) ". $handled" else "")
                }
            }
            Kind.T212_BUY -> {
                val r = PatientExecutor.buy(config, o.symbol, ys, o.space, o.cash, o.refPrice)
                if (r.error != null) "nothing bought: ${r.error}"
                else if (r.filled > 0.0) "bought ${"%.4f".format(r.filled)}"
                else "nothing bought: ${r.note.ifBlank { "the ladder filled nothing" }}"
            }
            Kind.DETAIL_BUY, Kind.WATCH_BUY -> {
                val r = ManualEntryWatcher.buyAndHandOver(ctx, o.ns, o.symbol, o.cash, null,
                    force = o.kind == Kind.DETAIL_BUY || o.force)
                if (r.ok && r.filledQty > 0.0) "bought ${"%.4f".format(r.filledQty)} and handed to your watchlist"
                else "nothing bought: ${r.error.ifBlank { r.note.ifBlank { "the ladder filled nothing" } }}"
            }
            Kind.ALGO_TP -> {
                val r = AlgoTakeProfit.takeNow(ctx, o.algoNs, o.symbol)
                if (!r.ok) "not taken: ${r.error}"
                else "sold at ${"%.2f".format(r.exitPrice)}, booked to the algo at " +
                    "${"%+.2f".format(r.pctReturn)}%" + (if (r.note.isNotBlank()) ". ${r.note}" else "")
            }
            else -> "unknown order kind ${o.kind}"
        }
    }
}

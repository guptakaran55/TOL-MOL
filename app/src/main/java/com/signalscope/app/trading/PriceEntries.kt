package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.PriceEntryStore
import com.signalscope.app.network.Trading212Client
import com.signalscope.app.network.YahooFinanceClient

/**
 * BUY AT MY PRICE — Trading 212's GTT, kept in the app.
 *
 * Zerodha keeps a buy trigger (a GTT) for free and holds no money against it.
 * Trading 212 has no such thing: a limit buy rests at the broker and its cash is
 * held back until it fills or is cancelled — missing from the account all the
 * while. So here the TRIGGER lives in the app, and nothing at all is placed or
 * held at Trading 212 until the price gets there.
 *
 * Set a level and an amount. Below the price, it starts when the price FALLS to
 * the level (a dip entry); above it, when the price RISES to it (a breakout) —
 * decided once, when it is set, as a GTT decides. Checked every minute while the
 * market is open (the RV gate's minute check). When the level is reached the buy
 * starts EXACTLY as the stock page's Patient buy with its RV gate box ticked: the
 * RV gate picks the moment (at most two hours) and refuses to pay more than half
 * a day's move above the price at that moment without asking you; the patient
 * ladder buys; and when this market's watchlist has a strategy, the position is
 * handed to it — its exit rules, trailing stop and target take over.
 *
 * Armed until you call it off, at most a year. India keeps a real GTT at Zerodha
 * instead — see ManualEntryWatcher.buyIndiaAtPrice.
 *
 * WHAT IT CANNOT DO THAT A BROKER-SIDE ORDER CAN: it looks once a minute, and only
 * while the app's service runs. A dip that comes and goes inside one minute can be
 * missed, and nothing is watched while the phone is off or the app is stopped.
 */
object PriceEntries {
    private const val TAG = "PriceEntries"

    /** Armed at most this long, as a Zerodha GTT is. */
    const val LIFE_MS = 365L * 24 * 3_600_000L

    // ── THE RULE (pure) ──────────────────────────────────────────────────────

    /** Which way the price must move to reach [level] from [now]: true = up to it,
     *  false = down to it, null = it is the price now (or the price is unknown). */
    fun directionFor(level: Double, now: Double): Boolean? = when {
        !(now > 0.0) || !(level > 0.0) -> null
        level > now -> true
        level < now -> false
        else -> null
    }

    /** Has [price] reached [level], moving the way the entry waits for? */
    fun reached(rising: Boolean, level: Double, price: Double): Boolean =
        price > 0.0 && level > 0.0 && (if (rising) price >= level else price <= level)

    fun baseOf(space: String) = if (space.equals("DE", true)) "de" else "us"

    // ── SETTING ONE ──────────────────────────────────────────────────────────

    data class SetResult(val ok: Boolean, val error: String = "", val note: String = "",
                         val id: String = "", val rising: Boolean = false, val price: Double = 0.0)

    /**
     * Arm a buy of [cash] when [symbol] reaches [level]. Network: the price now,
     * to know which way it has to move.
     */
    fun set(ctx: Context, space: String, symbol: String, cash: Double, level: Double): SetResult {
        val sp = space.uppercase()
        if (sp != "DE" && sp != "US") return SetResult(false, "Buy at price on Trading 212 is for Germany and the US.")
        val sym = symbol.trim().uppercase().removeSuffix(".DE")
        if (sym.isBlank()) return SetResult(false, "No symbol given.")
        if (!(cash > 0.0)) return SetResult(false, "Enter an amount to invest.")
        if (!(level > 0.0)) return SetResult(false, "Enter the price to buy at.")
        val config = ConfigManager(ctx)
        if (!config.hasT212Credentials) return SetResult(false, "Trading 212 login is missing (Settings).")
        val ys = YahooFinanceClient.toYahooSymbol(sym, sp)
        val ticker = try { Trading212Client(config).resolveT212Ticker(sym, sp) } catch (_: Exception) { null } ?: ""
        val now = PatientExecutor.currentRefOrNull(config, ticker, ys, sp)
            ?: return SetResult(false, "No live price for $sym right now — it is needed to know whether your " +
                "price is above or below today's. Try again in a minute.")
        val rising = directionFor(level, now)
            ?: return SetResult(false, "That is the price right now — use Patient buy to buy at once.")
        val t = System.currentTimeMillis()
        val e = PriceEntryStore.Entry(id = "pe$t${(Math.random() * 1000).toInt()}", space = sp, symbol = sym,
            ticker = ticker, yahooSymbol = ys, cash = cash, level = level, rising = rising, setAt = now,
            createdAt = t, expiresAt = t + LIFE_MS, lastPrice = now, lastCheckAt = t)
        PriceEntryStore.add(ctx, e)
        try { RvGate.ensureTick(ctx) } catch (_: Exception) {}
        Log.i(TAG, "[$sp] $sym armed: buy ${"%.2f".format(cash)} when it ${if (rising) "rises" else "falls"} " +
            "to ${"%.2f".format(level)} (now ${"%.2f".format(now)})")
        val gap = Math.abs(level / now - 1.0) * 100.0
        return SetResult(true, id = e.id, rising = rising, price = now,
            note = "Watching $sym: when it ${if (rising) "rises" else "falls"} to ${"%.2f".format(level)} " +
                "(${"%.1f".format(gap)}% ${if (rising) "above" else "below"} today's ${"%.2f".format(now)}), " +
                "your Patient buy of ${"%.2f".format(cash)} starts through the RV gate. Nothing is placed at " +
                "Trading 212 and no cash is held until then.")
    }

    /** Call one off. False when it was no longer waiting. */
    fun cancel(ctx: Context, id: String): Boolean =
        PriceEntryStore.leaveWaiting(ctx, id) { it.copy(status = "CANCELLED", result = "called off by you") } != null

    fun waitingFor(ctx: Context, base: String): List<PriceEntryStore.Entry> =
        PriceEntryStore.waiting(ctx).filter { baseOf(it.space) == base }

    /** Is anything armed in a market that is open now? Keeps the minute check going. */
    fun anyDue(ctx: Context, now: Long = System.currentTimeMillis()): Boolean = try {
        PriceEntryStore.waiting(ctx).any { MarketHours.isRegularSession(baseOf(it.space), now) }
    } catch (_: Exception) { false }

    // ── THE MINUTE CHECK ─────────────────────────────────────────────────────

    /**
     * Look at every armed entry whose market is open. One whose price has been
     * reached becomes the stock page's Patient buy, through the RV gate.
     * Blocking: a buy the gate lets go at once runs its ladder here, as the RV
     * gate's own queue does — so only ever called from the service.
     */
    fun process(ctx: Context) {
        val config = ConfigManager(ctx)
        for (e in PriceEntryStore.waiting(ctx)) {
            val now = System.currentTimeMillis()
            if (now >= e.expiresAt) {
                if (PriceEntryStore.leaveWaiting(ctx, e.id) { it.copy(status = "EXPIRED", result = "lapsed after a year") } != null)
                    try { LabNotifier.priceEntryLapsed(ctx, e.space, e.symbol, e.level) } catch (_: Exception) {}
                continue
            }
            if (!MarketHours.isRegularSession(baseOf(e.space), now)) continue
            val price = try { PatientExecutor.currentRefOrNull(config, e.ticker, e.yahooSymbol, e.space) }
                        catch (ex: Exception) { Log.w(TAG, "${e.symbol}: no price: ${ex.message}"); null }
                ?: continue
            if (!reached(e.rising, e.level, price)) {
                PriceEntryStore.update(ctx, e.id) { if (it.status == "WAITING") it.copy(lastPrice = price, lastCheckAt = now) else it }
                continue
            }
            // REACHED. Recorded before anything is bought, so it can never start twice,
            // and a call-off that arrived first wins.
            PriceEntryStore.leaveWaiting(ctx, e.id) {
                it.copy(status = "TRIGGERED", triggeredAt = now, triggerPrice = price, lastPrice = price, lastCheckAt = now)
            } ?: continue
            Log.w(TAG, "[${e.space}] ${e.symbol} reached ${"%.2f".format(e.level)} (at ${"%.2f".format(price)}) — buying")
            // As the stock page decides: handed to the watchlist when it has a strategy.
            val managed = try { ManualEntryWatcher.isConfigured(ctx, baseOf(e.space)) } catch (_: Exception) { false }
            val o = RvManualQueue.Order(id = "",
                kind = if (managed) RvManualQueue.Kind.DETAIL_BUY else RvManualQueue.Kind.T212_BUY,
                space = e.space, symbol = e.symbol, ticker = e.ticker, yahooSymbol = e.yahooSymbol,
                cash = e.cash, refPrice = price)
            // Said before the buy runs: a ladder the gate lets go at once takes minutes.
            try {
                LabNotifier.priceEntryReached(ctx, e.space, e.symbol, e.level, price, e.rising,
                    "Starting through the RV gate" + (if (managed) ", then handed to your watchlist" else ""))
            } catch (_: Exception) {}
            val result = try { RvManualQueue.submitAndRun(ctx, o) }
                         catch (ex: Exception) { Log.e(TAG, "${e.symbol} buy failed", ex); "failed: ${ex.message}" }
            PriceEntryStore.update(ctx, e.id) { it.copy(result = result) }
        }
    }
}

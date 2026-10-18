package com.signalscope.app.trading

import android.app.AlarmManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.T212LiveStore
import com.signalscope.app.network.YahooFinanceClient

/**
 * THE RV GATE — Range–Velocity gate: WHEN an automatic patient buy or sale starts.
 *
 * An algo's decision used to go to the ladder the moment it fired, wherever the
 * price happened to be in the day. The gate asks two questions first:
 *
 *   SELL — start now if the price is in the TOP 40% of today's range (it is
 *          already near the day's best), or if it is FALLING on the 15-minute
 *          prices (waiting would only cost more). Otherwise — low in the range
 *          and rising — wait for it to come up.
 *   BUY  — start now if the price is in the BOTTOM 40% of today's range, or if
 *          it is RISING (waiting would only cost more). Otherwise — high in the
 *          range and drifting down — wait for it to come down.
 *
 * It only ever waits when time is on your side, and never longer than
 * [MAX_WAIT_MS]: after two hours the order starts anyway, with a notification.
 *
 *  • Early in the day the range is a few cents and says nothing, so the range
 *    test is used only once today's range is at least half a day's average move
 *    (ATR); before that the velocity alone decides.
 *  • "Velocity" is the live price against the close of the last FINISHED
 *    15-minute bar — or today's opening price before one has finished — with a
 *    small dead band (a twentieth of the ATR) so a flat tape is not read as a
 *    direction. Germany's bars arrive about fifteen minutes late; its live price
 *    is the real-time quote the ladders already use.
 *  • A stop never waits, and nothing is held back blind: no live price, or no
 *    prices to judge by once the session has settled, means go now.
 *
 * Applies to the algos' automatic buys and sales and the watchlist's automatic
 * strategy exits on Trading 212. Buttons you press start at once. India is not
 * gated: its exits rest at Zerodha as triggers.
 */
object RvGate {
    private const val TAG = "RvGate"

    const val MAX_WAIT_MS = 120 * 60_000L
    /** Fraction of today's range that counts as "near the top" / "near the bottom". */
    const val EDGE = 0.40
    /** Today's range must be at least this many ATRs before its shape means anything. */
    const val RANGE_MIN_ATR = 0.5
    /** Moves smaller than this many ATRs are "flat", not a direction. */
    const val DEAD_BAND_ATR = 0.05
    /** How long a gate with no 15-minute prices may wait at the open for the first. */
    const val OPENING_GRACE_MIN = 20L
    /** How often a waiting order is looked at again. */
    const val TICK_MS = 60_000L

    enum class Side { BUY, SELL }

    data class Decision(val go: Boolean, val why: String, val timedOut: Boolean = false)

    /**
     * The rule itself, with no I/O.
     *
     * @param price     live price; null or 0 when it could not be read
     * @param high      today's high (0 = unknown); the live price is folded in
     * @param low       today's low (0 = unknown); the live price is folded in
     * @param atr       a day's average move (0 = unknown)
     * @param velocity  live price minus the last finished 15-minute close; null
     *                  when there is nothing of today's to compare with
     * @param waitedMs  how long this order has already been held back
     * @param minutesSinceOpen minutes since today's regular session opened
     * @param belowStop for a sale: the price is at or under the position's stop
     */
    fun decide(side: Side, price: Double?, high: Double, low: Double, atr: Double,
               velocity: Double?, waitedMs: Long, minutesSinceOpen: Long,
               belowStop: Boolean = false): Decision {
        if (side == Side.SELL && belowStop) return Decision(true, "under its stop — stop sales never wait")
        if (price == null || !(price > 0.0)) return Decision(true, "no live price — not held back blind")
        if (waitedMs >= MAX_WAIT_MS) return Decision(true, "held back 2 hours — starting anyway", timedOut = true)

        val hi = if (high > 0.0) maxOf(high, price) else price
        val lo = if (low > 0.0) minOf(low, price) else price
        val range = hi - lo
        val rangeMeans = range > 0.0 && (atr <= 0.0 || range >= RANGE_MIN_ATR * atr)
        if (rangeMeans) {
            val pos = (price - lo) / range
            if (side == Side.SELL && pos >= 1.0 - EDGE)
                return Decision(true, "in the top 40% of today's range")
            if (side == Side.BUY && pos <= EDGE)
                return Decision(true, "in the bottom 40% of today's range")
        }

        if (velocity == null) {
            return if (minutesSinceOpen < OPENING_GRACE_MIN)
                Decision(false, "waiting for today's first 15-minute prices")
            else Decision(true, "no 15-minute prices to judge by — not held back blind")
        }
        val dead = if (atr > 0.0) DEAD_BAND_ATR * atr else price * 0.0005
        val where = if (rangeMeans) (if (side == Side.SELL) "low in today's range" else "high in today's range")
                    else "today's range still too narrow to judge"
        return when (side) {
            Side.SELL -> when {
                velocity < -dead -> Decision(true, "falling on the 15-minute prices")
                velocity > dead -> Decision(false, "$where and rising")
                else -> Decision(false, "$where and flat")
            }
            Side.BUY -> when {
                velocity > dead -> Decision(true, "rising on the 15-minute prices")
                velocity < -dead -> Decision(false, "$where and drifting down")
                else -> Decision(false, "$where and flat")
            }
        }
    }

    /** What the gate reads off the market for one stock. */
    data class Reading(val price: Double?, val high: Double, val low: Double, val atr: Double,
                       val velocity: Double?, val minutesSinceOpen: Long)

    /** When today's regular session opened, in wall-clock ms (0 when unknown). */
    fun todayOpenMs(base: String, now: Long = System.currentTimeMillis()): Long {
        val zone = MarketHours.zoneOf(base) ?: return 0L
        val open = MarketHours.openOf(base) ?: return 0L
        return java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .atTime(open).atZone(zone).toInstant().toEpochMilli()
    }

    /** Live price, today's range, ATR and velocity for [symbol] — network. */
    fun read(config: ConfigManager, ticker: String, symbol: String, base: String): Reading {
        val space = if (base == "de") "DE" else "US"
        val exch = if (base == "de") "DE" else "NASDAQ"
        val now = System.currentTimeMillis()
        val openMs = todayOpenMs(base, now)
        val mins = if (openMs > 0L) (now - openMs) / 60_000L else Long.MAX_VALUE
        val ys = YahooFinanceClient.toYahooSymbol(symbol, space)
        val live = try { PatientExecutor.currentRefOrNull(config, ticker, ys, space) } catch (e: Exception) { null }
        val atr = try { DailyVolatility.of(symbol, exch)?.atr ?: 0.0 } catch (e: Exception) { 0.0 }
        var high = 0.0; var low = 0.0; var velocity: Double? = null
        try {
            val res = YahooFinanceClient.fetchCandles15mRange(symbol.trim(), exch, "1d")
            val cs = (res as? YahooFinanceClient.CandleResult.Success)?.candles.orEmpty()
            val openSec = openMs / 1000L
            val today = cs.filter { it.timestamp >= openSec && it.close > 0.0 }
            if (today.isNotEmpty()) {
                high = today.maxOf { it.high.takeIf { h -> h > 0.0 } ?: it.close }
                low = today.minOf { it.low.takeIf { l -> l > 0.0 } ?: it.close }
                val nowSec = now / 1000L
                val lag = if (base == "de") FeedDelay.XETRA_SEC else 0L
                val done = today.filter { FeedDelay.isComplete(it.timestamp, 15 * 60L, nowSec, 0L, lag) }
                val ref = done.lastOrNull()?.close ?: today.first().open.takeIf { it > 0.0 }
                if (live != null && ref != null && ref > 0.0) velocity = live - ref
            }
        } catch (e: Exception) { Log.w(TAG, "$symbol 15-minute prices failed: ${e.message}") }
        return Reading(live, high, low, atr, velocity, mins)
    }

    /**
     * The gate for one waiting order. [since] is when it began waiting (0 = it
     * starts now); waiting is counted from today's open at the earliest, so a
     * wait that ran into the close does not carry the evening into tomorrow.
     */
    fun check(config: ConfigManager, ticker: String, symbol: String, ns: String, side: Side,
              since: Long, belowStop: Boolean = false): Pair<Decision, Reading?> {
        val base = Spaces.baseOf(ns)
        val now = System.currentTimeMillis()
        val waited = if (since > 0L) now - maxOf(since, todayOpenMs(base, now)) else 0L
        if (side == Side.SELL && belowStop)
            return Decision(true, "under its stop — stop sales never wait") to null
        val r = try { read(config, ticker, symbol, base) } catch (e: Exception) { null }
            ?: return Decision(true, "prices unreadable — not held back blind") to null
        val d = decide(side, r.price, r.high, r.low, r.atr, r.velocity, waited.coerceAtLeast(0L),
            r.minutesSinceOpen, belowStop)
        Log.i(TAG, "[$ns] $symbol ${side.name} ${if (d.go) "GO" else "WAIT"} — ${d.why} " +
            "(price ${r.price}, range ${r.low}–${r.high}, ATR ${"%.3f".format(r.atr)}, " +
            "velocity ${r.velocity?.let { "%.3f".format(it) }}, waited ${waited / 60_000}m)")
        return d to r
    }

    /**
     * WHAT THE GATE SAW, for the waiting-orders list: the price, where it sat in
     * today's range (0 = the low, 1 = the high; -1 while the range is too narrow
     * to mean anything), which way it was moving, and the day's ATR. Kept on the
     * held order so the list can show each condition as met or not without
     * fetching anything.
     */
    fun snapshot(r: Reading?, d: Decision): String = try {
        val o = org.json.JSONObject().put("at", System.currentTimeMillis()).put("why", d.why)
        if (r != null) {
            val price = r.price ?: 0.0
            val hi = if (r.high > 0.0) maxOf(r.high, price) else price
            val lo = if (r.low > 0.0) minOf(r.low, price) else price
            val range = hi - lo
            val meaningful = price > 0.0 && range > 0.0 && (r.atr <= 0.0 || range >= RANGE_MIN_ATR * r.atr)
            val dead = if (r.atr > 0.0) DEAD_BAND_ATR * r.atr else price * 0.0005
            o.put("price", price).put("atr", r.atr)
                .put("pos", if (meaningful) (price - lo) / range else -1.0)
                .put("dir", when {
                    r.velocity == null -> "unknown"
                    r.velocity > dead -> "rising"
                    r.velocity < -dead -> "falling"
                    else -> "flat"
                })
        }
        o.toString()
    } catch (e: Exception) { "" }

    /** True when a waiting-sale or waiting-buy note came from this gate. */
    const val NOTE = "RV gate"

    // ── THE MINUTE CHECK ────────────────────────────────────────────────────
    // A 15-minute pass is too coarse to catch the turn a waiting order is waiting
    // for, so while anything waits — and only then — the service is woken every
    // minute to look again. It stops itself when nothing waits or the market shuts.

    const val ACTION_TICK = "com.signalscope.RV_GATE_TICK"
    private const val REQUEST_CODE = 0x52A6

    /** Is any automatic order waiting on the gate in a market that is open now? */
    fun anyWaiting(ctx: Context, now: Long = System.currentTimeMillis()): Boolean = try {
        RvManualQueue.anyDue(ctx, now) || T212LiveStore.load(ctx).trades.any {
            // An order waits two hours at most; a mark older than that plus an hour
            // is left over from an order that has since moved on, not a wait.
            it.rvSince > 0L && now - it.rvSince < MAX_WAIT_MS + 60 * 60_000L &&
                (it.status == "RETRY" || it.status == "OPEN") &&
                T212LiveStore.isT212Ns(it.ns) &&
                MarketHours.isRegularSession(Spaces.baseOf(it.ns), now)
        }
    } catch (e: Exception) { false }

    /** Arm the next minute's check if something waits; do nothing otherwise. */
    fun ensureTick(ctx: Context) {
        if (!anyWaiting(ctx)) return
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = com.signalscope.app.service.ScanService.createIntent(ctx, ACTION_TICK)
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            val pi = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                android.app.PendingIntent.getForegroundService(ctx, REQUEST_CODE, intent, flags)
            else android.app.PendingIntent.getService(ctx, REQUEST_CODE, intent, flags)
            val at = SystemClock.elapsedRealtime() + TICK_MS
            val exact = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
                am.canScheduleExactAlarms()
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        } catch (e: Exception) { Log.w(TAG, "could not arm the minute check: ${e.message}") }
    }
}

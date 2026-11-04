package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.signalscope.app.data.CandleData
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.T212LiveStore
import java.time.Instant
import java.time.ZoneId

/**
 * THE RV GATE, LAID OPEN — what Waiting orders › RV gate analysis shows.
 *
 * For one order the gate is holding: today's 15-minute prices with the lines its
 * rule draws on them (the bottom or top 40% of the range, the "too near the high"
 * zone, the price a rise or fall has to cross, the dip and the turn, the price
 * limit), every look it has taken since the order began waiting, each way it can
 * start with how far the price is from it, and what waiting is doing for this
 * order and against it.
 *
 * And the gate's track record from its own journal ([RvJournal]): of the orders it
 * held, how the price it let them go at compared with the price at its first look
 * — by the reason it let them go. That is the honest answer to "does waiting pay".
 *
 * Nothing here decides anything: the verdict shown is [RvGate.decide] itself, on
 * the same reading the gate takes.
 */
object RvGateAnalysis {
    private const val TAG = "RvGateAnalysis"

    /** One way the gate can start this order, as things stand at this look.
     *  [met] null = cannot tell (no prices to judge by). [level] = the price that
     *  meets it, when there is one. */
    data class Way(val id: String, val title: String, val met: Boolean?, val level: Double?, val detail: String)

    /** A plain sentence about this order's wait: "plus" (what it gains), "minus"
     *  (what it risks or costs), "info". */
    data class Note(val tone: String, val text: String)

    /** The price lines the gate's rule draws on today's chart, as [RvGate.decide]
     *  computes them. Pure. */
    data class Levels(
        val hi: Double, val lo: Double, val range: Double,
        /** Is today's range wide enough (½ ATR) for the range test to count? */
        val rangeMeans: Boolean, val minRange: Double,
        /** 0 = today's low, 1 = today's high; null while the range means nothing. */
        val pos: Double?,
        /** A day's move — the ATR, or 2% of the price when that is unknown. */
        val unit: Double,
        /** Buy: at or under this starts it. Sell: at or over. (Range test.) */
        val zoneEdge: Double?,
        /** Buy only: over this (top 20%) a rise does not count. */
        val chaseFloor: Double?,
        /** Range too narrow: a move to here makes it count AND puts the price at
         *  the right end — buy: a fall to the high minus ½ ATR; sell: a rise to the
         *  low plus ½ ATR. */
        val widenAt: Double?,
        /** The last finished 15-minute close the direction is measured from. */
        val velRef: Double?,
        val dead: Double,
        /** Buy: over this is "rising". Sell: under this is "falling". */
        val goLine: Double?,
        val peak: Double, val trough: Double,
        /** Buy, no dip yet: a fall to here begins one. */
        val dipAt: Double?,
        /** Buy, in a dip: back up to here is the turn that starts it. */
        val turnAt: Double?
    )

    fun levels(side: RvGate.Side, price: Double, high: Double, low: Double, atr: Double,
               velocity: Double?, memory: RvGate.Memory?): Levels {
        val buy = side == RvGate.Side.BUY
        val mem = if (buy) memory else null
        val seenHigh = mem?.peak?.takeIf { it > 0.0 } ?: 0.0
        val hi = maxOf(if (high > 0.0) maxOf(high, price) else price, seenHigh)
        val lo = if (low > 0.0) minOf(low, price) else price
        val range = hi - lo
        val minRange = if (atr > 0.0) RvGate.RANGE_MIN_ATR * atr else 0.0
        val means = range > 0.0 && (atr <= 0.0 || range >= minRange)
        val pos = if (means) (price - lo) / range else null
        val unit = if (atr > 0.0) atr else price * 0.02
        val dead = if (atr > 0.0) RvGate.DEAD_BAND_ATR * atr else price * 0.0005
        val zoneEdge = if (!means) null else if (buy) lo + RvGate.EDGE * range else hi - RvGate.EDGE * range
        val chaseFloor = if (buy && means) hi - RvGate.TOP_ZONE * range else null
        val widenAt = if (means || atr <= 0.0) null else if (buy) hi - minRange else lo + minRange
        val velRef = velocity?.let { price - it }
        val goLine = velRef?.let { if (buy) it + dead else it - dead }
        val peak = mem?.peak ?: 0.0
        val trough = mem?.trough ?: 0.0
        val dipAt = if (buy && peak > 0.0 && trough <= 0.0) peak - RvGate.DIP_ATR * unit else null
        val turnAt = if (buy && trough > 0.0) trough + RvGate.TURN_ATR * unit else null
        return Levels(hi, lo, range, means, minRange, pos, unit, zoneEdge, chaseFloor, widenAt,
            velRef, dead, goLine, peak, trough, dipAt, turnAt)
    }

    private fun p(x: Double) = "%.2f".format(x)
    private fun inAtr(d: Double, unit: Double) = if (unit > 0.0) "%.2f ATR".format(Math.abs(d) / unit) else ""
    private fun hhmm(ms: Long): String = try {
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime().toString().take(5)
    } catch (_: Exception) { "" }

    /** Every way the order can start, met or not, with the price that meets it. Pure. */
    fun ways(side: RvGate.Side, price: Double, lv: Levels, velocity: Double?, minsOpen: Long,
             waitedMs: Long, startsBy: Long, cur: String): List<Way> {
        val buy = side == RvGate.Side.BUY
        val out = ArrayList<Way>()
        // 1. WHERE IN TODAY'S RANGE
        if (lv.rangeMeans && lv.zoneEdge != null) {
            val pct = Math.round((lv.pos ?: 0.0) * 100)
            val met = if (buy) (lv.pos ?: 1.0) <= RvGate.EDGE else (lv.pos ?: 0.0) >= 1.0 - RvGate.EDGE
            val gap = if (buy) price - lv.zoneEdge else lv.zoneEdge - price
            out += Way("range", if (buy) "Low in today's range (bottom 40%)" else "High in today's range (top 40%)",
                met, lv.zoneEdge,
                (if (buy) "At or under " else "At or over ") + "$cur${p(lv.zoneEdge)}. Now $cur${p(price)}, $pct% up the range" +
                    (if (met) " — met." else ", ${p(gap)} away (${inAtr(gap, lv.unit)})."))
        } else {
            out += Way("range", if (buy) "Low in today's range (bottom 40%)" else "High in today's range (top 40%)",
                false, lv.widenAt,
                "Today's range is ${p(lv.range)}, under the ½ ATR (${p(lv.minRange)}) it needs to mean anything, " +
                    "so this test is switched off for now." +
                    (lv.widenAt?.let {
                        if (buy) " A fall to $cur${p(it)} (${p(price - it)} under now) would widen it enough and leave " +
                            "the price at the bottom — that starts it."
                        else " A rise to $cur${p(it)} (${p(it - price)} over now) would widen it enough and leave " +
                            "the price at the top — that starts it."
                    } ?: ""))
        }
        // 2. WHICH WAY IT IS MOVING
        val dirTitle = if (buy) "Price rising on the 15-minute prices" else "Price falling on the 15-minute prices"
        if (velocity == null || lv.velRef == null || lv.goLine == null) {
            out += Way("dir", dirTitle, null, null,
                if (minsOpen < RvGate.OPENING_GRACE_MIN)
                    "No finished 15-minute bar of today yet. It waits up to ${RvGate.OPENING_GRACE_MIN} minutes after the open for one."
                else "No 15-minute prices of today came back. The gate does not hold an order blind, so it goes.")
        } else {
            val chase = buy && lv.rangeMeans && (lv.pos ?: 0.0) >= 1.0 - RvGate.TOP_ZONE
            val moving = if (buy) velocity > lv.dead else velocity < -lv.dead
            val vs = "the last finished 15-minute close $cur${p(lv.velRef)} " +
                (if (buy) "plus" else "minus") + " a dead band of ${p(lv.dead)} (0.05 ATR)"
            if (chase) {
                out += Way("dir", "Price rising — but not near today's high", false, lv.chaseFloor,
                    "In the top 20% of today's range (over $cur${p(lv.chaseFloor ?: lv.hi)}) a rise is a chase and " +
                        "does not count. " + (if (moving) "It IS rising now, " else "") +
                        "so here only a dip and a turn — or a fall back under $cur${p(lv.chaseFloor ?: lv.hi)} while rising — starts it.")
            } else {
                val gap = if (buy) lv.goLine - price else price - lv.goLine
                out += Way("dir", dirTitle, moving, lv.goLine,
                    (if (buy) "Over " else "Under ") + "$cur${p(lv.goLine)} — $vs. Now $cur${p(price)}" +
                        (if (moving) " — met." else ", ${p(Math.max(gap, 0.0))} to go (${inAtr(gap, lv.unit)})."))
            }
        }
        // 3. THE DIP AND THE TURN (buys only)
        if (buy) {
            when {
                lv.turnAt != null -> {
                    val met = price >= lv.turnAt
                    out += Way("dip", "A dip from its own high, then a turn up", met, lv.turnAt,
                        "It has dipped to $cur${p(lv.trough)}, under its high since waiting $cur${p(lv.peak)}. " +
                            "Back up to $cur${p(lv.turnAt)} (0.1 ATR off the low) starts it" +
                            (if (met) " — met." else " — ${p(lv.turnAt - price)} to go."))
                }
                lv.dipAt != null -> out += Way("dip", "A dip from its own high, then a turn up", false, lv.dipAt,
                    "Its high since it began waiting is $cur${p(lv.peak)}. A fall to $cur${p(lv.dipAt)} (0.2 ATR under " +
                        "it, ${p(price - lv.dipAt)} under now) and then a rise of ${p(RvGate.TURN_ATR * lv.unit)} off " +
                        "the low starts it — anywhere in the range.")
                else -> out += Way("dip", "A dip from its own high, then a turn up", false, null,
                    "Counted from its first look: a fall of 0.2 ATR under the highest price since, then 0.1 ATR back up.")
            }
        }
        // 4. THE TWO-HOUR LIMIT
        val left = ((RvGate.MAX_WAIT_MS - waitedMs) / 60_000L).coerceAtLeast(0L)
        out += Way("time", "Two trading hours waited", waitedMs >= RvGate.MAX_WAIT_MS, null,
            if (waitedMs >= RvGate.MAX_WAIT_MS) "The two hours are up — it starts at the next look, whatever the price."
            else "Starts anyway at ${hhmm(startsBy)} — $left min from now — at whatever the price is then.")
        return out
    }

    /** What waiting is doing for this order and against it, in sentences. Pure. */
    fun notes(side: RvGate.Side, base: String, price: Double, lv: Levels, velocity: Double?,
              minsOpen: Long, startsBy: Long, firstPrice: Double, firstAt: Long,
              limit: Double, limitIsAlgo: Boolean, velRefAt: Long, cur: String): List<Note> {
        val buy = side == RvGate.Side.BUY
        val out = ArrayList<Note>()
        // SO FAR: what acting at the first look would have got, against now.
        if (firstPrice > 0.0 && price > 0.0) {
            val d = if (buy) firstPrice - price else price - firstPrice
            val pct = d / firstPrice * 100.0
            val tone = when { Math.abs(d) < lv.dead -> "info"; d > 0.0 -> "plus"; else -> "minus" }
            out += Note(tone, (if (buy) "Buying" else "Selling") + " at its first look" +
                (if (firstAt > 0L) " (${hhmm(firstAt)})" else "") + " would have " + (if (buy) "paid" else "got") +
                " about $cur${p(firstPrice)}. Now $cur${p(price)} — so far the wait has " +
                (if (d >= 0.0) "saved" else "cost") + " $cur${p(Math.abs(d))} a share (${"%.2f".format(Math.abs(pct))}%).")
        }
        // A RANGE TOO NARROW TO READ.
        if (!lv.rangeMeans && lv.minRange > 0.0) {
            if (minsOpen >= 60L)
                out += Note("minus", "A quiet day: today's whole range is $cur${p(lv.range)}, only " +
                    "%.2f".format(lv.range / lv.unit) + " of a day's average move. Under ½ ATR the range test is off and " +
                    "only the direction decides — so a " + (if (buy) "buy drifting down waits for an up-tick" else
                    "sale drifting up waits for a down-tick") + ", and on a sleepy day may simply run to the 2-hour limit.")
            else out += Note("info", "Early in the session the range is still forming. Until it reaches ½ ATR " +
                "($cur${p(lv.minRange)}) only the direction decides.")
        }
        val dead = lv.dead
        if (velocity != null) {
            val flat = Math.abs(velocity) <= dead
            if (buy) {
                val chase = lv.rangeMeans && (lv.pos ?: 0.0) >= 1.0 - RvGate.TOP_ZONE
                when {
                    chase && velocity > dead -> {
                        out += Note("plus", "Near today's high and rising: it refuses to chase. Buying at once would " +
                            "pay close to the top of the day.")
                        out += Note("minus", "On a day that keeps climbing no dip comes; it then buys at the 2-hour " +
                            "limit, higher than now.")
                    }
                    velocity < -dead -> {
                        out += Note("plus", "Falling while it waits: every minute it waits the price is lower. " +
                            "It goes on the first move over $cur${p(lv.goLine ?: price)}.")
                        out += Note("minus", "That up-tick is measured against one 15-minute close, so the first " +
                            "bounce of a longer fall can start it — it does not wait for the bottom.")
                    }
                    flat -> out += Note("minus", "Flat: moves under $cur${p(dead)} (0.05 ATR) count as neither up nor " +
                        "down. A quiet stock can sit like this until the 2-hour limit.")
                }
            } else {
                when {
                    velocity > dead -> {
                        out += Note("plus", "Rising while it waits: the sale is given the chance to sell higher.")
                        out += Note("minus", "If it turns before reaching the top 40%, the first down-tick sells " +
                            "it — partway down from the best it saw.")
                    }
                    flat -> out += Note("minus", "Flat: moves under $cur${p(dead)} (0.05 ATR) count as neither up nor " +
                        "down. A quiet stock can sit like this until the 2-hour limit.")
                }
                out += Note("info", "A sale under its own stop never waits.")
            }
        }
        // HOW FRESH ITS SENSE OF DIRECTION IS.
        val refAt = if (velRefAt > 0L) " (the bar that closed at ${hhmm(velRefAt)})" else ""
        if (base == "de")
            out += Note("minus", "Germany's 15-minute bars arrive about 15 minutes late. The direction compares the " +
                "live price with the last bar that has arrived$refAt, so a turn is seen 15–30 minutes after it happens.")
        else
            out += Note("info", "The direction compares the live price with the last finished 15-minute close$refAt. " +
                "That reference moves every quarter hour, so \"rising\" can flip only because a new bar closed.")
        if (buy && limit > 0.0)
            out += Note("info", if (limitIsAlgo) "Its ceiling is $cur${p(limit)}: if the price is over it when the gate " +
                "lets it go, the algo does not buy at all."
            else "Its price limit is $cur${p(limit)} (½ a day's move over where you asked): if the price is over it " +
                "when the gate lets it go, it does not buy — it asks you.")
        if (lv.minRange <= 0.0)
            out += Note("minus", "No day's average move (ATR) could be read, so its distances use 2% of the price — " +
                "which may be far too wide or too narrow for this stock.")
        if (startsBy > 0L)
            out += Note("info", "Worst case: it starts at ${hhmm(startsBy)} at whatever the price is then. The gate " +
                "never cancels an order — it only chooses the moment.")
        return out
    }

    // ── ONE WAITING ORDER ───────────────────────────────────────────────────

    private class Target(val key: String, val side: RvGate.Side, val ns: String, val symbol: String,
                         val ticker: String, val since: Long, val memory: RvGate.Memory?,
                         val limit: Double, val limitIsAlgo: Boolean, val from: String, val amount: Double)

    private fun target(ctx: Context, src: String, ns: String, id: String): Target? {
        if (src == "you") {
            val o = RvManualQueue.all(ctx).firstOrNull { it.id == id } ?: return null
            return Target("you|${o.id}", if (o.isSell) RvGate.Side.SELL else RvGate.Side.BUY, o.ns, o.symbol,
                o.ticker, o.createdAt, if (o.rvPeak > 0.0) RvGate.Memory(o.rvPeak, o.rvTrough) else null,
                if (o.isSell) 0.0 else o.capLimit, false,
                when (o.kind) {
                    RvManualQueue.Kind.WATCH_BUY -> "You · watchlist Buy"
                    RvManualQueue.Kind.ALGO_TP -> "You · take profit"
                    else -> "You · stock page"
                }, if (o.isSell) o.qty else o.cash)
        }
        val r = T212LiveStore.get(ctx, id, ns) ?: return null
        val sell = r.status == "OPEN" || r.status == "EXITING"
        val side = if (sell) RvGate.Side.SELL else RvGate.Side.BUY
        val watch = Spaces.bucketIdOf(r.ns) == com.signalscope.app.data.ManualWatchStore.ID
        val name = if (watch) "Your watchlist" else "Algo · " + (try {
            com.signalscope.app.data.BucketStore.get(ctx, r.ns)?.name
        } catch (_: Exception) { null } ?: r.ns)
        return Target(RvJournal.algoKey(r.ns, r.paperTradeId, side), side, r.ns, r.symbol, r.t212Ticker,
            r.rvSince, if (!sell && r.rvSince > 0L && r.rvPeak > 0.0) RvGate.Memory(r.rvPeak, r.rvTrough) else null,
            0.0, true, name, if (sell) r.qty else r.amount)
    }

    /** The gate's first look at the order behind [key] — its price and time — from the journal. */
    private fun firstLook(lines: List<String>, key: String): Pair<Double, Long>? {
        val needle = "\"key\":\"" + key.replace("\"", "\\\"") + "\""
        for (l in lines.asReversed()) {
            if (!l.contains("\"ev\":\"first\"") || !l.contains(needle)) continue
            val o = try { JsonParser.parseString(l).asJsonObject } catch (_: Exception) { continue }
            val px = o.num("price") ?: continue
            return px to (o.long("at") ?: 0L)
        }
        return null
    }

    /**
     * Everything the analysis screen shows for one waiting order. Network: the
     * same reading the gate takes. [src] "you" (your button order, [id] its id) or
     * "algo" (an algo's or the watchlist's order, [ns] and [id] its paper trade).
     */
    fun forOrder(ctx: Context, src: String, ns: String, id: String): Map<String, Any?> {
        val t = target(ctx, src, ns, id) ?: return mapOf("ok" to false, "error" to "That order is no longer waiting.")
        val config = ConfigManager(ctx)
        val base = Spaces.baseOf(t.ns)
        val cur = if (base == "de") "€" else "$"
        val now = System.currentTimeMillis()
        val ticker = t.ticker.ifBlank {
            try { com.signalscope.app.network.Trading212Client(config)
                .resolveT212Ticker(t.symbol.uppercase().removeSuffix(".DE"), base.uppercase()) ?: "" }
            catch (_: Exception) { "" }
        }
        val bars = ArrayList<CandleData>()
        val rd = try { RvGate.read(config, ticker, t.symbol, base, bars) } catch (e: Exception) {
            Log.w(TAG, "${t.symbol}: reading failed: ${e.message}"); null
        } ?: return mapOf("ok" to false, "error" to "Prices for ${t.symbol} could not be read just now.")
        val openMs = RvGate.todayOpenMs(base, now)
        val closeMs = try {
            val zone = MarketHours.zoneOf(base); val close = MarketHours.closeOf(base)
            if (zone == null || close == null) 0L
            else Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atTime(close).atZone(zone).toInstant().toEpochMilli()
        } catch (_: Exception) { 0L }
        val since = if (t.since > 0L) t.since else now
        val waited = (now - maxOf(since, openMs)).coerceAtLeast(0L)
        val startsBy = maxOf(since, openMs) + RvGate.MAX_WAIT_MS
        val price = rd.price ?: 0.0
        val mem = if (t.side == RvGate.Side.BUY && price > 0.0) RvGate.remember(t.memory, price, rd.atr) else null
        val d = RvGate.decide(t.side, rd.price, rd.high, rd.low, rd.atr, rd.velocity, waited,
            rd.minutesSinceOpen, memory = mem)
        val lv = levels(t.side, price, rd.high, rd.low, rd.atr, rd.velocity, mem)

        val openSec = openMs / 1000L
        val today = bars.filter { it.timestamp >= openSec && it.close > 0.0 }.sortedBy { it.timestamp }
        val prevClose = bars.filter { it.timestamp < openSec && it.close > 0.0 }.maxByOrNull { it.timestamp }?.close
        val lag = if (base == "de") FeedDelay.XETRA_SEC else 0L
        val refBar = today.lastOrNull { FeedDelay.isComplete(it.timestamp, 15 * 60L, now / 1000L, 0L, lag) }
        val velRefAt = refBar?.let { (it.timestamp + 15 * 60L) * 1000L } ?: 0L

        val limit = if (t.side == RvGate.Side.SELL) 0.0 else if (!t.limitIsAlgo) t.limit else try {
            val r = T212LiveStore.get(ctx, id, ns)
            val cfg = com.signalscope.app.data.StrategyLabStore.activeConfig(ctx, t.ns)
            if (r == null) 0.0 else T212LiveMirror.entryCeiling(r.paperEntryPrice, cfg.exit.stopLossPct,
                cfg.exit.stopLossEnabled, rd.atr)
        } catch (_: Exception) { 0.0 }
        val lines = RvJournal.lines(ctx, now - 3L * 24 * 3_600_000L)
        val first = firstLook(lines, t.key)
        val trail = RvJournal.trail(ctx, t.key).filter { it.at >= maxOf(since, openMs) - 60_000L }

        return mapOf(
            "ok" to true, "symbol" to t.symbol, "side" to t.side.name, "space" to base.uppercase(),
            "from" to t.from, "amount" to t.amount, "cur" to cur,
            "now" to now, "openMs" to openMs, "closeMs" to closeMs, "since" to since, "startsBy" to startsBy,
            "waitedMin" to waited / 60_000L, "minsOpen" to rd.minutesSinceOpen.takeIf { it < 100_000L },
            "price" to rd.price, "high" to lv.hi, "low" to lv.lo, "atr" to rd.atr, "velocity" to rd.velocity,
            "prevClose" to prevClose,
            "bars" to today.map { listOf(it.timestamp * 1000L, it.open, it.high, it.low, it.close) },
            "barLagMin" to lag / 60L,
            "decision" to mapOf("go" to d.go, "why" to d.why, "timedOut" to d.timedOut),
            "levels" to mapOf(
                "rangeMeans" to lv.rangeMeans, "minRange" to lv.minRange, "range" to lv.range, "pos" to lv.pos,
                "zoneEdge" to lv.zoneEdge, "chaseFloor" to lv.chaseFloor, "widenAt" to lv.widenAt,
                "velRef" to lv.velRef, "velRefAt" to velRefAt.takeIf { it > 0L }, "dead" to lv.dead,
                "goLine" to lv.goLine, "peak" to lv.peak.takeIf { it > 0.0 }, "trough" to lv.trough.takeIf { it > 0.0 },
                "dipAt" to lv.dipAt, "turnAt" to lv.turnAt, "limit" to limit.takeIf { it > 0.0 },
                "limitIsAlgo" to t.limitIsAlgo, "unit" to lv.unit),
            "ways" to ways(t.side, price, lv, rd.velocity, rd.minutesSinceOpen, waited, startsBy, cur),
            "notes" to notes(t.side, base, price, lv, rd.velocity, rd.minutesSinceOpen, startsBy,
                first?.first ?: 0.0, first?.second ?: 0L, limit, t.limitIsAlgo, velRefAt, cur),
            "firstPrice" to first?.first, "firstAt" to first?.second,
            "trail" to trail.map { listOf(it.at, it.price, if (it.go) 1 else 0, it.why) })
    }

    // ── THE TRACK RECORD ────────────────────────────────────────────────────

    /** One group of held orders: how the price the gate let them go at compared
     *  with the price at its first look. [avgPct] > 0 = waiting got the better price. */
    data class Group(val reason: String, val n: Int, val avgPct: Double, val better: Int,
                     val avgWaitMin: Double, val bestPct: Double, val worstPct: Double)

    data class Record(
        val days: Int,
        /** Orders the gate looked at (you asked for it, or an algo's). */
        val looked: Int,
        /** …let go at the first look: the gate changed nothing. */
        val atOnce: Int,
        /** …held, then let go. */
        val held: Int,
        /** …held and not let go (called off, lapsed, or still waiting). */
        val neverLetGo: Int,
        val all: Group?,
        val groups: List<Group>,
        /** Held orders with a known fill: fill against the first look's price. */
        val fills: Int, val avgFillPct: Double?
    )

    /** The reason a release gave, in a few words. */
    fun reasonOf(why: String, buy: Boolean): String = when {
        why.contains("2 hours") -> "2-hour limit"
        why.contains("bottom 40%") -> "bottom of range"
        why.contains("top 40%") -> "top of range"
        why.contains("dipped") -> "dip and turn"
        why.contains("rising") -> "rising"
        why.contains("falling") -> "falling"
        why.contains("stop") -> "under its stop"
        why.contains("gate failed") -> "gate failed"
        else -> if (buy) "no prices to judge by" else "no prices to judge by (sale)"
    }

    private fun JsonObject.num(k: String): Double? =
        if (has(k) && get(k).isJsonPrimitive && get(k).asJsonPrimitive.isNumber) get(k).asDouble else null
    private fun JsonObject.long(k: String): Long? = num(k)?.toLong()
    private fun JsonObject.str(k: String): String? =
        if (has(k) && get(k).isJsonPrimitive) get(k).asString else null
    private fun JsonObject.bool(k: String): Boolean? =
        if (has(k) && get(k).isJsonPrimitive && get(k).asJsonPrimitive.isBoolean) get(k).asBoolean else null

    private class Episode(val at: Long, val price: Double?, val buy: Boolean) {
        var relAt = 0L; var relPrice: Double? = null; var relWhy = ""; var waitedMin = 0L
        var fill: Double? = null
    }

    /**
     * The gate's track record in [space] ("DE"/"US") for [side] ("BUY"/"SELL"),
     * from journal [lines]. Pure. An episode runs from a "first" look to its
     * release; an algo order retried later starts a new one. Orders placed with
     * the box unticked are left out — the gate did not hold them.
     */
    fun record(lines: List<String>, space: String, side: String, days: Int): Record {
        val buy = side.equals("BUY", true)
        val latest = HashMap<String, Episode>()
        val eps = ArrayList<Episode>()
        for (l in lines) {
            val o = try { JsonParser.parseString(l).asJsonObject } catch (_: Exception) { continue }
            val key = o.str("key") ?: continue
            when (o.str("ev")) {
                "first" -> {
                    if (!(o.str("space") ?: "").equals(space, true)) { latest.remove(key); continue }
                    if (!(o.str("side") ?: "").equals(side, true)) { latest.remove(key); continue }
                    if (o.bool("asked") == false) { latest.remove(key); continue }
                    val e = Episode(o.long("at") ?: 0L, o.num("price"), buy)
                    latest[key] = e; eps += e
                }
                "release" -> latest[key]?.takeIf { it.relAt == 0L }?.let { e ->
                    e.relAt = o.long("at") ?: 0L; e.relPrice = o.num("price")
                    e.relWhy = o.str("why") ?: ""; e.waitedMin = o.long("waitedMin") ?: 0L
                }
                "fill" -> latest[key]?.takeIf { it.relAt > 0L && it.fill == null }?.let { e -> e.fill = o.num("price") }
            }
        }
        val released = eps.filter { it.relAt > 0L }
        val atOnce = released.count { it.waitedMin == 0L && it.relAt - it.at < 90_000L }
        val held = released.filter { !(it.waitedMin == 0L && it.relAt - it.at < 90_000L) }
        fun pct(e: Episode, px: Double?): Double? {
            val f = e.price ?: return null
            if (!(f > 0.0) || px == null || !(px > 0.0)) return null
            return (if (buy) f - px else px - f) / f * 100.0
        }
        fun group(name: String, es: List<Episode>): Group? {
            val xs = es.mapNotNull { e -> pct(e, e.relPrice)?.let { e to it } }
            if (xs.isEmpty()) return null
            return Group(name, xs.size, xs.map { it.second }.average(), xs.count { it.second > 0.0 },
                xs.map { it.first.waitedMin.toDouble() }.average(), xs.maxOf { it.second }, xs.minOf { it.second })
        }
        val groups = held.groupBy { reasonOf(it.relWhy, buy) }.mapNotNull { (k, v) -> group(k, v) }
            .sortedByDescending { it.n }
        val fx = held.mapNotNull { pct(it, it.fill) }
        return Record(days, eps.size, atOnce, held.size, eps.size - released.size, group("all held", held), groups,
            fx.size, if (fx.isEmpty()) null else fx.average())
    }

    /** The record for the screen: the last [days] days of [space]'s [side]. */
    fun recordFor(ctx: Context, space: String, side: String, days: Int = 30): Map<String, Any?> = try {
        val r = record(RvJournal.lines(ctx, System.currentTimeMillis() - days * 24L * 3_600_000L),
            space.uppercase(), side.uppercase(), days)
        mapOf("ok" to true, "record" to r)
    } catch (e: Exception) { mapOf("ok" to false, "error" to (e.message ?: "record unreadable")) }
}

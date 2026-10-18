package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.BucketStore
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.ManualWatchStore
import com.signalscope.app.data.PositionPeakStore
import com.signalscope.app.data.StrategyLabStore
import com.signalscope.app.data.T212LiveStore
import com.signalscope.app.data.T212TargetStore
import com.signalscope.app.data.ZerodhaLiveStore
import com.signalscope.app.network.ZerodhaClient

// ════════════════════════════════════════════════════════════════════════════
// WHAT YOU HOLD, AT A GLANCE — drawn by the home-screen widget ([PositionWidget])
// ════════════════════════════════════════════════════════════════════════════
// Every real position the app bought (Trading 212 and Zerodha, algos and the
// watchlist alike), sorted into the three things worth knowing without opening
// the app:
//
//   🔴 NEAR ITS STOP     within [NEAR_STOP_PCT] of the tightest stop watching it
//   🎯 NEAR ITS TARGET   within [NEAR_TARGET_PCT] of the nearest target
//   🟢 NEAR ITS HIGH     in profit, and within [NEAR_HIGH_PCT] of the highest
//                        price since it was bought
//
// A position shows once, under the most pressing of the three. It only REPORTS:
// nothing here places, moves or cancels an order.
//
// "IN PROFIT" IS PART OF THE HIGH RULE ON PURPOSE. The high since a buy starts
// at what was paid, so without it a stock bought yesterday and down 2% would be
// "near its high" — every fresh position would be listed and the line would
// mean nothing.
// ════════════════════════════════════════════════════════════════════════════

object PositionRadar {
    private const val TAG = "PositionRadar"

    const val NEAR_HIGH_PCT = 5.0
    const val NEAR_TARGET_PCT = 2.0
    const val NEAR_STOP_PCT = 2.0
    /** About what an expanded notification shows before Android cuts it off. */
    const val MAX_LINES = 6
    /** Out of hours, a last price this recent is still the price. */
    private const val OFF_HOURS_MAX_MS = 4L * 24 * 60 * 60_000L

    data class Pos(
        val key: String, val symbol: String, val market: String,
        val paid: Double, val price: Double, val peak: Double,
        /** 0 = no target / no stop known. */
        val target: Double, val stop: Double,
        /** When the price was last current, epoch ms; 0 = never priced. Set
         *  only when the price is NOT current — see [priceStale]. */
        val staleSinceMs: Long = 0L,
        /** The price is too old to judge anything by. */
        val priceStale: Boolean = false,
        /** The highest prices of the last 5 and 3 finished trading days — the
         *  short-term highs. 0 = not known. */
        val weekHigh: Double = 0.0,
        val threeHigh: Double = 0.0,
        /** Who manages its exit and where its stop and target actually rest —
         *  the widget's second line: "watchlist · stop 80.12 checked by the app ·
         *  target 83.90 waiting at Trading 212". Blank when not worked out. */
        val exitBy: String = ""
    ) {
        val gainPct: Double get() = if (paid > 0.0 && price > 0.0) (price / paid - 1.0) * 100.0 else 0.0
        val underPeakPct: Double get() = if (peak > 0.0 && price > 0.0) (1.0 - price / peak) * 100.0 else Double.NaN
        val toTargetPct: Double get() = if (target > 0.0 && price > 0.0) (target / price - 1.0) * 100.0 else Double.NaN
        val aboveStopPct: Double get() = if (stop > 0.0 && price > 0.0) (price / stop - 1.0) * 100.0 else Double.NaN
    }

    enum class Kind { NO_PRICE, NEAR_STOP, NEAR_TARGET, NEAR_HIGH, SHORT_HIGH }

    /** How close under a short-term high still counts as AT it. */
    const val AT_SHORT_HIGH_PCT = 0.3

    /**
     * AT A SHORT-TERM HIGH — at or above the highest price of the last week (or,
     * failing that, of the last three days), in profit or not.
     *
     * The user's reason (16 Oct 2026): the top of the week is where a holding's
     * loss is the smallest it has been all week — the moment to decide on it —
     * and the local high a campaign's swing would sell at. So unlike the high
     * since buy, it does not ask for a profit: a stock 15% under what was paid,
     * breaking its week's high, is exactly the case worth seeing.
     */
    fun shortHighOf(p: Pos): Pair<String, Double>? {
        if (p.price <= 0.0) return null
        val f = 1.0 - AT_SHORT_HIGH_PCT / 100.0
        if (p.weekHigh > 0.0 && p.price >= p.weekHigh * f) return "1-week" to p.weekHigh
        if (p.threeHigh > 0.0 && p.price >= p.threeHigh * f) return "3-day" to p.threeHigh
        return null
    }

    /** The most pressing thing about a position, or null when nothing is near.
     *  Through a level counts as near it — that is the most pressing case of all.
     *
     *  NO CURRENT PRICE COMES FIRST. A frozen feed does not just make the line
     *  wrong — the stops measuring that price cannot act on a fall either, which
     *  is the one thing about a position worth knowing before anything else. */
    fun kindOf(p: Pos): Kind? = when {
        p.priceStale || p.price <= 0.0 -> Kind.NO_PRICE
        p.stop > 0.0 && p.aboveStopPct <= NEAR_STOP_PCT -> Kind.NEAR_STOP
        p.target > 0.0 && p.toTargetPct <= NEAR_TARGET_PCT -> Kind.NEAR_TARGET
        p.paid > 0.0 && p.price > p.paid && p.underPeakPct <= NEAR_HIGH_PCT -> Kind.NEAR_HIGH
        shortHighOf(p) != null -> Kind.SHORT_HIGH
        else -> null
    }

    /** [lines] is capped at [MAX_LINES]; [full] is every line, for the widget,
     *  which scrolls. Null on a summary saved before it existed. */
    data class Summary(val headline: String, val lines: List<String>, val held: Int, val atMs: Long,
                       val full: List<String>? = null,
                       /** Every line again, with its market's flag and what manages its
                        *  exit — what the widget draws. Null on older summaries. */
                       val rows: List<Row>? = null)

    /** One widget row: the market's flag, the line, and who manages the exit. */
    data class Row(val flag: String, val text: String, val detail: String)

    fun flagOf(market: String): String = when (market.uppercase()) {
        "DE" -> "🇩🇪"; "US" -> "🇺🇸"; "IN" -> "🇮🇳"; "BINANCE", "BN" -> "🪙"; else -> ""
    }

    private fun pct(x: Double) = "%.1f".format(kotlin.math.abs(x))
    private fun signed(x: Double) = (if (x >= 0) "+" else "−") + pct(x) + "%"
    private fun px(x: Double) = "%.2f".format(x)

    private fun day(ms: Long): String =
        java.text.SimpleDateFormat("dd.MM", java.util.Locale.US).format(java.util.Date(ms))

    fun line(k: Kind, p: Pos): String = when (k) {
        Kind.NO_PRICE -> "⚠ ${p.symbol} · no current price" +
            (if (p.staleSinceMs > 0L) " since ${day(p.staleSinceMs)}" else "") +
            " — its stops can't see a fall"
        Kind.NEAR_STOP -> "🔴 ${p.symbol} ${signed(p.gainPct)} · " +
            (if (p.aboveStopPct < 0.0) "under its stop (${px(p.stop)})"
             else "stop ${pct(p.aboveStopPct)}% below (${px(p.stop)})")
        Kind.NEAR_TARGET -> "🎯 ${p.symbol} ${signed(p.gainPct)} · " +
            (if (p.toTargetPct <= 0.0) "past its target (${px(p.target)})"
             else "target ${pct(p.toTargetPct)}% away (${px(p.target)})")
        Kind.NEAR_HIGH -> "🟢 ${p.symbol} ${signed(p.gainPct)} · " +
            (if (p.underPeakPct < 0.05) "at its high since buy"
             else "${pct(p.underPeakPct)}% under its high (${px(p.peak)})")
        Kind.SHORT_HIGH -> {
            val (label, level) = shortHighOf(p) ?: ("1-week" to p.weekHigh)
            "🔼 ${p.symbol} ${signed(p.gainPct)} · " +
                (if (p.price > level) "above its $label high (${px(level)})" else "at its $label high (${px(level)})")
        }
    }

    /** Pure: the headline and lines for a set of positions. */
    fun summarize(ps: List<Pos>, now: Long): Summary {
        val tagged = ps.mapNotNull { p -> kindOf(p)?.let { it to p } }
        fun of(k: Kind, by: (Pos) -> Double) = tagged.filter { it.first == k }.sortedBy { by(it.second) }
        val blind = of(Kind.NO_PRICE) { it.staleSinceMs.toDouble() }
        val stops = of(Kind.NEAR_STOP) { it.aboveStopPct }
        val targets = of(Kind.NEAR_TARGET) { it.toTargetPct }
        val highs = of(Kind.NEAR_HIGH) { it.underPeakPct }
        // Furthest above its short-term high first.
        val shortHighs = of(Kind.SHORT_HIGH) { p -> shortHighOf(p)?.let { -(p.price / it.second) } ?: 0.0 }
        val headline = listOfNotNull(
            highs.size.takeIf { it > 0 }?.let { "🟢 $it near ${if (it == 1) "its" else "their"} high" },
            shortHighs.size.takeIf { it > 0 }?.let { "🔼 $it at a short-term high" },
            targets.size.takeIf { it > 0 }?.let { "🎯 $it near target" },
            stops.size.takeIf { it > 0 }?.let { "🔴 $it near stop" },
            blind.size.takeIf { it > 0 }?.let { "⚠ $it unpriced" }
        ).joinToString(" · ").ifBlank {
            "${ps.size} held · none near a stop, target or high"
        }
        // Most pressing first: what the app cannot see at all, then what might be
        // sold at a loss, then what might be sold at a profit, then what is simply
        // doing well.
        val ordered = blind + stops + targets + highs + shortHighs
        val all = ordered.map { (k, p) -> line(k, p) }
        val lines = if (all.size <= MAX_LINES) all
                    else all.take(MAX_LINES - 1) + "+${all.size - (MAX_LINES - 1)} more — open the app"
        val rows = ordered.map { (k, p) -> Row(flagOf(p.market), line(k, p), p.exitBy) }
        return Summary(headline, lines, ps.size, now, full = all, rows = rows)
    }

    /** The latest summary in memory. Read through [latest] instead. */
    @Volatile private var cached: Summary? = null
    @Volatile private var loaded = false

    private fun file(ctx: Context) = java.io.File(ctx.filesDir, "position_radar.json")

    /** Older than this and it is not shown: a summary of positions as they stood
     *  yesterday morning reads as today's. */
    private const val MAX_AGE_MS = 24 * 60 * 60_000L

    /**
     * The latest summary, for the notification to draw; null when nothing is
     * held or no pass has produced one.
     *
     * KEPT ON DISK as well as in memory. The phone restarts the app between
     * passes, and every pass begins by redrawing this notification — from memory
     * alone, the summary would vanish for the whole of every pass and come back
     * only at the end of it.
     */
    fun latest(ctx: Context): Summary? {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                cached = try {
                    val f = file(ctx)
                    if (f.exists()) com.google.gson.Gson().fromJson(f.readText(), Summary::class.java) else null
                } catch (e: Exception) { null }
                loaded = true
            }
        }
        return cached?.takeIf { System.currentTimeMillis() - it.atMs < MAX_AGE_MS && it.headline.isNotBlank() }
    }

    /** The pinned card this replaced — its id and channel, so a card left over
     *  from an earlier version is taken down. */
    private const val OLD_CARD_ID = 4721
    private const val OLD_CARD_CHANNEL = "positions_pinned"

    /**
     * Hand the summary to the home-screen widget.
     *
     * THE WIDGET, NOT A NOTIFICATION. A notification is shown expanded only while
     * the phone chooses to, folded it needs a tap, and the phone may bundle it
     * with others; a widget is always fully visible and cannot be swiped away.
     * The old pinned card is removed here as well, so updating the app does not
     * leave it behind.
     */
    fun publish(ctx: Context, s: Summary? = latest(ctx)) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(OLD_CARD_ID)
            nm.deleteNotificationChannel(OLD_CARD_CHANNEL)
        } catch (e: Exception) { Log.w(TAG, "old card not removed", e) }
        PositionWidget.update(ctx, s)
    }

    private fun keep(ctx: Context, s: Summary?) {
        cached = s; loaded = true
        try {
            if (s == null) file(ctx).delete()
            else file(ctx).writeText(com.google.gson.Gson().toJson(s))
        } catch (e: Exception) { Log.w(TAG, "summary not saved", e) }
    }

    /**
     * Gather every real position, refresh the highs, and keep the summary.
     * Called once at the end of each background pass. Network: a price for each
     * held name whose market is open and whose price is stale, a one-time seed of
     * the high for a new position, and one read of Zerodha's triggers.
     */
    fun refresh(ctx: Context, priceMaxAgeMs: Long = 20 * 60_000L): Summary? {
        val now = System.currentTimeMillis()
        val raw = try { gather(ctx, now, priceMaxAgeMs) } catch (e: Exception) {
            Log.w(TAG, "gather failed", e); return latest(ctx)
        }
        val s = if (raw.isEmpty()) null else summarize(raw, now)
        keep(ctx, s)
        publish(ctx, s)
        if (s != null) Log.i(TAG, "${s.held} held — ${s.headline}")
        return s
    }

    /**
     * WHO MANAGES THE EXIT, AND WHERE EACH LEVEL RESTS — the widget's second line.
     *
     * The difference matters most on a sharp fall. A level resting AT THE BROKER
     * works with the phone in a drawer; one "checked by the app" acts only when the
     * app next looks — every 15 minutes, while the phone lets it run. On Trading 212
     * only one sell order can wait per share, and that is normally the target, so
     * the stop there is the app's to watch. Zerodha holds both sides of one trigger.
     */
    private fun exitByOf(ctx: Context, h: Held, base: String, isWatch: Boolean,
                         stop: Double, target: Double,
                         resting: List<T212WaitingOrders.Order>): String {
        val who = when {
            h.byHand -> "your own"
            isWatch -> "watchlist"
            else -> "algo " + ((try { BucketStore.get(ctx, h.ns)?.name } catch (e: Exception) { null } ?: h.ns).trim())
        }
        val parts = arrayListOf(who)
        if (base == "in") {
            parts += if (h.gttStop > 0.0) "stop ${px(h.gttStop)} at Zerodha"
                     else if (stop > 0.0) "stop ${px(stop)} checked by the app" else "no stop"
            if (h.gttTarget > 0.0) parts += "target ${px(h.gttTarget)} at Zerodha"
            else if (target > 0.0) parts += "target ${px(target)} checked by the app"
        } else {
            val mine = resting.filter { h.ticker.isNotBlank() && it.ticker.equals(h.ticker, true) }
            val bStop = mine.firstOrNull { it.type.contains("STOP") }
            val bTarget = mine.firstOrNull { !it.type.contains("STOP") && it.limitPrice > 0.0 }
            parts += when {
                bStop != null -> "stop ${px(bStop.stopPrice)} at Trading 212"
                stop > 0.0 -> "stop ${px(stop)} checked by the app"
                else -> "no stop"
            }
            // Two different levels can both be live: the app's own take-profit,
            // which it checks, and a sell order resting at the broker. Both are
            // named when they differ, so the line and this row never disagree.
            if (bTarget != null && target > 0.0 && kotlin.math.abs(bTarget.limitPrice / target - 1.0) > 0.002)
                parts += "target ${px(target)} checked by the app, sell ${px(bTarget.limitPrice)} waiting at Trading 212"
            else if (bTarget != null) parts += "target ${px(bTarget.limitPrice)} at Trading 212"
            else if (target > 0.0) parts += "target ${px(target)} checked by the app"
            else if (h.byHand) parts += "no target"
        }
        return parts.joinToString(" · ")
    }

    private class Held(val key: String, val ns: String, val symbol: String, val paid: Double,
                       val paperEntry: Double, val buySec: Long, val tradeId: String,
                       val gttTarget: Double = 0.0, val gttStop: Double = 0.0,
                       /** Bought by hand, not by the app — known only from the broker's
                        *  own holdings list: what was paid, and what it is worth now. */
                       val byHand: Boolean = false, val brokerPrice: Double = 0.0,
                       /** Trading 212's instrument code, to find the orders resting on it. */
                       val ticker: String = "")

    private fun gather(ctx: Context, now: Long, priceMaxAgeMs: Long = 20 * 60_000L): List<Pos> {
        val held = ArrayList<Held>()
        // ── Trading 212: Germany and the US ──────────────────────────────────
        T212LiveStore.load(ctx).trades.filter {
            it.status == "OPEN" && it.qty > 0.0 && it.liveEntryPrice > 0.0 && !it.isManual() &&
                T212WeekTargets.applies(it.ns)
        }.forEach {
            held.add(Held("t212|${it.ns}|${it.paperTradeId}", it.ns, it.symbol.uppercase(),
                it.liveEntryPrice, it.paperEntryPrice,
                if (it.buyAt > 0L) it.buyAt / 1000L else it.entryTs, it.paperTradeId,
                ticker = it.t212Ticker))
        }
        // ── Zerodha: India ───────────────────────────────────────────────────
        val zRows = ZerodhaLiveStore.load(ctx).trades.filter {
            it.status == "OPEN" && it.qty > 0 && it.entryPrice > 0.0 && Spaces.isBucket(it.ns)
        }
        if (zRows.isNotEmpty()) {
            // Its stop and target rest AT THE BROKER, as the two sides of one
            // trigger, so they are read from there — one call for all of them.
            val triggers = try {
                val cfg = ConfigManager(ctx)
                if (!cfg.isZerodhaConnected) emptyList()
                else (ZerodhaClient(cfg).fetchGttTriggers() as? ZerodhaClient.GttListResult.Success)
                    ?.triggers.orEmpty()
                    .filter { it.status == "active" && it.transactionType.equals("SELL", true) }
            } catch (e: Exception) { Log.w(TAG, "Zerodha triggers unreadable", e); emptyList() }
            zRows.forEach { r ->
                val g = triggers.firstOrNull { it.id == r.gttId }
                    ?: triggers.firstOrNull { it.tradingsymbol.equals(r.symbol, true) }
                held.add(Held("zerodha|${r.ns}|${r.paperTradeId}", r.ns, r.symbol.uppercase(),
                    r.entryPrice, r.paperEntryPrice,
                    if (r.buyAt > 0L) r.buyAt / 1000L
                    else r.paperTradeId.substringAfterLast('-').toLongOrNull() ?: 0L,
                    r.paperTradeId,
                    gttTarget = g?.triggerPriceAux ?: 0.0, gttStop = g?.triggerPrice ?: 0.0))
            }
        }
        // THE WHOLE PORTFOLIO, not only what the app bought (the user's ask, 16 Oct
        // 2026): every other holding the brokers report — bought by hand, or left
        // over after the app's own record of it closed. The shared holdings read,
        // so no extra call to a broker; an unreadable one simply adds nothing.
        val brokerPx = HashMap<String, Double>()
        for (space in listOf("de", "us", "in")) {
            val hs = (try { BrokerHoldings.forSpace(ctx, space) } catch (e: Exception) { null })
                as? BrokerHoldings.Result.Ok ?: continue
            hs.holdings.forEach { if (it.price > 0.0) brokerPx["$space|${it.symbol.uppercase()}"] = it.price }
            val covered = held.filter { Spaces.baseOf(it.ns) == space }.map { it.symbol }.toSet()
            hs.holdings.filter { it.qty > 0.0 && it.symbol.isNotBlank() && it.symbol.uppercase() !in covered }
                .distinctBy { it.symbol.uppercase() }
                .forEach { h ->
                    held.add(Held("hand|$space|${h.symbol.uppercase()}", space, h.symbol.uppercase(),
                        h.avgCost, 0.0, 0L, "", byHand = true, brokerPrice = h.price, ticker = h.ticker))
                }
        }
        if (held.isEmpty()) { PositionPeakStore.save(ctx, emptyMap()); return emptyList() }

        // ── Prices: refreshed only where the market is open ─────────────────
        // Out of hours the last price is the price, and asking again every pass
        // would spend requests for the same number.
        held.filter { !it.byHand }.groupBy { it.ns }.forEach { (ns, hs) ->
            if (MarketHours.isRegularSession(Spaces.baseOf(ns), now))
                try { MarkToMarket.ensureFresh(ctx, ns, hs.map { it.symbol }, priceMaxAgeMs) }
                catch (e: Exception) { Log.w(TAG, "[$ns] price refresh failed", e) }
        }

        val peaks = PositionPeakStore.load(ctx)
        val nextPeaks = HashMap<String, PositionPeakStore.Peak>()
        // The sell orders waiting at Trading 212, from the last pass's read — no
        // request here. Which of a holding's protections actually rest at the broker.
        val resting = try { T212WaitingOrders.last(ctx).orders.filter { !it.isBuy } } catch (e: Exception) { emptyList() }
        val configs = HashMap<String, com.signalscope.app.trading.StrategyLabConfig?>()
        val watch = HashMap<String, ManualWatchStore.State>()
        val out = ArrayList<Pos>()
        for (h in held) {
            val base = Spaces.baseOf(h.ns)
            // CURRENT, OR NOT USED. In session the price has to be as fresh as the
            // stops themselves require; out of hours the last close is the price,
            // so anything from the last few days will do (a weekend is ~3). A
            // feed that froze a week ago passes neither, and shows as unpriced
            // rather than as a gain it may not have.
            val mark = MarkToMarket.get(h.symbol)
            val markCurrent = mark != null && mark.price > 0.0 &&
                (if (MarketHours.isRegularSession(base, now)) MarkToMarket.freshPrice(h.symbol, now) > 0.0
                 else now - mark.atMs < OFF_HOURS_MAX_MS)
            // Without a current feed price, the broker's own — read moments ago with
            // the holdings. A holding bought by hand may not be in the feed at all,
            // and after a restart out of hours the feed's last price is gone until
            // the market reopens.
            val brokerPrice = if (h.byHand) h.brokerPrice else brokerPx["$base|${h.symbol}"] ?: 0.0
            val current = markCurrent || brokerPrice > 0.0
            val price = if (markCurrent) mark!!.price else if (current) brokerPrice else 0.0
            // A FEED THAT HAS STOPPED is said out loud, once a day — the widget
            // alone shows a stale price, not that nothing will ever refresh it.
            val feedSym = com.signalscope.app.network.YahooFinanceClient
                .toYahooSymbol(h.symbol, Spaces.exchangeOf(h.ns))
            com.signalscope.app.network.FeedHealth.stoppedSince(feedSym).takeIf { it > 0L }?.let { since ->
                try { LabNotifier.feedStopped(ctx, base, h.symbol, feedSym, since, held = true) }
                catch (e: Exception) { Log.w(TAG, "feed alarm failed", e) }
            }
            val isWatch = Spaces.bucketIdOf(h.ns) == ManualWatchStore.ID
            val ws = if (isWatch) watch.getOrPut(base) { ManualWatchStore.load(ctx, base) } else null
            val entry = ws?.entries?.firstOrNull {
                it.status == "OPEN" && "${it.symbol.uppercase()}-${it.signalBarTs}" == h.tradeId
            }

            // ── the high since buy ──
            val prev = peaks[h.key]
            var peak = maxOf(prev?.peak ?: 0.0, h.paid, MarkToMarket.peakPrice(h.symbol), price,
                entry?.peakPrice ?: 0.0)
            var seeded = prev?.seeded ?: false
            if (!seeded && h.buySec > 0L) {
                StrategyLabRunner.highSince(ctx, h.ns, h.symbol, h.buySec)?.let { hi ->
                    peak = maxOf(peak, hi); seeded = true
                }
            }
            nextPeaks[h.key] = PositionPeakStore.Peak(peak,
                if (prev == null || peak > prev.peak) now else prev.atMs, seeded)

            // ── the stops and targets watching it ──
            // None the app knows of for a holding bought by hand.
            val cfg = if (h.byHand) null else configs.getOrPut(h.ns) {
                try { StrategyLabStore.activeConfig(ctx, h.ns) } catch (e: Exception) { null }
            }
            val x = cfg?.exit
            var stop = 0.0
            var target = 0.0
            if (x != null && x.stopLossEnabled && x.stopLossPct > 0.0) {
                // The strategy's stop from its own entry, and — on Trading 212 —
                // the same distance from what was actually paid. Whichever is
                // higher is reached first.
                if (h.paperEntry > 0.0) stop = maxOf(stop, h.paperEntry * (1 - x.stopLossPct / 100.0))
                if (base == "de" || base == "us") stop = maxOf(stop, h.paid * (1 - x.stopLossPct / 100.0))
            }
            if (x != null && x.takeProfitEnabled && x.takeProfitPct > 0.0 && h.paperEntry > 0.0)
                target = ManualTakeProfit.tighterOf(target, h.paperEntry * (1 + x.takeProfitPct / 100.0))
            stop = maxOf(stop, h.gttStop, entry?.trailLevel ?: 0.0)
            target = ManualTakeProfit.tighterOf(target, h.gttTarget)
            if (entry != null && (ws?.takeProfitPct ?: 0.0) > 0.0)
                target = ManualTakeProfit.tighterOf(target,
                    ManualTakeProfit.targetFor(entry.filledPrice, ws!!.takeProfitPct))
            if (h.key.startsWith("t212|"))
                T212TargetStore.get(ctx, h.ns, h.tradeId)?.takeIf { !it.done && it.price > 0.0 }?.let {
                    target = ManualTakeProfit.tighterOf(target, it.price)
                }

            out.add(Pos(h.key, h.symbol, base.uppercase(), h.paid, price, peak, target, stop,
                staleSinceMs = if (current) 0L else (mark?.atMs ?: 0L), priceStale = !current,
                exitBy = exitByOf(ctx, h, base, isWatch, stop, target, resting)))
        }
        PositionPeakStore.save(ctx, nextPeaks)
        return withShortHighs(out)
    }

    /**
     * Each position's short-term highs: the highest price of the last 5 and 3
     * FINISHED trading days, from the shared daily cache — one download per stock
     * per day, whatever the number of passes.
     */
    private fun withShortHighs(ps: List<Pos>): List<Pos> {
        val bars = HashMap<String, List<com.signalscope.app.screener.DailyBar>>()
        ps.groupBy { it.market.lowercase() }.forEach { (space, list) ->
            try {
                com.signalscope.app.screener.ScreenerRunner.fetchUniverseBars(
                    Spaces.dataSpaceOf(space), list.map { it.symbol }.distinct(), minBars = 5)
                    .first.forEach { (sym, b) -> bars["$space|${sym.uppercase()}"] = b }
            } catch (e: Exception) { Log.w(TAG, "[$space] daily bars for short-term highs failed", e) }
        }
        val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        return ps.map { p ->
            val done = bars["${p.market.lowercase()}|${p.symbol.uppercase()}"]
                ?.filter { it.date.isBefore(today) }?.sortedBy { it.date } ?: return@map p
            if (done.size < 5) return@map p
            p.copy(weekHigh = done.takeLast(5).maxOf { it.high }, threeHigh = done.takeLast(3).maxOf { it.high })
        }
    }
}

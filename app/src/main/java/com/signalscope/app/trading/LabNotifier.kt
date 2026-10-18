package com.signalscope.app.trading

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.signalscope.app.R

/**
 * Trade notifications for the crypto algo — the user was flying blind on when
 * the lab opened/closed positions unless they happened to have the app open.
 *
 * Two channels, deliberately separate so they can be silenced independently:
 *  - LIVE  (high importance)    — real money moved on Binance
 *  - PAPER (default importance) — the simulated lab opened/closed a trade
 */
object LabNotifier {
    private const val TAG = "LabNotifier"
    /** Read by MainActivity to open the algo the notification is about. */
    const val EXTRA_OPEN_NS = "open_algo_ns"
    /** Read by MainActivity to open the CHART for one company. An entry signal is
     *  about a single decision on a single name, and the chart is where that
     *  decision gets made — landing on whatever screen the app was left on means
     *  finding the name again by hand, from memory, having just closed the one
     *  place that told you what it was. */
    const val EXTRA_OPEN_SYMBOL = "open_symbol"
    const val EXTRA_OPEN_SPACE = "open_space"
    /** Read by MainActivity to land a tap on a specific tab. */
    const val EXTRA_OPEN_TAB = "open_tab"
    /**
     * Read by MainActivity to land a tap on the WATCHER LIST, scrolled to one
     * name — as opposed to [EXTRA_OPEN_SYMBOL], which opens that name's chart
     * over the top of it.
     *
     * THE TWO ALERTS WANT DIFFERENT DESTINATIONS. An entry signal is a decision
     * about one company and the chart is where that decision gets made. A "near
     * its floor" notice is a decision about whether to act at all, and everything
     * needed for it — the range, the levels, the projection, the Buy button — is
     * on the row itself. Opening the chart there buries the answer under the
     * working.
     */
    const val EXTRA_OPEN_WATCH_SYMBOL = "open_watch_symbol"
    private const val CH_LIVE = "binance_live_trades"
    private const val CH_PAPER = "lab_paper_trades"
    private const val CH_MANUAL = "manual_entry_signals"
    /**
     * ITS OWN CHANNEL, and not merely for tidiness.
     *
     * Android fixes a channel's importance when it is FIRST created and ignores
     * every later attempt to change it. Entry signals create this app's manual
     * channel at high importance, so a floor notice asking for something quieter
     * on the same channel would have been silently overruled — it would buzz
     * exactly as loudly as a signal while the code claimed otherwise.
     *
     * Separate also means separately silenceable, which matters more here than
     * anywhere: this is the chattiest alert the app has, and the user must be
     * able to turn it down without losing the ones that say a strategy acted.
     */
    private const val CH_SUPPORT = "manual_near_support"
    /** Its own channel, so the new, still-untested alert can be silenced alone. */
    private const val CH_TURN = "manual_turning_up"
    /** Real STOCK fills, separate from crypto's channel so one can be silenced
     *  without the other — the two move at completely different rates. */
    private const val CH_T212 = "t212_live_trades"

    /**
     * [openNs] deep-links the tap to one algo. Without it the notification opens
     * the app wherever it was left, which on a six-algo account means reading the
     * name, switching space, finding the card and opening it by hand — for an
     * event the notification already knew the exact location of.
     */
    /** Discovery's MACD chart for one stock, drawn for the shade: [wide] under the
     *  text when expanded, [square] beside the title when collapsed. */
    class ChartArt(val wide: Bitmap, val square: Bitmap?)

    /**
     * THE CHART DISCOVERY SHOWS, for a watcher name — from the curve the watcher
     * already keeps per stock, the same thirty days Discovery stores, so the two
     * screens draw one shape and nothing is downloaded to make it.
     *
     * Null when the name has no stored curve yet; the alert then goes out exactly
     * as it always did. A chart is an addition to the alert, never a condition
     * of it.
     */
    private fun macdArt(ctx: Context, space: String, symbol: String): ChartArt? = try {
        val sym = symbol.trim().uppercase()
        val s = com.signalscope.app.data.ManualSparkStore.load(ctx, space).series[sym]
        val curve = s?.macdRecent.orEmpty()
        if (curve.size < 2) null
        else {
            val base = Spaces.baseOf(space.lowercase())
            // Regular-session fifteen-minute candles, fetched once: they build the
            // 15m, 1H and 4H dots, and are the price dot's candles too except in
            // the US, where that dot reads the extended sessions.
            val c15 = try {
                (com.signalscope.app.network.YahooFinanceClient.fetchCandles15m(
                    sym, Spaces.exchangeOf(base), includePrePost = false)
                    as? com.signalscope.app.network.YahooFinanceClient.CandleResult.Success)?.candles
            } catch (e: Exception) { null }
            val v = if (base == "us") velocity15m(space, sym)
                    else c15?.let { PriceVelocity.last15m(it, System.currentTimeMillis() / 1000L, FeedDelay.secFor(base)) }
            val slopes = macdSlopes(ctx, base, sym, s?.macdRecent.orEmpty(), c15)
            MacdChartBitmap.wide(ctx, curve, s?.phase, curve.size, v?.pct, v?.let { note15m(it) }, slopes)
                ?.let { w -> ChartArt(w, MacdChartBitmap.square(ctx, curve, s?.phase, v?.pct, slopes)) }
        }
    } catch (e: Exception) { Log.w(TAG, "chart for $symbol failed", e); null }

    /**
     * THE NEAR-LOW ALERT'S PICTURE — see [LowsChartBitmap]: the slope dots, price
     * over a year, a month and five days with each window's low, and the daily
     * MACD as a strip. The price comes from the bars the watchlist downloaded for
     * this stock moments ago on the same pass; the MACD curve from the stored
     * series, as before. Null when there is nothing to draw.
     */
    private fun lowsArt(ctx: Context, space: String, symbol: String, price: Double,
                        bars0: List<com.signalscope.app.data.CandleData>? = null): ChartArt? = try {
        val sym = symbol.trim().uppercase()
        val base = Spaces.baseOf(space.lowercase())
        val s = com.signalscope.app.data.ManualSparkStore.load(ctx, space).series[sym]
        val curve = s?.macdRecent.orEmpty()
        val c15 = try {
            (com.signalscope.app.network.YahooFinanceClient.fetchCandles15m(
                sym, Spaces.exchangeOf(base), includePrePost = false)
                as? com.signalscope.app.network.YahooFinanceClient.CandleResult.Success)?.candles
        } catch (e: Exception) { null }
        val v = if (base == "us") velocity15m(space, sym)
                else c15?.let { PriceVelocity.last15m(it, System.currentTimeMillis() / 1000L, FeedDelay.secFor(base)) }
        val slopes = macdSlopes(ctx, base, sym, curve, c15)
        val bars = bars0 ?: StrategyLabRunner.lastCandles
            ?.takeIf { it.first.equals(MacdLadder.bare(sym), true) }?.third.orEmpty()
        val panels = LowsChartBitmap.panels(bars, price)
        // NO CORNER SQUARE. The phone keeps that small picture beside the title even
        // when the alert is expanded, and narrows the main picture to make room for
        // it — the three price charts came out at about half the card's width.
        // Without it they use the full width; folded, the alert is its title and
        // one line, which is all a folded alert has room for anyway.
        LowsChartBitmap.wide(ctx, panels, curve, s?.phase, slopes, v?.pct, v?.let { note15m(it) })
            ?.let { w -> ChartArt(w, null) }
    } catch (e: Exception) { Log.w(TAG, "lows chart for $symbol failed", e); null }

    /** The five MACD-slope dots — the same routine the detail view uses, so an
     *  alert and the app can never show one stock two ways. See [MacdLadder]. */
    private fun macdSlopes(ctx: Context, base: String, sym: String, dailyMacd: List<Double>,
                           c15: List<com.signalscope.app.data.CandleData>?): List<MacdSlopes.Reading>? =
        MacdLadder.readings(ctx, base, sym, c15, dailyMacd)

    /**
     * The price dot's reading: the last completed 15-minute candle against the
     * one before — see [PriceVelocity]. US includes the extended sessions, as the
     * queued-buy check does, so the dot and that check agree on "rising".
     * Null when the candles cannot be had; the dot is then left off, not guessed.
     */
    private fun velocity15m(space: String, sym: String): PriceVelocity.Reading? = try {
        val base = Spaces.baseOf(space.lowercase())
        val res = com.signalscope.app.network.YahooFinanceClient.fetchCandles15m(
            sym, Spaces.exchangeOf(base), includePrePost = base == "us")
        (res as? com.signalscope.app.network.YahooFinanceClient.CandleResult.Success)?.candles
            ?.let { PriceVelocity.last15m(it, System.currentTimeMillis() / 1000L, FeedDelay.secFor(base)) }
    } catch (e: Exception) { null }

    /** "15m to 20:45" — or with the date when that candle is not from today, so
     *  an evening read of the close's last candle never passes for "now". */
    private fun note15m(r: PriceVelocity.Reading): String {
        val t = java.time.Instant.ofEpochSecond(r.endSec).atZone(java.time.ZoneId.systemDefault())
        val today = java.time.LocalDate.now(java.time.ZoneId.systemDefault())
        val fmt = if (t.toLocalDate() == today) "HH:mm" else "dd.MM HH:mm"
        return "15m to " + t.format(java.time.format.DateTimeFormatter.ofPattern(fmt))
    }

    private fun notify(ctx: Context, channel: String, importance: Int,
                       channelName: String, title: String, text: String, idSeed: String,
                       openNs: String? = null,
                       openSymbol: String? = null, openSpace: String? = null,
                       openWatchSymbol: String? = null,
                       chart: ChartArt? = null,
                       /** False: the expanded view is the title and the picture
                        *  only — the text shows while folded. */
                       textInBig: Boolean = true) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(channel, channelName, importance))
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                ?: Intent()
            if (!openNs.isNullOrBlank()) {
                launch.putExtra(EXTRA_OPEN_NS, openNs)
                // Without this the system may hand back the EXISTING intent for an
                // already-running task and the extra is silently dropped — the tap
                // then just brings the app forward, doing nothing.
                launch.action = Intent.ACTION_VIEW
                launch.data = android.net.Uri.parse("signalscope://algo/$openNs")
            }
            if (!openSymbol.isNullOrBlank()) {
                launch.putExtra(EXTRA_OPEN_SYMBOL, openSymbol)
                if (!openSpace.isNullOrBlank()) launch.putExtra(EXTRA_OPEN_SPACE, openSpace)
                // Same reason as above — and the URI must differ per symbol, or the
                // system reuses one PendingIntent across alerts and every tap opens
                // whichever name happened to be notified first.
                launch.action = Intent.ACTION_VIEW
                launch.data = android.net.Uri.parse(
                    "signalscope://chart/${openSpace ?: "de"}/$openSymbol")
            }
            if (!openWatchSymbol.isNullOrBlank()) {
                launch.putExtra(EXTRA_OPEN_WATCH_SYMBOL, openWatchSymbol)
                if (!openSpace.isNullOrBlank()) launch.putExtra(EXTRA_OPEN_SPACE, openSpace)
                // A DISTINCT URI, for the same reason the chart link needs one:
                // the system reuses a PendingIntent across alerts that share a
                // URI, so without this every floor alert would open whichever
                // name happened to be notified first.
                launch.action = Intent.ACTION_VIEW
                launch.data = android.net.Uri.parse(
                    "signalscope://watcher/${openSpace ?: "de"}/$openWatchSymbol")
            }
            val pi = PendingIntent.getActivity(ctx, idSeed.hashCode(), launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val b = NotificationCompat.Builder(ctx, channel)
                .setContentTitle(coloured(title)).setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_sort_by_size)
                .setContentIntent(pi).setAutoCancel(true)
            if (chart != null) {
                // A CUSTOM BODY, NOT A PICTURE NOTIFICATION. The system's picture
                // style shrinks the text to one line when expanded, and the line
                // it drops is the one saying nothing has been bought. This keeps
                // the whole text and puts the chart beneath it; the collapsed view
                // stays the standard one, with the small chart beside the title.
                val big = RemoteViews(ctx.packageName, R.layout.notification_chart).apply {
                    setTextViewText(R.id.nc_title, coloured(title))
                    setTextViewText(R.id.nc_text, text)
                    setImageViewBitmap(R.id.nc_chart, chart.wide)
                    if (!textInBig) setViewVisibility(R.id.nc_text, android.view.View.GONE)
                }
                b.setStyle(NotificationCompat.DecoratedCustomViewStyle())
                    .setCustomBigContentView(big)
                chart.square?.let { b.setLargeIcon(it) }
            } else {
                b.setStyle(NotificationCompat.BigTextStyle().bigText(text))
            }
            val n = b.build()
            // Unique id per trade event so a burst of fills doesn't overwrite itself.
            // Recorded with the same destination the tap carries, so the in-app
            // list can open it again after the notification itself is gone.
            val link = if (openNs.isNullOrBlank() && openSymbol.isNullOrBlank() && openWatchSymbol.isNullOrBlank()) null
                else com.signalscope.app.data.NotificationLog.Link(ns = openNs, symbol = openSymbol,
                    watchSymbol = openWatchSymbol, space = openSpace)
            com.signalscope.app.data.NotificationLog.post(ctx, nm, idSeed.hashCode(), n, link)
        } catch (e: Exception) { Log.w(TAG, "notify failed", e) }
    }

    fun liveBuy(ctx: Context, symbol: String, quoteSpent: Double, qty: String) =
        notify(ctx, CH_LIVE, NotificationManager.IMPORTANCE_HIGH, "Binance LIVE trades",
            "🟢 LIVE BUY $symbol",
            "Bought $qty for ${"%.2f".format(quoteSpent)} USDC (real order on Binance).",
            "lb-$symbol-$qty")

    fun liveSell(ctx: Context, symbol: String, proceeds: Double, pnl: Double, reason: String) =
        notify(ctx, CH_LIVE, NotificationManager.IMPORTANCE_HIGH, "Binance LIVE trades",
            (if (pnl >= 0) "🔴 LIVE SELL $symbol  (+" else "🔴 LIVE SELL $symbol  (") +
                "%.2f USDC)".format(pnl),
            "Sold for ${"%.2f".format(proceeds)} USDC · P&L ${"%+.2f".format(pnl)} USDC" +
                (if (reason.isNotBlank()) " · $reason" else ""),
            "ls-$symbol-${System.currentTimeMillis() / 60000}")

    /** The algo's own name, falling back to the namespace when it has none —
     *  "B_Conservative" is what the card says, and matching it is the whole point.
     *
     *  THE RESERVED WATCHLIST NAMESPACE IS NOT AN ALGO and must not be dressed as
     *  one. It has no BucketStore row by design, so the fallback used to uppercase
     *  the raw namespace and put "DE__MANUAL" at the front of a real-money alert —
     *  which reads exactly like an algo's name. The one thing a reader must be
     *  able to tell from these alerts is whether a strategy acted on its own or
     *  something managed a position they bought themselves, and that spelling
     *  actively hid it. */
    private fun algoLabel(ctx: Context, ns: String): String = try {
        if (com.signalscope.app.trading.Spaces.bucketIdOf(ns) ==
            com.signalscope.app.data.ManualWatchStore.ID) "${spaceLabel(ns)} watchlist"
        else com.signalscope.app.data.BucketStore.get(ctx, ns)?.name ?: ns.uppercase()
    } catch (e: Exception) { ns.uppercase() }

    private fun spaceLabel(ns: String): String =
        when (com.signalscope.app.trading.Spaces.baseOf(ns)) {
            "de" -> "Germany"; "in" -> "India"; "binance" -> "Crypto"; else -> "USA"
        }

    /** Silent when paper notifications are switched off FOR THIS MARKET — checked
     *  here rather than at each call site so no future caller can bypass the
     *  preference. Per market because they are not worth the same everywhere: in
     *  a space that trades on its own a paper alert is a report, and in one that
     *  cannot it is the only prompt the user will get. */
    private fun paperMuted(ctx: Context, ns: String): Boolean = try {
        // ── THE WATCHER'S LEDGER IS A SCAN, NOT A BOOK OF HOLDINGS ───────────
        //
        // The reserved watchlist namespace enrols every CANDIDATE it is watching
        // — 35 German names on this account — and replays the strategy over all
        // of them to find out which ones would enter. That produces paper opens
        // and paper closes for names the user has never owned and has no
        // intention of owning. Sixteen were "open" on paper against a single real
        // holding.
        //
        // Sending those as trade notifications produced "Germany watchlist would
        // SELL MUX" for a stock the user does not hold, which is not merely noise:
        // a sell instruction for something you do not own reads as the app having
        // lost track of your position, which is the one thing these alerts must
        // never falsely suggest.
        //
        // NOTHING IS LOST BY MUTING IT. This namespace already has a complete set
        // of notifications of its own, each for a case this one cannot express:
        //   • a candidate's entry firing  → manualEntrySignal, its own channel
        //   • a real manual entry exiting → t212Sell, from the watcher's own sweep
        //   • a holding breaking its stop → heldPositionBreak
        //   • a holding the config would exit → portfolioStrategyExit
        // The paper ledger's own opens and closes duplicate all four for the names
        // that matter and invent the rest.
        val isScan = com.signalscope.app.trading.Spaces.bucketIdOf(ns) ==
            com.signalscope.app.data.ManualWatchStore.ID
        val base = com.signalscope.app.trading.Spaces.baseOf(ns)
        val spaceOn = com.signalscope.app.data.ConfigManager(ctx).paperNotifiesSpace(base)
        // The algo's own say, under the market's. Null = never chosen = on, so an
        // algo created before this existed keeps notifying.
        val algoOn = if (com.signalscope.app.trading.Spaces.isBucket(ns))
            com.signalscope.app.data.BucketStore.get(ctx, ns)?.notify ?: true
        else true
        isScan || !(spaceOn && algoOn)
    } catch (e: Exception) { false }

    /**
     * A gate-passing name the user does NOT hold has just fired an entry under the
     * manual watcher's config.
     *
     * Its own channel, at high importance, and deliberately NOT behind the paper
     * switch: this is not a report of something the app did, it is an invitation to
     * make a decision that expires. Muting paper chatter should not mute it, and
     * wanting it should not force paper chatter back on.
     *
     * The text says "you would have to buy this yourself" in as many words, because
     * the one dangerous misreading is thinking an algo has already acted.
     */
    fun manualEntrySignal(ctx: Context, space: String, symbol: String, rank: Int,
                          price: Double, reason: String) {
        // THE SAME COMPACT PICTURE AS THE NEAR-LOW ALERT — see [lowsArt]: a
        // one-line title, then the slope strip and the 2×2 grid of price and MACD.
        // The rule wording goes to the folded line only; expanded, the picture
        // shows what it described. Without a picture, the full wording below.
        val art = lowsArt(ctx, space, symbol, price)
        if (art != null) {
            notify(ctx, CH_MANUAL, NotificationManager.IMPORTANCE_HIGH, "Manual entry signals",
                "🟣 $symbol" + (if (rank > 0) " #$rank" else "") +
                    " ${curOf(space)}${"%.2f".format(price)} · entry signal",
                reason.ifBlank { "The watcher config would enter here" },
                "me-$space-$symbol-${java.time.LocalDate.now(java.time.ZoneOffset.UTC)}",
                openSymbol = symbol, openSpace = space, chart = art, textInBig = false)
            return
        }
        notify(ctx, CH_MANUAL, NotificationManager.IMPORTANCE_HIGH, "Manual entry signals",
            "🟣 $symbol — entry signal (#$rank)",
            "${spaceLabel(space)} · the watcher config would enter at ${"%.4f".format(price)}" +
                (if (reason.isNotBlank()) " · $reason" else "") +
                ". No algo holds this and nothing has been bought — open the Watchlist tab to review the chart and decide.",
            // One id per symbol per day: the watcher's own dedup already caps this
            // at once daily, and a stable id means a repeat can only replace, never
            // stack into a pile of identical alerts.
            "me-$space-$symbol-${java.time.LocalDate.now(java.time.ZoneOffset.UTC)}",
            openSymbol = symbol, openSpace = space,
            chart = macdArt(ctx, space, symbol))
    }

    /**
     * A WATCHER NAME HAS COME DOWN TO THE BOTTOM OF ITS RECENT RANGE.
     *
     * A DIFFERENT KIND OF ALERT FROM THE ONE ABOVE, and the wording has to keep
     * them apart. An entry signal is the strategy saying it would buy; this says
     * nothing about the strategy at all. It is a price observation about a name
     * already judged interesting — ranked, trending, or pinned by hand — and the
     * decision is entirely the reader's.
     *
     * Deliberately NOT high importance. A signal that fires is news; a stock
     * drifting to the low end of its three-day range is an opportunity to look,
     * and buzzing the phone for every one of those across three markets is how a
     * channel gets silenced.
     */
    fun nearSupport(ctx: Context, space: String, symbol: String, rank: Int,
                    price: Double, low: Double, high: Double, visits: Int,
                    days: Int, cur: String) {
        val offLow = if (low > 0.0) (price / low - 1.0) * 100.0 else 0.0
        notify(ctx, CH_SUPPORT, NotificationManager.IMPORTANCE_DEFAULT, "Near support",
            "🔵 $symbol — near its floor" + (if (rank > 0) " (#$rank)" else ""),
            "${spaceLabel(space)} · $cur${"%.2f".format(price)} is " +
                "${"%.1f".format(offLow)}% off its $days-day low of $cur${"%.2f".format(low)} " +
                "(range to $cur${"%.2f".format(high)})" +
                (if (visits > 1) " · price has come back to that floor $visits separate times" else "") +
                ". Nothing has been bought and no rule fired — this is where the stock is, not advice.",
            // One per symbol per day, matching the watcher's own cap. A stable id
            // means a repeat replaces rather than stacks.
            "ns-$space-$symbol-${java.time.LocalDate.now(java.time.ZoneOffset.UTC)}",
            // The LIST, not the chart — see [EXTRA_OPEN_WATCH_SYMBOL].
            openWatchSymbol = symbol, openSpace = space,
            chart = macdArt(ctx, space, symbol))
    }

    /**
     * NEAR ITS 3-DAY, 1-WEEK OR 1-MONTH LOW — one alert naming the DEEPEST of the
     * lows it is sitting at, and mentioning the others, rather than one buzz per
     * horizon. [hits] come shortest first (see [IntradayLevels.floorsHit]).
     * Same channel and wording rules as [nearSupport]: a price observation, not
     * a signal, and it says so.
     */
    fun nearLows(ctx: Context, space: String, symbol: String, rank: Int, price: Double,
                 hits: List<IntradayLevels.Floor>, cur: String) {
        val deep = hits.lastOrNull() ?: return
        val offLow = if (deep.low > 0.0) (price / deep.low - 1.0) * 100.0 else 0.0
        val others = hits.dropLast(1).map { it.label }
        // THE PICTURE CARRIES IT NOW. With the price charts showing each window's
        // low, the alert is the title and the picture: how far above the low goes
        // in the title, the low and the range are the folded line, and the rest —
        // "nothing was bought", the visit count — is dropped. It never buys, and
        // the space is better spent on the charts. Without a picture the full
        // wording below is still sent, as before.
        val art = lowsArt(ctx, space, symbol, price)
        if (art != null) {
            // SHORT ENOUGH FOR ONE LINE: "🔵 PANW #13 $366.17 · +1.2% vs 1W low". The
            // longer "1.2% above its 1-week low" was cut off at the price.
            val lowTag = when (deep.scale) { "3d" -> "3D"; "1w" -> "1W"; "1m" -> "1M"; else -> deep.label }
            notify(ctx, CH_SUPPORT, NotificationManager.IMPORTANCE_DEFAULT, "Near support",
                "🔵 $symbol" + (if (rank > 0) " #$rank" else "") + " $cur${"%.2f".format(price)} · " +
                    "${"%+.1f".format(offLow)}% vs $lowTag low",
                "Low $cur${"%.2f".format(deep.low)} · range to $cur${"%.2f".format(deep.high)}",
                "ns-$space-$symbol-${java.time.LocalDate.now(java.time.ZoneOffset.UTC)}-${deep.scale}",
                openWatchSymbol = symbol, openSpace = space, chart = art, textInBig = false)
            return
        }
        notify(ctx, CH_SUPPORT, NotificationManager.IMPORTANCE_DEFAULT, "Near support",
            "🔵 $symbol — near its ${deep.label} low" + (if (rank > 0) " (#$rank)" else ""),
            "${spaceLabel(space)} · $cur${"%.2f".format(price)} is " +
                "${"%.1f".format(offLow)}% off its ${deep.label} low of $cur${"%.2f".format(deep.low)} " +
                "(range to $cur${"%.2f".format(deep.high)})" +
                (if (deep.visits > 1) " · price has come back to that floor ${deep.visits} separate times" else "") +
                (if (others.isNotEmpty()) " · also at the bottom of its ${others.joinToString(" and ")} range" +
                    (if (others.size > 1) "s" else "") else "") +
                ". Nothing has been bought and no rule fired — this is where the stock is, not advice.",
            // One per symbol, per day, per horizon: a deeper low later in the day
            // is a new alert, the same one again replaces rather than stacks.
            "ns-$space-$symbol-${java.time.LocalDate.now(java.time.ZoneOffset.UTC)}-${deep.scale}",
            openWatchSymbol = symbol, openSpace = space,
            chart = macdArt(ctx, space, symbol))
    }

    /**
     * WHAT A WATCHER ALERT LOOKS LIKE for one name, without anything having fired.
     *
     * Labelled PREVIEW in the title and the text, so it can never be read as a
     * signal. False when the name has no stored curve — which is itself the
     * answer to "would its alerts carry a chart?".
     */
    /**
     * A WATCHLIST STOCK HAS TURNED UP AT ITS FLOOR — the month and day trend
     * rising, the price at the bottom of its 3-day range, and the 15-minute and
     * hourly MACD just turned up. See [SignalWatch].
     *
     * The wording says in plain words that nothing was bought and that the rule
     * is still being tested: the one dangerous reading of any alert is that an
     * order has already gone in.
     */
    fun turningUp(ctx: Context, space: String, symbol: String, rank: Int, price: Double) {
        val cur = curOf(space)
        // The compact picture, as for the near-low and entry alerts.
        val art = lowsArt(ctx, space, symbol, price)
        if (art != null) {
            notify(ctx, CH_TURN, NotificationManager.IMPORTANCE_DEFAULT, "Turning up at a floor",
                "🟢 $symbol" + (if (rank > 0) " #$rank" else "") +
                    " $cur${"%.2f".format(price)} · turning up at floor",
                "Month and day rising, 15m and 1h just turned up, at its 3-day low",
                "tu-$space-$symbol-${java.time.LocalDate.now(java.time.ZoneOffset.UTC)}",
                openWatchSymbol = symbol, openSpace = space, chart = art, textInBig = false)
            return
        }
        notify(ctx, CH_TURN, NotificationManager.IMPORTANCE_DEFAULT, "Turning up at a floor",
            "🟢 $symbol — turning up at its floor" + (if (rank > 0) " (#$rank)" else ""),
            "${spaceLabel(space)} · $cur${"%.2f".format(price)}, at the bottom of its 3-day range, with the " +
                "month and day trend rising and the 15-minute and hourly MACD just turned up. " +
                "Nothing has been bought. This rule is new and being tracked — its record builds up " +
                "in the watcher panel.",
            "tu-$space-$symbol-${java.time.LocalDate.now(java.time.ZoneOffset.UTC)}",
            openWatchSymbol = symbol, openSpace = space,
            chart = macdArt(ctx, space, symbol))
    }

    /** "€", "₹" or "$" for a space. */
    private fun curOf(space: String): String =
        when (Spaces.baseOf(space.lowercase())) { "de" -> "€"; "in" -> "₹"; else -> "$" }

    /**
     * A clearly-labelled PREVIEW of a watcher alert's picture: [kind] is "low"
     * (near-low), "entry" (entry signal) or "turn" (turning up at a floor). All
     * three share one picture, with its own download of the bars — a preview is
     * not part of a pass that has just fetched them. Posted quietly on the
     * near-low channel whatever the kind, so trying one never rings the phone.
     */
    fun previewChart(ctx: Context, space: String, symbol: String, kind: String = "low"): Boolean {
        val bars = StrategyLabRunner.recentClosedCandles(ctx,
            com.signalscope.app.data.ManualWatchStore.nsFor(space), symbol)
        val px = MarkToMarket.get(symbol)?.price?.takeIf { it > 0.0 } ?: bars.lastOrNull()?.close ?: 0.0
        val art = lowsArt(ctx, space, symbol, px, bars) ?: macdArt(ctx, space, symbol) ?: return false
        val what = when (kind) { "entry" -> "🟣 entry signal"; "turn" -> "🟢 turning up at floor"; else -> "🔵 near-low alert" }
        notify(ctx, CH_SUPPORT, NotificationManager.IMPORTANCE_DEFAULT, "Near support",
            "🔍 PREVIEW — $symbol ${curOf(space)}${"%.2f".format(px)} · $what",
            "Nothing fired and nothing has been bought.",
            "pv-$space-$symbol-$kind-${System.currentTimeMillis()}",
            openWatchSymbol = symbol, openSpace = space, chart = art, textInBig = false)
        return true
    }

    /**
     * A REAL stock buy filled. High importance and never muted by the paper
     * switch: crypto has notified on real fills since it went live, while the T212
     * path notified only on FAILURES — so a €400 order that worked was completely
     * silent while one that didn't shouted. That asymmetry teaches you the app
     * only speaks when something is wrong, which is exactly the wrong lesson for a
     * system that spends money unattended.
     */
    fun t212Buy(ctx: Context, ns: String, symbol: String, qty: Double, spent: Double, cur: String) =
        notify(ctx, CH_T212, NotificationManager.IMPORTANCE_HIGH, "Stock LIVE trades",
            "${flagOf(ns)} $symbol bought",
            whoBought(ctx, ns, ""),
            "t2b-$ns-$symbol-${System.currentTimeMillis() / 60000}", openNs = ns)

    /**
     * A REAL stock sale.
     *
     * IT TAKES THE EXIT PRICE, NOT THE PROCEEDS, AND THAT IS THE POINT OF THE
     * SIGNATURE. The old one accepted a finished cash figure, so every caller had
     * to build one — and none of them had a real fill to build it from. Placing an
     * order returns an id, not an execution; T212 reports the price minutes later
     * (see [T212LiveMirror.reconcileFills]). So the callers each reached for the
     * nearest number to hand — a candle close, the ladder's reference price — and
     * published it as money. One of them then divided it by the entry to print a
     * percentage under the words "your money".
     *
     * That is the same class of error the fill reader carries a long comment
     * about: a real S92 exit filled at 58.05 was announced at 56.65, understating
     * a +3.29% trade as +0.80%. Numbers under that heading are read as settled
     * fact — they are what someone decides the strategy is working by.
     *
     * Taking the per-share exit instead makes the mistake unrepresentable. There
     * is no honest way to fake it: either the broker has reported a fill or it has
     * not, and [realExit] of 0 means it has not. In that case this says so plainly
     * and quotes NO cash and NO percentage, rather than quoting a guess.
     *
     * ONE ALERT PER TRADE, NOT ONE PER EVENT. The id is keyed to [tradeKey], so
     * the settled figure REPLACES the provisional notice in the tray instead of
     * stacking a second card under it. The old id was bucketed by wall-clock
     * minute, which had the opposite effect from the one intended: two different
     * subsystems reporting the same sale within the same minute silently
     * overwrote each other, and which of them the reader ended up seeing was
     * decided by ordering rather than by which one knew the real price.
     *
     * @param paid     per-share entry, in the instrument's currency. 0 = unknown,
     *                 which suppresses the percentage but not the cash figure.
     * @param realExit per-share exit as REPORTED BY THE BROKER. Never a candle
     *                 close, never the ladder's reference price. 0 = not yet known.
     * @param origin   which subsystem decided to sell, when that is not simply the
     *                 algo named by [ns]. Blank for an algo's own exit.
     */
    fun t212Sell(ctx: Context, ns: String, symbol: String, qty: Double, cur: String,
                 reason: String, tradeKey: String, origin: String = "",
                 paid: Double = 0.0, realExit: Double = 0.0,
                 broker: String = "Trading 212") {
        val t = sellAlertText(
            who = whoBought(ctx, ns, origin),
            symbol = symbol, qty = qty, cur = cur,
            // The origin can carry the kind of exit too ("its target at Trading 212").
            reason = listOf(reason, origin).filter { it.isNotBlank() }.joinToString(" · "),
            paid = paid, realExit = realExit, broker = broker, flag = flagOf(ns))
        notify(ctx, CH_T212, NotificationManager.IMPORTANCE_HIGH, "Stock LIVE trades",
            t.first, t.second, "t2s-$ns-${tradeKey.ifBlank { symbol }}", openNs = ns)
    }

    /**
     * A 1-week-high target is now waiting at Trading 212 for a position. Once per
     * position, quietly: it is a standing instruction, not a trade, and the sale
     * it may lead to gets its own loud alert when it happens.
     */
    fun yourSaleBooked(ctx: Context, ns: String, symbol: String, price: Double, paid: Double,
                       seatTo: String?, tradeKey: String, cur: String) {
        val pct = if (paid > 0.0) (price / paid - 1.0) * 100.0 else null
        notify(ctx, CH_T212, NotificationManager.IMPORTANCE_DEFAULT, "Stock LIVE trades",
            "💰 $symbol — your order sold it at ${"%.2f".format(price)}" +
                (pct?.let { " (${"%+.1f".format(it)}%)" } ?: ""),
            "${algoLabel(ctx, ns)} held $symbol, and an order you placed yourself sold it at " +
                "${"%.2f".format(price)} $cur. It is booked to the algo like a take-profit. " +
                (if (seatTo != null) "Its seat goes to $seatTo, bought on its next entry signal."
                 else "Its seat waits in cash until a rising contender comes up."),
            "t2y-$ns-${tradeKey.ifBlank { symbol }}", openNs = ns)
    }

    fun soldOutsideApp(ctx: Context, ns: String, symbol: String, seatTo: String?, tradeKey: String) {
        notify(ctx, CH_T212, NotificationManager.IMPORTANCE_DEFAULT, "Stock LIVE trades",
            "✋ $symbol — sold outside the app, removed from ${algoLabel(ctx, ns)}",
            "The $symbol shares were no longer in your Trading 212 account on two checks half an hour " +
                "apart, so ${algoLabel(ctx, ns)}'s position is closed as sold by you. Its paper result is " +
                "set aside, since the strategy did not choose that exit. " +
                (if (seatTo != null) "Its seat goes to $seatTo, bought on its next entry signal."
                 else "Its seat waits in cash until a rising contender comes up."),
            "t2x-$ns-${tradeKey.ifBlank { symbol }}", openNs = ns)
    }

    fun exitGuardSet(ctx: Context, ns: String, symbol: String, level: Double, decision: Double,
                     qty: Double, cur: String, tradeKey: String) {
        notify(ctx, CH_T212, NotificationManager.IMPORTANCE_LOW, "Stock LIVE trades",
            "\uD83D\uDEE1 $symbol — safety stop at ${"%.2f".format(level)} while its sale waits",
            "${algoLabel(ctx, ns)} · the strategy sold $symbol" +
                (if (decision > 0.0) " at ${"%.2f".format(decision)} $cur" else "") +
                ", but the shares could not be sold yet. A stop for ${"%.4f".format(qty)} now waits at " +
                "Trading 212 at ${"%.2f".format(level)} $cur: if the price falls there before the app " +
                "sells, Trading 212 sells it, even with the phone off. The app still tries to sell at " +
                "the next chance and takes the stop off first.",
            "t2g-$ns-${tradeKey.ifBlank { symbol }}", openNs = ns)
    }

    /** @param label which rule set the price, in words — "its 1-week high",
     *  "near its 1-month high". */
    fun weekTargetSet(ctx: Context, ns: String, symbol: String, price: Double, paid: Double,
                      qty: Double, cur: String, tradeKey: String,
                      label: String = "its 1-week high") {
        val pct = if (paid > 0.0) (price / paid - 1.0) * 100.0 else null
        notify(ctx, CH_T212, NotificationManager.IMPORTANCE_LOW, "Stock LIVE trades",
            "🎯 $symbol — target set at ${"%.2f".format(price)}" +
                (pct?.let { " (${"%+.1f".format(it)}%)" } ?: ""),
            "${algoLabel(ctx, ns)} · a sell for ${"%.4f".format(qty)} is waiting at Trading 212 " +
                "at ${"%.2f".format(price)} $cur ($label)" +
                (if (paid > 0.0) ", against the ${"%.2f".format(paid)} you paid" else "") +
                ". It sells there even with the phone off. The stop is still watched by the app, " +
                "and any other sale clears this order first.",
            "t2t-$ns-${tradeKey.ifBlank { symbol }}", openNs = ns)
    }

    /**
     * Title and body for a live sale — split out from [t212Sell] with no Android
     * types in it so the money can actually be tested.
     *
     * The rules it encodes are the ones that were wrong in the field, and each is
     * a case in LabNotifierSellTextTest:
     *   • a percentage appears only when BOTH ends are real fills;
     *   • an unpriced sale quotes no cash and no percentage, and says what it is
     *     waiting for, rather than dressing the nearest available number as money;
     *   • the cash figure is always the real exit times the quantity, so it cannot
     *     disagree with the percentage printed beside it.
     *
     * @return title to body.
     */
    internal fun sellAlertText(who: String, symbol: String, qty: Double, cur: String,
                               reason: String, paid: Double, realExit: Double,
                               broker: String, flag: String = ""): Pair<String, String> {
        // MINIMAL, ON THE USER'S ASK (16 Oct 2026): the title is the market, the
        // stock and the result; the body is who bought it and what sold it. No
        // quantities, no prices, nothing said twice — the detail is in the app.
        val settled = realExit > 0.0 && qty > 0.0
        val pct = if (settled && paid > 0.0) (realExit / paid - 1.0) * 100.0 else null
        val title = (if (flag.isNotBlank()) "$flag " else "") + "$symbol sold" +
            (pct?.let { " ${"%+.2f".format(it)}%" } ?: "")
        val body = listOf(who, shortReason(reason)).filter { it.isNotBlank() }.joinToString(" · ") +
            (if (settled) "" else " · result once $broker reports the price")
        return Pair(title, body)
    }

    /** The market's flag, for the front of a trade alert. */
    fun flagOf(ns: String): String = when (com.signalscope.app.trading.Spaces.baseOf(ns)) {
        "de" -> "🇩🇪"; "in" -> "🇮🇳"; "us" -> "🇺🇸"; "binance" -> "🪙"; else -> ""
    }

    /**
     * WHAT SOLD IT, in a few words. The reasons arrive as sentences written for
     * the log — "reached its target of 145.00 (its 1-week high)" — and the card
     * wants the kind of exit, not the sentence. Unknown wording is cut to its
     * first clause rather than dropped.
     */
    internal fun shortReason(reason: String): String {
        val r = reason.lowercase()
        return when {
            r.isBlank() -> ""
            ("week" in r && "high" in r) || "1-week" in r -> "1-week-high target reached"
            "month" in r && ("target" in r || "high" in r) -> "month target reached"
            ("your" in r || "you set" in r || "manual" in r) && ("target" in r || "take profit" in r || "take-profit" in r) ->
                "your target reached"
            "target" in r || "take profit" in r || "take-profit" in r -> "target reached"
            "atr" in r -> "ATR stop triggered"
            "bollinger" in r -> "Bollinger stop triggered"
            "trail" in r -> "trailing stop triggered"
            "stop" in r -> "stop-loss triggered"
            "macd" in r -> "MACD exit"
            "trend" in r -> "trend exit"
            "hold" in r && "day" in r -> "time limit"
            else -> reason.split(" · ", " (", " — ", ": ").first().trim().take(40)
        }
    }

    /** Who bought it: an algo by name, or the watcher — and whether the buy was
     *  your own, which the exit minder says in its [origin]. */
    private fun whoBought(ctx: Context, ns: String, origin: String): String {
        val watcher = try {
            com.signalscope.app.trading.Spaces.bucketIdOf(ns) == com.signalscope.app.data.ManualWatchStore.ID
        } catch (e: Exception) { false }
        val yours = origin.contains("yourself", true) || origin.contains("your own", true)
        return when {
            watcher && yours -> "Watcher · your buy"
            watcher -> "Watcher buy"
            else -> "Algo ${algoLabel(ctx, ns)}"
        }
    }

    /** Titles carry their result in colour where the phone shows it: a gain green,
     *  a loss red. */
    private fun coloured(title: String): CharSequence {
        val m = Regex("([+\\-−])\\d+(\\.\\d+)?%").find(title) ?: return title
        val s = android.text.SpannableString(title)
        val col = if (m.groupValues[1] == "+") 0xFF059669.toInt() else 0xFFDC2626.toInt()
        s.setSpan(android.text.style.ForegroundColorSpan(col), m.range.first, m.range.last + 1,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    /** An entry that could not be placed is being kept for another attempt. Told
     *  once per queued order, because a decision that is waiting rather than dead
     *  is a different thing to know about than one that failed outright. */
    /** The RV gate held an automatic order back for its full two hours and has now
     *  started it anyway. */
    fun rvGateTimedOut(ctx: Context, ns: String, symbol: String, buy: Boolean) =
        notify(ctx, CH_T212, NotificationManager.IMPORTANCE_DEFAULT, "Stock LIVE trades",
            "⏱ $symbol — patient ${if (buy) "buy" else "sale"} started after 2 hours",
            "${algoLabel(ctx, ns)}'s ${if (buy) "buy" else "sale"} waited the RV gate's full two hours for a " +
                "better moment (${if (buy) "price low in today's range, or rising" else "price high in today's range, or falling"}) " +
                "and none came, so the patient ${if (buy) "buy" else "sale"} has started now.",
            "rvt-$ns-$symbol-${System.currentTimeMillis() / 60000}", openNs = ns)

    /** One of YOUR patient orders, held by the RV gate, has now run. */
    fun rvManualDone(ctx: Context, space: String, symbol: String, buy: Boolean,
                     timedOut: Boolean, why: String, result: String) =
        notify(ctx, CH_T212, NotificationManager.IMPORTANCE_HIGH, "Stock LIVE trades",
            (if (timedOut) "⏱ " else "✅ ") + "$symbol — your patient ${if (buy) "buy" else "sale"} " +
                (if (timedOut) "started after 2 hours" else "started by the RV gate"),
            (if (timedOut) "No better moment came in two hours of trading, so it ran anyway. "
             else "The RV gate found its moment: $why. ") + "Result: $result.",
            "rvm-$space-$symbol-${System.currentTimeMillis() / 60000}",
            openSymbol = symbol, openSpace = space.lowercase())

    /**
     * A buy you asked to wait on the RV gate was ready to go, but the price had
     * run more than half a day's move above where you pressed the button. Nothing
     * bought — it asks first, as the algos' price ceiling refuses outright.
     */
    fun rvManualAsk(ctx: Context, space: String, symbol: String, pressed: Double, now: Double, limit: Double) =
        notify(ctx, CH_T212, NotificationManager.IMPORTANCE_HIGH, "Stock LIVE trades",
            "⏸ $symbol ran up — still want it?",
            "You asked to buy at ${"%.2f".format(pressed)}; it is now ${"%.2f".format(now)}, above your " +
                "limit of ${"%.2f".format(limit)} (half a day's move higher). Nothing has been bought. " +
                "Open Waiting orders to buy anyway or call it off — unanswered, it lapses at the close.",
            "rva-$space-$symbol-${System.currentTimeMillis() / 60000}",
            openSymbol = symbol, openSpace = space.lowercase())

    fun t212EntryQueued(ctx: Context, ns: String, symbol: String, why: String) =
        notify(ctx, CH_T212, NotificationManager.IMPORTANCE_DEFAULT, "Stock LIVE trades",
            "⏳ $symbol — buy queued for retry",
            "${algoLabel(ctx, ns)} could not fill: $why. It will retry for as long as the " +
                "strategy holds the position on paper, and give up if it exits or the price " +
                "runs past the chase limit.",
            "t2q-$ns-$symbol-${System.currentTimeMillis() / 60000}", openNs = ns)

    /**
     * A holding has dropped through the level the trailing managers protect it at.
     *
     * High importance, its own wording, and deliberately NOT a claim that anything
     * was sold: the protection is a resting order at the broker, and this is only
     * the app noticing sooner than its once-a-day pass would. Saying "sold" here
     * when nothing sold would be the worst possible error on this channel.
     */
    fun heldPositionBreak(ctx: Context, space: String, symbol: String,
                          price: Double, stop: Double, pct: Double, qty: Double) =
        notify(ctx, CH_T212, NotificationManager.IMPORTANCE_HIGH, "Stock LIVE trades",
            "🔻 $symbol below its stop level",
            "${spaceLabel(space)} · ${"%.4f".format(qty)} held, now ${"%.2f".format(price)} " +
                "against a stop level of ${"%.2f".format(stop)} (${"%+.1f".format(pct)}% on cost). " +
                "Nothing has been sold — your resting order at the broker is what protects it. " +
                "Review and decide.",
            "hb-$space-$symbol-${java.time.LocalDate.now(java.time.ZoneOffset.UTC)}",
            openSymbol = symbol, openSpace = space)

    /**
     * Entries decided after the close, waiting for the next open.
     *
     * A decision made on the day's last bar used to be silent in both directions:
     * nothing said it had been made, and nothing said it had expired overnight.
     * The only evidence was a name that never appeared in the book.
     *
     * NORMAL importance, not high. This is a heads-up about tomorrow, not
     * something to act on tonight — the market is shut, so there is nothing the
     * reader could do with an interruption even if they wanted to.
     *
     * IT PROMISES NOTHING. Each of these still has to pass the setup check, the
     * chase ceiling and the momentum gate in the morning, and several will not.
     * Wording it as a queue rather than a plan is the difference between a
     * heads-up and a claim the app cannot keep.
     */
    fun entriesParkedForOpen(ctx: Context, space: String, symbols: List<String>) {
        if (symbols.isEmpty()) return
        notify(ctx, CH_T212, NotificationManager.IMPORTANCE_DEFAULT, "Stock LIVE trades",
            "🌙 ${symbols.size} entr${if (symbols.size == 1) "y" else "ies"} waiting for the open",
            "${spaceLabel(space)} · ${symbols.joinToString(", ")}. " +
                "Decided after the close, so nothing could be bought tonight — no attempts are " +
                "being wasted on a shut market. Each will be re-checked at the open and bought " +
                "only if the setup still stands, the price has not run above its limit, and the " +
                "last 15 minutes are trending up.",
            // One card per space per day: a second name parking later replaces it
            // with the fuller list rather than stacking a near-identical alert.
            "pk-$space-${java.time.LocalDate.now(java.time.ZoneOffset.UTC)}")
    }

    /**
     * A holding your STRATEGY says to exit — not merely one below a stop level.
     *
     * Deliberately worded to say what fired and what has NOT happened. A holding
     * you bought years ago is not a trade the app opened, and an alert about it
     * must never read as though something has been sold on your behalf.
     */
    fun portfolioStrategyExit(ctx: Context, space: String, symbol: String,
                              price: Double, reason: String, pct: Double,
                              qty: Double, sold: Boolean) =
        notify(ctx, CH_T212, NotificationManager.IMPORTANCE_HIGH, "Stock LIVE trades",
            (if (sold) "🔴 SOLD $symbol — strategy exit" else "⚠️ $symbol — your strategy says exit"),
            "${spaceLabel(space)} · ${"%.4f".format(qty)} held at ${"%.2f".format(price)} " +
                "(${"%+.1f".format(pct)}% on cost). Rule: $reason. " +
                (if (sold) "The position has been sold."
                 else "Nothing has been sold — this is a prompt, the decision is yours."),
            "px-$space-$symbol-${java.time.LocalDate.now(java.time.ZoneOffset.UTC)}",
            openSymbol = symbol, openSpace = space)

    /**
     * A MARKET THE APP CANNOT TRADE FOR YOU.
     *
     * India needs the session re-authorised by hand from this device, refuses
     * ordinary orders from a phone's address, and falls back to a trigger parked
     * a fraction from the price. So nothing here happens on its own, and a paper
     * trade stops being a report of something done — it is the only notice that a
     * decision is open, and it is worth interrupting for.
     */
    /**
     * Does this algo need the user to act, or is it reporting something it did?
     *
     * India used to answer "always prompt", on the reasoning that nothing could
     * be placed there without a person. That stopped being true the moment an
     * Indian algo could be armed for real money — and the wording then actively
     * misled: "Conservative would BUY SYRMA · nothing has been bought" was sent
     * at the very moment the algo was placing a real order for it, and the order
     * was rejected for an unrelated reason nobody was told about.
     *
     * So it asks the algo instead of the market. An armed one reports; an unarmed
     * one prompts, which is still every Indian algo the user has not armed and
     * every paper algo everywhere.
     */
    private fun promptsRatherThanReports(ctx: Context, ns: String): Boolean = try {
        if (!com.signalscope.app.trading.Spaces.isBucket(ns)) true
        else com.signalscope.app.data.BucketStore.get(ctx, ns)
            ?.let { !(it.live && it.liveBudget > 0.0 && it.liveAllowlist.isNotEmpty()) } ?: true
    } catch (e: Exception) { true }

    /**
     * A holding the trailing stop cannot protect, because nothing is guarding it.
     *
     * Every other decline on that path is the system choosing NOT to tighten a
     * floor that already exists — unremarkable, and a log line is enough. This one
     * means there is no floor: the position is naked and the only place that said
     * so was a log nobody reads. Once a day, because it stays true until fixed.
     */
    fun unprotectedHolding(ctx: Context, ns: String, symbol: String) {
        val day = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        notify(ctx, CH_T212, NotificationManager.IMPORTANCE_HIGH, "Stock LIVE trades",
            "⚠️ $symbol has no stop at the broker",
            "${spaceLabel(ns)} · your trailing stop had nothing to raise — there is no " +
                "resting sell trigger on this holding, so it is unprotected. Set one from the " +
                "position, or run the stop manager.",
            "np-$ns-$symbol-$day", openSymbol = symbol, openSpace = Spaces.uiSpaceOf(ns))
    }

    /**
     * A FLOOR-ORDER ALGO'S PAPER BUY. These algos cannot place real orders at all,
     * so the text says so every time — the one misreading that matters is
     * thinking money moved. Under the market's paper switch like any paper alert;
     * the algo's own bell is checked by the caller.
     */
    fun floorBought(ctx: Context, algo: String, ns: String, symbol: String, price: Double, what: String) {
        if (paperMuted(ctx, ns)) return
        notify(ctx, CH_PAPER, NotificationManager.IMPORTANCE_DEFAULT, "Lab paper trades",
            "📥 $algo bought $symbol (paper)",
            "${spaceLabel(ns)} · $what. Paper only — nothing real was bought.",
            "fb-$ns-$symbol-$price", openNs = ns)
    }

    /** A campaign crossing a level it was told to watch — the weekly resistance,
     *  the monthly resistance zone. A price observation, not a trade. */
    fun campaignNote(ctx: Context, algo: String, ns: String, symbol: String, what: String) {
        if (paperMuted(ctx, ns)) return
        notify(ctx, CH_PAPER, NotificationManager.IMPORTANCE_DEFAULT, "Lab paper trades",
            "🎯 $algo · $symbol",
            "${spaceLabel(ns)} · $what. Paper only.",
            "fc-$ns-$symbol-${what.hashCode()}", openNs = ns)
    }

    fun floorSold(ctx: Context, algo: String, ns: String, symbol: String, price: Double,
                  pct: Double, what: String) {
        if (paperMuted(ctx, ns)) return
        notify(ctx, CH_PAPER, NotificationManager.IMPORTANCE_DEFAULT, "Lab paper trades",
            "📤 $algo sold $symbol (paper)  ${"%+.2f".format(pct)}%",
            "${spaceLabel(ns)} · $what. Paper only — nothing real was sold.",
            "fs-$ns-$symbol-$price", openNs = ns)
    }

    /**
     * [late] is set when the signal is from a bar that finished well before this
     * pass — a catch-up, held back to the market's open rather than announced at
     * night as though it had just happened. It says which bar, in plain words.
     */
    fun paperOpen(ctx: Context, ns: String, symbol: String, price: Double, late: String? = null) {
        if (paperMuted(ctx, ns)) return
        val prompt = promptsRatherThanReports(ctx, ns)
        notify(ctx, CH_PAPER,
            if (prompt) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT,
            "Lab paper trades",
            (if (prompt) "🟢 ${algoLabel(ctx, ns)} would BUY $symbol" + (if (late != null) " (late)" else "")
             else "📈 ${algoLabel(ctx, ns)} bought $symbol (paper${if (late != null) ", late" else ""})"),
            "${spaceLabel(ns)} · ${"%.2f".format(price)}. " +
                (if (late != null) "$late. " else "") +
                (if (prompt)
                    "Nothing has been bought — this algo is not armed for real money. " +
                    "If you want it, buy it yourself; the app will manage the exit."
                 else "Paper position opened; the real order is on its own line if one went out. " +
                      "Tap to open this algo."),
            "po-$ns-$symbol-$price", openNs = ns)
    }

    fun paperClose(ctx: Context, ns: String, symbol: String, pctReturn: Double, reason: String,
                   late: String? = null) {
        if (paperMuted(ctx, ns)) return
        val prompt = promptsRatherThanReports(ctx, ns)
        notify(ctx, CH_PAPER,
            if (prompt) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT,
            "Lab paper trades",
            (if (prompt) "🔴 ${algoLabel(ctx, ns)} would SELL $symbol (${"%+.2f".format(pctReturn)}%)"
             else "📉 ${algoLabel(ctx, ns)} sold $symbol  (${"%+.2f".format(pctReturn)}%)") +
                (if (late != null) " · late" else ""),
            "${spaceLabel(ns)} · ${"%+.2f".format(pctReturn)}%" +
                (if (reason.isNotBlank()) " · $reason" else "") +
                (if (late != null) ". $late" else "") +
                (if (prompt) ". Nothing has been sold — sell it yourself if you agree."
                 else ". Tap to open this algo."),
            "pc-$ns-$symbol-$pctReturn", openNs = ns)
    }

    /**
     * The ledger and the broker disagree about a POSITION, not a price.
     *
     * HIGH importance on the live channel deliberately: every other number the app
     * shows is computed off the position, so while this is unresolved the P&L, the
     * seat balances and the next entry size are all quietly wrong. This is the one
     * background finding that should interrupt.
     *
     * The id folds in the hour, so a persistent drift re-notifies once an hour
     * instead of silently collapsing into the first notification and being missed.
     */
    /**
     * A LADDER THAT RAN AND DID NOT FILL, while the market was open.
     *
     * Worth interrupting for, unlike the other decline reasons. A frozen switch
     * or an after-hours signal are states you can reason about later; this one is
     * a live opportunity that is quietly slipping away — the strategy wanted the
     * position, the market was there to sell it, and the chase simply did not
     * reach. It is also the only case where a human can still act: the name can
     * be bought by hand from the Portfolio tab while the price is near.
     *
     * Deliberately NOT sent for a closed market. Telling someone to go and place
     * a manual order at 18:01 on a shut exchange is advice they cannot take, and
     * a notification that cannot be acted on is the kind people learn to swipe
     * away — taking the useful ones with it.
     */
    fun ladderUnfilled(ctx: Context, algo: String, symbol: String,
                       amount: Double, currency: String, note: String) =
        notify(ctx, CH_LIVE, NotificationManager.IMPORTANCE_HIGH, "Binance LIVE trades",
            "⏳ $symbol not filled — buy it by hand?",
            "$algo wanted ${"%.0f".format(amount)} $currency of $symbol and the patient " +
                "ladder walked its whole range without filling" +
                (if (note.isNotBlank()) " ($note)" else "") + ".\n\n" +
                "The market is open, so you can still take it: open Portfolio, find " +
                "$symbol and use Patient buy — or Buy now if you want it immediately. " +
                "The paper trade is already open, so doing nothing leaves paper and " +
                "real disagreeing on this name.",
            "lu-$symbol-${System.currentTimeMillis() / 600_000}")

    /**
     * AN EXIT THAT DID NOT FILL. Louder than the entry case, deliberately.
     *
     * A missed entry costs an opportunity. A missed exit leaves real money in a
     * position the strategy has already decided to leave — still exposed, and no
     * longer managed by anything except the hope that the next attempt does
     * better. The paper book has already booked the sale, so every figure that
     * follows is describing a position you still hold.
     */
    fun ladderExitUnfilled(ctx: Context, algo: String, symbol: String,
                           qty: Double, note: String) =
        notify(ctx, CH_LIVE, NotificationManager.IMPORTANCE_HIGH, "Binance LIVE trades",
            "⚠ $symbol did NOT sell — you still hold it",
            "$algo tried to exit ${"%.5f".format(qty).trimEnd('0').trimEnd('.')} of $symbol " +
                "and the ladder filled nothing" + (if (note.isNotBlank()) " ($note)" else "") + ".\n\n" +
                "Paper has already booked this exit, so the algo's P&L now describes a " +
                "position you are still exposed to. The market is open — sell it by hand " +
                "from Portfolio (Patient sell, or Sell now to be out immediately).",
            "lx-$symbol-${System.currentTimeMillis() / 600_000}")

    /**
     * The ledger and the broker disagree about what is held.
     *
     * ONCE A DAY, NOT ONCE AN HOUR, and on the STOCK channel rather than crypto's.
     *
     * The id used to carry the hour, so every hourly audit minted a new
     * notification that could not replace the last one. For a mismatch that
     * resolves itself within the hour that is merely redundant; for one that
     * cannot resolve — a Continental row abandoned mid-exit on 8 September — it
     * was an alarm every hour for ten days about 0.698 shares. An alert that
     * cannot be silenced by fixing the thing it describes teaches you to swipe it
     * away, which costs you the next one that matters.
     *
     * The dead-end that produced that particular case is fixed at the source (an
     * abandoned exit is now adopted, and a row whose shares are gone is closed),
     * so this should now stop on its own. The daily id is the backstop for
     * whatever the next unresolvable one turns out to be.
     */
    fun positionDrift(ctx: Context, count: Int) =
        notify(ctx, CH_T212, NotificationManager.IMPORTANCE_HIGH, "Stock LIVE trades",
            "⚠ Position mismatch ($count)",
            "The app's ledger disagrees with Trading 212 about $count holding" +
                (if (count == 1) "" else "s") + ". Open Algos to see the diff — P&L is unreliable " +
                "until it is resolved. Repeats once a day while it stands.",
            "pd-${java.time.LocalDate.now(java.time.ZoneOffset.UTC)}")

    /**
     * A NAME WHOSE PRICE FEED HAS STOPPED while its market is open — see
     * FeedHealth for how that is told apart from a quiet one.
     *
     * HIGH IMPORTANCE, on the stock channel, because nothing about the name can
     * be trusted while this stands: its value on the panel is the last price the
     * feed ever gave, the stops and targets the app watches read that price, and
     * the app refuses to buy it. Pfisterer sat like that for a week with nothing
     * said. Once a day per name, since it stays true until the feed moves.
     *
     * [held] picks the wording — for a position the risk is a fall nobody sees,
     * for a candidate it is a signal drawn on a frozen chart.
     */
    fun feedStopped(ctx: Context, space: String, symbol: String, feedSymbol: String,
                    lastTradeSec: Long, held: Boolean) {
        val day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
        if (!com.signalscope.app.network.FeedHealth.claimAlarm(feedSymbol, day)) return
        val since = com.signalscope.app.network.FeedHealth.describe(lastTradeSec)
        notify(ctx, CH_T212, NotificationManager.IMPORTANCE_HIGH, "Stock LIVE trades",
            "⚠ $symbol — price feed stopped",
            "${spaceLabel(space)} · no trade on $feedSymbol since $since, although the market " +
                "is open. " +
                (if (held) "You hold it, and the app's exits read this feed — where no live " +
                    "venue price stands in, its stop and target are watching a frozen number. " +
                    "Check it at the broker. "
                 else "Its signals are drawn on a frozen chart. ") +
                "The app will not buy it until the feed moves again. Often the listing has " +
                "moved to a new symbol.",
            "fs-$feedSymbol-$day", openSymbol = symbol, openSpace = Spaces.uiSpaceOf(space))
    }
}

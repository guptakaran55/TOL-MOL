package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.BucketStore
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.MacdGateStore
import com.signalscope.app.data.ManualWatchStore
import com.signalscope.app.data.StrategyLabStore
import com.signalscope.app.network.Trading212Client
import com.signalscope.app.network.YahooFinanceClient
import com.signalscope.app.screener.DailyBar
import com.signalscope.app.screener.ScreenerConfig
import com.signalscope.app.screener.ScreenerRunner
import com.signalscope.app.screener.StockScreener
import com.signalscope.app.util.Indicators
import java.time.LocalDate
import java.time.ZoneOffset

// ════════════════════════════════════════════════════════════════════════════
// MANUAL ENTRY WATCHER
// ════════════════════════════════════════════════════════════════════════════
// The gate ranks the whole universe daily and then only ever acts on the few
// names it has seats for. Everything else it judged and PASSED is discarded.
// This watches the best of those and tells the user when an entry actually
// fires on one — a decision the user then makes by hand.
//
// THE DIVISION OF LABOUR, which is the whole design:
//   • the GATES decide WHO is worth watching  (rank + positive daily slope)
//   • the WATCHER CONFIG decides WHEN to enter (its own entry rules and frames)
//   • the same WATCHER CONFIG then manages the EXIT of anything bought
//
// That last line is why the config is per space and lives on a reserved
// namespace: an entry judged by one config and exited by another would measure
// its stop and its MACD-distance qualifier from an entry it would never have
// taken. One config owns a manual trade from signal to exit, or none does.
//
// NOTHING HERE PLACES AN ORDER BY ITSELF. [runOnce] only reads and notifies.
// [buyAndHandOver] places a real order and is reachable only from a user tap.
// ════════════════════════════════════════════════════════════════════════════

object ManualEntryWatcher {

    private const val TAG = "ManualEntryWatcher"

    /**
     * What the daily pass produces. BUMP THIS whenever an artefact is added or
     * its shape changes — that is what makes existing installs rebuild once
     * instead of waiting for tomorrow and showing an empty feature meanwhile.
     *
     *   1 — ranking only
     *   2 — ranking + sparklines
     *   3 — ranking + sparklines + TTM squeeze
     *   4 — same, but on bars that finally carry a high and a low. Build 3
     *       scored nothing: the adjusted-close feed set every OHLC field to the
     *       close, so every bar was flat and the scan refused all of them.
     *   5 — same, with the squeeze tiers the right way round. Build 4 reported
     *       the LOOSEST channel every time and called it the tightest, which put
     *       40% of a 105-name market in the strictest bucket.
     *   6 — adds the same squeeze measured on weekly bars, folded from the same
     *       dailies. No extra downloads; a second clock on one set of data.
     *   7 — screens the LIVE index instead of the list typed into the app, and
     *       stores a short MACD curve shaped like Discovery's. Build 6 ranked a
     *       market that no longer exists: names dropped from the index were still
     *       offered with a Buy button, and — the half that actually cost
     *       something — names ADDED to it were never screened at all.
     */
    // 8: the projected wave channel joined the daily artefacts. The bump is what
    // makes it appear TODAY rather than tomorrow — every install already carries
    // today's date, so without it the guard skips the pass and the new artefact
    // simply never arrives, looking exactly like a market with nothing in it.
    // 9: today's pass had ranked on a series missing the last session (see
    // ScreenerRunner.dailyDataCurrent); the bump redoes it on corrected prices.
    // 10: the slope is no longer smoothed by default (see [MacdSlopes]); the bump
    // re-ranks today on the unsmoothed figure instead of waiting for tomorrow.
    private const val DAILY_BUILD = 10

    /** Fallback depth when no context is available to read the setting from.
     *  The real value lives in settings — see [depthFor]. */
    const val DEFAULT_LIMIT = 30

    /**
     * How many names to follow in a space, from the user's setting.
     *
     * Read per call rather than captured once: changing it should take effect on
     * the next pass, not the next restart. Clamped, because this number decides
     * how many price downloads happen every tick and a typo of 600 would spend
     * the feed's patience within a pass.
     */
    fun depthFor(ctx: Context): Int =
        try { ConfigManager(ctx).watcherDepth.coerceIn(5, 60) } catch (e: Exception) { DEFAULT_LIMIT }

    /**
     * Spaces the watcher runs in. Deliberately NOT the bucketable-spaces list.
     *
     * That list says where ALGOS may exist, and US is excluded from it because
     * Trading 212 settles in the account's home currency, which makes a
     * dollar-denominated automated algo unusable. None of that applies here: the
     * watcher only screens, notifies, and — where a real order path exists — buys
     * what the user taps. Screening US names is useful precisely BECAUSE no algo
     * is doing it.
     */
    val WATCHED_SPACES = listOf("de", "us", "in")

    /**
     * Can a manual buy actually be placed in this space?
     *
     * Germany and the US both execute through Trading 212. India does not: order
     * placement there is Zerodha's, a separate path with its own arming gates that
     * this feature has never been wired to. So India gets the screen, the alerts
     * and the chart, and no Buy button — an button that silently does nothing is
     * worse than none, and one that half-works with real money is worse again.
     */
    fun canBuyIn(space: String): Boolean = Spaces.baseOf(space.lowercase()) in listOf("de", "us", "in")

    data class Candidate(
        val symbol: String,
        val rank: Int,
        val score: Double?,
        val slope: Double,
        /** The gate that nominated it — shown so a candidate can be traced back
         *  to the algo whose screener ranked it. */
        val fromNs: String
    )

    // ── the watcher's OWN daily screen ─────────────────────────────────────
    /** The space's full universe. The watcher screens ALL of it, not any algo's
     *  symbol group — it is a separate lens on the same market, not a follower. */
    private fun universeFor(ctx: Context, space: String): List<String> {
        val base = Spaces.baseOf(space)
        val index = when (base) {
            "de" -> com.signalscope.app.service.deScreenedUniverse(ctx).map { it.removeSuffix(".DE") }
            "in" -> nseUniverse(ctx)
            "binance" -> return emptyList()
            else -> com.signalscope.app.service.NASDAQ_100_SYMBOLS
        }
        // ── PINNED NAMES BELONG IN THE DAILY PASS TOO ───────────────────────
        //
        // A pin is allowed to be a name the index has never heard of — that is
        // most of the point of pinning. But everything this pass builds is keyed
        // to the universe it screens, so a pin from outside it arrived on the
        // list with no sparkline, no MACD curve and no projected channel: the
        // columns simply sat empty, which reads as a broken row rather than as a
        // name nobody screened.
        //
        // THE UNIVERSE IS PART OF THE PASS STAMP, so adding one re-runs the pass
        // by itself — no build bump needed, and a pin added at three in the
        // afternoon gets its artefacts on the next tick rather than tomorrow.
        //
        // A pinned name now also gets RANKED, which is a small change worth
        // naming: it will carry a real rank and score instead of a zero. It does
        // not gain a seat it had not earned — the pin already bypassed the depth
        // cut and the slope gate, and that is unchanged. The only cost is that a
        // handful of extra names shift the percentile base very slightly, which
        // against a hundred or six hundred is not a number anyone could see.
        val pinned = try {
            ManualWatchStore.load(ctx, base).pinned.map { it.trim().uppercase() }
        } catch (e: Exception) { emptyList() }
        return (index + pinned).distinct()
    }

    /**
     * India's universe: the live index plus everything the app's own list adds.
     *
     * The union lives in one place next to the embedded list — see the note on
     * [com.signalscope.app.service.nseScreenedUniverse] for why it is a union and
     * not simply whichever of the two is fresher. What matters here is only that
     * the watcher asks the SAME question discovery asks. Reading a different list
     * is what made a name appear on one screen and not the other, with no way to
     * tell from either screen which one was wrong.
     */
    private fun nseUniverse(ctx: Context): List<String> {
        val syms = com.signalscope.app.service.nseScreenedUniverse(ctx)
        Log.i(TAG, "[in] screening ${syms.size} names")
        return syms
    }

    /** EMA recipe identical to MacdGateEngine's, so a name the algos' gate passes
     *  and one the watcher passes mean the same thing. */
    private fun ema(x: DoubleArray, span: Int): DoubleArray {
        val s = if (span < 2) 2 else span
        val a = 2.0 / (s + 1.0)
        val out = DoubleArray(x.size)
        if (x.isEmpty()) return out
        out[0] = x[0]
        for (i in 1 until x.size) out[i] = a * x[i] + (1 - a) * out[i - 1]
        return out
    }

    /** A short fingerprint of exactly which names were screened. Size alone would
     *  miss a swap of one constituent for another, which is the commonest kind of
     *  index revision there is. */
    private fun universeTag(syms: List<String>): String =
        "${syms.size}-${syms.sorted().joinToString(",").hashCode().toString(16)}"

    /**
     * The MACD phase, by the same rule the Discovery analyser applies.
     *
     * Copied as a rule rather than shared as code because the analyser derives it
     * halfway through a hundred-line function over data this pass does not have.
     * What matters is that the WORDING matches: the shared chart renderer picks
     * its colour from this string, so a phase invented here in different words
     * would draw the same stock a different colour on the two screens — which is
     * exactly the disagreement this whole change exists to remove.
     */
    private fun macdPhaseOf(macd: DoubleArray): String {
        if (macd.size < 4) return ""
        val n = macd.size
        val slope = macd[n - 1] - macd[n - 2]
        val slopePrev = macd[n - 2] - macd[n - 3]
        val slopePrev2 = macd[n - 3] - macd[n - 4]
        val accel = slope - slopePrev
        val floorMag = 0.01
        val wasNeg2d = slopePrev <= -floorMag && slopePrev2 <= -floorMag
        val wasPos2d = slopePrev >= floorMag && slopePrev2 >= floorMag
        val wasNegWeak = slopePrev <= 0 && slopePrev2 <= 0 &&
            (Math.abs(slopePrev) + Math.abs(slopePrev2)) > floorMag
        val wasPosWeak = slopePrev >= 0 && slopePrev2 >= 0 &&
            (Math.abs(slopePrev) + Math.abs(slopePrev2)) > floorMag
        return when {
            slope > 0 && (wasNeg2d || wasNegWeak) -> "BUY FLIP"
            slope < 0 && accel > 0 -> "EARLY BUY"
            slope < 0 && (wasPos2d || wasPosWeak) -> "SELL FLIP"
            slope > 0 && accel < 0 -> "EARLY SELL"
            slope > 0 && accel >= 0 -> "BULLISH"
            slope < 0 && accel <= 0 -> "BEARISH"
            else -> "NEUTRAL"
        }
    }

    private fun latestSlope(closes: DoubleArray, smoothing: Int): Double? {
        // One definition, shared with the five-timescale dots on the alerts, so
        // the SLOPE column and the 1D dot can never disagree. See [MacdSlopes].
        return MacdSlopes.latest(closes, smoothing)
    }

    /**
     * The watcher's own screen: rank the whole space universe and measure each
     * name's daily MACD slope. Runs at most once per UTC day.
     *
     * INDEPENDENT OF THE ALGOS BY DESIGN. It used to merge the algos' stored gate
     * rankings, which made "the watch list" the union of six lists over six symbol
     * groups — a thing with no single meaning, and the source of an exclusion bug
     * where a name seated in one algo could still be nominated by another.
     *
     * Running its own screen costs nothing extra: [ScreenerRunner.fetchUniverseBars]
     * shares one download per symbol per UTC day across every caller, so whichever
     * of the algos or the watcher runs first pays for the bars and the rest read
     * them. The ranking itself is arithmetic over data already in hand.
     *
     * NO-LOOKAHEAD: bars dated today are dropped before ranking, exactly as the
     * algos' gate does, so an in-progress session can never leak into a slope.
     */
    fun evaluateDaily(ctx: Context, space: String, force: Boolean = false): Boolean {
        val base = Spaces.baseOf(space.lowercase())
        val ns = ManualWatchStore.nsFor(base)
        val day = today()
        val s0 = ManualWatchStore.load(ctx, base)
        // RE-RUN WHEN THE DAY CHANGES **OR** WHEN THIS BUILD PRODUCES ARTEFACTS
        // THE STORED PASS DID NOT.
        //
        // Guarding on the date alone has now failed twice in the same way. The
        // pass makes the ranking, the sparklines and the squeeze from one set of
        // bars; add a new one and every existing install already holds today's
        // date, so the pass is skipped and the new thing never appears — for the
        // rest of the day, looking precisely like a market where nothing is
        // happening rather than a feature that has not run.
        //
        // Costs nothing to redo: the bars are cached for the UTC day, so a repeat
        // pass is arithmetic over data already in hand and reproduces the same
        // ranking.
        // THE UNIVERSE IS PART OF THE STAMP. Reading it is a preference lookup and
        // some string splitting — no network — so it is cheap to do before the
        // skip check, and doing it after was quietly wrong: a pass that ran on the
        // embedded list before the day's first scan would stamp the day and then
        // refuse to re-run once the live index arrived. Folding the membership in
        // means the pass redoes itself the moment the market it screens changes,
        // which also covers an index revision landing mid-day.
        val syms = universeFor(ctx, base)
        if (syms.isEmpty()) return false
        val stamp = "$day#$DAILY_BUILD@${universeTag(syms)}"
        if (!force && s0.lastDailyBuild == stamp) return false

        val cfg = StrategyLabStore.activeConfig(ctx, ns)
        val smoothing = cfg.slopeSmoothingOverride ?: MacdSlopes.defaultSmoothing(base)
        val scrCfg = cfg.screenerLookbackOverride
            ?.let { ScreenerConfig(lookbackDays = it.toLong()) } ?: ScreenerConfig()

        val (universe, _) = ScreenerRunner.fetchUniverseBars(Spaces.dataSpaceOf(base), syms)
        if (universe.isEmpty()) {
            Log.w(TAG, "[$base] watcher screen: no data")
            return false
        }
        val cutoff = LocalDate.now(ZoneOffset.UTC)
        val complete = LinkedHashMap<String, List<DailyBar>>()
        for ((sym, bars) in universe) {
            val b = bars.filter { it.date.isBefore(cutoff) }
            if (b.size >= 30) complete[sym] = b
        }
        if (complete.isEmpty()) return false
        // A series missing the last session would rank — and colour every 1D dot —
        // on the close before last, all day. Left unstamped, the next pass retries.
        val newest = complete.values.mapNotNull { it.lastOrNull()?.date }.maxOrNull()
        if (!ScreenerRunner.dailyDataCurrent(Spaces.dataSpaceOf(base), newest)) {
            Log.w(TAG, "[$base] watcher screen: daily prices end at $newest — last session missing, retried next pass")
            return false
        }

        // ── THE LIQUIDITY FLOOR ─────────────────────────────────────────────
        //
        // Applied here, once a day, on bars already in hand. It exists because a
        // universe built by unioning a downloaded index list with a stored one
        // only ever grows, and what it grows by is the long tail — names too
        // thin for the cost model's 0.20% slippage assumption to survive, and
        // easy for a momentum ranking to mistake for opportunities.
        //
        // NOTHING YOU OWN OR CHOSE IS EVER CUT. A held position dropped from the
        // universe would lose its ranking row, its sparkline and its projected
        // channel — the app would stop describing a real position because it got
        // quiet, which is the same shape of mistake as retiring a holding for
        // being absent from one broker read. A pin is an explicit instruction and
        // outranks a general rule by definition.
        val floor = try { ConfigManager(ctx).minTurnoverFor(base) }
                    catch (e: Exception) { 0.0 }
        var cut = 0
        if (floor > 0.0) {
            // UNREADABLE MEANS DO NOT FILTER AT ALL. Without the exemption list
            // there is no way to tell a name the user pinned from one the tail
            // dragged in, and filtering blind would cut the very rows that must
            // never be cut.
            val exempt: HashSet<String>? = try {
                val st = ManualWatchStore.load(ctx, base)
                HashSet<String>().apply {
                    st.pinned.forEach { add(it.uppercase()) }
                    st.entries.filter { it.status == "OPEN" || it.status == "PENDING" }
                        .forEach { add(it.symbol.uppercase()) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[$base] pins/holdings unreadable — liquidity filter skipped")
                null
            }
            val keep = if (exempt == null) complete else LinkedHashMap<String, List<DailyBar>>()
            if (exempt != null) for ((sym, bars) in complete) {
                if (sym.uppercase() in exempt ||
                    com.signalscope.app.screener.Liquidity.passes(bars, floor)) keep[sym] = bars
                else cut++
            }
            if (keep.isNotEmpty()) {
                complete.clear(); complete.putAll(keep)
                if (cut > 0) Log.i(TAG, "[$base] liquidity floor removed $cut of " +
                    "${cut + keep.size} name(s) below ${"%,.0f".format(floor)} median daily turnover")
            } else {
                // EVERYTHING FAILED, WHICH IS NOT A MARKET, IT IS A BUG. A floor
                // typed with too many zeros, or a feed that stopped sending
                // volume, must not empty the screen — leave the universe alone
                // and say so.
                Log.w(TAG, "[$base] liquidity floor of ${"%,.0f".format(floor)} would remove " +
                    "EVERY name — ignoring it this pass, the figure or the volume feed is wrong")
                cut = 0
            }
        }
        val asOf = complete.values.flatten().maxOfOrNull { it.date } ?: return false

        val ranked = StockScreener.screen(scrCfg, complete, asOf)
        val slopes = HashMap<String, Double>()
        for ((sym, bars) in complete)
            latestSlope(DoubleArray(bars.size) { bars[it].close }, smoothing)?.let { slopes[sym] = it }

        val rows = ranked.map {
            ManualWatchStore.RankRow(it.symbol, it.rank, it.score, slopes[it.symbol.uppercase()] ?: 0.0)
        }
        ManualWatchStore.mutate(ctx, base) {
            it.copy(lastEvalDay = day, rankedAt = System.currentTimeMillis(),
                ranking = rows, scanned = complete.size,
                illiquidCut = cut, turnoverFloor = floor,
                eligible = rows.count { r -> r.slope > 0.0 })
        }

        // WHAT THE TRACKED SIGNALS DID NEXT — filled in from the bars already in
        // hand, once a day. See [SignalWatch.updateOutcomes].
        try { SignalWatch.updateOutcomes(ctx, base, complete) }
        catch (e: Exception) { Log.w(TAG, "[$base] signal outcome update failed", e) }

        // ── A YEAR OF SHAPE PER NAME ────────────────────────────────────────────
        // Built here because this pass already holds every symbol's daily bars, so
        // the series cost arithmetic and not one extra download. A score and a
        // slope are both single numbers, and neither can tell a long climb that
        // paused from a year-long slide that bounced once — which is the first
        // thing a person wants to know before buying by hand.
        try {
            val spark = HashMap<String, com.signalscope.app.data.ManualSparkStore.Series>()
            for ((sym, bars) in complete) {
                val win = if (bars.size > com.signalscope.app.data.ManualSparkStore.LOOKBACK_DAYS)
                    bars.takeLast(com.signalscope.app.data.ManualSparkStore.LOOKBACK_DAYS) else bars
                if (win.size < 30) continue
                val closes = DoubleArray(win.size) { win[it].close }
                val e12 = ema(closes, 12); val e26 = ema(closes, 26)
                val macdLine = DoubleArray(closes.size) { e12[it] - e26[it] }
                // THE SHORT CURVE COMES OFF THE FULL HISTORY, not off the window
                // above. Restarting the moving averages at the window's first bar
                // leaves the first month of the line as warm-up rather than
                // signal, and the short curve is drawn from the recent end where
                // that would not show — but the values would still be wrong by a
                // little, and this is the curve a person actually reads.
                val full = DoubleArray(bars.size) { bars[it].close }
                val f12 = ema(full, 12); val f26 = ema(full, 26)
                val fullMacd = DoubleArray(full.size) { f12[it] - f26[it] }
                val recent = fullMacd.takeLast(com.signalscope.app.data.ManualSparkStore.MACD_RECENT)
                    .map { Math.round(it * 10000.0) / 10000.0 }
                // WHERE THIS STOCK IS HEADING, on the same bars and for the same
                // reason as everything else in this loop: they are already here.
                //
                // Off the FULL history rather than the one-year window, exactly as
                // the MACD curve above is — a fifty-day average restarted at the
                // window's first bar spends its first fifty bars warming up, and
                // an average that is still warming up is not a trend line.
                val proj = try { Indicators.projectWaveRange(full.toList(), 20) }
                           catch (e: Exception) { null }
                spark[sym.uppercase()] = com.signalscope.app.data.ManualSparkStore.Series(
                    projFloor = proj?.projectedFloor ?: 0.0,
                    projCeiling = proj?.projectedCeiling ?: 0.0,
                    projMid = proj?.projectedMidpoint ?: 0.0,
                    projDays = proj?.daysAhead ?: 0,
                    px = com.signalscope.app.data.ManualSparkStore.downsample(closes, decimals = 2),
                    macd = com.signalscope.app.data.ManualSparkStore.downsample(macdLine, decimals = 4),
                    macdRecent = recent,
                    phase = macdPhaseOf(fullMacd),
                    days = win.size,
                    t0 = win.first().date.toEpochDay() * 86400L,
                    t1 = win.last().date.toEpochDay() * 86400L)
            }
            com.signalscope.app.data.ManualSparkStore.save(ctx, base,
                com.signalscope.app.data.ManualSparkStore.State(day, spark))
            Log.i(TAG, "[$base] sparklines built for ${spark.size} names")

            // ── THE SQUEEZE, over the WHOLE universe ────────────────────────
            // Not the candidate list, deliberately. The ranking sorts for stocks
            // already moving; a squeeze finds the ones that have stopped. Scoring
            // only the top of the list would be hunting quiet names in a list
            // ordered by loudness — and the compressions worth having are exactly
            // the ones nobody is ranking yet.
            //
            // Free here: these are the same bars, in the same pass.
            val sq = HashMap<String, com.signalscope.app.data.SqueezeStore.Row>()
            var unscored = 0
            for ((sym, bars) in complete) {
                val v = com.signalscope.app.screener.SqueezeScanner.scan(bars)
                // The same study on a weekly clock, from the same bars regrouped.
                // A weekly coil is the setup and the daily is the timing, so both
                // are kept — and a name is worth recording if EITHER has something
                // to say, or a long weekly coil would be dropped for being quiet
                // on the day.
                val w = try {
                    com.signalscope.app.screener.SqueezeScanner.scan(
                        com.signalscope.app.screener.SqueezeScanner.toWeekly(bars))
                } catch (e: Exception) { com.signalscope.app.screener.SqueezeScanner.Verdict() }
                if (!v.ok && !w.ok) { unscored++; continue }
                val nothing = v.tier <= 0.0 && !v.fired && w.tier <= 0.0 && !w.fired
                if (nothing) continue
                sq[sym.uppercase()] = com.signalscope.app.data.SqueezeStore.Row(
                    tier = v.tier, days = v.days, momentum = v.momentum,
                    fired = v.fired, firedFrom = v.firedFrom,
                    wTier = w.tier, wDays = w.days, wMomentum = w.momentum,
                    wFired = w.fired, wFiredFrom = w.firedFrom)
            }
            com.signalscope.app.data.SqueezeStore.save(ctx, base,
                com.signalscope.app.data.SqueezeStore.State(day, sq, unscored))
            // TIER <= x, not >=. A narrower channel is the harder test, so the
            // smaller number is the stronger squeeze — this line said the
            // opposite and reported the loosest names as the tightest, which is
            // how the inversion in the scanner stayed hidden for a build.
            val high = sq.values.count { it.tier > 0.0 && it.tier <= 1.0 }
            val mid = sq.values.count { it.tier > 0.0 && it.tier <= 1.5 }
            val fired = sq.values.count { it.fired }
            val wk = sq.values.count { it.wTier > 0.0 }
            val wkHigh = sq.values.count { it.wTier > 0.0 && it.wTier <= 1.0 }
            Log.i(TAG, "[$base] squeeze: ${sq.size} coiled — $high high (1.0x), " +
                "$mid mid or better (1.5x), $fired fired today, $unscored unscored | " +
                "weekly: $wk coiled, $wkHigh high, ${sq.values.count { it.wFired }} fired")
        } catch (e: Exception) {
            // Never fatal: the ranking is the job, the pictures are a courtesy.
            Log.w(TAG, "[$base] sparkline build failed", e)
        } finally {
            // MARKED WHETHER IT WORKED OR NOT — in the finally, deliberately. This
            // is the guard that stops the screen re-running, and tying it to
            // success would turn one failed write into a permanent re-screen every
            // tick until the day rolls over.
            ManualWatchStore.mutate(ctx, base) {
                it.copy(lastSparkDay = day, lastDailyBuild = stamp)
            }
        }
        Log.i(TAG, "[$base] watcher screen: ${rows.size} ranked, ${rows.count { it.slope > 0.0 }} passing")
        return true
    }

    /**
     * Every algo's candidates in a space, merged.
     *
     * A name two gates both nominate is ONE candidate, kept at its best rank —
     * the watcher would otherwise evaluate and alert on it twice for what is, to
     * the user, a single opportunity.
     */
    /**
     * Every name ANY algo in this space is already exposed to.
     *
     * [candidatesFor] can only exclude the seats of the gate it is reading, so on
     * its own it lets a name seated in algo A reach the user as a candidate
     * nominated by algo B — an invitation to buy by hand something the account
     * already holds. Doubling exposure to one company is the opposite of what a
     * seat-limited strategy is for, and the user would have no way to see it from
     * the candidate row.
     *
     * Three sources, because a name can be exposed without occupying a seat:
     *   • gate seats           — reserved, will be bought when its entry fires
     *   • open paper trades    — the engine considers itself in the position
     *   • open/pending T212 rows — real shares, or an order on its way to being some
     */
    /** Public view of [heldAnywhere], so the panel can mark WHY a ranked name is
     *  not a candidate instead of silently omitting it. */
    fun exposedSymbols(ctx: Context, space: String): Set<String> =
        heldAnywhere(ctx, Spaces.baseOf(space.lowercase()))

    /**
     * HOW MANY SHARES OF EACH NAME THIS APP IS ACTUALLY MANAGING.
     *
     * [exposedSymbols] answers "does anything hold this name", which is the right
     * question for offering a fresh entry and the WRONG one for coverage. Own €200
     * of a stock, let the watcher buy €50 of it, and the name is "held" — so the
     * portfolio manager stood aside from the whole position while the watcher
     * looked after a quarter of it. An exit then sold the quarter and left the
     * rest with no rules on it at all, which is the exact hole this closes.
     *
     * Counted from the BROKER-MIRROR rows, because there is exactly one of those
     * per managed position — the watcher's own buys and every algo's alike. The
     * watch entries mirror the same positions, so adding both would double-count;
     * they are consulted only for a name the mirror does not carry.
     *
     * A number that is too LOW here costs a duplicate prompt. Too HIGH costs
     * silence on real unmanaged shares. So where the two disagree, take the
     * larger — prompting twice is a nuisance, missing a position is a loss.
     */
    /**
     * WHICH ALGO IS ALREADY LOOKING AFTER EACH NAME, by namespace.
     *
     * The quantity walk below already knows this and threw it away, which left the
     * Watchlist panel unable to distinguish "nothing is managing this" from "an
     * algo is". Those look identical from the broker's side — both are simply
     * shares the watchlist book did not buy — and the panel said "not yet judged"
     * for both. For a position an armed algo is actively holding and will sell on
     * its own terms, that is not merely vague: it reads as unprotected, which is
     * the one conclusion that could make someone sell it twice.
     */
    fun managedOwnersFor(ctx: Context, space: String): Map<String, List<String>> {
        val base = Spaces.baseOf(space.lowercase())
        val out = HashMap<String, MutableSet<String>>()
        try {
            val rows: List<Pair<String, String>> =
                if (base == "in")
                    com.signalscope.app.data.ZerodhaLiveStore.load(ctx).trades
                        .filter { Spaces.baseOf(it.ns) == base && it.isLive }
                        .map { it.symbol.uppercase() to it.ns }
                else
                    com.signalscope.app.data.T212LiveStore.load(ctx).trades
                        .filter { Spaces.baseOf(it.ns) == base &&
                            (it.status == "OPEN" || it.status == "PENDING" || it.status == "EXITING") }
                        .map { it.symbol.uppercase() to it.ns }
            for ((sym, ns) in rows) out.getOrPut(sym) { LinkedHashSet() }.add(ns)
        } catch (e: Exception) { Log.w(TAG, "[$base] managing algos unreadable", e) }
        try {
            ManualWatchStore.load(ctx, base).entries
                .filter { it.status == "OPEN" && it.filledQty > 0.0 }
                .forEach { out.getOrPut(it.symbol.uppercase()) { LinkedHashSet() }
                    .add(ManualWatchStore.nsFor(base)) }
        } catch (e: Exception) { Log.w(TAG, "[$base] book owners unreadable", e) }
        return out.mapValues { it.value.toList() }
    }

    fun managedQtyFor(ctx: Context, space: String): Map<String, Double> {
        val base = Spaces.baseOf(space.lowercase())
        val mirror = HashMap<String, Double>()
        try {
            if (base == "in") {
                com.signalscope.app.data.ZerodhaLiveStore.load(ctx).trades
                    .filter { Spaces.baseOf(it.ns) == base &&
                        (it.status == "OPEN" || it.status == "PENDING" || it.status == "EXITING") }
                    .forEach { r ->
                        val k = r.symbol.uppercase()
                        mirror[k] = (mirror[k] ?: 0.0) + r.qty.toDouble()
                    }
            } else {
                com.signalscope.app.data.T212LiveStore.load(ctx).trades
                    .filter { Spaces.baseOf(it.ns) == base &&
                        (it.status == "OPEN" || it.status == "PENDING" || it.status == "EXITING") }
                    .forEach { r ->
                        val k = r.symbol.uppercase()
                        mirror[k] = (mirror[k] ?: 0.0) + r.qty
                    }
            }
        } catch (e: Exception) { Log.w(TAG, "[$base] mirror quantities unreadable", e) }

        val book = HashMap<String, Double>()
        try {
            ManualWatchStore.load(ctx, base).entries
                .filter { it.status == "OPEN" && it.filledQty > 0.0 }
                .forEach { e ->
                    val k = e.symbol.uppercase()
                    book[k] = (book[k] ?: 0.0) + e.filledQty
                }
        } catch (e: Exception) { Log.w(TAG, "[$base] watch-book quantities unreadable", e) }

        val out = HashMap<String, Double>()
        for (k in mirror.keys + book.keys)
            out[k] = maxOf(mirror[k] ?: 0.0, book[k] ?: 0.0)
        return out
    }

    private fun heldAnywhere(ctx: Context, base: String): Set<String> {
        val out = HashSet<String>()

        // ── REAL exposure only ────────────────────────────────────────────────
        // PAPER POSITIONS ARE DELIBERATELY NOT EXCLUDED. A paper seat costs
        // nothing and earns nothing; blocking a manual entry because a simulation
        // is "holding" the name forfeits a real profit to protect an imaginary
        // position. The thing worth preventing is owning the same company twice
        // with real money — and paper cannot do that.
        //
        // An ARMED algo is the exception, and the reason this is not simply "skip
        // all algos": an algo whose live switch is on either holds real shares or
        // is one open gate away from buying them, so its seats and positions are
        // real exposure in waiting. Paper algos are skipped; armed ones are not.
        val armedNamespaces = ArrayList<String>()
        try {
            BucketStore.list(ctx, base).filter { it.live }.forEach { armedNamespaces.add(it.ns) }
        } catch (e: Exception) { Log.w(TAG, "bucket list failed for $base", e) }
        for (ns in armedNamespaces.distinct()) {
            try {
                MacdGateStore.load(ctx, ns).slots.forEach { sl ->
                    sl.symbol?.let { out.add(it.uppercase()) }
                }
            } catch (_: Exception) {}
            try {
                StrategyLabStore.load(ctx, ns).trades
                    .filter { it.status == "OPEN" }
                    .forEach { out.add(it.symbol.uppercase()) }
            } catch (_: Exception) {}
        }

        // ── THE WATCHER'S OWN LEDGER IS NOT READ HERE, AND MUST NOT BE ────────
        //
        // It used to be, on the stated grounds that "these are never paper — every
        // one of them was bought with a real order through the Buy button". That
        // is simply not true, and the numbers are not close: this book's namespace
        // enrols every CANDIDATE it watches and replays the strategy over all of
        // them, so on this account it held 15 open PAPER positions against 2 real
        // holdings.
        //
        // The cost was silent and specific. Exposure is what [candidates] filters
        // by, so any name the scan happened to be paper-holding could never appear
        // on the list — INCLUDING ONE THE USER HAD JUST PINNED BY HAND. Pinning
        // S92 and JEN stored correctly, the chip and the counter both said so, and
        // neither ever showed up, because the scan was "holding" them.
        //
        // Every REAL position in this book is already covered by the entries below:
        // a hand buy, an adopted holding and an in-flight ladder all write an entry
        // there. So nothing is lost by not looking at the replay — which was the
        // one source here that could not tell a simulation from a share.
        //
        // In-flight manual buys included: the ladder can run for minutes, and
        // during that window there is no engine position yet but real orders are
        // working.
        try {
            ManualWatchStore.load(ctx, base).entries
                .filter { it.status == "PENDING" || it.status == "OPEN" }
                .forEach { out.add(it.symbol.uppercase()) }
        } catch (_: Exception) {}

        // Real broker rows are keyed by namespace, so filter to this space's own.
        try {
            com.signalscope.app.data.T212LiveStore.load(ctx).trades
                .filter {
                    Spaces.baseOf(it.ns) == base &&
                        (it.status == "OPEN" || it.status == "PENDING" || it.status == "EXITING")
                }
                .forEach { out.add(it.symbol.uppercase()) }
        } catch (_: Exception) {}

        // AND ZERODHA'S, which were never read at all. Only Trading 212's live
        // rows were consulted, so a real Indian position held by an armed algo did
        // not count as exposure and the watcher would happily offer the same
        // company again — the exact double-exposure this walk exists to prevent,
        // in the one market where the app cannot see a fractional share to net it
        // out afterwards.
        try {
            com.signalscope.app.data.ZerodhaLiveStore.load(ctx).trades
                .filter {
                    Spaces.baseOf(it.ns) == base &&
                        (it.status == "OPEN" || it.status == "PENDING" || it.status == "EXITING")
                }
                .forEach { out.add(it.symbol.uppercase()) }
        } catch (_: Exception) {}
        return out
    }

    /**
     * The top [limit] names from the watcher's OWN ranking that pass its slope gate
     * and that NOTHING in this space is already exposed to.
     *
     * Read-only over stored state — no fetch, no recompute — so the Watchlist tab
     * and the watch tick can both call it freely and always agree.
     *
     * The exposure filter is applied HERE rather than baked into the stored ranking
     * because exposure changes through the day as algos take seats and fill orders,
     * while the ranking is a once-a-day fact. Filtering at read time means the list
     * is correct the moment an algo buys something, without re-screening.
     *
     * @param exposedIn already-computed exposure set, when the caller has one.
     *   [heldAnywhere] walks every namespace's stores, so a caller that also needs
     *   the set (the panel marks held names in the full ranking) would otherwise
     *   pay for that whole walk twice on a call that runs on every repaint.
     * @param stateIn already-loaded state, for the same reason — the ranking file
     *   carries ~105 rows and re-parsing it per call is not free.
     */
    fun candidates(
        ctx: Context, space: String, limit: Int = 0,
        exposedIn: Set<String>? = null,
        stateIn: ManualWatchStore.State? = null
    ): List<Candidate> {
        @Suppress("NAME_SHADOWING") val limit = if (limit > 0) limit else depthFor(ctx)
        val base = Spaces.baseOf(space.lowercase())
        val st = stateIn ?: ManualWatchStore.load(ctx, base)
        if (st.ranking.isEmpty()) return emptyList()
        val exposed = exposedIn ?: heldAnywhere(ctx, base)
        val out = ArrayList<Candidate>()
        val seen = HashSet<String>()

        // ── PINNED FIRST, and outside the depth cut ─────────────────────────
        //
        // Deliberately exempt from the slope gate. That gate keeps momentum junk
        // off the list, but a hand-added name is usually here BECAUSE it has gone
        // quiet — a coiled stock has a flat or falling slope almost by definition,
        // so applying the gate would throw out precisely what was pinned.
        //
        // Not exempt from exposure: the list is names you could still buy, and
        // something already held is not one of them.
        //
        // Additive rather than competing for the depth budget, so pinning
        // something never silently evicts a ranked name. The cost is a download
        // per pin per pass, which is why the number of pins is capped.
        val byName = st.ranking.associateBy { it.symbol.uppercase() }
        for (p in st.pinned) {
            val k = p.uppercase()
            if (k in exposed || !seen.add(k)) continue
            val r = byName[k]
            out.add(Candidate(r?.symbol ?: k, r?.rank ?: 0, r?.score ?: 0.0,
                r?.slope ?: 0.0, ManualWatchStore.nsFor(base)))
        }

        // The depth setting governs the RANKED names only, counted on its own.
        var ranked = 0
        for (r in st.ranking) {
            if (ranked >= limit) break
            if (r.slope <= 0.0) continue                       // the daily MACD gate
            val k = r.symbol.uppercase()
            if (k in exposed || !seen.add(k)) continue
            out.add(Candidate(r.symbol, r.rank, r.score, r.slope, ManualWatchStore.nsFor(base)))
            ranked++
        }
        return out
    }

    // ── the watch tick ─────────────────────────────────────────────────────
    private fun today(): String = LocalDate.now(ZoneOffset.UTC).toString()

    /** Is the watcher usable for this space — i.e. has a config been attached? */
    fun isConfigured(ctx: Context, space: String): Boolean =
        StrategyLabStore.load(ctx, ManualWatchStore.nsFor(space)).configJson != null

    data class Outcome(val checked: Int, val alerted: List<String>, val note: String)

    /**
     * Evaluate the space's candidates and notify on any that just opened a
     * position under the watcher's config. READ-ONLY: no ledger, no watermark, no
     * baseline, no order.
     *
     * Skipped without a config on purpose rather than falling back to the built-in
     * preset. The preset is a 60min/30min default that nobody chose; alerting from
     * it would attribute signals to a strategy the user never picked, and they
     * would have no way to tell from the notification.
     */
    fun runOnce(ctx: Context, space: String, limit: Int = 0): Outcome {
        @Suppress("NAME_SHADOWING") val limit = if (limit > 0) limit else depthFor(ctx)
        val base = Spaces.baseOf(space.lowercase())
        val ns = ManualWatchStore.nsFor(base)
        // Settle any fill price still outstanding BEFORE anything else, and do it
        // even when no config is attached: a position bought under a config that
        // was later swapped still deserves its real cost recorded.
        // REPAIR FIRST: a manual position with no live-order row is invisible to
        // BOTH stops, so nothing can ever sell it.
        try { backfillLiveRows(ctx, base) } catch (e: Exception) { Log.w(TAG, "[$base] live-row backfill failed", e) }
        // ONE OWNER PER SHARE, re-established every pass. Must run BEFORE anything
        // that reads a quantity or decides an exit: a book still claiming shares an
        // algo owns would size its sell off a number the account cannot honour.
        try { separateFromAlgos(ctx, base) }
        catch (e: Exception) { Log.w(TAG, "[$base] algo/book separation failed", e) }
        try { reconcileFills(ctx, base) } catch (e: Exception) { Log.w(TAG, "[$base] fill reconcile failed", e) }
        // AFTER the fill reconcile, so a row is only frozen once its entry price has
        // settled — and BEFORE the config check below, because a position bought
        // under a config that was later detached still finished, and a book that
        // cannot admit that keeps warning about it and keeps its name blocked.
        try { closeFinishedEntries(ctx, base) } catch (e: Exception) { Log.w(TAG, "[$base] close sweep failed", e) }
        // AND THE TRUTH FROM THE BROKER. The sweep above can only retire a trade the
        // engine opened; this one retires anything the account no longer holds,
        // which is the only way a forced buy — or one you sold by hand — ever gets
        // off the books. Runs before the config check for the same reason as the
        // others: a position does not stop having been sold because a config was
        // detached.
        try { reconcileAgainstBroker(ctx, base) } catch (e: Exception) { Log.w(TAG, "[$base] broker reconcile failed", e) }
        // Buy triggers that have since fired. Before the reconcile below would
        // ever see them, and before the config check, because a trigger that
        // filled is a real position whether or not a strategy is attached.
        try { restoreLostTriggers(ctx, base) } catch (e: Exception) { Log.w(TAG, "[$base] trigger restore failed", e) }
        try { settleWaitingBuys(ctx, base) } catch (e: Exception) { Log.w(TAG, "[$base] buy settle failed", e) }
        // YOUR OWN FLOOR AND CEILING DO NOT DEPEND ON A STRATEGY. They sit ABOVE
        // the config check on purpose: both are rules the user set by hand, and
        // neither has anything to do with which JSON happens to be attached. Below
        // the check, detaching a config for a week silently froze every high-water
        // mark — the opposite of what its own comment promised.
        try { runManualStopsAndTargets(ctx, base) }
        catch (e: Exception) { Log.w(TAG, "[$base] hand-set exit sweep failed", e) }
        if (!isConfigured(ctx, base)) return Outcome(0, emptyList(), "no watcher config attached")
        // Positions the replay never opened — forced buys, and anything whose
        // hand-over did not take. Needs the config, so it sits below the check.
        try { manageAdoptedPositions(ctx, base) } catch (e: Exception) { Log.w(TAG, "[$base] adopted exit sweep failed", e) }

        // The watcher's own daily screen — at most once per UTC day, and free on
        // every pass after the first because the bars are already cached.
        try { evaluateDaily(ctx, base) }
        catch (e: Exception) { Log.w(TAG, "[$base] daily screen failed", e) }

        val cands = candidates(ctx, base, limit)
        if (cands.isEmpty()) return Outcome(0, emptyList(), "no gate candidates today")

        // ── PRICE THE GERMAN LIST OFF THE LIVE VENUE ─────────────────────────
        //
        // Candidates are not open positions, so nothing else prices them: their
        // number on the panel comes from whatever the quote stream last pushed.
        // For Germany that stream is a quarter of an hour behind, which is the
        // whole reason the panel and the broker's own screen disagreed.
        //
        // GERMANY ONLY, and not for tidiness. The other two markets already
        // stream real trades — America through Finnhub, India through Zerodha —
        // so there is nothing here for them to gain, and a quote per candidate
        // per pass is a real cost to spend on a number that is already right.
        if (base == "de") {
            try {
                MarkToMarket.ensureFresh(ctx, ns, cands.map { it.symbol },
                    MarkToMarket.DEFAULT_MAX_AGE_MS)
            } catch (e: Exception) { Log.w(TAG, "[$base] candidate pricing failed", e) }
        }

        val day = today()
        val exchange = Spaces.exchangeOf(base)
        val alerted = ArrayList<String>()
        var checked = 0
        // The download allowance for the signal tracker this pass — see [SignalWatch].
        val sigBudget = SignalWatch.newBudget()

        for (c in cands) {
            val s = ManualWatchStore.load(ctx, base)
            // Already alerted today, or already being bought / already held by the
            // manual book — three separate reasons not to interrupt the user again.
            if (ManualWatchStore.alreadyAlerted(s, c.symbol, day)) continue
            if (ManualWatchStore.liveEntry(s, c.symbol) != null) continue

            // THE WATCHER'S OWN FRESH-START BOUNDARY. Zero on a name's first check —
            // there is nothing to start from yet — and stamped just below at the bar
            // that check evaluated. From then on the replay arrives FLAT at the
            // moment this name joined the list, instead of inheriting whatever
            // position months of history left it holding. The algos have worked this
            // way since the seat fix; this is the same rule for the same reason.
            val since = ManualWatchStore.load(ctx, base).watchingSince[c.symbol.uppercase()] ?: 0L
            val fresh = try {
                StrategyLabRunner.freshEntrySignal(ctx, ns, c.symbol, exchange, since)
            } catch (e: Exception) {
                Log.w(TAG, "[$base] ${c.symbol} watch check failed", e); null
            }
            // Stamp it the first time, one tick behind the evaluated bar so THAT bar
            // is itself tradeable — the same convention a seat's bootstrap uses, and
            // for the same reason: a boundary on the bar would make the first entry
            // permanently ineligible.
            if (since <= 0L && StrategyLabRunner.lastRunBarTs > 0L) {
                val anchor = StrategyLabRunner.lastRunBarTs - 1L
                ManualWatchStore.mutate(ctx, base) {
                    it.copy(watchingSince = it.watchingSince + (c.symbol.uppercase() to anchor))
                }
            }
            // Record what the replay is doing with this name, whether or not it
            // alerted. A candidate the strategy is ALREADY long on never alerts, and
            // without this the list cannot say why — it looks identical to one
            // nothing has happened to. Free: the check above already ran the engine.
            // MATCH THE SYMBOL BEFORE BELIEVING EITHER OF THESE.
            //
            // They are object-level state set as a side effect of the check above,
            // and this loop is NOT the only caller: a Buy tap runs the same check
            // on its own thread, and so does the entry-retry sweep. A tap landing
            // between the call and this read would stamp another company's entry
            // price, percentage and green dot onto this row.
            //
            // No money rides on it — it is display only — but it is the display
            // used to decide real purchases, which is not a good place for another
            // company's numbers. The symbol test costs nothing and closes it.
            val k0 = c.symbol.uppercase()
            val ps = StrategyLabRunner.lastPositionState?.takeIf { it.symbol.equals(k0, true) }
            // The SAME check run without the fresh-start boundary: would this
            // strategy be riding this name today, had it always been watching it?
            // A different question from the one above and often a different answer,
            // so it is stored separately rather than folded in.
            val hs = StrategyLabRunner.lastHistoricalState?.takeIf { it.symbol.equals(k0, true) }
            // Where this name has been turning, off the very bars that check just
            // read. Same side-channel discipline as the two above, and the same
            // symbol test — these are numbers an order gets sized against.
            val lv = StrategyLabRunner.lastLevels?.takeIf { it.symbol.equals(k0, true) }
            ManualWatchStore.mutate(ctx, base) { s ->
                val k = c.symbol.uppercase()
                // KEPT WHEN A PASS CANNOT PRODUCE NEW ONES. A failed fetch or a
                // young listing is not evidence that the old levels moved — and
                // the panel stamps them with their own age, so a stale pair says
                // so for itself rather than vanishing and reading as "this stock
                // has no levels".
                val withLevels = if (lv != null) s.levels + (k to lv) else s.levels
                val withHist = if (hs != null && hs.holding)
                    s.historical + (k to ManualWatchStore.Holding(
                        since = hs.entryTs, entryPrice = hs.entryPrice,
                        pctReturn = hs.pctReturn, checkedAt = System.currentTimeMillis(),
                        fresh = hs.fresh))
                else s.historical - k
                if (ps != null && ps.holding)
                    s.copy(historical = withHist, levels = withLevels,
                        holdings = s.holdings + (k to ManualWatchStore.Holding(
                            since = ps.entryTs, entryPrice = ps.entryPrice,
                            pctReturn = ps.pctReturn, checkedAt = System.currentTimeMillis(),
                            fresh = ps.fresh)))
                else s.copy(historical = withHist, levels = withLevels,
                    holdings = s.holdings - k)
            }
            checked++

            // ── A PRICE FEED THAT HAS STOPPED ────────────────────────────────
            //
            // Everything below reads this name's price, and the check above has
            // just fetched its chart — whose reply also says whether the feed is
            // still moving (see FeedHealth). A frozen chart still produces
            // "signals": the watchlist recorded Pfisterer's entry off bars that had
            // ended a week before. So: say so, once a day, and leave the name alone
            // — no floor alert, no entry alert, no buy — until it trades again.
            val feedSym = YahooFinanceClient.toYahooSymbol(k0, exchange)
            val feedStoppedAt = com.signalscope.app.network.FeedHealth.stoppedSince(feedSym)
            if (feedStoppedAt > 0L) {
                try { LabNotifier.feedStopped(ctx, base, k0, feedSym, feedStoppedAt, held = false) }
                catch (ex: Exception) { Log.w(TAG, "[$base] $k0 feed alarm failed", ex) }
                Log.w(TAG, "[$base] $k0 left alone — its price feed has stopped")
                continue
            }

            // ── NEAR ITS FLOOR ──────────────────────────────────────────────
            //
            // A PRICE OBSERVATION, NOT A SIGNAL, and the difference is the whole
            // reason it is a separate alert with separate wording and its own
            // dedup. The entry alert below says the STRATEGY would buy. This one
            // says only that a name already judged interesting — ranked, trending
            // or pinned by hand — is trading at the bottom of its own recent
            // range. Nothing fired and nothing was bought.
            //
            // JUDGED ON THE LAST COMPLETED BAR, like every other rule here, and
            // on the levels that pass just computed. Costs nothing: both are
            // already in hand from the check above.
            // What the floor check below works out, handed to the signal tracker
            // after it rather than worked out a second time.
            var sigPx = 0.0; var sigAtFloor = false; var sigFresh = false
            if (lv != null && lv.swingHigh > lv.swingLow) {
                // A MARKET-SHUT GUARD. A completed bar exists all night, so without
                // this every name sitting near its low would be announced at three
                // in the morning about a session that ended hours ago — and by the
                // open the price has had all night to be somewhere else.
                //
                // It used to time the newest two-hour bar from its START, which
                // passed only in a short window after each bar closed — a market
                // open all afternoon read as shut most of the time. Now: is the
                // exchange in session by its clock, AND is the newest bar we hold
                // from today (which catches a holiday). See [MarketHours].
                val marketLive = SignalWatch.liveDataFresh(base, k0)
                val live = try { MarkToMarket.freshPrice(k0) } catch (ex: Exception) { 0.0 }
                val px = if (live > 0.0) live else lv.price
                val pos = IntradayLevels.positionInRange(px, lv.swingLow, lv.swingHigh)
                sigPx = px
                sigAtFloor = IntradayLevels.inSupportZone(pos)
                sigFresh = marketLive
                // THE 3-DAY, WEEK AND MONTH LOWS, each by the same rule. One alert per
                // pass names the deepest one it is sitting at; each horizon alerts
                // at most once a day, so reaching the month's low later in a day that
                // already flagged the 3-day one still gets through.
                val st2 = ManualWatchStore.load(ctx, base)
                val hits = if (marketLive && px > 0.0) IntradayLevels.floorsHit(lv, px) else emptyList()
                val news = hits.filter { !ManualWatchStore.floorAlerted(st2, c.symbol, it.scale, day) }
                if (news.isNotEmpty()) {
                    val cur = when (base) { "de" -> "\u20ac"; "in" -> "\u20b9"; else -> "$" }
                    try {
                        LabNotifier.nearLows(ctx, base, c.symbol, c.rank, px, hits, cur)
                        ManualWatchStore.markFloorsAlerted(ctx, base, c.symbol, hits.map { it.scale }, day)
                        Log.i(TAG, "[$base] ${c.symbol} near its ${hits.last().label} low: " +
                            "${"%.2f".format(px)} vs ${"%.2f".format(hits.last().low)}" +
                            " (${hits.joinToString(",") { it.scale }}, new ${news.joinToString(",") { it.scale }})")
                    } catch (ex: Exception) {
                        Log.w(TAG, "[$base] ${c.symbol} floor alert failed", ex)
                    }
                }
            }

            // ── TURNING UP AT A FLOOR, and the "all five green" comparison ──────
            // After the floor check, on the same prices and bars. Never allowed to
            // break the pass: a failure leaves this one stock unjudged this time.
            try {
                SignalWatch.check(ctx, base, c.symbol, c.rank, sigPx, sigAtFloor, sigFresh, day, sigBudget)
            } catch (e: Exception) { Log.w(TAG, "[$base] ${c.symbol} signal check failed", e) }

            if (fresh != null) {
                // ONLY WHILE THE MARKET IS ACTUALLY TRADING.
                //
                // The signal is judged on completed candles, and the newest completed
                // candle exists all night — so without this the watcher happily woke
                // the user at 02:00 about a German entry it had found on a candle that
                // closed before the exchange shut. Nothing can be done with that: the
                // ladder cannot fill into a closed market (it says so, in the fills
                // log), and by the opening bell the price has had a whole night to
                // move somewhere else.
                //
                // Judged by the AGE OF THE NEWEST BAR rather than a clock or a
                // calendar. A trading venue that is open produces bars; one that is
                // shut does not. That needs no timezone table, no holiday list, and
                // no extra request — the age is already in hand from the check that
                // just ran, and it is right on half-days and unscheduled closures
                // where a hardcoded session window would be wrong.
                val barAgeMin = (System.currentTimeMillis() / 1000 - fresh.lastBarTs) / 60
                // Plus the feed's own delay: German bars are judged fifteen minutes
                // after they close (see [FeedDelay]), and without adding it back the
                // margin for a late or skipped pass shrank from half an hour to a
                // quarter — one missed pass and a real entry signal went unsent.
                val staleLimit = maxOf(90L, (fresh.exitTfMin.toLong()) + 30L) + FeedDelay.secFor(base) / 60L
                // AN OLD BAR IS FINE ONCE THE MARKET IS TRADING AGAIN. A signal on a
                // session's last bar was held while the market was shut — rightly —
                // and then held on through the next morning too, because its bar was
                // still hours old; once that day's first bar finished it was no longer
                // the newest signal and was never sent at all (COIN, DXCM and PYPL on
                // 16 Oct). The first pass with the market trading again sends it.
                val tradingNow = MarketHours.isRegularSession(base, System.currentTimeMillis()) &&
                    SignalWatch.tradedToday(base, k0)
                if (barAgeMin > staleLimit && !tradingNow) {
                    // NOT marked as alerted, deliberately: the signal is real and may
                    // still stand when trading resumes. Suppressing it now and letting
                    // the first pass of the session raise it is the whole point —
                    // silently consuming the day's one alert here would mean the user
                    // never hears about it at the moment it could be acted on.
                    Log.i(TAG, "[$base] ${c.symbol} entry signal held — market shut " +
                        "(newest bar ${barAgeMin}m old, limit ${staleLimit}m)")
                } else {
                    LabNotifier.manualEntrySignal(ctx, base, c.symbol, c.rank, fresh.entryPrice, fresh.entryReason)
                    ManualWatchStore.markAlerted(ctx, base, c.symbol, day)
                    alerted.add(c.symbol)
                    Log.i(TAG, "[$base] ENTRY SIGNAL ${c.symbol} #${c.rank} @ ${fresh.entryPrice}")
                }
            }
            // Same courtesy pacing the runner uses between symbols.
            try { Thread.sleep(200) } catch (_: InterruptedException) {}
        }

        // Forget the boundary and the holding for anything that has left the list.
        // A name that drops out and later returns is a NEW watch, and reviving a
        // boundary stamped weeks ago would hand it back the very history the
        // fresh start exists to discard.
        val live = cands.map { it.symbol.uppercase() }.toSet()
        ManualWatchStore.mutate(ctx, base) {
            // LEVELS ARE PRUNED AGAINST A WIDER SET than the boundary and the
            // holding map, because they are the one thing here that matters for
            // stock you ALREADY OWN. A bought name leaves the candidate list by
            // definition — that is what the exposure filter is for — so pruning
            // these to the candidates alone would delete them from precisely the
            // rows that use them to judge a stop.
            val keep = live + it.entries
                .filter { e -> e.status == "OPEN" || e.status == "PENDING" }
                .map { e -> e.symbol.uppercase() }
            it.copy(lastRunAt = System.currentTimeMillis(),
                lastRunNote = "checked $checked, alerted ${alerted.size}",
                watchingSince = it.watchingSince.filterKeys { k -> k in live },
                holdings = it.holdings.filterKeys { k -> k in live },
                levels = it.levels.filterKeys { k -> k in keep })
        }
        return Outcome(checked, alerted, "checked $checked of ${cands.size}")
    }

    // ── buy + hand the position to the engine ──────────────────────────────
    data class BuyResult(
        val ok: Boolean,
        val symbol: String,
        val filledQty: Double = 0.0,
        val filledPrice: Double = 0.0,
        val signalPrice: Double = 0.0,
        val driftPct: Double = 0.0,
        val error: String = "",
        val note: String = "",
        /**
         * TRUE = shares were actually bought (or an ordinary order is filling
         * right now). FALSE = a trigger is resting and nothing has been bought.
         *
         * Carried all the way out to the screen deliberately. "Order placed" over
         * a resting trigger is the single most misleading thing this app can say:
         * it reads as done, and the user stops watching a thing that may never
         * happen.
         */
        val immediate: Boolean = false
    )

    /**
     * Buy [cash] worth of [symbol] with the patient ladder, then hand the position
     * to the engine so its exit is managed by the watcher config.
     *
     * THE HAND-OVER, and why it is one line rather than a new mechanism:
     *
     * The engine never stores positions — it replays history and re-derives its
     * trades every pass. So it cannot be given a position. What it CAN do is
     * recognise one it would have taken itself, which is exactly what a candidate
     * whose entry rule just fired is. The only thing in the way is the flat-start
     * rule: a newly enrolled symbol gets its baseline stamped at today's bar and
     * every entry at or before it is discarded, so the signal we just acted on
     * would be thrown away and the engine would hold nothing.
     *
     * Back-dating the baseline to just BEFORE the signal bar is the whole fix. The
     * replay then adopts its own entry at that bar and runs the config's exits from
     * it. The baseline is only ever a filter on which trades get RECORDED — it is
     * not an input to the engine's maths — so moving it cannot change what the
     * engine computes, only which of its results are kept.
     *
     * The watermark is set to the same bar so the next pass is not treated as a
     * bootstrap (which would re-stamp a flat start and undo this).
     *
     * REAL MONEY: reachable only from an explicit user tap. There is deliberately
     * no automatic caller.
     */
    /**
     * @param force buy even with NO entry signal standing — your conviction rather
     *   than the strategy's. The position is still handed over, but understand what
     *   changes: the replay never chose this trade, so it arrives flat and has
     *   nothing to exit. Its exits are then run by [manageAdoptedPositions], which
     *   asks the SAME rules about your fill price and your entry time.
     *
     *   What you give up is the entry discipline, which is the half with measured
     *   evidence behind it. What you keep is every exit rule and the hard stop.
     */
    fun buyAndHandOver(
        ctx: Context, space: String, symbol: String, cash: Double,
        onProgress: ((Map<String, Any?>) -> Unit)? = null,
        force: Boolean = false
    ): BuyResult {
        val base = Spaces.baseOf(space.lowercase())
        val sym = symbol.trim().uppercase()
        val ns = ManualWatchStore.nsFor(base)
        if (sym.isBlank()) return BuyResult(false, sym, error = "No symbol given.")
        if (cash <= 0.0) return BuyResult(false, sym, error = "Enter an amount to invest.")
        if (!isConfigured(ctx, base))
            return BuyResult(false, sym, error = "Attach a watcher config first — without one nothing would manage the exit.")

        val config = ConfigManager(ctx)

        // ── INDIA GOES TO ZERODHA ──────────────────────────────────────────────
        // Different broker, different order model, and a protective trigger the
        // broker itself holds. Routed before the Trading 212 path rather than
        // bolted into it: the two share only the idea of "buy this", and merging
        // them would mean one function deciding which broker owns a position from
        // inside code that is mostly about ladders T212 alone has.
        if (Spaces.baseOf(base) == "in") return buyIndia(ctx, base, sym, cash, force)

        if (!config.hasT212Credentials)
            return BuyResult(false, sym, error = "Trading 212 login is missing (Settings).")

        // RE-CHECK THE SIGNAL AT THE MOMENT OF BUYING, not at the moment of
        // alerting. The user may have taken hours to decide, and the anchor the
        // exit measures from must be the bar that is live NOW — anchoring to a
        // stale alert would place the stop against a price the market has left.
        //
        // WITH THE SAME BOUNDARY THE ALERT USED. This is the whole point and it
        // was missing: the watch pass asks "is there an entry, counting only from
        // when we started watching this name", while this asked "is there an
        // entry, counting from the beginning of time". Those are different
        // questions and they routinely disagree — full history usually leaves the
        // replay already long from some trade months ago, whose entry bar is not
        // the newest one, so this check returned nothing and refused a buy the
        // app had just recommended seconds earlier.
        //
        // The damage was worse than an argument with itself: the only way through
        // was the override, so genuine signalled entries got recorded as forced
        // ones — conviction trades in the book that the strategy had actually
        // chosen.
        val since = ManualWatchStore.load(ctx, base).watchingSince[sym] ?: 0L
        val signalled = StrategyLabRunner.freshEntrySignal(ctx, ns, sym, Spaces.exchangeOf(base), since)
        // A FORCED BUY BORROWS THE SHAPE OF A SIGNAL, NOT ITS AUTHORITY.
        //
        // Everything downstream — the ladder anchor, the book row, the live-order
        // row, the hand-over — needs an entry bar and an entry price. A forced buy
        // has no signal to supply them, so the newest COMPLETED bar does instead.
        // Synthesising the same shape keeps one code path for both kinds of buy;
        // the [forced] flag on the stored row is what keeps them distinguishable
        // afterwards, which is the part that actually matters.
        val fresh = signalled ?: if (force) {
            val lb = StrategyLabRunner.latestClosedBar(ctx, ns, sym, Spaces.exchangeOf(base))
                ?: return BuyResult(false, sym, error = "No usable price data for $sym right now.")
            StrategyLabRunner.FreshEntry(sym, lb.ts, lb.close,
                "forced entry — no signal, bought on your call", lb.ts, lb.close, 0, 0)
        } else return BuyResult(false, sym,
            error = "No entry signal on the latest closed bar any more — the setup has passed. " +
                    "Open the chart to see where it fired and how far price has moved.")
        val forced = signalled == null

        val t212Space = if (base == "de") "DE" else "US"
        val client = Trading212Client(config)
        val ticker = client.resolveT212Ticker(sym, t212Space)
            ?: return BuyResult(false, sym, error = client.resolveFailureReason(sym, t212Space))
        val yahooSym = YahooFinanceClient.toYahooSymbol(sym, Spaces.exchangeOf(base))

        // Recorded BEFORE the ladder runs: a ladder can take minutes and the
        // process can die mid-way, and an order with no record of why it was
        // placed is worse than no order at all.
        // CLAIMED, NOT OVERWRITTEN. A second tap on the same signal used to write
        // a fresh PENDING row over the first — over a ladder still running, or over
        // a position already bought — and then run a second ladder. See
        // [ManualWatchStore.claim].
        ManualWatchStore.claim(ctx, base, com.signalscope.app.data.ManualWatchStore.Entry(
            symbol = sym, space = base, signalBarTs = fresh.entryTs, signalPrice = fresh.entryPrice,
            requestedCash = cash, t212Ticker = ticker, createdAt = System.currentTimeMillis(),
            status = "PENDING", note = fresh.entryReason, forced = forced))
            ?.let { return alreadyOnSignal(sym, it) }

        val r = try {
            PatientExecutor.buy(config, ticker, yahooSym, t212Space, cash, fresh.lastClose) { p ->
                onProgress?.invoke(p)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$base] $sym ladder threw", e)
            ManualWatchStore.upsert(ctx, base, ManualWatchStore.Entry(
                symbol = sym, space = base, signalBarTs = fresh.entryTs, signalPrice = fresh.entryPrice,
                requestedCash = cash, t212Ticker = ticker, createdAt = System.currentTimeMillis(),
                status = "FAILED", note = e.message ?: "ladder failed"))
            return BuyResult(false, sym, error = e.message ?: "order failed")
        }

        if (!r.ok || r.filled <= 0.0) {
            ManualWatchStore.upsert(ctx, base, ManualWatchStore.Entry(
                symbol = sym, space = base, signalBarTs = fresh.entryTs, signalPrice = fresh.entryPrice,
                requestedCash = cash, t212Ticker = ticker, createdAt = System.currentTimeMillis(),
                orderIds = r.orderIds, status = "FAILED",
                note = r.error ?: r.note.ifBlank { "nothing filled" }))
            return BuyResult(false, sym, error = r.error ?: r.note.ifBlank { "nothing filled" })
        }

        // THE LADDER DOES NOT REPORT A FILL PRICE. Its result carries the shares
        // filled and the ANCHOR it opened from — and the anchor is not what you
        // paid: the whole point of a cancel-replace chase is that it walks away
        // from its anchor. Recording the anchor as the fill would put a fabricated
        // number into the drift figure, which exists precisely to tell the user how
        // far their real cost sits from the price the exit rules measure from.
        //
        // So leave it at 0 and let [reconcileFills] fill it in from the broker's own
        // executed-order history, matched by order id. Until that lands the row
        // reads "fill pending" rather than a plausible lie.
        val entry = ManualWatchStore.Entry(
            symbol = sym, space = base, signalBarTs = fresh.entryTs, signalPrice = fresh.entryPrice,
            requestedCash = cash, filledQty = r.filled, filledPrice = 0.0,
            orderIds = r.orderIds, t212Ticker = ticker,
            createdAt = System.currentTimeMillis(), status = "OPEN", note = fresh.entryReason,
            forced = forced)
        ManualWatchStore.upsert(ctx, base, entry)
        // Best-effort immediate attempt; the tick retries for anything not yet
        // settled in T212's history.
        try { reconcileFills(ctx, base) } catch (e: Exception) { Log.w(TAG, "fill reconcile failed", e) }
        val fillPx = ManualWatchStore.load(ctx, base).entries
            .lastOrNull { it.symbol.equals(sym, true) && it.signalBarTs == fresh.entryTs }
            ?.filledPrice ?: 0.0
        val drift = if (fillPx > 0.0 && fresh.entryPrice > 0.0)
            (fillPx / fresh.entryPrice - 1.0) * 100.0 else 0.0

        // ── REGISTER THE REAL POSITION ──
        //
        // Without this row nothing can ever SELL what was just bought. Both exit
        // paths — the strategy's own and the fill-anchored stop — locate a position
        // through the live-order ledger, and a manual buy wrote only to its own
        // book. The shares existed, the engine could be taught to manage them, and
        // the sell had nowhere to look: a real holding with two exit rules watching
        // it and neither able to act.
        //
        // Keyed on the SIGNAL bar so it pairs with the engine's own trade id for
        // this entry, and carrying the ticker so the exit ladder does not have to
        // resolve it again at the worst possible moment.
        try {
            com.signalscope.app.data.T212LiveStore.upsert(ctx,
                com.signalscope.app.data.T212LiveStore.LiveTrade(
                    paperTradeId = "$sym-${fresh.entryTs}", ns = ns, symbol = sym,
                    t212Ticker = ticker, status = "OPEN", entryTs = fresh.entryTs,
                    paperEntryPrice = fresh.entryPrice, amount = cash,
                    qty = r.filled, buyOrderIds = r.orderIds,
                    buyOrderId = r.orderIds.firstOrNull() ?: 0L,
                    buyAt = System.currentTimeMillis()))
        } catch (e: Exception) {
            Log.e(TAG, "[$base] $sym live row write failed — exits cannot act on it", e)
        }

        // ── the hand-over ──
        try {
            StrategyLabStore.enroll(ctx, sym, Spaces.exchangeOf(base), ns)
            val anchor = fresh.entryTs - 1L
            StrategyLabStore.applyRunResult(
                ctx, sym, emptyList(), emptyList(), anchor, ns, baseline = anchor)
            StrategyLabStore.setEnabled(ctx, true, ns)
            // FORCE THE NEXT PASS TO ACTUALLY LOOK AT IT.
            //
            // The runner skips refetching a symbol until two exit frames have passed
            // since its watermark — four hours on a 120-minute exit frame. The
            // hand-over stamps that watermark at the signal bar, and a signal is
            // fresh by definition, so the guard would skip this symbol for the rest
            // of the window: the engine would never see the position it was just
            // handed, and a REAL holding would sit with nothing managing its exit
            // for hours while the panel correctly reported exactly that.
            //
            // The guard exempts a symbol with no mark, by design. This is that
            // exemption, used deliberately.
            try { MarkToMarket.forget(sym) } catch (_: Exception) {}
            Log.i(TAG, "[$base] $sym handed over — baseline anchored at $anchor (signal bar ${fresh.entryTs})")
        } catch (e: Exception) {
            Log.e(TAG, "[$base] $sym hand-over failed", e)
            return BuyResult(true, sym, r.filled, fillPx, fresh.entryPrice, drift,
                note = "Bought, but the hand-over failed (${e.message}) — the exit is NOT being managed. " +
                       "Check the manual algo before relying on it.")
        }

        return BuyResult(true, sym, r.filled, fillPx, fresh.entryPrice, drift,
            note = if (fillPx <= 0.0)
                ("Fill price not settled in T212's history yet — it fills in on a later pass. " + r.note).trim()
            else r.note)
    }

    /**
     * The Indian half of [buyAndHandOver].
     *
     * Same shape as the Trading 212 path — re-check the signal, place the order,
     * record the position, hand it to the engine — with two differences that come
     * from the broker rather than from choice:
     *
     *  • WHOLE SHARES. NSE has no fractional trading, so the amount buys as many
     *    shares as it covers and refuses below one, rather than silently rounding
     *    up and spending more than was asked.
     *
     *  • A BROKER-HELD PROTECTIVE TRIGGER goes on immediately. It is the reason
     *    this path is worth having at all: on Trading 212 the stop lives in this
     *    app and only acts while the app is running, whereas Zerodha holds the
     *    trigger itself and will act on a falling price whether the phone is on,
     *    off, or in another country. For a market that trades while you are
     *    asleep, that difference is the whole point.
     */
    private fun buyIndia(ctx: Context, base: String, sym: String, cash: Double,
                         force: Boolean = false): BuyResult {
        val ns = ManualWatchStore.nsFor(base)
        // Same re-check as every other path, and — critically — with the same
        // fresh-start boundary the alert used. Without it this asks a different
        // question than the one that produced the notification and refuses buys
        // the app just recommended. See the long note in [buyAndHandOver].
        val since = ManualWatchStore.load(ctx, base).watchingSince[sym] ?: 0L
        val signalled = StrategyLabRunner.freshEntrySignal(ctx, ns, sym, Spaces.exchangeOf(base), since)
        val fresh = signalled ?: if (force) {
            val lb = StrategyLabRunner.latestClosedBar(ctx, ns, sym, Spaces.exchangeOf(base))
                ?: return BuyResult(false, sym, error = "No usable price data for $sym right now.")
            StrategyLabRunner.FreshEntry(sym, lb.ts, lb.close,
                "forced entry — no signal, bought on your call", lb.ts, lb.close, 0, 0)
        } else return BuyResult(false, sym,
            error = "No entry signal on the latest closed bar any more — the setup has passed. " +
                    "Open the chart to see where it fired and how far price has moved.")
        val forced = signalled == null

        val paperTradeId = "$sym-${fresh.entryTs}"
        // CLAIMED, NOT OVERWRITTEN — the Tata Steel bug. A repeat tap was refused
        // by the broker and that refusal was written over the live trigger's row.
        ManualWatchStore.claim(ctx, base, ManualWatchStore.Entry(
            symbol = sym, space = base, signalBarTs = fresh.entryTs, signalPrice = fresh.entryPrice,
            requestedCash = cash, createdAt = System.currentTimeMillis(),
            status = "PENDING", note = fresh.entryReason))
            ?.let { return alreadyOnSignal(sym, it) }

        // AN ORDINARY ORDER FIRST, A TRIGGER ONLY IF THE BROKER REFUSES ONE.
        // The two produce entries in completely different states and the flag on
        // the result is the only thing that says which — see the block above
        // [ZerodhaLiveMirror.buyNowOrRest].
        val r = ZerodhaLiveMirror.buyNowOrRest(ctx, ns, sym, cash, fresh.entryPrice, paperTradeId)
        if (!r.ok) {
            ManualWatchStore.upsert(ctx, base, ManualWatchStore.Entry(
                symbol = sym, space = base, signalBarTs = fresh.entryTs, signalPrice = fresh.entryPrice,
                requestedCash = cash, createdAt = System.currentTimeMillis(),
                status = "FAILED", note = r.error))
            return BuyResult(false, sym, error = r.error)
        }

        // ── BOUGHT, NOW ──────────────────────────────────────────────────────
        // The shares exist and their real cost is known, so the entry opens and
        // the strategy adopts it in the same breath. Protection is already on —
        // the mirror attaches it at the moment of the fill, which is the whole
        // advantage of an ordinary order over a trigger.
        if (r.immediate && r.filledQty > 0 && r.filledPrice > 0.0) {
            ManualWatchStore.upsert(ctx, base, ManualWatchStore.Entry(
                symbol = sym, space = base, signalBarTs = fresh.entryTs, signalPrice = fresh.entryPrice,
                requestedCash = cash, filledQty = r.filledQty.toDouble(), filledPrice = r.filledPrice,
                t212Ticker = sym, createdAt = System.currentTimeMillis(),
                status = "OPEN", note = fresh.entryReason, forced = forced,
                buyOrderId = r.orderId.ifBlank { null }))
            adoptIntoStrategy(ctx, ns, sym, base, fresh.entryTs)
            Log.w(TAG, "[$base] $sym BOUGHT ${r.filledQty} at ${"%.2f".format(r.filledPrice)} " +
                "(order ${r.orderId}) — open and handed to the strategy")
            try { LabNotifier.t212Buy(ctx, ns, sym, r.filledQty.toDouble(), r.filledPrice, "INR") }
            catch (_: Exception) {}
            return BuyResult(true, sym, r.filledQty.toDouble(), r.filledPrice,
                fresh.entryPrice, 0.0, note = r.note, immediate = true)
        }

        // ── NOT BOUGHT YET ───────────────────────────────────────────────────
        // Either a resting trigger, or an ordinary order the broker took but has
        // not finished filling. Both are instructions, not holdings: the shares
        // do not exist and the fill price is unknown. Marking either OPEN would
        // put a phantom position in the book and start measuring exits against a
        // price nobody has paid.
        //
        // The hand-over waits for the same reason — [settleWaitingBuys] performs
        // it when the shares actually appear.
        ManualWatchStore.upsert(ctx, base, ManualWatchStore.Entry(
            symbol = sym, space = base, signalBarTs = fresh.entryTs, signalPrice = fresh.entryPrice,
            requestedCash = cash, filledQty = 0.0, filledPrice = 0.0,
            t212Ticker = sym, createdAt = System.currentTimeMillis(),
            status = "PENDING", note = fresh.entryReason, forced = forced,
            gttId = r.gttId, triggerPrice = if (r.immediate) 0.0 else r.price,
            buyOrderId = r.orderId.ifBlank { null }))
        if (r.immediate)
            Log.i(TAG, "[$base] $sym order ${r.orderId} accepted, still filling")
        else
            Log.i(TAG, "[$base] $sym buy trigger ${r.gttId} resting at " +
                "${"%.2f".format(r.price)} — ordinary order refused: ${r.refusal}")
        // ZERO FILLED, because zero filled. The intended size and the level it is
        // waiting at both live in the note; putting them in the fill slots makes
        // every reader downstream believe a purchase happened.
        return BuyResult(true, sym, 0.0, 0.0, fresh.entryPrice, 0.0,
            note = r.note, immediate = r.immediate)
    }

    /**
     * Hand a freshly opened Indian position to the strategy that will manage it.
     *
     * Shared by both ways a position can appear — bought outright, or noticed
     * later after a trigger filled — because the adoption has to be identical.
     * The baseline is back-dated to just BEFORE the signal bar so the replay
     * re-enters on its own entry rather than treating the position as inherited
     * from nowhere; getting that wrong is what makes a strategy exit on its first
     * pass because it cannot see why it is holding anything.
     */
    private fun adoptIntoStrategy(ctx: Context, ns: String, sym: String,
                                  base: String, signalBarTs: Long) {
        StrategyLabStore.enroll(ctx, sym, Spaces.exchangeOf(base), ns)
        val anchor = signalBarTs - 1L
        StrategyLabStore.applyRunResult(ctx, sym, emptyList(), emptyList(), anchor, ns, baseline = anchor)
        StrategyLabStore.setEnabled(ctx, true, ns)
        MarkToMarket.forget(sym)
    }

    /**
     * TAKE A HOLDING THE APP NEVER BOUGHT UNDER FULL MANAGEMENT.
     *
     * The portfolio pass already judges these with the same config, but it only
     * ever prompts — deliberately, because applying a rule retrospectively to
     * something you have held for years is a different order of decision from
     * managing a position the app opened itself. This is how you say "no, treat
     * this one like the app's own": it moves into the book, and from then on its
     * exits act rather than ask.
     *
     * REACHABLE ONLY FROM AN EXPLICIT TAP. There is no automatic caller and there
     * must not be one — nothing should quietly acquire the right to sell shares
     * you bought elsewhere.
     *
     * ANCHORED ON WHAT YOU ACTUALLY PAID and on when the app first saw the
     * holding, which is the same anchor the portfolio pass has been using. The
     * broker does not report a purchase date, so the rules that count bars start
     * from first sight rather than from your real purchase — honest but weaker,
     * and the caller is told so.
     *
     * TOPS UP RATHER THAN DUPLICATES. If the book already carries some of this
     * name, the existing row's quantity is raised to the broker's full holding
     * instead of a second row being created, because two rows on one position is
     * how you end up selling the same shares twice.
     */
    fun adoptHolding(ctx: Context, space: String, symbol: String): BuyResult {
        val base = Spaces.baseOf(space.lowercase())
        val sym = symbol.trim().uppercase()
        val ns = ManualWatchStore.nsFor(base)
        if (!isConfigured(ctx, base)) return BuyResult(false, sym,
            error = "Attach a strategy to this space first — without one there are no exit rules to apply.")

        val held = when (val r = BrokerHoldings.forSpace(ctx, base)) {
            is BrokerHoldings.Result.Unreadable -> return BuyResult(false, sym,
                error = "Could not read your holdings from the broker (${r.why}). Nothing changed.")
            is BrokerHoldings.Result.Ok -> r.holdings.firstOrNull { it.symbol.equals(sym, true) }
        } ?: return BuyResult(false, sym, error = "The broker does not report a holding in $sym.")
        if (held.qty <= 0.0 || held.avgCost <= 0.0) return BuyResult(false, sym,
            error = "The broker reports no usable quantity or cost for $sym.")

        // ── IS SOMETHING ELSE ALREADY MANAGING THIS NAME? ────────────────────
        //
        // The check was only against the watcher's OWN book, so a name an ALGO
        // held sailed through — and the same shares ended up claimed by two
        // managers, each of which would try to sell them. That is the exact
        // situation every other path in this app refuses, and this one had a hole
        // in it because it only ever looked at half the picture.
        // TAKE ONLY WHAT NOBODY ELSE IS MANAGING.
        //
        // This used to take the broker's FULL holding, so moving in a name an algo
        // partly held produced two records for one pile of shares — the ledger
        // claimed 22.792 DBK against 11.396 actually owned. The rule is one owner
        // per share: the algo keeps what it bought, this book takes the remainder.
        val nsFor = ManualWatchStore.nsFor(base)
        val byAlgos = try {
            com.signalscope.app.data.T212LiveStore.load(ctx).trades
                .filter {
                    Spaces.baseOf(it.ns) == base && it.ns != nsFor &&
                        it.symbol.equals(sym, true) && it.qty > 0.0 &&
                        (it.status == "OPEN" || it.status == "PENDING" || it.status == "EXITING")
                }.sumOf { it.qty }
        } catch (e: Exception) { 0.0 }
        val mineQty = (held.qty - byAlgos).coerceAtLeast(0.0)
        if (mineQty <= maxOf(1e-6, held.qty * 0.01)) return BuyResult(false, sym,
            error = "An algo already manages all ${"%.4f".format(held.qty)} of $sym. There is nothing " +
                "left over for the watcher to take — disarm it there if you want this book to have it.")

        // THE BROKER'S OWN TICKER, resolved now.
        //
        // This used to be left blank, and the fallback further down substituted the
        // bare symbol. Trading 212 does not name instruments that way, so every
        // holding moved here failed to match the account and was reported by the
        // ledger audit as a position the broker had lost — five of them, on a card
        // warning that the P&L could not be trusted.
        val t212Ticker = if (base == "de" || base == "us") {
            val client = com.signalscope.app.network.Trading212Client(ConfigManager(ctx))
            client.resolveT212Ticker(sym, if (base == "de") "DE" else "US")
                ?: return BuyResult(false, sym,
                    error = "Could not find $sym at Trading 212 (${client.resolveFailureReason(sym, if (base == "de") "DE" else "US")}). " +
                        "Nothing moved — a position recorded under the wrong ticker cannot be sold by the app.")
        } else ""

        val st = ManualWatchStore.load(ctx, base)
        val existing = st.entries.filter { it.symbol.equals(sym, true) && it.status == "OPEN" }
            .maxByOrNull { it.signalBarTs }
        val since = com.signalscope.app.data.PortfolioTrackStore.startFor(ctx, base, sym, held.avgCost)

        return try {
            if (existing != null) {
                ManualWatchStore.upsert(ctx, base, existing.copy(
                    filledQty = mineQty, filledPrice = held.avgCost,
                    t212Ticker = existing.t212Ticker.ifBlank { t212Ticker },
                    note = "your share only — the book covers ${"%.4f".format(mineQty)} of ${"%.4f".format(held.qty)}"))
                Log.w(TAG, "[$base] $sym combined: book raised to ${held.qty} at ${"%.2f".format(held.avgCost)}")
            } else {
                ManualWatchStore.upsert(ctx, base, ManualWatchStore.Entry(
                    symbol = sym, space = base,
                    // The app's first sight of the holding, used as the entry bar.
                    // It is the same anchor the portfolio pass judged it on, so
                    // adopting it does not change the verdict it was already giving.
                    signalBarTs = since, signalPrice = held.avgCost,
                    requestedCash = mineQty * held.avgCost,
                    filledQty = mineQty, filledPrice = held.avgCost,
                    t212Ticker = t212Ticker, createdAt = System.currentTimeMillis(),
                    status = "OPEN", forced = true,
                    note = "moved from your portfolio — bought outside this app"))
                Log.w(TAG, "[$base] $sym moved into the book: ${held.qty} at ${"%.2f".format(held.avgCost)}")
            }
            adoptIntoStrategy(ctx, ns, sym, base, since)
            // It is the book's now, so the portfolio pass must stop tracking it —
            // otherwise it keeps a stale reason and could prompt about shares that
            // are being managed.
            com.signalscope.app.data.PortfolioTrackStore.mutate(ctx, base) { s ->
                s.copy(tracks = s.tracks - sym)
            }
            BuyResult(true, sym, mineQty, held.avgCost,
                note = "The watcher now manages ${"%.4f".format(mineQty)} of $sym" +
                    (if (byAlgos > 0.0) " — the other ${"%.4f".format(byAlgos)} stays with the algo that bought it" else "") +
                    ". Its exits will SELL rather than prompt.")
        } catch (e: Exception) {
            Log.e(TAG, "[$base] adoptHolding $sym failed", e)
            BuyResult(false, sym, error = e.message ?: "could not move this holding")
        }
    }

    /**
     * Call off a resting buy trigger the user has changed their mind about.
     *
     * Reachable only from an explicit tap, like the buy itself. The entry is left
     * untouched unless the broker confirms the trigger is gone — a row that says
     * cancelled while the order is still live at Zerodha is worse than one that
     * says nothing.
     */
    fun cancelTrigger(ctx: Context, space: String, symbol: String, signalBarTs: Long): BuyResult {
        val base = Spaces.baseOf(space.lowercase())
        val sym = symbol.trim().uppercase()
        val ns = ManualWatchStore.nsFor(base)
        if (base != "in") return BuyResult(false, sym,
            error = "Only Indian entries wait at the broker like this.")
        val st = ManualWatchStore.load(ctx, base)
        val e = st.entries.lastOrNull {
            it.symbol.equals(sym, true) && it.signalBarTs == signalBarTs && it.status == "PENDING"
        } ?: return BuyResult(false, sym, error = "No waiting order found for $sym.")

        val r = ZerodhaLiveMirror.cancelBuyTrigger(ctx, ns, "$sym-$signalBarTs")
        if (!r.ok) return BuyResult(false, sym, error = r.error)

        ManualWatchStore.upsert(ctx, base, e.copy(
            status = "CANCELLED", gttId = 0L, buyOrderId = null,
            note = "cancelled before it filled", closedAt = System.currentTimeMillis()))
        Log.w(TAG, "[$base] $sym waiting buy cancelled by the user")
        return BuyResult(true, sym, note = "The waiting order for $sym has been removed.")
    }

    /**
     * Turn a triggered buy into a real position once the shares actually arrive.
     *
     * An ordinary order answers immediately; a trigger answers whenever the market
     * reaches it, which is usually while the app is doing something else or not
     * running at all. So the fill is detected rather than awaited: when the broker
     * starts reporting the holding, the entry becomes OPEN at the cost the broker
     * reports, the strategy adopts it, and protection goes on.
     *
     * The broker's average cost is used, never the trigger price. They differ —
     * a triggered limit fills wherever the book was at that instant — and every
     * exit rule measures from this number.
     */
    /** The refusal a second buy on the same signal gets, saying what is already there. */
    private fun alreadyOnSignal(sym: String, standing: ManualWatchStore.Entry): BuyResult {
        val what = when {
            standing.status == "PENDING" && standing.gttId > 0L ->
                "a buy trigger is already resting at the broker"
            standing.status == "PENDING" -> "a buy is already being placed"
            standing.status == "OPEN" || standing.filledQty > 0.0 -> "it has already been bought"
            else -> "this signal has already been traded"
        }
        Log.w(TAG, "$sym: second buy on the same signal refused — $what")
        return BuyResult(false, sym, error = "Not bought again: on this signal for $sym, $what. " +
            "Nothing was changed. To add more, wait for the next signal or use the broker's app.")
    }

    /**
     * PUT BACK A WAITING ORDER THE BOOK HAD WRITTEN OFF.
     *
     * The repair half of the Tata Steel bug: the new claim stops it happening
     * again, but a row already overwritten stays wrong on its own. Its trigger
     * is still resting at Zerodha, and if it fills the shares would arrive with
     * no row to settle into — no strategy handover, no protective stop.
     *
     * The order mirror still has the truth. So where the book shows an attempt
     * that bought nothing but the mirror holds a live trigger for the same
     * signal, the book row is restored to waiting, with that trigger's id — the
     * exact state the settle step below looks for.
     */
    private fun restoreLostTriggers(ctx: Context, base: String) {
        if (base != "in") return
        val ns = ManualWatchStore.nsFor(base)
        val dead = ManualWatchStore.load(ctx, base).entries.filter { ManualWatchStore.isDeadAttempt(it) }
        for (e in dead) {
            val row = com.signalscope.app.data.ZerodhaLiveStore
                .forTrade(ctx, ns, "${e.symbol.uppercase()}-${e.signalBarTs}") ?: continue
            val gtt = row.gttId ?: 0L
            if (row.status != "PENDING" || gtt <= 0L) continue
            ManualWatchStore.upsert(ctx, base, e.copy(status = "PENDING", gttId = gtt,
                closedAt = 0L,
                note = "restored — a later tap had overwritten this record while its " +
                    "trigger was still waiting at Zerodha"))
            Log.w(TAG, "[$base] ${e.symbol}: restored a waiting trigger (GTT $gtt) the book had written off")
        }
    }

    private fun settleWaitingBuys(ctx: Context, base: String) {
        if (base != "in") return
        val st = ManualWatchStore.load(ctx, base)
        // BOTH KINDS OF WAITING ENTRY. A resting trigger, and an ordinary order
        // the broker accepted but had not finished filling by the time the tap
        // returned. Filtering on the trigger id alone — which this did — left the
        // second kind PENDING for ever: real shares, no position in the book, and
        // no protective stop, because nothing ever came back to look at it.
        val waiting = st.entries.filter {
            it.status == "PENDING" && (it.gttId > 0L || !it.buyOrderId.isNullOrBlank())
        }
        if (waiting.isEmpty()) return
        val holdings = (BrokerHoldings.zerodha(ctx) as? BrokerHoldings.Result.Ok)?.holdings ?: return
        val byName = holdings.associateBy { it.symbol.uppercase() }
        val ns = ManualWatchStore.nsFor(base)

        for (e in waiting) {
            val sym = e.symbol.uppercase()
            val h = byName[sym] ?: continue          // not filled yet — keep waiting
            if (h.qty <= 0.0 || h.avgCost <= 0.0) continue

            ManualWatchStore.upsert(ctx, base, e.copy(
                status = "OPEN", filledQty = h.qty, filledPrice = h.avgCost))
            try {
                com.signalscope.app.data.ZerodhaLiveStore.forTrade(ctx, ns, "$sym-${e.signalBarTs}")?.let {
                    com.signalscope.app.data.ZerodhaLiveStore.upsert(ctx,
                        it.copy(status = "OPEN", qty = h.qty.toInt(), entryPrice = h.avgCost, gttId = null))
                }
                adoptIntoStrategy(ctx, ns, sym, base, e.signalBarTs)
                // Protection now, not on the next sweep. The emergency-stop pass
                // would get there eventually, but "eventually" on a fresh position
                // is exactly the window worth closing.
                ZerodhaLiveMirror.attachProtectionFor(ctx, ns, "$sym-${e.signalBarTs}",
                    sym, h.qty.toInt(), h.avgCost)
                Log.w(TAG, "[$base] $sym waiting buy FILLED — ${h.qty} at ${"%.2f".format(h.avgCost)}, " +
                    "handed to the strategy and protected")
            } catch (ex: Exception) {
                Log.e(TAG, "[$base] $sym settle after fill failed", ex)
            }
            try {
                LabNotifier.t212Buy(ctx, ns, sym, h.qty, h.avgCost, "INR")
            } catch (_: Exception) {}
        }
    }

    /**
     * Fill in the REAL average buy price for manual entries that don't have one,
     * from T212's executed-order history, matched by ORDER ID.
     *
     * By id, never by symbol and time: the ladder places up to six orders on one
     * ticker and the account is shared with the user's own manual trading, so a
     * symbol+timestamp match would happily pair our position with an order placed
     * by hand a minute earlier and silently corrupt the drift figure.
     *
     * A ladder fills across several rungs at different prices, so the honest number
     * is the VALUE-WEIGHTED average of the matching fills, not the last one.
     *
     * Read-only against the broker. Safe to call repeatedly: entries that already
     * have a price are skipped, and an order that has not settled yet is simply
     * left for the next pass.
     */
    /**
     * Give an already-bought manual position the live-order row it is missing.
     *
     * WHY THIS MATTERS MORE THAN IT SOUNDS: every exit path — the strategy's and
     * the fill-anchored stop's — finds a position through the live-order ledger. A
     * row that does not exist is not a position with a broken stop; it is a
     * position no stop can SEE. It sits through any fall, indefinitely, while the
     * card cheerfully prints the stop price it is already below.
     *
     * Manual buys made before that row was being written are in exactly that
     * state. They cannot be repaired by hand and would otherwise stay unmanaged for
     * as long as they are held, so the repair runs itself, every pass.
     *
     * Only fills in what is MISSING — an existing row is never overwritten, since
     * it may carry order ids and reconciled prices this cannot reconstruct.
     */
    /**
     * ONE PILE OF SHARES, ONE OWNER EACH.
     *
     * The rule: an algo is responsible for exactly the shares it bought, and this
     * book is responsible for whatever is left over. Neither may claim the other's.
     *
     * WHY THIS HAS TO BE CONTINUOUS rather than a repair. The moving-in path takes
     * a snapshot, and snapshots go stale: the algo buys more of a name the book
     * already covers, or sells part of its own, and the split silently stops being
     * true. Left alone that produces two records for one holding — which is how
     * the ledger came to claim 22.792 DBK against 11.396 actually held, and how an
     * exit could have tried to sell shares another manager had already sold.
     *
     * ONLY EVER SHRINKS THIS BOOK. It never touches an algo's seat, never grows a
     * claim, and never places an order. If the broker cannot be read it changes
     * nothing at all — an unreadable account is not evidence that a position moved.
     */
    private fun separateFromAlgos(ctx: Context, base: String) {
        if (base != "de" && base != "us") return
        val ns = ManualWatchStore.nsFor(base)
        val holdings = when (val r = BrokerHoldings.forSpace(ctx, base)) {
            is BrokerHoldings.Result.Ok -> r.holdings.associateBy { it.symbol.uppercase() }
            is BrokerHoldings.Result.Unreadable -> return
        }
        val algoQty = HashMap<String, Double>()
        try {
            com.signalscope.app.data.T212LiveStore.load(ctx).trades
                .filter {
                    Spaces.baseOf(it.ns) == base && it.ns != ns && it.qty > 0.0 &&
                        (it.status == "OPEN" || it.status == "PENDING" || it.status == "EXITING")
                }
                .forEach { r ->
                    val k = r.symbol.uppercase()
                    algoQty[k] = (algoQty[k] ?: 0.0) + r.qty
                }
        } catch (e: Exception) { Log.w(TAG, "[$base] algo quantities unreadable", e); return }
        if (algoQty.isEmpty()) return

        for (e in ManualWatchStore.load(ctx, base).entries) {
            if (e.status != "OPEN" || e.filledQty <= 0.0) continue
            val sym = e.symbol.uppercase()
            val algo = algoQty[sym] ?: continue
            val broker = holdings[sym]?.qty ?: continue
            val mine = (broker - algo).coerceAtLeast(0.0)
            // Dust, not a position: a fractional sliver left by rounding is not
            // worth a book entry and certainly not worth an exit order.
            val dust = maxOf(1e-6, broker * 0.01)
            if (mine <= dust) {
                ManualWatchStore.upsert(ctx, base, e.copy(
                    status = "CLOSED", filledQty = 0.0, exitQty = 0.0,
                    closedAt = System.currentTimeMillis(),
                    exitReason = "handed back to the algo — it holds all ${"%.4f".format(broker)} of this name",
                    note = "the algo owns this position; the book was double-counting it"))
                try {
                    com.signalscope.app.data.T212LiveStore.get(ctx, "$sym-${e.signalBarTs}", ns)?.let {
                        com.signalscope.app.data.T212LiveStore.upsert(ctx,
                            it.copy(status = "MANUAL_CLOSED",
                                error = "the algo holds this name — book entry retired, nothing sold"))
                    }
                } catch (ex: Exception) { Log.w(TAG, "[$base] $sym ledger retire failed", ex) }
                Log.w(TAG, "[$base] $sym: the algo holds all ${broker} — book entry retired (nothing sold)")
            } else if (kotlin.math.abs(mine - e.filledQty) > dust) {
                ManualWatchStore.upsert(ctx, base, e.copy(filledQty = mine,
                    note = "your share only — the algo holds ${"%.4f".format(algo)} of ${"%.4f".format(broker)}"))
                try {
                    com.signalscope.app.data.T212LiveStore.get(ctx, "$sym-${e.signalBarTs}", ns)?.let {
                        com.signalscope.app.data.T212LiveStore.upsert(ctx, it.copy(qty = mine, liveQtyFilled = mine))
                    }
                } catch (ex: Exception) { Log.w(TAG, "[$base] $sym ledger trim failed", ex) }
                Log.w(TAG, "[$base] $sym: book trimmed ${e.filledQty} → $mine " +
                    "(algo holds $algo of $broker) — nothing sold")
            }
        }
    }

    private fun backfillLiveRows(ctx: Context, base: String) {
        // TRADING 212 SPACES ONLY — this writes into the Trading 212 order ledger.
        //
        // It had no such guard, so every Indian holding the watcher opened was
        // also written in there as though Trading 212 had bought it. The ledger
        // audit then compared those rows against an actual Trading 212 portfolio
        // that had never heard of them and reported each as a missing position:
        // thirteen false shortfalls on the German card, under a banner telling the
        // user their P&L and seat balances were unreliable.
        //
        // India's real orders live in the Zerodha ledger and are settled by the
        // Zerodha path; there was never anything for this to do there.
        if (base != "de" && base != "us") return
        val ns = ManualWatchStore.nsFor(base)
        val st = ManualWatchStore.load(ctx, base)
        val open = st.entries.filter { it.status == "OPEN" && it.filledQty > 0.0 }
        if (open.isEmpty()) return
        val client by lazy { Trading212Client(ConfigManager(ctx)) }
        val t212Space = if (base == "de") "DE" else "US"
        for (e in open) {
            val id = "${e.symbol.uppercase()}-${e.signalBarTs}"
            val existingRow = com.signalscope.app.data.T212LiveStore.get(ctx, id, ns)
            if (existingRow != null) {
                // REPAIR A TICKER THAT CANNOT MATCH THE BROKER.
                //
                // Rows written before the adopt path resolved one carry the bare
                // symbol, and Trading 212 does not name instruments that way — so
                // every comparison against the account missed and the audit called
                // each a position the broker had lost. Corrected in place rather
                // than left for the user to clear, because the holding is real and
                // only its label was wrong.
                if (!existingRow.t212Ticker.contains('_')) {
                    val fixed = try { client.resolveT212Ticker(e.symbol.uppercase(), t212Space) }
                                catch (ex: Exception) { null }
                    if (fixed != null && fixed != existingRow.t212Ticker) {
                        com.signalscope.app.data.T212LiveStore.upsert(ctx,
                            existingRow.copy(t212Ticker = fixed))
                        Log.w(TAG, "[$base] ${e.symbol} ledger ticker repaired " +
                            "'${existingRow.t212Ticker}' → '$fixed' — it could never have matched the account")
                    }
                }
                continue
            }
            // NO ROW WITHOUT A REAL TICKER. Writing the bare symbol as a fallback
            // created a record the app could neither match nor sell; saying so and
            // waiting is the honest response.
            if ((base == "de" || base == "us") && !e.t212Ticker.contains('_')) {
                val resolved = try { client.resolveT212Ticker(e.symbol.uppercase(), t212Space) }
                               catch (ex: Exception) { null }
                if (resolved == null) {
                    Log.w(TAG, "[$base] ${e.symbol} has no Trading 212 ticker — no ledger row created")
                    continue
                }
                com.signalscope.app.data.T212LiveStore.upsert(ctx,
                    com.signalscope.app.data.T212LiveStore.LiveTrade(
                        paperTradeId = id, ns = ns, symbol = e.symbol.uppercase(),
                        t212Ticker = resolved, status = "OPEN", entryTs = e.signalBarTs,
                        paperEntryPrice = e.signalPrice, amount = e.requestedCash,
                        qty = e.filledQty, buyOrderIds = e.orderIds,
                        buyOrderId = e.orderIds.firstOrNull() ?: 0L,
                        liveEntryPrice = e.filledPrice, liveQtyFilled = e.filledQty,
                        buyAt = e.createdAt))
                Log.w(TAG, "[$base] ${e.symbol} had NO live-order row — created one as $resolved")
                continue
            }
            com.signalscope.app.data.T212LiveStore.upsert(ctx,
                com.signalscope.app.data.T212LiveStore.LiveTrade(
                    paperTradeId = id, ns = ns, symbol = e.symbol.uppercase(),
                    t212Ticker = e.t212Ticker.ifBlank { e.symbol.uppercase() },
                    status = "OPEN", entryTs = e.signalBarTs,
                    paperEntryPrice = e.signalPrice, amount = e.requestedCash,
                    qty = e.filledQty, buyOrderIds = e.orderIds,
                    buyOrderId = e.orderIds.firstOrNull() ?: 0L,
                    // Carry the per-share price already reconciled, so the
                    // fill-anchored stop can act at once rather than waiting for the
                    // broker history to be read again.
                    liveEntryPrice = e.filledPrice, liveQtyFilled = e.filledQty,
                    buyAt = e.createdAt))
            Log.w(TAG, "[$base] ${e.symbol} had NO live-order row — created one; " +
                "no stop could have fired for it until now")
        }
    }

    /**
     * Mark manual positions the strategy has finished with as CLOSED.
     *
     * WHY THIS HAD TO BE ADDED: nothing ever wrote that state. The book could say
     * waiting, bought or failed — and a sold position simply stayed "bought" for
     * good. Two things followed, neither of which looked like bookkeeping:
     *
     *   • the row wore a permanent red "no exit manager". That warning fires on a
     *     position the engine is not holding, and after a normal exit the engine
     *     is not holding it BECAUSE IT JUST SOLD IT. The one loud signal for a
     *     genuinely unguarded position was being spent on every completed trade.
     *   • [heldAnywhere] counts OPEN manual entries as exposure, so the name was
     *     blocked from the candidate list for ever. One round trip retired a
     *     company permanently, silently, with no way to tell from the UI.
     *
     * THE PAPER LEDGER IS THE TRIGGER, not the broker row: both brokers hand over
     * to the same engine, and it is the engine that decides a trade is over.
     * Keying off Trading 212's ledger would have left every Indian position open
     * for ever — the same bug again, wearing a smaller blast radius.
     *
     * The broker row supplies only the PRICE, because the engine's exit is a
     * candle close and yours is a fill. Where no real price is known the field
     * stays at zero and the card says whose figure it is showing. Printing a
     * candle close under the heading "your money" is the exact mistake that once
     * put a $196 cost on a $227 fill, and it is not worth repeating to avoid a
     * blank.
     *
     * A position whose HAND-OVER FAILED has no paper trade at all, so it is never
     * closed here and keeps its warning. That is the case the warning exists for,
     * and it stays loud.
     */
    private fun closeFinishedEntries(ctx: Context, base: String) {
        val ns = ManualWatchStore.nsFor(base)
        val st = ManualWatchStore.load(ctx, base)
        val open = st.entries.filter { it.status == "OPEN" }
        if (open.isEmpty()) return
        val ledger = try { StrategyLabStore.load(ctx, ns).trades }
                     catch (e: Exception) { Log.w(TAG, "[$base] ledger read failed", e); return }
        if (ledger.isEmpty()) return

        for (e in open) {
            val id = "${e.symbol.uppercase()}-${e.signalBarTs}"
            val done = ledger.firstOrNull { it.id == id && it.status == "CLOSED" } ?: continue

            // The REAL exit, per broker. Both are per-share in the instrument's own
            // currency — neither is reconstructed from account-currency cash.
            var realExit = 0.0
            var realQty = 0.0
            // Epoch MILLISECONDS — both brokers stamp their sale time that way. The
            // book's exit time is SECONDS, like every bar time it is compared with,
            // so it is converted where it is written below. Storing it raw put a
            // closed position somewhere around the year 58000 on anything that
            // read it as a date.
            var exitAt = 0L
            try {
                if (base == "in") {
                    com.signalscope.app.data.ZerodhaLiveStore.forTrade(ctx, ns, id)?.let { r ->
                        if (r.status == "CLOSED" || r.status == "MANUAL_CLOSED") {
                            realExit = r.exitPrice
                            realQty = r.qty.toDouble()
                            exitAt = r.sellAt
                        }
                    }
                } else {
                    com.signalscope.app.data.T212LiveStore.get(ctx, id, ns)?.let { r ->
                        if (r.status == "CLOSED" || r.status == "MANUAL_CLOSED") {
                            realExit = r.liveExitPrice
                            // Dust the exit had to clear to be a legal order was never
                            // bought here, so it is not part of what this position sold.
                            realQty = (r.liveExitQty - r.sweptQty).coerceAtLeast(0.0)
                            exitAt = r.sellAt
                        }
                    }
                }
            } catch (ex: Exception) {
                Log.w(TAG, "[$base] ${e.symbol} real-exit lookup failed", ex)
            }

            ManualWatchStore.upsert(ctx, base, e.copy(
                status = "CLOSED",
                exitTs = if (exitAt > 0L) exitAt / 1000L else (done.exitTs ?: 0L),
                exitPrice = realExit,
                exitQty = realQty,
                exitReason = done.exitReason.orEmpty().ifBlank { "closed by the strategy" },
                closedAt = System.currentTimeMillis()))
            Log.i(TAG, "[$base] ${e.symbol} CLOSED — strategy ${"%.2f".format(done.pctReturn)}%" +
                (if (realExit > 0.0 && e.filledPrice > 0.0)
                    ", your money ${"%.2f".format((realExit / e.filledPrice - 1.0) * 100.0)}%"
                 else ", no real exit price known yet"))
        }
    }

    /**
     * THE HAND-SET PERCENTAGE LEVELS, for this book only — a floor under the
     * position and a ceiling over it.
     *
     * Runs BEFORE the strategy's own exits on every pass, for one reason: these
     * are the levels the user put on the position themselves, and a level that
     * only gets consulted after every cleverer rule has had its turn is not a
     * level.
     *
     * THE TWO ANCHORS ARE DIFFERENT AND THAT IS THE POINT. The floor hangs off
     * the position's HIGH, so it follows a winner up. The ceiling hangs off what
     * you PAID, so it stays exactly where you put it. Anchoring the target on the
     * high instead would make it run away from price as the position rose — a
     * target that can never be reached.
     *
     * TWO SEPARATE JOBS, and they are separate on purpose. The high-water mark is
     * updated for every position whether or not anything is switched on, so
     * turning the trail on later measures from the real high rather than from
     * wherever price happened to be that afternoon. Only the acting half is gated.
     *
     * GERMANY AND THE US SELL; INDIA MOVES ITS RESTING TRIGGER. That is not an
     * inconsistency, it is the difference between the brokers: a ladder only
     * exists while the app does, and a Zerodha trigger keeps working with the
     * phone in a drawer and the session long expired — which is the whole reason
     * the Indian side is built around them. The one case India cannot park at the
     * broker is a target the market has ALREADY passed, because Zerodha will not
     * rest a sell above a price that is below it; that one is sold from here.
     */
    private fun runManualStopsAndTargets(ctx: Context, base: String) {
        val st = ManualWatchStore.load(ctx, base)
        val open = st.entries.filter { it.status == "OPEN" && it.filledQty > 0.0 && it.filledPrice > 0.0 }
        if (open.isEmpty()) return
        val ns = ManualWatchStore.nsFor(base)
        val pct = st.trailPct
        val unit = st.trailUnit ?: ManualWatchStore.TrailUnit.PCT
        val tpPct = st.takeProfitPct
        val src = st.trailSource
        // ONE READ OF THE BROKER'S TRIGGER LIST FOR THE WHOLE SWEEP. It was being
        // fetched per symbol per tick — the entire list, once for each holding —
        // on the endpoint this app has been rate-limited on before.
        val restingTriggers = if (base == "in" && (pct > 0.0 || tpPct > 0.0))
            try { ZerodhaLiveMirror.restingSellTriggers(ctx) } catch (ex: Exception) { null }
        else null

        // ── PRICE WHAT THIS SWEEP IS ABOUT TO JUDGE, BEFORE JUDGING IT ───────
        //
        // The stop used to read whatever the general refresh had left behind. That
        // refresh is asynchronous and throttled, so on any pass where it had not
        // finished the mark was a whole tick old — and in live-ticks mode the
        // ten-minute rule then refused it. The stop declined for the right reason
        // and the wrong cause: it was waiting on a background thread, not on the
        // market. With the app closed there is no tick stream at all, so that was
        // most passes rather than a rare race.
        //
        // Asking directly removes the dependency. A handful of quotes for the
        // names actually held, once per pass.
        //
        // ONLY WHERE IT CHANGES AN OUTCOME. Bar-close mode judges completed
        // candles and does not consult this at all, so fetching for it would be
        // requests spent on a number nobody reads.
        if ((pct > 0.0 || tpPct > 0.0) && src != ManualWatchStore.TrailSource.BAR_CLOSE) {
            try {
                MarkToMarket.ensureFresh(ctx, ns, open.map { it.symbol },
                    // Comfortably inside the firing bound, so a price fetched here
                    // is still actionable by the time the loop below reaches it.
                    ManualTrailingStop.TICK_MAX_AGE_MS / 2)
            } catch (ex: Exception) { Log.w(TAG, "[$base] pre-stop pricing failed", ex) }
        }

        for (e in open) {
            val sym = e.symbol.uppercase()
            // FRESH OR NOTHING. A mark of any age was being read as a live price,
            // so a stop could fire on last night's close — a number that has had
            // all night to be wrong, against a market that is shut. Zero here
            // means "no opinion", which every check below already honours.
            val live = try { MarkToMarket.freshPrice(sym) } catch (ex: Exception) { 0.0 }
            // THE PEAK MAY SEE WHAT THE TRIGGER MAY NOT. A pre-market or
            // after-hours print is a real trade and belongs in a high-water mark
            // — without it a US stop was blind from 22:00 to 15:30 and a stock
            // that ran overnight came back to a floor measured from yesterday.
            // It still may not FIRE anything: those books are thin enough that a
            // couple of hundred shares set a level no real seller could have got.
            val peakLive = try { MarkToMarket.peakPrice(sym) } catch (ex: Exception) { live }
            // ONE LOOKUP, TWO ANSWERS. This call downloads the name's candles;
            // taking only the closing price off them and throwing the rest away
            // was paying full price for a tenth of what arrived.
            val bar = try {
                StrategyLabRunner.latestClosedBar(ctx, ns, sym, Spaces.exchangeOf(base))
            } catch (ex: Exception) { null }
            val lastClose = bar?.close ?: 0.0
            bar?.levels?.let { lv ->
                if (lv.symbol.equals(sym, true))
                    ManualWatchStore.mutate(ctx, base) { s ->
                        s.copy(levels = s.levels + (sym to lv))
                    }
            }

            // The mark rises even when the trail is off — see the note above.
            val candidate = ManualTrailingStop.peakCandidate(src, peakLive, lastClose)
            val peak = ManualTrailingStop.nextPeak(e.peakPrice, e.filledPrice, candidate)
            // CARRIED FORWARD, not re-derived from the stale row. Writing the peak
            // and then writing a CLOSED status from the ORIGINAL copy overwrote the
            // mark that had just been saved — same key, older value — so the field
            // was wrong on precisely the rows anyone would review afterwards.
            var cur = e
            if (peak > e.peakPrice) {
                cur = e.copy(peakPrice = peak, peakAt = System.currentTimeMillis())
                ManualWatchStore.upsert(ctx, base, cur)
            }
            if (pct <= 0.0 && tpPct <= 0.0) continue

            // THE TRAIL'S WIDTH, in the unit chosen: a percentage of the high, or
            // a multiple of the stock's own daily range — the 14-day ATR or half
            // the Bollinger width — so a quiet stock and a wild one each get room
            // that suits them. The range moves daily; the floor still only rises.
            val level = if (pct <= 0.0) 0.0 else {
                val vol = if (unit == ManualWatchStore.TrailUnit.PCT) null
                          else DailyVolatility.of(sym, Spaces.exchangeOf(base))
                val raw = ManualTrailingStop.levelForWidth(peak,
                    ManualTrailingStop.widthFor(unit, pct, peak, vol?.atr ?: 0.0, vol?.bbHalf ?: 0.0))
                val basis = ManualTrailingStop.basis(unit, pct)
                val held = ManualTrailingStop.ratchet(cur.trailLevel, cur.trailBasis, basis, raw)
                if (held != cur.trailLevel || basis != cur.trailBasis) {
                    cur = cur.copy(trailLevel = held, trailBasis = basis)
                    ManualWatchStore.upsert(ctx, base, cur)
                }
                held
            }
            // THE CEILING IS MEASURED FROM YOUR FILL, NOT FROM THE PEAK — see
            // [ManualTakeProfit]. Hanging it off the high would make it retreat
            // upward as the position ran, which is a target that can never be
            // reached rather than a target at all.
            val ceiling = ManualTakeProfit.targetFor(e.filledPrice, tpPct)
            if (level <= 0.0 && ceiling <= 0.0) continue

            // THE FIRING PRICE IS HELD TO A STRICTER CLOCK THAN THE PEAK. In tick
            // mode the mark must be minutes old, not merely inside the general
            // grace window — see [ManualTrailingStop.TICK_MAX_AGE_MS]. Zero means
            // the feed has stopped, and BOTH levels then hold rather than acting on
            // a price that is no longer an answer. Silence is not a low price, and
            // it is not a high one either.
            val tick = if (src == ManualWatchStore.TrailSource.TICK)
                try { MarkToMarket.freshPrice(sym, maxAgeMs = ManualTrailingStop.TICK_MAX_AGE_MS) }
                catch (ex: Exception) { 0.0 }
            else live
            if (src == ManualWatchStore.TrailSource.TICK && tick <= 0.0 && live > 0.0)
                Log.w(TAG, "[$base] $sym hand-set exits are on live ticks but the feed is " +
                    "quiet — holding rather than acting on a stale price")
            val trigger = ManualTrailingStop.triggerPrice(src, tick, lastClose)
            // ONE DECISION, TAKEN IN ONE PLACE. Both levels can be through at the
            // same moment — the floor follows the high, the ceiling does not — and
            // which of them gets written into the book as the reason is decided by
            // [ManualTakeProfit.decide] rather than by the order of two ifs here.
            val verdict = ManualTakeProfit.decide(trigger, level, ceiling)

            if (base == "in") {
                // MOVE THE STANDING INSTRUCTION, DO NOT PLACE A SECOND ONE. Each
                // leg of the trigger already resting on the holding is pulled
                // toward the user's level, and only ever tighter: the floor up,
                // the ceiling down. They then work with the phone in a drawer.
                if (level > 0.0) {
                    try {
                        ZerodhaLiveMirror.raiseProtectiveFloor(ctx, ns, sym, level, restingTriggers)
                    } catch (ex: Exception) { Log.w(TAG, "[$base] $sym trail floor failed", ex) }
                }
                if (ceiling > 0.0 && verdict != ManualTakeProfit.Verdict.TARGET) {
                    try {
                        ZerodhaLiveMirror.lowerProfitCeiling(ctx, ns, sym, ceiling, restingTriggers)
                    } catch (ex: Exception) { Log.w(TAG, "[$base] $sym target ceiling failed", ex) }
                }
                // THE ONE CASE A TRIGGER CANNOT HOLD. Zerodha will not rest a sell
                // target at or below the market, so a target the price has ALREADY
                // PASSED has nowhere to be parked — and doing nothing would leave
                // the setting looking broken in exactly the situation a person
                // notices it most: switching a target on after a position has
                // already run past it. Germany would sell here, so India does too.
                if (verdict == ManualTakeProfit.Verdict.TARGET) {
                    // `cur`, NOT `e`. The high-water mark may have been written
                    // moments ago and the original row no longer holds it — closing
                    // from the stale copy would put the old mark back under the same
                    // key, on precisely the rows anyone reviews afterwards.
                    sellIndianPositionNow(ctx, base, ns, cur, trigger,
                        "take profit +${"%.2f".format(tpPct)}% from a fill of " +
                            "${"%.2f".format(e.filledPrice)}")
                }
                continue
            }

            if (verdict == ManualTakeProfit.Verdict.HOLD) continue
            val hitTarget = (verdict == ManualTakeProfit.Verdict.TARGET)

            val why = if (hitTarget)
                "take profit +${"%.2f".format(tpPct)}% from a fill of ${"%.2f".format(e.filledPrice)}"
            else
                "trailing stop -${"%.2f".format(pct)}% from a high of ${"%.2f".format(peak)}"
            Log.w(TAG, "[$base] $sym ${if (hitTarget) "TAKE PROFIT" else "TRAILING STOP"} hit: " +
                "${"%.2f".format(trigger)} ${if (hitTarget) "reached" else "through"} " +
                "${"%.2f".format(if (hitTarget) ceiling else level)} ($why)")
            val sold = try {
                T212LiveMirror.stopOutNow(ctx, ns, sym, why,
                    origin = if (hitTarget) "your own take profit on stock you bought yourself"
                             else "your own trailing stop on stock you bought yourself",
                    decisionPrice = if (hitTarget) ceiling else level).ok
            } catch (ex: Exception) {
                Log.e(TAG, "[$base] $sym hand-set sell threw", ex); false
            }
            // Not sold means still held, and it is tried again next pass. The book
            // is deliberately NOT marked closed on a failed sell — a row claiming a
            // position is gone while the shares are still there is the one state
            // worth more than a missed sale.
            if (sold) {
                ManualWatchStore.upsert(ctx, base, cur.copy(
                    status = "CLOSED", exitTs = System.currentTimeMillis() / 1000,
                    closedAt = System.currentTimeMillis(), exitQty = e.filledQty,
                    exitReason = why))
            }
        }
    }

    /**
     * DID A SELL ORDER ACTUALLY REACH THE BROKER?
     *
     * POSITIVE EVIDENCE ONLY. Both Indian sell paths used to answer this with
     * "the row is no longer OPEN", which is not the same question and quietly
     * answers yes to several situations where nothing was sold at all:
     *
     *   • the row is MISSING — `null != "OPEN"` is true, so a position with no
     *     live-order record read as a completed sale
     *   • the row is MANUAL_CLOSED — retired by the broker reconcile because the
     *     name was absent from the holdings list, which for an Indian delivery
     *     buy is simply what today looks like
     *   • the row is ERROR — the previous attempt failed, which is the opposite
     *     of a sale
     *   • the row is EXITING — an order was attempted and did not complete
     *
     * Every one of those ended with the book marked CLOSED and, on the Indian
     * side, a notification announcing a sale. A sale is confirmed by a sell
     * order id and nothing else.
     */
    private fun soldAtBroker(r: com.signalscope.app.data.ZerodhaLiveStore.Row?): Boolean =
        r != null && r.status == "CLOSED" && (r.sellOrderId?.isNotBlank() == true)

    /**
     * SELL AN INDIAN WATCHER POSITION NOW, through the broker.
     *
     * The same route the adopted exits take, and deliberately not a second copy
     * of it: the order that matters is the resting trigger being DELETED BEFORE
     * anything is sold. A standing instruction and a live sell order against one
     * holding is how the same shares get sold twice — and if the delete fails,
     * that route aborts rather than selling, leaving the trigger to protect the
     * position. None of that is worth re-deriving here.
     *
     * Used only where a trigger cannot carry the instruction at all, which for a
     * target means the market has already passed it.
     *
     * NO EXIT PRICE IS WRITTEN INTO THE BOOK, and the blank is the right value.
     * The price that DECIDED the sale is not the price it achieved, and this
     * field is documented to hold a real per-share fill or nothing precisely so
     * that a number the market never paid can never be read back as money.
     */
    private fun sellIndianPositionNow(ctx: Context, base: String, ns: String,
                                      e: ManualWatchStore.Entry,
                                      decidedAt: Double, why: String) {
        val sym = e.symbol.uppercase()
        val tradeKey = "$sym-${e.signalBarTs}"
        Log.w(TAG, "[$base] $sym TAKE PROFIT hit: ${"%.2f".format(decidedAt)} — already past " +
            "the level, so it cannot rest at the broker ($why)")
        val sold = try {
            ZerodhaLiveMirror.mirrorExit(ctx, tradeKey, sym, decidedAt, ns)
            // Zerodha's exit reports through its own row rather than returning;
            // a row that is no longer open is the confirmation. A row that is
            // MISSING is not — that is a position with no live-order record, which
            // the backfill repairs on a later pass.
            val r = com.signalscope.app.data.ZerodhaLiveStore.forTrade(ctx, ns, tradeKey)
            if (r == null) Log.w(TAG, "[$base] $sym has no live-order row to sell through yet")
            soldAtBroker(r)
        } catch (ex: Exception) {
            Log.e(TAG, "[$base] $sym take-profit sell threw", ex); false
        }
        if (!sold) return                          // still held, and tried again next pass

        ManualWatchStore.upsert(ctx, base, e.copy(
            status = "CLOSED", exitTs = System.currentTimeMillis() / 1000,
            exitPrice = 0.0, exitQty = e.filledQty, exitReason = why,
            closedAt = System.currentTimeMillis()))
        // INDIA IS NOTIFIED FROM HERE because nothing else would — its sell path
        // announces nothing. Germany's does, which is why the equivalent line does
        // not exist on that side: two cards for one sale collide in the tray and
        // the worse number wins.
        try {
            LabNotifier.t212Sell(ctx, ns, sym, e.filledQty, "INR", why,
                tradeKey = tradeKey,
                origin = "your own take profit on stock you bought yourself",
                paid = e.filledPrice, realExit = 0.0, broker = "Zerodha")
        } catch (ex: Exception) { Log.w(TAG, "[$base] $sym sell notify failed", ex) }
    }

    /**
     * Manage the exit of manual positions the ENGINE is not holding.
     *
     * These are the rows that used to carry "no exit manager" and mean it: bought
     * by hand, real money at risk, and the replay flat because it never chose the
     * trade. Until now the only thing standing behind them was the hard stop —
     * protection without a plan for getting out at a profit, which is how a good
     * idea becomes a position held for months.
     *
     * The rules applied here are the SAME functions the replay runs, asked about
     * YOUR entry price and YOUR entry time. Not a second copy of the exit logic:
     * a copy would drift, and hand-bought stock would end up exiting on different
     * terms than the identical stock an algo bought.
     *
     * Positions the engine DOES hold are left alone — they already have a manager,
     * and two managers on one position is worse than one.
     */
    private fun manageAdoptedPositions(ctx: Context, base: String) {
        val ns = ManualWatchStore.nsFor(base)
        val st = ManualWatchStore.load(ctx, base)
        val open = st.entries.filter { it.status == "OPEN" && it.filledQty > 0.0 && it.filledPrice > 0.0 }
        if (open.isEmpty()) return
        val engineHolds = try {
            StrategyLabStore.load(ctx, ns).trades
                .filter { it.status == "OPEN" }.map { it.symbol.uppercase() }.toHashSet()
        } catch (e: Exception) { return }

        for (e in open) {
            val sym = e.symbol.uppercase()
            if (sym in engineHolds) continue          // already managed by the replay
            // Anchor on what YOU paid and when the position actually began. For a
            // forced buy there is no signal bar to measure from, and for a signalled
            // one that drifted this is still the honest anchor: the stop the user is
            // shown is the one measured from their cost.
            val anchorTs = if (e.signalBarTs > 0L) e.signalBarTs else (e.createdAt / 1000L)
            val v = StrategyLabRunner.adoptedExitCheck(ctx, ns, sym,
                Spaces.exchangeOf(base), e.filledPrice, anchorTs)

            // RECORD WHETHER THIS POSITION IS ACTUALLY BEING MANAGED, so the panel
            // can stop asserting that it is. A refusal here means the rules could
            // not be applied at all — the position then has the hard stop and
            // nothing else, which the user needs to see rather than infer.
            val why = if (v.ok) "" else (v.note.ifBlank { "the exit rules could not be evaluated" })
            ManualWatchStore.mutate(ctx, base) { s ->
                if (why.isBlank()) s.copy(adoptedNote = s.adoptedNote - sym)
                else s.copy(adoptedNote = s.adoptedNote + (sym to why))
            }

            if (!v.ok || !v.fires) continue
            // SECOND LOCK ON THE SAME DOOR. The engine already refuses to return a
            // verdict when the entry bar is outside the window, but this is the
            // line that spends real money, so it does not take that on trust. A
            // sell placed under a rule set the config never specified is worse
            // than no sell at all, because it arrives wearing the name of one.
            if (!v.entryMacdKnown) {
                Log.w(TAG, "[$base] $sym adopted exit SUPPRESSED — ${v.note}")
                continue
            }

            Log.w(TAG, "[$base] $sym adopted exit fires: ${v.reason} " +
                "(bar ${v.barTs}, ${"%.2f".format(v.price)})")

            // ── THE RV GATE, for the strategy's own exit on Germany and the US ──
            // Low in today's range and rising: hold the sale (at most two hours),
            // exactly as an algo's sale is held. Stops never wait, and India is
            // not gated — its exits rest at Zerodha as triggers.
            if (base != "in" && !v.reason.contains("stop", ignoreCase = true) &&
                MarketHours.isRegularSession(base, System.currentTimeMillis())) {
                val lr = try {
                    com.signalscope.app.data.T212LiveStore.load(ctx).trades.firstOrNull {
                        it.ns == ns && it.symbol.equals(sym, true) && it.status == "OPEN" && it.qty > 0.0
                    }
                } catch (ex: Exception) { null }
                if (lr != null) {
                    val since = if (lr.rvSince > 0L) lr.rvSince else System.currentTimeMillis()
                    val (gate, rd) = try {
                        RvGate.check(com.signalscope.app.data.ConfigManager(ctx), lr.t212Ticker, sym, ns,
                            RvGate.Side.SELL, since)
                    } catch (ex: Exception) { RvGate.Decision(true, "gate failed: ${ex.message}") to null }
                    RvJournal.look(ctx, RvJournal.algoKey(ns, lr.paperTradeId, RvGate.Side.SELL),
                        RvJournal.base("watch", RvGate.Side.SELL, ns, sym, lr.t212Ticker,
                            mapOf("qty" to lr.qty, "entry" to lr.liveEntryPrice.takeIf { it > 0.0 },
                                "reason" to v.reason)),
                        firstLook = lr.rvSince == 0L, since = since, d = gate, rd = rd)
                    if (!gate.go) {
                        com.signalscope.app.data.T212LiveStore.upsert(ctx, lr.copy(rvSince = since,
                            rvNote = RvGate.snapshot(rd, gate),
                            error = "holding the sale: ${RvGate.NOTE} — ${gate.why}; sells by itself within 2 hours"))
                        // Looked at again by the next pass — this exit is decided
                        // here, not by the minute check's waiting-sale sweep.
                        Log.i(TAG, "[$base] $sym strategy exit held by the RV gate: ${gate.why}")
                        continue
                    }
                    if (gate.timedOut) try { LabNotifier.rvGateTimedOut(ctx, ns, sym, buy = false) } catch (_: Exception) {}
                }
            }

            // Per-share exit AS THE BROKER REPORTS IT, or 0 while it has not. Never
            // `v.price`: that is the candle the rule fired on, which is a statement
            // about the strategy and not about the account.
            var realExit = 0.0
            val sold = try {
                if (base == "in") {
                    ZerodhaLiveMirror.mirrorExit(ctx, "$sym-${e.signalBarTs}", sym, v.price, ns)
                    // Zerodha's exit reports through its own row rather than returning;
                    // treat a row that is no longer open as the confirmation.
                    val r = com.signalscope.app.data.ZerodhaLiveStore
                        .forTrade(ctx, ns, "$sym-${e.signalBarTs}")
                    realExit = r?.exitPrice ?: 0.0
                    if (!soldAtBroker(r))
                        Log.w(TAG, "[$base] $sym exit did NOT reach the broker " +
                            "(row ${r?.status ?: "missing"}) — the book stays open")
                    soldAtBroker(r)
                } else {
                    // ORIGIN, IN THE WORDS A READER WOULD USE. This namespace has no
                    // BucketStore row — it is not an algo — so an alert about it used
                    // to be headed with the raw namespace uppercased, "DE__MANUAL",
                    // which is indistinguishable from an algo's name. The single most
                    // important thing these alerts convey is whether a strategy acted
                    // by itself or something managed stock the user chose to buy.
                    T212LiveMirror.stopOutNow(ctx, ns, sym, v.reason,
                        origin = "exit minder for stock you bought yourself").ok
                }
            } catch (ex: Exception) {
                Log.e(TAG, "[$base] $sym adopted exit sell threw", ex); false
            }
            if (!sold) continue                        // still held; retried next pass

            // NO EXIT PRICE IS RECORDED HERE, and the blank is the correct value.
            //
            // `v.price` is the strategy's candle close — the price the RULE fired
            // at. The field it was being written into is documented to hold the
            // real per-share fill or nothing, so that a paper number can never be
            // read as money. Writing the candle there put the two beyond telling
            // apart, and the gap is not academic: this exit fired on a Friday
            // close, could not be sold until the market reopened on Monday, and
            // filled 1.40 higher. The book recorded +0.80% for a trade that made
            // +3.29%, with nothing to indicate it was an estimate.
            //
            // The real figure lands minutes later, when the broker reports the
            // fill, and [ManualWatchStore.recordRealExit] writes it then. Until it
            // does the row reads as unpriced, which is what it is.
            ManualWatchStore.upsert(ctx, base, e.copy(
                status = "CLOSED", exitTs = v.barTs, exitPrice = realExit,
                exitQty = e.filledQty, exitReason = v.reason,
                closedAt = System.currentTimeMillis()))
            // GERMANY AND THE US ARE NOT NOTIFIED HERE. Their sell path sends it —
            // one alert per sale, from the only place that ever learns the fill
            // price. This call site used to send a SECOND alert within the same
            // second, and because the ids were bucketed by wall-clock minute the
            // two collided: the estimate built from the candle silently replaced
            // the sell path's card in the tray, so the worse of the two numbers
            // was the one always read.
            //
            // INDIA STILL IS, because nothing else would. Zerodha's exit path
            // notifies nowhere, so this is that market's only word that a real
            // position was sold, and dropping it to tidy the German duplicate
            // would have traded one bug for a quieter one.
            if (base == "in") {
                try {
                    LabNotifier.t212Sell(ctx, ns, sym, e.filledQty, "INR", v.reason,
                        tradeKey = "$sym-${e.signalBarTs}",
                        origin = "exit minder for stock you bought yourself",
                        paid = e.filledPrice, realExit = realExit, broker = "Zerodha")
                } catch (ex: Exception) { Log.w(TAG, "[$base] $sym sell notify failed", ex) }
            }
        }
    }

    /** A position bought this recently is not judged missing. Brokers take a little
     *  while to show a new holding, and closing a just-bought row because the
     *  portfolio has not caught up would be the worst possible false positive. */
    private const val SETTLE_GRACE_MS = 20 * 60_000L

    /**
     * INDIA NEEDS FAR LONGER BEFORE ABSENCE MEANS ANYTHING.
     *
     * A delivery buy does not reach the holdings list until it settles overnight;
     * on the day itself it lives only in the positions list, and that list has
     * already been observed to lag a fresh fill. Twenty minutes of grace against
     * a settlement cycle measured in days is not a margin, it is a coin flip —
     * and it landed wrong twice, retiring SYRMA twenty-five minutes after it was
     * bought and LXCHEM after thirty-one.
     *
     * A whole day is deliberately generous. The cost of waiting is a dead row
     * sitting in the book for an afternoon; the cost of not waiting is a real
     * position whose record has been retired, which is a real position with
     * nothing managing its exit.
     */
    private const val IN_SETTLE_GRACE_MS = 26 * 60 * 60_000L

    /**
     * How long a holding must stay missing before absence is believed.
     *
     * Longer than one watch pass on purpose, so the second sighting is a genuinely
     * separate read of the broker rather than the same answer inspected twice.
     */
    private const val ABSENT_CONFIRM_MS = 25 * 60_000L

    /**
     * Close manual positions the broker says you no longer hold.
     *
     * THE HOLE THIS FILLS: the other close path keys on the strategy's own ledger,
     * so it can only retire a trade the ENGINE opened. A forced buy has no ledger
     * trade at all — the strategy never chose it — so nothing could ever mark one
     * finished. Sell it by hand in the broker app and the row stayed OPEN for
     * good: still claiming to be managed, still blocking the name from ever being
     * offered again, still counted as exposure.
     *
     * A DELIBERATE CHANGE OF POLICY. The live-order ledger notes that a missing
     * share is never inferred, because it has several possible causes and only the
     * user can say which. That was right when the only evidence was an absence.
     * Here the evidence is a SUCCESSFUL reading of the whole portfolio in which the
     * name does not appear — which is a positive statement that it is gone, not a
     * failure to find it.
     *
     * THREE GUARDS, because closing a live position by mistake is far worse than
     * leaving a dead one open:
     *   • only on a call that definitively SUCCEEDED — never on an error, a missing
     *     login, or an exception, all of which return "no holdings" and would
     *     otherwise read as "you sold everything"
     *   • never within [SETTLE_GRACE_MS] of the buy, so a holding the broker has
     *     not published yet is not mistaken for one that has gone
     *   • the price is NOT guessed. We did not execute this exit, so no exit price
     *     is claimed and the card says the sale was not priced rather than showing
     *     a number nobody paid.
     */
    private fun reconcileAgainstBroker(ctx: Context, base: String) {
        val st = ManualWatchStore.load(ctx, base)
        val now = System.currentTimeMillis()
        val open = st.entries.filter {
            // See [IN_SETTLE_GRACE_MS]: an Indian delivery buy is invisible to the
            // holdings list until it settles overnight, so absence says nothing
            // about it for far longer than it does in any other market.
            it.status == "OPEN" && it.filledQty > 0.0 &&
                (now - it.createdAt) > (if (base == "in") IN_SETTLE_GRACE_MS else SETTLE_GRACE_MS)
        }
        if (open.isEmpty()) return

        val cfg = ConfigManager(ctx)
        // How many shares the account holds of each name (Trading 212 only).
        var heldQty: Map<String, Double> = emptyMap()
        // held == null means WE DO NOT KNOW. Distinct from an empty set, which means
        // the account genuinely holds nothing. Collapsing the two is how a rate-limit
        // reply turns into closing every position you own.
        // Through the shared reader: one call per broker per tick, and a result
        // that can say it could not read rather than pretending you hold nothing.
        //
        // Deliberately the WHOLE account for Trading 212, not this space's slice.
        // A German row must not be closed because the US filter hid it — the
        // question here is "does the account still have these shares", and the
        // venue is irrelevant to that.
        val held: Set<String>? = when (base) {
            // INDIA COUNTS TODAY'S POSITIONS AS HELD, and must.
            //
            // On NSE a stock bought today is not in holdings — it settles on T+1
            // and until then it exists only in positions. Reading holdings alone
            // therefore answered "no, you don't own it" for every share bought
            // today, and this sweep dutifully wrote it off: a SYRMA position
            // bought by hand at 09:10 was marked "closed outside the app" at
            // 09:36, twenty-five minutes later, with the shares sitting in the
            // account the whole time. The book then stopped managing its exit.
            //
            // A null from the positions call is NOT an empty day: it means the
            // question went unanswered, and the union below must not shrink
            // because of it — so a failure there leaves the holdings answer
            // alone rather than narrowing it.
            "in" -> {
                val settled = (BrokerHoldings.zerodha(ctx) as? BrokerHoldings.Result.Ok)
                    ?.holdings?.map { it.symbol.uppercase() }?.toHashSet()
                val today = try {
                    com.signalscope.app.network.ZerodhaClient(cfg).fetchTodayPositionSymbols()
                } catch (e: Exception) {
                    Log.w(TAG, "[in] today's positions read threw — ${e.message}"); null
                }
                // BOTH OR NEITHER. Half the picture is worse than none here,
                // because the half that is missing is exactly the half that
                // contains everything bought today — so acting on holdings alone
                // does not merely risk a wrong answer, it reliably gives one.
                if (settled == null || today == null) {
                    if (settled != null)
                        Log.w(TAG, "[in] holdings read but today's positions did not — " +
                            "skipping the sweep rather than closing today's buys")
                    null
                } else settled.apply { addAll(today) }
            }
            // THE BROKER'S OWN CODES COUNT AS NAMES TOO. A row records the exact
            // instrument it bought, and that is the one identity that cannot be
            // misspelled: matching on the derived symbol alone closed a Booking
            // position bought as BKNG because the account listed it as PCLN.
            "de", "us" -> (BrokerHoldings.t212(ctx) as? BrokerHoldings.Result.Ok)
                ?.holdings?.also { hs ->
                    heldQty = hs.flatMap { listOf(it.symbol.uppercase() to it.qty, it.ticker.uppercase() to it.qty) }
                        .filter { it.first.isNotBlank() }.toMap()
                }
                ?.flatMap { listOf(it.symbol.uppercase(), it.ticker.uppercase()) }
                ?.filter { it.isNotBlank() }?.toHashSet()
            else -> null
        }
        if (held == null) {
            // SAY SO. A sweep that declines to act and says nothing is
            // indistinguishable from one that never ran, which makes it impossible
            // to tell a working guard from a broken feature.
            Log.i(TAG, "[$base] broker reconcile skipped — holdings unreadable " +
                "(not connected, rate limited, or the call failed); ${open.size} open row(s) left alone")
            return
        }
        Log.i(TAG, "[$base] broker reconcile: broker holds ${held.size} name(s), " +
            "checking ${open.size} open manual row(s)")

        val ns = ManualWatchStore.nsFor(base)
        for (e in open) {
            val sym = e.symbol.uppercase()
            val code: String? = e.t212Ticker   // may be null in rows read back from disk
            // A SCRAP IS NOT THE POSITION. HAG on 16 Oct: the watchlist's 3.683
            // shares were sold at the open by a sell order for the whole holding,
            // and a €1 buy minutes later left 0.0118 of a share — enough to keep
            // "HAG" in the account, so the row stayed open for a position that was
            // gone. Under a tenth of what the row holds counts as sold.
            val bq = heldQty[sym] ?: code?.let { heldQty[it.uppercase()] }
            val onlyScrap = bq != null && e.filledQty > 0.0 && bq < e.filledQty * 0.1
            if (!onlyScrap && (sym in held || (!code.isNullOrBlank() && code.uppercase() in held))) {
                // BACK IN THE LIST — forget that we ever missed it, so a name that
                // flickers once never accumulates its way to being retired.
                if (e.absentAt != 0L)
                    ManualWatchStore.upsert(ctx, base, e.copy(absentAt = 0L))
                continue
            }

            // ── ABSENCE HAS TO PERSIST ──────────────────────────────────────
            // See [ManualWatchStore.Entry.absentAt]. The first time a holding
            // goes missing is a question, not an answer; only the second reading
            // — a separate call, a separate pass — is evidence.
            if (e.absentAt == 0L) {
                ManualWatchStore.upsert(ctx, base, e.copy(absentAt = now))
                Log.w(TAG, "[$base] $sym is OPEN in the book but the broker did not " +
                    "list it — waiting for a second reading before believing that")
                continue
            }
            if (now - e.absentAt < ABSENT_CONFIRM_MS) {
                Log.i(TAG, "[$base] $sym still missing, but not for long enough yet")
                continue
            }

            Log.w(TAG, "[$base] $sym missing from the broker on two separate reads " +
                "${(now - e.absentAt) / 60_000L} minutes apart — closing as sold outside the app")

            // Retire the live-order row too. A row still claiming shares that are
            // gone is not merely untidy: the exit paths find positions through it,
            // and one of them could try to sell stock the account no longer has.
            try {
                val id = "$sym-${e.signalBarTs}"
                if (base == "in") {
                    com.signalscope.app.data.ZerodhaLiveStore.forTrade(ctx, ns, id)?.let { row ->
                        if (row.status == "OPEN" || row.status == "PENDING")
                            com.signalscope.app.data.ZerodhaLiveStore.upsert(ctx,
                                row.copy(status = "MANUAL_CLOSED", gttId = null,
                                    manualClosedAt = now,
                                    error = "not held at the broker — closed outside the app"))
                    }
                } else {
                    com.signalscope.app.data.T212LiveStore.get(ctx, id, ns)?.let { row ->
                        if (row.status == "OPEN" || row.status == "PENDING")
                            com.signalscope.app.data.T212LiveStore.upsert(ctx,
                                row.copy(status = "MANUAL_CLOSED",
                                    error = "not held at the broker — closed outside the app"))
                    }
                }
            } catch (ex: Exception) {
                Log.w(TAG, "[$base] $sym live-row retire failed", ex)
            }

            // WHAT IT SOLD FOR, when the broker's record says — the sale orders on
            // this stock since the position was bought.
            val soldPx = if (base == "in" || code.isNullOrBlank()) 0.0 else try {
                val fills = (com.signalscope.app.network.Trading212Client(cfg).fetchOrderHistory(50)
                    as? com.signalscope.app.network.Trading212Client.T212Result.Success)?.data.orEmpty()
                    .filter { f -> f.ticker.equals(code, true) && f.side.equals("SELL", true) &&
                        f.filledQuantity > 0.0 && f.fillPrice > 0.0 &&
                        (try { java.time.Instant.parse(f.dateExecuted).toEpochMilli() }
                         catch (x: Exception) { now }) >= e.createdAt }
                val q = fills.sumOf { it.filledQuantity }
                if (q > 0.0) fills.sumOf { it.fillPrice * it.filledQuantity } / q else 0.0
            } catch (ex: Exception) { 0.0 }
            ManualWatchStore.upsert(ctx, base, e.copy(
                status = "CLOSED", exitTs = now / 1000L,
                exitPrice = soldPx, exitQty = e.filledQty,
                exitReason = if (soldPx > 0.0) "sold outside the watchlist at ${"%.2f".format(soldPx)}"
                             else "sold outside the app — not held at the broker",
                closedAt = now))
            ManualWatchStore.mutate(ctx, base) { s -> s.copy(adoptedNote = s.adoptedNote - sym) }
        }
    }

    fun reconcileFills(ctx: Context, space: String): Int {
        val base = Spaces.baseOf(space.lowercase())
        val st = ManualWatchStore.load(ctx, base)
        val pending = st.entries.filter {
            // NOT "only if blank". A price already stored can be WRONG — every row
            // written before the currency fix holds a euro-derived per-share figure
            // labelled as dollars, and a repair that only fills blanks leaves those
            // corrupt forever while looking like it ran. The history call is one
            // request regardless of how many rows use it, so re-deriving every open
            // row costs nothing and makes the fix retroactive.
            it.status == "OPEN" && it.orderIds.isNotEmpty()
        }
        if (pending.isEmpty()) return 0
        val config = ConfigManager(ctx)
        if (!config.hasT212Credentials) return 0
        val fills = when (val r = Trading212Client(config).fetchOrderHistory(50)) {
            is Trading212Client.T212Result.Success -> r.data
            else -> return 0
        }
        if (fills.isEmpty()) return 0
        var fixed = 0
        for (e in pending) {
            val ids = e.orderIds.toHashSet()
            val mine = fills.filter { it.orderId in ids && it.side.equals("BUY", true) && it.fillPrice > 0.0 }
            if (mine.isEmpty()) continue
            val qty = mine.sumOf { it.filledQuantity }
            if (qty <= 0.0) continue
            // INSTRUMENT-currency only. filledValue is the cash that left the
            // ACCOUNT, so on a cross-currency trade (a euro account buying US
            // stocks) dividing it by a share count gives a per-share price in the
            // wrong currency — about 14% low, and it is this number the real stop
            // is measured from.
            val value = mine.sumOf { it.fillPrice * it.filledQuantity }
            val avg = if (value > 0.0) value / qty else mine.first().fillPrice
            // What the ladder ASKED, weighted the same way, so asked-vs-got is a
            // like-for-like comparison. Rungs with no recorded limit are excluded
            // rather than counted as zero — a missing limit would otherwise drag
            // the average down and manufacture price improvement that never
            // happened.
            val withLimit = mine.filter { it.limitPrice > 0.0 }
            val askQty = withLimit.sumOf { it.filledQuantity }
            val asked = if (askQty > 0.0)
                withLimit.sumOf { it.limitPrice * it.filledQuantity } / askQty else 0.0
            // Only write when something actually changed, so a re-derivation that
            // agrees with what is stored does not churn the file every pass.
            val same = kotlin.math.abs(e.filledPrice - avg) < 1e-9 &&
                       kotlin.math.abs(e.askedPrice - asked) < 1e-9 &&
                       kotlin.math.abs(e.filledQty - qty) < 1e-9
            if (same) continue
            if (e.filledPrice > 0.0 && kotlin.math.abs(e.filledPrice - avg) > 1e-9)
                Log.w(TAG, "[$base] ${e.symbol} fill price CORRECTED " +
                    "${"%.4f".format(e.filledPrice)} → ${"%.4f".format(avg)}")
            ManualWatchStore.upsert(ctx, base,
                e.copy(filledPrice = avg, filledQty = qty, askedPrice = asked))
            fixed++
            Log.i(TAG, "[$base] ${e.symbol} real fill ${"%.4f".format(avg)} over ${mine.size} order(s)")
        }
        return fixed
    }
}

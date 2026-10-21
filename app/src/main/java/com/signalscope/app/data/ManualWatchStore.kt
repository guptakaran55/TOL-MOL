package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.signalscope.app.trading.Spaces
import java.io.File

// ════════════════════════════════════════════════════════════════════════════
// MANUAL ENTRY WATCHER — per-space state
// ════════════════════════════════════════════════════════════════════════════
// Two things live here, and nothing else:
//
//   1. ALERT DEDUP. Which symbol was last alerted on which UTC day. The watcher
//      evaluates its candidates every tick, so without this a name whose signal
//      stays open would re-notify on every pass — the single fastest way to make
//      the user silence the channel and lose the feature.
//
//   2. THE MANUAL ENTRIES themselves. What was bought, at what the ENGINE called
//      the entry (which is what its exit measures from) and at what the ladder
//      actually filled. Both are kept: they are different numbers and conflating
//      them is how a stop ends up computed against a price nobody paid.
//
// The watcher's CONFIG is deliberately NOT here. It lives on the reserved
// namespace ([nsFor]) through the normal StrategyLabStore config path, so the
// existing engine, feed planner, runner and review chart all read it the same
// way they read any algo's — and uploading a new one resets that namespace's
// book exactly as it does for an algo, because changing the strategy invalidates
// the ledger it produced.
//
// WHY A RESERVED NAMESPACE AND NOT A REAL BUCKET: a bucket can be armed for live
// money, and an armed bucket's runner mirrors its OWN entries to the broker.
// Manual entries are already bought by the user's tap, so a mirror behind them
// would place a second real order for the same position. Keeping this namespace
// out of BucketStore means the live mirror can never find a config for it and
// always declines — the user's tap stays the only thing that can buy.
// ════════════════════════════════════════════════════════════════════════════

object ManualWatchStore {

    private const val TAG = "ManualWatchStore"
    private val gson = Gson()

    /** Bucket-shaped id for the reserved namespace ("de__manual"). Filesystem-safe
     *  via the same separator every ns-keyed store already uses. */
    const val ID = "manual"

    fun nsFor(space: String): String = Spaces.bucketNs(Spaces.baseOf(space.lowercase()), ID)

    data class Entry(
        val symbol: String = "",
        val space: String = "",
        /** The ENGINE bar the entry fired on. This is the anchor the hand-over
         *  back-dates the baseline to, so the replay adopts its own entry. */
        val signalBarTs: Long = 0L,
        /** The engine's entry price — that bar's close. Every exit rule in the
         *  config measures from THIS, not from what the ladder paid. */
        val signalPrice: Double = 0.0,
        val requestedCash: Double = 0.0,
        val filledQty: Double = 0.0,
        val filledPrice: Double = 0.0,
        /**
         * The highest price this position has reached since it was opened, and
         * when. Written by the watcher tick; the trail measures down from it.
         *
         * NEVER FALLS. A high-water mark that could retreat would let a stop drift
         * back down and stop being a ratchet, which is the only property that
         * makes a trailing stop worth having.
         */
        val peakPrice: Double = 0.0,
        val peakAt: Long = 0L,
        /**
         * The trailing floor last worked out, and the setting it was worked out
         * under ("ATR:1.5"). A floor measured in the stock's daily range would
         * slip down on a day the range widened; this keeps it rising only, until
         * the setting itself changes.
         */
        val trailLevel: Double = 0.0,
        val trailBasis: String = "",
        /**
         * The limit the ladder actually rested at, quantity-weighted across its
         * rungs. Paired with [filledPrice] this is EXECUTION quality — did the
         * order get the price it asked for, or better?
         *
         * Deliberately separate from the signal-vs-fill drift. That one measures
         * how far the market moved between the strategy deciding and you acting,
         * and shrinks by acting faster. This measures how well the ladder itself
         * did against its own ask, and shrinks by changing the ladder. Same units,
         * different causes, different fixes — and blending them into one "slippage"
         * figure hides which of the two is actually costing money.
         */
        val askedPrice: Double = 0.0,
        val orderIds: List<Long> = emptyList(),
        val t212Ticker: String = "",
        val createdAt: Long = 0L,
        /** PENDING (ladder running) · OPEN (filled, handed to the engine) ·
         *  FAILED (nothing filled) · CLOSED (engine exited it). */
        val status: String = "PENDING",
        val note: String = "",
        // ── HOW IT ENDED ──────────────────────────────────────────────────────
        // CLOSED was described above from the first version of this file and NEVER
        // WRITTEN by anything: a sold position stayed OPEN for good. Two things
        // followed from that, and neither looked like a bookkeeping bug:
        //
        //   1. the book showed a permanent red "no exit manager" on a position
        //      that had already been sold — the strategy no longer held it,
        //      because it had just closed it, which is precisely that warning's
        //      trigger. It is the only loud signal for a genuinely unguarded
        //      position, and firing it on every completed trade spends it.
        //   2. the name stayed in the exposure set for ever, so the watcher could
        //      never offer it again. One trade retired a company permanently.
        //
        // ZERO/NULL DEFAULTS ARE LOAD-BEARING: Gson builds these through Unsafe
        // and never runs a Kotlin default, so every row written before these
        // fields existed arrives with them absent. Numbers come back 0; a String
        // declared non-null comes back null and throws on first read. Hence the
        // nullable reason.
        /**
         * When the broker FIRST failed to list this holding. 0 = it was there
         * last time we looked.
         *
         * ONE READ IS NOT EVIDENCE. Retiring a position because it is missing
         * assumes the holdings list is complete, and a list that comes back
         * short — for any reason, on any broker — then retires a real holding
         * and takes its stop with it. That has now happened in two markets:
         * twice in India within half an hour of the buy, and twice in Germany
         * inside thirty-three minutes.
         *
         * So absence has to persist across passes before it counts. This is the
         * first sighting; the close waits for the second.
         *
         * Cleared the moment the name reappears, so a single bad read costs one
         * tick of delay and leaves nothing behind.
         */
        val absentAt: Long = 0L,
        /** When the broker position ended. 0 while still open. */
        val exitTs: Long = 0L,
        /** REAL per-share exit, in the instrument's own currency — never derived
         *  by dividing account-currency cash by a share count, which is the bug
         *  that once priced a $227 fill at $196. 0 when no real figure is known
         *  yet, in which case the UI falls back to the strategy's exit and says
         *  so, rather than presenting a paper number as money. */
        val exitPrice: Double = 0.0,
        val exitQty: Double = 0.0,
        val exitReason: String? = null,
        val closedAt: Long = 0L,
        /**
         * Bought WITHOUT a live entry signal — your conviction, not the strategy's.
         *
         * Kept as a fact about the trade rather than inferred later, because the
         * two kinds of position are judged differently and the difference has to
         * survive: a signalled entry is anchored where the strategy would have
         * entered, a forced one is anchored where YOU bought. Losing that would
         * make a forced trade look like evidence for or against a strategy that
         * never chose it, quietly poisoning every comparison built on the book.
         */
        val forced: Boolean = false,
        /**
         * SHARES OF THIS POSITION YOU SOLD YOURSELF, already taken off [filledQty]
         * (see T212HandSales). The fill reader rebuilds [filledQty] from the buy
         * orders every pass and subtracts this, so it cannot hand back shares
         * that are gone.
         */
        val soldQty: Double = 0.0,
        /**
         * A TOP-UP's FROZEN PAST — see ManualEntryWatcher.topUp. The buy orders
         * already read back when more was added, the shares they bought, and their
         * price × quantity (paid and asked). The fill reader adds these and reads
         * only the newer orders, so an old order scrolled out of Trading 212's
         * history cannot shrink the position. Null / 0 when never topped up.
         */
        val settledIds: List<Long>? = null,
        val settledQty: Double = 0.0,
        val settledPriceValue: Double = 0.0,
        val settledAskValue: Double = 0.0,
        val settledAskQty: Double = 0.0,
        /** How many times more was added on the same signal. */
        val topUps: Int = 0,
        /**
         * The resting BUY trigger this entry is waiting on (India only, 0 = none).
         *
         * A trigger is a standing instruction, not a position. While this is set
         * and the status is PENDING, nothing has been bought and no exit rule
         * should be measuring anything — the entry price is not known until it
         * fills, and inventing one would anchor every exit to a price nobody paid.
         */
        val gttId: Long = 0L,
        /** Where that trigger rests, so the row can say what it is waiting for. */
        val triggerPrice: Double = 0.0,
        /**
         * The ORDINARY Zerodha order this entry went in on (India only, null =
         * none). Set when the broker accepted a real order rather than refusing
         * it into the trigger route.
         *
         * Nullable on purpose: Gson builds these rows through Unsafe and never
         * runs a Kotlin default, so every row written before this field existed
         * arrives with it absent — a non-null String would come back null anyway
         * and throw on first read.
         *
         * PAIRED WITH, NOT INSTEAD OF, [gttId]. An entry has one or the other and
         * never both, and which one it has decides how it settles: an ordinary
         * order is normally already filled, a trigger has bought nothing yet.
         */
        val buyOrderId: String? = null
    ) {
        /** What YOUR money did, fill to fill. Null until both ends are real —
         *  a half-known return is worse than an absent one, because it looks
         *  finished. */
        val realPctReturn: Double?
            get() = if (filledPrice > 0.0 && exitPrice > 0.0)
                (exitPrice / filledPrice - 1.0) * 100.0 else null
        /** How far the fill drifted from the price the exit rules measure from.
         *  Positive = paid MORE than the engine thinks it entered at, which
         *  silently tightens every percentage-based stop. */
        val driftPct: Double
            get() = if (signalPrice > 0.0 && filledPrice > 0.0)
                (filledPrice / signalPrice - 1.0) * 100.0 else 0.0
    }

    /** One name from the watcher's OWN daily screen. */
    data class RankRow(
        val symbol: String = "",
        val rank: Int = 0,
        val score: Double = 0.0,
        val slope: Double = 0.0
    )

    /** What the strategy's replay is doing with a candidate — recorded so the list
     *  can distinguish "already long, nothing to announce" from "still waiting".
     *  Both look identical otherwise: neither ever fires an alert. */
    data class Holding(
        val since: Long = 0L,
        val entryPrice: Double = 0.0,
        val pctReturn: Double = 0.0,
        val checkedAt: Long = 0L,
        /**
         * Did this position open on the NEWEST bar?
         *
         * The difference decides what the row means. A position opened on the
         * newest bar IS the alert that just fired — act on it. One opened earlier
         * is a trade already running, which will never alert again until it closes.
         * Both are "holding", and showing them identically made a fresh signal look
         * like a stale hold and vice versa.
         */
        val fresh: Boolean = false
    )

    /**
     * LOCAL SUPPORT AND RESISTANCE, read off the watcher's OWN bars.
     *
     * Levels where this stock has actually turned recently — not a forecast, and
     * not the daily levels the Discovery scan computes. The difference is the
     * timeframe: those answer "where has this company turned over a year", these
     * answer "where has it turned this week", which is the question in front of
     * someone deciding whether to buy a name today and where to put a floor.
     *
     * COSTS NO DOWNLOAD. The bars are already fetched for the entry check and the
     * stop sweep; this is a second pass of pure arithmetic over the same array.
     *
     * A ZERO IS AN ANSWER, not a missing number. No resistance means price is
     * above everything in the window — clear overhead, which is a genuinely
     * different situation from a level that happens to be far away, and worth
     * saying rather than leaving as a blank that reads like a failed fetch.
     *
     * [price] IS KEPT with them because a level is only meaningful against the
     * price it was measured from. Storing the distance instead would freeze a
     * percentage that stops being true the moment the market moves; storing both
     * ends lets the panel recompute honestly against whatever is current.
     */
    data class Levels(
        /**
         * REDUNDANT WITH THE MAP KEY, AND LOAD-BEARING ANYWAY.
         *
         * These are produced through an object-level side channel on the runner,
         * and that channel has more than one caller: a Buy tap runs the same check
         * on its own thread. Without a name to match against, a tap landing between
         * the call and the read would stamp another company's levels onto this row
         * — and unlike most display slips, these are numbers someone sizes an order
         * against.
         */
        val symbol: String = "",
        val support: Double = 0.0,
        val resistance: Double = 0.0,
        /**
         * How many times price actually turned at each level.
         *
         * ZERO IS THE IMPORTANT VALUE: it means no cluster of turning points
         * backed the number and it came from pivot arithmetic off the latest bar
         * instead. That is a guess, and a guess shown with the same confidence as
         * a level tested three times is the kind of false precision that gets a
         * stop placed under nothing at all.
         */
        val supportTouches: Int = 0,
        val resistanceTouches: Int = 0,
        /**
         * THE LAST FEW TRADING DAYS ON THEIR OWN — the swing window.
         *
         * Kept apart from the clustered levels above because they answer
         * different questions. Those say where this stock has turned over
         * weeks; these say where it has been trading THIS WEEK, which is the
         * horizon someone timing an entry off a pullback is actually working
         * on. Blending them would average away the only part that is
         * actionable today.
         *
         * [swingPos] is the number the whole thing exists for: 0 means sitting
         * on the low of its recent range, 1 means pressed against the high.
         * NOT clamped — price trades outside a window measured minutes ago, and
         * that is precisely the moment worth seeing.
         */
        val swingLow: Double = 0.0,
        val swingHigh: Double = 0.0,
        val swingPos: Double = -1.0,
        /** Separate returns to the bottom of the range, not bars spent there. */
        val swingVisits: Int = 0,
        val swingDays: Int = 0,
        /** The same reading over the last WEEK (5 trading days) and MONTH (21):
         *  a 3-day floor says where today's pullback might stop; these say whether
         *  that floor is also the lowest the stock has been in a week or a month,
         *  which is a different and rarer thing. 0 = not enough history. */
        val weekLow: Double = 0.0,
        val weekHigh: Double = 0.0,
        val weekVisits: Int = 0,
        val weekDays: Int = 0,
        val monthLow: Double = 0.0,
        val monthHigh: Double = 0.0,
        val monthVisits: Int = 0,
        val monthDays: Int = 0,
        /** The close these were measured against. */
        val price: Double = 0.0,
        /** Minutes per bar they were read off — 1440 when the watcher's feed is
         *  daily, which is worth showing rather than calling it intraday. */
        val barMin: Int = 0,
        val bars: Int = 0,
        val atMs: Long = 0L
    )

    /**
     * WHERE THE HIGH-WATER MARK COMES FROM.
     *
     * The three answer genuinely different questions and none is simply better,
     * which is why it is a choice rather than a constant.
     *
     *  BAR_CLOSE   — the peak is the highest completed bar CLOSE, and the stop is
     *                judged on closes too. Speaks the same language as every other
     *                exit rule in this app. A spike that fades inside one bar is
     *                never captured and never sells you out.
     *  TICK        — the peak is the highest price actually traded and the stop can
     *                fire mid-bar. Captures the whole move, and also fires on a
     *                one-second wick that recovers immediately.
     *  TICK_PEAK   — the peak rises on ticks, the sell waits for a close below the
     *                level. Keeps most of the move without wick-selling, at the
     *                cost of the two halves running on different clocks.
     */
    enum class TrailSource { BAR_CLOSE, TICK, TICK_PEAK }

    /**
     * WHAT THE TRAIL'S NUMBER IS COUNTED IN. A flat percentage gives a quiet
     * utility and a jumpy chip maker the same room, which is too tight for one or
     * too loose for the other. The other two scale to the stock's own daily
     * range: [ATR] — multiples of the 14-day average true range; [BB] —
     * multiples of half the 20-day Bollinger width, (upper − lower) / 2.
     */
    enum class TrailUnit { PCT, ATR, BB }

    data class State(
        /** SYMBOL -> UTC day (yyyy-MM-dd) it was last alerted on. */
        val alerted: Map<String, String> = emptyMap(),
        /**
         * SYMBOL -> UTC day it was last flagged as sitting near its floor.
         *
         * DELIBERATELY NOT THE SAME MAP as the entry alerts above. The two say
         * different things and a name can legitimately do both on one day —
         * "the strategy would enter here" and "this is trading at the bottom of
         * its recent range" are separate facts, and sharing a dedup would let
         * whichever fired first silence the other for the rest of the day.
         */
        val supportAlerted: Map<String, String> = emptyMap(),
        /** "SYMBOL|1w" / "SYMBOL|1m" -> UTC day the weekly or monthly low was
         *  flagged. Separate from the 3-day map so a deeper low reached later in
         *  the day still gets through. Nullable: older files have no such field. */
        val supportAlertedScales: Map<String, String>? = null,
        /**
         * A PLAIN PERCENTAGE TRAIL FOR HAND-MANAGED POSITIONS, 0 = off.
         *
         * Deliberately separate from the adaptive stop the brokers' own managers
         * run: that one widens and tightens on volatility and age, which is the
         * right behaviour for a strategy and the wrong one for a position the user
         * is watching themselves and wants a predictable floor under.
         *
         * Applies ONLY to this book. Algo positions have their own exits and are
         * never touched by it.
         */
        val trailPct: Double = 0.0,
        /**
         * A PLAIN PERCENTAGE TARGET ABOVE YOUR FILL, 0 = off.
         *
         * The other half of the hand-set pair, and measured from a deliberately
         * different anchor: the trail hangs off the position's HIGH, this hangs
         * off what you PAID. A trail is about giving back as little as possible
         * of a move already made; a target is the return you decided was enough.
         *
         * NOT the same thing as the config's take profit. That one measures from
         * the engine's entry and only acts while the engine is actually holding
         * the position — which for hand-bought stock is frequently never.
         *
         * Shares [trailSource] as its clock, so the two hand-set levels are
         * judged on the same prices. Two separate mode settings would let a
         * position carry a floor on closes and a ceiling on ticks, which is two
         * different pictures of the same bar.
         */
        val takeProfitPct: Double = 0.0,
        val trailSource: TrailSource = TrailSource.BAR_CLOSE,
        /** What [trailPct] counts in. Null on files saved before the choice
         *  existed, which were all percentages. */
        val trailUnit: TrailUnit? = null,
        val entries: List<Entry> = emptyList(),
        val lastRunAt: Long = 0L,
        val lastRunNote: String = "",
        // ── the watcher's own daily screen (§ it no longer borrows algo gates) ──
        /** UTC day the ranking below was computed on; "" = never. */
        val lastEvalDay: String = "",
        /**
         * UTC day the sparkline pass was last ATTEMPTED — not the day it last
         * succeeded.
         *
         * The distinction is the whole point. Guarding on the series file's own day
         * meant a failed write could never satisfy the guard, so the full universe
         * screen re-ran on every tick, for ever. Cached bars make that cheap in
         * bandwidth and not in CPU, and a disk error is not a reason to re-screen a
         * hundred names every quarter of an hour until midnight.
         */
        val lastSparkDay: String = "",
        /**
         * Which day the daily pass ran AND which set of artefacts it produced,
         * as "yyyy-MM-dd#n".
         *
         * The day alone is not enough, and that has now caught me twice. The pass
         * builds the ranking, the sparklines and the squeeze from one set of
         * bars; when a new artefact is added, every install already carries
         * today's date, so the guard skips the pass and the new thing simply
         * never appears — for the rest of the day, looking exactly like a market
         * with nothing in it.
         *
         * Stamping the artefact SET as well means adding one is self-correcting:
         * bump the number, and the next pass rebuilds once. It is the difference
         * between remembering to write a migration and not having to.
         */
        val lastDailyBuild: String = "",
        val rankedAt: Long = 0L,
        /** The FULL ranking, every name scored, in screener order. Kept whole
         *  rather than pre-trimmed to the top N: the candidate list is the top N
         *  that are ALSO unheld, and which names are held changes during the day
         *  as algos take seats — so trimming at write time would silently shorten
         *  the list every time an algo bought something. */
        val ranking: List<RankRow> = emptyList(),
        val scanned: Int = 0,
        val eligible: Int = 0,
        /**
         * How many names the liquidity floor removed on the last daily pass,
         * and what the floor was when it did.
         *
         * SHOWN, NOT MERELY APPLIED. A filter that silently shrinks the universe
         * is indistinguishable from a market with fewer opportunities in it, and
         * the whole reason this exists is that a universe quietly grew without
         * anyone noticing. It is not going to quietly shrink as well.
         */
        val illiquidCut: Int = 0,
        val turnoverFloor: Double = 0.0,
        /** SYMBOL -> what the replay is holding. Absent = flat, or not checked yet
         *  on this pass. Rebuilt each watch tick from the checks it already runs,
         *  so it costs nothing beyond storing the answer. */
        val holdings: Map<String, Holding> = emptyMap(),
        /**
         * SYMBOL -> what the replay reaches over the FULL history, with no
         * fresh-start boundary applied.
         *
         * "Would this strategy be holding this name today, had it always been
         * running on it?" — the macro read on a candidate, and deliberately NOT the
         * same thing as [holdings]. That one is bounded to the moment the name
         * joined the watch list, because acting on an inherited phantom is how a
         * seat gets stuck; this one is unbounded, because a person judging whether
         * a name is worth a real order wants the whole picture, not the slice since
         * the app noticed it.
         */
        val historical: Map<String, Holding> = emptyMap(),
        /**
         * SYMBOL -> why the strategy is NOT currently able to manage this position.
         * Absent or blank = it is being managed.
         *
         * Exists because the panel cannot work this out for itself: deciding it
         * needs price history, and the panel repaints on a timer. Without this the
         * row claimed "its exits are run against your own fill price" purely
         * because a config was attached — which is exactly untrue in the one case
         * that matters, an entry older than the retained history, where the rules
         * cannot be applied and nothing is watching but the hard stop.
         */
        val adoptedNote: Map<String, String> = emptyMap(),
        /**
         * Names YOU put on the list, regardless of where the ranking placed them.
         *
         * These exist because the ranking cannot nominate the stocks this is most
         * useful for. It sorts by momentum, and a coiled stock is by definition
         * one that has stopped moving — so a squeeze candidate typically sits far
         * down the list with a flat or negative slope, and the gate that keeps
         * junk out would drop it.
         *
         * So a pin bypasses BOTH the depth cut and the slope gate. It does not
         * bypass exposure: something already held is still excluded, because the
         * point of the list is names you could still buy.
         *
         * What a pin does NOT do is force an entry. The config still decides that
         * on its own rules — pinning only buys the name a seat at the table.
         */
        val pinned: List<String> = emptyList(),
        /**
         * SYMBOL -> the bar from which entries count for that name: its FRESH-START
         * boundary, the watcher's equivalent of a seat's tracking baseline.
         *
         * Stamped the first time a name is checked and kept for as long as it stays
         * a candidate. Without it the replay inherits whatever position full history
         * happens to leave it in, so a name the strategy went long on weeks ago can
         * never announce anything — the alert needs an entry on the NEWEST bar, and
         * an August trade is never that.
         *
         * Dropped when a name leaves the list, so rejoining starts it fresh again
         * rather than resurrecting a boundary from the last time it was watched.
         */
        val watchingSince: Map<String, Long> = emptyMap(),
        /**
         * SYMBOL -> the local turning points on the watcher's own feed.
         *
         * Covers both sides of the panel — the names you could buy and the ones
         * you already hold — because the question differs but the number does
         * not: on a candidate it says whether you are buying into a ceiling, on a
         * holding it says whether your stop is sitting under a floor or in open
         * air.
         */
        val levels: Map<String, Levels> = emptyMap()
    )

    private fun file(ctx: Context, space: String) =
        File(ctx.filesDir, "manual_watch_${Spaces.baseOf(space.lowercase())}.json")

    @Synchronized
    fun load(ctx: Context, space: String): State = try {
        val f = file(ctx, space)
        val raw = if (!f.exists()) State() else gson.fromJson(f.readText(), State::class.java) ?: State()
        repairExitSeconds(ctx, space, renameRetiredSymbols(ctx, space, raw))
    } catch (e: Exception) { Log.e(TAG, "load($space) failed", e); State() }

    /** Any epoch time above this is milliseconds: in seconds it would be the year 5138. */
    private const val MS_FLOOR = 100_000_000_000L

    /**
     * ONE-TIME CLEAN-UP FOR EXIT TIMES SAVED IN MILLISECONDS.
     *
     * [Entry.exitTs] is seconds everywhere, but when the strategy closed a
     * position the watcher copied the broker's sale time across unconverted —
     * and every broker stamps that in milliseconds. Those rows read as a date
     * tens of thousands of years out: wrong on every screen that shows when the
     * trade ended, and sorted to the top of any list ordered by it. The writer
     * is fixed; this puts right the rows it already wrote, and saves them back
     * so it happens once.
     *
     * Runs on every load, so it asks the cheap question first and touches
     * neither disk nor state when nothing needs fixing.
     */
    private fun repairExitSeconds(ctx: Context, space: String, s: State): State {
        val bad = s.entries.count { it.exitTs > MS_FLOOR }
        if (bad == 0) return s
        val next = s.copy(entries = s.entries.map {
            if (it.exitTs > MS_FLOOR) it.copy(exitTs = it.exitTs / 1000L) else it
        })
        save(ctx, space, next)
        Log.w(TAG, "repaired $bad exit time${if (bad == 1) "" else "s"} saved in milliseconds in the $space book")
        return next
    }

    /**
     * ONE-TIME CLEAN-UP FOR A COMPANY THAT RENAMED WHILE WE HELD IT.
     *
     * The book records a position under whatever the broker called it on the day
     * it was bought, and for a renamed company the broker's name is the retired
     * one — see [SymbolRenames] for why it stays retired for ever on that side.
     * The rest of the app has since been taught to translate, but the rows
     * already written to disk still carry the dead name, and a dead name in the
     * book is not a cosmetic problem:
     *
     *   • the holdings read now returns the LIVE symbol, so the row and the
     *     broker line stop matching and the position reads as one the account no
     *     longer holds
     *   • the exit rules key off the symbol, and the row they cannot find is the
     *     row they cannot manage
     *
     * So the rename is applied to the stored state itself, once, and written
     * back. Only the SYMBOL moves. The broker's own instrument code on each entry
     * is deliberately left alone: it is still exactly what the broker expects on
     * an order, and rewriting it would break the one thing that was never broken.
     *
     * Applied to Xetra only, because that is the venue the rename table covers
     * for stored state — a US position is resolved through the broker's code on
     * every path that matters and never stored under a bare retired symbol.
     */
    private fun renameRetiredSymbols(ctx: Context, space: String, s: State): State {
        if (Spaces.baseOf(space.lowercase()) != "de") return s
        fun fixed(sym: String) = SymbolRenames.currentDe(sym.trim().uppercase())
        fun retired(sym: String) = SymbolRenames.isRetiredDe(sym.trim().uppercase())

        // The common case by far is nothing to do, and this runs on every tick —
        // so it asks the cheap question first and touches neither disk nor state
        // when the answer is no.
        val touched = s.entries.any { retired(it.symbol) } ||
            s.pinned.any { retired(it) } ||
            s.ranking.any { retired(it.symbol) } ||
            s.alerted.keys.any { retired(it) } ||
            s.holdings.keys.any { retired(it) } ||
            s.historical.keys.any { retired(it) } ||
            s.adoptedNote.keys.any { retired(it) } ||
            s.watchingSince.keys.any { retired(it) }
        if (!touched) return s

        // Merge rather than overwrite on a key collision: a book can legitimately
        // hold rows under BOTH names if a position was opened before the rename
        // and topped up after it, and the later write is the one to keep.
        fun <V> rekey(m: Map<String, V>): Map<String, V> {
            val out = LinkedHashMap<String, V>()
            for ((k, v) in m) out[fixed(k)] = v
            return out
        }

        val renamed = s.entries.count { retired(it.symbol) }
        val next = s.copy(
            entries = s.entries.map { if (retired(it.symbol)) it.copy(symbol = fixed(it.symbol)) else it },
            pinned = s.pinned.map { fixed(it) }.distinct(),
            ranking = s.ranking.map { if (retired(it.symbol)) it.copy(symbol = fixed(it.symbol)) else it },
            alerted = rekey(s.alerted),
            holdings = rekey(s.holdings),
            historical = rekey(s.historical),
            adoptedNote = rekey(s.adoptedNote),
            watchingSince = rekey(s.watchingSince)
        )
        save(ctx, space, next)
        Log.w(TAG, "renamed retired Xetra symbols in the $space book ($renamed entr" +
            (if (renamed == 1) "y" else "ies") + " moved to their current ticker)")
        return next
    }

    @Synchronized
    fun save(ctx: Context, space: String, s: State) {
        try { file(ctx, space).writeText(gson.toJson(s)) }
        catch (e: Exception) { Log.e(TAG, "save($space) failed", e) }
    }

    @Synchronized
    fun mutate(ctx: Context, space: String, block: (State) -> State): State {
        val next = block(load(ctx, space)); save(ctx, space, next); return next
    }

    /** True when [symbol] has already been alerted on [day] — the once-a-day cap. */
    fun alreadyAlerted(s: State, symbol: String, day: String): Boolean =
        s.alerted[symbol.uppercase()] == day

    fun markAlerted(ctx: Context, space: String, symbol: String, day: String) =
        mutate(ctx, space) { it.copy(alerted = it.alerted + (symbol.uppercase() to day)) }

    /** True when [symbol] has already been flagged near its floor on [day]. */
    fun alreadySupportAlerted(s: State, symbol: String, day: String): Boolean =
        s.supportAlerted[symbol.uppercase()] == day

    fun markSupportAlerted(ctx: Context, space: String, symbol: String, day: String) =
        mutate(ctx, space) {
            it.copy(supportAlerted = it.supportAlerted + (symbol.uppercase() to day))
        }

    /** Has this stock's [scale] low ("3d", "1w", "1m") already been flagged [day]? */
    fun floorAlerted(s: State, symbol: String, scale: String, day: String): Boolean =
        if (scale == "3d") alreadySupportAlerted(s, symbol, day)
        else s.supportAlertedScales?.get("${symbol.uppercase()}|$scale") == day

    fun markFloorsAlerted(ctx: Context, space: String, symbol: String, scales: List<String>, day: String) =
        mutate(ctx, space) { s ->
            val sym = symbol.uppercase()
            val wide = (s.supportAlertedScales ?: emptyMap()) +
                scales.filter { it != "3d" }.map { "$sym|$it" to day }
            s.copy(supportAlerted = if ("3d" in scales) s.supportAlerted + (sym to day) else s.supportAlerted,
                // Yesterday's entries are dead weight; keep only today's.
                supportAlertedScales = wide.filterValues { it == day })
        }

    /** The live (PENDING or OPEN) entry for a symbol, if any. Used to keep the
     *  watcher from alerting on something already being bought or held. */
    fun liveEntry(s: State, symbol: String): Entry? =
        s.entries.lastOrNull {
            it.symbol.equals(symbol, true) && (it.status == "PENDING" || it.status == "OPEN")
        }

    /**
     * AN ATTEMPT THAT BOUGHT NOTHING — a failed ladder or a cancelled order, with
     * no shares behind it.
     *
     * These were treated as live simply because they were not CLOSED, which is
     * the only way a row ever left the book. So every dead attempt stayed in the
     * open list for good, padded its count, and — the part that mattered —
     * stood in for the company when the panel asked "does the book already cover
     * this holding?". A real share bought in the broker's own app vanished from
     * the held-outside list behind an empty attempt on the same name.
     *
     * DECIDED LOCALLY, never by asking the broker. The broker has no opinion
     * about an order that never filled, and its "you don't hold that" is the same
     * answer it gives for a genuine sale — so routing this through a holdings
     * check would put a cosmetic tidy-up on the one path that can retire a real
     * position.
     *
     * THE SHARE COUNT IS THE SAFETY. Any row carrying shares is a position
     * whatever its status says, and stays in the book: a label that disagrees
     * with a quantity is a bug to be seen, not a row to be tidied away.
     */
    fun isDeadAttempt(e: Entry): Boolean =
        (e.status == "FAILED" || e.status == "CANCELLED") && e.filledQty <= 0.0

    /**
     * MAY A NEW BUY TAKE THIS SIGNAL'S PLACE IN THE BOOK?
     *
     * One row per company per signal bar — so a second buy on the same signal
     * writes over the first. That used to happen unchecked: a repeat tap on
     * Tata Steel was refused by the broker ("this entry already has an order"),
     * and the refusal was written over the record of the live Zerodha trigger
     * the first tap had placed. The book lost a real order.
     *
     * Only an attempt that bought nothing may be replaced. Anything else — a buy
     * in flight, a position, a finished trade — is a record of real money and
     * stays exactly as it is.
     */
    fun mayReplace(existing: Entry?): Boolean = existing == null || isDeadAttempt(existing)

    /**
     * Claim a signal for a new buy, in ONE step: look and write under the same
     * lock, so two taps a moment apart cannot both see "free" and both buy.
     *
     * Returns null when the claim succeeded and [e] is written; otherwise the
     * entry already standing on that signal, and nothing is written at all.
     */
    fun claim(ctx: Context, space: String, e: Entry): Entry? {
        var standing: Entry? = null
        mutate(ctx, space) { s ->
            val same = { it: Entry -> it.symbol.equals(e.symbol, true) && it.signalBarTs == e.signalBarTs }
            val cur = s.entries.lastOrNull(same)
            if (!mayReplace(cur)) { standing = cur; s }
            else s.copy(entries = (s.entries.filterNot(same) + e).takeLast(200))
        }
        return standing
    }

    /** Cap on hand-added names per space. Each one costs a price download every
     *  pass exactly like a ranked candidate, so an unbounded list would quietly
     *  become the bandwidth problem the depth setting exists to control. */
    const val MAX_PINNED = 25

    fun pin(ctx: Context, space: String, symbol: String): Boolean {
        val k = symbol.trim().uppercase()
        if (k.isBlank()) return false
        var added = false
        mutate(ctx, space) { s ->
            if (s.pinned.any { it.equals(k, true) } || s.pinned.size >= MAX_PINNED) s
            else { added = true; s.copy(pinned = s.pinned + k) }
        }
        return added
    }

    fun unpin(ctx: Context, space: String, symbol: String) {
        val k = symbol.trim().uppercase()
        mutate(ctx, space) { s -> s.copy(pinned = s.pinned.filterNot { it.equals(k, true) }) }
    }

    fun isPinned(s: State, symbol: String): Boolean =
        s.pinned.any { it.equals(symbol.trim(), true) }

    fun upsert(ctx: Context, space: String, e: Entry) = mutate(ctx, space) { s ->
        // Keyed by symbol + the bar it fired on: re-entering the same name later
        // is a genuinely different trade and must not overwrite the first.
        val rest = s.entries.filterNot {
            it.symbol.equals(e.symbol, true) && it.signalBarTs == e.signalBarTs
        }
        s.copy(entries = (rest + e).takeLast(200))
    }

    /**
     * Write the REAL per-share exit onto a closed entry, once the broker reports it.
     *
     * THE GAP THIS FILLS. An adopted position is closed the moment its sell order
     * comes back, but a sell order returns an id — the price it achieved arrives
     * minutes later, from the fill reader. So at closing time the only exit figure
     * in hand is the strategy's candle close, and [Entry.exitPrice] is documented
     * to hold a real fill or zero precisely so that the difference cannot be
     * papered over. It is therefore written as zero, and this is what corrects it.
     *
     * Nothing else can. The routine that backfills real exits only inspects entries
     * still marked OPEN, and by the time a price exists this one is CLOSED — so the
     * candle estimate stood as the permanent record of what the position made. On
     * one real S92 exit that was the difference between the +0.80% the card claimed
     * and the +3.29% the account actually received.
     *
     * Keyed by the paper trade id the mirror knows, which is exactly the
     * "SYMBOL-signalBarTs" pair an entry is keyed by — no fuzzy symbol-and-time
     * matching, which would eventually pair the wrong round trip in a name that has
     * been traded twice.
     *
     * Silently does nothing for a namespace that is not the watchlist's, for an
     * entry that has since been re-opened, or for one that already carries a real
     * price: a figure the broker confirmed is never overwritten by a later guess.
     *
     * [Entry.exitTs] IS DELIBERATELY LEFT ALONE. It holds the strategy BAR the
     * exit rule fired on, in seconds, and that is a different fact from when the
     * order filled — one explains the decision, the other records the execution,
     * and over a weekend they can sit days apart. When the position actually
     * closed is already kept in [Entry.closedAt], in milliseconds; writing a
     * millisecond fill time into the seconds-based bar field would put the trade
     * somewhere around the year 58000 on every chart that reads it.
     */
    fun recordRealExit(ctx: Context, ns: String, paperTradeId: String,
                       exitPrice: Double, exitQty: Double) {
        if (exitPrice <= 0.0) return
        if (Spaces.bucketIdOf(ns) != ID) return
        val base = Spaces.baseOf(ns)
        mutate(ctx, base) { s ->
            val hit = s.entries.firstOrNull {
                "${it.symbol.uppercase()}-${it.signalBarTs}" == paperTradeId &&
                    it.status == "CLOSED" && it.exitPrice <= 0.0
            } ?: return@mutate s
            Log.i(TAG, "[$base] ${hit.symbol} real exit landed: ${"%.4f".format(exitPrice)}" +
                (if (hit.filledPrice > 0.0)
                    " → your money ${"%+.2f".format((exitPrice / hit.filledPrice - 1.0) * 100.0)}%"
                 else ""))
            s.copy(entries = s.entries.map {
                if (it === hit) it.copy(
                    exitPrice = exitPrice,
                    exitQty = if (exitQty > 0.0) exitQty else it.exitQty)
                else it
            })
        }
    }
}

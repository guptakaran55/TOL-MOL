package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

/**
 * THE BUILT-IN PAPER ALGOS — floor orders and MACD turns — paper only, built
 * into the app rather than read from a config file.
 *
 * The config-driven algos are a large family where the file picks among moves the
 * engine already knows. These strategies need moves that engine does not have (an
 * order resting at a price, a fill only below it, orders that die at the close,
 * a weekly-and-daily trend filter), so they have their own engine and record.
 * Their few numbers are settings on the algo instead.
 *
 * TWO KINDS, TWO SEPARATE STRATEGIES. Each algo is one or the other, with its
 * own seats and its own record, so the two entries can be compared on their own
 * results rather than competing for the same seats:
 *   • floor — two resting buy orders a day at the recent low;
 *   • macd  — buys at the next price once the 15-minute and hourly MACD turn up
 *             together, with the 4-hour rising.
 * They share the universe, the trend filter and every exit.
 *
 * NOT A BUCKET, ON PURPOSE. Everything that can place a real order — the live
 * mirrors, the arming switches, the broker audits — walks the bucket list. Kept
 * out of it, this algo cannot be armed or mirrored to a broker by any path, not
 * just by the absence of a switch.
 *
 * EVERY FIELD HAS A DEFAULT, so each class has a no-argument constructor and the
 * JSON reader fills a field missing from an older file with that default rather
 * than with an empty value.
 *
 * SAVED SAFELY: written to a temporary file and swapped in; an unreadable file is
 * set aside under another name, never overwritten with an empty list.
 */
object FloorAlgoStore {
    private const val TAG = "FloorAlgoStore"
    private const val FILE = "floor_algos.json"
    private const val MAX_TRADES = 500
    private const val MAX_LOG = 80

    /** Where these algos may run: the stock markets. Crypto never closes, so
     *  "cancel at the close" has no meaning there. */
    val SPACES = listOf("de", "us", "in")

    const val KIND_FLOOR = "floor"
    const val KIND_MACD = "macd"
    /** A CAMPAIGN per stock: entered near its monthly support, swung between
     *  daily highs and lows, ended on the daily or 4-hour MACD turning down.
     *  See [com.signalscope.app.trading.CampaignRules]. */
    const val KIND_CAMPAIGN = "campaign"
    /** THE MONTHLY-LOW CHAIN: a stock at its monthly low, a positive news outlook,
     *  half a seat on the timeframes all turning up, half on the conservative
     *  champion's buy, each sold on the champion's exits. Stocks the outlook turns
     *  down are followed on the side. See [com.signalscope.app.trading.ChainRules]. */
    const val KIND_CHAIN = "chain"

    /**
     * MACD turns on the MONTHLY-LOW LIST, AI-checked — the user's design (21 Oct
     * 2026): the chain's stock picker, the MACD-turn entry and exits. Not a kind
     * of its own: a MACD-turns algo whose [Settings.universeMode] is this. Each
     * morning, every stock in the scan that touched its monthly low in the last
     * [Settings.watchDays] trading days is asked the news outlook; BUY or STRONG
     * BUY is watched for the turn as usual, anything else is watched ON THE SIDE —
     * its turns are followed, never bought — so the record shows what the outlook
     * was worth. Backtested without the outlook (desktop, Germany 2023-26, zero
     * cost): +12.1%/yr against +10.2% for the same entry on the top-by-ROC list.
     */
    const val UNI_MONTHLY_LOW = "monthlylow"
    /** What the "new algo" dialog sends for it; created as [KIND_MACD]. */
    const val CREATE_LOW_MACD = "lowmacd"

    /** [Member.pick] on a monthly-low list. */
    const val PICK_OUT = "out"     // not at its monthly low lately, or no verdict yet: not watched today
    const val PICK_YES = "yes"     // at its monthly low, outlook BUY / STRONG BUY: may take a seat
    const val PICK_SIDE = "side"   // at its monthly low, outlook anything else: followed on the side

    /** Floor orders that expire at the close and are placed afresh each morning. */
    const val MODE_DAY = "day"
    /** Floor orders that wait, day after day, in the weekly support zone until
     *  they fill — or until the price reaches the weekly resistance zone first. */
    const val MODE_WEEK = "week"

    data class Settings(
        /** Paper money for the whole algo, in the market's currency. */
        val capital: Double = 1000.0,
        /** Stocks held at once. Each seat is capital ÷ seats; a floor order is half a seat. */
        val seats: Int = 5,
        /** Top of the Discovery list by ROC — room to the nearest resistance. */
        val universeSize: Int = 30,
        /** Floor orders: the first order rests at the lowest price of this many trading days. */
        val lowDays: Int = 3,
        /** Floor orders: the second order rests this many percent below the first. */
        val secondPct: Double = 2.0,
        /** Sold at the close this many trading days after the buy, if nothing else sold it first. */
        val holdDays: Int = 10,
        /** Sold if the price falls this many percent under the average buying price. 0 = no stop. */
        val stopPct: Double = 4.0,
        /** Sold if the price rises this many percent over the average buying price. 0 = no target. */
        val targetPct: Double = 7.0,
        /**
         * THE TREND FILTER, as a switch. On: only stocks with the weekly and daily
         * MACD both rising are bought, and a held stock is sold at the close of a
         * morning that finds either no longer rising. Off: every stock in the
         * universe is a candidate and the trend never sells — the entry and the
         * stop, target and time limit are tested on their own, on more stocks.
         * Off by default (the user's choice, 9 Oct 2026, after a week in which the
         * filter let through 0–6 stocks a day and sold dip buys the next morning).
         */
        val trendFilter: Boolean = false,
        /**
         * Floor orders only: [MODE_DAY] — at the recent low, expiring at each
         * close; or [MODE_WEEK] — at the top of the weekly support zone, waiting
         * until filled, cancelled if the price reaches the weekly resistance zone
         * first (the user's design, 14 Oct 2026).
         */
        val orderMode: String = MODE_DAY,
        /** Weekly zones: the bottom and the top this many percent of the last
         *  week's price range (low to high) — support and resistance. */
        val zonePct: Double = 10.0,
        /**
         * HALF THE BID/ASK SPREAD, in percent — what a MARKET order gives up on
         * each execution against the price the chart shows. Charged on MACD buys
         * and on sales by the stop, the time limit or the trend; never on a floor
         * buy or a sale at the target, which are limit orders and do not cross
         * the spread. Null = the market's default ([defaultSpreadPct]).
         */
        val spreadPct: Double? = null,
        // ── campaigns only ──
        /** What the campaign's stop is counted in — PCT, ATR or BB — and [stopPct]
         *  is its size, as on the watcher's own trailing stop: hung off the highest
         *  price since each buy. */
        val stopUnit: String = "PCT",
        /** Buffer zones are this fraction of the ATR of their own timescale. */
        val atrFrac: Double = 0.1,
        /** "projected" — supports and resistances from the trend channel the
         *  wave projection draws; "range" — plain recent lows and highs. */
        val levelSource: String = "projected",
        /** A campaign still running after this many trading days is ended. */
        val maxDays: Int = 60,
        /** Ask the news outlook before starting a campaign: AVOID / STRONG AVOID skips
         *  the stock, BUY / STRONG BUY bids higher so the buy is surer to fill. */
        val useNews: Boolean = true,
        /** "top" — the top [universeSize] by ROC; "all" — every stock in the
         *  market's latest Discovery scan, which has already passed the market's
         *  volume × price floor (the watcher panel's turnover filter). */
        val universeMode: String = "top",
        /** Campaigns only: money per campaign. 0 = capital ÷ seats. With [seats]
         *  at 0 — no limit on campaigns — this is what each one is given. */
        val seatAmount: Double = 0.0,
        /** Campaigns only: news outlooks asked a morning at most — each is a paid
         *  call. Candidates past it wait for the next morning, never start unasked. */
        val newsPerDay: Int = 12,
        /** Chains only: trading days a stock stays watched after its last touch of
         *  the monthly low; a half that has not bought by then never will. */
        val watchDays: Int = 20
    )

    /** Half-spread to assume per market order when none is set: Germany sits
     *  between tight DAX names and wider small caps; NASDAQ 100 names are tight. */
    fun defaultSpreadPct(space: String): Double = when (space) { "de" -> 0.10; "us" -> 0.03; else -> 0.05 }

    /** A stock in the universe, and what the trend filter said on the plan day. */
    data class Member(
        val symbol: String = "",
        val name: String = "",
        /** Return on capital — what the list was first picked by. Kept only so
         *  older saved lists still read; nothing uses it now. */
        val roce: Double? = null,
        /** ROC: room to the nearest resistance from the Discovery scan, in percent —
         *  what the list is picked by. Null on a list saved before that. */
        val roc: Double? = null,
        /** Weekly and daily MACD slope: 1 rising, -1 falling, 0 flat, null unknown / not checked. */
        val week: Int? = null,
        val day: Int? = null,
        /** MACD turns only: the 4-hour slope at the last intraday look. */
        val h4: Int? = null,
        val note: String = "",
        // MACD turns only — the day so far, for the journal: how many looks,
        // how many with the 4-hour rising, how many with the 15-minute and hourly
        // both rising, and the first turn seen (bought, or blocked by the 4-hour).
        val looks: Int = 0,
        val h4Up: Int = 0,
        val bothUp: Int = 0,
        val turnSec: Long = 0L,
        val turnPx: Double = 0.0,
        val turnBlocked: Boolean = false,
        // Monthly-low lists only ([UNI_MONTHLY_LOW]). Nullable: lists saved before
        // these existed read back without them (Gson leaves them null).
        /** [PICK_OUT], [PICK_YES] or [PICK_SIDE]; null on any other list. */
        val pick: String? = null,
        /** The news outlook's verdict this morning. */
        val verdict: String? = null,
        /** Trading days since its last touch of the monthly low (0 = yesterday). */
        val lowAgo: Int? = null
    )

    /** One half of the day's order on a stock. [filledSec] 0 = not filled. */
    data class Leg(
        val price: Double = 0.0,
        val amount: Double = 0.0,
        val filledSec: Long = 0L,
        val fillPrice: Double = 0.0
    )

    data class Order(
        val symbol: String = "",
        val name: String = "",
        /** The floor: the lowest price of the last few trading days. */
        val low: Double = 0.0,
        val first: Leg = Leg(),
        val second: Leg = Leg(),
        /** RESTING while it can fill; ENDED once its day is over (any half still
         *  unfilled then simply expired); CANCELLED — a weekly order whose price
         *  reached the resistance zone before it dipped. */
        val status: String = "RESTING",
        /** Only prices from this moment on can fill it — never one that traded
         *  before the order existed. */
        val fromSec: Long = 0L,
        /** [MODE_DAY] or [MODE_WEEK]. */
        val mode: String = MODE_DAY,
        /** Weekly orders: the day it was placed, the resistance-zone price that
         *  cancels it, and when that happened. */
        val placedDay: String = "",
        val cancelAt: Double = 0.0,
        val cancelSec: Long = 0L,
        /** The trade its fills went into — a weekly order's second half can fill
         *  on a later day than its first. */
        val tradeId: String = ""
    )

    data class Trade(
        val id: String = "",
        val symbol: String = "",
        val name: String = "",
        /** The exchange's calendar day it was bought (yyyy-mm-dd). */
        val day: String = "",
        /** When the first half filled — the dip is measured from here. 0 = a MACD
         *  turn seen but not yet filled: it buys at the next fifteen-minute price. */
        val firstSec: Long = 0L,
        val qty: Double = 0.0,
        /** Money in. The average buying price is cost ÷ qty. */
        val cost: Double = 0.0,
        /** How many of the two halves filled. A MACD turn buys a whole seat as one. */
        val halves: Int = 0,
        /** The first fill's price, and when and at what the second half joined —
         *  so the stop and target can be replayed on the position as it stood at
         *  each moment. firstPrice 0 = a trade from before stops and targets
         *  existed: it keeps the rule it was bought under, sold on time only. */
        val firstPrice: Double = 0.0,
        val secondSec: Long = 0L,
        val secondPrice: Double = 0.0,
        /** MACD turns: when the turn was seen, and the first bar that may fill it. */
        val signalSec: Long = 0L,
        val fromSec: Long = 0L,
        /** OPEN | SOLD | MISSED — missed means every seat was taken when the
         *  order filled: followed for the record, never counted in the money. */
        val status: String = "OPEN",
        val sellDay: String? = null,
        val sellPrice: Double = 0.0,
        val sellSec: Long = 0L,
        /** What sold it: stop | target | time | trend. */
        val exitReason: String = "",
        /** The stop and target in force now, from the average buying price. 0 = none. */
        val stop: Double = 0.0,
        val target: Double = 0.0,
        /** The day the morning check found the daily or weekly MACD no longer
         *  rising — sold at that day's close unless the stop or target comes first. */
        val trendDay: String? = null,
        /** Money result, AFTER the broker's charges once [feesIn]. */
        val pnl: Double = 0.0,
        /** The broker's charges for the round trip, in the market's currency —
         *  what was charged once sold; what a sale at the latest price would be
         *  charged while held or followed. Same rate card as the live algos. */
        val fees: Double = 0.0,
        /** [pnl] has the charges taken off. Sales from before charges were
         *  modelled are put right once, on the next pass. */
        val feesIn: Boolean = false,
        /** The spread paid on market executions, in the market's currency — charged
         *  once sold, estimated (as a sale at the market) while held or followed. */
        val slip: Double = 0.0,
        /** [pnl] has [slip] taken off. Older sales are put right once. */
        val slipIn: Boolean = false,
        val lastPrice: Double = 0.0,
        val lastSec: Long = 0L,
        // What happened next, in percent from the buying price; null until known.
        val ret1: Double? = null,
        val ret3: Double? = null,
        val ret5: Double? = null,
        val dip: Double? = null,
        val done: Boolean = false,
        val checkedMs: Long = 0L,
        // Monthly-low lists only. Nullable for the same reason as on [Member].
        /** The news outlook's verdict when the turn was seen. */
        val verdict: String? = null,
        /** Why a MISSED trade was only followed: "news outlook AVOID", "every seat taken". */
        val followWhy: String? = null,
        /** A followed trade, replayed through the same stop, target and time limit
         *  as a bought one: what would have sold it, the result before charges, and
         *  the day — so outlook-yes and outlook-no can be compared like for like. */
        val sideExit: String? = null,
        val sideExitPct: Double? = null,
        val sideExitDay: String? = null
    ) {
        val entry: Double get() = if (qty > 0.0) cost / qty else 0.0
    }

    /**
     * ONE CAMPAIGN on one stock — a seat held for weeks, through several swing
     * trades, until the trend gives out. Each swing's buy and sale is a [Trade]
     * of its own (id "campaignId|n"), so the card's Held and Closed lists, the
     * charges and the results work for campaigns as for any algo.
     */
    data class Campaign(
        val id: String = "",
        val symbol: String = "",
        val name: String = "",
        /** WAITING (entry order resting) · HOLDING · RIDING (above the day's high,
         *  waiting for the price to turn down) · OUT (sold a swing, waiting to buy
         *  back lower) · ENDED. */
        val state: String = "WAITING",
        val startDay: String = "",
        val endDay: String = "",
        val endReason: String = "",
        /** Money for the next buy: the seat at the start, then what each sale left. */
        val cash: Double = 0.0,
        val seat: Double = 0.0,
        /** The resting buy order's price, and the first bar that may fill it. */
        val orderPrice: Double = 0.0,
        val orderFromSec: Long = 0L,
        /** The swing held now: its trade, size, cost, and highest price since bought. */
        val legId: String = "",
        val qty: Double = 0.0,
        val cost: Double = 0.0,
        val peak: Double = 0.0,
        val rideLast: Double = 0.0,
        val legs: Int = 0,
        // Today's levels, from the morning's daily bars.
        val levelsDay: String = "",
        val support: Double = 0.0,
        val weekRes: Double = 0.0,
        val monthRes: Double = 0.0,
        val prevHigh: Double = 0.0,
        val prevLow: Double = 0.0,
        val atr: Double = 0.0,
        val bbHalf: Double = 0.0,
        val bufDay: Double = 0.0,
        val bufWeek: Double = 0.0,
        val bufMonth: Double = 0.0,
        /** Days in a row the daily MACD slope has been falling. */
        val negDays: Int = 0,
        /** In the monthly resistance zone — swings stop, the tighter exit applies. */
        val inMonthZone: Boolean = false,
        /** Entered the weekly resistance zone today: the swing target is the
         *  weekly resistance itself. */
        val inWeekZone: Boolean = false,
        val weekCrosses: Int = 0,
        /** The 4-hour MACD slope at the last look, to see it flip. */
        val h4Sign: Int = 0,
        /** Sell at the next bar's open, and why. */
        val exitPending: String = "",
        /** Bars up to here are done with. */
        val lastBarSec: Long = 0L,
        /** The news outlook's verdict when the campaign was started. */
        val verdict: String = "",
        /** Money made or lost by its finished swings, after charges and spread. */
        val pnl: Double = 0.0
    )

    /**
     * ONE STOCK'S CHAIN: watched from the morning its monthly low was touched,
     * until both halves have sold or the watch ran out. Each half that buys is a
     * [Trade] of its own ("chainId|A" on the timeframes, "chainId|B" on the
     * champion), so the card's Held and Closed lists and the charges work as for
     * every algo.
     *
     * [counted] false: followed on the side, never counted in the money — the
     * news outlook said no, or every seat was taken. Its trades are MISSED. That
     * is what lets the record answer whether the outlook helped.
     */
    data class Chain(
        val id: String = "",
        val symbol: String = "",
        val name: String = "",
        /** WATCHING (no half bought yet) · HOLDING (at least one half in) · ENDED. */
        val state: String = "WATCHING",
        /** The day judged at its monthly low, the latest such day, and the last
         *  trading day either half may still buy. */
        val touchDay: String = "",
        val lastTouchDay: String = "",
        val expiryDay: String = "",
        /** No bar before this may buy — the watch began when the app first saw it. */
        val watchFromSec: Long = 0L,
        /** The news outlook when the chain started; "" = not asked yet (today's
         *  checks were used up) — nothing buys until it is. */
        val verdict: String = "",
        val counted: Boolean = true,
        /** Why it is only followed. */
        val why: String = "",
        /** Money for the whole seat; each half is half of it. */
        val seat: Double = 0.0,
        /** The 15-minute bars up to here have been looked at for the timeframes turn. */
        val scannedSec: Long = 0L,
        /** The last time the champion was asked about this stock. */
        val champCheckSec: Long = 0L,
        /** Each half: "" waiting · OPEN · SOLD · VOID (the watch ran out first). */
        val dots: String = "",
        val champ: String = "",
        val endDay: String = "",
        val endReason: String = ""
    )

    data class Algo(
        val id: String = "",
        val name: String = "",
        /** [KIND_FLOOR] or [KIND_MACD]. Algos from before there were two are floor. */
        val kind: String = KIND_FLOOR,
        val space: String = "de",
        val enabled: Boolean = true,
        val notify: Boolean = true,
        val createdAt: Long = 0L,
        val settings: Settings = Settings(),
        val universe: List<Member> = emptyList(),
        /** The day the universe was last taken from Discovery, and that scan's time. */
        val universeDay: String = "",
        val universeFrom: Long = 0L,
        /** The day [orders] belong to — and, for MACD turns, the day the
         *  universe's trend was read for. */
        val planDay: String = "",
        /** The last day the held stocks' daily and weekly trend was checked. */
        val trendCheckDay: String = "",
        /** The last plan day written to the journal. See [FloorJournalStore]. */
        val journalDay: String = "",
        /** Whether [planDay] was planned with the trend filter on. Plans saved
         *  before the filter became a switch were — hence the default. When this
         *  and the setting disagree, today is planned again. */
        val planFilter: Boolean = true,
        /** The floor order type [planDay] was planned with; a change re-plans today. */
        val planMode: String = MODE_DAY,
        /** Campaigns only: every campaign, running or ended (the newest 200 kept). */
        val campaigns: List<Campaign> = emptyList(),
        /** Chains only: every chain, running or ended (the newest 300 kept). */
        val chains: List<Chain> = emptyList(),
        /** News outlook verdicts by stock: "VERDICT|yyyy-mm-dd" — asked again after
         *  a few days, not on every pass. */
        val outlooks: Map<String, String> = emptyMap(),
        val orders: List<Order> = emptyList(),
        val trades: List<Trade> = emptyList(),
        val log: List<String> = emptyList(),
        val lastRunMs: Long = 0L,
        val lastNote: String = ""
    ) {
        /** Namespace form, so a notification can open this card like any algo's. */
        val ns: String get() = "${space}__$id"
    }

    data class State(val algos: List<Algo> = emptyList())

    private val gson = Gson()
    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    @Synchronized
    fun load(ctx: Context): State {
        val f = file(ctx)
        if (!f.exists()) return State()
        return try {
            gson.fromJson(f.readText(), State::class.java) ?: State()
        } catch (e: Exception) {
            Log.e(TAG, "floor algos unreadable — set aside, starting afresh", e)
            try { f.renameTo(File(f.parentFile, "$FILE.bad-${System.currentTimeMillis()}")) } catch (_: Exception) {}
            State()
        }
    }

    @Synchronized
    private fun save(ctx: Context, s: State) {
        try {
            val f = file(ctx)
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(gson.toJson(s))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) { Log.e(TAG, "floor algos save failed", e) }
    }

    /** Read, change and write back as one step. */
    @Synchronized
    fun mutate(ctx: Context, block: (State) -> State): State {
        val next = block(load(ctx))
        save(ctx, next.copy(algos = next.algos.map { tidy(it) }))
        return next
    }

    private fun tidy(a: Algo): Algo {
        // Closed and finished trades fall off the old end first; open ones never do.
        val trades = if (a.trades.size <= MAX_TRADES) a.trades else {
            val keep = a.trades.filter { it.status == "OPEN" || !it.done }
            val old = a.trades.filterNot { it.status == "OPEN" || !it.done }
            old.takeLast((MAX_TRADES - keep.size).coerceAtLeast(0)) + keep
        }
        // Ended campaigns fall off past 200; running ones never do.
        val campaigns = if (a.campaigns.size <= 200) a.campaigns else {
            val live = a.campaigns.filter { it.state != "ENDED" }
            a.campaigns.filter { it.state == "ENDED" }.takeLast((200 - live.size).coerceAtLeast(0)) + live
        }
        val chains = if (a.chains.size <= 300) a.chains else {
            val live = a.chains.filter { it.state != "ENDED" }
            a.chains.filter { it.state == "ENDED" }.takeLast((300 - live.size).coerceAtLeast(0)) + live
        }
        return a.copy(trades = trades.sortedBy { it.firstSec }, log = a.log.takeLast(MAX_LOG),
            campaigns = campaigns, chains = chains)
    }

    fun list(ctx: Context, space: String): List<Algo> = load(ctx).algos.filter { it.space == space }

    fun get(ctx: Context, id: String): Algo? = load(ctx).algos.firstOrNull { it.id == id }

    /** Switched on — or paused but still holding, or still following what
     *  happened after a buy. */
    fun hasWork(a: Algo): Boolean = a.enabled || a.trades.any { it.status == "OPEN" || !it.done } ||
        a.chains.any { it.state == "HOLDING" }

    fun anyRunning(ctx: Context): Boolean = load(ctx).algos.any { hasWork(it) }

    fun create(ctx: Context, space: String, name: String, kind: String = KIND_FLOOR): Algo {
        val lowMacd = kind == CREATE_LOW_MACD
        val k = if (lowMacd) KIND_MACD
                else if (kind == KIND_MACD || kind == KIND_CAMPAIGN || kind == KIND_CHAIN) kind else KIND_FLOOR
        // A campaign's stop starts as the watcher's own trailing stop for this
        // market — the same unit and size — or two ATRs where none is set.
        // A chain starts on the user's tested setup: seven seats, every stock past
        // the market's volume × price floor, the news outlook on.
        // MACD turns on the monthly-low list start as the German MACD turns algo
        // runs — 1000 in twenty seats, stop 4%, target 7%, ten days — so the two
        // differ only in how the stocks are picked.
        val settings = if (k == KIND_CHAIN) Settings(seats = 7, capital = 1400.0, universeMode = "all",
            useNews = true, newsPerDay = 12, watchDays = 20, stopPct = 4.0, targetPct = 0.0)
        else if (lowMacd) Settings(seats = 20, capital = 1000.0, universeMode = UNI_MONTHLY_LOW,
            useNews = true, newsPerDay = 12, watchDays = 20, stopPct = 4.0, targetPct = 7.0, holdDays = 10,
            trendFilter = false)
        else if (k != KIND_CAMPAIGN) Settings() else try {
            val w = ManualWatchStore.load(ctx, space)
            if (w.trailPct > 0.0) Settings(stopPct = w.trailPct, stopUnit = (w.trailUnit ?: ManualWatchStore.TrailUnit.PCT).name)
            else Settings(stopPct = 2.0, stopUnit = "ATR")
        } catch (e: Exception) { Settings(stopPct = 2.0, stopUnit = "ATR") }
        val a = Algo(
            id = "f" + java.lang.Long.toString(System.currentTimeMillis() % 1_000_000_000L, 36),
            name = name.trim().ifBlank { if (lowMacd) "AI low + MACD turns" else when (k) { KIND_MACD -> "MACD turns"; KIND_CAMPAIGN -> "Campaigns"
                KIND_CHAIN -> "Monthly-low chain"; else -> "Floor orders" } }.take(40),
            kind = k, space = space, createdAt = System.currentTimeMillis(), settings = settings,
            log = listOf(stamp() + " · created"))
        mutate(ctx) { it.copy(algos = it.algos + a) }
        return a
    }

    fun update(ctx: Context, id: String, block: (Algo) -> Algo): Algo? {
        var out: Algo? = null
        mutate(ctx) { s -> s.copy(algos = s.algos.map { if (it.id == id) block(it).also { n -> out = n } else it }) }
        return out
    }

    fun delete(ctx: Context, id: String) {
        mutate(ctx) { s -> s.copy(algos = s.algos.filterNot { it.id == id }) }
        FloorJournalStore.delete(ctx, id)
    }

    fun stamp(ms: Long = System.currentTimeMillis()): String =
        java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.UK).format(java.util.Date(ms))
}

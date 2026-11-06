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
 * Trading 212, and India's buy buttons since 6 Nov 2026 — released, the buy goes
 * to Zerodha as the button sends it (an ordinary order; a trigger only if Zerodha
 * refuses one). The crypto space trades around the clock with no daily range in
 * the same sense.
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
        /** "DE", "US" or "IN". */
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
        /** A waiting buy's memory for the dip-and-turn — see [RvGate.Memory]. */
        val rvPeak: Double = 0.0,
        val rvTrough: Double = 0.0,
        /** A buy's price when you pressed the button (or, on a shut market, the
         *  last price then), and the most it may pay without asking you — see
         *  [limitFor]. 0 = not known yet; set at the first look. */
        val capRef: Double = 0.0,
        val capLimit: Double = 0.0,
        /** When it stopped to ask you, because the price had run past [capLimit]. */
        val askedAt: Long = 0L,
        /** A watchlist Buy's entry bar when you pressed it — see [watchSignalTs].
         *  0 = none (any other kind, a forced buy, or queued before this existed). */
        val signalTs: Long = 0L,
        /** WAITING, STARTED, DONE, CANCELLED. */
        val status: String = "WAITING",
        val why: String = "",
        val result: String = ""
    ) {
        val isSell get() = kind == Kind.T212_SELL || kind == Kind.ALGO_TP
        val ns get() = when (space.uppercase()) { "DE" -> "de"; "IN" -> "in"; else -> "us" }
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

    // ── THE PRICE LIMIT ON YOUR WAITING BUYS ────────────────────────────────
    // The algos' waiting buys have always had one (the chase ceiling): a stock
    // that ran away while the order waited is not bought. Yours had none, so a
    // buy that waited two hours for a dip that never came bought whatever the
    // price had become. Now a waiting buy that is ready to go but finds the price
    // more than [LIMIT_ATR] of a day's move above where you pressed the button
    // does not buy: it stops and asks you, with a notification. Unanswered, it
    // lapses at the close. Sales are never held back by it.

    /** How far above your price a waiting buy may go without asking, in ATRs. */
    const val LIMIT_ATR = 0.5

    /** The most a waiting buy may pay without asking: [ref] plus half a day's move
     *  (2% of the price standing in for a day's move when that is unknown). */
    fun limitFor(ref: Double, atr: Double): Double =
        if (!(ref > 0.0)) 0.0 else ref + LIMIT_ATR * (if (atr > 0.0) atr else ref * 0.02)

    /** Has the price run past the limit? Unknown price or limit: no. */
    fun overLimit(price: Double?, limit: Double): Boolean =
        price != null && price > 0.0 && limit > 0.0 && price > limit

    /** Buys that ran past their limit and are waiting for your answer. */
    fun askingFor(ctx: Context, space: String): List<Order> =
        all(ctx).filter { it.status == "ASK" && it.space.equals(space, true) }

    // ── EVERY DECISION ABOUT A BUTTON ORDER, KEPT ───────────────────────────
    // A "go" used to leave no trace: the order simply ran, and afterwards there
    // was no telling whether the gate had been asked at all, or what it saw. So
    // every outcome is written down — started at once, held, released, called
    // off — and so is an order placed with the box UNticked, with where the price
    // sat and what the gate would have said, so the two can be compared.

    private const val LOG_FILE = "rv_gate_log.json"
    private const val LOG_KEEP_MS = 14 * 24 * 3_600_000L
    private const val LOG_MAX = 400

    data class LogRow(
        val at: Long,
        val kind: String,
        /** "DE" or "US". */
        val space: String,
        val symbol: String,
        val buy: Boolean,
        /** Was the box ticked — i.e. did the gate get a say? */
        val asked: Boolean,
        /** What happened, in a few words: "started at once", "held", … */
        val outcome: String,
        /** The gate's reason, or what it would have said. */
        val why: String = "",
        /** What the gate saw — see [RvGate.snapshot]. */
        val rvNote: String = ""
    )

    private data class LogBook(val rows: List<LogRow> = emptyList())

    @Synchronized
    private fun logRows(ctx: Context): List<LogRow> = try {
        val f = File(ctx.filesDir, LOG_FILE)
        if (!f.exists()) emptyList()
        else gson.fromJson(f.readText(), LogBook::class.java)?.rows.orEmpty()
    } catch (e: Exception) { android.util.Log.w(TAG, "log unreadable", e); emptyList() }

    @Synchronized
    private fun logAdd(ctx: Context, row: LogRow) {
        val cutoff = row.at - LOG_KEEP_MS
        val keep = (logRows(ctx).filter { it.at > cutoff } + row).takeLast(LOG_MAX)
        try { File(ctx.filesDir, LOG_FILE).writeText(gson.toJson(LogBook(keep))) }
        catch (e: Exception) { android.util.Log.w(TAG, "log save failed", e) }
    }

    private fun logOf(o: Order, asked: Boolean, outcome: String, why: String, note: String = "") =
        LogRow(System.currentTimeMillis(), o.kind, o.space, o.symbol, !o.isSell, asked, outcome, why, note)

    // ── THE RV GATE'S LONG RECORD (see [RvJournal]) ─────────────────────────
    private fun jSide(o: Order) = if (o.isSell) RvGate.Side.SELL else RvGate.Side.BUY
    private fun jKey(id: String) = "you|$id"
    private fun jNewId() = "x${System.currentTimeMillis()}${(Math.random() * 1000).toInt()}"
    private fun jBase(o: Order, ticker: String, asked: Boolean, runBy: String) =
        RvJournal.base("you", jSide(o), o.ns, o.symbol, ticker, mapOf("kind" to o.kind, "asked" to asked,
            "runBy" to runBy, "amount" to o.cash.takeIf { it > 0.0 }, "qty" to o.qty.takeIf { it > 0.0 },
            "limit" to o.capLimit.takeIf { it > 0.0 }))

    /** What a finished order filled, or why it did not — one line in the record. */
    private fun jOutcome(ctx: Context, key: String, text: String, fill: Map<String, Any?>) {
        val qty = (fill["qty"] as? Double) ?: 0.0
        val price = (fill["price"] as? Double) ?: 0.0
        if (qty > 0.0 || price > 0.0) RvJournal.event(ctx, "fill", key, fill + ("result" to text))
        else RvJournal.event(ctx, "end", key, mapOf("why" to text))
    }

    /** This market's decisions since [sinceMs], newest first. */
    fun history(ctx: Context, space: String, sinceMs: Long): List<LogRow> =
        logRows(ctx).filter { it.space.equals(space, true) && it.at >= sinceMs }.sortedByDescending { it.at }

    /**
     * The box was NOT ticked, so the order runs at once. Recorded anyway, with
     * what the gate would have said — network, so only ever called off the
     * page's thread. It never holds the order back.
     */
    fun notAsked(ctx: Context, o: Order) {
        val outcome = "box not ticked — started at once"
        val key = jKey(jNewId())
        if (!MarketHours.isRegularSession(o.ns, System.currentTimeMillis())) {
            logAdd(ctx, logOf(o, false, outcome, "the market was shut"))
            RvJournal.event(ctx, "first", key, jBase(o, o.ticker, false, "page") + ("marketShut" to true))
            RvJournal.event(ctx, "release", key, mapOf("why" to outcome, "waitedMin" to 0L))
            return
        }
        val config = ConfigManager(ctx)
        val ticker = o.ticker.ifBlank { resolve(config, o.symbol, o.space) }
        val (gate, rd) = try {
            RvGate.check(config, ticker, o.symbol, o.ns,
                if (o.isSell) RvGate.Side.SELL else RvGate.Side.BUY, 0L)
        } catch (e: Exception) { RvGate.Decision(true, "gate failed: ${e.message}") to null }
        // The control group: what the gate would have said, and the price it ran at.
        RvJournal.event(ctx, "first", key, jBase(o, ticker, false, "page") + RvJournal.reading(rd) +
            mapOf("gateWouldGo" to gate.go, "gateWhy" to gate.why))
        RvJournal.event(ctx, "release", key, RvJournal.reading(rd) + mapOf("why" to outcome, "waitedMin" to 0L))
        logAdd(ctx, logOf(o, false, outcome,
            (if (gate.go) "the gate would have started it too — " else "the gate would have held it — ") + gate.why,
            RvGate.snapshot(rd, gate)))
    }

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
    /** [submit]'s answer when a watchlist Buy has no signal standing to wait on.
     *  Not a GO from the gate: the page must not announce a buy as started. */
    const val NO_SIGNAL = "no entry signal standing to wait on"

    fun submit(ctx: Context, asked: Order): Triple<Boolean, String, Long> {
        // A WATCHLIST BUY IS A BUY ON ONE SIGNAL, so the signal is pinned now. The
        // buy re-checks it when it runs, and on intraday bars a signal is off the
        // newest bar long before the gate's two hours are up: every held watchlist
        // buy came back "the setup has passed" and nothing reached Trading 212
        // (G24, VH2, 21 Oct). With no signal standing there is nothing to wait
        // for — the button runs at once and gives its own answer.
        val o = if (asked.kind == Kind.WATCH_BUY && !asked.force) {
            val ts = watchSignalTs(ctx, asked)
            if (ts == null) {
                logAdd(ctx, logOf(asked, true, "not held", "no entry signal standing — the button answers at once"))
                return Triple(false, NO_SIGNAL, 0L)
            }
            asked.copy(signalTs = ts)
        } else asked
        val config = ConfigManager(ctx)
        val now = System.currentTimeMillis()
        val open = MarketHours.isRegularSession(o.ns, now)
        if (open) {
            val ticker = o.ticker.ifBlank { resolve(config, o.symbol, o.space) }
            val (gate, rd) = try {
                RvGate.check(config, ticker, o.symbol, o.ns,
                    if (o.isSell) RvGate.Side.SELL else RvGate.Side.BUY, 0L)
            } catch (e: Exception) { RvGate.Decision(true, "gate failed: ${e.message}") to null }
            if (gate.go) {
                logAdd(ctx, logOf(o, true, "started at once", gate.why, RvGate.snapshot(rd, gate)))
                RvJournal.look(ctx, jKey(jNewId()), jBase(o, ticker, true, "page"), true, 0L, gate, rd)
                return Triple(false, gate.why, 0L)
            }
            val ref = rd?.price?.takeIf { it > 0.0 } ?: o.refPrice
            val q = o.copy(id = "rv${now}${(Math.random() * 1000).toInt()}", createdAt = now,
                ticker = ticker, status = "WAITING", why = gate.why, rvNote = RvGate.snapshot(rd, gate),
                rvPeak = gate.memory?.peak ?: 0.0, rvTrough = gate.memory?.trough ?: 0.0,
                capRef = if (o.isSell) 0.0 else ref, capLimit = if (o.isSell) 0.0 else limitFor(ref, rd?.atr ?: 0.0))
            save(ctx, all(ctx) + q)
            logAdd(ctx, logOf(o, true, "held", gate.why, q.rvNote))
            RvJournal.look(ctx, jKey(q.id), jBase(q, ticker, true, "queue"), true, 0L, gate, rd)
            RvGate.ensureTick(ctx)
            return Triple(true, gate.why, now + RvGate.MAX_WAIT_MS)
        }
        // A shut market: the order waits for the open, and the two hours start then.
        // A buy's limit is measured from the last price now — a gap up at the open
        // of more than half a day's move is exactly the chase it is there to stop.
        val (ref, atr) = if (o.isSell) 0.0 to 0.0 else try {
            val tk = o.ticker.ifBlank { resolve(config, o.symbol, o.space) }
            val r = RvGate.read(config, tk, o.symbol, Spaces.baseOf(o.ns))
            (r.price?.takeIf { it > 0.0 } ?: o.refPrice) to r.atr
        } catch (_: Exception) { o.refPrice to 0.0 }
        val q = o.copy(id = "rv${now}${(Math.random() * 1000).toInt()}", createdAt = now,
            status = "WAITING", why = "the market is closed — the RV gate starts looking at the open",
            capRef = if (ref > 0.0) ref else 0.0, capLimit = limitFor(ref, atr))
        save(ctx, all(ctx) + q)
        logAdd(ctx, logOf(o, true, "held for the open", q.why))
        RvJournal.event(ctx, "queued", jKey(q.id), jBase(q, q.ticker, true, "queue") +
            mapOf("price" to ref.takeIf { it > 0.0 }, "marketShut" to true))
        return Triple(true, q.why, 0L)
    }

    /**
     * A BUY THE APP STARTS ON YOUR BEHALF — a "buy at my price" whose price was
     * reached ([PriceEntries]). Exactly what the button does with the RV gate box
     * ticked: [submit] asks the gate; held, the order waits in this queue like any
     * other (its price limit, the two-hour cap, the notification when it runs);
     * let go at once, it runs here and now — where the page would have run the
     * button's own order — and you are notified as for a released order, since
     * nobody is looking at the page. Blocking: a ladder takes minutes.
     * @return what happened, in a sentence, and whether anything was bought.
     */
    fun submitAndRun(ctx: Context, o: Order): RunOutcome {
        val (queued, why, by) = submit(ctx, o)
        if (queued) return RunOutcome("held by the RV gate — $why" +
            (if (by > 0L) "; it starts by ${hhmm(by)} at the latest" else ""), held = true)
        if (why == NO_SIGNAL) return RunOutcome("not started — $why", nothingPlaced = true)
        val (result, fill) = try { run(ctx, ConfigManager(ctx), o) }
                          catch (e: Exception) { Log.e(TAG, "${o.symbol} ${o.kind} failed", e); "failed: ${e.message}" to emptyMap() }
        try { LabNotifier.rvManualDone(ctx, o.space, o.symbol, !o.isSell, false, why, result, wentThrough(result)) }
        catch (_: Exception) {}
        // A share filled or an order id kept means something reached the broker,
        // whatever the sentence says.
        val filled = ((fill["qty"] as? Number)?.toDouble() ?: 0.0) > 0.0 ||
            (fill["orderIds"] as? Collection<*>)?.isNotEmpty() == true
        return RunOutcome("started at once ($why) — $result", bought = wentThrough(result) || filled,
            nothingPlaced = !filled && nothingPlaced(result))
    }

    /**
     * What [submitAndRun] did. [held]: the gate is holding it — this queue owns it
     * now. [bought]: something was bought. [nothingPlaced]: sure that NO order
     * reached Trading 212 — the only case a retry cannot buy twice.
     */
    data class RunOutcome(val text: String, val held: Boolean = false,
                          val bought: Boolean = false, val nothingPlaced: Boolean = false)

    /** Pure: does [result] say no order reached the broker? "nothing bought" (the
     *  ladder filled nothing and took its rungs back), or a failure that says
     *  nothing was placed. Any other failure may have left an order behind. The
     *  caller also checks the fill itself. */
    internal fun nothingPlaced(result: String): Boolean =
        result.startsWith("nothing bought") ||
            (result.startsWith("failed") && result.contains("nothing placed"))

    private fun hhmm(ms: Long): String = try {
        java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalTime().toString().take(5)
    } catch (_: Exception) { "" }

    fun cancel(ctx: Context, id: String): Boolean {
        val o = all(ctx).firstOrNull { it.id == id && (it.status == "WAITING" || it.status == "ASK") } ?: return false
        update(ctx, o.id) { it.copy(status = "CANCELLED", result = "called off by you") }
        logAdd(ctx, logOf(o, true, "called off", "by you, before it started"))
        RvJournal.event(ctx, "end", jKey(o.id), mapOf("why" to "called off by you"))
        return true
    }

    /** The latest a waiting order will start: two trading hours after it began
     *  waiting, counted from today's open at the earliest. */
    fun startsBy(o: Order, now: Long = System.currentTimeMillis()): Long {
        val open = RvGate.todayOpenMs(o.ns, now)
        return maxOf(o.createdAt, open) + RvGate.MAX_WAIT_MS
    }

    /** The entry bar of a watchlist name's signal as it stands now, or null when
     *  there is none — the same question the watchlist's Buy asks. Network. */
    private fun watchSignalTs(ctx: Context, o: Order): Long? = try {
        val base = Spaces.baseOf(o.ns)
        val sym = o.symbol.trim().uppercase()
        val since = com.signalscope.app.data.ManualWatchStore.load(ctx, base).watchingSince[sym] ?: 0L
        StrategyLabRunner.freshEntrySignal(ctx, com.signalscope.app.data.ManualWatchStore.nsFor(base), sym,
            Spaces.exchangeOf(base), since)?.entryTs
    } catch (e: Exception) { Log.w(TAG, "${o.symbol} signal check failed: ${e.message}"); null }

    /** Pure: an India buy Zerodha accepted that owns no share yet. */
    internal fun indiaPlaced(immediate: Boolean, note: String): String =
        (if (immediate) "order placed at Zerodha, filling" else "buy trigger resting at Zerodha — nothing bought yet") +
            (if (note.isNotBlank()) ": $note" else "")

    /** Did the order do what it was for? Read off [run]'s sentence. */
    private fun wentThrough(result: String) =
        !listOf("nothing ", "not taken", "failed", "unknown").any { result.startsWith(it) }

    private fun resolve(config: ConfigManager, symbol: String, space: String): String = try {
        // India trades on Zerodha by the symbol itself.
        if (space.equals("IN", true)) symbol.uppercase().removeSuffix(".NS")
        else Trading212Client(config).resolveT212Ticker(symbol.uppercase().removeSuffix(".DE"), space) ?: ""
    } catch (_: Exception) { "" }

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
            val mem = if (o.rvPeak > 0.0) RvGate.Memory(o.rvPeak, o.rvTrough) else null
            val (gate, rd) = try {
                RvGate.check(config, ticker, o.symbol, o.ns,
                    if (o.isSell) RvGate.Side.SELL else RvGate.Side.BUY, o.createdAt, memory = mem)
            } catch (e: Exception) { RvGate.Decision(true, "gate failed: ${e.message}") to null }
            RvJournal.look(ctx, jKey(o.id), jBase(o, ticker, true, "queue"), firstLook = o.rvNote.isBlank(),
                since = maxOf(o.createdAt, RvGate.todayOpenMs(o.ns, now)), d = gate, rd = rd)
            if (!gate.go) {
                update(ctx, o.id) { it.copy(why = gate.why, ticker = ticker, rvNote = RvGate.snapshot(rd, gate),
                    rvPeak = gate.memory?.peak ?: it.rvPeak, rvTrough = gate.memory?.trough ?: it.rvTrough) }
                continue
            }
            // YOUR PRICE LIMIT. Set at the first look if it is not known yet (an
            // order placed on a shut market, or before this rule existed).
            if (!o.isSell) {
                val price = rd?.price
                val ref = if (o.capRef > 0.0) o.capRef else (price ?: 0.0)
                val limit = if (o.capLimit > 0.0) o.capLimit else limitFor(ref, rd?.atr ?: 0.0)
                if (overLimit(price, limit)) {
                    val why = "it ran to %.2f — more than half a day's move above %.2f, where you asked (limit %.2f)"
                        .format(price, ref, limit)
                    update(ctx, o.id) { it.copy(status = "ASK", askedAt = now, ticker = ticker,
                        capRef = ref, capLimit = limit, why = why, rvNote = RvGate.snapshot(rd, gate)) }
                    logAdd(ctx, logOf(o, true, "asked you — over its price limit", why, RvGate.snapshot(rd, gate)))
                    RvJournal.event(ctx, "ask", jKey(o.id), mapOf("price" to price, "ref" to ref, "limit" to limit))
                    try { LabNotifier.rvManualAsk(ctx, o.space, o.symbol, ref, price ?: 0.0, limit) } catch (_: Exception) {}
                    continue
                }
            }
            update(ctx, o.id) { it.copy(status = "STARTED", why = gate.why, ticker = ticker) }
            Log.i(TAG, "${o.symbol} ${o.kind} released by the RV gate: ${gate.why}")
            val (result, fill) = try { run(ctx, config, o.copy(ticker = ticker)) }
                         catch (e: Exception) { Log.e(TAG, "${o.symbol} ${o.kind} failed", e); "failed: ${e.message}" to emptyMap() }
            // NOTHING PLACED BECAUSE THE HOLDING COULD NOT BE READ (Trading 212 refusing
            // too-frequent requests) is not an answer: back to waiting, tried again on the
            // next look. NDX1, 4 Nov 2026, was dropped this way after the gate let it go.
            if (result.contains(UNREAD) && MarketHours.isRegularSession(o.ns, System.currentTimeMillis())) {
                update(ctx, o.id) { it.copy(status = "WAITING", why = "holding unreadable at Trading 212 — trying again") }
                Log.w(TAG, "${o.symbol} ${o.kind}: holding unreadable — back to waiting")
                continue
            }
            update(ctx, o.id) { it.copy(status = "DONE", result = result) }
            jOutcome(ctx, jKey(o.id), result, fill)
            val heldMin = (System.currentTimeMillis() - maxOf(o.createdAt, RvGate.todayOpenMs(o.ns))) / 60_000L
            logAdd(ctx, logOf(o, true,
                if (gate.timedOut) "started at the 2-hour limit" else "started after ${heldMin.coerceAtLeast(0L)} min",
                "${gate.why} — $result", RvGate.snapshot(rd, gate)))
            try { LabNotifier.rvManualDone(ctx, o.space, o.symbol, !o.isSell, gate.timedOut, gate.why, result,
                wentThrough(result)) }
            catch (_: Exception) {}
        }
    }

    /**
     * YOU SAID BUY ANYWAY, to an order that stopped at its price limit. Runs the
     * button's order now — blocking, a ladder takes minutes, so off the page's
     * thread. Refused once the market has shut: the question was about today.
     * @return a sentence for the page.
     */
    fun buyAnyway(ctx: Context, id: String): String {
        val o = all(ctx).firstOrNull { it.id == id && it.status == "ASK" }
            ?: return "That order is no longer waiting for an answer."
        if (!MarketHours.isRegularSession(o.ns, System.currentTimeMillis())) {
            update(ctx, o.id) { it.copy(status = "CANCELLED", result = "the market had closed — nothing bought") }
            logAdd(ctx, logOf(o, true, "lapsed", "the market had closed before your answer"))
            RvJournal.event(ctx, "end", jKey(o.id), mapOf("why" to "lapsed — the market had closed before your answer"))
            return "The market has closed, so nothing was bought. Ask again at the next open."
        }
        update(ctx, o.id) { it.copy(status = "STARTED") }
        RvJournal.event(ctx, "release", jKey(o.id), mapOf("why" to "you said buy anyway",
            "waitedMin" to ((System.currentTimeMillis() - maxOf(o.createdAt, RvGate.todayOpenMs(o.ns))) / 60_000L).coerceAtLeast(0L)))
        val config = ConfigManager(ctx)
        val (result, fill) = try { run(ctx, config, o) }
                     catch (e: Exception) { Log.e(TAG, "${o.symbol} buy anyway failed", e); "failed: ${e.message}" to emptyMap() }
        update(ctx, o.id) { it.copy(status = "DONE", result = result) }
        jOutcome(ctx, jKey(o.id), result, fill)
        logAdd(ctx, logOf(o, true, "bought anyway, at your word", result))
        try { LabNotifier.rvManualDone(ctx, o.space, o.symbol, true, false, "you said buy anyway", result,
            wentThrough(result)) }
        catch (_: Exception) {}
        return result
    }

    /**
     * An order that asked and was not answered before its market closed lapses:
     * nothing bought, and said so. Called on every pass.
     */
    fun expireAsks(ctx: Context) {
        val now = System.currentTimeMillis()
        for (o in all(ctx).filter { it.status == "ASK" }) {
            if (MarketHours.isRegularSession(o.ns, now)) continue
            update(ctx, o.id) { it.copy(status = "CANCELLED", result = "not answered before the close — nothing bought") }
            logAdd(ctx, logOf(o, true, "lapsed", "not answered before the close — nothing bought"))
            RvJournal.event(ctx, "end", jKey(o.id), mapOf("why" to "lapsed — not answered before the close"))
        }
    }

    /**
     * Exactly what the button would have run. Returns a sentence for the notice,
     * and what filled for the record: quantity, price when the route reports one,
     * order ids when it reports those (the price can be looked up by them later).
     */
    private const val UNREAD = "could not read your current holding"

    private fun run(ctx: Context, config: ConfigManager, o: Order): Pair<String, Map<String, Any?>> {
        val ys = o.yahooSymbol.ifBlank {
            com.signalscope.app.network.YahooFinanceClient.toYahooSymbol(o.symbol, o.space)
        }
        return when (o.kind) {
            Kind.T212_SELL -> {
                val r = PatientExecutor.sell(config, o.ticker, ys, o.space, o.qty, o.refPrice)
                val fill = mapOf("qty" to r.filled, "orderIds" to r.orderIds.takeIf { it.isNotEmpty() },
                    "ladderFrom" to r.refPrice.takeIf { it > 0.0 })
                (if (r.error != null) "nothing sold: ${r.error}" else {
                    val handled = if (r.filled > 0.0) try {
                        T212HandSales.afterAppSale(ctx, r.ticker, r.orderIds, r.refPrice)
                    } catch (_: Exception) { "" } else ""
                    (if (r.filled > 0.0) "sold ${"%.4f".format(r.filled)}" +
                        (if (r.remaining > 0.0) ", ${"%.4f".format(r.remaining)} left unsold" else "")
                     else "nothing sold: ${r.note.ifBlank { "the ladder found no buyer" }}") +
                        (if (handled.isNotBlank()) ". $handled" else "")
                }) to fill
            }
            Kind.T212_BUY -> {
                val r = PatientExecutor.buy(config, o.symbol, ys, o.space, o.cash, o.refPrice)
                val fill = mapOf("qty" to r.filled, "orderIds" to r.orderIds.takeIf { it.isNotEmpty() },
                    "ladderFrom" to r.refPrice.takeIf { it > 0.0 })
                (if (r.error != null) "nothing bought: ${r.error}"
                 else if (r.filled > 0.0) "bought ${"%.4f".format(r.filled)}"
                 else "nothing bought: ${r.note.ifBlank { "the ladder filled nothing" }}") to fill
            }
            Kind.DETAIL_BUY, Kind.WATCH_BUY -> {
                val r = ManualEntryWatcher.buyAndHandOver(ctx, o.ns, o.symbol, o.cash, null,
                    force = o.kind == Kind.DETAIL_BUY || o.force, signalTs = o.signalTs)
                val fill = mapOf("qty" to r.filledQty, "price" to r.filledPrice.takeIf { it > 0.0 },
                    "signal" to r.signalPrice.takeIf { it > 0.0 })
                (if (r.ok && r.toppedUp) "added ${"%.4f".format(r.filledQty)} to your watchlist position"
                 else if (r.ok && r.filledQty > 0.0) "bought ${"%.4f".format(r.filledQty)} and handed to your watchlist"
                 // INDIA: an order accepted at Zerodha that has not filled yet, or a
                 // resting trigger — placed, not "nothing bought".
                 else if (r.ok) indiaPlaced(r.immediate, r.note)
                 else "nothing bought: ${r.error.ifBlank { r.note.ifBlank { "the ladder filled nothing" } }}") to fill
            }
            Kind.ALGO_TP -> {
                val r = AlgoTakeProfit.takeNow(ctx, o.algoNs, o.symbol)
                val fill: Map<String, Any?> = if (r.ok && r.real) mapOf("price" to r.exitPrice.takeIf { it > 0.0 }) else emptyMap()
                (if (!r.ok) "not taken: ${r.error}"
                 else "sold at ${"%.2f".format(r.exitPrice)}, booked to the algo at " +
                    "${"%+.2f".format(r.pctReturn)}%" + (if (r.note.isNotBlank()) ". ${r.note}" else "")) to fill
            }
            else -> "unknown order kind ${o.kind}" to emptyMap()
        }
    }
}

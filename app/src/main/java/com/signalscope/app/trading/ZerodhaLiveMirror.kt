package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.BucketStore
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.StrategyLabStore
import com.signalscope.app.data.ZerodhaLiveStore
import com.signalscope.app.network.ZerodhaClient

/**
 * PAPER SIGNAL → REAL ZERODHA ORDER, per algo.
 *
 * The Indian counterpart of [T212LiveMirror], and deliberately NOT a copy of it.
 * The patient ladder exists because Trading 212 has no server-side conditional
 * orders: the only way to hold out for a price there is to keep replacing a
 * limit order from inside the app, which means the protection dies whenever the
 * phone does. Zerodha has GTT — a trigger that lives at the broker — so the
 * Indian design is simpler and strictly safer:
 *
 *      entry  → one plain order
 *      then   → a GTT resting at the broker to protect it
 *      exit   → delete the GTT, then sell
 *
 * The protection survives the app being closed, the phone being off, and the
 * process being killed by Doze. That is not a small difference: the ladder's
 * whole failure mode is the app not being alive at the moment it matters.
 *
 * ── FOUR GATES, same as every other live path ─────────────────────────────
 *   1. master freeze          — one switch, defaults ON, blocks everything
 *   2. Settings mirror toggle — Zerodha algo orders enabled at all
 *   3. per-algo `live` flag   — this algo specifically
 *   4. per-algo allowlist     — this NAME specifically
 * All four must agree. Any one of them off means no order, silently and safely.
 *
 * ── WHOLE SHARES ──────────────────────────────────────────────────────────
 * NSE equities cannot be traded fractionally. A budget that does not reach one
 * share buys NOTHING, and the honest response is to say so rather than round up
 * into a position the algo never sized. This is the single biggest behavioural
 * difference from the German path, where €20 always buys something.
 */
object ZerodhaLiveMirror {

    private const val TAG = "ZerodhaLiveMirror"
    private const val EXCHANGE = "NSE"


    data class LiveCfg(val budget: Double, val allow: Set<String>, val seats: Int)

    /**
     * The per-algo half of the gate. Returns null when this algo must not trade,
     * which is the default for every algo that has not been explicitly armed.
     */
    private fun liveCfgFor(ctx: Context, ns: String): LiveCfg? {
        val b = BucketStore.get(ctx, ns) ?: return null
        if (!b.live) return null
        if (b.liveBudget <= 0.0) return null
        val allow = b.liveAllowlist.map { it.uppercase() }.toSet()
        if (allow.isEmpty()) return null              // blank = NONE, the safe default
        return LiveCfg(b.liveBudget, allow, b.holdN.coerceAtLeast(1))
    }

    private fun gatesOpen(ctx: Context, ns: String, symbol: String): LiveCfg? {
        // The two global gates that used to sit here are gone. They were a switch
        // in Settings and a master freeze, and neither told the truth once algos
        // carried their own arming — the crypto pair of them read OFF while real
        // orders were going out, because the algo path never consulted them.
        //
        // What remains is the per-algo gate, which is the stricter one anyway:
        // armed, funded, and this exact symbol on its allowlist. A blank allowlist
        // means NONE, so a newly created algo can never trade by default.
        val cfg = ConfigManager(ctx)
        if (!cfg.isZerodhaConnected) return null
        val live = liveCfgFor(ctx, ns) ?: return null
        if (symbol.uppercase() !in live.allow) return null
        return live
    }

    /**
     * A new paper BUY became real.
     *
     * Writes a PENDING row BEFORE placing anything. If the process dies between
     * the write and the broker's reply, the row is what stops the next pass
     * placing a second order for the same signal — the alternative is a
     * duplicate position with no record of the first.
     */
    fun mirrorEntry(ctx: Context, paperTradeId: String, symbol: String,
                    paperEntryPrice: Double, ns: String) {
        val sym = symbol.trim().uppercase()
        val live = gatesOpen(ctx, ns, sym) ?: return
        if (ZerodhaLiveStore.forTrade(ctx, ns, paperTradeId) != null) return   // already mirrored

        // ── THE SAME ROUTE THE APP'S OWN BUY BUTTON USES ─────────────────────
        //
        // This used to send a bare MARKET order, and Zerodha's API refuses those
        // outright: "Market orders without market protection are not allowed via
        // API." So every algo entry this space ever signalled was rejected at the
        // broker's door — four in a row on this account — while the algo card,
        // the ledger and the paper book all showed a perfectly healthy position.
        // Nothing was wrong upstream: the gate, the freshness rule, the allowlist,
        // the seat sizing and the connection were all working exactly as intended.
        //
        // [buyNowOrRest] is the routine the manual buy button has used all along,
        // and it is not manual-specific — it prices a LIMIT through the market so
        // it crosses the spread and fills like a market order but with a ceiling,
        // queues an after-market order when nothing is trading, and rests a
        // trigger when the account cannot send ordinary orders at all. That last
        // route is the one that works on an account with no static IP registered,
        // which is this one.
        //
        // WHY DELEGATE RATHER THAN COPY THE FIX. A second copy of "how to buy at
        // Zerodha" is how these two paths drifted apart in the first place: the
        // manual side learned about limits, after-market orders and triggers, and
        // the algo side was left on the one order type the broker will not accept.
        val perSeat = live.budget / live.seats
        val r = try {
            buyNowOrRest(ctx, ns, sym, perSeat, paperEntryPrice, paperTradeId)
        } catch (e: Exception) {
            Log.e(TAG, "[$ns] mirrorEntry $sym failed", e)
            ManualResult(false, error = e.message ?: "failed")
        }
        if (r.ok) {
            Log.w(TAG, "[$ns] REAL BUY $sym — ${if (r.immediate) "ordered" else "trigger resting"}" +
                ", ${r.qty} at ${"%.2f".format(r.price)}")
            return
        }
        // A REFUSAL MUST LAND SOMEWHERE THE USER LOOKS. The routine writes its own
        // row for failures that happen after an order is sent; the ones that stop
        // before that — a seat too small for one share, no price, not connected —
        // return without writing anything, and used to leave the card blank. A
        // blank card next to an armed switch reads as a broken switch.
        if (ZerodhaLiveStore.forTrade(ctx, ns, paperTradeId) == null) {
            ZerodhaLiveStore.upsert(ctx, ZerodhaLiveStore.Row(
                ns = ns, paperTradeId = paperTradeId, symbol = sym,
                status = "ERROR", paperEntryPrice = paperEntryPrice,
                buyAt = System.currentTimeMillis(),
                error = r.error.ifBlank { r.refusal.ifBlank { "the order was not placed" } }))
        }
        Log.e(TAG, "[$ns] BUY $sym not placed: ${r.error.ifBlank { r.refusal }}")
    }

    data class ManualResult(
        val ok: Boolean, val qty: Int = 0, val price: Double = 0.0,
        val orderId: String = "", val gttId: Long = 0L,
        val error: String = "", val note: String = "",
        /**
         * TRUE = an ordinary order went to the broker and the shares either exist
         * now or will within seconds. FALSE = a trigger is resting and NOTHING has
         * been bought or sold.
         *
         * The caller cannot infer this from anything else in here and must not
         * try. Treating a resting trigger as a position puts something in the book
         * that does not exist and starts measuring exits against it; treating a
         * real fill as a trigger leaves a live holding unprotected while the app
         * waits for a fill that already happened.
         */
        val immediate: Boolean = false,
        /** What the broker actually filled, and its own average price for it.
         *  0 while an accepted order is still working. */
        val filledQty: Int = 0,
        val filledPrice: Double = 0.0,
        /** The broker's words when it would not take an ordinary order. Kept even
         *  on a SUCCESSFUL fallback, because it is the only evidence of why this
         *  account is on the slower route. */
        val refusal: String = ""
    )

    // ═════════════════════════════════════════════════════════════════════════
    // TWO WAYS TO BUY, TRIED IN THAT ORDER
    // ═════════════════════════════════════════════════════════════════════════
    // 1. AN ORDINARY ORDER, PRICED THROUGH THE MARKET. A buy limit set above the
    //    offer crosses the spread and fills at once against whatever is resting
    //    on the book — the behaviour of a market order, with a ceiling on what a
    //    thin book can charge. This is what "buy now" means, and it is tried
    //    first on both sides.
    //
    // 2. A RESTING TRIGGER, only when the broker refuses the first. Zerodha does
    //    refuse ordinary orders from an address it has not been told about, and a
    //    phone on mobile data cannot supply a fixed one — but it accepts
    //    conditional triggers from anywhere, which is how every protective stop
    //    this app places has always worked.
    //
    // WHY BOTH RATHER THAN PICKING ONE. This file used to assert that ordinary
    // orders are always refused, while the algo entry path and the daily exit
    // ladder in the same app were sending them. Both claims cannot be true, and
    // no comment can settle it — the answer depends on the account. So the real
    // order is attempted and the broker's own refusal text is kept on the entry
    // when it comes back. The app finds out instead of assuming, and says so.
    //
    // THE TWO ROUTES ARE NOT INTERCHANGEABLE and every caller must know which one
    // ran:
    //   • an ordinary order fills now, so the position exists and can be
    //     protected and handed to the strategy in the same breath;
    //   • a trigger fills whenever the market reaches it, usually while the app
    //     is not running, so nothing is owned yet, no exit rule may measure
    //     anything, and protection has to wait until the fill is noticed.
    // ═════════════════════════════════════════════════════════════════════════

    /** NSE prices must sit on the instrument's tick. Most are 0.05; a few are
     *  coarser, and those are learned from the broker's own rejection elsewhere
     *  in the app. Rounded twice — the second pass clears the binary dust that
     *  multiplying back by a tick leaves behind. */
    private fun onTick(v: Double, tick: Double): Double {
        if (tick <= 0.0) return v
        return Math.round((Math.round(v / tick) * tick) * 100.0) / 100.0
    }

    /**
     * THE BROKER NAMES THE TICK IN ITS OWN REFUSAL — so read it and remember it.
     *
     * The app carries no instrument table, and most NSE equities tick at 0.05, so
     * that was the assumption everywhere. It is wrong often enough to matter:
     * SYRMA, FINCABLES and GMMPFAUDLR all tick at 0.10, and a price rounded to
     * 0.05 lands on a legal multiple of 0.10 only half the time. Which is exactly
     * what was seen — the algo's order for SYRMA was refused at 06:49 and the
     * hand-placed one for the same stock went through at 09:10. Not a difference
     * between the two paths: a coin flip, twice.
     *
     * A learning mechanism for this already existed and the live mirror was never
     * wired into it, so the mirror re-made the same rejected price on every pass,
     * for ever. One rejection is now enough to fix the instrument permanently.
     *
     * Matches both phrasings Kite uses — "tick size 0.10" and "Tick size for this
     * script is 0.10" — because it has been seen to send each.
     */
    private fun learnTickFrom(cfg: ConfigManager, sym: String, message: String?): Double? {
        if (message.isNullOrBlank()) return null
        val m = Regex("tick size(?:\\s+for this script)?\\s*(?:is\\s*)?[:\\s]\\s*([0-9]*\\.?[0-9]+)",
            RegexOption.IGNORE_CASE).find(message)
            ?: Regex("multiple of tick size\\s*([0-9]*\\.?[0-9]+)",
                RegexOption.IGNORE_CASE).find(message)
        val tick = m?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: return null
        if (cfg.learnedTickSize(sym) == tick) return tick
        cfg.rememberTickSize(sym, tick)
        Log.w(TAG, "learned tick size $tick for $sym from the broker's refusal")
        return tick
    }

    /** True when a refusal is only about the price grid — worth retrying at once
     *  on the corrected tick, unlike every other refusal. */
    private fun refusedForTickSize(message: String?): Boolean =
        message != null && message.contains("tick size", true)

    /**
     * Create a resting trigger, re-pricing once if the broker rejects the grid.
     *
     * THE PROTECTIVE STOP HAS THE SAME PROBLEM AS THE ENTRY and it matters more:
     * an entry refused on a tick leaves you flat, a STOP refused on a tick leaves
     * a real position with nothing behind it. FINCABLES was bought and its
     * protection refused with "Trigger price should be a multiple of tick size
     * 0.1" — a holding left bare on a rounding convention.
     */
    private fun createGttWithTick(
        cfg: ConfigManager, client: ZerodhaClient, sym: String, type: String,
        triggers: List<Double>, lastPrice: Double,
        orders: List<ZerodhaClient.GttOrderSpec>
    ): ZerodhaClient.GttCreateResult {
        var res = client.createGttTrigger(type, EXCHANGE, sym, triggers, lastPrice, orders)
        val f = res as? ZerodhaClient.GttCreateResult.Failure
        if (f != null && refusedForTickSize(f.message)) {
            val tick = learnTickFrom(cfg, sym, f.message)
            if (tick != null) {
                val t2 = triggers.map { onTick(it, tick) }
                val o2 = orders.map { it.copy(price = onTick(it.price, tick)) }
                if (t2 != triggers || o2 != orders) {
                    Log.w(TAG, "$sym: re-placing the trigger on a $tick grid")
                    res = client.createGttTrigger(type, EXCHANGE, sym, t2, lastPrice, o2)
                }
            }
        }
        return res
    }

    /** How far THROUGH the market an immediate order is priced. This is never
     *  what it pays — only the worst it will accept before the order would sit
     *  unfilled, which is the one outcome this route exists to avoid. */
    private const val IMMEDIATE_THROUGH = 0.003     // 0.3%

    /**
     * How far through the market an AFTER-MARKET order is priced.
     *
     * Wider than the intraday figure on purpose. That one works off a live tick
     * seconds old; this one works off a close that is already hours stale, and
     * the first minutes of an Indian session move further than the middle of one.
     * A cushion that is right at midday is too thin at the open.
     */
    private const val AMO_THROUGH = 0.004           // 0.4%

    /** How long to keep asking whether the immediate order filled. A limit priced
     *  through the book normally completes on the first look; this is for the
     *  case where it goes in as several partial fills. */
    private const val FILL_POLL_TRIES = 6
    private const val FILL_POLL_GAP_MS = 900L

    /**
     * Did the broker turn this down purely because nothing is trading yet?
     *
     * Only that refusal earns an after-market retry. Every other kind — no funds,
     * a price outside the band, a name that cannot be dealt — would be refused
     * again on the same terms in the morning, or worse, accepted and filled at an
     * open the user never agreed to. So this is deliberately narrow, and anything
     * it does not recognise falls through to the resting trigger exactly as before.
     */
    private fun refusedForMarketHours(message: String): Boolean {
        val m = message.lowercase()
        if (m.contains("amo")) return true                     // Kite's own hint
        if (m.contains("trading hours") || m.contains("market hours")) return true
        return m.contains("market") && (m.contains("clos") || m.contains("not open"))
    }

    private data class Instant(
        /** FALSE means the broker REFUSED it and the trigger route should run.
         *  An accepted order that has not finished filling is still placed. */
        val placed: Boolean,
        val orderId: String = "",
        val filledQty: Int = 0,
        val avgPrice: Double = 0.0,
        val status: String = "",
        val refusal: String = "",
        /** FALSE = the broker never answered, so nobody knows whether the order
         *  is live. Neither route may run after this; see [ZerodhaClient.OrderResult.Failure]. */
        val answered: Boolean = true
    ) {
        /** Filled, priced and finished — safe to treat as a real position. */
        val complete: Boolean
            get() = placed && status == "COMPLETE" && filledQty > 0 && avgPrice > 0.0
    }

    /**
     * Send a real order and watch it for a few seconds.
     *
     * ONLY A REFUSAL FALLS BACK. An order the broker accepted but has not
     * finished filling must never be re-sent down the trigger route — that buys
     * or sells the same thing twice, and the second one would be invisible to the
     * entry that believes it placed one order.
     */
    private fun placeImmediate(client: ZerodhaClient, side: String, sym: String,
                               qty: Int, limit: Double, cfg: ConfigManager? = null): Instant {
        var res = client.placeOrder(EXCHANGE, sym, side, qty, "LIMIT", limit)
        // ONE RETRY, AND ONLY FOR THE PRICE GRID. A tick-size refusal is the one
        // rejection that carries its own fix inside it: the broker says what the
        // tick is, so the same order re-priced on that grid is the order that was
        // always meant. Every other refusal — no funds, no permission, market
        // shut — is answered by the routes below, not by trying again harder.
        if (res is ZerodhaClient.OrderResult.Failure && cfg != null &&
            refusedForTickSize(res.message)) {
            val tick = learnTickFrom(cfg, sym, res.message)
            if (tick != null) {
                val fixed = onTick(limit, tick)
                if (fixed > 0.0 && fixed != limit) {
                    Log.w(TAG, "$side $sym: re-pricing ${"%.2f".format(limit)} → " +
                        "${"%.2f".format(fixed)} on the tick the broker just named")
                    res = client.placeOrder(EXCHANGE, sym, side, qty, "LIMIT", fixed)
                }
            }
        }
        val id = when (val r = res) {
            is ZerodhaClient.OrderResult.Success -> r.orderId
            is ZerodhaClient.OrderResult.Failure -> {
                Log.w(TAG, "$side $sym: ordinary order did not go through — ${r.message} " +
                    "(broker answered: ${r.answered})")
                return Instant(placed = false, refusal = r.message, answered = r.answered)
            }
        }
        var last: ZerodhaClient.OrderFill? = null
        for (i in 0 until FILL_POLL_TRIES) {
            try { Thread.sleep(FILL_POLL_GAP_MS) } catch (e: InterruptedException) { break }
            last = client.fetchOrderFill(id) ?: continue
            if (last.status == "COMPLETE" || last.status == "REJECTED" ||
                last.status == "CANCELLED") break
        }
        // A REJECTION AFTER ACCEPTANCE IS STILL A REFUSAL. Zerodha takes some
        // orders at the door and the exchange rejects them a moment later, which
        // leaves nothing bought — so it falls back exactly like a door refusal.
        // Only when NOTHING filled: a partial fill is a real position, and a
        // second route on top of it would double the trade.
        if (last != null && last.status == "REJECTED" && last.filledQty == 0)
            return Instant(placed = false,
                refusal = last.message.ifBlank { "the exchange rejected it" })
        Log.w(TAG, "$side $qty $sym order $id — ${last?.status ?: "no status yet"}, " +
            "filled ${last?.filledQty ?: 0} at ${"%.2f".format(last?.avgPrice ?: 0.0)}")
        return Instant(placed = true, orderId = id,
            filledQty = last?.filledQty ?: 0, avgPrice = last?.avgPrice ?: 0.0,
            status = last?.status ?: "")
    }

    /**
     * The freshest price available, or 0.0 when there is none.
     *
     * A live tick from the last minute beats a fetched quote. The signal bar's
     * close is NOT an acceptable fallback here — it can be hours old, and this is
     * the number a real order is built around. Stale in, wrong order out.
     */
    private fun referencePrice(client: ZerodhaClient, sym: String): Double {
        val mark = try { MarkToMarket.get(sym) } catch (e: Exception) { null }
        if (mark != null && mark.price > 0.0 &&
            (System.currentTimeMillis() - mark.atMs) < 60_000L) return mark.price
        return when (val r = client.fetchLtp(EXCHANGE, sym)) {
            is ZerodhaClient.LtpResult.Success -> r.lastPrice
            else -> 0.0
        }
    }

    /** How far above the market a FALLBACK buy trigger rests. Comfortably clear
     *  of the 0.25% the broker enforces, without paying away much of the entry.
     *  Only reached when an ordinary order was refused. */
    private const val BUY_TRIGGER_OVER = 0.004      // +0.4%
    /** The limit the triggered order rests at, above its own trigger, so a fast
     *  move through the level still fills instead of leaving an order behind. */
    private const val BUY_LIMIT_OVER = 0.006        // +0.6% on top of the trigger

    /**
     * Buy an Indian name. An ordinary order if the broker will take one, an
     * after-market order when nothing is trading, a resting trigger if it will
     * take neither — see the block above for why all three exist and why
     * [ManualResult.immediate] is not optional reading.
     *
     * NOT MANUAL-SPECIFIC, despite where it grew up. It was called manualBuy and
     * used only by the buy button, while the ALGO mirror kept its own copy that
     * sent a plain market order — an order type Zerodha's API refuses outright.
     * So the button worked and every algo entry was rejected at the door. One
     * routine now, because "how to buy at Zerodha" is one question with one
     * answer, and two copies of it is how those two paths silently diverged.
     */
    fun buyNowOrRest(ctx: Context, ns: String, symbol: String, cash: Double,
                     paperEntryPrice: Double, paperTradeId: String): ManualResult {
        val sym = symbol.trim().uppercase()
        val cfg = ConfigManager(ctx)
        if (!cfg.isZerodhaConnected) return ManualResult(false, error = "Zerodha is not connected.")
        if (ZerodhaLiveStore.forTrade(ctx, ns, paperTradeId) != null)
            return ManualResult(false, error = "This entry already has an order.")
        // NOT INTO A PRICE FEED THAT HAS STOPPED — the same refusal Trading 212's
        // buys make (PatientExecutor). The order below is priced off the broker,
        // but the signal that asked for it was drawn on the feed, and every exit
        // the app would then watch reads the feed too.
        val stoppedAt = com.signalscope.app.network.FeedHealth.stoppedSince(sym, "NSE")
        if (stoppedAt > 0L) {
            val fs = com.signalscope.app.network.YahooFinanceClient.toYahooSymbol(sym, "NSE")
            try { LabNotifier.feedStopped(ctx, "in", sym, fs, stoppedAt, held = false) }
            catch (e: Exception) { Log.w(TAG, "feed alarm failed", e) }
            return ManualResult(false, error = "Not buying $sym: its price feed ($fs) has shown " +
                "no trade since ${com.signalscope.app.network.FeedHealth.describe(stoppedAt)} although " +
                "the market is open. Nothing was placed.")
        }

        val client = ZerodhaClient(cfg)
        return try { run route@{
            // ── THE REFERENCE PRICE DECIDES EVERYTHING BELOW ─────────────────
            // A stale one does not produce a slightly-off order; it produces an
            // order built around a level the market has already left, which then
            // fills somewhere unintended or not at all.
            val ref = referencePrice(client, sym)
            if (ref <= 0.0) return ManualResult(false,
                error = "No current price for $sym — the broker did not answer and no live tick " +
                        "has arrived. Nothing was placed; try again in a moment rather than " +
                        "sending an order priced off a guess.")
            val tick = cfg.learnedTickSize(sym) ?: 0.05

            // ── ROUTE 1: AN ORDINARY ORDER, PRICED TO FILL NOW ───────────────
            val through = onTick(ref * (1.0 + IMMEDIATE_THROUGH), tick)
            // SIZED ON THE CEILING, not on the last price. That is the most this
            // can cost per share, and sizing on anything cheaper risks a fill that
            // spends more than was asked for.
            val qtyNow = Math.floor(cash / through).toInt()
            if (qtyNow < 1) return ManualResult(false,
                error = "₹${"%.0f".format(cash)} is under one share at ₹${"%.2f".format(through)} — " +
                        "NSE has no fractional shares.")

            // Recorded BEFORE anything is sent, so a process death in between
            // cannot leave a live order nothing in the app knows about.
            ZerodhaLiveStore.upsert(ctx, ZerodhaLiveStore.Row(
                ns = ns, paperTradeId = paperTradeId, symbol = sym,
                status = "PENDING", qty = qtyNow, paperEntryPrice = paperEntryPrice,
                buyAt = System.currentTimeMillis()))

            val now = placeImmediate(client, "BUY", sym, qtyNow, through, cfg)
            if (now.placed) {
                val paid = if (now.avgPrice > 0.0) now.avgPrice else through
                ZerodhaLiveStore.upsert(ctx, ZerodhaLiveStore.Row(
                    ns = ns, paperTradeId = paperTradeId, symbol = sym,
                    status = if (now.complete) "OPEN" else "PENDING",
                    qty = if (now.filledQty > 0) now.filledQty else qtyNow,
                    buyOrderId = now.orderId,
                    entryPrice = if (now.complete) paid else 0.0,
                    paperEntryPrice = paperEntryPrice,
                    buyAt = System.currentTimeMillis()))
                if (now.complete) {
                    Log.w(TAG, "[$ns] REAL BUY $sym — ${now.filledQty} at ${"%.2f".format(paid)} " +
                        "(order ${now.orderId}, live price at the tap ${"%.2f".format(ref)})")
                    attachProtectiveGtt(ctx, ns, paperTradeId, sym, now.filledQty, paid)
                    val gtt = ZerodhaLiveStore.forTrade(ctx, ns, paperTradeId)?.gttId ?: 0L
                    return@route ManualResult(true, qty = now.filledQty, price = paid,
                        orderId = now.orderId, gttId = gtt, immediate = true,
                        filledQty = now.filledQty, filledPrice = paid,
                        note = "Bought ${now.filledQty} at ₹${"%.2f".format(paid)}. " +
                               "The live price when you tapped was ₹${"%.2f".format(ref)}." +
                               (if (gtt > 0L) " A protective stop is on it."
                                else " ⚠ The protective stop was NOT created — this holding has no stop."))
                }
                // ACCEPTED BUT STILL WORKING. Not a position yet, and deliberately
                // not re-sent down the other route: the order is live.
                return@route ManualResult(true, qty = qtyNow, price = through,
                    orderId = now.orderId, immediate = true,
                    note = "Order ${now.orderId} is live for $qtyNow $sym, paying up to " +
                           "₹${"%.2f".format(through)} — the live price when you tapped was " +
                           "₹${"%.2f".format(ref)}. It has not finished filling yet; the app picks " +
                           "up the position and protects it as soon as it does.")
            }

            if (!now.answered) {
                // NEITHER ROUTE. Zerodha never answered, so the order may be live
                // and a trigger on top of it would be a second instruction for the
                // same tap. Doing nothing and saying so is the only safe reply.
                ZerodhaLiveStore.forTrade(ctx, ns, paperTradeId)?.let {
                    ZerodhaLiveStore.upsert(ctx, it.copy(status = "ERROR",
                        error = "no answer from Zerodha: ${now.refusal}"))
                }
                return@route ManualResult(false, refusal = now.refusal,
                    error = "Zerodha did not answer the order for $sym (${now.refusal}). " +
                            "Nothing further was placed on purpose — the order may have reached " +
                            "them, and a second instruction could buy $sym twice. Check your " +
                            "order list in Kite before trying again.")
            }

            // ── ROUTE 2: NOTHING IS TRADING YET. Queue it for the open. ──────
            //
            // This is the route that matters to someone in Europe, where the
            // Indian open lands before breakfast. Until it existed, every tap
            // outside market hours became a trigger parked 0.4% ABOVE the market,
            // which only buys if the stock RISES to it — a breakout entry nobody
            // asked for, standing in for the order they actually wanted.
            //
            // An after-market order is the real thing: it goes on the book at the
            // open and fills on its own terms, at a price capped here.
            if (refusedForMarketHours(now.refusal)) {
                val amoLimit = onTick(ref * (1.0 + AMO_THROUGH), tick)
                val amoQty = Math.floor(cash / amoLimit).toInt()
                if (amoQty >= 1) {
                    when (val res = client.placeOrder(
                        EXCHANGE, sym, "BUY", amoQty, "LIMIT", amoLimit, variety = "amo")
                    ) {
                        is ZerodhaClient.OrderResult.Success -> {
                            ZerodhaLiveStore.upsert(ctx, ZerodhaLiveStore.Row(
                                ns = ns, paperTradeId = paperTradeId, symbol = sym,
                                status = "PENDING", qty = amoQty, buyOrderId = res.orderId,
                                entryPrice = 0.0, paperEntryPrice = paperEntryPrice,
                                buyAt = System.currentTimeMillis()))
                            Log.w(TAG, "[$ns] AFTER-MARKET BUY ${res.orderId} for $amoQty $sym " +
                                "at up to ${"%.2f".format(amoLimit)} (last close " +
                                "${"%.2f".format(ref)}) after: ${now.refusal}")
                            return@route ManualResult(true, qty = amoQty, price = amoLimit,
                                orderId = res.orderId, immediate = false, refusal = now.refusal,
                                note = "The market is shut, so order ${res.orderId} is QUEUED for " +
                                       "the open: $amoQty $sym, paying up to " +
                                       "₹${"%.2f".format(amoLimit)} — about " +
                                       "${"%.1f".format(AMO_THROUGH * 100)}% over the " +
                                       "₹${"%.2f".format(ref)} it last traded at. " +
                                       "Nothing is bought yet. If the open gaps above that limit " +
                                       "it will not fill, and the app will say so. " +
                                       "The protective stop goes on once it does fill.")
                        }
                        is ZerodhaClient.OrderResult.Failure -> {
                            if (!res.answered) {
                                // Same rule as the ordinary order above: silence is
                                // not a refusal, and a trigger on top of a possibly
                                // live order is a second instruction for one tap.
                                ZerodhaLiveStore.forTrade(ctx, ns, paperTradeId)?.let {
                                    ZerodhaLiveStore.upsert(ctx, it.copy(status = "ERROR",
                                        error = "no answer to the after-market order: ${res.message}"))
                                }
                                return@route ManualResult(false, refusal = res.message,
                                    error = "Zerodha did not answer the after-market order for " +
                                            "$sym (${res.message}). Nothing further was placed — " +
                                            "check your order list in Kite before trying again.")
                            }
                            Log.w(TAG, "[$ns] after-market buy refused for $sym: ${res.message} " +
                                "— falling back to a trigger")
                        }
                    }
                }
            }

            // ── ROUTE 3: THE BROKER REFUSED. Rest a trigger instead. ─────────
            val trigger = onTick(ref * (1.0 + BUY_TRIGGER_OVER), tick)
            val limit = onTick(trigger * (1.0 + BUY_LIMIT_OVER), tick)
            val qty = Math.floor(cash / limit).toInt()
            if (qty < 1) return@route ManualResult(false, refusal = now.refusal,
                error = "Zerodha refused an ordinary order (${now.refusal}), and " +
                        "₹${"%.0f".format(cash)} is under one share at the " +
                        "₹${"%.2f".format(limit)} a trigger would have to allow — " +
                        "NSE has no fractional shares.")

            when (val res = createGttWithTick(cfg, client, sym, type = "single",                 triggers = listOf(trigger), lastPrice = ref,
                orders = listOf(ZerodhaClient.GttOrderSpec(
                    transactionType = "BUY", quantity = qty, price = limit,
                    product = "CNC", orderType = "LIMIT")))
            ) {
                is ZerodhaClient.GttCreateResult.Success -> {
                    // STILL PENDING, deliberately. Nothing has been bought — a
                    // trigger is a standing instruction, and calling it OPEN here
                    // would put a position in the book that does not exist and
                    // start measuring exits against it.
                    ZerodhaLiveStore.upsert(ctx, ZerodhaLiveStore.Row(
                        ns = ns, paperTradeId = paperTradeId, symbol = sym,
                        status = "PENDING", qty = qty, gttId = res.id,
                        entryPrice = 0.0, paperEntryPrice = paperEntryPrice,
                        buyAt = System.currentTimeMillis(),
                        error = "ordinary order refused: ${now.refusal}"))
                    Log.w(TAG, "[$ns] BUY TRIGGER ${res.id} for $qty $sym at " +
                        "${"%.2f".format(trigger)} (limit ${"%.2f".format(limit)}, " +
                        "live ${"%.2f".format(ref)}) after refusal: ${now.refusal}")
                    ManualResult(true, qty = qty, price = trigger, gttId = res.id,
                        immediate = false, refusal = now.refusal,
                        note = "Zerodha would not take an ordinary order (${now.refusal}), so a " +
                               "trigger is waiting at ₹${"%.2f".format(trigger)} — about " +
                               "${"%.1f".format(BUY_TRIGGER_OVER * 100)}% above the " +
                               "₹${"%.2f".format(ref)} the stock was at when you tapped. " +
                               "Nothing is bought until it gets there.")
                }
                is ZerodhaClient.GttCreateResult.Failure -> {
                    ZerodhaLiveStore.forTrade(ctx, ns, paperTradeId)?.let {
                        ZerodhaLiveStore.upsert(ctx, it.copy(status = "ERROR", error = res.message))
                    }
                    ManualResult(false, refusal = now.refusal,
                        error = "Neither route worked. Ordinary order: ${now.refusal}. " +
                                "Trigger: ${res.message}")
                }
            }
        } } catch (e: Exception) {
            Log.e(TAG, "[$ns] buy for $sym threw", e)
            ManualResult(false, error = e.message ?: "buy failed")
        }
    }

    /**
     * Call off a waiting BUY — a resting trigger, or an ordinary order the broker
     * took but has not filled.
     *
     * BOTH KINDS, because after the immediate route was added the trigger was no
     * longer the only thing a buy could be waiting on. An ordinary limit that the
     * market has run away from sits on the book all day, and refusing to cancel it
     * here would leave the broker app as the only way out — which is exactly the
     * split-brain this function exists to prevent.
     *
     * Cancels at the broker FIRST and only then clears the app's record. The other
     * order would leave something live on the account that nothing in the app
     * knows about — which is how you end up owning something you decided against,
     * days later, with no idea where it came from.
     */
    fun cancelBuyTrigger(ctx: Context, ns: String, paperTradeId: String): ManualResult {
        val cfg = ConfigManager(ctx)
        if (!cfg.isZerodhaConnected) return ManualResult(false, error = "Zerodha is not connected.")
        val row = ZerodhaLiveStore.forTrade(ctx, ns, paperTradeId)
            ?: return ManualResult(false, error = "No record of that order.")
        if (row.status != "PENDING")
            return ManualResult(false, error = "That order is no longer waiting — it is ${row.status.lowercase()}.")
        val id = row.gttId ?: 0L
        val orderId = row.buyOrderId ?: ""
        if (id <= 0L && orderId.isNotBlank()) return cancelWorkingOrder(ctx, ns, row, orderId)
        if (id <= 0L) return ManualResult(false, error = "Nothing waiting is attached to that entry.")
        return try {
            when (val r = ZerodhaClient(cfg).deleteGttTrigger(id)) {
                is ZerodhaClient.GttDeleteResult.Success -> {
                    ZerodhaLiveStore.upsert(ctx, row.copy(
                        status = "CANCELLED", gttId = null, error = "cancelled before it filled"))
                    Log.w(TAG, "[$ns] buy trigger $id on ${row.symbol} cancelled")
                    ManualResult(true, qty = row.qty, gttId = id, note = "Trigger $id removed.")
                }
                is ZerodhaClient.GttDeleteResult.Failure -> {
                    // The record is deliberately left as it was. Saying "cancelled"
                    // while the trigger is still live at the broker is the one
                    // outcome that could actually cost money.
                    Log.w(TAG, "[$ns] could not cancel trigger $id: ${r.message}")
                    ManualResult(false, error = "Zerodha would not remove trigger $id: ${r.message}")
                }
            }
        } catch (e: Exception) {
            ManualResult(false, error = e.message ?: "cancel failed")
        }
    }

    /**
     * Call off an ordinary buy order that has not finished filling.
     *
     * A PARTIAL FILL IS NOT CANCELLED AWAY. Cancelling stops the REST of the
     * order; whatever already filled is bought and stays bought. So the record is
     * only marked cancelled when nothing filled — otherwise it is left PENDING
     * for the settle sweep to open as the smaller position it really is. Writing
     * CANCELLED over a partial fill would hide real shares from the book, and an
     * unhidden position is the only kind the stop sweep can protect.
     */
    private fun cancelWorkingOrder(ctx: Context, ns: String, row: ZerodhaLiveStore.Row,
                                   orderId: String): ManualResult {
        val cfg = ConfigManager(ctx)
        val client = ZerodhaClient(cfg)
        return try {
            val before = client.fetchOrderFill(orderId)
            if (before != null && before.status == "COMPLETE")
                return ManualResult(false,
                    error = "Too late — order $orderId has already filled ${before.filledQty} " +
                            "at ₹${"%.2f".format(before.avgPrice)}. The position is yours; " +
                            "sell it if you have changed your mind.")
            // TRIED BOTH WAYS ROUND. Kite matches the variety on cancel, so an
            // after-market order cancelled down the regular path is answered with
            // "order not found" and quietly left live — the user is told it is
            // gone while it waits to fill at the open. Nothing records which kind
            // a row was placed as, so the second attempt is the cheap insurance:
            // cancelling an id that does not exist under a variety costs a refusal
            // and nothing else.
            val cancelled = when (val first = client.cancelOrder(orderId)) {
                is ZerodhaClient.OrderResult.Success -> first
                is ZerodhaClient.OrderResult.Failure ->
                    client.cancelOrder(orderId, variety = "amo").let { second ->
                        if (second is ZerodhaClient.OrderResult.Success) second else first
                    }
            }
            when (val r = cancelled) {
                is ZerodhaClient.OrderResult.Success -> {
                    val after = client.fetchOrderFill(orderId)
                    val got = after?.filledQty ?: 0
                    if (got > 0) {
                        Log.w(TAG, "[$ns] order $orderId on ${row.symbol} cancelled after a " +
                            "partial fill of $got — left PENDING for the settle sweep")
                        ManualResult(false,
                            error = "Cancelled the rest, but $got share${if (got == 1) "" else "s"} " +
                                    "had already filled. That much is yours — the app will open and " +
                                    "protect it on the next check.")
                    } else {
                        ZerodhaLiveStore.upsert(ctx, row.copy(
                            status = "CANCELLED", error = "cancelled before it filled"))
                        Log.w(TAG, "[$ns] order $orderId on ${row.symbol} cancelled unfilled")
                        ManualResult(true, qty = row.qty, orderId = orderId,
                            note = "Order $orderId removed. Nothing was bought.")
                    }
                }
                is ZerodhaClient.OrderResult.Failure -> {
                    // Record deliberately untouched. Saying "cancelled" while the
                    // order is still live is the one outcome that costs money.
                    Log.w(TAG, "[$ns] could not cancel order $orderId: ${r.message}")
                    ManualResult(false,
                        error = "Zerodha would not cancel order $orderId: ${r.message}")
                }
            }
        } catch (e: Exception) {
            ManualResult(false, error = e.message ?: "cancel failed")
        }
    }

    /** How far below the market a FALLBACK sell trigger rests. Past the 0.25%
     *  the broker enforces, close enough that any downtick takes it. Only
     *  reached when an ordinary order was refused. */
    private const val SELL_TRIGGER_UNDER = 0.004     // −0.4%
    private const val SELL_LIMIT_UNDER = 0.006       // −0.6% below its own trigger

    /**
     * Sell a holding from the app: an ordinary order if the broker will take one,
     * a resting trigger if it will not.
     *
     * When the ordinary order goes through this really is an immediate sale, and
     * the caller may say so. When it does not, the trigger rests just under the
     * market and takes the first downtick — which on a stock running away upwards
     * will sit unfilled, and the caller must NOT describe that as having sold.
     * [ManualResult.immediate] is how the two are told apart.
     */
    fun manualSell(ctx: Context, symbol: String, qty: Int): ManualResult {
        val cfg = ConfigManager(ctx)
        if (!cfg.isZerodhaConnected) return ManualResult(false, error = "Zerodha is not connected.")
        if (qty < 1) return ManualResult(false, error = "Nothing to sell.")
        val sym = symbol.trim().uppercase()
        val client = ZerodhaClient(cfg)
        return try { run route@{
            val ref = referencePrice(client, sym)
            if (ref <= 0.0) return ManualResult(false,
                error = "No current price for $sym — nothing placed, rather than selling at a guess.")
            val tick = cfg.learnedTickSize(sym) ?: 0.05

            // ── ROUTE 1: AN ORDINARY ORDER, PRICED TO FILL NOW ───────────────
            // A sell limit BELOW the bid crosses the spread and is taken at once
            // at whatever is actually bid — which is normally better than this
            // number. It is a floor on a thin book, not the price expected.
            val through = onTick(ref * (1.0 - IMMEDIATE_THROUGH), tick)
            val now = placeImmediate(client, "SELL", sym, qty, through, cfg)
            if (now.placed) {
                if (now.complete) {
                    Log.w(TAG, "MANUAL SELL $sym — ${now.filledQty} at " +
                        "${"%.2f".format(now.avgPrice)} (order ${now.orderId})")
                    // THE OLD PROTECTION HAS TO GO. A stop left resting on shares
                    // that no longer exist is not harmless: it survives until the
                    // user buys the name back, and then sells it out from under
                    // them for reasons nobody remembers.
                    val cleared = clearRestingSells(client, sym)
                    return@route ManualResult(true, qty = now.filledQty, price = now.avgPrice,
                        orderId = now.orderId, immediate = true,
                        filledQty = now.filledQty, filledPrice = now.avgPrice,
                        note = "Sold ${now.filledQty} at ₹${"%.2f".format(now.avgPrice)}. " +
                               "The live price when you tapped was ₹${"%.2f".format(ref)}." +
                               (if (cleared > 0) " Removed $cleared resting stop/target trigger" +
                                    (if (cleared == 1) "." else "s.") else ""))
                }
                return@route ManualResult(true, qty = qty, price = through,
                    orderId = now.orderId, immediate = true,
                    note = "Order ${now.orderId} is live to sell $qty $sym at no less than " +
                           "₹${"%.2f".format(through)} — the live price when you tapped was " +
                           "₹${"%.2f".format(ref)}. It has not finished filling yet.")
            }

            if (!now.answered)
                return@route ManualResult(false, refusal = now.refusal,
                    error = "Zerodha did not answer the sell order for $sym (${now.refusal}). " +
                            "Nothing further was placed on purpose — the order may have reached " +
                            "them, and a second instruction could sell $sym twice. Check your " +
                            "order list in Kite before trying again.")

            // ── ROUTE 2: NOTHING IS TRADING YET. Queue it for the open. ──────
            //
            // THE PROTECTIVE STOP IS LEFT ALONE HERE, unlike the trigger route
            // below. A queued sell is not a standing instruction competing with
            // the stop — no exchange is awake to act on either of them tonight,
            // and if the queued order fails to fill in the morning, a position
            // whose stop had already been deleted would be left with nothing.
            // Two sell instructions surviving into the open costs at worst one
            // rejected order; a stop deleted for a sale that never happened costs
            // real money. The sweep clears the stop once the sale completes.
            if (refusedForMarketHours(now.refusal)) {
                val amoLimit = onTick(ref * (1.0 - AMO_THROUGH), tick)
                when (val res = client.placeOrder(
                    EXCHANGE, sym, "SELL", qty, "LIMIT", amoLimit, variety = "amo")
                ) {
                    is ZerodhaClient.OrderResult.Success -> {
                        Log.w(TAG, "AFTER-MARKET SELL ${res.orderId} for $qty $sym at no less " +
                            "than ${"%.2f".format(amoLimit)} (last close ${"%.2f".format(ref)}) " +
                            "after: ${now.refusal}")
                        return@route ManualResult(true, qty = qty, price = amoLimit,
                            orderId = res.orderId, immediate = false, refusal = now.refusal,
                            note = "The market is shut, so order ${res.orderId} is QUEUED for the " +
                                   "open: $qty $sym at no less than ₹${"%.2f".format(amoLimit)} — " +
                                   "about ${"%.1f".format(AMO_THROUGH * 100)}% under the " +
                                   "₹${"%.2f".format(ref)} it last traded at. " +
                                   "NOTHING IS SOLD YET, and if the open gaps below that limit it " +
                                   "will not fill. Any protective stop on $sym is deliberately " +
                                   "left in place until the sale actually completes.")
                    }
                    is ZerodhaClient.OrderResult.Failure -> {
                        if (!res.answered)
                            return@route ManualResult(false, refusal = res.message,
                                error = "Zerodha did not answer the after-market sell for $sym " +
                                        "(${res.message}). Nothing further was placed — check " +
                                        "your order list in Kite before trying again.")
                        Log.w(TAG, "after-market sell refused for $sym: ${res.message} " +
                            "— falling back to a trigger")
                    }
                }
            }

            // ── ROUTE 3: THE BROKER REFUSED. Rest a trigger instead. ─────────
            //
            // CLEAR THE OLD TRIGGERS FIRST, and this is the difference between
            // Zerodha and the ladder markets. A trigger is not an order that
            // reserves anything — it is a standing instruction that lives at the
            // broker until something fires it. So resting a manual sell for the
            // whole holding while a protective stop is ALSO resting on the same
            // shares leaves two instructions to sell the same stock: whichever
            // fires second is either refused for shares that are gone, or, if the
            // name has since been bought back, sells it out from under you.
            //
            // THE NEW ONE IS PLACED FIRST, then the old ones are removed.
            //
            // Clearing first reads as the tidier order and is the dangerous one:
            // if the new trigger then fails to create, the protective stop has
            // already been deleted and the position is left with nothing at all.
            // Placing first leaves a few seconds in which two triggers exist,
            // which costs at worst one rejected order — against a position that
            // could otherwise sit unguarded until somebody noticed.
            val trigger = onTick(ref * (1.0 - SELL_TRIGGER_UNDER), tick)
            val limit = onTick(trigger * (1.0 - SELL_LIMIT_UNDER), tick)
            when (val res = createGttWithTick(cfg, client, sym, type = "single",                 triggers = listOf(trigger), lastPrice = ref,
                orders = listOf(ZerodhaClient.GttOrderSpec(
                    transactionType = "SELL", quantity = qty, price = limit,
                    product = "CNC", orderType = "LIMIT")))
            ) {
                is ZerodhaClient.GttCreateResult.Success -> {
                    Log.w(TAG, "MANUAL SELL TRIGGER ${res.id} for $qty $sym at " +
                        "${"%.2f".format(trigger)} after refusal: ${now.refusal}")
                    // Only now that a replacement exists. Two standing sell
                    // instructions on one holding is how the same shares get sold
                    // twice — or sold again after you have bought the name back.
                    val supersededBefore = clearRestingSells(client, sym, exceptId = res.id)
                    ManualResult(true, qty = qty, price = trigger, gttId = res.id,
                        immediate = false, refusal = now.refusal,
                        note = "Zerodha would not take an ordinary order (${now.refusal}), so a " +
                               "trigger is resting at ₹${"%.2f".format(trigger)} — about " +
                               "${"%.1f".format(SELL_TRIGGER_UNDER * 100)}% below the " +
                               "₹${"%.2f".format(ref)} the stock was at when you tapped. " +
                               "NOTHING HAS BEEN SOLD until price reaches it." +
                               (if (supersededBefore > 0)
                                   " This replaced $supersededBefore resting stop/target trigger" +
                                   (if (supersededBefore == 1) "" else "s") +
                                   " on $sym — your protective stop is now this trigger, not the old one."
                                else ""))
                }
                is ZerodhaClient.GttCreateResult.Failure -> ManualResult(false,
                    refusal = now.refusal,
                    error = "Neither route worked. Ordinary order: ${now.refusal}. " +
                            "Trigger: ${res.message}")
            }
        } } catch (e: Exception) {
            Log.e(TAG, "manual sell $sym threw", e)
            ManualResult(false, error = e.message ?: "sell failed")
        }
    }

    /**
     * Delete every active SELL trigger on a name, and say how many went.
     *
     * Called only after the whole holding has actually gone. Best-effort by
     * design: a trigger that will not delete is worth a log line, not a failed
     * sale that has already happened at the exchange.
     */
    private fun clearRestingSells(client: ZerodhaClient, sym: String, exceptId: Long = 0L): Int {
        return try {
            val list = (client.fetchGttTriggers() as? ZerodhaClient.GttListResult.Success)
                ?.triggers ?: return 0
            var gone = 0
            for (t in list) {
                if (!t.tradingsymbol.equals(sym, true)) continue
                if (t.status != "active") continue
                if (!t.transactionType.equals("SELL", true)) continue
                // Never the one the caller has just placed to REPLACE these.
                if (exceptId != 0L && t.id == exceptId) continue
                if (client.deleteGttTrigger(t.id) is ZerodhaClient.GttDeleteResult.Success) {
                    gone++
                    Log.w(TAG, "removed orphan sell trigger ${t.id} on $sym")
                } else {
                    Log.w(TAG, "orphan sell trigger ${t.id} on $sym would not delete")
                }
            }
            gone
        } catch (e: Exception) {
            Log.w(TAG, "orphan trigger sweep on $sym failed", e)
            0
        }
    }

    /** Public door to [attachProtectiveGtt], for a position that arrived through a
     *  trigger rather than an immediate order — the app is not present at that
     *  fill, so protection has to be attached when it is next noticed. */
    fun attachProtectionFor(ctx: Context, ns: String, paperTradeId: String,
                            sym: String, qty: Int, entry: Double) =
        attachProtectiveGtt(ctx, ns, paperTradeId, sym, qty, entry)

    /** One read of the broker's trigger list, for a caller sweeping many names. */
    fun restingSellTriggers(ctx: Context): List<ZerodhaClient.GttTrigger>? = try {
        (ZerodhaClient(ConfigManager(ctx)).fetchGttTriggers()
            as? ZerodhaClient.GttListResult.Success)?.triggers
    } catch (e: Exception) { Log.w(TAG, "trigger list read failed", e); null }

    /**
     * RAISE THE RESTING TRIGGER TO A NEW FLOOR — never lower it, never add a second.
     *
     * This is how a hand-set percentage trail reaches an Indian position. It does
     * not sell and does not create a parallel instruction: it finds the sell
     * trigger already guarding the holding and moves it UP if the new floor is
     * tighter. Where both the adaptive stop and the user's percentage have an
     * opinion, the one closer to price stands, and there is still exactly one
     * trigger on the holding.
     *
     * WHY NOT SELL FROM THE APP, as Germany does. A trigger lives at the broker.
     * It keeps working while the phone is in a drawer, the app has been killed by
     * the system, and the Zerodha session has expired — which for this user is
     * most of the trading day. An app-side sell would be protection that exists
     * only while someone is holding the phone.
     *
     * MONOTONIC, and that is the whole feature. A floor that could retreat would
     * follow price in both directions and protect nothing.
     */
    fun raiseProtectiveFloor(ctx: Context, ns: String, symbol: String,
                             newFloor: Double,
                             preFetched: List<ZerodhaClient.GttTrigger>? = null): Boolean {
        if (newFloor <= 0.0) return false
        val cfg = ConfigManager(ctx)
        if (!cfg.isZerodhaConnected) return false
        val sym = symbol.trim().uppercase()
        val client = ZerodhaClient(cfg)
        return try {
            val list = preFetched
                ?: (client.fetchGttTriggers() as? ZerodhaClient.GttListResult.Success)
                    ?.triggers ?: return false
            val resting = list.firstOrNull {
                it.tradingsymbol.equals(sym, true) && it.status == "active" &&
                    it.transactionType.equals("SELL", true)
            } ?: run {
                // THE ONE SILENT FAILURE WORTH BREAKING SILENCE FOR. Every other
                // path here declines to TIGHTEN a floor that already exists. This
                // one means there is no floor at all — the position is naked and
                // the user would never learn it from a log line they never read.
                Log.w(TAG, "[$ns] $sym has NO resting stop to raise — unprotected")
                try { LabNotifier.unprotectedHolding(ctx, ns, sym) } catch (_: Exception) {}
                return false
            }

            val current = resting.triggerPrice
            val want = ManualTrailingStop.tighterOf(current, newFloor)
            // Zerodha refuses a trigger too close to the live price, so a floor
            // that has caught up with the market cannot be placed — and must not
            // be reported as if it had been.
            // CHEAPEST TEST FIRST. A floor that has not moved is the common case
            // by far, and it was paying for a price lookup before discovering it
            // had nothing to do.
            if (want <= current + 0.0001) return false   // already at least this tight
            val ltp = when (val r = client.fetchLtp(EXCHANGE, sym)) {
                is ZerodhaClient.LtpResult.Success -> r.lastPrice
                else -> 0.0
            }
            if (ltp > 0.0 && want >= ltp * 0.9975) {
                Log.w(TAG, "[$ns] $sym trail floor ${"%.2f".format(want)} is at the market " +
                    "(${"%.2f".format(ltp)}) — Zerodha will not rest it there, left as is")
                return false
            }
            when (val r = client.modifyGttTriggerPrice(resting.id, want, current)) {
                is ZerodhaClient.GttModifyResult.Success -> {
                    Log.w(TAG, "[$ns] $sym trailing floor raised ${"%.2f".format(current)} " +
                        "→ ${"%.2f".format(want)}")
                    true
                }
                else -> {
                    Log.w(TAG, "[$ns] $sym trailing floor could not be raised: $r")
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[$ns] $sym raiseProtectiveFloor failed", e); false
        }
    }

    /** The position's resting trigger at Zerodha: by its own id first, else the
     *  active sell trigger on the same stock. Null when none, or unreadable. */
    private fun restingTriggerOf(client: ZerodhaClient, row: ZerodhaLiveStore.Row): ZerodhaClient.GttTrigger? {
        val list = (client.fetchGttTriggers() as? ZerodhaClient.GttListResult.Success)?.triggers ?: return null
        return list.firstOrNull { it.id == row.gttId && it.status == "active" }
            ?: list.firstOrNull {
                it.tradingsymbol.equals(row.symbol, true) && it.status == "active" &&
                    it.transactionType.equals("SELL", true)
            }
    }

    /**
     * YOUR PRICE AS THIS POSITION'S TARGET — the upper side of the trigger
     * already resting under it at Zerodha, moved to where you want it.
     *
     * Moved, never added: the trigger carries the stop as well, and a second one
     * would be two sells against one holding. Either way up is allowed — this is
     * your choice of price, not a ratchet — but never at or under the market,
     * which Zerodha refuses, nor under the stop beside it. When it fires, the
     * sale is booked to the algo the same way any sale of its shares by a
     * trigger above the entry is.
     */
    fun setTargetLeg(ctx: Context, ns: String, paperTradeId: String, price: Double): String {
        val cfg = ConfigManager(ctx)
        if (!cfg.isZerodhaConnected) return "Zerodha is not connected."
        val row = ZerodhaLiveStore.forTrade(ctx, ns, paperTradeId)
            ?: return "That position is not in the app's records."
        if (row.status != "OPEN") return "That position is not open any more."
        val client = ZerodhaClient(cfg)
        return try {
            val g = restingTriggerOf(client, row)
                ?: return "${row.symbol} has no resting trigger at Zerodha to carry a target."
            val current = g.triggerPriceAux ?: 0.0
            if (current <= 0.0)
                return "${row.symbol}'s trigger at Zerodha is a stop only, with no target side to move. " +
                    "Rebuilding it would leave the position unprotected while it was replaced, so it is left alone."
            val tick = cfg.learnedTickSize(row.symbol) ?: 0.05
            val px = Math.round(Math.floor(price / tick + 1e-9) * tick * 10_000.0) / 10_000.0
            if (!(px > 0.0)) return "Enter a price above zero."
            if (px <= g.triggerPrice)
                return "${"%.2f".format(px)} is under the stop resting beside it (${"%.2f".format(g.triggerPrice)})."
            val ltp = (client.fetchLtp(EXCHANGE, row.symbol) as? ZerodhaClient.LtpResult.Success)?.lastPrice ?: 0.0
            if (ltp > 0.0 && px <= ltp * 1.0025)
                return "${"%.2f".format(px)} is at or under the current price (${"%.2f".format(ltp)}) — Zerodha will " +
                    "not rest a target there. Use Take profit to sell now, or pick a higher price."
            when (val r = client.modifyGttTriggerPrice(g.id, px, current)) {
                is ZerodhaClient.GttModifyResult.Success -> {
                    Log.w(TAG, "[$ns] ${row.symbol} target set by hand: ${"%.2f".format(current)} → ${"%.2f".format(px)}")
                    "Target for ${row.symbol} moved to ₹${"%.2f".format(px)} at Zerodha (was ₹${"%.2f".format(current)}). " +
                        "When it sells, the sale is booked to the position like any of its exits."
                }
                else -> "Zerodha did not accept the new target: $r"
            }
        } catch (e: Exception) {
            Log.w(TAG, "[$ns] ${row.symbol} setTargetLeg failed", e)
            "Could not change the target: ${e.message}"
        }
    }

    /** What the target window needs to open on an Indian position. */
    fun targetContext(ctx: Context, ns: String, paperTradeId: String): Map<String, Any?>? {
        val row = ZerodhaLiveStore.forTrade(ctx, ns, paperTradeId) ?: return null
        if (row.status != "OPEN") return null
        val cfg = ConfigManager(ctx)
        val g = if (cfg.isZerodhaConnected) try { restingTriggerOf(ZerodhaClient(cfg), row) } catch (e: Exception) { null } else null
        return mapOf("ok" to true, "broker" to "zerodha", "ns" to ns, "pid" to paperTradeId,
            "ticker" to row.symbol.uppercase(), "symbol" to row.symbol.uppercase(),
            "qty" to row.qty.toDouble(), "paid" to row.entryPrice,
            // The price now — the cached one when there is one, else fetched: the
            // window is opened by hand and can wait a second for a real number.
            "ltp" to (MarkToMarket.get(row.symbol)?.price?.takeIf { it > 0.0 }
                ?: MarkToMarket.priceNow(ctx, ns, row.symbol) ?: 0.0),
            "currency" to "INR", "space" to "IN",
            "owner" to (if (Spaces.bucketIdOf(ns) == com.signalscope.app.data.ManualWatchStore.ID) "your watchlist"
                        else "the algo " + (try { com.signalscope.app.data.BucketStore.get(ctx, ns)?.name } catch (e: Exception) { null } ?: ns)),
            "target" to (g?.triggerPriceAux ?: 0.0), "targetLabel" to (if ((g?.triggerPriceAux ?: 0.0) > 0.0) "the trigger at Zerodha" else ""),
            "stop" to (g?.triggerPrice ?: 0.0),
            "resting" to ((g?.triggerPriceAux ?: 0.0) > 0.0), "noTargetLeg" to (g != null && (g.triggerPriceAux ?: 0.0) <= 0.0),
            // The watchlist's own take-profit pulls this side DOWN to itself on
            // every pass (never up), so a higher price of yours would not hold.
            "watchTp" to (if (Spaces.bucketIdOf(ns) == com.signalscope.app.data.ManualWatchStore.ID) try {
                val st = com.signalscope.app.data.ManualWatchStore.load(ctx, "in")
                val e = st.entries.firstOrNull { it.status == "OPEN" && "${it.symbol.uppercase()}-${it.signalBarTs}" == paperTradeId }
                if (e != null && st.takeProfitPct > 0.0) ManualTakeProfit.targetFor(e.filledPrice, st.takeProfitPct) else 0.0
            } catch (e: Exception) { 0.0 } else 0.0))
    }

    /**
     * LOWER THE RESTING TARGET TO A NEW CEILING — never raise it, never add a second.
     *
     * The exact mirror of [raiseProtectiveFloor], and it exists for the same
     * reason: a hand-set level has to reach an Indian position through the
     * instruction already sitting at the broker, because that is the only thing
     * that keeps working once the app is shut. The protective trigger placed on
     * every bought position is a two-leg one — a stop below and a target above —
     * so the user's take profit has a leg waiting for it.
     *
     * TIGHTER MEANS LOWER HERE, and that inversion is the whole reason this is a
     * separate function rather than the same one with a comparison flipped at the
     * call site. Closer to price for a ceiling is DOWN.
     *
     * DECLINES RATHER THAN IMPROVISES in the two cases it cannot serve:
     *
     *   • THE TRIGGER HAS NO TARGET LEG. A single-leg stop cannot grow one
     *     without being deleted and rebuilt, and there is a window in the middle
     *     of that where the position has no protection at all. A target is worth
     *     less than a stop, so it does not get to risk one.
     *   • THE MARKET IS ALREADY THERE. Zerodha refuses a sell target at or below
     *     the last price, so there is nothing to rest. The caller handles that
     *     case by selling, which is what a target already passed actually means.
     */
    fun lowerProfitCeiling(ctx: Context, ns: String, symbol: String,
                           newCeiling: Double,
                           preFetched: List<ZerodhaClient.GttTrigger>? = null): Boolean {
        if (newCeiling <= 0.0) return false
        val cfg = ConfigManager(ctx)
        if (!cfg.isZerodhaConnected) return false
        val sym = symbol.trim().uppercase()
        val client = ZerodhaClient(cfg)
        return try {
            val list = preFetched
                ?: (client.fetchGttTriggers() as? ZerodhaClient.GttListResult.Success)
                    ?.triggers ?: return false
            val resting = list.firstOrNull {
                it.tradingsymbol.equals(sym, true) && it.status == "active" &&
                    it.transactionType.equals("SELL", true)
            } ?: run {
                // Deliberately quieter than the stop side's equivalent. No resting
                // trigger at all means the position is UNPROTECTED, which that
                // function already says loudly; repeating it here would double
                // every notification for the same fact.
                Log.w(TAG, "[$ns] $sym has no resting trigger to carry a target")
                return false
            }
            val current = resting.triggerPriceAux ?: 0.0
            if (current <= 0.0) {
                Log.w(TAG, "[$ns] $sym is guarded by a stop-only trigger, so there is no " +
                    "target leg to lower — left alone rather than rebuilt, which would " +
                    "leave the position unprotected while it was replaced")
                return false
            }

            val want = ManualTakeProfit.tighterOf(current, newCeiling)
            // CHEAPEST TEST FIRST, exactly as on the stop side: a ceiling that has
            // not moved is the common case by far and must not pay for a price
            // lookup to discover it has nothing to do.
            if (want >= current - 0.0001) return false   // already at least this tight
            val ltp = when (val r = client.fetchLtp(EXCHANGE, sym)) {
                is ZerodhaClient.LtpResult.Success -> r.lastPrice
                else -> 0.0
            }
            if (ltp > 0.0 && want <= ltp * 1.0025) {
                Log.w(TAG, "[$ns] $sym target ${"%.2f".format(want)} is at or under the market " +
                    "(${"%.2f".format(ltp)}) — Zerodha will not rest it there, so the sale is " +
                    "the caller's to make")
                return false
            }
            when (val r = client.modifyGttTriggerPrice(resting.id, want, current)) {
                is ZerodhaClient.GttModifyResult.Success -> {
                    Log.w(TAG, "[$ns] $sym profit target lowered ${"%.2f".format(current)} " +
                        "→ ${"%.2f".format(want)}")
                    true
                }
                else -> {
                    Log.w(TAG, "[$ns] $sym profit target could not be lowered: $r")
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[$ns] $sym lowerProfitCeiling failed", e); false
        }
    }

    /**
     * The protective trigger. Placed AFTER the buy, and its failure is not fatal
     * to the position — an unprotected holding is worse than a protected one but
     * far better than one whose entry was rolled back on a secondary error.
     *
     * Uses the same planner as the India portfolio's own OCO placement, so an
     * algo position and a hand-placed one are protected by identical rules
     * rather than two drifting copies of the same idea.
     */
    private fun attachProtectiveGtt(ctx: Context, ns: String, paperTradeId: String,
                                    sym: String, qty: Int, entry: Double) {
        try {
            val cfg = ConfigManager(ctx)
            val client = ZerodhaClient(cfg)
            val plan = OcoPlanner.planSellOco(cfg, OcoPlanner.Input(
                ltp = entry, support = 0.0, resistance = 0.0,
                atr = entry * 0.02, avgPrice = entry,
                // The instrument's OWN grid where the broker has told us one,
                // rather than the 0.05 most NSE equities happen to use. Planning
                // a stop on the wrong grid is how a bought position ended up with
                // its protection refused.
                tickSize = cfg.learnedTickSize(sym) ?: 0.05,
                broker = OcoPlanner.Broker.ZERODHA))
            if (!plan.ok) return
            // THE TARGET LEG FROM THE RANGE RULE — the one Trading 212 positions
            // use ([WeekTarget]): near the month's low or bought on strength, a
            // tenth of the month's range short of its high; near the week's low,
            // the week's high; never closer than 1% to the fill, because India's
            // round trip costs taxes and charges. The planner's own target stays
            // as the fallback: a two-leg trigger needs an upper leg, and one set
            // far off does no harm where the range rule has nothing to offer.
            val tick = cfg.learnedTickSize(sym) ?: 0.05
            val range = try {
                StrategyLabRunner.rangesBefore(ctx, ns, sym, System.currentTimeMillis() / 1000L)
                    ?.let { WeekTarget.plan(it, entry, null, preExisting = false, minGapPct = WeekTarget.minGapFor("in")) }
            } catch (e: Exception) { null }
            val tgt = range?.takeIf { it.ok }?.let { Math.floor(it.price / tick + 1e-9) * tick }
                ?.takeIf { it > plan.sl }
            val target = tgt?.let { Math.round(it * 10_000.0) / 10_000.0 } ?: plan.target
            val targetLimit = if (tgt != null)
                Math.round(Math.floor(target * 0.99 / tick + 1e-9) * tick * 10_000.0) / 10_000.0
                else plan.targetLimit
            Log.i(TAG, "[$ns] $sym target leg ${"%.2f".format(target)} — " +
                (if (tgt != null) "${range!!.label} (range rule)"
                 else "planner's (range rule: ${range?.why?.ifBlank { null } ?: "no prices"})"))
            val res = createGttWithTick(cfg, client, sym, type = "two-leg",                 triggers = listOf(plan.sl, target), lastPrice = entry,
                orders = listOf(
                    ZerodhaClient.GttOrderSpec("SELL", qty, plan.slLimit),
                    ZerodhaClient.GttOrderSpec("SELL", qty, targetLimit)))
            if (res is ZerodhaClient.GttCreateResult.Success) {
                ZerodhaLiveStore.forTrade(ctx, ns, paperTradeId)?.let {
                    ZerodhaLiveStore.upsert(ctx, it.copy(gttId = res.id))
                }
                Log.w(TAG, "[$ns] GTT ${res.id} protecting $qty $sym " +
                    "(SL ${"%.2f".format(plan.sl)} / TGT ${"%.2f".format(target)})")
            } else {
                Log.w(TAG, "[$ns] GTT for $sym not created — position is unprotected")
            }
        } catch (e: Exception) { Log.w(TAG, "GTT attach failed for $sym", e) }
    }

    /**
     * The strategy decided to exit.
     *
     * THE GTT IS DELETED FIRST, AND THE SELL ONLY HAPPENS IF THAT SUCCEEDS. A
     * resting trigger plus an algo sell is two sell orders against one holding:
     * the second fills against nothing and opens an accidental short. If the
     * delete fails, the safe move is to leave the position alone and say so —
     * the trigger is still protecting it, so nothing is at risk except tidiness.
     */
    fun mirrorExit(ctx: Context, paperTradeId: String, symbol: String,
                   paperExitPrice: Double, ns: String) {
        val sym = symbol.trim().uppercase()
        val row = ZerodhaLiveStore.forTrade(ctx, ns, paperTradeId) ?: return
        if (row.status != "OPEN") return
        if (row.isManual) return
        val cfg = ConfigManager(ctx)
        if (!cfg.isZerodhaConnected) return
        // NOTE: an exit is deliberately NOT gated on the freeze or the allowlist.
        // Those govern taking NEW risk. Blocking an exit would strand a real
        // position with no way out the moment the user hits the safety switch.

        val client = ZerodhaClient(cfg)
        try {
            row.gttId?.let { id ->
                val del = client.deleteGttTrigger(id)
                if (del !is ZerodhaClient.GttDeleteResult.Success) {
                    // THE DELETE FAILED — BUT WHY? It used to stop here for good,
                    // and one reason it fails is that the trigger already FIRED and
                    // sold the shares: Shoppers Stop's stop sold all 28, and the book
                    // went on believing it held them, trying to cancel a trigger that
                    // no longer existed. So ask Zerodha what became of it.
                    val why = (del as ZerodhaClient.GttDeleteResult.Failure).message
                    when (resolveUncancellable(ctx, client, row, id, why)) {
                        ExitPath.SELL -> Unit          // nothing rests: safe to sell below
                        else -> return                 // closed as already sold, or held back
                    }
                }
            }
            // Never sell more than this algo owns: one account, several algos,
            // one aggregate holding at the broker.
            val others = ZerodhaLiveStore.claimedByOthers(ctx, ns, sym)
            val qty = row.qty
            if (qty < 1) return
            ZerodhaLiveStore.upsert(ctx, row.copy(status = "EXITING", gttId = null))

            // A LIMIT PRICED THROUGH THE BID, NOT A MARKET ORDER. Same fix as the
            // entry, and for the same reason: Zerodha's API refuses bare market
            // orders, so this arm could never have sold anything either. It was
            // the more dangerous of the two — an entry that cannot fire leaves you
            // flat, but an exit that cannot fire leaves a real position with a
            // strategy that believes it has already sold.
            //
            // A sell limit BELOW the bid crosses the spread and is taken at once
            // at whatever is actually bid, which is normally better than this
            // number. It is a floor on a thin book, not the expected price.
            val ref = referencePrice(client, sym)
            val tick = cfg.learnedTickSize(sym) ?: 0.05
            val through = if (ref > 0.0) onTick(ref * (1.0 - IMMEDIATE_THROUGH), tick) else 0.0
            val res = if (through > 0.0)
                client.placeOrder(EXCHANGE, sym, "SELL", qty, "LIMIT", through)
            else ZerodhaClient.OrderResult.Failure(
                "no current price for $sym — nothing sent, rather than selling at a guess")
            when (res) {
                is ZerodhaClient.OrderResult.Success -> {
                    ZerodhaLiveStore.upsert(ctx, row.copy(
                        status = "CLOSED", gttId = null, sellOrderId = res.orderId,
                        exitPrice = paperExitPrice, paperExitPrice = paperExitPrice,
                        sellAt = System.currentTimeMillis(), error = "", exitHeld = false))
                    Log.w(TAG, "[$ns] REAL SELL $qty $sym (order ${res.orderId})" +
                        if (others > 0) " · $others left for other algos" else "")
                }
                is ZerodhaClient.OrderResult.Failure -> {
                    // Back to OPEN, not ERROR: the shares are still held, and the
                    // next pass should be free to try the exit again.
                    //
                    // THE PROTECTIVE STOP IS GONE AND THE ROW MUST NOT PRETEND
                    // OTHERWISE. This restored the ORIGINAL row, which still
                    // carried the id of the trigger deleted moments earlier — so a
                    // position whose stop had just been cancelled went on claiming
                    // it was protected, and nothing would ever put one back. A
                    // failed exit is precisely when a stop matters most.
                    val hadStop = row.gttId != null
                    // Held for a retry while the market is open — the strategy still
                    // wants out, and a refusal (a shut market, no price) is usually
                    // about the moment rather than the stock.
                    ZerodhaLiveStore.upsert(ctx, row.copy(
                        status = "OPEN", gttId = null, exitHeld = true,
                        error = "Exit refused: ${res.message}" +
                            (if (hadStop) " · the protective stop was cancelled for the exit and is being replaced" else "")))
                    Log.e(TAG, "[$ns] SELL $sym refused: ${res.message}")
                    if (hadStop) {
                        // Put one back rather than leaving the holding bare until
                        // some later pass happens to notice.
                        try { attachProtectiveGtt(ctx, ns, paperTradeId, sym, qty,
                                if (row.entryPrice > 0.0) row.entryPrice else paperExitPrice) }
                        catch (e: Exception) { Log.e(TAG, "[$ns] $sym stop re-attach failed", e) }
                        if (ZerodhaLiveStore.forTrade(ctx, ns, paperTradeId)?.gttId == null)
                            LabNotifier.unprotectedHolding(ctx, ns, sym)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$ns] mirrorExit $sym failed", e)
        }
    }

    // ── EXITS THAT COULD NOT GO THROUGH ────────────────────────────────────────

    enum class ExitPath { SELL, CLOSED, HOLD }

    /** Retries allowed for one held-back exit before it is left for you. */
    private const val MAX_EXIT_TRIES = 6

    /**
     * The protective trigger could not be cancelled. Decide from what Zerodha
     * says became of it:
     *
     *   • TRIGGERED — the stop sold this position's shares. Close the row; sell
     *     nothing, whatever else the account holds.
     *   • ACTIVE, or unknown — it may still be resting. Selling now could sell
     *     twice. Hold back and retry later.
     *   • GONE WITHOUT FIRING (cancelled, expired, deleted…) — nothing rests, so
     *     a sale cannot double up; but only if the account still holds at least
     *     this position's shares. Holding none: close the row. Holding some but
     *     fewer: hold back — the difference could be your own shares, and they
     *     are not the app's to sell.
     */
    private fun resolveUncancellable(ctx: Context, client: ZerodhaClient,
                                     row: ZerodhaLiveStore.Row, gttId: Long, why: String): ExitPath {
        val state = client.fetchGttStatus(gttId)
        // Holdings are read unless the trigger is known to have sold them.
        val held = if (state == "triggered") null else heldQuantity(ctx, row.symbol)
        if (held != null && held > 0 && row.goneSeenAt > 0L)
            ZerodhaLiveStore.upsert(ctx, row.copy(goneSeenAt = 0L))   // back in the account: forget the sighting
        return when (decideUncancellable(state, held, row.qty)) {
            ExitPath.SELL -> ExitPath.SELL
            ExitPath.CLOSED -> if (state == "triggered") closeAsSold(ctx, row,
                    "the protective stop (GTT $gttId) had already fired and sold the shares before the strategy's exit")
                else closeIfGoneConfirmed(ctx, ZerodhaLiveStore.forTrade(ctx, row.ns, row.paperTradeId) ?: row,
                    "no longer held at Zerodha — the protective trigger (GTT $gttId) " +
                        (if (state == null) "could not be described by Zerodha" else "is $state"))
            ExitPath.HOLD -> {
                val reason = when {
                    state == null -> "could not cancel GTT $gttId ($why), and its state could not be read"
                    state == "active" -> "could not cancel GTT $gttId ($why)"
                    held == null -> "GTT $gttId is $state, but the holdings could not be read"
                    else -> "GTT $gttId is $state and only $held of ${row.qty} shares are held — left for you to check"
                }
                ZerodhaLiveStore.upsert(ctx, row.copy(exitHeld = true,
                    error = "Exit held back: $reason. The trigger still protects it; retried while the market is open."))
                Log.e(TAG, "[${row.ns}] ${row.symbol} exit held back — $reason")
                ExitPath.HOLD
            }
        }
    }

    /**
     * The decision on its own, so it can be tested without a broker: what to do
     * with an exit whose trigger would not cancel, given what Zerodha says became
     * of that trigger ([state]) and how many shares the account holds ([held],
     * null when unknown). See [resolveUncancellable] for the reasoning.
     */
    internal fun decideUncancellable(state: String?, held: Int?, qty: Int): ExitPath = when {
        state == "triggered" -> ExitPath.CLOSED
        // NOTHING HELD, NOTHING TO SELL — whatever the trigger's state. Zerodha
        // can fail to describe a trigger at all (Shoppers Stop's answered "Error
        // during internal conversion"), and "unknown" must not keep a row open
        // over shares the account does not have. Closing places no order.
        held != null && held <= 0 -> ExitPath.CLOSED
        state == null || state == "active" -> ExitPath.HOLD
        held == null -> ExitPath.HOLD
        held >= qty -> ExitPath.SELL
        else -> ExitPath.HOLD
    }

    /** How long a "none held" reading must stand before a second one may close the row. */
    private const val GONE_CONFIRM_MS = 10 * 60_000L

    /**
     * Close for absence only on a SECOND reading, a pass later — the same
     * two-reading rule the watcher uses before believing a holding is gone. A
     * single bad read of the holdings must never retire a real position.
     */
    private fun closeIfGoneConfirmed(ctx: Context, row: ZerodhaLiveStore.Row, reason: String): ExitPath {
        val now = System.currentTimeMillis()
        if (row.goneSeenAt <= 0L) {
            ZerodhaLiveStore.upsert(ctx, row.copy(goneSeenAt = now, exitHeld = true,
                error = "Exit held back: not held at Zerodha — confirming on the next pass before closing."))
            Log.w(TAG, "[${row.ns}] ${row.symbol} not held at Zerodha — confirming before closing")
            return ExitPath.HOLD
        }
        if (now - row.goneSeenAt < GONE_CONFIRM_MS) return ExitPath.HOLD
        return closeAsSold(ctx, row, reason)
    }

    private fun closeAsSold(ctx: Context, row: ZerodhaLiveStore.Row, reason: String): ExitPath {
        ZerodhaLiveStore.upsert(ctx, row.copy(status = "MANUAL_CLOSED", gttId = null, exitHeld = false,
            manualClosedAt = System.currentTimeMillis(), error = "Closed: $reason."))
        Log.w(TAG, "[${row.ns}] ${row.symbol} closed — $reason")
        return ExitPath.CLOSED
    }

    /**
     * Shares of [symbol] the account holds — settled holdings. Null when that
     * cannot be read, or when the name was traded TODAY (today's buys are not in
     * holdings yet, so a count read then would be wrong in the dangerous
     * direction). Null always means "do nothing".
     */
    internal fun heldQuantity(ctx: Context, symbol: String): Int? {
        val sym = symbol.uppercase()
        val today = try { ZerodhaClient(ConfigManager(ctx)).fetchTodayPositionSymbols() } catch (e: Exception) { null }
            ?: return null
        if (sym in today.map { it.uppercase() }) return null
        val h = (BrokerHoldings.zerodha(ctx) as? BrokerHoldings.Result.Ok)?.holdings ?: return null
        return h.filter { it.symbol.equals(sym, true) }.sumOf { it.qty }.toInt()
    }

    /**
     * RETRY EXITS THAT WERE HELD BACK — once per pass.
     *
     * The Trading 212 side has always retried an exit that did not go through;
     * this side never did, so a strategy's decision to sell could be dropped
     * after a single failed attempt and never tried again.
     *
     * First, whatever the time: is the position simply gone (the stop fired)?
     * Then, only while the Indian market is open, and at most [MAX_EXIT_TRIES]
     * times: run the exit again — the same careful path, trigger first.
     */
    fun retryHeldExits(ctx: Context) {
        val cfg = ConfigManager(ctx)
        if (!cfg.isZerodhaConnected) return
        val rows = ZerodhaLiveStore.load(ctx).trades.filter {
            it.status == "OPEN" && !it.isManual &&
                (it.exitHeld || ((it.error as String?) ?: "").startsWith("Exit held back"))
        }
        if (rows.isEmpty()) return
        val client = ZerodhaClient(cfg)
        val open = IndianSession.isOpen(System.currentTimeMillis())
        for (row in rows) {
            try {
                // 1. Gone already? A fact about the account — settled at any hour,
                //    and for absence only on a second reading (see above).
                val id = row.gttId
                val state = id?.let { client.fetchGttStatus(it) }
                if (state == "triggered") {
                    closeAsSold(ctx, row, "the protective stop (GTT $id) had already fired and sold the shares")
                    continue
                }
                val held = heldQuantity(ctx, row.symbol)
                if (held != null && held <= 0) {
                    closeIfGoneConfirmed(ctx, row, "no longer held at Zerodha" +
                        (if (id != null) " — the protective trigger (GTT $id) " +
                            (if (state == null) "could not be described by Zerodha" else "is $state") else ""))
                    continue
                }
                if (held != null && held > 0 && row.goneSeenAt > 0L)
                    ZerodhaLiveStore.upsert(ctx, row.copy(goneSeenAt = 0L))

                // 2. Still held. Does the strategy still want out?
                val paper = try {
                    com.signalscope.app.data.StrategyLabStore.load(ctx, row.ns).trades
                        .firstOrNull { it.id == row.paperTradeId }
                } catch (e: Exception) { null }
                if (paper != null && paper.status == "OPEN") {
                    // The strategy is holding it again: the exit it wanted is void.
                    ZerodhaLiveStore.upsert(ctx, row.copy(exitHeld = false, exitTries = 0, error = ""))
                    continue
                }
                if (!open || row.exitTries >= MAX_EXIT_TRIES) continue
                ZerodhaLiveStore.upsert(ctx, row.copy(exitTries = row.exitTries + 1))
                Log.i(TAG, "[${row.ns}] ${row.symbol} retrying held-back exit (${row.exitTries + 1}/$MAX_EXIT_TRIES)")
                mirrorExit(ctx, row.paperTradeId, row.symbol,
                    paper?.exitPrice ?: row.paperExitPrice, row.ns)
            } catch (e: Exception) {
                Log.w(TAG, "[${row.ns}] ${row.symbol} held-exit retry failed", e)
            }
        }
    }

    /** Armed algos in the India space, for the gates panel. */
    fun armedAlgos(ctx: Context): List<Pair<String, String>> = try {
        BucketStore.list(ctx, "in")
            .filter { it.live && it.liveBudget > 0.0 && it.liveAllowlist.isNotEmpty() }
            .map { it.ns to it.name }
    } catch (e: Exception) { emptyList() }
}

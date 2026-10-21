package com.signalscope.app.trading

import android.util.Log
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.network.FeedHealth
import com.signalscope.app.network.Trading212Client
import com.signalscope.app.network.YahooFinanceClient

// ════════════════════════════════════════════════════════════════════════════
// PATIENT ORDER EXECUTOR — extracted verbatim from MainActivity.DashboardBridge
// ════════════════════════════════════════════════════════════════════════════
// WHY THIS FILE EXISTS: this ladder is the "marketable-limit + cancel-replace-
// chase + fill-poller" unit that T212LiveMirror.LIVE_FROZEN was waiting for. It
// was already built and proven in manual use — it just lived as two
// @JavascriptInterface methods on an inner class, depending on MainActivity's
// `config` field and pushing progress straight into the WebView, so nothing
// outside the UI could call it.
//
// This is a MOVE, not a rewrite. Every threshold, guard, sleep, retry, precision
// walk and break-condition is byte-for-byte the original — each one encodes a bug
// that was hit live (double-buys from unconfirmed cancels, re-sells from a lagging
// portfolio read, untradeable dust chased to "unfilled", stale anchors that could
// never bridge the cap). The ONLY changes are mechanical:
//   • Context/ConfigManager passed in rather than read off the Activity.
//   • Progress delivered through an optional lambda rather than deliverAiCallback.
//   • Returns a [Result] instead of pushing JSON at a WebView.
//   • Collects the order IDs it placed — a ladder can place up to 6 orders per
//     leg, and the live-mirror ledger needs all of them to attribute real fills.
//
// Blocking by design: rungs dwell 40s each, so a full leg runs ~4 minutes. Callers
// must run it off any latch-bounded path (see ScanService's 10-minute tick latch).
// ════════════════════════════════════════════════════════════════════════════

object PatientExecutor {

    private const val TAG = "PatientExecutor"

    // ── tuning (unchanged from the original) ────────────────────────────────
    const val STEP_ATR = 0.10           // gap between rungs, as a fraction of ATR
    const val BUY_MAX_ATR = 0.50        // buy: max we'll concede ABOVE the LTP
    const val SELL_START_ATR = 0.30     // sell: how far ABOVE LTP we open
    const val SELL_FLOOR_ATR = 0.20     // sell: how far BELOW LTP we'll concede
    const val DWELL_MS = 40_000L        // rest per rung (user's tested value)
    private const val DUST_FRAC = 0.03
    private const val MIN_ORDER_VALUE = 1.10
    private const val ORDER_SPACING_MS = 2500L
    private const val ORDER_RATE_BACKOFF_MS = 6000L

    private data class CancelOutcome(val gone: Boolean, val likelyFilled: Boolean)

    /** What the ladder achieved. [orderIds] is every order it successfully placed,
     *  in rung order — needed to match real fills back to the paper trade. */
    data class Result(
        val ok: Boolean,
        val ticker: String,
        val filled: Double = 0.0,          // shares bought, or shares sold
        val remaining: Double = 0.0,
        val note: String = "",
        val staleOrder: Boolean = false,
        val error: String? = null,
        val orderIds: List<Long> = emptyList(),
        val refPrice: Double = 0.0,        // the anchor the ladder opened from
        val atr: Double = 0.0,
        /** Shares sold that the caller did NOT own — unclaimed sub-€1 dust the exit
         *  had to clear to be legal. Reported so the caller can keep them out of the
         *  strategy's P&L: they were never bought, so counting their proceeds as
         *  return would credit the algo with money it did not earn. */
        val sweptQty: Double = 0.0
    )

    /** Progress payload keys match the original window.onT212PatientProgress
     *  contract exactly, so the dashboard's renderer needs no change. */
    private fun prog(cb: ((Map<String, Any?>) -> Unit)?, m: Map<String, Any?>) {
        try { cb?.invoke(m) } catch (_: Exception) {}
    }

    // ── shared helpers (verbatim) ───────────────────────────────────────────

    /**
     * Re-anchor on a FRESH price at start, ignoring how stale the caller's
     * reference was. Root cause this kills: for a stock that isn't held and isn't
     * live-streaming, the dashboard's reference falls back to the last SCAN row —
     * which can be days old (observed live: AAPL ladder anchored at $317 while the
     * market traded at $333.67; the +0.5×ATR cap can never bridge a 5% gap, so the
     * order was doomed at placement). DE → Tradegate (real-time); anything → live
     * Yahoo; else the caller's ref.
     *
     * A FEED THAT HAS STOPPED IS NOT A PRICE (see FeedHealth). Yahoo then gives
     * no live quote at all, rather than the last one it ever had: a sale anchored
     * on Pfisterer's frozen 66.60 would have rested its whole ladder above a
     * market at 59 and filled nothing. The venue quote is then checked against
     * the broker's own valuation instead, and the broker's price stands in for
     * the feed's before the caller's guess does.
     */
    private fun freshRef(
        config: ConfigManager, client: Trading212Client, ticker: String, yahooSymbol: String,
        space: String, uiRef: Double
    ): Double {
        val ys = yahooSymbol.trim()
        val yahooLive = YahooFinanceClient.fetchLivePrice(ys)
        val stopped = FeedHealth.stoppedSince(ys) > 0L
        if (space.equals("DE", true)) {
            val isin = when (val r = client.fetchInstrumentMetadata(setOf(ticker))) {
                is Trading212Client.T212Result.Success -> r.data[ticker]?.isin
                else -> null
            }
            val tg = isin?.let {
                GermanRealtime.checkedQuote(config.appContext, it, ys, ticker,
                    yahooLive ?: uiRef, feedStale = stopped)
            }
            if (tg != null) return tg.mid
        }
        if (yahooLive != null) return yahooLive
        if (stopped) BrokerHoldings.t212Price(config.appContext, ticker, space)
            .takeIf { it > 0.0 }?.let { return it }
        return uiRef
    }

    /**
     * Resolve with one rate-limit retry. T212's instrument-list endpoint allows
     * 1 request / 30s; on a cold cache the first resolve can fail with "list not
     * loaded yet" (observed live with AVGO). We're on a background thread, so
     * waiting out one window beats erroring at the caller.
     */
    private fun resolveWithRetry(client: Trading212Client, sym: String, spc: String): String? {
        client.resolveT212Ticker(sym, spc)?.let { return it }
        Thread.sleep(31_000L)
        return client.resolveT212Ticker(sym, spc)
    }

    /**
     * Throws when the symbol has no session open to trade into. Unknown → proceed.
     *
     * US NAMES MAY USE EXTENDED HOURS; EVERYWHERE ELSE IS UNCHANGED. Trading 212
     * runs pre-market and after-hours sessions on its more liquid US stocks, so
     * an entry decided on an evening bar has somewhere to go rather than waiting
     * for the next open. Xetra has no such session, and asking for one there
     * returns the regular bounds anyway — so this is US-only by effect as much
     * as by the flag.
     *
     * THE ORIGINAL OBJECTION STILL STANDS AND IS WORTH KEEPING IN VIEW: a ladder
     * walks from a good price toward a worse one, and a thin book fills none of
     * it, so it can reach its cap having bought nothing (observed live on MELI at
     * 22:55 CET). What makes that survivable is the cleanup at the end of the
     * loop — an unfilled ladder cancels its last resting order and says so if the
     * cancel failed, so the worst case is a wasted pass rather than a stale limit
     * waiting to be hit at tomorrow's open.
     */
    /**
     * Throws when the name's price feed has stopped — for a BUY only.
     *
     * Read straight after [requireMarketOpen], whose reply is what judged it, so
     * the verdict is as fresh as the question. A sale is never refused for this:
     * getting out of a name the app can no longer see is the one trade that
     * stays sensible.
     *
     * Why refuse at all: everything that would look after the position once
     * bought — its stop, its target, its value — reads the same frozen price, and
     * the signal that asked for the buy was drawn on it. Pfisterer was bought on
     * 14 Oct exactly like this, a week into its feed being frozen.
     */
    private fun requireLiveFeed(config: ConfigManager, yahooSymbol: String, space: String) {
        val ys = yahooSymbol.trim()
        val since = FeedHealth.stoppedSince(ys)
        if (since <= 0L) return
        val sym = ys.uppercase().substringBefore('.')
        try {
            LabNotifier.feedStopped(config.appContext, space.lowercase(), sym, ys, since, held = false)
        } catch (e: Exception) { Log.w(TAG, "feed alarm failed", e) }
        throw IllegalArgumentException(
            "Not buying $sym: its price feed ($ys) has shown no trade since " +
            "${FeedHealth.describe(since)} although the market is open, so nothing could watch " +
            "a stop or a target on it. Nothing was placed.")
    }

    private fun requireMarketOpen(yahooSymbol: String, space: String) {
        val extended = space.uppercase() == "US"
        if (YahooFinanceClient.fetchMarketOpen(yahooSymbol.trim(), extended) == false)
            throw IllegalArgumentException(
                "Market for $yahooSymbol is closed" +
                (if (extended) " — including its pre-market and after-hours sessions" else "") +
                " — a patient ladder can't fill into a shut venue " +
                "(every step would just rest, ending at the worst price for nothing). " +
                "Retry during market hours, or use Buy now / Sell now if you want an order resting for the open.")
    }

    /**
     * The price this executor would trade against RIGHT NOW, for callers that
     * must decide whether to trade at all before dispatching a ladder.
     *
     * Exposed deliberately rather than letting the caller fetch its own quote.
     * A gate that approves a purchase on one price source while the ladder buys
     * off another is not a gate — on German names this reads a Tradegate mid and
     * the plain Yahoo quote can differ by more than the entire ceiling being
     * enforced, so the check would pass on a number nothing was ever going to
     * pay. Same function, same answer, one source of truth.
     */
    fun currentRef(config: ConfigManager, ticker: String, yahooSymbol: String,
                   space: String, fallback: Double): Double =
        try { freshRef(config, Trading212Client(config), ticker, yahooSymbol, space, fallback) }
        catch (e: Exception) { Log.w(TAG, "currentRef failed", e); fallback }

    /**
     * The same price, but able to say it does not know.
     *
     * [currentRef] answers a question the LADDER asks, where a fallback is right:
     * it is already committed to trading and needs some number to rest an order
     * near. A GATE asks the opposite question — may this trade happen at all —
     * and there a fallback is the worst possible answer, because the fallback
     * handed in is the signal price itself. The gate then compares the signal
     * with the signal, finds no movement, and approves a name that has gapped.
     *
     * That is not hypothetical: it is the shape of the exact failure the entry
     * ceiling was written to prevent, reappearing on the path that runs when the
     * feed is busy — which at an opening bell is most of them.
     *
     * Null means unknown. A caller that can afford to wait must wait.
     */
    fun currentRefOrNull(config: ConfigManager, ticker: String, yahooSymbol: String,
                         space: String): Double? =
        try {
            // A sentinel no real quote can equal, so "the fallback came back" is
            // distinguishable from "a price came back".
            val probe = -1.0
            val v = freshRef(config, Trading212Client(config), ticker, yahooSymbol, space, probe)
            if (v > 0.0 && v != probe) v else null
        } catch (e: Exception) { Log.w(TAG, "currentRefOrNull failed", e); null }

    /** The same ATR the ladder sizes its concessions with, for callers that need
     *  to express a limit in the units this executor actually thinks in. */
    fun dailyAtr(yahooSymbol: String, space: String, refPrice: Double): Double =
        estimateAtr(yahooSymbol, space, refPrice)

    /** 14-day ATR in price units; 1.5%-of-price fallback. */
    private fun estimateAtr(yahooSymbol: String, space: String, refPrice: Double): Double {
        return try {
            val exch = if (space.equals("DE", true)) "DE" else "NASDAQ"
            val cr = YahooFinanceClient.fetchCandles(yahooSymbol.trim(), exch)
            val cs = (cr as? YahooFinanceClient.CandleResult.Success)?.candles
            if (cs != null && cs.size >= 16) {
                val a = com.signalscope.app.util.Indicators.atr(
                    cs.map { it.high }, cs.map { it.low }, cs.map { it.close }, 14
                ).lastOrNull { !it.isNaN() && it > 0.0 }
                if (a != null && a > 0.0) a else refPrice * 0.015
            } else refPrice * 0.015
        } catch (e: Exception) { Log.w(TAG, "estimateAtr failed", e); refPrice * 0.015 }
    }

    /**
     * Cancel [orderId] and CONFIRM — via T212's own pending-orders list — that
     * it's actually gone before the caller trusts it.
     *
     * Root cause of the double-buy bug: cancelOrder() can return a Failure (rate
     * limit, transient 5xx, order already filled, ...) WITHOUT throwing, so a
     * `try { cancel } catch {}` silently discarded the failure and the loop placed
     * the NEXT rung as if the previous were gone. Two resting buy orders on one
     * ticker, and a price that ticks through both, is how you get bought twice.
     */
    private fun cancelAndConfirmGone(client: Trading212Client, orderId: Long): CancelOutcome {
        repeat(3) { attempt ->
            when (val r = client.cancelOrder(orderId)) {
                is Trading212Client.T212Result.Success -> return CancelOutcome(true, false)
                is Trading212Client.T212Result.Failure -> {
                    if (r.httpCode == 404) return CancelOutcome(true, true) // gone — most likely a fill
                    if (attempt < 2) Thread.sleep(1500L)
                }
                is Trading212Client.T212Result.NotConfigured -> return CancelOutcome(false, false)
            }
        }
        return try {
            when (val r = client.fetchOpenOrders()) {
                is Trading212Client.T212Result.Success -> {
                    val stillOpen = com.google.gson.JsonParser.parseString(r.data).asJsonArray
                        .any { it.asJsonObject.get("id")?.asLong == orderId }
                    CancelOutcome(gone = !stillOpen, likelyFilled = !stillOpen)
                }
                else -> CancelOutcome(false, false)   // couldn't check — assume it may still rest
            }
        } catch (e: Exception) { CancelOutcome(false, false) }
    }

    /**
     * Poll [ownedQty] until it moves away from [baseline] or attempts run out.
     *
     * T212's /equity/portfolio can lag seconds behind a fill. Reading once right
     * after a suspected fill and trusting it is how the ladder re-sells shares that
     * are already gone. Spacing MUST exceed T212's portfolio limit (1 req / 5s):
     * at 2s every poll after the first got 429 → "unknown" → the ladder fell back
     * to its stale quantity and re-sold an already-sold position (observed live:
     * SAX filled at rung 1 yet the ladder walked to rung 5 and said "unfilled").
     */
    private fun awaitQtyChange(
        ownedQty: () -> Double, baseline: Double, maxTries: Int = 4, delayMs: Long = 6000L
    ): Double {
        var v = ownedQty(); var tries = 0
        while (tries < maxTries && (v < 0 || Math.abs(v - baseline) < 1e-9)) {
            Thread.sleep(delayMs); v = ownedQty(); tries++
        }
        return v
    }

    // ── SELL ────────────────────────────────────────────────────────────────

    /**
     * @param targetQty SELL EXACTLY THIS MUCH, or null to LIQUIDATE the position.
     *
     * The distinction is not cosmetic. T212 holds ONE pooled position per
     * instrument, shared by every algo, the test bench, and anything traded by
     * hand. This ladder was written for the Portfolio "sell to cash" button, whose
     * job is to empty a holding — so it re-read the broker after every rung and
     * kept selling until the position hit dust, treating [quantity] as nothing more
     * than a fallback for a failed portfolio read.
     *
     * Handed to a partial claimant that behaviour sells other people's shares. It
     * did: a bench trade that bought 1.239 AIXA sold 1.317 — its own shares plus
     * the 0.078 the user held manually in the same instrument. With two algos on
     * one ticker, whichever exits first would liquidate the other's position too.
     *
     * Pass a number and the ladder sells that much and stops, still re-reading the
     * broker between rungs so a fill during a dwell can never double-sell. Pass
     * null for the original liquidate semantics.
     *
     * @param claimedByOthers shares of the same pooled position that OTHER live rows
     *   own. Anything in the pool beyond [targetQty] + this is unclaimed remainder,
     *   and if it is worth less than T212's €1 floor it gets SWEPT into this sale —
     *   see the note at the sweep site for why that is the safe choice.
     */
    fun sell(
        config: ConfigManager, ticker: String, yahooSymbol: String, space: String,
        quantity: Double, refPrice: Double,
        onProgress: ((Map<String, Any?>) -> Unit)? = null,
        targetQty: Double? = null,
        claimedByOthers: Double = 0.0
    ): Result =
        // HELD FOR THE WHOLE LADDER, so a resting target cannot be put back on
        // this stock halfway through the sale — see [SellGuard].
        SellGuard.selling(ticker) {
            sellLadder(config, ticker, yahooSymbol, space, quantity, refPrice,
                onProgress, targetQty, claimedByOthers)
        }

    private fun sellLadder(
        config: ConfigManager, ticker: String, yahooSymbol: String, space: String,
        quantity: Double, refPrice: Double,
        onProgress: ((Map<String, Any?>) -> Unit)?,
        targetQty: Double?,
        claimedByOthers: Double
    ): Result {
        val t = ticker.trim()
        return try {
            if (quantity <= 0.0) throw IllegalArgumentException("No held quantity to sell")
            if (refPrice <= 0.0) throw IllegalArgumentException("No reference price — run a scan or wait for the live quote")
            val client = Trading212Client(config)
            // `space` rather than a normalised copy: the sell path has no such
            // local, and the gate normalises what it is given.
            requireMarketOpen(yahooSymbol, space)
            val ref = freshRef(config, client, t, yahooSymbol, space, refPrice)
            val atr = estimateAtr(yahooSymbol, space, ref)
            // HYBRID ladder: the concession schedule (+START×ATR shrinking to
            // −FLOOR×ATR) applies to a FRESH market quote at every rung, so a moving
            // market can't strand the order at a stale anchor. The one thing fixed
            // from t=0 is the absolute floor — the price discipline. If the market
            // falls past it we cancel and notify rather than follow it down.
            val hardFloor = Math.round((ref - SELL_FLOOR_ATR * atr) * 100.0) / 100.0
            val offsets = ArrayList<Double>()
            var off = SELL_START_ATR
            while (off >= -SELL_FLOOR_ATR - 1e-9) { offsets.add(off); off -= STEP_ATR }
            prog(onProgress, mapOf(
                "ticker" to t, "side" to "SELL", "rung" to 0, "totalRungs" to offsets.size,
                "price" to Math.round((ref + SELL_START_ATR * atr) * 100.0) / 100.0,
                "bound" to hardFloor, "atr" to atr, "note" to "start"))
            // Free any shares reserved by a resting SL/target first.
            try { client.cancelOpenOrdersForTicker(t) }
            catch (e: Exception) { Log.w(TAG, "patient pre-sell cancel failed", e) }

            // Live position = SINGLE SOURCE OF TRUTH, re-read after every rung so a
            // fill during a dwell can never double-sell.
            fun ownedQty(): Double =
                (client.fetchPortfolio() as? Trading212Client.T212Result.Success)
                    ?.data?.firstOrNull { it.ticker.equals(t, true) }?.quantity ?: -1.0

            val startOwned = ownedQty().let { if (it < 0) quantity else it }
            // THE CLAIM. Never more than the caller owns, never more than exists.
            // With targetQty == null this is startOwned and every expression below
            // reduces exactly to the original liquidate behaviour.
            var cap = targetQty?.let { minOf(it, startOwned) } ?: startOwned
            if (cap <= 0.0) return Result(ok = false, ticker = t, filled = 0.0,
                remaining = 0.0, note = "nothing to sell — broker holds none of it")

            // ── THE RESIDUE RULE, IN CURRENCY, AND THE DUST SWEEP ──────────────
            //
            // T212's floor is "one full unit of currency" — €1.00, not one share.
            // It governs the order AND what the order leaves behind: a sale must
            // either close the position outright or leave at least €1 of it.
            //
            // A partial exit is therefore fine in almost every real case: a €20
            // claim leaving a €20 sibling is nowhere near the floor. The trap is
            // leaving DUST, and dust is created by drift — a partial fill nobody
            // reconciled, a rounding remnant, an old hand-bought sliver.
            //
            // Split the pool three ways: ours, theirs, and whatever is left over.
            var swept = 0.0
            var unclaimed = (startOwned - cap - claimedByOthers).coerceAtLeast(0.0)
            if (targetQty != null && unclaimed > 1e-9 && unclaimed * ref < MIN_ORDER_VALUE) {
                // SWEEP IT. A sub-€1 holding cannot be sold on its own by anyone,
                // ever — so leaving it is not preserving value, it is stranding it,
                // and it also blocks this exit. No live row claims these shares, so
                // selling them takes nothing from another algo. The alternative is an
                // algo that can never close a position, which is far worse than
                // converting €0.99 of unsellable stock into €0.99 of cash.
                Log.i(TAG, "sweeping ${"%.5f".format(unclaimed)} of $t (≈${"%.2f".format(unclaimed * ref)}) " +
                    "— unclaimed and below the ${Trading212Client.MIN_POSITION_VALUE} floor")
                swept = minOf(unclaimed, startOwned - cap)
                cap = minOf(cap + unclaimed, startOwned)
                unclaimed = 0.0
            }
            // What is left after our sale, once any sweep is applied. Only a real
            // sibling claim should remain here — and if THAT is dust, no one can
            // exit cleanly and a human has to decide.
            val residue = startOwned - cap
            val residueValue = residue * ref
            if (targetQty != null && residue > 1e-9 && residueValue < MIN_ORDER_VALUE) return Result(
                ok = false, ticker = t, filled = 0.0, remaining = cap,
                note = "cannot sell only our ${"%.5f".format(cap)} — it would leave " +
                    "${"%.5f".format(residue)} of $t behind, worth about " +
                    "${"%.2f".format(residueValue)}, which another live row claims and T212 " +
                    "will not let anyone hold under " +
                    "${"%.2f".format(Trading212Client.MIN_POSITION_VALUE)}. Close both rows together " +
                    "from the Portfolio tab.")
            val dustFloor = maxOf(1e-6, cap * DUST_FRAC)
            val placedIds = ArrayList<Long>()
            var lastId: Long? = null
            var filledAny = false
            var lastKnownRemaining = startOwned
            var lastMarket = ref
            // The broker's own words for why an order was refused, carried out to the
            // caller instead of collapsing into "unfilled".
            var lastReject: String? = null

            rung@ for (i in offsets.indices) {
                var suspectFill = false
                lastId?.let { id ->
                    val outcome = cancelAndConfirmGone(client, id)
                    if (!outcome.gone) break@rung
                    suspectFill = outcome.likelyFilled
                    Thread.sleep(ORDER_SPACING_MS)
                }
                val remaining = if (suspectFill) awaitQtyChange({ ownedQty() }, lastKnownRemaining) else ownedQty()
                // Progress is measured against the CLAIM, not against zero: the
                // position may legitimately still hold shares that are not ours.
                val inPool = if (remaining >= 0.0) remaining else lastKnownRemaining
                val soldSoFar = (startOwned - inPool).coerceAtLeast(0.0)
                val left = cap - soldSoFar
                if (left <= dustFloor || left * ref < MIN_ORDER_VALUE) { filledAny = true; break@rung }
                // Order the smaller of "what we still owe" and "what is actually
                // there" — the second guard keeps a stale portfolio read from
                // requesting shares the account no longer has.
                val qty = minOf(left, inPool)
                if (qty <= 0.0) { filledAny = true; break@rung }
                if (remaining > 0) lastKnownRemaining = remaining
                val mkt = freshRef(config, client, t, yahooSymbol, space, lastMarket)
                lastMarket = mkt
                val price = Math.max(Math.round((mkt + offsets[i] * atr) * 100.0) / 100.0, hardFloor)
                prog(onProgress, mapOf(
                    "ticker" to t, "side" to "SELL", "rung" to (i + 1), "totalRungs" to offsets.size,
                    "price" to price, "remaining" to qty, "note" to "resting"))
                // PRECISION FALLBACK — the exact quantity is ALWAYS attempt one.
                //
                // Some instruments reject long decimals, so the BUY leg has always
                // walked 5→3→2→0 dp. Copying that to the SELL leg floor-first was a
                // regression: a liquidation used to pass the holding verbatim as T212
                // reported it (0.38314882) and close the position dead, but floored
                // to 5dp it becomes 0.38314 and leaves a 0.00000882 stub — which the
                // broker refuses outright. A Portfolio "Patient sell" that had always
                // worked started failing on exactly that.
                //
                // So: exact first, coarser only as a fallback, and never rounding UP,
                // which could sell a share belonging to another claimant.
                var placed = false
                val attempts = LinkedHashSet<Double>()
                attempts.add(qty)
                for (dp in intArrayOf(5, 3, 2, 0)) {
                    val f = Math.pow(10.0, dp.toDouble())
                    val q = Math.floor(qty * f) / f
                    if (q > 0.0) attempts.add(q)
                }
                prec@ for (q in attempts) {
                    var rate = 0
                    place@ while (true) {
                        when (val r = client.placeOrder(t, q, "SELL", "LIMIT", price, null)) {
                            is Trading212Client.T212Result.Success -> {
                                lastId = r.data; placedIds.add(r.data); placed = true; break@prec
                            }
                            is Trading212Client.T212Result.Failure -> {
                                if (r.httpCode == 429 && rate < 2) { rate++; Thread.sleep(ORDER_RATE_BACKOFF_MS); continue@place }
                                if (r.message.contains("precision", true)) continue@prec
                                // "No unreserved shares" is PROOF the position already
                                // sold (an earlier rung filled while the read lagged).
                                if (r.message.contains("selling-equity-not-owned", true) ||
                                    r.message.contains("no unreserved shares", true)) filledAny = true
                                // KEEP THE REASON. Discarding it turned every rejection
                                // — closed market, reserved shares, bad tick size — into
                                // the single word "unfilled", which is the least useful
                                // thing a diagnostic tool can say.
                                else lastReject = r.message
                                break@prec
                            }
                            is Trading212Client.T212Result.NotConfigured -> { lastId = null; break@rung }
                        }
                    }
                }
                if (!placed) { lastId = null; break@rung }
                Thread.sleep(DWELL_MS)
            }

            // The loop may have spent its rate-limit budget — one 429 here would
            // report "sold 0.0" for a filled order. Wait out the window and retry.
            var endOwned = ownedQty()
            if (endOwned < 0) { Thread.sleep(6000L); endOwned = ownedQty() }
            if (endOwned < 0) endOwned = if (filledAny) 0.0 else startOwned
            val sold = (startOwned - endOwned).coerceAtLeast(0.0)
            // Done when the CLAIM is discharged. Testing endOwned against dust asked
            // "is the position empty", which is a different and, for a partial
            // claimant, unreachable question. The value clause is the same one the
            // BUY leg uses: a residue too small to be a legal order is finished, not
            // unfilled — dropping it would report a 3%-of-a-€20-position leftover as
            // a failure when no order could ever clear it.
            if (sold >= cap - dustFloor || (cap - sold) * ref < MIN_ORDER_VALUE) filledAny = true
            var staleOrderWarning = false
            if (!filledAny) lastId?.let { id -> if (!cancelAndConfirmGone(client, id).gone) staleOrderWarning = true }

            // `remaining` = the unsold part of OUR claim, not the residual position.
            // Reporting the pool's residue would make a fully-discharged exit look
            // half-finished whenever a sibling algo still holds the same name.
            Result(ok = filledAny, ticker = t, filled = sold,
                remaining = (cap - sold).coerceAtLeast(0.0),
                note = when {
                    filledAny -> "filled"
                    lastReject != null -> "rejected by T212: $lastReject"
                    placedIds.isEmpty() -> "no order was accepted — nothing rested"
                    else -> "unfilled — rested to the floor without a fill"
                },
                staleOrder = staleOrderWarning,
                orderIds = placedIds, refPrice = ref, atr = atr,
                // Only what actually went out counts as swept: if the ladder filled
                // less than the full cap, the dust may still be sitting there.
                sweptQty = minOf(swept, sold))
        } catch (e: Exception) {
            Log.e(TAG, "patient sell failed", e)
            Result(ok = false, ticker = t, error = e.message ?: "patient sell failed")
        }
    }

    // ── BUY ─────────────────────────────────────────────────────────────────

    /**
     * @param maxPrice an ABSOLUTE per-share limit this ladder may never bid above,
     *   0 = none. Distinct from the internal cap, and the distinction is the whole
     *   point of the parameter: the internal one is `fresh quote + half an ATR`,
     *   which is anchored to wherever the market IS. That is right for a ladder
     *   chasing a moving price within a session, and useless as protection against
     *   a gap — the anchor moves with the gap, so the cap moves with it too, and a
     *   name that opened 6% above its signal is bought 6% high by a cap that is
     *   working exactly as designed. A caller that cares where the price is
     *   relative to its SIGNAL has to say so, and this is how.
     */
    fun buy(
        config: ConfigManager, symbol: String, yahooSymbol: String, space: String,
        cashAmount: Double, refPrice: Double,
        // BEFORE the callback, so callers that pass their progress handler as a
        // trailing lambda keep binding it to the callback rather than to this.
        maxPrice: Double = 0.0,
        onProgress: ((Map<String, Any?>) -> Unit)? = null
    ): Result {
        val spc = if (space.uppercase() == "DE") "DE" else "US"
        var tickerForMsg = symbol.trim()
        return try {
            if (cashAmount <= 0.0) throw IllegalArgumentException("Enter an amount greater than 0")
            if (refPrice <= 0.0) throw IllegalArgumentException("No reference price — run a scan or wait for the live quote")
            val client = Trading212Client(config)
            requireMarketOpen(yahooSymbol, spc)
            requireLiveFeed(config, yahooSymbol, spc)
            val raw = symbol.trim()
            val ticker = if (raw.contains("_")) raw else {
                val sym = raw.uppercase()
                resolveWithRetry(client, sym, spc)
                    ?: throw IllegalArgumentException("Couldn't resolve \"$sym\": ${client.resolveFailureReason(sym, spc)}")
            }
            tickerForMsg = ticker
            val ref = freshRef(config, client, ticker, yahooSymbol, space, refPrice)
            // REFUSE BEFORE PLACING ANYTHING. The market is already above the limit
            // the caller set, so no rung of this ladder could fill under it — and a
            // ladder that rests orders it does not want filled is how a stale limit
            // ends up sitting into the next session.
            if (maxPrice > 0.0 && ref > maxPrice)
                return Result(ok = false, ticker = ticker, filled = 0.0,
                    note = "price ${"%.2f".format(ref)} is above the ${"%.2f".format(maxPrice)} " +
                           "limit this entry may pay — nothing placed",
                    refPrice = ref)
            var lastFailure: String? = null
            val atr = estimateAtr(yahooSymbol, space, ref)
            // Concessions (0 → +BUY_MAX×ATR) apply to a FRESH quote each rung so a
            // rising market can't strand the order below reality. The absolute cap is
            // fixed at t=0 — if the market runs past it we cancel rather than chase.
            // WHICHEVER BINDS FIRST. The caller's limit is measured from its signal
            // and does not move; this one is measured from the market and does. A
            // ladder that clears the gate on the opening quote can still walk half
            // an ATR upward while it runs, straight back through the limit that
            // just approved it — so the limit has to live inside the loop too, not
            // only in front of it.
            val atrCap = Math.round((ref + BUY_MAX_ATR * atr) * 100.0) / 100.0
            val hardCap = if (maxPrice > 0.0) Math.min(atrCap, maxPrice) else atrCap
            val offsets = ArrayList<Double>()
            var off = 0.0
            while (off <= BUY_MAX_ATR + 1e-9) { offsets.add(off); off += STEP_ATR }
            prog(onProgress, mapOf(
                "ticker" to ticker, "side" to "BUY", "rung" to 0, "totalRungs" to offsets.size,
                "price" to ref, "bound" to hardCap, "atr" to atr, "note" to "start"))
            // Cap by SHARES (target from the LTP) so total spend can't run past the
            // cash asked for by more than the ATR cap. Baseline the position so
            // "how much have I already bought" is re-derived every rung.
            val targetShares = cashAmount / ref
            fun ownedQty(): Double =
                (client.fetchPortfolio() as? Trading212Client.T212Result.Success)
                    ?.data?.firstOrNull { it.ticker.equals(ticker, true) }?.quantity ?: -1.0
            val baseline = ownedQty().let { if (it < 0) 0.0 else it }
            val dustShares = maxOf(1e-6, targetShares * DUST_FRAC)
            val placedIds = ArrayList<Long>()
            var lastId: Long? = null
            var filledAny = false
            var lastKnownOwned = baseline
            var lastMarket = ref

            rung@ for (i in offsets.indices) {
                var suspectFill = false
                lastId?.let { id ->
                    val outcome = cancelAndConfirmGone(client, id)
                    if (!outcome.gone) break@rung
                    suspectFill = outcome.likelyFilled
                    Thread.sleep(ORDER_SPACING_MS)
                }
                val ownedNow = if (suspectFill) awaitQtyChange({ ownedQty() }, lastKnownOwned) else ownedQty()
                val boughtSoFar = if (ownedNow < 0) (lastKnownOwned - baseline).coerceAtLeast(0.0)
                                  else (ownedNow - baseline).coerceAtLeast(0.0)
                if (ownedNow >= 0) lastKnownOwned = ownedNow
                val need = targetShares - boughtSoFar
                if (need <= dustShares || need * ref < MIN_ORDER_VALUE) { filledAny = true; break@rung }
                val mkt = freshRef(config, client, ticker, yahooSymbol, space, lastMarket)
                lastMarket = mkt
                val price = Math.min(Math.round((mkt + offsets[i] * atr) * 100.0) / 100.0, hardCap)
                prog(onProgress, mapOf(
                    "ticker" to ticker, "side" to "BUY", "rung" to (i + 1), "totalRungs" to offsets.size,
                    "price" to price, "remaining" to need, "note" to "resting"))
                // Precision walk WITHIN the rung (no extra dwell): Euro instruments
                // reject 5dp quantities. A reject places nothing, so stepping coarser
                // is safe. A 429 also places nothing → back off and retry the SAME
                // precision (inner loop, so it does NOT drop to a coarser qty).
                var placed = false
                prec@ for (dp in intArrayOf(5, 3, 2, 0)) {
                    val f = Math.pow(10.0, dp.toDouble()); val q = Math.floor(need * f) / f
                    if (q <= 0.0) continue@prec
                    var rate = 0
                    place@ while (true) {
                        when (val r = client.placeOrder(ticker, q, "BUY", "LIMIT", price, null)) {
                            is Trading212Client.T212Result.Success -> {
                                lastId = r.data; placedIds.add(r.data); placed = true; break@prec
                            }
                            is Trading212Client.T212Result.Failure -> {
                                if (r.httpCode == 429 && rate < 2) { rate++; Thread.sleep(ORDER_RATE_BACKOFF_MS); continue@place }
                                // ONLY A FAILURE THAT ACTUALLY ENDED THE RUNG.
                                //
                                // The precision reject below is a NORMAL internal
                                // step — every US fractional order is offered at
                                // five decimals, refused, and re-offered at three,
                                // which works. Recording it made the app report
                                // "the broker said: invalid quantity precision" as
                                // though that were why nothing was bought, when in
                                // fact six orders had been placed perfectly well
                                // and simply found no seller after hours. A
                                // transient step dressed as the outcome is worse
                                // than no explanation at all.
                                if (r.message.contains("precision", true)) continue@prec
                                lastFailure = r.message
                                break@prec
                            }
                            is Trading212Client.T212Result.NotConfigured -> break@rung
                        }
                    }
                }
                if (!placed) break@rung
                Thread.sleep(DWELL_MS)
            }

            var ownedEnd = ownedQty()
            if (ownedEnd < 0) { Thread.sleep(6000L); ownedEnd = ownedQty() }
            val bought = if (ownedEnd < 0) (lastKnownOwned - baseline).coerceAtLeast(0.0)
                         else (ownedEnd - baseline).coerceAtLeast(0.0)
            if (targetShares - bought <= dustShares ||
                (targetShares - bought) * ref < MIN_ORDER_VALUE) filledAny = true
            var staleOrderWarning = false
            if (!filledAny) lastId?.let { id -> if (!cancelAndConfirmGone(client, id).gone) staleOrderWarning = true }

            Result(ok = filledAny, ticker = ticker, filled = bought,
                remaining = (targetShares - bought).coerceAtLeast(0.0),
                note = if (filledAny) "filled"
                       else "unfilled" + (lastFailure?.let { " — the broker said: $it" } ?: ""),
                staleOrder = staleOrderWarning,
                orderIds = placedIds, refPrice = ref, atr = atr)
        } catch (e: Exception) {
            Log.e(TAG, "patient buy failed", e)
            Result(ok = false, ticker = tickerForMsg, error = e.message ?: "patient buy failed")
        }
    }
}

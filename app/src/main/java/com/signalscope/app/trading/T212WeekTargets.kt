package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.ManualWatchStore
import com.signalscope.app.data.StrategyLabStore
import com.signalscope.app.data.T212LiveStore
import com.signalscope.app.data.T212TargetStore
import com.signalscope.app.network.Trading212Client
import org.json.JSONArray
import org.json.JSONObject

/**
 * THE RANGE TARGETS, kept at Trading 212 — [WeekTarget] holds the rule: near the
 * month's low or on strength, a tenth of the month's range short of its high;
 * near the week's low, the week's high.
 *
 * Runs on every background pass, AFTER the algos and the watchlist have done
 * their selling, so it never puts an order back that a sale on the same pass is
 * about to clear. For each position the app bought on Trading 212:
 *
 *   • once the buy has settled, works the target out from the week and month
 *     BEFORE the buy and records it — or records why there is none;
 *   • while the market is open and nothing rests, places one limit sell there,
 *     good until cancelled, for exactly that position's shares;
 *   • on every pass checks it is still waiting. Executed: closes the position
 *     everywhere it is recorded (the ledger, the algo's paper record or the
 *     watchlist book) and leaves the alert to the fill reader, which is the one
 *     place that knows the real price. Cancelled: puts it back;
 *   • once the position has ended some other way, makes sure nothing is left
 *     resting against shares that are no longer its own.
 *
 * It never sells anything itself and never touches a stop. Every sale the app
 * makes clears the stock's waiting orders before it starts, and [SellGuard]
 * keeps this from putting one back while that sale is running.
 */
object T212WeekTargets {
    private const val TAG = "T212WeekTargets"
    const val REASON = "range target"
    private const val ORIGIN = "its target at Trading 212"

    /** A refused order is tried again after this long, not on every pass. */
    private const val REFUSED_RETRY_MS = 60 * 60_000L
    private const val ORDER_SPACING_MS = 2500L
    private const val RATE_BACKOFF_MS = 6000L

    /** When targets were first switched on — positions bought earlier are
     *  treated with the extra care [WeekTarget.plan] describes. */
    private const val PREF_SINCE = "t212_week_targets_since"

    /** Real positions an algo or the watchlist bought on Trading 212. Not the
     *  test bench, not the legacy bare spaces, not another broker's rows. */
    internal fun applies(ns: String): Boolean {
        if (!Spaces.isBucket(ns)) return false
        val b = Spaces.baseOf(ns)
        return b == "de" || b == "us"
    }

    /** The shares this position owns — the smaller of what the buy reported and
     *  what the broker's history confirmed, so the target can never reach into
     *  another position's shares in the same pooled holding. */
    private fun ownQty(r: T212LiveStore.LiveTrade): Double =
        if (r.liveQtyFilled > 0.0) minOf(r.qty, r.liveQtyFilled) else r.qty

    private fun sinceMs(ctx: Context, now: Long): Long {
        val p = ctx.getSharedPreferences("signalscope_prefs", Context.MODE_PRIVATE)
        val v = p.getLong(PREF_SINCE, 0L)
        if (v > 0L) return v
        p.edit().putLong(PREF_SINCE, now).apply()
        return now
    }

    internal data class Resting(val id: Long, val ticker: String, val qty: Double, val filled: Double)

    /** The open-orders list, or null when it could not be read — which is NOT
     *  "nothing is waiting", and nothing below acts on it as if it were. */
    internal fun parseOpen(raw: String): Map<Long, Resting>? = try {
        val s = raw.trim()
        val arr: JSONArray = if (s.startsWith("[")) JSONArray(s) else {
            val o = JSONObject(s)
            listOf("items", "orders", "data").firstNotNullOfOrNull { o.optJSONArray(it) } ?: JSONArray()
        }
        val out = HashMap<Long, Resting>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optLong("id", 0L)
            if (id <= 0L) continue
            val st = o.optString("status", "").uppercase()
            if (st == "CANCELLED" || st == "FILLED" || st == "REJECTED") continue
            val tk = o.optString("ticker", "").ifBlank {
                o.optJSONObject("instrument")?.optString("ticker", "").orEmpty()
            }
            val q = o.optDouble("quantity", Double.NaN).let {
                if (it.isNaN()) o.optDouble("remainingQuantity", 0.0) else it
            }
            out[id] = Resting(id, tk, kotlin.math.abs(q), o.optDouble("filledQuantity", 0.0))
        }
        out
    } catch (e: Exception) { Log.w(TAG, "open-order list unreadable", e); null }

    private fun short(msg: String): String =
        msg.substringBefore(" payload=").substringBefore(" Hint:").trim().let {
            if (it.length > 140) it.take(138) + "…" else it
        }

    /** One pass. Returns a few words for the pass summary, blank when idle. */
    fun keep(ctx: Context): String {
        val cfg = ConfigManager(ctx)
        if (!cfg.hasT212Credentials) return ""
        val now = System.currentTimeMillis()
        val since = sinceMs(ctx, now)
        val ledger = T212LiveStore.load(ctx).trades.filter { applies(it.ns) }
        // NOT ON A POSITION BEING SOLD. When the strategy has closed the trade on
        // paper and the sale is waiting, the shares belong to that sale and to its
        // safety stop ([T212ExitGuards]); putting the target back after every
        // unfilled attempt would only fight them for the same shares.
        val selling = T212ExitGuards.waitingKeys(ctx, ledger)
        val held = ledger.filter {
            it.status == "OPEN" && it.qty > 0.0 && it.liveEntryPrice > 0.0 && !it.isManual() &&
                T212TargetStore.key(it.ns, it.paperTradeId) !in selling
        }
        val pending = T212TargetStore.load(ctx).targets.filter { !it.done }
        if (held.isEmpty() && pending.isEmpty()) return ""

        val client = Trading212Client(cfg)
        val resting: Map<Long, Resting> = when (val r = client.fetchOpenOrders()) {
            is Trading212Client.T212Result.Success -> parseOpen(r.data) ?: return ""
            is Trading212Client.T212Result.Failure -> {
                Log.w(TAG, "open orders unavailable: ${r.message}"); return ""
            }
            else -> return ""
        }
        // Read at most once a pass, and only if something actually needs them.
        // Null = could not be read, which every caller treats as "don't know".
        val fills: Map<Long, Trading212Client.HistoricalFill>? by lazy {
            (client.fetchOrderHistory(limit = 50) as? Trading212Client.T212Result.Success)
                ?.data?.filter { it.filledQuantity > 0.0 }?.associateBy { it.orderId }
        }
        val pooled: Map<String, Double>? by lazy {
            (BrokerHoldings.t212(ctx) as? BrokerHoldings.Result.Ok)
                ?.holdings?.associate { it.symbol.uppercase() to it.qty }
        }
        fun filledUnder(t: T212TargetStore.Target): Trading212Client.HistoricalFill? {
            val ids = listOfNotNull(t.orderId.takeIf { it > 0L }) + (t.prevIds ?: emptyList())
            if (ids.isEmpty()) return null
            val f = fills ?: return null
            return ids.firstNotNullOfOrNull { f[it] }
        }

        var set = 0
        var sold = 0

        // ── POSITIONS THAT HAVE ENDED, OR TARGETS YOU TOOK OFF ───────────────
        for (t in pending) {
            val row = ledger.firstOrNull { it.ns == t.ns && it.paperTradeId == t.paperTradeId }
            val live = row != null && (row.status == "OPEN" || row.status == "EXITING" || row.status == "PENDING")
            if (live && !t.removed) continue
            // A sale is in flight on this one: whatever happens next is its call.
            if (row?.status == "EXITING") continue
            try {
                // Sold by the target, and the paper side still to be told.
                if (t.filledAt > 0L) {
                    if (row != null && !t.booked) book(ctx, row, t.fillPrice, t.fillQty, now, reasonFor(t))
                    else T212TargetStore.update(ctx, t.ns, t.paperTradeId, { t }) {
                        it.copy(done = true, doneAt = now)
                    }
                    continue
                }
                // Executed after all — reported late, or found by a reconcile that
                // closed the row before this pass saw the fill.
                val hit = if (t.orderId > 0L && resting.containsKey(t.orderId)) null else filledUnder(t)
                if (hit != null && row != null &&
                    (row.status == "OPEN" || (row.status == "CLOSED" && row.allSellIds().isEmpty()))) {
                    recordFill(ctx, row, t, hit, now); sold++; continue
                }
                // Still waiting against a position that has ended, or one you took
                // the target off: cancel it.
                if (t.orderId > 0L && resting.containsKey(t.orderId)) {
                    val ok = SellGuard.ifIdle(t.ticker) {
                        client.cancelOrder(t.orderId) is Trading212Client.T212Result.Success
                    } ?: false
                    if (!ok) continue                      // next pass
                    Thread.sleep(ORDER_SPACING_MS)
                }
                T212TargetStore.update(ctx, t.ns, t.paperTradeId, { t }) {
                    it.copy(orderId = 0L, prevIds = null, done = true, doneAt = now,
                        note = when {
                            it.removed -> "taken off by you"
                            it.price > 0.0 -> "the position was sold another way before reaching it"
                            else -> it.note
                        })
                }
                Log.i(TAG, "[${t.ns}] ${t.symbol} target finished (${if (t.removed) "removed" else "position ended"})")
            } catch (e: Exception) { Log.w(TAG, "[${t.ns}] ${t.symbol} target tidy-up failed", e) }
        }

        // ── POSITIONS STILL HELD ─────────────────────────────────────────────
        for (row in held) {
            try {
                val existing = T212TargetStore.get(ctx, row.ns, row.paperTradeId)
                if (existing != null && (existing.done || existing.removed)) continue

                // The target sold it, and something has since written the row back
                // to open from an older copy. Say it again; place nothing.
                if (existing != null && existing.filledAt > 0L) {
                    T212LiveStore.get(ctx, row.paperTradeId, row.ns)?.takeIf { it.status == "OPEN" }?.let {
                        T212LiveStore.upsert(ctx, it.copy(status = "CLOSED"))
                    }
                    continue
                }

                // ── 1. DECIDE THE TARGET, ONCE ──────────────────────────────────
                // Once per RULE: a position the old week-high-only rule left without
                // a target is decided again under the range rule, as a position
                // already held. One that got a target keeps it.
                val stale = existing != null && existing.rule == null && existing.price <= 0.0 &&
                    existing.orderId == 0L && existing.filledAt == 0L
                val t = (if (existing == null || stale) decide(ctx, row, since, now, redo = stale) else existing)
                    ?: continue
                if (t.price <= 0.0) continue               // none, and the card says why

                val want = ownQty(row)
                if (want <= 0.0) continue
                val marketOpen = MarketHours.isRegularSession(Spaces.baseOf(row.ns), now)
                val cur = t.orderId > 0L && resting.containsKey(t.orderId)

                // ── 2. DID IT SELL? ─────────────────────────────────────────────
                // Under the current order once it has gone, or under an earlier one
                // whose fill the history reported late — then the order resting now
                // is reserving shares that are no longer there, and comes off first.
                val late = if (cur && !t.prevIds.isNullOrEmpty())
                    fills?.let { f -> t.prevIds!!.firstNotNullOfOrNull { f[it] } } else null
                val hit = if (!cur) filledUnder(t) else late
                if (hit != null) {
                    // The leftover order comes off FIRST, and the sale is booked only
                    // once it has. Booking clears the order from this record; doing
                    // that with the order still at the broker would leave a sell
                    // there that nothing remembers.
                    if (cur) {
                        val off = SellGuard.ifIdle(row.t212Ticker) {
                            client.cancelOrder(t.orderId) is Trading212Client.T212Result.Success
                        } ?: false
                        if (!off) continue                 // next pass
                    }
                    recordFill(ctx, row, t, hit, now); sold++
                    continue
                }

                // ── 3. IS IT STILL WAITING, AND FOR THE RIGHT NUMBER OF SHARES? ──
                // Only an order that has gone missing needs the history or the
                // holdings — an ordinary pass reads neither.
                val missing = t.orderId > 0L && !cur
                val there: Boolean? = if (missing && fills != null) pooled?.let { p ->
                    val have = p[row.symbol.uppercase()] ?: 0.0
                    val others = T212LiveStore.otherClaimsQty(ctx, row.t212Ticker, row.ns, row.paperTradeId)
                    have >= want + others - 1e-6
                } else null
                // A partly executed order is left exactly as it is.
                val restingQty = resting.mapValues { (_, r) -> if (r.filled > 0.0) want else r.qty }
                val filledIds = if (missing) (fills?.keys ?: emptySet()) else emptySet()
                when (WeekTarget.step(t.orderId, want, restingQty, filledIds, there, t.goneSince, now)) {
                    WeekTarget.Step.KEEP -> {
                        if (t.goneSince > 0L || (!t.prevIds.isNullOrEmpty() && now - t.placedAt > WeekTarget.GIVE_UP_MS))
                            T212TargetStore.update(ctx, row.ns, row.paperTradeId, { t }) {
                                it.copy(goneSince = 0L,
                                    prevIds = if (now - it.placedAt > WeekTarget.GIVE_UP_MS) null else it.prevIds)
                            }
                    }
                    WeekTarget.Step.WAIT -> if (t.goneSince == 0L)
                        T212TargetStore.update(ctx, row.ns, row.paperTradeId, { t }) { it.copy(goneSince = now) }
                    WeekTarget.Step.ABANDON -> {
                        T212TargetStore.update(ctx, row.ns, row.paperTradeId, { t }) {
                            it.copy(orderId = 0L, done = true, doneAt = now,
                                note = "its waiting order disappeared and so did the shares, but Trading 212 " +
                                    "never reported it executing — check the account")
                        }
                        Log.w(TAG, "[${row.ns}] ${row.symbol} target order vanished with the shares, no fill reported")
                    }
                    WeekTarget.Step.FILLED -> Unit         // handled above
                    WeekTarget.Step.FORGET -> {
                        Log.w(TAG, "[${row.ns}] ${row.symbol} target order ${t.orderId} was cancelled — putting it back")
                        val t2 = T212TargetStore.update(ctx, row.ns, row.paperTradeId, { t }) {
                            it.copy(prevIds = (it.prevIds ?: emptyList()) + it.orderId, orderId = 0L,
                                goneSince = 0L, note = null)
                        }
                        if (marketOpen && place(ctx, client, row, t2, want, now)) set++
                    }
                    WeekTarget.Step.REPLACE -> if (marketOpen) {
                        val cancelled = SellGuard.ifIdle(row.t212Ticker) {
                            client.cancelOrder(t.orderId) is Trading212Client.T212Result.Success
                        } ?: false
                        if (cancelled) {
                            Thread.sleep(ORDER_SPACING_MS)
                            val t2 = T212TargetStore.update(ctx, row.ns, row.paperTradeId, { t }) {
                                it.copy(prevIds = (it.prevIds ?: emptyList()) + it.orderId, orderId = 0L)
                            }
                            if (place(ctx, client, row, t2, want, now)) set++
                        }
                    }
                    WeekTarget.Step.PLACE -> if (marketOpen && now >= t.retryAfter) {
                        if (place(ctx, client, row, t, want, now)) set++
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "[${row.ns}] ${row.symbol} target pass failed", e) }
        }
        return listOfNotNull(
            if (set > 0) "🎯 $set target${if (set == 1) "" else "s"} set" else null,
            if (sold > 0) "🎯 $sold sold at target" else null).joinToString(" · ")
    }

    /**
     * Work the target out once, from the week and month BEFORE the buy, and
     * record it — or record why there is none. Null when it cannot be decided yet
     * (the bars did not arrive, or a position held from before has no price to
     * judge by).
     *
     * @param redo deciding again under a new rule — the position is already held,
     *   so it gets the same care as one held from before targets existed.
     */
    private fun decide(ctx: Context, row: T212LiveStore.LiveTrade, since: Long, now: Long,
                       redo: Boolean = false): T212TargetStore.Target? {
        val buySec = if (row.buyAt > 0L) row.buyAt / 1000L else row.entryTs
        val ranges = StrategyLabRunner.rangesBefore(ctx, row.ns, row.symbol, buySec) ?: return null
        val pre = redo || row.buyAt <= 0L || row.buyAt < since
        // Held from before: whether it is already past the target decides what
        // happens, so it waits for a price rather than guess.
        val pxNow = if (pre) (MarkToMarket.priceNow(ctx, row.ns, row.symbol) ?: return null) else null
        val plan = WeekTarget.plan(ranges, row.liveEntryPrice, pxNow, pre,
            WeekTarget.minGapFor(Spaces.baseOf(row.ns)))
        Log.i(TAG, "[${row.ns}] ${row.symbol} target " +
            (if (plan.ok) "${plan.price} (${plan.label} before the buy; paid ${row.liveEntryPrice})"
             else "none — ${plan.why}") + (if (redo) " [decided again under the range rule]" else ""))
        return T212TargetStore.update(ctx, row.ns, row.paperTradeId, {
            T212TargetStore.Target(ns = row.ns, paperTradeId = row.paperTradeId,
                symbol = row.symbol.uppercase(), ticker = row.t212Ticker, createdAt = now)
        }) {
            it.copy(price = plan.price, paid = row.liveEntryPrice, note = plan.why.ifBlank { null },
                kind = plan.kind.ifBlank { null }, rule = WeekTarget.RULE)
        }
    }

    /**
     * Put the target in. Inside [SellGuard] and after re-reading the position,
     * because between the start of the pass and now a sale may have begun.
     * @return true when an order is now resting.
     */
    private fun place(ctx: Context, client: Trading212Client, row: T212LiveStore.LiveTrade,
                      t: T212TargetStore.Target, want: Double, now: Long): Boolean {
        var placedId = 0L
        var placedQty = 0.0
        val err: String = SellGuard.ifIdle(row.t212Ticker) {
            val fresh = T212LiveStore.get(ctx, row.paperTradeId, row.ns)
            if (fresh == null || fresh.status != "OPEN") return@ifIdle ""
            var last = ""
            for (q in WeekTarget.qtyAttempts(want)) {
                var rate = 0
                var next = false
                while (!next) {
                    when (val r = client.placeOrder(row.t212Ticker, q, "SELL", "LIMIT",
                                                    limitPrice = t.price, stopPrice = null,
                                                    timeValidity = "GOOD_TILL_CANCEL")) {
                        is Trading212Client.T212Result.Success -> {
                            placedId = r.data; placedQty = q; return@ifIdle ""
                        }
                        is Trading212Client.T212Result.Failure -> {
                            if (r.httpCode == 429 && rate < 2) { rate++; Thread.sleep(RATE_BACKOFF_MS) }
                            else if (r.message.contains("precision", true)) { last = r.message; next = true }
                            else return@ifIdle r.message
                        }
                        is Trading212Client.T212Result.NotConfigured -> return@ifIdle "Trading 212 is not set up"
                    }
                }
            }
            last.ifBlank { "refused" }
        } ?: return false                                  // a sale holds this stock right now

        if (placedId > 0L) {
            val after = T212TargetStore.update(ctx, row.ns, row.paperTradeId, { t }) {
                it.copy(orderId = placedId, orderQty = placedQty, placedAt = now, goneSince = 0L,
                    retryAfter = 0L, note = null)
            }
            Log.w(TAG, "[${row.ns}] ${row.symbol} TARGET resting: sell $placedQty at ${t.price} " +
                "(order $placedId, paid ${row.liveEntryPrice})")
            if (!after.announced) {
                try {
                    LabNotifier.weekTargetSet(ctx, row.ns, row.symbol, t.price, row.liveEntryPrice, placedQty,
                        if (Spaces.baseOf(row.ns) == "de") "EUR" else "USD", row.paperTradeId,
                        WeekTarget.Plan(t.price, kind = t.kind ?: "week").label)
                } catch (_: Exception) {}
                T212TargetStore.update(ctx, row.ns, row.paperTradeId, { after }) { it.copy(announced = true) }
            }
            Thread.sleep(ORDER_SPACING_MS)
            return true
        }
        if (err.isNotBlank()) {
            // NO FREE SHARES almost always means another sell order already holds
            // them — one you set yourself in the broker's app. Only one sell can
            // wait per share, so yours stays and this waits behind it.
            val held = err.contains("selling-equity-not-owned", true) || err.contains("owned: 0", true)
            T212TargetStore.update(ctx, row.ns, row.paperTradeId, { t }) {
                it.copy(retryAfter = now + REFUSED_RETRY_MS,
                    note = if (held)
                        "another sell order on this stock already holds the shares at Trading 212 " +
                            "(one you set yourself?) — only one can wait, so yours stays and this goes in " +
                            "if that one is cancelled. Checked again every hour"
                    else "Trading 212 refused the target order: ${short(err)} — trying again within the hour")
            }
            Log.w(TAG, "[${row.ns}] ${row.symbol} target refused: ${short(err)}")
        }
        return false
    }

    /** The target executed: close the position everywhere it is recorded. */
    private fun recordFill(ctx: Context, row: T212LiveStore.LiveTrade, t: T212TargetStore.Target,
                           fill: Trading212Client.HistoricalFill, now: Long) {
        val px = fill.fillPrice.takeIf { it > 0.0 } ?: t.price
        T212LiveStore.get(ctx, row.paperTradeId, row.ns)?.let { cur ->
            if (cur.status == "OPEN" || (cur.status == "CLOSED" && cur.allSellIds().isEmpty()))
                T212LiveStore.upsert(ctx, cur.copy(status = "CLOSED",
                    sellOrderId = fill.orderId, sellOrderIds = listOf(fill.orderId),
                    sellAt = if (cur.status == "CLOSED" && cur.sellAt > 0L) cur.sellAt else now,
                    exitTs = now / 1000L, paperExitPrice = t.price, refExitPrice = t.price, error = "",
                    exitOrigin = ORIGIN,
                    exitReasonNote = "reached its target of ${"%.2f".format(t.price)} " +
                        "(${WeekTarget.Plan(t.price, kind = t.kind ?: "week").label})"))
        }
        T212TargetStore.update(ctx, row.ns, row.paperTradeId, { t }) {
            it.copy(orderId = 0L, prevIds = null, goneSince = 0L, filledAt = now,
                fillPrice = px, fillQty = fill.filledQuantity, note = null)
        }
        Log.w(TAG, "[${row.ns}] ${row.symbol} SOLD AT TARGET ${t.price}: ${fill.filledQuantity} @ $px " +
            "(paid ${row.liveEntryPrice})")
        book(ctx, row, px, fill.filledQuantity, now, reasonFor(t))
        // The fill reader prices the sale off the same history entry and sends
        // the one alert that carries real money.
        try { T212LiveMirror.reconcileFills(ctx) }
        catch (e: Exception) { Log.w(TAG, "fill read after the target sale failed", e) }
    }

    /**
     * Close the books to match: the algo's paper record at the price the shares
     * actually sold for — credited like any other exit — or the watchlist book's
     * entry. Retried on later passes until it goes through.
     */
    /** The reason a target sale is filed under: the automatic rule's, or yours. */
    private fun reasonFor(t: T212TargetStore.Target): String =
        if (t.kind == "manual") REASON_YOURS else REASON

    /** A target sale at a price you set yourself — see [setManual]. */
    const val REASON_YOURS = "your own target"

    private fun book(ctx: Context, row: T212LiveStore.LiveTrade, px: Double, qty: Double, now: Long,
                     reason: String = REASON): Boolean {
        val ok = if (Spaces.bucketIdOf(row.ns) == ManualWatchStore.ID) {
            ManualWatchStore.mutate(ctx, Spaces.baseOf(row.ns)) { s ->
                s.copy(entries = s.entries.map { e ->
                    if (e.status == "OPEN" && "${e.symbol.uppercase()}-${e.signalBarTs}" == row.paperTradeId)
                        e.copy(status = "CLOSED", exitTs = now / 1000L, closedAt = now,
                            exitQty = if (qty > 0.0) qty else e.filledQty,
                            exitPrice = px, exitReason = reason)
                    else e
                })
            }
            true
        } else {
            StrategyLabRunner.withAlgoHeld(row.ns, 60_000L) {
                val open = StrategyLabStore.load(ctx, row.ns).trades
                    .firstOrNull { it.id == row.paperTradeId && it.status == "OPEN" }
                // Already closed on paper (its own exit came first): nothing to add.
                open == null || AlgoTakeProfit.bookPaperExit(ctx, row.ns, open, px, now, reason) != null
            } ?: false
        }
        T212TargetStore.update(ctx, row.ns, row.paperTradeId, {
            T212TargetStore.Target(ns = row.ns, paperTradeId = row.paperTradeId, symbol = row.symbol)
        }) { if (ok) it.copy(booked = true, done = true, doneAt = now) else it }
        if (!ok) Log.w(TAG, "[${row.ns}] ${row.symbol} paper record busy — booking the target sale next pass")
        return ok
    }

    /**
     * Take the target off a position. Cancelled here when nothing is selling the
     * stock at this moment; otherwise marked, and the next pass finishes the job
     * — including booking a sale, if it turns out the order had already executed.
     */
    fun remove(ctx: Context, ns: String, paperTradeId: String): String {
        val t = T212TargetStore.get(ctx, ns, paperTradeId) ?: return "There is no target on this position."
        if (t.done) return "This target has already finished."
        val now = System.currentTimeMillis()
        var cancelled = t.orderId <= 0L
        if (t.orderId > 0L) {
            val r = SellGuard.ifIdle(t.ticker) { Trading212Client(ConfigManager(ctx)).cancelOrder(t.orderId) }
            cancelled = r is Trading212Client.T212Result.Success
        }
        T212TargetStore.update(ctx, ns, paperTradeId, { t }) {
            if (cancelled) it.copy(removed = true, orderId = 0L, done = true, doneAt = now, note = "taken off by you")
            else it.copy(removed = true)
        }
        Log.w(TAG, "[$ns] ${t.symbol} target taken off by hand (cancelled now: $cancelled)")
        return if (cancelled) "Target on ${t.symbol} taken off. The stop is still watched by the app."
               else "Target on ${t.symbol} marked to come off — Trading 212 did not confirm the cancel just now, " +
                    "so the next check finishes it."
    }

    /**
     * YOUR PRICE AS THIS POSITION'S TARGET, in place of the automatic one.
     *
     * Not a second order beside it: Trading 212 lets one sell wait per share, so
     * an order of your own next to the app's would have the two fighting over the
     * same shares. This changes the position's own target instead — the waiting
     * order comes off, yours goes in, and from then on it is kept exactly like an
     * automatic one: put back if cancelled, and when it sells, the position is
     * closed and the sale booked to the algo (or the watchlist) at the price the
     * shares actually went for, matched by the order itself.
     *
     * Never re-decided: a target you set stays yours until you change or remove it.
     */
    fun setManual(ctx: Context, ns: String, paperTradeId: String, price: Double): String {
        val cfg = ConfigManager(ctx)
        if (!cfg.hasT212Credentials) return "Trading 212 is not set up."
        val row = T212LiveStore.get(ctx, paperTradeId, ns)
            ?: return "That position is not in the app's records."
        if (row.status != "OPEN" || row.qty <= 0.0 || row.liveEntryPrice <= 0.0)
            return "That position is not open, or its buy has not settled yet."
        val px = WeekTarget.floorCent(price)
        if (!(px > 0.0)) return "Enter a price above zero."
        // A limit sell under the market sells at once. Said here rather than
        // discovered as a sale nobody meant — Take profit is the button for that.
        val market = MarkToMarket.get(row.symbol)?.price ?: 0.0
        if (market > 0.0 && px <= market)
            return "${"%.2f".format(px)} is at or below the current price (${"%.2f".format(market)}), so it " +
                "would sell straight away. Use Take profit to sell now, or pick a higher price."
        val now = System.currentTimeMillis()
        val existing = T212TargetStore.get(ctx, ns, paperTradeId)
        val client = Trading212Client(cfg)
        if (existing != null && existing.orderId > 0L) {
            val off = SellGuard.ifIdle(row.t212Ticker) {
                client.cancelOrder(existing.orderId) is Trading212Client.T212Result.Success
            } ?: false
            if (!off) return "A sale is running on ${row.symbol}, or Trading 212 did not confirm taking the " +
                "waiting target off — nothing was changed. Try again in a minute."
            Thread.sleep(ORDER_SPACING_MS)
        }
        val t = T212TargetStore.update(ctx, ns, paperTradeId, {
            T212TargetStore.Target(ns = ns, paperTradeId = paperTradeId, symbol = row.symbol.uppercase(),
                ticker = row.t212Ticker, createdAt = now)
        }) {
            // The order just cancelled is kept among the earlier ids, so a fill
            // the broker reports late is still matched to this position.
            it.copy(price = px, paid = row.liveEntryPrice, kind = "manual", rule = WeekTarget.RULE,
                note = null, removed = false, done = false, doneAt = 0L,
                prevIds = ((it.prevIds ?: emptyList()) + listOfNotNull(it.orderId.takeIf { id -> id > 0L })).distinct(),
                orderId = 0L, goneSince = 0L, retryAfter = 0L, announced = true)
        }
        Log.w(TAG, "[$ns] ${row.symbol} target set by hand at $px (was ${existing?.price ?: 0.0})")
        val who = ownerLabel(ctx, ns)
        val want = ownQty(row)
        if (!MarketHours.isRegularSession(Spaces.baseOf(ns), now))
            return "Target for ${row.symbol} set at ${"%.2f".format(px)}. The market is closed, so it goes in at " +
                "Trading 212 on the first check after the open. When it sells, the sale is booked to $who."
        if (want > 0.0 && place(ctx, client, row, t, want, now))
            return "Target for ${row.symbol} set at ${"%.2f".format(px)} and waiting at Trading 212. When it sells, " +
                "the sale is booked to $who like any of its exits."
        val why = T212TargetStore.get(ctx, ns, paperTradeId)?.note ?: "it is tried again on the next check"
        return "Target for ${row.symbol} recorded at ${"%.2f".format(px)}, but it is not waiting at Trading 212 yet: $why."
    }

    /** "the algo B_Conservative" / "your watchlist" — for messages. */
    private fun ownerLabel(ctx: Context, ns: String): String =
        if (Spaces.bucketIdOf(ns) == ManualWatchStore.ID) "your watchlist"
        else "the algo " + (try { com.signalscope.app.data.BucketStore.get(ctx, ns)?.name } catch (e: Exception) { null }
            ?: ns)

    /** What the target window needs to open on a Trading 212 position. */
    fun context(ctx: Context, ns: String, paperTradeId: String): Map<String, Any?>? {
        val row = T212LiveStore.get(ctx, paperTradeId, ns) ?: return null
        if (row.status != "OPEN") return null
        val t = T212TargetStore.get(ctx, ns, paperTradeId)?.takeIf { !it.done && !it.removed && it.price > 0.0 }
        val base = Spaces.baseOf(ns)
        // The watchlist's own take-profit is watched by the app beside this
        // target, and whichever is lower sells first — worth saying in the window.
        val watchTp = if (Spaces.bucketIdOf(ns) == ManualWatchStore.ID) try {
            val st = ManualWatchStore.load(ctx, base)
            val e = st.entries.firstOrNull { it.status == "OPEN" && "${it.symbol.uppercase()}-${it.signalBarTs}" == paperTradeId }
            if (e != null && st.takeProfitPct > 0.0) ManualTakeProfit.targetFor(e.filledPrice, st.takeProfitPct) else 0.0
        } catch (e: Exception) { 0.0 } else 0.0
        return mapOf("ok" to true, "broker" to "t212", "ns" to ns, "pid" to paperTradeId,
            "ticker" to row.t212Ticker, "symbol" to row.symbol.uppercase(),
            "qty" to ownQty(row), "paid" to row.liveEntryPrice,
            // The price now — the cached one when there is one, else fetched: the
            // window is opened by hand and can wait a second for a real number.
            "ltp" to (MarkToMarket.get(row.symbol)?.price?.takeIf { it > 0.0 }
                ?: MarkToMarket.priceNow(ctx, ns, row.symbol) ?: 0.0),
            "currency" to (if (base == "de") "EUR" else "USD"), "space" to base.uppercase(),
            "owner" to ownerLabel(ctx, ns),
            "target" to (t?.price ?: 0.0), "targetLabel" to (t?.let { WeekTarget.Plan(it.price, kind = it.kind ?: "week").label } ?: ""),
            "resting" to ((t?.orderId ?: 0L) > 0L), "watchTp" to watchTp)
    }

    /**
     * The app position a whole Trading 212 holding belongs to, when it belongs to
     * exactly one — so a target set from the portfolio becomes that position's
     * target rather than a second order fighting it. Null when the holding is
     * shared (another position, or shares of your own beside the app's), because
     * then a target for the whole holding is not one position's to own.
     */
    fun soleOwner(ctx: Context, ticker: String, holdingQty: Double): Map<String, Any?>? {
        val rows = T212LiveStore.load(ctx).trades.filter {
            applies(it.ns) && it.status == "OPEN" && it.qty > 0.0 && it.liveEntryPrice > 0.0 &&
                !it.isManual() && it.t212Ticker.equals(ticker, true)
        }
        val r = rows.singleOrNull() ?: return null
        if (kotlin.math.abs(ownQty(r) - holdingQty) > 1e-4) return null
        return mapOf("ns" to r.ns, "pid" to r.paperTradeId, "owner" to ownerLabel(ctx, r.ns))
    }

    /** What the cards show, keyed "ns|paperTradeId". One file read for all rows. */
    fun views(ctx: Context): Map<String, Map<String, Any?>> =
        T212TargetStore.load(ctx).targets.associate { t ->
            t.key() to mapOf(
                "price" to t.price, "paid" to t.paid,
                // Which rule set it, in words; old rows were all the week's high.
                "label" to (if (t.price > 0.0) WeekTarget.Plan(t.price, kind = t.kind ?: "week").label else ""),
                "pct" to (if (t.paid > 0.0 && t.price > 0.0) (t.price / t.paid - 1.0) * 100.0 else null),
                "resting" to (t.orderId > 0L), "placedAt" to t.placedAt,
                "note" to (t.note ?: ""), "removed" to t.removed,
                "filled" to (t.filledAt > 0L), "fillPrice" to t.fillPrice, "done" to t.done)
        }
}

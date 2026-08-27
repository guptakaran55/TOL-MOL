package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.google.gson.JsonParser
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.GttActivityLog
import com.signalscope.app.data.T212OcoStore
import com.signalscope.app.network.Trading212Client
import com.signalscope.app.network.YahooFinanceClient
import com.signalscope.app.util.StockAnalyzer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * Trading 212 daily-trailing manager — the US-space equivalent of
 * `DailyPortfolioManager` for Zerodha.
 *
 * Two operations, both run in one pass (caller decides cadence):
 *
 *  1. RECONCILE  — for each persisted OCO pair, query T212's open-orders list.
 *                  If only one leg is still pending, the OTHER filled — cancel
 *                  the survivor and remove the pair (OCO contract honoured
 *                  retroactively). If both still pending, no-op. If both gone,
 *                  remove the pair (one filled then the other was manually
 *                  cancelled, or the position closed entirely).
 *
 *  2. TRAIL      — for each holding with quantity > 0:
 *                  a. Fetch Yahoo candles + run StockAnalyzer (same as IN scan).
 *                  b. Compute new SL = max(support, chandelier, hardFloor)
 *                     and new Target = max(resistance, LTP + 2×ATR), clamped.
 *                  c. If a live T212 SELL stop exists, cancel+replace it only
 *                     when the new stop is higher. If a live SELL limit target
 *                     exists, cancel+replace it only when the new target is
 *                     higher. This matches T212's one-reserved-exit limitation.
 *                  d. If a stored legacy pair exists:
 *                       - newSL > oldSL × 1.005? cancel old SL leg, place new
 *                         stop at newSL, update stored pair. (Monotonic — never
 *                         lower.)
 *                       - newTgt > oldTgt × 1.005? cancel old Target, place new
 *                         limit at newTgt, capped at oldTgt × 1.08 per pass
 *                         (anti-spike).
 *                  e. If no sell exit exists and auto-create is enabled, place
 *                     one protective STOP only. We never auto-create two T212
 *                     sell legs because the first pending sell reserves shares.
 *
 * Returns a structured summary so the caller (JS bridge) can render a
 * "Trail run complete: 2 SLs raised, 1 OCO survivor cancelled" toast.
 */
object Trading212TrailingManager {

    private const val TAG = "T212Trailing"

    data class Summary(
        val reconciledFilled: Int,        // pairs where one leg filled → survivor cancelled
        val reconciledClosed: Int,        // pairs where both legs gone → removed
        val slsRaised: Int,
        val targetsRaised: Int,
        val skipped: Int,
        val errors: List<String>,
        val log: List<String>
    )

    fun reconcileOnly(ctx: Context, config: ConfigManager): Summary {
        val log = mutableListOf<String>()
        val errors = mutableListOf<String>()
        if (!config.hasT212Credentials) {
            errors.add("T212 not configured")
            return Summary(0, 0, 0, 0, 0, errors, log)
        }
        val counts = reconcilePairs(ctx, Trading212Client(config), log, errors)
        return Summary(counts.filled, counts.closed, 0, 0, 0, errors, log)
    }

    /**
     * @param autoCreateOco legacy bridge flag. T212 no longer auto-creates
     *        two-leg OCOs; a pending sell reserves shares. New automation may
     *        create one protective STOP only when config.autoCreateGtt is on.
     */
    fun run(ctx: Context, config: ConfigManager, autoCreateOco: Boolean = false): Summary {
        val log = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var reconciledFilled = 0
        var reconciledClosed = 0
        var slsRaised = 0
        var targetsRaised = 0
        var skipped = 0

        if (!config.hasT212Credentials) {
            errors.add("T212 not configured")
            return Summary(0, 0, 0, 0, 0, errors, log)
        }
        val client = Trading212Client(config)

        // ── 1. RECONCILE persisted legacy pairs ───────────────────────────
        val reconcileCounts = reconcilePairs(ctx, client, log, errors)
        reconciledFilled += reconcileCounts.filled
        reconciledClosed += reconcileCounts.closed
        if (reconcileCounts.blocked) {
            return Summary(reconciledFilled, reconciledClosed, 0, 0, 0, errors, log)
        }

        // ── 2. TRAIL each holding ────────────────────────────────────────
        val holdings = when (val r = client.fetchPortfolio()) {
            is Trading212Client.T212Result.Success -> r.data
            is Trading212Client.T212Result.Failure -> {
                errors.add("portfolio: ${r.message}")
                return Summary(reconciledFilled, reconciledClosed, 0, 0, 0, errors, log)
            }
            is Trading212Client.T212Result.NotConfigured -> emptyList()
        }
        val pendingSellOrders = fetchPendingSellOrders(client, errors)
            ?: return Summary(reconciledFilled, reconciledClosed, 0, 0, 0, errors, log)
        val pendingByTicker = pendingSellOrders.groupBy { it.ticker }
        val weights = config.scoringWeights
        val activePairs = T212OcoStore.loadAll(ctx).associateBy { it.ticker }

        for (h in holdings) {
            if (h.quantity <= 0) continue
            try {
                var analysis: com.signalscope.app.data.StockAnalysis? = null
                for (candidate in client.t212ToYahooCandidates(h.ticker, null)) {
                    val exchange = if (candidate.endsWith(".DE", ignoreCase = true)) "DE" else "NASDAQ"
                    val cr = YahooFinanceClient.fetchCandles(candidate, exchange)
                    if (cr is YahooFinanceClient.CandleResult.Success) {
                        analysis = StockAnalyzer.analyze(
                            candles = cr.candles, symbol = candidate, name = candidate,
                            token = candidate, minAvgVolume = 0, w = weights
                        )
                        if (analysis != null) break
                    }
                }
                if (analysis == null) {
                    skipped++
                    log.add("${h.ticker}: no analysis (Yahoo fetch failed)")
                    continue
                }
                val existing = activePairs[h.ticker]
                val plan = OcoPlanner.planSellOco(
                    config,
                    OcoPlanner.Input(
                        ltp = h.currentPrice,
                        support = analysis.support,
                        resistance = analysis.resistance,
                        atr = analysis.atr,
                        avgPrice = existing?.avgPrice?.takeIf { it > 0.0 } ?: h.averagePrice,
                        projectedCeiling = analysis.projectedCeiling,
                        existingSl = existing?.slPrice,
                        tickSize = 0.01,
                        // Per-broker overlay: routes OcoPlanner to the T212
                        // SL curve (tighter — SL is sole exit, no Target leg)
                        // and the −15% hard floor (higher-ATR US/EU names).
                        broker = OcoPlanner.Broker.T212
                    )
                )
                if (!plan.ok) {
                    skipped++
                    log.add("${h.ticker}: no OCO plan (${plan.error ?: "invalid data"})")
                    continue
                }
                val newSlPrice = plan.sl
                val newTgtPrice = plan.target
                if (existing == null) {
                    // Filter pending T212 orders for this ticker down to true
                    // sell-exits. With the parser now letting positive-qty
                    // STOP/LIMIT orders through (to capture T212 UI-placed
                    // targets), we MUST disambiguate here: a positive-qty
                    // LIMIT below LTP is almost certainly a BUY-at-dip we
                    // placed from the watchlist, NOT a sell target.
                    val ltp = h.currentPrice
                    val liveExits = pendingByTicker[h.ticker].orEmpty().filter { o ->
                        when (o.kind) {
                            ExitKind.SL -> {
                                // STOP price below LTP = protective SL (correct)
                                val sp = o.stopPrice ?: o.price
                                sp > 0.0 && (ltp <= 0.0 || sp < ltp)
                            }
                            ExitKind.TARGET -> {
                                // LIMIT price above LTP = take-profit target.
                                // LIMIT price below LTP would be a buy-at-dip
                                // on the same ticker (watchlist case) — skip.
                                val lp = o.limitPrice ?: o.price
                                lp > 0.0 && (ltp <= 0.0 || lp > ltp)
                            }
                            else -> false
                        }
                    }

                    when {
                        liveExits.size > 1 -> {
                            skipped++
                            log.add("${h.ticker}: multiple live T212 sell exits found; leaving manual")
                        }
                        liveExits.size == 1 -> {
                            when (trailSingleExit(ctx, client, h, liveExits.first(), plan, config, log, errors)) {
                                ExitKind.SL -> slsRaised++
                                ExitKind.TARGET -> targetsRaised++
                                else -> skipped++
                            }
                        }
                        config.t212AutoCreateGtt -> {
                            if (createInitialStop(ctx, client, h, newSlPrice, log, errors)) {
                                slsRaised++
                            } else {
                                skipped++
                            }
                        }
                        else -> {
                            skipped++
                            log.add("${h.ticker}: no live T212 exit order; auto-create is off")
                        }
                    }
                    continue
                }

                // Trail SL leg if newSL is meaningfully higher (≥ 0.5% bump worth the API call)
                if (config.t212AutoTrailingEnabled && newSlPrice > existing.slPrice * 1.005 && !existing.simulated) {
                    log.add("${h.ticker}: SL ${existing.slPrice} → $newSlPrice")
                    val replacement = replaceLegacyPairLeg(
                        ctx = ctx,
                        client = client,
                        ticker = h.ticker,
                        orderId = existing.slOrderId,
                        qty = h.quantity,
                        oldType = "STOP",
                        oldLimit = null,
                        oldStop = existing.slPrice,
                        newType = "STOP",
                        newLimit = null,
                        newStop = newSlPrice,
                        errors = errors
                    )
                    if (replacement != null) {
                        T212OcoStore.update(ctx, h.ticker, slOrderId = replacement, slPrice = newSlPrice)
                        slsRaised++
                        GttActivityLog.append(ctx, "modify_sl", symbol = h.ticker,
                            fields = mapOf("broker" to "t212", "old" to existing.slPrice, "new" to newSlPrice, "id" to replacement))
                    }
                }

                // Trail Target leg, capped at +8%/day (anti-spike)
                if (config.autoTrailingTargetEnabled && !existing.simulated) {
                    val capped = min(newTgtPrice, existing.tgtPrice * (1.0 + config.targetMaxDailyStepPct))
                    if (capped > existing.tgtPrice * 1.005) {
                        val tgt = roundCent(capped)
                        log.add("${h.ticker}: TGT ${existing.tgtPrice} → $tgt")
                        val replacement = replaceLegacyPairLeg(
                            ctx = ctx,
                            client = client,
                            ticker = h.ticker,
                            orderId = existing.tgtOrderId,
                            qty = h.quantity,
                            oldType = "LIMIT",
                            oldLimit = existing.tgtPrice,
                            oldStop = null,
                            newType = "LIMIT",
                            newLimit = tgt,
                            newStop = null,
                            errors = errors
                        )
                        if (replacement != null) {
                            T212OcoStore.update(ctx, h.ticker, tgtOrderId = replacement, tgtPrice = tgt)
                            targetsRaised++
                            GttActivityLog.append(ctx, "modify_tgt", symbol = h.ticker,
                                fields = mapOf("broker" to "t212", "old" to existing.tgtPrice, "new" to tgt, "id" to replacement))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "trail failed for ${h.ticker}", e)
                errors.add("${h.ticker}: ${e.message ?: "exception"}")
            }
        }

        Log.i(TAG, "T212 trail complete: SLs=$slsRaised TGTs=$targetsRaised " +
                "filled=$reconciledFilled closed=$reconciledClosed skipped=$skipped errors=${errors.size}")
        return Summary(reconciledFilled, reconciledClosed, slsRaised, targetsRaised, skipped, errors, log)
    }

    /** T212 prices in USD/EUR — round to 2 decimal places (cent precision). */
    private enum class ExitKind { SL, TARGET, OTHER }

    private data class PendingSellOrder(
        val id: Long,
        val ticker: String,
        val type: String,
        val quantity: Double,
        val stopPrice: Double?,
        val limitPrice: Double?,
        val price: Double,
        val kind: ExitKind
    )

    private fun fetchPendingSellOrders(
        client: Trading212Client,
        errors: MutableList<String>
    ): List<PendingSellOrder>? {
        return when (val r = client.fetchOpenOrders()) {
            is Trading212Client.T212Result.Success -> {
                try {
                    parsePendingSellOrders(r.data)
                } catch (e: Exception) {
                    errors.add("open-order parse failed: ${e.message ?: "invalid payload"}")
                    null
                }
            }
            is Trading212Client.T212Result.Failure -> {
                errors.add("open orders: ${r.message}")
                null
            }
            is Trading212Client.T212Result.NotConfigured -> {
                errors.add("open orders: T212 not configured")
                null
            }
        }
    }

    private fun parsePendingSellOrders(raw: String): List<PendingSellOrder> {
        val root = JsonParser.parseString(raw)
        val arr = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject && root.asJsonObject.has("items") -> root.asJsonObject.getAsJsonArray("items")
            root.isJsonObject && root.asJsonObject.has("orders") -> root.asJsonObject.getAsJsonArray("orders")
            else -> return emptyList()
        }
        val out = mutableListOf<PendingSellOrder>()
        for (el in arr) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            val instrument = o.get("instrument")?.takeIf { it.isJsonObject }?.asJsonObject
            fun str(name: String): String? = o.get(name)?.takeIf { !it.isJsonNull }?.asString
            fun dbl(name: String): Double? = o.get(name)?.takeIf { !it.isJsonNull }?.asDouble

            val ticker = str("ticker")
                ?: instrument?.get("ticker")?.takeIf { !it.isJsonNull }?.asString
                ?: continue
            val status = str("status") ?: "PENDING"
            if (status.uppercase(Locale.US) in setOf("CANCELLED", "FILLED", "REJECTED")) continue

            val quantity = dbl("quantity") ?: dbl("remainingQuantity") ?: 0.0
            val side = (str("side") ?: if (quantity < 0.0) "SELL" else "BUY").uppercase(Locale.US)

            val id = o.get("id")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            if (id <= 0L) continue
            val type = str("type") ?: "ORDER"
            val typeKey = type.uppercase(Locale.US)
            // Earlier this point had: `if (side != "SELL" && quantity >= 0.0) continue`
            // which silently dropped T212 take-profit LIMIT orders that come back
            // with positive quantity and no `side` field (the UI-placed flow).
            // The user's TARGETs were vanishing here. Now we let STOP/LIMIT
            // exit-shaped orders through; the use site disambiguates SL vs
            // TARGET vs BUY-at-dip via price-vs-LTP comparison. Non-exit-shaped
            // BUY orders (MARKET buys, etc.) are still skipped.
            val isExitShape = typeKey.contains("STOP") || typeKey.contains("LIMIT")
            if (side != "SELL" && quantity >= 0.0 && !isExitShape) continue
            val stopPrice = dbl("stopPrice")
            val limitPrice = dbl("limitPrice")
            val kind = when {
                typeKey.contains("STOP") -> ExitKind.SL
                typeKey.contains("LIMIT") -> ExitKind.TARGET
                else -> ExitKind.OTHER
            }
            val price = when (kind) {
                ExitKind.SL -> stopPrice ?: limitPrice ?: 0.0
                ExitKind.TARGET -> limitPrice ?: stopPrice ?: 0.0
                ExitKind.OTHER -> stopPrice ?: limitPrice ?: 0.0
            }
            if (price <= 0.0) continue
            out.add(PendingSellOrder(
                id = id,
                ticker = ticker,
                type = type,
                quantity = abs(quantity),
                stopPrice = stopPrice,
                limitPrice = limitPrice,
                price = price,
                kind = kind
            ))
        }
        return out
    }

    private fun trailSingleExit(
        ctx: Context,
        client: Trading212Client,
        holding: Trading212Client.Position,
        order: PendingSellOrder,
        plan: OcoPlanner.Plan,
        config: ConfigManager,
        log: MutableList<String>,
        errors: MutableList<String>
    ): ExitKind {
        val qty = order.quantity.takeIf { it > 0.0 }?.let { min(it, holding.quantity) } ?: holding.quantity
        if (qty <= 0.0) {
            log.add("${holding.ticker}: pending exit has no usable quantity")
            return ExitKind.OTHER
        }

        return when (order.kind) {
            ExitKind.SL -> {
                if (!config.t212AutoTrailingEnabled) {
                    log.add("${holding.ticker}: SL trailing skipped (auto-trail off)")
                    return ExitKind.OTHER
                }
                val newSl = plan.sl
                if (newSl <= order.price * 1.005) {
                    log.add("${holding.ticker}: SL unchanged (${order.price} -> $newSl below bump threshold)")
                    return ExitKind.OTHER
                }
                if (newSl >= holding.currentPrice) {
                    log.add("${holding.ticker}: SL $newSl not below LTP ${holding.currentPrice}; skipped")
                    return ExitKind.OTHER
                }
                val replacement = replacePendingSellOrder(
                    ctx = ctx,
                    client = client,
                    ticker = holding.ticker,
                    order = order,
                    qty = qty,
                    newType = "STOP",
                    newLimit = null,
                    newStop = newSl,
                    errors = errors
                )
                if (replacement != null) {
                    log.add("${holding.ticker}: live SL ${order.price} -> $newSl")
                    GttActivityLog.append(ctx, "modify_sl", symbol = holding.ticker,
                        fields = mapOf("broker" to "t212", "old" to order.price, "new" to newSl, "id" to replacement))
                    ExitKind.SL
                } else ExitKind.OTHER
            }
            ExitKind.TARGET -> {
                if (!config.autoTrailingTargetEnabled) {
                    log.add("${holding.ticker}: target trailing skipped (target-trail off)")
                    return ExitKind.OTHER
                }
                val capped = min(plan.target, order.price * (1.0 + config.targetMaxDailyStepPct))
                val newTarget = roundCent(capped)
                if (newTarget <= order.price * 1.005) {
                    log.add("${holding.ticker}: target unchanged (${order.price} -> $newTarget below bump threshold)")
                    return ExitKind.OTHER
                }
                val replacement = replacePendingSellOrder(
                    ctx = ctx,
                    client = client,
                    ticker = holding.ticker,
                    order = order,
                    qty = qty,
                    newType = "LIMIT",
                    newLimit = newTarget,
                    newStop = null,
                    errors = errors
                )
                if (replacement != null) {
                    log.add("${holding.ticker}: live target ${order.price} -> $newTarget")
                    GttActivityLog.append(ctx, "modify_tgt", symbol = holding.ticker,
                        fields = mapOf("broker" to "t212", "old" to order.price, "new" to newTarget, "id" to replacement))
                    ExitKind.TARGET
                } else ExitKind.OTHER
            }
            ExitKind.OTHER -> {
                log.add("${holding.ticker}: unsupported live T212 sell order type ${order.type}")
                ExitKind.OTHER
            }
        }
    }

    private fun createInitialStop(
        ctx: Context,
        client: Trading212Client,
        holding: Trading212Client.Position,
        stopPrice: Double,
        log: MutableList<String>,
        errors: MutableList<String>
    ): Boolean {
        if (holding.quantity <= 0.0 || stopPrice <= 0.0) return false
        if (stopPrice >= holding.currentPrice) {
            log.add("${holding.ticker}: initial SL $stopPrice not below LTP ${holding.currentPrice}; skipped")
            return false
        }
        return when (val r = client.placeOrder(holding.ticker, holding.quantity, "SELL", "STOP", null, stopPrice)) {
            is Trading212Client.T212Result.Success -> {
                log.add("${holding.ticker}: created protective T212 SL at $stopPrice (id=${r.data})")
                GttActivityLog.append(ctx, "create_sl", symbol = holding.ticker,
                    fields = mapOf("broker" to "t212", "new" to stopPrice, "id" to r.data, "reason" to "auto-create-single-stop"))
                true
            }
            is Trading212Client.T212Result.Failure -> {
                errors.add("${holding.ticker}: initial SL create failed: ${r.message}")
                false
            }
            is Trading212Client.T212Result.NotConfigured -> {
                errors.add("${holding.ticker}: initial SL create failed: T212 not configured")
                false
            }
        }
    }

    private fun replacePendingSellOrder(
        ctx: Context,
        client: Trading212Client,
        ticker: String,
        order: PendingSellOrder,
        qty: Double,
        newType: String,
        newLimit: Double?,
        newStop: Double?,
        errors: MutableList<String>
    ): Long? {
        val oldType = when (order.kind) {
            ExitKind.SL -> "STOP"
            ExitKind.TARGET -> "LIMIT"
            ExitKind.OTHER -> order.type
        }
        return replaceSellOrder(
            ctx = ctx,
            client = client,
            ticker = ticker,
            orderId = order.id,
            qty = qty,
            oldType = oldType,
            oldLimit = if (oldType == "LIMIT") (order.limitPrice ?: order.price) else null,
            oldStop = if (oldType == "STOP") (order.stopPrice ?: order.price) else null,
            newType = newType,
            newLimit = newLimit,
            newStop = newStop,
            errors = errors
        )
    }

    private fun replaceLegacyPairLeg(
        ctx: Context,
        client: Trading212Client,
        ticker: String,
        orderId: Long,
        qty: Double,
        oldType: String,
        oldLimit: Double?,
        oldStop: Double?,
        newType: String,
        newLimit: Double?,
        newStop: Double?,
        errors: MutableList<String>
    ): Long? = replaceSellOrder(ctx, client, ticker, orderId, qty, oldType, oldLimit, oldStop, newType, newLimit, newStop, errors)

    private fun replaceSellOrder(
        ctx: Context,
        client: Trading212Client,
        ticker: String,
        orderId: Long,
        qty: Double,
        oldType: String,
        oldLimit: Double?,
        oldStop: Double?,
        newType: String,
        newLimit: Double?,
        newStop: Double?,
        errors: MutableList<String>
    ): Long? {
        if (orderId <= 0L || qty <= 0.0) return null
        when (val cancel = client.cancelOrder(orderId)) {
            is Trading212Client.T212Result.Success -> Unit
            is Trading212Client.T212Result.Failure -> {
                errors.add("$ticker: cancel before replace failed: ${cancel.message}")
                return null
            }
            is Trading212Client.T212Result.NotConfigured -> {
                errors.add("$ticker: cancel before replace failed: T212 not configured")
                return null
            }
        }

        when (val placed = client.placeOrder(ticker, qty, "SELL", newType, newLimit, newStop)) {
            is Trading212Client.T212Result.Success -> return placed.data
            is Trading212Client.T212Result.Failure -> {
                errors.add("$ticker: replacement $newType failed after cancel: ${placed.message}")
            }
            is Trading212Client.T212Result.NotConfigured -> {
                errors.add("$ticker: replacement $newType failed after cancel: T212 not configured")
            }
        }

        when (val restored = client.placeOrder(ticker, qty, "SELL", oldType, oldLimit, oldStop)) {
            is Trading212Client.T212Result.Success -> {
                errors.add("$ticker: restored old $oldType exit after replacement failure (id=${restored.data})")
                GttActivityLog.append(ctx, "restore", symbol = ticker,
                    fields = mapOf("broker" to "t212", "oldOrderId" to orderId, "restoredId" to restored.data, "type" to oldType))
            }
            is Trading212Client.T212Result.Failure -> {
                errors.add("$ticker: CRITICAL restore of old $oldType failed: ${restored.message}")
            }
            is Trading212Client.T212Result.NotConfigured -> {
                errors.add("$ticker: CRITICAL restore of old $oldType failed: T212 not configured")
            }
        }
        return null
    }

    private data class ReconcileCounts(
        val filled: Int,
        val closed: Int,
        val blocked: Boolean = false
    )

    private fun reconcilePairs(
        ctx: Context,
        client: Trading212Client,
        log: MutableList<String>,
        errors: MutableList<String>
    ): ReconcileCounts {
        val pairs = T212OcoStore.loadAll(ctx)
        if (pairs.isEmpty()) return ReconcileCounts(0, 0)

        val openIds: Set<Long> = when (val r = client.fetchOpenOrders()) {
            is Trading212Client.T212Result.Success -> {
                try {
                    val arr = JsonParser.parseString(r.data).asJsonArray
                    arr.mapNotNull { el ->
                        el.takeIf { it.isJsonObject }
                            ?.asJsonObject
                            ?.get("id")
                            ?.takeIf { !it.isJsonNull }
                            ?.asLong
                    }.toSet()
                } catch (e: Exception) {
                    errors.add("reconcile parse failed: ${e.message ?: "invalid open-order payload"}")
                    return ReconcileCounts(0, 0, blocked = true)
                }
            }
            is Trading212Client.T212Result.Failure -> {
                errors.add("reconcile: ${r.message}")
                return ReconcileCounts(0, 0, blocked = true)
            }
            is Trading212Client.T212Result.NotConfigured -> {
                errors.add("reconcile: T212 not configured")
                return ReconcileCounts(0, 0, blocked = true)
            }
        }

        var filled = 0
        var closed = 0
        for (p in pairs) {
            if (p.simulated || p.slOrderId < 0L || p.tgtOrderId < 0L) {
                log.add("${p.ticker}: removing legacy fake OCO pair")
                T212OcoStore.remove(ctx, p.ticker)
                closed++
                continue
            }
            val slPending = p.slOrderId in openIds
            val tgPending = p.tgtOrderId in openIds
            when {
                slPending && tgPending -> Unit
                slPending && !tgPending -> {
                    log.add("${p.ticker}: target leg filled, cancelling SL survivor (id=${p.slOrderId})")
                    client.cancelOrder(p.slOrderId)
                    T212OcoStore.remove(ctx, p.ticker)
                    GttActivityLog.append(ctx, "delete", symbol = p.ticker,
                        fields = mapOf("broker" to "t212", "id" to p.slOrderId, "reason" to "oco-target-filled-cancel-sl"))
                    filled++
                }
                !slPending && tgPending -> {
                    log.add("${p.ticker}: SL leg filled, cancelling Target survivor (id=${p.tgtOrderId})")
                    client.cancelOrder(p.tgtOrderId)
                    T212OcoStore.remove(ctx, p.ticker)
                    GttActivityLog.append(ctx, "delete", symbol = p.ticker,
                        fields = mapOf("broker" to "t212", "id" to p.tgtOrderId, "reason" to "oco-sl-filled-cancel-target"))
                    filled++
                }
                else -> {
                    log.add("${p.ticker}: both legs no longer pending, removing pair record")
                    T212OcoStore.remove(ctx, p.ticker)
                    closed++
                }
            }
        }
        return ReconcileCounts(filled, closed)
    }

    private fun roundCent(v: Double): Double = Math.round(v * 100.0) / 100.0
}

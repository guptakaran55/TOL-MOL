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
import kotlin.math.max
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
 *                  c. If a stored pair exists:
 *                       - newSL > oldSL × 1.005? cancel old SL leg, place new
 *                         stop at newSL, update stored pair. (Monotonic — never
 *                         lower.)
 *                       - newTgt > oldTgt × 1.005? cancel old Target, place new
 *                         limit at newTgt, capped at oldTgt × 1.08 per pass
 *                         (anti-spike).
 *                  d. If no pair exists: optionally auto-create one (gated by
 *                     `autoCreateOco`). Default OFF — user manually places via
 *                     detail-modal buttons until they trust the engine.
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

    /**
     * @param autoCreateOco when true, holdings without a pair get an auto-OCO
     *        placed (SL stop + Target limit). Default false — user explicitly
     *        opts in via the Settings toggle once they trust the formulas.
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

        // ── 1. RECONCILE persisted pairs ─────────────────────────────────
        val pairs = T212OcoStore.loadAll(ctx)
        if (pairs.isNotEmpty()) {
            val openIds: Set<Long> = when (val r = client.fetchOpenOrders()) {
                is Trading212Client.T212Result.Success -> {
                    try {
                        val arr = JsonParser.parseString(r.data).asJsonArray
                        arr.mapNotNull { it.asJsonObject?.get("id")?.asLong }.toSet()
                    } catch (e: Exception) { emptySet() }
                }
                is Trading212Client.T212Result.Failure -> {
                    errors.add("reconcile: ${r.message}")
                    return Summary(0, 0, 0, 0, 0, errors, log)
                }
                is Trading212Client.T212Result.NotConfigured -> emptySet()
            }
            for (p in pairs) {
                if (p.simulated || p.slOrderId < 0L || p.tgtOrderId < 0L) {
                    log.add("${p.ticker}: removing legacy fake OCO pair")
                    T212OcoStore.remove(ctx, p.ticker)
                    reconciledClosed++
                    continue
                }
                val slPending = p.slOrderId in openIds
                val tgPending = p.tgtOrderId in openIds
                when {
                    slPending && tgPending -> { /* still alive — no-op */ }
                    slPending && !tgPending -> {
                        // Target filled — cancel the SL survivor
                        log.add("${p.ticker}: target leg filled, cancelling SL survivor (id=${p.slOrderId})")
                        client.cancelOrder(p.slOrderId)
                        T212OcoStore.remove(ctx, p.ticker)
                        GttActivityLog.append(ctx, "delete", symbol = p.ticker,
                            fields = mapOf("broker" to "t212", "id" to p.slOrderId, "reason" to "oco-target-filled-cancel-sl"))
                        reconciledFilled++
                    }
                    !slPending && tgPending -> {
                        // SL filled — cancel the Target survivor
                        log.add("${p.ticker}: SL leg filled, cancelling Target survivor (id=${p.tgtOrderId})")
                        client.cancelOrder(p.tgtOrderId)
                        T212OcoStore.remove(ctx, p.ticker)
                        GttActivityLog.append(ctx, "delete", symbol = p.ticker,
                            fields = mapOf("broker" to "t212", "id" to p.tgtOrderId, "reason" to "oco-sl-filled-cancel-target"))
                        reconciledFilled++
                    }
                    else -> {
                        // Both gone — both filled (rare race) or manually cancelled.
                        // Just clear the stored pair.
                        log.add("${p.ticker}: both legs no longer pending, removing pair record")
                        T212OcoStore.remove(ctx, p.ticker)
                        reconciledClosed++
                    }
                }
            }
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
        val weights = config.scoringWeights
        val activePairs = T212OcoStore.loadAll(ctx).associateBy { it.ticker }

        for (h in holdings) {
            if (h.quantity <= 0) continue
            try {
                val yahooSym = client.t212ToYahooSymbol(h.ticker)
                val cr = YahooFinanceClient.fetchCandles(yahooSym, "NASDAQ")
                val analysis = if (cr is YahooFinanceClient.CandleResult.Success) {
                    StockAnalyzer.analyze(
                        candles = cr.candles, symbol = yahooSym, name = yahooSym,
                        token = yahooSym, minAvgVolume = 0, w = weights
                    )
                } else null
                if (analysis == null) {
                    skipped++
                    log.add("${h.ticker}: no analysis (Yahoo fetch failed)")
                    continue
                }
                val ltp = h.currentPrice
                val atr = analysis.atr.takeIf { it > 0 } ?: max(0.01, ltp * 0.02)
                val entry = h.averagePrice
                val supportFloor = if (analysis.support > 0 && analysis.support < ltp) analysis.support else 0.0
                val gain = if (entry > 0) (ltp - entry) / entry else 0.0
                val atrMult = config.slChandelierAtrMultiple(gain, daysSincePeak = 0L)
                val chandelier = ltp - atrMult * atr
                val hardFloor = entry * config.newPositionHardStopFraction
                val rawSl = max(supportFloor, max(chandelier, hardFloor))
                val newSlPrice = roundCent(rawSl.coerceIn(ltp * 0.75, ltp * 0.999))
                val tgtWave = if (analysis.projectedCeiling != null && analysis.projectedCeiling > ltp)
                    analysis.projectedCeiling else analysis.resistance
                val tgtAtrCand = ltp + config.targetAtrMultiple * atr
                val rawTgt = max(tgtWave, tgtAtrCand)
                val newTgtPrice = roundCent(min(ltp * 1.25, rawTgt))

                val existing = activePairs[h.ticker]
                if (existing == null) {
                    if (autoCreateOco) {
                        log.add("${h.ticker}: no OCO — auto-creating SL=$newSlPrice / TGT=$newTgtPrice")
                        val slRes = client.placeOrder(h.ticker, h.quantity, "SELL", "STOP", null, newSlPrice)
                        val tgRes = client.placeOrder(h.ticker, h.quantity, "SELL", "LIMIT", newTgtPrice, null)
                        val slId = (slRes as? Trading212Client.T212Result.Success)?.data ?: -1L
                        val tgId = (tgRes as? Trading212Client.T212Result.Success)?.data ?: -1L
                        if (slId > 0L && tgId > 0L) {
                            T212OcoStore.add(ctx, T212OcoStore.Pair(
                                h.ticker, slId, tgId, h.quantity, newSlPrice, newTgtPrice,
                                entry, System.currentTimeMillis(), simulated = false
                            ))
                        } else {
                            val rolledBack = mutableListOf<Long>()
                            if (slId > 0L) {
                                client.cancelOrder(slId)
                                rolledBack.add(slId)
                            }
                            if (tgId > 0L) {
                                client.cancelOrder(tgId)
                                rolledBack.add(tgId)
                            }
                            val rollbackNote = if (rolledBack.isNotEmpty()) " rolled back orphan order(s) ${rolledBack.joinToString(",")}" else ""
                            errors.add("${h.ticker}: auto-create failed (sl=$slId tg=$tgId).$rollbackNote")
                        }
                    } else {
                        skipped++
                        log.add("${h.ticker}: no OCO and autoCreateOco=false — skipped")
                    }
                    continue
                }

                // Trail SL leg if newSL is meaningfully higher (≥ 0.5% bump worth the API call)
                if (newSlPrice > existing.slPrice * 1.005 && !existing.simulated) {
                    log.add("${h.ticker}: SL ${existing.slPrice} → $newSlPrice")
                    client.cancelOrder(existing.slOrderId)
                    val r = client.placeOrder(h.ticker, h.quantity, "SELL", "STOP", null, newSlPrice)
                    if (r is Trading212Client.T212Result.Success) {
                        T212OcoStore.update(ctx, h.ticker, slOrderId = r.data, slPrice = newSlPrice)
                        slsRaised++
                        GttActivityLog.append(ctx, "modify_sl", symbol = h.ticker,
                            fields = mapOf("broker" to "t212", "old" to existing.slPrice, "new" to newSlPrice, "id" to r.data))
                    } else if (r is Trading212Client.T212Result.Failure) {
                        errors.add("${h.ticker}: SL replace failed: ${r.message}")
                    }
                }

                // Trail Target leg, capped at +8%/day (anti-spike)
                if (!existing.simulated) {
                    val capped = min(newTgtPrice, existing.tgtPrice * (1.0 + config.targetMaxDailyStepPct))
                    if (capped > existing.tgtPrice * 1.005) {
                        val tgt = roundCent(capped)
                        log.add("${h.ticker}: TGT ${existing.tgtPrice} → $tgt")
                        client.cancelOrder(existing.tgtOrderId)
                        val r = client.placeOrder(h.ticker, h.quantity, "SELL", "LIMIT", tgt, null)
                        if (r is Trading212Client.T212Result.Success) {
                            T212OcoStore.update(ctx, h.ticker, tgtOrderId = r.data, tgtPrice = tgt)
                            targetsRaised++
                            GttActivityLog.append(ctx, "modify_tgt", symbol = h.ticker,
                                fields = mapOf("broker" to "t212", "old" to existing.tgtPrice, "new" to tgt, "id" to r.data))
                        } else if (r is Trading212Client.T212Result.Failure) {
                            errors.add("${h.ticker}: TGT replace failed: ${r.message}")
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
    private fun roundCent(v: Double): Double = Math.round(v * 100.0) / 100.0
}

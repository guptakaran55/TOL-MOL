package com.signalscope.app.trading

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.PortfolioHolding
import com.signalscope.app.data.StockAnalysis
import com.signalscope.app.network.ZerodhaClient
import kotlinx.coroutines.delay

/**
 * Runs ONCE per trading day (15:20 IST, 9 minutes before close) and:
 *   1. Reconciles PositionTracker with current holdings (new/closed positions)
 *   2. For every held position:
 *      a. Evaluates MACD-death watchdog. If FIRE → run exit ladder (limit→market)
 *         and cancel any remaining GTTs on that symbol.
 *      b. Else, computes new trailing SL via TrailingStopCalculator.
 *         If newSL > currentGttTrigger → modify the SL GTT upward.
 *         If no SL GTT exists yet → skip (we don't auto-create; user opts in via bulk OCO).
 *      c. For positions with gain > 3 × atrPct AND an active upper-target GTT,
 *         delete the upper target to uncap upside (trailing SL now protects gains).
 *
 * Safety:
 *   - config.autoTrailingEnabled must be TRUE. Default is FALSE — user opts in.
 *   - Exit orders (MACD watchdog firing) also require config.autoExitEnabled = TRUE.
 *     This is a separate hotter switch because it PLACES MARKET ORDERS.
 *   - Every decision is logged. Dry-run mode simulates without calling the API.
 *   - Rate-limit safe: 1s sleep between API calls (Kite allows ~3/sec).
 */
class DailyPortfolioManager(
    private val context: Context,
    private val config: ConfigManager
) {

    companion object {
        private const val TAG = "DailyPortfolioMgr"
        private const val API_CALL_GAP_MS = 1000L
        private const val EXIT_LIMIT_SLIPPAGE_1 = 0.998   // first limit try: LTP × 0.998
        private const val EXIT_LIMIT_SLIPPAGE_2 = 0.995   // second limit try: LTP × 0.995
        /** Last rung: far enough under the bid that the book takes it immediately,
         *  which is what the market order it replaces was meant to achieve. It is a
         *  floor on a thin book, not the price expected — a crossing sell is filled
         *  at whatever is actually bid, normally well above this. */
        private const val EXIT_LIMIT_SLIPPAGE_3 = 0.99    // final try: LTP × 0.99
        private const val EXIT_WAIT_FILL_MS = 60_000L     // 60s wait per limit attempt
        private const val ORDER_POLL_MS = 5_000L          // poll order status every 5s
        private const val MIN_HOLD_DAYS = 5               // don't tighten trailing SL in first 5 days
        private const val MS_PER_DAY = 24L * 60L * 60L * 1000L

        // Dedicated channel for MACD flip alerts — heads-up, full-screen-intent capable
        const val CHANNEL_MACD_EXIT = "signalscope_macd_exit"

        // Sniper GTT: how tight to set the trigger when MACD flips and autoCreateGtt=true
        // 1.5% below LTP — aggressive enough to catch continued decline, loose enough for
        // end-of-day noise. Limit = trigger × 0.985 (−1.5% from trigger for slippage).
        private const val SNIPER_TRIGGER_FRAC = 0.985
        private const val SNIPER_LIMIT_FRAC   = 0.985   // limit below trigger

        fun ensureMacdExitChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_MACD_EXIT) == null) {
                val ch = NotificationChannel(
                    CHANNEL_MACD_EXIT,
                    "MACD Exit Alerts",
                    NotificationManager.IMPORTANCE_HIGH   // heads-up banner + sound
                ).apply {
                    description = "High-priority alerts when MACD momentum death is detected on a held position"
                    enableVibration(true)
                    enableLights(true)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private val tracker = PositionTracker(context)
    private val zc = ZerodhaClient(config)

    data class RunReport(
        val timestamp: Long,
        val holdingsSeen: Int,
        val slUpdates: Int,
        val slSkipped: Int,
        val exitsAttempted: Int,
        val exitsFilled: Int,
        val upperTargetsDeleted: Int,
        val errors: List<String>,
        val perSymbolLog: List<String>
    )

    /**
     * Run one full daily tick. Expects to be called ONCE per trading day after
     * 15:20 IST. The scheduler (ScanService) ensures the timing.
     *
     * @param holdings snapshot of current portfolio (with fresh analysis attached)
     * @param dryRun if true, logs what WOULD happen but makes no API modifications
     */
    suspend fun runDailyTick(
        holdings: List<PortfolioHolding>,
        dryRun: Boolean = false
    ): RunReport {
        val startMs = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        val logLines = mutableListOf<String>()
        var slUpdates = 0
        var slSkipped = 0
        var exitsAttempted = 0
        var exitsFilled = 0
        var targetsDeleted = 0

        // ── Hygiene: prune watchdog suppression keys older than today ───────────
        // The set is keyed "yyyy-MM-dd|SYMBOL" — entries from previous days are
        // now stale (BOOK_PROFIT today shouldn't be suppressed by yesterday's flip).
        // Without this prune, the set grows unbounded across trading sessions.
        run {
            val istCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Kolkata"))
            istCal.timeInMillis = startMs
            val today = String.format(
                java.util.Locale.US, "%04d-%02d-%02d",
                istCal.get(java.util.Calendar.YEAR),
                istCal.get(java.util.Calendar.MONTH) + 1,
                istCal.get(java.util.Calendar.DAY_OF_MONTH)
            )
            val firedKeys = com.signalscope.app.ui.ScanServiceResultHolder.macdWatchdogFiredKeys
            synchronized(firedKeys) {
                val stale = firedKeys.filter { !it.startsWith("$today|") }
                if (stale.isNotEmpty()) {
                    firedKeys.removeAll(stale.toSet())
                    Log.i(TAG, "Pruned ${stale.size} stale watchdog keys (kept today's)")
                }
            }
            // Same hygiene for the Tier-1.5 soft sniper set.
            val softKeys = com.signalscope.app.ui.ScanServiceResultHolder.softSniperFiredKeys
            synchronized(softKeys) {
                val stale = softKeys.filter { !it.startsWith("$today|") }
                if (stale.isNotEmpty()) {
                    softKeys.removeAll(stale.toSet())
                    Log.i(TAG, "Pruned ${stale.size} stale soft-sniper keys (kept today's)")
                }
            }
            // And the score-persistence streak map (Gate-A anti-flicker counter).
            // Streaks are inherently single-day so any non-today key is dead.
            val streakMap = com.signalscope.app.ui.ScanServiceResultHolder.profitScoreStreak
            synchronized(streakMap) {
                val stale = streakMap.keys.filter { !it.startsWith("$today|") }
                if (stale.isNotEmpty()) {
                    stale.forEach { streakMap.remove(it) }
                    Log.i(TAG, "Pruned ${stale.size} stale profit-score streak keys (kept today's)")
                }
            }
        }

        // 1. Reconcile tracker with broker state
        val brokerTuples = holdings.map { Triple(it.symbol, it.avgPrice, it.ltp) }
        val histBySym = holdings.mapNotNull { h ->
            h.analysis?.let { h.symbol to it.macdHist }
        }.toMap()
        val states = tracker.reconcile(brokerTuples, histBySym, startMs)

        // 2. Fetch all active GTTs ONCE (cheaper than per-symbol look-ups)
        val gttsBySymbol: Map<String, List<ZerodhaClient.GttTrigger>> = when (
            val r = zc.fetchGttTriggers()
        ) {
            is ZerodhaClient.GttListResult.Success -> r.triggers
                .filter { it.status == "active" }
                .groupBy { it.tradingsymbol }
            is ZerodhaClient.GttListResult.Expired -> {
                errors.add("Zerodha session expired — user must reconnect")
                return RunReport(
                    startMs, holdings.size, 0, holdings.size, 0, 0, 0, errors, logLines
                )
            }
            is ZerodhaClient.GttListResult.Failure -> {
                errors.add("GTT fetch failed: ${r.message}")
                emptyMap()
            }
        }

        // 3. Process each holding
        for (h in holdings) {
            val analysis = h.analysis ?: run {
                logLines.add("${h.symbol}: SKIP — no analysis (scan may not have completed)")
                continue
            }
            val state = states[h.symbol] ?: run {
                logLines.add("${h.symbol}: SKIP — tracker state missing after reconcile")
                continue
            }
            val gtts = gttsBySymbol[h.symbol].orEmpty()

            // Find the sell SL GTT (single-leg SELL below LTP, or two-leg OCO's SL leg)
            val slGtt = gtts.firstOrNull { g ->
                g.transactionType == "SELL" && g.triggerPrice < h.ltp && g.triggerPrice > 0
            }
            // Find upper-target GTT (two-leg OCO's target leg is triggerPriceAux; also
            // cover any single-leg SELL above LTP which acts as a target)
            val upperTargetSingleLeg = gtts.firstOrNull { g ->
                g.triggerType == "single" && g.transactionType == "SELL" && g.triggerPrice > h.ltp
            }

            // ── (a) MACD exit watchdog ───────────────────────────────────────
            val gainPct = if (h.avgPrice > 0) (h.ltp - h.avgPrice) / h.avgPrice * 100.0 else 0.0
            val watchdogOut = MacdExitWatchdog.evaluate(
                MacdExitWatchdog.Input(
                    symbol = h.symbol,
                    currentHist = analysis.macdHist,
                    peakHistValue = state.peakHistValue,
                    histNegativeSinceEpoch = state.histNegativeSinceEpoch,
                    unrealizedGainPct = gainPct,
                    minBarsFilter = analysis.minBarsFilter,
                    nowMs = startMs
                )
            )

            if (watchdogOut.shouldExit) {
                // ── Tier 1 (default): high-priority push notification ─────────────
                // Gated by config.macdFlipNotificationsEnabled (default true).
                // Even if the user mutes Tier 1, the watchdog still records into
                // cross-talk so suppression and alert history work correctly.
                if (config.macdFlipNotificationsEnabled) {
                    sendMacdFlipNotification(h, analysis, gainPct, watchdogOut.reason)
                    logLines.add("${h.symbol}: ⚠ MACD FLIP — notification sent. gain=${gainPct.format(1)}%")
                } else {
                    logLines.add("${h.symbol}: ⚠ MACD FLIP — notification muted (Tier 1 disabled). gain=${gainPct.format(1)}%")
                }
                // Cross-talk into ScanService: register the firing so (a) BOOK_PROFIT
                // is suppressed for this symbol the rest of today, and (b) the alert
                // appears in the in-app history panel alongside ScanService alerts.
                registerMacdFlipForCrossTalk(h, analysis, gainPct, watchdogOut.reason, startMs)

                // ── Tier 2 (autoCreateGtt): sniper GTT ───────────────────────────
                // Cancel existing OCO and replace with a tight single-leg SELL GTT
                // at LTP × SNIPER_TRIGGER_FRAC. If price drops further it executes;
                // if it recovers the GTT sits inert and you can decide later.
                // MUCH safer than a market order — still conditional, still cancellable.
                if (!dryRun && config.autoCreateGtt) {
                    val sniperResult = replaceSniperGtt(h, gtts)
                    logLines.add("${h.symbol}: sniper GTT → ${sniperResult}")
                    delay(API_CALL_GAP_MS)
                } else if (dryRun) {
                    val sniperTrigger = Math.round(h.ltp * SNIPER_TRIGGER_FRAC * 20.0) / 20.0
                    logLines.add("${h.symbol}: DRY-RUN sniper GTT would be set at ₹${sniperTrigger.format(2)}")
                }

                // ── Tier 3 (autoExitEnabled): live market-order ladder ────────────
                // Irreversible — places real SELL orders immediately. Only runs if
                // the user has explicitly enabled the hot switch.
                if (!dryRun && config.autoExitEnabled) {
                    exitsAttempted++
                    val ladderResult = runExitLadder(h, gtts)
                    if (ladderResult.filled) {
                        exitsFilled++
                        logLines.add("${h.symbol}: MACD EXIT FILLED qty=${h.quantity} via ${ladderResult.mode}")
                    } else {
                        errors.add("${h.symbol}: MACD exit ladder not filled: ${ladderResult.error}")
                    }
                    delay(API_CALL_GAP_MS)
                }

                // Whether or not live orders were placed, skip SL trailing for this
                // holding — the sniper GTT is now the active protection.
                continue
            }

            // ── (b) Trailing SL update (or auto-create if no GTT exists yet) ──────
            if (slGtt == null) {
                // Auto-create OCO GTT only when ALL safety gates pass:
                //   1. user opted in
                //   2. support / resistance levels are valid
                //   3. holding is at least 1 full day old (today's bar is incomplete,
                //      same-day S/R from intraday data is unreliable)
                //   4. range is wide enough to be meaningful: (R-S) ≥ 2× ATR — narrower
                //      ranges mean S/R is fitted to noise, OCO would trigger on chop
                val daysHeldForOco = ((startMs - state.entryEpoch) / MS_PER_DAY).coerceAtLeast(0)
                val sRange = analysis.resistance - analysis.support
                val rangeIsValid = analysis.atr > 0 && sRange >= 2.0 * analysis.atr
                val canAutoCreate = config.autoCreateGtt &&
                    analysis.support > 0 &&
                    analysis.resistance > analysis.support &&
                    daysHeldForOco >= 1 &&
                    rangeIsValid
                if (canAutoCreate) {
                    autoCreateOcoGtt(h, analysis, state, startMs, dryRun, logLines, errors)
                    delay(API_CALL_GAP_MS)
                } else {
                    val reason = when {
                        !config.autoCreateGtt -> "autoCreateGtt=false — use Bulk OCO or enable auto-create in Settings"
                        analysis.support <= 0 -> "support level unavailable — cannot place SL"
                        analysis.resistance <= analysis.support -> "resistance ≤ support — S/R data invalid, skipping"
                        daysHeldForOco < 1 -> "fresh buy (<1d old) — skipping auto-OCO until tomorrow's S/R is reliable"
                        !rangeIsValid -> "S/R range too tight (${sRange.format(2)} < 2×ATR ${(2*analysis.atr).format(2)}) — skipping"
                        else -> "unknown gate"
                    }
                    logLines.add("${h.symbol}: no SL GTT — $reason")
                    slSkipped++
                }
            } else {
                val daysHeld = ((startMs - state.entryEpoch) / MS_PER_DAY).coerceAtLeast(0)
                val trail = TrailingStopCalculator.compute(
                    TrailingStopCalculator.Input(
                        symbol = h.symbol,
                        ltp = h.ltp,
                        entryPrice = state.entryPrice,
                        peakPrice = state.peakPrice,
                        peakEpoch = state.peakEpoch,
                        atr = analysis.atr,
                        supportLevel = analysis.support,
                        yesterdaySL = slGtt.triggerPrice,
                        fixedAtrMultiple = config.slChandelierFixedAtrMultiple,
                        timeDecay10dFactor = config.slTimeDecay10dFactor,
                        timeDecay20dFactor = config.slTimeDecay20dFactor,
                        nowMs = startMs
                    )
                )
                // Configured hard floor from entry is always-on: even during min-hold we never
                // sit below it. If the current GTT is below hardFloor and hardFloor < LTP,
                // we still raise to the floor.
                val hardFloor = Math.round(state.entryPrice * config.newPositionHardStopFraction * 20.0) / 20.0
                val inMinHold = daysHeld < MIN_HOLD_DAYS
                // During min-hold: skip profit-adaptive trail. Only bump to hardFloor if needed.
                // After min-hold: use full trail output, but ensure ≥ hardFloor.
                val effectiveNewSL = if (inMinHold) {
                    maxOf(slGtt.triggerPrice, if (hardFloor < h.ltp) hardFloor else slGtt.triggerPrice)
                } else {
                    maxOf(trail.newSL, if (hardFloor < h.ltp) hardFloor else 0.0)
                }
                val minStep = 0.05
                val shouldUpdate = effectiveNewSL > slGtt.triggerPrice + minStep
                val holdTag = if (inMinHold) "[min-hold day ${daysHeld + 1}/$MIN_HOLD_DAYS, hard-SL floor only]" else "[day ${daysHeld + 1}, full trail]"
                if (!shouldUpdate) {
                    logLines.add("${h.symbol}: SL unchanged at ₹${slGtt.triggerPrice.format(2)} $holdTag — ${trail.reason}")
                    slSkipped++
                    com.signalscope.app.data.GttActivityLog.append(
                        config.appContext, "skip", symbol = h.symbol,
                        fields = mapOf("leg" to "sl", "current" to slGtt.triggerPrice, "proposed" to effectiveNewSL, "reason" to "no-bump-needed", "tag" to holdTag, "trail" to trail.reason)
                    )
                } else if (!config.autoTrailingEnabled) {
                    logLines.add("${h.symbol}: SL would update to ₹${effectiveNewSL.format(2)} $holdTag but autoTrailingEnabled=false — ${trail.reason}")
                    slSkipped++
                    com.signalscope.app.data.GttActivityLog.append(
                        config.appContext, "skip", symbol = h.symbol,
                        fields = mapOf("leg" to "sl", "current" to slGtt.triggerPrice, "proposed" to effectiveNewSL, "reason" to "auto-trailing-disabled", "tag" to holdTag)
                    )
                } else if (dryRun) {
                    logLines.add("${h.symbol}: DRY-RUN SL ₹${slGtt.triggerPrice.format(2)} → ₹${effectiveNewSL.format(2)} $holdTag — ${trail.reason}")
                    com.signalscope.app.data.GttActivityLog.append(
                        config.appContext, "skip", symbol = h.symbol,
                        fields = mapOf("leg" to "sl", "current" to slGtt.triggerPrice, "proposed" to effectiveNewSL, "reason" to "dry-run", "tag" to holdTag)
                    )
                } else {
                    // ── Trailing TARGET (upside) computation ──────────────────
                    // Mirror of the SL trail but on the upside leg. Symmetric behaviour:
                    // SL ratchets up daily via chandelier; target ratchets up via LTP+kATR.
                    // Always pushes higher, never lower — hence stocks that genuinely trend
                    // don't get capped at last month's resistance level.
                    val oldTarget = slGtt.triggerPriceAux  // null for single-leg, ignored there
                    val trailedTarget: Double? = if (
                        slGtt.triggerType == "two-leg" &&
                        oldTarget != null &&
                        analysis.atr > 0 &&
                        h.ltp > 0
                    ) {
                        val candidate = h.ltp + config.targetAtrMultiple * analysis.atr
                        val ratcheted = maxOf(oldTarget, candidate)
                        // Anti-spike cap: never let target jump > MaxDailyStep% of oldTarget in one day.
                        // A flash-crash bounce candle should not push the cap unreachably far.
                        val dailyMax = oldTarget * (1.0 + config.targetMaxDailyStepPct)
                        val capped = minOf(ratcheted, dailyMax)
                        // Round to 0.05 tick (Kite minimum)
                        Math.round(capped * 20.0) / 20.0
                    } else null

                    val targetBump = trailedTarget != null && oldTarget != null &&
                        (trailedTarget - oldTarget) / oldTarget >= 0.01  // ≥ 1% step worth the API call

                    // Modify: for two-leg OCO trail target up alongside SL; for single-leg just bump trigger
                    val modifyOk = if (slGtt.triggerType == "two-leg" && oldTarget != null) {
                        // Pick effective target: trailed value if both flags green, else preserve.
                        val effectiveTarget = if (config.autoTrailingTargetEnabled && targetBump)
                            trailedTarget!!
                        else
                            oldTarget
                        val newSlLimit  = Math.round(effectiveNewSL * (1.0 - config.slLimitPct) * 20.0) / 20.0
                        val newTgtLimit = Math.round(effectiveTarget * 0.99 * 20.0) / 20.0
                        val res = zc.setGttTriggerPrices(
                            slGtt.id,
                            listOf(effectiveNewSL, effectiveTarget),
                            listOf(newSlLimit, newTgtLimit)
                        )
                        if (res is ZerodhaClient.GttModifyResult.Success && effectiveTarget != oldTarget) {
                            logLines.add("${h.symbol}: TGT ₹${oldTarget.format(2)} → ₹${effectiveTarget.format(2)} ✓ (LTP+${config.targetAtrMultiple}×ATR=${"%.2f".format(h.ltp + config.targetAtrMultiple * analysis.atr)}, capped at +${(config.targetMaxDailyStepPct*100).toInt()}%)")
                        } else if (config.autoTrailingTargetEnabled && trailedTarget != null && trailedTarget > oldTarget && !targetBump) {
                            logLines.add("${h.symbol}: TGT trail step too small (₹${oldTarget.format(2)} → ₹${trailedTarget.format(2)}, <1%) — skipped to avoid API spam")
                        } else if (!config.autoTrailingTargetEnabled && trailedTarget != null && trailedTarget > oldTarget) {
                            logLines.add("${h.symbol}: TGT would update ₹${oldTarget.format(2)} → ₹${trailedTarget.format(2)} but autoTrailingTargetEnabled=false")
                        }
                        res is ZerodhaClient.GttModifyResult.Success
                    } else {
                        // Single-leg: use modify helper (no target leg to trail)
                        val res = zc.modifyGttTriggerPrice(slGtt.id, effectiveNewSL, oldTriggerHint = slGtt.triggerPrice)
                        res is ZerodhaClient.GttModifyResult.Success
                    }
                    if (modifyOk) {
                        tracker.markSlUpdated(h.symbol, effectiveNewSL, startMs)
                        slUpdates++
                        logLines.add("${h.symbol}: SL ₹${slGtt.triggerPrice.format(2)} → ₹${effectiveNewSL.format(2)} ✓ $holdTag — ${trail.reason}")
                        com.signalscope.app.data.GttActivityLog.append(
                            config.appContext, "modify_sl", symbol = h.symbol,
                            fields = mapOf(
                                "id" to slGtt.id, "old" to slGtt.triggerPrice, "new" to effectiveNewSL,
                                "ltp" to h.ltp, "tag" to holdTag, "reason" to trail.reason
                            )
                        )
                    } else {
                        slSkipped++
                        errors.add("${h.symbol}: SL modify failed")
                        com.signalscope.app.data.GttActivityLog.append(
                            config.appContext, "error", symbol = h.symbol,
                            fields = mapOf("leg" to "sl", "id" to slGtt.id, "attempted" to effectiveNewSL, "reason" to "kite-modify-failed")
                        )
                    }
                    delay(API_CALL_GAP_MS)
                }
            }

            // ── (c) Upper-target cleanup when gain > 3 × atrPct ─────────────
            val atrPct = if (h.ltp > 0) analysis.atr / h.ltp * 100.0 else 0.0
            val volAdjustedThreshold = 3.0 * atrPct
            if (gainPct > volAdjustedThreshold && upperTargetSingleLeg != null) {
                if (!config.autoTrailingEnabled) {
                    logLines.add("${h.symbol}: would delete upper-target GTT (gain ${gainPct.format(1)}% > ${volAdjustedThreshold.format(1)}%) but autoTrailingEnabled=false")
                } else if (dryRun) {
                    logLines.add("${h.symbol}: DRY-RUN delete upper-target GTT (gain ${gainPct.format(1)}% > ${volAdjustedThreshold.format(1)}%)")
                } else {
                    val res = zc.deleteGttTrigger(upperTargetSingleLeg.id)
                    if (res is ZerodhaClient.GttDeleteResult.Success) {
                        targetsDeleted++
                        logLines.add("${h.symbol}: upper-target GTT deleted — gain ${gainPct.format(1)}% > ${volAdjustedThreshold.format(1)}% (3×ATR)")
                    } else {
                        errors.add("${h.symbol}: upper-target delete failed")
                    }
                    delay(API_CALL_GAP_MS)
                }
            }

            tracker.markEvaluated(h.symbol, startMs)
        }

        val report = RunReport(
            timestamp = startMs,
            holdingsSeen = holdings.size,
            slUpdates = slUpdates,
            slSkipped = slSkipped,
            exitsAttempted = exitsAttempted,
            exitsFilled = exitsFilled,
            upperTargetsDeleted = targetsDeleted,
            errors = errors,
            perSymbolLog = logLines
        )
        config.lastDailyTickEpoch = startMs
        // Bump portfolioVersion so the dashboard polls and re-renders. Without this
        // the WebView won't see watchdog-driven changes (sniper GTTs, MACD_FLIP alerts,
        // SL trail bumps) until the next regular intraday scan tick.
        com.signalscope.app.ui.ScanServiceResultHolder.portfolioVersion++
        Log.i(TAG, "Daily tick done: ${holdings.size} holdings · $slUpdates SL updates · $exitsFilled exits filled · ${errors.size} errors")
        return report
    }

    // ── Tier 1: MACD flip notification (always fires) ──────────────────────

    private fun sendMacdFlipNotification(
        h: PortfolioHolding,
        analysis: StockAnalysis,
        gainPct: Double,
        reason: String
    ) {
        try {
            ensureMacdExitChannel(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val title = "⚠ MACD FLIP — ${h.symbol}  sell soon"
            val body = buildString {
                append("LTP ₹${h.ltp.format(2)}  ·  gain ${gainPct.format(1)}%  ·  qty ${h.quantity}\n")
                append("MACD hist negative ≥ ${analysis.minBarsFilter}d filter (adaptive).\n")
                append("Sniper GTT being set ~1.5% below LTP. Check Kite to review or cancel.\n")
                append("Reason: $reason")
            }
            val notif = NotificationCompat.Builder(context, CHANNEL_MACD_EXIT)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText("LTP ₹${h.ltp.format(2)} · gain ${gainPct.format(1)}% · sniper GTT being set")
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .build()
            nm.notify(("macd_${h.symbol}").hashCode(), notif)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send MACD flip notification for ${h.symbol}", e)
        }
    }

    /**
     * Register a watchdog firing into ScanServiceResultHolder so:
     *   (1) ScanService suppresses BOOK_PROFIT for this symbol the rest of today
     *       (the watchdog is a stricter, sustained-confirmation signal — its action
     *       supersedes the advisory dual-score alert)
     *   (2) The watchdog firing appears in the in-app alert history panel as a
     *       MACD_FLIP StockAlert, so the user has a complete audit trail of what
     *       was decided and acted on at 15:20.
     */
    private fun registerMacdFlipForCrossTalk(
        h: PortfolioHolding,
        analysis: StockAnalysis,
        gainPct: Double,
        reason: String,
        nowMs: Long
    ) {
        try {
            // IST trading date key — same format ScanService uses for date-stamped sets
            val istCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Kolkata"))
            istCal.timeInMillis = nowMs
            val today = String.format(
                java.util.Locale.US, "%04d-%02d-%02d",
                istCal.get(java.util.Calendar.YEAR),
                istCal.get(java.util.Calendar.MONTH) + 1,
                istCal.get(java.util.Calendar.DAY_OF_MONTH)
            )

            // (1) Suppression key — ScanService.generateAlerts() reads this before BOOK_PROFIT
            com.signalscope.app.ui.ScanServiceResultHolder
                .macdWatchdogFiredKeys.add("$today|${h.symbol}")

            // (2) Append to alert history panel
            val alert = com.signalscope.app.data.StockAlert(
                symbol = h.symbol,
                name = h.symbol,
                alertType = com.signalscope.app.data.AlertType.MACD_FLIP,
                message = "Histogram watchdog confirmed (≥${analysis.minBarsFilter}d sustained, gain ${gainPct.format(1)}%). Sniper GTT set. $reason",
                sellScore = analysis.sellScore,
                cherryPoints = analysis.cherryPoints,
                price = h.ltp,
                macdPhase = analysis.macdPhase,
                source = "zerodha"
            )
            com.signalscope.app.ui.ScanServiceResultHolder.macdWatchdogAlerts.add(alert)
        } catch (e: Exception) {
            Log.e(TAG, "registerMacdFlipForCrossTalk failed for ${h.symbol}", e)
        }
    }

    // ── Tier 2: Sniper GTT — cancel existing OCO, set tight single-leg SELL ─
    //
    //  Why single-leg instead of OCO here:
    //    The OCO target leg is now irrelevant once MACD is flipping — you want out,
    //    not waiting for a recovery to hit a target. A single-leg SELL trigger at
    //    LTP × 0.985 (1.5% buffer) will fire on any continued decline.
    //    If price somehow recovers, the GTT stays inert — no harm done.

    private suspend fun replaceSniperGtt(
        h: PortfolioHolding,
        existingGtts: List<ZerodhaClient.GttTrigger>
    ): String {
        // Cancel ONLY existing SELL-side GTTs. A user might have an unrelated BUY
        // GTT (e.g. buy-the-dip ladder) we must not touch — wiping it would silently
        // remove a real-money order with no recovery path.
        val sellGtts = existingGtts.filter { g ->
            g.transactionType == "SELL" ||
            // two-leg OCO: both legs are conceptually "exit" — the type flag may sit
            // on the first leg only, so type=="two-leg" + any SELL leg counts as SELL.
            (g.triggerType == "two-leg" && g.transactionType.equals("SELL", ignoreCase = true))
        }
        for (g in sellGtts) {
            zc.deleteGttTrigger(g.id)
            delay(API_CALL_GAP_MS)
        }
        // Extra spacer between final delete and create so Zerodha's GTT list is
        // consistent before we POST the sniper trigger (avoids the race where the
        // create succeeds but ends up coexisting with a not-yet-deleted leg).
        delay(API_CALL_GAP_MS)

        val sniperTrigger = Math.round(h.ltp * SNIPER_TRIGGER_FRAC * 20.0) / 20.0
        val sniperLimit   = Math.round(sniperTrigger * SNIPER_LIMIT_FRAC * 20.0) / 20.0
        // Kite hard bounds: trigger must be < LTP (it's a stop, not a target)
        val clampedTrigger = sniperTrigger.coerceIn(h.ltp * 0.75, h.ltp * 0.999)

        val result = zc.createGttTrigger(
            type = "single",
            exchange = h.exchange,
            tradingsymbol = h.symbol,
            triggerValues = listOf(clampedTrigger),
            lastPrice = h.ltp,
            orders = listOf(
                ZerodhaClient.GttOrderSpec(
                    transactionType = "SELL",
                    quantity = h.quantity,
                    price = sniperLimit,
                    product = "CNC",
                    orderType = "LIMIT"
                )
            )
        )
        return when (result) {
            is ZerodhaClient.GttCreateResult.Success ->
                "✅ sniper GTT #${result.id} set at ₹${clampedTrigger.format(2)}, limit ₹${sniperLimit.format(2)} (${((clampedTrigger/h.ltp-1)*100).format(1)}%)"
            is ZerodhaClient.GttCreateResult.Failure ->
                "❌ sniper GTT failed: ${result.message}"
        }
    }

    // ── Exit-order ladder ───────────────────────────────────────────────────
    //   1. Cancel every GTT on this symbol (SL + target) to prevent double-sell
    //   2. Place LIMIT SELL at LTP × 0.998, wait up to 60s for fill
    //   3. If unfilled, cancel + place LIMIT at LTP × 0.995, wait 60s
    //   4. If still unfilled, place MARKET order as last resort

    // ── Auto-create OCO GTT for new positions that have no existing GTT ────────
    //   SL leg  : support level, floored at the configured entry hard-stop, clamped to Kite range
    //   TGT leg : resistance (or projectedCeiling if available), capped at ltp × 1.25
    //   Both legs use the same slippage pads as manual Bulk OCO (SL limit −slLimitPct%, TGT limit −1%)
    //
    //   Called at 15:20 IST so fresh same-day buys get overnight protection automatically.

    private suspend fun autoCreateOcoGtt(
        h: PortfolioHolding,
        analysis: com.signalscope.app.data.StockAnalysis,
        state: PositionTracker.TradeState,
        nowMs: Long,
        dryRun: Boolean,
        logLines: MutableList<String>,
        errors: MutableList<String>
    ) {
        val ltp = h.ltp

        // ── SL price ────────────────────────────────────────────────────────
        // Unified formula: max(support, chandelier, hardFloor) clamped to Kite range.
        // Mirrors Bulk OCO modal (dashboard.html planBulkOco) and trace renderer.
        val plan = OcoPlanner.planSellOco(
            config,
            OcoPlanner.Input(
                ltp = ltp,
                support = analysis.support,
                resistance = analysis.resistance,
                atr = analysis.atr,
                avgPrice = state.entryPrice,
                projectedCeiling = analysis.projectedCeiling,
                tickSize = 0.05
            )
        )
        if (!plan.ok) {
            errors.add("${h.symbol}: auto-create OCO plan failed - ${plan.error ?: "invalid data"}")
            return
        }
        val hardFloor = plan.hardFloor
        val supportCandidate = if (analysis.support > 0 && analysis.support < ltp) analysis.support else 0.0
        val atrAtCreate = analysis.atr.takeIf { it > 0 } ?: maxOf(0.01, ltp * 0.02)
        val gainAtPeak = if (state.entryPrice > 0) (ltp - state.entryPrice) / state.entryPrice else 0.0
        val atrMult = config.slChandelierAtrMultiple(gainAtPeak, daysSincePeak = 0L)
        val chandelierAtCreate = ltp - atrMult * atrAtCreate
        val slRaw = maxOf(supportCandidate, chandelierAtCreate, hardFloor)
        // Kite rule: SL trigger must be within [ltp×0.75, ltp×0.999]
        val slTrigger = plan.sl
        val slLimit = plan.slLimit

        // ── Target price ────────────────────────────────────────────────────
        // Unified formula: max(waveCeiling||resistance, LTP + 2×ATR) clamped Kite +25%.
        val tgtWave = if (analysis.projectedCeiling != null && analysis.projectedCeiling > ltp)
            analysis.projectedCeiling else analysis.resistance
        val tgtAtrCand = ltp + config.targetAtrMultiple * atrAtCreate
        val tgtRaw = maxOf(tgtWave, tgtAtrCand)
        // Kite rule: target trigger must be ≤ ltp × 1.25
        val tgtTrigger = plan.target
        val tgtLimit = plan.targetLimit

        val slPctFromLtp  = (slTrigger / ltp - 1) * 100
        val tgtPctFromLtp = (tgtTrigger / ltp - 1) * 100
        val tgtSrc = plan.targetSource

        val summary = "SL ₹${slTrigger.format(2)} (${slPctFromLtp.format(1)}%) / TGT ₹${tgtTrigger.format(2)} (+${tgtPctFromLtp.format(1)}%) via $tgtSrc"

        // autoCreateGtt is the only gate needed — OCO creation is a conditional server-side
        // order, far safer than trailing (modifies live GTT) or auto-exit (places market order).
        // No dependency on autoTrailingEnabled: user may want creates without trailing.
        // Audit-log the decision (paired with the subsequent ZerodhaClient "create" log)
        com.signalscope.app.data.GttActivityLog.append(
            config.appContext,
            if (dryRun) "skip" else "decide", symbol = h.symbol,
            fields = mapOf(
                "path" to "autoCreateOco",
                "ltp" to ltp, "entry" to state.entryPrice,
                "support" to analysis.support, "chandelier" to chandelierAtCreate,
                "hardFloor" to hardFloor, "slPicked" to slTrigger,
                "tgtWave" to tgtWave, "tgtAtr" to tgtAtrCand, "tgtPicked" to tgtTrigger,
                "reason" to if (dryRun) "dry-run" else "fresh-create"
            )
        )
        if (dryRun) {
            logLines.add("${h.symbol}: [DRY-RUN] would auto-create OCO — $summary")
            return
        }

        val result = zc.createGttTrigger(
            type = "two-leg",
            exchange = h.exchange,
            tradingsymbol = h.symbol,
            triggerValues = listOf(slTrigger, tgtTrigger),
            lastPrice = ltp,
            orders = listOf(
                ZerodhaClient.GttOrderSpec(
                    transactionType = "SELL",
                    quantity = h.quantity,
                    price = slLimit,
                    product = "CNC",
                    orderType = "LIMIT"
                ),
                ZerodhaClient.GttOrderSpec(
                    transactionType = "SELL",
                    quantity = h.quantity,
                    price = tgtLimit,
                    product = "CNC",
                    orderType = "LIMIT"
                )
            )
        )

        when (result) {
            is ZerodhaClient.GttCreateResult.Success ->
                logLines.add("${h.symbol}: ✅ auto-created OCO GTT #${result.id} — $summary")
            is ZerodhaClient.GttCreateResult.Failure ->
                errors.add("${h.symbol}: auto-create OCO failed — ${result.message}")
        }
    }

    private data class LadderResult(
        val filled: Boolean,
        val mode: String,      // "limit1", "limit2", "market", "none"
        val orderId: String?,
        val error: String?
    )

    private suspend fun runExitLadder(
        h: PortfolioHolding,
        gtts: List<ZerodhaClient.GttTrigger>
    ): LadderResult {
        // Cancel all symbol GTTs first
        for (g in gtts) {
            zc.deleteGttTrigger(g.id)
            delay(500)
        }

        // Attempt 1: limit at LTP × 0.998
        val price1 = Math.round(h.ltp * EXIT_LIMIT_SLIPPAGE_1 * 20.0) / 20.0
        val r1 = zc.placeOrder(h.exchange, h.symbol, "SELL", h.quantity, "LIMIT", price1)
        if (r1 is ZerodhaClient.OrderResult.Failure) {
            return LadderResult(false, "none", null, "placeOrder1 failed: ${r1.message}")
        }
        val id1 = (r1 as ZerodhaClient.OrderResult.Success).orderId
        if (pollUntilFilled(id1)) return LadderResult(true, "limit1", id1, null)

        // Cancel attempt 1
        zc.cancelOrder(id1)
        delay(API_CALL_GAP_MS)

        // Attempt 2: wider limit
        val price2 = Math.round(h.ltp * EXIT_LIMIT_SLIPPAGE_2 * 20.0) / 20.0
        val r2 = zc.placeOrder(h.exchange, h.symbol, "SELL", h.quantity, "LIMIT", price2)
        if (r2 is ZerodhaClient.OrderResult.Failure) {
            return LadderResult(false, "none", null, "placeOrder2 failed: ${r2.message}")
        }
        val id2 = (r2 as ZerodhaClient.OrderResult.Success).orderId
        if (pollUntilFilled(id2)) return LadderResult(true, "limit2", id2, null)

        // Cancel attempt 2
        zc.cancelOrder(id2)
        delay(API_CALL_GAP_MS)

        // Attempt 3: the last rung, priced far enough through the bid to be taken
        // at once by whatever is actually there.
        //
        // NOT A MARKET ORDER, though it was one and read as the obvious choice.
        // Zerodha's API refuses bare market orders — "market orders without market
        // protection are not allowed via API" — so this rung could only ever fail,
        // and it is the rung that runs when the position genuinely needs to be out.
        // The same refusal silently blocked every algo entry and exit in the Indian
        // space until it was traced; this is the last place it was still hiding.
        val price3 = Math.round(h.ltp * EXIT_LIMIT_SLIPPAGE_3 * 20.0) / 20.0
        val r3 = zc.placeOrder(h.exchange, h.symbol, "SELL", h.quantity, "LIMIT", price3)
        if (r3 is ZerodhaClient.OrderResult.Failure) {
            return LadderResult(false, "none", null, "final rung failed: ${r3.message}")
        }
        val id3 = (r3 as ZerodhaClient.OrderResult.Success).orderId
        // Market usually fills within a few seconds; still poll briefly
        if (pollUntilFilled(id3, maxWaitMs = 30_000L)) return LadderResult(true, "market", id3, null)
        return LadderResult(false, "market", id3, "market placed but not observed filling within 30s")
    }

    private suspend fun pollUntilFilled(orderId: String, maxWaitMs: Long = EXIT_WAIT_FILL_MS): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxWaitMs) {
            val status = zc.fetchOrderStatus(orderId)
            if (status == "COMPLETE") return true
            if (status == "REJECTED" || status == "CANCELLED") return false
            delay(ORDER_POLL_MS)
        }
        return false
    }

    private fun Double.format(decimals: Int) = "%.${decimals}f".format(this)
}

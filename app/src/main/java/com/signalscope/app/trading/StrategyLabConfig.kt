package com.signalscope.app.trading

import org.json.JSONObject

// ════════════════════════════════════════════════════════════════════════════
// STRATEGY LAB — LIVE AUTOMATED TRADING MODE (US space, PAPER ONLY)
// ════════════════════════════════════════════════════════════════════════════
// Config contract for the configurable MACD-slope engine, ported from
// STRATEGY_LAB_PORT_SPEC.md (§0/§0.1). The desktop screener exports a JSON; the
// app imports ONLY the engine parameters — any `symbols`/`dataset`/`vwap`/`orb`/
// `_meta` in the file is IGNORED (the basket is enrolled manually on-device).
//
// Unknown/missing fields fall back to the defaults shown. `enabled=false` blocks
// contribute nothing. Unsupported toggles (trendFilter, momentum gate/exit) cause
// an upload to be REJECTED to preserve parity with the desktop backtest.
// ════════════════════════════════════════════════════════════════════════════

data class StrategyLabConfig(
    val resample: String = "60min",      // ENTRY candle timeframe
    val exitBars: String = "30min",      // EXIT-check timeframe ("same" = single-timeframe)
    val warmupBars: Int = 240,           // entry-timeframe bars skipped before any decision
    val notional: Double = 1000.0,       // fixed $ per trade
    val slippagePct: Double = 0.05,      // subtracted from each closed trade's % return
    /**
     * The flat per-order fee for this space, already expressed as a percentage
     * of ONE ROUND TRIP against [notional]. Not read from the strategy JSON —
     * injected by StrategyLabStore.activeConfig from the app settings, because
     * it describes the broker rather than the strategy.
     */
    val flatFeeRoundTripPct: Double = 0.0,
    /**
     * Where the resample grid is anchored, in seconds east of UTC midnight. Not read from the
     * strategy JSON — injected by StrategyLabStore.activeConfig from the space, like the fees.
     *
     * The desktop lab anchors every bucket at the exchange's local midnight. For UTC-stamped
     * German data and New York's whole-hour offsets that grid coincides with the epoch one, but
     * India's +5:30 does not: an epoch-anchored 240-min grid starts NSE blocks at 05:30, 09:30,
     * 13:30 IST, so the day's first "4-hour" block holds the 09:15 hour alone. Its close then
     * drives entries, fills and the stop as though it were a whole block. 0 = epoch grid.
     */
    val barAnchorSec: Long = 0L,
    val macdFast: Int = 12,
    val macdSlow: Int = 26,
    val macdSignal: Int = 9,
    val entry: EntryCfg = EntryCfg(),
    val gates: GatesCfg = GatesCfg(),
    val exit: ExitCfg = ExitCfg(),
    // ── §10 portfolio-layer overrides. NULL = key absent in the JSON ⇒ use the
    // Algos UI value. Precedence is CONFIG > UI so that "run this JSON, get 374
    // trades" is a reproducible parity check; "set the Slots box to 5 first" is
    // not, and that is exactly how a silent mismatch survives.
    val topNOverride: Int? = null,              // monthlyReinvestTopN  (seats / hold-N)
    val slopeSmoothingOverride: Int? = null,    // reinvestSlopeSmoothing
    val slopeConfirmDaysOverride: Int? = null,  // reinvestSlopeConfirmDays
    val screenerLookbackOverride: Int? = null,  // screenerLookbackDays
    val reinvestStrategy: String? = null,       // e.g. "daily_macd_natural" (mode 3b)
    val source: String = "preset",       // "preset" or the uploaded filename
    /**
     * WHICH RULE SET THE ENGINE RUNS. "macd" is everything above. [SMA_HOLD] is
     * the crypto trend hold (Android spec "Crypto Hold above 50-day SMA", desktop
     * backtests of 19 Oct 2026): hold while the daily close is above its simple
     * average, sell on a daily close at or below it. None of the MACD, gate or
     * exit fields apply to it — [fromJson] sets them all inert.
     */
    val strategy: String = "macd",
    /** [SMA_HOLD] only: the average's length in completed daily closes. */
    val holdSmaLength: Int = 50
) {
    val isSmaHold: Boolean get() = strategy.equals(SMA_HOLD, ignoreCase = true)
    data class Cond(val enabled: Boolean = false)

    data class EntryCfg(
        val mode: String = "ALL",                 // "ALL" / "ANY"
        val slopeFlipUpEnabled: Boolean = true,
        val slopeFlipUpSmoothing: Int = 2,
        val slopeFlipUpPersistent: Boolean = false,
        val slopePositiveEnabled: Boolean = false,
        val macdCrossUpEnabled: Boolean = false,
        val macdAboveSignalEnabled: Boolean = false,
        val macdAboveZeroEnabled: Boolean = false,
        val accelPositiveEnabled: Boolean = true,
        val accelMinSigma: Double = 1.0,
        val sustainSlopeEnabled: Boolean = true,
        val sustainSlopeBars: Int = 20,
        val earlyRecoveryEnabled: Boolean = true,
        val earlyRecoveryPct: Double = 60.0,
        val earlyRecoveryLookback: Int = 22,
        val earlyRecoveryConfirmVelocity: Boolean = true,
        val velocityEnabled: Boolean = false,
        val velocityLookback: Int = 10,
        val velocityThreshold: Double = 0.5
    )

    data class GatesCfg(
        val adxEnabled: Boolean = false,
        val adxMin: Double = 20.0,
        val adxPeriod: Int = 14,
        val smaEnabled: Boolean = true,
        val smaLength: Int = 50,
        val smaKind: String = "ema",              // "ema" / "sma"
        val emaSlopeEnabled: Boolean = false,
        val emaSlopeLength: Int = 16,
        val emaSlopeLookback: Int = 10,
        val emaSlopeMin: Double = 0.1
    )

    data class ExitCfg(
        val mode: String = "ANY",
        val slopeFlipDownEnabled: Boolean = true,
        val slopeFlipDownSmoothing: Int = 2,
        val slopeFlipDownPersistent: Boolean = false,
        val fastMacdExitEnabled: Boolean = false,
        val slopeRetraceEnabled: Boolean = false,
        val slopeRetracePct: Double = 40.0,
        val slopeRetraceConfirmVelocity: Boolean = true,
        val macdCrossDownEnabled: Boolean = false,
        val macdBelowZeroEnabled: Boolean = false,
        val accelNegativeEnabled: Boolean = false,
        /**
         * Sigma floor for the deceleration exit. DEFAULT 0.0 ON PURPOSE.
         *
         * 0.0 reproduces the historical bare rule (`exitAccel < 0`) byte for byte, so every
         * config written before this field existed behaves exactly as it did. Note this is
         * NOT the entry side's default of 1.0 — mirroring that here would silently re-tune
         * any config that had already enabled accelNegative.
         *
         * k > 0 requires the deceleration to exceed k standard deviations of this symbol's
         * OWN acceleration swing before it fires. The bare rule triggers on ~50% of bars,
         * which is why every champion has kept it off; the scaled form is what made it
         * usable. See RESULTS_MATRIX_decel_exit.md in the backtest repo.
         */
        val accelNegativeMinSigma: Double = 0.0,
        val velocityEnabled: Boolean = false,
        val velocityLookback: Int = 10,
        val velocityThreshold: Double = 0.0,
        val stopLossEnabled: Boolean = true,
        val stopLossPct: Double = 4.0,
        val takeProfitEnabled: Boolean = false,
        val takeProfitPct: Double = 5.0,
        val maxBarsEnabled: Boolean = false,
        val maxBars: Int = 100,
        val macdDistanceEnabled: Boolean = true,
        val macdDistancePct: Double = 30.0,
        val minHoldBars: Int = 0,
        val sessionEnabled: Boolean = false,
        val sessionFlattenAtClose: Boolean = true,
        val sessionNoEntryAfter: String = "15:30"
    )

    val multiTimeframe: Boolean get() = !exitBars.equals("same", ignoreCase = true)

    /** Plain-words, read-only summary for the UI (no editable fields). */
    fun summary(): String {
        if (isSmaHold) return "Timeframe: 1 day (UTC close) · \$${notional.toInt()} · slip ${slippagePct}%\n" +
            "Hold while the daily close is above its $holdSmaLength-day simple average; " +
            "sell on a daily close at or below it.\nNo stop, no target — the average is the only exit."
        val core = buildList {
            if (entry.slopeFlipUpEnabled) add("slope flip up" + if (entry.slopeFlipUpPersistent) " (armed)" else "")
            if (entry.slopePositiveEnabled) add("slope positive")
            if (entry.macdCrossUpEnabled) add("MACD cross up")
            if (entry.macdAboveSignalEnabled) add("MACD>signal")
            if (entry.macdAboveZeroEnabled) add("MACD>0")
            if (entry.accelPositiveEnabled) add("accel>${entry.accelMinSigma}σ")
            if (entry.velocityEnabled) add("velocity≥${entry.velocityThreshold}%")
        }
        val rescue = buildList {
            if (entry.sustainSlopeEnabled) add("sustain ${entry.sustainSlopeBars} bars")
            if (entry.earlyRecoveryEnabled) add("early-recovery ${entry.earlyRecoveryPct}%")
        }
        val gateList = buildList {
            if (gates.adxEnabled) add("ADX≥${gates.adxMin}")
            if (gates.smaEnabled) add("price>${gates.smaKind.uppercase()}${gates.smaLength}")
            if (gates.emaSlopeEnabled) add("EMA-slope≥${gates.emaSlopeMin}%")
        }
        val exits = buildList {
            if (exit.slopeFlipDownEnabled) add("slope flip down" + if (exit.fastMacdExitEnabled) " (fast)" else "")
            if (exit.slopeRetraceEnabled) add("slope retrace ${exit.slopeRetracePct}%")
            if (exit.macdCrossDownEnabled) add("MACD cross down")
            if (exit.macdBelowZeroEnabled) add("MACD<0")
            if (exit.accelNegativeEnabled)
                add(if (exit.accelNegativeMinSigma > 0) "decel<${exit.accelNegativeMinSigma}σ" else "accel<0")
            if (exit.velocityEnabled) add("velocity≤${exit.velocityThreshold}%")
            if (exit.stopLossEnabled) add("stop -${exit.stopLossPct}%")
            if (exit.takeProfitEnabled) add("target +${exit.takeProfitPct}%")
            if (exit.maxBarsEnabled) add("max ${exit.maxBars} bars")
        }
        val tf = if (multiTimeframe) "$resample entries / $exitBars exits" else "$resample (single-timeframe)"
        return buildString {
            append("Timeframe: $tf · warmup $warmupBars · \$${notional.toInt()} · slip ${slippagePct}%\n")
            append("Entry (${entry.mode}): ${core.joinToString(", ").ifBlank { "—" }}")
            if (rescue.isNotEmpty()) append("  OR ${rescue.joinToString(", ")}")
            append("\n")
            append("Gates: ${gateList.joinToString(", ").ifBlank { "none" }}\n")
            append("Exit (${exit.mode}): ${exits.joinToString(", ").ifBlank { "—" }}")
            if (exit.macdDistanceEnabled) append("  [exit needs MACD moved ≥${exit.macdDistancePct}%]")
            if (exit.minHoldBars > 0) append("  [min hold ${exit.minHoldBars} bars]")
        }
    }

    companion object {
        /** The validated champion preset (§7) — symbol-free, the default until a config is uploaded. */
        fun preset(): StrategyLabConfig = StrategyLabConfig(source = "preset")

        /** [strategy] value of the crypto daily trend hold. */
        const val SMA_HOLD = "smaHold"

        /**
         * The crypto hold as tested: 50-day SMA on UTC daily closes, 0.2% per round
         * trip (Binance's 0.1% a side), 100 USDC a coin. What a new hold algo is
         * given, and the record of the tested claim — edit a copy, not this.
         */
        const val SMA_HOLD_JSON = """{
  "strategy": "smaHold",
  "resample": "1D",
  "exitBars": "same",
  "sma": { "kind": "sma", "length": 50 },
  "notional": 100,
  "slippagePct": 0.2,
  "_meta": "Hold while the daily UTC close is above its 50-day simple average; sell on a daily close at or below it. Decided just after 00:00 UTC, acted on at once. No stop, no target. Desktop backtests 19 Oct 2026: BTC+ETH 2021-04..2026-08 +16%/yr (hold -0.5%), worst drop -52% (hold -77%); 8 coins +22%/yr (hold -2%)."
}"""

        /**
         * The hold's own reader. Refuses the variants the desktop tests showed to
         * be worse rather than running them: an EMA instead of the SMA, an
         * average built on intraday candles, a stop or a target on top.
         */
        private fun smaHoldFromJson(root: JSONObject, source: String): ParseResult {
            val resample = str(root, "resample", "1D")
            if (FeedPlan.frameMinutes(resample, 1440) != 1440)
                return ParseResult.Rejected("The 50-day average rule runs on DAILY closes only — " +
                    "\"$resample\" would make it an average of intraday bars, which lost about 38% a year in testing.")
            val exitBars = str(root, "exitBars", "same")
            if (!exitBars.equals("same", true) && FeedPlan.frameMinutes(exitBars, 1440) != 1440)
                return ParseResult.Rejected("The average rule is checked once a day — exitBars must be \"same\".")
            val smaO = obj(root, "sma")
            if (!str(smaO, "kind", "sma").equals("sma", true))
                return ParseResult.Rejected("Only a SIMPLE average is supported — the EMA lost to it in every test.")
            val length = int(smaO, "length", 50)
            if (length < 2) return ParseResult.Rejected("sma.length must be at least 2.")
            val exitO = obj(root, "exit")
            if (bool(obj(exitO, "stopLoss"), "enabled", false) || bool(obj(exitO, "takeProfit"), "enabled", false))
                return ParseResult.Rejected("The average rule has no stop or target — neither was part of what was tested.")
            val d = StrategyLabConfig()
            return ParseResult.Ok(StrategyLabConfig(
                resample = "1D", exitBars = "same",
                // Feed planning and the "warming up" checks count this.
                warmupBars = length,
                notional = num(root, "notional", 100.0),
                slippagePct = num(root, "slippagePct", 0.2),
                // EVERYTHING MACD-SHAPED OFF. Code that reads these directly — the
                // real-money stop checks, the gate — must find nothing to act on.
                entry = EntryCfg(slopeFlipUpEnabled = false, accelPositiveEnabled = false,
                    sustainSlopeEnabled = false, earlyRecoveryEnabled = false),
                gates = GatesCfg(smaEnabled = false),
                exit = ExitCfg(slopeFlipDownEnabled = false, stopLossEnabled = false,
                    takeProfitEnabled = false, macdDistanceEnabled = false),
                macdFast = d.macdFast, macdSlow = d.macdSlow, macdSignal = d.macdSignal,
                source = source, strategy = SMA_HOLD, holdSmaLength = length))
        }

        sealed class ParseResult {
            data class Ok(val config: StrategyLabConfig) : ParseResult()
            data class Rejected(val reason: String) : ParseResult()
        }

        // ── tolerant config readers, mirroring the Python cfg_* helpers ──
        private fun obj(parent: JSONObject?, key: String): JSONObject? =
            parent?.optJSONObject(key)
        private fun bool(o: JSONObject?, key: String, def: Boolean) =
            if (o != null && o.has(key)) o.optBoolean(key, def) else def
        private fun num(o: JSONObject?, key: String, def: Double) =
            if (o != null && o.has(key)) o.optDouble(key, def) else def
        private fun int(o: JSONObject?, key: String, def: Int) =
            if (o != null && o.has(key)) o.optInt(key, def) else def
        private fun str(o: JSONObject?, key: String, def: String) =
            if (o != null && o.has(key)) (o.optString(key, def).ifBlank { def }) else def
        /** §10 override reader: null when the key is absent, so the caller can
         *  fall back to the UI value and log which source won. */
        private fun intOrNull(o: JSONObject?, key: String): Int? =
            if (o != null && o.has(key)) o.optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } else null
        private fun strOrNull(o: JSONObject?, key: String): String? =
            if (o != null && o.has(key)) o.optString(key, "").ifBlank { null } else null

        /**
         * Parse + validate an uploaded config JSON. Ignores symbols/dataset/vwap/
         * orb/_meta entirely. Rejects non-macd strategies, missing entry/exit, and
         * unsupported toggles (trendFilter / momentum) per §10.
         */
        fun fromJson(jsonText: String, source: String): ParseResult {
            val root = try { JSONObject(jsonText) } catch (e: Exception) {
                return ParseResult.Rejected("Not valid JSON: ${e.message}")
            }
            if (root.optString("strategy", "macd").equals(SMA_HOLD, ignoreCase = true))
                return smaHoldFromJson(root, source)
            if (!root.optString("strategy", "macd").equals("macd", ignoreCase = true)) {
                return ParseResult.Rejected("Only the 'macd' and '$SMA_HOLD' strategies are supported.")
            }
            val entryO = obj(root, "entry") ?: return ParseResult.Rejected("Missing 'entry' object.")
            val exitO = obj(root, "exit") ?: return ParseResult.Rejected("Missing 'exit' object.")
            val gatesO = obj(root, "gates")

            // Unsupported-feature guard (§10): reject rather than silently mis-run.
            val trendMode = str(obj(root, "trendFilter"), "mode", "off")
            if (!trendMode.equals("off", ignoreCase = true))
                return ParseResult.Rejected("trendFilter is not supported in the app (must be off).")
            if (bool(obj(gatesO, "momentum"), "enabled", false))
                return ParseResult.Rejected("gates.momentum is not supported in the app.")
            if (bool(obj(exitO, "momentumExit"), "enabled", false))
                return ParseResult.Rejected("exit.momentumExit is not supported in the app.")
            // exit.fastMacdExit is implemented (StrategyLabEngine §3.7/§6.1) and accepted.
            // It only has an effect in multi-timeframe mode — with exitBars="same" the
            // fine bars ARE the entry bars, so the coarse slope check is already the
            // fast one and the toggle is a no-op.
            // Not yet faithfully ported — reject rather than silently diverge.
            if (bool(obj(exitO, "session"), "enabled", false))
                return ParseResult.Rejected("exit.session (no-late-entry / flatten-at-close) is not implemented yet — disable it.")

            val macdO = obj(root, "macd")
            val d = StrategyLabConfig()  // defaults source

            val cfg = StrategyLabConfig(
                resample = str(root, "resample", d.resample),
                exitBars = str(root, "exitBars", d.exitBars),
                warmupBars = int(root, "warmupBars", d.warmupBars),
                notional = num(root, "notional", d.notional),
                slippagePct = num(root, "slippagePct", d.slippagePct),
                macdFast = int(macdO, "fast", d.macdFast),
                macdSlow = int(macdO, "slow", d.macdSlow),
                macdSignal = int(macdO, "signal", d.macdSignal),
                entry = EntryCfg(
                    mode = str(entryO, "mode", "ALL"),
                    slopeFlipUpEnabled = bool(obj(entryO, "slopeFlipUp"), "enabled", true),
                    slopeFlipUpSmoothing = int(obj(entryO, "slopeFlipUp"), "smoothing", 2),
                    slopeFlipUpPersistent = bool(obj(entryO, "slopeFlipUp"), "persistent", false),
                    slopePositiveEnabled = bool(obj(entryO, "slopePositive"), "enabled", false),
                    macdCrossUpEnabled = bool(obj(entryO, "macdCrossUp"), "enabled", false),
                    macdAboveSignalEnabled = bool(obj(entryO, "macdAboveSignal"), "enabled", false),
                    macdAboveZeroEnabled = bool(obj(entryO, "macdAboveZero"), "enabled", false),
                    accelPositiveEnabled = bool(obj(entryO, "accelPositive"), "enabled", true),
                    accelMinSigma = num(obj(entryO, "accelPositive"), "minSigma", 1.0),
                    sustainSlopeEnabled = bool(obj(entryO, "sustainSlope"), "enabled", true),
                    sustainSlopeBars = int(obj(entryO, "sustainSlope"), "bars", 20),
                    earlyRecoveryEnabled = bool(obj(entryO, "earlyRecovery"), "enabled", false),
                    earlyRecoveryPct = num(obj(entryO, "earlyRecovery"), "pct", 50.0),
                    earlyRecoveryLookback = int(obj(entryO, "earlyRecovery"), "lookback", 22),
                    earlyRecoveryConfirmVelocity = bool(obj(entryO, "earlyRecovery"), "confirmVelocity", true),
                    velocityEnabled = bool(obj(entryO, "velocity"), "enabled", false),
                    velocityLookback = int(obj(entryO, "velocity"), "lookback", 10),
                    velocityThreshold = num(obj(entryO, "velocity"), "threshold", 0.5)
                ),
                gates = GatesCfg(
                    adxEnabled = bool(obj(gatesO, "adx"), "enabled", false),
                    adxMin = num(obj(gatesO, "adx"), "min", 20.0),
                    adxPeriod = int(obj(gatesO, "adx"), "period", 14),
                    smaEnabled = bool(obj(gatesO, "sma"), "enabled", true),
                    smaLength = int(obj(gatesO, "sma"), "length", 50),
                    smaKind = str(obj(gatesO, "sma"), "kind", "ema"),
                    emaSlopeEnabled = bool(obj(gatesO, "emaSlope"), "enabled", false),
                    emaSlopeLength = int(obj(gatesO, "emaSlope"), "length", 16),
                    emaSlopeLookback = int(obj(gatesO, "emaSlope"), "lookback", 10),
                    emaSlopeMin = num(obj(gatesO, "emaSlope"), "min", 0.1)
                ),
                exit = ExitCfg(
                    mode = str(exitO, "mode", "ANY"),
                    slopeFlipDownEnabled = bool(obj(exitO, "slopeFlipDown"), "enabled", true),
                    slopeFlipDownSmoothing = int(obj(exitO, "slopeFlipDown"), "smoothing", 2),
                    slopeFlipDownPersistent = bool(obj(exitO, "slopeFlipDown"), "persistent", false),
                    fastMacdExitEnabled = bool(obj(exitO, "fastMacdExit"), "enabled", false),
                    slopeRetraceEnabled = bool(obj(exitO, "slopeRetrace"), "enabled", false),
                    slopeRetracePct = num(obj(exitO, "slopeRetrace"), "pct", 40.0),
                    slopeRetraceConfirmVelocity = bool(obj(exitO, "slopeRetrace"), "confirmVelocity", true),
                    macdCrossDownEnabled = bool(obj(exitO, "macdCrossDown"), "enabled", false),
                    macdBelowZeroEnabled = bool(obj(exitO, "macdBelowZero"), "enabled", false),
                    accelNegativeEnabled = bool(obj(exitO, "accelNegative"), "enabled", false),
                    accelNegativeMinSigma = num(obj(exitO, "accelNegative"), "minSigma", 0.0),
                    velocityEnabled = bool(obj(exitO, "velocity"), "enabled", false),
                    velocityLookback = int(obj(exitO, "velocity"), "lookback", 10),
                    velocityThreshold = num(obj(exitO, "velocity"), "threshold", 0.0),
                    stopLossEnabled = bool(obj(exitO, "stopLoss"), "enabled", true),
                    stopLossPct = num(obj(exitO, "stopLoss"), "pct", 4.0),
                    takeProfitEnabled = bool(obj(exitO, "takeProfit"), "enabled", false),
                    takeProfitPct = num(obj(exitO, "takeProfit"), "pct", 5.0),
                    maxBarsEnabled = bool(obj(exitO, "maxBars"), "enabled", false),
                    maxBars = int(obj(exitO, "maxBars"), "bars", 100),
                    macdDistanceEnabled = bool(obj(exitO, "macdDistance"), "enabled", true),
                    macdDistancePct = num(obj(exitO, "macdDistance"), "pct", 30.0),
                    minHoldBars = int(exitO, "minHoldBars", 0),
                    sessionEnabled = bool(obj(exitO, "session"), "enabled", false),
                    sessionFlattenAtClose = bool(obj(exitO, "session"), "flattenAtClose", true),
                    sessionNoEntryAfter = str(obj(exitO, "session"), "noEntryAfter", "15:30")
                ),
                // §10 — portfolio-layer overrides, read only if the key exists.
                topNOverride = intOrNull(root, "monthlyReinvestTopN"),
                slopeSmoothingOverride = intOrNull(root, "reinvestSlopeSmoothing"),
                slopeConfirmDaysOverride = intOrNull(root, "reinvestSlopeConfirmDays"),
                screenerLookbackOverride = intOrNull(root, "screenerLookbackDays"),
                reinvestStrategy = strOrNull(root, "reinvestStrategy"),
                source = source
            )
            return ParseResult.Ok(cfg)
        }
    }
}

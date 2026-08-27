package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sqrt

// ════════════════════════════════════════════════════════════════════════════
// STRATEGY LAB ENGINE — pure, deterministic port of engine_reference.py
// (STRATEGY_LAB_PORT_SPEC §1–§6). No Android/network/broker calls. PAPER ONLY.
//
// Given a symbol's fine candles (15-min Yahoo bars) and a StrategyLabConfig, it
// resamples to the entry/exit timeframes, computes indicators, and runs the
// same entry/exit state machine as the desktop backtester — returning the list
// of trades (closed + the one open, mark-to-market). The live runner (Stage 2)
// diffs this against a watermark to emit only new actions.
// ════════════════════════════════════════════════════════════════════════════

object StrategyLabEngine {

    private val NY: ZoneId = ZoneId.of("America/New_York")
    private const val NaN = Double.NaN

    data class Trade(
        val symbol: String,
        val side: String = "LONG",
        val status: String,            // "OPEN" / "CLOSED"
        val entryTs: Long,             // epoch seconds of the entry candle
        val entryPrice: Double,
        val entryReason: String,
        val exitTs: Long? = null,
        val exitPrice: Double? = null,
        val exitReason: String = "",
        val pctReturn: Double,
        val pnlNotional: Double,
        val barsHeld: Int
    )

    data class RunResult(
        val symbol: String,
        val trades: List<Trade>,
        val signals: Int,
        val gates: Map<String, Int>,
        val openCount: Int,
        val bars: Int,
        /** Timestamp (epoch sec) of the newest bar the engine actually evaluated,
         *  on the SAME timeframe that entryTs/exitTs are stamped on (the exit/fine
         *  frame in MTF mode). The live mirror compares entry/exit times against
         *  THIS — not the raw fetch granularity — so "newest bar only" is exact. */
        val lastBarTs: Long = 0L,
        /**
         * THE WINDOW THE ENGINE ACTUALLY RAN ON, as opposed to the one the config
         * asked for. [entryTfSec] is the frame AFTER clamping to the data's own
         * granularity, so it can differ from the configured resample without
         * anything being wrong — and until it was reported, a run on a silently
         * coarsened frame was indistinguishable from a run on the declared one.
         *
         * [firstTradableTs] is the timestamp of the first entry candle that clears
         * warmup. It exists so warmup can be audited WITHOUT counting bars: any
         * trade entered before it is a violation, full stop. Counting bars from a
         * date instead requires knowing session hours and holidays, which is
         * exactly where a warmup audit goes wrong — an India session is 6.25h, so
         * calendar arithmetic overstates elapsed bars badly.
         */
        val baseSec: Long = 0L,
        val entryTfSec: Long = 0L,
        val exitTfSec: Long = 0L,
        val entryBars: Int = 0,
        val warmBars: Int = 0,
        val firstTradableTs: Long = 0L,
        val error: String? = null
    )

    // ── EMA (recursive, adjust=false), seeded e[0]=x[0]; span>=2 ──
    private fun ema(x: DoubleArray, span: Int): DoubleArray {
        val s = if (span < 2) 2 else span
        val a = 2.0 / (s + 1.0)
        val out = DoubleArray(x.size)
        if (x.isEmpty()) return out
        out[0] = x[0]
        for (i in 1 until x.size) out[i] = a * x[i] + (1 - a) * out[i - 1]
        return out
    }

    private fun ewmAlpha(x: DoubleArray, alpha: Double): DoubleArray {
        val out = DoubleArray(x.size)
        if (x.isEmpty()) return out
        out[0] = x[0]
        for (i in 1 until x.size) out[i] = alpha * x[i] + (1 - alpha) * out[i - 1]
        return out
    }

    private fun diff(x: DoubleArray): DoubleArray {
        val out = DoubleArray(x.size)
        for (i in 1 until x.size) out[i] = x[i] - x[i - 1]
        return out
    }

    /** Wilder ADX (alpha = 1/period). Standard Wilder smoothing; parity-hardened
     *  later via §8 fixtures. ADX gate is OFF in the champion preset. */
    private fun adx(high: DoubleArray, low: DoubleArray, close: DoubleArray, period: Int): DoubleArray {
        val n = close.size
        val tr = DoubleArray(n); val plusDm = DoubleArray(n); val minusDm = DoubleArray(n)
        for (i in 1 until n) {
            val up = high[i] - high[i - 1]
            val dn = low[i - 1] - low[i]
            plusDm[i] = if (up > dn && up > 0) up else 0.0
            minusDm[i] = if (dn > up && dn > 0) dn else 0.0
            val hl = high[i] - low[i]
            val hc = abs(high[i] - close[i - 1]); val lc = abs(low[i] - close[i - 1])
            tr[i] = maxOf(hl, hc, lc)
        }
        if (n > 0) tr[0] = high[0] - low[0]
        val alpha = 1.0 / period.coerceAtLeast(2)
        val atr = ewmAlpha(tr, alpha)
        val pdi = ewmAlpha(plusDm, alpha); val mdi = ewmAlpha(minusDm, alpha)
        val dx = DoubleArray(n)
        for (i in 0 until n) {
            val plusDI = if (atr[i] != 0.0) 100.0 * pdi[i] / atr[i] else 0.0
            val minusDI = if (atr[i] != 0.0) 100.0 * mdi[i] / atr[i] else 0.0
            val denom = plusDI + minusDI
            dx[i] = if (denom != 0.0) abs(plusDI - minusDI) / denom * 100.0 else 0.0
        }
        return ewmAlpha(dx, alpha)
    }

    private fun rollingMean(x: DoubleArray, window: Int): DoubleArray {
        val out = DoubleArray(x.size) { NaN }
        var sum = 0.0
        for (i in x.indices) {
            sum += x[i]
            if (i >= window) sum -= x[i - window]
            if (i >= window - 1) out[i] = sum / window
        }
        return out
    }

    private fun rollingStdSample(x: DoubleArray, window: Int, minPeriods: Int): DoubleArray {
        val out = DoubleArray(x.size) { NaN }
        for (i in x.indices) {
            val start = maxOf(0, i - window + 1)
            val cnt = i - start + 1
            if (cnt < minPeriods) continue
            var mean = 0.0
            for (j in start..i) mean += x[j]
            mean /= cnt
            var ss = 0.0
            for (j in start..i) { val d = x[j] - mean; ss += d * d }
            out[i] = if (cnt > 1) sqrt(ss / (cnt - 1)) else 0.0
        }
        return out
    }

    private fun pctChange(x: DoubleArray, look: Int): DoubleArray {
        val out = DoubleArray(x.size) { NaN }
        for (i in x.indices) if (i >= look && x[i - look] != 0.0) out[i] = (x[i] / x[i - look] - 1.0) * 100.0
        return out
    }

    // ── timeframe parsing + resampling (left-closed, left-label epoch buckets) ──
    fun timeframeSeconds(tf: String): Long = when {
        tf.equals("same", true) -> 0L
        tf.endsWith("min", true) -> (tf.dropLast(3).trim().toLongOrNull() ?: 60L) * 60L
        tf.endsWith("h", true) -> (tf.dropLast(1).trim().toLongOrNull() ?: 1L) * 3600L
        tf.endsWith("D", true) || tf.endsWith("d", true) -> (tf.dropLast(1).trim().toLongOrNull() ?: 1L) * 86400L
        else -> 3600L
    }

    /** The data's own bar spacing in seconds — the MEDIAN gap between consecutive
     *  bars (median, not min, so one dup/odd gap can't shrink it; same rule as
     *  GateBacktest.toDaily's native-daily detection). 0 if < 2 bars. */
    fun baseGranularitySec(candles: List<CandleData>): Long {
        if (candles.size < 2) return 0L
        val ts = LongArray(candles.size) { candles[it].timestamp }
        ts.sort()
        val gaps = ArrayList<Long>(ts.size - 1)
        for (i in 1 until ts.size) { val gp = ts[i] - ts[i - 1]; if (gp > 0) gaps.add(gp) }
        if (gaps.isEmpty()) return 0L
        gaps.sort()
        return gaps[gaps.size / 2]
    }

    /** RESAMPLE CLAMP — you cannot synthesize bars finer than the source. A frame
     *  FINER than the base bar (e.g. a 240-min entry over DAILY data) is impossible
     *  to build, so we clamp it UP to the base and run on the base bars — exactly
     *  the Python lab's resample="" native path. A frame coarser than the base
     *  resamples as before; 0 ("same") is left untouched. This is a true no-op for
     *  correctly-grained data (60-min bars → 240/120 config still resamples). */
    fun clampTf(requestedSec: Long, baseSec: Long): Long =
        if (requestedSec in 1 until baseSec) baseSec else requestedSec

    fun resample(
        candles: List<CandleData>,
        periodSec: Long,
        completeThroughSec: Long? = null
    ): List<CandleData> {
        if (periodSec <= 0L || candles.isEmpty()) return candles
        val sorted = candles.sortedBy { it.timestamp }
        val out = ArrayList<CandleData>()
        var bStart = -1L
        var o = 0.0; var h = 0.0; var l = 0.0; var c = 0.0; var v = 0L
        fun keepBucket(start: Long): Boolean =
            completeThroughSec == null || start + periodSec <= completeThroughSec
        for (cd in sorted) {
            val bucket = (cd.timestamp / periodSec) * periodSec
            if (bucket != bStart) {
                if (bStart >= 0 && keepBucket(bStart)) out.add(CandleData(bStart, o, h, l, c, v))
                bStart = bucket; o = cd.open; h = cd.high; l = cd.low; c = cd.close; v = cd.volume
            } else {
                h = maxOf(h, cd.high); l = minOf(l, cd.low); c = cd.close; v += cd.volume
            }
        }
        if (bStart >= 0 && keepBucket(bStart)) out.add(CandleData(bStart, o, h, l, c, v))
        return out
    }

    private fun nyDateCode(ts: Long): Long =
        Instant.ofEpochSecond(ts).atZone(NY).toLocalDate().toEpochDay()

    private fun lastOfDay(ts: LongArray): BooleanArray {
        val n = ts.size
        val out = BooleanArray(n) { true }
        if (n > 1) for (i in 0 until n - 1) out[i] = nyDateCode(ts[i + 1]) != nyDateCode(ts[i])
        return out
    }

    // ── indicator frame (computed on a given timeframe's candles) ──
    private class Frame(val cd: List<CandleData>, val cfg: StrategyLabConfig) {
        val n = cd.size
        val close = DoubleArray(n) { cd[it].close }
        val high = DoubleArray(n) { cd[it].high }
        val low = DoubleArray(n) { cd[it].low }
        val ts = LongArray(n) { cd[it].timestamp }
        lateinit var macd: DoubleArray
        lateinit var macdSignal: DoubleArray
        lateinit var entrySlope: DoubleArray
        lateinit var exitSlope: DoubleArray
        lateinit var entryAccel: DoubleArray
        lateinit var exitAccel: DoubleArray
        lateinit var accelSigma: DoubleArray
        lateinit var runlen: IntArray
        lateinit var trough: DoubleArray
        lateinit var emaSlopeChg: DoubleArray
        lateinit var entryVel: DoubleArray
        lateinit var exitVel: DoubleArray
        lateinit var level: DoubleArray
        lateinit var adxArr: DoubleArray
    }

    private fun computeIndicators(f: Frame) {
        val cfg = f.cfg; val close = f.close
        val macd = DoubleArray(f.n)
        val ef = ema(close, cfg.macdFast); val es = ema(close, cfg.macdSlow)
        for (i in 0 until f.n) macd[i] = ef[i] - es[i]
        f.macd = macd
        f.macdSignal = ema(macd, cfg.macdSignal)
        val rawSlope = diff(macd)
        f.entrySlope = ema(rawSlope, cfg.entry.slopeFlipUpSmoothing)
        f.exitSlope = ema(rawSlope, cfg.exit.slopeFlipDownSmoothing)
        f.entryAccel = diff(f.entrySlope)
        f.exitAccel = diff(f.exitSlope)
        f.accelSigma = rollingStdSample(f.entryAccel, 200, 50)

        val runlen = IntArray(f.n)
        for (i in 0 until f.n) runlen[i] = if (f.entrySlope[i] >= 0.0) (if (i > 0) runlen[i - 1] else 0) + 1 else 0
        f.runlen = runlen

        val look = maxOf(2, cfg.entry.earlyRecoveryLookback)
        val trough = DoubleArray(f.n) { NaN }
        for (i in 0 until f.n) {
            val start = maxOf(0, i - look + 1)
            if (i - start + 1 >= 2) {
                var m = f.entrySlope[start]
                for (j in start..i) m = minOf(m, f.entrySlope[j])
                trough[i] = m
            }
        }
        f.trough = trough

        val emaG = ema(close, maxOf(2, cfg.gates.emaSlopeLength))
        val emaLook = maxOf(1, cfg.gates.emaSlopeLookback)
        val chg = DoubleArray(f.n) { NaN }
        for (i in 0 until f.n) if (i >= emaLook && emaG[i - emaLook] != 0.0) chg[i] = (emaG[i] / emaG[i - emaLook] - 1.0) * 100.0
        f.emaSlopeChg = chg

        f.entryVel = pctChange(close, maxOf(1, cfg.entry.velocityLookback))
        f.exitVel = pctChange(close, maxOf(1, cfg.exit.velocityLookback))

        val lvlLen = maxOf(2, cfg.gates.smaLength)
        f.level = if (cfg.gates.smaKind.equals("ema", true)) ema(close, lvlLen) else rollingMean(close, lvlLen)
        f.adxArr = adx(f.high, f.low, f.close, maxOf(2, cfg.gates.adxPeriod))
    }

    // ── fast MACD exit (§3.7) — the ONE series computed on the FINE bars ──
    // Everything in Frame/computeIndicators lives on the coarse ENTRY candles and is
    // read as-of. `exit.fastMacdExit` needs a second MACD + slope built directly from
    // the fine (exit-resolution) closes so the slope-flip-down exit reacts on every
    // fine bar instead of waiting for the slow entry candle to close.
    private class FastExit(val macd: DoubleArray, val slope: DoubleArray)

    private fun computeFastExit(fineClose: DoubleArray, cfg: StrategyLabConfig): FastExit {
        val ef = ema(fineClose, cfg.macdFast)
        val es = ema(fineClose, cfg.macdSlow)
        val macd = DoubleArray(fineClose.size) { ef[it] - es[it] }
        // same recipe as the coarse slope (diff[0]=0, then EMA), just on fine data
        return FastExit(macd, ema(diff(macd), cfg.exit.slopeFlipDownSmoothing))
    }

    /**
     * §6.1 — fires the slope-flip-down exit off the FINE-bar series. Returns the exit
     * reason, or null if it doesn't fire (or is suppressed by the MACD-distance
     * qualifier, which for the fast exit is measured on the FINE MACD against the fine
     * MACD at the entry bar — not the coarse one).
     */
    private fun fastExitReason(fe: FastExit, i: Int, entryFineMacd: Double, cfg: StrategyLabConfig): String? {
        if (i < 1) return null
        val x = cfg.exit
        val armed = x.mode.equals("ALL", true) || x.slopeFlipDownPersistent
        val flip = if (armed) fe.slope[i] <= 0.0
                   else fe.slope[i - 1] > 0.0 && fe.slope[i] <= 0.0
        if (!flip) return null
        if (x.macdDistanceEnabled && abs(entryFineMacd) > 1e-12) {
            val moved = abs(fe.macd[i] - entryFineMacd) / abs(entryFineMacd) * 100.0
            if (moved < x.macdDistancePct) return null  // hasn't travelled far enough — hold
        }
        return if (armed) "MACD slope negative on ${cfg.exitBars} bar (armed, fast exit)"
               else "MACD slope flipped + to - on ${cfg.exitBars} bar (fast exit)"
    }

    // ── entry / exit logic (§4–§5) ──
    private fun entrySignal(f: Frame, i: Int, cfg: StrategyLabConfig): Pair<Boolean, String> {
        val e = cfg.entry
        val checks = ArrayList<Pair<Boolean, String>>()
        // Reason strings are kept VERBATIM-identical to engine_reference.py so an
        // exported report diffs cleanly against the desktop backtest.
        if (e.slopeFlipUpEnabled) {
            if (e.slopeFlipUpPersistent) checks.add(Pair(f.entrySlope[i] >= 0.0, "MACD slope >= 0 (armed while > 0)"))
            else checks.add(Pair(f.entrySlope[i - 1] < 0.0 && f.entrySlope[i] >= 0.0, "MACD slope flipped - to +"))
        }
        if (e.slopePositiveEnabled) checks.add(Pair(f.entrySlope[i] > 0.0, "MACD slope already positive"))
        if (e.macdCrossUpEnabled) checks.add(Pair(f.macd[i - 1] <= f.macdSignal[i - 1] && f.macd[i] > f.macdSignal[i], "MACD crossed above signal"))
        if (e.macdAboveSignalEnabled) checks.add(Pair(f.macd[i] > f.macdSignal[i], "MACD above signal"))
        if (e.macdAboveZeroEnabled) checks.add(Pair(f.macd[i] > 0.0, "MACD above zero"))
        if (e.accelPositiveEnabled) {
            val accel = f.entryAccel[i]
            val sigma = f.accelSigma[i]
            val floor = if (e.accelMinSigma > 0 && !sigma.isNaN()) e.accelMinSigma * sigma else 0.0
            val label = if (floor != 0.0) "MACD acceleration %.4f > %.4f".format(accel, floor) else "MACD acceleration positive"
            checks.add(Pair(accel > floor, label))
        }
        if (e.velocityEnabled) {
            val v = f.entryVel[i]
            val shown = if (v.isNaN()) 0.0 else v
            checks.add(Pair(!v.isNaN() && v >= e.velocityThreshold, "velocity %.2f%% >= threshold".format(shown)))
        }

        val need = maxOf(1, e.sustainSlopeBars)
        var sustainOk = false
        if (e.sustainSlopeEnabled) sustainOk = f.entrySlope[i] > 0.0 && f.runlen[i] >= need
        var earlyOk = false
        if (e.earlyRecoveryEnabled) {
            val trough = f.trough[i]
            if (!trough.isNaN() && trough < -1e-9) {
                val thr = (e.earlyRecoveryPct / 100.0) * trough
                val crossed = f.entrySlope[i - 1] < thr && f.entrySlope[i] >= thr
                val velOk = !e.earlyRecoveryConfirmVelocity || (!f.entryVel[i].isNaN() && f.entryVel[i] > 0.0)
                earlyOk = crossed && velOk
            }
        }
        if (checks.isEmpty() && !e.sustainSlopeEnabled && !e.earlyRecoveryEnabled) return Pair(false, "")
        val any = e.mode.equals("ANY", true)
        val core = if (checks.isEmpty()) false else if (any) checks.any { it.first } else checks.all { it.first }
        val fired = core || sustainOk || earlyOk
        val reasons = ArrayList<String>()
        checks.filter { it.first }.forEach { reasons.add(it.second) }
        if (sustainOk) reasons.add("slope positive ${f.runlen[i]} bars >= $need (integral)")
        if (earlyOk) reasons.add("slope recovered to ${g(e.earlyRecoveryPct)}% of trough (early entry)" +
            (if (e.earlyRecoveryConfirmVelocity) ", vel>0" else ""))
        return Pair(fired, reasons.joinToString("; "))
    }

    private fun gateBlock(f: Frame, i: Int, cfg: StrategyLabConfig): String? {
        val g = cfg.gates
        if (g.adxEnabled && f.adxArr[i] < g.adxMin) return "ADX below minimum"
        if (g.smaEnabled) { val lv = f.level[i]; if (lv.isNaN() || f.close[i] <= lv) return "Price below ${g.smaKind.uppercase()}" }
        if (g.emaSlopeEnabled) { val c = f.emaSlopeChg[i]; if (c.isNaN() || c < g.emaSlopeMin) return "EMA slope below minimum" }
        return null
    }

    private fun indicatorExit(f: Frame, i: Int, entryMacd: Double, cfg: StrategyLabConfig,
                              includeVelocity: Boolean, includeSlope: Boolean): String? {
        val x = cfg.exit
        val all = x.mode.equals("ALL", true)
        val persistent = x.slopeFlipDownPersistent
        val checks = ArrayList<Pair<Boolean, String>>()
        if (includeSlope && x.slopeFlipDownEnabled) {
            if (all || persistent) checks.add(Pair(f.exitSlope[i] <= 0.0, "MACD slope negative (armed while < 0)"))
            else checks.add(Pair(f.exitSlope[i - 1] > 0.0 && f.exitSlope[i] <= 0.0, "MACD slope flipped + to -"))
        }
        if (x.macdCrossDownEnabled) {
            if (all) checks.add(Pair(f.macd[i] < f.macdSignal[i], "MACD below signal"))
            else checks.add(Pair(f.macd[i - 1] >= f.macdSignal[i - 1] && f.macd[i] < f.macdSignal[i], "MACD crossed below signal"))
        }
        if (x.macdBelowZeroEnabled) checks.add(Pair(f.macd[i] < 0.0, "MACD below zero"))
        if (x.accelNegativeEnabled) checks.add(Pair(f.exitAccel[i] < 0.0, "MACD acceleration negative"))
        if (includeVelocity && x.velocityEnabled) {
            val v = f.exitVel[i]; checks.add(Pair(!v.isNaN() && v <= x.velocityThreshold, "velocity below floor"))
        }
        if (checks.isEmpty()) return null
        val fired = if (all) checks.all { it.first } else checks.any { it.first }
        if (!fired) return null
        if (x.macdDistanceEnabled && abs(entryMacd) > 1e-12) {
            val moved = abs(f.macd[i] - entryMacd) / abs(entryMacd) * 100.0
            if (moved < x.macdDistancePct) return null  // suppressed until MACD has travelled
        }
        return checks.filter { it.first }.joinToString("; ") { it.second }
    }

    // Raw per-trade return rounded to 4 dp ONCE at close (Python strategy_lab.py
    // :591 round(pct,4)). applySlippage then does round4(this − slip), matching
    // Python's two-step round(round(raw,4) − slip, 4). Downstream compounding stays
    // full precision — Python has no per-step capital rounding, so neither do we.
    private fun pctReturn(entry: Double, exit: Double) = if (entry > 0) round4((exit - entry) / entry * 100.0) else 0.0

    /** Python "{:g}" style — 4.0 → "4", 0.05 → "0.05" — for parity-identical reason strings. */
    private fun g(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    fun run(symbol: String, fineCandles: List<CandleData>, cfg: StrategyLabConfig): RunResult {
        return try {
            applySlippage(dispatch(symbol, fineCandles, cfg, null), cfg)
        } catch (e: Exception) {
            RunResult(symbol, emptyList(), 0, emptyMap(), 0, fineCandles.size, error = e.message ?: "engine error")
        }
    }

    /**
     * Live-forward variant used by StrategyLabRunner. The incoming source bars
     * are already individually closed, but a higher timeframe bucket (for
     * example a 60-min entry candle made from four 15-min bars) may still be
     * incomplete. Drop incomplete resampled buckets for live decisions while
     * keeping [run] unchanged for CSV/backtest parity with the desktop engine.
     */
    fun runLiveClosed(
        symbol: String,
        fineCandles: List<CandleData>,
        cfg: StrategyLabConfig,
        sourcePeriodSec: Long = 15 * 60L
    ): RunResult {
        return try {
            val completeThroughSec = fineCandles.maxOfOrNull { it.timestamp + sourcePeriodSec } ?: 0L
            applySlippage(dispatch(symbol, fineCandles, cfg, completeThroughSec), cfg)
        } catch (e: Exception) {
            RunResult(symbol, emptyList(), 0, emptyMap(), 0, fineCandles.size, error = e.message ?: "engine error")
        }
    }

    /**
     * Pick single- vs multi-timeframe AFTER clamping each configured frame to the
     * data's own bar spacing (see [clampTf]). You cannot build a frame finer than
     * the source, so a finer-than-base frame runs on the base bars — the Python
     * lab's resample="" native path. When both frames collapse to the base bar,
     * MTF degenerates to single-timeframe, so route to runSingle (native, exits on
     * the same bars). No-op for correctly-grained data.
     */
    private fun dispatch(
        symbol: String, raw: List<CandleData>, cfg: StrategyLabConfig, completeThroughSec: Long?
    ): RunResult {
        val base = baseGranularitySec(raw)
        val entryTf = clampTf(timeframeSeconds(cfg.resample), base)
        val exitTf = clampTf(timeframeSeconds(cfg.exitBars), base)
        val mtf = cfg.multiTimeframe && exitTf in 1 until entryTf
        return if (mtf) runMtf(symbol, raw, cfg, entryTf, exitTf, completeThroughSec)
               else runSingle(symbol, raw, cfg, entryTf, completeThroughSec)
    }

    /** Subtract slippagePct from each CLOSED trade's % return and recompute its
     *  $-PnL on the notional — exactly like the desktop apply_slippage_and_compound.
     *  OPEN (mark-to-market) trades keep their gross figure, as in the reference. */
    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0

    private fun applySlippage(res: RunResult, cfg: StrategyLabConfig): RunResult {
        // Spread (a percentage) and brokerage (a flat cash amount) are different
        // costs and are charged together here, once per closed round trip. The
        // flat part arrives pre-converted to a percentage of the notional, so a
        // config with no fee set behaves exactly as before — important, because
        // the parity runs against Python assume slippage alone.
        val cost = cfg.slippagePct + cfg.flatFeeRoundTripPct
        if (cost <= 0.0) return res
        val adj = res.trades.map { t ->
            if (t.status == "CLOSED") {
                // Parity with apply_slippage_and_compound: round net% AND $-PnL to
                // 4 dp. Leaving it unrounded put Android a hair off Python on every
                // slippage>0 config; slippage=0 configs skip this whole path, which
                // is why the German (slip 0.0) run matched trade-for-trade but these
                // 0.2 champions drift by small amounts.
                val net = round4(t.pctReturn - cost)
                t.copy(pctReturn = net, pnlNotional = round4(cfg.notional * net / 100.0))
            } else t
        }
        return res.copy(trades = adj)
    }

    // ── single-timeframe (exitBars == "same") ──
    private fun runSingle(
        symbol: String,
        raw: List<CandleData>,
        cfg: StrategyLabConfig,
        entryTf: Long,
        completeThroughSec: Long? = null
    ): RunResult {
        val cd = resample(raw, entryTf, completeThroughSec)
        val f = Frame(cd, cfg); if (f.n < 3) return RunResult(symbol, emptyList(), 0, emptyMap(), 0, f.n)
        computeIndicators(f)
        val trades = ArrayList<Trade>(); val gates = HashMap<String, Int>(); var signals = 0
        var inTrade = false; var entryPrice = 0.0; var entryMacd = 0.0; var entryTs = 0L; var entryReason = ""; var entryIdx = -1
        var peakSlope = 0.0
        val retraceOn = cfg.exit.slopeRetraceEnabled; val retraceFrac = cfg.exit.slopeRetracePct / 100.0
        val sessOn = cfg.exit.sessionEnabled
        val sessFlat = sessOn && cfg.exit.sessionFlattenAtClose
        val lod = if (sessOn) lastOfDay(f.ts) else null
        val warm = maxOf(1, cfg.warmupBars)
        for (i in warm until f.n) {
            if (inTrade) {
                peakSlope = maxOf(peakSlope, f.exitSlope[i])
                val barsHeld = i - entryIdx
                var reason: String? = null
                val close = f.close[i]
                if (cfg.exit.stopLossEnabled && close <= entryPrice * (1 - cfg.exit.stopLossPct / 100.0)) reason = "stop loss -${g(cfg.exit.stopLossPct)}%"
                else if (cfg.exit.takeProfitEnabled && close >= entryPrice * (1 + cfg.exit.takeProfitPct / 100.0)) reason = "take profit +${g(cfg.exit.takeProfitPct)}%"
                else if (cfg.exit.maxBarsEnabled && barsHeld >= cfg.exit.maxBars) reason = "max holding ${cfg.exit.maxBars} bars"
                else if (barsHeld < cfg.exit.minHoldBars) reason = null
                else reason = indicatorExit(f, i, entryMacd, cfg, includeVelocity = true, includeSlope = true)
                if (reason == null && retraceOn && peakSlope > 1e-9 && barsHeld >= cfg.exit.minHoldBars &&
                    f.exitSlope[i] <= retraceFrac * peakSlope && f.exitSlope[i] < f.exitSlope[i - 1]) {
                    reason = "slope retraced to ${g(cfg.exit.slopeRetracePct)}% of peak (rollover exit)"
                }
                if (reason == null && sessFlat && lod != null && lod[i]) reason = "session close - flat (no overnight)"
                if (reason != null) {
                    val pct = pctReturn(entryPrice, close)
                    trades.add(Trade(symbol, "LONG", "CLOSED", entryTs, entryPrice, entryReason, f.ts[i], close, reason,
                        pct, cfg.notional * pct / 100.0, barsHeld))
                    inTrade = false
                }
                continue
            }
            val (fired, reasons) = entrySignal(f, i, cfg)
            if (!fired) continue
            signals++
            val block = gateBlock(f, i, cfg)
            if (block != null) { gates[block] = (gates[block] ?: 0) + 1; continue }
            inTrade = true; entryPrice = f.close[i]; entryMacd = f.macd[i]; entryTs = f.ts[i]; entryIdx = i
            entryReason = reasons; peakSlope = f.exitSlope[i]
        }
        var openCount = 0
        if (inTrade) {
            val last = f.n - 1; val pct = pctReturn(entryPrice, f.close[last]); openCount = 1
            trades.add(Trade(symbol, "LONG", "OPEN", entryTs, entryPrice, entryReason, null, null,
                "mark-to-market", pct, cfg.notional * pct / 100.0, last - entryIdx))
        }
        return RunResult(symbol, trades, signals, gates, openCount, f.n,
            lastBarTs = if (f.n > 0) f.ts[f.n - 1] else 0L,
            baseSec = baseGranularitySec(raw), entryTfSec = entryTf, exitTfSec = entryTf,
            entryBars = f.n, warmBars = warm,
            firstTradableTs = if (warm < f.n) f.ts[warm] else 0L)
    }

    // ── multi-timeframe (entries on entry candles; exits on fine bars) ──
    private fun runMtf(
        symbol: String,
        raw: List<CandleData>,
        cfg: StrategyLabConfig,
        entryTf: Long,
        exitTf: Long,
        completeThroughSec: Long? = null
    ): RunResult {
        val fine = resample(raw, exitTf, completeThroughSec)
        val cd = resample(raw, entryTf, completeThroughSec)
        val warm = maxOf(1, cfg.warmupBars)
        if (cd.size <= warm || fine.size < 10) return RunResult(symbol, emptyList(), 0, emptyMap(), 0, fine.size)
        val f = Frame(cd, cfg); computeIndicators(f)

        val eEnd = LongArray(f.n) { f.ts[it] + entryTf }
        val fineTs = LongArray(fine.size) { fine[it].timestamp }
        val fineClose = DoubleArray(fine.size) { fine[it].close }
        val fEnd = LongArray(fine.size) { fineTs[it] + exitTf }
        // map each fine bar to the most recent entry candle whose end <= this fine bar end
        val eIdxPerFine = IntArray(fine.size) { fi ->
            var lo = 0; var hi = f.n  // upper bound (searchsorted 'right')
            val key = fEnd[fi]
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (eEnd[mid] <= key) lo = mid + 1 else hi = mid }
            lo - 1
        }
        val exitVelFine = pctChange(fineClose, maxOf(1, cfg.exit.velocityLookback))
        val sessOn = cfg.exit.sessionEnabled
        val sessFlat = sessOn && cfg.exit.sessionFlattenAtClose
        val lod = if (sessOn) lastOfDay(fineTs) else null

        val trades = ArrayList<Trade>(); val gates = HashMap<String, Int>(); var signals = 0
        var inTrade = false; var entryPrice = 0.0; var entryMacd = 0.0; var entryTs = 0L; var entryReason = ""; var entryFineIdx = -1
        var lastE = 0; var peakSlope = 0.0
        val x = cfg.exit
        val retraceOn = x.slopeRetraceEnabled; val retraceFrac = x.slopeRetracePct / 100.0
        // §6.1 — fast exit and the coarse slope-flip-down are mutually exclusive: when
        // fast exit is on, the slope check moves to the fine bars and the coarse row is
        // dropped from indicatorExit.
        val fastOn = x.fastMacdExitEnabled && x.slopeFlipDownEnabled
        val fe = if (fastOn) computeFastExit(fineClose, cfg) else null
        var entryFineMacd = NaN

        for (i in fine.indices) {
            val ei = eIdxPerFine[i]; val newE = ei > lastE && ei >= 1
            val close = fineClose[i]
            if (inTrade) {
                if (newE) peakSlope = maxOf(peakSlope, f.exitSlope[ei])
                var reason: String? = null
                val held = i - entryFineIdx
                // §6.1 priority: hard exits → min-hold gate → FAST exit → retrace → indicators
                val fastReason = if (fe != null && held >= x.minHoldBars) fastExitReason(fe, i, entryFineMacd, cfg) else null
                if (x.stopLossEnabled && close <= entryPrice * (1 - x.stopLossPct / 100.0)) reason = "stop loss -${g(x.stopLossPct)}% (fine bar)"
                else if (x.takeProfitEnabled && close >= entryPrice * (1 + x.takeProfitPct / 100.0)) reason = "take profit +${g(x.takeProfitPct)}% (fine bar)"
                else if (x.maxBarsEnabled && held >= x.maxBars) reason = "max holding ${x.maxBars} fine bars"
                else if (held < x.minHoldBars) reason = null
                else if (fastReason != null) reason = fastReason
                else if (retraceOn && newE && peakSlope > 1e-9 && ei >= 1 &&
                    f.exitSlope[ei] <= retraceFrac * peakSlope && f.exitSlope[ei] < f.exitSlope[ei - 1]) {
                    var allowed = !x.slopeRetraceConfirmVelocity || (!exitVelFine[i].isNaN() && exitVelFine[i] <= 0.0)
                    if (x.macdDistanceEnabled && abs(entryMacd) > 1e-12) {
                        val moved = abs(f.macd[ei] - entryMacd) / abs(entryMacd) * 100.0; allowed = allowed && moved >= x.macdDistancePct
                    }
                    if (allowed) reason = "slope retraced to ${g(x.slopeRetracePct)}% of peak (rollover exit)"
                } else if (x.velocityEnabled && !exitVelFine[i].isNaN() && exitVelFine[i] <= x.velocityThreshold) {
                    var allowed = true
                    if (x.macdDistanceEnabled && abs(entryMacd) > 1e-12 && ei >= 0) {
                        val moved = abs(f.macd[ei] - entryMacd) / abs(entryMacd) * 100.0; allowed = moved >= x.macdDistancePct
                    }
                    if (allowed) reason = "velocity below floor (fine bar)"
                } else if (newE) {
                    reason = indicatorExit(f, ei, entryMacd, cfg, includeVelocity = false, includeSlope = !fastOn)
                }
                if (reason == null && sessFlat && lod != null && lod[i]) reason = "session close - flat (no overnight)"
                if (reason != null) {
                    val pct = pctReturn(entryPrice, close)
                    trades.add(Trade(symbol, "LONG", "CLOSED", entryTs, entryPrice, entryReason, fineTs[i], close, reason,
                        pct, cfg.notional * pct / 100.0, held))
                    inTrade = false
                }
            } else if (newE && ei >= warm) {
                val (fired, reasons) = entrySignal(f, ei, cfg)
                if (fired) {
                    signals++
                    val block = gateBlock(f, ei, cfg)
                    if (block != null) gates[block] = (gates[block] ?: 0) + 1
                    else {
                        inTrade = true; entryPrice = close; entryMacd = f.macd[ei]; entryTs = fineTs[i]
                        entryFineIdx = i; entryReason = reasons; peakSlope = f.exitSlope[ei]
                        entryFineMacd = fe?.macd?.get(i) ?: NaN   // §6.1 fast-exit distance baseline
                    }
                }
            }
            if (newE) lastE = ei
        }
        var openCount = 0
        if (inTrade) {
            val last = fine.size - 1; val pct = pctReturn(entryPrice, fineClose[last]); openCount = 1
            trades.add(Trade(symbol, "LONG", "OPEN", entryTs, entryPrice, entryReason, null, null,
                "mark-to-market", pct, cfg.notional * pct / 100.0, last - entryFineIdx))
        }
        return RunResult(symbol, trades, signals, gates, openCount, fine.size,
            lastBarTs = if (fine.isNotEmpty()) fineTs[fine.size - 1] else 0L,
            baseSec = baseGranularitySec(raw), entryTfSec = entryTf, exitTfSec = exitTf,
            entryBars = f.n, warmBars = warm,
            firstTradableTs = if (warm < f.n) f.ts[warm] else 0L)
    }

    // ════════════════════════════════════════════════════════════════════════
    // REVIEW PAYLOAD — the "why did/didn't it trade at this bar" data.
    // Reuses the SAME Frame/computeIndicators/entrySignal/gateBlock/indicatorExit
    // the live engine uses, so the per-bar checklist matches the engine exactly.
    // Windowed to the most recent [maxBars] fine bars to keep the payload small.
    // ════════════════════════════════════════════════════════════════════════
    data class CheckRow(val ok: Boolean, val label: String)

    data class BarReview(
        val t: Long, val o: Double, val h: Double, val l: Double, val c: Double,
        // nullable — NaN before warmup would make Gson emit invalid JSON, so we
        // send null (JS treats null as "no value / skip in the polyline").
        val macd: Double?, val macdSignal: Double?, val entrySlope: Double?, val exitSlope: Double?,
        val entryMarker: Int,          // 0 none · 1 flip+accel · 2 integral-forced
        val exitMarker: Int,           // 0 none · 1 win · 2 loss
        val entryCore: List<CheckRow>, val entryRescue: List<CheckRow>, val entryGates: List<CheckRow>,
        val entryModeAny: Boolean, val entryVerdict: String, val entryFires: Boolean,
        val exitInd: List<CheckRow>, val exitHard: List<CheckRow>, val exitDist: CheckRow?,
        val exitVerdict: String, val exitFires: Boolean, val exitHasPos: Boolean
    )

    data class ReviewPayload(
        val symbol: String, val error: String? = null,
        val bars: List<BarReview> = emptyList(),
        val trades: List<Trade> = emptyList(),
        val entryTf: String = "", val exitTf: String = ""
    )

    private fun g3(v: Double): String = if (v.isNaN()) "–" else "%.4f".format(v)

    fun reviewPayload(symbol: String, raw: List<CandleData>, cfg: StrategyLabConfig,
                      sourcePeriodSec: Long = 15 * 60L, maxBars: Int = 320): ReviewPayload {
        return try {
            // Same resample clamp as the engine's dispatch(), so the chart's bars
            // and the trades drawn on them are built the identical way.
            val base = baseGranularitySec(raw)
            val entryTf = clampTf(timeframeSeconds(cfg.resample), base)
            val exitTfClamped = clampTf(timeframeSeconds(cfg.exitBars), base)
            val mtf = cfg.multiTimeframe && exitTfClamped in 1 until entryTf
            val exitTf = if (mtf) exitTfClamped else entryTf
            val complete = raw.maxOfOrNull { it.timestamp + sourcePeriodSec } ?: 0L
            val fine = resample(raw, exitTf, complete)
            val coarse = if (mtf) resample(raw, entryTf, complete) else fine
            if (fine.size < 5 || coarse.size < 5) return ReviewPayload(symbol, "not enough bars to review")
            val f = Frame(coarse, cfg); computeIndicators(f)

            // fine → coarse "as-of" index (same rule as runMtf: coarse end <= fine end).
            val eEnd = LongArray(f.n) { f.ts[it] + entryTf }
            val fineClose = DoubleArray(fine.size) { fine[it].close }
            fun coarseIdxAt(fineEnd: Long): Int {
                var lo = 0; var hi = f.n
                while (lo < hi) { val mid = (lo + hi) ushr 1; if (eEnd[mid] <= fineEnd) lo = mid + 1 else hi = mid }
                return lo - 1
            }
            val exitVelFine = pctChange(fineClose, maxOf(1, cfg.exit.velocityLookback))
            // §6.1 — when fast exit is on, the checklist must show the FINE-bar slope row
            // instead of the coarse one, so the card matches what the engine actually ran.
            val fastOn = cfg.exit.fastMacdExitEnabled && cfg.exit.slopeFlipDownEnabled && mtf
            val fe = if (fastOn) computeFastExit(fineClose, cfg) else null
            val fineIdxOfTs = HashMap<Long, Int>(fine.size)
            for (i in fine.indices) fineIdxOfTs[fine[i].timestamp] = i

            // Trades (for markers + open-position lookups), from the real engine run.
            val trades = applySlippage(
                if (mtf) runMtf(symbol, raw, cfg, entryTf, exitTf, complete) else runSingle(symbol, raw, cfg, entryTf, complete),
                cfg).trades
            val entryFineTs = trades.associateBy { it.entryTs }
            val exitFineTs = trades.filter { it.exitTs != null }.associateBy { it.exitTs!! }

            val from = maxOf(0, fine.size - maxBars)
            val out = ArrayList<BarReview>(fine.size - from)
            for (i in from until fine.size) {
                val ei = coarseIdxAt(fine[i].timestamp + exitTf)
                // find open position at this fine bar (inclusive of the exit bar)
                val pos = trades.firstOrNull { tr ->
                    tr.entryTs <= fine[i].timestamp && (tr.status == "OPEN" || (tr.exitTs ?: Long.MAX_VALUE) >= fine[i].timestamp)
                }
                val entMacd = pos?.let { p ->
                    val pei = coarseIdxAt(p.entryTs + exitTf); if (pei in 0 until f.n) f.macd[pei] else NaN
                } ?: NaN

                val enT = entryFineTs[fine[i].timestamp]
                val exT = exitFineTs[fine[i].timestamp]
                val entryMarker = when { enT == null -> 0; enT.entryReason.contains("integral") -> 2; else -> 1 }
                val exitMarker = when { exT == null -> 0; exT.pnlNotional > 0 -> 1; else -> 2 }

                val (core, rescue, gateRows, modeAny) = entryRows(f, ei, cfg)
                val coreFired = if (core.isEmpty()) false else if (modeAny) core.any { it.ok } else core.all { it.ok }
                val trigger = coreFired || rescue.any { it.ok }
                val gatesPass = gateRows.all { it.ok }
                val (enVerdict, enFires) = when {
                    ei < 1 || ei < maxOf(1, cfg.warmupBars) -> "still warming up (bar $ei of ${maxOf(1, cfg.warmupBars)} warmup)" to false
                    !trigger -> "no entry trigger (${if (modeAny) "ANY" else "ALL"} core + rescues all false)" to false
                    !gatesPass -> "trigger fired but GATE blocks: " + gateRows.filter { !it.ok }.joinToString { it.label } to false
                    else -> "ENTRY fires here" to true
                }

                val entFineMacd = pos?.let { p -> fineIdxOfTs[p.entryTs]?.let { fi -> fe?.macd?.get(fi) } } ?: NaN
                val (exInd, exHard, exDist, exVerdict, exFires) =
                    exitRows(f, i, ei, cfg, fineClose, exitVelFine, pos, entMacd, fe, entFineMacd)

                fun nn(v: Double): Double? = if (v.isNaN()) null else v
                out.add(BarReview(
                    fine[i].timestamp, fine[i].open, fine[i].high, fine[i].low, fine[i].close,
                    if (ei in 0 until f.n) nn(f.macd[ei]) else null, if (ei in 0 until f.n) nn(f.macdSignal[ei]) else null,
                    if (ei in 0 until f.n) nn(f.entrySlope[ei]) else null, if (ei in 0 until f.n) nn(f.exitSlope[ei]) else null,
                    entryMarker, exitMarker, core, rescue, gateRows, modeAny, enVerdict, enFires,
                    exInd, exHard, exDist, exVerdict, exFires, pos != null))
            }
            ReviewPayload(symbol, null, out, trades, cfg.resample, if (mtf) cfg.exitBars else cfg.resample)
        } catch (e: Exception) {
            ReviewPayload(symbol, e.message ?: "review failed")
        }
    }

    private data class EntryRows(val core: List<CheckRow>, val rescue: List<CheckRow>, val gates: List<CheckRow>, val modeAny: Boolean)

    private fun entryRows(f: Frame, i: Int, cfg: StrategyLabConfig): EntryRows {
        val e = cfg.entry; val core = ArrayList<CheckRow>(); val rescue = ArrayList<CheckRow>(); val gates = ArrayList<CheckRow>()
        if (i < 1 || i >= f.n) return EntryRows(core, rescue, gates, e.mode.equals("ANY", true))
        val p = i - 1
        if (e.slopeFlipUpEnabled) {
            if (e.slopeFlipUpPersistent) core.add(CheckRow(f.entrySlope[i] >= 0.0, "MACD slope ≥ 0 (armed, ${g3(f.entrySlope[i])})"))
            else core.add(CheckRow(f.entrySlope[p] < 0.0 && f.entrySlope[i] >= 0.0, "MACD slope flips − → + (${g3(f.entrySlope[p])}→${g3(f.entrySlope[i])})"))
        }
        if (e.slopePositiveEnabled) core.add(CheckRow(f.entrySlope[i] > 0.0, "MACD slope already positive"))
        if (e.macdCrossUpEnabled) core.add(CheckRow(f.macd[p] <= f.macdSignal[p] && f.macd[i] > f.macdSignal[i], "MACD crosses above signal"))
        if (e.macdAboveSignalEnabled) core.add(CheckRow(f.macd[i] > f.macdSignal[i], "MACD above signal"))
        if (e.macdAboveZeroEnabled) core.add(CheckRow(f.macd[i] > 0.0, "MACD above zero"))
        if (e.accelPositiveEnabled) {
            val a = f.entryAccel[i]; val sg = f.accelSigma[i]
            val floor = if (e.accelMinSigma > 0 && !sg.isNaN()) e.accelMinSigma * sg else 0.0
            core.add(CheckRow(!a.isNaN() && a > floor, "MACD acceleration > floor (${g3(a)} vs ${g3(floor)})"))
        }
        if (e.velocityEnabled) { val v = f.entryVel[i]; core.add(CheckRow(!v.isNaN() && v >= e.velocityThreshold, "velocity ≥ ${g(e.velocityThreshold)}% (${g3(v)}%)")) }
        if (e.sustainSlopeEnabled) {
            val need = maxOf(1, e.sustainSlopeBars)
            rescue.add(CheckRow(f.entrySlope[i] > 0.0 && f.runlen[i] >= need, "slope held + for ≥ $need bars (${f.runlen[i]}) — integral"))
        }
        if (e.earlyRecoveryEnabled) {
            val tr = f.trough[i]; val frac = e.earlyRecoveryPct / 100.0
            val thr = if (!tr.isNaN()) frac * tr else NaN
            var ok = !tr.isNaN() && tr < -1e-9 && f.entrySlope[p] < thr && f.entrySlope[i] >= thr
            var velTxt = ""
            if (e.earlyRecoveryConfirmVelocity) { val v = f.entryVel[i]; if (ok) ok = !v.isNaN() && v > 0.0; velTxt = " · vel ${g3(v)}" }
            val detail = if (tr.isNaN() || tr >= -1e-9) "early-recovery — slope hasn't dipped below 0 in last ${e.earlyRecoveryLookback} bars"
                         else "early-recovery: trough ${g3(tr)}, fires at slope ≥ ${g3(thr)} (${g(e.earlyRecoveryPct)}% of trough)$velTxt"
            rescue.add(CheckRow(ok, detail))
        }
        val gg = cfg.gates
        if (gg.adxEnabled) gates.add(CheckRow(!f.adxArr[i].isNaN() && f.adxArr[i] >= gg.adxMin, "ADX ≥ ${g(gg.adxMin)} (${g3(f.adxArr[i])})"))
        if (gg.smaEnabled) { val lv = f.level[i]; gates.add(CheckRow(!lv.isNaN() && f.close[i] > lv, "close above ${gg.smaKind.uppercase()}${gg.smaLength}")) }
        if (gg.emaSlopeEnabled) { val ch = f.emaSlopeChg[i]; gates.add(CheckRow(!ch.isNaN() && ch >= gg.emaSlopeMin, "EMA slope ≥ ${g(gg.emaSlopeMin)}% (${g3(ch)}%)")) }
        return EntryRows(core, rescue, gates, e.mode.equals("ANY", true))
    }

    private data class ExitRowsResult(val ind: List<CheckRow>, val hard: List<CheckRow>, val dist: CheckRow?, val verdict: String, val fires: Boolean)

    private fun exitRows(f: Frame, i: Int, ei: Int, cfg: StrategyLabConfig, fineClose: DoubleArray,
                         exitVelFine: DoubleArray, pos: Trade?, entryMacd: Double,
                         fe: FastExit? = null, entryFineMacd: Double = NaN): ExitRowsResult {
        val x = cfg.exit; val ind = ArrayList<CheckRow>(); val hard = ArrayList<CheckRow>()
        val all = x.mode.equals("ALL", true); val persist = x.slopeFlipDownPersistent
        val armed = all || persist
        // §6.1 — the fast exit runs on the FINE bar and is its own branch (never combined
        // by exit.mode); it REPLACES the coarse slope row rather than adding to it.
        val fastOn = fe != null && x.slopeFlipDownEnabled
        var fastFlip = false
        if (fastOn && i >= 1) {
            fastFlip = if (armed) fe!!.slope[i] <= 0.0 else fe!!.slope[i - 1] > 0.0 && fe.slope[i] <= 0.0
            ind.add(CheckRow(fastFlip, if (armed) "${cfg.exitBars} MACD slope < 0 (armed, fast exit, ${g3(fe.slope[i])})"
                                       else "${cfg.exitBars} MACD slope flips + → − (fast exit)"))
        }
        // rows combined by exit.mode, read off the coarse frame
        val byMode = ArrayList<CheckRow>()
        if (ei in 1 until f.n) {
            if (!fastOn && x.slopeFlipDownEnabled) {
                if (armed) byMode.add(CheckRow(f.exitSlope[ei] <= 0.0, "MACD slope < 0 (armed, ${g3(f.exitSlope[ei])})"))
                else byMode.add(CheckRow(f.exitSlope[ei - 1] > 0.0 && f.exitSlope[ei] <= 0.0, "MACD slope flips + → −"))
            }
            if (x.macdCrossDownEnabled) byMode.add(if (all) CheckRow(f.macd[ei] < f.macdSignal[ei], "MACD below signal")
                                                 else CheckRow(f.macd[ei - 1] >= f.macdSignal[ei - 1] && f.macd[ei] < f.macdSignal[ei], "MACD crosses below signal"))
            if (x.macdBelowZeroEnabled) byMode.add(CheckRow(f.macd[ei] < 0.0, "MACD below zero"))
            if (x.accelNegativeEnabled) byMode.add(CheckRow(f.exitAccel[ei] < 0.0, "MACD acceleration negative"))
        }
        if (x.velocityEnabled) { val v = exitVelFine[i]; byMode.add(CheckRow(!v.isNaN() && v <= x.velocityThreshold, "velocity ≤ ${g(x.velocityThreshold)}% (${g3(v)}%)")) }
        ind.addAll(byMode)

        var indFired = if (byMode.isEmpty()) false else if (all) byMode.all { it.ok } else byMode.any { it.ok }
        var dist: CheckRow? = null
        if (indFired && x.macdDistanceEnabled) {
            if (!entryMacd.isNaN() && ei in 0 until f.n && abs(entryMacd) > 1e-12) {
                val moved = abs(f.macd[ei] - entryMacd) / abs(entryMacd) * 100.0
                val ok = moved >= x.macdDistancePct
                dist = CheckRow(ok, "MACD moved ≥ ${g(x.macdDistancePct)}% from entry (${"%.0f".format(moved)}%)")
                if (!ok) indFired = false
            } else dist = CheckRow(false, "MACD distance ≥ ${g(x.macdDistancePct)}% (needs open trade)")
        }
        // the fast exit's distance qualifier measures the FINE MACD vs the fine MACD at entry
        var fastFires = fastFlip
        if (fastFlip && x.macdDistanceEnabled) {
            if (!entryFineMacd.isNaN() && abs(entryFineMacd) > 1e-12) {
                val moved = abs(fe!!.macd[i] - entryFineMacd) / abs(entryFineMacd) * 100.0
                val ok = moved >= x.macdDistancePct
                dist = CheckRow(ok, "${cfg.exitBars} MACD moved ≥ ${g(x.macdDistancePct)}% from entry (${"%.0f".format(moved)}%)")
                if (!ok) fastFires = false
            } else { dist = CheckRow(false, "MACD distance ≥ ${g(x.macdDistancePct)}% (needs open trade)"); fastFires = false }
        }
        indFired = indFired || fastFires

        val hasPos = pos != null
        if (hasPos) {
            val cl = fineClose[i]; val ep = pos!!.entryPrice
            if (x.stopLossEnabled) hard.add(CheckRow(cl <= ep * (1 - x.stopLossPct / 100.0), "stop loss −${g(x.stopLossPct)}% (px ${"%.2f".format(cl)} vs entry ${"%.2f".format(ep)})"))
            if (x.takeProfitEnabled) hard.add(CheckRow(cl >= ep * (1 + x.takeProfitPct / 100.0), "take profit +${g(x.takeProfitPct)}% (px ${"%.2f".format(cl)})"))
        }

        val verdict: String; val fires: Boolean
        if (!hasPos) { verdict = "no open position at this bar"; fires = false }
        else {
            val hardFired = hard.firstOrNull { it.ok }
            when {
                hardFired != null -> { verdict = "EXIT fires: ${hardFired.label}"; fires = true }
                indFired -> { verdict = "EXIT fires (indicator)"; fires = true }
                else -> { verdict = "no exit — position holds"; fires = false }
            }
        }
        return ExitRowsResult(ind, hard, dist, verdict, fires)
    }
}

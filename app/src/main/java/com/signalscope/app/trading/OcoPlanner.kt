package com.signalscope.app.trading

import com.signalscope.app.data.ConfigManager
import kotlin.math.abs
import kotlin.math.max

/**
 * Single source for fresh SL + target placement.
 *
 * UI previews, Zerodha OCO creation, and Trading 212 app-managed OCOs should
 * call this instead of carrying separate copies of the same formula.
 */
object OcoPlanner {

    /** Identifies which broker's tuning curve to use. Zerodha = looser
     *  (Target leg catches winners); T212 = tighter (SL is sole exit). */
    enum class Broker { ZERODHA, T212 }

    data class Input(
        val ltp: Double,
        val support: Double,
        val resistance: Double,
        val atr: Double,
        val avgPrice: Double,
        val projectedCeiling: Double? = null,
        val existingSl: Double? = null,
        val daysSincePeak: Long = 0L,
        val tickSize: Double = 0.05,
        val broker: Broker = Broker.ZERODHA
    )

    data class Plan(
        val ok: Boolean,
        val sl: Double,
        val target: Double,
        val slLimit: Double,
        val targetLimit: Double,
        val atr: Double,
        val atrMult: Double,
        val supportFloor: Double,
        val chandelier: Double,
        val hardFloor: Double,
        val slRaw: Double,
        val targetRaw: Double,
        val targetSource: String,
        val slClamped: Boolean,
        val targetClamped: Boolean,
        val error: String? = null
    )

    fun planSellOco(config: ConfigManager, input: Input): Plan {
        val ltp = input.ltp
        if (ltp <= 0.0) return failed("ltp must be > 0")
        if (input.tickSize <= 0.0) return failed("tickSize must be > 0")

        val atr = input.atr.takeIf { it > 0.0 } ?: max(0.01, ltp * 0.02)
        val entry = input.avgPrice.takeIf { it > 0.0 } ?: ltp
        val gain = if (entry > 0.0) (ltp - entry) / entry else 0.0
        // Per-broker overlay: T212 reads the tighter `t212*` getter set;
        // Zerodha keeps the existing shared values.
        val atrMult = when (input.broker) {
            Broker.T212    -> config.t212SlChandelierAtrMultiple(gain, input.daysSincePeak)
            Broker.ZERODHA -> config.slChandelierAtrMultiple(gain, input.daysSincePeak)
        }
        val hardStopFrac = when (input.broker) {
            Broker.T212    -> config.t212NewPositionHardStopFraction
            Broker.ZERODHA -> config.newPositionHardStopFraction
        }

        val supportFloor = if (input.support > 0.0 && input.support < ltp) input.support else 0.0
        val chandelier = ltp - atrMult * atr
        val hardFloor = entry * hardStopFrac
        val monotonicFloor = input.existingSl?.takeIf { it > 0.0 } ?: 0.0
        val slRaw = max(max(supportFloor, chandelier), max(hardFloor, monotonicFloor))
        val slClampedRaw = slRaw.coerceIn(ltp * 0.75, ltp * 0.999)
        val sl = roundToTick(slClampedRaw, input.tickSize)

        val wave = input.projectedCeiling?.takeIf { it > ltp }
        val targetBase = wave ?: input.resistance
        val targetSource = if (wave != null) "wave" else "resistance"
        val targetAtr = ltp + config.targetAtrMultiple * atr
        val targetRaw = max(targetBase, targetAtr)
        val targetClampedRaw = targetRaw.coerceIn(ltp * 1.001, ltp * 1.25)
        val target = roundToTick(targetClampedRaw, input.tickSize)

        return Plan(
            ok = true,
            sl = sl,
            target = target,
            slLimit = roundToTick(sl * (1.0 - when (input.broker) {
                Broker.T212    -> config.t212SlLimitPct
                Broker.ZERODHA -> config.slLimitPct
            }), input.tickSize),
            targetLimit = roundToTick(target * 0.99, input.tickSize),
            atr = roundToTick(atr, input.tickSize),
            atrMult = round2(atrMult),
            supportFloor = roundToTick(supportFloor, input.tickSize),
            chandelier = roundToTick(chandelier, input.tickSize),
            hardFloor = roundToTick(hardFloor, input.tickSize),
            slRaw = roundToTick(slRaw, input.tickSize),
            targetRaw = roundToTick(targetRaw, input.tickSize),
            targetSource = targetSource,
            slClamped = abs(slClampedRaw - slRaw) > 0.000001,
            targetClamped = abs(targetClampedRaw - targetRaw) > 0.000001
        )
    }

    private fun failed(error: String): Plan = Plan(
        ok = false,
        sl = 0.0,
        target = 0.0,
        slLimit = 0.0,
        targetLimit = 0.0,
        atr = 0.0,
        atrMult = 0.0,
        supportFloor = 0.0,
        chandelier = 0.0,
        hardFloor = 0.0,
        slRaw = 0.0,
        targetRaw = 0.0,
        targetSource = "",
        slClamped = false,
        targetClamped = false,
        error = error
    )

    private fun roundToTick(v: Double, tickSize: Double): Double {
        val rounded = Math.round(v / tickSize).toDouble() * tickSize
        return Math.round(rounded * 10_000.0) / 10_000.0
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}

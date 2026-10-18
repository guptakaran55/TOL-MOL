package com.signalscope.app.trading

import kotlin.math.floor

/**
 * THE SAFETY STOP UNDER A SALE THAT IS WAITING — the rule, with no I/O.
 *
 * When a strategy has decided to sell but the sale cannot happen yet (the market
 * has shut, or the patient ladder found no buyer above its floor), the position
 * is exposed until the next attempt. A stop resting at the broker covers that
 * gap: if the price falls through it — overnight news, a weak open — Trading 212
 * sells without waiting for the app, or for the phone to be awake.
 *
 * WHERE IT SITS: under the LOWER of the price the strategy sold at and the price
 * now, by half a day's typical move. Half an ATR is far enough that ordinary
 * noise does not trigger it in the minutes before the app's own sale, and near
 * enough that a real drop is cut short. Kept between 1% and 3% so a very quiet or
 * very wild stock still gets a sensible distance; 1.5% when the typical move is
 * not known.
 */
object ExitGuard {
    const val ATR_FRACTION = 0.5
    const val MIN_PCT = 1.0
    const val MAX_PCT = 3.0
    const val FALLBACK_PCT = 1.5

    /** The stop price, or null when there is no price to anchor on. */
    fun level(decisionPx: Double, priceNow: Double?, atr: Double): Double? {
        val ref = listOfNotNull(decisionPx.takeIf { it > 0.0 }, priceNow?.takeIf { it > 0.0 })
            .minOrNull() ?: return null
        val pct = if (atr > 0.0) (ATR_FRACTION * atr / ref * 100.0).coerceIn(MIN_PCT, MAX_PCT)
                  else FALLBACK_PCT
        val v = floor(ref * (1.0 - pct / 100.0) * 100.0) / 100.0
        return if (v > 0.0) v else null
    }

    /** A stop at or above the price now would sell the moment it went in — at
     *  whatever the market gives. The app's own sale is the better route then. */
    fun wouldTriggerNow(level: Double, priceNow: Double?): Boolean =
        priceNow != null && priceNow > 0.0 && priceNow <= level
}

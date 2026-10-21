package com.signalscope.app.trading

/**
 * WHERE EACH POINT OF THE MACD CHART GOES — the arithmetic half of the picture
 * attached to a notification.
 *
 * A NOTIFICATION CANNOT SHOW AN SVG, so the app draws the chart itself. It must
 * draw THE SAME chart Discovery shows, or the two screens disagree about one
 * stock, so every rule here is copied from Discovery's large MACD chart:
 *
 *   • the vertical range is SYMMETRIC AROUND ZERO, so the zero line always sits
 *     in the middle and "above or below zero" reads at a glance;
 *   • points are spaced evenly left to right, oldest first;
 *   • the colour comes from the MACD phase, in Discovery's own vocabulary.
 *
 * Kept free of Android so it can be tested on its own; the drawing lives in
 * [MacdChartBitmap].
 */
object MacdChartGeometry {

    data class Layout(val xs: FloatArray, val ys: FloatArray, val zeroY: Float)

    /** Discovery's phase colours, exactly. Anything unrecognised is slate. */
    fun phaseColor(phase: String?): Int = when (phase?.trim()?.uppercase()) {
        "BUY FLIP" -> 0xFF059669.toInt()
        "EARLY BUY" -> 0xFF34D399.toInt()
        "SELL FLIP" -> 0xFFDC2626.toInt()
        "EARLY SELL" -> 0xFFF87171.toInt()
        "BULLISH" -> 0xFF059669.toInt()
        "BEARISH" -> 0xFFDC2626.toInt()
        "NEUTRAL" -> 0xFF64748B.toInt()
        else -> 0xFF94A3B8.toInt()
    }

    /**
     * PRICE VELOCITY — Discovery's own measure, one close against the one before
     * it, as a percentage. The notification applies it to 15-minute candles; see
     * [PriceVelocity].
     *
     * Null when either price is missing: no dot is better than a coloured guess.
     */
    fun velocityPct(lastClose: Double, priceNow: Double): Double? =
        if (lastClose > 0.0 && priceNow > 0.0 && lastClose.isFinite() && priceNow.isFinite())
            (priceNow / lastClose - 1.0) * 100.0 else null

    /** Green up, red down — and grey for a move too small to have a direction,
     *  or none known, so a rounding wobble is never painted as a trend. */
    fun velocityColor(pct: Double?): Int = when {
        pct == null || kotlin.math.abs(pct) < FLAT_PCT -> 0xFF94A3B8.toInt()
        pct > 0 -> 0xFF059669.toInt()
        else -> 0xFFDC2626.toInt()
    }

    /** Below this the move is inside a price's own rounding, not a direction. */
    const val FLAT_PCT = 0.01

    /**
     * Lay [curve] out in a [w] by [h] box with [pad] on every side.
     * Null when there is nothing to draw — fewer than two finite points.
     */
    fun layout(curve: List<Double>, w: Float, h: Float, pad: Float): Layout? {
        val pts = curve.filter { it.isFinite() }
        if (pts.size < 2) return null
        val absMax = pts.maxOf { kotlin.math.abs(it) }
        val mn = -absMax
        val rng = if (absMax == 0.0) 1.0 else 2 * absMax
        val cw = w - 2 * pad
        val ch = h - 2 * pad
        val xs = FloatArray(pts.size) { i -> pad + (i.toFloat() / (pts.size - 1)) * cw }
        val ys = FloatArray(pts.size) { i -> (pad + ch - ((pts[i] - mn) / rng) * ch).toFloat() }
        val zeroY = (pad + ch - ((0.0 - mn) / rng) * ch).toFloat()
        return Layout(xs, ys, zeroY)
    }
}

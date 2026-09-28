package com.signalscope.app.trading

import com.signalscope.app.data.CandleData

/**
 * SHORT-TERM PRICE VELOCITY ON 15-MINUTE CANDLES.
 *
 * Discovery's measure — one close against the one before it, as a percentage —
 * at the resolution a notification needs: the last completed 15-minute candle
 * against the one before. It says which way the stock is moving right now,
 * beside the MACD's much slower story.
 *
 * "Completed" is judged by the DATA, not the clock (see [FeedDelay]): a German
 * candle is only whole once Yahoo's fifteen-minute delay has passed, and reading
 * one earlier would be reading a candle with a minute of prices in it.
 */
object PriceVelocity {

    data class Reading(val pct: Double, val endSec: Long)

    const val PERIOD_SEC = 15 * 60L

    fun last15m(candles: List<CandleData>, nowSec: Long, delaySec: Long): Reading? {
        val done = candles.filter {
            it.close > 0.0 && it.close.isFinite() &&
                FeedDelay.isComplete(it.timestamp, PERIOD_SEC, nowSec, 0L, delaySec)
        }
        if (done.size < 2) return null
        val prev = done[done.size - 2]
        val last = done.last()
        val pct = MacdChartGeometry.velocityPct(prev.close, last.close) ?: return null
        return Reading(pct, last.timestamp + PERIOD_SEC)
    }
}

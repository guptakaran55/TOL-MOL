package com.signalscope.app.network

import com.signalscope.app.data.CandleData
import java.time.Instant
import java.time.ZoneId

/**
 * A DAILY SERIES THAT IS MISSING ITS LATEST SESSION, put right from the same reply.
 *
 * Just after midnight UTC, Yahoo's daily series for German and US stocks did not
 * yet contain the session that had closed hours earlier — while the reply's own
 * quote block already reported that session's last trade. Everything daily that
 * the app works out at that hour (the 3b gates, the watcher ranking and its 1D
 * dots, the floor-order trend check) was therefore a day behind, and stayed so
 * all day because the series is kept for the day.
 *
 * The reply says when the stock last traded and at what price. If that day is
 * newer than the series' last bar, the day is added from those figures: close =
 * last price, with the day's high, low and volume where the reply gives them.
 * Nothing is invented — a holiday has no newer trade, so nothing is added — and
 * the latest close needs no split adjustment, since adjustment only rescales
 * the bars before a split.
 *
 * Daily series only. Intraday and monthly bars are left exactly as received.
 */
object LatestSession {

    fun fill(
        candles: List<CandleData>,
        granularity: String?,
        lastTradeSec: Long,
        lastPrice: Double,
        dayHigh: Double,
        dayLow: Double,
        dayVolume: Long,
        zone: ZoneId
    ): List<CandleData> {
        if (granularity != "1d") return candles
        if (lastTradeSec <= 0L || !(lastPrice > 0.0) || !lastPrice.isFinite()) return candles
        val last = candles.lastOrNull() ?: return candles
        val lastAt = Instant.ofEpochSecond(last.timestamp).atZone(zone)
        val tradeDay = Instant.ofEpochSecond(lastTradeSec).atZone(zone).toLocalDate()
        if (!tradeDay.isAfter(lastAt.toLocalDate())) return candles
        // Stamped like the series' own bars (the same local time of day), so the
        // new bar lands on the right calendar date for every reader.
        val ts = tradeDay.atTime(lastAt.toLocalTime()).atZone(zone).toEpochSecond()
        val hi = if (dayHigh > 0.0 && dayHigh.isFinite()) maxOf(dayHigh, lastPrice) else lastPrice
        val lo = if (dayLow > 0.0 && dayLow.isFinite()) minOf(dayLow, lastPrice) else lastPrice
        return candles + CandleData(ts, lastPrice, hi, lo, lastPrice, dayVolume.coerceAtLeast(0L))
    }

    /** The same, reading the figures from a chart reply's meta block. */
    fun fill(candles: List<CandleData>, meta: org.json.JSONObject?): List<CandleData> {
        if (meta == null) return candles
        val zone = try { ZoneId.of(meta.optString("exchangeTimezoneName", "")) } catch (e: Exception) {
            try { java.time.ZoneOffset.ofTotalSeconds(meta.optInt("gmtoffset", 0)) } catch (e2: Exception) { return candles }
        }
        return fill(candles, meta.optString("dataGranularity", ""),
            meta.optLong("regularMarketTime", 0L), meta.optDouble("regularMarketPrice", Double.NaN),
            meta.optDouble("regularMarketDayHigh", Double.NaN), meta.optDouble("regularMarketDayLow", Double.NaN),
            meta.optLong("regularMarketVolume", 0L), zone)
    }
}

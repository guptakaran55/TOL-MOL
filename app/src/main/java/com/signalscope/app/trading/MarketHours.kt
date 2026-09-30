package com.signalscope.app.trading

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * IS THIS MARKET TRADING RIGHT NOW — and are the prices we hold from today?
 *
 * The watchlist's floor alert used to answer this by timing the newest
 * completed strategy bar from its START. On a two-hour frame that passes only
 * in a short window after each bar closes, and once German bars were made to
 * wait for Yahoo's fifteen-minute delay the window shrank to about one pass in
 * eight. A market open all afternoon read as shut most of the time.
 *
 * Two questions, asked separately:
 *   • is the exchange inside its regular session by the clock? (below)
 *   • is the newest bar we hold recent? — which is what catches a public
 *     holiday, when the clock says open and nothing traded.
 */
object MarketHours {

    private data class Session(val zone: ZoneId, val open: LocalTime, val close: LocalTime)

    private fun sessionFor(base: String): Session? = when (Spaces.baseOf(base.lowercase())) {
        "de" -> Session(ZoneId.of("Europe/Berlin"), LocalTime.of(9, 0), LocalTime.of(17, 30))
        "us" -> Session(ZoneId.of("America/New_York"), LocalTime.of(9, 30), LocalTime.of(16, 0))
        "in" -> Session(IndianSession.ZONE, LocalTime.of(9, 15), LocalTime.of(15, 30))
        else -> null
    }

    /** Inside the regular session, weekdays, by the exchange's own clock. */
    fun isRegularSession(base: String, nowMs: Long): Boolean {
        val s = sessionFor(base) ?: return false
        val t = Instant.ofEpochMilli(nowMs).atZone(s.zone)
        if (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY) return false
        val lt = t.toLocalTime()
        return !lt.isBefore(s.open) && lt.isBefore(s.close)
    }

    /**
     * Is a bar that started at [barStartSec] and lasts [barSec] recent enough to
     * be today's trading? Allows one bar's length, the feed's own delay and a
     * quarter-hour of slack for a pass that runs late.
     */
    fun barIsCurrent(barStartSec: Long, barSec: Long, nowSec: Long, feedDelaySec: Long): Boolean {
        val ageFromEnd = nowSec - (barStartSec + barSec)
        return ageFromEnd >= -barSec && ageFromEnd <= barSec + feedDelaySec + 15 * 60L
    }
}

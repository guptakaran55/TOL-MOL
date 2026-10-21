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

    /** The exchange's own clock — its calendar day is the trading day. */
    fun zoneOf(base: String): ZoneId? = sessionFor(base)?.zone

    /** The regular session's opening time, on the exchange's clock. */
    fun openOf(base: String): LocalTime? = sessionFor(base)?.open

    /** The regular session's closing time, on the exchange's clock. */
    fun closeOf(base: String): LocalTime? = sessionFor(base)?.close

    /** When the session's LAST fifteen-minute bar starts. A day whose data holds
     *  that bar has finished trading; one without it may still be going. */
    fun lastBarStart(base: String): LocalTime? = sessionFor(base)?.close?.minusMinutes(15)

    /** Inside the regular session, weekdays, by the exchange's own clock. */
    fun isRegularSession(base: String, nowMs: Long): Boolean {
        val s = sessionFor(base) ?: return false
        val t = Instant.ofEpochMilli(nowMs).atZone(s.zone)
        if (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY) return false
        val lt = t.toLocalTime()
        return !lt.isBefore(s.open) && lt.isBefore(s.close)
    }

    /**
     * The next time this market's regular session opens, strictly after [nowMs] —
     * weekdays only, by the exchange's own clock. Public holidays are not known
     * here, so on one this names the open that never comes; the next pass after
     * it corrects itself. Null for a market without fixed hours (crypto).
     */
    fun nextOpenMs(base: String, nowMs: Long): Long? {
        val s = sessionFor(base) ?: return null
        var d = Instant.ofEpochMilli(nowMs).atZone(s.zone).toLocalDate()
        repeat(8) {
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) {
                val at = d.atTime(s.open).atZone(s.zone).toInstant().toEpochMilli()
                if (at > nowMs) return at
            }
            d = d.plusDays(1)
        }
        return null
    }

    /**
     * WHEN THE STOCK MARKETS ARE ALL SHUT, which opens next — "India opens Mon
     * 05:45", on the phone's clock. Null while any of them is trading. For the
     * status line, which otherwise read like a scan of markets that were closed.
     */
    fun nextOpeningText(nowMs: Long, local: ZoneId = ZoneId.systemDefault()): String? {
        val markets = listOf("de" to "Germany", "us" to "US", "in" to "India")
        if (markets.any { isRegularSession(it.first, nowMs) }) return null
        val (name, at) = markets.mapNotNull { (b, n) -> nextOpenMs(b, nowMs)?.let { n to it } }
            .minByOrNull { it.second } ?: return null
        val t = Instant.ofEpochMilli(at).atZone(local)
        val today = Instant.ofEpochMilli(nowMs).atZone(local).toLocalDate()
        val day = when (t.toLocalDate()) {
            today -> "at"
            today.plusDays(1) -> "tomorrow"
            else -> t.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
        }
        return "$name opens $day %02d:%02d".format(t.hour, t.minute)
    }

    /**
     * The exchange's own calendar date, as yyyy-mm-dd — the day a "once a day"
     * alert about this market should count by. Counting by the world clock made
     * that day start at 2 a.m. in Germany, so every daily reminder went out again
     * in the middle of the night. Null for a market without fixed hours.
     */
    fun tradingDay(base: String, nowMs: Long): String? {
        val s = sessionFor(base) ?: return null
        return Instant.ofEpochMilli(nowMs).atZone(s.zone).toLocalDate().toString()
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

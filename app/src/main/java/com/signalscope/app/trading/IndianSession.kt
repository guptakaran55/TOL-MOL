package com.signalscope.app.trading

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * WHEN THE INDIAN MARKET IS OPEN — NSE's continuous session, 09:15 to 15:30 in
 * India, Monday to Friday.
 *
 * Portfolio monitoring needs the phone awake only inside it: that is when it
 * scans the holdings, and the daily stop-loss maintenance at 15:20 falls inside
 * it too. It used to hold the phone awake for 24 hours at a time with Wi-Fi at
 * full power, so most of every week was spent keeping a phone up to wait for a
 * market that was shut.
 *
 * Exchange holidays are not known here, so on one the phone is held during what
 * would have been the session — exactly as before. Nothing is ever missed; a
 * holiday just costs what every day used to.
 */
object IndianSession {
    val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
    private val OPEN: LocalTime = LocalTime.of(9, 15)
    private val CLOSE: LocalTime = LocalTime.of(15, 30)

    private fun weekday(d: ZonedDateTime) =
        d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY

    /** Inside the session, both ends included — as the monitoring loop always judged it. */
    fun isOpen(nowMs: Long): Boolean {
        val t = Instant.ofEpochMilli(nowMs).atZone(ZONE)
        if (!weekday(t)) return false
        val lt = t.toLocalTime()
        return !lt.isBefore(OPEN) && !lt.isAfter(CLOSE.plusSeconds(59))
    }

    /** The next session open strictly after [nowMs], in epoch milliseconds. */
    fun nextOpenMs(nowMs: Long): Long {
        val now = Instant.ofEpochMilli(nowMs).atZone(ZONE)
        var d = now.toLocalDate().atTime(OPEN).atZone(ZONE)
        if (!d.isAfter(now)) d = d.plusDays(1)
        while (!weekday(d)) d = d.plusDays(1)
        return d.toInstant().toEpochMilli()
    }
}

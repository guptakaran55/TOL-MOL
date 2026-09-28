package com.signalscope.app.trading

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

// ════════════════════════════════════════════════════════════════════════════
// HOW OLD A WAITING ORDER IS, COUNTED IN DAYS THE MARKET COULD HAVE TRADED
// ════════════════════════════════════════════════════════════════════════════
// A queued buy lives for 48 hours. Counted on the wall clock, a signal from a
// Friday evening — the last German bar ends at 18:00, half an hour after Xetra
// shuts — expired on Sunday evening, before Monday's open ever gave it a chance.
// The limit was sized to "two sessions" and a weekend quietly ate both.
//
// So weekend hours do not count. Saturdays and Sundays are skipped IN THE
// EXCHANGE'S OWN TIME ZONE, because that is whose weekend matters: a Frankfurt
// Saturday starts an hour before a UTC one.
//
// Public holidays are not skipped. A single one still leaves the order a full
// session, and a holiday calendar is a list that silently goes out of date.
// ════════════════════════════════════════════════════════════════════════════

object TradingClock {

    /** The exchange's time zone for a namespace's market. */
    fun zoneFor(ns: String): ZoneId = when (Spaces.baseOf(ns.lowercase())) {
        "de" -> ZoneId.of("Europe/Berlin")
        "us" -> ZoneId.of("America/New_York")
        "in" -> ZoneId.of("Asia/Kolkata")
        else -> ZoneId.of("UTC")
    }

    /**
     * Milliseconds from [startMs] to [endMs], leaving out every Saturday and
     * Sunday in [zone]. Zero when the span is empty or backwards.
     *
     * Walked one calendar day at a time rather than computed, so a daylight-
     * saving change inside the span is counted as the 23 or 25 hours it really
     * was.
     */
    fun weekdayMillisBetween(startMs: Long, endMs: Long, zone: ZoneId): Long {
        if (endMs <= startMs) return 0L
        val end = Instant.ofEpochMilli(endMs).atZone(zone)
        var cur = Instant.ofEpochMilli(startMs).atZone(zone)
        var total = 0L
        while (cur.isBefore(end)) {
            val nextMidnight = cur.toLocalDate().plusDays(1).atStartOfDay(zone)
            val segEnd = if (nextMidnight.isBefore(end)) nextMidnight else end
            val dow = cur.dayOfWeek
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY)
                total += Duration.between(cur, segEnd).toMillis()
            cur = segEnd
        }
        return total
    }

    /** How long a queued buy may wait, in weekday time: two sessions. */
    const val QUEUE_LIFE_MS = 48L * 60 * 60 * 1000

    /** Has an order queued at [queuedAtMs] outlived [QUEUE_LIFE_MS] of weekday time? */
    fun queueExpired(queuedAtMs: Long, nowMs: Long, ns: String): Boolean =
        weekdayMillisBetween(queuedAtMs, nowMs, zoneFor(ns)) > QUEUE_LIFE_MS
}

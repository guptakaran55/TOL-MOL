package com.signalscope.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * WHEN EACH MARKET'S AUTOMATIC SCAN RUNS, and when a missed one is made up.
 * Germany at 09:17, once Xetra's delayed prices carry the open; the US a minute
 * after New York's bell (on New York's clock, so the weeks Europe and America
 * change clocks apart cannot move it off the open); India after its close.
 */
class DiscoveryAutoSchedulerTest {

    private fun at(zone: String, y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneId.of(zone)).toInstant().toEpochMilli()

    private fun berlin(d: Int, h: Int, mi: Int) = at("Europe/Berlin", 2026, 10, d, h, mi)

    // ── the regular slots ──

    @Test fun `Germany scans at 09 17 on a weekday morning`() {
        // Monday 12 Oct 2026, 08:30 in Germany.
        assertEquals(berlin(12, 9, 17), DiscoveryAutoScheduler.nextSlotMs("dax", berlin(12, 8, 30)))
    }

    @Test fun `the US scans a minute after the bell, 15 31 German time in summer`() {
        assertEquals(berlin(12, 15, 31), DiscoveryAutoScheduler.nextSlotMs("nasdaq100", berlin(12, 12, 0)))
    }

    @Test fun `the US slot follows New York in the week the clocks disagree`() {
        // Europe went back an hour on 25 Oct, New York not until 1 Nov: the open
        // is 14:30 in Germany that week, so the scan is at 14:31, not 15:31.
        assertEquals(at("Europe/Berlin", 2026, 10, 26, 14, 31),
            DiscoveryAutoScheduler.nextSlotMs("nasdaq100", at("Europe/Berlin", 2026, 10, 26, 9, 0)))
    }

    @Test fun `after Friday's slot the next one is Monday`() {
        assertEquals(berlin(12, 9, 17), DiscoveryAutoScheduler.nextSlotMs("dax", berlin(9, 11, 0)))
    }

    @Test fun `India still scans after its own close`() {
        assertEquals(at("Asia/Kolkata", 2026, 10, 12, 16, 15),
            DiscoveryAutoScheduler.nextSlotMs("nifty500", at("Asia/Kolkata", 2026, 10, 12, 9, 0)))
    }

    // ── catching up a missed one ──

    @Test fun `a slot missed while the phone was off is owed straight away`() {
        val now = berlin(12, 11, 5)
        assertEquals(now, DiscoveryAutoScheduler.owedAt("dax", now,
            lastCompleteAt = berlin(9, 13, 0), lastTry = 0L, tries = 0))
    }

    @Test fun `a scan you ran by hand after the slot counts`() {
        assertNull(DiscoveryAutoScheduler.owedAt("dax", berlin(12, 12, 10),
            lastCompleteAt = berlin(12, 12, 0), lastTry = 0L, tries = 0))
    }

    @Test fun `a scan from before the slot does not`() {
        val now = berlin(12, 9, 40)
        assertEquals(now, DiscoveryAutoScheduler.owedAt("dax", now,
            lastCompleteAt = berlin(12, 9, 10), lastTry = 0L, tries = 0))
    }

    @Test fun `a failed attempt is retried half an hour later`() {
        assertEquals(berlin(12, 9, 47), DiscoveryAutoScheduler.owedAt("dax", berlin(12, 9, 27),
            lastCompleteAt = berlin(9, 13, 0), lastTry = berlin(12, 9, 17), tries = 1))
    }

    @Test fun `an alarm landing a minute early on its retry still counts as due`() {
        val now = berlin(12, 9, 46)
        assertEquals(now, DiscoveryAutoScheduler.owedAt("dax", now,
            lastCompleteAt = berlin(9, 13, 0), lastTry = berlin(12, 9, 17), tries = 1, firing = true))
    }

    @Test fun `three attempts and the day is left alone`() {
        assertNull(DiscoveryAutoScheduler.owedAt("dax", berlin(12, 11, 30),
            lastCompleteAt = berlin(9, 13, 0), lastTry = berlin(12, 11, 0), tries = 3))
    }

    @Test fun `yesterday's attempts do not count against today`() {
        val now = berlin(12, 10, 5)
        assertEquals(now, DiscoveryAutoScheduler.owedAt("dax", now,
            lastCompleteAt = berlin(9, 13, 0), lastTry = berlin(9, 11, 0), tries = 3))
    }

    @Test fun `nothing is owed before the slot, after the close, or at the weekend`() {
        val old = berlin(1, 13, 0)
        assertNull(DiscoveryAutoScheduler.owedAt("dax", berlin(12, 9, 10), old, 0L, 0))
        assertNull(DiscoveryAutoScheduler.owedAt("dax", berlin(12, 17, 45), old, 0L, 0))
        assertNull(DiscoveryAutoScheduler.owedAt("dax", berlin(10, 12, 0), old, 0L, 0))   // Saturday
    }

    @Test fun `a retry that would land after the close is dropped`() {
        assertNull(DiscoveryAutoScheduler.owedAt("dax", berlin(12, 17, 10),
            lastCompleteAt = berlin(9, 13, 0), lastTry = berlin(12, 17, 5), tries = 1))
    }
}

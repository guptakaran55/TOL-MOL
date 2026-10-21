package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * PORTFOLIO MONITORING HOLDS THE PHONE AWAKE ONLY WHILE NSE IS OPEN.
 *
 * It used to hold it for 24 hours at a time. These pin down the window it now
 * holds for, and the alarm time that wakes it for the next session.
 */
class IndianSessionTest {

    private fun ist(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(IndianSession.ZONE).toInstant().toEpochMilli()

    // 28 September 2026 is a Monday.

    @Test fun `open through the session, both ends included`() {
        assertFalse(IndianSession.isOpen(ist(2026, 9, 28, 9, 14, 59)))
        assertTrue(IndianSession.isOpen(ist(2026, 9, 28, 9, 15)))
        assertTrue("the 15:20 stop maintenance is inside it", IndianSession.isOpen(ist(2026, 9, 28, 15, 20)))
        assertTrue(IndianSession.isOpen(ist(2026, 9, 28, 15, 30, 59)))
        assertFalse(IndianSession.isOpen(ist(2026, 9, 28, 15, 31)))
    }

    @Test fun `shut all weekend`() {
        assertFalse(IndianSession.isOpen(ist(2026, 10, 3, 11, 0)))   // Saturday
        assertFalse(IndianSession.isOpen(ist(2026, 10, 4, 11, 0)))   // Sunday
    }

    @Test fun `before the bell, the alarm is for this morning`() {
        assertEquals(ist(2026, 9, 28, 9, 15), IndianSession.nextOpenMs(ist(2026, 9, 28, 6, 0)))
    }

    @Test fun `after the close, it is tomorrow's open`() {
        assertEquals(ist(2026, 9, 29, 9, 15), IndianSession.nextOpenMs(ist(2026, 9, 28, 16, 0)))
    }

    @Test fun `friday evening wakes on monday, not saturday`() {
        assertEquals(ist(2026, 10, 5, 9, 15), IndianSession.nextOpenMs(ist(2026, 10, 2, 16, 0)))
        assertEquals(ist(2026, 10, 5, 9, 15), IndianSession.nextOpenMs(ist(2026, 10, 3, 12, 0)))
    }

    @Test fun `exactly at the open, the next one is tomorrow`() {
        // Strictly after: an alarm set for "now" would fire in a loop.
        assertEquals(ist(2026, 9, 29, 9, 15), IndianSession.nextOpenMs(ist(2026, 9, 28, 9, 15)))
    }

    @Test fun `european evening maps to the right indian morning`() {
        // 22:00 in Germany on Monday is 01:30 on Tuesday in India.
        val berlin22 = LocalDateTime.of(2026, 9, 28, 22, 0)
            .atZone(java.time.ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli()
        assertFalse(IndianSession.isOpen(berlin22))
        assertEquals(ist(2026, 9, 29, 9, 15), IndianSession.nextOpenMs(berlin22))
    }
}

package com.signalscope.app.service

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * THE 15-MINUTE PASS IS NEVER SKIPPED by the service starting just before it.
 * Passes run at :01:30, :16:30, :31:30 and :46:30. Anything that started the
 * service in the minute before one — an automatic scan firing at 12:45, a scan
 * or order you started — used to push the alarm to the slot after, and that
 * pass never ran.
 */
class PassSlotTest {

    private fun berlin(h: Int, m: Int, s: Int) =
        ZonedDateTime.of(2026, 10, 12, h, m, s, 0, ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli()

    @Test fun `a start forty-nine seconds before a pass keeps that pass`() {
        // India's automatic scan fires at 12:45:41; the pass is due at 12:46:30.
        assertEquals(berlin(12, 46, 30), StrategyLabScheduler.nextQuarterWithGraceMs(berlin(12, 45, 41)))
    }

    @Test fun `any start just before a pass keeps that pass`() {
        assertEquals(berlin(13, 1, 30), StrategyLabScheduler.nextQuarterWithGraceMs(berlin(13, 0, 46)))
    }

    @Test fun `at its own firing the pass moves on to the next slot`() {
        assertEquals(berlin(13, 16, 30), StrategyLabScheduler.nextQuarterWithGraceMs(berlin(13, 1, 30)))
        assertEquals(berlin(13, 16, 30), StrategyLabScheduler.nextQuarterWithGraceMs(berlin(13, 5, 0)))
    }

    @Test fun `well ahead of a pass it is simply that pass`() {
        assertEquals(berlin(13, 1, 30), StrategyLabScheduler.nextQuarterWithGraceMs(berlin(13, 0, 5)))
    }
}

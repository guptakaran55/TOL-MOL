package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * A BAR IS FINISHED WHEN ITS DATA HAS ARRIVED, NOT WHEN THE CLOCK SAYS SO.
 *
 * Yahoo's Xetra prices run fifteen minutes behind. Judging a German bar one
 * minute after it ended judged a bar missing its last quarter hour — and the
 * complete bar, judged a pass later, could carry a signal the partial one did
 * not (Aixtron, 28 September) or lack one it did.
 */
class FeedDelayTest {

    private val grace = 60L
    // Times in UTC; Xetra in late September is UTC+2, so 08:00Z is 10:00 local.
    private fun t(h: Int, m: Int, s: Int = 0) =
        LocalDateTime.of(2026, 9, 28, h, m, s).toEpochSecond(ZoneOffset.UTC)
    private val hour = 3600L

    @Test fun `only Germany waits for its feed`() {
        assertEquals(15 * 60L, FeedDelay.secFor("de"))
        assertEquals(15 * 60L, FeedDelay.secFor("de__buyx6g"))
        assertEquals(0L, FeedDelay.secFor("us__abc"))
        assertEquals(0L, FeedDelay.secFor("in"))
        assertEquals(0L, FeedDelay.secFor("binance"))
    }

    @Test fun `the German 09-00 hour is not judged at 10-02 any more`() {
        // THE AIXTRON MORNING. The pass at 10:01:30 local used to take this bar as
        // closed while the feed still ended around 09:47.
        val bar = t(7, 0)                                    // 09:00 local
        val d = FeedDelay.secFor("de")
        assertFalse(FeedDelay.isComplete(bar, hour, t(8, 1, 30), grace, d))
        assertFalse("one second short", FeedDelay.isComplete(bar, hour, t(8, 15, 59), grace, d))
        assertTrue(FeedDelay.isComplete(bar, hour, t(8, 16, 0), grace, d))
    }

    @Test fun `the quarter-hourly pass meets the German bar one pass later, not two`() {
        // Passes run at :01:30, :16:30, :31:30, :46:30. The first one allowed to
        // judge the bar that ended at 10:00 local is 10:16:30 — fifteen minutes
        // later than before, not thirty.
        val bar = t(7, 0)
        val d = FeedDelay.secFor("de")
        val passes = listOf(t(8, 1, 30), t(8, 16, 30), t(8, 31, 30))
        assertEquals(t(8, 16, 30), passes.first { FeedDelay.isComplete(bar, hour, it, grace, d) })
    }

    @Test fun `a US bar is judged exactly as before`() {
        val bar = t(14, 0)
        val d = FeedDelay.secFor("us")
        assertTrue(FeedDelay.isComplete(bar, hour, t(15, 1, 0), grace, d))
        assertFalse(FeedDelay.isComplete(bar, hour, t(15, 0, 59), grace, d))
    }

    @Test fun `the first German 15-minute candle is not read at the open wake`() {
        // Retries wake sixteen minutes after the bell. By the clock the 09:00–09:15
        // candle had closed; by the feed it held about a minute of prices, and was
        // being read as the morning's direction.
        val first = t(7, 0)                                   // 09:00 local
        val wake = t(7, 16)                                   // 09:16 local
        assertFalse(FeedDelay.isComplete(first, 15 * 60L, wake, 0L, FeedDelay.XETRA_SEC))
        assertTrue(FeedDelay.isComplete(first, 15 * 60L, t(7, 30), 0L, FeedDelay.XETRA_SEC))
    }
}

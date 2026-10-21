package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * THE MARKETS STRIP on the widget: which session is "today", what it is
 * measured against, and where the little line is drawn.
 */
class IndexStripTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val dax = IndexStrip.INDICES.first { it.key == "dax" }

    private fun bar(day: Int, hour: Int, close: Double, zone: ZoneId = berlin) = CandleData(
        LocalDateTime.of(2026, 10, day, hour, 0).atZone(zone).toEpochSecond(), close, close, close, close, 0)

    @Test fun `the day's change is from the previous session's last close`() {
        val bars = listOf(bar(15, 10, 100.0), bar(15, 17, 102.0),          // Thu: closes at 102
            bar(16, 9, 101.0), bar(16, 12, 103.0), bar(16, 17, 104.04))     // Fri
        val r = IndexStrip.reading(dax, bars, berlin, 0L)!!
        assertEquals("2026-10-16", r.day)
        assertEquals(102.0, r.prevClose, 1e-9)
        assertEquals(104.04, r.last, 1e-9)
        assertEquals(2.0, r.pct, 1e-9)
        assertEquals(listOf(101.0, 103.0, 104.04), r.closes)
    }

    @Test fun `sessions are split on the exchange's own clock`() {
        // 23:00 in Berlin is still Thursday there, though it is already Friday in India.
        val kolkata = ZoneId.of("Asia/Kolkata")
        val bars = listOf(bar(15, 10, 100.0), bar(15, 23, 98.0), bar(16, 10, 99.0))
        val de = IndexStrip.reading(dax, bars, berlin, 0L)!!
        assertEquals(98.0, de.prevClose, 1e-9)
        val asIndia = IndexStrip.reading(dax, bars, kolkata, 0L)!!
        assertEquals(100.0, asIndia.prevClose, 1e-9)     // the 23:30 bar belongs to Friday there
    }

    @Test fun `one session alone has nothing to be measured against`() {
        assertNull(IndexStrip.reading(dax, listOf(bar(16, 9, 1.0), bar(16, 10, 2.0)), berlin, 0L))
        assertNull(IndexStrip.reading(dax, emptyList(), berlin, 0L))
    }

    @Test fun `the line fills the box and the previous close is always inside it`() {
        // A day that stayed well above its previous close: the baseline sits at the bottom.
        val (pts, baseY) = IndexStrip.geometry(listOf(105.0, 110.0, 108.0), 100.0, 100f, 20f, 1f)
        assertEquals(3, pts.size)
        assertEquals(1f, pts.first().first, 1e-4f)
        assertEquals(99f, pts.last().first, 1e-4f)
        assertEquals(1f, pts[1].second, 1e-4f)          // the high at the top
        assertEquals(19f, baseY, 1e-4f)                  // the previous close at the bottom
        assertTrue(pts.all { it.second in 1f..19f })
    }

    @Test fun `a flat day does not divide by zero`() {
        val (pts, baseY) = IndexStrip.geometry(listOf(100.0, 100.0), 100.0, 50f, 10f, 1f)
        assertNotNull(pts)
        assertTrue(baseY.isFinite())
        assertTrue(pts.all { it.second.isFinite() })
    }
}

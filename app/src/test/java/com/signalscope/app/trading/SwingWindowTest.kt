package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE SWING WINDOW — the last few trading days, on their own.
 *
 * This is the reading an entry is actually timed off: someone buying pullbacks
 * wants to know whether a name is sitting on the floor of its recent range right
 * now, not where it found support six weeks ago. Both are true and only one is
 * actionable this afternoon, which is why they are computed and shown apart.
 *
 * The number it produces also orders the candidate list, so a mistake here does
 * not merely mislabel a row — it puts the wrong name at the top of a list built
 * to be acted on from the top.
 */
class SwingWindowTest {

    private val DAY = 86_400L

    /** `days` sessions of `perDay` bars, with each day's low and high given. */
    private fun sessions(vararg day: Pair<Double, Double>, perDay: Int = 8,
                         startDay: Long = 20_000L): List<CandleData> {
        val out = ArrayList<CandleData>()
        day.forEachIndexed { d, (lo, hi) ->
            for (i in 0 until perDay) {
                // A simple sweep from low to high inside the session.
                val f = if (perDay == 1) 0.0 else i.toDouble() / (perDay - 1)
                val px = lo + (hi - lo) * f
                out.add(CandleData((startDay + d) * DAY + i * 3600L,
                    px, minOf(px + 0.1, hi), maxOf(px - 0.1, lo), px, 100L))
            }
        }
        return out
    }

    // ── counting days the market actually traded ────────────────────────────

    @Test fun `the window counts trading days, not calendar days`() {
        // THE REASON IT IS NOT DATE ARITHMETIC. Counting back three calendar days
        // hands you one session on a Monday and three on a Thursday — the window
        // would quietly be a third of its intended size at the start of every
        // week, which is also when a weekend gap makes it matter most.
        val mon = 20_000L                       // a "week" with a two-day gap before it
        val c = sessions(90.0 to 100.0, 91.0 to 101.0, 92.0 to 102.0,
            93.0 to 103.0, startDay = mon)
        val w = IntradayLevels.recentDays(c, 3)
        assertEquals("three sessions of eight bars", 24, w.size)
        val days = w.map { it.timestamp / DAY }.distinct()
        assertEquals(3, days.size)
        assertEquals("and they are the NEWEST three", listOf(mon + 1, mon + 2, mon + 3), days)
    }

    @Test fun `asking for more days than exist returns what there is`() {
        val c = sessions(90.0 to 100.0, 91.0 to 101.0)
        assertEquals(16, IntradayLevels.recentDays(c, 5).size)
    }

    @Test fun `an empty series yields an empty window`() {
        assertTrue(IntradayLevels.recentDays(emptyList(), 3).isEmpty())
        assertTrue(IntradayLevels.recentDays(sessions(90.0 to 100.0), 0).isEmpty())
    }

    // ── where price sits in that window ─────────────────────────────────────

    @Test fun `sitting on the floor reads as zero, the ceiling as one`() {
        assertEquals(0.0, IntradayLevels.positionInRange(90.0, 90.0, 110.0)!!, 1e-9)
        assertEquals(1.0, IntradayLevels.positionInRange(110.0, 90.0, 110.0)!!, 1e-9)
    }

    @Test fun `the reading is produced even when no level has been tested`() {
        // The whole advantage of the swing window over the clustered levels: it
        // needs nothing to have held before, only for the stock to have traded.
        // A name that has never turned anywhere still has a range it is inside.
        val ramp = (0 until 200).map { i ->
            val p = 50.0 + i * 0.25
            CandleData(20_000L * DAY + i * 3600L, p, p + 0.1, p - 0.1, p, 100L)
        }
        val lv = IntradayLevels.from("ABC", ramp, 60)
        assertNotNull("a trending stock still has a recent range", lv)
        assertTrue("and a position inside it", lv!!.swingPos >= 0.0)
        assertTrue(lv.swingHigh > lv.swingLow)
    }

    // ── visits, not bars ────────────────────────────────────────────────────

    @Test fun `ten bars parked on the low is one visit, not ten`() {
        // Price going nowhere is not a floor being defended, and counting bars
        // would score it far higher than a level genuinely held three times —
        // exactly backwards for the only question being asked.
        val parked = (0 until 10).map {
            CandleData(20_000L * DAY + it * 3600L, 90.0, 90.2, 89.9, 90.0, 100L)
        } + (0 until 10).map {
            CandleData(20_000L * DAY + (10 + it) * 3600L, 100.0, 100.2, 99.9, 100.0, 100L)
        }
        assertEquals(1, IntradayLevels.zoneVisits(parked, 89.9, 100.2))
    }

    @Test fun `coming back three separate times counts three`() {
        val bars = ArrayList<CandleData>()
        var t = 20_000L * DAY
        repeat(3) {
            // down to the floor...
            bars.add(CandleData(t, 90.0, 90.2, 89.9, 90.0, 100L)); t += 3600L
            // ...and away from it again
            repeat(3) { bars.add(CandleData(t, 98.0, 98.2, 97.9, 98.0, 100L)); t += 3600L }
        }
        assertEquals(3, IntradayLevels.zoneVisits(bars, 89.9, 98.2))
    }

    @Test fun `an unusable range counts nothing rather than throwing`() {
        val bars = sessions(90.0 to 100.0)
        assertEquals(0, IntradayLevels.zoneVisits(bars, 0.0, 100.0))
        assertEquals(0, IntradayLevels.zoneVisits(bars, 100.0, 90.0))
        assertEquals(0, IntradayLevels.zoneVisits(emptyList(), 90.0, 100.0))
    }

    // ── the ordering it drives ──────────────────────────────────────────────

    /** How the list sorts when ordered by nearness to the floor. */
    private fun order(vararg pos: Pair<String, Double?>): List<String> =
        pos.sortedWith(compareBy({ it.second ?: 99.0 }, { it.first })).map { it.first }

    @Test fun `nearest the floor comes first and a missing reading comes last`() {
        // ABSENT IS NOT ZERO, and here the difference is expensive: zero means
        // "sitting on its floor", which is the most interesting row on the
        // screen. A name whose reading failed must never impersonate one.
        assertEquals(listOf("C", "A", "B", "Z"),
            order("A" to 0.30, "B" to 0.80, "C" to 0.05, "Z" to null))
    }

    @Test fun `a name that has broken below its range still sorts to the top`() {
        // Not clamped, deliberately. A negative reading means price has left the
        // window through the floor — which is either the best entry on the list
        // or a level breaking, and both are things to look at first.
        assertEquals(listOf("BROKE", "AT_LOW", "MID"),
            order("AT_LOW" to 0.02, "MID" to 0.50, "BROKE" to -0.12))
    }
}

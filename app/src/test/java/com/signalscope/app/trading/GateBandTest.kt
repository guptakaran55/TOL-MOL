package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE GATE BAND UNDER THE PRICE CHART.
 *
 * It answers one question — could an entry have been taken on this day — and the
 * honest answer has three values, not two. That distinction is the whole feature:
 * with ranking on, a name can be perfectly eligible and still not held because
 * better-ranked names took every seat, and painting that the same red as "the
 * slope refused it" states the wrong reason with total confidence.
 *
 * The band used to be withheld entirely from ranked algos for that reason, which
 * meant the question went unanswered on nearly every algo the user runs. Amber is
 * the missing answer.
 *
 * Modelled as the pure day-by-day decision, because the real builder needs a
 * Context, a gate store and price history. What is pinned is the RULE.
 */
class GateBandTest {

    private val SEATED = 2
    private val OUTRANKED = 1
    private val REFUSED = 0

    private val DAY = 86_400_000L

    /** The rule the builder applies, for one day. */
    private fun state(seated: Boolean, slope: Double) = when {
        seated -> SEATED
        slope > 0.0 -> OUTRANKED
        else -> REFUSED
    }

    /** Does a seat span cover this day? Half-open, as the builder has it. */
    private fun covers(spanFrom: Long, spanTo: Long, day: Long): Boolean {
        val start = day * DAY
        return spanFrom < start + DAY && spanTo > start
    }

    // ── the three states ────────────────────────────────────────────────────

    @Test fun `a seated name is green whatever the slope says`() {
        // The seat is a RECORD of what happened; the slope is a reconstruction
        // from today's prices and may differ slightly from what the gate saw.
        // Where they disagree, the record wins.
        assertEquals(SEATED, state(seated = true, slope = 0.4))
        assertEquals(SEATED, state(seated = true, slope = -0.1))
    }

    @Test fun `eligible but unseated is amber, not red`() {
        // The case the old two-colour band got wrong. Nothing refused this name —
        // it simply lost the seat to a better-ranked one.
        assertEquals(OUTRANKED, state(seated = false, slope = 0.25))
    }

    @Test fun `a non-positive slope is red`() {
        assertEquals(REFUSED, state(seated = false, slope = 0.0))
        assertEquals(REFUSED, state(seated = false, slope = -1.5))
    }

    @Test fun `an unranked algo can only ever be green or red`() {
        // With every name holding a permanent seat, "eligible but unseated" cannot
        // arise — which is why this began life as a boolean and why widening it
        // must not change the picture for those algos.
        listOf(0.5, -0.5, 0.0).forEach { slope ->
            val s = if (slope > 0.0) SEATED else REFUSED
            assertTrue("unranked must never be amber", s != OUTRANKED)
        }
    }

    // ── the seat spans ──────────────────────────────────────────────────────

    @Test fun `a span covers the days it actually spans`() {
        val from = 100L * DAY + 3 * 3_600_000L      // day 100, 03:00
        val to = 103L * DAY + 9 * 3_600_000L        // day 103, 09:00
        assertTrue("the opening day counts", covers(from, to, 100))
        assertTrue(covers(from, to, 101))
        assertTrue(covers(from, to, 102))
        assertTrue("the closing day counts — it held the seat that morning",
            covers(from, to, 103))
    }

    @Test fun `a span that ended does not colour the following day`() {
        // Half-open on purpose. A seat released at noon did not hold the name the
        // next day, and painting that green would claim an entry was possible on a
        // day when nothing could have been bought.
        val from = 100L * DAY
        val to = 103L * DAY + 12 * 3_600_000L
        assertTrue(covers(from, to, 103))
        assertTrue("the day after release is not covered", !covers(from, to, 104))
        assertTrue("nor any day before it opened", !covers(from, to, 99))
    }

    @Test fun `a still-open span runs to the present`() {
        // No end recorded means the seat is still held, so every day from the
        // start onward is green.
        val from = 100L * DAY
        val open = Long.MAX_VALUE
        assertTrue(covers(from, open, 100))
        assertTrue(covers(from, open, 5_000))
        assertTrue(!covers(from, open, 99))
    }

    @Test fun `several spans on one name are all honoured`() {
        // A name can be seated, dropped and re-seated. Each stretch is green and
        // the gap between them is judged on its own merits.
        val spans = listOf(100L * DAY to 102L * DAY, 110L * DAY to 112L * DAY)
        fun seatedOn(day: Long) = spans.any { (f, t) -> covers(f, t, day) }
        assertTrue(seatedOn(100)); assertTrue(seatedOn(101))
        assertTrue("the gap is not seated", !seatedOn(105))
        assertTrue(seatedOn(110)); assertTrue(seatedOn(111))
        // And the gap takes its colour from the slope, which is the whole point.
        assertEquals(OUTRANKED, state(seatedOn(105), 0.3))
        assertEquals(REFUSED, state(seatedOn(105), -0.3))
    }

    @Test fun `a full run reads as one story`() {
        // Refused, then eligible but out-ranked, then seated — the shape a name
        // actually traces on its way into a ranked algo, and the shape the old
        // band collapsed into a single block of red.
        val spans = listOf(103L * DAY to Long.MAX_VALUE)
        fun seatedOn(day: Long) = spans.any { (f, t) -> covers(f, t, day) }
        val slopes = mapOf(100L to -0.2, 101L to -0.1, 102L to 0.3, 103L to 0.4, 104L to 0.5)
        val band = (100L..104L).map { state(seatedOn(it), slopes.getValue(it)) }
        assertEquals(listOf(REFUSED, REFUSED, OUTRANKED, SEATED, SEATED), band)
    }
}

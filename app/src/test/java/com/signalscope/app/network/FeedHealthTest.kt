package com.signalscope.app.network

import com.signalscope.app.data.CandleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A FEED THAT STOPPED, TOLD APART FROM ONE THAT IS MERELY QUIET.
 *
 * The figures are Pfisterer's, from the week its Yahoo line froze: last trade
 * 7 Oct 2026 15:35 UTC, while Xetra's session on 15 Oct ran 07:00–15:30 UTC and
 * the share traded all day under its new symbol.
 */
class FeedHealthTest {

    private val H = 3600L
    private val frozeAt = 1_791_387_328L           // 2026-10-07 15:35:28 UTC
    private val open15 = 1_792_047_600L            // 2026-10-15 07:00 UTC
    private val close15 = open15 + 8 * H + 30 * 60 // 15:30 UTC

    @Test fun `a week-old last trade two hours into an open session is a stopped feed`() {
        assertTrue(FeedHealth.stopped(frozeAt, open15, close15, open15 + 2 * H))
        assertTrue(FeedHealth.stopped(frozeAt, open15, close15, close15 - 60))
    }

    @Test fun `the first two hours of a session are not enough to judge`() {
        // A liquid name nearly always prints in the first minutes, but a thin one
        // can go a while; waiting costs nothing, since its price is not moving.
        assertFalse(FeedHealth.stopped(frozeAt, open15, close15, open15 + 2 * H - 1))
        assertFalse(FeedHealth.stopped(frozeAt, open15, close15, open15 + 60))
    }

    @Test fun `outside a session nothing is judged`() {
        // Last traded yesterday, and it is the evening or the early morning:
        // that is exactly what a healthy feed looks like.
        assertFalse(FeedHealth.stopped(frozeAt, open15, close15, close15))
        assertFalse(FeedHealth.stopped(frozeAt, open15, close15, open15 - H))
    }

    @Test fun `a name that has traded in today's session is live`() {
        assertFalse(FeedHealth.stopped(open15 + 5 * H, open15, close15, open15 + 6 * H))
        assertFalse(FeedHealth.stopped(open15, open15, close15, open15 + 3 * H))
    }

    @Test fun `missing facts never produce a verdict`() {
        assertFalse(FeedHealth.stopped(0L, open15, close15, open15 + 3 * H))
        assertFalse(FeedHealth.stopped(frozeAt, 0L, close15, open15 + 3 * H))
        assertFalse(FeedHealth.stopped(frozeAt, open15, 0L, open15 + 3 * H))
    }

    @Test fun `a verdict outlasts the session and is lifted only by a later trade`() {
        // Judged in session...
        val v = FeedHealth.next(0L, frozeAt, open15, close15, open15 + 3 * H)
        assertEquals(frozeAt, v)
        // ...still standing after the close, when it could not be judged afresh —
        // an evening order on a frozen name is no safer for the market being shut.
        assertEquals(frozeAt, FeedHealth.next(v, frozeAt, open15, close15, close15 + H))
        // ...and lifted the moment a reply shows any newer trade.
        assertEquals(0L, FeedHealth.next(v, open15 + 4 * H, open15, close15, close15 + H))
    }

    @Test fun `a healthy name never acquires a verdict`() {
        assertEquals(0L, FeedHealth.next(0L, open15 + 4 * H, open15, close15, open15 + 5 * H))
        assertEquals(0L, FeedHealth.next(0L, frozeAt, open15, close15, close15 + H))
    }

    // ── joining the frozen line to the live one ─────────────────────────────

    private fun bar(ts: Long, close: Double) = CandleData(ts, close, close, close, close, 1L)

    @Test fun `the live line wins where both have a bar, the frozen one fills the rest`() {
        val d = 86_400L
        val d9 = 1_791_529_200L                    // 2026-10-09 07:00 UTC
        // Old line: real daily closes through 14 Oct (Yahoo kept filling them).
        val older = listOf(bar(d9 - d, 66.6), bar(d9, 64.51), bar(d9 + 3 * d, 64.43),
            bar(d9 + 4 * d, 63.28), bar(d9 + 5 * d, 62.25))
        // New line: 9 Oct and today only — its 12–14 Oct bars came back empty.
        val newer = listOf(bar(d9, 64.51), bar(d9 + 6 * d, 59.60))
        val joined = YahooFinanceClient.spliceLines(older, newer)
        assertEquals(listOf(d9 - d, d9, d9 + 3 * d, d9 + 4 * d, d9 + 5 * d, d9 + 6 * d),
            joined.map { it.timestamp })
        assertEquals(59.60, joined.last().close, 1e-9)
    }

    @Test fun `either line alone passes through untouched`() {
        val a = listOf(bar(1L, 1.0), bar(2L, 2.0))
        assertEquals(a, YahooFinanceClient.spliceLines(a, emptyList()))
        assertEquals(a, YahooFinanceClient.spliceLines(emptyList(), a))
    }
}

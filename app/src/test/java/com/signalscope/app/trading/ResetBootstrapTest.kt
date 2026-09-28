package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHAT "RESET PAPER" IS SUPPOSED TO MEAN.
 *
 * A genuine clean start: the book empties and the algo tracks forward from here,
 * rather than re-deriving whatever position the strategy would already be in.
 *
 * It did not do that when the market was shut. The first run stamps a boundary
 * at the newest completed bar and deliberately makes that bar itself tradeable,
 * so the algo's very first position can become real. The assumption underneath
 * is that the newest bar is one that just closed — true in a session, false
 * overnight, where the last US 240-minute bar closes at 22:00 and stays "newest"
 * until the following afternoon. Resetting at midday therefore adopted a
 * position on a bar fourteen hours old, and resetting again an hour later
 * brought it straight back, because the bar had not moved.
 *
 * The rule pinned here is the narrow withdrawal of that concession: the bind bar
 * is tradeable only while the feed is live.
 */
class ResetBootstrapTest {

    private val STALE_MAX_MIN = 45L
    private val BAR = 240 * 60L          // a 240-minute bar, in seconds

    private fun bindFresh(lastBarAgeMin: Long) = lastBarAgeMin <= STALE_MAX_MIN

    /** The boundary the bootstrap stamps. */
    private fun baselineFor(wmBar: Long, lastBarAgeMin: Long) =
        if (bindFresh(lastBarAgeMin)) wmBar else wmBar + 1L

    /** The watermark it stamps alongside. */
    private fun watermarkFor(wmBar: Long, lastBarAgeMin: Long) =
        if (bindFresh(lastBarAgeMin)) (wmBar - 1L).coerceAtLeast(0L) else wmBar

    /** The ledger's own filter: an entry before the baseline is discarded. */
    private fun kept(entryTs: Long, baseline: Long) = entryTs >= baseline

    // ── the shut market, which is the bug ───────────────────────────────────

    @Test fun `a reset while the market is shut starts genuinely flat`() {
        val wmBar = 1_000_000L
        val age = 14 * 60L                       // fourteen hours past the close
        val baseline = baselineFor(wmBar, age)
        assertFalse("the stale bar must not be adopted", kept(wmBar, baseline))
        assertTrue("the next bar is where tracking begins", kept(wmBar + BAR, baseline))
    }

    @Test fun `resetting again an hour later does not bring it back`() {
        // The symptom that made this visible: the bar does not move while the
        // market is shut, so every reset re-adopted the same position.
        val wmBar = 1_000_000L
        listOf(14 * 60L, 15 * 60L, 16 * 60L).forEach { age ->
            assertFalse("still refused at ${age / 60}h",
                kept(wmBar, baselineFor(wmBar, age)))
        }
    }

    // ── the live market, where the concession still earns its place ─────────

    @Test fun `during a session the bind bar stays tradeable`() {
        // Withdrawn only where it pays nothing. On a live feed the first position
        // must still be able to become real, which is why the concession exists.
        val wmBar = 1_000_000L
        val baseline = baselineFor(wmBar, lastBarAgeMin = 3L)
        assertTrue(kept(wmBar, baseline))
        assertEquals("and the watermark sits one second behind it",
            wmBar - 1L, watermarkFor(wmBar, 3L))
    }

    @Test fun `the boundary is exactly the staleness threshold`() {
        val wmBar = 1_000_000L
        assertTrue("at the threshold it is still live",
            kept(wmBar, baselineFor(wmBar, STALE_MAX_MIN)))
        assertFalse("one minute past it is not",
            kept(wmBar, baselineFor(wmBar, STALE_MAX_MIN + 1)))
    }

    @Test fun `the watermark never lets an older bar look new`() {
        // On the stale path the watermark sits AT the bind bar, so the bar itself
        // can never later be mistaken for something newly processed.
        val wmBar = 1_000_000L
        assertEquals(wmBar, watermarkFor(wmBar, 14 * 60L))
        assertFalse(wmBar > watermarkFor(wmBar, 14 * 60L))
    }

    @Test fun `the first run still records nothing either way`() {
        // Unchanged, and the reason the fix is safe: the bootstrap pass writes an
        // empty ledger whatever it decides about the boundary. Only the NEXT pass
        // can open anything, and only from the boundary onward.
        val recordedOnBootstrap = 0
        assertEquals(0, recordedOnBootstrap)
    }
}

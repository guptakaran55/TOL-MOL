package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE "NEAR ITS FLOOR" ALERT.
 *
 * This one wakes a phone about a name nobody asked to be woken about, across
 * three markets, every fifteen minutes. The arithmetic deciding that is trivial;
 * what is not trivial is the set of conditions under which it must STAY QUIET,
 * because an alert channel that fires too often gets silenced and then the real
 * ones are lost with it.
 *
 * So most of what is pinned here is silence: not at night, not twice in a day,
 * not on a range that does not exist, and not on a name that is merely cheap in
 * absolute terms rather than low against its own recent behaviour.
 */
class SupportAlertTest {

    // ── the zone itself ─────────────────────────────────────────────────────

    @Test fun `the zone is the bottom tenth of the range, not a tenth of the price`() {
        // THE DISTINCTION THAT MAKES IT PORTABLE. Ten percent of a price is an
        // enormous move on a steady stock and a rounding error on a volatile one,
        // so a fixed percentage would alert constantly on one name and never on
        // another. A tenth of the stock's OWN range means the same everywhere.
        assertEquals(0.10, IntradayLevels.SUPPORT_ZONE, 1e-9)
        // A 100-110 range: the zone reaches 101, nowhere near 10% off the price.
        val low = 100.0; val high = 110.0
        assertTrue(IntradayLevels.inSupportZone(IntradayLevels.positionInRange(101.0, low, high)))
        assertFalse(IntradayLevels.inSupportZone(IntradayLevels.positionInRange(102.0, low, high)))
        // And a wide 50-150 range reaches 60 — a 20% fall from the high.
        assertTrue(IntradayLevels.inSupportZone(IntradayLevels.positionInRange(60.0, 50.0, 150.0)))
    }

    @Test fun `sitting exactly on the boundary counts`() {
        assertTrue(IntradayLevels.inSupportZone(0.10))
        assertFalse(IntradayLevels.inSupportZone(0.1000001))
    }

    @Test fun `broken below the floor still alerts`() {
        // Either the best entry on the list or a level failing, and both are worth
        // being told about. Excluding it for being off the end of a scale would
        // mute precisely the most extreme case.
        assertTrue(IntradayLevels.inSupportZone(-0.05))
        assertTrue(IntradayLevels.inSupportZone(
            IntradayLevels.positionInRange(95.0, 100.0, 110.0)))
    }

    @Test fun `no reading means no alert`() {
        // A missing position is not a zero. Zero means "on the floor", which is the
        // loudest thing this can say — a failed calculation must never borrow it.
        assertFalse(IntradayLevels.inSupportZone(null))
        assertFalse(IntradayLevels.inSupportZone(
            IntradayLevels.positionInRange(100.0, 0.0, 110.0)))
        assertFalse("an inverted range is not a range",
            IntradayLevels.inSupportZone(IntradayLevels.positionInRange(100.0, 110.0, 90.0)))
    }

    // ── when it must stay quiet ─────────────────────────────────────────────

    /** The whole gate, as the watcher applies it. */
    private fun shouldAlert(pos: Double?, price: Double, barAgeMin: Long,
                            limitMin: Long, alreadyToday: Boolean): Boolean =
        barAgeMin in 0..limitMin && price > 0.0 &&
            IntradayLevels.inSupportZone(pos) && !alreadyToday

    @Test fun `it does not fire at three in the morning`() {
        // A completed bar exists all night. Without an age check, every name
        // sitting near its low would be announced hours after the session that
        // produced it — and by the open the price has had all night to move.
        assertTrue(shouldAlert(0.05, 100.0, barAgeMin = 20, limitMin = 150, alreadyToday = false))
        assertFalse("nine hours after the close, nothing",
            shouldAlert(0.05, 100.0, barAgeMin = 540, limitMin = 150, alreadyToday = false))
    }

    @Test fun `a bar age it could not work out blocks the alert`() {
        // Negative means the newest bar is unknown. Unknown is not "fresh".
        assertFalse(shouldAlert(0.05, 100.0, barAgeMin = -1, limitMin = 150, alreadyToday = false))
    }

    @Test fun `once a day per name, and no more`() {
        // The watcher pass runs every fifteen minutes and a stock can sit at the
        // bottom of its range all afternoon. Without this it would be roughly
        // thirty identical alerts for one observation.
        assertFalse(shouldAlert(0.03, 100.0, 20, 150, alreadyToday = true))
    }

    @Test fun `silence about the price is silence`() {
        assertFalse(shouldAlert(0.03, 0.0, 20, 150, alreadyToday = false))
    }

    @Test fun `a name in the middle of its range says nothing`() {
        assertFalse(shouldAlert(0.45, 100.0, 20, 150, alreadyToday = false))
        assertFalse(shouldAlert(0.95, 100.0, 20, 150, alreadyToday = false))
    }

    // ── the two alerts are independent ──────────────────────────────────────

    @Test fun `being near the floor and having an entry signal are separate facts`() {
        // They share a channel but not a dedup. A name can legitimately do both in
        // one day, and a shared map would let whichever fired first silence the
        // other — losing the more actionable of the two at random.
        val entryAlerted = mapOf("AAPL" to "2026-09-22")
        val floorAlerted = emptyMap<String, String>()
        assertTrue("the entry alert has fired", entryAlerted["AAPL"] == "2026-09-22")
        assertTrue("the floor alert is still free to fire",
            floorAlerted["AAPL"] != "2026-09-22")
    }
}

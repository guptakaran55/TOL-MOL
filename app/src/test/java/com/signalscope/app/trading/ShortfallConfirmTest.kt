package com.signalscope.app.trading

import com.signalscope.app.trading.T212PositionAudit.Drift
import com.signalscope.app.trading.T212PositionAudit.Report
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A LEDGER SHORTFALL IS TOLD ONLY WHEN IT LASTS. MRK on 19 Oct: its target
 * filled at Trading 212, the check at 15:46 saw the ledger 0.352 ahead of the
 * broker, and the pass at 15:52 booked the sale — a red alarm for a gap that
 * closed itself six minutes later.
 */
class ShortfallConfirmTest {
    private val now = 1_792_400_000_000L
    private val min = 60_000L
    private fun short(sym: String, kind: String = "MISSING", firstSeenAt: Long) =
        Drift(kind, "${sym}d_EQ", sym, listOf("de__manual"), 0.352, 0.0, -0.352, 0.0, firstSeenAt)

    @Test fun `seen once is not confirmed`() {
        assertFalse(T212PositionAudit.isConfirmed(short("MRK", firstSeenAt = now), now))
    }

    @Test fun `a check now a minute later does not confirm it either`() {
        assertFalse(T212PositionAudit.isConfirmed(short("MRK", firstSeenAt = now - min), now))
    }

    @Test fun `still short on the next pass is confirmed`() {
        assertTrue(T212PositionAudit.isConfirmed(short("VBK", "MISMATCH", firstSeenAt = now - 15 * min), now))
    }

    @Test fun `a row from before first-seen times were kept is not confirmed`() {
        assertFalse(T212PositionAudit.isConfirmed(short("VBK", firstSeenAt = 0L), now))
    }

    @Test fun `only lasting shortfalls count, never a holding no algo claims`() {
        val r = Report(ok = true, checkedAt = now, drifts = listOf(
            short("MRK", firstSeenAt = now),
            short("VBK", "MISMATCH", firstSeenAt = now - 30 * min),
            Drift("EXTRA", "ROP_US_EQ", "ROP", emptyList(), 0.0, 0.14, 0.14, 50.0)))
        assertEquals(2, r.critical.size)
        assertEquals(listOf("VBK"), r.confirmedCritical.map { it.symbol })
    }
}

package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The RV gate's record: one clean JSON line per event, read off the phone later. */
class RvJournalTest {

    @Test fun `a line carries time, event and key first, then the fields`() {
        val l = RvJournal.line(1_792_300_000_000L, "first", "algo|de__x|SAP-1|BUY",
            mapOf("price" to 101.5, "side" to "BUY"))
        assertTrue(l.startsWith("{\"at\":1792300000000,\"ev\":\"first\",\"key\":\"algo|de__x|SAP-1|BUY\""))
        assertTrue(l.contains("\"price\":101.5"))
        assertFalse(l.contains("\n"))
    }

    @Test fun `unknown values and non-numbers are left out rather than written as junk`() {
        val l = RvJournal.line(1L, "release", "you|1",
            mapOf("vel" to null, "pos" to Double.NaN, "atr" to Double.POSITIVE_INFINITY, "why" to "rising"))
        assertFalse(l.contains("vel")); assertFalse(l.contains("pos")); assertFalse(l.contains("atr"))
        assertTrue(l.contains("\"why\":\"rising\""))
    }

    @Test fun `order ids go in as a list`() {
        val l = RvJournal.line(1L, "fill", "you|1", mapOf("orderIds" to listOf(11L, 12L)))
        assertTrue(l.contains("\"orderIds\":[11,12]"))
    }

    @Test fun `the reading puts the price where it sat in today's range`() {
        val r = RvJournal.reading(RvGate.Reading(108.0, 110.0, 100.0, 4.0, 0.3, 60L))
        assertEquals(0.8, r["pos"] as Double, 1e-9)
        assertEquals(108.0, r["price"])
    }

    @Test fun `a range too narrow to mean anything gives no position`() {
        val r = RvJournal.reading(RvGate.Reading(100.2, 100.8, 100.0, 4.0, 0.1, 10L))
        assertEquals(null, r["pos"])
    }
}

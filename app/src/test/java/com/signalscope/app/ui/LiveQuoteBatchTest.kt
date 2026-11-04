package com.signalscope.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live prices reach the page in batches: every tick, in order, in one call. The
 * page must receive exactly the calls each tick used to make on its own.
 */
class LiveQuoteBatchTest {

    @Test fun `the first tick of a batch asks for a delivery, the rest do not`() {
        val b = LiveQuoteBatch()
        assertTrue(b.offer("SAP.DE", "{\"p\":1}"))
        assertFalse(b.offer("AAPL", "{\"p\":2}"))
        assertFalse(b.offer("SAP.DE", "{\"p\":3}"))
        b.drain()
        assertTrue(b.offer("SAP.DE", "{\"p\":4}"))           // a new batch after delivery
    }

    @Test fun `every tick is kept, in the order it arrived`() {
        val b = LiveQuoteBatch()
        b.offer("A", "a1"); b.offer("B", "b1"); b.offer("a", "a2")
        assertEquals(listOf("a1", "b1", "a2"), b.drain())
    }

    @Test fun `draining empties the batch`() {
        val b = LiveQuoteBatch()
        b.offer("A", "a")
        assertEquals(1, b.size())
        assertEquals(listOf("a"), b.drain())
        assertEquals(0, b.size())
        assertEquals(emptyList<String>(), b.drain())
    }

    @Test fun `a backlog past the cap drops only ticks a later tick supersedes`() {
        val b = LiveQuoteBatch(max = 4)
        b.offer("A", "a1"); b.offer("B", "b1"); b.offer("A", "a2"); b.offer("C", "c1")
        b.offer("D", "d1")                                   // full: a1 is the oldest superseded
        assertEquals(listOf("b1", "a2", "c1", "d1"), b.drain())
        val u = LiveQuoteBatch(max = 2)                      // nothing superseded: nothing dropped
        u.offer("A", "a"); u.offer("B", "b"); u.offer("C", "c")
        assertEquals(listOf("a", "b", "c"), u.drain())
    }

    @Test fun `each tick is the same call, escaped the same way, as when it was sent alone`() {
        val json = "{\"symbol\":\"O'NEIL\",\"note\":\"a\\\\b\"}"
        val old = "window.updateLiveQuote && window.updateLiveQuote('" +
            json.replace("\\", "\\\\").replace("'", "\\'") + "')"
        assertEquals("$old;", LiveQuoteBatch.script(listOf(json)))
        val two = LiveQuoteBatch.script(listOf("{}", "{\"a\":1}"))
        assertEquals(2, two.lines().size)
        assertTrue(two.lines().all { it.startsWith("window.updateLiveQuote && window.updateLiveQuote('") })
    }

    @Test fun `ticks from several stream threads all arrive`() {
        val b = LiveQuoteBatch()
        val threads = (0 until 8).map { t -> Thread { repeat(500) { i -> b.offer("S${t}_${i % 50}", "$t:$i") } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(8 * 500, b.drain().size)
    }
}

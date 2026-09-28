package com.signalscope.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE GERMAN UNIVERSE IS A UNION, AND THE POINT OF IT IS COVERAGE.
 *
 * These lock the two blind spots it exists to close. Either one on its own is a
 * list that silently stops containing something: the curated list cannot grow,
 * and the broker's list can shrink. Nothing about that is visible at a call
 * site — a universe that is missing a name looks exactly like a universe that
 * was never asked about it — so it is asserted here instead.
 */
class GermanUniverseTest {

    @Test fun `the curated list survives a broker list that has dropped names`() {
        val broker = listOf("SAP.DE", "SIE.DE")
        val u = deScreenedUniverse(broker)
        // Ceconomy: in the curated list, and the name the whole thing started with.
        assertTrue("a curated name must not vanish because the broker stopped listing it",
            u.contains("CEC.DE"))
        assertTrue(u.containsAll(DAX_SYMBOLS.map { it.uppercase() }))
    }

    @Test fun `names the broker lists and the curated list never had are included`() {
        val u = deScreenedUniverse(listOf("NEWCO.DE"))
        assertTrue("the whole reason for the live half", u.contains("NEWCO.DE"))
    }

    @Test fun `curated names come first, so a partial scan covers them`() {
        val u = deScreenedUniverse(listOf("ZZZZ.DE"))
        assertEquals(DAX_SYMBOLS.first().uppercase(), u.first())
        assertEquals("ZZZZ.DE", u.last())
    }

    @Test fun `the same company from both halves appears once, whatever its case`() {
        val u = deScreenedUniverse(listOf("cec.de", "CEC.DE", "SAP.DE"))
        assertEquals(1, u.count { it == "CEC.DE" })
        assertEquals(u.size, u.toSet().size)
    }

    @Test fun `a bare ticker from the broker half gets the venue suffix`() {
        assertTrue(deScreenedUniverse(listOf("NEWCO")).contains("NEWCO.DE"))
    }

    @Test fun `junk rows are dropped rather than sent to the price feed`() {
        val u = deScreenedUniverse(listOf("", "   ", "BAD SYMBOL", "X/Y.DE", "SAP.DE"))
        assertTrue(u.contains("SAP.DE"))
        assertFalse(u.any { it.contains(" ") || it.contains("/") })
    }

    @Test fun `an unavailable broker list degrades to the curated list, not to nothing`() {
        assertEquals(DAX_SYMBOLS.map { it.uppercase() }, deScreenedUniverse(emptyList()))
    }
}

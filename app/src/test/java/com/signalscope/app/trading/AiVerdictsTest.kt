package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one line of reason an alert carries: the first sentence, no headline numbers. */
class AiVerdictsTest {

    @Test fun firstSentenceWithoutCitations() {
        assertEquals("Earnings beat and guidance was raised for the year.",
            AiVerdicts.reasonOf("Earnings beat and guidance was raised for the year [1][2-3]. Shares rose."))
    }

    @Test fun aShortFirstSentenceKeepsTheRest() {
        assertEquals("Mixed. Results are due next week and could move it.",
            AiVerdicts.reasonOf("Mixed. Results are due next week and could move it."))
    }

    @Test fun longTextIsCut() {
        val r = AiVerdicts.reasonOf("x".repeat(400))
        assertEquals(168, r.length)
        assertTrue(r.endsWith("…"))
    }
}

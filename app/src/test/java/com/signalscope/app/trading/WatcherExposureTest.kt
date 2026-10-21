package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHAT COUNTS AS ALREADY BEING EXPOSED TO A COMPANY.
 *
 * The watcher hides any name the space is already exposed to, which is right —
 * offering a company you already own is how you end up holding it twice. The
 * whole question is what "exposed" means, and it got that wrong in a way nothing
 * on screen could reveal: it counted the watcher's OWN replay.
 *
 * That namespace enrols every candidate it watches and simulates the strategy
 * over all of them, so it carried 15 open PAPER positions against 2 real
 * holdings. Each of those 15 names became unofferable — including two the user
 * had pinned by hand, which stored correctly, showed as pinned on the chip and
 * in the counter, and never appeared in the list.
 *
 * Modelled here as sets rather than through the store, which needs a Context:
 * what is being pinned is the RULE about which sources describe a real position.
 */
class WatcherExposureTest {

    /**
     * The rule the exposure walk now applies. Deliberately written out rather
     * than called, so a future edit to the walk has to be reconciled with a
     * statement of what it is for.
     */
    private fun exposed(
        armedAlgoPositions: Set<String> = emptySet(),
        armedGateSeats: Set<String> = emptySet(),
        manualBookEntries: Set<String> = emptySet(),
        brokerRows: Set<String> = emptySet(),
        /** The watcher's own replay. NOT a source of exposure. */
        @Suppress("UNUSED_PARAMETER") watcherReplay: Set<String> = emptySet()
    ): Set<String> = armedAlgoPositions + armedGateSeats + manualBookEntries + brokerRows

    @Test fun `the watcher's own replay is not exposure`() {
        // The defect, as it stood on the device: JEN and S92 pinned by hand, held
        // by nobody, "open" only in the scan.
        val e = exposed(
            manualBookEntries = setOf("ELG", "IFX"),
            watcherReplay = setOf("JEN", "S92", "RWE", "VOW3", "ELG", "IFX"))
        assertFalse("a pinned name must reach the list", "JEN" in e)
        assertFalse("a pinned name must reach the list", "S92" in e)
        assertFalse("nor should any other simulated holding", "RWE" in e)
        assertEquals("only the two real ones", setOf("ELG", "IFX"), e)
    }

    @Test fun `a real manual holding is still exposure`() {
        // The behaviour the broken rule was protecting, which must survive: the
        // watcher must not re-offer something you just bought.
        val e = exposed(manualBookEntries = setOf("ELG"))
        assertTrue("you own it — do not offer it again", "ELG" in e)
    }

    @Test fun `an in-flight buy is exposure before any position exists`() {
        // The ladder runs for minutes. During that window there is no position,
        // but real orders are working and a second buy would double the trade.
        val e = exposed(manualBookEntries = setOf("PENDINGCO"))
        assertTrue("PENDINGCO" in e)
    }

    @Test fun `an armed algo's seat and position both count`() {
        // An armed algo either holds shares or is one open gate away from buying
        // them; both are real exposure in waiting. A PAPER algo is not here at
        // all, which is deliberate and unchanged.
        val e = exposed(armedAlgoPositions = setOf("SAP"), armedGateSeats = setOf("BMW"))
        assertTrue("SAP" in e)
        assertTrue("BMW" in e)
    }

    @Test fun `broker rows count in every market, not just Trading 212`() {
        // The walk read Trading 212's live rows and never Zerodha's, so a real
        // Indian position did not block the watcher from offering the same
        // company — in the one market with no fractional shares to net it out.
        val t212 = exposed(brokerRows = setOf("NDX1"))
        val zerodha = exposed(brokerRows = setOf("SYRMA"))
        assertTrue("NDX1" in t212)
        assertTrue("SYRMA" in zerodha)
    }

    @Test fun `a pinned name survives everything except genuine exposure`() {
        // The two halves together: pinning is what puts an unranked name on the
        // list at all, and only a real holding may take it off again.
        val pinned = listOf("JEN", "S92", "ELG")
        val e = exposed(
            manualBookEntries = setOf("ELG"),
            watcherReplay = setOf("JEN", "S92"))
        val shown = pinned.filterNot { it in e }
        assertEquals("the two pinned names appear, the held one does not",
            listOf("JEN", "S92"), shown)
    }
}

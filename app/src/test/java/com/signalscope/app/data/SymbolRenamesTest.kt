package com.signalscope.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rename table is three lines of data and the whole feature, which is exactly
 * the combination that rots. Nobody re-derives a mapping they can read; they add a
 * line to it months later from memory, and the failure is silent — a holding that
 * quietly stops being priced, or worse, an order that resolves to the wrong
 * company because a freed-up ticker was reissued.
 *
 * So the invariants are asserted rather than trusted: every entry must move, must
 * move back, and must not collide with any other entry in either direction.
 */
class SymbolRenamesTest {

    @Test fun `the German renames this app was actually bitten by`() {
        assertEquals("MBG", SymbolRenames.currentDe("DAI"))   // Daimler → Mercedes-Benz
        assertEquals("1U1", SymbolRenames.currentDe("DRI"))   // 1&1 Drillisch → 1&1
        assertEquals("DHL", SymbolRenames.currentDe("DPW"))   // Deutsche Post → DHL Group
    }

    @Test fun `an order for the new name still reaches the old instrument`() {
        assertEquals("DAI", SymbolRenames.legacyDe("MBG"))
        assertEquals("DRI", SymbolRenames.legacyDe("1U1"))
        assertEquals("DPW", SymbolRenames.legacyDe("DHL"))
    }

    @Test fun `the Meta rename still works and is US-only`() {
        assertEquals("META", SymbolRenames.current("FB", "US"))
        assertEquals("FB", SymbolRenames.legacy("META", "US"))
        // A German "FB" — if one ever existed — is a different company entirely,
        // and reaching across venues is precisely the wrong-company failure.
        assertEquals("FB", SymbolRenames.currentDe("FB"))
    }

    @Test fun `a name that never changed passes straight through`() {
        for (s in listOf("SAP", "IFX", "BAS", "NDX1", "EOAN", "GXI", "AAPL", "QCOM")) {
            assertEquals(s, SymbolRenames.currentDe(s))
            assertEquals(s, SymbolRenames.legacyDe(s))
            assertFalse(SymbolRenames.isRetiredDe(s))
        }
    }

    @Test fun `lower case and stray spaces resolve the same as the canonical form`() {
        assertEquals("MBG", SymbolRenames.currentDe(" dai "))
        assertEquals("DAI", SymbolRenames.legacyDe("mbg"))
        assertTrue(SymbolRenames.isRetiredDe("dai"))
    }

    @Test fun `Pfisterer's frozen feed line is asked for under its live one`() {
        assertEquals("PFS0.DE", SymbolRenames.feedSuccessor("PFSE.DE"))
        assertEquals("PFS0.DE", SymbolRenames.feedSuccessor(" pfse.de "))
        // Xetra only: a bare or US symbol of the same spelling is not this share.
        assertEquals(null, SymbolRenames.feedSuccessor("PFSE"))
        assertEquals(null, SymbolRenames.feedSuccessor("PFSE.F"))
        // A line that never moved, and the live line itself, pass through.
        assertEquals(null, SymbolRenames.feedSuccessor("SAP.DE"))
        assertEquals(null, SymbolRenames.feedSuccessor("PFS0.DE"))
    }

    @Test fun `a feed-line change never renames what the app stores or what it orders`() {
        // The open position is booked as PFSE, and so are its live-order row, its
        // resting target ("PFSE-<signal bar>") and its ledger entries. Re-keying
        // the book to PFS0 would orphan every one of them, so the book clean-up
        // and both order directions must not see this entry at all.
        assertFalse(SymbolRenames.isRetiredDe("PFSE"))
        assertEquals("PFSE", SymbolRenames.currentDe("PFSE"))
        assertEquals("PFS0", SymbolRenames.legacyDe("PFS0"))
    }

    @Test fun `every rename round trips, and no retired name is also a live one`() {
        for (old in listOf("DAI", "DRI", "DPW")) {
            val now = SymbolRenames.currentDe(old)
            assertTrue("$old must actually move", now != old)
            assertEquals("$old must round trip", old, SymbolRenames.legacyDe(now))
            // The trap this guards: adding X→Y while Y→Z already exists leaves a
            // name that is both a destination and a source, and which of the two
            // meanings applies then depends on call order.
            assertFalse("$now must not itself be retired", SymbolRenames.isRetiredDe(now))
        }
    }
}

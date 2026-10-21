package com.signalscope.app.trading

import com.signalscope.app.data.ManualWatchStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHICH NAMESPACES MAY SEND PAPER TRADE NOTIFICATIONS.
 *
 * Paper open/close alerts were crypto-only, which made the per-market and
 * per-algo notification switches a lie everywhere else. Opening them to every
 * space fixed that and swept in the one namespace where a paper trade does NOT
 * describe a position: the watchlist's reserved namespace, whose ledger is a scan
 * over every candidate it watches.
 *
 * The result was "Germany watchlist would SELL MUX" for a stock the user does not
 * own — 35 enrolled candidates against one real holding. A sell instruction for
 * something you do not hold reads as the app having lost your position, so this
 * is worth a test of its own rather than a comment.
 *
 * Asserted against the namespace rule rather than the notifier, which needs a
 * Context: what is being pinned is the DECISION about scope.
 */
class PaperNotifyScopeTest {

    /** The rule the notifier applies, reproduced here. */
    private fun isScanNamespace(ns: String): Boolean =
        Spaces.bucketIdOf(ns) == ManualWatchStore.ID

    @Test fun `the watchlist namespace is a scan in every market`() {
        listOf("de", "us", "in", "binance").forEach { space ->
            val ns = ManualWatchStore.nsFor(space)
            assertTrue("$ns holds candidates, not positions", isScanNamespace(ns))
        }
    }

    @Test fun `a real algo is not a scan and must still notify`() {
        // The whole point of opening these up. An Indian algo's own book is
        // exactly what the user asked to be told about.
        assertFalse(isScanNamespace("in__bexj6w"))
        assertFalse(isScanNamespace("de__b1h8052"))
        assertFalse(isScanNamespace("binance__b11oect"))
        assertFalse("a bare space is a lab, not a scan", isScanNamespace("binance"))
        assertFalse(isScanNamespace("us"))
    }

    @Test fun `the reserved id is what distinguishes them, not the name`() {
        // An algo a user happens to call something similar must not be muted.
        assertFalse(isScanNamespace("de__manualish"))
        assertFalse(isScanNamespace("de__mymanual"))
        assertTrue(isScanNamespace("de__manual"))
    }

    @Test fun `the watchlist namespace is still shaped like a bucket`() {
        // It is deliberately not a BucketStore entry — an armed bucket mirrors its
        // own entries to the broker, and behind a position the user already bought
        // that would place a second real order. But it must still resolve to its
        // space, or the cost model and the notifier would both look in the wrong
        // market.
        val ns = ManualWatchStore.nsFor("de")
        assertEquals("de", Spaces.baseOf(ns))
        assertTrue(Spaces.isBucket(ns))
        assertEquals("de__manual", ns)
    }
}

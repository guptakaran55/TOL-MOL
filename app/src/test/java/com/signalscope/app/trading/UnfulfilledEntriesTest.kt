package com.signalscope.app.trading

import com.signalscope.app.data.T212LiveStore.LiveTrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHICH FAILED REAL BUYS LEAVE THE BOOK. ADN1 and NEM on 15 Oct 2026: both
 * refused at 18:16 because the price had run past the ceiling, and both held a
 * B_Conservative seat for four days with nothing in the account.
 */
class UnfulfilledEntriesTest {

    private val now = 1_792_400_000_000L          // Mon 19 Oct, 18:13
    private val hour = 3_600_000L
    private fun row(sym: String, status: String, ns: String = "de__buyx6g", filled: Double = 0.0,
                    qty: Double = 0.0, attemptedHoursAgo: Long = 72L) =
        LiveTrade(paperTradeId = "$sym-1792072800", ns = ns, symbol = sym, t212Ticker = "${sym}d_EQ",
            status = status, entryTs = 1792072800L, paperEntryPrice = 67.6, amount = 189.0, qty = qty,
            buyAt = now - attemptedHoursAgo * hour, liveQtyFilled = filled,
            error = "price ran 1.33% above the signal before this could fill (limit 68.28) — beyond the chase ceiling, not bought")

    @Test fun `a buy refused for good, with no share bought, is picked up`() {
        val out = UnfulfilledEntries.failedForGood(listOf(row("ADN1", "ERROR")), now, emptySet())
        assertEquals(listOf("ADN1"), out.map { it.symbol })
    }

    @Test fun `a buy still being retried is not`() {
        assertTrue(UnfulfilledEntries.failedForGood(listOf(row("ADN1", "RETRY")), now, emptySet()).isEmpty())
    }

    @Test fun `a position that was bought is not, whatever its state`() {
        assertTrue(UnfulfilledEntries.failedForGood(listOf(row("SAP", "ERROR", filled = 0.95, qty = 0.95)), now, emptySet()).isEmpty())
        assertTrue(UnfulfilledEntries.failedForGood(listOf(row("SAP", "OPEN", filled = 0.95, qty = 0.95)), now, emptySet()).isEmpty())
    }

    @Test fun `a failure from the last twenty minutes waits, in case a fill is still being booked`() {
        assertTrue(UnfulfilledEntries.failedForGood(listOf(row("ADN1", "ERROR", attemptedHoursAgo = 0L)), now, emptySet()).isEmpty())
    }

    @Test fun `older than thirty days is left in the past`() {
        assertTrue(UnfulfilledEntries.failedForGood(listOf(row("NEM", "ERROR", attemptedHoursAgo = 31L * 24)), now, emptySet()).isEmpty())
    }

    @Test fun `one already listed is not listed twice`() {
        assertTrue(UnfulfilledEntries.failedForGood(listOf(row("ADN1", "ERROR")), now,
            setOf("de__buyx6g|ADN1-1792072800")).isEmpty())
    }

    @Test fun `the watchlist's own buys hold no seat and are left out`() {
        assertTrue(UnfulfilledEntries.failedForGood(listOf(row("PYPL", "ERROR", ns = "us__manual")), now, emptySet()).isEmpty())
    }

    // GFT in B_Conservative, 21 Oct: armed for the name, but the order was never
    // sent — the signal was first seen after its bar had been checked.
    private val gftSkip = "this signal was first seen AFTER its bar had already been checked, so no real " +
        "order was placed — buying then would mean a price the signal no longer describes. Usually the " +
        "price feed delivered that bar late. It will trade for real on its next fresh signal."

    @Test fun `an order the armed algo never sent is a miss`() {
        assertTrue(UnfulfilledEntries.missedWhileArmed(gftSkip))
        assertTrue(UnfulfilledEntries.missedWhileArmed("the price feed was stale on this pass — no real order placed"))
        assertTrue(UnfulfilledEntries.missedWhileArmed(
            "signal came from an older bar, not the newest closed one — catch-up entries stay on paper"))
    }

    @Test fun `a name never meant to be real is not`() {
        assertTrue(!UnfulfilledEntries.missedWhileArmed("this algo is not armed for real money, or its budget is 0"))
        assertTrue(!UnfulfilledEntries.missedWhileArmed("GFT is not in this algo's real-money list"))
        assertTrue(!UnfulfilledEntries.missedWhileArmed("no Trading 212 credentials"))
    }

    @Test fun `the reason drops its promise of a next fresh signal`() {
        assertEquals("this signal was first seen AFTER its bar had already been checked, so no real order " +
            "was placed — buying then would mean a price the signal no longer describes",
            UnfulfilledEntries.shortReason(gftSkip))
    }

    @Test fun `the bar a trade was bought on comes from its id`() {
        assertEquals(1792072800L, UnfulfilledEntries.entryTsOf("ADN1-1792072800"))
        assertEquals(0L, UnfulfilledEntries.entryTsOf("broken"))
    }
}

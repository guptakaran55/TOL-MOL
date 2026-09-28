package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * The live-sale alert, pinned to the trade that exposed it.
 *
 * S92 was bought by hand at 56.20 and sold by the watchlist's exit minder on a
 * MACD flip. The rule fired on a Friday close, the German market was already
 * shut, and the order did not fill until Monday's open — 1.40 higher, at 58.05.
 *
 * The alert that arrived said 80.78 EUR and "your money +0.80%", because it had
 * been built from the strategy's candle rather than the broker's fill. The trade
 * had in fact made +3.29%. These cases hold that line: a percentage under those
 * words is a real fill against a real fill, or it is not printed at all.
 */
class LabNotifierSellTextTest {

    /** The formatter is locale-sensitive; the assertions below are not meant to be. */
    @Before fun pinLocale() { Locale.setDefault(Locale.US) }

    private val who = "Germany watchlist · exit minder for stock you bought yourself"
    private val why = "MACD slope flipped + to -"

    @Test fun `settled sale reports the broker fill, not the candle`() {
        val (title, body) = LabNotifier.sellAlertText(
            who = who, symbol = "S92", qty = 1.426, cur = "EUR", reason = why,
            paid = 56.20, realExit = 58.05, broker = "Trading 212")

        assertTrue("percentage belongs in the title", title.contains("+3.29%"))
        assertTrue("real proceeds", body.contains("for 82.78 EUR"))
        assertTrue("real per-share exit", body.contains("at 58.05"))
        assertTrue("real return", body.contains("your money +3.29%"))
        assertTrue("what was paid", body.contains("bought at 56.20"))
        assertTrue("why it sold", body.contains(why))

        // The exact figures the shipped alert used. Neither may reappear.
        assertFalse("the candle close must not be quoted", body.contains("56.65"))
        assertFalse("the candle-derived return must not be quoted", body.contains("+0.80%"))
    }

    @Test fun `unpriced sale quotes no money at all`() {
        val (title, body) = LabNotifier.sellAlertText(
            who = who, symbol = "S92", qty = 1.426, cur = "EUR", reason = why,
            paid = 56.20, realExit = 0.0, broker = "Trading 212")

        assertEquals("no percentage may be implied before a fill is known",
            "🔴 LIVE SELL S92", title)
        assertTrue(body.contains("waiting on Trading 212"))
        assertTrue("the reason survives into the provisional card", body.contains(why))
        assertFalse("no cash figure", body.contains("EUR"))
        assertFalse("no return figure", body.contains("your money"))
        assertFalse("nothing resembling a percentage", body.contains("%"))
    }

    @Test fun `the broker doing the waiting is named`() {
        val (_, body) = LabNotifier.sellAlertText(
            who = who, symbol = "TATAMOTORS", qty = 3.0, cur = "INR", reason = why,
            paid = 700.0, realExit = 0.0, broker = "Zerodha")
        assertTrue(body.contains("waiting on Zerodha"))
    }

    @Test fun `a known fill against an unknown cost states the money but not a return`() {
        // Entry never reconciled — real for the sale, absent for the purchase. A
        // percentage here would be measured against nothing.
        val (title, body) = LabNotifier.sellAlertText(
            who = who, symbol = "S92", qty = 1.426, cur = "EUR", reason = why,
            paid = 0.0, realExit = 58.05, broker = "Trading 212")

        assertEquals("🔴 LIVE SELL S92", title)
        assertTrue("the cash received is real and is reported", body.contains("for 82.78 EUR"))
        assertFalse("but no return can be computed", body.contains("your money"))
    }

    @Test fun `the cash figure always agrees with the percentage beside it`() {
        val paid = 56.20; val exit = 58.05; val qty = 1.426
        val (_, body) = LabNotifier.sellAlertText(
            who = who, symbol = "S92", qty = qty, cur = "EUR", reason = "",
            paid = paid, realExit = exit, broker = "Trading 212")

        // Both derive from the same pair of fills, so the reader can check one
        // against the other — which is exactly what the old alert failed.
        assertTrue(body.contains("for ${"%.2f".format(exit * qty)} EUR"))
        assertTrue(body.contains("your money ${"%+.2f".format((exit / paid - 1.0) * 100.0)}%"))
    }

    @Test fun `a loss is signed, not silently dropped`() {
        val (title, body) = LabNotifier.sellAlertText(
            who = who, symbol = "S92", qty = 1.0, cur = "EUR", reason = why,
            paid = 60.0, realExit = 57.0, broker = "Trading 212")
        assertTrue(title.contains("(-5.00%)"))
        assertTrue(body.contains("your money -5.00%"))
    }
}

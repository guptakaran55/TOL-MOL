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
 * The alert that arrived said "+0.80%", because it had been built from the
 * strategy's candle rather than the broker's fill. The trade had in fact made
 * +3.29%. These cases hold that line: a result in the alert is a real fill
 * against a real fill, or it is not printed at all.
 *
 * AND THE CARD IS MINIMAL (the user's ask, 16 Oct 2026): the market's flag, the
 * stock and the result in the title; who bought it and what sold it below — no
 * quantities, no prices, nothing said twice.
 */
class LabNotifierSellTextTest {

    /** The formatter is locale-sensitive; the assertions below are not meant to be. */
    @Before fun pinLocale() { Locale.setDefault(Locale.US) }

    private val who = "Watcher · your buy"
    private val why = "MACD slope flipped + to -"

    @Test fun `settled sale - flag, stock and the real result in the title, who and why below`() {
        val (title, body) = LabNotifier.sellAlertText(
            who = who, symbol = "S92", qty = 1.426, cur = "EUR", reason = why,
            paid = 56.20, realExit = 58.05, broker = "Trading 212", flag = "🇩🇪")
        assertEquals("🇩🇪 S92 sold +3.29%", title)
        assertEquals("Watcher · your buy · MACD exit", body)
        // The candle-derived return of the shipped alert may not reappear.
        assertFalse(title.contains("+0.80%"))
    }

    @Test fun `no quantities, prices or repeated figures in the body`() {
        val (title, body) = LabNotifier.sellAlertText(
            who = "Watcher buy", symbol = "HEI", qty = 0.348, cur = "EUR",
            reason = "reached its target of 145.00 (its 1-week high) · its target at Trading 212",
            paid = 143.25, realExit = 145.00, broker = "Trading 212", flag = "🇩🇪")
        assertEquals("🇩🇪 HEI sold +1.22%", title)
        assertEquals("Watcher buy · 1-week-high target reached", body)
        assertFalse(body.contains("145")); assertFalse(body.contains("0.348")); assertFalse(body.contains("%"))
    }

    @Test fun `unpriced sale quotes no result at all`() {
        val (title, body) = LabNotifier.sellAlertText(
            who = who, symbol = "S92", qty = 1.426, cur = "EUR", reason = why,
            paid = 56.20, realExit = 0.0, broker = "Trading 212", flag = "🇩🇪")
        assertEquals("no result may be implied before a fill is known", "🇩🇪 S92 sold", title)
        assertTrue(body.contains("result once Trading 212 reports the price"))
        assertFalse("nothing resembling a percentage", body.contains("%"))
    }

    @Test fun `the broker doing the waiting is named`() {
        val (_, body) = LabNotifier.sellAlertText(
            who = who, symbol = "TATAMOTORS", qty = 3.0, cur = "INR", reason = why,
            paid = 700.0, realExit = 0.0, broker = "Zerodha", flag = "🇮🇳")
        assertTrue(body.contains("once Zerodha reports"))
    }

    @Test fun `a known fill against an unknown cost states no return`() {
        // Entry never reconciled — a percentage here would be measured against nothing.
        val (title, _) = LabNotifier.sellAlertText(
            who = who, symbol = "S92", qty = 1.426, cur = "EUR", reason = why,
            paid = 0.0, realExit = 58.05, broker = "Trading 212", flag = "🇩🇪")
        assertEquals("🇩🇪 S92 sold", title)
    }

    @Test fun `a loss is signed, not silently dropped`() {
        val (title, _) = LabNotifier.sellAlertText(
            who = who, symbol = "S92", qty = 1.0, cur = "EUR", reason = why,
            paid = 60.0, realExit = 57.0, broker = "Trading 212", flag = "🇩🇪")
        assertEquals("🇩🇪 S92 sold -5.00%", title)
    }

    @Test fun `the kind of exit in a few words`() {
        assertEquals("ATR stop triggered", LabNotifier.shortReason("ATR trailing stop 2.0× hit at 41.20"))
        assertEquals("stop-loss triggered", LabNotifier.shortReason("stop loss -4%"))
        assertEquals("target reached", LabNotifier.shortReason("take profit +7%"))
        assertEquals("your target reached", LabNotifier.shortReason("your own take profit on stock you bought yourself"))
        assertEquals("trailing stop triggered", LabNotifier.shortReason("trailing floor raised and hit"))
        assertEquals("", LabNotifier.shortReason(""))
        // Unknown wording keeps its first clause rather than vanishing.
        assertEquals("rotation to a stronger name", LabNotifier.shortReason("rotation to a stronger name · seat freed"))
    }
}

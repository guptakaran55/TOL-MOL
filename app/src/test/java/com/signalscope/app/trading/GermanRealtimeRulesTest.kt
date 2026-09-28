package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MOVING THE GERMAN SPACE ONTO A LIVE VENUE.
 *
 * Three rules decide whether that actually happens, and all three are the sort
 * that look wrong until the delay is held in mind: a quote that ARRIVES now can
 * be older than one FETCHED ten minutes ago, because Yahoo's German feed runs a
 * quarter of an hour behind. Measured, not assumed — on a normal afternoon its
 * newest one-minute bar for SAP, AIXTRON and Siemens Energy was in every case
 * exactly fifteen minutes old.
 *
 * Modelled as the pure decisions, because the real ones live on an object that
 * needs a Context, a network and a broker session. What is pinned is the RULE.
 */
class GermanRealtimeRulesTest {

    private val MIN = 60_000L
    private val REALTIME_HOLDS_MS = 15 * MIN
    private val SRC_REALTIME = "tradegate"

    // ── which mark wins ─────────────────────────────────────────────────────

    /** Does an arriving streamed tick get to replace what is already stored? */
    private fun streamMayOverwrite(currentSource: String?, currentAgeMs: Long): Boolean =
        !(currentSource == SRC_REALTIME && currentAgeMs < REALTIME_HOLDS_MS)

    @Test fun `a delayed tick may not overwrite a recent live-venue price`() {
        // The rule the whole change rests on. Without it the stream would undo
        // every poll within seconds and leave no trace of having done so — the
        // feature would appear to be built and do nothing.
        assertFalse(streamMayOverwrite(SRC_REALTIME, 30_000L))
        assertFalse(streamMayOverwrite(SRC_REALTIME, 14 * MIN))
    }

    @Test fun `once the live price is older than the delay, the stream wins again`() {
        // Self-correcting on purpose. A sixteen-minute-old venue price really is
        // worse than a tick carrying fifteen-minute-old news, so the hold expires
        // rather than pinning a number that has stopped being the best available.
        assertTrue(streamMayOverwrite(SRC_REALTIME, 15 * MIN))
        assertTrue(streamMayOverwrite(SRC_REALTIME, 40 * MIN))
    }

    @Test fun `nothing else is protected from the stream`() {
        // The hold is about ONE source being better, not about marks being
        // precious. A polled Yahoo price or a bar close is still replaced by an
        // arriving tick exactly as before, in every space.
        assertTrue(streamMayOverwrite(null, 0L))
        assertTrue(streamMayOverwrite("poll", 10L))
        assertTrue(streamMayOverwrite("bar", 10L))
        assertTrue(streamMayOverwrite("stream", 10L))
    }

    @Test fun `the hold is exactly the measured delay`() {
        // Pinned as a number because it is not arbitrary: shorter and a live price
        // gets overwritten by older news; longer and a genuinely stale venue price
        // outranks a better one.
        assertEquals(15 * MIN, REALTIME_HOLDS_MS)
    }

    // ── which marks count as needing a refresh ──────────────────────────────

    /** Should this symbol be re-priced on this pass? */
    private fun needsPricing(base: String, source: String?, ageMs: Long, maxAgeMs: Long): Boolean {
        if (source == null) return true
        val delayedGerman = base == "de" && source == "stream"
        return delayedGerman || ageMs > maxAgeMs
    }

    @Test fun `a streamed German mark never counts as fresh`() {
        // The chicken-and-egg this exists to break. The refresh only visits marks
        // it considers stale, and the stream keeps German marks looking current to
        // the second — so without this the live venue would never be asked once,
        // and the position would sit there looking perfectly priced.
        assertTrue("seconds old and still not good enough",
            needsPricing("de", "stream", 5_000L, 12 * MIN))
    }

    @Test fun `a German mark from the live venue ages normally`() {
        // It is not re-fetched on every pass — only once it is genuinely old.
        // Otherwise every sweep would spend a request per held name for a price
        // that had not moved.
        assertFalse(needsPricing("de", SRC_REALTIME, 2 * MIN, 12 * MIN))
        assertTrue(needsPricing("de", SRC_REALTIME, 13 * MIN, 12 * MIN))
    }

    @Test fun `the other markets are left completely alone`() {
        // America streams real trades through Finnhub and India through Zerodha.
        // Their streamed marks ARE current, and treating them as stale would buy
        // nothing while spending a quote per name per pass.
        assertFalse(needsPricing("us", "stream", 5_000L, 12 * MIN))
        assertFalse(needsPricing("in", "stream", 5_000L, 12 * MIN))
        assertTrue("but they still age out like anything else",
            needsPricing("us", "stream", 20 * MIN, 12 * MIN))
    }

    // ── the guard that stops a wrong instrument being priced ────────────────

    /** May a live quote be attempted at all? */
    private fun mayAskVenue(referencePrice: Double) = referencePrice > 0.0

    /** The reader's own sanity gate, restated: how far may a quote stray? */
    private fun withinReference(quote: Double, reference: Double, tolerance: Double = 0.08) =
        reference > 0.0 && Math.abs(quote - reference) / reference <= tolerance

    @Test fun `no reference price means no live quote is even attempted`() {
        // NOT CAUTION FOR ITS OWN SAKE. Quotes are fetched by ISIN, and the wrong
        // ISIN returns a well-formed, entirely plausible, completely wrong price.
        // While building this, a bad ISIN for SMA Solar returned 1.82 against a
        // true 61.75. The reference is the only thing standing between that and a
        // panel — so with no reference there is no attempt.
        assertFalse(mayAskVenue(0.0))
        assertFalse(mayAskVenue(-1.0))
        assertTrue(mayAskVenue(61.75))
    }

    @Test fun `the wrong company is rejected, the right one is not`() {
        assertFalse("1.82 against 61.75 is not the same instrument",
            withinReference(1.82, 61.75))
        assertTrue("a quarter of an hour of drift is well inside tolerance",
            withinReference(182.74, 182.10))
        assertTrue(withinReference(36.58, 36.47))
    }

    @Test fun `a stale reference is still a perfectly good reference`() {
        // The reference answers "is this roughly the right company", which a
        // fifteen-minute-old price answers just as well as a current one. It is
        // deliberately NOT being asked "what is this worth now" — that is the
        // question the live quote is there to answer.
        val fifteenMinutesStale = 182.10
        assertTrue(withinReference(182.74, fifteenMinutesStale))
        // Even a genuinely large intraday move stays inside the gate.
        assertTrue("a 5% move must not look like a wrong instrument",
            withinReference(191.20, fifteenMinutesStale))
    }
}

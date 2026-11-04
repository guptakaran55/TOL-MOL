package com.signalscope.app.trading

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** EVERY ALGO'S 💡 REPLAY: which ones a run replays and which it keeps. */
class WhatIfBatchTest {
    private val now = 1_792_900_000_000L
    private val day = 24 * 60 * 60_000L

    @Test fun `never replayed is replayed`() {
        assertTrue(WhatIfBatch.needsReplay(null, now))
    }

    @Test fun `less than a week old is kept, older is replayed`() {
        assertFalse(WhatIfBatch.needsReplay(now - 2 * day, now))
        assertTrue(WhatIfBatch.needsReplay(now - 8 * day, now))
    }
}

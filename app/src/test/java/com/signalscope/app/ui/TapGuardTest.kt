package com.signalscope.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A notification tap is acted on once — a replay after a restart is not. */
class TapGuardTest {
    private val now = 1_000_000_000L

    @Test fun aNumberedTapIsUsedOnce() {
        assertFalse(TapGuard.isReplay("ns-CDW@1", "x", false, listOf("ns-ABNB@1"), null, now))
        assertTrue(TapGuard.isReplay("ns-CDW@1", "x", false, listOf("ns-CDW@1"), null, now))
    }

    @Test fun aRelaunchFromRecentsIsNeverATap() {
        assertTrue(TapGuard.isReplay("new@2", "x", true, emptyList(), null, now))
    }

    @Test fun anOldNumberlessTapIsKnownByWhereItLeadsForHalfADay() {
        val last = "sym|CDW|us|$now"
        assertTrue(TapGuard.isReplay(null, "sym|CDW|us", false, emptyList(), last, now + 60_000))
        assertFalse(TapGuard.isReplay(null, "sym|CDW|us", false, emptyList(), last, now + TapGuard.LEGACY_WINDOW_MS + 1))
        assertFalse(TapGuard.isReplay(null, "sym|TEAM|us", false, emptyList(), last, now + 60_000))
        assertFalse(TapGuard.isReplay(null, "sym|CDW|us", false, emptyList(), null, now))
    }
}

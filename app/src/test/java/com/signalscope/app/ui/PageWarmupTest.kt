package com.signalscope.app.ui

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A REOPENED SCREEN DRAWS FIRST, THEN FILLS IN (6 Nov 2026): the market on
 * screen is sent first — decided as the page decides it — and a hidden screen
 * lets go of what is saved on the phone, never while a scan builds on it.
 */
class PageWarmupTest {
    @Test fun `markets map to the page's own spaces`() {
        assertEquals("DE", PageWarmup.spaceOf("DAX 100"))
        assertEquals("US", PageWarmup.spaceOf("NASDAQ 100"))
        assertEquals("IN", PageWarmup.spaceOf("NIFTY 500"))
        assertEquals("DE", PageWarmup.spaceOf("anything", "€"))
    }

    @Test fun `the market on screen is told apart, and with none known all count`() {
        assertTrue(PageWarmup.onScreen("DAX 100", "", "DE"))
        assertFalse(PageWarmup.onScreen("NIFTY 500", "", "DE"))
        assertTrue(PageWarmup.onScreen("NIFTY 500", "", ""))
    }

    @Test fun `hidden screen drops scan results unless a scan runs, short memory drops store copies`() {
        assertEquals(true to true, PageWarmup.releaseFor(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, scanRunning = false))
        assertEquals(true to false, PageWarmup.releaseFor(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, scanRunning = true))
        assertEquals(true to false, PageWarmup.releaseFor(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, scanRunning = false))
        assertEquals(false to false, PageWarmup.releaseFor(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, scanRunning = false))
    }
}

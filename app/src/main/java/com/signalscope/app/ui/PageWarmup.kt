package com.signalscope.app.ui

import android.content.ComponentCallbacks2
import com.signalscope.app.data.StoreCache

/**
 * A REOPENED SCREEN DRAWS FIRST, THEN FILLS IN (the user, 6 Nov 2026).
 *
 * Android throws the screen away in the background when the phone is short of
 * memory (10:02, 10:10 and 11:03 that morning) while the app itself runs on for
 * its timers. Coming back from the recent-apps list rebuilt the screen inside
 * that running app — which still held every market's scan results, so the new
 * page was handed all of them the moment it loaded and stayed white until it had
 * swallowed them. Swiping the app away and opening it again was instant, because
 * a fresh start has nothing loaded yet: the page drew, and the data came after.
 *
 * Now both openings work the fresh way: the page is drawn first, then the market
 * on screen is sent, then the rest one at a time with a pause between — and
 * while the screen is hidden the app lets go of what is saved on the phone
 * anyway, so there is less to pull back in and less reason to be thrown away.
 */
object PageWarmup {
    /** Longest wait for the page's first frame before sending anyway. */
    const val PAINT_FALLBACK_MS = 1_500L
    /** Pause between one market's results and the next, so the screen draws between. */
    const val GAP_MS = 300L

    /** The page's market for a scan ("DAX 100" → "DE") — as the page's own
     *  spaceOfMarket decides it. Pure. */
    fun spaceOf(market: String, currency: String = ""): String {
        val m = market.uppercase()
        return when {
            currency == "€" || m.contains("DAX") || m.contains("XETRA") -> "DE"
            currency == "$" || m.contains("NASDAQ") || m.contains("NYSE") -> "US"
            else -> "IN"
        }
    }

    /** Is this market the one on screen? With no market known, every one counts. Pure. */
    fun onScreen(market: String, currency: String, visible: String): Boolean =
        visible.isBlank() || spaceOf(market, currency).equals(visible, true)

    /** What to let go of for an [onTrimMemory] level. Pure.
     *  @return (drop the kept store files, drop the scan results held for the screen) */
    fun releaseFor(level: Int, scanRunning: Boolean): Pair<Boolean, Boolean> {
        val stores = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        val scan = level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && !scanRunning
        return stores to scan
    }

    /**
     * The screen is hidden or the phone is short of memory: let go of what is
     * saved on the phone anyway. The scan results are read back from their file
     * when the screen returns (MainActivity's restore), and only when no scan is
     * running — a running scan builds on them. Kept store files are read again on
     * their next use.
     */
    fun release(level: Int) {
        val (stores, scan) = releaseFor(level, ScanServiceResultHolder.isDiscoveryRunning)
        if (stores) StoreCache.clear()
        if (scan) ScanServiceResultHolder.lastDiscoveryResult = null
    }
}

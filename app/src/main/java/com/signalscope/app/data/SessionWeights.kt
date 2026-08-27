package com.signalscope.app.data

/**
 * Session-only override for scoring weights.
 *
 * The Setups tab in dashboard.html exposes sliders for certain MACD tunables.
 * Moving a slider calls DashboardBridge.setSessionWeight(...) which stamps the
 * adjusted weights into [override]. ConfigManager.scoringWeights prefers this
 * override over the persisted defaults, so the currently-running scan pipeline
 * (ScanService + StockAnalyzer) picks the new values up immediately.
 *
 * Persistence behaviour:
 *  - Lives in memory only (singleton object, no disk write).
 *  - Resets to null on every process start, which restores the Settings-tab
 *    defaults each time the user reopens the app (per product spec).
 */
object SessionWeights {
    @Volatile
    var override: ScoringWeights? = null
}

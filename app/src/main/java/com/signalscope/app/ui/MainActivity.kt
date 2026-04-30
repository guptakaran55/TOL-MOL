package com.signalscope.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.DiscoveryResultStore
import com.signalscope.app.data.DiscoveryScanResult
import com.signalscope.app.data.PortfolioResultStore
import com.signalscope.app.service.ScanService
import com.signalscope.app.network.YahooFinanceClient
import com.signalscope.app.network.StockAiClient
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

/**
 * Main activity — hosts a WebView that renders the full SignalScope dashboard.
 *
 * Architecture:
 *   - dashboard.html (loaded from assets) renders the stock table, detail modal, etc.
 *   - Kotlin pushes scan results into the WebView via evaluateJavascript()
 *   - dashboard.html calls back into Kotlin via @JavascriptInterface for actions
 *
 * The top bar with scan controls is native Android (buttons stay responsive
 * even while the WebView is rendering a large table).
 * Everything below the controls is the WebView dashboard.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var config: ConfigManager
    private lateinit var webView: WebView
    private lateinit var btnMonitor: MaterialButton
    private lateinit var btnNifty: MaterialButton
    private lateinit var btnNasdaq: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var txtStatus: TextView
    private lateinit var controlBar: LinearLayout

    private val gson = Gson()
    private var isServiceRunning = false

    // ── Polling-based UI refresh (replaces unreliable broadcasts on MIUI) ──
    private val pollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastSeenDiscoveryVersion = 0L
    private var lastSeenPortfolioVersion = 0L
    private var wasDiscoveryRunning = false
    private val POLL_INTERVAL_MS = 500L // 500ms — keeps UI in sync with notification progress

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollForUpdates()
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun pollForUpdates() {
        val holder = ScanServiceResultHolder

        // Check for discovery updates
        if (holder.discoveryVersion != lastSeenDiscoveryVersion) {
            lastSeenDiscoveryVersion = holder.discoveryVersion

            refreshStatusBar()

            if (holder.isDiscoveryRunning) {
                wasDiscoveryRunning = true
                val p = holder.discoveryProgress
                val t = holder.discoveryTotal
                val m = holder.discoveryMarket
                webView.evaluateJavascript(
                    "window.updateProgress($p, $t, '${m.replace("'", "\\'")}')", null
                )
                pushDiscoveryResultsToWebView()
            } else if (wasDiscoveryRunning) {
                // Scan just finished
                wasDiscoveryRunning = false
                pushDiscoveryResultsToWebView()
                webView.evaluateJavascript("window.scanComplete()", null)
                btnStop.visibility = View.GONE
            }
        }

        // Check for portfolio updates
        if (holder.portfolioVersion != lastSeenPortfolioVersion) {
            lastSeenPortfolioVersion = holder.portfolioVersion
            refreshStatusBar()
            pushPortfolioResultsToWebView()
        }
    }

    // Keep broadcast receiver as fallback for immediate delivery when it works
    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ScanService.BROADCAST_SCAN_UPDATE -> {
                    refreshStatusBar()
                    pushPortfolioResultsToWebView()
                    lastSeenPortfolioVersion = ScanServiceResultHolder.portfolioVersion
                }
                ScanService.BROADCAST_DISCOVERY_UPDATE -> {
                    // Force an immediate poll cycle so updates are instant when broadcasts work
                    pollForUpdates()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()

        config = ConfigManager(this)

        // Setups-tab sliders are session-only by design. Clear any lingering
        // session override on a fresh MainActivity launch so reopening the app
        // always gives the user the Settings-tab defaults (per product spec).
        com.signalscope.app.data.SessionWeights.override = null

        setupWebView()
        setupButtons()
        requestNotificationPermission()
        refreshStatusBar()

        // Load cached discovery results from disk so they survive process death
        if (ScanServiceResultHolder.lastDiscoveryResult == null) {
            val cached = DiscoveryResultStore.loadLatest(this)
            if (cached != null) {
                ScanServiceResultHolder.lastDiscoveryResult = cached
                Log.d(TAG, "Restored ${cached.allStocks.size} cached discovery stocks (${cached.market})")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(ScanService.BROADCAST_SCAN_UPDATE)
            addAction(ScanService.BROADCAST_DISCOVERY_UPDATE)
        }
        ContextCompat.registerReceiver(this, scanReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        isServiceRunning = config.serviceRunning

        // Restore from disk if in-memory results were lost (process death)
        if (ScanServiceResultHolder.lastDiscoveryResult == null) {
            val cached = DiscoveryResultStore.loadLatest(this)
            if (cached != null) {
                ScanServiceResultHolder.lastDiscoveryResult = cached
                Log.d(TAG, "Restored ${cached.allStocks.size} cached discovery stocks (${cached.market})")
            }
        }

        // Track if discovery is currently running
        wasDiscoveryRunning = ScanServiceResultHolder.isDiscoveryRunning
        btnStop.visibility = if (wasDiscoveryRunning) View.VISIBLE else View.GONE

        refreshStatusBar()

        // Push whatever results we have (live or cached) to WebView
        pushDiscoveryResultsToWebView()
        pushPortfolioResultsToWebView()

        // Start polling for live updates (reliable even on MIUI)
        pollHandler.removeCallbacks(pollRunnable)
        pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        pollHandler.removeCallbacks(pollRunnable)
        try { unregisterReceiver(scanReceiver) } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════
    // LAYOUT (programmatic — no XML dependency)
    // ═══════════════════════════════════════════════════════

    private fun buildLayout() {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFf8fafc.toInt())
        }

        // ── Top bar: status + controls ──
        controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xFFffffff.toInt())
        }

        // Status row
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        // Logo ImageView — loads assets/logo.png (same image used inside the WebView).
        // Falls back to the launcher icon if the asset is missing.
        val logo = ImageView(this).apply {
            try {
                assets.open("logo.png").use {
                    setImageBitmap(android.graphics.BitmapFactory.decodeStream(it))
                }
            } catch (e: Exception) {
                Log.w(TAG, "logo.png not found in assets, falling back to launcher icon", e)
                setImageResource(com.signalscope.app.R.mipmap.ic_launcher)
            }
            val size = dp(30)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(8) }
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "SignalScope"
        }
        statusRow.addView(logo)

        txtStatus = TextView(this).apply {
            text = "Ready"
            textSize = 14f
            setTextColor(0xFF0f172a.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusRow.addView(txtStatus)

        val btnSettings = TextView(this).apply {
            text = "⚙"
            textSize = 20f
            setPadding(dp(8), 0, dp(4), 0)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        statusRow.addView(btnSettings)
        controlBar.addView(statusRow)

        // ── Single compact action row: [Monitor | Nifty | Nasdaq] ──
        // Saves ~50dp vs the old 3-row layout; uses horizontal space that was wasted by short labels.
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }

        btnMonitor = MaterialButton(this).apply {
            text = "▶ Monitor"
            textSize = 11f; isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF059669.toInt())
            cornerRadius = dp(8)
            // Slightly wider weight so the toggle stays prominent
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1.2f).apply { marginEnd = dp(4) }
            setPadding(dp(4), 0, dp(4), 0)
        }
        actionRow.addView(btnMonitor)

        btnNifty = MaterialButton(this).apply {
            text = "🇮🇳 Nifty 500"
            textSize = 11f; isAllCaps = false
            setBackgroundColor(0xFF2563eb.toInt())
            cornerRadius = dp(8)
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                marginStart = dp(2); marginEnd = dp(2)
            }
            setPadding(dp(4), 0, dp(4), 0)
        }
        actionRow.addView(btnNifty)

        btnNasdaq = MaterialButton(this).apply {
            text = "🇺🇸 Nasdaq"
            textSize = 11f; isAllCaps = false
            setBackgroundColor(0xFF2563eb.toInt())
            cornerRadius = dp(8)
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(4) }
            setPadding(dp(4), 0, dp(4), 0)
        }
        actionRow.addView(btnNasdaq)
        controlBar.addView(actionRow)

        // Stop button: overlays the same slot only while a scan is running. Thin + centered.
        btnStop = MaterialButton(this).apply {
            text = "⏹ Stop Scan"
            textSize = 11f; isAllCaps = false
            setBackgroundColor(0xFFdc2626.toInt())
            cornerRadius = dp(8)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(32)
            ).apply { topMargin = dp(4) }
        }
        controlBar.addView(btnStop)

        root.addView(controlBar)

        // ── WebView: the dashboard ──
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setBackgroundColor(0xFFf8fafc.toInt())
        }
        root.addView(webView)

        setContentView(root)
    }

    // ═══════════════════════════════════════════════════════
    // WEBVIEW SETUP
    // ═══════════════════════════════════════════════════════

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true // needed for file:///android_asset/dashboard.html
            allowFileAccessFromFileURLs = false // block JS from reading other local files
            allowUniversalAccessFromFileURLs = false // block cross-origin file access
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
        }

        // Add JS bridge so dashboard.html can call Kotlin
        webView.addJavascriptInterface(DashboardBridge(), "Android")

        // Load dashboard from assets
        webView.loadUrl("file:///android_asset/dashboard.html")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Push any existing results once page is loaded
                pushDiscoveryResultsToWebView()
                if (ScanServiceResultHolder.lastZerodhaResult != null) {
                    pushPortfolioResultsToWebView()
                } else {
                    // Cold start (process was killed): rehydrate Portfolio tab from
                    // the last persisted snapshot so the user doesn't see an empty
                    // table while waiting for the next 15-min scan tick.
                    PortfolioResultStore.load(applicationContext)?.let { snap ->
                        val escaped = snap.payloadJson
                            .replace("\\", "\\\\")
                            .replace("'", "\\'")
                        webView.evaluateJavascript(
                            "window.updatePortfolioData('$escaped')", null
                        )
                        Log.d(TAG, "Rehydrated portfolio from disk snapshot")
                    }
                }
                // Fetch sector data in background
                fetchSectorData()
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // JS BRIDGE — dashboard.html can call these from JavaScript
    // ═══════════════════════════════════════════════════════

    inner class DashboardBridge {

        @JavascriptInterface
        fun triggerNiftyScan() {
            runOnUiThread { startDiscoveryScan(ScanService.ACTION_DISCOVERY_NIFTY500) }
        }

        @JavascriptInterface
        fun triggerNasdaqScan() {
            runOnUiThread { startDiscoveryScan(ScanService.ACTION_DISCOVERY_NASDAQ100) }
        }

        @JavascriptInterface
        fun stopScan() {
            runOnUiThread { stopDiscoveryScan() }
        }

        // ── Session-only scoring-weight tunables (Setups tab sliders) ──
        // Returns persisted Settings defaults as JSON. Setups tab uses this
        // on render to initialize sliders — NEVER returns the session override.
        @JavascriptInterface
        fun getPersistedWeightsJson(): String {
            return try {
                gson.toJson(config.persistedScoringWeights)
            } catch (e: Throwable) {
                Log.e(TAG, "getPersistedWeightsJson failed", e); "{}"
            }
        }

        // Called from Setups-tab slider oninput. Patches just the given field into
        // the session override (stays in memory, cleared on process restart). Next
        // scan picks the value up because ConfigManager.scoringWeights checks the
        // override first.
        @JavascriptInterface
        fun setSessionWeight(key: String, value: Double) {
            val base = com.signalscope.app.data.SessionWeights.override
                ?: config.persistedScoringWeights
            val updated = when (key) {
                "buyMacdPctlThreshold"    -> base.copy(buyMacdPctlThreshold = value)
                "profitMacdPctlThreshold" -> base.copy(profitMacdPctlThreshold = value)
                "minSlopeMagnitude"       -> base.copy(minSlopeMagnitude = value)
                "goldenBuyMaxSlope"       -> base.copy(goldenBuyMaxSlope = value)
                else -> {
                    Log.w(TAG, "setSessionWeight: unknown key '$key'"); return
                }
            }
            com.signalscope.app.data.SessionWeights.override = updated
            Log.i(TAG, "session weight '$key' -> $value (override now active)")
        }

        // Clear session override — reverts to Settings defaults without restarting.
        @JavascriptInterface
        fun resetSessionWeights() {
            com.signalscope.app.data.SessionWeights.override = null
            Log.i(TAG, "session weight override cleared")
        }

        @JavascriptInterface
        fun triggerPortfolioScan() {
            runOnUiThread {
                // Ensure service is running before triggering portfolio scan
                ensureServiceRunning()
                startService(ScanService.createIntent(this@MainActivity, ScanService.ACTION_SCAN_NOW))
                Toast.makeText(this@MainActivity, "Scanning portfolio...", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun fetchSectors() {
            fetchSectorData()
        }

        @JavascriptInterface
        fun openSettings() {
            runOnUiThread {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }

        @JavascriptInterface
        fun openDocumentation() {
            runOnUiThread {
                try {
                    // Copy PDF from assets to cache dir so it can be opened via FileProvider/Intent
                    val pdfFile = java.io.File(cacheDir, "SignalScope_Documentation.pdf")
                    assets.open("SignalScope_Documentation.pdf").use { input ->
                        pdfFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this@MainActivity,
                        "$packageName.fileprovider",
                        pdfFile
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/pdf")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Open Documentation"))
                } catch (e: Exception) {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "No PDF viewer found. Install a PDF reader app.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        @JavascriptInterface
        fun getLastScanTime(): String {
            val ts = config.lastPortfolioScan
            return if (ts > 0) SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts)) else "Never"
        }

        @JavascriptInterface
        fun hasAiEnabled(): Boolean = config.hasLlmCredentials

        /** Read the most recent [limit] entries from the GTT activity audit log
         *  (newest first). Returns a JSON array of {ts, action, sym, …} objects. */
        @JavascriptInterface
        fun getGttActivityLog(limit: Int = 500): String {
            return try {
                com.signalscope.app.data.GttActivityLog.readRecent(applicationContext, limit).toString()
            } catch (e: Exception) {
                Log.e(TAG, "getGttActivityLog failed", e)
                "[]"
            }
        }

        /** Total event count in the activity log (for the audit modal header). */
        @JavascriptInterface
        fun getGttActivityCount(): Int =
            com.signalscope.app.data.GttActivityLog.count(applicationContext)

        /** Wipe the activity log — exposed in case the user wants a clean slate
         *  before a fresh trading session. */
        @JavascriptInterface
        fun clearGttActivityLog() {
            com.signalscope.app.data.GttActivityLog.clear(applicationContext)
        }

        /** Fetch GTT execution history for calibration insights */
        @JavascriptInterface
        fun fetchGttExecutionData(): String {
            return try {
                val summary = com.signalscope.app.network.ZerodhaClient(config).analyzeGttExecutionHistory()
                if (summary == null) {
                    "{\"error\": \"Failed to fetch GTT data\"}"
                } else {
                    // Convert to JSON for JavaScript
                    val json = org.json.JSONObject()
                    json.put("totalGtts", summary.totalGtts)
                    json.put("totalExecuted", summary.totalExecuted)
                    json.put("overallExecutionRate", String.format("%.1f", summary.overallExecutionRate * 100))

                    val bySymbolArray = org.json.JSONArray()
                    summary.bySymbol.forEach { stat ->
                        val obj = org.json.JSONObject()
                        obj.put("symbol", stat.symbol)
                        obj.put("totalGtts", stat.totalGtts)
                        obj.put("executedCount", stat.executedCount)
                        obj.put("activeCount", stat.activeCount)
                        obj.put("cancelledCount", stat.cancelledCount)
                        obj.put("executionRate", String.format("%.1f", stat.executionRate * 100))
                        obj.put("avgTriggerDeviation", if (stat.avgTriggerDeviation != null) String.format("%.2f", stat.avgTriggerDeviation) else "—")
                        bySymbolArray.put(obj)
                    }
                    json.put("bySymbol", bySymbolArray)
                    json.toString()
                }
            } catch (e: Exception) {
                Log.e(TAG, "GTT execution data fetch failed", e)
                "{\"error\": \"${e.message}\"}"
            }
        }

        /** Called from detail modal — analyzes why a stock has pulled back */
        @JavascriptInterface
        fun analyzePullback(symbol: String, price: Double, ema21PctDiff: Double,
                            macdPhase: String, profitScore: Int, protectScore: Int,
                            rsi: Double = 0.0, adx: Double = 0.0,
                            support: Double = 0.0, resistance: Double = 0.0) {
            thread(isDaemon = true) {
                // Guard the ENTIRE body — any unexpected exception must still
                // deliver a callback, otherwise the spinner spins forever.
                val text: String = try {
                    val result = StockAiClient.analyzePullback(
                        config, symbol, price, ema21PctDiff, macdPhase, profitScore, protectScore,
                        if (rsi > 0) rsi else null,
                        if (adx > 0) adx else null,
                        if (support > 0) support else null,
                        if (resistance > 0) resistance else null
                    )
                    when (result) {
                        is StockAiClient.AiResult.Success -> result.text
                        is StockAiClient.AiResult.Error -> "⚠ ${result.message}"
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Pullback thread crashed for $symbol", e)
                    "⚠ AI analysis crashed: ${e.message ?: e.javaClass.simpleName}"
                }
                deliverAiCallback("window.onPullbackResult", text)
            }
        }

        /** Follow-up question about company fundamentals after pullback analysis */
        @JavascriptInterface
        fun askAboutCompany(symbol: String, price: Double, userQuestion: String, previousAnalysis: String = "") {
            thread(isDaemon = true) {
                val text: String = try {
                    val result = StockAiClient.askFollowUpQuestion(
                        config, symbol, userQuestion, price, previousAnalysis
                    )
                    when (result) {
                        is StockAiClient.AiResult.Success -> result.text
                        is StockAiClient.AiResult.Error -> "⚠ ${result.message}"
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Follow-up question crashed for $symbol", e)
                    "⚠ Question analysis crashed: ${e.message ?: e.javaClass.simpleName}"
                }
                deliverAiCallback("window.onFollowUpResult", text)
            }
        }

        /** Called from detail modal — full stock outlook analysis */
        @JvmOverloads
        @JavascriptInterface
        fun analyzeOutlook(symbol: String, price: Double, buyScore: Int,
                           profitScore: Int, protectScore: Int, sellIntent: String,
                           macdPhase: String, macdSlope: Double, rsi: Double,
                           sma200: Double, ema21PctDiff: Double, rrRatio: Double, priceVel: Double,
                           isInPortfolio: Boolean = false) {
            thread(isDaemon = true) {
                val text: String = try {
                    val result = StockAiClient.analyzeOutlook(
                        config, symbol, price, buyScore, profitScore, protectScore, sellIntent,
                        macdPhase, macdSlope, if (rsi == 0.0) null else rsi,
                        if (sma200 == 0.0) null else sma200,
                        ema21PctDiff, rrRatio, priceVel, isInPortfolio
                    )
                    when (result) {
                        is StockAiClient.AiResult.Success -> result.text
                        is StockAiClient.AiResult.Error -> "⚠ ${result.message}"
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Outlook thread crashed for $symbol", e)
                    "⚠ AI analysis crashed: ${e.message ?: e.javaClass.simpleName}"
                }
                deliverAiCallback("window.onOutlookResult", text)
            }
        }

        /**
         * Called from the 📉 Pullback tab "LLM Analyse All Pullbacks" button.
         * Receives a JSON array of pullback stocks, runs a single batched LLM call
         * that scores each stock's entry quality, then delivers the result as a
         * formatted markdown table back to JS via window.onPullbackBatchResult(text).
         *
         * Each stock entry includes: symbol, price, rsi, macdPhase, ema21PctDiff,
         * atr, support, resistance, buyScore, buySignal, sma200, adx, minBarsFilter.
         */
        @JavascriptInterface
        fun analyseDeepPullbacks(stocksJson: String) {
            thread(isDaemon = true) {
                val text: String = try {
                    val result = StockAiClient.analyseDeepPullbackBatch(config, stocksJson)
                    when (result) {
                        is StockAiClient.AiResult.Success -> result.text
                        is StockAiClient.AiResult.Error -> "⚠ ${result.message}"
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Deep pullback batch analysis crashed", e)
                    "⚠ Analysis crashed: ${e.message ?: e.javaClass.simpleName}"
                }
                deliverAiCallback("window.onPullbackBatchResult", text)
            }
        }

        /**
         * Returns the merged recent-alerts list as a JSON array string.
         * Combines:
         *   - ScanService intraday alerts (from lastZerodhaResult.alerts)
         *   - DPM watchdog alerts (from macdWatchdogAlerts)
         * Both lists are deduped by symbol+alertType, sorted with most-severe first.
         * Used by the dashboard alert panel so MACD_FLIP firings are visible alongside
         * BOOK_PROFIT / PROTECT_CAPITAL / PEAK_WARNING.
         */
        @JavascriptInterface
        fun getRecentAlerts(): String {
            return try {
                val zerodha = ScanServiceResultHolder.lastZerodhaResult
                val intraday = zerodha?.alerts ?: emptyList()
                val watchdog = synchronized(ScanServiceResultHolder.macdWatchdogAlerts) {
                    ScanServiceResultHolder.macdWatchdogAlerts.toList()
                }
                val softSniper = synchronized(ScanServiceResultHolder.softSniperAlerts) {
                    ScanServiceResultHolder.softSniperAlerts.toList()
                }
                // Severity rank — lower = more severe (sorted ascending).
                // SOFT_SNIPER ranked between MACD_FLIP and PROTECT_CAPITAL: it's a
                // GTT-placement event (action taken) so it outranks plain advisories
                // like BOOK_PROFIT, but the hard watchdog (MACD_FLIP) still beats it.
                fun rank(t: com.signalscope.app.data.AlertType): Int = when (t) {
                    com.signalscope.app.data.AlertType.STRONG_EXIT     -> 0
                    com.signalscope.app.data.AlertType.MACD_FLIP       -> 1
                    com.signalscope.app.data.AlertType.SOFT_SNIPER     -> 2
                    com.signalscope.app.data.AlertType.PROTECT_CAPITAL -> 3
                    com.signalscope.app.data.AlertType.BOOK_PROFIT     -> 4
                    com.signalscope.app.data.AlertType.PEAK_WARNING    -> 5
                    com.signalscope.app.data.AlertType.GOLDEN_BUY      -> 6
                    com.signalscope.app.data.AlertType.STRONG_BUY      -> 7
                    else -> 99
                }
                val merged = (intraday + watchdog + softSniper)
                    .distinctBy { "${it.symbol}|${it.alertType}" }
                    .sortedBy { rank(it.alertType) }
                    .map { a -> mapOf(
                        "symbol" to a.symbol,
                        "name" to a.name,
                        "alertType" to a.alertType.name,
                        "message" to a.message,
                        "sellScore" to a.sellScore,
                        "buyScore" to a.buyScore,
                        "price" to a.price,
                        "macdPhase" to a.macdPhase,
                        "source" to a.source
                    ) }
                gson.toJson(merged)
            } catch (e: Throwable) {
                Log.e(TAG, "getRecentAlerts failed", e)
                "[]"
            }
        }

        // ── GTT (Good-Till-Triggered) bridge methods ──────────────────

        /**
         * Fetch existing Zerodha GTT triggers. Returns a JSON array string:
         * [{id,tradingsymbol,exchange,type,status,triggerPrice,limitPrice,quantity,transactionType,lastPrice}, ...]
         * Always returns a JSON string (never null) so JS can JSON.parse unconditionally.
         */
        @JavascriptInterface
        fun getGttTriggersJson(): String {
            return try {
                val zc = com.signalscope.app.network.ZerodhaClient(config)
                when (val r = zc.fetchGttTriggers()) {
                    is com.signalscope.app.network.ZerodhaClient.GttListResult.Success -> {
                        val sb = StringBuilder("[")
                        r.triggers.forEachIndexed { i, t ->
                            if (i > 0) sb.append(",")
                            sb.append("{")
                            sb.append("\"id\":").append(t.id).append(",")
                            sb.append("\"tradingsymbol\":\"").append(t.tradingsymbol).append("\",")
                            sb.append("\"exchange\":\"").append(t.exchange).append("\",")
                            sb.append("\"type\":\"").append(t.triggerType).append("\",")
                            sb.append("\"status\":\"").append(t.status).append("\",")
                            sb.append("\"triggerPrice\":").append(t.triggerPrice).append(",")
                            sb.append("\"limitPrice\":").append(t.limitPrice).append(",")
                            sb.append("\"quantity\":").append(t.quantity).append(",")
                            sb.append("\"transactionType\":\"").append(t.transactionType).append("\",")
                            sb.append("\"lastPrice\":").append(t.lastPrice)
                            if (t.triggerPriceAux != null) {
                                sb.append(",\"triggerPriceAux\":").append(t.triggerPriceAux)
                            }
                            sb.append("}")
                        }
                        sb.append("]")
                        "{\"ok\":true,\"triggers\":${sb}}"
                    }
                    is com.signalscope.app.network.ZerodhaClient.GttListResult.Expired ->
                        "{\"ok\":false,\"expired\":true,\"error\":\"${r.message.replace("\"","")}\",\"triggers\":[]}"
                    is com.signalscope.app.network.ZerodhaClient.GttListResult.Failure ->
                        "{\"ok\":false,\"error\":\"${r.message.replace("\"","")}\",\"triggers\":[]}"
                }
            } catch (e: Throwable) {
                Log.e(TAG, "getGttTriggersJson failed", e)
                "{\"ok\":false,\"error\":\"${(e.message ?: "exception").replace("\"","")}\",\"triggers\":[]}"
            }
        }

        /**
         * Bump a GTT trigger to the next round price-step above its current trigger.
         * Step rules (INR): <100→1, <500→5, <2000→10, ≥2000→50.
         * Called with the currently-known trigger price so we round the right baseline
         * even if the server-side value has drifted; returns a JSON status for the JS caller.
         */
        @JavascriptInterface
        fun bumpGttTrigger(id: Long, currentTrigger: Double): String {
            thread(isDaemon = true) {
                val text: String = try {
                    val step = when {
                        currentTrigger < 100.0 -> 1.0
                        currentTrigger < 500.0 -> 5.0
                        currentTrigger < 2000.0 -> 10.0
                        else -> 50.0
                    }
                    // Next multiple of `step` strictly above currentTrigger
                    val next = (kotlin.math.floor(currentTrigger / step) + 1.0) * step
                    val zc = com.signalscope.app.network.ZerodhaClient(config)
                    // Pass currentTrigger as hint so the modify helper can identify
                    // WHICH leg to bump on two-leg (OCO) GTTs — otherwise Kite rejects
                    // with "Two leg condition triggers expect two trigger values."
                    when (val r = zc.modifyGttTriggerPrice(id, next, oldTriggerHint = currentTrigger)) {
                        is com.signalscope.app.network.ZerodhaClient.GttModifyResult.Success ->
                            "{\"ok\":true,\"id\":${r.id},\"newTrigger\":${r.newTriggerPrice}}"
                        is com.signalscope.app.network.ZerodhaClient.GttModifyResult.Failure ->
                            "{\"ok\":false,\"error\":\"${r.message.replace("\"","")}\"}"
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "bumpGttTrigger failed", e)
                    "{\"ok\":false,\"error\":\"${(e.message ?: "exception").replace("\"","")}\"}"
                }
                deliverAiCallback("window.onGttBumpResult", text)
            }
            return "{\"ok\":true,\"pending\":true}"
        }

        /**
         * Editable GTT modify — arbitrary prices for either/both legs. Called
         * from the in-app GTT edit modal. For single-leg pass newTarget=0 and
         * targetLimit=0; for two-leg OCO both must be > 0. Limit prices are
         * the limit order prices (typically slightly inside the trigger to
         * ensure fills — ~2% below SL trigger, ~1% below target trigger).
         * Dispatches result via window.onGttEditResult.
         */
        @JavascriptInterface
        fun setGttTriggerPrices(
            id: Long,
            newSlTrigger: Double, newSlLimit: Double,
            newTargetTrigger: Double, newTargetLimit: Double
        ): String {
            thread(isDaemon = true) {
                val text: String = try {
                    val zc = com.signalscope.app.network.ZerodhaClient(config)
                    val isTwoLeg = newTargetTrigger > 0.0
                    val triggers = if (isTwoLeg) listOf(newSlTrigger, newTargetTrigger) else listOf(newSlTrigger)
                    val limits = if (isTwoLeg) listOf(newSlLimit, newTargetLimit) else listOf(newSlLimit)
                    when (val r = zc.setGttTriggerPrices(id, triggers, limits)) {
                        is com.signalscope.app.network.ZerodhaClient.GttModifyResult.Success ->
                            "{\"ok\":true,\"id\":${r.id}}"
                        is com.signalscope.app.network.ZerodhaClient.GttModifyResult.Failure ->
                            "{\"ok\":false,\"error\":\"${r.message.replace("\"","")}\"}"
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "setGttTriggerPrices failed", e)
                    "{\"ok\":false,\"error\":\"${(e.message ?: "exception").replace("\"","")}\"}"
                }
                deliverAiCallback("window.onGttEditResult", text)
            }
            return "{\"ok\":true,\"pending\":true}"
        }

        /** Delete/cancel a GTT by id. Dispatches via window.onGttDeleteResult. */
        @JavascriptInterface
        fun deleteGttTrigger(id: Long): String {
            thread(isDaemon = true) {
                val text: String = try {
                    val zc = com.signalscope.app.network.ZerodhaClient(config)
                    when (val r = zc.deleteGttTrigger(id)) {
                        is com.signalscope.app.network.ZerodhaClient.GttDeleteResult.Success ->
                            "{\"ok\":true,\"id\":${r.id}}"
                        is com.signalscope.app.network.ZerodhaClient.GttDeleteResult.Failure ->
                            "{\"ok\":false,\"error\":\"${r.message.replace("\"","")}\"}"
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "deleteGttTrigger failed", e)
                    "{\"ok\":false,\"error\":\"${(e.message ?: "exception").replace("\"","")}\"}"
                }
                deliverAiCallback("window.onGttDeleteResult", text)
            }
            return "{\"ok\":true,\"pending\":true}"
        }

        /**
         * Create a one-click OCO (two-leg) SELL GTT with both a stop-loss leg
         * (trigger < LTP) and a target leg (trigger > LTP). This is the "set
         * SL + target at projected S/R in a single click" flow.
         *
         * Kite API rule: trigger values must be within ±25% of last_price.
         * No minimum-distance floor — user wants SL as tight as possible to
         * minimize losses. Only safety pad is 0.1% to keep triggers strictly
         * on their correct side of LTP (so they don't fire instantly).
         * If the caller's requested triggers violate the ±25% limit we clamp
         * them and return the effective values in the JS callback.
         */
        @JavascriptInterface
        fun createOcoSellGtt(
            symbol: String, exchange: String, quantity: Int,
            slTrigger: Double, targetTrigger: Double, lastPrice: Double
        ) {
            thread(isDaemon = true) {
                val text: String = try {
                    if (quantity <= 0) throw IllegalArgumentException("quantity must be > 0")
                    if (lastPrice <= 0) throw IllegalArgumentException("lastPrice invalid")
                    // Clamp triggers only to Kite's ±25% hard limit. No 5% floor —
                    // user wants SL as tight as possible to minimize loss beyond volatility.
                    // Tiny 0.1% pad keeps SL strictly below LTP so it doesn't fire instantly.
                    val slMax = lastPrice * 0.999  // strictly below LTP (≥0.1% below)
                    val slMin = lastPrice * 0.75   // at most 25% below (Kite hard limit)
                    val tgMin = lastPrice * 1.001  // strictly above LTP (≥0.1% above)
                    val tgMax = lastPrice * 1.25   // at most 25% above (Kite hard limit)

                    var sl = slTrigger.coerceIn(slMin, slMax)
                    var tg = targetTrigger.coerceIn(tgMin, tgMax)
                    // Round to 2 decimals — Kite rejects anything finer than a tick
                    sl = Math.round(sl * 20.0) / 20.0   // 0.05 ticks
                    tg = Math.round(tg * 20.0) / 20.0

                    val zc = com.signalscope.app.network.ZerodhaClient(config)
                    val orders = listOf(
                        com.signalscope.app.network.ZerodhaClient.GttOrderSpec("SELL", quantity, sl),
                        com.signalscope.app.network.ZerodhaClient.GttOrderSpec("SELL", quantity, tg)
                    )
                    when (val r = zc.createGttTrigger(
                        type = "two-leg",
                        exchange = exchange,
                        tradingsymbol = symbol,
                        triggerValues = listOf(sl, tg),
                        lastPrice = lastPrice,
                        orders = orders
                    )) {
                        is com.signalscope.app.network.ZerodhaClient.GttCreateResult.Success ->
                            "{\"ok\":true,\"id\":${r.id},\"sl\":$sl,\"target\":$tg,\"symbol\":\"$symbol\"}"
                        is com.signalscope.app.network.ZerodhaClient.GttCreateResult.Failure ->
                            "{\"ok\":false,\"error\":\"${r.message.replace("\"","")}\",\"symbol\":\"$symbol\"}"
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "createOcoSellGtt failed", e)
                    "{\"ok\":false,\"error\":\"${(e.message ?: "exception").replace("\"","")}\",\"symbol\":\"$symbol\"}"
                }
                deliverAiCallback("window.onOcoGttResult", text)
            }
        }

        /** Create a single-leg SELL GTT (either SL or target alone). */
        @JavascriptInterface
        fun createSingleSellGtt(
            symbol: String, exchange: String, quantity: Int,
            triggerPrice: Double, lastPrice: Double, kind: String /* "SL" or "TARGET" */
        ) {
            thread(isDaemon = true) {
                val text: String = try {
                    if (quantity <= 0) throw IllegalArgumentException("quantity must be > 0")
                    val pct = (triggerPrice - lastPrice) / lastPrice
                    // No 5% minimum floor — user wants tight SLs. Only enforce side
                    // of LTP (with a tiny 0.1% pad) and the ±25% Kite hard limit.
                    if (kind == "SL" && pct >= -0.001) throw IllegalArgumentException("SL trigger must be strictly below LTP")
                    if (kind == "TARGET" && pct <= 0.001) throw IllegalArgumentException("Target trigger must be strictly above LTP")
                    if (Math.abs(pct) > 0.25) throw IllegalArgumentException("Trigger beyond ±25% of LTP (Kite hard limit)")
                    val trig = Math.round(triggerPrice * 20.0) / 20.0
                    val zc = com.signalscope.app.network.ZerodhaClient(config)
                    val orders = listOf(
                        com.signalscope.app.network.ZerodhaClient.GttOrderSpec("SELL", quantity, trig)
                    )
                    when (val r = zc.createGttTrigger(
                        type = "single", exchange = exchange, tradingsymbol = symbol,
                        triggerValues = listOf(trig), lastPrice = lastPrice, orders = orders
                    )) {
                        is com.signalscope.app.network.ZerodhaClient.GttCreateResult.Success ->
                            "{\"ok\":true,\"id\":${r.id},\"trigger\":$trig,\"kind\":\"$kind\",\"symbol\":\"$symbol\"}"
                        is com.signalscope.app.network.ZerodhaClient.GttCreateResult.Failure ->
                            "{\"ok\":false,\"error\":\"${r.message.replace("\"","")}\",\"kind\":\"$kind\",\"symbol\":\"$symbol\"}"
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "createSingleSellGtt failed", e)
                    "{\"ok\":false,\"error\":\"${(e.message ?: "exception").replace("\"","")}\",\"kind\":\"$kind\",\"symbol\":\"$symbol\"}"
                }
                deliverAiCallback("window.onOcoGttResult", text)
            }
        }

        /**
         * Bulk create OCO SELL GTTs for multiple portfolio holdings in one sequential run.
         *
         * Input JSON shape (array of specs):
         *   [{"symbol":"INFY","exchange":"NSE","qty":10,"sl":1450.0,"tgt":1700.0,"ltp":1600.0}, ...]
         *
         * Safety measures (user-requested "triple sure" profile):
         *   - 1-second Thread.sleep between API calls (Kite allows ~3/sec for order endpoints;
         *     1s is ~3× safer). Pre-flight count check is done from JS side via getGttTriggersJson.
         *   - Each leg clamped to ±25% of LTP (Kite hard limit). No 5% minimum
         *     floor — user wants SL as tight as possible.
         *   - Tick rounding to 0.05.
         *   - Per-stock failures are captured, not fatal — loop continues so a partial run
         *     still books whatever succeeded.
         *
         * Progress callbacks (into JS):
         *   window.onBulkGttProgress('{"i":N,"total":T,"symbol":"...","status":"ok|fail|skip","id":..., "error":"..."}')
         *   window.onBulkGttComplete('{"created":[...], "failed":[...], "total":T}')
         */
        @JavascriptInterface
        fun bulkCreateOcoSellGtts(stocksJson: String) {
            thread(isDaemon = true) {
                val created = mutableListOf<String>()
                val failed = mutableListOf<String>()
                try {
                    val arr = org.json.JSONArray(stocksJson)
                    val total = arr.length()
                    val zc = com.signalscope.app.network.ZerodhaClient(config)

                    // ── Idempotency sweep ──
                    // Fetch all active GTTs ONCE up-front and build a symbol→IDs map of
                    // existing SELL triggers. Before creating a fresh OCO per symbol, we
                    // delete every existing SELL GTT on that symbol. This turns "Bulk OCO"
                    // into an idempotent operation — users can re-tap it without piling up
                    // duplicates (which in live trading would fire stacked sell orders
                    // when an SL hits, wasting API slots and creating noise).
                    val existingBySymbol: Map<String, List<Long>> = try {
                        when (val r = zc.fetchGttTriggers()) {
                            is com.signalscope.app.network.ZerodhaClient.GttListResult.Success ->
                                r.triggers
                                    .filter { it.status == "active" && it.transactionType == "SELL" }
                                    .groupBy({ it.tradingsymbol }, { it.id })
                            else -> emptyMap()
                        }
                    } catch (_: Throwable) { emptyMap() }
                    var sweptCount = 0

                    for (i in 0 until total) {
                        val o = arr.getJSONObject(i)
                        val symbol = o.getString("symbol")
                        val exchange = o.optString("exchange", "NSE")
                        val qty = o.getInt("qty")
                        val ltp = o.getDouble("ltp")
                        var sl = o.getDouble("sl")
                        var tg = o.getDouble("tgt")

                        // Delete any pre-existing SELL GTTs on this symbol before creating
                        // the fresh one. 400ms spacing stays under Kite's ~3/s order limit.
                        existingBySymbol[symbol]?.forEach { oldId ->
                            try {
                                zc.deleteGttTrigger(oldId)
                                sweptCount++
                                Thread.sleep(400L)
                            } catch (e: Throwable) {
                                Log.w(TAG, "sweep: delete GTT $oldId for $symbol failed: ${e.message}")
                            }
                        }

                        val progressText: String = try {
                            if (qty <= 0) throw IllegalArgumentException("qty ≤ 0")
                            if (ltp <= 0) throw IllegalArgumentException("bad ltp")
                            // ±25% Kite hard limit only. 0.1% pad keeps trigger
                            // strictly on its correct side of LTP.
                            sl = sl.coerceIn(ltp * 0.75, ltp * 0.999)
                            tg = tg.coerceIn(ltp * 1.001, ltp * 1.25)
                            sl = Math.round(sl * 20.0) / 20.0
                            tg = Math.round(tg * 20.0) / 20.0
                            val orders = listOf(
                                com.signalscope.app.network.ZerodhaClient.GttOrderSpec("SELL", qty, sl),
                                com.signalscope.app.network.ZerodhaClient.GttOrderSpec("SELL", qty, tg)
                            )
                            when (val r = zc.createGttTrigger(
                                type = "two-leg", exchange = exchange, tradingsymbol = symbol,
                                triggerValues = listOf(sl, tg), lastPrice = ltp, orders = orders
                            )) {
                                is com.signalscope.app.network.ZerodhaClient.GttCreateResult.Success -> {
                                    created.add("{\"symbol\":\"$symbol\",\"id\":${r.id},\"sl\":$sl,\"tgt\":$tg}")
                                    "{\"i\":${i+1},\"total\":$total,\"symbol\":\"$symbol\",\"status\":\"ok\",\"id\":${r.id},\"sl\":$sl,\"tgt\":$tg}"
                                }
                                is com.signalscope.app.network.ZerodhaClient.GttCreateResult.Failure -> {
                                    failed.add("{\"symbol\":\"$symbol\",\"error\":\"${r.message.replace("\"","")}\"}")
                                    "{\"i\":${i+1},\"total\":$total,\"symbol\":\"$symbol\",\"status\":\"fail\",\"error\":\"${r.message.replace("\"","")}\"}"
                                }
                            }
                        } catch (e: Throwable) {
                            val msg = (e.message ?: "exception").replace("\"","")
                            failed.add("{\"symbol\":\"$symbol\",\"error\":\"$msg\"}")
                            "{\"i\":${i+1},\"total\":$total,\"symbol\":\"$symbol\",\"status\":\"fail\",\"error\":\"$msg\"}"
                        }
                        deliverAiCallback("window.onBulkGttProgress", progressText)
                        // Extra-safe delay between calls (user wants to be "triple sure").
                        // Kite's published limit for order endpoints is ~3/sec; 1s spacing is 3× under cap.
                        if (i < total - 1) {
                            try { Thread.sleep(1000L) } catch (_: InterruptedException) {}
                        }
                    }
                    val summary = "{\"created\":[${created.joinToString(",")}],\"failed\":[${failed.joinToString(",")}],\"total\":$total,\"swept\":$sweptCount}"
                    deliverAiCallback("window.onBulkGttComplete", summary)
                } catch (e: Throwable) {
                    Log.e(TAG, "bulkCreateOcoSellGtts failed", e)
                    val msg = (e.message ?: "exception").replace("\"","")
                    deliverAiCallback("window.onBulkGttComplete",
                        "{\"created\":[${created.joinToString(",")}],\"failed\":[${failed.joinToString(",")}],\"total\":0,\"error\":\"$msg\"}")
                }
            }
        }

        /**
         * Deliver a text payload to a WebView JS callback. Escapes defensively and guards
         * against the activity being destroyed mid-flight (old callback should no-op, not crash).
         */
        private fun deliverAiCallback(jsFn: String, text: String) {
            val escaped = try {
                text.replace("\\", "\\\\").replace("'", "\\'")
                    .replace("\n", "\\n").replace("\r", "")
            } catch (e: Throwable) {
                Log.e(TAG, "Escape failed", e)
                "⚠ result encoding failed"
            }
            runOnUiThread {
                try {
                    if (!isFinishing && !isDestroyed) {
                        webView.evaluateJavascript("$jsFn('$escaped')", null)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "evaluateJavascript failed for $jsFn", e)
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // BUTTON HANDLERS
    // ═══════════════════════════════════════════════════════

    private fun setupButtons() {
        btnMonitor.setOnClickListener {
            if (!config.isZerodhaConnected) {
                Toast.makeText(this, "Configure credentials in Settings first", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, SettingsActivity::class.java))
                return@setOnClickListener
            }
            if (isServiceRunning) stopMonitoring() else startMonitoring()
        }

        btnNifty.setOnClickListener {
            startDiscoveryScan(ScanService.ACTION_DISCOVERY_NIFTY500)
        }

        btnNasdaq.setOnClickListener {
            startDiscoveryScan(ScanService.ACTION_DISCOVERY_NASDAQ100)
        }

        btnStop.setOnClickListener {
            stopDiscoveryScan()
        }
    }

    // ═══════════════════════════════════════════════════════
    // SERVICE CONTROL
    // ═══════════════════════════════════════════════════════

    private fun ensureServiceRunning() {
        if (!isServiceRunning) {
            ContextCompat.startForegroundService(this,
                ScanService.createIntent(this, ScanService.ACTION_START))
            isServiceRunning = true
            config.serviceRunning = true
        }
    }

    private fun startMonitoring() {
        ensureServiceRunning()
        requestBatteryOptimizationExemption()
        refreshStatusBar()
        Toast.makeText(this, "Portfolio monitoring started", Toast.LENGTH_SHORT).show()
    }

    private fun stopMonitoring() {
        startService(ScanService.createIntent(this, ScanService.ACTION_STOP))
        isServiceRunning = false
        config.serviceRunning = false
        refreshStatusBar()
        Toast.makeText(this, "Monitoring stopped", Toast.LENGTH_SHORT).show()
    }

    private fun startDiscoveryScan(action: String) {
        ensureServiceRunning()
        // Discovery scans can take 15–40 minutes. On OEM-modded Androids
        // (MIUI, ColorOS, OneUI) the OS will kill the foreground service
        // mid-scan unless the app is exempt from battery optimization.
        // Prompting here is idempotent — Android shows nothing if already exempt.
        requestBatteryOptimizationExemption()
        startService(ScanService.createIntent(this, action))
        btnStop.visibility = View.VISIBLE
        val market = if (action.contains("NIFTY")) "NIFTY" else "NASDAQ 100"
        Toast.makeText(this, "$market discovery scan starting...", Toast.LENGTH_SHORT).show()
    }

    private fun stopDiscoveryScan() {
        startService(ScanService.createIntent(this, ScanService.ACTION_DISCOVERY_STOP))
        btnStop.visibility = View.GONE
        Toast.makeText(this, "Discovery scan stopping...", Toast.LENGTH_SHORT).show()
    }

    // ═══════════════════════════════════════════════════════
    // PUSH DATA TO WEBVIEW
    // ═══════════════════════════════════════════════════════

    private fun pushDiscoveryResultsToWebView() {
        // Access the service's static result holder
        val result = ScanServiceResultHolder.lastDiscoveryResult ?: return
        try {
            val payload = mapOf(
                "allStocks" to result.allStocks,
                "market" to result.market,
                "currency" to result.currency,
                "errors" to result.errors,
                "skipped" to result.skipped,
                "lastError" to result.lastError,
                "isScanning" to !result.isComplete,
                // Use discoveryProgress (stocks attempted = idx+1) so the app screen matches
                // the notification, which also counts attempts. totalScanned only counts
                // successfully analysed stocks, so it's always lower when rate-limits occur.
                "progress" to if (!result.isComplete) mapOf(
                    "current" to ScanServiceResultHolder.discoveryProgress,
                    "total" to result.totalSymbols
                ) else null
            )
            val json = gson.toJson(payload)
            // Escape for JS string
            val escaped = json.replace("\\", "\\\\").replace("'", "\\'")
            webView.evaluateJavascript("window.updateScanData('$escaped')", null)
        } catch (e: Exception) {
            Log.e(TAG, "Error pushing results to WebView", e)
        }
    }

    private fun pushPortfolioResultsToWebView() {
        val zerodha = ScanServiceResultHolder.lastZerodhaResult ?: return
        val allHoldings = mutableListOf<Map<String, Any?>>()

        // IST trading-date key for matching against macdWatchdogFiredKeys ("yyyy-MM-dd|SYMBOL")
        val istToday = run {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
            sdf.format(java.util.Date())
        }

        zerodha.holdings.forEach { h ->
            val a = h.analysis
            // Did the DPM histogram-watchdog fire for this stock today?
            // Holdings UI uses this to show a 🚨 indicator.
            val macdFlipFiredToday = ScanServiceResultHolder.isMacdWatchdogFired(h.symbol, istToday)
            // Tier 1.5 soft sniper firing — separate badge so user knows a 3% buffer
            // GTT is in place even before the harder watchdog confirms.
            val softSniperFiredToday = ScanServiceResultHolder.isSoftSniperFired(h.symbol, istToday)
            allHoldings.add(mapOf(
                "symbol" to h.symbol,
                "exchange" to h.exchange,
                "quantity" to h.quantity,
                "avgPrice" to h.avgPrice,
                "ltp" to h.ltp,
                "pnl" to h.pnl,
                "dayChange" to h.dayChange,
                "dayChangePct" to h.dayChangePct,
                "invested" to h.invested,
                "currentVal" to h.currentVal,
                "totalReturnPct" to h.totalReturnPct,
                "source" to "zerodha",
                "verdict" to h.verdict,
                "buyScore" to (a?.buyScore ?: 0),
                "profitScore" to (a?.profitScore ?: 0),
                "protectScore" to (a?.protectScore ?: 0),
                "sellIntent" to (a?.sellIntent ?: "HOLD"),
                "sellScore" to (a?.sellScore ?: 0),
                "macdPhase" to (a?.macdPhase ?: "—"),
                "macdSlope" to (a?.macdSlope ?: 0.0),
                "macdCurve" to (a?.macdCurve ?: emptyList<Double>()),
                "buySignal" to (a?.buySignal ?: "—"),
                "sellSignal" to (a?.sellSignal ?: "—"),
                "support" to (a?.support ?: 0.0),
                "resistance" to (a?.resistance ?: 0.0),
                "atr" to (a?.atr),
                "projectedCeiling" to (a?.projectedCeiling),
                "projectedFloor" to (a?.projectedFloor),
                "projectedMidpoint" to (a?.projectedMidpoint),
                "minBarsFilter" to (a?.minBarsFilter),
                "macdFlipFiredToday" to macdFlipFiredToday,
                "softSniperFiredToday" to softSniperFiredToday,
                "obvDivergence" to (a?.obvDivergence ?: false),
                "obvWeakness" to (a?.obvWeakness ?: false),
                "hasAnalysis" to (a != null)
            ))
        }

        if (allHoldings.isEmpty()) return

        try {
            val totalInvested = allHoldings.sumOf { (it["invested"] as? Double) ?: 0.0 }
            val totalCurrent = allHoldings.sumOf { (it["currentVal"] as? Double) ?: 0.0 }
            val totalPnl = totalCurrent - totalInvested
            val dayPnl = allHoldings.sumOf { (it["dayChange"] as? Double) ?: 0.0 }
            val sellAlerts = allHoldings.count {
                val intent = (it["sellIntent"] as? String) ?: "HOLD"
                intent != "HOLD"
            }

            val payload = mapOf(
                "holdings" to allHoldings,
                "summary" to mapOf(
                    "count" to allHoldings.size,
                    "invested" to totalInvested,
                    "current" to totalCurrent,
                    "totalPnl" to totalPnl,
                    "totalReturnPct" to if (totalInvested > 0) (totalPnl / totalInvested * 100) else 0.0,
                    "dayPnl" to dayPnl,
                    "sellAlerts" to sellAlerts
                ),
                // Epoch-millis the payload was built. Used by:
                //   1. dashboard.html's "Last scanned X min ago" banner
                //   2. PortfolioResultStore for staleness display after rehydrate
                "capturedAt" to System.currentTimeMillis()
            )
            val json = gson.toJson(payload)
            val escaped = json.replace("\\", "\\\\").replace("'", "\\'")
            webView.evaluateJavascript("window.updatePortfolioData('$escaped')", null)

            // Persist the exact JSON we just pushed so the Portfolio tab survives
            // process death (MIUI / OnePlus aggressive killing during app switching).
            // On cold start, MainActivity.onPageFinished will re-push this verbatim.
            PortfolioResultStore.save(applicationContext, json)
        } catch (e: Exception) {
            Log.e(TAG, "Error pushing portfolio to WebView", e)
        }
    }

    // ═══════════════════════════════════════════════════════
    // SECTOR DATA — fetched from Yahoo Finance in background
    // ═══════════════════════════════════════════════════════

    private val sectorIndices = listOf(
        Triple("Banking", "^NSEBANK", "NIFTY Bank"),
        Triple("IT", "^CNXIT", "NIFTY IT"),
        Triple("Pharma", "^CNXPHARMA", "NIFTY Pharma"),
        Triple("FMCG", "^CNXFMCG", "NIFTY FMCG"),
        Triple("Auto", "^CNXAUTO", "NIFTY Auto"),
        Triple("Energy", "^CNXENERGY", "NIFTY Energy"),
        Triple("Fin Services", "^CNXFIN", "NIFTY Fin Service"),
        Triple("Metal", "^CNXMETAL", "NIFTY Metal"),
        Triple("Realty", "^CNXREALTY", "NIFTY Realty"),
        Triple("Media", "^CNXMEDIA", "NIFTY Media")
    )

    @Volatile private var sectorFetchInProgress = false

    private fun fetchSectorData() {
        if (sectorFetchInProgress) return
        sectorFetchInProgress = true

        thread(isDaemon = true) {
            try {
                val sectors = mutableListOf<Map<String, Any?>>()

                for ((name, symbol, index) in sectorIndices) {
                    try {
                        val result = YahooFinanceClient.fetchCandles(symbol, "NSE")
                        if (result is YahooFinanceClient.CandleResult.Success && result.candles.size >= 2) {
                            val candles = result.candles
                            val latest = candles.last()
                            val prev = candles[candles.size - 2]
                            val dayChange = if (prev.close > 0) (latest.close - prev.close) / prev.close * 100.0 else 0.0

                            // Period stats (last 6 months or available)
                            val periodStart = if (candles.size > 120) candles[candles.size - 120] else candles.first()
                            val periodChange = if (periodStart.close > 0) (latest.close - periodStart.close) / periodStart.close * 100.0 else 0.0
                            val high = candles.takeLast(120).maxOfOrNull { it.high } ?: latest.high
                            val low = candles.takeLast(120).minOfOrNull { it.low } ?: latest.low

                            // Last 60 closing prices for sparkline chart
                            val closesForChart = candles.takeLast(60).map { it.close }

                            sectors.add(mapOf(
                                "name" to name,
                                "index" to index,
                                "symbol" to symbol,
                                "price" to latest.close,
                                "dayChange" to dayChange,
                                "periodChange" to periodChange,
                                "high" to high,
                                "low" to low,
                                "closes" to closesForChart
                            ))
                        }
                        Thread.sleep(600) // pace requests
                    } catch (e: Exception) {
                        Log.w(TAG, "Sector fetch error for $name", e)
                    }
                }

                if (sectors.isNotEmpty()) {
                    val json = gson.toJson(sectors)
                    val escaped = json.replace("\\", "\\\\").replace("'", "\\'")
                    runOnUiThread {
                        webView.evaluateJavascript("window.updateSectorData('$escaped')", null)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sector data fetch failed", e)
            } finally {
                sectorFetchInProgress = false
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // STATUS BAR
    // ═══════════════════════════════════════════════════════

    private fun refreshStatusBar() {
        isServiceRunning = config.serviceRunning

        if (isServiceRunning) {
            btnMonitor.text = "⏹ Stop"
            btnMonitor.setBackgroundColor(0xFFdc2626.toInt())
        } else {
            btnMonitor.text = "▶ Monitor"
            btnMonitor.setBackgroundColor(0xFF059669.toInt())
        }

        // Discovery scan status takes priority when running
        val holder = ScanServiceResultHolder
        if (holder.isDiscoveryRunning && holder.discoveryStatusText.isNotEmpty()) {
            txtStatus.text = holder.discoveryStatusText
            txtStatus.setTextColor(0xFF2563eb.toInt()) // blue for discovery
            return
        }

        // Otherwise show portfolio monitoring status
        if (isServiceRunning) {
            val lastScan = config.lastPortfolioScan
            val timeStr = if (lastScan > 0)
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastScan)) else "—"
            // Logo ImageView sits immediately to the left, so no brand prefix needed.
            txtStatus.text = "Monitoring · Last: $timeStr"
            txtStatus.setTextColor(0xFF059669.toInt())
        } else {
            // Show cached discovery info if available
            val cached = holder.lastDiscoveryResult
            if (cached != null && cached.isComplete) {
                val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(cached.timestamp))
                txtStatus.text = "${cached.market}: ${cached.allStocks.size} stocks · $timeStr"
                txtStatus.setTextColor(0xFF64748b.toInt()) // grey — stale data indicator
            } else {
                txtStatus.text = "Ready"
                txtStatus.setTextColor(0xFF0f172a.toInt())
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // PERMISSIONS
    // ═══════════════════════════════════════════════════════

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use OnBackPressedCallback", ReplaceWith("onBackPressedDispatcher"))
    override fun onBackPressed() {
        // If modal is open in WebView, close it instead of exiting
        webView.evaluateJavascript(
            "if(document.getElementById('modal').classList.contains('show')){closeDetail();true}else{false}",
            { result ->
                if (result == "false" || result == "null") {
                    super.onBackPressed()
                }
            }
        )
    }
}

/**
 * Static holder for discovery results — allows MainActivity to read
 * ScanService's results without binding.
 * In production, use a ViewModel + LiveData or Room DB instead.
 */
object ScanServiceResultHolder {
    @Volatile var lastDiscoveryResult: DiscoveryScanResult? = null
    @Volatile var lastZerodhaResult: com.signalscope.app.data.ScanResult? = null

    // Polling-friendly state set by ScanService — no broadcasts needed
    @Volatile var isDiscoveryRunning = false
    @Volatile var discoveryProgress = 0
    @Volatile var discoveryTotal = 0
    @Volatile var discoveryMarket = ""
    @Volatile var discoveryStatusText = ""
    /** Incremented each time the service updates results — UI polls for changes */
    @Volatile var discoveryVersion = 0L
    @Volatile var portfolioVersion = 0L

    // ── MACD watchdog cross-talk (DailyPortfolioManager ↔ ScanService) ──────────
    // When the DPM histogram-watchdog fires for a symbol on a given trading day:
    //   (a) suppresses ScanService's BOOK_PROFIT for that symbol the rest of the day
    //   (b) exposes the watchdog firing as a StockAlert in the in-app alert panel
    // Keys are date-stamped ("yyyy-MM-dd|SYMBOL") so the set is self-clearing
    // across days. macdWatchdogAlerts is appended to the live alert history.
    val macdWatchdogFiredKeys: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())
    val macdWatchdogAlerts: MutableList<com.signalscope.app.data.StockAlert> =
        java.util.Collections.synchronizedList(mutableListOf<com.signalscope.app.data.StockAlert>())

    /** Helper: did the DPM watchdog fire for [symbol] on [yyyyMmDd] (IST trading date)? */
    fun isMacdWatchdogFired(symbol: String, yyyyMmDd: String): Boolean =
        macdWatchdogFiredKeys.contains("$yyyyMmDd|$symbol")

    // ── Tier 1.5 Soft Sniper cross-talk ─────────────────────────────────────
    // When ScanService's intraday BOOK_PROFIT branch auto-places a soft sniper
    // GTT, it records the firing here so:
    //   (a) the same symbol doesn't get spammed with multiple soft snipers in
    //       one trading session (one-shot-per-day guard)
    //   (b) the action shows up in the in-app alert panel as a SOFT_SNIPER
    //       StockAlert alongside MACD_FLIP / BOOK_PROFIT
    //   (c) UI can surface a "🎯 SS" badge on the holding row
    // Keys are date-stamped ("yyyy-MM-dd|SYMBOL") so they self-clear next day.
    val softSniperFiredKeys: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())
    val softSniperAlerts: MutableList<com.signalscope.app.data.StockAlert> =
        java.util.Collections.synchronizedList(mutableListOf<com.signalscope.app.data.StockAlert>())

    /** Helper: did the soft sniper fire for [symbol] on [yyyyMmDd] (IST trading date)? */
    fun isSoftSniperFired(symbol: String, yyyyMmDd: String): Boolean =
        softSniperFiredKeys.contains("$yyyyMmDd|$symbol")

    // ── Score persistence streak (Tier 1.5 anti-flicker gate) ──
    // Tracks how many consecutive 15-min scan ticks each symbol has had
    // profitScore ≥ profitActivationThreshold. Soft sniper requires ≥ 2 ticks
    // (≈ 30 min of stable BOOK_PROFIT signal) before placing a GTT — kills
    // intraday flicker caused by the daily-indicators-on-partial-bar mismatch.
    // Keyed "yyyy-MM-dd|SYMBOL" so it self-clears next day.
    val profitScoreStreak: MutableMap<String, Int> =
        java.util.Collections.synchronizedMap(mutableMapOf<String, Int>())
}

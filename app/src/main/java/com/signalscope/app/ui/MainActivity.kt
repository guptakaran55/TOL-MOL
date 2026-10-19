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
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.DiscoveryResultStore
import com.signalscope.app.data.DiscoveryScanResult
import com.signalscope.app.data.PortfolioResultStore
import com.signalscope.app.data.LiveBuyTrackerStore
import com.signalscope.app.data.WatchlistStore
import com.signalscope.app.service.ScanService
import com.signalscope.app.network.YahooFinanceClient
import com.signalscope.app.network.YahooFinanceStreamClient
import com.signalscope.app.network.StockAiClient
import com.signalscope.app.trading.LivePortfolioBars
import com.signalscope.app.trading.LiveBuyTrackerEngine
import com.signalscope.app.trading.LiveSoftSniperEngine
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
/** Result of cancelling a patient-ladder rung, used by DashboardBridge.
 *
 *  [gone]: the order is confirmed off the book (safe to place the next rung).
 *  [likelyFilled]: it disappeared WITHOUT us successfully cancelling it — i.e.
 *  it probably filled, as opposed to us cleanly cancelling a still-resting,
 *  definitely-unfilled order. That distinction matters: only in the
 *  "likely filled" case can T212's /equity/portfolio endpoint still be lagging
 *  behind the fill, so only then must we wait for the position to catch up.
 *
 *  Declared at file level, not inside DashboardBridge: Kotlin forbids nested
 *  class declarations inside an `inner class`.
 */
// CancelOutcome moved with the ladder into trading/PatientExecutor.kt.

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        /** A Trading 212 holding's scores are reused for this long before its
         *  prices are fetched and scored again. The broker's own numbers —
         *  quantity, price, P&L — are fresh on every fetch regardless. */
        private const val T212_ANALYSIS_TTL_MS = 15 * 60 * 1000L
        /** Company figures are reused for this long. */
        private const val T212_FUNDAMENTALS_TTL_MS = 6 * 60 * 60 * 1000L

        /**
         * How many gate-passing, unseated names the manual watch list carries.
         *
         * 15 rather than the whole eligible set (typically 40–60 of the ~106 DAX
         * names) on purpose: these are the names the gate would actually rotate
         * into as seats free, so they are the ones a manual entry is worth
         * considering on. Watching the full list would triple the per-tick data
         * cost for candidates ranked far below anything the strategy would take.
         */
        const val WATCHLIST_N = 15

        /** How long a manualWatcherState answer may be reused. Long enough to
         *  collapse a burst of repaints into one read, short enough that nothing
         *  on screen is visibly behind. */
        private const val MWS_TTL_MS = 1500L
        private val mwsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

        /** Drop the memo after anything that changes what it reports. */
        fun invalidateManualWatcherState() = mwsCache.clear()
    }

    private lateinit var config: ConfigManager
    private lateinit var webView: WebView
    private lateinit var btnMonitor: MaterialButton
    private lateinit var btnNifty: MaterialButton
    private lateinit var btnNasdaq: MaterialButton
    private lateinit var btnDax: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var txtStatus: TextView
    private lateinit var controlBar: LinearLayout
    private lateinit var liveStreamClient: YahooFinanceStreamClient
    /** Built lazily — only if the India feed is switched to Kite. */
    private var zerodhaStreamClient: com.signalscope.app.network.ZerodhaStreamClient? = null
    /** Built lazily — only if the US feed is switched to Finnhub. */
    private var finnhubStreamClient: com.signalscope.app.network.FinnhubStreamClient? = null

    private val gson = Gson()
    private var isServiceRunning = false
    private var liveStreamStatus = YahooFinanceStreamClient.StreamStatus(
        enabled = false,
        connected = false,
        message = "Live stream off",
        symbolCount = 0
    )
    @Volatile private var t212LiveSymbols: Set<String> = emptySet()
    @Volatile private var t212OcoReconcileRunning = false
    /** One portfolio fetch at a time: a second tap waits for the first's answer
     *  rather than running every holding's analysis again alongside it. */
    private val t212PortfolioFetchRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Company figures by feed symbol. They move once a quarter, so a holding
     *  rescored every quarter-hour need not download them every time. */
    private val t212FundamentalsCache =
        java.util.concurrent.ConcurrentHashMap<String, Pair<Long, com.signalscope.app.network.YahooFinanceClient.FundamentalResult.Success>>()

    // Strategy Lab CSV backtest file picker — registered as a field so it's
    // available before STARTED. Result delivered to window.onStrategyLabBacktest.
    private val backtestCsvPicker: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) handleBacktestCsv(uri)
        }

    // Strategy Lab CONFIG JSON file picker — attach a .json instead of pasting.
    // The picked file's NAME is passed to the store as the config source so the
    // UI can show which file each space is running (different file per space).
    @Volatile private var configPickNs: String = "us"
    private val configJsonPicker: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) handleConfigJson(uri)
        }

    private fun displayNameOf(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            } ?: uri.lastPathSegment ?: "config.json"
        } catch (e: Exception) { uri.lastPathSegment ?: "config.json" }
    }

    private fun handleConfigJson(uri: Uri) {
        val ns = configPickNs
        thread(isDaemon = true, name = "strategy-lab-config") {
            val out: String = try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                val fileName = displayNameOf(uri)
                if (text.isBlank()) gson.toJson(mapOf("ok" to false, "error" to "Empty file"))
                else when (val pr = com.signalscope.app.trading.StrategyLabConfig.fromJson(text, fileName)) {
                    is com.signalscope.app.trading.StrategyLabConfig.Companion.ParseResult.Ok -> {
                        // Store with the file NAME as the source so the panel shows it.
                        com.signalscope.app.data.StrategyLabStore.setConfig(applicationContext, text, fileName, ns)
                        // A new config is a new strategy: the cached what-if belongs
                        // to the old one and would silently misreport in the
                        // comparison table.
                        com.signalscope.app.data.WhatIfStore.remove(applicationContext, ns)
                        invalidateManualWatcherState()
                        gson.toJson(mapOf("ok" to true, "source" to fileName,
                            "summary" to pr.config.summary(), "ns" to ns,
                            // Slippage drives how a P&L should be read, so it is
                            // returned explicitly rather than buried in the summary.
                            "slippagePct" to pr.config.slippagePct,
                            "entryTf" to pr.config.resample,
                            "exitTf" to (if (pr.config.multiTimeframe) pr.config.exitBars else pr.config.resample),
                            "warmupBars" to pr.config.warmupBars))
                    }
                    is com.signalscope.app.trading.StrategyLabConfig.Companion.ParseResult.Rejected ->
                        gson.toJson(mapOf("ok" to false, "error" to pr.reason))
                }
            } catch (e: Exception) {
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "read failed")))
            }
            // Outer-class delivery (deliverAiCallback lives on the inner bridge):
            // mirror handleBacktestCsv's evaluateJavascript pattern.
            val escaped = out.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\n", "\\n").replace("\r", "")
            runOnUiThread {
                try {
                    if (!isFinishing && !isDestroyed && ::webView.isInitialized)
                        webView.evaluateJavascript("window.onStrategyLabConfigFile('$escaped')", null)
                } catch (e: Throwable) { Log.e(TAG, "config deliver failed", e) }
            }
        }
    }

    // Gate walk-forward backtest: pick MANY symbol CSVs, run engine per symbol,
    // then replay Mode 3b / Mode 2 over the union of days (GateBacktest).
    @Volatile private var gateBtNs: String = "us"
    @Volatile private var gateBtParamsJson: String = "{}"
    private val gateBtPicker: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (!uris.isNullOrEmpty()) handleGateBacktest(uris)
        }

    // FOLDER picker — the practical path for 100-symbol universes. Android's
    // multi-file picker has no "select all", so a full DAX folder would need
    // 100 long-press taps. Pick the folder once; every *.csv inside is used.
    private val gateBtFolderPicker: ActivityResultLauncher<Uri?> =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) handleGateBacktestFolder(uri)
        }

    private fun handleGateBacktestFolder(tree: Uri) {
        thread(isDaemon = true, name = "gate-bt-scan") {
            try {
                val docId = android.provider.DocumentsContract.getTreeDocumentId(tree)
                val children = android.provider.DocumentsContract
                    .buildChildDocumentsUriUsingTree(tree, docId)
                val found = ArrayList<Pair<String, Uri>>()
                contentResolver.query(children, arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ), null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        val name = c.getString(1) ?: ""
                        if (name.lowercase().endsWith(".csv"))
                            found.add(name to android.provider.DocumentsContract
                                .buildDocumentUriUsingTree(tree, id))
                    }
                }
                if (found.isEmpty()) {
                    deliverGateBt("{\"ok\":false,\"error\":\"No .csv files found in the selected folder (top level only — subfolders are not scanned).\"}")
                    return@thread
                }
                found.sortBy { it.first.lowercase() }   // deterministic order
                handleGateBacktest(found.map { it.second })
            } catch (e: Exception) {
                deliverGateBt(gson.toJson(mapOf("ok" to false,
                    "error" to "Folder scan failed: ${e.message}")))
            }
        }
    }

    private fun handleGateBacktest(uris: List<Uri>) {
        val ns = gateBtNs
        val paramsJson = gateBtParamsJson
        thread(isDaemon = true, name = "gate-backtest") {
            val out: String = try {
                val po = org.json.JSONObject(paramsJson)
                // A backtest always runs on the space's correct bar grid, even while the live book
                // is still finishing an open position on the grid it was opened under.
                val cfg = com.signalscope.app.data.StrategyLabStore.activeConfig(applicationContext, ns)
                    .copy(barAnchorSec = com.signalscope.app.data.StrategyLabStore.barAnchorFor(ns))
                val params = com.signalscope.app.trading.GateBacktest.Params(
                    mode = po.optString("mode", "3B"),
                    holdN = po.optInt("holdN", 5),
                    smoothing = po.optInt("smoothing", 5),
                    notional = po.optDouble("notional", 1000.0),
                    confirmDays = po.optInt("confirmDays", 1),
                    // Liquidation slippage comes from the CONFIG (these champions carry
                    // slippagePct=0.2), not a hardcoded 0.05. Only bites MONTHLY-mode
                    // month-end forced sales, but otherwise a liquidation would cost
                    // 0.05 while every normal close costs the config's 0.2.
                    slippagePct = po.optDouble("slippagePct", cfg.slippagePct),
                    topN = po.optInt("topN", 20))
                val data = ArrayList<com.signalscope.app.trading.GateBacktest.SymbolData>()
                val failed = ArrayList<String>()
                // One file at a time: parse → engine → daily+slope → discard candles,
                // so peak memory is a single symbol's intraday series.
                for ((i, uri) in uris.withIndex()) {
                    deliverGateBt("{\"progress\":true,\"done\":$i,\"total\":${uris.size}}")
                    try {
                        val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                        val (sym, candles) = com.signalscope.app.trading.StrategyLabBacktest.parseCsv(text)
                        // Report WHY, not just the name: "0 rows" means the datetime
                        // format wasn't understood (see normalizeTs), which previously
                        // showed up as a bare symbol list with no explanation.
                        if (candles.size < 50) {
                            failed.add("${sym.ifBlank { "file$i" }}(${candles.size} rows parsed)")
                            continue
                        }
                        // Repair splits ONCE, here, so the engine and the screener's daily series
                        // see the same prices. The engine adjusts internally too (idempotent — a
                        // repaired series has no discontinuity left to find), but GateBacktest.build
                        // squashes these candles into the ranking series, and ranking off an
                        // unadjusted -65% demerger would pick companies on corrupted scores.
                        val adj = com.signalscope.app.trading.StrategyLabEngine.backAdjust(candles)
                        val r = com.signalscope.app.trading.StrategyLabEngine.run(sym, adj, cfg)
                        if (r.error != null) { failed.add("$sym(${r.error})"); continue }
                        data.add(com.signalscope.app.trading.GateBacktest.build(sym, adj, r.trades, params.smoothing))
                    } catch (e: Exception) { failed.add("file$i(${e.message})") }
                }
                // A distinct phase, NOT "walk-forward" with the file counts reused — that rendered
                // as "day 400/400 (100%)" before the walk had taken a single step. Preparing is
                // real work (union of all trading days, plus the one-time screener prep) and can
                // take a few seconds on a large universe, so it deserves saying so.
                deliverGateBt("{\"progress\":true,\"done\":0,\"total\":0,\"phase\":\"preparing\"}")
                val rep = com.signalscope.app.trading.GateBacktest.run(data, params) { done, total ->
                    deliverGateBt("{\"progress\":true,\"done\":$done,\"total\":$total,\"phase\":\"walk-forward\"}")
                }
                val map = mutableMapOf<String, Any?>(
                    "ok" to (rep.error == null), "error" to rep.error, "mode" to rep.mode,
                    "symbols" to data.size, "failedFiles" to failed,
                    // Ride along with the result: a return is only interpretable
                    // against the friction assumed to produce it.
                    "slippagePct" to params.slippagePct,
                    "notional" to params.notional, "holdNParam" to params.holdN,
                    "injected" to rep.injected, "finalValue" to rep.finalValue,
                    "openMtmAdj" to rep.openMtmAdj, "compoundedPct" to rep.compoundedPct,
                    "cagrPct" to rep.cagrPct, "maxDrawdownPct" to rep.maxDrawdownPct,
                    "t0" to rep.t0, "lastDay" to rep.lastDay,
                    "closedCount" to rep.closedCount, "winRatePct" to rep.winRatePct,
                    "liquidations" to rep.liquidations, "liquidationAvgPct" to rep.liquidationAvgPct,
                    "cashDaysPct" to rep.cashDaysPct,
                    "perSymbol" to rep.perSymbol, "log" to rep.log,
                    // Per-seat equity curve (EUR) for the aggregate slot chart.
                    "slotSeries" to rep.slotSeries, "holdN" to params.holdN)
                gson.toJson(map)
            } catch (e: Exception) {
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "gate backtest failed")))
            }
            deliverGateBt(out)
        }
    }

    /** Push one daily-gate progress tick to window.onGateEvalProgress. */
    private fun deliverGateEval(json: String) {
        val escaped = json.replace("\\", "\\\\").replace("'", "\\'")
            .replace("\n", "\\n").replace("\r", "")
        runOnUiThread {
            try {
                if (!isFinishing && !isDestroyed && ::webView.isInitialized)
                    webView.evaluateJavascript("window.onGateEvalProgress('$escaped')", null)
            } catch (e: Throwable) { Log.e(TAG, "gate eval deliver failed", e) }
        }
    }

    private fun deliverGateBt(json: String) {
        val escaped = json.replace("\\", "\\\\").replace("'", "\\'")
            .replace("\n", "\\n").replace("\r", "")
        runOnUiThread {
            try {
                if (!isFinishing && !isDestroyed && ::webView.isInitialized)
                    webView.evaluateJavascript("window.onGateBacktestResult('$escaped')", null)
            } catch (e: Throwable) { Log.e(TAG, "gate bt deliver failed", e) }
        }
    }

    // Holds the last backtest's full report CSV (desktop format) for export.
    @Volatile private var lastBacktestReport: String? = null
    // Which Strategy Lab namespace the pending CSV backtest belongs to ("us" or
    // "binance") — set just before the file picker launches, read on result.
    @Volatile private var backtestNs: String = "us"

    private val reportExportLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            val text = lastBacktestReport
            if (uri != null && text != null) thread(isDaemon = true, name = "strategy-lab-export") {
                try { contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
                catch (e: Exception) { Log.e(TAG, "report export write failed", e) }
            }
        }

    private fun handleBacktestCsv(uri: Uri) {
        thread(isDaemon = true, name = "strategy-lab-backtest") {
            val out: String = try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                if (text.isBlank()) gson.toJson(mapOf("error" to "Empty file"))
                else {
                    val cfg = com.signalscope.app.data.StrategyLabStore.activeConfig(applicationContext, backtestNs)
                        .copy(barAnchorSec = com.signalscope.app.data.StrategyLabStore.barAnchorFor(backtestNs))
                    val rawJson = com.signalscope.app.data.StrategyLabStore.load(applicationContext, backtestNs).configJson
                    val t0 = System.currentTimeMillis()
                    val result = com.signalscope.app.trading.StrategyLabBacktest.run(text, cfg)
                    val elapsed = System.currentTimeMillis() - t0
                    lastBacktestReport = if (result.error == null)
                        com.signalscope.app.trading.StrategyLabReport.build(result, cfg, rawJson, elapsed) else null
                    gson.toJson(result)
                }
            } catch (e: Exception) {
                gson.toJson(mapOf("error" to (e.message ?: "read failed")))
            }
            val escaped = out.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\n", "\\n").replace("\r", "")
            runOnUiThread {
                try {
                    if (!isFinishing && !isDestroyed && ::webView.isInitialized)
                        webView.evaluateJavascript("window.onStrategyLabBacktest('$escaped')", null)
                } catch (e: Throwable) { Log.e(TAG, "backtest deliver failed", e) }
            }
        }
    }

    // ── Polling-based UI refresh (replaces unreliable broadcasts on MIUI) ──
    private val pollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastSeenDiscoveryVersion = 0L
    private var lastSeenPortfolioVersion = 0L
    private var wasDiscoveryRunning = false
    private val POLL_INTERVAL_MS = 500L // 500ms — keeps UI in sync with notification progress
    private val DISCOVERY_PUSH_MIN_INTERVAL_MS = 2_000L
    private val DISCOVERY_PUSH_MIN_STOCK_DELTA = 10
    private var lastDiscoveryPayloadPushAt = 0L

    // ── SENDING DISCOVERY RESULTS TO THE PAGE ───────────────────────────────
    // The India result alone is over 10 MB of text. It used to be built on the
    // thread that draws the screen — and on a cold start four times over: twice
    // when the screen appeared (to a page that had not even loaded yet, so both
    // were thrown away) and twice more when the page finished. That is most of
    // the white screen and the stutter after it.
    //
    // Now: built on its own background thread, one send at a time and in order;
    // nothing is sent before the page exists; and a result the CURRENT page
    // already holds is not sent again — the page keeps every market's results
    // until it reloads, and a reload starts a new generation that gets
    // everything afresh.
    private val discoveryPushExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "discovery-push").apply { isDaemon = true }
    }
    @Volatile private var pageReady = false
    @Volatile private var pageGen = 0
    private val pushedDiscoverySig = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var livePushInFlight = false

    private fun discoverySig(r: DiscoveryScanResult, complete: Boolean) =
        "${r.market}|${r.timestamp}|${r.allStocks.size}|$complete"
    private var lastDiscoveryPayloadStockCount = -1
    private var lastDiscoveryPayloadMarket = ""
    private var lastDiscoveryPayloadComplete = false

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
                // "Complete" is chained to the send so it can never arrive before
                // the final results it announces.
                pushDiscoveryResultsToWebView(force = true, thenJs = "window.scanComplete()")
                // btnStop kept permanently hidden — replaced by the pulsing
                // ⏹ Stop pill in the dashboard topBar (saves vertical space).
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
        // One-time move onto the better US feed for installs that predate the
        // choice. No-ops on every run after the first.
        try { config.migrateUsFeedOnce() } catch (_: Exception) {}
        // One-time removal of rows that were never Trading 212 positions — see
        // the note on the function. Backs up before it deletes and no-ops after.
        try { com.signalscope.app.data.T212LiveStore.retireForeignRows(applicationContext) }
        catch (_: Exception) {}
        liveStreamClient = YahooFinanceStreamClient(
            onQuote = { quote ->
                // Free mark-to-market for algo positions: this price already
                // arrived, so valuing an open seat off it costs no request.
                com.signalscope.app.trading.MarkToMarket.onQuote(
                    quote.symbol, quote.price, quote.time.takeIf { it > 0L } ?: System.currentTimeMillis())
                val liveSignal = LivePortfolioBars.recordQuote(quote)
                if (liveSignal != null) {
                    LiveSoftSniperEngine.onSignal(applicationContext, config, liveSignal)
                    LiveBuyTrackerEngine.onSignal(applicationContext, config, liveSignal)
                }
                // Dummy GTT alerts (all spaces): vibrate + notify when a watched
                // SL/target level is reached. Places no broker order.
                com.signalscope.app.trading.DummyGttMonitor.onQuote(
                    applicationContext, quote.symbol, quote.price
                )
                runOnUiThread {
                    pushLiveQuoteToWebView(quote, liveSignal)
                }
            },
            onStatus = { status ->
                LivePortfolioBars.updateStreamState(
                    enabled = status.enabled,
                    connected = status.connected,
                    symbolCount = status.symbolCount,
                    message = status.message
                )
                runOnUiThread {
                    liveStreamStatus = status
                    pushLiveStreamStateToWebView()
                }
            }
        )

        // Setups-tab sliders are session-only by design. Clear any lingering
        // session override on a fresh MainActivity launch so reopening the app
        // always gives the user the Settings-tab defaults (per product spec).
        com.signalscope.app.data.SessionWeights.override = null

        setupWebView()
        setupButtons()
        requestNotificationPermission()
        refreshStatusBar()

        // Load cached discovery results from disk so they survive process death.
        restoreCachedDiscoveryAsync()
    }

    /** Guards against a second restore starting while the first is still reading. */
    @Volatile private var discoveryRestoreRunning = false

    /**
     * Rehydrate the discovery cache OFF THE MAIN THREAD.
     *
     * This parses the newest discovery file, and for the Indian universe that file
     * is over ten megabytes — roughly two seconds of blocking work on a good day.
     * It used to run inline here and again in onResume, which is how a phone that
     * had just rebooted ended up showing "app isn't responding" before the first
     * frame was ever drawn: the system was already thrashing, killing background
     * apps to free memory, and the very first thing this app did on waking was
     * read ten megabytes of JSON on the thread that draws the screen.
     *
     * Nothing during start-up needs the result synchronously. The page pushes
     * results once it has finished loading, which is long after this returns, and
     * the push is repeated here when the read lands late.
     */
    private fun restoreCachedDiscoveryAsync() {
        if (ScanServiceResultHolder.lastDiscoveryResult != null) return
        synchronized(this) {
            if (discoveryRestoreRunning) return
            discoveryRestoreRunning = true
        }
        thread(isDaemon = true, name = "discovery-restore") {
            try {
                val cached = DiscoveryResultStore.loadLatest(applicationContext)
                if (cached != null && ScanServiceResultHolder.lastDiscoveryResult == null) {
                    ScanServiceResultHolder.lastDiscoveryResult = cached
                    Log.d(TAG, "Restored ${cached.allStocks.size} cached discovery stocks (${cached.market})")
                    runOnUiThread {
                        try { pushDiscoveryResultsToWebView(force = true) }
                        catch (e: Exception) { Log.w(TAG, "deferred discovery push skipped: ${e.message}") }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "cached discovery restore failed", e)
            } finally {
                discoveryRestoreRunning = false
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

        // Restore from disk if in-memory results were lost (process death).
        // Off the main thread for the same reason as in onCreate — and onResume is
        // the worse of the two places to block, because it runs every time the app
        // comes back to the foreground, not just once.
        restoreCachedDiscoveryAsync()

        // Track if discovery is currently running
        wasDiscoveryRunning = ScanServiceResultHolder.isDiscoveryRunning
        // Native btnStop replaced by topBar pulsing ⏹ pill — keep it hidden
        // unconditionally so it doesn't reclaim its vertical slot during scans.
        btnStop.visibility = View.GONE

        refreshStatusBar()

        // Push whatever results we have (live or cached) to WebView
        pushDiscoveryResultsToWebView(force = true)
        pushPortfolioResultsToWebView()
        // Strategy Lab paper loops (US, Germany, crypto) — each self-gates on
        // enabled and is throttled so a resume doesn't re-fetch more than every
        // 10 min.
        //
        // OFF THE DRAWING THREAD. Each reads and decodes an algo's whole save file
        // before its own 10-minute check can say no — and did it on the thread
        // that paints the screen, while the app was opening. They already run
        // from a background thread every quarter hour, so running them from one
        // here changes nothing but when the screen gets to draw.
        kotlin.concurrent.thread(isDaemon = true, name = "resume-lab-check") {
            val app = applicationContext
            com.signalscope.app.trading.StrategyLabRunner.runAll(app, 10 * 60 * 1000L)
            com.signalscope.app.trading.StrategyLabRunner.runAll(app, 10 * 60 * 1000L, "de")
            com.signalscope.app.trading.StrategyLabRunner.runAll(app, 10 * 60 * 1000L, "binance")
        }
        updateLiveStreamSubscriptions()
        pushLiveStreamStateToWebView()
        reconcileT212OcoPairsInBackground("resume")
        com.signalscope.app.service.T212DailyTrailScheduler.ensureScheduled(this)
        com.signalscope.app.service.StrategyLabScheduler.ensureScheduled(this)
        pollHandler.postDelayed({ pushCachedDiscoveryResultsToWebViewAsync() }, 250L)

        // Start polling for live updates (reliable even on MIUI)
        pollHandler.removeCallbacks(pollRunnable)
        pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    private fun reconcileT212OcoPairsInBackground(reason: String) {
        if (!config.hasT212Credentials || t212OcoReconcileRunning) return
        t212OcoReconcileRunning = true
        thread(isDaemon = true, name = "t212-oco-reconcile") {
            try {
                val summary = com.signalscope.app.trading.Trading212TrailingManager
                    .reconcileOnly(applicationContext, config)
                if (summary.reconciledFilled > 0 || summary.reconciledClosed > 0 || summary.errors.isNotEmpty()) {
                    Log.i(TAG, "T212 OCO reconcile ($reason): $summary")
                }
                val json = gson.toJson(mapOf("ok" to summary.errors.isEmpty(), "summary" to summary, "reason" to reason))
                val escaped = json.replace("\\", "\\\\").replace("'", "\\'")
                    .replace("\n", "\\n").replace("\r", "")
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && ::webView.isInitialized) {
                        webView.evaluateJavascript(
                            "if(window.onT212ReconcileResult)window.onT212ReconcileResult('$escaped')",
                            null
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "T212 OCO reconcile failed ($reason)", e)
            } finally {
                t212OcoReconcileRunning = false
            }
        }
    }

    /**
     * The app was already running when the notification was tapped. onCreate does
     * not run again, so without this the tap would merely bring the app forward
     * on whatever screen it was left — which is exactly what it did before.
     */
    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        consumeAlgoDeepLink(newIntent)
    }

    /**
     * Open one algo's card, in its own space, from a notification tap.
     *
     * The extra is CLEARED after use. Android hands the same intent back on every
     * later resume, so leaving it in place would drag the user back to the same
     * algo every time they returned to the app — long after the trade that
     * prompted it stopped being interesting.
     */
    private fun consumeAlgoDeepLink(src: Intent?) {
        // NOT BEFORE THE PAGE EXISTS. A tap that arrives while the screen is still
        // loading used to be sent to a page with nothing to receive it, and then
        // erased — the app opened wherever it was and the tap was simply lost.
        // Left on the intent instead, it is picked up the moment the page is
        // ready (see onPageFinished).
        if (!pageReady) return
        consumeChartDeepLink(src)
        consumeWatcherDeepLink(src)
        consumeTabDeepLink(src)
        val ns = src?.getStringExtra(com.signalscope.app.trading.LabNotifier.EXTRA_OPEN_NS)
        if (ns.isNullOrBlank()) return
        src.removeExtra(com.signalscope.app.trading.LabNotifier.EXTRA_OPEN_NS)
        if (!::webView.isInitialized) return
        val safe = ns.replace("\\", "").replace("'", "")
        webView.post {
            webView.evaluateJavascript(
                "window.openAlgoFromNotification && window.openAlgoFromNotification('$safe')", null)
        }
    }

    /**
     * Open the CHART for one company from an entry-signal notification.
     *
     * An entry signal is a single decision about a single name, and the chart is
     * where that decision is made. Landing on whatever screen the app was last
     * left on means recovering the ticker from memory — right after dismissing the
     * one thing that told you what it was.
     *
     * Cleared after use for the same reason as the algo link: Android replays the
     * same intent on every later resume, and without clearing it the app would
     * re-open this chart every time the user came back, days after the signal.
     */
    /**
     * Land a tap on the tab the notice was about.
     *
     * The running-scan notice used to open the app wherever it was last left, so it
     * announced that something had happened and then dropped the user somewhere
     * unrelated — the only way to find out what it meant was to go looking. A notice
     * about the algos should open the algos.
     *
     * Cleared after use like the others: Android replays the same intent on every
     * later resume, and this notice is permanent, so without clearing it every
     * return to the app would be yanked back to the same tab.
     */
    private fun consumeTabDeepLink(src: Intent?) {
        val tab = src?.getStringExtra(com.signalscope.app.trading.LabNotifier.EXTRA_OPEN_TAB)
        if (tab.isNullOrBlank()) return
        src.removeExtra(com.signalscope.app.trading.LabNotifier.EXTRA_OPEN_TAB)
        if (!::webView.isInitialized) return
        val safe = tab.replace("\\", "").replace("'", "")
        webView.post {
            webView.evaluateJavascript(
                "if(typeof setTab==='function')setTab('$safe')", null)
        }
    }

    /**
     * Land on the watcher LIST for one market, scrolled to one name.
     *
     * Cleared after use like the others: Android replays the same intent on every
     * later resume, so without clearing it every return to the app would be
     * yanked back to the same row.
     */
    private fun consumeWatcherDeepLink(src: Intent?) {
        val sym = src?.getStringExtra(
            com.signalscope.app.trading.LabNotifier.EXTRA_OPEN_WATCH_SYMBOL)
        if (sym.isNullOrBlank()) return
        val space = src.getStringExtra(
            com.signalscope.app.trading.LabNotifier.EXTRA_OPEN_SPACE) ?: "de"
        src.removeExtra(com.signalscope.app.trading.LabNotifier.EXTRA_OPEN_WATCH_SYMBOL)
        src.removeExtra(com.signalscope.app.trading.LabNotifier.EXTRA_OPEN_SPACE)
        if (!::webView.isInitialized) return
        val safeSym = sym.replace("\\", "").replace("'", "")
        val safeSpace = space.replace("\\", "").replace("'", "")
        webView.post {
            webView.evaluateJavascript(
                "window.openWatcherListFromNotification && " +
                    "window.openWatcherListFromNotification('$safeSpace','$safeSym')", null)
        }
    }

    private fun consumeChartDeepLink(src: Intent?) {
        val sym = src?.getStringExtra(com.signalscope.app.trading.LabNotifier.EXTRA_OPEN_SYMBOL)
        if (sym.isNullOrBlank()) return
        val space = src.getStringExtra(com.signalscope.app.trading.LabNotifier.EXTRA_OPEN_SPACE) ?: "de"
        src.removeExtra(com.signalscope.app.trading.LabNotifier.EXTRA_OPEN_SYMBOL)
        src.removeExtra(com.signalscope.app.trading.LabNotifier.EXTRA_OPEN_SPACE)
        if (!::webView.isInitialized) return
        val safeSym = sym.replace("\\", "").replace("'", "")
        val safeSpace = space.replace("\\", "").replace("'", "")
        webView.post {
            webView.evaluateJavascript(
                "window.openWatcherChartFromNotification && " +
                    "window.openWatcherChartFromNotification('$safeSpace','$safeSym')", null)
        }
    }

    override fun onPause() {
        super.onPause()
        pollHandler.removeCallbacks(pollRunnable)
        if (::liveStreamClient.isInitialized) {
            LivePortfolioBars.updateStreamState(false, false, 0, "Paused")
            liveStreamClient.stop("Paused")
        }
        // The Kite socket must go down with it. A broker feed left open in the
        // background burns the connection allowance and the battery for prices
        // nothing is reading.
        zerodhaStreamClient?.stop("Paused")
        finnhubStreamClient?.stop("Paused")
        try { unregisterReceiver(scanReceiver) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        if (::liveStreamClient.isInitialized) {
            LivePortfolioBars.updateStreamState(false, false, 0, "Activity destroyed")
            liveStreamClient.stop("Activity destroyed")
        }
        zerodhaStreamClient?.stop("Activity destroyed")
        finnhubStreamClient?.stop("Activity destroyed")
        super.onDestroy()
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

        btnDax = MaterialButton(this).apply {
            text = "🇩🇪 DAX"
            textSize = 11f; isAllCaps = false
            setBackgroundColor(0xFF2563eb.toInt())
            cornerRadius = dp(8)
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(4) }
            setPadding(dp(4), 0, dp(4), 0)
        }
        actionRow.addView(btnDax)
        // Action row hidden by default — duplicates of these buttons live in the
        // dashboard topBar (compact icons next to the hamburger ☰) and inside
        // the Discovery tab header. Hiding here reclaims ~40dp of vertical space
        // for the actual content. The buttons remain INSTANTIATED so click
        // listeners are still live (used by the JS bridge) — they just aren't
        // painted on screen.
        actionRow.visibility = View.GONE
        controlBar.addView(actionRow)

        // Stop button: also hidden — replaced by a small ⏹ pill in the dashboard
        // topBar that appears only while STATE.scanning is true.
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

        controlBar.visibility = View.GONE
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
            // NEVER SERVE A CACHED DASHBOARD. The page ships inside the APK, so
            // re-reading it costs nothing and never touches the network — while a
            // cached copy surviving an update means the app quietly runs the OLD
            // interface against the NEW bridge. That is worse than it sounds: the
            // screen looks a version behind, every fix appears not to have shipped,
            // and any call the old page makes to a method the new build renamed
            // simply fails. "I installed it but nothing changed" is the symptom, and
            // it is indistinguishable from a broken install.
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
        // ONCE, EVER — not on every launch.
        //
        // This exists only to flush a page cached by a build from before the line
        // above existed, which is a one-time job. Running it every start was a
        // mistake with a measurable cost: it deletes files synchronously on the UI
        // thread, and it pushed startup from ~1.3s to 2.4–3.6s, far enough to trip
        // the not-responding watchdog on a cold start.
        //
        // The setting above is what actually prevents stale pages from here on;
        // this is just cleanup of the past, so it runs behind a flag and never
        // again.
        try {
            val p = getSharedPreferences("signalscope_prefs", MODE_PRIVATE)
            if (!p.getBoolean("webview_cache_flushed_v1", false)) {
                webView.clearCache(true)
                p.edit().putBoolean("webview_cache_flushed_v1", true).apply()
                Log.i(TAG, "WebView cache flushed once for the no-cache switchover")
            }
        } catch (e: Exception) { Log.w(TAG, "cache flush check failed", e) }

        // Add JS bridge so dashboard.html can call Kotlin
        webView.addJavascriptInterface(DashboardBridge(), "Android")

        // Daily-gate progress → a determinate overlay in the dashboard. The
        // universe fetch is ONE daily series per candidate (~1s each), so a
        // 106-name evaluation runs for minutes; with no signal the UI looked
        // frozen and "Save & run" read as doing nothing.
        com.signalscope.app.trading.MacdGateEngine.progressSink = { ns, phase, done, total ->
            deliverGateEval(gson.toJson(mapOf(
                "ns" to ns, "phase" to phase, "done" to done, "total" to total)))
        }

        // Run-pass progress → an inline busy chip + % next to "Run now", so a tap
        // visibly does something instead of returning a toast and going quiet.
        com.signalscope.app.trading.StrategyLabRunner.progressSink = { ns, phase, done, total ->
            val json = gson.toJson(mapOf(
                "ns" to ns, "phase" to phase, "done" to done, "total" to total))
            val escaped = json.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\n", "\\n").replace("\r", "")
            runOnUiThread {
                try {
                    if (!isFinishing && !isDestroyed && ::webView.isInitialized)
                        webView.evaluateJavascript("window.onRunProgress('$escaped')", null)
                } catch (e: Throwable) { Log.e(TAG, "run progress deliver failed", e) }
                // A pass can OPEN a position, and the stream only carries what it
                // was told to subscribe to — so re-derive the list when a pass ends,
                // or a brand-new holding would sit on bar prices until the next
                // resume. Cheap: it diffs and no-ops when nothing changed.
                if (phase == "done") {
                    try { updateLiveStreamSubscriptions() }
                    catch (e: Exception) { Log.w(TAG, "stream resubscribe failed", e) }
                }
            }
        }

        // Marks refreshed on a background thread → repaint. Without this the card
        // keeps showing the price it was drawn with: the live-quote stream normally
        // repaints often enough to mask it, but after the market closes there are
        // no quotes and "(stale price)" persists even though a fresh price arrived.
        com.signalscope.app.trading.MarkToMarket.onRefreshed = {
            runOnUiThread {
                try {
                    if (!isFinishing && !isDestroyed && ::webView.isInitialized)
                        webView.evaluateJavascript(
                            "try{if(window.STATE&&STATE.tab==='algos')renderKeepScroll()}catch(e){}", null)
                } catch (e: Throwable) { Log.e(TAG, "mark repaint failed", e) }
            }
        }

        // What-if progress → the bulb panel's own counter. Under mode 3b the replay
        // fetches the whole ranking universe (100+ names), so without this it would
        // spin silently for a minute or more — the exact failure mode "Save & run
        // does nothing" turned out to be.
        com.signalscope.app.trading.StrategyLabRunner.whatIfSink = { ns, done, total ->
            val json = gson.toJson(mapOf("ns" to ns, "done" to done, "total" to total))
            val escaped = json.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\n", "\\n").replace("\r", "")
            runOnUiThread {
                try {
                    if (!isFinishing && !isDestroyed && ::webView.isInitialized)
                        webView.evaluateJavascript("window.onWhatIfProgress('$escaped')", null)
                } catch (e: Throwable) { Log.e(TAG, "what-if progress deliver failed", e) }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            // SECURITY: this WebView holds the live trading JS bridge. It must
            // never navigate away from the bundled dashboard asset. If injected
            // content (e.g. a prompt-injected AI/news payload) tries to send the
            // page to an external URL, block it — don't load it in the
            // privileged context. Only file:///android_asset/ is allowed.
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: android.webkit.WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString().orEmpty()
                return !url.startsWith("file:///android_asset/")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // A notification tap that arrived while the process was dead is
                // handled HERE, not in onCreate: the page has to exist before it
                // can be told where to go, and on a cold start onCreate runs long
                // before it does.
                // A NEW PAGE holds nothing yet: open a fresh generation so every
                // market is sent to it once, whatever the last page had.
                pageGen++
                pushedDiscoverySig.clear()
                pageReady = true
                // After the flag, which it now waits for.
                consumeAlgoDeepLink(intent)
                // Push any existing results once page is loaded
                pushDiscoveryResultsToWebView(force = true)
                pollHandler.postDelayed({ pushCachedDiscoveryResultsToWebViewAsync() }, 250L)
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
                updateLiveStreamSubscriptions()
                pushLiveStreamStateToWebView()
                // Fetch market context in background
                fetchMarketIndexData()
                // Fetch sector data in background
                fetchSectorData()
            }
        }

        // Load dashboard from assets after the client is attached so the
        // onPageFinished hydration hook cannot be missed on a fast load.
        pageReady = false
        webView.loadUrl("file:///android_asset/dashboard.html")
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
        fun triggerDaxScan() {
            runOnUiThread { startDiscoveryScan(ScanService.ACTION_DISCOVERY_DAX) }
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

        @JavascriptInterface
        fun getEffectiveWeightsJson(): String {
            return try {
                gson.toJson(config.scoringWeights)
            } catch (e: Throwable) {
                Log.e(TAG, "getEffectiveWeightsJson failed", e); "{}"
            }
        }

        @JavascriptInterface
        fun getRiskSettingsJson(): String {
            return try {
                gson.toJson(
                    mapOf(
                        "slLimitPct" to config.slLimitPct,
                        "newPositionHardStopPct" to config.newPositionHardStopPct,
                        "newPositionHardStopFraction" to config.newPositionHardStopFraction,
                        "slChandelierFixedAtrMultiple" to config.slChandelierFixedAtrMultiple,
                        "slTimeDecay10dFactor" to config.slTimeDecay10dFactor,
                        "slTimeDecay20dFactor" to config.slTimeDecay20dFactor,
                        "targetAtrMultiple" to config.targetAtrMultiple,
                        "targetMaxDailyStepPct" to config.targetMaxDailyStepPct
                    )
                )
            } catch (e: Throwable) {
                Log.e(TAG, "getRiskSettingsJson failed", e); "{}"
            }
        }

        @JavascriptInterface
        fun planOcoSellJson(
            ltp: Double,
            support: Double,
            resistance: Double,
            atr: Double,
            avgPrice: Double,
            projectedCeiling: Double,
            tickSize: Double
        ): String {
            return try {
                val plan = com.signalscope.app.trading.OcoPlanner.planSellOco(
                    config,
                    com.signalscope.app.trading.OcoPlanner.Input(
                        ltp = ltp,
                        support = support,
                        resistance = resistance,
                        atr = atr,
                        avgPrice = avgPrice,
                        projectedCeiling = projectedCeiling.takeIf { it > 0.0 },
                        tickSize = tickSize.takeIf { it > 0.0 } ?: 0.05
                    )
                )
                gson.toJson(plan)
            } catch (e: Throwable) {
                Log.e(TAG, "planOcoSellJson failed", e)
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "exception")))
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
                "minSlopeMagnitude"       -> base.copy(minSlopeMagnitude = value.coerceIn(-89.0, 89.0))
                "goldenBuyMaxSlope"       -> base.copy(goldenBuyMaxSlope = kotlin.math.abs(value).coerceIn(0.0, 89.0))
                "setupSma200MinSlope"     -> base.copy(setupSma200MinSlope = value)
                "setupMacdLowPctMin"      -> base.copy(setupMacdLowPctMin = value.coerceIn(0.0, 100.0))
                "setupMacdPctlMax"        -> base.copy(setupMacdPctlMax = value.coerceIn(0.0, 100.0))
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
                // A single scan — it starts the service for itself and lets it
                // stop afterwards, instead of switching monitoring on for good.
                ContextCompat.startForegroundService(this@MainActivity,
                    ScanService.createIntent(this@MainActivity, ScanService.ACTION_SCAN_NOW))
                Toast.makeText(this@MainActivity, "Scanning portfolio...", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun fetchSectors() {
            fetchSectorData()
        }

        @JavascriptInterface
        fun fetchMarketIndex() {
            fetchMarketIndexData()
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
        fun getHeaderStatusText(): String {
            val holder = ScanServiceResultHolder
            if (holder.isDiscoveryRunning && holder.discoveryStatusText.isNotEmpty()) {
                return holder.discoveryStatusText
            }
            return if (config.serviceRunning) {
                val ts = config.lastPortfolioScan
                val timeStr = if (ts > 0)
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts)) else "Never"
                "Monitoring · Last: $timeStr"
            } else {
                val cached = holder.lastDiscoveryResult
                if (cached != null && cached.isComplete) {
                    val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(cached.timestamp))
                    "${cached.market}: ${cached.allStocks.size} · $timeStr"
                } else {
                    "Ready"
                }
            }
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

        // ── Space switching (IN ↔ US ↔ DE workspaces) ────────────────────────
        // Returns the persisted space so JS can render the right tabs/data on
        // first paint without a flicker. Defaults to "IN" for new installs.
        @JavascriptInterface
        fun getCurrentSpace(): String = config.currentSpace

        /** Persist the user's chosen space. Called from the hamburger drawer
         *  in dashboard.html. Also flips the native action-row buttons
         *  (Nifty / Nasdaq / DAX) to match the new space — runs on the UI
         *  thread because that's what View visibility changes need. */
        @JavascriptInterface
        fun setCurrentSpace(space: String) {
            val s = space.uppercase()
            if (s == "IN" || s == "US" || s == "DE" || s == "BN") {
                config.currentSpace = s
                runOnUiThread { applySpaceVisibility() }
            }
        }

        /** Quick access for the dashboard to know if T212 is hooked up — drives
         *  the empty-state on US-space tabs. */
        @JavascriptInterface
        fun isT212Connected(): Boolean = config.hasT212Credentials

        // ── Monitor toggle + scan-status bridges (replaces the native action row) ──
        // The native top-of-screen button row was hidden to reclaim vertical
        // space; the dashboard topBar now hosts compact equivalents that read
        // these getters and call the toggle.
        @JavascriptInterface
        fun isMonitoringActive(): Boolean = isServiceRunning

        @JavascriptInterface
        fun toggleMonitoring() {
            runOnUiThread {
                if (!config.isZerodhaConnected) {
                    Toast.makeText(this@MainActivity, "Configure Zerodha credentials in Settings first", Toast.LENGTH_LONG).show()
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    return@runOnUiThread
                }
                if (isServiceRunning) stopMonitoring() else startMonitoring()
            }
        }

        @JavascriptInterface
        fun isLiveStreamingEnabled(): Boolean = config.livePriceStreamingEnabled

        @JavascriptInterface
        fun toggleLiveStreaming(): Boolean {
            val enabled = !config.livePriceStreamingEnabled
            config.livePriceStreamingEnabled = enabled
            runOnUiThread {
                updateLiveStreamSubscriptions()
                pushLiveStreamStateToWebView()
                Toast.makeText(
                    this@MainActivity,
                    if (enabled) "Live prices enabled" else "Live prices stopped",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return enabled
        }

        @JavascriptInterface
        fun refreshLiveStreamSymbols() {
            runOnUiThread { updateLiveStreamSubscriptions() }
        }

        @JavascriptInterface
        fun isDiscoveryRunning(): Boolean =
            ScanServiceResultHolder.isDiscoveryRunning

        /** What the last Discovery scan said as it ended — "done", "stopped", or
         *  the error that ended it — so the page can explain a scan it never saw
         *  finish rather than spinning on it. */
        @JavascriptInterface
        fun discoveryOutcome(): String = ScanServiceResultHolder.discoveryStatusText

        @JavascriptInterface
        fun getCachedDiscoveryForSpace(space: String): String {
            return try {
                val normalized = space.uppercase()
                val market = when (normalized) {
                    "US" -> "NASDAQ 100"
                    "DE" -> "DAX 100"
                    else -> "NIFTY 500"
                }
                val cached = DiscoveryResultStore.load(applicationContext, market)
                    ?: return gson.toJson(mapOf("ok" to false, "space" to normalized, "error" to "No cached scan"))
                gson.toJson(discoveryPayload(cached, forceComplete = true) + mapOf("ok" to true, "space" to normalized))
            } catch (e: Exception) {
                Log.e(TAG, "getCachedDiscoveryForSpace failed", e)
                gson.toJson(mapOf("ok" to false, "space" to space, "error" to (e.message ?: "unknown")))
            }
        }

        // ── Trading 212 portfolio fetch (US space) ────────────────────────────
        // Reads positions + cash via the Trading 212 REST API (HTTP Basic auth).
        // Result delivered async via window.onT212PortfolioResult(json) so the
        // bridge call returns immediately and JS can show a spinner.
        // Returns raw T212 fields plus per-position StockAnalyzer scoring.
        // ── Phase 2B.1: Trading 212 order primitives ──────────────────────────
        // Place / cancel orders. All run on background threads; the result is
        // delivered via window.onT212OrderResult(json) callback so the UI can
        // show ✓ / ✗ inline. Audit log is written by Trading212Client itself.

        /** Legacy bridge kept for older dashboard assets; T212 is always live. */
        @JavascriptInterface
        fun isT212SimulateOnly(): Boolean = false

        /**
         * Place a single-leg SELL stop order — i.e. the SL leg for an existing
         * position. Quantity defaults to the position's full size; the caller
         * passes the T212 ticker (e.g. "KO_US_EQ") since that's what T212's
         * order endpoint expects, NOT the Yahoo symbol.
         */
        @JavascriptInterface
        fun placeT212StopSell(ticker: String, quantity: Double, stopPrice: Double) {
            thread(isDaemon = true) {
                val out = runT212Order("STOP", "SELL", ticker, quantity,
                    limitPrice = null, stopPrice = stopPrice)
                deliverAiCallback("window.onT212OrderResult", out)
            }
        }

        /** Place a single-leg SELL limit order — i.e. the Target leg. */
        @JavascriptInterface
        fun placeT212LimitSell(ticker: String, quantity: Double, limitPrice: Double) {
            thread(isDaemon = true) {
                val out = runT212Order("LIMIT", "SELL", ticker, quantity,
                    limitPrice = limitPrice, stopPrice = null)
                deliverAiCallback("window.onT212OrderResult", out)
            }
        }

        /**
         * Place a single-leg SELL stop-limit order — same intent as a stop, but
         * with downside-floor protection. When price falls to [stopPrice], the
         * order activates as a LIMIT SELL at [limitPrice] (which must be ≤
         * stopPrice for it to fill on a normal drop). If a gap takes the market
         * below [limitPrice] the order does NOT fill — protects against
         * catastrophic slippage on illiquid / volatile names.
         *
         * Trade-off vs plain STOP: a stop always fills (possibly badly); a
         * stop-limit might not fill at all in a real crash, but never fills at
         * a terrible price. T212 reserves shares for either flavour identically.
         */
        @JavascriptInterface
        fun placeT212StopLimitSell(
            ticker: String,
            quantity: Double,
            stopPrice: Double,
            limitPrice: Double
        ) {
            thread(isDaemon = true) {
                val out = runT212Order("STOP_LIMIT", "SELL", ticker, quantity,
                    limitPrice = limitPrice, stopPrice = stopPrice)
                deliverAiCallback("window.onT212OrderResult", out)
            }
        }

        /**
         * A LIMIT BUY FROM THE STOCK PAGE, sized from an amount of money.
         *
         * Rests at Trading 212 (good till cancelled) at [limit] until the price
         * comes down to it. [cash] is in the account's currency; a dollar stock's
         * share count is worked out at the live rate, as the algos' orders are.
         * The order then shows in Waiting orders → Buys, where it can be cancelled.
         * Result → window.onT212OrderResult.
         */
        @JavascriptInterface
        fun t212LimitBuyCash(symbol: String, space: String, cash: Double, limit: Double) {
            thread(isDaemon = true, name = "limit-buy") {
                val sp = if (space.equals("US", true)) "US" else "DE"
                val sym = symbol.trim().uppercase().removeSuffix(".DE")
                val out = try {
                    if (!(cash > 0.0) || !(limit > 0.0)) throw IllegalArgumentException("enter an amount and a price")
                    val client = com.signalscope.app.network.Trading212Client(config)
                    val ticker = client.resolveT212Ticker(sym, sp)
                        ?: throw IllegalStateException("$sym could not be found at Trading 212")
                    val acc = config.accountCurrency.ifBlank { "EUR" }
                    val inst = if (sp == "DE") "EUR" else "USD"
                    val rate = try { com.signalscope.app.trading.FxRates.rate(applicationContext, acc, inst).rate }
                               catch (e: Exception) { 1.0 }.takeIf { it > 0.0 } ?: 1.0
                    val qty = java.math.BigDecimal(cash * rate / limit)
                        .setScale(4, java.math.RoundingMode.DOWN).toDouble()
                    if (qty <= 0.0) throw IllegalStateException("the amount buys less than one ten-thousandth of a share")
                    // Some instruments take fewer decimals than four (Hensoldt: three).
                    // A precision refusal places nothing, so the same order goes again
                    // at 3, 2, then 0 decimals — never rounded up — as the algos' orders
                    // do. Spaced out: T212 allows about one order every two seconds.
                    var res = ""
                    var sent = false
                    walk@ for (q in com.signalscope.app.trading.WeekTarget.qtyAttempts(qty)) {
                        var rateRetries = 0
                        while (true) {
                            if (sent) Thread.sleep(ORDER_SPACING_MS)
                            sent = true
                            when (val r = client.placeOrder(ticker, q, "BUY", "LIMIT", limit, null)) {
                                is com.signalscope.app.network.Trading212Client.T212Result.Success -> {
                                    res = gson.toJson(mapOf("ok" to true, "id" to r.data, "simulated" to false,
                                        "type" to "LIMIT", "side" to "BUY", "ticker" to ticker, "qty" to q))
                                    break@walk
                                }
                                is com.signalscope.app.network.Trading212Client.T212Result.Failure -> {
                                    res = gson.toJson(mapOf("ok" to false, "error" to r.message, "httpCode" to r.httpCode,
                                        "type" to "LIMIT", "ticker" to ticker))
                                    if (r.httpCode == 429 && rateRetries < 2) { rateRetries++; Thread.sleep(ORDER_RATE_BACKOFF_MS); continue }
                                    if (r.message.contains("precision", true)) continue@walk
                                    break@walk
                                }
                                is com.signalscope.app.network.Trading212Client.T212Result.NotConfigured -> {
                                    res = gson.toJson(mapOf("ok" to false, "error" to "not configured", "ticker" to ticker))
                                    break@walk
                                }
                            }
                        }
                    }
                    // Into the waiting list straight away rather than at the next pass.
                    if (res.contains("\"ok\":true")) try {
                        com.signalscope.app.trading.T212WaitingOrders.refresh(applicationContext)
                    } catch (_: Exception) {}
                    res
                } catch (e: Exception) {
                    gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "limit buy failed"), "ticker" to sym))
                }
                deliverAiCallback("window.onT212OrderResult", out)
            }
        }

        /**
         * THE PRICE ON SHORTER TIMESCALES for the stock page's chart — the last
         * month as hourly closes and the last five days as 15-minute closes, each
         * with its low and high, the same windows the near-low alert's picture
         * draws. Network; reached only through callAsync.
         */
        fun pricePanelsJson(symbol: String, space: String): String {
            val sp = space.uppercase()
            val exch = when (sp) { "DE" -> "DE"; "IN" -> "NSE"; else -> "NASDAQ" }
            val sym = symbol.trim().uppercase().removeSuffix(".DE").removeSuffix(".NS")
            fun panel(res: com.signalscope.app.network.YahooFinanceClient.CandleResult): Map<String, Any?>? {
                val cs = (res as? com.signalscope.app.network.YahooFinanceClient.CandleResult.Success)
                    ?.candles?.filter { it.close > 0.0 } ?: return null
                if (cs.size < 2) return null
                return mapOf("closes" to cs.map { it.close },
                    "low" to cs.minOf { if (it.low > 0.0) it.low else it.close },
                    "high" to cs.maxOf { if (it.high > 0.0) it.high else it.close },
                    "from" to cs.first().timestamp * 1000L, "to" to cs.last().timestamp * 1000L)
            }
            val m1 = try { panel(com.signalscope.app.network.YahooFinanceClient.fetchCandlesAt(sym, exch, "60m", "1mo")) }
                     catch (e: Exception) { null }
            val d5 = try { panel(com.signalscope.app.network.YahooFinanceClient.fetchCandles15mRange(sym, exch, "5d")) }
                     catch (e: Exception) { null }
            return gson.toJson(mapOf("ok" to (m1 != null || d5 != null), "m1" to m1, "d5" to d5,
                "error" to (if (m1 == null && d5 == null) "the price feed did not answer — try again" else "")))
        }

        /** Place a BUY limit order — used by the watchlist "buy on dip" flow. */
        @JavascriptInterface
        fun placeT212LimitBuy(ticker: String, quantity: Double, limitPrice: Double) {
            thread(isDaemon = true) {
                val out = runT212Order("LIMIT", "BUY", ticker, quantity,
                    limitPrice = limitPrice, stopPrice = null)
                deliverAiCallback("window.onT212OrderResult", out)
            }
        }

        /**
         * App-managed OCO — places SL stop + Target limit as two SEPARATE live
         * orders. T212 has no native OCO, so the trailing pass reconciles fills
         * and cancels the surviving leg when it next runs.
         */
        @JavascriptInterface
        fun placeT212SellOco(ticker: String, quantity: Double, stopPrice: Double, limitPrice: Double) {
            deliverAiCallback(
                "window.onT212OrderResult",
                "{\"ok\":false,\"ticker\":\"${ticker.replace("\"","")}\",\"error\":\"Trading 212 API does not support native OCO/GTT pairs. A pending sell order reserves the shares, so the second full-quantity sell leg is rejected as owned 0.0. Place either SL or target as the live order.\"}"
            )
            return
            thread(isDaemon = true) {
                val slJson = runT212Order("STOP", "SELL", ticker, quantity, null, stopPrice)
                val tgJson = runT212Order("LIMIT", "SELL", ticker, quantity, limitPrice, null)
                // Record the pair on disk so the trailing routine can later
                // reconcile fills + cancel survivors. Parse the synthesised
                // JSON we just built — slJson/tgJson are already the result
                // strings runT212Order produced.
                var rollbackJson = ""
                try {
                    val sl = com.google.gson.JsonParser.parseString(slJson).asJsonObject
                    val tg = com.google.gson.JsonParser.parseString(tgJson).asJsonObject
                    val slOk = sl.get("ok")?.asBoolean == true
                    val tgOk = tg.get("ok")?.asBoolean == true
                    val slId = if (slOk) sl.get("id")?.asLong ?: 0L else 0L
                    val tgId = if (tgOk) tg.get("id")?.asLong ?: 0L else 0L
                    if (slOk && tgOk && slId > 0L && tgId > 0L) {
                        // Pull avgPrice from the latest portfolio snapshot if
                        // available — needed for hardFloor recompute on trail.
                        val avgPrice = try {
                            val portRes = com.signalscope.app.network.Trading212Client(config).fetchPortfolio()
                            (portRes as? com.signalscope.app.network.Trading212Client.T212Result.Success)
                                ?.data?.firstOrNull { it.ticker == ticker }?.averagePrice ?: 0.0
                        } catch (_: Exception) { 0.0 }
                        com.signalscope.app.data.T212OcoStore.add(applicationContext,
                            com.signalscope.app.data.T212OcoStore.Pair(
                                ticker = ticker,
                                slOrderId = slId,
                                tgtOrderId = tgId,
                                qty = quantity,
                                slPrice = stopPrice,
                                tgtPrice = limitPrice,
                                avgPrice = avgPrice,
                                createdAt = System.currentTimeMillis(),
                                simulated = false
                            ))
                    } else if (slOk != tgOk) {
                        val orphanLeg = if (slOk) "sl" else "tgt"
                        val orphanId = if (slOk) slId else tgId
                        if (orphanId > 0L) {
                            val cancel = com.signalscope.app.network.Trading212Client(config).cancelOrder(orphanId)
                            rollbackJson = when (cancel) {
                                is com.signalscope.app.network.Trading212Client.T212Result.Success ->
                                    ",\"rollback\":{\"attempted\":true,\"ok\":true,\"leg\":\"$orphanLeg\",\"id\":$orphanId}"
                                is com.signalscope.app.network.Trading212Client.T212Result.Failure ->
                                    ",\"rollback\":{\"attempted\":true,\"ok\":false,\"leg\":\"$orphanLeg\",\"id\":$orphanId,\"error\":\"${cancel.message.replace("\"","")}\"}"
                                is com.signalscope.app.network.Trading212Client.T212Result.NotConfigured ->
                                    ",\"rollback\":{\"attempted\":true,\"ok\":false,\"leg\":\"$orphanLeg\",\"id\":$orphanId,\"error\":\"not configured\"}"
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to record T212 OCO pair", e)
                }
                deliverAiCallback("window.onT212OrderResult",
                    "{\"ok\":true,\"oco\":true,\"sl\":$slJson,\"tgt\":$tgJson$rollbackJson}")
            }
        }

        // ── Phase 2B.2: Daily trailing + OCO reconcile ────────────────────────
        // Manual trigger for now — runs the whole trailing pass on a background
        // thread, delivers a structured summary via window.onT212TrailResult.
        // Once you trust it, we'll wire an AlarmManager tick at 21:50 IST
        // (≈3:50 PM ET) to fire it automatically every weekday.
        @JavascriptInterface
        fun runT212Trailing(autoCreateOco: Boolean = false) {
            thread(isDaemon = true) {
                val resultJson: String = try {
                    val s = com.signalscope.app.trading.Trading212TrailingManager.run(
                        applicationContext, config, autoCreateOco
                    )
                    val gson = com.google.gson.Gson()
                    "{\"ok\":true,\"summary\":${gson.toJson(s)}}"
                } catch (e: Exception) {
                    Log.e(TAG, "runT212Trailing failed", e)
                    "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
                }
                deliverAiCallback("window.onT212TrailResult", resultJson)
            }
        }

        /** Returns the persisted T212 OCO pairs as JSON — drives badges in UI. */
        @JavascriptInterface
        fun reconcileT212OcoPairs() {
            thread(isDaemon = true) {
                val resultJson: String = try {
                    val s = com.signalscope.app.trading.Trading212TrailingManager.reconcileOnly(
                        applicationContext, config
                    )
                    "{\"ok\":${s.errors.isEmpty()},\"summary\":${gson.toJson(s)}}"
                } catch (e: Exception) {
                    Log.e(TAG, "reconcileT212OcoPairs failed", e)
                    "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
                }
                deliverAiCallback("window.onT212ReconcileResult", resultJson)
            }
        }

        @JavascriptInterface
        fun getT212OcoPairs(): String {
            return try {
                com.google.gson.Gson().toJson(
                    com.signalscope.app.data.T212OcoStore.loadAll(applicationContext)
                )
            } catch (e: Exception) {
                Log.e(TAG, "getT212OcoPairs failed", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun deleteStaleT212OcoPairs(tickersJson: String): String {
            thread(isDaemon = true) {
                val deleted = mutableListOf<String>()
                val failed = mutableListOf<String>()
                try {
                    val arr = org.json.JSONArray(tickersJson)
                    val wanted = mutableSetOf<String>()
                    for (i in 0 until arr.length()) {
                        val t = arr.optString(i).trim()
                        if (t.isNotEmpty()) wanted.add(t)
                    }
                    val pairs = com.signalscope.app.data.T212OcoStore.loadAll(applicationContext)
                        .filter { wanted.contains(it.ticker) }
                    val client = com.signalscope.app.network.Trading212Client(config)
                    pairs.forEachIndexed { idx, pair ->
                        fun cancelLeg(id: Long): Boolean {
                            if (pair.simulated || id < 0L) return true
                            return when (val r = client.cancelOrder(id)) {
                                is com.signalscope.app.network.Trading212Client.T212Result.Success -> true
                                is com.signalscope.app.network.Trading212Client.T212Result.Failure -> {
                                    failed.add("{\"ticker\":\"${pair.ticker}\",\"id\":$id,\"error\":\"${r.message.replace("\"","")}\"}")
                                    false
                                }
                                is com.signalscope.app.network.Trading212Client.T212Result.NotConfigured -> {
                                    failed.add("{\"ticker\":\"${pair.ticker}\",\"id\":$id,\"error\":\"not configured\"}")
                                    false
                                }
                            }
                        }
                        val slOk = cancelLeg(pair.slOrderId)
                        val tgtOk = cancelLeg(pair.tgtOrderId)
                        if (slOk && tgtOk) {
                            com.signalscope.app.data.T212OcoStore.remove(applicationContext, pair.ticker)
                            deleted.add("\"${pair.ticker}\"")
                        }
                        if (idx < pairs.size - 1) {
                            try { Thread.sleep(400L) } catch (_: InterruptedException) {}
                        }
                    }
                    deliverAiCallback(
                        "window.onT212StaleOcoDeleteResult",
                        "{\"ok\":${failed.isEmpty()},\"deleted\":[${deleted.joinToString(",")}],\"failed\":[${failed.joinToString(",")}],\"total\":${pairs.size}}"
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "deleteStaleT212OcoPairs failed", e)
                    deliverAiCallback(
                        "window.onT212StaleOcoDeleteResult",
                        "{\"ok\":false,\"deleted\":[${deleted.joinToString(",")}],\"failed\":[${failed.joinToString(",")}],\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
                    )
                }
            }
            return "{\"ok\":true,\"pending\":true}"
        }

        @JavascriptInterface
        fun cancelT212Order(orderId: Long) {
            thread(isDaemon = true) {
                val text: String = try {
                    val client = com.signalscope.app.network.Trading212Client(config)
                    when (val r = client.cancelOrder(orderId)) {
                        is com.signalscope.app.network.Trading212Client.T212Result.Success -> {
                            com.signalscope.app.trading.T212WaitingOrders.drop(applicationContext, orderId)
                            "{\"ok\":true,\"action\":\"cancel\",\"id\":$orderId}"
                        }
                        is com.signalscope.app.network.Trading212Client.T212Result.Failure -> {
                            // Not found = already filled or cancelled: gone from the list too.
                            if (r.message.contains("404") || r.message.contains("not found", true))
                                com.signalscope.app.trading.T212WaitingOrders.drop(applicationContext, orderId)
                            "{\"ok\":false,\"action\":\"cancel\",\"id\":$orderId,\"error\":\"${r.message.replace("\"","")}\"}"
                        }
                        is com.signalscope.app.network.Trading212Client.T212Result.NotConfigured ->
                            "{\"ok\":false,\"error\":\"not configured\"}"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "cancelT212Order failed", e)
                    "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
                }
                deliverAiCallback("window.onT212OrderResult", text)
            }
        }

        /**
         * Bulk single-leg T212 exit placement. Receives a JSON payload from the
         * dashboard "Bulk SL" / "Bulk Target" buttons of the form:
         *   { "mode": "STOP" | "LIMIT",
         *     "orders": [ { "ticker": "...", "qty": N, "price": P }, ... ] }
         *
         * Each order is placed sequentially with 2500 ms spacing — T212 rate-
         * limits order endpoints to ~1 req / 2 s, so going faster risks 429s
         * mid-batch. Per-order failure is recorded but doesn't abort the batch.
         * Every placement (success OR failure) lands in the GTT audit log so
         * the user has the full trail. Result delivered via the bulk-specific
         * callback `window.onT212BulkResult(json)` with shape:
         *   { ok, mode, requested, placed, failures: [{ticker, error}] }
         */
        @JavascriptInterface
        fun placeT212BulkSingleLeg(payloadJson: String) {
            thread(isDaemon = true) {
                val resultJson: String = try {
                    val obj = org.json.JSONObject(payloadJson)
                    val mode = obj.optString("mode", "STOP").uppercase()
                    if (mode != "STOP" && mode != "LIMIT") {
                        throw IllegalArgumentException("mode must be STOP or LIMIT, got '$mode'")
                    }
                    val orders = obj.optJSONArray("orders")
                        ?: throw IllegalArgumentException("missing 'orders' array")
                    val client = com.signalscope.app.network.Trading212Client(config)
                    val failures = mutableListOf<Map<String, String>>()
                    var placed = 0
                    val total = orders.length()
                    for (i in 0 until total) {
                        val o = orders.getJSONObject(i)
                        val ticker = o.optString("ticker", "")
                        val qty = o.optDouble("qty", 0.0)
                        val price = o.optDouble("price", 0.0)
                        if (ticker.isBlank() || qty <= 0.0 || price <= 0.0) {
                            failures.add(mapOf(
                                "ticker" to ticker,
                                "error" to "invalid input (qty=$qty price=$price)"
                            ))
                            continue
                        }
                        val limitPrice = if (mode == "LIMIT") price else null
                        val stopPrice = if (mode == "STOP") price else null
                        try {
                            val r = client.placeOrder(
                                ticker = ticker,
                                quantity = qty,
                                side = "SELL",
                                orderType = mode,
                                limitPrice = limitPrice,
                                stopPrice = stopPrice
                            )
                            when (r) {
                                is com.signalscope.app.network.Trading212Client.T212Result.Success ->
                                    placed++
                                is com.signalscope.app.network.Trading212Client.T212Result.Failure ->
                                    failures.add(mapOf("ticker" to ticker, "error" to r.message))
                                is com.signalscope.app.network.Trading212Client.T212Result.NotConfigured ->
                                    failures.add(mapOf("ticker" to ticker, "error" to "not configured"))
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Bulk T212 leg failed for $ticker", e)
                            failures.add(mapOf(
                                "ticker" to ticker,
                                "error" to (e.message ?: "exception")
                            ))
                        }
                        // Rate-limit-aware spacing — last iteration doesn't sleep
                        if (i < total - 1) Thread.sleep(2500L)
                    }
                    val gson = com.google.gson.Gson()
                    val payload = mapOf(
                        "ok" to true,
                        "mode" to mode,
                        "requested" to total,
                        "placed" to placed,
                        "failures" to failures
                    )
                    gson.toJson(payload)
                } catch (e: Exception) {
                    Log.e(TAG, "placeT212BulkSingleLeg failed", e)
                    "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
                }
                deliverAiCallback("window.onT212BulkResult", resultJson)
            }
        }

        /**
         * RUN A CALL THAT WAITS ON THE INTERNET WITHOUT FREEZING THE PAGE.
         *
         * A call from the page answers on the page's own thread, so while one of
         * these waited on Yahoo, Trading 212 or Zerodha — up to 30 seconds on a
         * slow Kite reply — the whole dashboard sat frozen: no scrolling, no taps.
         * Here the same call runs in the background and its answer is handed back
         * to the page by request id when it arrives.
         *
         * ONLY THESE CALLS. The list is explicit on purpose: the page cannot name
         * an arbitrary method, and every entry is a read — none of them places an
         * order.
         */
        @JavascriptInterface
        fun callAsync(method: String, argsJson: String, reqId: String) {
            thread(isDaemon = true, name = "page-async-$method") {
                val out = try {
                    val a = com.google.gson.JsonParser.parseString(argsJson.ifBlank { "[]" }).asJsonArray
                    when (method) {
                        "searchSymbolsForSpace" -> searchSymbolsForSpace(a[0].asString, a[1].asString)
                        "t212GermanRef" -> t212GermanRef(a[0].asString, a[1].asDouble)
                        "t212Fills" -> t212Fills(a[0].asInt)
                        "fetchGttExecutionData" -> fetchGttExecutionData()
                        "macdLadder" -> macdLadderJson(a[0].asString, a[1].asString)
                        "floorRunNow" -> floorRunNow(a[0].asString)
                        "algoTakeProfit" -> gson.toJson(com.signalscope.app.trading.AlgoTakeProfit
                            .takeNow(applicationContext, a[0].asString, a[1].asString))
                        "weekTargetRemove" -> gson.toJson(mapOf("msg" to com.signalscope.app.trading.T212WeekTargets
                            .remove(applicationContext, a[0].asString, a[1].asString)))
                        // YOUR OWN TARGET for an app position — the target window,
                        // opened from an algo or watchlist row (or from the portfolio
                        // on a holding that is one position's alone). It replaces the
                        // position's own target; it never places a second order.
                        "positionTargetContext" -> {
                            val ns = a[0].asString; val pid = a[1].asString
                            val m = if (com.signalscope.app.trading.Spaces.baseOf(ns) == "in")
                                com.signalscope.app.trading.ZerodhaLiveMirror.targetContext(applicationContext, ns, pid)
                            else com.signalscope.app.trading.T212WeekTargets.context(applicationContext, ns, pid)
                            gson.toJson(m ?: mapOf("ok" to false, "error" to "That position is not open in the app's records."))
                        }
                        "positionTargetSet" -> {
                            val ns = a[0].asString; val pid = a[1].asString; val px = a[2].asDouble
                            gson.toJson(mapOf("msg" to (if (com.signalscope.app.trading.Spaces.baseOf(ns) == "in")
                                com.signalscope.app.trading.ZerodhaLiveMirror.setTargetLeg(applicationContext, ns, pid, px)
                            else com.signalscope.app.trading.T212WeekTargets.setManual(applicationContext, ns, pid, px))))
                        }
                        "t212SoleOwner" -> gson.toJson(com.signalscope.app.trading.T212WeekTargets
                            .soleOwner(applicationContext, a[0].asString, a[1].asDouble) ?: mapOf("ns" to ""))
                        "dailyMacd" -> dailyMacdJson(a[0].asString, a[1].asString)
                        "exitLevels" -> exitLevelsJson(a[0].asString, a[1].asString)
                        "pricePanels" -> pricePanelsJson(a[0].asString, a[1].asString)
                        // A first full history rebuild can take minutes at Trading
                        // 212's pace; on the page's own thread it froze the app.
                        "t212ClosedTrades" -> t212ClosedTrades(a[0].asBoolean, a[1].asString)
                        // NOT AN ORDER: asks the RV gate whether now is the moment. On
                        // "now" the page runs the button's own order as before; on
                        // "wait" the order is queued for the service to start later.
                        "rvGateOrder" -> rvGateOrderJson(a[0].asString, a[1].asString)
                        "t212WaitingBuysRefresh" -> {
                            com.signalscope.app.trading.T212WaitingOrders.refresh(applicationContext)
                            t212WaitingBuys()
                        }
                        "zerodhaClosedTrades" -> zerodhaClosedTrades(a[0].asBoolean)
                        "binanceClosedTrades" -> binanceClosedTrades(a[0].asBoolean)
                        else -> "{\"error\":\"not callable in the background: $method\"}"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "callAsync $method failed", e)
                    "{\"error\":\"${(e.message ?: "failed").replace("\"", "")}\"}"
                }
                // Two rare line-break characters end a script string early; the
                // broker's text is not ours to trust not to contain them.
                val escaped = out.replace("\\", "\\\\").replace("'", "\\'")
                    .replace("\n", "\\n").replace("\r", "")
                    .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
                val id = reqId.replace(Regex("[^A-Za-z0-9_]"), "")
                runOnUiThread {
                    try {
                        if (!isFinishing && !isDestroyed)
                            webView.evaluateJavascript("window.onAsyncResult('$id','$escaped')", null)
                    } catch (e: Throwable) { Log.e(TAG, "callAsync delivery failed", e) }
                }
            }
        }

        /** The five MACD-slope readings for the stock detail view — the same
         *  routine as the watcher alerts. Only reached through [callAsync]: it
         *  downloads prices, and the page must not wait on it. */
        fun macdLadderJson(symbol: String, space: String): String {
            val sp = space.lowercase().let { if (it == "bn") "binance" else it }
            val base = com.signalscope.app.trading.Spaces.baseOf(sp)
            if (base == "binance") return "{\"ok\":false,\"error\":\"not for crypto\"}"
            val r = com.signalscope.app.trading.MacdLadder.readings(applicationContext, base, symbol)
                ?: return "{\"ok\":false,\"error\":\"no data\"}"
            return gson.toJson(mapOf("ok" to true,
                "readings" to r.map { mapOf("label" to it.label, "sign" to it.sign) }))
        }

        /**
         * THE DAILY MACD, FRESH — for the stock sheet's chart.
         *
         * The sheet used to draw the 30 values stored on the Discovery scan, which
         * are only as current as that scan: a US scan left from August drew
         * August's MACD under today's name. This fetches the daily prices when
         * the sheet opens and returns the standard 12/26/9 MACD, its signal line
         * and the histogram for the last six months or so — the same picture a
         * broker's chart shows. Today's bar is included while the session runs,
         * as a broker's chart does.
         */
        fun dailyMacdJson(symbol: String, space: String): String {
            val sp = space.uppercase().let { if (it == "BINANCE") "BN" else it }
            val sym = symbol.trim().uppercase().removeSuffix(".NS").removeSuffix(".DE")
            if (sym.isBlank()) return "{\"ok\":false,\"error\":\"no symbol\"}"
            val bars = com.signalscope.app.screener.ScreenerRunner.fetchDailyBarsFor(sp, sym)
                ?: return "{\"ok\":false,\"error\":\"the price feed is busy — try again in a minute\"}"
            if (bars.size < 40) return "{\"ok\":false,\"error\":\"not enough daily prices\"}"
            val closes = bars.map { it.close }
            val m = com.signalscope.app.util.Indicators.macd(closes)
            val keep = 130
            val from = (bars.size - keep).coerceAtLeast(0)
            fun r(x: Double) = Math.round(x * 10000.0) / 10000.0
            return gson.toJson(mapOf("ok" to true, "symbol" to sym,
                "d" to bars.subList(from, bars.size).map { it.date.toString() },
                "c" to closes.subList(from, closes.size).map { r(it) },
                "m" to m.macdLine.subList(from, closes.size).map { r(it) },
                "s" to m.signalLine.subList(from, closes.size).map { r(it) },
                "h" to m.histogram.subList(from, closes.size).map { r(it) }))
        }

        /**
         * The levels a Trading 212 target or stop can be set at: the 3-day, 1-week
         * and 1-month highs and lows and the clustered support and resistance —
         * the same reading the watchlist shows, on the watchlist's own feed, so the
         * numbers agree. Worked out on demand because a holding need not be on the
         * watchlist. Only reached through [callAsync]: it downloads prices.
         */
        fun exitLevelsJson(symbol: String, space: String): String {
            val base = when (space.uppercase()) { "US" -> "us"; "IN" -> "in"; else -> "de" }
            val sym = symbol.trim().uppercase().removeSuffix(".DE").removeSuffix(".NS")
            if (sym.isBlank()) return "{\"ok\":false,\"error\":\"no symbol\"}"
            val lb = com.signalscope.app.trading.StrategyLabRunner.latestClosedBar(
                applicationContext, com.signalscope.app.data.ManualWatchStore.nsFor(base), sym,
                com.signalscope.app.trading.Spaces.exchangeOf(base))
                ?: return "{\"ok\":false,\"error\":\"the price feed is busy — try again in a minute\"}"
            return gson.toJson(mapOf("ok" to true, "symbol" to sym, "price" to lb.close,
                "levels" to lb.levels))
        }

        // ── THE NOTIFICATION LIST ─────────────────────────────────────────
        @JavascriptInterface
        fun notificationLog(): String = try {
            val st = com.signalscope.app.data.NotificationLog.load(applicationContext)
            gson.toJson(mapOf("ok" to true, "seenAt" to st.seenAt, "entries" to st.entries))
        } catch (e: Exception) { "{\"ok\":false,\"entries\":[]}" }

        @JavascriptInterface
        fun notificationUnread(): Int = try {
            com.signalscope.app.data.NotificationLog.unread(applicationContext)
        } catch (e: Exception) { 0 }

        @JavascriptInterface
        fun notificationSeen() {
            try { com.signalscope.app.data.NotificationLog.markSeen(applicationContext) } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun notificationClear() {
            try { com.signalscope.app.data.NotificationLog.clear(applicationContext) } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun fetchT212OpenOrders(): String {
            return try {
                val client = com.signalscope.app.network.Trading212Client(config)
                when (val r = client.fetchOpenOrders()) {
                    is com.signalscope.app.network.Trading212Client.T212Result.Success -> r.data
                    is com.signalscope.app.network.Trading212Client.T212Result.Failure ->
                        "{\"error\":\"${r.message.replace("\"","")}\"}"
                    is com.signalscope.app.network.Trading212Client.T212Result.NotConfigured ->
                        "{\"error\":\"not configured\"}"
                }
            } catch (e: Exception) {
                "{\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        // ── German reference price: Tradegate (real-time) with Yahoo (delayed,
        // ~15min on Xetra — measured, not assumed) as fallback and display
        // companion. Never surfaces a Tradegate failure to the user as an
        // error — a rejected/unreachable quote just means "use Yahoo," same
        // as if Tradegate didn't exist. Called once per confirm dialog, so a
        // blocking network call here (same pattern as fetchT212OpenOrders
        // above) is cheap enough not to need a background thread.
        @JavascriptInterface
        fun t212GermanRef(tickerOrSymbol: String, yahooRefPrice: Double): String {
            return try {
                val client = com.signalscope.app.network.Trading212Client(config)
                val raw = tickerOrSymbol.trim()
                val ticker = if (raw.contains("_")) raw else client.resolveT212Ticker(raw.uppercase(), "DE")
                val isin = ticker?.let {
                    when (val r = client.fetchInstrumentMetadata(setOf(it))) {
                        is com.signalscope.app.network.Trading212Client.T212Result.Success -> r.data[it]?.isin
                        else -> null
                    }
                }
                // Through the same reference ladder as every other German price:
                // a feed that has stopped no longer gets to veto the live venue.
                val feedSym = ticker?.let { client.t212ToYahooSymbol(it) } ?: raw
                val feedStale = com.signalscope.app.network.FeedHealth.stoppedSince(feedSym) > 0L
                val q = isin?.let {
                    com.signalscope.app.trading.GermanRealtime.checkedQuote(applicationContext, it,
                        feedSym, ticker, yahooRefPrice, feedStale)
                }
                if (q != null) {
                    gson.toJson(mapOf(
                        "source" to "tradegate", "price" to q.mid, "bid" to q.bid, "ask" to q.ask,
                        "last" to q.last, "yahooPrice" to yahooRefPrice))
                } else {
                    gson.toJson(mapOf("source" to "yahoo", "price" to yahooRefPrice, "yahooPrice" to yahooRefPrice))
                }
            } catch (e: Exception) {
                gson.toJson(mapOf("source" to "yahoo", "price" to yahooRefPrice, "yahooPrice" to yahooRefPrice))
            }
        }

        /** Internal helper — runs placeOrder + builds the JSON response. */
        // ── MANUAL US ORDERS — user-initiated one-tap buy / sell-to-cash. ──
        // NOT gated by the T212 live freeze: that freeze stops the ALGO
        // from placing orders; these fire only on an explicit user tap.
        // Both use MARKETABLE LIMITS (never market orders, per user doctrine):
        // buy capped at ref×1.005, sell floored at ref×0.995 — near-certain
        // immediate fill with a hard bound on the worst-case price.

        /** Minimum gap between two order requests (T212 allows ~1 order / 2s). */
        private val ORDER_SPACING_MS = 2500L
        /** Extra wait after a 429 before re-sending the same order. */
        private val ORDER_RATE_BACKOFF_MS = 6000L

        @JavascriptInterface
        fun t212ManualBuy(symbol: String, usdAmount: Double, refPrice: Double, space: String) {
            thread(isDaemon = true) {
                val spc = if (space.uppercase() == "DE") "DE" else "US"
                val out = try {
                    if (usdAmount <= 0.0) throw IllegalArgumentException("Enter an amount greater than 0")
                    if (refPrice <= 0.0) throw IllegalArgumentException("No reference price — run a scan or wait for the live quote")
                    val client = com.signalscope.app.network.Trading212Client(config)
                    val raw = symbol.trim()
                    // A pre-resolved T212 ticker (contains "_", e.g. a held position's
                    // exact ticker passed from the UI) is used as-is — bypasses the
                    // Yahoo→T212 resolver entirely, so it's always correct. Case is
                    // SIGNIFICANT here: Xetra tickers carry a lowercase venue letter
                    // ("SZGd_EQ"), so uppercasing one turns it into a 404.
                    val ticker = if (raw.contains("_")) raw
                    else {
                        val sym = raw.uppercase()   // Yahoo symbols are case-insensitive
                        resolveTickerWithRetry(client, sym, spc)
                            ?: throw IllegalArgumentException(
                                "Couldn't resolve \"$sym\": ${client.resolveFailureReason(sym, spc)}")
                    }
                    val limit = Math.round(refPrice * 1.005 * 100.0) / 100.0
                    val rawQty = usdAmount / refPrice
                    // Adaptive quantity precision: European instruments often reject
                    // 5-dp fractional quantities. A precision-mismatch is a 400 that
                    // places NOTHING, so retrying at a coarser precision is safe —
                    // step 5→3→2→whole until T212 accepts.
                    // Built with Gson, never string concatenation: a broker error body can
                    // contain newlines/quotes, and a hand-built string turns those into
                    // invalid JSON — which shows the user a parse error INSTEAD of the
                    // real reason their order failed.
                    var res = gson.toJson(mapOf("ok" to false, "error" to "no order attempted", "ticker" to ticker))
                    // T212 rate-limits order placement (~1 request / 2s). The precision
                    // walk below fires several orders, so each attempt after the first
                    // MUST be spaced out or the retry trips a 429 on itself.
                    var sentOnce = false
                    loop@ for (dp in intArrayOf(5, 3, 2, 0)) {
                        val f = Math.pow(10.0, dp.toDouble())
                        val q = Math.floor(rawQty * f) / f
                        if (q <= 0.0) {
                            res = gson.toJson(mapOf("ok" to false, "ticker" to ticker,
                                "error" to ("${"%.2f".format(usdAmount)} is too small for $ticker's share step " +
                                    "(rounds to 0 at ${dp}dp — this instrument may be whole-share only; try a larger amount).")))
                            break@loop
                        }
                        var rateRetries = 0
                        attempt@ while (true) {
                            if (sentOnce) Thread.sleep(ORDER_SPACING_MS)
                            sentOnce = true
                            when (val r = client.placeOrder(ticker, q, "BUY", "LIMIT", limit, null)) {
                                is com.signalscope.app.network.Trading212Client.T212Result.Success ->
                                    { res = gson.toJson(mapOf("ok" to true, "id" to r.data, "type" to "LIMIT",
                                        "side" to "BUY", "ticker" to ticker, "qty" to q)); break@loop }
                                is com.signalscope.app.network.Trading212Client.T212Result.Failure -> {
                                    res = gson.toJson(mapOf("ok" to false, "error" to r.message,
                                        "httpCode" to r.httpCode, "ticker" to ticker))
                                    // A 429 places NOTHING — safe to back off and re-send
                                    // the SAME quantity (never a coarser one).
                                    if (r.httpCode == 429 && rateRetries < 2) {
                                        rateRetries++; Thread.sleep(ORDER_RATE_BACKOFF_MS); continue@attempt
                                    }
                                    // A precision reject also places nothing → try coarser.
                                    if (r.message.contains("precision", true)) break@attempt
                                    break@loop  // any other error is real → stop
                                }
                                is com.signalscope.app.network.Trading212Client.T212Result.NotConfigured ->
                                    { res = gson.toJson(mapOf("ok" to false, "error" to "not configured", "ticker" to ticker)); break@loop }
                            }
                        }
                    }
                    res
                } catch (e: Exception) {
                    gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "manual buy failed"), "ticker" to symbol))
                }
                deliverAiCallback("window.onT212OrderResult", out)
            }
        }

        @JavascriptInterface
        fun t212ManualSell(ticker: String, quantity: Double, refPrice: Double) {
            thread(isDaemon = true) {
                val out = try {
                    if (quantity <= 0.0) throw IllegalArgumentException("No held quantity to sell")
                    if (refPrice <= 0.0) throw IllegalArgumentException("No reference price — run a scan or wait for the live quote")
                    val client = com.signalscope.app.network.Trading212Client(config)
                    val t = ticker.trim()
                    // A resting SL/target order reserves the shares and would make
                    // the sell bounce with "owned: 0.0" — clear the ticker's open
                    // orders first (T212's one-working-order constraint).
                    try { client.cancelOpenOrdersForTicker(t) }
                    catch (e: Exception) { Log.w(TAG, "manual pre-sell cancel failed", e) }
                    val limit = Math.round(refPrice * 0.995 * 100.0) / 100.0
                    runT212Order("LIMIT", "SELL", t, quantity, limitPrice = limit, stopPrice = null)
                } catch (e: Exception) {
                    gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "manual sell failed"), "ticker" to ticker))
                }
                deliverAiCallback("window.onT212OrderResult", out)
            }
        }

        // Patient-ladder tuning, ATR estimation, fresh-price re-anchoring,
        // cancel-confirmation and fill-polling all moved to
        // trading/PatientExecutor.kt so the algo live-mirror can reuse the same
        // proven executor. Only resolveTickerWithRetry stays — it is still used by
        // the plain (non-patient) order path below.

        /**
         * Resolve with one rate-limit retry. T212's instrument-list endpoint
         * allows 1 request / 30s; on a cold cache the first resolve can fail
         * with "list not loaded yet" (observed live with AVGO). We're on a
         * background thread, so waiting out one window beats erroring at the
         * user. Still null after the retry → genuinely unresolvable.
         */
        private fun resolveTickerWithRetry(
            client: com.signalscope.app.network.Trading212Client, sym: String, spc: String
        ): String? {
            client.resolveT212Ticker(sym, spc)?.let { return it }
            Thread.sleep(31_000L)
            return client.resolveT212Ticker(sym, spc)
        }


        // ── PATIENT ORDER BRIDGES ─────────────────────────────────────────
        // The ladder itself now lives in trading/PatientExecutor.kt so the algo
        // live-mirror can call the SAME proven executor instead of a market order.
        // These are thin adapters: run it off the UI thread, forward its progress
        // to the WebView on the unchanged window.onT212PatientProgress contract,
        // post the same notification, deliver the same result JSON.
        private fun patientProgressSink(): (Map<String, Any?>) -> Unit = { m ->
            deliverAiCallback("window.onT212PatientProgress", gson.toJson(m))
        }

        /** One bench order at a time. Two concurrent ladders on the same account
         *  fight over the cancel-open-orders step and can strand each other's
         *  rungs — and the bench exists to isolate one variable, not race. */
        private val benchBusy = java.util.concurrent.atomic.AtomicBoolean(false)

        @JavascriptInterface
        fun t212PatientSell(ticker: String, yahooSymbol: String, space: String, quantity: Double, refPrice: Double) {
            thread(isDaemon = true) {
                val r = com.signalscope.app.trading.PatientExecutor.sell(
                    config, ticker, yahooSymbol, space, quantity, refPrice, patientProgressSink())
                val out = if (r.error != null)
                    gson.toJson(mapOf("ok" to false, "error" to r.error, "ticker" to r.ticker, "patient" to true))
                else {
                    postT212PatientNotice(r.ticker, "sell", r.ok, r.filled, r.remaining, r.staleOrder)
                    // YOUR SALE, BROUGHT INTO THE BOOKS. When an algo or the watchlist
                    // held these shares, its position is closed at this sale's price
                    // and an algo's seat moves on — instead of the ledger claiming
                    // shares that are gone until someone clears the row by hand.
                    val handled = if (r.filled > 0.0) try {
                        com.signalscope.app.trading.T212HandSales.afterAppSale(
                            applicationContext, r.ticker, r.orderIds, r.refPrice)
                    } catch (e: Exception) { Log.w(TAG, "hand-sale booking failed", e); "" } else ""
                    gson.toJson(mapOf("ok" to r.ok, "ticker" to r.ticker, "sold" to r.filled,
                        "remaining" to r.remaining, "note" to r.note,
                        "patient" to true, "staleOrder" to r.staleOrder, "handled" to handled))
                }
                deliverAiCallback("window.onT212OrderResult", out)
            }
        }

        @JavascriptInterface
        fun t212PatientBuy(symbol: String, yahooSymbol: String, space: String, cashAmount: Double, refPrice: Double) {
            thread(isDaemon = true) {
                val r = com.signalscope.app.trading.PatientExecutor.buy(
                    config, symbol, yahooSymbol, space, cashAmount, refPrice,
                    onProgress = patientProgressSink())
                val out = if (r.error != null)
                    gson.toJson(mapOf("ok" to false, "error" to r.error, "ticker" to r.ticker, "patient" to true))
                else {
                    postT212PatientNotice(r.ticker, "buy", r.ok, r.filled, r.remaining, r.staleOrder)
                    gson.toJson(mapOf("ok" to r.ok, "ticker" to r.ticker, "bought" to r.filled,
                        "remaining" to r.remaining, "note" to r.note,
                        "patient" to true, "staleOrder" to r.staleOrder))
                }
                deliverAiCallback("window.onT212OrderResult", out)
            }
        }

        private fun postT212PatientNotice(
            ticker: String, side: String, filled: Boolean, done: Double, remaining: Double,
            staleOrderWarning: Boolean = false, detail: String? = null
        ) {
            try {
                val ctx = applicationContext
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                val ch = "t212_patient_order"
                nm.createNotificationChannel(android.app.NotificationChannel(
                    ch, "Patient order result", android.app.NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Result of a patient (best-price) Trading 212 order" })
                val pi = android.app.PendingIntent.getActivity(
                    ctx, ticker.hashCode(),
                    android.content.Intent(ctx, MainActivity::class.java),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
                val verb = if (side == "buy") "Bought" else "Sold"
                val title = when {
                    staleOrderWarning -> "⚠️ Patient $side — check T212 app — $ticker"
                    filled -> "✅ Patient $side filled — $ticker"
                    else -> "⏳ Patient $side unfilled — $ticker"
                }
                val text = when {
                    detail != null -> detail
                    // We couldn't confirm T212 actually dropped the last resting order —
                    // don't say "nothing resting" when that might not be true.
                    staleOrderWarning -> "Couldn't confirm the last resting order was cancelled. " +
                        "Open the T212 app and check pending orders for $ticker before placing anything else on it."
                    filled -> "$verb ${"%.6f".format(done)} at your held-out price."
                    else -> "No rung filled to the cap. ${"%.6f".format(remaining)} left un-${side}ed — nothing resting. Retry when you like."
                }
                val n = androidx.core.app.NotificationCompat.Builder(ctx, ch)
                    .setContentTitle(title).setContentText(text)
                    .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
                    .setSmallIcon(android.R.drawable.ic_menu_sort_by_size)
                    .setContentIntent(pi).setAutoCancel(true)
                    .setPriority(if (staleOrderWarning) androidx.core.app.NotificationCompat.PRIORITY_MAX
                                 else androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                    .build()
                com.signalscope.app.data.NotificationLog.post(ctx, nm, 9200 + ticker.hashCode().mod(100), n)
            } catch (e: Exception) { Log.w(TAG, "patient order notice failed", e) }
        }

        private fun runT212Order(
            orderType: String, side: String, ticker: String, qty: Double,
            limitPrice: Double?, stopPrice: Double?
        ): String {
            return try {
                val client = com.signalscope.app.network.Trading212Client(config)
                // Gson, not concatenation — broker error bodies contain newlines/quotes
                // that would otherwise produce invalid JSON and mask the real error.
                when (val r = client.placeOrder(ticker, qty, side, orderType, limitPrice, stopPrice)) {
                    is com.signalscope.app.network.Trading212Client.T212Result.Success ->
                        gson.toJson(mapOf("ok" to true, "id" to r.data, "simulated" to false,
                            "type" to orderType, "side" to side, "ticker" to ticker))
                    is com.signalscope.app.network.Trading212Client.T212Result.Failure ->
                        gson.toJson(mapOf("ok" to false, "error" to r.message, "httpCode" to r.httpCode,
                            "type" to orderType, "ticker" to ticker))
                    is com.signalscope.app.network.Trading212Client.T212Result.NotConfigured ->
                        gson.toJson(mapOf("ok" to false, "error" to "not configured", "ticker" to ticker))
                }
            } catch (e: Exception) {
                Log.e(TAG, "runT212Order failed", e)
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "unknown"), "ticker" to ticker))
            }
        }

        private fun t212YahooExchange(symbol: String): String =
            if (symbol.endsWith(".DE", ignoreCase = true)) "DE" else "NASDAQ"

        private fun enrichT212AnalysisWithFundamentals(
            symbol: String,
            analysis: com.signalscope.app.data.StockAnalysis,
            weights: com.signalscope.app.data.ScoringWeights
        ): com.signalscope.app.data.StockAnalysis {
            return try {
                val key = symbol.uppercase(Locale.US)
                val now = System.currentTimeMillis()
                val fundResult = t212FundamentalsCache[key]
                    ?.takeIf { now - it.first < T212_FUNDAMENTALS_TTL_MS }?.second
                    ?: when (val fetched = com.signalscope.app.network.YahooFinanceClient
                        .fetchFundamentals(symbol, t212YahooExchange(symbol))) {
                        is com.signalscope.app.network.YahooFinanceClient.FundamentalResult.Success ->
                            fetched.also { t212FundamentalsCache[key] = now to it }
                        else -> return analysis
                    }

                val vr = com.signalscope.app.util.ValueAnalyzer.analyze(
                    fundamentals = fundResult.data,
                    currentPrice = analysis.price,
                    w = weights
                )
                val refreshedName = fundResult.data.name?.trim()?.takeIf { it.isNotBlank() } ?: analysis.name
                analysis.copy(
                    name = refreshedName,
                    trailingPe = vr.trailingPe,
                    forwardPe = vr.forwardPe,
                    priceToBook = vr.priceToBook,
                    evToEbitda = vr.evToEbitda,
                    debtToEquity = vr.debtToEquity,
                    roce = vr.roce,
                    revenueGrowth = vr.revenueGrowth,
                    earningsGrowth = vr.earningsGrowth,
                    grossMargins = vr.grossMargins,
                    operatingMargins = vr.operatingMargins,
                    profitMargins = vr.profitMargins,
                    dividendYield = vr.dividendYield,
                    operatingCashflow = vr.operatingCashflow,
                    freeCashflow = vr.freeCashflow,
                    netIncome = vr.netIncome,
                    totalRevenue = vr.totalRevenue,
                    totalCash = vr.totalCash,
                    totalDebt = vr.totalDebt,
                    currentRatio = vr.currentRatio,
                    annualRevenueGrowth = vr.annualRevenueGrowth,
                    annualNetIncomeGrowth = vr.annualNetIncomeGrowth,
                    fiftyTwoWeekLow = vr.fiftyTwoWeekLow,
                    fiftyTwoWeekHigh = vr.fiftyTwoWeekHigh,
                    sharesOutstanding = vr.sharesOutstanding,
                    sector = vr.sector,
                    industry = vr.industry,
                    sectorMedianPe = vr.sectorMedianPe,
                    hasBuyback = vr.hasBuyback,
                    valueScore = vr.valueScore,
                    valueRating = vr.valueRating,
                    valueScoreReport = vr.valueScoreReport
                )
            } catch (e: Exception) {
                Log.w(TAG, "T212 fundamentals enrichment failed for $symbol: ${e.message}")
                analysis
            }
        }

        private fun yahooMatchKeys(symbol: String?): Set<String> {
            val raw = symbol?.trim()?.uppercase(Locale.US).orEmpty()
            if (raw.isBlank()) return emptySet()
            val bare = raw.substringBefore(".")
            return listOf(raw, bare).filter { it.isNotBlank() }.toSet()
        }

        private fun findCachedT212DiscoveryAnalysis(
            client: com.signalscope.app.network.Trading212Client,
            ticker: String,
            currency: String?
        ): com.signalscope.app.data.StockAnalysis? {
            return try {
                val wantedExact = client.t212ToYahooCandidates(ticker, currency)
                    .map { it.trim().uppercase(Locale.US) }
                    .filter { it.isNotBlank() }
                    .toSet()
                val wantedBare = wantedExact.map { it.substringBefore(".") }.toSet()
                if (wantedExact.isEmpty() && wantedBare.isEmpty()) return null

                val cached = DiscoveryResultStore.loadAll(applicationContext)
                    .asSequence()
                    .flatMap { it.allStocks.asSequence() }
                    .toList()

                cached.firstOrNull { a ->
                    val keys = setOf(a.symbol, a.token)
                        .map { it.trim().uppercase(Locale.US) }
                        .filter { it.isNotBlank() }
                    keys.any { it in wantedExact }
                } ?: cached.firstOrNull { a ->
                    val keys = setOf(a.symbol, a.token)
                        .flatMap { yahooMatchKeys(it) }
                    keys.any { it in wantedBare }
                }
            } catch (e: Exception) {
                Log.w(TAG, "T212 cached discovery lookup failed for $ticker: ${e.message}")
                null
            }
        }

        private fun parseT212OpenOrders(raw: String): List<Map<String, Any?>> {
            return try {
                val root = com.google.gson.JsonParser.parseString(raw)
                val arr = when {
                    root.isJsonArray -> root.asJsonArray
                    root.isJsonObject && root.asJsonObject.has("items") -> root.asJsonObject.getAsJsonArray("items")
                    root.isJsonObject && root.asJsonObject.has("orders") -> root.asJsonObject.getAsJsonArray("orders")
                    else -> return emptyList()
                }
                val out = mutableListOf<Map<String, Any?>>()
                for (el in arr) {
                    if (!el.isJsonObject) continue
                    val o = el.asJsonObject
                    val instrument = o.get("instrument")?.takeIf { it.isJsonObject }?.asJsonObject
                    val ticker = o.get("ticker")?.takeIf { !it.isJsonNull }?.asString
                        ?: instrument?.get("ticker")?.takeIf { !it.isJsonNull }?.asString
                        ?: continue
                    val status = o.get("status")?.takeIf { !it.isJsonNull }?.asString ?: "PENDING"
                    val statusKey = status.uppercase(Locale.US)
                    if (statusKey in setOf("CANCELLED", "FILLED", "REJECTED")) continue
                    val type = o.get("type")?.takeIf { !it.isJsonNull }?.asString ?: "ORDER"
                    val quantity = o.get("quantity")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0
                    val side = o.get("side")?.takeIf { !it.isJsonNull }?.asString
                        ?: if (quantity < 0.0) "SELL" else "BUY"
                    val stopPrice = o.get("stopPrice")?.takeIf { !it.isJsonNull }?.asDouble
                    val limitPrice = o.get("limitPrice")?.takeIf { !it.isJsonNull }?.asDouble
                    val price = stopPrice ?: limitPrice ?: 0.0
                    val kind = if (side.equals("SELL", ignoreCase = true)) {
                        when {
                            type.contains("STOP", ignoreCase = true) -> "SL"
                            type.contains("LIMIT", ignoreCase = true) -> "TARGET"
                            else -> "SELL"
                        }
                    } else "BUY"
                    out.add(mapOf(
                        "id" to (o.get("id")?.takeIf { !it.isJsonNull }?.asLong ?: 0L),
                        "ticker" to ticker,
                        "type" to type,
                        "status" to status,
                        "side" to side.uppercase(Locale.US),
                        "quantity" to quantity,
                        "absQuantity" to kotlin.math.abs(quantity),
                        "stopPrice" to stopPrice,
                        "limitPrice" to limitPrice,
                        "price" to price,
                        "kind" to kind
                    ))
                }
                out
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse T212 open orders: ${e.message}")
                emptyList()
            }
        }

        /** The last portfolio shown, straight from disk, so the tab can paint
         *  before the broker has answered. `{"ok":false}` when there is none. */
        @JavascriptInterface
        fun getCachedT212Portfolio(): String =
            com.signalscope.app.data.T212PortfolioStore.load(applicationContext) ?: "{\"ok\":false}"

        /** The broker's own figures for one holding — the part that is fresh on
         *  every fetch, laid over whatever analysis the holding carries. */
        private fun t212LiveFields(
            pos: com.signalscope.app.network.Trading212Client.Position,
            meta: com.signalscope.app.network.Trading212Client.InstrumentMeta?,
            pendingOrders: List<Map<String, Any?>>
        ): Map<String, Any?> {
            val live = mutableMapOf<String, Any?>(
                "ticker"        to pos.ticker,
                "instrumentName" to meta?.name,
                "currency"      to (meta?.currencyCode ?: "USD"),
                "quantity"      to pos.quantity,
                "averagePrice"  to pos.averagePrice,
                "currentPrice"  to pos.currentPrice,
                "pplToday"      to pos.pplToday,
                "pplTotal"      to pos.pplTotal,
                "isin"          to meta?.isin
            )
            if (pendingOrders.isNotEmpty()) {
                live["pendingOrders"] = pendingOrders
                live["reservedSellQuantity"] = pendingOrders
                    .filter { (it["side"] as? String).equals("SELL", ignoreCase = true) }
                    .sumOf { (it["absQuantity"] as? Double) ?: 0.0 }
            }
            return live
        }

        /** A holding as it was last shown, with today's broker figures laid on
         *  top. Pending orders are cleared first: an order that has since filled
         *  or been cancelled must not linger from the old copy. */
        private fun t212Overlay(
            previous: com.google.gson.JsonObject,
            live: Map<String, Any?>
        ): com.google.gson.JsonObject {
            val o = previous.deepCopy()
            o.remove("pendingOrders")
            o.remove("reservedSellQuantity")
            for ((k, v) in live) {
                if (v == null) o.remove(k) else o.add(k, gson.toJsonTree(v))
            }
            return o
        }

        /** Every holding in a saved payload, by broker ticker. */
        private fun t212PositionsByTicker(payloadJson: String?): Map<String, com.google.gson.JsonObject> = try {
            val root = com.google.gson.JsonParser.parseString(payloadJson ?: "{}").asJsonObject
            root.getAsJsonArray("positions")
                ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
                ?.mapNotNull { o -> o.get("ticker")?.takeIf { !it.isJsonNull }?.asString?.let { it to o } }
                ?.toMap()
                ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }

        @JavascriptInterface
        fun fetchT212Portfolio() {
            // Already running: its answer reaches the page all the same.
            if (!t212PortfolioFetchRunning.compareAndSet(false, true)) return
            thread(isDaemon = true) {
              try {
                val resultJson: String = try {
                    if (!config.hasT212Credentials) {
                        "{\"ok\":false,\"error\":\"Trading 212 not configured — add API key + secret in Settings\"}"
                    } else {
                        val t212 = com.signalscope.app.network.Trading212Client(config)
                        val reconcileSummary = try {
                            com.signalscope.app.trading.Trading212TrailingManager.reconcileOnly(
                                applicationContext, config
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "T212 reconcile before portfolio fetch failed", e)
                            null
                        }
                        val cashRes = t212.fetchAccountSummary()
                        val portRes = t212.fetchPortfolio()
                        val positions = when (portRes) {
                            is com.signalscope.app.network.Trading212Client.T212Result.Success -> portRes.data
                            is com.signalscope.app.network.Trading212Client.T212Result.Failure ->
                                return@thread deliverAiCallback(
                                    "window.onT212PortfolioResult",
                                    "{\"ok\":false,\"error\":\"portfolio: ${portRes.message.replace("\"","")}\",\"httpCode\":${portRes.httpCode}}"
                                )
                            is com.signalscope.app.network.Trading212Client.T212Result.NotConfigured ->
                                return@thread deliverAiCallback(
                                    "window.onT212PortfolioResult",
                                    "{\"ok\":false,\"error\":\"not configured\"}"
                                )
                        }

                        // ── Enrichment phase ────────────────────────────────
                        // 1) Pull instrument metadata for company names
                        val tickers = positions.map { it.ticker }.toSet()
                        val metaMap: Map<String, com.signalscope.app.network.Trading212Client.InstrumentMeta> =
                            when (val mRes = t212.fetchInstrumentMetadata(tickers)) {
                                is com.signalscope.app.network.Trading212Client.T212Result.Success -> mRes.data
                                else -> emptyMap()  // Fall through with raw tickers if metadata unavailable
                            }
                        val openOrdersByTicker: Map<String, List<Map<String, Any?>>> =
                            when (val oRes = t212.fetchOpenOrders()) {
                                is com.signalscope.app.network.Trading212Client.T212Result.Success ->
                                    parseT212OpenOrders(oRes.data).groupBy { (it["ticker"] as? String).orEmpty() }
                                is com.signalscope.app.network.Trading212Client.T212Result.Failure -> {
                                    Log.w(TAG, "T212 open-order fetch failed: ${oRes.message}")
                                    emptyMap()
                                }
                                is com.signalscope.app.network.Trading212Client.T212Result.NotConfigured -> emptyMap()
                            }
                        val cashJson = when (cashRes) {
                            is com.signalscope.app.network.Trading212Client.T212Result.Success ->
                                gson.toJson(cashRes.data)
                            else -> "null"
                        }
                        val reconcileJson = if (reconcileSummary != null) gson.toJson(reconcileSummary) else "null"

                        // THE BROKER HAS ANSWERED — SHOW IT NOW. Each holding goes
                        // out with its scores from last time and today's quantity,
                        // price and P&L on top; one never scored before shows its
                        // numbers and waits for its scores. Only what follows is
                        // slow, and the list no longer waits for it.
                        val previous = t212PositionsByTicker(
                            com.signalscope.app.data.T212PortfolioStore.load(applicationContext))
                        val quick = com.google.gson.JsonArray()
                        for (pos in positions) {
                            val meta = metaMap[pos.ticker]
                            val live = t212LiveFields(pos, meta, openOrdersByTicker[pos.ticker].orEmpty())
                            val prev = previous[pos.ticker]
                            quick.add(
                                if (prev != null) t212Overlay(prev, live)
                                else gson.toJsonTree(live + mapOf(
                                    "yahooSymbol" to t212.t212ToYahooSymbol(pos.ticker),
                                    "symbol" to t212.t212ToYahooSymbol(pos.ticker),
                                    "name" to (meta?.shortName ?: pos.ticker),
                                    "hasAnalysis" to false,
                                    "analysisPending" to true
                                )).asJsonObject
                            )
                        }
                        t212LiveSymbols = quick
                            .mapNotNull { it.asJsonObject.get("yahooSymbol")?.takeIf { e -> !e.isJsonNull }?.asString }
                            .map { it.trim().uppercase() }
                            .filter { it.isNotBlank() }
                            .toSet()
                        runOnUiThread { updateLiveStreamSubscriptions() }
                        deliverAiCallback("window.onT212PortfolioResult",
                            "{\"ok\":true,\"partial\":true,\"positions\":$quick,\"summary\":$cashJson,\"reconcile\":$reconcileJson,\"capturedAt\":${System.currentTimeMillis()}}")

                        // 2) For each position, fetch Yahoo candles + run
                        //    StockAnalyzer so the user sees the same scores
                        //    as the Indian portfolio. Sequential to respect
                        //    Yahoo's rate limit (~2 req/s ceiling).
                        //    ONLY WHERE SOMETHING CHANGED: a holding scored in the
                        //    last quarter-hour keeps those scores, so a refresh
                        //    costs one round of prices per new or stale holding,
                        //    not one per holding.
                        val weights = config.scoringWeights
                        val analysedAt = System.currentTimeMillis()
                        val enrichedPositions = positions.map { pos ->
                            val prev = previous[pos.ticker]
                            val prevAt = prev?.get("analyzedAt")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                            if (prev != null && prev.get("hasAnalysis")?.asBoolean == true &&
                                analysedAt - prevAt in 0L until T212_ANALYSIS_TTL_MS) {
                                return@map t212Overlay(prev,
                                    t212LiveFields(pos, metaMap[pos.ticker], openOrdersByTicker[pos.ticker].orEmpty()))
                            }
                            var yahooSym = t212.t212ToYahooSymbol(pos.ticker)
                            val meta = metaMap[pos.ticker]
                            val name = meta?.shortName ?: pos.ticker
                            val currency = meta?.currencyCode ?: "USD"
                            var analysisError = ""
                            // THE COMPANY'S NAME, as the price feed spells it ("HelloFresh SE"),
                            // so a row can be matched to the broker's own list by eye. Taken
                            // from whichever feed symbol finally answered.
                            var company: String? = null
                            // Run analyzer on Yahoo candles (NASDAQ default exchange ID)
                            var analysis: com.signalscope.app.data.StockAnalysis? = try {
                                val candleResult = com.signalscope.app.network.YahooFinanceClient
                                    .fetchCandles(yahooSym, "NASDAQ")
                                if (candleResult is com.signalscope.app.network.YahooFinanceClient.CandleResult.Success) {
                                    company = candleResult.name?.trim()?.takeIf { it.isNotBlank() }
                                    val displayName = name.trim().takeIf { it.isNotBlank() && !it.equals(pos.ticker, ignoreCase = true) }
                                        ?: candleResult.name?.trim()?.takeIf { it.isNotBlank() }
                                        ?: pos.ticker
                                    val a = com.signalscope.app.util.StockAnalyzer.analyze(
                                        candles = candleResult.candles,
                                        symbol = yahooSym,
                                        name = displayName,
                                        token = yahooSym,
                                        minAvgVolume = 0,   // bypass for portfolio holdings
                                        w = weights
                                    )
                                    // Company figures from the shared helper, which keeps them
                                    // for hours — they move once a quarter.
                                    a?.let { enrichT212AnalysisWithFundamentals(yahooSym, it, weights) }
                                } else null
                            } catch (e: Exception) {
                                Log.w(TAG, "T212 enrichment failed for ${pos.ticker}: ${e.message}")
                                analysisError = e.message ?: "unknown"
                                null
                            }
                            if (analysis == null) {
                                for (candidate in t212.t212ToYahooCandidates(pos.ticker, currency)) {
                                    if (candidate.equals(yahooSym, ignoreCase = true)) continue
                                    val exchange = if (candidate.endsWith(".DE", ignoreCase = true)) "DE" else "NASDAQ"
                                    try {
                                        when (val candleResult = com.signalscope.app.network.YahooFinanceClient
                                            .fetchCandles(candidate, exchange)) {
                                            is com.signalscope.app.network.YahooFinanceClient.CandleResult.Success -> {
                                                val displayName = name.trim().takeIf { it.isNotBlank() && !it.equals(pos.ticker, ignoreCase = true) }
                                                    ?: candleResult.name?.trim()?.takeIf { it.isNotBlank() }
                                                    ?: pos.ticker
                                                val fallback = com.signalscope.app.util.StockAnalyzer.analyze(
                                                    candles = candleResult.candles,
                                                    symbol = candidate,
                                                    name = displayName,
                                                    token = candidate,
                                                    minAvgVolume = 0,
                                                    w = weights
                                                )
                                                if (fallback != null) {
                                                    company = candleResult.name?.trim()?.takeIf { it.isNotBlank() } ?: company
                                                    yahooSym = candidate
                                                    analysis = enrichT212AnalysisWithFundamentals(candidate, fallback, weights)
                                                    analysisError = ""
                                                    break
                                                } else {
                                                    analysisError = "insufficient candles for $candidate"
                                                }
                                            }
                                            is com.signalscope.app.network.YahooFinanceClient.CandleResult.RateLimited -> {
                                                analysisError = "Yahoo rate-limited while trying $candidate"
                                                break
                                            }
                                            is com.signalscope.app.network.YahooFinanceClient.CandleResult.Error ->
                                                analysisError = "Yahoo $candidate: ${candleResult.message}"
                                            is com.signalscope.app.network.YahooFinanceClient.CandleResult.NoData ->
                                                analysisError = "No Yahoo candles for $candidate"
                                        }
                                    } catch (e: Exception) {
                                        analysisError = e.message ?: "unknown"
                                    }
                                }
                            }
                            // EVERY SPELLING OF THE BROKER CODE FAILED. Ask the
                            // ISIN, which is not a spelling of anything: the
                            // broker publishes it with the instrument and the
                            // price feed's own search resolves it to a listing.
                            // Confirmed once, kept for ever — so this costs one
                            // request in the life of an awkward name, not one per
                            // portfolio refresh.
                            if (analysis == null) {
                                val byIsin = t212.resolveFeedSymbolViaIsin(pos.ticker, meta?.isin)
                                if (byIsin != null && !byIsin.equals(yahooSym, ignoreCase = true)) {
                                    try {
                                        val exchange = if (byIsin.endsWith(".DE", ignoreCase = true)) "DE" else "NASDAQ"
                                        val cr = com.signalscope.app.network.YahooFinanceClient
                                            .fetchCandles(byIsin, exchange)
                                        if (cr is com.signalscope.app.network.YahooFinanceClient.CandleResult.Success) {
                                            val displayName = name.trim().takeIf { it.isNotBlank() && !it.equals(pos.ticker, ignoreCase = true) }
                                                ?: cr.name?.trim()?.takeIf { it.isNotBlank() }
                                                ?: pos.ticker
                                            val byIsinAnalysis = com.signalscope.app.util.StockAnalyzer.analyze(
                                                candles = cr.candles,
                                                symbol = byIsin,
                                                name = displayName,
                                                token = byIsin,
                                                minAvgVolume = 0,
                                                w = weights
                                            )
                                            if (byIsinAnalysis != null) {
                                                company = cr.name?.trim()?.takeIf { it.isNotBlank() } ?: company
                                                yahooSym = byIsin
                                                analysis = enrichT212AnalysisWithFundamentals(byIsin, byIsinAnalysis, weights)
                                                analysisError = ""
                                                Log.i(TAG, "T212 ${pos.ticker}: matched by ISIN to $byIsin")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "T212 ISIN match failed for ${pos.ticker}: ${e.message}")
                                    }
                                }
                            }
                            if (analysis == null) {
                                val cached = findCachedT212DiscoveryAnalysis(t212, pos.ticker, currency)
                                if (cached != null) {
                                    yahooSym = cached.symbol
                                    company = null   // the cached reading carries no feed name of its own
                                    analysis = cached
                                    analysisError = ""
                                    Log.i(TAG, "T212 ${pos.ticker}: using cached discovery analysis ${cached.symbol}")
                                }
                            }
                            // Scoring failed this time (a rate limit, usually) but
                            // worked before: keep the older scores rather than blank
                            // the row. Their old time stamp means the next fetch
                            // tries again.
                            if (analysis == null && prev != null && prev.get("hasAnalysis")?.asBoolean == true) {
                                return@map t212Overlay(prev,
                                    t212LiveFields(pos, metaMap[pos.ticker], openOrdersByTicker[pos.ticker].orEmpty()))
                            }
                            // A name from a feed symbol whose chart was then thrown away
                            // (too short, or superseded by a cached reading) belongs to
                            // that symbol, not necessarily to this holding.
                            if (analysis?.symbol?.equals(yahooSym, ignoreCase = true) != true) company = null
                            // Merge T212 raw + name + analysis into one map.
                            // JS detail-modal reads stock objects directly so all
                            // analysis fields need to be top-level.
                            val merged = mutableMapOf<String, Any?>(
                                "ticker"        to pos.ticker,
                                "yahooSymbol"   to yahooSym,
                                "symbol"        to yahooSym,            // expected by detail modal
                                "name"          to (analysis?.name?.trim()?.takeIf { it.isNotBlank() } ?: name),
                                "currency"      to currency,
                                "quantity"      to pos.quantity,
                                "averagePrice"  to pos.averagePrice,
                                "currentPrice"  to pos.currentPrice,
                                "pplToday"      to pos.pplToday,
                                "pplTotal"      to pos.pplTotal,
                                "isin"          to meta?.isin,
                                "company"       to company,
                                // Trading 212's own name for it — the portfolio's label.
                                "instrumentName" to meta?.name
                            )
                            val pendingOrders = openOrdersByTicker[pos.ticker].orEmpty()
                            if (pendingOrders.isNotEmpty()) {
                                merged["pendingOrders"] = pendingOrders
                                merged["reservedSellQuantity"] = pendingOrders
                                    .filter { (it["side"] as? String).equals("SELL", ignoreCase = true) }
                                    .sumOf { (it["absQuantity"] as? Double) ?: 0.0 }
                            }
                            // Inline the StockAnalysis fields if we got one.
                            if (analysis != null) {
                                merged["price"]            = analysis.price
                                merged["buyScore"]         = analysis.cherryPoints
                                merged["buySignal"]        = analysis.cherrySignal
                                merged["pullbackScore"]    = analysis.pullbackScore
                                merged["pullbackSignal"]   = analysis.pullbackSignal
                                merged["momentumScore"]    = analysis.momentumScore
                                merged["momentumSignal"]   = analysis.momentumSignal
                                merged["entryMode"]        = analysis.entryMode
                                merged["profitScore"]      = analysis.profitScore
                                merged["protectScore"]     = analysis.protectScore
                                merged["sellScore"]        = analysis.sellScore
                                merged["sellSignal"]       = analysis.sellSignal
                                merged["sellIntent"]       = analysis.sellIntent
                                merged["macdPhase"]        = analysis.macdPhase
                                merged["macdSlope"]        = analysis.macdSlope
                                merged["macdSlopeAngle"]   = analysis.macdSlopeAngle
                                merged["macdAccel"]        = analysis.macdAccel
                                merged["macdPctl"]         = analysis.macdPctl
                                merged["macdLowPct"]       = analysis.macdLowPct
                                merged["macd"]             = analysis.macd
                                merged["macdSignal"]       = analysis.macdSignal
                                merged["macdHist"]         = analysis.macdHist
                                merged["macd1yLow"]        = analysis.macd1yLow
                                merged["macdCurve"]        = analysis.macdCurve
                                merged["smoothMacdCurve"]  = analysis.smoothMacdCurve
                                merged["smoothMacdSlope"]  = analysis.smoothMacdSlope
                                merged["smoothMacdPrevSlope"] = analysis.smoothMacdPrevSlope
                                merged["smoothMacdAccel"]  = analysis.smoothMacdAccel
                                merged["smoothMacdLowDistPct"] = analysis.smoothMacdLowDistPct
                                merged["smoothMacdHook"]   = analysis.smoothMacdHook
                                merged["smoothMacdPrevSavedSlope"] = analysis.smoothMacdPrevSavedSlope
                                merged["smoothMacdTurnedPositiveToday"] = analysis.smoothMacdTurnedPositiveToday
                                merged["smoothMacdTurnSourceTimestamp"] = analysis.smoothMacdTurnSourceTimestamp
                                merged["priceCurve"]       = analysis.priceCurve
                                merged["kalmanCurve"]      = analysis.kalmanCurve
                                merged["kalmanSlope"]      = analysis.kalmanSlope
                                merged["kalmanTrendPct"]   = analysis.kalmanTrendPct
                                merged["kalmanRegime"]     = analysis.kalmanRegime
                                merged["kalmanHorizonDays"]= analysis.kalmanHorizonDays
                                merged["rsi"]              = analysis.rsi
                                merged["adx"]              = analysis.adx
                                merged["plusDi"]           = analysis.plusDi
                                merged["minusDi"]          = analysis.minusDi
                                merged["obv"]              = analysis.obv
                                merged["sma200"]           = analysis.sma200
                                merged["sma200Slope"]      = analysis.sma200Slope
                                merged["ema21"]            = analysis.ema21
                                merged["ema21Slope"]       = analysis.ema21Slope
                                merged["ema21PctDiff"]     = analysis.ema21PctDiff
                                merged["ema50"]            = analysis.ema50
                                merged["ema50Slope"]       = analysis.ema50Slope
                                merged["ema200"]           = analysis.ema200
                                merged["ema200Slope"]      = analysis.ema200Slope
                                merged["bbUpper"]          = analysis.bbUpper
                                merged["bbMid"]            = analysis.bbMid
                                merged["bbLower"]          = analysis.bbLower
                                merged["avgVol20"]         = analysis.avgVol20
                                merged["volume"]           = analysis.volume
                                merged["priceVel"]         = analysis.priceVel
                                merged["priceAccel"]       = analysis.priceAccel
                                merged["priceRoc3"]        = analysis.priceRoc3
                                merged["upDays"]           = analysis.upDays
                                merged["support"]          = analysis.support
                                merged["resistance"]       = analysis.resistance
                                merged["risk"]             = analysis.risk
                                merged["reward"]           = analysis.reward
                                merged["rrRatio"]          = analysis.rrRatio
                                merged["atr"]              = analysis.atr
                                merged["riskInAtrs"]       = analysis.riskInAtrs
                                merged["positionSize"]     = analysis.positionSize
                                merged["capitalNeeded"]    = analysis.capitalNeeded
                                merged["potentialProfit"]  = analysis.potentialProfit
                                merged["rocPct"]           = analysis.rocPct
                                merged["projectedCeiling"] = analysis.projectedCeiling
                                merged["projectedFloor"]   = analysis.projectedFloor
                                merged["projectedMidpoint"]= analysis.projectedMidpoint
                                merged["minBarsFilter"]    = analysis.minBarsFilter
                                merged["swingScore"]       = analysis.swingScore
                                merged["longTermScore"]    = analysis.longTermScore
                                merged["styleLabel"]       = analysis.styleLabel
                                merged["styleReason"]      = analysis.styleReason
                                merged["macdCycleDays"]    = analysis.macdCycleDays
                                merged["returnAutocorr1"]  = analysis.returnAutocorr1
                                merged["styleScoreReport"] = analysis.styleScoreReport
                                merged["obvDivergence"]    = analysis.obvDivergence
                                merged["obvWeakness"]      = analysis.obvWeakness
                                merged["divHistBullish"]   = analysis.divHistBullish
                                merged["divHistBearish"]   = analysis.divHistBearish
                                merged["divVolBullish"]    = analysis.divVolBullish
                                merged["divVolBearish"]    = analysis.divVolBearish
                                merged["divDetail"]        = analysis.divDetail
                                merged["volBullDryUp"]        = analysis.volBullDryUp
                                merged["volBearDryUp"]        = analysis.volBearDryUp
                                merged["volBullCapitulation"] = analysis.volBullCapitulation
                                merged["volBearChurning"]     = analysis.volBearChurning
                                merged["volPatternDetail"]    = analysis.volPatternDetail
                                merged["goldenBuy"]        = analysis.goldenBuy
                                merged["goldenBuyReport"]  = analysis.goldenBuyReport
                                merged["buyScoreReport"]   = analysis.cherryPointsReport
                                merged["pullbackScoreReport"] = analysis.pullbackScoreReport
                                merged["momentumScoreReport"] = analysis.momentumScoreReport
                                merged["trailingPe"]       = analysis.trailingPe
                                merged["forwardPe"]        = analysis.forwardPe
                                merged["priceToBook"]      = analysis.priceToBook
                                merged["evToEbitda"]       = analysis.evToEbitda
                                merged["debtToEquity"]     = analysis.debtToEquity
                                merged["roce"]             = analysis.roce
                                merged["revenueGrowth"]    = analysis.revenueGrowth
                                merged["earningsGrowth"]   = analysis.earningsGrowth
                                merged["grossMargins"]     = analysis.grossMargins
                                merged["operatingMargins"] = analysis.operatingMargins
                                merged["profitMargins"]    = analysis.profitMargins
                                merged["dividendYield"]    = analysis.dividendYield
                                merged["operatingCashflow"]= analysis.operatingCashflow
                                merged["freeCashflow"]     = analysis.freeCashflow
                                merged["netIncome"]        = analysis.netIncome
                                merged["totalRevenue"]     = analysis.totalRevenue
                                merged["totalCash"]        = analysis.totalCash
                                merged["totalDebt"]        = analysis.totalDebt
                                merged["currentRatio"]     = analysis.currentRatio
                                merged["annualRevenueGrowth"] = analysis.annualRevenueGrowth
                                merged["annualNetIncomeGrowth"] = analysis.annualNetIncomeGrowth
                                merged["fiftyTwoWeekLow"]  = analysis.fiftyTwoWeekLow
                                merged["fiftyTwoWeekHigh"] = analysis.fiftyTwoWeekHigh
                                merged["sharesOutstanding"]= analysis.sharesOutstanding
                                merged["sector"]           = analysis.sector
                                merged["industry"]         = analysis.industry
                                merged["sectorMedianPe"]   = analysis.sectorMedianPe
                                merged["hasBuyback"]       = analysis.hasBuyback
                                merged["valueScore"]       = analysis.valueScore
                                merged["valueRating"]      = analysis.valueRating
                                merged["valueScoreReport"] = analysis.valueScoreReport
                                merged["hasAnalysis"]      = true
                            } else {
                                merged["hasAnalysis"]      = false
                                if (analysisError.isNotBlank()) {
                                    merged["analysisError"] = analysisError
                                }
                            }
                            merged["analyzedAt"] = analysedAt
                            gson.toJsonTree(merged).asJsonObject
                        }
                        t212LiveSymbols = enrichedPositions
                            .mapNotNull { it.get("yahooSymbol")?.takeIf { e -> !e.isJsonNull }?.asString }
                            .map { it.trim().uppercase() }
                            .filter { it.isNotBlank() }
                            .toSet()
                        runOnUiThread { updateLiveStreamSubscriptions() }

                        val positionsJson = gson.toJson(enrichedPositions)
                        val payload = "{\"ok\":true,\"positions\":$positionsJson,\"summary\":$cashJson,\"reconcile\":$reconcileJson,\"capturedAt\":${System.currentTimeMillis()}}"
                        com.signalscope.app.data.T212PortfolioStore.save(applicationContext, payload)
                        payload
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "fetchT212Portfolio failed", e)
                    "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
                }
                deliverAiCallback("window.onT212PortfolioResult", resultJson)
              } finally {
                t212PortfolioFetchRunning.set(false)
              }
            }
        }

        // ── Symbol autocomplete (Yahoo /v1/finance/search) ────────────────────
        // Synchronous bridge — Yahoo's search endpoint is fast (~150ms) and
        // the JS side debounces to 300ms+, so blocking the WebView thread for
        // a single round-trip is acceptable. Returns a JSON array of
        // {symbol, name, exchange, quoteType} or an empty array on error.
        private fun searchHitMatchesSpace(
            hit: com.signalscope.app.network.YahooFinanceClient.SearchHit,
            space: String
        ): Boolean {
            val sym = hit.symbol.trim().uppercase()
            val ex = hit.exchange.trim().uppercase()
            return when (space.uppercase()) {
                "DE" -> sym.endsWith(".DE") ||
                    ex.contains("XETRA") ||
                    ex.contains("FRANKFURT") ||
                    ex == "GER" ||
                    ex == "FRA"
                "US" -> !sym.contains(".") && (
                    ex.isBlank() ||
                    ex == "NMS" ||
                    ex == "NYQ" ||
                    ex == "ASE" ||
                    ex == "PCX" ||
                    ex == "BATS" ||
                    ex == "NGM" ||
                    ex == "NCM" ||
                    ex.contains("NASDAQ") ||
                    ex.contains("NYSE")
                )
                else -> sym.endsWith(".NS") ||
                    sym.endsWith(".BO") ||
                    ex == "NSI" ||
                    ex == "BSE" ||
                    ex.contains("NSE") ||
                    ex.contains("BSE")
            }
        }

        private fun normalizeManualSymbolForSpace(rawSymbol: String, space: String): String {
            val raw = rawSymbol.trim().uppercase()
            if (raw.isEmpty() || raw.startsWith("^")) return raw
            val base = raw.substringBefore(".")
            return when (space.uppercase()) {
                "US" -> base
                "DE" -> if (raw.endsWith(".DE")) raw else "$base.DE"
                else -> if (raw.endsWith(".NS") || raw.endsWith(".BO")) raw else "$base.NS"
            }
        }

        @JavascriptInterface
        fun searchSymbols(query: String, isUS: Boolean = false): String {
            return searchSymbolsForSpace(query, if (isUS) "US" else "IN")
        }

        @JavascriptInterface
        fun searchSymbolsForSpace(query: String, space: String = "IN"): String {
            return try {
                val region = when (space.uppercase()) {
                    "US" -> "US"
                    "DE" -> "DE"
                    else -> "IN"
                }
                when (val r = com.signalscope.app.network.YahooFinanceClient
                    .searchSymbols(query, region)) {
                    is com.signalscope.app.network.YahooFinanceClient.SearchResult.Success -> {
                        val filtered = r.hits.filter { hit -> searchHitMatchesSpace(hit, region) }
                        com.google.gson.Gson().toJson(filtered)
                    }
                    is com.signalscope.app.network.YahooFinanceClient.SearchResult.RateLimited ->
                        "{\"error\":\"rate-limited\"}"
                    is com.signalscope.app.network.YahooFinanceClient.SearchResult.Error ->
                        "{\"error\":\"${r.message.replace("\"","")}\"}"
                }
            } catch (e: Exception) {
                Log.e(TAG, "searchSymbols failed", e)
                "[]"
            }
        }

        // ── Feature 3: Manual stock analysis (off-list / micro/small-cap) ─────
        // Lets the user type any Yahoo-recognised symbol and get the same
        // StockAnalyzer scoring as a normal scan. Result is delivered to JS via
        // the `window.onManualAnalysisResult(json)` callback so the bridge
        // call returns immediately (no UI thread blocking on network I/O).
        @JavascriptInterface
        fun analyzeManualSymbol(symbol: String, isUS: Boolean = false) {
            analyzeManualSymbolForSpace(symbol, if (isUS) "US" else "IN")
        }

        @JavascriptInterface
        fun analyzeManualSymbolForSpace(symbol: String, space: String = "IN") {
            thread(isDaemon = true) {
                val resultJson: String = try {
                    val raw = symbol.trim().uppercase()
                    if (raw.isEmpty()) throw IllegalArgumentException("empty symbol")
                    val normalizedSpace = when (space.uppercase()) {
                        "US" -> "US"
                        "DE" -> "DE"
                        else -> "IN"
                    }
                    val resolved = normalizeManualSymbolForSpace(raw, normalizedSpace)
                    val exchange = when (normalizedSpace) {
                        "US" -> "NASDAQ"
                        "DE" -> "DE"
                        else -> "NSE"
                    }
                    val candleResult = com.signalscope.app.network.YahooFinanceClient
                        .fetchCandles(resolved, exchange)
                    when (candleResult) {
                        is com.signalscope.app.network.YahooFinanceClient.CandleResult.Success -> {
                            val weights = config.scoringWeights
                            val displayName = candleResult.name?.trim()?.takeIf { it.isNotBlank() } ?: resolved
                            var analysis = com.signalscope.app.util.StockAnalyzer.analyze(
                                candles = candleResult.candles,
                                symbol = resolved,
                                name = displayName,
                                token = resolved,
                                minAvgVolume = 0,   // off-list stocks bypass the volume filter
                                w = weights
                            )
                            if (analysis == null) {
                                "{\"ok\":false,\"error\":\"insufficient candle history (need 50+ daily bars)\",\"symbol\":\"$resolved\"}"
                            } else {
                                // Best-effort fundamentals — non-fatal if it fails
                                try {
                                    val fundResult = com.signalscope.app.network.YahooFinanceClient
                                        .fetchFundamentals(resolved, exchange)
                                    if (fundResult is com.signalscope.app.network.YahooFinanceClient.FundamentalResult.Success) {
                                        val vr = com.signalscope.app.util.ValueAnalyzer.analyze(
                                            fundamentals = fundResult.data,
                                            currentPrice = analysis!!.price,
                                            sectorMedianPe = null,
                                            w = weights
                                        )
                                        val refreshedName = fundResult.data.name?.trim()?.takeIf { it.isNotBlank() } ?: analysis!!.name
                                        analysis = analysis!!.copy(
                                            name = refreshedName,
                                            trailingPe = vr.trailingPe,
                                            forwardPe = vr.forwardPe,
                                            priceToBook = vr.priceToBook,
                                            evToEbitda = vr.evToEbitda,
                                            debtToEquity = vr.debtToEquity,
                                            roce = vr.roce,
                                            revenueGrowth = vr.revenueGrowth,
                                            earningsGrowth = vr.earningsGrowth,
                                            grossMargins = vr.grossMargins,
                                            operatingMargins = vr.operatingMargins,
                                            profitMargins = vr.profitMargins,
                                            dividendYield = vr.dividendYield,
                                            operatingCashflow = vr.operatingCashflow,
                                            freeCashflow = vr.freeCashflow,
                                            netIncome = vr.netIncome,
                                            totalRevenue = vr.totalRevenue,
                                            totalCash = vr.totalCash,
                                            totalDebt = vr.totalDebt,
                                            currentRatio = vr.currentRatio,
                                            annualRevenueGrowth = vr.annualRevenueGrowth,
                                            annualNetIncomeGrowth = vr.annualNetIncomeGrowth,
                                            fiftyTwoWeekLow = vr.fiftyTwoWeekLow,
                                            fiftyTwoWeekHigh = vr.fiftyTwoWeekHigh,
                                            sharesOutstanding = vr.sharesOutstanding,
                                            sector = vr.sector,
                                            industry = vr.industry,
                                            sectorMedianPe = vr.sectorMedianPe,
                                            hasBuyback = vr.hasBuyback,
                                            valueScore = vr.valueScore,
                                            valueRating = vr.valueRating,
                                            valueScoreReport = vr.valueScoreReport
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "manual fundamentals failed for $resolved: ${e.message}")
                                }
                                val gson = com.google.gson.Gson()
                                val payload = mapOf("ok" to true, "analysis" to analysis)
                                gson.toJson(payload)
                            }
                        }
                        is com.signalscope.app.network.YahooFinanceClient.CandleResult.Error ->
                            "{\"ok\":false,\"error\":\"${candleResult.message.replace("\"","")}\",\"symbol\":\"$resolved\"}"
                        is com.signalscope.app.network.YahooFinanceClient.CandleResult.RateLimited ->
                            "{\"ok\":false,\"error\":\"Yahoo rate-limited — try again in a few seconds\",\"symbol\":\"$resolved\"}"
                        is com.signalscope.app.network.YahooFinanceClient.CandleResult.NoData ->
                            "{\"ok\":false,\"error\":\"No data found — symbol invalid or delisted\",\"symbol\":\"$resolved\"}"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "analyzeManualSymbol failed", e)
                    "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
                }
                deliverAiCallback("window.onManualAnalysisResult", resultJson)
            }
        }

        /** Analyze a Binance crypto pair with DAILY candles (≈1y) through the same
         *  StockAnalyzer the stock spaces use, then hand the result to the shared
         *  detail view (window.onManualAnalysisResult → showDetail) so the crypto
         *  space gets the 252-day price SVG, MACD, momentum, pullback, RSI, etc.
         *  Fundamentals (P/E, dividends…) are skipped — they don't apply to crypto. */
        @JavascriptInterface
        fun analyzeCryptoSymbol(symbol: String) {
            thread(isDaemon = true) {
                val resultJson: String = try {
                    val sym = symbol.trim().uppercase()
                    if (sym.isEmpty()) throw IllegalArgumentException("empty symbol")
                    when (val cr = com.signalscope.app.network.BinanceClient.fetchDailyCandles(sym)) {
                        is com.signalscope.app.network.YahooFinanceClient.CandleResult.Success -> {
                            val analysis = com.signalscope.app.util.StockAnalyzer.analyze(
                                candles = cr.candles, symbol = sym, name = sym, token = sym,
                                minAvgVolume = 0, w = config.scoringWeights
                            )
                            if (analysis == null)
                                "{\"ok\":false,\"error\":\"insufficient candle history (need 50+ daily bars)\",\"symbol\":\"$sym\"}"
                            else com.google.gson.Gson().toJson(mapOf("ok" to true, "analysis" to analysis))
                        }
                        is com.signalscope.app.network.YahooFinanceClient.CandleResult.RateLimited ->
                            "{\"ok\":false,\"error\":\"Binance rate-limited — try again shortly\",\"symbol\":\"$symbol\"}"
                        is com.signalscope.app.network.YahooFinanceClient.CandleResult.Error ->
                            "{\"ok\":false,\"error\":\"${cr.message.replace("\"","")}\",\"symbol\":\"$symbol\"}"
                        is com.signalscope.app.network.YahooFinanceClient.CandleResult.NoData ->
                            "{\"ok\":false,\"error\":\"No data — check the pair (e.g. BTCUSDT)\",\"symbol\":\"$symbol\"}"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "analyzeCryptoSymbol failed", e)
                    "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
                }
                deliverAiCallback("window.onManualAnalysisResult", resultJson)
            }
        }

        /** Per-bar "why did/didn't it trade" review for one enrolled lab symbol.
         *  Delivers the chart series + trades + per-bar entry/exit checklists to
         *  window.onLabReview. space → lab namespace (US/DE/BN). */
        @JavascriptInterface
        fun labReview(space: String, symbol: String) {
            thread(isDaemon = true) {
                // INDIA WAS FALLING THROUGH TO THE US.
                //
                // This read "BN -> binance, DE -> de, else -> us", so every Indian
                // name was replayed as an American one — wrong exchange, wrong
                // feed, wrong trading calendar. The chart still drew, which is why
                // it went unnoticed: it looked like an answer.
                //
                // AND IT REPLAYED WITH THE DEFAULT STRATEGY. The config comes from
                // the BARE space, and no bare space has one — every real config
                // lives on a watcher or algo namespace — so it silently fell back
                // to the built-in preset. Reading "why did my exit fire" off a
                // chart drawn by a different strategy cannot give a true answer,
                // so it now prefers the watcher's namespace, which is the one
                // actually managing anything you bought by hand.
                val base = when (space.uppercase()) {
                    "BN", "BINANCE" -> "binance"
                    "DE" -> "de"
                    "IN", "INDIA" -> "in"
                    else -> "us"
                }
                val watcherNs = com.signalscope.app.data.ManualWatchStore.nsFor(base)
                val ns = if (com.signalscope.app.data.StrategyLabStore
                        .load(applicationContext, watcherNs).configJson != null) watcherNs else base
                val out = try {
                    gson.toJson(com.signalscope.app.trading.StrategyLabRunner.reviewPayload(applicationContext, ns, symbol))
                } catch (e: Exception) {
                    Log.e(TAG, "labReview failed", e)
                    gson.toJson(mapOf("symbol" to symbol, "error" to (e.message ?: "review failed")))
                }
                deliverAiCallback("window.onLabReview", out)
            }
        }

        /** Momentum screener (SCREENER_REINVEST_KOTLIN_PORT.md Part A): fetch daily
         *  bars for the given universe in the given space, rank by the momentum
         *  quality score, and deliver the ordered result to window.onScreenerResult.
         *  Per-symbol progress → window.onScreenerProgress. */
        /** The exact universe Discovery scans for a space, as a space-joined
         *  string for the screener's default box — so the two never drift.
         *  DE symbols are stripped of ".DE" to match the box's bare convention
         *  (the fetcher re-adds the suffix from the exchange). BN/IN have no
         *  Discovery constant, so the JS keeps its own fallback for those. */
        @JavascriptInterface
        fun discoveryUniverse(space: String): String {
            return try {
                when (space.uppercase()) {
                    "US" -> com.signalscope.app.service.NASDAQ_100_SYMBOLS.joinToString(" ")
                    "DE" -> com.signalscope.app.service.deScreenedUniverse(applicationContext)
                        .joinToString(" ") { it.removeSuffix(".DE") }
                    // India is the one space whose universe is DOWNLOADED rather
                    // than hardcoded, and what gets screened is the live index
                    // UNION the app's own list — the same answer discovery and the
                    // watcher use, from the same place, so no two screens can
                    // disagree about which market they are looking at. Never
                    // fetches: this runs on the main thread from a rendering card.
                    "IN", "INDIA" ->
                        com.signalscope.app.service.nseScreenedUniverse(applicationContext)
                            .joinToString(" ")
                    else -> ""
                }
            } catch (e: Exception) { "" }
        }

        @JavascriptInterface
        fun runScreener(symbolsJson: String, space: String) {
            thread(isDaemon = true) {
                val out: String = try {
                    val arr = com.google.gson.JsonParser.parseString(symbolsJson).asJsonArray
                    val syms = arr.mapNotNull { runCatching { it.asString }.getOrNull() }
                    if (syms.size < 2) throw IllegalArgumentException("Need at least 2 symbols to rank a universe.")
                    val cfg = com.signalscope.app.screener.ScreenerConfig()
                    val res = com.signalscope.app.screener.ScreenerRunner.run(space, syms, cfg) { done, total ->
                        deliverAiCallback("window.onScreenerProgress", "{\"done\":$done,\"total\":$total}")
                    }
                    gson.toJson(mapOf(
                        "ok" to true, "asOf" to res.asOf, "universeSize" to res.universeSize,
                        "skipped" to res.skipped, "ranked" to res.ranked, "space" to space.uppercase(),
                        // Expose the scoring parameters so the UI breakdown reads them
                        // from source (weights are per-config, not hardcoded in JS).
                        "weightReturn" to cfg.weightReturn, "weightVol" to cfg.weightVol,
                        "weightAboveSma" to cfg.weightAboveSma, "lookbackDays" to cfg.lookbackDays,
                        "smaPeriod" to cfg.smaPeriod))
                } catch (e: Exception) {
                    Log.e(TAG, "runScreener failed", e)
                    gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "screen failed")))
                }
                deliverAiCallback("window.onScreenerResult", out)
            }
        }

        @JavascriptInterface
        fun analyzeDeepStyle(symbol: String, space: String = "IN") {
            thread(isDaemon = true) {
                val json = com.google.gson.Gson()
                val resultJson: String = try {
                    val raw = symbol.trim().uppercase()
                    if (raw.isEmpty()) throw IllegalArgumentException("empty symbol")
                    val normalizedSpace = when (space.uppercase()) {
                        "US" -> "US"
                        "DE" -> "DE"
                        else -> "IN"
                    }
                    val resolved = when {
                        raw.contains(".") || raw.startsWith("^") -> raw
                        normalizedSpace == "US" -> raw
                        normalizedSpace == "DE" -> "$raw.DE"
                        else -> "$raw.NS"
                    }
                    val exchange = when (normalizedSpace) {
                        "US" -> "NASDAQ"
                        "DE" -> "DE"
                        else -> "NSE"
                    }

                    when (val candleResult = com.signalscope.app.network.YahooFinanceClient
                        .fetchCandlesCached(resolved, exchange)) {
                        is com.signalscope.app.network.YahooFinanceClient.CandleResult.Success -> {
                            val deep = com.signalscope.app.util.DeepStyleAnalyzer
                                .analyze(candleResult.candles)
                                ?: throw IllegalStateException("not enough candle history for phase-2 style analysis")
                            json.toJson(
                                mapOf(
                                    "ok" to true,
                                    "requestSymbol" to raw,
                                    "symbol" to resolved,
                                    "space" to normalizedSpace,
                                    "deep" to deep
                                )
                            )
                        }
                        is com.signalscope.app.network.YahooFinanceClient.CandleResult.Error -> json.toJson(
                            mapOf("ok" to false, "requestSymbol" to raw, "symbol" to resolved, "error" to candleResult.message)
                        )
                        is com.signalscope.app.network.YahooFinanceClient.CandleResult.RateLimited -> json.toJson(
                            mapOf("ok" to false, "requestSymbol" to raw, "symbol" to resolved, "error" to "Yahoo rate-limited - try again in a few seconds")
                        )
                        is com.signalscope.app.network.YahooFinanceClient.CandleResult.NoData -> json.toJson(
                            mapOf("ok" to false, "requestSymbol" to raw, "symbol" to resolved, "error" to "No candle data found")
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "analyzeDeepStyle failed", e)
                    json.toJson(mapOf("ok" to false, "symbol" to symbol, "error" to (e.message ?: "unknown error")))
                }
                deliverAiCallback("window.onDeepStyleResult", resultJson)
            }
        }

        // ── Feature 1: Watchlist bridges ─────────────────────────────────────
        // CRUD over WatchlistStore + a rescore helper + a buy-GTT placer.
        // All sync read paths return JSON strings (parse on JS side); the
        // place-buy-GTT runs on a background thread and delivers via the
        // existing onOcoGttResult callback for consistency with other GTT flows.

        /** Returns the watchlist as a JSON array (oldest-first by addedAt). */
        @JavascriptInterface
        fun getWatchlist(): String {
            return try {
                val list = com.signalscope.app.data.WatchlistStore.load(applicationContext)
                com.google.gson.Gson().toJson(list)
            } catch (e: Exception) {
                Log.e(TAG, "getWatchlist failed", e)
                "[]"
            }
        }

        private fun watchlistEntryMatchesSpace(entry: com.signalscope.app.data.WatchlistStore.Entry, space: String): Boolean {
            val sym = entry.symbol.trim().uppercase()
            val ex = entry.exchange.trim().uppercase()
            return when (space.uppercase()) {
                "DE" -> ex == "DE" || ex == "XETRA" || sym.endsWith(".DE")
                "US" -> ex == "US" || ex == "NASDAQ" || ex == "NYSE"
                else -> ex == "NSE" || ex == "BSE" || sym.endsWith(".NS") || sym.endsWith(".BO")
            }
        }

        /** Add a symbol. No-ops if already present. Returns updated count. */
        @JavascriptInterface
        fun addToWatchlist(symbol: String, exchange: String = "NSE", notes: String = ""): Int {
            return try {
                val entry = normalizeWatchlistEntry(symbol, exchange, notes)
                val list = com.signalscope.app.data.WatchlistStore.add(
                    applicationContext,
                    entry
                )
                runOnUiThread { updateLiveStreamSubscriptions() }
                list.size
            } catch (e: Exception) {
                Log.e(TAG, "addToWatchlist failed", e)
                -1
            }
        }

        @JavascriptInterface
        fun removeFromWatchlist(symbol: String): Int {
            return try {
                val list = com.signalscope.app.data.WatchlistStore.remove(applicationContext, symbol)
                runOnUiThread { updateLiveStreamSubscriptions() }
                list.size
            } catch (e: Exception) {
                Log.e(TAG, "removeFromWatchlist failed", e)
                -1
            }
        }

        @JavascriptInterface
        fun removeFromWatchlistForSpace(symbol: String, exchange: String): Int {
            return try {
                val entry = normalizeWatchlistEntry(symbol, exchange, "")
                val list = com.signalscope.app.data.WatchlistStore.remove(
                    applicationContext,
                    entry.symbol,
                    entry.exchange
                )
                runOnUiThread { updateLiveStreamSubscriptions() }
                list.size
            } catch (e: Exception) {
                Log.e(TAG, "removeFromWatchlistForSpace failed", e)
                -1
            }
        }

        @JavascriptInterface
        fun updateWatchlistNotes(symbol: String, notes: String): Boolean {
            return try {
                com.signalscope.app.data.WatchlistStore.update(applicationContext, symbol, notes = notes)
                true
            } catch (e: Exception) {
                Log.e(TAG, "updateWatchlistNotes failed", e)
                false
            }
        }

        @JavascriptInterface
        fun updateWatchlistNotesForSpace(symbol: String, exchange: String, notes: String): Boolean {
            return try {
                val entry = normalizeWatchlistEntry(symbol, exchange, "")
                com.signalscope.app.data.WatchlistStore.update(
                    applicationContext,
                    entry.symbol,
                    exchange = entry.exchange,
                    notes = notes
                )
                true
            } catch (e: Exception) {
                Log.e(TAG, "updateWatchlistNotesForSpace failed", e)
                false
            }
        }

        @JavascriptInterface
        fun isInWatchlist(symbol: String): Boolean = try {
            com.signalscope.app.data.WatchlistStore.contains(applicationContext, symbol)
        } catch (_: Exception) { false }

        @JavascriptInterface
        fun isInWatchlistForSpace(symbol: String, exchange: String): Boolean = try {
            val entry = normalizeWatchlistEntry(symbol, exchange, "")
            com.signalscope.app.data.WatchlistStore.contains(applicationContext, entry.symbol, entry.exchange)
        } catch (_: Exception) { false }

        @JavascriptInterface
        fun getLiveBuyTrackersJson(): String {
            return try {
                gson.toJson(com.signalscope.app.data.LiveBuyTrackerStore.load(applicationContext))
            } catch (e: Exception) {
                Log.e(TAG, "getLiveBuyTrackersJson failed", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun armLiveBuyTracker(symbol: String, exchange: String, quantity: Int): String {
            return try {
                if (quantity <= 0) throw IllegalArgumentException("quantity must be > 0")
                val entry = normalizeWatchlistEntry(symbol, exchange, "live buy tracker")
                val watchlist = com.signalscope.app.data.WatchlistStore.add(
                    applicationContext,
                    entry.copy(notes = "live buy tracker - armed quantity $quantity")
                )
                val list = com.signalscope.app.data.LiveBuyTrackerStore.arm(
                    applicationContext,
                    entry.symbol,
                    entry.exchange,
                    quantity
                )
                runOnUiThread { updateLiveStreamSubscriptions() }
                gson.toJson(mapOf("ok" to true, "count" to list.size, "watchlistCount" to watchlist.size))
            } catch (e: Exception) {
                Log.e(TAG, "armLiveBuyTracker failed", e)
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        @JavascriptInterface
        fun cancelLiveBuyTracker(symbol: String): String {
            return try {
                val list = com.signalscope.app.data.LiveBuyTrackerStore.cancel(applicationContext, symbol)
                runOnUiThread { updateLiveStreamSubscriptions() }
                gson.toJson(mapOf("ok" to true, "count" to list.size))
            } catch (e: Exception) {
                Log.e(TAG, "cancelLiveBuyTracker failed", e)
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        // ── DUMMY GTT ALERTS ──────────────────────────────────────────────
        // App-only SL/target levels that vibrate + notify when reached (no
        // broker order placed). Foundation for T212 manual execution; works in
        // all spaces. See DummyGttStore / DummyGttMonitor.
        @JavascriptInterface
        fun getDummyGtts(): String =
            try { gson.toJson(com.signalscope.app.data.DummyGttStore.load(applicationContext)) }
            catch (e: Exception) { Log.e(TAG, "getDummyGtts failed", e); "[]" }

        /** Create a dummy GTT alert. exchange feeds the Yahoo symbol resolver so
         *  the live stream subscribes to the right ticker. */
        @JavascriptInterface
        fun addDummyGtt(
            symbol: String, exchange: String, slPrice: Double, targetPrice: Double,
            qty: Double, refPrice: Double, ticker: String = "", note: String = ""
        ): String {
            return try {
                val space = config.currentSpace
                val yahoo = YahooFinanceClient.toYahooSymbol(symbol.trim(), exchange).uppercase()
                val gtt = com.signalscope.app.data.DummyGttStore.DummyGtt(
                    space = space,
                    symbol = symbol.trim().uppercase(),
                    yahooSymbol = yahoo,
                    ticker = ticker,
                    qty = qty,
                    slPrice = if (slPrice > 0.0) slPrice else null,
                    targetPrice = if (targetPrice > 0.0) targetPrice else null,
                    refPrice = refPrice,
                    note = note
                )
                val list = com.signalscope.app.data.DummyGttStore.add(applicationContext, gtt)
                runOnUiThread { updateLiveStreamSubscriptions() }
                gson.toJson(mapOf("ok" to true, "count" to list.size, "id" to gtt.id))
            } catch (e: Exception) {
                Log.e(TAG, "addDummyGtt failed", e)
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        @JavascriptInterface
        fun removeDummyGtt(id: String): String {
            return try {
                val list = com.signalscope.app.data.DummyGttStore.remove(applicationContext, id)
                runOnUiThread { updateLiveStreamSubscriptions() }
                gson.toJson(mapOf("ok" to true, "count" to list.size))
            } catch (e: Exception) {
                Log.e(TAG, "removeDummyGtt failed", e)
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        /** Clears notified stamps so the level alerts again on the next cross. */
        @JavascriptInterface
        fun reArmDummyGtt(id: String): String {
            return try {
                com.signalscope.app.data.DummyGttStore.reArm(applicationContext, id)
                gson.toJson(mapOf("ok" to true))
            } catch (e: Exception) {
                Log.e(TAG, "reArmDummyGtt failed", e)
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        // ── STRATEGY LAB — LIVE AUTOMATED TRADING MODE (US space, PAPER ONLY) ──
        // Config-driven MACD engine running on live US bars; emits paper trades +
        // audit, never places real orders. See StrategyLab* classes + the spec.
        @JavascriptInterface
        fun strategyLabGetState(): String {
            return try {
                val s = com.signalscope.app.data.StrategyLabStore.load(applicationContext)
                val cfg = com.signalscope.app.data.StrategyLabStore.activeConfig(applicationContext)
                val closed = s.trades.filter { it.status == "CLOSED" }
                val wins = closed.count { it.pctReturn > 0 }
                val board = mapOf(
                    "open" to s.trades.count { it.status == "OPEN" },
                    "closed" to closed.size,
                    "wins" to wins,
                    "losses" to closed.count { it.pctReturn < 0 },
                    "totalPnl" to closed.sumOf { it.pnlNotional },
                    "totalPct" to closed.sumOf { it.pctReturn }
                )
                val t212Live = com.signalscope.app.data.T212LiveStore.load(applicationContext)
                gson.toJson(mapOf(
                    "enabled" to s.enabled,
                    "source" to s.configSource,
                    "summary" to cfg.summary(),
                    "lastRunAt" to s.lastRunAt,
                    "enrolled" to s.enrolled,
                    "trades" to s.trades,
                    "audit" to s.audit,
                    "statuses" to s.statuses,
                    "scoreboard" to board,
                    "liveMirror" to mapOf(
                        "broker" to "t212", "unit" to "",
                        // No global switches any more: whether real orders can
                        // happen is decided per algo. These two stay in the payload
                        // only so the panel keeps rendering, and report the truth.
                        "enabled" to config.hasT212Credentials,
                        "frozen" to false,
                        "configured" to config.hasT212Credentials,
                        "quoteOrderSize" to config.t212LivePerTradeAmount,
                        "open" to t212Live.trades.count { it.status == "OPEN" },
                        "closed" to t212Live.trades.count { it.status == "CLOSED" },
                        "errors" to t212Live.trades.count { it.status == "ERROR" || it.error.isNotBlank() },
                        "pnlQuote" to 0.0,
                        // Real fills, backfilled from T212's executed-order history
                        // (T212LiveMirror.reconcileFills). Reports 0 samples until
                        // some round trip has BOTH fill prices known — including
                        // orders placed by hand in the T212 app, which is how this
                        // can be calibrated before live entries are unfrozen.
                        "slippage" to com.signalscope.app.trading.SlippageStats.toMap(
                            com.signalscope.app.trading.SlippageStats.compute(
                                com.signalscope.app.trading.T212LiveMirror.slippageSamples(applicationContext))),
                        "trades" to t212Live.trades
                    )
                ))
            } catch (e: Exception) {
                Log.e(TAG, "strategyLabGetState failed", e)
                "{\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        @JavascriptInterface
        fun strategyLabSetEnabled(on: Boolean): Boolean {
            return try {
                com.signalscope.app.data.StrategyLabStore.setEnabled(applicationContext, on)
                com.signalscope.app.service.StrategyLabScheduler.ensureScheduled(applicationContext)
                if (on) com.signalscope.app.trading.StrategyLabRunner.runAll(applicationContext, 0L) {
                    deliverAiCallback("window.onStrategyLabUpdate", "{}")
                }
                on
            } catch (e: Exception) { Log.e(TAG, "strategyLabSetEnabled failed", e); false }
        }

        private fun normalizeUsStrategyLabExchange(raw: String): String? {
            return when (raw.trim().uppercase()) {
                "", "US", "NASDAQ", "NMS", "NGM", "NCM", "NYQ", "NYSE", "ASE", "AMEX", "PCX", "BATS" -> "US"
                else -> null
            }
        }

        @JavascriptInterface
        fun strategyLabEnroll(symbol: String, exchange: String): String {
            return try {
                val norm = symbol.trim().uppercase()
                // Reject obvious non-tickers (spaces, punctuation, empty) up front.
                // A format-valid but non-existent ticker still enrolls, but its
                // per-symbol status will report "no data — check the ticker".
                if (!Regex("^[A-Z0-9.\\-]{1,12}$").matches(norm)) {
                    return gson.toJson(mapOf("ok" to false,
                        "error" to "\"$symbol\" isn't a valid ticker — letters/digits only (e.g. AAPL, AVGO, BRK-B)."))
                }
                val normalizedExchange = normalizeUsStrategyLabExchange(exchange)
                    ?: return gson.toJson(mapOf("ok" to false,
                        "error" to "Strategy Lab US space only accepts US Yahoo/Nasdaq/NYSE listings."))
                val s = com.signalscope.app.data.StrategyLabStore.enroll(applicationContext, norm, normalizedExchange)
                com.signalscope.app.service.StrategyLabScheduler.ensureScheduled(applicationContext)
                gson.toJson(mapOf("ok" to true, "count" to s.enrolled.size))
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        @JavascriptInterface
        fun strategyLabUnenroll(symbol: String): String {
            return try {
                val s = com.signalscope.app.data.StrategyLabStore.unenroll(applicationContext, symbol)
                com.signalscope.app.service.StrategyLabScheduler.ensureScheduled(applicationContext)
                gson.toJson(mapOf("ok" to true, "count" to s.enrolled.size))
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        // ── Daily-MACD gate (Mode 3b) bridges ─────────────────────────────
        private fun gateNs(ns: String): String {
            // A bucket namespace ("<space>__<id>") is ALREADY a valid data ns — pass
            // it through untouched so a per-algo backtest reads THAT bucket's config
            // (config/enrolled/gate are all keyed on the bucket ns). Without this the
            // else-branch would collapse it to bare "us" and backtest the wrong book.
            if (ns.contains("__")) return ns
            return when (ns.lowercase()) {
                "bn", "binance" -> "binance"; "de" -> "de"; else -> "us"
            }
        }

        @JavascriptInterface
        fun macdGateState(ns: String): String {
            return try {
                val gns = gateNs(ns)
                val s = com.signalscope.app.data.MacdGateStore.load(applicationContext, gns)
                // ONE ledger read for the whole panel — the per-slot lookup below
                // would otherwise re-read and re-value it once per seat.
                val mtmAll = try {
                    com.signalscope.app.trading.MarkToMarket.snapshot(applicationContext, gns).values
                } catch (e: Exception) { emptyList() }
                gson.toJson(mapOf(
                    "ok" to true, "enabled" to s.enabled, "holdN" to s.holdN,
                    "notional" to s.notionalPerSlot, "smoothing" to s.smoothing,
                    "confirmDays" to s.confirmDays, "injected" to s.injected,
                    "useScreenerSelection" to s.useScreenerSelection,
                    "totalValue" to com.signalscope.app.data.MacdGateStore.totalValue(s),
                    "lastEvalDay" to s.lastEvalDay,
                    // `capital` is the REALISED seat balance — the number the gate
                    // compounds and sizes the next position from, so it must not move
                    // while a position is merely up on paper. `mtmValue` is the same
                    // seat marked at the live price, for DISPLAY only.
                    "slots" to s.slots.map { sl ->
                        val mk = sl.symbol?.let { sy -> mtmAll.firstOrNull { it.symbol.equals(sy, true) } }
                        mapOf(
                            "symbol" to sl.symbol, "capital" to sl.capital,
                            "since" to sl.since, "negDays" to sl.negDays, "flagged" to sl.flagged,
                            "mtmPct" to (mk?.pctReturn ?: 0.0),
                            "mtmValue" to (mk?.let { sl.capital * (1.0 + it.pctReturn / 100.0) } ?: sl.capital),
                            "mtmStale" to (mk?.stale ?: true),
                            "hasMtm" to (mk != null))
                    },
                    "log" to s.log.takeLast(30)))
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"", "")}\"}"
            }
        }

        /** Enable/disable the gate. First enable initializes N cash slots at
         *  notional each (injected = N × notional — cash slots count, §10.5).
         *  Disabling stops evaluation but leaves enrollment as-is: the user
         *  takes back manual control of the current basket. */
        @JavascriptInterface
        fun macdGateSetEnabled(ns: String, on: Boolean): String {
            return try {
                val n = gateNs(ns)
                val s = com.signalscope.app.data.MacdGateStore.mutate(applicationContext, n) { st ->
                    if (on && st.slots.isEmpty()) {
                        val slots = (1..st.holdN).map {
                            com.signalscope.app.data.MacdGateStore.Slot(null, st.notionalPerSlot, System.currentTimeMillis(), 0, false)
                        }
                        com.signalscope.app.data.MacdGateStore.appendLog(
                            st.copy(enabled = true, slots = slots,
                                injected = st.notionalPerSlot * st.holdN, lastEvalDay = ""),
                            "initialized ${st.holdN} slots × ${st.notionalPerSlot}")
                    } else st.copy(enabled = on, lastEvalDay = if (on) "" else st.lastEvalDay)
                }
                // Kick a pass so an enable acts within seconds, not at the next tick.
                if (on) com.signalscope.app.trading.StrategyLabRunner.runAll(applicationContext, 0L, n)
                gson.toJson(mapOf("ok" to true, "enabled" to s.enabled))
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"", "")}\"}"
            }
        }

        /**
         * Toggle the screener's "pick the best N" step. Off ⇒ every symbol gets its
         * own seat and only the daily-MACD gate decides — the honest configuration
         * on a 2-coin universe, where "top 1 of 2 by a three-factor percentile
         * screen" overstates what the strategy is doing.
         *
         * Reshaping seats is the same hazard as macdGateConfigure, so it is refused
         * mid-position rather than silently orphaning or duplicating live capital.
         * Takes effect on the next Save & run, which re-derives the slot array.
         */
        @JavascriptInterface
        fun macdGateSetSelection(ns: String, on: Boolean): String {
            return try {
                val n = gateNs(ns)
                var err: String? = null
                com.signalscope.app.data.MacdGateStore.mutate(applicationContext, n) { st ->
                    if (st.useScreenerSelection == on) st
                    else if (st.slots.any { it.symbol != null }) {
                        err = "Seats hold live symbols — changing this reshapes the seat count. " +
                              "Disable the gate and let positions close first."
                        st
                    } else st.copy(useScreenerSelection = on, slots = emptyList(),
                                   injected = 0.0, lastEvalDay = "")
                }
                if (err != null) gson.toJson(mapOf("ok" to false, "error" to err))
                else gson.toJson(mapOf("ok" to true, "on" to on))
            } catch (e: Exception) {
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "unknown")))
            }
        }

        /** Reconfigure slots/sizing — only while every slot is cash (changing N
         *  mid-flight would orphan or duplicate live capital). */
        @JavascriptInterface
        fun macdGateConfigure(ns: String, holdN: Int, notional: Double, smoothing: Int): String {
            return try {
                val n = gateNs(ns)
                var err: String? = null
                com.signalscope.app.data.MacdGateStore.mutate(applicationContext, n) { st ->
                    if (st.slots.any { it.symbol != null }) { err = "Slots hold live symbols — disable the gate and let positions close first."; st }
                    else st.copy(
                        holdN = holdN.coerceIn(1, 20),
                        notionalPerSlot = notional.coerceIn(10.0, 1_000_000.0),
                        smoothing = smoothing.coerceIn(1, 30),
                        slots = emptyList(), injected = 0.0)
                }
                if (err != null) "{\"ok\":false,\"error\":\"$err\"}"
                else gson.toJson(mapOf("ok" to true))
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"", "")}\"}"
            }
        }

        /** Open a multi-file picker for symbol CSVs (desktop format) and replay
         *  the gate/reinvest walk-forward over them — parity vs the Python lab.
         *  paramsJson: {mode:"3B"|"MONTHLY", holdN, smoothing, notional, topN}.
         *  Progress + result → window.onGateBacktestResult. */
        @JavascriptInterface
        fun gateBacktestPick(ns: String, paramsJson: String) {
            gateBtNs = gateNs(ns)
            gateBtParamsJson = paramsJson
            runOnUiThread {
                try { gateBtPicker.launch(arrayOf("*/*")) }
                catch (e: Exception) { Log.e(TAG, "gate bt picker launch failed", e) }
            }
        }

        /** Pick a whole FOLDER of symbol CSVs — every top-level *.csv is used.
         *  The right path for 100-symbol universes (no select-all in Android's
         *  file picker). Same params/result contract as gateBacktestPick. */
        @JavascriptInterface
        fun gateBacktestPickFolder(ns: String, paramsJson: String) {
            gateBtNs = gateNs(ns)
            gateBtParamsJson = paramsJson
            runOnUiThread {
                try { gateBtFolderPicker.launch(null) }
                catch (e: Exception) { Log.e(TAG, "gate bt folder picker launch failed", e) }
            }
        }

        /** Force a fresh evaluation now (clears the once-per-day guard). */
        @JavascriptInterface
        fun macdGateEvalNow(ns: String): String {
            return try {
                val n = gateNs(ns)
                com.signalscope.app.data.MacdGateStore.mutate(applicationContext, n) { it.copy(lastEvalDay = "") }
                com.signalscope.app.trading.StrategyLabRunner.runAll(applicationContext, 0L, n)
                "{\"ok\":true}"
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"", "")}\"}"
            }
        }

        /** Open a system file picker for a .json config and apply it to [ns]'s
         *  Strategy Lab. The picked file's NAME becomes the config source, so the
         *  panel shows which file each space runs. Result → onStrategyLabConfigFile. */
        @JavascriptInterface
        fun strategyLabPickConfigFile(ns: String) {
            configPickNs = when (ns.lowercase()) { "bn", "binance" -> "binance"; "de" -> "de"; else -> "us" }
            runOnUiThread {
                try { configJsonPicker.launch(arrayOf("application/json", "text/plain", "*/*")) }
                catch (e: Exception) { Log.e(TAG, "config picker launch failed", e) }
            }
        }

        /** Validate + activate a pasted config JSON. Engine params only — any
         *  symbols/dataset/etc. in the file are ignored by the parser. */
        @JavascriptInterface
        fun strategyLabUploadConfig(jsonText: String, filename: String = "uploaded"): String {
            return try {
                when (val r = com.signalscope.app.trading.StrategyLabConfig.fromJson(jsonText, filename)) {
                    is com.signalscope.app.trading.StrategyLabConfig.Companion.ParseResult.Ok -> {
                        com.signalscope.app.data.StrategyLabStore.setConfig(applicationContext, jsonText, filename)
                        gson.toJson(mapOf("ok" to true, "summary" to r.config.summary()))
                    }
                    is com.signalscope.app.trading.StrategyLabConfig.Companion.ParseResult.Rejected ->
                        gson.toJson(mapOf("ok" to false, "error" to r.reason))
                }
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        @JavascriptInterface
        fun strategyLabResetConfig(): String {
            return try {
                com.signalscope.app.data.StrategyLabStore.resetToPreset(applicationContext)
                val cfg = com.signalscope.app.data.StrategyLabStore.activeConfig(applicationContext)
                gson.toJson(mapOf("ok" to true, "summary" to cfg.summary()))
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        @JavascriptInterface
        fun strategyLabResetPaper(): String {
            return try {
                com.signalscope.app.data.StrategyLabStore.resetPaperState(applicationContext)
                gson.toJson(mapOf("ok" to true))
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        /** Force a run now; result delivered via window.onStrategyLabUpdate. */
        @JavascriptInterface
        fun strategyLabRunNow() {
            com.signalscope.app.trading.StrategyLabRunner.runAll(applicationContext, 0L) {
                deliverAiCallback("window.onStrategyLabUpdate", "{}")
            }
        }

        /** Open a file picker for a CSV (same format as the desktop backtester);
         *  runs it against the ACTIVE config and delivers the result to
         *  window.onStrategyLabBacktest — for verifying parity with Python. */
        @JavascriptInterface
        fun strategyLabBacktestPickCsv() {
            backtestNs = "us"
            runOnUiThread {
                try { backtestCsvPicker.launch(arrayOf("*/*")) }
                catch (e: Exception) { Log.e(TAG, "csv picker launch failed", e) }
            }
        }

        /** Read-only: run the engine on each enrolled symbol's live window and
         *  report trades + P&L without touching the ledger. → window.onStrategyLabPreview */
        @JavascriptInterface
        fun strategyLabPreview() {
            thread(isDaemon = true) {
                val out = try {
                    gson.toJson(mapOf("ok" to true,
                        "symbols" to com.signalscope.app.trading.StrategyLabRunner.preview(applicationContext)))
                } catch (e: Exception) {
                    gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "preview failed")))
                }
                deliverAiCallback("window.onStrategyLabPreview", out)
            }
        }

        /** Ingest the recent live window into the ledger so P&L appears now. */
        @JavascriptInterface
        fun strategyLabBackfill() {
            thread(isDaemon = true) {
                try { com.signalscope.app.trading.StrategyLabRunner.backfill(applicationContext) }
                catch (e: Exception) { Log.e(TAG, "backfill failed", e) }
                deliverAiCallback("window.onStrategyLabUpdate", "{}")
            }
        }

        /** Export the last backtest's report in the desktop Python CSV format. */
        @JavascriptInterface
        fun strategyLabExportReport(): Boolean {
            if (lastBacktestReport == null) return false
            runOnUiThread {
                try { reportExportLauncher.launch("signalscope_report_${System.currentTimeMillis()}.csv") }
                catch (e: Exception) { Log.e(TAG, "report export launch failed", e) }
            }
            return true
        }

        // ── MONTHLY REINVEST (rescan→reinvest loop, §4) — "us" / "binance" ───
        private fun reinvestNs(space: String) = when (space.uppercase()) { "BN" -> "binance"; "DE" -> "de"; else -> "us" }

        // ── BUCKETS — multiple strategies per space ──
        private fun bucketBaseNs(space: String) = when (space.uppercase()) {
            "BN", "BINANCE" -> "binance"; "DE" -> "de"; "IN", "INDIA" -> "in"; else -> "us"
        }

        @JavascriptInterface
        fun bucketsList(space: String): String {
            return try {
                // Opportunistic, non-blocking, self-throttled to 30 min: opening the
                // tab tops up prices for open positions the stream doesn't carry.
                // Returns immediately — this call must never wait on the network.
                try { com.signalscope.app.trading.MarkToMarket.refreshAsync(applicationContext) }
                catch (e: Exception) { Log.w(TAG, "mark refresh kick failed", e) }
                val base = bucketBaseNs(space)
                val bucketable = com.signalscope.app.data.BucketStore.isBucketable(base)
                val rows = com.signalscope.app.data.BucketStore.list(applicationContext, base).map { b ->
                    val lab = com.signalscope.app.data.StrategyLabStore.load(applicationContext, b.ns)
                    val guards: Map<String, Map<String, Any?>> =
                        try { com.signalscope.app.trading.T212ExitGuards.views(applicationContext) }
                        catch (e: Exception) { emptyMap() }
                    val closed = lab.trades.filter { it.status == "CLOSED" }
                    // Live position count per algo, per broker. T212 buckets returned
                    // a hardcoded 0 because live stocks were frozen when this was
                    // written — so the arming panel could never show a German algo
                    // what it was actually holding.
                    val liveOpen = if (b.space == "binance")
                        com.signalscope.app.data.BinanceLiveStore.load(applicationContext, b.ns)
                            .trades.count { it.status == "OPEN" }
                    else com.signalscope.app.data.T212LiveStore.load(applicationContext)
                        .trades.count { it.ns == b.ns && (it.status == "OPEN" || it.status == "PENDING" || it.status == "EXITING") }
                    // PORTFOLIO return for a 3b bucket = slot capital ÷ injected.
                    // `paperPnlPct` below is a raw SUM of every closed trade's return
                    // across every scanned name — research, not the book — so it is
                    // not comparable to the backtest. Show this instead when armed.
                    val gate = com.signalscope.app.data.MacdGateStore.load(applicationContext, b.ns)
                    val gateOn = gate.enabled && gate.slots.isNotEmpty()
                    val gateValue = com.signalscope.app.data.MacdGateStore.totalValue(gate)
                    val gatePct = if (gateOn && gate.injected > 0.0)
                        (gateValue / gate.injected - 1.0) * 100.0 else 0.0
                    // OPEN positions valued on the live clock. Kept SEPARATE from
                    // gatePct/paperPnlPct (both realised-only) rather than folded in:
                    // the realised figures are what the gate compounds and sizes the
                    // next position from, so they must not drift with an unclosed
                    // position. The UI adds them for the headline; the stores don't.
                    val mtm = try {
                        com.signalscope.app.trading.MarkToMarket.snapshot(applicationContext, b.ns)
                    } catch (e: Exception) { com.signalscope.app.trading.MarkToMarket.Snapshot() }
                    // Contribution to the whole book: each open seat is one slot of
                    // N, so its unrealised % counts for 1/N of the portfolio.
                    val seatsForMtm = if (gateOn) gate.slots.size.coerceAtLeast(1)
                                      else lab.enrolled.size.coerceAtLeast(1)
                    val openMtmPct = mtm.values.sumOf { it.pctReturn } / seatsForMtm
                    // Slippage is the single assumption that most changes how a P&L
                    // should be read (0% vs 0.20% can flip a champion into a loser),
                    // so it rides along with every algo row rather than being
                    // something the user has to open the JSON to recall.
                    val acfg = com.signalscope.app.data.StrategyLabStore
                        .activeConfig(applicationContext, b.ns)
                    mapOf(
                        "id" to b.id, "ns" to b.ns, "name" to b.name, "space" to b.space,
                        "symbols" to b.symbols, "mode" to b.mode.name, "capital" to b.capital,
                        "holdN" to b.holdN, "smoothing" to b.smoothing,
                        "slippagePct" to acfg.slippagePct,
                        // What a round trip ACTUALLY costs this algo, after the
                        // exclusive choice between a percentage and a flat fee has
                        // been resolved. The badge must show the figure the engine
                        // charges, not the one the strategy file happens to state.
                        // WHICH SETTINGS THE FILE TAKES OVER. Config outranks the UI
                        // so a run is reproducible from the JSON alone (§10) — but
                        // the card never said so, leaving the Slots box looking
                        // broken when a file declared its own top-N and quietly won.
                        "cfgTopN" to acfg.topNOverride,
                        "cfgSmoothing" to acfg.slopeSmoothingOverride,
                        "cfgConfirmDays" to acfg.slopeConfirmDaysOverride,
                        "cfgLookback" to acfg.screenerLookbackOverride,
                        "flatFeePct" to acfg.flatFeeRoundTripPct,
                        "effCostPct" to (acfg.slippagePct + acfg.flatFeeRoundTripPct),
                        // EVERY CHARGE, NAMED AND SIZED. The figure above is a blend,
                        // and a blend is exactly what let a 0.30% conversion charge
                        // sit unnoticed inside an Indian algo's cost for as long as
                        // it did: the card said "≈ 0.41% per round trip" and there
                        // was no way to see what it was made of. A number nobody can
                        // decompose is a number nobody can check.
                        //
                        // Each line is already summed across the legs it applies to,
                        // and a cash charge is converted against THIS algo's seat —
                        // so what the card shows is what this algo pays, not a rate
                        // card the reader has to apply themselves.
                        "costParts" to com.signalscope.app.trading.TradingCosts
                            .brokerCosts(b.ns, config.accountCurrency, config.fxConversionPct)
                            .let { br ->
                                val seat = if (b.holdN > 0) b.capital / b.holdN else b.capital
                                br.components.map { c ->
                                    mapOf("label" to c.label,
                                        "pct" to (c.pct + (if (c.cash > 0.0 && seat > 0.0)
                                            c.cash / seat * 100.0 else 0.0)),
                                        "cash" to c.cash)
                                }
                            },
                        // Set by hand on top of the modelled price list. Surfaced so
                        // the card can warn that it may be counting something twice.
                        "extraFeeCash" to (b.flatFeeOverride ?: config.flatFeeFor(b.ns)),
                        "entryTf" to acfg.resample,
                        "exitTf" to (if (acfg.multiTimeframe) acfg.exitBars else acfg.resample),
                        "warmupBars" to acfg.warmupBars,
                        "configSource" to lab.configSource, "enabled" to lab.enabled,
                        "enrolledCount" to lab.enrolled.size,
                        "openCount" to lab.trades.count { it.status == "OPEN" },
                        "closedCount" to closed.size,
                        "paperPnlPct" to closed.sumOf { it.pctReturn },
                        "openMtmPct" to openMtmPct,
                        "openPriced" to mtm.priced, "openUnpriced" to mtm.unpriced,
                        "markedAt" to mtm.markedAtMs,
                        "openMarks" to mtm.values.map { mapOf(
                            "symbol" to it.symbol, "entryPrice" to it.entryPrice,
                            "price" to it.price, "pctReturn" to it.pctReturn,
                            "stale" to it.stale, "source" to it.source) },
                        "useScreenerSelection" to gate.useScreenerSelection,
                        "gateOn" to gateOn, "gatePct" to gatePct,
                        "gateValue" to gateValue, "gateInjected" to gate.injected,
                        "gateFilled" to gate.slots.count { it.symbol != null },
                        "live" to b.live, "liveBudget" to b.liveBudget,
                        // Null on disk means never chosen, which reads as ON.
                        "notify" to (b.notify ?: true),
                        "liveAllowlist" to b.liveAllowlist, "liveOpen" to liveOpen,
                        // ── THE REAL POSITIONS, NOT JUST HOW MANY ────────────
                        // The card counted them and listed none, so "2 REAL open"
                        // sat directly above "No open positions right now" — both
                        // true, about different books, and impossible to reconcile
                        // from the screen. Worse, the ones that disagree are
                        // exactly the ones worth seeing: a real holding whose
                        // paper row has gone has no strategy exit left on it.
                        "liveRows" to (
                            if (b.space == "binance")
                                com.signalscope.app.data.BinanceLiveStore
                                    .load(applicationContext, b.ns).trades
                                    .filter { it.status == "OPEN" }
                                    .map { listOf(it.symbol, it.paperTradeId, 0.0, "") }
                            else com.signalscope.app.data.T212LiveStore.load(applicationContext)
                                .trades.filter { it.ns == b.ns &&
                                    (it.status == "OPEN" || it.status == "PENDING" || it.status == "EXITING") }
                                .map { listOf(it.symbol,  it.paperTradeId,
                                    (if (it.liveQtyFilled > 0.0) it.liveQtyFilled else it.qty),
                                    (it.adoptedNote ?: ""), (it.error ?: "")) }
                            ).map { r ->
                                val sym = r[0] as String; val pid = r[1] as String
                                val q = r[2] as Double
                                mapOf("symbol" to sym, "paperTradeId" to pid, "qty" to q,
                                    // Why the adopted rules could not be applied,
                                    // when they could not. Blank means they were —
                                    // the position has its full rule set back.
                                    "adoptedNote" to (r[3] as String),
                                    // Why a waiting sale is being held back at the open, when it is.
                                    "saleNote" to ((r.getOrNull(4) as? String)
                                        ?.takeIf { it.startsWith("holding the sale") } ?: ""),
                                    // Does the paper book still know about it? When
                                    // it does not, every strategy exit for this
                                    // position is gone and only the hard stop —
                                    // which reads the live ledger directly — is
                                    // still standing.
                                    "matched" to lab.trades.any { t ->
                                        t.id == pid && t.status == "OPEN" },
                                    // NOT LOST — SOLD ON PAPER, WAITING TO SELL FOR REAL.
                                    // The strategy decided to exit, usually on a bar
                                    // judged after the close, and the exit retry sells
                                    // it at the next tradeable moment. Shown as a lost
                                    // position it invited "I sold this myself", which
                                    // closes the record WITHOUT selling: AFX on 6 Oct
                                    // was left held and unmanaged exactly that way.
                                    "sellPending" to lab.trades.any { t ->
                                        t.id == pid && t.status == "CLOSED" },
                                    "paperExitTs" to (lab.trades.firstOrNull { it.id == pid }?.exitTs ?: 0L),
                                    // The safety stop under a sale that is waiting, when one is set.
                                    "guard" to guards[com.signalscope.app.data.T212ExitGuardStore.key(b.ns, pid)])
                            },
                        // Both the RESOLVED cost (what the engine will actually
                        // charge) and whether it came from an override, so the
                        // card can show the number in force and mark it as
                        // differing from the strategy file.
                        "slipOverride" to b.slippagePctOverride,
                        "flatFeeOverride" to b.flatFeeOverride,
                        "flatFeeEffective" to (b.flatFeeOverride
                            ?: config.flatFeeFor(b.ns)),
                        "currency" to com.signalscope.app.trading.Spaces.currencyOf(b.ns))
                }
                gson.toJson(mapOf("ok" to true, "bucketable" to bucketable, "space" to base, "buckets" to rows))
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        @JavascriptInterface
        fun bucketCreate(space: String, name: String): String {
            return try {
                val base = bucketBaseNs(space)
                if (!com.signalscope.app.data.BucketStore.isBucketable(base))
                    return "{\"ok\":false,\"error\":\"Buckets are US/DE only for now (crypto live path unchanged).\"}"
                val b = com.signalscope.app.data.BucketStore.create(applicationContext, base, name)
                gson.toJson(mapOf("ok" to true, "id" to b.id, "ns" to b.ns))
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        @JavascriptInterface
        fun bucketDelete(ns: String): String {
            return try {
                com.signalscope.app.data.BucketStore.delete(applicationContext, ns)
                "{\"ok\":true}"
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        // ── BUILT-IN PAPER ALGOS: floor orders and MACD turns ─────────────────
        // Their own store and engine, never the bucket list — so no arming path,
        // live mirror or broker audit can reach them. See FloorAlgoStore.

        private fun floorErr(e: Exception) =
            "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"", "")}\"}"

        @JavascriptInterface
        fun floorList(space: String): String = try {
            val base = bucketBaseNs(space)
            gson.toJson(mapOf("ok" to true,
                "cur" to com.signalscope.app.trading.FloorAlgoEngine.currencyMark(base),
                "algos" to com.signalscope.app.data.FloorAlgoStore.list(applicationContext, base)
                    .map { it.copy(log = it.log.takeLast(30).reversed()) }
                    .map { a -> mapOf("a" to a, "ns" to a.ns) }))
        } catch (e: Exception) { floorErr(e) }

        @JavascriptInterface
        fun floorCreate(space: String, name: String, kind: String): String = try {
            val base = bucketBaseNs(space)
            if (base !in com.signalscope.app.data.FloorAlgoStore.SPACES)
                "{\"ok\":false,\"error\":\"These algos run on the stock markets only.\"}"
            else {
                val a = com.signalscope.app.data.FloorAlgoStore.create(applicationContext, base, name, kind)
                com.signalscope.app.service.StrategyLabScheduler.ensureScheduled(applicationContext)
                gson.toJson(mapOf("ok" to true, "id" to a.id, "ns" to a.ns))
            }
        } catch (e: Exception) { floorErr(e) }

        /** Pausing cancels the day's resting orders — and any MACD turn still
         *  waiting for its price — at once. Resuming plans afresh, counting only
         *  prices from that moment on. */
        @JavascriptInterface
        fun floorSetEnabled(id: String, on: Boolean): String = try {
            val stamp = com.signalscope.app.data.FloorAlgoStore.stamp()
            com.signalscope.app.data.FloorAlgoStore.update(applicationContext, id) { a ->
                if (on) a.copy(enabled = true, planDay = if (a.orders.any { it.status == "RESTING" }) a.planDay else "",
                    log = a.log + "$stamp · resumed")
                else a.copy(enabled = false,
                    orders = a.orders.map { if (it.status == "RESTING") it.copy(status = "ENDED") else it },
                    trades = a.trades.filterNot { it.firstSec == 0L && it.signalSec > 0L },
                    log = a.log + "$stamp · paused — nothing new is bought; anything held still sells on its stop, target, time or trend")
            } ?: throw IllegalStateException("algo not found")
            com.signalscope.app.service.StrategyLabScheduler.ensureScheduled(applicationContext)
            "{\"ok\":true}"
        } catch (e: Exception) { floorErr(e) }

        @JavascriptInterface
        fun floorSetNotify(id: String, on: Boolean): String = try {
            com.signalscope.app.data.FloorAlgoStore.update(applicationContext, id) { it.copy(notify = on) }
                ?: throw IllegalStateException("algo not found")
            "{\"ok\":true}"
        } catch (e: Exception) { floorErr(e) }

        @JavascriptInterface
        fun floorRename(id: String, name: String): String = try {
            val n = name.trim().take(40)
            if (n.isEmpty()) throw IllegalArgumentException("a name is needed")
            com.signalscope.app.data.FloorAlgoStore.update(applicationContext, id) { it.copy(name = n) }
                ?: throw IllegalStateException("algo not found")
            "{\"ok\":true}"
        } catch (e: Exception) { floorErr(e) }

        /** New sizes apply to orders placed from now on; what is already resting
         *  or held keeps the size it was placed with. The stop, target and time
         *  limit apply to what is held as well — they are read on every pass.
         *  Switching the trend filter re-plans today straight away. */
        @JavascriptInterface
        fun floorSettings(id: String, capital: Double, seats: Int, universeSize: Int,
                          lowDays: Int, secondPct: Double, holdDays: Int,
                          stopPct: Double, targetPct: Double, trendFilter: Boolean,
                          orderMode: String, zonePct: Double, spreadPct: Double): String = try {
            fun bad(what: String): Nothing = throw IllegalArgumentException(what)
            if (!(capital >= 10.0 && capital <= 10_000_000.0)) bad("capital must be between 10 and 10,000,000")
            if (seats !in 1..30) bad("seats must be 1 to 30")
            if (universeSize !in 5..100) bad("universe must be 5 to 100 stocks")
            if (lowDays !in 1..10) bad("the low must look back 1 to 10 days")
            if (!(secondPct >= 0.0 && secondPct <= 10.0)) bad("the second order must be 0 to 10% below")
            if (holdDays !in 1..20) bad("hold 1 to 20 days")
            if (!(stopPct >= 0.0 && stopPct <= 50.0)) bad("the stop must be 0 to 50% (0 = no stop)")
            if (!(targetPct >= 0.0 && targetPct <= 100.0)) bad("the target must be 0 to 100% (0 = no target)")
            val mode = if (orderMode == com.signalscope.app.data.FloorAlgoStore.MODE_WEEK)
                com.signalscope.app.data.FloorAlgoStore.MODE_WEEK else com.signalscope.app.data.FloorAlgoStore.MODE_DAY
            // Below half: the support and resistance zones must not meet.
            if (!(zonePct >= 1.0 && zonePct <= 45.0)) bad("the weekly zone must be 1 to 45% of the week's range")
            if (!(spreadPct >= 0.0 && spreadPct <= 2.0)) bad("the spread must be 0 to 2% per market order")
            val stamp = com.signalscope.app.data.FloorAlgoStore.stamp()
            com.signalscope.app.data.FloorAlgoStore.update(applicationContext, id) { a ->
                // The paper replay buys the second half before checking the stop
                // in the same bar — true only if a falling price meets the second
                // order first. See FloorOrders.walk.
                if (a.kind == com.signalscope.app.data.FloorAlgoStore.KIND_FLOOR && stopPct > 0.0 && secondPct >= stopPct)
                    bad("the second order must sit above the stop — keep it less than ${"%.1f".format(stopPct)}% below")
                val s = com.signalscope.app.data.FloorAlgoStore.Settings(capital, seats, universeSize, lowDays,
                    secondPct, holdDays, stopPct, targetPct, trendFilter, mode, zonePct,
                    // The market's default is kept as "default" (null) while it is the
                    // value saved, so a later change of default reaches it.
                    spreadPct.takeIf { Math.abs(it - com.signalscope.app.data.FloorAlgoStore.defaultSpreadPct(a.space)) > 1e-9 })
                // The spread is re-read for sales already booked: put right on the next pass.
                val spreadChanged = s.spreadPct != a.settings.spreadPct
                val weekly = a.kind == com.signalscope.app.data.FloorAlgoStore.KIND_FLOOR &&
                    mode == com.signalscope.app.data.FloorAlgoStore.MODE_WEEK
                val modeChanged = a.kind == com.signalscope.app.data.FloorAlgoStore.KIND_FLOOR && mode != a.settings.orderMode
                // A changed trend filter re-plans today at the next check — the
                // engine compares it with the filter today was planned under.
                val filterChanged = trendFilter != a.settings.trendFilter
                if (s == a.settings) a
                else a.copy(settings = s,
                    universeDay = if (universeSize != a.settings.universeSize) "" else a.universeDay,
                    trades = if (!spreadChanged) a.trades
                             else a.trades.map { if (it.status == "SOLD") it.copy(slipIn = false) else it },
                    log = a.log + ("$stamp · settings: ${"%.0f".format(capital)} in $seats seats, top $universeSize, " +
                        "spread ${"%.2f".format(spreadPct)}% per market order, " +
                        (if (weekly) "weekly zone orders (${"%.0f".format(zonePct)}% of the week's range), 2nd order ${"%.1f".format(secondPct)}% below, "
                         else if (a.kind == com.signalscope.app.data.FloorAlgoStore.KIND_FLOOR)
                            "daily orders at the low of $lowDays days, 2nd order ${"%.1f".format(secondPct)}% below, " else "") +
                        "stop ${"%.1f".format(stopPct)}%, target ${"%.1f".format(targetPct)}%, sell after $holdDays days, " +
                        "trend filter ${if (trendFilter) "on" else "off"}" +
                        (if (filterChanged || modeChanged) " — today is re-planned at the next check" else "")))
            } ?: throw IllegalStateException("algo not found")
            "{\"ok\":true}"
        } catch (e: Exception) { floorErr(e) }

        /** A campaign algo's own numbers: its stop (unit and size, as the watcher's
         *  trailing stop), the buffer zones as a fraction of ATR, where the levels
         *  come from, the longest a campaign may run, and the news outlook. */
        @JavascriptInterface
        fun campaignSettings(id: String, stopUnit: String, stopSize: Double, atrFrac: Double,
                             levelSource: String, maxDays: Int, useNews: Boolean,
                             universeMode: String, seats: Int, seatAmount: Double, newsPerDay: Int): String = try {
            fun bad(what: String): Nothing = throw IllegalArgumentException(what)
            val unit = stopUnit.uppercase().takeIf { it in setOf("PCT", "ATR", "BB") } ?: bad("the stop must be in %, ATR or Bollinger widths")
            if (!(stopSize > 0.0 && stopSize <= 50.0)) bad("the stop size must be above 0")
            if (!(atrFrac >= 0.01 && atrFrac <= 2.0)) bad("the buffer must be 0.01 to 2 ATRs")
            if (maxDays !in 5..250) bad("a campaign may run 5 to 250 trading days")
            if (seats !in 0..500) bad("seats must be 0 (no limit) to 500")
            if (!(seatAmount >= 0.0 && seatAmount <= 10_000_000.0)) bad("the money per campaign must be 0 or more")
            if (seats == 0 && seatAmount <= 0.0) bad("with no limit on seats, set the money per campaign")
            if (newsPerDay !in 0..200) bad("news checks a day must be 0 to 200")
            val src = if (levelSource == "range") "range" else "projected"
            val uni = if (universeMode == "all") "all" else "top"
            val stamp = com.signalscope.app.data.FloorAlgoStore.stamp()
            com.signalscope.app.data.FloorAlgoStore.update(applicationContext, id) { a ->
                val s = a.settings.copy(stopUnit = unit, stopPct = stopSize, atrFrac = atrFrac,
                    levelSource = src, maxDays = maxDays, useNews = useNews,
                    universeMode = uni, seats = seats, seatAmount = seatAmount, newsPerDay = newsPerDay)
                if (s == a.settings) a
                else a.copy(settings = s,
                    // A different universe is read from Discovery at the next check.
                    universeDay = if (uni != a.settings.universeMode) "" else a.universeDay,
                    log = a.log + ("$stamp · campaign settings: " +
                    (if (uni == "all") "every stock past the volume × price floor" else "top ${a.settings.universeSize} by ROC") + ", " +
                    (if (seats == 0) "no limit on campaigns" else "$seats seats") +
                    (if (seatAmount > 0.0) ", ${"%.0f".format(seatAmount)} each" else "") +
                    ", $newsPerDay news checks a day, stop $stopSize " +
                    (when (unit) { "ATR" -> "ATR"; "BB" -> "Bollinger half-widths"; else -> "%" }) +
                    " off the high, buffers ${"%.2f".format(atrFrac)} ATR, levels from the " +
                    (if (src == "range") "plain recent range" else "projected trend channel") +
                    ", at most $maxDays days, news outlook ${if (useNews) "on" else "off"}"))
            } ?: throw IllegalStateException("algo not found")
            "{\"ok\":true}"
        } catch (e: Exception) { floorErr(e) }

        /** The monthly-low chain's numbers: seats, capital, how long a stock stays
         *  watched, the daily cap on news checks, whether the news outlook gates a
         *  seat at all, and the spread charged per market-price trade. */
        @JavascriptInterface
        fun chainSettings(id: String, seats: Int, capital: Double, watchDays: Int, newsPerDay: Int,
                          useNews: Boolean, spreadPct: Double): String = try {
            fun bad(what: String): Nothing = throw IllegalArgumentException(what)
            if (seats !in 1..50) bad("seats must be 1 to 50")
            if (!(capital > 0.0 && capital <= 10_000_000.0)) bad("the capital must be above 0")
            if (watchDays !in 1..120) bad("a watch may last 1 to 120 trading days")
            if (newsPerDay !in 0..200) bad("news checks a day must be 0 to 200")
            if (!(spreadPct >= 0.0 && spreadPct <= 5.0)) bad("the spread must be 0 to 5%")
            val stamp = com.signalscope.app.data.FloorAlgoStore.stamp()
            com.signalscope.app.data.FloorAlgoStore.update(applicationContext, id) { a ->
                if (a.kind != com.signalscope.app.data.FloorAlgoStore.KIND_CHAIN) bad("not a chain algo")
                val spread = spreadPct.takeIf { Math.abs(it - com.signalscope.app.data.FloorAlgoStore.defaultSpreadPct(a.space)) > 1e-9 }
                val s = a.settings.copy(seats = seats, capital = capital, watchDays = watchDays,
                    newsPerDay = newsPerDay, useNews = useNews, spreadPct = spread)
                if (s == a.settings) a
                else a.copy(settings = s, log = a.log + ("$stamp · chain settings: $seats seats, " +
                    "${"%.0f".format(capital)} capital, watched $watchDays trading days, " +
                    "news outlook ${if (useNews) "on, at most $newsPerDay a day" else "off"}, " +
                    "spread ${"%.2f".format(spreadPct)}%"))
            } ?: throw IllegalStateException("algo not found")
            "{\"ok\":true}"
        } catch (e: Exception) { floorErr(e) }

        @JavascriptInterface
        fun floorDelete(id: String): String = try {
            com.signalscope.app.data.FloorAlgoStore.delete(applicationContext, id)
            "{\"ok\":true}"
        } catch (e: Exception) { floorErr(e) }

        /** The scan journal's last [days] days, newest first, for the card's Journal tab. */
        @JavascriptInterface
        fun floorJournal(id: String, days: Int): String = try {
            val rows = com.signalscope.app.data.FloorJournalStore.load(applicationContext, id).rows
            val keep = rows.map { it.day }.distinct().sortedDescending().take(days.coerceIn(1, 160)).toSet()
            gson.toJson(mapOf("ok" to true, "days" to rows.map { it.day }.distinct().size,
                "rows" to rows.filter { it.day in keep }.sortedWith(
                    compareByDescending<com.signalscope.app.data.FloorJournalStore.Row> { it.day }.thenBy { it.rank })))
        } catch (e: Exception) { floorErr(e) }

        /** The whole journal as a CSV, through the share sheet. */
        @JavascriptInterface
        fun floorExportJournal(id: String): String = try {
            val a = com.signalscope.app.data.FloorAlgoStore.get(applicationContext, id)
                ?: throw IllegalStateException("algo not found")
            val rows = com.signalscope.app.data.FloorJournalStore.load(applicationContext, id).rows
            if (rows.isEmpty()) throw IllegalStateException("the journal is empty — its first day is written the morning after a plan day")
            val safe = a.name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifEmpty { "algo" }
            val f = java.io.File(cacheDir, "journal_${a.space}_${safe}_${java.time.LocalDate.now()}.csv")
            f.writeText(com.signalscope.app.data.FloorJournalStore.csv(rows))
            runOnUiThread {
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this@MainActivity, "$packageName.fileprovider", f)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, f.name)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "Export scan journal"))
                } catch (e: Exception) { Log.e(TAG, "share journal csv failed", e) }
            }
            gson.toJson(mapOf("ok" to true, "rows" to rows.size, "file" to f.name))
        } catch (e: Exception) { floorErr(e) }

        /** "Check now" — downloads prices, so only reached through [callAsync]. */
        fun floorRunNow(id: String): String {
            val a = com.signalscope.app.data.FloorAlgoStore.get(applicationContext, id)
                ?: return "{\"ok\":false,\"error\":\"algo not found\"}"
            val ran = com.signalscope.app.trading.FloorAlgoEngine.runOne(applicationContext, id)
            if (!ran) return "{\"ok\":false,\"error\":\"another check is still running — try again in a minute\"}"
            return floorList(com.signalscope.app.trading.Spaces.uiSpaceOf(a.space))
        }

        /**
         * Flip one algo's notification flag AND NOTHING ELSE.
         *
         * Deliberately not [bucketConfigure]. That bridge is the whole Save & run
         * routine — it rebuilds the algo from the patch, re-enrols and un-enrols
         * symbols, reshapes seats and re-arms the mode — so borrowing it to set a
         * single boolean ran the entire pipeline behind a tap on a bell. The user
         * saw an algo re-run when they had only asked it to be quiet.
         *
         * A control that does more than it says is the problem regardless of
         * whether the extra work is harmless, so this writes the one field it
         * names and touches nothing else.
         */
        @JavascriptInterface
        fun bucketSetNotify(ns: String, on: Boolean): String {
            return try {
                val cur = com.signalscope.app.data.BucketStore.get(applicationContext, ns)
                    ?: return "{\"ok\":false,\"error\":\"algo not found\"}"
                com.signalscope.app.data.BucketStore.update(applicationContext, cur.copy(notify = on))
                Log.i(TAG, "[$ns] paper notifications ${if (on) "on" else "off"}")
                gson.toJson(mapOf("ok" to true, "ns" to ns, "notify" to on))
            } catch (e: Exception) {
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "could not save")))
            }
        }

        /** Set a bucket's symbol group, mode, capital, and gate params. Also
         *  syncs the underlying lab enrollment to match the symbol list and
         *  arms the chosen mode (3b gate / monthly reinvest).
         *
         *  DOES A GREAT DEAL MORE THAN SAVE, which is why the notification toggle
         *  above has its own bridge rather than borrowing this one. */
        @JavascriptInterface
        fun bucketConfigure(ns: String, patchJson: String): String {
            return try {
                val cur = com.signalscope.app.data.BucketStore.get(applicationContext, ns)
                    ?: return "{\"ok\":false,\"error\":\"bucket not found\"}"
                val p = org.json.JSONObject(patchJson)
                val symbols = if (p.has("symbols")) {
                    val arr = p.getJSONArray("symbols")
                    (0 until arr.length()).map { arr.getString(it).trim().uppercase() }.filter { it.isNotBlank() }
                } else cur.symbols
                val mode = if (p.has("mode"))
                    com.signalscope.app.data.BucketStore.Mode.valueOf(p.getString("mode")) else cur.mode
                val liveAllow = if (p.has("liveAllowlist")) {
                    val arr = p.getJSONArray("liveAllowlist")
                    (0 until arr.length()).map { arr.getString(it).trim().uppercase() }.filter { it.isNotBlank() }
                } else cur.liveAllowlist
                // COST OVERRIDES. Absent key = leave as is; explicit JSON null =
                // clear back to the config/setting; a number = use it, including
                // 0. optDouble cannot express those three states, so read the
                // key directly — using it here would turn "clear" into "zero"
                // and silently claim the strategy trades for free.
                val slipOv = when {
                    !p.has("slippagePctOverride") -> cur.slippagePctOverride
                    p.isNull("slippagePctOverride") -> null
                    else -> p.getDouble("slippagePctOverride").coerceIn(0.0, 10.0)
                }
                val feeOv = when {
                    !p.has("flatFeeOverride") -> cur.flatFeeOverride
                    p.isNull("flatFeeOverride") -> null
                    else -> p.getDouble("flatFeeOverride").coerceAtLeast(0.0)
                }
                val next = cur.copy(
                    name = p.optString("name", cur.name),
                    symbols = symbols, mode = mode,
                    capital = p.optDouble("capital", cur.capital),
                    holdN = p.optInt("holdN", cur.holdN),
                    smoothing = p.optInt("smoothing", cur.smoothing),
                    slippagePctOverride = slipOv,
                    flatFeeOverride = feeOv,
                    // Real money, either broker. This used to force `false` for any
                    // non-Binance bucket on the grounds that "T212 is frozen anyway"
                    // — true when the freeze was a compile-time const, and quietly
                    // wrong once it became a switch the user can throw. It meant a
                    // German algo could never be armed: the checkbox, the save path
                    // and the mirror's own gate were all correct, and this line
                    // discarded the answer before it reached disk.
                    //
                    // Arming is still not sufficient on its own — the master freeze,
                    // the Settings mirror toggle and a non-empty allowlist must all
                    // agree before a real stock order leaves the app.
                    live = p.optBoolean("live", cur.live),
                    // Absent = unchanged, so a card that does not send the key
                    // cannot silently mute an algo.
                    notify = if (p.has("notify")) p.optBoolean("notify", true) else cur.notify,
                    liveBudget = p.optDouble("liveBudget", cur.liveBudget),
                    liveAllowlist = liveAllow)
                // A CHANGED COST INVALIDATES THE STORED REPLAY. That summary is
                // a return produced under the old friction, and leaving it on
                // the card next to the new setting would invite exactly the
                // comparison it cannot support. Dropped, so the card asks for a
                // fresh run rather than quoting a stale one.
                if (next.slippagePctOverride != cur.slippagePctOverride ||
                    next.flatFeeOverride != cur.flatFeeOverride) {
                    try { com.signalscope.app.data.WhatIfStore.remove(applicationContext, ns) }
                    catch (e: Exception) { Log.w(TAG, "what-if drop failed: ${e.message}") }
                }
                // §5.2 — REFUSE a config whose entry frame can never clear warmup
                // on the deepest feed available. Silence was the original failure
                // mode (a 240min config on a 15m/60d feed ran forever and could not
                // fire a single signal), so this must fail loudly at save time.
                val cfgNow = com.signalscope.app.data.StrategyLabStore.activeConfig(applicationContext, ns)
                val planCheck = com.signalscope.app.trading.FeedPlan.plan(cfgNow, ns)
                if (!planCheck.ok) return gson.toJson(mapOf("ok" to false,
                    "error" to (planCheck.problem ?: "This config cannot clear its warmup on any available feed.")))
                Log.i(TAG, "[$ns] engine feed → ${com.signalscope.app.trading.FeedPlan.describe(planCheck)}")

                com.signalscope.app.data.BucketStore.update(applicationContext, next)

                // Seat-reshape outcome, reported back so a change that could NOT be
                // applied says so instead of appearing to have been accepted.
                // Screener selection, per algo. Off = gate only, one seat per
                // symbol, ranking never consulted — the way to run a single name.
                val wantSelection = if (p.has("useScreenerSelection"))
                    p.getBoolean("useScreenerSelection")
                else try {
                    com.signalscope.app.data.MacdGateStore.load(applicationContext, ns).useScreenerSelection
                } catch (e: Exception) { true }
                var gateReshapeBlocked = false
                var gateHeldSeats = 0
                var gateSeatsNow = 0

                // ENROLLMENT OWNERSHIP depends on the mode:
                //  • GATE_3B — `symbols` is the CANDIDATE UNIVERSE to rank, NOT the
                //    basket. MacdGateEngine owns enrollment: it enrolls a name when
                //    it fills a slot and unenrolls it on eject. Enrolling the whole
                //    list here made the engine simulate every candidate (106 names →
                //    24 "open" research trades) instead of the ≤holdN the gate picked.
                //    So enrolled := exactly the names the gate currently holds; the
                //    daily evaluation fills the rest.
                //  • MANUAL / MONTHLY — `symbols` IS the basket, so enroll it as-is.
                val lab = com.signalscope.app.data.StrategyLabStore.load(applicationContext, ns)
                val have = lab.enrolled.map { it.symbol.uppercase() }.toSet()
                val want = if (mode == com.signalscope.app.data.BucketStore.Mode.GATE_3B)
                    com.signalscope.app.data.MacdGateStore.load(applicationContext, ns)
                        .slots.mapNotNull { it.symbol?.uppercase() }.toSet()
                else symbols.toSet()
                val exch = com.signalscope.app.trading.Spaces.exchangeOf(ns)
                for (s in want - have) com.signalscope.app.data.StrategyLabStore.enroll(applicationContext, s, exch, ns)
                for (s in have - want) com.signalscope.app.data.StrategyLabStore.unenroll(applicationContext, s, ns)

                // Arm the chosen mode.
                when (mode) {
                    com.signalscope.app.data.BucketStore.Mode.GATE_3B -> {
                        // §10 PRECEDENCE: config > UI. A JSON carrying the portfolio
                        // knobs makes "run this file, get 374 trades" reproducible;
                        // "set the Slots box to 5 first" is not, and that is exactly
                        // how a silent mismatch survives. Log which source won.
                        val effHoldN = cfgNow.topNOverride ?: next.holdN
                        val effSmooth = cfgNow.slopeSmoothingOverride ?: next.smoothing
                        val effConfirm = cfgNow.slopeConfirmDaysOverride ?: 1
                        Log.i(TAG, "[$ns] gate params — " +
                            "holdN=$effHoldN(${if (cfgNow.topNOverride != null) "config" else "UI"}) " +
                            "smoothing=$effSmooth(${if (cfgNow.slopeSmoothingOverride != null) "config" else "UI"}) " +
                            "confirmDays=$effConfirm(${if (cfgNow.slopeConfirmDaysOverride != null) "config" else "default"}) " +
                            "lookback=${cfgNow.screenerLookbackOverride ?: 90}" +
                            "(${if (cfgNow.screenerLookbackOverride != null) "config" else "default"})" +
                            (cfgNow.reinvestStrategy?.let { " strategy=$it" } ?: ""))
                        com.signalscope.app.data.MacdGateStore.mutate(applicationContext, ns) { st ->
                            // Screener selection OFF ⇒ one seat per symbol, so the
                            // ranking never decides anything and the gate alone governs
                            // each name. Capital is still the bucket's, just divided
                            // over more seats — total exposure is unchanged.
                            // Seats must follow the selection setting being SAVED,
                            // not the one already on disk. Reading the stored flag
                            // here meant switching ranking off left the seat count
                            // at its old value — the card kept saying 1/5 for a
                            // one-company gate-only algo that should read 1/1.
                            val seats = com.signalscope.app.data.MacdGateStore
                                .effectiveHoldN(
                                    st.copy(holdN = effHoldN, useScreenerSelection = wantSelection),
                                    next.symbols.size)
                            val per = next.capital / seats.coerceAtLeast(1)
                            // lastEvalDay="" forces a re-evaluation on the Save & run
                            // pass below, so a universe/holdN change takes effect now
                            // instead of waiting for tomorrow's once-a-day window.
                            val armed = st.copy(enabled = true, holdN = seats,
                                smoothing = effSmooth, confirmDays = effConfirm,
                                notionalPerSlot = per, lastEvalDay = "",
                                useScreenerSelection = wantSelection)
                            // The N cash slots must EXIST or the fill loop has nothing
                            // to iterate and the gate silently never trades.
                            //
                            // RESHAPE ON COUNT *OR* CAPITAL. The old test looked only
                            // at the seat count, so changing capital alone left the
                            // seats and the injected total at their previous size —
                            // and injected is the denominator of this algo's headline
                            // return, which then measured performance against money
                            // the algo no longer has. One card read "capital 400"
                            // above "1000 of 1000 injected".
                            //
                            // Still never mid-position: splitting or merging seats
                            // that hold shares would orphan or duplicate capital. But
                            // a skip must now be REPORTED (see reshapeBlocked below)
                            // rather than silently ignored, which is how a 6-seat
                            // layout survived a change to 5 for weeks unnoticed.
                            // "HOLDS A NAME" IS NOT "HOLDS SHARES". A seat is assigned
                            // its symbol the moment the gate picks it, but the trade
                            // only opens later, when the strategy's own entry fires —
                            // so a seat can carry a name for days with every euro of
                            // it still in cash. Blocking on the name meant the reshape
                            // could never run: six seats stayed six, "1000 of 1000"
                            // stayed stale, and the warning fired on a card that also
                            // said "no open positions". Money at risk is the ledger's
                            // answer, not the seat label's.
                            val investedSyms = try {
                                com.signalscope.app.data.StrategyLabStore
                                    .load(applicationContext, ns).trades
                                    .filter { it.status == "OPEN" }
                                    .map { it.symbol.uppercase() }.toSet()
                            } catch (e: Exception) { emptySet<String>() }
                            val allCash = armed.slots.none {
                                it.symbol?.uppercase()?.let { s -> s in investedSyms } == true
                            }
                            val wantsReshape = armed.slots.size != seats ||
                                kotlin.math.abs(armed.injected - next.capital) > 0.01
                            val needsInit = armed.slots.isEmpty() || (allCash && wantsReshape)
                            gateReshapeBlocked = !armed.slots.isEmpty() && !allCash && wantsReshape
                            gateHeldSeats = armed.slots.count {
                                it.symbol?.uppercase()?.let { s -> s in investedSyms } == true
                            }
                            gateSeatsNow = if (needsInit) seats else armed.slots.size
                            if (needsInit) {
                                // KEEP THE MONEY, don't just report losing it.
                                //
                                // A rebuild used to re-derive every seat from the
                                // CONFIGURED capital, so seats that had grown to 440
                                // snapped back to 400 and 40 of real gains left the
                                // book. Merely logging that is visibility, not a fix.
                                //
                                // The two reasons a rebuild happens want opposite
                                // treatment, and the existing test already tells them
                                // apart:
                                //  - CAPITAL CHANGED — the user just declared a new
                                //    total. Honour it; that is the whole point of
                                //    typing a new number.
                                //  - ONLY THE SEAT COUNT CHANGED — a layout change,
                                //    not a funding decision. Redistribute what the
                                //    seats are actually WORTH across the new count,
                                //    and leave the injected baseline alone so the
                                //    performance history survives re-shaping.
                                val nowMs = System.currentTimeMillis()
                                val beforeVal = com.signalscope.app.data.MacdGateStore
                                    .totalValue(armed)
                                val capitalChanged =
                                    kotlin.math.abs(armed.injected - next.capital) > 0.01
                                val keepGrowth = !capitalChanged && beforeVal > 0.0 &&
                                    armed.slots.isNotEmpty()
                                val perEff = if (keepGrowth) beforeVal / seats else per
                                val injectedEff =
                                    if (keepGrowth) armed.injected else perEff * seats
                                val wasPct = if (armed.injected > 0.0)
                                    (beforeVal / armed.injected - 1.0) * 100.0 else 0.0
                                // Every running span ends here — a rebuild tears the
                                // seats down, so leaving spans open would make later
                                // trades appear to sit inside an enrollment that no
                                // longer exists.
                                val ended = com.signalscope.app.data.MacdGateStore
                                    .closeAllEnrollments(armed, "seat_rebuild", nowMs)
                                com.signalscope.app.data.MacdGateStore.appendLog(
                                    ended.copy(
                                        slots = (1..seats).map {
                                            com.signalscope.app.data.MacdGateStore.Slot(
                                                null, perEff, nowMs, 0, false)
                                        },
                                        injected = injectedEff),
                                    "initialized $seats slots × ${"%.0f".format(perEff)}" +
                                        (if (!st.useScreenerSelection) " (gate only — one seat per symbol)" else "") +
                                        (if (armed.slots.isEmpty()) ""
                                         else if (keepGrowth)
                                            " — reshaped from ${armed.slots.size} seats, " +
                                            "${"%.2f".format(beforeVal)} kept " +
                                            "(${"%+.2f".format(wasPct)}% preserved)"
                                         else
                                            " — capital changed to ${"%.0f".format(next.capital)}, " +
                                            "rebuilt from ${"%.2f".format(beforeVal)} " +
                                            "(${"%+.2f".format(wasPct)}% discarded)"))
                            }
                            else armed
                        }
                    }
                    else -> {
                        // Non-gate modes: make sure the gate is off for this bucket.
                        com.signalscope.app.data.MacdGateStore.mutate(applicationContext, ns) { it.copy(enabled = false) }
                    }
                }
                com.signalscope.app.data.StrategyLabStore.setEnabled(applicationContext, true, ns)
                com.signalscope.app.trading.StrategyLabRunner.runAll(applicationContext, 0L, ns)
                gson.toJson(mapOf("ok" to true,
                    "reshapeBlocked" to gateReshapeBlocked,
                    "heldSeats" to gateHeldSeats,
                    "seatsNow" to gateSeatsNow))
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        /** Attach a config JSON file to a specific bucket namespace. */
        @JavascriptInterface
        fun bucketPickConfigFile(ns: String) {
            configPickNs = ns
            runOnUiThread {
                try { configJsonPicker.launch(arrayOf("application/json", "text/plain", "*/*")) }
                catch (e: Exception) { Log.e(TAG, "bucket config picker launch failed", e) }
            }
        }

        /** Candle + entry/exit review for ONE symbol under a bucket's config →
         *  window.onLabReview (same payload/chart the old lab used). */
        @JavascriptInterface
        fun bucketReview(ns: String, symbol: String) {
            thread(isDaemon = true) {
                val out = try {
                    gson.toJson(com.signalscope.app.trading.StrategyLabRunner.reviewPayload(applicationContext, ns, symbol))
                } catch (e: Exception) {
                    gson.toJson(mapOf("symbol" to symbol, "error" to (e.message ?: "review failed")))
                }
                deliverAiCallback("window.onLabReview", out)
            }
        }

        /**
         * MANUAL ENTRY WATCHER state for a space — everything the Watchlist tab's
         * panel needs, in one read. No fetch and no engine run: candidates come
         * from the gates' last stored evaluation and the rest is local state, so
         * opening the tab costs nothing.
         */
        @JavascriptInterface
        fun manualWatcherState(space: String): String {
            // SHORT-TTL MEMO. WebView runs JS on the UI thread and a bridge call
            // blocks it until it returns, so the cost of this method lands directly
            // on input responsiveness. It walks every armed algo's ledger — files
            // that hold up to 1000 trades and 500 audits each — and the Watchlist
            // tab repaints far more often than this data changes.
            //
            // A second of staleness is invisible on a panel whose inputs move once
            // a day (the ranking) or on a user action (a buy). Every action that
            // does change it calls invalidateManualWatcherState, so the memo never
            // hides a result the user just caused.
            val key = space.lowercase()
            val now = System.currentTimeMillis()
            mwsCache[key]?.let { (at, json) -> if (now - at < MWS_TTL_MS) return json }
            val out = buildManualWatcherState(space)
            mwsCache[key] = Pair(now, out)
            return out
        }

        private fun buildManualWatcherState(space: String): String {
            return try {
                val base = com.signalscope.app.trading.Spaces.baseOf(space.lowercase().let {
                    when (it) { "de" -> "de"; "bn" -> "binance"; "in" -> "in"; "us" -> "us"; else -> it }
                })
                val ns = com.signalscope.app.data.ManualWatchStore.nsFor(base)
                val lab = com.signalscope.app.data.StrategyLabStore.load(applicationContext, ns)
                val configured = lab.configJson != null
                val cfg = com.signalscope.app.data.StrategyLabStore.activeConfig(applicationContext, ns)
                val st = com.signalscope.app.data.ManualWatchStore.load(applicationContext, base)
                // ONE exposure walk, ONE state read, shared by the candidate list and
                // the ranking below. This method is called on every repaint of the
                // Watchlist tab, and computing it twice meant walking every algo's
                // gate, ledger and broker rows twice per frame.
                val exposed = com.signalscope.app.trading.ManualEntryWatcher
                    .exposedSymbols(applicationContext, base)
                // WHICH NAMES ACTUALLY FIRED, and when. A notification is read once
                // and dismissed; without this the panel cannot answer "what was the
                // one it told me about?" and the ticker has to be remembered.
                // Kept as a map so a row can say WHEN, not merely that it happened.
                val alerted = st.alerted
                val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
                // A year of price and MACD per name, read once for the whole panel.
                // Kept in its own file precisely so this read is optional and does
                // not weigh down every repaint of the rest of the state.
                val spark = com.signalscope.app.data.ManualSparkStore
                    .load(applicationContext, base).series
                // WHERE EACH NAME HAS BEEN TURNING, off the watcher's own bars.
                // Handed over raw — the two prices, what they were measured
                // against, and when — so the panel can state the distance against
                // whatever price is current rather than a percentage frozen at the
                // moment of the last pass.
                fun lv(sym: String): Map<String, Any?>? =
                    st.levels[sym.uppercase()]?.let {
                        mapOf("support" to it.support, "resistance" to it.resistance,
                              "supportTouches" to it.supportTouches,
                              "resistanceTouches" to it.resistanceTouches,
                              // The swing window — the reading an entry is timed
                              // off, and the one the list can be ordered by.
                              "swingLow" to it.swingLow, "swingHigh" to it.swingHigh,
                              "weekLow" to it.weekLow, "weekHigh" to it.weekHigh, "weekDays" to it.weekDays,
                              "monthLow" to it.monthLow, "monthHigh" to it.monthHigh, "monthDays" to it.monthDays,
                              "swingPos" to it.swingPos,
                              "swingVisits" to it.swingVisits,
                              "swingDays" to it.swingDays,
                              "price" to it.price, "barMin" to it.barMin,
                              "bars" to it.bars, "atMs" to it.atMs)
                    }
                val cands = com.signalscope.app.trading.ManualEntryWatcher
                    .candidates(applicationContext, base, exposedIn = exposed, stateIn = st)
                    .map { c ->
                        val day = alerted[c.symbol.uppercase()]
                        val h = st.holdings[c.symbol.uppercase()]
                        val hh = st.historical[c.symbol.uppercase()]
                        val sp = spark[c.symbol.uppercase()]
                        val mkC = try { com.signalscope.app.trading.MarkToMarket.get(c.symbol) }
                                  catch (ex: Exception) { null }
                        mapOf("symbol" to c.symbol, "rank" to c.rank,
                            "score" to c.score, "slope" to c.slope, "fromNs" to c.fromNs,
                            // Hand-added rather than ranked in. Shown because the
                            // row otherwise looks like the screener nominated a
                            // name with a flat slope, which would read as a bug.
                            "pinned" to com.signalscope.app.data.ManualWatchStore
                                .isPinned(st, c.symbol),
                            "alertedDay" to day, "alertedToday" to (day == today),
                            // The strategy is already long on this one, so it will
                            // never alert until it exits. Shown, not hidden.
                            "holding" to (h != null),
                            "holdingSince" to h?.since,
                            "holdingPct" to h?.pctReturn,
                            "holdingFresh" to (h?.fresh == true),
                            // ── THE FULL-HISTORY READ ────────────────────────────
                            // Would the strategy be riding this today, had it always
                            // been on it? Separate from the fields above, which are
                            // bounded to when this name joined the watch list and
                            // therefore say nothing about the year before that.
                            "histHolding" to (hh != null),
                            "histSince" to hh?.since,
                            "histPct" to hh?.pctReturn,
                            "sparkPx" to sp?.px, "sparkMacd" to sp?.macd,
                            // The short MACD curve and its phase, shaped exactly
                            // like Discovery's, so a name missing from the current
                            // scan is still drawn the way Discovery would draw it
                            // rather than as a year of crossings in forty pixels.
                            "sparkMacdRecent" to sp?.macdRecent,
                            "sparkPhase" to sp?.phase,
                            "sparkDays" to sp?.days,
                            "sparkT0" to sp?.t0, "sparkT1" to sp?.t1,
                            // ── THE PROJECTED CHANNEL ────────────────────────
                            // The only PREDICTION on this panel. Where the 50-day
                            // trend line is heading and how wide this stock
                            // normally swings around it — sent as its own fields,
                            // never folded in with the measured levels, so the
                            // screen can keep the two visibly apart.
                            "projFloor" to sp?.projFloor,
                            "projCeiling" to sp?.projCeiling,
                            "projMid" to sp?.projMid,
                            "projDays" to sp?.projDays,
                            // ── WHAT IT COSTS RIGHT NOW ──────────────────────
                            // Sent so the Buy dialog can say the price before you
                            // commit an amount. Live mark when one exists,
                            // otherwise the last daily close from the sparkline —
                            // and which of the two it is travels with it, because
                            // a yesterday price shown as if it were live is how
                            // someone sizes an order against a number the market
                            // has already left.
                            "px" to (mkC?.price?.takeIf { it > 0.0 } ?: sp?.px?.lastOrNull()),
                            // LIVE means recently ticked, not merely present. A mark
                            // derived from a closed bar is a real price and a stale
                            // one, and labelling it live is how an order gets sized
                            // and triggered against yesterday.
                            "pxLive" to (mkC != null && mkC.price > 0.0 &&
                                (System.currentTimeMillis() - mkC.atMs) < 5 * 60_000L),
                            "pxAtMs" to (mkC?.atMs ?: 0L),
                            "levels" to lv(c.symbol))
                    }
                // Open engine positions in the manual book — what the config is
                // currently managing the exit of.
                val openSyms = lab.trades.filter { it.status == "OPEN" }
                    .associateBy { it.symbol.uppercase() }
                // Every trade by id, so a CLOSED entry can report what the strategy
                // finally made on it. Keyed by id rather than symbol: re-entering the
                // same name later is a different trade and must not overwrite the
                // first one's result.
                val tradesById = lab.trades.associateBy { it.id }
                // 1-week-high targets waiting at Trading 212 (Germany and the US only).
                val weekTargets = if (base == "de" || base == "us")
                    try { com.signalscope.app.trading.T212WeekTargets.views(applicationContext) }
                    catch (ex: Exception) { emptyMap() }
                else emptyMap()
                // Live positions first, finished ones beneath. Both belong in the
                // book — a trade that vanishes the moment it closes teaches nothing
                // — but only one of the two can still need a decision from you.
                val entries = st.entries
                    .sortedWith(compareBy({ if (it.status == "CLOSED") 1 else 0 }, { -it.createdAt }))
                    .take(40).map { e ->
                    val t = openSyms[e.symbol.uppercase()]
                    // The ledger trade behind this entry, open or finished. A closed
                    // one still has an entry price, and without it a finished row
                    // would show the strategy side as blank — as if it had never
                    // managed the position it in fact ran start to finish.
                    val lt = tradesById["${e.symbol.uppercase()}-${e.signalBarTs}"]
                    // YOUR money's return, measured from what you actually PAID
                    // against the newest price known — which is what the broker
                    // shows you and what you actually have. Kept separate from the
                    // engine's figure below: that one is measured from the engine's
                    // own entry bar against its last COMPLETED candle, so the two
                    // disagree on both ends and neither is wrong.
                    val mk = try { com.signalscope.app.trading.MarkToMarket.get(e.symbol) }
                             catch (ex: Exception) { null }
                    val livePx = mk?.price ?: 0.0
                    val realPct = if (e.filledPrice > 0.0 && livePx > 0.0)
                        (livePx / e.filledPrice - 1.0) * 100.0 else null
                    mapOf("symbol" to e.symbol, "status" to e.status,
                        "livePrice" to livePx, "realPct" to realPct,
                        "askedPrice" to e.askedPrice,
                        // Negative = filled BETTER than asked (price improvement).
                        "execPct" to (if (e.askedPrice > 0.0 && e.filledPrice > 0.0)
                            (e.filledPrice / e.askedPrice - 1.0) * 100.0 else null),
                        "liveAtMs" to (mk?.atMs ?: 0L), "liveSource" to (mk?.source ?: ""),
                        "engineEntry" to (t?.entryPrice ?: lt?.entryPrice),
                        "signalBarTs" to e.signalBarTs, "signalPrice" to e.signalPrice,
                        "filledQty" to e.filledQty, "filledPrice" to e.filledPrice,
                        "driftPct" to e.driftPct, "requestedCash" to e.requestedCash,
                        "createdAt" to e.createdAt, "note" to e.note,
                        "ticker" to e.t212Ticker,
                        // Whether the ENGINE actually holds it — the one check that
                        // proves the hand-over worked. An entry marked OPEN here with
                        // no engine position behind it is a managed exit that isn't.
                        "engineHolds" to (t != null),
                        "enginePct" to t?.pctReturn,
                        // ── FINISHED TRADES ──────────────────────────────────
                        // A closed row is history, not a warning. Sent explicitly
                        // rather than inferred from an absent engine position,
                        // which is exactly the guess that made every completed
                        // trade look unguarded.
                        "closed" to (e.status == "CLOSED"),
                        // AN ATTEMPT THAT BOUGHT NOTHING. Sent as a verdict rather
                        // than re-derived in the panel, so the list and the
                        // held-outside check can never disagree about what counts.
                        "deadAttempt" to com.signalscope.app.data.ManualWatchStore.isDeadAttempt(e),
                        // Empty = the strategy is managing this one. Non-empty = it
                        // cannot, and says why. Never inferred from an attached
                        // config, which is what made the row claim management it
                        // was not performing.
                        "manageNote" to (st.adoptedNote[e.symbol.uppercase()] ?: ""),
                        // On a holding the question these answer is different from
                        // the one on a candidate: not "am I buying into a ceiling"
                        // but "is my stop under a floor that has actually held".
                        "levels" to lv(e.symbol),
                        // Same projection on a holding: where the channel it is
                        // riding is heading over the next few weeks.
                        "projFloor" to spark[e.symbol.uppercase()]?.projFloor,
                        "projCeiling" to spark[e.symbol.uppercase()]?.projCeiling,
                        "projMid" to spark[e.symbol.uppercase()]?.projMid,
                        "projDays" to spark[e.symbol.uppercase()]?.projDays,
                        // A resting buy trigger: an instruction, not a holding.
                        // Sent so the row can say what it is waiting for rather
                        // than looking like a position with no numbers in it.
                        "gttId" to e.gttId, "triggerPrice" to e.triggerPrice,
                        // The other way an entry can be waiting: an ordinary order
                        // the broker accepted but has not filled. Without this the
                        // row showed nothing at all and offered no way to call it
                        // off, because the badge was keyed on the trigger alone.
                        "buyOrderId" to (e.buyOrderId ?: ""),
                        // Bought against the strategy's judgement rather than on it.
                        // Surfaced so a forced trade can never be read later as
                        // evidence about a strategy that never chose it.
                        "forced" to e.forced,
                        "exitTs" to e.exitTs, "exitPrice" to e.exitPrice,
                        "exitQty" to e.exitQty, "exitReason" to (e.exitReason ?: ""),
                        // WHEN THE POSITION ACTUALLY ENDED, which is not [exitTs] —
                        // that is the strategy BAR the exit rule fired on. The two
                        // can sit days apart when a rule fires into a shut market,
                        // so the ledger orders by this one: a list of finished
                        // trades is read in the order they finished.
                        "closedAt" to e.closedAt,
                        // YOUR realised return, fill to fill. Null when the broker
                        // never reported a real exit price — the card then shows the
                        // strategy's figure and says so, instead of passing a candle
                        // close off as money.
                        "realExitPct" to e.realPctReturn,
                        "paperExitPct" to lt?.takeIf { it.status == "CLOSED" }?.pctReturn,
                        "ns" to ns,
                        "target" to weekTargets[com.signalscope.app.data.T212TargetStore.key(
                            ns, "${e.symbol.uppercase()}-${e.signalBarTs}")])
                }
                // The watcher's OWN full ranking, with the reason each name is or
                // is not a candidate. Held names are marked rather than dropped:
                // "why isn't BMW on the list" is the question this answers, and an
                // omission answers nothing.
                val ranking = st.ranking.map { r ->
                    mapOf("symbol" to r.symbol, "rank" to r.rank, "score" to r.score,
                        "slope" to r.slope, "eligible" to (r.slope > 0.0),
                        "held" to (r.symbol.uppercase() in exposed))
                }
                // SEAT SATURATION across the space's 3b algos. Display only — the
                // watcher does not consult these to build its list any more. It is
                // here to answer the question the candidate count raises on its own:
                // "are these waiting because the algos are FULL, or because the
                // algos would not take them anyway?" A full house means the
                // opportunities are real ones the strategies simply have no room
                // for, which is exactly when a manual entry is worth considering.
                var seatsFilled = 0; var seatsTotal = 0; var algoCount = 0
                val seatRows = ArrayList<Map<String, Any?>>()
                try {
                    for (b in com.signalscope.app.data.BucketStore.list(applicationContext, base)) {
                        if (b.mode != com.signalscope.app.data.BucketStore.Mode.GATE_3B) continue
                        val gs = com.signalscope.app.data.MacdGateStore.load(applicationContext, b.ns)
                        if (!gs.enabled || gs.slots.isEmpty()) continue
                        val f = gs.slots.count { it.symbol != null }
                        seatsFilled += f; seatsTotal += gs.slots.size; algoCount++
                        seatRows.add(mapOf("name" to b.name, "ns" to b.ns,
                            "filled" to f, "total" to gs.slots.size, "live" to b.live))
                    }
                } catch (e: Exception) { Log.w(TAG, "seat summary failed", e) }
                // ── WHAT YOU ACTUALLY HOLD, beside what the app bought ──────────
                // The book and the account are two different facts and the panel
                // needs both to stop lying by omission. A name bought partly here
                // and partly in the broker's own app looked, in this list, like a
                // small position — and a name bought entirely outside did not
                // appear at all, while the strategy was quietly judging it.
                //
                // Unreadable is passed through as unreadable. A rate-limited fetch
                // must not render as "you own nothing", which is the shape of every
                // dangerous mistake this app has made about holdings.
                var holdOk = true
                val brokerQty = HashMap<String, Double>()
                val brokerCost = HashMap<String, Double>()
                val brokerPx = HashMap<String, Double>()
                try {
                    when (val r = com.signalscope.app.trading.BrokerHoldings.forSpace(applicationContext, base)) {
                        is com.signalscope.app.trading.BrokerHoldings.Result.Ok ->
                            r.holdings.forEach {
                                val k = it.symbol.uppercase()
                                brokerQty[k] = it.qty; brokerCost[k] = it.avgCost; brokerPx[k] = it.price
                            }
                        is com.signalscope.app.trading.BrokerHoldings.Result.Unreadable -> holdOk = false
                    }
                } catch (e: Exception) { holdOk = false }
                val managedQty = try {
                    com.signalscope.app.trading.ManualEntryWatcher.managedQtyFor(applicationContext, base)
                } catch (e: Exception) { emptyMap() }
                val managedOwners = try {
                    com.signalscope.app.trading.ManualEntryWatcher
                        .managedOwnersFor(applicationContext, base)
                } catch (e: Exception) { emptyMap() }
                val trackState = try {
                    com.signalscope.app.data.PortfolioTrackStore.load(applicationContext, base)
                } catch (e: Exception) { null }
                // A dead attempt is not coverage. Counting one here hid a real
                // holding of the same company from the held-outside list — the
                // book "had" it, as a row with no shares in it.
                val bookSyms = st.entries.filter {
                    it.status != "CLOSED" &&
                        !com.signalscope.app.data.ManualWatchStore.isDeadAttempt(it)
                }.map { it.symbol.uppercase() }.toHashSet()
                // Holdings with NO row in the book at all — bought in the broker's
                // own app, or long before this app existed. Same shape as an entry
                // so the panel can render them in one list.
                val heldOnly = brokerQty.keys.filter { it !in bookSyms }.map { sym ->
                    val q = brokerQty[sym] ?: 0.0
                    val c = brokerCost[sym] ?: 0.0
                    val p = brokerPx[sym] ?: 0.0
                    mapOf("symbol" to sym, "qty" to q, "avgCost" to c, "price" to p,
                        "pct" to (if (c > 0.0 && p > 0.0) (p / c - 1.0) * 100.0 else null),
                        "managedQty" to (managedQty[sym] ?: 0.0),
                        // WHO IS ALREADY LOOKING AFTER IT, by name. Without this the
                        // panel could not tell "nothing is judging this" from "an
                        // algo owns it and will sell it" — and said the former for
                        // both, which reads as unprotected.
                        "managedBy" to (managedOwners[sym] ?: emptyList<String>()).map { n ->
                            try {
                                if (com.signalscope.app.trading.Spaces.bucketIdOf(n) ==
                                    com.signalscope.app.data.ManualWatchStore.ID) "the watchlist"
                                else com.signalscope.app.data.BucketStore.get(applicationContext, n)?.name ?: n
                            } catch (e: Exception) { n }
                        },
                        "reason" to (trackState?.tracks?.get(sym)?.lastReason ?: ""),
                        "alertedDay" to (trackState?.tracks?.get(sym)?.alertedDay ?: ""))
                }
                gson.toJson(mapOf("ok" to true, "ns" to ns, "space" to base,
                    "configured" to configured,
                    "holdingsOk" to holdOk,
                    "brokerQty" to brokerQty,
                    "managedQty" to managedQty,
                    "heldOnly" to heldOnly,
                    // India screens and alerts, but has no order path wired here —
                    // placement there is Zerodha's, with its own arming gates.
                    "canBuy" to com.signalscope.app.trading.ManualEntryWatcher.canBuyIn(base),
                    "ranking" to ranking,
                    "seats" to seatRows, "seatsFilled" to seatsFilled,
                    "seatsTotal" to seatsTotal, "algoCount" to algoCount,
                    "lastEvalDay" to st.lastEvalDay, "rankedAt" to st.rankedAt,
                    // The liquidity floor, and what it actually removed. Sent
                    // together so the panel can never show one without the other
                    // — a threshold with no effect and an effect with no stated
                    // threshold are both ways of pruning invisibly.
                    "turnoverFloor" to config.minTurnoverFor(base),
                    "illiquidCut" to st.illiquidCut,
                    "floorApplied" to st.turnoverFloor,
                    "scanned" to st.scanned, "eligibleCount" to st.eligible,
                    "configSource" to (if (configured) lab.configSource else ""),
                    // The stop percentage, so the panel can show where each stop
                    // actually sits. Two positions with the same config have
                    // different real stops, because each is measured from what that
                    // position actually cost.
                    "stopPct" to (if (cfg.exit.stopLossEnabled) cfg.exit.stopLossPct else 0.0),
                    // The hand-set trail, and where it takes its high from.
                    "trailPct" to st.trailPct, "trailSource" to st.trailSource.name,
                    "trailUnit" to (st.trailUnit ?: com.signalscope.app.data.ManualWatchStore.TrailUnit.PCT).name,
                    // Where each held position's floor sits now, so a trail set in
                    // ATR or band units can be read as a price.
                    "trailFloors" to st.entries.filter { it.status == "OPEN" && it.trailLevel > 0.0 }
                        .map { mapOf("symbol" to it.symbol, "level" to it.trailLevel,
                            "peak" to it.peakPrice, "fill" to it.filledPrice) },
                    // The hand-set target. Measured from each position's OWN fill,
                    // so the panel works the level out per row rather than being
                    // handed one price that would be wrong for every position but
                    // the one it was computed from.
                    "takeProfitPct" to st.takeProfitPct,
                    // ROUND-TRIP CURRENCY COST for this space, so a position's P&L
                    // can be shown NET of what closing it will actually cost. The
                    // buy conversion is already spent; the sell one is certain the
                    // moment you exit, so a "profit" that ignores it is a profit you
                    // cannot collect.
                    "fxRoundTripPct" to (
                        if (!com.signalscope.app.trading.Spaces.currencyOf(base)
                                .equals(config.accountCurrency, true))
                            2.0 * config.fxConversionPct.coerceAtLeast(0.0)
                        else 0.0),
                    "accountCurrency" to config.accountCurrency,
                    // Per-ORDER flat fee in the account's currency. Charged twice on
                    // a round trip, and against the POSITION's own value — not the
                    // config's notional, which is a different and usually larger
                    // number. A cash fee on a small position is a large percentage.
                    "flatFeeCash" to config.flatFeeFor(base),
                    "entryTf" to cfg.resample,
                    "exitTf" to (if (cfg.multiTimeframe) cfg.exitBars else cfg.resample),
                    "slippagePct" to cfg.slippagePct,
                    "warmupBars" to cfg.warmupBars,
                    "currency" to com.signalscope.app.trading.Spaces.currencyOf(base),
                    "candidates" to cands,
                    "entries" to entries,
                    "lastRunAt" to st.lastRunAt, "lastRunNote" to st.lastRunNote,
                    // Everything alerted recently, newest first — including names no
                    // longer candidates (bought since, or the algos took them). The
                    // question "which one did it tell me about?" is asked precisely
                    // when the name has moved on from the list.
                    "alertLog" to alerted.entries
                        .sortedByDescending { it.value }
                        .take(20)
                        .map { mapOf("symbol" to it.key, "day" to it.value,
                            "today" to (it.value == today)) },
                    "alertedToday" to alerted.count { it.value == today }))
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        /**
         * Run the watcher's own screen NOW, then check its candidates for entries.
         *
         * Without this the watcher only wakes on the background paper tick, so a
         * freshly attached config shows an empty list for up to 15 minutes with
         * nothing on screen explaining why. Forces past the once-a-day guard, so it
         * is also the way to re-screen after changing the config.
         *
         * Read-only: ranks, checks, and may notify. Places no orders.
         */
        @JavascriptInterface
        fun manualWatcherRunNow(space: String) {
            thread(isDaemon = true, name = "manual-watch-now") {
                val out = try {
                    com.signalscope.app.trading.ManualEntryWatcher
                        .evaluateDaily(applicationContext, space, force = true)
                    val o = com.signalscope.app.trading.ManualEntryWatcher
                        .runOnce(applicationContext, space)
                    gson.toJson(mapOf("ok" to true, "checked" to o.checked,
                        "alerted" to o.alerted, "note" to o.note))
                } catch (e: Exception) {
                    Log.e(TAG, "manualWatcherRunNow failed", e)
                    gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "failed")))
                }
                invalidateManualWatcherState()
                deliverAiCallback("window.onManualWatchRun", out)
            }
        }

        /**
         * Buy a watch-list candidate with the patient ladder and hand it to the
         * engine. REAL MONEY — only ever reached from an explicit user tap that the
         * page has already confirmed. Result → window.onManualBuy; ladder progress
         * rides the existing window.onT212PatientProgress contract.
         */
        @JavascriptInterface
        fun manualBuy(space: String, symbol: String, cash: Double) =
            manualBuyForce(space, symbol, cash, false)

        /**
         * Buy with no entry signal standing — the user overriding the strategy.
         *
         * A separate bridge method rather than an extra argument on the old one, so
         * an override can never happen because a caller passed a stray truthy value
         * into a parameter it did not know about. Forcing a real order has to be
         * something the caller asked for by name.
         */
        @JavascriptInterface
        fun manualBuyForce(space: String, symbol: String, cash: Double, force: Boolean) {
            thread(isDaemon = true, name = "manual-buy") {
                val out = try {
                    val r = com.signalscope.app.trading.ManualEntryWatcher.buyAndHandOver(
                        applicationContext, space, symbol, cash, patientProgressSink(), force)
                    gson.toJson(mapOf("ok" to r.ok, "symbol" to r.symbol,
                        "filledQty" to r.filledQty, "filledPrice" to r.filledPrice,
                        "signalPrice" to r.signalPrice, "driftPct" to r.driftPct,
                        "error" to r.error, "note" to r.note,
                        // TWO SEPARATE FACTS, and the screen needs both. "immediate"
                        // says an ordinary order went in rather than a trigger being
                        // parked; "filled" says shares actually exist. An accepted
                        // order still working is immediate and NOT filled, and
                        // announcing that as bought is the same lie as announcing a
                        // resting trigger as bought.
                        "immediate" to r.immediate, "filled" to (r.filledQty > 0.0)))
                } catch (e: Exception) {
                    Log.e(TAG, "manualBuy failed", e)
                    gson.toJson(mapOf("ok" to false, "symbol" to symbol,
                        "error" to (e.message ?: "buy failed")))
                }
                invalidateManualWatcherState()
                deliverAiCallback("window.onManualBuy", out)
            }
        }

        /**
         * The hand-set percentage trail for this market's watcher book.
         *
         * Zero switches it off. The high-water marks keep being recorded either
         * way, so switching it on later measures from the real high rather than
         * from wherever price happened to be at that moment.
         */
        @JavascriptInterface
        fun manualSetTrailing(space: String, pct: Double, source: String): String {
            return try {
                val base = com.signalscope.app.trading.Spaces.baseOf(space.lowercase())
                val src = try {
                    com.signalscope.app.data.ManualWatchStore.TrailSource.valueOf(source)
                } catch (e: Exception) {
                    com.signalscope.app.data.ManualWatchStore.TrailSource.BAR_CLOSE
                }
                val clean = pct.coerceIn(0.0, 90.0)
                com.signalscope.app.data.ManualWatchStore.mutate(applicationContext, base) {
                    it.copy(trailPct = clean, trailSource = src)
                }
                Log.w(TAG, "[$base] manual trailing stop set to " +
                    (if (clean <= 0.0) "OFF" else "${"%.2f".format(clean)}% on $src"))
                gson.toJson(mapOf("ok" to true, "pct" to clean, "source" to src.name))
            } catch (e: Exception) {
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "could not save")))
            }
        }

        /** What the hand-set trail's number counts in: PCT, ATR or BB. Saved on its
         *  own so changing the unit never rewrites the number or the clock. */
        @JavascriptInterface
        fun manualSetTrailUnit(space: String, unit: String): String {
            return try {
                val base = com.signalscope.app.trading.Spaces.baseOf(space.lowercase())
                val u = try { com.signalscope.app.data.ManualWatchStore.TrailUnit.valueOf(unit.uppercase()) }
                        catch (e: Exception) { com.signalscope.app.data.ManualWatchStore.TrailUnit.PCT }
                com.signalscope.app.data.ManualWatchStore.mutate(applicationContext, base) { it.copy(trailUnit = u) }
                Log.w(TAG, "[$base] manual trailing stop unit set to $u")
                gson.toJson(mapOf("ok" to true, "unit" to u.name))
            } catch (e: Exception) {
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "could not save")))
            }
        }

        /**
         * The hand-set percentage TARGET for this market's watcher book.
         *
         * Zero switches it off. Separate from the trail's setter rather than an
         * extra argument on it, so that saving one can never silently rewrite the
         * other — they are set from the same panel but they are different
         * decisions, and a single call that carries both would overwrite a trail
         * with whatever the target field happened to contain at the time.
         */
        @JavascriptInterface
        fun manualSetTakeProfit(space: String, pct: Double): String {
            return try {
                val base = com.signalscope.app.trading.Spaces.baseOf(space.lowercase())
                // The upper bound is generous on purpose — a 400% target on a
                // small speculative position is a legitimate thing to want — but
                // it is bounded, because an unbounded one typed by mistake is a
                // target that can never be reached and therefore silently no
                // target at all.
                val clean = pct.coerceIn(0.0, 1000.0)
                com.signalscope.app.data.ManualWatchStore.mutate(applicationContext, base) {
                    it.copy(takeProfitPct = clean)
                }
                Log.w(TAG, "[$base] manual take profit set to " +
                    (if (clean <= 0.0) "OFF" else "+${"%.2f".format(clean)}% from your fill"))
                gson.toJson(mapOf("ok" to true, "pct" to clean))
            } catch (e: Exception) {
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "could not save")))
            }
        }

        /**
         * The minimum median daily turnover a name must trade to be screened in
         * this market. 0 switches the filter off.
         *
         * Takes effect on the next daily pass, and forces one by clearing the
         * stamp — a threshold you change and then cannot see the result of until
         * tomorrow is a control that feels broken.
         */
        @JavascriptInterface
        fun setMinTurnover(space: String, value: Double): String {
            return try {
                val base = com.signalscope.app.trading.Spaces.baseOf(space.lowercase())
                config.setMinTurnover(base, value)
                com.signalscope.app.data.ManualWatchStore.mutate(applicationContext, base) {
                    it.copy(lastDailyBuild = "")
                }
                Log.w(TAG, "[$base] liquidity floor set to ${"%,.0f".format(value)}")
                gson.toJson(mapOf("ok" to true, "value" to value))
            } catch (e: Exception) {
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "could not save")))
            }
        }

        /** Remove a resting buy trigger that has not fired. User tap only. */
        @JavascriptInterface
        fun manualCancelTrigger(space: String, symbol: String, signalBarTs: String) {
            thread(isDaemon = true, name = "cancel-trigger") {
                val out = try {
                    val r = com.signalscope.app.trading.ManualEntryWatcher.cancelTrigger(
                        applicationContext, space, symbol, signalBarTs.toLongOrNull() ?: 0L)
                    gson.toJson(mapOf("ok" to r.ok, "symbol" to r.symbol,
                        "error" to r.error, "note" to r.note))
                } catch (e: Exception) {
                    gson.toJson(mapOf("ok" to false, "symbol" to symbol,
                        "error" to (e.message ?: "cancel failed")))
                }
                invalidateManualWatcherState()
                deliverAiCallback("window.onCancelTrigger", out)
            }
        }

        /**
         * EVERYTHING THIS APP IS WAITING ON, in one list.
         *
         * These were scattered across three stores and only ever visible in the
         * panel that happened to own each one: a queued algo entry showed in the
         * algo card, a resting Indian trigger in the watcher book, a running
         * ladder nowhere at all. So "what has the app got outstanding right now"
         * had no answer, which is a bad property for the class of thing that can
         * spend money without being asked again.
         *
         * Ordered by how close each is to actually trading — a ladder working
         * right now first, an entry parked for a market that opens tomorrow last.
         */
        @JavascriptInterface
        fun pendingOrders(space: String): String {
            return try {
                val base = com.signalscope.app.trading.Spaces.baseOf(space.lowercase())
                val out = ArrayList<Map<String, Any?>>()

                // ── EACH ORDER: WHAT STARTED IT, AND WHAT IT IS WAITING FOR ─────
                // Conditions are listed as met / not met, so the list says at a
                // glance why something has not gone yet. Kept short on purpose.
                val nowMs = System.currentTimeMillis()
                val marketOpen = com.signalscope.app.trading.MarketHours.isRegularSession(base, nowMs)
                fun cond(ok: Boolean?, text: String, either: Boolean = false) =
                    mapOf("ok" to ok, "text" to text, "or" to either)
                fun px(x: Double) = "%.2f".format(x)
                fun fromNs(ns: String): String =
                    if (com.signalscope.app.trading.Spaces.bucketIdOf(ns) == com.signalscope.app.data.ManualWatchStore.ID)
                        "Your watchlist"
                    else "Algo · " + (try { com.signalscope.app.data.BucketStore.get(applicationContext, ns)?.name }
                                      catch (e: Exception) { null } ?: ns)
                fun note(n: String?): org.json.JSONObject? =
                    if (n.isNullOrBlank()) null else try { org.json.JSONObject(n) } catch (e: Exception) { null }
                // The RV gate's two ways to start, as two conditions joined by "or".
                fun rvConds(n: org.json.JSONObject?, buy: Boolean): List<Map<String, Any?>> {
                    if (n == null || !n.has("dir")) return emptyList()
                    val pos = n.optDouble("pos", -1.0)
                    val where = if (pos >= 0.0) " (${Math.round(pos * 100)}% up)" else " (range still too narrow)"
                    val dir = n.optString("dir")
                    val top = pos >= 1.0 - com.signalscope.app.trading.RvGate.TOP_ZONE
                    val peak = n.optDouble("peak", 0.0); val trough = n.optDouble("trough", 0.0)
                    return if (buy) listOf(
                        cond(pos in 0.0..com.signalscope.app.trading.RvGate.EDGE, "Low in today's range$where"),
                        cond(dir == "rising" && !top,
                            if (top) "Price rising — not counted near today's high" else "Price rising", either = true),
                        cond(false, if (trough > 0.0) "Dipped to ${px(trough)} — waiting for the turn up"
                                    else if (peak > 0.0) "A dip under ${px(peak)}, then a turn up"
                                    else "A dip, then a turn up", either = true))
                    else listOf(
                        cond(pos >= 1.0 - com.signalscope.app.trading.RvGate.EDGE, "High in today's range$where"),
                        cond(dir == "falling", "Price falling", either = true))
                }
                // The most a queued algo buy may pay — the line NEM ran past.
                fun ceilingFor(ns: String, signal: Double, atr: Double): Double = try {
                    val cfg = com.signalscope.app.data.StrategyLabStore.activeConfig(applicationContext, ns)
                    com.signalscope.app.trading.T212LiveMirror.entryCeiling(signal, cfg.exit.stopLossPct,
                        cfg.exit.stopLossEnabled, atr)
                } catch (e: Exception) { 0.0 }

                // Trading 212: ladders in flight, and entries queued for a moment
                // that has not arrived.
                if (base == "de" || base == "us") {
                    com.signalscope.app.data.T212LiveStore.load(applicationContext).trades
                        .filter {
                            com.signalscope.app.trading.Spaces.baseOf(it.ns) == base &&
                                (it.status == "PENDING" || it.status == "RETRY" || it.status == "EXITING" ||
                                    // a sale the RV gate is holding for a better moment
                                    (it.status == "OPEN" && it.rvSince > 0L &&
                                        it.error.startsWith("holding the sale") &&
                                        System.currentTimeMillis() - it.rvSince <
                                            com.signalscope.app.trading.RvGate.MAX_WAIT_MS + 3_600_000L))
                        }
                        .forEach { r ->
                            val rv = r.rvSince > 0L && r.error.contains(com.signalscope.app.trading.RvGate.NOTE)
                            val kind = when {
                                r.status == "PENDING" -> "buy ladder running"
                                r.status == "EXITING" -> "sell ladder running"
                                r.status == "OPEN" -> "sale held by the RV gate"
                                rv -> "buy held by the RV gate"
                                r.parkedForOpen -> "waiting for the market to open"
                                else -> "queued — waiting for its moment"
                            }
                            out.add(mapOf(
                                "id" to r.paperTradeId, "ns" to r.ns, "symbol" to r.symbol,
                                "kind" to kind, "status" to r.status,
                                "why" to r.error, "tries" to r.retryCount,
                                "dueAt" to (if (rv) r.rvSince + com.signalscope.app.trading.RvGate.MAX_WAIT_MS else r.retryAfter),
                                "since" to (if (rv) r.rvSince else r.buyAt),
                                "signalPrice" to r.paperEntryPrice, "amount" to r.amount,
                                // Only a queued row can be called off from here. A
                                // ladder mid-flight has real orders resting at the
                                // broker, and pretending otherwise would leave them
                                // there while the app claimed they were gone.
                                "cancellable" to (r.status == "RETRY"),
                                // So the sheet can offer "wait for the open" only
                                // where it would change something — on a row that
                                // is already holding for one, the button would be
                                // a control that does nothing.
                                "parked" to r.parkedForOpen,
                                "side" to (if (r.status == "EXITING" || r.status == "OPEN") "SELL" else "BUY"),
                                "from" to fromNs(r.ns),
                                "conds" to run {
                                    val n = note(r.rvNote)
                                    when (r.status) {
                                        "PENDING", "EXITING" -> listOf(cond(true, "Ladder working now"))
                                        "OPEN" -> listOf(cond(marketOpen, "Market open")) +
                                            (if (rv) rvConds(n, buy = false) else emptyList())
                                        else -> listOf(cond(marketOpen && !r.parkedForOpen, "Market open")) +
                                            (if (rv) rvConds(n, buy = true) else emptyList())
                                    }
                                },
                                "ceiling" to (if (r.status == "RETRY")
                                    ceilingFor(r.ns, r.paperEntryPrice, note(r.rvNote)?.optDouble("atr", 0.0) ?: 0.0)
                                    else 0.0),
                                "sortKey" to (if (r.status == "RETRY") 2 else 1)))
                        }
                }

                // YOUR OWN BUYS, IN FLIGHT — every space, not just India.
                //
                // This read the watcher's book for India only, so a patient buy
                // started from the German or US watchlist was invisible here: the
                // ladder was visibly working on screen while this panel said
                // "nothing outstanding". The one screen whose whole job is to
                // answer "what is the app doing with my money" was answering it
                // wrongly, for the orders the user starts by hand.
                //
                // The record exists and always did — the watcher writes it BEFORE
                // the ladder runs. This was simply not looking at it.
                if (base == "de" || base == "us") {
                    com.signalscope.app.data.ManualWatchStore.load(applicationContext, base).entries
                        .filter { it.status == "PENDING" }
                        .forEach { e ->
                            out.add(mapOf(
                                "id" to e.symbol, "ns" to "", "symbol" to e.symbol,
                                "kind" to "your buy — ladder running",
                                "status" to "PENDING", "side" to "BUY", "from" to "You · watchlist Buy",
                                "conds" to listOf(cond(true, "Ladder working now")),
                                "why" to "You started this from the watchlist. The ladder rests up to six " +
                                    "rungs, about four minutes, then reports a fill or gives up.",
                                "tries" to 0, "dueAt" to 0L, "since" to e.createdAt,
                                "signalPrice" to e.signalPrice, "amount" to e.requestedCash,
                                "cancellable" to false,
                                "sortKey" to 0))
                        }
                }

                // EVERY ORDER RESTING AT TRADING 212, AND WHAT PLACED IT — the
                // app's targets, safety stops and ladders, and anything placed in
                // Trading 212 itself. A buy holds cash back from the account until
                // it fills or is cancelled, and one placed outside the app was
                // invisible here. Read once a pass; a ladder still working its
                // order is listed above already.
                if (base == "de" || base == "us") {
                    val W = com.signalscope.app.trading.T212WaitingOrders
                    val snap = W.last(applicationContext)
                    val watchLadders = com.signalscope.app.data.ManualWatchStore.load(applicationContext, base)
                        .entries.filter { it.status == "PENDING" }.map { it.symbol.uppercase() }.toSet()
                    fun cur(c: String) = when (c.uppercase()) { "EUR" -> "€"; "USD" -> "$"; "GBP" -> "£"; else -> "$c " }
                    fun qty(q: Double) = if (q == Math.floor(q)) "%.0f".format(q)
                                         else "%.4f".format(q).trimEnd('0').trimEnd('.')
                    W.forSpace(applicationContext, base)
                        .filter { !it.ladderRunning && !(it.by == "app" && it.ownerNs.isBlank() &&
                            it.isBuy && it.symbol.uppercase() in watchLadders) }
                        .forEach { o ->
                            val acc = cur(snap.accountCurrency)
                            val price = when {
                                o.limitPrice > 0.0 && o.stopPrice > 0.0 ->
                                    "stop ${cur(o.currency)}${"%.2f".format(o.stopPrice)}, limit ${cur(o.currency)}${"%.2f".format(o.limitPrice)}"
                                o.limitPrice > 0.0 -> "${cur(o.currency)}${"%.2f".format(o.limitPrice)} limit"
                                o.stopPrice > 0.0 -> "${cur(o.currency)}${"%.2f".format(o.stopPrice)} stop"
                                else -> "market"
                            }
                            val what = (if (o.isBuy) "Buy " else "Sell ") + "${qty(o.qty)} at $price."
                            val cash = if (o.isBuy) " It holds $acc${"%.2f".format(o.held)} of your cash until it " +
                                "fills or is cancelled — money the algos cannot use meanwhile." else ""
                            out.add(mapOf(
                                "id" to o.id.toString(), "ns" to o.ownerNs, "symbol" to o.symbol,
                                "kind" to (if (o.isBuy) "buy" else "sell") + " order at Trading 212",
                                "status" to "BROKER", "side" to o.side,
                                "from" to W.label(o), "limit" to o.limitPrice, "stop" to o.stopPrice,
                                "otype" to o.type, "cur" to cur(o.currency),
                                "source" to o.source, "fromApp" to (o.by == "app"),
                                "label" to W.label(o),
                                "why" to ((if (o.name.isNotBlank()) "${o.name} · " else "") + what + cash +
                                    " " + W.managedBy(o)),
                                "tries" to 0, "dueAt" to 0L, "since" to o.createdAt,
                                "signalPrice" to 0.0, "amount" to o.held,
                                "held" to o.held, "heldCur" to acc, "readAt" to snap.at,
                                "brokerOrderId" to (if (W.cancellable(o)) o.id else 0L),
                                "cancellable" to false,
                                "sortKey" to (if (o.isBuy) 3 else 4)))
                        }
                    if (snap.error.isNotBlank() && snap.orders.none { it.space.equals(base, true) })
                        Log.w(TAG, "waiting orders: ${snap.error}")
                }

                // YOUR PATIENT ORDERS WAITING ON THE RV GATE.
                if (base == "de" || base == "us") {
                    com.signalscope.app.trading.RvManualQueue.waitingFor(applicationContext, base).forEach { o ->
                        val what = when (o.kind) {
                            com.signalscope.app.trading.RvManualQueue.Kind.T212_SELL -> "sell ${o.qty}"
                            com.signalscope.app.trading.RvManualQueue.Kind.ALGO_TP -> "take profit"
                            else -> "buy for ${"%.2f".format(o.cash)}"
                        }
                        out.add(mapOf(
                            "id" to o.id, "ns" to o.algoNs, "symbol" to o.symbol,
                            "kind" to "your ${if (o.isSell) "sale" else "buy"} held by the RV gate",
                            "status" to "RVMANUAL", "rvManualId" to o.id,
                            "side" to (if (o.isSell) "SELL" else "BUY"),
                            "from" to when (o.kind) {
                                com.signalscope.app.trading.RvManualQueue.Kind.WATCH_BUY -> "You · watchlist Buy"
                                com.signalscope.app.trading.RvManualQueue.Kind.ALGO_TP -> "You · take profit"
                                else -> "You · stock page"
                            },
                            "conds" to (listOf(cond(com.signalscope.app.trading.MarketHours.isRegularSession(o.ns, nowMs), "Market open")) +
                                rvConds(note(o.rvNote), buy = !o.isSell)),
                            "why" to "Your patient $what, waiting for a better moment: ${o.why}. " +
                                "It starts by itself — at the latest two trading hours after you asked.",
                            "tries" to 0, "dueAt" to com.signalscope.app.trading.RvManualQueue.startsBy(o),
                            "since" to o.createdAt, "signalPrice" to o.refPrice, "amount" to o.cash,
                            "ceiling" to (if (!o.isSell && o.capLimit > 0.0) o.capLimit else 0.0),
                            "cancellable" to false, "sortKey" to 0))
                    }
                    // …and those that ran past their price limit and are asking you.
                    com.signalscope.app.trading.RvManualQueue.askingFor(applicationContext, base).forEach { o ->
                        out.add(mapOf(
                            "id" to o.id, "ns" to o.algoNs, "symbol" to o.symbol,
                            "kind" to "your buy — waiting for your answer",
                            "status" to "RVASK", "rvManualId" to o.id, "side" to "BUY",
                            "from" to (if (o.kind == com.signalscope.app.trading.RvManualQueue.Kind.WATCH_BUY)
                                "You · watchlist Buy" else "You · stock page"),
                            "conds" to listOf(cond(false, "Price at or under your limit ${px(o.capLimit)} " +
                                "(you asked at ${px(o.capRef)})")),
                            "why" to "Nothing bought: ${o.why}. Buy anyway, or call it off. " +
                                "Unanswered, it lapses at the close.",
                            "tries" to 0, "dueAt" to 0L, "since" to o.askedAt, "amount" to o.cash,
                            "cancellable" to false, "sortKey" to -1))
                    }
                }

                // India: buy triggers resting at Zerodha, and orders it accepted
                // but has not reported filled.
                if (base == "in") {
                    com.signalscope.app.data.ManualWatchStore.load(applicationContext, base).entries
                        .filter { it.status == "PENDING" }
                        .forEach { e ->
                            val resting = e.gttId > 0L
                            out.add(mapOf(
                                "id" to e.symbol, "ns" to "", "symbol" to e.symbol,
                                "kind" to (if (resting) "trigger resting at Zerodha"
                                           else "order placed, fill not reported yet"),
                                "status" to "PENDING", "side" to "BUY", "from" to "Your watchlist",
                                "conds" to listOf(if (resting) cond(null, "Price reaches ₹${px(e.triggerPrice)}")
                                                  else cond(true, "Order at Zerodha")),
                                "why" to (e.note.ifBlank {
                                    if (resting) "waiting for the price to reach the trigger"
                                    else "waiting for Zerodha to report the fill" }),
                                "tries" to 0,
                                "dueAt" to 0L, "since" to e.createdAt,
                                "signalPrice" to e.triggerPrice, "amount" to e.requestedCash,
                                "signalBarTs" to e.signalBarTs,
                                "cancellable" to resting,
                                "sortKey" to (if (resting) 2 else 1)))
                        }
                }
                out.sortBy { (it["sortKey"] as? Int) ?: 9 }
                gson.toJson(mapOf("ok" to true, "space" to base, "rows" to out))
            } catch (e: Exception) {
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "could not read pending orders")))
            }
        }

        /**
         * ENTRIES THIS APP DECLINED RECENTLY, and why.
         *
         * A refusal is a decision the app made about your money without asking,
         * and it was only ever visible as a log line. Shown beside what is still
         * waiting because the two answer one question together: what is the app
         * doing, and what did it decide not to do.
         *
         * Bounded to the same window a queued entry may live, so this is a record
         * of the decisions still close enough to act on rather than a history.
         */
        @JavascriptInterface
        fun declinedEntries(space: String): String {
            return try {
                val base = com.signalscope.app.trading.Spaces.baseOf(space.lowercase())
                // The same weekday-time window a queued entry lives for, so a
                // Friday refusal is still here on Monday morning — when it can
                // actually be acted on.
                val nowMs = System.currentTimeMillis()
                // WHAT YOU NOW OWN, so a declined row cannot offer to buy it again.
                //
                // A refusal from yesterday says nothing about today's account. Left
                // unchecked this list happily offered "queue for the next open" on a
                // name bought since — arming a SECOND purchase of something already
                // held, from a button whose whole purpose is to overrule a refusal.
                // Nothing downstream would have caught it: the entry path has never
                // consulted holdings.
                val ownedNow = try {
                    val m = com.signalscope.app.trading.ManualEntryWatcher
                        .managedQtyFor(applicationContext, base)
                        .filterValues { it > 0.0 }.keys.toMutableSet()
                    when (val br = com.signalscope.app.trading.BrokerHoldings
                        .forSpace(applicationContext, base)) {
                        is com.signalscope.app.trading.BrokerHoldings.Result.Ok ->
                            br.holdings.filter { it.qty > 0.0 }.forEach { m.add(it.symbol.uppercase()) }
                        else -> Unit
                    }
                    m
                } catch (e: Exception) { emptySet<String>() }
                val rows = com.signalscope.app.data.T212LiveStore.load(applicationContext).trades
                    .filter {
                        com.signalscope.app.trading.Spaces.baseOf(it.ns) == base &&
                            it.status == "ERROR" && it.buyAt > 0L &&
                            !com.signalscope.app.trading.TradingClock.queueExpired(it.buyAt, nowMs, it.ns)
                    }
                    .sortedByDescending { it.buyAt }
                    .map {
                        mapOf("id" to it.paperTradeId, "ns" to it.ns, "symbol" to it.symbol,
                            "why" to it.error, "at" to it.buyAt,
                            "signalPrice" to it.paperEntryPrice, "amount" to it.amount,
                            "tries" to it.retryCount,
                            "heldNow" to (it.symbol.uppercase() in ownedNow))
                    }
                gson.toJson(mapOf("ok" to true, "rows" to rows))
            } catch (e: Exception) {
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "could not read declined entries")))
            }
        }

        /**
         * Put a declined entry back in the queue for the next open.
         *
         * ARMS A REAL BUY, and is reachable only from an explicit double-confirmed
         * tap. There is no automatic caller and there must not be one: the app
         * declined this trade for a stated reason, and overruling that reason is a
         * judgement about money that belongs to the person whose money it is.
         *
         * Nothing else is relaxed. It re-enters at the top of the same gates that
         * turned it away — the market must be open, the setup must still be live,
         * the price must sit inside its limits, and the last quarter hour must be
         * rising. Most re-queued entries will simply be declined again, and that
         * is the intended outcome rather than a failure of this button.
         *
         * The original decision time is KEPT, so the 48-hour age limit still
         * measures from when the strategy actually chose the trade. Re-queuing
         * cannot be used to keep an old decision alive indefinitely.
         */
        /** Does the account hold this name at all, by either measure. */
        private fun heldNow(symbol: String, base: String): Boolean = try {
            val u = symbol.uppercase()
            val managed = com.signalscope.app.trading.ManualEntryWatcher
                .managedQtyFor(applicationContext, base)[u] ?: 0.0
            val atBroker = when (val br = com.signalscope.app.trading.BrokerHoldings
                .forSpace(applicationContext, base)) {
                is com.signalscope.app.trading.BrokerHoldings.Result.Ok ->
                    br.holdings.any { it.symbol.equals(u, true) && it.qty > 0.0 }
                else -> false
            }
            managed > 0.0 || atBroker
        } catch (e: Exception) { false }

        @JavascriptInterface
        fun requeueDeclinedEntry(ns: String, paperTradeId: String) {
            thread(isDaemon = true, name = "requeue-entry") {
                val out = try {
                    val row = com.signalscope.app.data.T212LiveStore.get(applicationContext, paperTradeId, ns)
                    when {
                        row == null -> gson.toJson(mapOf("ok" to false,
                            "error" to "That entry is no longer in the book."))
                        row.status != "ERROR" -> gson.toJson(mapOf("ok" to false,
                            "error" to "This one is ${row.status}, not declined — nothing to re-queue."))
                        // LAST LINE OF DEFENCE, and the only one there is. Nothing
                        // further down the entry path has ever consulted holdings,
                        // so if this row were re-queued for a name bought since, a
                        // second position would be opened without a word.
                        heldNow(row.symbol, com.signalscope.app.trading.Spaces.baseOf(row.ns)) ->
                            gson.toJson(mapOf("ok" to false,
                                "error" to "You already hold ${row.symbol}. Re-queuing would buy it a " +
                                    "second time. Use the Buy button on the watchlist if adding to the " +
                                    "position is what you want."))
                        else -> {
                            com.signalscope.app.data.T212LiveStore.upsert(applicationContext,
                                row.copy(status = "RETRY", parkedForOpen = true,
                                    error = "re-queued by you — will be re-checked at the next open",
                                    retryAfter = System.currentTimeMillis()))
                            Log.w(TAG, "USER RE-QUEUED a declined entry: ${row.symbol} (${row.ns}) " +
                                "— previously: ${row.error}")
                            gson.toJson(mapOf("ok" to true, "symbol" to row.symbol,
                                "note" to "${row.symbol} is queued again. It faces the same gates at the " +
                                    "next open — if the price is still outside its limits it will be " +
                                    "declined again, for the same reason."))
                        }
                    }
                } catch (e: Exception) {
                    gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "re-queue failed")))
                }
                deliverAiCallback("window.onRequeueEntry", out)
            }
        }

        /**
         * Call off a QUEUED entry — one that has not reached the broker.
         *
         * Deliberately refuses anything else. A queued row is an intention this
         * app is holding; a running ladder has real orders resting at Trading
         * 212, and marking that cancelled here would leave them live while the
         * list claimed otherwise.
         */
        /**
         * Try a queued entry now rather than waiting for the next sweep.
         *
         * On its own thread and answered through a callback, because it runs a
         * real order ladder against the broker — up to tens of seconds — and
         * blocking the web view for that would look like the app had hung at the
         * exact moment it was spending money.
         */
        /** Stop retrying a queued entry until the market actually opens. */
        @JavascriptInterface
        fun parkQueuedForOpen(ns: String, paperTradeId: String) {
            thread(isDaemon = true, name = "park-queued") {
                val out = try {
                    val note = com.signalscope.app.trading.T212LiveMirror
                        .parkQueuedForOpen(applicationContext, ns, paperTradeId)
                    gson.toJson(mapOf("ok" to true, "note" to note))
                } catch (e: Exception) {
                    Log.e(TAG, "parkQueuedForOpen failed", e)
                    gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "could not park")))
                }
                deliverAiCallback("window.onParkQueued", out)
            }
        }

        @JavascriptInterface
        fun retryQueuedNow(ns: String, paperTradeId: String) {
            thread(isDaemon = true, name = "retry-queued") {
                val out = try {
                    val note = com.signalscope.app.trading.T212LiveMirror
                        .retryQueuedNow(applicationContext, ns, paperTradeId)
                    gson.toJson(mapOf("ok" to true, "note" to note))
                } catch (e: Exception) {
                    Log.e(TAG, "retryQueuedNow failed", e)
                    gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "retry failed")))
                }
                deliverAiCallback("window.onRetryQueued", out)
            }
        }

        @JavascriptInterface
        fun cancelQueuedEntry(ns: String, paperTradeId: String) {
            thread(isDaemon = true, name = "cancel-queued") {
                val out = try {
                    val row = com.signalscope.app.data.T212LiveStore.get(applicationContext, paperTradeId, ns)
                    when {
                        row == null -> gson.toJson(mapOf("ok" to false,
                            "error" to "That queued entry is no longer there."))
                        row.status != "RETRY" -> gson.toJson(mapOf("ok" to false,
                            "error" to "This one has already reached the broker (${row.status}). " +
                                "Cancel it in the Trading 212 app so the two cannot disagree."))
                        else -> {
                            com.signalscope.app.data.T212LiveStore.upsert(applicationContext,
                                row.copy(status = "ERROR", parkedForOpen = false,
                                    error = "cancelled by you before it reached the broker"))
                            Log.w(TAG, "queued entry cancelled by user: ${row.symbol} (${row.ns})")
                            gson.toJson(mapOf("ok" to true, "symbol" to row.symbol,
                                "note" to "${row.symbol} will not be bought. Nothing was placed, so nothing is cancelled at the broker."))
                        }
                    }
                } catch (e: Exception) {
                    gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "cancel failed")))
                }
                deliverAiCallback("window.onCancelQueued", out)
            }
        }

        /**
         * Move a holding the app never bought under the watcher's management.
         *
         * User tap only, and confirmed on the JS side before it gets here: after
         * this the strategy's exits SELL those shares rather than prompting about
         * them, which is a right the app should never acquire on its own.
         */
        @JavascriptInterface
        fun manualAdoptHolding(space: String, symbol: String) {
            thread(isDaemon = true, name = "adopt-holding") {
                val out = try {
                    val r = com.signalscope.app.trading.ManualEntryWatcher.adoptHolding(
                        applicationContext, space, symbol)
                    gson.toJson(mapOf("ok" to r.ok, "symbol" to r.symbol,
                        "qty" to r.filledQty, "price" to r.filledPrice,
                        "error" to r.error, "note" to r.note))
                } catch (e: Exception) {
                    gson.toJson(mapOf("ok" to false, "symbol" to symbol,
                        "error" to (e.message ?: "could not move this holding")))
                }
                invalidateManualWatcherState()
                deliverAiCallback("window.onAdoptHolding", out)
            }
        }

        /**
         * The squeeze verdict for every coiled name in a space.
         *
         * Returned as its own map rather than folded into the discovery payload:
         * it is refreshed once a day while that payload is rebuilt on every scan,
         * and merging them would rebuild several hundred rows to carry a field
         * that had not changed since morning.
         */
        @JavascriptInterface
        fun squeezeMap(space: String): String = try {
            val st = com.signalscope.app.data.SqueezeStore.load(applicationContext, space)
            gson.toJson(mapOf("day" to st.day, "unscored" to st.unscored,
                "rows" to st.rows.mapValues {
                    mapOf("tier" to it.value.tier, "days" to it.value.days,
                        "mom" to it.value.momentum, "fired" to it.value.fired,
                        "firedFrom" to it.value.firedFrom,
                        // The weekly reading travels with the daily one so the
                        // screen can switch clocks without another round trip.
                        "wTier" to it.value.wTier, "wDays" to it.value.wDays,
                        "wMom" to it.value.wMomentum, "wFired" to it.value.wFired,
                        "wFiredFrom" to it.value.wFiredFrom)
                }))
        } catch (e: Exception) {
            Log.e(TAG, "squeezeMap failed", e); "{\"rows\":{}}"
        }

        /**
         * Put a name on the watcher list by hand, or take it off.
         *
         * The ranking cannot nominate the stocks this is for — it sorts by
         * momentum and these are the ones that have gone quiet — so a pin skips
         * both the depth cut and the slope gate. It does not skip exposure, and
         * it does not force an entry: the config still decides that.
         */
        @JavascriptInterface
        fun watchPin(space: String, symbol: String): String = try {
            val added = com.signalscope.app.data.ManualWatchStore
                .pin(applicationContext, space, symbol)
            invalidateManualWatcherState()
            // Priced from the next pass, so a pinned name is not sitting on a
            // day-old figure the first time it is looked at.
            try { com.signalscope.app.trading.MarkToMarket.forget(symbol) } catch (_: Exception) {}
            gson.toJson(mapOf("ok" to added, "symbol" to symbol.uppercase(),
                "error" to (if (added) "" else
                    "Already on the list, or the ${com.signalscope.app.data.ManualWatchStore.MAX_PINNED}-name limit is full.")))
        } catch (e: Exception) {
            Log.e(TAG, "watchPin failed", e)
            gson.toJson(mapOf("ok" to false, "symbol" to symbol, "error" to (e.message ?: "failed")))
        }

        @JavascriptInterface
        fun watchUnpin(space: String, symbol: String): String = try {
            com.signalscope.app.data.ManualWatchStore.unpin(applicationContext, space, symbol)
            invalidateManualWatcherState()
            gson.toJson(mapOf("ok" to true, "symbol" to symbol.uppercase()))
        } catch (e: Exception) {
            gson.toJson(mapOf("ok" to false, "symbol" to symbol, "error" to (e.message ?: "failed")))
        }

        /**
         * Just the hand-added names for a space, plus whether a strategy is
         * attached to judge them.
         *
         * DELIBERATELY NOT [manualWatcherState], which is the obvious thing to
         * reach for and the wrong one: that walks every armed algo's gate, ledger
         * and broker rows to build the whole panel. A button that only needs to
         * know "is this name already on the list" would put that entire walk on
         * every repaint of the detail sheet, on the UI thread. This reads one
         * small file.
         */
        @JavascriptInterface
        fun watchPinnedList(space: String): String = try {
            val base = com.signalscope.app.trading.Spaces.baseOf(
                space.lowercase().let { if (it == "bn") "binance" else it })
            val st = com.signalscope.app.data.ManualWatchStore.load(applicationContext, base)
            val ns = com.signalscope.app.data.ManualWatchStore.nsFor(base)
            val configured = com.signalscope.app.data.StrategyLabStore
                .load(applicationContext, ns).configJson != null
            gson.toJson(mapOf(
                "ok" to true,
                "pinned" to st.pinned.map { it.uppercase() },
                "limit" to com.signalscope.app.data.ManualWatchStore.MAX_PINNED,
                "configured" to configured))
        } catch (e: Exception) {
            Log.e(TAG, "watchPinnedList failed", e)
            gson.toJson(mapOf("ok" to false, "pinned" to emptyList<String>(),
                "limit" to 0, "configured" to false, "error" to (e.message ?: "failed")))
        }

        /** Buy an Indian name from the detail sheet. An ordinary order where the
         *  broker allows one, a resting trigger where it does not — the reply says
         *  which, because the two mean very different things to the user. */
        @JavascriptInterface
        fun zerodhaDetailBuy(symbol: String, cash: Double) {
            thread(isDaemon = true, name = "detail-buy-in") {
                val out = try {
                    val r = com.signalscope.app.trading.ManualEntryWatcher.buyAndHandOver(
                        applicationContext, "in", symbol, cash, null, force = true)
                    gson.toJson(mapOf("ok" to r.ok, "symbol" to r.symbol,
                        "price" to r.signalPrice, "error" to r.error, "note" to r.note,
                        "immediate" to r.immediate, "side" to "buy",
                        "filled" to (r.filledQty > 0.0),
                        "filledQty" to r.filledQty, "filledPrice" to r.filledPrice))
                } catch (e: Exception) {
                    gson.toJson(mapOf("ok" to false, "symbol" to symbol,
                        "error" to (e.message ?: "buy failed")))
                }
                invalidateManualWatcherState()
                deliverAiCallback("window.onDetailTrade", out)
            }
        }

        /**
         * Buy a German or US name from the detail sheet AND HAND IT TO THAT
         * MARKET'S WATCHER, as the Indian button already does.
         *
         * This used to be a bare patient ladder: the shares arrived and nothing
         * owned them — no exit rule, no trailing stop, no resting 1-week-high
         * target — exactly like a stock bought in the broker's own app. The same
         * ladder still does the buying; the difference is that the position is
         * written into the watcher's book on the way out, so every pass after this
         * one treats it as the app's own.
         *
         * The page only calls this when the market has a watcher config; without
         * one there would be no rules to hand it to, and it keeps the bare ladder.
         */
        @JavascriptInterface
        fun t212DetailBuy(symbol: String, space: String, cash: Double) {
            thread(isDaemon = true, name = "detail-buy-t212") {
                val base = if (space.equals("US", true)) "us" else "de"
                val sym = symbol.trim().uppercase().removeSuffix(".DE")
                val out = try {
                    val r = com.signalscope.app.trading.ManualEntryWatcher.buyAndHandOver(
                        applicationContext, base, sym, cash, patientProgressSink(), force = true)
                    val filled = r.ok && r.filledQty > 0.0
                    val market = if (base == "us") "US" else "German"
                    postT212PatientNotice(r.symbol, "buy", filled, r.filledQty, 0.0,
                        detail = if (filled)
                            "Bought ${"%.4f".format(r.filledQty)}. Handed to your $market watchlist, " +
                                "which now manages the exit."
                        else "Nothing bought: ${r.error.ifBlank { r.note.ifBlank { "the ladder filled nothing" } }}")
                    gson.toJson(mapOf("ok" to r.ok, "symbol" to r.symbol,
                        "price" to r.signalPrice, "error" to r.error,
                        "note" to (if (filled)
                            "Handed to your $market watchlist: its exit rules and trailing stop now " +
                                "manage these shares, and a 1-week-high target goes in at Trading 212 " +
                                "once the fill price has come through. " + r.note
                            else r.note).trim(),
                        "immediate" to true, "side" to "buy", "filled" to filled,
                        "filledQty" to r.filledQty, "filledPrice" to r.filledPrice))
                } catch (e: Exception) {
                    Log.e(TAG, "t212DetailBuy failed", e)
                    gson.toJson(mapOf("ok" to false, "symbol" to sym,
                        "error" to (e.message ?: "buy failed")))
                }
                invalidateManualWatcherState()
                deliverAiCallback("window.onDetailTrade", out)
            }
        }

        /** Sell an Indian holding, the same two ways round. */
        @JavascriptInterface
        fun zerodhaDetailSell(symbol: String, qty: Int) {
            thread(isDaemon = true, name = "detail-sell-in") {
                val out = try {
                    val r = com.signalscope.app.trading.ZerodhaLiveMirror
                        .manualSell(applicationContext, symbol, qty)
                    // YOUR SALE, BROUGHT INTO THE ALGOS' BOOKS — see ZerodhaHandSales.
                    val handled = if (r.ok && r.filledQty > 0) try {
                        com.signalscope.app.trading.ZerodhaHandSales.afterAppSale(
                            applicationContext, symbol, r.filledQty, r.filledPrice, qty)
                    } catch (e: Exception) { Log.w(TAG, "Indian hand-sale booking failed", e); "" } else ""
                    gson.toJson(mapOf("ok" to r.ok, "symbol" to symbol,
                        "price" to r.price, "error" to r.error, "note" to r.note,
                        "immediate" to r.immediate, "side" to "sell",
                        "filled" to (r.filledQty > 0),
                        "filledQty" to r.filledQty, "filledPrice" to r.filledPrice,
                        "handled" to handled))
                } catch (e: Exception) {
                    gson.toJson(mapOf("ok" to false, "symbol" to symbol,
                        "error" to (e.message ?: "sell failed")))
                }
                deliverAiCallback("window.onDetailTrade", out)
            }
        }

        /** Force a paper evaluation of a bucket now. */
        @JavascriptInterface
        fun bucketRunNow(ns: String) {
            com.signalscope.app.trading.StrategyLabRunner.runAll(applicationContext, 0L, ns)
        }

        /**
         * Can this algo's portfolio layer be replayed at all? Reads local state
         * only (config, feed plan, bucket, gate) — no network — so the UI can warn
         * BEFORE starting a fetch that would take minutes and then fail anyway.
         * Returns {ok, warn, title, detail, fix}.
         */
        @JavascriptInterface
        fun bucketPortfolioFeasible(ns: String): String {
            return try {
                val f = com.signalscope.app.trading.StrategyLabRunner
                    .portfolioFeasibility(applicationContext, ns)
                gson.toJson(mapOf("ok" to f.ok, "warn" to f.warn,
                    "title" to f.title, "detail" to f.detail, "fix" to f.fix))
            } catch (e: Exception) {
                // Never block the action on a check failure — let the real run report.
                gson.toJson(mapOf("ok" to true, "warn" to false))
            }
        }

        /**
         * Export the PORTFOLIO layer: three joined CSVs (trades / enrollments /
         * equity) sharing one run_id. Distinct from bucketExportTrades, which dumps
         * the ENGINE layer — that file cannot show what 3b did, because 3b only
         * holds a name while it is enrolled and the engine replays every symbol
         * over its whole history.
         */
        @JavascriptInterface
        fun bucketExportPortfolio(ns: String) {
            thread(isDaemon = true) {
                val res = try {
                    com.signalscope.app.trading.StrategyLabRunner.portfolioCsv(applicationContext, ns)
                } catch (e: Exception) {
                    com.signalscope.app.trading.StrategyLabRunner.PortfolioCsv(
                        false, e.message ?: "export failed")
                }
                if (!res.ok) {
                    deliverAiCallback("window.onPortfolioCsv",
                        gson.toJson(mapOf("ok" to false, "note" to res.note)))
                    return@thread
                }
                try {
                    val uris = ArrayList<android.net.Uri>()
                    for (f in res.files) {
                        val file = java.io.File(cacheDir, f.name)
                        file.writeText(f.content)
                        uris.add(androidx.core.content.FileProvider.getUriForFile(
                            this@MainActivity, "$packageName.fileprovider", file))
                    }
                    runOnUiThread {
                        try {
                            val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                type = "text/csv"
                                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                putExtra(Intent.EXTRA_SUBJECT, res.runId)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(send, "Export portfolio CSVs"))
                        } catch (e: Exception) {
                            Log.e(TAG, "share portfolio csvs failed", e)
                        }
                    }
                    deliverAiCallback("window.onPortfolioCsv", gson.toJson(mapOf(
                        "ok" to true, "runId" to res.runId, "note" to res.note,
                        "files" to res.files.map { it.name },
                        "trades" to res.trades, "enrollments" to res.enrollments,
                        "equityDays" to res.equityDays, "universeSize" to res.universeSize,
                        "seats" to res.seats, "symbolsTraded" to res.symbolsTraded,
                        "neverTraded" to res.neverTraded)))
                } catch (e: Exception) {
                    deliverAiCallback("window.onPortfolioCsv", gson.toJson(mapOf(
                        "ok" to false, "note" to (e.message ?: "write failed"))))
                }
            }
        }

        /**
         * Export this algo's ENGINE-layer trades over the live window as a flat CSV.
         * Useful for signal-level debugging against Python, but it is NOT the
         * strategy: it replays each symbol independently over its full history, so
         * for a 3b algo most rows are trades the portfolio never took. Use
         * bucketExportPortfolio to audit what 3b actually did. → window.onTradesCsv.
         */
        /**
         * Export the LIVE ledger and its seat spans — what this phone actually did.
         *
         * The portfolio export next to it runs a fresh simulation and reads no live
         * state at all, so it cannot show a defect that exists only in the live
         * path. This one reads nothing but stored state, which is why it is the one
         * that can answer "did every trade sit inside its own seat's span".
         * → window.onLiveLedgerCsv.
         */
        @JavascriptInterface
        fun bucketExportLive(ns: String) {
            thread(isDaemon = true) {
                val res = try {
                    com.signalscope.app.trading.StrategyLabRunner.liveLedgerCsv(applicationContext, ns)
                } catch (e: Exception) {
                    com.signalscope.app.trading.StrategyLabRunner.LiveLedgerCsv(
                        false, e.message ?: "export failed")
                }
                if (!res.ok) {
                    deliverAiCallback("window.onLiveLedgerCsv",
                        gson.toJson(mapOf("ok" to false, "note" to res.note)))
                    return@thread
                }
                try {
                    val uris = ArrayList<android.net.Uri>()
                    for (f in res.files) {
                        val file = java.io.File(cacheDir, f.name)
                        file.writeText(f.content)
                        uris.add(androidx.core.content.FileProvider.getUriForFile(
                            this@MainActivity, "$packageName.fileprovider", file))
                    }
                    runOnUiThread {
                        try {
                            val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                type = "text/csv"
                                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                putExtra(Intent.EXTRA_SUBJECT, res.runId)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(send, "Export live ledger CSVs"))
                        } catch (e: Exception) {
                            Log.e(TAG, "share live ledger csvs failed", e)
                        }
                    }
                    deliverAiCallback("window.onLiveLedgerCsv", gson.toJson(mapOf(
                        "ok" to true, "note" to res.note, "runId" to res.runId,
                        "trades" to res.trades, "spans" to res.spans,
                        "orphans" to res.orphans, "unknown" to res.unknown,
                        "warmupViolations" to res.warmupViolations)))
                } catch (e: Exception) {
                    deliverAiCallback("window.onLiveLedgerCsv",
                        gson.toJson(mapOf("ok" to false, "note" to (e.message ?: "write failed"))))
                }
            }
        }

        @JavascriptInterface
        fun bucketExportTrades(ns: String) {
            thread(isDaemon = true) {
                val res = try {
                    com.signalscope.app.trading.StrategyLabRunner.tradesCsv(applicationContext, ns)
                } catch (e: Exception) {
                    com.signalscope.app.trading.StrategyLabRunner.TradesCsv(false, e.message ?: "export failed")
                }
                if (!res.ok) {
                    deliverAiCallback("window.onTradesCsv",
                        gson.toJson(mapOf("ok" to false, "note" to res.note)))
                    return@thread
                }
                try {
                    val f = java.io.File(cacheDir, res.fileName)
                    f.writeText(res.content)
                    runOnUiThread {
                        try {
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                this@MainActivity, "$packageName.fileprovider", f)
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_SUBJECT, res.fileName)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(send, "Export trades CSV"))
                        } catch (e: Exception) {
                            Log.e(TAG, "share trades csv failed", e)
                        }
                    }
                    deliverAiCallback("window.onTradesCsv", gson.toJson(mapOf(
                        "ok" to true, "trades" to res.trades, "symbols" to res.symbols,
                        "file" to res.fileName)))
                } catch (e: Exception) {
                    deliverAiCallback("window.onTradesCsv",
                        gson.toJson(mapOf("ok" to false, "note" to (e.message ?: "write failed"))))
                }
            }
        }

        /**
         * IMMEDIATE real-money disarm for ONE algo. Flips live=false and nothing
         * else, the moment the user unticks the box.
         *
         * Deliberately NOT routed through bucketConfigure: that path validates the
         * config (feed/warmup) and can REFUSE the save — which would silently leave
         * the algo ARMED after the user asked to disarm it. Same asymmetry as the
         * rest of the live mirror: reducing risk is never gated, adding risk is.
         * Exits are unaffected — an already-open real position can still be sold.
         */
        @JavascriptInterface
        fun bucketDisarmLive(ns: String): String {
            return try {
                val b = com.signalscope.app.data.BucketStore.get(applicationContext, ns)
                    ?: return "{\"ok\":false,\"error\":\"algo not found\"}"
                com.signalscope.app.data.BucketStore.update(applicationContext, b.copy(live = false))
                // Count against the RIGHT broker. This read Binance unconditionally,
                // so disarming a German algo would always report "0 open" — exactly
                // when knowing a real position is still out there matters most.
                val openLive = if (b.space == "binance")
                    com.signalscope.app.data.BinanceLiveStore
                        .load(applicationContext, ns).trades.count { it.status == "OPEN" }
                else com.signalscope.app.data.T212LiveStore.load(applicationContext)
                    .trades.count { it.ns == ns && (it.status == "OPEN" || it.status == "PENDING" || it.status == "EXITING") }
                Log.w(TAG, "[$ns] REAL orders DISARMED by user (open live positions: $openLive)")
                gson.toJson(mapOf("ok" to true, "openLive" to openLive))
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        /** "What would this algo have made?" — replays the CURRENT config over the
         *  live data window the engine already fetches (no CSV needed) and reports
         *  the net P&L plus the exact horizon it covers. Read-only.
         *  Result → window.onBucketHypothetical. */
        @JavascriptInterface
        fun bucketHypothetical(ns: String) {
            thread(isDaemon = true) {
                val out = try {
                    val b = com.signalscope.app.data.BucketStore.get(applicationContext, ns)
                    val cfg = com.signalscope.app.data.StrategyLabStore.activeConfig(applicationContext, ns)
                    val cap = b?.capital ?: cfg.notional
                    val r = com.signalscope.app.trading.StrategyLabRunner
                        .hypothetical(applicationContext, ns, cap)
                    // Keep the result so the algos comparison table can show it
                    // without re-running a multi-minute fetch per algo. Slippage and
                    // frames are stored WITH it — a return is not interpretable
                    // without the assumptions that produced it.
                    if (r.ok) try {
                        com.signalscope.app.data.WhatIfStore.put(applicationContext,
                            com.signalscope.app.data.WhatIfStore.Summary(
                                ns = ns, name = b?.name ?: ns,
                                computedAt = System.currentTimeMillis(),
                                layer = r.layer,
                                configSource = com.signalscope.app.data.StrategyLabStore
                                    .load(applicationContext, ns).configSource,
                                slippagePct = cfg.slippagePct,
                                entryTf = r.entryTf, exitTf = r.exitTf, interval = r.interval,
                                compoundedPct = r.compoundedPct, cagrPct = r.cagrPct,
                                maxDrawdownPct = r.maxDrawdownPct, spanDays = r.spanDays,
                                fromTs = r.fromTs, toTs = r.toTs,
                                closed = r.closed, symbols = r.symbols, seats = r.holdN,
                                note = r.note))
                    } catch (e: Exception) { Log.w(TAG, "what-if summary save failed", e) }
                    gson.toJson(r)
                } catch (e: Exception) {
                    gson.toJson(mapOf("ok" to false, "note" to (e.message ?: "what-if failed")))
                }
                deliverAiCallback("window.onBucketHypothetical", out)
            }
        }

        /**
         * EVERY COMPLETED ALGO ROUND TRIP AT TRADING 212: the strategy's prices
         * against what the broker filled. Built from the app's own records — no
         * network — so the closed-trades table can show it on every row without a
         * download. [space] "DE" / "US" keeps one market (by the instrument), null
         * keeps the whole account.
         *
         * The comparison is per ROUND TRIP, built from the reconciled weighted
         * averages — never from one rung of a ladder, which is not the trade's price
         * — and only once both legs are complete: a buy whose sell is still filling
         * has half a cost and would drag every average down.
         */
        private fun algoRoundTripLayer(space: String?): Map<String, Any?> {
            val ctx = applicationContext
            val sp = space?.uppercase()
            val mirrored = com.signalscope.app.data.T212LiveStore.load(ctx).trades.filter {
                sp == null || com.signalscope.app.trading.T212ClosedTrades.spaceOf(it.t212Ticker) == sp
            }
            val nameOf = HashMap<String, String>()
            fun algoName(ns: String): String {
                if (ns.isBlank()) return "—"
                return nameOf.getOrPut(ns) {
                    com.signalscope.app.data.BucketStore.get(ctx, ns)?.name
                        ?: if (com.signalscope.app.data.ManualWatchStore.ID ==
                                com.signalscope.app.trading.Spaces.bucketIdOf(ns)) "watchlist" else ns
                }
            }
            // The constant the paper ledger CHARGES ITSELF, once per closed round
            // trip — which is why the two legs are paired before measuring.
            val assumedFor = HashMap<String, Double>()
            fun assumedOf(ns: String): Double = assumedFor.getOrPut(ns) {
                try { com.signalscope.app.data.StrategyLabStore.activeConfig(ctx, ns).slippagePct }
                catch (e: Exception) { 0.0 }
            }
            fun day(ms: Long): String =
                if (ms > 0L) java.time.Instant.ofEpochMilli(ms).toString().take(10) else ""
            // THE PRICE THAT DECIDED EACH SALE, where the row never recorded it.
            // Most sales did not: the algo's exit is on its paper trade, and a
            // watchlist stop or target is written into the sale's own reason
            // ("trailing stop -1.50% from a high of 14.24"). Without it a trade had
            // a fill and nothing to measure it against, and drew no mark at all.
            val paperExits = HashMap<String, Map<String, Double>>()
            fun paperExitOf(ns: String, id: String): Double? = paperExits.getOrPut(ns) {
                try {
                    com.signalscope.app.data.StrategyLabStore.load(ctx, ns).trades
                        .mapNotNull { tr -> tr.exitPrice?.takeIf { it > 0.0 }?.let { tr.id to it } }.toMap()
                } catch (e: Exception) { emptyMap() }
            }[id]
            val trailRx = Regex("""trailing stop -([0-9.]+)% from a high of ([0-9.]+)""")
            val tpRx = Regex("""take profit \+([0-9.]+)% from a fill of ([0-9.]+)""")
            fun levelInReason(why: String?): Double? {
                if (why.isNullOrBlank()) return null
                trailRx.find(why)?.let { m ->
                    val p = m.groupValues[1].toDoubleOrNull(); val h = m.groupValues[2].toDoubleOrNull()
                    if (p != null && h != null && h > 0.0) return h * (1 - p / 100.0)
                }
                tpRx.find(why)?.let { m ->
                    val p = m.groupValues[1].toDoubleOrNull(); val f = m.groupValues[2].toDoubleOrNull()
                    if (p != null && f != null && f > 0.0) return f * (1 + p / 100.0)
                }
                return null
            }
            val watchBooks = HashMap<String, List<com.signalscope.app.data.ManualWatchStore.Entry>>()
            fun watchReason(t: com.signalscope.app.data.T212LiveStore.LiveTrade): String? {
                if (com.signalscope.app.trading.Spaces.bucketIdOf(t.ns) != com.signalscope.app.data.ManualWatchStore.ID) return null
                val base = com.signalscope.app.trading.Spaces.baseOf(t.ns)
                val book = watchBooks.getOrPut(base) {
                    try { com.signalscope.app.data.ManualWatchStore.load(ctx, base).entries } catch (e: Exception) { emptyList() }
                }
                return book.firstOrNull {
                    it.symbol.equals(t.symbol, true) && it.status == "CLOSED" &&
                        kotlin.math.abs(it.filledPrice - t.liveEntryPrice) < 0.006
                }?.exitReason
            }
            fun msOf(v: Long?): Long = when { v == null || v <= 0L -> 0L; v < 100_000_000_000L -> v * 1000L; else -> v }
            val roundTrips = mirrored.mapNotNull { t ->
                // THE BUY ALONE IS ENOUGH FOR A MARK. A sale with no decision price
                // or no recorded fill used to drop the whole trade, buy and all.
                if (!t.buyComplete()) return@mapNotNull null
                val pe = t.paperEntryPrice
                if (pe <= 0.0 || t.liveEntryPrice <= 0.0) return@mapNotNull null
                val closedRow = t.status == "CLOSED" || t.status == "MANUAL_CLOSED"
                if (!closedRow) return@mapNotNull null
                val px = (t.paperExitPrice?.takeIf { it > 0.0 }
                    ?: paperExitOf(t.ns, t.paperTradeId)
                    ?: levelInReason(t.exitReasonNote)
                    ?: levelInReason(watchReason(t))) ?: 0.0
                val liveOut = t.liveExitPrice.takeIf { it > 0.0 }
                    ?: t.manualExitPrice.takeIf { it > 0.0 } ?: 0.0
                val complete = t.sellComplete() && px > 0.0 && liveOut > 0.0
                val entrySlip = (t.liveEntryPrice - pe) / pe * 100.0   // + = paid more
                val exitSlip = if (px > 0.0 && liveOut > 0.0) (px - liveOut) / px * 100.0 else null  // + = sold lower
                val measured = if (exitSlip != null) entrySlip + exitSlip else null
                val assumed = assumedOf(t.ns)
                // THE ENTRY COST'S TWO CAUSES. Latency is the market moving between
                // the signal bar's close and the order going out — fixed by ticking
                // faster. Execution is the ladder's own cost from its anchor to the
                // fill — fixed by changing the ladder.
                val ref = t.refEntryPrice
                val latency = if (ref > 0.0) (ref - pe) / pe * 100.0 else null
                val exec = if (ref > 0.0) (t.liveEntryPrice - ref) / ref * 100.0 else null
                // A BENCH row has no config, so its "assumed" is the 0.05% default,
                // not what the algos charge — flagged so the panel can say so.
                val isBench = t.ns == com.signalscope.app.trading.LiveTestBench.NS
                val soldAt = listOf(t.sellAt, t.manualClosedAt, msOf(t.exitTs)).firstOrNull { it > 0L } ?: 0L
                mapOf("symbol" to t.symbol,
                    "algo" to (if (isBench) "test bench" else algoName(t.ns)),
                    "isBench" to isBench,
                    "complete" to complete,
                    "entrySlipPct" to entrySlip, "exitSlipPct" to exitSlip,
                    "measuredPct" to measured, "assumedPct" to assumed,
                    "deviationPct" to measured?.let { it - assumed },
                    "latencyPct" to latency, "execPct" to exec,
                    "rungs" to (t.allBuyIds().size + t.allSellIds().size),
                    "paperEntry" to pe, "liveEntry" to t.liveEntryPrice,
                    "paperExit" to px, "liveExit" to liveOut,
                    // When the two legs happened, so a closed-trade row (built from
                    // the broker's fills) can find the algo trade behind it.
                    "buyDay" to day(t.buyAt), "sellDay" to day(soldAt),
                    "qty" to t.ownExitQty(),
                    "closedAt" to (t.exitTs ?: 0L))
            }.sortedBy { it["closedAt"] as? Long ?: 0L }
            // Legs still filling — shown so a stuck ladder is visible rather than
            // just absent from the averages.
            val pendingLegs = mirrored.count {
                (it.allBuyIds().isNotEmpty() && !it.buyComplete()) ||
                (it.allSellIds().isNotEmpty() && !it.sellComplete())
            }
            // The summary line keeps to trades measured on BOTH legs — a buy-only
            // mark is shown in its row but would skew a round-trip average.
            val fullTrips = roundTrips.filter { it["complete"] == true }
            val rtMeasured = fullTrips.mapNotNull { it["measuredPct"] as? Double }.sorted()
            val rtAssumed = fullTrips.mapNotNull { it["assumedPct"] as? Double }
            // Every eligible algo's assumed constant, so the panel can state what is
            // being trusted even before a single real round trip exists.
            val assumedByAlgo = (com.signalscope.app.data.BucketStore.allNamespaces(ctx) + listOf("us", "de"))
                .filter { com.signalscope.app.trading.Spaces.baseOf(it).let { b ->
                    (b == "us" || b == "de") && (sp == null || b == sp.lowercase()) } }
                .distinct()
                .mapNotNull { n ->
                    val a = assumedOf(n)
                    if (a <= 0.0) null else mapOf("algo" to algoName(n), "ns" to n, "assumedPct" to a)
                }
            // A true median: with an even count, the two middle values averaged.
            fun medianOf(xs: List<Double>): Double? = when {
                xs.isEmpty() -> null
                xs.size % 2 == 1 -> xs[xs.size / 2]
                else -> (xs[xs.size / 2 - 1] + xs[xs.size / 2]) / 2.0
            }
            return mapOf(
                "roundTrips" to roundTrips,
                "rtCount" to fullTrips.size,
                "rtMeasuredMean" to (if (rtMeasured.isEmpty()) null else rtMeasured.average()),
                "rtMeasuredMedian" to medianOf(rtMeasured),
                "rtAssumedMean" to (if (rtAssumed.isEmpty()) null else rtAssumed.average()),
                "assumedByAlgo" to assumedByAlgo,
                "pendingLegs" to pendingLegs)
        }

        /** The algo trades behind this market's closed-trades table — local, instant. */
        @JavascriptInterface
        fun t212AlgoExecution(space: String): String = try {
            gson.toJson(mapOf("ok" to true) + algoRoundTripLayer(space))
        } catch (e: Exception) {
            gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "failed")))
        }

        /**
         * Recent REAL T212 fills, each attributed to the algo that placed it.
         *
         * ATTRIBUTION IS BY ORDER ID, captured at placement time: the mirror stores
         * the id T212 returned against (ns, paperTradeId), so matching is exact and
         * needs no guessing from symbol+timestamp — which would be ambiguous anyway,
         * since several German algos share one universe and can enter the same
         * ticker on the same bar. Anything with no stored id is a MANUAL order (or
         * one placed before this wiring existed) and is labelled as such rather
         * than being force-fitted to an algo.
         *
         * Doubles as the smoke test for fetchOrderHistory — if the endpoint path or
         * key scopes are wrong, this is where it says so.
         */
        @JavascriptInterface
        fun t212Fills(limit: Int): String {
            return try {
                val cfg = com.signalscope.app.data.ConfigManager(applicationContext)
                if (!cfg.hasT212Credentials)
                    return gson.toJson(mapOf("ok" to false,
                        "error" to "No T212 API key saved — add it in Settings first."))
                val client = com.signalscope.app.network.Trading212Client(cfg)
                val fills = when (val r = client.fetchOrderHistory(limit.coerceIn(1, 50))) {
                    is com.signalscope.app.network.Trading212Client.T212Result.Success -> r.data
                    is com.signalscope.app.network.Trading212Client.T212Result.Failure ->
                        return gson.toJson(mapOf("ok" to false, "error" to r.message))
                    else -> return gson.toJson(mapOf("ok" to false, "error" to "T212 not configured"))
                }
                val mirrored = com.signalscope.app.data.T212LiveStore.load(applicationContext).trades
                // orderId -> the algo row that placed it, plus which leg it was.
                // EVERY rung maps back, not just the first: a patient ladder's later
                // rungs would otherwise show up as "manual / unmatched" in this list
                // — the exact orders that cost the most, attributed to nobody.
                val byBuy = HashMap<Long, com.signalscope.app.data.T212LiveStore.LiveTrade>()
                val bySell = HashMap<Long, com.signalscope.app.data.T212LiveStore.LiveTrade>()
                mirrored.forEach { t ->
                    t.allBuyIds().forEach { byBuy[it] = t }
                    t.allSellIds().forEach { bySell[it] = t }
                }
                val nameOf = HashMap<String, String>()
                fun algoName(ns: String): String {
                    if (ns.isBlank()) return "—"
                    return nameOf.getOrPut(ns) {
                        com.signalscope.app.data.BucketStore.get(applicationContext, ns)?.name ?: ns
                    }
                }
                // The constant the paper ledger CHARGES ITSELF. StrategyLabEngine
                // subtracts slippagePct once per CLOSED ROUND TRIP, not per leg —
                // which is why the honest comparison below pairs the two legs before
                // measuring. Comparing it against a per-leg median would flatter the
                // strategy by exactly a factor of two.
                val assumedFor = HashMap<String, Double>()
                fun assumedOf(ns: String): Double = assumedFor.getOrPut(ns) {
                    try { com.signalscope.app.data.StrategyLabStore.activeConfig(applicationContext, ns).slippagePct }
                    catch (e: Exception) { 0.0 }
                }
                val rows = fills.map { f ->
                    val m = byBuy[f.orderId] ?: bySell[f.orderId]
                    val leg = if (byBuy.containsKey(f.orderId)) "ENTRY"
                              else if (bySell.containsKey(f.orderId)) "EXIT" else ""
                    val paper = when (leg) {
                        "ENTRY" -> m?.paperEntryPrice ?: 0.0
                        "EXIT" -> m?.paperExitPrice ?: 0.0
                        else -> 0.0
                    }
                    val legIds = when (leg) {
                        "ENTRY" -> m?.allBuyIds()
                        "EXIT" -> m?.allSellIds()
                        else -> null
                    }
                    // Signed so it reads the way a trader expects: POSITIVE = worse
                    // for you. Paid above the paper price on a buy, or sold below it
                    // on a sell. Both directions occur; the MEAN is the real cost.
                    val slipPct = if (paper > 0.0 && f.fillPrice > 0.0) {
                        if (leg == "EXIT") (paper - f.fillPrice) / paper * 100.0
                        else (f.fillPrice - paper) / paper * 100.0
                    } else null
                    // Price improvement vs the limit we placed: POSITIVE = the ladder
                    // got a better price than it asked for (bought below / sold above).
                    // This is the patient executor's own scorecard, independent of any
                    // paper comparison — it exists even for manual orders.
                    val impPct = if (f.limitPrice > 0.0 && f.fillPrice > 0.0) {
                        if (f.side == "SELL") (f.fillPrice - f.limitPrice) / f.limitPrice * 100.0
                        else (f.limitPrice - f.fillPrice) / f.limitPrice * 100.0
                    } else null
                    mapOf(
                        "orderId" to f.orderId, "ticker" to f.ticker, "side" to f.side,
                        "qty" to f.filledQuantity, "price" to f.fillPrice,
                        "limitPrice" to f.limitPrice, "improvedPct" to impPct,
                        "value" to f.filledValue, "time" to f.dateExecuted,
                        "status" to f.status,
                        "algo" to (m?.let { algoName(it.ns) } ?: "manual"),
                        "matched" to (m != null), "leg" to leg,
                        "symbol" to (m?.symbol ?: ""),
                        "paperPrice" to paper, "slipPct" to slipPct,
                        "assumedPct" to m?.let { assumedOf(it.ns) },
                        // WHICH RUNG of the ladder this order was. Without it a leg
                        // that filled across six orders reads as six unrelated trades
                        // in the same name, which is how a partial fill gets mistaken
                        // for over-trading.
                        "rungIdx" to legIds?.indexOf(f.orderId)?.takeIf { it >= 0 }?.plus(1),
                        "rungTotal" to legIds?.size)
                }
                // Nothing parsed → attach the raw payload. Otherwise "responded but
                // returned no executed orders" is indistinguishable from an empty
                // account, and there is no way to tell from the phone which it is.
                val rawDiag = if (rows.isEmpty()) {
                    when (val d = client.fetchOrderHistoryRaw(limit.coerceIn(1, 50))) {
                        is com.signalscope.app.network.Trading212Client.T212Result.Success -> d.data
                        is com.signalscope.app.network.Trading212Client.T212Result.Failure -> d.message
                        else -> null
                    }
                } else null
                val slips = rows.mapNotNull { it["slipPct"] as? Double }.sorted()
                // A true median: with an EVEN count, average the two middle values.
                // `sorted[size/2]` picks the upper one, so at 2 samples it reported
                // the worse fill and called it the median — the first bench run read
                // "median +0.297%" from [-0.215, +0.297], where the honest answer is
                // +0.041%. Small samples are exactly when this is most misleading.
                fun medianOf(xs: List<Double>): Double? = when {
                    xs.isEmpty() -> null
                    xs.size % 2 == 1 -> xs[xs.size / 2]
                    else -> (xs[xs.size / 2 - 1] + xs[xs.size / 2]) / 2.0
                }
                gson.toJson(mapOf("ok" to true, "fills" to rows,
                    "raw" to rawDiag,
                    "matchedCount" to rows.count { it["matched"] == true },
                    "slipSamples" to slips.size,
                    "slipMean" to (if (slips.isEmpty()) null else slips.average()),
                    "slipMedian" to medianOf(slips)) +
                    // Round-trip layer: what the config ASSUMED vs what the broker
                    // actually charged, per completed trade and in aggregate.
                    algoRoundTripLayer(null) + mapOf(
                    "drag" to com.signalscope.app.trading.SlippageStats.toMap(
                        com.signalscope.app.trading.SlippageStats.compute(
                            com.signalscope.app.trading.T212LiveMirror.slippageSamples(applicationContext)))))
            } catch (e: Exception) {
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "unknown")))
            }
        }

        /**
         * Ledger-vs-broker position reconciliation.
         *
         * [force] = true does the network round trip; false returns the cached
         * report so the banner can render on every repaint without hammering the
         * API (T212 rate-limits portfolio reads).
         */
        /**
         * COMPLETED ROUND TRIPS AND WHAT EACH ONE MADE.
         *
         * Trading 212 reports buys and sells as separate events and never pairs
         * them, so an account has no list of finished trades and no per-trade P&L.
         * This builds both from the executed-order history — real fills only, no
         * strategy opinion anywhere in it.
         *
         * [space] is "US" or "DE": one account, but each tab totals only its own
         * market's trades.
         *
         * [force] does the paged network walk; false returns the stored report so
         * the panel can open instantly and without touching a rate-limited
         * endpoint on every repaint.
         */
        /** Every buy order waiting at Trading 212, both tabs — for the cash card and
         *  the algos' cash check. Read from the pass's last look; no network. */
        @JavascriptInterface
        fun t212WaitingBuys(): String = try {
            val s = com.signalscope.app.trading.T212WaitingOrders.last(applicationContext)
            gson.toJson(mapOf("ok" to true, "at" to s.at, "error" to s.error,
                "currency" to s.accountCurrency,
                "orders" to s.orders.filter { !it.ladderRunning && it.isBuy }.map {
                    mapOf("id" to it.id, "symbol" to it.symbol, "space" to it.space,
                        "held" to it.held, "by" to it.by, "qty" to it.qty,
                        "limit" to it.limitPrice, "currency" to it.currency)
                }))
        } catch (e: Exception) { gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "unreadable"))) }

        /** A patient order with "Wait for the RV gate" ticked — see [RvManualQueue.submit]. */
        fun rvGateOrderJson(kind: String, argsJson: String): String = try {
            val o = rvOrderOf(kind, argsJson)
            val (queued, why, by) = com.signalscope.app.trading.RvManualQueue.submit(applicationContext, o)
            gson.toJson(mapOf("ok" to true, "queued" to queued, "why" to why, "startsBy" to by))
        } catch (e: Exception) {
            gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "the RV gate could not be asked")))
        }

        private fun rvOrderOf(kind: String, argsJson: String): com.signalscope.app.trading.RvManualQueue.Order {
            val a = com.google.gson.JsonParser.parseString(argsJson).asJsonObject
            fun s(k: String) = a.get(k)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            fun d(k: String) = try { a.get(k)?.asDouble ?: 0.0 } catch (_: Exception) { 0.0 }
            val space = s("space").uppercase().let { if (it == "DE") "DE" else "US" }
            return com.signalscope.app.trading.RvManualQueue.Order(
                id = "", kind = kind, space = space, symbol = s("symbol").uppercase().removeSuffix(".DE"),
                ticker = s("ticker"), yahooSymbol = s("yahooSymbol"), qty = d("qty"), cash = d("cash"),
                refPrice = d("refPrice"), force = (a.get("force")?.asBoolean ?: false), algoNs = s("algoNs"))
        }

        /**
         * A button order placed with the RV gate box UNticked. It has already run;
         * this only records where the price sat and what the gate would have said,
         * for the decisions list. Network, on its own thread; never in the way.
         */
        @JavascriptInterface
        fun rvGateNotAsked(kind: String, argsJson: String) {
            thread(isDaemon = true, name = "rv-not-asked") {
                try { com.signalscope.app.trading.RvManualQueue.notAsked(applicationContext, rvOrderOf(kind, argsJson)) }
                catch (e: Exception) { Log.w("MainActivity", "rv not-asked record failed: ${e.message}") }
            }
        }

        /** The RV gate's decisions on your button orders in [space], last three days. No network. */
        @JavascriptInterface
        fun rvGateLog(space: String): String = try {
            val since = System.currentTimeMillis() - 3 * 24 * 3_600_000L
            gson.toJson(mapOf("ok" to true,
                "rows" to com.signalscope.app.trading.RvManualQueue.history(applicationContext, space, since)))
        } catch (e: Exception) { gson.toJson(mapOf("ok" to false, "rows" to emptyList<Any>())) }

        /** You said buy anyway to a waiting buy that stopped at its price limit. The
         *  ladder runs in the background; the result arrives as a notification. */
        @JavascriptInterface
        fun rvManualBuyAnyway(id: String) {
            thread(isDaemon = true, name = "rv-buy-anyway") {
                val msg = try { com.signalscope.app.trading.RvManualQueue.buyAnyway(applicationContext, id) }
                          catch (e: Exception) { "failed: ${e.message}" }
                deliverAiCallback("window.onRvBuyAnyway", gson.toJson(mapOf("note" to msg)))
            }
        }

        /** Call off one of your orders waiting on the RV gate. */
        @JavascriptInterface
        fun rvManualCancel(id: String): String = gson.toJson(mapOf(
            "ok" to com.signalscope.app.trading.RvManualQueue.cancel(applicationContext, id)))

        /** The stocks you hold in [space], from the last broker read — for the page's
         *  underline. Crypto: the coins the algos hold for real. No network. */
        @JavascriptInterface
        fun heldSymbols(space: String): String = try {
            val base = com.signalscope.app.trading.Spaces.baseOf(space.lowercase())
            val syms = if (base == "binance") {
                applicationContext.filesDir.list().orEmpty()
                    .filter { it.startsWith("binance_live_mirror") && it.endsWith(".json") }
                    .map { if (it == "binance_live_mirror.json") "binance"
                           else it.removePrefix("binance_live_mirror_").removeSuffix(".json") }
                    .distinct()
                    .flatMap { ns -> com.signalscope.app.data.BinanceLiveStore.load(applicationContext, ns).trades
                        .filter { it.status == "OPEN" }.map { it.symbol.uppercase() } }
                    .distinct()
            } else com.signalscope.app.trading.BrokerHoldings.heldSymbols(applicationContext, base)
            gson.toJson(syms)
        } catch (e: Exception) { "[]" }

        /** Price feeds judged stopped ("CRZK.DE") — the value floor leaves them out. No network. */
        @JavascriptInterface
        fun stoppedFeeds(): String = try {
            gson.toJson(com.signalscope.app.network.FeedHealth.stoppedSymbols())
        } catch (e: Exception) { "[]" }

        /** When the automatic Discovery scan for [space] next runs, as epoch ms; "0" = none. */
        @JavascriptInterface
        fun discoveryNextAuto(space: String): String = try {
            val market = when (space.uppercase()) {
                "IN" -> "nifty500"; "US" -> "nasdaq100"; "DE" -> "dax"; else -> null
            }
            (market?.let { com.signalscope.app.service.DiscoveryAutoScheduler.nextRunAt(applicationContext, it) } ?: 0L).toString()
        } catch (e: Exception) { "0" }

        @JavascriptInterface
        fun t212ClosedTrades(force: Boolean, space: String): String {
            return try {
                val r = if (force) com.signalscope.app.trading.T212ClosedTrades.run(applicationContext, space)
                        else com.signalscope.app.trading.T212ClosedTrades.last(applicationContext, space)
                gson.toJson(mapOf(
                    "ok" to r.ok, "error" to r.error, "checkedAt" to r.checkedAt,
                    "fillsSeen" to r.fillsSeen, "currency" to r.currency,
                    "totalPnl" to r.totalPnl, "wins" to r.wins, "losses" to r.losses,
                    "stillOpen" to r.stillOpen, "unmatchedSells" to r.unmatchedSells,
                    "totalCharges" to r.totalCharges, "costed" to r.costed,
                    "trips" to r.trips.map { t ->
                        mapOf("ticker" to t.ticker, "symbol" to t.symbol, "qty" to t.qty,
                            "buyPrice" to t.buyPrice, "sellPrice" to t.sellPrice,
                            "boughtAt" to t.boughtAt, "soldAt" to t.soldAt,
                            "cashIn" to t.cashIn, "cashOut" to t.cashOut,
                            "pnl" to t.pnl, "pctReturn" to t.pctReturn,
                            "heldDays" to t.heldDays,
                            "charges" to t.charges, "netPnl" to t.netPnl,
                            "netPctReturn" to t.netPctReturn,
                            "chargeParts" to t.chargeParts, "sameDay" to t.sameDay)
                    }))
            } catch (e: Exception) {
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "unknown")))
            }
        }

        /**
         * The same analysis for the Indian account.
         *
         * SAME KEYS as the Trading 212 one on purpose, so a single panel renders
         * both and the two can never drift into showing different things. The one
         * extra field is [knownSince], because this side genuinely cannot see
         * further back than the day the app started capturing — Kite's trade list
         * holds the current day only — and a total that hides its own horizon is
         * the kind of number people act on wrongly.
         */
        @JavascriptInterface
        fun zerodhaClosedTrades(force: Boolean): String {
            return try {
                val r = if (force) com.signalscope.app.trading.ZerodhaClosedTrades.run(applicationContext, true)
                        else com.signalscope.app.trading.ZerodhaClosedTrades.last(applicationContext)
                gson.toJson(mapOf(
                    "ok" to r.ok, "error" to r.error, "checkedAt" to r.checkedAt,
                    "fillsSeen" to r.fillsSeen, "currency" to r.currency,
                    "totalPnl" to r.totalPnl, "wins" to r.wins, "losses" to r.losses,
                    "stillOpen" to r.stillOpen, "unmatchedSells" to r.unmatchedSells,
                    "knownSince" to com.signalscope.app.trading.ZerodhaClosedTrades
                        .knownSince(applicationContext),
                    "totalCharges" to r.totalCharges, "costed" to r.costed,
                    "trips" to r.trips.map { t ->
                        mapOf("ticker" to t.ticker, "symbol" to t.symbol, "qty" to t.qty,
                            "buyPrice" to t.buyPrice, "sellPrice" to t.sellPrice,
                            "boughtAt" to t.boughtAt, "soldAt" to t.soldAt,
                            "cashIn" to t.cashIn, "cashOut" to t.cashOut,
                            "pnl" to t.pnl, "pctReturn" to t.pctReturn,
                            "heldDays" to t.heldDays,
                            // Costed from THIS trip's own two legs, not from a
                            // per-seat average — the charges on a trade that sold
                            // higher than it bought are genuinely larger.
                            "charges" to t.charges, "netPnl" to t.netPnl,
                            "netPctReturn" to t.netPctReturn,
                            // What the charges are made of, and whether the
                            // trade was same-day — the panel's breakdown and its
                            // intraday mark read these.
                            "chargeParts" to t.chargeParts, "sameDay" to t.sameDay)
                    }))
            } catch (e: Exception) {
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "unknown")))
            }
        }

        /**
         * The same analysis for crypto: the algos' real Binance round trips. Local
         * records only — nothing to fetch — so [force] is accepted for a uniform
         * panel and ignored. Fees are estimated at Binance's standard rate; see
         * [com.signalscope.app.trading.BinanceClosedTrades].
         */
        @JavascriptInterface
        fun binanceClosedTrades(force: Boolean): String {
            return try {
                val r = com.signalscope.app.trading.BinanceClosedTrades.report(applicationContext)
                gson.toJson(mapOf(
                    "ok" to r.ok, "error" to r.error, "checkedAt" to r.checkedAt,
                    "fillsSeen" to r.fillsSeen, "currency" to r.currency,
                    "totalPnl" to r.totalPnl, "wins" to r.wins, "losses" to r.losses,
                    "stillOpen" to r.stillOpen, "unmatchedSells" to r.unmatchedSells,
                    "totalCharges" to r.totalCharges, "costed" to r.costed,
                    "feesEstimated" to true,
                    "trips" to r.trips.map { t ->
                        mapOf("ticker" to t.ticker, "symbol" to t.symbol, "qty" to t.qty,
                            "buyPrice" to t.buyPrice, "sellPrice" to t.sellPrice,
                            "boughtAt" to t.boughtAt, "soldAt" to t.soldAt,
                            "cashIn" to t.cashIn, "cashOut" to t.cashOut,
                            "pnl" to t.pnl, "pctReturn" to t.pctReturn,
                            "heldDays" to t.heldDays,
                            "charges" to t.charges, "netPnl" to t.netPnl,
                            "netPctReturn" to t.netPctReturn,
                            "chargeParts" to t.chargeParts, "sameDay" to t.sameDay)
                    }))
            } catch (e: Exception) {
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "unknown")))
            }
        }

        @JavascriptInterface
        fun t212PositionAudit(force: Boolean): String {
            return try {
                val r = if (force) com.signalscope.app.trading.T212PositionAudit.run(applicationContext)
                        else com.signalscope.app.trading.T212PositionAudit.last(applicationContext)
                gson.toJson(mapOf(
                    "ok" to r.ok, "error" to r.error, "checkedAt" to r.checkedAt,
                    "positionsSeen" to r.positionsSeen, "rowsChecked" to r.rowsChecked,
                    "criticalCount" to r.critical.size,
                    "drifts" to r.drifts.map { d ->
                        mapOf("kind" to d.kind, "ticker" to d.ticker, "symbol" to d.symbol,
                            // WHOSE POSITION IS THIS. The whole point of the row is to
                            // send you somewhere to fix it, so an internal namespace is
                            // a dead end — the bench's own id read as a nonexistent algo
                            // and sent the reader hunting through six cards for a trade
                            // no algo had. Resolve to the name shown on the card, and
                            // name the bench explicitly as not-an-algo.
                            "algos" to d.algos.map { n ->
                                when {
                                    n == com.signalscope.app.trading.LiveTestBench.NS ->
                                        "the test bench (not an algo)"
                                    else -> com.signalscope.app.data.BucketStore
                                        .get(applicationContext, n)?.name
                                        ?: "a deleted algo"
                                }
                            },
                            // Lets the page route the fix: bench rows are cleared from
                            // the bench panel, algo rows from the algo's own card.
                            "isBench" to d.algos.any {
                                it == com.signalscope.app.trading.LiveTestBench.NS },
                            // THE RAW NAMESPACES, so the row can ACT and not merely
                            // point. Only display names were sent, which meant the
                            // banner could name the owner but had no way to call
                            // anything — and its instruction ("open that algo and
                            // mark it sold by hand") becomes impossible to follow
                            // the moment the algo's paper book is reset, because
                            // that control only exists on an open paper row.
                            "nsList" to d.algos,
                            "expectedQty" to d.expectedQty, "actualQty" to d.actualQty,
                            "deltaQty" to d.deltaQty, "actualValue" to d.actualValue)
                    }))
            } catch (e: Exception) {
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "unknown")))
            }
        }

        /**
         * LIVE TEST BENCH — fire ONE small real trade through the algos' own
         * execution path and watch every link in the chain.
         *
         * Runs on a background thread and reports through the SAME
         * window.onT212PatientProgress channel the Portfolio ladder uses, so the
         * existing progress renderer works unchanged. Blocking: a six-rung ladder
         * dwells per rung, so this can take minutes — which is exactly why it must
         * never be called from a tick.
         */
        @JavascriptInterface
        fun benchBuy(symbol: String, space: String, amount: Double): String {
            if (!benchBusy.compareAndSet(false, true)) return gson.toJson(mapOf("ok" to false,
                "error" to "A bench order is already running. One at a time — that is the point."))
            thread(isDaemon = true) {
                val out = try {
                    com.signalscope.app.trading.LiveTestBench.buy(
                        applicationContext, symbol, space, amount, patientProgressSink())
                } catch (e: Exception) {
                    com.signalscope.app.trading.LiveTestBench.Outcome(
                        false, "execute", e.message ?: "unknown")
                } finally { benchBusy.set(false) }
                deliverAiCallback("window.onBenchResult", gson.toJson(benchOut(out)))
            }
            return gson.toJson(mapOf("ok" to true, "started" to true))
        }

        @JavascriptInterface
        fun benchSell(paperTradeId: String, space: String): String {
            if (!benchBusy.compareAndSet(false, true)) return gson.toJson(mapOf("ok" to false,
                "error" to "A bench order is already running."))
            thread(isDaemon = true) {
                val out = try {
                    com.signalscope.app.trading.LiveTestBench.sell(
                        applicationContext, paperTradeId, space, patientProgressSink())
                } catch (e: Exception) {
                    com.signalscope.app.trading.LiveTestBench.Outcome(
                        false, "execute", e.message ?: "unknown")
                } finally { benchBusy.set(false) }
                deliverAiCallback("window.onBenchResult", gson.toJson(benchOut(out)))
            }
            return gson.toJson(mapOf("ok" to true, "started" to true))
        }

        private fun benchOut(o: com.signalscope.app.trading.LiveTestBench.Outcome) = mapOf(
            "ok" to o.ok, "stage" to o.stage, "error" to o.error,
            "ticker" to o.ticker, "symbol" to o.symbol, "side" to o.side,
            "refPrice" to o.refPrice, "quotePrice" to o.quotePrice,
            "filled" to o.filled, "remaining" to o.remaining,
            "rungs" to o.orderIds.size, "atr" to o.atr, "note" to o.note,
            "id" to o.paperTradeId, "anchorSource" to o.anchorSource)

        /**
         * Every bench trade with its full price chain resolved. This is the table
         * that answers "does the app's book match reality" line by line: what the
         * decision saw, what the ladder anchored on, what actually filled, how many
         * shares, over how many rungs.
         */
        @JavascriptInterface
        fun benchRows(): String {
            return try {
                val rows = com.signalscope.app.trading.LiveTestBench.rows(applicationContext)
                gson.toJson(mapOf("ok" to true, "busy" to benchBusy.get(),
                    "rows" to rows.map { t ->
                        val entryChain = if (t.paperEntryPrice > 0.0 && t.liveEntryPrice > 0.0)
                            (t.liveEntryPrice - t.paperEntryPrice) / t.paperEntryPrice * 100.0 else null
                        val exitChain = if ((t.paperExitPrice ?: 0.0) > 0.0 && t.liveExitPrice > 0.0)
                            (t.paperExitPrice!! - t.liveExitPrice) / t.paperExitPrice!! * 100.0 else null
                        mapOf(
                            "id" to t.paperTradeId, "symbol" to t.symbol, "ticker" to t.t212Ticker,
                            "status" to t.status, "entryTs" to t.entryTs, "exitTs" to t.exitTs,
                            "signalEntry" to t.paperEntryPrice, "anchorEntry" to t.refEntryPrice,
                            "fillEntry" to t.liveEntryPrice,
                            "signalExit" to (t.paperExitPrice ?: 0.0), "anchorExit" to t.refExitPrice,
                            "fillExit" to t.liveExitPrice,
                            "qtyOrdered" to t.qty, "qtyFilled" to t.liveQtyFilled,
                            "qtyExited" to t.liveExitQty,
                            "buyRungs" to t.allBuyIds().size, "buyRungsSeen" to t.buyRungsSeen,
                            "sellRungs" to t.allSellIds().size, "sellRungsSeen" to t.sellRungsSeen,
                            "entryValue" to t.liveEntryValue, "exitValue" to t.liveExitValue,
                            "entrySlipPct" to entryChain, "exitSlipPct" to exitChain,
                            // What an exit would actually sell, and how much of the
                            // pooled T212 position belongs to someone else. Shown
                            // before the tap, because "sell it back" silently
                            // liquidating a shared holding is not recoverable.
                            // Real cash the exit brought in from shares this row never
                            // owned. Shown on its own line so it can never be mistaken
                            // for strategy return.
                            "sweptQty" to t.sweptQty,
                            "sweptValue" to t.sweptValue(),
                            "sellQty" to (if (t.liveQtyFilled > 0.0) t.liveQtyFilled else t.qty),
                            "otherClaims" to com.signalscope.app.data.T212LiveStore.otherClaimsQty(
                                applicationContext, t.t212Ticker,
                                com.signalscope.app.trading.LiveTestBench.NS, t.paperTradeId),
                            "error" to t.error)
                    }))
            } catch (e: Exception) {
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "unknown")))
            }
        }

        /** Read fills for bench rows on demand — the bench is used interactively
         *  and should not wait for the hourly tick to learn what it paid. */
        @JavascriptInterface
        fun benchReconcile(): String {
            return try {
                val n = com.signalscope.app.trading.T212LiveMirror.reconcileFills(applicationContext)
                val audit = com.signalscope.app.trading.T212PositionAudit.run(applicationContext)
                gson.toJson(mapOf("ok" to true, "updated" to n,
                    "auditOk" to audit.ok, "criticalCount" to audit.critical.size,
                    "auditError" to audit.error))
            } catch (e: Exception) {
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "unknown")))
            }
        }

        /**
         * WILL THE ACCOUNT COVER WHAT THE ARMED ALGOS CAN SPEND?
         *
         * Worst case, not expected case: every armed algo fills every seat it has
         * left to fill, all at once. That is not a pessimistic invention — the gate
         * evaluates all seats in one pass, so a day when several names turn eligible
         * together is exactly when the whole commitment lands at once, and it is
         * also the day a shortfall is most expensive: the orders that fail are the
         * ones the strategy most wanted.
         *
         *   commitment = Σ each armed algo's budget
         *   deployed   = what its open live positions already cost
         *   still to spend = commitment − deployed
         *
         * Compared against free cash. Pure local read — the balance was cached by
         * the reconciliation pass, which is already on the network.
         */
        @JavascriptInterface
        fun t212CashCommitment(): String {
            return try {
                val rows = com.signalscope.app.data.T212LiveStore.load(applicationContext).trades
                var commitment = 0.0
                var deployed = 0.0
                var armed = 0
                val perAlgo = ArrayList<Map<String, Any?>>()
                for (n in com.signalscope.app.data.BucketStore.allNamespaces(applicationContext)) {
                    if (com.signalscope.app.trading.Spaces.baseOf(n).let { it != "de" && it != "us" }) continue
                    val b = com.signalscope.app.data.BucketStore.get(applicationContext, n) ?: continue
                    if (!b.live || b.liveBudget <= 0.0 || b.liveAllowlist.isEmpty()) continue
                    armed++
                    val spent = rows.filter { it.ns == n && it.status in setOf("OPEN", "PENDING", "EXITING") }
                        .sumOf { it.liveEntryValue }
                    commitment += b.liveBudget
                    deployed += spent
                    perAlgo.add(mapOf("name" to b.name, "budget" to b.liveBudget,
                        "deployed" to spent, "remaining" to (b.liveBudget - spent).coerceAtLeast(0.0)))
                }
                val stillToSpend = (commitment - deployed).coerceAtLeast(0.0)
                val free = config.t212FreeFundsCached
                val at = config.t212FreeFundsAt
                // A negative cache means "never read" — report unknown rather than
                // a shortfall, because a false alarm here trains you to ignore it.
                val known = free >= 0.0 && at > 0L
                gson.toJson(mapOf("ok" to true,
                    "known" to known, "free" to free, "checkedAt" to at,
                    "commitment" to commitment, "deployed" to deployed,
                    "stillToSpend" to stillToSpend,
                    "shortfall" to (if (known) (stillToSpend - free).coerceAtLeast(0.0) else 0.0),
                    "armed" to armed, "frozen" to false,
                    "currency" to (if (com.signalscope.app.trading.Spaces.baseOf(
                            bucketBaseNs("DE")) == "de") "EUR" else "EUR"),
                    "algos" to perAlgo))
            } catch (e: Exception) {
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "unknown")))
            }
        }

        /** India's four gates, same shape as the T212 strip. */
        @JavascriptInterface
        fun zerodhaLiveGates(): String {
            return try {
                val armed = com.signalscope.app.trading.ZerodhaLiveMirror
                    .armedAlgos(applicationContext).map { (n, name) ->
                        val b = com.signalscope.app.data.BucketStore.get(applicationContext, n)
                        mapOf("ns" to n, "name" to name,
                            "budget" to (b?.liveBudget ?: 0.0),
                            "names" to (b?.liveAllowlist?.size ?: 0))
                    }
                gson.toJson(mapOf("ok" to true,
                    "frozen" to false,
                    "mirrorEnabled" to config.isZerodhaConnected,
                    "configured" to config.isZerodhaConnected,
                    "armedAlgos" to armed,
                    "canTrade" to (config.canMirrorZerodhaLive && armed.isNotEmpty())))
            } catch (e: Exception) {
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "unknown")))
            }
        }

        /** Flip India's master freeze. [frozen] is the state to END UP IN. */
        @JavascriptInterface
        fun zerodhaSetLiveFrozen(frozen: Boolean): String {
            return try {
                // The master freeze no longer exists — arming is per algo now,
                // so this reports that rather than pretending to flip something.
                Log.w(TAG, "Zerodha freeze toggle called ($frozen); the master freeze was removed")
                gson.toJson(mapOf("ok" to false, "frozen" to false,
                    "error" to "The master freeze is gone — arm or disarm the algo itself."))
            } catch (e: Exception) {
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "unknown")))
            }
        }

        /**
         * The T212 live master switch, and everything that gates it.
         *
         * Returns all four gates so the UI can say exactly WHY nothing would trade,
         * rather than showing one toggle that silently does nothing because a
         * different gate is shut.
         */
        /**
         * The currency-conversion cost for the CURRENT space, so the Algos tab can
         * show it rather than leaving it buried in the arithmetic. An unmodelled
         * cost is exactly what makes a backtest flatter itself, and one that is
         * modelled but invisible is only marginally better.
         */
        @JavascriptInterface
        fun fxCostInfo(space: String): String {
            return try {
                val ns = when (space.uppercase()) {
                    "DE" -> "de"; "BN" -> "binance"; "IN" -> "in"; else -> "us"
                }
                val cur = com.signalscope.app.trading.Spaces.currencyOf(ns)
                val acct = config.accountCurrency
                val per = config.fxConversionPct
                val applies = !cur.equals(acct, true)
                gson.toJson(mapOf("ok" to true, "instrumentCurrency" to cur,
                    "accountCurrency" to acct, "perConversionPct" to per,
                    "roundTripPct" to (if (applies) 2.0 * per else 0.0),
                    "applies" to applies))
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "failed").replace("\"","")}\"}"
            }
        }

        /** Set the per-conversion FX charge (percent). Round trip costs double it. */
        @JavascriptInterface
        fun setFxConversionPct(pct: Double): String {
            return try {
                config.fxConversionPct = pct.coerceIn(0.0, 5.0)
                invalidateManualWatcherState()
                "{\"ok\":true,\"pct\":${config.fxConversionPct}}"
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "failed").replace("\"","")}\"}"
            }
        }

        /** Turn the unfilled-entry retry queue on or off. Opt-in: it places real
         *  orders on a later pass than the one that decided them. */
        @JavascriptInterface
        fun t212SetRetryUnfilled(on: Boolean): String {
            return try {
                config.t212RetryUnfilledEntries = on
                "{\"ok\":true,\"on\":$on}"
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "failed").replace("\"","")}\"}"
            }
        }

        @JavascriptInterface
        fun t212LiveGates(): String {
            return try {
                val armed = com.signalscope.app.data.BucketStore.allNamespaces(applicationContext)
                    .filter { com.signalscope.app.trading.Spaces.baseOf(it).let { b -> b == "de" || b == "us" } }
                    .mapNotNull { n ->
                        val b = com.signalscope.app.data.BucketStore.get(applicationContext, n) ?: return@mapNotNull null
                        if (!b.live) null else mapOf(
                            "ns" to n, "name" to b.name, "budget" to b.liveBudget,
                            // Gson can hand back null for a list written before the
                            // field existed — see the gson-defaults hazard.
                            "allowlist" to (b.liveAllowlist ?: emptyList<String>()))
                    }
                val queued = com.signalscope.app.data.T212LiveStore.load(applicationContext)
                    .trades.filter { it.status == "RETRY" }
                gson.toJson(mapOf("ok" to true,
                    "frozen" to false,
                    "mirrorEnabled" to config.hasT212Credentials,
                    "configured" to config.hasT212Credentials,
                    "armedAlgos" to armed,
                    "retryUnfilled" to config.t212RetryUnfilledEntries,
                    "queuedRetries" to queued.map {
                        mapOf("symbol" to it.symbol, "ns" to it.ns, "tries" to it.retryCount,
                              "why" to it.error)
                    },
                    "canTrade" to (config.canMirrorT212Live && armed.isNotEmpty())))
            } catch (e: Exception) {
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "unknown")))
            }
        }

        /** Flip the master freeze. Called only from an explicit double-confirmed tap. */
        @JavascriptInterface
        fun t212SetLiveFrozen(frozen: Boolean): String {
            return try {
                // The master freeze no longer exists — arming is per algo now,
                // so this reports that rather than pretending to flip something.
                Log.w(TAG, "T212 freeze toggle called ($frozen); the master freeze was removed")
                gson.toJson(mapOf("ok" to false, "frozen" to false,
                    "error" to "The master freeze is gone — arm or disarm the algo itself."))
            } catch (e: Exception) {
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "unknown")))
            }
        }

        /**
         * THE DUST LEDGER — where sub-€1 remainders came from and went.
         *
         * Two different quantities, deliberately not netted into one:
         *
         *  • SWEPT   — dust an algo's exit cleared. Real cash into the account,
         *              excluded from strategy P&L because it was never bought.
         *  • CREATED — the algo's OWN shares its exit failed to sell. This is the
         *              diagnostic one: an algo that keeps creating residue has an
         *              execution problem (chase too tight, dwell too short, names too
         *              illiquid), and that shows up here long before it shows up as
         *              a stranded position.
         *
         * Netting them would hide exactly the algo worth investigating — one that
         * sweeps a lot AND creates a lot nets to zero and looks perfectly healthy.
         */
        @JavascriptInterface
        fun t212DustLedger(): String {
            return try {
                val rows = com.signalscope.app.data.T212LiveStore.load(applicationContext).trades
                val byNs = rows.groupBy { it.ns }
                val perAlgo = byNs.mapNotNull { (ns, list) ->
                    val sweptQty = list.sumOf { it.sweptQty }
                    val sweptVal = list.sumOf { it.sweptValue() }
                    val madeQty = list.sumOf { it.ownResidueQty() }
                    val madeVal = list.sumOf { it.ownResidueValue() }
                    if (sweptQty <= 0.0 && madeQty <= 0.0) return@mapNotNull null
                    mapOf(
                        "ns" to ns,
                        "name" to (if (ns == com.signalscope.app.trading.LiveTestBench.NS) "test bench"
                                   else com.signalscope.app.data.BucketStore.get(applicationContext, ns)?.name ?: ns),
                        "sweptQty" to sweptQty, "sweptValue" to sweptVal,
                        "sweptCount" to list.count { it.sweptQty > 0.0 },
                        "createdQty" to madeQty, "createdValue" to madeVal,
                        "createdCount" to list.count { it.ownResidueQty() > 0.0 })
                }.sortedByDescending { (it["createdValue"] as? Double ?: 0.0) + (it["sweptValue"] as? Double ?: 0.0) }
                gson.toJson(mapOf("ok" to true,
                    "perAlgo" to perAlgo,
                    "totalSweptValue" to rows.sumOf { it.sweptValue() },
                    "totalSweptCount" to rows.count { it.sweptQty > 0.0 },
                    "totalCreatedValue" to rows.sumOf { it.ownResidueValue() },
                    "totalCreatedCount" to rows.count { it.ownResidueQty() > 0.0 }))
            } catch (e: Exception) {
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "unknown")))
            }
        }

        /**
         * THE REAL BOOK for ONE algo — what the broker actually did, per algo.
         *
         * The third of three layers the Algos tab reports, and until now the only one
         * with no per-algo home: BOOK (paper ledger) and REPLAY (💡) were both on the
         * card, while REAL lived exclusively in a global dialog. A blended execution
         * figure across six algos hides the one executing badly, which is the whole
         * reason to measure it.
         *
         * Reads ONLY the local store — no network. Fills are backfilled by the tick
         * and by the bench's explicit refresh, so this can be called on every render.
         */
        @JavascriptInterface
        /**
         * The same panel, read from Zerodha's ledger.
         *
         * Reports what that ledger can actually support and no more. Round-trip
         * deviation needs a real buy price AND a real sell price; a rejected order
         * has neither, so the honest answer for this account today is the error
         * list — which is the whole point, because those errors were invisible.
         */
        private fun zerodhaRealBook(ns: String): String = try {
            val rows = com.signalscope.app.data.ZerodhaLiveStore.load(applicationContext)
                .trades.filter { it.ns == ns }
            val done = rows.filter { it.status == "CLOSED" && !it.isManual }
            val spent = done.sumOf { it.entryPrice * it.qty }
            val got = done.sumOf { it.exitPrice * it.qty }
            // Entry-leg deviation: what the decision saw against what was paid.
            // Available the moment one order fills, rather than at the exit.
            val entryBars = rows.filter {
                it.isLive && it.paperEntryPrice > 0.0 && it.entryPrice > 0.0
            }.map {
                mapOf("symbol" to it.symbol,
                    "entrySlipPct" to (it.entryPrice - it.paperEntryPrice) / it.paperEntryPrice * 100.0)
            }
            val assumed = com.signalscope.app.data.StrategyLabStore
                .activeConfig(applicationContext, ns)
                .let { it.slippagePct + it.flatFeeRoundTripPct }
            gson.toJson(mapOf("ok" to true,
                "openNow" to rows.count { it.isLive },
                "closed" to done.size,
                "pending" to rows.count { it.status == "PENDING" },
                "errors" to rows.count { it.status == "ERROR" },
                "errorRows" to rows.filter { it.status == "ERROR" }
                    .sortedByDescending { maxOf(it.buyAt, it.sellAt) }
                    .take(8)
                    .map { mapOf("symbol" to it.symbol, "error" to it.error,
                        "at" to maxOf(it.buyAt, it.sellAt)) },
                "cashIn" to spent, "cashOut" to got,
                "cashPnl" to (got - spent),
                "cashPnlPct" to (if (spent > 0.0) (got / spent - 1.0) * 100.0 else 0.0),
                "slipSamples" to 0, "slipMedian" to null,
                "assumedPct" to assumed, "deviationPp" to null,
                "bars" to emptyList<Any>(), "entryBars" to entryBars,
                "yardsticks" to emptyList<Any>(),
                "manualCount" to rows.count { it.isManual }))
        } catch (e: Exception) {
            Log.e(TAG, "zerodhaRealBook failed", e)
            gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "failed")))
        }

        fun bucketRealBook(ns: String): String {
            // INDIA KEEPS ITS REAL ORDERS IN A DIFFERENT LEDGER, and this read only
            // ever looked in Trading 212's. So an Indian algo's card said "no broker
            // orders from this algo yet" while the Zerodha ledger held four rejected
            // orders explaining exactly why nothing had been bought. The switch was
            // armed, the broker had refused every order, and the one panel whose job
            // is to say so was reading an empty drawer.
            if (com.signalscope.app.trading.Spaces.baseOf(ns) == "in")
                return zerodhaRealBook(ns)
            return try {
                val rows = com.signalscope.app.data.T212LiveStore.load(applicationContext)
                    .trades.filter { it.ns == ns }
                val openNow = rows.count { it.status == "OPEN" || it.status == "PENDING" || it.status == "EXITING" }
                // Only fully reconciled round trips can be measured. A leg still
                // filling contributes nothing rather than a fraction of itself.
                // Strategy-executed round trips only. Hand-closed ones are real cash
                // but not the algo's doing, so they are reported on their own line
                // rather than folded into its performance or its execution stats.
                val done = rows.filter {
                    it.status == "CLOSED" && !it.isManual() && it.buyComplete() && it.sellComplete()
                }
                val manual = rows.filter { it.isManual() }
                // ── ENTRY-ONLY DEVIATION, for positions still open ────────────────
                // Half a round trip is still a real measurement, and it is available
                // MINUTES after the first live buy instead of days later when the
                // strategy happens to exit. It answers "did the ladder fill near the
                // signal price?" — which is the first thing worth knowing when an
                // algo goes live, and the last thing you want to wait for.
                //
                // Reported separately and NEVER against the config constant: that
                // constant is charged once per COMPLETED trade, so holding an entry
                // leg against it would flatter the algo by roughly half.
                val openPriced = rows.filter {
                    (it.status == "OPEN" || it.status == "EXITING") && !it.isManual() &&
                    it.buyComplete() && it.paperEntryPrice > 0.0 && it.liveEntryPrice > 0.0
                }
                val entryBars = openPriced.map { t ->
                    mapOf("symbol" to t.symbol,
                        "entrySlipPct" to (t.liveEntryPrice - t.paperEntryPrice) / t.paperEntryPrice * 100.0,
                        "signal" to t.paperEntryPrice, "fill" to t.liveEntryPrice,
                        "anchor" to t.refEntryPrice)
                }
                val entrySlips = entryBars.mapNotNull { it["entrySlipPct"] as? Double }.sorted()
                val manualPnl = manual.sumOf { t ->
                    val px = t.manualExitPrice
                    val q = if (t.manualExitQty > 0.0) t.manualExitQty else t.liveQtyFilled
                    if (px > 0.0 && t.liveEntryPrice > 0.0 && q > 0.0) (px - t.liveEntryPrice) * q else 0.0
                }
                val spent = done.sumOf { it.liveEntryValue }
                val got = done.sumOf { it.ownExitValue() }
                val slips = done.mapNotNull { t ->
                    val pe = t.paperEntryPrice; val px = t.paperExitPrice ?: 0.0
                    if (pe <= 0.0 || px <= 0.0 || t.liveEntryPrice <= 0.0 || t.liveExitPrice <= 0.0) null
                    else (t.liveEntryPrice - pe) / pe * 100.0 + (px - t.liveExitPrice) / px * 100.0
                }.sorted()
                val med = when {
                    slips.isEmpty() -> null
                    slips.size % 2 == 1 -> slips[slips.size / 2]
                    else -> (slips[slips.size / 2 - 1] + slips[slips.size / 2]) / 2.0
                }
                val assumed = try {
                    com.signalscope.app.data.StrategyLabStore.activeConfig(applicationContext, ns).slippagePct
                } catch (e: Exception) { 0.0 }
                // EVERY ALGO'S YARDSTICK, not just this one's.
                //
                // The bench belongs to no algo, so its "assumed" figure is whatever
                // the DEFAULT config happens to carry — 0.050%. Two of the German
                // algos really do use 0.050% and the rest use 0.200%, so a single
                // comparison silently picks one camp's yardstick and reports the
                // answer as if it were universal.
                //
                // What the bench measures is a property of the VENUE and the ladder,
                // not of any strategy: the same fill is legitimately judged against
                // every algo's own assumption. So ship them all and let the panel
                // show one measurement scored against each constant — which is also
                // the only way "my algos assume different slippage" becomes visible
                // rather than being averaged into a number describing nobody.
                val yardsticks = (com.signalscope.app.data.BucketStore
                        .allNamespaces(applicationContext) + listOf("de", "us"))
                    .filter { com.signalscope.app.trading.Spaces.baseOf(it)
                        .let { b -> b == "de" || b == "us" } }
                    .distinct()
                    .mapNotNull { n ->
                        val nm = com.signalscope.app.data.BucketStore
                            .get(applicationContext, n)?.name ?: return@mapNotNull null
                        val a = try {
                            com.signalscope.app.data.StrategyLabStore
                                .activeConfig(applicationContext, n).slippagePct
                        } catch (e: Exception) { 0.0 }
                        if (a <= 0.0) null else mapOf(
                            "algo" to nm, "ns" to n, "assumedPct" to a,
                            "deviationPp" to (if (med == null) null else med - a))
                    }
                    .sortedBy { it["assumedPct"] as? Double ?: 0.0 }
                gson.toJson(mapOf("ok" to true,
                    "yardsticks" to yardsticks,
                    "openNow" to openNow,
                    "closed" to done.size,
                    "pending" to rows.count { it.allBuyIds().isNotEmpty() && !it.buyComplete() },
                    "errors" to rows.count { it.status == "ERROR" },
                    // THE MESSAGE, not just the count. "2 error" told you something
                    // went wrong and nothing about what — while the broker's own
                    // explanation (no cash, ladder unfilled, ticker unknown) was
                    // sitting on the row all along, readable only from a log.
                    "errorRows" to rows.filter { it.status == "ERROR" }
                        .sortedByDescending { maxOf(it.buyAt, it.sellAt) }
                        .take(6)
                        .map { mapOf(
                            "symbol" to it.symbol,
                            "error" to it.error,
                            "at" to maxOf(it.buyAt, it.sellAt)) },
                    "cashIn" to spent, "cashOut" to got,
                    "cashPnl" to (got - spent),
                    "cashPnlPct" to (if (spent > 0.0) (got / spent - 1.0) * 100.0 else 0.0),
                    "slipSamples" to slips.size,
                    "slipMedian" to med,
                    "assumedPct" to assumed,
                    "deviationPp" to (if (med == null) null else med - assumed),
                    "bars" to done.mapNotNull { t ->
                        val pe = t.paperEntryPrice; val px = t.paperExitPrice ?: 0.0
                        if (pe <= 0.0 || px <= 0.0 || t.liveEntryPrice <= 0.0 || t.liveExitPrice <= 0.0) null
                        else mapOf("symbol" to t.symbol,
                            "measuredPct" to ((t.liveEntryPrice - pe) / pe * 100.0 +
                                              (px - t.liveExitPrice) / px * 100.0),
                            "assumedPct" to assumed)
                    },
                    "entryBars" to entryBars,
                    "entryMedian" to when {
                        entrySlips.isEmpty() -> null
                        entrySlips.size % 2 == 1 -> entrySlips[entrySlips.size / 2]
                        else -> (entrySlips[entrySlips.size / 2 - 1] + entrySlips[entrySlips.size / 2]) / 2.0
                    },
                    "manualCount" to manual.size,
                    "manualPnl" to manualPnl,
                    "manualPriced" to manual.count { it.manualExitPrice > 0.0 },
                    "manualSymbols" to manual.map { it.symbol }.distinct(),
                    "sweptValue" to rows.sumOf { it.sweptValue() },
                    "createdValue" to rows.sumOf { it.ownResidueValue() },
                    "lastSyncAt" to (rows.maxOfOrNull { it.fillsSyncedAt } ?: 0L)))
            } catch (e: Exception) {
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "unknown")))
            }
        }

        /**
         * "I sold that one myself." Explicit, user-confirmed only — see
         * [com.signalscope.app.trading.ManualIntervention] for why it is never
         * inferred from a missing share.
         */
        @JavascriptInterface
        fun algoMarkSoldByHand(ns: String, symbol: String): String {
            return try {
                val r = com.signalscope.app.trading.ManualIntervention
                    .markSoldByHand(applicationContext, ns, symbol)
                gson.toJson(mapOf("ok" to r.ok, "error" to r.error, "symbol" to r.symbol,
                    "paperRemoved" to r.paperRemoved, "discardedPnlPct" to r.discardedPnlPct,
                    "seatFreed" to r.seatFreed, "liveMarked" to r.liveMarked,
                    "matchedFill" to r.matchedFill, "exitPrice" to r.exitPrice,
                    "exitQty" to r.exitQty, "realPnl" to r.realPnl))
            } catch (e: Exception) {
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "unknown")))
            }
        }

        /** Stop the in-flight replay at the next symbol boundary. */
        @JavascriptInterface
        fun bucketCancelWhatIf(): String {
            return try {
                com.signalscope.app.trading.StrategyLabRunner.cancelWhatIf()
                gson.toJson(mapOf("ok" to true))
            } catch (e: Exception) {
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "unknown")))
            }
        }

        /**
         * Rename an algo. Cosmetic only — the namespace (and therefore every
         * ledger, gate state and saved result keyed by it) is deliberately left
         * alone, so renaming can never orphan history or disturb a live position.
         */
        @JavascriptInterface
        fun bucketRename(ns: String, name: String): String {
            return try {
                val clean = name.trim().take(40)
                if (clean.isBlank())
                    return gson.toJson(mapOf("ok" to false, "error" to "Name cannot be empty."))
                val b = com.signalscope.app.data.BucketStore.get(applicationContext, ns)
                    ?: return gson.toJson(mapOf("ok" to false, "error" to "Algo not found."))
                com.signalscope.app.data.BucketStore.update(applicationContext, b.copy(name = clean))
                // Keep the saved what-if label in step, or the comparison table
                // would keep showing the old name against the same numbers.
                try {
                    com.signalscope.app.data.WhatIfStore.get(applicationContext, ns)?.let {
                        com.signalscope.app.data.WhatIfStore.put(applicationContext, it.copy(name = clean))
                    }
                } catch (e: Exception) { Log.w(TAG, "what-if rename sync failed", e) }
                gson.toJson(mapOf("ok" to true, "name" to clean))
            } catch (e: Exception) {
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "rename failed")))
            }
        }

        /** Saved what-if results for every algo in a space, for the comparison
         *  table. Pure read of stored summaries — no fetching, no recompute. */
        @JavascriptInterface
        fun whatIfSummaries(space: String): String {
            return try {
                val base = bucketBaseNs(space)
                val saved = com.signalscope.app.data.WhatIfStore.all(applicationContext)
                val rows = com.signalscope.app.data.BucketStore.list(applicationContext, base).map { b ->
                    val cfg = com.signalscope.app.data.StrategyLabStore.activeConfig(applicationContext, b.ns)
                    val s = saved[b.ns]
                    mapOf(
                        "ns" to b.ns, "name" to b.name, "mode" to b.mode.name,
                        "configSource" to com.signalscope.app.data.StrategyLabStore
                            .load(applicationContext, b.ns).configSource,
                        // Always live from the CURRENT config, so a changed slippage
                        // is visible even against a stale saved result.
                        "slippageNow" to cfg.slippagePct,
                        "has" to (s != null),
                        "computedAt" to (s?.computedAt ?: 0L),
                        "layer" to (s?.layer ?: ""),
                        "slippagePct" to (s?.slippagePct ?: cfg.slippagePct),
                        "entryTf" to (s?.entryTf ?: cfg.resample),
                        "exitTf" to (s?.exitTf ?: cfg.exitBars),
                        "interval" to (s?.interval ?: ""),
                        "compoundedPct" to (s?.compoundedPct ?: 0.0),
                        "cagrPct" to (s?.cagrPct ?: 0.0),
                        "maxDrawdownPct" to (s?.maxDrawdownPct ?: 0.0),
                        "spanDays" to (s?.spanDays ?: 0.0),
                        "fromTs" to (s?.fromTs ?: 0L), "toTs" to (s?.toTs ?: 0L),
                        "closed" to (s?.closed ?: 0), "symbols" to (s?.symbols ?: 0),
                        "seats" to (s?.seats ?: 0))
                }
                gson.toJson(mapOf("ok" to true, "rows" to rows))
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        /** The 3b gate's current state for the Algos panel: the N capital slots,
         *  the bench (eligible + ranked but unheld) and the blocked names (ranked
         *  but slope ≤ 0). Pure read of the LAST daily evaluation — no refetch,
         *  no recompute, so opening the panel is free. */
        @JavascriptInterface
        fun bucketGate(ns: String): String {
            return try {
                val st = com.signalscope.app.data.MacdGateStore.load(applicationContext, ns)
                if (!st.enabled || st.slots.isEmpty())
                    return "{\"ok\":true,\"enabled\":false}"
                val slopes = st.lastSlopes
                val held = st.slots.mapNotNull { it.symbol?.uppercase() }.toSet()
                val rankOf = HashMap<String, Int>()
                st.lastRanking.forEachIndexed { i, s -> rankOf[s.uppercase()] = i + 1 }
                // Null for any ranking computed before scores were kept — the page
                // simply omits the column rather than printing a fabricated zero.
                val scoreByUpper = st.lastScores?.entries
                    ?.associate { it.key.uppercase() to it.value } ?: emptyMap()
                val slots = st.slots.map { sl ->
                    val u = sl.symbol?.uppercase()
                    mapOf("symbol" to sl.symbol, "capital" to sl.capital,
                        "flagged" to sl.flagged, "negDays" to sl.negDays, "since" to sl.since,
                        "rank" to (u?.let { rankOf[it] } ?: 0),
                        "score" to u?.let { scoreByUpper[it] },
                        "slope" to (u?.let { slopes[it] } ?: 0.0))
                }
                // WHY THIS SEAT IS CASH. The panel used to print "no eligible name"
                // on every empty seat as a hardcoded caption — a claim nothing had
                // checked, and one that read as an outright contradiction on a card
                // that listed 35 eligible names two rows below. Derive it instead,
                // and if names really are waiting, say that the fill is a once-a-day
                // step rather than implying nobody qualified.
                val eligibleUnheld = st.lastRanking.count { r ->
                    r.uppercase() !in held && (slopes[r.uppercase()] ?: slopes[r] ?: -1.0) > 0.0
                }
                val emptyReason = when {
                    st.lastRanking.isEmpty() -> "no evaluation yet"
                    slopes.values.none { it > 0.0 } -> "no rising trend in the whole list today"
                    eligibleUnheld == 0 -> "every eligible name is already held"
                    else -> "$eligibleUnheld waiting — fills at the next evaluation"
                }
                // AN OPEN POSITION NO SEAT OWNS. Reported the moment it exists, not
                // when it closes: while it is unseated its result cannot compound
                // into any seat, so the value-vs-injected figure above is describing
                // less money than is actually at risk.
                val orphans = try {
                    com.signalscope.app.data.StrategyLabStore
                        .load(applicationContext, ns).trades
                        .filter { it.status == "OPEN" }
                        .map { it.symbol.uppercase() }.distinct()
                        .filter { it !in held }
                } catch (e: Exception) { emptyList<String>() }
                // Walk the ranking ONCE, in order, splitting unheld names into the
                // bench (slope > 0 — these fill the next empty slot, in this order)
                // and blocked (slope ≤ 0 — ranked high but the gate refuses them).
                val bench = ArrayList<Map<String, Any?>>()
                val blocked = ArrayList<Map<String, Any?>>()
                // MANUAL WATCH LIST — the same names as the bench, just more of
                // them. The bench is a 5-row preview inside the gate panel ("who
                // fills the next seat"); this is the candidate set for the manual
                // entry flow, where the whole point is to look at names the gate
                // ranked and passed but has no seat for. Kept as a SEPARATE key
                // rather than widening the bench, so the existing panel renders
                // exactly as it did.
                val watch = ArrayList<Map<String, Any?>>()
                for ((i, s) in st.lastRanking.withIndex()) {
                    val u = s.uppercase()
                    if (u in held) continue
                    val sl = slopes[u] ?: continue
                    val row = mapOf("symbol" to s, "rank" to (i + 1), "slope" to sl,
                        "score" to scoreByUpper[u])
                    if (sl > 0.0) {
                        if (bench.size < 5) bench.add(row)
                        if (watch.size < WATCHLIST_N) watch.add(row)
                    }
                    else if (blocked.size < 5) blocked.add(row)
                }
                // FULL RANKING — every name the screener ordered, with its gate
                // verdict. Held and blocked names are INCLUDED (unlike bench /
                // watchlist, which are candidate lists): the point of the full
                // table is to see the whole picture the gate saw, not only the
                // part it can act on. ~106 rows for DAX, so the payload is small.
                val ranking = st.lastRanking.mapIndexed { i, s ->
                    val u = s.uppercase()
                    val sl = slopes[u]
                    mapOf("symbol" to s, "rank" to (i + 1), "slope" to sl,
                        "score" to scoreByUpper[u], "held" to (u in held),
                        "eligible" to ((sl ?: -1.0) > 0.0))
                }
                gson.toJson(mapOf("ok" to true, "enabled" to true,
                    "holdN" to st.holdN, "smoothing" to st.smoothing,
                    "lastEvalDay" to st.lastEvalDay,
                    "rankedAt" to st.lastRankedAt,
                    "scanned" to slopes.size,
                    // HOW MANY NAMES THE SCORE WAS MEASURED AGAINST. The score is a
                    // percentile inside the set that was ranked, so two runs over
                    // slightly different sets give the same name different numbers.
                    // Without this the panel looked like it disagreed with the
                    // screener for no reason.
                    "rankedCount" to st.lastRanking.size,
                    "eligible" to slopes.values.count { it > 0.0 },
                    "filled" to held.size,
                    "injected" to st.injected,
                    "value" to com.signalscope.app.data.MacdGateStore.totalValue(st),
                    "slots" to slots, "bench" to bench, "blocked" to blocked,
                    "watchlist" to watch, "watchlistN" to WATCHLIST_N,
                    "ranking" to ranking,
                    "emptyReason" to emptyReason, "orphans" to orphans,
                    "blockedTotal" to slopes.values.count { it <= 0.0 },
                    "log" to st.log.takeLast(6).reversed()))
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        /**
         * ROC PER COMPANY, taken from this space's last DISCOVERY SCAN.
         *
         * This is the app's own ROC — headroom to the nearest resistance:
         *
         *     ROC % = (resistance − price) ÷ price × 100
         *
         * NOT the textbook Rate of Change indicator. Same three letters, entirely
         * different quantity: one measures how far price has already travelled,
         * this measures how far it can travel before it meets a ceiling. The
         * discovery table labels it "ROC to Resistance" and that is the meaning
         * carried here, so a threshold set in the picker means exactly what the
         * same number means on the discovery row.
         *
         * PURE READ of the cached scan — opening the picker costs nothing and
         * never starts a scan. Resistance comes from the adaptive support and
         * resistance pass, which needs the full analysis; there is no cheap way
         * to recompute it for one company, and no reason to, since the discovery
         * tab already maintains it per market.
         *
         * Symbols are stripped of their exchange suffix so they match the bare
         * tickers the picker lists — otherwise every German name would miss.
         */
        @JavascriptInterface
        fun universeMetrics(ns: String): String {
            return try {
                val market = when (com.signalscope.app.trading.Spaces.baseOf(ns).uppercase()) {
                    "US" -> "NASDAQ 100"
                    "DE" -> "DAX 100"
                    "IN" -> "NIFTY 500"
                    else -> return gson.toJson(mapOf("ok" to false,
                        "error" to "ROC comes from a discovery scan, and this space has none."))
                }
                val cached = com.signalscope.app.data.DiscoveryResultStore
                    .load(applicationContext, market)
                    ?: return gson.toJson(mapOf("ok" to false, "market" to market,
                        "error" to "No $market discovery scan cached yet."))
                val rows = HashMap<String, Double>()
                for (s in cached.allStocks) {
                    val key = s.symbol.trim().uppercase().substringBefore('.')
                    if (key.isNotBlank() && s.rocPct > 0.0) rows[key] = s.rocPct
                }
                gson.toJson(mapOf("ok" to true, "market" to market,
                    "scannedAt" to cached.timestamp, "complete" to cached.isComplete,
                    "totalScanned" to cached.totalScanned,
                    "count" to rows.size, "rows" to rows))
            } catch (e: Exception) {
                gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "unknown")))
            }
        }

        /** FLAT-START reset for ONE algo: clears its paper ledger + watermarks so
         *  tracking restarts from the next bar (no orders placed). Refuses while
         *  REAL positions are open — same guard as binanceLabResetPaper: wiping
         *  paper severs every live record from its counterpart, and that exact
         *  desync once had the orphan sweep liquidate a real ETH position.
         *
         *  IT MUST ALSO RESET THE SEATS. The headline % for an armed 3b algo is
         *  seat capital ÷ injected, which lives in the gate store, NOT in the paper
         *  ledger. Clearing only the ledger left the card reading -1.0% over a book
         *  showing "0 trades · +0.0% booked" — the reset visibly did not take, and
         *  worse, the stale seat capital is what sizes the NEXT position. A reset
         *  that says "starts flat" has to make every number that claims to describe
         *  this algo agree, or none of them can be trusted.
         *
         *  Seats are re-created empty at par rather than deleted, so the card still
         *  reads N/N slots and the algo is immediately runnable; lastEvalDay is
         *  cleared so the gate re-evaluates on the next pass instead of tomorrow. */
        @JavascriptInterface
        fun bucketResetPaper(ns: String): String {
            return try {
                if (com.signalscope.app.trading.Spaces.baseOf(ns) == "binance") {
                    val openLive = com.signalscope.app.data.BinanceLiveStore
                        .load(applicationContext, ns).trades.filter { it.status == "OPEN" }
                    if (openLive.isNotEmpty()) {
                        return gson.toJson(mapOf("ok" to false, "error" to
                            "Refused: ${openLive.size} REAL Binance position(s) still open (" +
                            openLive.joinToString(", ") { it.symbol } +
                            "). Resetting paper now would desync the live ledger — " +
                            "let them close (or sell manually) first."))
                    }
                } else if (com.signalscope.app.trading.Spaces.baseOf(ns) == "in") {
                    // AND ON ZERODHA, which this used to skip: an Indian algo fell
                    // through to the Trading 212 check, found nothing there, and
                    // wiped the paper book from under real positions it had bought.
                    val openLive = com.signalscope.app.data.ZerodhaLiveStore
                        .load(applicationContext).trades.filter { it.ns == ns && it.isLive }
                    if (openLive.isNotEmpty()) {
                        return gson.toJson(mapOf("ok" to false, "error" to
                            "Refused: ${openLive.size} REAL Zerodha position(s) still open (" +
                            openLive.joinToString(", ") { it.symbol } +
                            "). Resetting paper now would orphan them — close them first, " +
                            "or mark them sold by hand if you already did."))
                    }
                } else {
                    // Same hazard on the T212 side: a real mirrored position is tied
                    // to the paper trade that produced it. Wipe the paper and the
                    // live row has no counterpart, so nothing can price or close it.
                    val openLive = com.signalscope.app.data.T212LiveStore
                        .load(applicationContext).trades.filter {
                            it.ns == ns && it.status in setOf("OPEN", "PENDING", "EXITING")
                        }
                    if (openLive.isNotEmpty()) {
                        return gson.toJson(mapOf("ok" to false, "error" to
                            "Refused: ${openLive.size} REAL Trading 212 position(s) still open (" +
                            openLive.joinToString(", ") { it.symbol } +
                            "). Resetting paper now would orphan them — close them first, " +
                            "or mark them sold by hand if you already did."))
                    }
                }
                com.signalscope.app.data.StrategyLabStore.resetPaperState(applicationContext, ns)

                // The seats, which is where the headline % actually comes from.
                var seatsReset = 0
                var wasPct = 0.0
                com.signalscope.app.data.MacdGateStore.mutate(applicationContext, ns) { st ->
                    if (!st.enabled || st.slots.isEmpty()) st else {
                        val before = com.signalscope.app.data.MacdGateStore.totalValue(st)
                        wasPct = if (st.injected > 0.0) (before / st.injected - 1.0) * 100.0 else 0.0
                        // ADOPT THE CONFIGURED SHAPE, don't perpetuate the old one.
                        // Keeping the existing seat count meant a layout left over
                        // from an earlier setting survived every reset — six pockets
                        // under a five-slot config, refilled to six on the next
                        // evaluation. A flat start has to start flat in shape too.
                        // All seats are being emptied here, so this is exactly the
                        // moment a reshape is safe.
                        val bkt = com.signalscope.app.data.BucketStore.get(applicationContext, ns)
                        val seats = com.signalscope.app.data.MacdGateStore
                            .effectiveHoldN(st, bkt?.symbols?.size ?: st.slots.size)
                            .coerceAtLeast(1)
                        val capital = bkt?.capital?.takeIf { it > 0.0 }
                            ?: (if (st.injected > 0.0) st.injected else before)
                        val per = capital / seats
                        seatsReset = seats
                        val now = System.currentTimeMillis()
                        com.signalscope.app.data.MacdGateStore.appendLog(
                            com.signalscope.app.data.MacdGateStore
                                .closeAllEnrollments(st, "paper_reset", now)
                                .copy(
                                slots = (1..seats).map {
                                    com.signalscope.app.data.MacdGateStore.Slot(
                                        null, per, now, 0, false)
                                },
                                injected = per * seats,
                                lastEvalDay = "",
                                lastRanking = emptyList(),
                                lastSlopes = emptyMap(),
                                lastScores = null, lastRankedAt = 0L),
                            "${java.time.LocalDate.now()} paper reset — $seats seats back to " +
                                "${"%.0f".format(per)} each (was ${"%+.2f".format(wasPct)}%)")
                    }
                }
                gson.toJson(mapOf("ok" to true,
                    "seatsReset" to seatsReset, "clearedPct" to wasPct))
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        /** Post a clearly-labelled PREVIEW of a watcher alert for one name, chart
         *  included — the only way to see the shade version without waiting for a
         *  real signal. Not wired to a button; nothing is bought or recorded. */
        /** The same preview for a chosen alert kind: "low", "entry" or "turn". */
        @JavascriptInterface
        fun previewWatchAlertAs(space: String, symbol: String, kind: String): String = try {
            val base = com.signalscope.app.trading.Spaces.baseOf(space.lowercase())
            val ok = com.signalscope.app.trading.LabNotifier.previewChart(applicationContext, base, symbol, kind)
            gson.toJson(mapOf("ok" to ok,
                "error" to (if (ok) null else "nothing to draw for $symbol in $base")))
        } catch (e: Exception) {
            gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "failed")))
        }

        @JavascriptInterface
        fun previewWatchAlert(space: String, symbol: String): String = try {
            val base = com.signalscope.app.trading.Spaces.baseOf(space.lowercase())
            val ok = com.signalscope.app.trading.LabNotifier.previewChart(applicationContext, base, symbol)
            gson.toJson(mapOf("ok" to ok,
                "error" to (if (ok) null else "no stored MACD curve for $symbol in $base")))
        } catch (e: Exception) {
            gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "failed")))
        }

        /**
         * The signal tracker for one market: every "turning up at a floor" and
         * "all five green" event, newest first, with what the price did 1, 3 and
         * 5 trading days later, and a summary per rule. A local file — no network.
         */
        @JavascriptInterface
        fun signalTrack(space: String): String = try {
            val base = com.signalscope.app.trading.Spaces.baseOf(space.lowercase())
            val evs = com.signalscope.app.data.SignalTrackStore.load(applicationContext).events
                .filter { it.space == base }.sortedByDescending { it.atMs }
            fun summary(rule: String): Map<String, Any?> {
                val r = evs.filter { it.rule == rule }
                fun avg(xs: List<Double>) = if (xs.isEmpty()) null else xs.average()
                val r3 = r.mapNotNull { it.ret3 }
                return mapOf("n" to r.size,
                    "avg1" to avg(r.mapNotNull { it.ret1 }), "n1" to r.count { it.ret1 != null },
                    "avg3" to avg(r3), "n3" to r3.size,
                    "avg5" to avg(r.mapNotNull { it.ret5 }), "n5" to r.count { it.ret5 != null },
                    "up3" to (if (r3.isEmpty()) null else r3.count { it > 0.0 } * 100.0 / r3.size))
            }
            gson.toJson(mapOf("ok" to true,
                "floorTurn" to summary(com.signalscope.app.data.SignalTrackStore.RULE_FLOOR_TURN),
                "allGreen" to summary(com.signalscope.app.data.SignalTrackStore.RULE_ALL_GREEN),
                "events" to evs.take(40)))
        } catch (e: Exception) {
            gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "failed")))
        }

        /** A bucket's paper ledger (trades + rollup) for the Algos ledger view. */
        @JavascriptInterface
        fun bucketLedger(ns: String): String {
            return try {
                val s = com.signalscope.app.data.StrategyLabStore.load(applicationContext, ns)
                val closed = s.trades.filter { it.status == "CLOSED" }
                // FEED FRESHNESS, per symbol. The watermark is the timestamp of the
                // newest bar the engine has actually PROCESSED — the only honest
                // answer to "are the candles old". Distinct from the gate date (once
                // a day) and from the mark-to-market stamp (live price), which is why
                // "old candles" was ambiguous without it. Note a 240min/120min config
                // legitimately trails by up to one exit bar, and always sits behind
                // the current time because unclosed bars are deliberately discarded.
                //
                // Plus, per trade, WHY it never became a real order — the question a
                // paper position with nothing behind it always raises.
                val skips = try {
                    com.signalscope.app.data.LiveSkipStore.forNs(applicationContext, ns)
                } catch (e: Exception) { emptyMap<String, String>() }
                // WHAT HAPPENED TO THE REAL ORDER, per paper trade. A position the
                // strategy holds with a failed or waiting order behind it looked
                // exactly like one with shares behind it — Nemetschek sat on this
                // card for three days, bought at 18:01 into a closed Xetra, with
                // nothing on its row to say so.
                // Every broker, not only Trading 212: the take-profit button needs to
                // say plainly whether real shares will be sold, in every market.
                val liveRows = try {
                    when (com.signalscope.app.trading.Spaces.baseOf(ns)) {
                        "in" -> com.signalscope.app.data.ZerodhaLiveStore.load(applicationContext).trades
                            .filter { it.ns == ns }
                            .associate { r ->
                                r.paperTradeId to mapOf("status" to r.status, "error" to (r.error ?: ""),
                                    "qty" to r.qty.toDouble())
                            }
                        "binance" -> com.signalscope.app.data.BinanceLiveStore.load(applicationContext, ns).trades
                            .associate { r ->
                                r.paperTradeId to mapOf("status" to r.status, "error" to (r.error ?: ""),
                                    "qty" to (r.executedQty.toDoubleOrNull() ?: 0.0))
                            }
                        else -> {
                            // The 1-week-high target waiting at the broker, when there is one.
                            val tg = try { com.signalscope.app.trading.T212WeekTargets.views(applicationContext) }
                                     catch (e: Exception) { emptyMap() }
                            com.signalscope.app.data.T212LiveStore.load(applicationContext).trades
                                .filter { it.ns == ns }
                                .associate { r ->
                                    r.paperTradeId to mapOf("status" to r.status, "error" to (r.error ?: ""),
                                        "qty" to r.qty, "retryAfter" to r.retryAfter,
                                        "parked" to r.parkedForOpen,
                                        "target" to tg[com.signalscope.app.data.T212TargetStore.key(ns, r.paperTradeId)])
                                }
                        }
                    }
                } catch (e: Exception) { emptyMap<String, Any>() }
                gson.toJson(mapOf("ok" to true, "skips" to skips,
                    "liveRows" to liveRows,
                    // Seconds the price feed trails the exchange here. A bar is judged
                    // this long after it ends, so the card's "next bar" says when the
                    // decision is actually made rather than when the clock turns.
                    "feedDelaySec" to com.signalscope.app.trading.FeedDelay.secFor(ns),
                    "open" to s.trades.count { it.status == "OPEN" },
                    "closed" to closed.size,
                    "wins" to closed.count { it.pctReturn > 0 },
                    "pnlPct" to closed.sumOf { it.pctReturn },
                    "lastRunAt" to s.lastRunAt,
                    "watermarks" to s.watermarks,
                    "bars" to s.statuses.mapValues { it.value.bars },
                    // The frame the watermark is measured in, so the UI can say when
                    // the current bar CLOSED (wm + frame) and when the next one will
                    // (wm + 2 frames) — which is also exactly when the runner stops
                    // skipping and fetches again.
                    "frameSec" to run {
                        val c = com.signalscope.app.data.StrategyLabStore.activeConfig(applicationContext, ns)
                        val p = com.signalscope.app.trading.FeedPlan.plan(c, ns)
                        maxOf((if (c.multiTimeframe) p.exitMin else p.entryMin).toLong() * 60L, p.sourceSec)
                    },
                    "trades" to s.trades.takeLast(80)))
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        /** Launch the walk-forward CSV backtest against a bucket's active config. */
        @JavascriptInterface
        fun bucketBacktestPickCsv(ns: String) {
            backtestNs = ns
            runOnUiThread {
                try { backtestCsvPicker.launch(arrayOf("*/*")) }
                catch (e: Exception) { Log.e(TAG, "bucket csv picker launch failed", e) }
            }
        }

        @JavascriptInterface
        fun reinvestGetState(space: String): String {
            return try {
                val ns = reinvestNs(space)
                val s = com.signalscope.app.data.ReinvestStore.load(applicationContext, ns)
                val balancesSorted = s.balances.entries.sortedByDescending { it.value }
                    .map { mapOf("symbol" to it.key, "balance" to it.value) }
                gson.toJson(mapOf(
                    "enabled" to s.enabled, "initialized" to s.initialized, "mode" to s.mode,
                    "notional" to s.notional, "topN" to s.topN, "universe" to s.universe,
                    "balances" to balancesSorted, "injectedCapital" to s.injectedCapital, "idleCash" to s.idleCash,
                    "windingDown" to (s.windingDown ?: emptyList<String>()),
                    "totalValue" to com.signalscope.app.data.ReinvestStore.totalValue(s),
                    "compoundedPct" to com.signalscope.app.data.ReinvestStore.compoundedPct(s),
                    "lastRebalanceMonth" to s.lastRebalanceMonth, "lastRebalanceAt" to s.lastRebalanceAt,
                    "history" to s.history.reversed().take(24)))
            } catch (e: Exception) {
                Log.e(TAG, "reinvestGetState failed", e)
                "{\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        @JavascriptInterface
        fun reinvestInitialize(space: String, universeJson: String, totalInvest: Double, mode: String, topN: Int) {
            thread(isDaemon = true) {
                val ns = reinvestNs(space)
                try {
                    val arr = com.google.gson.JsonParser.parseString(universeJson).asJsonArray
                    val syms = arr.mapNotNull { runCatching { it.asString }.getOrNull() }
                    val r = com.signalscope.app.screener.ReinvestEngine.initialize(
                        applicationContext, ns, syms, totalInvest, mode, topN) { done, total ->
                        deliverAiCallback("window.onScreenerProgress", "{\"done\":$done,\"total\":$total}")
                    }
                    // Crypto: funding reinvest is a no-op for live sizing unless the
                    // mirror is in BUDGET mode (that's the only mode that reads the
                    // sleeves). Auto-switch so the user can't fund a pot yet keep
                    // trading the flat fixed size by mistake.
                    if (r.ok && ns == "binance") config.binanceLiveSizingMode = "BUDGET"
                    deliverAiCallback("window.onReinvestUpdate",
                        gson.toJson(mapOf("ok" to r.ok, "message" to r.message, "added" to r.added)))
                } catch (e: Exception) {
                    Log.e(TAG, "reinvestInitialize failed", e)
                    deliverAiCallback("window.onReinvestUpdate",
                        gson.toJson(mapOf("ok" to false, "message" to (e.message ?: "init failed"))))
                }
            }
        }

        @JavascriptInterface
        fun reinvestRebalanceNow(space: String) {
            thread(isDaemon = true) {
                val ns = reinvestNs(space)
                val r = try {
                    com.signalscope.app.screener.ReinvestEngine.rebalance(applicationContext, ns) { done, total ->
                        deliverAiCallback("window.onScreenerProgress", "{\"done\":$done,\"total\":$total}")
                    }
                } catch (e: Exception) {
                    com.signalscope.app.screener.ReinvestEngine.RunOutcome(false, e.message ?: "rebalance failed")
                }
                deliverAiCallback("window.onReinvestUpdate",
                    gson.toJson(mapOf("ok" to r.ok, "message" to r.message, "added" to r.added, "dropped" to r.dropped)))
            }
        }

        @JavascriptInterface
        fun reinvestSetEnabled(space: String, on: Boolean): Boolean {
            return try { com.signalscope.app.data.ReinvestStore.setEnabled(applicationContext, reinvestNs(space), on); on }
            catch (e: Exception) { Log.e(TAG, "reinvestSetEnabled failed", e); false }
        }

        @JavascriptInterface
        fun reinvestReset(space: String): String {
            return try { com.signalscope.app.data.ReinvestStore.reset(applicationContext, reinvestNs(space)); "{\"ok\":true}" }
            catch (e: Exception) { "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}" }
        }

        // ── BINANCE STRATEGY LAB (CRYPTO space, PAPER ONLY) ───────────────────
        // Same engine + store + runner as the US lab, but namespace "binance":
        // its own config file, ledger and Binance 15-min spot klines. No keys,
        // no real orders. Mirrors the strategyLab* bridges above. Results are
        // delivered to the SAME window.onStrategyLab* callbacks (only one space
        // is active at a time, so they can be shared).
        private val BN = "binance"

        private fun coreBinancePairs(): List<Map<String, String>> = listOf(
            mapOf(
                "symbol" to "BTCUSDC",
                "base" to "BTC",
                "quote" to "USDC",
                "name" to "Bitcoin",
                "why" to "Largest and most liquid crypto asset."
            ),
            mapOf(
                "symbol" to "ETHUSDC",
                "base" to "ETH",
                "quote" to "USDC",
                "name" to "Ethereum",
                "why" to "Large-cap smart-contract network."
            ),
            mapOf(
                "symbol" to "BNBUSDC",
                "base" to "BNB",
                "quote" to "USDC",
                "name" to "BNB",
                "why" to "Large-cap Binance ecosystem token."
            )
        )

        /** The USDC size a live BUY would actually use right now — mirrors
         *  BinanceLiveMirror.perTradeQuoteSize so the UI never shows a number
         *  that disagrees with what really gets ordered. For BUDGET mode with a
         *  funded reinvest book this is the average enrolled sleeve. */
        private fun effectiveBinanceBuySize(): Double {
            if (config.binanceLiveSizingMode != "BUDGET") return config.binanceLiveQuoteOrderSize
            val enrolled = com.signalscope.app.data.StrategyLabStore.load(applicationContext, BN).enrolled
            val rs = com.signalscope.app.data.ReinvestStore.load(applicationContext, BN)
            if (rs.initialized && rs.balances.isNotEmpty()) {
                val bals = enrolled.mapNotNull { rs.balances[it.symbol.uppercase()] }.filter { it > 0.0 }
                if (bals.isNotEmpty()) return bals.average()
            }
            if (config.binanceLiveBudget > 0.0)
                return config.binanceLiveBudget / enrolled.size.coerceAtLeast(1)
            return config.binanceLiveQuoteOrderSize
        }

        private fun binanceAccountStateJson(): String {
            fun baseState(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> =
                mapOf(
                    "configured" to config.hasBinanceCredentials,
                    "liveEnabled" to config.hasBinanceCredentials,
                    "quoteOrderSize" to config.binanceLiveQuoteOrderSize,
                    "sizingMode" to config.binanceLiveSizingMode,
                    "budget" to config.binanceLiveBudget,
                    "effectiveBuySize" to effectiveBinanceBuySize(),
                    "liveAllowlist" to config.binanceLiveAllowlist,
                    "core" to coreBinancePairs()
                ) + extra

            if (!config.hasBinanceCredentials) {
                return gson.toJson(baseState(mapOf(
                    "ok" to false,
                    "error" to "Binance API key/secret not saved yet."
                )))
            }

            return try {
                when (val r = com.signalscope.app.network.BinanceClient.fetchAccount(config)) {
                    is com.signalscope.app.network.BinanceClient.BinanceResult.Success -> {
                        val account = r.data
                        val preferred = listOf("USDT", "BTC", "ETH", "BNB")
                        val balances = account.balances.values
                            .map {
                                mapOf(
                                    "asset" to it.asset,
                                    "free" to it.free,
                                    "locked" to it.locked,
                                    "freeValue" to (it.free.toDoubleOrNull() ?: 0.0),
                                    "lockedValue" to (it.locked.toDoubleOrNull() ?: 0.0)
                                )
                            }
                            .filter {
                                ((it["freeValue"] as? Double) ?: 0.0) > 0.0 ||
                                    ((it["lockedValue"] as? Double) ?: 0.0) > 0.0 ||
                                    preferred.contains(it["asset"] as? String)
                            }
                            .sortedWith(compareBy<Map<String, Any?>> {
                                val asset = it["asset"] as? String ?: ""
                                val idx = preferred.indexOf(asset)
                                if (idx >= 0) idx else preferred.size + 1
                            }.thenBy { it["asset"] as? String ?: "" })

                        val usdt = account.balances["USDT"]
                        gson.toJson(baseState(mapOf(
                            "ok" to true,
                            "accountType" to account.accountType,
                            "canTrade" to account.canTrade,
                            "usdtFree" to (usdt?.free ?: "0"),
                            "usdtLocked" to (usdt?.locked ?: "0"),
                            "balances" to balances
                        )))
                    }
                    is com.signalscope.app.network.BinanceClient.BinanceResult.Failure ->
                        gson.toJson(baseState(mapOf("ok" to false, "error" to r.message)))
                    is com.signalscope.app.network.BinanceClient.BinanceResult.NotConfigured ->
                        gson.toJson(baseState(mapOf(
                            "ok" to false,
                            "configured" to false,
                            "error" to "Binance API key/secret not saved yet."
                        )))
                }
            } catch (e: Exception) {
                Log.e(TAG, "binanceAccountStateJson failed", e)
                gson.toJson(baseState(mapOf(
                    "ok" to false,
                    "error" to (e.message ?: "Binance account fetch failed")
                )))
            }
        }

        @JavascriptInterface
        fun binanceAccountGetState(): String = binanceAccountStateJson()

        @JavascriptInterface
        fun fetchBinanceAccount() {
            thread(isDaemon = true, name = "binance-account") {
                deliverAiCallback("window.onBinanceAccount", binanceAccountStateJson())
            }
        }

        /** Remove never-filled ERROR rows from the live ledger (e.g. buys rejected
         *  for insufficient balance). Records with real executed quantity are kept
         *  — see BinanceLiveStore.clearFailedEntries. */
        @JavascriptInterface
        fun binanceClearFailedLive(): String {
            return try {
                val n = com.signalscope.app.data.BinanceLiveStore.clearFailedEntries(applicationContext)
                gson.toJson(mapOf("ok" to true, "removed" to n))
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"", "")}\"}"
            }
        }

        /** Set which symbols may receive REAL Binance orders. Blank = all
         *  enrolled (the pre-allowlist behaviour). Paper is never affected. */
        @JavascriptInterface
        fun binanceSetLiveAllowlist(csv: String): String {
            return try {
                config.binanceLiveAllowlist = csv
                gson.toJson(mapOf("ok" to true, "liveAllowlist" to config.binanceLiveAllowlist))
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"", "")}\"}"
            }
        }

        // ── LIVE SIZING (fixed per-trade vs budget ÷ enrolled, PnL-compounded) ──
        @JavascriptInterface
        fun binanceSetSizing(mode: String, budget: Double, fixedSize: Double): String {
            return try {
                config.binanceLiveSizingMode = mode
                if (budget >= 0.0) config.binanceLiveBudget = budget
                if (fixedSize > 0.0) config.binanceLiveQuoteOrderSize = fixedSize
                gson.toJson(mapOf(
                    "ok" to true,
                    "mode" to config.binanceLiveSizingMode,
                    "budget" to config.binanceLiveBudget,
                    "fixedSize" to config.binanceLiveQuoteOrderSize
                ))
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"", "")}\"}"
            }
        }

        // ── MANUAL TEST ORDERS (crypto only): invest = market BUY of $X USDT,
        //    withdraw = market SELL the full free base balance back to USDT.
        //    Reuses the exact signed-order path the auto mirror uses, so a
        //    success here proves keys + Spot permission + rules all work. ──
        @JavascriptInterface
        fun binanceManualBuy(symbol: String, usdt: Double) {
            thread(isDaemon = true, name = "binance-manual-buy") {
                deliverAiCallback("window.onBinanceManualResult", binanceManualBuyJson(symbol, usdt))
            }
        }

        @JavascriptInterface
        fun binanceManualSell(symbol: String) {
            thread(isDaemon = true, name = "binance-manual-sell") {
                deliverAiCallback("window.onBinanceManualResult", binanceManualSellJson(symbol))
            }
        }

        private fun manualErr(msg: String, sym: String, action: String): String =
            gson.toJson(mapOf("ok" to false, "action" to action, "symbol" to sym, "error" to msg))

        private fun binanceManualBuyJson(symbol: String, usdt: Double): String {
            val sym = symbol.trim().uppercase()
            val bc = com.signalscope.app.network.BinanceClient
            val quote = bc.quoteAssetOf(sym)
            if (!config.hasBinanceCredentials) return manualErr("Binance API key/secret not saved yet.", sym, "BUY")
            if (quote == null) return manualErr(
                "Manual buy supports ${bc.SUPPORTED_QUOTES.joinToString("/")} spot pairs only (e.g. BTCUSDC).", sym, "BUY")
            if (usdt <= 0.0) return manualErr("Enter a $quote amount greater than 0.", sym, "BUY")
            return try {
                when (val a = bc.fetchAccount(config)) {
                    is com.signalscope.app.network.BinanceClient.BinanceResult.Success -> {
                        if (!a.data.canTrade) return manualErr("API key cannot trade — enable Spot trading permission.", sym, "BUY")
                        val free = a.data.balances[quote]?.free?.toDoubleOrNull() ?: 0.0
                        if (free < usdt) return manualErr(
                            "Insufficient $quote free: have ${"%.2f".format(free)}, need ${"%.2f".format(usdt)}.", sym, "BUY")
                    }
                    is com.signalscope.app.network.BinanceClient.BinanceResult.Failure -> return manualErr(a.message, sym, "BUY")
                    is com.signalscope.app.network.BinanceClient.BinanceResult.NotConfigured -> return manualErr("Binance API key/secret not saved yet.", sym, "BUY")
                }
                val coid = "ss_man_b_${System.currentTimeMillis()}".take(36)
                when (val r = bc.placeMarketBuy(config, sym, usdt, coid)) {
                    is com.signalscope.app.network.BinanceClient.BinanceResult.Success -> {
                        val o = r.data
                        gson.toJson(mapOf(
                            "ok" to true, "action" to "BUY", "symbol" to sym,
                            "orderId" to o.orderId, "status" to o.status,
                            "executedQty" to o.executedQty, "spentQuote" to o.cummulativeQuoteQty,
                            "avgPrice" to o.averagePrice
                        ))
                    }
                    is com.signalscope.app.network.BinanceClient.BinanceResult.Failure -> manualErr(r.message, sym, "BUY")
                    is com.signalscope.app.network.BinanceClient.BinanceResult.NotConfigured -> manualErr("Binance API key/secret not saved yet.", sym, "BUY")
                }
            } catch (e: Exception) {
                Log.e(TAG, "binanceManualBuy failed", e)
                manualErr(e.message ?: "Manual buy failed", sym, "BUY")
            }
        }

        private fun binanceManualSellJson(symbol: String): String {
            val sym = symbol.trim().uppercase()
            val bc = com.signalscope.app.network.BinanceClient
            val quote = bc.quoteAssetOf(sym)
            if (!config.hasBinanceCredentials) return manualErr("Binance API key/secret not saved yet.", sym, "SELL")
            if (quote == null) return manualErr(
                "Manual sell supports ${bc.SUPPORTED_QUOTES.joinToString("/")} spot pairs only (e.g. BTCUSDC).", sym, "SELL")
            val base = bc.baseAssetOf(sym) ?: sym.removeSuffix(quote)
            return try {
                // THE ALGOS' COINS GO THROUGH THEIR OWN TAKE-PROFIT FIRST. Selling
                // the whole balance from here used to leave every algo holding
                // this coin on paper with nothing behind it. Now each algo's share
                // is sold by its own exit, booked to it at the real price, and its
                // seat handed on; only what is left is sold below.
                val algoNotes = ArrayList<String>()
                try {
                    val nss = applicationContext.filesDir.list().orEmpty()
                        .filter { it.startsWith("binance_live_mirror") && it.endsWith(".json") }
                        .map { if (it == "binance_live_mirror.json") "binance"
                               else it.removePrefix("binance_live_mirror_").removeSuffix(".json") }
                        .distinct()
                    for (ns in nss) {
                        val holds = com.signalscope.app.data.BinanceLiveStore.load(applicationContext, ns).trades
                            .any { it.status == "OPEN" && it.symbol.equals(sym, true) }
                        if (!holds) continue
                        val tp = com.signalscope.app.trading.AlgoTakeProfit.takeNow(applicationContext, ns, sym)
                        algoNotes += if (tp.ok) "Algo's share sold and booked at ${"%.4f".format(tp.exitPrice)} " +
                                "(${"%+.2f".format(tp.pctReturn)}%). ${tp.note}".trim()
                            else "An algo's share could not be sold through it: ${tp.error}"
                    }
                } catch (e: Exception) { Log.w(TAG, "algo take-profit before manual sell failed", e) }
                val free = when (val a = bc.fetchAccount(config)) {
                    is com.signalscope.app.network.BinanceClient.BinanceResult.Success -> a.data.balances[base]?.free?.toDoubleOrNull() ?: 0.0
                    is com.signalscope.app.network.BinanceClient.BinanceResult.Failure -> return manualErr(a.message, sym, "SELL")
                    is com.signalscope.app.network.BinanceClient.BinanceResult.NotConfigured -> return manualErr("Binance API key/secret not saved yet.", sym, "SELL")
                }
                if (free <= 0.0) {
                    if (algoNotes.isNotEmpty()) return gson.toJson(mapOf("ok" to true, "action" to "SELL",
                        "symbol" to sym, "executedQty" to "0", "receivedQuote" to "0", "avgPrice" to 0.0,
                        "handled" to algoNotes.joinToString(" ")))
                    return manualErr("No free $base balance to sell.", sym, "SELL")
                }
                val coid = "ss_man_s_${System.currentTimeMillis()}".take(36)
                val qty = java.math.BigDecimal.valueOf(free).stripTrailingZeros().toPlainString()
                when (val r = bc.placeMarketSell(config, sym, qty, coid, null)) {
                    is com.signalscope.app.network.BinanceClient.BinanceResult.Success -> {
                        val o = r.data
                        gson.toJson(mapOf(
                            "ok" to true, "action" to "SELL", "symbol" to sym,
                            "orderId" to o.orderId, "status" to o.status,
                            "executedQty" to o.executedQty, "receivedQuote" to o.cummulativeQuoteQty,
                            "avgPrice" to o.averagePrice,
                            "handled" to algoNotes.joinToString(" ")
                        ))
                    }
                    is com.signalscope.app.network.BinanceClient.BinanceResult.Failure -> manualErr(r.message, sym, "SELL")
                    is com.signalscope.app.network.BinanceClient.BinanceResult.NotConfigured -> manualErr("Binance API key/secret not saved yet.", sym, "SELL")
                }
            } catch (e: Exception) {
                Log.e(TAG, "binanceManualSell failed", e)
                manualErr(e.message ?: "Manual sell failed", sym, "SELL")
            }
        }

        @JavascriptInterface
        fun binanceLabGetState(): String {
            return try {
                val s = com.signalscope.app.data.StrategyLabStore.load(applicationContext, BN)
                val cfg = com.signalscope.app.data.StrategyLabStore.activeConfig(applicationContext, BN)
                val live = com.signalscope.app.data.BinanceLiveStore.load(applicationContext)
                val closed = s.trades.filter { it.status == "CLOSED" }
                val wins = closed.count { it.pctReturn > 0 }
                // PARTIAL trades carry realized cash P&L too (prorated cost basis).
                val liveClosed = live.trades.filter { it.status == "CLOSED" || it.status == "PARTIAL" }
                val board = mapOf(
                    "open" to s.trades.count { it.status == "OPEN" },
                    "closed" to closed.size,
                    "wins" to wins,
                    "losses" to closed.count { it.pctReturn < 0 },
                    "totalPnl" to closed.sumOf { it.pnlNotional },
                    "totalPct" to closed.sumOf { it.pctReturn }
                )
                gson.toJson(mapOf(
                    "enabled" to s.enabled,
                    "source" to s.configSource,
                    "summary" to cfg.summary(),
                    "lastRunAt" to s.lastRunAt,
                    "enrolled" to s.enrolled,
                    "trades" to s.trades,
                    "audit" to s.audit,
                    "statuses" to s.statuses,
                    "scoreboard" to board,
                    "liveMirror" to mapOf(
                        "enabled" to config.hasBinanceCredentials,
                        "configured" to config.hasBinanceCredentials,
                        "quoteOrderSize" to config.binanceLiveQuoteOrderSize,
                        "sizingMode" to config.binanceLiveSizingMode,
                        "budget" to config.binanceLiveBudget,
                        "enrolledCount" to s.enrolled.size,
                        "perSymbolSize" to effectiveBinanceBuySize(),
                        "open" to live.trades.count { it.status == "OPEN" },
                        "closed" to liveClosed.size,
                        "errors" to live.trades.count { it.status == "ERROR" || it.error.isNotBlank() },
                        "pnlQuote" to liveClosed.sumOf { it.livePnlQuote },
                        "unmanaged" to live.unmanaged,
                        // Paper-vs-real execution drag (slippage + fees). Only
                        // fully-CLOSED trades with all four prices contribute.
                        "slippage" to com.signalscope.app.trading.SlippageStats.toMap(
                            com.signalscope.app.trading.SlippageStats.compute(
                                liveClosed.filter { it.status == "CLOSED" }.map {
                                    com.signalscope.app.trading.SlippageStats.Sample(
                                        paperEntry = it.paperEntryPrice,
                                        liveEntry = it.liveAvgBuyPrice,
                                        paperExit = it.paperExitPrice ?: 0.0,
                                        liveExit = it.liveAvgSellPrice,
                                        quoteSpent = it.buyQuoteQty.toDoubleOrNull() ?: 0.0,
                                        livePnlQuote = it.livePnlQuote)
                                })),
                        "trades" to live.trades
                    )
                ))
            } catch (e: Exception) {
                Log.e(TAG, "binanceLabGetState failed", e)
                "{\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        @JavascriptInterface
        fun binanceLabSetEnabled(on: Boolean): Boolean {
            return try {
                com.signalscope.app.data.StrategyLabStore.setEnabled(applicationContext, on, BN)
                com.signalscope.app.service.StrategyLabScheduler.ensureScheduled(applicationContext)
                if (on) com.signalscope.app.trading.StrategyLabRunner.runAll(applicationContext, 0L, BN) {
                    deliverAiCallback("window.onStrategyLabUpdate", "{}")
                }
                on
            } catch (e: Exception) { Log.e(TAG, "binanceLabSetEnabled failed", e); false }
        }

        @JavascriptInterface
        fun binanceLabEnroll(symbol: String, exchange: String): String {
            return try {
                val norm = symbol.trim().uppercase()
                // Binance spot pairs: letters/digits only, e.g. BTCUSDT, ETHUSDT.
                if (!Regex("^[A-Z0-9]{4,20}$").matches(norm)) {
                    return gson.toJson(mapOf("ok" to false,
                        "error" to "\"$symbol\" isn't a valid Binance pair — letters/digits only (e.g. BTCUSDT, ETHUSDT, SOLUSDT)."))
                }
                val s = com.signalscope.app.data.StrategyLabStore.enroll(applicationContext, norm, "BINANCE", BN)
                com.signalscope.app.service.StrategyLabScheduler.ensureScheduled(applicationContext)
                gson.toJson(mapOf("ok" to true, "count" to s.enrolled.size))
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        @JavascriptInterface
        fun binanceLabUnenroll(symbol: String): String {
            return try {
                val s = com.signalscope.app.data.StrategyLabStore.unenroll(applicationContext, symbol, BN)
                com.signalscope.app.service.StrategyLabScheduler.ensureScheduled(applicationContext)
                gson.toJson(mapOf("ok" to true, "count" to s.enrolled.size))
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        @JavascriptInterface
        fun binanceLabUploadConfig(jsonText: String, filename: String = "uploaded"): String {
            return try {
                when (val r = com.signalscope.app.trading.StrategyLabConfig.fromJson(jsonText, filename)) {
                    is com.signalscope.app.trading.StrategyLabConfig.Companion.ParseResult.Ok -> {
                        com.signalscope.app.data.StrategyLabStore.setConfig(applicationContext, jsonText, filename, BN)
                        gson.toJson(mapOf("ok" to true, "summary" to r.config.summary()))
                    }
                    is com.signalscope.app.trading.StrategyLabConfig.Companion.ParseResult.Rejected ->
                        gson.toJson(mapOf("ok" to false, "error" to r.reason))
                }
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        @JavascriptInterface
        fun binanceLabResetConfig(): String {
            return try {
                com.signalscope.app.data.StrategyLabStore.resetToPreset(applicationContext, BN)
                val cfg = com.signalscope.app.data.StrategyLabStore.activeConfig(applicationContext, BN)
                gson.toJson(mapOf("ok" to true, "summary" to cfg.summary()))
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        /** Retire the legacy bare crypto lab: unenroll all its coins, disable it,
         *  reset its monthly-reinvest, and restrict real-money coins to BTC/ETH.
         *  Crypto strategy moves to Algos buckets. Refuses while real positions
         *  are open (they must be closed first, or the ledger desyncs). */
        @JavascriptInterface
        fun binanceRetireLegacy(): String {
            return try {
                val openLive = com.signalscope.app.data.BinanceLiveStore
                    .load(applicationContext).trades.filter { it.status == "OPEN" }
                if (openLive.isNotEmpty()) return gson.toJson(mapOf("ok" to false,
                    "error" to "Close the ${openLive.size} open live position(s) first: " +
                        openLive.joinToString(", ") { it.symbol }))
                val enrolled = com.signalscope.app.data.StrategyLabStore.load(applicationContext, "binance").enrolled
                for (e in enrolled) com.signalscope.app.data.StrategyLabStore.unenroll(applicationContext, e.symbol, "binance")
                com.signalscope.app.data.StrategyLabStore.setEnabled(applicationContext, false, "binance")
                try { com.signalscope.app.data.ReinvestStore.reset(applicationContext, "binance") } catch (_: Exception) {}
                try { com.signalscope.app.data.MacdGateStore.mutate(applicationContext, "binance") { it.copy(enabled = false) } } catch (_: Exception) {}
                config.binanceLiveAllowlist = "BTCUSDC,ETHUSDC"
                gson.toJson(mapOf("ok" to true, "unenrolled" to enrolled.size))
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        @JavascriptInterface
        fun binanceLabResetPaper(): String {
            return try {
                // RESET GUARD: wiping the paper ledger while REAL positions are
                // open severs every live record from its paper counterpart —
                // that exact desync caused a real ETH position to be liquidated
                // by the orphan sweep. Refuse until live positions are flat.
                val openLive = com.signalscope.app.data.BinanceLiveStore
                    .load(applicationContext).trades.filter { it.status == "OPEN" }
                if (openLive.isNotEmpty()) {
                    return gson.toJson(mapOf("ok" to false, "error" to
                        "Refused: ${openLive.size} REAL Binance position(s) still open (" +
                        openLive.joinToString(", ") { it.symbol } +
                        "). Resetting paper now would desync the live ledger — " +
                        "let them close (or sell manually) first."))
                }
                com.signalscope.app.data.StrategyLabStore.resetPaperState(applicationContext, BN)
                gson.toJson(mapOf("ok" to true))
            } catch (e: Exception) {
                "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        @JavascriptInterface
        fun binanceLabRunNow() {
            com.signalscope.app.trading.StrategyLabRunner.runAll(applicationContext, 0L, BN) {
                deliverAiCallback("window.onStrategyLabUpdate", "{}")
            }
        }

        @JavascriptInterface
        fun binanceLabBacktestPickCsv() {
            backtestNs = BN
            runOnUiThread {
                try { backtestCsvPicker.launch(arrayOf("*/*")) }
                catch (e: Exception) { Log.e(TAG, "csv picker launch failed", e) }
            }
        }

        @JavascriptInterface
        fun binanceLabPreview() {
            thread(isDaemon = true) {
                val out = try {
                    gson.toJson(mapOf("ok" to true,
                        "symbols" to com.signalscope.app.trading.StrategyLabRunner.preview(applicationContext, BN)))
                } catch (e: Exception) {
                    gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "preview failed")))
                }
                deliverAiCallback("window.onStrategyLabPreview", out)
            }
        }

        @JavascriptInterface
        fun binanceLabBackfill() {
            thread(isDaemon = true) {
                try { com.signalscope.app.trading.StrategyLabRunner.backfill(applicationContext, BN) }
                catch (e: Exception) { Log.e(TAG, "binance backfill failed", e) }
                deliverAiCallback("window.onStrategyLabUpdate", "{}")
            }
        }

        @JavascriptInterface
        fun binanceLabExportReport(): Boolean {
            if (lastBacktestReport == null) return false
            runOnUiThread {
                try { reportExportLauncher.launch("signalscope_binance_report_${System.currentTimeMillis()}.csv") }
                catch (e: Exception) { Log.e(TAG, "report export launch failed", e) }
            }
            return true
        }

        // ── GERMANY (Xetra / T212) STRATEGY LAB — namespace "de", PAPER + optional
        // T212 live mirror. Same engine/store/runner as US, but 15-min .DE candles
        // and Xetra tickers. Mirrors the strategyLab* bridges with ns="de".
        private val DE = "de"

        @JavascriptInterface
        fun deLabGetState(): String {
            return try {
                val s = com.signalscope.app.data.StrategyLabStore.load(applicationContext, DE)
                val cfg = com.signalscope.app.data.StrategyLabStore.activeConfig(applicationContext, DE)
                val closed = s.trades.filter { it.status == "CLOSED" }
                val wins = closed.count { it.pctReturn > 0 }
                val board = mapOf(
                    "open" to s.trades.count { it.status == "OPEN" }, "closed" to closed.size,
                    "wins" to wins, "losses" to closed.count { it.pctReturn < 0 },
                    "totalPnl" to closed.sumOf { it.pnlNotional }, "totalPct" to closed.sumOf { it.pctReturn })
                val t212Live = com.signalscope.app.data.T212LiveStore.load(applicationContext)
                gson.toJson(mapOf(
                    "enabled" to s.enabled, "source" to s.configSource, "summary" to cfg.summary(),
                    "lastRunAt" to s.lastRunAt, "enrolled" to s.enrolled, "trades" to s.trades,
                    "audit" to s.audit, "statuses" to s.statuses, "scoreboard" to board,
                    "liveMirror" to mapOf(
                        "broker" to "t212", "unit" to "",
                        // No global switches any more: whether real orders can
                        // happen is decided per algo. These two stay in the payload
                        // only so the panel keeps rendering, and report the truth.
                        "enabled" to config.hasT212Credentials,
                        "frozen" to false,
                        "configured" to config.hasT212Credentials,
                        "quoteOrderSize" to config.t212LivePerTradeAmount,
                        "open" to t212Live.trades.count { it.status == "OPEN" },
                        "closed" to t212Live.trades.count { it.status == "CLOSED" },
                        "errors" to t212Live.trades.count { it.status == "ERROR" || it.error.isNotBlank() },
                        "pnlQuote" to 0.0, "trades" to t212Live.trades)))
            } catch (e: Exception) {
                Log.e(TAG, "deLabGetState failed", e)
                "{\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
            }
        }

        @JavascriptInterface
        fun deLabSetEnabled(on: Boolean): Boolean {
            return try {
                com.signalscope.app.data.StrategyLabStore.setEnabled(applicationContext, on, DE)
                com.signalscope.app.service.StrategyLabScheduler.ensureScheduled(applicationContext)
                if (on) com.signalscope.app.trading.StrategyLabRunner.runAll(applicationContext, 0L, DE) {
                    deliverAiCallback("window.onStrategyLabUpdate", "{}")
                }
                on
            } catch (e: Exception) { Log.e(TAG, "deLabSetEnabled failed", e); false }
        }

        @JavascriptInterface
        fun deLabEnroll(symbol: String, exchange: String): String {
            return try {
                val norm = symbol.trim().uppercase()
                if (!Regex("^[A-Z0-9.\\-]{1,12}$").matches(norm)) {
                    return gson.toJson(mapOf("ok" to false,
                        "error" to "\"$symbol\" isn't a valid ticker — letters/digits only (e.g. SAP, SIE, ALV)."))
                }
                val s = com.signalscope.app.data.StrategyLabStore.enroll(applicationContext, norm, "DE", DE)
                com.signalscope.app.service.StrategyLabScheduler.ensureScheduled(applicationContext)
                gson.toJson(mapOf("ok" to true, "count" to s.enrolled.size))
            } catch (e: Exception) { "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}" }
        }

        @JavascriptInterface
        fun deLabUnenroll(symbol: String): String {
            return try {
                val s = com.signalscope.app.data.StrategyLabStore.unenroll(applicationContext, symbol, DE)
                com.signalscope.app.service.StrategyLabScheduler.ensureScheduled(applicationContext)
                gson.toJson(mapOf("ok" to true, "count" to s.enrolled.size))
            } catch (e: Exception) { "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}" }
        }

        @JavascriptInterface
        fun deLabUploadConfig(jsonText: String, filename: String = "uploaded"): String {
            return try {
                when (val r = com.signalscope.app.trading.StrategyLabConfig.fromJson(jsonText, filename)) {
                    is com.signalscope.app.trading.StrategyLabConfig.Companion.ParseResult.Ok -> {
                        com.signalscope.app.data.StrategyLabStore.setConfig(applicationContext, jsonText, filename, DE)
                        gson.toJson(mapOf("ok" to true, "summary" to r.config.summary()))
                    }
                    is com.signalscope.app.trading.StrategyLabConfig.Companion.ParseResult.Rejected ->
                        gson.toJson(mapOf("ok" to false, "error" to r.reason))
                }
            } catch (e: Exception) { "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}" }
        }

        @JavascriptInterface
        fun deLabResetConfig(): String {
            return try {
                com.signalscope.app.data.StrategyLabStore.resetToPreset(applicationContext, DE)
                gson.toJson(mapOf("ok" to true, "summary" to com.signalscope.app.data.StrategyLabStore.activeConfig(applicationContext, DE).summary()))
            } catch (e: Exception) { "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}" }
        }

        @JavascriptInterface
        fun deLabResetPaper(): String {
            return try {
                com.signalscope.app.data.StrategyLabStore.resetPaperState(applicationContext, DE)
                gson.toJson(mapOf("ok" to true))
            } catch (e: Exception) { "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}" }
        }

        @JavascriptInterface
        fun deLabRunNow() {
            com.signalscope.app.trading.StrategyLabRunner.runAll(applicationContext, 0L, DE) {
                deliverAiCallback("window.onStrategyLabUpdate", "{}")
            }
        }

        @JavascriptInterface
        fun deLabBacktestPickCsv() {
            backtestNs = DE
            runOnUiThread {
                try { backtestCsvPicker.launch(arrayOf("*/*")) }
                catch (e: Exception) { Log.e(TAG, "csv picker launch failed", e) }
            }
        }

        @JavascriptInterface
        fun deLabPreview() {
            thread(isDaemon = true) {
                val out = try {
                    gson.toJson(mapOf("ok" to true,
                        "symbols" to com.signalscope.app.trading.StrategyLabRunner.preview(applicationContext, DE)))
                } catch (e: Exception) { gson.toJson(mapOf("ok" to false, "error" to (e.message ?: "preview failed"))) }
                deliverAiCallback("window.onStrategyLabPreview", out)
            }
        }

        @JavascriptInterface
        fun deLabBackfill() {
            thread(isDaemon = true) {
                try { com.signalscope.app.trading.StrategyLabRunner.backfill(applicationContext, DE) }
                catch (e: Exception) { Log.e(TAG, "de backfill failed", e) }
                deliverAiCallback("window.onStrategyLabUpdate", "{}")
            }
        }

        @JavascriptInterface
        fun deLabExportReport(): Boolean {
            if (lastBacktestReport == null) return false
            runOnUiThread {
                try { reportExportLauncher.launch("signalscope_de_report_${System.currentTimeMillis()}.csv") }
                catch (e: Exception) { Log.e(TAG, "report export launch failed", e) }
            }
            return true
        }

        /** Re-fetch + re-score every watchlist entry. Result delivered async via
         *  window.onWatchlistRefreshed(json) — same pattern as scan callbacks. */
        @JavascriptInterface
        fun refreshWatchlist() {
            refreshWatchlistInternal(null)
        }

        @JavascriptInterface
        fun refreshWatchlistForSpace(space: String) {
            refreshWatchlistInternal(space)
        }

        private fun refreshWatchlistInternal(space: String?) {
            thread(isDaemon = true) {
                val resultJson: String = try {
                    val normalizedSpace = space?.trim()?.uppercase()
                        ?.takeIf { it == "IN" || it == "US" || it == "DE" }
                    val allEntries = com.signalscope.app.data.WatchlistStore.load(applicationContext)
                    val entries = if (normalizedSpace == null) {
                        allEntries
                    } else {
                        allEntries.filter { watchlistEntryMatchesSpace(it, normalizedSpace) }
                    }
                    val weights = config.scoringWeights
                    val results = entries.map { e ->
                        try {
                            val resolved = YahooFinanceClient.toYahooSymbol(e.symbol, e.exchange)
                            val cr = com.signalscope.app.network.YahooFinanceClient
                                .fetchCandles(resolved, e.exchange)
                            if (cr is com.signalscope.app.network.YahooFinanceClient.CandleResult.Success) {
                                val displayName = cr.name?.trim()?.takeIf { it.isNotBlank() } ?: resolved
                                val a = com.signalscope.app.util.StockAnalyzer.analyze(
                                    candles = cr.candles, symbol = resolved, name = displayName,
                                    token = resolved, minAvgVolume = 0, w = weights
                                )
                                if (a != null) {
                                    // Buy-GTT trigger formula: support * 1.005 (slightly above
                                    // support to ensure fill). Clamped to Kite range
                                    // [LTP×0.75, LTP×0.999]. ATR-based candidate stored as
                                    // alternative for the trace, but support wins by default.
                                    val supportCand = if (a.support > 0) a.support * 1.005 else 0.0
                                    val triggerRaw = supportCand
                                    val trigger = Math.round(
                                        triggerRaw.coerceIn(a.price * 0.75, a.price * 0.999) * 20.0
                                    ) / 20.0
                                    val snap = com.signalscope.app.data.WatchlistStore.Snapshot(
                                        ltp = a.price, support = a.support, resistance = a.resistance,
                                        atr = a.atr, cherryPoints = a.cherryPoints, cherrySignal = a.cherrySignal,
                                        pullbackScore = a.pullbackScore, pullbackSignal = a.pullbackSignal,
                                        momentumScore = a.momentumScore, momentumSignal = a.momentumSignal,
                                        entryMode = a.entryMode,
                                        proposedTrigger = trigger, capturedAt = System.currentTimeMillis()
                                    )
                                    com.signalscope.app.data.WatchlistStore.update(
                                        applicationContext,
                                        e.symbol,
                                        exchange = e.exchange,
                                        lastSnapshot = snap
                                    )
                                    mapOf("symbol" to e.symbol, "exchange" to e.exchange, "ok" to true, "snap" to snap)
                                } else {
                                    mapOf("symbol" to e.symbol, "exchange" to e.exchange, "ok" to false, "error" to "insufficient candles")
                                }
                            } else {
                                mapOf("symbol" to e.symbol, "exchange" to e.exchange, "ok" to false, "error" to "fetch failed")
                            }
                        } catch (ex: Exception) {
                            mapOf("symbol" to e.symbol, "exchange" to e.exchange, "ok" to false, "error" to (ex.message ?: "unknown"))
                        }
                    }
                    com.google.gson.Gson().toJson(mapOf("ok" to true, "space" to normalizedSpace, "results" to results))
                } catch (e: Exception) {
                    Log.e(TAG, "refreshWatchlist failed", e)
                    "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\"}"
                }
                deliverAiCallback("window.onWatchlistRefreshed", resultJson)
            }
        }

        /** Place a single-leg BUY GTT for a watchlist symbol. Trigger price is
         *  the dip-target (e.g. support×1.005); limit price is auto-derived
         *  with a small upward pad so the order fills. Result delivered via
         *  the existing onOcoGttResult callback (same shape as create/sweep). */
        @JavascriptInterface
        fun createBuyGtt(symbol: String, exchange: String, qty: Int, trigger: Double, ltp: Double) {
            thread(isDaemon = true) {
                val text: String = try {
                    val sym = symbol.trim().uppercase()
                    if (qty <= 0) throw IllegalArgumentException("qty must be > 0")
                    if (trigger <= 0 || ltp <= 0) throw IllegalArgumentException("trigger / ltp must be > 0")
                    // Kite hard limit: BUY trigger must sit in [ltp×0.75, ltp×0.999]
                    val trig = Math.round(trigger.coerceIn(ltp * 0.75, ltp * 0.999) * 20.0) / 20.0
                    // Limit pad: 0.5% above trigger so the buy actually fills on the
                    // dip-bounce (a precise-trigger limit order often misses by a tick).
                    val limit = Math.round(trig * 1.005 * 20.0) / 20.0
                    val zc = com.signalscope.app.network.ZerodhaClient(config)
                    val orders = listOf(
                        com.signalscope.app.network.ZerodhaClient.GttOrderSpec(
                            transactionType = "BUY",
                            quantity = qty,
                            price = limit,
                            product = "CNC",
                            orderType = "LIMIT"
                        )
                    )
                    when (val r = zc.createGttTrigger(
                        type = "single",
                        exchange = exchange,
                        tradingsymbol = sym,
                        triggerValues = listOf(trig),
                        lastPrice = ltp,
                        orders = orders
                    )) {
                        is com.signalscope.app.network.ZerodhaClient.GttCreateResult.Success ->
                            "{\"ok\":true,\"id\":${r.id},\"trigger\":$trig,\"limit\":$limit,\"kind\":\"BUY\",\"symbol\":\"$sym\"}"
                        is com.signalscope.app.network.ZerodhaClient.GttCreateResult.Failure ->
                            "{\"ok\":false,\"error\":\"${r.message.replace("\"","")}\",\"kind\":\"BUY\",\"symbol\":\"$sym\"}"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "createBuyGtt failed", e)
                    "{\"ok\":false,\"error\":\"${(e.message ?: "unknown").replace("\"","")}\",\"kind\":\"BUY\",\"symbol\":\"$symbol\"}"
                }
                deliverAiCallback("window.onOcoGttResult", text)
            }
        }

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
        @JvmOverloads
        @JavascriptInterface
        fun analyzePullback(symbol: String, price: Double, ema21PctDiff: Double,
                            macdPhase: String, profitScore: Int, protectScore: Int,
                            rsi: Double = 0.0, adx: Double = 0.0,
                            support: Double = 0.0, resistance: Double = 0.0,
                            name: String = "") {
            thread(isDaemon = true) {
                // Guard the ENTIRE body — any unexpected exception must still
                // deliver a callback, otherwise the spinner spins forever.
                val text: String = try {
                    val aiCtx = StockAiClient.MarketContext.fromSpace(config.currentSpace)
                    val result = StockAiClient.analyzePullback(
                        config, symbol, price, ema21PctDiff, macdPhase, profitScore, protectScore,
                        if (rsi > 0) rsi else null,
                        if (adx > 0) adx else null,
                        if (support > 0) support else null,
                        if (resistance > 0) resistance else null,
                        ctx = aiCtx,
                        name = name.ifBlank { null }
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
        @JvmOverloads
        @JavascriptInterface
        fun askAboutCompany(symbol: String, price: Double, userQuestion: String, previousAnalysis: String = "", name: String = "") {
            thread(isDaemon = true) {
                val text: String = try {
                    val aiCtx = StockAiClient.MarketContext.fromSpace(config.currentSpace)
                    val result = StockAiClient.askFollowUpQuestion(
                        config, symbol, userQuestion, price, previousAnalysis,
                        ctx = aiCtx,
                        name = name.ifBlank { null }
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
        fun analyzeOutlook(symbol: String, price: Double, cherryPoints: Int,
                           profitScore: Int, protectScore: Int, sellIntent: String,
                           macdPhase: String, macdSlope: Double, rsi: Double,
                           sma200: Double, ema21PctDiff: Double, rrRatio: Double, priceVel: Double,
                           isInPortfolio: Boolean = false, name: String = "") {
            thread(isDaemon = true) {
                val text: String = try {
                    val aiCtx = StockAiClient.MarketContext.fromSpace(config.currentSpace)
                    val result = StockAiClient.analyzeOutlook(
                        config, symbol, price, cherryPoints, profitScore, protectScore, sellIntent,
                        macdPhase, macdSlope, if (rsi == 0.0) null else rsi,
                        if (sma200 == 0.0) null else sma200,
                        ema21PctDiff, rrRatio, priceVel, isInPortfolio,
                        ctx = aiCtx,
                        name = name.ifBlank { null }
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

        /** A source under the stock outlook, opened in the phone's browser. Only
         *  the article links Google News itself sent — a headline or AI reply
         *  can't use this to send the user anywhere else. */
        @JavascriptInterface
        fun openNewsLink(url: String) {
            val uri = try { android.net.Uri.parse(url) } catch (e: Exception) { return }
            if (uri.scheme != "https" || uri.host != "news.google.com") return
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this@MainActivity, "No browser found to open the article.",
                        android.widget.Toast.LENGTH_LONG).show()
                }
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
                    val aiCtx = StockAiClient.MarketContext.fromSpace(config.currentSpace)
                    val result = StockAiClient.analyseDeepPullbackBatch(config, stocksJson, ctx = aiCtx)
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
         * Portfolio News Scan — batched criticality triage of the current
         * space's holdings. Fetches recent news per holding + one LLM call
         * that returns strict JSON ({summary, stocks:[{symbol,verdict,conclusion}]}).
         * Delivered to window.onPortfolioNewsResult. Places no orders.
         *   stocksJson: [{symbol, name}]
         */
        @JavascriptInterface
        fun scanPortfolioNews(stocksJson: String) {
            thread(isDaemon = true) {
                val out: String = try {
                    val aiCtx = StockAiClient.MarketContext.fromSpace(config.currentSpace)
                    when (val r = StockAiClient.analysePortfolioNews(config, stocksJson, aiCtx)) {
                        is StockAiClient.AiResult.Success -> r.text
                        is StockAiClient.AiResult.Error ->
                            "{\"error\":\"${r.message.replace("\"", "'")}\"}"
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "scanPortfolioNews crashed", e)
                    "{\"error\":\"${(e.message ?: e.javaClass.simpleName).replace("\"", "'")}\"}"
                }
                deliverAiCallback("window.onPortfolioNewsResult", out)
            }
        }

        /**
         * Returns the merged recent-alerts list as a JSON array string.
         * Combines:
         *   - ScanService intraday alerts (from lastZerodhaResult.alerts)
         *   - DPM watchdog alerts (from macdWatchdogAlerts)
         *   - Discovery smooth-turn buy timing alerts (from smoothTurnAlerts)
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
                val smoothTurns = synchronized(ScanServiceResultHolder.smoothTurnAlerts) {
                    ScanServiceResultHolder.smoothTurnAlerts.toList()
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
                    com.signalscope.app.data.AlertType.SMOOTH_BUY_TURN -> 6
                    com.signalscope.app.data.AlertType.GOLDEN_BUY      -> 7
                    com.signalscope.app.data.AlertType.STRONG_BUY      -> 8
                    else -> 99
                }
                val merged = (intraday + watchdog + softSniper + smoothTurns)
                    .distinctBy { "${it.symbol}|${it.alertType}" }
                    .sortedBy { rank(it.alertType) }
                    .map { a -> mapOf(
                        "symbol" to a.symbol,
                        "name" to a.name,
                        "alertType" to a.alertType.name,
                        "message" to a.message,
                        "sellScore" to a.sellScore,
                        "buyScore" to a.cherryPoints,
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

        @JavascriptInterface
        fun fetchGttTriggersAsync() {
            thread(isDaemon = true) {
                deliverAiCallback("window.onGttTriggersResult", getGttTriggersJson())
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

        /** Delete/cancel several GTTs by id. Used for stale non-portfolio cleanup. */
        @JavascriptInterface
        fun deleteGttTriggers(idsJson: String): String {
            thread(isDaemon = true) {
                val deleted = mutableListOf<Long>()
                val failed = mutableListOf<String>()
                try {
                    val ids = org.json.JSONArray(idsJson)
                    val zc = com.signalscope.app.network.ZerodhaClient(config)
                    for (i in 0 until ids.length()) {
                        val id = ids.optLong(i, 0L)
                        if (id <= 0L) continue
                        when (val r = zc.deleteGttTrigger(id)) {
                            is com.signalscope.app.network.ZerodhaClient.GttDeleteResult.Success -> deleted.add(r.id)
                            is com.signalscope.app.network.ZerodhaClient.GttDeleteResult.Failure ->
                                failed.add("{\"id\":$id,\"error\":\"${r.message.replace("\"","")}\"}")
                        }
                        if (i < ids.length() - 1) {
                            try { Thread.sleep(400L) } catch (_: InterruptedException) {}
                        }
                    }
                    deliverAiCallback(
                        "window.onBulkStaleGttDeleteResult",
                        "{\"ok\":${failed.isEmpty()},\"deleted\":[${deleted.joinToString(",")}],\"failed\":[${failed.joinToString(",")}],\"total\":${ids.length()}}"
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "deleteGttTriggers failed", e)
                    deliverAiCallback(
                        "window.onBulkStaleGttDeleteResult",
                        "{\"ok\":false,\"deleted\":[${deleted.joinToString(",")}],\"failed\":[${failed.joinToString(",")}],\"error\":\"${(e.message ?: "exception").replace("\"","")}\"}"
                    )
                }
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
                    val zc = com.signalscope.app.network.ZerodhaClient(config)
                    val effectiveLastPrice = when (val fresh = zc.fetchLtp(exchange, symbol)) {
                        is com.signalscope.app.network.ZerodhaClient.LtpResult.Success -> fresh.lastPrice
                        is com.signalscope.app.network.ZerodhaClient.LtpResult.Expired -> throw IllegalStateException(fresh.message)
                        is com.signalscope.app.network.ZerodhaClient.LtpResult.Failure -> lastPrice
                    }
                    // Clamp triggers only to Kite's ±25% hard limit. No 5% floor —
                    // user wants SL as tight as possible to minimize loss beyond volatility.
                    // Tiny 0.1% pad keeps SL strictly below LTP so it doesn't fire instantly.
                    val slMax = lastPrice * 0.999  // strictly below LTP (≥0.1% below)
                    val slMin = effectiveLastPrice * 0.75   // at most 25% below (Kite hard limit)
                    val tgMin = lastPrice * 1.001  // strictly above LTP (≥0.1% above)
                    val tgMax = lastPrice * 1.25   // at most 25% above (Kite hard limit)
                    val safeSlMax = effectiveLastPrice * 0.999
                    val safeSlMin = effectiveLastPrice * 0.75
                    val safeTgMin = effectiveLastPrice * 1.001
                    val safeTgMax = effectiveLastPrice * 1.25

                    var sl = slTrigger.coerceIn(safeSlMin, safeSlMax)
                    var tg = targetTrigger.coerceIn(safeTgMin, safeTgMax)
                    // Round to 2 decimals — Kite rejects anything finer than a tick
                    sl = Math.round(sl * 20.0) / 20.0   // 0.05 ticks
                    tg = Math.round(tg * 20.0) / 20.0

                    val orders = listOf(
                        com.signalscope.app.network.ZerodhaClient.GttOrderSpec("SELL", quantity, sl),
                        com.signalscope.app.network.ZerodhaClient.GttOrderSpec("SELL", quantity, tg)
                    )
                    when (val r = zc.createGttTrigger(
                        type = "two-leg",
                        exchange = exchange,
                        tradingsymbol = symbol,
                        triggerValues = listOf(sl, tg),
                        lastPrice = effectiveLastPrice,
                        orders = orders
                    )) {
                        is com.signalscope.app.network.ZerodhaClient.GttCreateResult.Success ->
                            "{\"ok\":true,\"id\":${r.id},\"sl\":$sl,\"target\":$tg,\"ltp\":$effectiveLastPrice,\"symbol\":\"$symbol\"}"
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
                    if (lastPrice <= 0) throw IllegalArgumentException("lastPrice invalid")
                    val zc = com.signalscope.app.network.ZerodhaClient(config)
                    val effectiveLastPrice = when (val fresh = zc.fetchLtp(exchange, symbol)) {
                        is com.signalscope.app.network.ZerodhaClient.LtpResult.Success -> fresh.lastPrice
                        is com.signalscope.app.network.ZerodhaClient.LtpResult.Expired -> throw IllegalStateException(fresh.message)
                        is com.signalscope.app.network.ZerodhaClient.LtpResult.Failure -> lastPrice
                    }
                    val pct = (triggerPrice - effectiveLastPrice) / effectiveLastPrice
                    // No 5% minimum floor — user wants tight SLs. Only enforce side
                    // of LTP (with a tiny 0.1% pad) and the ±25% Kite hard limit.
                    if (kind == "SL" && pct >= -0.001) throw IllegalArgumentException("SL trigger must be strictly below LTP")
                    if (kind == "TARGET" && pct <= 0.001) throw IllegalArgumentException("Target trigger must be strictly above LTP")
                    if (Math.abs(pct) > 0.25) throw IllegalArgumentException("Trigger beyond ±25% of LTP (Kite hard limit)")
                    val trig = Math.round(triggerPrice * 20.0) / 20.0
                    val orders = listOf(
                        com.signalscope.app.network.ZerodhaClient.GttOrderSpec("SELL", quantity, trig)
                    )
                    when (val r = zc.createGttTrigger(
                        type = "single", exchange = exchange, tradingsymbol = symbol,
                        triggerValues = listOf(trig), lastPrice = effectiveLastPrice, orders = orders
                    )) {
                        is com.signalscope.app.network.ZerodhaClient.GttCreateResult.Success ->
                            "{\"ok\":true,\"id\":${r.id},\"trigger\":$trig,\"kind\":\"$kind\",\"ltp\":$effectiveLastPrice,\"symbol\":\"$symbol\"}"
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
         *   window.onBulkGttComplete('{"created":[...], "failed":[...], "skipped":[...], "total":T}')
         */
        @JavascriptInterface
        fun bulkCreateOcoSellGtts(stocksJson: String) {
            thread(isDaemon = true) {
                val created = mutableListOf<String>()
                val failed = mutableListOf<String>()
                val skipped = mutableListOf<String>()
                try {
                    val root = org.json.JSONTokener(stocksJson).nextValue()
                    val arr = when (root) {
                        is org.json.JSONArray -> root
                        is org.json.JSONObject -> root.optJSONArray("stocks") ?: org.json.JSONArray()
                        else -> org.json.JSONArray()
                    }
                    val staleIds = (root as? org.json.JSONObject)?.optJSONArray("staleIds")
                    val total = arr.length()
                    val zc = com.signalscope.app.network.ZerodhaClient(config)
                    var sweptCount = 0
                    var staleDeletedCount = 0
                    val pendingStaleIds = mutableListOf<Long>()

                    if (staleIds != null) {
                        for (j in 0 until staleIds.length()) {
                            val staleId = staleIds.optLong(j, 0L)
                            if (staleId <= 0L) continue
                            pendingStaleIds.add(staleId)
                        }
                    }

                    // Fetch all active SELL GTTs once. A symbol is swept only after
                    // confirming the proposed bulk OCO does not downgrade an existing
                    // higher SL or target.
                    val existingBySymbol: Map<String, List<com.signalscope.app.network.ZerodhaClient.GttTrigger>> = try {
                        when (val r = zc.fetchGttTriggers()) {
                            is com.signalscope.app.network.ZerodhaClient.GttListResult.Success ->
                                r.triggers
                                    .filter { it.status == "active" && it.transactionType == "SELL" }
                                    .groupBy { it.tradingsymbol }
                            else -> emptyMap()
                        }
                    } catch (_: Throwable) { emptyMap() }

                    for (i in 0 until total) {
                        val o = arr.getJSONObject(i)
                        val symbol = o.getString("symbol")
                        val exchange = o.optString("exchange", "NSE")
                        val qty = o.getInt("qty")
                        var ltp = o.getDouble("ltp")
                        var sl = o.getDouble("sl")
                        var tg = o.getDouble("tgt")

                        val progressText: String = try {
                            if (qty <= 0) throw IllegalArgumentException("qty ≤ 0")
                            ltp = when (val fresh = zc.fetchLtp(exchange, symbol)) {
                                is com.signalscope.app.network.ZerodhaClient.LtpResult.Success -> fresh.lastPrice
                                is com.signalscope.app.network.ZerodhaClient.LtpResult.Expired -> throw IllegalStateException(fresh.message)
                                is com.signalscope.app.network.ZerodhaClient.LtpResult.Failure -> ltp
                            }
                            if (ltp <= 0) throw IllegalArgumentException("bad ltp")
                            // ±25% Kite hard limit only. 0.1% pad keeps trigger
                            // strictly on its correct side of LTP.
                            sl = sl.coerceIn(ltp * 0.75, ltp * 0.999)
                            tg = tg.coerceIn(ltp * 1.001, ltp * 1.25)
                            sl = Math.round(sl * 20.0) / 20.0
                            tg = Math.round(tg * 20.0) / 20.0

                            val existingForSymbol = existingBySymbol[symbol].orEmpty()
                            val protectedReason = existingForSymbol.asSequence().mapNotNull { g ->
                                val existingSl = when {
                                    g.triggerType == "two-leg" -> g.triggerPrice
                                    g.triggerPrice > 0.0 && g.triggerPrice < ltp -> g.triggerPrice
                                    else -> null
                                }
                                val existingTarget = when {
                                    g.triggerType == "two-leg" -> g.triggerPriceAux
                                    g.triggerPrice > ltp -> g.triggerPrice
                                    else -> null
                                }
                                val betterSl = existingSl != null && existingSl > sl + 0.01
                                val betterTarget = existingTarget != null && existingTarget > tg + 0.01
                                when {
                                    betterSl && betterTarget -> "existing SL $existingSl and target $existingTarget are higher"
                                    betterSl -> "existing SL $existingSl is higher"
                                    betterTarget -> "existing target $existingTarget is higher"
                                    else -> null
                                }
                            }.firstOrNull()

                            if (protectedReason != null) {
                                val msg = protectedReason.replace("\"","")
                                skipped.add("{\"symbol\":\"$symbol\",\"reason\":\"$msg\",\"sl\":$sl,\"tgt\":$tg}")
                                "{\"i\":${i+1},\"total\":$total,\"symbol\":\"$symbol\",\"status\":\"skip\",\"error\":\"$msg\"}"
                            } else {
                                val orders = listOf(
                                    com.signalscope.app.network.ZerodhaClient.GttOrderSpec("SELL", qty, sl),
                                    com.signalscope.app.network.ZerodhaClient.GttOrderSpec("SELL", qty, tg)
                                )
                                when (val r = zc.createGttTrigger(
                                    type = "two-leg", exchange = exchange, tradingsymbol = symbol,
                                    triggerValues = listOf(sl, tg), lastPrice = ltp, orders = orders
                                )) {
                                    is com.signalscope.app.network.ZerodhaClient.GttCreateResult.Success -> {
                                        existingForSymbol.forEach { old ->
                                            try {
                                                zc.deleteGttTrigger(old.id)
                                                sweptCount++
                                                Thread.sleep(400L)
                                            } catch (e: Throwable) {
                                                Log.w(TAG, "post-create sweep: delete GTT ${old.id} for $symbol failed: ${e.message}")
                                            }
                                        }
                                        created.add("{\"symbol\":\"$symbol\",\"id\":${r.id},\"sl\":$sl,\"tgt\":$tg,\"ltp\":$ltp}")
                                        "{\"i\":${i+1},\"total\":$total,\"symbol\":\"$symbol\",\"status\":\"ok\",\"id\":${r.id},\"sl\":$sl,\"tgt\":$tg,\"ltp\":$ltp}"
                                    }
                                    is com.signalscope.app.network.ZerodhaClient.GttCreateResult.Failure -> {
                                        failed.add("{\"symbol\":\"$symbol\",\"error\":\"${r.message.replace("\"","")}\"}")
                                        "{\"i\":${i+1},\"total\":$total,\"symbol\":\"$symbol\",\"status\":\"fail\",\"error\":\"${r.message.replace("\"","")}\"}"
                                    }
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
                    if (pendingStaleIds.isNotEmpty() && created.isNotEmpty()) {
                        pendingStaleIds.forEach { staleId ->
                            try {
                                zc.deleteGttTrigger(staleId)
                                sweptCount++
                                staleDeletedCount++
                                Thread.sleep(400L)
                            } catch (e: Throwable) {
                                Log.w(TAG, "post-create stale sweep: delete GTT $staleId failed: ${e.message}")
                            }
                        }
                    }
                    val summary = "{\"created\":[${created.joinToString(",")}],\"failed\":[${failed.joinToString(",")}],\"skipped\":[${skipped.joinToString(",")}],\"total\":$total,\"swept\":$sweptCount,\"staleDeleted\":$staleDeletedCount}"
                    deliverAiCallback("window.onBulkGttComplete", summary)
                } catch (e: Throwable) {
                    Log.e(TAG, "bulkCreateOcoSellGtts failed", e)
                    val msg = (e.message ?: "exception").replace("\"","")
                    deliverAiCallback("window.onBulkGttComplete",
                        "{\"created\":[${created.joinToString(",")}],\"failed\":[${failed.joinToString(",")}],\"skipped\":[${skipped.joinToString(",")}],\"total\":0,\"error\":\"$msg\"}")
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

        btnDax.setOnClickListener {
            startDiscoveryScan(ScanService.ACTION_DISCOVERY_DAX)
        }

        btnStop.setOnClickListener {
            stopDiscoveryScan()
        }

        // Initial visibility — hide foreign-market buttons in the wrong space.
        applySpaceVisibility()
    }

    /**
     * Show only the scan buttons relevant to the active workspace.
     *   IN  → 🇮🇳 Nifty 500 only        (NASDAQ + DAX hidden)
     *   US  → 🇺🇸 Nasdaq + 🇩🇪 DAX      (Nifty hidden — German user trades EU + US)
     *
     * Called once at startup and again from the JS bridge whenever the user
     * flips space via the hamburger drawer (setCurrentSpace).
     */
    private fun applySpaceVisibility() {
        val space = config.currentSpace
        btnNifty.visibility  = if (space == "IN") View.VISIBLE else View.GONE
        btnNasdaq.visibility = if (space == "US") View.VISIBLE else View.GONE
        btnDax.visibility    = if (space == "DE") View.VISIBLE else View.GONE
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
        // No longer switches portfolio monitoring on first — that is what left
        // it on after every scan. The scan starts the service by itself.
        lastDiscoveryPayloadPushAt = 0L
        lastDiscoveryPayloadStockCount = -1
        lastDiscoveryPayloadMarket = ""
        lastDiscoveryPayloadComplete = false
        // Discovery scans can take 15–40 minutes. On OEM-modded Androids
        // (MIUI, ColorOS, OneUI) the OS will kill the foreground service
        // mid-scan unless the app is exempt from battery optimization.
        // Prompting here is idempotent — Android shows nothing if already exempt.
        requestBatteryOptimizationExemption()
        ContextCompat.startForegroundService(this, ScanService.createIntent(this, action))
        btnStop.visibility = View.VISIBLE
        val market = when {
            action.contains("NIFTY") -> "NIFTY"
            action.contains("DAX") -> "DAX"
            else -> "NASDAQ 100"
        }
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

    private fun shouldPushDiscoveryPayload(result: DiscoveryScanResult, force: Boolean): Boolean {
        if (force) return true
        val now = android.os.SystemClock.uptimeMillis()
        val stockCount = result.allStocks.size
        if (result.market != lastDiscoveryPayloadMarket) return true
        if (result.isComplete && !lastDiscoveryPayloadComplete) return true
        if (stockCount - lastDiscoveryPayloadStockCount >= DISCOVERY_PUSH_MIN_STOCK_DELTA) return true
        return now - lastDiscoveryPayloadPushAt >= DISCOVERY_PUSH_MIN_INTERVAL_MS
    }

    private fun markDiscoveryPayloadPushed(result: DiscoveryScanResult) {
        lastDiscoveryPayloadPushAt = android.os.SystemClock.uptimeMillis()
        lastDiscoveryPayloadStockCount = result.allStocks.size
        lastDiscoveryPayloadMarket = result.market
        lastDiscoveryPayloadComplete = result.isComplete
    }

    private fun pushDiscoveryResultsToWebView(force: Boolean = false, thenJs: String? = null) {
        // Access the service's static result holder
        val result = ScanServiceResultHolder.lastDiscoveryResult
        val runThen = { if (thenJs != null) try { webView.evaluateJavascript(thenJs, null) } catch (_: Exception) {} }
        if (result == null) { runThen(); return }
        try {
            // No page yet: it asks for everything the moment it finishes loading.
            if (!pageReady) { runThen(); return }
            if (!shouldPushDiscoveryPayload(result, force)) { runThen(); return }
            // A scan in progress sends at most one update at a time; the next
            // poll carries the newer numbers anyway.
            if (!force && livePushInFlight) { runThen(); return }
            val complete = result.isComplete
            val sig = discoverySig(result, complete)
            if (complete && pushedDiscoverySig[result.market] == sig) { runThen(); return }
            markDiscoveryPayloadPushed(result)
            val gen = pageGen
            livePushInFlight = true
            discoveryPushExec.execute {
                try {
                    val json = gson.toJson(discoveryPayload(result))
                    // Escape for JS string
                    val escaped = json.replace("\\", "\\\\").replace("'", "\\'")
                    runOnUiThread {
                        try {
                            if (gen == pageGen) {
                                webView.evaluateJavascript("window.updateScanData('$escaped')", null)
                                if (complete) pushedDiscoverySig[result.market] = sig
                            }
                        } finally { livePushInFlight = false; runThen() }
                    }
                } catch (e: Exception) {
                    livePushInFlight = false
                    Log.e(TAG, "Error pushing results to WebView", e)
                    runOnUiThread { runThen() }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pushing results to WebView", e)
            runThen()
        }
    }

    private fun discoveryPayload(result: DiscoveryScanResult, forceComplete: Boolean = false): Map<String, Any?> {
        val scanning = !forceComplete && !result.isComplete
        return mapOf(
            "allStocks" to result.allStocks,
            "market" to result.market,
            "currency" to result.currency,
            "errors" to result.errors,
            "skipped" to result.skipped,
            "dropped" to result.dropped,
            "turnoverFloor" to result.turnoverFloor,
            "lastError" to result.lastError,
            "timestamp" to result.timestamp,
            "isScanning" to scanning,
            // Use discoveryProgress (stocks attempted = idx+1) so the app screen matches
            // the notification, which also counts attempts. totalScanned only counts
            // successfully analysed stocks, so it's always lower when rate-limits occur.
            "progress" to if (scanning) mapOf(
                "current" to ScanServiceResultHolder.discoveryProgress,
                "total" to result.totalSymbols
            ) else null
        )
    }

    private fun pushCachedDiscoveryResultsToWebViewAsync() {
        // Same rules as the live result: never before the page exists, never a
        // market this page already holds, and on the same single background
        // thread so sends cannot overtake each other.
        if (!pageReady) return
        val gen = pageGen
        discoveryPushExec.execute {
            try {
                val live = ScanServiceResultHolder.lastDiscoveryResult
                DiscoveryResultStore.loadAll(applicationContext).forEach { cached ->
                    if (live != null && live.market == cached.market) return@forEach
                    val sig = discoverySig(cached, true)
                    if (pushedDiscoverySig[cached.market] == sig) return@forEach
                    val json = gson.toJson(discoveryPayload(cached, forceComplete = true))
                    val escaped = json.replace("\\", "\\\\").replace("'", "\\'")
                    // One market at a time, so the drawing thread takes one bite
                    // and gets back to drawing between them.
                    runOnUiThread {
                        if (gen == pageGen) {
                            webView.evaluateJavascript("window.updateScanData('$escaped')", null)
                            pushedDiscoverySig[cached.market] = sig
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error pushing cached discovery results", e)
            }
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
                "price" to (a?.price ?: h.ltp),
                "pnl" to h.pnl,
                "dayChange" to h.dayChange,
                "dayChangePct" to h.dayChangePct,
                "invested" to h.invested,
                "currentVal" to h.currentVal,
                "totalReturnPct" to h.totalReturnPct,
                "source" to "zerodha",
                "verdict" to h.verdict,
                "buyScore" to (a?.cherryPoints ?: 0),
                "pullbackScore" to (a?.pullbackScore ?: 0),
                "pullbackSignal" to (a?.pullbackSignal ?: "—"),
                "momentumScore" to (a?.momentumScore ?: 0),
                "momentumSignal" to (a?.momentumSignal ?: "—"),
                "entryMode" to (a?.entryMode ?: "NONE"),
                "profitScore" to (a?.profitScore ?: 0),
                "protectScore" to (a?.protectScore ?: 0),
                "sellIntent" to (a?.sellIntent ?: "HOLD"),
                "sellScore" to (a?.sellScore ?: 0),
                "macdPhase" to (a?.macdPhase ?: "—"),
                "macdSlope" to (a?.macdSlope ?: 0.0),
                "macdSlopeAngle" to (a?.macdSlopeAngle ?: 0.0),
                "macdAccel" to (a?.macdAccel ?: 0.0),
                "macdPctl" to (a?.macdPctl ?: 50.0),
                "macdLowPct" to (a?.macdLowPct ?: 0.0),
                "macd" to (a?.macd ?: 0.0),
                "macdSignal" to (a?.macdSignal ?: 0.0),
                "macdHist" to (a?.macdHist ?: 0.0),
                "macd1yLow" to (a?.macd1yLow ?: 0.0),
                "macdCurve" to (a?.macdCurve ?: emptyList<Double>()),
                "smoothMacdCurve" to (a?.smoothMacdCurve ?: emptyList<Double>()),
                "smoothMacdSlope" to (a?.smoothMacdSlope ?: 0.0),
                "smoothMacdPrevSlope" to (a?.smoothMacdPrevSlope ?: 0.0),
                "smoothMacdAccel" to (a?.smoothMacdAccel ?: 0.0),
                "smoothMacdLowDistPct" to (a?.smoothMacdLowDistPct ?: 0.0),
                "smoothMacdHook" to (a?.smoothMacdHook ?: false),
                "smoothMacdPrevSavedSlope" to a?.smoothMacdPrevSavedSlope,
                "smoothMacdTurnedPositiveToday" to (a?.smoothMacdTurnedPositiveToday ?: false),
                "smoothMacdTurnSourceTimestamp" to (a?.smoothMacdTurnSourceTimestamp ?: 0L),
                "priceCurve" to (a?.priceCurve ?: emptyList<Double>()),
                "kalmanCurve" to (a?.kalmanCurve ?: emptyList<Double>()),
                "kalmanSlope" to (a?.kalmanSlope ?: 0.0),
                "kalmanTrendPct" to (a?.kalmanTrendPct ?: 0.0),
                "kalmanRegime" to (a?.kalmanRegime ?: "UNKNOWN"),
                "kalmanHorizonDays" to (a?.kalmanHorizonDays ?: 0),
                "rsi" to (a?.rsi),
                "adx" to (a?.adx ?: 0.0),
                "plusDi" to (a?.plusDi ?: 0.0),
                "minusDi" to (a?.minusDi ?: 0.0),
                "obv" to (a?.obv ?: 0.0),
                "sma200" to (a?.sma200),
                "sma200Slope" to (a?.sma200Slope ?: 0.0),
                "ema21" to (a?.ema21),
                "ema21Slope" to (a?.ema21Slope ?: 0.0),
                "ema21PctDiff" to (a?.ema21PctDiff ?: 0.0),
                "ema50" to (a?.ema50),
                "ema50Slope" to (a?.ema50Slope ?: 0.0),
                "ema200" to (a?.ema200),
                "ema200Slope" to (a?.ema200Slope ?: 0.0),
                "bbUpper" to (a?.bbUpper),
                "bbMid" to (a?.bbMid),
                "bbLower" to (a?.bbLower),
                "avgVol20" to (a?.avgVol20 ?: 0.0),
                "volume" to (a?.volume ?: 0.0),
                "priceVel" to (a?.priceVel ?: 0.0),
                "priceAccel" to (a?.priceAccel ?: 0.0),
                "priceRoc3" to (a?.priceRoc3 ?: 0.0),
                "upDays" to (a?.upDays ?: 0),
                "buySignal" to (a?.cherrySignal ?: "—"),
                "sellSignal" to (a?.sellSignal ?: "—"),
                "support" to (a?.support ?: 0.0),
                "resistance" to (a?.resistance ?: 0.0),
                "atr" to (a?.atr),
                "projectedCeiling" to (a?.projectedCeiling),
                "projectedFloor" to (a?.projectedFloor),
                "projectedMidpoint" to (a?.projectedMidpoint),
                "minBarsFilter" to (a?.minBarsFilter),
                "swingScore" to (a?.swingScore ?: 0),
                "longTermScore" to (a?.longTermScore ?: 0),
                "styleLabel" to (a?.styleLabel ?: "MIXED"),
                "styleReason" to (a?.styleReason ?: ""),
                "macdCycleDays" to (a?.macdCycleDays ?: 0.0),
                "returnAutocorr1" to (a?.returnAutocorr1 ?: 0.0),
                "styleScoreReport" to (a?.styleScoreReport ?: ""),
                "macdFlipFiredToday" to macdFlipFiredToday,
                "softSniperFiredToday" to softSniperFiredToday,
                "obvDivergence" to (a?.obvDivergence ?: false),
                "obvWeakness" to (a?.obvWeakness ?: false),
                "divHistBullish" to (a?.divHistBullish ?: false),
                "divHistBearish" to (a?.divHistBearish ?: false),
                "divVolBullish" to (a?.divVolBullish ?: false),
                "divVolBearish" to (a?.divVolBearish ?: false),
                "divDetail" to (a?.divDetail ?: ""),
                "volBullDryUp" to (a?.volBullDryUp ?: false),
                "volBearDryUp" to (a?.volBearDryUp ?: false),
                "volBullCapitulation" to (a?.volBullCapitulation ?: false),
                "volBearChurning" to (a?.volBearChurning ?: false),
                "volPatternDetail" to (a?.volPatternDetail ?: ""),
                "buyScoreReport" to (a?.cherryPointsReport ?: ""),
                "pullbackScoreReport" to (a?.pullbackScoreReport ?: ""),
                "momentumScoreReport" to (a?.momentumScoreReport ?: ""),
                "trailingPe" to (a?.trailingPe),
                "forwardPe" to (a?.forwardPe),
                "priceToBook" to (a?.priceToBook),
                "evToEbitda" to (a?.evToEbitda),
                "debtToEquity" to (a?.debtToEquity),
                "roce" to (a?.roce),
                "revenueGrowth" to (a?.revenueGrowth),
                "earningsGrowth" to (a?.earningsGrowth),
                "grossMargins" to (a?.grossMargins),
                "operatingMargins" to (a?.operatingMargins),
                "profitMargins" to (a?.profitMargins),
                "dividendYield" to (a?.dividendYield),
                "operatingCashflow" to (a?.operatingCashflow),
                "freeCashflow" to (a?.freeCashflow),
                "netIncome" to (a?.netIncome),
                "totalRevenue" to (a?.totalRevenue),
                "totalCash" to (a?.totalCash),
                "totalDebt" to (a?.totalDebt),
                "currentRatio" to (a?.currentRatio),
                "annualRevenueGrowth" to (a?.annualRevenueGrowth),
                "annualNetIncomeGrowth" to (a?.annualNetIncomeGrowth),
                "fiftyTwoWeekLow" to (a?.fiftyTwoWeekLow),
                "fiftyTwoWeekHigh" to (a?.fiftyTwoWeekHigh),
                "sharesOutstanding" to (a?.sharesOutstanding),
                "sector" to (a?.sector),
                "industry" to (a?.industry),
                "sectorMedianPe" to (a?.sectorMedianPe),
                "hasBuyback" to (a?.hasBuyback ?: false),
                "valueScore" to (a?.valueScore ?: 0),
                "valueRating" to (a?.valueRating ?: "N/A"),
                "valueScoreReport" to (a?.valueScoreReport ?: ""),
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
            updateLiveStreamSubscriptions()
        } catch (e: Exception) {
            Log.e(TAG, "Error pushing portfolio to WebView", e)
        }
    }

    // ═══════════════════════════════════════════════════════
    // SECTOR DATA — fetched from Yahoo Finance in background
    // ═══════════════════════════════════════════════════════

    private fun normalizeWatchlistEntry(
        symbol: String,
        exchange: String,
        notes: String
    ): WatchlistStore.Entry {
        val raw = symbol.trim().uppercase()
        val normalizedExchange = when {
            raw.endsWith(".NS") -> "NSE"
            raw.endsWith(".BO") -> "BSE"
            raw.endsWith(".DE") -> "DE"
            exchange.equals("US", ignoreCase = true) -> "US"
            exchange.equals("NASDAQ", ignoreCase = true) -> "NASDAQ"
            exchange.equals("NYSE", ignoreCase = true) -> "NYSE"
            exchange.equals("DE", ignoreCase = true) -> "DE"
            exchange.equals("XETRA", ignoreCase = true) -> "DE"
            exchange.equals("BSE", ignoreCase = true) -> "BSE"
            else -> "NSE"
        }
        val storedSymbol = when (normalizedExchange) {
            "NSE" -> raw.removeSuffix(".NS")
            "BSE" -> raw.removeSuffix(".BO")
            else -> raw
        }
        return WatchlistStore.Entry(symbol = storedSymbol, exchange = normalizedExchange, notes = notes)
    }

    private fun collectLiveStreamSymbols(): Set<String> {
        val symbols = linkedSetOf<String>()
        ScanServiceResultHolder.lastZerodhaResult?.holdings?.forEach { holding ->
            symbols.add(YahooFinanceClient.toYahooSymbol(holding.symbol, holding.exchange))
        }
        if (ScanServiceResultHolder.lastZerodhaResult == null) {
            addCachedPortfolioSymbols(symbols)
        }
        symbols.addAll(t212LiveSymbols)
        WatchlistStore.load(applicationContext).forEach { entry ->
            symbols.add(YahooFinanceClient.toYahooSymbol(entry.symbol, entry.exchange))
        }
        LiveBuyTrackerStore.load(applicationContext)
            .filter { it.status == "ARMED" || it.status == "FIRING" }
            .forEach { entry ->
                symbols.add(YahooFinanceClient.toYahooSymbol(entry.symbol, entry.exchange))
            }
        // Dummy GTT alert levels need live ticks so the monitor can fire.
        symbols.addAll(com.signalscope.app.data.DummyGttStore.yahooSymbols(applicationContext))
        // ALGO HOLDINGS. Without these an algo's open positions were priced only
        // from the engine's closed bars, so their unrealised P&L stepped once every
        // 2 hours on a 240/120 config (once a DAY on a daily one) — while a stock
        // sitting in the portfolio or watchlist updated every second. Nothing about
        // the strategy needs this: entries and exits stay on their own bar frames
        // and never see a tick. It is purely valuation, so it cannot affect trading.
        // Only OPEN positions are subscribed, not whole 106-name universes.
        try {
            val nss = (com.signalscope.app.data.BucketStore.allNamespaces(applicationContext) +
                listOf("us", "de", "binance")).distinct()
            for (n in nss) {
                val ex = com.signalscope.app.trading.Spaces.exchangeOf(n)
                com.signalscope.app.data.StrategyLabStore.load(applicationContext, n).trades
                    .filter { it.status == "OPEN" }
                    .forEach { symbols.add(YahooFinanceClient.toYahooSymbol(it.symbol, ex)) }
            }
        } catch (e: Exception) { Log.w(TAG, "algo live symbols failed", e) }
        // THE WATCHER'S CANDIDATES — the names with a Buy button on them.
        //
        // They were missing, and it mattered more than anything else on this list.
        // Every other entry here is something already owned or already watched;
        // these are the ones being PRICED FOR A PURCHASE. Without a tick the panel
        // fell back to the last daily close, so the figure beside the Buy button
        // could be a day old, and on India the buy trigger is placed relative to
        // that reference. A stale reference puts the trigger at the wrong level —
        // which is exactly the slow fill that exposed this.
        //
        // Bounded: each space nominates at most a handful of candidates, so this
        // adds tens of symbols, not universes.
        try {
            for (sp in com.signalscope.app.trading.ManualEntryWatcher.WATCHED_SPACES) {
                val ex = com.signalscope.app.trading.Spaces.exchangeOf(sp)
                com.signalscope.app.trading.ManualEntryWatcher
                    .candidates(applicationContext, sp)
                    .forEach { symbols.add(YahooFinanceClient.toYahooSymbol(it.symbol, ex)) }
            }
        } catch (e: Exception) { Log.w(TAG, "watcher candidate live symbols failed", e) }
        return symbols
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
            .toCollection(LinkedHashSet())
    }

    private fun addCachedPortfolioSymbols(symbols: MutableSet<String>) {
        try {
            val snap = PortfolioResultStore.load(applicationContext) ?: return
            val root = org.json.JSONObject(snap.payloadJson)
            val holdings = root.optJSONArray("holdings") ?: return
            for (i in 0 until holdings.length()) {
                val h = holdings.optJSONObject(i) ?: continue
                val symbol = h.optString("symbol", "").trim()
                val exchange = h.optString("exchange", "NSE").trim()
                if (symbol.isNotBlank()) {
                    symbols.add(YahooFinanceClient.toYahooSymbol(symbol, exchange))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read cached portfolio symbols for live stream", e)
        }
    }

    private fun updateLiveStreamSubscriptions() {
        if (!::liveStreamClient.isInitialized) return
        if (!config.livePriceStreamingEnabled) {
            liveStreamClient.stop("Live stream off")
            liveStreamStatus = liveStreamStatus.copy(
                enabled = false,
                connected = false,
                message = "Live stream off",
                symbolCount = 0
            )
            LivePortfolioBars.updateStreamState(false, false, 0, "Live stream off")
            pushLiveStreamStateToWebView()
            return
        }

        val symbols = collectLiveStreamSymbols()
        if (symbols.isEmpty()) {
            liveStreamClient.stop("No portfolio/watchlist/tracker symbols")
            liveStreamStatus = liveStreamStatus.copy(
                enabled = true,
                connected = false,
                message = "Waiting for portfolio/watchlist/tracker symbols",
                symbolCount = 0
            )
            LivePortfolioBars.updateStreamState(true, false, 0, "Waiting for portfolio/watchlist/tracker symbols")
            pushLiveStreamStateToWebView()
            return
        }

        liveStreamStatus = liveStreamStatus.copy(
            enabled = true,
            message = if (liveStreamStatus.connected) "Connected" else "Connecting...",
            symbolCount = symbols.size
        )
        LivePortfolioBars.updateStreamState(
            enabled = true,
            connected = liveStreamStatus.connected,
            symbolCount = symbols.size,
            message = liveStreamStatus.message
        )
        pushLiveStreamStateToWebView()

        // ── FEED ROUTING ──────────────────────────────────────────────────
        // Indian names can come from Kite instead of Yahoo. The split is by
        // symbol, not by which tab is open, because the mark-to-market pass
        // values every space's positions continuously — routing by the visible
        // tab would silently stop marking whatever you are not looking at.
        //
        // Both feeds emit the SAME Yahoo-style symbol, so nothing downstream
        // knows or cares which one a price came from.
        val useKite = try { config.useZerodhaLiveTicks } catch (e: Exception) { false }
        val useFinnhub = try { config.useFinnhubLiveTicks } catch (e: Exception) { false }

        // Partition by SYMBOL SHAPE, once, so each feed gets exactly the names it
        // can serve and Yahoo keeps the remainder. Written as a partition rather
        // than a chain of if/else because the failure that matters is a symbol
        // falling through every branch and silently never being priced.
        val indian = if (!useKite) emptyList()
            else symbols.filter { it.endsWith(".NS", true) || it.endsWith(".BO", true) }
        // US tickers carry no suffix. A dot means another exchange, a dash means
        // a crypto pair — both belong to Yahoo, and treating either as US would
        // subscribe Finnhub to a symbol it will never quote.
        val american = if (!useFinnhub) emptyList()
            else symbols.filter { !it.contains('.') && !it.contains('-') }
        val routed = (indian + american).toSet()
        val rest = symbols.filter { it !in routed }

        if (useKite) {
            if (zerodhaStreamClient == null) zerodhaStreamClient = buildZerodhaStream()
            zerodhaStreamClient?.setSymbols(indian)
        } else zerodhaStreamClient?.stop("Switched to Yahoo")

        if (useFinnhub) {
            if (finnhubStreamClient == null) finnhubStreamClient = buildFinnhubStream()
            finnhubStreamClient?.setSymbols(american)
        } else finnhubStreamClient?.stop("Switched to Yahoo")

        // Yahoo keeps everything the others cannot serve. Stopping it outright
        // would take unrelated spaces down with a switch labelled for one market.
        liveStreamClient.setSymbols(rest)
    }

    /** Same callbacks as the other feeds — see the routing note above. */
    private fun buildFinnhubStream() = com.signalscope.app.network.FinnhubStreamClient(
        config = config,
        onQuote = { quote ->
            com.signalscope.app.trading.MarkToMarket.onQuote(
                quote.symbol, quote.price,
                quote.time.takeIf { it > 0L } ?: System.currentTimeMillis())
            val liveSignal = LivePortfolioBars.recordQuote(quote)
            if (liveSignal != null) {
                LiveSoftSniperEngine.onSignal(applicationContext, config, liveSignal)
                LiveBuyTrackerEngine.onSignal(applicationContext, config, liveSignal)
            }
            com.signalscope.app.trading.DummyGttMonitor.onQuote(
                applicationContext, quote.symbol, quote.price)
            runOnUiThread { pushLiveQuoteToWebView(quote, liveSignal) }
        },
        onStatus = { status ->
            runOnUiThread {
                liveStreamStatus = liveStreamStatus.copy(
                    connected = status.connected || liveStreamStatus.connected,
                    message = "Finnhub: ${status.message}")
                pushLiveStreamStateToWebView()
            }
        })

    /** Same callbacks as the Yahoo stream — see the routing note above. */
    private fun buildZerodhaStream() = com.signalscope.app.network.ZerodhaStreamClient(
        appContext = applicationContext,
        config = config,
        onQuote = { quote ->
            com.signalscope.app.trading.MarkToMarket.onQuote(
                quote.symbol, quote.price,
                quote.time.takeIf { it > 0L } ?: System.currentTimeMillis())
            val liveSignal = LivePortfolioBars.recordQuote(quote)
            if (liveSignal != null) {
                LiveSoftSniperEngine.onSignal(applicationContext, config, liveSignal)
                LiveBuyTrackerEngine.onSignal(applicationContext, config, liveSignal)
            }
            com.signalscope.app.trading.DummyGttMonitor.onQuote(
                applicationContext, quote.symbol, quote.price)
            runOnUiThread { pushLiveQuoteToWebView(quote, liveSignal) }
        },
        onStatus = { status ->
            // Named so a stalled Indian feed is not mistaken for a stalled
            // Yahoo one when both are running side by side.
            runOnUiThread {
                liveStreamStatus = liveStreamStatus.copy(
                    connected = status.connected || liveStreamStatus.connected,
                    message = "Kite: ${status.message}")
                pushLiveStreamStateToWebView()
            }
        })

    private fun pushLiveStreamStateToWebView() {
        if (!::webView.isInitialized) return
        val payload = mapOf(
            "enabled" to config.livePriceStreamingEnabled,
            "connected" to liveStreamStatus.connected,
            "status" to liveStreamStatus.message,
            "symbols" to liveStreamStatus.symbolCount
        )
        val escaped = gson.toJson(payload).replace("\\", "\\\\").replace("'", "\\'")
        webView.evaluateJavascript("window.updateLiveStreamState && window.updateLiveStreamState('$escaped')", null)
    }

    private fun pushLiveQuoteToWebView(
        quote: YahooFinanceStreamClient.LiveQuote,
        signal: LivePortfolioBars.ReversalSignal?
    ) {
        if (!::webView.isInitialized || !config.livePriceStreamingEnabled) return
        val payload = mapOf(
            "symbol" to quote.symbol,
            "price" to quote.price,
            "time" to quote.time,
            "currency" to quote.currency,
            "exchange" to quote.exchange,
            "quoteType" to quote.quoteType,
            "marketHours" to quote.marketHours,
            "changePercent" to quote.changePercent,
            "dayVolume" to quote.dayVolume,
            "dayHigh" to quote.dayHigh,
            "dayLow" to quote.dayLow,
            "change" to quote.change,
            "priceHint" to quote.priceHint,
            "receivedAt" to System.currentTimeMillis(),
            "liveMacd" to signal?.let {
                mapOf(
                    "ready" to it.ready,
                    "shouldProtect" to it.shouldProtect,
                    "reason" to it.reason,
                    "dropFromSessionHighPct" to it.dropFromSessionHighPct,
                    "currentBarMovePct" to it.currentBarMovePct,
                    "closeSlopePct" to it.closeSlopePct,
                    "buyReady" to it.mtfMacd.buyReady,
                    "sellWarning" to it.mtfMacd.sellWarning,
                    "sellConfirmed" to it.mtfMacd.sellConfirmed,
                    "summary" to it.mtfMacd.summary,
                    "velocity30m" to velocityPayload(it.velocity30m),
                    "oneHour" to framePayload(it.mtfMacd.oneHour),
                    "twoHour" to framePayload(it.mtfMacd.twoHour),
                    "fourHour" to framePayload(it.mtfMacd.fourHour)
                )
            }
        )
        val escaped = gson.toJson(payload).replace("\\", "\\\\").replace("'", "\\'")
        webView.evaluateJavascript("window.updateLiveQuote && window.updateLiveQuote('$escaped')", null)
    }

    private fun framePayload(frame: LivePortfolioBars.FrameMacdSignal): Map<String, Any> = mapOf(
        "label" to frame.label,
        "ready" to frame.ready,
        "bars" to frame.bars,
        "macd" to frame.macd,
        "histogram" to frame.histogram,
        "slope" to frame.slope,
        "previousSlope" to frame.previousSlope,
        "buyHook" to frame.buyHook,
        "sellWarning" to frame.sellWarning,
        "reason" to frame.reason
    )

    private fun velocityPayload(signal: LivePortfolioBars.PriceVelocitySignal): Map<String, Any> = mapOf(
        "label" to signal.label,
        "ready" to signal.ready,
        "bars" to signal.bars,
        "velocityPct" to signal.velocityPct,
        "previousVelocityPct" to signal.previousVelocityPct,
        "accelerationPct" to signal.accelerationPct,
        "warning" to signal.warning,
        "severe" to signal.severe,
        "confirmed" to signal.confirmed,
        "summary" to signal.summary
    )

    private data class MarketIndexCandidate(
        val symbol: String,
        val name: String,
        val index: String
    )

    private data class MarketIndexSpec(
        val space: String,
        val exchange: String,
        val candidates: List<MarketIndexCandidate>
    )

    private val marketIndexSpecs = listOf(
        MarketIndexSpec(
            "IN",
            "NSE",
            listOf(
                MarketIndexCandidate("^CRSLDX", "NIFTY 500", "NIFTY 500"),
                MarketIndexCandidate("^NSEI", "NIFTY 50", "NIFTY 50")
            )
        ),
        MarketIndexSpec(
            "US",
            "US",
            listOf(
                MarketIndexCandidate("^NDX", "NASDAQ 100", "NASDAQ 100"),
                MarketIndexCandidate("^IXIC", "NASDAQ", "NASDAQ Composite")
            )
        ),
        MarketIndexSpec(
            "DE",
            "DE",
            listOf(
                MarketIndexCandidate("^GDAXI", "DAX", "DAX")
            )
        )
    )

    @Volatile private var marketIndexFetchInProgress = false

    private fun fetchMarketIndexData() {
        if (marketIndexFetchInProgress) return
        marketIndexFetchInProgress = true

        thread(isDaemon = true) {
            try {
                val indices = mutableListOf<Map<String, Any?>>()

                for (spec in marketIndexSpecs) {
                    var added = false
                    for (candidate in spec.candidates) {
                        try {
                            val result = YahooFinanceClient.fetchCandles(candidate.symbol, spec.exchange)
                            if (result is YahooFinanceClient.CandleResult.Success && result.candles.size >= 2) {
                                val candles = result.candles
                                val latest = candles.last()
                                val prev = candles[candles.size - 2]
                                val dayChange = if (prev.close > 0) {
                                    (latest.close - prev.close) / prev.close * 100.0
                                } else 0.0
                                val periodStart = if (candles.size > 120) candles[candles.size - 120] else candles.first()
                                val periodChange = if (periodStart.close > 0) {
                                    (latest.close - periodStart.close) / periodStart.close * 100.0
                                } else 0.0
                                val high = candles.takeLast(120).maxOfOrNull { it.high } ?: latest.high
                                val low = candles.takeLast(120).minOfOrNull { it.low } ?: latest.low
                                val closesForChart = candles.takeLast(60).map { it.close }

                                indices.add(
                                    mapOf(
                                        "space" to spec.space,
                                        "name" to candidate.name,
                                        "index" to candidate.index,
                                        "symbol" to candidate.symbol,
                                        "price" to latest.close,
                                        "dayChange" to dayChange,
                                        "periodChange" to periodChange,
                                        "high" to high,
                                        "low" to low,
                                        "closes" to closesForChart
                                    )
                                )
                                added = true
                                break
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Market index fetch error for ${candidate.symbol}", e)
                        }
                    }
                    if (added) Thread.sleep(350) else Thread.sleep(200)
                }

                if (indices.isNotEmpty()) {
                    val json = gson.toJson(indices)
                    val escaped = json.replace("\\", "\\\\").replace("'", "\\'")
                    runOnUiThread {
                        webView.evaluateJavascript("window.updateMarketIndexData('$escaped')", null)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Market index data fetch failed", e)
            } finally {
                marketIndexFetchInProgress = false
            }
        }
    }

    /**
     * One sector card: the line to draw, tried in order until one answers with
     * enough history.
     *
     * WHY THERE ARE FALLBACKS. Yahoo's history for most NSE sector indices has
     * gone thin — ^CNXFMCG, ^CNXAUTO, ^CNXENERGY, ^CNXMETAL, ^CNXREALTY, ^CNXFIN
     * and ^CNXMEDIA answer two years of daily bars with one to three of them
     * (checked 19 Oct 2026; Bank, IT and Pharma still answer in full). The card
     * then fell under the fetcher's 50-bar minimum and vanished, so the tab
     * showed whatever two or three indices still worked. An ETF that tracks the
     * same index draws the same line, so it stands in, and the card says so.
     *
     * The US and German spaces had no sectors at all — only their market index.
     * They use the sector ETFs: SPDR's for the US, iShares STOXX Europe 600 on
     * Xetra for Germany (the nearest liquid sector lines for German shares).
     */
    private data class SectorSpec(
        val space: String,
        val exchange: String,
        val name: String,
        val index: String,
        val symbols: List<String>
    )

    private val sectorSpecs = listOf(
        SectorSpec("IN", "NSE", "Banking", "NIFTY Bank", listOf("^NSEBANK", "BANKBEES.NS")),
        SectorSpec("IN", "NSE", "IT", "NIFTY IT", listOf("^CNXIT", "ITBEES.NS")),
        SectorSpec("IN", "NSE", "Pharma", "NIFTY Pharma", listOf("^CNXPHARMA", "PHARMABEES.NS")),
        SectorSpec("IN", "NSE", "FMCG", "NIFTY FMCG", listOf("^CNXFMCG", "FMCGIETF.NS")),
        SectorSpec("IN", "NSE", "Auto", "NIFTY Auto", listOf("^CNXAUTO", "AUTOBEES.NS")),
        SectorSpec("IN", "NSE", "Energy", "NIFTY Energy", listOf("^CNXENERGY", "MOENERGY.NS")),
        SectorSpec("IN", "NSE", "Fin Services", "NIFTY Fin Service", listOf("^CNXFIN", "BFSI.NS")),
        SectorSpec("IN", "NSE", "Metal", "NIFTY Metal", listOf("^CNXMETAL", "METALIETF.NS")),
        SectorSpec("IN", "NSE", "Realty", "NIFTY Realty", listOf("^CNXREALTY", "MOREALTY.NS")),
        SectorSpec("IN", "NSE", "Media", "NIFTY Media", listOf("^CNXMEDIA")),
        SectorSpec("IN", "NSE", "PSU Bank", "NIFTY PSU Bank", listOf("^CNXPSUBANK", "PSUBNKBEES.NS")),
        SectorSpec("IN", "NSE", "Infra", "NIFTY Infra", listOf("^CNXINFRA", "INFRABEES.NS")),

        SectorSpec("US", "US", "Tech", "Technology", listOf("XLK")),
        SectorSpec("US", "US", "Semis", "Semiconductors", listOf("SMH")),
        SectorSpec("US", "US", "Financials", "Financials", listOf("XLF")),
        SectorSpec("US", "US", "Health", "Health Care", listOf("XLV")),
        SectorSpec("US", "US", "Energy", "Energy", listOf("XLE")),
        SectorSpec("US", "US", "Discretionary", "Consumer Discretionary", listOf("XLY")),
        SectorSpec("US", "US", "Staples", "Consumer Staples", listOf("XLP")),
        SectorSpec("US", "US", "Industrials", "Industrials", listOf("XLI")),
        SectorSpec("US", "US", "Materials", "Materials", listOf("XLB")),
        SectorSpec("US", "US", "Utilities", "Utilities", listOf("XLU")),
        SectorSpec("US", "US", "Real Estate", "Real Estate", listOf("XLRE")),
        SectorSpec("US", "US", "Comms", "Communication Services", listOf("XLC")),

        SectorSpec("DE", "DE", "Banks", "STOXX 600 Banks", listOf("EXV1.DE")),
        SectorSpec("DE", "DE", "Insurance", "STOXX 600 Insurance", listOf("EXH5.DE")),
        SectorSpec("DE", "DE", "Tech", "STOXX 600 Technology", listOf("EXV3.DE")),
        SectorSpec("DE", "DE", "Health", "STOXX 600 Health Care", listOf("EXV4.DE")),
        SectorSpec("DE", "DE", "Autos", "STOXX 600 Automobiles", listOf("EXV5.DE")),
        SectorSpec("DE", "DE", "Chemicals", "STOXX 600 Chemicals", listOf("EXV7.DE")),
        SectorSpec("DE", "DE", "Industrials", "STOXX 600 Industrials", listOf("EXH4.DE")),
        SectorSpec("DE", "DE", "Construction", "STOXX 600 Construction", listOf("EXV8.DE")),
        SectorSpec("DE", "DE", "Basic Res.", "STOXX 600 Basic Resources", listOf("EXV6.DE")),
        SectorSpec("DE", "DE", "Utilities", "STOXX 600 Utilities", listOf("EXH9.DE")),
        SectorSpec("DE", "DE", "Telecom", "STOXX 600 Telecoms", listOf("EXV2.DE")),
        SectorSpec("DE", "DE", "Real Estate", "STOXX 600 Real Estate", listOf("EXI5.DE"))
    )

    @Volatile private var sectorFetchInProgress = false

    private fun fetchSectorData() {
        if (sectorFetchInProgress) return
        sectorFetchInProgress = true

        thread(isDaemon = true) {
            try {
                val sectors = mutableListOf<Map<String, Any?>>()

                for (spec in sectorSpecs) {
                    for ((i, symbol) in spec.symbols.withIndex()) {
                        try {
                            val result = YahooFinanceClient.fetchCandles(symbol, spec.exchange)
                            Thread.sleep(400) // pace requests
                            if (result !is YahooFinanceClient.CandleResult.Success || result.candles.size < 2) continue
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

                            // An ETF standing in for an index (or an ETF being the
                            // sector line itself, outside India) says which one.
                            val viaEtf = !symbol.startsWith("^")
                            sectors.add(mapOf(
                                "space" to spec.space,
                                "name" to spec.name,
                                "index" to if (viaEtf) "${spec.index} · ${symbol.substringBefore('.')} ETF" else spec.index,
                                "symbol" to symbol,
                                "proxy" to (viaEtf && i > 0),
                                "price" to latest.close,
                                "dayChange" to dayChange,
                                "periodChange" to periodChange,
                                "high" to high,
                                "low" to low,
                                "closes" to closesForChart
                            ))
                            break
                        } catch (e: Exception) {
                            Log.w(TAG, "Sector fetch error for ${spec.name} ($symbol)", e)
                        }
                    }
                }
                Log.i(TAG, "sectors: ${sectors.size} of ${sectorSpecs.size} lines fetched")

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

    // Discovery buy-timing alerts: quality-filtered smoothed MACD turns from
    // the Setups -> Turned Today lens.
    val smoothTurnAlerts: MutableList<com.signalscope.app.data.StockAlert> =
        java.util.Collections.synchronizedList(mutableListOf<com.signalscope.app.data.StockAlert>())

    /** Helper: did the soft sniper fire for [symbol] on [yyyyMmDd] (IST trading date)? */
    fun isSoftSniperFired(symbol: String, yyyyMmDd: String): Boolean =
        softSniperFiredKeys.contains("$yyyyMmDd|$symbol")

    /** Atomic one-shot guard shared by scan-based and live-ticker soft sniper paths. */
    fun markSoftSniperFiredIfAbsent(symbol: String, yyyyMmDd: String): Boolean {
        val key = "$yyyyMmDd|$symbol"
        synchronized(softSniperFiredKeys) {
            if (softSniperFiredKeys.contains(key)) return false
            softSniperFiredKeys.add(key)
            return true
        }
    }

    // ── Score persistence streak (Tier 1.5 anti-flicker gate) ──
    // Tracks how many consecutive 15-min scan ticks each symbol has had
    // profitScore ≥ profitActivationThreshold. Soft sniper requires ≥ 2 ticks
    // (≈ 30 min of stable BOOK_PROFIT signal) before placing a GTT — kills
    // intraday flicker caused by the daily-indicators-on-partial-bar mismatch.
    // Keyed "yyyy-MM-dd|SYMBOL" so it self-clears next day.
    val profitScoreStreak: MutableMap<String, Int> =
        java.util.Collections.synchronizedMap(mutableMapOf<String, Int>())
}

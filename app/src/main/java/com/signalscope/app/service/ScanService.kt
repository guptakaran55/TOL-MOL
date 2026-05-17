package com.signalscope.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.app.AlarmManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.signalscope.app.data.*
import com.signalscope.app.network.YahooFinanceClient
import com.signalscope.app.ui.ScanServiceResultHolder
import com.signalscope.app.network.ZerodhaClient
import com.signalscope.app.trading.DailyPortfolioManager
import com.signalscope.app.ui.MainActivity
import com.signalscope.app.util.StockAnalyzer
import com.signalscope.app.util.ValueAnalyzer
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.*

/**
 * Foreground Service with TWO scan modes:
 *
 * 1. PORTFOLIO MONITORING (automatic, market hours only)
 *    - Scans Zerodha holdings every 15 min
 *    - Sends push notifications on sell signals
 *    - Gated by market hours (9:15–15:30 IST weekdays)
 *
 * 2. DISCOVERY SCANNING (on-demand, anytime)
 *    - Scans full NIFTY 500 or NASDAQ 100 via Yahoo Finance
 *    - Shows buy scores, sell scores, all indicators
 *    - NOT gated by market hours — user triggers manually
 *    - No sell notifications (not held stocks)
 *    - Results stored in lastDiscoveryResult for UI display
 */
class ScanService : Service() {

    companion object {
        private const val TAG = "ScanService"
        const val CHANNEL_SCAN   = "signalscope_scan"
        const val CHANNEL_ALERTS = "signalscope_alerts"
        const val NOTIFICATION_SCAN_ID = 1

        // Portfolio monitoring actions
        const val ACTION_START    = "com.signalscope.START_SCAN"
        const val ACTION_STOP     = "com.signalscope.STOP_SCAN"
        const val ACTION_SCAN_NOW = "com.signalscope.SCAN_NOW"

        // Discovery scan actions (anytime, on-demand)
        // Manual variants run in FAST mode (user is watching, wants results sooner).
        // _AUTO variants run in ROBUST mode (background, must not fail midway).
        const val ACTION_DISCOVERY_NIFTY500       = "com.signalscope.DISCOVERY_NIFTY500"
        const val ACTION_DISCOVERY_NASDAQ100      = "com.signalscope.DISCOVERY_NASDAQ100"
        const val ACTION_DISCOVERY_DAX            = "com.signalscope.DISCOVERY_DAX"
        const val ACTION_DISCOVERY_NIFTY500_AUTO  = "com.signalscope.DISCOVERY_NIFTY500_AUTO"
        const val ACTION_DISCOVERY_NASDAQ100_AUTO = "com.signalscope.DISCOVERY_NASDAQ100_AUTO"
        const val ACTION_DISCOVERY_DAX_AUTO       = "com.signalscope.DISCOVERY_DAX_AUTO"
        const val ACTION_DISCOVERY_STOP           = "com.signalscope.DISCOVERY_STOP"

        // Broadcast events
        const val BROADCAST_SCAN_UPDATE      = "com.signalscope.SCAN_UPDATE"
        const val BROADCAST_DISCOVERY_UPDATE  = "com.signalscope.DISCOVERY_UPDATE"
        const val EXTRA_SCAN_STATUS           = "scan_status"
        const val EXTRA_DISCOVERY_PROGRESS    = "discovery_progress"
        const val EXTRA_DISCOVERY_TOTAL       = "discovery_total"
        const val EXTRA_DISCOVERY_MARKET      = "discovery_market"
        const val EXTRA_DISCOVERY_STATUS_TEXT = "discovery_status_text"

        fun createIntent(context: Context, action: String): Intent =
            Intent(context, ScanService::class.java).apply { this.action = action }
    }

    private lateinit var config: ConfigManager
    private lateinit var zerodhaClient: ZerodhaClient
    private lateinit var weights: ScoringWeights

    private var portfolioJob: Job? = null
    private var discoveryJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Single Yahoo session lock ──
    // Portfolio + discovery share one OkHttp client, cookie jar, and crumb cache.
    // Running both at once doubles the request rate against Yahoo and frequently
    // corrupts the crumb session mid-scan, which is the #1 cause of random
    // mid-scan stalls (e.g. discovery dying at stock 70/120/200 when the user
    // taps "scan portfolio"). This mutex serialises them: whichever scan starts
    // first runs to completion before the other gets the Yahoo channel.
    private val yahooScanLock = Mutex()

    // Tiered scan frequency: full analysis every FULL_SCAN_INTERVAL scans,
    // quick scans (cached data) in between. Since daily indicators barely move
    // intraday, we only need full Yahoo API hits once per hour.
    private var scanCount = 0
    private val FULL_SCAN_EVERY_N = 4  // full scan every 4th cycle (= ~1 hour at 15-min intervals)

    // Alert deduplication: two strategies combined
    // - L4/L5 (urgent): time-based cooldown (can re-fire same day if conditions persist)
    // - L1/L2/L3 (slow-moving): once per trading day (daily indicators can't change intraday)
    private val recentAlerts = mutableMapOf<String, Long>()
    private val dailyAlertsSent = mutableSetOf<String>() // keys: "symbol_alertType_YYYY-MM-dd"
    private var lastTradingDate = "" // tracks date to reset dailyAlertsSent

    /** Returns true if this alert is urgent enough to use time-based cooldowns (vs once-per-day). */
    private fun isUrgentAlert(alertType: AlertType): Boolean = when (alertType) {
        AlertType.STRONG_EXIT -> true     // Both scores firing — act now
        AlertType.BOOK_PROFIT -> true     // MACD slope flipped — sell at peak
        else -> false
    }

    // Cooldown only applies to urgent alerts — the rest use once-per-day
    @Suppress("DEPRECATION")
    private fun alertCooldownMs(alertType: AlertType): Long = when (alertType) {
        AlertType.STRONG_EXIT         -> 15 * 60 * 1000L   // Both scores — act NOW
        AlertType.BOOK_PROFIT         -> 30 * 60 * 1000L   // MACD slope peak — sell for max profit
        AlertType.PROTECT_CAPITAL     -> 24 * 60 * 60 * 1000L  // Structural break — once per day
        AlertType.PEAK_WARNING        -> 24 * 60 * 60 * 1000L  // Prepare alert — once per day
        AlertType.GOLDEN_BUY          -> 24 * 60 * 60 * 1000L
        AlertType.STRONG_BUY          -> 24 * 60 * 60 * 1000L
        // Legacy types — fallback 24h
        else                          -> 24 * 60 * 60 * 1000L
    }

    private fun todayTradingDate(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        return sdf.format(Date())
    }

    // ── Results accessible from UI ──
    var lastZerodhaResult: ScanResult? = null; private set
    var lastDiscoveryResult: DiscoveryScanResult? = null; private set

    // Discovery scan state
    @Volatile var isDiscoveryRunning = false; private set
    @Volatile var discoveryAbort = false

    override fun onCreate() {
        super.onCreate()
        config = ConfigManager(this)
        zerodhaClient = ZerodhaClient(config)
        weights = config.scoringWeights
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START                   -> startPortfolioMonitoring()
            ACTION_STOP                    -> stopAll()
            ACTION_SCAN_NOW                -> triggerImmediatePortfolioScan()
            ACTION_DISCOVERY_NIFTY500      -> startDiscoveryScan("nifty500", robust = false)
            ACTION_DISCOVERY_NASDAQ100     -> startDiscoveryScan("nasdaq100", robust = false)
            ACTION_DISCOVERY_DAX           -> startDiscoveryScan("dax", robust = false)
            ACTION_DISCOVERY_NIFTY500_AUTO -> startDiscoveryScan("nifty500", robust = true)
            ACTION_DISCOVERY_NASDAQ100_AUTO-> startDiscoveryScan("nasdaq100", robust = true)
            ACTION_DISCOVERY_DAX_AUTO      -> startDiscoveryScan("dax", robust = true)
            ACTION_DISCOVERY_STOP          -> stopDiscoveryScan()
            else                           -> startPortfolioMonitoring()
        }
        // After every command, make sure the auto-discovery alarm chain is armed.
        // No-op if already scheduled.
        DiscoveryAutoScheduler.ensureScheduled(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopAll()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ═══════════════════════════════════════════════════════
    // MODE 1: PORTFOLIO MONITORING (market hours only)
    // ═══════════════════════════════════════════════════════

    private fun startPortfolioMonitoring() {
        Log.i(TAG, "Starting portfolio monitoring")
        config.serviceRunning = true
        YahooFinanceClient.clearCache() // fresh start — first scan will do full 2y fetch

        // IMPORTANT ORDER: wake/wifi locks FIRST, then startForeground.
        // If the OS decides the service is idle during the tiny window between
        // startForeground() returning and acquire() being called, Doze can
        // briefly suspend the coroutine and the first network request stalls.
        // Acquiring locks first closes that race.
        acquireScanLocks(durationMs = 24 * 60 * 60 * 1000L)
        startForeground(NOTIFICATION_SCAN_ID, buildScanNotification("Starting..."))

        portfolioJob?.cancel()
        portfolioJob = serviceScope.launch {
            while (isActive) {
                try {
                    if (isMarketOpen()) {
                        runFullPortfolioScan()
                    } else {
                        // Outside market hours: portfolio monitoring pauses
                        // but service stays alive for on-demand discovery scans
                        val msg = if (isDiscoveryRunning)
                            "Discovery scan running..."
                        else
                            "Portfolio monitoring paused (market closed). Discovery scans available."
                        updateScanNotification(msg)
                    }
                    delay(config.portfolioScanIntervalMin * 60 * 1000L)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Portfolio scan loop error", e)
                    updateScanNotification("Error: ${e.message}")
                    delay(60_000)
                }
            }
        }
    }

    private fun stopAll() {
        Log.i(TAG, "Stopping all scans")
        portfolioJob?.cancel()
        discoveryAbort = true
        discoveryJob?.cancel()
        releaseScanLocks()
        config.serviceRunning = false
        isDiscoveryRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Acquire BOTH a partial wake lock (CPU stays on) AND a high-perf WiFi lock
     * (radio stays awake).
     *
     * Wake lock alone isn't enough: when the screen turns off Android puts the
     * WiFi radio into a power-saving doze where outbound TCP sessions stall for
     * tens of seconds at a time. That's the proximate cause of "scan stops at
     * 70/120/200 when the screen is off" — the OkHttp socket times out, Yahoo
     * thinks we hung up, and the next request comes back rate-limited.
     *
     * WIFI_MODE_FULL_HIGH_PERF tells the OS "I'm doing real network work, don't
     * tickle the radio." It costs battery but only while held — we release as
     * soon as scans stop.
     */
    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF is still the right mode for our use case
    private fun acquireScanLocks(durationMs: Long) {
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SignalScope::ScanWakeLock").apply {
                setReferenceCounted(false)
                acquire(durationMs)
            }
        }
        if (wifiLock?.isHeld != true) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SignalScope::ScanWifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseScanLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun triggerImmediatePortfolioScan() {
        serviceScope.launch {
            try {
                // Immediate portfolio scan bypasses market hours check
                runFullPortfolioScan()
            } catch (e: Exception) {
                Log.e(TAG, "Immediate scan error", e)
                updateScanNotification("Scan failed: ${e.message}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // MODE 2: DISCOVERY SCANNING (anytime, on-demand)
    // Scans NIFTY 500 or NASDAQ 100 via Yahoo Finance
    // Shows full indicator data — no market hours gate
    // ═══════════════════════════════════════════════════════

    /**
     * Start a discovery scan.
     *
     * @param robust  ROBUST mode = slow but durable (used for automatic 2-hour
     *                background scans where the user isn't watching). Doubles the
     *                pacing and roughly doubles the error tolerance so transient
     *                Yahoo rate-limits never abort the run.
     *                FAST mode = current pacing (used for manual user-triggered
     *                scans where they want results sooner). Still has the
     *                250ms candle/fundamentals gap and retry phase.
     */
    private fun startDiscoveryScan(market: String, robust: Boolean) {
        if (isDiscoveryRunning) {
            Log.w(TAG, "Discovery scan already running — ignoring")
            return
        }

        // Always make sure we're a proper foreground service holding wake+wifi
        // locks before kicking off a multi-minute scan. Even when portfolio
        // monitoring already started us, re-acquiring is a no-op (locks are
        // setReferenceCounted(false)) and ensures the wifi lock is held for
        // discovery even if startPortfolioMonitoring wasn't called.
        //
        // ORDER MATTERS: acquire locks BEFORE startForeground so the first
        // network request can't stall in the Doze-eligible window that
        // follows a fresh startForeground() call on some OEMs (MIUI).
        // ROBUST mode can take 30+ min; give the wake lock plenty of headroom.
        acquireScanLocks(durationMs = (if (robust) 4 else 2) * 60 * 60 * 1000L)
        if (!config.serviceRunning) {
            config.serviceRunning = true
            startForeground(NOTIFICATION_SCAN_ID, buildScanNotification("Starting discovery scan..."))
        }

        isDiscoveryRunning = true
        discoveryAbort = false
        ScanServiceResultHolder.isDiscoveryRunning = true
        ScanServiceResultHolder.discoveryMarket = when (market) {
            "nifty500" -> "NIFTY 500"
            "nasdaq100" -> "NASDAQ 100"
            "dax" -> "DAX 100"
            else -> market.uppercase()
        }

        discoveryJob?.cancel()
        discoveryJob = serviceScope.launch {
            // Serialise against any in-flight portfolio scan so we don't share the
            // Yahoo session with another scanner. See yahooScanLock comment.
            if (yahooScanLock.isLocked) {
                Log.i(TAG, "Discovery scan: portfolio scan in progress, queuing behind it")
                updateScanNotification("Discovery queued (portfolio scan running)…")
            }
            yahooScanLock.withLock {
                try {
                    // ── RESUME-FROM-CHECKPOINT ──
                    // If the previous run of this market was killed mid-scan by MIUI/Samsung
                    // battery-killers, we have a partial result saved on disk. Resume from
                    // there instead of starting over — but only if:
                    //   (a) it's recent (< 45 min old) so the data is still valid
                    //   (b) it has at least some stocks scanned (not a fresh abort)
                    //   (c) it wasn't already complete
                    val marketName = when (market) {
                        "nifty500" -> "NIFTY 500"
                        "nasdaq100" -> "NASDAQ 100"
                        "dax" -> "DAX 100"
                        else -> market.uppercase()
                    }
                    val prior = DiscoveryResultStore.load(this@ScanService, marketName)
                    val previousComplete = DiscoveryResultStore.loadLatestComplete(this@ScanService, marketName)
                    val ageMs = if (prior != null) System.currentTimeMillis() - prior.timestamp else Long.MAX_VALUE
                    val resumeFrom = if (prior != null
                            && !prior.isComplete
                            && prior.allStocks.isNotEmpty()
                            && ageMs < 45 * 60 * 1000L) {
                        Log.i(TAG, "Resuming $marketName scan: ${prior.allStocks.size} stocks already done, age=${ageMs/1000}s")
                        updateScanNotification("Resuming $marketName: ${prior.allStocks.size} cached, continuing...")
                        prior
                    } else null
                    when (market) {
                        "nifty500"  -> runYahooDiscovery(NIFTY_500_YAHOO_SYMBOLS, "NIFTY 500", "₹", robust = robust, resumeFrom = resumeFrom, previousComplete = previousComplete)
                        "nasdaq100" -> runYahooDiscovery(NASDAQ_100_SYMBOLS, "NASDAQ 100", "$", robust = robust, resumeFrom = resumeFrom, previousComplete = previousComplete)
                        "dax"       -> runYahooDiscovery(DAX_SYMBOLS, "DAX 100", "€", robust = robust, resumeFrom = resumeFrom, previousComplete = previousComplete)
                        else        -> Log.w(TAG, "Unknown market '$market' — skipping discovery")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Discovery scan error", e)
                    val errMsg = "Discovery scan failed: ${e.message}"
                    updateScanNotification(errMsg)
                    ScanServiceResultHolder.discoveryStatusText = errMsg
                } finally {
                    isDiscoveryRunning = false
                    ScanServiceResultHolder.isDiscoveryRunning = false
                    ScanServiceResultHolder.discoveryVersion++
                }
            }
        }
    }

    private fun stopDiscoveryScan() {
        discoveryAbort = true
        discoveryJob?.cancel()
        isDiscoveryRunning = false
        ScanServiceResultHolder.isDiscoveryRunning = false
        ScanServiceResultHolder.discoveryStatusText = "Discovery scan stopped"
        ScanServiceResultHolder.discoveryVersion++
        updateScanNotification("Discovery scan stopped")
    }

    private fun discoverySymbolKey(symbol: String): String =
        symbol.trim().uppercase(Locale.US).removeSuffix(".NS")

    private fun annotateSmoothMacdTurn(
        analysis: StockAnalysis,
        previousSmoothBySymbol: Map<String, StockAnalysis>,
        previousSmoothTimestamp: Long
    ): StockAnalysis {
        val previous = previousSmoothBySymbol[discoverySymbolKey(analysis.symbol)]
            ?: return analysis
        val turnedPositive = previous.smoothMacdSlope < 0.0 && analysis.smoothMacdSlope >= 0.0
        return analysis.copy(
            smoothMacdPrevSavedSlope = previous.smoothMacdSlope,
            smoothMacdTurnedPositiveToday = turnedPositive,
            smoothMacdTurnSourceTimestamp = if (turnedPositive) previousSmoothTimestamp else 0L
        )
    }

    /**
     * Scan a list of symbols via Yahoo Finance with adaptive pacing & retry queue.
     *
     * Two operating points, controlled by [robust]:
     *
     * - FAST   (manual scans): start at 500ms pace, bail to retry after 50
     *                          consecutive errors. ~12-15 min for NIFTY 500
     *                          on a good network.
     * - ROBUST (auto scans):   start at 1500ms pace, tolerate 100 consecutive
     *                          errors before bailing, gentler retry phase.
     *                          ~30-40 min but rarely fails midway. Used for
     *                          background scheduled scans where the user isn't
     *                          watching the screen.
     *
     * Resilience features (both modes):
     *   - Adaptive pace: backs off 2x on rate limit, recovers 0.95x on success
     *   - Rate-limited stocks are queued for retry (not skipped)
     *   - After main pass, a retry phase re-scans failed stocks at gentler pace
     *   - Consecutive error cap triggers early exit to retry phase
     */
    private suspend fun runYahooDiscovery(
        symbols: List<String>,
        marketName: String,
        currency: String,
        robust: Boolean = false,
        resumeFrom: DiscoveryScanResult? = null,
        previousComplete: DiscoveryScanResult? = null
    ) = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val total = symbols.size
        // Pre-seed from any prior partial run (see resume-from-checkpoint logic in
        // startDiscoveryScan). Symbols whose analysis is already in allStocks are
        // skipped in the main loop — we don't re-fetch them.
        val allStocks = mutableListOf<StockAnalysis>()
        val alreadyScannedSymbols = mutableSetOf<String>()
        if (resumeFrom != null) {
            allStocks.addAll(resumeFrom.allStocks)
            resumeFrom.allStocks.forEach { alreadyScannedSymbols.add(it.symbol) }
        }
        val previousSmoothBySymbol = previousComplete
            ?.allStocks
            ?.associateBy { discoverySymbolKey(it.symbol) }
            ?: emptyMap()
        val previousSmoothTimestamp = previousComplete?.timestamp ?: 0L
        var errors = 0
        var skipped = 0
        var lastError = ""

        // Adaptive pacing — ROBUST starts slower & tolerates more errors.
        val PACE_MIN = if (robust) 1500L else 500L
        val PACE_MAX = 8000L
        val PACE_BACKOFF_MULT = 2.0
        val PACE_RECOVERY_MULT = 0.95
        val errorBailThreshold = if (robust) 100 else 50
        var currentPace = PACE_MIN
        var consecutiveErrors = 0
        val retryQueue = mutableListOf<String>()
        Log.i(TAG, "Discovery start: $marketName mode=${if (robust) "ROBUST" else "FAST"} " +
                "pace=${PACE_MIN}ms bail=$errorBailThreshold")

        val startText = "Discovery: $marketName — 0/$total scanned"
        updateScanNotification(startText)
        ScanServiceResultHolder.discoveryProgress = 0
        ScanServiceResultHolder.discoveryTotal = total
        ScanServiceResultHolder.discoveryStatusText = startText
        ScanServiceResultHolder.discoveryVersion++
        Log.i(TAG, "Discovery scan started: $marketName — $total symbols")

        // ── Main scan pass ──
        for ((idx, symbol) in symbols.withIndex()) {
            if (discoveryAbort) {
                Log.i(TAG, "Discovery scan aborted at $idx/$total")
                break
            }

            // Skip symbols already scanned in a prior (interrupted) run.
            // No network request, no pacing delay — instant pass-through.
            if (symbol in alreadyScannedSymbols) {
                ScanServiceResultHolder.discoveryProgress = idx + 1
                continue
            }

            // If too many consecutive errors, bail to retry phase.
            // FAST=50, ROBUST=100. The ROBUST cap is much higher because background
            // scans during volatile network conditions (screen off, doze pulses)
            // legitimately rack up errors in bursts — we'd rather slow down and
            // wait it out than abort with hundreds of stocks unscanned.
            if (consecutiveErrors >= errorBailThreshold) {
                Log.w(TAG, "Discovery: $consecutiveErrors consecutive errors — queuing ${symbols.size - idx} remaining for retry phase")
                retryQueue.addAll(symbols.subList(idx, symbols.size))
                break
            }

            try {
                val exchange = if (currency == "₹") "NSE" else "NASDAQ"
                val candleResult = YahooFinanceClient.fetchCandles(symbol, exchange)

                when (candleResult) {
                    is YahooFinanceClient.CandleResult.Success -> {
                        consecutiveErrors = 0
                        var analysis = StockAnalyzer.analyze(
                            candles = candleResult.candles,
                            symbol = symbol,
                            name = symbol,
                            token = symbol,
                            minAvgVolume = if (currency == "₹") 100000 else 50000,
                            w = weights
                        )
                        if (analysis != null) {
                            // ── Gap between v8 (candles) and v10 (fundamentals) requests ──
                            // Both hit Yahoo Finance. Firing them back-to-back with zero gap
                            // effectively doubles the request rate and triggers Yahoo's rate limiter
                            // after ~60-70 stocks (≈30s of sustained 4-req/s traffic).
                            // A 250ms gap keeps us at a safe 2-req/s burst ceiling.
                            delay(250L)

                            // Fetch fundamentals for value analysis
                            try {
                                Log.d(TAG, "Fetching fundamentals for $symbol (exchange=$exchange)")
                                val fundResult = YahooFinanceClient.fetchFundamentals(symbol, exchange)
                                Log.d(TAG, "Fundamentals result for $symbol: ${fundResult::class.simpleName}")
                                when (fundResult) {
                                    is YahooFinanceClient.FundamentalResult.Success -> {
                                        val vr = ValueAnalyzer.analyze(
                                            fundamentals = fundResult.data,
                                            currentPrice = analysis.price,
                                            sectorMedianPe = null,
                                            w = weights
                                        )
                                        Log.d(TAG, "Value score for $symbol: ${vr.valueScore} (${vr.valueRating}) PE=${vr.trailingPe} PB=${vr.priceToBook}")
                                        analysis = analysis.copy(
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
                                    is YahooFinanceClient.FundamentalResult.RateLimited -> {
                                        // Yahoo is already stressed from our candle requests.
                                        // Back off the global pace so the NEXT stock's candle
                                        // fetch doesn't also get rate-limited (which would
                                        // increment consecutiveErrors and eventually abort the scan).
                                        currentPace = (currentPace * PACE_BACKOFF_MULT).toLong().coerceAtMost(PACE_MAX)
                                        Log.w(TAG, "Fundamentals rate limited for $symbol — slowing pace to ${currentPace}ms, adding cool-down")
                                        delay(2000L) // brief cool-down before next stock's candle fetch
                                    }
                                    is YahooFinanceClient.FundamentalResult.NoData ->
                                        Log.w(TAG, "No fundamental data for $symbol")
                                    is YahooFinanceClient.FundamentalResult.Error ->
                                        Log.w(TAG, "Fundamentals error for $symbol: ${fundResult.message}")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Fundamentals fetch failed for $symbol — technical data kept", e)
                            }
                            allStocks.add(annotateSmoothMacdTurn(analysis, previousSmoothBySymbol, previousSmoothTimestamp))
                        } else {
                            skipped++
                        }
                        // Recover pace on success
                        currentPace = (currentPace * PACE_RECOVERY_MULT).toLong().coerceAtLeast(PACE_MIN)
                    }
                    is YahooFinanceClient.CandleResult.RateLimited -> {
                        consecutiveErrors++
                        errors++
                        lastError = "Rate limited on $symbol — backing off ${currentPace}ms"
                        retryQueue.add(symbol) // queue for retry instead of skipping
                        currentPace = (currentPace * PACE_BACKOFF_MULT).toLong().coerceAtMost(PACE_MAX)
                        Log.w(TAG, "Discovery: rate limited on $symbol, pace=${currentPace}ms, queued for retry")
                        // Extra backoff wait on rate limit
                        val backoffWait = (3000L + consecutiveErrors * 500L).coerceAtMost(15000L)
                        delay(backoffWait)
                    }
                    is YahooFinanceClient.CandleResult.NoData -> {
                        consecutiveErrors = 0
                        skipped++
                    }
                    is YahooFinanceClient.CandleResult.Error -> {
                        consecutiveErrors++
                        errors++
                        lastError = "$symbol: ${candleResult.message}"
                        Log.w(TAG, "Discovery: error for $symbol — ${candleResult.message}")
                    }
                }

                // Update progress every stock (not just every 5 — gives smoother UI updates)
                val sorted = allStocks.sortedByDescending { it.buyScore }
                lastDiscoveryResult = DiscoveryScanResult(
                    market = marketName,
                    currency = currency,
                    timestamp = System.currentTimeMillis(),
                    totalScanned = allStocks.size,
                    totalSymbols = total,
                    skipped = skipped,
                    errors = errors,
                    lastError = lastError,
                    isComplete = false,
                    allStocks = sorted,
                    buySignals = sorted.filter { it.buyScore >= 60 },
                    strongBuys = sorted.filter { it.buyScore >= 75 },
                    goldenBuys = sorted.filter { it.goldenBuy },
                    setups = sorted.filter {
                        (it.sma200 != null && it.price > it.sma200) &&
                                (it.macdPhase == "BUY FLIP" || it.macdPhase == "EARLY BUY")
                    },
                    durationMs = System.currentTimeMillis() - startTime
                )
                ScanServiceResultHolder.lastDiscoveryResult = lastDiscoveryResult
                ScanServiceResultHolder.discoveryProgress = idx + 1
                ScanServiceResultHolder.discoveryTotal = total
                // Honest status: attempted / total, with a breakdown of outcomes so the user
                // understands why "analyzed" is lower than "attempted" (filtered, skipped, errored).
                val analyzed = allStocks.size
                val breakdown = "$analyzed ok · $skipped skip · $errors err"
                ScanServiceResultHolder.discoveryStatusText =
                    "Discovery $marketName: ${idx + 1}/$total — $breakdown"
                ScanServiceResultHolder.discoveryVersion++

                updateScanNotification(ScanServiceResultHolder.discoveryStatusText)

                // Periodic disk save every 25 stocks — survives mid-scan process death (MIUI)
                if ((idx + 1) % 25 == 0 && allStocks.isNotEmpty()) {
                    try {
                        DiscoveryResultStore.save(this@ScanService, lastDiscoveryResult!!)
                    } catch (e: Exception) {
                        Log.w(TAG, "Periodic save failed", e)
                    }
                }

                // Pacing delay (only once — not double-delayed on rate limits)
                delay(currentPace)

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                consecutiveErrors++
                errors++
                lastError = "$symbol: ${e.message}"
                Log.e(TAG, "Discovery error for $symbol", e)
            }
        }

        // ── Retry phase — re-scan rate-limited stocks at gentler pace ──
        if (retryQueue.isNotEmpty() && !discoveryAbort) {
            // 30s cool-down (was 10s) — gives Yahoo's rate limiter more time to reset.
            // The old 10s often wasn't enough: retry phase would start still rate-limited and
            // burn through its 20-error cap within the first few seconds.
            Log.i(TAG, "Discovery retry phase: ${retryQueue.size} stocks, cooling down 30s...")
            updateScanNotification("Discovery $marketName: cooling down 30s before retrying ${retryQueue.size}...")
            delay(30_000L)

            // ROBUST retry is even slower (3s) and tolerates more errors before giving up.
            val retryPace = if (robust) 3000L else 2000L
            val retryErrorCap = if (robust) 80 else 40
            var retryErrors = 0

            for ((rIdx, symbol) in retryQueue.withIndex()) {
                if (discoveryAbort || retryErrors >= retryErrorCap) {
                    if (retryErrors >= retryErrorCap) Log.w(TAG, "Discovery retry: hit $retryErrors errors — giving up on remaining ${retryQueue.size - rIdx}")
                    break
                }

                try {
                    val exchange = if (currency == "₹") "NSE" else "NASDAQ"
                    val candleResult = YahooFinanceClient.fetchCandles(symbol, exchange)

                    when (candleResult) {
                        is YahooFinanceClient.CandleResult.Success -> {
                            var analysis = StockAnalyzer.analyze(
                                candles = candleResult.candles,
                                symbol = symbol,
                                name = symbol,
                                token = symbol,
                                minAvgVolume = if (currency == "₹") 100000 else 50000,
                                w = weights
                            )
                            if (analysis != null) {
                                try {
                                    val fundResult = YahooFinanceClient.fetchFundamentals(symbol, exchange)
                                    if (fundResult is YahooFinanceClient.FundamentalResult.Success) {
                                        val vr = ValueAnalyzer.analyze(fundResult.data, analysis.price, w = weights)
                                        analysis = analysis.copy(
                                            trailingPe = vr.trailingPe, forwardPe = vr.forwardPe,
                                            priceToBook = vr.priceToBook, evToEbitda = vr.evToEbitda,
                                            debtToEquity = vr.debtToEquity, roce = vr.roce,
                                            revenueGrowth = vr.revenueGrowth, earningsGrowth = vr.earningsGrowth,
                                            grossMargins = vr.grossMargins, operatingMargins = vr.operatingMargins,
                                            profitMargins = vr.profitMargins, dividendYield = vr.dividendYield,
                                            operatingCashflow = vr.operatingCashflow, freeCashflow = vr.freeCashflow,
                                            netIncome = vr.netIncome, totalRevenue = vr.totalRevenue,
                                            totalCash = vr.totalCash, totalDebt = vr.totalDebt,
                                            currentRatio = vr.currentRatio,
                                            annualRevenueGrowth = vr.annualRevenueGrowth,
                                            annualNetIncomeGrowth = vr.annualNetIncomeGrowth,
                                            fiftyTwoWeekLow = vr.fiftyTwoWeekLow, fiftyTwoWeekHigh = vr.fiftyTwoWeekHigh,
                                            sharesOutstanding = vr.sharesOutstanding,
                                            sector = vr.sector, industry = vr.industry,
                                            sectorMedianPe = vr.sectorMedianPe, hasBuyback = vr.hasBuyback,
                                            valueScore = vr.valueScore, valueRating = vr.valueRating,
                                            valueScoreReport = vr.valueScoreReport
                                        )
                                    }
                                } catch (_: Exception) {}
                                allStocks.add(annotateSmoothMacdTurn(analysis, previousSmoothBySymbol, previousSmoothTimestamp))
                            } else {
                                skipped++
                            }
                        }
                        is YahooFinanceClient.CandleResult.RateLimited -> {
                            retryErrors++
                            errors++
                            delay(5000L) // extra 5s cool-down on retry rate limit
                        }
                        is YahooFinanceClient.CandleResult.NoData -> skipped++
                        is YahooFinanceClient.CandleResult.Error -> {
                            retryErrors++
                            errors++
                        }
                    }

                    // Update UI during retry
                    val sorted = allStocks.sortedByDescending { it.buyScore }
                    lastDiscoveryResult = DiscoveryScanResult(
                        market = marketName, currency = currency,
                        timestamp = System.currentTimeMillis(),
                        totalScanned = allStocks.size, totalSymbols = total,
                        skipped = skipped, errors = errors, lastError = lastError, isComplete = false,
                        allStocks = sorted,
                        buySignals = sorted.filter { it.buyScore >= 60 },
                        strongBuys = sorted.filter { it.buyScore >= 75 },
                        goldenBuys = sorted.filter { it.goldenBuy },
                        setups = sorted.filter {
                            (it.sma200 != null && it.price > it.sma200) &&
                                    (it.macdPhase == "BUY FLIP" || it.macdPhase == "EARLY BUY")
                        },
                        durationMs = System.currentTimeMillis() - startTime
                    )
                    ScanServiceResultHolder.lastDiscoveryResult = lastDiscoveryResult
                    ScanServiceResultHolder.discoveryProgress = total - retryQueue.size + rIdx + 1
                    // Retry status — show it's the retry phase so user doesn't think main scan is stuck
                    val retryAnalyzed = allStocks.size
                    ScanServiceResultHolder.discoveryStatusText =
                        "Discovery $marketName — RETRY ${rIdx + 1}/${retryQueue.size} · $retryAnalyzed total ok · $retryErrors err"
                    ScanServiceResultHolder.discoveryVersion++

                    updateScanNotification(ScanServiceResultHolder.discoveryStatusText)

                    delay(retryPace)

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    retryErrors++
                    errors++
                    Log.e(TAG, "Discovery retry error for $symbol", e)
                }
            }
        }

        // ── Final result ──
        applySectorPeMedians(allStocks, weights)
        val sorted = allStocks.sortedByDescending { it.buyScore }
        val elapsed = System.currentTimeMillis() - startTime
        lastDiscoveryResult = DiscoveryScanResult(
            market = marketName,
            currency = currency,
            timestamp = System.currentTimeMillis(),
            totalScanned = allStocks.size,
            totalSymbols = total,
            skipped = skipped,
            errors = errors,
            lastError = lastError,
            isComplete = !discoveryAbort,
            allStocks = sorted,
            buySignals = sorted.filter { it.buyScore >= 60 },
            strongBuys = sorted.filter { it.buyScore >= 75 },
            goldenBuys = sorted.filter { it.goldenBuy },
            setups = sorted.filter {
                (it.sma200 != null && it.price > it.sma200) &&
                        (it.macdPhase == "BUY FLIP" || it.macdPhase == "EARLY BUY")
            },
            durationMs = elapsed
        )
        ScanServiceResultHolder.lastDiscoveryResult = lastDiscoveryResult
        ScanServiceResultHolder.discoveryVersion++

        // Persist to disk so results survive process death
        DiscoveryResultStore.save(this@ScanService, lastDiscoveryResult!!)

        val statusMsg = if (discoveryAbort) "Discovery stopped" else
            "Discovery done: ${allStocks.size} stocks in ${elapsed / 60000}m"
        updateScanNotification(statusMsg)
        ScanServiceResultHolder.discoveryStatusText = statusMsg
        Log.i(TAG, "$statusMsg — ${sorted.count { it.buyScore >= 60 }} buy signals, " +
                "${sorted.count { it.goldenBuy }} qualified MACD setups" +
                if (retryQueue.isNotEmpty()) " (retried ${retryQueue.size})" else "")
    }

    private fun applySectorPeMedians(stocks: MutableList<StockAnalysis>, weights: ScoringWeights) {
        val medians = stocks
            .filter { !it.sector.isNullOrBlank() && it.trailingPe != null && it.trailingPe > 0.0 }
            .groupBy { it.sector!!.trim().uppercase(Locale.US) }
            .mapValues { (_, rows) -> median(rows.mapNotNull { it.trailingPe }.filter { it > 0.0 }) }

        if (medians.isEmpty()) return
        for (i in stocks.indices) {
            val stock = stocks[i]
            val medianPe = stock.sector?.trim()?.uppercase(Locale.US)?.let { medians[it] } ?: continue
            if (stock.valueRating == "N/A" && stock.valueScore == 0) continue
            val vr = ValueAnalyzer.analyzeSnapshot(stock, sectorMedianPe = medianPe, w = weights)
            stocks[i] = stock.copy(
                sectorMedianPe = medianPe,
                valueScore = vr.valueScore,
                valueRating = vr.valueRating,
                valueScoreReport = vr.valueScoreReport
            )
        }
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    // ═══════════════════════════════════════════════════════
    // FULL PORTFOLIO SCAN  (Zerodha only)
    // ═══════════════════════════════════════════════════════

    private suspend fun runFullPortfolioScan() {
        if (!config.isZerodhaConnected) {
            updateScanNotification("Zerodha not connected — open Settings")
            broadcastUpdate()
            return
        }

        // ── Wait for the Yahoo channel ──
        // If a discovery scan is in-flight, queue behind it instead of barging in
        // and corrupting its crumb session. The user's observation — tapping
        // "scan portfolio" mid-discovery causes failures — is exactly this race.
        if (yahooScanLock.isLocked) {
            Log.i(TAG, "Portfolio scan: discovery in progress, queuing behind it")
            updateScanNotification("Portfolio scan queued (discovery running)…")
        }
        yahooScanLock.withLock {
            runFullPortfolioScanLocked()
        }
    }

    private suspend fun runFullPortfolioScanLocked() {
        scanCount++
        val isFullScan = scanCount % FULL_SCAN_EVERY_N == 1 || scanCount == 1
        val scanType = if (isFullScan) "Full scan" else "Quick scan"
        updateScanNotification("$scanType: Zerodha portfolio...")

        val result = if (isFullScan) {
            // Full scan: fetch candles (cached 2y + 5d refresh), recompute all indicators
            scanZerodhaPortfolio()
        } else {
            // Quick scan: re-fetch holdings for updated LTP, reuse cached candle data
            // The fetchCandlesCached call is extremely cheap here — it only hits Yahoo
            // for 5 days of data (or returns pure cache if recently refreshed)
            scanZerodhaPortfolio()
        }

        lastZerodhaResult = result
        ScanServiceResultHolder.lastZerodhaResult = result
        ScanServiceResultHolder.portfolioVersion++

        if (isFullScan) {
            // Full scan: process all alert types
            processAlerts(result.alerts)
        } else {
            // Quick scan: only process urgent alerts (L4/L5) — L1-L3 already sent once today
            processAlerts(result.alerts.filter { isUrgentAlert(it.alertType) })
        }

        config.lastPortfolioScan = System.currentTimeMillis()

        val totalAlerts = result.alerts.size
        val statusText = when {
            totalAlerts > 0 -> "⚠ $totalAlerts alert(s) — tap to view"
            else -> "✓ Portfolio OK — no sell signals"
        }
        updateScanNotification(statusText)
        broadcastUpdate()

        // ── Daily trailing-SL + MACD-exit tick ──
        // Runs ONCE per trading day after 15:20 IST, piggy-backing on the last
        // pre-close portfolio scan so we already have fresh analysis in memory.
        // Gated by autoTrailingEnabled / autoExitEnabled — defaults OFF.
        maybeRunDailyTick(result)
    }

    /**
     * Trigger the daily trailing-SL + MACD-exit tick if:
     *   - current time ≥ 15:20 IST (weekday) AND
     *   - we haven't already ticked today (lastDailyTickEpoch is not today IST)
     *   - result holds at least one analyzed holding
     *
     * The tick itself honors config.autoTrailingEnabled / autoExitEnabled;
     * if both are false it runs in report-only mode (no API modifications),
     * which gives the user a dashboard preview before opting in.
     */
    private suspend fun maybeRunDailyTick(result: ScanResult) {
        try {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return
            val totalMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            if (totalMin < 15 * 60 + 20) return    // before 15:20 IST — not yet

            // Check we haven't ticked today (IST day boundary)
            val last = config.lastDailyTickEpoch
            if (last > 0) {
                val lastCal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
                lastCal.timeInMillis = last
                val sameDay = lastCal.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
                        lastCal.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
                if (sameDay) return
            }

            val analyzedHoldings = result.holdings.filter { it.analysis != null }
            if (analyzedHoldings.isEmpty()) return

            Log.i(TAG, "Running daily trailing-SL tick over ${analyzedHoldings.size} holdings")
            val mgr = com.signalscope.app.trading.DailyPortfolioManager(applicationContext, config)
            // Dry-run whenever BOTH switches are off — user gets visibility without side-effects
            val dryRun = !config.autoTrailingEnabled && !config.autoExitEnabled
            val report = mgr.runDailyTick(analyzedHoldings, dryRun = dryRun)
            Log.i(TAG, "Daily tick: ${report.slUpdates} SL updates · ${report.exitsFilled}/${report.exitsAttempted} exits · ${report.upperTargetsDeleted} targets removed · ${report.errors.size} errors")
            report.perSymbolLog.forEach { Log.d(TAG, "  • $it") }
            report.errors.forEach { Log.w(TAG, "  ✗ $it") }
        } catch (e: Exception) {
            Log.e(TAG, "Daily tick failed", e)
        }
    }

    /**
     * Fast protection pass for newly detected Zerodha holdings.
     *
     * The daily 15:20 OCO builder waits for cleaner support/resistance data. This
     * pass is deliberately simpler: every portfolio scan checks whether a holding
     * already has an active SELL stop below LTP. If not, it places a single-leg
     * emergency SL around entry * (1 - configured hard-stop %).
     */
    private suspend fun ensureEmergencyStopGtts(holdings: List<PortfolioHolding>) {
        if (!config.autoCreateGtt || holdings.isEmpty()) return

        val gttList = when (val r = zerodhaClient.fetchGttTriggers()) {
            is ZerodhaClient.GttListResult.Success -> r.triggers
            is ZerodhaClient.GttListResult.Expired -> {
                config.zerodhaAccessToken = ""
                sendZerodhaExpiredNotification()
                return
            }
            is ZerodhaClient.GttListResult.Failure -> {
                Log.w(TAG, "Emergency SL skipped: could not fetch GTTs (${r.message})")
                return
            }
        }

        val activeSellGttsBySymbol = gttList
            .filter { it.status.equals("active", ignoreCase = true) && it.transactionType.equals("SELL", ignoreCase = true) }
            .groupBy { it.tradingsymbol.uppercase(Locale.US) }

        for (h in holdings) {
            val ltp = h.ltp
            val entry = if (h.avgPrice > 0.0) h.avgPrice else ltp
            if (h.quantity <= 0 || ltp <= 0.0 || entry <= 0.0) continue

            val symbolKey = h.symbol.uppercase(Locale.US)
            val existing = activeSellGttsBySymbol[symbolKey].orEmpty()
            val existingStop = existing
                .filter { it.triggerPrice > 0.0 && it.triggerPrice < ltp }
                .maxByOrNull { it.triggerPrice }

            val rawTrigger = entry * config.newPositionHardStopFraction
            val trigger = roundToTick(rawTrigger.coerceIn(ltp * 0.75, ltp * 0.999))
            val limit = roundToTick(trigger * (1.0 - config.slLimitPct))
            val stopPct = (trigger / entry - 1.0) * 100.0

            if (existingStop == null) {
                val result = zerodhaClient.createGttTrigger(
                    type = "single",
                    exchange = h.exchange,
                    tradingsymbol = h.symbol,
                    triggerValues = listOf(trigger),
                    lastPrice = ltp,
                    orders = listOf(
                        ZerodhaClient.GttOrderSpec(
                            transactionType = "SELL",
                            quantity = h.quantity,
                            price = limit,
                            product = "CNC",
                            orderType = "LIMIT"
                        )
                    )
                )
                when (result) {
                    is ZerodhaClient.GttCreateResult.Success -> {
                        Log.i(TAG, "Emergency SL GTT #${result.id} created for ${h.symbol} at $trigger")
                        com.signalscope.app.data.GttActivityLog.append(
                            config.appContext,
                            "emergency_sl_create",
                            symbol = h.symbol,
                            fields = mapOf(
                                "id" to result.id,
                                "entry" to entry,
                                "ltp" to ltp,
                                "trigger" to trigger,
                                "limit" to limit,
                                "pctFromEntry" to stopPct
                            )
                        )
                    }
                    is ZerodhaClient.GttCreateResult.Failure ->
                        Log.w(TAG, "Emergency SL create failed for ${h.symbol}: ${result.message}")
                }
                delay(1000L)
            } else if (existingStop.triggerType == "single" && trigger > existingStop.triggerPrice + 0.05) {
                val result = zerodhaClient.setGttTriggerPrices(
                    existingStop.id,
                    newTriggerValues = listOf(trigger),
                    newLimitPrices = listOf(limit)
                )
                if (result is ZerodhaClient.GttModifyResult.Success) {
                    Log.i(TAG, "Emergency SL raised for ${h.symbol}: ${existingStop.triggerPrice} -> $trigger")
                    com.signalscope.app.data.GttActivityLog.append(
                        config.appContext,
                        "emergency_sl_raise",
                        symbol = h.symbol,
                        fields = mapOf(
                            "id" to existingStop.id,
                            "old" to existingStop.triggerPrice,
                            "new" to trigger,
                            "limit" to limit,
                            "reason" to "configured hard floor"
                        )
                    )
                    delay(1000L)
                }
            } else {
                com.signalscope.app.data.GttActivityLog.append(
                    config.appContext,
                    "skip",
                    symbol = h.symbol,
                    fields = mapOf(
                        "path" to "emergencySl",
                        "existing" to (existingStop?.triggerPrice ?: 0.0),
                        "candidate" to trigger,
                        "reason" to "existing SL is already equal/better or OCO-managed"
                    )
                )
            }
        }
    }

    private fun roundToTick(value: Double): Double =
        Math.round(value * 20.0) / 20.0

    private suspend fun scanZerodhaPortfolio(): ScanResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val alerts = mutableListOf<StockAlert>()
        val scannedHoldings = mutableListOf<PortfolioHolding>()

        val holdingsResult = zerodhaClient.fetchHoldings()
        val rawHoldings = when (holdingsResult) {
            is ZerodhaClient.HoldingsResult.Success -> holdingsResult.holdings
            is ZerodhaClient.HoldingsResult.Expired -> {
                config.zerodhaAccessToken = ""
                sendZerodhaExpiredNotification()
                return@withContext emptyScanResult(startTime)
            }
            is ZerodhaClient.HoldingsResult.Failure -> {
                Log.e(TAG, "Zerodha holdings failed: ${holdingsResult.message}")
                return@withContext emptyScanResult(startTime)
            }
        }

        ensureEmergencyStopGtts(rawHoldings)

        for (holding in rawHoldings) {
            try {
                // Use cached candles for portfolio scans — avoids re-fetching 2y of
                // daily data every 15 min. Only today's candle gets refreshed.
                val candleResult = YahooFinanceClient.fetchCandlesCached(holding.symbol, holding.exchange)
                when (candleResult) {
                    is YahooFinanceClient.CandleResult.Success -> {
                        // ── B: EOD-confirmed scoring ──
                        // During live trading hours, today's daily candle is partial
                        // (incomplete OHLC + running volume). MACD/RSI/OBV computed on
                        // a partial bar oscillate intraday and produce score-flicker.
                        // We drop today's bar so the soft-sniper's score reflects
                        // only confirmed daily structure. Live LTP still drives the
                        // trigger price — we use EOD signal + intraday execution.
                        // After 15:30 IST today's bar is final; we use the full series.
                        val rawCandles = candleResult.candles
                        val candlesForAnalysis =
                            if (isLiveTradingHours() && rawCandles.size >= 2)
                                rawCandles.dropLast(1)
                            else rawCandles

                        val analysis = StockAnalyzer.analyze(
                            candles = candlesForAnalysis,
                            symbol = holding.symbol, name = holding.symbol,
                            token = holding.symbol, minAvgVolume = 0,
                            w = weights
                        )
                        val verdict = computeVerdict(analysis, holding.totalReturnPct)
                        scannedHoldings.add(holding.copy(analysis = analysis, verdict = verdict))
                        if (analysis != null) {
                            // ── A: Score persistence streak update ──
                            // Track consecutive scan ticks where profitScore stayed
                            // ≥ activation threshold. maybePlaceSoftSniper requires
                            // streak ≥ 2 — kills single-tick intraday flicker firings.
                            val today = todayTradingDate()
                            val streakKey = "$today|${holding.symbol}"
                            val streakMap = ScanServiceResultHolder.profitScoreStreak
                            if (analysis.profitScore >= weights.profitActivationThreshold) {
                                streakMap[streakKey] = (streakMap[streakKey] ?: 0) + 1
                            } else {
                                streakMap.remove(streakKey)
                            }

                            generateAlerts(holding, analysis, holding.totalReturnPct)
                                ?.let { alerts.addAll(it) }
                        }
                        delay(600)
                    }
                    is YahooFinanceClient.CandleResult.RateLimited -> {
                        delay(10_000)
                    }
                    else -> scannedHoldings.add(holding.copy(verdict = "NO DATA"))
                }
            } catch (_: Exception) {
                scannedHoldings.add(holding.copy(verdict = "ERROR"))
            }
        }
        buildScanResult(startTime, scannedHoldings, alerts)
    }

    // ═══════════════════════════════════════════════════════
    // MARKET HOURS CHECK (portfolio monitoring only)
    // ═══════════════════════════════════════════════════════

    private fun isMarketOpen(): Boolean {
        if (!config.scanDuringMarketHoursOnly) return true

        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val totalMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) return false
        return totalMin in (9 * 60 + 15)..(15 * 60 + 30) // 9:15 AM to 3:30 PM IST (actual NSE hours)
    }

    /**
     * True only when the NSE is *currently* in live continuous trading.
     * Unlike isMarketOpen(), this ignores the user's scanDuringMarketHoursOnly
     * config — it's a pure wall-clock check used by:
     *   - EOD-confirmed scoring (drop today's partial bar during the session)
     *   - TIGHT-tier time-of-day gate in maybePlaceSoftSniper (≥ 14:00 IST)
     */
    private fun isLiveTradingHours(): Boolean {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        val day = cal.get(Calendar.DAY_OF_WEEK)
        if (day == Calendar.SATURDAY || day == Calendar.SUNDAY) return false
        val totalMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return totalMin in (9 * 60 + 15)..(15 * 60 + 30)
    }

    /** Returns IST hour-of-day as a fractional double (e.g. 14.5 = 2:30 PM IST). */
    private fun istHourFraction(): Double {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        return cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0
    }

    // ═══════════════════════════════════════════════════════
    // VERDICT + ALERTS (unchanged from your code)
    // ═══════════════════════════════════════════════════════

    /** Verdict now uses the intent label from the dual scoring system. */
    private fun computeVerdict(analysis: StockAnalysis?, returnPct: Double): String {
        if (analysis == null) return "HOLD"
        return analysis.sellIntent  // "STRONG EXIT", "BOOK PROFIT", "PROTECT CAPITAL", or "HOLD"
    }

    /**
     * Generate alerts from the dual scoring system.
     * - Intent-based: STRONG_EXIT, BOOK_PROFIT, PROTECT_CAPITAL (from sellIntent)
     * - Stage 1 warning: PEAK_WARNING (earlySell / EARLY SELL phase — prepare to sell)
     */
    private fun generateAlerts(
        holding: PortfolioHolding, analysis: StockAnalysis,
        returnPct: Double
    ): List<StockAlert>? {
        val source = "zerodha"
        val alerts = mutableListOf<StockAlert>()

        // Intent-based alerts from dual scoring
        when (analysis.sellIntent) {
            "STRONG EXIT" -> alerts.add(StockAlert(
                symbol = holding.symbol, name = holding.symbol,
                alertType = AlertType.STRONG_EXIT,
                message = "Profit ${analysis.profitScore}/58 + Protect ${analysis.protectScore}/58 — sell all",
                sellScore = analysis.sellScore, buyScore = analysis.buyScore,
                price = analysis.price, macdPhase = analysis.macdPhase, source = source))

            "BOOK PROFIT" -> {
                // Suppress if DPM histogram-watchdog already fired for this symbol today.
                // The watchdog is a stricter, sustained-confirmation MACD signal — its
                // sniper GTT / exit ladder action supersedes this advisory.
                val today = todayTradingDate()
                if (!ScanServiceResultHolder.isMacdWatchdogFired(holding.symbol, today)) {
                    alerts.add(StockAlert(
                        symbol = holding.symbol, name = holding.symbol,
                        alertType = AlertType.BOOK_PROFIT,
                        message = "Profit score ${analysis.profitScore}/58 — dual-score sell signal, consider booking 50-70%",
                        sellScore = analysis.sellScore, buyScore = analysis.buyScore,
                        price = analysis.price, macdPhase = analysis.macdPhase, source = source))
                    // Tier 1.5 — opportunistic auto-GTT placement. Fire-and-forget: gates
                    // are checked inside, API call is on a daemon thread so the scan loop
                    // is not blocked. Success appears as a SOFT_SNIPER alert in the panel.
                    maybePlaceSoftSniper(holding, analysis, returnPct, today)
                } else {
                    // Visible to logcat for audit; the user sees the MACD_FLIP alert
                    // in the panel instead, which conveys the same "sell now" intent
                    // backed by a stricter signal.
                    Log.i(TAG, "BOOK_PROFIT suppressed for ${holding.symbol} — MACD_FLIP already fired today")
                }
            }

            "PROTECT CAPITAL" -> alerts.add(StockAlert(
                symbol = holding.symbol, name = holding.symbol,
                alertType = AlertType.PROTECT_CAPITAL,
                message = "Capital Protection ${analysis.protectScore}/58 — trend broken, exit position",
                sellScore = analysis.sellScore, buyScore = analysis.buyScore,
                price = analysis.price, macdPhase = analysis.macdPhase, source = source))
        }

        // Stage 1 warning: MACD decelerating (EARLY SELL phase) — prepare to sell
        // Only fire if no stronger sell intent already generated
        if (analysis.macdPhase == "EARLY SELL" && analysis.sellIntent == "HOLD") {
            alerts.add(StockAlert(
                symbol = holding.symbol, name = holding.symbol,
                alertType = AlertType.PEAK_WARNING,
                message = "MACD decelerating (EARLY SELL phase) — DPM watchdog monitoring; will auto-confirm at 15:20 if histogram sustains < 50% of peak",
                sellScore = analysis.sellScore, buyScore = analysis.buyScore,
                price = analysis.price, macdPhase = analysis.macdPhase, source = source))
        }

        return if (alerts.isNotEmpty()) alerts else null
    }

    // ── Tier 1.5 Soft Sniper ─────────────────────────────────────────────────
    //
    //   Bridges the gap between Tier 1 (BOOK_PROFIT advisory notification, fast)
    //   and Tier 2 (DPM histogram watchdog with sniper GTT, slow — waits
    //   minBarsFilter daily closes for confirmation).
    //
    //   Triggered: BOOK_PROFIT alert is being generated AND user opted in.
    //   Action:    cancel existing SELL GTTs, place single-leg SELL GTT at
    //              LTP × (1 − softSniperBufferPct). Default 3% buffer so normal
    //              pullbacks don't fire it; tight enough that real continuation
    //              caps drawdown at ~3-4%.
    //
    //   Handoff:   if the daily watchdog later confirms, replaceSniperGtt() in
    //              DailyPortfolioManager already cancels existing SELL GTTs
    //              before creating its tight 1.5% sniper — so the soft sniper
    //              gets cleanly replaced by the hard sniper without any extra
    //              code. No GTT can stack on top of another for the same symbol.
    //
    //   One-shot:  softSniperFiredKeys ("yyyy-MM-dd|SYMBOL") prevents repeat
    //              placements on subsequent 15-min scans the same trading day.
    private fun maybePlaceSoftSniper(
        holding: PortfolioHolding,
        analysis: StockAnalysis,
        returnPct: Double,
        today: String
    ) {
        // Gate 1: master switches
        if (!config.autoSoftSniperEnabled) return
        if (!config.autoCreateGtt) return  // soft sniper inherits the GTT master gate

        // Gate 2: gain threshold — don't auto-protect underwater or marginal positions
        val gainPct = returnPct  // already provided as % (e.g. 7.3 for +7.3%)
        if (gainPct < config.softSniperMinGainPct) {
            Log.i(TAG, "Soft sniper skipped for ${holding.symbol}: gain ${"%.1f".format(gainPct)}% < threshold ${config.softSniperMinGainPct}%")
            return
        }

        // Gate 3: one-shot per symbol per trading day
        if (ScanServiceResultHolder.isSoftSniperFired(holding.symbol, today)) {
            return  // silent — already fired earlier in the same session
        }

        // Gate 4: don't fight the hard watchdog — if it already fired, it owns the
        // GTT slot. Defensive: BOOK_PROFIT branch also checks this, but defend in depth.
        if (ScanServiceResultHolder.isMacdWatchdogFired(holding.symbol, today)) return

        // Gate 5: data sanity
        if (holding.ltp <= 0 || holding.quantity <= 0) return

        // Gate 6 (A): score persistence — require profitScore ≥ threshold to
        // have held for at least 2 consecutive 15-min ticks (~30 min) before
        // committing capital. Kills single-tick intraday flicker fires that
        // happen because daily indicators reading a partial bar are noisy.
        // The streak counter is updated every scan in the holdings loop.
        val streakKey = "$today|${holding.symbol}"
        val streak = ScanServiceResultHolder.profitScoreStreak[streakKey] ?: 0
        if (streak < 2) {
            Log.i(
                TAG,
                "[SOFT SNIPER] ${holding.symbol} score-persistence wait: streak=$streak/2 " +
                "(profitScore=${analysis.profitScore}). Will fire next tick if score holds."
            )
            return
        }

        // ─── DYNAMIC BUFFER SELECTION (Gemini's volume-confirmation tier) ───
        // Pick one of three buffer widths based on OBV + raw-volume conviction.
        // Or skip entirely if volume is weak (likely false positive on thin trade).
        val volNow = analysis.volume
        val volAvg = analysis.avgVol20
        val volRatio = if (volAvg > 0) volNow / volAvg else 1.0
        val volumeSpike = volRatio >= config.softSniperVolumeSpikeMultiple
        val volumeWeak  = volAvg > 0 && volRatio < config.softSniperVolumeWeakMultiple
        val macdFlipped = analysis.macdSlope < 0   // momentum has rolled over

        // Gate 6: thin-trade veto — score crossed but nobody is participating.
        // This is the false-positive guard Gemini's framework recommends.
        if (volumeWeak) {
            Log.i(
                TAG,
                "[SOFT SNIPER] ${holding.symbol} SKIPPED: profitScore=${analysis.profitScore} " +
                "but volume only ${"%.0f".format(volRatio * 100)}% of 20d avg " +
                "(threshold ${(config.softSniperVolumeWeakMultiple * 100).toInt()}%) — likely noise"
            )
            return
        }

        // Gate (C): TIGHT tier is only allowed after 14:00 IST. Before then,
        // today's running volume is too small a fraction of full-day to make
        // the 1.30× spike comparison meaningful (at 10 AM today is ~15% of a
        // typical full day's volume — 1.30× there means almost nothing).
        // After 14:00 ~76% of the session has elapsed and the comparison is
        // structurally fair. Early "spikes" get downgraded to NORMAL buffer.
        val tightAllowed = istHourFraction() >= 14.0

        val (bufferPct, bufferTier) = when {
            volumeSpike && macdFlipped && tightAllowed ->
                // Smart money is flushing on a momentum break — exit fast.
                config.softSniperTightBufferPct to "TIGHT"
            volumeSpike && macdFlipped && !tightAllowed ->
                // Conviction signal but session is too young to trust the
                // volume comparison — defensive 3% instead of aggressive 1.5%.
                config.softSniperBufferPct to "NORMAL_EARLY"
            analysis.obvDivergence ->
                // OBV divergence with normal volume — defensive default.
                config.softSniperBufferPct to "NORMAL"
            else ->
                // Score crossed but neither volume spike nor OBV divergence —
                // give the trade room before booking out.
                config.softSniperLooseBufferPct to "LOOSE"
        }
        Log.i(
            TAG,
            "[SOFT SNIPER] ${holding.symbol} buffer=$bufferTier (${(bufferPct*100).toInt()}%) " +
            "volRatio=${"%.2f".format(volRatio)}x macdSlope=${"%.4f".format(analysis.macdSlope)} " +
            "obvDiv=${analysis.obvDivergence}"
        )

        // Mark BEFORE the API call so concurrent ticks (or a retry path) can't
        // fire a second placement. If the API call later fails we still keep
        // the key — failing closed is safer than risking a duplicate GTT.
        ScanServiceResultHolder.softSniperFiredKeys.add("$today|${holding.symbol}")

        // Compute target levels — round to 0.05 ticks (Kite minimum)
        val triggerRaw = holding.ltp * (1.0 - bufferPct)
        val triggerPrice = (Math.round(triggerRaw * 20.0) / 20.0)
            .coerceIn(holding.ltp * 0.75, holding.ltp * 0.999)  // Kite ±25% rule + must be < LTP
        val limitPrice = Math.round(triggerPrice * (1.0 - config.softSniperLimitPct) * 20.0) / 20.0

        // Fire-and-forget so the scan loop isn't blocked by the API round-trip.
        // Pace deletes + create with the standard 1s gap to stay well under
        // Zerodha's 3 calls/sec rate limit.
        kotlin.concurrent.thread(isDaemon = true, name = "soft-sniper-${holding.symbol}") {
            try {
                // 1. Cancel existing SELL GTTs (preserve BUY GTTs — those are
                //    independent buy-the-dip ladders we must not touch).
                val gttList = zerodhaClient.fetchGttTriggers()
                if (gttList is ZerodhaClient.GttListResult.Success) {
                    val sellGtts = gttList.triggers.filter {
                        it.status == "active" &&
                        it.tradingsymbol == holding.symbol &&
                        it.transactionType.equals("SELL", ignoreCase = true)
                    }
                    for (g in sellGtts) {
                        zerodhaClient.deleteGttTrigger(g.id)
                        Thread.sleep(1000L)
                    }
                    if (sellGtts.isNotEmpty()) Thread.sleep(1000L) // extra spacer before create
                }

                // 2. Place single-leg SELL GTT
                val result = zerodhaClient.createGttTrigger(
                    type = "single",
                    exchange = holding.exchange,
                    tradingsymbol = holding.symbol,
                    triggerValues = listOf(triggerPrice),
                    lastPrice = holding.ltp,
                    orders = listOf(
                        ZerodhaClient.GttOrderSpec(
                            transactionType = "SELL",
                            quantity = holding.quantity,
                            price = limitPrice,
                            product = "CNC",
                            orderType = "LIMIT"
                        )
                    )
                )

                when (result) {
                    is ZerodhaClient.GttCreateResult.Success -> {
                        val pctFromLtp = (triggerPrice / holding.ltp - 1) * 100
                        val msg = "Auto-GTT [$bufferTier] @ ₹${"%.2f".format(triggerPrice)} (${"%.1f".format(pctFromLtp)}%) — limit ₹${"%.2f".format(limitPrice)}, gain ${"%.1f".format(gainPct)}%, vol ${"%.1f".format(volRatio)}x"
                        Log.i(TAG, "[SOFT SNIPER] ${holding.symbol} → GTT #${result.id} placed. $msg")
                        ScanServiceResultHolder.softSniperAlerts.add(StockAlert(
                            symbol = holding.symbol, name = holding.symbol,
                            alertType = AlertType.SOFT_SNIPER,
                            message = msg,
                            sellScore = analysis.sellScore, buyScore = analysis.buyScore,
                            price = holding.ltp, macdPhase = analysis.macdPhase, source = "zerodha"
                        ))
                        // Bump portfolioVersion so the dashboard re-renders and
                        // shows the new GTT chip + SOFT_SNIPER alert immediately.
                        ScanServiceResultHolder.portfolioVersion++
                    }
                    is ZerodhaClient.GttCreateResult.Failure -> {
                        Log.w(TAG, "[SOFT SNIPER] ${holding.symbol} create failed: ${result.message}")
                        // Fired-key stays — better to skip retries than risk dupes.
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[SOFT SNIPER] ${holding.symbol} thread crashed", e)
            }
        }
    }

    private fun processAlerts(alerts: List<StockAlert>) {
        if (!config.notificationsEnabled) return
        val now = System.currentTimeMillis()
        val today = todayTradingDate()

        // Reset daily alert set on new trading day
        if (today != lastTradingDate) {
            dailyAlertsSent.clear()
            lastTradingDate = today
        }

        // Periodic cleanup: remove entries older than 24 hours to prevent unbounded growth
        if (recentAlerts.size > 100) {
            val dayAgo = now - 24 * 60 * 60 * 1000L
            recentAlerts.entries.removeAll { it.value < dayAgo }
        }

        for (alert in alerts) {
            if (isUrgentAlert(alert.alertType)) {
                // L4/L5: time-based cooldown — can re-fire if condition persists
                val key = "${alert.symbol}_${alert.alertType}_${alert.source}"
                val lastAlerted = recentAlerts[key] ?: 0L
                if (now - lastAlerted > alertCooldownMs(alert.alertType)) {
                    sendAlertNotification(alert)
                    recentAlerts[key] = now
                }
            } else {
                // L1/L2/L3: once per trading day — daily indicators don't change intraday
                val dailyKey = "${alert.symbol}_${alert.alertType}_$today"
                if (dailyKey !in dailyAlertsSent) {
                    sendAlertNotification(alert)
                    dailyAlertsSent.add(dailyKey)
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // NOTIFICATIONS
    // ═══════════════════════════════════════════════════════

    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_SCAN, "Scan Status", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Background scan progress"; setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_ALERTS, "Stock Alerts", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Sell signals and portfolio alerts"; enableVibration(config.vibrateOnAlerts); setShowBadge(true) })
        // MACD exit channel — registered here for completeness but DailyPortfolioManager
        // also self-registers via ensureMacdExitChannel() so it works even if ScanService
        // hasn't run yet in a session.
        DailyPortfolioManager.ensureMacdExitChannel(this)
    }

    private fun buildScanNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_SCAN)
            .setContentTitle("SignalScope")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(pi).setOngoing(true).setSilent(true).build()
    }

    private fun updateScanNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_SCAN_ID, buildScanNotification(text))
    }

    @Suppress("DEPRECATION")
    private fun sendAlertNotification(alert: StockAlert) {
        val title = when (alert.alertType) {
            AlertType.STRONG_EXIT     -> "🔴 STRONG EXIT: ${alert.symbol}"
            AlertType.BOOK_PROFIT     -> "💰 BOOK PROFIT: ${alert.symbol}"
            AlertType.PROTECT_CAPITAL -> "TREND BROKEN: ${alert.symbol}"
            AlertType.PEAK_WARNING    -> "⚡ Peak Approaching: ${alert.symbol}"
            AlertType.MACD_FLIP       -> "🚨 MACD FLIP: ${alert.symbol}"
            AlertType.GOLDEN_BUY      -> "MACD Setup: ${alert.symbol}"
            AlertType.STRONG_BUY      -> "🟢 Buy: ${alert.symbol}"
            else -> "📊 ${alert.alertType}: ${alert.symbol}"
        }
        val priority = when (alert.alertType) {
            AlertType.STRONG_EXIT     -> NotificationCompat.PRIORITY_MAX
            AlertType.BOOK_PROFIT     -> NotificationCompat.PRIORITY_HIGH
            AlertType.PROTECT_CAPITAL -> NotificationCompat.PRIORITY_HIGH
            AlertType.PEAK_WARNING    -> NotificationCompat.PRIORITY_DEFAULT
            AlertType.MACD_FLIP       -> NotificationCompat.PRIORITY_MAX  // DPM channel handles real-time push; this is panel-only
            AlertType.GOLDEN_BUY      -> NotificationCompat.PRIORITY_DEFAULT
            AlertType.STRONG_BUY      -> NotificationCompat.PRIORITY_DEFAULT
            else -> NotificationCompat.PRIORITY_LOW
        }
        val alertColor = when (alert.alertType) {
            AlertType.STRONG_EXIT     -> 0xFFDC2626.toInt()  // Red
            AlertType.BOOK_PROFIT     -> 0xFFF97316.toInt()  // Orange
            AlertType.PROTECT_CAPITAL -> 0xFFEAB308.toInt()  // Yellow
            AlertType.PEAK_WARNING    -> 0xFF8B5CF6.toInt()  // Purple
            AlertType.MACD_FLIP       -> 0xFFB91C1C.toInt()  // Deep red — watchdog confirmed
            AlertType.GOLDEN_BUY      -> 0xFFD97706.toInt()  // Setup amber
            AlertType.STRONG_BUY      -> 0xFF059669.toInt()  // Green
            else -> 0xFF64748B.toInt()
        }

        val levelTag = when (alert.alertType) {
            AlertType.STRONG_EXIT     -> "⏱ Sell all — both scores firing"
            AlertType.BOOK_PROFIT     -> "⏱ Sell 50-70%, trail the rest"
            AlertType.PROTECT_CAPITAL -> "⏱ Sell entire position — trend broken"
            AlertType.PEAK_WARNING    -> "⏱ Prepare to sell — MACD slope may flip soon"
            AlertType.MACD_FLIP       -> "🚨 Watchdog confirmed — sniper GTT / exit ladder fired"
            else -> ""
        }

        val bigBody = buildString {
            append(alert.message)
            append("\n₹${String.format(Locale.US, "%.2f", alert.price)} · ${alert.macdPhase}")
            if (levelTag.isNotEmpty()) append("\n$levelTag")
        }

        val pi = PendingIntent.getActivity(this, alert.symbol.hashCode(),
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setContentTitle(title).setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigBody))
            .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentIntent(pi)
            .setColor(alertColor)
            .setColorized(alert.alertType == AlertType.STRONG_EXIT)
            .setPriority(priority).setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .apply {
                if (config.vibrateOnAlerts) {
                    when (alert.alertType) {
                        AlertType.STRONG_EXIT     -> setVibrate(longArrayOf(0, 400, 150, 400, 150, 400))
                        AlertType.BOOK_PROFIT     -> setVibrate(longArrayOf(0, 300, 100, 300))
                        AlertType.PROTECT_CAPITAL -> setVibrate(longArrayOf(0, 250, 100, 250))
                        else -> {}
                    }
                }
            }
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(alert.symbol.hashCode() + 1000, notification)
    }

    private fun sendZerodhaExpiredNotification() {
        val pi = PendingIntent.getActivity(this, 9999, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setContentTitle("Zerodha session expired")
            .setContentText("Open SignalScope to reconnect your Zerodha account")
            .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(9999, notification)
    }

    private fun broadcastUpdate() {
        sendBroadcast(Intent(BROADCAST_SCAN_UPDATE).apply {
            putExtra(EXTRA_SCAN_STATUS, "scan_complete")
        })
    }

    private fun broadcastDiscoveryUpdate(market: String, progress: Int, total: Int, statusText: String = "") {
        sendBroadcast(Intent(BROADCAST_DISCOVERY_UPDATE).apply {
            putExtra(EXTRA_DISCOVERY_MARKET, market)
            putExtra(EXTRA_DISCOVERY_PROGRESS, progress)
            putExtra(EXTRA_DISCOVERY_TOTAL, total)
            putExtra(EXTRA_DISCOVERY_STATUS_TEXT, statusText)
        })
    }

    // ═══════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════

    private fun buildScanResult(startTime: Long,
                                holdings: List<PortfolioHolding>, alerts: List<StockAlert>): ScanResult {
        val totalInvested = holdings.sumOf { it.invested }
        val totalCurrent = holdings.sumOf { it.currentVal }
        return ScanResult(market = "zerodha", startTime = startTime, totalScanned = holdings.size,
            holdings = holdings, alerts = alerts, durationMs = System.currentTimeMillis() - startTime,
            totalInvested = totalInvested, totalCurrent = totalCurrent, totalPnl = totalCurrent - totalInvested,
            sellAlertCount = alerts.count { it.alertType == AlertType.STRONG_EXIT || it.alertType == AlertType.BOOK_PROFIT || it.alertType == AlertType.PROTECT_CAPITAL })
    }

    private fun emptyScanResult(startTime: Long) = ScanResult(
        market = "zerodha", startTime = startTime, totalScanned = 0,
        holdings = emptyList(), alerts = emptyList(), durationMs = System.currentTimeMillis() - startTime)
}

// ═══════════════════════════════════════════════════════
// SYMBOL LISTS for discovery scanning
// ═══════════════════════════════════════════════════════

/**
 * Full NIFTY 500 universe for Yahoo Finance (.NS suffix added automatically).
 * Mirrors the Python app's FALLBACK_SET: NIFTY 50 + NIFTY Next 50 + BSE 100 +
 * Midcap 150 + Smallcap 250 = ~471 unique symbols.
 */
val NIFTY_500_YAHOO_SYMBOLS = listOf(
    // ── NIFTY 50 ──
    "ADANIENT","ADANIPORTS","APOLLOHOSP","ASIANPAINT","AXISBANK",
    "BAJAJ-AUTO","BAJFINANCE","BAJAJFINSV","BEL","BPCL",
    "BHARTIARTL","BRITANNIA","CIPLA","COALINDIA","DRREDDY",
    "EICHERMOT","ETERNAL","GRASIM","HCLTECH","HDFCBANK",
    "HDFCLIFE","HEROMOTOCO","HINDALCO","HINDUNILVR","ICICIBANK",
    "ITC","INDUSINDBK","INFY","JSWSTEEL","KOTAKBANK",
    "LT","M&M","MARUTI","NTPC","NESTLEIND",
    "ONGC","POWERGRID","RELIANCE","SBILIFE","SBIN",
    "SUNPHARMA","TCS","TATACONSUM","TATAMOTORS","TATASTEEL",
    "TECHM","TITAN","TRENT","ULTRACEMCO","WIPRO",

    // ── NIFTY NEXT 50 ──
    "ABB","ABBOTINDIA","AMBUJACEM","BAJAJHLDNG","BANKBARODA",
    "BERGEPAINT","BOSCHLTD","CANBK","CHOLAFIN","COLPAL",
    "DABUR","DIVISLAB","DLF","GAIL","GODREJCP",
    "HAVELLS","HINDPETRO","ICICIPRULI","INDHOTEL","IOC",
    "IRCTC","IRFC","JIOFIN","JSWENERGY","LICI",
    "LTIM","LUPIN","MARICO","MAXHEALTH","NHPC",
    "PFC","PIDILITIND","PNB","RECLTD","SBICARD",
    "SHREECEM","SHRIRAMFIN","SIEMENS","SRF","TORNTPHARM",
    "TVSMOTOR","UNIONBANK","UNITDSPR","VBL","VEDL",
    "YESBANK","ZOMATO","ZYDUSLIFE","IDEA","MOTHERSON",

    // ── BSE 100 extras ──
    "ADANIGREEN","ADANIPOWER","MUTHOOTFIN","CUMMINSIND","POLYCAB",
    "PERSISTENT","SUZLON","FEDERALBNK","INDUSTOWER","BSE",
    "BHEL","HDFCAMC","COFORGE","AUROPHARMA","TATAPOWER",
    "DIXON","IREDA","ACC","BIOCON","GODREJPROP",

    // ── NIFTY 100 / BSE extras ──
    "ADANIENERGY","ADANITOTAL","INDIANB","ASHOKLEY","PBFINTECH",
    "SWIGGY","AUBANK","IDFCFIRSTB","OBEROIRLTY","OFSS",
    "PIIND","ESCORTS","PAGEIND","MPHASIS","TATAELXSI",
    "KPITTECH","VOLTAS","LODHA","DELHIVERY","MRF",
    "SUNDARMFIN","LICHSGFIN","MFSL","NATIONALUM","HUDCO",
    "BANKINDIA","ALKEM","HINDCOPPER","SAIL","PETRONET",

    // ── NIFTY Midcap 150 extras ──
    "GUJGASLTD","SJVN","NMDC","BALKRISIND","SUPREMEIND",
    "APLAPOLLO","PHOENIXLTD","ASTRAL","JUBLFOOD","MANAPPURAM",
    "DEEPAKNTR","CROMPTON","SONACOMS","KALYANKJIL","METROBRAND",
    "BSOFT","PATANJALI","JSL","THERMAX","SYNGENE",
    "HONAUT","EXIDEIND","KEI","CGPOWER","PRESTIGE",
    "IPCALAB","CONCOR","BANDHANBNK","FORTIS","RBLBANK",
    "IDBIBANK","GMRAIRPORT","INDIAMART","ANGELONE","ZEEL",
    "LALPATHLAB","AJANTPHARM","TATACOMM","BDL","COCHINSHIP",
    "TATACHEM","ABCAPITAL","POONAWALLA","CAMS","CLEAN",
    "KAYNES","SUNTV","NAVINFLUOR","RVNL","TIINDIA",
    "ENDURANCE","SOLARINDS","NAUKRI","CENTRALBK","NIACL",
    "EMAMILTD","GLAXO","AIAENG","IIFL","RAJESHEXPO",
    "ABFRL","BLUESTARLT","KANSAINER","SUNDRMFAST","EIDPARRY",
    "MAHABANK","LINDEINDIA","BRIGADE","GRINDWELL","SCHAEFFLER",
    "APTUS","JKCEMENT","ASTRAZEN","CYIENT","NUVOCO",
    "AAVAS","HAPPSTMNDS","RATNAMANI","FIVESTAR","GPPL",
    "SUMICHEM","TRIVENI","ZENSARTECH","POLYMED","FINCABLES",
    "TIMKEN","PGHH","MCX","LAURUSLABS","CDSL",

    // ── NIFTY Smallcap 250 extras ──
    "GLAND","JBCHEPHARM","KARURVYSYA","RADICO","NARAYANA",
    "GRSE","MRPL","MSUMI","GILLETTE","DEVYANI",
    "SUNDRMHOLD","CHOLAHLDNG","ASTERDM","GODIGIT","SAPPHIRE",
    "SWANENERGY","ROUTE","LATENTVIEW","ISEC","DATAPATTNS",
    "FINEORG","WHIRLPOOL","MAHSEAMLESS","RENUKA","AFFLE",
    "TTML","TRITURBINE","EDELWEISS","NESCO","STARCEMENT",
    "CENTURYTEX","TEAMLEASE","MAPMYINDIA","MAHLIFE","NETWORK18",
    "PPLPHARMA","WESTLIFE","OLECTRA","JTLIND","BIKAJI",
    "TARSONS","KRSNAA","GRAVITA","TANLA","ECLERX",
    "CRAFTSMAN","SHYAMMETL","VGUARD","AMIORG","JYOTHYLAB",
    "STARHEALTH","MOTILALOFS","TTKPRESTIG","UTIAMC","NUVAMA",
    "PNBHOUSING","INDIACEM","ZFCVINDIA","ELGIEQUIP","SONATSOFTW",
    "GOCOLORS","GODFRYPHLP","CHALET","KIOCL","CERA",
    "ISGEC","TEGA","SUVENPHAR","SPARC","SBFC",
    "PRINCEPIPE","HBLPOWER","TV18BRDCST","RALLIS","FLUOROCHEM",
    "REDINGTON","BLUEDART","POWERINDIA","CARBORUNIV","CENTURYPLY",
    "PRSMJOHNSN","FINPIPE","MEDPLUS","ANURAS","SENCO",
    "GATEWAY","RAINBOW","AARTIIND","TCIEXP","MAHINDCIE",
    "MASTEK","ASAHIINDIA","LAXMIMACH","VSTIND","BALAMINES",
    "SUPRAJIT","KPIL","QUESS","AETHER","JKLAKSHMI",
    "NIITMTS","RAYMOND","WELCORP","KIMS","RATEGAIN",
    "SAREGAMA","MAHLOG","GLENMARK","IRCON","NATCOPHARM",
    "ZYDUSWELL","JSWINFRA","SHRIRAMCIT","TRIDENT","INOXWIND",
    "NCC","DCMSHRIRAM","RKFORGE","CHEMPLASTS","GMMPFAUDLR",
    "BLUEJET","BBTC","JAMNAAUTO","AARTIDRUGS","TDPOWERSYS",
    "GHCL","SPLPETRO","KFINTECH","ABSLAMC","SANOFI",
    "HINDWAREAP","SAKSOFT","RITES","PEL","LXCHEM",
    "NEWGEN","ACE","MASFIN","ENGINERSIN","SAGCEM",
    "IFBIND","DOMS","AVALON","SBCL","ADFFOODS",
    "VAIBHAVGBL","SAFARI","INDIGOPNTS","SHOPERSTOP","GREENPANEL",
    "PURMO","UCOBANK","CUB","MAZDOCK","BANARISUG",
    "TATAINVEST","RAMCOCEM","JKPAPER","SFL","CCL",
    "LUXIND","GARFIBRES","MIDHANI","DATAMATICS","VIPIND",
    "TVSSRICHAK","CASTROLIND","GULFOILLUB","ALKYLAMINE","ELECON",
    "PRAJIND","BAJAJELEC","PNCINFRA","SHILPAMED","WOCKPHARMA",
    "GESHIP","ORIENTELEC","GREENLAM","JKTYRE","DALBHARAT",
    "SKFINDIA","GMDCLTD","CAPACITE","SYMPHONY","SUNTECK",
    "SYRMA","INTELLECT"
).distinct()

// ═══════════════════════════════════════════════════════
// AUTOMATIC ROBUST DISCOVERY SCHEDULER
//
// Wakes the service every 2 hours during market hours (09:30, 11:30, 13:30,
// 15:00 IST, weekdays) to run a ROBUST NIFTY 500 scan. Uses inexact
// AlarmManager so Doze mode batches it with other wakeups for battery.
//
// Schedules one alarm at a time (the next slot) and re-arms after each fire,
// so the chain self-heals across reboots / process death without needing
// setRepeating's drift problems.
// ═══════════════════════════════════════════════════════
object DiscoveryAutoScheduler {
    private const val TAG = "DiscoveryAutoSched"
    private const val ACTION_FIRE = "com.signalscope.AUTO_DISCOVERY_FIRE"
    private const val REQUEST_CODE = 0x5C0E

    // 09:30, 11:30, 13:30, 15:00 IST — covers market open + every ~2h
    private val SLOT_MINUTES = listOf(9 * 60 + 30, 11 * 60 + 30, 13 * 60 + 30, 15 * 60)

    fun ensureScheduled(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = android.app.PendingIntent.getBroadcast(
            ctx, REQUEST_CODE,
            Intent(ctx, AutoDiscoveryReceiver::class.java).setAction(ACTION_FIRE),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val nextFireWallMs = nextSlotWallTimeMs()
        val delay = (nextFireWallMs - System.currentTimeMillis()).coerceAtLeast(60_000L)
        val triggerAt = SystemClock.elapsedRealtime() + delay
        // ELAPSED_REALTIME so phone-clock changes don't surprise us.
        // setAndAllowWhileIdle so Doze doesn't suppress it (still inexact,
        // OS may delay by a few minutes — fine for a scheduled discovery).
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        Log.i(TAG, "Next auto-discovery in ${delay / 60000} min")
    }

    /** Wall-clock millis of the next slot, skipping weekends. */
    private fun nextSlotWallTimeMs(): Long {
        val tz = TimeZone.getTimeZone("Asia/Kolkata")
        val cal = Calendar.getInstance(tz)
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val today = cal.clone() as Calendar
        today.set(Calendar.SECOND, 0); today.set(Calendar.MILLISECOND, 0)

        // Try today's remaining slots first
        for (slot in SLOT_MINUTES) {
            if (slot > nowMin) {
                today.set(Calendar.HOUR_OF_DAY, slot / 60)
                today.set(Calendar.MINUTE, slot % 60)
                if (isWeekday(today)) return today.timeInMillis
            }
        }
        // Otherwise next valid weekday's first slot (09:30)
        val next = today.clone() as Calendar
        do {
            next.add(Calendar.DAY_OF_MONTH, 1)
        } while (!isWeekday(next))
        next.set(Calendar.HOUR_OF_DAY, SLOT_MINUTES[0] / 60)
        next.set(Calendar.MINUTE, SLOT_MINUTES[0] % 60)
        return next.timeInMillis
    }

    private fun isWeekday(cal: Calendar): Boolean {
        val d = cal.get(Calendar.DAY_OF_WEEK)
        return d != Calendar.SATURDAY && d != Calendar.SUNDAY
    }
}

/**
 * Broadcast receiver that AlarmManager pokes at each scheduled slot.
 * Kicks off a ROBUST NIFTY 500 discovery and re-arms the chain.
 */
class AutoDiscoveryReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.i("AutoDiscoveryRecv", "Auto-discovery alarm fired — starting ROBUST NIFTY 500 scan")
        // Re-arm the next slot first so a crash in the scan doesn't break the chain.
        DiscoveryAutoScheduler.ensureScheduled(context)
        val svc = ScanService.createIntent(context, ScanService.ACTION_DISCOVERY_NIFTY500_AUTO)
        // Foreground service start is allowed from a broadcast triggered by
        // setAndAllowWhileIdle (alarm-driven exemption on Android 12+).
        try {
            context.startForegroundService(svc)
        } catch (e: Exception) {
            Log.e("AutoDiscoveryRecv", "Failed to start ScanService for auto-discovery", e)
        }
    }
}

val NASDAQ_100_SYMBOLS = listOf(
    "AAPL","ABNB","ADBE","ADI","ADP","ADSK","AEP","AMAT","AMD","AMGN",
    "AMZN","ANSS","APP","ARM","ASML","AVGO","AZN","BIIB","BKNG","BKR",
    "CCEP","CDNS","CDW","CEG","CHTR","CMCSA","COIN","COST","CPRT","CRWD",
    "CSCO","CSGP","CTAS","CTSH","DASH","DDOG","DLTR","DXCM","EA","EXC",
    "FANG","FAST","FTNT","GEHC","GFS","GILD","GOOG","GOOGL","HON","IDXX",
    "ILMN","INTC","INTU","ISRG","KDP","KHC","KLAC","LIN","LRCX","LULU",
    "MAR","MCHP","MDB","MDLZ","MELI","META","MNST","MRVL","MSFT","MU",
    "NFLX","NVDA","NXPI","ODFL","ON","ORLY","PANW","PAYX","PCAR","PDD",
    "PEP","PLTR","PYPL","QCOM","REGN","ROP","ROST","SBUX","SMCI","SNPS",
    "TEAM","TMUS","TSLA","TTD","TTWO","TXN","VRSK","VRTX","WBD","WDAY",
    "XEL","ZS"
)

// ── DAX 40 + selected MDAX components (German equity universe) ──
// Yahoo uses the `.DE` suffix for Deutsche Börse Xetra listings. Includes
// the 40 DAX blue chips (Adidas, SAP, Allianz, …) plus a handful of liquid
// MDAX names that round out the German large-cap landscape — same ~50-stock
// shape as Nifty 50 / NASDAQ 100, fast enough to scan in one pass.
val DAX_SYMBOLS = listOf(
    // DAX 40
    "ADS.DE","AIR.DE","ALV.DE","BAS.DE","BAYN.DE","BEI.DE","BMW.DE","BNR.DE",
    "CBK.DE","CON.DE","1COV.DE","DBK.DE","DB1.DE","DHL.DE","DTE.DE","DTG.DE",
    "ENR.DE","EOAN.DE","FRE.DE","HEI.DE","HEN3.DE","HFG.DE","HNR1.DE","IFX.DE",
    "MBG.DE","MRK.DE","MTX.DE","MUV2.DE","P911.DE","PAH3.DE","QIA.DE","RHM.DE",
    "RWE.DE","SAP.DE","SHL.DE","SIE.DE","SY1.DE","VNA.DE","VOW3.DE","ZAL.DE",
    // MDAX additions
    "AIXA.DE","AT1.DE","NDA.DE","AG1.DE","BC8.DE","GBF.DE","EVD.DE","DHER.DE",
    "DEZ.DE","DWS.DE","EVK.DE","FTK.DE","FRA.DE","FNTN.DE","FPE3.DE","HAG.DE",
    "HOT.DE","BOSS.DE","IOS.DE","JEN.DE","JUN3.DE","SDF.DE","KGX.DE","KBX.DE",
    "KRN.DE","LXS.DE","LEG.DE","LHA.DE","NEM.DE","NDX1.DE","PUM.DE","RAA.DE",
    "RDC.DE","R3NK.DE","RRTL.DE","SZG.DE","SRT3.DE","SHA.DE","SAX.DE","TEG.DE",
    "TLX.DE","TKA.DE","8TRA.DE","TUI1.DE","UTDI.DE","WCH.DE",
    // TecDAX / additional liquid German tech names
    "1U1.DE","AOF.DE","COK.DE","COP.DE","DRW3.DE","ELG.DE","EKT.DE","EVT.DE",
    "KTN.DE","NA9.DE","PNE3.DE","F3C.DE","WAF.DE","S92.DE","TMV.DE","VBK.DE",
    "EUZ.DE"
).distinct()

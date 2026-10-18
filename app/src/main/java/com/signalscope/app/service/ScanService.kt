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
import com.signalscope.app.trading.LivePortfolioBars
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
 *    - Shows Pullback/Momentum entry points, sell scores, all indicators
 *    - NOT gated by market hours — user triggers manually
 *    - No sell notifications (not held stocks)
 *    - Results stored in lastDiscoveryResult for UI display
 */
class ScanService : Service() {

    companion object {
        private const val TAG = "ScanService"
        const val CHANNEL_SCAN   = "signalscope_scan"
        const val CHANNEL_ALERTS = "signalscope_alerts"
        /** Silent by design — see [recordAutoLoginOutcome]. */
        const val CHANNEL_ZERODHA_LOGIN = "zerodha_daily_login"
        const val NOTIFICATION_SCAN_ID = 1
        const val NOTIFICATION_ZERODHA_LOGIN_ID = 0x2ED0

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
        const val ACTION_T212_DAILY_TRAIL         = "com.signalscope.T212_DAILY_TRAIL"
        const val ACTION_STRATEGY_LAB_TICK        = "com.signalscope.STRATEGY_LAB_TICK"
        const val ACTION_ZERODHA_AUTO_LOGIN       = "com.signalscope.ZERODHA_AUTO_LOGIN"
        /** The alarm that wakes portfolio monitoring at the Indian open. */
        const val ACTION_MONITOR_WAKE             = "com.signalscope.MONITOR_WAKE"
        /** The refresh button on the positions widget. */
        const val ACTION_WIDGET_REFRESH           = "com.signalscope.WIDGET_REFRESH"

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

    // ── WHO IS USING THE SERVICE RIGHT NOW ──────────────────────────────────
    // Every short background job — a Discovery scan, a one-off portfolio scan,
    // the algo check, the Trading 212 trail, the Zerodha morning login — counts
    // itself in here and out again. When the last one leaves and monitoring is
    // not switched on, the Wi-Fi and wake holds are handed back and the service
    // stops.
    //
    // It replaces a per-job guess of "was anything else running when I
    // started?", which a finished scan answered wrongly for ever: a completed
    // Discovery job still counted as running, and a scan switched monitoring on
    // outright. Between them, one scan left the phone held awake with Wi-Fi at
    // full power indefinitely.
    private val activeJobs = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var lastStartId = 0
    private var wakeDeadline = 0L
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
        AlertType.SMOOTH_BUY_TURN     -> 24 * 60 * 60 * 1000L
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
        // Redraw the positions widget from the last summary as soon as the app is
        // running again (and take down the old pinned card, if one is left over).
        com.signalscope.app.trading.PositionRadar.publish(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Recorded AFTER the job below is dispatched and counted, so a job that
        // finishes at this exact moment can never stop the service out from under
        // the request that just arrived. See [endJob].
        try { dispatch(intent, startId) } finally { lastStartId = startId }
        DiscoveryAutoScheduler.ensureScheduled(this)
        T212DailyTrailScheduler.ensureScheduled(this)
        StrategyLabScheduler.ensureScheduled(this)
        ZerodhaAutoLoginScheduler.ensureScheduled(this)
        return START_STICKY
    }

    private fun dispatch(intent: Intent?, startId: Int) {
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
            ACTION_T212_DAILY_TRAIL        -> triggerT212DailyTrail("alarm")
            ACTION_STRATEGY_LAB_TICK       -> triggerStrategyLabPaperTick("alarm")
            com.signalscope.app.trading.RvGate.ACTION_TICK -> triggerRvGateTick()
            ACTION_WIDGET_REFRESH          -> triggerWidgetRefresh()
            ACTION_ZERODHA_AUTO_LOGIN      -> triggerZerodhaAutoLogin("alarm")
            // THE MARKET IS OPENING. Monitoring let the phone sleep at the close;
            // this puts it back to work. Started as a foreground service by the
            // alarm, so it must show its notification even if monitoring has since
            // been switched off — and then it simply stops.
            ACTION_MONITOR_WAKE            -> if (config.serviceRunning) startPortfolioMonitoring() else {
                startForeground(NOTIFICATION_SCAN_ID, buildScanNotification("Monitoring is off"))
                if (activeJobs.get() == 0) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
            }
            // A RESTART WITH NO REASON GIVEN. Android does this after killing the
            // app mid-job, and this used to start full monitoring — a 24-hour wake
            // hold and full-power Wi-Fi — whether or not you had ever switched it
            // on. Now it resumes monitoring only if it WAS on; otherwise it re-arms
            // the schedules (below) and goes back to sleep.
            else                           -> resumeAfterRestart(startId)
        }
    }

    private fun resumeAfterRestart(startId: Int) {
        if (config.serviceRunning) { startPortfolioMonitoring(); return }
        Log.i(TAG, "Restarted by the system with nothing switched on — schedules re-armed, going idle")
        if (activeJobs.get() == 0) stopSelf(startId)
    }

    /** A short job is starting: hold the phone awake and Wi-Fi up for at most
     *  [durationMs], show the running notification, and count it in. */
    private fun beginJob(durationMs: Long, text: String) {
        acquireScanLocks(durationMs)
        startForeground(NOTIFICATION_SCAN_ID, buildScanNotification(text))
        activeJobs.incrementAndGet()
    }

    /**
     * A short job has finished. The last one out hands back the holds and stops
     * the service — unless monitoring is switched on, which keeps both by design.
     *
     * Stops with the NEWEST start request's id, so if another request arrived in
     * the meantime Android ignores the stop rather than killing what it started.
     */
    private fun endJob() {
        val left = activeJobs.decrementAndGet()
        if (left < 0) activeJobs.set(0)
        if (left > 0) return
        if (config.serviceRunning || portfolioJob?.isActive == true) {
            // Monitoring keeps the service, but holds the phone awake only while
            // the Indian market is open — see [startPortfolioMonitoring].
            if (!isMarketOpen()) releaseScanLocks()
            return
        }
        releaseScanLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
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
        // NO 24-HOUR HOLD ANY MORE. The phone used to be kept awake, with Wi-Fi at
        // full power, for a day at a time — nights and weekends included, when
        // there is nothing to monitor. It is taken only while the Indian market
        // is open (still before startForeground, for the reason above), extended
        // by the loop every pass, and handed back at the close.
        if (isMarketOpen())
            acquireScanLocks(durationMs = config.portfolioScanIntervalMin * 60_000L + 10 * 60_000L)
        startForeground(NOTIFICATION_SCAN_ID, buildScanNotification("Starting..."))

        portfolioJob?.cancel()
        portfolioJob = serviceScope.launch {
            while (isActive) {
                try {
                    if (isMarketOpen()) {
                        // Held for one interval plus a margin, and extended every
                        // pass — so it lasts exactly as long as the session does.
                        acquireScanLocks(durationMs = config.portfolioScanIntervalMin * 60_000L + 10 * 60_000L)
                        runFullPortfolioScan()
                    } else {
                        // Outside market hours: portfolio monitoring pauses
                        // but service stays alive for on-demand discovery scans.
                        //
                        // MARKET SHUT: let the phone sleep. Unless a short job is
                        // running, which holds for itself and lets go when done.
                        if (activeJobs.get() == 0) releaseScanLocks()
                        // Asleep, this loop's timer barely advances, so an alarm
                        // restarts it at the open rather than trusting it to notice.
                        MonitorWakeScheduler.schedule(this@ScanService,
                            com.signalscope.app.trading.IndianSession.nextOpenMs(System.currentTimeMillis()) + 5_000L)
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
        MonitorWakeScheduler.cancel(this)
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
        // EXTENDED, NEVER SHORTENED. This used to skip entirely when a hold
        // already existed, so a two-hour scan starting inside a 12-minute algo
        // check inherited the 12 minutes and lost its hold mid-scan. Re-acquiring
        // unconditionally would do the opposite and cut monitoring's 24 hours to
        // 12 minutes. So: take the later of the two deadlines.
        val deadline = System.currentTimeMillis() + durationMs
        if (wakeLock?.isHeld != true || deadline > wakeDeadline) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SignalScope::ScanWakeLock")
                .apply { setReferenceCounted(false) }
            wl.acquire(durationMs)
            wakeLock = wl
            wakeDeadline = deadline
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
        wakeDeadline = 0L
    }

    private fun triggerImmediatePortfolioScan() {
        // A single scan, not monitoring: it used to switch monitoring on to get
        // the service running, and monitoring then never switched off.
        beginJob(15 * 60 * 1000L, "Scanning portfolio...")
        serviceScope.launch {
            try {
                // Immediate portfolio scan bypasses market hours check
                runFullPortfolioScan()
            } catch (e: Exception) {
                Log.e(TAG, "Immediate scan error", e)
                updateScanNotification("Scan failed: ${e.message}")
            } finally { endJob() }
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
    private fun triggerT212DailyTrail(reason: String) {
        beginJob(30 * 60 * 1000L, "T212 trailing maintenance...")
        serviceScope.launch {
            try {
                runT212TrailingMaintenance(reason, respectRecentRunGate = true)
            } catch (e: Exception) {
                Log.e(TAG, "T212 trailing maintenance failed", e)
                updateScanNotification("T212 trail failed: ${e.message}")
            } finally { endJob() }
        }
    }

    private suspend fun runT212TrailingMaintenance(
        reason: String,
        respectRecentRunGate: Boolean
    ) {
        if (!config.hasT212Credentials) {
            Log.i(TAG, "T212 trail skipped ($reason): not configured")
            updateScanNotification("T212 not configured")
            return
        }

        val now = System.currentTimeMillis()
        val last = config.lastT212DailyTickEpoch
        if (respectRecentRunGate && last > 0L && now - last < 90 * 60 * 1000L) {
            Log.i(TAG, "T212 trail skipped ($reason): ran ${(now - last) / 60000} min ago")
            return
        }

        updateScanNotification("T212: trailing SL/targets...")
        val summary = withContext(Dispatchers.IO) {
            com.signalscope.app.trading.Trading212TrailingManager.run(
                applicationContext,
                config,
                autoCreateOco = false
            )
        }
        config.lastT212DailyTickEpoch = now
        Log.i(TAG, "T212 trail ($reason): SL=${summary.slsRaised} target=${summary.targetsRaised} " +
                "reconciled=${summary.reconciledFilled}/${summary.reconciledClosed} skipped=${summary.skipped} errors=${summary.errors.size}")
        summary.log.forEach { Log.d(TAG, "  T212: $it") }
        summary.errors.forEach { Log.w(TAG, "  T212 error: $it") }
        updateScanNotification(
            "T212 trail: SL ${summary.slsRaised}, target ${summary.targetsRaised}, errors ${summary.errors.size}"
        )
        broadcastUpdate()
    }

    // ═══════════════════════════════════════════════════════
    // ZERODHA UNATTENDED DAILY LOGIN
    // ═══════════════════════════════════════════════════════

    private fun triggerZerodhaAutoLogin(reason: String) {
        // Short lock: three round-trips plus at most one TOTP window wait.
        beginJob(5 * 60 * 1000L, "Zerodha daily login...")
        serviceScope.launch {
            try {
                runZerodhaAutoLogin(reason)
            } catch (e: Exception) {
                Log.e(TAG, "Zerodha auto-login failed", e)
                recordAutoLoginOutcome("Crashed: ${e.message}", notify = true)
            } finally { endJob() }
        }
    }

    private suspend fun runZerodhaAutoLogin(reason: String) {
        if (!config.canAutoLoginZerodha) {
            Log.i(TAG, "Zerodha auto-login skipped ($reason): not enabled or incomplete")
            return
        }
        val today = todayTradingDate()
        if (config.zerodhaAutoLoginBlockedOn == today) {
            // Kite already told us the credentials are wrong today. Trying again
            // is how accounts get locked, which is worse than a missed session.
            Log.w(TAG, "Zerodha auto-login blocked for $today — credentials were rejected")
            return
        }
        // Slots are 30 min apart; anything closer than 20 is a duplicate poke
        // (service restart, boot catch-up landing on top of a scheduled slot).
        val sinceLast = System.currentTimeMillis() - config.zerodhaAutoLoginStatusAt
        if (config.zerodhaAutoLoginStatusAt > 0L && sinceLast < 20 * 60 * 1000L) {
            Log.i(TAG, "Zerodha auto-login skipped ($reason): tried ${sinceLast / 60000} min ago")
            return
        }

        // A token that still works needs no login — the later slots exist only as
        // retries for the earlier ones, and this is what makes them free.
        val existing = withContext(Dispatchers.IO) { zerodhaClient.verifyToken() }
        if (existing is ZerodhaClient.ZerodhaAuthResult.Success) {
            Log.i(TAG, "Zerodha auto-login ($reason): session already live")
            return
        }

        updateScanNotification("Zerodha: signing in...")
        val result = withContext(Dispatchers.IO) {
            com.signalscope.app.network.ZerodhaAutoLogin.run(config)
        }
        when (result) {
            is com.signalscope.app.network.ZerodhaAutoLogin.Result.Success -> {
                recordAutoLoginOutcome("Connected as ${result.userName}", notify = false)
                Log.i(TAG, "Zerodha auto-login ($reason) succeeded")
                broadcastUpdate()
            }
            is com.signalscope.app.network.ZerodhaAutoLogin.Result.Rejected -> {
                config.zerodhaAutoLoginBlockedOn = today
                recordAutoLoginOutcome("Rejected: ${result.message}", notify = true)
                Log.w(TAG, "Zerodha auto-login ($reason) rejected: ${result.message}")
            }
            is com.signalscope.app.network.ZerodhaAutoLogin.Result.Retryable -> {
                recordAutoLoginOutcome("Failed: ${result.message}", notify = true)
                Log.w(TAG, "Zerodha auto-login ($reason) failed: ${result.message}")
            }
        }
    }

    /**
     * Failures get a notification; successes do not.
     *
     * The whole point of this feature is that nothing wakes anybody at 4am, and
     * these attempts all land in the small hours European time. So the channel is
     * deliberately silent: the message is sitting in the shade at breakfast, hours
     * before the Indian close, which is early enough to paste a token by hand.
     */
    private fun recordAutoLoginOutcome(message: String, notify: Boolean) {
        config.zerodhaAutoLoginStatus = message
        config.zerodhaAutoLoginStatusAt = System.currentTimeMillis()
        if (!notify) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ZERODHA_LOGIN, "Zerodha daily login", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Automatic Kite session renewal" })
            val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
            val pi = PendingIntent.getActivity(
                this, 0x2ED0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            NotificationLog.post(this, nm, NOTIFICATION_ZERODHA_LOGIN_ID, NotificationCompat.Builder(this, CHANNEL_ZERODHA_LOGIN)
                .setContentTitle("Zerodha auto-login didn't go through")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "$message\n\nIndian algos stay logged out until you connect by hand " +
                    "in Settings → Zerodha."))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build())
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't post auto-login notification", e)
        }
    }

    /**
     * THE RV GATE'S MINUTE CHECK — only while an automatic order is waiting on it.
     * Looks at the waiting buys and sales again and starts any whose moment has
     * come (or whose two hours are up), then arms the next minute if anything is
     * still waiting.
     */
    /**
     * THE WIDGET'S ↻ — re-read every position now instead of at the end of the
     * next pass. Prices are asked for again unless under a minute old (the pass
     * accepts twenty), so a tap shows the market as it is. Reports only: nothing
     * is bought, sold or moved.
     */
    private fun triggerWidgetRefresh() {
        beginJob(3 * 60 * 1000L, "Refreshing positions…")
        try { com.signalscope.app.trading.PositionWidget.markRefreshing(applicationContext) } catch (_: Exception) {}
        serviceScope.launch {
            try {
                com.signalscope.app.trading.PositionRadar.refresh(applicationContext, priceMaxAgeMs = 60_000L)
            } catch (e: Exception) {
                Log.w(TAG, "widget refresh failed: ${e.message}")
            } finally {
                try {
                    com.signalscope.app.trading.PositionWidget.clearRefreshing()
                    com.signalscope.app.trading.PositionRadar.publish(applicationContext)
                } catch (_: Exception) {}
                endJob()
            }
        }
    }

    private fun triggerRvGateTick() {
        beginJob(10 * 60 * 1000L, "Checking waiting orders…")
        serviceScope.launch {
            try {
                com.signalscope.app.trading.T212LiveMirror.retryPendingEntries(applicationContext)
                com.signalscope.app.trading.T212LiveMirror.retryPendingExits(applicationContext)
                // Your own patient orders that you asked to wait on the gate.
                com.signalscope.app.trading.RvManualQueue.process(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "RV gate check failed", e)
            } finally {
                try { com.signalscope.app.trading.RvGate.ensureTick(applicationContext) } catch (_: Exception) {}
                endJob()
            }
        }
    }

    private fun triggerStrategyLabPaperTick(reason: String) {
        beginJob(12 * 60 * 1000L, "Strategy Lab paper tick...")
        serviceScope.launch {
            try {
                runStrategyLabPaperTick(reason)
            } catch (e: Exception) {
                Log.e(TAG, "Strategy Lab paper tick failed", e)
                updateScanNotification("Strategy Lab failed: ${e.message}")
            } finally { endJob() }
        }
    }

    private suspend fun runStrategyLabPaperTick(reason: String) {
        // ACTIVITY CHECK OVER EVERY NAMESPACE, INCLUDING BUCKETS. This used to
        // read only the three BARE legacy labs — but after the bucket migration
        // those are empty shells, so the 15-min alarm fired, concluded "nothing
        // enabled", and returned. Every background pass was a silent no-op and
        // algos only advanced when the app was foregrounded or Run now pressed:
        // positions sat unvalued for hours with the alarm chain working perfectly.
        // A gate-armed lab counts as active even with 0 enrolled — under 3b the
        // gate owns enrollment, and all seats sitting in cash (or a fresh arm)
        // is a legitimate state that still needs the daily evaluation to run
        // (same deadlock shape as the guard inside StrategyLabRunner.runAll).
        // The manual-entry namespaces are added EXPLICITLY because they are
        // deliberately not BucketStore entries (see ManualWatchStore's header: a
        // bucket can be armed for live money, and an armed bucket mirrors its own
        // entries to the broker — behind a position the user already bought by
        // hand that would place a second real order). Not being buckets, they are
        // invisible to allNamespaces, so without this line the engine would never
        // tick them and nothing would ever manage a manual entry's exit.
        val manualNs = (com.signalscope.app.data.BucketStore.BUCKETABLE_SPACES +
            com.signalscope.app.trading.ManualEntryWatcher.WATCHED_SPACES).distinct()
            .map { com.signalscope.app.data.ManualWatchStore.nsFor(it) }
        val allNs = (listOf("us", "de", "binance") +
            com.signalscope.app.data.BucketStore.allNamespaces(applicationContext) +
            manualNs).distinct()
        val anyActive = allNs.any { n ->
            val s = StrategyLabStore.load(applicationContext, n)
            val gateArmed = try {
                com.signalscope.app.data.MacdGateStore.load(applicationContext, n).enabled
            } catch (e: Exception) { false }
            s.enabled && (s.enrolled.isNotEmpty() || gateArmed)
        } || try { com.signalscope.app.data.FloorAlgoStore.anyRunning(applicationContext) } catch (e: Exception) { false }
        if (!anyActive) {
            Log.i(TAG, "Strategy Lab tick skipped ($reason): no enabled/enrolled paper labs")
            updateScanNotification("Strategy Lab: nothing enabled")
            return
        }

        // Said plainly when the stock markets are shut: the pass still runs (crypto
        // never closes, and prices, stops and the widget are kept current), but
        // "paper tick" at 2 a.m. on a Saturday read like a scan of shut markets.
        updateScanNotification(
            if (com.signalscope.app.trading.MarketHours.nextOpeningText(System.currentTimeMillis()) != null)
                "Checking · stock markets shut"
            else "Strategy Lab: paper tick...")
        // Run the SAME list the activity check saw. runAll self-gates per
        // namespace, so inactive ones no-op — no double-run, no divergence
        // between "what we checked" and "what we run".
        val nsList = allNs
        val latch = java.util.concurrent.CountDownLatch(nsList.size)
        for (n in nsList)
            com.signalscope.app.trading.StrategyLabRunner.runAll(applicationContext, 0L, n) { latch.countDown() }
        val completed = withContext(Dispatchers.IO) {
            latch.await(10, java.util.concurrent.TimeUnit.MINUTES)
        }
        // Paper trades found late on a shut market were held back; any whose market
        // is now trading go out here, on the first pass after the open.
        try {
            val n = com.signalscope.app.trading.LatePaperAlerts.flush(applicationContext)
            if (n > 0) Log.i(TAG, "Sent $n paper alert(s) held for the open")
        } catch (e: Exception) { Log.w(TAG, "held paper alerts failed: ${e.message}") }
        // Log the namespaces actually swept, not three fixed booleans — the old
        // us/de/binance flags were exactly what made bucket algos invisible here.
        // Every namespace has now run, so any entries they queued are complete as
        // a set — this is the moment siblings on the same name can be merged into
        // one order. Placing here rather than waiting on the coalescing timer
        // keeps a tick's entries close to the bar that produced them.
        try { com.signalscope.app.trading.T212LiveMirror.flushEntryIntents(applicationContext) }
        catch (e: Exception) { Log.w(TAG, "entry flush failed: ${e.message}") }
        // Entries wanted but not filled — a closed market, or a ladder that conceded
        // its whole range. Re-attempted only while paper still holds the position;
        // no-op unless the user opted in. After the flush, so a fresh intent gets
        // its first attempt before any backlog competes for the same ladder thread.
        // BEFORE the retry sweep, so a row the ladder silently filled is reopened
        // rather than retried — which would buy the same name a second time.
        try { com.signalscope.app.trading.T212LiveMirror.adoptStrandedFills(applicationContext) }
        catch (e: Exception) { Log.w(TAG, "stranded-fill adoption failed", e) }
        try { com.signalscope.app.trading.T212LiveMirror.retryPendingEntries(applicationContext) }
        catch (e: Exception) { Log.w(TAG, "entry retry failed: ${e.message}") }
        // Indian exits that could not go through — retried, or closed if the
        // protective stop had already sold the shares. See the sweep itself.
        try { com.signalscope.app.trading.ZerodhaLiveMirror.retryHeldExits(applicationContext) }
        catch (e: Exception) { Log.w(TAG, "held-exit retry failed: ${e.message}") }
        // UNSOLD EXITS come first in importance: a position the book has closed but
        // the broker still holds is real money exposed with nothing managing it.
        // Unconditional — an exit is never withheld for a preference.
        try { com.signalscope.app.trading.RvGate.ensureTick(applicationContext) } catch (_: Exception) {}
        // Your buys that stopped at their price limit and were not answered by the close.
        try { com.signalscope.app.trading.RvManualQueue.expireAsks(applicationContext) } catch (_: Exception) {}
        // The RV gate's record: how each released algo order ended (fill or not).
        try { com.signalscope.app.trading.RvJournal.settle(applicationContext) } catch (_: Exception) {}
        try { com.signalscope.app.trading.T212LiveMirror.retryPendingExits(applicationContext) }
        catch (e: Exception) { Log.w(TAG, "exit retry failed: ${e.message}") }
        // ALGO POSITIONS SOLD IN THE BROKER'S OWN APP — gone from the account on two
        // reads half an hour apart — are closed and their seats handed on.
        try {
            val hs = com.signalscope.app.trading.T212HandSales.sweep(applicationContext)
            if (hs.isNotBlank()) Log.i(TAG, "Hand sales: $hs")
        } catch (e: Exception) { Log.w(TAG, "hand-sale sweep failed: ${e.message}") }
        // …and the same for India's algos, priced from the day's Zerodha fills.
        try {
            val hz = com.signalscope.app.trading.ZerodhaHandSales.sweep(applicationContext)
            if (hz.isNotBlank()) Log.i(TAG, "Hand sales (India): $hz")
        } catch (e: Exception) { Log.w(TAG, "Indian hand-sale sweep failed: ${e.message}") }
        // TODAY'S INDIAN FILLS, CAPTURED WHILE THE DAY STILL EXISTS. Kite's trade
        // list is emptied overnight and there is no endpoint that gives a past day
        // back, so a pass that does not ask loses that day's trades permanently.
        // Self-throttling and deduped, so calling it on every tick costs one small
        // request every ten minutes and nothing at all when the session is down.
        try { com.signalscope.app.data.ZerodhaTradeStore.capture(applicationContext) }
        catch (e: Exception) { Log.w(TAG, "zerodha trade capture failed: ${e.message}") }
        // MANUAL ENTRY WATCHER — after the algos, never before. It fetches a series
        // per candidate, and running it first would spend the tick's rate-limit
        // budget on names nobody holds while positions that DO hold money waited to
        // be priced and exited. Read-only and self-skipping (no config attached, or
        // no gate candidates ⇒ immediate no-op), so on a space that has not set it
        // up this costs one store read.
        // What the watchers actually did, so the completion notice can SAY it.
        val watchBits = ArrayList<String>()
        for (sp in com.signalscope.app.trading.ManualEntryWatcher.WATCHED_SPACES) {
            try {
                val o = withContext(Dispatchers.IO) {
                    com.signalscope.app.trading.ManualEntryWatcher.runOnce(applicationContext, sp)
                }
                if (o.checked > 0 || o.alerted.isNotEmpty()) {
                    Log.i(TAG, "Manual watcher [$sp]: ${o.note}, alerted ${o.alerted.joinToString(",")}")
                    val label = when (sp) { "de" -> "DE"; "in" -> "IN"; "us" -> "US"; else -> sp }
                    watchBits.add("$label ${o.checked}" +
                        (if (o.alerted.isEmpty()) "" else " → ${o.alerted.joinToString("/")}"))
                }
                // WHAT YOU ALREADY OWN, checked on the same pass. The daily trailing
                // managers protect these; they just cannot speak between runs. This
                // only warns — it places, cancels and modifies nothing.
                val hp = withContext(Dispatchers.IO) {
                    com.signalscope.app.trading.HeldPositionWatch.runOnce(applicationContext, sp)
                }
                if (hp.alerted.isNotEmpty()) {
                    Log.w(TAG, "Held-position break [$sp]: ${hp.alerted.joinToString(",")}")
                    val label = when (sp) { "de" -> "DE"; "in" -> "IN"; "us" -> "US"; else -> sp }
                    watchBits.add("$label held ⚠ ${hp.alerted.joinToString("/")}")
                }
            } catch (e: Exception) { Log.w(TAG, "manual watcher [$sp] failed: ${e.message}") }
        }
        // 1-WEEK-HIGH TARGETS AT TRADING 212 — after every seller above has had its
        // turn, so a target is never put back on a position this same pass sells.
        try {
            val tg = withContext(Dispatchers.IO) {
                com.signalscope.app.trading.T212WeekTargets.keep(applicationContext)
            }
            if (tg.isNotBlank()) { Log.i(TAG, "Week targets: $tg"); watchBits.add(tg) }
        } catch (e: Exception) { Log.w(TAG, "week targets failed: ${e.message}") }
        // SAFETY STOPS UNDER SALES THAT ARE WAITING — after the exit retry and the
        // targets, so a sale made this pass is tidied rather than protected, and a
        // sale that found no buyer gets its stop back straight away.
        try {
            val gd = withContext(Dispatchers.IO) {
                com.signalscope.app.trading.T212ExitGuards.keep(applicationContext)
            }
            if (gd.isNotBlank()) { Log.i(TAG, "Safety stops: $gd"); watchBits.add(gd) }
        } catch (e: Exception) { Log.w(TAG, "safety stops failed: ${e.message}") }
        // BUYS WAITING AT TRADING 212 — yours as well as the app's. Each holds cash
        // back from the account, so the waiting-orders list and the algos' cash
        // check both need to know they are there. Read only.
        try {
            val wb = withContext(Dispatchers.IO) {
                com.signalscope.app.trading.T212WaitingOrders.refresh(applicationContext)
            }
            if (wb.isNotBlank()) Log.i(TAG, "Waiting buys: $wb")
        } catch (e: Exception) { Log.w(TAG, "waiting buys read failed: ${e.message}") }
        // FLOOR-ORDER ALGOS — paper only, last in the pass for the same reason as
        // the watcher: nothing here holds real money, so it never goes ahead of
        // anything that does.
        val floorNote = try {
            withContext(Dispatchers.IO) { com.signalscope.app.trading.FloorAlgoEngine.runAll(applicationContext) }
        } catch (e: Exception) { Log.w(TAG, "floor algos failed: ${e.message}"); "" }
        // WHAT YOU HOLD, FOR THE PINNED NOTIFICATION — last, so it reads the
        // positions, targets and stops exactly as this pass left them. Reports
        // only; it places and cancels nothing.
        try {
            withContext(Dispatchers.IO) {
                com.signalscope.app.trading.PositionRadar.refresh(applicationContext)
            }
        } catch (e: Exception) { Log.w(TAG, "position summary failed: ${e.message}") }
        if (completed) {
            Log.i(TAG, "Strategy Lab tick complete ($reason): ${nsList.size} ns [${nsList.joinToString(",")}]")
            // NAME WHAT IT DID. "Paper tick complete" told the user a thing had
            // happened without saying which thing, to what, or whether it mattered —
            // so it read as noise and got swiped away unread, which is the worst
            // outcome for the one channel that also carries real trade alerts.
            val open = try {
                (listOf("us", "de", "binance") +
                    com.signalscope.app.data.BucketStore.allNamespaces(applicationContext))
                    .distinct()
                    .sumOf { n -> StrategyLabStore.load(applicationContext, n)
                        .trades.count { it.status == "OPEN" } }
            } catch (e: Exception) { -1 }
            // WITH EVERY STOCK MARKET SHUT, say that instead. The watchlist sizes and
            // held counts are unchanged all night and read like a scan; what is
            // worth knowing is that nothing can trade, and when that changes.
            val nextOpen = com.signalscope.app.trading.MarketHours.nextOpeningText(System.currentTimeMillis())
            val summary = if (nextOpen != null) buildString {
                append("Stock markets shut")
                // Same test as the activity check above: an armed seat gate counts
                // even with every seat in cash, because the pass still runs it.
                val cryptoOn = nsList.any { n ->
                    com.signalscope.app.trading.Spaces.baseOf(n) == "binance" &&
                        StrategyLabStore.load(applicationContext, n).let { s ->
                            s.enabled && (s.enrolled.isNotEmpty() || try {
                                com.signalscope.app.data.MacdGateStore.load(applicationContext, n).enabled
                            } catch (e: Exception) { false })
                        }
                }
                if (cryptoOn) append(" · crypto checked")
                append(" · $nextOpen")
                // Anything that actually happened still shows — only the plain
                // counts ("DE 40") are left out.
                val notable = watchBits.filterNot { Regex("^(DE|US|IN) \\d+$").matches(it) }
                if (notable.isNotEmpty()) append(" · ${notable.joinToString(", ")}")
            } else buildString {
                append("Checked ${nsList.size} algos")
                if (open >= 0) append(" · $open open")
                if (watchBits.isNotEmpty()) append(" · watcher ${watchBits.joinToString(", ")}")
                if (floorNote.isNotEmpty()) append(" · $floorNote")
            }
            // Land on whatever the pass actually produced: the watch list when a
            // watcher raised something, the algos otherwise.
            updateScanNotification(summary, if (watchBits.isNotEmpty()) "watchlist" else "algos")
        } else {
            Log.w(TAG, "Strategy Lab tick timed out ($reason): ${nsList.size} ns [${nsList.joinToString(",")}]")
            updateScanNotification("Strategy Lab paper tick timed out")
        }
    }

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
        // Always re-promote discovery to foreground: after an OEM kills the old
        // process this new Service instance is not foreground yet.
        //
        // IT NO LONGER SWITCHES MONITORING ON. It used to, and nothing switched it
        // off again — so every scan, including the automatic India one each
        // weekday evening, left the phone in full monitoring with the Wi-Fi at
        // full power, and restarted it that way after every reboot.
        beginJob((if (robust) 4 else 2) * 60 * 60 * 1000L, "Starting discovery scan...")

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
            // The outer finally counts the scan out even if it is cancelled while
            // still queued for the lock, before the inner one could run.
            try { yahooScanLock.withLock {
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
                        "nifty500"  -> runYahooDiscovery(resolveNifty500Symbols(), "NIFTY 500", "₹", robust = robust, resumeFrom = resumeFrom, previousComplete = previousComplete)
                        "nasdaq100" -> runYahooDiscovery(NASDAQ_100_SYMBOLS, "NASDAQ 100", "$", robust = robust, resumeFrom = resumeFrom, previousComplete = previousComplete)
                        "dax"       -> runYahooDiscovery(resolveDaxSymbols(), "DAX 100", "€", robust = robust, resumeFrom = resumeFrom, previousComplete = previousComplete)
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
            } } finally {
                // Cancelled while queued: the inner block never ran, and a scan
                // left marked "running" would refuse every later one.
                isDiscoveryRunning = false
                ScanServiceResultHolder.isDiscoveryRunning = false
                endJob()
            }
        }
    }

    private fun resolveNifty500Symbols(): List<String> {
        updateScanNotification("Refreshing NIFTY 500 universe...")
        return when (val result = YahooFinanceClient.fetchNifty500Symbols()) {
            is YahooFinanceClient.NiftyUniverseResult.Success -> {
                val screened = nseScreenedUniverse(result.symbols)
                ScanServiceResultHolder.discoveryStatusText =
                    "NIFTY 500 universe: ${result.symbols.size} live + " +
                    "${screened.size - result.symbols.size} kept from the app's list " +
                    "= ${screened.size} screened"
                ScanServiceResultHolder.discoveryVersion++
                // Keep it. The algo screens want this list on the main thread,
                // where a multi-second download is not an option — without a
                // cached copy they would be stuck on the embedded fallback,
                // which is a snapshot of the index as it stood when someone
                // typed it out, not as it stands today.
                try {
                    val cfg = ConfigManager(applicationContext)
                    cfg.nseUniverseCached = result.symbols.joinToString(" ")
                    cfg.nseUniverseCachedAt = System.currentTimeMillis()
                } catch (e: Exception) { Log.w(TAG, "universe cache write failed: ${e.message}") }
                // The CACHE holds the live index verbatim; the union happens at
                // every point of use. Storing the union instead would make the
                // embedded strays indistinguishable from real constituents the
                // next time anything read it back.
                screened
            }
            is YahooFinanceClient.NiftyUniverseResult.Failure -> {
                Log.w(
                    TAG,
                    "NIFTY 500 universe refresh failed (${result.message}); " +
                            "using embedded fallback ${NIFTY_500_YAHOO_SYMBOLS.size}"
                )
                ScanServiceResultHolder.discoveryStatusText =
                    "NIFTY 500 universe fallback: ${NIFTY_500_YAHOO_SYMBOLS.size} symbols"
                ScanServiceResultHolder.discoveryVersion++
                nseScreenedUniverse(emptyList())
            }
        }
    }

    /**
     * The German universe for this run: the broker's live Xetra equity list
     * unioned with the curated one. Mirrors [resolveNifty500Symbols] — same
     * shape, same keep-the-last-good-copy contract, same "say what you used"
     * status line.
     */
    private fun resolveDaxSymbols(): List<String> {
        updateScanNotification("Refreshing German universe...")
        val live = try {
            com.signalscope.app.network.Trading212Client(ConfigManager(applicationContext))
                .xetraEquitySymbols()
        } catch (e: Exception) {
            Log.w(TAG, "Xetra universe read failed: ${e.message}"); emptyList()
        }
        val screened = deScreenedUniverse(live)
        if (live.isNotEmpty()) {
            // Keep it: the gate engine and the watcher want this universe on the
            // main thread, where a multi-second read is not an option. The CACHE
            // HOLDS THE LIVE LIST VERBATIM and the union happens at each point of
            // use — storing the union instead would make the curated extras
            // indistinguishable from broker names the next time it was read back.
            try {
                val cfg = ConfigManager(applicationContext)
                cfg.deUniverseCached = live.joinToString(" ")
                cfg.deUniverseCachedAt = System.currentTimeMillis()
            } catch (e: Exception) { Log.w(TAG, "DE universe cache write failed: ${e.message}") }
            val curated = DAX_SYMBOLS.map { it.uppercase(Locale.US) }.toSet()
            val fromBrokerOnly = screened.count { it !in curated }
            ScanServiceResultHolder.discoveryStatusText =
                "German universe: ${screened.size - fromBrokerOnly} curated + " +
                "$fromBrokerOnly more the broker lists = ${screened.size} screened"
        } else {
            Log.w(TAG, "Xetra universe empty — using curated list ${DAX_SYMBOLS.size}")
            ScanServiceResultHolder.discoveryStatusText =
                "German universe: broker list unavailable, using the app's " +
                "${DAX_SYMBOLS.size} curated names"
        }
        ScanServiceResultHolder.discoveryVersion++
        return screened
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

    private fun smoothTurnQualityScore(analysis: StockAnalysis, weights: ScoringWeights): Int {
        if (!analysis.smoothMacdTurnedPositiveToday) return 0
        val pctlMax = weights.setupMacdPctlMax.coerceIn(0.0, 100.0)
        var score = 25
        score += if (analysis.smoothMacdAccel > 0.0) 22 else if (analysis.smoothMacdAccel >= 0.0) 10 else 0
        score += if (analysis.smoothMacdLowDistPct <= 35.0) 22 else if (analysis.smoothMacdLowDistPct <= 55.0) 10 else 0
        score += if (analysis.macdPctl <= pctlMax) 16 else if (analysis.macdPctl <= (pctlMax + 15.0).coerceAtMost(90.0)) 7 else 0
        score += if (analysis.priceVel >= 0.0) 12 else if (analysis.priceVel >= -0.5) 5 else 0
        score += if (analysis.sma200Slope >= 0.0) 8 else if (analysis.sma200Slope >= -0.1) 4 else 0
        return score.coerceIn(0, 100)
    }

    private fun isQualitySmoothTurn(analysis: StockAnalysis, weights: ScoringWeights): Boolean =
        analysis.smoothMacdTurnedPositiveToday &&
                analysis.smoothMacdAccel > 0.0 &&
                analysis.smoothMacdLowDistPct <= 35.0 &&
                analysis.macdPctl <= weights.setupMacdPctlMax.coerceIn(0.0, 100.0) &&
                analysis.priceVel >= -0.5 &&
                analysis.sma200Slope >= -0.1

    private fun maybeNotifySmoothTurn(analysis: StockAnalysis, marketName: String, weights: ScoringWeights) {
        if (!isQualitySmoothTurn(analysis, weights)) return
        val q = smoothTurnQualityScore(analysis, weights)
        val zeroSide = if (analysis.macd >= 0.0) "above zero" else "below zero"
        val alert = StockAlert(
            symbol = analysis.symbol,
            name = analysis.name,
            alertType = AlertType.SMOOTH_BUY_TURN,
            message = "Smooth MACD turn Q$q: slope crossed positive today ($zeroSide). Check price/support for entry.",
            sellScore = 0,
            cherryPoints = analysis.cherryPoints,
            price = analysis.price,
            macdPhase = analysis.macdPhase,
            source = "discovery:$marketName"
        )
        synchronized(ScanServiceResultHolder.smoothTurnAlerts) {
            val staleBefore = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            ScanServiceResultHolder.smoothTurnAlerts.removeAll { it.timestamp < staleBefore }
            ScanServiceResultHolder.smoothTurnAlerts.removeAll {
                it.symbol == alert.symbol && it.alertType == AlertType.SMOOTH_BUY_TURN
            }
            ScanServiceResultHolder.smoothTurnAlerts.add(alert)
            if (ScanServiceResultHolder.smoothTurnAlerts.size > 100) {
                ScanServiceResultHolder.smoothTurnAlerts.removeAt(0)
            }
        }
        processAlerts(listOf(alert))
    }

    private fun closedDailyCandlesForDiscovery(
        candles: List<CandleData>,
        marketName: String
    ): List<CandleData> {
        if (candles.size < 2) return candles

        val (tzId, closeMinute) = when {
            marketName.contains("NASDAQ", ignoreCase = true) -> "America/New_York" to (16 * 60)
            marketName.contains("DAX", ignoreCase = true) -> "Europe/Berlin" to (17 * 60 + 30)
            else -> "Asia/Kolkata" to (15 * 60 + 30)
        }
        val tz = TimeZone.getTimeZone(tzId)
        val now = Calendar.getInstance(tz)
        val day = now.get(Calendar.DAY_OF_WEEK)
        if (day == Calendar.SATURDAY || day == Calendar.SUNDAY) return candles

        val last = Calendar.getInstance(tz)
        last.timeInMillis = candles.last().timestamp * 1000L
        val sameTradingDay =
            last.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                    last.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        if (!sameTradingDay) return candles

        val nowMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return if (nowMinute < closeMinute) {
            candles.dropLast(1)
        } else {
            candles
        }
    }

    private fun discoveryExchangeFor(marketName: String): String = when {
        marketName.contains("DAX", ignoreCase = true) -> "DE"
        marketName.contains("NIFTY", ignoreCase = true) -> "NSE"
        else -> "NASDAQ"
    }

    /**
     * THE LIQUIDITY FLOOR, AND WHY GERMANY NO LONGER HAS ONE.
     *
     * A floor is a sensible defence against an untrusted universe: a broad
     * index carries genuinely untradeable names, and screening them wastes a
     * scan on stocks nobody could get filled in.
     *
     * The German list is not an untrusted universe. It is 103 companies the
     * user picked by hand and verified one at a time, plus the names they
     * actually hold. A floor over a hand-picked list has nothing to protect
     * against — it only removes names the user deliberately chose.
     *
     * And it removed a great many. Measured across the German list: eighteen of
     * the 106 sat under 50,000 shares a day, including Rational, Sixt,
     * Hapag-Lloyd, KWS Saat and 1&1 — ordinary, liquid German companies that a
     * share-count threshold happens to catch because their shares are expensive
     * or their free float is small. A share count is the wrong unit for this;
     * €4m a day in a €900 share is 4,500 shares.
     *
     * Worse, the floor applied HERE and nowhere else. The algo screener has no
     * liquidity test at all, so it could open a position in a name Discovery
     * had been silently discarding every day — which is exactly what happened
     * with Ceconomy: bought by the gate engine at a 20-day average of ~25,000
     * shares, and absent from Discovery for the whole month before.
     *
     * NASDAQ keeps its floor: that list is a real index, not a curated one.
     * Anything dropped is now named in the run summary either way.
     */
    /** Which space's settings a Discovery market reads. */
    private fun discoveryBase(marketName: String): String = when {
        marketName.contains("NIFTY", ignoreCase = true) -> "in"
        marketName.contains("DAX", ignoreCase = true) -> "de"
        else -> "us"
    }

    private fun shortMoney(v: Double): String = when {
        v >= 1e7 -> "%.0fm".format(v / 1e6)
        v >= 1e6 -> "%.1fm".format(v / 1e6).replace(".0m", "m")
        v >= 1e3 -> "%.0fk".format(v / 1e3)
        else -> "%.0f".format(v)
    }

    // THE SHARE-COUNT FLOOR BELOW IS NOT THE LIQUIDITY FILTER. That is the
    // turnover floor set per market in the watcher panel and applied in the
    // scan loop — price times volume, the unit this comment argues for. What is
    // left here is NASDAQ's old share count, kept only because it removes nothing
    // the turnover floor would keep.
    private fun discoveryMinAvgVolume(marketName: String): Int = when {
        marketName.contains("NIFTY", ignoreCase = true) -> 0
        marketName.contains("DAX", ignoreCase = true) -> 0
        else -> 50000
    }

    private fun entryScore(analysis: StockAnalysis): Int =
        maxOf(analysis.pullbackScore, analysis.momentumScore, analysis.cherryPoints)

    private fun isModerateEntry(analysis: StockAnalysis): Boolean =
        analysis.pullbackScore >= weights.pullbackModerateThreshold ||
                analysis.momentumScore >= weights.momentumModerateThreshold ||
                analysis.cherryPoints >= weights.cherryModerateThreshold

    private fun isStrongEntry(analysis: StockAnalysis): Boolean =
        analysis.pullbackScore >= weights.pullbackStrongThreshold ||
                analysis.momentumScore >= weights.momentumStrongThreshold ||
                analysis.cherryPoints >= weights.cherryStrongThreshold

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
        /** Names the scan looked at and threw away, kept apart so the run can
         *  say WHY rather than reporting one opaque "skipped" total. */
        val thinlyTraded = mutableListOf<String>()
        val tooShort = mutableListOf<String>()
        val noData = mutableListOf<String>()

        // ── THE TURNOVER FLOOR, THE SAME ONE THE WATCHER USES ─────────────────
        //
        // Set per market in the watcher panel, and until now read ONLY there —
        // so the watcher's list shrank to what actually trades while this scan
        // went on showing the whole union of the broker's listing and the stored
        // list, long tail and all. One setting, one rule, both screens.
        //
        // Applied BEFORE the fundamentals request, which is the point of doing it
        // here: every name cut skips a second call to Yahoo and its pause, so a
        // floor makes the scan faster as well as shorter.
        //
        // NOTHING YOU PINNED OR THE BOOK HOLDS IS CUT, matching the watcher. If
        // that list cannot be read the floor is not applied at all — filtering
        // blind would cut exactly the rows that must never be cut.
        // Pins and book rows are stored bare ("ENR"); German scan symbols carry
        // the exchange ("ENR.DE"). Compared without either suffix, or no German
        // pin would ever be recognised and every one could be cut.
        fun liqKey(x: String) = x.trim().uppercase(Locale.US).removeSuffix(".NS").removeSuffix(".DE")
        val liqBase = discoveryBase(marketName)
        val liqFloor = try { config.minTurnoverFor(liqBase) } catch (e: Exception) { 0.0 }
        val liqExempt: Set<String>? = if (liqFloor <= 0.0) emptySet() else try {
            val st = com.signalscope.app.data.ManualWatchStore.load(this@ScanService, liqBase)
            HashSet<String>().apply {
                st.pinned.forEach { add(liqKey(it)) }
                st.entries.filter { it.status == "OPEN" || it.status == "PENDING" }
                    .forEach { add(liqKey(it.symbol)) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Discovery $marketName: pins/holdings unreadable — turnover floor skipped"); null
        }
        val belowTurnover = mutableListOf<String>()
        fun cutForTurnover(symbol: String, candles: List<com.signalscope.app.data.CandleData>): Boolean =
            liqFloor > 0.0 && liqExempt != null &&
                liqKey(symbol) !in liqExempt &&
                !com.signalscope.app.screener.Liquidity.passesCandles(candles, liqFloor)

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
                val exchange = discoveryExchangeFor(marketName)
                val candleResult = YahooFinanceClient.fetchCandles(symbol, exchange)

                when (candleResult) {
                    is YahooFinanceClient.CandleResult.Success -> {
                        consecutiveErrors = 0
                        val displayName = candleResult.name?.trim()?.takeIf { it.isNotBlank() } ?: symbol
                        val closedCandles = closedDailyCandlesForDiscovery(candleResult.candles, marketName)
                        val illiquid = cutForTurnover(symbol, closedCandles)
                        var analysis = if (illiquid) null else StockAnalyzer.analyze(
                            candles = closedCandles,
                            symbol = symbol,
                            name = displayName,
                            token = symbol,
                            minAvgVolume = discoveryMinAvgVolume(marketName),
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
                                        val refreshedName = fundResult.data.name?.trim()?.takeIf { it.isNotBlank() } ?: analysis.name
                                        analysis = analysis.copy(
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
                            val annotated = annotateSmoothMacdTurn(analysis, previousSmoothBySymbol, previousSmoothTimestamp)
                            allStocks.add(annotated)
                            maybeNotifySmoothTurn(annotated, marketName, weights)
                        } else {
                            skipped++
                            // SAY WHICH KIND OF NOTHING THIS IS.
                            //
                            // A dropped stock used to increment one counter and
                            // vanish, so "thin trade" and "not enough history"
                            // looked identical from outside — and both looked
                            // identical to the stock simply not being in the
                            // list. That is how a name the algo was free to BUY
                            // could be permanently absent from Discovery with
                            // nothing anywhere saying so.
                            if (illiquid) belowTurnover.add(symbol)
                            val floor = discoveryMinAvgVolume(marketName)
                            val vols = closedCandles.map { it.volume }
                            val avgVol20 =
                                if (vols.size >= 20) vols.takeLast(20).average()
                                else if (vols.isNotEmpty()) vols.average()
                                else 0.0
                            if (illiquid) {
                                // counted above, under its own reason
                            } else if (closedCandles.size < 50) {
                                tooShort.add(symbol)
                            } else if (floor > 0 && avgVol20 < floor) {
                                thinlyTraded.add(symbol)
                                Log.i(TAG, "Discovery: $symbol dropped — 20d avg volume " +
                                        "${avgVol20.toLong()} below floor $floor")
                            }
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
                        noData.add(symbol)
                    }
                    is YahooFinanceClient.CandleResult.Error -> {
                        consecutiveErrors++
                        errors++
                        lastError = "$symbol: ${candleResult.message}"
                        Log.w(TAG, "Discovery: error for $symbol — ${candleResult.message}")
                    }
                }

                // Update progress every stock (not just every 5 — gives smoother UI updates)
                val sorted = allStocks.sortedWith(
                    compareByDescending<StockAnalysis> { entryScore(it) }
                        .thenByDescending { it.momentumScore }
                        .thenByDescending { it.pullbackScore }
                )
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
                    buySignals = sorted.filter { isModerateEntry(it) },
                    strongBuys = sorted.filter { isStrongEntry(it) },
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
                    val exchange = discoveryExchangeFor(marketName)
                    val candleResult = YahooFinanceClient.fetchCandles(symbol, exchange)

                    when (candleResult) {
                        is YahooFinanceClient.CandleResult.Success -> {
                            val displayName = candleResult.name?.trim()?.takeIf { it.isNotBlank() } ?: symbol
                            val closedCandles = closedDailyCandlesForDiscovery(candleResult.candles, marketName)
                            val illiquid = cutForTurnover(symbol, closedCandles)
                            var analysis = if (illiquid) null else StockAnalyzer.analyze(
                                candles = closedCandles,
                                symbol = symbol,
                                name = displayName,
                                token = symbol,
                                minAvgVolume = discoveryMinAvgVolume(marketName),
                                w = weights
                            )
                            if (analysis != null) {
                                try {
                                    val fundResult = YahooFinanceClient.fetchFundamentals(symbol, exchange)
                                    if (fundResult is YahooFinanceClient.FundamentalResult.Success) {
                                        val vr = ValueAnalyzer.analyze(fundResult.data, analysis.price, w = weights)
                                        val refreshedName = fundResult.data.name?.trim()?.takeIf { it.isNotBlank() } ?: analysis.name
                                        analysis = analysis.copy(
                                            name = refreshedName,
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
                                val annotated = annotateSmoothMacdTurn(analysis, previousSmoothBySymbol, previousSmoothTimestamp)
                                allStocks.add(annotated)
                                maybeNotifySmoothTurn(annotated, marketName, weights)
                            } else {
                                skipped++
                                if (illiquid) belowTurnover.add(symbol)
                            }
                        }
                        is YahooFinanceClient.CandleResult.RateLimited -> {
                            retryErrors++
                            errors++
                            delay(5000L) // extra 5s cool-down on retry rate limit
                        }
                        is YahooFinanceClient.CandleResult.NoData -> { skipped++; noData.add(symbol) }
                        is YahooFinanceClient.CandleResult.Error -> {
                            retryErrors++
                            errors++
                        }
                    }

                    // Update UI during retry
                    val sorted = allStocks.sortedWith(
                        compareByDescending<StockAnalysis> { entryScore(it) }
                            .thenByDescending { it.momentumScore }
                            .thenByDescending { it.pullbackScore }
                    )
                    lastDiscoveryResult = DiscoveryScanResult(
                        market = marketName, currency = currency,
                        timestamp = System.currentTimeMillis(),
                        totalScanned = allStocks.size, totalSymbols = total,
                        skipped = skipped, errors = errors, lastError = lastError, isComplete = false,
                        allStocks = sorted,
                        buySignals = sorted.filter { isModerateEntry(it) },
                        strongBuys = sorted.filter { isStrongEntry(it) },
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
        val sorted = allStocks.sortedWith(
            compareByDescending<StockAnalysis> { entryScore(it) }
                .thenByDescending { it.momentumScore }
                .thenByDescending { it.pullbackScore }
        )
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
            buySignals = sorted.filter { isModerateEntry(it) },
            strongBuys = sorted.filter { isStrongEntry(it) },
            goldenBuys = sorted.filter { it.goldenBuy },
            setups = sorted.filter {
                (it.sma200 != null && it.price > it.sma200) &&
                        (it.macdPhase == "BUY FLIP" || it.macdPhase == "EARLY BUY")
            },
            durationMs = elapsed,
            dropped = mapOf(
                "belowFloor" to belowTurnover.distinct(),
                "noData" to noData.distinct(),
                "tooShort" to tooShort.distinct(),
                "thinVolume" to thinlyTraded.distinct()
            ).filterValues { it.isNotEmpty() },
            turnoverFloor = liqFloor
        )
        ScanServiceResultHolder.lastDiscoveryResult = lastDiscoveryResult
        ScanServiceResultHolder.discoveryVersion++

        // Persist to disk so results survive process death
        DiscoveryResultStore.save(this@ScanService, lastDiscoveryResult!!)

        // WHAT THE SCAN THREW AWAY, IN THE SCAN'S OWN SUMMARY.
        //
        // A dropped name is invisible by nature — it looks exactly like a name
        // that was never in the list. Saying so here is the difference between
        // "this stock isn't covered" and "this stock is covered and something
        // discarded it", which are opposite problems with opposite fixes.
        val droppedNote = buildString {
            if (thinlyTraded.isNotEmpty()) {
                append(" · ${thinlyTraded.size} below the volume floor (")
                append(thinlyTraded.take(6).joinToString(", ") { it.removeSuffix(".NS") })
                if (thinlyTraded.size > 6) append(", +${thinlyTraded.size - 6} more")
                append(")")
            }
            if (belowTurnover.isNotEmpty()) {
                append(" · ${belowTurnover.size} below the ${currency}${shortMoney(liqFloor)} turnover floor (")
                append(belowTurnover.take(6).joinToString(", ") { it.removeSuffix(".NS").removeSuffix(".DE") })
                if (belowTurnover.size > 6) append(", +${belowTurnover.size - 6} more")
                append(")")
                // EVERYTHING CUT IS NOT A MARKET, IT IS A SETTING. A figure typed
                // with too many zeros would otherwise present as an empty market.
                if (allStocks.isEmpty())
                    append(" — the floor removed EVERY name; the figure in the watcher panel is probably wrong")
            }
            if (tooShort.isNotEmpty()) append(" · ${tooShort.size} with too little history")
        }
        val statusMsg = (if (discoveryAbort) "Discovery stopped" else
            "Discovery done: ${allStocks.size} stocks in ${elapsed / 60000}m") + droppedNote
        updateScanNotification(statusMsg)
        ScanServiceResultHolder.discoveryStatusText = statusMsg
        if (thinlyTraded.isNotEmpty())
            Log.w(TAG, "Discovery $marketName dropped for thin trade: ${thinlyTraded.joinToString(", ")}")
        if (belowTurnover.isNotEmpty())
            Log.i(TAG, "Discovery $marketName below the ${"%,.0f".format(liqFloor)} turnover floor " +
                "(${belowTurnover.size}): ${belowTurnover.joinToString(", ")}")
        Log.i(TAG, "$statusMsg — ${sorted.count { isModerateEntry(it) }} entry signals, " +
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
        // Real execution is gated inside DailyPortfolioManager by the
        // individual auto-create, auto-trailing, and auto-exit switches.
        maybeRunDailyTick(result)
    }

    /**
     * Trigger the daily trailing-SL + MACD-exit tick if:
     *   - current time ≥ 15:20 IST (weekday) AND
     *   - we haven't already ticked today (lastDailyTickEpoch is not today IST)
     *   - result holds at least one analyzed holding
     *
     * The tick itself honors config.autoCreateGtt, autoTrailingEnabled, and
     * autoExitEnabled for the specific actions it may take.
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
            val report = mgr.runDailyTick(analyzedHoldings, dryRun = false)
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
            // FAR ENOUGH BELOW THE LAST PRICE TO BE LEGAL.
            //
            // The ceiling used to be 0.1% under the last price, but Zerodha
            // refuses any trigger closer than 0.25% — so a stop that landed in
            // that band was rejected outright and the holding kept no protection.
            // Held at 0.5% to leave room for the price to move between computing
            // this and the order arriving.
            val tick = tickFor(h.symbol)
            val trigger = roundToTick(rawTrigger.coerceIn(ltp * 0.75, ltp * 0.995), tick)
            val limit = roundToTick(trigger * (1.0 - config.slLimitPct), tick)
            val stopPct = (trigger / entry - 1.0) * 100.0

            if (existingStop == null) {
                suspend fun place(tr: Double, lim: Double) = zerodhaClient.createGttTrigger(
                    type = "single",
                    exchange = h.exchange,
                    tradingsymbol = h.symbol,
                    triggerValues = listOf(tr),
                    lastPrice = ltp,
                    orders = listOf(
                        ZerodhaClient.GttOrderSpec(
                            transactionType = "SELL",
                            quantity = h.quantity,
                            price = lim,
                            product = "CNC",
                            orderType = "LIMIT"
                        )
                    )
                )
                var trigger = trigger
                var limit = limit
                var result = place(trigger, limit)
                // ONE RETRY, ONLY WHEN THE BROKER NAMED THE TICK. It has just told
                // us exactly what this instrument needs; refusing to use that and
                // leaving the holding unprotected would be stubbornness, not
                // caution. Any other rejection is passed through untouched — this
                // does not retry blindly.
                if (result is ZerodhaClient.GttCreateResult.Failure) {
                    val t = tickFromError(result.message)
                    if (t != null && t != tick) {
                        learnTick(h.symbol, t)
                        trigger = roundToTick(rawTrigger.coerceIn(ltp * 0.75, ltp * 0.995), t)
                        limit = roundToTick(trigger * (1.0 - config.slLimitPct), t)
                        delay(400L)
                        result = place(trigger, limit)
                    }
                }
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

    // ════════════════════════════════════════════════════════════════════════
    // TICK SIZE — learned from the broker, because nothing else here knows it
    // ════════════════════════════════════════════════════════════════════════
    // This used to round every price to 0.05 and nothing else. Most NSE equities
    // do tick at 0.05, so it worked often enough to look correct — but some tick
    // at 0.50, and for those the broker rejected every stop the app tried to
    // place. The holding was then left with no protection at all, silently, on a
    // path whose whole job is to protect it.
    //
    // The app has no instrument table to consult, and fetching Zerodha's full
    // dump to answer one question per symbol is a large amount of machinery. But
    // the rejection message states the required tick outright — so the app reads
    // it, remembers it for that symbol, and gets it right from the next pass on.
    // Learning the answer from the refusal costs one failed order, once, per
    // instrument. Guessing costs the stop entirely, for ever.
    private val learnedTicks = HashMap<String, Double>()

    private fun tickFor(symbol: String): Double =
        learnedTicks[symbol.uppercase(Locale.US)] ?: config.learnedTickSize(symbol) ?: 0.05

    /** Pull the required tick out of a rejection like "should be a multiple of
     *  tick size 0.50". Returns null when the message is about something else. */
    private fun tickFromError(msg: String?): Double? {
        if (msg.isNullOrBlank()) return null
        val m = Regex("tick size\\s*([0-9]*\\.?[0-9]+)", RegexOption.IGNORE_CASE).find(msg)
        return m?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.takeIf { it > 0.0 }
    }

    private fun learnTick(symbol: String, tick: Double) {
        val k = symbol.uppercase(Locale.US)
        learnedTicks[k] = tick
        config.rememberTickSize(k, tick)
        Log.i(TAG, "Learned tick size $tick for $k from the broker's rejection")
    }

    private fun roundToTick(value: Double, tick: Double = 0.05): Double {
        if (tick <= 0.0) return value
        // The second rounding is not decoration. Multiplying back by a tick that
        // has no exact binary form gives numbers like 123.45000000000002, and a
        // broker validating "must be a multiple of the tick" can reject exactly
        // that — a rejection that would look identical to a genuine tick mistake
        // and send the retry chasing a value it already had right.
        return Math.round((Math.round(value / tick) * tick) * 100.0) / 100.0
    }

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
        // 9:15 AM to 3:30 PM IST, weekdays (actual NSE hours) — one definition,
        // shared with the alarm that wakes monitoring at the open.
        return com.signalscope.app.trading.IndianSession.isOpen(System.currentTimeMillis())
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
                sellScore = analysis.sellScore, cherryPoints = analysis.cherryPoints,
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
                        sellScore = analysis.sellScore, cherryPoints = analysis.cherryPoints,
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
                sellScore = analysis.sellScore, cherryPoints = analysis.cherryPoints,
                price = analysis.price, macdPhase = analysis.macdPhase, source = source))
        }

        // Stage 1 warning: MACD decelerating (EARLY SELL phase) — prepare to sell
        // Only fire if no stronger sell intent already generated
        if (analysis.macdPhase == "EARLY SELL" && analysis.sellIntent == "HOLD") {
            alerts.add(StockAlert(
                symbol = holding.symbol, name = holding.symbol,
                alertType = AlertType.PEAK_WARNING,
                message = "MACD decelerating (EARLY SELL phase) — DPM watchdog monitoring; will auto-confirm at 15:20 if histogram sustains < 50% of peak",
                sellScore = analysis.sellScore, cherryPoints = analysis.cherryPoints,
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

        // Gate 2: one-shot per symbol per trading day
        if (ScanServiceResultHolder.isSoftSniperFired(holding.symbol, today)) {
            return  // silent — already fired earlier in the same session
        }

        // Gate 3: don't fight the hard watchdog — if it already fired, it owns the
        // GTT slot. Defensive: BOOK_PROFIT branch also checks this, but defend in depth.
        if (ScanServiceResultHolder.isMacdWatchdogFired(holding.symbol, today)) return

        // Gate 4: data sanity
        if (holding.ltp <= 0 || holding.quantity <= 0) return

        val liveSignal =
            if (config.livePriceStreamingEnabled) {
                val yahooSymbol = YahooFinanceClient.toYahooSymbol(holding.symbol, holding.exchange)
                LivePortfolioBars.signalForYahooSymbol(yahooSymbol)
            } else null
        val liveMode = liveSignal?.ready == true
        val executionLtp = liveSignal
            ?.takeIf { liveMode && it.latestPrice > 0.0 }
            ?.latestPrice
            ?: holding.ltp
        val gainPct = if (liveMode && holding.avgPrice > 0.0) {
            (executionLtp - holding.avgPrice) / holding.avgPrice * 100.0
        } else {
            returnPct
        }
        if (gainPct < config.softSniperMinGainPct) {
            Log.i(TAG, "Soft sniper skipped for ${holding.symbol}: gain ${"%.1f".format(gainPct)}% < threshold ${config.softSniperMinGainPct}%")
            return
        }

        // Gate 6: live 15m rollover when the stream is healthy; otherwise the
        // existing 2-scan persistence fallback remains in charge.
        // Gate 6 (A): score persistence — require profitScore ≥ threshold to
        // have held for at least 2 consecutive 15-min ticks (~30 min) before
        // committing capital. Kills single-tick intraday flicker fires that
        // happen because daily indicators reading a partial bar are noisy.
        // The streak counter is updated every scan in the holdings loop.
        if (liveMode) {
            val signal = liveSignal ?: return
            if (!signal.shouldProtect) {
                Log.i(
                    TAG,
                    "[SOFT SNIPER] ${holding.symbol} live ticker active; waiting for 15m rollover " +
                    "(${signal.reason}, pullback=${"%.2f".format(signal.dropFromSessionHighPct)}%, " +
                    "slope=${"%.2f".format(signal.closeSlopePct)}%)"
                )
                return
            }
        } else {
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
            liveMode ->
                config.softSniperBufferPct to "LIVE_SCAN"
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
        if (!ScanServiceResultHolder.markSoftSniperFiredIfAbsent(holding.symbol, today)) return

        // Compute target levels — round to 0.05 ticks (Kite minimum)
        val triggerRaw = executionLtp * (1.0 - bufferPct)
        val triggerPrice = (Math.round(triggerRaw * 20.0) / 20.0)
            .coerceIn(executionLtp * 0.75, executionLtp * 0.999)  // Kite ±25% rule + must be < LTP
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
                    lastPrice = executionLtp,
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
                        val pctFromLtp = (triggerPrice / executionLtp - 1) * 100
                        val msg = "Auto-GTT [$bufferTier] @ ₹${"%.2f".format(triggerPrice)} (${"%.1f".format(pctFromLtp)}%) — limit ₹${"%.2f".format(limitPrice)}, gain ${"%.1f".format(gainPct)}%, vol ${"%.1f".format(volRatio)}x"
                        Log.i(TAG, "[SOFT SNIPER] ${holding.symbol} → GTT #${result.id} placed. $msg")
                        ScanServiceResultHolder.softSniperAlerts.add(StockAlert(
                            symbol = holding.symbol, name = holding.symbol,
                            alertType = AlertType.SOFT_SNIPER,
                            message = msg,
                            sellScore = analysis.sellScore, cherryPoints = analysis.cherryPoints,
                            price = executionLtp, macdPhase = analysis.macdPhase, source = if (liveMode) "zerodha-live" else "zerodha"
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

    /**
     * @param openTab where a tap should LAND ("algos", "watchlist", or blank to
     *   leave the app wherever it was).
     *
     * Landing wherever the app happened to be left is why this notice read as
     * noise: it announced that something had happened and then dropped the user
     * somewhere unrelated to it, so the only way to find out what it meant was to
     * go looking. A notice about the algos should open the algos.
     */
    private fun buildScanNotification(text: String, openTab: String = ""): Notification {
        val intent = Intent(this, MainActivity::class.java)
        if (openTab.isNotBlank()) {
            intent.putExtra(com.signalscope.app.trading.LabNotifier.EXTRA_OPEN_TAB, openTab)
            // Without a distinct action+data the system hands back the EXISTING
            // intent for an already-running task and the extra is dropped — the tap
            // then just brings the app forward, doing nothing.
            intent.action = Intent.ACTION_VIEW
            intent.data = android.net.Uri.parse("signalscope://tab/$openTab")
        }
        val pi = PendingIntent.getActivity(this, if (openTab.isBlank()) 0 else openTab.hashCode(),
            intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_SCAN)
            .setContentTitle("SignalScope")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(pi).setOngoing(true).setSilent(true).build()
    }

    private fun updateScanNotification(text: String, openTab: String = "") {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_SCAN_ID, buildScanNotification(text, openTab))
    }

    @Suppress("DEPRECATION")
    private fun sendAlertNotification(alert: StockAlert) {
        val title = when (alert.alertType) {
            AlertType.STRONG_EXIT     -> "🔴 STRONG EXIT: ${alert.symbol}"
            AlertType.BOOK_PROFIT     -> "💰 BOOK PROFIT: ${alert.symbol}"
            AlertType.PROTECT_CAPITAL -> "TREND BROKEN: ${alert.symbol}"
            AlertType.PEAK_WARNING    -> "⚡ Peak Approaching: ${alert.symbol}"
            AlertType.MACD_FLIP       -> "🚨 MACD FLIP: ${alert.symbol}"
            AlertType.SMOOTH_BUY_TURN -> "Smooth MACD Turn: ${alert.symbol}"
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
            AlertType.SMOOTH_BUY_TURN -> NotificationCompat.PRIORITY_HIGH
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
            AlertType.SMOOTH_BUY_TURN -> 0xFF0D9488.toInt()  // Teal
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
            AlertType.SMOOTH_BUY_TURN -> "Early entry timing - confirm price/support before buying"
            else -> ""
        }

        val currency = when {
            alert.source.contains("NASDAQ", ignoreCase = true) ||
                    alert.source.contains("US", ignoreCase = true) -> "$"
            alert.source.contains("DAX", ignoreCase = true) ||
                    alert.source.contains("DE", ignoreCase = true) ||
                    alert.source.contains("Germany", ignoreCase = true) -> "€"
            else -> "₹"
        }
        val bigBody = buildString {
            append(alert.message)
            append("\n$currency${String.format(Locale.US, "%.2f", alert.price)} · ${alert.macdPhase}")
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
                        AlertType.SMOOTH_BUY_TURN -> setVibrate(longArrayOf(0, 180, 80, 180))
                        else -> {}
                    }
                }
            }
            .build()
        // The holding it is about, in the market it trades in, so the list can
        // open it again.
        val alertSpace = when (currency) { "$" -> "us"; "€" -> "de"; else -> "in" }
        NotificationLog.post(this, getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager,
            alert.symbol.hashCode() + 1000, notification,
            NotificationLog.Link(holding = alert.symbol, space = alertSpace))
    }

    private fun sendZerodhaExpiredNotification() {
        val pi = PendingIntent.getActivity(this, 9999, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setContentTitle("Zerodha session expired")
            .setContentText("Open SignalScope to reconnect your Zerodha account")
            .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()
        NotificationLog.post(this, getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager,
            9999, notification)
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
/**
 * THE INDIAN UNIVERSE ACTUALLY SCREENED: the live index UNION the list embedded
 * below. Every Indian screen in the app goes through here, so discovery, the
 * watcher and the algo screener all look at the same market.
 *
 * WHY A UNION AND NOT SIMPLY THE LIVE INDEX. The index is a membership rule, not
 * a judgement about a company. A name dropped from it is often still liquid,
 * still worth holding, and still perfectly tradeable — it just stopped meeting a
 * size or turnover cut-off. Screening the live list alone silently retires those
 * companies from the app, which is a decision the index committee made for
 * entirely unrelated reasons.
 *
 * WHY NOT THE EMBEDDED LIST ALONE, which is what the watcher used to do: it was
 * typed out by hand at some point and has been frozen ever since, so everything
 * added to the index afterwards was invisible. That is the same failure in the
 * other direction and harder to notice, because a missing name looks like
 * nothing at all.
 *
 * The union has neither blind spot. It costs a longer scan — a couple of hundred
 * extra symbols — and some of the embedded strays will have been delisted or
 * renamed since, which shows up honestly in the run's "no data" count rather
 * than as a wrong answer.
 *
 * LIVE NAMES COME FIRST so a partial or interrupted scan covers the current index
 * before it reaches the extras.
 */
fun nseScreenedUniverse(live: List<String>): List<String> {
    val valid = Regex("^[A-Z0-9&-]+\\.?[A-Z]*$")
    val out = LinkedHashSet<String>()
    fun add(raw: String) {
        val k = raw.trim().uppercase(Locale.US).removeSuffix(".NS")
        if (k.isNotBlank() && valid.matches(k)) out.add(k)
    }
    for (x in live) add(x)
    for (x in NIFTY_500_YAHOO_SYMBOLS) add(x)
    return out.toList()
}

/**
 * THE GERMAN UNIVERSE ACTUALLY SCREENED: the broker's live Xetra equity list
 * UNION the curated list below. Same argument as [nseScreenedUniverse], and it
 * was missing here for no better reason than that nobody had written it.
 *
 * WHY A UNION AND NOT THE CURATED LIST ALONE. It was typed out by hand on one
 * day and has been frozen ever since, so every German company the broker has
 * added since is invisible — and invisible in the worst way, because a missing
 * name looks like nothing at all. You cannot notice an absence.
 *
 * WHY NOT THE BROKER'S LIST ALONE. It is a tradeability list, not a judgement.
 * It will drop a name the moment the broker stops offering it, taking with it a
 * company that is still listed, still liquid and possibly still held. The
 * curated side keeps those.
 *
 * CURATED NAMES COME FIRST, which is the opposite order from India and
 * deliberate. There the live index is the higher-signal list and the embedded
 * one is the stale extra. Here it is the other way round: the curated 103 were
 * each checked by hand, and the broker's list is the broad extra. A partial or
 * interrupted scan should cover the good list first.
 */
fun deScreenedUniverse(live: List<String>): List<String> {
    val valid = Regex("^[A-Z0-9]+\\.DE$")
    val out = LinkedHashSet<String>()
    fun add(raw: String) {
        var k = raw.trim().uppercase(Locale.US)
        if (k.isBlank()) return
        if (!k.endsWith(".DE")) k = "$k.DE"
        if (valid.matches(k)) out.add(k)
    }
    for (x in DAX_SYMBOLS) add(x)
    for (x in live) add(x)
    return out.toList()
}

/** The same union from the kept copy — a preference read, never the network, so
 *  it is safe on the main thread and on a pass with no connection. Hands back
 *  the curated list alone until a German scan has run once, which is honest:
 *  that is genuinely all the app knows at that point. */
fun deScreenedUniverse(ctx: android.content.Context): List<String> {
    val cached = try {
        com.signalscope.app.data.ConfigManager(ctx).deUniverseCached
    } catch (e: Exception) { "" }
    return deScreenedUniverse(
        cached.split(' ', '\n', '\r', '\t', ',').filter { it.isNotBlank() })
}

/** The same union, for callers holding only the cached copy a scan saved rather
 *  than a freshly downloaded list. Reads a preference and never the network, so
 *  it is safe on the main thread and on a background pass with no connection. */
fun nseScreenedUniverse(ctx: android.content.Context): List<String> {
    val cached = try {
        com.signalscope.app.data.ConfigManager(ctx).nseUniverseCached
    } catch (e: Exception) { "" }
    return nseScreenedUniverse(
        cached.split(' ', '\n', '\r', '\t', ',').filter { it.isNotBlank() })
}

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
// Wakes the service once per weekday PER MARKET to run a ROBUST discovery scan:
// India after the NSE close, Germany and the US an hour after each opens. See
// [DiscoveryAutoScheduler] for why each sits where it does.
//
// Each market keeps one alarm (its next slot) and re-arms after every fire, so
// the chains self-heal across reboots / process death without needing
// setRepeating's drift problems — and a slot missed outright is caught up.
// ═══════════════════════════════════════════════════════
/**
 * Schedule a wake-up that is actually ALLOWED to start a foreground service.
 *
 * THE BUG THIS FIXES: every scheduler here used inexact `setAndAllowWhileIdle`
 * with a `getForegroundService` PendingIntent, on targetSdk 34. From Android 12
 * onwards an app in the background may not start a foreground service — and the
 * alarm exemption that would permit it is granted ONLY to EXACT alarms. An
 * inexact alarm does not qualify, so when it fired with the app backgrounded the
 * service start threw ForegroundServiceStartNotAllowedException and the tick
 * simply never happened. It worked whenever the app was open (foreground apps are
 * exempt anyway), which is exactly the "only Save & run advances anything"
 * symptom: the alarm chain was alive and re-arming, every firing just died.
 *
 * SCHEDULE_EXACT_ALARM is already declared in the manifest, but on Android 13+ it
 * can be revoked by the user, so the capability is checked at runtime and the old
 * inexact call remains the fallback (better a delayed tick than a crash).
 */
private fun scheduleWakeupAlarm(
    am: AlarmManager, triggerAtElapsed: Long, pi: android.app.PendingIntent, tag: String
) {
    val canExact = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
        am.canScheduleExactAlarms()
    try {
        if (canExact) am.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsed, pi)
        else {
            Log.w(tag, "exact alarms not permitted — falling back to inexact; " +
                "background ticks may be skipped by the OS")
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsed, pi)
        }
    } catch (e: SecurityException) {
        Log.w(tag, "exact alarm refused (${e.message}) — using inexact")
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsed, pi)
    }
}

/**
 * Wakes portfolio monitoring at the Indian open.
 *
 * Monitoring lets the phone sleep while the market is shut, and a sleeping phone
 * does not run timers — so something has to wake it for the bell. One alarm,
 * replaced every time it is set, cancelled when monitoring is switched off.
 */
object MonitorWakeScheduler {
    private const val TAG = "MonitorWake"
    private const val REQUEST_CODE = 0x51AC

    private fun pending(ctx: Context): android.app.PendingIntent {
        val intent = ScanService.createIntent(ctx, ScanService.ACTION_MONITOR_WAKE)
        val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            android.app.PendingIntent.getForegroundService(ctx, REQUEST_CODE, intent, flags)
        else android.app.PendingIntent.getService(ctx, REQUEST_CODE, intent, flags)
    }

    fun schedule(ctx: Context, atWallMs: Long) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val delay = (atWallMs - System.currentTimeMillis()).coerceAtLeast(60_000L)
            scheduleWakeupAlarm(am, SystemClock.elapsedRealtime() + delay, pending(ctx), TAG)
        } catch (e: Exception) { Log.w(TAG, "could not set the market-open alarm", e) }
    }

    fun cancel(ctx: Context) {
        try {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(ctx))
        } catch (_: Exception) {}
    }
}

object StrategyLabScheduler {
    private const val TAG = "StrategyLabSched"
    private const val REQUEST_CODE = 0x51AB
    private const val INTERVAL_MS = 15 * 60 * 1000L
    private const val GRACE_MS = 90 * 1000L

    fun ensureScheduled(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = paperTickPendingIntent(ctx)
        if (!hasActivePaperLab(ctx)) {
            am.cancel(pi)
            Log.d(TAG, "No enabled/enrolled Strategy Lab namespace; paper tick alarm cancelled")
            return
        }

        val nextFireWallMs = nextQuarterWithGraceMs()
        val delay = (nextFireWallMs - System.currentTimeMillis()).coerceAtLeast(60_000L)
        val triggerAt = SystemClock.elapsedRealtime() + delay
        am.cancel(pi)
        scheduleWakeupAlarm(am, triggerAt, pi, TAG)
        Log.i(TAG, "Next Strategy Lab paper tick in ${delay / 60000} min")
    }

    private fun paperTickPendingIntent(ctx: Context): android.app.PendingIntent {
        val intent = ScanService.createIntent(ctx, ScanService.ACTION_STRATEGY_LAB_TICK)
        val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.PendingIntent.getForegroundService(ctx, REQUEST_CODE, intent, flags)
        } else {
            android.app.PendingIntent.getService(ctx, REQUEST_CODE, intent, flags)
        }
    }

    private fun hasActivePaperLab(ctx: Context): Boolean =
        (listOf("us", "de", "binance") +
            com.signalscope.app.data.BucketStore.allNamespaces(ctx)).distinct().any { ns ->
            val s = StrategyLabStore.load(ctx, ns)
            // Gate-armed counts even with 0 enrolled: under 3b all seats can sit
            // in cash, and cancelling the alarm then would stop the daily gate
            // evaluation that is the only way seats ever fill again.
            val gateArmed = try {
                com.signalscope.app.data.MacdGateStore.load(ctx, ns).enabled
            } catch (e: Exception) { false }
            s.enabled && (s.enrolled.isNotEmpty() || gateArmed)
        } || try { com.signalscope.app.data.FloorAlgoStore.anyRunning(ctx) } catch (e: Exception) { false }

    /**
     * The next pass slot still ahead of [now].
     *
     * A SLOT LESS THAN A MINUTE AWAY IS STILL THIS ONE. This used to require a
     * full minute's notice and otherwise jump to the slot after — so any start of
     * the service in the minute before a pass (an automatic discovery scan firing
     * at 12:45 ahead of the 12:46:30 pass, a scan or order started by hand) re-armed
     * the alarm a quarter of an hour on, and that pass simply never ran: no stop
     * checked, no exit taken, for fifteen minutes. Now it is kept, and the
     * one-minute floor on the alarm makes it run at most a minute late instead.
     * The alarm never fires early, so at its own firing the slot has passed and
     * this moves on to the next one as before.
     */
    internal fun nextQuarterWithGraceMs(now: Long = System.currentTimeMillis()): Long {
        val currentSlot = (now / INTERVAL_MS) * INTERVAL_MS + GRACE_MS
        if (currentSlot > now) return currentSlot
        return ((now / INTERVAL_MS) + 1) * INTERVAL_MS + GRACE_MS
    }
}

object T212DailyTrailScheduler {
    private const val TAG = "T212DailyTrailSched"
    private const val ACTION_FIRE = "com.signalscope.T212_DAILY_TRAIL_FIRE"
    private const val REQUEST_CODE = 0x7212

    private data class Slot(val label: String, val timeZoneId: String, val minuteOfDay: Int)

    private val SLOTS = listOf(
        Slot("xetra", "Europe/Berlin", 17 * 60 + 45),
        Slot("us", "America/New_York", 16 * 60 + 20)
    )

    fun ensureScheduled(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = android.app.PendingIntent.getBroadcast(
            ctx, REQUEST_CODE,
            Intent(ctx, T212DailyTrailReceiver::class.java).setAction(ACTION_FIRE),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val nextFireWallMs = nextSlotWallTimeMs()
        val delay = (nextFireWallMs - System.currentTimeMillis()).coerceAtLeast(60_000L)
        val triggerAt = SystemClock.elapsedRealtime() + delay
        am.cancel(pi)
        scheduleWakeupAlarm(am, triggerAt, pi, TAG)
        Log.i(TAG, "Next T212 trail alarm in ${delay / 60000} min")
    }

    private fun nextSlotWallTimeMs(): Long {
        return SLOTS.minOf { nextSlotFor(it) }
    }

    private fun nextSlotFor(slot: Slot): Long {
        val tz = TimeZone.getTimeZone(slot.timeZoneId)
        val cal = Calendar.getInstance(tz)
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (isWeekday(cal) && slot.minuteOfDay > nowMin) {
            cal.set(Calendar.HOUR_OF_DAY, slot.minuteOfDay / 60)
            cal.set(Calendar.MINUTE, slot.minuteOfDay % 60)
            return cal.timeInMillis
        }
        do {
            cal.add(Calendar.DAY_OF_MONTH, 1)
        } while (!isWeekday(cal))
        cal.set(Calendar.HOUR_OF_DAY, slot.minuteOfDay / 60)
        cal.set(Calendar.MINUTE, slot.minuteOfDay % 60)
        return cal.timeInMillis
    }

    private fun isWeekday(cal: Calendar): Boolean {
        val d = cal.get(Calendar.DAY_OF_WEEK)
        return d != Calendar.SATURDAY && d != Calendar.SUNDAY
    }
}

class T212DailyTrailReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.i("T212DailyTrailRecv", "T212 trailing alarm fired")
        T212DailyTrailScheduler.ensureScheduled(context)
        val svc = ScanService.createIntent(context, ScanService.ACTION_T212_DAILY_TRAIL)
        try {
            context.startForegroundService(svc)
        } catch (e: Exception) {
            Log.e("T212DailyTrailRecv", "Failed to start ScanService for T212 trail", e)
        }
    }
}

/**
 * Wakes the phone before the Indian open to renew the Kite session.
 *
 * THREE SLOTS, NOT ONE. Kite's login is a network call against a service that is
 * itself busiest right before the open, and the phone may be on a flaky café
 * hotspot at 4am. A single attempt that hits a blip costs the whole session, so
 * two later slots stand behind the first. They are free when they aren't needed:
 * each one checks the existing token first and returns immediately if it works.
 *
 * A credential rejection cancels the remaining slots for the day (the service
 * checks the block flag) — that path is about avoiding an account lock, and no
 * amount of retrying fixes a wrong password.
 *
 * Weekdays only, Indian calendar. The token expires every day including Saturday,
 * but nothing trades then, so there is no reason to wake the phone for it.
 */
object ZerodhaAutoLoginScheduler {
    private const val TAG = "ZerodhaLoginSched"
    private const val ACTION_FIRE = "com.signalscope.ZERODHA_AUTO_LOGIN_FIRE"
    private const val REQUEST_CODE = 0x2ED0
    private const val CATCHUP_REQUEST_CODE = 0x2ED1

    // IST. Kite drops yesterday's token in the early hours; the pre-open auction
    // starts 09:00 and the open is 09:15, so this is all comfortably ahead of it.
    // In Germany that is roughly 03:50 / 04:20 / 05:00 in summer — the hours this
    // whole feature exists to sleep through.
    private val SLOT_MINUTES = listOf(7 * 60 + 20, 7 * 60 + 50, 8 * 60 + 30)

    fun ensureScheduled(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = firePendingIntent(ctx, REQUEST_CODE)
        val config = ConfigManager(ctx)
        if (!config.canAutoLoginZerodha) {
            am.cancel(pi)
            am.cancel(firePendingIntent(ctx, CATCHUP_REQUEST_CODE))
            Log.d(TAG, "Auto-login off or incomplete; alarms cancelled")
            return
        }

        val delay = (nextSlotWallTimeMs() - System.currentTimeMillis()).coerceAtLeast(60_000L)
        am.cancel(pi)
        scheduleWakeupAlarm(am, SystemClock.elapsedRealtime() + delay, pi, TAG)
        Log.i(TAG, "Next Zerodha auto-login in ${delay / 60000} min")

        maybeScheduleCatchUp(ctx, am, config)
    }

    /**
     * Reboot at 10am IST, or the app installed mid-session, leaves every slot for
     * the day already behind us and the account logged out until tomorrow. When
     * that happens and the trading day is still running, fire one attempt a minute
     * from now. The service re-checks the token first, so on a healthy session
     * this costs one wake-up and nothing else.
     */
    private fun maybeScheduleCatchUp(ctx: Context, am: AlarmManager, config: ConfigManager) {
        val pi = firePendingIntent(ctx, CATCHUP_REQUEST_CODE)
        am.cancel(pi)

        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        if (!isWeekday(cal)) return
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        // Between the first slot and the NSE close, with the session already gone.
        if (nowMin < SLOT_MINUTES.first() || nowMin > 15 * 60 + 30) return
        if (config.isZerodhaConnected) return
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        if (config.zerodhaAutoLoginBlockedOn == sdf.format(Date())) return
        val sinceLast = System.currentTimeMillis() - config.zerodhaAutoLoginStatusAt
        if (config.zerodhaAutoLoginStatusAt > 0L && sinceLast < 20 * 60 * 1000L) return

        scheduleWakeupAlarm(am, SystemClock.elapsedRealtime() + 60_000L, pi, TAG)
        Log.i(TAG, "Zerodha session missing mid-day — catch-up login queued")
    }

    private fun firePendingIntent(ctx: Context, requestCode: Int): android.app.PendingIntent =
        android.app.PendingIntent.getBroadcast(
            ctx, requestCode,
            Intent(ctx, ZerodhaAutoLoginReceiver::class.java).setAction(ACTION_FIRE),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

    private fun nextSlotWallTimeMs(): Long {
        val tz = TimeZone.getTimeZone("Asia/Kolkata")
        val cal = Calendar.getInstance(tz)
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val today = (cal.clone() as Calendar).apply {
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        for (slot in SLOT_MINUTES) {
            if (slot > nowMin && isWeekday(today)) {
                today.set(Calendar.HOUR_OF_DAY, slot / 60)
                today.set(Calendar.MINUTE, slot % 60)
                return today.timeInMillis
            }
        }
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

class ZerodhaAutoLoginReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.i("ZerodhaLoginRecv", "Zerodha auto-login alarm fired")
        // Re-arm first so a failure inside the login can't break the chain.
        ZerodhaAutoLoginScheduler.ensureScheduled(context)
        val svc = ScanService.createIntent(context, ScanService.ACTION_ZERODHA_AUTO_LOGIN)
        try {
            context.startForegroundService(svc)
        } catch (e: Exception) {
            Log.e("ZerodhaLoginRecv", "Failed to start ScanService for Zerodha auto-login", e)
        }
    }
}

/**
 * ONE AUTOMATIC DISCOVERY SCAN PER MARKET, EACH WEEKDAY, ON THAT MARKET'S CLOCK.
 *
 * Only India used to have one. Germany and the US refreshed only when someone
 * tapped Scan, so their tables sat for days — "2 days old" on a Monday morning
 * with Xetra three hours into the session — and every number read off them (a
 * stock's sheet, its MACD line) was as old as the scan.
 *
 * WHEN EACH ONE RUNS, and why they differ:
 *  • India, 16:15 IST — after the close. Unchanged: it was placed there so the
 *    day's candle is finished and the table is ready for the next morning.
 *  • Germany, 09:17 Berlin — as soon as the open is in the data. Xetra prices
 *    reach the app about a quarter of an hour late, so 09:17 is the first
 *    moment the day's opening trades are there to score.
 *  • The US, 09:31 New York — a minute after the bell. US prices arrive with
 *    next to no delay, so one minute is enough for the opening trade to be in.
 *    Kept on New York time so the clock changes, which Europe and America make
 *    on different weekends, cannot shift it off the open.
 *
 * A MISSED SLOT IS CAUGHT UP. The phone may be off at 10:00, or the alarm may be
 * held back by the OS; whenever the service next starts on the same day and the
 * market has no finished scan since its slot, it schedules one a minute out. A
 * scan you ran by hand after the slot counts — it is the same table. Each market
 * gets at most [MAX_TRIES_PER_DAY] attempts a day, [RETRY_GAP_MS] apart, so a
 * day when the data source is down costs three tries, not a battery.
 *
 * Holidays are not known here: on a weekday the market is shut, the scan simply
 * reads the previous session again.
 */
object DiscoveryAutoScheduler {
    private const val TAG = "DiscoveryAutoSched"
    private const val ACTION_FIRE = "com.signalscope.AUTO_DISCOVERY_FIRE"
    const val EXTRA_MARKET = "market"
    private const val PREFS = "discovery_auto"
    private const val RETRY_GAP_MS = 30 * 60 * 1000L
    private const val MAX_TRIES_PER_DAY = 3

    /**
     * @param minute     local time the scan starts
     * @param lastMinute local time after which a missed scan waits for the next day
     */
    private data class Slot(
        val market: String, val name: String, val zone: String,
        val minute: Int, val lastMinute: Int, val requestCode: Int, val action: String)

    private val SLOTS = listOf(
        // India's request code is the one its single alarm always had, so the
        // alarm armed by the previous install is replaced rather than doubled.
        Slot("nifty500", "NIFTY 500", "Asia/Kolkata", 16 * 60 + 15, 22 * 60, 0x5C0E,
            ScanService.ACTION_DISCOVERY_NIFTY500_AUTO),
        Slot("dax", "DAX 100", "Europe/Berlin", 9 * 60 + 17, 17 * 60 + 30, 0x5C0F,
            ScanService.ACTION_DISCOVERY_DAX_AUTO),
        Slot("nasdaq100", "NASDAQ 100", "America/New_York", 9 * 60 + 31, 16 * 60, 0x5C10,
            ScanService.ACTION_DISCOVERY_NASDAQ100_AUTO))

    fun ensureScheduled(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()
        for (slot in SLOTS) {
            try {
                val at = catchUpAt(ctx, slot, now) ?: nextSlotMs(slot, now)
                val delay = (at - now).coerceAtLeast(60_000L)
                val pi = pending(ctx, slot)
                am.cancel(pi)
                scheduleWakeupAlarm(am, SystemClock.elapsedRealtime() + delay, pi, TAG)
                Log.i(TAG, "[${slot.name}] next auto-discovery in ${delay / 60000} min")
            } catch (e: Exception) {
                Log.w(TAG, "[${slot.name}] could not set its alarm", e)
            }
        }
    }

    /**
     * The alarm for [market] has gone off: is a scan actually due? Records the
     * attempt and returns the service action to run, or null when today's scan
     * already exists or the day's attempts are spent. Asked at the moment of
     * firing because a retry armed half an hour ago knows nothing of a scan that
     * has finished since.
     */
    fun claimIfDue(ctx: Context, market: String?): String? {
        val slot = SLOTS.firstOrNull { it.market == market } ?: SLOTS[0]
        val now = System.currentTimeMillis()
        val due = catchUpAt(ctx, slot, now, firing = true) ?: return null
        if (due > now + 60_000L) return null
        val p = prefs(ctx)
        val slotMs = slotMsOn(slot, now)
        val tries = if (p.getLong("lastTry_${slot.market}", 0L) >= slotMs)
            p.getInt("tries_${slot.market}", 0) else 0
        p.edit().putLong("lastTry_${slot.market}", now)
            .putInt("tries_${slot.market}", tries + 1).apply()
        return slot.action
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun pending(ctx: Context, slot: Slot): android.app.PendingIntent =
        android.app.PendingIntent.getBroadcast(
            ctx, slot.requestCode,
            Intent(ctx, AutoDiscoveryReceiver::class.java).setAction(ACTION_FIRE)
                .putExtra(EXTRA_MARKET, slot.market),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

    /** Today's slot for this market, in wall-clock millis — whether or not it has passed. */
    private fun slotMsOn(slot: Slot, now: Long): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone(slot.zone))
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, slot.minute / 60)
        cal.set(Calendar.MINUTE, slot.minute % 60)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * When today's scan should still be run, or null if it is not owed: not a
     * weekday, before the slot or past the day's window, already scanned since
     * the slot (by the alarm or by hand), or out of attempts. [firing] relaxes the
     * spacing by two minutes, since an alarm can land a little after it was due.
     */
    private fun catchUpAt(ctx: Context, slot: Slot, now: Long, firing: Boolean = false): Long? {
        val p = prefs(ctx)
        return owedAt(slot, now,
            lastCompleteAt = DiscoveryResultStore.lastCompleteAt(ctx, slot.name),
            lastTry = p.getLong("lastTry_${slot.market}", 0L),
            tries = p.getInt("tries_${slot.market}", 0), firing = firing)
    }

    /** When [market]'s automatic scan next runs: a catch-up still owed today,
     *  else the next regular slot. For the page's "last scan" line. */
    fun nextRunAt(ctx: Context, market: String, now: Long = System.currentTimeMillis()): Long? {
        val slot = SLOTS.firstOrNull { it.market == market } ?: return null
        return catchUpAt(ctx, slot, now) ?: nextSlotMs(slot, now)
    }

    /** [catchUpAt] with its stored inputs passed in, so the rules can be tested. */
    internal fun owedAt(market: String, now: Long, lastCompleteAt: Long, lastTry: Long,
                        tries: Int, firing: Boolean = false): Long? =
        owedAt(SLOTS.first { it.market == market }, now, lastCompleteAt, lastTry, tries, firing)

    private fun owedAt(slot: Slot, now: Long, lastCompleteAt: Long, lastTry: Long,
                       tries: Int, firing: Boolean): Long? {
        val cal = Calendar.getInstance(TimeZone.getTimeZone(slot.zone))
        cal.timeInMillis = now
        if (!isWeekday(cal)) return null
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (nowMin < slot.minute || nowMin >= slot.lastMinute) return null
        val slotMs = slotMsOn(slot, now)
        if (lastCompleteAt >= slotMs) return null
        val triedToday = lastTry >= slotMs
        if (triedToday && tries >= MAX_TRIES_PER_DAY) return null
        val at = if (!triedToday) now
                 else lastTry + RETRY_GAP_MS - (if (firing) 2 * 60_000L else 0L)
        val endMs = slotMs + (slot.lastMinute - slot.minute) * 60_000L
        return if (at < endMs) maxOf(at, now) else null
    }

    /** The next regular slot for [market]: today's if still ahead, else the next weekday's. */
    internal fun nextSlotMs(market: String, now: Long): Long =
        nextSlotMs(SLOTS.first { it.market == market }, now)

    private fun nextSlotMs(slot: Slot, now: Long): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone(slot.zone))
        cal.timeInMillis = slotMsOn(slot, now)
        if (isWeekday(cal) && cal.timeInMillis > now) return cal.timeInMillis
        do {
            cal.add(Calendar.DAY_OF_MONTH, 1)
        } while (!isWeekday(cal))
        return cal.timeInMillis
    }

    private fun isWeekday(cal: Calendar): Boolean {
        val d = cal.get(Calendar.DAY_OF_WEEK)
        return d != Calendar.SATURDAY && d != Calendar.SUNDAY
    }
}

/**
 * Broadcast receiver that AlarmManager pokes at each market's slot (or retry).
 * Starts that market's ROBUST discovery if it is still owed, and re-arms.
 */
class AutoDiscoveryReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // No market on the intent = the India-only alarm an older install armed.
        val market = intent?.getStringExtra(DiscoveryAutoScheduler.EXTRA_MARKET) ?: "nifty500"
        val action = try { DiscoveryAutoScheduler.claimIfDue(context, market) }
                     catch (e: Exception) { Log.e("AutoDiscoveryRecv", "due check failed", e); null }
        // Re-arm before starting anything, so a crash in the scan can't break the chain.
        DiscoveryAutoScheduler.ensureScheduled(context)
        if (action == null) {
            Log.i("AutoDiscoveryRecv", "Auto-discovery alarm for $market — nothing owed today")
            return
        }
        Log.i("AutoDiscoveryRecv", "Auto-discovery alarm fired — starting ROBUST $market scan")
        // Foreground service start is allowed from a broadcast triggered by an
        // exact alarm (alarm-driven exemption on Android 12+).
        try {
            context.startForegroundService(ScanService.createIntent(context, action))
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

// ── German equity universe — USER-CURATED, VERIFIED list (103 names) ──
// This exact list was hand-picked by the user as their "DAX top ~100" and every
// symbol was verified against Yahoo on 2026-07-17: all 103 resolve to the
// correct company with a live price (e.g. PUM.DE → PUMA SE — the bare "PUM"
// without .DE is ProUroCare Medical, a US OTC penny stock; always keep the
// suffix).
//
// NO LONGER THE WHOLE STORY. This is now the CURATED HALF of the German
// universe — see [deScreenedUniverse], which unions it with the equities the
// broker actually lists on Xetra. Nothing should read this list directly any
// more: go through the union, or Discovery and the screeners drift apart again,
// which is the failure this list's own "single source of truth" note was
// written to prevent.
val DAX_SYMBOLS = listOf(
    "1U1.DE","ADN1.DE","ADS.DE","AFX.DE","AG1.DE","AIR.DE","AIXA.DE","ALV.DE",
    "AOF.DE","AT1.DE","BAS.DE","BAYN.DE","BC8.DE","BEI.DE","BMW.DE","BNR.DE",
    "BOSS.DE","BVB.DE","CBK.DE","CEC.DE","COK.DE","CON.DE","DB1.DE","DBK.DE",
    "DEZ.DE","DHER.DE","DHL.DE","DRW3.DE","DTE.DE","DTG.DE","DUE.DE","EKT.DE",
    "ELG.DE","ENR.DE","EOAN.DE","EVK.DE","EVT.DE","FNTN.DE","FRA.DE","FRE.DE",
    "FTK.DE","GFT.DE","GXI.DE","HAG.DE","HEI.DE","HEN3.DE","HFG.DE","HLAG.DE",
    "HNR1.DE","HYQ.DE","IFX.DE","INH.DE","IOS.DE","JEN.DE","JST.DE","KGX.DE",
    "KTN.DE","KWS.DE","LEG.DE","LHA.DE","LXS.DE","MBG.DE","MRK.DE","MTX.DE",
    "MUV2.DE","MUX.DE","NA9.DE","NB2.DE","NDX1.DE","NEM.DE","P911.DE","PAH3.DE",
    "PBB.DE","PNE3.DE","PUM.DE","QIA.DE","R3NK.DE","RAA.DE","RHM.DE","RWE.DE",
    "S92.DE","SAP.DE","SDF.DE","SGL.DE","SHL.DE","SIE.DE","SIX2.DE","SMHN.DE",
    "SRT3.DE","SY1.DE","SZG.DE","SZU.DE","TKA.DE","TMV.DE","TUI1.DE","UTDI.DE",
    "VBK.DE","VNA.DE","VOS.DE","VOW3.DE","WAF.DE","WCH.DE","ZAL.DE",
    // Re-added on top of the curated 103: stocks the user currently HOLDS on
    // T212, kept here so Discovery/screener coverage continues while held
    // (8TRA = Traton, TEG = TAG Immobilien, NDA = Aurubis).
    "8TRA.DE","TEG.DE","NDA.DE"
).distinct()

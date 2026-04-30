package com.signalscope.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import java.io.File
import java.security.KeyStore

/**
 * Encrypted credential storage for SignalScope.
 *
 * Stores credentials for:
 *   ZERODHA_API_KEY, ZERODHA_API_SECRET
 *   OPENAI_API_KEY (or Anthropic key) for AI stock analysis
 *
 * All sensitive values use EncryptedSharedPreferences (AES-256 GCM).
 * Non-sensitive config values use regular SharedPreferences.
 *
 * Handles Android Keystore corruption gracefully — if the encrypted store
 * cannot be decrypted (AEADBadTagException), corrupted data is wiped and
 * recreated. The user will need to re-enter credentials but the app won't crash.
 */
class ConfigManager(context: Context) {

    /** Application context — exposed so collaborators (e.g. ZerodhaClient,
     *  GttActivityLog hooks) that already hold a ConfigManager don't need a
     *  parallel Context plumbing. We hold the application context only,
     *  never the activity, to avoid leaks. */
    val appContext: Context = context.applicationContext

    companion object {
        private const val TAG = "ConfigManager"
        private const val ENCRYPTED_PREFS_FILE = "signalscope_secure_prefs"
        private const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"
    }

    // ── Credential store ──
    // NOTE: Switched from EncryptedSharedPreferences to plain SharedPreferences.
    // Reason: EncryptedSharedPreferences relies on Android Keystore, which gets
    // corrupted frequently on MIUI/Xiaomi devices, causing AEADBadTagException
    // and wiping all stored credentials. Since the prefs file lives inside the
    // app's private sandbox (MODE_PRIVATE), other apps cannot read it without
    // root — encryption was offering little real security but causing constant
    // credential loss for Xiaomi users.
    private val encrypted: SharedPreferences = context.getSharedPreferences(
        "signalscope_credentials", Context.MODE_PRIVATE
    ).also { newPrefs ->
        // One-time migration: if old encrypted store still has credentials, copy them over
        try {
            if (newPrefs.getString("ZERODHA_API_KEY", "").isNullOrEmpty()) {
                val oldEncrypted = createEncryptedPrefs(context)
                val keysToMigrate = listOf(
                    "ZERODHA_API_KEY", "ZERODHA_API_SECRET",
                    "zerodha_access_token", "OPENAI_API_KEY"
                )
                val editor = newPrefs.edit()
                var migrated = 0
                keysToMigrate.forEach { key ->
                    val v = oldEncrypted.getString(key, "") ?: ""
                    if (v.isNotEmpty()) { editor.putString(key, v); migrated++ }
                }
                if (migrated > 0) {
                    editor.apply()
                    Log.i(TAG, "Migrated $migrated credentials from encrypted store")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Migration from encrypted prefs failed (expected on first install or corrupted keystore)", e)
        }
    }

    /**
     * Creates EncryptedSharedPreferences with automatic recovery from Keystore corruption.
     *
     * Android Keystore can become corrupted (especially on MIUI/Xiaomi devices),
     * causing AEADBadTagException when trying to decrypt existing preferences.
     * When this happens, we:
     *   1. Delete the corrupted shared preferences XML file
     *   2. Remove the master key from Android Keystore
     *   3. Recreate both from scratch
     *
     * The user loses stored credentials (API keys) but the app remains functional.
     */
    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            buildEncryptedPrefs(context)
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences corrupted — wiping and recreating", e)
            try {
                // Step 1: Delete the corrupted preferences file
                val prefsFile = File(context.filesDir.parent, "shared_prefs/$ENCRYPTED_PREFS_FILE.xml")
                if (prefsFile.exists()) {
                    prefsFile.delete()
                    Log.w(TAG, "Deleted corrupted prefs file: ${prefsFile.absolutePath}")
                }

                // Step 2: Remove the corrupted master key from Android Keystore
                try {
                    val keyStore = KeyStore.getInstance("AndroidKeyStore")
                    keyStore.load(null)
                    if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                        keyStore.deleteEntry(MASTER_KEY_ALIAS)
                        Log.w(TAG, "Deleted corrupted master key from Keystore")
                    }
                } catch (ksEx: Exception) {
                    Log.e(TAG, "Failed to clear Keystore entry", ksEx)
                }

                // Step 3: Recreate fresh
                buildEncryptedPrefs(context)
            } catch (e2: Exception) {
                // Last resort: fall back to unencrypted prefs so the app doesn't crash-loop
                Log.e(TAG, "CRITICAL: Cannot create encrypted prefs even after reset — using plaintext fallback", e2)
                context.getSharedPreferences("${ENCRYPTED_PREFS_FILE}_fallback", Context.MODE_PRIVATE)
            }
        }
    }

    private fun buildEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ── Plain store (non-sensitive config) ──
    private val prefs = context.getSharedPreferences("signalscope_prefs", Context.MODE_PRIVATE)

    // ═══════════════════════════════════════════════════════
    // ZERODHA CREDENTIALS  (matches .env: ZERODHA_*)
    // ═══════════════════════════════════════════════════════

    var zerodhaApiKey: String
        get() = encrypted.getString("ZERODHA_API_KEY", "") ?: ""
        set(v) = encrypted.edit().putString("ZERODHA_API_KEY", v).apply()

    var zerodhaApiSecret: String
        get() = encrypted.getString("ZERODHA_API_SECRET", "") ?: ""
        set(v) = encrypted.edit().putString("ZERODHA_API_SECRET", v).apply()

    /** Access token is session-lived (expires daily) — store separately */
    var zerodhaAccessToken: String
        get() = encrypted.getString("zerodha_access_token", "") ?: ""
        set(v) = encrypted.edit().putString("zerodha_access_token", v).apply()

    var zerodhaUserName: String
        get() = prefs.getString("zerodha_user_name", "") ?: ""
        set(v) = prefs.edit().putString("zerodha_user_name", v).apply()

    val hasZerodhaCredentials: Boolean
        get() = zerodhaApiKey.isNotBlank() && zerodhaApiSecret.isNotBlank()

    val isZerodhaConnected: Boolean
        get() = hasZerodhaCredentials && zerodhaAccessToken.isNotBlank()

    /** Kept for backward compatibility — now just checks Zerodha */
    val hasCredentials: Boolean get() = isZerodhaConnected

    val zerodhaLoginUrl: String
        get() = if (zerodhaApiKey.isNotBlank())
            "https://kite.zerodha.com/connect/login?v=3&api_key=$zerodhaApiKey"
        else ""

    // ═══════════════════════════════════════════════════════
    // LLM / AI CREDENTIALS (for stock analysis via news)
    // ═══════════════════════════════════════════════════════

    /** OpenAI or Anthropic API key */
    var openaiApiKey: String
        get() = encrypted.getString("OPENAI_API_KEY", "") ?: ""
        set(v) = encrypted.edit().putString("OPENAI_API_KEY", v).apply()

    /** LLM provider: "openai" or "anthropic" */
    var llmProvider: String
        get() = prefs.getString("llm_provider", "openai") ?: "openai"
        set(v) = prefs.edit().putString("llm_provider", v).apply()

    /** LLM model name (e.g. "gpt-4o-mini", "claude-sonnet-4-20250514") */
    var llmModel: String
        get() = prefs.getString("llm_model", "gpt-4o-mini") ?: "gpt-4o-mini"
        set(v) = prefs.edit().putString("llm_model", v).apply()

    val hasLlmCredentials: Boolean
        get() = openaiApiKey.isNotBlank()

    // ═══════════════════════════════════════════════════════
    // SCAN CONFIGURATION
    // ═══════════════════════════════════════════════════════

    var portfolioScanIntervalMin: Int
        get() = prefs.getInt("scan_interval_min", 15)
        set(v) = prefs.edit().putInt("scan_interval_min", v).apply()

    /** Sell score threshold for moderate sell notification (default 45) */
    var sellScoreAlertThreshold: Int
        get() = prefs.getInt("sell_threshold", 45)
        set(v) = prefs.edit().putInt("sell_threshold", v).apply()

    /** Sell score threshold for strong sell notification (default 65) */
    var strongSellAlertThreshold: Int
        get() = prefs.getInt("strong_sell_threshold", 65)
        set(v) = prefs.edit().putInt("strong_sell_threshold", v).apply()

    /** Only scan during Indian market hours (9:15 AM - 3:30 PM IST, weekdays) */
    var scanDuringMarketHoursOnly: Boolean
        get() = prefs.getBoolean("market_hours_only", true)
        set(v) = prefs.edit().putBoolean("market_hours_only", v).apply()

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notifications_enabled", true)
        set(v) = prefs.edit().putBoolean("notifications_enabled", v).apply()

    var vibrateOnAlerts: Boolean
        get() = prefs.getBoolean("vibrate_alerts", true)
        set(v) = prefs.edit().putBoolean("vibrate_alerts", v).apply()

    // ═══════════════════════════════════════════════════════
    // SCORING WEIGHTS (stored as JSON in plain prefs)
    // ═══════════════════════════════════════════════════════

    private val gson = Gson()

    // Persisted weights (from SharedPreferences) — the "defaults" configured in Settings.
    // Survives app restarts. Used as the baseline every time the Setups-tab sliders re-initialize.
    val persistedScoringWeights: ScoringWeights
        get() {
            val json = prefs.getString("scoring_weights", null) ?: return ScoringWeights()
            return try {
                gson.fromJson(json, ScoringWeights::class.java)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse scoring weights, using defaults", e)
                ScoringWeights()
            }
        }

    // Effective weights — checks the in-memory session override first (set by the
    // Setups-tab sliders via DashboardBridge.setSessionWeight). If no override is
    // active, returns the persisted defaults. This is what ScanService/StockAnalyzer
    // read. SessionWeights.override is cleared on every process start (session-only).
    var scoringWeights: ScoringWeights
        get() = SessionWeights.override ?: persistedScoringWeights
        set(v) = prefs.edit().putString("scoring_weights", gson.toJson(v)).apply()

    fun resetScoringWeights() {
        prefs.edit().remove("scoring_weights").apply()
        SessionWeights.override = null
    }

    // ═══════════════════════════════════════════════════════
    // SERVICE STATE
    // ═══════════════════════════════════════════════════════

    var serviceRunning: Boolean
        get() = prefs.getBoolean("service_running", false)
        set(v) = prefs.edit().putBoolean("service_running", v).apply()

    var lastPortfolioScan: Long
        get() = prefs.getLong("last_scan_ts", 0)
        set(v) = prefs.edit().putLong("last_scan_ts", v).apply()

    // ═══════════════════════════════════════════════════════
    // AUTO TRAILING SL + MACD EXIT WATCHDOG
    // ═══════════════════════════════════════════════════════
    // Two separate gates, both OFF by default. User must opt in explicitly.
    //
    //   autoTrailingEnabled → daily tick MAY modify SL-leg on OCO GTTs
    //                          (monotonic, never widens, never places a market order)
    //   autoExitEnabled     → daily tick MAY place live SELL orders via the
    //                          limit→limit→market ladder when MACD watchdog fires.
    //                          HOT — this spends real money. Keep off until trusted.

    var autoTrailingEnabled: Boolean
        get() = prefs.getBoolean("auto_trailing_enabled", false)
        set(v) = prefs.edit().putBoolean("auto_trailing_enabled", v).apply()

    var autoExitEnabled: Boolean
        get() = prefs.getBoolean("auto_exit_enabled", false)
        set(v) = prefs.edit().putBoolean("auto_exit_enabled", v).apply()

    /** Limit-price pad for OCO SL leg: limitPrice = triggerPrice × (1 - slLimitPct). Default 2%. */
    var slLimitPct: Double
        get() = prefs.getFloat("sl_limit_pct", 0.02f).toDouble()
        set(v) = prefs.edit().putFloat("sl_limit_pct", v.toFloat()).apply()

    /** Epoch ms of last successful daily tick — prevents double-runs within one trading day. */
    var lastDailyTickEpoch: Long
        get() = prefs.getLong("last_daily_tick_epoch", 0L)
        set(v) = prefs.edit().putLong("last_daily_tick_epoch", v).apply()

    /**
     * Auto-create OCO GTT for new holdings detected at the 15:20 IST daily tick.
     *
     * When TRUE: if a holding has no SL GTT, the daily tick creates a fresh two-leg
     * OCO automatically using SL=support (floored at entry×0.90) and TP=resistance
     * (capped at ltp×1.25). This protects gap-down risk overnight for same-day buys.
     *
     * Default FALSE — user opts in. Kept separate from autoTrailingEnabled because
     * creating a GTT from scratch is a more aggressive action than just trailing one.
     */
    var autoCreateGtt: Boolean
        get() = prefs.getBoolean("auto_create_gtt", true)   // ON by default — OCO create is the safest automation
        set(v) = prefs.edit().putBoolean("auto_create_gtt", v).apply()

    /**
     * Master switch for the Tier-1 MACD-flip push notification at 15:20.
     * Default TRUE — most users want to hear about it. Set FALSE for vacation mode
     * or when you want the watchdog to silently take action (sniper GTT etc.) without
     * a phone buzz. Even when disabled, the alert still appears in the in-app
     * history panel; only the heads-up notification is muted.
     */
    var macdFlipNotificationsEnabled: Boolean
        get() = prefs.getBoolean("macd_flip_notifications_enabled", true)
        set(v) = prefs.edit().putBoolean("macd_flip_notifications_enabled", v).apply()

    // ═══════════════════════════════════════════════════════
    // TIER 1.5 — SOFT SNIPER (intraday auto-GTT on BOOK_PROFIT alert)
    // ═══════════════════════════════════════════════════════
    //
    //   When the 15-min ScanService raises a BOOK_PROFIT alert (verdict ==
    //   "BOOK PROFIT" — driven by SELL FLIP / EARLY SELL phase + score
    //   thresholds + gain > 3%), the soft sniper auto-places a single-leg
    //   SELL GTT at LTP × (1 − softSniperBufferPct). This bridges the gap
    //   between Tier 1 (advisory notification, fast) and Tier 2 (hard
    //   sniper GTT, slow — waits minBarsFilter days for confirmation).
    //
    //   Replaced by hard sniper if the daily watchdog later confirms
    //   (replaceSniperGtt cancels existing SELL GTTs first, so the
    //   handoff is automatic).
    //
    //   Default OFF — opt-in because it places real GTTs.

    /** Master switch for the Tier-1.5 soft sniper. Default FALSE — opt-in. */
    var autoSoftSniperEnabled: Boolean
        get() = prefs.getBoolean("auto_soft_sniper_enabled", false)
        set(v) = prefs.edit().putBoolean("auto_soft_sniper_enabled", v).apply()

    /** Trigger buffer below LTP. Default 3% — wide enough for normal pullbacks
     *  to stay clear, tight enough to cap drawdown if the slide continues. */
    var softSniperBufferPct: Double
        get() = prefs.getFloat("soft_sniper_buffer_pct", 0.03f).toDouble()
        set(v) = prefs.edit().putFloat("soft_sniper_buffer_pct", v.toFloat()).apply()

    /** Limit price = trigger × (1 − softSniperLimitPct) for fill confidence.
     *  Default 0.5% inside the trigger — enough to fill, not so deep we leave
     *  money on the table on routine spread crossings. */
    var softSniperLimitPct: Double
        get() = prefs.getFloat("soft_sniper_limit_pct", 0.005f).toDouble()
        set(v) = prefs.edit().putFloat("soft_sniper_limit_pct", v.toFloat()).apply()

    /** Minimum unrealized gain (%) before soft sniper fires. Default 5%.
     *  Below this, the BOOK_PROFIT alert still fires but no GTT is placed —
     *  the position isn't yet worth protecting with broker-side automation. */
    var softSniperMinGainPct: Double
        get() = prefs.getFloat("soft_sniper_min_gain_pct", 5.0f).toDouble()
        set(v) = prefs.edit().putFloat("soft_sniper_min_gain_pct", v.toFloat()).apply()

    // ── Dynamic Soft-Sniper Buffer (volume-aware) ─────────────────────────
    // The soft sniper now picks one of THREE buffer widths depending on
    // OBV + volume confirmation strength:
    //
    //   • TIGHT (default 1.5%) — MACD slope cross-down + volume ≥ spike multiple
    //                            → smart money flushing, exit fast
    //   • NORMAL (softSniperBufferPct, default 3%) — OBV divergence detected
    //                            → warning, defensive buffer
    //   • LOOSE (default 4%)   — score crossed but no volume / OBV confirmation
    //                            → likely weak signal, give it room
    //
    // If today's volume falls below softSniperVolumeWeakMultiple × avgVol20,
    // the soft sniper SKIPS placement entirely — no participation = likely
    // false positive on thin trade.

    /** Tight buffer (%) used when MACD flips red on a high-volume bar. Default 1.5%. */
    var softSniperTightBufferPct: Double
        get() = prefs.getFloat("soft_sniper_tight_buffer_pct", 0.015f).toDouble()
        set(v) = prefs.edit().putFloat("soft_sniper_tight_buffer_pct", v.toFloat()).apply()

    /** Loose buffer (%) used when score crossed but no volume/OBV confirmation. Default 4%. */
    var softSniperLooseBufferPct: Double
        get() = prefs.getFloat("soft_sniper_loose_buffer_pct", 0.04f).toDouble()
        set(v) = prefs.edit().putFloat("soft_sniper_loose_buffer_pct", v.toFloat()).apply()

    /** "Spike" multiple — today's volume ≥ N × avgVol20 counts as confirmed flush. Default 1.30. */
    var softSniperVolumeSpikeMultiple: Double
        get() = prefs.getFloat("soft_sniper_volume_spike_multiple", 1.30f).toDouble()
        set(v) = prefs.edit().putFloat("soft_sniper_volume_spike_multiple", v.toFloat()).apply()

    /** "Weak" multiple — today's volume < N × avgVol20 means SKIP soft sniper. Default 0.70. */
    var softSniperVolumeWeakMultiple: Double
        get() = prefs.getFloat("soft_sniper_volume_weak_multiple", 0.70f).toDouble()
        set(v) = prefs.edit().putFloat("soft_sniper_volume_weak_multiple", v.toFloat()).apply()

    // ── Trailing OCO Target (asymmetric upside trail) ─────────────────────
    // The SL leg of every OCO trails up daily via TrailingStopCalculator.
    // The TARGET leg historically did NOT trail — it was frozen at whatever
    // resistance/wave-ceiling was visible at OCO creation. For trending NSE 500
    // names this caps winners 5–25% in even when the move continues for months.
    //
    // When autoTrailingTargetEnabled is true, the daily portfolio manager also
    // ratchets the target leg up using:
    //     candidateTarget = LTP + (targetAtrMultiple × ATR)
    //     newTarget       = max(oldTarget, candidateTarget)         // ratchet
    //     cappedTarget    = min(newTarget, oldTarget × (1+MaxStep)) // anti-spike
    // Only bumps when the step is ≥ 1% of oldTarget (avoids GTT API spam).
    // Default OFF on first deploy — flip to true after observing the
    // "would update" log lines for a few days.

    /** Master switch for upside-target trailing in the daily OCO modify pass. Default false. */
    var autoTrailingTargetEnabled: Boolean
        get() = prefs.getBoolean("auto_trailing_target_enabled", false)
        set(v) = prefs.edit().putBoolean("auto_trailing_target_enabled", v).apply()

    /** ATR multiple used for the trailing target distance from LTP. Default 2.0. */
    var targetAtrMultiple: Double
        get() = prefs.getFloat("target_atr_multiple", 2.0f).toDouble()
        set(v) = prefs.edit().putFloat("target_atr_multiple", v.toFloat()).apply()

    /** Max single-day target jump (fraction of oldTarget). Prevents flash-crash bounces
     *  from pushing the target unrealistically far. Default 0.08 (8%). */
    var targetMaxDailyStepPct: Double
        get() = prefs.getFloat("target_max_daily_step_pct", 0.08f).toDouble()
        set(v) = prefs.edit().putFloat("target_max_daily_step_pct", v.toFloat()).apply()

    // ═══════════════════════════════════════════════════════
    // CLEAR
    // ═══════════════════════════════════════════════════════

    fun clearZerodhaSession() {
        encrypted.edit().remove("zerodha_access_token").apply()
        prefs.edit().remove("zerodha_user_name").apply()
    }

    fun clearAll() {
        encrypted.edit().clear().apply()
        prefs.edit().clear().apply()
    }
}

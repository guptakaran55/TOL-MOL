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
                    "zerodha_access_token", "OPENAI_API_KEY",
                    "BINANCE_API_KEY", "BINANCE_API_SECRET"
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

    /**
     * The last NSE universe successfully downloaded from the index CSV.
     *
     * The download is a network call and the universe is wanted on the main
     * thread — when a card renders, when an algo is created. Keeping the last
     * good copy means those callers get the CURRENT index rather than the
     * embedded fallback, without any of them having to fetch.
     *
     * Blank until a scan has run once, which is the honest state: better to
     * hand back the embedded list and say so than to block the UI on a
     * multi-second request.
     */
    var nseUniverseCached: String
        get() = prefs.getString("nse_universe_cached", "") ?: ""
        set(v) = prefs.edit().putString("nse_universe_cached", v).apply()

    var nseUniverseCachedAt: Long
        get() = prefs.getLong("nse_universe_cached_at", 0L)
        set(v) = prefs.edit().putLong("nse_universe_cached_at", v).apply()

    // ── FLAT PER-ORDER FEE, PER SPACE ─────────────────────────────────────
    // The percentage slippage constant models the SPREAD, which scales with the
    // position. Brokerage, exchange charges and stamp duty do not: they are a
    // fixed cash amount per order, and a fixed amount is a completely different
    // shape of cost. On a large position it disappears; on a small one it can
    // exceed the edge entirely. A strategy that looks profitable at €2,000 a
    // trade and loses money at €80 is not visible at all through a percentage.
    //
    // Charged PER ORDER, so a round trip pays it twice — one on the way in, one
    // on the way out.
    //
    // Kept in the space's own currency because a fee is cash: "5" is nothing in
    // rupees and material in euros. Defaults are 0 everywhere, so nothing
    // changes until these are deliberately set — an app that quietly started
    // charging fees would invalidate every number already recorded.

    /** Flat fee per order for US algos, in USD. */
    var flatFeeUs: Double
        get() = prefs.getFloat("flat_fee_us", 0f).toDouble()
        set(v) = prefs.edit().putFloat("flat_fee_us", v.toFloat().coerceAtLeast(0f)).apply()

    /** Flat fee per order for German algos, in EUR. */
    var flatFeeDe: Double
        get() = prefs.getFloat("flat_fee_de", 0f).toDouble()
        set(v) = prefs.edit().putFloat("flat_fee_de", v.toFloat().coerceAtLeast(0f)).apply()

    /** Flat fee per order for Indian algos, in INR. */
    var flatFeeIn: Double
        get() = prefs.getFloat("flat_fee_in", 0f).toDouble()
        set(v) = prefs.edit().putFloat("flat_fee_in", v.toFloat().coerceAtLeast(0f)).apply()

    /** Flat fee per order for crypto algos, in USDC. */
    var flatFeeBinance: Double
        get() = prefs.getFloat("flat_fee_binance", 0f).toDouble()
        set(v) = prefs.edit().putFloat("flat_fee_binance", v.toFloat().coerceAtLeast(0f)).apply()

    /** The flat per-order fee that applies to a namespace, in that space's currency. */
    fun flatFeeFor(ns: String): Double =
        when (com.signalscope.app.trading.Spaces.baseOf(ns)) {
            "binance" -> flatFeeBinance
            "de" -> flatFeeDe
            "in" -> flatFeeIn
            else -> flatFeeUs
        }

    /**
     * The flat fee expressed as a percentage of one round trip, so the engines —
     * which all work in percentage returns — can charge it alongside slippage.
     *
     * Two orders per round trip, both against the money actually committed. A
     * position value of zero or less yields 0 rather than infinity: a fee cannot
     * be charged against a position that was never taken, and returning a huge
     * number there would silently zero out a strategy's entire return.
     */
    fun flatFeeRoundTripPct(ns: String, positionValue: Double): Double {
        val fee = flatFeeFor(ns)
        if (fee <= 0.0 || positionValue <= 0.0) return 0.0
        return (2.0 * fee / positionValue) * 100.0
    }

    /**
     * Last known Trading 212 free cash, cached by the reconciliation pass.
     *
     * The commitment warning has to render on every repaint, and T212 rate-limits
     * account reads hard — so the number is fetched by the pass that is already
     * making network calls and simply read from here. A slightly stale balance is
     * the right trade: the warning is about a shortfall of tens of euros building
     * up over hours, not about the last cent.
     */
    var t212FreeFundsCached: Double
        get() = prefs.getFloat("t212_free_funds_cached", -1f).toDouble()
        set(v) = prefs.edit().putFloat("t212_free_funds_cached", v.toFloat()).apply()

    var t212FreeFundsAt: Long
        get() = prefs.getLong("t212_free_funds_at", 0L)
        set(v) = prefs.edit().putLong("t212_free_funds_at", v).apply()

    /**
     * Whether PAPER trade notifications are sent at all.
     *
     * Six algos across four spaces produce a steady drip of these — each one
     * true, none of them urgent, and together enough to bury the notifications
     * that involve real money. Turning them off silences the paper channel ONLY;
     * live fills, unfilled ladders and position mismatches are unaffected,
     * because those are the ones you cannot afford to have learned to ignore.
     */
    var paperTradeNotifications: Boolean
        get() = prefs.getBoolean("paper_trade_notifications", true)
        set(v) = prefs.edit().putBoolean("paper_trade_notifications", v).apply()

    // ── US PRICE SOURCE ───────────────────────────────────────────────────
    // Yahoo's US quotes are redistributed and can lag badly. Finnhub streams
    // actual trades. Same two-part shape as the India switch: a key, and a
    // toggle that only does anything once the key is present.
    //
    // Stored in the ENCRYPTED prefs, like every other broker credential — a
    // data key is still a key, and a leaked one is someone else's quota.

    var finnhubApiKey: String
        get() = encrypted.getString("FINNHUB_API_KEY", "") ?: ""
        set(v) = encrypted.edit().putString("FINNHUB_API_KEY", v.trim()).apply()

    val hasFinnhubKey: Boolean get() = finnhubApiKey.isNotBlank()

    /** Live tick source for the US space: "yahoo" (default) or "finnhub". */
    var usLivePriceSource: String
        get() = prefs.getString("us_live_price_source", "yahoo") ?: "yahoo"
        set(v) = prefs.edit().putString("us_live_price_source",
            if (v == "finnhub") "finnhub" else "yahoo").apply()

    val useFinnhubLiveTicks: Boolean
        get() = usLivePriceSource == "finnhub" && hasFinnhubKey

    // ── ZERODHA ALGO ORDERS ───────────────────────────────────────────────
    // Same two-key shape as the Trading 212 side: a master freeze that blocks
    // everything, and a mirror toggle that says algo orders are wanted at all.
    // Both are needed, because they answer different questions — "is the system
    // armed" and "is it allowed to trade right now" — and collapsing them into
    // one switch removes the ability to stop everything without disarming each
    // algo individually.

    /** Master freeze for Indian algo orders. Defaults ON. Never flipped in code. */
    var zerodhaLiveFrozen: Boolean
        get() = prefs.getBoolean("zerodha_live_frozen", true)
        set(v) = prefs.edit().putBoolean("zerodha_live_frozen", v).apply()

    /** Whether algos may mirror into real Zerodha orders at all. Defaults OFF. */
    var zerodhaLiveMirrorEnabled: Boolean
        get() = prefs.getBoolean("zerodha_live_mirror_enabled", false)
        set(v) = prefs.edit().putBoolean("zerodha_live_mirror_enabled", v).apply()

    val canMirrorZerodhaLive: Boolean
        get() = isZerodhaConnected && zerodhaLiveMirrorEnabled && !zerodhaLiveFrozen

    // ── INDIA PRICE SOURCE ────────────────────────────────────────────────
    // Two separate switches on purpose, because they carry different risk and
    // are paid for differently.
    //
    // LIVE ticks come with the base Kite Connect subscription and are the same
    // book the orders reach, so they are strictly better than a redistributed
    // feed once the socket works.
    //
    // HISTORICAL candles are a SEPARATE paid add-on. A subscription taken out
    // for a few weeks of backtest data will lapse, and when it does, every
    // Indian algo would lose its bars mid-session. So the historical switch is
    // advisory rather than binding: the app asks Kite first and silently uses
    // Yahoo when Kite refuses — see [zerodhaHistoryLastRefusal].
    //
    // Both default to Yahoo. A price feed is not something to switch under
    // someone by shipping a new default.

    /** Live tick source for the India space: "yahoo" (default) or "zerodha". */
    var indiaLivePriceSource: String
        get() = prefs.getString("india_live_price_source", "yahoo") ?: "yahoo"
        set(v) = prefs.edit().putString("india_live_price_source",
            if (v == "zerodha") "zerodha" else "yahoo").apply()

    val useZerodhaLiveTicks: Boolean
        get() = indiaLivePriceSource == "zerodha" && isZerodhaConnected

    /** Candle source for the India space: "yahoo" (default) or "zerodha". */
    var indiaHistorySource: String
        get() = prefs.getString("india_history_source", "yahoo") ?: "yahoo"
        set(v) = prefs.edit().putString("india_history_source",
            if (v == "zerodha") "zerodha" else "yahoo").apply()

    val useZerodhaHistory: Boolean
        get() = indiaHistorySource == "zerodha" && isZerodhaConnected

    /**
     * When Kite last refused a historical request, and why. Written by the
     * fetcher on a 403 so the settings screen can say "your add-on has lapsed,
     * candles are coming from Yahoo" instead of leaving a switch that claims to
     * be on while nothing behind it works.
     */
    var zerodhaHistoryLastRefusal: String
        get() = prefs.getString("zerodha_history_last_refusal", "") ?: ""
        set(v) = prefs.edit().putString("zerodha_history_last_refusal", v).apply()

    var zerodhaHistoryLastRefusalAt: Long
        get() = prefs.getLong("zerodha_history_last_refusal_at", 0L)
        set(v) = prefs.edit().putLong("zerodha_history_last_refusal_at", v).apply()

    // ═══════════════════════════════════════════════════════
    // TRADING 212 CREDENTIALS  (live US/DE broker)
    // ═══════════════════════════════════════════════════════
    //
    // NOTE on auth model:
    //   T212 uses HTTP Basic auth with key + secret:
    //   Authorization: Basic base64(key:secret)
    //
    // Base URL is always the live endpoint:
    //   https://live.trading212.com/api/v0

    var t212ApiKey: String
        get() = encrypted.getString("T212_API_KEY", "") ?: ""
        set(v) = encrypted.edit().putString("T212_API_KEY", v).apply()

    var t212ApiSecret: String
        get() = encrypted.getString("T212_API_SECRET", "") ?: ""
        set(v) = encrypted.edit().putString("T212_API_SECRET", v).apply()

    /** Always live — demo endpoint removed. The user only ever uses the live
     *  T212 account (read+order); demo endpoint was only ever a footgun (it
     *  rejected live keys with confusing 401s). Field still exists for any
     *  future code that reads it; always returns true. */
    val t212UseLive: Boolean get() = true

    val t212BaseUrl: String
        get() = "https://live.trading212.com/api/v0"

    /** Legacy compatibility setting. Trading 212 order flows are always live;
     *  older stored values are ignored and cleared when written. */
    var t212SimulateOnly: Boolean
        get() = false
        set(v) = prefs.edit().putBoolean("t212_simulate_only", false).apply()

    /** T212 uses HTTP Basic auth — Authorization: Basic base64(key:secret).
     *  Both fields are required; the API rejects empty-secret basic auth
     *  with 401. Verified against working Python reference script. */
    val hasT212Credentials: Boolean
        get() = t212ApiKey.isNotBlank() && t212ApiSecret.isNotBlank()

    // ── T212 LIVE MIRROR (US space automated lab) ──────────────────────────
    // OFF by default. When on, NEW paper BUY/SELL decisions on the NEWEST closed
    // bar are mirrored into real T212 MARKET orders (a deliberately tiny size),
    // recorded in a separate live ledger. Paper keeps running regardless.
    var t212LiveMirrorEnabled: Boolean
        get() = prefs.getBoolean("t212_live_mirror_enabled", false)
        set(v) = prefs.edit().putBoolean("t212_live_mirror_enabled", v).apply()

    /** Account-currency amount spent on each live mirrored T212 BUY. Hard-capped
     *  small — this is a real-money test size, not a position sizer. */
    var t212LivePerTradeAmount: Double
        get() = prefs.getFloat("t212_live_per_trade_amount", 20.0f).toDouble()
        set(v) = prefs.edit().putFloat(
            "t212_live_per_trade_amount",
            v.coerceIn(1.0, 3000.0).toFloat()
        ).apply()

    /**
     * MASTER FREEZE over T212 live entries — the switch that used to be a
     * hardcoded `const val LIVE_FROZEN = true` in T212LiveMirror.
     *
     * Frozen by default and deliberately separate from [t212LiveMirrorEnabled]:
     * that flag says "this account may mirror", this one says "the execution layer
     * is trusted enough to send an order". Both, plus a per-algo live flag, plus
     * the per-algo allowlist, must agree before any real stock order is placed.
     * Four independent gates is not paranoia when one wrong tick is a real loss.
     *
     * Prove the chain with the Live Test Bench before turning this off — the bench
     * runs the same executor with the same reconciliation, one manual trade at a
     * time, and it exists precisely so this switch is not the first real order.
     */
    var t212LiveFrozen: Boolean
        get() = prefs.getBoolean("t212_live_frozen", true)
        set(v) = prefs.edit().putBoolean("t212_live_frozen", v).apply()

    val canMirrorT212Live: Boolean
        get() = hasT212Credentials && t212LiveMirrorEnabled && !t212LiveFrozen

    // ═══════════════════════════════════════════════════════
    // BINANCE CREDENTIALS + LIVE MIRROR  (crypto space)
    // ═══════════════════════════════════════════════════════
    //
    // Binance Strategy Lab always keeps its paper ledger running. When
    // binanceLiveMirrorEnabled is true, NEW Binance paper BUY/SELL decisions
    // are mirrored into live Spot MARKET orders and written to a separate live
    // mirror ledger. No margin/futures/withdrawals are used.

    var binanceApiKey: String
        get() = encrypted.getString("BINANCE_API_KEY", "") ?: ""
        set(v) = encrypted.edit().putString("BINANCE_API_KEY", v).apply()

    var binanceApiSecret: String
        get() = encrypted.getString("BINANCE_API_SECRET", "") ?: ""
        set(v) = encrypted.edit().putString("BINANCE_API_SECRET", v).apply()

    val hasBinanceCredentials: Boolean
        get() = binanceApiKey.isNotBlank() && binanceApiSecret.isNotBlank()

    var binanceLiveMirrorEnabled: Boolean
        get() = prefs.getBoolean("binance_live_mirror_enabled", false)
        set(v) = prefs.edit().putBoolean("binance_live_mirror_enabled", v).apply()

    /** Quote amount spent on each live mirrored BUY. Kept separate from the
     * paper notional so the user can shadow the exact same signals with a
     * deliberately small real-money size. */
    var binanceLiveQuoteOrderSize: Double
        get() = prefs.getFloat("binance_live_quote_order_size", 10.0f).toDouble()
        set(v) = prefs.edit().putFloat(
            "binance_live_quote_order_size",
            v.coerceIn(5.0, 10_000.0).toFloat()
        ).apply()

    /** Live sizing model for Binance mirror BUYs:
     *  "FIXED"  — every entry uses binanceLiveQuoteOrderSize (legacy behaviour).
     *  "BUDGET" — a total budget split equally across enrolled symbols, then
     *             compounded per-symbol by the reinvest engine's balance. */
    var binanceLiveSizingMode: String
        get() = (prefs.getString("binance_live_sizing_mode", "FIXED") ?: "FIXED").uppercase()
            .let { if (it == "BUDGET") "BUDGET" else "FIXED" }
        set(v) = prefs.edit().putString(
            "binance_live_sizing_mode",
            if (v.uppercase() == "BUDGET") "BUDGET" else "FIXED"
        ).apply()

    /** Total live budget (USDT) split equally across the enrolled Binance basket
     *  when binanceLiveSizingMode == "BUDGET". 0 disables budget sizing (falls
     *  back to the fixed per-trade size). */
    var binanceLiveBudget: Double
        get() = prefs.getFloat("binance_live_budget", 0.0f).toDouble()
        set(v) = prefs.edit().putFloat(
            "binance_live_budget",
            v.coerceIn(0.0, 1_000_000.0).toFloat()
        ).apply()

    /**
     * Symbols the live mirror is allowed to place REAL orders for.
     * Comma-separated; blank means "no restriction" (mirror every enrolled
     * symbol — the old behaviour).
     *
     * Why this exists: the paper lab and the live mirror had no separation, so
     * enrolling 16 coins for paper research also armed 16 coins for real money.
     * Every paper entry attempted a real buy; the only thing stopping unwanted
     * purchases was running out of USDC (which surfaced as "Insufficient
     * balance" errors). Top the account up and it WOULD have bought all of
     * them. Defaults to BTC/ETH so the safe direction is the default —
     * research widely on paper, trade narrowly with real money.
     */
    var binanceLiveAllowlist: String
        get() = prefs.getString("binance_live_allowlist", "BTCUSDC,ETHUSDC") ?: "BTCUSDC,ETHUSDC"
        set(v) = prefs.edit().putString("binance_live_allowlist", v.trim().uppercase()).apply()

    /** True when [sym] may receive REAL orders. Blank allowlist = allow all. */
    fun binanceLiveAllows(sym: String): Boolean {
        val list = binanceLiveAllowlist.split(',', ' ', ';')
            .map { it.trim().uppercase() }.filter { it.isNotBlank() }
        return list.isEmpty() || list.contains(sym.trim().uppercase())
    }

    val canMirrorBinanceLive: Boolean
        get() = hasBinanceCredentials && binanceLiveMirrorEnabled

    // ═══════════════════════════════════════════════════════
    // SPACE / WORKSPACE  (which broker space the UI is currently in)
    // ═══════════════════════════════════════════════════════

    /** Current workspace: "IN" (Zerodha / NSE), "US" (Trading 212 / NASDAQ),
     *  or "DE" (Trading 212 / Xetra).
     *  Persisted across app restarts so the user lands back where they left. */
    var currentSpace: String
        get() {
            val s = (prefs.getString("current_space", "IN") ?: "IN").uppercase()
            return if (s == "IN" || s == "US" || s == "DE" || s == "BN") s else "IN"
        }
        set(v) {
            val s = v.uppercase()
            if (s == "IN" || s == "US" || s == "DE" || s == "BN") {
                prefs.edit().putString("current_space", s).apply()
            }
        }

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

    /** Universal master switch for the Yahoo live-price WebSocket overlay. */
    var livePriceStreamingEnabled: Boolean
        get() = prefs.getBoolean("live_price_streaming_enabled", false)
        set(v) = prefs.edit().putBoolean("live_price_streaming_enabled", v).apply()

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
                val parsed = gson.fromJson(json, ScoringWeights::class.java)
                val obj = com.google.gson.JsonParser.parseString(json).asJsonObject
                val d = ScoringWeights()
                var fixed = parsed
                if (!obj.has("setupMacdLowPctMin")) {
                    fixed = fixed.copy(setupMacdLowPctMin = d.setupMacdLowPctMin)
                }
                if (!obj.has("setupMacdPctlMax")) {
                    fixed = fixed.copy(setupMacdPctlMax = d.setupMacdPctlMax)
                }
                if (!obj.has("pullbackStrongThreshold")) {
                    fixed = fixed.copy(
                        pullbackStrongThreshold = fixed.cherryStrongThreshold,
                        pullbackModerateThreshold = fixed.cherryModerateThreshold
                    )
                }
                if (!obj.has("momentumTrendStackPts")) {
                    fixed = fixed.copy(
                        momentumTrendStackPts = d.momentumTrendStackPts,
                        momentumMacdPts = d.momentumMacdPts,
                        momentumEma21SlopePts = d.momentumEma21SlopePts,
                        momentumNotOverextendedPts = d.momentumNotOverextendedPts,
                        momentumRsiRoomPts = d.momentumRsiRoomPts,
                        momentumObvPts = d.momentumObvPts,
                        momentumStrongThreshold = d.momentumStrongThreshold,
                        momentumModerateThreshold = d.momentumModerateThreshold
                    )
                }
                // v3 migration — when a stored config predates the long-term
                // momentum overhaul, the 5 new buckets are missing AND the old
                // short-term weights are still at their pre-overhaul values
                // (~30/25/15/15/10/5 = 100 pts). We rewrite ALL momentum
                // weights to the new defaults so the user sees the intended
                // long-term-anchored mix instead of a weird 130-pt total.
                if (!obj.has("momentumTrendPersistencePts")) {
                    fixed = fixed.copy(
                        // long-term primary (NEW)
                        momentumTrendPersistencePts = d.momentumTrendPersistencePts,
                        momentumEmaCascadePts = d.momentumEmaCascadePts,
                        momentumDrawdownPts = d.momentumDrawdownPts,
                        momentumLongReturnPts = d.momentumLongReturnPts,
                        momentumSharpePts = d.momentumSharpePts,
                        // short-term confirmation (REDUCED to v3 defaults)
                        momentumTrendStackPts = d.momentumTrendStackPts,
                        momentumMacdPts = d.momentumMacdPts,
                        momentumEma21SlopePts = d.momentumEma21SlopePts,
                        momentumNotOverextendedPts = d.momentumNotOverextendedPts,
                        momentumRsiRoomPts = d.momentumRsiRoomPts,
                        momentumObvPts = d.momentumObvPts
                    )
                }
                if (!obj.has("valueRevenueGrowthHighPts")) {
                    fixed = fixed.copy(
                        valueRevenueGrowthHighPts = d.valueRevenueGrowthHighPts,
                        valueRevenueGrowthMidPts = d.valueRevenueGrowthMidPts,
                        valueRevenueGrowthPositivePts = d.valueRevenueGrowthPositivePts,
                        valueRevenueGrowthHighThreshold = d.valueRevenueGrowthHighThreshold,
                        valueRevenueGrowthMidThreshold = d.valueRevenueGrowthMidThreshold,
                        valueEarningsGrowthHighPts = d.valueEarningsGrowthHighPts,
                        valueEarningsGrowthMidPts = d.valueEarningsGrowthMidPts,
                        valueEarningsGrowthPositivePts = d.valueEarningsGrowthPositivePts,
                        valueEarningsGrowthHighThreshold = d.valueEarningsGrowthHighThreshold,
                        valueEarningsGrowthMidThreshold = d.valueEarningsGrowthMidThreshold,
                        valueGrossMarginPts = d.valueGrossMarginPts,
                        valueGrossMarginThreshold = d.valueGrossMarginThreshold,
                        valueProfitMarginGrowthPts = d.valueProfitMarginGrowthPts,
                        valueProfitMarginGrowthThreshold = d.valueProfitMarginGrowthThreshold,
                        valueRevenueGrowthPenaltyThreshold = d.valueRevenueGrowthPenaltyThreshold,
                        valueRevenueGrowthPenaltyPts = d.valueRevenueGrowthPenaltyPts,
                        valueEarningsGrowthPenaltyThreshold = d.valueEarningsGrowthPenaltyThreshold,
                        valueEarningsGrowthPenaltyPts = d.valueEarningsGrowthPenaltyPts
                    )
                }
                if (kotlin.math.abs(fixed.minSlopeMagnitude) <= 5.0 && kotlin.math.abs(fixed.goldenBuyMaxSlope) <= 5.0) {
                    fixed = fixed.copy(
                        minSlopeMagnitude = d.minSlopeMagnitude,
                        goldenBuyMaxSlope = d.goldenBuyMaxSlope
                    )
                }
                fixed
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
        set(v) {
            prefs.edit().putString("scoring_weights", gson.toJson(v)).apply()
            // Saving new Settings defaults should take effect immediately rather than
            // leaving an older Setups-tab session override in control until restart.
            SessionWeights.override = null
        }

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

    /**
     * Emergency hard stop for newly detected Zerodha holdings.
     *
     * 0.05 means the first protective SELL GTT is placed around entry * 0.95.
     * The same floor is reused by later OCO creation/trailing so the app has one
     * consistent "never leave me less protected than this" baseline.
     */
    var newPositionHardStopPct: Double
        get() = prefs.getFloat("new_position_hard_stop_pct", 0.05f).toDouble()
        set(v) = prefs.edit().putFloat("new_position_hard_stop_pct", v.toFloat()).apply()

    val newPositionHardStopFraction: Double
        get() = (1.0 - newPositionHardStopPct.coerceIn(0.01, 0.25)).coerceIn(0.75, 0.99)

    /**
     * Optional fixed chandelier ATR multiple for stop-loss placement/trailing.
     *
     * 0.0 keeps the existing automatic gain-tiered formula:
     *   <=5%: 3.0, <=10%: 2.5, <=20%: 2.0, <=30%: 1.5, else: 1.0.
     *
     * Any positive value overrides the tiers. Example: 1.2 gives a tighter,
     * more conservative stop.
     */
    var slChandelierFixedAtrMultiple: Double
        get() = prefs.getFloat("sl_chandelier_fixed_atr_multiple", 0.0f).toDouble()
        set(v) = prefs.edit().putFloat("sl_chandelier_fixed_atr_multiple", v.toFloat()).apply()

    /** Time-decay factor after a position has gone >=10 days without a fresh peak. */
    var slTimeDecay10dFactor: Double
        get() = prefs.getFloat("sl_time_decay_10d_factor", 0.80f).toDouble()
        set(v) = prefs.edit().putFloat("sl_time_decay_10d_factor", v.toFloat()).apply()

    /** Time-decay factor after a position has gone >=20 days without a fresh peak. */
    var slTimeDecay20dFactor: Double
        get() = prefs.getFloat("sl_time_decay_20d_factor", 0.60f).toDouble()
        set(v) = prefs.edit().putFloat("sl_time_decay_20d_factor", v.toFloat()).apply()

    /** Shared stop-loss chandelier multiplier used by Settings, India, US, and Germany flows. */
    fun slChandelierAtrMultiple(gainFraction: Double, daysSincePeak: Long = 0L): Double {
        var mult = if (slChandelierFixedAtrMultiple > 0.0) {
            slChandelierFixedAtrMultiple
        } else {
            when {
                gainFraction <= 0.05 -> 3.0
                gainFraction <= 0.10 -> 2.5
                gainFraction <= 0.20 -> 2.0
                gainFraction <= 0.30 -> 1.5
                else -> 1.0
            }
        }

        mult *= when {
            daysSincePeak >= 20 -> slTimeDecay20dFactor.coerceIn(0.10, 1.00)
            daysSincePeak >= 10 -> slTimeDecay10dFactor.coerceIn(0.10, 1.00)
            else -> 1.0
        }
        return mult.coerceIn(0.20, 8.00)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TRADING 212 BROKER OVERRIDES (Option C: per-broker divergent params)
    // ═══════════════════════════════════════════════════════════════════════
    // Zerodha and T212 have structurally different cost/order models that make
    // a single shared parameter set wrong for one of them:
    //
    //   - T212 has NO target leg (shares get reserved by the first pending
    //     sell). The SL is therefore the SOLE exit — it must trail closer to
    //     the peak to lock profits, not just stop reversals.
    //   - T212 has NO per-trade fee or DP charge on home-currency trades
    //     (EUR-on-Xetra, USD-on-NASDAQ for USD accounts). Cancel + replace
    //     cycles are basically free, so a tighter trail's whipsaw cost is
    //     lower than its give-back-on-trend benefit — flipping the trade-off.
    //   - T212 stocks (US large caps, German Xetra mid-caps) often have
    //     higher daily ATR than NSE blue-chips, so a flat −10% hard floor
    //     gets stopped on routine noise. A −15% floor gives breathing room.
    //
    // Therefore: the SL trail curve is tighter on T212, the hard floor is
    // deeper, time decay accelerates faster, and auto-trail is ON by default
    // (Zerodha leaves it user-opt-in because the GTT modify endpoint is rate
    // limited). The Settings UI surfaces both as paired rows.

    /** T212 hard floor as a fraction of entry price. Default 0.85 = −15% vs Zerodha's −10%. */
    var t212NewPositionHardStopFraction: Double
        get() = prefs.getFloat("t212_new_position_hard_stop_fraction", 0.85f).toDouble()
        set(v) = prefs.edit().putFloat("t212_new_position_hard_stop_fraction", v.toFloat()).apply()

    /** T212 fixed-mult override (default 0 = use the tiered curve below). */
    var t212SlChandelierFixedAtrMultiple: Double
        get() = prefs.getFloat("t212_sl_chandelier_fixed_atr_multiple", 0.0f).toDouble()
        set(v) = prefs.edit().putFloat("t212_sl_chandelier_fixed_atr_multiple", v.toFloat()).apply()

    /** T212 time-decay after 10d without a fresh peak. Default 0.65 (vs Zerodha 0.80). */
    var t212SlTimeDecay10dFactor: Double
        get() = prefs.getFloat("t212_sl_time_decay_10d_factor", 0.65f).toDouble()
        set(v) = prefs.edit().putFloat("t212_sl_time_decay_10d_factor", v.toFloat()).apply()

    /** T212 time-decay after 20d. Default 0.45 (vs Zerodha 0.60). */
    var t212SlTimeDecay20dFactor: Double
        get() = prefs.getFloat("t212_sl_time_decay_20d_factor", 0.45f).toDouble()
        set(v) = prefs.edit().putFloat("t212_sl_time_decay_20d_factor", v.toFloat()).apply()

    /** T212 limit pad below the SL trigger. Default 0.5% (Zerodha 2%) — T212
     *  home-currency liquid names have tighter spreads, so a tight pad fills. */
    var t212SlLimitPct: Double
        get() = prefs.getFloat("t212_sl_limit_pct", 0.005f).toDouble()
        set(v) = prefs.edit().putFloat("t212_sl_limit_pct", v.toFloat()).apply()

    /** T212 auto-create protective stop for holdings without an exit leg. */
    var t212AutoCreateGtt: Boolean
        get() = prefs.getBoolean("t212_auto_create_gtt", false)
        set(v) = prefs.edit().putBoolean("t212_auto_create_gtt", v).apply()

    /** T212 auto-trail toggle. Default ON — trailing is the WHOLE point of
     *  T212 management since there's no Target leg to catch winners. */
    var t212AutoTrailingEnabled: Boolean
        get() = prefs.getBoolean("t212_auto_trailing_enabled", true)
        set(v) = prefs.edit().putBoolean("t212_auto_trailing_enabled", v).apply()

    /** T212 chandelier multiplier with the TIGHTER curve (SL is sole exit so
     *  trails closer to lock profits on the upside). Mirrors the shared
     *  `slChandelierAtrMultiple` shape; uses T212-prefixed config getters. */
    fun t212SlChandelierAtrMultiple(gainFraction: Double, daysSincePeak: Long = 0L): Double {
        var mult = if (t212SlChandelierFixedAtrMultiple > 0.0) {
            t212SlChandelierFixedAtrMultiple
        } else {
            // Tighter than Zerodha at every tier. The biggest divergence is
            // at >30% gain (1.0 → 0.5) where T212's SL must catch reversals
            // fast because there's no Target leg to lock the gain.
            when {
                gainFraction <= 0.05 -> 2.5
                gainFraction <= 0.10 -> 1.8
                gainFraction <= 0.20 -> 1.2
                gainFraction <= 0.30 -> 0.8
                else -> 0.5
            }
        }
        mult *= when {
            daysSincePeak >= 20 -> t212SlTimeDecay20dFactor.coerceIn(0.10, 1.00)
            daysSincePeak >= 10 -> t212SlTimeDecay10dFactor.coerceIn(0.10, 1.00)
            else -> 1.0
        }
        return mult.coerceIn(0.20, 8.00)
    }

    /** Epoch ms of last successful daily tick — prevents double-runs within one trading day. */
    var lastDailyTickEpoch: Long
        get() = prefs.getLong("last_daily_tick_epoch", 0L)
        set(v) = prefs.edit().putLong("last_daily_tick_epoch", v).apply()

    /** Separate T212 trailing/reconcile tick marker; US/DE market days differ from NSE. */
    var lastT212DailyTickEpoch: Long
        get() = prefs.getLong("last_t212_daily_tick_epoch", 0L)
        set(v) = prefs.edit().putLong("last_t212_daily_tick_epoch", v).apply()

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
    // BULK KEY IMPORT (upload one JSON instead of typing every key)
    // ═══════════════════════════════════════════════════════

    /**
     * Import API credentials from a JSON object (the fill-in template).
     * Only NON-BLANK string values are applied, so a partial file updates just
     * those keys and never wipes an existing one with a blank. Snake_case and
     * UPPER_CASE aliases are both accepted. Unknown keys are ignored.
     *
     * @return (countApplied, humanReadableSummary). Throws on invalid JSON so
     *         the caller can surface a parse error.
     */
    fun importCredentialsJson(jsonText: String): Pair<Int, String> {
        val o = com.google.gson.JsonParser.parseString(jsonText).asJsonObject
        var n = 0
        val applied = ArrayList<String>()
        fun apply(aliases: List<String>, label: String, set: (String) -> Unit) {
            for (k in aliases) {
                if (o.has(k) && !o.get(k).isJsonNull) {
                    val v = o.get(k).asString.trim()
                    if (v.isNotEmpty()) { set(v); n++; applied.add(label) }
                    return   // first matching alias wins, even if blank (don't overwrite with "")
                }
            }
        }
        apply(listOf("zerodha_api_key", "ZERODHA_API_KEY"), "Zerodha key") { zerodhaApiKey = it }
        apply(listOf("zerodha_api_secret", "ZERODHA_API_SECRET"), "Zerodha secret") { zerodhaApiSecret = it }
        apply(listOf("t212_api_key", "T212_API_KEY"), "T212 key") { t212ApiKey = it }
        apply(listOf("t212_api_secret", "T212_API_SECRET"), "T212 secret") { t212ApiSecret = it }
        apply(listOf("binance_api_key", "BINANCE_API_KEY"), "Binance key") { binanceApiKey = it }
        apply(listOf("binance_api_secret", "BINANCE_API_SECRET"), "Binance secret") { binanceApiSecret = it }
        apply(listOf("ai_api_key", "openai_api_key", "OPENAI_API_KEY", "llm_api_key"), "AI key") { openaiApiKey = it }
        apply(listOf("llm_provider"), "LLM provider") { llmProvider = it.lowercase() }
        apply(listOf("llm_model"), "LLM model") { llmModel = it }
        return n to (if (applied.isEmpty()) "No recognized keys found in the file." else "Imported: " + applied.joinToString(", "))
    }

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

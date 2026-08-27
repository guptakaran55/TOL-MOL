package com.signalscope.app.trading

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.TimeUnit

/**
 * Per-holding trade-lifecycle state that the daily trailing-SL + MACD-exit
 * logic needs but Zerodha doesn't tell us:
 *   - entryPrice / entryDate  (we approximate: avgPrice from Zerodha, first-seen date)
 *   - peakPrice / peakDate    (chandelier anchor — highest LTP since entry)
 *   - peakHistValue           (highest MACD histogram since entry — v2 watchdog)
 *   - histNegativeSinceEpoch  (when hist first went negative; 0 if currently ≥ 0)
 *   - lastEvaluatedEpoch      (so we only tick once per trading day)
 *
 * Persisted in the same EncryptedSharedPreferences as ConfigManager uses
 * (AES-256-GCM, keystore-wrapped) since this tracks real-money position data.
 *
 * Keyed by tradingsymbol. If a symbol disappears from holdings (fully sold)
 * we keep the record for 30 days then GC — lets the user re-open a position
 * without instantly losing history from a quick round-trip.
 */
class PositionTracker(context: Context) {

    data class TradeState(
        val symbol: String,
        val entryPrice: Double,       // avgPrice from Zerodha at first-seen
        val entryEpoch: Long,         // epoch ms when first seen in holdings
        var peakPrice: Double,        // highest LTP observed since entry
        var peakEpoch: Long,          // when peak was set
        var peakHistValue: Double,    // highest MACD histogram (macd - signal) since entry
        var peakHistEpoch: Long,      // when peak histogram was set
        var histNegativeSinceEpoch: Long, // when hist first went < 0 in current streak; 0 if hist ≥ 0
        var lastSL: Double,           // last SL trigger price we set (for monotonic trail)
        var lastEvaluatedEpoch: Long, // epoch ms of last daily-tick processing
        var exitedEpoch: Long = 0L    // non-zero = position closed, kept for GC
    )

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "position_tracker_v1",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val gson = Gson()
    private val listType = object : TypeToken<Map<String, TradeState>>() {}.type
    private val KEY = "states_v1"

    @Synchronized
    fun getAll(): MutableMap<String, TradeState> {
        val raw = prefs.getString(KEY, null) ?: return mutableMapOf()
        return try {
            val parsed: Map<String, TradeState> = gson.fromJson(raw, listType) ?: emptyMap()
            parsed.toMutableMap()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    @Synchronized
    fun get(symbol: String): TradeState? = getAll()[symbol]

    @Synchronized
    fun saveAll(states: Map<String, TradeState>) {
        prefs.edit().putString(KEY, gson.toJson(states)).apply()
    }

    /**
     * Reconcile the broker's holdings snapshot against our tracked state:
     *   - new symbol → create a TradeState with entry=avgPrice, peak=ltp, today as day 1
     *   - existing symbol → update peak if ltp > peak, update peak-MACD if slope > peakSlope
     *   - symbol we track but broker no longer reports → mark exitedEpoch for GC
     * Returns the up-to-date map so callers don't need a second read.
     */
    @Synchronized
    fun reconcile(
        brokerSymbols: List<Triple<String, Double, Double>>, // (symbol, avgPrice, ltp)
        todayHistBySymbol: Map<String, Double>,
        nowMs: Long = System.currentTimeMillis()
    ): Map<String, TradeState> {
        val current = getAll()
        val seen = mutableSetOf<String>()

        for ((symbol, avg, ltp) in brokerSymbols) {
            seen.add(symbol)
            val hist = todayHistBySymbol[symbol] ?: 0.0
            val existing = current[symbol]
            if (existing == null || existing.exitedEpoch > 0L) {
                // New position (or re-entry after an exit)
                current[symbol] = TradeState(
                    symbol = symbol,
                    entryPrice = avg,
                    entryEpoch = nowMs,
                    peakPrice = ltp,
                    peakEpoch = nowMs,
                    peakHistValue = hist,
                    peakHistEpoch = nowMs,
                    histNegativeSinceEpoch = if (hist < 0.0) nowMs else 0L,
                    lastSL = 0.0,
                    lastEvaluatedEpoch = 0L,
                    exitedEpoch = 0L
                )
            } else {
                if (ltp > existing.peakPrice) {
                    existing.peakPrice = ltp
                    existing.peakEpoch = nowMs
                }
                if (hist > existing.peakHistValue) {
                    existing.peakHistValue = hist
                    existing.peakHistEpoch = nowMs
                }
                // Track the current hist-negative streak. Reset to 0 whenever
                // hist returns to ≥ 0 so a brief flip to red weeks ago doesn't
                // count toward the "sustained" filter.
                if (hist < 0.0) {
                    if (existing.histNegativeSinceEpoch == 0L) {
                        existing.histNegativeSinceEpoch = nowMs
                    }
                } else {
                    existing.histNegativeSinceEpoch = 0L
                }
                // Broker may have updated avgPrice (partial top-up buy). Take the more
                // conservative (higher) value so our entry basis never understates cost.
                if (avg > existing.entryPrice) {
                    // Recompute: weighted avg already applied broker-side. Trust it.
                    val newState = existing.copy(entryPrice = avg)
                    current[symbol] = newState
                }
            }
        }

        // Mark exits (broker no longer reports the symbol)
        for ((sym, state) in current.entries.toList()) {
            if (sym !in seen && state.exitedEpoch == 0L) {
                state.exitedEpoch = nowMs
            }
        }

        // GC: drop records that have been exited for >30 days
        val gcCutoff = nowMs - TimeUnit.DAYS.toMillis(30)
        val cleaned = current.filterValues { it.exitedEpoch == 0L || it.exitedEpoch > gcCutoff }
            .toMutableMap()

        saveAll(cleaned)
        return cleaned
    }

    /** Update lastSL + lastEvaluatedEpoch after a successful SL modify. */
    @Synchronized
    fun markSlUpdated(symbol: String, newSL: Double, nowMs: Long = System.currentTimeMillis()) {
        val current = getAll()
        current[symbol]?.let {
            it.lastSL = newSL
            it.lastEvaluatedEpoch = nowMs
            saveAll(current)
        }
    }

    @Synchronized
    fun markEvaluated(symbol: String, nowMs: Long = System.currentTimeMillis()) {
        val current = getAll()
        current[symbol]?.let {
            it.lastEvaluatedEpoch = nowMs
            saveAll(current)
        }
    }

    @Synchronized
    fun clearAll() = prefs.edit().remove(KEY).apply()
}

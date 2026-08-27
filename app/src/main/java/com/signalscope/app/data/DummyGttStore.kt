package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/**
 * App-only ("dummy") GTT levels the user wants to be ALERTED about — never
 * sent to any broker. This is the foundation of the manual-execution flow for
 * Trading 212 (US/DE), where only one live order can exist at a time, so a
 * simultaneous SL+target OCO is impossible. Instead of placing real orders the
 * app watches the live price and vibrates + notifies when a level is reached,
 * leaving the user to place the single real order manually after review.
 *
 * Although it exists for T212's constraint, it is space-agnostic: the user can
 * track Zerodha (IN) levels too (their "all spaces" choice). Nothing here
 * touches ZerodhaClient / Trading212Client / the OCO planner.
 *
 * Storage:
 *   filesDir/dummy_gtts.json   atomic write (write-to-tmp + rename)
 *
 * `slNotifiedAt` / `targetNotifiedAt` are stamped when an alert fires so the
 * monitor doesn't re-alert on every subsequent tick. "Re-arm" clears them.
 */
object DummyGttStore {

    private const val TAG = "DummyGttStore"
    private val gson = Gson()

    data class DummyGtt(
        val id: String = UUID.randomUUID().toString(),
        val space: String,            // "IN" / "US" / "DE"
        val symbol: String,           // trading / display symbol (e.g. "ENR.DE", "RELIANCE")
        val yahooSymbol: String,      // live-quote match key (uppercased, e.g. "ENR.DE")
        val ticker: String = "",      // T212 ticker if relevant (e.g. "ENR1l_EQ")
        val qty: Double = 0.0,
        val slPrice: Double? = null,
        val targetPrice: Double? = null,
        val refPrice: Double = 0.0,   // LTP at creation — disambiguates which side a level sits on
        val note: String = "",
        val createdAt: Long = System.currentTimeMillis(),
        val slNotifiedAt: Long? = null,
        val targetNotifiedAt: Long? = null
    )

    // In-memory cache so the live monitor doesn't hit disk on every tick.
    // Invalidated on every mutation; (re)populated on first read.
    @Volatile private var cache: List<DummyGtt>? = null

    private fun file(ctx: Context): File = File(ctx.filesDir, "dummy_gtts.json")

    @Synchronized
    fun load(ctx: Context): List<DummyGtt> {
        cache?.let { return it }
        val list = try {
            val f = file(ctx)
            if (!f.exists()) emptyList()
            else {
                val type = object : TypeToken<List<DummyGtt>>() {}.type
                gson.fromJson<List<DummyGtt>>(f.readText(), type) ?: emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "load failed — returning empty", e)
            emptyList()
        }
        cache = list
        return list
    }

    @Synchronized
    private fun saveAll(ctx: Context, list: List<DummyGtt>) {
        try {
            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(gson.toJson(list))
            if (f.exists()) f.delete()
            tmp.renameTo(f)
            cache = list
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
        }
    }

    @Synchronized
    fun add(ctx: Context, gtt: DummyGtt): List<DummyGtt> {
        val list = load(ctx).toMutableList()
        list.add(gtt)
        saveAll(ctx, list)
        return list
    }

    @Synchronized
    fun remove(ctx: Context, id: String): List<DummyGtt> {
        val list = load(ctx).filterNot { it.id == id }
        saveAll(ctx, list)
        return list
    }

    /** Clears the notified stamps so the level alerts again. */
    @Synchronized
    fun reArm(ctx: Context, id: String): List<DummyGtt> {
        val list = load(ctx).map {
            if (it.id == id) it.copy(slNotifiedAt = null, targetNotifiedAt = null) else it
        }
        saveAll(ctx, list)
        return list
    }

    /** Stamp a side as notified so the monitor stops re-firing for it. */
    @Synchronized
    fun markNotified(ctx: Context, id: String, side: Side, whenMs: Long = System.currentTimeMillis()) {
        val list = load(ctx).map {
            if (it.id != id) it
            else when (side) {
                Side.SL -> it.copy(slNotifiedAt = whenMs)
                Side.TARGET -> it.copy(targetNotifiedAt = whenMs)
            }
        }
        saveAll(ctx, list)
    }

    enum class Side { SL, TARGET }

    /** Yahoo symbols of all dummy GTTs — the set the live stream must subscribe to. */
    fun yahooSymbols(ctx: Context): List<String> =
        load(ctx).map { it.yahooSymbol.trim().uppercase() }.filter { it.isNotBlank() }.distinct()
}

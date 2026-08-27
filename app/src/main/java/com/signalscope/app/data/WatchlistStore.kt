package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Persists the user's watchlist of "wavy" stocks they're interested in entering
 * via BUY GTTs (limit orders sitting at support, waiting for a dip-fill).
 *
 * Storage:
 *   filesDir/watchlist.json   atomic write (write-to-tmp + rename)
 *
 * Mutation pattern:
 *   load → mutate list → save. All mutations go through this store so
 *   concurrent readers always see a consistent file.
 *
 * Each entry carries:
 *   - symbol / exchange (canonical identifiers used by ZerodhaClient)
 *   - addedAt (epoch ms; used for "added 3d ago" labels)
 *   - notes (free-text reason — "bouncing off 200dma weekly", etc.)
 *   - qtyOverride (null → prompt before live order; non-null → fixed qty)
 *   - lastSnapshot (cached LTP/score from last analyze pass — staleness label)
 */
object WatchlistStore {

    private const val TAG = "WatchlistStore"
    private val gson = Gson()

    /**
     * One watchlist entry. Designed to be gson-friendly with sensible defaults
     * so older JSON files still deserialize after schema additions.
     */
    data class Entry(
        val symbol: String,
        val exchange: String = "NSE",
        val addedAt: Long = System.currentTimeMillis(),
        val notes: String = "",
        val qtyOverride: Int? = null,
        val lastSnapshot: Snapshot? = null
    )

    /** Cached scoring snapshot — refreshed when the user taps "rescore" on a row. */
    data class Snapshot(
        val ltp: Double,
        val support: Double,
        val resistance: Double,
        val atr: Double,
        @SerializedName("buyScore")
        val cherryPoints: Int,
        @SerializedName("buySignal")
        val cherrySignal: String,
        val pullbackScore: Int = 0,
        val pullbackSignal: String = "NO SIGNAL",
        val momentumScore: Int = 0,
        val momentumSignal: String = "NO SIGNAL",
        val entryMode: String = "NONE",
        val proposedTrigger: Double,
        val capturedAt: Long
    )

    private fun file(ctx: Context): File = File(ctx.filesDir, "watchlist.json")

    private fun normSymbol(symbol: String): String = symbol.trim().uppercase()
    private fun normExchange(exchange: String?): String = exchange?.trim()?.uppercase().orEmpty()
    private fun sameKey(entry: Entry, symbol: String, exchange: String? = null): Boolean {
        if (!entry.symbol.equals(symbol.trim(), ignoreCase = true)) return false
        val ex = normExchange(exchange)
        return ex.isBlank() || normExchange(entry.exchange) == ex
    }

    /** @return immutable list — caller should treat as read-only and use add/remove/update for mutations. */
    fun load(ctx: Context): List<Entry> {
        return try {
            val f = file(ctx)
            if (!f.exists()) return emptyList()
            val type = object : TypeToken<List<Entry>>() {}.type
            gson.fromJson<List<Entry>>(f.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load watchlist — returning empty", e)
            emptyList()
        }
    }

    private fun saveAll(ctx: Context, list: List<Entry>) {
        try {
            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(gson.toJson(list))
            if (f.exists()) f.delete()
            tmp.renameTo(f)
            Log.d(TAG, "Saved watchlist (${list.size} entries)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save watchlist", e)
        }
    }

    /** Adds or no-ops if symbol already present. Returns the resulting list. */
    fun add(ctx: Context, entry: Entry): List<Entry> {
        val list = load(ctx).toMutableList()
        if (list.any { sameKey(it, entry.symbol, entry.exchange) }) return list
        list.add(entry)
        saveAll(ctx, list)
        return list
    }

    /** Removes by symbol (case-insensitive). Returns the resulting list. */
    fun remove(ctx: Context, symbol: String, exchange: String? = null): List<Entry> {
        val list = load(ctx).toMutableList()
        list.removeAll { sameKey(it, symbol, exchange) }
        saveAll(ctx, list)
        return list
    }

    /** Patches one entry's mutable fields (notes / qtyOverride / lastSnapshot). */
    fun update(
        ctx: Context,
        symbol: String,
        exchange: String? = null,
        notes: String? = null,
        qtyOverride: Int? = null,
        lastSnapshot: Snapshot? = null
    ): List<Entry> {
        val list = load(ctx).toMutableList()
        val idx = list.indexOfFirst { sameKey(it, symbol, exchange) }
        if (idx < 0) return list
        val cur = list[idx]
        list[idx] = cur.copy(
            notes = notes ?: cur.notes,
            qtyOverride = qtyOverride ?: cur.qtyOverride,
            lastSnapshot = lastSnapshot ?: cur.lastSnapshot
        )
        saveAll(ctx, list)
        return list
    }

    /** Convenience: contains-check used by the detail-modal button to flip its label. */
    fun contains(ctx: Context, symbol: String, exchange: String? = null): Boolean =
        load(ctx).any { sameKey(it, symbol, exchange) }
}

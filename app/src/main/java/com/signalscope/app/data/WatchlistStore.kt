package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
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
 *   - qtyOverride (null → use risk-per-trade math; non-null → fixed qty)
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
        val buyScore: Int,
        val buySignal: String,
        val pullbackScore: Int = 0,
        val pullbackSignal: String = "NO SIGNAL",
        val momentumScore: Int = 0,
        val momentumSignal: String = "NO SIGNAL",
        val entryMode: String = "NONE",
        val proposedTrigger: Double,
        val capturedAt: Long
    )

    private fun file(ctx: Context): File = File(ctx.filesDir, "watchlist.json")

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
        if (list.any { it.symbol.equals(entry.symbol, ignoreCase = true) }) return list
        list.add(entry)
        saveAll(ctx, list)
        return list
    }

    /** Removes by symbol (case-insensitive). Returns the resulting list. */
    fun remove(ctx: Context, symbol: String): List<Entry> {
        val list = load(ctx).toMutableList()
        list.removeAll { it.symbol.equals(symbol, ignoreCase = true) }
        saveAll(ctx, list)
        return list
    }

    /** Patches one entry's mutable fields (notes / qtyOverride / lastSnapshot). */
    fun update(
        ctx: Context,
        symbol: String,
        notes: String? = null,
        qtyOverride: Int? = null,
        lastSnapshot: Snapshot? = null
    ): List<Entry> {
        val list = load(ctx).toMutableList()
        val idx = list.indexOfFirst { it.symbol.equals(symbol, ignoreCase = true) }
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
    fun contains(ctx: Context, symbol: String): Boolean =
        load(ctx).any { it.symbol.equals(symbol, ignoreCase = true) }
}

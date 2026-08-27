package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Persistent record of app-managed T212 OCO pairs. Trading 212 has no native
 * one-cancels-other, so when we place a SL+Target pair we keep both order
 * ids on disk together with the position context. The trailing routine then:
 *   - Cancels the survivor when one leg fills (poll fetchOpenOrders + diff)
 *   - Ratchets SL/Target prices up via cancel + replace (T212 has no modify)
 *
 * Storage:
 *   filesDir/t212_oco_pairs.json   atomic write (mirrors PortfolioResultStore)
 */
object T212OcoStore {

    private const val TAG = "T212OcoStore"
    private val gson = Gson()

    /**
     * One app-managed OCO pair. `slPrice` / `tgtPrice` are recorded so the
     * trailing routine can decide "is the new computed price tighter than
     * the existing leg?" without re-fetching the order from T212.
     *
     * `simulated` is a legacy flag for older fake pairs. New pairs are always
     * real broker orders; the trailing manager removes legacy fake pairs.
     */
    data class Pair(
        val ticker: String,
        val slOrderId: Long,
        val tgtOrderId: Long,
        val qty: Double,
        val slPrice: Double,
        val tgtPrice: Double,
        val avgPrice: Double,        // entry — used by hardFloor in trail recompute
        val createdAt: Long,
        val simulated: Boolean = false
    )

    private fun file(ctx: Context): File = File(ctx.filesDir, "t212_oco_pairs.json")

    fun loadAll(ctx: Context): List<Pair> {
        return try {
            val f = file(ctx)
            if (!f.exists()) return emptyList()
            val type = object : TypeToken<List<Pair>>() {}.type
            gson.fromJson<List<Pair>>(f.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "loadAll failed", e)
            emptyList()
        }
    }

    private fun saveAll(ctx: Context, list: List<Pair>) {
        try {
            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(gson.toJson(list))
            if (f.exists()) f.delete()
            tmp.renameTo(f)
            Log.d(TAG, "Saved ${list.size} T212 OCO pair(s)")
        } catch (e: Exception) {
            Log.e(TAG, "saveAll failed", e)
        }
    }

    /** Add a pair. Removes any existing pair for the same ticker first
     *  (one OCO per ticker — replacing on re-create is sane behavior). */
    fun add(ctx: Context, pair: Pair): List<Pair> {
        val list = loadAll(ctx).toMutableList()
        list.removeAll { it.ticker == pair.ticker }
        list.add(pair)
        saveAll(ctx, list)
        return list
    }

    fun remove(ctx: Context, ticker: String): List<Pair> {
        val list = loadAll(ctx).toMutableList()
        list.removeAll { it.ticker == ticker }
        saveAll(ctx, list)
        return list
    }

    /** Replace one pair's mutable fields (used by the trailing routine after
     *  a successful cancel + replace cycle). */
    fun update(
        ctx: Context, ticker: String,
        slOrderId: Long? = null, tgtOrderId: Long? = null,
        slPrice: Double? = null, tgtPrice: Double? = null
    ): List<Pair> {
        val list = loadAll(ctx).toMutableList()
        val idx = list.indexOfFirst { it.ticker == ticker }
        if (idx < 0) return list
        val cur = list[idx]
        list[idx] = cur.copy(
            slOrderId = slOrderId ?: cur.slOrderId,
            tgtOrderId = tgtOrderId ?: cur.tgtOrderId,
            slPrice = slPrice ?: cur.slPrice,
            tgtPrice = tgtPrice ?: cur.tgtPrice
        )
        saveAll(ctx, list)
        return list
    }

    fun findByTicker(ctx: Context, ticker: String): Pair? =
        loadAll(ctx).firstOrNull { it.ticker == ticker }
}

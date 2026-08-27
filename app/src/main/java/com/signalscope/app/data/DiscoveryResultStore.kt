package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

/**
 * Persists the last DiscoveryScanResult to internal storage as JSON.
 * Survives process death, app restarts, and device reboots.
 *
 * One file per market (nifty500 / nasdaq100) so both caches coexist.
 *
 * Uses atomic writes (write-to-tmp + rename) so the file is never corrupted
 * even if MIUI/Xiaomi kills the app mid-write.
 */
object DiscoveryResultStore {

    private const val TAG = "DiscoveryResultStore"
    private val gson = Gson()

    private fun fileFor(context: Context, market: String): File {
        val safeName = market.replace(" ", "_").lowercase()
        return File(context.filesDir, "discovery_${safeName}.json")
    }

    private fun completeFileFor(context: Context, market: String): File {
        val safeName = market.replace(" ", "_").lowercase()
        return File(context.filesDir, "discovery_${safeName}_complete.json")
    }

    private fun previousCompleteFileFor(context: Context, market: String): File {
        val safeName = market.replace(" ", "_").lowercase()
        return File(context.filesDir, "discovery_${safeName}_previous_complete.json")
    }

    fun save(context: Context, result: DiscoveryScanResult) {
        try {
            val file = fileFor(context, result.market)
            if (result.isComplete) {
                val completeFile = completeFileFor(context, result.market)
                val previousCompleteFile = previousCompleteFileFor(context, result.market)
                if (completeFile.exists()) {
                    try {
                        val old = gson.fromJson(completeFile.readText(), DiscoveryScanResult::class.java)
                        if (old != null && old.isComplete && old.timestamp != result.timestamp) {
                            val tmpPrev = File(previousCompleteFile.parentFile, previousCompleteFile.name + ".tmp")
                            tmpPrev.writeText(gson.toJson(old))
                            if (previousCompleteFile.exists()) previousCompleteFile.delete()
                            tmpPrev.renameTo(previousCompleteFile)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to rotate previous complete discovery snapshot for ${result.market}", e)
                    }
                }
                val tmpComplete = File(completeFile.parentFile, completeFile.name + ".tmp")
                tmpComplete.writeText(gson.toJson(result))
                if (completeFile.exists()) completeFile.delete()
                tmpComplete.renameTo(completeFile)
            }
            // Atomic write: write to temp file first, then rename. Prevents corruption
            // if the app/service is killed mid-write (very common on MIUI/Xiaomi).
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(gson.toJson(result))
            if (file.exists()) file.delete()
            tmp.renameTo(file)
            Log.d(TAG, "Saved ${result.allStocks.size} stocks for ${result.market} (${file.length() / 1024}KB, complete=${result.isComplete})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save discovery result", e)
        }
    }

    fun load(context: Context, market: String): DiscoveryScanResult? {
        return try {
            val file = fileFor(context, market)
            if (!file.exists()) return null
            val result = gson.fromJson(file.readText(), DiscoveryScanResult::class.java)
            Log.d(TAG, "Loaded ${result.allStocks.size} stocks for ${result.market}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load discovery result for $market — file may be corrupted, deleting", e)
            try { fileFor(context, market).delete() } catch (_: Exception) {}
            null
        }
    }

    fun loadLatestComplete(context: Context, market: String): DiscoveryScanResult? {
        return try {
            val file = completeFileFor(context, market)
            if (file.exists()) {
                val result = gson.fromJson(file.readText(), DiscoveryScanResult::class.java)
                if (result != null && result.isComplete) return result
            }
            load(context, market)?.takeIf { it.isComplete }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load latest complete discovery result for $market", e)
            null
        }
    }

    /** Load the most recently saved result (any market). Skips .tmp files. */
    fun loadLatest(context: Context): DiscoveryScanResult? {
        return try {
            val dir = context.filesDir
            val latest = dir.listFiles()
                ?.filter { it.name.startsWith("discovery_") && it.name.endsWith(".json") && !it.name.contains("_complete") }
                ?.maxByOrNull { it.lastModified() }
                ?: return null
            val result = gson.fromJson(latest.readText(), DiscoveryScanResult::class.java)
            Log.d(TAG, "Loaded latest: ${result.allStocks.size} stocks (${result.market})")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load latest discovery result", e)
            null
        }
    }

    /** Load every market snapshot currently persisted on disk. */
    fun loadAll(context: Context): List<DiscoveryScanResult> {
        return try {
            val dir = context.filesDir
            dir.listFiles()
                ?.filter { it.name.startsWith("discovery_") && it.name.endsWith(".json") && !it.name.contains("_complete") }
                ?.mapNotNull { file ->
                    try {
                        gson.fromJson(file.readText(), DiscoveryScanResult::class.java)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load ${file.name}, deleting corrupted cache", e)
                        try { file.delete() } catch (_: Exception) {}
                        null
                    }
                }
                ?.sortedByDescending { it.timestamp }
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load discovery cache list", e)
            emptyList()
        }
    }
}

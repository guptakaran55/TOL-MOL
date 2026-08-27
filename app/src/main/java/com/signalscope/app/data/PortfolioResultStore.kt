package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

/**
 * Persists the last portfolio payload (the JSON pushed to dashboard.html)
 * to internal storage so the Portfolio tab survives:
 *   - app switching (Android killing the process for memory)
 *   - MIUI / OnePlus / Samsung aggressive background killing
 *   - device reboots
 *
 * The stored payload is the *exact same JSON* that was last sent to the WebView
 * via window.updatePortfolioData(...) — so on cold start we can re-push it
 * verbatim without rebuilding any holdings/analysis.
 *
 * Mirrors DiscoveryResultStore's atomic write contract (write-to-tmp + rename)
 * so the snapshot is never corrupted even if the OS kills the app mid-write.
 */
object PortfolioResultStore {

    private const val TAG = "PortfolioResultStore"
    private val gson = Gson()

    /**
     * @param payloadJson the already-serialised JSON object that was sent to the
     *                    WebView; must include a "capturedAt" epoch-millis field.
     */
    data class Snapshot(
        val payloadJson: String,
        val capturedAt: Long
    )

    private fun file(ctx: Context): File =
        File(ctx.filesDir, "portfolio_snapshot.json")

    fun save(ctx: Context, payloadJson: String) {
        try {
            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            val snap = Snapshot(payloadJson, System.currentTimeMillis())
            tmp.writeText(gson.toJson(snap))
            if (f.exists()) f.delete()
            tmp.renameTo(f)
            Log.d(TAG, "Saved portfolio snapshot (${f.length() / 1024}KB)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save portfolio snapshot", e)
        }
    }

    fun load(ctx: Context): Snapshot? {
        return try {
            val f = file(ctx)
            if (!f.exists()) return null
            val s = gson.fromJson(f.readText(), Snapshot::class.java)
            Log.d(
                TAG,
                "Loaded portfolio snapshot from ${java.util.Date(s.capturedAt)}"
            )
            s
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load portfolio snapshot — deleting", e)
            try {
                file(ctx).delete()
            } catch (_: Exception) {
            }
            null
        }
    }
}

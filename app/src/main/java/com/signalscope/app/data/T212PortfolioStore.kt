package com.signalscope.app.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The last Trading 212 portfolio the dashboard was shown, kept on disk.
 *
 * Fetching it fresh means asking the broker, then pulling two years of prices
 * and the company figures for every holding, one after another — long enough
 * that the tab used to sit on an empty list. With this copy the tab paints at
 * once, and the fetch only has to redo the holdings that changed.
 *
 * Stored verbatim: it is the exact payload last passed to
 * window.onT212PortfolioResult, so it can be handed straight back. Written
 * through a temp file and a rename, like [PortfolioResultStore], so a kill
 * mid-write never leaves half a file.
 */
object T212PortfolioStore {

    private const val TAG = "T212PortfolioStore"

    private fun file(ctx: Context): File = File(ctx.filesDir, "t212_portfolio_snapshot.json")

    fun save(ctx: Context, payloadJson: String) {
        try {
            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(payloadJson)
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save T212 portfolio snapshot", e)
        }
    }

    fun load(ctx: Context): String? = try {
        file(ctx).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load T212 portfolio snapshot", e)
        null
    }
}

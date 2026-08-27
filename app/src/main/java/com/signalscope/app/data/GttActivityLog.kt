package com.signalscope.app.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Append-only audit log of every GTT decision the engine makes:
 *   - manual Bulk OCO creates from dashboard
 *   - daily auto-create OCO (DailyPortfolioManager.autoCreateOcoGtt)
 *   - daily SL/Target trail modifies
 *   - bumps, deletes
 *   - skip-decisions with reason ("min-hold", "below floor", "no-bump", etc.)
 *
 * Storage:
 *   filesDir/gtt_activity.jsonl  — newline-delimited JSON, one event per line
 *
 * Retention:
 *   Soft cap MAX_LINES (default 5000). When exceeded on append, the oldest
 *   half of the file is dropped. Cheap and self-healing — no separate rotation
 *   thread needed.
 *
 * Read path:
 *   readRecent(ctx, limit) returns the most recent N events as JSONArray,
 *   newest-first. Used by the dashboard "GTT Audit" modal via JS bridge.
 *
 * Thread safety:
 *   All writes synchronized on the LOCK object — ZerodhaClient + DailyPortfolioManager
 *   can both append concurrently from different threads.
 */
object GttActivityLog {

    private const val TAG = "GttActivityLog"
    private const val FILE_NAME = "gtt_activity.jsonl"
    private const val MAX_LINES = 5000
    private const val PRUNE_KEEP = 2500           // when over MAX_LINES, drop down to this
    private val LOCK = Any()

    private val tsFmt: SimpleDateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }
    }

    private fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    /**
     * Append a single event. Never throws — any I/O failure is logged and swallowed
     * so a logging hiccup can't break order placement or trailing logic.
     *
     * @param action       short verb tag: "create" / "modify_sl" / "modify_tgt" /
     *                     "bump" / "delete" / "skip" / "error"
     * @param symbol       trading symbol (or null for non-symbol events)
     * @param fields       arbitrary key/value pairs (sl/tg/old/new/ltp/reason/etc.)
     */
    fun append(
        ctx: Context,
        action: String,
        symbol: String? = null,
        fields: Map<String, Any?> = emptyMap()
    ) {
        try {
            val now = System.currentTimeMillis()
            val obj = JSONObject().apply {
                put("ts", tsFmt.format(Date(now)))
                put("epoch", now)
                put("action", action)
                if (symbol != null) put("sym", symbol)
                for ((k, v) in fields) {
                    if (v == null) continue
                    when (v) {
                        is Number  -> put(k, v)
                        is Boolean -> put(k, v)
                        else       -> put(k, v.toString())
                    }
                }
            }
            synchronized(LOCK) {
                val f = file(ctx)
                f.appendText(obj.toString() + "\n")
                // Cheap line count via newline scan; only when file is large
                if (f.length() > 800_000L) {
                    pruneIfNeeded(f)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "append($action) failed", e)
        }
    }

    /**
     * Drop the oldest half of the file when line count exceeds MAX_LINES.
     * Read-all → keep tail PRUNE_KEEP → atomic rewrite. Caller must hold LOCK.
     */
    private fun pruneIfNeeded(f: File) {
        try {
            val lines = f.readLines()
            if (lines.size <= MAX_LINES) return
            val keep = lines.takeLast(PRUNE_KEEP)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(keep.joinToString("\n") + "\n")
            f.delete()
            tmp.renameTo(f)
            Log.i(TAG, "Pruned activity log ${lines.size} → ${keep.size} lines")
        } catch (e: Exception) {
            Log.e(TAG, "prune failed", e)
        }
    }

    /**
     * Read the most recent [limit] events, newest first. Returns a JSONArray
     * (each element is the JSONObject originally appended, plus a parsed-okay
     * flag — malformed lines are silently skipped).
     */
    fun readRecent(ctx: Context, limit: Int = 500): JSONArray {
        return try {
            val f = file(ctx)
            if (!f.exists()) return JSONArray()
            synchronized(LOCK) {
                val tail = f.readLines().takeLast(limit).asReversed()
                val arr = JSONArray()
                for (line in tail) {
                    if (line.isBlank()) continue
                    try {
                        arr.put(JSONObject(line))
                    } catch (_: Exception) {
                        // skip corrupt line
                    }
                }
                arr
            }
        } catch (e: Exception) {
            Log.e(TAG, "readRecent failed", e)
            JSONArray()
        }
    }

    /** For debugging / settings screen — total events in file. */
    fun count(ctx: Context): Int = try {
        val f = file(ctx)
        if (!f.exists()) 0 else synchronized(LOCK) { f.readLines().count { it.isNotBlank() } }
    } catch (_: Exception) { 0 }

    /** Wipe — surfaced to user via Settings if they want a clean slate. */
    fun clear(ctx: Context) {
        try { synchronized(LOCK) { file(ctx).delete() } } catch (_: Exception) {}
    }
}

package com.signalscope.app.data

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

// ════════════════════════════════════════════════════════════════════════════
// EVERY NOTIFICATION THE APP HAS SENT, KEPT WHERE IT CAN BE FOUND AGAIN
// ════════════════════════════════════════════════════════════════════════════
// A notification is gone the moment it is swiped away or tapped — and a tap made
// while the app is still opening could land nowhere, so the one record of what
// it was about went with it. This keeps them: what it said, when, and where a
// tap was meant to take you, so the same place can be opened again from inside
// the app.
//
// Everything that posts a notification goes through [post], which posts it AND
// records it, so the list cannot fall behind what was actually shown. The
// running-scan progress notice is the one thing deliberately not posted through
// here: it rewrites itself every few seconds and is a status, not a message.
// ════════════════════════════════════════════════════════════════════════════

object NotificationLog {

    private const val TAG = "NotificationLog"
    private const val FILE = "notification_log.json"
    private const val MAX = 300
    private const val KEEP_MS = 30L * 24 * 60 * 60 * 1000

    /** Where a tap on the notification goes. Mirrors the deep links the system
     *  notification carries, so "Open" in the list does exactly what the tap did. */
    data class Link(
        val ns: String? = null,           // an algo's card
        val symbol: String? = null,       // a watchlist chart
        val watchSymbol: String? = null,  // a watchlist row
        val space: String? = null,
        val tab: String? = null,          // a tab
        val holding: String? = null       // a portfolio holding
    )

    data class Entry(
        val key: Long,
        val at: Long,
        val title: String,
        val text: String,
        val channel: String,
        val link: Link? = null
    )

    data class State(val entries: List<Entry> = emptyList(), val seenAt: Long = 0L)

    private val gson = Gson()
    @Volatile private var cache: State? = null

    @Synchronized
    fun load(ctx: Context): State {
        cache?.let { return it }
        val st = try {
            val f = File(ctx.filesDir, FILE)
            if (f.exists()) gson.fromJson(f.readText(), State::class.java) ?: State() else State()
        } catch (e: Exception) { Log.w(TAG, "load failed", e); State() }
        // Rows read back by a parser that ignores declared nullability.
        val rows: List<Entry>? = st.entries
        val clean = State(rows.orEmpty(), st.seenAt)
        cache = clean
        return clean
    }

    @Synchronized
    private fun save(ctx: Context, st: State) {
        cache = st
        try {
            val f = File(ctx.filesDir, FILE)
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeText(gson.toJson(st))
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (e: Exception) { Log.w(TAG, "save failed", e) }
    }

    /** Post [n] as notification [id] and record it. Posting comes first, and a
     *  failure to record never stops the notification itself. */
    fun post(ctx: Context, nm: NotificationManager, id: Int, n: Notification, link: Link? = null) {
        nm.notify(id, n)
        try { record(ctx, n, link) } catch (e: Exception) { Log.w(TAG, "record failed", e) }
    }

    @Synchronized
    fun record(ctx: Context, n: Notification, link: Link? = null) {
        val ex = n.extras
        val title = ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (ex?.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex?.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val now = System.currentTimeMillis()
        val st = load(ctx)
        val e = Entry(key = now * 1000 + (st.entries.size % 1000), at = now, title = title, text = text,
            channel = n.channelId ?: "", link = link)
        val kept = (listOf(e) + st.entries).filter { now - it.at < KEEP_MS }.take(MAX)
        save(ctx, st.copy(entries = kept))
    }

    fun unread(ctx: Context): Int {
        val st = load(ctx)
        return st.entries.count { it.at > st.seenAt }
    }

    @Synchronized
    fun markSeen(ctx: Context) {
        val st = load(ctx)
        save(ctx, st.copy(seenAt = System.currentTimeMillis()))
    }

    @Synchronized
    fun clear(ctx: Context) {
        save(ctx, State(emptyList(), System.currentTimeMillis()))
    }
}

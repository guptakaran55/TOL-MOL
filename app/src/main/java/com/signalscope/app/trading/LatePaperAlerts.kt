package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * PAPER TRADES FOUND LATE, TOLD AT THE OPEN.
 *
 * An algo books a paper buy or sale the moment it first sees the signal, and
 * nearly always that is minutes after the bar finished. Sometimes it is hours:
 * a price source that changed overnight, or a re-read once the feed caught up,
 * finds a signal on a bar that ended in the afternoon. That arrived as a fresh
 * "bought COFORGE (paper)" at 01:01 on a Saturday — a bar twelve hours old, on a
 * market that was shut until Monday.
 *
 * Stock markets only; crypto never closes. A signal more than [LATE_AFTER_SEC]
 * past its bar's end is a catch-up. If the market is trading it is sent at once,
 * marked late; otherwise it is held here and sent on the first pass after the
 * open. The paper trade itself is booked when it is found either way — only the
 * telling waits. Real orders are untouched: they have their own rules and their
 * own notifications.
 */
object LatePaperAlerts {
    private const val TAG = "LatePaperAlerts"
    private const val FILE = "late_paper_alerts.json"
    private val gson = Gson()

    /** A decision normally lands 10–35 minutes after its bar ends. This leaves
     *  room for a slow pass and still catches the ones found overnight. */
    const val LATE_AFTER_SEC = 90 * 60L

    /** Held longer than this — a long run of holidays — it is dropped, not sent. */
    private const val KEEP_MS = 7 * 24 * 3_600_000L

    data class Held(
        val ns: String,
        val symbol: String,
        val buy: Boolean,
        val price: Double = 0.0,
        val pct: Double = 0.0,
        val reason: String = "",
        /** When the signal's bar started, in seconds, and how long it lasts. */
        val barTs: Long = 0L,
        val frameSec: Long = 0L,
        val heldAt: Long = 0L
    )

    private data class Book(val held: List<Held> = emptyList())

    /** Is a signal on the bar that started at [barStartSec] late at [nowSec]? */
    fun isLate(barStartSec: Long, frameSec: Long, nowSec: Long): Boolean =
        barStartSec > 0L && nowSec - (barStartSec + frameSec) > LATE_AFTER_SEC

    /**
     * Which bar, in plain words: "Friday's close" when the bar ran to the end of
     * the session, otherwise "Friday at 12:30" — when it finished, on the phone's
     * clock. "today" and "yesterday" where they fit.
     */
    fun describe(base: String, barStartSec: Long, frameSec: Long, nowMs: Long,
                  local: ZoneId = ZoneId.systemDefault()): String {
        val end = Instant.ofEpochSecond(barStartSec + frameSec)
        val today = Instant.ofEpochMilli(nowMs).atZone(local).toLocalDate()
        fun dayName(d: java.time.LocalDate): String = when (d) {
            today -> "today"
            today.minusDays(1) -> "yesterday"
            else -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        }
        val zone = MarketHours.zoneOf(base)
        val open = MarketHours.openOf(base)
        val close = MarketHours.closeOf(base)
        if (zone != null && open != null && close != null) {
            val endAt = end.atZone(zone).toLocalTime()
            if (!endAt.isBefore(close) || endAt.isBefore(open)) {
                // The session's last bar: name the trading day it belongs to.
                val session = Instant.ofEpochSecond(barStartSec).atZone(zone).toLocalDate()
                return "${dayName(session)}'s close"
            }
        }
        val endLocal = end.atZone(local)
        return "${dayName(endLocal.toLocalDate())} at " +
            "%02d:%02d".format(endLocal.hour, endLocal.minute)
    }

    fun opened(ctx: Context, ns: String, symbol: String, price: Double, barTs: Long, frameSec: Long) =
        route(ctx, Held(ns, symbol, buy = true, price = price, barTs = barTs, frameSec = frameSec))

    fun closed(ctx: Context, ns: String, symbol: String, pct: Double, reason: String,
               barTs: Long, frameSec: Long) =
        route(ctx, Held(ns, symbol, buy = false, pct = pct, reason = reason,
            barTs = barTs, frameSec = frameSec))

    private fun note(h: Held, nowMs: Long): String =
        "Caught up late — signal from " +
            describe(Spaces.baseOf(h.ns), h.barTs, h.frameSec, nowMs)

    private fun route(ctx: Context, h: Held) {
        val base = Spaces.baseOf(h.ns)
        val now = System.currentTimeMillis()
        if (MarketHours.zoneOf(base) == null || !isLate(h.barTs, h.frameSec, now / 1000L)) {
            send(ctx, h, null); return
        }
        if (MarketHours.isRegularSession(base, now)) { send(ctx, h, note(h, now)); return }
        hold(ctx, h.copy(heldAt = now))
        Log.i(TAG, "[${h.ns}] ${h.symbol} ${if (h.buy) "buy" else "sale"} found late on a shut " +
            "market — held for the open")
    }

    private fun send(ctx: Context, h: Held, late: String?) {
        if (h.buy) LabNotifier.paperOpen(ctx, h.ns, h.symbol, h.price, late)
        else LabNotifier.paperClose(ctx, h.ns, h.symbol, h.pct, h.reason, late)
    }

    @Synchronized
    private fun load(ctx: Context): List<Held> = try {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) emptyList()
        else gson.fromJson(f.readText(), Book::class.java)?.held.orEmpty()
    } catch (e: Exception) { Log.w(TAG, "unreadable", e); emptyList() }

    @Synchronized
    private fun save(ctx: Context, held: List<Held>) {
        try { File(ctx.filesDir, FILE).writeText(gson.toJson(Book(held))) }
        catch (e: Exception) { Log.w(TAG, "save failed", e) }
    }

    @Synchronized
    private fun hold(ctx: Context, h: Held) {
        val rest = load(ctx).filterNot {
            it.ns == h.ns && it.symbol == h.symbol && it.buy == h.buy && it.barTs == h.barTs
        }
        save(ctx, rest + h)
    }

    /**
     * Send whatever was held for a market that is now trading. Called once a
     * pass, after the algos have run. Returns how many went out.
     */
    @Synchronized
    fun flush(ctx: Context): Int {
        val all = load(ctx)
        if (all.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val (due, waiting) = all.partition { MarketHours.isRegularSession(Spaces.baseOf(it.ns), now) }
        save(ctx, waiting.filter { now - it.heldAt < KEEP_MS })
        for (h in due) {
            try { send(ctx, h, note(h, now)) }
            catch (e: Exception) { Log.w(TAG, "[${h.ns}] ${h.symbol} could not be sent", e) }
        }
        return due.size
    }
}

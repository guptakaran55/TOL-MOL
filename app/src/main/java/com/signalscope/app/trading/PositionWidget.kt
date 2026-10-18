package com.signalscope.app.trading

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.signalscope.app.R

// ════════════════════════════════════════════════════════════════════════════
// THE POSITIONS WIDGET — the summary [PositionRadar] builds, on the home screen
// ════════════════════════════════════════════════════════════════════════════
// A notification is shown expanded only while the phone chooses to show it so;
// folded, it says one line and needs a tap. A widget is always fully visible,
// cannot be swiped away, and scrolls when there is more than fits.
//
// NOTHING IS FETCHED HERE. The background pass already gathers every position
// at the end of each run; it hands the result over and this only draws it. So
// the widget costs no requests, and shows exactly what the pass found — with the
// time it found it, so a panel that has stopped updating is obvious.
// ════════════════════════════════════════════════════════════════════════════

class PositionWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        draw(ctx, mgr, ids, PositionRadar.latest(ctx))
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) {
        draw(ctx, mgr, intArrayOf(id), PositionRadar.latest(ctx))
    }

    companion object {
        private const val TAG = "PositionWidget"

        /** When a tap on ↻ started a refresh that has not finished (0 = none). The
         *  time line reads "refreshing…" meanwhile, so the tap visibly did something. */
        @Volatile private var refreshingSince = 0L

        /** The ↻ was tapped: say so at once, before the prices come back. */
        fun markRefreshing(ctx: Context) {
            refreshingSince = System.currentTimeMillis()
            update(ctx, PositionRadar.latest(ctx))
        }

        /** The refresh finished (or failed) — back to the "updated HH:MM" line. */
        fun clearRefreshing() { refreshingSince = 0L }

        /** Redraw every placed copy of the widget. A no-op when none is placed. */
        fun update(ctx: Context, s: PositionRadar.Summary?) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, PositionWidget::class.java))
                if (ids.isNotEmpty()) draw(ctx, mgr, ids, s)
            } catch (e: Exception) { Log.w(TAG, "widget update failed", e) }
        }

        private fun draw(ctx: Context, mgr: AppWidgetManager, ids: IntArray, s: PositionRadar.Summary?) {
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent()
            val open = PendingIntent.getActivity(ctx, 4722, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            // Every line, not the six the notification had room for. Gson leaves
            // the newer field null on a summary saved before it existed.
            val lines = s?.let { (it.full as List<String>?) ?: (it.lines as List<String>?) }.orEmpty()
            // ↻ — straight to the background service, which re-reads every position
            // and price and redraws this widget; the app itself does not open.
            val refreshIntent = com.signalscope.app.service.ScanService.createIntent(
                ctx, com.signalscope.app.service.ScanService.ACTION_WIDGET_REFRESH)
            val refresh = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                PendingIntent.getForegroundService(ctx, 4724, refreshIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            else PendingIntent.getService(ctx, 4724, refreshIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            // A refresh that never reported back (the app was killed mid-way) must
            // not leave the widget saying "refreshing…" for ever.
            val busy = refreshingSince > 0L && System.currentTimeMillis() - refreshingSince < 3 * 60_000L
            for (id in ids) {
                val v = RemoteViews(ctx.packageName, R.layout.widget_positions)
                v.setOnClickPendingIntent(R.id.wp_root, open)
                v.setOnClickPendingIntent(R.id.wp_refresh, refresh)
                v.setTextViewText(R.id.wp_time, if (busy) "refreshing…" else s?.let {
                    "updated " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                        .format(java.util.Date(it.atMs))
                } ?: "")
                if (s == null) {
                    v.setTextViewText(R.id.wp_headline, "Nothing held by the app right now")
                    v.setViewVisibility(R.id.wp_list, View.GONE)
                    v.setViewVisibility(R.id.wp_empty, View.VISIBLE)
                    v.setTextViewText(R.id.wp_empty,
                        "It fills in on the next background pass after a buy — every 15 minutes.")
                } else {
                    v.setTextViewText(R.id.wp_headline, s.headline)
                    if (lines.isEmpty()) {
                        v.setViewVisibility(R.id.wp_list, View.GONE)
                        v.setViewVisibility(R.id.wp_empty, View.VISIBLE)
                        v.setTextViewText(R.id.wp_empty, "${s.held} held · nothing near a stop, target or high")
                    } else if (android.os.Build.VERSION.SDK_INT >= 31) {
                        // A scrolling list, built in one go — no list service needed
                        // on Android 12 and later.
                        v.setViewVisibility(R.id.wp_list, View.VISIBLE)
                        v.setViewVisibility(R.id.wp_empty, View.GONE)
                        val items = RemoteViews.RemoteCollectionItems.Builder()
                            .setHasStableIds(false).setViewTypeCount(1)
                        // The market's flag, the line, and — smaller, on a second line —
                        // what manages the exit. Older summaries have only the lines.
                        val rows = (s.rows as List<PositionRadar.Row>?)
                        val texts: List<CharSequence> = rows?.map { r ->
                            val sb = android.text.SpannableStringBuilder()
                            if (r.flag.isNotBlank()) sb.append(r.flag).append(' ')
                            sb.append(r.text)
                            if (r.detail.isNotBlank()) {
                                val from = sb.length
                                sb.append("\n      ").append(r.detail)
                                sb.setSpan(android.text.style.RelativeSizeSpan(0.82f), from, sb.length, 0)
                                sb.setSpan(android.text.style.ForegroundColorSpan(0x99FFFFFF.toInt()), from, sb.length, 0)
                            }
                            sb
                        } ?: lines
                        texts.forEachIndexed { i, line ->
                            items.addItem(i.toLong(), RemoteViews(ctx.packageName, R.layout.widget_positions_row).apply {
                                setTextViewText(R.id.wp_row, line)
                                setOnClickFillInIntent(R.id.wp_row, Intent())
                            })
                        }
                        v.setRemoteAdapter(R.id.wp_list, items.build())
                        // Tapping a line opens the app, like tapping anywhere else.
                        v.setPendingIntentTemplate(R.id.wp_list, PendingIntent.getActivity(ctx, 4723, launch,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
                    } else {
                        // Older phones: the same lines as plain text, top to bottom.
                        v.setViewVisibility(R.id.wp_list, View.GONE)
                        v.setViewVisibility(R.id.wp_empty, View.VISIBLE)
                        v.setTextViewText(R.id.wp_empty, lines.joinToString(separator = System.lineSeparator()))
                    }
                }
                mgr.updateAppWidget(id, v)
            }
        }
    }
}

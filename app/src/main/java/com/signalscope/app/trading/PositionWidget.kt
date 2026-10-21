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

    /** A tab was tapped: remember it for that copy of the widget and redraw it. */
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION_TAB) { super.onReceive(ctx, intent); return }
        val id = intent.getIntExtra(EXTRA_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val dim = intent.getStringExtra(EXTRA_DIM) ?: return
        val code = intent.getStringExtra(EXTRA_CODE) ?: return
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val p = prefs(ctx)
        // Tapping the kind you are already on goes back to All.
        val next = if (dim == "k" && p.getString("k_$id", WidgetTabs.ALL) == code) WidgetTabs.ALL else code
        p.edit().putString("${dim}_$id", next).apply()
        try { draw(ctx, AppWidgetManager.getInstance(ctx), intArrayOf(id), PositionRadar.latest(ctx)) }
        catch (e: Exception) { Log.w(TAG, "tab redraw failed", e) }
    }

    companion object {
        private const val TAG = "PositionWidget"
        const val ACTION_TAB = "com.signalscope.WIDGET_TAB"
        private const val EXTRA_ID = "id"
        private const val EXTRA_DIM = "dim"
        private const val EXTRA_CODE = "code"

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("position_widget", Context.MODE_PRIVATE)

        private val M_CHIPS = intArrayOf(R.id.wp_m0, R.id.wp_m1, R.id.wp_m2, R.id.wp_m3, R.id.wp_m4)
        private val K_CHIPS = intArrayOf(R.id.wp_k0, R.id.wp_k1, R.id.wp_k2, R.id.wp_k3, R.id.wp_k4, R.id.wp_k5)

        /** One row of pills: each shows its count, the chosen one filled, and a tap filters on it. */
        private fun drawChips(ctx: Context, v: RemoteViews, slots: IntArray, chips: List<WidgetTabs.Chip>,
                              id: Int, dim: String) {
            slots.forEachIndexed { i, slot ->
                val c = chips.getOrNull(i)
                if (c == null) { v.setViewVisibility(slot, View.GONE); return@forEachIndexed }
                v.setViewVisibility(slot, View.VISIBLE)
                v.setTextViewText(slot, c.text)
                v.setInt(slot, "setBackgroundResource",
                    if (c.selected) R.drawable.widget_chip_on else R.drawable.widget_chip_off)
                v.setTextColor(slot, if (c.selected) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt())
                val tap = Intent(ctx, PositionWidget::class.java).setAction(ACTION_TAB)
                    .putExtra(EXTRA_ID, id).putExtra(EXTRA_DIM, dim).putExtra(EXTRA_CODE, c.code)
                    // Distinct data per pill, or Android hands every pill the same intent.
                    .setData(android.net.Uri.parse("signalscope://widget-tab/$id/$dim/${c.code}"))
                v.setOnClickPendingIntent(slot, PendingIntent.getBroadcast(ctx, 0, tap,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
        }

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

        private val TILE_TXT = intArrayOf(R.id.wp_ix0_txt, R.id.wp_ix1_txt, R.id.wp_ix2_txt)
        private val TILE_IMG = intArrayOf(R.id.wp_ix0_img, R.id.wp_ix1_img, R.id.wp_ix2_img)

        /**
         * One tile per index, in [IndexStrip.INDICES] order: "DAX +0.42%", the
         * change in green or red, and the session's line. A session that is not
         * today on its own exchange's clock (a weekend, a holiday, before the
         * open) says which day it is, so an old line never passes for a live one.
         * Empty until the first reading has arrived; an index with no reading yet
         * shows its name and a dash rather than hiding the other two.
         */
        private fun indexTiles(ctx: Context): List<Pair<CharSequence, android.graphics.Bitmap?>> {
            val st = IndexStrip.latest(ctx) ?: return emptyList()
            val dm = ctx.resources.displayMetrics
            val w = (110 * dm.density).toInt()
            val h = (20 * dm.density).toInt()
            if (st.readings.isEmpty()) return emptyList()
            return IndexStrip.INDICES.take(3).map { ix ->
                val r = st.readings.firstOrNull { it.key == ix.key }
                    ?: return@map ("${ix.label} —" as CharSequence) to null
                // The current name, not the one saved with an older reading.
                val sb = android.text.SpannableStringBuilder(ix.label).append(' ')
                val pct = String.format(java.util.Locale.US, "%+.2f%%", r.pct).replace('-', '−')
                val from = sb.length
                sb.append(pct)
                sb.setSpan(android.text.style.ForegroundColorSpan(
                    if (r.pct >= 0.0) 0xFF34D399.toInt() else 0xFFF87171.toInt()), from, sb.length, 0)
                sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), from, sb.length, 0)
                val zone = MarketHours.zoneOf(ix.space) ?: java.time.ZoneId.of("UTC")
                val today = java.time.LocalDate.now(zone).toString()
                if (r.day.isNotEmpty() && r.day != today) {
                    val wd = try {
                        java.time.LocalDate.parse(r.day).dayOfWeek
                            .getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.UK)
                    } catch (e: Exception) { r.day }
                    val f2 = sb.length
                    sb.append(" ").append(wd)
                    sb.setSpan(android.text.style.ForegroundColorSpan(0x99FFFFFF.toInt()), f2, sb.length, 0)
                }
                (sb as CharSequence) to IndexStrip.sparkline(r, w, h, dm.density)
            }
        }

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
            val markets = indexTiles(ctx)
            for (id in ids) {
                val v = RemoteViews(ctx.packageName, R.layout.widget_positions)
                if (markets.isEmpty()) v.setViewVisibility(R.id.wp_indices, View.GONE)
                else {
                    v.setViewVisibility(R.id.wp_indices, View.VISIBLE)
                    markets.forEachIndexed { i, (text, img) ->
                        v.setTextViewText(TILE_TXT[i], text)
                        if (img != null) v.setImageViewBitmap(TILE_IMG[i], img)
                    }
                }
                v.setOnClickPendingIntent(R.id.wp_root, open)
                v.setOnClickPendingIntent(R.id.wp_refresh, refresh)
                v.setTextViewText(R.id.wp_time, if (busy) "refreshing…" else s?.let {
                    "updated " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                        .format(java.util.Date(it.atMs))
                } ?: "")
                // THE TABS, per copy of the widget: its own market and kind.
                val allRows = (s?.rows as List<PositionRadar.Row>?).orEmpty()
                val tabs = if (allRows.isEmpty()) null else WidgetTabs.view(allRows,
                    prefs(ctx).getString("m_$id", WidgetTabs.ALL) ?: WidgetTabs.ALL,
                    prefs(ctx).getString("k_$id", WidgetTabs.ALL) ?: WidgetTabs.ALL)
                if (tabs == null) {
                    v.setViewVisibility(R.id.wp_mtabs, View.GONE)
                    v.setViewVisibility(R.id.wp_ktabs, View.GONE)
                    v.setViewVisibility(R.id.wp_headline, View.VISIBLE)
                } else {
                    v.setViewVisibility(R.id.wp_mtabs, View.VISIBLE)
                    v.setViewVisibility(R.id.wp_ktabs, View.VISIBLE)
                    // The kinds' pills carry the headline's counts — it would only repeat them.
                    v.setViewVisibility(R.id.wp_headline, View.GONE)
                    drawChips(ctx, v, M_CHIPS, tabs.markets, id, "m")
                    drawChips(ctx, v, K_CHIPS, tabs.kinds, id, "k")
                }
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
                        val rows = tabs?.rows ?: (s.rows as List<PositionRadar.Row>?)
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
                        v.setTextViewText(R.id.wp_empty, (tabs?.rows?.map { it.text } ?: lines)
                            .joinToString(separator = System.lineSeparator()))
                    }
                }
                mgr.updateAppWidget(id, v)
            }
        }
    }
}

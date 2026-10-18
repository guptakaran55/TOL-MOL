package com.signalscope.app.trading

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.signalscope.app.data.DummyGttStore
import com.signalscope.app.ui.MainActivity
import java.util.Locale

/**
 * Watches the live Yahoo price stream against the user's app-only "dummy" GTT
 * levels ([DummyGttStore]). When the live price crosses an SL or target level
 * it fires a strong vibration + high-priority notification so the user can
 * place the real order manually (essential for Trading 212, where a
 * simultaneous SL+target OCO can't be set natively).
 *
 * It places NO broker orders and mutates NO order state — it only reads the
 * dummy store, stamps "notified" so it doesn't re-alert every tick, and posts
 * a notification. Safe to call from the stream thread on every quote.
 */
object DummyGttMonitor {

    private const val TAG = "DummyGttMonitor"
    const val CHANNEL_DUMMY_GTT = "signalscope_dummy_gtt"

    @Volatile private var channelReady = false

    /**
     * @param yahooSymbol the quote's Yahoo symbol (e.g. "ENR.DE")
     * @param ltp         the live last-traded price
     * Returns a short human label of what fired (for optional UI echo), or null.
     */
    fun onQuote(ctx: Context, yahooSymbol: String, ltp: Double): String? {
        if (ltp <= 0.0) return null
        val key = yahooSymbol.trim().uppercase(Locale.US)
        // Trading 212 spaces only (US/DE). Zerodha (IN) can place real
        // multi-leg GTTs, so dummy alerts are intentionally not used there.
        val matches = DummyGttStore.load(ctx).filter {
            it.yahooSymbol.trim().uppercase(Locale.US) == key &&
                !it.space.equals("IN", ignoreCase = true)
        }
        if (matches.isEmpty()) return null

        var fired: String? = null
        for (g in matches) {
            // Target: price rising to/through the target level.
            val tgt = g.targetPrice
            if (tgt != null && tgt > 0.0 && g.targetNotifiedAt == null && ltp >= tgt) {
                notify(ctx, g.id, DummyGttStore.Side.TARGET, g.symbol, g.space, ltp, tgt, g.qty)
                DummyGttStore.markNotified(ctx, g.id, DummyGttStore.Side.TARGET)
                fired = "${g.symbol} target ${fmt(tgt)} reached"
            }
            // SL: price falling to/through the stop level.
            val sl = g.slPrice
            if (sl != null && sl > 0.0 && g.slNotifiedAt == null && ltp <= sl) {
                notify(ctx, g.id, DummyGttStore.Side.SL, g.symbol, g.space, ltp, sl, g.qty)
                DummyGttStore.markNotified(ctx, g.id, DummyGttStore.Side.SL)
                fired = "${g.symbol} stop ${fmt(sl)} reached"
            }
        }
        return fired
    }

    private fun notify(
        ctx: Context, id: String, side: DummyGttStore.Side,
        symbol: String, space: String, ltp: Double, level: Double, qty: Double
    ) {
        try {
            ensureChannel(ctx)
            vibrate(ctx, side)

            val cur = when (space.uppercase(Locale.US)) {
                "US" -> "$"; "DE" -> "€"; else -> "₹"
            }
            val isTarget = side == DummyGttStore.Side.TARGET
            val title = if (isTarget) "🎯 Target reached — $symbol" else "🛡 Stop reached — $symbol"
            val qtyStr = if (qty > 0.0) " · qty ${fmt(qty)}" else ""
            val body = buildString {
                append(if (isTarget) "Target $cur${fmt(level)} hit" else "Stop $cur${fmt(level)} hit")
                append(" (live $cur${fmt(ltp)})$qtyStr.\n")
                append("Place your $space order manually after review.")
            }

            val pi = PendingIntent.getActivity(
                ctx, (id + side.name).hashCode(),
                Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL_DUMMY_GTT)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pi)
                .setColor(if (isTarget) 0xFF22C55E.toInt() else 0xFFDC2626.toInt())
                .setColorized(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .build()

            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            com.signalscope.app.data.NotificationLog.post(ctx, nm, (id + side.name).hashCode(), n)
        } catch (e: Exception) {
            Log.e(TAG, "notify failed for $symbol", e)
        }
    }

    private fun ensureChannel(ctx: Context) {
        if (channelReady) return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_DUMMY_GTT, "Dummy GTT alerts", NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Vibrates and alerts when a watched SL/target price is reached"
                    enableVibration(true)
                    setShowBadge(true)
                }
            )
            channelReady = true
        } catch (e: Exception) {
            Log.e(TAG, "channel create failed", e)
        }
    }

    private fun vibrate(ctx: Context, side: DummyGttStore.Side) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            // Target = upbeat triple buzz; SL = urgent long-long.
            val pattern = if (side == DummyGttStore.Side.TARGET)
                longArrayOf(0, 200, 100, 200, 100, 200)
            else
                longArrayOf(0, 450, 180, 450)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "vibrate failed", e)
        }
    }

    private fun fmt(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(Locale.US, "%.2f", v)
}

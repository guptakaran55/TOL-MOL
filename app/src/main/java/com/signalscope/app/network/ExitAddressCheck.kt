package com.signalscope.app.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.signalscope.app.data.ConfigManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Confirms the phone is still reaching the internet through the whitelisted
 * address before real money is put at risk.
 *
 * WHY
 * Zerodha only accepts live orders from an address registered with them. This
 * phone gets that fixed address by tunnelling to a rented gateway. Tunnels drop
 * — the carrier hands over to a different cell, the gateway reboots, someone
 * toggles the wrong switch — and when one drops the traffic quietly leaves via
 * the carrier instead. The broker then refuses the order for a reason that
 * looks nothing like "your VPN is down", and the first anyone knows of it is a
 * missed entry discovered hours later.
 *
 * So before placing, we ask the outside world what address it sees us as. If it
 * isn't the whitelisted one, the order is refused HERE, loudly, with a
 * notification naming the real cause.
 *
 * WHAT IT REFUSES TO DO
 * It never blocks on ignorance. If the lookup itself fails — the echo service is
 * down, the network is flaky — the order goes through as it always did. Being
 * unable to reach a third-party address service is not evidence that anything is
 * wrong with the tunnel, and letting an unrelated outage halt trading would be a
 * worse failure than the one this guards against. Only a positive, contradictory
 * answer stops an order.
 */
object ExitAddressCheck {

    private const val TAG = "ExitAddressCheck"
    private const val CHANNEL = "exit_address_alerts"
    private const val NOTIFICATION_ID = 0x14D0

    /** Plain-text address echoes. Tried in order; first usable answer wins. */
    private val ECHO_URLS = listOf(
        "https://api.ipify.org",
        "https://icanhazip.com",
        "https://ifconfig.me/ip"
    )

    /**
     * Two minutes. Short enough that a tunnel that died mid-session is caught
     * before the next order, long enough that a burst of orders doesn't make one
     * lookup per order against a free public service.
     */
    private const val CACHE_MS = 120_000L

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    @Volatile private var cachedIp: String? = null
    @Volatile private var cachedAt = 0L
    @Volatile private var lastNotifiedAt = 0L

    sealed class Verdict {
        /** Seen address matches the whitelisted one, or no whitelist is configured. */
        object Ok : Verdict()
        /** We know the address and it is NOT the whitelisted one. */
        data class Mismatch(val seen: String, val expected: String) : Verdict()
        /** Could not find out. Deliberately treated as permission to proceed. */
        data class Unknown(val reason: String) : Verdict()
    }

    /** Blocking. The address the internet currently sees this phone as, or null. */
    fun currentAddress(force: Boolean = false): String? {
        val now = System.currentTimeMillis()
        if (!force) {
            val c = cachedIp
            if (c != null && now - cachedAt < CACHE_MS) return c
        }
        for (url in ECHO_URLS) {
            try {
                http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    val text = resp.body?.string()?.trim().orEmpty()
                    if (resp.isSuccessful && looksLikeAddress(text)) {
                        cachedIp = text
                        cachedAt = now
                        return text
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "address echo $url failed: ${e.message}")
            }
        }
        return null
    }

    /** Accepts both families — the tunnel carries v6 as well, and either can be whitelisted. */
    private fun looksLikeAddress(s: String): Boolean =
        s.isNotBlank() && s.length <= 45 && s.none { it.isWhitespace() } &&
            (s.count { it == '.' } == 3 || s.contains(':'))

    /**
     * The pre-flight itself. Records what it saw so the settings screen can show
     * it, and notifies once per half hour on a genuine mismatch — enough to be
     * unmissable, not enough to become forty identical alerts during one session.
     */
    fun verify(config: ConfigManager, force: Boolean = false): Verdict {
        val expected = config.zerodhaWhitelistedIp.trim()
        if (expected.isBlank()) return Verdict.Ok   // feature not configured — stay out of the way

        val seen = currentAddress(force)
            ?: return Verdict.Unknown("couldn't reach any address-echo service")

        config.lastSeenPublicIp = seen
        config.lastSeenPublicIpAt = System.currentTimeMillis()

        if (seen.equals(expected, ignoreCase = true)) return Verdict.Ok

        notifyMismatch(config.appContext, seen, expected)
        return Verdict.Mismatch(seen, expected)
    }

    private fun notifyMismatch(ctx: Context, seen: String, expected: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotifiedAt < 30 * 60 * 1000L) return
        lastNotifiedAt = now
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL, "Order gateway alerts", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Live orders blocked because the VPN tunnel is down" })
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                ?: android.content.Intent()
            val pi = android.app.PendingIntent.getActivity(
                ctx, NOTIFICATION_ID, launch,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE)
            com.signalscope.app.data.NotificationLog.post(ctx, nm, NOTIFICATION_ID, androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL)
                .setContentTitle("Live orders blocked — VPN tunnel is down")
                .setContentText("Seen as $seen, whitelisted is $expected")
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(
                    "Zerodha only accepts orders from $expected, but this phone is " +
                    "currently reaching the internet as $seen.\n\n" +
                    "No order was sent. Turn the WireGuard tunnel back on; existing " +
                    "GTT triggers are parked at the broker and are unaffected."))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build())
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't post exit-address notification", e)
        }
    }
}

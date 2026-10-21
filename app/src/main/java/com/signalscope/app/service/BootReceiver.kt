package com.signalscope.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.signalscope.app.data.ConfigManager

/**
 * Restarts the scan service after phone reboot, if it was running before.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // AND AFTER AN APP UPDATE. Without it an update stopped monitoring and
        // nothing restarted it until the next reboot — while the top bar still
        // showed it as on, and the Indian holdings' daily stop maintenance, which
        // runs inside it, silently skipped the days in between.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {

            // THE POSITIONS WIDGET, REDRAWN AT ONCE. An update that changes its
            // layout leaves the home screen holding a drawing made for the old one,
            // which shows as a blank panel until the next pass — up to 15 minutes.
            try {
                com.signalscope.app.trading.PositionWidget.update(context,
                    com.signalscope.app.trading.PositionRadar.latest(context))
            } catch (e: Exception) { Log.w("BootReceiver", "widget redraw failed", e) }

            val config = ConfigManager(context)
            if (config.serviceRunning && config.hasCredentials) {
                Log.i("BootReceiver", "Restarting ScanService after boot")
                val serviceIntent = ScanService.createIntent(context, ScanService.ACTION_START)
                context.startForegroundService(serviceIntent)
            } else {
                // Even if portfolio monitoring isn't active, re-arm the auto-discovery
                // alarm chain so scheduled scans survive reboots independently.
                DiscoveryAutoScheduler.ensureScheduled(context)
                T212DailyTrailScheduler.ensureScheduled(context)
                StrategyLabScheduler.ensureScheduled(context)
                // Especially this one: a reboot overnight is exactly when the
                // morning login would otherwise be lost, and nothing else in the
                // app would notice until the Indian session was already over.
                ZerodhaAutoLoginScheduler.ensureScheduled(context)
            }
        }
    }
}

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
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {

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
            }
        }
    }
}

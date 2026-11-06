package com.signalscope.app

import android.app.Application

class SignalScopeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before anything fetches a price: every chart reply from here on is read
        // for whether its feed has stopped, and the verdicts live on disk.
        com.signalscope.app.network.FeedHealth.init(this)
        // Long price histories kept a day at a time on the phone — see BarHistory.
        com.signalscope.app.network.BarHistory.init(this)
    }

    /** The screen went to the background, or the phone is short of memory — see
     *  PageWarmup.release. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        try { com.signalscope.app.ui.PageWarmup.release(level) } catch (_: Exception) {}
    }
}

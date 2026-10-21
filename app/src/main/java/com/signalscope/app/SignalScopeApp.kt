package com.signalscope.app

import android.app.Application

class SignalScopeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before anything fetches a price: every chart reply from here on is read
        // for whether its feed has stopped, and the verdicts live on disk.
        com.signalscope.app.network.FeedHealth.init(this)
    }
}

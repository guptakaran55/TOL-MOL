package com.signalscope.app.trading

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The Buy and Skip buttons on a buy request (see [FloorAsks]).
 *
 * The tap is queued with its time before anything else, so it counts even if the
 * settling below has to wait for a pass that holds the engine's lock; the alert
 * comes down at once. Settling runs on its own thread — the broadcast must finish
 * within seconds, and a pass can hold the lock for minutes.
 */
class FloorAskReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_ALGO = "floor_ask_algo"
        const val EXTRA_ASK = "floor_ask_id"
        const val EXTRA_BUY = "floor_ask_buy"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val algo = intent.getStringExtra(EXTRA_ALGO) ?: return
        val ask = intent.getStringExtra(EXTRA_ASK) ?: return
        val buy = intent.getBooleanExtra(EXTRA_BUY, false)
        val tap = System.currentTimeMillis()
        val app = ctx.applicationContext
        FloorAsks.queue(app, algo, ask, buy, tap)
        try { LabNotifier.floorAskDone(app, algo, ask) } catch (_: Exception) {}
        Thread({
            try {
                val msg = FloorAlgoEngine.answer(app, algo, ask, buy, tap)
                Log.i("FloorAsk", "$ask: ${if (buy) "Buy" else "Skip"} — $msg")
            } catch (e: Exception) { Log.w("FloorAsk", "settling $ask failed — the next pass settles it", e) }
        }, "floor-ask").start()
    }
}

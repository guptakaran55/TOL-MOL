package com.signalscope.app.trading

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Trade notifications for the crypto algo — the user was flying blind on when
 * the lab opened/closed positions unless they happened to have the app open.
 *
 * Two channels, deliberately separate so they can be silenced independently:
 *  - LIVE  (high importance)    — real money moved on Binance
 *  - PAPER (default importance) — the simulated lab opened/closed a trade
 */
object LabNotifier {
    private const val TAG = "LabNotifier"
    /** Read by MainActivity to open the algo the notification is about. */
    const val EXTRA_OPEN_NS = "open_algo_ns"
    private const val CH_LIVE = "binance_live_trades"
    private const val CH_PAPER = "lab_paper_trades"

    /**
     * [openNs] deep-links the tap to one algo. Without it the notification opens
     * the app wherever it was left, which on a six-algo account means reading the
     * name, switching space, finding the card and opening it by hand — for an
     * event the notification already knew the exact location of.
     */
    private fun notify(ctx: Context, channel: String, importance: Int,
                       channelName: String, title: String, text: String, idSeed: String,
                       openNs: String? = null) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(channel, channelName, importance))
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                ?: Intent()
            if (!openNs.isNullOrBlank()) {
                launch.putExtra(EXTRA_OPEN_NS, openNs)
                // Without this the system may hand back the EXISTING intent for an
                // already-running task and the extra is silently dropped — the tap
                // then just brings the app forward, doing nothing.
                launch.action = Intent.ACTION_VIEW
                launch.data = android.net.Uri.parse("signalscope://algo/$openNs")
            }
            val pi = PendingIntent.getActivity(ctx, idSeed.hashCode(), launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(ctx, channel)
                .setContentTitle(title).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_menu_sort_by_size)
                .setContentIntent(pi).setAutoCancel(true)
                .build()
            // Unique id per trade event so a burst of fills doesn't overwrite itself.
            nm.notify(idSeed.hashCode(), n)
        } catch (e: Exception) { Log.w(TAG, "notify failed", e) }
    }

    fun liveBuy(ctx: Context, symbol: String, quoteSpent: Double, qty: String) =
        notify(ctx, CH_LIVE, NotificationManager.IMPORTANCE_HIGH, "Binance LIVE trades",
            "🟢 LIVE BUY $symbol",
            "Bought $qty for ${"%.2f".format(quoteSpent)} USDC (real order on Binance).",
            "lb-$symbol-$qty")

    fun liveSell(ctx: Context, symbol: String, proceeds: Double, pnl: Double, reason: String) =
        notify(ctx, CH_LIVE, NotificationManager.IMPORTANCE_HIGH, "Binance LIVE trades",
            (if (pnl >= 0) "🔴 LIVE SELL $symbol  (+" else "🔴 LIVE SELL $symbol  (") +
                "%.2f USDC)".format(pnl),
            "Sold for ${"%.2f".format(proceeds)} USDC · P&L ${"%+.2f".format(pnl)} USDC" +
                (if (reason.isNotBlank()) " · $reason" else ""),
            "ls-$symbol-${System.currentTimeMillis() / 60000}")

    /** The algo's own name, falling back to the namespace when it has none —
     *  "B_Conservative" is what the card says, and matching it is the whole point. */
    private fun algoLabel(ctx: Context, ns: String): String = try {
        com.signalscope.app.data.BucketStore.get(ctx, ns)?.name ?: ns.uppercase()
    } catch (e: Exception) { ns.uppercase() }

    private fun spaceLabel(ns: String): String =
        when (com.signalscope.app.trading.Spaces.baseOf(ns)) {
            "de" -> "Germany"; "in" -> "India"; "binance" -> "Crypto"; else -> "USA"
        }

    /** Silent when paper notifications are switched off — checked here rather
     *  than at each call site so no future caller can bypass the preference. */
    private fun paperMuted(ctx: Context): Boolean = try {
        !com.signalscope.app.data.ConfigManager(ctx).paperTradeNotifications
    } catch (e: Exception) { false }

    fun paperOpen(ctx: Context, ns: String, symbol: String, price: Double) {
        if (paperMuted(ctx)) return
        notify(ctx, CH_PAPER, NotificationManager.IMPORTANCE_DEFAULT, "Lab paper trades",
            "📈 ${algoLabel(ctx, ns)} bought $symbol (paper)",
            "${spaceLabel(ns)} · opened a paper position @ ${"%.4f".format(price)}." +
                " Tap to open this algo.",
            "po-$ns-$symbol-$price", openNs = ns)
    }

    fun paperClose(ctx: Context, ns: String, symbol: String, pctReturn: Double, reason: String) {
        if (paperMuted(ctx)) return
        notify(ctx, CH_PAPER, NotificationManager.IMPORTANCE_DEFAULT, "Lab paper trades",
            "📉 ${algoLabel(ctx, ns)} sold $symbol  (${"%+.2f".format(pctReturn)}%)",
            "${spaceLabel(ns)} · closed @ ${"%+.2f".format(pctReturn)}%" +
                (if (reason.isNotBlank()) " · $reason" else "") +
                " Tap to open this algo.",
            "pc-$ns-$symbol-$pctReturn", openNs = ns)
    }

    /**
     * The ledger and the broker disagree about a POSITION, not a price.
     *
     * HIGH importance on the live channel deliberately: every other number the app
     * shows is computed off the position, so while this is unresolved the P&L, the
     * seat balances and the next entry size are all quietly wrong. This is the one
     * background finding that should interrupt.
     *
     * The id folds in the hour, so a persistent drift re-notifies once an hour
     * instead of silently collapsing into the first notification and being missed.
     */
    /**
     * A LADDER THAT RAN AND DID NOT FILL, while the market was open.
     *
     * Worth interrupting for, unlike the other decline reasons. A frozen switch
     * or an after-hours signal are states you can reason about later; this one is
     * a live opportunity that is quietly slipping away — the strategy wanted the
     * position, the market was there to sell it, and the chase simply did not
     * reach. It is also the only case where a human can still act: the name can
     * be bought by hand from the Portfolio tab while the price is near.
     *
     * Deliberately NOT sent for a closed market. Telling someone to go and place
     * a manual order at 18:01 on a shut exchange is advice they cannot take, and
     * a notification that cannot be acted on is the kind people learn to swipe
     * away — taking the useful ones with it.
     */
    fun ladderUnfilled(ctx: Context, algo: String, symbol: String,
                       amount: Double, currency: String, note: String) =
        notify(ctx, CH_LIVE, NotificationManager.IMPORTANCE_HIGH, "Binance LIVE trades",
            "⏳ $symbol not filled — buy it by hand?",
            "$algo wanted ${"%.0f".format(amount)} $currency of $symbol and the patient " +
                "ladder walked its whole range without filling" +
                (if (note.isNotBlank()) " ($note)" else "") + ".\n\n" +
                "The market is open, so you can still take it: open Portfolio, find " +
                "$symbol and use Patient buy — or Buy now if you want it immediately. " +
                "The paper trade is already open, so doing nothing leaves paper and " +
                "real disagreeing on this name.",
            "lu-$symbol-${System.currentTimeMillis() / 600_000}")

    /**
     * AN EXIT THAT DID NOT FILL. Louder than the entry case, deliberately.
     *
     * A missed entry costs an opportunity. A missed exit leaves real money in a
     * position the strategy has already decided to leave — still exposed, and no
     * longer managed by anything except the hope that the next attempt does
     * better. The paper book has already booked the sale, so every figure that
     * follows is describing a position you still hold.
     */
    fun ladderExitUnfilled(ctx: Context, algo: String, symbol: String,
                           qty: Double, note: String) =
        notify(ctx, CH_LIVE, NotificationManager.IMPORTANCE_HIGH, "Binance LIVE trades",
            "⚠ $symbol did NOT sell — you still hold it",
            "$algo tried to exit ${"%.5f".format(qty).trimEnd('0').trimEnd('.')} of $symbol " +
                "and the ladder filled nothing" + (if (note.isNotBlank()) " ($note)" else "") + ".\n\n" +
                "Paper has already booked this exit, so the algo's P&L now describes a " +
                "position you are still exposed to. The market is open — sell it by hand " +
                "from Portfolio (Patient sell, or Sell now to be out immediately).",
            "lx-$symbol-${System.currentTimeMillis() / 600_000}")

    fun positionDrift(ctx: Context, count: Int) =
        notify(ctx, CH_LIVE, NotificationManager.IMPORTANCE_HIGH, "Binance LIVE trades",
            "⚠ Position mismatch ($count)",
            "The app's ledger disagrees with Trading 212 about $count holding" +
                (if (count == 1) "" else "s") + ". Open Algos to see the diff — P&L is unreliable until it is resolved.",
            "pd-${System.currentTimeMillis() / 3_600_000}")
}

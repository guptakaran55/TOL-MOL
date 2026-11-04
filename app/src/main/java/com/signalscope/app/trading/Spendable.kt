package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.BucketStore
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.FloorAlgoStore
import com.signalscope.app.data.FloorLiveStore
import com.signalscope.app.data.PriceEntryStore
import com.signalscope.app.data.T212LiveStore
import com.signalscope.app.network.Trading212Client

/**
 * WHAT IS REALLY YOURS TO SPEND at Trading 212 — shown beside every buy amount.
 *
 * Trading 212's free cash already leaves out what its own resting orders hold.
 * It cannot know what THIS APP has promised and not yet placed: your buys waiting
 * on the RV gate, your "buy at price" entries, and the money an armed algo will
 * still spend on its seats. Spend that cash by hand and those buys find nothing
 * there when their moment comes. So: free cash, less the app's commitments.
 *
 * Amounts are in the account's currency; a US buy's dollars are converted at the
 * rate the ladders use (FxRates).
 */
object Spendable {
    private const val TAG = "Spendable"

    data class Part(val label: String, val amount: Double, val detail: String)

    /** Pure: what is left once [parts] are taken from [free]. */
    fun yours(free: Double, parts: List<Part>): Double = free - parts.sumOf { it.amount }

    /**
     * Network when [refresh] (Trading 212's cash, one read) or when a dollar rate
     * is not known yet; otherwise local reads only.
     */
    fun compute(ctx: Context, refresh: Boolean): Map<String, Any?> {
        val cfg = ConfigManager(ctx)
        if (!cfg.hasT212Credentials) return mapOf("ok" to false, "error" to "Trading 212 is not set up in Settings.")
        val acct = cfg.accountCurrency.ifBlank { "EUR" }.uppercase()
        var refreshError = ""
        if (refresh) try {
            when (val s = Trading212Client(cfg).fetchAccountSummary()) {
                is Trading212Client.T212Result.Success -> {
                    cfg.t212FreeFundsCached = s.data.freeFunds
                    cfg.t212FreeFundsAt = System.currentTimeMillis()
                }
                is Trading212Client.T212Result.Failure -> refreshError = s.message
                else -> refreshError = "not set up"
            }
        } catch (e: Exception) { refreshError = e.message ?: "read failed" }
        val free = cfg.t212FreeFundsCached
        val at = cfg.t212FreeFundsAt
        val usdPerAcct = try { FxRates.rate(ctx, acct, "USD").rate } catch (_: Exception) { 1.0 }
        // Units of the space's currency per one unit of the account's.
        fun toAcct(amount: Double, space: String): Double {
            val cur = if (space.equals("US", true)) "USD" else "EUR"
            val r = if (cur == acct) 1.0 else if (cur == "USD") usdPerAcct else
                try { FxRates.rate(ctx, acct, cur).rate } catch (_: Exception) { 1.0 }
            return if (r > 0.0) amount / r else amount
        }
        val parts = ArrayList<Part>()
        try {
            val rv = RvManualQueue.all(ctx).filter { (it.status == "WAITING" || it.status == "ASK") && !it.isSell && it.cash > 0.0 }
            if (rv.isNotEmpty()) parts += Part("your buys waiting on the RV gate",
                rv.sumOf { toAcct(it.cash, it.space) }, rv.joinToString(", ") { it.symbol })
        } catch (e: Exception) { Log.w(TAG, "RV gate orders unreadable", e) }
        try {
            val pe = PriceEntryStore.waiting(ctx)
            if (pe.isNotEmpty()) parts += Part("your buy-at-price entries",
                pe.sumOf { toAcct(it.cash, it.space) }, pe.joinToString(", ") { it.symbol })
        } catch (e: Exception) { Log.w(TAG, "price entries unreadable", e) }
        // ARMED ALGOS: what each will still spend on its seats — the same figure the
        // Algos tab's cash check uses.
        val algos = ArrayList<String>()
        var algoSum = 0.0
        try {
            val rows = T212LiveStore.load(ctx).trades
            for (n in BucketStore.allNamespaces(ctx)) {
                if (Spaces.baseOf(n).let { it != "de" && it != "us" }) continue
                val b = BucketStore.get(ctx, n) ?: continue
                if (!b.live || b.liveBudget <= 0.0 || b.liveAllowlist.isEmpty()) continue
                val spent = rows.filter { it.ns == n && it.status in setOf("OPEN", "PENDING", "EXITING") }.sumOf { it.liveEntryValue }
                val seats = b.holdN.coerceAtLeast(1)
                val grown = LiveSeats.perSeatFor(ctx, n, b.liveBudget, seats) * seats
                val left = (grown - spent).coerceAtLeast(0.0)
                if (left > 0.0) { algoSum += left; algos += "${b.name} ${"%.0f".format(left)}" }
            }
        } catch (e: Exception) { Log.w(TAG, "algo budgets unreadable", e) }
        try {
            val floorRows = FloorLiveStore.load(ctx).rows
            for (a in FloorAlgoStore.load(ctx).algos) {
                val s = a.settings
                if (!s.liveOn || s.liveBudget <= 0.0) continue
                val deployed = floorRows.filter { it.ns == a.ns && it.holds }.sumOf { r ->
                    if (r.qty > 0.0 && r.entryPrice > 0.0) r.qty * r.entryPrice else r.amount }
                val left = (s.liveBudget - deployed).coerceAtLeast(0.0)
                if (left > 0.0) { algoSum += left; algos += "${a.name} ${"%.0f".format(left)}" }
            }
        } catch (e: Exception) { Log.w(TAG, "built-in algo budgets unreadable", e) }
        if (algoSum > 0.0) parts += Part("armed algos' seats still to fill", algoSum, algos.joinToString(", "))
        // Already out of the free cash — said, not subtracted.
        val heldAtBroker = try {
            T212WaitingOrders.last(ctx).orders.filter { it.isBuy }.sumOf { it.held }
        } catch (_: Exception) { 0.0 }
        val known = free >= 0.0 && at > 0L
        return mapOf("ok" to true, "known" to known, "free" to free, "checkedAt" to at, "currency" to acct,
            "parts" to parts.map { mapOf("label" to it.label, "amount" to it.amount, "detail" to it.detail) },
            "committed" to parts.sumOf { it.amount }, "yours" to (if (known) yours(free, parts) else null),
            "heldAtBroker" to heldAtBroker, "usdPerAcct" to usdPerAcct, "refreshError" to refreshError)
    }
}

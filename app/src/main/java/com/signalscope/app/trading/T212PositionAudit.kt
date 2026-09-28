package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.T212LiveStore
import com.signalscope.app.network.Trading212Client
import java.io.File
import com.google.gson.Gson
import kotlin.math.abs

/**
 * DOES THE APP'S BELIEF MATCH THE BROKER'S RECORD?
 *
 * Every other measurement in this app compares PRICES — paper vs ladder anchor vs
 * fill. All of them assume the position itself is what the ledger says it is. That
 * assumption is unguarded, and it is the one that loses money quietly:
 *
 *  • a ladder rung is rejected, so 3 shares are held where the ledger says 6 —
 *    every subsequent P&L, seat balance and entry size is computed on the wrong base
 *  • a sell fills partially and the remainder is never chased, leaving a live
 *    position the app believes is closed and therefore never exits again
 *  • a manual trade in the T212 app changes a holding the algo also owns
 *  • an exit order errors after the paper trade already closed
 *
 * None of these produce an error anywhere. The app keeps computing confidently off
 * a number that stopped being true. This compares the two sides and says so.
 *
 * READ-ONLY by construction. It never places, cancels or amends anything — the
 * correct response to a drift is a human decision, not an automatic counter-trade
 * that could double a position on a transient API blip.
 */
object T212PositionAudit {
    private const val TAG = "T212PositionAudit"
    private val gson = Gson()

    /** Fractional shares mean exact equality is the wrong test. A share is only
     *  "missing" when it is missing by more than rounding: 1e-4 absolute, or 0.5%
     *  of the expected size for larger positions. */
    private fun tolOf(expected: Double) = maxOf(1e-4, abs(expected) * 0.005)

    /**
     * THE ASYMMETRY THAT MATTERS.
     *
     * T212 reports ONE aggregate line per instrument. It has no idea which shares
     * belong to an algo, which to the bench, and which to a trade placed by hand in
     * its own app — and this account is used for all three. So the two directions
     * are not equally meaningful:
     *
     *  • broker holds LESS than the ledger claims → REAL problem. The app will try
     *    to sell shares that are not there; the exit partially fills or is rejected,
     *    and every P&L computed from the ledger size is wrong.
     *  • broker holds MORE → almost always a manual holding in the same instrument.
     *    Nothing the app does is broken by it, and there is no way to attribute the
     *    surplus even in principle.
     *
     * Treating a surplus as an alarm produced exactly the false positives seen on
     * the first bench run: two "quantity differs" rows for names the user already
     * owned by hand. Loud on shortfall, quiet and labelled on surplus.
     */
    private fun shortfall(expected: Double, actual: Double): Boolean =
        expected - actual > tolOf(expected)

    private fun surplus(expected: Double, actual: Double): Boolean =
        actual - expected > tolOf(expected)

    /** Statuses that mean REAL SHARES MAY BE HELD, and therefore must be audited.
     *
     *  PENDING and EXITING are the dangerous ones and were the easy ones to miss.
     *  A ladder writes PENDING before it places anything, then flips to OPEN when
     *  it knows what filled. If the process dies in between — Doze, a crash, the
     *  user force-stopping the app mid-chase — the row stays PENDING forever while
     *  the broker may well have filled it. Auditing only OPEN rows would make that
     *  position invisible to the one check designed to find it. */
    private val LIVE_STATUSES = setOf("OPEN", "PENDING", "EXITING")

    /** A ladder is six rungs at ~40s. Anything still mid-flight after this did not
     *  finish, it died. */
    private const val STUCK_AFTER_MS = 15 * 60 * 1000L

    /** Alarms — the ledger is wrong and downstream numbers cannot be trusted:
     *   MISSING  — the app holds it, the broker holds none of it.
     *   MISMATCH — the broker holds LESS than the ledger claims.
     *   STUCK    — a ladder started and never reported; the true size is unknown.
     *
     *  Benign, reported for context only:
     *   OVERLAP  — the broker holds MORE of a name an algo also holds (your own
     *              manual position in the same instrument; unattributable).
     *   DUST     — an unclaimed holding worth less than T212's one-unit floor. Not
     *              an error, but worth seeing: nobody can sell it on its own, and in
     *              a name an algo trades it would block that algo's partial exits
     *              were the ladder not sweeping it.
     *   EXTRA    — the broker holds it and no algo claims any of it. */
    data class Drift(
        val kind: String,
        val ticker: String,
        val symbol: String,
        val algos: List<String>,
        val expectedQty: Double,
        val actualQty: Double,
        val deltaQty: Double,
        val actualValue: Double
    )

    data class Report(
        val ok: Boolean = false,
        val checkedAt: Long = 0L,
        val error: String = "",
        val positionsSeen: Int = 0,
        val rowsChecked: Int = 0,
        val drifts: List<Drift> = emptyList()
    ) {
        /** Only a SHORTFALL against an algo's own position is an alarm. EXTRA (a
         *  holding no algo claims) and OVERLAP (the broker holds more of a name an
         *  algo also holds) are both explained by manual trading in the same
         *  account, which is normal here — see the asymmetry note above. STUCK is an
         *  alarm because the true size is unknown, not merely different. */
        val critical: List<Drift> get() = drifts.filter { it.kind !in setOf("EXTRA", "OVERLAP", "DUST") }
        val hasCritical: Boolean get() = critical.isNotEmpty()
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "t212_position_audit.json")

    fun last(ctx: Context): Report = try {
        val f = file(ctx)
        if (!f.exists()) Report() else gson.fromJson(f.readText(), Report::class.java) ?: Report()
    } catch (e: Exception) { Log.e(TAG, "load failed", e); Report() }

    @Synchronized
    private fun store(ctx: Context, r: Report) {
        try {
            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(gson.toJson(r))
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (e: Exception) { Log.e(TAG, "save failed", e) }
    }

    /**
     * Fetch the broker's holdings and diff them against every OPEN mirror row.
     * Network call — never run on the main thread. The result is cached so the UI
     * can render the banner without a request on every repaint.
     */
    fun run(ctx: Context): Report {
        val config = ConfigManager(ctx)
        if (!config.hasT212Credentials) {
            return Report(ok = false, checkedAt = System.currentTimeMillis(),
                error = "No T212 API key saved.").also { store(ctx, it) }
        }
        val positions = when (val r = Trading212Client(config).fetchPortfolio()) {
            is Trading212Client.T212Result.Success -> r.data
            is Trading212Client.T212Result.Failure ->
                return Report(ok = false, checkedAt = System.currentTimeMillis(),
                    error = r.message).also { store(ctx, it) }
            else -> return Report(ok = false, checkedAt = System.currentTimeMillis(),
                error = "T212 not configured").also { store(ctx, it) }
        }

        // FREE CASH, cached while we are already on the network. The commitment
        // warning needs this on every repaint and T212 rate-limits account reads,
        // so the pass that is already talking to the broker fetches it once.
        try {
            val summary = Trading212Client(config).fetchAccountSummary()
            if (summary is Trading212Client.T212Result.Success) {
                config.t212FreeFundsCached = summary.data.freeFunds
                config.t212FreeFundsAt = System.currentTimeMillis()
            }
        } catch (e: Exception) { Log.w(TAG, "free-cash read failed: ${e.message}") }

        // ONLY ROWS THAT COULD BE AT TRADING 212 IN THE FIRST PLACE.
        //
        // This compared EVERY row in the store against a Trading 212 portfolio,
        // including Indian positions that a missing guard elsewhere had written in
        // here. Zerodha holdings can never appear in a Trading 212 account, so each
        // one was reported as a position the broker had lost — thirteen of them,
        // under a banner saying the ledger could not be trusted.
        //
        // Kept as a filter here as well as fixing the writer, because an audit that
        // can be broken by a bad row elsewhere is not an audit. This one now
        // answers a question it can actually answer: of the things claimed to be at
        // Trading 212, what is missing.
        val open = T212LiveStore.load(ctx).trades.filter {
            it.status in LIVE_STATUSES && it.qty > 0.0 &&
                com.signalscope.app.trading.Spaces.baseOf(it.ns).let { b -> b == "de" || b == "us" }
        }
        // A leg that started and never reported: the ledger quantity is an ORDER
        // estimate, not a holding, so no quantity comparison can be trusted for it.
        // Reported on its own terms instead of being silently compared.
        val now = System.currentTimeMillis()
        val stuck = open.filter {
            it.status != "OPEN" && now - maxOf(it.buyAt, it.sellAt) > STUCK_AFTER_MS
        }
        // A ticker can legitimately be held by several algos at once, so the
        // comparison is per TICKER against the SUM of what the algos claim —
        // T212 reports one aggregate line per instrument, not one per strategy.
        // Stuck rows are excluded from the quantity diff — their ledger figure is an
        // order estimate, so comparing it would raise a MISMATCH that says nothing
        // beyond "we already know this one is unresolved", on top of its STUCK row.
        val stuckIds = stuck.map { it.ns to it.paperTradeId }.toSet()
        val expectedByTicker = open
            .filterNot { (it.ns to it.paperTradeId) in stuckIds }
            .groupBy { it.t212Ticker }
        val actualByTicker = positions.associateBy { it.ticker }
        val drifts = ArrayList<Drift>()

        for ((ticker, rows) in expectedByTicker) {
            if (ticker.isBlank()) continue
            // Prefer the qty actually filled; fall back to the qty ordered for rows
            // whose fills have not been read back yet.
            val expected = rows.sumOf { if (it.liveQtyFilled > 0.0) it.liveQtyFilled else it.qty }
            val pos = actualByTicker[ticker]
            val actual = pos?.quantity ?: 0.0
            val short = shortfall(expected, actual)
            val over = surplus(expected, actual)
            if (!short && !over) continue
            drifts.add(Drift(
                kind = if (short) (if (actual <= 0.0) "MISSING" else "MISMATCH") else "OVERLAP",
                ticker = ticker,
                symbol = rows.first().symbol,
                algos = rows.map { it.ns }.distinct(),
                expectedQty = expected, actualQty = actual,
                deltaQty = actual - expected,
                actualValue = (pos?.quantity ?: 0.0) * (pos?.currentPrice ?: 0.0)))
        }
        for (t in stuck) {
            val pos = actualByTicker[t.t212Ticker]
            drifts.add(Drift(
                kind = "STUCK", ticker = t.t212Ticker, symbol = t.symbol,
                algos = listOf(t.ns),
                expectedQty = t.qty, actualQty = pos?.quantity ?: 0.0,
                deltaQty = (pos?.quantity ?: 0.0) - t.qty,
                actualValue = (pos?.quantity ?: 0.0) * (pos?.currentPrice ?: 0.0)))
        }
        // "No algo claims this" must account for stuck rows too, or a stuck leg's
        // holding is reported twice — once as STUCK and again as EXTRA.
        val claimedTickers = open.map { it.t212Ticker }.toSet()
        for (p in positions) {
            if (p.ticker in claimedTickers) continue
            if (p.quantity <= 0.0) continue
            val value = p.quantity * p.currentPrice
            // DUST is worth calling out separately from an ordinary manual holding.
            // Below T212's one-unit floor a position cannot be sold on its own by
            // anyone, and while it sits in a name an algo also trades it blocks that
            // algo's partial exits. Naming it here is how you find it BEFORE it
            // stops a stop-loss, rather than from a rejected order afterwards.
            val isDust = value > 0.0 &&
                value < com.signalscope.app.network.Trading212Client.MIN_POSITION_VALUE
            drifts.add(Drift(
                kind = if (isDust) "DUST" else "EXTRA",
                ticker = p.ticker, symbol = p.ticker.substringBefore("_"),
                algos = emptyList(), expectedQty = 0.0, actualQty = p.quantity,
                deltaQty = p.quantity, actualValue = value))
        }

        val rep = Report(ok = true, checkedAt = System.currentTimeMillis(),
            positionsSeen = positions.size, rowsChecked = open.size,
            // Critical first, then biggest exposure — the order you would want to
            // read them in when something is wrong.
            drifts = drifts.sortedWith(
                compareBy<Drift> {
                    when (it.kind) {
                        "MISSING", "MISMATCH", "STUCK" -> 0
                        "DUST" -> 1                     // unsellable alone; blocks partial exits
                        "OVERLAP" -> 2
                        else -> 3                       // EXTRA — pure manual holdings
                    }
                }.thenByDescending { abs(it.actualValue) }))
        store(ctx, rep)
        if (rep.hasCritical)
            Log.w(TAG, "POSITION DRIFT — ${rep.critical.size} critical: " +
                rep.critical.joinToString { "${it.symbol} want ${it.expectedQty} have ${it.actualQty}" })
        else
            Log.i(TAG, "positions reconciled — ${rep.rowsChecked} rows vs ${rep.positionsSeen} holdings")
        return rep
    }
}

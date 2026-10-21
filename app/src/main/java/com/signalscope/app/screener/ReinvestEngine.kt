package com.signalscope.app.screener

import android.content.Context
import android.util.Log
import com.signalscope.app.data.ReinvestStore
import com.signalscope.app.data.StrategyLabStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// ════════════════════════════════════════════════════════════════════════════
// REINVEST ENGINE — the live monthly cycle (§4.0): rescan → reinvest → repeat
// ════════════════════════════════════════════════════════════════════════════
// Ties the screener (Part A) + persistent capital state (ReinvestStore) to the
// paper Strategy Lab basket. It:
//   • initialize(): first screen → fund top-N → enroll them in the lab.
//   • onTradeClosed(): compound a symbol's balance by each closed trade's % ret.
//   • rebalance(): STEP 1-4 — refetch, rescan, diff, migrate leavers' cash to
//     joiners; then sync the lab's enrolled basket (unenroll leavers, enroll
//     joiners). STEP 5 (trade the new basket) is the existing lab runner.
//   • maybeAutoRebalance(): fire rebalance once per new calendar month.
//
// Faithful to §4.2/§4.5 (MIGRATE default, stayers ride, leavers' pool split
// equally among joiners). Compounds by the trade's NET % return, independent of
// the lab's own $ notional — so it's fully decoupled. Places no real orders.
// Only the "us" and "binance" lab namespaces have a reinvest layer.
// ════════════════════════════════════════════════════════════════════════════

object ReinvestEngine {

    private const val TAG = "ReinvestEngine"

    private fun spaceForNs(ns: String): String = com.signalscope.app.trading.Spaces.dataSpaceOf(ns)
    private fun exchangeForNs(ns: String): String = com.signalscope.app.trading.Spaces.exchangeOf(ns)
    private fun cfgFor(s: ReinvestStore.State): ScreenerConfig = ScreenerConfig(topN = s.topN)
    private fun monthKey(d: LocalDate): String = "%04d-%02d".format(d.year, d.monthValue)
    private fun epochSecToDay(sec: Long): Long =
        Instant.ofEpochSecond(sec).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()

    data class RunOutcome(val ok: Boolean, val message: String, val added: List<String> = emptyList(), val dropped: List<String> = emptyList())

    /** First run: screen the universe, split [totalInvest] EQUALLY across the
     *  top-N, enroll them. injectedCapital = totalInvest (never more than the
     *  wallet's free cash — that cap is enforced by the caller). */
    fun initialize(
        ctx: Context, ns: String, universe: List<String>, totalInvest: Double, mode: String, topN: Int,
        onProgress: ((Int, Int) -> Unit)? = null
    ): RunOutcome {
        val space = spaceForNs(ns)
        val cfg = ScreenerConfig(topN = topN)
        val res = ScreenerRunner.run(space, universe, cfg, onProgress)
        val basket = res.ranked.take(topN).map { it.symbol }
        if (basket.isEmpty()) return RunOutcome(false, "Screen produced no ranked symbols (check the universe / data).")
        val asOfDay = LocalDate.parse(res.asOf).toEpochDay()
        val month = monthKey(LocalDate.parse(res.asOf))

        val perSymbol = totalInvest / basket.size          // split the pot equally
        val injected = perSymbol * basket.size             // == totalInvest (bar rounding)
        val balances = LinkedHashMap<String, Double>()
        val since = HashMap<String, Long>()
        for (s in basket) { balances[s] = perSymbol; since[s] = asOfDay }

        ReinvestStore.save(ctx, ns, ReinvestStore.State(
            enabled = true, initialized = true, mode = mode.uppercase(), notional = perSymbol, topN = topN,
            universe = universe.map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct(),
            balances = balances, enrolledSince = since,
            injectedCapital = injected,
            lastRebalanceMonth = month, lastRebalanceAt = System.currentTimeMillis(),
            history = listOf(ReinvestStore.Event(
                System.currentTimeMillis(), month, emptyList(), basket, 0.0, injected, 0.0))
        ))
        syncLabEnrollment(ctx, ns, basket)
        return RunOutcome(true, "Funded ${basket.size} symbols with \$${"%.0f".format(perSymbol)} each ($${"%.0f".format(injected)} total).", added = basket)
    }

    /** Compound a symbol's balance by a closed trade's net % return (guarded).
     *  If the symbol was WINDING DOWN (dropped by a rebalance while in a trade),
     *  this exit is the moment its capital is released: split the compounded
     *  balance among the last rebalance's joiners and unenroll it — the algo,
     *  not the rebalance, chose the exit time. */
    fun onTradeClosed(ctx: Context, ns: String, symbol: String, entryTsSec: Long, pctReturn: Double) {
        val sym = symbol.uppercase()
        val s = ReinvestStore.load(ctx, ns)
        if (!s.enabled || !s.initialized) return
        val since = s.enrolledSince[sym] ?: return
        if (epochSecToDay(entryTsSec) < since) return            // opened before enrollment
        val bal = s.balances[sym] ?: return
        val compounded = bal * (1.0 + pctReturn / 100.0)
        val windDown = (s.windingDown ?: emptyList()).map { it.uppercase() }
        if (sym !in windDown) {
            ReinvestStore.save(ctx, ns, s.copy(balances = s.balances + (sym to compounded)))
            return
        }
        // Wind-down finalization: release this symbol's capital and retire it.
        val balances = LinkedHashMap(s.balances); balances.remove(sym)
        val since2 = HashMap(s.enrolledSince); since2.remove(sym)
        val recipients = (s.lastJoiners ?: emptyList())
            .map { it.uppercase() }.filter { it in balances.keys }
            .ifEmpty { balances.keys.filter { it.uppercase() !in windDown } }
        var idleCash = s.idleCash
        if (recipients.isNotEmpty()) {
            val share = compounded / recipients.size
            for (r in recipients) balances[r] = (balances[r] ?: 0.0) + share
        } else {
            // No recipient for the released cash → PARK it as idle cash so it stays
            // in the total (Python cash-slot parity); it rejoins the pool at the
            // next rebalance. Previously this cash was dropped from the books.
            idleCash += compounded
        }
        val total = balances.values.sum() + idleCash
        val comp = if (s.injectedCapital > 0.0) (total / s.injectedCapital - 1.0) * 100.0 else 0.0
        ReinvestStore.save(ctx, ns, s.copy(
            balances = balances, enrolledSince = since2, idleCash = idleCash,
            windingDown = windDown.filter { it != sym },
            history = (s.history + ReinvestStore.Event(
                System.currentTimeMillis(), monthKey(LocalDate.now(ZoneOffset.UTC)),
                listOf(sym), emptyList(), compounded, total, comp)).takeLast(60)))
        try { StrategyLabStore.unenroll(ctx, sym, ns) }
        catch (e: Exception) { Log.w(TAG, "wind-down unenroll $sym failed", e) }
        Log.i(TAG, "wind-down complete: $sym released ${"%.2f".format(compounded)} to ${recipients.size} joiner(s)")
    }

    /** STEP 1-4: refetch, rescan, diff, migrate. Then sync lab enrollment. */
    fun rebalance(ctx: Context, ns: String, onProgress: ((Int, Int) -> Unit)? = null): RunOutcome {
        val s = ReinvestStore.load(ctx, ns)
        if (!s.initialized) return RunOutcome(false, "Reinvest not initialized yet.")
        val space = spaceForNs(ns)
        val res = ScreenerRunner.run(space, s.universe, cfgFor(s), onProgress)
        val newBasket = res.ranked.take(s.topN).map { it.symbol }
        if (newBasket.isEmpty()) return RunOutcome(false, "Rescan produced no ranked symbols.")
        val asOf = LocalDate.parse(res.asOf)
        val asOfDay = asOf.toEpochDay()
        val month = monthKey(asOf)

        val newSet = newBasket.toSet()
        val balances = LinkedHashMap(s.balances)
        val since = HashMap(s.enrolledSince)
        val allLeavers = balances.keys.filter { it !in newSet }.toList()
        val joiners = newBasket.filter { it !in balances.keys }

        // POINT-8 SEQUENCING (sells before buys, at algo-chosen times): a leaver
        // still holding an OPEN position is NOT dumped or unenrolled here — it
        // goes into windingDown (exit-only), keeps its sleeve, and releases its
        // cash to the joiners only when the ALGO exits the trade (onTradeClosed).
        // Only flat leavers' cash migrates immediately. Joiners are enrolled now
        // but buy nothing until their own entry signals fire — until then their
        // sleeve is just cash sitting in the wallet.
        val openSyms = StrategyLabStore.load(ctx, ns).trades
            .filter { it.status == "OPEN" }.map { it.symbol.uppercase() }.toSet()
        val windDownNew = allLeavers.filter { it.uppercase() in openSyms }
        val flatLeavers = allLeavers.filterNot { it in windDownNew }

        // Prior idle cash (parked when a past rebalance had no joiner) rejoins the
        // distributable pool now — a fresh joiner can finally pick it up.
        var pool = s.idleCash
        for (l in flatLeavers) { pool += balances.remove(l) ?: 0.0; since.remove(l) }

        var idleCash = 0.0
        when (s.mode.uppercase()) {
            "EQUAL_REBALANCE" -> {
                // Equalize the ACTIVE basket only; wind-down sleeves stay aside
                // until their trades close.
                val kept = balances.filterKeys { it in windDownNew.toSet() }
                val total0 = balances.filterKeys { it in newSet }.values.sum() + pool
                val per = if (newBasket.isNotEmpty()) total0 / newBasket.size else 0.0
                balances.clear(); balances.putAll(kept)
                for (b in newBasket) { balances[b] = per; since[b] = asOfDay }
                if (newBasket.isEmpty()) idleCash = total0   // nowhere to deploy → hold, don't drop
            }
            else -> {  // MIGRATE (default): flat leavers' pool split equally among joiners; stayers ride
                if (joiners.isNotEmpty() && pool > 0.0) {
                    val share = pool / joiners.size
                    for (j in joiners) { balances[j] = share; since[j] = asOfDay }
                } else {
                    for (j in joiners) { balances[j] = balances[j] ?: 0.0; since[j] = asOfDay }
                    // No joiner to receive the freed cash → RETAIN it as idle cash so
                    // it stays in the total (Python cash-slot parity). Dropping it
                    // under-reports returns whenever the book parks in cash — the
                    // common case on the 2-coin crypto universe at hold-1.
                    idleCash = pool
                }
            }
        }

        val total = balances.values.sum() + idleCash
        val compounded = if (s.injectedCapital > 0.0) (total / s.injectedCapital - 1.0) * 100.0 else 0.0
        val ev = ReinvestStore.Event(System.currentTimeMillis(), month, allLeavers, joiners, pool, total, compounded)
        val windDownAll = ((s.windingDown ?: emptyList()) + windDownNew).map { it.uppercase() }.distinct()
        ReinvestStore.save(ctx, ns, s.copy(
            balances = balances, enrolledSince = since, idleCash = idleCash,
            lastRebalanceMonth = month, lastRebalanceAt = System.currentTimeMillis(),
            windingDown = windDownAll, lastJoiners = joiners.map { it.uppercase() },
            history = (s.history + ev).takeLast(60)))

        // STEP: sync the lab basket — flat leavers out, joiners in; wind-down
        // symbols STAY ENROLLED (exit-only) so the algo can close them live.
        syncLabEnrollment(ctx, ns, newBasket + windDownAll)
        Log.i(TAG, "rebalance $ns $month: +${joiners.size} / -${flatLeavers.size} now, ${windDownNew.size} winding down, pool=${"%.2f".format(pool)}")
        val wdNote = if (windDownNew.isNotEmpty())
            " ${windDownNew.size} leaver(s) winding down (exit on algo signal): ${windDownNew.joinToString(", ")}." else ""
        return RunOutcome(true, "Rebalanced for $month: ${joiners.size} in, ${flatLeavers.size} out now.$wdNote",
            added = joiners, dropped = allLeavers)
    }

    /** MANUAL MODE (user decision): the app never rebalances by itself. When a
     *  new calendar month starts and the user hasn't rebalanced yet, post ONE
     *  reminder notification (per namespace per month) — the user runs the
     *  screen and taps "Rebalance now" themselves. */
    fun maybeMonthlyReminder(ctx: Context, ns: String) {
        val s = ReinvestStore.load(ctx, ns)
        if (!s.enabled || !s.initialized) return
        val month = monthKey(LocalDate.now(ZoneOffset.UTC))
        if (month == s.lastRebalanceMonth) return
        val prefs = ctx.getSharedPreferences("reinvest_reminders", Context.MODE_PRIVATE)
        val key = "notified_$ns"
        if (prefs.getString(key, "") == month) return
        try {
            postReminder(ctx, ns, month)
            prefs.edit().putString(key, month).apply()
        } catch (e: Exception) { Log.w(TAG, "reminder notification failed", e) }
    }

    private const val REMINDER_CHANNEL = "reinvest_reminder"

    private fun postReminder(ctx: Context, ns: String, month: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.createNotificationChannel(android.app.NotificationChannel(
            REMINDER_CHANNEL, "Monthly Reallocation Reminder",
            android.app.NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Once a month: re-screen the universe and reallocate manually" })
        val label = when (com.signalscope.app.trading.Spaces.baseOf(ns)) { "binance" -> "Crypto"; "de" -> "German stocks"; else -> "US stocks" }
        val pi = android.app.PendingIntent.getActivity(
            ctx, ns.hashCode(),
            android.content.Intent(ctx, com.signalscope.app.ui.MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = androidx.core.app.NotificationCompat.Builder(ctx, REMINDER_CHANNEL)
            .setContentTitle("🔁 Monthly reallocation due — $label")
            .setContentText("$month: run your screen, then tap Rebalance now. Joiners enter on algo signals; dropped names wind down on their algo exits.")
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(
                "$month: run your screen, then tap Rebalance now. Joiners enter on their own algo signals (cash sits idle until then); dropped names stay exit-only until the algo closes them."))
            .setSmallIcon(android.R.drawable.ic_menu_month)
            .setContentIntent(pi).setAutoCancel(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .build()
        com.signalscope.app.data.NotificationLog.post(ctx, nm, 9100 + ns.hashCode().mod(100), n,
            com.signalscope.app.data.NotificationLog.Link(ns = ns))
    }

    /** Make the lab's enrolled set exactly [basket]: enroll joiners, drop leavers. */
    private fun syncLabEnrollment(ctx: Context, ns: String, basket: List<String>) {
        val ex = exchangeForNs(ns)
        val current = StrategyLabStore.load(ctx, ns).enrolled.map { it.symbol.uppercase() }.toSet()
        val target = basket.map { it.uppercase() }.toSet()
        for (add in target - current) StrategyLabStore.enroll(ctx, add, ex, ns)
        for (drop in current - target) StrategyLabStore.unenroll(ctx, drop, ns)
    }
}

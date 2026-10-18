package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.ManualWatchStore
import com.signalscope.app.network.Trading212Client
import com.signalscope.app.network.YahooFinanceClient
import com.signalscope.app.network.ZerodhaClient
import java.time.LocalDate
import java.time.ZoneOffset

// ════════════════════════════════════════════════════════════════════════════
// HELD-POSITION WATCH — the prompt the daily managers cannot give
// ════════════════════════════════════════════════════════════════════════════
// The app already PROTECTS every holding: a trailing-stop manager runs for
// Zerodha and another for Trading 212, both enabled, both moving real stop
// orders. What neither can do is TELL you at the moment something breaks,
// because both run once a day. Between passes the resting broker order is the
// only thing awake — which is fine for protection and useless for a decision.
//
// So this adds the missing half and nothing else: it watches what you already
// own, every pass, and says something when a holding drops through the level
// the trailing managers would use. It NEVER places, cancels or modifies an
// order. Protection stays where it is, in the managers and at the broker; this
// is purely the voice they lack.
//
// ONE ALERT PER HOLDING PER DAY. A position that has broken its level is still
// broken an hour later, and repeating that every twelve minutes would train the
// user to swipe away the channel that also carries entry signals.
// ════════════════════════════════════════════════════════════════════════════

object HeldPositionWatch {

    private const val TAG = "HeldPositionWatch"

    /**
     * How far below the trailing level counts as "sharply", as a fraction of the
     * stop distance. Zero would fire on every touch, including the noise a
     * bar-close stop is specifically designed to ignore.
     */
    private const val BREAK_MARGIN = 0.0

    data class Held(
        val symbol: String,
        val qty: Double,
        val avgCost: Double,
        val price: Double
    )

    /**
     * THE MARKET'S OWN DAY, not the world clock's. "Once a day" counted by the
     * world clock started each day at 2 a.m. in Germany, so every reminder that
     * still applied went out again around 02:15 — every night, weekends included.
     */
    private fun today(base: String, nowMs: Long = System.currentTimeMillis()): String =
        MarketHours.tradingDay(base, nowMs) ?: LocalDate.now(ZoneOffset.UTC).toString()

    /**
     * ONLY WHILE THE MARKET IS TRADING. A holding judged on a shut market is
     * judged on yesterday's close, and nothing can be done about it until the
     * open anyway — so the reminder waits for the open and goes out on the first
     * pass after it (Germany ~09:16, US ~15:31, India ~05:46 German time).
     */
    private fun mayAlert(base: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        MarketHours.isRegularSession(base, nowMs)

    /**
     * What the account actually holds in this space.
     *
     * Read from the BROKER, not from the app's own books. A holding bought years
     * ago through the broker's own app is exactly the case this feature is for,
     * and it appears in no ledger of ours.
     */
    /**
     * Now reads through [BrokerHoldings], which fetches once per broker per tick
     * and can report that it could NOT read.
     *
     * This used to make its own call and turn every failure — rate limit, refusal,
     * exception — into an empty list. With three consumers each fetching the same
     * Trading 212 account on one tick, the later ones were rate limited, reported
     * "nothing held", and the whole pass returned early without a word. The
     * feature looked built and never ran once.
     *
     * Returns null when the answer is unknown, so callers can tell that apart from
     * an account that genuinely holds nothing.
     */
    private fun holdingsFor(ctx: Context, base: String): List<Held>? =
        when (val r = BrokerHoldings.forSpace(ctx, base)) {
            is BrokerHoldings.Result.Unreadable -> {
                Log.w(TAG, "[$base] holdings unreadable — ${r.why}; leaving everything alone")
                null
            }
            is BrokerHoldings.Result.Ok ->
                r.holdings.map { Held(it.symbol, it.qty, it.avgCost, it.price) }
        }

    data class Outcome(val checked: Int, val alerted: List<String>, val note: String)

    /**
     * Check every holding against the level the trailing managers would use, and
     * alert on anything through it.
     *
     * Uses [OcoPlanner] — the SAME planner both daily managers plan their stops
     * with — so the level this warns about is the level they would actually place.
     * Re-deriving it here with a second formula would eventually disagree with
     * them, and a warning that fires at a different price than the protection is
     * worse than no warning at all.
     */
    fun runOnce(ctx: Context, space: String): Outcome {
        val base = Spaces.baseOf(space.lowercase())
        val cfg = ConfigManager(ctx)
        val held = holdingsFor(ctx, base)
            ?: return Outcome(0, emptyList(), "holdings unreadable — nothing judged")
        if (held.isEmpty()) return Outcome(0, emptyList(), "nothing held")

        val day = today(base)
        val open = mayAlert(base)
        val st = ManualWatchStore.load(ctx, base)
        val alerted = ArrayList<String>()
        var checked = 0

        for (h in held) {
            if (h.avgCost <= 0.0 || h.price <= 0.0) continue
            checked++
            if (!open) continue                       // market shut — say it at the open
            val key = "held:${h.symbol}"
            if (st.alerted[key] == day) continue      // already said so today

            // The trailing level, planned exactly as the daily managers plan it.
            val atr = h.price * 0.02
            val plan = try {
                OcoPlanner.planSellOco(cfg, OcoPlanner.Input(
                    ltp = h.price, support = 0.0, resistance = 0.0,
                    atr = atr, avgPrice = h.avgCost, tickSize = 0.05,
                    broker = if (base == "in") OcoPlanner.Broker.ZERODHA
                             else OcoPlanner.Broker.T212))
            } catch (e: Exception) { null }
            if (plan == null || !plan.ok || plan.sl <= 0.0) continue

            val threshold = plan.sl * (1.0 - BREAK_MARGIN)
            if (h.price > threshold) continue

            val pct = (h.price / h.avgCost - 1.0) * 100.0
            LabNotifier.heldPositionBreak(ctx, base, h.symbol, h.price, plan.sl, pct, h.qty)
            ManualWatchStore.mutate(ctx, base) {
                it.copy(alerted = it.alerted + (key to day))
            }
            alerted.add(h.symbol)
            Log.w(TAG, "[$base] ${h.symbol} through its stop level: " +
                "${"%.2f".format(h.price)} vs ${"%.2f".format(plan.sl)} (${"%+.1f".format(pct)}%)")
        }
        // The strategy's own verdict on the same holdings — a different question
        // from the one above, and asked separately for that reason.
        try { strategyPass(ctx, base, held) }
        catch (e: Exception) { Log.w(TAG, "[$base] portfolio strategy pass failed", e) }

        return Outcome(checked, alerted, "checked $checked held")
    }

    // ════════════════════════════════════════════════════════════════════════
    // THE PORTFOLIO, JUDGED BY THE STRATEGY
    // ════════════════════════════════════════════════════════════════════════
    // The block above asks "has this fallen through the level the trailing
    // managers would use" — one number, about protection. This asks the whole
    // strategy: target reached, momentum rolled over, held too long, stop hit.
    //
    // It runs the SAME exit rules the algos run, through the same adopted-position
    // path the manual book uses. Not a second implementation: a copy would drift,
    // and stock you bought yourself would end up exiting on different terms than
    // stock the app bought.
    //
    // WHAT IT KNOWS AND WHAT IT DOES NOT. The broker gives us your real average
    // cost, so the stop and the target measure from what you actually paid. It
    // does NOT give us the date you bought, so the rules that count bars measure
    // from when the app first saw the holding. That is honest but weaker, and the
    // check reports when a rule could not be applied rather than pretending.
    //
    // PROMPTS BY DEFAULT. Selling stock you have held for years, on a rule applied
    // to it retrospectively, is a different order of decision from managing a
    // position the app opened itself — so acting needs its own switch.
    private fun strategyPass(ctx: Context, base: String, held: List<Held>) {
        val cfg = ConfigManager(ctx)
        if (!cfg.portfolioStrategyExitsEnabled || held.isEmpty()) return

        val ns = ManualWatchStore.nsFor(base)
        // No config attached means no rules to apply. Silent by design: the user
        // has not chosen a strategy for this space, so there is nothing to say.
        if (com.signalscope.app.data.StrategyLabStore.load(ctx, ns).configJson == null) return

        val day = today(base)
        val open = mayAlert(base)
        val exchange = Spaces.exchangeOf(base)
        // Shares another manager already owns are left to it — two managers on one
        // position is worse than one. Measured in SHARES rather than names, so a
        // partly-managed holding no longer disappears whole; see the loop below.

        com.signalscope.app.data.PortfolioTrackStore.retainOnly(
            ctx, base, held.map { it.symbol.uppercase() }.toHashSet())

        // How much of each name the app is already managing. Quantities, not names
        // — see below.
        val managed = try { ManualEntryWatcher.managedQtyFor(ctx, base) } catch (e: Exception) { emptyMap() }

        for (h in held) {
            val sym = h.symbol.uppercase()
            if (h.avgCost <= 0.0 || h.price <= 0.0) continue

            // ── THE PART NOBODY WAS LOOKING AFTER ────────────────────────────
            // This used to skip the whole holding if the watcher owned the NAME.
            // Own 200 of something, let the watcher buy 50, and all 200 fell out
            // of this check — while the watcher only ever manages, and only ever
            // sells, its own 50. The other 150 had no exit rules on it at all:
            // not here, because the name looked taken, and not in the watcher,
            // because it never bought them.
            //
            // So the test is now on QUANTITY. The watcher keeps its 50 — this
            // deliberately does not reach into another manager's position — and
            // the remaining 150 is judged here, by the same config, and prompts.
            val mine = managed[sym] ?: 0.0
            val remainder = h.qty - mine
            // Ignore rounding dust and slivers not worth a notification: a
            // fractional share left over from a ladder is not an unmanaged
            // position, and saying so every day would train you to ignore this.
            if (remainder <= h.qty * 0.01 || remainder * h.price < 5.0) continue

            val since = com.signalscope.app.data.PortfolioTrackStore
                .startFor(ctx, base, sym, h.avgCost)
            val v = try {
                StrategyLabRunner.adoptedExitCheck(ctx, ns, sym, exchange, h.avgCost, since)
            } catch (e: Exception) {
                Log.w(TAG, "[$base] $sym strategy check failed", e); continue
            }
            if (!v.ok) continue

            com.signalscope.app.data.PortfolioTrackStore.mutate(ctx, base) { s ->
                val t = s.tracks[sym] ?: com.signalscope.app.data.PortfolioTrackStore.Track()
                s.copy(tracks = s.tracks + (sym to t.copy(
                    lastReason = if (v.fires) v.reason else "holding")))
            }
            if (!v.fires) continue
            // Judged and remembered above either way; only the telling waits.
            if (!open) continue

            val st = com.signalscope.app.data.PortfolioTrackStore.load(ctx, base)
            if (st.tracks[sym]?.alertedDay == day) continue   // once a day, whatever happens

            val pct = (h.price / h.avgCost - 1.0) * 100.0
            // PROMPTS ONLY, for now, and deliberately.
            //
            // Selling here would need a path that does not exist yet: these
            // holdings have no live-order row, so neither existing exit route can
            // find them, and the Indian one would have to clear a resting broker
            // trigger before selling or leave it guarding shares that are gone.
            //
            // It is buildable. What stopped it is that I cannot test a real sell
            // without selling real stock, and shipping an untested path that
            // liquidates positions bought years ago is not a reasonable trade for
            // saving you a tap.
            // THE REMAINDER IS WHAT THIS PROMPT IS ABOUT, so it is the quantity
            // named — not the whole holding. Saying "sell 200" when 50 of them
            // belong to another manager that will sell them on its own terms
            // would be telling you to do something twice.
            LabNotifier.portfolioStrategyExit(ctx, base, sym, h.price, v.reason, pct, remainder, false)
            com.signalscope.app.data.PortfolioTrackStore.mutate(ctx, base) { s ->
                val t = s.tracks[sym] ?: com.signalscope.app.data.PortfolioTrackStore.Track()
                s.copy(tracks = s.tracks + (sym to t.copy(alertedDay = day, lastReason = v.reason)))
            }
            Log.w(TAG, "[$base] $sym strategy exit: ${v.reason} " +
                "(${"%+.1f".format(pct)}% on cost)" +
                "— prompt only, on ${"%.4f".format(remainder)} of ${"%.4f".format(h.qty)}" +
                (if (mine > 0.0) " (${"%.4f".format(mine)} managed elsewhere)" else "") +
                (if (!v.entryMacdKnown) " [${v.note}]" else ""))
        }
    }
}

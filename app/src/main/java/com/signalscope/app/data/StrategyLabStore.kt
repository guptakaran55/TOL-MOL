package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.signalscope.app.trading.StrategyLabConfig
import java.io.File

// ════════════════════════════════════════════════════════════════════════════
// STRATEGY LAB STORE — Live Automated Trading Mode state (US space, PAPER ONLY)
// ════════════════════════════════════════════════════════════════════════════
// Persists: enabled flag, the active config (raw uploaded JSON or the preset),
// the manually-enrolled US basket, per-symbol bar watermarks (idempotency), the
// cumulative paper ledger, and the decision audit log. Atomic JSON, same pattern
// as the other stores. Nothing here places real orders.
// ════════════════════════════════════════════════════════════════════════════

object StrategyLabStore {

    private const val TAG = "StrategyLabStore"
    private val gson = Gson()

    /** One enrolled symbol. The basket is set MANUALLY in-app (the laptop screener
     *  produces the list; the user types/imports symbols). Not from the config. */
    data class Enrolled(
        val symbol: String,                 // e.g. "MSTR"
        val exchange: String = "NASDAQ",
        val addedAt: Long = System.currentTimeMillis()
    )

    /** A persisted paper trade (cumulative — survives the sliding Yahoo window). */
    data class LedgerTrade(
        val id: String,                     // stable: "<symbol>-<entryTs>"
        val symbol: String,
        val status: String,                 // OPEN / CLOSED
        val entryTs: Long,
        val entryPrice: Double,
        val entryReason: String,
        val exitTs: Long? = null,
        val exitPrice: Double? = null,
        val exitReason: String = "",
        val pctReturn: Double = 0.0,
        val pnlNotional: Double = 0.0,
        val barsHeld: Int = 0
    )

    /** One decision audit row (BUY / SELL / NO_ACTION + reason). */
    data class Audit(
        val id: String,
        val ts: Long = System.currentTimeMillis(),
        val symbol: String,
        val action: String,                 // BUY / SELL / NO_ACTION / ERROR
        val price: Double,
        val reason: String,
        val barTs: Long
    )

    /** Per-symbol run diagnostics so the UI isn't a black box. */
    data class SymbolStatus(
        val symbol: String,
        val lastRunAt: Long = 0L,
        val ok: Boolean = false,
        val bars: Int = 0,
        val signals: Int = 0,
        val gated: Int = 0,
        val inPosition: Boolean = false,
        val note: String = "",           // "flat" / "in position" / "no data — check ticker" / etc.
        /**
         * THE WINDOW THE ENGINE ACTUALLY RAN, recorded every pass at no extra cost
         * because the pass already has it. Exists to settle warmup and timeframe
         * questions from stored live state instead of by re-deriving bar counts
         * from dates — which needs session hours and holidays and is where such
         * audits go wrong. [firstTradableTs] is the decisive one: no trade may
         * enter before it. Zero on any status written before this was recorded.
         */
        val baseSec: Long = 0L,
        val entryTfSec: Long = 0L,
        val exitTfSec: Long = 0L,
        val entryBars: Int = 0,
        val warmBars: Int = 0,
        val firstTradableTs: Long = 0L
    )

    data class State(
        val enabled: Boolean = false,
        val configJson: String? = null,     // null = use the baked-in preset
        val configSource: String = "preset",
        val enrolled: List<Enrolled> = emptyList(),
        val watermarks: Map<String, Long> = emptyMap(),  // symbol -> last processed bar ts
        // FLAT START: symbol -> the bar timestamp at which paper tracking began
        // (set on the first run after enroll/reset). Trades whose ENTRY is at or
        // before this are ignored entirely — so "Reset + Run now" means a genuine
        // clean start from today, not adopting positions opened before you began.
        val baselines: Map<String, Long> = emptyMap(),
        val trades: List<LedgerTrade> = emptyList(),
        val audit: List<Audit> = emptyList(),
        val statuses: Map<String, SymbolStatus> = emptyMap(),
        val lastRunAt: Long = 0L,
        /** The bar grid this book's timestamps were made on (StrategyLabConfig.barAnchorSec). */
        val barAnchorSec: Long = 0L
    )

    // ── Namespacing ───────────────────────────────────────────────────────
    // Each broker space gets its OWN store file so its config, ledger, audit
    // and watermarks are fully independent. "us" keeps the original file name
    // for backward compatibility; "binance" (crypto) uses a separate file, so
    // the two spaces can run DIFFERENT configs side by side.
    private fun file(ctx: Context, ns: String): File =
        File(ctx.filesDir, if (ns == "us") "strategy_lab.json" else "strategy_lab_${ns}.json")

    fun load(ctx: Context, ns: String = "us"): State {
        return try {
            val f = file(ctx, ns)
            if (!f.exists()) State() else gson.fromJson(f.readText(), State::class.java) ?: State()
        } catch (e: Exception) {
            Log.e(TAG, "load failed — returning empty", e); State()
        }
    }

    @Synchronized
    fun save(ctx: Context, state: State, ns: String = "us") {
        try {
            val f = file(ctx, ns)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(gson.toJson(state))
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
        }
    }

    /** Single atomic read-modify-write under the store lock — prevents the
     *  runner thread and UI thread from clobbering each other's updates. */
    @Synchronized
    fun mutate(ctx: Context, ns: String = "us", block: (State) -> State): State {
        val next = block(load(ctx, ns)); save(ctx, next, ns); return next
    }

    /** Apply ONE symbol's run output in a single atomic write: upsert trades by
     *  id, append only new audits, and advance the watermark together — so a
     *  failed/half write never advances the watermark without its trades. */
    @Synchronized
    fun applyRunResult(
        ctx: Context, symbol: String, newTrades: List<LedgerTrade>, newAudits: List<Audit>,
        watermark: Long, ns: String = "us", baseline: Long? = null, maxTrades: Int = 1000, maxAudit: Int = 500,
        /** Rows the caller has determined are stale and must be REMOVED. Without
         *  this the merge could only add/update, so an "OPEN" row the engine no
         *  longer reproduces could never be cleared (see the runner's reconcile). */
        removeIds: Set<String> = emptySet()
    ) {
        val s = load(ctx, ns)
        val sym = symbol.uppercase()
        val map = LinkedHashMap<String, LedgerTrade>()
        s.trades.forEach { map[it.id] = it }
        // Remove BEFORE the overlay so a row that is both removed and re-supplied
        // in newTrades keeps the fresh version.
        removeIds.forEach { map.remove(it) }
        // A CLOSED TRADE IS NEVER REOPENED. A pass that read the book before a
        // trade was closed by hand (the take-profit button) still carries it as
        // open; writing that back would resurrect a position already sold.
        newTrades.forEach { if (!(map[it.id]?.status == "CLOSED" && it.status != "CLOSED")) map[it.id] = it }
        val existing = s.audit.map { it.id }.toHashSet()
        val audit = (s.audit + newAudits.filter { it.id !in existing }).takeLast(maxAudit)
        val curBase = (s.baselines as? Map<String, Long>) ?: emptyMap()
        val newBaselines = if (baseline != null) curBase + (sym to baseline) else curBase
        save(ctx, s.copy(
            trades = map.values.toList().takeLast(maxTrades),
            audit = audit,
            watermarks = s.watermarks + (sym to watermark),
            baselines = newBaselines,
            lastRunAt = System.currentTimeMillis()
        ), ns)
    }

    /** Close one OPEN trade by hand, recording how — and touch nothing else: not
     *  the watermark, the baseline or the enrollment, so the algo carries on
     *  exactly as before. False if the trade was not open any more. */
    @Synchronized
    fun closeByHand(ctx: Context, ns: String, closed: LedgerTrade, audit: Audit): Boolean {
        var done = false
        mutate(ctx, ns) { s ->
            if (s.trades.none { it.id == closed.id && it.status == "OPEN" }) s
            else {
                done = true
                s.copy(trades = s.trades.map { if (it.id == closed.id) closed else it },
                    audit = (s.audit + audit).takeLast(500))
            }
        }
        return done
    }

    /** The flat-start baseline bar ts for a symbol (0 = no baseline yet). */
    fun baselineFor(ctx: Context, symbol: String, ns: String = "us"): Long =
        ((load(ctx, ns).baselines as? Map<String, Long>) ?: emptyMap())[symbol.uppercase()] ?: 0L

    /** The active config: parse the uploaded JSON if present, else the preset.
     *  Parsing here is defensive — uploads are validated at the bridge first. */
    fun activeConfig(ctx: Context, ns: String = "us"): StrategyLabConfig {
        val st = load(ctx, ns)
        val raw = st.configJson
        val parsed = if (raw == null) StrategyLabConfig.preset()
            else when (val r = StrategyLabConfig.fromJson(raw, st.configSource)) {
                is StrategyLabConfig.Companion.ParseResult.Ok -> r.config
                is StrategyLabConfig.Companion.ParseResult.Rejected -> StrategyLabConfig.preset()
            }
        // Bars are grouped on the exchange's own clock, as the desktop lab groups them. Only India
        // needs it: its +5:30 offset is the one that does not land on the epoch grid.
        val base = parsed.copy(barAnchorSec = gridFor(ctx, ns, st))
        // COSTS ARE RESOLVED HERE, AND ONLY HERE.
        //
        // This is the single function every engine gets its config from — the
        // paper runner, the 3b walk, the replay, the CSV exports, the badges.
        // Several of them re-read it partway through a run. So resolving the
        // final numbers at this one point is what makes an override actually
        // hold: there is no second path that could hand out the raw file's
        // values, and nothing downstream needs to know an override exists.
        //
        // Precedence, most specific first:
        //   1. the algo's own override  (set on its card)
        //   2. the space's flat fee     (Settings)
        //   3. the config file          (the strategy's own stated assumption)
        return try {
            val cm = com.signalscope.app.data.ConfigManager(ctx)
            val bucket = try { BucketStore.get(ctx, ns) } catch (e: Exception) { null }

            // THE CHARGES ARE MEASURED AGAINST WHAT ONE SEAT ACTUALLY BUYS, not
            // against the algo's total capital. Those differ the moment money is
            // split over seats — 1000 over 5 seats is a 200 position — and a fixed
            // cash charge measured against the wrong one understates itself by the
            // seat count. That is the whole weight of Zerodha's depository fee.
            val seats = (bucket?.holdN ?: 1).coerceAtLeast(1)
            val positionValue = when {
                bucket != null && bucket.capital > 0.0 -> bucket.capital / seats
                base.notional > 0.0 -> base.notional
                else -> 0.0
            }

            // ── WHAT THE BROKER TAKES ────────────────────────────────────────
            // Modelled per broker from their published price list rather than
            // approximated by a number typed into a box. A single "fee per order"
            // could not express any of what Zerodha actually charges: a tax on one
            // leg only, a flat cash depository fee on the sell alone, and GST
            // levied on some of the others but not on the taxes.
            val broker = com.signalscope.app.trading.TradingCosts.brokerCosts(
                ns, cm.accountCurrency, cm.fxConversionPct)
            val brokerPct = broker.totalPct(positionValue)

            // ── ANYTHING THE USER ADDS ON TOP ────────────────────────────────
            // Still honoured, still per order, still charged on both legs — but it
            // is now EXTRA rather than the whole model, and it defaults to nothing.
            // A fee typed in to stand for charges the app now models itself would
            // count them twice, which the algo card says out loud when it is set.
            val extraCash = bucket?.flatFeeOverride ?: cm.flatFeeFor(ns)
            val extraPct = if (extraCash > 0.0 && positionValue > 0.0)
                (2.0 * extraCash / positionValue) * 100.0 else 0.0

            // ── SPREAD ───────────────────────────────────────────────────────
            // ADDED TO THE BROKER'S CHARGES, NOT TRADED AGAINST THEM. These used
            // to be exclusive, on the reasoning that a flat fee and a percentage
            // were two descriptions of one friction — which was true only while
            // the "fee" was a hand-made stand-in for everything. Now that the
            // price list is modelled properly they are plainly different things:
            // STT is a tax and the spread is the market, and a trade pays both.
            val slip = bucket?.slippagePctOverride ?: base.slippagePct

            // THE ESCAPE HATCH, UNCHANGED IN MEANING. A percentage set on the algo
            // card says "charge exactly this and nothing else" — the way to test a
            // what-if, or to overrule the model entirely without editing anything.
            val totalFeePct = if (bucket?.slippagePctOverride != null) 0.0
                              else brokerPct + extraPct

            if (slip == base.slippagePct && totalFeePct == 0.0) base
            else base.copy(slippagePct = slip, flatFeeRoundTripPct = totalFeePct)
        } catch (e: Exception) { base }
    }

    /** Seconds east of UTC at which a space's bar grid SHOULD start (StrategyLabConfig.barAnchorSec). */
    fun barAnchorFor(ns: String): Long =
        if (com.signalscope.app.trading.Spaces.baseOf(ns) == "in") 19_800L else 0L

    /**
     * The grid this book may run on NOW — which is not always the one it should run on.
     *
     * A trade is identified by its symbol and its entry bar's timestamp, and moving the grid moves
     * every timestamp. Switched under an OPEN position, the engine would report that position under
     * a new id: the book would hold it twice, the original never closing. So a book moves to its
     * target grid only while it holds nothing, and at that moment its watermarks and baselines are
     * cleared so the next run is a clean flat start on the new clock — exactly what Reset does, minus
     * the ledger, which is kept as history. Until then it stays on the grid it was built on and its
     * open positions finish under the rules they were opened under.
     */
    private fun gridFor(ctx: Context, ns: String, st: State): Long {
        val target = barAnchorFor(ns)
        if (st.barAnchorSec == target) return target
        if (st.trades.any { it.status == "OPEN" }) return st.barAnchorSec
        val after = mutate(ctx, ns) { cur ->
            if (cur.barAnchorSec == target || cur.trades.any { it.status == "OPEN" }) cur
            else cur.copy(barAnchorSec = target, watermarks = emptyMap(), baselines = emptyMap())
        }
        if (after.barAnchorSec == target)
            Log.i(TAG, "[$ns] bar grid moved to anchor ${target}s — flat book, tracking restarts on the new clock")
        return after.barAnchorSec
    }

    fun setEnabled(ctx: Context, on: Boolean, ns: String = "us"): State = mutate(ctx, ns) {
        it.copy(enabled = on)
    }

    /** Changing the config is changing the strategy, so the paper state from the
     *  old config must NOT carry over (it would mix strategies). Reset ledger,
     *  audit, and watermarks; keep enrollment. */
    fun setConfig(ctx: Context, rawJson: String?, source: String, ns: String = "us"): State = mutate(ctx, ns) {
        it.copy(configJson = rawJson, configSource = source,
            trades = emptyList(), audit = emptyList(), watermarks = emptyMap(), baselines = emptyMap())
    }

    fun resetToPreset(ctx: Context, ns: String = "us"): State = mutate(ctx, ns) {
        it.copy(configJson = null, configSource = "preset",
            trades = emptyList(), audit = emptyList(), watermarks = emptyMap(), baselines = emptyMap())
    }

    fun enroll(ctx: Context, symbol: String, exchange: String, ns: String = "us"): State = mutate(ctx, ns) { s ->
        val norm = symbol.trim().uppercase()
        val defaultEx = com.signalscope.app.trading.Spaces.exchangeOf(ns)
        if (norm.isBlank() || s.enrolled.any { it.symbol.equals(norm, true) }) s
        else s.copy(enrolled = s.enrolled + Enrolled(norm, exchange.trim().ifBlank { defaultEx }))
    }

    /** Removing a symbol also drops its watermark and any open (ghost) position —
     *  otherwise an un-managed open trade would linger forever. Closed history is kept. */
    fun unenroll(ctx: Context, symbol: String, ns: String = "us"): State = mutate(ctx, ns) { s ->
        val norm = symbol.trim().uppercase()
        s.copy(
            enrolled = s.enrolled.filterNot { it.symbol.equals(norm, true) },
            watermarks = s.watermarks - norm,
            baselines = ((s.baselines as? Map<String, Long>) ?: emptyMap()) - norm,
            statuses = s.statuses - norm,
            trades = s.trades.filterNot { it.symbol.equals(norm, true) && it.status == "OPEN" }
        )
    }

    fun setWatermark(ctx: Context, symbol: String, barTs: Long, ns: String = "us") {
        val s = load(ctx, ns)
        save(ctx, s.copy(watermarks = s.watermarks + (symbol.uppercase() to barTs)), ns)
    }

    fun setSymbolStatus(ctx: Context, status: SymbolStatus, ns: String = "us"): State = mutate(ctx, ns) {
        it.copy(statuses = it.statuses + (status.symbol.uppercase() to status))
    }

    fun appendTrade(ctx: Context, trade: LedgerTrade, ns: String = "us", maxTrades: Int = 1000): State {
        val s = load(ctx, ns)
        // Upsert by id (an OPEN trade later becomes CLOSED with the same id).
        val merged = s.trades.filterNot { it.id == trade.id } + trade
        return s.copy(trades = merged.takeLast(maxTrades)).also { save(ctx, it, ns) }
    }

    fun appendAudit(ctx: Context, audit: Audit, ns: String = "us", maxAudit: Int = 500): State {
        val s = load(ctx, ns)
        if (s.audit.any { it.id == audit.id }) return s   // idempotent on decision id
        return s.copy(audit = (s.audit + audit).takeLast(maxAudit), lastRunAt = System.currentTimeMillis())
            .also { save(ctx, it, ns) }
    }

    fun markRun(ctx: Context, ns: String = "us") = save(ctx, load(ctx, ns).copy(lastRunAt = System.currentTimeMillis()), ns)

    /** Full wipe (ledger + audit + watermarks; keep config + enrollment). */
    fun resetPaperState(ctx: Context, ns: String = "us"): State {
        return mutate(ctx, ns) {
            it.copy(trades = emptyList(), audit = emptyList(), watermarks = emptyMap(), baselines = emptyMap())
        }
    }
}

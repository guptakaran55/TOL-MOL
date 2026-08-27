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
        val lastRunAt: Long = 0L
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
        newTrades.forEach { map[it.id] = it }
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

    /** The flat-start baseline bar ts for a symbol (0 = no baseline yet). */
    fun baselineFor(ctx: Context, symbol: String, ns: String = "us"): Long =
        ((load(ctx, ns).baselines as? Map<String, Long>) ?: emptyMap())[symbol.uppercase()] ?: 0L

    /** The active config: parse the uploaded JSON if present, else the preset.
     *  Parsing here is defensive — uploads are validated at the bridge first. */
    fun activeConfig(ctx: Context, ns: String = "us"): StrategyLabConfig {
        val st = load(ctx, ns)
        val raw = st.configJson
        val base = if (raw == null) StrategyLabConfig.preset()
            else when (val r = StrategyLabConfig.fromJson(raw, st.configSource)) {
                is StrategyLabConfig.Companion.ParseResult.Ok -> r.config
                is StrategyLabConfig.Companion.ParseResult.Rejected -> StrategyLabConfig.preset()
            }
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

            // A per-algo fee of 0.0 is a real answer ("this algo pays no flat
            // fee"), so only a NULL falls through to the space setting.
            val feeCash = bucket?.flatFeeOverride ?: cm.flatFeeFor(ns)

            // THE FEE IS MEASURED AGAINST WHAT A SEAT ACTUALLY BUYS, not against
            // the config file's notional. Those are different numbers whenever
            // capital is split over seats — with 1000 over 5 seats a position is
            // 200, and charging a flat fee against 1000 understates it fivefold.
            // Getting this wrong makes a fee look survivable that in fact is not.
            val seats = (bucket?.holdN ?: 1).coerceAtLeast(1)
            val positionValue = when {
                bucket != null && bucket.capital > 0.0 -> bucket.capital / seats
                base.notional > 0.0 -> base.notional
                else -> 0.0
            }
            val feePct = if (feeCash > 0.0 && positionValue > 0.0)
                (2.0 * feeCash / positionValue) * 100.0 else 0.0

            // EXCLUSIVE, NOT CUMULATIVE. A flat fee and a percentage are two ways
            // of describing the same friction, and charging both meant a config's
            // 0.20% was silently added to a fee typed in to replace it — which is
            // exactly how a small fee turned into a catastrophic-looking backtest.
            // Whichever is set on the algo wins outright.
            val slip = when {
                bucket?.slippagePctOverride != null -> bucket.slippagePctOverride!!
                feeCash > 0.0 -> 0.0
                else -> base.slippagePct
            }
            val effFeePct = if (bucket?.slippagePctOverride != null) 0.0 else feePct

            if (slip == base.slippagePct && effFeePct == 0.0) base
            else base.copy(slippagePct = slip, flatFeeRoundTripPct = effFeePct)
        } catch (e: Exception) { base }
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

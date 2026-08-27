package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.signalscope.app.trading.Spaces

/**
 * Buckets — multiple independent strategies inside ONE space.
 *
 * A bucket = a screening/engine config + a symbol group (subset of the space's
 * universe) + a capital allocation + a mode (monthly reinvest / 3b MACD gate /
 * manual) + a paper/live choice. Each bucket runs on its own namespace
 * "<space>__<id>", so every existing ns-keyed store (config, enrolled, paper
 * ledger, reinvest, gate, live mirror, slippage) isolates it automatically.
 *
 * MODEL: buckets REPLACE the single lab. On first read of a space that has a
 * legacy single lab (enrolled symbols on the bare "us"/"de"/"binance" ns), we
 * migrate it into "Bucket 1 (default)" so nothing is lost.
 */
object BucketStore {
    private const val TAG = "BucketStore"
    private val gson = Gson()

    // Only DE (German, paper — T212 live frozen) and BINANCE (crypto, real opt-in)
    // support algos. US and IN are intentionally excluded: T212 transacts only in
    // the user's home currency (EUR for a Germany-resident account), so USD equity
    // algo-trading isn't usable — no point offering the tab there.
    // India joins as a PAPER space: the algo machinery (screener, gate, seats,
    // ledger, replay) is space-agnostic and its bars already come from the same
    // fetcher. What it deliberately does NOT get is a live order mirror —
    // Zerodha order placement is a separate build with its own arming gates, and
    // shipping the paper half first is what lets the strategy be judged before
    // any of it can spend money.
    val BUCKETABLE_SPACES = listOf("de", "binance", "in")
    fun isBucketable(space: String) = Spaces.baseOf(space) in BUCKETABLE_SPACES

    // Binance is NOT auto-migrated: the bare "binance" lab keeps running its
    // existing live path (and open positions) untouched. Crypto buckets are
    // ADDITIVE — created explicitly by the user alongside the bare lab. Migrating
    // the paper lab to a bucket ns while the live ledger stays on bare "binance"
    // would desync the two books, so we don't.
    private val MIGRATABLE_SPACES = listOf("de")

    enum class Mode { MONTHLY, GATE_3B, MANUAL }

    data class Bucket(
        val id: String,
        val name: String,
        val space: String,                 // base space: "us" | "de" | "binance"
        val symbols: List<String> = emptyList(),
        val mode: Mode = Mode.MANUAL,
        val capital: Double = 1000.0,      // paper injected notional for this bucket
        val holdN: Int = 5,                // 3b slots / monthly topN
        val smoothing: Int = 5,            // 3b slope smoothing
        // Live money is PER BUCKET and opt-in. liveAllowlist gates which of the
        // bucket's symbols may place REAL orders (empty = none, safe default).
        val live: Boolean = false,
        val liveBudget: Double = 0.0,
        val liveAllowlist: List<String> = emptyList(),
        // ── COST OVERRIDES, per algo ──────────────────────────────────────
        // The config JSON states the friction the strategy was designed and
        // validated against. These let you ask "what if it were worse?" without
        // editing the file — the file stays the record of the original claim,
        // and the same file can be shared across spaces whose costs differ.
        //
        // NULL means "use the config / the space default", which is not the same
        // as 0.0: zero is a deliberate claim that trading is free, and a value
        // of 0 typed into the box must mean exactly that. Nullable is also
        // required for a second reason — Gson bypasses constructors, so a
        // non-null default would never apply to a bucket saved before these
        // fields existed and would arrive as 0.0, silently wiping the config's
        // slippage on every algo already on disk.
        val slippagePctOverride: Double? = null,
        /** Flat fee per order, in the SPACE's currency. Overrides the setting. */
        val flatFeeOverride: Double? = null,
        val createdAt: Long = System.currentTimeMillis()
    ) {
        val ns: String get() = Spaces.bucketNs(space, id)
    }

    data class State(val buckets: List<Bucket> = emptyList())

    private fun file(ctx: Context, space: String) =
        java.io.File(ctx.filesDir, "buckets_${space}.json")

    fun load(ctx: Context, space: String): State {
        val base = Spaces.baseOf(space)
        if (base !in BUCKETABLE_SPACES) return State()   // binance: never bucketized (Stage 1)
        return try {
            val f = file(ctx, base)
            val s = if (!f.exists()) State() else gson.fromJson(f.readText(), State::class.java) ?: State()
            migrateIfNeeded(ctx, base, s)
        } catch (e: Exception) { Log.e(TAG, "load($space) failed", e); State() }
    }

    private fun save(ctx: Context, space: String, s: State) {
        try { file(ctx, Spaces.baseOf(space)).writeText(gson.toJson(s)) }
        catch (e: Exception) { Log.e(TAG, "save($space) failed", e) }
    }

    /** On first use, absorb a legacy single-lab into "Bucket 1" so the switch to
     *  the bucket model loses nothing. Idempotent: runs once, then buckets exist. */
    private fun migrateIfNeeded(ctx: Context, space: String, s: State): State {
        if (s.buckets.isNotEmpty()) return s
        if (space !in MIGRATABLE_SPACES) return s        // binance: never auto-migrated
        val legacy = StrategyLabStore.load(ctx, space)   // bare-space ns = the old single lab
        if (!legacy.enabled && legacy.enrolled.isEmpty()) {
            // Nothing to migrate — start empty; the UI offers "create a bucket".
            return s
        }
        val b = Bucket(
            id = "b1",
            name = "Bucket 1 (default)",
            space = space,
            symbols = legacy.enrolled.map { it.symbol.uppercase() },
            mode = Mode.MANUAL,
            capital = 1000.0
        )
        // Copy the legacy config + enrolled state onto the bucket's namespace,
        // then RETIRE the bare-space lab (disable + unenroll) so it doesn't keep
        // evaluating the same symbols in parallel with Bucket 1 — that double-run
        // would double the paper trades. T212 live is frozen, so retiring the
        // bare US/DE lab has no real-money effect.
        try {
            val ns = b.ns
            StrategyLabStore.setConfig(ctx, legacy.configJson, legacy.configSource, ns)
            StrategyLabStore.setEnabled(ctx, legacy.enabled, ns)
            for (en in legacy.enrolled) StrategyLabStore.enroll(ctx, en.symbol, en.exchange, ns)
            // Retire the bare lab.
            for (en in legacy.enrolled) StrategyLabStore.unenroll(ctx, en.symbol, space)
            StrategyLabStore.setEnabled(ctx, false, space)
        } catch (e: Exception) { Log.w(TAG, "legacy migrate copy failed", e) }
        val next = State(listOf(b))
        save(ctx, space, next)
        Log.i(TAG, "migrated legacy $space lab → Bucket 1 (${b.symbols.size} symbols)")
        return next
    }

    fun list(ctx: Context, space: String): List<Bucket> = load(ctx, space).buckets

    fun get(ctx: Context, ns: String): Bucket? {
        val space = Spaces.baseOf(ns)
        val id = Spaces.bucketIdOf(ns)
        return list(ctx, space).firstOrNull { it.id == id }
    }

    fun create(ctx: Context, space: String, name: String): Bucket {
        val base = Spaces.baseOf(space)
        val s = load(ctx, base)
        val id = "b" + (System.currentTimeMillis() % 100000000L).toString(36)
        // Pre-fill the WHOLE universe so the user never has to type tickers — the
        // 3b/monthly screener ranks the full set and holds top-N (Slots). German
        // stores bare tickers (no ".DE"); crypto is the fixed 2-coin set.
        val defaultSyms = when (base) {
            "de" -> com.signalscope.app.service.DAX_SYMBOLS.map { it.removeSuffix(".DE").uppercase() }
            "binance" -> listOf("BTCUSDC", "ETHUSDC")
            else -> emptyList()
        }
        // MODE DEFAULT = 3b wherever a gate can actually run (de / binance). The
        // daily-MACD gate is the strategy these spaces are built around; MANUAL
        // means "no gate at all", which is a deliberate opt-out, not a sane
        // starting point. Crypto runs it gate-only (one seat per coin, no
        // screening) — see MacdGateStore.useScreenerSelection.
        // Smoothing follows the spec per asset class: 5 for stocks, 2 for crypto.
        val gateable = base in BUCKETABLE_SPACES
        val b = Bucket(id = id, name = name.ifBlank { "Bucket ${s.buckets.size + 1}" },
                       space = base, symbols = defaultSyms,
                       mode = if (gateable) Mode.GATE_3B else Mode.MANUAL,
                       smoothing = if (base == "binance") 2 else 5)
        save(ctx, base, s.copy(buckets = s.buckets + b))
        return b
    }

    fun update(ctx: Context, bucket: Bucket) {
        val s = load(ctx, bucket.space)
        save(ctx, bucket.space, s.copy(buckets = s.buckets.map { if (it.id == bucket.id) bucket else it }))
    }

    /** Remove a bucket and wipe its per-namespace state files. */
    fun delete(ctx: Context, ns: String) {
        val space = Spaces.baseOf(ns)
        val id = Spaces.bucketIdOf(ns)
        val s = load(ctx, space)
        save(ctx, space, s.copy(buckets = s.buckets.filterNot { it.id == id }))
        // Best-effort cleanup of the bucket's isolated state.
        try { StrategyLabStore.resetPaperState(ctx, ns) } catch (_: Exception) {}
        for (name in listOf("strategy_lab_${ns}.json", "reinvest_${ns}.json",
                "macd_gate_${ns}.json")) {
            try { java.io.File(ctx.filesDir, name).delete() } catch (_: Exception) {}
        }
    }

    /** All bucket namespaces (US/DE only in Stage 1) — for runner/scheduler. */
    fun allNamespaces(ctx: Context): List<String> =
        BUCKETABLE_SPACES.flatMap { sp -> list(ctx, sp).map { it.ns } }
}

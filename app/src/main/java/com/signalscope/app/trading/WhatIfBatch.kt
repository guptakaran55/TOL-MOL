package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.signalscope.app.data.BucketStore
import com.signalscope.app.data.StrategyLabStore
import com.signalscope.app.data.WhatIfStore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

// ════════════════════════════════════════════════════════════════════════════
// EVERY ALGO'S 💡 REPLAY, EVERY MARKET, ONE AFTER ANOTHER (the user's ask,
// 25 Oct 2026)
// ════════════════════════════════════════════════════════════════════════════
// The 💡 replay re-runs an algo's own rules over all the history the data
// provider has, and the compare table ranks algos by it. It was run by hand, a
// market at a time, so most algos had no figure and the rest were weeks apart —
// no ranking at all. This walks every config algo in every market, quickest
// market first, keeping any result less than a week old, and saves each one as
// the button does. One replay at a time, as every replay is: two at once only
// fight over the same provider limit.
//
// The built-in floor-order and MACD-turns algos are in it too, through their own
// replay (FloorReplay). Left out: the news-outlook ones, which could not be
// replayed honestly — the outlook knows what happened next — and the campaigns
// and weekly-zone floor orders, which have no replay yet.
// ════════════════════════════════════════════════════════════════════════════

object WhatIfBatch {
    private const val TAG = "WhatIfBatch"
    private const val FILE = "whatif_batch.json"

    /** A saved result younger than this is kept rather than replayed again. */
    const val FRESH_MS = 7L * 24 * 60 * 60_000L

    /** Quickest market first, so most of the table fills early. */
    private val ORDER = listOf("binance", "us", "de", "in")

    data class Status(
        val running: Boolean = false,
        val startedAt: Long = 0L,
        val updatedAt: Long = 0L,
        val finishedAt: Long = 0L,
        /** Replays this run needs, how many are done, how many were kept as fresh. */
        val total: Int = 0,
        val done: Int = 0,
        val kept: Int = 0,
        /** The algo being replayed now, "name (market)". */
        val current: String? = null,
        /** "name (market): why", one per replay that did not finish. */
        val failed: List<String>? = null,
        val cancelled: Boolean = false
    )

    private val gson = Gson()
    private val active = AtomicBoolean(false)
    @Volatile private var cancel = false

    /** Does this algo need a replay? None saved, or one older than [FRESH_MS]. Pure — tested. */
    fun needsReplay(computedAt: Long?, now: Long): Boolean = computedAt == null || now - computedAt > FRESH_MS

    /**
     * Replay one algo and keep the result for the compare table — slippage and
     * timeframes stored WITH it, since a return means nothing without them.
     */
    fun replayAndSave(ctx: Context, ns: String): StrategyLabRunner.Hypothetical {
        val b = BucketStore.get(ctx, ns)
        val cfg = StrategyLabStore.activeConfig(ctx, ns)
        val r = StrategyLabRunner.hypothetical(ctx, ns, b?.capital ?: cfg.notional)
        if (r.ok) try {
            WhatIfStore.put(ctx, WhatIfStore.Summary(
                ns = ns, name = b?.name ?: ns,
                computedAt = System.currentTimeMillis(),
                layer = r.layer,
                configSource = StrategyLabStore.load(ctx, ns).configSource,
                slippagePct = cfg.slippagePct,
                entryTf = r.entryTf, exitTf = r.exitTf, interval = r.interval,
                compoundedPct = r.compoundedPct, cagrPct = r.cagrPct,
                maxDrawdownPct = r.maxDrawdownPct, spanDays = r.spanDays,
                fromTs = r.fromTs, toTs = r.toTs,
                closed = r.closed, symbols = r.symbols, seats = r.holdN,
                note = r.note))
        } catch (e: Exception) { Log.w(TAG, "what-if summary save failed", e) }
        return r
    }

    fun status(ctx: Context): Status {
        val s = try {
            val f = File(ctx.filesDir, FILE)
            if (f.exists()) gson.fromJson(f.readText(), Status::class.java) ?: Status() else Status()
        } catch (e: Exception) { Status() }
        // Marked running by a run this process no longer has — the app was
        // stopped part-way. It is over; starting again picks up where it left off.
        return if (s.running && !active.get()) s.copy(running = false) else s
    }

    private fun save(ctx: Context, s: Status) {
        try {
            val f = File(ctx.filesDir, FILE)
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeText(gson.toJson(s))
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (e: Exception) { Log.w(TAG, "status save failed", e) }
    }

    /** Start a run. @return null when started, otherwise why not. */
    fun start(ctx: Context): String? {
        if (!active.compareAndSet(false, true)) return "Already replaying every algo."
        cancel = false
        val app = ctx.applicationContext
        thread(isDaemon = true, name = "whatif-batch") {
            try { run(app) }
            catch (e: Exception) { Log.w(TAG, "batch failed", e) }
            finally { active.set(false) }
        }
        return null
    }

    /** Stop after the replay in progress, which is asked to stop too. */
    fun cancel() {
        cancel = true
        StrategyLabRunner.cancelWhatIf()
    }

    private fun label(base: String) = when (base) {
        "binance" -> "crypto"; "us" -> "US"; "de" -> "Germany"; "in" -> "India"; else -> base
    }

    /** One algo to replay: its name for the progress line, and the replay itself. */
    private class Task(val ns: String, val name: String, val replay: () -> Pair<Boolean, String>)

    private fun run(ctx: Context) {
        val start = System.currentTimeMillis()
        val saved = WhatIfStore.all(ctx)
        // Per market: the config algos, then the built-in ones that have a replay (FloorReplay).
        val all = ORDER.flatMap { base ->
            val config = try {
                BucketStore.list(ctx, base).map { b ->
                    Task(b.ns, "${b.name} (${label(base)})") { replayAndSave(ctx, b.ns).let { it.ok to it.note } }
                }
            } catch (e: Exception) { emptyList() }
            val builtIn = try {
                com.signalscope.app.data.FloorAlgoStore.list(ctx, base).filter { FloorReplay.unsupported(it) == null }.map { a ->
                    Task(a.ns, "${a.name} (${label(base)})") { FloorReplay.replayAndSave(ctx, a).let { it.ok to it.note } }
                }
            } catch (e: Exception) { emptyList() }
            config + builtIn
        }
        val todo = all.filter { needsReplay(saved[it.ns]?.computedAt, start) }
        var st = Status(running = true, startedAt = start, updatedAt = start,
            total = todo.size, kept = all.size - todo.size)
        save(ctx, st)
        Log.i(TAG, "replaying ${todo.size} of ${all.size} algos (${all.size - todo.size} kept as fresh)")
        val failed = ArrayList<String>()
        var done = 0
        for (task in todo) {
            if (cancel) break
            // A replay started by hand runs first; this one waits its turn.
            while (StrategyLabRunner.whatIfRunning() && !cancel) Thread.sleep(5_000L)
            if (cancel) break
            st = st.copy(current = task.name, done = done, updatedAt = System.currentTimeMillis())
            save(ctx, st)
            val (ok, note) = try { task.replay() } catch (e: Exception) { false to (e.message ?: "replay failed") }
            if (!ok) failed.add("${task.name}: ${note.ifBlank { "did not finish" }.take(140)}")
            done++
            Log.i(TAG, "$done/${todo.size} ${task.name} — ${if (ok) "done" else "failed: $note"}")
        }
        save(ctx, st.copy(running = false, done = done, current = null, failed = failed,
            cancelled = cancel, finishedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
    }

    /** Once, on the first Algos visit after this install — the user asked for it. */
    @Synchronized
    fun startOnce(ctx: Context) {
        val marker = File(ctx.filesDir, "whatif_batch_2026_10_25.started")
        if (marker.exists()) return
        if (start(ctx) == null) try { marker.writeText(System.currentTimeMillis().toString()) } catch (e: Exception) { }
    }
}

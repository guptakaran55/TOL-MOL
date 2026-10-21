package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.signalscope.app.data.T212LiveStore
import java.io.File

/**
 * THE RV GATE'S RECORD — every order it judged, kept a year, so whether its
 * waiting pays can be measured on real fills rather than a backtest.
 *
 * Not shown anywhere on purpose: a plain file of one JSON line per event, read
 * off the phone for analysis. Events, joined by "key":
 *
 *   first    the gate's first look — the price then (what buying or selling at
 *            once would have got), today's high and low, where the price sat in
 *            that range, its direction, the day's ATR.
 *   release  the gate let it go — why, minutes waited, the price then.
 *   ask      a buy of yours stopped at its price limit to ask you.
 *   fill     what Trading 212 filled — quantity, price when known, order ids
 *            (the exact price can always be looked up by those ids later).
 *   end      finished without a fill — refused over its ceiling, called off,
 *            lapsed, nothing filled — and why.
 *
 * Keys: "algo|<ns>|<paper trade id>|BUY|SELL" for the algos' and the watchlist's
 * automatic orders; "you|<id>" for your button orders. An algo order can be
 * released more than once (an attempt that found no seller is retried); its
 * fill belongs to the last release before it.
 *
 * The algos' fills are booked on their order rows by other code, so those are
 * collected here by [settle] on each pass rather than at the moment they happen.
 */
object RvJournal {
    private const val TAG = "RvJournal"
    private const val FILE = "rv_gate_journal.jsonl"
    private const val OPEN_FILE = "rv_gate_journal_open.json"
    private const val KEEP_MS = 366L * 24 * 3_600_000L
    private const val PRUNE_BYTES = 2_000_000L
    /** An algo order with no outcome this long after its release is written off. */
    private const val GIVE_UP_MS = 4L * 24 * 3_600_000L
    /** A booked fill is taken once its price is reconciled from the broker, or after this. */
    private const val FILL_SETTLE_MS = 6L * 3_600_000L

    private val gson = Gson()

    fun algoKey(ns: String, paperTradeId: String, side: RvGate.Side) = "algo|$ns|$paperTradeId|${side.name}"

    fun base(src: String, side: RvGate.Side, ns: String, symbol: String, ticker: String,
             extra: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        mapOf("src" to src, "side" to side.name, "ns" to ns, "space" to Spaces.baseOf(ns).uppercase(),
            "symbol" to symbol, "ticker" to ticker) + extra

    /** The market as the gate read it, flattened for a line. */
    fun reading(rd: RvGate.Reading?): Map<String, Any?> {
        if (rd == null) return emptyMap()
        val p = rd.price ?: 0.0
        val hi = if (rd.high > 0.0) maxOf(rd.high, p) else p
        val lo = if (rd.low > 0.0) minOf(rd.low, p) else p
        val range = hi - lo
        val means = p > 0.0 && range > 0.0 && (rd.atr <= 0.0 || range >= RvGate.RANGE_MIN_ATR * rd.atr)
        return mapOf("price" to rd.price, "high" to hi, "low" to lo, "atr" to rd.atr,
            "vel" to rd.velocity, "pos" to (if (means) (p - lo) / range else null),
            "minsOpen" to rd.minutesSinceOpen.takeIf { it < 100_000L })
    }

    /**
     * One look by the gate. Writes "first" when the order was not already waiting
     * ([firstLook]) and "release" when the gate said go. [since] is when it began
     * waiting (0 = now). An algo order released here is watched by [settle].
     */
    fun look(ctx: Context, key: String, base: Map<String, Any?>, firstLook: Boolean, since: Long,
             d: RvGate.Decision, rd: RvGate.Reading?) {
        try {
            val r = reading(rd)
            if (firstLook) add(ctx, "first", key, base + r)
            if (d.go) {
                val now = System.currentTimeMillis()
                add(ctx, "release", key, r + mapOf("why" to d.why, "timedOut" to d.timedOut,
                    "waitedMin" to (if (since > 0L) ((now - since) / 60_000L).coerceAtLeast(0L) else 0L),
                    "peak" to d.memory?.peak?.takeIf { it > 0.0 }, "trough" to d.memory?.trough?.takeIf { it > 0.0 }))
                if (key.startsWith("algo|")) openPut(ctx, key, now)
            }
        } catch (e: Exception) { Log.w(TAG, "look not recorded: ${e.message}") }
    }

    /** Any other event — ask, fill, end. Never throws. */
    fun event(ctx: Context, ev: String, key: String, fields: Map<String, Any?>) {
        try { add(ctx, ev, key, fields) } catch (e: Exception) { Log.w(TAG, "$ev not recorded: ${e.message}") }
    }

    /** One JSON line, with nulls and non-numbers left out. Pure — tested. */
    internal fun line(at: Long, ev: String, key: String, fields: Map<String, Any?>): String {
        val m = LinkedHashMap<String, Any?>()
        m["at"] = at; m["ev"] = ev; m["key"] = key
        for ((k, v) in fields) {
            if (v == null) continue
            if (v is Double && !v.isFinite()) continue
            m[k] = v
        }
        return gson.toJson(m)
    }

    @Synchronized
    private fun add(ctx: Context, ev: String, key: String, fields: Map<String, Any?>) {
        val f = File(ctx.filesDir, FILE)
        f.appendText(line(System.currentTimeMillis(), ev, key, fields) + "\n")
        if (f.length() > PRUNE_BYTES) prune(f)
    }

    /** Lines older than a year go; the rest stay in order. */
    private fun prune(f: File) {
        try {
            val cutoff = System.currentTimeMillis() - KEEP_MS
            val keep = f.readLines().filter { l ->
                val at = Regex("\"at\":(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                at >= cutoff
            }
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(if (keep.isEmpty()) "" else keep.joinToString("\n", postfix = "\n"))
            tmp.renameTo(f)
        } catch (e: Exception) { Log.w(TAG, "prune failed: ${e.message}") }
    }

    // ── ALGO ORDERS WAITING FOR AN OUTCOME ──────────────────────────────────

    private val openType = object : TypeToken<Map<String, Long>>() {}.type

    @Synchronized
    private fun openLoad(ctx: Context): Map<String, Long> = try {
        val f = File(ctx.filesDir, OPEN_FILE)
        if (!f.exists()) emptyMap() else gson.fromJson<Map<String, Long>>(f.readText(), openType) ?: emptyMap()
    } catch (e: Exception) { emptyMap() }

    @Synchronized
    private fun openSave(ctx: Context, m: Map<String, Long>) {
        try { File(ctx.filesDir, OPEN_FILE).writeText(gson.toJson(m)) } catch (_: Exception) {}
    }

    @Synchronized
    private fun openPut(ctx: Context, key: String, releasedAt: Long) = openSave(ctx, openLoad(ctx) + (key to releasedAt))

    /**
     * Each pass: for every algo order released and not yet settled, read its
     * order row and write how it ended — the fill, or why there was none.
     */
    fun settle(ctx: Context) {
        val open = openLoad(ctx)
        if (open.isEmpty()) return
        val now = System.currentTimeMillis()
        val done = HashSet<String>()
        for ((key, releasedAt) in open) {
            try {
                val parts = key.split("|")
                if (parts.size != 4) { done += key; continue }
                val ns = parts[1]; val id = parts[2]; val buy = parts[3] == "BUY"
                val row = T212LiveStore.get(ctx, id, ns)
                val old = now - releasedAt > GIVE_UP_MS
                if (row == null) {
                    if (old || now - releasedAt > FILL_SETTLE_MS) {
                        event(ctx, "end", key, mapOf("why" to "the order's record is gone")); done += key
                    }
                    continue
                }
                if (buy) {
                    val filled = row.liveQtyFilled > 0.0 && row.liveEntryPrice > 0.0
                    if (filled && (row.fillsSyncedAt > 0L || now - releasedAt > FILL_SETTLE_MS)) {
                        event(ctx, "fill", key, mapOf("qty" to row.liveQtyFilled, "price" to row.liveEntryPrice,
                            "signal" to row.paperEntryPrice.takeIf { it > 0.0 }, "filledAt" to row.buyAt.takeIf { it > 0L },
                            "orderIds" to row.buyOrderIds?.takeIf { it.isNotEmpty() }))
                        done += key
                    } else if (!filled && row.status == "ERROR") {
                        event(ctx, "end", key, mapOf("why" to row.error)); done += key
                    } else if (!filled && (row.status == "CLOSED" || row.status == "MANUAL_CLOSED")) {
                        event(ctx, "end", key, mapOf("why" to "closed before any fill")); done += key
                    }
                } else {
                    val sold = row.liveExitQty > 0.0 && row.liveExitPrice > 0.0
                    if (sold && (row.fillsSyncedAt > 0L || now - releasedAt > FILL_SETTLE_MS)) {
                        event(ctx, "fill", key, mapOf("qty" to row.liveExitQty, "price" to row.liveExitPrice,
                            "filledAt" to row.sellAt.takeIf { it > 0L },
                            "orderIds" to row.sellOrderIds?.takeIf { it.isNotEmpty() }))
                        done += key
                    } else if (!sold && (row.status == "CLOSED" || row.status == "MANUAL_CLOSED")) {
                        event(ctx, "end", key, mapOf("why" to "closed by another route",
                            "price" to row.manualExitPrice.takeIf { it > 0.0 })); done += key
                    }
                }
                if (key !in done && old) {
                    event(ctx, "end", key, mapOf("why" to "no outcome within 4 days", "status" to row.status))
                    done += key
                }
            } catch (e: Exception) { Log.w(TAG, "settle $key failed: ${e.message}") }
        }
        if (done.isNotEmpty()) synchronized(this) { openSave(ctx, openLoad(ctx) - done) }
    }
}

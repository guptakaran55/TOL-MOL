package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.signalscope.app.data.StoreCache
import com.signalscope.app.network.YahooFinanceClient
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

/**
 * THE RESULTS RECORD (the user, 6 Nov 2026) — what each stock actually reported
 * and how the price took it, kept for good, good news and bad alike:
 *   • the last four quarters' earnings a share against what analysts expected
 *     (beat / miss, by how much), from Yahoo;
 *   • the latest report's date and the price over the two days after it;
 *   • every big move [NewsMoves] saw (date, %, volume, the headline behind it).
 *
 * Shown as one line on the stock page, and given to the two AI cards as FACTS.
 * The AI's own past verdicts are never fed back to it — that would make it agree
 * with itself; they belong in a score of how it did, not in its next answer.
 *
 * Keyed like [AiChips] ("US:NVDA", "DE:BC8", "IN:TCS").
 */
object ResultsBook {
    private const val TAG = "ResultsBook"
    private const val FILE = "results_book.json"
    private const val MAX_MOVES = 24
    private val gson = Gson()

    data class Quarter(val qEnd: Long = 0L, val eps: Double? = null, val est: Double? = null, val surprisePct: Double? = null)
    data class Move(val dayMs: Long = 0L, val pct: Double = 0.0, val volX: Double = 0.0,
                    val headline: String = "", val outlet: String = "", val results: Boolean = false,
                    val surprisePct: Double? = null)
    data class Rec(val quarters: List<Quarter>? = null, val moves: List<Move>? = null,
                   val readAt: Long = 0L, val lastReportMs: Long = 0L, val reactionPct: Double? = null,
                   val reactionFor: Long = 0L,
                   /** The company's sector and industry, as Yahoo files it. */
                   val sector: String? = null, val industry: String? = null,
                   /** Market cap in [capCur]; 0 = Yahoo had none, null = not read yet (11 Nov 2026). */
                   val marketCap: Double? = null, val capCur: String? = null)
    private data class Saved(val m: Map<String, Rec>? = null)

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    @Synchronized
    private fun all(ctx: Context): Map<String, Rec> = try {
        StoreCache.read(file(ctx)) { gson.fromJson(it, Saved::class.java) }?.m.orEmpty()
    } catch (e: Exception) { Log.w(TAG, "unreadable", e); emptyMap() }

    @Synchronized
    private fun change(ctx: Context, key: String, f: (Rec) -> Rec): Rec {
        val m = all(ctx).toMutableMap()
        val n = f(m[key] ?: Rec())
        m[key] = n
        try { StoreCache.write(file(ctx), gson.toJson(Saved(m))) } catch (e: Exception) { Log.w(TAG, "save failed", e) }
        return n
    }

    fun get(ctx: Context, key: String): Rec? = all(ctx)[key.trim().uppercase()]

    // ── pure ────────────────────────────────────────────────────────────────

    /** Pure: read again? Every 3 days, and as soon as a report has come out since. */
    fun stale(r: Rec?, lastReportMs: Long, now: Long): Boolean =
        r == null || r.readAt <= 0L || now - r.readAt > 3L * 86_400_000L ||
            (lastReportMs in (r.readAt + 1)..now) ||
            ((r.sector == null || r.marketCap == null) && now - r.readAt > 6L * 3_600_000L)   // read before these were kept

    /** Pure: the price over the two sessions from the report day, against the close
     *  before it. Daily bars, oldest first. Null when there are not enough. */
    fun reaction(bars: List<Pair<Long, Double>>, reportMs: Long): Double? {
        if (reportMs <= 0L) return null
        val day = Instant.ofEpochMilli(reportMs).atZone(ZoneOffset.UTC).toLocalDate()
        val i = bars.indexOfFirst { !Instant.ofEpochMilli(it.first).atZone(ZoneOffset.UTC).toLocalDate().isBefore(day) }
        if (i <= 0) return null
        val prev = bars[i - 1].second
        val after = bars[minOf(i + 1, bars.size - 1)].second
        return if (prev > 0.0 && after > 0.0) (after / prev - 1.0) * 100.0 else null
    }

    /** Pure: "beat 9.7%" / "miss 2.0%" / "in line". */
    fun word(s: Double?): String = when {
        s == null -> "?"
        s >= 1.0 -> "beat ${"%.1f".format(s)}%"
        s <= -1.0 -> "miss ${"%.1f".format(-s)}%"
        else -> "in line"
    }

    // ── reading ─────────────────────────────────────────────────────────────

    private fun yahooOf(key: String) = EarningsDates.yahooOf(key)

    /** The latest report day known: a results date that has passed. */
    private fun lastReport(ctx: Context, key: String, r: Rec?, now: Long): Long {
        val e = EarningsDates.known(ctx, key)?.atMs ?: 0L
        return maxOf(r?.lastReportMs ?: 0L, if (e in 1..now) e else 0L)
    }

    /**
     * The record for [key], read from Yahoo when stale (or [force]). Network —
     * off the main thread. Returns what is kept, fresh or not.
     */
    fun read(ctx: Context, key0: String, force: Boolean = false): Rec? {
        val key = key0.trim().uppercase()
        if (!key.contains(':') || key.startsWith("XX:")) return null
        val now = System.currentTimeMillis()
        val cur = get(ctx, key)
        val rep = lastReport(ctx, key, cur, now)
        if (!force && !stale(cur, rep, now)) return cur
        val reply = YahooFinanceClient.fetchResultsAndProfile(yahooOf(key)) ?: return cur
        val qs = reply.quarters
        var react: Double? = cur?.reactionPct.takeIf { cur?.reactionFor == rep }
        if (rep > 0L && (react == null || now - rep < 4L * 86_400_000L)) try {
            val y = yahooOf(key)
            val c = YahooFinanceClient.fetchDailyAdjusted(y, if (key.startsWith("US:")) "US" else "")
            if (c is YahooFinanceClient.CandleResult.Success)
                react = reaction(c.candles.takeLast(120).map { it.timestamp to it.close }, rep) ?: react
        } catch (e: Exception) { Log.w(TAG, "[$key] reaction failed: ${e.message}") }
        return change(ctx, key) { r ->
            r.copy(quarters = qs.map { Quarter(it.quarterEndMs, it.eps, it.estimate, it.surprisePct) }
                    .ifEmpty { r.quarters.orEmpty() },
                readAt = now, lastReportMs = maxOf(r.lastReportMs, rep),
                sector = reply.sector ?: r.sector ?: "", industry = reply.industry ?: r.industry,
                marketCap = reply.marketCap ?: r.marketCap?.takeIf { it > 0.0 } ?: 0.0, capCur = reply.capCur ?: r.capCur,
                reactionPct = react, reactionFor = if (react != null) rep else r.reactionFor)
        }
    }

    /** A big move seen today — kept once a day per stock. */
    fun addMove(ctx: Context, key: String, m: Move, reportMs: Long = 0L) {
        change(ctx, key.trim().uppercase()) { r ->
            val day = m.dayMs / 86_400_000L
            val moves = r.moves.orEmpty().filter { it.dayMs / 86_400_000L != day } + m
            r.copy(moves = moves.sortedBy { it.dayMs }.takeLast(MAX_MOVES),
                lastReportMs = maxOf(r.lastReportMs, if (reportMs in 1..System.currentTimeMillis()) reportMs else 0L))
        }
    }

    fun movedToday(ctx: Context, key: String, now: Long): Boolean =
        get(ctx, key)?.moves.orEmpty().any { it.dayMs / 86_400_000L == now / 86_400_000L }

    /** The latest quarter's surprise, if that report came out in the last [days] days. */
    fun freshSurprise(r: Rec?, now: Long, days: Int = 3): Double? {
        if (r == null || r.lastReportMs <= 0L || now - r.lastReportMs > days * 86_400_000L) return null
        return r.quarters.orEmpty().lastOrNull()?.takeIf { r.readAt >= r.lastReportMs }?.surprisePct
    }

    // ── for the page and the AI ──────────────────────────────────────────────

    fun forPage(r: Rec?): Map<String, Any?> = mapOf(
        "sector" to r?.sector?.takeIf { it.isNotBlank() }, "industry" to r?.industry,
        "cap" to r?.marketCap?.takeIf { it > 0.0 }, "capCur" to r?.capCur,
        "quarters" to r?.quarters.orEmpty().takeLast(4).reversed().map { mapOf("q" to it.qEnd, "s" to it.surprisePct) },
        "lastReport" to (r?.lastReportMs ?: 0L), "reaction" to r?.reactionPct,
        "moves" to r?.moves.orEmpty().takeLast(3).reversed().map {
            mapOf("day" to it.dayMs, "pct" to it.pct, "volX" to it.volX, "headline" to it.headline, "outlet" to it.outlet) })

    /** Facts for the AI prompt; "" when there are none. */
    fun forPrompt(r: Rec?, cur: String = ""): String {
        if (r == null) return ""
        val qs = r.quarters.orEmpty().takeLast(4)
        val mv = r.moves.orEmpty().filter { System.currentTimeMillis() - it.dayMs < 120L * 86_400_000L }.takeLast(4)
        val sec = listOfNotNull(r.sector?.takeIf { it.isNotBlank() }, r.industry?.takeIf { it.isNotBlank() })
        if (qs.isEmpty() && mv.isEmpty() && sec.isEmpty()) return ""
        fun d(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()
        return buildString {
            if (sec.isNotEmpty()) append("Sector: ${sec.joinToString(" · ")}\n\n")
            if (qs.isEmpty() && mv.isEmpty()) return@buildString
            append("─ Results record (facts from Yahoo Finance and this app, not opinions) ─\n")
            qs.reversed().forEach { q ->
                append("• Quarter to ${d(q.qEnd)}: earnings a share ${q.eps?.let { "%.2f".format(it) } ?: "?"}" +
                    (q.est?.let { " vs ${"%.2f".format(it)} expected" } ?: "") + " — ${word(q.surprisePct)}\n")
            }
            if (r.lastReportMs > 0L) append("• Latest results published ${d(r.lastReportMs)}" +
                (r.reactionPct?.let { "; price ${"%+.1f".format(it)}% over the two sessions after" } ?: "") + "\n")
            mv.reversed().forEach { m ->
                append("• ${d(m.dayMs)}: price ${"%+.1f".format(m.pct)}% on ${"%.1f".format(m.volX)}× usual volume" +
                    (if (m.headline.isNotBlank()) " — \"${m.headline}\"${if (m.outlet.isNotBlank()) " (${m.outlet})" else ""}" else "") + "\n")
            }
        }
    }
}

package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.BucketStore
import com.signalscope.app.data.DiscoveryResultStore
import com.signalscope.app.data.QualityCheapStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * THE "QUALITY · CHEAP · UPTREND" PAPER ALGOS (the user's note of 26 Oct 2026):
 * a copy of each market's live conservative algo whose company list is the
 * cheapest sound businesses in an uptrend. Two things set them apart from an
 * ordinary algo, and both live here:
 *
 *  • THE LIST IS REBUILT EACH QUARTER, from the market's own scan — but only
 *    offered. Once a quarter has ended, the first finished scan after it is put
 *    through the filter and the new list waits on the algo's card; nothing
 *    changes until it is accepted there.
 *
 *  • A NEWS VETO. Before a stock takes a seat — and each day it sits in one
 *    unbought — the AI's news verdict is asked for. Clearly bad news (its
 *    strongest "avoid") keeps it out and the seat goes to the next stock. Every
 *    verdict is logged, and each stock kept out is followed afterwards, so in a
 *    few months the record shows whether the veto helped. The veto only ever
 *    removes: with no AI key, or no answer, a stock is let through.
 */
object QualityCheap {
    private const val TAG = "QualityCheap"

    /** The US list is the ten cheapest, as the backtest's was; the others keep every stock that passes. */
    private val CAP = mapOf("us" to 10)
    private const val MAX_LIST = 30

    // ── THE FILTER — pure, tested ────────────────────────────────────────────

    data class Row(val symbol: String, val name: String, val price: Double, val sma200: Double?,
                   val trailingPe: Double?, val freeCashflow: Double?, val report: String)

    /** One part's points from the Value Score breakdown ("Quality|22|30|…"); null when absent. */
    fun partOf(report: String, part: String): Int? = report.lineSequence()
        .map { it.split('|') }
        .firstOrNull { it.size >= 2 && it[0].trim() == part }
        ?.get(1)?.trim()?.toIntOrNull()

    /**
     * Quality part 20 of 30 or more, no value-trap penalty, free cash flow not
     * negative (its −4 is missing from the breakdown, so it is read directly), a
     * positive P/E, and the price above its 200-day average.
     */
    fun passes(r: Row): Boolean {
        val q = partOf(r.report, "Quality") ?: return false
        val traps = partOf(r.report, "Value traps") ?: return false
        val pe = r.trailingPe ?: return false
        val sma = r.sma200 ?: return false
        return q >= 20 && traps == 0 && (r.freeCashflow ?: 0.0) >= 0.0 && pe > 0.0 && r.price > sma
    }

    /** A ticker as the algos store it: no exchange suffix. */
    fun bare(sym: String): String = sym.trim().uppercase().replace(Regex("\\.(DE|NS|BO)$"), "")

    /** Every stock that passes, cheapest first, one share class per company, at most [cap]. */
    fun pick(rows: List<Row>, cap: Int): List<String> {
        val companies = HashSet<String>()
        return rows.filter { passes(it) }
            .sortedBy { it.trailingPe }
            .filter { companies.add(it.name.trim().lowercase().ifBlank { bare(it.symbol) }) }
            .map { bare(it.symbol) }.distinct().take(cap)
    }

    /** The last quarter that has ENDED by [day]: on 26 Oct 2026, "2026-Q3". */
    fun lastQuarter(day: LocalDate): String {
        val q = (day.monthValue - 1) / 3
        return if (q == 0) "${day.year - 1}-Q4" else "${day.year}-Q$q"
    }

    /** The outlook's strongest down call: clearly bad news, for a stock you do not own or one you do. */
    fun isVeto(verdict: String): Boolean = verdict == "STRONG AVOID" || verdict == "SELL"

    private fun day(ms: Long = System.currentTimeMillis()): String =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    // ── THE QUARTERLY LIST ───────────────────────────────────────────────────

    /** Today's list from the market's latest finished scan, and when that scan finished. */
    fun listNow(ctx: Context, base: String): Pair<List<String>, Long>? {
        val market = ScanLookup.MARKET[base] ?: return null
        val scan = DiscoveryResultStore.loadLatestComplete(ctx, market) ?: return null
        val rows = scan.allStocks.map {
            // Gson can leave a missing string null whatever its declared type.
            Row(it.symbol, (it.name as String?).orEmpty(), it.price, it.sma200, it.trailingPe,
                it.freeCashflow, (it.valueScoreReport as String?).orEmpty())
        }
        return pick(rows, CAP[base] ?: MAX_LIST) to scan.timestamp
    }

    /**
     * Each pass: has a quarter ended since an algo's list was made, with a scan
     * finished after it? Then work out the new list and leave it on the card.
     * Reads only file dates until there is something to do.
     */
    fun checkQuarter(ctx: Context): String {
        // One made by the first build (26 Oct 2026), before the veto and the
        // quarterly list existed, is registered here — not left waiting for its
        // Algos tab to be opened while its seats are filled without a veto.
        for (base in ScanLookup.MARKET.keys) try {
            BucketStore.list(ctx, base).filter { it.name == BucketStore.QUALITY_CHEAP_NAME }
                .forEach { QualityCheapStore.register(ctx, it.ns, lastQuarter(LocalDate.now())) }
        } catch (e: Exception) { Log.w(TAG, "[$base] quality algo registration failed", e) }
        val done = ArrayList<String>()
        for (a in QualityCheapStore.load(ctx).algos) {
            try {
                if (BucketStore.get(ctx, a.ns) == null) continue
                val market = ScanLookup.MARKET[Spaces.baseOf(a.ns)] ?: continue
                val scanAt = DiscoveryResultStore.lastCompleteAt(ctx, market)
                if (scanAt <= 0L) continue
                val q = lastQuarter(LocalDate.parse(day(scanAt)))
                if (q <= a.listQuarter || q == a.dismissed || a.proposal?.quarter == q) continue
                done.add(propose(ctx, a.ns, manual = false))
            } catch (e: Exception) { Log.w(TAG, "[${a.ns}] quarterly check failed", e) }
        }
        return done.joinToString(" · ")
    }

    /**
     * Work out the list now and leave it for approval — at a quarter's end, or
     * when you ask. A list that has not changed moves the algo on to that quarter
     * with nothing to approve.
     * @return what happened, in words.
     */
    fun propose(ctx: Context, ns: String, manual: Boolean): String {
        val a = QualityCheapStore.get(ctx, ns) ?: return "This is not a quality · cheap · uptrend algo."
        val b = BucketStore.get(ctx, ns) ?: return "Algo not found."
        val (list, scanAt) = listNow(ctx, Spaces.baseOf(ns))
            ?: return "There is no finished scan of this market yet."
        val q = lastQuarter(LocalDate.parse(day(scanAt)))
        val scanDay = day(scanAt)
        if (list.isEmpty()) {
            QualityCheapStore.update(ctx, ns) { it.copy(lastNote = "$q: no stock passed the filter in the scan of $scanDay — list kept") }
            return "No stock passed the filter in the scan of $scanDay, so the list is kept."
        }
        val cur = b.symbols.map { it.uppercase() }
        val added = list.filter { it !in cur }
        val removed = cur.filter { it !in list }
        if (added.isEmpty() && removed.isEmpty()) {
            QualityCheapStore.update(ctx, ns) {
                it.copy(listQuarter = maxOf(it.listQuarter, q), proposal = null,
                    lastNote = "checked against the scan of $scanDay — unchanged")
            }
            return "The list is unchanged in the scan of $scanDay."
        }
        QualityCheapStore.update(ctx, ns) {
            it.copy(proposal = QualityCheapStore.Proposal(q, list, added, removed, scanAt,
                System.currentTimeMillis(), manual))
        }
        Log.i(TAG, "[$ns] $q list ready: +${added.joinToString(",")} −${removed.joinToString(",")}")
        if (!manual) try { LabNotifier.qualityListReady(ctx, ns, q, added, removed) } catch (_: Exception) {}
        return "$q list ready: ${added.size} in, ${removed.size} out."
    }

    /** The waiting list, once accepted on the card and the algo saved with it. */
    fun accepted(ctx: Context, ns: String) {
        QualityCheapStore.update(ctx, ns) { a ->
            val p = a.proposal ?: return@update a
            a.copy(listQuarter = maxOf(a.listQuarter, p.quarter), proposal = null,
                lastNote = "${p.quarter} list accepted ${day()}: ${p.added.size} in, ${p.removed.size} out")
        }
    }

    /** The waiting list turned down: the quarter is not offered again, a mid-quarter check simply goes. */
    fun dismissed(ctx: Context, ns: String) {
        QualityCheapStore.update(ctx, ns) { a ->
            val p = a.proposal ?: return@update a
            a.copy(proposal = null, dismissed = if (p.manual) a.dismissed else p.quarter,
                lastNote = "${p.quarter} list turned down ${day()} — current list kept")
        }
    }

    // ── THE NEWS VETO ────────────────────────────────────────────────────────

    fun vetoOn(ctx: Context, ns: String): Boolean =
        QualityCheapStore.get(ctx, ns)?.let { it.veto != false } ?: false

    /**
     * Does the news keep [symbol] out of a seat today? With [ask], today's
     * verdict is asked for if it is not known yet — a background pass only.
     * Without an answer the stock is let through. Each verdict is logged once a
     * day; a veto notifies once per spell.
     */
    fun blocks(ctx: Context, ns: String, symbol: String, price: Double, ask: Boolean): Boolean {
        if (!vetoOn(ctx, ns)) return false
        val base = Spaces.baseOf(ns)
        val v = AiVerdicts.cached(ctx, base, symbol)
            ?: (if (ask) AiVerdicts.now(ctx, base, symbol, price) else null)
            ?: return false
        record(ctx, ns, symbol, price, v)
        return isVeto(v.verdict)
    }

    /**
     * A stock seated with no verdict known yet — by a path that cannot wait on
     * the AI. Asked in the background; clearly bad news gives the seat back
     * before the strategy can buy.
     */
    fun checkLater(ctx: Context, ns: String, symbol: String) {
        if (!vetoOn(ctx, ns)) return
        val base = Spaces.baseOf(ns)
        if (AiVerdicts.cached(ctx, base, symbol) != null) return
        val app = ctx.applicationContext
        AiVerdicts.whenReady(app, base, symbol, 0.0) { v ->
            record(app, ns, symbol, MarkToMarket.get(symbol)?.price ?: 0.0, v)
            if (isVeto(v.verdict)) MacdGateEngine.releaseVetoed(app, ns, symbol)
        }
    }

    private fun record(ctx: Context, ns: String, symbol: String, price: Double, v: AiVerdicts.Verdict) {
        val today = day()
        val sym = symbol.uppercase()
        val vetoed = isVeto(v.verdict)
        var newSpell = false
        QualityCheapStore.update(ctx, ns) { a ->
            val checks = a.checks.orEmpty()
            if (checks.any { it.day == today && it.symbol == sym }) return@update a
            val px = if (price > 0.0) price else ScanLookup.of(ctx, ns, sym)?.second ?: 0.0
            val check = QualityCheapStore.Check(today, sym, v.verdict, v.confidence, v.reason, px, vetoed)
            var kept = a.kept.orEmpty()
            if (vetoed) {
                val last = kept.lastOrNull { it.symbol == sym }
                // Let through since its last spell? Then this is a new one.
                val letThrough = last != null && checks.any { it.symbol == sym && !it.vetoed && it.day > last.lastDay }
                kept = if (last != null && !letThrough)
                    kept.map { if (it === last) it.copy(lastDay = today, days = it.days + 1) else it }
                else {
                    newSpell = true
                    kept + QualityCheapStore.Kept(sym, today, today, 1, v.verdict, v.reason, px, px, today)
                }
            }
            a.copy(checks = checks + check, kept = kept)
        }
        if (newSpell) {
            Log.i(TAG, "[$ns] $sym kept out by the news veto: ${v.verdict} — ${v.reason}")
            try { LabNotifier.newsVeto(ctx, ns, sym, v) } catch (_: Exception) {}
        }
    }

    /** The stocks kept out, followed: each one's latest daily close, from the algo's daily evaluation. */
    fun follow(ctx: Context, ns: String, closes: Map<String, Pair<Double, String>>) {
        val a = QualityCheapStore.get(ctx, ns) ?: return
        if (a.kept.isNullOrEmpty()) return
        QualityCheapStore.update(ctx, ns) { x ->
            x.copy(kept = x.kept.orEmpty().map { k ->
                val c = closes[k.symbol] ?: closes[k.symbol.uppercase()]
                if (c != null && c.first > 0.0) k.copy(nowPx = c.first, nowDay = c.second) else k
            })
        }
    }

    /** What the algo's card shows, or null for an ordinary algo. */
    fun cardInfo(ctx: Context, ns: String): Map<String, Any?>? {
        val a = QualityCheapStore.get(ctx, ns) ?: return null
        val kept = a.kept.orEmpty()
        val checks = a.checks.orEmpty()
        val moves = kept.mapNotNull { k -> if (k.price > 0.0 && k.nowPx > 0.0) (k.nowPx / k.price - 1.0) * 100.0 else null }
        return mapOf(
            "listQuarter" to a.listQuarter, "note" to a.lastNote, "veto" to (a.veto != false),
            "proposal" to a.proposal,
            "checks" to checks.size, "vetoes" to checks.count { it.vetoed },
            "recent" to checks.takeLast(40).reversed(),
            "kept" to kept.reversed().map { k ->
                mapOf("symbol" to k.symbol, "since" to k.since, "lastDay" to k.lastDay, "days" to k.days,
                    "verdict" to k.verdict, "reason" to k.reason, "price" to k.price,
                    "nowPx" to k.nowPx, "nowDay" to k.nowDay,
                    "pct" to (if (k.price > 0.0 && k.nowPx > 0.0) (k.nowPx / k.price - 1.0) * 100.0 else null))
            },
            "keptAvgPct" to (if (moves.isEmpty()) null else moves.average()))
    }
}

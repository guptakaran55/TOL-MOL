package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

/**
 * Bull-flag breakout over a folder of CSVs (StrategyLabBacktest format, one file
 * per symbol). Runs several variants side by side so the volume check has to
 * earn its keep against buying on price alone.
 *
 * Opt-in: skipped unless BULLFLAG_DATA points at the folder. BULLFLAG_OUT is the
 * results folder (default build/bullflag), BULLFLAG_COST the round-trip cost in
 * % (default 0.476), BULLFLAG_SEATS the portfolio's seat count (default 5).
 * BULLFLAG_INDEX, daily CSVs of market indexes as NAME=path,NAME=path (a bare
 * path is named after its file), adds a run of every variant under each
 * market-trend rule of each index.
 */
class BullFlagBacktestHarness {

    private data class Variant(val name: String, val p: BullFlag.Params, val dailyOnly: Boolean = false,
                               val market: String? = null, val marketUp: ((Long) -> Boolean)? = null)

    @Test
    fun runVariants() {
        val root = System.getenv("BULLFLAG_DATA")
        assumeTrue("BULLFLAG_DATA not set", root != null)
        val out = File(System.getenv("BULLFLAG_OUT") ?: "build/bullflag").apply { mkdirs() }
        val cost = System.getenv("BULLFLAG_COST")?.toDouble() ?: 0.476
        val seats = System.getenv("BULLFLAG_SEATS")?.toInt() ?: 5
        val base = BullFlag.Params(costPct = cost)
        val plain = listOf(
            Variant("surge_1.5x", base),
            Variant("surge_2.0x", base.copy(volPaceMult = 2.0)),
            Variant("price_only", base.copy(requireVolume = false))
        )
        val indexes = (System.getenv("BULLFLAG_INDEX") ?: "").split(',').filter { it.isNotBlank() }.map { e ->
            val path = e.substringAfter('=')
            val name = if ('=' in e) e.substringBefore('=') else File(path).nameWithoutExtension
            name to StrategyLabBacktest.parseCsv(File(path).readText()).second
        }
        val multi = indexes.size > 1
        val filtered = indexes.flatMap { (name, idx) ->
            BullFlag.MarketRule.values().flatMap { rule ->
                val up = BullFlag.marketTrend(idx, rule)
                val tag = (if (multi) "${name.lowercase()}_" else "") + rule.name.lowercase()
                plain.map { it.copy(name = "${it.name}+$tag", market = rule.label.replace("index", name), marketUp = up) }
            }
        }
        val variants = plain + Variant("surge_1.5x_daily_close", base, dailyOnly = true) + filtered

        val files = File(root!!).listFiles { f -> f.name.endsWith(".csv") && f.name != "manifest.csv" }!!
            .sortedBy { it.name.lowercase() }
        val results = variants.map { ArrayList<BullFlag.Result>() }
        var firstTs = Long.MAX_VALUE; var lastTs = Long.MIN_VALUE
        for ((n, f) in files.withIndex()) {
            if (n % 100 == 0) println("${n}/${files.size}")
            val (sym, raw) = StrategyLabBacktest.parseCsv(f.readText())
            if (raw.size < 500) continue
            val candles = BullFlag.backAdjustWithVolume(raw)
            firstTs = minOf(firstTs, candles.first().timestamp); lastTs = maxOf(lastTs, candles.last().timestamp)
            val daily by lazy { toDailyBars(candles) }
            for ((k, v) in variants.withIndex())
                results[k].add(BullFlag.run(sym, if (v.dailyOnly) daily else candles, v.p, v.marketUp))
        }

        val summary = StringBuilder("variant,symbols,setup_days,market_blocked,unconfirmed_breaks,chased,trades,win_pct,avg_pct,median_pct," +
            "avg_win_pct,avg_loss_pct,profit_factor,avg_hold_days,stop,breakeven,target,time,open," +
            "seats,taken,skipped_full,compounded_pct,cagr_pct,max_dd_pct\n")
        val report = StringBuilder()
        for ((k, v) in variants.withIndex()) {
            val rs = results[k]
            val trades = rs.flatMap { it.trades }
            val s = BullFlag.stats(trades)
            val pf = BullFlag.portfolio(trades, seats, firstTs = firstTs, lastTs = lastTs)
            summary.append(listOf(v.name, rs.size, rs.sumOf { it.setupDays }, rs.sumOf { it.marketBlocked }, rs.sumOf { it.breaksWithoutVolume },
                rs.sumOf { it.chased }, s.trades, f(s.winRatePct), f(s.avgPct), f(s.medianPct), f(s.avgWinPct),
                f(s.avgLossPct), f(s.profitFactor), f(s.avgHoldDays),
                s.byExit["STOP"] ?: 0, s.byExit["BREAKEVEN"] ?: 0, s.byExit["TARGET"] ?: 0, s.byExit["TIME"] ?: 0,
                trades.count { it.exitReason == "OPEN" },
                seats, pf.taken, pf.skippedFull, f(pf.compoundedPct), f(pf.cagrPct), f(pf.maxDrawdownPct)
            ).joinToString(",") + "\n")

            report.append("\n== ${v.name} ==${v.market?.let { "  (only while $it)" } ?: ""}\n")
            report.append("trades ${s.trades}  win ${f(s.winRatePct)}%  avg ${f(s.avgPct)}%  median ${f(s.medianPct)}%  " +
                "PF ${f(s.profitFactor)}  hold ${f(s.avgHoldDays)}d  exits ${s.byExit}\n")
            report.append("$seats seats: took ${pf.taken}, missed ${pf.skippedFull} (full)  " +
                "compounded ${f(pf.compoundedPct)}%  CAGR ${f(pf.cagrPct)}%  maxDD ${f(pf.maxDrawdownPct)}%\n")
            for ((year, ts) in trades.filter { it.exitReason != "OPEN" }.groupBy { year(it.entryTs) }.toSortedMap()) {
                val y = BullFlag.stats(ts)
                report.append("  $year: ${y.trades} trades  win ${f(y.winRatePct)}%  avg ${f(y.avgPct)}%  PF ${f(y.profitFactor)}\n")
            }

            val csv = StringBuilder("symbol,entry_time,entry,vol_pace,stop,target,exit_time,exit,exit_reason,pct,hold_days," +
                "pole_gain_pct,retrace_pct,pole_vol_ratio,flag_vol_ratio,flag_days,trigger\n")
            for (t in trades.sortedBy { it.entryTs }) csv.append(listOf(t.symbol, ts(t.entryTs), t.entryPrice, f(t.volPace),
                f(t.stop), f(t.target), t.exitTs?.let { ts(it) } ?: "", t.exitPrice ?: "", t.exitReason, f(t.pctReturn),
                t.holdDays, f(t.setup.poleGainPct), f(t.setup.retracePct), f(t.setup.poleVolRatio),
                f(t.setup.flagVolRatio), t.setup.flagDays, t.setup.trigger).joinToString(",") + "\n")
            File(out, "trades_${v.name}.csv").writeText(csv.toString())
        }
        File(out, "summary.csv").writeText(summary.toString())
        File(out, "report.txt").writeText(report.toString())
        println(report)
    }

    /** One bar per UTC day, so the surge is judged on the whole day's volume at the close. */
    private fun toDailyBars(c: List<CandleData>): List<CandleData> =
        BullFlag.toDays(c).map { d ->
            CandleData(c[d.firstBar + d.barCount - 1].timestamp, d.open, d.high, d.low, d.close, d.volume.toLong())
        }

    private fun f(x: Double) = if (x.isNaN()) "" else if (x.isInfinite()) "inf" else "%.3f".format(x)
    private fun ts(t: Long) = Instant.ofEpochSecond(t).toString()
    private fun year(t: Long) = Instant.ofEpochSecond(t).atZone(ZoneOffset.UTC).year
}

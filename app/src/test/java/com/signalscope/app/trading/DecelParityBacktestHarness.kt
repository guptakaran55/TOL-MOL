package com.signalscope.app.trading

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Desktop-parity run of the NSE decel config through the SAME pipeline as the phone's
 * folder backtest (MainActivity.handleGateBacktest): parseCsv → backAdjust → engine.run
 * → GateBacktest.build → GateBacktest.run (3B).
 *
 * Opt-in: skipped unless PARITY_DATA points at the dataset root. PARITY_CONFIG is the
 * config JSON (as pulled off the phone), PARITY_OUT the results folder, and
 * PARITY_PHONE_COST the phone's resolved per-round-trip cost in % (optional).
 */
class DecelParityBacktestHarness {

    private data class Variant(val name: String, val decel: Boolean, val cost: Double)

    @Test
    fun runParityGrid() {
        val root = System.getenv("PARITY_DATA")
        assumeTrue("PARITY_DATA not set", root != null)
        val cfgFile = File(System.getenv("PARITY_CONFIG") ?: "src/test/resources/nse_decel_config.json")
        val out = File(System.getenv("PARITY_OUT") ?: "build/parity").apply { mkdirs() }
        val datasets = (System.getenv("PARITY_DATASETS") ?: "").split(',').filter { it.isNotBlank() }
        // The phone's resolution for this algo (StrategyLabStore.activeConfig): spread + Zerodha's
        // modelled charges + the Settings flat fee, all measured against ONE seat's money.
        val phoneCost = System.getenv("PARITY_PHONE_COST")?.split(':')?.let { (slip, seat, fee) ->
            val broker = TradingCosts.brokerCosts("in", "EUR", 0.15)
            val b = broker.totalPct(seat.toDouble())
            println("PHONE COST: spread $slip + broker ${"%.4f".format(b)} (${broker.components.joinToString { "${it.label} ${"%.4f".format(it.pct)}%+${it.cash}" }}) + flat ${"%.4f".format(2 * fee.toDouble() / seat.toDouble() * 100)}")
            slip.toDouble() + b + 2 * fee.toDouble() / seat.toDouble() * 100.0
        }

        val base = (StrategyLabConfig.fromJson(cfgFile.readText(), cfgFile.name)
            as StrategyLabConfig.Companion.ParseResult.Ok).config
            .copy(barAnchorSec = System.getenv("PARITY_ANCHOR")?.toLongOrNull() ?: 0L)
        val costs = listOfNotNull(0.20, 0.476, phoneCost)
        val variants = costs.flatMap { c -> listOf(Variant("decelON", true, c), Variant("decelOFF", false, c)) }
        fun cfgFor(v: Variant): StrategyLabConfig {
            val x = if (v.decel) base.exit.copy(accelNegativeEnabled = true, accelNegativeMinSigma = 1.0,
                                                slopeFlipDownEnabled = false)
                    else base.exit.copy(accelNegativeEnabled = false, slopeFlipDownEnabled = true,
                                        slopeFlipDownSmoothing = 2, slopeFlipDownPersistent = false)
            return base.copy(exit = x, slippagePct = v.cost, flatFeeRoundTripPct = 0.0)
        }

        val summary = StringBuilder("dataset,variant,cost_pct,symbols,failed,trades,cagr_pct,max_dd_pct,calmar,win_pct,compounded_pct,final_value,open_mtm_adj,t0,last_day,liquidations,cash_days_pct\n")
        for (ds in datasets) {
            val dir = File(root, ds)
            val files = dir.listFiles { f -> f.name.endsWith(".csv") && f.name != "manifest.csv" }!!
                .sortedBy { it.name.lowercase() }
            val cfgs = variants.map { cfgFor(it) }
            val data = variants.map { ArrayList<GateBacktest.SymbolData>() }
            val failed = ArrayList<String>()
            val engineDump = StringBuilder("variant,symbol,status,entry_time,exit_time,entry_price,exit_price,pct,exit_reason\n")
            for ((n, f) in files.withIndex()) {
                if (n % 50 == 0) println("[$ds] ${n}/${files.size}")
                val (sym, candles) = StrategyLabBacktest.parseCsv(f.readText())
                if (candles.size < 50) { failed.add("$sym(${candles.size} rows)"); continue }
                val adj = StrategyLabEngine.backAdjust(candles)
                for (k in variants.indices) {
                    val r = StrategyLabEngine.run(sym, adj, cfgs[k])
                    if (r.error != null) { if (k == 0) failed.add("$sym(${r.error})"); continue }
                    if (variants[k].cost == 0.476) for (t in r.trades) engineDump.append(listOf(variants[k].name, sym, t.status,
                        ts(t.entryTs), t.exitTs?.let { ts(it) } ?: "", t.entryPrice, t.exitPrice ?: "", t.pctReturn, q(t.exitReason)).joinToString(",") + "\n")
                    data[k].add(GateBacktest.build(sym, adj, r.trades, 5))
                }
            }
            for ((k, v) in variants.withIndex()) {
                val p = GateBacktest.Params(mode = "3B", holdN = 5, smoothing = 5, notional = 1000.0,
                    confirmDays = 1, slippagePct = v.cost, topN = 5)
                val rep = GateBacktest.run(data[k], p) { _, _ -> }
                val calmar = if (rep.maxDrawdownPct > 0) rep.cagrPct / rep.maxDrawdownPct else 0.0
                val tag = "${ds}__${v.name}__cost${"%.3f".format(v.cost)}"
                summary.append(listOf(ds, v.name, "%.4f".format(v.cost), data[k].size, failed.size,
                    rep.closedCount, "%.2f".format(rep.cagrPct), "%.2f".format(rep.maxDrawdownPct),
                    "%.2f".format(calmar), "%.1f".format(rep.winRatePct), "%.2f".format(rep.compoundedPct),
                    "%.2f".format(rep.finalValue), "%.2f".format(rep.openMtmAdj), rep.t0, rep.lastDay,
                    rep.liquidations, "%.1f".format(rep.cashDaysPct)).joinToString(",") + "\n")
                println("RESULT $tag trades=${rep.closedCount} cagr=${"%.2f".format(rep.cagrPct)} dd=${"%.2f".format(rep.maxDrawdownPct)} err=${rep.error}")
                writeTrades(File(out, "${tag}_trades.csv"), rep)
                writeEquity(File(out, "${tag}_equity.csv"), rep)
                writeEnrollments(File(out, "${tag}_seats.csv"), rep)
            }
            File(out, "${ds}_engine_trades_cost0.476.csv").writeText(engineDump.toString())
            File(out, "${ds}_failed_files.txt").writeText(failed.joinToString("\n"))
        }
        File(out, "summary.csv").writeText(summary.toString())
    }

    private fun ts(s: Long) = Instant.ofEpochSecond(s).atOffset(ZoneOffset.ofHoursMinutes(5, 30)).toString()
    private fun day(d: Long) = LocalDate.ofEpochDay(d).toString()
    private fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    private fun writeTrades(f: File, r: GateBacktest.Report) {
        val sb = StringBuilder("seq,seat,symbol,seat_taken,entry_time,entry_price,exit_time,exit_price,pct_return_net,cap_before,cap_after,entry_reason,exit_reason\n")
        for (t in r.portfolioTrades) sb.append(listOf(t.seq, t.seat + 1, t.symbol, day(t.enrolledFromDay),
            ts(t.entryTs), t.entryPrice, ts(t.exitTs), t.exitPrice, t.pctReturn,
            "%.4f".format(t.capBefore), "%.4f".format(t.capAfter), q(t.entryReason), q(t.exitReason))
            .joinToString(",") + "\n")
        f.writeText(sb.toString())
    }

    private fun writeEquity(f: File, r: GateBacktest.Report) {
        val sb = StringBuilder("date,total_value,seats_in_position,cash_seats\n")
        for (e in r.equity) sb.append("${day(e.day)},${"%.4f".format(e.totalValue)},${e.seatsInPosition},${e.cashSeats}\n")
        f.writeText(sb.toString())
    }

    private fun writeEnrollments(f: File, r: GateBacktest.Report) {
        val sb = StringBuilder("seat,symbol,from,to,screener_rank,screener_score,slope_at_entry,eject_reason,trades,pnl\n")
        for (e in r.enrollments) sb.append(listOf(e.seat + 1, e.symbol, day(e.fromDay), e.toDay?.let { day(it) } ?: "",
            e.screenerRank, "%.4f".format(e.screenerScore), "%.6f".format(e.slopeAtEntry), e.ejectReason,
            e.trades, "%.4f".format(e.pnl)).joinToString(",") + "\n")
        f.writeText(sb.toString())
    }
}

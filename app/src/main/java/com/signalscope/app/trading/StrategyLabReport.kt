package com.signalscope.app.trading

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ════════════════════════════════════════════════════════════════════════════
// STRATEGY LAB REPORT — exports a backtest in the desktop Python report format
// ════════════════════════════════════════════════════════════════════════════
// Reproduces report_*.csv: [RUN] / [OVERALL] / [MONTHLY] / [SYMBOLS] / [GATES] /
// [TRADES]. Same column names and ordering, so an Android export diffs directly
// against the laptop backtester for the same CSV + config.
// ════════════════════════════════════════════════════════════════════════════

object StrategyLabReport {

    private val NY: ZoneId = ZoneId.of("America/New_York")
    // "2024-06-12 09:30:00-04:00" — space separator + offset, matching the source.
    private val TFMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX", Locale.US)

    private fun fmtTime(tsSec: Long?): String =
        if (tsSec == null || tsSec <= 0L) "" else Instant.ofEpochSecond(tsSec).atZone(NY).format(TFMT)

    private fun monthKey(tsSec: Long): String =
        Instant.ofEpochSecond(tsSec).atZone(NY).toLocalDate().let { "%04d-%02d".format(it.year, it.monthValue) }

    private fun cell(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n'))
            "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun num(d: Double, dp: Int = 2): String = String.format(Locale.US, "%.${dp}f", d)

    /**
     * @param rawConfigJson the uploaded config text (preserved verbatim if present),
     *        else null → a compact note is written instead.
     */
    fun build(
        result: StrategyLabBacktest.Result,
        cfg: StrategyLabConfig,
        rawConfigJson: String?,
        elapsedMs: Long
    ): String {
        val sb = StringBuilder()
        val closed = result.trades.filter { it.status == "CLOSED" }
        val open = result.trades.filter { it.status == "OPEN" }

        // ── [RUN] ──
        sb.append("[RUN]\n").append("key,value\n")
        sb.append("generated_local,").append(java.time.LocalDateTime.now().withNano(0)).append("\n")
        sb.append("strategy,macd\n")
        sb.append("symbols_run,1\n")
        sb.append("bars_total,").append(result.barsParsed).append("\n")
        sb.append("resample,").append(cfg.resample).append("\n")
        sb.append("exit_bars,").append(cfg.exitBars).append("\n")
        sb.append("slippage_pct,").append(cfg.slippagePct).append("\n")
        sb.append("warmup_bars,").append(cfg.warmupBars).append("\n")
        sb.append("notional,").append(cfg.notional).append("\n")
        sb.append("elapsed_ms,").append(elapsedMs).append("\n")
        sb.append("config_json,").append(cell(rawConfigJson ?: "preset (champion default)")).append("\n\n")

        // ── [OVERALL] ──
        val wins = closed.count { it.pctReturn > 0 }
        val losses = closed.count { it.pctReturn < 0 }
        val totalPnl = closed.sumOf { it.pnlNotional }
        val grossWin = closed.filter { it.pctReturn > 0 }.sumOf { it.pnlNotional }
        val grossLoss = closed.filter { it.pctReturn < 0 }.sumOf { it.pnlNotional }
        val pf = if (grossLoss != 0.0) grossWin / kotlin.math.abs(grossLoss) else 0.0
        val expectancy = if (closed.isNotEmpty()) totalPnl / closed.size else 0.0
        // equity curve in $ for max drawdown + compounded return
        var equity = cfg.notional; var peak = cfg.notional; var maxDd = 0.0
        for (t in closed.sortedBy { it.entryTs }) {
            equity *= 1.0 + t.pctReturn / 100.0
            if (equity > peak) peak = equity
            val dd = equity - peak
            if (dd < maxDd) maxDd = dd
        }
        val compoundedPct = (equity / cfg.notional - 1.0) * 100.0
        sb.append("[OVERALL]\n").append("metric,value\n")
        sb.append("closed,").append(closed.size).append("\n")
        sb.append("wins,").append(wins).append("\n")
        sb.append("losses,").append(losses).append("\n")
        sb.append("win_rate,").append(num(if (closed.isNotEmpty()) wins * 100.0 / closed.size else 0.0)).append("\n")
        sb.append("total_pnl,").append(num(totalPnl)).append("\n")
        sb.append("expectancy,").append(num(expectancy)).append("\n")
        sb.append("profit_factor,").append(num(pf)).append("\n")
        sb.append("max_drawdown,").append(num(maxDd)).append("\n")
        sb.append("open_trades,").append(open.size).append("\n")
        sb.append("open_pnl,").append(num(open.sumOf { it.pnlNotional })).append("\n")
        sb.append("signals,").append(result.signals).append("\n")
        sb.append("gated,").append(result.gated).append("\n")
        sb.append("companies_funded,").append(if (closed.isNotEmpty() || open.isNotEmpty()) 1 else 0).append("\n")
        sb.append("total_invested,").append(num(cfg.notional)).append("\n")
        sb.append("final_value,").append(num(equity)).append("\n")
        sb.append("compounded_return_pct,").append(num(compoundedPct)).append("\n")
        sb.append("span_days,").append(num(result.spanDays, 1)).append("\n")
        sb.append("cagr_pct,").append(num(StrategyLabBacktest.cagr(compoundedPct, result.spanDays))).append("\n\n")

        // ── [MONTHLY] (by exit month, realized P&L) ──
        sb.append("[MONTHLY]\n").append("month,pnl,closed_trades,win_rate_pct\n")
        val byMonth = LinkedHashMap<String, MutableList<StrategyLabEngine.Trade>>()
        for (t in closed.sortedBy { it.exitTs ?: it.entryTs }) {
            val k = monthKey(t.exitTs ?: t.entryTs)
            byMonth.getOrPut(k) { ArrayList() }.add(t)
        }
        for ((m, ts) in byMonth) {
            val w = ts.count { it.pctReturn > 0 }
            sb.append(m).append(",").append(num(ts.sumOf { it.pnlNotional }))
                .append(",").append(ts.size).append(",")
                .append(num(if (ts.isNotEmpty()) w * 100.0 / ts.size else 0.0, 1)).append("\n")
        }
        sb.append("\n")

        // ── [SYMBOLS] ──
        sb.append("[SYMBOLS]\n").append("symbol,closed,wins,win_rate,pnl,compound_pct,best,worst,signals,gated,open,note\n")
        val best = closed.maxOfOrNull { it.pctReturn } ?: 0.0
        val worst = closed.minOfOrNull { it.pctReturn } ?: 0.0
        sb.append(cell(result.symbol)).append(",").append(closed.size).append(",").append(wins).append(",")
            .append(num(if (closed.isNotEmpty()) wins * 100.0 / closed.size else 0.0)).append(",")
            .append(num(totalPnl)).append(",").append(num(compoundedPct)).append(",")
            .append(num(best)).append(",").append(num(worst)).append(",")
            .append(result.signals).append(",").append(result.gated).append(",").append(open.size).append(",\n\n")

        // ── [GATES] ──
        sb.append("[GATES]\n").append("reason,count\n")
        for ((reason, count) in result.gates.entries.sortedByDescending { it.value })
            sb.append(cell(reason)).append(",").append(count).append("\n")
        sb.append("\n")

        // ── [TRADES] ──
        sb.append("[TRADES]\n")
        sb.append("symbol,side,status,entry_time,entry_price,exit_time,exit_price,pct_return,pnl_notional,bars_held,entry_reason,exit_reason\n")
        for (t in result.trades) {
            sb.append(cell(result.symbol)).append(",").append(t.side).append(",").append(t.status).append(",")
                .append(fmtTime(t.entryTs)).append(",").append(num(t.entryPrice, 4)).append(",")
                .append(fmtTime(t.exitTs)).append(",")
                .append(if (t.exitPrice != null) num(t.exitPrice, 4) else "").append(",")
                .append(num(t.pctReturn, 4)).append(",").append(num(t.pnlNotional, 3)).append(",")
                .append(t.barsHeld).append(",").append(cell(t.entryReason)).append(",").append(cell(t.exitReason)).append("\n")
        }
        return sb.toString()
    }
}

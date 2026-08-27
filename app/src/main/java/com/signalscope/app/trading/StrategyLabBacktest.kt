package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

// ════════════════════════════════════════════════════════════════════════════
// STRATEGY LAB BACKTEST — offline CSV parity path
// ════════════════════════════════════════════════════════════════════════════
// Feed the SAME CSV the desktop backtester uses (symbol,datetime,open,high,low,
// close,volume — datetime offset-aware, e.g. "2024-06-12 09:30:00-04:00") plus
// the active config, and run the identical pure engine. Output mirrors the
// desktop run_backtest summary (per-trade net %, $-PnL, compounded return).
//
// Parity notes: epoch-second bucketing matches pandas NY-tz resampling because
// US offsets are whole hours; EMA/rolling/velocity/slope/accel match pandas
// (adjust=false, ddof=1). Wilder ADX may differ by a hair in the warmup region
// (pandas NaN-seeding) — irrelevant for ADX-off configs like the champion.
// ════════════════════════════════════════════════════════════════════════════

object StrategyLabBacktest {

    // "2024-06-12 09:30:00-04:00" — note the space (not 'T') and the offset.
    private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX")

    /**
     * Normalize a datetime cell to the ONE shape [FMT] accepts, because real
     * exported datasets disagree on the offset format:
     *   Alpaca/US crypto : "2022-06-16 02:55:00-04:00"  (colon)      → XXX ok
     *   Yahoo German 60m : "2023-08-31 09:00:00+0200"   (NO colon)   → XXX FAILS
     * The colon-less form made every German row throw, so a file parsed to 0
     * candles while still reporting its symbol (symbol is read before the date)
     * — surfacing as "106 files skipped" with no reason attached.
     *
     * Handled: 'T' separator, ±HHMM and ±HH:MM offsets, trailing 'Z', and a
     * naive stamp with no offset at all (assumed UTC — matches these datasets).
     * String surgery rather than multi-formatter fallback: ~370k rows across a
     * 106-file universe, and thrown exceptions per row would be far too slow.
     */
    fun normalizeTs(raw: String): String {
        var v = raw.trim()
        if (v.length > 10 && (v[10] == 'T' || v[10] == 't')) v = v.substring(0, 10) + " " + v.substring(11)
        if (v.endsWith("Z") || v.endsWith("z")) return v.dropLast(1).trimEnd() + "+00:00"
        val n = v.length
        // ±HHMM → ±HH:MM   (sign 5 chars from the end, then 4 digits)
        if (n >= 5) {
            val sign = v[n - 5]
            if ((sign == '+' || sign == '-') && v.substring(n - 4).all { it.isDigit() })
                return v.substring(0, n - 2) + ":" + v.substring(n - 2)
        }
        // already ±HH:MM ?
        if (n >= 6) {
            val sign = v[n - 6]
            if ((sign == '+' || sign == '-') && v[n - 3] == ':') return v
        }
        return "$v+00:00"   // naive stamp → treat as UTC
    }

    data class Result(
        val symbol: String,
        val trades: List<StrategyLabEngine.Trade>,
        val closedCount: Int,
        val openCount: Int,
        val wins: Int,
        val losses: Int,
        val totalNetPct: Double,         // sum of closed net % returns
        val totalPnl: Double,            // sum of closed $-PnL on the notional
        val compoundedPct: Double,       // (Π(1+net/100) - 1) * 100 over closed trades
        val signals: Int,
        val gated: Int,
        val gates: Map<String, Int>,
        val barsParsed: Int,
        val rowsSkipped: Int,
        val error: String? = null,
        val spanDays: Double = 0.0,      // calendar span of the data window (first→last bar)
        val cagrPct: Double = 0.0        // year-adjusted compounded return over that span
    )

    /** Annualize a compounded return over a calendar span. Returns 0 if the span
     *  is too short to be meaningful (< ~2 weeks) so it isn't wildly extrapolated. */
    fun cagr(compoundedPct: Double, spanDays: Double): Double {
        if (spanDays < 14.0) return 0.0
        val years = spanDays / 365.25
        val growth = 1.0 + compoundedPct / 100.0
        if (growth <= 0.0) return -100.0                       // wiped out
        return (Math.pow(growth, 1.0 / years) - 1.0) * 100.0
    }

    fun parseCsv(text: String): Triple<String, List<CandleData>, Int> {
        val out = ArrayList<CandleData>()
        var symbol = "CSV"
        var skipped = 0
        val lines = text.split('\n')
        for ((idx, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            // header detection (first row, or any row containing the literal "datetime")
            if (idx == 0 && line.lowercase().contains("datetime")) continue
            val c = line.split(',')
            if (c.size < 7) { skipped++; continue }
            try {
                symbol = c[0].trim().ifBlank { symbol }
                val ts = OffsetDateTime.parse(normalizeTs(c[1]), FMT).toEpochSecond()
                out.add(CandleData(
                    timestamp = ts,
                    open = c[2].trim().toDouble(),
                    high = c[3].trim().toDouble(),
                    low = c[4].trim().toDouble(),
                    close = c[5].trim().toDouble(),
                    volume = c[6].trim().toDouble().toLong()
                ))
            } catch (e: Exception) { skipped++ }
        }
        // ascending + de-dup by timestamp (keep last), as normalize_ohlcv does.
        val dedup = LinkedHashMap<Long, CandleData>()
        out.sortedBy { it.timestamp }.forEach { dedup[it.timestamp] = it }
        return Triple(symbol, dedup.values.toList(), skipped)
    }

    fun run(csvText: String, cfg: StrategyLabConfig): Result {
        val (symbol, candles, rowsSkipped) = parseCsv(csvText)
        if (candles.size < 50)
            return Result(symbol, emptyList(), 0, 0, 0, 0, 0.0, 0.0, 0.0, 0, 0, emptyMap(), candles.size, rowsSkipped,
                error = "Only ${candles.size} usable rows — need enough to form the warmup window.")

        val r = StrategyLabEngine.run(symbol, candles, cfg)
        if (r.error != null)
            return Result(symbol, emptyList(), 0, 0, 0, 0, 0.0, 0.0, 0.0, 0, 0, emptyMap(), candles.size, rowsSkipped, error = r.error)

        val closed = r.trades.filter { it.status == "CLOSED" }
        var compound = 1.0
        for (t in closed.sortedBy { it.entryTs }) compound *= 1.0 + t.pctReturn / 100.0
        val compoundedPct = (compound - 1.0) * 100.0
        val spanDays = if (candles.size >= 2)
            (candles.last().timestamp - candles.first().timestamp) / 86_400.0 else 0.0
        return Result(
            symbol = symbol,
            trades = r.trades,
            closedCount = closed.size,
            openCount = r.trades.count { it.status == "OPEN" },
            wins = closed.count { it.pctReturn > 0 },
            losses = closed.count { it.pctReturn < 0 },
            totalNetPct = closed.sumOf { it.pctReturn },
            totalPnl = closed.sumOf { it.pnlNotional },
            compoundedPct = compoundedPct,
            signals = r.signals,
            gated = r.gates.values.sum(),
            gates = r.gates,
            barsParsed = candles.size,
            rowsSkipped = rowsSkipped,
            spanDays = spanDays,
            cagrPct = cagr(compoundedPct, spanDays)
        )
    }
}

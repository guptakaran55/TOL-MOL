package com.signalscope.app.util

import com.signalscope.app.data.CandleData
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * THE APP'S OWN SCORES, OVER HISTORY (6 Nov 2026) — for strategies that pick
 * stocks by the app's scores (momentum, swing, long-term…), tested on the PC.
 * Runs the phone's StockAnalyzer, unchanged, on each date with the 2 years of
 * daily bars before it (the app fetches range=2y, interval=1d).
 *
 * Skipped unless SCORE_IN is set, so ordinary builds never run it:
 *   SCORE_IN    folder of SYMBOL.csv files: date,open,high,low,close,volume (oldest first)
 *   SCORE_OUT   folder for SYMBOL.csv score files
 *   SCORE_EVERY score every Nth bar (default 1; 5 = weekly)
 *   SCORE_FROM  first date to score, yyyy-MM-dd (default: as soon as 2 years exist)
 *   SCORE_MINVOL minimum 20-day average volume (default 0: no filter)
 *
 *   SCORE_IN=... SCORE_OUT=... ./gradlew testDebugUnitTest --tests '*ScoreReplay*'
 */
class ScoreReplay {
    @Test fun replay() {
        val inDir = System.getenv("SCORE_IN")
        assumeTrue("SCORE_IN not set", !inDir.isNullOrBlank())
        val outDir = File(System.getenv("SCORE_OUT") ?: "$inDir/../scores").apply { mkdirs() }
        val every = (System.getenv("SCORE_EVERY") ?: "1").toInt().coerceAtLeast(1)
        val from = System.getenv("SCORE_FROM")?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) }
        val minVol = (System.getenv("SCORE_MINVOL") ?: "0").toInt()
        val window = 504

        val files = File(inDir!!).listFiles { f -> f.name.endsWith(".csv") }.orEmpty().sortedBy { it.name }
        var done = 0
        for (f in files) {
            val out = File(outDir, f.name)
            if (out.exists() && out.length() > 0) { done++; continue }
            val bars = f.readLines().drop(1).mapNotNull { l ->
                val p = l.split(',')
                if (p.size < 6) null else try {
                    val d = LocalDate.parse(p[0].trim().take(10))
                    CandleData(d.atStartOfDay().toEpochSecond(ZoneOffset.UTC) * 1000, p[1].toDouble(), p[2].toDouble(),
                        p[3].toDouble(), p[4].toDouble(), p[5].toDouble().toLong()) to d
                } catch (e: Exception) { null }
            }.filter { it.first.close > 0.0 }
            val sb = StringBuilder("date,close,momentum,swing,longterm,buy,pullback,profit,protect,macdPhase,macdSlope,ema21Pct,support,resistance\n")
            var i = window - 1               // the full 2 years, as on the phone
            while (i < bars.size) {
                val d = bars[i].second
                if (from == null || !d.isBefore(from)) {
                    val slice = bars.subList(maxOf(0, i - window + 1), i + 1).map { it.first }
                    val a = try { StockAnalyzer.analyze(slice, f.nameWithoutExtension, f.nameWithoutExtension, "", minVol) } catch (e: Exception) { null }
                    if (a != null) sb.append("$d,${slice.last().close},${a.momentumScore},${a.swingScore},${a.longTermScore},${a.cherryPoints}," +
                        "${a.pullbackScore},${a.profitScore},${a.protectScore},${a.macdPhase.replace(',', ' ')},${a.macdSlope}," +
                        "${a.ema21PctDiff},${a.support},${a.resistance}\n")
                }
                i += every
            }
            out.writeText(sb.toString())
            done++
            println("scored ${f.nameWithoutExtension} ($done/${files.size})")
        }
    }
}

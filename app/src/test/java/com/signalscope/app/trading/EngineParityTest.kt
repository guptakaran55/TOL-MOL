package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// ════════════════════════════════════════════════════════════════════════════
// ENGINE PARITY GATE
// ════════════════════════════════════════════════════════════════════════════
// This exists for exactly one reason: the exit rules are about to be lifted out
// of the two replay loops so a position the engine did NOT open can be managed
// by them. Every one of the six live algos runs that code. A refactor that
// quietly moves a single trade by one bar would be invisible in the app and
// would corrupt every backtest comparison made after it.
//
// So: run the engine over deterministic candles across a matrix of configs,
// flatten every resulting trade to text, and compare against a snapshot taken
// BEFORE the refactor. Byte-identical or the build fails.
//
// WHY SYNTHETIC CANDLES AND NOT REAL ONES: the gate has to be reproducible on
// any machine at any time. Yahoo's window slides, so a test anchored to real
// downloads would change its own expected answer and eventually "fail" for
// reasons that have nothing to do with the code. The generator below is a
// fixed-seed walk with deliberate trends, spikes and crashes in it, which is
// what actually exercises the branches — stops, targets, time limits, rollovers.
//
// REGENERATING THE SNAPSHOT is a deliberate act: delete the file and run again.
// Never do that to make a red build go green unless the change was intended to
// alter trading behaviour, because that is precisely what this is here to catch.
// ════════════════════════════════════════════════════════════════════════════

class EngineParityTest {

    private val goldenFile = File("src/test/resources/engine_parity_golden.txt")

    /**
     * A deterministic price series with structure in it.
     *
     * A pure random walk rarely triggers a 4% stop or an 8% target inside a
     * holding window, so a walk alone would leave the hard-exit branches — the
     * ones being extracted — largely unexercised and the gate would pass while
     * proving little. These regimes force each of them to fire.
     */
    private fun candles(seed: Long, n: Int, startTs: Long, stepSec: Long): List<CandleData> {
        var s = seed
        fun rnd(): Double {                       // xorshift — same everywhere, forever
            s = s xor (s shl 13); s = s xor (s ushr 7); s = s xor (s shl 17)
            return ((s ushr 11).toDouble() / (1L shl 53).toDouble()) - 0.5
        }
        val out = ArrayList<CandleData>(n)
        var px = 100.0
        for (i in 0 until n) {
            val regime = (i / 220) % 5
            val drift = when (regime) {
                0 -> 0.0011      // steady climb — take profits and time limits
                1 -> -0.0014     // steady bleed — stops
                2 -> 0.0         // chop — indicator exits and rollovers
                3 -> 0.0035      // sharp rally — targets
                else -> -0.0040  // sharp break — stops, hard
            }
            px *= (1.0 + drift + rnd() * 0.010)
            if (px < 1.0) px = 1.0
            val o = px * (1 + rnd() * 0.002)
            val c = px
            val hi = maxOf(o, c) * (1 + kotlin.math.abs(rnd()) * 0.004)
            val lo = minOf(o, c) * (1 - kotlin.math.abs(rnd()) * 0.004)
            out.add(CandleData(startTs + i * stepSec, o, hi, lo, c, 10_000L + i))
        }
        return out
    }

    /** The config matrix. Each one turns on a different exit branch, so a change
     *  to any single branch shows up as a diff rather than averaging away. */
    private fun configs(): List<Pair<String, StrategyLabConfig>> {
        val base = StrategyLabConfig()
        return listOf(
            "single_default" to base.copy(resample = "60min", exitBars = "same", warmupBars = 30),
            "single_stop_tp" to base.copy(resample = "60min", exitBars = "same", warmupBars = 30,
                exit = base.exit.copy(stopLossEnabled = true, stopLossPct = 4.0,
                    takeProfitEnabled = true, takeProfitPct = 8.0)),
            "single_maxbars" to base.copy(resample = "60min", exitBars = "same", warmupBars = 30,
                exit = base.exit.copy(maxBarsEnabled = true, maxBars = 12, minHoldBars = 2)),
            "single_retrace" to base.copy(resample = "60min", exitBars = "same", warmupBars = 30,
                exit = base.exit.copy(slopeRetraceEnabled = true, slopeRetracePct = 40.0,
                    slopeRetraceConfirmVelocity = false)),
            "single_macddist" to base.copy(resample = "60min", exitBars = "same", warmupBars = 30,
                exit = base.exit.copy(macdDistanceEnabled = true, macdDistancePct = 25.0)),
            "mtf_default" to base.copy(resample = "60min", exitBars = "30min", warmupBars = 30),
            "mtf_stop_tp" to base.copy(resample = "60min", exitBars = "30min", warmupBars = 30,
                exit = base.exit.copy(stopLossEnabled = true, stopLossPct = 4.0,
                    takeProfitEnabled = true, takeProfitPct = 8.0)),
            "mtf_maxbars" to base.copy(resample = "60min", exitBars = "30min", warmupBars = 30,
                exit = base.exit.copy(maxBarsEnabled = true, maxBars = 40, minHoldBars = 4)),
            "mtf_retrace" to base.copy(resample = "60min", exitBars = "30min", warmupBars = 30,
                exit = base.exit.copy(slopeRetraceEnabled = true, slopeRetracePct = 40.0,
                    slopeRetraceConfirmVelocity = false)),
            "mtf_fastexit" to base.copy(resample = "60min", exitBars = "30min", warmupBars = 30,
                exit = base.exit.copy(fastMacdExitEnabled = true, slopeFlipDownEnabled = true))
        )
    }

    /** Every number that decides a trade, in a fixed order. Rounded to 6 places
     *  so the snapshot cannot fail on floating-point printing alone while still
     *  catching any real move in price or timing. */
    private fun render(): String {
        val sb = StringBuilder()
        val bars = candles(seed = 0x5EED_1234L, n = 1200, startTs = 1_700_000_000L, stepSec = 900L)
        for ((name, cfg) in configs()) {
            for (sym in listOf("AAA", "BBB")) {
                val src = if (sym == "AAA") bars else candles(0xBEEF_4321L, 1200, 1_700_000_000L, 900L)
                val r = StrategyLabEngine.run(sym, src, cfg)
                sb.append("== ").append(name).append(' ').append(sym)
                    .append(" signals=").append(r.signals)
                    .append(" bars=").append(r.bars)
                    .append(" open=").append(r.openCount).append('\n')
                r.gates.toSortedMap().forEach { (k, v) -> sb.append("   gate ").append(k).append('=').append(v).append('\n') }
                for (t in r.trades) {
                    sb.append("   ").append(t.status).append(' ')
                        .append(t.entryTs).append(' ').append("%.6f".format(t.entryPrice)).append(' ')
                        .append(t.exitTs ?: -1L).append(' ')
                        .append(t.exitPrice?.let { "%.6f".format(it) } ?: "-").append(' ')
                        .append("%.6f".format(t.pctReturn)).append(' ')
                        .append(t.barsHeld).append(" | ").append(t.exitReason).append('\n')
                }
            }
        }
        return sb.toString()
    }

    /**
     * The adopted-position path must agree with the replay.
     *
     * Hand it the replay's OWN entry — same price, same bar — and it must reach
     * the same exit, on the same bar, for the same stated reason. If it does
     * not, then a stock bought by hand is being managed on different terms than
     * the identical stock bought by an algo, which is the one outcome that would
     * make this feature worse than not having it.
     */
    @Test
    fun adoptedExitMatchesTheReplay() {
        val bars = candles(seed = 0x5EED_1234L, n = 1200, startTs = 1_700_000_000L, stepSec = 900L)
        var compared = 0
        for ((name, cfg) in configs()) {
            val r = StrategyLabEngine.run("AAA", bars, cfg)
            for (t in r.trades.filter { it.status == "CLOSED" }) {
                val a = StrategyLabEngine.adoptedExit(bars, cfg, t.entryPrice, t.entryTs)
                assertTrue("$name: adopted check should evaluate", a.ok)
                assertTrue("$name: adopted check should fire for a trade the replay closed", a.fires)
                assertEquals("$name: exit bar", t.exitTs, a.barTs)
                assertEquals("$name: exit reason", t.exitReason, a.reason)
                assertEquals("$name: exit price", t.exitPrice!!, a.price, 1e-9)
                compared++
            }
        }
        assertTrue("expected trades to compare against, got $compared", compared >= 10)
        println("ADOPTED-vs-REPLAY: $compared closed trades agreed exactly")
    }

    /**
     * An entry outside the retained window must produce NO verdict.
     *
     * The trap: the MACD-distance guard reads `abs(entryMacd) > 1e-12`, and every
     * comparison against NaN is false — so an unknown entry reading did not
     * disable that rule, it SKIPPED it, and the position exited on the first bare
     * slope flip. The holding-time limit fails the same way, counting bars from
     * the start of the window rather than from the entry.
     *
     * Both fail towards selling. This pins the refusal, because the case is
     * exactly the one the adopted path exists to serve: a stock bought long ago.
     */
    @Test
    fun adoptedExitRefusesWhenEntryPredatesHistory() {
        val bars = candles(seed = 0x5EED_1234L, n = 1200, startTs = 1_700_000_000L, stepSec = 900L)
        val longAgo = bars.first().timestamp - 5_000_000L
        val byName = configs().toMap()

        for (name in listOf("single_macddist", "single_maxbars", "mtf_maxbars")) {
            val a = StrategyLabEngine.adoptedExit(bars, byName.getValue(name), 100.0, longAgo)
            assertTrue("$name: must not claim a verdict", !a.ok)
            assertTrue("$name: must not fire", !a.fires)
            assertTrue("$name: must report the entry reading as unknown", !a.entryMacdKnown)
        }

        // The refusal must be NARROW. With neither affected rule enabled nothing is
        // being skipped, so an old entry is still perfectly judgeable — refusing
        // there would strand long-held positions for no reason at all.
        //
        // Note this has to be built explicitly: the MACD-distance rule ships ON by
        // default, so almost every real config lands in the refusing branch above.
        // That is correct, and it means an entry outside the fetched window gets no
        // strategy exit — only the hard stop. The cure is a wider fetch, not a
        // looser rule.
        val plain = StrategyLabConfig().let {
            it.copy(resample = "60min", exitBars = "same", warmupBars = 30,
                exit = it.exit.copy(macdDistanceEnabled = false, maxBarsEnabled = false,
                    stopLossEnabled = true, stopLossPct = 4.0))
        }
        val b = StrategyLabEngine.adoptedExit(bars, plain, 100.0, longAgo)
        assertTrue("a config using neither affected rule should still evaluate", b.ok)
    }

    /**
     * The deceleration exit's sigma floor must be OPT-IN, not a silent re-tune.
     *
     * `exit.accelNegative` shipped for a long time as a bare `exitAccel < 0`. Adding a sigma
     * floor to it is only safe if minSigma = 0 reproduces that bare rule EXACTLY — otherwise
     * every config that had already enabled it changes behaviour on upgrade, silently, with
     * no edit to the config itself. The entry side defaults to 1.0; this deliberately does not.
     */
    @Test
    fun decelMinSigmaZeroEqualsTheOldBareRule() {
        val bars = candles(seed = 0x5EED_1234L, n = 1200, startTs = 1_700_000_000L, stepSec = 900L)
        val base = StrategyLabConfig().copy(resample = "60min", exitBars = "30min", warmupBars = 30)
        val bare = base.copy(exit = base.exit.copy(accelNegativeEnabled = true))
        assertEquals("minSigma must default to 0", 0.0, bare.exit.accelNegativeMinSigma, 1e-12)
        val explicitZero = base.copy(exit = base.exit.copy(
            accelNegativeEnabled = true, accelNegativeMinSigma = 0.0))

        val a = StrategyLabEngine.run("AAA", bars, bare)
        val b = StrategyLabEngine.run("AAA", bars, explicitZero)
        assertEquals("trade count", a.trades.size, b.trades.size)
        for (i in a.trades.indices) {
            assertEquals("trade $i exit bar", a.trades[i].exitTs, b.trades[i].exitTs)
            assertEquals("trade $i reason", a.trades[i].exitReason, b.trades[i].exitReason)
        }
        // And the label stays the historical one, because it is written into the ledger.
        val fired = a.trades.filter { it.exitReason.contains("acceleration negative") }
        println("BARE-RULE PARITY: ${a.trades.size} trades identical, ${fired.size} closed on the bare decel label")
    }

    /**
     * A positive sigma floor must actually restrict the rule, and must restrict it MORE as
     * the floor rises. This is the property the NSE config depends on: at k=0 the rule fires
     * on ~half of all bars, and the whole point of the floor is to make it selective.
     */
    @Test
    fun decelMinSigmaTightensMonotonically() {
        val bars = candles(seed = 0x5EED_1234L, n = 1200, startTs = 1_700_000_000L, stepSec = 900L)
        // slopeFlipDown OFF so the deceleration rule is the only indicator exit in play —
        // otherwise the slope exit masks it and the count barely moves. This mirrors how the
        // NSE config uses it: as a REPLACEMENT for the slope exit, not an addition to it.
        val base = StrategyLabConfig().copy(resample = "60min", exitBars = "30min", warmupBars = 30)
        val counts = listOf(0.0, 1.0, 2.0, 3.0).map { k ->
            val cfg = base.copy(exit = base.exit.copy(
                slopeFlipDownEnabled = false, accelNegativeEnabled = true, accelNegativeMinSigma = k))
            k to StrategyLabEngine.run("AAA", bars, cfg).trades.count { it.status == "CLOSED" }
        }
        println("DECEL FLOOR: " + counts.joinToString(", ") { "k=${it.first} -> ${it.second} trades" })
        for (i in 1 until counts.size) {
            assertTrue("a higher sigma floor must not fire MORE often " +
                "(k=${counts[i-1].first}:${counts[i-1].second} -> k=${counts[i].first}:${counts[i].second})",
                counts[i].second <= counts[i - 1].second)
        }
        assertTrue("a floor should meaningfully restrict vs the bare rule",
            counts.last().second < counts.first().second)
    }

    @Test
    fun engineOutputIsUnchanged() {
        val now = render()
        if (!goldenFile.exists()) {
            goldenFile.parentFile.mkdirs()
            goldenFile.writeText(now)
            // Not a pass disguised as a skip: it says loudly that this run only
            // RECORDED behaviour and proved nothing about it.
            println("PARITY SNAPSHOT CREATED (${now.lines().size} lines) — rerun to gate against it")
            return
        }
        val golden = goldenFile.readText()
        if (golden != now) {
            val g = golden.lines(); val c = now.lines()
            val firstDiff = (0 until maxOf(g.size, c.size)).firstOrNull {
                g.getOrNull(it) != c.getOrNull(it)
            } ?: -1
            val detail = buildString {
                append("ENGINE BEHAVIOUR CHANGED — trades differ from the recorded snapshot.\n")
                append("first difference at line ").append(firstDiff + 1).append('\n')
                append("  was: ").append(g.getOrNull(firstDiff) ?: "<end of file>").append('\n')
                append("  now: ").append(c.getOrNull(firstDiff) ?: "<end of file>").append('\n')
                append("golden ").append(g.size).append(" lines, current ").append(c.size).append(" lines\n")
            }
            assertEquals(detail, golden, now)
        }
        assertTrue("snapshot should not be empty", now.isNotEmpty())
    }
}

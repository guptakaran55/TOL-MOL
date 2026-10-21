package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

/**
 * THE CRYPTO 50-DAY HOLD — hold while the daily close is above its 50-day simple
 * average, out on a close at or below it (Android spec of 19 Oct 2026).
 *
 * The parity vectors are the spec's own §5 tables. The candles are Binance's
 * BTCUSDC / ETHUSDC daily klines, downloaded once and kept here so the test
 * never depends on the network: every switch date must match exactly.
 */
class CryptoHoldTest {

    private val cfg: StrategyLabConfig =
        (StrategyLabConfig.fromJson(StrategyLabConfig.SMA_HOLD_JSON, "test")
            as StrategyLabConfig.Companion.ParseResult.Ok).config

    private fun klines(sym: String): List<CandleData> =
        File("src/test/resources/crypto_hold/${sym}_1d.csv").readLines().drop(1).map { l ->
            val p = l.split(',')
            CandleData(p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(),
                p[4].toDouble(), p[5].toDouble().toLong())
        }

    private fun day(ts: Long) = Instant.ofEpochSecond(ts).atZone(ZoneOffset.UTC).toLocalDate().toString()

    /** Every switch the engine makes, as "date ACTION close sma", from [from] on. */
    private fun switches(sym: String, from: String): List<String> {
        val run = StrategyLabEngine.run(sym, klines(sym), cfg)
        assertNull(run.error)
        val out = ArrayList<Pair<Long, String>>()
        for (t in run.trades) {
            out.add(t.entryTs to "BUY ${t.entryReason}")
            if (t.exitTs != null) out.add(t.exitTs!! to "SELL ${t.exitReason}")
        }
        return out.sortedBy { it.first }.filter { day(it.first) >= from }.map { "${day(it.first)} ${it.second}" }
    }

    @Test fun `BTCUSDC switches on exactly the spec's days`() {
        assertEquals(listOf(
            "2026-05-22 SELL close below SMA50 (75478.21 vs 76398.01)",
            "2026-05-23 BUY close above SMA50 (76676.41 vs 76592.32)",
            "2026-05-26 SELL close below SMA50 (75848.94 vs 77091.32)",
            "2026-07-14 BUY close above SMA50 (65003.84 vs 64353.94)",
            "2026-07-16 SELL close below SMA50 (63792.48 vs 63920.07)",
            "2026-07-17 BUY close above SMA50 (63902.43 vs 63727.18)",
            "2026-07-31 SELL close below SMA50 (62839.63 vs 63393.64)",
            "2026-08-02 BUY close above SMA50 (63519.12 vs 63359.52)"),
            switches("BTCUSDC", "2026-05-22"))
    }

    @Test fun `ETHUSDC switches on exactly the spec's days`() {
        assertEquals(listOf(
            "2025-10-09 SELL close below SMA50 (4371.27 vs 4408.46)",
            "2026-01-02 BUY close above SMA50 (3124.71 vs 3007.74)",
            "2026-01-20 SELL close below SMA50 (2937.10 vs 3087.24)",
            "2026-03-15 BUY close above SMA50 (2178.55 vs 2138.94)",
            "2026-03-27 SELL close below SMA50 (1991.95 vs 2044.25)",
            "2026-03-31 BUY close above SMA50 (2104.36 vs 2039.55)",
            "2026-05-15 SELL close below SMA50 (2223.77 vs 2250.99)",
            "2026-07-10 BUY close above SMA50 (1796.10 vs 1773.42)"),
            switches("ETHUSDC", "2025-10-09"))
    }

    // ── the rule's edges, on made-up days ──

    private val d0 = 1_767_225_600L   // 2026-01-01 00:00 UTC
    private fun days(vararg closes: Double) = closes.mapIndexed { i, c ->
        CandleData(d0 + i * 86_400L, c, c, c, c, 1L)
    }
    private fun flat(n: Int, px: Double = 100.0) = DoubleArray(n) { px }

    @Test fun `no decision before fifty completed closes`() {
        // Rising from the first day: the earliest possible buy is the 50th close.
        val run = StrategyLabEngine.run("X", days(*DoubleArray(60) { 100.0 + it }), cfg)
        assertEquals(d0 + 49 * 86_400L, run.trades.first().entryTs)
        assertEquals(d0 + 49 * 86_400L, run.firstTradableTs)
    }

    @Test fun `a close exactly on the average is out, not in`() {
        val run = StrategyLabEngine.run("X", days(*flat(55)), cfg)
        assertTrue(run.trades.isEmpty())
        val up = StrategyLabEngine.run("X", days(*(flat(55) + 100.5)), cfg)
        assertEquals(1, up.trades.size)
        assertEquals(d0 + 55 * 86_400L, up.trades[0].entryTs)
    }

    @Test fun `held positions are sold on the first close at or below the average`() {
        // In on day 50, then back to exactly the (rising) average's level is out.
        val closes = DoubleArray(50) { 100.0 } + 110.0 + 100.0
        val run = StrategyLabEngine.run("X", days(*closes), cfg)
        assertEquals("CLOSED", run.trades[0].status)
        assertEquals(d0 + 51 * 86_400L, run.trades[0].exitTs)
    }

    @Test fun `the still-forming day never counts`() {
        // 30-minute bars: 55 whole days at 100 then up, and a half day crashing.
        val bars = ArrayList<CandleData>()
        for (d in 0 until 56) for (k in 0 until 48) {
            val px = if (d < 50) 100.0 else 120.0
            bars.add(CandleData(d0 + d * 86_400L + k * 1_800L, px, px, px, px, 1L))
        }
        for (k in 0 until 24) bars.add(CandleData(d0 + 56 * 86_400L + k * 1_800L, 50.0, 50.0, 50.0, 50.0, 1L))
        val run = StrategyLabEngine.runLiveClosed("X", bars, cfg, 1_800L)
        assertEquals(d0 + 55 * 86_400L, run.lastBarTs)          // the half day is not a day
        assertEquals("OPEN", run.trades.single().status)         // so its crash sells nothing
        assertEquals(d0 + 50 * 86_400L, run.trades.single().entryTs)
    }

    @Test fun `switched on mid-trend, it follows the current signal`() {
        // Above the line for weeks: tracking starting today buys today, not after
        // waiting for a fresh cross that may be months away.
        val closes = DoubleArray(80) { 100.0 + it }
        val start = d0 + 79 * 86_400L
        val run = StrategyLabEngine.runLiveClosed("X", days(*closes), cfg, 86_400L, start)
        assertEquals(start, run.trades.single().entryTs)
        assertEquals(start, run.lastBarTs)
    }

    @Test fun `no split repair on coins - a real crash is a real crash`() {
        // A -40% day would be "repaired" as a split on stocks; here it must sell.
        val closes = DoubleArray(50) { 100.0 + it } + 90.0
        val run = StrategyLabEngine.run("X", days(*closes), cfg)
        val last = run.trades.last()
        assertEquals("CLOSED", last.status)
        assertEquals(90.0, last.exitPrice!!, 1e-9)
    }

    @Test fun `the reading on the newest day is reported`() {
        val r = StrategyLabEngine.run("X", days(*(flat(49) + 101.0)), cfg).holdLast
        assertNotNull(r); r!!
        assertTrue(r.warm); assertTrue(r.above)
        assertEquals(100.02, r.sma, 1e-9)
        assertFalse(StrategyLabEngine.run("X", days(*flat(10)), cfg).holdLast!!.warm)
    }

    @Test fun `a position bought by hand is judged by the same line`() {
        val closes = DoubleArray(50) { 100.0 } + 110.0 + 111.0 + 90.0
        val bars = days(*closes)
        val ex = StrategyLabEngine.adoptedExit(bars, cfg, 110.0, d0 + 50 * 86_400L, 86_400L)
        assertTrue(ex.ok); assertTrue(ex.fires)
        assertEquals(d0 + 52 * 86_400L, ex.barTs)
        assertTrue(ex.reason.startsWith("close below SMA50"))
    }

    @Test fun `the review draws the average and one check per side`() {
        val p = StrategyLabEngine.reviewPayload("X", days(*(DoubleArray(60) { 100.0 + it })), cfg, 86_400L)
        assertNull(p.error)
        assertNull(p.bars.first().level)                     // not formed on day one
        assertNotNull(p.bars.last().level)
        assertTrue(p.bars.last().entryCore.single().ok)
        assertEquals(1, p.bars.count { it.entryMarker == 1 })
    }

    // ── the config ──

    @Test fun `the shipped config is the tested rule`() {
        assertTrue(cfg.isSmaHold)
        assertEquals(50, cfg.holdSmaLength)
        assertEquals("1D", cfg.resample)
        assertEquals(0.2, cfg.slippagePct, 1e-12)
        assertFalse(cfg.exit.stopLossEnabled)
        assertFalse(cfg.exit.takeProfitEnabled)
        assertFalse(cfg.entry.slopeFlipUpEnabled)
        assertFalse(cfg.gates.smaEnabled)
    }

    @Test fun `on crypto it is fed daily klines`() {
        val plan = FeedPlan.plan(cfg, "binance__bhold")
        assertTrue(plan.ok)
        assertEquals("1d", plan.interval)
        assertEquals(86_400L, plan.sourceSec)
    }

    private fun rejected(json: String) =
        StrategyLabConfig.fromJson(json, "t") is StrategyLabConfig.Companion.ParseResult.Rejected

    @Test fun `the variants that tested worse are refused`() {
        assertTrue(rejected("""{"strategy":"smaHold","sma":{"kind":"ema","length":50}}"""))
        assertTrue(rejected("""{"strategy":"smaHold","resample":"60min"}"""))
        assertTrue(rejected("""{"strategy":"smaHold","resample":"4h"}"""))
        assertTrue(rejected("""{"strategy":"smaHold","exit":{"stopLoss":{"enabled":true,"pct":5}}}"""))
        assertTrue(rejected("""{"strategy":"smaHold","exit":{"takeProfit":{"enabled":true}}}"""))
        assertFalse(rejected("""{"strategy":"smaHold"}"""))
        assertTrue(rejected("""{"strategy":"rsi","entry":{},"exit":{}}"""))
    }

    // ── what the card says per coin ──

    @Test fun `each coin's line says where it stands and what follows`() {
        val up = StrategyLabEngine.HoldReading(0L, 65003.84, 64353.94, 200, 50)
        val down = StrategyLabEngine.HoldReading(0L, 0.12346, 0.13, 200, 50)
        assertEquals("close 65003.84 vs SMA50 64353.94 · above ✓ — held; sells on a daily close at or below the line",
            StrategyLabRunner.holdNote(up, held = true, engineLong = true))
        assertTrue(StrategyLabRunner.holdNote(up, held = false, engineLong = true).contains("sold by hand"))
        assertEquals("close 0.1235 vs SMA50 0.1300 · below ✗ — out; buys on the first daily close above",
            StrategyLabRunner.holdNote(down, held = false, engineLong = false))
        assertEquals("warming up — 12 of 50 daily closes",
            StrategyLabRunner.holdNote(StrategyLabEngine.HoldReading(0L, 1.0, Double.NaN, 12, 50), false, false))
    }
}

package com.signalscope.app.trading

import com.signalscope.app.trading.RvGate.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RV GATE ANALYSIS — the lines drawn on the chart must be exactly where the
 * gate's own rule ([RvGate.decide]) flips, or the screen explains a different
 * gate from the one trading. And the track record must compare like with like.
 *
 * Day used: low 100, high 110 (range 10), ATR 4, an hour after the open.
 */
class RvGateAnalysisTest {
    private val eps = 1e-6

    private fun decide(side: Side, price: Double, vel: Double?, high: Double = 110.0, low: Double = 100.0,
                       mem: RvGate.Memory? = null) =
        RvGate.decide(side, price, high, low, 4.0, vel, 0L, 60L, memory = mem)

    @Test fun `a buy goes at the bottom-40 line and not a cent over it`() {
        val lv = RvGateAnalysis.levels(Side.BUY, 104.0, 110.0, 100.0, 4.0, 0.0, null)
        assertEquals(104.0, lv.zoneEdge!!, eps)
        assertTrue(decide(Side.BUY, lv.zoneEdge!! - eps, 0.0).go)
        assertFalse(decide(Side.BUY, lv.zoneEdge!! + 0.01, 0.0).go)
    }

    @Test fun `on a narrow day the fall that widens the range is exactly where a buy goes`() {
        // Range 1 under ½ ATR (2): the range test is off until the price reaches 101 - 2 = 99.
        val lv = RvGateAnalysis.levels(Side.BUY, 100.5, 101.0, 100.0, 4.0, -0.5, null)
        assertFalse(lv.rangeMeans)
        assertEquals(99.0, lv.widenAt!!, eps)
        assertTrue(decide(Side.BUY, 99.0, -0.5, high = 101.0).go)
        assertFalse(decide(Side.BUY, 99.01, -0.5, high = 101.0).go)
    }

    @Test fun `the rising line is the last 15-minute close plus the dead band`() {
        val vel = 0.21
        val lv = RvGateAnalysis.levels(Side.BUY, 105.0, 110.0, 100.0, 4.0, vel, null)
        assertEquals(104.79, lv.velRef!!, eps)
        assertEquals(104.99, lv.goLine!!, eps)
        assertTrue(decide(Side.BUY, 105.0, vel).go)                    // over the line
        assertFalse(decide(Side.BUY, 105.0, 0.19).go)                  // under it
    }

    @Test fun `near the high a rise does not count, and the chase floor marks where that starts`() {
        val lv = RvGateAnalysis.levels(Side.BUY, 109.0, 110.0, 100.0, 4.0, 0.5, null)
        assertEquals(108.0, lv.chaseFloor!!, eps)
        assertFalse(decide(Side.BUY, 109.0, 0.5).go)
        val ways = RvGateAnalysis.ways(Side.BUY, 109.0, lv, 0.5, 60L, 0L, 0L, "€")
        assertEquals(false, ways.first { it.id == "dir" }.met)
    }

    @Test fun `the turn line is where a dip-and-turn buy goes`() {
        val mem = RvGate.remember(RvGate.Memory(109.0, 108.0), 108.4, 4.0)
        val lv = RvGateAnalysis.levels(Side.BUY, 108.4, 110.0, 100.0, 4.0, 0.0, mem)
        assertEquals(108.4, lv.turnAt!!, eps)
        assertTrue(decide(Side.BUY, 108.4 + eps, 0.0, mem = mem).go)
        val before = RvGateAnalysis.levels(Side.BUY, 109.0, 110.0, 100.0, 4.0, 0.0, RvGate.Memory(109.0, 0.0))
        assertEquals(108.2, before.dipAt!!, eps)                       // 0.2 ATR under its high
        assertNull(before.turnAt)
    }

    @Test fun `a sale goes at the top-40 line and on the falling line`() {
        val lv = RvGateAnalysis.levels(Side.SELL, 106.0, 110.0, 100.0, 4.0, -0.3, null)
        assertEquals(106.0, lv.zoneEdge!!, eps)
        assertNull(lv.chaseFloor); assertNull(lv.dipAt)
        assertTrue(decide(Side.SELL, 106.0 + eps, 0.0).go)
        assertFalse(decide(Side.SELL, 105.99, 0.0).go)
        assertEquals(106.3 - 0.2, lv.goLine!!, eps)                    // last close 106.3, minus 0.05 ATR
        assertTrue(decide(Side.SELL, 105.0, -0.21).go)
    }

    @Test fun `every way is listed, and the two hours count as met once they are up`() {
        val lv = RvGateAnalysis.levels(Side.BUY, 105.0, 110.0, 100.0, 4.0, 0.0, null)
        val ways = RvGateAnalysis.ways(Side.BUY, 105.0, lv, 0.0, 200L, RvGate.MAX_WAIT_MS, 0L, "$")
        assertEquals(listOf("range", "dir", "dip", "time"), ways.map { it.id })
        assertEquals(true, ways.last().met)
        val sale = RvGateAnalysis.ways(Side.SELL, 105.0, lv, 0.0, 200L, 0L, 0L, "$")
        assertEquals(listOf("range", "dir", "time"), sale.map { it.id })
    }

    @Test fun `with no 15-minute prices the direction is unknown, not unmet`() {
        val lv = RvGateAnalysis.levels(Side.BUY, 105.0, 110.0, 100.0, 4.0, null, null)
        val dir = RvGateAnalysis.ways(Side.BUY, 105.0, lv, null, 5L, 0L, 0L, "$").first { it.id == "dir" }
        assertNull(dir.met)
    }

    @Test fun `the first-look note says what waiting has saved or cost`() {
        val lv = RvGateAnalysis.levels(Side.BUY, 99.0, 110.0, 100.0, 4.0, 0.0, null)
        val ns = RvGateAnalysis.notes(Side.BUY, "us", 99.0, lv, 0.0, 60L, 0L, 100.0, 0L, 0.0, false, 0L, "$")
        assertEquals("plus", ns.first().tone)
        assertTrue(ns.first().text.contains("saved $1.00"))
        val worse = RvGateAnalysis.notes(Side.BUY, "us", 101.0, lv, 0.0, 60L, 0L, 100.0, 0L, 0.0, false, 0L, "$")
        assertEquals("minus", worse.first().tone)
    }

    // ── the track record ───────────────────────────────────────────────────

    private fun l(at: Long, ev: String, key: String, vararg f: Pair<String, Any?>) = RvJournal.line(at, ev, key, mapOf(*f))

    @Test fun `held orders are scored first look against release, at-once and unticked ones apart`() {
        val m = 60_000L
        val lines = listOf(
            // held 30 min, let go rising, 1% cheaper
            l(0, "first", "you|a", "space" to "DE", "side" to "BUY", "price" to 100.0, "asked" to true),
            l(30 * m, "release", "you|a", "price" to 99.0, "why" to "rising on the 15-minute prices", "waitedMin" to 30L),
            l(31 * m, "fill", "you|a", "price" to 99.2, "qty" to 1.0),
            // held to the limit, 2% dearer
            l(0, "first", "algo|de__x|S-1|BUY", "space" to "DE", "side" to "BUY", "price" to 50.0),
            l(120 * m, "release", "algo|de__x|S-1|BUY", "price" to 51.0, "why" to "held back 2 hours — starting anyway", "waitedMin" to 120L),
            // went at once
            l(0, "first", "you|b", "space" to "DE", "side" to "BUY", "price" to 10.0, "asked" to true),
            l(1000, "release", "you|b", "price" to 10.0, "why" to "in the bottom 40% of today's range", "waitedMin" to 0L),
            // unticked: not the gate's
            l(0, "first", "you|c", "space" to "DE", "side" to "BUY", "price" to 10.0, "asked" to false),
            l(1000, "release", "you|c", "price" to 9.0, "why" to "rising", "waitedMin" to 50L),
            // another market, another side
            l(0, "first", "you|d", "space" to "US", "side" to "BUY", "price" to 10.0, "asked" to true),
            l(0, "first", "you|e", "space" to "DE", "side" to "SELL", "price" to 10.0, "asked" to true),
            // never let go
            l(0, "first", "you|f", "space" to "DE", "side" to "BUY", "price" to 10.0, "asked" to true),
            "not json")
        val r = RvGateAnalysis.record(lines, "DE", "BUY", 30)
        assertEquals(4, r.looked)
        assertEquals(1, r.atOnce)
        assertEquals(2, r.held)
        assertEquals(1, r.neverLetGo)
        val all = r.all!!
        assertEquals(2, all.n); assertEquals(1, all.better)
        assertEquals((1.0 - 2.0) / 2.0, all.avgPct, 1e-9)
        assertEquals(1.0, r.groups.first { it.reason == "rising" }.avgPct, 1e-9)
        assertEquals(-2.0, r.groups.first { it.reason == "2-hour limit" }.avgPct, 1e-9)
        assertEquals(1, r.fills); assertEquals(0.8, r.avgFillPct!!, 1e-9)
    }

    @Test fun `for a sale a higher release price is the better one`() {
        val lines = listOf(
            l(0, "first", "you|s", "space" to "US", "side" to "SELL", "price" to 100.0, "asked" to true),
            l(600_000, "release", "you|s", "price" to 101.0, "why" to "in the top 40% of today's range", "waitedMin" to 10L))
        val r = RvGateAnalysis.record(lines, "US", "SELL", 30)
        assertEquals(1.0, r.all!!.avgPct, 1e-9)
        assertEquals("top of range", r.groups.single().reason)
    }

    @Test fun `a retried algo order starts a new episode`() {
        val k = "algo|us__x|A-1|BUY"
        val lines = listOf(
            l(0, "first", k, "space" to "US", "side" to "BUY", "price" to 100.0),
            l(600_000, "release", k, "price" to 99.0, "why" to "rising", "waitedMin" to 10L),
            l(900_000, "first", k, "space" to "US", "side" to "BUY", "price" to 98.0),
            l(1_500_000, "release", k, "price" to 99.0, "why" to "rising", "waitedMin" to 10L))
        val r = RvGateAnalysis.record(lines, "US", "BUY", 30)
        assertEquals(2, r.held)
        assertNotNull(r.all)
        assertEquals((1.0 + (98.0 - 99.0) / 98.0 * 100.0) / 2.0, r.all!!.avgPct, 1e-9)
    }
}

package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import com.signalscope.app.data.FloorAlgoStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TreeMap

/**
 * THE 💡 REPLAY FOR THE BUILT-IN ALGOS — the live engine's own fill, seat and
 * exit rules, walked over past prices (FloorReplay, 25 Oct 2026).
 */
class FloorReplayTest {
    private val utc = ZoneOffset.UTC
    private val s = FloorAlgoStore.Settings(capital = 1000.0, seats = 5, lowDays = 3, secondPct = 2.0,
        holdDays = 10, stopPct = 4.0, targetPct = 7.0, trendFilter = false)
    private val noCharges: (Double, Double, Boolean, String) -> Double = { _, _, _, _ -> 0.0 }

    /** Weekdays from 1 Sep 2026, one daily candle each at 09:00 UTC. */
    private fun dailyDays(ohlc: List<DoubleArray>): TreeMap<LocalDate, List<CandleData>> {
        val out = TreeMap<LocalDate, List<CandleData>>()
        var d = LocalDate.of(2026, 9, 1)
        for (p in ohlc) {
            while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
            out[d] = listOf(CandleData(d.atTime(9, 0).toEpochSecond(utc), p[0], p[1], p[2], p[3], 1L))
            d = d.plusDays(1)
        }
        return out
    }
    private fun bar(o: Double, h: Double, l: Double, c: Double) = doubleArrayOf(o, h, l, c)

    @Test fun `a dip below the 3-day low buys half a seat at the floor and the target sells it`() {
        val flat = List(5) { bar(100.0, 101.0, 99.0, 100.0) }
        val days = dailyDays(flat + listOf(bar(100.0, 100.5, 98.0, 99.5), bar(100.0, 107.0, 99.5, 106.0)))
        val dip = days.keys.elementAt(5)
        val trips = FloorReplay.floorOrders(mapOf("AAA" to days), listOf("AAA"), { _, _ -> true }, null,
            s, days.firstKey(), days.lastKey(), utc, noCharges)
        assertEquals(1, trips.size)
        val t = trips[0]
        assertEquals(dip, t.buyDay)
        assertEquals("target", t.reason)
        // 100 at 99 (the floor; the 2% order at 97.02 never traded), sold at 99 × 1.07.
        assertEquals(100.0, t.cost, 1e-9)
        assertEquals(100.0 / 99.0 * 99.0 * 1.07, t.value, 1e-9)
    }

    @Test fun `only free seats buy, the fill nearest its floor at the open first`() {
        val one = s.copy(seats = 1)
        val flat = List(5) { bar(100.0, 101.0, 99.0, 100.0) }
        val near = dailyDays(flat + listOf(bar(99.2, 99.5, 98.0, 99.0)))     // opened 0.2% over its floor
        val far = dailyDays(flat + listOf(bar(101.0, 101.5, 98.0, 99.0)))    // opened 2% over
        val trips = FloorReplay.floorOrders(mapOf("FAR" to far, "NEAR" to near), listOf("FAR", "NEAR"),
            { _, _ -> true }, null, one, near.firstKey(), near.lastKey(), utc, noCharges)
        assertEquals(listOf("NEAR"), trips.map { it.symbol })
    }

    @Test fun `a position still held at the end is valued at the last close`() {
        val flat = List(5) { bar(100.0, 101.0, 99.0, 100.0) }
        val days = dailyDays(flat + listOf(bar(100.0, 100.5, 98.5, 99.0), bar(99.0, 100.0, 98.6, 99.5)))
        val t = FloorReplay.floorOrders(mapOf("AAA" to days), listOf("AAA"), { _, _ -> true }, null,
            s, days.firstKey(), days.lastKey(), utc, noCharges).single()
        assertEquals("open", t.reason)
        assertEquals(100.0 / 99.0 * 99.5, t.value, 1e-9)
    }

    @Test fun `the trend rule is read from the days before, never the day itself`() {
        val rising = List(60) { i -> 100.0 + i }
        val c = rising.mapIndexed { i, p ->
            val d = LocalDate.of(2026, 1, 1).plusDays(i.toLong())
            CandleData(d.atTime(9, 0).toEpochSecond(utc), p, p, p, p, 1L)
        }
        val book = FloorReplay.TrendBook(c, null, utc, 1)
        assertEquals(1, book.day(LocalDate.of(2026, 1, 1).plusDays(59)))
        assertNull(book.day(LocalDate.of(2026, 1, 1).plusDays(10)))          // fewer than 30 closes before it
        assertNull(book.month(LocalDate.of(2026, 3, 1)))                     // no monthly prices given
    }

    @Test fun `the summary counts money against the algo's capital`() {
        val d0 = LocalDate.of(2025, 10, 24); val d1 = LocalDate.of(2026, 10, 23)
        val trips = listOf(
            FloorReplay.Trip("A", d0, d0.plusDays(3), 200.0, 220.0, 0.0, "target"),
            FloorReplay.Trip("B", d0.plusDays(5), d0.plusDays(8), 200.0, 190.0, 0.0, "stop"))
        val r = FloorReplay.summarize(trips, 1000.0, d0, d1, 2)
        assertEquals(1.0, r.compoundedPct, 1e-9)
        assertEquals(1, r.wins)
        assertEquals(10.0 / 1020.0 * 100.0, r.maxDrawdownPct, 1e-9)
        assertTrue(r.cagrPct > 0.9 && r.cagrPct < 1.1)
    }

    @Test fun `the news-outlook algos and campaigns have no replay`() {
        fun algo(kind: String, uni: String = "top") = FloorAlgoStore.Algo(id = "f1", name = "x", kind = kind, space = "de",
            settings = FloorAlgoStore.Settings(universeMode = uni))
        assertNull(FloorReplay.unsupported(algo(FloorAlgoStore.KIND_FLOOR)))
        assertNull(FloorReplay.unsupported(algo(FloorAlgoStore.KIND_MACD)))
        assertTrue(FloorReplay.unsupported(algo(FloorAlgoStore.KIND_MACD, FloorAlgoStore.UNI_MONTHLY_LOW)) != null)
        assertTrue(FloorReplay.unsupported(algo(FloorAlgoStore.KIND_CHAIN)) != null)
        assertTrue(FloorReplay.unsupported(algo(FloorAlgoStore.KIND_CAMPAIGN)) != null)
    }
}

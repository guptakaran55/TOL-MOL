package com.signalscope.app.trading

import com.signalscope.app.data.FloorAlgoStore
import com.signalscope.app.data.FloorAlgoStore.Algo
import com.signalscope.app.data.FloorAlgoStore.Leg
import com.signalscope.app.data.FloorAlgoStore.Member
import com.signalscope.app.data.FloorAlgoStore.Order
import com.signalscope.app.data.FloorAlgoStore.Trade
import com.signalscope.app.data.FloorJournalStore
import com.signalscope.app.screener.DailyBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * THE SCAN JOURNAL: what each stock's day came to, how near it came, and the
 * price its returns are measured from.
 */
class FloorJournalTest {

    private fun d(s: String) = LocalDate.parse(s)
    private fun bar(day: String, close: Double, low: Double = close) =
        DailyBar(date = d(day), close = close, high = close, low = low)

    private val day = d("2026-10-06")

    private val floorAlgo = Algo(id = "f1", kind = FloorAlgoStore.KIND_FLOOR, planDay = "2026-10-06",
        settings = FloorAlgoStore.Settings(trendFilter = true),
        universe = listOf(
            Member("AAA", "Aaa", 30.0, week = 1, day = 1, note = "orders resting"),
            Member("BBB", "Bbb", 25.0, week = 1, day = 1, note = "orders resting"),
            Member("CCC", "Ccc", 20.0, week = -1, day = 1, note = "weekly slope not rising"),
            Member("DDD", "Ddd", 15.0, week = 1, day = 1, note = "held — nothing new"),
            Member("EEE", "Eee", 10.0, note = "no daily prices")),
        orders = listOf(
            Order("AAA", "Aaa", 100.0, first = Leg(100.0, 100.0), second = Leg(98.0, 100.0), status = "ENDED"),
            Order("BBB", "Bbb", 50.0, first = Leg(50.0, 100.0, filledSec = 1L, fillPrice = 50.0),
                second = Leg(49.0, 100.0), status = "ENDED")),
        trades = listOf(Trade(id = "BBB|2026-10-06", symbol = "BBB", day = "2026-10-06", firstSec = 1L,
            qty = 2.0, cost = 100.0, halves = 1, firstPrice = 50.0, status = "OPEN")))

    private val daily = mapOf(
        "AAA" to listOf(bar("2026-10-01", 104.0), bar("2026-10-02", 103.0), bar("2026-10-05", 102.0),
            bar("2026-10-06", 102.0, low = 100.8),
            bar("2026-10-07", 103.0), bar("2026-10-08", 101.0), bar("2026-10-09", 105.84),
            bar("2026-10-12", 104.0), bar("2026-10-13", 110.88)),
        "BBB" to listOf(bar("2026-10-06", 51.0, low = 49.5)),
        "CCC" to listOf(bar("2026-10-01", 21.0, low = 20.0), bar("2026-10-02", 20.0, low = 19.5),
            bar("2026-10-05", 21.5, low = 21.0), bar("2026-10-06", 19.8, low = 19.2)),
        "DDD" to listOf(bar("2026-10-06", 70.0)))

    private val rows = FloorJournal.rows(floorAlgo, day, daily).associateBy { it.symbol }

    @Test fun `an order that never filled - how near it came, and the low as its price`() {
        val r = rows.getValue("AAA")
        assertEquals(FloorJournalStore.NO_FILL, r.result)
        assertEquals(100.0, r.floor, 1e-9)
        assertEquals(98.0, r.second, 1e-9)
        assertEquals(0.8, r.gap!!, 1e-9)
        assertEquals(100.8, r.ref, 1e-9)      // the nearest an order could have been and filled
        assertEquals(1, r.rank)
    }

    @Test fun `a fill is journaled as bought, from its own price`() {
        val r = rows.getValue("BBB")
        assertEquals(FloorJournalStore.BOUGHT, r.result)
        assertEquals(1, r.halves)
        assertEquals(50.0, r.ref, 1e-9)
        assertEquals(-1.0, r.gap!!, 1e-9)
    }

    @Test fun `a dip the trend kept out - its floor from the daily lows, measured from the floor`() {
        val r = rows.getValue("CCC")
        assertEquals(FloorJournalStore.TREND_BLOCKED, r.result)
        assertEquals(19.5, r.floor, 1e-9)      // lowest of the three days before
        assertTrue(r.gap!! < 0.0)               // it traded under its floor
        assertEquals(19.5, r.ref, 1e-9)         // an order at the floor would have filled there
    }

    @Test fun `with the trend filter off nothing is kept out by the trend`() {
        val off = floorAlgo.copy(settings = FloorAlgoStore.Settings(trendFilter = false),
            orders = floorAlgo.orders + Order("CCC", "Ccc", 19.5, first = Leg(19.5, 100.0),
                second = Leg(19.11, 100.0), status = "ENDED"),
            universe = floorAlgo.universe.map { if (it.symbol == "CCC") it.copy(note = "orders resting") else it })
        val r = FloorJournal.rows(off, day, daily).associateBy { it.symbol }
        assertEquals(FloorJournalStore.NO_FILL, r.getValue("CCC").result)   // it had orders, falling week or not
        assertEquals(-1, r.getValue("CCC").week)                             // the slope is still recorded
        val macdOff = Algo(id = "m2", kind = FloorAlgoStore.KIND_MACD, planDay = "2026-10-06",
            universe = listOf(Member("AAA", week = -1, day = -1, h4 = 1, looks = 12)))
        assertEquals(FloorJournalStore.NO_TURN, FloorJournal.rows(macdOff, day, daily).single().result)
        val macdOn = macdOff.copy(settings = FloorAlgoStore.Settings(trendFilter = true))
        assertEquals(FloorJournalStore.TREND_BLOCKED, FloorJournal.rows(macdOn, day, daily).single().result)
    }

    @Test fun `held and unpriced stocks are said to be so`() {
        assertEquals(FloorJournalStore.HELD, rows.getValue("DDD").result)
        assertEquals(FloorJournalStore.NO_DATA, rows.getValue("EEE").result)   // no trend could be read
        assertNull(rows.getValue("EEE").gap)
    }

    @Test fun `returns fill in as later closes arrive, and the row is done at five days`() {
        val r = rows.getValue("AAA")
        val partial = FloorJournal.withOutcomes(listOf(r),
            mapOf("AAA" to daily.getValue("AAA").take(7)), d("2026-10-10")).single()
        assertEquals((103.0 / 100.8 - 1) * 100, partial.ret1!!, 1e-9)
        assertEquals(5.0, partial.ret3!!, 1e-9)
        assertNull(partial.ret5)
        assertFalse(partial.done)
        val full = FloorJournal.withOutcomes(listOf(partial), daily, d("2026-10-14")).single()
        assertEquals(10.0, full.ret5!!, 1e-9)
        assertTrue(full.done)
    }

    @Test fun `MACD turns - a turn the 4-hour blocked is measured from the price when it was seen`() {
        val a = Algo(id = "m1", kind = FloorAlgoStore.KIND_MACD, planDay = "2026-10-06",
            universe = listOf(
                Member("AAA", week = 1, day = 1, h4 = -1, looks = 30, h4Up = 4, bothUp = 9,
                    turnSec = 123L, turnPx = 101.5, turnBlocked = true),
                Member("BBB", week = 1, day = 1, h4 = 1, looks = 30, h4Up = 30)))
        val r = FloorJournal.rows(a, day, daily).associateBy { it.symbol }
        assertEquals(FloorJournalStore.H4_BLOCKED, r.getValue("AAA").result)
        assertEquals(101.5, r.getValue("AAA").ref, 1e-9)
        assertEquals(0.0, r.getValue("AAA").floor, 1e-9)
        assertEquals(FloorJournalStore.NO_TURN, r.getValue("BBB").result)
        assertEquals(51.0, r.getValue("BBB").ref, 1e-9)    // the close
        assertTrue(FloorJournal.summary(r.values.toList(), macd = true).contains("1 turned up while the 4-hour was not rising"))
    }

    @Test fun `the log line counts orders, fills, near misses and kept-out dips`() {
        val s = FloorJournal.summary(rows.values.toList(), macd = false)
        assertTrue(s, s.startsWith("2 with orders, 1 filled, 1 more within 1%"))
        assertTrue(s, s.contains("1 dipped below their floor"))
    }

    @Test fun `the floor of the daily lows needs that many days`() {
        assertEquals(19.5, FloorJournal.priorDailyLow(daily.getValue("CCC"), day, 3)!!, 1e-9)
        assertNull(FloorJournal.priorDailyLow(daily.getValue("BBB"), day, 3))
    }
}

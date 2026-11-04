package com.signalscope.app.network

import com.signalscope.app.data.CandleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Two years of bars downloaded once a day, the latest days after, checked against each other. */
class BarHistoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun bars(fromHour: Int, toHour: Int, px: (Int) -> Double = { 100.0 + it }) =
        (fromHour..toHour).map { h -> CandleData(h * 3600L, px(h), px(h) + 1, px(h) - 1, px(h), 1000L) }

    @Test fun theFreshBarsReplaceTheKeptOnesWhereTheyMeet() {
        val kept = bars(0, 100)                       // this morning's whole download
        val fresh = bars(60, 130)                     // the latest days, now
        assertTrue(BarHistory.agrees(kept, fresh))
        val m = BarHistory.merge(kept, fresh, 0L)
        assertEquals((0..130).map { it * 3600L }, m.map { it.timestamp })
        assertEquals(fresh.last(), m.last())
        // Nothing older than the window is kept.
        assertEquals(10 * 3600L, BarHistory.merge(kept, fresh, 10 * 3600L).first().timestamp)
    }

    @Test fun aChangedHistoryIsNotMerged() {
        val kept = bars(0, 100)
        // A split: every bar the fresh download shares with the kept copy is halved.
        assertFalse(BarHistory.agrees(kept, bars(60, 130) { (100.0 + it) / 2 }))
        // One corrected bar is enough.
        assertFalse(BarHistory.agrees(kept, bars(60, 130) { if (it == 80) 250.0 else 100.0 + it }))
        // The last kept bar may have been forming when kept: a difference there is not a change.
        assertTrue(BarHistory.agrees(kept, bars(60, 130) { if (it == 100) 999.0 else 100.0 + it }))
    }

    @Test fun freshBarsThatDoNotReachTheKeptOnesAreNoMatch() {
        assertFalse(BarHistory.agrees(bars(0, 100), bars(101, 130)))        // a gap
        assertFalse(BarHistory.agrees(bars(0, 100), bars(99, 130)))         // one shared bar — the last kept, left out
        assertFalse(BarHistory.agrees(bars(0, 100), emptyList()))
    }

    @Test fun onlyLongIntradayHistoriesAreKept() {
        assertTrue(BarHistory.applies("730d", "60m", false))
        assertTrue(BarHistory.applies("60d", "15m", false))
        assertFalse(BarHistory.applies("5d", "60m", false))                // short: fetched whole
        assertFalse(BarHistory.applies("1y", "1d", false))                 // daily: small
        assertFalse(BarHistory.applies("60d", "15m", true))                // a live mark with extended hours
        assertEquals(730, BarHistory.rangeDays("730d"))
        assertEquals(365, BarHistory.rangeDays("1y"))
        assertEquals("Europe/Berlin", BarHistory.zoneOf("SAP.DE").id)
        assertEquals("Asia/Kolkata", BarHistory.zoneOf("TCS.NS").id)
        assertEquals("America/New_York", BarHistory.zoneOf("AAPL").id)
    }

    @Test fun theKeptCopyReadsBackExactly() {
        val f = tmp.newFile("SAP.DE_730d_60m.bin")
        val k = BarHistory.Kept("2026-11-02", "SAP SE", bars(0, 50) { 100.0 + it * 0.37 })
        BarHistory.save(f, k)
        assertEquals(k, BarHistory.load(f))
    }

    @Test fun withNoStorageEveryRequestIsAWholeDownload() {
        var whole = 0; var recent = 0
        val r = BarHistory.fetch("X|730d|60m", "X", "730d", 0L,
            full = { whole++; YahooFinanceClient.CandleResult.Success(bars(0, 60)) },
            recent = { recent++; YahooFinanceClient.CandleResult.NoData })
        assertTrue(r is YahooFinanceClient.CandleResult.Success)
        assertEquals(1, whole); assertEquals(0, recent)
    }
}

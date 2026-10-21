package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * NSE bars must group on the exchange's clock, as the desktop lab groups them. An epoch grid
 * splits the 09:15–15:30 session into 05:30/09:30/13:30 IST blocks, leaving the 09:15 hour alone
 * in the first one.
 */
class BarGridTest {
    private val ist = ZoneOffset.ofHoursMinutes(5, 30)
    private fun hour(h: Int, m: Int, close: Double) = CandleData(
        LocalDateTime.of(2023, 8, 28, h, m).toEpochSecond(ist), close, close, close, close, 1L)
    private val session = listOf(hour(9, 15, 1.0), hour(10, 15, 2.0), hour(11, 15, 3.0), hour(12, 15, 4.0),
                                 hour(13, 15, 5.0), hour(14, 15, 6.0), hour(15, 15, 7.0))
    private fun startsIst(bars: List<CandleData>) =
        bars.map { java.time.Instant.ofEpochSecond(it.timestamp).atOffset(ist).toLocalTime().toString() }

    @Test
    fun indiaFourHourBlocksMatchTheDesktop() {
        val b = StrategyLabEngine.resample(session, 14_400L, anchorSec = 19_800L)
        assertEquals(listOf("08:00", "12:00"), startsIst(b))
        assertEquals(listOf(3.0, 7.0), b.map { it.close })   // 11:15 close, then the 15:15 close
    }

    @Test
    fun epochGridIsUnchangedWhenNotAnchored() {
        val b = StrategyLabEngine.resample(session, 14_400L)
        assertEquals(listOf("05:30", "09:30", "13:30"), startsIst(b))
    }

    @Test
    fun dailyBarIsTheWholeSessionEitherWay() {
        for (a in listOf(0L, 19_800L)) {
            val b = StrategyLabEngine.resample(session, 86_400L, anchorSec = a)
            assertEquals(1, b.size); assertEquals(7.0, b[0].close, 0.0)
        }
    }
}

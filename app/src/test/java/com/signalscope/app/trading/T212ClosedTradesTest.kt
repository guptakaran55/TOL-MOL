package com.signalscope.app.trading

import com.signalscope.app.network.Trading212Client
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matching rule is the whole feature, so it is tested against sequences whose
 * answer is known by construction rather than against an account.
 *
 * The case that matters most is the partial close: buy 100, sell 99, and the
 * profit is on 99 shares. Getting that wrong reports money that has not been made
 * — which is the specific failure this page exists to avoid.
 */
class T212ClosedTradesTest {

    private var seq = 0
    private fun fill(ticker: String, side: String, qty: Double, price: Double,
                     day: Int, cash: Double = -1.0) = Trading212Client.HistoricalFill(
        orderId = (++seq).toLong(), ticker = ticker, filledQuantity = qty, fillPrice = price,
        filledValue = if (cash >= 0.0) cash else qty * price, side = side,
        dateExecuted = "2026-09-%02dT10:00:00".format(day), status = "FILLED")

    @Test fun `a clean round trip reports its own profit`() {
        val (trips, r) = T212ClosedTrades.match(listOf(
            fill("SAPd_EQ", "BUY", 10.0, 100.0, 1),
            fill("SAPd_EQ", "SELL", 10.0, 110.0, 5)))
        assertEquals(1, trips.size)
        assertEquals(10.0, trips[0].qty, 1e-9)
        assertEquals(100.0, trips[0].pnl, 1e-9)
        assertEquals(10.0, trips[0].pctReturn, 1e-9)
        assertEquals(4, trips[0].heldDays)
        assertEquals(100.0, r.totalPnl, 1e-9)
        assertEquals(1, r.wins)
    }

    @Test fun `selling 99 of 100 counts profit on 99 only`() {
        // THE RULE THE USER ASKED FOR. The hundredth share is still owned and its
        // gain has not been made; counting it would report money that does not exist.
        val (trips, r) = T212ClosedTrades.match(listOf(
            fill("SAPd_EQ", "BUY", 100.0, 10.0, 1),
            fill("SAPd_EQ", "SELL", 99.0, 12.0, 9)))
        assertEquals(1, trips.size)
        assertEquals(99.0, trips[0].qty, 1e-9)
        assertEquals("profit is 2.00 on 99 shares, not on 100", 198.0, r.totalPnl, 1e-9)
        assertEquals("the last share is still held", 1.0, r.stillOpen["SAPd_EQ"]!!, 1e-9)
    }

    @Test fun `oldest shares are sold first`() {
        // Two entries at different prices, one sell covering only the first. FIFO
        // must close the cheap one — averaging them would hide that the early
        // entry worked and report a smaller, wrong profit.
        val (trips, _) = T212ClosedTrades.match(listOf(
            fill("BMWd_EQ", "BUY", 5.0, 50.0, 1),
            fill("BMWd_EQ", "BUY", 5.0, 70.0, 2),
            fill("BMWd_EQ", "SELL", 5.0, 80.0, 3)))
        assertEquals(1, trips.size)
        assertEquals("the 50.00 lot is the one closed", 50.0, trips[0].buyPrice, 1e-9)
        assertEquals(150.0, trips[0].pnl, 1e-9)
    }

    @Test fun `one sell spanning two buys becomes two round trips`() {
        val (trips, r) = T212ClosedTrades.match(listOf(
            fill("BMWd_EQ", "BUY", 4.0, 50.0, 1),
            fill("BMWd_EQ", "BUY", 6.0, 60.0, 2),
            fill("BMWd_EQ", "SELL", 10.0, 70.0, 3)))
        assertEquals("each entry is closed on its own terms", 2, trips.size)
        assertEquals(4.0 * 20.0 + 6.0 * 10.0, r.totalPnl, 1e-9)
        assertTrue("nothing left open", r.stillOpen.isEmpty())
    }

    @Test fun `a sell with no buy in the window is reported, never invented`() {
        // The purchase predates the fetched history. There is no honest entry
        // price, so it must not become a trade — a fabricated buy would show a
        // vast profit against a price of zero.
        val (trips, r) = T212ClosedTrades.match(listOf(
            fill("OLDd_EQ", "SELL", 7.0, 30.0, 4)))
        assertTrue("no trade may be invented", trips.isEmpty())
        assertEquals(7.0, r.unmatchedSells["OLDd_EQ"]!!, 1e-9)
        assertEquals(0.0, r.totalPnl, 1e-9)
    }

    @Test fun `profit follows the cash, not the per-share price`() {
        // A US name on a euro account: the price is in dollars, the cash in euros.
        // Using price × quantity would report the dollar figure as euros — the
        // same currency confusion that once put a stop 17% from where it belonged.
        val (trips, _) = T212ClosedTrades.match(listOf(
            fill("AAPLd_EQ", "BUY", 10.0, 100.0, 1, cash = 860.0),
            fill("AAPLd_EQ", "SELL", 10.0, 110.0, 5, cash = 946.0)))
        assertEquals(1, trips.size)
        assertEquals("profit is the cash that moved: 946 - 860", 86.0, trips[0].pnl, 1e-9)
        assertEquals("prices stay in the instrument's own currency", 100.0, trips[0].buyPrice, 1e-9)
    }

    @Test fun `two names never borrow each other's shares`() {
        val (trips, r) = T212ClosedTrades.match(listOf(
            fill("AAAd_EQ", "BUY", 5.0, 10.0, 1),
            fill("BBBd_EQ", "SELL", 5.0, 20.0, 2)))
        assertTrue("a sell of BBB cannot close a buy of AAA", trips.isEmpty())
        assertEquals(5.0, r.stillOpen["AAAd_EQ"]!!, 1e-9)
        assertEquals(5.0, r.unmatchedSells["BBBd_EQ"]!!, 1e-9)
    }

    @Test fun `losses are counted as losses`() {
        val (_, r) = T212ClosedTrades.match(listOf(
            fill("Xd_EQ", "BUY", 10.0, 100.0, 1),
            fill("Xd_EQ", "SELL", 10.0, 90.0, 2)))
        assertEquals(-100.0, r.totalPnl, 1e-9)
        assertEquals(0, r.wins)
        assertEquals(1, r.losses)
    }

    @Test fun `history order does not change the answer`() {
        // Fills arrive newest-first from the broker; the matcher sorts them. If it
        // did not, a sell would be seen before its buy and every trip would vanish.
        val f = listOf(
            fill("SAPd_EQ", "SELL", 10.0, 110.0, 5),
            fill("SAPd_EQ", "BUY", 10.0, 100.0, 1))
        val (trips, r) = T212ClosedTrades.match(f)
        assertEquals(1, trips.size)
        assertEquals(100.0, r.totalPnl, 1e-9)
    }

    // ── THE 203.66 BUG ──────────────────────────────────────────────────────
    // A lot eaten by several sells had its cost re-derived each time by dividing
    // the lot's TOTAL cash by whatever quantity was LEFT. The divisor shrank with
    // every bite, so the last sliver of a position appeared to have cost the whole
    // position's money: 206.00 over 0.0952 remaining shares = 2163.87 a share, and
    // an 11-cent loss was displayed as -203.66 on a P&L screen.

    @Test fun `a lot eaten by several sells keeps its original cost per share`() {
        val f = listOf(
            fill("GFTd_EQ", "BUY",  8.0,     25.75, 1),
            fill("GFTd_EQ", "SELL", 7.9048,  24.45, 5),
            fill("GFTd_EQ", "SELL", 0.0952,  24.60, 5))
        val (trips, r) = T212ClosedTrades.match(f)
        assertEquals(2, trips.size)
        val sliver = trips.first { it.qty < 0.5 }
        assertEquals("the sliver lost 11 cents, not 203 euros",
            0.0952 * (24.60 - 25.75), sliver.pnl, 1e-6)
        assertTrue("no round trip may lose more than the position cost",
            trips.all { kotlin.math.abs(it.pnl) < it.qty * 25.75 })
        assertEquals("the whole position lost about ten euros",
            7.9048 * (24.45 - 25.75) + 0.0952 * (24.60 - 25.75), r.totalPnl, 1e-6)
    }

    @Test fun `a percentage can never exceed what was put in`() {
        // -98.86% on a trade that moved 4% is the shape of the same fault. A round
        // trip cannot lose almost everything unless the price almost went to zero.
        val (trips, _) = T212ClosedTrades.match(listOf(
            fill("GFTd_EQ", "BUY",  8.0,    25.75, 1),
            fill("GFTd_EQ", "SELL", 7.9048, 24.45, 5),
            fill("GFTd_EQ", "SELL", 0.0952, 24.60, 5)))
        trips.forEach {
            val priceMove = kotlin.math.abs(it.sellPrice / it.buyPrice - 1.0) * 100.0
            assertTrue("a ${"%.2f".format(priceMove)}% price move cannot be a " +
                "${"%.2f".format(it.pctReturn)}% return",
                kotlin.math.abs(it.pctReturn) < priceMove + 1.0)
        }
    }

    @Test fun `nonsense cash is refused in favour of the price`() {
        // The guard on its own. A per-share cash wildly out of step with the price
        // is a pairing mistake somewhere upstream, and the price is the one number
        // that cannot disagree with itself.
        assertEquals("an 84x ratio is not a currency", 25.75,
            T212ClosedTrades.cashPerShare(cash = 206.0, qty = 0.0952, price = 25.75), 1e-9)
        assertEquals("a real currency rate is kept", 86.0,
            T212ClosedTrades.cashPerShare(cash = 860.0, qty = 10.0, price = 100.0), 1e-9)
        assertEquals("pence quoted, pounds settled, is kept", 0.2575,
            T212ClosedTrades.cashPerShare(cash = 2.575, qty = 10.0, price = 25.75), 1e-9)
        assertEquals("no cash at all falls back to the price", 25.75,
            T212ClosedTrades.cashPerShare(cash = 0.0, qty = 8.0, price = 25.75), 1e-9)
    }

    @Test fun `each trade lands on its own market's tab`() {
        // One Trading 212 account, shown as a US tab and a German tab. Xetra lines
        // carry a lowercase 'd' on the base; reading it after upper-casing would
        // send every German trade to the US.
        assertEquals("DE", T212ClosedTrades.spaceOf("SAPd_EQ"))
        assertEquals("DE", T212ClosedTrades.spaceOf("MUV2d_EQ"))
        assertEquals("US", T212ClosedTrades.spaceOf("AAPL_US_EQ"))
        assertEquals("US", T212ClosedTrades.spaceOf("AMD_US_EQ"))
    }

    @Test fun `splitting by market changes no trade, only which total it is in`() {
        val fills = listOf(
            fill("SAPd_EQ", "BUY", 10.0, 100.0, 1),
            fill("AAPL_US_EQ", "BUY", 5.0, 200.0, 2),
            fill("SAPd_EQ", "SELL", 10.0, 110.0, 3),
            fill("AAPL_US_EQ", "SELL", 5.0, 190.0, 4))
        val (_, all) = T212ClosedTrades.match(fills)
        val (_, de) = T212ClosedTrades.match(fills.filter { T212ClosedTrades.spaceOf(it.ticker) == "DE" })
        val (_, us) = T212ClosedTrades.match(fills.filter { T212ClosedTrades.spaceOf(it.ticker) == "US" })
        assertEquals(listOf("SAPd_EQ"), de.trips.map { it.ticker })
        assertEquals(listOf("AAPL_US_EQ"), us.trips.map { it.ticker })
        assertEquals(100.0, de.totalPnl, 1e-9)
        assertEquals(-50.0, us.totalPnl, 1e-9)
        assertEquals("the two tabs add up to the account", all.totalPnl, de.totalPnl + us.totalPnl, 1e-9)
    }
}

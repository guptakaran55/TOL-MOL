package com.signalscope.app.trading

import com.signalscope.app.data.FloorAlgoStore
import com.signalscope.app.data.FloorAlgoStore.Ask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The AI-outlook low-buy algos ask before every paper buy: Buy, Skip, no answer, a late tap. */
class FloorAsksTest {

    private val today = LocalDate.of(2026, 11, 2)
    private val askedMs = 1_793_610_000_000L                 // a Monday, in the session
    private val signalSec = askedMs / 1000 - 120

    private fun turn(id: String = "SAP|2026-11-02") = Ask(id = id, symbol = id.substringBefore("|"), kind = FloorAsks.TURN,
        verdict = "BUY", day = today.toString(), signalSec = signalSec, fromSec = ChainRules.ceil15(signalSec),
        askedMs = askedMs)
    private fun macd(seats: Int = 5, vararg asks: Ask) = FloorAlgoStore.Algo(id = "m", kind = FloorAlgoStore.KIND_MACD,
        settings = FloorAlgoStore.Settings(seats = seats, universeMode = FloorAlgoStore.UNI_MONTHLY_LOW), asks = asks.toList())

    @Test fun buyFillsFromTheTapNotFromTheSignal() {
        val tap = askedMs + 7 * 60_000L
        val (a, _) = FloorAsks.resolve(macd(5, turn()), "SAP|2026-11-02", FloorAsks.BUY, tap, today)
        val t = a.trades.single()
        assertEquals("OPEN", t.status)
        assertEquals(tap / 1000, t.signalSec)
        assertEquals(ChainRules.ceil15(tap / 1000), t.fromSec)   // the first 15-minute bar at or after the tap
        assertNull(t.followWhy)
        assertEquals(FloorAsks.BUY, a.asks.single().status)
    }

    @Test fun skipOrNoAnswerFollowsItOnTheSideFromTheSignal() {
        for ((answer, why) in listOf(FloorAsks.SKIP to "skipped by you", FloorAsks.NO_ANSWER to "no answer",
                FloorAsks.LATE to "answered after the close")) {
            val (a, _) = FloorAsks.resolve(macd(5, turn()), "SAP|2026-11-02", answer, askedMs + 60_000L, today)
            val t = a.trades.single()
            assertEquals("MISSED", t.status)
            assertEquals(why, t.followWhy)
            assertEquals(signalSec, t.signalSec)
            assertEquals(ChainRules.ceil15(signalSec), t.fromSec)
        }
    }

    @Test fun aTapCountsAsLateOrUnansweredWhenItShould() {
        val k = turn()
        assertEquals(FloorAsks.BUY, FloorAsks.answerAt(k, true, askedMs + 29 * 60_000L, sessionOpen = true))
        assertEquals(FloorAsks.NO_ANSWER, FloorAsks.answerAt(k, true, askedMs + 31 * 60_000L, sessionOpen = true))
        assertEquals(FloorAsks.LATE, FloorAsks.answerAt(k, true, askedMs + 5 * 60_000L, sessionOpen = false))
        assertEquals(FloorAsks.SKIP, FloorAsks.answerAt(k, false, askedMs + 5 * 60_000L, sessionOpen = true))
        // Due: past the half hour, or the market shut.
        val a = macd(5, k)
        assertTrue(FloorAsks.due(a, askedMs + 10 * 60_000L, sessionOpen = true).isEmpty())
        assertEquals(1, FloorAsks.due(a, askedMs + 31 * 60_000L, sessionOpen = true).size)
        assertEquals(1, FloorAsks.due(a, askedMs + 10 * 60_000L, sessionOpen = false).size)
    }

    @Test fun aBuyWithEverySeatTakenIsFollowedNotBought() {
        val held = FloorAlgoStore.Trade(id = "BMW|2026-11-02", symbol = "BMW", day = today.toString(), firstSec = 1L, status = "OPEN")
        val a0 = macd(1, turn()).copy(trades = listOf(held))
        val (a, _) = FloorAsks.resolve(a0, "SAP|2026-11-02", FloorAsks.BUY, askedMs + 60_000L, today)
        assertEquals("every seat taken", a.trades.first { it.symbol == "SAP" }.followWhy)
    }

    @Test fun anAnsweredRequestIsNotAnsweredTwice() {
        val (once, _) = FloorAsks.resolve(macd(5, turn()), "SAP|2026-11-02", FloorAsks.SKIP, askedMs + 60_000L, today)
        val (twice, msg) = FloorAsks.resolve(once, "SAP|2026-11-02", FloorAsks.BUY, askedMs + 120_000L, today)
        assertEquals(once, twice)
        assertEquals("", msg)
    }

    @Test fun aChainKeepsItsSeatOnlyWhileAHalfIsToComeOrWasBought() {
        val chain = FloorAlgoStore.Chain(id = "LPK|2026-10-19", symbol = "LPK", state = "HOLDING", counted = true,
            dots = "ASK", champ = "")
        val ask = Ask(id = "LPK|2026-10-19|A", symbol = "LPK", kind = FloorAsks.CHAIN_A, chainId = chain.id,
            day = today.toString(), signalSec = signalSec, fromSec = ChainRules.ceil15(signalSec), askedMs = askedMs)
        val a0 = FloorAlgoStore.Algo(id = "c", kind = FloorAlgoStore.KIND_CHAIN, chains = listOf(chain), asks = listOf(ask))
        // Skipped, the champion half still to come: the seat stays.
        val (a1, _) = FloorAsks.resolve(a0, ask.id, FloorAsks.SKIP, askedMs + 60_000L, today)
        val c1 = a1.chains.single()
        assertEquals("SKIP", c1.dots)
        assertTrue(c1.counted)
        // The champion half skipped too, nothing bought: the seat is freed.
        val askB = ask.copy(id = "LPK|2026-10-19|B", kind = FloorAsks.CHAIN_B)
        val (a2, _) = FloorAsks.resolve(FloorAsks.raise(a1.copy(chains = listOf(c1.copy(champ = "ASK"))), askB),
            askB.id, FloorAsks.NO_ANSWER, askedMs + 120_000L, today)
        assertFalse(a2.chains.single().counted)
        // Bought on a tap: the half is OPEN and the seat held.
        val (a3, _) = FloorAsks.resolve(a0, ask.id, FloorAsks.BUY, askedMs + 60_000L, today)
        assertEquals("OPEN", a3.chains.single().dots)
        assertEquals("OPEN", a3.trades.single().status)
    }

    @Test fun onlyTheAiOutlookLowBuyAlgosAsk() {
        assertTrue(FloorAsks.asks(macd()))
        assertTrue(FloorAsks.asks(FloorAlgoStore.Algo(kind = FloorAlgoStore.KIND_CHAIN)))
        assertFalse(FloorAsks.asks(FloorAlgoStore.Algo(kind = FloorAlgoStore.KIND_MACD)))   // the plain MACD swing
        assertFalse(FloorAsks.asks(FloorAlgoStore.Algo(kind = FloorAlgoStore.KIND_FLOOR)))
    }
}

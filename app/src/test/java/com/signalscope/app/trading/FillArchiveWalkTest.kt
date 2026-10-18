package com.signalscope.app.trading

import com.signalscope.app.network.Trading212Client
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * READING TRADING 212'S ORDER HISTORY INTO THE SAVED ARCHIVE. The history comes
 * newest first, fifty orders a page, cancelled ones included — and the old read
 * stopped at the first page with no executed order in it, which an evening of
 * unfilled ladder rungs produces. Each case here is a way the record used to be
 * lost.
 */
class FillArchiveWalkTest {

    private fun fill(id: Long, at: String, side: String = "BUY") =
        Trading212Client.HistoricalFill(id, "SAPd_EQ", 1.0, 100.0, 100.0, side, at, "FILLED")

    private fun page(fills: List<Trading212Client.HistoricalFill>, items: Int = 50, next: String? = null) =
        Trading212Client.HistoryPage(200, fills, items, next)

    private val limited = Trading212Client.HistoryPage(429, emptyList(), 0, null)

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    /** Serves pages by cursor (null = the newest page) and counts the requests. */
    private class Broker(val pages: Map<String?, Trading212Client.HistoryPage>) {
        var asked = 0
        fun fetch(cursor: String?): Trading212Client.HistoryPage { asked++; return pages.getValue(cursor) }
    }

    @Test fun `pages of cancelled orders do not end the read`() {
        val a = fill(1, "2026-10-14T08:00:00Z")
        val b = fill(2, "2026-06-02T09:00:00Z")
        val broker = Broker(mapOf(
            null to page(listOf(a), next = "c2"),
            "c2" to page(emptyList(), next = "c3"),      // fifty cancelled rungs
            "c3" to page(emptyList(), next = "c4"),      // fifty more
            "c4" to page(listOf(b), next = "c5"),
            "c5" to page(emptyList(), items = 0)))
        val (arc, note) = T212ClosedTrades.walk(T212ClosedTrades.Archive(), broker::fetch,
            ms("2026-10-14T10:00:00Z")) {}
        assertEquals(listOf(2L, 1L), arc.fills.map { it.orderId })   // oldest first
        assertTrue(arc.complete)
        assertNull(arc.resumeCursor)
        assertEquals("", note)
    }

    @Test fun `a rate-limited read keeps what it got and the next one carries on`() {
        val a = fill(1, "2026-10-14T08:00:00Z")
        val b = fill(2, "2026-06-02T09:00:00Z")
        val first = Broker(mapOf(null to page(listOf(a), next = "c2"), "c2" to limited))
        val saved = ArrayList<T212ClosedTrades.Archive>()
        val (half, note) = T212ClosedTrades.walk(T212ClosedTrades.Archive(), first::fetch,
            ms("2026-10-14T10:00:00Z")) { saved += it }
        assertEquals(listOf(1L), half.fills.map { it.orderId })
        assertFalse(half.complete)
        assertEquals("c2", half.resumeCursor)
        assertTrue(note.contains("too many requests"))
        assertEquals(half, saved.last())                              // already on disk

        // Later: a new order on top. The newest page joins up with what is saved,
        // then the read resumes where the first one was cut off.
        val n = fill(3, "2026-10-14T12:00:00Z", side = "SELL")
        val second = Broker(mapOf(
            null to page(listOf(n, a), next = "x2"),
            "c2" to page(listOf(b), next = "c3"),
            "c3" to page(emptyList(), items = 0)))
        val (full, note2) = T212ClosedTrades.walk(half, second::fetch, ms("2026-10-14T13:00:00Z")) {}
        assertEquals(listOf(2L, 1L, 3L), full.fills.map { it.orderId })
        assertTrue(full.complete)
        assertEquals("", note2)
        assertEquals(3, second.asked)    // never walked the newest pages past "a"
    }

    @Test fun `a caught-up archive costs one page`() {
        val a = fill(1, "2026-10-14T08:00:00Z")
        val saved = T212ClosedTrades.Archive(listOf(a), complete = true, syncedAt = ms("2026-10-14T10:00:00Z"))
        val n = fill(3, "2026-10-14T12:00:00Z", side = "SELL")
        val broker = Broker(mapOf(null to page(listOf(n, a), next = "x2")))
        val (arc, _) = T212ClosedTrades.walk(saved, broker::fetch, ms("2026-10-14T13:00:00Z")) {}
        assertEquals(1, broker.asked)
        assertEquals(listOf(1L, 3L), arc.fills.map { it.orderId })
        assertEquals(ms("2026-10-14T13:00:00Z"), arc.syncedAt)
    }

    @Test fun `new orders behind a wall of cancelled ones are still reached`() {
        val a = fill(1, "2026-10-13T08:00:00Z")
        val saved = T212ClosedTrades.Archive(listOf(a), complete = true, syncedAt = ms("2026-10-13T10:00:00Z"))
        val n = fill(3, "2026-10-14T12:00:00Z", side = "SELL")
        val broker = Broker(mapOf(
            null to page(emptyList(), next = "x2"),        // tonight's cancelled rungs
            "x2" to page(listOf(n), next = "x3"),
            "x3" to page(listOf(a), next = "x4")))
        val (arc, _) = T212ClosedTrades.walk(saved, broker::fetch, ms("2026-10-14T13:00:00Z")) {}
        assertEquals(listOf(1L, 3L), arc.fills.map { it.orderId })
        assertEquals(3, broker.asked)
    }

    @Test fun `a read that fails at once loses nothing`() {
        val a = fill(1, "2026-10-13T08:00:00Z")
        val saved = T212ClosedTrades.Archive(listOf(a), complete = true, syncedAt = ms("2026-10-13T10:00:00Z"))
        val (arc, note) = T212ClosedTrades.walk(saved, { limited }, ms("2026-10-14T13:00:00Z")) {}
        assertEquals(listOf(1L), arc.fills.map { it.orderId })
        assertEquals(ms("2026-10-13T10:00:00Z"), arc.syncedAt)       // not marked caught up
        assertTrue(note.isNotBlank())
    }

    @Test fun `a long history stops at the page limit and resumes`() {
        val pages = HashMap<String?, Trading212Client.HistoryPage>()
        pages[null] = page(listOf(fill(100, "2026-10-14T08:00:00Z")), next = "p1")
        for (i in 1..9) pages["p$i"] = page(listOf(fill(100L - i, "2026-10-${"%02d".format(14 - i)}T08:00:00Z")), next = "p${i + 1}")
        pages["p10"] = page(emptyList(), items = 0)
        val broker = Broker(pages)
        val (part, note) = T212ClosedTrades.walk(T212ClosedTrades.Archive(), broker::fetch,
            ms("2026-10-14T10:00:00Z"), maxPages = 4) {}
        assertEquals(4, part.fills.size)
        assertFalse(part.complete)
        assertTrue(note.isNotBlank())
        val (rest, note2) = T212ClosedTrades.walk(part, broker::fetch, ms("2026-10-14T11:00:00Z")) {}
        assertEquals(10, rest.fills.size)
        assertTrue(rest.complete)
        assertEquals("", note2)
    }
}

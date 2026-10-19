package com.signalscope.app.network

import com.signalscope.app.network.NewsDigest.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * THE HEADLINES THE AI READS — checked on Google News feeds as they came on
 * 16 Oct 2026, in Google's own (relevance) order.
 */
class NewsDigestTest {

    private val now = Instant.parse("2026-10-16T09:00:00Z").toEpochMilli()
    private val today = LocalDate.parse("2026-10-16")

    private fun item(at: String, titleAndOutlet: String): Item {
        val outlet = titleAndOutlet.substringAfterLast(" - ")
        return Item(titleAndOutlet, outlet, Instant.parse(at).toEpochMilli(), "https://news.google.com/rss/articles/x")
    }

    private val nvidia = listOf(
        item("2026-09-28T07:00:00Z", "NVIDIA Announces a \$150 Billion Share Repurchase Authorization Increase - NVIDIA Newsroom"),
        item("2026-09-29T07:00:00Z", "Nvidia's record buyback shows chipmaker's stock is too cheap for CEO Huang to resist - CNBC"),
        item("2026-09-28T07:00:00Z", "Nvidia announces jaw-dropping \$150 billion stock buyback, largest single authorization in history - Yahoo Finance"),
        item("2026-09-28T07:00:00Z", "Nvidia Adds Record \$150 Billion to Stock Buyback - WSJ"),
        item("2026-09-28T07:00:00Z", "Nvidia Adds \$150 Billion to Massive Stock Buyback, the Largest Ever - The New York Times"),
        item("2026-10-08T15:18:12Z", "Trump discloses Nvidia stock trades as he prepares to honor CEO Huang - Reuters"),
        item("2026-09-28T07:00:00Z", "Nvidia Boosts Share Buyback by a Record \$150 Billion - Bloomberg"),
        item("2026-09-28T07:00:00Z", "Nvidia share buyback plan gets \$150 billion boost - CNBC"),
        item("2026-09-28T07:00:00Z", "What Nvidia's \$150 billion stock buyback means for shareholders and potential investors - Yahoo Finance"),
        item("2026-09-28T07:00:00Z", "Nvidia sets biggest-ever buyback plan as AI chip competition weighs on stock performance - Reuters"),
        item("2026-10-15T19:31:20Z", "Nvidia leads chip stocks lower as AI trade takes - Yahoo Finance"),
        item("2026-10-15T13:42:37Z", "Nvidia leads chip stocks lower as AI trade takes - Yahoo Finance"),
        item("2026-10-15T20:33:00Z", "Nvidia leads chip stocks lower as AI trade takes - Yahoo Finance"),
        item("2026-09-28T07:00:00Z", "'So much cash': Nvidia stock jumps after largest share buyback ever - Yahoo Finance")
    )

    @Test fun `yesterday's news comes first, not eleventh`() {
        val out = NewsDigest.digest(nvidia, 12, now, listOf("Nvidia", "NVDA"))
        assertEquals("Nvidia leads chip stocks lower as AI trade takes", out[0].title)
        assertEquals("2026-10-15 (1 day ago) · Nvidia leads chip stocks lower as AI trade takes — Yahoo Finance",
            NewsDigest.line(out[0], today, ZoneOffset.UTC))
        assertEquals("Trump discloses Nvidia stock trades as he prepares to honor CEO Huang", out[1].title)
        val dates = out.map { it.timeMs!! }
        assertEquals(dates.sortedDescending(), dates)
    }

    @Test fun `one buyback told by many outlets is one line with a count`() {
        val out = NewsDigest.digest(nvidia, 12, now, listOf("Nvidia", "NVDA"))
        // Fourteen feed items, five stories — the buyback had filled ten of twelve places.
        assertEquals(5, out.size)
        val buyback = out.single { it.title.startsWith("NVIDIA Announces") }
        assertEquals(6, buyback.outlets)    // Newsroom, Yahoo, WSJ, NYT, Bloomberg, CNBC
        assertTrue(NewsDigest.line(buyback, today, ZoneOffset.UTC).endsWith("— NVIDIA Newsroom (+5 more outlets)"))
        // The same headline three times from one outlet is one outlet.
        assertEquals(1, out[0].outlets)
    }

    @Test fun `quote pages, forecast pages, stock lists and price blurbs are dropped`() {
        val tata = listOf(
            item("2026-10-15T00:08:40Z", "Tata Motors Limited (TMCV.NS) stock price, news, quote and history - Yahoo Finance UK"),
            item("2026-10-13T02:06:40Z", "TATA MOTORS LIMITED Share Price - Live NSE: TMCV Stock Price & Chart - Upstox"),
            item("2026-10-07T07:00:00Z", "Tata Motors (NSEI:TMCV) Stock Forecast & Analyst Predictions - Simply Wall Street"),
            item("2026-10-08T07:00:00Z", "Tata Motors Passenger Vehicles Share Price Target 2026: Stock Analysis, Share Price Today, Bull Case and Bear Case After Q1 FY27 - Univest"),
            item("2026-10-01T07:00:00Z", "Tata Motors PV shares fall over 3% despite 40% Q2 sales growth; Nifty Auto index down over 4% - The Economic Times"),
            item("2026-09-21T07:00:00Z", "Tata Motors Passenger Vehicles TMPV ESOP Share Allotment 2026 - Kalkine India"),
            item("2026-09-22T07:00:00Z", "Best EV Stocks in India 2026 - Groww"),
            item("2026-10-12T11:39:53Z", "Tata Motors stock stands at INR 281.30, 33.3 percent below its high - ad-hoc-news.de"),
            item("2026-09-23T07:00:00Z", "Hero Motors Ltd Share Price Today – Live NSE/BSE - INDmoney"),
            item("2026-10-16T02:18:08Z", "Is Now The Time To Look At Buying Tata Motors Passenger Vehicles Limited (NSE:TMPV)? - Simply Wall Street"),
            item("2026-10-16T08:25:50Z", "Tata Motors Limited (TMCV.NS) Stock Price, News, Quote & History - Yahoo! Finance Canada"),
            item("2026-09-17T07:00:00Z", "Tata Motors shares rise 2% after company hikes prices of commercial vehicles; all you need to know - Upstox"),
            item("2026-10-09T07:00:00Z", "Tata Motors Share Price rises 2.47%; valuation view - Univest")
        )
        val out = NewsDigest.digest(tata, 12, now, listOf("Tata Motors", "TATAMOTORS"))
        assertEquals(listOf(
            "Is Now The Time To Look At Buying Tata Motors Passenger Vehicles Limited (NSE:TMPV)?",
            "Tata Motors Share Price rises 2.47%; valuation view",
            "Tata Motors PV shares fall over 3% despite 40% Q2 sales growth; Nifty Auto index down over 4%",
            "Tata Motors Passenger Vehicles TMPV ESOP Share Allotment 2026",
            "Tata Motors shares rise 2% after company hikes prices of commercial vehicles; all you need to know"
        ), out.map { it.title })
    }

    @Test fun `older than thirty days is gone`() {
        val out = NewsDigest.digest(listOf(
            item("2026-09-10T07:00:00Z", "Acme wins navy order - Reuters"),
            item("2026-10-10T07:00:00Z", "Acme names new finance chief - Reuters")
        ), 12, now)
        assertEquals(listOf("Acme names new finance chief"), out.map { it.title })
    }

    @Test fun `a widely covered older story keeps a place over this week's small items`() {
        val small = (1..14).map { d ->
            item(Instant.parse("2026-10-16T07:00:00Z").minusSeconds(d * 86_400L).toString(), "Story $d about topic$d word$d - Outlet$d")
        }
        val big = listOf("Reuters", "Bloomberg", "CNBC", "WSJ").map { o ->
            item("2026-09-26T07:00:00Z", "Megacorp wins giant navy order - $o")
        }
        val out = NewsDigest.digest(small + big, 12, now)
        assertEquals(12, out.size)
        assertEquals("Megacorp wins giant navy order", out.last().title)
        assertEquals(4, out.last().outlets)
        assertFalse(out.any { it.title == "Story 12 about topic12 word12" })   // the oldest small one made room
        assertTrue(out.any { it.title == "Story 11 about topic11 word11" })
    }

    // ── A LEGAL OR REGULATORY MATTER KEEPS ITS PLACE ─────────────────────────
    // Booking on 19 Oct 2026: the FTC action was eleven days old and three
    // outlets had it, under a week and a half of small items.

    // Fourteen small, unrelated items after it (no shared words, one outlet each),
    // and the FTC story told by two outlets — too few for the "widely covered"
    // places, so only the legal rule can keep it.
    private val smallWords = listOf("alpine", "breeze", "cobalt", "dune", "ember", "fjord", "garnet", "harbor",
        "indigo", "juniper", "kelp", "lagoon", "meadow", "nectar", "orchid", "pebble", "quartz", "ripple",
        "saffron", "tundra", "umber", "violet", "willow", "xenon", "yarrow", "zephyr", "amber", "basalt",
        "cedar", "delta", "echo", "flint", "glacier", "hazel", "iris", "jasper", "kestrel", "lotus", "maple", "nimbus",
        "onyx", "prairie")
    private val booking = (0 until 14).map { d ->
        val w = smallWords.subList(d * 3, d * 3 + 3).joinToString(" ")
        item("2026-10-${"%02d".format(15 - d / 2)}T0${d % 9}:00:00Z", "Booking Holdings $w - Outlet$d")
    } + listOf(
        item("2026-10-05T10:00:00Z", "Booking Holdings Faces FTC Action Over Hotel Ads: More Than \$500 Million at Stake - Yahoo Finance"),
        item("2026-10-05T12:00:00Z", "Market Chatter: Booking Holdings Could Face FTC Action Over Priceline Hotel Ads - MT Newswires"))

    @Test fun `an older regulatory story more than one outlet carried is kept, even among six`() {
        val out = NewsDigest.digest(booking, 6, now, listOf("Booking Holdings"))
        assertEquals(6, out.size)
        assertTrue(out.any { it.title.contains("FTC") })
    }

    @Test fun `and among twelve`() {
        assertTrue(NewsDigest.digest(booking, 12, now, listOf("Booking Holdings")).any { it.title.contains("FTC") })
    }

    @Test fun `a regulatory headline only one outlet ran is not forced in`() {
        val lone = booking.filterNot { it.source != "Yahoo Finance" && it.title.contains("FTC") }
        val out = NewsDigest.digest(lone, 6, now, listOf("Booking Holdings"))
        assertFalse(out.any { it.title.contains("FTC") })
    }

    @Test fun `one matter worded differently by three outlets counts as covered`() {
        // As the feed had it on 19 Oct: three outlets, three wordings, no two
        // close enough to merge — and one of them a question.
        val feed = booking.filterNot { it.title.contains("FTC") } + listOf(
            item("2026-10-08T06:00:00Z", "Booking Holdings Stock Is Down 14% in Three Months. Should You Buy Before the FTC Decides? - TIKR.com"),
            item("2026-10-08T05:00:00Z", "Booking Holdings Faces FTC Action Over Hotel Ads: More Than \$500 Million at Stake - Yahoo Finance"),
            item("2026-10-07T09:00:00Z", "Booking Holdings' Priceline.com facing FTC action for third parties' deceptive practices - Seeking Alpha"))
        val out = NewsDigest.digest(feed, 12, now, listOf("Booking Holdings"))
        val ftc = out.filter { it.title.contains("FTC") }
        assertEquals(1, ftc.size)                                  // one place for one matter
        assertTrue(ftc[0].title.contains("Faces FTC Action"))      // the statement, not the question
    }

    @Test fun `legal words are recognised`() {
        assertTrue(NewsDigest.isLegal("Booking Holdings Faces FTC Action Over Hotel Ads"))
        assertTrue(NewsDigest.isLegal("BaFin opens probe into Wirecard accounts"))
        assertTrue(NewsDigest.isLegal("Bayer settles Roundup lawsuits for \$10 billion"))
        assertFalse(NewsDigest.isLegal("TD Waterhouse buys a Booking Holdings stake"))
        assertFalse(NewsDigest.isLegal("Deutsche Bank raises its target"))
    }

    // ── ONE COMPANY NAME FOR EVERY NEWS SEARCH ───────────────────────────────

    @Test fun `legal endings come off, Holdings and Group stay`() {
        assertEquals("Booking Holdings", NewsDigest.searchName("Booking Holdings Inc."))
        assertEquals("PayPal Holdings", NewsDigest.searchName("PayPal Holdings, Inc."))
        assertEquals("SAP", NewsDigest.searchName("SAP SE"))
        assertEquals("Siemens", NewsDigest.searchName("Siemens Aktiengesellschaft"))
        assertEquals("Henkel", NewsDigest.searchName("Henkel AG & Co. KGaA"))
        assertEquals("Atlassian", NewsDigest.searchName("Atlassian Corporation"))
        assertEquals("Alphabet", NewsDigest.searchName("Alphabet Inc. Class A"))
        assertEquals("Deutsche Telekom", NewsDigest.searchName("Deutsche Telekom AG"))
        assertEquals("Fresenius", NewsDigest.searchName("Fresenius SE"))
    }

    @Test fun `the two forms the app held for Booking now search the same words`() {
        assertEquals(NewsDigest.searchName("Booking Holdings Inc."), NewsDigest.searchName("Booking Holdings"))
    }

    @Test fun `no name means no name`() {
        assertEquals(null, NewsDigest.searchName(""))
        assertEquals(null, NewsDigest.searchName(null))
    }
}

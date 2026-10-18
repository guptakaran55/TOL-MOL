package com.signalscope.app.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE OUTLOOK'S ANSWER — held to the verdicts offered and the headlines sent,
 * and never a verdict guessed out of prose.
 */
class OutlookReplyTest {

    private val four = (1..4).map {
        OutlookReply.Source("2026-10-1$it", "$it days ago", "Headline $it", "Outlet $it", "https://news.google.com/rss/articles/$it", 1)
    }

    private fun built(raw: String, owned: Boolean = false) =
        JSONObject(OutlookReply.build(raw, "NVDA", owned, four, "2026-10-16"))

    @Test fun `a clean answer keeps its fields and only real headline numbers`() {
        val o = built("""{"short_term":"Chip stocks fell [1] and the buyback [3] helps [9].","long_term":"Tentative [2-4].",
            "risks":["AI trade unwinding [1]","Valuation"],"news":"new","confidence":"medium","verdict":"watch"}""")
        assertEquals("WATCH", o.getString("verdict"))
        assertEquals("MEDIUM", o.getString("confidence"))
        assertEquals("NEW", o.getString("news"))
        assertEquals("Chip stocks fell [1] and the buyback [3] helps.", o.getString("short_term"))
        assertEquals("Tentative [2,3,4].", o.getString("long_term"))
        assertEquals("AI trade unwinding [1]", o.getJSONArray("risks").getString(0))
        assertEquals(4, o.getJSONArray("sources").length())
        assertEquals(1, o.getJSONArray("sources").getJSONObject(0).getInt("n"))
        assertFalse(o.has("raw"))
    }

    @Test fun `the verdict is held to the list for owned and not owned`() {
        // Fenced, and HOLD for a stock not owned: read as its counterpart, WATCH.
        assertEquals("WATCH", built("```json\n{\"verdict\":\"HOLD\"}\n```").getString("verdict"))
        assertEquals("REDUCE", built("""{"verdict":"AVOID"}""", owned = true).getString("verdict"))
        assertEquals("STRONG AVOID", built("""{"verdict":"strong-avoid"}""").getString("verdict"))
        assertEquals("BUY", built("""{"verdict":"Buy (moderate)"}""").getString("verdict"))
        assertEquals("", built("""{"verdict":"no idea"}""").getString("verdict"))
    }

    @Test fun `a cut-off answer has no verdict - a sell-off is not a SELL`() {
        val cut = """{"short_term":"A broad sell-off hit chips","long_term":"Investors may hold","risks":["sell-off"""
        val o = built(cut)
        assertEquals("", o.getString("verdict"))
        assertTrue(o.getString("raw").startsWith("{\"short_term\""))
        assertEquals("", OutlookReply.verdictIn(o.toString()))
        assertTrue(OutlookReply.tail(o.toString(), 20).endsWith("risks\":[\"sell-off"))
    }

    @Test fun `a prose answer counts only a verdict written as one`() {
        assertEquals("AVOID", built("SHORT TERM: a sell-off.\nVERDICT: **AVOID** — wait for results").getString("verdict"))
        assertEquals("", built("It is advisable to avoid investing at this time.").getString("verdict"))
    }

    @Test fun `an error is not an answer`() {
        assertEquals("", OutlookReply.verdictIn("⚠ AI call failed: timeout"))
    }
}

package com.signalscope.app.trading

import com.signalscope.app.trading.PositionRadar.Row
import org.junit.Assert.assertEquals
import org.junit.Test

/** The positions widget's tabs: markets and kinds to tap, since a widget cannot scroll sideways. */
class WidgetTabsTest {

    // The widget on 19 Oct 2026, 22:36 (a few of its rows).
    private val rows = listOf(
        Row("🇮🇳", "⚠ RAYMOND · no current price — its stops can't see a fall", "", "IN", "NO_PRICE"),
        Row("🇩🇪", "🔴 VBK −3.4% · stop 0.5% below (28.80)", "", "DE", "NEAR_STOP"),
        Row("🇩🇪", "🎯 QIA +0.2% · target 0.8% away (41.81)", "", "DE", "NEAR_TARGET"),
        Row("🇺🇸", "🎯 PYPL +0.3% · target 1.3% away (57.20)", "", "US", "NEAR_TARGET"),
        Row("🇺🇸", "🟢 PAYX +0.7% · at its high since buy", "", "US", "NEAR_HIGH"),
        Row("🇩🇪", "🟢 SAP +1.1% · 0.8% under its high (203.13)", "", "DE", "NEAR_HIGH"))

    private fun texts(c: List<WidgetTabs.Chip>) = c.map { it.text + if (it.selected) "*" else "" }

    @Test fun `all markets and kinds, with counts, and only the markets that hold something`() {
        val v = WidgetTabs.view(rows, "ALL", "ALL")
        assertEquals(listOf("All 6*", "🇩🇪 3", "🇺🇸 2", "🇮🇳 1"), texts(v.markets))
        assertEquals(listOf("All 6*", "🟢 2", "🎯 2", "🔴 1", "⚠ 1"), texts(v.kinds))
        assertEquals(6, v.rows.size)
    }

    @Test fun `a market narrows the rows and the kinds' counts`() {
        val v = WidgetTabs.view(rows, "DE", "ALL")
        assertEquals(listOf("VBK", "QIA", "SAP"), v.rows.map { it.text.split(' ')[1] })
        assertEquals(listOf("All 3*", "🟢 1", "🎯 1", "🔴 1"), texts(v.kinds))
        assertEquals("🇩🇪 3*", texts(v.markets)[1])
    }

    @Test fun `market and kind together`() {
        val v = WidgetTabs.view(rows, "US", "NEAR_TARGET")
        assertEquals(listOf("🎯 PYPL +0.3% · target 1.3% away (57.20)"), v.rows.map { it.text })
    }

    @Test fun `a tab that has emptied falls back to All rather than an empty widget`() {
        assertEquals("ALL", WidgetTabs.view(rows, "BN", "ALL").market)
        val v = WidgetTabs.view(rows, "US", "NEAR_STOP")
        assertEquals("ALL", v.kind)
        assertEquals(2, v.rows.size)
    }

    @Test fun `a row read back from a file saved before the tabs does not crash`() {
        // What blanked the widget on 19 Oct: the saved row has no market at all.
        val old = com.google.gson.Gson().fromJson(
            """{"flag":"🇺🇸","text":"🎯 PYPL +0.3% · target 1.3% away (57.20)","detail":""}""", Row::class.java)
        assertEquals("US", WidgetTabs.marketOf(old))
        assertEquals("NEAR_TARGET", WidgetTabs.kindOf(old))
        assertEquals(1, WidgetTabs.view(listOf(old), "US", "ALL").rows.size)
    }

    @Test fun `rows saved before the tabs existed are read from their flag and mark`() {
        val old = listOf(Row("🇺🇸", "🎯 PYPL +0.3% · target 1.3% away (57.20)", ""),
                         Row("🪙", "🟢 BTCUSDC +2.0% · at its high since buy", ""))
        assertEquals(listOf("US", "BN"), old.map { WidgetTabs.marketOf(it) })
        assertEquals(listOf("NEAR_TARGET", "NEAR_HIGH"), old.map { WidgetTabs.kindOf(it) })
    }
}

package com.signalscope.app.trading

/**
 * THE POSITIONS WIDGET'S TABS — which rows a tap on a market or a kind shows.
 *
 * Asked for as sideways scrolling (19 Oct 2026), which a home-screen widget
 * cannot do: Android allows no horizontal scrolling inside one, and a sideways
 * swipe turns the launcher's page. So the markets and the kinds of line are tabs
 * to tap instead, each with its count — the kinds replace the headline that
 * used to list those counts, so the widget gains a row of markets and loses
 * nothing in height.
 *
 * A tab with nothing in it is not shown, except the one you are on; a tab whose
 * rows have all gone falls back to All rather than showing an empty widget.
 */
object WidgetTabs {
    const val ALL = "ALL"

    data class Tab(val code: String, val label: String)

    val MARKETS = listOf(Tab(ALL, "All"), Tab("DE", "🇩🇪"), Tab("US", "🇺🇸"), Tab("IN", "🇮🇳"), Tab("BN", "🪙"))

    /** In the order the headline listed them. */
    val KINDS = listOf(Tab(ALL, "All"), Tab("NEAR_HIGH", "🟢"), Tab("SHORT_HIGH", "🔼"),
        Tab("NEAR_TARGET", "🎯"), Tab("NEAR_STOP", "🔴"), Tab("NO_PRICE", "⚠"))

    // NULL, NOT BLANK, ON A ROW SAVED BEFORE THE FIELD EXISTED. The list is read
    // back from disk by a reader that skips the defaults, so "market" arrives as
    // nothing at all — and treating it as text crashed the widget into a blank
    // panel on the first redraw after the install (19 Oct, 23:46).

    /** A row's market — read from its flag on a row saved before it was recorded. */
    fun marketOf(r: PositionRadar.Row): String = (r.market as String?).orEmpty().ifBlank {
        when ((r.flag as String?).orEmpty()) { "🇩🇪" -> "DE"; "🇺🇸" -> "US"; "🇮🇳" -> "IN"; "🪙" -> "BN"; else -> "" }
    }

    /** A row's kind — read from its leading mark on a row saved before it was recorded. */
    fun kindOf(r: PositionRadar.Row): String = (r.kind as String?).orEmpty().ifBlank {
        val t = (r.text as String?).orEmpty().trimStart()
        KINDS.drop(1).firstOrNull { t.startsWith(it.label) }?.code ?: ""
    }

    data class Chip(val code: String, val label: String, val count: Int, val selected: Boolean) {
        val text: String get() = "$label $count"
    }

    data class Shown(val markets: List<Chip>, val kinds: List<Chip>,
                     val rows: List<PositionRadar.Row>, val market: String, val kind: String)

    fun view(rows: List<PositionRadar.Row>, market: String, kind: String): Shown {
        val m = if (market == ALL || rows.any { marketOf(it) == market }) market else ALL
        val inMarket = if (m == ALL) rows else rows.filter { marketOf(it) == m }
        val k = if (kind == ALL || inMarket.any { kindOf(it) == kind }) kind else ALL
        val shown = if (k == ALL) inMarket else inMarket.filter { kindOf(it) == k }
        val markets = MARKETS.map { t ->
            Chip(t.code, t.label, if (t.code == ALL) rows.size else rows.count { marketOf(it) == t.code }, t.code == m)
        }.filter { it.code == ALL || it.count > 0 }
        val kinds = KINDS.map { t ->
            Chip(t.code, t.label, if (t.code == ALL) inMarket.size else inMarket.count { kindOf(it) == t.code }, t.code == k)
        }.filter { it.code == ALL || it.count > 0 }
        return Shown(markets, kinds, shown, m, k)
    }
}

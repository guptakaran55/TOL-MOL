package com.signalscope.app.data

// ════════════════════════════════════════════════════════════════════════════
// COMPANIES THAT CHANGED THEIR NAME, AND THE TICKER THAT CHANGED WITH IT
// ════════════════════════════════════════════════════════════════════════════
// A rename is the one corporate action this app cannot shrug off. A split or a
// dividend changes the numbers and the price feed adjusts for it; a rename
// changes the KEY, and every side of the app then holds a different one.
//
// Trading 212 does not re-issue an instrument code when a company renames — the
// code it assigned at listing is a database id and it keeps it for ever. Yahoo
// does the opposite: it follows the exchange, retires the old symbol, and serves
// the new one. So for a renamed company the broker says one thing, the price
// feed says another, and neither is wrong.
//
// What that cost, before this file existed: a holding read back from the broker
// arrived under a symbol Yahoo has never heard of. No candles, so no scan, no
// live price, no P&L, and — the part that actually matters — no exit rules,
// because the rules need bars. The position sat in the book protected by the
// hard stop alone while the panel showed blanks where the numbers should be.
//
// Both directions are needed and they are not interchangeable:
//   • broker → feed, so a holding gets priced and scanned under its live symbol
//   • feed → broker, so an order for the new symbol still reaches the instrument
//     the broker files under the old one
//
// EXTEND ONLY WITH CONFIRMED 1:1 RENAMES — the same company, continuing to
// trade, under a new ticker on the same venue. Never a merger, never a spin-off,
// never a ticker that was freed up and reissued to somebody else: that last one
// is the dangerous case, because it resolves cleanly to the WRONG company and
// nothing downstream can tell.
// ════════════════════════════════════════════════════════════════════════════
object SymbolRenames {

    /**
     * US renames. Trading 212 still lists these under the OLD internal code.
     *
     *   FB → META : Meta renamed FB→META in Jun 2022. "FB" was later reissued as
     *               a ProShares ETF, which is exactly why resolving it blind is
     *               worse than failing — it returns a real, tradeable, wrong thing.
     */
    private val US = mapOf(
        "FB" to "META"
    )

    /**
     * Xetra renames. Same story, and this is where the app was actually bitten:
     * a German account can easily hold two or three of these at once.
     *
     *   DAI → MBG : Daimler became Mercedes-Benz Group, Feb 2022.
     *   DRI → 1U1 : 1&1 Drillisch became 1&1 AG, 2021.
     *   DPW → DHL : Deutsche Post became DHL Group, Jul 2023.
     *
     * Each was checked against the live feed before being added: the left-hand
     * symbol returns "may be delisted" and the right-hand one returns the
     * company. That check is the bar for adding a fourth.
     */
    private val DE = mapOf(
        "DAI" to "MBG",
        "DRI" to "1U1",
        "DPW" to "DHL"
    )

    /**
     * A LINE THAT MOVED ON THE PRICE FEED ONLY — the same share, a new feed
     * symbol, and NOTHING else in the app may follow it.
     *
     * Not a rename in the sense of the table above. The company kept its name and
     * Trading 212 kept its code; only Yahoo started publishing the share under a
     * new symbol and froze the old one. Everything the app stores — the watchlist
     * book, the live-order rows, the resting targets keyed "SYMBOL-signalBar", the
     * strategy ledger, the peak store — carries the old name, and stays correct,
     * because the old name is still what the broker calls it. So the change is
     * made in exactly one place, the request that goes out to the feed, and the
     * table above (which also rewrites the stored book) is left alone on purpose:
     * putting a line change there would re-key an OPEN position away from every
     * record that points at it.
     *
     * The old line is not simply dropped. Yahoo moved the live prices to the new
     * symbol but left the daily history behind on the old one — the new line held
     * five daily bars on its first week — so a chart request asks BOTH and splices
     * them, the new line winning wherever both have a bar.
     *
     *   PFSE → PFS0 : Pfisterer Holding SE, Oct 2026 (Yahoo labels the old line
     *                 bearer, the new one registered; the ISIN did not change).
     *                 PFSE.DE froze at 66.60 on 7 Oct; PFS0.DE
     *                 trades, its 9 Oct close (64.51) is the old line's, and
     *                 Trading 212's own instrument list already names it PFS0.
     *
     * Same bar for adding one as for the table above: checked against the live
     * feed, the same company, the same share continuing.
     */
    private val FEED_LINE_DE = mapOf(
        "PFSE" to "PFS0"
    )

    /** The live feed symbol for a frozen one ("PFSE.DE" → "PFS0.DE"), or null
     *  when the line never moved. Xetra symbols only — that is where the table
     *  lives, and a bare US symbol of the same spelling is a different company. */
    fun feedSuccessor(feedSymbol: String): String? {
        val s = feedSymbol.trim().uppercase()
        if (!s.endsWith(".DE")) return null
        return FEED_LINE_DE[s.removeSuffix(".DE")]?.let { "$it.DE" }
    }

    private val US_REVERSE = US.entries.associate { (k, v) -> v to k }
    private val DE_REVERSE = DE.entries.associate { (k, v) -> v to k }

    private fun tableFor(venue: String) = when (venue.trim().uppercase()) {
        "US", "NASDAQ", "NYSE" -> US
        "DE", "XETRA", "GERMANY", "GER", "FRA" -> DE
        else -> emptyMap()
    }

    private fun reverseFor(venue: String) = when (venue.trim().uppercase()) {
        "US", "NASDAQ", "NYSE" -> US_REVERSE
        "DE", "XETRA", "GERMANY", "GER", "FRA" -> DE_REVERSE
        else -> emptyMap()
    }

    /** The symbol the PRICE FEED knows this company by today. Unchanged when the
     *  company never renamed, which is almost always — so this is safe to call
     *  on every symbol rather than only on the ones suspected of being stale. */
    fun current(base: String, venue: String): String {
        val k = base.trim().uppercase()
        return tableFor(venue)[k] ?: base
    }

    /** The symbol the BROKER still files this company under. The inverse of
     *  [current], for building an order out of a live symbol. */
    fun legacy(base: String, venue: String): String {
        val k = base.trim().uppercase()
        return reverseFor(venue)[k] ?: base
    }

    /** True when [base] is a retired symbol that this app should no longer be
     *  storing anywhere — used by the one-time clean-up on the manual book. */
    fun isRetired(base: String, venue: String): Boolean =
        tableFor(venue).containsKey(base.trim().uppercase())

    /** Xetra convenience wrappers — the venue this actually matters on, and the
     *  one where spelling "DE" out at every call site would bury the point. */
    fun currentDe(base: String): String = current(base, "DE")
    fun legacyDe(base: String): String = legacy(base, "DE")
    fun isRetiredDe(base: String): Boolean = isRetired(base, "DE")
}

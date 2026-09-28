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

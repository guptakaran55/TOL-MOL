package com.signalscope.app.trading

/**
 * Namespace ↔ space resolution — the single source of truth once "buckets"
 * exist. A bucket is a strategy running inside a space on its own config +
 * symbol group + capital. Its namespace is "<space>__<bucketId>" (e.g.
 * "de__value"); a plain space ("us"/"de"/"binance") has no "__" and passes
 * through unchanged.
 *
 * Every place that used to branch on the raw ns ("ns == \"binance\"",
 * "when (ns)") must go through baseOf() instead, or a bucket would route to
 * the wrong exchange's data feed / ticker resolution. The "__" separator is
 * filesystem-safe (unlike ":"), so per-bucket state files
 * ("strategy_lab_de__value.json", etc.) are created automatically by the
 * existing ns-keyed stores with no store changes.
 */
object Spaces {
    const val SEP = "__"

    /** Base data/exchange space ("us" | "de" | "binance") for any namespace. */
    fun baseOf(ns: String): String = ns.substringBefore(SEP)

    /** True if [ns] names a bucket rather than a bare space. */
    fun isBucket(ns: String): Boolean = ns.contains(SEP)

    /** Bucket id portion, or "" for a bare space. */
    fun bucketIdOf(ns: String): String = if (isBucket(ns)) ns.substringAfter(SEP) else ""

    /** Compose a bucket namespace from a base space and a bucket id. */
    fun bucketNs(space: String, bucketId: String): String = "$space$SEP$bucketId"

    /** UI space code ("US"/"DE"/"IN"/"BN") for a namespace's base space. */
    fun uiSpaceOf(ns: String): String = when (baseOf(ns)) {
        "binance" -> "BN"; "de" -> "DE"; "in" -> "IN"; else -> "US"
    }

    /** Exchange tag used by the fetchers/enroll ("BINANCE"/"DE"/"NSE"/"NASDAQ"). */
    fun exchangeOf(ns: String): String = when (baseOf(ns)) {
        "binance" -> "BINANCE"; "de" -> "DE"; "in" -> "NSE"; else -> "NASDAQ"
    }

    /** Screener/data space code ("BN"/"DE"/"IN"/"US"). */
    fun dataSpaceOf(ns: String): String = when (baseOf(ns)) {
        "binance" -> "BN"; "de" -> "DE"; "in" -> "IN"; else -> "US"
    }

    /**
     * The currency a space's money is counted in. Needed by the flat per-trade
     * fee, which is a CASH amount and therefore meaningless without one — a fee
     * of "5" is trivial in rupees and material in euros.
     */
    fun currencyOf(ns: String): String = when (baseOf(ns)) {
        "binance" -> "USDC"; "de" -> "EUR"; "in" -> "INR"; else -> "USD"
    }

    /**
     * Does this space's BROKER convert currency on every trade?
     *
     * Only Trading 212 does, and only because one account settles in one currency
     * while its instruments do not: a euro account buying a Nasdaq share converts
     * in on the buy and out on the sell, and is charged for each.
     *
     * Zerodha and Binance do not. Those accounts are funded in their own currency
     * and hold it — whatever conversion happened did so ONCE, when money was moved
     * in, and is not a cost any individual trade carries.
     *
     * THIS EXISTS BECAUSE THE DISTINCTION WAS MISSED. The account currency and the
     * conversion rate are both documented as Trading 212 settings, and both are
     * single global values — but the cost resolver applied them to any space whose
     * instrument currency differed from the account's. A euro account therefore
     * paid a 0.30% round-trip conversion on every INDIAN trade, on a rupee account
     * funded from a rupee bank that converts nothing. On a config turning over
     * hundreds of trades that was three quarters of the modelled Indian trading
     * cost, invisible inside a single blended percentage.
     */
    fun brokerConvertsPerTrade(ns: String): Boolean = when (baseOf(ns)) {
        "us", "de" -> true          // Trading 212
        else -> false               // Zerodha (INR), Binance (own settlement)
    }
}

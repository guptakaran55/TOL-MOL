package com.signalscope.app.trading

// ════════════════════════════════════════════════════════════════════════════
// WHAT A ROUND TRIP ACTUALLY COSTS, PER BROKER
// ════════════════════════════════════════════════════════════════════════════
// One place, one model, three brokers that charge in genuinely different shapes:
//
//   GERMANY  (Trading 212, EUR account buying EUR instruments)
//       Nothing. No commission, no conversion — the account currency and the
//       instrument currency are the same. Spread is the only friction, and that
//       is the config's slippage, not a broker charge.
//
//   US       (Trading 212, EUR account buying USD instruments)
//       No commission either, but every execution converts, and Trading 212
//       charges 0.15% on each conversion. A round trip converts twice.
//
//   INDIA    (Zerodha, equity delivery)
//       Brokerage is free and almost everything else is not. Seven statutory
//       charges, some on both legs, some on one, one of them a flat cash amount
//       and one of them a tax levied on the others. This is why a single typed-in
//       "fee per order" could never have been right: it has no way to express a
//       charge that lands once, or one that scales with turnover, or GST.
//
// WHY A CASH PART AND A PERCENTAGE PART. Zerodha's depository charge is a fixed
// rupee amount per company per day, so its weight depends entirely on position
// size — punishing on a small seat, negligible on a large one. Folding it into a
// percentage at this level, where the seat size is known, is the only way it can
// be honest. Everything else scales with turnover and stays a percentage.
//
// PURE ARITHMETIC, NO I/O, NO ANDROID. The rates come in as arguments so the
// whole model can be checked against known numbers.
// ════════════════════════════════════════════════════════════════════════════

object TradingCosts {

    /**
     * One named charge. [pct] is a percentage of turnover already summed across
     * whichever legs it applies to; [cash] is an absolute amount in the
     * instrument's currency, likewise already summed per round trip.
     *
     * NAMED, because the whole reason a phantom currency charge survived inside
     * the Indian cost for so long is that the card showed one blended number and
     * no way to take it apart.
     */
    data class Component(val label: String, val pct: Double = 0.0, val cash: Double = 0.0)

    data class Breakdown(
        val components: List<Component>,
        /** Turnover-scaled charges, summed, per round trip. */
        val pctRoundTrip: Double,
        /** Fixed-cash charges, summed, per round trip, in the instrument currency. */
        val cashRoundTrip: Double
    ) {
        /**
         * The whole thing as one percentage, which is the only shape the engines
         * can charge. [positionValue] is what ONE SEAT actually buys — not the
         * algo's total capital, because a flat charge measured against money
         * spread over five seats understates itself fivefold.
         */
        fun totalPct(positionValue: Double): Double =
            pctRoundTrip + (if (positionValue > 0.0) cashRoundTrip / positionValue * 100.0 else 0.0)
    }

    // ── Trading 212 ─────────────────────────────────────────────────────────

    /** Their published FX fee, charged on the value of EACH execution that has to
     *  convert. Two executions per round trip. */
    const val T212_FX_PCT_PER_SIDE = 0.15

    // ── Zerodha, equity delivery ────────────────────────────────────────────
    //
    // Rates as published on Zerodha's charges page. Every one of them is a
    // constant here rather than a literal at the point of use, so a rate change
    // is one edit and the test that pins it fails loudly if the sum drifts.

    /** Delivery brokerage. Free, and stated explicitly rather than omitted — a
     *  missing line reads as "forgotten", a zero reads as "checked". */
    const val ZERODHA_BROKERAGE_PCT_PER_SIDE = 0.0

    /**
     * Securities Transaction Tax, equity delivery: 0.1% on BUY **and** SELL.
     *
     * DELIBERATELY BOTH SIDES, AND WORTH READING BEFORE CHANGING. The widely
     * quoted "STT is charged only on the sell side" is the INTRADAY rule, where
     * it is 0.025% on the sell leg. For delivery — which is what this app trades,
     * since it holds overnight — Zerodha's own charges page states 0.1% on buy
     * and sell. Getting this wrong is a 0.1% error on every round trip, which on
     * a strategy turning over hundreds of trades is the difference between a
     * champion and a loser.
     */
    const val ZERODHA_STT_PCT_PER_SIDE = 0.1

    /** NSE exchange transaction charge, per side, on turnover. */
    const val ZERODHA_EXCHANGE_PCT_PER_SIDE = 0.00335

    /** SEBI turnover fee: ₹10 per crore = 0.0001%, per side. */
    const val ZERODHA_SEBI_PCT_PER_SIDE = 0.0001

    /** Stamp duty, BUY SIDE ONLY, on turnover. */
    const val ZERODHA_STAMP_PCT_BUY_ONLY = 0.015

    /**
     * Depository charge when shares leave the demat account: a flat ₹13.5 per
     * company per day on the SELL leg only, whatever the quantity.
     *
     * ONCE PER ROUND TRIP, NOT TWICE. The app's older hand-typed fee was charged
     * on both legs like a brokerage, which doubled this.
     */
    const val ZERODHA_DP_CASH_SELL_ONLY = 13.5

    /** GST, on the service charges only — never on STT or stamp duty, which are
     *  taxes in their own right and not services. */
    const val GST_RATE = 0.18

    // ── Binance, for the closed-trades view only ────────────────────────────
    //
    // Binance's standard spot fee, per side. NOT used to cost any algo or
    // backtest — that stays unmodelled, for the reasons [rulesFor] gives. It only
    // splits a closed trade's real net result into a gross figure and a fee,
    // because the fee itself was taken out of the coins and the proceeds and
    // never recorded on its own.
    const val BINANCE_FEE_PCT_PER_SIDE_ESTIMATE = 0.1

    // ── Zerodha, bought and sold the SAME DAY ───────────────────────────────
    //
    // A delivery order squared off the same day never reaches the demat account,
    // so the exchange settles it as intraday and Zerodha charges it as intraday —
    // whatever product it was placed under. Charging those trades the delivery
    // card put a 0.1% tax on both legs and a depository fee on a sale that never
    // left the account: about three times what the contract note says.

    /** Intraday brokerage: 0.03% of each order's value, never more than ₹20 an order. */
    const val ZERODHA_INTRADAY_BROKERAGE_PCT_PER_SIDE = 0.03
    const val ZERODHA_INTRADAY_BROKERAGE_CAP = 20.0

    /** Intraday STT: 0.025%, on the SELL leg only. */
    const val ZERODHA_INTRADAY_STT_PCT_SELL_ONLY = 0.025

    /** Intraday stamp duty: 0.003%, buy side only. */
    const val ZERODHA_INTRADAY_STAMP_PCT_BUY_ONLY = 0.003

    // ── the model ───────────────────────────────────────────────────────────

    // ── THE RATE CARD, WRITTEN ONCE ─────────────────────────────────────────
    //
    // Every charge below is needed in two shapes: as a RATE, to quote a cost per
    // round trip before any trade exists, and as CASH, to put an actual number
    // against a trade that has already happened. Writing those out separately is
    // how a charge ends up in one view and not the other — the shape of mistake
    // this file was created to end, after a Trading 212 conversion fee spent
    // months being charged to a rupee account.
    //
    // So a charge is declared once here and both views are derived from it.
    private data class Rule(
        val label: String,
        /** Percentage of the BUY leg's value. */
        val buyPct: Double = 0.0,
        /** Percentage of the SELL leg's value. */
        val sellPct: Double = 0.0,
        /** Fixed amount, charged on the sell leg only, whatever the size. */
        val sellCash: Double = 0.0,
        /** Does GST ride on this one? True for services, false for taxes —
         *  GST is not levied on STT or on stamp duty. */
        val gstable: Boolean = false,
        /** Most a percentage line may come to on one leg, 0 = no limit
         *  (intraday brokerage stops at ₹20 an order). */
        val capPerSide: Double = 0.0
    )

    private fun rulesFor(ns: String, accountCurrency: String, fxPctPerSide: Double,
                         intraday: Boolean = false): List<Rule> =
        when (Spaces.baseOf(ns)) {
            "in" -> if (intraday) listOf(
                Rule("brokerage",
                    buyPct = ZERODHA_INTRADAY_BROKERAGE_PCT_PER_SIDE,
                    sellPct = ZERODHA_INTRADAY_BROKERAGE_PCT_PER_SIDE,
                    gstable = true, capPerSide = ZERODHA_INTRADAY_BROKERAGE_CAP),
                Rule("STT", sellPct = ZERODHA_INTRADAY_STT_PCT_SELL_ONLY),
                Rule("stamp duty", buyPct = ZERODHA_INTRADAY_STAMP_PCT_BUY_ONLY),
                Rule("exchange",
                    buyPct = ZERODHA_EXCHANGE_PCT_PER_SIDE,
                    sellPct = ZERODHA_EXCHANGE_PCT_PER_SIDE, gstable = true),
                Rule("SEBI",
                    buyPct = ZERODHA_SEBI_PCT_PER_SIDE,
                    sellPct = ZERODHA_SEBI_PCT_PER_SIDE, gstable = true))
            else listOf(
                Rule("brokerage",
                    buyPct = ZERODHA_BROKERAGE_PCT_PER_SIDE,
                    sellPct = ZERODHA_BROKERAGE_PCT_PER_SIDE, gstable = true),
                Rule("STT",
                    buyPct = ZERODHA_STT_PCT_PER_SIDE, sellPct = ZERODHA_STT_PCT_PER_SIDE),
                Rule("stamp duty", buyPct = ZERODHA_STAMP_PCT_BUY_ONLY),
                Rule("exchange",
                    buyPct = ZERODHA_EXCHANGE_PCT_PER_SIDE,
                    sellPct = ZERODHA_EXCHANGE_PCT_PER_SIDE, gstable = true),
                Rule("SEBI",
                    buyPct = ZERODHA_SEBI_PCT_PER_SIDE,
                    sellPct = ZERODHA_SEBI_PCT_PER_SIDE, gstable = true),
                Rule("DP charge", sellCash = ZERODHA_DP_CASH_SELL_ONLY, gstable = true))
            "us", "de" ->
                // Trading 212 takes no commission. The only charge is conversion,
                // and only where the instrument does not settle in the account's
                // own currency — the entire difference between the German and the
                // US space on this platform.
                if (Spaces.currencyOf(ns).equals(accountCurrency, true)) emptyList()
                else listOf(Rule("FX conversion",
                    buyPct = fxPctPerSide.coerceAtLeast(0.0),
                    sellPct = fxPctPerSide.coerceAtLeast(0.0)))
            // Binance is deliberately left unmodelled rather than guessed: its fee
            // depends on a tier, on whether the order took or made liquidity, and
            // on whether it was paid in BNB. A plausible-looking default would be
            // a number nobody chose, presented as one somebody did.
            else -> emptyList()
        }

    /**
     * WHAT A TRADE THAT ALREADY HAPPENED ACTUALLY COST, in money.
     *
     * Takes both legs separately because they are different amounts and several
     * of these charges do not apply evenly across them: stamp duty is on the buy
     * alone, the depository fee on the sell alone, and the transaction tax is a
     * percentage of each leg's own value. A round trip that bought ₹12,000 and
     * sold ₹13,000 does not pay the same on both sides, and averaging them would
     * quietly understate a winner and overstate a loser.
     *
     * This is the difference between the trade and the contract note.
     */
    /**
     * @param intraday bought and sold the same day — charged on the intraday card
     *   where the broker has one (Zerodha); ignored elsewhere.
     * @param fixedShare the part of the per-sale fixed fee this trade carries. The
     *   depository fee is charged once per company per day sold, so when one day's
     *   sale is split across several matched trades each carries its share of the
     *   one fee — never a whole fee each.
     */
    fun chargesFor(
        ns: String,
        buyValue: Double,
        sellValue: Double,
        accountCurrency: String,
        fxPctPerSide: Double = T212_FX_PCT_PER_SIDE,
        intraday: Boolean = false,
        fixedShare: Double = 1.0
    ): Breakdown {
        val b = buyValue.coerceAtLeast(0.0)
        val s = sellValue.coerceAtLeast(0.0)
        val out = ArrayList<Component>()
        var gstBase = 0.0
        for (r in rulesFor(ns, accountCurrency, fxPctPerSide, intraday)) {
            fun leg(pct: Double, v: Double): Double {
                val c = pct / 100.0 * v
                return if (r.capPerSide > 0.0) minOf(c, r.capPerSide) else c
            }
            // THE FIXED FEE NEEDS A SALE TO EXIST. It was added unconditionally,
            // so a half-finished round trip — a buy with no sell yet — was charged
            // a depository fee for shares that had not left the account. Every
            // percentage line already falls to zero on a zero leg; this one had to
            // be told.
            val cash = leg(r.buyPct, b) + leg(r.sellPct, s) +
                       (if (s > 0.0) r.sellCash * fixedShare.coerceIn(0.0, 1.0) else 0.0)
            if (cash <= 0.0) continue
            if (r.gstable) gstBase += cash
            out.add(Component(r.label, cash = cash))
        }
        if (gstBase > 0.0) out.add(Component("GST", cash = gstBase * GST_RATE))
        return Breakdown(out, 0.0, out.sumOf { it.cash })
    }

    /**
     * The broker's own charges for one round trip in [ns], as RATES — for
     * quoting a cost before any trade exists.
     *
     * Derived from the same rules as [chargesFor], so a charge cannot appear on
     * an algo's card and then be missing from its realised P&L, or the reverse.
     * A round trip is assumed to buy and sell the same value here, which is what
     * a forward-looking quote can know; [chargesFor] uses the real two legs.
     *
     * EXCLUDES SLIPPAGE, on purpose. Spread and execution quality are a property
     * of the market and the order, not of the broker's price list, and the two
     * are set in different places by different people. Blending them is how a
     * config's stated 0.20% once got silently swallowed by a typed-in fee.
     *
     * @param accountCurrency only consulted where the broker actually converts.
     * @param fxPctPerSide the conversion rate to assume, per execution.
     */
    fun brokerCosts(
        ns: String,
        accountCurrency: String,
        fxPctPerSide: Double = T212_FX_PCT_PER_SIDE
    ): Breakdown {
        val out = ArrayList<Component>()
        var gstPct = 0.0
        var gstCash = 0.0
        for (r in rulesFor(ns, accountCurrency, fxPctPerSide)) {
            val pct = r.buyPct + r.sellPct
            if (pct <= 0.0 && r.sellCash <= 0.0) continue
            if (r.gstable) { gstPct += pct; gstCash += r.sellCash }
            out.add(Component(r.label, pct = pct, cash = r.sellCash))
        }
        // GST rides on the SERVICE charges and on nothing else. Kept as its own
        // line because it is a tax on the others, not a charge of its own, and
        // folding it into each would hide that.
        if (gstPct > 0.0 || gstCash > 0.0)
            out.add(Component("GST", pct = gstPct * GST_RATE, cash = gstCash * GST_RATE))
        return Breakdown(out, out.sumOf { it.pct }, out.sumOf { it.cash })
    }
}

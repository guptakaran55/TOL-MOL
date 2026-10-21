package com.signalscope.app.trading

// ════════════════════════════════════════════════════════════════════════════
// CLOSED ROUND TRIPS — one FIFO matcher, every broker
// ════════════════════════════════════════════════════════════════════════════
// A broker hands back a list of fills. What a person wants to know is how much
// each completed trade made, and that is not in the list: it has to be built by
// pairing every sell against the buys it actually closed, oldest shares first.
//
// WHY THIS IS NOT INSIDE ONE BROKER'S FILE. It was, and India then needed the
// same thing — at which point the choice was a second copy of the matching rules
// or one engine both brokers feed. This app has already been bitten once by two
// copies of a money path drifting apart (the Zerodha buy button learned about
// limit orders and the algo mirror did not, so every algo entry was refused for
// three days). Once is enough.
//
// So the fills arrive already translated into [Fill] and nothing in here knows
// which broker produced them. The per-broker parts — how to fetch history, how a
// ticker is spelled, which currency the cash is in — stay with their broker.
// ════════════════════════════════════════════════════════════════════════════

object ClosedTrades {

    /**
     * One execution, in neutral terms.
     *
     * BOTH [price] AND [cash] because they answer different questions and can
     * legitimately disagree. Price is per share in the instrument's own currency;
     * cash is what actually left or entered the account, which after conversion
     * is a different number entirely. P&L is built from cash wherever it can be
     * trusted, so a dollar trade on a euro account does not report dollars as
     * euros — see [cashPerShare] for when it cannot be trusted.
     */
    data class Fill(
        /** The broker's own spelling, for tracing a row back to a statement. */
        val ticker: String,
        /** What a human calls it. */
        val symbol: String,
        val qty: Double,
        val price: Double,
        /** Total cash moved, account currency. 0 when the broker does not say,
         *  in which case price × quantity is the only available answer. */
        val cash: Double,
        /** BUY / SELL. */
        val side: String,
        /** ISO-8601, sortable as text. The matcher orders by this and nothing else. */
        val at: String
    )

    data class RoundTrip(
        val ticker: String,
        val symbol: String,
        val qty: Double,
        val buyPrice: Double,
        val sellPrice: Double,
        val boughtAt: String,
        val soldAt: String,
        /** Cash out on the way in, and back on the way out — account currency. */
        val cashIn: Double,
        val cashOut: Double,
        val pnl: Double,
        val pctReturn: Double,
        val heldDays: Int,
        /**
         * What the broker and the taxman took on this round trip, in the account
         * currency. 0 means "not costed", never "free" — the panel says which.
         *
         * NOT SUBTRACTED FROM [pnl]. The gross figure is what the trade did and
         * the net is what reached the account, and both are worth seeing: a
         * strategy judged on gross looks better than the account ever will, while
         * one judged only on net cannot tell a bad entry from an expensive seat.
         */
        val charges: Double = 0.0,
        /** What [charges] is made of — STT, stamp duty, the depository fee, GST… —
         *  so a figure that looks too big can be opened and checked line by line.
         *  Null on a broker that is not costed. */
        val chargeParts: Map<String, Double>? = null,
        /** Bought and sold the same day — charged as intraday where that differs. */
        val sameDay: Boolean = false
    ) {
        /** What actually reached the account. */
        val netPnl: Double get() = pnl - charges
        /** Net return on the money put in, which is the only percentage that can
         *  honestly be compared with a savings rate. */
        val netPctReturn: Double get() =
            if (cashIn > 0.0) (pnl - charges) / cashIn * 100.0 else 0.0
    }

    data class Report(
        val ok: Boolean = false,
        val error: String = "",
        val checkedAt: Long = 0L,
        val fillsSeen: Int = 0,
        val trips: List<RoundTrip> = emptyList(),
        /** Shares bought and never sold — reported, never counted. */
        val stillOpen: Map<String, Double> = emptyMap(),
        /**
         * Shares sold that no buy in the fetched history accounts for. Not an
         * error and not a trade: it means the purchase happened further back than
         * the history reached. Named so a short total is explained rather than
         * silently wrong.
         */
        val unmatchedSells: Map<String, Double> = emptyMap(),
        val totalPnl: Double = 0.0,
        val wins: Int = 0,
        val losses: Int = 0,
        /** Charges across every trip. 0 when this broker is not costed. */
        val totalCharges: Double = 0.0,
        /** True once every trip carries a costing, so the panel can promise a NET
         *  figure rather than implying one it cannot back up. */
        val costed: Boolean = false,
        val currency: String = "",
        /** How far back the fills actually go, so a total can be read against a
         *  period rather than mistaken for all time. Blank when unknown. */
        val firstFillAt: String = "",
        val lastFillAt: String = ""
    )

    private data class Lot(var qty: Double, val origQty: Double, val price: Double,
                           val cash: Double, val at: String)

    /**
     * THE CASH A SHARE ACTUALLY COST, sanity-checked against its own price.
     *
     * Cash is preferred because it survives a currency difference: a $100 fill is
     * about €86, and price × quantity would report the dollar figure as euros.
     * But cash is only trustworthy while it describes the SAME shares as the
     * quantity beside it, and when a feed pairs an order's total with one rung's
     * quantity the result is nonsense — a 0.11 loss displayed as 203.66.
     *
     * So the ratio is checked before it is believed. A real one is either a
     * currency rate, or the hundredfold of a market that quotes in pence while
     * settling in pounds. Anything else is a mismatch, and the honest fallback is
     * the price, which cannot disagree with itself.
     *
     * A wrong number on a P&L screen is worse than a missing one — it is believed.
     */
    fun cashPerShare(cash: Double, qty: Double, price: Double): Double {
        if (qty <= 0.0) return 0.0
        if (price <= 0.0) return if (cash > 0.0) cash / qty else 0.0
        if (cash <= 0.0) return price
        val per = cash / qty
        val ratio = per / price
        val currencyLike = ratio in 0.4..2.5     // any pair of currencies a broker trades
        val penceLike = ratio in 0.004..0.025    // quoted in pence, settled in pounds
        return if (currencyLike || penceLike) per else price
    }

    private fun daysBetween(a: String, b: String): Int = try {
        val f = java.time.Instant.parse(a.take(19) + "Z").epochSecond
        val t = java.time.Instant.parse(b.take(19) + "Z").epochSecond
        ((t - f) / 86400L).toInt().coerceAtLeast(0)
    } catch (e: Exception) { 0 }

    /**
     * Walk the history once, pairing sells against the buys they closed.
     *
     * Pure over the fills it is given, so the matching can be tested against a
     * known sequence rather than against an account.
     */
    /**
     * Put the broker's real charges against every trip, using each one's OWN two
     * legs rather than a per-seat estimate.
     *
     * Separate from [match] on purpose: matching is arithmetic over fills and has
     * no idea which broker they came from, while costing is entirely a question
     * of whose price list applies. Keeping them apart is what lets one matcher
     * serve every market.
     */
    fun costed(rep: Report, ns: String, accountCurrency: String,
               fxPctPerSide: Double = TradingCosts.T212_FX_PCT_PER_SIDE): Report {
        // TWO THINGS A PIECE CANNOT KNOW ON ITS OWN, decided across all of them.
        //
        // Each row here is one matched PIECE — one buy lot against one sell — and
        // a sale that filled in three parts, or closed three lots, is three pieces.
        // Costing every piece as a whole trade charged the depository fee three
        // times for one sale (Zerodha charges it once per company per day sold),
        // so it is now shared across that day's pieces by value.
        //
        // And a piece bought and sold on the same day is intraday in the broker's
        // books, whatever product it went in as — a different, much smaller card.
        fun day(at: String) = at.take(10)
        val delivered = rep.trips.filter { day(it.boughtAt) != day(it.soldAt) }
        val soldThatDay = delivered.groupBy { it.ticker + "|" + day(it.soldAt) }
            .mapValues { (_, ts) -> ts.sumOf { it.cashOut } }
        val priced = rep.trips.map { t ->
            val same = day(t.boughtAt).isNotEmpty() && day(t.boughtAt) == day(t.soldAt)
            val dayTotal = soldThatDay[t.ticker + "|" + day(t.soldAt)] ?: 0.0
            val share = if (same) 0.0 else if (dayTotal > 0.0) t.cashOut / dayTotal else 1.0
            val c = TradingCosts.chargesFor(ns, t.cashIn, t.cashOut, accountCurrency, fxPctPerSide,
                intraday = same, fixedShare = share)
            t.copy(charges = c.cashRoundTrip, sameDay = same,
                chargeParts = c.components.associate { it.label to it.cash })
        }
        return rep.copy(
            trips = priced,
            totalCharges = priced.sumOf { it.charges },
            // A market with no price list modelled must not claim a net figure —
            // it would read as "these trades cost nothing", which is never true.
            costed = TradingCosts.brokerCosts(ns, accountCurrency, fxPctPerSide)
                .components.isNotEmpty())
    }

    fun match(fills: List<Fill>): Pair<List<RoundTrip>, Report> {
        val open = HashMap<String, ArrayDeque<Lot>>()
        val trips = ArrayList<RoundTrip>()
        val unmatched = HashMap<String, Double>()

        val sorted = fills.sortedBy { it.at }
        for (f in sorted) {
            if (f.qty <= 0.0 || f.ticker.isBlank()) continue
            val q = open.getOrPut(f.ticker) { ArrayDeque() }
            if (f.side.equals("BUY", true)) {
                q.addLast(Lot(f.qty, f.qty, f.price, f.cash, f.at))
                continue
            }
            // A SELL eats the oldest shares first.
            var remaining = f.qty
            // Cash per share on THIS sell, so a partial consumption takes its share
            // of the money rather than all of it.
            val sellCashPerShare = cashPerShare(f.cash, f.qty, f.price)
            while (remaining > 1e-9 && q.isNotEmpty()) {
                val lot = q.first()
                val take = minOf(remaining, lot.qty)
                // The lot's ORIGINAL size, not what is left of it: consuming
                // half a lot does not change what a share of it cost.
                val buyCashPerShare = cashPerShare(lot.cash, lot.origQty, lot.price)
                val cashIn = buyCashPerShare * take
                val cashOut = sellCashPerShare * take
                trips.add(RoundTrip(
                    ticker = f.ticker, symbol = f.symbol, qty = take,
                    buyPrice = lot.price, sellPrice = f.price,
                    boughtAt = lot.at, soldAt = f.at,
                    cashIn = cashIn, cashOut = cashOut,
                    pnl = cashOut - cashIn,
                    pctReturn = if (cashIn > 0.0) (cashOut / cashIn - 1.0) * 100.0 else 0.0,
                    heldDays = daysBetween(lot.at, f.at)))
                lot.qty -= take
                remaining -= take
                if (lot.qty <= 1e-9) q.removeFirst()
            }
            if (remaining > 1e-9) {
                // Sold shares this history cannot account for. The buy is older
                // than the window, so there is no honest entry price for it —
                // recorded as unmatched rather than paired with a guess.
                unmatched[f.ticker] = (unmatched[f.ticker] ?: 0.0) + remaining
            }
        }

        val stillOpen = open.mapValues { (_, lots) -> lots.sumOf { it.qty } }
            .filterValues { it > 1e-9 }
        val realised = trips.sumOf { it.pnl }
        return trips to Report(
            ok = true, checkedAt = System.currentTimeMillis(), fillsSeen = fills.size,
            trips = trips.sortedByDescending { it.soldAt },
            stillOpen = stillOpen, unmatchedSells = unmatched,
            totalPnl = realised,
            wins = trips.count { it.pnl > 0.0 },
            losses = trips.count { it.pnl < 0.0 },
            firstFillAt = sorted.firstOrNull()?.at ?: "",
            lastFillAt = sorted.lastOrNull()?.at ?: "")
    }
}

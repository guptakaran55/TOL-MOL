package com.signalscope.app.util

import com.signalscope.app.data.ScoringWeights
import com.signalscope.app.data.StockAnalysis
import com.signalscope.app.network.YahooFinanceClient
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Calculates the Value Score (0-100) from deterministic Yahoo fundamental data.
 *
 * The score now blends cheapness with business quality:
 *   1. Valuation discipline (30): PE/PB/EV-EBITDA, sector PE, forward PE trend
 *   2. Quality + balance sheet (30): debt, cash, liquidity, ROE, margins, cash conversion
 *   3. Growth durability (18): revenue and earnings growth
 *   4. Shareholder yield (10): dividend, future buyback hook
 *   5. Price discount (12): proximity to 52-week low
 */
object ValueAnalyzer {

    data class ValueResult(
        val valueScore: Int,
        val valueRating: String,
        val trailingPe: Double?,
        val forwardPe: Double?,
        val priceToBook: Double?,
        val evToEbitda: Double?,
        val debtToEquity: Double?,
        val roce: Double?,           // Yahoo ROE proxy, as percentage
        val revenueGrowth: Double?,  // as percentage
        val earningsGrowth: Double?, // as percentage
        val grossMargins: Double?,   // as percentage
        val operatingMargins: Double?,
        val profitMargins: Double?,
        val dividendYield: Double?,  // as percentage
        val operatingCashflow: Double?,
        val freeCashflow: Double?,
        val netIncome: Double?,
        val totalRevenue: Double?,
        val totalCash: Double?,
        val totalDebt: Double?,
        val currentRatio: Double?,
        val annualRevenueGrowth: Double?,
        val annualNetIncomeGrowth: Double?,
        val fiftyTwoWeekLow: Double?,
        val fiftyTwoWeekHigh: Double?,
        val sharesOutstanding: Long?,
        val sector: String?,
        val industry: String?,
        val sectorMedianPe: Double?,
        val hasBuyback: Boolean,
        val valueScoreReport: String
    )

    fun analyze(
        fundamentals: YahooFinanceClient.FundamentalData,
        currentPrice: Double,
        sectorMedianPe: Double? = null,
        w: ScoringWeights = ScoringWeights()
    ): ValueResult {
        val pe = fundamentals.trailingPe
        val fwdPe = fundamentals.forwardPe
        val pb = fundamentals.priceToBook
        val evEbitda = fundamentals.enterpriseToEbitda
        val de = fundamentals.debtToEquity
        val roePct = fundamentals.returnOnEquity?.let { it * 100.0 }
        val cfo = fundamentals.operatingCashflow
        val fcf = fundamentals.freeCashflow
        val ni = fundamentals.netIncome
        val revenue = fundamentals.totalRevenue
        val cash = fundamentals.totalCash
        val debt = fundamentals.totalDebt
        val netCash = if (cash != null && debt != null) cash - debt else null
        val currentRatio = fundamentals.currentRatio
        val revenueGrowth = chooseGrowth(fundamentals.revenueGrowth, fundamentals.annualRevenueGrowth)
        val earningsGrowth = chooseGrowth(fundamentals.earningsGrowth, fundamentals.annualNetIncomeGrowth)
        val grossMargin = fundamentals.grossMargins
        val operatingMargin = fundamentals.operatingMargins
        val profitMargin = fundamentals.profitMargins
        val divYield = fundamentals.dividendYield
        val low52 = fundamentals.fiftyTwoWeekLow

        var valuation = 0
        val valuationReasons = mutableListOf<String>()
        if (pe != null && pe > 0) {
            val absPts = when {
                pe < 12.0 -> 10
                pe < 18.0 -> 7
                pe < 25.0 -> 4
                else -> 0
            }
            valuation += absPts
            if (absPts > 0) valuationReasons += "PE ${fmt(pe)}"

            if (sectorMedianPe != null && sectorMedianPe > 0) {
                val secPts = when {
                    pe <= sectorMedianPe * 0.75 -> 8
                    pe < sectorMedianPe -> 5
                    else -> 0
                }
                valuation += secPts
                if (secPts > 0) valuationReasons += "PE < sector"
            }
        }
        if (fwdPe != null && pe != null && fwdPe > 0 && pe > 0 && fwdPe <= pe * 0.90) {
            valuation += 4
            valuationReasons += "forward PE improving"
        }
        if (pb != null && pb > 0) {
            when {
                pb < w.valuePbLowThreshold -> {
                    valuation += 6
                    valuationReasons += "PB ${fmt(pb)}"
                }
                pb < w.valuePbMidThreshold -> valuation += 3
            }
        }
        if (evEbitda != null && evEbitda > 0) {
            when {
                evEbitda < w.valueEvEbitdaLowThreshold -> {
                    valuation += 8
                    valuationReasons += "EV/EBITDA ${fmt(evEbitda)}"
                }
                evEbitda < w.valueEvEbitdaMidThreshold -> valuation += 4
            }
        }
        valuation = valuation.coerceIn(0, 30)

        var quality = 0
        val qualityReasons = mutableListOf<String>()
        if (de != null) {
            when {
                de < w.valueDebtEquityNetCashThreshold -> {
                    quality += 6
                    qualityReasons += "low debt"
                }
                de < w.valueDebtEquityLowThreshold -> quality += 4
                de < w.valueDebtEquityMidThreshold -> quality += 2
            }
        }
        if (netCash != null && netCash >= 0.0) {
            quality += 4
            qualityReasons += "net cash"
        }
        if (currentRatio != null) {
            when {
                currentRatio >= 1.5 -> quality += 3
                currentRatio >= 1.0 -> quality += 1
            }
        }
        if (roePct != null) {
            when {
                roePct >= w.valueRoceHighThreshold -> {
                    quality += 6
                    qualityReasons += "ROE ${fmt(roePct)}%"
                }
                roePct >= w.valueRoceMidThreshold -> quality += 3
            }
        }
        if (profitMargin != null) {
            when {
                profitMargin >= 10.0 -> quality += 4
                profitMargin >= 5.0 -> quality += 2
            }
        }
        if (operatingMargin != null) {
            when {
                operatingMargin >= 15.0 -> quality += 3
                operatingMargin >= 8.0 -> quality += 1
            }
        }
        if (cfo != null && ni != null && ni > 0 && cfo > ni) {
            quality += 4
            qualityReasons += "CFO > NI"
        }
        if (fcf != null && fcf > 0.0) {
            quality += 3
            qualityReasons += "FCF positive"
        }
        quality = quality.coerceIn(0, 30)

        var growth = 0
        val growthReasons = mutableListOf<String>()
        val penaltyReasons = mutableListOf<String>()
        var growthPenalty = 0
        if (revenueGrowth != null) {
            when {
                revenueGrowth >= w.valueRevenueGrowthHighThreshold -> {
                    growth += w.valueRevenueGrowthHighPts
                    growthReasons += "revenue +${fmt(revenueGrowth)}%"
                }
                revenueGrowth >= w.valueRevenueGrowthMidThreshold -> growth += w.valueRevenueGrowthMidPts
                revenueGrowth > 0.0 -> growth += w.valueRevenueGrowthPositivePts
            }
        }
        if (earningsGrowth != null) {
            when {
                earningsGrowth >= w.valueEarningsGrowthHighThreshold -> {
                    growth += w.valueEarningsGrowthHighPts
                    growthReasons += "earnings +${fmt(earningsGrowth)}%"
                }
                earningsGrowth >= w.valueEarningsGrowthMidThreshold -> growth += w.valueEarningsGrowthMidPts
                earningsGrowth > 0.0 -> growth += w.valueEarningsGrowthPositivePts
            }
        }
        if (grossMargin != null && grossMargin >= w.valueGrossMarginThreshold) growth += w.valueGrossMarginPts
        if (profitMargin != null &&
            profitMargin >= w.valueProfitMarginGrowthThreshold &&
            earningsGrowth != null &&
            earningsGrowth > 0.0
        ) {
            growth += w.valueProfitMarginGrowthPts
        }
        growth = growth.coerceIn(0, 18)

        var yield = 0
        val yieldReasons = mutableListOf<String>()
        if (divYield != null && divYield > 0) {
            when {
                divYield >= w.valueDivHighThreshold -> {
                    yield += 6
                    yieldReasons += "dividend ${fmt(divYield)}%"
                }
                divYield >= w.valueDivMidThreshold -> yield += 3
            }
        }
        val hasBuyback = false
        if (hasBuyback) yield += 4
        yield = yield.coerceIn(0, 10)

        var discount = 0
        val discountReasons = mutableListOf<String>()
        if (low52 != null && low52 > 0 && currentPrice > 0) {
            val pctFromLow = ((currentPrice - low52) / low52) * 100.0
            when {
                pctFromLow < w.value52wLowCloseThreshold -> {
                    discount += 12
                    discountReasons += "near 52W low"
                }
                pctFromLow < w.value52wLowMidThreshold -> discount += 5
            }
        }
        discount = discount.coerceIn(0, 12)

        var score = valuation + quality + growth + yield + discount
        if (revenueGrowth != null && revenueGrowth < w.valueRevenueGrowthPenaltyThreshold) {
            score -= w.valueRevenueGrowthPenaltyPts
            growthPenalty += w.valueRevenueGrowthPenaltyPts
            penaltyReasons += "revenue ${fmt(revenueGrowth)}%"
        }
        if (earningsGrowth != null && earningsGrowth < w.valueEarningsGrowthPenaltyThreshold) {
            score -= w.valueEarningsGrowthPenaltyPts
            growthPenalty += w.valueEarningsGrowthPenaltyPts
            penaltyReasons += "earnings ${fmt(earningsGrowth)}%"
        }
        if (fcf != null && fcf < 0.0) score -= 4
        score = score.coerceIn(0, 100)

        val rating = when {
            score >= w.valueDeepThreshold -> "DEEP VALUE"
            score >= w.valueModerateThreshold -> "MODERATE VALUE"
            score >= w.valueMildThreshold -> "MILD VALUE"
            else -> "NOT ATTRACTIVE"
        }

        val report = listOf(
            "Valuation|$valuation|30|${valuationReasons.joinToString("; ").ifBlank { "no cheap valuation signal" }}",
            "Quality|$quality|30|${qualityReasons.joinToString("; ").ifBlank { "no strong quality signal" }}",
            "Growth|$growth|18|${growthReasons.joinToString("; ").ifBlank { "no growth confirmation" }}",
            "Yield|$yield|10|${yieldReasons.joinToString("; ").ifBlank { "no shareholder-yield signal" }}",
            "Discount|$discount|12|${discountReasons.joinToString("; ").ifBlank { "not close to 52W low" }}",
            "Value traps|-$growthPenalty|12|${penaltyReasons.joinToString("; ").ifBlank { "no growth penalty" }}"
        ).joinToString("\n")

        return ValueResult(
            valueScore = score,
            valueRating = rating,
            trailingPe = pe,
            forwardPe = fwdPe,
            priceToBook = pb,
            evToEbitda = evEbitda,
            debtToEquity = de,
            roce = roePct,
            revenueGrowth = revenueGrowth,
            earningsGrowth = earningsGrowth,
            grossMargins = grossMargin,
            operatingMargins = operatingMargin,
            profitMargins = profitMargin,
            dividendYield = divYield,
            operatingCashflow = cfo,
            freeCashflow = fcf,
            netIncome = ni,
            totalRevenue = revenue,
            totalCash = cash,
            totalDebt = debt,
            currentRatio = currentRatio,
            annualRevenueGrowth = fundamentals.annualRevenueGrowth,
            annualNetIncomeGrowth = fundamentals.annualNetIncomeGrowth,
            fiftyTwoWeekLow = low52,
            fiftyTwoWeekHigh = fundamentals.fiftyTwoWeekHigh,
            sharesOutstanding = fundamentals.sharesOutstanding,
            sector = fundamentals.sector,
            industry = fundamentals.industry,
            sectorMedianPe = sectorMedianPe,
            hasBuyback = hasBuyback,
            valueScoreReport = report
        )
    }

    fun analyzeSnapshot(
        stock: StockAnalysis,
        sectorMedianPe: Double? = null,
        w: ScoringWeights = ScoringWeights()
    ): ValueResult {
        return analyze(
            fundamentals = YahooFinanceClient.FundamentalData(
                trailingPe = stock.trailingPe,
                forwardPe = stock.forwardPe,
                priceToBook = stock.priceToBook,
                enterpriseToEbitda = stock.evToEbitda,
                debtToEquity = stock.debtToEquity,
                returnOnEquity = stock.roce?.let { it / 100.0 },
                revenueGrowth = stock.revenueGrowth,
                earningsGrowth = stock.earningsGrowth,
                grossMargins = stock.grossMargins,
                operatingMargins = stock.operatingMargins,
                profitMargins = stock.profitMargins,
                dividendYield = stock.dividendYield,
                operatingCashflow = stock.operatingCashflow,
                freeCashflow = stock.freeCashflow,
                totalRevenue = stock.totalRevenue,
                netIncome = stock.netIncome,
                totalCash = stock.totalCash,
                totalDebt = stock.totalDebt,
                currentRatio = stock.currentRatio,
                annualRevenueGrowth = stock.annualRevenueGrowth,
                annualNetIncomeGrowth = stock.annualNetIncomeGrowth,
                fiftyTwoWeekLow = stock.fiftyTwoWeekLow,
                fiftyTwoWeekHigh = stock.fiftyTwoWeekHigh,
                sharesOutstanding = stock.sharesOutstanding,
                sector = stock.sector,
                industry = stock.industry
            ),
            currentPrice = stock.price,
            sectorMedianPe = sectorMedianPe,
            w = w
        )
    }

    private fun chooseGrowth(current: Double?, annual: Double?): Double? = when {
        current != null && current.isFinite() -> current
        annual != null && annual.isFinite() -> annual
        else -> null
    }

    private fun fmt(v: Double): String = (v * 10.0).roundToInt().let { (it / 10.0).toString() }
}

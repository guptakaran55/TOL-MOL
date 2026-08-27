package com.signalscope.app.data

import com.google.gson.annotations.SerializedName

/**
 * All configurable scoring weights and thresholds used by StockAnalyzer.
 * Defaults match the recommended (battle-tested) values.
 *
 * Three sections: Entry Points, Profit Booking, Capital Protection.
 *
 * v3 rename: cherryStrongThreshold / cherryModerateThreshold (was buy*).
 * @SerializedName keeps persisted weight overrides loading from old configs.
 */
data class ScoringWeights(

    // ═══════════════════════════════════════════════════════
    // PULLBACK POINTS (legacy buy score math, recommended max ~115)
    // ═══════════════════════════════════════════════════════

    // 1. SMA trend
    val buySma200Pts: Int = 25,          // Points when price > SMA200
    val buySma50Pts: Int = 15,           // Fallback when only 50–199 candles

    // 2. MACD inflection (mutually exclusive)
    val buyGoldenBuyPts: Int = 30,       // Qualified MACD setup before/around zero-cross
    val buyGoldenBonus: Int = 10,        // Extra reward when the early reversal is confirmed
    val buyMacdZeroCrossUpPts: Int = 30, // MACD zero-cross up with accel > 0
    val buySlopeCrossUpPts: Int = 20,    // MACD slope cross up
    val buyEarlyBuyPts: Int = 10,        // Early buy
    val buyMacdPctlBonus: Int = 3,       // Bonus when MACD percentile ≤ 25
    val buyMacdPctlThreshold: Double = 25.0,  // MACD percentile threshold for bonus

    // 3. RSI (graduated, max = buyRsiMaxPts + buyRsiFlipBonus)
    val buyRsiMaxPts: Int = 20,
    val buyRsiFlipBonus: Int = 8,        // Bonus when RSI just turned upward from oversold
    val buyRsiFlipLow: Double = 25.0,    // RSI range low for flip detection
    val buyRsiFlipHigh: Double = 45.0,   // RSI range high for flip detection

    // 4. Bollinger Bands
    val buyBbBasePts: Int = 10,          // Price below mid-band
    val buyBbLowerBonus: Int = 5,        // Touched lower band bonus

    // 5. ADX
    val buyAdxStrongThreshold: Double = 25.0,
    val buyAdxVeryStrongThreshold: Double = 30.0,
    val buyAdxBasePts: Int = 15,         // ADX > 25 + bullish
    val buyAdxVeryStrongBonus: Int = 5,  // ADX > 30 bonus

    // 6. OBV
    val buyObvPts: Int = 5,

    // 7. EMA(21) proximity (graduated)
    val buyEmaMaxPts: Int = 10,

    // Buy signal thresholds
    val pullbackStrongThreshold: Int = 75,    // pullbackScore >= this -> strong pullback entry
    val pullbackModerateThreshold: Int = 60,  // pullbackScore >= this -> moderate pullback entry

    // Legacy net-entry thresholds. Kept so older code and saved settings that
    // still reference buyScore stay compatible.
    @SerializedName("buyStrongThreshold")
    val cherryStrongThreshold: Int = 75,    // cherryPoints >= this -> strong entry
    @SerializedName("buyModerateThreshold")
    val cherryModerateThreshold: Int = 60,  // cherryPoints >= this -> moderate entry

    // MOMENTUM POINTS (trend-continuation entry, max 100)
    // ── Short-term confirmation buckets (reduced — these used to dominate) ──
    // MACD inflection is fundamentally a pullback signal so we don't want it
    // driving the momentum score; it stays only as a "momentum isn't dying"
    // confirmation. EMA21 / RSI / NotOverextended are all short-term entry
    // timing — kept for confirmation but at low weight. Total ~33 pts out of
    // ~103 = roughly one-third confirmation, two-thirds long-term quality.
    val momentumTrendStackPts: Int = 10,       // price + 200/50/21 trend alignment (slopes today)
    val momentumMacdPts: Int = 8,              // MACD bullish phase / positive slope
    val momentumEma21SlopePts: Int = 5,        // short-term EMA rising
    val momentumNotOverextendedPts: Int = 3,   // price has room; avoids chasing far above EMA21
    val momentumRsiRoomPts: Int = 3,           // RSI has upward room
    val momentumObvPts: Int = 4,               // accumulation confirmation

    // ── Long-term quality buckets (PRIMARY — these drive momentum score) ──
    // These survive zoom-out: they measure whether this stock has *been* a
    // good buy-and-hold over the last 6-12 months, not whether today's
    // inflection looks good. Total ~70 pts of ~103.
    /** % of last 200 days price closed above SMA200. ≥80% = sustained-trend
     *  quality. Direct measure of "has this been a quietly trending stock?" */
    val momentumTrendPersistencePts: Int = 20,
    /** EMA21 > EMA50 > EMA200 today with all three slopes positive.
     *  Classic Stage-2 markup — sustained order across timeframes. */
    val momentumEmaCascadePts: Int = 15,
    /** Max drawdown over last 252 days. Shallow drawdown (≤10%) = smoother
     *  ride that a holder actually wants. ATR-relative could come later. */
    val momentumDrawdownPts: Int = 10,
    /** 6-month + 12-month cumulative returns. Direct buy-and-hold payoff. */
    val momentumLongReturnPts: Int = 15,
    /** Sharpe-like ratio (annualized return / annualized vol, 252-day).
     *  Risk-adjusted quality — high return AND low vol = best holding. */
    val momentumSharpePts: Int = 10,

    val momentumStrongThreshold: Int = 75,
    val momentumModerateThreshold: Int = 60,

    // ── MACD angle tunables for Setups / qualified MACD setup ──
    val minSlopeMagnitude: Double = -45.0,  // signed absolute MACD angle lower bound: atan(d(MACD)/dt)
    val goldenBuyMaxSlope: Double = 85.0,   // signed absolute MACD angle upper cap; high values allow sudden positive surges
    val setupSma200MinSlope: Double = 0.1,  // minimum d(SMA200)/dt for Setups + qualified setup
    val setupMacdLowPctMin: Double = 60.0,  // legacy: old minimum % closeness to the 1Y MACD low
    val setupMacdPctlMax: Double = 75.0,    // maximum 1Y MACD percentile for Setups + qualified setup

    // ═══════════════════════════════════════════════════════
    // PROFIT BOOKING WEIGHTS (recommended max ~58)
    // ═══════════════════════════════════════════════════════

    // 1. MACD momentum (mutually exclusive)
    val profitSlopeCrossDnPts: Int = 28, // Prime exit — price at/near peak
    val profitEarlySellPts: Int = 15,    // Warning — peak approaching
    val profitMacdZeroCrossDnPts: Int = 8, // Confirmation only
    val profitMacdPctlBonus: Int = 3,    // Bonus when MACD percentile ≥ 75
    val profitMacdPctlThreshold: Double = 75.0,

    // 2. RSI overbought tiers
    val profitRsiExtremePts: Int = 25,   // RSI > 85
    val profitRsiOverboughtPts: Int = 20,// RSI ≥ 70
    val profitRsiMildPts: Int = 12,      // RSI ≥ 65
    val profitRsiNoisePts: Int = 6,      // RSI ≥ 60
    val profitRsiFlipBonus: Int = 5,     // Bonus when RSI just turned downward from overbought
    val profitRsiExtremeThreshold: Double = 85.0,
    val profitRsiOverboughtThreshold: Double = 70.0,
    val profitRsiMildThreshold: Double = 65.0,
    val profitRsiNoiseThreshold: Double = 60.0,

    // 3. Bollinger stretch
    val profitBbUpperPts: Int = 10,      // Price ≥ upper band
    val profitBbTouchedPts: Int = 5,     // Touched upper band recently

    // 4. OBV bearish divergence — distribution detection
    // Classic textbook: price makes a new (5-day) high but OBV does not confirm.
    // Means smart money is selling into retail buying. Strong early warning.
    val profitObvDivergencePts: Int = 8,  // Price at/near 5-day high AND OBV < OBV5 → divergence
    val profitObvWeaknessPts: Int = 4,    // OBV < OBV20 (softer: distribution under the surface)
    val profitObvNewHighProximityPct: Double = 0.99, // price ≥ 99% of recent 5-day high counts as "at the high"

    // Activation threshold
    val profitActivationThreshold: Int = 35, // profitScore ≥ this to fire

    // ═══════════════════════════════════════════════════════
    // CAPITAL PROTECTION WEIGHTS (recommended max ~58)
    // ═══════════════════════════════════════════════════════

    // 1. Price below SMA200
    val protectSma200Pts: Int = 28,      // Primary structural break
    val protectSma200SlopeBonus: Int = 5,// SMA200 slope declining bonus

    // 2. ADX bearish (mutually exclusive tiers)
    val protectAdxStrongPts: Int = 15,   // ADX > 30 + bearish
    val protectAdxWeakPts: Int = 10,     // ADX > 25 + bearish
    val protectAdxStrongThreshold: Double = 30.0,
    val protectAdxWeakThreshold: Double = 25.0,

    // 3. OBV declining
    val protectObvPts: Int = 12,

    // 4. MACD zero-cross down
    val protectMacdZeroCrossDnPts: Int = 8,

    // Activation threshold
    val protectActivationThreshold: Int = 35, // protectScore ≥ this to fire

    // ═══════════════════════════════════════════════════════
    // VALUE CATCHING SCORE (max 100)
    // Fundamental valuation — completely separate from technical scores.
    // ═══════════════════════════════════════════════════════

    // 1. Valuation Multiples (max 35)
    val valuePeSectorPts: Int = 15,       // PE < sector median
    val valuePeHistoricalPts: Int = 15,   // PE < 70% of 5Y avg (use trailing PE vs historical)
    val valuePeHistoricalPct: Double = 70.0, // % threshold for historical PE comparison
    val valuePbLowPts: Int = 10,          // PB < 1.5
    val valuePbMidPts: Int = 5,           // PB 1.5–2.0
    val valuePbLowThreshold: Double = 1.5,
    val valuePbMidThreshold: Double = 2.0,
    val valueEvEbitdaLowPts: Int = 10,    // EV/EBITDA < 8
    val valueEvEbitdaMidPts: Int = 5,     // EV/EBITDA 8–12
    val valueEvEbitdaLowThreshold: Double = 8.0,
    val valueEvEbitdaMidThreshold: Double = 12.0,

    // 2. Financial Safety (max 30)
    val valueDebtEquityNetCashPts: Int = 15, // D/E < 0.3 (net cash)
    val valueDebtEquityLowPts: Int = 10,     // D/E 0.3–1.0
    val valueDebtEquityMidPts: Int = 5,      // D/E 1.0–2.0
    val valueDebtEquityNetCashThreshold: Double = 0.3,
    val valueDebtEquityLowThreshold: Double = 1.0,
    val valueDebtEquityMidThreshold: Double = 2.0,
    val valueRoceHighPts: Int = 10,       // ROCE > 15%
    val valueRoceMidPts: Int = 5,         // ROCE 10–15%
    val valueRoceHighThreshold: Double = 15.0,
    val valueRoceMidThreshold: Double = 10.0,
    val valueCfoPositivePts: Int = 5,     // CFO > Net Income

    // 3. Growth Durability (max 18)
    val valueRevenueGrowthHighPts: Int = 7,
    val valueRevenueGrowthMidPts: Int = 4,
    val valueRevenueGrowthPositivePts: Int = 2,
    val valueRevenueGrowthHighThreshold: Double = 12.0,
    val valueRevenueGrowthMidThreshold: Double = 4.0,
    val valueEarningsGrowthHighPts: Int = 7,
    val valueEarningsGrowthMidPts: Int = 4,
    val valueEarningsGrowthPositivePts: Int = 2,
    val valueEarningsGrowthHighThreshold: Double = 12.0,
    val valueEarningsGrowthMidThreshold: Double = 4.0,
    val valueGrossMarginPts: Int = 2,
    val valueGrossMarginThreshold: Double = 35.0,
    val valueProfitMarginGrowthPts: Int = 2,
    val valueProfitMarginGrowthThreshold: Double = 10.0,
    val valueRevenueGrowthPenaltyThreshold: Double = -5.0,
    val valueRevenueGrowthPenaltyPts: Int = 5,
    val valueEarningsGrowthPenaltyThreshold: Double = -10.0,
    val valueEarningsGrowthPenaltyPts: Int = 7,

    // 4. Shareholder Yield (max 15)
    val valueDivHighPts: Int = 10,        // Yield ≥ 3%
    val valueDivMidPts: Int = 5,          // Yield 1–3%
    val valueDivHighThreshold: Double = 3.0,
    val valueDivMidThreshold: Double = 1.0,
    val valueBuybackPts: Int = 5,         // Active buyback / share count declining

    // 5. Price Discount (max 12)
    val value52wLowClosePts: Int = 12,    // < 15% from 52W low
    val value52wLowMidPts: Int = 5,       // 15–30% from 52W low
    val value52wLowCloseThreshold: Double = 15.0,
    val value52wLowMidThreshold: Double = 30.0,

    // 5. News Sentiment overlay (max ±8)
    val valueNewsSentimentMax: Int = 8,   // ±8 adjustment from LLM/news

    // Value rating thresholds
    val valueDeepThreshold: Int = 80,     // ≥ 80 → Deep Value
    val valueModerateThreshold: Int = 65, // ≥ 65 → Moderate Value
    val valueMildThreshold: Int = 50      // ≥ 50 → Mild Value, < 50 → Not Attractive
)

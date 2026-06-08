package com.signalscope.app.data

/**
 * Data models for SignalScope Android.
 * Matches the Python app's data structures exactly.
 *
 * v2: Added DiscoveryScanResult for full-universe discovery scans.
 */

// ═══════════════════════════════════════════════════════
// CANDLE DATA
// ═══════════════════════════════════════════════════════

data class CandleData(
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long
)

// ═══════════════════════════════════════════════════════
// PORTFOLIO HOLDING
// ═══════════════════════════════════════════════════════

data class PortfolioHolding(
    val symbol: String,
    val token: String,
    val quantity: Int,
    val avgPrice: Double,
    val ltp: Double,
    val pnl: Double,
    val dayChange: Double = 0.0,
    val dayChangePct: Double = 0.0,
    val exchange: String = "NSE",
    val source: String = "zerodha",
    val analysis: StockAnalysis? = null,
    val verdict: String = "HOLD"
) {
    val invested: Double get() = avgPrice * quantity
    val currentVal: Double get() = ltp * quantity
    val totalReturnPct: Double
        get() = if (invested > 0) (currentVal - invested) / invested * 100.0 else 0.0
}

// ═══════════════════════════════════════════════════════
// STOCK ANALYSIS (output of StockAnalyzer)
// ═══════════════════════════════════════════════════════

data class StockAnalysis(
    val symbol: String,
    val name: String,
    val token: String,
    val price: Double,

    val sma200: Double?,
    val sma200Slope: Double,
    val rsi: Double?,
    val bbUpper: Double?,
    val bbMid: Double?,
    val bbLower: Double?,

    val macd: Double,
    val macdSignal: Double,
    val macdHist: Double,
    val macdSlope: Double,
    /** Signed absolute angle derived from raw d(MACD)/dt via atan(slope). */
    val macdSlopeAngle: Double = 0.0,
    val macdAccel: Double,
    val macdPctl: Double,
    val macdLowPct: Double,
    val macd1yLow: Double,
    val macdPhase: String,
    val macdCurve: List<Double> = emptyList(),
    val smoothMacdCurve: List<Double> = emptyList(),
    val smoothMacdSlope: Double = 0.0,
    val smoothMacdPrevSlope: Double = 0.0,
    val smoothMacdAccel: Double = 0.0,
    val smoothMacdLowDistPct: Double = 0.0,
    val smoothMacdHook: Boolean = false,
    val smoothMacdPrevSavedSlope: Double? = null,
    val smoothMacdTurnedPositiveToday: Boolean = false,
    val smoothMacdTurnSourceTimestamp: Long = 0L,

    // 252-session price trend strip for the detail/discovery UI. Raw price is
    // kept alongside a local-linear Kalman smooth so the chart can show both
    // actual movement and the filtered trajectory without recomputing on JS.
    val priceCurve: List<Double> = emptyList(),
    val kalmanCurve: List<Double> = emptyList(),
    val kalmanSlope: Double = 0.0,        // smoothed daily percent slope
    val kalmanTrendPct: Double = 0.0,     // smoothed horizon return
    val kalmanRegime: String = "UNKNOWN",
    val kalmanHorizonDays: Int = 0,

    // Adaptive MACD-watchdog time filter (days). Derived from each stock's
    // 6-month histogram oscillation period. Fast midcaps → 3; slow large-caps → 15+.
    // See MacdExitWatchdog.kt for the derivation formula.
    val minBarsFilter: Int = 6,

    val adx: Double,
    val plusDi: Double,
    val minusDi: Double,
    val obv: Double,

    val ema21: Double?,
    val ema21Slope: Double = 0.0,
    val ema21PctDiff: Double,
    val ema50: Double? = null,
    val ema50Slope: Double = 0.0,
    val ema200: Double? = null,
    val ema200Slope: Double = 0.0,
    val avgVol20: Double,

    // Today's (most-recent candle) raw volume — needed by the dynamic Soft Sniper
    // buffer to confirm "volume spike" vs "thin trade" at GTT-placement time.
    val volume: Double = 0.0,

    val priceVel: Double,
    val priceAccel: Double,
    val priceRoc3: Double,
    val upDays: Int,

    val support: Double,
    val resistance: Double,
    val risk: Double,
    val reward: Double,
    val rrRatio: Double,

    val atr: Double,
    val riskInAtrs: Double,
    val positionSize: Int,
    val capitalNeeded: Double,
    val potentialProfit: Double,
    val rocPct: Double,

    val buyScore: Int,
    val buySignal: String,
    val pullbackScore: Int = 0,
    val pullbackSignal: String = "NO SIGNAL",
    val momentumScore: Int = 0,
    val momentumSignal: String = "NO SIGNAL",
    val entryMode: String = "NONE",

    // Dual sell scoring system — replaces single sellScore
    val profitScore: Int,       // Sub-score A: Profit Booking (max ~58)
    val protectScore: Int,      // Sub-score B: Capital Protection (max ~58)
    val sellIntent: String,     // "STRONG EXIT" | "BOOK PROFIT" | "PROTECT CAPITAL" | "HOLD"

    // Legacy sellScore kept for backward compatibility (= max of profitScore, protectScore)
    val sellScore: Int,
    val sellSignal: String,

    val goldenBuy: Boolean,
    val isBuy: Boolean,
    val isModerateBuy: Boolean,
    val isSell: Boolean,
    val isModerateSell: Boolean,

    // ── OBV bearish divergence flags (feed BOOK_PROFIT score + UI distribution chip) ──
    // obvDivergence: price at/near 5-day high but OBV < OBV5 (strong: smart money exiting)
    // obvWeakness:   OBV < OBV20 (soft: longer-term distribution under the surface)
    val obvDivergence: Boolean = false,
    val obvWeakness: Boolean = false,

    /** Pre-formatted per-bucket trace for the "Why this score?" detail panel
     *  in the dashboard. Pipe-delimited rows (bucket|status|points|max|reason),
     *  newline-separated. Empty string when not built (older cached analysis). */
    val buyScoreReport: String = "",
    val pullbackScoreReport: String = "",
    val momentumScoreReport: String = "",

    /** Read-only qualified MACD setup checklist for the detail modal.
     *  Pipe rows: label|pass|value|required. Empty for older cached analysis. */
    val goldenBuyReport: String = "",

    /** Lightweight first-pass style fit:
     *  swingScore = suitability for repeated support/resistance wave riding.
     *  longTermScore = suitability for slower trend-hold behaviour.
     *  These are heuristic scan-time scores, not a full spectral/Hurst study. */
    val swingScore: Int = 0,
    val longTermScore: Int = 0,
    val styleLabel: String = "MIXED",      // "RIDE" / "HOLD" / "MIXED" / "WEAK"
    val styleReason: String = "",
    val macdCycleDays: Double = 0.0,       // estimated MACD histogram cycle period, days
    val returnAutocorr1: Double = 0.0,     // lag-1 daily-return autocorrelation
    val styleScoreReport: String = "",

    // ── Value Analysis (fundamental) ──
    val trailingPe: Double? = null,
    val forwardPe: Double? = null,
    val priceToBook: Double? = null,
    val evToEbitda: Double? = null,
    val debtToEquity: Double? = null,
    val roce: Double? = null,             // Yahoo ROE proxy, as percentage
    val revenueGrowth: Double? = null,    // as percentage
    val earningsGrowth: Double? = null,   // as percentage
    val grossMargins: Double? = null,     // as percentage
    val operatingMargins: Double? = null, // as percentage
    val profitMargins: Double? = null,    // as percentage
    val dividendYield: Double? = null,    // as percentage
    val operatingCashflow: Double? = null,
    val freeCashflow: Double? = null,
    val netIncome: Double? = null,
    val totalRevenue: Double? = null,
    val totalCash: Double? = null,
    val totalDebt: Double? = null,
    val currentRatio: Double? = null,
    val annualRevenueGrowth: Double? = null,
    val annualNetIncomeGrowth: Double? = null,
    val fiftyTwoWeekLow: Double? = null,
    val fiftyTwoWeekHigh: Double? = null,
    val sharesOutstanding: Long? = null,
    val sector: String? = null,
    val industry: String? = null,
    val sectorMedianPe: Double? = null,   // populated per-scan from sector grouping
    val hasBuyback: Boolean = false,

    // ── Tier 2: Wave projection (opt-in, shown via detail-modal button) ──
    // Projected oscillation range 20 days forward based on EMA50 trend + 2σ envelope.
    // NOT a price forecast — a probabilistic channel.
    val projectedCeiling: Double? = null,
    val projectedFloor: Double? = null,
    val projectedMidpoint: Double? = null,

    val valueScore: Int = 0,              // 0–100 fundamental value score
    val valueRating: String = "N/A",      // "DEEP VALUE" / "MODERATE VALUE" / "MILD VALUE" / "NOT ATTRACTIVE" / "N/A"
    val valueScoreReport: String = ""     // bucket|max|reason rows for audit
)

// ═══════════════════════════════════════════════════════
// ALERTS
// ═══════════════════════════════════════════════════════

enum class AlertType {
    // Intent-based sell alerts (derived from dual scoring)
    STRONG_EXIT,        // profitScore ≥ 35 AND protectScore ≥ 35 — sell all
    BOOK_PROFIT,        // profitScore ≥ 35 AND protectScore < 35 — sell 50-70%, trail rest
    PROTECT_CAPITAL,    // protectScore ≥ 35 AND profitScore < 35 — sell all, trend broken
    PEAK_WARNING,       // earlySell (MACD decelerating) — prepare to sell soon
    MACD_FLIP,          // DPM histogram-watchdog confirmed at 15:20 — sniper GTT / exit ladder fired
    SOFT_SNIPER,        // Tier 1.5 — auto-GTT placed on BOOK_PROFIT alert (intraday, 3% buffer)

    // Buy alerts
    SMOOTH_BUY_TURN,    // Discovery: saved smooth MACD slope crossed positive with quality filters
    GOLDEN_BUY,
    STRONG_BUY,

    // Legacy (kept for backward compat, mapped to new types internally)
    @Deprecated("Use STRONG_EXIT") STRONG_SELL,
    @Deprecated("Use BOOK_PROFIT") MODERATE_SELL,
    @Deprecated("Use PROTECT_CAPITAL") SELL_FLIP,
    @Deprecated("Use PROTECT_CAPITAL") TREND_BREAK,
    @Deprecated("Use PROTECT_CAPITAL") CONSECUTIVE_DECLINE
}

data class StockAlert(
    val symbol: String,
    val name: String,
    val alertType: AlertType,
    val message: String,
    val sellScore: Int,
    val buyScore: Int,
    val price: Double,
    val macdPhase: String,
    val source: String = "zerodha",
    val timestamp: Long = System.currentTimeMillis()
)

// ═══════════════════════════════════════════════════════
// PORTFOLIO SCAN RESULT
// ═══════════════════════════════════════════════════════

data class ScanResult(
    val market: String,
    val startTime: Long,
    val totalScanned: Int,
    val holdings: List<PortfolioHolding>,
    val alerts: List<StockAlert>,
    val durationMs: Long,
    val totalInvested: Double = 0.0,
    val totalCurrent: Double = 0.0,
    val totalPnl: Double = 0.0,
    val sellAlertCount: Int = 0
)

// ═══════════════════════════════════════════════════════
// DISCOVERY SCAN RESULT (NIFTY 500 / NASDAQ 100)
// Mirrors the Python dashboard's "All", "Buy", "Setups" tabs
// ═══════════════════════════════════════════════════════

data class DiscoveryScanResult(
    val market: String,            // "NIFTY 50", "NASDAQ 100", etc.
    val currency: String,          // "₹" or "$"
    val timestamp: Long,
    val totalScanned: Int,
    val totalSymbols: Int,         // how many we attempted
    val skipped: Int,
    val errors: Int,
    val lastError: String = "",    // last error message for UI display
    val isComplete: Boolean,       // false while still scanning

    /** All analyzed stocks, sorted by strongest entry score descending */
    val allStocks: List<StockAnalysis>,

    /** Stocks with moderate Pullback or Momentum points */
    val buySignals: List<StockAnalysis>,

    /** Stocks with strong Pullback or Momentum points */
    val strongBuys: List<StockAnalysis>,

    /** Stocks matching qualified MACD setup criteria */
    val goldenBuys: List<StockAnalysis>,

    /** Setups: SMA pass + BUY FLIP or EARLY BUY phase */
    val setups: List<StockAnalysis>,

    val durationMs: Long
)

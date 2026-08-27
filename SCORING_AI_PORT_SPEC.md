# SignalScope — Scoring & AI Features Port Spec

**Purpose.** This document is a self-contained specification for porting five features from the
SignalScope **Android** app into your existing **SignalScope v4.1 Flask/Python** web scanner:

1. **Momentum Score** (0–~103) — trend-continuation / buy-and-hold quality lens
2. **Pullback Score** (0–~118) — "buy the dip in an uptrend" lens
3. **Value Score** (0–100) — fundamental cheapness + quality (needs Yahoo fundamentals)
4. **AI Pullback Analysis** — LLM verdict TEMPORARY / STRUCTURAL / UNCERTAIN
5. **AI Stock Outlook** — LLM structured outlook + BUY/WATCH/AVOID verdict

Everything below is transcribed from the Android source (`StockAnalyzer.kt`,
`ValueAnalyzer.kt`, `StockAiClient.kt`, `ScoringWeights.kt`). Ports should reproduce the
**exact thresholds, weights, and bucket boundaries** — they are battle-tuned.

> **Key design note.** The two technical scores (momentum, pullback) are computed from the
> *same* indicator set your `analyze_stock()` already builds: close/high/low/volume, SMA(50/200),
> EMA(21/50/200) **and their slopes**, RSI, Bollinger Bands, MACD + MACD slope + MACD acceleration,
> OBV + OBV moving averages, ADX/DI. **You do not need new market data** — you need to (a) make sure
> slopes and MACD percentile are available, and (b) apply the point buckets below.

---

## 0. Shared inputs (compute once, feed all scores)

For each symbol, from the daily OHLCV series (chronological, `n` = number of bars, `price` = last close):

| Symbol in this doc | Meaning | Notes |
|---|---|---|
| `price` | last close | |
| `sma200Val`, `sma50Val` | SMA(200), SMA(50) last value | `None` if insufficient history |
| `sma200Slope` | slope of SMA200 | linear-fit or `sma[-1] - sma[-k]`; sign is what matters |
| `ema21Val`, `ema50Val`, `ema200Val` | EMA last values | |
| `ema21Slope`, `ema50Slope`, `ema200Slope` | EMA slopes | |
| `ema21PctDiff` | `(price - ema21Val) / ema21Val * 100` | signed % distance from EMA21 |
| `rsiVal` | RSI(14) last value | |
| `rsiToday` | latest RSI (for flip detection) | |
| `rsiBuyFlip` | RSI just turned up while oversold | `rsi[-1] > rsi[-2]` and in oversold zone |
| `macdSlope` | slope of MACD line | |
| `macdSlopeAngle` | `atan(macdSlope)` in **degrees** | signed MACD angle; see below |
| `macdAccel` | 2nd difference of MACD (acceleration) | |
| `macdPctl` | percentile of current MACD vs its trailing ~1Y history (0–100) | 0 = historic low, 100 = historic high |
| `macdPhase` | `"BULLISH"` if MACD > signal else `"BEARISH"` | |
| `macdZeroCrossUp` | MACD crossed from <0 to >0 on last bar | |
| `slopeCrossUp` | MACD slope crossed from <0 to >0 | |
| `earlyBuy` | early-reversal heuristic (MACD hooking up before cross) | app uses trough-detection; a reasonable proxy: `macdAccel>0 and macdSlope>0 and macd<0` |
| `belowMidBand` | close < Bollinger mid band | |
| `touchedLowerBand` | low touched/pierced lower band recently | |
| `currAdx` | ADX last value | |
| `bullishTrend` | `+DI > -DI` (uptrend direction) | |
| `obvCurrent`, `obv5`, `obv20` | OBV and its 5/20 moving averages | |

**MACD angle.** `macdSlopeAngle = degrees(atan(macdSlope))`. If your MACD values are tiny, scale
consistently — the app treats the angle as `atan(d(MACD)/dt)` where dt = 1 bar. The angle only
feeds the "qualified setup" gate (below), which clamps to `[-89°, 89°]`.

**Percentile of MACD (`macdPctl`).** Over the trailing ~252 bars: `100 * (count of past MACD values <= current) / N`.

If any input is unavailable, treat the corresponding bucket as scoring **0** (never crash).

---

## 1. PULLBACK SCORE

"Buy the dip inside an uptrend." Sum of 7 buckets. Recommended max ≈ 118.

```
pullbackScore = smaPts + (macdInfPts + goldenBonus + pctlBonus)
              + (rsiPts + rsiBuyFlipPts) + bbPts + adxPts + obvPts + emaPts
```

### 1.1 SMA trend — `smaPts`
```
smaPass = sma200Val is not None and price > sma200Val
smaPts  = 25 if smaPass else 0          # buySma200Pts = 25
```

### 1.2 MACD inflection — `macdInfPts` (+ bonuses)
First define the **qualified setup ("goldenBuy")** gate:
```
setupMinAngle    = -45.0            # from minSlopeMagnitude (=-45), clamped
goldenBuyMaxAngle = 85.0            # from goldenBuyMaxSlope (=85), clamped
setupSmaMinSlope = 0.1             # setupSma200MinSlope
setupMacdPctlMax = 75.0            # setupMacdPctlMax, clamped 0..100

goldenBuy = (macdPctl <= setupMacdPctlMax)
        and (macdAccel > 0)
        and (macdSlopeAngle >= setupMinAngle)
        and (macdSlopeAngle <= goldenBuyMaxAngle)
        and (sma200Slope >= setupSmaMinSlope)
```
Then MACD inflection points (mutually exclusive, first match wins):
```
if   goldenBuy:                          macdInfPts = 30   # buyGoldenBuyPts
elif macdZeroCrossUp and macdAccel > 0:  macdInfPts = 30   # buyMacdZeroCrossUpPts
elif slopeCrossUp:                       macdInfPts = 20   # buySlopeCrossUpPts
elif earlyBuy:                           macdInfPts = 10   # buyEarlyBuyPts
else:                                    macdInfPts = 0

goldenBonus = 10 if goldenBuy else 0                       # buyGoldenBonus
pctlBonus   = 3  if (macdPctl <= 25.0 and macdInfPts > 0) else 0   # buyMacdPctlBonus / threshold 25
```

### 1.3 RSI graduated + flip bonus — `rsiPts`, `rsiBuyFlipPts`
```
rsiPts = 0
if rsiVal is not None:
    r = rsiVal
    if 25.0 <= r <= 55.0:
        if r <= 35.0:  rsiPts = int(5 + 15 * (r - 25) / 10)
        else:          rsiPts = int(20 * (55 - r) / 20)     # buyRsiMaxPts = 20
    elif r < 25.0:     rsiPts = 3
    else:              rsiPts = 0
    rsiPts = clamp(rsiPts, 0, 20)

# flip bonus: RSI just turned up while in [25,45]
rsiBuyFlipPts = 8 if (rsiBuyFlip and 25.0 <= rsiToday <= 45.0) else 0   # buyRsiFlipBonus
```

### 1.4 Bollinger — `bbPts`
```
bbPass = belowMidBand or touchedLowerBand
bbPts  = (10 + (5 if touchedLowerBand else 0)) if bbPass else 0   # base 10, lower-band bonus 5
```

### 1.5 ADX (directional) — `adxPts`
```
adxStrong     = currAdx > 25.0     # buyAdxStrongThreshold
adxVeryStrong = currAdx > 30.0     # buyAdxVeryStrongThreshold
adxPts = (15 + (5 if adxVeryStrong else 0)) if (adxStrong and bullishTrend) else 0
```

### 1.6 OBV — `obvPts`
```
obvPass = obvCurrent > obv5 and obvCurrent > obv20
obvPts  = 5 if obvPass else 0
```

### 1.7 EMA(21) proximity — `emaPts`
```
emaPts = 0
if ema21Val is not None:
    d = ema21PctDiff
    if   d < -3:            emaPts = 3
    elif -3.0 <= d <= 0.0:  emaPts = 10          # buyEmaMaxPts
    elif 0.0  <  d <= 2.0:  emaPts = int(10 * 0.8)  # 8
    elif 2.0  <  d <= 4.0:  emaPts = int(10 * 0.5)  # 5
    elif 4.0  <  d <= 7.0:  emaPts = int(10 * 0.2)  # 2
    else:                   emaPts = 0
```

### 1.8 Pullback signal
```
if   pullbackScore >= 75: pullbackSignal = "STRONG PULLBACK"   # pullbackStrongThreshold
elif pullbackScore >= 60: pullbackSignal = "PULLBACK READY"    # pullbackModerateThreshold
else:                     pullbackSignal = "NO SIGNAL"
```

---

## 2. MOMENTUM SCORE

Trend-continuation + buy-and-hold quality. ~1/3 short-term confirmation, ~2/3 long-term quality.
Recommended max ≈ 103.

```
momentumScore = momentumTrendPts + momentumMacdPts + momentumEmaSlopePts
              + momentumRoomPts + momentumRsiPts + momentumObvPts
              + momentumTrendPersistencePts + momentumEmaCascadePts
              + momentumDrawdownPts + momentumLongReturnPts + momentumSharpePts
```

### 2.0 Extra long-term metrics to compute
```
def pct_slope(value, slope):  # slope as % of the level
    return (slope / value * 100.0) if (value and value > 0) else 0.0

smaSlopePct    = pct_slope(sma200Val, sma200Slope)
ema21SlopePct  = pct_slope(ema21Val, ema21Slope)
ema50SlopePct  = pct_slope(ema50Val, ema50Slope)
ema200SlopePct = pct_slope(ema200Val, ema200Slope)

# 1. Trend persistence: fraction of last 200 bars closed above SMA200 (0..1)
#    Only over bars where SMA200 is defined. 0.0 if <200 bars of history.
# 2. EMA cascade:
emaCascadeOk     = ema21Val>ema50Val>ema200Val (all not None)
emaCascadeStrong = emaCascadeOk and ema21Slope>0 and ema50Slope>0 and ema200Slope>0
# 3. Max drawdown over last min(252,n) closes, as a positive fraction (0.15 = 15%)
# 4. Returns:
return6mPct  = (price - close[n-126]) / close[n-126] * 100   if n>=126 else 0
return12mPct = (price - close[n-252]) / close[n-252] * 100   if n>=252 else 0
# 5. Sharpe-like over last min(252, n-1) daily returns (need >=30 samples):
#    mean = mean(daily_ret); std = population std (ddof=0)
#    sharpeLike = (mean * 252) / (std * sqrt(252))   if std>0 else 0
```

### 2.1 Short-term confirmation buckets

**Trend stack (`momentumTrendPts`, max 10):**
```
smaTrendOk = smaPass or (sma200Val is not None and price >= sma200Val*0.99 and sma200Slope > 0)
rawTrendStack = (30 if smaTrendOk else 0)
              + (25 if (smaSlopePct > 0 or ema200SlopePct > 0) else 0)
              + (25 if ema50SlopePct > 0 else 0)
              + (20 if ema21SlopePct > 0 else 0)          # out of 100
momentumTrendPts = round(rawTrendStack/100.0 * 10)
```

**MACD continuation (`momentumMacdPts`, max 8):**
```
if   (macdZeroCrossUp and macdAccel>0) or slopeCrossUp:  momentumMacdPts = 8
elif macdPhase=="BULLISH" and macdSlope>0:               momentumMacdPts = round(8*0.85)  # 7
elif macdSlope>0 and macdAccel>=0:                       momentumMacdPts = round(8*0.70)  # 6
elif earlyBuy and macdAccel>0:                           momentumMacdPts = round(8*0.55)  # 4
else:                                                    momentumMacdPts = 0
```

**EMA21 slope (`momentumEmaSlopePts`, max 5):**
```
if ema21Val is None:                              0
elif ema21SlopePct>0 and price>=ema21Val:         5
elif ema21SlopePct>0:                             round(5*0.60)  # 3
else:                                             0
```

**Not overextended / room (`momentumRoomPts`, max 3):** `d = ema21PctDiff`
```
if   0.0 <= d <= 4.0:    3
elif -2.0 <= d < 0.0:    round(3*0.80)  # 2
elif 4.0 < d <= 7.0:     round(3*0.45)  # 1
elif -4.0 <= d < -2.0:   round(3*0.35)  # 1
else:                    0
```

**RSI room (`momentumRsiPts`, max 3):**
```
if   45.0 <= rsiVal <= 68.0:   3
elif 35.0 <= rsiVal < 45.0:    round(3*0.60)  # 2
elif 68.0 < rsiVal <= 75.0:    round(3*0.45)  # 1
elif rsiVal < 35.0:            round(3*0.20)  # 1
else:                          0
```

**OBV (`momentumObvPts`, max 4):** `4 if obvPass else 0` (same `obvPass` as pullback).

### 2.2 Long-term quality buckets (primary drivers)

**Trend persistence (max 20):** `p = trendPersistencePct` (0..1)
```
p>=0.80 -> 20 ; p>=0.60 -> round(20*0.75)=15 ; p>=0.40 -> round(20*0.45)=9 ; p>=0.20 -> round(20*0.20)=4 ; else 0
```

**EMA cascade (max 15):**
```
emaCascadeStrong -> 15 ; emaCascadeOk -> round(15*0.60)=9 ; else 0
```

**Max drawdown (max 10):** `dd = maxDrawdownPct` (positive fraction; lower is better)
```
dd<=0.10 -> 10 ; dd<=0.20 -> round(10*0.60)=6 ; dd<=0.30 -> round(10*0.30)=3 ; else 0
```

**Long-term return (max 15):** sum of two tranches, capped at 15
```
# 6-month tranche:
r6>=25 -> round(15*0.40)=6 ; r6>=15 -> round(15*0.30)=5 ; r6>=5 -> round(15*0.20)=3 ; else 0
# 12-month tranche:
r12>=40 -> round(15*0.60)=9 ; r12>=25 -> round(15*0.45)=7 ; r12>=10 -> round(15*0.30)=5 ; else 0
momentumLongReturnPts = min(tranche6 + tranche12, 15)
```

**Sharpe-like (max 10):** `s = sharpeLike`
```
s>=1.5 -> 10 ; s>=1.0 -> round(10*0.70)=7 ; s>=0.5 -> round(10*0.40)=4 ; s>0.0 -> round(10*0.15)=2 ; else 0
```

### 2.3 Momentum signal
```
if   momentumScore >= 75: "STRONG MOMENTUM"   # momentumStrongThreshold
elif momentumScore >= 60: "MOMENTUM READY"    # momentumModerateThreshold
else:                     "NO SIGNAL"
```

---

## 3. COMPOSITE (how the two lenses combine)

```
cherryPoints = max(pullbackScore, momentumScore)

if   pullbackScore>=60 and momentumScore>=60: entryMode = "PULLBACK + MOMENTUM"
elif momentumScore>=60:                       entryMode = "MOMENTUM"
elif pullbackScore>=60:                       entryMode = "PULLBACK"
else:                                         entryMode = "NONE"

# cherrySignal (thresholds: cherryStrongThreshold=75, cherryModerateThreshold=60)
if   cherryPoints>=75 and entryMode=="MOMENTUM": "STRONG MOMENTUM"
elif cherryPoints>=75 and entryMode=="PULLBACK": "STRONG PULLBACK"
elif cherryPoints>=75:                           "STRONG ENTRY"
elif cherryPoints>=60 and entryMode=="MOMENTUM": "MOMENTUM READY"
elif cherryPoints>=60 and entryMode=="PULLBACK": "PULLBACK READY"
elif cherryPoints>=60:                           "ENTRY READY"
else:                                            "NO SIGNAL"
```

### Per-bucket "Why this score?" breakdown (optional but recommended)
The Android UI renders a colored table from a pipe-delimited trace. For each bucket emit a row:
```
<bucket name>|<status>|<points>|<max>|<reason>
```
`status = "pass"` if `pts >= max`, `"fail"` if `pts <= 0`, else `"partial"`. Since the breakdown is
generated from the same variables as the score, it can never drift. In Python, return a list of
dicts `{name, status, points, max, reason}` per bucket for both pullback and momentum.

---

## 4. VALUE SCORE (0–100) — needs Yahoo fundamentals

Requires fundamental fields (Yahoo `quoteSummary`: `trailingPE`, `forwardPE`, `priceToBook`,
`enterpriseToEbitda`, `debtToEquity`, `returnOnEquity`, `operatingCashflow`, `freeCashflow`,
`netIncome`, `totalRevenue`, `totalCash`, `totalDebt`, `currentRatio`, `revenueGrowth`,
`earningsGrowth`, `grossMargins`, `operatingMargins`, `profitMargins`, `dividendYield`,
`fiftyTwoWeekLow`, `sector`). Margins/growth are expressed as **percentages** (e.g. 10.0 = 10%).
`sectorMedianPe` is optional (pass `None` to skip the sector sub-check).

```
score = valuation + quality + growth + yield_ + discount  (then penalties)
```

**4.1 Valuation (cap 30):**
```
if pe and pe>0:
    absPts = 10 if pe<12 else 7 if pe<18 else 4 if pe<25 else 0
    valuation += absPts
    if sectorMedianPe and sectorMedianPe>0:
        valuation += 8 if pe<=sectorMedianPe*0.75 else 5 if pe<sectorMedianPe else 0
if fwdPe and pe and fwdPe>0 and pe>0 and fwdPe <= pe*0.90:  valuation += 4
if pb and pb>0:            valuation += 6 if pb<1.5 else (3 if pb<2.0 else 0)
if evEbitda and evEbitda>0: valuation += 8 if evEbitda<8 else (4 if evEbitda<12 else 0)
valuation = clamp(valuation, 0, 30)
```

**4.2 Quality (cap 30):**
```
if de is not None:      quality += 6 if de<0.3 else 4 if de<1.0 else 2 if de<2.0 else 0
if netCash>=0:          quality += 4          # netCash = totalCash - totalDebt
if currentRatio:        quality += 3 if currentRatio>=1.5 else (1 if currentRatio>=1.0 else 0)
if roePct:              quality += 6 if roePct>=15 else (3 if roePct>=10 else 0)  # roePct = returnOnEquity*100
if profitMargin:        quality += 4 if profitMargin>=10 else (2 if profitMargin>=5 else 0)
if operatingMargin:     quality += 3 if operatingMargin>=15 else (1 if operatingMargin>=8 else 0)
if cfo and ni and ni>0 and cfo>ni:  quality += 4
if fcf and fcf>0:       quality += 3
quality = clamp(quality, 0, 30)
```

**4.3 Growth (cap 18):** (`revenueGrowth`/`earningsGrowth` chosen: prefer quarterly, fall back to annual)
```
if revenueGrowth:  growth += 7 if rg>=12 else 4 if rg>=4 else (2 if rg>0 else 0)
if earningsGrowth: growth += 7 if eg>=12 else 4 if eg>=4 else (2 if eg>0 else 0)
if grossMargin and grossMargin>=35:  growth += 2
if profitMargin and profitMargin>=10 and earningsGrowth and earningsGrowth>0: growth += 2
growth = clamp(growth, 0, 18)
```

**4.4 Yield (cap 10):**
```
if divYield and divYield>0:  yield_ += 6 if divYield>=3 else (3 if divYield>=1 else 0)
# buyback hook is currently always False in the app -> +0
yield_ = clamp(yield_, 0, 10)
```

**4.5 Discount (cap 12):**
```
if low52 and low52>0 and price>0:
    pctFromLow = (price - low52)/low52 * 100
    discount += 12 if pctFromLow<15 else (5 if pctFromLow<30 else 0)
discount = clamp(discount, 0, 12)
```

**4.6 Penalties, then clamp:**
```
score = valuation + quality + growth + yield_ + discount
if revenueGrowth is not None and revenueGrowth < -5:   score -= 5
if earningsGrowth is not None and earningsGrowth < -10: score -= 7
if fcf is not None and fcf < 0:                         score -= 4
score = clamp(score, 0, 100)
```

**4.7 Rating:**
```
score>=80 -> "DEEP VALUE" ; score>=65 -> "MODERATE VALUE" ; score>=50 -> "MILD VALUE" ; else "NOT ATTRACTIVE"
```

---

## 5. AI PULLBACK ANALYSIS

Triggered when a stock is in a deep pullback. Fetches ~12 recent news headlines (Google News RSS,
most-recent-first), then calls the LLM. Returns free text ending in a verdict.

**System prompt (verbatim):**
```
You are a stock market analyst. Analyze a deep pullback critically and independently.
Form your own opinion based PRIMARILY on recent news (headlines appear in chronological order, most recent first).
Do NOT assume the pullback is automatically a buying opportunity — critically assess both risks and opportunities.

Evaluate whether this pullback is:
• TEMPORARY: A healthy correction in an uptrend; news suggests a one-off event or overreaction
• STRUCTURAL: Signs of fundamental weakness; news suggests deteriorating business/sector conditions
• UNCERTAIN: Conflicting signals; need more confirmation before committing

Prioritize recent news over older stories. Be concise (3-4 sentences max). If you're unsure, answer UNCERTAIN rather than guessing.
```

**User prompt template:**
```
Stock: {name} ({SYMBOL}) ({marketLabel})
Current price: {curr}{price:.2f}
EMA(21) distance: {ema21PctDiff:.1f}% (deep pullback trigger)
MACD Phase: {macdPhase}

─ Technical Context ─
RSI = {rsi:.0f} ({rsiStatus})
ADX = {adx:.1f} ({adxStatus})
Price is {pctAboveSupport:.1f}% above support ({curr}{support:.2f})

─ Recent News Headlines (last 30 days) ─
[1] {headline1}
[2] {headline2}
...

Analyze critically:
• Based on the news, what is the PRIMARY driver of this pullback?
• Is this a temporary market overreaction or structural weakness?
• What would need to happen for this to be a BUY vs AVOID?
• If uncertain, say UNCERTAIN rather than guessing.

Provide your verdict: TEMPORARY, STRUCTURAL, or UNCERTAIN.
```
Helper text:
- `rsiStatus`: `<30`="oversold — may bounce soon", `<50`="mid-range — pullback zone ideal",
  `<70`="near exhaustion — caution on further bounce", else "overbought — unusual for a pullback".
- `adxStatus`: `>25`="trending", `>15`="emerging trend", else "choppy".
- If no headlines are found → return error "No recent news found for {symbol}" (don't call the LLM).
- `max_tokens` ≈ 500.

---

## 6. AI STOCK OUTLOOK

Manually triggered ("Analyze Outlook"). Deliberately **minimal context**: price + ownership + news
only — no app technicals passed, to keep the LLM independent.

**Allowed verdicts depend on ownership:**
- In portfolio → `STRONG BUY / BUY / HOLD / REDUCE / SELL`
- Not owned → `STRONG BUY / BUY / WATCH / AVOID` (**HOLD forbidden**)

**System prompt (verbatim, `{analystPersona}` e.g. "US equity investors", `{allowedVerdicts}` and
`{holdRule}` substituted):**
```
You are a stock market analyst providing outlook summaries for {analystPersona}.
Form your own independent opinion based PRIMARILY on recent news and publicly known fundamentals.
Headlines are listed in chronological order (most recent first) — prioritize those heavily over older news.
Do NOT assume any prior bullish or bearish bias — you are given current price, ownership status, and headlines only. Critically assess both risks and opportunities.
Do not infer or mention app technical indicators unless they are explicitly present in the news headlines.

Give a structured response:
1. SHORT TERM (1-4 weeks): News-driven outlook
2. LONG TERM (3-12 months): Fundamental narrative from recent news and publicly known context
3. KEY RISKS: 1-2 bullet points from recent developments
4. VERDICT: One of: {allowedVerdicts}

{holdRule}

Keep each section to 2-3 sentences max. Be specific about price levels when possible.
```
`holdRule` = if owned: `"HOLD is allowed because the user currently owns this stock."`
else: `"HOLD is NOT a valid verdict — the user does NOT own this stock, so you must commit to a directional call (BUY / WATCH / AVOID). Do not output HOLD under any circumstances."`

**User prompt template:**
```
Stock: {name} ({SYMBOL}) ({marketLabel})
Current Price: {curr}{price:.2f}
User currently owns this: {YES|NO}

── Recent News Headlines (prioritize items from the last 30 days) ──
[1] {headline1}
...
(if none: "(No recent news available via Google News RSS — rely on general market knowledge of this company, and note the absence of news in your response)")

Based on the above, provide the structured outlook. Cite headline numbers inline where relevant. {if not owned: "Remember: HOLD is NOT a valid verdict here. "}Do not reference any app-generated scoring.
```
`max_tokens` ≈ 500.

**Market context values** (pick per scan universe):

| space | marketLabel | currencySymbol | analystPersona |
|---|---|---|---|
| India (NSE) | "NSE India" | ₹ | "Indian equity investors" |
| US | "US Markets" | $ | "US equity investors" |
| Germany (Xetra) | "Xetra Germany" | € | "German/European equity investors" |

(Use whatever labels fit your universes; these only affect prompt wording + currency glyph.)

---

## 7. News headline fetch (feeds §5 and §6)

Both AI features pull headlines from **Google News RSS** (no API key):
```
https://news.google.com/rss/search?q={query}&hl=en-US&gl=US&ceid=US:en
```
- Strip exchange suffixes from the ticker (`.NS`, `.BO`, `.DE`, etc.) before querying; optionally
  append the company name and a market keyword to disambiguate.
- Parse `<item>` → `<title>` + `<pubDate>`; keep items from the **last 30 days**; sort most-recent-first;
  cap at ~12.
- If zero headlines: pullback → return error; outlook → still call LLM with the "no news" note.

Your Flask app can reuse whatever HTTP client it already has; this is a plain GET + XML parse.

---

## 8. Suggested integration into your Flask app

1. In `analyze_stock()` you already compute close/high/low/vol, SMA, EMA21, RSI, Bollinger, MACD
   (+ slope/accel), OBV, ADX/DI, support/resistance/ATR. **Add:** SMA200 + slope, EMA50/EMA200 +
   slopes, EMA21 slope, `macdSlopeAngle`, `macdPctl`, OBV 5/20 MAs, and the long-term metrics in §2.0.
2. Add pure functions `pullback_score(ind) -> (score, signal, rows)` and
   `momentum_score(ind) -> (score, signal, rows)` implementing §1 and §2. Return the composite (§3).
3. Add `value_score(fundamentals, price, sector_median_pe=None) -> (score, rating, rows)` (§4);
   fetch fundamentals from Yahoo `quoteSummary` (`assetProfile`, `financialData`,
   `defaultKeyStatistics`, `summaryDetail`).
4. Add two LLM endpoints (§5, §6) reusing your existing MACD-setup/BUY-SELL scan plumbing; wire the
   Google News fetch (§7). Keep `max_tokens≈500`, temperature low.
5. Surface all three scores + signals in your results table alongside the existing BUY/SELL scores;
   expose the per-bucket `rows` in a "Why this score?" expander.

**Output JSON shape (per symbol) suggested:**
```json
{
  "symbol": "AAPL",
  "pullback": {"score": 72, "signal": "PULLBACK READY", "rows": [{"name":"SMA(200)","status":"pass","points":25,"max":25,"reason":"price > SMA200 (uptrend)"}]},
  "momentum": {"score": 81, "signal": "STRONG MOMENTUM", "rows": [ ... ]},
  "composite": {"cherryPoints": 81, "entryMode": "MOMENTUM", "signal": "STRONG MOMENTUM"},
  "value": {"score": 63, "rating": "MILD VALUE", "rows": [ ... ]},
  "ai_pullback": "…verdict text…",
  "ai_outlook": "…structured text…"
}
```

---

## 9. Exact constants reference (ScoringWeights defaults)

Pullback: SMA200=25, goldenBuy=30, goldenBonus=10, macdZeroCrossUp=30, slopeCrossUp=20, earlyBuy=10,
macdPctlBonus=3 (@≤25%ile), RSImax=20, RSIflipBonus=8 (RSI∈[25,45]), BBbase=10, BBlowerBonus=5,
ADXbase=15 (ADX>25), ADXveryStrongBonus=5 (ADX>30), OBV=5, EMAmax=10.
Thresholds: strong=75, moderate=60.

Momentum: trendStack=10, macd=8, ema21Slope=5, notOverextended=3, rsiRoom=3, obv=4,
trendPersistence=20, emaCascade=15, drawdown=10, longReturn=15, sharpe=10. Thresholds: strong=75, moderate=60.

Setup gate: minSlopeAngle=-45°, maxSlopeAngle=85°, sma200MinSlope=0.1, macdPctlMax=75.

Value: valuation cap 30, quality cap 30, growth cap 18, yield cap 10, discount cap 12; penalties
rev<-5%→-5, eps<-10%→-7, fcf<0→-4. Ratings: 80 deep / 65 moderate / 50 mild.

Composite: cherryStrong=75, cherryModerate=60.

> All `round()` calls use round-half-to-even isn't required — the app uses `Math.round` (half-up).
> Use `int(x + 0.5)` for the fractional-multiplier buckets to match exactly.
```

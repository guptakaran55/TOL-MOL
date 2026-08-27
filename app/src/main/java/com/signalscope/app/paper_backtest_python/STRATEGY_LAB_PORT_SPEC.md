# Strategy Lab Port Spec — Configurable MACD-Slope Engine + Stock Screener

## Status / relationship to the older brief

This document **supersedes Section 3 ("Exact strategy to port") of `CLAUDE_OPUS_ANDROID_PORT_BRIEF.md`** and adds the **stock screener**.

The old brief describes the first, hard-coded 15-minute strategy (`backtest.py`: ENTRY_SMOOTHING=5, EXIT_SMOOTHING=2, ADX≥20, close>SMA200, 0.85 catastrophe floor). The strategy has since evolved into a **configurable multi-timeframe engine** (`strategy_lab.py`) whose every parameter is supplied by a **config JSON** (the file the desktop app exports). This spec is the source of truth for that engine.

Everything else in the old brief still applies unchanged: **paper-only first**, never call `Trading212Client.placeOrder()`, closed-bar handling in `America/New_York`, duplicate protection via a per-symbol watermark, dedicated enrollment, durable service orchestration, golden parity tests. Re-read those sections — they are not repeated here.

**Two things to port:** (A) the **Screener** that ranks symbols and picks the basket, and (B) the **Trade Engine** that turns bars into entry/exit decisions for the enrolled symbols. Keep both pure and deterministic (no Android Context / network / broker calls), exactly like `UsPaperStrategy.kt`.

---

## 0. The configuration contract

The desktop app exports a JSON like the one below (company list intentionally omitted — the screener supplies the basket). **Every decision the engine makes is driven by these fields.** Port the engine to read this object; do not hard-code the champion values.

```jsonc
{
  "strategy": "macd",
  "resample": "60min",          // ENTRY candle timeframe
  "exitBars": "30min",          // EXIT-check timeframe ("same" = single timeframe; else multi-timeframe)
  "warmupBars": 240,            // bars skipped before any decision (on the ENTRY timeframe)
  "notional": 1000,             // fixed $ per trade
  "slippagePct": 0.05,          // subtracted from each closed trade's % return
  "macd": { "fast": 12, "slow": 26, "signal": 9 },

  "entry": {
    "mode": "ALL",              // "ALL" = every enabled core cond must hold; "ANY" = any one
    "slopeFlipUp":   { "enabled": true,  "smoothing": 2, "persistent": false },
    "slopePositive": { "enabled": false },
    "macdCrossUp":   { "enabled": false },
    "macdAboveSignal":{ "enabled": false },
    "macdAboveZero": { "enabled": false },
    "accelPositive": { "enabled": true,  "minSigma": 0.7 },
    "sustainSlope":  { "enabled": true,  "bars": 20 },                 // OR-rescue (PID integral)
    "earlyRecovery": { "enabled": true,  "pct": 50, "lookback": 22, "confirmVelocity": true }, // OR-rescue
    "velocity":      { "enabled": false, "lookback": 10, "threshold": 0.5 }
  },

  "gates": {                    // ALL enabled gates must pass (applied AFTER entry fires)
    "adx":      { "enabled": false, "min": 20, "period": 14 },
    "sma":      { "enabled": true,  "length": 50, "kind": "ema" },     // price-above-level gate
    "emaSlope": { "enabled": false, "length": 16, "lookback": 10, "min": 0.1 }
  },

  "exit": {
    "mode": "ANY",             // how the indicator-exit conditions combine
    "slopeFlipDown": { "enabled": true,  "smoothing": 2, "persistent": false },
    "fastMacdExit":  { "enabled": false },                            // MTF: run slope-flip-down on fine bars
    "slopeRetrace":  { "enabled": false, "pct": 40, "confirmVelocity": true },
    "macdCrossDown": { "enabled": false },
    "macdBelowZero": { "enabled": false },
    "accelNegative": { "enabled": false },
    "velocity":      { "enabled": false, "lookback": 10, "threshold": 0 },
    "stopLoss":      { "enabled": true,  "pct": 4 },                  // hard exit
    "takeProfit":    { "enabled": false, "pct": 5 },                  // hard exit
    "maxBars":       { "enabled": false, "bars": 100 },               // hard exit
    "macdDistance":  { "enabled": true,  "pct": 30 },                 // QUALIFIER, suppresses indicator exits
    "minHoldBars":   0,                                               // blocks indicator exits for N bars
    "session":       { "enabled": false, "flattenAtClose": true, "noEntryAfter": "15:30" }
  }
}
```

Unknown/missing fields must fall back to the defaults shown. A `*.enabled === false` block contributes nothing.

### 0.1 Android configuration model — preset + upload, NOT a full editor

Android does **not** expose a per-field tweaking UI. It supports exactly two ways to set the active config:

1. **Baked-in preset (default).** Ship the validated config below as a hard-coded default the app loads on first run / on "Reset to preset". This is the "tried-and-tested" config and is what runs unless the user uploads another.
2. **Upload a config JSON from the device.** A file picker reads a `.json` exported by the desktop app, validates it, and makes it the active config. Persist the active config (atomic JSON store) so it survives restart. Show a **read-only summary** of the active config (entry/exit conditions in plain words + key numbers) and which source it came from (`preset` vs `filename`) — no editable fields.

**Validation on upload:** require `strategy === "macd"` and the presence of `entry` and `exit` objects; otherwise reject with a clear message and keep the previous config. Apply the same field defaults as §0.

**Fields Android MUST IGNORE from the JSON** (they're desktop-backtest-only and have no meaning live):
- `dataset` — Android trades live bars of the *enrolled* symbols, not a backtest folder.
- `symbols` — the basket comes from enrollment (see §3.5), never from the config file.
- `vwap`, `orb` — other strategies; this engine is `macd` only.
- `_meta` — informational (the backtest's results), display-only if you want, never executed.

**Fields Android READS:** `resample`, `exitBars`, `warmupBars`, `notional`, `slippagePct`, `macd.*`, `entry.*`, `gates.*`, `exit.*`.

> **Baked-in preset to ship** (the config the user validated; `dataset`/`vwap`/`orb`/`_meta` stripped since Android ignores them):
> ```jsonc
> { "strategy":"macd", "resample":"60min", "exitBars":"30min", "warmupBars":240,
>   "notional":1000, "slippagePct":0.05, "macd":{"fast":12,"slow":26,"signal":9},
>   "entry":{ "mode":"ALL",
>     "slopeFlipUp":{"enabled":true,"smoothing":2,"persistent":false},
>     "accelPositive":{"enabled":true,"minSigma":1},
>     "sustainSlope":{"enabled":true,"bars":20},
>     "earlyRecovery":{"enabled":true,"pct":50,"lookback":22,"confirmVelocity":true} },
>   "gates":{ "sma":{"enabled":true,"length":50,"kind":"ema"} },
>   "exit":{ "mode":"ANY",
>     "slopeFlipDown":{"enabled":true,"smoothing":2,"persistent":false},
>     "stopLoss":{"enabled":true,"pct":4},
>     "macdDistance":{"enabled":true,"pct":30},
>     "minHoldBars":0 } }
> ```

**Timeframe / warmup feasibility note:** this preset uses `resample=60min` (entry) and `exitBars=30min` (exit), but the Android data layer fetches **15-minute** Yahoo candles. Derive the entry/exit candles by resampling the 15-min bars (§1). `warmupBars=240` is **240 entry (60-min) candles ≈ 960 fifteen-min bars** — with Yahoo's 60-day window that's enough for 24/7 crypto but tight for RTH equities; the bounded local candle cache (≥300–1000 finalized bars, per the brief) must hold enough fine bars to form 240 entry candles before trading. If history is insufficient, make no decision (don't trade on a half-formed warmup).

---

## 1. Data prep (must match exactly)

1. **Bars** are regular-session OHLCV, ascending by time, de-duplicated, malformed prices dropped. Times in `America/New_York`.
2. **Resampling.** Aggregate fine bars to a timeframe with **left label, left-closed** windows:
   `open=first, high=max, low=min, close=last, volume=sum`; drop windows with no data. (pandas `resample(rule, label="left", closed="left")`.)
   - **ENTRY timeframe** = `resample` (e.g. `60min`).
   - **EXIT timeframe** = `exitBars` (e.g. `30min`). If `exitBars == "same"`, it's single-timeframe (entries and exits on the same candles).
3. **Warmup.** Compute indicators on the full series, but make **no decision** until index ≥ `warmupBars` on the entry timeframe.
4. **EMA helper** (used everywhere): `EMA(x, span) = x.ewm(span=max(2,span), adjust=false).mean()`, i.e. recursive `e[i] = a*x[i] + (1-a)*e[i-1]`, `a = 2/(span+1)`, seeded with `e[0]=x[0]`.

---

## 2. Indicators (computed on the ENTRY-timeframe candles)

For closes `C` (let `D(x)[i] = x[i]-x[i-1]`, with `D(x)[0]=0`):

```text
MACD        = EMA(C, fast) - EMA(C, slow)                 # fast=12, slow=26
MACD_SIGNAL = EMA(MACD, signal)                           # signal=9
RAW_SLOPE   = D(MACD)                                     # first value 0

ENTRY_SLOPE = EMA(RAW_SLOPE, entry.slopeFlipUp.smoothing)     # default span 2
EXIT_SLOPE  = EMA(RAW_SLOPE, exit.slopeFlipDown.smoothing)    # default span 2
ENTRY_ACCEL = D(ENTRY_SLOPE)                              # d²MACD/dt²  (first value 0)
EXIT_ACCEL  = D(EXIT_SLOPE)

ACCEL_SIGMA = rolling std (population? -> pandas .std default = sample, ddof=1)
              of ENTRY_ACCEL over a 200-window, min_periods=50  (NaN until 50 samples)

# PID integral: consecutive count of bars where ENTRY_SLOPE >= 0, reset to 0 when it goes negative.
ENTRY_SLOPE_RUNLEN[i] = (ENTRY_SLOPE[i] >= 0) ? ENTRY_SLOPE_RUNLEN[i-1] + 1 : 0

# Rolling trough for early recovery:
ENTRY_SLOPE_TROUGH[i] = min(ENTRY_SLOPE over last `entry.earlyRecovery.lookback` bars, min_periods=2)

# EMA-slope gate input (note: its own length/lookback, independent of MACD):
EMA_G        = EMA(C, gates.emaSlope.length)              # default length 16
EMA_SLOPE_CHG[i] = (EMA_G[i] / EMA_G[i - gates.emaSlope.lookback] - 1) * 100   # percent

# Price-level gate input:
LEVEL = (gates.sma.kind == "ema") ? EMA(C, gates.sma.length) : rollingMean(C, gates.sma.length)   # "sma" field name, but champion uses EMA50

# Velocity (percent change over N bars):
ENTRY_VELOCITY[i] = (C[i]/C[i-entry.velocity.lookback] - 1) * 100
EXIT_VELOCITY[i]  = (C[i]/C[i-exit.velocity.lookback]  - 1) * 100
```

**ADX (Wilder)** — *parity-critical, same warning as the old brief:*
```text
upMove   = high[i]-high[i-1];  downMove = low[i-1]-low[i]
plusDM   = (upMove>downMove && upMove>0)   ? upMove   : 0
minusDM  = (downMove>upMove && downMove>0) ? downMove : 0
TR       = max(high-low, |high-prevClose|, |low-prevClose|)
alpha    = 1/period                                        # Wilder, NOT 2/(period+1)
ATR      = EWM(TR, alpha)
plusDI   = 100 * EWM(plusDM, alpha)/ATR
minusDI  = 100 * EWM(minusDM, alpha)/ATR
DX       = |plusDI-minusDI| / (plusDI+minusDI) * 100       # 0 if denom 0
ADX      = EWM(DX, alpha), NaN→0
```
`EWM(x, alpha) = x.ewm(alpha=alpha, adjust=false).mean()`.

> Note: `ENTRY_ACCEL`, `ACCEL_SIGMA`, velocities, trough, runlen, EMA_SLOPE_CHG, LEVEL, ADX are **always computed** even when their feature is off. A subtle but real consequence: `entry.velocity.lookback` feeds `ENTRY_VELOCITY`, which is *also* read by early-recovery's `confirmVelocity` — so that lookback matters whenever early-recovery confirmation is on, even if the velocity entry condition is disabled.

---

## 3. The Screener (symbol selection)

Ranks the enrolled/candidate universe so you trade the names this momentum strategy actually earns on. Computed on **daily** bars.

### 3.1 Per-symbol daily series (compute once, cache)
For each symbol: resample its bars to **1 day** (`1D`, same left-closed agg), take the daily `close` series (drop NaN; require ≥ 20 daily bars). Compute `SMA50_full = rollingMean(close, 50, min_periods=10)` over the **whole** history (so a short lookback window has no warmup gap).

### 3.2 Traits over a lookback window
`lookback_days` comes from the UI ("Full history"=0, "1 month"=30, "3 months"=90, "6 months"=180, "12 months"=365). Slice to the **most recent** N calendar days ending at the last bar (`mask = date >= lastDate - lookback_days`); if `lookback_days==0`, use the whole series. On the sliced closes `c` (and the aligned slice `s` of `SMA50_full`):

```text
ret   = (c[last]/c[first] - 1) * 100                    # trailing return %
dret  = daily simple returns of c
vol   = stdev(dret) * sqrt(252) * 100                   # annualized volatility %
above = mean( c > s , ignoring NaN in s ) * 100         # % of days close was above its SMA50
```
Require ≥ 5 points in the window or skip the symbol.

### 3.3 Percentile → weighted score → rank
Across all surviving symbols, convert each trait to a **percentile rank** `0..100` (lowest value = 0, highest = 100; ties by sorted index; single symbol → 50):
```text
pRet, pVol, pAbove = percentileRank(ret), percentileRank(vol), percentileRank(above)
```
Apply tunable weights (UI defaults = the measured correlations with realized PnL: return 0.72, volatility 0.57, %>SMA 0.45):
```text
score = (wRet*pRet + wVol*pVol + wAbove*pAbove) / (wRet + wVol + wAbove)
```
Sort by `score` desc; the **top N** is the basket to enroll for the trade engine.

### 3.4 Forward-use rules (important)
- For **live selection**, use a **trailing** window (e.g. last 3 months) on data refreshed to *today*, and **re-screen on a schedule** (monthly) to rotate into current leaders. This is the only forward-valid use.
- "Full history" ranking is **hindsight** (it correlates with full-period PnL because both span the same window) — fine for analysis, not for picking what to trade next.
- The screener is a **selection heuristic** (a proxy that tilts toward high-volatility trending names and away from low-vol defensives), not a PnL predictor. It does not perfectly reproduce realized-PnL rank, and need not.

### 3.5 Where to run the screener — Android has no historical store (DECISION: screen off-device)

Android has no bulk historical data and screening needs **daily** bars (≥50 per symbol for the SMA50 term) across a whole candidate universe. Screening is also a **monthly** decision, not a live one. Therefore, for the first milestone:

**Recommended (Milestone 1): screen on the laptop, push the basket to Android.**
- The desktop app runs §3 (it has the data + the screener), producing a **top-N basket** (a plain list of symbols, e.g. `["MSTR","PLTR","MELI",...]`) and the **config JSON** (§0, no symbols).
- On Android, the basket **is the enrollment list** from the brief: the user enrolls those symbols (typed, or via a small "import basket" picker that reads a `["SYM", ...]` JSON), and uploads/keeps the config.
- Android then only runs the **trade engine** (§4–§6) on the enrolled symbols — the part that must be live and on-device.
- Re-screen on the laptop monthly and update the enrolled list (the validated walk-forward: top-half by trailing-window score, monthly refresh).
- **This needs almost no new Android code** beyond the brief's enrollment + the §0.1 config upload — no on-device daily-history fetch, no screener math, no bulk traffic.

**Optional (later milestone): on-device dynamic screening.**
Feasible because **daily** data is cheap (unlike the 5-min intraday the engine trades). Only worth it if you want full autonomy. Guard rails if you build it:
- Keep the candidate universe **small** (the user's watchlist, ~20–40 symbols — NOT the full Nasdaq-100).
- Fetch **daily** bars (Yahoo daily history goes back years; or Alpaca daily) for the candidates, compute §3 traits over the trailing window, rank, take top-N, and **update enrollment**.
- Run it **monthly / on-demand only**, never on the 15-min trade cadence; serialize it with the existing Yahoo scan mutex; reuse the service's wake/Wi-Fi locks; show a progress/last-screened status.
- Treat the result as *enrollment*, decoupled from the live trade loop (re-screen swaps the symbol set between cycles; never mid-position churn).

Either way, the **trade engine is identical** — only the source of the enrolled symbol list differs (laptop export vs on-device monthly screen).

---

## 4. Entry decision (on a completed ENTRY candle)

Evaluate only when a new entry candle closes and there is **no open position**. `row` = current candle, `prev` = previous. Let `cfg_on(x) = x.enabled === true`.

### 4.1 Core conditions (combined by `entry.mode`)
Collect each **enabled** core condition's boolean:
```text
slopeFlipUp:    persistent ? (ENTRY_SLOPE[i] >= 0)
                           : (ENTRY_SLOPE[i-1] < 0 && ENTRY_SLOPE[i] >= 0)
slopePositive:  ENTRY_SLOPE[i] > 0
macdCrossUp:    MACD[i-1] <= MACD_SIGNAL[i-1] && MACD[i] > MACD_SIGNAL[i]
macdAboveSignal:MACD[i] > MACD_SIGNAL[i]
macdAboveZero:  MACD[i] > 0
accelPositive:  floor = (minSigma>0 && !isNaN(ACCEL_SIGMA[i])) ? minSigma*ACCEL_SIGMA[i] : 0
                ENTRY_ACCEL[i] > floor
velocity:       ENTRY_VELOCITY[i] >= entry.velocity.threshold
```
`coreFired = (mode=="ANY") ? any(core) : all(core)`; if **no** core condition is enabled, `coreFired = false`.

### 4.2 OR-rescue paths (each ORed on top, independent of mode)
```text
sustainSlope (PID integral):  ENTRY_SLOPE[i] > 0 && ENTRY_SLOPE_RUNLEN[i] >= bars
earlyRecovery:                trough = ENTRY_SLOPE_TROUGH[i]; frac = pct/100; thr = frac*trough
                              crossed = trough < -1e-9 && ENTRY_SLOPE[i-1] < thr && ENTRY_SLOPE[i] >= thr
                              velOk   = !confirmVelocity || ENTRY_VELOCITY[i] > 0
                              earlyOk = crossed && velOk
```
(Early recovery fires while the slope is still **negative**, crossing up through `pct%` of its trough — a *different* bar than the zero-cross flip, so it can never coincide with `slopeFlipUp`'s event form. It is a rescue path, never an AND-able core condition.)

```text
entrySignal = coreFired || sustainOk || earlyOk
```
If nothing is enabled at all (no core, no sustain, no earlyRecovery), there is no signal.

### 4.3 Gates (applied only if `entrySignal` is true; ALL enabled gates must pass)
Evaluated in this order; first failure is the block reason:
```text
adx:      ADX[i] >= gates.adx.min
sma:      !isNaN(LEVEL[i]) && close[i] > LEVEL[i]          # strict >
emaSlope: !isNaN(EMA_SLOPE_CHG[i]) && EMA_SLOPE_CHG[i] >= gates.emaSlope.min
```
(`momentum` gate exists in the desktop tool but is off by default; ignore unless you port the momentum score.)

A signal that fires but fails a gate must be **audited as `BUY_SIGNAL / NO_ACTION`** (same as the old brief), not silently dropped.

### 4.4 Session entry cutoff (only if `exit.session.enabled`)
If `session.noEntryAfter` is set and the candle's local (ET) time ≥ that clock time, **block the entry** (audit as session-gated). N/A for 24/7 instruments.

### 4.5 Open the position
At the entry candle close: `entryPrice = close`, `entryMacd = MACD[i]`, `quantity = notional/entryPrice`, record `entryReason` = the conditions that fired. One open position per symbol, max.

---

## 5. Exit decision

### 5.1 Single-timeframe (`exitBars == "same"`) — checked each entry candle while in a position
Precedence (first match wins):

1. **Hard exits** (fire even during `minHoldBars`):
   ```text
   stopLoss:   close <= entryPrice * (1 - pct/100)
   takeProfit: close >= entryPrice * (1 + pct/100)
   maxBars:    barsHeld >= bars
   ```
2. If `barsHeld < minHoldBars` → **no indicator exit** this bar (stop/tp/maxBars above still applied).
3. **Indicator exits** (`indicatorExit`, below). If it returns a reason → exit.
4. **Slope-retracement** (if `slopeRetrace.enabled`, and step 3 produced nothing): track `peakSlope = max(EXIT_SLOPE since entry)`. Fire if
   `peakSlope > 1e-9 && barsHeld >= minHoldBars && EXIT_SLOPE[i] <= (pct/100)*peakSlope && EXIT_SLOPE[i] < EXIT_SLOPE[i-1]`
   (and if `confirmVelocity`, also `EXIT_VELOCITY[i] <= 0`).
5. **Session flatten** (if `session.enabled && session.flattenAtClose`, nothing else fired, and this is the **last bar of the trading day**) → exit "no overnight".

**`indicatorExit`** (combined by `exit.mode`; `persistent = exit.slopeFlipDown.persistent`):
```text
slopeFlipDown: (mode=="ALL" || persistent) ? EXIT_SLOPE[i] <= 0                       # state ("armed")
                                           : EXIT_SLOPE[i-1] > 0 && EXIT_SLOPE[i] <= 0 # one-bar flip event
macdCrossDown: (mode=="ALL") ? MACD[i] < MACD_SIGNAL[i]
                             : MACD[i-1] >= MACD_SIGNAL[i-1] && MACD[i] < MACD_SIGNAL[i]
macdBelowZero: MACD[i] < 0
accelNegative: EXIT_ACCEL[i] < 0
velocity:      EXIT_VELOCITY[i] <= exit.velocity.threshold        # included here in single-timeframe
fired = (mode=="ALL") ? all(enabled) : any(enabled)
if !fired -> no exit
# MACD-distance QUALIFIER (suppresses the indicator exit until price has travelled):
if macdDistance.enabled && |entryMacd| > 1e-12:
    moved = |MACD[i] - entryMacd| / |entryMacd| * 100
    if moved < macdDistance.pct -> NO exit (suppressed)
```

### 5.2 Multi-timeframe (`exitBars != "same"`) — entries on entry candles, exits on **fine** (exit-timeframe) bars
Iterate fine bars. Map each fine bar to the entry candle that has completed (the most recent entry-candle whose end ≤ this fine bar's end). Entries are still decided **only when a new entry candle completes** (Section 4) and execute at that fine bar's close. While in a position, on **every fine bar** evaluate, in precedence order:

```text
1. stopLoss   (fine close <= entryPrice*(1-pct/100))            "stop loss -P% (fine bar)"
2. takeProfit (fine close >= entryPrice*(1+pct/100))
3. maxBars    (i - entryFineIndex >= bars)  "max holding N fine bars"
4. momentumExit (if enabled)
5. if (i - entryFineIndex) < minHoldBars  -> block indicator exits
6. fastMacdExit (only if exit.fastMacdExit.enabled && slopeFlipDown.enabled):
      compute FINE MACD = EMA(fineClose,fast)-EMA(fineClose,slow),
              FEXIT_SLOPE = EMA(D(FINE_MACD), slopeFlipDown.smoothing)
      trigger = persistent ? FEXIT_SLOPE[i] <= 0 : (FEXIT_SLOPE[i-1] > 0 && FEXIT_SLOPE[i] <= 0)
      qualifier: if macdDistance.enabled, measure moved on the FINE MACD vs the FINE MACD at entry
7. slopeRetrace (only on a new entry candle; uses entry-candle EXIT_SLOPE peak; distance on entry-candle MACD)
8. velocity (fine-bar EXIT_VELOCITY <= threshold; distance on entry-candle MACD)
9. else, on a new entry candle: indicatorExit(entry-candle row, include_velocity=false,
      include_slope = !fastMacdExit)   # i.e. if fast exit is on, the slow slope-flip is handled by step 6
10. session flatten on the last fine bar of the day (if enabled)
```
The champion (`exitBars=15min`, `fastMacdExit=false`) therefore: stop/tp/maxBars on every 15-min bar, and the **slope-flip-down + MACD-distance** exit evaluated when each 60-min entry candle completes.

---

## 6. Trade accounting (paper ledger)

```text
quantity   = notional / entryPrice
grossPct   = (exitPrice - entryPrice) / entryPrice * 100
netPct     = grossPct - slippagePct           # slippage applied once, per closed trade
pnl        = notional * netPct / 100          # == (exitPrice-entryPrice)*quantity, minus slippage
```
Per-symbol compounding (optional analytics): start at 1.0, `equity *= (1 + netPct/100)` over that symbol's closed trades in time order; `compounded% = (equity-1)*100`. **No** commissions/spread/extra stops — parity depends on this.

For the Android paper engine, apply the brief's lifecycle rules unchanged: one open position per symbol, persist `lastProcessedClosedBarTimestamp`, idempotent decision id (`symbol + barTs + action`), evaluate only fully-closed bars, bootstrap to the latest eligible bar (don't replay history into the live ledger).

---

## 7. Champion default (the validated config)

If you want a known-good starting config to hard-default the UI to (net of 0.05% slippage, validated on 4 years of Nasdaq-100):

```text
resample 60min · exitBars 15min · warmup 240 · notional 1000 · slippage 0.05 · MACD 12/26/9
entry mode ALL: slopeFlipUp(smoothing 2) + accelPositive(minSigma 1) + sustainSlope(20)   [earlyRecovery OFF]
gates: sma kind=ema length=50   [adx/emaSlope OFF]
exit mode ANY: slopeFlipDown(smoothing 2) + stopLoss 4% + macdDistance 30%   [fast exit/retrace OFF]
```
The user's exported config may differ (it's the contract); honor whatever JSON is fed in. The engine must be config-driven, not hard-coded.

---

## 8. Parity tests (same bar as the old brief)

Build a fixed OHLCV fixture; run it through the **desktop `strategy_lab.py`** (set the same config) and the Kotlin port. Compare to tolerance `1e-6` where practical:

- Indicators: MACD, MACD_SIGNAL, ENTRY_SLOPE, EXIT_SLOPE, ENTRY_ACCEL, ACCEL_SIGMA, LEVEL (EMA50), ADX, EMA_SLOPE_CHG, velocities.
- Screener: for a fixed dataset + lookback + weights, the full ranked list (symbol, ret, vol, above, percentiles, score) and the top-N basket.
- Engine: every signal timestamp, action, entry/exit price, exit reason string, net %, and $1,000-notional PnL — for both single-timeframe and the 60m/15m multi-timeframe path.

State-machine cases to cover (superset of the old brief): ALL vs ANY core combination; each OR-rescue firing alone; persistent ("armed") vs event flip on both entry and exit; the MACD-distance qualifier suppressing then later allowing an exit; min-hold blocking indicator exits while the stop still fires; slope-retracement; session flatten + no-late-entry; multi-timeframe exit precedence; one-position-per-symbol; no same-bar re-entry; duplicate-bar idempotency; restart persistence.

---

## 9. Hard safety boundary (unchanged)

Paper/simulation only for the first milestone. The engine emits **decisions**; a physically separate, explicitly-enabled adapter would be required for any future live execution. Never call `Trading212Client.placeOrder()` / `cancelOrder()` or any Zerodha order path from the strategy or paper engine. Keep strategy math out of `dashboard.html`. Label the feature `PAPER`.

---

## 10. Coverage — what this spec does and does NOT specify

**Fully specified here (self-contained, no external doc needed):**
- §0/§0.1 config contract + Android preset/upload model and the exact fields read vs ignored.
- §1 data prep (resample rule, EMA definition, warmup).
- §2 every indicator formula, including Wilder ADX.
- §3 the screener (daily traits → percentile → weighted score → top-N) and §3.5 where to run it.
- §4 entry (ALL/ANY core, the two OR-rescues, gates, session cutoff, open).
- §5 exit (single-timeframe and the multi-timeframe fine-bar path, indicator-exit, MACD-distance qualifier, retrace, fast-exit, session flatten).
- §6 accounting; §7 the preset; §8 parity tests.

**Relied upon from `CLAUDE_OPUS_ANDROID_PORT_BRIEF.md` (NOT repeated here):** the Android architecture and integration points (`ScanService`, `YahooFinanceClient`, stores, `DashboardBridge`, `MainActivity`), fully-closed-bar handling in `America/New_York`, the per-symbol `lastProcessedClosedBarTimestamp` watermark + idempotent decision IDs, dedicated symbol enrollment, durable service orchestration, the persistence/audit JSON model, the US dashboard panel, and the paper-only enforcement/tests. If the implementing session has that brief in context, this spec completes the picture. If not, read the brief first.

**Intentionally OUT OF SCOPE for this port (these toggles are OFF in the validated preset and are NOT implemented here):**
- `trendFilter` (previous-day 1-day regression-slope filter) — *not specified*. 
- `gates.momentum` and `exit.momentumExit` (the ~11-bucket SignalScope daily momentum score) — *not specified*.
- `session` (flatten-at-close / no-late-entry) — *described in §4.4/§5, but off in the preset*; it's only meaningful for instruments with a daily close (irrelevant for 24/7 crypto).

**Required guard for config upload:** when a user uploads a config (§0.1), if any unsupported feature is enabled (`trendFilter.mode != "off"`, `gates.momentum.enabled`, `exit.momentumExit.enabled`), the safe behavior is to **reject the upload with a clear message** (or explicitly ignore those toggles and warn) — never silently run as if they were active, since that would break parity with the desktop backtest the config came from.

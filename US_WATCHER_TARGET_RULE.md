# US Watcher target rule — "just under the last monthly peak" (for simulation)

**Status:** live on the phone since 2026-10-19 23:24 (rule id `us-monthpeak-v1`).
**Scope:** real Trading 212 positions in the **US** space only. Germany and India are unchanged.
**Ask:** validate or refute this rule against the old one and against simple alternatives, with the
controls in §8, and report a verdict in the usual `RESULTS_MATRIX_*.md` style.

---

## TL;DR

| | Old rule (`range-v1`, still used in DE) | New US rule (`us-monthpeak-v1`) |
|---|---|---|
| US **algo** buys | range target (usually none: momentum buys sit near the month high) | **no target** — algo sells on its own exits |
| US **Watcher** buys | week high / month high − 10% of range, ≥ 1% above fill | **last standing monthly peak close − a month's ATR buffer**, ≥ **5%** above fill |
| Fallback | — | stock above every peak of the past year → projected monthly zone bottom; else none |
| Minimum distance | 1% US & IN, 0.5% DE | **5% US**, 1% IN, 0.5% DE |

Why: every US round trip on Trading 212 pays a 0.15% currency conversion on each leg. Short
targets (the week's high, +1–2% month targets) booked little more than the charges.

---

## 1. Context — what a "target" is in the app

- When a position is bought on Trading 212, the app works out a **target price once, at the buy**, from
  prices that had closed **before** the buy, and rests it at the broker as a **GTC limit sell** for
  exactly that position's shares. It is **never rolled** afterwards.
- Trading 212 reserves shares for every resting sell, so **only one sell can rest** per position. The
  **stop is watched by the app**, not rested at the broker. Every app sale cancels the resting target first.
- A target fills at its limit or better (a gap open above it fills at the open).
- The user can override with their own price ("your own price") or remove it on the card.

### Who buys US positions

| Buyer | Entry | Exit (before this change) |
|---|---|---|
| **Algo** "Us conservative" (live) | B-SLOW 240/120 champion file (it actually runs the *aggressive* file, accel σ 0.5) on the 3b daily-MACD gate | the config's exits (120-min slope flip down, 4% stop, MACD-distance 30%) + range target |
| **Watcher** (manual namespace) | the user's tap on a Watcher alert. Two kinds: (a) an entry signal from the Watcher config (B-SLOW 240/120 **conservative**) on a name the gate ranked but had no seat for; (b) a **forced entry** ("no signal, bought on your call"), typically after a 1-week / 1-month **low** alert | the Watcher config's exits + a **trailing stop of 1.2 Bollinger half-widths** (20-day, from the peak since the buy) + range target |

Typical Watcher position size is small (e.g. $50).

---

## 2. Old rule — `range-v1` (unchanged for Germany; India's Zerodha upper leg uses the same with 1%)

From **intraday** bars that had fully closed before the buy:

- week window = last 5 trading days, month window = last 21 trading days (needs ≥ 4 / ≥ 15 days).
- `pos = (paid − low) / (high − low)` per window. "Near the low" = `pos ≤ 0.10`.
- month target = `floor_cent(monthHigh − 0.10 × (monthHigh − monthLow))`; week target = `floor_cent(weekHigh)`.
- Order tried:
  - no month window → week target
  - near the month's low → month target
  - near the week's low → week target, then month target
  - near neither → month target
- First candidate ≥ `minGap` above the fill wins; otherwise no target.
- Position held from before the rule existed and already at/above the target → no target (left to the user).

---

## 3. New US rule — `us-monthpeak-v1` (exact)

```
if buyer is an algo:            no target ("sells on its own rules, as it was tested")
if buyer is the Watcher:
    bars   = DAILY bars with date < buy date (New York calendar)       # see data notes
    buf    = 0.1 × ATR14(bars) × √21                                   # "a month's buffer"
    peaks  = standing_monthly_peaks(bars)  with close > paid
    for p in peaks sorted by close ascending:                          # nearest resistance first
        t = floor_cent(p.close − buf)
        if t ≥ paid × 1.05:  return t   (kind "monthpeak")
    if peaks is empty:                                                 # at new highs
        z = projected_month_zone_bottom(bars)
        if z ≥ paid × 1.05:  return z   (kind "monthzone")
    return none
if held before the rule and price now ≥ target: none
```

**Standing monthly peak** (closes, as a line chart draws them):
- index `i` within the last **252** daily bars, `i ≥ 21`, and at least **5 bars old** (`i ≤ n − 6`);
- `close[i] ≥ every close of the 21 bars before it` (the highest close of the month before it);
- `close[i] ≥ every close after it`, up to the buy (**never closed above since**). A peak the price later
  rose through counts as support, not resistance.
- Because each standing peak is above everything after it, the newest standing peak is the lowest.

**ATR14** = simple mean of the last 14 true ranges, `TR = max(H−L, |H−prevC|, |L−prevC|)`.

**Projected monthly zone bottom** (fallback only), from `Indicators.projectWaveRange`, which is the
campaign algo's monthly level:
```
ema   = EMA50 of closes, seeded with the first close, α = 2/51
slope = (ema[n−1] − ema[n−11]) / 10
σ     = population std of (close − ema) over the last 50 bars
ceil(a) = round_cent(ema[n−1] + slope·a + 2σ)            # needs ≥ 60 closes
zone_bottom = floor_cent(max(ceil(21), ceil(5)) − buf)
```

`floor_cent(x) = floor(x·100 + 1e−9) / 100`, which rounds down so the target is never above a printed price.

**Data notes**
- The daily bars are Yahoo's adjusted series (range 2y, events=div,splits), with high and low scaled by
  the same factor as the close. On a dividend payer, an adjusted peak can sit slightly under the raw peak.
  That makes the target conservative.
- Bars dated on/after the buy day are excluded, so a buy during a session never sees that session.

---

## 4. Worked examples — use as fixtures (decided 2026-10-19)

| Stock | Paid | Standing peaks above (date, close) | Buffer | Target | Gap |
|---|---|---|---|---|---|
| ABNB | 174.63 | 2026-08-25, 190.50 | 2.10 (ATR 4.59) | **188.39** | +7.9% |
| PYPL | 56.31 | 2026-08-20, 62.30 | 0.52 | **61.77** | +9.7% |
| PAYX | 109.92 | 2026-08-31, 127.34 | 1.02 | **126.31** | +14.9% |

These targets were reproduced with the reference code in §9 on Yahoo's daily data to 2026-10-16. The
buffer column is rounded, but the target is computed unrounded and floored to the cent.

- **ABNB:** the user's own reading of the 1-year chart was "around 188". The trend projection alone
  would have given 195.08, above the peak. That is why the peak, not the projection, is primary.
- **ABNB in practice:** the open ABNB position was bought by the algo, so it gets **no** target under this rule.
- **Existing positions:** those bought before the switch kept their old-rule targets: PYPL 57.20 (+1.6%) and PAYX 114.73 (+4.4%).

---

## 5. Cost and execution model to use

| Item | US (Trading 212, EUR account) | DE | IN (Zerodha) |
|---|---|---|---|
| Broker / FX | 0.15% FX on the buy **and** on the sale | 0 | STT, stamp, exchange, SEBI, GST, ~₹13.5–16 DP per sale |
| Spread estimate (per market execution) | 0.03% | 0.10% | 0.05% |
| Round trip used so far | **0.36%** (conservative: charges spread on the limit exit too) | 0 | — |

- **Exits:** the target is a limit and fills at the limit or the open, whichever is better.
- **Stop:** app-watched, at the market. Model it as filling at its level, or at the open if the open gaps through.
- **Same bar touches both:** take the **stop first** (pessimistic).
- **Positions:** many Watcher positions are small, so the per-position fixed costs (India) matter.
  US costs are percentage-only.

---

## 6. Evidence so far

### 6a. Phone, real closed US trades (Trading 212, FIFO-matched fills, to 2026-10-19)

- **53 closed:** 5 sold at a resting target (avg **+2.69%**, 5/5 won, 5.8 days); 48 sold another way
  (avg +1.64%, median +0.96%, 26/48 won, 17.6 days).
- **Charges** took **25% of the gross gain**.
- **The two smallest month-rule targets**, 1.0% and 1.2% above the fill, kept **+0.70%** and **+0.95%** after charges.
- **Caveat:** "sold at a target" was matched by sale price ≈ a recorded target price, which is heuristic.

### 6b. Daily-bar event study (quick, not a portfolio backtest)

**Setup**
- **Universe:** Nasdaq-100 current members, daily bars built from the cached 5-min Alpaca sets.
  Two eras: 2022-06→2026-06 (101 names) and 2016→2021 (94 names).
- **Entry:** at the close on a day the close sits in the bottom 10% of the 21-day range ("month low"),
  or else of the 5-day range ("week low"). At most one entry per name per cohort per 10 days.
- **Exit:** target (limit, gap-aware), 4% stop (stop first), else the close of day 20.
- **Costs:** 0.36% round trip.
- **"No target set"** when the target is under the minimum distance; that trade then runs to the stop or day 20.

**Average net result per trade, with the 5% minimum applied to every variant:**

| Bought near | Target | 2022–26 net | net/day | 2016–21 net | net/day | Fees/gross (22–26) |
|---|---|---|---|---|---|---|
| week low | week high | +0.47% | 0.046% | +1.14% | 0.098% | 43% |
| week low | old month target | +0.48% | 0.045% | +1.24% | 0.104% | 43% |
| week low | projected zone bottom | +0.56% | 0.053% | +1.26% | 0.107% | 39% |
| week low | **last monthly peak (new)** | **+0.57%** | 0.055% | **+1.24%** | 0.106% | 39% |
| week low | fixed +11% | +0.53% | 0.050% | +1.13% | 0.096% | 40% |
| week low | no target | +0.96% | 0.076% | +1.38% | 0.101% | 27% |
| month low | old month target | +0.34% | 0.032% | +1.08% | 0.097% | 52% |
| month low | projected zone bottom | +0.38% | 0.036% | +1.09% | 0.095% | 48% |
| month low | **last monthly peak (new)** | +0.30% | 0.028% | +1.00% | 0.089% | 55% |
| month low | fixed +16% | +0.48% | 0.043% | +1.13% | 0.093% | 43% |
| month low | no target | +0.59% | 0.048% | +1.36% | 0.106% | 38% |

With the old **1%** minimum, the week-high target kept only **+0.24%** (2022–26) and **+0.75%** (2016–21) on
week-low buys, and fees took 60% / 32% of the gain. Targets of +3% kept **−0.25% / +0.31%**.

**Read so far**
- The 5% minimum is the bigger part of the improvement.
- The peak level performs **level with the projection and with a fixed target at the same distance**.
  It is not obviously special, which matches `RESULTS_MATRIX_support_buy.md` and `RESULTS_MATRIX_channel_swing.md`:
  levels did not beat phantom-shifted levels.
- "No target" scores best per trade, but this universe is survivors that drifted up, which flatters
  holding longer. Treat that as an upper bound, not a recommendation.
- The median trade hit the 4% stop in most rows. The entry, not the target, is the weak part.

**Limits of 6b**
- Daily bars; a flat 4% stop instead of the Watcher's 1.2-BB trailing stop.
- Entries are near-low proxies, not the Watcher's real entries.
- A 20-day limit instead of the config's own exits; no portfolio or seat constraints.
- One universe, with survivorship.

---

## 7. Where it applies

- **US Watcher buys bought from 2026-10-19 23:24 on:** this rule.
- **US algo buys:** none. The champion configs were validated with take-profit off.
- **Germany:** `range-v1` with 0.5% minimum (algo and Watcher). **India:** `range-v1` with 1% minimum on the Zerodha upper leg.
- **Manual "your own price" targets:** always win.

---

## 8. What to test

Priority order. Report each against **no target** and the **old rule**.

1. **Faithful Watcher simulation (US).**
   - Entries: (a) B-SLOW 240/120 conservative entry signals on gate-ranked names without a seat;
     (b) 1-week / 1-month low alerts as a forced-entry proxy.
   - Exits: the config's own exits + 1.2-BB trailing stop (20-day Bollinger, from the peak since the buy) + the target.
   - US costs as in §5. Eras: Nasdaq 2016–21, 2022–26; S&P-ex-NDX 2022–26 as the out-of-universe check.
2. **Controls.**
   - **Phantom peaks:** shift every standing peak by a random ±5–15% and re-run (3+ draws).
     If phantoms do as well, the level carries no information and a fixed distance is equivalent.
   - **Random-entry control:** same exits, entries moved to random days of the same name (±6 months).
   - **Survivorship:** report against buy-and-hold of the same names over the same holding windows.
3. **Sensitivity**, one knob at a time:
   - minimum distance 3 / 5 / 8%
   - buffer 0.05 / 0.1 / 0.2 ATR × √21
   - peak window 10 / 21 / 42 bars
   - lookback 126 / 252 / 504 bars
   - peak level from **closes vs intraday highs**
   - "standing" filter on vs off
   - fallback projection vs none
4. **Algo buys:** does any target (this rule, fixed %, none) help the B-SLOW conservative/aggressive
   champions on the US universe? Today they get none. Confirm that is right.
5. **Germany at 0 fees:** would this rule (with a lower minimum, e.g. 2%) beat `range-v1` for DE Watcher
   buys? Germany was deliberately left unchanged.

**Metrics:**
- per trade: average and median net
- net per day held
- hit rate
- fees as a share of the gross gain
- portfolio level: seats, compounding, CAGR, max drawdown, P/DD
- the share of results from the top 1% of trades

**Adopt / keep criteria:**
- the rule beats the old one on net per day after costs in ≥ 2 of 3 data sets, **and**
- it does not lose to phantom peaks by more than noise.

Otherwise recommend the simpler equivalent: a fixed minimum distance or no target.

---

## 9. Reference implementation (Python, mirrors the app)

```python
import math

def floor_cent(x): return math.floor(x * 100 + 1e-9) / 100

def atr14(H, L, C):
    tr = [max(H[i]-L[i], abs(H[i]-C[i-1]), abs(L[i]-C[i-1])) for i in range(len(C)-14, len(C))]
    return sum(tr) / 14

def standing_peaks(C, lookback=252, window=21, min_age=5):
    n, out, max_after = len(C), [], float('-inf')
    for i in range(n-1, max(window, n-lookback)-1, -1):
        if i <= n-1-min_age and C[i] >= max_after and all(C[k] <= C[i] for k in range(i-window, i)):
            out.append(i)
        max_after = max(max_after, C[i])
    return out                                   # indices, newest first

def ema(x, p=50):
    a, out = 2/(p+1), [x[0]]
    for v in x[1:]: out.append(v*a + out[-1]*(1-a))
    return out

def proj_ceiling(C, ahead):
    if len(C) < 60: return None
    e = ema(C); n = len(C)
    slope = (e[-1] - e[-11]) / 10
    r = [C[i]-e[i] for i in range(n-50, n)]; mu = sum(r)/50
    sd = math.sqrt(sum((v-mu)**2 for v in r)/50)
    return round(e[-1] + slope*ahead + 2*sd, 2)

def us_watcher_target(H, L, C, paid, min_gap=5.0):
    """H, L, C: daily bars that closed BEFORE the buy day. Returns (price, kind) or None."""
    if len(C) < 15: return None
    buf = 0.1 * atr14(H, L, C) * math.sqrt(21)
    above = sorted((C[i] for i in standing_peaks(C) if C[i] > paid))
    for close in above:
        t = floor_cent(close - buf)
        if t >= paid * (1 + min_gap/100): return t, "monthpeak"
    if not above:
        c5, c21 = proj_ceiling(C, 5), proj_ceiling(C, 21)
        if c5 and c21:
            z = floor_cent(max(c21, c5) - buf)
            if z >= paid * (1 + min_gap/100): return z, "monthzone"
    return None
```

The quick study in §6b computed this level, the old rule, the projection and fixed targets per entry,
then walked forward daily: the stop is checked first (open gap, then low), then the target (open
gap, then high), else the close on day 20.

---

## 10. Code references (Android app, `TOL-MOL`)

- `app/src/main/java/com/signalscope/app/trading/WeekTarget.kt`
  - `usPlan`, `standingPeaks`, `monthBuffer`, `monthZoneBottom`
  - `minGapFor` (US 5.0)
  - `plan`: the old `range-v1` rule
- `app/src/main/java/com/signalscope/app/trading/T212WeekTargets.kt`
  - `decide`: picks the US vs `range-v1` path; Watcher = namespace `us__manual`
  - `dailyBarsBefore`: daily bars before the buy, New York calendar
- `app/src/main/java/com/signalscope/app/trading/CampaignRules.kt`: `atr14`, `levels`
- `app/src/main/java/com/signalscope/app/util/Indicators.kt`: `projectWaveRange`
- Tests: `app/src/test/java/com/signalscope/app/trading/WeekTargetTest.kt` (24 cases, including an ABNB-shaped fixture)

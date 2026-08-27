# NSE 500 · 3B · NASDAQ champion · Zerodha flat ₹15.8 sell fee

Run 2026-08-11 in the Python lab. The question: **how much does Zerodha's flat per-sell fee cost,
and how does that change with capital per seat?**

## Setup

| | |
|---|---|
| Dataset | `nse500_kite_60m_2020-08_2026-08` — Kite Connect, 500 names, 4,563,305 bars |
| Window walked | 2020-11-11 → 2026-08-11 · 1,425 trading days · 5.75 years |
| Config | `B_SLOW_240m-120m_STOCKS-conservative` — the NASDAQ champion (lower-drawdown variant) |
| Frames | entry 240-min, exit 120-min, 240-bar MACD warmup |
| Exits active | slope-flip-down (smoothing 2), stop loss 4%, MACD-distance qualifier 30% |
| Portfolio | 3B — daily-MACD gate, flag and exit at natural close |
| Seats | 5 · slope smoothing 5 · confirm days 1 · screener lookback 90d |
| Costs | 0.20% slippage on every trade **plus** ₹15.8 flat on every sell |
| Trades | 544 closed (~95/yr, ~19 sells per seat per year) |
| Total fees | ₹8,595 regardless of seat size — that is the whole point |

## Result

Gross CAGR before the flat fee is **26.70%** in every row; only the fee changes.

| Per seat | Total invested | CAGR no fee | CAGR with fee | Cost | Max DD | Fee as % of position |
|---|---|---|---|---|---|---|
| ₹1,000 | ₹5,000 | 26.70% | **0.68%** | −26.02pp | 49.6% | 1.580% |
| ₹2,500 | ₹12,500 | 26.70% | **18.34%** | −8.36pp | 23.6% | 0.632% |
| ₹5,000 | ₹25,000 | 26.70% | 22.76% | −3.94pp | 21.6% | 0.316% |
| ₹7,500 | ₹37,500 | 26.70% | 24.14% | −2.56pp | 21.1% | 0.211% |
| ₹10,000 | ₹50,000 | 26.70% | 24.80% | −1.90pp | 20.9% | 0.158% |
| ₹25,000 | ₹1,25,000 | 26.70% | 25.96% | −0.74pp | 20.5% | 0.063% |
| ₹50,000 | ₹2,50,000 | 26.70% | 26.33% | −0.37pp | 20.3% | 0.032% |
| ₹1,00,000 | ₹5,00,000 | 26.70% | 26.52% | −0.18pp | 20.3% | 0.016% |
| ₹2,50,000 | ₹12,50,000 | 26.70% | 26.63% | −0.07pp | 20.2% | 0.006% |

`₹15.8 flat == 0.20% slippage at a ₹7,900 position; == 0.06% at ₹25,000.`

## The new failure mode: a seat can hit zero

At **₹1,000 and ₹2,500 per seat a seat was driven to zero** — 2023-07-06 and 2025-06-12
respectively. This cannot happen with percentage slippage, which always leaves something behind.
A flat fee subtracts an absolute amount, so a seat that shrinks far enough is killed outright, and
because capital compounds *inside* a seat, zero is permanent: that fifth of the portfolio never
trades again. The ₹2,500 row still shows 18.34% CAGR only because the other four seats carried it.

Max drawdown tells the same story from another angle — it inflates from ~20% to 49.6% as the seat
shrinks. That extra drawdown is not market risk, it is the fee.

## Reading

- **₹25,000 per seat (₹1.25 lakh total) is the practical floor.** Below it the fee costs more than
  a percentage point of CAGR; above it the fee is a rounding error. This matches the threshold
  estimated earlier from German data, derived independently.
- **Below ₹5,000 per seat the strategy does not work at all** — not "works worse". At ₹1,000 the
  entire 26.70% edge is consumed and a seat dies.
- **Fewer, larger seats beat more, smaller ones** under a flat fee, which pushes against
  diversification. Worth re-running the seat-count sweep with the fee applied before settling on 5.
- **The fee's cost is driven by trade frequency**, at ~19 sells per seat per year. A faster config
  (A_FAST) would multiply this; the slow champions are the right family for a flat-fee venue.

## Two caveats that limit what the 26.70% means

1. **Survivorship bias.** All 500 names are index members *today*. Names dropped from the index are
   absent, and current members are present for years when they were not in it. This flatters 3B
   specifically, because the screener's job is to rank this universe and take the top few — so
   pre-filtering to survivors inflates the ranking's apparent skill.
2. **Regime.** The walk starts 2020-11, right after the COVID crash, and covers one of the
   strongest Indian mid/smallcap bull runs on record. Treat 26.70% as a ceiling, not an expectation.

**The fee analysis itself is not affected by either.** The relationship between seat size and fee
drag is arithmetic — cost equals fee divided by position value — so the columns above hold whatever
the market does. Only the gross 26.70% is regime-dependent.

## Seat-count sweep at a fixed ₹25,000 per seat

Capital scales with seat count here (5 seats = ₹1.25L, 12 seats = ₹3L), so this answers "what does
adding another ₹25,000 seat buy me", not "how should I slice a fixed pot".

| Seats | Invested | CAGR no fee | CAGR with fee | Fee cost | Max DD | Calmar | Sharpe | Trades | Fees paid |
|---|---|---|---|---|---|---|---|---|---|
| 5 | ₹1,25,000 | 26.70% | 25.96% | 0.74pp | 20.5% | 1.27 | 1.10 | 544 | ₹8,595 |
| 6 | ₹1,50,000 | 23.14% | 22.42% | 0.71pp | 22.9% | 0.98 | 0.98 | 653 | ₹10,317 |
| 7 | ₹1,75,000 | 34.27% | 33.75% | 0.53pp | 27.1% | 1.25 | 1.18 | 757 | ₹11,961 |
| 8 | ₹2,00,000 | 29.49% | 28.74% | 0.75pp | 18.1% | **1.59** | 1.48 | 881 | ₹13,920 |
| 9 | ₹2,25,000 | 24.61% | 23.89% | 0.72pp | 22.4% | 1.07 | 1.27 | 998 | ₹15,768 |
| 10 | ₹2,50,000 | 30.50% | 29.83% | 0.67pp | 22.0% | 1.36 | 1.40 | 1,107 | ₹17,491 |
| 11 | ₹2,75,000 | 28.86% | 28.23% | 0.62pp | 22.6% | 1.25 | **1.57** | 1,222 | ₹19,308 |
| 12 | ₹3,00,000 | 26.44% | 25.77% | 0.67pp | 20.3% | 1.27 | **1.58** | 1,328 | ₹20,982 |

### Reading

- **The fee is a non-issue at this seat size — at every seat count.** Cost stays in a narrow
  0.53–0.75pp band from 5 to 12 seats. Fees grow in absolute terms (₹8,595 → ₹20,982) but so does
  capital, so the ratio is unchanged. This **corrects** the earlier note that a flat fee "pushes
  against diversification": that is only true when splitting a *fixed* pot into more, smaller seats.
  With per-seat capital held at ₹25,000, adding seats is fee-neutral.
- **The CAGR column is noise. Do not pick a seat count from it.** It runs 23.14 → 34.27 → 29.49 →
  24.61 → 30.50 with no monotonic pattern — an 11pp spread that jumps around. Start-date choice
  alone has been measured to move totals by ~50pp on comparable runs, so an 11pp non-monotonic
  spread carries no signal about which seat count is better.
- **Sharpe is the one consistent trend: 1.10 at 5 seats → 1.58 at 12.** That is the real
  diversification effect, and it is what you would expect: more seats means a smoother ride, not a
  higher return. Max drawdown does *not* improve monotonically (20.5% → 27.1% → 18.1% → 22.4%).
- **Ignore any absolute-profit comparison across these rows.** Profit rises from ₹3.46L to ₹8.20L
  mostly because invested capital rises from ₹1.25L to ₹3L. Only the CAGR column is comparable.

**Conclusion:** at ₹25,000 per seat the flat fee has stopped mattering, so seat count should be
chosen for smoothness and operational load, not return. Sharpe argues for more seats; every extra
seat is another live position to manage and another ₹25,000 of capital. 8 seats had the best Calmar
(1.59) but one sample is not enough to trust that over 5 or 10.

## Out-of-sample: all datasets × both champions, 7 seats at ₹25,000

₹1,75,000 invested. Flat ₹15.8 per sell, 0.20% slippage, 3B daily-MACD gate, smoothing 5, confirm 1.

| Window | Config | Span walked | Yrs | CAGR no fee | CAGR w/fee | Fee cost | Max DD | Calmar | Sharpe | Trades | Final |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 2015–2020 | conservative | 2015-05-04 → 2020-12-11 | 5.61 | 14.82% | **13.85%** | 0.97pp | 28.2% | 0.49 | 0.80 | 741 | ₹3,62,091 |
| 2015–2020 | aggressive | 2015-05-04 → 2020-12-11 | 5.61 | 15.60% | **14.54%** | 1.06pp | 30.1% | 0.48 | 0.70 | 770 | ₹3,74,605 |
| 2020–2026 | conservative | 2020-11-11 → 2026-08-11 | 5.75 | 34.27% | **33.75%** | 0.52pp | 27.1% | 1.25 | 1.18 | 757 | ₹9,30,545 |
| 2020–2026 | aggressive | 2020-11-11 → 2026-08-11 | 5.75 | 30.25% | **29.67%** | 0.58pp | 28.2% | 1.05 | 1.09 | 769 | ₹7,78,813 |
| 2010–2015 | conservative | 2015-05-04 → 2015-12-11 | 0.61 | −4.13% | −5.01% | 0.88pp | 9.1% | −0.55 | −0.34 | 60 | ₹1,69,638 |
| 2010–2015 | aggressive | 2015-05-04 → 2015-12-11 | 0.61 | −11.81% | −12.69% | 0.88pp | 9.1% | −1.39 | −1.00 | 65 | ₹1,61,202 |

### The regime caveat was right, and this is how big it is

**33.75% → 13.85%.** Same config, same seat count, same fee, same universe construction — a
different five-year window. The 2020–2026 result is **2.4× the 2015–2020 result**, and 2015–2020 is
the window containing the 2018–19 midcap bear market and the COVID crash, i.e. the normal mix of
conditions. Treat **~14%/yr as the honest expectation** and 34% as what a tailwind looks like.

Note what did *not* change: **max drawdown is 27–30% in both windows.** The downside is stable; only
the upside is regime-dependent. That is the worst possible shape for a planning assumption — you
cannot size positions off the good window's return and the good window's drawdown together.
Calmar collapses from 1.25 to 0.49.

### Conservative beats aggressive, consistently

| | 2015–2020 | 2020–2026 |
|---|---|---|
| CAGR | aggressive +0.69pp | **conservative +4.08pp** |
| Max DD | **conservative −1.9pp** | **conservative −1.1pp** |
| Sharpe | **conservative +0.10** | **conservative +0.09** |

Conservative wins drawdown and Sharpe in *both* windows and CAGR in one; aggressive's only win is
0.69pp of CAGR, which is inside the noise. **Use conservative for NSE.** This is the opposite of the
German/DAX result, where aggressive was the champion — the config choice does not transfer between
markets.

### The flat fee costs more when you are losing

Fee drag is ~1.0pp in 2015–2020 versus ~0.55pp in 2020–2026, on near-identical trade counts (741 vs
757). Because seats compound less in the weak window, seat capital stays smaller, so the same ₹15.8
is a larger fraction of each position. A flat fee is mildly anti-compounding: it bites hardest
exactly when the strategy is already struggling.

### The 2010–2015 dataset is unusable — delete it

Kite serves 60-minute candles only from **2015-02-02**, so that download contains nothing before
then: 1,493 bars per symbol, Feb–Dec 2015 only, leaving ~7 months after warmup and lookback. It is
also **entirely contained inside the 2015–2020 dataset**, so it adds no information. Its negative
numbers are a 7-month sample, not a finding. (It also emits divide-by-zero warnings from symbols
with flat or missing price history; confined to this dataset.)

## Not yet tested

- Whether the seat-count ordering survives several pinned start dates — the single biggest gap here.
- The trailing-stop / target layer from `INDIA_TRAILING_SL_TARGET_SPEC.md`, which would change trade
  frequency and therefore total fees.
- GTT intrabar stop fills. Measured on German data, stops fill ~1.05pp worse than the rule at bar
  close; a resting GTT should recover most of that.


---

# Android India-space run vs the Python lab — same window, same rules

Source files: `sscope_in__bg4ry3_2023-09-01_2026-08-13_60m_{equity,portfolio_trades,enrollments}.csv`.
The app ran 5 seats at 200/seat (normalised, index = 1000), no flat fee, 0.20% slippage, over
**2023-11-30 → 2026-08-13**. The lab was re-walked over that identical window from the cached
NSE 500 Kite simulation so the comparison is like-for-like.

## Result

| Run | seats | per seat | fee | CAGR | total | max DD | Calmar | Sharpe | trades | win% | median | entries/day |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **Android app** | 5 | 200 | — | **+3.59%** | +10.0% | 17.6% | 0.20 | 0.30 | 299 | 39.1% | −1.95% | 0.449 |
| Lab · conservative | 5 | 200 | — | **+22.23%** | +71.8% | 21.3% | 1.04 | 1.04 | 270 | 41.1% | −4.21% | 0.404 |
| Lab · aggressive | 5 | 200 | — | +14.27% | +43.3% | 24.7% | 0.58 | 0.75 | 273 | 39.0% | −4.28% | 0.408 |
| Lab · conservative | 7 | ₹25,000 | ₹15.8 | +11.98% | +35.7% | 21.1% | 0.57 | 0.80 | 368 | 42% | −3.13% | 0.550 |
| Lab · aggressive | 7 | ₹25,000 | ₹15.8 | +10.04% | +29.4% | 20.8% | 0.48 | 0.69 | 369 | 40% | −4.22% | 0.552 |
| Lab · conservative | 7 | ₹25,000 | — | +13.25% | +39.9% | 20.5% | 0.65 | 0.87 | 368 | 42% | −3.13% | 0.550 |

**This window is weak in the lab too.** Conservative at 7 seats with the fee returns 11.98% here
against 33.75% over the full 2020–2026 window — so roughly two-thirds of the shortfall against the
headline number is the regime, not the app. But it does not explain the gap against the lab's own
5-seat run in the same window.

## What the app gets right

Fourteen of fourteen structural checks pass on the exported ledger:

- Total value equals the sum of the seat capitals on every one of 666 days.
- Reported return equals price move minus slippage exactly; P&L equals seat capital × return; seat
  capital chains correctly from each trade to the next, so **compounding inside a seat is correct**.
- Each day's equity change reconciles exactly to the trades that exited that day.
- No symbol enrolled in two seats at once; no overlapping enrollments; every trade inside its span.
- **No seat was freed while its position was still open** — the defining 3b rule holds.
- Bar geometry agrees: the app labels 240-min bars 02:00/06:00 UTC and the lab labels them
  08:00/12:00 IST, but both buckets contain the same underlying hourly bars (3 + 4 per session).
- Entry cadence agrees: 0.449 entries/day and 66% zero-entry days, against 0.404 and 69%.
- **The screener ports correctly.** On 2023-11-30 both rank TATAINVEST first (99.03 vs 98.64), and
  four of the app's five picks sit inside the lab's top 20. Score differences of ~1 point are the
  expected consequence of percentiles computed over a different-sized universe.

## Where the return actually goes

Per-trade edge, on near-identical trade counts:

| | Android | Lab |
|---|---|---|
| Captured trades | 299 | 270 |
| **Mean per trade** | **+0.23%** | **+0.86%** |
| Sum of captured returns | +70% | +233% |
| Mean of the top decile | +16.3% | +20.8% |
| Trades above +30% caught (pool holds 83) | 1 | 4 |
| Distinct names traded | 113 | 118 |
| Names in common | 51 | |

The raw opportunity set is the same quality — mean return across every trade the strategy generates
anywhere in the universe is **+0.23%** for the app and **+0.21%** for the lab. The difference is
entirely in *which* of those trades a seat is sitting on. The lab's seats capture four times the
per-trade edge of the pool they draw from; the app's capture roughly the pool average. The lab wins
by concentrating into the right tail, and the right tail is where all the money is — its five best
trades alone carry more P&L than the entire run nets.

## Four differences that plausibly cause it

1. **The universe is a different index.** 28 of the app's traded names do not exist in the Nifty 500
   dataset at all — AETHER, ALKYLAMINE, AVALON, BALAMINES, DATAMATICS, GARFIBRES, GREENLAM,
   GRINDWELL, HINDWAREAP, IFBIND, INDIGOPNTS, ISGEC, JAMNAAUTO, JTLIND, KIOCL and others, which read
   as smallcap rather than Nifty 500 constituents. The header also records **397 names, below the
   app's own documented ">400 or fall back" guard.** Only 51 of ~115 traded names overlap. Two
   different universes cannot produce the same ledger no matter how correct the engine is.
2. **Entry timing drifts.** Only 3 of 299 trades (1%) match a lab trade exactly on entry date and
   return. 144 land within three days on the same symbol with a similar but different return —
   consistent with MACD convergence differences, not with a logic error.
3. **Warmup is not enforced.** The export declares 240 warmup bars; at 2 entry-frame bars per day
   that is 120 trading days. Data begins 2023-09-01 and the first trade fires 2024-01-01 — 82
   trading days, about 164 bars. The first legal signal should have been 2024-02-23. 17 trades (6%)
   fired early. The symptom fits counting 240 bars on the **hourly base feed** (~34 days) instead of
   the entry frame. Small effect here, but it is the exact porting mistake this document warns about.
4. **No fee, and 5 seats instead of 7.** Capital-after equals capital-before plus P&L exactly, with
   nothing subtracted. Self-consistent for a normalised index run, but these results are not
   fee-inclusive and are not comparable to any figure elsewhere in this file.

## Order to fix

Universe first — it is the only difference large enough to explain an 18.6pp gap on its own, and
every other comparison stays noisy until both sides run the same list. Then warmup, then the fee.
Timing drift is worth measuring but is the least likely culprit: entries landing a day or two apart
with similar returns is the signature of a converging indicator, not a broken rule.

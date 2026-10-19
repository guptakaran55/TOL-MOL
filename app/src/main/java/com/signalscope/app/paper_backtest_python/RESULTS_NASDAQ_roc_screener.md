# Room to resistance (ROC) as a seat trait — NASDAQ

**Verdict: do not add it, and above all do not use it as a filter.** The worry behind the test — that
momentum seats are bought right under a ceiling — turns out to be mostly unfounded: about **half of all
seated names have no real ceiling above them at all**, against a third of the universe, and those are
the best trades the portfolio takes. The app's ROC number cannot see this. When there is no ceiling it
falls back to a level just above the day's high, so a name at fresh highs reads as having *almost no
room*. Filtering on it throws away the best seats and cost **8–13 CAGR points in 2016–21**. As a
ranking trait it is a regime bet: it wins every start date in 2022–26 and loses almost every one in
2016–21. Most of what ROC % measures is volatility.

Generated 2026-10-19. Script: `backtest_results/roc_screener/roc_screener_lab.py` (runs on the
desktop lab's engine and data). Raw output: `walks.csv`, `diagnostics.json`, `full_run.log` beside it.
Follows `RESULTS_NASDAQ_potential_screener.md`.

## What ROC means here — the app's own number

Ported from `Indicators.findSupportResistanceDetailed` and `StockAnalyzer` and checked against a
literal line-by-line transcription on every day of four names (3,856 days): identical apart from
one-cent rounding at prices under $20, and the "no ceiling" verdict agreed on every day.

- Daily bars up to and including the ranking day. Adaptive lookback 50–252 days from pivot density,
  strict 5-bar swing pivots, clusters within 0.8 × ATR(14).
- Resistance = the best-scored cluster (touches × reversal × recency) **at least 1 ATR above** the
  price, or a broken-support cluster at least 2 ATR above.
- **No qualifying cluster → fallback**: classic pivot R1 if it is above the price, else the window
  high. This is what happens at fresh highs, and it reports a small ROC.
- ROC = (R − P) / P × 100.

Because of the 1-ATR floor, ROC % rises with volatility (rank correlation with ATR % **+0.42 to
+0.46**). And because of the fallback it runs *against* momentum: rank correlation with the
screener's trailing return is **−0.44 to −0.48**.

Three readings were tested:

| reading | what a "no ceiling" name gets |
|---|---|
| **app** | exactly the phone's number: little room |
| **sky** | unlimited room (no ceiling = open sky) |
| **atr** | the app number divided by ATR %: room counted in days' ranges, volatility removed |

## Setup

7 seats, normalised 1000 per seat, 0.20% slippage, 240min entry / 120min exit, 3b (flag, exit at
natural close), daily-MACD slope gate smoothing 5 / confirm 1, screener lookback 90 calendar days
(production, not the 62 trading days of the earlier study, so the control **is** the engine).
Both B_SLOW champions × both NASDAQ eras × 10 start offsets (0–132 trading days) = 40 walks per
variant, 320 in all.

The walk is a copy of the lab engine's 3b walk with the ranking made swappable. At offset 0 the control
reproduces the engine's final value **to the cent** on all four samples (16,818.77 · 18,084.86 ·
23,088.46 · 22,427.56). Drawdown and Sharpe are from a **marked-to-market** daily curve (open positions
valued at the daily close), not the engine's realised-only curve.

| variant | ROC weight | rule | reading |
|---|---|---|---|
| A control | — | current screener (0.72 / 0.57 / 0.45) | — |
| B | 0.20 | 4th percentile trait | app |
| C | 0.45 | 4th percentile trait | app |
| D3 | — | no seat if ROC < 3% | app |
| D5 | — | no seat if ROC < 5% | app |
| B_sky | 0.20 | 4th percentile trait | sky |
| D3_sky | — | no seat if ROC < 3% | sky |
| B_atr | 0.20 | 4th percentile trait | atr |

## Result 1 — where momentum seats actually sit

Averaged over every 5th trading day, control portfolio:

| | seated top 7 | whole universe |
|---|---|---|
| no real ceiling above (fallback) | **51–52%** | 29–34% |
| real ceiling less than 3% away | **1–3%** | 6–9% |

Momentum seats sit under a near ceiling **less** often than a random name, not more. They are
disproportionately at fresh highs. The concern is the right instinct applied to the wrong stocks.

Per-trade net return of the trades the control actually booked, by ROC on the day before entry
(both eras pooled, conservative champion; aggressive is the same within ~0.2pp):

| ROC before entry | trades | mean per trade |
|---|---|---|
| < 3% | 25 | +1.23% |
| 3–6% | 87 | +0.82% |
| 6–10% | 97 | +1.17% |
| 10–20% | 94 | +2.89% |
| ≥ 20% | 48 | +0.89% |
| **no ceiling** | **530** | **+2.18%** |

The no-ceiling group is 60% of all booked trades and earns more than any bucket except 10–20%.
These are exactly the names the app's ROC labels as having the least room.

## Result 2 — does room predict the trade, once volatility is taken out?

Every closed trade the engine generated anywhere in the universe (~14,800 per champion, both eras),
by ROC % and by room in ATRs:

| by ROC % | < 3% | 3–6% | 6–10% | 10–20% | ≥ 20% | no ceiling |
|---|---|---|---|---|---|---|
| mean per trade | +0.18% | +0.26% | +0.43% | +0.52% | +0.63% | +0.60% |

| by room in ATRs | < 2 | 2–4 | 4–8 | ≥ 8 | no ceiling |
|---|---|---|---|---|---|
| mean per trade | **+0.45%** | +0.39% | +0.25% | +0.42% | +0.60% |

The ROC % gradient is real but **disappears, even reverses, when room is counted in ATRs**. Split the
same trades into volatility thirds first and compare less vs more room inside each third:

| volatility third | less room | more room | no ceiling |
|---|---|---|---|
| calm | +0.22% | +0.16% | +0.19% |
| middle | +0.23% | +0.25% | +0.20% |
| volatile | +0.72% | +0.67% | **+1.66%** |

Inside a volatility group room makes no difference. What looked like "more room → better trades" is
"more volatile → bigger moves". The one strong effect is the volatile, no-ceiling group: breakouts.

The 2022–26 era alone does show trades under 3% room losing money (−0.34% / −0.24%), but 2016–21
shows the opposite (+0.50%), so it does not survive a second sample.

Among the screener's top 20 only, calm and middle names with more room did do better (+0.20% → +0.56%,
+0.20% → +0.94%). This is the only cut where room helped. It is small, and it lives in the names the
screener ranks lowest anyway.

## Result 3 — the portfolio walks

| variant | mean CAGR | median | worst | mean DD | Calmar | Sharpe | per trade | conversion |
|---|---|---|---|---|---|---|---|---|
| **A control** | 30.77% | 29.76% | 19.76% | −15.0% | 2.08 | 1.59 | 2.00% | 40% |
| B | 34.57% | 30.69% | 18.75% | **−13.7%** | **2.69** | **1.68** | 2.03% | 40% |
| C | 33.99% | **32.58%** | 18.98% | −16.3% | 2.11 | 1.62 | 1.94% | 42% |
| D3 | 27.95% | 25.47% | 10.33% | −14.5% | 2.15 | 1.31 | 1.29% | 45% |
| D5 | 28.77% | 25.72% | 7.94% | −16.1% | 2.04 | 1.31 | 1.31% | **48%** |
| B_sky | 31.31% | 29.90% | **21.14%** | −14.0% | 2.25 | 1.59 | 1.94% | 40% |
| D3_sky | 30.34% | 29.51% | 18.89% | −13.8% | 2.26 | 1.55 | 1.92% | 40% |
| B_atr | 33.92% | 31.85% | 19.71% | −14.6% | 2.40 | 1.62 | 1.99% | 40% |

Head-to-head against control, out of 40 paired walks:

| variant | CAGR | Calmar | Sharpe | lower DD | median CAGR edge |
|---|---|---|---|---|---|
| B | 21/40 | 26/40 | 21/40 | 30/40 | +1.09pp |
| C | 24/40 | 15/40 | 20/40 | 5/40 | +2.43pp |
| D3 | 18/40 | 20/40 | **6/40** | 24/40 | −3.63pp |
| D5 | 20/40 | 18/40 | 19/40 | 11/40 | −3.88pp |
| B_sky | 24/40 | 27/40 | 22/40 | 23/40 | +0.51pp |
| D3_sky | 22/40 | 31/40 | 10/40 | 31/40 | +0.09pp |
| **B_atr** | **30/40** | **29/40** | **24/40** | 25/40 | **+2.15pp** |

Mean CAGR by sample (wins out of 10 starts):

| sample | A | B | C | D3 | D5 | B_sky | D3_sky | B_atr |
|---|---|---|---|---|---|---|---|---|
| 2016–21 cons | 20.4 | 19.7 (1) | 19.6 (2) | **12.8 (0)** | **8.9 (0)** | 22.6 (10) | 19.9 (3) | 21.0 (8) |
| 2016–21 aggr | 22.2 | 20.3 (0) | 21.1 (2) | **11.3 (0)** | **8.9 (0)** | 22.3 (4) | 20.1 (1) | 21.7 (2) |
| 2022–26 cons | 40.7 | 44.8 (10) | 46.8 (10) | 42.9 (8) | 46.7 (10) | 39.9 (2) | 41.1 (8) | 44.9 (10) |
| 2022–26 aggr | 39.8 | 53.5 (10) | 48.5 (10) | 44.9 (10) | 50.5 (10) | 40.4 (8) | 40.3 (10) | 48.1 (10) |

### Reading

- **The filters are the dangerous part.** D3 and D5 raise conversion (40% → 45–48%) exactly as the
  "room to run" idea predicts, then lose a third of the per-trade return (2.00% → 1.3%) because what
  they mostly remove is the no-ceiling names. In 2016–21 that cuts CAGR by 40–60% and loses all 20
  walks. Their
  2022–26 wins come from a market where almost everything that moved kept moving.
- **The app-reading traits (B, C) are the same story at lower strength.** 10/10 in 2022–26, 0–2/10 in
  2016–21, a clean flip by era — the same shape as `depth` in the potential-screener study, and for
  the same reason: the app's ROC is about −0.45 correlated with trailing return, so weighting it is
  partly just weighting momentum down. C makes drawdown worse in 35 of 40 walks.
- **The open-sky reading (B_sky, D3_sky) is close to harmless and close to useless.** Median edge
  +0.5pp and +0.1pp. D3_sky lowers drawdown in 31/40 but loses Sharpe in 30/40.
- **B_atr is the only one with a case**: 30/40 on CAGR, 29/40 on Calmar, +2.15pp median edge, worst
  case unchanged (19.71% vs 19.76%). But it wins 20/20 in 2022–26 and 10/20 in 2016–21 (8/10 cons,
  2/10 aggr). It is still regime-shaped, and still about −0.5 correlated with trailing return.

## Verdict

- **Keep the screener as it is.** Nothing here beats control in both eras.
- **Never filter seats on the app's ROC.** It labels breakouts as "no room" and those are the best
  seats. If the phone ever shows a small ROC on a stock at fresh highs, read it as "no ceiling found",
  not "about to hit one".
- **If a room trait is ever pursued, use room in ATRs (B_atr) at 0.20** — but treat it like `accel`:
  a candidate that must be re-validated per champion and market, not a default. Its edge comes from
  one era.
- **The app's ROC should report "no ceiling" separately** from "little room". Anything that ranks by
  it today (the floor algo's universe) puts fresh-high names last for the same reason.

## Do not conclude from this

- NASDAQ only, commission-free, 0.20% slippage. Not run on NSE or DAX.
- Both eras use today's NASDAQ-100 members. Survivorship bias flatters momentum and breakout names
  most, which is precisely the no-ceiling group this study finds strongest.
- Trade buckets use ROC on the day **before** entry; seat decisions use it on the binding day. Both
  are known before the trade, but they are not the same number.
- The pooled trade tables count each era once per champion. Conservative and aggressive share most
  trades, so their tables are not independent evidence.

## Reproducing

```
cd backtest_results/roc_screener
python roc_screener_lab.py            # all 320 walks + engine parity + diagnostics, ~5 min (cached)
python roc_screener_lab.py --quick    # one sample, offset 0
```

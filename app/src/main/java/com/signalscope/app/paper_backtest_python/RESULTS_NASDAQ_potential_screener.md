# Ranking on "unused potential" instead of realized momentum — NASDAQ

**Question.** The champion screener ranks on realized momentum (trailing return, volatility,
%-above-SMA50) and then the MACD entry waits for a *fresh* upturn. Those pull in opposite
directions: a name that has already run has an extended MACD, more likely to roll over than to fire.
Symptom — **about half of all enrollments never produce a single trade.** Would ranking on room-to-run
instead fix it?

**Two new traits.**

| Trait | Definition | Why this form |
|---|---|---|
| `depth` | Position of today's daily MACD inside its own trailing 62-day range, inverted. 100 = at its low. | MACD magnitude scales with price, so the percentile is taken **within the symbol first**, making it comparable across names. A raw level would just rank cheap stocks. |
| `accel` | d²MACD/dt², smoothed 5 days, divided by that symbol's own 62-day std of dMACD/dt. | Scale-free curvature: is the slope itself turning up? |

Both are then percentile-ranked cross-sectionally, exactly as the three existing traits are.

**Traits validated before use** (2022-06 era): coverage 92%, no degenerate values.
Cross-sectional correlation against trailing return — `depth` **−0.189**, `accel` **+0.006**.
That difference matters: acceleration carries information the current screener does not have at all,
while depth is partly just a negation of the trailing-return trait.

## Setup

7 seats, normalised 1000 total, 0.20% slippage, 240min entry / 120min exit, daily-MACD slope gate
with smoothing 5 and confirm 1. Both champions, both NASDAQ validation eras. Control (variant A) is
the champion screener run through the **same** machinery, so the A/B is clean. Trait windows are 62
trading days throughout (the trading-day equivalent of the 90-calendar-day screener lookback), which
makes every variant mutually comparable but means A does not reproduce any previously published
figure exactly.

| Variant | ret | vol | sma | accel | depth |
|---|---|---|---|---|---|
| A control (champion) | 0.72 | 0.57 | 0.45 | — | — |
| B + accel | 0.72 | 0.57 | 0.45 | 0.45 | — |
| C + depth | 0.72 | 0.57 | 0.45 | — | 0.45 |
| D + accel + depth | 0.72 | 0.57 | 0.45 | 0.45 | 0.45 |
| E potential-tilted | 0.36 | 0.57 | 0.45 | 0.60 | 0.60 |
| F pure potential | — | 0.57 | — | 0.72 | 0.72 |

## Result — averaged over all four samples

| Variant | CAGR | max DD | Calmar | Sharpe | trades | **per trade** | **conversion** |
|---|---|---|---|---|---|---|---|
| **A control** | **43.23%** | 13.1% | 3.39 | 1.79 | 521 | **1.920%** | **49%** |
| B + accel | 37.24% | **12.0%** | 3.13 | 1.78 | 518 | 1.730% | 53% |
| C + depth | 37.34% | 12.4% | 3.20 | **1.81** | 542 | 1.749% | 49% |
| D + accel + depth | 33.98% | 14.0% | 2.39 | 1.66 | 537 | 1.693% | 53% |
| E potential-tilted | 16.42% | 13.6% | 1.19 | 1.12 | 552 | 0.921% | 56% |
| F pure potential | 20.59% | 16.3% | 1.42 | 0.96 | 592 | 0.828% | **63%** |

## The idea works — and that is exactly how we know it fails

**Conversion rises monotonically with potential weighting: 49% → 63%.** The hypothesis is confirmed
at the mechanism level. Weighting the screener toward names whose MACD is low and curling up does
put seats on names that actually fire. Dead capital drops by a third.

**Per-trade return falls monotonically at the same time: 1.920% → 0.828%.** That is the whole story
in one line. The seats that never traded were not costing much; the extra trades bought by fixing
them are systematically *worse* than the ones already being taken. More trades, less money —
variant F takes 71 more trades than control and returns half as much.

The reason is that trailing return is not a defect to be corrected. Momentum persistence is real,
and it is doing genuine work in the ranking. "Room to run" is close to its inverse (−0.189), so
weighting it up is partly just weighting momentum down, and momentum pays more than the conversion
gain is worth. E and F, which cut the trailing-return weight, lose to control on CAGR, Calmar and
Sharpe in **all four samples**.

## Start-date robustness: 120 walks

The one big control number (77.5% on 2022-06 conservative) sat on the champion's own tuning data, so
each variant was re-run from 10 start dates offset 0–132 trading days, on all four samples.

CAGR by start-date offset:

| sample | variant | 0 | 11 | 22 | 33 | 44 | 55 | 66 | 88 | 110 | 132 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 2016–20 cons | A | 23.3 | 22.4 | 22.1 | 21.3 | 24.1 | 22.9 | 22.9 | 23.7 | 24.4 | 23.2 |
| | **B** | **29.0** | **29.4** | **27.9** | **28.6** | **27.5** | **27.5** | **27.5** | **30.4** | **30.6** | **32.9** |
| 2016–20 aggr | A | 23.7 | 25.6 | 25.0 | 24.3 | 28.0 | 25.9 | 25.9 | 28.2 | 27.1 | 27.5 |
| | B | 25.5 | 25.6 | 24.5 | 24.8 | 24.7 | 24.0 | 24.5 | 27.2 | 27.4 | 29.0 |
| 2022–26 cons | **A** | **77.5** | **77.2** | **71.8** | **62.7** | **72.0** | **69.0** | **85.9** | **78.6** | **72.9** | **75.3** |
| | B | 45.2 | 48.2 | 42.4 | 42.2 | 48.4 | 45.4 | 50.7 | 49.7 | 49.3 | 52.9 |
| 2022–26 aggr | A | 48.4 | 51.6 | 49.8 | 49.1 | 52.3 | 55.3 | 56.4 | 58.0 | 57.7 | 55.7 |
| | **C** | **59.6** | **62.3** | **60.9** | **59.5** | **64.3** | **63.7** | **69.1** | **64.3** | **72.9** | **74.9** |

| Across all 40 starts | mean | median | min | max | mean DD | mean Calmar |
|---|---|---|---|---|---|---|
| A control | 44.22% | 38.30% | 21.27% | 85.88% | 12.8% | 3.59 |
| B + accel | 39.02% | 37.53% | **24.03%** | 57.37% | 12.1% | 3.32 |
| C + depth | 41.24% | 37.82% | 17.63% | 74.85% | **11.6%** | **3.94** |

Head-to-head against control, out of 40:

| | CAGR | Calmar | Sharpe | lower DD |
|---|---|---|---|---|
| B + accel | 21/40 | 28/40 | 22/40 | **30/40** |
| C + depth | 10/40 | 15/40 | 22/40 | **34/40** |

**The 77.5% was not a fluke** — control holds 62.7–85.9% across every start on that sample. But the
sweep exposes something more useful: **which variant wins flips by sample, and flips cleanly.**
B beats control at 10 of 10 starts on 2016–20 conservative and loses at 10 of 10 on 2022–26
conservative. C beats control at 10 of 10 on 2022–26 aggressive and loses at 10 of 10 on 2022–26
conservative. These are not noisy coin-flips; each variant is reliably better or reliably worse
*depending on which entry config it is paired with*. The screener trait and the entry gate interact.

## Verdict

**Do not replace the screener.** Control wins on CAGR and the two heaviest potential variants lose
everywhere. But the idea is not dead — it is a **risk lever, not a return lever**:

- On CAGR, B is a coin flip (21/40) and C is a loser (10/40).
- On drawdown, both win consistently — B 30/40, C 34/40, mean DD 12.8% → 12.1% / 11.6%.
- C has the **best mean Calmar of anything tested, 3.94 vs 3.59**, while losing on median. Its edge
  is concentrated, not broad.

Acceleration is the more promising of the two, because it is orthogonal to what the screener already
measures (+0.006) rather than a partial negation of it (−0.189). If either is pursued, pursue `accel`,
at a weight low enough to shade the ranking rather than steer it, and treat it as a
drawdown-reduction change that must be re-validated per entry config — not a portable default.

## Do not conclude from this

- Nothing here is fee-inclusive; NASDAQ was run commission-free with 0.20% slippage only.
- Both eras are NASDAQ-100 members as constituted today, so the survivorship caveat from the NSE
  work applies unchanged, and applies harder to a screener whose job is to take the top few.
- The 62-trading-day trait window is not identical to the 90-calendar-day production lookback.
  Variants are comparable to each other; none is comparable to a previously published figure.
- ~~Untested: `accel` at lower weights~~ — tested below.


---

# Follow-up: `accel` at 0.15 / 0.20 / 0.30 / 0.45

200 walks — five weightings × four samples × ten start dates. Neighbours around 0.20 were included
so the result could be read as a curve rather than a single lucky point.

| Across all 40 walks | mean CAGR | median | min | max | mean DD | Calmar | Sharpe | conv% |
|---|---|---|---|---|---|---|---|---|
| A control | 44.22% | 38.30% | 21.27% | **85.88%** | 12.8% | 3.59 | 1.86 | 49 |
| accel 0.15 | 44.22% | 41.06% | 20.39% | 76.56% | 11.9% | 3.62 | 1.94 | 51 |
| **accel 0.20** | **44.39%** | **41.82%** | 20.46% | 75.51% | **11.4%** | **4.26** | **2.01** | 51 |
| accel 0.30 | 41.77% | 40.38% | **25.34%** | 64.67% | 12.1% | 3.63 | 1.93 | 52 |
| accel 0.45 | 39.02% | 37.53% | 24.03% | 57.37% | 12.1% | 3.32 | 1.84 | 53 |

Head-to-head against control, out of 40 paired walks:

| variant | CAGR | Calmar | **Sharpe** | **lower DD** | median CAGR edge |
|---|---|---|---|---|---|
| accel 0.15 | 18/40 | 28/40 | 34/40 | 25/40 | −0.80pp |
| **accel 0.20** | 14/40 | 25/40 | **40/40** | **39/40** | −1.40pp |
| accel 0.30 | **26/40** | **30/40** | 26/40 | 36/40 | **+2.68pp** |
| accel 0.45 | 21/40 | 28/40 | 22/40 | 30/40 | +0.19pp |

## 0.20 is a risk optimum, not a return optimum

**Sharpe improves in 40 of 40 walks and drawdown in 39 of 40.** Nothing else in this entire
investigation was unanimous across every sample, champion and start date. Mean drawdown falls
12.8% → 11.4%, the lowest of any configuration tested. That is a real, robust effect.

**CAGR is a wash** — mean +0.17pp, median edge −1.40pp, 14/40 head-to-head. The higher median CAGR
(41.82% vs 38.30%) is not a contradiction: control has a fat right tail concentrated in one sample,
which lifts its mean while leaving its median low.

## The config interaction does not disappear at low weight — it moves

Mean CAGR edge over control, by sample (wins out of 10 starts):

| sample | accel 0.15 | accel 0.20 | accel 0.30 | accel 0.45 |
|---|---|---|---|---|
| 2022–26 conservative | 3/10  −5.1 | 3/10  −6.5 | 0/10 −18.7 | 0/10 −26.9 |
| 2022–26 aggressive | **10/10  +8.5** | **10/10 +11.7** | 6/10  +1.2 | 6/10  +0.3 |
| 2016–20 conservative | 5/10  −0.0 | 1/10  −1.2 | **10/10  +3.8** | **10/10  +6.1** |
| 2016–20 aggressive | 0/10  −3.3 | 0/10  −3.2 | **10/10  +4.0** | 5/10  −0.4 |

**The weight decides which config it helps.** At 0.20 the trait wins one sample decisively and loses
the other three; its flat overall mean is carried entirely by the 2022–26 aggressive result. At 0.30
it wins three of four. There is no weight that is uniformly good on return — lowering the weight did
not remove the interaction found earlier, it relocated it. That is an overfitting hazard, and it
means any chosen weight is a bet on which regime and champion you will actually be running.

## Caveat on the Calmar 4.26

That figure is the highest in the investigation, but it is a **spike, not a plateau** — neighbours
sit at 3.62 and 3.63. It traces to a single anomalous cell: mean drawdown of 9.45% on 2022–26
conservative, against 12.79% at weight 0.15 and 11.00% at 0.30. Do not treat 4.26 as reproducible.
The drawdown series itself (12.8 → 11.9 → **11.4** → 12.1 → 12.1) is smooth and well-behaved, so the
drawdown reduction is trustworthy where the Calmar headline is not.

## Verdict

- **Want a smoother curve:** `accel` at **0.20**. Sharpe 40/40, drawdown 39/40, mean DD 12.8% → 11.4%,
  CAGR neutral. Unusually well-evidenced for a change this small.
- **Want return:** `accel` at **0.30** — 26/40 on CAGR, +2.68pp median edge, wins three of four
  samples, and lifts the worst case across all 40 walks from 21.27% to 25.34%.
- **Neither is a portable default.** Re-validate against the specific champion and regime in use.
- Still NASDAQ-only, still commission-free, still survivorship-biased. Untested on NSE or DAX.

# Buying the retest dip below the algo's signal — NASDAQ

**Verdict: buy at the signal. A resting limit below the signal price lost CAGR in every variant tested.**
The dip after a buy signal is common, but the trades that dip down to your limit are mostly the ones
that keep falling, while the strong runners never come back for you. The limit makes each fill a
little cheaper and misses 30–95% of the trades. The missed trades carry the profit.

Generated 2026-10-19. Script: `backtest_results/roc_screener/limit_retest.py`. Raw output:
`limit_retest_trades.csv`, `limit_retest_walks.csv`, `limit_retest.log` beside it.

## What was tested

When the champion's 240min candle fires a buy, instead of buying at that close, rest a limit at
close × (1 − X%). It fills on a later 120min bar whose low reaches it (at the limit, or at the open if
the bar gaps below). After the fill the champion's own exits run, **measured from the fill**: 4% stop
on 120min closes, slope-flip-down with the 30% MACD-distance qualifier. The order is cancelled after
a timeout, or as soon as the algo's own sell signal would fire. A cancelled signal is a trade that
never happened.

X = 1, 2, 3, 4% × timeout 1 day / 1 week / until the algo's sell = 12 variants, against buying at the
signal. Both B_SLOW champions, both NASDAQ eras, 10 start offsets, 7 seats 3b, 0.20% slippage per
trade (kept on limit fills too, which slightly flatters buying at the signal). At X = 0 the simulator
reproduces the engine's trades exactly on all 202 symbol runs checked (2022–26, both champions), and
the "at signal" portfolio reproduces the engine control of `RESULTS_NASDAQ_roc_screener.md`
(mean CAGR 30.77%, median 29.76%, worst 19.76%).

Earlier measurement, for context: in about 70% of entries the price dips 2% or more below the buy
within 5 days (median dip about 3%). So the dip is real. The question is whether waiting for it pays.

## Result — trades (all symbols, means over both champions and eras)

| order | signals filled | per filled trade | win % | per signal |
|---|---|---|---|---|
| **at signal** | 100% | +0.44% | 45% | **+0.44%** |
| −1%, until sell | 70% | +0.46% | 43% | +0.32% |
| −2%, until sell | 49% | +0.42% | 44% | +0.21% |
| −3%, until sell | 35% | +0.52% | 45% | +0.18% |
| −4%, until sell | 25% | +0.59% | 46% | +0.15% |
| −2%, 1 day | 20% | +0.56% | 43% | +0.11% |
| −4%, 1 day | 5% | +0.76% | 42% | +0.04% |

A filled trade bought 2% cheaper earns about the same as one bought at the signal (+0.42% vs +0.44%),
not 2 points more. The discount is eaten by selection: the names that come back down to the limit
are disproportionately the ones heading for the stop. Deeper limits do improve the filled trades a
little (+0.6% at −4%), but they fill so rarely that money per signal falls by two thirds or more.

## Result — portfolio (40 walks per order type)

| order | mean CAGR | median | worst | mean DD | Sharpe | trades | CAGR wins vs at signal |
|---|---|---|---|---|---|---|---|
| **at signal** | **30.77%** | **29.76%** | **19.76%** | −15.0% | **1.59** | 441 | — |
| −1%, 1 week | 24.76% | 23.73% | 19.88% | −16.3% | 1.38 | 395 | 12/40 |
| −1%, until sell | 24.60% | 23.28% | 15.42% | −13.9% | 1.43 | 372 | 0/40 |
| −1%, 1 day | 23.54% | 22.50% | 19.04% | −14.2% | 1.34 | 400 | 0/40 |
| −3%, 1 week | 21.94% | 20.55% | 11.92% | −12.0% | 1.39 | 272 | 0/40 |
| −3%, until sell | 21.57% | 19.41% | 10.72% | −11.3% | 1.47 | 230 | 0/40 |
| −2%, 1 day | 20.48% | 20.00% | 11.68% | −14.2% | 1.21 | 308 | 0/40 |
| −2%, until sell | 19.15% | 17.76% | 9.66% | −13.4% | 1.27 | 305 | 0/40 |
| −2%, 1 week | 18.24% | 18.00% | 14.14% | −16.1% | 1.17 | 346 | 0/40 |
| −4%, 1 week | 17.78% | 16.32% | 10.02% | −12.7% | 1.24 | 215 | 0/40 |
| −3%, 1 day | 15.51% | 14.34% | 10.15% | −11.8% | 1.15 | 210 | 0/40 |
| −4%, until sell | 14.64% | 14.99% | 5.42% | −12.6% | 1.19 | 166 | 0/40 |
| −4%, 1 day | 8.65% | 7.45% | 3.66% | −13.6% | 0.78 | 147 | 0/40 |

Mean CAGR by sample:

| order | 2016–21 aggr | 2016–21 cons | 2022–26 aggr | 2022–26 cons |
|---|---|---|---|---|
| at signal | 22.2 | 20.4 | 39.8 | 40.7 |
| −1%, 1 week | 21.3 | 21.8 | 29.3 | 26.6 |
| −2%, until sell | 11.4 | 10.7 | 26.3 | 28.3 |
| −3%, until sell | 12.2 | 11.1 | 28.4 | 34.6 |

- **No variant beats buying at the signal on CAGR or Sharpe.** Only −1% with a one-week timeout
  wins any walks (12/40), and only in 2016–21 conservative.
- Drawdown is somewhat lower with deep limits (−15.0% → about −11 to −12%) because the seats sit in
  cash more often. Calmar does not recover: CAGR falls far faster than drawdown.
- The loss is worst in the strong market (2022–26: 40% → 20–35%), where the best trades never look
  back.

## What this means for the app

- For the algo's own entries, **send the buy at the signal.**
- This is not a verdict on the RV gate. That rule waits for a dip *and a turn back up* near the
  day's high, and caps the wait at 2 hours. It never rests an order below the price and hopes. A
  dip-and-turn buy was not tested here.

## Do not conclude from this

- NASDAQ only, today's members (survivorship bias), commission-free.
- Fills are judged on 120min bar lows. A limit touched by a single print might not fill in reality,
  which would make the limit results slightly worse still.

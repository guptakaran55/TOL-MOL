# The full decision chain — monthly low → timeframes green → champion — backtested

**Verdict: the chain makes about 1–6% a year with a 41–44% win rate. The current momentum portfolio
made 19–41% on the same stocks in three of the four samples.** Every way of starting from "near its
monthly low" earned a fraction of starting from strength. Splitting each buy into two halves made it
worse still, because the second half often never fires and its money sits idle in a seat that could
be working.

Generated 2026-10-19. Script: `backtest_results/roc_screener/chain_lab.py`. Raw output:
`chain_walks.csv`, `chain_run.log`.

## The chain as tested

1. **Near the monthly low**: price in the bottom 10% of its last 21 trading days' range (the app's
   rule in `IntradayLevels.floorsHit`, judged on each 15-minute bar). A watch then lasts 20 trading
   days after the latest touch.
2. **AI outlook positive**: not testable on past data (the model knows what happened next). Assumed
   always yes.
3. **Half the money** when daily, 4-hour, hourly and 15-minute MACD slopes all turn rising (the app's
   own slope: unsmoothed, completed bars, UTC-aligned buckets). India's data is hourly, so there it is
   daily, 4-hour and hourly only.
4. **The other half** when the conservative champion's buy fires.
5. **Each half sells** when the champion says sell, measured from its own buy (4% stop, slope flip
   down with the 30% MACD-distance rule).

Big companies only: median daily turnover over the previous 60 sessions at least the app's cutoff
(US $10m, India ₹10 crore) or a tighter one (US $50m, India ₹50 crore; the main run).

Portfolio: 7 seats of 1000, compounding. A stock takes a seat on its first buy and keeps it until both
halves are out. 0.20% cost per trade, daily marked-to-market. 10 start dates per sample.

Checks: the champion's signals and exits reproduce the engine's trades exactly when replayed one at a
time (66/66, 85/85, 66/66, 79/79 on four test names). The momentum reference is the engine's own 3b
portfolio (conservative, 7 seats) on the same data.

## Result (means over 10 start dates; range of yearly growth in brackets)

| sample | the chain | dots half only | champion half only | chain, no monthly-low step | **momentum 3b today** |
|---|---|---|---|---|---|
| US Nasdaq-100 2016–21 | 6.4% (5–9) | 20.3% (18–23) | 17.5% (16–20) | 6.3% | **20.4% (20–21)** |
| US Nasdaq-100 2022–26 | 1.0% (−4–5) | 14.9% (11–19) | −2.8% | 8.7% | **40.7% (37–48)** |
| US big caps ex-NDX 2022–26 | 2.4% (0–5) | 0.3% | −1.5% | 0.8% | **−3.8%** |
| India 500 2020–26 | 6.4% (4–9) | −3.4% | −1.6% | 1.0% | **18.9% (9–26)** |

| sample | chain win rate | chain avg per trade | chain worst drop | momentum worst drop |
|---|---|---|---|---|
| US Nasdaq-100 2016–21 | 44% | +0.28% | −13% | −14% |
| US Nasdaq-100 2022–26 | 41% | +0.05% | −19% | −15% |
| US big caps ex-NDX 2022–26 | 44% | +0.11% | −14% | −19% |
| India 500 2020–26 | 42% | +0.24% | −23% | −24% |

The momentum portfolio averages +1.1% to +2.5% per trade on the samples where it works. The chain
averages +0.05% to +0.28%.

## Reading

- **Starting from the monthly low is the expensive step.** In every sample except the defensive
  big caps, the momentum portfolio beat the chain by 12–40 points a year.
- **The half-and-half split costs a lot by itself.** On the Nasdaq 2016–21, either half alone made
  17–20%, while both together made 6%. A seat waits up to 20 days for its second half. Meanwhile half
  its money is idle and the seat is blocked for other stocks.
- **The timeframes-green half is the better half, but not reliably.** It made 20% and 15% on the
  Nasdaq, then 0% on US big caps and −3% in India.
- **The one sample where the chain won** is the defensive US big caps in 2022–26. There, nothing
  worked: the chain made 2.4% and momentum lost 3.8%. Treat that as "everything was flat", not as
  an edge.
- **The size cutoff barely matters**: identical for US big caps; the tighter cutoff was 2 points
  better on the Nasdaq and India.
- **The win rate was never the problem.** It was 41–46% for every version, including the momentum
  portfolio's own trades. What differs is how big the winners are, and buying strength is what makes
  them big.

## Do not conclude from this

- The AI step is assumed always positive. A real filter could only remove trades. It could raise the
  win rate if it is genuinely good, but nothing here measures that. It has to be logged going forward
  to find out.
- Today's index members only (survivorship bias), no commissions beyond 0.20% per trade.
- The near-low rule is judged on 15-minute bars (hourly in India); the app judges it on its live pass.

# Every trading idea in the app: what the PC backtests said, what the phone shows

Written 15 Nov 2026. Backtests: the `RESULTS_MATRIX_*.md` files in the backtest folder. Phone: every ledger pulled on
15 Nov (records are 3–6 weeks long for most algos, since August for a few). "Per trade" is after costs unless it says
otherwise. Real-money figures are closed trades only.

## The short conclusion

**The phone matches the honest backtests, not the early optimistic ones.**

- The first backtests (July–August) showed +45% to +157% a year. They used today's stock lists to trade the past, and
  that hindsight added about 40 points a year in the US (`point_in_time`). Redone on the lists as they were, the same
  algo makes 9–16% a year in the US — below simply holding the Nasdaq-100 — about +12–18% in Germany, and about index
  level in India.
- Every later test said the same: the per-trade edge is thin (+0.1% to +0.7%), costs take most of it, and
  **no tested rule reliably beat the buyable index** (`momentum`, `etf_rules`, `graham_backtest`, `value_score`).
- The phone agrees: most paper algos are at −2% to −4% a trade over a few weeks, real money is about −€117 and −₹2,447
  on closed trades, and the only things in profit are the ones the backtests also liked or that rode a rally.
- A few weeks cannot confirm or kill an edge of this size. A swing trade moves about ±5.7%, so after 100 trades the
  average is still uncertain by ±0.6% — twice the edge being looked for. What the phone CAN show is something badly
  broken, and several algos qualify (worse than −1% a trade over 70–100 trades).

## The table

| # | Idea | How it trades | What the backtest said | What the phone shows | Strong points | Not working |
|---|---|---|---|---|---|---|
| 1 | **MACD champion, seat algo** (config-file algos: B_Conservative, "Us conservative", Test B/B0/C) | 4-hour/2-hour MACD turn buys; 5 seats filled from the 90-day momentum ranking; daily MACD frees a seat | Hindsight lists: +45–157%/yr. Honest lists: US 9–16%/yr (8–18 points under QQQ), Germany +12–18%, India ≈ index | Real: Germany −€43 on 25 trades, US **+€67 on 14**. Paper tests: −2% to −5% a trade over 3–4 weeks | Smaller worst falls than the index; the US real one is the only real-money winner so far | Does not beat the index; the "slower exit" for Germany only worked on one stock list; India at index level before costs |
| 2 | **Fast variants** (Test A 60/30, 15-minute scalp) | Same idea on 1-hour and 15-minute bars | 15-minute scalp: −14%/yr on German data | Scalp: about −1% a trade over 77–96 trades in each market, −16% to −18% of capital | None found | Trades 20–25 times a week; costs and noise swallow it. **Matches the backtest: stop it** |
| 3 | **Watchlist** (your buys on its entry alerts) | The champion's signal per stock, no seats; you tap Buy; trailing stop; far targets | Thin edge: Germany +0.12–0.20% a trade at zero cost (about zero at 0.2%), US +0.3–0.7% | Real: Germany −€101 on 123 trades (−0.68% a trade) but **+€71 in the last 14 days**; US −€37 on 38; India −₹622 on 5 | You choose; pins; the newer exits (slower exit, no near target) follow what the tests found | 30–37% of trades won; earlier weeks carried near targets and tight trailing stops the tests later rejected |
| 4 | **RV gate** (when to send a buy or sale) | Waits for a dip and a turn inside the day, at most 2 trading hours | 409 signals replayed: buys 0.21% cheaper; the buys it misses are the runners | 105 real orders: 0.0% either way | Costs nothing on average; cheaper entries in the replay | The gain is small and unproven live; it missed 3 buys, two of which rose ~4% |
| 5 | **MACD turns — "Macd swing"** | 15-minute and hourly MACD turn up with the 4-hour rising; −4% stop, +7% target, 10 days | US +0.27% a trade (+10%/yr, far under the index); Germany about +0.07% (unproven); India +12%/yr in 2021–26, −4%/yr in 2015–20 | Germany: paper −0.6% a trade (42), real −0.7% (39 small trades). US: 0.0% (26). India: +4.8% a trade on 12, in a rally | Simple; real edge before costs in the US and India tests | Loses in falling or choppy markets; Germany has shown nothing in test or live; ₹5,000 seats lose to the ₹16 fee |
| 6 | **Floor orders — "Floor test", "Low buys"** | Limit buys resting at the 3-day low, half a seat and half 2% lower | A real per-trade edge over buying at the open, but about zero after costs on the top-30 list; Germany with the old month+day filter +9.5%/yr | Germany +1.1% a trade (31 sold, +4.5%); month-trend copies positive on 3–4 trades; US −1.3% a trade (40); India "Low buys" +3.8% (15) | Buys dips at a price, never chases; small drawdowns with the trend filter | US loses in test and live; the "no target" copy is worst; few trades, so slow to judge |
| 7 | **Campaigns** | Starts near the monthly support, swings between the day's lows and highs for weeks, ends when the MACD turns down | Never backtested as built. Its cousins were: channel swings and buying at support — both "no edge, not adopted" | Germany +0.35% a trade (98 sold, 67% won, +7.1% booked) but €48 down on 17 still open; US −0.2%; India −0.3% on 177 | High win rate in Germany | Open losses eat most of the booked gain; India and the US lose; no backtest behind it |
| 8 | **Monthly low + AI outlook** ("Ai low buy-algo exit", "AI low + MACD turns") | Stocks at their monthly low, the AI news outlook says yes, you tap Buy or Skip | Without the AI the picker is no better than the usual list; the AI part cannot be backtested | −1.3% a trade (6) and −0.2% (19) | It records what the AI turned down, so its value will show | Too few trades to say anything; many requests lapse unanswered |
| 9 | **Quality · cheap · uptrend** | Sound business, cheapest of those, only above its 200-day average | +21%/yr vs QQQ +20% — but +15% vs +20% before 2026; the catch-up was one year of memory-chip stocks | Positive in all three markets, on 4, 4 and 1 trades | The only idea that is positive everywhere on the phone; low turnover, so low costs | The backtest lead is one sector in one year; 9 trades prove nothing |
| 10 | **Trend filters and market gates** ("· trend" copies, Nifty gate) | Buy only when the stock's (or the index's) trend is rising | Mostly "trades less, not better"; the stock trend filter made every Macd swing worse; only the Nifty daily-MACD gate was positive in both eras (best of six tries) | Trend copies: mixed, on 3–30 trades. Nifty-gate copy starts Monday | Cuts the worst falls (floor orders: −30% to −12%) | Gives up most of the good years; the classic 200-day filter was the worst of all |
| 11 | **Exits** | 4% stop checked at each 2-hour close; far targets; trailing stop on the watchlist | Keep the 4% stop from the buy price; trailing, ATR/2 and Bollinger stops rejected; near targets (+3–4%) cost ~0.2% a trade; a resting broker stop sells on brief dips | India's +3–4% targets sold the winners early (5 Nov) — as the test predicted; since fixed | The stop rule held on 4 of 5 datasets | Short targets cap the few big winners the whole approach lives on |
| 12 | **Crypto** ("Hold above SMA50", the MACD crypto algos) | Hold a coin only while above its 50-day average; or MACD turns | 50-day hold +16–31%/yr vs +2–10% for every MACD algo; it is crash protection that gives up part of the rallies | Hold (real): 2 trades, −5.3% a trade. "A fast" paper: about +13% since August (22 trades) | The hold rule halves the worst falls and trades 20 times a year | MACD crypto algos trade 55–325 times a year and pay it all in fees |
| 13 | **Old India system** (dual scores, daily trailing, soft and hard snipers) | Scores on your holdings; trailing GTTs; sniper stops on a MACD flip | Not backtested on the PC | Dormant: all 7 India holdings are watchlist-managed, and since 12 Nov it only touches stocks that are yours alone | Protects a hand-bought stock nothing else manages | Was fighting the watchlist over the same orders; about 25 settings for something with nothing to act on |
| 14 | **Index timing, monthly saving, score pools** | Rules on the index itself; €100 a month; pools picked by the app's scores | No rule beat buy-and-hold in 2–4 of ~20 years; score pools no better than random pools | Not on the phone (PC only) | Honest baseline | Nothing here adds return |
| 15 | **Swings on your own holdings** ("Portfolio · floor / MACD swings") | The floor and MACD-turn rules on the stocks you hold | Not backtested separately | Floor: +1.0% a trade (21). MACD: −0.9% (32) | The floor version repeats the Germany floor result | The MACD version repeats the Germany Macd swing result |

## What I would do with this

1. **Stop the clear losers** (paper only, so nothing is lost): the 15-minute scalps in all markets, "no target" copies,
   US Floor test, India Campaigns, "Macd swing · DE copy" in the US.
2. **Keep watching, unchanged:** Germany Floor test and its month-trend copies, Quality · cheap · uptrend, the Nifty-gate
   copy beside the plain India Macd swing, Germany Campaigns (watch the open losses).
3. **Real money:** nothing new goes live on this evidence. The only real winner is the US conservative algo, on 14 trades.
4. **Note:** the config-file algos' paper ledgers charge no costs at all (the cost fields in Settings are all zero), so
   their own numbers on the phone look about 0.2–0.5% a trade better than they are.

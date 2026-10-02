# Floor orders ("Hierarchical MACD") — spec for a backtest

A buy-low / sell-later strategy. It runs **paper only** in the SignalScope app
(German space, algo name "Hierarchical macd"). This file describes exactly what
the app does, so a backtest elsewhere can reproduce it.

## Read this first: what it is NOT

This is **not 3b** (the MACD-gate money manager) and **not the "dots" strategy**.
None of the following apply:

| Not used | What happens instead |
|---|---|
| 3b screener (90-day return / volatility / time above the 50-day average) | Universe = top 30 by **return on capital** |
| A seat "owning" a stock and waiting for it | Seats are generic. Whichever order fills first takes a free seat. |
| Waiting for "all dots green" | Only the **1M and 1D** MACD slopes are checked. The 4H, 1H and 15m dots are **not used**. |
| "Natural exit" when the dots turn red | **Time exit**: sold at the close 3 trading days after buying |
| Seat compounding (a +20% seat trades its next stock larger) | **Fixed** seat size; profits never resize a seat |
| Stop loss / target | None |

"Hierarchical" refers to the MACD timescale ladder. The algo uses only its top
two levels (month and day) as a trend filter. Entry and exit are price and time
rules, not signals.

## Parameters (defaults)

| Parameter | Default |
|---|---|
| Capital | 1000 (EUR) |
| Seats (stocks held at once) | 5 → seat = 200, each order = half a seat = 100 |
| Universe size | 30 |
| Low lookback | 3 trading days |
| Second order below the low | 2.0 % |
| Hold | 3 trading days |
| Costs | none: no fees, no slippage, no FX, no data delay |

## Universe

- The market's Discovery scan (Germany: the "DAX 100" list, already cut by a
  median-turnover floor at scan time).
- Keep stocks with a return-on-capital figure (Yahoo's return-on-equity value,
  shown as ROCE in the app). Sort descending and take the top 30.
- Refreshed **once per exchange day** from the latest complete scan.
- Stocks that drop out of the top 30 keep being managed until their exit.
- ⚠ Backtest caveat: this is a **snapshot of today's fundamentals**. Applying it
  to past dates is look-ahead and survivorship biased. Either accept that and say
  so, or use point-in-time fundamentals.

## MACD slope (the only indicator)

For a series of closes:

```
ema(x, span): a = 2/(span+1); out[0] = x[0]; out[i] = a*x[i] + (1-a)*out[i-1]
macd  = ema(close, 12) - ema(close, 26)
raw   = [0] + [macd[i] - macd[i-1] for i >= 1]
slope = ema(raw, 5)[-1]          # smoothing 5 for stocks
rising = slope > 0               # need >= 30 closes, else "unknown" (fails the filter)
```

- **1D**: daily closes (Yahoo, split-adjusted, about 2 years) **strictly before** the
  plan day.
- **1M**: monthly closes (Yahoo, 10 years), **completed months only**. The
  current month is excluded.
- Filter: a stock gets orders only if **1D rising AND 1M rising**.
- In the app, the daily slope is checked first. The monthly slope is only looked
  up when the daily one passes. This doesn't change the result.

## Daily cycle (exchange time zone, e.g. Europe/Berlin)

### 1. Plan (weekdays, from 06:00 local, once per day)

For each universe stock **not currently held**, in return-on-capital order:

```
if not (rising_1M and rising_1D): skip
floor = min(low of all regular-session 15-min bars of the last 3 trading days before today)
order1 = limit at floor,          amount = seat/2
order2 = limit at floor * (1-2%), amount = seat/2
```

"Trading days" means dates that have bars. Holidays don't count. An order only
counts bars that start **after it was placed**. In a backtest, placement is
before the open, so the whole session counts.

### 2. Fills (during the plan day only)

For each order half, independently:

```
first 15-min bar of the day with  bar.low < limit   (strictly below; touching is not a fill)
fill price = bar.open if bar.open < limit else limit   (a gap down fills at the better open)
qty = amount / fill price
```

Both halves can fill in the same bar. Unfilled halves **expire at the close**.
Nothing carries over to the next day.

### 3. Seats: who gets one

Process all fills of the day sorted by **(bar time, return-on-capital rank,
first half before second)**:

- **First fill of a stock that day**:
  - Count busy seats. A trade is busy on day D if it was bought on or before D
    and not yet sold, or sold at the close of D or later. The sell day still
    counts as busy, because the stock is sold at the close.
  - If busy < seats, open a trade.
  - Otherwise record a **MISSED** trade. It is followed for statistics only and
    never enters the P&L.
- **Second half filling later**: added to that same trade, whether it is open or
  missed.
- One trade per stock per day. A held stock gets no new orders until it is sold.

### 4. Exit

- Sell the whole position at the **close of the 3rd trading day after the buy
  day** (counted in days with data).
- Exit price = the close of that day's last 15-min bar. A daily-bar backtest can
  use the official daily close; the two differ slightly.
- `pnl = qty * exit_price - cost`, where `cost` = the money spent on the filled
  halves (100 or 200).
- There is no other exit.

## Statistics tracked per fill (including MISSED)

- `entry` = cost / qty. This is the average price when both halves filled.
- `ret1`, `ret3`, `ret5` = close of the 1st, 3rd and 5th trading day after the
  buy day ÷ entry − 1.
- `dip` = lowest low from the fill bar (inclusive) through the 5th trading day
  after the buy ÷ entry − 1.
- Tracking stops after 30 calendar days.

## Using daily bars instead of 15-min bars

Yahoo keeps only about 60 days of 15-min data, so a long backtest will likely use
daily bars. That is a close approximation:

- floor = min of the daily lows of the previous 3 days
- fill if daily low < limit, at the open if the open < limit
- exit at the daily close

What changes: the **order of fills within a day is unknown**, so when seats run
short, break ties by return-on-capital rank. The app would use actual fill time.

## Reference: the replay this came from

The replay covered 30 watchlist stocks, 60 days of 15-min bars, and only days
with 1M and 1D rising. It had no seat limit and no costs.

| Entry | Buys | Avg 3-day return | Up after 3 days | Median dip after buying |
|---|---|---|---|---|
| Limit at the 3-day low only | 75 | +0.44 % | 54 % | −2.23 % |
| **Split: low and −2 % (this algo)** | 75 | **+0.60 %** | 55 % | −2.07 % |
| Buy at market in the bottom 20 % of the range | 100 | +0.19 % | — | — |

A backtest with seats, fixed sizing and time exits should land near these
figures. If it doesn't, compare the fill rule (strictly below) and the
trading-day counting first.

# Algos overview — every space

Generated 31 Oct 2026 21:42 from the phone's own records. BOOK = the algo's paper record (the strategy's own prices); REAL = what the broker filled; REPLAY = the 💡 run over all fetchable history; VALIDATED = the backtest the config file was approved with. Percentages are before charges unless the replay says otherwise.

> **Read from copies taken earlier, not live from the phone** (`C:\Users\gupta\AppData\Local\Temp/algos_overview_data`). Rows marked "not read" and any missing section need a fresh read: run this script with the phone connected.

## Germany

| Algo | Money | Seats · list | Book (booked) | Replay /yr | Replay worst drop |
|---|---|---|---|---|---|
| B_Conservative | paper — real money off | 5 · 106 | -2.5% (5 closed) | +28.7% | 16.7% |
| Conservative high ROC set | paper — real money off | 3 · 27 | -11.0% (11 closed) | +1.0% | 31.9% |
| Test A fast 60/30 | paper | 5 · 358 | +1.0% (12 closed) | +8.1% | 2.3% |
| Test B aggressive 240/120 | paper | 5 · 358 | -2.1% (4 closed) | +10.8% | 17.4% |
| Test B0 progenitor 240/120 | paper | 5 · 358 | -3.5% (6 closed) | -3.9% | 24.8% |
| Test C slow 240/120 | paper | 5 · 358 | -3.3% (5 closed) | +5.6% | 20.5% |
| Test D daily decel | paper | 5 · 358 | -0.1% (3 closed) | +8.4% | 22.7% |
| Test HF 15m scalp | paper | 5 · 358 | -1.1% (44 closed) | -41.3% | 12.3% |
| Quality · cheap · uptrend | paper | 5 · 13 | +0.0% (0 closed) | — | — |

### B_Conservative

- **Money:** paper — €500 capital (a €1,000 real budget is set, but real orders are switched off)
- **Config:** `GERMANY_105_conservative_slower-exit_RECOMMENDED.json`
- **Seats (MACD gate 3b):** each day the 106 companies are ranked and the top 5 whose daily MACD slope is rising (smoothing 5) take a seat; a seat whose slope turns down is given up once its trade has closed. A seat's money grows or shrinks with its trades.
- **Entry:** Judged on 4-hour candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); the MACD slope is above zero; MACD acceleration is positive, at least 0.7 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price above its 50-candle EMA.
- **Exit:** Checked on 2-hour candles; any of these sells: the MACD slope turns down (smoothed over 10 candles); stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy).
- **Book:** -2.48% booked, 5 closed trades (2 won); open now: AOF, SRT3
- **Real:** 22 real sales, 11 won, €-68.28 before charges, average -0.99% a sale; nothing held now
- **Replay:** +28.7%/yr (+94.4% over 2.6 yr), worst drop 16.7%, 217 trades, slippage 0.2% — computed 21 Oct 2026
- **Validated:** RECOMMENDED for Germany; germany105_2023_26_continuous: +26.2%/yr, worst drop 9.6%

### Conservative high ROC set

- **Money:** paper — €2,000 capital (a €900 real budget is set, but real orders are switched off)
- **Config:** `B_SLOW_240m-120m_STOCKS-conservative_nasdaq+26pct-yr_lower-drawdown.json`
- **Seats (MACD gate 3b):** each day the 27 companies are ranked and the top 3 whose daily MACD slope is rising (smoothing 5) take a seat; a seat whose slope turns down is given up once its trade has closed. A seat's money grows or shrinks with its trades.
- **Entry:** Judged on 4-hour candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); the MACD slope is above zero; MACD acceleration is positive, at least 0.7 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price above its 50-candle EMA.
- **Exit:** Checked on 2-hour candles; any of these sells: the MACD slope turns down (smoothed over 2 candles); stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy).
- **Book:** -11.01% booked, 11 closed trades (2 won); open now: GXI
- **Replay:** +1.0%/yr (+2.6% over 2.6 yr), worst drop 31.9%, 204 trades, slippage 0.2% — computed 25 Oct 2026
- **Validated:** REFINED CHAMPION - CONSERVATIVE (lower drawdown, lesser profit); nasdaq_2022_26_top20: +25.8%/yr; nasdaq_2016_21_top20: +12.8%/yr; nasdaq_2022_26_top8: +42.4%/yr; nasdaq_2016_21_top8: +25.2%/yr; sp_ex_ndx_2022_26_top20: -2.3%/yr

### Test A fast 60/30

- **Money:** paper — €1,000 capital
- **Config:** `preset`
- **Seats (MACD gate 3b):** each day the 358 companies are ranked and the top 5 whose daily MACD slope is rising (smoothing 5) take a seat; a seat whose slope turns down is given up once its trade has closed. A seat's money grows or shrinks with its trades.
- **Book:** +0.98% booked, 12 closed trades (7 won); open now: TMV
- **Replay:** +8.1%/yr (+0.9% over 0.1 yr), worst drop 2.3%, 34 trades, slippage 0.05% — computed 25 Oct 2026

### Test B aggressive 240/120

- **Money:** paper — €1,000 capital
- **Config:** `B_SLOW_240m-120m_STOCKS-aggressive_nasdaq+27pct-yr_higher-gain-higher-dd.json`
- **Seats (MACD gate 3b):** each day the 358 companies are ranked and the top 5 whose daily MACD slope is rising (smoothing 5) take a seat; a seat whose slope turns down is given up once its trade has closed. A seat's money grows or shrinks with its trades.
- **Entry:** Judged on 4-hour candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); the MACD slope is above zero; MACD acceleration is positive, at least 0.5 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price above its 50-candle EMA.
- **Exit:** Checked on 2-hour candles; any of these sells: the MACD slope turns down (smoothed over 2 candles); stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy).
- **Book:** -2.09% booked, 4 closed trades (1 won); open now: GFT, MLP, SLSA
- **Replay:** +10.8%/yr (+30.9% over 2.6 yr), worst drop 17.4%, 277 trades, slippage 0.2% — computed 25 Oct 2026
- **Validated:** REFINED CHAMPION - AGGRESSIVE (higher gain, higher drawdown); nasdaq_2022_26_top20: +26.7%/yr; nasdaq_2016_21_top20: +13.8%/yr; nasdaq_2022_26_top8: +44.4%/yr; nasdaq_2016_21_top8: +25.4%/yr; sp_ex_ndx_2022_26_top20: -2.2%/yr

### Test B0 progenitor 240/120

- **Money:** paper — €1,000 capital
- **Config:** `B0_PROGENITOR_240m-120m_full-featured_SUPERSEDED-by-refined.json`
- **Seats (MACD gate 3b):** each day the 358 companies are ranked and the top 5 whose daily MACD slope is rising (smoothing 5) take a seat; a seat whose slope turns down is given up once its trade has closed. A seat's money grows or shrinks with its trades.
- **Entry:** Judged on 4-hour candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); the MACD slope is above zero; MACD acceleration is positive, at least 0.7 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price velocity over 10 candles above 0.5; price above its 50-candle EMA.
- **Exit:** Checked on 2-hour candles; any of these sells: the MACD slope turns down (smoothed over 2 candles); stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy); the fast MACD exit.
- **Book:** -3.46% booked, 6 closed trades (2 won); open now: GFT, MLP
- **Replay:** -3.9%/yr (-9.9% over 2.6 yr), worst drop 24.8%, 374 trades, slippage 0.2% — computed 25 Oct 2026

### Test C slow 240/120

- **Money:** paper — €1,000 capital
- **Config:** `C_SLOW_240m-120m_crypto-binance-fee-survivor_+3pct-yr-only.json`
- **Seats (MACD gate 3b):** each day the 358 companies are ranked and the top 5 whose daily MACD slope is rising (smoothing 5) take a seat; a seat whose slope turns down is given up once its trade has closed. A seat's money grows or shrinks with its trades.
- **Entry:** Judged on 4-hour candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); MACD acceleration is positive, at least 1 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price above its 50-candle EMA.
- **Exit:** Checked on 2-hour candles; any of these sells: the MACD slope turns down (smoothed over 2 candles); stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy).
- **Book:** -3.26% booked, 5 closed trades (1 won); open now: GFT, MLP
- **Replay:** +5.6%/yr (+15.5% over 2.6 yr), worst drop 20.5%, 245 trades, slippage 0.2% — computed 25 Oct 2026

### Test D daily decel

- **Money:** paper — €1,000 capital
- **Config:** `D_NSE_1D-240m_daily-decel-exit_ANDROID.json`
- **Seats (MACD gate 3b):** each day the 358 companies are ranked and the top 5 whose daily MACD slope is rising (smoothing 5) take a seat; a seat whose slope turns down is given up once its trade has closed. A seat's money grows or shrinks with its trades.
- **Entry:** Judged on daily candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); the MACD slope is above zero; MACD acceleration is positive, at least 0.7 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price above its 50-candle EMA.
- **Exit:** Checked on 4-hour candles; any of these sells: stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy); MACD acceleration turns negative.
- **Book:** -0.13% booked, 3 closed trades (1 won); open now: MLP
- **Replay:** +8.4%/yr (+23.7% over 2.6 yr), worst drop 22.7%, 140 trades, slippage 0.2% — computed 23 Oct 2026
- **Validated:** UNCONFIRMED LEAD — NSE daily-clock deceleration exit, do not treat as a champion; nse500_2020_26: +42.8%/yr; vs_champion_on_same_clock_no_decel: +19.5%/yr

### Test HF 15m scalp

- **Money:** paper — €1,000 capital
- **Config:** `HF_GERMANY_15m_flip1-scalp_zero-fee-only_+19pct-60d.json`
- **Seats (MACD gate 3b):** each day the 358 companies are ranked and the top 5 whose daily MACD slope is rising (smoothing 5) take a seat; a seat whose slope turns down is given up once its trade has closed. A seat's money grows or shrinks with its trades.
- **Entry:** Judged on 15-minute candles; all of these must hold: the MACD slope turns up (smoothed over 1 candles); price above its 50-candle EMA.
- **Exit:** Checked on 15-minute candles; any of these sells: the MACD slope turns down (smoothed over 1 candles).
- **Book:** -1.11% booked, 44 closed trades (10 won); nothing open
- **Replay:** -41.3%/yr (-10.2% over 0.2 yr), worst drop 12.3%, 276 trades, slippage 0.0% — computed 25 Oct 2026

### Quality · cheap · uptrend

- **Money:** paper — €500 capital
- **Config:** `GERMANY_105_conservative_slower-exit_RECOMMENDED.json`
- **Seats (MACD gate 3b):** each day the 13 companies are ranked and the top 5 whose daily MACD slope is rising (smoothing 5) take a seat; a seat whose slope turns down is given up once its trade has closed. A seat's money grows or shrinks with its trades.
- **Entry:** Judged on 4-hour candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); the MACD slope is above zero; MACD acceleration is positive, at least 0.7 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price above its 50-candle EMA.
- **Exit:** Checked on 2-hour candles; any of these sells: the MACD slope turns down (smoothed over 10 candles); stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy).
- **Quality list:** from 2026-Q3; news veto on, 22 verdicts, 0 kept out.
- **Book:** +0.00% booked, 0 closed trades (0 won); open now: G24, SHL
- **Replay:** no replay yet
- **Validated:** RECOMMENDED for Germany; germany105_2023_26_continuous: +26.2%/yr, worst drop 9.6%

### Floor algos (Germany) — paper only, by design

- **Floor test** (on) — buys at the 3-day low (half there, half 2.0% lower), 5 seats, 30 companies. Sells: stop −4.0%, target +7.0%, after 10 days. 17 sold (8 won), +1.28% a trade before charges; 5 open, +0.54% on average now; open now: 2GB, AT1, JUN3, NDA, UTDI. Replay: no replay yet.
- **Macd swing** (on) — macd algo; 20 seats, 40 companies, list: top. 40 sold (25 won), +2.40% a trade before charges; 18 open, +0.09% on average now; open now: AT1, BMW, DTE, FNTN, FTK, GBF, HEI, JUN3, KGX, LPK, MTX, PAH3, PFSE, SZG, UTDI, VOW, VOW3, ZAL. Replay: no replay yet.
- **Floor test · trend** (on) — buys at the 3-day low (half there, half 2.0% lower), 5 seats, 30 companies; only when the monthly and daily trend rise. Sells: stop −4.0%, target +7.0%, after 10 days, or when the trend breaks. 1 sold (1 won), +7.00% a trade before charges; 3 open, -0.60% on average now; open now: 2GB, LPK, S92. Replay: no replay yet.
- **Macd swing · trend** (on) — macd algo; 20 seats, 40 companies, list: top. 14 sold (7 won), +0.62% a trade before charges; 3 open, +0.40% on average now; open now: BMW, HEI, ZAL. Replay: no replay yet.
- **Campaigns DE** (on) — campaign algo; 0 seats, 30 companies, list: all. 37 sold (26 won), +0.38% a trade before charges; 6 open, -0.83% on average now; open now: FTK, HOT, PFSE, SMHN, WAF, WCH. Replay: no replay yet.
- **Ai low buy-algo exit** (on) — chain algo; 7 seats, 30 companies, list: all. 1 sold (0 won), -5.81% a trade before charges; 1 open, -0.88% on average now; open now: SZG. Replay: no replay yet.
- **Macd swing · no target** (on) — macd algo; 20 seats, 40 companies, list: top. 15 sold (0 won), -4.06% a trade before charges; 19 open, +2.42% on average now; open now: 2GB, AT1, BAYN, CBK, DBK, DWS, EVT, FTK, GBF, HEI, JUN3, KGX, PAH3, PFSE, SMHN, VH2, VOW, VOW3, ZAL. Replay: no replay yet.
- **Floor test · no target** (on) — buys at the 3-day low (half there, half 2.0% lower), 5 seats, 30 companies. Sells: stop −4.0%, after 10 days. 4 sold (0 won), -4.00% a trade before charges; 5 open, +3.45% on average now; open now: JUN3, SDF, SZG, TPE, UTDI. Replay: no replay yet.
- **AI low + MACD turns** (on) — macd algo; 20 seats, 30 companies, list: monthlylow. 4 sold (2 won), +1.09% a trade before charges; 5 open, -0.30% on average now; open now: BAYN, FNTN, JEN, NDX1, SZG. Replay: no replay yet.
- **Floor · month trend · no trend sale** (on) — buys at the 3-day low (half there, half 2.0% lower), 5 seats, 30 companies; only when the monthly and daily trend rise. Sells: stop −4.0%, target +7.0%, after 10 days. 1 sold (1 won), +7.00% a trade before charges; 3 open, -0.60% on average now; open now: 2GB, LPK, S92. Replay: no replay yet.
- **Floor · month trend · 3-day hold** (on) — buys at the 3-day low (half there, half 2.0% lower), 5 seats, 30 companies; only when the monthly and daily trend rise. Sells: stop −0.0%, after 3 days. 0 sold (0 won), — a trade before charges; 3 open, -1.40% on average now; open now: 2GB, LPK, S92. Replay: no replay yet.

## USA

| Algo | Money | Seats · list | Book (booked) | Replay /yr | Replay worst drop |
|---|---|---|---|---|---|
| Us conservative | paper — real money off | 5 · 102 | +0.0% (11 closed) | +3.7% | 17.0% |
| Test A fast 60/30 | paper | 5 · 102 | -7.0% (9 closed) | -15.5% | 5.9% |
| Test B conservative 240/120 | paper | 5 · 102 | -5.1% (6 closed) | -0.8% | 29.0% |
| Test B0 progenitor 240/120 | paper | 5 · 102 | -2.8% (6 closed) | -0.7% | 38.8% |
| Test C slow 240/120 | paper | 5 · 102 | -3.8% (4 closed) | -2.3% | 24.8% |
| Test D daily decel | paper | 5 · 102 | -9.0% (5 closed) | +11.2% | 17.9% |
| Test HF 15m scalp | paper | 5 · 102 | -7.8% (56 closed) | -37.6% | 10.0% |
| Quality · cheap · uptrend | paper | 5 · 10 | +0.0% (0 closed) | — | — |

### Us conservative

- **Money:** paper — $2,000 capital (a $2,000 real budget is set, but real orders are switched off)
- **Config:** `B_SLOW_240m-120m_STOCKS-aggressive_nasdaq+27pct-yr_higher-gain-higher-dd.json`
- **Seats (MACD gate 3b):** each day the 102 companies are ranked and the top 5 whose daily MACD slope is rising (smoothing 5) take a seat; a seat whose slope turns down is given up once its trade has closed. A seat's money grows or shrinks with its trades.
- **Entry:** Judged on 4-hour candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); the MACD slope is above zero; MACD acceleration is positive, at least 0.5 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price above its 50-candle EMA.
- **Exit:** Checked on 2-hour candles; any of these sells: the MACD slope turns down (smoothed over 2 candles); stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy).
- **Book:** +0.00% booked, 11 closed trades (8 won); nothing open
- **Real:** 12 real sales, 8 won, $75.02 before charges, average +1.27% a sale; nothing held now
- **Replay:** +3.7%/yr (+10.2% over 2.7 yr), worst drop 17.0%, 227 trades, slippage 0.2% — computed 25 Oct 2026
- **Validated:** REFINED CHAMPION - AGGRESSIVE (higher gain, higher drawdown); nasdaq_2022_26_top20: +26.7%/yr; nasdaq_2016_21_top20: +13.8%/yr; nasdaq_2022_26_top8: +44.4%/yr; nasdaq_2016_21_top8: +25.4%/yr; sp_ex_ndx_2022_26_top20: -2.2%/yr

### Test A fast 60/30

- **Money:** paper — $1,000 capital
- **Config:** `A_FAST_60m-30m_lowfee-venues_crypto-ETH+161pct_nasdaq-top20.json`
- **Seats (MACD gate 3b):** each day the 102 companies are ranked and the top 5 whose daily MACD slope is rising (smoothing 5) take a seat; a seat whose slope turns down is given up once its trade has closed. A seat's money grows or shrinks with its trades.
- **Entry:** Judged on 1-hour candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); MACD acceleration is positive, at least 1 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price above its 50-candle EMA.
- **Exit:** Checked on 30-minute candles; any of these sells: the MACD slope turns down (smoothed over 2 candles); stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy).
- **Book:** -7.04% booked, 9 closed trades (2 won); nothing open
- **Replay:** -15.5%/yr (-1.5% over 0.1 yr), worst drop 5.9%, 21 trades, slippage 0.05% — computed 25 Oct 2026

### Test B conservative 240/120

- **Money:** paper — $1,000 capital
- **Config:** `B_SLOW_240m-120m_STOCKS-conservative_nasdaq+26pct-yr_lower-drawdown.json`
- **Seats (MACD gate 3b):** each day the 102 companies are ranked and the top 5 whose daily MACD slope is rising (smoothing 5) take a seat; a seat whose slope turns down is given up once its trade has closed. A seat's money grows or shrinks with its trades.
- **Entry:** Judged on 4-hour candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); the MACD slope is above zero; MACD acceleration is positive, at least 0.7 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price above its 50-candle EMA.
- **Exit:** Checked on 2-hour candles; any of these sells: the MACD slope turns down (smoothed over 2 candles); stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy).
- **Book:** -5.09% booked, 6 closed trades (2 won); nothing open
- **Replay:** -0.8%/yr (-2.0% over 2.7 yr), worst drop 29.0%, 223 trades, slippage 0.2% — computed 25 Oct 2026
- **Validated:** REFINED CHAMPION - CONSERVATIVE (lower drawdown, lesser profit); nasdaq_2022_26_top20: +25.8%/yr; nasdaq_2016_21_top20: +12.8%/yr; nasdaq_2022_26_top8: +42.4%/yr; nasdaq_2016_21_top8: +25.2%/yr; sp_ex_ndx_2022_26_top20: -2.3%/yr

### Test B0 progenitor 240/120

- **Money:** paper — $1,000 capital
- **Config:** `B0_PROGENITOR_240m-120m_full-featured_SUPERSEDED-by-refined.json`
- **Seats (MACD gate 3b):** each day the 102 companies are ranked and the top 5 whose daily MACD slope is rising (smoothing 5) take a seat; a seat whose slope turns down is given up once its trade has closed. A seat's money grows or shrinks with its trades.
- **Entry:** Judged on 4-hour candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); the MACD slope is above zero; MACD acceleration is positive, at least 0.7 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price velocity over 10 candles above 0.5; price above its 50-candle EMA.
- **Exit:** Checked on 2-hour candles; any of these sells: the MACD slope turns down (smoothed over 2 candles); stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy); the fast MACD exit.
- **Book:** -2.80% booked, 6 closed trades (3 won); nothing open
- **Replay:** -0.7%/yr (-1.9% over 2.7 yr), worst drop 38.8%, 330 trades, slippage 0.2% — computed 25 Oct 2026

### Test C slow 240/120

- **Money:** paper — $1,000 capital
- **Config:** `C_SLOW_240m-120m_crypto-binance-fee-survivor_+3pct-yr-only.json`
- **Seats (MACD gate 3b):** each day the 102 companies are ranked and the top 5 whose daily MACD slope is rising (smoothing 5) take a seat; a seat whose slope turns down is given up once its trade has closed. A seat's money grows or shrinks with its trades.
- **Entry:** Judged on 4-hour candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); MACD acceleration is positive, at least 1 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price above its 50-candle EMA.
- **Exit:** Checked on 2-hour candles; any of these sells: the MACD slope turns down (smoothed over 2 candles); stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy).
- **Book:** -3.82% booked, 4 closed trades (1 won); nothing open
- **Replay:** -2.3%/yr (-6.1% over 2.7 yr), worst drop 24.8%, 197 trades, slippage 0.2% — computed 25 Oct 2026

### Test D daily decel

- **Money:** paper — $1,000 capital
- **Config:** `D_NSE_1D-240m_daily-decel-exit_ANDROID.json`
- **Seats (MACD gate 3b):** each day the 102 companies are ranked and the top 5 whose daily MACD slope is rising (smoothing 5) take a seat; a seat whose slope turns down is given up once its trade has closed. A seat's money grows or shrinks with its trades.
- **Entry:** Judged on daily candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); the MACD slope is above zero; MACD acceleration is positive, at least 0.7 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price above its 50-candle EMA.
- **Exit:** Checked on 4-hour candles; any of these sells: stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy); MACD acceleration turns negative.
- **Book:** -9.01% booked, 5 closed trades (1 won); nothing open
- **Replay:** +11.2%/yr (+32.6% over 2.7 yr), worst drop 17.9%, 138 trades, slippage 0.2% — computed 25 Oct 2026
- **Validated:** UNCONFIRMED LEAD — NSE daily-clock deceleration exit, do not treat as a champion; nse500_2020_26: +42.8%/yr; vs_champion_on_same_clock_no_decel: +19.5%/yr

### Test HF 15m scalp

- **Money:** paper — $1,000 capital
- **Config:** `HF_GERMANY_15m_flip1-scalp_zero-fee-only_+19pct-60d.json`
- **Seats (MACD gate 3b):** each day the 102 companies are ranked and the top 5 whose daily MACD slope is rising (smoothing 5) take a seat; a seat whose slope turns down is given up once its trade has closed. A seat's money grows or shrinks with its trades.
- **Entry:** Judged on 15-minute candles; all of these must hold: the MACD slope turns up (smoothed over 1 candles); price above its 50-candle EMA.
- **Exit:** Checked on 15-minute candles; any of these sells: the MACD slope turns down (smoothed over 1 candles).
- **Book:** -7.80% booked, 56 closed trades (9 won); nothing open
- **Replay:** -37.6%/yr (-9.5% over 0.2 yr), worst drop 10.0%, 288 trades, slippage 0.0% — computed 25 Oct 2026

### Quality · cheap · uptrend

- **Money:** paper — $2,000 capital
- **Config:** `B_SLOW_240m-120m_STOCKS-aggressive_nasdaq+27pct-yr_higher-gain-higher-dd.json`
- **Seats (MACD gate 3b):** each day the 10 companies are ranked and the top 5 whose daily MACD slope is rising (smoothing 5) take a seat; a seat whose slope turns down is given up once its trade has closed. A seat's money grows or shrinks with its trades.
- **Entry:** Judged on 4-hour candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); the MACD slope is above zero; MACD acceleration is positive, at least 0.5 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price above its 50-candle EMA.
- **Exit:** Checked on 2-hour candles; any of these sells: the MACD slope turns down (smoothed over 2 candles); stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy).
- **Quality list:** from 2026-Q3; news veto on, 17 verdicts, 0 kept out.
- **Book:** +0.00% booked, 0 closed trades (0 won); open now: AMGN, REGN, ROP
- **Replay:** no replay yet
- **Validated:** REFINED CHAMPION - AGGRESSIVE (higher gain, higher drawdown); nasdaq_2022_26_top20: +26.7%/yr; nasdaq_2016_21_top20: +13.8%/yr; nasdaq_2022_26_top8: +44.4%/yr; nasdaq_2016_21_top8: +25.4%/yr; sp_ex_ndx_2022_26_top20: -2.2%/yr

### Floor algos (USA) — paper only, by design

- **Floor test** (on) — buys at the 3-day low (half there, half 2.0% lower), 5 seats, 30 companies. Sells: stop −4.0%, target +7.0%, after 10 days. 24 sold (9 won), -0.06% a trade before charges; 3 open, +1.71% on average now; open now: ADBE, CEG, META. Replay: no replay yet.
- **Macd swing test** (on) — macd algo; 5 seats, 30 companies, list: top. 15 sold (5 won), -0.81% a trade before charges; 2 open, +2.58% on average now; open now: COIN, PEP. Replay: no replay yet.
- **Campaigns US** (on) — campaign algo; 0 seats, 30 companies, list: all. 8 sold (3 won), -0.23% a trade before charges. Replay: no replay yet.
- **Macd swing · DE copy** (on) — macd algo; 20 seats, 40 companies, list: top. 38 sold (17 won), -0.75% a trade before charges; 12 open, +0.52% on average now; open now: CEG, CHTR, CMCSA, COIN, CPRT, DASH, DLTR, DXCM, LULU, PDD, TMUS, TTD. Replay: no replay yet.
- **Floor test · trend · DE copy** (on) — buys at the 3-day low (half there, half 2.0% lower), 5 seats, 30 companies; only when the monthly and daily trend rise. Sells: stop −4.0%, target +7.0%, after 10 days, or when the trend breaks. 4 sold (2 won), -0.95% a trade before charges; 4 open, +0.81% on average now; open now: AMAT, AMD, LRCX, SMCI. Replay: no replay yet.
- **Macd swing · trend · DE copy** (on) — macd algo; 20 seats, 40 companies, list: top. 7 sold (3 won), +0.23% a trade before charges; 5 open, +0.62% on average now; open now: CPRT, DLTR, LULU, PDD, TTD. Replay: no replay yet.

## India

| Algo | Money | Seats · list | Book (booked) | Replay /yr | Replay worst drop |
|---|---|---|---|---|---|
| Conservative | paper — real money off | 5 · 427 | -11.4% (4 closed) | -5.1% | 23.4% |
| Aggressive | paper | 5 · 427 | -13.9% (15 closed) | -5.8% | 20.2% |
| Test d | paper | 5 · 427 | -12.9% (14 closed) | -14.6% | 36.1% |
| Test A fast 60/30 | paper | 5 · 427 | -1.4% (2 closed) | -52.7% | 8.7% |
| Test B0 progenitor 240/120 | paper | 5 · 427 | -4.9% (4 closed) | -4.4% | 26.3% |
| Test C slow 240/120 | paper | 5 · 427 | -2.1% (2 closed) | -3.2% | 20.2% |
| Test HF 15m scalp | paper | 5 · 427 | -5.9% (28 closed) | -56.1% | 16.3% |
| Quality · cheap · uptrend | paper | 5 · 17 | +0.0% (0 closed) | — | — |

### Conservative

- **Money:** paper — ₹75,000 capital (a ₹50,000 real budget is set, but real orders are switched off)
- **Config:** `GERMANY_105_conservative_slower-exit_RECOMMENDED.json`
- **Seats (MACD gate 3b):** each day the 427 companies are ranked and the top 5 whose daily MACD slope is rising (smoothing 5) take a seat; a seat whose slope turns down is given up once its trade has closed. A seat's money grows or shrinks with its trades.
- **Entry:** Judged on 4-hour candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); the MACD slope is above zero; MACD acceleration is positive, at least 0.7 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price above its 50-candle EMA.
- **Exit:** Checked on 2-hour candles; any of these sells: the MACD slope turns down (smoothed over 10 candles); stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy).
- **Book:** -11.43% booked, 4 closed trades (1 won); nothing open
- **Real:** 8 real sales, 3 won, ₹-1,427.44 before charges, average -1.07% a sale (11 more sold by the Zerodha stop or target, not priced in this record); nothing held now
- **Replay:** -5.1%/yr (-13.3% over 2.7 yr), worst drop 23.4%, 127 trades, slippage 0.2% — computed 25 Oct 2026
- **Validated:** RECOMMENDED for Germany; germany105_2023_26_continuous: +26.2%/yr, worst drop 9.6%

### Aggressive

- **Money:** paper — ₹150,000 capital
- **Config:** `B_SLOW_240m-120m_STOCKS-aggressive_nasdaq+27pct-yr_higher-gain-higher-dd.json`
- **Seats (MACD gate 3b):** each day the 427 companies are ranked and the top 5 whose daily MACD slope is rising (smoothing 5) take a seat; a seat whose slope turns down is given up once its trade has closed. A seat's money grows or shrinks with its trades.
- **Entry:** Judged on 4-hour candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); the MACD slope is above zero; MACD acceleration is positive, at least 0.5 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price above its 50-candle EMA.
- **Exit:** Checked on 2-hour candles; any of these sells: the MACD slope turns down (smoothed over 2 candles); stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy).
- **Book:** -13.85% booked, 15 closed trades (1 won); open now: COFORGE
- **Replay:** -5.8%/yr (-15.0% over 2.7 yr), worst drop 20.2%, 197 trades, slippage 0.2% — computed 25 Oct 2026
- **Validated:** REFINED CHAMPION - AGGRESSIVE (higher gain, higher drawdown); nasdaq_2022_26_top20: +26.7%/yr; nasdaq_2016_21_top20: +13.8%/yr; nasdaq_2022_26_top8: +44.4%/yr; nasdaq_2016_21_top8: +25.4%/yr; sp_ex_ndx_2022_26_top20: -2.2%/yr

### Test d

- **Money:** paper — ₹150,000 capital
- **Config:** `D_NSE_1D-240m_daily-decel-exit_ANDROID.json`
- **Seats (MACD gate 3b):** each day the 427 companies are ranked and the top 5 whose daily MACD slope is rising (smoothing 5) take a seat; a seat whose slope turns down is given up once its trade has closed. A seat's money grows or shrinks with its trades.
- **Entry:** Judged on daily candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); the MACD slope is above zero; MACD acceleration is positive, at least 0.7 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price above its 50-candle EMA.
- **Exit:** Checked on 4-hour candles; any of these sells: stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy); MACD acceleration turns negative.
- **Book:** -12.87% booked, 14 closed trades (0 won); open now: COFORGE, PPLPHARMA
- **Replay:** -14.6%/yr (-34.7% over 2.7 yr), worst drop 36.1%, 143 trades, slippage 0.2% — computed 25 Oct 2026
- **Validated:** UNCONFIRMED LEAD — NSE daily-clock deceleration exit, do not treat as a champion; nse500_2020_26: +42.8%/yr; vs_champion_on_same_clock_no_decel: +19.5%/yr

### Test A fast 60/30

- **Money:** paper — ₹150,000 capital
- **Config:** `A_FAST_60m-30m_lowfee-venues_crypto-ETH+161pct_nasdaq-top20.json`
- **Seats (MACD gate 3b):** each day the 427 companies are ranked and the top 5 whose daily MACD slope is rising (smoothing 5) take a seat; a seat whose slope turns down is given up once its trade has closed. A seat's money grows or shrinks with its trades.
- **Entry:** Judged on 1-hour candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); MACD acceleration is positive, at least 1 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price above its 50-candle EMA.
- **Exit:** Checked on 30-minute candles; any of these sells: the MACD slope turns down (smoothed over 2 candles); stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy).
- **Book:** -1.41% booked, 2 closed trades (0 won); open now: COFORGE, PPLPHARMA
- **Replay:** -52.7%/yr (-7.1% over 0.1 yr), worst drop 8.7%, 26 trades, slippage 0.05% — computed 25 Oct 2026

### Test B0 progenitor 240/120

- **Money:** paper — ₹150,000 capital
- **Config:** `B0_PROGENITOR_240m-120m_full-featured_SUPERSEDED-by-refined.json`
- **Seats (MACD gate 3b):** each day the 427 companies are ranked and the top 5 whose daily MACD slope is rising (smoothing 5) take a seat; a seat whose slope turns down is given up once its trade has closed. A seat's money grows or shrinks with its trades.
- **Entry:** Judged on 4-hour candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); the MACD slope is above zero; MACD acceleration is positive, at least 0.7 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price velocity over 10 candles above 0.5; price above its 50-candle EMA.
- **Exit:** Checked on 2-hour candles; any of these sells: the MACD slope turns down (smoothed over 2 candles); stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy); the fast MACD exit.
- **Book:** -4.92% booked, 4 closed trades (0 won); open now: COFORGE
- **Replay:** -4.4%/yr (-11.5% over 2.7 yr), worst drop 26.3%, 299 trades, slippage 0.2% — computed 25 Oct 2026

### Test C slow 240/120

- **Money:** paper — ₹150,000 capital
- **Config:** `C_SLOW_240m-120m_crypto-binance-fee-survivor_+3pct-yr-only.json`
- **Seats (MACD gate 3b):** each day the 427 companies are ranked and the top 5 whose daily MACD slope is rising (smoothing 5) take a seat; a seat whose slope turns down is given up once its trade has closed. A seat's money grows or shrinks with its trades.
- **Entry:** Judged on 4-hour candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); MACD acceleration is positive, at least 1 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price above its 50-candle EMA.
- **Exit:** Checked on 2-hour candles; any of these sells: the MACD slope turns down (smoothed over 2 candles); stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy).
- **Book:** -2.13% booked, 2 closed trades (0 won); open now: COFORGE
- **Replay:** -3.2%/yr (-8.4% over 2.7 yr), worst drop 20.2%, 178 trades, slippage 0.2% — computed 25 Oct 2026

### Test HF 15m scalp

- **Money:** paper — ₹150,000 capital
- **Config:** `HF_GERMANY_15m_flip1-scalp_zero-fee-only_+19pct-60d.json`
- **Seats (MACD gate 3b):** each day the 427 companies are ranked and the top 5 whose daily MACD slope is rising (smoothing 5) take a seat; a seat whose slope turns down is given up once its trade has closed. A seat's money grows or shrinks with its trades.
- **Entry:** Judged on 15-minute candles; all of these must hold: the MACD slope turns up (smoothed over 1 candles); price above its 50-candle EMA.
- **Exit:** Checked on 15-minute candles; any of these sells: the MACD slope turns down (smoothed over 1 candles).
- **Book:** -5.88% booked, 28 closed trades (4 won); open now: PPLPHARMA
- **Replay:** -56.1%/yr (-16.3% over 0.2 yr), worst drop 16.3%, 247 trades, slippage 0.0% — computed 25 Oct 2026

### Quality · cheap · uptrend

- **Money:** paper — ₹75,000 capital
- **Config:** `GERMANY_105_conservative_slower-exit_RECOMMENDED.json`
- **Seats (MACD gate 3b):** each day the 17 companies are ranked and the top 5 whose daily MACD slope is rising (smoothing 5) take a seat; a seat whose slope turns down is given up once its trade has closed. A seat's money grows or shrinks with its trades.
- **Entry:** Judged on 4-hour candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); the MACD slope is above zero; MACD acceleration is positive, at least 0.7 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price above its 50-candle EMA.
- **Exit:** Checked on 2-hour candles; any of these sells: the MACD slope turns down (smoothed over 10 candles); stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy).
- **Quality list:** from 2026-Q3; news veto on, 16 verdicts, 0 kept out.
- **Book:** +0.00% booked, 0 closed trades (0 won); open now: COFORGE, IGIL, LTTS, TECHM
- **Replay:** no replay yet
- **Validated:** RECOMMENDED for Germany; germany105_2023_26_continuous: +26.2%/yr, worst drop 9.6%

### Floor algos (India) — paper only, by design

- **Low buys** (on) — buys at the 3-day low (half there, half 2.0% lower), 5 seats, 30 companies. Sells: stop −4.0%, target +7.0%, after 3 days. 19 sold (8 won), -0.22% a trade before charges; 4 open, -0.25% on average now; open now: KSB, POLICYBZR, SWIGGY, VISL. Replay: no replay yet.
- **Macd swing** (on) — macd algo; 5 seats, 30 companies, list: top. 12 sold (3 won), -1.09% a trade before charges; 2 open, -0.77% on average now; open now: AIAENG, HDBFS. Replay: no replay yet.
- **Campaigns IN** (on) — campaign algo; 20 seats, 30 companies, list: all. 105 sold (29 won), -0.56% a trade before charges; 1 open, -0.22% on average now; open now: MARUTI. Replay: no replay yet.
- **Floor test · DE copy** (on) — buys at the 3-day low (half there, half 2.0% lower), 5 seats, 30 companies. Sells: stop −4.0%, target +7.0%, after 10 days. 10 sold (2 won), -1.81% a trade before charges; 5 open, -1.01% on average now; open now: AFCONS, HAPPSTMNDS, KSB, SWIGGY, VISL. Replay: no replay yet.
- **Macd swing · DE copy** (on) — macd algo; 20 seats, 40 companies, list: top. 20 sold (5 won), -1.26% a trade before charges; 12 open, -0.36% on average now; open now: AIAENG, CMPDI, COCHINSHIP, GODREJPROP, HDBFS, HINDCOPPER, JUBLFOOD, MARUTI, MUTHOOTFIN, NIACL, VAML, ZENTEC. Replay: no replay yet.
- **Floor test · trend · DE copy** (on) — buys at the 3-day low (half there, half 2.0% lower), 5 seats, 30 companies; only when the monthly and daily trend rise. Sells: stop −4.0%, target +7.0%, after 10 days, or when the trend breaks. 5 sold (1 won), -2.06% a trade before charges; 1 open, -0.95% on average now; open now: ANANTRAJ. Replay: no replay yet.
- **Macd swing · trend · DE copy** (on) — macd algo; 20 seats, 40 companies, list: top. 0 sold (0 won), — a trade before charges. Replay: no replay yet.

## Crypto

| Algo | Money | Seats · list | Book (booked) | Replay /yr | Replay worst drop |
|---|---|---|---|---|---|
| B_SLOW_240m/120m_Stocksaggressive | paper | 5 · 2 | +6.7% (15 closed) | +6.2% | 13.5% |
| B_SLOW240/120mSTOCKSconservative | paper | 2 · 2 | -5.0% (8 closed) | +18.6% | 24.3% |
| C_SLOW240m-120crypto-fee, surv | paper — real money off | 1 · 1 | +0.0% (2 closed) | +3.8% | 20.1% |
| germany_daily_3b_native_parity | paper | 5 · 2 | +3.0% (40 closed) | +27.1% | 8.8% |
| A fast _0.2slippage | paper | 2 · 2 | +12.7% (21 closed) | +39.1% | 5.5% |
| C slow eth | paper — real money off | 1 · 1 | -5.4% (3 closed) | +11.9% | 30.2% |
| Test A fast 60/30 | paper | 2 · 2 | -1.3% (2 closed) | +57.2% | 4.4% |
| Test B0 progenitor 240/120 | paper | 2 · 2 | -6.3% (3 closed) | -3.0% | 25.9% |
| Test D daily decel | paper | 2 · 2 | -1.7% (1 closed) | +0.2% | 29.4% |
| Test HF 15m scalp | paper | 2 · 2 | +0.9% (17 closed) | +42.8% | 4.1% |
| Hold above SMA50 · daily | **REAL** USDC 560 | 8 · 8 | -10.5% (2 closed) | +10.4% | 41.0% |

### B_SLOW_240m/120m_Stocksaggressive

- **Money:** paper — USDC 1,000 capital
- **Config:** `B_SLOW_240m-120m_STOCKS-aggressive_nasdaq+27pct-yr_higher-gain-higher-dd.json`
- **Seats (MACD gate 3b):** each day the 2 companies are ranked and the top 5 whose daily MACD slope is rising (smoothing 2) take a seat; a seat whose slope turns down is given up once its trade has closed. A seat's money grows or shrinks with its trades.
- **Entry:** Judged on 4-hour candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); the MACD slope is above zero; MACD acceleration is positive, at least 0.5 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price above its 50-candle EMA.
- **Exit:** Checked on 2-hour candles; any of these sells: the MACD slope turns down (smoothed over 2 candles); stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy).
- **Book:** +6.67% booked, 15 closed trades (5 won); nothing open
- **Replay:** +6.2%/yr (+9.9% over 1.6 yr), worst drop 13.5%, 127 trades, slippage 0.2% — computed 25 Oct 2026
- **Validated:** REFINED CHAMPION - AGGRESSIVE (higher gain, higher drawdown); nasdaq_2022_26_top20: +26.7%/yr; nasdaq_2016_21_top20: +13.8%/yr; nasdaq_2022_26_top8: +44.4%/yr; nasdaq_2016_21_top8: +25.4%/yr; sp_ex_ndx_2022_26_top20: -2.2%/yr

### B_SLOW240/120mSTOCKSconservative

- **Money:** paper — USDC 100 capital
- **Config:** `B_SLOW_240m-120m_STOCKS-conservative_nasdaq+26pct-yr_lower-drawdown.json`
- **Seats (MACD gate 3b):** every company has its own seat; the daily MACD slope alone decides when each may trade.
- **Entry:** Judged on 4-hour candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); the MACD slope is above zero; MACD acceleration is positive, at least 0.7 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price above its 50-candle EMA.
- **Exit:** Checked on 2-hour candles; any of these sells: the MACD slope turns down (smoothed over 2 candles); stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy).
- **Book:** -5.04% booked, 8 closed trades (3 won); nothing open
- **Replay:** +18.6%/yr (+30.9% over 1.6 yr), worst drop 24.3%, 120 trades, slippage 0.2% — computed 25 Oct 2026
- **Validated:** REFINED CHAMPION - CONSERVATIVE (lower drawdown, lesser profit); nasdaq_2022_26_top20: +25.8%/yr; nasdaq_2016_21_top20: +12.8%/yr; nasdaq_2022_26_top8: +42.4%/yr; nasdaq_2016_21_top8: +25.2%/yr; sp_ex_ndx_2022_26_top20: -2.3%/yr

### C_SLOW240m-120crypto-fee, surv

- **Money:** paper — USDC 400 capital (a USDC 200 real budget is set, but real orders are switched off)
- **Config:** `C_SLOW_240m-120m_crypto-binance-fee-survivor_+3pct-yr-only.json`
- **Seats (MACD gate 3b):** every company has its own seat; the daily MACD slope alone decides when each may trade.
- **Entry:** Judged on 4-hour candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); MACD acceleration is positive, at least 1 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price above its 50-candle EMA.
- **Exit:** Checked on 2-hour candles; any of these sells: the MACD slope turns down (smoothed over 2 candles); stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy).
- **Book:** +0.00% booked, 2 closed trades (1 won); nothing open
- **Real:** 2 real sales, USDC -3.06 before charges; nothing held now
- **Replay:** +3.8%/yr (+6.0% over 1.6 yr), worst drop 20.1%, 45 trades, slippage 0.2% — computed 25 Oct 2026

### germany_daily_3b_native_parity

- **Money:** paper — USDC 1,000 capital
- **Config:** `germany_daily_3b_native_parity.json`
- **Seats (MACD gate 3b):** every company has its own seat; the daily MACD slope alone decides when each may trade.
- **Entry:** Judged on daily candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); the MACD slope is above zero; MACD acceleration is positive, at least 0.5 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price above its 50-candle EMA.
- **Exit:** Checked on daily candles; any of these sells: the MACD slope turns down (smoothed over 2 candles); stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy).
- **Book:** +3.01% booked, 40 closed trades (12 won); nothing open
- **Replay:** +27.1%/yr (+17.3% over 0.7 yr), worst drop 8.8%, 181 trades, slippage 0.2% — computed 25 Oct 2026

### A fast _0.2slippage

- **Money:** paper — USDC 1,000 capital
- **Config:** `A_FAST_60m-30m_0.2 slippage_crypto-ETH+161pct_nasdaq-top20.json`
- **Seats (MACD gate 3b):** every company has its own seat; the daily MACD slope alone decides when each may trade.
- **Entry:** Judged on 1-hour candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); MACD acceleration is positive, at least 1 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price above its 50-candle EMA.
- **Exit:** Checked on 30-minute candles; any of these sells: the MACD slope turns down (smoothed over 2 candles); stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy).
- **Book:** +12.73% booked, 21 closed trades (9 won); nothing open
- **Replay:** +39.1%/yr (+14.7% over 0.4 yr), worst drop 5.5%, 68 trades, slippage 0.2% — computed 25 Oct 2026

### C slow eth

- **Money:** paper — USDC 185 capital (a USDC 185 real budget is set, but real orders are switched off)
- **Config:** `C_SLOW_240m-120m_crypto-binance-fee-survivor_+3pct-yr-only.json`
- **Seats (MACD gate 3b):** every company has its own seat; the daily MACD slope alone decides when each may trade.
- **Entry:** Judged on 4-hour candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); MACD acceleration is positive, at least 1 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price above its 50-candle EMA.
- **Exit:** Checked on 2-hour candles; any of these sells: the MACD slope turns down (smoothed over 2 candles); stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy).
- **Book:** -5.45% booked, 3 closed trades (1 won); nothing open
- **Real:** 2 real sales, USDC 1.14 before charges; nothing held now
- **Replay:** +11.9%/yr (+19.4% over 1.6 yr), worst drop 30.2%, 46 trades, slippage 0.2% — computed 25 Oct 2026

### Test A fast 60/30

- **Money:** paper — USDC 1,000 capital
- **Config:** `A_FAST_60m-30m_lowfee-venues_crypto-ETH+161pct_nasdaq-top20.json`
- **Seats (MACD gate 3b):** every company has its own seat; the daily MACD slope alone decides when each may trade.
- **Entry:** Judged on 1-hour candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); MACD acceleration is positive, at least 1 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price above its 50-candle EMA.
- **Exit:** Checked on 30-minute candles; any of these sells: the MACD slope turns down (smoothed over 2 candles); stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy).
- **Book:** -1.32% booked, 2 closed trades (1 won); nothing open
- **Replay:** +57.2%/yr (+20.7% over 0.4 yr), worst drop 4.4%, 68 trades, slippage 0.05% — computed 25 Oct 2026

### Test B0 progenitor 240/120

- **Money:** paper — USDC 1,000 capital
- **Config:** `B0_PROGENITOR_240m-120m_full-featured_SUPERSEDED-by-refined.json`
- **Seats (MACD gate 3b):** every company has its own seat; the daily MACD slope alone decides when each may trade.
- **Entry:** Judged on 4-hour candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); the MACD slope is above zero; MACD acceleration is positive, at least 0.7 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price velocity over 10 candles above 0.5; price above its 50-candle EMA.
- **Exit:** Checked on 2-hour candles; any of these sells: the MACD slope turns down (smoothed over 2 candles); stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy); the fast MACD exit.
- **Book:** -6.27% booked, 3 closed trades (0 won); nothing open
- **Replay:** -3.0%/yr (-4.7% over 1.6 yr), worst drop 25.9%, 156 trades, slippage 0.2% — computed 25 Oct 2026

### Test D daily decel

- **Money:** paper — USDC 1,000 capital
- **Config:** `D_NSE_1D-240m_daily-decel-exit_ANDROID.json`
- **Seats (MACD gate 3b):** every company has its own seat; the daily MACD slope alone decides when each may trade.
- **Entry:** Judged on daily candles; all of these must hold: the MACD slope turns up (smoothed over 2 candles); the MACD slope is above zero; MACD acceleration is positive, at least 0.7 standard deviations; the slope is sustained (over 20 candles); early-recovery check (60% over 22 candles, price velocity confirming); price above its 50-candle EMA.
- **Exit:** Checked on 4-hour candles; any of these sells: stop-loss −4% from the buy, on the candle close; the MACD-distance rule (30%, measured from the MACD at the buy); MACD acceleration turns negative.
- **Book:** -1.71% booked, 1 closed trades (0 won); nothing open
- **Replay:** +0.2%/yr (+0.6% over 3.6 yr), worst drop 29.4%, 43 trades, slippage 0.2% — computed 25 Oct 2026
- **Validated:** UNCONFIRMED LEAD — NSE daily-clock deceleration exit, do not treat as a champion; nse500_2020_26: +42.8%/yr; vs_champion_on_same_clock_no_decel: +19.5%/yr

### Test HF 15m scalp

- **Money:** paper — USDC 1,000 capital
- **Config:** `HF_GERMANY_15m_flip1-scalp_zero-fee-only_+19pct-60d.json`
- **Seats (MACD gate 3b):** every company has its own seat; the daily MACD slope alone decides when each may trade.
- **Entry:** Judged on 15-minute candles; all of these must hold: the MACD slope turns up (smoothed over 1 candles); price above its 50-candle EMA.
- **Exit:** Checked on 15-minute candles; any of these sells: the MACD slope turns down (smoothed over 1 candles).
- **Book:** +0.89% booked, 17 closed trades (6 won); nothing open
- **Replay:** +42.8%/yr (+8.2% over 0.2 yr), worst drop 4.1%, 240 trades, slippage 0.0% — computed 25 Oct 2026

### Hold above SMA50 · daily

- **Money:** REAL — USDC 560 budget over 8 seats
- **Config:** `crypto_hold_sma50_daily.json`
- **Entry:** Buy when the daily close is above its 50-day average.
- **Exit:** Sell when the daily close falls below its 50-day average.
- **Book:** -10.51% booked, 2 closed trades (0 won); nothing open
- **Real:** 0 real sales, USDC 0.00 before charges; nothing held now
- **Replay:** +10.4%/yr (+65.4% over 5.1 yr), worst drop 41.0%, 369 trades, slippage 0.2% — computed 31 Oct 2026


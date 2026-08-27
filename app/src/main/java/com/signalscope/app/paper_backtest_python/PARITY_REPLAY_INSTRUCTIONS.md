# Parity Replay — verify the Android engine matches the desktop Python backtest

## Goal
Prove the Kotlin paper engine produces **the same trades and PnL** as the desktop Python engine
(`engine_reference.py` / `strategy_lab.py`) when given **identical data, config, and symbols**.
This is an **engine parity** check, before any live trading.

## Read this first — what NOT to do (common misconception)
- **Do NOT remove the first 3 months.** The "3 months" only ever existed as the *screener's lookback
  window for ranking stocks*. It is **not** removed from any simulation.
- **The screener is not part of this test.** Stock selection is a separate step (and the Android app
  doesn't have a screener). Parity is purely: same inputs → same engine output.
- The only start-of-series skip is the engine's internal **`warmupBars` (240 entry candles)** — it is
  identical in both engines and applied automatically. Do not trim it by hand.

## The correct method
Run the **same config** over the **same full dataset** for the **same fixed symbol list** in both
engines, then compare trade-for-trade. Use a handful of fixed symbols (e.g. 3–5) for a tight diff, or
the whole set for breadth.

---

## Build a REPLAY function in the Android app (separate from the live paper engine)

This is the "explicit separate Backtest/Replay action" the port brief allows. It must NOT touch the
live paper ledger, watermark, or broker.

1. **Input.** Let the user pick uploaded CSV file(s) in the cache format:
   `symbol,datetime,open,high,low,close,volume` (one file per symbol). These are the *same* files
   used by the desktop app.

2. **Normalize + resample EXACTLY like the spec (§1 of STRATEGY_LAB_PORT_SPEC.md):**
   - Parse timestamps; for US data treat them in `America/New_York` (the desktop stores ET). Use the
     timestamps as-is from the CSV — do not re-fetch or alter them.
   - Resample the fine bars to the **entry timeframe** (`config.resample`, e.g. 60min) and the
     **exit timeframe** (`config.exitBars`, e.g. 30min) using **left label, left-closed** windows:
     `open=first, high=max, low=min, close=last, volume=sum`; drop empty windows.
   - This must match `backtest.py: resample_ohlcv()` precisely, or every downstream bar shifts.

3. **Reuse the SAME pure strategy evaluator** (`UsPaperStrategy`) — do not write a second copy of the
   indicator/signal math. Feed it the resampled bars + the active config.

4. **Replay the WHOLE series chronologically** (this is the difference from live mode):
   - Process every bar from index 0; make decisions from `warmupBars` onward (internal, automatic).
   - **Bypass** the live-mode "evaluate only the latest closed bar / bootstrap / per-symbol watermark"
     rules — for replay you want every historical decision, exactly like the backtest.
   - One open position per symbol; same entry/exit precedence as the spec (§4–§5), including the
     multi-timeframe path (entries on entry candles, exits checked on the finer bars) when
     `exitBars != "same"`.

5. **Apply accounting identically** (spec §6 / `engine_reference.py: apply_slippage_and_compound`):
   - `quantity = notional / entryPrice`; `grossPct = (exit-entry)/entry*100`;
     `netPct = grossPct - slippagePct` (subtract slippage **once per closed trade**);
     `pnl = notional * netPct / 100`.
   - Per-symbol compounding: `equity *= (1 + netPct/100)` over that symbol's closed trades in order.

6. **Output** (per symbol and in total), for comparison:
   - Closed trades: `entryTime, entryPrice, exitTime, exitPrice, exitReason, netPct, pnl`.
   - Totals: total PnL, closed count, win rate, compounded %, max drawdown.
   - (Optional but ideal) a per-bar indicator dump for the first symbol: `MACD, MACD_SIGNAL,
     ENTRY_SLOPE, EXIT_SLOPE, ENTRY_ACCEL, ACCEL_SIGMA, LEVEL(EMA50), ADX` — for a 1e-6 indicator diff.

---

## Produce the desktop baseline to compare against
On the laptop (this backtester), run the **same config** on the **same dataset**, with the **same
symbols selected** (just select those tickers; **do not use the screener**), then Run. Every run writes
`runs/lab_reports/report_*.csv`, whose `[TRADES]` section lists each trade (entry/exit time, price,
reason, % return, PnL) and `[OVERALL]`/`[SYMBOLS]` give the totals. Use that as ground truth.
(Or ask the desktop assistant to dump a JSON of trades+indicators for the chosen symbols.)

---

## What must match (and tolerances)
- **Bar alignment:** identical resampled timestamps. (If these differ, fix resampling first — nothing
  else can match.)
- **Indicators:** `1e-6` relative tolerance. Watch the **Wilder ADX** (`alpha = 1/period`, not
  `2/(period+1)`) — this is the classic parity break.
- **Trades:** exact match on entry/exit **timestamps** and **exit-reason category**; entry/exit
  **prices** exact; `netPct` and `pnl` within a small rounding tolerance (the desktop rounds to 4 dp).
- **Totals:** total PnL, closed count, win rate, compounded %, max drawdown — within rounding.

## Pass criteria
For each test symbol over the full dataset: same number of trades, same entry/exit bars, same exit
reasons, and PnL matching to rounding. If trades diverge, bisect: first confirm indicator parity on a
fixed bar, then the entry hook, then the exit precedence (stop vs slope-flip vs MACD-distance qualifier).

## Reminder
Replay/parity is **simulation only** — it must never call `Trading212Client.placeOrder()` or write to
the live paper ledger. Delete or hide this temporary upload-and-replay action before shipping live.

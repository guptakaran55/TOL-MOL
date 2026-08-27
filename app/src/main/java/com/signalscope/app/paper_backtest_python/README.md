# SignalScope 15m Paper Backtester

This is a small Python project for testing the same paper strategy used by the Android live paper engine:

- BUY when the 5-bar-smoothed MACD slope flips from negative to positive.
- Entry is allowed only when `ADX >= 20` and `close > SMA200`.
- SELL when the 2-bar-smoothed MACD slope flips from positive to negative.
- Catastrophe exit when `close < entry * 0.85`.
- Each entry uses fixed `1000` notional, matching the Android simulator.

It writes:

- `summary.csv` - per-symbol performance.
- `trades.csv` - closed and open trades.
- `audit.csv` - every signal, gated signal, buy, sell, and disaster exit with MSID/smoothed MACD slope values.

## Setup

```powershell
cd C:\Users\gupta\Downloads\SignalScopeAndroid\SignalScopeAndroid\app\src\main\java\com\signalscope\app\paper_backtest_python
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

If this Windows shell says `python` is not recognized, this Codex runtime also works:

```powershell
& 'C:\Users\gupta\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' backtest.py --help
```

## Quick Yahoo Test

The script fetches recent intraday bars from Yahoo's chart endpoint. For 15m candles, use up to around `60d`.

PepsiCo is `PEP`, not `PEPS`.

```powershell
python backtest.py --source yahoo --symbols PEP MSCI CSGP --period 60d --interval 15m --out runs\us_60d
```

German/Xetra examples use Yahoo suffix `.DE`:

```powershell
python backtest.py --source yahoo --symbols SAP.DE SIE.DE ALV.DE AIR.DE DTE.DE --period 60d --interval 15m --out runs\dax_60d
```

## Longer History

For 6-12 months or years of 15-minute bars, use a paid/exported intraday data provider and feed the backtester CSV files.

One CSV per symbol:

```text
data\
  SAP.DE.csv
  SIE.DE.csv
  PEP.csv
```

Required CSV columns:

```text
datetime,open,high,low,close,volume
```

Then run:

```powershell
python backtest.py --source csv --csv-dir data --symbols SAP.DE SIE.DE PEP --resample 15min --out runs\csv_backtest
```

Or use one combined CSV with a `symbol` column:

```text
symbol,datetime,open,high,low,close,volume
SAP.DE,2026-01-02 08:00:00,100,101,99,100.5,123456
```

```powershell
python backtest.py --source csv --csv-file data\all_symbols_15m.csv --symbols SAP.DE SIE.DE PEP --out runs\csv_backtest
```

If the provider gives 5-minute bars instead of native 15-minute bars, add `--resample 15min`.

## How To Interpret Sparse Signals

One BUY in one day across only three symbols is normal for this strategy. It waits for a specific 15m MACD-slope re-hook and then applies ADX/SMA200 gates.

Use the `audit.csv` file to see whether symbols are:

- not firing because the slope did not flip;
- firing but gated by low ADX;
- firing but gated by price below SMA200;
- buying and later exiting.

For a faster experiment, test 20-50 liquid symbols over 60 days, then sort `summary.csv` by `total_closed_pnl`, `win_rate_pct`, and `closed`.

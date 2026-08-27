#!/usr/bin/env python
"""
15-minute paper backtester for the SignalScope MACD-slope strategy.

This mirrors the Android paper engine:
  BUY  when 5-bar-smoothed MACD slope flips negative to positive
       and ADX >= 20 and close > SMA200.
  SELL when 2-bar-smoothed MACD slope flips positive to negative.
  DISASTER EXIT when close < entry * 0.85.

Use Yahoo for quick recent 15m checks, or CSV files for deeper history.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable
from urllib.parse import quote
from urllib.request import Request, urlopen

import numpy as np
import pandas as pd


ENTRY_SMOOTHING = 5
EXIT_SMOOTHING = 2
MIN_ADX_FOR_ENTRY = 20.0
DISASTER_FLOOR = 0.85
NOTIONAL = 1000.0
MIN_BARS = 240


@dataclass
class OpenTrade:
    symbol: str
    entry_time: pd.Timestamp
    entry_price: float
    entry_reason: str


def ema(series: pd.Series, span: int) -> pd.Series:
    return series.ewm(span=max(2, span), adjust=False).mean()


def add_indicators(df: pd.DataFrame) -> pd.DataFrame:
    out = df.copy()
    close = out["close"]

    ema_fast = ema(close, 12)
    ema_slow = ema(close, 26)
    macd = ema_fast - ema_slow
    macd_signal = ema(macd, 9)
    macd_hist = macd - macd_signal

    raw_slope = macd.diff().fillna(0.0)
    slow_slope = ema(raw_slope, ENTRY_SMOOTHING)
    fast_slope = ema(raw_slope, EXIT_SMOOTHING)

    out["macd"] = macd
    out["macd_signal"] = macd_signal
    out["macd_hist"] = macd_hist
    out["smooth_macd_slope"] = slow_slope
    out["fast_smooth_macd_slope"] = fast_slope
    out["sma200"] = close.rolling(200).mean()
    out["adx"] = adx(out)
    return out


def adx(df: pd.DataFrame, period: int = 14) -> pd.Series:
    high = df["high"]
    low = df["low"]
    close = df["close"]

    up_move = high.diff()
    down_move = -low.diff()
    plus_dm = np.where((up_move > down_move) & (up_move > 0), up_move, 0.0)
    minus_dm = np.where((down_move > up_move) & (down_move > 0), down_move, 0.0)

    prev_close = close.shift(1)
    tr = pd.concat(
        [
            high - low,
            (high - prev_close).abs(),
            (low - prev_close).abs(),
        ],
        axis=1,
    ).max(axis=1)

    alpha = 1.0 / period
    atr = tr.ewm(alpha=alpha, adjust=False).mean()
    plus_di = 100.0 * pd.Series(plus_dm, index=df.index).ewm(alpha=alpha, adjust=False).mean() / atr
    minus_di = 100.0 * pd.Series(minus_dm, index=df.index).ewm(alpha=alpha, adjust=False).mean() / atr
    dx = ((plus_di - minus_di).abs() / (plus_di + minus_di).replace(0, np.nan)) * 100.0
    return dx.ewm(alpha=alpha, adjust=False).mean().fillna(0.0)


def normalize_ohlcv(raw: pd.DataFrame, symbol: str) -> pd.DataFrame:
    if raw.empty:
        return raw

    df = raw.copy()
    df.columns = [str(c).strip().lower().replace(" ", "_") for c in df.columns]

    if "datetime" not in df.columns:
        if "date" in df.columns and "time" in df.columns:
            df["datetime"] = df["date"].astype(str) + " " + df["time"].astype(str)
        elif "date" in df.columns:
            df["datetime"] = df["date"]
        elif df.index.name or isinstance(df.index, pd.DatetimeIndex):
            df = df.reset_index()
            first = df.columns[0]
            df = df.rename(columns={first: "datetime"})

    required = {"datetime", "open", "high", "low", "close"}
    missing = sorted(required - set(df.columns))
    if missing:
        raise ValueError(f"{symbol}: missing CSV columns: {', '.join(missing)}")

    if "volume" not in df.columns:
        df["volume"] = 0

    df["datetime"] = pd.to_datetime(df["datetime"], errors="coerce")
    df = df.dropna(subset=["datetime", "open", "high", "low", "close"])
    df = df.sort_values("datetime").drop_duplicates("datetime")
    df["symbol"] = symbol

    cols = ["symbol", "datetime", "open", "high", "low", "close", "volume"]
    for c in ["open", "high", "low", "close", "volume"]:
        df[c] = pd.to_numeric(df[c], errors="coerce")
    return df[cols].dropna(subset=["open", "high", "low", "close"]).reset_index(drop=True)


def resample_ohlcv(df: pd.DataFrame, rule: str) -> pd.DataFrame:
    if df.empty:
        return df
    symbol = str(df["symbol"].iloc[0])
    indexed = df.set_index("datetime").sort_index()
    bars = indexed.resample(rule, label="left", closed="left").agg(
        {
            "open": "first",
            "high": "max",
            "low": "min",
            "close": "last",
            "volume": "sum",
        }
    )
    bars = bars.dropna(subset=["open", "high", "low", "close"]).reset_index()
    bars.insert(0, "symbol", symbol)
    return bars[["symbol", "datetime", "open", "high", "low", "close", "volume"]]


def load_csv_data(csv_dir: Path | None, csv_file: Path | None, symbols: list[str]) -> dict[str, pd.DataFrame]:
    frames: dict[str, pd.DataFrame] = {}

    if csv_file:
        raw = pd.read_csv(csv_file)
        lowered = {c.lower(): c for c in raw.columns}
        if "symbol" in lowered:
            sym_col = lowered["symbol"]
            for symbol, group in raw.groupby(sym_col):
                if symbols and str(symbol).upper() not in {s.upper() for s in symbols}:
                    continue
                frames[str(symbol).upper()] = normalize_ohlcv(group.drop(columns=[sym_col]), str(symbol).upper())
        else:
            if len(symbols) != 1:
                raise ValueError("--csv-file without a symbol column needs exactly one --symbols value")
            frames[symbols[0].upper()] = normalize_ohlcv(raw, symbols[0].upper())

    if csv_dir:
        for symbol in symbols:
            candidates = [
                csv_dir / f"{symbol}.csv",
                csv_dir / f"{symbol.upper()}.csv",
                csv_dir / f"{symbol.replace('.', '_')}.csv",
                csv_dir / f"{symbol.upper().replace('.', '_')}.csv",
            ]
            path = next((p for p in candidates if p.exists()), None)
            if not path:
                raise FileNotFoundError(f"No CSV found for {symbol} in {csv_dir}")
            frames[symbol.upper()] = normalize_ohlcv(pd.read_csv(path), symbol.upper())

    return frames


def load_yahoo_data(symbols: list[str], period: str, interval: str) -> dict[str, pd.DataFrame]:
    frames: dict[str, pd.DataFrame] = {}
    for symbol in symbols:
        try:
            frames[symbol.upper()] = fetch_yahoo_symbol(symbol, period, interval)
        except Exception as exc:
            print(f"warn: no Yahoo data for {symbol}: {exc}")
    return frames


def fetch_yahoo_symbol(symbol: str, period: str, interval: str) -> pd.DataFrame:
    encoded = quote(symbol, safe="")
    url = (
        f"https://query1.finance.yahoo.com/v8/finance/chart/{encoded}"
        f"?range={quote(period)}&interval={quote(interval)}&events=history&includePrePost=false"
    )
    req = Request(url, headers={"User-Agent": "Mozilla/5.0 SignalScopeBacktest/1.0"})
    with urlopen(req, timeout=30) as resp:
        payload = json.loads(resp.read().decode("utf-8"))

    chart = payload.get("chart", {})
    if chart.get("error"):
        raise RuntimeError(chart["error"])
    results = chart.get("result") or []
    if not results:
        raise RuntimeError("empty chart result")

    result = results[0]
    timestamps = result.get("timestamp") or []
    quotes = ((result.get("indicators") or {}).get("quote") or [{}])[0]
    if not timestamps or not quotes:
        raise RuntimeError("missing timestamps or quotes")

    meta = result.get("meta") or {}
    dt = pd.to_datetime(timestamps, unit="s", utc=True)
    tz_name = meta.get("exchangeTimezoneName")
    if tz_name:
        try:
            dt = dt.tz_convert(tz_name)
        except Exception:
            pass

    raw = pd.DataFrame(
        {
            "datetime": dt,
            "open": quotes.get("open"),
            "high": quotes.get("high"),
            "low": quotes.get("low"),
            "close": quotes.get("close"),
            "volume": quotes.get("volume"),
        }
    )
    return normalize_ohlcv(raw, symbol.upper())


def backtest_symbol(symbol: str, df: pd.DataFrame) -> tuple[pd.DataFrame, pd.DataFrame, dict]:
    df = add_indicators(df)
    audits = []
    trades = []
    open_trade: OpenTrade | None = None

    for i in range(1, len(df)):
        if i < MIN_BARS:
            continue

        row = df.iloc[i]
        prev = df.iloc[i - 1]
        when = row["datetime"]
        close = float(row["close"])
        slow_now = float(row["smooth_macd_slope"])
        slow_prev = float(prev["smooth_macd_slope"])
        fast_now = float(row["fast_smooth_macd_slope"])
        fast_prev = float(prev["fast_smooth_macd_slope"])
        sma200 = row["sma200"]
        adx_now = float(row["adx"])

        if open_trade is not None and close < open_trade.entry_price * DISASTER_FLOOR:
            reason = f"15m disaster floor hit: close {close:.4f} < entry {open_trade.entry_price:.4f} x {DISASTER_FLOOR}"
            trades.append(close_trade(open_trade, when, close, reason))
            audits.append(audit_row(symbol, when, "DISASTER_SIGNAL", "CATASTROPHE_EXIT", row, prev, reason))
            open_trade = None
            continue

        if open_trade is not None:
            if fast_prev > 0.0 and fast_now <= 0.0:
                reason = f"15m fast smooth MACD slope flipped + to - (prev={fast_prev:.4f} now={fast_now:.4f})"
                trades.append(close_trade(open_trade, when, close, reason))
                audits.append(audit_row(symbol, when, "SELL_SIGNAL", "SELL", row, prev, reason))
                open_trade = None
            continue

        if not (slow_prev < 0.0 and slow_now >= 0.0):
            continue

        if adx_now < MIN_ADX_FOR_ENTRY:
            reason = f"15m buy signal gated by ADX {adx_now:.1f} < {MIN_ADX_FOR_ENTRY:.1f}"
            audits.append(audit_row(symbol, when, "BUY_SIGNAL", "NO_ACTION", row, prev, reason))
            continue

        if pd.isna(sma200) or close <= float(sma200):
            reason = f"15m buy signal gated by trend: close {close:.4f} <= SMA200 {sma200 if not pd.isna(sma200) else 'n/a'}"
            audits.append(audit_row(symbol, when, "BUY_SIGNAL", "NO_ACTION", row, prev, reason))
            continue

        reason = (
            f"15m slow smooth MACD slope flipped - to + "
            f"(prev={slow_prev:.4f} now={slow_now:.4f}), ADX={adx_now:.1f}, price>{float(sma200):.4f}"
        )
        open_trade = OpenTrade(symbol=symbol, entry_time=when, entry_price=close, entry_reason=reason)
        audits.append(audit_row(symbol, when, "BUY_SIGNAL", "BUY", row, prev, reason))

    if open_trade is not None:
        last = df.iloc[-1]
        trades.append(open_trade_row(open_trade, last))

    trades_df = pd.DataFrame(trades)
    audits_df = pd.DataFrame(audits)
    summary = summarize(symbol, trades_df, audits_df)
    return trades_df, audits_df, summary


def audit_row(symbol: str, when: pd.Timestamp, signal: str, action: str, row: pd.Series, prev: pd.Series, reason: str) -> dict:
    return {
        "symbol": symbol,
        "bar_time": when,
        "signal": signal,
        "action": action,
        "price": float(row["close"]),
        "smooth_macd_slope": float(row["smooth_macd_slope"]),
        "previous_smooth_macd_slope": float(prev["smooth_macd_slope"]),
        "fast_smooth_macd_slope": float(row["fast_smooth_macd_slope"]),
        "previous_fast_smooth_macd_slope": float(prev["fast_smooth_macd_slope"]),
        "macd": float(row["macd"]),
        "macd_signal": float(row["macd_signal"]),
        "macd_histogram": float(row["macd_hist"]),
        "adx": float(row["adx"]),
        "sma200": None if pd.isna(row["sma200"]) else float(row["sma200"]),
        "reason": reason,
    }


def close_trade(trade: OpenTrade, exit_time: pd.Timestamp, exit_price: float, exit_reason: str) -> dict:
    pct = (exit_price - trade.entry_price) / trade.entry_price * 100.0 if trade.entry_price > 0 else 0.0
    pnl = NOTIONAL * pct / 100.0
    return {
        "symbol": trade.symbol,
        "status": "CLOSED",
        "entry_time": trade.entry_time,
        "entry_price": trade.entry_price,
        "entry_reason": trade.entry_reason,
        "exit_time": exit_time,
        "exit_price": exit_price,
        "exit_reason": exit_reason,
        "pct_return": pct,
        "pnl_notional": pnl,
    }


def open_trade_row(trade: OpenTrade, last: pd.Series) -> dict:
    last_close = float(last["close"])
    pct = (last_close - trade.entry_price) / trade.entry_price * 100.0 if trade.entry_price > 0 else 0.0
    return {
        "symbol": trade.symbol,
        "status": "OPEN",
        "entry_time": trade.entry_time,
        "entry_price": trade.entry_price,
        "entry_reason": trade.entry_reason,
        "exit_time": None,
        "exit_price": None,
        "exit_reason": f"mark-to-market at {last['datetime']}",
        "pct_return": pct,
        "pnl_notional": NOTIONAL * pct / 100.0,
    }


def summarize(symbol: str, trades: pd.DataFrame, audits: pd.DataFrame) -> dict:
    if trades.empty:
        return {
            "symbol": symbol,
            "closed": 0,
            "open": 0,
            "signals": 0 if audits.empty else len(audits),
            "buys": 0 if audits.empty else int((audits["action"] == "BUY").sum()),
            "gated": 0 if audits.empty else int((audits["action"] == "NO_ACTION").sum()),
            "total_closed_pct": 0.0,
            "total_closed_pnl": 0.0,
            "win_rate_pct": 0.0,
            "best_pct": 0.0,
            "worst_pct": 0.0,
        }

    closed = trades[trades["status"] == "CLOSED"]
    open_count = int((trades["status"] == "OPEN").sum())
    wins = int((closed["pct_return"] > 0).sum()) if not closed.empty else 0
    return {
        "symbol": symbol,
        "closed": int(len(closed)),
        "open": open_count,
        "signals": 0 if audits.empty else len(audits),
        "buys": 0 if audits.empty else int((audits["action"] == "BUY").sum()),
        "gated": 0 if audits.empty else int((audits["action"] == "NO_ACTION").sum()),
        "total_closed_pct": float(closed["pct_return"].sum()) if not closed.empty else 0.0,
        "total_closed_pnl": float(closed["pnl_notional"].sum()) if not closed.empty else 0.0,
        "win_rate_pct": float(wins / len(closed) * 100.0) if not closed.empty else 0.0,
        "best_pct": float(closed["pct_return"].max()) if not closed.empty else 0.0,
        "worst_pct": float(closed["pct_return"].min()) if not closed.empty else 0.0,
    }


def read_symbols(args: argparse.Namespace) -> list[str]:
    symbols = [s.strip().upper() for s in (args.symbols or []) if s.strip()]
    if args.symbols_file:
        symbols.extend(
            s.strip().upper()
            for s in Path(args.symbols_file).read_text().splitlines()
            if s.strip() and not s.strip().startswith("#")
        )
    return list(dict.fromkeys(symbols))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Backtest SignalScope paper strategy on 15m OHLCV data.")
    parser.add_argument("--source", choices=["yahoo", "csv"], default="yahoo")
    parser.add_argument("--symbols", nargs="*", default=[])
    parser.add_argument("--symbols-file")
    parser.add_argument("--period", default="60d", help="Yahoo only. For 15m, Yahoo is limited to recent intraday history.")
    parser.add_argument("--interval", default="15m", help="Yahoo interval. Strategy expects 15m.")
    parser.add_argument("--csv-dir", type=Path, help="Directory containing one CSV per symbol, e.g. SAP.DE.csv")
    parser.add_argument("--csv-file", type=Path, help="One CSV with a symbol column, or one-symbol CSV with --symbols SYMBOL")
    parser.add_argument("--resample", help="Optional pandas rule for CSV data, e.g. 15min to convert 5m bars to 15m.")
    parser.add_argument("--out", type=Path, default=Path("runs/latest"))
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    symbols = read_symbols(args)
    if not symbols:
        raise SystemExit("Pass --symbols PEP MSCI CSGP or --symbols-file symbols.txt")

    if args.source == "yahoo":
        frames = load_yahoo_data(symbols, args.period, args.interval)
    else:
        frames = load_csv_data(args.csv_dir, args.csv_file, symbols)

    if args.resample:
        frames = {symbol: resample_ohlcv(df, args.resample) for symbol, df in frames.items()}

    args.out.mkdir(parents=True, exist_ok=True)
    all_trades = []
    all_audits = []
    summaries = []

    for symbol, df in frames.items():
        if len(df) < MIN_BARS:
            print(f"warn: {symbol} has only {len(df)} bars; need at least {MIN_BARS}")
            continue
        trades, audits, summary = backtest_symbol(symbol, df)
        all_trades.append(trades)
        all_audits.append(audits)
        summaries.append(summary)

    trades_df = pd.concat(all_trades, ignore_index=True) if all_trades else pd.DataFrame()
    audits_df = pd.concat(all_audits, ignore_index=True) if all_audits else pd.DataFrame()
    summary_df = pd.DataFrame(summaries)

    trades_df.to_csv(args.out / "trades.csv", index=False)
    audits_df.to_csv(args.out / "audit.csv", index=False)
    summary_df.to_csv(args.out / "summary.csv", index=False)

    print(f"Wrote {args.out / 'summary.csv'}")
    if not summary_df.empty:
        print(summary_df.to_string(index=False))


if __name__ == "__main__":
    main()

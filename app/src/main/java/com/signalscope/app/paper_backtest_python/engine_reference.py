"""
engine_reference.py  --  VERBATIM extract of the SignalScope trade-engine logic.

Source of truth: strategy_lab.py + backtest.py (desktop backtester). This file is a faithful,
self-contained copy of ONLY the trading algorithm, for cross-checking the Android (Kotlin) port
against STRATEGY_LAB_PORT_SPEC.md. It is NOT the running app.

Included: indicators, entry/exit signal logic, single-timeframe and multi-timeframe simulation,
trade/accounting helpers, the 4 primitives (ema, adx, normalize_ohlcv, resample_ohlcv).
NOT included: the screener, the HTTP server, the web UI, and the VWAP/ORB strategies.

The functions are copied unchanged; the only added glue is the `bt` namespace shim below so the
verbatim `bt.ema(...)`/`bt.adx(...)` calls resolve. Behaviour is identical to the desktop engine.
"""
import math
import types
import numpy as np
import pandas as pd


# ========================================================================================
# PRIMITIVES (from backtest.py)
# ========================================================================================

def ema(series: pd.Series, span: int) -> pd.Series:
    return series.ewm(span=max(2, span), adjust=False).mean()

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

    import warnings
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", FutureWarning)
        try:
            parsed = pd.to_datetime(df["datetime"], errors="coerce")
            mixed = parsed.dtype == object
        except (ValueError, TypeError):
            mixed = True  # newer pandas raises on mixed UTC offsets instead of returning object dtype
    if mixed:
        # Mixed UTC offsets (EST/EDT across a multi-year span): normalize via UTC, then Eastern.
        parsed = pd.to_datetime(df["datetime"], errors="coerce", utc=True).dt.tz_convert("America/New_York")
    df["datetime"] = parsed
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


# --- namespace shim so the copied strategy_lab functions' bt.* calls resolve ---
bt = types.SimpleNamespace(ema=ema, adx=adx, normalize_ohlcv=normalize_ohlcv, resample_ohlcv=resample_ohlcv)


# ========================================================================================
# CONFIG HELPERS
# ========================================================================================

def cfg_get(cfg: dict, path: str, default):
    node = cfg
    for part in path.split("."):
        if not isinstance(node, dict) or part not in node:
            return default
        node = node[part]
    return node

def cfg_num(cfg: dict, path: str, default: float) -> float:
    try:
        return float(cfg_get(cfg, path, default))
    except (TypeError, ValueError):
        return default

def cfg_int(cfg: dict, path: str, default: int) -> int:
    return int(round(cfg_num(cfg, path, default)))

def cfg_on(cfg: dict, path: str) -> bool:
    return bool(cfg_get(cfg, path, False))


TREND_GATE = 'Prev-day trend filter'


# ========================================================================================
# MOMENTUM SCORE (off by default; used only if a momentum gate/exit is enabled)
# ========================================================================================

def momentum_score_daily(daily: pd.DataFrame) -> pd.Series:
    """Reconstruct SignalScope's ~11-bucket momentum score on daily bars (point-in-time, trailing windows).
    Faithful-but-approximate to the production app. Returns a score Series indexed by date."""
    c = daily["close"]
    ema21, ema50, ema200 = bt.ema(c, 21), bt.ema(c, 50), bt.ema(c, 200)
    sma200 = c.rolling(200, min_periods=30).mean()
    s21, s50 = ema21.diff() > 0, ema50.diff() > 0
    s200 = (ema200.diff() > 0) | (sma200.diff() > 0)

    # 1) Trend stack (raw/100 * 10)
    raw = (c > sma200) * 30 + s200 * 25 + s50 * 25 + s21 * 20
    trend_stack = raw / 100.0 * 10.0

    # 2) MACD continuation (max 8)
    macd = bt.ema(c, 12) - bt.ema(c, 26)
    mslope = macd.diff()
    maccel = mslope.diff()
    cross_up = (macd.shift(1) < 0) & (macd >= 0)
    slope_cross = (mslope.shift(1) < 0) & (mslope >= 0)
    macd_cont = pd.Series(0.0, index=c.index)
    macd_cont = macd_cont.mask((macd > 0) & (mslope > 0), 7.0)
    macd_cont = macd_cont.mask((mslope > 0) & (maccel >= 0), 6.0)
    macd_cont = macd_cont.mask(slope_cross & (maccel > 0), 4.0)
    macd_cont = macd_cont.mask((cross_up & (maccel > 0)) | slope_cross, 8.0)

    # 3) EMA21 slope (max 5)
    ema21_slope = pd.Series(0.0, index=c.index)
    ema21_slope = ema21_slope.mask(s21 & (c <= ema21), 2.5)
    ema21_slope = ema21_slope.mask(s21 & (c > ema21), 5.0)

    # 4) Not overextended (max 3)
    dist = (c / ema21 - 1.0) * 100.0
    not_over = pd.Series(0.0, index=c.index)
    not_over = not_over.mask((dist > 4) & (dist <= 8), 1.5)
    not_over = not_over.mask((dist >= -2) & (dist < 0), 2.0)
    not_over = not_over.mask((dist >= 0) & (dist <= 4), 3.0)

    # 5) RSI room (max 3)
    rsi = _rsi(c, 14)
    rsi_room = pd.Series(0.0, index=c.index)
    rsi_room = rsi_room.mask(rsi < 35, 1.0)
    rsi_room = rsi_room.mask((rsi >= 68) & (rsi <= 75), 1.5)
    rsi_room = rsi_room.mask((rsi >= 45) & (rsi <= 68), 3.0)

    # 6) OBV accumulation (max 4)
    obv = (np.sign(c.diff()).fillna(0.0) * daily["vol"]).cumsum()
    obv_room = (obv > obv.shift(5)).astype(float) * 2 + (obv > obv.shift(20)).astype(float) * 2

    # 7) Trend persistence (max 20): fraction of last 200 days with close > SMA200
    frac = (c > sma200).rolling(200, min_periods=40).mean()
    persistence = pd.Series(0.0, index=c.index)
    persistence = persistence.mask(frac >= 0.20, 4.0)
    persistence = persistence.mask(frac >= 0.40, 9.0)
    persistence = persistence.mask(frac >= 0.60, 15.0)
    persistence = persistence.mask(frac >= 0.80, 20.0)

    # 8) EMA cascade (max 15)
    cascade = (ema21 > ema50) & (ema50 > ema200)
    casc = pd.Series(0.0, index=c.index)
    casc = casc.mask(cascade, 9.0)
    casc = casc.mask(cascade & s21 & s50 & (ema200.diff() > 0), 15.0)

    # 9) Max drawdown over trailing 252d (max 10)
    roll_max = c.rolling(252, min_periods=20).max()
    dd = (1.0 - c / roll_max) * 100.0  # current decline from trailing peak, %
    ddp = pd.Series(0.0, index=c.index)
    ddp = ddp.mask(dd <= 30, 3.0)
    ddp = ddp.mask(dd <= 20, 6.0)
    ddp = ddp.mask(dd <= 10, 10.0)

    # 10) Long-term return (max 15): 6m ~40%, 12m ~60%
    r6 = c / c.shift(126) - 1.0
    r12 = c / c.shift(252) - 1.0
    ltr = 6.0 * (r6 / 0.20).clip(0, 1).fillna(0) + 9.0 * (r12 / 0.40).clip(0, 1).fillna(0)

    # 11) Sharpe-like (max 10): annualized mean/vol over 252d
    rets = c.pct_change()
    mean = rets.rolling(252, min_periods=40).mean() * 252
    vol = rets.rolling(252, min_periods=40).std() * np.sqrt(252)
    sharpe = mean / vol.replace(0, np.nan)
    shp = pd.Series(0.0, index=c.index)
    shp = shp.mask(sharpe > 0, 2.0)
    shp = shp.mask(sharpe >= 0.5, 4.0)
    shp = shp.mask(sharpe >= 1.0, 7.0)
    shp = shp.mask(sharpe >= 1.5, 10.0)

    score = (trend_stack + macd_cont + ema21_slope + not_over + rsi_room + obv_room
             + persistence + casc + ddp + ltr + shp)
    return score.fillna(0.0)

def attach_momentum(frame: pd.DataFrame) -> pd.Series:
    """Per-bar momentum score for an intraday frame, using each day's PRIOR-day daily score (no lookahead)."""
    dates = frame["datetime"].dt.date
    g = frame.groupby(dates)
    daily = pd.DataFrame({"close": g["close"].last(), "high": g["high"].max(),
                          "low": g["low"].min(), "vol": g["volume"].sum()}).sort_index()
    score = momentum_score_daily(daily).shift(1)  # prior completed day
    mapping = score.to_dict()
    return dates.map(mapping).astype(float).fillna(0.0).to_numpy()


# ========================================================================================
# SESSION HELPERS (off by default)
# ========================================================================================

def _parse_clock(s) -> tuple[int, int] | None:
    """Parse 'HH:MM' (exchange-local clock) into an (hour, minute) tuple, or None if blank/invalid."""
    s = str(s or "").strip()
    if ":" not in s:
        return None
    try:
        hh, mm = s.split(":")[:2]
        return (int(hh), int(mm))
    except (ValueError, TypeError):
        return None

def session_last_of_day(datetimes) -> np.ndarray:
    """Boolean array: True on the last bar of each trading day (auto-detects early-close days)."""
    d = pd.to_datetime(pd.Series(list(datetimes))).dt.normalize().to_numpy()
    last = np.ones(len(d), dtype=bool)
    if len(d) > 1:
        last[:-1] = d[1:] != d[:-1]
    return last


# ========================================================================================
# INDICATORS
# ========================================================================================

def compute_indicators(df: pd.DataFrame, cfg: dict) -> pd.DataFrame:
    out = df.copy()
    close = out["close"]

    fast = cfg_int(cfg, "macd.fast", 12)
    slow = cfg_int(cfg, "macd.slow", 26)
    signal = cfg_int(cfg, "macd.signal", 9)
    macd = bt.ema(close, fast) - bt.ema(close, slow)
    macd_signal = bt.ema(macd, signal)

    raw_slope = macd.diff().fillna(0.0)
    out["macd"] = macd
    out["macd_signal"] = macd_signal
    out["entry_slope"] = bt.ema(raw_slope, cfg_int(cfg, "entry.slopeFlipUp.smoothing", 5))
    out["exit_slope"] = bt.ema(raw_slope, cfg_int(cfg, "exit.slopeFlipDown.smoothing", 2))
    # MACD acceleration: change of the smoothed slope (d2 MACD / dt2)
    out["entry_accel"] = out["entry_slope"].diff().fillna(0.0)
    out["exit_accel"] = out["exit_slope"].diff().fillna(0.0)
    # Typical acceleration magnitude per stock/regime, for sigma-based strength thresholds.
    out["accel_sigma"] = out["entry_accel"].rolling(200, min_periods=50).std()
    # PID integral term: how many consecutive bars the entry slope has stayed >= 0.
    pos = out["entry_slope"] >= 0.0
    out["entry_slope_runlen"] = pos.groupby((~pos).cumsum()).cumsum()
    # Rolling trough of the slope, for the early-recovery entry (enter before the full zero-cross).
    recov_look = max(2, cfg_int(cfg, "entry.earlyRecovery.lookback", 30))
    out["entry_slope_trough"] = out["entry_slope"].rolling(recov_look, min_periods=2).min()

    ema_len = max(2, cfg_int(cfg, "gates.emaSlope.length", 200))
    ema_look = max(1, cfg_int(cfg, "gates.emaSlope.lookback", 10))
    ema_series = bt.ema(close, ema_len)
    out["ema_slope_chg"] = (ema_series / ema_series.shift(ema_look) - 1.0) * 100.0

    entry_look = max(1, cfg_int(cfg, "entry.velocity.lookback", 10))
    exit_look = max(1, cfg_int(cfg, "exit.velocity.lookback", 10))
    out["entry_velocity"] = close.pct_change(entry_look) * 100.0
    out["exit_velocity"] = close.pct_change(exit_look) * 100.0

    lvl_len = max(2, cfg_int(cfg, "gates.sma.length", 200))
    if str(cfg_get(cfg, "gates.sma.kind", "sma")).lower() == "ema":
        out["sma"] = bt.ema(close, lvl_len)
    else:
        out["sma"] = close.rolling(lvl_len).mean()
    out["adx"] = bt.adx(out, period=max(2, cfg_int(cfg, "gates.adx.period", 14)))

    if cfg_on(cfg, "gates.momentum.enabled") or cfg_on(cfg, "exit.momentumExit.enabled"):
        out["momentum"] = attach_momentum(out)
    else:
        out["momentum"] = 100.0  # neutral pass-through when the feature is off
    return out


# ========================================================================================
# ENTRY / EXIT SIGNAL LOGIC
# ========================================================================================

def entry_signal(row, prev, cfg: dict) -> tuple[bool, list[str]]:
    """Evaluate enabled entry conditions on itertuples rows. Returns (fired, reasons)."""
    checks: list[tuple[bool, str]] = []

    if cfg_on(cfg, "entry.slopeFlipUp.enabled"):
        if cfg_on(cfg, "entry.slopeFlipUp.persistent"):
            # State (slope >= 0) rather than the one-bar flip EVENT, so in ALL mode it can coincide with
            # the accel/other conditions on a LATER bar instead of needing them all on the flip bar.
            ok = float(row.entry_slope) >= 0.0
            checks.append((ok, "MACD slope >= 0 (armed while > 0)"))
        else:
            ok = float(prev.entry_slope) < 0.0 <= float(row.entry_slope)
            checks.append((ok, "MACD slope flipped - to +"))
    if cfg_on(cfg, "entry.slopePositive.enabled"):
        ok = float(row.entry_slope) > 0.0
        checks.append((ok, "MACD slope already positive"))
    if cfg_on(cfg, "entry.macdCrossUp.enabled"):
        ok = float(prev.macd) <= float(prev.macd_signal) and float(row.macd) > float(row.macd_signal)
        checks.append((ok, "MACD crossed above signal"))
    if cfg_on(cfg, "entry.macdAboveSignal.enabled"):
        ok = float(row.macd) > float(row.macd_signal)
        checks.append((ok, "MACD above signal"))
    if cfg_on(cfg, "entry.macdAboveZero.enabled"):
        ok = float(row.macd) > 0.0
        checks.append((ok, "MACD above zero"))
    if cfg_on(cfg, "entry.accelPositive.enabled"):
        accel = float(row.entry_accel)
        k = cfg_num(cfg, "entry.accelPositive.minSigma", 0.0)
        floor = k * float(row.accel_sigma) if (k > 0 and pd.notna(row.accel_sigma)) else 0.0
        ok = accel > floor
        checks.append((ok, f"MACD acceleration {accel:.4f} > {floor:.4f}" if floor else "MACD acceleration positive"))
    if cfg_on(cfg, "entry.velocity.enabled"):
        vel = row.entry_velocity
        ok = bool(pd.notna(vel)) and float(vel) >= cfg_num(cfg, "entry.velocity.threshold", 0.5)
        checks.append((ok, f"velocity {0.0 if pd.isna(vel) else float(vel):.2f}% >= threshold"))
    # PID integral / persistence rescue path: enter once slope has held positive long enough,
    # OR-ed with the normal conditions so it catches trends the acceleration gate was too strict for.
    sustain_enabled = cfg_on(cfg, "entry.sustainSlope.enabled")
    sustain_ok = False
    sustain_label = None
    if sustain_enabled:
        need = max(1, cfg_int(cfg, "entry.sustainSlope.bars", 8))
        runlen = int(getattr(row, "entry_slope_runlen", 0))
        sustain_ok = float(row.entry_slope) > 0.0 and runlen >= need
        sustain_label = f"slope positive {runlen} bars >= {need} (integral)"

    # Early-recovery rescue path: enter while the slope is still NEGATIVE but recovering — when it
    # crosses up through X% of its trough, BEFORE the zero-cross. This fires on a *different* bar than
    # the slope-flip (the flip needs slope>=0, this needs slope<0), so the two can never be true on the
    # same bar. It is therefore an independent OR-path, NOT an AND-able core condition — enabling it
    # alongside the flip means "flip OR early-recovery", with optional positive-velocity confirmation.
    early_enabled = cfg_on(cfg, "entry.earlyRecovery.enabled")
    early_ok = False
    early_label = None
    if early_enabled:
        trough = float(getattr(row, "entry_slope_trough", 0.0))
        frac = cfg_num(cfg, "entry.earlyRecovery.pct", 60.0) / 100.0
        thr = frac * trough  # trough is negative, so thr is a negative threshold above the trough
        crossed = trough < -1e-9 and float(prev.entry_slope) < thr <= float(row.entry_slope)
        velok = True
        if cfg_on(cfg, "entry.earlyRecovery.confirmVelocity"):
            v = row.entry_velocity
            velok = bool(pd.notna(v)) and float(v) > 0.0
        early_ok = crossed and velok
        early_label = (f"slope recovered to {frac * 100:g}% of trough (early entry)"
                       + (", vel>0" if cfg_on(cfg, "entry.earlyRecovery.confirmVelocity") else ""))

    if not checks and not sustain_enabled and not early_enabled:
        return False, []
    mode_any = str(cfg_get(cfg, "entry.mode", "ALL")).upper() == "ANY"
    core_fired = (any(ok for ok, _ in checks) if mode_any else all(ok for ok, _ in checks)) if checks else False
    fired = core_fired or sustain_ok or early_ok
    reasons = [label for ok, label in checks if ok]
    if sustain_ok:
        reasons.append(sustain_label)
    if early_ok:
        reasons.append(early_label)
    return fired, reasons

def gate_block(row, cfg: dict) -> str | None:
    """Return a block reason if a gate rejects the entry, else None."""
    if cfg_on(cfg, "gates.adx.enabled"):
        min_adx = cfg_num(cfg, "gates.adx.min", 20.0)
        if float(row.adx) < min_adx:
            return "ADX below minimum"
    if cfg_on(cfg, "gates.sma.enabled"):
        sma = row.sma
        if pd.isna(sma) or float(row.close) <= float(sma):
            kind = str(cfg_get(cfg, "gates.sma.kind", "sma")).upper()
            return f"Price below or at {kind}"
    if cfg_on(cfg, "gates.emaSlope.enabled"):
        chg = row.ema_slope_chg
        if pd.isna(chg) or float(chg) < cfg_num(cfg, "gates.emaSlope.min", 0.1):
            return "EMA slope below minimum"
    if cfg_on(cfg, "gates.momentum.enabled"):
        if float(getattr(row, "momentum", 0.0)) < cfg_num(cfg, "gates.momentum.min", 60.0):
            return "Momentum score below minimum"
    return None

def indicator_exit(row, prev, entry_macd: float, cfg: dict, include_velocity: bool = True,
                   include_slope: bool = True) -> str | None:
    """Indicator-based exits combined via exit.mode ANY/ALL, gated by the MACD-distance qualifier.
    include_slope=False lets the multi-timeframe engine handle the slope flip on fine bars instead."""
    mode_all = str(cfg_get(cfg, "exit.mode", "ANY")).upper() == "ALL"
    persistent = cfg_on(cfg, "exit.slopeFlipDown.persistent")
    checks: list[tuple[bool, str]] = []
    if include_slope and cfg_on(cfg, "exit.slopeFlipDown.enabled"):
        if mode_all or persistent:
            # State (slope <= 0) rather than the one-bar flip EVENT. This keeps the exit armed while the
            # slope stays negative, so a down-flip that the MACD-distance qualifier suppressed can still
            # fire on a later bar (instead of being lost forever). Disarms if slope turns positive again.
            checks.append((float(row.exit_slope) <= 0.0, "MACD slope negative (armed while < 0)"))
        else:
            checks.append((float(prev.exit_slope) > 0.0 >= float(row.exit_slope), "MACD slope flipped + to -"))
    if cfg_on(cfg, "exit.macdCrossDown.enabled"):
        if mode_all:
            checks.append((float(row.macd) < float(row.macd_signal), "MACD below signal"))
        else:
            ok = float(prev.macd) >= float(prev.macd_signal) and float(row.macd) < float(row.macd_signal)
            checks.append((ok, "MACD crossed below signal"))
    if cfg_on(cfg, "exit.macdBelowZero.enabled"):
        checks.append((float(row.macd) < 0.0, "MACD below zero"))
    if cfg_on(cfg, "exit.accelNegative.enabled"):
        checks.append((float(row.exit_accel) < 0.0, "MACD acceleration negative"))
    if include_velocity and cfg_on(cfg, "exit.velocity.enabled"):
        vel = row.exit_velocity
        ok = bool(pd.notna(vel)) and float(vel) <= cfg_num(cfg, "exit.velocity.threshold", 0.0)
        checks.append((ok, "velocity below floor"))
    if not checks:
        return None
    fired = all(ok for ok, _ in checks) if mode_all else any(ok for ok, _ in checks)
    if not fired:
        return None
    if cfg_on(cfg, "exit.macdDistance.enabled"):
        need = cfg_num(cfg, "exit.macdDistance.pct", 20.0)
        if abs(entry_macd) > 1e-12:
            moved = abs(float(row.macd) - entry_macd) / abs(entry_macd) * 100.0
            if moved < need:
                return None  # indicator exit suppressed until MACD has moved enough from entry
    return "; ".join(label for ok, label in checks if ok)

def exit_signal(row, prev, entry_price: float, entry_macd: float, bars_held: int, cfg: dict) -> str | None:
    """Hard exits (stop/target/max bars) fire immediately; indicator exits combine via exit.mode ANY/ALL."""
    close = float(row.close)
    if cfg_on(cfg, "exit.stopLoss.enabled"):
        pct = cfg_num(cfg, "exit.stopLoss.pct", 15.0)
        if close <= entry_price * (1.0 - pct / 100.0):
            return f"stop loss -{pct:g}%"
    if cfg_on(cfg, "exit.takeProfit.enabled"):
        pct = cfg_num(cfg, "exit.takeProfit.pct", 5.0)
        if close >= entry_price * (1.0 + pct / 100.0):
            return f"take profit +{pct:g}%"
    if cfg_on(cfg, "exit.maxBars.enabled") and bars_held >= cfg_int(cfg, "exit.maxBars.bars", 100):
        return f"max holding {cfg_int(cfg, 'exit.maxBars.bars', 100)} bars"
    if cfg_on(cfg, "exit.momentumExit.enabled"):
        if float(getattr(row, "momentum", 100.0)) < cfg_num(cfg, "exit.momentumExit.min", 60.0):
            return "momentum dropped below floor"
    if bars_held < cfg_int(cfg, "exit.minHoldBars", 0):
        return None  # indicator exits blocked during the minimum holding period
    return indicator_exit(row, prev, entry_macd, cfg, include_velocity=True)


# ========================================================================================
# PREV-DAY TREND FILTER (off by default)
# ========================================================================================

def prev_day_trend(d: pd.DataFrame) -> np.ndarray:
    """Regression slope (% per day) of each row's previous trading day; NaN on the first day."""
    day_codes, _, _ = session_info(d)
    closes = d["close"].to_numpy(dtype=float)
    slopes: dict[int, float] = {}
    for code in np.unique(day_codes):
        c = closes[day_codes == code]
        if len(c) >= 5 and c[0] > 0:
            slope = np.polyfit(np.arange(len(c), dtype=float), c, 1)[0]
            slopes[int(code)] = slope * len(c) / c[0] * 100.0
    prev = np.full(len(d), np.nan)
    for code in np.unique(day_codes):
        prior = slopes.get(int(code) - 1)
        if prior is not None:
            prev[day_codes == code] = prior
    return prev

def trend_filter_blocks(prev_slope: float, cfg: dict) -> bool:
    """True if the previous-day trend filter rejects an entry at this bar."""
    mode = str(cfg_get(cfg, "trendFilter.mode", "off")).lower()
    if mode == "off":
        return False
    if math.isnan(prev_slope):
        return True
    thr = abs(cfg_num(cfg, "trendFilter.threshold", 0.3))
    if mode == "up":
        return prev_slope < thr
    if mode == "down":
        return prev_slope > -thr
    if mode == "flat":
        return abs(prev_slope) >= thr
    if mode == "notdown":
        return prev_slope <= -thr
    return False


# ========================================================================================
# TRADE + RESULT BUILDERS
# ========================================================================================

def trade_record(symbol: str, side: str, status: str, entry_time: str, entry_price: float,
                 entry_reason: str, exit_time: str, exit_price: float, exit_reason: str,
                 notional: float, bars_held: int) -> dict:
    direction = 1.0 if side == "LONG" else -1.0
    pct = direction * (exit_price - entry_price) / entry_price * 100.0 if entry_price > 0 else 0.0
    return {
        "symbol": symbol,
        "side": side,
        "status": status,
        "entry_time": entry_time,
        "entry_price": round(entry_price, 4),
        "entry_reason": entry_reason,
        "exit_time": exit_time if status == "CLOSED" else "",
        "exit_price": round(exit_price, 4) if status == "CLOSED" else None,
        "exit_reason": exit_reason,
        "pct_return": round(pct, 4),
        "pnl_notional": round(notional * pct / 100.0, 4),
        "bars_held": bars_held,
    }

def empty_result(symbol: str, bars: int) -> dict:
    return {"symbol": symbol, "trades": [], "signals": 0, "gates": {},
            "open_count": 0, "open_pnl": 0.0, "bars": bars}


# ========================================================================================
# SIMULATION LOOPS (single-timeframe + multi-timeframe)
# ========================================================================================

def simulate_macd(symbol: str, df: pd.DataFrame, cfg: dict, notional: float, warmup: int) -> dict:
    df = compute_indicators(df, cfg)
    prev_trend = prev_day_trend(df)
    trades: list[dict] = []
    gate_counts: dict[str, int] = {}
    signals = 0

    entry_price = 0.0
    entry_macd = 0.0
    entry_time = None
    entry_reason = ""
    entry_index = -1
    in_trade = False
    peak_slope = 0.0
    retrace_on = cfg_on(cfg, "exit.slopeRetrace.enabled")
    retrace_frac = cfg_num(cfg, "exit.slopeRetrace.pct", 40.0) / 100.0
    sess_on = cfg_on(cfg, "exit.session.enabled")
    sess_cut = _parse_clock(cfg_get(cfg, "exit.session.noEntryAfter", "")) if sess_on else None
    sess_flat = sess_on and cfg_on(cfg, "exit.session.flattenAtClose")
    last_of_day = session_last_of_day(df["datetime"]) if sess_on else None

    rows = list(df.itertuples(index=False))
    for i in range(max(1, warmup), len(rows)):
        row = rows[i]
        prev = rows[i - 1]
        close = float(row.close)
        when = row.datetime

        if in_trade:
            peak_slope = max(peak_slope, float(row.exit_slope))
            reason = exit_signal(row, prev, entry_price, entry_macd, i - entry_index, cfg)
            if (not reason and retrace_on and peak_slope > 1e-9 and i - entry_index >= cfg_int(cfg, "exit.minHoldBars", 0)
                    and float(row.exit_slope) <= retrace_frac * peak_slope
                    and float(row.exit_slope) < float(prev.exit_slope)):
                reason = f"slope retraced to {retrace_frac * 100:g}% of peak (rollover exit)"
            if not reason and sess_flat and last_of_day[i]:
                reason = "session close - flat (no overnight)"
            if reason:
                pct = (close - entry_price) / entry_price * 100.0
                trades.append({
                    "symbol": symbol,
                    "side": "LONG",
                    "status": "CLOSED",
                    "entry_time": str(entry_time),
                    "entry_price": round(entry_price, 4),
                    "entry_reason": entry_reason,
                    "exit_time": str(when),
                    "exit_price": round(close, 4),
                    "exit_reason": reason,
                    "pct_return": round(pct, 4),
                    "pnl_notional": round(notional * pct / 100.0, 4),
                    "bars_held": i - entry_index,
                })
                in_trade = False
            continue

        fired, reasons = entry_signal(row, prev, cfg)
        if not fired:
            continue
        signals += 1

        if sess_on and sess_cut is not None and (when.hour, when.minute) >= sess_cut:
            gate_counts["session: no entry near close"] = gate_counts.get("session: no entry near close", 0) + 1
            continue
        block = gate_block(row, cfg)
        if block:
            gate_counts[block] = gate_counts.get(block, 0) + 1
            continue
        if trend_filter_blocks(prev_trend[i], cfg):
            gate_counts[TREND_GATE] = gate_counts.get(TREND_GATE, 0) + 1
            continue

        in_trade = True
        entry_price = close
        entry_macd = float(row.macd)
        entry_time = when
        entry_index = i
        entry_reason = "; ".join(reasons)
        peak_slope = float(row.exit_slope)

    open_pnl = 0.0
    open_count = 0
    if in_trade:
        last = rows[-1]
        pct = (float(last.close) - entry_price) / entry_price * 100.0
        open_pnl = notional * pct / 100.0
        open_count = 1
        trades.append({
            "symbol": symbol,
            "side": "LONG",
            "status": "OPEN",
            "entry_time": str(entry_time),
            "entry_price": round(entry_price, 4),
            "entry_reason": entry_reason,
            "exit_time": "",
            "exit_price": None,
            "exit_reason": f"mark-to-market at {last.datetime}",
            "pct_return": round(pct, 4),
            "pnl_notional": round(open_pnl, 4),
            "bars_held": len(rows) - 1 - entry_index,
        })

    return {
        "symbol": symbol,
        "trades": trades,
        "signals": signals,
        "gates": gate_counts,
        "open_count": open_count,
        "open_pnl": open_pnl,
        "bars": len(df),
    }

def simulate_macd_mtf(symbol: str, fine_df: pd.DataFrame, entry_rule: str, cfg: dict,
                      notional: float, warmup: int) -> dict:
    """MACD entries decided on entry_rule candles; stop/target/velocity exits checked on every fine bar.

    MACD-based exits are evaluated whenever an entry candle completes; stop loss,
    take profit, max-bars (in fine bars) and the velocity floor (fine-bar lookback)
    are checked on every fine bar so sharp moves exit quickly.
    """
    f = fine_df.reset_index(drop=True)
    entry_frame = bt.resample_ohlcv(f, entry_rule)
    if len(entry_frame) <= warmup or len(f) < 10:
        return empty_result(symbol, len(f))
    entry_frame = compute_indicators(entry_frame, cfg)
    prev_trend = prev_day_trend(entry_frame)
    e_rows = list(entry_frame.itertuples(index=False))
    e_end = (entry_frame["datetime"] + pd.Timedelta(entry_rule)).to_numpy()

    fclose = f["close"].to_numpy(dtype=float)
    times = f["datetime"].astype(str).tolist()
    gaps = f["datetime"].diff().dt.total_seconds().div(60.0)
    positive = gaps[gaps > 0]
    fine_minutes = float(positive.median()) if len(positive) else 1.0
    f_end = (f["datetime"] + pd.Timedelta(minutes=fine_minutes)).to_numpy()
    e_idx_per_fine = np.searchsorted(e_end, f_end, side="right") - 1

    exit_look = max(1, cfg_int(cfg, "exit.velocity.lookback", 10))
    fvel = (f["close"].pct_change(exit_look) * 100.0).to_numpy()

    vel_on = cfg_on(cfg, "exit.velocity.enabled")
    vel_floor = cfg_num(cfg, "exit.velocity.threshold", 0.0)
    dist_on = cfg_on(cfg, "exit.macdDistance.enabled")
    dist_pct = cfg_num(cfg, "exit.macdDistance.pct", 20.0)
    min_hold = cfg_int(cfg, "exit.minHoldBars", 0)
    stop_on = cfg_on(cfg, "exit.stopLoss.enabled")
    stop_pct = cfg_num(cfg, "exit.stopLoss.pct", 15.0)
    tp_on = cfg_on(cfg, "exit.takeProfit.enabled")
    tp_pct = cfg_num(cfg, "exit.takeProfit.pct", 5.0)
    mb_on = cfg_on(cfg, "exit.maxBars.enabled")
    mb = cfg_int(cfg, "exit.maxBars.bars", 100)
    mom_exit_on = cfg_on(cfg, "exit.momentumExit.enabled")
    mom_exit_min = cfg_num(cfg, "exit.momentumExit.min", 60.0)
    # Trailing-slope-retracement exit: leave once the (entry-candle) slope has fallen to a fraction
    # of its peak-since-entry and is decelerating — exits the rollover BEFORE the full zero-cross.
    retrace_on = cfg_on(cfg, "exit.slopeRetrace.enabled")
    retrace_frac = cfg_num(cfg, "exit.slopeRetrace.pct", 40.0) / 100.0
    retrace_vel = cfg_on(cfg, "exit.slopeRetrace.confirmVelocity")

    # Multi-timeframe fast exit: compute the MACD slope on the FINE (exit-resolution) bars so the
    # slope-flip-down exit reacts on 15-min momentum while entries stay on the slow entry candles.
    fast_exit_on = cfg_on(cfg, "exit.fastMacdExit.enabled") and cfg_on(cfg, "exit.slopeFlipDown.enabled")
    fexit_persistent = cfg_on(cfg, "exit.slopeFlipDown.persistent")
    fmacd_arr = None
    fexit_slope = None
    if fast_exit_on:
        fmacd_ser = bt.ema(f["close"], cfg_int(cfg, "macd.fast", 12)) - bt.ema(f["close"], cfg_int(cfg, "macd.slow", 26))
        fmacd_arr = fmacd_ser.to_numpy(dtype=float)
        fexit_slope = bt.ema(fmacd_ser.diff().fillna(0.0), cfg_int(cfg, "exit.slopeFlipDown.smoothing", 2)).to_numpy(dtype=float)

    sess_on = cfg_on(cfg, "exit.session.enabled")
    sess_cut = _parse_clock(cfg_get(cfg, "exit.session.noEntryAfter", "")) if sess_on else None
    sess_flat = sess_on and cfg_on(cfg, "exit.session.flattenAtClose")
    last_of_day = session_last_of_day(f["datetime"]) if sess_on else None
    fdt = f["datetime"]

    trades: list[dict] = []
    gate_counts: dict[str, int] = {}
    signals = 0
    in_trade = False
    entry_price = entry_macd = entry_macd_fine = 0.0
    entry_time = None
    entry_reason = ""
    entry_i = -1
    last_e = 0
    peak_slope = 0.0

    for i in range(len(f)):
        ei = int(e_idx_per_fine[i])
        new_e = ei > last_e and ei >= 1
        close = float(fclose[i])

        if in_trade:
            if new_e:
                peak_slope = max(peak_slope, float(e_rows[ei].exit_slope))
            reason = None
            if stop_on and close <= entry_price * (1.0 - stop_pct / 100.0):
                reason = f"stop loss -{stop_pct:g}% (fine bar)"
            elif tp_on and close >= entry_price * (1.0 + tp_pct / 100.0):
                reason = f"take profit +{tp_pct:g}% (fine bar)"
            elif mb_on and i - entry_i >= mb:
                reason = f"max holding {mb} fine bars"
            elif mom_exit_on and float(getattr(e_rows[ei], "momentum", 100.0)) < mom_exit_min:
                reason = "momentum dropped below floor"
            elif i - entry_i < min_hold:
                reason = None  # indicator exits blocked during the minimum holding period
            elif fast_exit_on and i > 0 and (fexit_slope[i] <= 0.0 if fexit_persistent
                                             else fexit_slope[i - 1] > 0.0 >= fexit_slope[i]):
                allowed = True
                if dist_on and abs(entry_macd_fine) > 1e-12:
                    moved = abs(fmacd_arr[i] - entry_macd_fine) / abs(entry_macd_fine) * 100.0
                    allowed = moved >= dist_pct
                if allowed:
                    reason = ("15m MACD slope negative (fast exit, armed)" if fexit_persistent
                              else "15m MACD slope flipped + to - (fast exit)")
            elif (retrace_on and new_e and peak_slope > 1e-9
                  and float(e_rows[ei].exit_slope) <= retrace_frac * peak_slope
                  and float(e_rows[ei].exit_slope) < float(e_rows[ei - 1].exit_slope)):
                allowed = not retrace_vel or (not math.isnan(fvel[i]) and fvel[i] <= 0.0)
                if dist_on and abs(entry_macd) > 1e-12:
                    moved = abs(float(e_rows[ei].macd) - entry_macd) / abs(entry_macd) * 100.0
                    allowed = allowed and moved >= dist_pct
                if allowed:
                    reason = f"slope retraced to {retrace_frac * 100:g}% of peak (rollover exit)"
            elif vel_on and not math.isnan(fvel[i]) and fvel[i] <= vel_floor:
                allowed = True
                if dist_on and abs(entry_macd) > 1e-12 and ei >= 0:
                    moved = abs(float(e_rows[ei].macd) - entry_macd) / abs(entry_macd) * 100.0
                    allowed = moved >= dist_pct
                if allowed:
                    reason = "velocity below floor (fine bar)"
            elif new_e:
                reason = indicator_exit(e_rows[ei], e_rows[ei - 1], entry_macd, cfg,
                                        include_velocity=False, include_slope=not fast_exit_on)
            if not reason and sess_flat and last_of_day[i]:
                reason = "session close - flat (no overnight)"
            if reason:
                trades.append(trade_record(symbol, "LONG", "CLOSED", str(entry_time), entry_price,
                                           entry_reason, times[i], close, reason, notional, i - entry_i))
                in_trade = False
        elif new_e and ei >= warmup:
            fired, reasons = entry_signal(e_rows[ei], e_rows[ei - 1], cfg)
            if fired:
                signals += 1
                near_close = sess_on and sess_cut is not None and (fdt.iloc[i].hour, fdt.iloc[i].minute) >= sess_cut
                if near_close:
                    gate_counts["session: no entry near close"] = gate_counts.get("session: no entry near close", 0) + 1
                block = None if near_close else gate_block(e_rows[ei], cfg)
                if near_close:
                    pass
                elif block:
                    gate_counts[block] = gate_counts.get(block, 0) + 1
                elif trend_filter_blocks(prev_trend[ei], cfg):
                    gate_counts[TREND_GATE] = gate_counts.get(TREND_GATE, 0) + 1
                else:
                    in_trade = True
                    entry_price = close
                    entry_macd = float(e_rows[ei].macd)
                    entry_macd_fine = float(fmacd_arr[i]) if fast_exit_on else 0.0
                    entry_time = f["datetime"].iloc[i]
                    entry_i = i
                    entry_reason = "; ".join(reasons)
                    peak_slope = float(e_rows[ei].exit_slope)

        if new_e:
            last_e = ei

    open_count = 0
    open_pnl = 0.0
    if in_trade:
        rec = trade_record(symbol, "LONG", "OPEN", str(entry_time), entry_price, entry_reason,
                           "", float(fclose[-1]), f"mark-to-market at {times[-1]}", notional,
                           len(f) - 1 - entry_i)
        open_count, open_pnl = 1, rec["pnl_notional"]
        trades.append(rec)

    result = empty_result(symbol, len(f))
    result.update({"trades": trades, "signals": signals, "gates": gate_counts,
                   "open_count": open_count, "open_pnl": open_pnl})
    return result


# --- accounting (faithful transcription of run_backtest()'s per-symbol step; the full run_backtest
#     is omitted because it is entangled with the DataStore / HTTP progress globals) ---
def apply_slippage_and_compound(closed_trades, notional, slippage):
    """For one symbol's CLOSED trades: subtract slippage% from each trade's return, recompute its
    dollar PnL on the fixed notional, then compound all-in per symbol. Mirrors run_backtest()."""
    if slippage > 0:
        for t in closed_trades:
            t["pct_return"] = round(t["pct_return"] - slippage, 4)
            t["pnl_notional"] = round(notional * t["pct_return"] / 100.0, 4)
    compound = 1.0
    for t in closed_trades:
        compound *= 1.0 + t["pct_return"] / 100.0
    return {
        "pnl": round(sum(t["pnl_notional"] for t in closed_trades), 4),
        "compounded_return_pct": round((compound - 1.0) * 100.0, 2),
    }

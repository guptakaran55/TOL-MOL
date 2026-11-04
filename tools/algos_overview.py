"""Write ALGOS_OVERVIEW.md: every algo in every space, its entry and exit rules, and
how it has done — on paper, for real, in its replay, and in the backtest it was
validated with.

Usage:
    python -I tools/algos_overview.py <out.md>              # reads the phone over adb
    python -I tools/algos_overview.py <out.md> <data_dir>   # reads files already copied

Read-only: it copies the app's files with `adb shell run-as ... cat` and changes nothing.
"""
import datetime
import glob
import json
import os
import subprocess
import sys

OUT = sys.argv[1]
SRC = sys.argv[2] if len(sys.argv) > 2 else None
SPACES = [("de", "Germany", "€"), ("us", "USA", "$"), ("in", "India", "₹"), ("binance", "Crypto", "USDC ")]
FILES = ["floor_algos.json", "whatif_summaries.json", "t212_live_mirror.json",
         "zerodha_live_trades.json", "quality_cheap.json"]


# ── reading the phone ──────────────────────────────────────────────────────────

def adb_dir():
    adb = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
    out = os.path.join(os.environ.get("TEMP", "."), "algos_overview_data")
    os.makedirs(out, exist_ok=True)

    def cat(name):
        r = subprocess.run([adb, "shell", "run-as", "com.signalscope.app", "cat", "files/" + name],
                           capture_output=True)
        if r.returncode == 0 and r.stdout:
            open(os.path.join(out, name), "wb").write(r.stdout)

    names = subprocess.run([adb, "shell", "run-as", "com.signalscope.app", "ls", "files/"],
                           capture_output=True, text=True).stdout.split()
    if not names:
        sys.exit("The phone is not connected (adb found no files).")
    for n in names:
        if n.startswith(("buckets_", "strategy_lab_", "macd_gate_", "binance_live_mirror")) or n in FILES:
            cat(n)
    return out


D = SRC or adb_dir()


def load(name, default=None):
    try:
        return json.load(open(os.path.join(D, name), encoding="utf-8"))
    except Exception:
        return default


# ── the rules, in words ────────────────────────────────────────────────────────

def frame(tf, default="the same candles"):
    tf = (tf or "").strip().lower()
    if tf in ("", "1d", "d"):
        return "daily candles" if tf else "daily candles"
    if tf == "same":
        return default
    if tf.endswith("min"):
        m = int(tf[:-3])
        return f"{m // 60}-hour candles" if m % 60 == 0 else f"{m}-minute candles"
    return tf + " candles"


def on(d, k):
    v = (d or {}).get(k)
    return v if isinstance(v, dict) and v.get("enabled") else None


def entry_words(c):
    if c.get("strategy") == "smaHold":
        s = c.get("sma") or {}
        n = s.get("length", 50)
        return [f"Buy when the daily close is above its {n}-day average."], \
               [f"Sell when the daily close falls below its {n}-day average."]
    e, g, x = c.get("entry") or {}, c.get("gates") or {}, c.get("exit") or {}
    ent = [f"Judged on {frame(c.get('resample'))}; "
           + ("all of these must hold:" if (e.get("mode") or "ALL") == "ALL" else "any of these:")]
    if (r := on(e, "slopeFlipUp")): ent.append(f"the MACD slope turns up (smoothed over {r.get('smoothing', 2)} candles)")
    if on(e, "slopePositive"): ent.append("the MACD slope is above zero")
    if (r := on(e, "accelPositive")): ent.append(f"MACD acceleration is positive, at least {r.get('minSigma')} standard deviations")
    if (r := on(e, "sustainSlope")): ent.append(f"the slope is sustained (over {r.get('bars')} candles)")
    if (r := on(e, "earlyRecovery")): ent.append(f"early-recovery check ({r.get('pct')}% over {r.get('lookback')} candles"
                                               + (", price velocity confirming)" if r.get("confirmVelocity") else ")"))
    if (r := on(e, "velocity")): ent.append(f"price velocity over {r.get('lookback')} candles above {r.get('threshold')}")
    for k, w in (("macdCrossUp", "MACD crosses above its signal line"), ("macdAboveSignal", "MACD is above its signal line"),
                 ("macdAboveZero", "MACD is above zero")):
        if on(e, k): ent.append(w)
    if (r := on(g, "sma")): ent.append(f"price above its {r.get('length')}-candle {str(r.get('kind', 'sma')).upper()}")
    if (r := on(g, "adx")): ent.append(f"ADX({r.get('period', 14)}) at least {r.get('min')}")
    if (r := on(g, "emaSlope")): ent.append(f"the {r.get('length')}-EMA rising at least {r.get('min')} over {r.get('lookback')} candles")
    ex = [f"Checked on {frame(c.get('exitBars'), frame(c.get('resample')))}; any of these sells:"]
    if (r := on(x, "slopeFlipDown")): ex.append(f"the MACD slope turns down (smoothed over {r.get('smoothing', 2)} candles)")
    if (r := on(x, "stopLoss")): ex.append(f"stop-loss −{r.get('pct')}% from the buy, on the candle close")
    if (r := on(x, "takeProfit")): ex.append(f"take profit +{r.get('pct')}%")
    if (r := on(x, "maxBars")): ex.append(f"held {r.get('bars')} candles")
    if (r := on(x, "macdDistance")): ex.append(f"the MACD-distance rule ({r.get('pct')}%, measured from the MACD at the buy)")
    if (r := on(x, "slopeRetrace")): ex.append(f"the slope gives back {r.get('pct')}% of its peak")
    for k, w in (("fastMacdExit", "the fast MACD exit"), ("macdCrossDown", "MACD crosses below its signal line"),
                 ("macdBelowZero", "MACD falls below zero"), ("accelNegative", "MACD acceleration turns negative"),
                 ("velocity", "price velocity turns negative")):
        if on(x, k): ex.append(w)
    for k, v in x.items():   # anything not translated above, by name
        if isinstance(v, dict) and v.get("enabled") and k not in (
                "slopeFlipDown", "stopLoss", "takeProfit", "maxBars", "macdDistance", "slopeRetrace",
                "fastMacdExit", "macdCrossDown", "macdBelowZero", "accelNegative", "velocity"):
            ex.append(f"{k} ({', '.join(f'{a}={b}' for a, b in v.items() if a != 'enabled')})")
    return ent, ex


def validated(meta):
    """The backtest numbers the config file was validated with, as short lines."""
    if not isinstance(meta, dict):
        return []
    out = []
    for k in ("status", "label"):
        if isinstance(meta.get(k), str) and len(meta[k]) < 140:
            out.append(meta[k])

    def walk(o, path):
        if isinstance(o, dict):
            c, d = o.get("cagr_pct"), o.get("worst_drop_pct", o.get("max_dd_pct"))
            if isinstance(c, (int, float)):
                out.append(f"{path}: {c:+.1f}%/yr" + (f", worst drop {d:.1f}%" if isinstance(d, (int, float)) else ""))
            for kk, vv in o.items():
                if isinstance(vv, dict) and kk not in ("today_setting",):
                    walk(vv, kk)
    for k, v in meta.items():
        if k.startswith("validated") and isinstance(v, dict):
            walk(v, k.replace("validated_", "").replace("_", " "))
    return out[:6]


# ── the numbers ────────────────────────────────────────────────────────────────

def pct(v, d=1):
    return "—" if v is None else f"{v:+.{d}f}%"


def when(ms):
    return datetime.datetime.fromtimestamp(ms / 1000).strftime("%d %b %Y") if ms else "—"


def replay_line(ns, summaries):
    s = next((x for x in summaries if x.get("ns") == ns), None)
    if not s:
        return "no replay yet"
    yrs = (s.get("spanDays") or 0) / 365.0
    return (f"{pct(s.get('cagrPct'))}/yr ({pct(s.get('compoundedPct'))} over {yrs:.1f} yr), worst drop "
            f"{(s.get('maxDrawdownPct') or 0):.1f}%, {s.get('closed', 0)} trades, slippage {s.get('slippagePct', 0)}% "
            f"— computed {when(s.get('computedAt'))}")


def real_lines(ns, base, cur, t212, zer):
    """What the broker actually did for this algo."""
    if base in ("de", "us"):
        rows = [t for t in t212 if t.get("ns") == ns]
        closed = [t for t in rows if t.get("status") in ("CLOSED", "MANUAL_CLOSED")
                  and t.get("liveEntryPrice", 0) > 0 and t.get("liveExitPrice", 0) > 0]
        # From the prices and the shares sold, never the value fields: a buy filled in
        # parts can carry only its first part's value (GFT 1 Sep: €140 for €403 bought),
        # which turned a 6% loss into a €237 "gain".
        money = sum((t["liveExitPrice"] - t["liveEntryPrice"]) * (t.get("liveExitQty") or t.get("qty", 0))
                    for t in closed)
        rets = [(t["liveExitPrice"] / t["liveEntryPrice"] - 1) * 100 for t in closed]
        opn = [t for t in rows if t.get("status") in ("OPEN", "EXITING")]
    elif base == "in":
        rows = [t for t in zer if t.get("ns") == ns]
        closed = [t for t in rows if t.get("status") in ("CLOSED", "MANUAL_CLOSED")
                  and t.get("entryPrice", 0) > 0 and t.get("exitPrice", 0) > 0]
        money = sum((t["exitPrice"] - t["entryPrice"]) * t.get("qty", 0) for t in closed)
        rets = [(t["exitPrice"] / t["entryPrice"] - 1) * 100 for t in closed]
        opn = [t for t in rows if t.get("status") in ("OPEN", "EXITING")]
    else:
        mirror = load(f"binance_live_mirror_{ns}.json", {}) or {}
        rows = mirror.get("trades", [])
        closed = [t for t in rows if t.get("status") == "CLOSED"]
        money = sum(t.get("livePnlQuote", 0) or 0 for t in closed)
        rets = []
        opn = [t for t in rows if t.get("status") == "OPEN"]
    if not rows:
        return None
    wins = sum(1 for r in rets if r > 0)
    line = (f"{len(closed)} real sales, {wins} won" if rets else f"{len(closed)} real sales") + \
           f", {cur}{money:,.2f} before charges"
    if rets:
        line += f", average {sum(rets) / len(rets):+.2f}% a sale"
    unpriced = [t for t in rows if base == "in" and t.get("status") == "MANUAL_CLOSED"
                and t.get("entryPrice", 0) > 0 and not t.get("exitPrice", 0) > 0]
    if unpriced:
        line += f" ({len(unpriced)} more sold by the Zerodha stop or target, not priced in this record)"
    held = ", ".join(sorted({t.get("symbol", "?") for t in opn}))
    return line + (f"; holding now: {held}" if held else "; nothing held now")


# ── the file ───────────────────────────────────────────────────────────────────

summaries = load("whatif_summaries.json", [])
if isinstance(summaries, dict):
    inner = summaries.get("items") or summaries.get("summaries") or summaries
    summaries = list(inner.values()) if isinstance(inner, dict) else inner
summaries = [s for s in summaries if isinstance(s, dict)]
t212 = (load("t212_live_mirror.json", {}) or {}).get("trades", [])
zer = (load("zerodha_live_trades.json", {}) or {}).get("trades", [])
quality = {a.get("ns"): a for a in (load("quality_cheap.json", {}) or {}).get("algos", [])}
floors = (load("floor_algos.json", {}) or {}).get("algos", [])

L = [f"# Algos overview — every space", "",
     f"Generated {datetime.datetime.now().strftime('%d %b %Y %H:%M')} from the phone's own records. "
     "BOOK = the algo's paper record (the strategy's own prices); REAL = what the broker filled; "
     "REPLAY = the 💡 run over all fetchable history; VALIDATED = the backtest the config file was "
     "approved with. Percentages are before charges unless the replay says otherwise.", ""]
if SRC:
    L += [f"> **Read from copies taken earlier, not live from the phone** (`{SRC}`). Rows marked "
          "\"not read\" and any missing section need a fresh read: run this script with the phone connected.", ""]
if not floors:
    L += ["> The floor algos were not in this read.", ""]

for base, label, cur in SPACES:
    buckets = (load(f"buckets_{base}.json", {}) or {}).get("buckets", [])
    fl = [a for a in floors if a.get("space") == base]
    if not buckets and not fl:
        continue
    L += [f"## {label}", ""]
    rows = []
    for b in buckets:
        ns = f"{base}__{b['id']}"
        lab = load(f"strategy_lab_{ns}.json", None)
        if lab is None:
            lab = {"_missing": True}
        gate = load(f"macd_gate_{ns}.json", {}) or {}
        closed = [t for t in lab.get("trades", []) if t.get("status") == "CLOSED"]
        booked = sum(t.get("pctReturn", 0) for t in closed)
        if gate.get("enabled") and gate.get("injected", 0) > 0:
            val = sum(s.get("capital", 0) for s in gate.get("slots", []))
            booked = (val / gate["injected"] - 1) * 100
        s = next((x for x in summaries if x.get("ns") == ns), {})
        rows.append((b, ns, lab, gate, closed, booked, s))
    if rows:
        L += ["| Algo | Money | Seats · list | Book (booked) | Replay /yr | Replay worst drop |", "|---|---|---|---|---|---|"]
        for b, ns, lab, gate, closed, booked, s in rows:
            money = (f"**REAL** {cur}{b.get('liveBudget', 0):,.0f}" if b.get("live") and b.get("liveBudget", 0) > 0
                     else "paper — real money off" if b.get("liveBudget", 0) > 0 else "paper")
            book = "not read" if lab.get("_missing") else f"{pct(booked)} ({len(closed)} closed)"
            L.append(f"| {b['name'].strip()} | {money} | {b.get('holdN')} · {len(b.get('symbols') or [])} | "
                     f"{book} | {pct(s.get('cagrPct')) if s else '—'} | "
                     f"{(str(round(s['maxDrawdownPct'], 1)) + '%') if s.get('maxDrawdownPct') is not None else '—'} |")
        L.append("")
    for b, ns, lab, gate, closed, booked, s in rows:
        L += [f"### {b['name'].strip()}", ""]
        cfg = {}
        try:
            cfg = json.loads(lab.get("configJson") or "{}")
        except Exception:
            pass
        live = b.get("live") and b.get("liveBudget", 0) > 0
        L.append(f"- **Money:** " + (f"REAL — {cur}{b['liveBudget']:,.0f} budget over {b.get('holdN')} seats"
                                     if live else f"paper — {cur}{b.get('capital', 0):,.0f} capital" +
                                     (f" (a {cur}{b['liveBudget']:,.0f} real budget is set, but real orders are switched off)"
                                      if b.get("liveBudget", 0) > 0 else "")))
        if not lab.get("_missing"):
            L.append(f"- **Config:** `{lab.get('configSource', 'preset')}`" + ("" if lab.get("enabled") else " — switched OFF"))
        if b.get("mode") == "GATE_3B":
            sel = gate.get("useScreenerSelection", True)
            L.append(f"- **Seats (MACD gate 3b):** " + (
                f"each day the {len(b.get('symbols') or [])} companies are ranked and the top {gate.get('holdN', b.get('holdN'))} "
                f"whose daily MACD slope is rising (smoothing {gate.get('smoothing', b.get('smoothing'))}) take a seat; a seat "
                f"whose slope turns down is given up once its trade has closed. A seat's money grows or shrinks with its trades."
                if sel else "every company has its own seat; the daily MACD slope alone decides when each may trade."))
        if cfg:
            ent, ex = entry_words(cfg)
            L.append("- **Entry:** " + ent[0] + ("" if len(ent) == 1 else " " + "; ".join(ent[1:]) + "."))
            L.append("- **Exit:** " + ex[0] + ("" if len(ex) == 1 else " " + "; ".join(ex[1:]) + "."))
            if base in ("de", "us") and live:
                L.append("- **Also for real money:** a stop −" + str((cfg.get("exit") or {}).get("stopLoss", {}).get("pct", 4)) +
                         "% from what was actually paid (2-hour close)" +
                         ("; a target at the week's high or near the month's high rests at Trading 212" if base == "de"
                          else "; no target (US algo buys are tested without one)") + ".")
        q = quality.get(ns)
        if q:
            L.append(f"- **Quality list:** from {q.get('listQuarter')}; news veto {'on' if q.get('veto') is not False else 'off'}, "
                     f"{len(q.get('checks') or [])} verdicts, {len(q.get('kept') or [])} kept out.")
        opn = sorted({t.get("symbol") for t in lab.get("trades", []) if t.get("status") == "OPEN"})
        wins = sum(1 for t in closed if t.get("pctReturn", 0) > 0)
        if lab.get("_missing"):
            L.append("- **Book, entry and exit rules:** not read — this algo's record was not in the copy used.")
        else:
            L.append(f"- **Book:** {pct(booked, 2)} booked, {len(closed)} closed trades ({wins} won)" +
                     (f"; open now: {', '.join(opn)}" if opn else "; nothing open"))
        real = real_lines(ns, base, cur, t212, zer)
        if real:
            L.append(f"- **Real:** {real}")
        L.append(f"- **Replay:** {replay_line(ns, summaries)}")
        v = validated(cfg.get("_meta"))
        if v:
            L.append("- **Validated:** " + "; ".join(v))
        L.append("")
    if fl:
        L += [f"### Floor algos ({label}) — paper only, by design", ""]
        for a in fl:
            st = a.get("settings") or {}
            kind = a.get("kind")
            # A floor algo's finished trade is "SOLD". Judged before charges, from its own
            # prices: India's first paper trades were ₹100 each, and the fixed ₹16 sale
            # charge alone made every one of them a loss.
            def entry_px(t):
                f, s2 = t.get("firstPrice", 0) or 0, t.get("secondPrice", 0) or 0
                return (f + s2) / 2 if s2 and t.get("halves", 1) >= 2 else f
            closed = [100 * (t["sellPrice"] / entry_px(t) - 1) for t in a.get("trades", [])
                      if t.get("status") == "SOLD" and entry_px(t) > 0 and (t.get("sellPrice") or 0) > 0]
            marks = [100 * (t["lastPrice"] / entry_px(t) - 1) for t in a.get("trades", [])
                     if t.get("status") == "OPEN" and entry_px(t) > 0 and (t.get("lastPrice") or 0) > 0]
            opn = sorted({t.get("symbol") for t in a.get("trades", []) if t.get("status") == "OPEN"})
            avg = lambda v: f"{sum(v) / len(v):+.2f}%" if v else "—"
            result = (f"{len(closed)} sold ({sum(1 for x in closed if x > 0)} won), {avg(closed)} a trade before charges"
                      + (f"; {len(marks)} open, {avg(marks)} on average now" if marks else ""))
            if kind == "floor":
                rule = (f"buys at the {st.get('lowDays', 3)}-day low (half there, half {st.get('secondPct')}% lower), "
                        f"{st.get('seats')} seats, {st.get('universeSize')} companies"
                        + ("; only when the monthly and daily trend rise" if st.get("trendFilter") else "")
                        + f". Sells: stop −{st.get('stopPct')}%" + (f", target +{st.get('targetPct')}%" if st.get("targetPct") else "")
                        + f", after {st.get('holdDays')} days"
                        + ("" if st.get("trendExit") is False or not st.get("trendFilter") else ", or when the trend breaks") + ".")
            else:
                rule = f"{kind} algo; {st.get('seats')} seats, {st.get('universeSize')} companies, list: {st.get('universeMode')}."
            L.append(f"- **{a.get('name')}** ({'on' if a.get('enabled') else 'paused'}) — {rule} "
                     f"{result}" + (f"; open now: {', '.join(opn)}" if opn else "") +
                     f". Replay: {replay_line(a.get('ns', ''), summaries)}.")
        L.append("")

open(OUT, "w", encoding="utf-8").write("\n".join(L) + "\n")
print(f"wrote {OUT}: {len(L)} lines")

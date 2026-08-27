# Replicating the tested NSE setup in the Android app's India space

Everything below was measured in the Python lab on Kite 60-minute data. Source results:
`RESULTS_NSE500_3b_flat_fee.md` in this folder. Follow it exactly and the app should reproduce the
ledger; where the app already differs, that difference is called out.

---

## 1. Strategy config — use the CONSERVATIVE champion, not the aggressive one

`B_SLOW_240m-120m_STOCKS-conservative_nasdaq+26pct-yr_lower-drawdown.json`

**This is the opposite of the German/DAX choice.** Aggressive is the German champion; on NSE
conservative won drawdown and Sharpe in *both* five-year windows, and CAGR in one:

| | 2015–2020 | 2020–2026 |
|---|---|---|
| CAGR | aggressive +0.69pp | **conservative +4.08pp** |
| Max DD | **conservative −1.9pp** | **conservative −1.1pp** |
| Sharpe | **conservative +0.10** | **conservative +0.09** |

Config choice does not transfer between markets. Do not reuse the German preset here.

### Frames and warmup

| Setting | Value |
|---|---|
| Entry frame | `240min` (resampled) |
| Exit frame | `120min` |
| Base feed | Kite `60minute` — divides both 240 and 120 |
| MACD warmup | **240 bars of the ENTRY frame**, not of the base feed |
| MACD | 12 / 26 / 9 |

The warmup is the single most common porting mistake. 240 bars of a 240-minute frame on a 375-minute
NSE session is roughly **80 trading days** of history per symbol before the first signal can fire.
Counting 240 *base* bars instead gives ~34 days and will fire far too early.

### Exit rules active in this champion

- `slopeFlipDown` — enabled, smoothing **2** (note: this is the *exit* smoothing, unrelated to the
  portfolio-layer slope smoothing of 5 below)
- `stopLoss` — enabled, **4%**
- `macdDistance` qualifier — enabled, **30%**
- `fastMacdExit` — **disabled**. Leave it off; on the German feed turning it on at the wrong
  resolution cost −28%/yr.
- `takeProfit`, `maxBars`, `slopeRetrace`, `velocity`, `session` — all disabled
- `minHoldBars` 0, exit mode `ANY`

---

## 2. Portfolio layer — 3B

| Setting | Value |
|---|---|
| Mode | **3b** — flag on the gate, exit at the strategy's own natural close |
| Seats | **7** |
| Capital per seat | **₹25,000** (₹1,75,000 total) |
| Daily-MACD slope smoothing | **5** |
| Confirm days | **1** |
| Screener lookback | **90 days** |
| Screener weights | 0.72 trailing return · 0.57 annualised vol · 0.45 %-above-SMA50 |
| Lookback boundary | **exclusive** — `date > cutoff`, not `>=` |

Rules that must hold exactly:

1. A seat holds **one symbol or cash**. It cannot buy anything else while enrolled.
2. **Enrollment is not a purchase.** The screener fills all seats immediately; a seat buys only when
   its own symbol's MACD entry fires on the 240-min frame.
3. Capital **compounds inside a seat**. Seat identity persists; it is the unit of compounding.
4. A seat is freed only when its symbol's daily-MACD slope is non-positive for `confirm_days`
   consecutive days — **and in 3b, not while a position is open**. A flagged name mid-trade keeps its
   seat until the trade exits naturally.
5. A closing trade does **not** free a seat.
6. The slope counter **resets to zero** on any positive-slope day. A stale streak evicts far too early.

---

## 3. Fees — model the flat fee, do not fold it into slippage

- **₹15.8 flat per SELL**, subtracted as an absolute amount from the seat's capital once per closed
  trade. Plus 0.20% slippage on the round trip as usual.
- A flat fee is **not** slippage. Slippage scales with position size; this does not. Measured cost at
  5 seats: −26.02pp of CAGR at ₹1,000/seat, −0.74pp at ₹25,000/seat.
- **A seat can be driven to zero.** At ₹1,000 and ₹2,500 per seat a seat hit zero in testing
  (2023-07-06 and 2025-06-12). Because capital compounds inside a seat, zero is permanent — that
  fraction of the portfolio never trades again. Percentage slippage cannot do this. If the app clamps
  at zero, log it loudly; if it allows negative capital, that is a bug.
- **₹25,000 per seat is the floor.** Below ₹5,000 the strategy does not work at all.
- Fee drag is worse in weak markets (~1.0pp in 2015–2020 vs ~0.55pp in 2020–2026 on near-identical
  trade counts), because smaller seat capital makes the same fee a larger fraction.

---

## 4. Universe

Keep what the app already does — it is the right design:

1. Download current constituents from the official index CSV (niftyindices.com), NSE archive as
   second source.
2. Accept only if the result exceeds **400 names**. A truncated or error page must never become the
   universe.
3. Fall back to the embedded list only when both downloads fail.

Verified working: the live download returned **500 names** from niftyindices.

**The embedded fallback needs updating.** Against the live list it holds **120 names no longer in the
index** and is **missing 181** that are. It is not a slightly-stale snapshot; it is substantially
different. Twelve large caps it was missing and which are in the current index: ADANIENSOL, DMART,
ENRIN, HAL, HINDZINC, HYUNDAI, INDIGO, JINDALSTEL, LTM, TATACAP, TMCV, TMPV. A merged 439-name list
is at `symbols_india_fallback.txt` in the Python repo.

---

## 5. Data

| | |
|---|---|
| Source | Kite Connect historical candles |
| Interval | `60minute` |
| **Earliest available** | **2015-02-02** — Kite has no 60-min data before this. A request for 2010 silently returns 2015+ only. |
| Per-request cap | ~400 days at 60minute; page backwards and dedupe on timestamp |
| Rate limit | 3 requests/second |
| Adjustment | **Unadjusted traded prices.** Splits and bonuses appear as gaps. |

On adjustment: raw is what the **trade engine** wants (this matches the German setup, which achieved
79/79 trade parity on raw prices). But the **screener's trailing-return factor will misread a split**
as a collapse and rank the name last. If the app computes screener factors from the same raw feed,
names that split inside the 90-day lookback will be wrongly excluded. Worth handling explicitly.

---

## 6. What to expect — and what not to

| Window | CAGR (after fee) | Max DD | Calmar | Sharpe | Trades |
|---|---|---|---|---|---|
| 2015–2020 | **13.85%** | 28.2% | 0.49 | 0.80 | 741 |
| 2020–2026 | **33.75%** | 27.1% | 1.25 | 1.18 | 757 |

**Plan on ~14%/yr, not 34%.** Same config, same seats, same fee — the only difference is which five
years. 2015–2020 contains the 2018–19 midcap bear market and the COVID crash, i.e. a normal mix;
2020–2026 is a tailwind.

**Max drawdown was 27–30% in both windows.** Only the upside is regime-dependent. Never size
positions off the good window's return together with the good window's drawdown.

Both figures also carry **survivorship bias** — every name is an index member *today*, so names
dropped from the index are absent and current members are present for years when they were not in
it. This flatters 3B specifically, because the screener's job is to rank this universe and take the
top few. Treat both rows as upper bounds.

---

## 7. Expected live behaviour — read this before reporting a bug

**Most days produce no entry at all. That is correct.** Measured on the 2020–2026 NSE walk:

- 757 entries over 1,425 trading days with 7 seats = **0.53 entries per day** across the whole book
- Per seat: **one entry roughly every 13 trading days**
- **59% of days produce zero entries.** Three or more on one day is a ~2% event.

So a day, or several days, with seats enrolled and nothing bought is the *modal* outcome, not a
failure. Do not treat silence as evidence the engine is broken. What to check instead:

1. Is the feed current? A stale feed truncates everything downstream and looks exactly like "the
   strategy stopped trading."
2. Are seats **enrolled**? Names in seats with zero open positions is a correct 3B state.
3. Has the **warmup** been cleared — 240 entry-frame bars, ~80 trading days per symbol?

---

## 8. Parity checklist against the Python ledger

Pin these before comparing, or the numbers will not agree:

- [ ] **Pin the start date.** Leaving it open-ended makes the walk start at the earliest usable day.
      On comparable runs, a one-week shift in start date moved the total by ~50 percentage points —
      same trades, different seat assignment, and capital compounds per seat. This is the single
      biggest source of spurious mismatch.
- [ ] Same universe list (compare counts and diff the symbol sets first).
- [ ] Same seat count (7) and per-seat capital (₹25,000).
- [ ] Warmup counted on the **entry frame**.
- [ ] Screener lookback boundary exclusive.
- [ ] Screener tie-break: sort ascending by score then reverse — ties resolve to the
      alphabetically-last symbol. A different tie-break changes which name gets a seat.
- [ ] Flat fee applied once per **closed** trade, sell side only.
- [ ] Then diff **enrollment counts** and **trade counts** before comparing P&L. A P&L mismatch with
      matching trade counts is a fee or compounding difference; a trade-count mismatch is a data or
      warmup difference.

---

## 9. Untested — do not assume these work

- The trailing-stop / target layer in `INDIA_TRAILING_SL_TARGET_SPEC.md`. Only the stop is portable
  (the target needs support/resistance/projected-ceiling inputs the Python engine does not have), and
  the momentum-death override would be dead code because the champion's slope-flip exit fires first.
  Swapping the fixed 4% stop for a 3× ATR chandelier is **not** a like-for-like improvement — on a
  2%-ATR name that is 6% of room instead of 4%, which changes which trades survive.
- GTT intrabar stop fills. On German data, stop exits filled ~1.05pp worse than the 4% rule because
  the engine tests stops at bar close; a resting GTT should recover most of that. Untested on NSE.
- Late binding (a free seat buying whichever top-ranked name fires first). Implemented in the Python
  lab as `reinvestPoolWidth` and **it lost on all four samples tested** — trade count roughly
  doubled but Calmar fell in every case. Do not port it.
- Seat counts other than 7 at ₹25,000: CAGR across 5–12 seats was non-monotonic noise (22.4%–33.8%).
  Only Sharpe trended, rising 1.10 → 1.58. Pick seat count for smoothness, not return.

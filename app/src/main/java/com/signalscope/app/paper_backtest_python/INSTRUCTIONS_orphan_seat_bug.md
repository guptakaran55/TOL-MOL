# Instructions: orphan position / empty seat on the live 3B card

Written for the Android agent, from the Python lab side. Context: `B_Conservative`, Trading 212,
106 names, 5 seats × €80, mode `MACD gate 3b`. Card shows **4/5 slots held**, **1 REAL open** in
`DRW3`, seats are `AFX / SAP / AOF / HLAG` + one empty captioned "no eligible name" while the same
card lists **35 eligible names**.

Your diagnosis of *what* "4/5 slots" means is correct: seats hold a **name**, not shares. Enrollment
is not a purchase. That is 3B working as designed — measured on the Python side, only **48–63% of
enrollments ever produce a trade**, and 45–65% of days close nothing at all. Do not treat idle
seats as a fault.

Three corrections and one unblock follow.

---

## 1. Stop waiting on the six log lines — diagnose from the exported CSVs

You asked the user to read `WHAT THE GATE DID` before proceeding. You do not need to. The app
already exports three CSVs per run (`*_portfolio_trades.csv`, `*_enrollments.csv`, `*_equity.csv`)
and they contain everything required. I have run these checks against two of this user's real
exports (NSE 500 and Binance) — both passed all of them, which is itself a strong clue (see §3).

Run these ten assertions. Numbers 9 and 10 are the ones that catch this bug.

| # | Invariant | How to test |
|---|---|---|
| 1 | `total_value` == sum of `seatN_cap` | every row of the equity CSV |
| 2 | `pct_return` == (exit/entry − 1)×100 − `slippage_pct` | every trade |
| 3 | `pnl` == `seat_cap_before` × `pct_return`/100 | every trade |
| 4 | `seat_cap_after` == `seat_cap_before` + `pnl` | every trade |
| 5 | per seat, trade N's `seat_cap_after` == trade N+1's `seat_cap_before` | group by seat, order by seq |
| 6 | daily equity change == Σ `pnl` of trades exiting that day | join equity to trades on date |
| 7 | no symbol enrolled in two seats at once | interval overlap on enrollments |
| 8 | no overlapping enrollments within one seat | interval overlap per seat |
| **9** | **every trade sits inside its own seat's enrollment span** | join trade `(seat, symbol, enrolled_from)` to an enrollment row; assert `enrolled_from ≤ entry` and `exit ≤ enrolled_to` |
| **10** | **no seat freed while its position is open** | for each `eject_reason = slope_negative`, assert no trade on that (seat, symbol, enrolled_from) has `exit_time > enrolled_to` |

**Check 9 is the orphan detector.** An orphan is precisely a trade with no matching enrollment row.

---

## 2. The root cause is probably a 3B rule violation, not roster hygiene

You framed this as "the watch roster isn't swept when seats are rebuilt." That is a real gap and
worth fixing, but it is the *second* line of defence. Under 3B the orphan should be impossible in
the first place, because of this rule:

> **A seat is freed only when its symbol's daily-MACD slope is non-positive for `confirm_days`
> consecutive days — AND, in mode 3b, not while a position is open.** A flagged name holding an
> open trade keeps its seat until that trade exits naturally. A closing trade does not free a seat.

`DRW3` has an open position and no seat. If it ever held one, releasing it was a direct breach of
that rule. So before writing a roster sweep, answer: **did `DRW3` hold a seat, and what released
it?** Check 10 above answers that from the CSV.

Two possible worlds, and they need different fixes:

- **`DRW3` held a seat and lost it while open** → the 3b guard is wrong. Fix the guard. A roster
  sweep would hide this rather than fix it.
- **`DRW3` never held a seat and entered off a stale roster** → your rebuild-sweep diagnosis is
  right, and the fix is as you described.

Your note that the existing guard "only checks whether a seat's name has an open position at that
exact moment" points at the first world: a name that is flat at rebuild time but re-enters days
later. That is the classic hole. Note the ledger comment on the user's own card — *"nothing stops a
re-entry after a stop-out while the daily slope stays positive"* — which is exactly the sequence
that produces a flat-at-rebuild, armed-afterwards orphan.

---

## 3. The replay engine is clean; the bug is in the live path

This matters for where you look. I ran checks 1–10 against this user's exported **replay** ledgers
(NSE 500, 451 enrollments; Binance, 101 trades). **Every check passed, including 9 and 10.** The
replay walk enrolls, gates and compounds correctly.

So do not go hunting in the shared 3B walk. The defect is in the **live seat-rebuild / persistence
path** — the code that reconstructs seats after a settings change or paper reset, which replay never
executes. That is a much smaller surface.

---

## 4. Fixes, in priority order

1. **Harden the 3b release guard.** A seat may not be released while its name has an open position,
   *and* a name must not be re-armed for entry once its seat is gone. Today the check is
   instantaneous; it needs to hold across the whole rebuild.
2. **Re-attach, do not orphan.** You proposed letting the orphan run and merely blocking re-entry.
   Prefer binding it to the empty seat at reconciliation. There is an unseated open position and a
   seatless empty pocket — pairing them restores compounding, which "let it run" does not. If the
   trade finishes unseated its P&L is booked but never compounds, so it neither sizes the next
   position nor moves the 400-of-400 figure. That is silent capital leakage.
3. **Sweep the roster on rebuild** as your safety net, after 1 and 2.
4. **Fix the hardcoded caption.** "no eligible name" asserted as a diagnosis when 35 names qualified
   is worse than no caption. Print the real reason: `awaiting next evaluation`, `sold by hand — frees
   tomorrow`, `all eligible names already held`, `no data today`.
5. **Show an orphan on the card** at the moment it exists, not when it closes.

---

## 5. Two traps when you write the fix

**Never drop freed capital.** I found exactly this class of bug in the Python lab today: on a
rebalance where names left and nothing joined, the freed capital was popped out of the book and
never returned — the portfolio drained to **exactly zero** and reported **−100%** while the
per-symbol rows still showed a profit. Freed capital must go somewhere explicit (cash) and be
included in every total. Assert `Σ seat capital + cash == book value` after every rebuild.

**Seat identity is the unit of compounding, so re-seating changes results.** Measured on the Python
side: the same trade set with a different seat assignment moved a total return by **~50 percentage
points**. Whatever rule you pick for re-attaching orphans, make it deterministic and write it down —
it is not cosmetic.

---

## 6. Do not change

- Idle seats. 48–63% of enrollments never trading is correct 3B behaviour, not a bug to fix.
- The gate's "flag, exit at natural close" semantics. A closing trade must not free a seat.
- The screener tie-break or lookback boundary — the Python and Android screeners currently agree
  (both rank TATAINVEST first on 2023-11-30, 4 of 5 top picks inside the lab's top 20).

---

## 7. Report back with

- The result of checks 9 and 10 on the current export.
- Which of the two worlds in §2 is true for `DRW3`.
- Whether `Σ seat capital + cash == book value` held across the rebuild that produced the empty seat.

Do not change code before answering the first two — the two worlds need different fixes and the
wrong one masks the defect.

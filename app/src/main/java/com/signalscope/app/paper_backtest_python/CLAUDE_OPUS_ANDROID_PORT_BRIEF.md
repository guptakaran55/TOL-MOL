# Claude Opus Implementation Brief: Port the Validated US 15-Minute Strategy into SignalScope Android

## Your role

You are working inside the existing **SignalScope Android** project. Your task is to implement the already backtested Python US-market algorithm as a reliable Android **paper-trading engine** in the US section of the app.

Do not redesign the application and do not invent a different trading strategy. Treat the Python implementation as the strategy specification and preserve its behavior exactly. Integrate it into the architecture that already exists.

The first production milestone is simulation/paper trading only. It must never submit a real Trading 212 order. A future live-execution phase may reuse the engine only after indicator parity, lifecycle behavior, and duplicate protection have been proven.

---

## 1. What SignalScope is

SignalScope is a native Kotlin Android market-analysis and portfolio-management application with three workspaces:

- `IN`: Indian equities, Zerodha, NIFTY discovery.
- `US`: US equities, Trading 212 portfolio, NASDAQ discovery.
- `DE`: German/Xetra equities, Trading 212 portfolio, DAX discovery.

The app combines:

- Market discovery scans over NIFTY 500, NASDAQ 100, and DAX symbols.
- Daily technical analysis and scoring.
- Portfolio monitoring.
- Yahoo Finance historical candles and live quote streaming.
- Zerodha and Trading 212 broker integrations.
- Persistent watchlists, scan snapshots, paper/dummy alerts, and order audit logs.
- A hybrid UI: native Android activity/service code plus a large HTML/JavaScript dashboard in a WebView.

Technology and constraints:

- Kotlin/JVM 17.
- Android API 26 minimum, API 34 target.
- OkHttp, Gson, and Kotlin coroutines.
- No Jetpack Compose.
- No Room database currently; persistent feature stores use small JSON files or SharedPreferences.
- Long-running work is handled by `ScanService`, an Android foreground service with wake/Wi-Fi locks.
- The dashboard is `app/src/main/assets/dashboard.html`.
- Kotlin-to-dashboard actions use `MainActivity.DashboardBridge` and `@JavascriptInterface`.
- Kotlin pushes state to JavaScript with `webView.evaluateJavascript(...)`.

The Graphify report identifies `DashboardBridge`, `MainActivity`, `YahooFinanceClient`, `Indicators`, `StockAnalyzer`, and `Trading212Client` as central integration points. Avoid introducing a disconnected second application architecture.

---

## 2. Existing architecture relevant to this feature

Read these files before implementing:

### Authoritative Python strategy

- `app/src/main/java/com/signalscope/app/paper_backtest_python/backtest.py`
- `app/src/main/java/com/signalscope/app/paper_backtest_python/README.md`
- `app/src/main/java/com/signalscope/app/paper_backtest_python/symbols_us_example.txt`

### Market data and indicators

- `network/Yahoofinanceclient.kt`
  - `YahooFinanceClient.fetchCandles15m(...)` already fetches 60 days of 15-minute candles with `includePrePost=false`.
  - `CandleData.timestamp` is epoch seconds.
- `network/YahooFinanceStreamClient.kt`
  - Streams live Yahoo quotes over WebSocket.
- `trading/LivePortfolioBars.kt`
  - Builds process-local 15-minute bars from live ticks.
  - It retains only 32 completed bars, so it is **not sufficient** for this strategy's 200-bar SMA and 240-bar warm-up.
  - Its multi-timeframe MACD logic is a different feature and must not replace the Python strategy.
- `util/Indicators.kt`
  - Existing EMA, SMA, MACD, smoothed MACD slope, and ADX utilities.
  - Important parity warning: the Python strategy's ADX uses Wilder-style EWM with `alpha = 1 / 14`. The current Kotlin `Indicators.adx()` calls the generic EMA with `alpha = 2 / (period + 1)`. These are not mathematically identical. Implement a strategy-specific Wilder EWM/ADX or carefully add a reusable Wilder function without changing unrelated daily-scoring results.

### Android execution and persistence patterns

- `service/ScanService.kt`
  - Foreground service, coroutine scope, scan actions, locking, notifications, persistence, and scheduling patterns.
- `data/DiscoveryResultStore.kt`
- `data/PortfolioResultStore.kt`
- `data/LiveBuyTrackerStore.kt`
- `data/WatchlistStore.kt`
- `data/GttActivityLog.kt`
  - These demonstrate atomic JSON writes using temporary-file-plus-rename and append-only JSONL auditing.
- `data/ConfigManager.kt`
  - Stores workspace and feature settings.
- `data/Models.kt`
  - Shared app models.

### US portfolio and broker integration

- `network/Trading212Client.kt`
  - Reads the live Trading 212 portfolio and can place real orders.
  - T212 symbols such as `AAPL_US_EQ` are mapped to Yahoo symbols such as `AAPL`.
- `trading/Trading212TrailingManager.kt`
- `data/T212OcoStore.kt`
- `ui/MainActivity.kt`
  - Fetches and enriches the T212 portfolio.
  - Owns the live Yahoo stream while the activity is alive.
  - Exposes the WebView bridge.
- `app/src/main/assets/dashboard.html`
  - Renders the US workspace and Trading 212 portfolio.

Do not call `Trading212Client.placeOrder()` from the paper engine.

---

## 3. Exact strategy to port

The source of truth is `paper_backtest_python/backtest.py`. The constants are:

```text
ENTRY_SMOOTHING = 5
EXIT_SMOOTHING = 2
MIN_ADX_FOR_ENTRY = 20.0
DISASTER_FLOOR = 0.85
NOTIONAL = 1000.0
MIN_BARS = 240
BAR_INTERVAL = 15 minutes
```

Use only fully closed regular-session 15-minute bars. Yahoo is already called with `includePrePost=false`.

### Indicator formulas

For closes `C`:

```text
EMA12 = EMA(C, span=12, adjust=false)
EMA26 = EMA(C, span=26, adjust=false)
MACD = EMA12 - EMA26
MACD_SIGNAL = EMA(MACD, span=9, adjust=false)
MACD_HISTOGRAM = MACD - MACD_SIGNAL

RAW_MACD_SLOPE[0] = 0
RAW_MACD_SLOPE[i] = MACD[i] - MACD[i-1]

SLOW_SLOPE = EMA(RAW_MACD_SLOPE, span=5, adjust=false)
FAST_SLOPE = EMA(RAW_MACD_SLOPE, span=2, adjust=false)

SMA200 = arithmetic rolling mean of the last 200 closes
```

For ADX(14), match the Python implementation:

```text
upMove = high[i] - high[i-1]
downMove = low[i-1] - low[i]

plusDM = upMove when upMove > downMove and upMove > 0, otherwise 0
minusDM = downMove when downMove > upMove and downMove > 0, otherwise 0

TR = max(
  high - low,
  abs(high - previousClose),
  abs(low - previousClose)
)

ATR, smoothed plusDM, smoothed minusDM, and final ADX use
EWM(alpha = 1 / 14, adjust=false).
```

Do not silently use the current generic Kotlin EMA for the ADX smoothing. Golden tests must prove parity.

### State machine and signal precedence

Process bars chronologically. Skip decisions until the current index is at least 240.

There may be at most one open paper position per symbol.

For each new closed bar:

1. If a position is open, evaluate the catastrophe exit first:

   ```text
   close < entryPrice * 0.85
   ```

   The comparison is strict `<`. Exit at the bar close and perform no other action on that bar.

2. If a position remains open, evaluate the normal sell:

   ```text
   previous FAST_SLOPE > 0
   current FAST_SLOPE <= 0
   ```

   Exit at the bar close and perform no other action on that bar.

3. If there is no open position, detect the entry hook:

   ```text
   previous SLOW_SLOPE < 0
   current SLOW_SLOPE >= 0
   ```

4. Gate the entry:

   ```text
   ADX >= 20
   close > SMA200
   ```

   Both comparisons must match the Python boundaries exactly.

5. If both gates pass, open at the current bar close with a fixed `$1,000` paper notional.

Use fractional paper quantity:

```text
quantity = 1000.0 / entryPrice
pnl = (exitPrice - entryPrice) * quantity
returnPct = (exitPrice - entryPrice) / entryPrice * 100
```

Do not add commissions, slippage, spread, ATR stops, trailing stops, Trading 212 OCO behavior, app buy scores, RSI filters, or any other conditions. Those belong to other SignalScope systems and would invalidate parity with the backtest.

---

## 4. Required runtime behavior

### Paper-only safety boundary

The new feature must be visibly labeled `PAPER` or `SIMULATION`.

It must not call:

- `Trading212Client.placeOrder`
- `Trading212Client.cancelOrder`
- Any Zerodha order/GTT method
- Existing live-buy or live-exit execution engines

Keep strategy decision output separate from broker execution. If a later live phase is built, it should consume explicit strategy events through a separately enabled execution adapter.

### Dedicated enrollment

Do not automatically run the algorithm over every NASDAQ 100 stock every 15 minutes. That would create excessive Yahoo traffic and could rate-limit the entire app.

Create a dedicated US paper-algorithm symbol enrollment list. Do not assume that every general watchlist item or every Trading 212 holding is enrolled.

The user should be able to add/remove US symbols such as `PEP`, `MSCI`, `CSGP`, `MSFT`, `NVDA`, `AVGO`, `COST`, and `LIN`.

Normalize symbols to uppercase Yahoo US symbols. Persist the enrollment list.

### Closed-bar handling

Never evaluate a partially formed Yahoo bar.

Use `America/New_York` for US market/session calculations so daylight-saving changes are handled correctly. A bar is eligible only after:

```text
barStart + 15 minutes <= current time - small completion grace
```

A completion grace of approximately 60-90 seconds is reasonable for delayed Yahoo updates.

The data endpoint excludes pre/post-market bars. Do not synthesize missing bars. On weekends and exchange holidays, simply process nothing.

### Duplicate protection

Persist `lastProcessedClosedBarTimestamp` per symbol. Repeated service runs, activity recreation, process death, reboot, or fetching overlapping 60-day data must not create duplicate decisions or duplicate trades.

The operation that advances the processed timestamp and records the decision should be synchronized and crash-safe.

### Initial activation

Historical candles are warm-up context, not permission to create dozens of old trades when the user first enables the Android feature.

On first enrollment/activation:

- Fetch enough history to calculate indicators.
- Initialize the symbol's watermark.
- Evaluate at most the latest eligible closed bar according to a clearly documented bootstrap rule.
- Do not replay the entire 60-day history into the live paper ledger.

Historical replay belongs in tests or an explicit separate `Backtest/Replay` action.

### Background operation

The algorithm should not depend on `MainActivity` remaining open. The current Yahoo WebSocket and `LivePortfolioBars` are activity/process-local and therefore cannot be the only execution source.

Integrate periodic evaluation into `ScanService` or a similarly durable service-owned path:

- Feature disabled by default.
- Run only when the US paper engine is enabled and at least one symbol is enrolled.
- Wake shortly after each eligible 15-minute US bar close.
- Reuse the service's coroutine, wake-lock, Wi-Fi-lock, notification, and error-handling patterns.
- Serialize Yahoo work with the app's existing Yahoo scan mutex or otherwise prevent discovery/portfolio/algo fetches from colliding.
- Use adaptive pacing and do not fetch an entire 60-day payload more often than necessary.

A practical first version may evaluate on manual refresh plus a service interval. Correctness and duplicate protection are more important than pretending to be tick-perfect.

### Candle caching

`fetchCandles15m()` currently downloads up to 60 days on every call. Add a bounded per-symbol 15-minute cache or store enough recent bars locally, then merge overlapping Yahoo responses by timestamp.

Keep at least 300 finalized bars so the 240-bar warm-up and indicators are safe. A larger bounded window such as 500-1,000 bars is acceptable.

Sort ascending, discard malformed prices, and deduplicate by timestamp before calculating indicators.

---

## 5. Persistence model

Follow the existing atomic JSON-store style. A suggested model is:

```text
UsPaperEngineState
  enabled
  symbols[]
  updatedAt

UsPaperSymbolState
  symbol
  lastProcessedClosedBarTimestamp
  lastFetchAt
  lastError
  openPosition?

PaperPosition
  id
  symbol
  status: OPEN | CLOSED
  notional
  quantity
  entryTime
  entryPrice
  entryReason
  exitTime?
  exitPrice?
  exitReason?
  returnPct
  pnl

PaperAuditEvent
  id
  symbol
  barTime
  signal: BUY_SIGNAL | SELL_SIGNAL | DISASTER_SIGNAL
  action: BUY | SELL | CATASTROPHE_EXIT | NO_ACTION
  price
  smoothMacdSlope
  previousSmoothMacdSlope
  fastSmoothMacdSlope
  previousFastSmoothMacdSlope
  macd
  macdSignal
  macdHistogram
  adx
  sma200?
  reason
  createdAt
```

The audit fields and reason text should closely mirror Python's `audit_row()` output. Gated entry hooks must be recorded as `BUY_SIGNAL / NO_ACTION`.

Use stable IDs, for example:

```text
symbol + barTimestamp + action
```

This gives a second layer of idempotency beyond the watermark.

Keep audit retention bounded. An append-only JSONL store modeled after `GttActivityLog` is suitable.

---

## 6. Suggested code organization

Prefer small, testable units instead of adding all logic to `MainActivity` or `ScanService`.

Suggested new files:

```text
trading/UsPaperStrategy.kt
  Pure deterministic indicator calculation and state-machine evaluation.
  No Android Context, network, files, WebView, or broker calls.

data/UsPaperTradingStore.kt
  Atomic persistent state, positions, symbol watermarks, and enrollment.

data/UsPaperAuditLog.kt
  Append-only bounded audit events.

trading/UsPaperTradingEngine.kt
  Orchestration: fetch/merge finalized candles, evaluate unprocessed bars,
  persist results, and return a UI-friendly snapshot.
```

Likely existing files to modify:

```text
data/ConfigManager.kt
  Feature toggle and optional schedule/pacing settings.

service/ScanService.kt
  Manual action and durable periodic evaluation.

ui/MainActivity.kt
  DashboardBridge getters/actions and JavaScript callbacks.

app/src/main/assets/dashboard.html
  US-only paper-algorithm panel.
```

Do not place strategy math in `dashboard.html`. JavaScript is presentation and user interaction only.

Do not modify `StockAnalyzer` scoring to implement this strategy. The daily discovery scorer and the 15-minute paper strategy are separate systems.

---

## 7. US dashboard requirements

Add a compact panel visible only in the `US` workspace. It should fit the existing dashboard style and include:

- Clear title: `15m MACD Paper Algo`.
- Persistent enabled/disabled toggle.
- Prominent `PAPER — no broker orders` label.
- Enrolled symbol list with add/remove controls.
- `Run now` action.
- Last successful run and latest processed 15-minute bar.
- Data status/error per symbol.
- Open positions:
  - symbol
  - entry time and price
  - current mark
  - unrealized P&L and return
- Closed-trade history:
  - entry/exit times and prices
  - exit reason
  - realized P&L and return
- Summary:
  - open count
  - closed count
  - realized P&L
  - unrealized P&L
  - win rate
- Recent audit events, including gated signals.
- Reset-paper-ledger action with an explicit confirmation.

Follow existing bridge conventions. Example bridge surface:

```text
getUsPaperAlgoState()
setUsPaperAlgoEnabled(enabled)
addUsPaperAlgoSymbol(symbol)
removeUsPaperAlgoSymbol(symbol)
runUsPaperAlgoNow()
resetUsPaperAlgo()
```

Deliver async refreshes to a callback such as:

```text
window.onUsPaperAlgoState(json)
```

Keep all WebView strings JSON-escaped using the project's existing callback helpers.

---

## 8. Required parity and reliability tests

This port is not complete merely because it compiles.

### Golden parity fixture

Create a fixed OHLCV fixture and run it through both:

- Python `backtest.py`
- Kotlin `UsPaperStrategy`

Compare:

- MACD line.
- Slow and fast smoothed MACD slopes.
- SMA200.
- ADX.
- Every signal timestamp.
- Every action.
- Entry/exit prices.
- Return percentage and `$1,000`-notional P&L.

Use a documented floating-point tolerance, ideally `1e-6` or tighter where practical.

### State-machine tests

Cover at least:

1. Slow slope crosses upward, ADX and SMA gates pass -> buy.
2. Slow slope crosses upward but ADX `< 20` -> audited no-action.
3. Slow slope crosses upward but close `<= SMA200` -> audited no-action.
4. Fast slope crosses from positive to zero -> sell.
5. Catastrophe threshold:
   - exactly `entry * 0.85` does not exit;
   - below it exits.
6. Catastrophe exit takes precedence over normal sell.
7. No same-bar re-entry after an exit.
8. One open position maximum per symbol.
9. The same bar processed twice creates no duplicate event.
10. State survives store reload/process restart.
11. Partial current bar is ignored.
12. US daylight-saving/session boundaries are handled with `America/New_York`.

### Integration verification

- Compile with `:app:compileDebugKotlin`.
- Verify the US dashboard renders with no JavaScript errors.
- Verify the feature remains paper-only even when real T212 credentials exist.
- Verify discovery scans and T212 portfolio refresh still work.
- Verify disabling the feature stops scheduled evaluation.
- Verify no network or disk I/O runs on the Android main thread.

---

## 9. Acceptance criteria

The work is complete when:

- The strategy produces the same decisions as Python for the same finalized candles.
- ADX parity is proven rather than assumed.
- The Android engine runs only on enrolled US symbols.
- It operates without the activity needing to stay open.
- It never processes a partially closed bar.
- It cannot duplicate a trade after retries, overlapping fetches, process death, or reboot.
- Open positions, closed trades, P&L, and audit events survive restart.
- The US dashboard clearly exposes the simulator and its state.
- The implementation contains no live broker-order call path.
- Existing India, Germany, discovery, T212 portfolio, trailing, and live-stream features continue to compile and behave as before.

---

## 10. Implementation sequence

Use this order:

1. Read the Python backtest and relevant Kotlin files listed above.
2. Build the pure Kotlin strategy evaluator.
3. Add Python/Kotlin golden fixtures and prove indicator/event parity.
4. Build persistent paper state, ledger, and audit storage.
5. Build the orchestration engine with finalized-bar filtering and idempotency.
6. Add manual service/bridge execution.
7. Add the US dashboard panel.
8. Add optional periodic background scheduling.
9. Compile, test, and inspect the final diff for accidental broker calls.

If implementation details conflict with the backtest, preserve the backtest behavior and document the conflict. Do not "improve" strategy logic during the port.

---

## Final instruction

Implement this as a faithful, observable, crash-safe Android port of the validated Python paper strategy—not as a new trading experiment. Keep strategy calculation pure, persistence explicit, background orchestration durable, and broker execution physically separated.

from __future__ import annotations

from pathlib import Path
from html import escape

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import (
    PageBreak,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)


ROOT = Path(__file__).resolve().parent
OUT = ROOT / "app" / "src" / "main" / "assets" / "SignalScope_Documentation.pdf"


BASE = getSampleStyleSheet()
STYLES = {
    "title": ParagraphStyle(
        "SignalTitle",
        parent=BASE["Title"],
        fontName="Helvetica-Bold",
        fontSize=24,
        leading=30,
        alignment=TA_CENTER,
        textColor=colors.HexColor("#0f172a"),
        spaceAfter=8,
    ),
    "subtitle": ParagraphStyle(
        "SignalSubtitle",
        parent=BASE["Normal"],
        fontName="Helvetica",
        fontSize=10,
        leading=14,
        alignment=TA_CENTER,
        textColor=colors.HexColor("#475569"),
        spaceAfter=16,
    ),
    "h1": ParagraphStyle(
        "SignalH1",
        parent=BASE["Heading1"],
        fontName="Helvetica-Bold",
        fontSize=17,
        leading=22,
        textColor=colors.HexColor("#0f172a"),
        spaceBefore=10,
        spaceAfter=8,
    ),
    "h2": ParagraphStyle(
        "SignalH2",
        parent=BASE["Heading2"],
        fontName="Helvetica-Bold",
        fontSize=12,
        leading=15,
        textColor=colors.HexColor("#1e293b"),
        spaceBefore=8,
        spaceAfter=5,
    ),
    "body": ParagraphStyle(
        "SignalBody",
        parent=BASE["BodyText"],
        fontName="Helvetica",
        fontSize=9,
        leading=12,
        textColor=colors.HexColor("#1f2937"),
        spaceAfter=6,
    ),
    "small": ParagraphStyle(
        "SignalSmall",
        parent=BASE["BodyText"],
        fontName="Helvetica",
        fontSize=7.7,
        leading=10,
        textColor=colors.HexColor("#475569"),
        spaceAfter=4,
    ),
    "bullet": ParagraphStyle(
        "SignalBullet",
        parent=BASE["BodyText"],
        fontName="Helvetica",
        fontSize=8.7,
        leading=11.4,
        leftIndent=11,
        firstLineIndent=-8,
        textColor=colors.HexColor("#1f2937"),
        spaceAfter=3,
    ),
    "note": ParagraphStyle(
        "SignalNote",
        parent=BASE["BodyText"],
        fontName="Helvetica-Oblique",
        fontSize=8.2,
        leading=11,
        textColor=colors.HexColor("#334155"),
        backColor=colors.HexColor("#f8fafc"),
        borderColor=colors.HexColor("#cbd5e1"),
        borderWidth=0.5,
        borderPadding=6,
        spaceBefore=5,
        spaceAfter=8,
    ),
}


def txt(value: object) -> str:
    return escape(str(value)).replace("\n", "<br/>")


def p(value: object, style: str = "body") -> Paragraph:
    return Paragraph(txt(value), STYLES[style])


def bullet(value: object) -> Paragraph:
    return Paragraph("- " + txt(value), STYLES["bullet"])


def cell(value: object, style: str = "small") -> Paragraph:
    return p(value, style)


def table(rows, widths=None, header=True, font_size=7.7):
    body_style = ParagraphStyle(
        f"Cell{font_size}",
        parent=STYLES["small"],
        fontSize=font_size,
        leading=font_size + 2.3,
        spaceAfter=0,
    )
    header_style = ParagraphStyle(
        f"Head{font_size}",
        parent=body_style,
        fontName="Helvetica-Bold",
        textColor=colors.white,
    )
    rendered = []
    for r_idx, row in enumerate(rows):
        style = header_style if header and r_idx == 0 else body_style
        rendered.append([Paragraph(txt(col), style) for col in row])
    t = Table(rendered, colWidths=widths, repeatRows=1 if header else 0, hAlign="LEFT")
    commands = [
        ("GRID", (0, 0), (-1, -1), 0.25, colors.HexColor("#cbd5e1")),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 4),
        ("RIGHTPADDING", (0, 0), (-1, -1), 4),
        ("TOPPADDING", (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
        ("ROWBACKGROUNDS", (0, 1 if header else 0), (-1, -1), [colors.white, colors.HexColor("#f8fafc")]),
    ]
    if header:
        commands += [
            ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#0f766e")),
            ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ]
    t.setStyle(TableStyle(commands))
    return t


def section(story, title, body=None):
    story.append(p(title, "h1"))
    if body:
        story.append(p(body))


def subsection(story, title):
    story.append(p(title, "h2"))


def footer(canvas, doc):
    canvas.saveState()
    canvas.setFont("Helvetica", 7)
    canvas.setFillColor(colors.HexColor("#64748b"))
    canvas.drawString(16 * mm, 10 * mm, "SignalScope Documentation")
    canvas.drawRightString(194 * mm, 10 * mm, f"Page {doc.page}")
    canvas.restoreState()


def build_story():
    story = []
    story.append(Spacer(1, 30 * mm))
    story.append(p("SignalScope", "title"))
    story.append(p("Operating Manual, Score Methodology, and GTT Automation Guide", "subtitle"))
    story.append(p("Updated: 2026-05-23", "subtitle"))
    story.append(Spacer(1, 8 * mm))
    story.append(p(
        "This document explains how the app thinks: how it scans, how Buy/Sell/Value scores are calculated, "
        "what the Setups lenses mean, and how the trailing stop-loss, target, soft sniper, and hard sniper "
        "GTT procedures work. It is written to match the current Android implementation.",
        "body",
    ))
    story.append(p(
        "Important: SignalScope is a decision-support tool, not financial advice. Every GTT and broker order "
        "must be reviewed in the broker app, especially after app updates, API-session expiry, network errors, "
        "corporate actions, and unusually volatile market days.",
        "note",
    ))
    story.append(PageBreak())

    section(story, "1. The Operating Model")
    for item in [
        "Discovery finds candidates. It scans broad stock lists from Yahoo Finance daily candles and creates ranked lists for Discovery, Setups, Pullbacks, Value, and sector views.",
        "Portfolio monitoring protects active positions. It scans Zerodha holdings on the configured interval, refreshes daily technical indicators, raises alerts, and can manage GTTs when you opt in.",
        "Live price streaming is an overlay. When enabled, Yahoo live quotes update LTP for portfolio/watchlist stocks and feed 15-minute bars for the soft-sniper gate. It does not yet rebuild the full daily MACD/RSI/value stack tick-by-tick.",
        "The app separates entry logic from exit logic. Buy Score is for entries; Profit Score and Protect Score are for exits; Value Score is a separate fundamental lens.",
        "Automation is gated. Creating OCO GTTs, trailing SL/target, soft sniper, hard sniper, and market exit ladder each have separate safety switches.",
    ]:
        story.append(bullet(item))

    subsection(story, "Data Sources")
    story.append(table([
        ["Area", "Source", "Notes"],
        ["Daily candles", "Yahoo Finance chart API", "2 years of daily OHLCV; cached for portfolio scans with 5-day incremental refreshes."],
        ["Fundamentals", "Yahoo Finance quoteSummary", "Used for Value Score across India, US, and Germany where fields are available."],
        ["Indian portfolio / GTT", "Zerodha Kite", "Holdings, GTT list/create/modify/delete, optional sell orders when hot auto-exit is enabled."],
        ["US/DE portfolio", "Trading 212 REST", "Portfolio fetch and order helpers; T212 OCO is simulated as separate SL and target orders."],
        ["Live price overlay", "Yahoo streamer WebSocket", "Unofficial stream used only while the live button is enabled."],
    ], widths=[34 * mm, 45 * mm, 92 * mm]))

    subsection(story, "Scan Timing")
    story.append(table([
        ["Scan", "Timing", "What it updates"],
        ["Manual discovery", "When you tap Nifty / Nasdaq / DAX scan", "Full candidate list, setups, pullbacks, value score, sector index/market views."],
        ["Automatic discovery", "Weekdays after Indian market close, currently 16:15 IST for NIFTY", "A robust post-close NIFTY scan using closed daily candles."],
        ["Portfolio monitoring", "Default every 15 minutes during configured market hours", "Holdings, LTP, daily indicators from cached/recent candles, alerts, GTT visibility."],
        ["Full portfolio refresh", "Every 4th portfolio scan, roughly hourly at 15-minute interval", "Refreshes the full candle context; quick scans still reuse cached candle history."],
        ["Daily manager tick", "Once per trading day near 15:20 IST", "Position tracker reconciliation, MACD watchdog, auto OCO creation, trailing SL, trailing target."],
        ["Live stream", "Only while live button is ON", "Portfolio/watchlist LTP and 15-minute live bars for soft-sniper confirmation."],
    ], widths=[38 * mm, 49 * mm, 84 * mm]))

    story.append(PageBreak())

    section(story, "2. Main Scores")
    story.append(p(
        "The dashboard shows several scores next to each stock. They are deliberately separate so that an entry signal "
        "does not accidentally become an exit signal, and a cheap stock does not automatically become a technical buy.",
    ))
    story.append(table([
        ["Score", "Range", "Purpose", "Primary output"],
        ["Buy Score", "0 to approx. 115", "Technical entry readiness", "STRONG BUY >= 75, MODERATE BUY >= 60"],
        ["Profit Score", "0 to approx. 58", "Profit-booking risk while trend is still alive", "BOOK PROFIT when >= 35 and price is above SMA trend"],
        ["Protect Score", "0 to approx. 58", "Capital-protection risk when structure breaks", "PROTECT CAPITAL when >= 35 and structural gate passes"],
        ["Sell Score", "max(Profit, Protect)", "Backward-compatible display score", "Used for sorting/legacy display; intent comes from the dual scores"],
        ["Value Score", "0 to 100", "Fundamental cheapness plus quality/growth/yield", "DEEP VALUE >= 80, MODERATE >= 65, MILD >= 50"],
        ["Swing / Hold style", "0 to 100 each", "Whether stock is better for swing riding or long holding", "RIDE, HOLD, MIXED, WEAK"],
    ], widths=[34 * mm, 28 * mm, 70 * mm, 39 * mm]))

    subsection(story, "Daily Indicator Stack")
    for item in [
        "Minimum candle history: 50 daily bars. SMA200 is used where possible; SMA50 is the fallback for shorter history.",
        "Indicators: SMA, EMA21, EMA200, RSI14, Bollinger 20/2, MACD 12/26/9, OBV, ADX14, ATR14, adaptive support/resistance, wave projection, and Kalman-smoothed price/MACD curves.",
        "The same StockAnalyzer output feeds Discovery, Portfolio, Setups, Pullbacks, Watchlist, and detail modals.",
    ]:
        story.append(bullet(item))

    section(story, "3. Buy Score Procedure")
    story.append(p(
        "Buy Score is a technical entry score. It rewards a rising larger trend, an improving MACD structure, a useful RSI zone, "
        "pullback location, directional trend strength, accumulation, and proximity to EMA21. It is not a guarantee to buy; it is a ranking and explanation system.",
    ))
    story.append(table([
        ["Component", "Default points", "How points are earned"],
        ["SMA trend", "25 or 15", "Price above SMA200 gives 25. If only 50-199 candles exist, price above SMA50 gives 15."],
        ["MACD inflection", "10 to 30", "Qualified setup = 30; MACD zero-cross up with acceleration = 30; slope cross up = 20; early buy = 10."],
        ["Setup bonus", "+10", "Added when the qualified MACD setup passes."],
        ["MACD low percentile bonus", "+3", "Added when MACD percentile is <= 25 and a bullish MACD event exists."],
        ["RSI graduated", "0 to 20", "Best zone is RSI 25-55. RSI 25-35 ramps upward; 35-55 tapers down. Deep RSI < 25 gets a small 3-point floor."],
        ["RSI flip bonus", "+8", "RSI just turned up while inside the 25-45 oversold/reversal zone."],
        ["Bollinger", "10 + 5", "Below mid-band earns base points; recent lower-band touch adds bonus."],
        ["ADX", "15 + 5", "ADX > 25 with +DI > -DI earns trend points; ADX > 30 adds a bonus."],
        ["OBV", "+5", "OBV above both 5-day and 20-day comparison values, suggesting accumulation."],
        ["EMA21 proximity", "0 to 10", "Maximum near or slightly below EMA21. Overextended moves above EMA21 receive fewer points."],
    ], widths=[42 * mm, 29 * mm, 100 * mm], font_size=7.3))
    story.append(p(
        "Buy labels: STRONG BUY when Buy Score >= 75; MODERATE BUY when Buy Score >= 60; otherwise NO SIGNAL.",
        "note",
    ))

    subsection(story, "Qualified MACD Setup")
    story.append(p("The qualified setup is the stricter entry pattern behind the Classic Setups lens. It requires all of these:"))
    for item in [
        "MACD acceleration is positive.",
        "Signed MACD slope angle is above the configured minimum and below the configured maximum.",
        "SMA200 slope is at or above the configured minimum.",
        "MACD percentile is not already stretched above the configured maximum.",
    ]:
        story.append(bullet(item))

    story.append(PageBreak())

    section(story, "4. Setups Tab Lenses")
    story.append(p(
        "The Setups tab is not a replacement for Discovery. It is a focused candidate bucket for early entries. "
        "The top filter chooses which lens you are looking through.",
    ))
    story.append(table([
        ["Lens", "Meaning", "Implementation"],
        ["Classic", "The original MACD setup list", "Uses signed raw MACD slope angle, positive MACD acceleration, minimum SMA slope, and maximum MACD percentile."],
        ["Fresh Smooth Hook", "Earlier entry lens using smoothed MACD", "Previous smoothed MACD slope is negative, current smoothed slope is at/above threshold, acceleration is positive, and the smoothed MACD is still near its recent low zone."],
        ["Turned Positive Today", "Morning list of fresh smooth reversals", "Compares the previous saved discovery scan's smoothed MACD slope with the current scan. Previous < 0 and today >= 0 qualifies."],
        ["Classic + Smooth Hook", "Intersection list", "Stock must pass both Classic and Fresh Smooth Hook filters."],
    ], widths=[39 * mm, 51 * mm, 81 * mm]))

    subsection(story, "How MACD Smoothing Works")
    story.append(p(
        "Smoothed MACD uses the same style of local-linear Kalman filter used for the price curve, but it is applied to raw MACD values, not log price. "
        "The current implementation uses a fixed 126-day MACD window. Measurement noise and process noise are derived from recent MACD-difference variance, so the filter adapts to each stock's own MACD volatility. "
        "There are no user-facing smoothing-strength knobs yet; the Setups dropdown controls filter thresholds, not the filter's internal smoothing level.",
    ))
    story.append(table([
        ["Smooth field", "Meaning"],
        ["smoothMacdCurve", "Last 30 values of the Kalman-smoothed MACD curve."],
        ["smoothMacdSlope", "Current estimated slope of the smoothed MACD curve."],
        ["smoothMacdPrevSlope", "Previous estimated slope from the current analysis window."],
        ["smoothMacdAccel", "Change in smoothed slope. Positive means the smoothed curve is bending upward."],
        ["smoothMacdLowDistPct", "Where current smoothed MACD sits inside its recent smoothed range. 0% means near recent low; 100% means near recent high."],
    ], widths=[45 * mm, 126 * mm]))
    story.append(p(
        "Fresh Smooth Hook default idea: enter closer to the exact MACD slope turn, but avoid names already far away from the smooth MACD low. "
        "That is why the low-zone cap exists.",
        "note",
    ))

    section(story, "5. Sell Score Procedure")
    story.append(p(
        "Sell logic is intentionally dual-score. Profit Score tries to protect open profits before the trend is broken. "
        "Protect Score is slower but more structural: it asks whether the position itself has become unsafe.",
    ))
    subsection(story, "Profit Score: Book Profit")
    story.append(table([
        ["Component", "Default points", "How points are earned"],
        ["MACD slope cross down", "28", "Primary profit-booking event: MACD slope flips down."],
        ["MACD early sell", "15", "MACD slope is still positive but acceleration has turned negative."],
        ["MACD zero-cross down", "8", "MACD crosses below zero with negative acceleration."],
        ["MACD high-percentile bonus", "+3", "Added when MACD percentile >= 75 and a MACD sell event exists."],
        ["RSI overbought", "6 to 25", "RSI >= 60 gives early noise points; >= 65, >= 70, and > 85 give stronger tiers."],
        ["RSI turn-down bonus", "+5", "RSI just turned down after being in an overbought tier."],
        ["Bollinger stretch", "5 or 10", "Recent upper-band touch gives 5; current price >= upper band gives 10."],
        ["OBV distribution", "4 or 8", "OBV below OBV20 gives weakness; price near a 5-day high while OBV < OBV5 gives bearish divergence."],
    ], widths=[45 * mm, 29 * mm, 97 * mm], font_size=7.3))
    story.append(p("BOOK PROFIT activates when Profit Score >= 35 and the price is above the SMA trend gate.", "note"))

    subsection(story, "Protect Score: Protect Capital")
    story.append(table([
        ["Component", "Default points", "How points are earned"],
        ["Price below SMA trend", "28", "Primary structural break."],
        ["Declining SMA slope bonus", "+5", "Added if price is below SMA and the SMA slope is declining."],
        ["Bearish ADX", "10 or 15", "ADX > 25 or > 30 with -DI > +DI."],
        ["OBV declining", "12", "OBV below both 5-day and 20-day comparison values."],
        ["MACD zero-cross down", "8", "Structural momentum confirmation."],
    ], widths=[45 * mm, 29 * mm, 97 * mm], font_size=7.3))
    story.append(p(
        "PROTECT CAPITAL activates when Protect Score >= 35 and the structural gate passes: price is below SMA trend or ADX is bearish. "
        "STRONG EXIT appears when both Profit and Protect are active.",
        "note",
    ))

    story.append(PageBreak())

    section(story, "6. Value Score Procedure")
    story.append(p(
        "Value Score is a 0-100 fundamental score. It is separate from Buy Score. A high Value Score means the business looks cheaper/healthier by the available Yahoo fundamentals; it does not mean the chart has turned.",
    ))
    story.append(table([
        ["Bucket", "Max", "Inputs and scoring"],
        ["Valuation", "30", "Trailing PE, sector median PE, forward PE improving vs trailing PE, price/book, EV/EBITDA."],
        ["Quality and balance sheet", "30", "Debt/equity, net cash, current ratio, return on equity, profit margin, operating margin, CFO > net income, positive free cash flow."],
        ["Growth durability", "18", "Revenue growth, earnings growth, gross margin, profit margin plus positive earnings growth."],
        ["Shareholder yield", "10", "Dividend yield. Buyback hook exists in the model but current deterministic implementation does not award buyback points yet."],
        ["Price discount", "12", "Distance from 52-week low. Very close to 52-week low earns more points."],
        ["Value trap penalties", "subtracted", "Negative revenue growth, sharply negative earnings growth, and negative free cash flow can reduce the score."],
    ], widths=[41 * mm, 20 * mm, 110 * mm], font_size=7.3))
    story.append(table([
        ["Rating", "Threshold"],
        ["DEEP VALUE", "Value Score >= 80"],
        ["MODERATE VALUE", "Value Score >= 65"],
        ["MILD VALUE", "Value Score >= 50"],
        ["NOT ATTRACTIVE", "Value Score < 50"],
    ], widths=[55 * mm, 55 * mm]))
    story.append(p(
        "Coverage note: Yahoo fundamentals are not equally complete for every India/US/Germany stock. FII/institutional holding and explicit 3-year profit-growth are not treated as mandatory first-class score gates in this version because they are not reliably available from the same Yahoo fields across markets. The app currently uses the available Yahoo growth fields, margins, ROE, PE/PB/EV-EBITDA, cash/debt, cash flow, dividend yield, and 52-week-low discount.",
        "note",
    ))
    subsection(story, "How to Read the Value Detail View")
    for item in [
        "Tap a Value Score in Discovery or Value-style tables to open the breakdown.",
        "Each row is one bucket: points earned, maximum points, and the reason text used by the scoring code.",
        "Use Value Score for ranking and due diligence; use Buy Score/Setups for timing.",
    ]:
        story.append(bullet(item))

    section(story, "7. Alert Meanings")
    story.append(table([
        ["Alert", "Meaning", "Typical action"],
        ["BOOK_PROFIT", "Profit Score crossed threshold while trend is still structurally alive.", "Consider booking 50-70% or enable/review soft sniper."],
        ["PEAK_WARNING", "MACD is decelerating but sell intent has not fully activated.", "Prepare; do not panic-exit from this alone."],
        ["PROTECT_CAPITAL", "Protect Score crossed threshold with structural weakness.", "Review for exit or stronger downside protection."],
        ["STRONG_EXIT", "Both profit-booking and capital-protection conditions are active.", "Sell-all bias; verify broker state and GTTs."],
        ["MACD_FLIP", "Daily histogram watchdog confirmed a sustained MACD deterioration.", "Hard sniper may be placed; optional exit ladder only if enabled."],
        ["SOFT_SNIPER", "Soft sniper placed a conditional sell GTT after BOOK_PROFIT plus gates.", "Review the newly placed SELL GTT in broker app."],
    ], widths=[35 * mm, 78 * mm, 58 * mm], font_size=7.2))

    story.append(PageBreak())

    section(story, "8. GTT Setup Overview")
    story.append(p(
        "SignalScope uses GTTs as conditional broker-side protection. GTTs are not market orders. They sit at the broker and trigger only when price conditions are met. "
        "For Zerodha, trigger values are rounded to 0.05 ticks and kept within Kite's +/-25% hard bounds around last price.",
    ))
    story.append(table([
        ["Procedure", "Creates or modifies", "Primary safety gates"],
        ["Watchlist Buy GTT", "Single BUY GTT", "Qty > 0, trigger below LTP, trigger clamped to 75%-99.9% of LTP, limit = trigger x 1.005."],
        ["Manual OCO Sell GTT", "Two-leg SELL GTT: SL + target", "SL below LTP, target above LTP, both clamped to +/-25% and rounded."],
        ["Bulk OCO Sell GTT", "Sequential OCO creation for many holdings", "Preflight active SELL GTTs; avoids downgrading an existing higher SL/target; 1 second spacing."],
        ["Auto OCO creation", "Daily manager creates missing OCO", "autoCreateGtt on, valid support/resistance, holding at least 1 day old, S/R range >= 2 x ATR."],
        ["Trailing SL", "Modifies existing SL leg upward", "autoTrailingEnabled on, never lowers SL, min-hold floor rules, Kite bounds."],
        ["Trailing target", "Modifies OCO target upward", "autoTrailingTargetEnabled on, never lowers target, step >= 1%, daily jump cap."],
        ["Soft sniper", "Replaces SELL GTTs with single SELL stop GTT", "autoSoftSniperEnabled and autoCreateGtt on, BOOK_PROFIT, gain >= threshold, live/fallback gate, volume gate."],
        ["Hard sniper", "Replaces SELL GTTs with tighter single SELL stop GTT", "Daily MACD watchdog confirmation; autoCreateGtt needed for placement."],
    ], widths=[36 * mm, 45 * mm, 90 * mm], font_size=7.1))

    subsection(story, "Manual OCO Sell GTT")
    for item in [
        "Use from Portfolio when you want a normal protective bracket: stop-loss below LTP and target above LTP.",
        "SL trigger is clamped into [LTP x 0.75, LTP x 0.999]. Target trigger is clamped into [LTP x 1.001, LTP x 1.25].",
        "Both legs are SELL limit orders. Manual OCO uses the selected triggers as both trigger and limit levels in the bridge. Auto-created/trailing flows use slippage pads.",
        "After creation, always check Kite to verify quantity, trigger, limit, and status.",
    ]:
        story.append(bullet(item))

    subsection(story, "Bulk OCO Sell GTT")
    for item in [
        "Bulk OCO is designed for setting protection across many current holdings.",
        "Before replacing a symbol's SELL GTTs, the app checks whether an existing SL or target is already better. If existing protection is higher, it skips rather than downgrades.",
        "The app spaces calls to reduce broker API pressure and records results in the GTT Audit log.",
    ]:
        story.append(bullet(item))

    section(story, "9. Trailing Stop-Loss Procedure")
    story.append(p(
        "The trailing SL engine is profit-adaptive and peak-anchored. It runs in the daily manager tick, not on every live tick. "
        "It only modifies live GTTs when autoTrailingEnabled is enabled.",
    ))
    story.append(table([
        ["Step", "Formula / rule"],
        ["Track position state", "PositionTracker records entry price, peak price, peak time, last SL, and MACD histogram state."],
        ["Compute gain at peak", "gain = (peakPrice - entryPrice) / entryPrice."],
        ["Choose ATR multiple", "<=5% gain: 3.0x ATR; <=10%: 2.5x; <=20%: 2.0x; <=30%: 1.5x; otherwise 1.0x. A fixed ATR multiple can override this."],
        ["Apply time decay", "If no fresh peak for >=10 days, multiply by 0.80. If >=20 days, multiply by 0.60."],
        ["Chandelier stop", "chandelier = peakPrice - atrMultiple x ATR."],
        ["Support floor", "Use support level only if it is valid and below LTP."],
        ["Candidate SL", "max(supportFloor, chandelier, previousSL). The previous SL rule makes it monotonic: it never moves lower."],
        ["Hard floor", "entry x (1 - newPositionHardStopPct). Default hard stop pct is 5%, so floor is around entry x 0.95."],
        ["Kite bounds", "Final trigger is clamped to [LTP x 0.75, LTP x 0.999] and rounded to 0.05."],
    ], widths=[43 * mm, 128 * mm], font_size=7.2))
    story.append(p(
        "First 5 holding days: the app does not tighten the full profit-adaptive trail. It only raises a too-low SL to the hard floor when possible. "
        "After the minimum hold period, the full trailing formula is allowed.",
        "note",
    ))

    story.append(PageBreak())

    section(story, "10. Target Setup and Trailing Target Procedure")
    subsection(story, "Initial Auto-Created Target")
    story.append(p(
        "When the daily manager auto-creates an OCO for a holding with no SL GTT, it chooses the target from chart structure and volatility.",
    ))
    for item in [
        "Target structure candidate = projected wave ceiling when above LTP, otherwise resistance.",
        "Target volatility candidate = LTP + targetAtrMultiple x ATR. Default targetAtrMultiple is 2.0.",
        "Initial target = max(structure candidate, volatility candidate), capped at LTP x 1.25 and rounded to 0.05.",
        "Target limit price for auto-created OCO is target trigger x 0.99.",
    ]:
        story.append(bullet(item))

    subsection(story, "Trailing Target")
    story.append(p(
        "Trailing target is optional and separate from trailing SL. It prevents a strong trend from being capped by an old resistance target.",
    ))
    story.append(table([
        ["Rule", "Details"],
        ["Master switch", "autoTrailingTargetEnabled must be true."],
        ["Candidate", "candidateTarget = LTP + targetAtrMultiple x ATR."],
        ["Ratchet", "newTarget = max(oldTarget, candidateTarget). It never lowers target."],
        ["Anti-spike cap", "target cannot jump more than targetMaxDailyStepPct in one day. Default cap is 8%."],
        ["Minimum bump", "Only modifies if new target is at least 1% above old target, to avoid API spam."],
        ["Limit price", "New target limit is target trigger x 0.99."],
    ], widths=[42 * mm, 129 * mm], font_size=7.3))

    section(story, "11. Sniper Sell GTT Procedures")
    story.append(p(
        "Sniper GTTs are defensive SELL GTTs placed when the app believes momentum is rolling over. "
        "They cancel existing SELL-side GTTs for that symbol first, then place one single-leg SELL GTT. BUY GTTs are preserved.",
    ))

    subsection(story, "Soft Sniper: Fast Conditional Protection")
    story.append(p(
        "Soft sniper bridges the gap between a fast BOOK_PROFIT alert and the slower daily MACD watchdog. It is opt-in because it places real broker-side GTTs.",
    ))
    story.append(table([
        ["Gate", "Requirement"],
        ["Master switches", "autoSoftSniperEnabled = true and autoCreateGtt = true."],
        ["Trigger source", "A BOOK_PROFIT alert is being generated and hard MACD watchdog has not already fired today."],
        ["One-shot", "Only one soft sniper placement per symbol per trading day."],
        ["Position sanity", "Valid LTP and quantity."],
        ["Gain floor", "Unrealized gain must be >= softSniperMinGainPct. Default is 5%."],
        ["Live mode gate", "If live stream is ready, the 15-minute bar must show downside protection: pullback from session high, down bar/slope, and fresh ticks."],
        ["Fallback gate", "If live stream is not ready, Profit Score must persist for 2 consecutive scan ticks."],
        ["Volume veto", "If today's volume is below 0.70x avgVol20 by default, placement is skipped."],
    ], widths=[42 * mm, 129 * mm], font_size=7.2))
    story.append(table([
        ["Buffer tier", "When used", "Default"],
        ["TIGHT", "Volume spike >= 1.30x avgVol20, MACD slope red, and after 14:00 IST", "1.5% below LTP"],
        ["NORMAL_EARLY", "Volume spike and MACD red before 14:00 IST", "3% below LTP"],
        ["LIVE_SCAN", "Live 15-minute bar confirms downside", "3% below live LTP"],
        ["NORMAL", "OBV divergence with normal volume", "3% below LTP"],
        ["LOOSE", "Score crossed without volume/OBV confirmation", "4% below LTP"],
    ], widths=[35 * mm, 96 * mm, 40 * mm], font_size=7.2))
    story.append(p(
        "Soft sniper trigger = execution LTP x (1 - selected buffer). Limit = trigger x (1 - softSniperLimitPct), default 0.5% below trigger. "
        "The result appears as a SOFT_SNIPER alert and in the GTT audit trail.",
        "note",
    ))

    subsection(story, "Hard Sniper: Daily MACD Watchdog")
    story.append(p(
        "The hard sniper is tied to the daily MACD histogram watchdog. It waits for sustained negative histogram behavior based on each stock's natural MACD cycle. "
        "When it confirms, it can place a tighter single-leg SELL GTT around 1.5% below LTP.",
    ))
    for item in [
        "The watchdog uses minBarsFilter, derived from recent MACD histogram peak/trough rhythm. Fast stocks require fewer bars; slow stocks require more.",
        "When the watchdog fires, the app records a MACD_FLIP alert and suppresses duplicate BOOK_PROFIT noise for that symbol that day.",
        "If autoCreateGtt is enabled, existing SELL GTTs are cancelled and a sniper SELL GTT is created at approximately LTP x 0.985, with limit around trigger x 0.985.",
        "If autoExitEnabled is also enabled, the app may run the hot exit ladder: limit at LTP x 0.998, then limit at LTP x 0.995, then market order if still unfilled. Keep this off until you have observed behavior carefully.",
    ]:
        story.append(bullet(item))

    story.append(PageBreak())

    section(story, "12. Live Stream and 15-Minute Bars")
    story.append(p(
        "The live button subscribes to Yahoo live quotes for Zerodha holdings, cached portfolio symbols, Trading 212 symbols, and watchlist symbols. "
        "The UI LTP updates live. A local 15-minute bar builder records live quote shape for soft-sniper confirmation.",
    ))
    story.append(table([
        ["Live 15m condition", "Default rule"],
        ["Freshness", "Latest quote must be no older than 90 seconds."],
        ["Bar shape", "Current 15m bar needs at least 3 ticks and either 2 minutes of age or a completed previous bar."],
        ["Pullback", "Price must be at least 0.75% below the session high."],
        ["Downside bar", "Current bar move <= -0.20% or close slope <= -0.30%."],
        ["Downside turn", "Previous slope not already deeply negative, or drop from high is large enough."],
    ], widths=[50 * mm, 121 * mm], font_size=7.3))
    story.append(p(
        "Current limitation: live ticks do not yet recompute the full daily MACD, smooth MACD, RSI, ADX, or Value Score in real time. "
        "Those remain daily-candle/scan-driven. Live bars only sharpen the soft-sniper timing when the stream is healthy.",
        "note",
    ))

    section(story, "13. Daily Playbooks")
    subsection(story, "Morning Discovery")
    for item in [
        "Open Discovery and Setups after the post-close or morning scan has completed.",
        "Sort Discovery by Buy Score for technical strength and by Value Score for fundamental cheapness.",
        "Use Setups -> Turned Positive Today to see stocks whose saved smooth MACD slope changed from negative to non-negative between scans.",
        "Use Fresh Smooth Hook for earlier entries; use Classic + Smooth Hook when you want stricter confirmation.",
    ]:
        story.append(bullet(item))

    subsection(story, "Entry")
    for item in [
        "Do not buy just because one score is high. Check chart detail: trend, MACD phase, RSI, support/resistance, risk/reward, and value-score details.",
        "For watchlist dips, create a BUY GTT only after verifying trigger, quantity, and limit.",
        "For fresh holdings, use Bulk OCO or let auto-create OCO protect them at the next daily manager tick if your settings allow it.",
    ]:
        story.append(bullet(item))

    subsection(story, "Profit Protection")
    for item in [
        "BOOK_PROFIT means the app sees profit-booking risk before full structural damage. Consider partial exit or let soft sniper place a conditional SELL GTT if enabled.",
        "PEAK_WARNING is a preparation signal, not a sell-all instruction.",
        "PROTECT_CAPITAL and STRONG_EXIT deserve faster review because structural risk is active.",
        "After any automatic GTT action, open Kite/T212 and verify actual broker state.",
    ]:
        story.append(bullet(item))

    section(story, "14. Safety Checklist")
    for item in [
        "Zerodha access tokens expire. If GTT operations fail, reconnect before trusting automation.",
        "GTT trigger does not guarantee execution at the desired price. Gaps, illiquidity, and limit prices can leave orders unfilled.",
        "Corporate actions and symbol changes can distort historical indicators and GTT levels.",
        "AutoCreateGtt creates protection. AutoTrailing modifies protection. AutoSoftSniper replaces SELL GTTs. AutoExitEnabled can place real sell orders. Treat these as increasingly hotter switches.",
        "Use the GTT Audit panel after every automation session. It is the first place to inspect what was decided, skipped, modified, or failed.",
    ]:
        story.append(bullet(item))

    return story


def main():
    OUT.parent.mkdir(parents=True, exist_ok=True)
    doc = SimpleDocTemplate(
        str(OUT),
        pagesize=A4,
        rightMargin=16 * mm,
        leftMargin=16 * mm,
        topMargin=15 * mm,
        bottomMargin=16 * mm,
        title="SignalScope Documentation",
        author="SignalScope",
        subject="Score methodology and GTT automation guide",
    )
    doc.build(build_story(), onFirstPage=footer, onLaterPages=footer)
    print(f"Wrote {OUT} ({OUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()

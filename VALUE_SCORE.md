# How the app calculates the Value Score

Written 26 Oct 2026 from the app's current code.

## What it is

The Value Score is a number from **0 to 100** that every stock gets in the Discovery scan. It asks: is this a cheap, sound, growing business, trading near its yearly low? It is built only from the company figures Yahoo publishes. Prices, charts and MACD play no part, except the distance from the 52-week low.

You see it:
- in the **Value** column of the Discovery and Value tables, as a number with a rating;
- in a stock's detail panel, with a part-by-part breakdown (for example "Valuation 18/30 — PE 11.2; PE < sector").

The rating bands are:

| Score | Rating |
|---|---|
| 80 and up | DEEP VALUE |
| 65 – 79 | MODERATE VALUE |
| 50 – 64 | MILD VALUE |
| below 50 | NOT ATTRACTIVE |
| fund, ETF, index | N/A (no company accounts, so no value score) |

## The five parts

The score adds up five parts, each capped at its own maximum, and then takes off penalties. Every rule awards points only when the figure exists. A missing figure scores 0 for that rule, never a penalty.

### 1. Valuation — up to 30 points

| What | Points |
|---|---|
| P/E below 12 / below 18 / below 25 | 10 / 7 / 4 |
| P/E at or below ¾ of its sector's median P/E / below the median | 8 / 5 |
| Forward P/E at least 10% below today's P/E (earnings expected to rise) | 4 |
| Price-to-book below 1.5 / below 2.0 | 6 / 3 |
| EV/EBITDA below 8 / below 12 | 8 / 4 |

These can add up to 36, but this part is capped at 30. A negative P/E (a loss-maker) earns nothing here.

**The sector median P/E** is worked out during each scan. It is the middle P/E of all the stocks in the same scan and the same sector, counting only positive P/Es and including the stock itself. So it compares a stock with its sector peers in that market's scan, not with the whole sector worldwide.

### 2. Quality and balance sheet — up to 30 points

| What | Points |
|---|---|
| Debt-to-equity below 0.3 / below 1.0 / below 2.0 | 6 / 4 / 2 |
| Net cash: more cash than debt | 4 |
| Current ratio at least 1.5 / at least 1.0 | 3 / 1 |
| Return on equity at least 15% / at least 10% | 6 / 3 |
| Profit margin at least 10% / at least 5% | 4 / 2 |
| Operating margin at least 15% / at least 8% | 3 / 1 |
| Cash from operations above net income, for a profitable company | 4 |
| Free cash flow positive | 3 |

These can add up to 33, but this part is capped at 30.

### 3. Growth — up to 18 points

| What | Points |
|---|---|
| Revenue growth at least 12% / at least 4% / above 0 | 7 / 4 / 2 |
| Earnings growth at least 12% / at least 4% / above 0 | 7 / 4 / 2 |
| Gross margin at least 35% | 2 |
| Profit margin at least 10% while earnings are growing | 2 |

Growth uses Yahoo's latest year-on-year quarterly figure. When that is missing, it uses the total change from the oldest to the newest annual report Yahoo lists (usually about four years) — see quirk 9.

### 4. Shareholder yield — up to 10 points

| What | Points |
|---|---|
| Dividend yield at least 3% / at least 1% | 6 / 3 |
| Share buybacks | 4, but never awarded (see below) |

### 5. Price discount — up to 12 points

| How far the price is above its 52-week low | Points |
|---|---|
| less than 15% above | 12 |
| 15% to 30% above | 5 |
| 30% or more above | 0 |

### Penalties ("value traps")

| What | Points |
|---|---|
| Revenue shrinking more than 5% | −5 |
| Earnings shrinking more than 10% | −7 |
| Free cash flow negative | −4 |

The final score is the five parts added up, minus the penalties, kept between 0 and 100.

**Highest score possible today: 96.** That is 30 + 30 + 18 + 6 + 12, because the buyback points are never awarded.

## Where the numbers come from

All figures are Yahoo's: trailing and forward P/E, price-to-book, EV/EBITDA, debt-to-equity, return on equity, margins, revenue and earnings growth, operating cash flow, free cash flow, net income, total cash and debt, current ratio, dividend yield and the 52-week low.

Yahoo reports most of these as fractions and debt-to-equity as a percentage; the app converts them all to the plain percentages and ratios used in the tables above.

## Gaps and quirks worth knowing

1. **Many point values in Settings have no effect.** The Settings screen lets you change the points for:
   - P/E versus sector, and P/E versus its own 5-year average;
   - price-to-book and EV/EBITDA;
   - each debt band;
   - return on equity;
   - cash from operations;
   - dividend yield and buybacks;
   - the 52-week low;
   - a ±8 news-sentiment adjustment.

   The score ignores all of these and uses the fixed points in the tables above. What does follow Settings:
   - every threshold (the "below 1.5", "at least 15%" figures);
   - the growth points and the penalties;
   - the rating bands.

   Changing one of the ignored point values silently changes nothing.
2. **No "P/E against its own history" rule.** There is a Settings entry for a P/E below 70% of the stock's 5-year average, but nothing calculates it.
3. **Buybacks are never scored.** The rule exists but is switched off, so the shareholder-yield part tops out at 6 of its 10.
4. **"ROCE" on screen is really return on equity.** The column labelled ROCE shows Yahoo's return on equity. Yahoo does not publish return on capital employed.
5. **The negative-free-cash-flow penalty is not listed in the breakdown.** The "Value traps" line in a stock's detail shows only the revenue and earnings penalties. The −4 for negative free cash flow is still taken off the total.
6. **The 52-week low is measured in percent above the low, not place in the year's range.** This is the same issue just fixed in the watchlist's 1-year-low sort. A stock with a narrow year can be "less than 15% above its low" while sitting high in its range. Using place in the range (0% = at the low, 100% = at the high) would match the watchlist and the area-score idea.
7. **The sector median depends on the scan.** A sector with only one or two stocks in the scan gives a weak comparison, and the stock's own P/E is part of the median it is compared with.
8. **Quarterly growth swings.** One unusual quarter can move the growth part, or trigger a penalty, by up to about 14 points.
9. **The annual fallback is a several-year total, judged as if it were one year.** When Yahoo has no quarterly growth figure, the app takes the change across all the annual reports it lists (usually about four years) and holds that total to the same "at least 12%" and "at least 4%" bars. Revenue up 15% over four years — under 4% a year — still scores as strong growth. The penalties work the same way: a 6% fall over four years counts as "shrinking more than 5%".

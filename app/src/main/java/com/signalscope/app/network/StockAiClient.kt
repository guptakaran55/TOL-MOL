package com.signalscope.app.network

import android.util.Log
import com.signalscope.app.data.ConfigManager
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.concurrent.TimeUnit

/**
 * AI-powered stock analysis client.
 *
 * Two features:
 * 1. Deep Pullback Analysis — auto-triggered when price drops >3% below EMA(21)
 *    Fetches Google News headlines → sends to LLM → returns probable cause
 *
 * 2. Stock Outlook — manually triggered from detail modal
 *    Fetches news + sends technical context → LLM returns short/long term outlook
 *
 * Supports OpenAI (ChatGPT) and Anthropic (Claude) APIs.
 */
object StockAiClient {

    private const val TAG = "StockAiClient"

    /**
     * Market context for LLM prompts. Previously every prompt was hardcoded
     * "NSE India" / "Indian NSE stocks" / "₹" — which leaked Indian terminology
     * into the US/German Outlook outputs when the user was in those spaces.
     *
     * Defaults to [IN] so any caller that doesn't pass a context preserves the
     * old behaviour exactly (no surprise regressions for legacy code paths).
     *
     * Resolved at the bridge layer from ConfigManager.currentSpace and passed
     * into every analyze entry point.
     */
    data class MarketContext(
        val space: String,            // "IN" / "US"
        val marketLabel: String,      // user-prompt label e.g. "NSE India"
        val analystPersona: String,   // system-prompt persona target
        val traderRegion: String,     // adjective form e.g. "Indian" / "US"
        val currencySymbol: String,   // "₹" / "$"
        val newsHl: String,           // Google News hl param
        val newsGl: String,           // Google News gl param
        val newsCeid: String,         // Google News ceid param
        val newsKeyword: String       // search suffix e.g. "NSE" or "" (none)
    ) {
        companion object {
            val IN = MarketContext(
                space = "IN",
                marketLabel = "NSE India",
                analystPersona = "Indian NSE stocks",
                traderRegion = "Indian",
                currencySymbol = "₹",
                newsHl = "en-IN",
                newsGl = "IN",
                newsCeid = "IN:en",
                newsKeyword = "NSE"
            )
            val US = MarketContext(
                space = "US",
                marketLabel = "US (NASDAQ/NYSE)",
                analystPersona = "US-listed equities (NASDAQ / NYSE)",
                traderRegion = "US",
                currencySymbol = "$",
                newsHl = "en-US",
                newsGl = "US",
                newsCeid = "US:en",
                newsKeyword = ""
            )
            val DE = MarketContext(
                space = "DE",
                marketLabel = "Germany / Europe (Xetra / Frankfurt)",
                analystPersona = "German and European listed equities (Xetra / Frankfurt / Euronext)",
                traderRegion = "German/European",
                currencySymbol = "€",
                // English-language German edition of Google News: surfaces both
                // local and international coverage of Xetra-listed names. (de-DE
                // would bias toward German-only sources; en works better for the
                // LLM and still returns the relevant European headlines.)
                newsHl = "en-DE",
                newsGl = "DE",
                newsCeid = "DE:en",
                newsKeyword = ""
            )
            fun fromSpace(space: String?): MarketContext = when {
                space?.equals("US", ignoreCase = true) == true -> US
                space?.equals("DE", ignoreCase = true) == true -> DE
                else -> IN
            }
        }
    }

    // MIUI/Xiaomi hardening — same recipe as Yahoo/Zerodha clients:
    // prefer IPv4 (flaky IPv6), HTTP/1.1 only (HTTP/2 is unstable on MIUI),
    // retry on connection failure, and a hard callTimeout so DNS hangs can't stall forever.
    private val ipv4PreferredDns = object : Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            val v4 = all.filter { it is java.net.Inet4Address }
            val v6 = all.filter { it !is java.net.Inet4Address }
            return v4 + v6
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)          // was missing — prompts can be large
        .callTimeout(40, TimeUnit.SECONDS)           // hard cap: spinner can't outlive this
        .retryOnConnectionFailure(true)
        .dns(ipv4PreferredDns)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    // ═══════════════════════════════════════════════════════
    // GOOGLE NEWS — fetch recent headlines for a stock
    // ═══════════════════════════════════════════════════════

    fun fetchNewsHeadlines(
        symbol: String,
        maxResults: Int = 8,
        ctx: MarketContext = MarketContext.IN,
        companyName: String? = null
    ): List<String> {
        // Clean the ticker: strip every exchange suffix Yahoo appends (Indian
        // .NS/.BO, plus the European venues used by the DE space — .DE/.PA/.AS/
        // .L/.MI/.MC/.SW/.ST/.HE/.OL) and the NSE -EQ series tag. Without this,
        // a German ticker like "ENR.DE" was searched verbatim and matched no news.
        val cleanSymbol = symbol
            .replace(Regex("\\.(NS|BO|DE|PA|AS|L|MI|MC|SW|ST|HE|OL)$", RegexOption.IGNORE_CASE), "")
            .replace("-EQ", "").replace("_", " ")
            .trim()

        // Prefer the real company name for the news query when we have one — a
        // human name ("Siemens Energy") surfaces far more coverage than a raw
        // ticker ("ENR"), especially for European names. Fall back to the
        // cleaned ticker when no name was passed.
        val searchTerm = companyName?.trim()?.takeIf { it.isNotBlank() } ?: cleanSymbol

        // Region-aware Google News query: append market keyword only when set
        // (IN uses "NSE", US/DE use no suffix because those tickers aren't
        // disambiguated by exchange in news headlines the same way).
        val baseQuery = if (ctx.newsKeyword.isNotEmpty()) "$searchTerm stock ${ctx.newsKeyword}"
                        else "$searchTerm stock"
        // Recency: Google News's `when:30d` operator restricts results to the
        // last 30 days server-side. parseRssHeadlines ALSO filters by pubDate
        // as a hard guarantee in case the operator is ignored on some locales.
        val query = "$baseQuery when:30d"
        val url = "https://news.google.com/rss/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&hl=${ctx.newsHl}&gl=${ctx.newsGl}&ceid=${ctx.newsCeid}"

        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                parseRssHeadlines(body, maxResults)
            }
        } catch (e: Exception) {
            Log.w(TAG, "News fetch failed for $symbol", e)
            emptyList()
        }
    }

    /**
     * Parse RSS <item> titles, now also reading <pubDate> so we can:
     *   - drop stories older than 30 days (Google News is relevance-ranked, so
     *     a stale headline can otherwise rank above recent ones),
     *   - de-duplicate near-identical titles,
     *   - prefix each kept headline with its date so the LLM can weigh recency.
     * Items with an UNPARSEABLE/absent date are KEPT (we can't prove they're
     * stale) — preserves the old behaviour when feeds omit pubDate.
     */
    private fun parseRssHeadlines(xml: String, max: Int): List<String> {
        data class Item(val title: String, val timeMs: Long?)
        val items = mutableListOf<Item>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var inItem = false
            var currentTag = ""
            var title = ""
            var timeMs: Long? = null

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        if (currentTag == "item") { inItem = true; title = ""; timeMs = null }
                    }
                    XmlPullParser.TEXT -> {
                        if (inItem && parser.text.isNotBlank()) {
                            when (currentTag) {
                                "title" -> {
                                    val t = parser.text.trim()
                                    if (t.isNotEmpty() && !t.startsWith("<?")) title = t
                                }
                                "pubDate" -> timeMs = parsePubDate(parser.text.trim())
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "item") {
                            if (title.isNotEmpty()) items.add(Item(title, timeMs))
                            inItem = false
                        }
                        currentTag = ""
                    }
                }
                parser.next()
            }
        } catch (e: Exception) {
            Log.w(TAG, "RSS parse error", e)
        }

        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        val dateFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (it in items) {
            if (it.timeMs != null && it.timeMs < cutoff) continue          // older than 30d
            if (!seen.add(it.title.lowercase(java.util.Locale.US))) continue // exact-title dup
            out.add(
                if (it.timeMs != null) "[${dateFmt.format(java.util.Date(it.timeMs))}] ${it.title}"
                else it.title
            )
            if (out.size >= max) break
        }
        return out
    }

    /** RFC-1123 pubDate → epoch ms; null if unparseable (caller keeps the item). */
    private fun parsePubDate(raw: String): Long? {
        val patterns = arrayOf("EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, dd MMM yyyy HH:mm:ss Z")
        for (p in patterns) {
            try {
                return java.text.SimpleDateFormat(p, java.util.Locale.US).parse(raw)?.time
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }

    // ═══════════════════════════════════════════════════════
    // LLM CALL — supports OpenAI and Anthropic
    // ═══════════════════════════════════════════════════════

    sealed class AiResult {
        data class Success(val text: String) : AiResult()
        data class Error(val message: String) : AiResult()
    }

    private fun callLlm(config: ConfigManager, systemPrompt: String, userPrompt: String, maxTokens: Int = 500): AiResult {
        val apiKey = config.openaiApiKey
        if (apiKey.isBlank()) return AiResult.Error("No API key configured. Go to Settings → AI Stock Analysis.")

        return try {
            if (config.llmProvider == "anthropic") {
                callAnthropic(apiKey, config.llmModel, systemPrompt, userPrompt, maxTokens)
            } else {
                callOpenAi(apiKey, config.llmModel, systemPrompt, userPrompt, maxTokens)
            }
        } catch (e: Exception) {
            Log.e(TAG, "LLM call failed", e)
            AiResult.Error("AI call failed: ${e.message}")
        }
    }

    private fun callOpenAi(apiKey: String, model: String, system: String, user: String, maxTokens: Int = 500): AiResult {
        val json = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("temperature", 0.3)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", user))
            })
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: return AiResult.Error("Empty response")
            if (!response.isSuccessful) {
                val errMsg = try { JSONObject(body).getJSONObject("error").getString("message") } catch (_: Exception) { body.take(200) }
                return AiResult.Error("OpenAI error: $errMsg")
            }
            val content = JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            return AiResult.Success(content.trim())
        }
    }

    private fun callAnthropic(apiKey: String, model: String, system: String, user: String, maxTokens: Int = 500): AiResult {
        val json = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("temperature", 0.3)
            put("system", system)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "user").put("content", user))
            })
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: return AiResult.Error("Empty response")
            if (!response.isSuccessful) {
                val errMsg = try { JSONObject(body).getJSONObject("error").getString("message") } catch (_: Exception) { body.take(200) }
                return AiResult.Error("Anthropic error: $errMsg")
            }
            val content = JSONObject(body)
                .getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
            return AiResult.Success(content.trim())
        }
    }

    // ═══════════════════════════════════════════════════════
    // FEATURE 1: DEEP PULLBACK ANALYSIS
    // Auto-triggered when ema21PctDiff < -3%
    // ═══════════════════════════════════════════════════════

    fun analyzePullback(
        config: ConfigManager,
        symbol: String,
        price: Double,
        ema21PctDiff: Double,
        macdPhase: String,
        profitScore: Int,
        protectScore: Int,
        rsi: Double? = null,
        adx: Double? = null,
        support: Double? = null,
        resistance: Double? = null,
        ctx: MarketContext = MarketContext.IN,
        name: String? = null
    ): AiResult {
        val headlines = fetchNewsHeadlines(symbol, 12, ctx, name)  // increased from 8 to 12 for richer context
        val displayName = name?.trim()?.takeIf { it.isNotBlank() }
        if (headlines.isEmpty()) {
            return AiResult.Error("No recent news found for ${displayName ?: symbol}")
        }

        val system = """You are a stock market analyst. Analyze a deep pullback critically and independently.
Form your own opinion based PRIMARILY on recent news (headlines appear in chronological order, most recent first).
Do NOT assume the pullback is automatically a buying opportunity — critically assess both risks and opportunities.

Evaluate whether this pullback is:
• TEMPORARY: A healthy correction in an uptrend; news suggests a one-off event or overreaction
• STRUCTURAL: Signs of fundamental weakness; news suggests deteriorating business/sector conditions
• UNCERTAIN: Conflicting signals; need more confirmation before committing

Prioritize recent news over older stories. Be concise (3-4 sentences max). If you're unsure, answer UNCERTAIN rather than guessing."""

        val trendDesc = if (adx != null) {
            "ADX = ${String.format("%.1f", adx)} (${if (adx > 25) "trending" else if (adx > 15) "emerging trend" else "choppy"})"
        } else "Trend strength not available"

        val rsiDesc = if (rsi != null) {
            val status = when {
                rsi < 30 -> "oversold — may bounce soon"
                rsi < 50 -> "mid-range — pullback zone ideal"
                rsi < 70 -> "near exhaustion — caution on further bounce"
                else -> "overbought — unusual for a pullback"
            }
            "RSI = ${String.format("%.0f", rsi)} ($status)"
        } else "RSI not available"

        val srDesc = if (support != null && resistance != null) {
            val priceVsSupport = ((price - support) / support * 100)
            "Price is ${String.format("%.1f%%", priceVsSupport)} above support (${ctx.currencySymbol}${String.format("%.2f", support)})"
        } else "Support/Resistance not available"

        val user = buildString {
            append("Stock: ${if (displayName != null) "$displayName ($symbol)" else symbol} (${ctx.marketLabel})\n")
            append("Current price: ${ctx.currencySymbol}${String.format("%.2f", price)}\n")
            append("EMA(21) distance: ${String.format("%.1f", ema21PctDiff)}% (deep pullback trigger)\n")
            append("MACD Phase: $macdPhase\n\n")

            append("─ Technical Context ─\n")
            append("$rsiDesc\n")
            append("$trendDesc\n")
            append("$srDesc\n\n")

            append("─ Recent News Headlines (last 30 days) ─\n")
            headlines.forEachIndexed { i, h -> append("[${i + 1}] $h\n") }

            append("\nAnalyze critically:\n")
            append("• Based on the news, what is the PRIMARY driver of this pullback?\n")
            append("• Is this a temporary market overreaction or structural weakness?\n")
            append("• What would need to happen for this to be a BUY vs AVOID?\n")
            append("• If uncertain, say UNCERTAIN rather than guessing.\n")
            append("\nProvide your verdict: TEMPORARY, STRUCTURAL, or UNCERTAIN.")
        }

        return callLlm(config, system, user)
    }

    // ═══════════════════════════════════════════════════════
    // FEATURE 2: STOCK OUTLOOK (manually triggered)
    // User taps "Analyze Outlook" button in detail modal
    // ═══════════════════════════════════════════════════════

    fun analyzeOutlook(
        config: ConfigManager,
        symbol: String,
        price: Double,
        cherryPoints: Int,
        profitScore: Int,
        protectScore: Int,
        sellIntent: String,
        macdPhase: String,
        macdSlope: Double,
        rsi: Double?,
        sma200: Double?,
        ema21PctDiff: Double,
        rrRatio: Double,
        priceVel: Double,
        isInPortfolio: Boolean = false,
        ctx: MarketContext = MarketContext.IN,
        name: String? = null
    ): AiResult {
        val displayName = name?.trim()?.takeIf { it.isNotBlank() }
        val headlines = fetchNewsHeadlines(symbol, 12, ctx, name)

        // Allowed verdicts: HOLD is ONLY valid if the stock is currently in the user's portfolio.
        // Otherwise the LLM must commit to a directional call (BUY / WATCH / AVOID / SELL).
        val allowedVerdicts = if (isInPortfolio)
            "STRONG BUY / BUY / HOLD / REDUCE / SELL"
        else
            "STRONG BUY / BUY / WATCH / AVOID"

        val holdRule = if (isInPortfolio)
            "HOLD is allowed because the user currently owns this stock."
        else
            "HOLD is NOT a valid verdict — the user does NOT own this stock, so you must commit to a directional call (BUY / WATCH / AVOID). Do not output HOLD under any circumstances."

        val system = """You are a stock market analyst providing outlook summaries for ${ctx.analystPersona}.
Form your own independent opinion based PRIMARILY on recent news and publicly known fundamentals.
Headlines are listed in chronological order (most recent first) — prioritize those heavily over older news.
Do NOT assume any prior bullish or bearish bias — you are given current price, ownership status, and headlines only. Critically assess both risks and opportunities.
Do not infer or mention app technical indicators unless they are explicitly present in the news headlines.

Give a structured response:
1. SHORT TERM (1-4 weeks): News-driven outlook
2. LONG TERM (3-12 months): Fundamental narrative from recent news and publicly known context
3. KEY RISKS: 1-2 bullet points from recent developments
4. VERDICT: One of: $allowedVerdicts

$holdRule

Keep each section to 2-3 sentences max. Be specific about price levels when possible."""

        val user = buildString {
            append("Stock: ${if (displayName != null) "$displayName ($symbol)" else symbol} (${ctx.marketLabel})\n")
            append("Current Price: ${ctx.currencySymbol}${String.format("%.2f", price)}\n")
            append("User currently owns this: ${if (isInPortfolio) "YES" else "NO"}\n\n")
            // Technical context intentionally withheld here to keep the LLM
            // outlook independent from app-generated SMA/MACD bias.
            // append("── Minimal Technical Context ──\n")
            // append("Trend: ...\n")
            // append("MACD Phase: ...\n\n")
            if (headlines.isNotEmpty()) {
                append("── Recent News Headlines (prioritize items from the last 30 days) ──\n")
                headlines.forEachIndexed { i, h -> append("[${i + 1}] $h\n") }
            } else {
                append("(No recent news available via Google News RSS — rely on general market knowledge of this company, and note the absence of news in your response)\n")
            }
            append("\nBased on the above, provide the structured outlook. ")
            append("Cite headline numbers inline where relevant. ")
            if (!isInPortfolio) {
                append("Remember: HOLD is NOT a valid verdict here. ")
            }
            append("Do not reference any app-generated scoring.")
        }
        // NOTE: cherryPoints / profitScore / protectScore / sellIntent / rrRatio / rsi / ema21PctDiff /
        // priceVel / SMA200 / MACD parameters are deliberately NOT passed to the LLM — per the
        // LLM-outlook rework: keep context minimal (price + ownership + news), drop technicals.
        // The params remain in the signature for backward compat with the JS bridge in MainActivity.

        return callLlm(config, system, user)
    }

    // ═══════════════════════════════════════════════════════
    // FEATURE 3: FOLLOW-UP QUESTIONS ABOUT COMPANY
    // User asks additional questions with headline context
    // ═══════════════════════════════════════════════════════

    fun askFollowUpQuestion(
        config: ConfigManager,
        symbol: String,
        userQuestion: String,
        price: Double,
        previousAnalysis: String = "",
        ctx: MarketContext = MarketContext.IN,
        name: String? = null
    ): AiResult {
        val displayName = name?.trim()?.takeIf { it.isNotBlank() }
        // Fetch broader set of headlines for deeper company context
        val headlines = fetchNewsHeadlines(symbol, maxResults = 20, ctx = ctx, companyName = name)

        if (headlines.isEmpty()) {
            return AiResult.Error("No recent news found. Unable to answer questions about ${displayName ?: symbol} fundamentals.")
        }

        val system = """You are a stock market analyst with deep company knowledge.
Use the provided news headlines to answer specific questions about a company's fundamentals,
competitive position, sector trends, revenue/earnings outlook, and business dynamics.

Headlines are listed in chronological order (most recent first) — prioritize recent news heavily.
If a question cannot be answered from the available news, say so explicitly.
Keep answers concise (2-3 sentences) unless asked for more detail."""

        val user = buildString {
            append("Stock: ${if (displayName != null) "$displayName ($symbol)" else symbol} (${ctx.marketLabel})\n")
            append("Current Price: ${ctx.currencySymbol}${String.format("%.2f", price)}\n\n")

            if (previousAnalysis.isNotEmpty()) {
                append("─ Previous Pullback Analysis ─\n")
                append(previousAnalysis + "\n\n")
            }

            append("─ Company News Headlines (last 30 days) ─\n")
            headlines.forEachIndexed { i, h -> append("[${i + 1}] $h\n") }

            append("\n─ Your Question ─\n")
            append(userQuestion)
            append("\n\nAnswer based ONLY on the news provided above. Cite headline numbers inline.")
        }

        return callLlm(config, system, user)
    }

    // ── Deep Pullback Batch Analyser ────────────────────────────────────────────
    // Called from MainActivity.analyseDeepPullbacks() via the JS bridge.
    // stocksJson: JSON array of objects with fields:
    //   { symbol, name, price, rsi, macdPhase, macdHist, ema21PctDiff, atr,
    //     support, resistance, buyScore, buySignal, sma200, adx, minBarsFilter }
    // Returns a formatted markdown table with entry quality scores.
    fun analyseDeepPullbackBatch(
        config: ConfigManager,
        stocksJson: String,
        ctx: MarketContext = MarketContext.IN
    ): AiResult {
        val arr = try {
            org.json.JSONArray(stocksJson)
        } catch (e: Exception) {
            return AiResult.Error("Invalid JSON payload: ${e.message}")
        }

        if (arr.length() == 0) return AiResult.Error("No stocks to analyse")

        val system = """You are an expert ${ctx.traderRegion} stock market trader specialising in pullback entries on momentum stocks.
You will be given a batch of ${ctx.marketLabel} stocks that have passed a 'deep pullback' technical filter.
Your job is to score each stock's current entry quality from 1 to 10 and give a brief rationale.

Scoring guide:
  9-10 → STRONG entry: multiple confluence signals, risk/reward clearly favourable
  6-8  → MODERATE entry: good setup but one or two concerns (RSI still falling, MACD not yet hooking, etc.)
  3-5  → WEAK entry: premature — pullback may not be complete, or momentum fading fast
  1-2  → AVOID: broken structure, RSI deeply oversold without stabilisation, or extreme MACD deterioration

Key factors to weigh (in priority order):
1. RSI: <35 = still falling risk; 35-48 = pullback zone ideal; near 48 = nearing exhaustion of pullback
2. MACD Phase + Histogram: 'EARLY BUY' or 'BUY FLIP' phase with hist turning less-negative = confirming;
   still deeply negative hist = caution
3. EMA21 gap: between −2% and −6% is sweet spot; beyond −8% = extended, wait for stabilisation
4. Support proximity: if price is near support, risk is defined — reward ema21PctDiff tells you upside to EMA mean-reversion
5. ADX: <20 = weak trend, pullback in choppy market; 20-40 = healthy; >40 = strong trend pullback (best)
6. minBarsFilter (Speed): lower = faster stock, pullbacks resolve quicker; higher = slower, need more patience
7. buyScore: app-generated composite score — treat as secondary signal, not primary

Output a markdown table with these EXACT columns (no extra text before or after):
| Symbol | Score | Verdict | Rationale |

Verdict must be exactly one of: STRONG ENTRY / MODERATE ENTRY / WEAK ENTRY / AVOID

After the table, add a short paragraph (2-3 sentences) summarising the batch — which themes you see across the pullbacks, and whether this is a broad market pullback or stock-specific.

Be direct and opinionated. Do NOT hedge excessively."""

        val user = buildString {
            append("Batch Deep Pullback Analysis — ${arr.length()} stocks\n\n")
            append("Format each row with real analysis. Do not just echo the numbers.\n\n")
            append("| Symbol | Price | RSI | MACD Phase | MACD Hist | EMA21 Gap | Support | Resistance | ATR | ADX | Speed(bars) | BuyScore | BuySignal |\n")
            append("|--------|-------|-----|------------|-----------|-----------|---------|------------|-----|-----|-------------|----------|-----------|\n")

            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                fun d(key: String, decimals: Int = 2): String {
                    val v = s.optDouble(key, Double.NaN)
                    return if (v.isNaN()) "—" else String.format("%.${decimals}f", v)
                }
                append("| ${s.optString("symbol", "?")} ")
                append("| ${ctx.currencySymbol}${d("price")} ")
                append("| ${d("rsi", 1)} ")
                append("| ${s.optString("macdPhase", "?")} ")
                append("| ${d("macdHist")} ")
                append("| ${d("ema21PctDiff", 1)}% ")
                append("| ${ctx.currencySymbol}${d("support")} ")
                append("| ${ctx.currencySymbol}${d("resistance")} ")
                append("| ${d("atr")} ")
                append("| ${d("adx", 1)} ")
                append("| ${s.optInt("minBarsFilter", 5)} ")  // 5 = sensible default if AST didn't provide one
                append("| ${d("buyScore", 1)} ")
                append("| ${s.optString("buySignal", "?")} |\n")
            }

            append("\nAnalyse each stock using the scoring guide and output the results table followed by the batch summary paragraph.")
        }

        return callLlm(config, system, user)
    }

    // ═══════════════════════════════════════════════════════
    // FEATURE 4: PORTFOLIO NEWS SCAN (criticality triage)
    // Batched, manually triggered. Fetches recent Google-News headlines for
    // every current-space holding, then ONE LLM call classifies each into a
    // 5-level verdict with a one-line conclusion + a portfolio summary.
    // Returns strict JSON for the dashboard panel to render. No orders, no
    // scoring — purely "what's in the news for my stocks right now".
    //   stocksJson: [{ "symbol": "...", "name": "..." }]
    // ═══════════════════════════════════════════════════════
    fun analysePortfolioNews(
        config: ConfigManager,
        stocksJson: String,
        ctx: MarketContext = MarketContext.IN
    ): AiResult {
        val arr = try { JSONArray(stocksJson) } catch (e: Exception) {
            return AiResult.Error("Invalid JSON payload: ${e.message}")
        }
        if (arr.length() == 0) return AiResult.Error("No holdings to scan")

        data class Item(val symbol: String, val name: String, val headlines: List<String>)
        val items = mutableListOf<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val sym = o.optString("symbol").trim()
            if (sym.isEmpty()) continue
            val nm = o.optString("name").trim().ifBlank { sym }
            val hls = try { fetchNewsHeadlines(sym, 6, ctx, nm) } catch (e: Exception) { emptyList() }
            items.add(Item(sym, nm, hls))
            // Be gentle on Google News RSS — small gap between holdings.
            if (i < arr.length() - 1) try { Thread.sleep(150) } catch (_: InterruptedException) {}
        }
        if (items.isEmpty()) return AiResult.Error("No valid holdings to scan")

        val system = """You are an equity news analyst scanning a ${ctx.analystPersona} portfolio.
For EACH stock, read its recent headlines (most recent first) and assign exactly one verdict:
  CRITICAL      — serious negative: fraud, regulatory action, guidance cut, big earnings miss, lawsuit, crash, default
  CAUTION       — mild or uncertain negative, or genuinely mixed signals worth watching
  QUIET         — no material news / routine coverage only
  POSITIVE      — clearly good developments
  VERY_POSITIVE — major upside: big beat, cluster of upgrades, large contract/order, buyout or strategic catalyst
Judge ONLY from the headlines provided — do not invent facts. If a stock has no headlines, verdict = QUIET and conclusion = "No recent news.".
Be decisive; reserve CRITICAL and VERY_POSITIVE for genuinely strong signals.

Output ONLY a JSON object (no markdown, no code fences), exactly this shape:
{"summary":"<=20 word portfolio-level takeaway","stocks":[{"symbol":"AAA","verdict":"CRITICAL|CAUTION|QUIET|POSITIVE|VERY_POSITIVE","conclusion":"<=22 word plain-English summary of what's happening"}]}"""

        val user = buildString {
            append("Portfolio — ${items.size} holdings (${ctx.marketLabel}). Classify every one.\n\n")
            items.forEach { it2 ->
                append("### ${it2.name} (${it2.symbol})\n")
                if (it2.headlines.isEmpty()) append("(no recent news)\n")
                else it2.headlines.forEachIndexed { idx, h -> append("[${idx + 1}] $h\n") }
                append("\n")
            }
            append("Return the JSON object now.")
        }

        // One row per holding — scale the budget with portfolio size.
        val maxTokens = (300 + items.size * 60).coerceAtMost(2000)
        return when (val res = callLlm(config, system, user, maxTokens)) {
            // Validate + reconcile the model's JSON against the holdings we
            // actually sent: coerce verdicts to the allowlist, drop fabricated
            // symbols, and fill any the model omitted. Guarantees the panel
            // reflects the real portfolio regardless of what the LLM returns.
            is AiResult.Success -> AiResult.Success(
                buildValidatedNewsJson(res.text, items.map { it.symbol to it.headlines.isNotEmpty() })
            )
            is AiResult.Error -> res
        }
    }

    private val NEWS_VERDICTS = setOf("CRITICAL", "CAUTION", "QUIET", "POSITIVE", "VERY_POSITIVE")

    /** Coerce a model-supplied verdict to the allowlist; anything else → QUIET. */
    private fun normalizeVerdict(raw: String?): String {
        val v = raw?.trim()?.uppercase(java.util.Locale.US)?.replace(' ', '_').orEmpty()
        return if (v in NEWS_VERDICTS) v else "QUIET"
    }

    /**
     * Rebuild the news JSON from the model output but anchored on the EXACT set
     * of holdings we requested (in order). Unknown/fabricated symbols are
     * dropped; missing ones are added as QUIET. Verdicts are allowlisted and
     * strings length-capped. On unparseable output, returns an error object the
     * dashboard already knows how to show.
     *   requested: list of (symbol, hadHeadlines)
     */
    private fun buildValidatedNewsJson(rawText: String, requested: List<Pair<String, Boolean>>): String {
        val obj = try { JSONObject(extractJsonObject(rawText)) } catch (e: Exception) { null }
            ?: return JSONObject().put("error", "Could not parse news analysis").toString()

        val summary = obj.optString("summary", "").trim().take(200)
        val bySym = HashMap<String, Pair<String, String>>()
        obj.optJSONArray("stocks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sym = o.optString("symbol").trim()
                if (sym.isEmpty()) continue
                bySym[sym.uppercase(java.util.Locale.US)] =
                    normalizeVerdict(o.optString("verdict")) to o.optString("conclusion").trim().take(220)
            }
        }

        val outStocks = JSONArray()
        for ((sym, hadNews) in requested) {
            val match = bySym[sym.uppercase(java.util.Locale.US)]
            val conclusion = match?.second?.takeIf { it.isNotBlank() }
                ?: if (hadNews) "No clear assessment from recent headlines." else "No recent news."
            outStocks.put(JSONObject().apply {
                put("symbol", sym)
                put("verdict", match?.first ?: "QUIET")
                put("conclusion", conclusion)
            })
        }
        return JSONObject().apply {
            put("summary", summary)
            put("stocks", outStocks)
        }.toString()
    }

    /** Trim anything around the JSON object so the dashboard can JSON.parse it
     *  even if the model wrapped the reply in prose or ```json fences. */
    private fun extractJsonObject(raw: String): String {
        val s = raw.indexOf('{'); val e = raw.lastIndexOf('}')
        return if (s >= 0 && e > s) raw.substring(s, e + 1) else raw
    }
}

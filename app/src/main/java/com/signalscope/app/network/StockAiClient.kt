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
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * AI-powered stock analysis client.
 *
 * Two features:
 * 1. Deep Pullback Analysis — auto-triggered when price drops >3% below EMA(21)
 *    Fetches Google News headlines → sends to LLM → returns probable cause
 *
 * 2. Stock Outlook — from the detail modal, and asked by the floor algo
 *    Fetches news (no technicals) → LLM returns a JSON outlook (see OutlookReply)
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

    /** Headlines as prompt lines, newest first: "2026-10-15 (1 day ago) · title — outlet (+2 more outlets)". */
    fun fetchNewsHeadlines(
        symbol: String,
        maxResults: Int = 8,
        ctx: MarketContext = MarketContext.IN,
        companyName: String? = null
    ): List<String> {
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        return fetchStories(symbol, maxResults, ctx, companyName).map { NewsDigest.line(it, today, zone) }
    }

    /** "Today is Friday, 16 October 2026." — headline dates mean little to a model that doesn't know it. */
    private fun todayLine(): String =
        "Today is ${LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.US))}."

    fun fetchStories(
        symbol: String,
        maxResults: Int = 8,
        ctx: MarketContext = MarketContext.IN,
        companyName: String? = null
    ): List<NewsDigest.Story> {
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
        // last 30 days server-side. NewsDigest ALSO filters by pubDate as a
        // hard guarantee in case the operator is ignored on some locales.
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
                // Google's order is relevance, not date — NewsDigest sorts, merges
                // the same story from many outlets, and drops quote pages.
                NewsDigest.digest(parseRssItems(body), maxResults, System.currentTimeMillis(),
                    listOf(searchTerm, cleanSymbol))
            }
        } catch (e: Exception) {
            Log.w(TAG, "News fetch failed for $symbol", e)
            emptyList()
        }
    }

    /**
     * Every RSS <item>, in the feed's order: its title, outlet (<source>), date
     * and article link. Items with an unparseable or absent date are kept
     * undated — we can't prove they're stale.
     */
    private fun parseRssItems(xml: String): List<NewsDigest.Item> {
        val items = mutableListOf<NewsDigest.Item>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var inItem = false
            var currentTag = ""
            var title = ""
            var source = ""
            var link = ""
            var timeMs: Long? = null

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        if (currentTag == "item") { inItem = true; title = ""; source = ""; link = ""; timeMs = null }
                    }
                    XmlPullParser.TEXT -> {
                        if (inItem && parser.text.isNotBlank()) {
                            val t = parser.text.trim()
                            when (currentTag) {
                                "title" -> if (!t.startsWith("<?")) title = t
                                "source" -> source = t
                                "link" -> link = t
                                "pubDate" -> timeMs = parsePubDate(t)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "item") {
                            if (title.isNotEmpty()) items.add(NewsDigest.Item(title, source, timeMs, link))
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
        return items
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

    /** [json]: the prompt asks for a JSON object — OpenAI is also held to it by its JSON mode. */
    private fun callLlm(config: ConfigManager, systemPrompt: String, userPrompt: String, maxTokens: Int = 500,
                        json: Boolean = false): AiResult {
        val apiKey = config.openaiApiKey
        if (apiKey.isBlank()) return AiResult.Error("No API key configured. Go to Settings → AI Stock Analysis.")

        return try {
            if (config.llmProvider == "anthropic") {
                callAnthropic(apiKey, config.llmModel, systemPrompt, userPrompt, maxTokens)
            } else {
                callOpenAi(apiKey, config.llmModel, systemPrompt, userPrompt, maxTokens, json)
            }
        } catch (e: Exception) {
            Log.e(TAG, "LLM call failed", e)
            AiResult.Error("AI call failed: ${e.message}")
        }
    }

    private fun callOpenAi(apiKey: String, model: String, system: String, user: String, maxTokens: Int = 500,
                           jsonReply: Boolean = false): AiResult {
        val json = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("temperature", 0.3)
            if (jsonReply) put("response_format", JSONObject().put("type", "json_object"))
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

Prioritize recent news over older stories. A headline marked "(+N more outlets)" is ONE story that several outlets covered.
Be concise (3-4 sentences max). If you rely on a headline, name its outlet and date in words — never bracketed reference numbers.
If you're unsure, answer UNCERTAIN rather than guessing."""

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

            // Bullets, not numbers: the headlines are never shown, so a "[3]" in
            // the answer would point at nothing the user can see.
            append("─ Recent News Headlines (last 30 days, newest first) ─\n")
            append("${todayLine()}\n")
            headlines.forEach { h -> append("• $h\n") }

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
        val stories = fetchStories(symbol, 12, ctx, name)
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()

        // Two calls up, one neutral, two down — whether or not the user owns it.
        // (It was 2-1-1 for a stock not owned: an unsure model had only one
        // place to land on the bearish side.)
        val allowed = OutlookReply.verdicts(isInPortfolio)
        val guide = if (isInPortfolio) """
  STRONG BUY — clearly good news that should keep lifting the price: worth adding
  BUY — good news on balance
  HOLD — mixed, thin or no clear news: keep it
  REDUCE — bad news on balance: trim
  SELL — clearly bad news (fraud, guidance cut, big miss, regulatory action, default): get out"""
        else """
  STRONG BUY — clearly good news that should keep lifting the price
  BUY — good news on balance
  WATCH — mixed, thin or no clear news
  AVOID — bad news on balance
  STRONG AVOID — clearly bad news (fraud, guidance cut, big miss, regulatory action, default)"""
        val middle = if (isInPortfolio) "HOLD" else "WATCH"

        val system = """You are a stock market analyst writing a short, news-based outlook on ${ctx.analystPersona}.
You are given only the company, today's price, whether the user owns it, and recent headlines. Form your own view; assume no bullish or bearish lean.

Reading the headlines:
- Each headline carries its date and age, and they are sorted newest first. Weigh the newest most; treat news more than two weeks old as background.
- A headline marked "(+N more outlets)" is ONE story that several outlets covered. Wide coverage means it mattered — not that it happened several times.
- Many headlines are commentary or tips ("Is now the time to buy…", "analyst says…"). Treat those as opinion and weigh real events — results, guidance, orders, deals, regulation, management changes — above them.
- Your own background knowledge of the company may be out of date. If you lean on it, say so.
- Do NOT state support, resistance, target or any other price levels: you know only today's price.

Verdict — exactly one of: ${allowed.joinToString(" / ")}$guide
Confidence — LOW, MEDIUM or HIGH: how firmly the news supports the verdict. Few, old or conflicting headlines mean LOW.
News — NEW (real developments in the last week or so), REPEAT (mostly older stories or commentary), or NONE (nothing relevant).

Reply with ONLY a JSON object — no markdown, no code fences — in exactly this shape:
{"short_term":"1-4 weeks: 2-3 sentences","long_term":"3-12 months: 1-2 sentences — headlines are thin evidence for this, so keep it tentative","risks":["one risk","another risk"],"news":"NEW|REPEAT|NONE","confidence":"LOW|MEDIUM|HIGH","verdict":"one of the verdicts above"}
When a sentence rests on a headline, put that headline's number in square brackets right after it, e.g. [2]. Use only the numbers listed."""

        val user = buildString {
            append("Stock: ${if (displayName != null) "$displayName ($symbol)" else symbol} — ${ctx.marketLabel}\n")
            append("${todayLine()}\n")
            append("Current price: ${ctx.currencySymbol}${String.format("%.2f", price)}\n")
            append("User owns it: ${if (isInPortfolio) "YES" else "NO"}\n\n")
            // Technical context intentionally withheld here to keep the LLM
            // outlook independent from app-generated SMA/MACD bias.
            if (stories.isNotEmpty()) {
                append("Headlines from the last 30 days, newest first:\n")
                stories.forEachIndexed { i, s -> append("[${i + 1}] ${NewsDigest.line(s, today, zone)}\n") }
            } else {
                append("No relevant headlines in the last 30 days. Say so. Without news, lean to $middle unless you know of " +
                    "something specific — and then say your knowledge may be out of date. Confidence must be LOW.\n")
            }
            append("\nReturn the JSON object now. Do not reference any app-generated scoring.")
        }
        // NOTE: cherryPoints / profitScore / protectScore / sellIntent / rrRatio / rsi / ema21PctDiff /
        // priceVel / SMA200 / MACD parameters are deliberately NOT passed to the LLM — per the
        // LLM-outlook rework: keep context minimal (price + ownership + news), drop technicals.
        // The params remain in the signature for backward compat with the JS bridge in MainActivity.

        // The answer goes back as checked JSON (OutlookReply): the panel shows its
        // sections and numbered, tappable sources; the floor algo reads its verdict.
        val sources = stories.map { s ->
            val d = NewsDigest.dateOf(s, zone)
            OutlookReply.Source(d?.toString() ?: "", d?.let { NewsDigest.age(it, today) } ?: "",
                s.title, s.source, s.link, s.outlets)
        }
        return when (val r = callLlm(config, system, user, maxTokens = 700, json = true)) {
            is AiResult.Success -> AiResult.Success(OutlookReply.build(r.text, symbol, isInPortfolio, sources, today.toString()))
            is AiResult.Error -> r
        }
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
A headline marked "(+N more outlets)" is ONE story that several outlets covered.
If a question cannot be answered from the available news, say so explicitly.
When you rely on a headline, name its outlet and date in words — never bracketed reference numbers.
Keep answers concise (2-3 sentences) unless asked for more detail."""

        val user = buildString {
            append("Stock: ${if (displayName != null) "$displayName ($symbol)" else symbol} (${ctx.marketLabel})\n")
            append("${todayLine()}\n")
            append("Current Price: ${ctx.currencySymbol}${String.format("%.2f", price)}\n\n")

            // From the pullback card or the outlook card — whichever the question was asked under.
            if (previousAnalysis.isNotEmpty()) {
                append("─ Earlier analysis of this stock ─\n")
                append(previousAnalysis + "\n\n")
            }

            append("─ Company News Headlines (last 30 days, newest first) ─\n")
            headlines.forEach { h -> append("• $h\n") }

            append("\n─ Your Question ─\n")
            append(userQuestion)
            append("\n\nAnswer based ONLY on the news provided above.")
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
            append("Portfolio — ${items.size} holdings (${ctx.marketLabel}). Classify every one.\n")
            append("${todayLine()} A headline marked \"(+N more outlets)\" is one story several outlets covered.\n\n")
            items.forEach { it2 ->
                append("### ${it2.name} (${it2.symbol})\n")
                if (it2.headlines.isEmpty()) append("(no recent news)\n")
                else it2.headlines.forEach { h -> append("• $h\n") }
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

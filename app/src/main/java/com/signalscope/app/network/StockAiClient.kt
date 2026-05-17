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

    fun fetchNewsHeadlines(symbol: String, maxResults: Int = 8): List<String> {
        // Clean symbol: remove .NS, .BO suffixes and -EQ
        val cleanSymbol = symbol
            .replace(".NS", "").replace(".BO", "")
            .replace("-EQ", "").replace("_", " ")

        val query = "$cleanSymbol stock NSE"
        val url = "https://news.google.com/rss/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&hl=en-IN&gl=IN&ceid=IN:en"

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

    private fun parseRssHeadlines(xml: String, max: Int): List<String> {
        val headlines = mutableListOf<String>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var inItem = false
            var currentTag = ""

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        if (currentTag == "item") inItem = true
                    }
                    XmlPullParser.TEXT -> {
                        if (inItem && currentTag == "title" && parser.text.isNotBlank()) {
                            val title = parser.text.trim()
                            if (title.isNotEmpty() && !title.startsWith("<?")) {
                                headlines.add(title)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "item") {
                            inItem = false
                            if (headlines.size >= max) return headlines
                        }
                        currentTag = ""
                    }
                }
                parser.next()
            }
        } catch (e: Exception) {
            Log.w(TAG, "RSS parse error", e)
        }
        return headlines
    }

    // ═══════════════════════════════════════════════════════
    // LLM CALL — supports OpenAI and Anthropic
    // ═══════════════════════════════════════════════════════

    sealed class AiResult {
        data class Success(val text: String) : AiResult()
        data class Error(val message: String) : AiResult()
    }

    private fun callLlm(config: ConfigManager, systemPrompt: String, userPrompt: String): AiResult {
        val apiKey = config.openaiApiKey
        if (apiKey.isBlank()) return AiResult.Error("No API key configured. Go to Settings → AI Stock Analysis.")

        return try {
            if (config.llmProvider == "anthropic") {
                callAnthropic(apiKey, config.llmModel, systemPrompt, userPrompt)
            } else {
                callOpenAi(apiKey, config.llmModel, systemPrompt, userPrompt)
            }
        } catch (e: Exception) {
            Log.e(TAG, "LLM call failed", e)
            AiResult.Error("AI call failed: ${e.message}")
        }
    }

    private fun callOpenAi(apiKey: String, model: String, system: String, user: String): AiResult {
        val json = JSONObject().apply {
            put("model", model)
            put("max_tokens", 500)
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

    private fun callAnthropic(apiKey: String, model: String, system: String, user: String): AiResult {
        val json = JSONObject().apply {
            put("model", model)
            put("max_tokens", 500)
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
        resistance: Double? = null
    ): AiResult {
        val headlines = fetchNewsHeadlines(symbol, 12)  // increased from 8 to 12 for richer context
        if (headlines.isEmpty()) {
            return AiResult.Error("No recent news found for $symbol")
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
            "Price is ${String.format("%.1f%%", priceVsSupport)} above support (₹${String.format("%.2f", support)})"
        } else "Support/Resistance not available"

        val user = buildString {
            append("Stock: $symbol (NSE India)\n")
            append("Current price: ₹${String.format("%.2f", price)}\n")
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
        buyScore: Int,
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
        isInPortfolio: Boolean = false
    ): AiResult {
        val headlines = fetchNewsHeadlines(symbol, 12)

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

        val system = """You are a stock market analyst providing outlook summaries for Indian NSE stocks.
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
            append("Stock: $symbol (NSE India)\n")
            append("Current Price: ₹${String.format("%.2f", price)}\n")
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
        // NOTE: buyScore / profitScore / protectScore / sellIntent / rrRatio / rsi / ema21PctDiff /
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
        previousAnalysis: String = ""
    ): AiResult {
        // Fetch broader set of headlines for deeper company context
        val headlines = fetchNewsHeadlines(symbol, maxResults = 20)

        if (headlines.isEmpty()) {
            return AiResult.Error("No recent news found. Unable to answer questions about $symbol fundamentals.")
        }

        val system = """You are a stock market analyst with deep company knowledge.
Use the provided news headlines to answer specific questions about a company's fundamentals,
competitive position, sector trends, revenue/earnings outlook, and business dynamics.

Headlines are listed in chronological order (most recent first) — prioritize recent news heavily.
If a question cannot be answered from the available news, say so explicitly.
Keep answers concise (2-3 sentences) unless asked for more detail."""

        val user = buildString {
            append("Stock: $symbol (NSE India)\n")
            append("Current Price: ₹${String.format("%.2f", price)}\n\n")

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
    fun analyseDeepPullbackBatch(config: ConfigManager, stocksJson: String): AiResult {
        val arr = try {
            org.json.JSONArray(stocksJson)
        } catch (e: Exception) {
            return AiResult.Error("Invalid JSON payload: ${e.message}")
        }

        if (arr.length() == 0) return AiResult.Error("No stocks to analyse")

        val system = """You are an expert Indian stock market trader specialising in pullback entries on momentum stocks.
You will be given a batch of NSE stocks that have passed a 'deep pullback' technical filter.
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
                append("| ₹${d("price")} ")
                append("| ${d("rsi", 1)} ")
                append("| ${s.optString("macdPhase", "?")} ")
                append("| ${d("macdHist")} ")
                append("| ${d("ema21PctDiff", 1)}% ")
                append("| ₹${d("support")} ")
                append("| ₹${d("resistance")} ")
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
}

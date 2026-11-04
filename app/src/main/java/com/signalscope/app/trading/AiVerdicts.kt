package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.network.StockAiClient

/**
 * THE NEWS OUTLOOK'S VERDICT, IN ONE LINE, for the watcher's alerts.
 *
 * The same outlook the detail sheet and the candidate list's AI button ask for —
 * same prompt, model and key — cut down to the call, its confidence and the first
 * sentence of the short-term view.
 *
 * Remembered per market and stock for the day, and the list's button writes here
 * too: a stock asked about once today is never asked again, whichever screen asked.
 * Asked one at a time on a thread of its own, so a watcher pass never waits on the
 * AI and three alerts landing together are three calls in a row, not at once.
 */
object AiVerdicts {
    private const val TAG = "AiVerdicts"
    private const val PREFS = "ai_verdicts"

    data class Verdict(val verdict: String, val confidence: String, val reason: String)

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ai-verdicts").apply { isDaemon = true }
    }
    /** Stocks already queued, each with everything waiting on its answer — two
     *  alerts for one stock make one call, and both get the verdict. */
    private val waiting = HashMap<String, MutableList<(Verdict) -> Unit>>()

    private fun key(space: String, symbol: String) =
        Spaces.baseOf(space.lowercase()) + ":" + symbol.trim().uppercase()

    private fun today() = java.time.LocalDate.now().toString()

    /** Today's verdict for this stock, if one has been asked for. */
    fun cached(ctx: Context, space: String, symbol: String): Verdict? = try {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(space, symbol), null)?.split("|", limit = 4)
        if (p == null || p.size < 4 || p[0] != today()) null else Verdict(p[1], p[2], p[3])
    } catch (e: Exception) { null }

    /** Keeps an outlook answer (the checked JSON the outlook returns) as today's
     *  verdict. An answer with no verdict in it is not kept — the next alert asks again. */
    fun remember(ctx: Context, space: String, symbol: String, outlookJson: String): Verdict? = try {
        val o = org.json.JSONObject(outlookJson)
        val v = o.optString("verdict").trim()
        if (v.isEmpty()) null
        else {
            val out = Verdict(v, o.optString("confidence").trim(),
                reasonOf(o.optString("short_term").ifBlank { o.optString("raw") }))
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val e = sp.edit()
            // Yesterday's answers go; only today's are ever read.
            sp.all.forEach { (k, x) -> if ((x as? String)?.startsWith(today() + "|") != true) e.remove(k) }
            e.putString(key(space, symbol), listOf(today(), out.verdict, out.confidence,
                out.reason.replace("|", "/")).joinToString("|")).apply()
            out
        }
    } catch (e: Exception) { Log.w(TAG, "could not keep the verdict for $symbol", e); null }

    /**
     * Today's verdict for this stock, handed to [then] on the AI's own thread —
     * from memory when it has one, otherwise asked for. Nothing at all when no AI
     * key is set, or when the call fails: an alert goes out with or without it.
     */
    fun whenReady(ctx: Context, space: String, symbol: String, price: Double, then: (Verdict) -> Unit) {
        val app = ctx.applicationContext
        if (noKey(app)) return
        val k = key(space, symbol)
        synchronized(waiting) {
            waiting[k]?.let { it.add(then); return }
            waiting[k] = mutableListOf(then)
        }
        worker.execute {
            val v = try { cached(app, space, symbol) ?: ask(app, space, symbol, price) }
                catch (e: Throwable) { Log.w(TAG, "verdict for $symbol failed", e); null }
            val all = synchronized(waiting) { waiting.remove(k) }.orEmpty()
            if (v != null) all.forEach { cb ->
                try { cb(v) } catch (e: Throwable) { Log.w(TAG, "after the verdict for $symbol", e) }
            }
        }
    }

    /** Today's verdict, asked for now if need be — for a background pass that
     *  can wait on the AI. Null with no AI key, or when the call fails. */
    fun now(ctx: Context, space: String, symbol: String, price: Double): Verdict? {
        val app = ctx.applicationContext
        if (noKey(app)) return null
        return cached(app, space, symbol) ?: try { ask(app, space, symbol, price) }
            catch (e: Exception) { Log.w(TAG, "verdict for $symbol failed", e); null }
    }

    private fun noKey(ctx: Context): Boolean =
        try { com.signalscope.app.data.ConfigManager(ctx).openaiApiKey.isBlank() } catch (e: Exception) { true }

    /** Asked WITH the company's name, as every other screen asks: the news
     *  search on a bare ticker ("SAX", "AIR") finds another company's news or none. */
    private fun ask(ctx: Context, space: String, symbol: String, price: Double): Verdict? {
        val known = try { ScanLookup.of(ctx, space, symbol) } catch (e: Exception) { null }
        val px = if (price > 0.0) price else known?.second ?: 0.0
        return when (val r = StockAiClient.analyzeOutlook(com.signalscope.app.data.ConfigManager(ctx),
            symbol, px, 0, 0, 0, "", "", 0.0, null, null, 0.0, 0.0, 0.0, false,
            StockAiClient.MarketContext.fromSpace(Spaces.baseOf(space.lowercase()).uppercase()),
            known?.first?.ifBlank { null })) {
            is StockAiClient.AiResult.Success -> remember(ctx, space, symbol, r.text)
            is StockAiClient.AiResult.Error -> { Log.w(TAG, "$symbol: ${r.message}"); null }
        }
    }

    private val CITE = Regex("\\s?\\[\\d+(?:\\s*[,;–-]\\s*\\d+)*]")

    /** The first sentence of the short-term view, without its headline numbers —
     *  an alert has no source list for them to point at. Pure — tested. */
    fun reasonOf(text: String): String {
        var t = CITE.replace(text, "").replace(Regex("\\s+"), " ").trim()
        val first = Regex("^.*?[.!?](?=\\s|$)").find(t)?.value
        if (first != null && first.length >= 25) t = first
        return if (t.length > 170) t.take(167) + "…" else t
    }

    /** "AI BUY · medium — Earnings beat lifted guidance.", the call coloured. */
    fun styled(v: Verdict): CharSequence {
        val head = "AI ${v.verdict}"
        val s = android.text.SpannableStringBuilder(head)
        val col = when {
            v.verdict.contains("BUY") -> 0xFF059669.toInt()
            v.verdict.contains("AVOID") || v.verdict == "SELL" || v.verdict == "REDUCE" -> 0xFFDC2626.toInt()
            else -> 0xFFA16207.toInt()
        }
        s.setSpan(android.text.style.ForegroundColorSpan(col), 3, head.length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, head.length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (v.confidence.isNotBlank()) s.append(" · ${v.confidence.lowercase()}")
        if (v.reason.isNotBlank()) s.append(" — ${v.reason}")
        return s
    }
}

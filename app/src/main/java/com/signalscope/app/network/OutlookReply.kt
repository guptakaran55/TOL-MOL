package com.signalscope.app.network

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The stock outlook's answer, rebuilt from whatever the model sent into one
 * checked shape that both the outlook panel and the floor algo read:
 *
 *   {"symbol","owned","asOf","verdict","confidence","news",
 *    "short_term","long_term","risks":[…],"sources":[{n,date,age,title,outlet,link,outlets}],
 *    "raw"  — only when the model's reply was not the JSON asked for}
 *
 * The verdict is held to the list the prompt offered; headline numbers in the
 * text are held to the headlines actually sent. Nothing here guesses a verdict
 * out of prose: a "sell-off" in the risks is never a SELL.
 */
object OutlookReply {

    /** Two up, one neutral, two down — for a stock the user owns, and one they don't. */
    val OWNED = listOf("STRONG BUY", "BUY", "HOLD", "REDUCE", "SELL")
    val NOT_OWNED = listOf("STRONG BUY", "BUY", "WATCH", "AVOID", "STRONG AVOID")

    fun verdicts(owned: Boolean): List<String> = if (owned) OWNED else NOT_OWNED

    /** A call from the other list, read as its counterpart in this one. */
    private val AS_OWNED = mapOf("WATCH" to "HOLD", "AVOID" to "REDUCE", "STRONG AVOID" to "SELL")
    private val AS_NOT_OWNED = mapOf("HOLD" to "WATCH", "REDUCE" to "AVOID", "SELL" to "AVOID")

    private val CONFIDENCE = setOf("LOW", "MEDIUM", "HIGH")
    private val NEWS = setOf("NEW", "REPEAT", "NONE")

    data class Source(val date: String, val age: String, val title: String, val outlet: String,
                      val link: String, val outlets: Int)

    fun build(raw: String, symbol: String, owned: Boolean, sources: List<Source>, asOf: String): String {
        val n = sources.size
        val o = try { JSONObject(jsonPart(raw)) } catch (e: Exception) { null }
        val out = JSONObject()
            .put("symbol", symbol)
            .put("owned", owned)
            .put("asOf", asOf)
        if (o != null) {
            out.put("verdict", verdict(o.optString("verdict"), owned))
            out.put("confidence", o.optString("confidence").trim().uppercase(Locale.US).takeIf { it in CONFIDENCE } ?: "")
            out.put("news", o.optString("news").trim().uppercase(Locale.US).takeIf { it in NEWS } ?: "")
            out.put("short_term", cites(o.optString("short_term"), n).take(700))
            out.put("long_term", cites(o.optString("long_term"), n).take(500))
            val risks = JSONArray()
            val arr = o.optJSONArray("risks")
            if (arr != null) {
                for (i in 0 until minOf(arr.length(), 4)) {
                    val r = cites(arr.optString(i), n).take(300)
                    if (r.isNotEmpty()) risks.put(r)
                }
            } else {
                // A model that sent the risks as one sentence instead of a list.
                cites(o.optString("risks"), n).take(400).takeIf { it.isNotEmpty() }?.let { risks.put(it) }
            }
            out.put("risks", risks)
        } else {
            // Not JSON — cut off, or the model ignored the shape. A verdict only
            // when it is written as one ("Verdict: AVOID"); a cut-off reply has
            // none and the algo asks again the next day.
            out.put("verdict", labelledVerdict(raw, owned))
            out.put("raw", cites(raw, n).take(3000))
        }
        out.put("sources", JSONArray().apply {
            sources.forEachIndexed { i, s ->
                put(JSONObject()
                    .put("n", i + 1).put("date", s.date).put("age", s.age).put("title", s.title)
                    .put("outlet", s.outlet).put("link", s.link).put("outlets", s.outlets))
            }
        })
        return out.toString()
    }

    /** The verdict in a built answer; "" when it has none or is not one. */
    fun verdictIn(built: String): String =
        try { JSONObject(built).optString("verdict", "") } catch (e: Exception) { "" }

    /** The last words of a built answer, for saying why it carried no verdict. */
    fun tail(built: String, len: Int): String = try {
        val o = JSONObject(built)
        val text = o.optString("raw").ifEmpty { listOf(o.optString("short_term"), o.optString("long_term")).joinToString(" ").trim() }
        text.ifEmpty { "an empty answer" }.takeLast(len)
    } catch (e: Exception) { built.takeLast(len) }

    // ── the pieces ──────────────────────────────────────────────────────────

    private fun verdict(raw: String, owned: Boolean): String {
        val v = raw.uppercase(Locale.US).replace('_', ' ').replace('-', ' ')
            .replace(Regex("[^A-Z ]"), " ").replace(Regex("\\s+"), " ").trim()
        val allowed = verdicts(owned)
        if (v in allowed) return v
        (if (owned) AS_OWNED else AS_NOT_OWNED)[v]?.let { return it }
        // "BUY (moderate)", "WATCH — wait for results": the call inside it.
        return callIn(v, owned) ?: ""
    }

    private fun callIn(text: String, owned: Boolean): String? {
        val all = (OWNED + NOT_OWNED).distinct().sortedByDescending { it.length }
        val m = Regex("\\b(" + all.joinToString("|") + ")\\b").find(text.uppercase(Locale.US)) ?: return null
        val v = m.groupValues[1]
        return if (v in verdicts(owned)) v else (if (owned) AS_OWNED else AS_NOT_OWNED)[v]
    }

    private fun labelledVerdict(raw: String, owned: Boolean): String {
        val at = raw.indexOf("verdict", ignoreCase = true)
        if (at < 0) return ""
        return callIn(raw.substring(at + 7, minOf(raw.length, at + 7 + 60)), owned) ?: ""
    }

    private val CITE = Regex("\\s*\\[(\\d+(?:\\s*[,;–-]\\s*\\d+)*)]")

    /** Headline numbers kept only if a headline by that number was sent;
     *  "[2-4]" and "[1; 3]" become "[2,3,4]" and "[1,3]". */
    fun cites(text: String, n: Int): String = CITE.replace(text.trim()) { m ->
        val nums = LinkedHashSet<Int>()
        for (part in m.groupValues[1].split(',', ';')) {
            val ends = part.split('-', '–').mapNotNull { it.trim().toIntOrNull() }
            when {
                ends.size == 2 && ends[1] >= ends[0] && ends[1] - ends[0] < 12 -> (ends[0]..ends[1]).forEach { nums.add(it) }
                ends.isNotEmpty() -> nums.add(ends[0])
            }
        }
        val ok = nums.filter { it in 1..n }
        if (ok.isEmpty()) "" else " [" + ok.joinToString(",") + "]"
    }.trim()

    /** The JSON object in a reply, even one wrapped in ```json fences or prose. */
    private fun jsonPart(raw: String): String {
        val s = raw.indexOf('{'); val e = raw.lastIndexOf('}')
        return if (s >= 0 && e > s) raw.substring(s, e + 1) else raw
    }
}

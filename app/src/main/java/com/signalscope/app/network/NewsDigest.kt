package com.signalscope.app.network

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * The short list of headlines an AI prompt reads, made from a Google News feed.
 *
 * Google ranks a search feed by relevance, not by date, and one event arrives as
 * a dozen near-identical headlines from different outlets. Taken as it came, the
 * newest story could sit at #11 of 12 while one buyback filled ten places. So:
 *   - quote pages, live-price pages, forecast pages and "best stocks" lists go;
 *   - one story told by several outlets becomes one line with an outlet count;
 *   - the newest stories are kept, newest first — with up to three places held
 *     for older stories that many outlets covered, so a big event two weeks
 *     back is not pushed out by this morning's small items.
 * No network and no Android here, so it is tested on real feeds.
 */
object NewsDigest {

    /** One feed item as Google sent it, in the feed's order. */
    data class Item(val title: String, val source: String, val timeMs: Long?, val link: String)

    /** One story: the headline shown for it, and how many outlets carried it. */
    data class Story(val title: String, val source: String, val timeMs: Long?, val link: String, val outlets: Int)

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val MAX_AGE_DAYS = 30
    /** Reports of one event more than this far apart are two stories. */
    private const val SAME_STORY_DAYS = 3
    /** A story this many outlets carried can claim a held place. */
    private const val WIDE = 3

    fun digest(items: List<Item>, max: Int, nowMs: Long, nameWords: Collection<String> = emptyList()): List<Story> {
        if (max <= 0) return emptyList()
        // The company's own name is in nearly every headline, so it says
        // nothing about whether two headlines tell the same story.
        val skip = GENERIC + nameWords.flatMap { words(it) }
        val cutoff = nowMs - MAX_AGE_DAYS * DAY_MS
        val kept = items.mapNotNull { clean(it) }
            .filter { it.timeMs == null || it.timeMs >= cutoff }
            .filterNot { isJunk(it) }
        if (kept.isEmpty()) return emptyList()

        val keys = kept.map { words(it.title).joinToString(" ") }
        val sets = kept.map { words(it.title).filterNot { w -> w in skip }.toSet() }
        val parent = IntArray(kept.size) { it }
        fun root(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        for (i in kept.indices) for (j in 0 until i) {
            if (root(i) != root(j) && sameStory(kept[i], kept[j], keys[i], keys[j], sets[i], sets[j]))
                parent[root(i)] = root(j)
        }
        // Each group's first member is the one Google ranked highest — its
        // headline, outlet, date and link stand for the story.
        val stories = kept.indices.groupBy { root(it) }.values.map { g ->
            val lead = kept[g.first()]
            val outlets = g.map { kept[it].source.lowercase().ifBlank { "#$it" } }.toSet().size
            Story(lead.title, lead.source, lead.timeMs, lead.link, outlets)
        }
        return choose(stories, max)
    }

    /** The newest [max], newest first; up to three places go to older, widely covered stories. */
    private fun choose(stories: List<Story>, max: Int): List<Story> {
        val byDate = stories.sortedByDescending { it.timeMs ?: Long.MIN_VALUE }
        if (byDate.size <= max) return byDate
        val pick = byDate.take(max).toMutableList()
        for (big in byDate.drop(max).filter { it.outlets >= WIDE }.take(minOf(3, max / 4))) {
            val out = pick.indexOfLast { it.outlets < WIDE }
            if (out < 0) break
            pick.removeAt(out)
            pick.add(big)
        }
        return pick.sortedByDescending { it.timeMs ?: Long.MIN_VALUE }
    }

    /**
     * How a story reads in a prompt:
     * "2026-10-15 (1 day ago) · Nvidia leads chip stocks lower — Yahoo Finance (+2 more outlets)".
     */
    fun line(s: Story, today: LocalDate, zone: ZoneId): String {
        val d = dateOf(s, zone)
        val head = if (d != null) "$d (${age(d, today)}) · " else ""
        val more = if (s.outlets > 1) " (+${s.outlets - 1} more outlet${if (s.outlets > 2) "s" else ""})" else ""
        val src = if (s.source.isNotBlank()) " — ${s.source}" else ""
        return "$head${s.title}$src$more"
    }

    fun dateOf(s: Story, zone: ZoneId): LocalDate? =
        s.timeMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

    fun age(d: LocalDate, today: LocalDate): String =
        when (val n = ChronoUnit.DAYS.between(d, today).coerceAtLeast(0)) {
            0L -> "today"
            1L -> "1 day ago"
            else -> "$n days ago"
        }

    // ── the pieces ──────────────────────────────────────────────────────────

    /** Google appends " - Outlet" to every title: split it off, so two pieces
     *  from one outlet do not look alike for sharing its name. */
    private fun clean(it: Item): Item? {
        var t = it.title.trim()
        var src = it.source.trim()
        val cut = t.lastIndexOf(" - ")
        if (cut > 0) {
            if (src.isEmpty()) src = t.substring(cut + 3).trim()
            t = t.substring(0, cut).trim()
        }
        return if (t.isEmpty()) null else it.copy(title = t, source = src)
    }

    private fun sameStory(a: Item, b: Item, ka: String, kb: String, wa: Set<String>, wb: Set<String>): Boolean {
        if (a.timeMs != null && b.timeMs != null && abs(a.timeMs - b.timeMs) > SAME_STORY_DAYS * DAY_MS) return false
        if (ka == kb) return true
        // Three telling words in common, and at least half of the shorter
        // headline's: "Nvidia Adds Record $150 Billion to Stock Buyback" and
        // "Nvidia Boosts Share Buyback by a Record $150 Billion" are one story.
        val shared = wa.count { it in wb }
        return shared >= 3 && shared * 2 >= minOf(wa.size, wb.size)
    }

    private val TOKEN = Regex("[\\p{L}\\p{N}]+(?:[.,]\\p{N}+)*")
    private val POSSESSIVE = Regex("['’]s\\b")

    fun words(s: String): List<String> =
        TOKEN.findAll(POSSESSIVE.replace(s.lowercase(), "")).map { it.value }.toList()

    private val GENERIC = setOf(
        "a", "an", "the", "and", "or", "of", "to", "in", "on", "for", "at", "by", "with", "from", "as",
        "is", "are", "was", "be", "its", "it", "this", "that", "after", "before", "over", "into", "about",
        "amid", "than", "what", "why", "how", "who", "will", "new", "says", "say", "said", "could", "may",
        "more", "up", "down", "vs", "his", "her", "their", "not", "has", "have", "had", "so", "much",
        "stock", "stocks", "share", "shares", "price", "company", "inc", "ltd", "limited", "corp",
        "corporation", "plc", "ag", "se", "nv", "sa", "co", "group", "holdings", "news",
        "nse", "bse", "nyse", "nasdaq", "xetra"
    )

    private val JUNK = listOf(
        // quote and live-price pages
        Regex("\\b(share|stock) price (today|live)\\b"),
        Regex("\\blive (share|stock) price\\b"),
        Regex("\\bstock price,? news,? quote\\b"),
        Regex("\\bquote (and|&) history\\b"),
        Regex("\\bprice (and|&) chart\\b"),
        Regex("\\bstock quote\\b"),
        // forecast pages
        Regex("\\bstock forecast\\b"),
        Regex("\\banalyst predictions\\b"),
        Regex("\\bprice prediction\\b"),
        Regex("\\bprice target 20\\d\\d\\b"),
        // lists of many stocks
        Regex("\\bbest ([\\w-]+ ){0,3}stocks\\b"),
        Regex("\\btop \\d+ ([\\w-]+ ){0,3}stocks\\b"),
        Regex("\\bstocks to (buy|watch|own)\\b")
    )

    /** Outlets whose items are machine-written price blurbs, not news. */
    private val JUNK_SOURCES = setOf("ad-hoc-news.de")

    private fun isJunk(it: Item): Boolean {
        if (it.source.lowercase() in JUNK_SOURCES) return true
        val t = it.title.lowercase()
        return JUNK.any { r -> r.containsMatchIn(t) }
    }
}

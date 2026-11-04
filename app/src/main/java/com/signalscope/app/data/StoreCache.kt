package com.signalscope.app.data

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/**
 * PARSED ONCE, READ MANY TIMES.
 *
 * The stores read and parsed their whole file on every call, and the busiest
 * are read constantly — the live ledger and every algo's book on each repaint
 * of the page and on every pass of the service. An algo's book holds up to
 * 1,000 trades and 500 audits, about 0.5 MB of JSON: the Algos card list alone
 * parsed 18 of them per repaint. Now each file is parsed once and kept, and
 * read again only when it has changed.
 *
 * WHY A KEPT COPY CANNOT GO STALE:
 *  • every read first looks at the file itself — its identity, size and
 *    modification time to the nanosecond (one stat call) — and uses the kept
 *    copy only when all three are exactly as they were when it was parsed. A
 *    file replaced by a save (a new file renamed into place), written in place,
 *    deleted or restored is parsed again, whoever changed it;
 *  • every store's own save calls [forget] once it has written, and a read that
 *    overlapped a write is not kept;
 *  • the stores' objects are immutable — val fields, read-only collections, and
 *    nothing in the app casts them to mutable ones — so one parsed copy can be
 *    handed to every caller on every thread.
 *
 * At most [MAX_FILES] files are kept; the least recently read go first.
 */
object StoreCache {
    private const val MAX_FILES = 64

    private data class Stamp(val key: Any?, val size: Long, val modifiedNs: Long)
    private class Kept(val stamp: Stamp, val value: Any)

    private val kept = object : LinkedHashMap<String, Kept>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Kept>?): Boolean = size > MAX_FILES
    }
    /** Bumped by [forget]: a read that began before a write must not keep what it read. */
    private val versions = HashMap<String, Long>()

    /**
     * [f]'s contents through [parse] — the kept copy when the file is exactly as
     * it was when that copy was parsed, else read and parsed now (and kept). Null
     * when the file does not exist or [parse] gives nothing; the store decides
     * what that means, as it always has. Exceptions from reading or parsing reach
     * the caller, and nothing is kept.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> read(f: File, parse: (String) -> T?): T? {
        val path = f.absolutePath
        val before = stamp(f)
        if (before == null) {                       // missing or unreadable: never served from memory
            synchronized(this) { kept.remove(path) }
            return null
        }
        var version = 0L
        synchronized(this) {
            val k = kept[path]
            if (k != null && k.stamp == before) return k.value as T
            version = versions[path] ?: 0L
        }
        val value = parse(f.readText()) ?: return null
        if (stamp(f) == before) synchronized(this) {
            if ((versions[path] ?: 0L) == version) kept[path] = Kept(before, value)
        }
        return value
    }

    /**
     * [text] as [f]'s new contents, IN ONE STEP: written to a temporary file of
     * its own, then renamed over [f]. A rename over an existing file is atomic,
     * so a reader sees the old file or the new one — never none, and never half
     * of one. The stores used to delete the old file first (or write over it in
     * place), and a read in that moment came back as an EMPTY ledger or book: in
     * a test with saves running back to back, over half of all reads did. Drops
     * the kept copy. Throws when the file could not be replaced.
     */
    fun write(f: File, text: String) {
        val tmp = File.createTempFile(f.name + ".", ".tmp", f.parentFile)
        try {
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {                 // only if the one-step replace is refused
                f.delete()
                if (!tmp.renameTo(f)) throw java.io.IOException("could not replace ${f.name}")
            }
        } finally {
            if (tmp.exists()) tmp.delete()          // still there only when the replace failed
            forget(f)
        }
    }

    /** [f] was just written, replaced or deleted: drop its kept copy. */
    fun forget(f: File) {
        val path = f.absolutePath
        synchronized(this) {
            versions[path] = (versions[path] ?: 0L) + 1L
            kept.remove(path)
        }
    }

    /** How many files are kept right now — for tests. */
    internal fun size(): Int = synchronized(this) { kept.size }

    private fun stamp(f: File): Stamp? = try {
        val a = Files.readAttributes(f.toPath(), BasicFileAttributes::class.java)
        if (!a.isRegularFile) null
        else Stamp(a.fileKey(), a.size(), a.lastModifiedTime().to(TimeUnit.NANOSECONDS))
    } catch (_: Exception) { null }
}

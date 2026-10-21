package com.signalscope.app.trading

import java.util.concurrent.ConcurrentHashMap

/**
 * ONE DOWNLOAD OF A STOCK'S BARS, SHARED BY EVERY ALGO THAT ASKS FOR THE SAME
 * FEED IN THE SAME PASS.
 *
 * Algos in one market rank the same universe the same way, so they tend to hold
 * the same names — and the 240/120, progenitor, crypto-fee and daily configs all
 * plan the same 60-minute feed. Every algo's pass starts on the same tick, so
 * without this each of them downloaded the identical series seconds apart.
 *
 * Kept for [ttlMs] only — long enough to cover one pass across every algo, short
 * enough that nothing is decided on older bars than a pass of its own would have
 * fetched. A failure is never kept: the next algo asks again, as it would have.
 *
 * Callers that ask at the same moment wait for the one download in flight
 * rather than each starting their own.
 */
object SharedFeed {

    private class Entry(val atMs: Long, val value: Any)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Any>()

    @Volatile var downloads = 0L; private set
    @Volatile var shared = 0L; private set

    /** [fetch] once per [key] per [ttlMs]; a result is kept only when [keep] says so. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String, ttlMs: Long, keep: (T) -> Boolean,
                      nowMs: () -> Long = System::currentTimeMillis, fetch: () -> T): T {
        fresh(key, ttlMs, nowMs())?.let { shared++; return it as T }
        val lock = locks.getOrPut(key) { Any() }
        synchronized(lock) {
            fresh(key, ttlMs, nowMs())?.let { shared++; return it as T }
            val v = fetch()
            downloads++
            val at = nowMs()
            if (keep(v)) entries[key] = Entry(at, v) else entries.remove(key)
            // Nothing outlives its window: the bars are dropped, not just ignored.
            entries.entries.removeIf { at - it.value.atMs > ttlMs }
            return v
        }
    }

    private fun fresh(key: String, ttlMs: Long, now: Long): Any? =
        entries[key]?.takeIf { now - it.atMs <= ttlMs }?.value

    fun clear() { entries.clear(); locks.clear(); downloads = 0; shared = 0 }
}

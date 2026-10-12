package com.signalscope.app.trading

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * ONE THING AT A TIME PER STOCK on the sell side of a Trading 212 account.
 *
 * A sale clears every waiting order on the stock and then walks a ladder of
 * limit sells for a few minutes. If a resting target were put back in the middle
 * of that, it would reserve the very shares the ladder is selling: the next rung
 * is refused for "no unreserved shares", which the ladder reads — correctly, in
 * every other situation — as proof the position already sold. The book would
 * then say sold while the shares sat there under a target.
 *
 * So a sale holds its stock for its whole length, and anything that places or
 * cancels a waiting sell asks first and steps aside if a sale is running. The
 * check is by Trading 212's own instrument code, because that is what the
 * account pools by.
 */
object SellGuard {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    private fun lockOf(ticker: String) =
        locks.computeIfAbsent(ticker.trim().uppercase()) { ReentrantLock() }

    /** A sale: waits for its turn, then holds the stock until done. */
    fun <T> selling(ticker: String, block: () -> T): T {
        val l = lockOf(ticker)
        l.lock()
        try { return block() } finally { l.unlock() }
    }

    /** Anything else: runs only if no sale holds the stock right now; null if one does. */
    fun <T> ifIdle(ticker: String, block: () -> T): T? {
        val l = lockOf(ticker)
        if (!l.tryLock()) return null
        try { return block() } finally { l.unlock() }
    }
}

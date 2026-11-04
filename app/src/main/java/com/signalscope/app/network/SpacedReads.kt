package com.signalscope.app.network

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * ONE READ AT A TIME, A FIXED GAP APART — FOR THE WHOLE APP.
 *
 * For an endpoint the broker answers only once every few seconds (Trading 212's
 * portfolio: once per 5 s per account). Every part of the app used to call it
 * whenever it liked, so at busy moments — ladders checking what they had bought,
 * the holdings check, the position audit, the German algos — most calls were
 * refused, and a refused read is indistinguishable from "could not read your
 * holding": the buy it was for was abandoned (NEE, 5 Nov 2026).
 *
 * Two kinds of caller:
 *
 *  • one that MUST have the answer (`wait = true` — a ladder, a button you
 *    pressed) waits for its turn. Callers that arrive while a read is waiting to
 *    start SHARE that read: it starts after each of them asked, so it is exactly
 *    as fresh for every one of them as their own would have been.
 *
 *  • one that can do without it (`wait = false` — a background check that tries
 *    again next pass, anything the page is waiting on) never waits. When no read
 *    may start now it gets [busy] at once — the answer the broker would have
 *    given — without sending a request that would only be refused and would take
 *    the turn a waiting caller needs.
 *
 * Reads START at least [spacingMs] apart, measured on a monotonic clock so a
 * change to the phone's time cannot hold them up.
 */
class SpacedReads<T>(
    private val spacingMs: Long,
    private val busy: () -> T,
    private val failed: (String) -> T,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private class Slot<T> {
        var started = false
        var done = false
        var value: T? = null
    }

    private val lock = ReentrantLock()
    private val finished = lock.newCondition()
    /** The earliest moment the next read may start; null before the first. The
     *  monotonic clock's origin is arbitrary (it can be negative), so "never" is
     *  null, not a number. */
    private var nextStartAt: Long? = null
    /** A read that has a turn booked but has not started — late arrivals join it. */
    private var pending: Slot<T>? = null

    /**
     * One read through [fetch], in its turn. [maxWaitMs] bounds how long a caller
     * that joined someone else's read waits for its answer.
     */
    fun read(wait: Boolean, maxWaitMs: Long = 120_000L, fetch: () -> T): T {
        val slot: Slot<T>
        val first: Boolean
        lock.withLock {
            val p = pending
            val now = clock()
            when {
                p != null -> {                          // a read is booked and not started: share it
                    if (!wait) return busy()
                    slot = p; first = false
                }
                nextStartAt.let { it == null || now >= it } -> {   // free: start now
                    slot = Slot<T>().also { it.started = true }; first = true
                    nextStartAt = now + spacingMs
                }
                else -> {                               // too soon after the last one: book the next turn
                    if (!wait) return busy()
                    slot = Slot<T>(); first = true
                    pending = slot
                }
            }
        }
        return if (first) runFirst(slot, fetch) else runShared(slot, maxWaitMs)
    }

    /** The caller whose read it is: wait for the turn if one was booked, read,
     *  and hand the answer to everyone who joined. */
    private fun runFirst(slot: Slot<T>, fetch: () -> T): T {
        // What followers get if this thread dies before it has an answer.
        var value: T = failed("the read it was waiting for did not finish")
        try {
            value = try {
                if (!slot.started) waitForTurn(slot)
                try { fetch() } catch (e: Exception) { failed(e.message ?: e.javaClass.simpleName) }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                failed("interrupted while waiting for its turn")
            }
            return value
        } finally {
            lock.withLock {
                if (pending === slot) pending = null    // never started: the next caller books its own turn
                if (!slot.done) { slot.value = value; slot.done = true }
                finished.signalAll()
            }
        }
    }

    private fun waitForTurn(slot: Slot<T>) {
        while (true) {
            val w = lock.withLock { nextStartAt?.let { it - clock() } ?: 0L }
            if (w <= 0L) break
            Thread.sleep(w)
        }
        lock.withLock {
            slot.started = true
            if (pending === slot) pending = null
            nextStartAt = clock() + spacingMs
        }
    }

    /** A caller that joined a booked read: wait for its answer. */
    private fun runShared(slot: Slot<T>, maxWaitMs: Long): T {
        val deadline = clock() + maxWaitMs
        lock.withLock {
            while (!slot.done) {
                val left = deadline - clock()
                if (left <= 0L) return failed("no answer from the read it was waiting for")
                try { finished.await(left, TimeUnit.MILLISECONDS) }
                catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return failed("interrupted while waiting for its turn")
                }
            }
            @Suppress("UNCHECKED_CAST")
            return slot.value as T
        }
    }
}

package com.signalscope.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One portfolio read at a time, a fixed gap apart, for the whole app: callers
 * that must have the answer wait their turn (and share a read that starts after
 * they asked); the others are told "busy" at once without a request being sent.
 */
class SpacedReadsTest {
    private val gap = 300L
    private fun now() = System.nanoTime() / 1_000_000L
    private fun gate() = SpacedReads<String>(gap, busy = { "BUSY" }, failed = { "FAIL:$it" })

    @Test fun `the first read starts at once, a waiting second one only after the gap`() {
        val g = gate(); val starts = Collections.synchronizedList(ArrayList<Long>())
        assertEquals("a", g.read(true) { starts.add(now()); "a" })
        assertEquals("b", g.read(true) { starts.add(now()); "b" })
        assertTrue("gap ${starts[1] - starts[0]}", starts[1] - starts[0] >= gap - 5)
    }

    @Test fun `a caller that does not wait is told busy at once, and nothing is sent`() {
        val g = gate(); val sent = AtomicInteger()
        g.read(false) { sent.incrementAndGet(); "a" }
        val t0 = now()
        assertEquals("BUSY", g.read(false) { sent.incrementAndGet(); "b" })
        assertTrue(now() - t0 < 50)
        assertEquals(1, sent.get())
        Thread.sleep(gap + 20)
        assertEquals("c", g.read(false) { sent.incrementAndGet(); "c" })   // free again after the gap
    }

    @Test fun `callers arriving while a read is booked share it, and it starts after all of them asked`() {
        val g = gate(); val sent = AtomicInteger()
        g.read(true) { "first" }                                   // the gap starts now
        val asked = Collections.synchronizedList(ArrayList<Long>())
        var startedAt = 0L
        val results = Collections.synchronizedList(ArrayList<String>())
        val ready = CountDownLatch(5)
        val threads = (0 until 5).map {
            Thread {
                asked.add(now()); ready.countDown()
                results.add(g.read(true) { startedAt = now(); "shared#${sent.incrementAndGet()}" })
            }
        }
        threads.forEach { it.start() }; threads.forEach { it.join(5_000) }
        assertTrue(ready.await(1, TimeUnit.SECONDS))
        assertEquals(1, sent.get())                                // ONE request for all five
        assertEquals(List(5) { "shared#1" }, results)
        assertTrue(asked.all { it <= startedAt })                  // fresh for every one of them
    }

    @Test fun `a caller that does not wait does not take a booked turn`() {
        val g = gate(); val sent = AtomicInteger()
        g.read(true) { "first" }
        val waiter = Thread { g.read(true) { sent.incrementAndGet(); "waited" } }
        waiter.start(); Thread.sleep(50)                           // its turn is booked now
        assertEquals("BUSY", g.read(false) { sent.incrementAndGet(); "stolen" })
        waiter.join(5_000)
        assertEquals(1, sent.get())
    }

    @Test fun `a failing read fails for everyone who shared it, and nobody hangs`() {
        val g = gate()
        g.read(true) { "first" }
        val results = Collections.synchronizedList(ArrayList<String>())
        val threads = (0 until 3).map { Thread { results.add(g.read(true) { throw IllegalStateException("boom") }) } }
        threads.forEach { it.start() }; threads.forEach { it.join(5_000) }
        assertEquals(List(3) { "FAIL:boom" }, results)
        Thread.sleep(gap + 20)
        assertEquals("ok", g.read(true) { "ok" })                  // and the gate still works
    }

    @Test fun `an interrupted waiter gives its sharers an answer, and the next caller still reads`() {
        val g = gate()
        g.read(true) { "first" }
        var leadResult = ""
        val lead = Thread { leadResult = g.read(true) { "never" } }
        lead.start(); Thread.sleep(30)
        var shared = ""
        val follower = Thread { shared = g.read(true) { "never either" } }
        follower.start(); Thread.sleep(30)
        lead.interrupt()
        lead.join(5_000); follower.join(5_000)
        assertTrue(leadResult, leadResult.startsWith("FAIL:interrupted"))
        assertTrue(shared, shared.startsWith("FAIL:"))
        assertEquals("next", g.read(true) { "next" })
    }

    @Test fun `under load every read starts at least the gap after the one before`() {
        val g = gate(); val starts = Collections.synchronizedList(ArrayList<Long>())
        val threads = (0 until 12).map { i ->
            Thread {
                repeat(2) { g.read(i % 3 != 0) { starts.add(now()); Thread.sleep(20); "x" }; Thread.sleep(37L * i % 90) }
            }
        }
        threads.forEach { it.start() }; threads.forEach { it.join(30_000) }
        val s = starts.sorted()
        assertTrue(s.size >= 2)
        for (i in 1 until s.size) assertTrue("gap ${s[i] - s[i - 1]}", s[i] - s[i - 1] >= gap - 40)
    }
}

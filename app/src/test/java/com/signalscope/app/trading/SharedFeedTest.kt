package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One download per feed per pass, shared by every algo that asks for it. */
class SharedFeedTest {

    @Before fun reset() = SharedFeed.clear()

    private var clock = 1_000_000L
    private fun get(key: String, calls: AtomicInteger, value: String = "bars") =
        SharedFeed.get(key, 60_000L, keep = { v: String -> v != "failed" }, nowMs = { clock }) {
            calls.incrementAndGet(); value
        }

    @Test fun `a second algo asking for the same feed is served the first one's download`() {
        val calls = AtomicInteger()
        assertEquals("bars", get("de|SAP|60m", calls))
        clock += 20_000
        assertEquals("bars", get("de|SAP|60m", calls))
        assertEquals(1, calls.get())
    }

    @Test fun `a different feed of the same stock is downloaded on its own`() {
        val calls = AtomicInteger()
        get("de|SAP|60m", calls); get("de|SAP|15m", calls); get("us|SAP|60m", calls)
        assertEquals(3, calls.get())
    }

    @Test fun `past its window the feed is downloaded again`() {
        val calls = AtomicInteger()
        get("de|SAP|60m", calls)
        clock += 60_001
        get("de|SAP|60m", calls)
        assertEquals(2, calls.get())
    }

    @Test fun `a failed download is not kept - the next algo asks again`() {
        val calls = AtomicInteger()
        assertEquals("failed", get("de|SAP|60m", calls, "failed"))
        assertEquals("bars", get("de|SAP|60m", calls, "bars"))
        assertEquals(2, calls.get())
    }

    @Test fun `algos asking at the same moment wait for one download`() {
        val calls = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        val go = CountDownLatch(1)
        val done = CountDownLatch(8)
        repeat(8) {
            pool.execute {
                go.await()
                SharedFeed.get("binance|BTCUSDC|2h", 60_000L, keep = { _: String -> true }) {
                    calls.incrementAndGet(); Thread.sleep(50); "bars"
                }
                done.countDown()
            }
        }
        go.countDown()
        done.await(5, TimeUnit.SECONDS)
        pool.shutdown()
        assertEquals(1, calls.get())
        assertEquals(7L, SharedFeed.shared)
    }
}

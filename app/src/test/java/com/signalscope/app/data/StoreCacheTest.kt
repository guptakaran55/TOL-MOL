package com.signalscope.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The stores' files, parsed once and kept until they change. A kept copy must
 * never outlive a change to its file, whoever made it.
 */
class StoreCacheTest {
    private val dir: File = Files.createTempDirectory("storecache").toFile()
    private data class Box(val text: String)

    private fun file(name: String, text: String) = File(dir, name).apply { writeText(text) }
    private var parses = 0
    private fun read(f: File): Box? = StoreCache.read(f) { parses++; Box(it) }

    @Test fun `an unchanged file is parsed once and the same copy handed out`() {
        val f = file("a.json", "one")
        val first = read(f); val second = read(f)
        assertEquals(Box("one"), first)
        assertSame(first, second)
        assertEquals(1, parses)
    }

    @Test fun `a store's save is seen at once`() {
        val f = file("b.json", "one"); read(f)
        f.writeText("two"); StoreCache.forget(f)
        assertEquals(Box("two"), read(f))
    }

    @Test fun `a file replaced the way the stores save it is seen, even at the same size and instant`() {
        val f = file("c.json", "one"); read(f)
        val tmp = File(dir, "c.json.tmp").apply { writeText("two") }      // same length
        f.delete(); tmp.renameTo(f)                                      // no forget: a new file all the same
        assertEquals(Box("two"), read(f))
    }

    @Test fun `a file written in place by anything else is seen`() {
        val f = file("d.json", "one"); read(f)
        Thread.sleep(25)                                                 // past the filesystem clock's tick
        f.writeText("two")                                               // same length, same file, no forget
        assertEquals(Box("two"), read(f))
    }

    @Test fun `a deleted file is not served from memory`() {
        val f = file("e.json", "one"); read(f)
        f.delete()
        assertNull(read(f))
    }

    @Test fun `a read that a write overtook is used but not kept`() {
        val f = file("f.json", "one")
        val v = StoreCache.read(f) { parses++; StoreCache.forget(f); Box(it) }   // a save lands mid-parse
        assertEquals(Box("one"), v)
        read(f)
        assertEquals(2, parses)                                          // parsed again: nothing was kept
    }

    @Test fun `a parse error reaches the caller and nothing is kept`() {
        val f = file("g.json", "bad")
        try { StoreCache.read(f) { throw IllegalStateException("corrupt") }; fail() }
        catch (e: IllegalStateException) { assertEquals("corrupt", e.message) }
        assertEquals(Box("bad"), read(f))
        assertEquals(1, parses)
    }

    @Test fun `a write replaces the file in one step, leaves no temporary file, and is seen at once`() {
        val f = file("h.json", "one"); read(f)
        StoreCache.write(f, "two, longer")
        assertEquals(Box("two, longer"), read(f))
        StoreCache.write(File(dir, "new.json"), "fresh")                  // creates a file that was not there
        assertEquals(Box("fresh"), read(File(dir, "new.json")))
        assertTrue(dir.list()!!.none { it.endsWith(".tmp") })
    }

    @Test fun `readers never see an empty or half-written file while writes go on`() {
        val f = file("i.json", "v0000")
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val bad = java.util.concurrent.atomic.AtomicInteger()
        val readers = (0 until 4).map {
            Thread { while (!stop.get()) { val v = StoreCache.read(f) { Box(it) }; if (v == null || !v.text.matches(Regex("v\\d{4}"))) bad.incrementAndGet() } }
        }
        readers.forEach { it.start() }
        for (i in 1..500) StoreCache.write(f, "v%04d".format(i))
        stop.set(true); readers.forEach { it.join() }
        assertEquals(0, bad.get())
        assertEquals(Box("v0500"), StoreCache.read(f) { Box(it) })
    }

    @Test fun `no more than 64 files are kept`() {
        repeat(80) { i -> read(file("many$i.json", "v$i")) }
        assertTrue(StoreCache.size() <= 64)
        val again = read(File(dir, "many79.json"))                       // the newest is still kept
        assertEquals(Box("v79"), again)
        assertEquals(80, parses)
    }
}

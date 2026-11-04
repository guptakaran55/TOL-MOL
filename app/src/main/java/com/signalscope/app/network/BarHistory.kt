package com.signalscope.app.network

import android.content.Context
import android.util.Log
import com.signalscope.app.data.CandleData
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * A LONG PRICE HISTORY DOWNLOADED ONCE A DAY, NOT ON EVERY CHECK (the user's
 * design, 31 Oct 2026).
 *
 * Each 15-minute check downloaded two years of hourly bars — or two months of
 * 15-minute ones — for every stock an algo looks at: ~275 series, ~35 MB a check,
 * to learn one new bar each. Now:
 *  • the FIRST request of the market's day — the pass before the open — downloads
 *    the whole series and keeps it in the phone's storage;
 *  • every later request downloads only the last [RECENT_RANGE] and checks it
 *    against the kept copy where both have bars. If they agree, the kept bars
 *    before the fresh ones plus the fresh ones are the answer — the same series a
 *    full download gives. If any bar differs (a split, a correction), the kept
 *    copy is thrown away and the whole series downloaded again.
 * The kept copy is not rewritten during the day — the morning's copy and the
 * latest few days always meet, so storage is written once a day, not every check.
 *
 * Long series of 15-, 30- and 60-minute bars only; everything else is small and
 * fetched whole, as is a stock whose Yahoo line has been renamed.
 */
object BarHistory {
    private const val TAG = "BarHistory"
    private const val DIR = "bar_history"
    private const val VERSION = 1
    /** What a check downloads once the day's copy is kept. */
    const val RECENT_RANGE = "5d"
    /** Kept and fresh closes differing by more than this are a changed history. */
    const val TOLERANCE = 0.0005
    /** Fewest shared bars that make a comparison. */
    const val MIN_COMPARED = 3
    private const val UNUSED_DAYS = 7L

    @Volatile private var dir: File? = null

    /** At app start. Until then — and in tests — every request is a full download. */
    fun init(ctx: Context) {
        val d = File(ctx.applicationContext.filesDir, DIR).apply { mkdirs() }
        dir = d
        try {
            val cutoff = System.currentTimeMillis() - UNUSED_DAYS * 86_400_000L
            d.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        } catch (e: Exception) { Log.w(TAG, "tidy failed", e) }
    }

    // ── PURE — tested ────────────────────────────────────────────────────────

    fun rangeDays(range: String): Int {
        val r = range.trim().lowercase()
        fun n(suffix: String) = r.removeSuffix(suffix).toIntOrNull() ?: 0
        return when {
            r.endsWith("mo") -> n("mo") * 30
            r.endsWith("d") -> n("d")
            r.endsWith("y") -> n("y") * 365
            else -> 0
        }
    }

    fun applies(range: String, interval: String, prePost: Boolean): Boolean =
        !prePost && interval in setOf("15m", "30m", "60m", "1h") && rangeDays(range) >= 20

    /** The market's own calendar, which decides when "today" starts. */
    fun zoneOf(yahooSymbol: String): ZoneId = when {
        yahooSymbol.endsWith(".DE", true) -> ZoneId.of("Europe/Berlin")
        yahooSymbol.endsWith(".NS", true) || yahooSymbol.endsWith(".BO", true) -> ZoneId.of("Asia/Kolkata")
        else -> ZoneId.of("America/New_York")
    }

    /**
     * Do the kept bars agree with the fresh ones wherever both have a bar? The
     * last kept bar is left out — it may have been taken while still forming — and
     * fewer than [MIN_COMPARED] shared bars, or fresh bars that start after the
     * kept ones end, is no agreement.
     */
    fun agrees(kept: List<CandleData>, fresh: List<CandleData>, tol: Double = TOLERANCE): Boolean {
        if (kept.size < 2 || fresh.isEmpty()) return false
        val first = fresh.first().timestamp
        val lastKept = kept.last().timestamp
        if (first > lastKept) return false
        val byTime = fresh.associateBy { it.timestamp }
        var compared = 0
        for (k in kept) {
            if (k.timestamp < first || k.timestamp >= lastKept) continue
            val f = byTime[k.timestamp] ?: continue
            compared++
            if (Math.abs(f.close - k.close) > tol * Math.max(Math.abs(f.close), 1e-9)) return false
        }
        return compared >= MIN_COMPARED
    }

    /** The kept bars before the fresh ones, then the fresh ones — nothing older than [fromSec]. */
    fun merge(kept: List<CandleData>, fresh: List<CandleData>, fromSec: Long): List<CandleData> {
        val first = fresh.first().timestamp
        return kept.filter { it.timestamp < first && it.timestamp >= fromSec } + fresh
    }

    // ── THE KEPT COPY ────────────────────────────────────────────────────────

    data class Kept(val day: String, val name: String?, val bars: List<CandleData>)

    fun save(f: File, k: Kept) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { o ->
            o.writeInt(VERSION); o.writeUTF(k.day); o.writeUTF(k.name ?: ""); o.writeInt(k.bars.size)
            for (b in k.bars) {
                o.writeLong(b.timestamp); o.writeDouble(b.open); o.writeDouble(b.high)
                o.writeDouble(b.low); o.writeDouble(b.close); o.writeLong(b.volume)
            }
        }
        if (f.exists()) f.delete()
        tmp.renameTo(f)
    }

    fun load(f: File): Kept? = try {
        if (!f.exists()) null else DataInputStream(f.inputStream().buffered()).use { i ->
            if (i.readInt() != VERSION) return null
            val day = i.readUTF(); val name = i.readUTF().ifEmpty { null }
            val n = i.readInt()
            val bars = ArrayList<CandleData>(n)
            repeat(n) {
                bars.add(CandleData(i.readLong(), i.readDouble(), i.readDouble(), i.readDouble(), i.readDouble(), i.readLong()))
            }
            Kept(day, name, bars)
        }
    } catch (e: Exception) { Log.w(TAG, "${f.name} unreadable — downloaded whole", e); null }

    private fun fileFor(key: String): File? =
        dir?.let { File(it, key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".bin") }

    /**
     * The series for [key] ("SYMBOL|range|interval"): whole once a day, then the
     * latest few days on top of the kept copy. [full] and [recent] are the two
     * downloads; either may be used, never both on a day's later checks.
     */
    fun fetch(key: String, yahooSymbol: String, range: String, nowMs: Long,
              full: () -> YahooFinanceClient.CandleResult,
              recent: () -> YahooFinanceClient.CandleResult): YahooFinanceClient.CandleResult {
        val f = fileFor(key) ?: return full()
        val today = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zoneOf(yahooSymbol)).toString()
        val kept = load(f)?.takeIf { it.day == today && it.bars.isNotEmpty() }
        if (kept != null) {
            val r = recent()
            if (r is YahooFinanceClient.CandleResult.RateLimited) return r
            val fresh = (r as? YahooFinanceClient.CandleResult.Success)?.candles.orEmpty()
            if (agrees(kept.bars, fresh))
                return YahooFinanceClient.CandleResult.Success(
                    merge(kept.bars, fresh, nowMs / 1000L - rangeDays(range) * 86_400L), r.let { (it as YahooFinanceClient.CandleResult.Success).name } ?: kept.name)
            Log.i(TAG, "$key: " + (if (fresh.isEmpty()) "latest bars unavailable" else "history changed since this morning") +
                " — downloaded whole")
        }
        val r = full()
        if (r is YahooFinanceClient.CandleResult.Success && r.candles.isNotEmpty())
            try { save(f, Kept(today, r.name, r.candles)) } catch (e: Exception) { Log.w(TAG, "$key not kept", e) }
        return r
    }
}

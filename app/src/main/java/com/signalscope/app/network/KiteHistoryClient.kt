package com.signalscope.app.network

import android.content.Context
import android.util.Log
import com.google.gson.JsonParser
import com.signalscope.app.data.CandleData
import com.signalscope.app.data.ConfigManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

// ════════════════════════════════════════════════════════════════════════════
// KITE HISTORICAL CANDLES — the broker's own bars, for the Indian market
// ════════════════════════════════════════════════════════════════════════════
// The strategy has always been evaluated on Yahoo bars, including for India,
// because that is the only feed the app implemented. Zerodha sells the real
// thing on the same subscription that provides the live stream: exchange bars,
// from the exchange, for the market they belong to.
//
// KEPT ALONGSIDE YAHOO, NOT INSTEAD OF IT. That subscription can lapse between
// months, and a strategy that silently stops seeing candles is far worse than
// one running on a second-best feed. So the source is a setting, and a failure
// here falls back rather than going dark — loudly, so a lapsed subscription is
// visible instead of being mistaken for a quiet market.
//
// TWO CALLS, and the second needs the first: history is addressed by a numeric
// instrument token, never by the trading symbol. That lookup is a multi-megabyte
// CSV of every instrument on every exchange, so it is fetched once a day and
// cached on disk.
// ════════════════════════════════════════════════════════════════════════════

object KiteHistoryClient {

    private const val TAG = "KiteHistory"
    private const val BASE_URL = "https://api.kite.trade"

    /** Kite allows 3 historical requests a second. Pacing below that has proved
     *  reliable across whole-universe pulls; going to the limit has not. */
    private const val REQUEST_GAP_MS = 400L

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    sealed class Result {
        data class Success(val candles: List<CandleData>) : Result()
        /** The session is dead — re-authenticate. Never retried. */
        object SessionExpired : Result()
        /** No history access on this API key, or the call was refused. */
        data class Unavailable(val why: String) : Result()
    }

    /**
     * Interval names and the widest window Kite will answer in one request.
     *
     * The caps are the broker's and it has changed them before, which is why the
     * fetch below halves and retries rather than trusting this table blindly. A
     * stale number here should cost one extra round trip, not a failed run.
     */
    private data class Spec(val kite: String, val maxDays: Long)

    private fun specFor(interval: String): Spec? = when (interval.lowercase().trim()) {
        "1m", "minute" -> Spec("minute", 60)
        "3m" -> Spec("3minute", 100)
        "5m" -> Spec("5minute", 100)
        "10m" -> Spec("10minute", 100)
        "15m" -> Spec("15minute", 200)
        "30m" -> Spec("30minute", 200)
        "60m", "1h" -> Spec("60minute", 400)
        "1d", "day" -> Spec("day", 2000)
        else -> null
    }

    fun supports(interval: String): Boolean = specFor(interval) != null

    // ── instrument tokens ───────────────────────────────────────────────────

    @Volatile private var tokens: Map<String, Long> = emptyMap()
    @Volatile private var tokensDay: String = ""

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "kite_instruments.csv")

    /**
     * Trading symbol to instrument token for NSE cash equities.
     *
     * Cached for the UTC day in memory and on disk. The download is several
     * megabytes of every instrument on every exchange, so doing it per symbol —
     * or per pass — would cost more than the candles it is meant to fetch.
     */
    @Synchronized
    private fun tokenMap(ctx: Context, cfg: ConfigManager): Map<String, Long> {
        val today = LocalDate.now(IST).toString()
        if (tokensDay == today && tokens.isNotEmpty()) return tokens

        val f = cacheFile(ctx)
        val fresh = f.exists() &&
            LocalDate.ofEpochDay(f.lastModified() / 86_400_000L).toString() >= today
        val csv = if (fresh) {
            try { f.readText() } catch (e: Exception) { "" }
        } else {
            try {
                val req = Request.Builder().url("$BASE_URL/instruments")
                    .addHeader("X-Kite-Version", "3")
                    .addHeader("Authorization", "token ${cfg.zerodhaApiKey}:${cfg.zerodhaAccessToken}")
                    .build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) { Log.w(TAG, "instruments HTTP ${r.code}"); "" }
                    else (r.body?.string() ?: "").also { body ->
                        if (body.isNotBlank()) try { f.writeText(body) } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "instruments fetch failed: ${e.message}"); "" }
        }
        if (csv.isBlank()) return tokens          // keep whatever we had

        val map = HashMap<String, Long>(4096)
        val lines = csv.lineSequence().iterator()
        if (!lines.hasNext()) return tokens
        val header = lines.next().split(',')
        val iTok = header.indexOf("instrument_token")
        val iSym = header.indexOf("tradingsymbol")
        val iExch = header.indexOf("exchange")
        val iType = header.indexOf("instrument_type")
        if (iTok < 0 || iSym < 0 || iExch < 0) return tokens
        while (lines.hasNext()) {
            val p = lines.next().split(',')
            if (p.size <= maxOf(iTok, iSym, iExch)) continue
            if (p[iExch] != "NSE") continue
            if (iType >= 0 && p.size > iType && p[iType] != "EQ") continue
            val t = p[iTok].toLongOrNull() ?: continue
            map[p[iSym].uppercase()] = t
        }
        if (map.isNotEmpty()) { tokens = map; tokensDay = today }
        Log.i(TAG, "instrument tokens: ${map.size} NSE equities")
        return tokens
    }

    // ── candles ─────────────────────────────────────────────────────────────

    /**
     * Bars for one NSE symbol over the last [days] calendar days.
     *
     * Chunked to the interval's cap and concatenated. Each chunk halves and
     * retries once on a refusal that looks like a window problem, and backs off
     * on a rate limit — the two failure modes that actually occur.
     */
    fun fetchCandles(ctx: Context, symbol: String, interval: String, days: Int): Result {
        val cfg = ConfigManager(ctx)
        if (!cfg.isZerodhaConnected) return Result.Unavailable("Zerodha is not connected")
        val spec = specFor(interval)
            ?: return Result.Unavailable("Kite has no $interval interval")
        val sym = symbol.trim().uppercase().removeSuffix(".NS")
        val token = tokenMap(ctx, cfg)[sym]
            ?: return Result.Unavailable("no NSE instrument token for $sym")

        val end = LocalDateTime.now(IST)
        val start = end.minusDays(days.toLong().coerceAtLeast(1))
        val out = ArrayList<CandleData>(4096)

        var from = start
        while (from.isBefore(end)) {
            val to = minOf(from.plusDays(spec.maxDays), end)
            when (val chunk = fetchWindow(cfg, token, spec.kite, from, to, depth = 0)) {
                is Result.Success -> out.addAll(chunk.candles)
                is Result.SessionExpired -> return chunk
                is Result.Unavailable -> {
                    // A refused chunk in the middle would leave a hole in the
                    // series, and a strategy silently evaluated over a gapped
                    // window is worse than one that says it has no data.
                    if (out.isEmpty()) return chunk
                    Log.w(TAG, "$sym: window from $from refused (${chunk.why}) — stopping with ${out.size} bars")
                    break
                }
            }
            from = to
            try { Thread.sleep(REQUEST_GAP_MS) } catch (_: InterruptedException) {}
        }
        if (out.isEmpty()) return Result.Unavailable("no candles returned for $sym")
        // Kite returns each chunk ascending; chunk boundaries can repeat a bar.
        val deduped = out.distinctBy { it.timestamp }.sortedBy { it.timestamp }
        return Result.Success(deduped)
    }

    private fun fetchWindow(
        cfg: ConfigManager, token: Long, interval: String,
        from: LocalDateTime, to: LocalDateTime, depth: Int, attempt: Int = 0
    ): Result {
        val url = "$BASE_URL/instruments/historical/$token/$interval" +
            "?from=${STAMP.format(from).replace(" ", "+")}" +
            "&to=${STAMP.format(to).replace(" ", "+")}"
        return try {
            val req = Request.Builder().url(url)
                .addHeader("X-Kite-Version", "3")
                .addHeader("Authorization", "token ${cfg.zerodhaApiKey}:${cfg.zerodhaAccessToken}")
                .build()
            http.newCall(req).execute().use { r ->
                when {
                    r.code == 403 -> Result.SessionExpired
                    r.code == 429 -> {
                        if (attempt >= 3) Result.Unavailable("rate limited by Zerodha")
                        else {
                            try { Thread.sleep(2000L * (attempt + 1)) } catch (_: InterruptedException) {}
                            fetchWindow(cfg, token, interval, from, to, depth, attempt + 1)
                        }
                    }
                    r.code == 400 -> {
                        // Usually "window too wide". The published caps have moved
                        // before, so split rather than trusting the table.
                        val spanDays = java.time.Duration.between(from, to).toDays()
                        if (depth >= 4 || spanDays <= 2)
                            Result.Unavailable("Zerodha refused the request (HTTP 400)")
                        else {
                            val mid = from.plusDays(spanDays / 2)
                            val a = fetchWindow(cfg, token, interval, from, mid, depth + 1)
                            if (a !is Result.Success) a
                            else {
                                val b = fetchWindow(cfg, token, interval, mid, to, depth + 1)
                                if (b !is Result.Success) b
                                else Result.Success(a.candles + b.candles)
                            }
                        }
                    }
                    !r.isSuccessful -> Result.Unavailable("HTTP ${r.code}")
                    else -> parse(r.body?.string())
                }
            }
        } catch (e: Exception) {
            Result.Unavailable(e.message ?: "historical request failed")
        }
    }

    private fun parse(body: String?): Result {
        if (body.isNullOrBlank()) return Result.Unavailable("empty response")
        return try {
            val j = JsonParser.parseString(body).asJsonObject
            if (j.get("status")?.asString != "success")
                return Result.Unavailable(j.get("message")?.asString ?: "request not successful")
            val arr = j.getAsJsonObject("data")?.getAsJsonArray("candles")
                ?: return Result.Unavailable("no candles in response")
            val out = ArrayList<CandleData>(arr.size())
            for (el in arr) {
                val c = el.asJsonArray
                if (c.size() < 6) continue
                // "2026-08-01T09:15:00+0530" — offset included, so the instant is
                // unambiguous and no timezone assumption is needed here.
                val ts = try {
                    java.time.OffsetDateTime.parse(
                        c[0].asString,
                        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")
                    ).toEpochSecond()
                } catch (e: Exception) {
                    try { java.time.OffsetDateTime.parse(c[0].asString).toEpochSecond() }
                    catch (e2: Exception) { continue }
                }
                out.add(CandleData(ts, c[1].asDouble, c[2].asDouble,
                    c[3].asDouble, c[4].asDouble, c[5].asLong))
            }
            if (out.isEmpty()) Result.Unavailable("no usable candles") else Result.Success(out)
        } catch (e: Exception) {
            Result.Unavailable(e.message ?: "could not read the response")
        }
    }
}

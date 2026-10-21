package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.signalscope.app.network.ZerodhaClient
import java.io.File

// ════════════════════════════════════════════════════════════════════════════
// EVERY FILL THIS ZERODHA ACCOUNT HAS EXECUTED, kept because nobody else will
// ════════════════════════════════════════════════════════════════════════════
// Kite's trade list holds TODAY and nothing else. At the next day's open it is
// empty again, and there is no endpoint that will give yesterday back. So the
// only way this app can ever answer "what did my Indian trading actually make"
// is to ask on each trading day and keep the answer.
//
// That makes this file the record itself rather than a cache of one, and two
// things follow from it:
//
//   NEVER OVERWRITE, ONLY ADD. A pass that returns nothing — a holiday, an
//   expired session, a phone with no signal — must leave what is already here
//   untouched. Treating an empty answer as the truth would erase the history on
//   the first quiet morning.
//
//   DEDUPE ON THE BROKER'S OWN TRADE ID. The same day is polled many times, so
//   without a stable key every poll would add the morning's fills again and the
//   P&L would grow by itself.
//
// HONEST ABOUT ITS OWN HORIZON. It can only know what happened since the app
// started asking. [firstSeenDay] says when that was, so a total is read against
// a period rather than mistaken for a lifetime figure.
// ════════════════════════════════════════════════════════════════════════════

object ZerodhaTradeStore {

    private const val TAG = "ZerodhaTradeStore"
    private const val FILE = "zerodha_trade_history.json"
    private val gson = Gson()

    data class Row(
        val tradeId: String = "",
        val orderId: String = "",
        val symbol: String = "",
        val exchange: String = "",
        val side: String = "",
        val qty: Double = 0.0,
        val price: Double = 0.0,
        /** Kite's own fill stamp, "yyyy-MM-dd HH:mm:ss" in IST. */
        val filledAt: String = "",
        val product: String = "",
        /** When this app first saw it, so a row can be traced to a poll. */
        val seenAt: Long = 0L
    )

    data class State(
        val rows: List<Row> = emptyList(),
        /** UTC date of the first successful capture — the start of what is known. */
        val firstSeenDay: String = "",
        val lastCaptureAt: Long = 0L,
        /** The last poll's outcome, so the panel can say why it is short. */
        val lastNote: String = ""
    )

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    @Synchronized
    fun load(ctx: Context): State = try {
        val f = file(ctx)
        if (!f.exists()) State() else gson.fromJson(f.readText(), State::class.java) ?: State()
    } catch (e: Exception) { Log.e(TAG, "load failed", e); State() }

    @Synchronized
    private fun save(ctx: Context, s: State) {
        try { file(ctx).writeText(gson.toJson(s)) }
        catch (e: Exception) { Log.e(TAG, "save failed", e) }
    }

    /** At most one poll per this interval, however many callers ask. */
    private const val MIN_GAP_MS = 10 * 60 * 1000L

    /**
     * Ask Kite for today's fills and merge them in.
     *
     * @param force skip the throttle — for a user who tapped "re-read".
     * @return how many fills were NEW to the store.
     */
    fun capture(ctx: Context, force: Boolean = false): Int {
        val cur = load(ctx)
        val now = System.currentTimeMillis()
        if (!force && cur.lastCaptureAt > 0L && now - cur.lastCaptureAt < MIN_GAP_MS) return 0
        val cfg = ConfigManager(ctx)
        if (!cfg.isZerodhaConnected) {
            save(ctx, cur.copy(lastNote = "Zerodha is not connected — nothing captured."))
            return 0
        }
        val fills = try { ZerodhaClient(cfg).fetchTodayTrades() } catch (e: Exception) {
            Log.w(TAG, "capture failed", e); null
        }
        if (fills == null) {
            // NOT AN EMPTY DAY. The broker did not answer, and recording that as
            // "no trades" would be a lie the P&L total would then repeat.
            save(ctx, cur.copy(lastNote = "The broker did not answer the trade list on the last try."))
            return 0
        }
        val known = cur.rows.map { it.tradeId }.toHashSet()
        val fresh = fills.filter { it.tradeId.isNotBlank() && it.tradeId !in known }
            .map { f ->
                Row(tradeId = f.tradeId, orderId = f.orderId, symbol = f.symbol,
                    exchange = f.exchange, side = f.side, qty = f.qty, price = f.price,
                    filledAt = f.filledAt, product = f.product, seenAt = now)
            }
        val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
        save(ctx, cur.copy(
            rows = cur.rows + fresh,
            firstSeenDay = cur.firstSeenDay.ifBlank { today },
            lastCaptureAt = now,
            lastNote = if (fills.isEmpty()) "No fills reported for today."
                       else "${fills.size} fill(s) today, ${fresh.size} new."))
        if (fresh.isNotEmpty())
            Log.i(TAG, "captured ${fresh.size} new Zerodha fill(s); ${cur.rows.size + fresh.size} held")
        return fresh.size
    }

    /** Everything held, translated for the shared round-trip matcher. */
    fun fillsForMatching(ctx: Context): List<com.signalscope.app.trading.ClosedTrades.Fill> =
        load(ctx).rows
            // DELIVERY ONLY. An intraday leg opens and closes the same day and is
            // a different instrument in every way that matters here; pairing one
            // against a delivery holding would invent a round trip nobody made.
            .filter { it.product.isBlank() || it.product.equals("CNC", true) }
            .map {
                com.signalscope.app.trading.ClosedTrades.Fill(
                    ticker = it.symbol, symbol = it.symbol,
                    qty = it.qty, price = it.price,
                    // Kite reports no cash figure per fill, and the account settles
                    // in the instrument's own currency anyway — so price times
                    // quantity IS the cash, with none of the conversion ambiguity
                    // that makes the Trading 212 side check its own arithmetic.
                    cash = it.qty * it.price,
                    side = it.side,
                    at = isoOf(it.filledAt))
            }

    /**
     * "2026-09-17 10:04:11" (IST) → "2026-09-17T04:34:11".
     *
     * The matcher sorts on this string and measures holding periods from it, so a
     * mixed format would silently reorder trades. Converted to UTC rather than
     * left in IST so a row can be compared with every other timestamp the app
     * keeps, all of which are UTC.
     */
    internal fun isoOf(kite: String): String = try {
        val t = kite.trim().replace(' ', 'T')
        if (t.length < 19) t else {
            val ist = java.time.LocalDateTime.parse(t.take(19))
            // FORMATTED EXPLICITLY, NEVER toString(). Kotlin's own rendering drops
            // a zero seconds field — "…T03:45" rather than "…T03:45:00" — and the
            // round-trip matcher parses these back to measure how long a position
            // was held. A 16-character stamp fails that parse, the failure is
            // swallowed, and every trade that happened to fill on the minute
            // silently reports as held for zero days.
            ist.minusHours(5).minusMinutes(30)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
        }
    } catch (e: Exception) { kite.trim().replace(' ', 'T') }
}

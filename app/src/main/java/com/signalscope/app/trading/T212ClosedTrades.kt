package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.network.Trading212Client
import java.io.File

// ════════════════════════════════════════════════════════════════════════════
// WHAT THE ACCOUNT ACTUALLY MADE — closed round trips, built from real fills
// ════════════════════════════════════════════════════════════════════════════
// Trading 212 tells you when something was bought and when something was sold.
// It does not tell you what any of it EARNED: there is no list of completed
// round trips and no per-trade P&L, so the one question a trader asks at the end
// of a month — which of these actually worked — has no answer in the app.
//
// This builds that answer from the executed-order history, which is the only
// record of what really happened. Nothing here is derived from the strategy's
// opinion of a trade; a signal price is what the app WANTED, and this file is
// exclusively about what the broker DID.
//
// ── ONLY CLOSED QUANTITY COUNTS ─────────────────────────────────────────────
// Buy 100, sell 99, and the profit is on 99 shares. The hundredth is still an
// open position with an unrealised mark, and folding it in would report money
// that has not been made. So sells are matched against buys share by share, and
// whatever is left unsold stays out of every total on this page.
//
// ── FIRST IN, FIRST OUT ─────────────────────────────────────────────────────
// A sell consumes the oldest shares still held. That is the convention nearly
// every broker and tax authority uses, so the numbers here agree with the ones
// the user will see elsewhere. The alternative — averaging every buy together —
// hides exactly the thing worth seeing: that the early entry made money and the
// one chased later did not.
//
// ── CASH, NOT PRICES ────────────────────────────────────────────────────────
// P&L is computed from the cash each leg actually moved, prorated to the shares
// consumed, NOT from price × quantity. On an account whose currency differs from
// the instrument's, those are different numbers — a $100 fill is about €86 — and
// this app has already once recorded a per-share price in the wrong currency and
// put a stop 17% away from where it belonged. Cash is what left the account and
// cash is what came back.
// ════════════════════════════════════════════════════════════════════════════

object T212ClosedTrades {

    private const val TAG = "T212ClosedTrades"
    /** The pre-split file, holding both markets in one report. Removed by the next history read. */
    private const val LEGACY_FILE = "t212_closed_trades.json"
    private fun fileFor(space: String) = "t212_closed_trades_${space.lowercase()}.json"
    private val gson = Gson()

    /** The two spaces one Trading 212 account is shown as. */
    val SPACES = listOf("US", "DE")

    /**
     * Which space a closed trade belongs to: "DE" for Xetra, "US" for the rest.
     *
     * ONE ACCOUNT, TWO TABS. Trading 212 keeps German and US shares in a single
     * account and a single order history, but the app shows them as separate
     * markets — so the history is split before it is matched, and each tab totals
     * only its own trades. Matching is per ticker anyway, so splitting first
     * changes no round trip, only which total it lands in.
     *
     * Uses the same venue rule the feed lookup does, so a stock can never be
     * German on one screen and American on another.
     */
    fun spaceOf(ticker: String): String =
        if (Trading212Client.wantedFeedSuffix(ticker) == ".DE") "DE" else "US"

    // THE SHAPES AND THE MATCHING NOW LIVE IN [ClosedTrades], shared with India.
    // Kept reachable under the old names so nothing that reads a report has to
    // care where the engine moved to.
    /** "SAPd_EQ" → "SAP". The suffix is Trading 212's, not the market's. */
    private fun bare(ticker: String): String =
        ticker.substringBefore("_").removeSuffix("d").removeSuffix("l").ifBlank { ticker }

    /**
     * Translate Trading 212's fills into neutral ones and hand them to the shared
     * matcher. The only thing that is genuinely T212's here is how a ticker is
     * spelled — everything else about pairing a sell to its buys is the same
     * question in every market.
     */
    fun match(fills: List<Trading212Client.HistoricalFill>): Pair<List<ClosedTrades.RoundTrip>, ClosedTrades.Report> =
        ClosedTrades.match(fills.map {
            ClosedTrades.Fill(
                ticker = it.ticker, symbol = bare(it.ticker),
                qty = it.filledQuantity, price = it.fillPrice, cash = it.filledValue,
                side = it.side, at = it.dateExecuted)
        })

    /** Kept as a door onto the shared rule so the tests that pin its thresholds —
     *  the 203.66 case among them — go on testing the code that actually runs. */
    fun cashPerShare(cash: Double, qty: Double, price: Double): Double =
        ClosedTrades.cashPerShare(cash, qty, price)

    // ── THE FILL ARCHIVE ────────────────────────────────────────────────────
    // Every executed order ever read, kept on the phone and only ever added to.
    //
    // WHY IT EXISTS. The report used to be rebuilt from scratch on every read, from
    // whatever that one walk through Trading 212's history managed to fetch — and
    // the walk could stop early: on a page made entirely of cancelled ladder rungs
    // (an evening of unfilled sell attempts fills several), or on the rate limit.
    // A short walk then overwrote a full report, and months of trades vanished
    // from both tabs at once. The broker still had them; the app had forgotten.
    //
    // Now a read only ADDS to this archive, and the report is matched from the
    // archive — so a read that comes back short can delay new trades appearing,
    // but can never take old ones away. Later reads stop as soon as they reach
    // orders already saved, so after the first full rebuild they are one or two
    // pages, not the whole account.
    private const val ARCHIVE_FILE = "t212_fill_archive.json"
    /** Pages one read may walk. Trading 212 serves about six a minute, so this is
     *  roughly ten minutes at worst; a longer history carries on next read. */
    private const val MAX_PAGES_PER_READ = 60
    private val lock = java.util.concurrent.locks.ReentrantLock()

    data class Archive(
        val fills: List<Trading212Client.HistoricalFill> = emptyList(),
        /** True once a read has reached the account's very first order. */
        val complete: Boolean = false,
        /** Where the first full read stopped, so the next one carries on from there. */
        val resumeCursor: String? = null,
        /** When the last read that joined up with what was already saved began.
         *  Everything executed before this is in the archive (back to where the
         *  first full read has reached). 0 = nothing read yet. */
        val syncedAt: Long = 0L
    )

    private fun keyOf(f: Trading212Client.HistoricalFill): String =
        if (f.orderId > 0L) "o${f.orderId}" else "${f.ticker}|${f.dateExecuted}|${f.side}|${f.filledQuantity}"

    private fun epochOf(iso: String): Long = try { java.time.Instant.parse(iso).toEpochMilli() }
        catch (_: Exception) {
            try { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() } catch (_: Exception) { 0L }
        }

    fun loadArchive(ctx: Context): Archive = try {
        val f = File(ctx.filesDir, ARCHIVE_FILE)
        if (!f.exists()) Archive()
        else gson.fromJson(f.readText(), Archive::class.java)?.let {
            // Gson leaves a missing list null whatever Kotlin believes.
            @Suppress("SENSELESS_COMPARISON")
            if (it.fills == null) it.copy(fills = emptyList()) else it
        } ?: Archive()
    } catch (e: Exception) { Log.w(TAG, "fill archive unreadable", e); Archive() }

    private fun saveArchive(ctx: Context, a: Archive) {
        try {
            val f = File(ctx.filesDir, ARCHIVE_FILE)
            val tmp = File(ctx.filesDir, "$ARCHIVE_FILE.tmp")
            tmp.writeText(gson.toJson(a))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) { Log.w(TAG, "fill archive save failed", e) }
    }

    /**
     * Bring the archive up to date. Two walks, newest first as Trading 212 serves:
     *  1. the newest orders, down to the first one already saved by a read that
     *     joined up — everything older than that is already here;
     *  2. while the very first full read has not yet reached the account's first
     *     order, carry on from where it stopped.
     * Saved after every page, so even a read cut off half way keeps what it got.
     * @return the archive, and a note when the read stopped short.
     */
    private fun sync(ctx: Context, client: Trading212Client): Pair<Archive, String> =
        walk(loadArchive(ctx), { client.historyPageRetrying(it) }, System.currentTimeMillis(),
            pauseMs = 400L) { saveArchive(ctx, it) }.also { (a, note) ->
            Log.i(TAG, "history read: ${a.fills.size} executed orders saved" +
                (if (a.complete) ", back to the first order" else ", first order not reached yet") +
                (if (note.isNotBlank()) " — $note" else ""))
        }

    /** [sync] without the phone or the network: [fetch] serves pages, [persist] keeps the archive. */
    internal fun walk(
        start0: Archive,
        fetch: (String?) -> Trading212Client.HistoryPage,
        start: Long,
        pauseMs: Long = 0L,
        maxPages: Int = MAX_PAGES_PER_READ,
        persist: (Archive) -> Unit
    ): Pair<Archive, String> {
        var a = start0
        val byKey = LinkedHashMap<String, Trading212Client.HistoricalFill>()
        a.fills.forEach { byKey[keyOf(it)] = it }
        val knownBefore = HashSet(byKey.keys)
        val syncedBefore = a.syncedAt
        var pages = 0
        var note = ""
        fun save(complete: Boolean = a.complete, resume: String? = a.resumeCursor, synced: Long = a.syncedAt) {
            a = Archive(byKey.values.sortedBy { it.dateExecuted }, complete, resume, synced)
            persist(a)
        }
        fun pause() = try { if (pauseMs > 0) Thread.sleep(pauseMs); true } catch (_: InterruptedException) { false }
        fun stopped(code: Int, what: String) =
            "Trading 212 stopped answering part way (${if (code == 429) "too many requests" else "code $code"}) — $what"

        // 1. THE NEWEST ORDERS.
        if (syncedBefore > 0L) {
            var cursor: String? = null
            val seen = HashSet<String>()
            while (true) {
                if (pages >= maxPages) { note = "The newest orders are still being read — the rest arrives with the next read."; break }
                val page = fetch(cursor)
                if (page.code != 200) { note = stopped(page.code, "showing what is saved; the next read carries on."); break }
                pages++
                page.fills.forEach { byKey[keyOf(it)] = it }
                val joined = page.fills.any { keyOf(it) in knownBefore && epochOf(it.dateExecuted) <= syncedBefore }
                val next = page.next
                val end = page.items == 0 || next.isNullOrBlank() || !seen.add(next)
                if (end) { save(complete = true, resume = null, synced = start); break }
                if (joined) { save(synced = start); break }
                save()
                cursor = next
                if (!pause()) break
            }
        }

        // 2. THE OLDER ORDERS a first full read has not reached yet.
        if (!a.complete && note.isBlank()) {
            var cursor: String? = if (a.syncedAt == 0L) null else a.resumeCursor
            val seen = HashSet<String>()
            while (true) {
                if (pages >= maxPages) { note = "Older history is still being read — it carries on with the next read."; break }
                val page = fetch(cursor)
                if (page.code != 200) { note = stopped(page.code, "older trades are still to come; the next read carries on."); break }
                pages++
                page.fills.forEach { byKey[keyOf(it)] = it }
                // The first page of a first read joins the archive up to "now".
                val synced = if (a.syncedAt == 0L) start else a.syncedAt
                val next = page.next
                val end = page.items == 0 || next.isNullOrBlank() || !seen.add(next)
                if (end) { save(complete = true, resume = null, synced = synced); break }
                save(resume = next, synced = synced)
                cursor = next
                if (!pause()) break
            }
        }
        return a to note
    }

    /**
     * Read what is new, add it to the archive, split by market, match each, cache
     * each. Network — call it off the main thread. Returns [space]'s report.
     */
    fun run(ctx: Context, space: String): ClosedTrades.Report {
        val config = ConfigManager(ctx)
        if (!config.hasT212Credentials)
            return storeAll(ctx, ClosedTrades.Report(error = "No Trading 212 API key saved."))
        // One account, two tabs: a second read while the first walks would only
        // double the requests and trip the rate limit for both.
        if (!lock.tryLock()) return last(ctx, space)
            .copy(error = "A history read is already running — this list updates when it finishes.")
        try {
            val (archive, note) = sync(ctx, Trading212Client(config))
            val fills = archive.fills
            if (fills.isEmpty()) {
                // Nothing saved at all. A failed read must not wipe a report that
                // still has trades in it.
                if (note.isNotBlank()) return keepOrStore(ctx, space, note)
                return storeAll(ctx, ClosedTrades.Report(ok = true, checkedAt = System.currentTimeMillis(),
                    error = "The history endpoint answered but reported no executed orders."))
            }
            // COSTED BY MARKET. Trading 212's only per-trade charge is its 0.15%
            // conversion fee, paid on each leg when the stock's currency is not the
            // account's — US shares on a euro account pay it both ways, German shares
            // on a euro account pay nothing. The pairing of market and account
            // currency is exactly what [TradingCosts] already decides, so the closed
            // trades are costed through it rather than by a second copy of the rule.
            val bySpace = SPACES.associateWith { sp ->
                val (_, rep) = match(fills.filter { spaceOf(it.ticker) == sp })
                ClosedTrades.costed(rep, sp.lowercase(), config.accountCurrency)
                    .copy(currency = config.accountCurrency, error = note).also { store(ctx, sp, it) }
            }
            File(ctx.filesDir, LEGACY_FILE).delete()
            Log.i(TAG, "closed-trade scan: ${fills.size} fills → " + bySpace.entries.joinToString { (sp, r) ->
                "$sp ${r.trips.size} round trips, net ${"%.2f".format(r.totalPnl)} ${r.currency}" })
            return bySpace.getValue(space.uppercase().takeIf { it in SPACES } ?: "US")
        } finally { lock.unlock() }
    }

    /** A failed read with nothing archived: a tab whose report still has trades
     *  keeps it; only a tab with nothing to lose stores the failure. */
    private fun keepOrStore(ctx: Context, space: String, note: String): ClosedTrades.Report {
        SPACES.forEach { sp ->
            if (last(ctx, sp).trips.isEmpty()) store(ctx, sp, ClosedTrades.Report(error = note))
        }
        return last(ctx, space).let { if (it.trips.isNotEmpty()) it.copy(error = note) else it }
    }

    /** The last report for [space], for a panel that must render without hitting the network. */
    fun last(ctx: Context, space: String): ClosedTrades.Report = try {
        val f = File(ctx.filesDir, fileFor(space))
        if (!f.exists()) ClosedTrades.Report(error = "not scanned yet")
        else {
            val r = gson.fromJson(f.readText(), ClosedTrades.Report::class.java)
                ?: ClosedTrades.Report(error = "unreadable")
            // Costed on reading too, so a report saved before costing existed
            // shows its fees without waiting for the next history read.
            if (r.ok && r.trips.isNotEmpty())
                ClosedTrades.costed(r, space.lowercase(), ConfigManager(ctx).accountCurrency)
            else r
        }
    } catch (e: Exception) { ClosedTrades.Report(error = e.message ?: "unreadable") }

    /** An account-wide outcome (no key, fetch failed) is the same answer on both tabs. */
    private fun storeAll(ctx: Context, r: ClosedTrades.Report): ClosedTrades.Report {
        SPACES.forEach { store(ctx, it, r) }
        return r
    }

    private fun store(ctx: Context, space: String, r: ClosedTrades.Report) {
        try { File(ctx.filesDir, fileFor(space)).writeText(gson.toJson(r)) }
        catch (e: Exception) { Log.w(TAG, "closed-trade store failed", e) }
    }
}

package com.signalscope.app.trading

import android.content.Context
import com.signalscope.app.data.FloorAlgoStore
import com.signalscope.app.data.FloorAlgoStore.Algo
import com.signalscope.app.data.FloorAlgoStore.Ask
import com.signalscope.app.data.FloorAlgoStore.Trade
import java.time.LocalDate

/**
 * ASKED BEFORE EVERY PAPER BUY — the two AI-outlook low-buy algos (the user's
 * design, 31 Oct 2026), so paper works the way real money will.
 *
 * When "Ai low buy-algo exit" (a monthly-low chain) or "AI low + MACD turns" (MACD
 * turns on the monthly-low list) would buy, nothing is bought. A request is kept,
 * and a notification with the stock, the AI verdict and a chart asks Buy or Skip:
 *  • BUY — the paper trade fills at the first price AFTER the tap: the next
 *    fifteen-minute bar opening at or after it, not the price the signal saw.
 *  • SKIP, or no answer within [WAIT_MS] — nothing is bought; the stock is followed
 *    on the side from the signal, marked "skipped by you" or "no answer", so the
 *    record compares your choice with the algo's.
 *  • A tap that arrives with the market shut lapses the same way.
 * Sales never ask: the stop, the target and the algo's own exits act alone.
 *
 * Taps are queued here at once ([queue]) and settled under the engine's lock
 * ([FloorAlgoEngine.answer]) or by the next pass — always with the tap's own time.
 */
object FloorAsks {
    const val WAIT_MS = 30 * 60_000L

    const val WAITING = "WAITING"
    const val BUY = "BUY"
    const val SKIP = "SKIP"
    const val NO_ANSWER = "NO_ANSWER"
    const val LATE = "LATE"

    const val TURN = "TURN"
    const val CHAIN_A = "CHAIN_A"
    const val CHAIN_B = "CHAIN_B"

    /** The algos that ask: every monthly-low chain, and MACD turns on the monthly-low list. */
    fun asks(a: Algo): Boolean = a.kind == FloorAlgoStore.KIND_CHAIN ||
        (a.kind == FloorAlgoStore.KIND_MACD && a.settings.universeMode == FloorAlgoStore.UNI_MONTHLY_LOW)

    fun raise(a: Algo, ask: Ask): Algo =
        a.copy(asks = (a.asks.filterNot { it.id == ask.id } + ask).takeLast(100))

    /** Requests still waiting that can no longer be answered in time. */
    fun due(a: Algo, nowMs: Long, sessionOpen: Boolean): List<Ask> =
        a.asks.filter { it.status == WAITING && (!sessionOpen || nowMs - it.askedMs > WAIT_MS) }

    /** What a tap at [tapMs] counts as: lapsed if the market was shut or the half hour had passed. */
    fun answerAt(ask: Ask, buy: Boolean, tapMs: Long, sessionOpen: Boolean): String = when {
        !sessionOpen -> LATE
        tapMs - ask.askedMs > WAIT_MS -> NO_ANSWER
        buy -> BUY
        else -> SKIP
    }

    fun followWhy(answer: String): String = when (answer) {
        SKIP -> "skipped by you"
        LATE -> "answered after the close"
        else -> "no answer"
    }

    /**
     * Settle one request. @return the algo after it, and a sentence for the log;
     * the algo unchanged when the request is not waiting any more.
     */
    fun resolve(a: Algo, askId: String, answer: String, nowMs: Long, today: LocalDate): Pair<Algo, String> {
        val ask = a.asks.firstOrNull { it.id == askId && it.status == WAITING } ?: return a to ""
        val nowSec = nowMs / 1000L
        var out = a.copy(asks = a.asks.map { if (it.id == askId) it.copy(status = answer, answeredMs = nowMs) else it })
        var why: String? = when {
            answer != BUY -> followWhy(answer)
            !a.enabled -> "algo paused"
            else -> null
        }
        if (ask.kind == TURN && why == null) {
            val spans = out.trades.filter { it.status != "MISSED" }
                .map { LocalDate.parse(it.day) to it.sellDay?.let { d -> LocalDate.parse(d) } }
            if (FloorOrders.seatsBusy(spans, today) >= a.settings.seats) why = "every seat taken"
        }
        val chain = if (ask.kind == TURN) null else out.chains.firstOrNull { it.id == ask.chainId && it.state != "ENDED" }
        if (ask.kind != TURN && chain == null) return out to "${ask.symbol}: its chain has ended — nothing bought"
        val t = Trade(id = ask.id, symbol = ask.symbol, name = ask.name, day = ask.day,
            signalSec = if (why == null) nowSec else ask.signalSec,
            fromSec = if (why == null) ChainRules.ceil15(nowSec) else ask.fromSec,
            status = if (why == null) "OPEN" else "MISSED",
            verdict = ask.verdict.ifBlank { null }, followWhy = why)
        out = out.copy(trades = out.trades.filterNot { it.id == t.id } + t)
        if (chain != null) {
            val mark = if (why == null) "OPEN" else "SKIP"
            var c = if (ask.kind == CHAIN_A) chain.copy(dots = mark, state = "HOLDING")
                    else chain.copy(champ = mark, state = "HOLDING")
            c = recount(c, out.trades)
            out = out.copy(chains = out.chains.map { if (it.id == c.id) c else it })
        }
        return out to (if (why == null) "${ask.symbol}: bought on your tap — fills at the next fifteen-minute price"
                       else "${ask.symbol}: not bought (${why}) — followed on the side")
    }

    /**
     * A counted chain keeps its seat while a half is still to come or one was
     * bought; with every half skipped or void and nothing bought, the seat is freed
     * and the chain followed on the side.
     */
    fun recount(c: FloorAlgoStore.Chain, trades: List<Trade>): FloorAlgoStore.Chain {
        if (!c.counted) return c
        val waiting = c.dots in setOf("", "ASK") || c.champ in setOf("", "ASK")
        val bought = trades.any { it.id.startsWith(c.id + "|") && (it.status == "OPEN" || it.status == "SOLD") }
        return if (waiting || bought) c else c.copy(counted = false, why = "skipped by you")
    }

    // ── TAPS, QUEUED THE MOMENT THEY ARRIVE ──────────────────────────────────

    private const val PREFS = "floor_ask_answers"

    /** Kept until settled, so a tap is never lost to a pass holding the lock. */
    fun queue(ctx: Context, algoId: String, askId: String, buy: Boolean, tapMs: Long) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("$algoId|$askId", (if (buy) BUY else SKIP) + "|" + tapMs).commit()
    }

    /** Taps waiting for [algoId]: ask id to (buy, tap time). */
    fun queued(ctx: Context, algoId: String): Map<String, Pair<Boolean, Long>> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.mapNotNull { (k, v) ->
            if (!k.startsWith("$algoId|")) return@mapNotNull null
            val p = (v as? String)?.split("|")?.takeIf { it.size == 2 } ?: return@mapNotNull null
            val ms = p[1].toLongOrNull() ?: return@mapNotNull null
            k.substringAfter("|") to ((p[0] == BUY) to ms)
        }.toMap()

    fun unqueue(ctx: Context, algoId: String, askId: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("$algoId|$askId").commit()
    }
}

package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

/**
 * WHY A PAPER ENTRY DID NOT BECOME A REAL ORDER.
 *
 * Every gate in the live path returns early and silently, which is correct
 * behaviour — a paper-only algo must not spam logs or errors for doing exactly
 * what it was told. But it left one question unanswerable from the app: a
 * position sits open on paper, nothing exists at the broker, and the card says
 * "no broker orders from this algo yet". That sentence is true for a frozen
 * switch, an unarmed algo, a name outside the allowlist and a stale signal
 * alike, and those need completely different responses.
 *
 * A REFUSAL is already recorded — the live ledger gets an error row when the
 * broker says no. What was missing is the DECLINE: the cases where the app
 * decided not to ask at all. Those never produce a row, so they need their own
 * home.
 *
 * Deliberately small and lossy: newest few hundred, no history. This answers
 * "why is this position not mirrored right now", not "what happened last month".
 */
object LiveSkipStore {

    private const val TAG = "LiveSkipStore"
    private const val FILE = "live_skip_reasons.json"
    private const val MAX = 300
    private val gson = Gson()

    data class Skip(
        val ns: String = "",
        val paperTradeId: String = "",
        val symbol: String = "",
        /** One line, in the user's terms, saying what to do about it. */
        val reason: String = "",
        val at: Long = 0L
    )

    private data class State(val skips: List<Skip> = emptyList())

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    private fun load(ctx: Context): State = try {
        val f = file(ctx)
        if (!f.exists()) State() else gson.fromJson(f.readText(), State::class.java) ?: State()
    } catch (e: Exception) { Log.w(TAG, "load failed: ${e.message}"); State() }

    /**
     * Record why this trade was not mirrored. One entry per trade — a later pass
     * overwrites an earlier one, because the reason can change (the freeze lifts,
     * the signal goes stale) and only the current answer is useful.
     */
    @Synchronized
    fun note(ctx: Context, ns: String, paperTradeId: String, symbol: String, reason: String) {
        try {
            val cur = load(ctx).skips.filterNot { it.ns == ns && it.paperTradeId == paperTradeId }
            val next = (cur + Skip(ns, paperTradeId, symbol, reason, System.currentTimeMillis()))
                .takeLast(MAX)
            file(ctx).writeText(gson.toJson(State(next)))
        } catch (e: Exception) { Log.w(TAG, "note failed: ${e.message}") }
    }

    /** Forget it — the trade mirrored after all, or is gone. */
    @Synchronized
    fun clear(ctx: Context, ns: String, paperTradeId: String) {
        try {
            val cur = load(ctx).skips
            val next = cur.filterNot { it.ns == ns && it.paperTradeId == paperTradeId }
            if (next.size != cur.size) file(ctx).writeText(gson.toJson(State(next)))
        } catch (e: Exception) { Log.w(TAG, "clear failed: ${e.message}") }
    }

    /** Reasons for one algo, keyed by paper trade id, for the card to render. */
    fun forNs(ctx: Context, ns: String): Map<String, String> =
        load(ctx).skips.filter { it.ns == ns }.associate { it.paperTradeId to it.reason }
}

package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

// ════════════════════════════════════════════════════════════════════════════
// WHAT-IF SUMMARIES — last computed replay result, per algo
// ════════════════════════════════════════════════════════════════════════════
// The replay itself is expensive (it fetches a whole universe), so its result is
// worth keeping. Without this, comparing algos means opening each one, pressing
// the bulb, waiting a minute or two, memorising the number, and moving on — by
// which point the first figure has been forgotten.
//
// Everything needed to make a fair comparison is stored ALONGSIDE the return,
// not just the return: the slippage it assumed, the frames, and the window it
// covered. A +40% over 600 days at 0.20% slippage and a +40% over 20 days at 0%
// are not the same claim, and a bare percentage cannot tell them apart.
//
// One file for all algos (the list is small); keyed by namespace.
// ════════════════════════════════════════════════════════════════════════════

object WhatIfStore {

    private const val TAG = "WhatIfStore"
    private val gson = Gson()

    data class Summary(
        val ns: String = "",
        val name: String = "",
        val computedAt: Long = 0L,
        /** "portfolio_3b" = the real strategy; "engine_only" = signals averaged. */
        val layer: String = "",
        val configSource: String = "",
        // ── the interpretation context, deliberately stored with the number ──
        val slippagePct: Double = 0.0,
        val entryTf: String = "",
        val exitTf: String = "",
        val interval: String = "",
        // ── the result ──
        val compoundedPct: Double = 0.0,
        val cagrPct: Double = 0.0,
        val maxDrawdownPct: Double = 0.0,
        val spanDays: Double = 0.0,
        val fromTs: Long = 0L,
        val toTs: Long = 0L,
        val closed: Int = 0,
        val symbols: Int = 0,
        val seats: Int = 0,
        val note: String = ""
    )

    private data class State(val items: Map<String, Summary> = emptyMap())

    private fun file(ctx: Context): File = File(ctx.filesDir, "whatif_summaries.json")

    private fun load(ctx: Context): State = try {
        val f = file(ctx)
        if (!f.exists()) State() else gson.fromJson(f.readText(), State::class.java) ?: State()
    } catch (e: Exception) {
        Log.w(TAG, "load failed: ${e.message}"); State()
    }

    @Synchronized
    fun put(ctx: Context, s: Summary) {
        try {
            val cur = load(ctx).items.toMutableMap()
            cur[s.ns] = s
            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(gson.toJson(State(cur)))
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
        }
    }

    fun all(ctx: Context): Map<String, Summary> = load(ctx).items

    fun get(ctx: Context, ns: String): Summary? = load(ctx).items[ns]

    /** Drop an algo's cached result — used when its config changes, since the old
     *  number was produced by a different strategy and would mislead. */
    @Synchronized
    fun remove(ctx: Context, ns: String) {
        try {
            val cur = load(ctx).items.toMutableMap()
            if (cur.remove(ns) != null) file(ctx).writeText(gson.toJson(State(cur)))
        } catch (e: Exception) {
            Log.w(TAG, "remove failed: ${e.message}")
        }
    }
}

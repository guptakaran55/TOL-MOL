package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Small persistent queue of live-buy interests.
 *
 * The user arms a symbol with a quantity from Discovery, Setups, or Portfolio. While live
 * prices are enabled, the live MACD tracker can place one buy order when the
 * 1H/2H/4H intraday stack turns up. Entries are single-fire by design.
 */
object LiveBuyTrackerStore {
    private const val TAG = "LiveBuyTrackerStore"
    private val gson = Gson()

    data class Entry(
        val symbol: String,
        val exchange: String = "NSE",
        val quantity: Int,
        val armedAt: Long = System.currentTimeMillis(),
        val status: String = "ARMED",
        val lastMessage: String = "Waiting for 1H/2H/4H MACD hook",
        val lastPrice: Double = 0.0,
        val orderId: String? = null,
        val firedAt: Long? = null
    )

    private fun file(ctx: Context): File = File(ctx.filesDir, "live_buy_trackers.json")

    fun load(ctx: Context): List<Entry> = synchronized(this) {
        try {
            val f = file(ctx)
            if (!f.exists()) return@synchronized emptyList()
            val type = object : TypeToken<List<Entry>>() {}.type
            gson.fromJson<List<Entry>>(f.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load live buy trackers", e)
            emptyList()
        }
    }

    fun arm(ctx: Context, symbol: String, exchange: String, quantity: Int): List<Entry> = synchronized(this) {
        val cleanSymbol = symbol.trim().uppercase()
        val cleanExchange = exchange.trim().uppercase().ifBlank { "NSE" }
        val next = load(ctx).filterNot {
            it.symbol.equals(cleanSymbol, ignoreCase = true) && (it.status == "ARMED" || it.status == "FIRING")
        }.toMutableList()
        next.add(Entry(symbol = cleanSymbol, exchange = cleanExchange, quantity = quantity.coerceAtLeast(1)))
        saveAll(ctx, next)
        next
    }

    fun cancel(ctx: Context, symbol: String): List<Entry> = synchronized(this) {
        val next = load(ctx).filterNot {
            it.symbol.equals(symbol, ignoreCase = true) && it.status == "ARMED"
        }
        saveAll(ctx, next)
        next
    }

    fun updateStatus(
        ctx: Context,
        symbol: String,
        status: String,
        message: String,
        lastPrice: Double,
        orderId: String? = null,
        firedAt: Long? = null
    ): List<Entry> = synchronized(this) {
        val next = load(ctx).map { entry ->
            if (!entry.symbol.equals(symbol, ignoreCase = true) ||
                (entry.status != "ARMED" && entry.status != "FIRING")
            ) {
                entry
            } else {
                entry.copy(
                    status = status,
                    lastMessage = message,
                    lastPrice = lastPrice,
                    orderId = orderId ?: entry.orderId,
                    firedAt = firedAt ?: entry.firedAt
                )
            }
        }
        saveAll(ctx, next)
        next
    }

    private fun saveAll(ctx: Context, list: List<Entry>) {
        try {
            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(gson.toJson(list))
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save live buy trackers", e)
        }
    }
}

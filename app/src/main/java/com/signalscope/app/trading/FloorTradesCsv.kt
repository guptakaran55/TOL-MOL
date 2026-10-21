package com.signalscope.app.trading

import com.signalscope.app.data.FloorAlgoStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Every trade of a floor, MACD-turn, campaign or chain algo as one CSV row — the
 * result BEFORE the broker's charges and the spread as well as after, with each
 * cost in its own column, so a market's fees can be taken out again when the
 * results are compared across markets.
 *
 * Sold trades are at their sale price. Held trades are at the latest price, with
 * the charges a sale there would pay. Missed trades (every seat taken when the
 * order filled) are followed for the record and marked as not counted.
 */
object FloorTradesCsv {

    const val HEADER = "algo,market,kind,currency,symbol,name,status,counted,bought,first_price,second_bought,second_price," +
        "halves,qty,money_in,avg_price,sold,price_out,exit_reason,days_held,value_out," +
        "pnl_before_charges,pct_before_charges,broker_charges,spread,pnl_after_charges,pct_after_charges"

    fun csv(a: FloorAlgoStore.Algo, nowSec: Long = System.currentTimeMillis() / 1000L): String {
        val zone = MarketHours.zoneOf(a.space) ?: ZoneId.of("UTC")
        val at = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
        fun time(sec: Long) = if (sec > 0L) at.format(Instant.ofEpochSecond(sec)) else ""
        fun n(v: Double, d: Int = 2) = if (!v.isFinite()) "" else String.format(Locale.US, "%.${d}f", v)
        fun q(v: String) = if (v.contains(',') || v.contains('"')) "\"" + v.replace("\"", "\"\"") + "\"" else v
        val sb = StringBuilder(HEADER).append('\n')
        // A MACD turn seen but never filled is a signal, not a trade.
        for (t in a.trades.filter { it.firstSec > 0L && it.qty > 0.0 && it.cost > 0.0 }.sortedBy { it.firstSec }) {
            val sold = t.status == "SOLD"
            val px = if (sold) t.sellPrice else t.lastPrice
            val outSec = if (sold) t.sellSec else maxOf(t.lastSec, nowSec)
            val value = t.qty * px
            val gross = if (px > 0.0) value - t.cost else Double.NaN
            val net = gross - t.fees - t.slip
            sb.append(listOf(q(a.name), a.space.uppercase(), a.kind, Spaces.currencyOf(a.space), t.symbol, q(t.name),
                t.status, if (t.status == "MISSED") "no" else "yes",
                time(t.firstSec), n(t.firstPrice, 4), time(t.secondSec), if (t.secondPrice > 0.0) n(t.secondPrice, 4) else "",
                t.halves.toString(), n(t.qty, 6), n(t.cost), n(t.cost / t.qty, 4),
                if (sold) time(t.sellSec) else "", if (px > 0.0) n(px, 4) else "",
                if (sold) t.exitReason else "held — at the latest price",
                n((outSec - t.firstSec) / 86_400.0, 1), if (px > 0.0) n(value) else "",
                n(gross), n(gross / t.cost * 100.0), n(t.fees), n(t.slip), n(net), n(net / t.cost * 100.0)
            ).joinToString(","))
            sb.append('\n')
        }
        return sb.toString()
    }
}

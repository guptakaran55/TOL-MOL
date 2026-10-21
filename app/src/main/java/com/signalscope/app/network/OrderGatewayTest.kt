package com.signalscope.app.network

import android.util.Log
import com.signalscope.app.data.ConfigManager

/**
 * A one-tap answer to "will Zerodha actually take my orders right now?"
 *
 * WHY THIS IS SAFE TO RUN WITH THE MARKET SHUT
 * Zerodha checks the whitelisted address at its gateway, before it looks at the
 * order at all. So a refusal that talks about MARKET HOURS, or FUNDS, or PRICE
 * BANDS is itself the proof we want: the request got through the gate, was
 * recognised as this account's, and was turned down on its own merits. Only a
 * refusal about permissions means the address is wrong.
 *
 * That inverts the usual problem. We do not need an order to succeed to learn
 * what we came to learn — we need to read WHICH kind of "no" comes back.
 *
 * WHY IT STILL PLACES A REAL ORDER
 * Because the only honest test of a live path is the live path. Two guards keep
 * that from costing anything:
 *
 *  - One share, priced 10% under the last trade. Well inside the circuit bands
 *    that would get it rejected as silly, nowhere near a price that fills.
 *  - Anything accepted is cancelled in the same breath, seconds later, before
 *    any exchange is awake to act on it. A cancel that fails is reported in
 *    capitals with the order id, never swallowed.
 */
object OrderGatewayTest {

    private const val TAG = "OrderGatewayTest"

    /** One share. This is a probe, not a position. */
    private const val QTY = 1
    /** Under the last trade: far enough not to fill, close enough not to be refused as absurd. */
    private const val DISCOUNT = 0.90

    data class Report(val lines: List<String>, val passed: Boolean) {
        fun asText(): String = lines.joinToString("\n")
    }

    /**
     * Blocking — call off the main thread. [symbol] is whatever the user typed;
     * nothing about this is tied to one company, and the default is only a
     * default.
     */
    fun run(config: ConfigManager, exchange: String = "NSE", symbol: String): Report {
        val out = ArrayList<String>()
        val sym = symbol.trim().uppercase()
        if (sym.isBlank()) return Report(listOf("Enter a symbol to test with."), false)
        if (!config.isZerodhaConnected) {
            return Report(listOf("Not connected to Zerodha — connect first."), false)
        }

        val client = ZerodhaClient(config)

        // ── 1. Where are we coming from? ──
        val expected = config.zerodhaWhitelistedIp.trim()
        val seen = ExitAddressCheck.currentAddress(force = true)
        out += when {
            expected.isBlank() && seen != null -> "Address: on $seen (no whitelist set here)"
            seen == null -> "Address: couldn't determine — no network?"
            seen.equals(expected, true) -> "Address: $seen — matches the whitelist ✅"
            else -> "Address: $seen — does NOT match $expected ❌"
        }

        // ── 2. What is it worth? ──
        val ltp = when (val r = client.fetchLtp(exchange, sym)) {
            is ZerodhaClient.LtpResult.Success -> r.lastPrice
            is ZerodhaClient.LtpResult.Expired -> {
                out += "Session expired while fetching the price — reconnect and retry."
                return Report(out, false)
            }
            is ZerodhaClient.LtpResult.Failure -> {
                out += "Couldn't fetch a price for $sym (${r.message}). Check the symbol."
                return Report(out, false)
            }
        }
        if (ltp <= 0.0) {
            out += "Zerodha returned no usable price for $sym."
            return Report(out, false)
        }
        val limit = ((ltp * DISCOUNT) * 20.0).toLong() / 20.0   // snap down to a 0.05 tick
        out += "$sym last traded at ${"%.2f".format(ltp)}; probing with $QTY share at ${"%.2f".format(limit)}"
        out += ""

        // ── 3. The two attempts ──
        var gatewayProven = false
        for (variety in listOf("regular", "amo")) {
            val label = if (variety == "regular") "Regular order" else "After-market order"
            when (val res = client.placeOrder(exchange, sym, "BUY", QTY, "LIMIT", limit, variety)) {
                is ZerodhaClient.OrderResult.Success -> {
                    gatewayProven = true
                    out += "$label: ACCEPTED (id ${res.orderId}) — cancelling it now"
                    when (val c = client.cancelOrder(res.orderId, variety)) {
                        is ZerodhaClient.OrderResult.Success ->
                            out += "   cancelled ✅ nothing left outstanding"
                        is ZerodhaClient.OrderResult.Failure -> {
                            out += "   ⚠️ COULD NOT CANCEL ${res.orderId} — ${c.message}"
                            out += "   ⚠️ CANCEL IT BY HAND IN THE KITE APP."
                            Log.e(TAG, "Test order ${res.orderId} could not be cancelled")
                        }
                    }
                }
                is ZerodhaClient.OrderResult.Failure -> {
                    out += "$label: refused — ${res.message}"
                    if (looksLikeGatewayRefusal(res.message)) {
                        out += "   → that reads like an ADDRESS or PERMISSION problem ❌"
                    } else {
                        gatewayProven = true
                        out += "   → refused on the order's own merits, so the gateway let us in ✅"
                    }
                }
            }
        }

        out += ""
        out += if (gatewayProven)
            "VERDICT: Zerodha is accepting instructions from this phone. ✅"
        else
            "VERDICT: nothing got past the gateway — check the tunnel and the whitelist. ❌"
        return Report(out, gatewayProven)
    }

    /**
     * Distinguishes "we don't like you" from "we don't like this order".
     *
     * Deliberately narrow: anything not recognised as a gateway complaint counts
     * as a business refusal, and therefore as proof we got in. A false "passed"
     * shows up the next time a real order is placed; a false "failed" would send
     * someone hunting a network fault that does not exist.
     */
    private fun looksLikeGatewayRefusal(message: String): Boolean {
        val m = message.lowercase()
        return listOf(
            "ip ", "ip address", "whitelist", "not permitted", "permission",
            "forbidden", "unauthor", "access denied", "blocked: this phone"
        ).any { m.contains(it) }
    }
}

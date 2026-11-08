package com.signalscope.app.network

import org.junit.Assert.assertEquals
import org.junit.Test

/** BOUGHT, NOT SETTLED (8 Nov 2026): a delivery buy counts as held before it reaches holdings. */
class ZerodhaDeliveryBuysTest {
    private val body = """{"status":"success","data":{"net":[
        {"tradingsymbol":"LTFOODS","exchange":"NSE","product":"CNC","quantity":13,"average_price":412.5,"last_price":405.0,"pnl":-97.5},
        {"tradingsymbol":"SUNDRMFAST","exchange":"NSE","product":"CNC","quantity":5,"average_price":0,"buy_price":1010.0,"last_price":1000.0},
        {"tradingsymbol":"INFY","exchange":"NSE","product":"MIS","quantity":10,"average_price":1500,"last_price":1490},
        {"tradingsymbol":"TCS","exchange":"NSE","product":"CNC","quantity":-3,"average_price":3000,"last_price":3010},
        {"tradingsymbol":"PFOCUS","exchange":"NSE","product":"CNC","quantity":2,"average_price":300,"last_price":290}],"day":[]}}"""

    @Test fun `delivery buys not yet in holdings become holdings rows`() {
        val rows = ZerodhaClient.parseDeliveryBuys(body, setOf("PFOCUS"))
        assertEquals(listOf("LTFOODS", "SUNDRMFAST"), rows.map { it.symbol })   // not intraday, not a sale, not already held
        assertEquals(13, rows[0].quantity)
        assertEquals(1010.0, rows[1].avgPrice, 1e-9)                              // falls back to the buy price
        assertEquals(-50.0, rows[1].pnl, 1e-9)
    }

    @Test fun `a refused reply adds nothing`() {
        assertEquals(0, ZerodhaClient.parseDeliveryBuys("""{"status":"error","message":"x"}""", emptySet()).size)
    }
}

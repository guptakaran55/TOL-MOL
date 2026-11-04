package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Test

/** WHAT IS REALLY YOURS TO SPEND — Trading 212's free cash less the app's promises. */
class SpendableTest {
    @Test fun `free cash less every promise`() {
        val parts = listOf(Spendable.Part("rv", 300.0, "AZN"), Spendable.Part("algos", 466.0, ""))
        assertEquals(1234.0, Spendable.yours(2000.0, parts), 1e-9)
    }

    @Test fun `nothing promised leaves all of it yours`() {
        assertEquals(500.0, Spendable.yours(500.0, emptyList()), 1e-9)
    }

    @Test fun `more promised than is free goes below zero, and says so`() {
        assertEquals(-50.0, Spendable.yours(100.0, listOf(Spendable.Part("rv", 150.0, ""))), 1e-9)
    }
}

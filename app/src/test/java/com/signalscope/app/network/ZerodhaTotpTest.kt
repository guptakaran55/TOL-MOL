package com.signalscope.app.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The second-factor code is the one part of the unattended login with no safety
 * net: Kite answers a wrong code exactly the way it answers a wrong password, and
 * the app treats that as terminal for the day to avoid locking the account. A
 * quietly broken generator would therefore look like "your credentials are wrong"
 * every single morning.
 *
 * These are the published RFC 6238 vectors (SHA-1, seed "12345678901234567890"
 * as base32), truncated to the 6 digits Kite issues.
 */
class ZerodhaTotpTest {

    private val seed = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    @Test
    fun `matches RFC 6238 reference vectors`() {
        assertEquals("287082", ZerodhaAutoLogin.totpNow(seed, 59L))
        assertEquals("081804", ZerodhaAutoLogin.totpNow(seed, 1111111109L))
        assertEquals("050471", ZerodhaAutoLogin.totpNow(seed, 1111111111L))
        assertEquals("005924", ZerodhaAutoLogin.totpNow(seed, 1234567890L))
        assertEquals("279037", ZerodhaAutoLogin.totpNow(seed, 2000000000L))
        assertEquals("353130", ZerodhaAutoLogin.totpNow(seed, 20000000000L))
    }

    @Test
    fun `tolerates the spacing and padding authenticator apps show`() {
        val prettified = "gezd gnbv gy3t qojq gezd gnbv gy3t qojq=="
        assertEquals(
            ZerodhaAutoLogin.totpNow(seed, 1111111111L),
            ZerodhaAutoLogin.totpNow(prettified, 1111111111L)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a seed that isn't base32`() {
        // A mistyped seed must fail loudly here rather than silently produce a
        // wrong code that Kite reads as a bad credential.
        ZerodhaAutoLogin.totpNow("not-a-valid-seed!", 59L)
    }
}

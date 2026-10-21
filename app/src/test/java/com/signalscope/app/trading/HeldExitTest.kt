package com.signalscope.app.trading

import com.signalscope.app.trading.ZerodhaLiveMirror.ExitPath
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AN INDIAN EXIT WHOSE PROTECTIVE TRIGGER WOULD NOT CANCEL.
 *
 * It used to give up after one try, for good. Shoppers Stop showed the case it
 * missed: the trigger had already FIRED and sold all 28 shares, the cancel
 * failed because there was nothing left to cancel, and the book went on
 * believing it held them.
 */
class HeldExitTest {

    private fun d(state: String?, held: Int?, qty: Int = 28) =
        ZerodhaLiveMirror.decideUncancellable(state, held, qty)

    @Test fun `the stop already fired - close it, sell nothing, whatever else is held`() {
        // Shoppers Stop. Even if you held more of it by hand, those are yours.
        assertEquals(ExitPath.CLOSED, d("triggered", null))
        assertEquals(ExitPath.CLOSED, d("triggered", 50))
    }

    @Test fun `still resting, or unknown - selling now could sell twice, so hold`() {
        assertEquals(ExitPath.HOLD, d("active", 28))
        assertEquals(ExitPath.HOLD, d(null, 28))
    }

    @Test fun `gone without firing and the shares are all there - safe to sell`() {
        assertEquals(ExitPath.SELL, d("cancelled", 28))
        assertEquals(ExitPath.SELL, d("expired", 40))
        assertEquals(ExitPath.SELL, d("missing", 28))
    }

    @Test fun `gone and nothing held - close it`() {
        assertEquals(ExitPath.CLOSED, d("deleted", 0))
        assertEquals(ExitPath.CLOSED, d("missing", 0))
    }

    @Test fun `gone but only some shares held - never sell what may be yours`() {
        assertEquals(ExitPath.HOLD, d("cancelled", 10))
    }

    @Test fun `nothing held closes it even when Zerodha cannot describe the trigger`() {
        // Shoppers Stop's trigger answered "Error during internal conversion":
        // its state is unknown, but the account holds none of the shares, so
        // there is nothing to sell and nothing left to protect.
        assertEquals(ExitPath.CLOSED, d(null, 0))
        assertEquals(ExitPath.CLOSED, d("active", 0))
    }

    @Test fun `holdings unreadable - do nothing`() {
        assertEquals(ExitPath.HOLD, d("cancelled", null))
    }
}

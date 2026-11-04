package com.signalscope.app.data

import com.signalscope.app.data.NotificationLog.Entry
import com.signalscope.app.data.NotificationLog.Link
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TEAM, 23 Oct 2026: one sale, sixteen identical rows in the notification list. */
class NotificationRepeatTest {
    private val now = 1_792_774_029_000L
    private val title = "🇺🇸 TEAM sold +6.25%"
    private val text = "Algo Us conservative · MACD exit"
    private val link = Link(ns = "us__b148ki0")
    private val first = Entry(key = 1L, at = now - 4_000L, title = title, text = text,
        channel = "t212_live_trades", link = link)

    @Test fun `the same words seconds later are the same notification`() {
        assertTrue(NotificationLog.isRepeat(listOf(first), title, text, "t212_live_trades", link, now))
    }

    @Test fun `different words, place or a later hour are a new one`() {
        assertFalse(NotificationLog.isRepeat(listOf(first), "🇺🇸 TEAM sold", text, "t212_live_trades", link, now))
        assertFalse(NotificationLog.isRepeat(listOf(first), title, text, "t212_live_trades", Link(ns = "us__other"), now))
        assertFalse(NotificationLog.isRepeat(listOf(first), title, text, "t212_live_trades", link,
            now + NotificationLog.REPEAT_MS))
    }
}

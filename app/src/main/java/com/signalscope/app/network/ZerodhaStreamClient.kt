package com.signalscope.app.network

import android.content.Context
import android.util.Log
import com.signalscope.app.data.ConfigManager
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * KITE TICKER — live NSE prices straight from the broker.
 *
 * Deliberately mirrors [YahooFinanceStreamClient]'s public surface (setSymbols /
 * stop / a quote callback / a status callback) and emits that class's LiveQuote,
 * so every existing consumer — the mark-to-market pass, the soft-sniper engine,
 * the live bar recorder — works against either feed with no change. The price
 * source becomes a choice at one wiring point instead of a fork through the app.
 *
 * WHY THIS IS WORTH HAVING OVER YAHOO. It is the same venue the orders go to, so
 * the price a decision is made on and the price it executes against come from
 * one book. Yahoo is a redistributed, delayed, occasionally-wrong copy: fine for
 * research, and the reason the app measures execution deviation at all.
 *
 * ── The protocol, because it is not JSON ──────────────────────────────────
 * Outbound is text: {"a":"subscribe","v":[tokens]} then {"a":"mode","v":["ltp",
 * [tokens]]}. Inbound is BINARY, big-endian:
 *
 *     [2 bytes] number of packets in this frame
 *     then, per packet:
 *       [2 bytes] packet length
 *       [N bytes] payload — in LTP mode, 8 bytes:
 *                 [4] instrument token   [4] last price, in PAISE
 *
 * A frame of 1 byte (or 0) is the server heartbeat and carries no packets.
 * Prices arrive as integers in paise and are divided by 100 — doing that wrong
 * yields prices a hundred times too large, which is the sort of error that
 * reaches a stop-loss calculation before anyone notices it.
 *
 * LTP mode is requested on purpose: it is the smallest packet Kite offers, and
 * everything downstream here wants a last price. Full mode would add depth this
 * app has no use for and multiply the bandwidth on a phone.
 *
 * ── Two failure modes worth naming ────────────────────────────────────────
 * ACCESS TOKENS EXPIRE DAILY, around 6am IST, and the socket is refused from
 * then until the next login. That is reported as a distinct status rather than a
 * generic disconnect, because the fix is a login, not a retry — and a silent
 * reconnect loop against an expired token is indistinguishable from a network
 * problem while your prices quietly stop updating.
 *
 * UNMAPPED SYMBOLS are dropped and named. A symbol with no instrument token
 * cannot be subscribed, and pretending otherwise means one position silently
 * stops being marked while the rest look healthy.
 */
class ZerodhaStreamClient(
    private val appContext: Context,
    private val config: ConfigManager,
    private val onQuote: (YahooFinanceStreamClient.LiveQuote) -> Unit,
    private val onStatus: (YahooFinanceStreamClient.StreamStatus) -> Unit
) {
    companion object {
        private const val TAG = "ZerodhaStream"
        private const val WS_URL = "wss://ws.kite.trade"
        private const val MAX_RECONNECT_MS = 60_000L
        /** Kite's own ceiling per connection. */
        private const val MAX_TOKENS = 3000
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var reconnectDelayMs = 3_000L
    private var enabled = false
    private var connected = false
    private var closedByUser = false
    private var symbols: Set<String> = emptySet()
    private var tokenOf: Map<String, Long> = emptyMap()
    private var symbolOfToken: Map<Long, String> = emptyMap()
    private var unmapped: List<String> = emptyList()
    private var authExpired = false

    fun setSymbols(nextSymbols: Collection<String>) {
        val clean = nextSymbols.map { it.trim().uppercase() }
            .filter { it.isNotBlank() }.toSet()
        synchronized(lock) {
            symbols = clean
            enabled = clean.isNotEmpty()
            closedByUser = !enabled
        }
        if (clean.isEmpty()) { stop("No live symbols"); return }
        if (!config.isZerodhaConnected) {
            emitStatus("Zerodha not logged in — no live prices"); return
        }
        // The mapping may need a multi-megabyte download, so resolve off the
        // caller's thread and only then touch the socket.
        scope.launch {
            resolveTokens(clean)
            val ws = synchronized(lock) { webSocket }
            if (ws == null) connect() else subscribe(ws)
        }
    }

    /**
     * The rest of the app keys prices by Yahoo-style symbol ("RELIANCE.NS"), so
     * that is what must come back out no matter which feed produced it — the
     * mark-to-market map, the bar recorder and the page all look up by that
     * exact string. Only the token lookup uses the bare NSE symbol.
     */
    private fun resolveTokens(clean: Set<String>) {
        val all = try { ZerodhaInstruments.map(appContext, config) }
                  catch (e: Exception) { emptyMap() }
        val hit = HashMap<String, Long>()
        val miss = ArrayList<String>()
        for (s in clean) {
            val bare = s.removeSuffix(".NS").removeSuffix(".BO")
            val t = all[bare]
            if (t != null) hit[s] = t else miss.add(s)
        }
        synchronized(lock) {
            tokenOf = hit
            symbolOfToken = hit.entries.associate { (k, v) -> v to k }
            unmapped = miss
        }
        if (miss.isNotEmpty())
            Log.w(TAG, "no instrument token for ${miss.size}: ${miss.take(8)}")
    }

    fun stop(reason: String = "Live stream off") {
        val ws: WebSocket?
        synchronized(lock) {
            enabled = false; connected = false; closedByUser = true
            symbols = emptySet(); tokenOf = emptyMap(); symbolOfToken = emptyMap()
            ws = webSocket; webSocket = null
        }
        reconnectJob?.cancel(); reconnectJob = null
        ws?.close(1000, reason)
        emitStatus(reason)
    }

    private fun connect() {
        synchronized(lock) {
            if (!enabled || symbols.isEmpty()) return
            if (webSocket != null) return
        }
        if (tokenOf.isEmpty()) {
            emitStatus("No instrument tokens — check the Zerodha login"); return
        }
        emitStatus("Connecting to Kite…")
        val url = "$WS_URL?api_key=${config.zerodhaApiKey}" +
                  "&access_token=${config.zerodhaAccessToken}"
        val ws = http.newWebSocket(Request.Builder().url(url).build(), listener)
        synchronized(lock) {
            if (!enabled || symbols.isEmpty()) ws.close(1000, "disabled") else webSocket = ws
        }
    }

    private fun subscribe(ws: WebSocket) {
        val toks = synchronized(lock) { tokenOf.values.toList() }.take(MAX_TOKENS)
        if (toks.isEmpty()) return
        val list = toks.joinToString(",")
        ws.send("{\"a\":\"subscribe\",\"v\":[$list]}")
        ws.send("{\"a\":\"mode\",\"v\":[\"ltp\",[$list]]}")
        val miss = synchronized(lock) { unmapped.size }
        emitStatus("Kite live · ${toks.size} instruments" +
            (if (miss > 0) " · $miss unmapped" else ""))
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) { connected = true; reconnectDelayMs = 3_000L; authExpired = false }
            subscribe(webSocket)
        }

        /** Kite sends errors and order updates as text; ticks are always binary. */
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.contains("TokenException", true) ||
                text.contains("Invalid", true) && text.contains("token", true)) {
                synchronized(lock) { authExpired = true }
                Log.w(TAG, "kite rejected the token: $text")
                emitStatus("Zerodha session expired — log in again")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            try { parseTicks(bytes) } catch (e: Exception) {
                Log.w(TAG, "tick parse failed: ${e.message}")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null); handleDisconnected("Kite closed: $reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleDisconnected("Kite disconnected")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // 403 here is nearly always the daily token expiry, not a network fault.
            val expired = response?.code == 403
            if (expired) synchronized(lock) { authExpired = true }
            handleDisconnected(
                if (expired) "Zerodha session expired — log in again"
                else "Kite stream error: ${t.message ?: "unknown"}")
        }
    }

    private fun parseTicks(bytes: ByteString) {
        val b = bytes.toByteArray()
        if (b.size < 2) return                       // heartbeat
        fun u16(i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        fun i32(i: Int) = ((b[i].toInt() and 0xFF) shl 24) or
                          ((b[i + 1].toInt() and 0xFF) shl 16) or
                          ((b[i + 2].toInt() and 0xFF) shl 8) or
                          (b[i + 3].toInt() and 0xFF)

        val count = u16(0)
        var off = 2
        val now = System.currentTimeMillis()
        val names = synchronized(lock) { symbolOfToken }
        for (p in 0 until count) {
            if (off + 2 > b.size) return
            val len = u16(off); off += 2
            if (len < 8 || off + len > b.size) { off += len; continue }
            val token = i32(off).toLong() and 0xFFFFFFFFL
            // Paise → rupees. Every Kite equity price is an integer of 1/100.
            val price = i32(off + 4) / 100.0
            off += len
            val sym = names[token] ?: continue
            if (price > 0.0) onQuote(
                YahooFinanceStreamClient.LiveQuote(
                    symbol = sym, price = price, time = now,
                    currency = "INR", exchange = "NSE"))
        }
    }

    private fun handleDisconnected(message: String) {
        synchronized(lock) { connected = false; webSocket = null }
        emitStatus(message)
        // An expired session will refuse every retry until a human logs in, so
        // hammering it just burns battery and hides the real cause.
        if (!closedByUser && !authExpired) scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val wait = reconnectDelayMs
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_MS)
            delay(wait)
            if (!closedByUser && enabled) connect()
        }
    }

    private fun emitStatus(message: String) {
        val st = synchronized(lock) {
            YahooFinanceStreamClient.StreamStatus(
                enabled = enabled, connected = connected,
                message = message, symbolCount = tokenOf.size)
        }
        onStatus(st)
    }
}

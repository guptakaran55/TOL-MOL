package com.signalscope.app.network

import android.util.Log
import com.signalscope.app.data.ConfigManager
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * FINNHUB TRADE STREAM — real-time US prices.
 *
 * Third feed in the same family as [YahooFinanceStreamClient] and
 * [ZerodhaStreamClient]: identical public surface, and it emits the Yahoo
 * client's LiveQuote so every consumer downstream — mark-to-market, the bar
 * recorder, the soft-sniper engine — works unchanged. Choosing a feed stays a
 * decision at one wiring point rather than a fork through the app.
 *
 * WHY IT EXISTS. Yahoo's US quotes are redistributed and can lag by a long way,
 * which matters here because the same prices drive stop checks and entry
 * decisions. Finnhub streams actual trades as they print.
 *
 * ── The protocol ──────────────────────────────────────────────────────────
 * Plain JSON text both ways, which makes it far less error-prone than Kite's
 * packed binary:
 *      out: {"type":"subscribe","symbol":"AAPL"}
 *      in:  {"type":"trade","data":[{"s":"AAPL","p":214.5,"t":1699999999999,"v":100}]}
 *      in:  {"type":"ping"}                     ← keepalive, no payload
 * Timestamps arrive in milliseconds already, so unlike the other two feeds
 * nothing needs converting.
 *
 * ── Two things that bite ──────────────────────────────────────────────────
 * ONE SUBSCRIBE MESSAGE PER SYMBOL. There is no bulk form, so a hundred names
 * is a hundred frames. They are sent with a small gap to avoid tripping the
 * rate limiter, which would otherwise drop the tail of the list silently — the
 * failure would look like "some positions never update".
 *
 * TRADES ONLY, DURING TRADING. Finnhub emits nothing for a symbol that has not
 * traded, so silence is normal outside market hours and for thin names. The
 * status line says "connected" rather than claiming prices are flowing, because
 * "no ticks" and "broken" look identical from here and only one of them is
 * worth acting on.
 */
class FinnhubStreamClient(
    private val config: ConfigManager,
    private val onQuote: (YahooFinanceStreamClient.LiveQuote) -> Unit,
    private val onStatus: (YahooFinanceStreamClient.StreamStatus) -> Unit
) {
    companion object {
        private const val TAG = "FinnhubStream"
        private const val WS_URL = "wss://ws.finnhub.io"
        private const val MAX_RECONNECT_MS = 60_000L
        private const val SUBSCRIBE_GAP_MS = 40L
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var subscribeJob: Job? = null
    private var reconnectDelayMs = 3_000L
    private var enabled = false
    private var connected = false
    private var closedByUser = false
    private var symbols: Set<String> = emptySet()
    private var subscribed: Set<String> = emptySet()
    private var authRejected = false

    fun setSymbols(nextSymbols: Collection<String>) {
        val clean = nextSymbols.map { it.trim().uppercase() }
            .filter { it.isNotBlank() }.toSet()
        val removed: Set<String>
        synchronized(lock) {
            removed = symbols - clean
            symbols = clean
            enabled = clean.isNotEmpty()
            closedByUser = !enabled
        }
        if (clean.isEmpty()) { stop("No live symbols"); return }
        if (config.finnhubApiKey.isBlank()) {
            emitStatus("Finnhub key missing — no live prices"); return
        }
        val ws = synchronized(lock) { webSocket }
        if (ws == null) connect()
        else {
            removed.forEach { ws.send("{\"type\":\"unsubscribe\",\"symbol\":\"$it\"}") }
            sendSubscriptions(ws, clean)
        }
    }

    fun stop(reason: String = "Live stream off") {
        val ws: WebSocket?
        synchronized(lock) {
            enabled = false; connected = false; closedByUser = true
            symbols = emptySet(); subscribed = emptySet()
            ws = webSocket; webSocket = null
        }
        reconnectJob?.cancel(); reconnectJob = null
        subscribeJob?.cancel(); subscribeJob = null
        ws?.close(1000, reason)
        emitStatus(reason)
    }

    private fun connect() {
        synchronized(lock) {
            if (!enabled || symbols.isEmpty()) return
            if (webSocket != null) return
        }
        emitStatus("Connecting to Finnhub…")
        val req = Request.Builder().url("$WS_URL?token=${config.finnhubApiKey}").build()
        val ws = http.newWebSocket(req, listener)
        synchronized(lock) {
            if (!enabled || symbols.isEmpty()) ws.close(1000, "disabled") else webSocket = ws
        }
    }

    /** One frame per symbol, paced — see the rate-limit note on the class. */
    private fun sendSubscriptions(ws: WebSocket, wanted: Set<String>) {
        subscribeJob?.cancel()
        subscribeJob = scope.launch {
            var sent = 0
            for (s in wanted) {
                if (!isActive) return@launch
                ws.send("{\"type\":\"subscribe\",\"symbol\":\"$s\"}")
                sent++
                delay(SUBSCRIBE_GAP_MS)
            }
            synchronized(lock) { subscribed = wanted }
            emitStatus("Finnhub live · $sent symbols")
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) { connected = true; reconnectDelayMs = 3_000L; authRejected = false }
            val wanted = synchronized(lock) { symbols }
            sendSubscriptions(webSocket, wanted)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val o = JSONObject(text)
                when (o.optString("type")) {
                    "trade" -> {
                        val arr = o.optJSONArray("data") ?: return
                        for (i in 0 until arr.length()) {
                            val t = arr.optJSONObject(i) ?: continue
                            val sym = t.optString("s").uppercase()
                            val px = t.optDouble("p", 0.0)
                            if (sym.isBlank() || px <= 0.0) continue
                            // Already milliseconds — no conversion, unlike the others.
                            val ts = t.optLong("t", System.currentTimeMillis())
                            onQuote(YahooFinanceStreamClient.LiveQuote(
                                symbol = sym, price = px, time = ts,
                                currency = "USD", exchange = "US"))
                        }
                    }
                    "ping" -> { /* keepalive */ }
                    "error" -> {
                        val msg = o.optString("msg")
                        Log.w(TAG, "finnhub error: $msg")
                        // A bad key fails every retry, so say so instead of
                        // reconnecting forever against a wall.
                        if (msg.contains("token", true) || msg.contains("key", true) ||
                            msg.contains("auth", true)) {
                            synchronized(lock) { authRejected = true }
                            emitStatus("Finnhub rejected the API key")
                        } else emitStatus("Finnhub: $msg")
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "message parse failed: ${e.message}") }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null); handleDisconnected("Finnhub closed: $reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleDisconnected("Finnhub disconnected")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            if (code == 401 || code == 403) synchronized(lock) { authRejected = true }
            handleDisconnected(
                if (code == 401 || code == 403) "Finnhub rejected the API key"
                else "Finnhub stream error: ${t.message ?: "unknown"}")
        }
    }

    private fun handleDisconnected(message: String) {
        synchronized(lock) { connected = false; webSocket = null; subscribed = emptySet() }
        emitStatus(message)
        if (!closedByUser && !authRejected) scheduleReconnect()
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
                message = message, symbolCount = symbols.size)
        }
        onStatus(st)
    }
}

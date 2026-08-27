package com.signalscope.app.network

import android.util.Base64
import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lightweight Yahoo Finance WebSocket client for live quote overlays.
 *
 * Yahoo wraps each pushed quote in JSON and base64-encodes a small protobuf
 * payload. We decode only the stable quote fields needed by the UI so the app
 * does not need a generated protobuf dependency.
 */
class YahooFinanceStreamClient(
    private val onQuote: (LiveQuote) -> Unit,
    private val onStatus: (StreamStatus) -> Unit
) {
    companion object {
        private const val TAG = "YahooFinanceStream"
        private const val STREAM_URL = "wss://streamer.finance.yahoo.com/?version=2"
        private const val HEARTBEAT_MS = 15_000L
        private const val MAX_RECONNECT_MS = 30_000L
    }

    data class LiveQuote(
        val symbol: String,
        val price: Double,
        val time: Long,
        val currency: String? = null,
        val exchange: String? = null,
        val quoteType: Int? = null,
        val marketHours: Int? = null,
        val changePercent: Double? = null,
        val dayVolume: Long? = null,
        val dayHigh: Double? = null,
        val dayLow: Double? = null,
        val change: Double? = null,
        val priceHint: Long? = null
    )

    data class StreamStatus(
        val enabled: Boolean,
        val connected: Boolean,
        val message: String,
        val symbolCount: Int
    )

    private val ipv4PreferredDns = object : Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            val v4 = all.filterIsInstance<java.net.Inet4Address>()
            val v6 = all.filter { it !is java.net.Inet4Address }
            return v4 + v6
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .dns(ipv4PreferredDns)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()
    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectDelayMs = 3_000L
    private var enabled = false
    private var connected = false
    private var closedByUser = false
    private var symbols: Set<String> = emptySet()
    private var subscribedSymbols: Set<String> = emptySet()

    fun setSymbols(nextSymbols: Collection<String>) {
        val clean = nextSymbols
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
            .toCollection(LinkedHashSet())
            .toSet()

        val removed: Set<String>
        synchronized(lock) {
            removed = symbols - clean
            symbols = clean
            enabled = clean.isNotEmpty()
            closedByUser = !enabled
        }

        if (clean.isEmpty()) {
            stop("No live symbols")
            return
        }

        val ws = synchronized(lock) { webSocket }
        if (ws == null) {
            connect()
        } else {
            sendUnsubscriptions(ws, removed)
            sendSubscriptions(ws, clean)
            emitStatus("Subscribed to ${clean.size} symbols")
        }
    }

    fun stop(reason: String = "Live stream off") {
        val ws: WebSocket?
        synchronized(lock) {
            enabled = false
            connected = false
            closedByUser = true
            symbols = emptySet()
            subscribedSymbols = emptySet()
            ws = webSocket
            webSocket = null
        }
        heartbeatJob?.cancel()
        heartbeatJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        ws?.close(1000, reason)
        emitStatus(reason)
    }

    private fun connect() {
        val currentSymbols = synchronized(lock) {
            if (!enabled || symbols.isEmpty()) return
            if (webSocket != null) return
            symbols
        }

        emitStatus("Connecting...")
        val request = Request.Builder()
            .url(STREAM_URL)
            .addHeader(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
            )
            .build()

        val ws = client.newWebSocket(request, listener)
        synchronized(lock) {
            if (!enabled || symbols.isEmpty()) {
                ws.close(1000, "disabled")
            } else {
                webSocket = ws
            }
        }
        sendSubscriptions(ws, currentSymbols)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                connected = true
                reconnectDelayMs = 3_000L
                this@YahooFinanceStreamClient.webSocket = webSocket
            }
            emitStatus("Connected")
            val currentSymbols = synchronized(lock) { symbols }
            sendSubscriptions(webSocket, currentSymbols)
            startHeartbeat()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                decodeIncoming(text)?.let(onQuote)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode stream message: ${e.message}")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleDisconnected("Closed: $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WebSocket failure: ${t.message}")
            handleDisconnected(t.message ?: "WebSocket failed")
        }
    }

    private fun handleDisconnected(message: String) {
        val shouldReconnect: Boolean
        synchronized(lock) {
            connected = false
            if (webSocket != null) webSocket = null
            shouldReconnect = enabled && symbols.isNotEmpty() && !closedByUser
        }
        heartbeatJob?.cancel()
        heartbeatJob = null
        emitStatus(message)
        if (shouldReconnect) scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        val delayMs = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_MS)
        reconnectJob = scope.launch {
            delay(delayMs)
            synchronized(lock) {
                if (!enabled || symbols.isEmpty() || webSocket != null) return@launch
            }
            connect()
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(HEARTBEAT_MS)
                val pair = synchronized(lock) { webSocket to symbols }
                val ws = pair.first ?: continue
                val currentSymbols = pair.second
                if (currentSymbols.isNotEmpty()) sendSubscriptions(ws, currentSymbols)
            }
        }
    }

    private fun sendSubscriptions(ws: WebSocket, currentSymbols: Set<String>) {
        if (currentSymbols.isEmpty()) return
        val payload = JSONObject().put("subscribe", org.json.JSONArray(currentSymbols)).toString()
        ws.send(payload)
        synchronized(lock) {
            subscribedSymbols = currentSymbols
        }
    }

    private fun sendUnsubscriptions(ws: WebSocket, removedSymbols: Set<String>) {
        if (removedSymbols.isEmpty()) return
        val payload = JSONObject().put("unsubscribe", org.json.JSONArray(removedSymbols)).toString()
        ws.send(payload)
        synchronized(lock) {
            subscribedSymbols = subscribedSymbols - removedSymbols
        }
    }

    private fun emitStatus(message: String) {
        val snapshot = synchronized(lock) {
            StreamStatus(
                enabled = enabled,
                connected = connected,
                message = message,
                symbolCount = symbols.size
            )
        }
        onStatus(snapshot)
    }

    private fun decodeIncoming(text: String): LiveQuote? {
        val encoded = try {
            val json = JSONObject(text)
            json.optString("message", "")
        } catch (_: Exception) {
            text
        }
        if (encoded.isBlank()) return null
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        return decodePricingData(bytes)
    }

    private fun decodePricingData(bytes: ByteArray): LiveQuote? {
        val reader = ProtoReader(bytes)
        var id: String? = null
        var price: Double? = null
        var time: Long = 0L
        var currency: String? = null
        var exchange: String? = null
        var quoteType: Int? = null
        var marketHours: Int? = null
        var changePercent: Double? = null
        var dayVolume: Long? = null
        var dayHigh: Double? = null
        var dayLow: Double? = null
        var change: Double? = null
        var priceHint: Long? = null

        while (!reader.exhausted()) {
            val tag = reader.readVarintOrNull() ?: break
            val field = (tag ushr 3).toInt()
            val wireType = (tag and 7L).toInt()
            when (field) {
                1 -> id = reader.readString(wireType)
                2 -> price = reader.readFloat(wireType)?.toDouble()
                3 -> time = reader.readSint64(wireType) ?: time
                4 -> currency = reader.readString(wireType)
                5 -> exchange = reader.readString(wireType)
                6 -> quoteType = reader.readInt32(wireType)
                7 -> marketHours = reader.readInt32(wireType)
                8 -> changePercent = reader.readFloat(wireType)?.toDouble()
                9 -> dayVolume = reader.readSint64(wireType)
                10 -> dayHigh = reader.readFloat(wireType)?.toDouble()
                11 -> dayLow = reader.readFloat(wireType)?.toDouble()
                12 -> change = reader.readFloat(wireType)?.toDouble()
                27 -> priceHint = reader.readSint64(wireType)
                else -> reader.skip(wireType)
            }
        }

        val symbol = id?.takeIf { it.isNotBlank() } ?: return null
        val lastPrice = price ?: return null
        return LiveQuote(
            symbol = symbol,
            price = lastPrice,
            time = time,
            currency = currency,
            exchange = exchange,
            quoteType = quoteType,
            marketHours = marketHours,
            changePercent = changePercent,
            dayVolume = dayVolume,
            dayHigh = dayHigh,
            dayLow = dayLow,
            change = change,
            priceHint = priceHint
        )
    }

    private class ProtoReader(private val bytes: ByteArray) {
        private var pos = 0

        fun exhausted(): Boolean = pos >= bytes.size

        fun readVarintOrNull(): Long? {
            if (exhausted()) return null
            var shift = 0
            var result = 0L
            while (shift < 64 && pos < bytes.size) {
                val b = bytes[pos++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
            return null
        }

        fun readString(wireType: Int): String? {
            if (wireType != 2) {
                skip(wireType)
                return null
            }
            val len = readVarintOrNull()?.toInt() ?: return null
            if (len < 0 || pos + len > bytes.size) {
                pos = bytes.size
                return null
            }
            val out = String(bytes, pos, len, Charsets.UTF_8)
            pos += len
            return out
        }

        fun readFloat(wireType: Int): Float? {
            if (wireType != 5) {
                skip(wireType)
                return null
            }
            if (pos + 4 > bytes.size) {
                pos = bytes.size
                return null
            }
            val out = ByteBuffer.wrap(bytes, pos, 4).order(ByteOrder.LITTLE_ENDIAN).float
            pos += 4
            return out
        }

        fun readInt32(wireType: Int): Int? {
            if (wireType != 0) {
                skip(wireType)
                return null
            }
            return readVarintOrNull()?.toInt()
        }

        fun readSint64(wireType: Int): Long? {
            if (wireType != 0) {
                skip(wireType)
                return null
            }
            val raw = readVarintOrNull() ?: return null
            return (raw ushr 1) xor -(raw and 1L)
        }

        fun skip(wireType: Int) {
            when (wireType) {
                0 -> readVarintOrNull()
                1 -> pos = (pos + 8).coerceAtMost(bytes.size)
                2 -> {
                    val len = readVarintOrNull()?.toInt() ?: 0
                    pos = (pos + len).coerceAtMost(bytes.size)
                }
                5 -> pos = (pos + 4).coerceAtMost(bytes.size)
                else -> pos = bytes.size
            }
        }
    }
}

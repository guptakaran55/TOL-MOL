package com.signalscope.app.trading

import com.signalscope.app.network.YahooFinanceStreamClient
import com.signalscope.app.util.Indicators
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Process-local 15-minute bar builder fed by the Yahoo live stream.
 *
 * This intentionally does not replace daily/scan indicators. It gives the app
 * a fresh intraday lens only while the WebSocket stream is connected and ticks
 * are recent; otherwise callers naturally fall back to scan-based logic.
 */
object LivePortfolioBars {
    private const val BAR_MS = 15 * 60 * 1000L
    private const val FRESH_TICK_MS = 90 * 1000L
    private const val MAX_COMPLETED_BARS = 32

    private const val MIN_CURRENT_BAR_TICKS = 3
    private const val MIN_CURRENT_BAR_AGE_MS = 2 * 60 * 1000L
    private const val MIN_DROP_FROM_HIGH_PCT = 0.75
    private const val MIN_CURRENT_BAR_DOWN_PCT = 0.20
    private const val MIN_CLOSE_SLOPE_DOWN_PCT = 0.30
    private const val MIN_VELOCITY_DROP_FROM_HIGH_PCT = 0.60
    private const val VELOCITY_30M_WARN_PCT = -0.45
    private const val VELOCITY_30M_SEVERE_PCT = -0.80
    private const val VELOCITY_30M_ACCEL_PCT = -0.35

    data class Bar(
        val startMs: Long,
        var open: Double,
        var high: Double,
        var low: Double,
        var close: Double,
        var volume: Long,
        var ticks: Int,
        var lastUpdateMs: Long
    )

    data class ReversalSignal(
        val symbol: String,
        val ready: Boolean,
        val shouldProtect: Boolean,
        val latestPrice: Double,
        val lastQuoteAtMs: Long,
        val currentBarStartMs: Long,
        val currentBarAgeMs: Long,
        val currentBarTicks: Int,
        val dropFromSessionHighPct: Double,
        val currentBarMovePct: Double,
        val closeSlopePct: Double,
        val previousSlopePct: Double,
        val reason: String,
        val velocity30m: PriceVelocitySignal = PriceVelocitySignal(),
        val mtfMacd: MultiTimeframeMacd = MultiTimeframeMacd()
    )

    data class PriceVelocitySignal(
        val label: String = "30M",
        val ready: Boolean = false,
        val bars: Int = 0,
        val velocityPct: Double = 0.0,
        val previousVelocityPct: Double = 0.0,
        val accelerationPct: Double = 0.0,
        val warning: Boolean = false,
        val severe: Boolean = false,
        val confirmed: Boolean = false,
        val summary: String = "waiting for 30m velocity bars"
    )

    data class FrameMacdSignal(
        val label: String = "",
        val ready: Boolean = false,
        val bars: Int = 0,
        val macd: Double = 0.0,
        val histogram: Double = 0.0,
        val slope: Double = 0.0,
        val previousSlope: Double = 0.0,
        val buyHook: Boolean = false,
        val sellWarning: Boolean = false,
        val reason: String = "waiting for bars"
    )

    data class MultiTimeframeMacd(
        val oneHour: FrameMacdSignal = FrameMacdSignal(label = "1H"),
        val twoHour: FrameMacdSignal = FrameMacdSignal(label = "2H"),
        val fourHour: FrameMacdSignal = FrameMacdSignal(label = "4H"),
        val sellWarning: Boolean = false,
        val sellConfirmed: Boolean = false,
        val buyReady: Boolean = false,
        val summary: String = "waiting for intraday MACD bars"
    )

    private data class State(
        var current: Bar? = null,
        val completed: ArrayDeque<Bar> = ArrayDeque(),
        var sessionDay: Int = -1,
        var sessionHigh: Double = 0.0,
        var sessionLow: Double = 0.0,
        var lastPrice: Double = 0.0,
        var lastQuoteAtMs: Long = 0L,
        var lastDayVolume: Long? = null
    )

    private val lock = Any()
    private val states = mutableMapOf<String, State>()
    @Volatile private var streamEnabled = false
    @Volatile private var streamConnected = false
    @Volatile private var streamSymbolCount = 0
    @Volatile private var streamMessage = "Live stream off"

    fun updateStreamState(enabled: Boolean, connected: Boolean, symbolCount: Int, message: String) {
        synchronized(lock) {
            streamEnabled = enabled
            streamConnected = connected
            streamSymbolCount = symbolCount
            streamMessage = message
            if (!enabled) {
                states.clear()
            }
        }
    }

    fun recordQuote(quote: YahooFinanceStreamClient.LiveQuote): ReversalSignal? {
        val symbol = normalizeYahooSymbol(quote.symbol)
        if (symbol.isBlank() || quote.price <= 0.0) return null

        val now = System.currentTimeMillis()
        val quoteMs = quoteTimeMs(quote.time, now)
        val bucket = (quoteMs / BAR_MS) * BAR_MS
        val day = (quoteMs / (24 * 60 * 60 * 1000L)).toInt()

        synchronized(lock) {
            val state = states.getOrPut(symbol) { State() }
            if (state.sessionDay != day) {
                state.completed.clear()
                state.current = null
                state.sessionHigh = quote.price
                state.sessionLow = quote.price
                state.lastDayVolume = quote.dayVolume
                state.sessionDay = day
            }

            val volumeDelta = volumeDelta(state, quote.dayVolume)
            val current = state.current
            if (current == null || current.startMs != bucket) {
                if (current != null) {
                    state.completed.addLast(current.copy())
                    while (state.completed.size > MAX_COMPLETED_BARS) state.completed.removeFirst()
                }
                state.current = Bar(
                    startMs = bucket,
                    open = quote.price,
                    high = quote.price,
                    low = quote.price,
                    close = quote.price,
                    volume = volumeDelta,
                    ticks = 1,
                    lastUpdateMs = quoteMs
                )
            } else {
                current.high = max(current.high, quote.price)
                current.low = min(current.low, quote.price)
                current.close = quote.price
                current.volume += volumeDelta
                current.ticks += 1
                current.lastUpdateMs = quoteMs
            }

            state.sessionHigh = max(state.sessionHigh, quote.price)
            state.sessionLow = if (state.sessionLow > 0.0) min(state.sessionLow, quote.price) else quote.price
            state.lastPrice = quote.price
            state.lastQuoteAtMs = now

            return evaluateLocked(symbol, state, now)
        }
    }

    fun signalForYahooSymbol(yahooSymbol: String, nowMs: Long = System.currentTimeMillis()): ReversalSignal? {
        val symbol = normalizeYahooSymbol(yahooSymbol)
        synchronized(lock) {
            val state = states[symbol] ?: return null
            return evaluateLocked(symbol, state, nowMs)
        }
    }

    fun isReadyForYahooSymbol(yahooSymbol: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        signalForYahooSymbol(yahooSymbol, nowMs)?.ready == true

    fun normalizeYahooSymbol(symbol: String): String =
        symbol.trim().uppercase(Locale.US)

    private fun evaluateLocked(symbol: String, state: State, nowMs: Long): ReversalSignal? {
        val current = state.current ?: return null
        val latestPrice = state.lastPrice
        if (latestPrice <= 0.0) return null

        val tickFresh = nowMs - state.lastQuoteAtMs <= FRESH_TICK_MS
        val currentBarAgeMs = (state.lastQuoteAtMs - current.startMs).coerceAtLeast(0L)
        val prev = state.completed.lastOrNull()
        val prev2 = if (state.completed.size >= 2) state.completed.elementAt(state.completed.size - 2) else null
        val hasEnoughBarShape = current.ticks >= MIN_CURRENT_BAR_TICKS &&
                (currentBarAgeMs >= MIN_CURRENT_BAR_AGE_MS || state.completed.isNotEmpty())
        val ready = streamEnabled && streamConnected && streamSymbolCount > 0 && tickFresh && hasEnoughBarShape

        val currentMove = pctMove(current.close, current.open)
        val closeSlope = prev?.let { pctMove(current.close, it.close) } ?: currentMove
        val previousSlope = when {
            prev != null && prev2 != null -> pctMove(prev.close, prev2.close)
            prev != null -> pctMove(prev.close, prev.open)
            else -> 0.0
        }
        val mtfMacd = evaluateMultiFrameMacd(state)
        val dropFromHigh = if (state.sessionHigh > 0.0) {
            ((state.sessionHigh - latestPrice) / state.sessionHigh * 100.0).coerceAtLeast(0.0)
        } else 0.0
        val velocity30m = evaluateThirtyMinuteVelocity(
            state = state,
            dropFromHighPct = dropFromHigh,
            oneHourRollingDown = mtfMacd.oneHour.sellWarning,
            currentBarMovePct = currentMove,
            closeSlopePct = closeSlope
        )

        val downsideBar = currentMove <= -MIN_CURRENT_BAR_DOWN_PCT ||
                closeSlope <= -MIN_CLOSE_SLOPE_DOWN_PCT
        val downsideTurn = previousSlope >= -0.50 || dropFromHigh >= MIN_DROP_FROM_HIGH_PCT * 1.6
        val protectionDropOk = dropFromHigh >= MIN_DROP_FROM_HIGH_PCT ||
                (velocity30m.severe && dropFromHigh >= MIN_VELOCITY_DROP_FROM_HIGH_PCT)
        val shouldProtect = ready &&
                protectionDropOk &&
                ((downsideBar && downsideTurn) ||
                        mtfMacd.sellWarning ||
                        mtfMacd.sellConfirmed ||
                        velocity30m.confirmed)

        val reason = when {
            !streamEnabled -> "live stream disabled"
            !streamConnected -> "live stream not connected"
            !tickFresh -> "latest quote is stale"
            !hasEnoughBarShape -> "waiting for 15m bar shape"
            shouldProtect && velocity30m.confirmed && (mtfMacd.sellWarning || mtfMacd.sellConfirmed) ->
                "30m velocity ${velocity30m.summary}; live MACD ${mtfMacd.summary}; pullback ${format(dropFromHigh)}% from session high"
            shouldProtect && velocity30m.confirmed ->
                "30m velocity ${velocity30m.summary}; pullback ${format(dropFromHigh)}% from session high"
            shouldProtect && (mtfMacd.sellWarning || mtfMacd.sellConfirmed) ->
                "live MACD ${mtfMacd.summary}; pullback ${format(dropFromHigh)}% from session high"
            shouldProtect -> "15m live bar rolled down after ${format(dropFromHigh)}% pullback from session high"
            else -> "live bar has not rolled down"
        }

        return ReversalSignal(
            symbol = symbol,
            ready = ready,
            shouldProtect = shouldProtect,
            latestPrice = latestPrice,
            lastQuoteAtMs = state.lastQuoteAtMs,
            currentBarStartMs = current.startMs,
            currentBarAgeMs = currentBarAgeMs,
            currentBarTicks = current.ticks,
            dropFromSessionHighPct = dropFromHigh,
            currentBarMovePct = currentMove,
            closeSlopePct = closeSlope,
            previousSlopePct = previousSlope,
            reason = reason,
            velocity30m = velocity30m,
            mtfMacd = mtfMacd
        )
    }

    private fun evaluateThirtyMinuteVelocity(
        state: State,
        dropFromHighPct: Double,
        oneHourRollingDown: Boolean,
        currentBarMovePct: Double,
        closeSlopePct: Double
    ): PriceVelocitySignal {
        val bars = (state.completed.toList() + listOfNotNull(state.current))
            .sortedBy { it.startMs }
        val closes = aggregateCloses(bars, 2)
        if (closes.size < 3) {
            return PriceVelocitySignal(bars = closes.size)
        }
        val velocity = pctMove(closes.last(), closes[closes.lastIndex - 1])
        val previous = pctMove(closes[closes.lastIndex - 1], closes[closes.lastIndex - 2])
        val acceleration = velocity - previous
        val warning = velocity <= VELOCITY_30M_WARN_PCT ||
                (velocity <= VELOCITY_30M_WARN_PCT * 0.70 && acceleration <= VELOCITY_30M_ACCEL_PCT)
        val severe = velocity <= VELOCITY_30M_SEVERE_PCT ||
                (velocity <= VELOCITY_30M_WARN_PCT * 1.20 && acceleration <= VELOCITY_30M_ACCEL_PCT)
        val dropOk = dropFromHighPct >= MIN_DROP_FROM_HIGH_PCT ||
                (severe && dropFromHighPct >= MIN_VELOCITY_DROP_FROM_HIGH_PCT)
        val momentumContext = oneHourRollingDown ||
                currentBarMovePct <= -MIN_CURRENT_BAR_DOWN_PCT ||
                closeSlopePct <= -MIN_CLOSE_SLOPE_DOWN_PCT ||
                severe
        val confirmed = warning && dropOk && momentumContext
        val label = when {
            confirmed && severe -> "severe ${format(velocity)}%/30m"
            confirmed -> "confirmed ${format(velocity)}%/30m"
            warning -> "warning ${format(velocity)}%/30m"
            else -> "stable ${format(velocity)}%/30m"
        }
        val accelText = if (acceleration >= 0.0) "+${format(acceleration)}" else format(acceleration)
        return PriceVelocitySignal(
            ready = true,
            bars = closes.size,
            velocityPct = velocity,
            previousVelocityPct = previous,
            accelerationPct = acceleration,
            warning = warning,
            severe = severe,
            confirmed = confirmed,
            summary = "$label, accel $accelText"
        )
    }

    private fun evaluateMultiFrameMacd(state: State): MultiTimeframeMacd {
        val bars = (state.completed.toList() + listOfNotNull(state.current))
            .sortedBy { it.startMs }
        val one = frameSignal("1H", bars, 4)
        val two = frameSignal("2H", bars, 8)
        val four = frameSignal("4H", bars, 16)
        val sellWarning = one.sellWarning || two.sellWarning
        val sellConfirmed = four.sellWarning
        val buyReady = one.buyHook && two.buyHook && (four.buyHook || (four.ready && !four.sellWarning))
        val parts = mutableListOf<String>()
        if (one.ready) parts.add("1H ${if (one.buyHook) "hook up" else if (one.sellWarning) "rolling down" else "flat"}")
        if (two.ready) parts.add("2H ${if (two.buyHook) "hook up" else if (two.sellWarning) "rolling down" else "flat"}")
        if (four.ready) parts.add("4H ${if (four.buyHook) "confirmed up" else if (four.sellWarning) "confirmed down" else "neutral"}")
        return MultiTimeframeMacd(
            oneHour = one,
            twoHour = two,
            fourHour = four,
            sellWarning = sellWarning,
            sellConfirmed = sellConfirmed,
            buyReady = buyReady,
            summary = if (parts.isEmpty()) "waiting for 1H/2H/4H bars" else parts.joinToString(" / ")
        )
    }

    private fun frameSignal(label: String, bars: List<Bar>, barsPerFrame: Int): FrameMacdSignal {
        val closes = aggregateCloses(bars, barsPerFrame)
        if (closes.size < 2) {
            return FrameMacdSignal(label = label, bars = closes.size)
        }
        if (closes.size < 4) {
            val slope = pctMove(closes.last(), closes[closes.lastIndex - 1])
            val prevSlope = if (closes.size >= 3) pctMove(closes[closes.lastIndex - 1], closes[closes.lastIndex - 2]) else 0.0
            return FrameMacdSignal(
                label = label,
                ready = true,
                bars = closes.size,
                slope = slope,
                previousSlope = prevSlope,
                buyHook = slope >= 0.08 && prevSlope < 0.0,
                sellWarning = slope <= -0.18 && prevSlope > -0.35,
                reason = "$label price-slope proxy"
            )
        }
        val fast = max(2, min(6, closes.size / 3))
        val slow = max(fast + 1, min(13, closes.size / 2))
        val signal = max(2, min(5, closes.size / 4))
        val macd = Indicators.macd(closes, fast, slow, signal)
        val hist = macd.histogram
        val macdLine = macd.macdLine
        val lastHist = hist.last()
        val prevHist = hist.getOrElse(hist.lastIndex - 1) { lastHist }
        val prev2Hist = hist.getOrElse(hist.lastIndex - 2) { prevHist }
        val slope = lastHist - prevHist
        val prevSlope = prevHist - prev2Hist
        val lastMacd = macdLine.last()
        val buyHook = slope > 0.0 && (lastHist <= 0.0 || lastMacd <= 0.0 || prevSlope < 0.0)
        val sellWarning = slope < 0.0 && (lastHist >= 0.0 || lastMacd >= 0.0 || prevSlope > 0.0)
        return FrameMacdSignal(
            label = label,
            ready = true,
            bars = closes.size,
            macd = lastMacd,
            histogram = lastHist,
            slope = slope,
            previousSlope = prevSlope,
            buyHook = buyHook,
            sellWarning = sellWarning,
            reason = "$label MACD($fast,$slow,$signal)"
        )
    }

    private fun aggregateCloses(bars: List<Bar>, barsPerFrame: Int): List<Double> {
        if (bars.isEmpty()) return emptyList()
        val frameMs = BAR_MS * barsPerFrame
        return bars
            .groupBy { (it.startMs / frameMs) * frameMs }
            .toSortedMap()
            .mapNotNull { (_, bucketBars) -> bucketBars.maxByOrNull { it.startMs }?.close }
    }

    private fun pctMove(value: Double, base: Double): Double =
        if (base > 0.0) (value - base) / base * 100.0 else 0.0

    private fun quoteTimeMs(raw: Long, fallbackNow: Long): Long {
        if (raw <= 0L) return fallbackNow
        val candidate = if (raw < 1_000_000_000_000L) raw * 1000L else raw
        return if (abs(candidate - fallbackNow) <= 12 * 60 * 60 * 1000L) candidate else fallbackNow
    }

    private fun volumeDelta(state: State, dayVolume: Long?): Long {
        if (dayVolume == null || dayVolume <= 0L) return 0L
        val prior = state.lastDayVolume
        state.lastDayVolume = dayVolume
        if (prior == null || dayVolume < prior) return 0L
        return dayVolume - prior
    }

    private fun format(value: Double): String =
        String.format(Locale.US, "%.2f", value)
}

package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.GttActivityLog
import com.signalscope.app.data.AlertType
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.StockAlert
import com.signalscope.app.network.YahooFinanceClient
import com.signalscope.app.network.YahooFinanceStreamClient
import com.signalscope.app.network.ZerodhaClient
import com.signalscope.app.ui.ScanServiceResultHolder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

/**
 * Live-ticker path for Tier 1.5 protection.
 *
 * When Yahoo streaming is connected, ticks are aggregated into 15-minute bars.
 * A fresh intraday rollover can place the same single-leg soft-sniper GTT as
 * the scan path. If the stream is off/stale/not shaped yet, this object does
 * nothing and ScanService's slower scan persistence remains the fallback.
 */
object LiveSoftSniperEngine {
    private const val TAG = "LiveSoftSniper"
    private const val SKIP_AUDIT_COOLDOWN_MS = 10 * 60 * 1000L
    private val skipAuditTimes = mutableMapOf<String, Long>()

    fun onQuote(context: Context, config: ConfigManager, quote: YahooFinanceStreamClient.LiveQuote) {
        if (!config.livePriceStreamingEnabled) return
        val signal = LivePortfolioBars.recordQuote(quote) ?: return
        onSignal(context, config, signal)
    }

    fun onSignal(
        context: Context,
        config: ConfigManager,
        signal: LivePortfolioBars.ReversalSignal
    ) {
        if (!config.livePriceStreamingEnabled) return
        if (!signal.shouldProtect) return
        maybePlaceLiveSoftSniper(context.applicationContext, config, signal)
    }

    private fun maybePlaceLiveSoftSniper(
        context: Context,
        config: ConfigManager,
        signal: LivePortfolioBars.ReversalSignal
    ) {
        if (!config.autoSoftSniperEnabled) {
            auditSkip(context, signal, "autoSoftSniper disabled")
            return
        }
        if (!config.autoCreateGtt) {
            auditSkip(context, signal, "autoCreateGtt disabled")
            return
        }
        if (!config.isZerodhaConnected) {
            auditSkip(context, signal, "Zerodha not connected")
            return
        }

        val result = ScanServiceResultHolder.lastZerodhaResult ?: run {
            auditSkip(context, signal, "no latest portfolio snapshot")
            return
        }
        val holding = result.holdings.firstOrNull { holding ->
            LivePortfolioBars.normalizeYahooSymbol(
                YahooFinanceClient.toYahooSymbol(holding.symbol, holding.exchange)
            ) == signal.symbol
        } ?: run {
            auditSkip(context, signal, "symbol not found in portfolio")
            return
        }

        val analysis = holding.analysis ?: run {
            auditSkip(context, signal, "portfolio holding has no analysis", holding.symbol)
            return
        }
        if (holding.quantity <= 0 || holding.avgPrice <= 0.0 || signal.latestPrice <= 0.0) {
            auditSkip(context, signal, "invalid quantity/avg/ltp", holding.symbol)
            return
        }

        val gainPct = (signal.latestPrice - holding.avgPrice) / holding.avgPrice * 100.0
        if (gainPct < config.softSniperMinGainPct) {
            Log.i(
                TAG,
                "Live soft sniper skipped for ${holding.symbol}: gain ${fmt(gainPct)}% < ${config.softSniperMinGainPct}%"
            )
            auditSkip(
                context,
                signal,
                "gain ${fmt(gainPct)}% below softSniperMinGain ${fmt(config.softSniperMinGainPct)}%",
                holding.symbol,
                gainPct
            )
            return
        }

        val today = todayTradingDate()
        if (ScanServiceResultHolder.isMacdWatchdogFired(holding.symbol, today)) {
            auditSkip(context, signal, "hard MACD watchdog already fired today", holding.symbol, gainPct)
            return
        }
        if (!ScanServiceResultHolder.markSoftSniperFiredIfAbsent(holding.symbol, today)) {
            auditSkip(context, signal, "soft sniper already fired today", holding.symbol, gainPct)
            return
        }

        val (bufferPct, bufferTier) = when {
            signal.velocity30m.confirmed || signal.dropFromSessionHighPct >= 1.0 || signal.closeSlopePct <= -0.50 ->
                config.softSniperTightBufferPct to "LIVE_TIGHT"
            else ->
                config.softSniperBufferPct to "LIVE_NORMAL"
        }

        val triggerRaw = signal.latestPrice * (1.0 - bufferPct)
        val triggerPrice = roundToTick(triggerRaw)
            .coerceIn(signal.latestPrice * 0.75, signal.latestPrice * 0.999)
        val limitPrice = roundToTick(triggerPrice * (1.0 - config.softSniperLimitPct))
        GttActivityLog.append(
            context,
            "decide_live_soft_sniper",
            symbol = holding.symbol,
            fields = auditFields(signal, gainPct) + mapOf(
                "exchange" to holding.exchange,
                "qty" to holding.quantity,
                "avg" to holding.avgPrice,
                "tier" to bufferTier,
                "bufferPct" to bufferPct,
                "trigger" to triggerPrice,
                "limit" to limitPrice,
                "reason" to signal.reason,
                "intent" to "create protective single-leg SELL GTT"
            )
        )

        thread(isDaemon = true, name = "live-soft-sniper-${holding.symbol}") {
            try {
                val zerodha = ZerodhaClient(config)

                val gttList = zerodha.fetchGttTriggers()
                if (gttList is ZerodhaClient.GttListResult.Success) {
                    val sellGtts = gttList.triggers.filter {
                        it.status.equals("active", ignoreCase = true) &&
                                it.tradingsymbol.equals(holding.symbol, ignoreCase = true) &&
                                it.transactionType.equals("SELL", ignoreCase = true)
                    }
                    for (g in sellGtts) {
                        GttActivityLog.append(
                            context,
                            "delete_existing_before_live_soft_sniper",
                            symbol = holding.symbol,
                            fields = mapOf(
                                "oldId" to g.id,
                                "oldTrigger" to g.triggerPrice,
                                "oldLimit" to g.limitPrice,
                                "reason" to "replace old SELL GTT with live soft sniper"
                            )
                        )
                        zerodha.deleteGttTrigger(g.id)
                        Thread.sleep(1000L)
                    }
                    if (sellGtts.isNotEmpty()) Thread.sleep(1000L)
                } else if (gttList is ZerodhaClient.GttListResult.Expired) {
                    Log.w(TAG, "Live soft sniper skipped for ${holding.symbol}: Zerodha session expired")
                    GttActivityLog.append(
                        context,
                        "skip_live_soft_sniper",
                        symbol = holding.symbol,
                        fields = auditFields(signal, gainPct) + mapOf("reason" to "Zerodha session expired while fetching existing GTTs")
                    )
                    return@thread
                }

                val create = zerodha.createGttTrigger(
                    type = "single",
                    exchange = holding.exchange,
                    tradingsymbol = holding.symbol,
                    triggerValues = listOf(triggerPrice),
                    lastPrice = signal.latestPrice,
                    orders = listOf(
                        ZerodhaClient.GttOrderSpec(
                            transactionType = "SELL",
                            quantity = holding.quantity,
                            price = limitPrice,
                            product = "CNC",
                            orderType = "LIMIT"
                        )
                    )
                )

                when (create) {
                    is ZerodhaClient.GttCreateResult.Success -> {
                        val pctFromLtp = (triggerPrice / signal.latestPrice - 1.0) * 100.0
                        val mtfNote = if (signal.mtfMacd.sellWarning || signal.mtfMacd.sellConfirmed) {
                            ", ${signal.mtfMacd.summary}"
                        } else {
                            ""
                        }
                        val velocityNote = if (signal.velocity30m.warning || signal.velocity30m.confirmed) {
                            ", 30m ${signal.velocity30m.summary}"
                        } else {
                            ""
                        }
                        val msg = "Live 15m Auto-GTT [$bufferTier] @ ${fmt(triggerPrice)} " +
                                "(${fmt(pctFromLtp)}%) - pullback ${fmt(signal.dropFromSessionHighPct)}%, " +
                                "15m slope ${fmt(signal.closeSlopePct)}%, gain ${fmt(gainPct)}%$velocityNote$mtfNote"
                        Log.i(TAG, "${holding.symbol} live soft sniper GTT #${create.id} placed. $msg")
                        GttActivityLog.append(
                            context,
                            "create_live_soft_sniper",
                            symbol = holding.symbol,
                            fields = auditFields(signal, gainPct) + mapOf(
                                "id" to create.id,
                                "exchange" to holding.exchange,
                                "qty" to holding.quantity,
                                "avg" to holding.avgPrice,
                                "tier" to bufferTier,
                                "bufferPct" to bufferPct,
                                "trigger" to triggerPrice,
                                "limit" to limitPrice,
                                "reason" to signal.reason,
                                "message" to msg
                            )
                        )
                        ScanServiceResultHolder.softSniperAlerts.add(
                            StockAlert(
                                symbol = holding.symbol,
                                name = holding.symbol,
                                alertType = AlertType.SOFT_SNIPER,
                                message = msg,
                                sellScore = analysis.sellScore,
                                cherryPoints = analysis.cherryPoints,
                                price = signal.latestPrice,
                                macdPhase = analysis.macdPhase,
                                source = "zerodha-live"
                            )
                        )
                        ScanServiceResultHolder.portfolioVersion++
                    }
                    is ZerodhaClient.GttCreateResult.Failure -> {
                        Log.w(TAG, "Live soft sniper create failed for ${holding.symbol}: ${create.message}")
                        GttActivityLog.append(
                            context,
                            "error_live_soft_sniper",
                            symbol = holding.symbol,
                            fields = auditFields(signal, gainPct) + mapOf(
                                "trigger" to triggerPrice,
                                "limit" to limitPrice,
                                "tier" to bufferTier,
                                "error" to create.message
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Live soft sniper thread crashed for ${holding.symbol}", e)
                GttActivityLog.append(
                    context,
                    "error_live_soft_sniper",
                    symbol = holding.symbol,
                    fields = auditFields(signal, gainPct) + mapOf(
                        "trigger" to triggerPrice,
                        "limit" to limitPrice,
                        "tier" to bufferTier,
                        "error" to (e.message ?: "unknown")
                    )
                )
            }
        }
    }

    private fun auditSkip(
        context: Context,
        signal: LivePortfolioBars.ReversalSignal,
        reason: String,
        symbolOverride: String? = null,
        gainPct: Double? = null
    ) {
        val symbol = symbolOverride ?: signal.symbol
        val key = "$symbol|$reason"
        val now = System.currentTimeMillis()
        synchronized(skipAuditTimes) {
            val last = skipAuditTimes[key] ?: 0L
            if (now - last < SKIP_AUDIT_COOLDOWN_MS) return
            skipAuditTimes[key] = now
            if (skipAuditTimes.size > 250) skipAuditTimes.clear()
        }
        GttActivityLog.append(
            context,
            "skip_live_soft_sniper",
            symbol = symbol,
            fields = auditFields(signal, gainPct) + mapOf("reason" to reason)
        )
    }

    private fun auditFields(signal: LivePortfolioBars.ReversalSignal, gainPct: Double?): Map<String, Any?> = mapOf(
        "ltp" to signal.latestPrice,
        "gainPct" to gainPct,
        "dropHighPct" to signal.dropFromSessionHighPct,
        "barMovePct" to signal.currentBarMovePct,
        "closeSlopePct" to signal.closeSlopePct,
        "prevSlopePct" to signal.previousSlopePct,
        "v30Ready" to signal.velocity30m.ready,
        "v30Pct" to signal.velocity30m.velocityPct,
        "v30Accel" to signal.velocity30m.accelerationPct,
        "v30Warning" to signal.velocity30m.warning,
        "v30Confirmed" to signal.velocity30m.confirmed,
        "v30Summary" to signal.velocity30m.summary,
        "macd1h" to frameSummary(signal.mtfMacd.oneHour),
        "macd2h" to frameSummary(signal.mtfMacd.twoHour),
        "macd4h" to frameSummary(signal.mtfMacd.fourHour),
        "mtfSellWarning" to signal.mtfMacd.sellWarning,
        "mtfSellConfirmed" to signal.mtfMacd.sellConfirmed,
        "mtfSummary" to signal.mtfMacd.summary
    )

    private fun frameSummary(frame: LivePortfolioBars.FrameMacdSignal): String =
        "${frame.label}:ready=${frame.ready},slope=${fmt(frame.slope)},hist=${fmt(frame.histogram)},buy=${frame.buyHook},sell=${frame.sellWarning}"

    private fun todayTradingDate(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        return sdf.format(Date())
    }

    private fun roundToTick(value: Double): Double =
        Math.round(value * 20.0) / 20.0

    private fun fmt(value: Double): String =
        String.format(Locale.US, "%.2f", value)
}

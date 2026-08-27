package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.AlertType
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.LiveBuyTrackerStore
import com.signalscope.app.data.StockAlert
import com.signalscope.app.network.YahooFinanceClient
import com.signalscope.app.network.ZerodhaClient
import com.signalscope.app.ui.ScanServiceResultHolder
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Live buy arming engine.
 *
 * A user explicitly arms a symbol + quantity from the UI. While live prices are
 * active, the engine waits for 1H and 2H intraday MACD hooks plus a neutral/up
 * 4H frame, then places one CNC limit BUY near the latest live price.
 */
object LiveBuyTrackerEngine {
    private const val TAG = "LiveBuyTracker"

    fun onSignal(
        context: Context,
        config: ConfigManager,
        signal: LivePortfolioBars.ReversalSignal
    ) {
        if (!config.livePriceStreamingEnabled) return
        if (!signal.ready || !signal.mtfMacd.buyReady) return
        if (!config.isZerodhaConnected) return

        val appContext = context.applicationContext
        val armed = LiveBuyTrackerStore.load(appContext).filter { it.status == "ARMED" }
        if (armed.isEmpty()) return

        val matched = armed.firstOrNull { entry ->
            LivePortfolioBars.normalizeYahooSymbol(
                YahooFinanceClient.toYahooSymbol(entry.symbol, entry.exchange)
            ) == signal.symbol
        } ?: return

        LiveBuyTrackerStore.updateStatus(
            appContext,
            matched.symbol,
            status = "FIRING",
            message = "Buy signal fired; placing limit order",
            lastPrice = signal.latestPrice
        )
        ScanServiceResultHolder.portfolioVersion++

        thread(isDaemon = true, name = "live-buy-${matched.symbol}") {
            val price = roundToTick(signal.latestPrice * 1.001)
            val reason = signal.mtfMacd.summary
            try {
                val zc = ZerodhaClient(config)
                when (val order = zc.placeOrder(
                    exchange = matched.exchange,
                    tradingsymbol = matched.symbol,
                    transactionType = "BUY",
                    quantity = matched.quantity,
                    orderType = "LIMIT",
                    price = price
                )) {
                    is ZerodhaClient.OrderResult.Success -> {
                        val msg = "Live buy order ${order.orderId} placed @ ${fmt(price)} after $reason"
                        LiveBuyTrackerStore.updateStatus(
                            appContext,
                            matched.symbol,
                            status = "TRIGGERED",
                            message = msg,
                            lastPrice = signal.latestPrice,
                            orderId = order.orderId,
                            firedAt = System.currentTimeMillis()
                        )
                        ScanServiceResultHolder.smoothTurnAlerts.add(
                            StockAlert(
                                symbol = matched.symbol,
                                name = matched.symbol,
                                alertType = AlertType.STRONG_BUY,
                                message = msg,
                                sellScore = 0,
                                cherryPoints = 0,
                                price = signal.latestPrice,
                                macdPhase = "LIVE BUY",
                                source = "zerodha-live-buy"
                            )
                        )
                    }
                    is ZerodhaClient.OrderResult.Failure -> {
                        LiveBuyTrackerStore.updateStatus(
                            appContext,
                            matched.symbol,
                            status = "ARMED",
                            message = "Order failed: ${order.message}; still armed",
                            lastPrice = signal.latestPrice
                        )
                        Log.w(TAG, "Buy order failed for ${matched.symbol}: ${order.message}")
                    }
                }
            } catch (e: Exception) {
                LiveBuyTrackerStore.updateStatus(
                    appContext,
                    matched.symbol,
                    status = "ARMED",
                    message = "Order error: ${e.message ?: "unknown"}; still armed",
                    lastPrice = signal.latestPrice
                )
                Log.e(TAG, "Live buy thread crashed for ${matched.symbol}", e)
            } finally {
                ScanServiceResultHolder.portfolioVersion++
            }
        }
    }

    private fun roundToTick(value: Double): Double =
        Math.round(value * 20.0) / 20.0

    private fun fmt(value: Double): String =
        String.format(Locale.US, "%.2f", value)
}

package com.signalscope.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.signalscope.app.R
import com.signalscope.app.data.AlertType
import com.signalscope.app.data.StockAlert

class AlertAdapter : ListAdapter<StockAlert, AlertAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<StockAlert>() {
            override fun areItemsTheSame(a: StockAlert, b: StockAlert) =
                a.symbol == b.symbol && a.alertType == b.alertType
            override fun areContentsTheSame(a: StockAlert, b: StockAlert) = a == b
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val strip: View = view.findViewById(R.id.alertStrip)
        val symbol: TextView = view.findViewById(R.id.alertSymbol)
        val badge: TextView = view.findViewById(R.id.alertBadge)
        val message: TextView = view.findViewById(R.id.alertMessage)
        val sellScore: TextView = view.findViewById(R.id.alertSellScore)
        val price: TextView = view.findViewById(R.id.alertPrice)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_alert, parent, false)
        return VH(view)
    }

    @Suppress("DEPRECATION")
    override fun onBindViewHolder(h: VH, position: Int) {
        val alert = getItem(position)

        h.symbol.text = alert.symbol
        h.message.text = alert.message
        h.sellScore.text = alert.sellScore.toString()
        h.price.text = "₹${String.format("%.2f", alert.price)}"

        // Color-code by alert type (new intent-based types)
        val (stripColor, badgeText, badgeTextColor) = when (alert.alertType) {
            AlertType.STRONG_EXIT     -> Triple(0xFFdc2626.toInt(), "STRONG EXIT", 0xFFdc2626.toInt())
            AlertType.BOOK_PROFIT     -> Triple(0xFFf97316.toInt(), "BOOK PROFIT", 0xFFf97316.toInt())
            AlertType.PROTECT_CAPITAL -> Triple(0xFFeab308.toInt(), "TREND BROKEN", 0xFFeab308.toInt())
            AlertType.PEAK_WARNING    -> Triple(0xFF8b5cf6.toInt(), "PEAK WARNING", 0xFF8b5cf6.toInt())
            AlertType.SMOOTH_BUY_TURN -> Triple(0xFF0d9488.toInt(), "SMOOTH TURN", 0xFF0d9488.toInt())
            AlertType.GOLDEN_BUY      -> Triple(0xFF0891b2.toInt(), "MACD SETUP", 0xFF0891b2.toInt())
            AlertType.MACD_FLIP       -> Triple(0xFFb91c1c.toInt(), "🚨 MACD FLIP", 0xFFb91c1c.toInt())
            AlertType.STRONG_BUY      -> Triple(0xFF059669.toInt(), "BUY", 0xFF059669.toInt())
            // Legacy types (deprecated but kept for existing alerts in memory)
            else -> Triple(0xFF64748b.toInt(), alert.alertType.name, 0xFF64748b.toInt())
        }

        h.strip.setBackgroundColor(stripColor)
        h.badge.text = badgeText
        h.badge.setTextColor(badgeTextColor)
        h.sellScore.setTextColor(
            if (alert.sellScore >= 35) 0xFFdc2626.toInt()
            else 0xFF94a3b8.toInt()
        )
    }
}

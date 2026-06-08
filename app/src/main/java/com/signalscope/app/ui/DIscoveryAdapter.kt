package com.signalscope.app.ui
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.signalscope.app.data.StockAnalysis

/**
 * Displays discovery scan results with full indicator data,
 * mirroring the Python web dashboard's stock table.
 *
 * Each row shows: Symbol, Price, Pullback/Momentum points, MACD phase + slope,
 * RSI, R:R ratio, ATR risk, signal label.
 */
class DiscoveryAdapter : ListAdapter<StockAnalysis, DiscoveryAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<StockAnalysis>() {
            override fun areItemsTheSame(a: StockAnalysis, b: StockAnalysis) = a.symbol == b.symbol
            override fun areContentsTheSame(a: StockAnalysis, b: StockAnalysis) = a == b
        }
    }

    /** Which currency to show — set by the activity based on scan market */
    var currency: String = "₹"

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val symbol: TextView = view.findViewById(android.R.id.text1) // We'll build programmatic layout
        // Using a programmatic layout for flexibility
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = buildItemLayout(parent)
        return VH(layout)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val s = getItem(position)
        val layout = h.itemView as LinearLayout

        // Symbol + signal (buy-only for discovery — you don't own these stocks)
        (layout.getChildAt(0) as? LinearLayout)?.let { row1 ->
            (row1.getChildAt(0) as? TextView)?.text = s.symbol
            (row1.getChildAt(1) as? TextView)?.apply {
                text = when {
                    s.goldenBuy -> "MACD SETUP"
                    s.momentumScore >= 75 -> "STRONG MOMO"
                    s.pullbackScore >= 75 -> "STRONG PULL"
                    maxOf(s.pullbackScore, s.momentumScore) >= 60 -> "ENTRY"
                    else -> "—"
                }
                setTextColor(when {
                    s.goldenBuy -> 0xFF0891b2.toInt()
                    s.momentumScore >= 75 -> 0xFF059669.toInt()
                    s.pullbackScore >= 75 -> 0xFF2563eb.toInt()
                    maxOf(s.pullbackScore, s.momentumScore) >= 60 -> 0xFF0891b2.toInt()
                    else -> 0xFF94a3b8.toInt()
                })
            }
        }

        // Price
        (layout.getChildAt(1) as? TextView)?.text = "$currency${String.format("%.2f", s.price)}"

        // Scores row (buy-only for discovery — sell scores not relevant for unowned stocks)
        (layout.getChildAt(2) as? LinearLayout)?.let { row2 ->
            (row2.getChildAt(0) as? TextView)?.apply {
                text = "Pb ${s.pullbackScore} · Mo ${s.momentumScore}"
                setTextColor(if (maxOf(s.pullbackScore, s.momentumScore) >= 60) 0xFF059669.toInt() else 0xFF64748b.toInt())
            }
            (row2.getChildAt(1) as? TextView)?.apply {
                text = s.macdPhase
                setTextColor(when (s.macdPhase) {
                    "BUY FLIP", "EARLY BUY", "BULLISH" -> 0xFF059669.toInt()
                    "SELL FLIP", "EARLY SELL", "BEARISH" -> 0xFFdc2626.toInt()
                    else -> 0xFF94a3b8.toInt()
                })
            }
            // Third slot now empty — hide it
            (row2.getChildAt(2) as? TextView)?.apply {
                text = ""
            }
        }

        // Indicators row
        (layout.getChildAt(3) as? TextView)?.apply {
            val rsiStr = if (s.rsi != null) "RSI ${String.format("%.0f", s.rsi)}" else "RSI —"
            val rrStr = if (s.rrRatio > 0) "R:R ${String.format("%.1f", s.rrRatio)}:1" else "R:R —"
            val atrStr = if (s.riskInAtrs > 0) "ATR ${String.format("%.1f", s.riskInAtrs)}" else "ATR —"
            val slopeStr = "d/dt ${if (s.macdSlope > 0) "+" else ""}${String.format("%.3f", s.macdSlope)}"
            text = "$rsiStr · $rrStr · $atrStr · $slopeStr"
        }
    }

    private fun buildItemLayout(parent: ViewGroup): LinearLayout {
        val ctx = parent.context
        val dp = { v: Int -> (v * ctx.resources.displayMetrics.density).toInt() }

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
            setBackgroundColor(0xFF111827.toInt())

            // Row 1: Symbol + Signal badge
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL

                addView(TextView(ctx).apply {
                    id = android.R.id.text1
                    textSize = 14f
                    setTextColor(0xFFf1f5f9.toInt())
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(ctx).apply {
                    textSize = 10f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                })
            })

            // Price
            addView(TextView(ctx).apply {
                textSize = 16f
                setTextColor(0xFFe2e8f0.toInt())
                setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                setPadding(0, dp(2), 0, dp(4))
            })

            // Row 2: Pullback/Momentum points and MACD phase
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL

                for (i in 0..2) {
                    addView(TextView(ctx).apply {
                        textSize = 11f
                        setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                        setPadding(dp(6), dp(2), dp(6), dp(2))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = dp(8) }
                    })
                }
            })

            // Indicators detail line
            addView(TextView(ctx).apply {
                textSize = 10f
                setTextColor(0xFF64748b.toInt())
                setTypeface(android.graphics.Typeface.MONOSPACE)
                setPadding(0, dp(4), 0, 0)
            })
        }
    }
}

package com.signalscope.app.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.ScoringWeights
import com.signalscope.app.network.ZerodhaClient
import kotlinx.coroutines.*

/**
 * Settings screen — enter Zerodha credentials, scan config, and AI settings.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var config: ConfigManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Zerodha fields ──
    private lateinit var etZerodhaApiKey: EditText
    private lateinit var etZerodhaApiSecret: EditText
    private lateinit var btnZerodhaLogin: Button
    private lateinit var btnZerodhaToken: Button
    private lateinit var etZerodhaToken: EditText
    private lateinit var tvZerodhaStatus: TextView
    private lateinit var layoutZerodhaToken: View

    // ── Trading 212 fields (US space) ──
    private lateinit var etT212ApiKey: EditText
    private lateinit var etT212ApiSecret: EditText
    private lateinit var switchT212SimulateOnly: Switch
    private lateinit var btnT212Test: Button
    private lateinit var tvT212Status: TextView

    // ── Config fields ──
    private lateinit var spinnerInterval: Spinner
    private lateinit var switchMarketHours: Switch
    private lateinit var switchVibrate: Switch
    private lateinit var switchAutoCreateGtt: Switch
    private lateinit var switchAutoTrailing: Switch
    private lateinit var switchAutoExit: Switch
    private lateinit var switchMacdFlipNotifications: Switch
    private lateinit var switchAutoSoftSniper: Switch
    private lateinit var etSoftSniperBufferPct: EditText
    private lateinit var etSoftSniperLimitPct: EditText
    private lateinit var etSoftSniperMinGainPct: EditText
    private lateinit var etSoftSniperTightBufferPct: EditText
    private lateinit var etSoftSniperLooseBufferPct: EditText
    private lateinit var etSoftSniperVolSpikeMult: EditText
    private lateinit var etSoftSniperVolWeakMult: EditText
    private lateinit var etSlLimitPct: EditText
    private lateinit var etNewPositionHardStopPct: EditText
    private lateinit var etSlChandelierFixedAtrMultiple: EditText
    private lateinit var etSlTimeDecay10dFactor: EditText
    private lateinit var etSlTimeDecay20dFactor: EditText
    private lateinit var switchAutoTrailingTarget: android.widget.Switch
    private lateinit var etTargetAtrMultiple: EditText
    private lateinit var etTargetMaxDailyStepPct: EditText

    // ── LLM / AI fields ──
    private lateinit var etLlmApiKey: EditText
    private lateinit var spinnerLlmProvider: Spinner
    private lateinit var spinnerLlmModel: Spinner
    private lateinit var tvLlmStatus: TextView

    // ── Scoring Weights ──
    private val weightFields = mutableMapOf<String, EditText>()
    private lateinit var btnResetWeights: Button

    // ── Save ──
    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = ConfigManager(this)
        setContentView(buildSettingsLayout())
        loadCurrentValues()
    }

    /**
     * Builds the settings UI programmatically so you don't need to modify XML layout files.
     * In your real project you can replace this with your activity_settings.xml layout.
     */
    private fun buildSettingsLayout(): View {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val scroll = android.widget.ScrollView(this)
        val inner = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 0, 0, 48)
        }

        // Section headers track their position in `inner` so we can wire
        // collapsibility AFTER the whole layout is built. Each header becomes
        // clickable: tap to hide every view between it and the next header
        // (exclusive). Connections sections (Zerodha / T212 / LLM) start
        // expanded; the rest start expanded for now too — flipping a section
        // to start-collapsed is a one-line change in the loop below if the
        // user later wants the menu pre-collapsed.
        val sectionHeaders = mutableListOf<TextView>()
        val sectionStartIdx = mutableListOf<Int>()
        fun addSection(title: String) {
            sectionStartIdx.add(inner.childCount)
            val header = TextView(this).apply {
                text = "▼ $title"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 32, 0, 8)
                setTextColor(android.graphics.Color.parseColor("#059669"))
                isClickable = true
                isFocusable = true
            }
            inner.addView(header)
            sectionHeaders.add(header)
        }

        fun addLabel(text: String) {
            inner.addView(TextView(this).apply {
                this.text = text
                textSize = 12f
                setPadding(0, 16, 0, 4)
                setTextColor(android.graphics.Color.parseColor("#475569"))
            })
        }

        fun addField(hint: String, isPassword: Boolean = false): EditText {
            val et = EditText(this).apply {
                this.hint = hint
                setTextColor(android.graphics.Color.BLACK)
                setHintTextColor(android.graphics.Color.parseColor("#94a3b8"))
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setPadding(24, 16, 24, 16)
                setBackgroundResource(android.R.drawable.edit_text)
                isLongClickable = true
                isFocusable = true
                isFocusableInTouchMode = true
            }
            inner.addView(et)
            return et
        }

        fun addStatus(): TextView {
            val tv = TextView(this).apply {
                textSize = 12f
                setPadding(0, 8, 0, 0)
                visibility = View.GONE
            }
            inner.addView(tv)
            return tv
        }

        fun addButton(label: String): Button {
            val btn = Button(this).apply {
                text = label
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = 8
                layoutParams = lp
            }
            inner.addView(btn)
            return btn
        }

        // ── Zerodha section ──
        addSection("Zerodha Credentials")
        addLabel("API Key (ZERODHA_API_KEY)")
        etZerodhaApiKey = addField("Enter Zerodha API Key")
        addLabel("API Secret (ZERODHA_API_SECRET)")
        etZerodhaApiSecret = addField("Enter Zerodha API Secret", isPassword = true)

        inner.addView(TextView(this).apply {
            text = "After saving, use 'Login with Zerodha' to connect your account daily"
            textSize = 11f
            setPadding(0, 4, 0, 8)
            setTextColor(android.graphics.Color.parseColor("#94a3b8"))
        })

        btnZerodhaLogin = addButton("🔗 Open Zerodha Login")
        tvZerodhaStatus = addStatus()

        // Token paste section
        layoutZerodhaToken = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            visibility = View.GONE
        }
        addLabel("Paste redirect URL or request_token from Zerodha:")
        etZerodhaToken = addField("Paste full URL or token here")
        btnZerodhaToken = addButton("✓ Submit Token")
        inner.addView(layoutZerodhaToken)

        // ── Trading 212 section (US space) ──
        addSection("🇺🇸 Trading 212 Credentials  (US space)")
        inner.addView(TextView(this).apply {
            text = "Paste your Trading 212 API key from the live or demo dashboard.\n" +
                   "T212 docs: settings → API → generate token.\n" +
                   "Phase 2A: read-only access (account balance, portfolio).\n" +
                   "Order placement (Phase 2B) is not enabled yet."
            textSize = 11f
            setPadding(0, 4, 0, 8)
            setTextColor(android.graphics.Color.parseColor("#94a3b8"))
        })
        addLabel("API Key (T212_API_KEY)")
        etT212ApiKey = addField("Paste Trading 212 API key", isPassword = true)
        addLabel("API Secret (T212_API_SECRET)  —  REQUIRED.  T212 uses HTTP Basic auth = base64(key:secret).")
        etT212ApiSecret = addField("Paste Trading 212 API secret", isPassword = true)

        switchT212SimulateOnly = Switch(this).apply {
            text = "🎭 Simulate-only mode  (recommended ON until trust is built)"
            setPadding(0, 12, 0, 0)
        }
        inner.addView(switchT212SimulateOnly)
        inner.addView(TextView(this).apply {
            text = "When ON: SL / target / buy orders are LOGGED to the GTT audit but NEVER actually sent to T212.\n" +
                   "Watch the audit log for ~1 week. Once the decisions look sane, flip this OFF — then orders go live.\n" +
                   "⚠ Live mode places real orders on your real T212 account. There is no demo fallback."
            textSize = 11f
            setPadding(0, 4, 0, 8)
            setTextColor(android.graphics.Color.parseColor("#94a3b8"))
        })

        btnT212Test = addButton("🔌 Test connection")
        tvT212Status = addStatus()

        // ── Scan config section ──
        addSection("Scan Configuration")

        addLabel("Scan interval:")
        spinnerInterval = Spinner(this)
        val intervals = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            arrayOf("5 minutes", "10 minutes", "15 minutes", "30 minutes", "60 minutes"))
        intervals.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerInterval.adapter = intervals
        inner.addView(spinnerInterval)

        switchMarketHours = Switch(this).apply {
            text = "Scan during market hours only (9:15–15:30 IST)"
            setPadding(0, 16, 0, 0)
        }
        inner.addView(switchMarketHours)

        switchVibrate = Switch(this).apply {
            text = "Vibrate on alerts"
            setPadding(0, 8, 0, 0)
        }
        inner.addView(switchVibrate)

        // ── Automated GTT / Trailing SL section ──
        addSection("🤖 Automated GTT & Trailing SL  (15:20 IST daily tick)")

        inner.addView(TextView(this).apply {
            text = "These run automatically at 15:20 IST on weekdays, 9 min before market close.\n" +
                   "Ordered safest → hottest: create (server-side, conditional) → trail (nudges GTT up) → exit (live sell order)."
            textSize = 11f
            setPadding(0, 0, 0, 8)
            setTextColor(android.graphics.Color.parseColor("#94a3b8"))
        })

        switchAutoCreateGtt = Switch(this).apply {
            text = "Auto-create OCO GTT for new holdings"
            setPadding(0, 12, 0, 0)
        }
        inner.addView(switchAutoCreateGtt)
        inner.addView(TextView(this).apply {
            text = "If you buy a stock and it has NO GTT by 15:20, the system creates one:\n" +
                   "  • SL  = support level  (floored by your hard-SL setting)\n" +
                   "  • TGT = resistance  (or wave projection ceiling if available)\n" +
                   "Protects gap-down overnight risk for same-day buys automatically."
            textSize = 10f
            setPadding(32, 4, 0, 8)
            setTextColor(android.graphics.Color.parseColor("#64748b"))
        })

        inner.addView(TextView(this).apply {
            text = "New-position hard SL (%) - default 5.0"
            textSize = 11f
            setPadding(32, 8, 0, 2)
            setTextColor(android.graphics.Color.parseColor("#374151"))
        })
        etNewPositionHardStopPct = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "5.0"
            textSize = 13f
        }
        inner.addView(etNewPositionHardStopPct)

        switchAutoTrailing = Switch(this).apply {
            text = "Auto-trail SL upward as stock rises"
            setPadding(0, 12, 0, 0)
        }
        inner.addView(switchAutoTrailing)
        inner.addView(TextView(this).apply {
            text = "Raises your existing SL GTT using the Chandelier exit formula (ATR-based).\n" +
                   "Monotonic — never lowers an SL. Skipped for the first 5 days after entry."
            textSize = 10f
            setPadding(32, 4, 0, 8)
            setTextColor(android.graphics.Color.parseColor("#64748b"))
        })

        inner.addView(TextView(this).apply {
            text = "Stop-loss Chandelier Settings"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(16, 16, 0, 4)
            setTextColor(android.graphics.Color.parseColor("#1f2937"))
        })

        inner.addView(TextView(this).apply {
            text = "SL limit pad (%) - default 2.0"
            textSize = 11f
            setPadding(32, 8, 0, 2)
            setTextColor(android.graphics.Color.parseColor("#374151"))
        })
        etSlLimitPct = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "2.0"
            textSize = 13f
        }
        inner.addView(etSlLimitPct)

        inner.addView(TextView(this).apply {
            text = "Fixed chandelier ATR multiple (0 = automatic tiers; 1.2 = tighter/conservative)"
            textSize = 11f
            setPadding(32, 8, 0, 2)
            setTextColor(android.graphics.Color.parseColor("#374151"))
        })
        etSlChandelierFixedAtrMultiple = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "0.0"
            textSize = 13f
        }
        inner.addView(etSlChandelierFixedAtrMultiple)

        inner.addView(TextView(this).apply {
            text = "10-day plateau decay factor (default 0.80)"
            textSize = 11f
            setPadding(32, 8, 0, 2)
            setTextColor(android.graphics.Color.parseColor("#374151"))
        })
        etSlTimeDecay10dFactor = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "0.80"
            textSize = 13f
        }
        inner.addView(etSlTimeDecay10dFactor)

        inner.addView(TextView(this).apply {
            text = "20-day plateau decay factor (default 0.60)"
            textSize = 11f
            setPadding(32, 8, 0, 2)
            setTextColor(android.graphics.Color.parseColor("#374151"))
        })
        etSlTimeDecay20dFactor = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "0.60"
            textSize = 13f
        }
        inner.addView(etSlTimeDecay20dFactor)

        inner.addView(TextView(this).apply {
            text = "Automatic tiers are 3.0 / 2.5 / 2.0 / 1.5 / 1.0 ATR as gains grow. Set a fixed ATR multiple only when you want one style across all holdings and all spaces."
            textSize = 10f
            setPadding(32, 4, 0, 12)
            setTextColor(android.graphics.Color.parseColor("#94a3b8"))
        })

        switchAutoExit = Switch(this).apply {
            text = "🔥 Auto-exit via MACD watchdog  (places LIVE SELL orders)"
            setPadding(0, 12, 0, 0)
        }
        inner.addView(switchAutoExit)
        inner.addView(TextView(this).apply {
            text = "⚠ THIS is the genuinely hot switch — places a live SELL order immediately.\n" +
                   "Fires only when: histogram < 50% of peak AND negative ≥ adaptive filter days AND gain > 3%.\n" +
                   "Places limit → limit → market ladder. Unlike GTT create/trail, this cannot be undone.\n" +
                   "Enable only after you've watched the watchdog log in dry-run mode for a few weeks."
            textSize = 10f
            setPadding(32, 4, 0, 8)
            setTextColor(android.graphics.Color.parseColor("#dc2626"))
        })

        switchMacdFlipNotifications = Switch(this).apply {
            text = "🔔 MACD-flip push notifications at 15:20"
            setPadding(0, 12, 0, 0)
        }
        inner.addView(switchMacdFlipNotifications)
        inner.addView(TextView(this).apply {
            text = "When the MACD histogram watchdog confirms a sell at 15:20, send a high-priority phone notification.\n" +
                   "Turn OFF for vacation mode — the watchdog still records the alert in the in-app history\n" +
                   "and still places sniper GTTs (if enabled), it just won't buzz your phone."
            textSize = 10f
            setPadding(32, 4, 0, 8)
            setTextColor(android.graphics.Color.parseColor("#64748b"))
        })

        // ── Tier 1.5 Soft Sniper ────────────────────────────────────────────
        // Auto-places a SELL GTT ~3% below LTP the moment a BOOK_PROFIT alert
        // fires intraday — bridges the gap between the 15-min advisory and the
        // 7+ day hard watchdog. Cleanly replaced by the hard sniper if the
        // watchdog later confirms (replaceSniperGtt cancels existing SELL GTTs).
        switchAutoSoftSniper = Switch(this).apply {
            text = "🎯 Tier 1.5 Soft Sniper (auto-GTT on BOOK_PROFIT)"
            setPadding(0, 12, 0, 0)
        }
        inner.addView(switchAutoSoftSniper)
        inner.addView(TextView(this).apply {
            text = "When a BOOK_PROFIT alert fires (SELL FLIP + score thresholds + gain ≥ 3%),\n" +
                   "automatically place a single-leg SELL GTT at LTP × (1 − buffer%).\n" +
                   "Fires within 15 min of phase change — much faster than waiting for the\n" +
                   "daily watchdog (which needs minBarsFilter consecutive negative-histogram days).\n" +
                   "If the hard watchdog later confirms, the soft GTT is auto-replaced by the\n" +
                   "tighter 1.5%-buffer sniper. Default OFF — opt-in (places real GTTs)."
            textSize = 10f
            setPadding(32, 4, 0, 8)
            setTextColor(android.graphics.Color.parseColor("#64748b"))
        })

        // Three numeric tunables. Plain EditText fields (consistent with other
        // numeric settings in this activity) — defaults are sensible for most
        // users; power users can tune per their risk tolerance.
        inner.addView(TextView(this).apply {
            text = "Trigger buffer below LTP (%)  — default 3.0"
            textSize = 11f
            setPadding(32, 8, 0, 2)
            setTextColor(android.graphics.Color.parseColor("#374151"))
        })
        etSoftSniperBufferPct = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "3.0"
            textSize = 13f
        }
        inner.addView(etSoftSniperBufferPct)

        inner.addView(TextView(this).apply {
            text = "Limit price pad inside trigger (%)  — default 0.5"
            textSize = 11f
            setPadding(32, 8, 0, 2)
            setTextColor(android.graphics.Color.parseColor("#374151"))
        })
        etSoftSniperLimitPct = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "0.5"
            textSize = 13f
        }
        inner.addView(etSoftSniperLimitPct)

        inner.addView(TextView(this).apply {
            text = "Minimum gain to trigger (%)  — default 5.0"
            textSize = 11f
            setPadding(32, 8, 0, 2)
            setTextColor(android.graphics.Color.parseColor("#374151"))
        })
        etSoftSniperMinGainPct = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "5.0"
            textSize = 13f
        }
        inner.addView(etSoftSniperMinGainPct)
        inner.addView(TextView(this).apply {
            text = "Below this gain, the BOOK_PROFIT alert still fires but no GTT is placed —\n" +
                   "the position isn't yet worth protecting with broker-side automation."
            textSize = 10f
            setPadding(32, 4, 0, 12)
            setTextColor(android.graphics.Color.parseColor("#94a3b8"))
        })

        // ── Dynamic Soft-Sniper Buffer (volume-aware) ──
        inner.addView(TextView(this).apply {
            text = "Dynamic Buffer (volume-aware tiers)"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(16, 16, 0, 4)
            setTextColor(android.graphics.Color.parseColor("#1f2937"))
        })

        inner.addView(TextView(this).apply {
            text = "Tight buffer (%) — when MACD flips red on a high-volume bar (default 1.5)"
            textSize = 11f
            setPadding(32, 8, 0, 2)
            setTextColor(android.graphics.Color.parseColor("#374151"))
        })
        etSoftSniperTightBufferPct = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "1.5"
            textSize = 13f
        }
        inner.addView(etSoftSniperTightBufferPct)

        inner.addView(TextView(this).apply {
            text = "Loose buffer (%) — when score crossed but no volume/OBV confirmation (default 4.0)"
            textSize = 11f
            setPadding(32, 8, 0, 2)
            setTextColor(android.graphics.Color.parseColor("#374151"))
        })
        etSoftSniperLooseBufferPct = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "4.0"
            textSize = 13f
        }
        inner.addView(etSoftSniperLooseBufferPct)

        inner.addView(TextView(this).apply {
            text = "Volume spike multiple — vol/avg20 ≥ this counts as confirmed flush (default 1.30)"
            textSize = 11f
            setPadding(32, 8, 0, 2)
            setTextColor(android.graphics.Color.parseColor("#374151"))
        })
        etSoftSniperVolSpikeMult = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "1.30"
            textSize = 13f
        }
        inner.addView(etSoftSniperVolSpikeMult)

        inner.addView(TextView(this).apply {
            text = "Volume weak multiple — vol/avg20 < this SKIPS placement (default 0.70)"
            textSize = 11f
            setPadding(32, 8, 0, 2)
            setTextColor(android.graphics.Color.parseColor("#374151"))
        })
        etSoftSniperVolWeakMult = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "0.70"
            textSize = 13f
        }
        inner.addView(etSoftSniperVolWeakMult)

        inner.addView(TextView(this).apply {
            text = "TIGHT fires when smart money is flushing (vol spike + MACD red).\n" +
                   "NORMAL = the buffer above (3%), used on OBV divergence alone.\n" +
                   "LOOSE used when score crossed but no confirmation. Below WEAK threshold,\n" +
                   "soft sniper SKIPS — likely false positive on thin trade."
            textSize = 10f
            setPadding(32, 4, 0, 12)
            setTextColor(android.graphics.Color.parseColor("#94a3b8"))
        })

        // ── Trailing OCO Target (asymmetric upside trail) ──
        inner.addView(TextView(this).apply {
            text = "Trailing OCO Target (upside)"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(16, 16, 0, 4)
            setTextColor(android.graphics.Color.parseColor("#1f2937"))
        })

        switchAutoTrailingTarget = android.widget.Switch(this).apply {
            text = "Trail OCO target up daily"
            textSize = 13f
            setPadding(16, 4, 0, 4)
        }
        inner.addView(switchAutoTrailingTarget)

        inner.addView(TextView(this).apply {
            text = "Target ATR multiple (default 2.0) — distance from LTP for the new target"
            textSize = 11f
            setPadding(32, 8, 0, 2)
            setTextColor(android.graphics.Color.parseColor("#374151"))
        })
        etTargetAtrMultiple = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "2.0"
            textSize = 13f
        }
        inner.addView(etTargetAtrMultiple)

        inner.addView(TextView(this).apply {
            text = "Max daily step (%) — anti-spike cap on single-day target jump (default 8.0)"
            textSize = 11f
            setPadding(32, 8, 0, 2)
            setTextColor(android.graphics.Color.parseColor("#374151"))
        })
        etTargetMaxDailyStepPct = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "8.0"
            textSize = 13f
        }
        inner.addView(etTargetMaxDailyStepPct)

        inner.addView(TextView(this).apply {
            text = "Each daily tick: candidate = LTP + (ATR mult × ATR). Target ratchets UP only,\n" +
                   "never down. Daily step capped to prevent flash-crash bounce candles from\n" +
                   "pushing the target unrealistically far. Bumps < 1% are skipped to avoid\n" +
                   "GTT API spam. When OFF, daily logs show 'would update' lines so you can\n" +
                   "preview behaviour before flipping the switch on."
            textSize = 10f
            setPadding(32, 4, 0, 12)
            setTextColor(android.graphics.Color.parseColor("#94a3b8"))
        })

        // ── AI / LLM section ──
        addSection("AI Stock Analysis (Optional)")

        inner.addView(TextView(this).apply {
            text = "Enables AI-powered pullback analysis and stock outlook summaries.\nUses Google News for headlines + your LLM API for summarization."
            textSize = 11f
            setPadding(0, 0, 0, 8)
            setTextColor(android.graphics.Color.parseColor("#94a3b8"))
        })

        addLabel("API Key (OpenAI or Anthropic)")
        etLlmApiKey = addField("sk-... or sk-ant-...", isPassword = true)

        addLabel("Provider")
        spinnerLlmProvider = Spinner(this)
        val providers = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            arrayOf("OpenAI (ChatGPT)", "Anthropic (Claude)"))
        providers.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLlmProvider.adapter = providers
        inner.addView(spinnerLlmProvider)

        addLabel("Model")
        spinnerLlmModel = Spinner(this)
        val models = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            arrayOf("gpt-4o-mini (cheapest)", "gpt-4o", "claude-sonnet-4-20250514", "claude-haiku-4-20250414"))
        models.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLlmModel.adapter = models
        inner.addView(spinnerLlmModel)

        tvLlmStatus = addStatus()
        if (config.hasLlmCredentials) {
            tvLlmStatus.text = "✅ AI analysis enabled (${config.llmProvider} / ${config.llmModel})"
            tvLlmStatus.setTextColor(Color.parseColor("#059669"))
            tvLlmStatus.visibility = View.VISIBLE
        }

        // ═══════════════════════════════════════════════════════
        // SCORING WEIGHTS — collapsible sections
        // ═══════════════════════════════════════════════════════
        val defaults = ScoringWeights() // recommended defaults

        fun addWeightSection(title: String, color: String): LinearLayout {
            val header = TextView(this).apply {
                text = "▶  $title"
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 36, 0, 8)
                setTextColor(Color.parseColor(color))
            }
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(16, 0, 0, 16)
            }
            header.setOnClickListener {
                if (body.visibility == View.GONE) {
                    body.visibility = View.VISIBLE
                    header.text = header.text.toString().replace("▶", "▼")
                } else {
                    body.visibility = View.GONE
                    header.text = header.text.toString().replace("▼", "▶")
                }
            }
            inner.addView(header)
            inner.addView(body)
            return body
        }

        fun addWeightRow(parent: LinearLayout, key: String, label: String, default: Number) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 6, 0, 6)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }

            val lbl = TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(Color.parseColor("#374151"))
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }

            val et = EditText(this).apply {
                inputType = if (default is Double) {
                    val signed = if (key == "minSlopeMagnitude" || key == "setupSma200MinSlope") InputType.TYPE_NUMBER_FLAG_SIGNED else 0
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or signed
                } else {
                    InputType.TYPE_CLASS_NUMBER
                }
                setTextColor(Color.BLACK)
                textSize = 13f
                setPadding(16, 8, 16, 8)
                setBackgroundResource(android.R.drawable.edit_text)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.4f)
            }

            val rec = TextView(this).apply {
                text = "  [$default]"
                textSize = 10f
                setTextColor(Color.parseColor("#9ca3af"))
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            }

            row.addView(lbl)
            row.addView(et)
            row.addView(rec)
            parent.addView(row)
            weightFields[key] = et
        }

        // Slider row — SeekBar-backed tunable with a live value readout.
        // Internally still writes to a hidden EditText so the existing
        // loadWeightValues / buildWeightsFromUI plumbing works unchanged.
        fun addWeightSlider(
            parent: LinearLayout,
            key: String,
            label: String,
            default: Number,
            min: Double,
            max: Double,
            step: Double
        ) {
            val isDouble = default is Double || step < 1.0
            val steps = ((max - min) / step).toInt().coerceAtLeast(1)
            val defaultVal = (default.toDouble()).coerceIn(min, max)
            val defaultProgress = (((defaultVal - min) / step).toInt()).coerceIn(0, steps)

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 8)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }

            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }
            val lbl = TextView(this).apply {
                this.text = label; textSize = 12f
                setTextColor(Color.parseColor("#374151"))
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            val valTv = TextView(this).apply {
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#2563eb"))
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            }
            val rec = TextView(this).apply {
                this.text = "  [$default]"; textSize = 10f
                setTextColor(Color.parseColor("#9ca3af"))
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            }
            header.addView(lbl); header.addView(valTv); header.addView(rec)

            // Hidden EditText — backs the existing read/write plumbing
            val et = EditText(this).apply {
                inputType = if (isDouble)
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                else InputType.TYPE_CLASS_NUMBER
                visibility = View.GONE
            }

            val seek = SeekBar(this).apply {
                this.max = steps
                progress = defaultProgress
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }

            fun progressToValue(p: Int): String {
                val raw = min + p * step
                return if (isDouble) String.format("%.3f", raw).trimEnd('0').trimEnd('.')
                else raw.toInt().toString()
            }
            // Seed EditText + readout
            valTv.text = progressToValue(defaultProgress)
            et.setText(valTv.text)

            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = progressToValue(p)
                    valTv.text = v
                    et.setText(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })

            // Keep SeekBar in sync when loadWeightValues() writes to EditText
            et.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val d = s?.toString()?.toDoubleOrNull() ?: return
                    val p = (((d - min) / step).toInt()).coerceIn(0, steps)
                    if (seek.progress != p) seek.progress = p
                    if (valTv.text.toString() != s.toString()) valTv.text = s.toString()
                }
            })

            container.addView(header)
            container.addView(seek)
            container.addView(et)
            parent.addView(container)
            weightFields[key] = et
        }

        // Helper to add schema description text inside a section
        fun addSchemaNote(parent: LinearLayout, text: String) {
            parent.addView(TextView(this).apply {
                this.text = text
                textSize = 10f
                setPadding(0, 2, 0, 8)
                setTextColor(Color.parseColor("#6b7280"))
                setLineSpacing(2f, 1f)
            })
        }

        fun addSubHeader(parent: LinearLayout, text: String) {
            parent.addView(TextView(this).apply {
                this.text = text; textSize = 12f; setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#475569")); setPadding(0, 12, 0, 4)
            })
        }

        // ── Section header ──
        addSection("Scoring Weights")
        inner.addView(TextView(this).apply {
            text = "Adjust indicator weights used for Buy, Profit Booking, and Capital Protection scores.\nValues in [brackets] are recommended defaults. Tap a section header to expand."
            textSize = 11f
            setPadding(0, 0, 0, 8)
            setTextColor(Color.parseColor("#94a3b8"))
        })

        // ══════════════════════════════════════════════════
        // BUY SCORE WEIGHTS
        // ══════════════════════════════════════════════════
        val buySection = addWeightSection("Buy Score Weights (max ~125)", "#2563eb")

        addSchemaNote(buySection,
            "Identifies bullish entry opportunities. Score = sum of all triggered components.\n" +
            "STRONG BUY ≥ 75 | MODERATE BUY ≥ 60 | NO SIGNAL < 60")

        // -- SMA --
        addSubHeader(buySection, "1. SMA Trend")
        addSchemaNote(buySection,
            "IF price > SMA200 → award pts. Confirms long-term uptrend.\n" +
            "SMA50 used as fallback when stock has < 200 candles of history.")
        addWeightRow(buySection, "buySma200Pts", "SMA200 points", defaults.buySma200Pts)
        addWeightRow(buySection, "buySma50Pts", "SMA50 fallback pts", defaults.buySma50Pts)

        // -- MACD --
        addSubHeader(buySection, "2. MACD Inflection (mutually exclusive)")
        addSchemaNote(buySection,
            "Only the HIGHEST matching signal scores. Never summed.\n" +
            "Qualified MACD setup: MACD acceleration + angle inside setup band + MACD %ile not stretched + SMA200 slope above setup minimum → strongest\n" +
            "Zero-cross up: MACD crosses above zero with positive acceleration\n" +
            "Slope cross up: MACD slope flips from negative to positive\n" +
            "Early buy: MACD decelerating downward (bottom approaching)\n" +
            "Pctl bonus: added when MACD is in bottom 25% of its 1Y range")
        addWeightRow(buySection, "buyGoldenBuyPts", "Qualified MACD setup", defaults.buyGoldenBuyPts)
        addWeightRow(buySection, "buyGoldenBonus", "Setup bonus", defaults.buyGoldenBonus)
        addWeightRow(buySection, "buyMacdZeroCrossUpPts", "MACD zero-cross up", defaults.buyMacdZeroCrossUpPts)
        addWeightRow(buySection, "buySlopeCrossUpPts", "Slope cross up", defaults.buySlopeCrossUpPts)
        addWeightRow(buySection, "buyEarlyBuyPts", "Early buy", defaults.buyEarlyBuyPts)
        addWeightRow(buySection, "buyMacdPctlBonus", "MACD pctl bonus", defaults.buyMacdPctlBonus)
        addWeightRow(buySection, "buyMacdPctlThreshold", "Pctl threshold ≤", defaults.buyMacdPctlThreshold)

        // -- MACD Angle Tunables (kept as plain editable defaults here; live sliders are in the Setups tab) --
        addSubHeader(buySection, "Setup Thresholds (defaults for Setups tab)")
        addSchemaNote(buySection,
            "These values act as the DEFAULTS for the Setups-tab sliders.\n" +
            "MACD angle MIN: signed lower bound for atan(d(MACD)/dt). Negative catches MACD still falling but curving upward.\n" +
            "MACD angle MAX for qualified setup: positive cap in degrees. High caps allow sudden-news surges without raw-slope clipping.\n" +
            "d(SMA200)/dt MIN: default long-term-trend floor for Setups and qualified setup.\n" +
            "MACD %ile max: default upper bound for the current MACD's position in its 1Y range.\n" +
            "Live adjustments happen on the Setups tab; those reset to these defaults on app reopen.")
        addWeightRow(buySection, "minSlopeMagnitude", "MACD angle minimum (°)", defaults.minSlopeMagnitude)
        addWeightRow(buySection, "goldenBuyMaxSlope", "MACD angle max (setup °)", defaults.goldenBuyMaxSlope)
        addWeightRow(buySection, "setupSma200MinSlope", "d(SMA200)/dt minimum", defaults.setupSma200MinSlope)
        addWeightRow(buySection, "setupMacdPctlMax", "MACD %ile ≤", defaults.setupMacdPctlMax)

        // -- RSI --
        addSubHeader(buySection, "3. RSI Oversold + Momentum Flip")
        addSchemaNote(buySection,
            "Graduated scoring: best zone is RSI 25-35 (oversold recovery).\n" +
            "RSI 25-35 → 5-20 pts | RSI 35-55 → scales down | RSI < 25 → 3 pts | RSI > 55 → 0\n" +
            "Flip bonus: awarded when RSI turns UP today (RSI today > yesterday\n" +
            "AND yesterday ≤ 2 days ago) while RSI is in the flip range.\n" +
            "This catches the exact reversal day from oversold territory.")
        addWeightRow(buySection, "buyRsiMaxPts", "RSI max pts", defaults.buyRsiMaxPts)
        addWeightRow(buySection, "buyRsiFlipBonus", "RSI flip bonus", defaults.buyRsiFlipBonus)
        addWeightRow(buySection, "buyRsiFlipLow", "RSI flip range low", defaults.buyRsiFlipLow)
        addWeightRow(buySection, "buyRsiFlipHigh", "RSI flip range high", defaults.buyRsiFlipHigh)

        // -- BB --
        addSubHeader(buySection, "4. Bollinger Bands")
        addSchemaNote(buySection,
            "IF price ≤ mid-band OR touched lower band in last 5 days → base pts.\n" +
            "Extra bonus if price actually hit the lower band (2σ stretch down).")
        addWeightRow(buySection, "buyBbBasePts", "BB base pts", defaults.buyBbBasePts)
        addWeightRow(buySection, "buyBbLowerBonus", "BB lower bonus", defaults.buyBbLowerBonus)

        // -- ADX --
        addSubHeader(buySection, "5. ADX Trend Strength")
        addSchemaNote(buySection,
            "IF ADX > strong threshold AND +DI > -DI (bullish) → base pts.\n" +
            "Extra bonus if ADX > very strong threshold (powerful trend).\n" +
            "ADX measures trend STRENGTH, not direction. DI lines give direction.")
        addWeightRow(buySection, "buyAdxStrongThreshold", "ADX strong threshold", defaults.buyAdxStrongThreshold)
        addWeightRow(buySection, "buyAdxVeryStrongThreshold", "ADX v.strong threshold", defaults.buyAdxVeryStrongThreshold)
        addWeightRow(buySection, "buyAdxBasePts", "ADX base pts", defaults.buyAdxBasePts)
        addWeightRow(buySection, "buyAdxVeryStrongBonus", "ADX v.strong bonus", defaults.buyAdxVeryStrongBonus)

        // -- OBV --
        addSubHeader(buySection, "6. OBV (On-Balance Volume)")
        addSchemaNote(buySection,
            "IF current OBV > OBV 5 days ago AND > OBV 20 days ago → pts.\n" +
            "Confirms accumulation — smart money buying on up days.")
        addWeightRow(buySection, "buyObvPts", "OBV pts", defaults.buyObvPts)

        // -- EMA --
        addSubHeader(buySection, "7. EMA(21) Proximity")
        addSchemaNote(buySection,
            "Measures how far price is from 21-day EMA. Best entry = slight pullback.\n" +
            "Below EMA by 0-3% → max pts (discount buy) | At EMA → 80% | Stretched > 7% → 0")
        addWeightRow(buySection, "buyEmaMaxPts", "EMA proximity max", defaults.buyEmaMaxPts)

        // -- Thresholds --
        addSubHeader(buySection, "Signal Thresholds")
        addSchemaNote(buySection,
            "buyScore ≥ strong → STRONG BUY | buyScore ≥ moderate → MODERATE BUY\n" +
            "Lower these to be more aggressive, raise to be more selective.")
        addWeightRow(buySection, "buyStrongThreshold", "Strong buy ≥", defaults.buyStrongThreshold)
        addWeightRow(buySection, "buyModerateThreshold", "Moderate buy ≥", defaults.buyModerateThreshold)

        // ══════════════════════════════════════════════════
        // PROFIT BOOKING WEIGHTS
        // ══════════════════════════════════════════════════
        val profitSection = addWeightSection("Profit Booking Weights (max ~63)", "#f97316")

        addSchemaNote(profitSection,
            "Intent: sell at MACD slope peak to capture maximum profit.\n" +
            "Only fires when price > SMA200 (uptrend intact — you're selling strength).\n" +
            "Activation: profitScore ≥ threshold AND price above SMA200.")

        // -- MACD --
        addSubHeader(profitSection, "1. MACD Momentum (mutually exclusive)")
        addSchemaNote(profitSection,
            "Only the HIGHEST matching signal scores. Never summed.\n" +
            "Slope cross down: MACD slope just flipped negative → PEAK (sell now)\n" +
            "Early sell: MACD still positive but decelerating → APPROACHING peak\n" +
            "Zero-cross down: MACD crossed below zero → too late for max profit\n" +
            "Pctl bonus: added when MACD is in top 25% of 1Y range (high = ripe)")
        addWeightRow(profitSection, "profitSlopeCrossDnPts", "Slope cross down", defaults.profitSlopeCrossDnPts)
        addWeightRow(profitSection, "profitEarlySellPts", "Early sell", defaults.profitEarlySellPts)
        addWeightRow(profitSection, "profitMacdZeroCrossDnPts", "MACD zero-cross dn", defaults.profitMacdZeroCrossDnPts)
        addWeightRow(profitSection, "profitMacdPctlBonus", "MACD pctl bonus", defaults.profitMacdPctlBonus)
        addWeightRow(profitSection, "profitMacdPctlThreshold", "Pctl threshold ≥", defaults.profitMacdPctlThreshold)

        // -- RSI --
        addSubHeader(profitSection, "2. RSI Overbought + Momentum Flip")
        addSchemaNote(profitSection,
            "Tiered scoring — higher RSI = more pts (stronger sell signal).\n" +
            "RSI > 85 → extreme | ≥ 70 → classic overbought | ≥ 65 → mild | ≥ 60 → noise\n" +
            "Flip bonus: awarded when RSI turns DOWN today (RSI today < yesterday\n" +
            "AND yesterday ≥ 2 days ago) while RSI is already in an overbought tier.\n" +
            "This catches the exact day momentum peaks and starts to fade.")
        addWeightRow(profitSection, "profitRsiExtremePts", "RSI extreme pts", defaults.profitRsiExtremePts)
        addWeightRow(profitSection, "profitRsiOverboughtPts", "RSI overbought pts", defaults.profitRsiOverboughtPts)
        addWeightRow(profitSection, "profitRsiMildPts", "RSI mild pts", defaults.profitRsiMildPts)
        addWeightRow(profitSection, "profitRsiNoisePts", "RSI noise pts", defaults.profitRsiNoisePts)
        addWeightRow(profitSection, "profitRsiFlipBonus", "RSI flip bonus", defaults.profitRsiFlipBonus)
        addWeightRow(profitSection, "profitRsiExtremeThreshold", "RSI extreme ≥", defaults.profitRsiExtremeThreshold)
        addWeightRow(profitSection, "profitRsiOverboughtThreshold", "RSI overbought ≥", defaults.profitRsiOverboughtThreshold)
        addWeightRow(profitSection, "profitRsiMildThreshold", "RSI mild ≥", defaults.profitRsiMildThreshold)
        addWeightRow(profitSection, "profitRsiNoiseThreshold", "RSI noise ≥", defaults.profitRsiNoiseThreshold)

        // -- BB + Activation --
        addSubHeader(profitSection, "3. Bollinger Stretch")
        addSchemaNote(profitSection,
            "IF price ≥ upper band → pts (stretched beyond 2σ, likely to snap back).\n" +
            "Softer: touched upper band in last 5 days → reduced pts.")
        addWeightRow(profitSection, "profitBbUpperPts", "BB upper pts", defaults.profitBbUpperPts)
        addWeightRow(profitSection, "profitBbTouchedPts", "BB touched pts", defaults.profitBbTouchedPts)

        addSubHeader(profitSection, "Activation")
        addSchemaNote(profitSection,
            "Profit Booking only fires when profitScore ≥ this threshold AND price > SMA200.\n" +
            "Lower = more sensitive (earlier alerts). Raise = fewer, higher-confidence alerts.")
        addWeightRow(profitSection, "profitActivationThreshold", "Activation threshold ≥", defaults.profitActivationThreshold)

        // ══════════════════════════════════════════════════
        // CAPITAL PROTECTION WEIGHTS
        // ══════════════════════════════════════════════════
        val protectSection = addWeightSection("Capital Protection Weights (max ~58)", "#eab308")

        addSchemaNote(protectSection,
            "Intent: exit before structural damage — the long-term trend is breaking.\n" +
            "Fires when price < SMA200 or bearish ADX is strong.\n" +
            "Activation: protectScore ≥ threshold AND (price < SMA200 OR ADX bearish).")

        // -- SMA --
        addSubHeader(protectSection, "1. Price Below SMA200")
        addSchemaNote(protectSection,
            "IF price < SMA200 → primary structural break signal.\n" +
            "Slope bonus: extra pts if SMA200 itself is declining (trend worsening).\n" +
            "This is the heaviest component — SMA200 break = trend reversal.")
        addWeightRow(protectSection, "protectSma200Pts", "Price < SMA200 pts", defaults.protectSma200Pts)
        addWeightRow(protectSection, "protectSma200SlopeBonus", "SMA slope bonus", defaults.protectSma200SlopeBonus)

        // -- ADX --
        addSubHeader(protectSection, "2. ADX Bearish (mutually exclusive)")
        addSchemaNote(protectSection,
            "IF -DI > +DI (bearish) AND ADX is strong → confirms directional selling.\n" +
            "ADX > 30 + bearish → strong pts | ADX > 25 + bearish → weaker pts.\n" +
            "Only the highest tier scores. Not summed.")
        addWeightRow(protectSection, "protectAdxStrongPts", "ADX strong pts", defaults.protectAdxStrongPts)
        addWeightRow(protectSection, "protectAdxWeakPts", "ADX weak pts", defaults.protectAdxWeakPts)
        addWeightRow(protectSection, "protectAdxStrongThreshold", "ADX strong ≥", defaults.protectAdxStrongThreshold)
        addWeightRow(protectSection, "protectAdxWeakThreshold", "ADX weak ≥", defaults.protectAdxWeakThreshold)

        // -- OBV + MACD + Activation --
        addSubHeader(protectSection, "3. OBV + MACD + Activation")
        addSchemaNote(protectSection,
            "OBV declining: current OBV < 5-day AND < 20-day → distribution confirmed.\n" +
            "MACD zero-cross: MACD just crossed below zero → structural momentum loss.\n" +
            "Activation: protectScore ≥ threshold AND (price < SMA200 OR ADX bearish).")
        addWeightRow(protectSection, "protectObvPts", "OBV declining pts", defaults.protectObvPts)
        addWeightRow(protectSection, "protectMacdZeroCrossDnPts", "MACD zero-cross dn", defaults.protectMacdZeroCrossDnPts)
        addWeightRow(protectSection, "protectActivationThreshold", "Activation threshold ≥", defaults.protectActivationThreshold)

        // ══════════════════════════════════════════════════
        // VALUE CATCHING WEIGHTS
        // ══════════════════════════════════════════════════
        val valueSection = addWeightSection("Value Catching Weights (max 100)", "#7c3aed")

        addSchemaNote(valueSection,
            "Intent: identify fundamentally undervalued stocks using financial data.\n" +
            "Score is 0\u2013100, completely independent of technical (buy/sell) scores.\n" +
            "Data sourced from Yahoo Finance quoteSummary API (PE, PB, D/E, ROE, etc.).\n" +
            "Ratings: \u226580 Deep Value | \u226565 Moderate | \u226550 Mild | <50 Not Attractive")

        // -- Valuation Multiples --
        addSubHeader(valueSection, "1. Valuation Multiples (max 35)")
        addSchemaNote(valueSection,
            "PE < sector median \u2192 pts. PE historically cheap \u2192 pts.\n" +
            "PB < 1.5 \u2192 full pts | PB 1.5\u20132.0 \u2192 mid pts.\n" +
            "EV/EBITDA < 8 \u2192 full pts | 8\u201312 \u2192 mid pts.")
        addWeightRow(valueSection, "valuePeSectorPts", "PE < sector median pts", defaults.valuePeSectorPts)
        addWeightRow(valueSection, "valuePeHistoricalPts", "PE historical cheap pts", defaults.valuePeHistoricalPts)
        addWeightRow(valueSection, "valuePeHistoricalPct", "PE historical % threshold", defaults.valuePeHistoricalPct)
        addWeightRow(valueSection, "valuePbLowPts", "PB low pts (<1.5)", defaults.valuePbLowPts)
        addWeightRow(valueSection, "valuePbMidPts", "PB mid pts (1.5-2.0)", defaults.valuePbMidPts)
        addWeightRow(valueSection, "valuePbLowThreshold", "PB low threshold", defaults.valuePbLowThreshold)
        addWeightRow(valueSection, "valuePbMidThreshold", "PB mid threshold", defaults.valuePbMidThreshold)
        addWeightRow(valueSection, "valueEvEbitdaLowPts", "EV/EBITDA low pts (<8)", defaults.valueEvEbitdaLowPts)
        addWeightRow(valueSection, "valueEvEbitdaMidPts", "EV/EBITDA mid pts (8-12)", defaults.valueEvEbitdaMidPts)
        addWeightRow(valueSection, "valueEvEbitdaLowThreshold", "EV/EBITDA low threshold", defaults.valueEvEbitdaLowThreshold)
        addWeightRow(valueSection, "valueEvEbitdaMidThreshold", "EV/EBITDA mid threshold", defaults.valueEvEbitdaMidThreshold)

        // -- Financial Safety --
        addSubHeader(valueSection, "2. Financial Safety (max 30)")
        addSchemaNote(valueSection,
            "D/E < 0.3 \u2192 net cash (full pts) | 0.3\u20131.0 \u2192 low | 1.0\u20132.0 \u2192 mid.\n" +
            "ROCE > 15% \u2192 full pts | 10\u201315% \u2192 mid pts.\n" +
            "CFO > Net Income \u2192 quality of earnings confirmed.")
        addWeightRow(valueSection, "valueDebtEquityNetCashPts", "D/E net cash pts (<0.3)", defaults.valueDebtEquityNetCashPts)
        addWeightRow(valueSection, "valueDebtEquityLowPts", "D/E low pts (0.3-1.0)", defaults.valueDebtEquityLowPts)
        addWeightRow(valueSection, "valueDebtEquityMidPts", "D/E mid pts (1.0-2.0)", defaults.valueDebtEquityMidPts)
        addWeightRow(valueSection, "valueDebtEquityNetCashThreshold", "D/E net cash threshold", defaults.valueDebtEquityNetCashThreshold)
        addWeightRow(valueSection, "valueDebtEquityLowThreshold", "D/E low threshold", defaults.valueDebtEquityLowThreshold)
        addWeightRow(valueSection, "valueDebtEquityMidThreshold", "D/E mid threshold", defaults.valueDebtEquityMidThreshold)
        addWeightRow(valueSection, "valueRoceHighPts", "ROCE high pts (>15%)", defaults.valueRoceHighPts)
        addWeightRow(valueSection, "valueRoceMidPts", "ROCE mid pts (10-15%)", defaults.valueRoceMidPts)
        addWeightRow(valueSection, "valueRoceHighThreshold", "ROCE high threshold %", defaults.valueRoceHighThreshold)
        addWeightRow(valueSection, "valueRoceMidThreshold", "ROCE mid threshold %", defaults.valueRoceMidThreshold)
        addWeightRow(valueSection, "valueCfoPositivePts", "CFO > Net Income pts", defaults.valueCfoPositivePts)

        // -- Shareholder Yield --
        addSubHeader(valueSection, "3. Shareholder Yield (max 15)")
        addSchemaNote(valueSection,
            "Dividend yield \u2265 3% \u2192 full pts | 1\u20133% \u2192 mid pts.\n" +
            "Active buyback (declining share count) \u2192 bonus pts.")
        addWeightRow(valueSection, "valueDivHighPts", "Div yield high pts (\u22653%)", defaults.valueDivHighPts)
        addWeightRow(valueSection, "valueDivMidPts", "Div yield mid pts (1-3%)", defaults.valueDivMidPts)
        addWeightRow(valueSection, "valueDivHighThreshold", "Div high threshold %", defaults.valueDivHighThreshold)
        addWeightRow(valueSection, "valueDivMidThreshold", "Div mid threshold %", defaults.valueDivMidThreshold)
        addWeightRow(valueSection, "valueBuybackPts", "Buyback pts", defaults.valueBuybackPts)

        // -- Price Discount --
        addSubHeader(valueSection, "4. Price Discount (max 12)")
        addSchemaNote(valueSection,
            "How close is the price to 52-week low?\n" +
            "< 15% from low \u2192 full pts (deep discount) | 15\u201330% \u2192 mid pts.")
        addWeightRow(valueSection, "value52wLowClosePts", "52W low close pts (<15%)", defaults.value52wLowClosePts)
        addWeightRow(valueSection, "value52wLowMidPts", "52W low mid pts (15-30%)", defaults.value52wLowMidPts)
        addWeightRow(valueSection, "value52wLowCloseThreshold", "Close to low threshold %", defaults.value52wLowCloseThreshold)
        addWeightRow(valueSection, "value52wLowMidThreshold", "Mid from low threshold %", defaults.value52wLowMidThreshold)

        // -- Rating Thresholds --
        addSubHeader(valueSection, "Value Rating Thresholds")
        addSchemaNote(valueSection,
            "valueScore \u2265 deep \u2192 DEEP VALUE | \u2265 moderate \u2192 MODERATE VALUE\n" +
            "\u2265 mild \u2192 MILD VALUE | < mild \u2192 NOT ATTRACTIVE")
        addWeightRow(valueSection, "valueDeepThreshold", "Deep Value \u2265", defaults.valueDeepThreshold)
        addWeightRow(valueSection, "valueModerateThreshold", "Moderate Value \u2265", defaults.valueModerateThreshold)
        addWeightRow(valueSection, "valueMildThreshold", "Mild Value \u2265", defaults.valueMildThreshold)

        // ── Reset weights button ──
        btnResetWeights = Button(this).apply {
            text = "↺ Reset All Weights to Recommended"
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            lp.topMargin = 16
            layoutParams = lp
            setBackgroundColor(Color.parseColor("#64748b"))
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        inner.addView(btnResetWeights)

        // ── Save button ──
        // Pinned OUTSIDE the scroll/collapsible region so it stays visible
        // even when the user collapses every section. Previously btnSave was
        // a child of `inner`, which meant the LAST section's collapsibility
        // range swallowed it (sectionEnd defaulted to inner.childCount).
        btnSave = Button(this).apply {
            text = "💾 Save Settings"
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            lp.topMargin = 12
            layoutParams = lp
            setBackgroundColor(Color.parseColor("#059669"))
            setTextColor(Color.WHITE)
        }

        // Make the scroll view fill remaining space so btnSave can pin below it.
        scroll.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        scroll.addView(inner)
        layout.addView(scroll)
        layout.addView(btnSave)   // pinned below the scroll — always visible

        // ── Wire section collapsibility ──────────────────────────────────────
        // Walk the captured (header, startIndex) pairs and bind a click handler
        // that toggles visibility on every view between this header and the
        // NEXT header (exclusive) — or to inner.childCount if this is the last.
        // Capturing start indices at addSection time avoids the O(n²) cost of
        // searching headers in inner.children on every click.
        sectionHeaders.forEachIndexed { i, header ->
            val title = header.text.toString().removePrefix("▼ ").removePrefix("▶ ")
            val sectionStart = sectionStartIdx[i] + 1
            val sectionEnd = if (i + 1 < sectionStartIdx.size) sectionStartIdx[i + 1]
                             else inner.childCount
            val children = (sectionStart until sectionEnd).map { inner.getChildAt(it) }
            // Connections (first section = Zerodha) starts expanded.
            // Trading 212 (second) and Scan Config (third) start expanded too.
            // Heavier weight sections (4+) start COLLAPSED so the menu is short
            // by default — user taps to expand the one they want to tune.
            val startCollapsed = i >= 3
            if (startCollapsed) {
                children.forEach { it.visibility = View.GONE }
                header.text = "▶ $title"
            }
            header.setOnClickListener {
                val firstVisibleChild = children.firstOrNull()
                val isExpanded = firstVisibleChild != null && firstVisibleChild.visibility != View.GONE
                val newVis = if (isExpanded) View.GONE else View.VISIBLE
                children.forEach { it.visibility = newVis }
                header.text = (if (isExpanded) "▶ " else "▼ ") + title
            }
        }

        setupListeners()
        return layout
    }

    private fun setupListeners() {
        btnSave.setOnClickListener { saveSettings() }

        btnResetWeights.setOnClickListener {
            config.resetScoringWeights()
            loadWeightValues(ScoringWeights())
            Toast.makeText(this, "Weights reset to recommended defaults", Toast.LENGTH_SHORT).show()
        }

        btnZerodhaLogin.setOnClickListener {
            // Save API key first so login URL is correct
            config.zerodhaApiKey = etZerodhaApiKey.text.toString().trim()
            config.zerodhaApiSecret = etZerodhaApiSecret.text.toString().trim()

            val url = config.zerodhaLoginUrl
            if (url.isBlank()) {
                tvZerodhaStatus.text = "❌ Enter Zerodha API Key first"
                tvZerodhaStatus.setTextColor(android.graphics.Color.parseColor("#dc2626"))
                tvZerodhaStatus.visibility = View.VISIBLE
                return@setOnClickListener
            }

            // Open Zerodha login in browser
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

            // Show token input
            layoutZerodhaToken.visibility = View.VISIBLE
            tvZerodhaStatus.text = "After login, copy the redirect URL and paste below"
            tvZerodhaStatus.setTextColor(android.graphics.Color.parseColor("#d97706"))
            tvZerodhaStatus.visibility = View.VISIBLE
        }

        btnZerodhaToken.setOnClickListener { submitZerodhaToken() }

        // ── Trading 212 connection test ──────────────────────────────────────
        // Saves the API key first (so the test reflects what's typed, not what
        // was saved last time), then ping /equity/account/cash on a background
        // thread. Status TextView shows the result so the user knows whether
        // their key + endpoint combo works before relying on it elsewhere.
        btnT212Test.setOnClickListener {
            config.t212ApiKey = etT212ApiKey.text.toString().trim()
            config.t212ApiSecret = etT212ApiSecret.text.toString().trim()
            config.t212SimulateOnly = switchT212SimulateOnly.isChecked
            if (!config.hasT212Credentials) {
                tvT212Status.text = "❌ Both API key AND secret are required (Basic auth)"
                tvT212Status.setTextColor(android.graphics.Color.parseColor("#dc2626"))
                tvT212Status.visibility = View.VISIBLE
                return@setOnClickListener
            }
            tvT212Status.text = "⏳ Testing LIVE endpoint…"
            tvT212Status.setTextColor(android.graphics.Color.parseColor("#475569"))
            tvT212Status.visibility = View.VISIBLE
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        com.signalscope.app.network.Trading212Client(config).fetchAccountSummary()
                    } catch (e: Exception) {
                        com.signalscope.app.network.Trading212Client.T212Result.Failure(
                            e.message ?: "exception"
                        )
                    }
                }
                when (result) {
                    is com.signalscope.app.network.Trading212Client.T212Result.Success -> {
                        tvT212Status.text =
                            "✅ Connected — cash $${"%.2f".format(result.data.cash)} · " +
                            "free $${"%.2f".format(result.data.freeFunds)}"
                        tvT212Status.setTextColor(android.graphics.Color.parseColor("#059669"))
                    }
                    is com.signalscope.app.network.Trading212Client.T212Result.Failure -> {
                        tvT212Status.text = "❌ ${result.message}"
                        tvT212Status.setTextColor(android.graphics.Color.parseColor("#dc2626"))
                    }
                    is com.signalscope.app.network.Trading212Client.T212Result.NotConfigured -> {
                        tvT212Status.text = "❌ API key missing"
                        tvT212Status.setTextColor(android.graphics.Color.parseColor("#dc2626"))
                    }
                }
            }
        }
    }

    private fun loadCurrentValues() {
        etZerodhaApiKey.setText(config.zerodhaApiKey)
        etZerodhaApiSecret.setText(config.zerodhaApiSecret)

        // T212 credentials (US space)
        etT212ApiKey.setText(config.t212ApiKey)
        etT212ApiSecret.setText(config.t212ApiSecret)
        switchT212SimulateOnly.isChecked = config.t212SimulateOnly

        // Interval spinner
        spinnerInterval.setSelection(when (config.portfolioScanIntervalMin) {
            5  -> 0
            10 -> 1
            15 -> 2
            30 -> 3
            60 -> 4
            else -> 2
        })

        switchMarketHours.isChecked = config.scanDuringMarketHoursOnly
        switchVibrate.isChecked = config.vibrateOnAlerts
        switchAutoCreateGtt.isChecked = config.autoCreateGtt
        switchAutoTrailing.isChecked = config.autoTrailingEnabled
        switchAutoExit.isChecked = config.autoExitEnabled
        switchMacdFlipNotifications.isChecked = config.macdFlipNotificationsEnabled
        switchAutoSoftSniper.isChecked = config.autoSoftSniperEnabled
        // Stored as fractions (0.03), surfaced as percentages (3.0) for readability
        etSoftSniperBufferPct.setText("%.2f".format(config.softSniperBufferPct * 100.0))
        etSoftSniperLimitPct.setText("%.2f".format(config.softSniperLimitPct * 100.0))
        etSoftSniperMinGainPct.setText("%.2f".format(config.softSniperMinGainPct))
        etSoftSniperTightBufferPct.setText("%.2f".format(config.softSniperTightBufferPct * 100.0))
        etSoftSniperLooseBufferPct.setText("%.2f".format(config.softSniperLooseBufferPct * 100.0))
        etSoftSniperVolSpikeMult.setText("%.2f".format(config.softSniperVolumeSpikeMultiple))
        etSoftSniperVolWeakMult.setText("%.2f".format(config.softSniperVolumeWeakMultiple))
        etSlLimitPct.setText("%.2f".format(config.slLimitPct * 100.0))
        etNewPositionHardStopPct.setText("%.2f".format(config.newPositionHardStopPct * 100.0))
        etSlChandelierFixedAtrMultiple.setText("%.2f".format(config.slChandelierFixedAtrMultiple))
        etSlTimeDecay10dFactor.setText("%.2f".format(config.slTimeDecay10dFactor))
        etSlTimeDecay20dFactor.setText("%.2f".format(config.slTimeDecay20dFactor))
        switchAutoTrailingTarget.isChecked = config.autoTrailingTargetEnabled
        etTargetAtrMultiple.setText("%.2f".format(config.targetAtrMultiple))
        etTargetMaxDailyStepPct.setText("%.2f".format(config.targetMaxDailyStepPct * 100.0))

        // LLM settings
        etLlmApiKey.setText(config.openaiApiKey)
        spinnerLlmProvider.setSelection(if (config.llmProvider == "anthropic") 1 else 0)
        val modelIdx = when (config.llmModel) {
            "gpt-4o-mini" -> 0; "gpt-4o" -> 1
            "claude-sonnet-4-20250514" -> 2; "claude-haiku-4-20250414" -> 3
            else -> 0
        }
        spinnerLlmModel.setSelection(modelIdx)

        // Show Zerodha connection status
        if (config.isZerodhaConnected) {
            tvZerodhaStatus.text = "✅ Connected as ${config.zerodhaUserName}"
            tvZerodhaStatus.setTextColor(Color.parseColor("#059669"))
            tvZerodhaStatus.visibility = View.VISIBLE
        }

        // Load persisted scoring defaults, not the live Setups-tab session override.
        loadWeightValues(config.persistedScoringWeights)
    }

    private fun loadWeightValues(w: ScoringWeights) {
        val map = mapOf(
            // Buy
            "buySma200Pts" to w.buySma200Pts, "buySma50Pts" to w.buySma50Pts,
            "buyGoldenBuyPts" to w.buyGoldenBuyPts, "buyGoldenBonus" to w.buyGoldenBonus,
            "buyMacdZeroCrossUpPts" to w.buyMacdZeroCrossUpPts, "buySlopeCrossUpPts" to w.buySlopeCrossUpPts,
            "buyEarlyBuyPts" to w.buyEarlyBuyPts, "buyMacdPctlBonus" to w.buyMacdPctlBonus,
            "buyMacdPctlThreshold" to w.buyMacdPctlThreshold,
            "buyRsiMaxPts" to w.buyRsiMaxPts,
            "buyRsiFlipBonus" to w.buyRsiFlipBonus,
            "buyRsiFlipLow" to w.buyRsiFlipLow, "buyRsiFlipHigh" to w.buyRsiFlipHigh,
            "buyBbBasePts" to w.buyBbBasePts, "buyBbLowerBonus" to w.buyBbLowerBonus,
            "buyAdxStrongThreshold" to w.buyAdxStrongThreshold, "buyAdxVeryStrongThreshold" to w.buyAdxVeryStrongThreshold,
            "buyAdxBasePts" to w.buyAdxBasePts, "buyAdxVeryStrongBonus" to w.buyAdxVeryStrongBonus,
            "buyObvPts" to w.buyObvPts, "buyEmaMaxPts" to w.buyEmaMaxPts,
            "buyStrongThreshold" to w.buyStrongThreshold, "buyModerateThreshold" to w.buyModerateThreshold,
            "minSlopeMagnitude" to w.minSlopeMagnitude, "goldenBuyMaxSlope" to w.goldenBuyMaxSlope,
            "setupSma200MinSlope" to w.setupSma200MinSlope,
            "setupMacdLowPctMin" to w.setupMacdLowPctMin,
            "setupMacdPctlMax" to w.setupMacdPctlMax,
            // Profit Booking
            "profitSlopeCrossDnPts" to w.profitSlopeCrossDnPts, "profitEarlySellPts" to w.profitEarlySellPts,
            "profitMacdZeroCrossDnPts" to w.profitMacdZeroCrossDnPts, "profitMacdPctlBonus" to w.profitMacdPctlBonus,
            "profitMacdPctlThreshold" to w.profitMacdPctlThreshold,
            "profitRsiFlipBonus" to w.profitRsiFlipBonus,
            "profitRsiExtremePts" to w.profitRsiExtremePts, "profitRsiOverboughtPts" to w.profitRsiOverboughtPts,
            "profitRsiMildPts" to w.profitRsiMildPts, "profitRsiNoisePts" to w.profitRsiNoisePts,
            "profitRsiExtremeThreshold" to w.profitRsiExtremeThreshold,
            "profitRsiOverboughtThreshold" to w.profitRsiOverboughtThreshold,
            "profitRsiMildThreshold" to w.profitRsiMildThreshold,
            "profitRsiNoiseThreshold" to w.profitRsiNoiseThreshold,
            "profitBbUpperPts" to w.profitBbUpperPts, "profitBbTouchedPts" to w.profitBbTouchedPts,
            "profitActivationThreshold" to w.profitActivationThreshold,
            // Capital Protection
            "protectSma200Pts" to w.protectSma200Pts, "protectSma200SlopeBonus" to w.protectSma200SlopeBonus,
            "protectAdxStrongPts" to w.protectAdxStrongPts, "protectAdxWeakPts" to w.protectAdxWeakPts,
            "protectAdxStrongThreshold" to w.protectAdxStrongThreshold,
            "protectAdxWeakThreshold" to w.protectAdxWeakThreshold,
            "protectObvPts" to w.protectObvPts, "protectMacdZeroCrossDnPts" to w.protectMacdZeroCrossDnPts,
            "protectActivationThreshold" to w.protectActivationThreshold,
            // Value Catching
            "valuePeSectorPts" to w.valuePeSectorPts, "valuePeHistoricalPts" to w.valuePeHistoricalPts,
            "valuePeHistoricalPct" to w.valuePeHistoricalPct,
            "valuePbLowPts" to w.valuePbLowPts, "valuePbMidPts" to w.valuePbMidPts,
            "valuePbLowThreshold" to w.valuePbLowThreshold, "valuePbMidThreshold" to w.valuePbMidThreshold,
            "valueEvEbitdaLowPts" to w.valueEvEbitdaLowPts, "valueEvEbitdaMidPts" to w.valueEvEbitdaMidPts,
            "valueEvEbitdaLowThreshold" to w.valueEvEbitdaLowThreshold, "valueEvEbitdaMidThreshold" to w.valueEvEbitdaMidThreshold,
            "valueDebtEquityNetCashPts" to w.valueDebtEquityNetCashPts,
            "valueDebtEquityLowPts" to w.valueDebtEquityLowPts, "valueDebtEquityMidPts" to w.valueDebtEquityMidPts,
            "valueDebtEquityNetCashThreshold" to w.valueDebtEquityNetCashThreshold,
            "valueDebtEquityLowThreshold" to w.valueDebtEquityLowThreshold,
            "valueDebtEquityMidThreshold" to w.valueDebtEquityMidThreshold,
            "valueRoceHighPts" to w.valueRoceHighPts, "valueRoceMidPts" to w.valueRoceMidPts,
            "valueRoceHighThreshold" to w.valueRoceHighThreshold, "valueRoceMidThreshold" to w.valueRoceMidThreshold,
            "valueCfoPositivePts" to w.valueCfoPositivePts,
            "valueDivHighPts" to w.valueDivHighPts, "valueDivMidPts" to w.valueDivMidPts,
            "valueDivHighThreshold" to w.valueDivHighThreshold, "valueDivMidThreshold" to w.valueDivMidThreshold,
            "valueBuybackPts" to w.valueBuybackPts,
            "value52wLowClosePts" to w.value52wLowClosePts, "value52wLowMidPts" to w.value52wLowMidPts,
            "value52wLowCloseThreshold" to w.value52wLowCloseThreshold, "value52wLowMidThreshold" to w.value52wLowMidThreshold,
            "valueDeepThreshold" to w.valueDeepThreshold, "valueModerateThreshold" to w.valueModerateThreshold,
            "valueMildThreshold" to w.valueMildThreshold
        )
        for ((key, value) in map) {
            weightFields[key]?.setText(
                if (value is Double) value.toString() else value.toString()
            )
        }
    }

    private fun readInt(key: String, default: Int): Int =
        weightFields[key]?.text?.toString()?.toIntOrNull() ?: default

    private fun readDouble(key: String, default: Double): Double =
        weightFields[key]?.text?.toString()?.toDoubleOrNull() ?: default

    private fun buildWeightsFromUI(): ScoringWeights {
        val d = ScoringWeights() // defaults for fallback
        return ScoringWeights(
            buySma200Pts = readInt("buySma200Pts", d.buySma200Pts),
            buySma50Pts = readInt("buySma50Pts", d.buySma50Pts),
            buyGoldenBuyPts = readInt("buyGoldenBuyPts", d.buyGoldenBuyPts),
            buyGoldenBonus = readInt("buyGoldenBonus", d.buyGoldenBonus),
            buyMacdZeroCrossUpPts = readInt("buyMacdZeroCrossUpPts", d.buyMacdZeroCrossUpPts),
            buySlopeCrossUpPts = readInt("buySlopeCrossUpPts", d.buySlopeCrossUpPts),
            buyEarlyBuyPts = readInt("buyEarlyBuyPts", d.buyEarlyBuyPts),
            buyMacdPctlBonus = readInt("buyMacdPctlBonus", d.buyMacdPctlBonus),
            buyMacdPctlThreshold = readDouble("buyMacdPctlThreshold", d.buyMacdPctlThreshold),
            buyRsiMaxPts = readInt("buyRsiMaxPts", d.buyRsiMaxPts),
            buyRsiFlipBonus = readInt("buyRsiFlipBonus", d.buyRsiFlipBonus),
            buyRsiFlipLow = readDouble("buyRsiFlipLow", d.buyRsiFlipLow),
            buyRsiFlipHigh = readDouble("buyRsiFlipHigh", d.buyRsiFlipHigh),
            buyBbBasePts = readInt("buyBbBasePts", d.buyBbBasePts),
            buyBbLowerBonus = readInt("buyBbLowerBonus", d.buyBbLowerBonus),
            buyAdxStrongThreshold = readDouble("buyAdxStrongThreshold", d.buyAdxStrongThreshold),
            buyAdxVeryStrongThreshold = readDouble("buyAdxVeryStrongThreshold", d.buyAdxVeryStrongThreshold),
            buyAdxBasePts = readInt("buyAdxBasePts", d.buyAdxBasePts),
            buyAdxVeryStrongBonus = readInt("buyAdxVeryStrongBonus", d.buyAdxVeryStrongBonus),
            buyObvPts = readInt("buyObvPts", d.buyObvPts),
            buyEmaMaxPts = readInt("buyEmaMaxPts", d.buyEmaMaxPts),
            buyStrongThreshold = readInt("buyStrongThreshold", d.buyStrongThreshold),
            buyModerateThreshold = readInt("buyModerateThreshold", d.buyModerateThreshold),
            minSlopeMagnitude = readDouble("minSlopeMagnitude", d.minSlopeMagnitude).coerceIn(-89.0, 89.0),
            goldenBuyMaxSlope = kotlin.math.abs(readDouble("goldenBuyMaxSlope", d.goldenBuyMaxSlope)).coerceIn(0.0, 89.0),
            setupSma200MinSlope = readDouble("setupSma200MinSlope", d.setupSma200MinSlope),
            setupMacdLowPctMin = readDouble("setupMacdLowPctMin", d.setupMacdLowPctMin).coerceIn(0.0, 100.0),
            setupMacdPctlMax = readDouble("setupMacdPctlMax", d.setupMacdPctlMax).coerceIn(0.0, 100.0),
            profitSlopeCrossDnPts = readInt("profitSlopeCrossDnPts", d.profitSlopeCrossDnPts),
            profitEarlySellPts = readInt("profitEarlySellPts", d.profitEarlySellPts),
            profitMacdZeroCrossDnPts = readInt("profitMacdZeroCrossDnPts", d.profitMacdZeroCrossDnPts),
            profitMacdPctlBonus = readInt("profitMacdPctlBonus", d.profitMacdPctlBonus),
            profitMacdPctlThreshold = readDouble("profitMacdPctlThreshold", d.profitMacdPctlThreshold),
            profitRsiFlipBonus = readInt("profitRsiFlipBonus", d.profitRsiFlipBonus),
            profitRsiExtremePts = readInt("profitRsiExtremePts", d.profitRsiExtremePts),
            profitRsiOverboughtPts = readInt("profitRsiOverboughtPts", d.profitRsiOverboughtPts),
            profitRsiMildPts = readInt("profitRsiMildPts", d.profitRsiMildPts),
            profitRsiNoisePts = readInt("profitRsiNoisePts", d.profitRsiNoisePts),
            profitRsiExtremeThreshold = readDouble("profitRsiExtremeThreshold", d.profitRsiExtremeThreshold),
            profitRsiOverboughtThreshold = readDouble("profitRsiOverboughtThreshold", d.profitRsiOverboughtThreshold),
            profitRsiMildThreshold = readDouble("profitRsiMildThreshold", d.profitRsiMildThreshold),
            profitRsiNoiseThreshold = readDouble("profitRsiNoiseThreshold", d.profitRsiNoiseThreshold),
            profitBbUpperPts = readInt("profitBbUpperPts", d.profitBbUpperPts),
            profitBbTouchedPts = readInt("profitBbTouchedPts", d.profitBbTouchedPts),
            profitActivationThreshold = readInt("profitActivationThreshold", d.profitActivationThreshold),
            protectSma200Pts = readInt("protectSma200Pts", d.protectSma200Pts),
            protectSma200SlopeBonus = readInt("protectSma200SlopeBonus", d.protectSma200SlopeBonus),
            protectAdxStrongPts = readInt("protectAdxStrongPts", d.protectAdxStrongPts),
            protectAdxWeakPts = readInt("protectAdxWeakPts", d.protectAdxWeakPts),
            protectAdxStrongThreshold = readDouble("protectAdxStrongThreshold", d.protectAdxStrongThreshold),
            protectAdxWeakThreshold = readDouble("protectAdxWeakThreshold", d.protectAdxWeakThreshold),
            protectObvPts = readInt("protectObvPts", d.protectObvPts),
            protectMacdZeroCrossDnPts = readInt("protectMacdZeroCrossDnPts", d.protectMacdZeroCrossDnPts),
            protectActivationThreshold = readInt("protectActivationThreshold", d.protectActivationThreshold),
            // Value Catching
            valuePeSectorPts = readInt("valuePeSectorPts", d.valuePeSectorPts),
            valuePeHistoricalPts = readInt("valuePeHistoricalPts", d.valuePeHistoricalPts),
            valuePeHistoricalPct = readDouble("valuePeHistoricalPct", d.valuePeHistoricalPct),
            valuePbLowPts = readInt("valuePbLowPts", d.valuePbLowPts),
            valuePbMidPts = readInt("valuePbMidPts", d.valuePbMidPts),
            valuePbLowThreshold = readDouble("valuePbLowThreshold", d.valuePbLowThreshold),
            valuePbMidThreshold = readDouble("valuePbMidThreshold", d.valuePbMidThreshold),
            valueEvEbitdaLowPts = readInt("valueEvEbitdaLowPts", d.valueEvEbitdaLowPts),
            valueEvEbitdaMidPts = readInt("valueEvEbitdaMidPts", d.valueEvEbitdaMidPts),
            valueEvEbitdaLowThreshold = readDouble("valueEvEbitdaLowThreshold", d.valueEvEbitdaLowThreshold),
            valueEvEbitdaMidThreshold = readDouble("valueEvEbitdaMidThreshold", d.valueEvEbitdaMidThreshold),
            valueDebtEquityNetCashPts = readInt("valueDebtEquityNetCashPts", d.valueDebtEquityNetCashPts),
            valueDebtEquityLowPts = readInt("valueDebtEquityLowPts", d.valueDebtEquityLowPts),
            valueDebtEquityMidPts = readInt("valueDebtEquityMidPts", d.valueDebtEquityMidPts),
            valueDebtEquityNetCashThreshold = readDouble("valueDebtEquityNetCashThreshold", d.valueDebtEquityNetCashThreshold),
            valueDebtEquityLowThreshold = readDouble("valueDebtEquityLowThreshold", d.valueDebtEquityLowThreshold),
            valueDebtEquityMidThreshold = readDouble("valueDebtEquityMidThreshold", d.valueDebtEquityMidThreshold),
            valueRoceHighPts = readInt("valueRoceHighPts", d.valueRoceHighPts),
            valueRoceMidPts = readInt("valueRoceMidPts", d.valueRoceMidPts),
            valueRoceHighThreshold = readDouble("valueRoceHighThreshold", d.valueRoceHighThreshold),
            valueRoceMidThreshold = readDouble("valueRoceMidThreshold", d.valueRoceMidThreshold),
            valueCfoPositivePts = readInt("valueCfoPositivePts", d.valueCfoPositivePts),
            valueDivHighPts = readInt("valueDivHighPts", d.valueDivHighPts),
            valueDivMidPts = readInt("valueDivMidPts", d.valueDivMidPts),
            valueDivHighThreshold = readDouble("valueDivHighThreshold", d.valueDivHighThreshold),
            valueDivMidThreshold = readDouble("valueDivMidThreshold", d.valueDivMidThreshold),
            valueBuybackPts = readInt("valueBuybackPts", d.valueBuybackPts),
            value52wLowClosePts = readInt("value52wLowClosePts", d.value52wLowClosePts),
            value52wLowMidPts = readInt("value52wLowMidPts", d.value52wLowMidPts),
            value52wLowCloseThreshold = readDouble("value52wLowCloseThreshold", d.value52wLowCloseThreshold),
            value52wLowMidThreshold = readDouble("value52wLowMidThreshold", d.value52wLowMidThreshold),
            valueDeepThreshold = readInt("valueDeepThreshold", d.valueDeepThreshold),
            valueModerateThreshold = readInt("valueModerateThreshold", d.valueModerateThreshold),
            valueMildThreshold = readInt("valueMildThreshold", d.valueMildThreshold)
        )
    }

    private fun saveSettings() {
        // Zerodha
        config.zerodhaApiKey = etZerodhaApiKey.text.toString().trim()
        config.zerodhaApiSecret = etZerodhaApiSecret.text.toString().trim()

        // Trading 212 (US space)
        config.t212ApiKey = etT212ApiKey.text.toString().trim()
        config.t212ApiSecret = etT212ApiSecret.text.toString().trim()
        config.t212SimulateOnly = switchT212SimulateOnly.isChecked

        // Scan config
        config.portfolioScanIntervalMin = when (spinnerInterval.selectedItemPosition) {
            0    -> 5
            1    -> 10
            2    -> 15
            3    -> 30
            4    -> 60
            else -> 15
        }
        config.scanDuringMarketHoursOnly = switchMarketHours.isChecked
        config.vibrateOnAlerts = switchVibrate.isChecked
        config.autoCreateGtt = switchAutoCreateGtt.isChecked
        config.autoTrailingEnabled = switchAutoTrailing.isChecked
        config.autoExitEnabled = switchAutoExit.isChecked
        config.macdFlipNotificationsEnabled = switchMacdFlipNotifications.isChecked
        // Soft sniper — clamp to safe ranges so a typo can't place crazy GTTs.
        // Buffer 0.5–10% (>10% defeats the purpose; <0.5% would fire on spread).
        // Limit 0.0–3% inside trigger. MinGain 0–50%.
        config.autoSoftSniperEnabled = switchAutoSoftSniper.isChecked
        val bufPct = etSoftSniperBufferPct.text.toString().toDoubleOrNull() ?: 3.0
        val limPct = etSoftSniperLimitPct.text.toString().toDoubleOrNull() ?: 0.5
        val minGain = etSoftSniperMinGainPct.text.toString().toDoubleOrNull() ?: 5.0
        config.softSniperBufferPct = (bufPct / 100.0).coerceIn(0.005, 0.10)
        config.softSniperLimitPct = (limPct / 100.0).coerceIn(0.0, 0.03)
        config.softSniperMinGainPct = minGain.coerceIn(0.0, 50.0)

        // Dynamic buffer tier configuration
        val tightPct = etSoftSniperTightBufferPct.text.toString().toDoubleOrNull() ?: 1.5
        val loosePct = etSoftSniperLooseBufferPct.text.toString().toDoubleOrNull() ?: 4.0
        val volSpike = etSoftSniperVolSpikeMult.text.toString().toDoubleOrNull() ?: 1.30
        val volWeak  = etSoftSniperVolWeakMult.text.toString().toDoubleOrNull() ?: 0.70
        // Tight: 0.3–3% — must remain tighter than NORMAL (softSniperBufferPct).
        // Loose: 1–10% — must remain wider than NORMAL.
        // Spike: 1.05–3.0x. Weak: 0.2–0.95x. Coerce to safe ranges.
        config.softSniperTightBufferPct = (tightPct / 100.0).coerceIn(0.003, 0.03)
        config.softSniperLooseBufferPct = (loosePct / 100.0).coerceIn(0.01, 0.10)
        config.softSniperVolumeSpikeMultiple = volSpike.coerceIn(1.05, 3.0)
        config.softSniperVolumeWeakMultiple  = volWeak.coerceIn(0.2, 0.95)

        // Trailing OCO target — defaults: ATR mult 2.0 (range 0.5–5.0),
        // max daily step 8% (range 1–25%). Tighter than 1% would block almost
        // every bump; wider than 25% defeats the anti-spike protection.
        // Stop-loss / chandelier settings. 0 fixed ATR means "use automatic tiers".
        val slLimitPadPct = etSlLimitPct.text.toString().toDoubleOrNull() ?: 2.0
        val newHardStopPct = etNewPositionHardStopPct.text.toString().toDoubleOrNull() ?: 5.0
        val fixedSlAtr = etSlChandelierFixedAtrMultiple.text.toString().toDoubleOrNull() ?: 0.0
        val slDecay10 = etSlTimeDecay10dFactor.text.toString().toDoubleOrNull() ?: 0.80
        val slDecay20 = etSlTimeDecay20dFactor.text.toString().toDoubleOrNull() ?: 0.60
        config.slLimitPct = (slLimitPadPct / 100.0).coerceIn(0.0, 0.05)
        config.newPositionHardStopPct = (newHardStopPct / 100.0).coerceIn(0.01, 0.25)
        config.slChandelierFixedAtrMultiple = fixedSlAtr.coerceIn(0.0, 8.0)
        config.slTimeDecay10dFactor = slDecay10.coerceIn(0.10, 1.00)
        config.slTimeDecay20dFactor = slDecay20.coerceIn(0.10, 1.00)

        config.autoTrailingTargetEnabled = switchAutoTrailingTarget.isChecked
        val tgtAtrMult = etTargetAtrMultiple.text.toString().toDoubleOrNull() ?: 2.0
        val tgtMaxStep = etTargetMaxDailyStepPct.text.toString().toDoubleOrNull() ?: 8.0
        config.targetAtrMultiple = tgtAtrMult.coerceIn(0.5, 5.0)
        config.targetMaxDailyStepPct = (tgtMaxStep / 100.0).coerceIn(0.01, 0.25)

        // LLM settings
        config.openaiApiKey = etLlmApiKey.text.toString().trim()
        config.llmProvider = if (spinnerLlmProvider.selectedItemPosition == 1) "anthropic" else "openai"
        config.llmModel = when (spinnerLlmModel.selectedItemPosition) {
            0 -> "gpt-4o-mini"; 1 -> "gpt-4o"
            2 -> "claude-sonnet-4-20250514"; 3 -> "claude-haiku-4-20250414"
            else -> "gpt-4o-mini"
        }

        // Scoring weights
        config.scoringWeights = buildWeightsFromUI()

        Toast.makeText(this, "✅ Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun submitZerodhaToken() {
        var token = etZerodhaToken.text.toString().trim()
        if (token.isBlank()) return

        // Extract request_token from full URL if pasted
        val match = Regex("request_token=([^&]+)").find(token)
        if (match != null) token = match.groupValues[1]

        btnZerodhaToken.isEnabled = false
        tvZerodhaStatus.text = "Connecting to Zerodha..."
        tvZerodhaStatus.setTextColor(android.graphics.Color.parseColor("#475569"))

        scope.launch {
            val client = ZerodhaClient(config)
            val result = withContext(Dispatchers.IO) { client.exchangeRequestToken(token) }

            withContext(Dispatchers.Main) {
                btnZerodhaToken.isEnabled = true
                when (result) {
                    is ZerodhaClient.ZerodhaAuthResult.Success -> {
                        tvZerodhaStatus.text = "✅ Zerodha connected — ${result.userName}"
                        tvZerodhaStatus.setTextColor(android.graphics.Color.parseColor("#059669"))
                        layoutZerodhaToken.visibility = View.GONE
                        etZerodhaToken.setText("")
                    }
                    is ZerodhaClient.ZerodhaAuthResult.Failure -> {
                        tvZerodhaStatus.text = "❌ ${result.message}"
                        tvZerodhaStatus.setTextColor(android.graphics.Color.parseColor("#dc2626"))
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

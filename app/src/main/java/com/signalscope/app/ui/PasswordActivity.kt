package com.signalscope.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PasswordActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PasswordActivity"
        // GitHub Gist raw URL — without commit hash so it always returns latest
        private const val PASSWORD_URL =
            "https://gist.githubusercontent.com/guptakaran55/b63d45a7563a434764cb54b13bd4c64d/raw/password.json"
    }

    private data class PasswordConfig(val master_password: String? = null)

    private lateinit var passwordInput: EditText
    private lateinit var submitBtn: Button
    private lateinit var statusText: TextView
    private val uiHandler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()

        // Auto-validate if a password hash was saved from a previous session.
        // We store the SHA-256 hash, not the plaintext — so we validate hash-to-hash
        // (fetched master password is hashed and compared against stored hash).
        val prefs = getSharedPreferences("signalscope_auth", MODE_PRIVATE)
        val savedHash = prefs.getString("saved_password_hash", null)
            ?: prefs.getString("saved_password", null)  // migrate old plaintext entry
        if (!savedHash.isNullOrEmpty()) {
            // We don't display the hash in the input — just show a placeholder
            submitBtn.isEnabled = false
            statusText.setTextColor(0xFF94a3b8.toInt())
            statusText.text = "Verifying saved session..."
            validateSavedHash(savedHash)
        }
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(80, 0, 80, 0)
            setBackgroundColor(0xFF0a0f1e.toInt())
        }

        // Logo image — loads assets/logo.png, falls back to launcher icon on error.
        val title = ImageView(this).apply {
            try {
                assets.open("logo.png").use {
                    setImageBitmap(android.graphics.BitmapFactory.decodeStream(it))
                }
            } catch (e: Exception) {
                Log.w("PasswordActivity", "logo.png not found in assets, using launcher icon", e)
                setImageResource(com.signalscope.app.R.mipmap.ic_launcher)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "SignalScope"
        }
        root.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            140
        ).apply { bottomMargin = 12 })

        val subtitle = TextView(this).apply {
            text = "Enter access password"
            setTextColor(0xFF94a3b8.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
        }
        root.addView(subtitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 48 })

        passwordInput = EditText(this).apply {
            hint = "Password"
            setHintTextColor(0xFF475569.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setBackgroundColor(0xFF1e293b.toInt())
            setPadding(40, 32, 40, 32)
            gravity = Gravity.CENTER
        }
        root.addView(passwordInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 24 })

        submitBtn = Button(this).apply {
            text = "UNLOCK"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setBackgroundColor(0xFF059669.toInt())
            setPadding(0, 28, 0, 28)
            isAllCaps = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setOnClickListener { onSubmit() }
        }
        root.addView(submitBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 24 })

        statusText = TextView(this).apply {
            text = ""
            setTextColor(0xFFef4444.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
        }
        root.addView(statusText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)
    }

    private fun onSubmit() {
        val password = passwordInput.text.toString().trim()
        if (password.isEmpty()) {
            statusText.setTextColor(0xFFef4444.toInt())
            statusText.text = "Enter a password"
            return
        }
        submitBtn.isEnabled = false
        statusText.setTextColor(0xFF94a3b8.toInt())
        statusText.text = "Verifying..."
        validatePassword(password, isAutoAttempt = false)
    }

    /** Auto-login path: we have a stored hash — fetch Gist, hash master, compare. */
    private fun validateSavedHash(savedHash: String) {
        thread(isDaemon = true) {
            val result = fetchMasterPassword()
            uiHandler.post {
                when (result) {
                    is FetchResult.Success -> {
                        val masterHash = sha256(result.password)
                        // Migrate: old plaintext entry → compare directly then re-store as hash
                        val matchesHash      = savedHash == masterHash
                        val matchesPlaintext = savedHash == result.password // old format migration
                        if (result.password.isNotEmpty() && (matchesHash || matchesPlaintext)) {
                            // Re-save as hash if it was stored as old plaintext
                            if (matchesPlaintext && !matchesHash) {
                                getSharedPreferences("signalscope_auth", MODE_PRIVATE)
                                    .edit()
                                    .putString("saved_password_hash", masterHash)
                                    .remove("saved_password")
                                    .apply()
                            }
                            startActivity(Intent(this, MainActivity::class.java))
                            finish()
                        } else {
                            // Master password changed — revoke saved session, ask user to re-enter
                            getSharedPreferences("signalscope_auth", MODE_PRIVATE)
                                .edit().remove("saved_password_hash").remove("saved_password").apply()
                            statusText.setTextColor(0xFFef4444.toInt())
                            statusText.text = "Access revoked. Enter the new password."
                            submitBtn.isEnabled = true
                        }
                    }
                    is FetchResult.Error -> {
                        Log.e(TAG, "Auto-validate fetch failed: ${result.message}")
                        statusText.setTextColor(0xFFef4444.toInt())
                        statusText.text = "Network error: ${result.message}"
                        submitBtn.isEnabled = true
                    }
                }
            }
        }
    }

    // SHA-256 hash of the given string, returned as a hex string.
    // We store/compare hashes so that even if SharedPreferences is read by
    // an attacker, they only see a hash — not the usable password.
    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun validatePassword(password: String, isAutoAttempt: Boolean) {
        thread(isDaemon = true) {
            val result = fetchMasterPassword()
            uiHandler.post {
                when (result) {
                    is FetchResult.Success -> {
                        val masterPassword = result.password
                        // Compare the entered password against the fetched master password.
                        // We hash both sides before comparing so the stored token is a hash,
                        // not the raw password. The Gist itself still contains the plaintext
                        // master password — that hasn't changed.
                        val enteredHash = sha256(password)
                        val masterHash  = sha256(masterPassword)
                        if (masterPassword.isNotEmpty() && enteredHash == masterHash) {
                            // Store the HASH — not the plaintext — so SharedPrefs leaks are harmless.
                            getSharedPreferences("signalscope_auth", MODE_PRIVATE)
                                .edit().putString("saved_password_hash", enteredHash).apply()
                            startActivity(Intent(this, MainActivity::class.java))
                            finish()
                        } else {
                            if (isAutoAttempt) {
                                getSharedPreferences("signalscope_auth", MODE_PRIVATE)
                                    .edit().remove("saved_password_hash").apply()
                                passwordInput.text.clear()
                            }
                            statusText.setTextColor(0xFFef4444.toInt())
                            statusText.text = if (masterPassword.isEmpty())
                                "Server config missing. Contact admin."
                            else "Wrong password"
                            submitBtn.isEnabled = true
                        }
                    }
                    is FetchResult.Error -> {
                        Log.e(TAG, "Fetch failed: ${result.message}")
                        statusText.setTextColor(0xFFef4444.toInt())
                        statusText.text = "Network error: ${result.message}"
                        submitBtn.isEnabled = true
                    }
                }
            }
        }
    }

    private sealed class FetchResult {
        data class Success(val password: String) : FetchResult()
        data class Error(val message: String) : FetchResult()
    }

    private fun fetchMasterPassword(): FetchResult {
        return try {
            val request = Request.Builder()
                .url(PASSWORD_URL)
                .header("Cache-Control", "no-cache")
                .build()
            httpClient.newCall(request).execute().use { response: Response ->
                if (!response.isSuccessful) {
                    return FetchResult.Error("HTTP ${response.code}")
                }
                val body = response.body?.string() ?: return FetchResult.Error("Empty response")
                val config = gson.fromJson(body, PasswordConfig::class.java)
                val pass = config?.master_password?.trim() ?: ""
                Log.d(TAG, "Fetched config OK. Password length: ${pass.length}")
                FetchResult.Success(pass)
            }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP fetch threw", e)
            FetchResult.Error(e.message ?: "unknown error")
        }
    }
}

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

        /** Set when the background check found the saved login no longer valid. */
        private const val EXTRA_REVOKED = "access_revoked"

        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
        private val gson = Gson()

        /**
         * CHECK A SAVED LOGIN WITHOUT MAKING ANYONE WAIT FOR IT.
         *
         * Every launch used to sit on this screen for two to three seconds while
         * the password was fetched from GitHub — and with a saved login the answer
         * is almost always "still valid". So the app now opens at once and this
         * runs behind it.
         *
         * Revocation still works, a few seconds later instead of up front: if the
         * password has changed, the saved login is deleted and the lock screen
         * comes back over the app, saying so. A network failure changes nothing —
         * a login that was valid stays valid until a check can actually be made,
         * rather than locking you out of your own app for being offline.
         */
        private fun verifySavedLoginInBackground(ctx: android.content.Context, savedHash: String) {
            thread(isDaemon = true, name = "login-check") {
                val result = fetchMasterPassword()
                if (result is FetchResult.Error) {
                    Log.w(TAG, "Saved login not re-checked (${result.message}) — kept")
                    return@thread
                }
                val password = (result as FetchResult.Success).password
                val prefs = ctx.getSharedPreferences("signalscope_auth", android.content.Context.MODE_PRIVATE)
                val masterHash = sha256(password)
                val matchesHash = savedHash == masterHash
                val matchesPlaintext = savedHash == password // old format migration
                if (password.isNotEmpty() && (matchesHash || matchesPlaintext)) {
                    // Re-save as hash if it was stored as old plaintext
                    if (matchesPlaintext && !matchesHash)
                        prefs.edit().putString("saved_password_hash", masterHash)
                            .remove("saved_password").apply()
                    return@thread
                }
                // Master password changed — revoke the saved login and lock the app.
                prefs.edit().remove("saved_password_hash").remove("saved_password").apply()
                Log.w(TAG, "Saved login no longer valid — returning to the lock screen")
                try {
                    ctx.startActivity(Intent(ctx, PasswordActivity::class.java)
                        .putExtra(EXTRA_REVOKED, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                } catch (e: Exception) {
                    // A backgrounded app may not open a screen. The login is
                    // already deleted, so the next launch asks for the password.
                    Log.w(TAG, "Could not show the lock screen now: ${e.message}")
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

    private data class PasswordConfig(val master_password: String? = null)

    private lateinit var passwordInput: EditText
    private lateinit var submitBtn: Button
    private lateinit var statusText: TextView
    private val uiHandler = Handler(Looper.getMainLooper())

    /** The dashboard, carrying whatever this launch was for. A notification tap
     *  names an algo, a chart or a watcher row; that used to be dropped here, so
     *  a tap on a closed app opened wherever the app had last been left. */
    private fun mainIntent(): Intent = Intent(this, MainActivity::class.java).apply {
        intent?.extras?.let { putExtras(it) }
        intent?.data?.let { data = it; action = Intent.ACTION_VIEW }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val revoked = intent?.getBooleanExtra(EXTRA_REVOKED, false) == true

        // A SAVED LOGIN GOES STRAIGHT IN — see [verifySavedLoginInBackground].
        // The lock screen is not even drawn. We store the SHA-256 hash, not the
        // plaintext, so the check behind the app compares hash to hash.
        if (!revoked) {
            val prefs = getSharedPreferences("signalscope_auth", MODE_PRIVATE)
            val savedHash = prefs.getString("saved_password_hash", null)
                ?: prefs.getString("saved_password", null)  // migrate old plaintext entry
            if (!savedHash.isNullOrEmpty()) {
                verifySavedLoginInBackground(applicationContext, savedHash)
                startActivity(mainIntent())
                finish()
                return
            }
        }

        buildUI()
        if (revoked) {
            statusText.setTextColor(0xFFef4444.toInt())
            statusText.text = "Access revoked. Enter the new password."
            submitBtn.isEnabled = true
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
                            startActivity(mainIntent())
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

}

package com.signalscope.app.network

import android.util.Log
import com.google.gson.JsonParser
import com.signalscope.app.data.ConfigManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Unattended daily Zerodha login.
 *
 * WHY THIS EXISTS
 * Kite invalidates the access token every morning, and the only way to get a
 * fresh one is an interactive login. The owner of this phone lives in Germany,
 * where that login lands at ~5am local time — so in practice the app spent every
 * Indian session logged out unless an alarm clock was involved.
 *
 * WHAT IT DOES
 * The Kite web login is three ordinary form posts — no browser, no WebView, no
 * server anywhere. We sign in, answer the second factor with a code generated
 * on-device from the TOTP seed, then ask for the connect-login page. Kite
 * answers with a redirect chain whose final hop carries the one-time code, and
 * that code goes into the exact same exchange the manual paste path already
 * uses. Nothing about how the app talks to Kite afterwards changes.
 *
 * WHAT IT DELIBERATELY DOES NOT DO
 * It never retries a login Kite actively rejected. Repeated bad passwords lock a
 * Zerodha account, and a locked account at 3am is a far worse outcome than a
 * missed session — so credential rejections are terminal for the day and surface
 * as a notification instead. Only transient trouble (no network, Kite 5xx) is
 * worth another attempt.
 *
 * These endpoints are Kite's own web-login internals, not the documented Connect
 * API, so they can change without notice. Every failure path therefore degrades
 * to "tell the user and let them paste a token by hand" rather than to anything
 * clever.
 */
object ZerodhaAutoLogin {

    private const val TAG = "ZerodhaAutoLogin"
    private const val KITE = "https://kite.zerodha.com"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36"

    sealed class Result {
        data class Success(val userName: String) : Result()
        /** Kite refused the credentials themselves. Do NOT try again today. */
        data class Rejected(val message: String) : Result()
        /** Network / Kite hiccup / a reply shape we didn't recognise. Retry later is safe. */
        data class Retryable(val message: String) : Result()
    }

    // ═══════════════════════════════════════════════════════
    // TOTP  (RFC 6238, 30s step, 6 digits — what Kite issues)
    // ═══════════════════════════════════════════════════════

    private fun base32Decode(secret: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = secret.filterNot { it.isWhitespace() || it == '-' }
            .trimEnd('=').uppercase()
        require(clean.isNotEmpty()) { "empty secret" }
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = alphabet.indexOf(c)
            require(v >= 0) { "$c is not a base32 character" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    /** Current 6-digit code for [secret]. Throws IllegalArgumentException on a malformed seed. */
    fun totpNow(secret: String, epochSec: Long = System.currentTimeMillis() / 1000): String {
        val key = base32Decode(secret)
        var counter = epochSec / 30
        val msg = ByteArray(8)
        for (i in 7 downTo 0) {
            msg[i] = (counter and 0xFF).toByte()
            counter = counter shr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val h = mac.doFinal(msg)
        val offset = h[h.size - 1].toInt() and 0x0F
        val bin = ((h[offset].toInt() and 0x7F) shl 24) or
                  ((h[offset + 1].toInt() and 0xFF) shl 16) or
                  ((h[offset + 2].toInt() and 0xFF) shl 8) or
                  (h[offset + 3].toInt() and 0xFF)
        return (bin % 1_000_000).toString().padStart(6, '0')
    }

    /**
     * A code minted in the last few seconds of its window can expire in flight and
     * come back as "invalid TOTP" — indistinguishable from a genuinely wrong seed,
     * and a wrong seed is treated as terminal. Waiting out the window edge is far
     * cheaper than misclassifying a good seed as a bad one.
     */
    private fun freshTotp(secret: String): String {
        val secsIntoWindow = (System.currentTimeMillis() / 1000) % 30
        if (secsIntoWindow > 25) Thread.sleep((31 - secsIntoWindow) * 1000L)
        return totpNow(secret)
    }

    // ═══════════════════════════════════════════════════════
    // LOGIN
    // ═══════════════════════════════════════════════════════

    /** Per-attempt cookie jar. Kite's session lives in cookies between the three calls. */
    private class Jar : CookieJar {
        private val store = mutableMapOf<String, Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { store[it.name] = it }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = store.values.toList()
    }

    /** Reason parked by whichever step failed, so [run] stays readable. */
    private var lastError: Result = Result.Retryable("unknown")

    private fun rejected(m: String): Result.Rejected =
        Result.Rejected(m).also { lastError = it }

    private fun retryable(m: String): Result.Retryable =
        Result.Retryable(m).also { lastError = it }

    /**
     * Runs the full sequence and, on success, leaves a live access token in
     * [config] exactly as a manual login would. Blocking — call off the main thread.
     */
    @Synchronized
    fun run(config: ConfigManager): Result {
        if (config.zerodhaApiKey.isBlank() || config.zerodhaApiSecret.isBlank())
            return rejected("Zerodha API key/secret not set")
        if (config.zerodhaUserId.isBlank() || config.zerodhaPassword.isBlank() ||
            config.zerodhaTotpSecret.isBlank())
            return rejected("Auto-login needs client ID, password and TOTP secret")

        val http = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .cookieJar(Jar())
            .followRedirects(false)       // we walk the chain ourselves — see step 3
            .followSslRedirects(false)
            .build()

        return try {
            val requestId = step1Password(http, config) ?: return lastError
            val code = try {
                freshTotp(config.zerodhaTotpSecret)
            } catch (e: IllegalArgumentException) {
                return rejected("TOTP secret isn't valid base32 (${e.message})")
            }
            if (!step2Totp(http, config, requestId, code)) return lastError
            val requestToken = step3RequestToken(http, config) ?: return lastError

            when (val ex = ZerodhaClient(config).exchangeRequestToken(requestToken)) {
                is ZerodhaClient.ZerodhaAuthResult.Success -> {
                    Log.i(TAG, "Auto-login succeeded for ${ex.userName}")
                    Result.Success(ex.userName)
                }
                // The one-time code was good enough to reach here, so a failure now
                // is about the API key/secret pair, not the password — terminal.
                is ZerodhaClient.ZerodhaAuthResult.Failure ->
                    rejected("Token exchange refused: ${ex.message}")
            }
        } catch (e: java.io.IOException) {
            retryable("Network: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Auto-login blew up", e)
            retryable(e.message ?: "Unknown error")
        }
    }

    /** POST /api/login — client ID + password. Returns the one-shot id for the 2FA step. */
    private fun step1Password(http: OkHttpClient, config: ConfigManager): String? {
        val body = FormBody.Builder()
            .add("user_id", config.zerodhaUserId)
            .add("password", config.zerodhaPassword)
            .build()
        http.newCall(kiteRequest("$KITE/api/login", body)).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = try { JsonParser.parseString(text).asJsonObject } catch (e: Exception) { null }
            if (json == null) {
                retryable("Kite sent back something that wasn't JSON (HTTP ${resp.code})")
                return null
            }
            if (json.get("status")?.asString != "success") {
                val msg = json.get("message")?.asString ?: "login refused"
                // 429 / 5xx are Kite having a moment; anything else at this step means
                // the client ID or password is wrong — terminal by design.
                if (resp.code == 429 || resp.code >= 500) retryable("Kite busy: $msg")
                else rejected("Login refused: $msg")
                return null
            }
            val id = json.getAsJsonObject("data")?.get("request_id")?.asString
            if (id.isNullOrBlank()) {
                retryable("Kite accepted the password but sent no request id")
                return null
            }
            return id
        }
    }

    /** POST /api/twofa — the generated code. On success the session lands in the cookie jar. */
    private fun step2Totp(
        http: OkHttpClient, config: ConfigManager, requestId: String, code: String
    ): Boolean {
        val body = FormBody.Builder()
            .add("user_id", config.zerodhaUserId)
            .add("request_id", requestId)
            .add("twofa_value", code)
            .add("twofa_type", "totp")
            .add("skip_session", "")
            .build()
        http.newCall(kiteRequest("$KITE/api/twofa", body)).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = try { JsonParser.parseString(text).asJsonObject } catch (e: Exception) { null }
            if (json == null) {
                retryable("2FA reply wasn't JSON (HTTP ${resp.code})")
                return false
            }
            if (json.get("status")?.asString != "success") {
                val msg = json.get("message")?.asString ?: "2FA refused"
                if (resp.code == 429 || resp.code >= 500) retryable("Kite busy: $msg")
                else rejected("Second factor refused: $msg")
                return false
            }
            return true
        }
    }

    /**
     * GET /connect/login — with the session cookie Kite answers 302 and bounces
     * through to the app's registered redirect address, carrying the one-time code.
     * We read that code out of the Location header and stop: the redirect address
     * itself is never fetched (it is usually a placeholder that answers nothing).
     */
    private fun step3RequestToken(http: OkHttpClient, config: ConfigManager): String? {
        var url = "$KITE/connect/login?v=3&api_key=${config.zerodhaApiKey}"
        val pattern = Regex("request_token=([^&]+)")
        repeat(10) {
            val req = Request.Builder().url(url).get()
                .header("User-Agent", UA)
                .header("X-Kite-Version", "3")
                .build()
            val location = http.newCall(req).execute().use { resp ->
                val loc = resp.header("Location")
                if (loc.isNullOrBlank()) {
                    // WHATEVER KITE SAID HERE IS THE ONLY EVIDENCE OF WHY.
                    // "It stopped redirecting" was true and useless: an expired
                    // Connect subscription, a session cookie that did not carry,
                    // and a consent screen all look identical from the outside and
                    // need completely different fixes. So the reply is read and
                    // reported rather than thrown away.
                    retryable(describeDeadEnd(resp.code, resp.body?.string().orEmpty()))
                    return null
                }
                loc
            }
            val next = url.toHttpUrl().resolve(location)?.toString()
            if (next == null) {
                retryable("Couldn't follow Kite's redirect to $location")
                return null
            }
            pattern.find(next)?.let { return it.groupValues[1] }
            url = next
        }
        retryable("Kite kept redirecting without ever handing over a token")
        return null
    }

    /**
     * Turns Kite's non-redirect answer into something a person can act on.
     *
     * The three cases that actually happen need three different responses from
     * the user, so guessing between them is worse than useless. A JSON error is
     * quoted verbatim — Kite's own words beat any paraphrase — and the HTML cases
     * are told apart by what the page is asking for.
     */
    private fun describeDeadEnd(code: Int, body: String): String {
        val head = body.take(4000)
        val lower = head.lowercase()

        // Kite answers the connect endpoint with JSON when it dislikes the app
        // itself: a wrong key, a lapsed Kite Connect subscription, a disabled app.
        if (lower.trimStart().startsWith("{")) {
            val msg = try {
                JsonParser.parseString(head).asJsonObject.get("message")?.asString
            } catch (e: Exception) { null }
            return "Kite refused the connect step (HTTP $code): " +
                (msg ?: head.take(160)) +
                " — this is about the API key or the Kite Connect subscription, not your password."
        }
        // A consent screen: the account is fine, but this app needs approving once
        // by hand before it can be driven unattended again.
        if (lower.contains("authorize") || lower.contains("authorise") ||
            lower.contains("permission") && lower.contains("app")) {
            return "Kite is showing an app-authorisation screen (HTTP $code). " +
                "Log in once by hand with 'Open Zerodha Login' and approve the app; " +
                "after that the automatic login should work again."
        }
        // The login form again: the sign-in worked but the session did not carry
        // through to the connect step.
        if (lower.contains("password") || lower.contains("user_id") || lower.contains("totp")) {
            return "Kite served the login page again (HTTP $code) — the sign-in " +
                "succeeded but the session did not carry through to the connect step."
        }
        return "Kite answered the connect step with HTTP $code and no redirect. " +
            "First bytes: " + head.replace(Regex("<[^>]*>"), " ")
                .replace(Regex("\\s+"), " ").trim().take(200)
    }

    private fun kiteRequest(url: String, body: FormBody): Request =
        Request.Builder().url(url).post(body)
            .header("User-Agent", UA)
            .header("X-Kite-Version", "3")
            .header("Referer", "$KITE/")
            .build()
}

package com.example.spotalk.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.example.spotalk.SpotifyConfig
import com.example.spotalk.log.AppLog
import com.example.spotalk.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * התחברות רשמית ל-Web API בשיטת Authorization Code + PKCE (בלי client secret).
 */
class AuthManager(context: Context, private val settings: AppSettings) {

    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private val refreshLock = Mutex()

    private val _loggedIn = MutableStateFlow(prefs.getString(KEY_REFRESH, null) != null)
    val loggedIn: StateFlow<Boolean> = _loggedIn

    fun buildAuthorizeUri(): Uri {
        val verifier = randomString(64)
        val state = randomString(16)
        prefs.edit().putString(KEY_VERIFIER, verifier).putString(KEY_STATE, state).apply()

        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", settings.clientId.value)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", SpotifyConfig.REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challengeFor(verifier))
            .appendQueryParameter("scope", SpotifyConfig.SCOPES.joinToString(" "))
            .appendQueryParameter("state", state)
            .build()
    }

    /** נקרא כשהדפדפן מחזיר אותנו ל-spotalk://callback */
    suspend fun handleRedirect(uri: Uri): Result<Unit> = runCatching {
        AppLog.i("Auth", "חזרה מההתחברות: error=${uri.getQueryParameter("error")} hasCode=${uri.getQueryParameter("code") != null}")
        uri.getQueryParameter("error")?.let { error("ההתחברות בוטלה: $it") }
        val code = uri.getQueryParameter("code") ?: error("לא התקבל קוד התחברות")
        val state = uri.getQueryParameter("state")
        if (state != prefs.getString(KEY_STATE, null)) error("state לא תואם, נסה שוב")
        val verifier = prefs.getString(KEY_VERIFIER, null) ?: error("חסר code_verifier, נסה שוב")

        val json = postForm(
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to SpotifyConfig.REDIRECT_URI,
                "client_id" to settings.clientId.value,
                "code_verifier" to verifier
            )
        )
        saveTokens(json)
        prefs.edit().remove(KEY_VERIFIER).remove(KEY_STATE)
            .putInt(KEY_SCOPE_VERSION, SpotifyConfig.SCOPE_VERSION).apply()
        _loggedIn.value = true
        AppLog.i("Auth", "התחברות ל-Web API הצליחה ✓")
    }.onFailure { AppLog.e("Auth", "התחברות נכשלה: ${it.message}", it) }

    /** מחזיר טוקן בתוקף, ומרענן אותו אם צריך */
    suspend fun accessToken(forceRefresh: Boolean = false): String? {
        refreshLock.withLock {
            val token = prefs.getString(KEY_ACCESS, null)
            val expiresAt = prefs.getLong(KEY_EXPIRES, 0L)
            if (!forceRefresh && token != null && System.currentTimeMillis() < expiresAt - 60_000) {
                return token
            }
            val refresh = prefs.getString(KEY_REFRESH, null) ?: return null
            return refreshWith(refresh)
        }
    }

    private suspend fun refreshWith(refresh: String): String? {
        return try {
            val json = postForm(
                mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refresh,
                    "client_id" to settings.clientId.value
                )
            )
            saveTokens(json)
            prefs.getString(KEY_ACCESS, null)
        } catch (e: TokenRejectedException) {
            AppLog.e("Auth", "רענון טוקן נדחה — מתנתקים: ${e.message}")
            logout()
            null
        }
    }

    /** האם המשתמש אישר הרשאה מסוימת. התחברות מגרסה ישנה (בלי רישום הרשאות) נחשבת "לא". */
    fun hasScope(scope: String): Boolean =
        prefs.getString(KEY_SCOPE, null)?.split(" ")?.contains(scope) == true

    /** התחברות מגרסה ישנה שלא רשמה הרשאות (ולכן גם לא ביקשה app-remote-control) */
    val isLegacyLogin: Boolean
        get() = prefs.getString(KEY_REFRESH, null) != null &&
            prefs.getInt(KEY_SCOPE_VERSION, 0) < SpotifyConfig.SCOPE_VERSION

    fun logout() {
        AppLog.i("Auth", "logout()")
        prefs.edit().clear().apply()
        _loggedIn.value = false
    }

    private fun saveTokens(json: JSONObject) {
        val editor = prefs.edit()
            .putString(KEY_ACCESS, json.getString("access_token"))
            .putLong(KEY_EXPIRES, System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000)
        // בריענון Spotify לפעמים לא שולחת refresh_token חדש — אז שומרים את הישן
        val newRefresh = json.optString("refresh_token", "")
        if (newRefresh.isNotEmpty()) editor.putString(KEY_REFRESH, newRefresh)
        // ההרשאות שהמשתמש אישר בפועל (בריענון לפעמים לא נשלח — אז שומרים את הקודם)
        val scope = json.optString("scope", "")
        if (scope.isNotEmpty()) {
            editor.putString(KEY_SCOPE, scope)
            AppLog.i("Auth", "הרשאות שאושרו: $scope")
        }
        editor.apply()
    }

    private suspend fun postForm(params: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val body = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val conn = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 400 || code == 401) throw TokenRejectedException(text)
            if (code !in 200..299) error("שגיאת התחברות $code: $text")
            JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun randomString(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return base64Url(buf)
    }

    private fun challengeFor(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun base64Url(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    class TokenRejectedException(message: String) : Exception(message)

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES = "expires_at"
        const val KEY_VERIFIER = "pkce_verifier"
        const val KEY_STATE = "pkce_state"
        const val KEY_SCOPE = "granted_scope"
        const val KEY_SCOPE_VERSION = "scope_version"
    }
}

package com.example.spotalk.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * הגדרות שהמשתמש מזין בתוך האפליקציה (לא בקוד).
 * כרגע: ה-Client ID מהדשבורד של Spotify.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _clientId = MutableStateFlow(prefs.getString(KEY_CLIENT_ID, "").orEmpty())
    val clientId: StateFlow<String> = _clientId

    val isConfigured: Boolean get() = _clientId.value.isNotBlank()

    fun setClientId(value: String) {
        val clean = value.trim()
        prefs.edit().putString(KEY_CLIENT_ID, clean).apply()
        _clientId.value = clean
    }

    /** כל "העטיפות" (משבצות הצבע) באותו צבע */
    private val _uniformTiles = MutableStateFlow(prefs.getBoolean(KEY_UNIFORM, false))
    val uniformTiles: StateFlow<Boolean> = _uniformTiles

    fun setUniformTiles(value: Boolean) {
        prefs.edit().putBoolean(KEY_UNIFORM, value).apply()
        _uniformTiles.value = value
    }

    /**
     * מצב התמונות: "all" = כל התמונות, "male" = רק של זמרים גברים (לפי זיהוי וסימון ידני), "none" = בלי תמונות.
     * מי שהיה לו "הצג תמונות" כבוי עובר אוטומטית ל-"none".
     */
    private val _imageMode = MutableStateFlow(
        prefs.getString(KEY_IMAGE_MODE, null)
            ?: if (prefs.getBoolean(KEY_SHOW_IMAGES, true)) MODE_ALL else MODE_NONE
    )
    val imageMode: StateFlow<String> = _imageMode

    fun setImageMode(value: String) {
        val v = if (value in listOf(MODE_ALL, MODE_MALE, MODE_NONE)) value else MODE_ALL
        prefs.edit().putString(KEY_IMAGE_MODE, v).apply()
        _imageMode.value = v
    }

    // ---------- קוד לסימון ידני של אמנים ----------

    val hasCode: Boolean get() = prefs.getString(KEY_CODE, null) != null

    fun setCode(code: String) {
        prefs.edit().putString(KEY_CODE, hash(code)).apply()
    }

    fun checkCode(code: String): Boolean = prefs.getString(KEY_CODE, null) == hash(code)

    private fun hash(code: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(("textplay:" + code).toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** שפת זיהוי הדיבור של העוזר הקולי: he-IL או en-US */
    private val _voiceLanguage = MutableStateFlow(prefs.getString(KEY_VOICE_LANG, "he-IL").orEmpty().ifBlank { "he-IL" })
    val voiceLanguage: StateFlow<String> = _voiceLanguage

    fun setVoiceLanguage(value: String) {
        prefs.edit().putString(KEY_VOICE_LANG, value).apply()
        _voiceLanguage.value = value
    }

    /** כמה שניות מקשיבים לכל היותר לפני שמחפשים (1–10) */
    private val _listenSeconds = MutableStateFlow(prefs.getInt(KEY_LISTEN_SECONDS, 3).coerceIn(1, 10))
    val listenSeconds: StateFlow<Int> = _listenSeconds

    fun setListenSeconds(value: Int) {
        val v = value.coerceIn(1, 10)
        prefs.edit().putInt(KEY_LISTEN_SECONDS, v).apply()
        _listenSeconds.value = v
    }

    /** אחרי ניגון קולי — לפתוח את אפליקציית Spotify על השיר (כבוי = נשארים ברקע) */
    private val _openSpotify = MutableStateFlow(prefs.getBoolean(KEY_OPEN_SPOTIFY, true))
    val openSpotify: StateFlow<Boolean> = _openSpotify

    fun setOpenSpotify(value: Boolean) {
        prefs.edit().putBoolean(KEY_OPEN_SPOTIFY, value).apply()
        _openSpotify.value = value
    }

    /** באיזה נגן מנגנים בקול: "spotify" או "ytm" (YouTube Music) */
    private val _musicApp = MutableStateFlow(prefs.getString(KEY_MUSIC_APP, "spotify").orEmpty())
    val musicApp: StateFlow<String> = _musicApp
    val useYtm get() = _musicApp.value == "ytm"

    fun setMusicApp(value: String) {
        prefs.edit().putString(KEY_MUSIC_APP, value).apply()
        _musicApp.value = value
    }

    /** מפתח API של Gemini (אופציונלי) — להבנת בקשות קוליות */
    private val _geminiKey = MutableStateFlow(prefs.getString(KEY_GEMINI, "").orEmpty())
    val geminiKey: StateFlow<String> = _geminiKey

    fun setGeminiKey(value: String) {
        val clean = value.trim()
        prefs.edit().putString(KEY_GEMINI, clean).apply()
        _geminiKey.value = clean
    }

    // ---------- חיפושים אחרונים ----------

    private val _recentSearches = MutableStateFlow(
        prefs.getString(KEY_RECENT, "").orEmpty().split("\n").filter { it.isNotBlank() }
    )
    val recentSearches: StateFlow<List<String>> = _recentSearches

    fun addRecentSearch(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        saveRecent((listOf(q) + _recentSearches.value.filterNot { it.equals(q, ignoreCase = true) }).take(15))
    }

    fun removeRecentSearch(query: String) = saveRecent(_recentSearches.value - query)

    fun clearRecentSearches() = saveRecent(emptyList())

    private fun saveRecent(list: List<String>) {
        prefs.edit().putString(KEY_RECENT, list.joinToString("\n")).apply()
        _recentSearches.value = list
    }

    companion object {
        private const val KEY_RECENT = "recent_searches"
        private const val KEY_SHOW_IMAGES = "show_images"
        private const val KEY_IMAGE_MODE = "image_mode"
        private const val KEY_CODE = "artist_code"
        const val MODE_ALL = "all"
        const val MODE_MALE = "male"
        const val MODE_NONE = "none"
        private const val KEY_VOICE_LANG = "voice_lang"
        private const val KEY_GEMINI = "gemini_key"
        private const val KEY_LISTEN_SECONDS = "listen_seconds"
        private const val KEY_MUSIC_APP = "music_app"
        private const val KEY_OPEN_SPOTIFY = "open_spotify"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_UNIFORM = "uniform_tiles"

        /** Client ID של Spotify הוא 32 תווים הקסדצימליים */
        fun isValidClientId(value: String): Boolean =
            value.trim().matches(Regex("^[0-9a-fA-F]{32}$"))
    }
}

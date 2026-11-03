package com.example.spotalk

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.spotalk.api.Item
import com.example.spotalk.api.ItemType
import com.example.spotalk.api.SpotifyApi
import com.example.spotalk.auth.AuthManager
import com.example.spotalk.log.AppLog
import com.example.spotalk.remote.RemotePlayer
import com.example.spotalk.voice.VoiceCommandParser
import com.example.spotalk.voice.VoiceController
import com.example.spotalk.ai.GeminiClient
import com.example.spotalk.ai.GeminiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class Tab { HOME, SEARCH, LIBRARY }

sealed interface Screen {
    data class Root(val tab: Tab) : Screen
    data class Playlist(val id: String, val title: String) : Screen
    data class Album(val id: String, val title: String) : Screen
    data class Artist(val id: String, val title: String) : Screen
    data object Liked : Screen
    data class ArtistLiked(val artistId: String, val artistName: String) : Screen
    data class Discography(val artistId: String, val artistName: String) : Screen
    data object Settings : Screen
    data object Queue : Screen
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val settings = (app as TextPlayApp).settings
    val auth = AuthManager(app, settings)
    val api = SpotifyApi(auth, app.filesDir)
    val player = (app as TextPlayApp).player

    private val _stack = MutableStateFlow<List<Screen>>(listOf(Screen.Root(Tab.HOME)))
    val stack: StateFlow<List<Screen>> = _stack

    private val _tab = MutableStateFlow(Tab.HOME)
    val tab: StateFlow<Tab> = _tab

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError

    init {
        com.example.spotalk.ui.Palette.uniformTiles = settings.uniformTiles.value
        // התחברות ישנה בלי הרשאת שליטה בנגן — צריך להתחבר מחדש פעם אחת
        // (נבדק רק על התחברות ישנה — כדי לא להיכנס ללולאה אם Spotify לא מחזירה את ההרשאה ברשימה)
        if (auth.isLegacyLogin) {
            AppLog.w("Auth", "חסרה הרשאה ${SpotifyConfig.REMOTE_SCOPE} — מבקשים התחברות מחדש")
            auth.logout()
            _authError.value = "צריך להתחבר מחדש פעם אחת כדי לאשר הרשאות חדשות (שליטה בנגן, הוספה לפלייליסטים)"
        }
        prefetch()
    }

    /** טוען ברקע את "שירים שאהבתי" — כדי שעמודי אמנים והרשימה ייפתחו מהר */
    private fun prefetch() {
        if (!auth.loggedIn.value) return
        viewModelScope.launch {
            // קודם נותנים למסך הבית להיטען
            kotlinx.coroutines.delay(5000)
            runCatching { api.likedTracks() }
                .onSuccess { AppLog.d("Prefetch", "נטענו ${it.size} שירים שאהבתי") }
                .onFailure { AppLog.w("Prefetch", "טעינת שירים שאהבתי נכשלה: ${it.message}") }
        }
    }

    // ---------- הוספה לפלייליסט ----------

    /** השירים שממתינים לבחירת פלייליסט (null = החלון סגור) */
    private val _addTarget = MutableStateFlow<List<String>?>(null)
    val addTarget: StateFlow<List<String>?> = _addTarget

    /** הודעה קצרה למשתמש (למשל "נוסף ל...") */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun requestAddToPlaylist(uris: List<String>) {
        if (uris.isNotEmpty()) _addTarget.value = uris
    }

    fun cancelAddToPlaylist() {
        _addTarget.value = null
    }

    /** זיכרון זמני: אילו שירים כבר נמצאים בכל פלייליסט */
    private val playlistCache = mutableMapOf<String, Set<String>>()

    private suspend fun urisOf(playlistId: String, refresh: Boolean = false): Set<String> {
        if (!refresh) playlistCache[playlistId]?.let { return it }
        return api.playlistUris(playlistId).also { playlistCache[playlistId] = it }
    }

    /** האם כל השירים שנבחרו כבר נמצאים בפלייליסט (לסימון ✓ בחלון הבחירה) */
    suspend fun playlistContains(playlistId: String, uris: List<String>): Boolean =
        urisOf(playlistId).containsAll(uris)

    fun addToPlaylist(playlist: Item) {
        val uris = _addTarget.value ?: return
        _addTarget.value = null
        viewModelScope.launch {
            try {
                // בודקים מחדש מול Spotify — כדי לא להוסיף כפילויות
                val existing = urisOf(playlist.id, refresh = true)
                val toAdd = uris.distinct().filterNot { it in existing }
                if (toAdd.isEmpty()) {
                    AppLog.i("Playlist", "דילוג — כבר קיים ב-${playlist.title}")
                    _message.value = "כבר נמצא ב-${playlist.title}"
                    return@launch
                }
                api.addToPlaylist(playlist.id, toAdd)
                playlistCache[playlist.id] = existing + toAdd
                AppLog.i("Playlist", "נוספו ${toAdd.size} שירים ל-${playlist.title}")
                val skipped = uris.size - toAdd.size
                _message.value = if (skipped > 0) {
                    "נוסף ל-${playlist.title} ($skipped כבר היו שם)"
                } else {
                    "✓ נוסף ל-${playlist.title}"
                }
            } catch (e: Exception) {
                AppLog.e("Playlist", "הוספה ל-${playlist.title} נכשלה", e)
                _message.value = "ההוספה נכשלה: ${e.message}"
            }
        }
    }

    /** מעקב / הסרת מעקב אחרי אמן. מחזיר את המצב החדש (או הישן אם נכשל) */
    suspend fun setFollowing(artist: Item, follow: Boolean): Boolean = try {
        api.setInLibrary(artist.uri, follow)
        AppLog.i("Follow", "${if (follow) "מעקב" else "הסרת מעקב"}: ${artist.title}")
        _message.value = if (follow) "עוקב אחרי ${artist.title}" else "הוסר המעקב אחרי ${artist.title}"
        follow
    } catch (e: Exception) {
        AppLog.e("Follow", "שינוי מעקב נכשל", e)
        _message.value = "הפעולה נכשלה: ${e.message}"
        !follow
    }

    // ---------- ניגון מתוך רשימה ----------

    /**
     * מנגן רשימה החל מהשיר שנבחר.
     * קודם דרך ה-Web API (מתחיל ישר מהשיר, בלי הבזק של השיר הראשון);
     * אם זה לא עובד — דרך App Remote, כמו קודם.
     */
    fun playInList(contextUri: String, index: Int, trackUri: String) {
        viewModelScope.launch {
            val ok = try {
                val device = api.phoneDeviceId()
                api.playContextAt(contextUri, trackUri, device)
                AppLog.i("Play", "Web API: $contextUri מ-$trackUri (device=${device != null})")
                true
            } catch (e: Exception) {
                AppLog.w("Play", "Web API נכשל (${e.message}) — עוברים ל-App Remote")
                false
            }
            if (!ok) player.playFromContext(contextUri, index, trackUri)
        }
    }

    /**
     * מנגן רשימת שירים קבועה (למשל "שירים שאהבתם" של אמן) החל מהשיר שנבחר.
     * ל-Spotify אין הקשר אמין לרשימה כזו — לכן שולחים את השירים עצמם.
     */
    fun playTrackList(uris: List<String>, trackUri: String) {
        viewModelScope.launch {
            // עד 100 שירים, כך שהשיר שנבחר תמיד בפנים
            val start = (uris.indexOf(trackUri) - 20).coerceAtLeast(0)
            val window = uris.drop(start).take(100)
            try {
                api.playUrisAt(window, trackUri, api.phoneDeviceId())
                AppLog.i("Play", "רשימה של ${window.size} שירים מ-$trackUri")
            } catch (e: Exception) {
                AppLog.w("Play", "ניגון רשימה נכשל (${e.message}) — מנגן רק את השיר")
                player.play(trackUri)
            }
        }
    }

    // ---------- עוזר קולי ----------

    /** הקשבה מובנית — מסתיימת לבד כשמפסיקים לדבר */
    val voice = VoiceController(app)

    fun startVoice() {
        voice.start(settings.voiceLanguage.value, settings.listenSeconds.value) { heard ->
            handleVoice(heard.first(), heard.drop(1))
        }
    }

    fun cancelVoice() = voice.stop()

    override fun onCleared() {
        voice.stop()
        super.onCleared()
    }

    /** מצב ה-AI להצגה בהגדרות: "✓ ..." כשעובד, אחרת הסיבה לכשל. null = עוד לא נבדק */
    private val _aiStatus = MutableStateFlow<String?>(null)
    val aiStatus: StateFlow<String?> = _aiStatus

    /** בדיקת מפתח Gemini בלי לדבר — מציגה בהגדרות אם עובד או למה לא */
    fun testGemini() {
        val key = settings.geminiKey.value
        if (key.isBlank()) {
            _aiStatus.value = "קודם צריך להכניס מפתח ולשמור"
            return
        }
        _aiStatus.value = "בודק…"
        viewModelScope.launch {
            val r = askGemini(key, listOf("תנגן שיר של עומר אדם"))
            _aiStatus.value = if (r.query != null) "✓ עובד. לדוגמה: \"${r.query}\"" else "נכשל: ${r.error}"
        }
    }

    private suspend fun askGemini(key: String, heard: List<String>): GeminiResult = try {
        GeminiClient.songQuery(key, heard)
    } catch (e: Exception) {
        GeminiResult(null, "${e.javaClass.simpleName}: ${e.message.orEmpty()}".take(140))
    }

    /** חיפוש שהגיע מהעוזר הקולי — מסך החיפוש ממלא אותו בתיבה ומחפש */
    private val _voiceQuery = MutableStateFlow<String?>(null)
    val voiceQuery: StateFlow<String?> = _voiceQuery

    fun consumeVoiceQuery() {
        _voiceQuery.value = null
    }

    fun showMessage(text: String) {
        _message.value = text
    }

    private fun showSearch(query: String) {
        addRecentSearch(query)
        selectTab(Tab.SEARCH)
        _voiceQuery.value = query
    }

    /**
     * מקבל את מה שנאמר (בין אם "תחפש את השיר X", "תנגן X" או סתם "X"):
     * אם הוגדר מפתח Gemini — ה-AI מבין מה התכוונת ומחזיר שאילתה נקייה;
     * מציג את תוצאות החיפוש, מחכה שנייה, ומנגן את השיר הראשון בתוצאות.
     */
    fun handleVoice(spoken: String, alternatives: List<String> = emptyList()) {
        val cmd = VoiceCommandParser.parse(spoken)
        if (cmd == null) {
            AppLog.w("Voice", "לא הובנה פקודה: \"$spoken\"")
            _message.value = "לא הבנתי — נסה שוב"
            return
        }
        if (settings.useYtm) {
            if (cmd.action == com.example.spotalk.voice.VoiceAction.QUEUE) {
                _message.value = "הוספה לתור לא נתמכת ב-YouTube Music"
                return
            }
            viewModelScope.launch {
                var query = cmd.query
                val key = settings.geminiKey.value
                if (key.isNotBlank() && !cmd.isPlaylist) {
                    askGemini(key, (listOf(spoken) + alternatives).distinct().take(5)).query?.let { query = it }
                }
                val ok = com.example.spotalk.voice.YtmPlayer.play(getApplication(), query, cmd.isPlaylist)
                _message.value = if (ok) "YouTube Music: $query" else "YouTube Music לא מותקנת או לא הגיבה"
            }
            return
        }
        if (cmd.isPlaylist) {
            viewModelScope.launch {
                try {
                    val pl = api.findPlaylist(cmd.query)
                    if (pl == null) {
                        _message.value = "לא נמצא פלייליסט: ${cmd.query}"
                    } else {
                        try {
                            api.playContext(pl.uri, api.phoneDeviceId())
                        } catch (e: Exception) {
                            player.play(pl.uri)
                        }
                        _message.value = "מנגן פלייליסט: ${pl.title}"
                    }
                } catch (e: Exception) {
                    AppLog.e("Voice", "פלייליסט קולי נכשל", e)
                    _message.value = "הפלייליסט נכשל: ${e.message}"
                }
            }
            return
        }
        viewModelScope.launch {
            var query = cmd.query
            val key = settings.geminiKey.value
            if (key.isNotBlank()) {
                _message.value = "מבין את הבקשה…"
                val heard = (listOf(spoken) + alternatives).distinct().take(5)
                val refined = askGemini(key, heard)
                if (refined.query != null) {
                    query = refined.query
                    _aiStatus.value = "✓ עובד. לדוגמה: \"${refined.query}\""
                } else {
                    _aiStatus.value = "נכשל: ${refined.error}"
                    _message.value = "ה-AI לא ענה (${refined.error.orEmpty().take(60)}) — מחפש לפי מה שנשמע"
                }
            }
            AppLog.i("Voice", "\"$spoken\" ← \"$query\"")
            if (cmd.action != com.example.spotalk.voice.VoiceAction.QUEUE) showSearch(query)
            try {
                if (cmd.action != com.example.spotalk.voice.VoiceAction.QUEUE) kotlinx.coroutines.delay(1000)
                var first = api.searchTracks(query, 5).firstOrNull()
                if (first == null && query != cmd.query) first = api.searchTracks(cmd.query, 5).firstOrNull()
                if (first == null) {
                    _message.value = "לא נמצא שיר: $query"
                } else if (cmd.action == com.example.spotalk.voice.VoiceAction.QUEUE) {
                    player.addToQueue(first.uri) { _ ->
                        _message.value = "נוסף לתור: ${first.title} — ${first.subtitle}"
                    }
                } else {
                    player.play(first.uri)
                    _message.value = "מנגן: ${first.title} — ${first.subtitle}"
                }
            } catch (e: Exception) {
                AppLog.e("Voice", "חיפוש קולי נכשל", e)
                _message.value = "החיפוש נכשל: ${e.message}"
            }
        }
    }

    // ---------- חיפושים אחרונים ----------

    fun addRecentSearch(query: String) = settings.addRecentSearch(query)
    fun removeRecentSearch(query: String) = settings.removeRecentSearch(query)
    fun clearRecentSearches() = settings.clearRecentSearches()

    // ---------- תור השמעה ----------

    /** הוספה לסוף התור של Spotify */
    fun queueTrack(item: Item) {
        player.addToQueue(item.uri) { _message.value = "נוסף לתור: ${item.title}" }
    }

    /** פתיחת פרופיל אמן לפי spotify:artist:ID */
    fun openArtistUri(uri: String, name: String) {
        val id = uri.substringAfterLast(":")
        if (uri.startsWith("spotify:artist:") && id.isNotBlank()) open(Screen.Artist(id, name))
    }

    val genders get() = getApplication<TextPlayApp>().genders

    fun setImageMode(mode: String) {
        settings.setImageMode(mode)
        com.example.spotalk.ui.Images.apply(mode)
    }

    fun setUniformTiles(value: Boolean) {
        settings.setUniformTiles(value)
        com.example.spotalk.ui.Palette.uniformTiles = value
    }

    fun clearMessage() {
        _message.value = null
    }

    fun selectTab(tab: Tab) {
        _tab.value = tab
        _stack.value = listOf(Screen.Root(tab))
    }

    fun open(screen: Screen) {
        _stack.value = _stack.value + screen
    }

    /** פתיחת פריט שאינו שיר (אלבום / אמן / פלייליסט) */
    fun openItem(item: Item) {
        when (item.type) {
            ItemType.ALBUM -> open(Screen.Album(item.id, item.title))
            ItemType.ARTIST -> open(Screen.Artist(item.id, item.title))
            ItemType.PLAYLIST -> open(Screen.Playlist(item.id, item.title))
            ItemType.TRACK -> player.play(item.uri)
        }
    }

    fun back(): Boolean {
        if (_stack.value.size <= 1) return false
        _stack.value = _stack.value.dropLast(1)
        return true
    }

    /**
     * שמירת Client ID. אם הוא השתנה — מתנתקים, כי הטוקנים שייכים ל-Client ID הקודם.
     */
    fun saveClientId(value: String) {
        val clean = value.trim()
        if (clean == settings.clientId.value) return
        player.disconnect()
        auth.logout()
        settings.setClientId(clean)
        _authError.value = null
        selectTab(Tab.HOME)
    }

    fun handleRedirect(uri: Uri) {
        viewModelScope.launch {
            auth.handleRedirect(uri)
                .onSuccess {
                    _authError.value = null
                    player.connect()
                    prefetch()
                }
                .onFailure { _authError.value = it.message }
        }
    }

    fun logout() {
        com.example.spotalk.ui.LoadCache.clear()
        api.clearLikedCache()
        api.clearResponseCache()
        player.disconnect()
        auth.logout()
        selectTab(Tab.HOME)
    }
}

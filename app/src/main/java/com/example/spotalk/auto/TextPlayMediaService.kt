package com.example.spotalk.auto

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import com.example.spotalk.MainActivity
import com.example.spotalk.TextPlayApp
import com.example.spotalk.ai.GeminiClient
import com.example.spotalk.api.Item
import com.example.spotalk.api.NowPlaying
import com.example.spotalk.api.SpotifyApi
import com.example.spotalk.auth.AuthManager
import com.example.spotalk.log.AppLog
import com.example.spotalk.voice.VoiceCommandParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * אפליקציית מדיה ל-Android Auto.
 * מציגה בצג הרכב: שירים שאהבתי, פלייליסטים, הושמעו לאחרונה וחיפוש.
 * תומכת בפקודה קולית ("תנגן X") ובשליטה בנגן (ניגון / השהיה / הבא / הקודם).
 * הניגון עצמו נעשה באפליקציית Spotify בטלפון, דרך ה-Web API.
 */
class TextPlayMediaService : MediaBrowserServiceCompat() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var session: MediaSessionCompat
    private var pollJob: Job? = null

    private var apiHolder: SpotifyApi? = null

    /** רשימת ה-URI של הרשימה האחרונה שנטענה (שירים שאהבתי / הושמעו לאחרונה) — לניגון ברצף */
    private var lastList: List<String> = emptyList()

    private val settings get() = (application as TextPlayApp).settings

    /** ה-API נוצר רק כשיש התחברות (גם אם המשתמש התחבר אחרי שהשירות עלה) */
    private fun api(): SpotifyApi? {
        apiHolder?.let { return it }
        val auth = AuthManager(applicationContext, settings)
        if (!auth.loggedIn.value) return null
        return SpotifyApi(auth, filesDir).also { apiHolder = it }
    }

    override fun onCreate() {
        super.onCreate()
        session = MediaSessionCompat(this, "TextPlayAuto").apply {
            setSessionActivity(
                PendingIntent.getActivity(
                    this@TextPlayMediaService, 0,
                    Intent(this@TextPlayMediaService, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            setCallback(callback)
            isActive = true
        }
        sessionToken = session.sessionToken
        setState(PlaybackStateCompat.STATE_NONE, 0)
        startPolling()
        AppLog.i("Auto", "שירות המדיה עלה")
    }

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        session.isActive = false
        session.release()
        AppLog.i("Auto", "שירות המדיה נסגר")
        super.onDestroy()
    }

    // ---------- הרשאת גישה ----------

    private val allowedClients = setOf(
        "com.google.android.projection.gearhead",   // Android Auto
        "com.google.android.googlequicksearchbox",  // Google Assistant
        "com.google.android.autosimulator",
        "com.google.android.carassistant",
        "com.android.systemui",
        "com.android.car.media"
    )

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        val allowed = clientUid == Process.SYSTEM_UID ||
            clientPackageName == packageName ||
            clientPackageName in allowedClients
        if (!allowed) {
            AppLog.w("Auto", "גישה נדחתה ל-$clientPackageName")
            return null
        }
        val extras = Bundle().apply {
            // מאפשר חיפוש בצג הרכב
            putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
        }
        return BrowserRoot(ROOT, extras)
    }

    // ---------- עץ הגלישה ----------

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        result.detach()
        scope.launch {
            val api = api()
            if (api == null) {
                setError("פתח את האפליקציה בטלפון והתחבר ל-Spotify")
                result.sendResult(mutableListOf())
                return@launch
            }
            val children = try {
                load(api, parentId)
            } catch (e: Exception) {
                AppLog.e("Auto", "טעינת $parentId נכשלה", e)
                setError("הטעינה נכשלה: ${e.message.orEmpty().take(80)}")
                mutableListOf()
            }
            result.sendResult(children)
        }
    }

    private suspend fun load(api: SpotifyApi, parentId: String): MutableList<MediaBrowserCompat.MediaItem> =
        when {
            parentId == ROOT -> mutableListOf(
                folder(LIKED, "שירים שאהבתי"),
                folder(PLAYLISTS, "פלייליסטים"),
                folder(RECENT, "הושמעו לאחרונה")
            )
            parentId == LIKED -> {
                val tracks = (api.likedTracksCached() ?: api.likedTracks()).take(MAX_TRACKS)
                lastList = tracks.map { it.uri }
                tracks.map { track(it, "") }.toMutableList()
            }
            parentId == RECENT -> {
                val tracks = api.recentlyPlayed().take(MAX_TRACKS)
                lastList = tracks.map { it.uri }
                tracks.map { track(it, "") }.toMutableList()
            }
            parentId == PLAYLISTS ->
                api.myPlaylists().take(MAX_PLAYLISTS)
                    .map { folder("$PLAYLIST_PREFIX${it.id}", it.title, allowedImage(it.imageUrl, emptyList())) }.toMutableList()
            parentId.startsWith(PLAYLIST_PREFIX) -> {
                val id = parentId.removePrefix(PLAYLIST_PREFIX)
                val c = api.playlist(id)
                (c.tracks ?: emptyList()).take(MAX_TRACKS).map { track(it, c.uri) }.toMutableList()
            }
            else -> mutableListOf()
        }

    override fun onSearch(
        query: String,
        extras: Bundle?,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        result.detach()
        scope.launch {
            val api = api()
            val items = try {
                if (api == null) mutableListOf() else {
                    val found = api.searchTracks(query, 10)
                    lastList = found.map { it.uri }
                    found.map { track(it, "") }.toMutableList()
                }
            } catch (e: Exception) {
                AppLog.e("Auto", "חיפוש נכשל", e)
                mutableListOf()
            }
            result.sendResult(items)
        }
    }

    /** תמונה מותרת לפי מצב התמונות (כל / רק גברים / בלי) */
    private fun allowedImage(url: String?, artistIds: List<String>): String? {
        if (url == null || !com.example.spotalk.ui.Images.enabled) return null
        if (!com.example.spotalk.ui.Images.maleOnly) return url
        if (artistIds.isEmpty()) return null
        val genders = (application as TextPlayApp).genders
        genders.request(artistIds)
        return if (genders.allMale(artistIds)) url else null
    }

    private fun folder(id: String, title: String, icon: String? = null) = MediaBrowserCompat.MediaItem(
        MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setIconUri(icon?.let(Uri::parse))
            .build(),
        MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
    )

    /** mediaId של שיר: t|<trackUri>|<contextUri או ריק> */
    private fun track(item: Item, contextUri: String) = MediaBrowserCompat.MediaItem(
        MediaDescriptionCompat.Builder()
            .setMediaId("t|${item.uri}|$contextUri")
            .setTitle(item.title)
            .setSubtitle(item.subtitle)
            .setIconUri(allowedImage(item.imageUrl, item.artistIds)?.let(Uri::parse))
            .build(),
        MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
    )

    // ---------- שליטה בנגן ----------

    private val callback = object : MediaSessionCompat.Callback() {

        override fun onPlay() = command("PUT", "/me/player/play")
        override fun onPause() = command("PUT", "/me/player/pause")
        override fun onStop() = command("PUT", "/me/player/pause")
        override fun onSkipToNext() = command("POST", "/me/player/next")
        override fun onSkipToPrevious() = command("POST", "/me/player/previous")

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            val parts = (mediaId ?: return).split("|", limit = 3)
            if (parts.size < 3 || parts[0] != "t") return
            val trackUri = parts[1]
            val context = parts[2]
            scope.launch {
                val api = api() ?: return@launch setError("פתח את האפליקציה והתחבר ל-Spotify")
                try {
                    playTrack(api, trackUri, context, lastList)
                    refreshSoon()
                } catch (e: Exception) {
                    AppLog.e("Auto", "ניגון נכשל", e)
                    setError(playError(e))
                }
            }
        }

        /** "היי גוגל, תנגן X ב-Spotalk" */
        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            scope.launch {
                val api = api() ?: return@launch setError("פתח את האפליקציה והתחבר ל-Spotify")
                try {
                    val spoken = query.orEmpty().trim()
                    if (spoken.isBlank()) {
                        // "תנגן מוזיקה" בלי שם — שירים שאהבתי
                        val liked = (api.likedTracksCached() ?: api.likedTracks()).map { it.uri }
                        val first = liked.firstOrNull() ?: return@launch setError("אין שירים שאהבתי")
                        playTrack(api, first, "", liked)
                    } else {
                        var q = VoiceCommandParser.parse(spoken)?.query ?: spoken
                        val key = settings.geminiKey.value
                        if (key.isNotBlank()) GeminiClient.songQuery(key, listOf(spoken)).query?.let { q = it }
                        var first = api.searchTracks(q, 5).firstOrNull()
                        if (first == null && q != spoken) first = api.searchTracks(spoken, 5).firstOrNull()
                        if (first == null) return@launch setError("לא נמצא שיר: $q")
                        playTrack(api, first.uri, "", listOf(first.uri))
                    }
                    refreshSoon()
                } catch (e: Exception) {
                    AppLog.e("Auto", "ניגון מחיפוש נכשל", e)
                    setError(playError(e))
                }
            }
        }
    }

    private fun command(method: String, path: String) {
        scope.launch {
            val api = api() ?: return@launch setError("פתח את האפליקציה והתחבר ל-Spotify")
            try {
                api.playerCommand(method, path)
                refreshSoon()
            } catch (e: Exception) {
                AppLog.e("Auto", "פקודה $path נכשלה", e)
                setError(playError(e))
            }
        }
    }

    private suspend fun playTrack(api: SpotifyApi, trackUri: String, contextUri: String, list: List<String>) {
        val device = try { api.phoneDeviceId() } catch (e: Exception) { null }
        when {
            contextUri.isNotBlank() -> api.playContextAt(contextUri, trackUri, device)
            list.isNotEmpty() -> {
                val start = (list.indexOf(trackUri) - 20).coerceAtLeast(0)
                api.playUrisAt(list.drop(start).take(100), trackUri, device)
            }
            else -> api.playUrisAt(listOf(trackUri), trackUri, device)
        }
    }

    private fun playError(e: Exception): String {
        val m = e.message.orEmpty()
        return if (m.contains("NO_ACTIVE_DEVICE", ignoreCase = true) || m.contains("404"))
            "פתח את אפליקציית Spotify בטלפון ונסה שוב"
        else "הניגון נכשל: ${m.take(80)}"
    }

    // ---------- סנכרון מצב הנגן לצג ----------

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                refreshNow()
                delay(3000)
            }
        }
    }

    private fun refreshSoon() {
        scope.launch {
            delay(800)
            refreshNow()
        }
    }

    private suspend fun refreshNow() {
        val api = api() ?: return
        val np = try { api.nowPlaying() } catch (e: Exception) { return }
        if (np == null) {
            setState(PlaybackStateCompat.STATE_NONE, 0)
            return
        }
        session.setMetadata(metadata(np))
        setState(if (np.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, np.progressMs)
    }

    private fun metadata(np: NowPlaying) = MediaMetadataCompat.Builder()
        .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, np.uri)
        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, np.title)
        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, np.artist)
        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, np.album)
        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, np.durationMs)
        .apply {
            allowedImage(np.imageUrl, np.artistIds)?.let {
                putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, it)
                putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, it)
            }
        }
        .build()

    private val actions = PlaybackStateCompat.ACTION_PLAY or
        PlaybackStateCompat.ACTION_PAUSE or
        PlaybackStateCompat.ACTION_PLAY_PAUSE or
        PlaybackStateCompat.ACTION_STOP or
        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
        PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
        PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH

    private fun setState(state: Int, positionMs: Long) {
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, positionMs, if (state == PlaybackStateCompat.STATE_PLAYING) 1f else 0f)
                .build()
        )
    }

    /** מציג הודעת שגיאה בצג הרכב */
    private fun setError(message: String) {
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(PlaybackStateCompat.STATE_ERROR, 0, 0f)
                .setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, message)
                .build()
        )
    }

    private companion object {
        const val ROOT = "root"
        const val LIKED = "liked"
        const val PLAYLISTS = "playlists"
        const val RECENT = "recent"
        const val PLAYLIST_PREFIX = "pl:"
        const val MAX_TRACKS = 100
        const val MAX_PLAYLISTS = 50
    }
}

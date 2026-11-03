package com.example.spotalk.remote

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.service.media.MediaBrowserService
import android.os.Handler
import android.os.Looper
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.types.PlayerState
import com.spotify.protocol.types.Repeat
import com.example.spotalk.SpotifyConfig
import com.example.spotalk.log.AppLog
import com.example.spotalk.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.lang.ref.WeakReference

data class NowPlaying(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val error: String? = null,
    val trackUri: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    /** מתי נמדד positionMs — כדי לחשב התקדמות בזמן אמת */
    val positionStamp: Long = 0,
    val isPaused: Boolean = true,
    val shuffle: Boolean = false,
    val repeatMode: Int = Repeat.OFF,
    val canSkipNext: Boolean = true,
    val canSkipPrev: Boolean = true,
    val contextTitle: String = "",
    val isLiked: Boolean = false,
    /** הודעה זמנית — למשל למה פקודת ניגון נכשלה */
    val notice: String? = null,
    /** כל אמני השיר: שם ו-URI (כדי לפתוח את הפרופיל מהנגן) */
    val artists: List<Pair<String, String>> = emptyList(),
    /** מונה שמתקדם בכל שינוי שיר — כדי לרענן את מסך התור */
    val trackChangeCount: Int = 0,
    /** החיבור נתקע — כנראה Spotify לא רץ ברקע וצריך לפתוח אותו לרגע */
    val canWake: Boolean = false,
    val canLike: Boolean = false
) {
    val hasTrack get() = trackUri != null

    fun livePosition(now: Long = System.currentTimeMillis()): Long =
        if (isPaused) positionMs
        else (positionMs + (now - positionStamp)).coerceAtMost(durationMs)
}

/** שליטה באפליקציית Spotify המותקנת דרך App Remote SDK הרשמי */
class RemotePlayer(private val context: Context, private val settings: AppSettings) {

    private companion object {
        const val TAG = "Remote"
        const val SPOTIFY_PACKAGE = "com.spotify.music"
    }

    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state

    private var remote: SpotifyAppRemote? = null
    private var lastLibraryCheckUri: String? = null
    private var pendingAction: ((SpotifyAppRemote) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    /**
     * ה-Activity שבחזית. Spotify SDK צריך Activity (לא Application context)
     * כדי להציג את חלון האישור — עם context של Application החיבור נתקע.
     */
    private var activityRef: WeakReference<Activity>? = null
    private var browser: MediaBrowser? = null
    private var autoRetried = false

    fun attach(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    /** האפליקציה ירדה לרקע: מנתקים — אלא אם חיבור באמצע (למשל חלון האישור של Spotify פתוח) */
    /** האם המסך הראשי של האפליקציה פתוח כרגע */
    val hasForegroundUi: Boolean get() = activityRef?.get() != null

    fun onBackground() {
        activityRef = null
        if (_state.value.connecting) {
            AppLog.d(TAG, "ירדנו לרקע באמצע חיבור — לא מנתקים")
            main.removeCallbacks(connectTimeout)
            return
        }
        disconnect()
    }

    /** אם Spotify לא עונה תוך 10 שניות — מפסיקים לחכות ומסבירים מה לעשות */
    private val connectTimeout = Runnable {
        if (_state.value.connecting && remote?.isConnected != true) {
            AppLog.w(TAG, "החיבור לא הסתיים תוך 10 שניות (אין onConnected ואין onFailure)")
            if (!autoRetried) {
                // ניסיון שני אוטומטי: מעירים שוב את Spotify ברקע ומתחברים מחדש
                autoRetried = true
                AppLog.i(TAG, "ניסיון חוזר אוטומטי")
                _state.update { it.copy(connecting = false) }
                connect()
                return@Runnable
            }
            _state.update {
                it.copy(
                    connecting = false,
                    canWake = true,
                    error = "Spotify לא עונה גם אחרי הערה ברקע. אפשר לפתוח אותו לרגע ולחזור"
                )
            }
            autoRetried = false
        }
    }

    fun connect() {
        if (remote?.isConnected == true || _state.value.connecting) {
            AppLog.d(TAG, "connect() דולג: connected=${remote?.isConnected} connecting=${_state.value.connecting}")
            if (_state.value.connecting) {
                // חזרנו לחזית באמצע חיבור — מפעילים מחדש את שעון ההמתנה
                main.removeCallbacks(connectTimeout)
                main.postDelayed(connectTimeout, 10_000)
            }
            return
        }
        if (!settings.isConfigured) {
            _state.update { it.copy(error = "צריך להגדיר Client ID בהגדרות") }
            return
        }
        if (!SpotifyAppRemote.isSpotifyInstalled(context)) {
            _state.update { it.copy(error = "אפליקציית Spotify לא מותקנת במכשיר") }
            return
        }
        val activity = activityRef?.get()
        val connectContext: Context = activity ?: context
        AppLog.i(TAG, "מתחבר ל-App Remote | clientId=${settings.clientId.value.take(6)}… redirect=${SpotifyConfig.REDIRECT_URI} | context=${if (activity != null) "Activity" else "Application"}")
        _state.update { it.copy(connecting = true, error = null, canWake = false) }
        main.removeCallbacks(connectTimeout)
        main.postDelayed(connectTimeout, 10_000)

        wakeSpotify { startAppRemote(connectContext) }
    }

    private fun startAppRemote(connectContext: Context) {
        if (!_state.value.connecting) return
        val params = ConnectionParams.Builder(settings.clientId.value)
            .setRedirectUri(SpotifyConfig.REDIRECT_URI)
            .showAuthView(true)
            .build()

        SpotifyAppRemote.connect(connectContext, params, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                main.removeCallbacks(connectTimeout)
                autoRetried = false
                releaseBrowser()
                AppLog.i(TAG, "onConnected ✓")
                remote = appRemote
                _state.update { it.copy(connected = true, connecting = false, error = null, canWake = false) }
                subscribe(appRemote)
                pendingAction?.let { action ->
                    AppLog.d(TAG, "מבצע פעולה שהמתינה לחיבור")
                    pendingAction = null
                    action(appRemote)
                }
            }

            override fun onFailure(error: Throwable) {
                main.removeCallbacks(connectTimeout)
                AppLog.e(TAG, "onFailure: ${error.javaClass.name}: ${error.message}", error)
                remote = null
                _state.update {
                    it.copy(
                        connected = false,
                        connecting = false,
                        error = explain(error)
                    )
                }
            }
        })
    }

    /** הסבר ברור בעברית לשגיאות החיבור הנפוצות */
    private fun explain(error: Throwable): String = when (error.javaClass.simpleName) {
        "UserNotAuthorizedException" ->
            "Spotify לא אישרה את האפליקציה. בדוק בדשבורד: Android SDK מסומן, שם החבילה וה-SHA1 נשמרו. שינויים בדשבורד יכולים לקחת כמה דקות."
        "CouldNotFindSpotifyApp" -> "אפליקציית Spotify לא מותקנת במכשיר"
        "NotLoggedInException" -> "צריך להתחבר לחשבון באפליקציית Spotify עצמה"
        "OfflineModeException" -> "Spotify במצב לא מקוון"
        "SpotifyDisconnectedException", "SpotifyConnectionTerminatedException" ->
            "החיבור ל-Spotify נותק"
        else -> "אין חיבור ל-Spotify: ${error.javaClass.simpleName}"
    }

    /**
     * מעיר את Spotify ברקע — בלי לפתוח אותו על המסך.
     * מתחברים לשירות המדיה הרשמי של Spotify (MediaBrowserService, אותו שירות ש-Android Auto
     * ושעונים משתמשים בו). עצם ההתחברות מפעילה את התהליך של Spotify, ואז App Remote יכול להתחבר.
     * גם אם Spotify דוחה את הבקשה, התהליך כבר הופעל — וזה כל מה שצריך.
     */
    private fun wakeSpotify(then: () -> Unit) {
        var done = false
        val proceed = {
            if (!done) {
                done = true
                then()
            }
        }
        val component = findSpotifyMediaService()
        if (component == null) {
            AppLog.w(TAG, "לא נמצא שירות המדיה של Spotify — מתחברים בלי הערה")
            proceed()
            return
        }
        releaseBrowser()
        AppLog.d(TAG, "מעיר את Spotify ברקע דרך ${component.className}")
        try {
            val b = MediaBrowser(context, component, object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    AppLog.d(TAG, "Spotify התעורר (MediaBrowser connected)")
                    main.postDelayed({ proceed() }, 300)
                }

                override fun onConnectionFailed() {
                    AppLog.d(TAG, "MediaBrowser נדחה — התהליך של Spotify הופעל בכל זאת")
                    main.postDelayed({ proceed() }, 800)
                }
            }, null)
            browser = b
            b.connect()
        } catch (e: Exception) {
            AppLog.w(TAG, "הערה ברקע נכשלה", e)
            proceed()
            return
        }
        // רשת ביטחון: לא מחכים יותר מ-3 שניות להערה
        main.postDelayed({ proceed() }, 3_000)
    }

    private fun findSpotifyMediaService(): ComponentName? = try {
        context.packageManager
            .queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(SPOTIFY_PACKAGE), 0)
            .firstOrNull()
            ?.serviceInfo
            ?.let { ComponentName(it.packageName, it.name) }
    } catch (e: Exception) {
        null
    }

    private fun releaseBrowser() {
        try {
            browser?.disconnect()
        } catch (_: Exception) {
        }
        browser = null
    }

    /**
     * פותח את אפליקציית Spotify. כשחוזרים ל-TextPlay (onStart) מתחברים מחדש אוטומטית.
     */
    fun openSpotifyApp() {
        AppLog.i(TAG, "פותח את אפליקציית Spotify")
        val intent = context.packageManager.getLaunchIntentForPackage("com.spotify.music")
        if (intent == null) {
            AppLog.w(TAG, "לא נמצא launch intent ל-com.spotify.music")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun disconnect() {
        AppLog.d(TAG, "disconnect()")
        main.removeCallbacks(connectTimeout)
        releaseBrowser()
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
        lastLibraryCheckUri = null
        _state.update { it.copy(connected = false, connecting = false) }
    }

    private fun subscribe(appRemote: SpotifyAppRemote) {
        appRemote.playerApi.subscribeToPlayerState()
            .setEventCallback { ps -> onPlayerState(ps) }
            .setErrorCallback { t -> AppLog.e(TAG, "subscribeToPlayerState נכשל", t) }
        appRemote.playerApi.subscribeToPlayerContext().setEventCallback { ctx ->
            _state.update { it.copy(contextTitle = ctx.title.orEmpty()) }
        }
    }

    private fun onPlayerState(ps: PlayerState) {
        val track = ps.track
        if (track?.uri != _state.value.trackUri || ps.isPaused != _state.value.isPaused) {
            AppLog.d(TAG, "PlayerState: ${track?.uri} paused=${ps.isPaused} pos=${ps.playbackPosition}")
        }
        _state.update {
            it.copy(
                trackUri = track?.uri,
                title = track?.name.orEmpty(),
                artist = track?.artist?.name.orEmpty(),
                artists = track?.artists.orEmpty().mapNotNull { a ->
                    val uri = a.uri ?: return@mapNotNull null
                    (a.name ?: "") to uri
                }.ifEmpty {
                    listOfNotNull(track?.artist?.let { a -> a.uri?.let { (a.name ?: "") to it } })
                },
                trackChangeCount = if (track?.uri != it.trackUri) it.trackChangeCount + 1 else it.trackChangeCount,
                album = track?.album?.name.orEmpty(),
                durationMs = track?.duration ?: 0L,
                positionMs = ps.playbackPosition,
                positionStamp = System.currentTimeMillis(),
                isPaused = ps.isPaused,
                shuffle = ps.playbackOptions?.isShuffling ?: false,
                repeatMode = ps.playbackOptions?.repeatMode ?: Repeat.OFF,
                canSkipNext = ps.playbackRestrictions?.canSkipNext ?: true,
                canSkipPrev = ps.playbackRestrictions?.canSkipPrev ?: true
            )
        }
        val uri = track?.uri
        if (uri != null) {
            val idx = ourQueue.indexOf(uri)
            if (idx >= 0) repeat(idx + 1) { ourQueue.removeAt(0) }
        }
        if (uri != null && uri != lastLibraryCheckUri) {
            lastLibraryCheckUri = uri
            refreshLibraryState(uri)
        }
    }

    private fun refreshLibraryState(uri: String) {
        remote?.userApi?.getLibraryState(uri)?.setResultCallback { ls ->
            if (_state.value.trackUri == uri) {
                _state.update { it.copy(isLiked = ls.isAdded, canLike = ls.canAdd) }
            }
        }
    }

    // ---------- פקודות ----------

    /**
     * מנגן על הטלפון הזה. קודם מעבירים את הניגון למכשיר המקומי —
     * אחרת, אם Spotify מנגן כרגע במחשב או ברמקול, הפקודה תלך לשם.
     */
    fun play(uri: String) = withRemote { r ->
        AppLog.i(TAG, "play $uri")
        r.connectApi.connectSwitchToLocalDevice().report("מעבר לטלפון", silent = true)
        r.playerApi.play(uri).report("הניגון")
    }

    /**
     * ניגון אלבום/פלייליסט/שירים שאהבתי החל משיר מסוים.
     * אם Spotify לא מקבלת skipToIndex על ההקשר — מנגנים לפחות את השיר עצמו.
     */
    fun playFromContext(contextUri: String, index: Int, trackUri: String? = null) = withRemote { r ->
        AppLog.i(TAG, "playFromContext $contextUri #$index")
        r.connectApi.connectSwitchToLocalDevice().report("מעבר לטלפון", silent = true)
        // גרסאות חדשות של Spotify מתעלמות מ-skipToIndex על הקשר שלא מתנגן כרגע.
        // לכן: קודם מפעילים את ההקשר, ורק אחרי שהוא התחיל — קופצים לשיר.
        val fallback = {
            val target = trackUri ?: contextUri
            AppLog.w(TAG, "קפיצה לשיר נכשלה — מנגן ישירות את $target")
            r.playerApi.play(target).report("הניגון")
        }
        r.playerApi.play(contextUri).apply {
            setResultCallback {
                if (index <= 0) return@setResultCallback
                r.playerApi.skipToIndex(contextUri, index).apply {
                    setResultCallback {
                        // וידוא: אם אחרי רגע לא מתנגן השיר שביקשנו — מנגנים אותו ישירות
                        if (trackUri != null) main.postDelayed({
                            if (_state.value.trackUri != trackUri) fallback()
                        }, 2500)
                    }
                    setErrorCallback { t ->
                        AppLog.w(TAG, "skipToIndex נכשל: ${t.message}")
                        fallback()
                    }
                }
            }
            setErrorCallback { t ->
                AppLog.w(TAG, "הפעלת ההקשר נכשלה: ${t.message}")
                fallback()
            }
        }
    }

    fun playShuffled(contextUri: String) = withRemote { r ->
        r.connectApi.connectSwitchToLocalDevice().report("מעבר לטלפון", silent = true)
        r.playerApi.setShuffle(true).report("ערבוב", silent = true)
        r.playerApi.play(contextUri).report("הניגון")
    }

    fun togglePlay() = withRemote {
        (if (_state.value.isPaused) it.playerApi.resume() else it.playerApi.pause()).report("ניגון/השהיה")
    }
    /** נגן/השהה רק אם המצב הנוכחי תואם (לכפתורי המערכת) */
    fun togglePlayIf(paused: Boolean) {
        if (_state.value.isPaused == paused) togglePlay()
    }

    fun next() = withRemote { it.playerApi.skipNext().report("שיר הבא") }
    fun previous() = withRemote { it.playerApi.skipPrevious().report("שיר קודם") }
    fun seekTo(ms: Long) = withRemote { it.playerApi.seekTo(ms).report("גלילה") }
    fun toggleShuffle() = withRemote { it.playerApi.toggleShuffle().report("ערבוב") }
    fun toggleRepeat() = withRemote { it.playerApi.toggleRepeat().report("חזרה") }

    fun toggleLike() = withRemote { r ->
        val s = _state.value
        val uri = s.trackUri ?: return@withRemote
        (if (s.isLiked) r.userApi.removeFromLibrary(uri) else r.userApi.addToLibrary(uri)).report("לייק")
        _state.update { it.copy(isLiked = !s.isLiked) }
    }

    /** שירים שהוספנו לתור ועדיין לא התנגנו (לפי הסדר) */
    private val ourQueue = mutableListOf<String>()

    /** הוספה לתור. מחזיר כמה שירים שהוספת קודם יתנגנו לפניו */
    fun addToQueue(uri: String, onDone: (aheadOfIt: Int) -> Unit) = withRemote { r ->
        AppLog.i(TAG, "queue $uri (לפניו בתור שלנו: ${ourQueue.size})")
        val ahead = ourQueue.size
        r.playerApi.queue(uri).apply {
            setResultCallback {
                ourQueue += uri
                onDone(ahead)
            }
            setErrorCallback { t ->
                AppLog.e(TAG, "הוספה לתור נכשלה", t)
                _state.update { it.copy(notice = "הוספה לתור נכשלה: ${t.message ?: t.javaClass.simpleName}") }
            }
        }
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    /** מציג למשתמש למה פקודה נכשלה, במקום להיכשל בשקט */
    private fun <T> CallResult<T>.report(
        what: String,
        silent: Boolean = false,
        onFail: (() -> Unit)? = null
    ) {
        AppLog.d(TAG, "פקודה: $what")
        setResultCallback { AppLog.d(TAG, "פקודה הצליחה: $what") }
        setErrorCallback { t ->
            AppLog.e(TAG, "פקודה נכשלה: $what — ${t.javaClass.name}: ${t.message}", t)
            if (onFail != null) {
                onFail()
            } else if (!silent) {
                val reason = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                _state.update { it.copy(notice = "$what נכשל: $reason") }
            }
        }
    }

    /** אם עדיין אין חיבור — מתחברים ומבצעים את הפעולה מיד אחרי החיבור */
    private fun withRemote(block: (SpotifyAppRemote) -> Unit) {
        val r = remote
        if (r == null || !r.isConnected) {
            AppLog.d(TAG, "אין חיבור — הפעולה ממתינה ומתחברים")
            pendingAction = block
            connect()
            return
        }
        block(r)
    }
}

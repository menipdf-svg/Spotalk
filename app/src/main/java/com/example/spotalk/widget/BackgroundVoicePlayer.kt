package com.example.spotalk.widget

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.example.spotalk.MainActivity
import com.example.spotalk.TextPlayApp
import com.example.spotalk.ai.GeminiClient
import com.example.spotalk.api.SpotifyApi
import com.example.spotalk.auth.AuthManager
import com.example.spotalk.log.AppLog
import com.example.spotalk.voice.VoiceCommandParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * חיפוש וניגון מהרקע — בלי לפתוח את האפליקציה.
 * משמש את הווידג'ט וכפתור ההפעלה: מבינים מה נאמר, מחפשים, מנגנים את הראשון
 * ומציגים רק הודעה קצרה (Toast). אם אין התחברות — פותחים את האפליקציה להתחברות.
 */
object BackgroundVoicePlayer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * [launcher] = מסך חזיתי (VoiceActivity) שממנו מותר לפתוח את Spotify; [onDone] נקרא בסיום (הצלחה או כשל).
     */
    fun handle(context: Context, heard: List<String>, launcher: Context? = null, onDone: () -> Unit = {}) {
        val app = context.applicationContext as TextPlayApp
        val spoken = heard.firstOrNull().orEmpty()
        val cmd = VoiceCommandParser.parse(spoken)
        if (cmd == null) {
            toast(app, "לא הבנתי — נסה שוב")
            onDone()
            return
        }
        if (app.settings.useYtm) {
            playInYtm(app, cmd, heard, launcher, onDone)
            return
        }
        val auth = AuthManager(app, app.settings)
        if (!auth.loggedIn.value) {
            toast(app, "צריך להתחבר ל-Spotify באפליקציה")
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onDone()
            return
        }
        val api = SpotifyApi(auth, app.filesDir)
        scope.launch {
            try {
                if (cmd.isPlaylist) {
                    val pl = api.findPlaylist(cmd.query)
                    if (pl == null) {
                        toast(app, "לא נמצא פלייליסט: ${cmd.query}")
                        return@launch
                    }
                    try {
                        api.playContext(pl.uri, api.phoneDeviceId())
                    } catch (e: Exception) {
                        AppLog.w("Voice", "פלייליסט דרך ה-API נכשל, מנסה App Remote: ${e.message}")
                        app.player.play(pl.uri)
                    }
                    toast(app, "מנגן פלייליסט: ${pl.title}")
                    if (launcher != null && app.settings.openSpotify.value) openInSpotify(launcher, pl.uri)
                    return@launch
                }
                var query = cmd.query
                val key = app.settings.geminiKey.value
                if (key.isNotBlank()) {
                    val r = try {
                        GeminiClient.songQuery(key, heard.distinct().take(5))
                    } catch (e: Exception) { null }
                    r?.query?.let { query = it }
                }
                AppLog.i("Voice", "[רקע] \"$spoken\" ← \"$query\"")
                var first = api.searchTracks(query, 5).firstOrNull()
                if (first == null && query != cmd.query) first = api.searchTracks(cmd.query, 5).firstOrNull()
                if (first == null) {
                    toast(app, "לא נמצא שיר: $query")
                    onDone()
                    return@launch
                }
                if (cmd.action == com.example.spotalk.voice.VoiceAction.QUEUE) {
                    try {
                        api.addToQueue(first.uri, api.phoneDeviceId())
                    } catch (e: Exception) {
                        AppLog.w("Voice", "תור דרך ה-API נכשל, מנסה App Remote: ${e.message}")
                        app.player.addToQueue(first.uri) {}
                    }
                    toast(app, "נוסף לתור: ${first.title} — ${first.subtitle}")
                    return@launch
                }
                try {
                    api.playUrisAt(listOf(first.uri), first.uri, api.phoneDeviceId())
                } catch (e: Exception) {
                    // אין מכשיר פעיל ב-Web API — מנסים דרך האפליקציה של Spotify
                    AppLog.w("Voice", "ניגון דרך ה-API נכשל, מנסה App Remote: ${e.message}")
                    app.player.play(first.uri)
                }
                toast(app, "מנגן: ${first.title} — ${first.subtitle}")
                if (launcher != null && app.settings.openSpotify.value) openInSpotify(launcher, first.uri)
            } catch (e: Exception) {
                AppLog.e("Voice", "ניגון מהרקע נכשל", e)
                toast(app, "הניגון נכשל: ${e.message.orEmpty().take(60)}")
            } finally {
                onDone()
            }
        }
    }

    /** YouTube Music: Gemini מנסח (לשירים), ואז הפקודה נשלחת לאפליקציה שלה */
    private fun playInYtm(
        app: TextPlayApp,
        cmd: com.example.spotalk.voice.VoiceCommand,
        heard: List<String>,
        launcher: Context?,
        onDone: () -> Unit
    ) {
        if (cmd.action == com.example.spotalk.voice.VoiceAction.QUEUE) {
            toast(app, "הוספה לתור לא נתמכת ב-YouTube Music")
            onDone()
            return
        }
        scope.launch {
            try {
                var query = cmd.query
                val key = app.settings.geminiKey.value
                if (key.isNotBlank() && !cmd.isPlaylist) {
                    val r = try { GeminiClient.songQuery(key, heard.distinct().take(5)) } catch (e: Exception) { null }
                    r?.query?.let { query = it }
                }
                val ok = com.example.spotalk.voice.YtmPlayer.play(app, query, cmd.isPlaylist, launcher)
                toast(app, if (ok) "YouTube Music: $query" else "YouTube Music לא מותקנת או לא הגיבה")
            } finally {
                onDone()
            }
        }
    }

    /** פותח את אפליקציית Spotify על השיר שמתנגן */
    private fun openInSpotify(from: Context, trackUri: String) {
        // במסך נעילה לא פותחים את Spotify (זה היה מבקש לפתוח את המכשיר) — הניגון ממשיך ברקע
        val km = from.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (km?.isKeyguardLocked == true) return
        try {
            from.startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(trackUri)).setPackage("com.spotify.music")
            )
        } catch (e: Exception) {
            AppLog.w("Voice", "פתיחת Spotify נכשלה: ${e.message}")
        }
    }

    private fun toast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }
}

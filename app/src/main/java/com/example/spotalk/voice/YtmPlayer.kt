package com.example.spotalk.voice

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.example.spotalk.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ניגון ב-YouTube Music.
 * אין ל-YouTube Music API רשמי, וגם פקודת "נגן לפי חיפוש" רק פותחת מסך חיפוש.
 * לכן קודם מוצאים את השיר (או הפלייליסט) המדויק בחיפוש הלא-רשמי של YouTube Music,
 * ואז פותחים קישור ישיר שלו באפליקציה — והוא מתחיל לנגן מיד.
 */
object YtmPlayer {

    const val PACKAGE = "com.google.android.apps.youtube.music"

    private const val SONGS_FILTER = "EgWKAQIIAWoMEA4QChADEAQQCRAF"
    private const val PLAYLISTS_FILTER = "EgWKAQIoAWoMEA4QChADEAQQCRAF"

    /** מחזיר true אם הפקודה נשלחה ל-YouTube Music */
    suspend fun play(context: Context, query: String, isPlaylist: Boolean, from: Context? = null): Boolean {
        val link = try {
            resolve(query, isPlaylist)
        } catch (e: Exception) {
            AppLog.w("Ytm", "חיפוש נכשל: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        val intent = if (link != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(PACKAGE)
        } else {
            // גיבוי: חיפוש דרך המערכת (רק פותח את מסך החיפוש)
            AppLog.w("Ytm", "לא נמצא קישור ישיר, פותח חיפוש")
            Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .setPackage(PACKAGE)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                .putExtra(SearchManager.QUERY, query)
        }
        return try {
            if (from != null) from.startActivity(intent)
            else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            AppLog.i("Ytm", "YouTube Music ← \"$query\" → ${link ?: "חיפוש"}")
            true
        } catch (e: Exception) {
            AppLog.e("Ytm", "פתיחת YouTube Music נכשלה", e)
            false
        }
    }

    /** קישור ישיר: https://music.youtube.com/watch?v=ID (שיר) או watch?list=ID (פלייליסט) */
    private suspend fun resolve(query: String, isPlaylist: Boolean): String? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", "WEB_REMIX")
                        .put("clientVersion", "1.20250101.01.00")
                        .put("hl", "iw")
                        .put("gl", "IL")
                )
            )
            .put("query", query)
            .put("params", if (isPlaylist) PLAYLISTS_FILTER else SONGS_FILTER)
            .toString()
        val conn = (URL("https://music.youtube.com/youtubei/v1/search?prettyPrint=false").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Origin", "https://music.youtube.com")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) {
                AppLog.w("Ytm", "HTTP $code: ${text.take(120)}")
                return@withContext null
            }
            val json = JSONObject(text)
            if (isPlaylist) {
                val id = findString(json) { k, v -> k == "browseId" && v.startsWith("VL") }?.removePrefix("VL")
                    ?: findString(json) { k, _ -> k == "playlistId" }
                id?.let { "https://music.youtube.com/watch?list=$it" }
            } else {
                val id = findVideoId(json) ?: findString(json) { k, v -> k == "videoId" && v.length == 11 }
                id?.let { "https://music.youtube.com/watch?v=$it" }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** videoId הראשון שבתוך playlistItemData (שורת שיר בתוצאות) */
    private fun findVideoId(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf { it.isNotBlank() }?.let { return it }
                for (k in node.keys()) findVideoId(node.opt(k))?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findVideoId(node.opt(i))?.let { return it }
        }
        return null
    }

    /** הערך הראשון (לפי סדר המסמך) שמתאים לתנאי */
    private fun findString(node: Any?, match: (String, String) -> Boolean): String? {
        when (node) {
            is JSONObject -> for (k in node.keys()) {
                val v = node.opt(k)
                if (v is String && match(k, v)) return v
                findString(v, match)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findString(node.opt(i), match)?.let { return it }
        }
        return null
    }
}

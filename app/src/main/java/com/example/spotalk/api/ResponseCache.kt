package com.example.spotalk.api

import com.example.spotalk.log.AppLog
import java.io.File
import java.security.MessageDigest

/**
 * מטמון של תשובות GET מ-Spotify, שמור בקבצים.
 * - חוסך בקשות (ומכסה): כל עוד התשובה "טרייה" לא שולחים בקשה בכלל.
 * - כש-Spotify חוסמת (429 / מכסה) או אין רשת — מציגים את התשובה האחרונה שנשמרה.
 */
class ResponseCache(dir: File) {

    // תיקייה חדשה: תשובות ישנות נשמרו עם שמות באנגלית
    private val root = File(dir, "api_cache_he").apply {
        mkdirs()
        File(dir, "api_cache").deleteRecursively()
    }

    /** כמה זמן תשובה נחשבת טרייה, לפי סוג הבקשה. 0 = לא שומרים */
    fun ttlFor(path: String): Long {
        val p = path.substringBefore("?")
        return when {
            p.startsWith("/me/player") && p != "/me/player/recently-played" -> 0
            p == "/me/player/recently-played" -> 2 * MIN
            p.startsWith("/me/library") -> 0
            p == "/me/tracks" -> 0 // "שירים שאהבתי" מנוהל בנפרד
            p == "/me" -> DAY
            p.startsWith("/me/") -> 10 * MIN
            p.startsWith("/playlists/") -> 10 * MIN
            p.startsWith("/albums/") -> 7 * DAY
            p.startsWith("/tracks/") -> 30 * DAY
            p.startsWith("/artists/") -> DAY
            p.startsWith("/search") -> DAY
            else -> 0
        }
    }

    /** תשובה שמורה. fresh = רק אם עוד לא פג תוקפה */
    fun get(path: String, fresh: Boolean): String? {
        val ttl = ttlFor(path)
        if (ttl == 0L) return null
        val f = fileFor(path)
        if (!f.exists()) return null
        if (fresh && System.currentTimeMillis() - f.lastModified() > ttl) return null
        return runCatching { f.readText() }.getOrNull()
    }

    fun put(path: String, body: String) {
        if (ttlFor(path) == 0L || body.isBlank()) return
        runCatching { fileFor(path).writeText(body) }
            .onFailure { AppLog.w("Cache", "שמירה נכשלה: ${it.message}") }
    }

    fun clear() {
        root.listFiles()?.forEach { it.delete() }
    }

    private fun fileFor(path: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(path.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(root, hash)
    }

    private companion object {
        const val MIN = 60_000L
        const val DAY = 24 * 60 * MIN
    }
}

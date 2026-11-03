package com.example.spotalk.log

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * יומן אירועים של האפליקציה, נשמר לקובץ.
 * הקובץ המיוצא כולל גם את ה-logcat של התהליך — שם מופיעות ההודעות הפנימיות של Spotify SDK.
 */
object AppLog {

    private const val MAX_BYTES = 1_000_000L
    private lateinit var file: File
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        file = File(context.filesDir, "textplay.log")

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            e("Crash", "קריסה בתהליכון ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
        i("App", "---- הפעלה: ${appVersion(context)} | ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) ----")
    }

    fun d(tag: String, msg: String) = write("D", tag, msg, null)
    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    @Synchronized
    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        when (level) {
            "E" -> Log.e("TextPlay/$tag", msg, t)
            "W" -> Log.w("TextPlay/$tag", msg, t)
            "I" -> Log.i("TextPlay/$tag", msg)
            else -> Log.d("TextPlay/$tag", msg)
        }
        if (!::file.isInitialized) return
        try {
            val sb = StringBuilder()
                .append(time.format(Date())).append(' ').append(level).append(' ')
                .append('[').append(tag).append("] ").append(msg).append('\n')
            if (t != null) sb.append(Log.getStackTraceString(t)).append('\n')
            file.appendText(sb.toString())
            if (file.length() > MAX_BYTES) trim()
        } catch (_: Exception) {
            // יומן לעולם לא מפיל את האפליקציה
        }
    }

    /** משאיר את החצי האחרון של הקובץ */
    private fun trim() {
        val text = file.readText()
        val cut = text.indexOf('\n', text.length / 2).takeIf { it >= 0 } ?: (text.length / 2)
        file.writeText("…(שורות ישנות נמחקו)\n" + text.substring(cut + 1))
    }

    @Synchronized
    fun clear() {
        if (::file.isInitialized) file.writeText("")
        i("App", "היומן נוקה")
    }

    /** בונה קובץ אחד לשיתוף: פרטי מכשיר + יומן האפליקציה + logcat של התהליך */
    fun exportFile(context: Context): File {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(dir, "textplay-log-$stamp.txt")
        dir.listFiles()?.filter { it != out }?.forEach { it.delete() }

        val appLog = synchronized(this) {
            if (::file.isInitialized && file.exists()) file.readText() else "(ריק)"
        }
        out.writeText(
            buildString {
                appendLine("===== TextPlay — קובץ לוגים =====")
                appendLine("נוצר: ${time.format(Date())}")
                appendLine("אפליקציה: ${appVersion(context)}")
                appendLine("Spotify: ${spotifyVersion(context)}")
                appendLine("מכשיר: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine()
                appendLine("===== יומן האפליקציה =====")
                appendLine(appLog)
                appendLine()
                appendLine("===== logcat של התהליך (כולל Spotify SDK) =====")
                appendLine(readLogcat())
            }
        )
        return out
    }

    private fun readLogcat(): String = try {
        val proc = ProcessBuilder(
            "logcat", "-d", "-v", "threadtime", "--pid=${Process.myPid()}", "-t", "3000"
        ).redirectErrorStream(true).start()
        proc.inputStream.bufferedReader().use { it.readText() }.ifBlank { "(ריק)" }
    } catch (e: Exception) {
        "(לא ניתן לקרוא logcat: ${e.message})"
    }

    private fun appVersion(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${context.packageName} ${info.versionName}"
    } catch (_: Exception) {
        context.packageName
    }

    private fun spotifyVersion(context: Context): String = try {
        context.packageManager.getPackageInfo("com.spotify.music", 0).versionName ?: "?"
    } catch (_: Exception) {
        "לא מותקן / לא נמצא"
    }
}

package com.example.spotalk.ai

import com.example.spotalk.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** תוצאה: שאילתת חיפוש, או הסיבה לכשל (להצגה בהגדרות) */
class GeminiResult(val query: String?, val error: String?)

/**
 * "הבנת הבקשה" עם Gemini: מקבל את מה שזוהה בדיבור (כולל החלופות של מזהה הדיבור)
 * ומחזיר שאילתת חיפוש נקייה ל-Spotify — שם השיר והאמן הנכונים.
 *
 * מנסה כמה מודלים לפי הסדר (אם שם מודל לא קיים — עוברים לבא).
 * המפתח נשלח בכותרת ולא ב-URL, ולא נכנס ללוגים.
 */
object GeminiClient {

    /** המהיר והזול קודם. אם מודל לא נמצא (404/400) — מנסים את הבא */
    private val MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.6-flash", "gemini-flash-latest")

    private fun endpoint(model: String) =
        "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

    private const val SYSTEM = """You help a Spotify voice search.
The user spoke a request, in Hebrew or English, to play music. You receive up to 5 speech-recognition alternatives of the same utterance. They may contain recognition mistakes, for example English song or artist names transcribed phonetically in Hebrew letters.
Decide what the user wants and return the best Spotify search query: the song title, followed by the artist when known.
Use the spelling that Spotify uses: English titles in English letters, Hebrew titles in Hebrew letters.
Fix recognition mistakes. If the user describes a song instead of naming it, identify the most likely song.
Ignore command words such as play, search, find, תנגן, תחפש, תמצא.
If the user names only an artist, return only the artist name.
Return JSON only, in this exact shape: {"query": "..."}"""

    private sealed interface Attempt {
        data class Ok(val query: String) : Attempt
        data class Fail(val code: Int, val message: String) : Attempt
    }

    suspend fun songQuery(apiKey: String, heard: List<String>): GeminiResult = withContext(Dispatchers.IO) {
        var last = "לא ידוע"
        for (model in MODELS) {
            val attempt = try {
                call(model, apiKey, heard)
            } catch (e: Exception) {
                AppLog.w("Gemini", "$model: ${e.javaClass.simpleName}: ${e.message}")
                return@withContext GeminiResult(null, "${e.javaClass.simpleName}: ${e.message.orEmpty()}".take(140))
            }
            when (attempt) {
                is Attempt.Ok -> return@withContext GeminiResult(attempt.query, null)
                is Attempt.Fail -> {
                    AppLog.w("Gemini", "$model: HTTP ${attempt.code}: ${attempt.message}")
                    last = "HTTP ${attempt.code}: ${attempt.message}".take(160)
                    // מודל לא קיים / בקשה לא תקינה — מנסים את הבא. מפתח שגוי, מכסה וכו' — עוצרים.
                    val keyProblem = attempt.message.contains("API key", ignoreCase = true)
                    if ((attempt.code != 404 && attempt.code != 400) || keyProblem) {
                        return@withContext GeminiResult(null, last)
                    }
                }
            }
        }
        GeminiResult(null, last)
    }

    private fun call(model: String, apiKey: String, heard: List<String>): Attempt {
        val prompt = heard.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")
        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM)))
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                )
            )
            // בלי temperature (מיושן) ובלי הגדרות חשיבה — כדי שלא תהיה סיבה לדחיית הבקשה
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

        val conn = (URL(endpoint(model)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val msg = runCatching { JSONObject(err).getJSONObject("error").optString("message") }
                    .getOrNull().orEmpty().ifBlank { err }
                return Attempt.Fail(code, msg.replace("\n", " ").take(140))
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val answer = JSONObject(text)
                .optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                ?.optString("text").orEmpty().trim()
            val query = parseQuery(answer)
            return if (query != null) Attempt.Ok(query)
            else Attempt.Fail(200, "תשובה ריקה או לא מובנת")
        } finally {
            conn.disconnect()
        }
    }

    /** מוציא את השאילתה מהתשובה — גם אם הגיעה עטופה ב-```json או כטקסט פשוט */
    private fun parseQuery(answer: String): String? {
        if (answer.isBlank()) return null
        val clean = answer.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val fromJson = runCatching { JSONObject(clean).optString("query").trim() }.getOrNull()
        if (!fromJson.isNullOrBlank()) return fromJson
        // לא JSON: אם זו שורה קצרה אחת — משתמשים בה כמו שהיא
        return clean.takeIf { !it.contains('{') && !it.contains('\n') && it.length <= 120 }
    }
}

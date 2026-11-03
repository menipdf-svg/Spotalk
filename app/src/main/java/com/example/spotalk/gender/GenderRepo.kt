package com.example.spotalk.gender

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import com.example.spotalk.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class Gender { MALE, FEMALE, UNKNOWN }

/**
 * מגדר של אמנים — כדי להציג תמונות רק של זמרים גברים.
 * Spotify לא מספקת את המידע, לכן:
 *  1. Wikidata — לפי מזהה ה-Spotify של האמן (מאפיין P1902). בקשה אחת לקבוצה של אמנים.
 *  2. MusicBrainz — לפי קישור ה-Spotify של האמן, למי שלא נמצא ב-Wikidata.
 *  3. סימון ידני — גובר על הכל.
 * להקה: נחשבת "גבר" רק אם כל חבריה הידועים גברים.
 * הכל נשמר בטלפון, כך שכל אמן נבדק פעם אחת.
 */
class GenderRepo(context: Context) {

    private val prefs = context.getSharedPreferences("gender", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** מצב נצפה (Compose) — מסכים מתעדכנים כשתוצאה מגיעה */
    private val known = mutableStateMapOf<String, Gender>()
    private val manual = mutableStateMapOf<String, Gender>()
    /** שמות האמנים שסומנו ידנית — לרשימה בהגדרות */
    private val manualNames = mutableStateMapOf<String, String>()

    private val pending = LinkedHashSet<String>()
    private val pendingLock = Mutex()
    private var worker: kotlinx.coroutines.Job? = null

    init {
        val now = System.currentTimeMillis()
        prefs.all.forEach { (key, value) ->
            val v = value as? String ?: return@forEach
            when {
                key.startsWith("m:") -> runCatching { Gender.valueOf(v) }.getOrNull()?.let { manual[key.drop(2)] = it }
                key.startsWith("n:") -> manualNames[key.drop(2)] = v
                key.startsWith("a:") -> {
                    val (g, ts) = v.split("|").let { it[0] to (it.getOrNull(1)?.toLongOrNull() ?: 0L) }
                    val gender = runCatching { Gender.valueOf(g) }.getOrNull() ?: return@forEach
                    // "לא ידוע" נבדק שוב אחרי שבוע — אולי נוסף מידע
                    if (gender != Gender.UNKNOWN || now - ts < WEEK) known[key.drop(2)] = gender
                }
            }
        }
    }

    /** המגדר הידוע (ידני קודם). null = עוד לא נבדק */
    fun genderOf(artistId: String): Gender? = manual[artistId] ?: known[artistId]

    fun manualOf(artistId: String): Gender? = manual[artistId]

    /** מותר להציג תמונה רק אם כל האמנים ידועים כגברים */
    fun allMale(artistIds: List<String>): Boolean =
        artistIds.isNotEmpty() && artistIds.all { genderOf(it) == Gender.MALE }

    fun setManual(artistId: String, gender: Gender?, name: String = "") {
        if (gender == null) {
            manual.remove(artistId)
            manualNames.remove(artistId)
            prefs.edit().remove("m:$artistId").remove("n:$artistId").apply()
        } else {
            manual[artistId] = gender
            if (name.isNotBlank()) manualNames[artistId] = name
            prefs.edit().putString("m:$artistId", gender.name).apply {
                if (name.isNotBlank()) putString("n:$artistId", name)
            }.apply()
        }
        AppLog.i(TAG, "סימון ידני $artistId ($name) = $gender")
    }

    /** כל הסימונים הידניים: מזהה, שם, מגדר */
    fun manualList(): List<Triple<String, String, Gender>> =
        manual.map { (id, g) -> Triple(id, manualNames[id] ?: id, g) }.sortedBy { it.second }

    /** מבקש לבדוק אמנים שעוד לא ידועים. הבדיקה רצה ברקע, בקצב איטי */
    fun request(artistIds: Collection<String>) {
        val missing = artistIds.filter { it.isNotBlank() && genderOf(it) == null }
        if (missing.isEmpty()) return
        scope.launch {
            pendingLock.withLock { pending.addAll(missing) }
            startWorker()
        }
    }

    @Synchronized
    private fun startWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (true) {
                delay(800) // אוספים כמה בקשות יחד
                val batch = pendingLock.withLock {
                    pending.take(40).also { pending.removeAll(it.toSet()) }
                }
                if (batch.isEmpty()) break
                val todo = batch.filter { genderOf(it) == null }
                if (todo.isEmpty()) continue
                val fromWikidata = runCatching { wikidata(todo) }
                    .onFailure { AppLog.w(TAG, "Wikidata נכשל: ${it.message}") }
                    .getOrDefault(emptyMap())
                fromWikidata.forEach { (id, g) -> save(id, g) }
                // מי שלא נמצא — MusicBrainz, אחד-אחד, שנייה ביניהם (המגבלה שלהם)
                for (id in todo.filter { it !in fromWikidata }) {
                    val g = runCatching { musicBrainz(id) }
                        .onFailure { AppLog.w(TAG, "MusicBrainz נכשל ל-$id: ${it.message}") }
                        .getOrNull() ?: Gender.UNKNOWN
                    save(id, g)
                    delay(1100)
                }
            }
        }
    }

    private suspend fun save(id: String, gender: Gender) = withContext(Dispatchers.Main) {
        known[id] = gender
        prefs.edit().putString("a:$id", "${gender.name}|${System.currentTimeMillis()}").apply()
    }

    // ---------- Wikidata ----------

    private fun wikidata(ids: List<String>): Map<String, Gender> {
        val values = ids.joinToString(" ") { "\"$it\"" }
        val query = """
            SELECT ?sid ?g ?mg WHERE {
              VALUES ?sid { $values }
              ?item wdt:P1902 ?sid .
              OPTIONAL { ?item wdt:P21 ?g . }
              OPTIONAL { ?item wdt:P527 ?m . ?m wdt:P21 ?mg . }
            }
        """.trimIndent()
        val url = "https://query.wikidata.org/sparql?format=json&query=" + URLEncoder.encode(query, "UTF-8")
        val rows = JSONObject(httpGet(url)).getJSONObject("results").getJSONArray("bindings")

        val own = mutableMapOf<String, MutableSet<String>>()
        val members = mutableMapOf<String, MutableSet<String>>()
        val found = mutableSetOf<String>()
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val sid = r.getJSONObject("sid").getString("value")
            found += sid
            r.optJSONObject("g")?.getString("value")?.let { own.getOrPut(sid) { mutableSetOf() } += qid(it) }
            r.optJSONObject("mg")?.getString("value")?.let { members.getOrPut(sid) { mutableSetOf() } += qid(it) }
        }
        val result = found.associateWith { sid ->
            classify(own[sid].orEmpty(), members[sid].orEmpty())
        }.filterValues { it != Gender.UNKNOWN }
        AppLog.d(TAG, "Wikidata: ${result.size}/${ids.size} זוהו")
        return result
    }

    private fun qid(uri: String) = uri.substringAfterLast("/")

    private fun classify(own: Set<String>, members: Set<String>): Gender {
        val ownG = own.mapNotNull(::fromQid).toSet()
        if (Gender.FEMALE in ownG) return Gender.FEMALE
        if (Gender.MALE in ownG) return Gender.MALE
        val memberG = members.mapNotNull(::fromQid)
        if (memberG.isEmpty()) return Gender.UNKNOWN
        return if (memberG.all { it == Gender.MALE }) Gender.MALE else Gender.FEMALE
    }

    private fun fromQid(q: String): Gender? = when (q) {
        "Q6581097", "Q2449503" -> Gender.MALE      // זכר, גבר טרנס
        "Q6581072", "Q1052281" -> Gender.FEMALE    // נקבה, אישה טרנס
        else -> null
    }

    // ---------- MusicBrainz ----------

    private suspend fun musicBrainz(spotifyId: String): Gender {
        val resource = URLEncoder.encode("https://open.spotify.com/artist/$spotifyId", "UTF-8")
        val urlJson = runCatching {
            JSONObject(httpGet("https://musicbrainz.org/ws/2/url?resource=$resource&inc=artist-rels&fmt=json"))
        }.getOrNull() ?: return Gender.UNKNOWN
        val rel = urlJson.optJSONArray("relations")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.has("artist") }
        } ?: return Gender.UNKNOWN
        val mbid = rel.getJSONObject("artist").getString("id")
        delay(1100)
        val artist = JSONObject(httpGet("https://musicbrainz.org/ws/2/artist/$mbid?inc=artist-rels&fmt=json"))
        when (artist.optString("gender").lowercase()) {
            "male" -> return Gender.MALE
            "female" -> return Gender.FEMALE
        }
        if (artist.optString("type") != "Group") return Gender.UNKNOWN
        // להקה: בודקים את המין של כל חבר
        val memberIds = artist.optJSONArray("relations")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { it.optString("type") == "member of band" && it.has("artist") }
                .map { it.getJSONObject("artist").getString("id") }
        }.orEmpty().distinct().take(8)
        if (memberIds.isEmpty()) return Gender.UNKNOWN
        val genders = memberIds.mapNotNull { id ->
            delay(1100)
            runCatching { JSONObject(httpGet("https://musicbrainz.org/ws/2/artist/$id?fmt=json")).optString("gender") }
                .getOrNull()?.lowercase()
        }
        return when {
            genders.any { it == "female" } -> Gender.FEMALE
            genders.isNotEmpty() && genders.all { it == "male" } -> Gender.MALE
            else -> Gender.UNKNOWN
        }
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            // שני השירותים מבקשים לזהות את האפליקציה
            conn.setRequestProperty("User-Agent", "TextPlay/1.0 (personal Android app)")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val TAG = "Gender"
        const val WEEK = 7L * 24 * 60 * 60 * 1000
    }
}

package com.example.spotalk.api

import com.example.spotalk.auth.AuthManager
import com.example.spotalk.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class ItemType { TRACK, ALBUM, ARTIST, PLAYLIST }

/** פריט תצוגה: טקסט + כתובת תמונה (אם יש) */
data class Item(
    val id: String,
    val uri: String,
    val type: ItemType,
    val title: String,
    val subtitle: String,
    val durationMs: Long = 0,
    val explicit: Boolean = false,
    /** לשירים: מזהי האמנים (לסינון שירים של אמן מסוים) */
    val artistIds: List<String> = emptyList(),
    /** לאלבומים: סינגל / EP */
    val isSingle: Boolean = false,
    /** לפלייליסטים: מי הבעלים והאם שיתופי (כדי לדעת לאן מותר להוסיף שירים) */
    val ownerId: String = "",
    val collaborative: Boolean = false,
    /** לאלבומים: תאריך יציאה (למיון — החדש ביותר) */
    val releaseDate: String = "",
    /** לשירים: שם האלבום */
    val albumName: String = "",
    /** כתובת תמונה מ-Spotify (מוצגת כשההגדרה "הצג תמונות" פעילה) */
    val imageUrl: String? = null
)

/** קבוצת תוצאות של אמן אחד: עד 3 אלבומים ועד 3 שירים שלו */
data class ArtistGroup(
    val artist: Item,
    val albums: List<Item>,
    val tracks: List<Item>
)

data class SearchResults(
    /** עד 5 אמנים */
    val artists: List<Item> = emptyList(),
    /** לכל אחד מהאמנים למעלה: 3 אלבומים ו-3 שירים */
    val groups: List<ArtistGroup> = emptyList(),
    val singles: List<Item> = emptyList(),
    /** שירים שלא שייכים לאמנים למעלה (למשל כשמחפשים שם של שיר) */
    val otherTracks: List<Item> = emptyList(),
    val otherAlbums: List<Item> = emptyList(),
    val playlists: List<Item> = emptyList()
) {
    val isEmpty
        get() = artists.isEmpty() && otherTracks.isEmpty() && otherAlbums.isEmpty() &&
            singles.isEmpty() && playlists.isEmpty()
}

data class Collection(
    val title: String,
    val subtitle: String,
    val uri: String,
    val imageUrl: String? = null,
    /** האמנים של הפריט (לאלבום) */
    val artistIds: List<String> = emptyList(),
    /** null = Spotify לא מחזירה את התוכן (פלייליסט שאינו שלך במצב פיתוח) */
    val tracks: List<Item>?
)

data class ArtistPage(
    val artist: Item,
    /** ז'אנרים (אם Spotify מחזירה) — מוצג במקום "מאזינים חודשיים", שלא זמין במצב פיתוח */
    val genres: List<String>,
    val isFollowing: Boolean,
    /** שירים של האמן מתוך "שירים שאהבתי" */
    val likedTracks: List<Item>,
    /** "פופולריים" — שירים של האמן לפי סדר הרלוונטיות בחיפוש */
    val tracks: List<Item>,
    val albums: List<Item>,
    val singles: List<Item>,
    val compilations: List<Item>,
    /** "מופיע ב-" — אלבומים של אחרים שהאמן משתתף בהם */
    val appearsOn: List<Item>,
    /** פלייליסטים עם שם האמן (במקום "Featuring" / "This Is") */
    val playlists: List<Item>
) {
    /** "רליסים פופולריים": החדש ביותר קודם, ואז אלבומים */
    val latest: Item? get() = (albums + singles).maxByOrNull { it.releaseDate }
}

data class Me(val id: String, val name: String)

/** מה מתנגן עכשיו ב-Spotify (ל-Android Auto) */
data class NowPlaying(
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val progressMs: Long,
    val isPlaying: Boolean,
    val imageUrl: String?,
    /** לסינון התמונה לפי מגדר */
    val artistIds: List<String> = emptyList()
)

open class ApiException(val code: Int, message: String) : Exception(message)

/** Spotify החזירה 429. quota = נגמרה המכסה של מצב פיתוח (לא רק "האט") */
class RateLimitException(val retryAfterSec: Long, val quota: Boolean) : ApiException(
    429,
    if (quota) "הגעת למכסת הבקשות של Spotify. נסה שוב מאוחר יותר"
    else "Spotify ביקשה להאט — נסה שוב בעוד $retryAfterSec שניות"
)

class SpotifyApi(private val auth: AuthManager, cacheDir: File) {

    private var cachedMe: Me? = null

    suspend fun me(): Me = cachedMe ?: get("/me").let { json ->
        Me(json.str("id"), json.str("display_name")).also { cachedMe = it }
    }

    suspend fun myName(): String = me().name

    suspend fun recentlyPlayed(): List<Item> =
        get("/me/player/recently-played?limit=30").arr("items")
            .objects().mapNotNull { it.optJSONObject("track")?.let(::parseTrack) }
            .distinctBy { it.uri }

    /**
     * חיפוש מסודר:
     * 5 אמנים ← לכל אחד 3 אלבומים ו-3 שירים ← סינגלים ← שירים ואלבומים נוספים ← פלייליסטים.
     */
    suspend fun search(query: String): SearchResults {
        val q = URLEncoder.encode(query, "UTF-8")
        // שני עמודים מהחיפוש הראשי — כדי שיהיה ממה לבנות את הקבוצות גם בלי בקשות נוספות
        val artistsAll = mutableListOf<Item>()
        val albums = mutableListOf<Item>()
        val tracks = mutableListOf<Item>()
        val playlists = mutableListOf<Item>()
        val pagesJson = coroutineScope {
            (0 until 2).map { page ->
                async { get("/search?q=$q&type=artist,album,track,playlist&limit=10&offset=${page * 10}") }
            }.awaitAll()
        }
        for (json in pagesJson) {
            artistsAll += json.optJSONObject("artists").arr("items").objects().map(::parseArtist)
            albums += json.optJSONObject("albums").arr("items").objects().map(::parseAlbum)
            tracks += json.optJSONObject("tracks").arr("items").objects().map(::parseTrack)
            playlists += json.optJSONObject("playlists").arr("items").objects().map(::parsePlaylist)
        }
        val artists = artistsAll.distinctBy { it.uri }.take(5)
        val allAlbums = albums.distinctBy { it.uri }
        val allTracks = tracks.distinctBy { it.uri }

        // לכל אמן: 3 אלבומים ו-3 שירים. בקשה אחר בקשה (לא במקביל) כדי לא להיחסם על קצב.
        // אם בקשה נכשלת — משלימים מתוצאות החיפוש הראשי, כך שהקבוצה לא נשארת ריקה.
        val groups = coroutineScope { artists.map { artist -> async {
            val fromSearchAlbums = allAlbums.filter { artist.id in it.artistIds }
            val fromSearchTracks = allTracks.filter { artist.id in it.artistIds }

            val apiAlbums = try {
                get("/artists/${artist.id}/albums?include_groups=album,single&limit=10")
                    .arr("items").objects().map(::parseAlbum)
            } catch (e: Exception) {
                AppLog.w("Search", "אלבומים של ${artist.title} נכשלו: ${e.message}")
                emptyList()
            }
            // קודם אלבומים מלאים, ואם אין מספיק — סינגלים
            val groupAlbums = (apiAlbums.filterNot { it.isSingle } + fromSearchAlbums.filterNot { it.isSingle } +
                apiAlbums.filter { it.isSingle } + fromSearchAlbums.filter { it.isSingle })
                .distinctBy { it.uri }.take(3)

            val apiTracks = if (fromSearchTracks.size >= 3) emptyList() else try {
                val aq = URLEncoder.encode("artist:\"${artist.title}\"", "UTF-8")
                get("/search?q=$aq&type=track&limit=10")
                    .optJSONObject("tracks").arr("items").objects().map(::parseTrack)
                    .filter { artist.id in it.artistIds }
            } catch (e: Exception) {
                AppLog.w("Search", "שירים של ${artist.title} נכשלו: ${e.message}")
                emptyList()
            }
            val groupTracks = (fromSearchTracks + apiTracks).distinctBy { it.uri }.take(3)

            AppLog.d("Search", "${artist.title}: ${groupAlbums.size} אלבומים, ${groupTracks.size} שירים")
            ArtistGroup(artist, groupAlbums, groupTracks)
        } }.awaitAll() }

        val shownUris = groups.flatMap { g -> g.albums.map { it.uri } + g.tracks.map { it.uri } }.toSet()
        return SearchResults(
            artists = artists,
            groups = groups,
            singles = allAlbums.filter { it.isSingle && it.uri !in shownUris }.take(6),
            otherTracks = allTracks.filter { it.uri !in shownUris }.take(10),
            otherAlbums = allAlbums.filter { !it.isSingle && it.uri !in shownUris }.take(6),
            playlists = playlists.distinctBy { it.uri }.take(10)
        )
    }

    /** חיפוש שירים בלבד (לעוזר הקולי) — בקשה אחת */
    suspend fun searchTracks(query: String, limit: Int = 10): List<Item> {
        val q = URLEncoder.encode(query, "UTF-8")
        return get("/search?q=$q&type=track&limit=${limit.coerceIn(1, 10)}")
            .optJSONObject("tracks").arr("items").objects().map(::parseTrack)
            .distinctBy { it.uri }
    }

    /** חיפוש אמנים בלבד — בקשה אחת */
    suspend fun searchArtists(query: String): List<Item> {
        val q = URLEncoder.encode(query, "UTF-8")
        return get("/search?q=$q&type=artist&limit=10").optJSONObject("artists").arr("items")
            .objects().map(::parseArtist)
    }

    /** חיפוש פלייליסטים בלבד — בקשה אחת */
    suspend fun searchPlaylists(query: String, limit: Int = 5): List<Item> {
        val q = URLEncoder.encode(query, "UTF-8")
        return get("/search?q=$q&type=playlist&limit=${limit.coerceIn(1, 10)}")
            .optJSONObject("playlists").arr("items").objects().map(::parsePlaylist)
            .distinctBy { it.uri }
    }

    /**
     * מוצא פלייליסט לפי שם שנאמר: קודם מהפלייליסטים שלך (התאמה מטושטשת — דיבור לא מדויק),
     * ואם אין התאמה טובה — מחפש ב-Spotify ולוקח את הראשון.
     */
    suspend fun findPlaylist(spoken: String): Item? {
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim()
        val want = norm(spoken)
        if (want.isEmpty()) return null
        val wantWords = want.split(" ").filter { it.isNotEmpty() }.toSet()
        fun score(title: String): Double {
            val t = norm(title)
            if (t == want) return 3.0
            if (t.contains(want) || want.contains(t) && t.length >= 3) return 2.0
            val words = t.split(" ").filter { it.isNotEmpty() }.toSet()
            if (words.isEmpty()) return 0.0
            return wantWords.intersect(words).size.toDouble() / maxOf(wantWords.size, words.size)
        }
        val mine = try { myPlaylists() } catch (e: Exception) { emptyList() }
        val best = mine.maxByOrNull { score(it.title) }
        if (best != null && score(best.title) >= 0.5) return best
        return searchPlaylists(spoken, 5).firstOrNull()
    }

    /** ניגון הקשר שלם (פלייליסט / אלבום) מההתחלה */
    suspend fun playContext(contextUri: String, deviceId: String?) {
        val body = JSONObject().put("context_uri", contextUri).toString()
        val q = deviceId?.let { "?device_id=" + URLEncoder.encode(it, "UTF-8") } ?: ""
        sendRaw("PUT", "/me/player/play$q", body)
    }

    suspend fun myPlaylists(): List<Item> =
        getAll("/me/playlists?limit=50").map(::parsePlaylist)

    /** פלייליסטים שמותר להוסיף אליהם שירים: שלך או שיתופיים */
    suspend fun editablePlaylists(): List<Item> {
        val myId = me().id
        return myPlaylists().filter { it.ownerId == myId || it.collaborative }
    }

    suspend fun savedAlbums(): List<Item> =
        getAll("/me/albums?limit=50").mapNotNull { it.optJSONObject("album")?.let(::parseAlbum) }

    suspend fun followedArtists(): List<Item> {
        // רשימת אמנים עובדת עם cursor (after) ולא offset — עוקבים אחרי next
        val result = mutableListOf<Item>()
        var path: String? = "/me/following?type=artist&limit=50"
        var guard = 0
        while (path != null && guard++ < 40) {
            val artists = get(path).optJSONObject("artists")
            result += artists.arr("items").objects().map(::parseArtist)
            path = artists?.nextPath()
        }
        return result
    }

    /** כל השירים שאהבת — עמוד אחרי עמוד */
    private val likedStore = LikedStore(cacheDir)
    private val likedLock = Mutex()

    /** מה שכבר בזיכרון (בלי בקשות לרשת) */
    @Volatile
    private var likedMemory: List<LikedEntry>? = null

    /** הרשימה מהמטמון בלבד — מיידי, בלי רשת. null אם עוד לא נשמר כלום */
    fun likedTracksCached(): List<Item>? {
        if (likedMemory == null) likedMemory = likedStore.load()
        return likedMemory?.map { it.item }
    }

    /**
     * "שירים שאהבתי" עם סנכרון חכם:
     * מביאים רק מהחדש לישן, ועוצרים ברגע שמגיעים לשיר שכבר שמור.
     * בדרך כלל זו בקשה אחת. הורדה מלאה רק בפעם הראשונה, או אם שירים הוסרו.
     */
    suspend fun likedTracks(): List<Item> = likedLock.withLock {
        val cached = likedMemory ?: likedStore.load()
        val result = if (cached == null) fullLikedSync("אין מטמון") else incrementalLikedSync(cached)
        likedMemory = result
        result.map { it.item }
    }

    private suspend fun incrementalLikedSync(cached: List<LikedEntry>): List<LikedEntry> {
        val known = cached.mapTo(HashSet()) { it.key }
        val fresh = mutableListOf<LikedEntry>()
        var total = -1
        var offset = 0
        var reachedKnown = false
        var pages = 0
        while (!reachedKnown && pages < 10) {
            val page = get("/me/tracks?limit=50&offset=$offset")
            total = page.optInt("total", -1)
            val entries = page.arr("items").objects().mapNotNull(::likedEntry)
            for (e in entries) {
                if (e.key in known) {
                    reachedKnown = true
                    break
                }
                fresh += e
            }
            pages++
            if (entries.size < 50 || page.str("next").isBlank()) break
            offset += 50
        }

        val merged = (fresh + cached).distinctBy { it.key }
        if (fresh.isEmpty() && total == cached.size) {
            AppLog.d("Liked", "אין שינוי (${cached.size} שירים) — בקשה אחת")
            return cached
        }
        if (total == merged.size) {
            AppLog.i("Liked", "נוספו ${fresh.size} שירים חדשים ($pages בקשות)")
            likedStore.save(merged)
            return merged
        }
        // המספרים לא מסתדרים — כנראה הוסרו שירים. מורידים מחדש, בקצב איטי.
        return fullLikedSync("השתנה מספר השירים (${merged.size} מול $total)")
    }

    /** הורדה מלאה — עמוד אחרי עמוד, עם הפסקה קטנה בין בקשות כדי לא להעמיס על Spotify */
    private suspend fun fullLikedSync(reason: String): List<LikedEntry> {
        AppLog.i("Liked", "הורדה מלאה: $reason")
        val all = mutableListOf<LikedEntry>()
        var path: String? = "/me/tracks?limit=50"
        var pages = 0
        while (path != null && pages < 200) {
            if (pages > 0) delay(1000)
            val page = get(path)
            all += page.arr("items").objects().mapNotNull(::likedEntry)
            path = page.nextPath()
            pages++
        }
        AppLog.i("Liked", "הורדה מלאה הסתיימה: ${all.size} שירים ב-$pages בקשות")
        likedStore.save(all)
        return all
    }

    fun clearLikedCache() {
        likedMemory = null
        likedStore.clear()
    }

    private fun likedEntry(o: JSONObject): LikedEntry? {
        val track = o.optJSONObject("track") ?: return null
        return LikedEntry(parseTrack(track), o.str("added_at"))
    }

    // ---------- מעקב / ספרייה ----------

    suspend fun isInLibrary(uri: String): Boolean = try {
        val q = URLEncoder.encode(uri, "UTF-8")
        val text = sendRaw("GET", "/me/library/contains?uris=$q", null)
        JSONArray(text).optBoolean(0, false)
    } catch (e: Exception) {
        AppLog.w("Api", "בדיקת ספרייה נכשלה: ${e.message}")
        false
    }

    /** מעקב/הסרת מעקב אחרי אמן, שמירה/הסרה של אלבום — הכל דרך /me/library */
    suspend fun setInLibrary(uri: String, add: Boolean) {
        // לפי התיעוד: הרשימה נשלחת כפרמטר uris בכתובת (מופרד בפסיקים), לא בגוף הבקשה
        val q = URLEncoder.encode(uri, "UTF-8")
        sendRaw(if (add) "PUT" else "DELETE", "/me/library?uris=$q", null)
    }

    suspend fun playlist(id: String): Collection {
        val p = get("/playlists/$id")
        val owner = p.optJSONObject("owner")?.str("display_name").orEmpty()
        val tracks = try {
            getAll("/playlists/$id/items?limit=100", maxPages = 100).mapNotNull { entry ->
                // אחרי עדכון פברואר 2026 השדה נקרא item; ישן: track
                (entry.optJSONObject("item") ?: entry.optJSONObject("track"))
                    ?.takeIf { it.str("type") == "track" || it.has("duration_ms") }
                    ?.let(::parseTrack)
            }
        } catch (e: ApiException) {
            if (e.code == 403 || e.code == 404) null else throw e
        }
        return Collection(
            title = p.str("name"),
            subtitle = listOf("פלייליסט", owner).filter { it.isNotBlank() }.joinToString(" • "),
            uri = p.str("uri"),
            imageUrl = pickImage(p),
            tracks = tracks
        )
    }

    suspend fun album(id: String): Collection {
        val a = get("/albums/$id")
        val artists = a.arr("artists").names()
        val year = a.str("release_date").take(4)
        val first = a.optJSONObject("tracks")
        val items = first.arr("items").objects().toMutableList()
        // אלבומים ארוכים: ממשיכים לעמודים הבאים
        first?.nextPath()?.let { items += getAll(it, maxPages = 20) }
        return Collection(
            title = a.str("name"),
            subtitle = listOf(artists, year).filter { it.isNotBlank() }.joinToString(" • "),
            uri = a.str("uri"),
            imageUrl = pickImage(a),
            artistIds = a.arr("artists").objects().map { it.str("id") },
            tracks = items.map(::parseTrack)
        )
    }

    /**
     * עמוד אמן: אלבומים, סינגלים, שירים, פלייליסטים.
     * "השירים הפופולריים" הוסרו מה-API במצב פיתוח — לכן השירים נאספים מחיפוש לפי האמן.
     */
    private val artistCache = mutableMapOf<String, Pair<Long, ArtistPage>>()

    /** עמוד אמן, עם זיכרון של 5 דקות (גם מסך הדיסקוגרפיה משתמש בו) */
    suspend fun artistPage(id: String, fresh: Boolean = false): ArtistPage {
        val cached = artistCache[id]
        if (!fresh && cached != null && System.currentTimeMillis() - cached.first < 5 * 60_000) {
            return cached.second
        }
        return loadArtistPage(id).also { artistCache[id] = System.currentTimeMillis() to it }
    }

    /** כמה פריטים לבקש בכל עמוד של דיסקוגרפיה. מנסים 50; אם Spotify דוחה (במצב פיתוח) — 10 */
    private var albumPageSize = 10

    private suspend fun artistAlbums(id: String, groups: String, maxItems: Int): List<Item> {
        val pages = (maxItems + albumPageSize - 1) / albumPageSize
        return try {
            getAll("/artists/$id/albums?include_groups=$groups&limit=$albumPageSize", maxPages = pages).map(::parseAlbum)
        } catch (e: ApiException) {
            if (e.code == 400 && albumPageSize > 10) {
                AppLog.w("Api", "limit=$albumPageSize נדחה לדיסקוגרפיה — עוברים ל-10")
                albumPageSize = 10
                artistAlbums(id, groups, maxItems)
            } else throw e
        }
    }

    private suspend fun loadArtistPage(id: String): ArtistPage = coroutineScope {
        val started = System.currentTimeMillis()
        val json = get("/artists/$id")
        val artist = parseArtist(json)
        val genres = json.arr("genres").let { a -> (0 until a.length()).map { a.optString(it) } }
            .filter { it.isNotBlank() }
        val name = artist.title

        // כל החלקים נטענים במקביל (עד 4 בקשות בו-זמנית)
        // בעמוד האמן מספיקים העמודים הראשונים. הדיסקוגרפיה המלאה נטענת רק כשפותחים אותה.
        val albumsJob = async { artistAlbums(id, "album", 20) }
        val singlesJob = async { artistAlbums(id, "single", 20) }
        val compilationsJob = async { emptyList<Item>() }
        val appearsJob = async { runCatching { artistAlbums(id, "appears_on", 10) }.getOrDefault(emptyList()) }
        val tracksJob = async {
            val artistQuery = URLEncoder.encode("artist:\"$name\"", "UTF-8")
            (0 until 3).map { page ->
                async {
                    runCatching {
                        get("/search?q=$artistQuery&type=track&limit=10&offset=${page * 10}")
                            .optJSONObject("tracks").arr("items").objects().map(::parseTrack)
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten().filter { id in it.artistIds }
        }
        val playlistsJob = async {
            val nameQuery = URLEncoder.encode(name, "UTF-8")
            (0 until 2).map { page ->
                async {
                    runCatching {
                        get("/search?q=$nameQuery&type=playlist&limit=10&offset=${page * 10}")
                            .optJSONObject("playlists").arr("items").objects().map(::parsePlaylist)
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
        // "שירים שאהבתם" — רק אם כבר טעונים בזיכרון, כדי לא לעכב את העמוד
        val liked = likedTracksCached()?.filter { id in it.artistIds }.orEmpty()
        val followingJob = async { isInLibrary(artist.uri) }

        val page = ArtistPage(
            artist = artist,
            genres = genres,
            isFollowing = followingJob.await(),
            likedTracks = liked,
            tracks = tracksJob.await().distinctBy { it.uri },
            albums = albumsJob.await().distinctBy { it.uri }.sortedByDescending { it.releaseDate },
            singles = singlesJob.await().distinctBy { it.uri }.sortedByDescending { it.releaseDate },
            compilations = compilationsJob.await().distinctBy { it.uri },
            appearsOn = appearsJob.await().distinctBy { it.uri },
            playlists = playlistsJob.await().distinctBy { it.uri }
        )
        AppLog.d("Api", "עמוד אמן $name נטען ב-${System.currentTimeMillis() - started}ms")
        page
    }

    data class Discography(val albums: List<Item>, val singles: List<Item>, val compilations: List<Item>)

    private val discographyCache = mutableMapOf<String, Discography>()

    /** כל הדיסקוגרפיה — עמוד אחרי עמוד (לא במקביל), נשמרת בזיכרון */
    suspend fun discography(id: String): Discography {
        discographyCache[id]?.let { return it }
        val albums = artistAlbums(id, "album", 300)
        val singles = artistAlbums(id, "single", 300)
        val compilations = runCatching { artistAlbums(id, "compilation", 50) }.getOrDefault(emptyList())
        return Discography(
            albums = albums.distinctBy { it.uri }.sortedByDescending { it.releaseDate },
            singles = singles.distinctBy { it.uri }.sortedByDescending { it.releaseDate },
            compilations = compilations.distinctBy { it.uri }.sortedByDescending { it.releaseDate }
        ).also { discographyCache[id] = it }
    }

    /** כל ה-URI שכבר נמצאים בפלייליסט — כדי למנוע כפילויות */
    suspend fun playlistUris(playlistId: String): Set<String> =
        getAll("/playlists/$playlistId/items?limit=100", maxPages = 100)
            .mapNotNull { e -> (e.optJSONObject("item") ?: e.optJSONObject("track"))?.str("uri") }
            .filter { it.isNotBlank() }
            .toSet()

    /** המזהה של הטלפון הזה ברשימת המכשירים של Spotify (המכשיר הפעיל, או הטלפון) */
    suspend fun phoneDeviceId(): String? {
        val devices = get("/me/player/devices").arr("devices").objects()
        val pick = devices.firstOrNull { it.optBoolean("is_active") && it.str("type") == "Smartphone" }
            ?: devices.firstOrNull { it.str("type") == "Smartphone" }
            ?: devices.firstOrNull { it.optBoolean("is_active") }
        return pick?.str("id")?.ifBlank { null }
    }

    /**
     * ניגון רשימה (אלבום / פלייליסט / שירים שאהבתי) ישר מהשיר שנבחר — בלי שהשיר הראשון יתנגן לרגע.
     * דרך ה-Web API: context_uri + offset לפי ה-URI של השיר.
     */
    suspend fun playContextAt(contextUri: String, trackUri: String, deviceId: String?) {
        val body = JSONObject()
            .put("context_uri", contextUri)
            .put("offset", JSONObject().put("uri", trackUri))
            .toString()
        val q = deviceId?.let { "?device_id=" + URLEncoder.encode(it, "UTF-8") } ?: ""
        sendRaw("PUT", "/me/player/play$q", body)
    }

    /** ניגון רשימת שירים מוגדרת (בלי הקשר של Spotify) החל משיר מסוים */
    suspend fun playUrisAt(uris: List<String>, trackUri: String, deviceId: String?) {
        val body = JSONObject()
            .put("uris", JSONArray(uris.take(100)))
            .put("offset", JSONObject().put("uri", trackUri))
            .toString()
        val q = deviceId?.let { "?device_id=" + URLEncoder.encode(it, "UTF-8") } ?: ""
        sendRaw("PUT", "/me/player/play$q", body)
    }

    /** תור ההשמעה של Spotify: מה מתנגן עכשיו ומה הבא בתור */
    suspend fun queue(): Pair<Item?, List<Item>> {
        val json = get("/me/player/queue")
        val current = json.optJSONObject("currently_playing")?.takeIf { it.has("uri") }?.let(::parseTrack)
        val next = json.arr("queue").objects().map(::parseTrack)
        return current to next
    }

    /** מצב הנגן ב-Spotify — תמיד טרי (בלי מטמון). null = שום דבר לא מתנגן / אין מכשיר פעיל */
    suspend fun nowPlaying(): NowPlaying? {
        val json = parseObject(sendRaw("GET", "/me/player", null))
        val item = json.optJSONObject("item")?.takeIf { it.has("uri") } ?: return null
        return NowPlaying(
            uri = item.str("uri"),
            title = item.str("name"),
            artist = item.arr("artists").names(),
            album = item.optJSONObject("album")?.str("name").orEmpty(),
            durationMs = item.optLong("duration_ms"),
            progressMs = json.optLong("progress_ms"),
            isPlaying = json.optBoolean("is_playing"),
            imageUrl = item.optJSONObject("album")?.let(::pickImage),
            artistIds = item.arr("artists").objects().map { it.str("id") }
        )
    }

    /** הוספת שיר לתור ההשמעה של Spotify */
    suspend fun addToQueue(trackUri: String, deviceId: String? = null) {
        val dev = deviceId?.let { "&device_id=$it" }.orEmpty()
        sendRaw("POST", "/me/player/queue?uri=" + java.net.URLEncoder.encode(trackUri, "UTF-8") + dev, null)
    }

    /** פקודת נגן פשוטה: PUT /me/player/pause, POST /me/player/next ועוד */
    suspend fun playerCommand(method: String, path: String) {
        sendRaw(method, path, null)
    }

    /** הוספת שירים לפלייליסט (נדרשות הרשאות playlist-modify) */
    suspend fun addToPlaylist(playlistId: String, uris: List<String>) {
        val body = JSONObject().put("uris", JSONArray(uris)).toString()
        send("POST", "/playlists/$playlistId/items", body)
    }

    // ---------- פרסור ----------

    private fun parseTrack(o: JSONObject) = Item(
        id = o.str("id"),
        uri = o.str("uri"),
        type = ItemType.TRACK,
        title = o.str("name"),
        subtitle = o.arr("artists").names(),
        durationMs = o.optLong("duration_ms"),
        explicit = o.optBoolean("explicit"),
        artistIds = o.arr("artists").objects().map { it.str("id") },
        albumName = o.optJSONObject("album")?.str("name").orEmpty(),
        imageUrl = o.optJSONObject("album")?.let(::pickImage)
    )

    private fun parseAlbum(o: JSONObject): Item {
        val single = o.str("album_type") == "single"
        return Item(
            id = o.str("id"),
            uri = o.str("uri"),
            type = ItemType.ALBUM,
            title = o.str("name"),
            subtitle = listOf(
                if (single) "סינגל" else "אלבום",
                o.str("release_date").take(4),
                o.arr("artists").names()
            ).filter { it.isNotBlank() }.joinToString(" • "),
            isSingle = single,
            artistIds = o.arr("artists").objects().map { it.str("id") },
            releaseDate = o.str("release_date"),
            imageUrl = pickImage(o)
        )
    }

    private fun parseArtist(o: JSONObject) = Item(
        id = o.str("id"),
        uri = o.str("uri"),
        type = ItemType.ARTIST,
        title = o.str("name"),
        subtitle = "אמן",
        artistIds = listOf(o.str("id")),
        imageUrl = pickImage(o)
    )

    /** בוחר תמונה בגודל בינוני (בערך 300px) מתוך images */
    private fun pickImage(o: JSONObject): String? {
        val imgs = o.arr("images").objects()
            .map { it.str("url") to it.optInt("width", 0) }
            .filter { it.first.isNotBlank() }
        if (imgs.isEmpty()) return null
        return (imgs.filter { it.second >= 280 }.minByOrNull { it.second } ?: imgs.maxByOrNull { it.second })?.first
    }

    /** תמונת האלבום של שיר (לנגן המלא) */
    suspend fun trackArt(trackId: String): Pair<String?, List<String>> {
        val t = get("/tracks/$trackId")
        return t.optJSONObject("album")?.let(::pickImage) to t.arr("artists").objects().map { it.str("id") }
    }

    private fun parsePlaylist(o: JSONObject): Item {
        val owner = o.optJSONObject("owner")
        return Item(
            id = o.str("id"),
            uri = o.str("uri"),
            type = ItemType.PLAYLIST,
            title = o.str("name"),
            subtitle = listOf("פלייליסט", owner?.str("display_name").orEmpty())
                .filter { it.isNotBlank() }.joinToString(" • "),
            ownerId = owner?.str("id").orEmpty(),
            collaborative = o.optBoolean("collaborative"),
            imageUrl = pickImage(o)
        )
    }

    // ---------- רשת ----------

    /** מביא את כל העמודים של רשימה, לפי שדה next שמחזירה Spotify */
    private suspend fun getAll(firstPath: String, maxPages: Int = 40): List<JSONObject> {
        val all = mutableListOf<JSONObject>()
        var path: String? = firstPath
        var pages = 0
        while (path != null && pages < maxPages) {
            if (pages > 0) delay(150)
            val json = get(path)
            all += json.arr("items").objects()
            path = json.nextPath()
            pages++
        }
        if (path != null) AppLog.w("Api", "עצרתי אחרי $pages עמודים: $firstPath")
        return all
    }

    private val responseCache = ResponseCache(cacheDir)

    /**
     * GET עם מטמון: אם יש תשובה טרייה — בלי בקשה בכלל.
     * אם Spotify חוסמת או שאין רשת — מחזירים את התשובה האחרונה ששמרנו (גם אם ישנה).
     */
    private suspend fun get(path: String): JSONObject {
        responseCache.get(path, fresh = true)?.let { return parseObject(it) }
        return try {
            val text = sendRaw("GET", path, null)
            responseCache.put(path, text)
            parseObject(text)
        } catch (e: Exception) {
            val stale = responseCache.get(path, fresh = false)
            if (stale != null && (e is RateLimitException || e is java.io.IOException)) {
                AppLog.w("Cache", "משתמשים בתשובה שמורה ל-$path (${e.message})")
                parseObject(stale)
            } else throw e
        }
    }

    private fun parseObject(text: String): JSONObject =
        if (text.isBlank() || !text.trimStart().startsWith("{")) JSONObject() else JSONObject(text)

    fun clearResponseCache() = responseCache.clear()

    private suspend fun send(method: String, path: String, body: String?): JSONObject {
        val text = sendRaw(method, path, body)
        return if (text.isBlank() || !text.trimStart().startsWith("{")) JSONObject() else JSONObject(text)
    }

    /** מחזיר את גוף התשובה כטקסט (חלק מהנקודות מחזירות מערך ולא אובייקט) */
    /** עד 2 בקשות במקביל — לא מעמיסים על Spotify */
    private val limiter = Semaphore(2)

    private suspend fun sendRaw(method: String, path: String, body: String?): String = limiter.withPermit {
        sendRawUnlimited(method, path, body)
    }

    /** עד מתי Spotify ביקשה לא לשלוח בקשות (כל הבקשות מחכות לזה) */
    @Volatile
    private var pausedUntil = 0L

    private suspend fun sendRawUnlimited(method: String, path: String, body: String?): String {
        val wait = pausedUntil - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        return try {
            sendWithAuth(method, path, body)
        } catch (e: RateLimitException) {
            // Spotify ביקשה להאט: כל האפליקציה עוצרת לזמן שהיא ביקשה
            pausedUntil = System.currentTimeMillis() + e.retryAfterSec * 1000
            AppLog.w("Api", "429 — ${if (e.quota) "מכסה" else "קצב"}, להמתין ${e.retryAfterSec} שניות")
            if (e.quota || e.retryAfterSec > 15) throw e
            delay(e.retryAfterSec * 1000)
            sendWithAuth(method, path, body)
        }
    }

    private suspend fun sendWithAuth(method: String, path: String, body: String?): String {
        val token = auth.accessToken() ?: throw ApiException(401, "לא מחובר")
        return try {
            request(method, path, body, token)
        } catch (e: ApiException) {
            if (e.code != 401) throw e
            val fresh = auth.accessToken(forceRefresh = true) ?: throw e
            request(method, path, body, fresh)
        }
    }

    private suspend fun request(method: String, path: String, body: String?, token: String): String =
        withContext(Dispatchers.IO) {
            // שמות בעברית: Spotify מחזירה שמות מתורגמים (למשל "החברים של נטאשה")
            // לפי הפרמטר locale ולפי הכותרת Accept-Language
            val sep = if (path.contains("?")) "&" else "?"
            val localized = if (method == "GET") "$path${sep}locale=he_IL" else path
            val conn = URL("https://api.spotify.com/v1$localized").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Accept-Language", "he-IL,he;q=0.9,en;q=0.5")
                if (body == null && (method == "PUT" || method == "POST")) {
                    // בקשה בלי גוף — שולחים אורך 0 במפורש
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(0)
                    conn.outputStream.close()
                } else if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code in 200..299) AppLog.d("Api", "$method $path → $code")
                else AppLog.w("Api", "$method $path → $code ${text.take(300)}")
                when {
                    code in 200..299 -> text
                    code == 403 && path.contains("/playlists/") && method == "POST" ->
                        throw ApiException(403, "אין הרשאה להוסיף לפלייליסט הזה")
                    code == 429 -> {
                        val retryAfter = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull() ?: 5L
                        val quota = text.contains("QUOTA_EXCEEDED")
                        throw RateLimitException(retryAfter, quota)
                    }
                    else -> {
                        // מצרפים את ההסבר של Spotify, אם יש
                        val reason = runCatching {
                            JSONObject(text).optJSONObject("error")?.optString("message").orEmpty()
                        }.getOrDefault("")
                        throw ApiException(code, if (reason.isNotBlank()) "שגיאה $code: $reason" else "שגיאה $code")
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
}

// ---------- עזרי JSON ----------

private fun JSONObject?.arr(key: String): JSONArray = this?.optJSONArray(key) ?: JSONArray()

private fun JSONObject.str(key: String): String =
    if (isNull(key)) "" else optString(key, "")

/** הכתובת של העמוד הבא, בלי הקידומת https://api.spotify.com/v1 */
private fun JSONObject.nextPath(): String? {
    val next = str("next")
    if (next.isBlank()) return null
    val prefix = "https://api.spotify.com/v1"
    return if (next.startsWith(prefix)) next.removePrefix(prefix) else null
}

/** מדלג על ערכי null (Spotify לפעמים מחזירה null בתוך רשימות) */
private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray.names(): String =
    objects().map { it.str("name") }.filter { it.isNotBlank() }.joinToString(", ")

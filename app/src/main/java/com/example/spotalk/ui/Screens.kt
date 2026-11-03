package com.example.spotalk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.spotalk.MainViewModel
import com.example.spotalk.Screen
import com.example.spotalk.api.Collection
import com.example.spotalk.api.Item
import com.example.spotalk.api.SearchResults
import kotlinx.coroutines.delay
import java.util.Calendar

// ---------- טעינה ----------

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Done<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

/**
 * זיכרון של תוצאות שכבר נטענו. כשחוזרים למסך — מציגים מיד את מה שנטען קודם,
 * ומרעננים ברקע בלי מסך טעינה.
 */
object LoadCache {
    private val map = mutableMapOf<String, Any?>()
    fun get(key: String): Any? = synchronized(map) { map[key] }
    fun put(key: String, value: Any?) = synchronized(map) { map[key] = value }
    fun clear() = synchronized(map) { map.clear() }
}

@Composable
fun <T> rememberLoad(key: Any?, cacheKey: String? = null, block: suspend () -> T): Load<T> {
    @Suppress("UNCHECKED_CAST")
    val cached = cacheKey?.let { LoadCache.get(it) } as T?
    val state by produceState<Load<T>>(if (cached != null) Load.Done(cached) else Load.Loading, key, cacheKey) {
        try {
            val fresh = block()
            if (cacheKey != null) LoadCache.put(cacheKey, fresh)
            value = Load.Done(fresh)
        } catch (e: Exception) {
            // אם כבר מוצג משהו מהזיכרון — לא מחליפים אותו בהודעת שגיאה
            if (value !is Load.Done) value = Load.Failed(e.message ?: "שגיאה בטעינה")
        }
    }
    return state
}

@Composable
internal fun <T> LoadBox(load: Load<T>, content: @Composable (T) -> Unit) {
    when (load) {
        is Load.Loading -> Loading()
        is Load.Failed -> Message(load.message)
        is Load.Done -> content(load.value)
    }
}

// ---------- בית ----------

@Composable
fun HomeScreen(vm: MainViewModel, currentUri: String?) {
    val name = rememberLoad(Unit, "home:name") { vm.api.myName() }
    val playlists = rememberLoad(Unit, "home:playlists") { vm.api.myPlaylists() }
    val recent = rememberLoad(Unit, "home:recent") { vm.api.recentlyPlayed() }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(greeting(), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                MicButton(tint = Palette.TextPrimary)
                IconButton(onClick = { vm.open(Screen.Settings) }) {
                    Icon(Icons.Filled.Settings, "הגדרות", tint = Palette.TextPrimary)
                }
            }
            (name as? Load.Done<String>)?.value?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Palette.TextSecondary, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }

        // רשת קיצורים בסגנון מסך הבית של Spotify
        item {
            val list = (playlists as? Load.Done<List<Item>>)?.value.orEmpty().take(6)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val shortcuts: List<Triple<String, String?, () -> Unit>> =
                    listOf<Triple<String, String?, () -> Unit>>(Triple("שירים שאהבתי", null, { vm.open(Screen.Liked) })) +
                        list.take(5).map { p -> Triple<String, String?, () -> Unit>(p.title, p.imageUrl, { vm.openItem(p) }) }
                shortcuts.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEachIndexed { i, (title, image, action) ->
                            Shortcut(title, image, isLiked = title == "שירים שאהבתי" && i == 0, onClick = action, modifier = Modifier.weight(1f))
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        item { SectionTitle("הושמעו לאחרונה") }
        when (recent) {
            is Load.Loading -> item { Box(Modifier.height(120.dp)) { Loading() } }
            is Load.Failed -> item { Message(recent.message) }
            is Load.Done -> items(recent.value) { t ->
                TrackRow(
                    t, isCurrent = t.uri == currentUri,
                    onClick = { vm.player.play(t.uri) },
                    onAddToPlaylist = { vm.requestAddToPlaylist(listOf(t.uri)) }
                )
            }
        }
        item { SpotifyAttribution(Modifier.fillMaxWidth().padding(24.dp)) }
    }
}

@Composable
private fun Shortcut(title: String, imageUrl: String?, isLiked: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Palette.Highlight)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLiked) LikedTile(56.dp) else IconTile(iconFor(com.example.spotalk.api.ItemType.PLAYLIST), title, 56.dp, RoundedCornerShape(0.dp), imageUrl = imageUrl)
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "בוקר טוב"
    in 12..16 -> "צהריים טובים"
    in 17..21 -> "ערב טוב"
    else -> "לילה טוב"
}

// ---------- חיפוש ----------

@Composable
fun SearchScreen(vm: MainViewModel, currentUri: String?) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<Load<SearchResults>?>(null) }

    // חיפוש שהגיע מהעוזר הקולי
    val voiceQuery by vm.voiceQuery.collectAsState()
    LaunchedEffect(voiceQuery) {
        voiceQuery?.let {
            query = it
            vm.consumeVoiceQuery()
        }
    }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            results = null
            return@LaunchedEffect
        }
        delay(400) // debounce
        results = Load.Loading
        results = try {
            Load.Done(vm.api.search(q))
        } catch (e: Exception) {
            Load.Failed(e.message ?: "שגיאה בחיפוש")
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text("חיפוש", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("מה בא לך לשמוע? אפשר גם לדבר", color = Color(0xFF535353)) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = Color.Black) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Clear, "ניקוי", tint = Color.Black)
                    }
                    MicButton(tint = Color.Black)
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(6.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                focusedTextColor = Color.Black,
                unfocusedTextColor = Color.Black,
                cursorColor = Color.Black,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )

        when (val r = results) {
            null -> RecentSearches(vm) { query = it }
            is Load.Loading -> Loading()
            is Load.Failed -> Message(r.message)
            is Load.Done -> {
                val res = r.value
                if (res.isEmpty) Message("לא נמצאו תוצאות") else LazyColumn(Modifier.fillMaxSize()) {
                    val trackRow: @Composable (Item) -> Unit = { t ->
                        TrackRow(
                            t, isCurrent = t.uri == currentUri,
                            onClick = {
                                vm.addRecentSearch(query)
                                vm.player.play(t.uri)
                            },
                            onAddToPlaylist = { vm.requestAddToPlaylist(listOf(t.uri)) }
                        )
                    }

                    // 1. אמנים (עד 5)
                    if (res.artists.isNotEmpty()) {
                        item { SectionTitle("אמנים") }
                        items(res.artists) { ItemRow(it) {
                                vm.addRecentSearch(query)
                                vm.openItem(it)
                            } }
                    }

                    // 2. אלבומים — 3 מכל אמן, לפי סדר האמנים
                    val albumGroups = res.groups.filter { it.albums.isNotEmpty() }
                    if (albumGroups.isNotEmpty()) {
                        item { SectionTitle("אלבומים") }
                        albumGroups.forEach { g ->
                            item { GroupLabel(g.artist.title) }
                            items(g.albums) { ItemRow(it) {
                                vm.addRecentSearch(query)
                                vm.openItem(it)
                            } }
                        }
                    }

                    // 3. שירים — 3 מכל אמן
                    val trackGroups = res.groups.filter { it.tracks.isNotEmpty() }
                    if (trackGroups.isNotEmpty()) {
                        item { SectionTitle("שירים") }
                        trackGroups.forEach { g ->
                            item { GroupLabel(g.artist.title) }
                            items(g.tracks) { trackRow(it) }
                        }
                    }

                    // 4. סינגלים
                    if (res.singles.isNotEmpty()) {
                        item { SectionTitle("סינגלים ו-EP") }
                        items(res.singles) { ItemRow(it) {
                                vm.addRecentSearch(query)
                                vm.openItem(it)
                            } }
                    }

                    // 5. עוד תוצאות (למשל כשמחפשים שם של שיר ולא של אמן)
                    if (res.otherTracks.isNotEmpty()) {
                        item { SectionTitle("עוד שירים") }
                        items(res.otherTracks) { trackRow(it) }
                    }
                    if (res.otherAlbums.isNotEmpty()) {
                        item { SectionTitle("עוד אלבומים") }
                        items(res.otherAlbums) { ItemRow(it) {
                                vm.addRecentSearch(query)
                                vm.openItem(it)
                            } }
                    }

                    // 6. פלייליסטים
                    if (res.playlists.isNotEmpty()) {
                        item { SectionTitle("פלייליסטים") }
                        items(res.playlists) { ItemRow(it) {
                                vm.addRecentSearch(query)
                                vm.openItem(it)
                            } }
                    }
                    item { SpotifyAttribution(Modifier.fillMaxWidth().padding(24.dp)) }
                }
            }
        }
    }
}

/** חיפושים אחרונים — מוצגים כשתיבת החיפוש ריקה */
@Composable
private fun RecentSearches(vm: MainViewModel, onPick: (String) -> Unit) {
    val recent by vm.settings.recentSearches.collectAsState()
    if (recent.isEmpty()) {
        Message("חפש שירים, אמנים, אלבומים ופלייליסטים")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("חיפושים אחרונים", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::clearRecentSearches) {
                    Text("נקה הכל", color = Palette.TextSecondary)
                }
            }
        }
        items(recent) { q ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(q) }
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.History, null, tint = Palette.TextSecondary)
                Spacer(Modifier.width(14.dp))
                Text(q, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.removeRecentSearch(q) }) {
                    Icon(Icons.Filled.Clear, "הסר", tint = Palette.TextMuted)
                }
            }
        }
    }
}

/** כותרת משנה של אמן בתוך קטע */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        color = Palette.Green,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp)
    )
}

// ---------- ספרייה ----------

private enum class LibFilter(val label: String) { PLAYLISTS("פלייליסטים"), ALBUMS("אלבומים"), ARTISTS("אמנים") }

@Composable
fun LibraryScreen(vm: MainViewModel) {
    var filter by rememberSaveable { mutableStateOf(LibFilter.PLAYLISTS) }
    val load = rememberLoad(filter, "library:$filter") {
        when (filter) {
            LibFilter.PLAYLISTS -> vm.api.myPlaylists()
            LibFilter.ALBUMS -> vm.api.savedAlbums()
            LibFilter.ARTISTS -> vm.api.followedArtists()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text("הספרייה שלך", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LibFilter.entries.forEach { f ->
                FilterChip(
                    selected = f == filter,
                    onClick = { filter = f },
                    label = { Text(f.label) },
                    shape = RoundedCornerShape(50),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Palette.Highlight,
                        labelColor = Palette.TextPrimary,
                        selectedContainerColor = Palette.Green,
                        selectedLabelColor = Color.Black
                    ),
                    border = null
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LoadBox(load) { list ->
            LazyColumn(Modifier.fillMaxSize()) {
                if (filter == LibFilter.PLAYLISTS) item {
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.open(Screen.Liked) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LikedTile(56.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("שירים שאהבתי", style = MaterialTheme.typography.bodyLarge)
                            Text("פלייליסט", color = Palette.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (list.isEmpty()) item { Message("אין כאן עדיין כלום") }
                items(list) { ItemRow(it) { vm.openItem(it) } }
            }
        }
    }
}

// ---------- מסכי פירוט ----------

@Composable
fun CollectionScreen(vm: MainViewModel, screen: Screen, currentUri: String?) {
    val load = rememberLoad(screen, "collection:$screen") {
        when (screen) {
            is Screen.Playlist -> vm.api.playlist(screen.id)
            is Screen.Album -> vm.api.album(screen.id)
            is Screen.ArtistLiked -> {
                val all = vm.api.likedTracksCached() ?: vm.api.likedTracks()
                val mine = all.filter { screen.artistId in it.artistIds }
                Collection(
                    title = "שירים שאהבתם",
                    subtitle = "${screen.artistName} • ${mine.size} שירים",
                    // ההקשר של Spotify ל"שירים שאהבת של אמן מסוים"
                    uri = "spotify:user:${vm.api.me().id}:collection:artist:${screen.artistId}",
                    tracks = mine
                )
            }
            else -> {
                val liked = vm.api.likedTracks()
                Collection(
                    title = "שירים שאהבתי",
                    subtitle = "${liked.size} שירים",
                    uri = "spotify:user:${vm.api.me().id}:collection",
                    tracks = liked
                )
            }
        }
    }
    val isAlbum = screen is Screen.Album
    val isLiked = screen is Screen.Liked || screen is Screen.ArtistLiked

    LoadBox(load) { c ->
        val tracks = c.tracks
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                DetailHeader(
                    title = c.title,
                    subtitle = c.subtitle,
                    tile = {
                        if (isLiked) LikedTile(180.dp)
                        else IconTile(
                            iconFor(if (isAlbum) com.example.spotalk.api.ItemType.ALBUM else com.example.spotalk.api.ItemType.PLAYLIST),
                            c.title, 180.dp,
                            imageUrl = c.imageUrl, artistIds = c.artistIds
                        )
                    },
                    onBack = { vm.back() }
                )
                ActionsRow(
                    spotifyUri = c.uri.ifBlank { null },
                    onPlay = {
                        val first = tracks?.firstOrNull()
                        when {
                            screen is Screen.ArtistLiked && first != null ->
                                vm.playTrackList(tracks.orEmpty().map { it.uri }, first.uri)
                            isLiked && first != null -> vm.playInList(c.uri, 0, first.uri)
                            c.uri.isNotBlank() -> vm.player.play(c.uri)
                            first != null -> vm.player.play(first.uri)
                        }
                    },
                    onShuffle = if (c.uri.isNotBlank()) ({ vm.player.playShuffled(c.uri) }) else null
                )
            }
            if (tracks == null) {
                item { Message("Spotify מציגה את רשימת השירים רק בפלייליסטים שלך. אפשר לנגן את כולו בכפתור למעלה.") }
            } else {
                itemsIndexed(tracks) { i, t ->
                    TrackRow(
                        item = t,
                        isCurrent = t.uri == currentUri,
                        leadingNumber = if (isAlbum) i + 1 else null,
                        onClick = {
                            val index = i
                            when {
                                // "שירים שאהבתם" של אמן: מנגנים בדיוק את הרשימה הזו, מהשיר שנלחץ
                                screen is Screen.ArtistLiked -> vm.playTrackList(tracks.map { it.uri }, t.uri)
                                c.uri.isNotBlank() && index >= 0 -> vm.playInList(c.uri, index, t.uri)
                                else -> vm.player.play(t.uri)
                            }
                        },
                        onAddToPlaylist = { vm.requestAddToPlaylist(listOf(t.uri)) }
                    )
                }
            }
            item { SpotifyAttribution(Modifier.fillMaxWidth().padding(24.dp)) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun DetailHeader(title: String, subtitle: String, tile: @Composable () -> Unit, onBack: () -> Unit) {
    val tint = Palette.tintFor(title)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(tint, Palette.Background)))
    ) {
        IconButton(onClick = onBack, modifier = Modifier.padding(4.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה")
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { tile() }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 16.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(subtitle, color = Palette.TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    }
}

@Composable
private fun ActionsRow(spotifyUri: String?, onPlay: () -> Unit, onShuffle: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (spotifyUri != null) OpenInSpotify(spotifyUri)
        Spacer(Modifier.weight(1f))
        if (onShuffle != null) IconButton(onClick = onShuffle) {
            Icon(Icons.Filled.Shuffle, "ערבוב", tint = Palette.TextSecondary, modifier = Modifier.size(28.dp))
        }
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(Palette.Green).clickable(onClick = onPlay),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.PlayArrow, "נגן", tint = Color.Black, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.width(8.dp))
    }
}

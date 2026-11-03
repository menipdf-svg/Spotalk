package com.example.spotalk.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.spotalk.MainViewModel
import com.example.spotalk.Screen
import com.example.spotalk.api.ArtistPage
import com.example.spotalk.api.Item
import com.example.spotalk.api.ItemType
import com.example.spotalk.remote.NowPlaying
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch

/**
 * עמוד אמן בעיצוב של Spotify — בלי תמונות:
 * כותרת גדולה, מעקב/ערבוב/ניגון, "שירים שאהבתם", פופולריים, רליסים פופולריים,
 * פלייליסטים, "מופיע ב-", וכותרת עליונה שמופיעה בגלילה.
 */
@Composable
fun ArtistScreen(vm: MainViewModel, screen: Screen.Artist, now: NowPlaying) {
    val load = rememberLoad(screen, "artist:${screen.id}") { vm.api.artistPage(screen.id) }
    LoadBox(load) { page -> ArtistContent(vm, page, now) }
}

@Composable
private fun ArtistContent(vm: MainViewModel, page: ArtistPage, now: NowPlaying) {
    val artist = page.artist
    val tint = Palette.tintFor(artist.title)
    val listState = rememberLazyListState()
    val showTopBar by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val scope = rememberCoroutineScope()
    var following by remember(artist.id) { mutableStateOf(page.isFollowing) }
    var showAllPopular by rememberSaveable(artist.id) { mutableStateOf(false) }

    // האם מתנגן עכשיו משהו של האמן הזה
    val playingThisArtist = !now.isPaused && now.hasTrack &&
        (page.tracks.any { it.uri == now.trackUri } || now.artist.contains(artist.title))

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            // ---------- כותרת גדולה (עם תמונת האמן) ----------
            item {
                val heroIds = artist.artistIds
                if (Images.enabled && Images.maleOnly && artist.imageUrl != null) {
                    LaunchedEffect(artist.id) { Images.genders.request(heroIds) }
                }
                val heroImage = artist.imageUrl != null && Images.allowed(heroIds)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(if (heroImage) 360.dp else 300.dp)
                        .background(Brush.verticalGradient(listOf(tint, tint.copy(alpha = 0.55f), Palette.Background)))
                ) {
                    if (heroImage) {
                        // תמונת האמן, כמו ב-Spotify
                        AsyncImage(
                            model = artist.imageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize()
                        )
                        Box(
                            Modifier
                                .matchParentSize()
                                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                        )
                    }
                    Text(
                        artist.title,
                        fontSize = 46.sp,
                        fontWeight = FontWeight.Black,
                        lineHeight = 50.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }

            // ---------- פרטים + כפתורים ----------
            item {
                if (page.genres.isNotEmpty()) {
                    Text(
                        page.genres.take(3).joinToString(" • "),
                        color = Palette.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            val target = !following
                            following = target
                            scope.launch { following = vm.setFollowing(artist, target) }
                        },
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, if (following) Palette.TextPrimary else Palette.TextSecondary),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp)
                    ) {
                        Text(
                            if (following) "במעקב" else "מעקב",
                            color = Palette.TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { vm.player.playShuffled(artist.uri) }) {
                        Icon(Icons.Filled.Shuffle, "ערבוב", tint = Palette.TextSecondary, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Palette.Green)
                            .clickable {
                                if (playingThisArtist) vm.player.togglePlay() else vm.player.play(artist.uri)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (playingThisArtist) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            "נגן",
                            tint = Color.Black,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            // ---------- שירים שאהבתם ----------
            if (page.likedTracks.isNotEmpty()) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.open(Screen.ArtistLiked(artist.id, artist.title)) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box {
                            IconTile(iconFor(ItemType.ARTIST), artist.title, 56.dp, CircleShape, imageUrl = artist.imageUrl, artistIds = artist.artistIds)
                            Box(
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .offset(x = (-4).dp, y = 4.dp)
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(Palette.Background),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.Favorite, null, tint = Palette.Green, modifier = Modifier.size(16.dp))
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("שירים שאהבתם", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${page.likedTracks.size} שירים • ${artist.title}",
                                color = Palette.TextSecondary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.TextSecondary)
                    }
                }
            }

            // ---------- פופולריים ----------
            if (page.tracks.isNotEmpty()) {
                item { SectionTitle("פופולריים") }
                val shown = if (showAllPopular) page.tracks else page.tracks.take(5)
                itemsIndexed(shown) { i, t ->
                    PopularRow(
                        number = i + 1,
                        item = t,
                        isCurrent = t.uri == now.trackUri,
                        onClick = { vm.player.play(t.uri) },
                        onAddToPlaylist = { vm.requestAddToPlaylist(listOf(t.uri)) }
                    )
                }
                if (page.tracks.size > 5) item {
                    TextButton(
                        onClick = { showAllPopular = !showAllPopular },
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        Text(
                            if (showAllPopular) "הצג פחות" else "ראה עוד (${page.tracks.size})",
                            color = Palette.TextSecondary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // ---------- רליסים פופולריים ----------
            val latest = page.latest
            val releases = (listOfNotNull(latest) + page.albums + page.singles).distinctBy { it.uri }
            if (releases.isNotEmpty()) {
                item { SectionTitle("רליסים פופולריים") }
                itemsIndexed(releases.take(4)) { i, r ->
                    ReleaseRow(r, isLatest = i == 0 && latest != null) { vm.openItem(r) }
                }
                item {
                    OutlinedButton(
                        onClick = { vm.open(Screen.Discography(artist.id, artist.title)) },
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, Palette.TextMuted),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("הצג את כל הדיסקוגרפיה", color = Palette.TextPrimary, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // ---------- פלייליסטים ----------
            if (page.playlists.isNotEmpty()) {
                item { SectionTitle("פלייליסטים עם ${artist.title}") }
                item { CardRow(page.playlists) { vm.openItem(it) } }
            }

            // ---------- מופיע ב- ----------
            if (page.appearsOn.isNotEmpty()) {
                item { SectionTitle("מופיע ב-") }
                item { CardRow(page.appearsOn) { vm.openItem(it) } }
            }

            item { SpotifyAttribution(Modifier.fillMaxWidth().padding(24.dp)) }
            item { Spacer(Modifier.height(16.dp)) }
        }

        // ---------- כפתורים צפים (לפני הגלילה) ----------
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundIconButton(onClick = { vm.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה")
            }
            Spacer(Modifier.weight(1f))
            ArtistMenu(vm, artist)
        }

        // ---------- כותרת עליונה בגלילה ----------
        AnimatedVisibility(visible = showTopBar, enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(red = tint.red * 0.55f, green = tint.green * 0.55f, blue = tint.blue * 0.55f))
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { vm.back() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה")
                }
                Text(
                    artist.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                ArtistMenu(vm, artist)
            }
        }
    }
}

@Composable
private fun RoundIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun ArtistMenu(vm: MainViewModel, artist: Item) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        RoundIconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, "אפשרויות")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Palette.Card) {
            DropdownMenuItem(
                text = { Text("פתח ב-Spotify") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                onClick = {
                    open = false
                    openInSpotify(ctx, artist.uri)
                }
            )
        }
    }
}

/** שורה ממוספרת כמו ב"פופולריים" של Spotify: מספר, משבצת, שם, אלבום, ⋮ */
@Composable
private fun PopularRow(
    number: Int,
    item: Item,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onAddToPlaylist: () -> Unit
) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$number",
            color = if (isCurrent) Palette.Green else Palette.TextSecondary,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.width(26.dp)
        )
        IconTile(iconFor(ItemType.TRACK), item.albumName.ifBlank { item.title }, 48.dp, imageUrl = item.imageUrl, artistIds = item.artistIds)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                color = if (isCurrent) Palette.Green else Palette.TextPrimary,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.explicit) {
                    ExplicitBadge()
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    item.albumName.ifBlank { item.subtitle },
                    color = Palette.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, "אפשרויות", tint = Palette.TextSecondary)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Palette.Card) {
                LocalTrackActions.current?.let { actions ->
                    DropdownMenuItem(
                        text = { Text("הוספה לתור") },
                        onClick = {
                            menu = false
                            actions.queue(item)
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("הוספה לפלייליסט") },
                    onClick = {
                        menu = false
                        onAddToPlaylist()
                    }
                )
                DropdownMenuItem(
                    text = { Text("פתח ב-Spotify") },
                    onClick = {
                        menu = false
                        openInSpotify(ctx, item.uri)
                    }
                )
            }
        }
    }
}

/** שורת רליס: משבצת גדולה, שם, "רליס אחרון" / שנה • סוג */
@Composable
private fun ReleaseRow(item: Item, isLatest: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(iconFor(ItemType.ALBUM), item.title, 72.dp, imageUrl = item.imageUrl, artistIds = item.artistIds)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val kind = if (item.isSingle) "סינגל" else "אלבום"
            val year = item.releaseDate.take(4)
            Text(
                if (isLatest) "רליס אחרון • $kind" else listOf(year, kind).filter { it.isNotBlank() }.joinToString(" • "),
                color = Palette.TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** שורה אופקית של כרטיסים (פלייליסטים / "מופיע ב-") */
@Composable
private fun CardRow(list: List<Item>, onClick: (Item) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(list) { item ->
            Column(
                Modifier
                    .width(144.dp)
                    .clickable { onClick(item) }
            ) {
                IconTile(iconFor(item.type), item.title, 144.dp, imageUrl = item.imageUrl, artistIds = item.artistIds)
                Spacer(Modifier.height(8.dp))
                Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    item.subtitle,
                    color = Palette.TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ---------- דיסקוגרפיה ----------

private enum class DiscoFilter(val label: String) {
    ALL("הכל"), ALBUMS("אלבומים"), SINGLES("סינגלים ו-EP"), COMPILATIONS("אוספים")
}

/** כל הדיסקוגרפיה של האמן, עם מסננים כמו ב-Spotify */
@Composable
fun DiscographyScreen(vm: MainViewModel, screen: Screen.Discography) {
    val load = rememberLoad(screen, "disco:${screen.artistId}") { vm.api.discography(screen.artistId) }
    var filter by rememberSaveable { mutableStateOf(DiscoFilter.ALL) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") }
            Text("דיסקוגרפיה • ${screen.artistName}", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DiscoFilter.entries.forEach { f ->
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
        LoadBox(load) { page ->
            val list = when (filter) {
                DiscoFilter.ALL -> (page.albums + page.singles + page.compilations).sortedByDescending { it.releaseDate }
                DiscoFilter.ALBUMS -> page.albums
                DiscoFilter.SINGLES -> page.singles
                DiscoFilter.COMPILATIONS -> page.compilations
            }
            LazyColumn(Modifier.fillMaxSize()) {
                if (list.isEmpty()) item { Message("אין כאן כלום") }
                items(list) { r -> ReleaseRow(r, isLatest = false) { vm.openItem(r) } }
                item { SpotifyAttribution(Modifier.fillMaxWidth().padding(24.dp)) }
            }
        }
    }
}

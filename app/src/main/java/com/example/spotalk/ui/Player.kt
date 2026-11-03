package com.example.spotalk.ui

import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.spotalk.remote.NowPlaying
import com.example.spotalk.remote.RemotePlayer
import com.spotify.protocol.types.Repeat
import kotlinx.coroutines.delay

/** תמונת האלבום של השיר שמתנגן + האמנים שלו (מוצגת רק אם כולם גברים) */
val LocalNowArt = compositionLocalOf<Pair<String?, List<String>>> { null to emptyList() }

/** מיקום הניגון בזמן אמת (מתעדכן פעמיים בשנייה) */
@Composable
private fun livePosition(state: NowPlaying): Long {
    val pos by produceState(state.livePosition(), state) {
        while (true) {
            value = state.livePosition()
            delay(500)
        }
    }
    return pos
}

@Composable
fun MiniPlayer(state: NowPlaying, player: RemotePlayer, onOpen: () -> Unit) {
    val tint = Palette.tintFor(state.album.ifBlank { state.title })
    val pos = livePosition(state)
    val darker = Color(red = tint.red * 0.6f, green = tint.green * 0.6f, blue = tint.blue * 0.6f)

    Column(
        Modifier
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(darker)
            .clickable(onClick = onOpen)
            .pointerInput(Unit) {
                // החלקה למעלה על המיני-נגן פותחת את הנגן המלא
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total < -40f) onOpen() }
                ) { change, amount ->
                    change.consume()
                    total += amount
                }
            }
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val art = LocalNowArt.current
            IconTile(Icons.Filled.MusicNote, state.album.ifBlank { state.title }, 40.dp, imageUrl = art.first, artistIds = art.second)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(state.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(state.artist, color = Palette.TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (state.canLike) IconButton(onClick = player::toggleLike) {
                Icon(
                    if (state.isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    "אהבתי",
                    tint = if (state.isLiked) Palette.Green else Palette.TextPrimary
                )
            }
            IconButton(onClick = player::togglePlay) {
                Icon(if (state.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause, "נגן/השהה", modifier = Modifier.size(30.dp))
            }
        }
        // פס התקדמות דק כמו ב-Spotify
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 8.dp).background(Color.White.copy(alpha = 0.25f))) {
                val fraction = if (state.durationMs > 0) (pos.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Color.White))
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
fun FullPlayer(
    state: NowPlaying,
    player: RemotePlayer,
    onAddToPlaylist: () -> Unit,
    onOpenArtist: (uri: String, name: String) -> Unit,
    onOpenQueue: () -> Unit,
    onClose: () -> Unit
) {
    val tint = Palette.tintFor(state.album.ifBlank { state.title })
    var artistMenu by remember { mutableStateOf(false) }
    val pos = livePosition(state)
    var dragging by remember { mutableStateOf<Float?>(null) }

    // החלקה למטה סוגרת את הנגן (כמו ב-Spotify)
    val scope = rememberCoroutineScope()
    val dragY = remember { Animatable(0f) }
    val density = LocalDensity.current
    val closeThreshold = with(density) { 110.dp.toPx() }
    val screenHeight = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }

    // הנגן מוצג מעל המסך (לא בתוך Scaffold) — לכן קובעים במפורש: טקסט ואייקונים בלבן
    CompositionLocalProvider(LocalContentColor provides Color.White) {
    Column(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dragY.value.roundToInt()) }
            .background(Brush.verticalGradient(listOf(tint, Palette.Background)))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (dragY.value > closeThreshold) {
                                // ממשיכים להחליק עד למטה בצורה חלקה, ורק אז סוגרים
                                dragY.animateTo(screenHeight, tween(220))
                                onClose()
                            } else {
                                dragY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                            }
                        }
                    },
                    onDragCancel = { scope.launch { dragY.animateTo(0f) } }
                ) { change, amount ->
                    change.consume()
                    scope.launch { dragY.snapTo((dragY.value + amount).coerceAtLeast(0f)) }
                }
            }
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
    ) {
        // ידית גרירה במקום החץ
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(width = 40.dp, height = 5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.5f))
            )
        }
        Column(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("מתנגן מתוך", color = Palette.TextSecondary, style = MaterialTheme.typography.labelSmall)
            Text(state.contextTitle.ifBlank { "Spotify" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        // אזור "העטיפה" — אייקון בלבד, בלי תמונה
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                val art = LocalNowArt.current
                IconTile(
                    Icons.Filled.MusicNote, state.album.ifBlank { state.title }, 320.dp, RoundedCornerShape(8.dp),
                    iconScale = 0.35f, imageUrl = art.first, artistIds = art.second
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // לחיצה על שם האמן פותחת את הפרופיל שלו (אם יש כמה — בוחרים מרשימה)
                Box {
                    Text(
                        state.artists.joinToString(", ") { it.first }.ifBlank { state.artist },
                        color = Palette.TextSecondary,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable {
                            when (state.artists.size) {
                                0 -> Unit
                                1 -> state.artists.first().let { (name, uri) -> onOpenArtist(uri, name) }
                                else -> artistMenu = true
                            }
                        }
                    )
                    DropdownMenu(
                        expanded = artistMenu,
                        onDismissRequest = { artistMenu = false },
                        containerColor = Palette.Card
                    ) {
                        state.artists.forEach { (name, uri) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    artistMenu = false
                                    onOpenArtist(uri, name)
                                }
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onAddToPlaylist) {
                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "הוספה לפלייליסט", modifier = Modifier.size(28.dp))
            }
            if (state.canLike) IconButton(onClick = player::toggleLike) {
                Icon(
                    if (state.isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    "אהבתי",
                    tint = if (state.isLiked) Palette.Green else Palette.TextPrimary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // פקדי הנגן תמיד משמאל לימין, גם בעברית — כמו ב-Spotify
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column {
                val duration = state.durationMs.coerceAtLeast(1)
                Slider(
                    value = dragging ?: (pos.toFloat() / duration).coerceIn(0f, 1f),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { player.seekTo((it * duration).toLong()) }
                        dragging = null
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    )
                )
                Row(Modifier.fillMaxWidth()) {
                    val shown = dragging?.let { (it * duration).toLong() } ?: pos
                    Text(formatTime(shown), color = Palette.TextSecondary, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    Text(formatTime(state.durationMs), color = Palette.TextSecondary, style = MaterialTheme.typography.labelSmall)
                }

                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = player::toggleShuffle) {
                        Icon(Icons.Filled.Shuffle, "ערבוב", tint = if (state.shuffle) Palette.Green else Palette.TextPrimary)
                    }
                    IconButton(onClick = player::previous, enabled = state.canSkipPrev) {
                        Icon(Icons.Filled.SkipPrevious, "הקודם", modifier = Modifier.size(40.dp))
                    }
                    Box(
                        Modifier.size(68.dp).clip(CircleShape).background(Color.White).clickable(onClick = player::togglePlay),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (state.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            "נגן/השהה",
                            tint = Color.Black,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                    IconButton(onClick = player::next, enabled = state.canSkipNext) {
                        Icon(Icons.Filled.SkipNext, "הבא", modifier = Modifier.size(40.dp))
                    }
                    IconButton(onClick = player::toggleRepeat) {
                        Icon(
                            if (state.repeatMode == Repeat.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                            "חזרה",
                            tint = if (state.repeatMode == Repeat.OFF) Palette.TextPrimary else Palette.Green
                        )
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            state.trackUri?.let { OpenInSpotify(it) }
            SpotifyAttribution(Modifier.weight(1f))
            // תור השמעה — כמו בפינה של הנגן ב-Spotify
            IconButton(onClick = onOpenQueue) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, "תור השמעה")
            }
        }
    }
    }
}

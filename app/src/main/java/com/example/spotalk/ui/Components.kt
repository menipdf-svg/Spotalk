package com.example.spotalk.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.spotalk.api.Item
import com.example.spotalk.gender.GenderRepo
import com.example.spotalk.api.ItemType

/** פעולות על שיר שזמינות מכל מקום (תור השמעה) — מסופק מ-AppRoot */
class TrackActions(val queue: (Item) -> Unit)

val LocalTrackActions = staticCompositionLocalOf<TrackActions?> { null }

fun iconFor(type: ItemType): ImageVector = when (type) {
    ItemType.TRACK -> Icons.Filled.MusicNote
    ItemType.ALBUM -> Icons.Filled.Album
    ItemType.ARTIST -> Icons.Filled.Person
    ItemType.PLAYLIST -> Icons.AutoMirrored.Filled.QueueMusic
}

/** מצב התמונות: האם מציגים, והאם רק של זמרים גברים */
object Images {
    lateinit var genders: GenderRepo
    var enabled by mutableStateOf(true)
    var maleOnly by mutableStateOf(false)

    fun apply(mode: String) {
        enabled = mode != "none"
        maleOnly = mode == "male"
    }

    /** האם מותר להציג תמונה של פריט עם האמנים האלה (במצב "רק גברים": כולם חייבים להיות גברים) */
    fun allowed(artistIds: List<String>): Boolean =
        enabled && (!maleOnly || (artistIds.isNotEmpty() && genders.allMale(artistIds)))
}

/**
 * משבצת "עטיפה". מציגה את התמונה מ-Spotify כשיש כזו וההגדרה פעילה;
 * אחרת — משבצת צבע עם אייקון.
 * במצב "רק זמרים גברים" התמונה מוצגת רק כשכל האמנים (artistIds) ידועים כגברים.
 */
@Composable
fun IconTile(
    icon: ImageVector,
    tintKey: String,
    size: Dp,
    shape: Shape = RoundedCornerShape(4.dp),
    iconScale: Float = 0.45f,
    imageUrl: String? = null,
    artistIds: List<String> = emptyList()
) {
    val wantsImage = Images.enabled && imageUrl != null
    if (wantsImage && Images.maleOnly && artistIds.isNotEmpty()) {
        LaunchedEffect(artistIds) { Images.genders.request(artistIds) }
    }
    val showImage = wantsImage && Images.allowed(artistIds)
    val base = Palette.tintFor(tintKey)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(Brush.linearGradient(listOf(base, base.copy(alpha = 0.55f).compositeOverDark()))),
        contentAlignment = Alignment.Center
    ) {
        if (showImage) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        } else {
            Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(size * iconScale))
        }
    }
}

private fun Color.compositeOverDark(): Color = Color(
    red = red * alpha + 0.07f * (1 - alpha),
    green = green * alpha + 0.07f * (1 - alpha),
    blue = blue * alpha + 0.07f * (1 - alpha)
)

@Composable
fun LikedTile(size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(4.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF450AF5), Color(0xFFC4EFD9)))),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.Favorite, null, tint = Color.White, modifier = Modifier.size(size * 0.42f))
    }
}

@Composable
fun TrackRow(
    item: Item,
    isCurrent: Boolean,
    onClick: () -> Unit,
    leadingNumber: Int? = null,
    onAddToPlaylist: (() -> Unit)? = null
) {
    var menuOpen by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingNumber != null) {
            Text(
                "$leadingNumber",
                color = if (isCurrent) Palette.Green else Palette.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(28.dp)
            )
        } else {
            IconTile(Icons.Filled.MusicNote, item.subtitle, 48.dp, imageUrl = item.imageUrl, artistIds = item.artistIds)
            Spacer(Modifier.width(12.dp))
        }
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
                    item.subtitle,
                    color = Palette.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (item.durationMs > 0) {
            Spacer(Modifier.width(8.dp))
            Text(formatTime(item.durationMs), color = Palette.TextMuted, style = MaterialTheme.typography.bodyMedium)
        }
        val actions = LocalTrackActions.current
        if (onAddToPlaylist != null || actions != null) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, "אפשרויות", tint = Palette.TextSecondary)
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = Palette.Card
                ) {
                    if (actions != null) {
                        DropdownMenuItem(
                            text = { Text("הוספה לתור") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                            onClick = {
                                menuOpen = false
                                actions.queue(item)
                            }
                        )
                    }
                    if (onAddToPlaylist != null) DropdownMenuItem(
                        text = { Text("הוספה לפלייליסט") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                        onClick = {
                            menuOpen = false
                            onAddToPlaylist()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("פתח ב-Spotify") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                        onClick = {
                            menuOpen = false
                            openInSpotify(ctx, item.uri)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ItemRow(item: Item, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(
            icon = iconFor(item.type),
            tintKey = item.title,
            size = 56.dp,
            shape = if (item.type == ItemType.ARTIST) CircleShape else RoundedCornerShape(4.dp),
            imageUrl = item.imageUrl,
            artistIds = item.artistIds
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                item.subtitle,
                color = Palette.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ExplicitBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(2.dp))
            .background(Palette.TextSecondary)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("E", color = Palette.Background, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
    )
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Palette.Green)
    }
}

@Composable
fun Message(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Palette.TextSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

/** ייחוס ל-Spotify + קישור חזרה, כנדרש בהנחיות המיתוג */
@Composable
fun OpenInSpotify(uri: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    TextButton(onClick = { openInSpotify(ctx, uri) }, modifier = modifier) {
        Text("פתח ב-Spotify", color = Palette.TextPrimary, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SpotifyAttribution(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        // להחליף באייקון הרשמי מ-developer.spotify.com/documentation/design
        Text("התוכן מסופק על ידי ", color = Palette.TextMuted, style = MaterialTheme.typography.labelSmall)
        Text("Spotify", color = Palette.TextSecondary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

fun openInSpotify(context: Context, spotifyUri: String) {
    val app = Intent(Intent.ACTION_VIEW, Uri.parse(spotifyUri)).setPackage("com.spotify.music")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(app)
    } catch (e: ActivityNotFoundException) {
        // spotify:album:ID -> https://open.spotify.com/album/ID
        val parts = spotifyUri.split(":")
        val web = if (parts.size >= 3) "https://open.spotify.com/${parts[1]}/${parts[2]}" else "https://open.spotify.com"
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

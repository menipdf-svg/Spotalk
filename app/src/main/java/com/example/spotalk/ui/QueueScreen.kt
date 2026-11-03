package com.example.spotalk.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.spotalk.MainViewModel
import com.example.spotalk.remote.NowPlaying

/**
 * תור ההשמעה, כמו ב-Spotify: מה מתנגן עכשיו, ומה הבא.
 * מתרענן אוטומטית בכל מעבר שיר.
 */
@Composable
fun QueueScreen(vm: MainViewModel, now: NowPlaying) {
    var refresh by remember { mutableIntStateOf(0) }
    val load = rememberLoad(now.trackChangeCount to refresh, "queue") { vm.api.queue() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") }
            Text("תור השמעה", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { refresh++ }) { Icon(Icons.Filled.Refresh, "רענון") }
        }
        LoadBox(load) { (current, next) ->
            LazyColumn(Modifier.fillMaxSize()) {
                if (current != null) {
                    item { SectionTitle("מתנגן עכשיו") }
                    item { TrackRow(current, isCurrent = true, onClick = {}) }
                }
                item { SectionTitle("הבא בתור") }
                if (next.isEmpty()) {
                    item { Message("התור ריק") }
                } else {
                    items(next) { t ->
                        TrackRow(
                            t, isCurrent = false,
                            onClick = { vm.player.play(t.uri) },
                            onAddToPlaylist = { vm.requestAddToPlaylist(listOf(t.uri)) }
                        )
                    }
                }
                item {
                    Text(
                        "Spotify מאפשרת להוסיף לתור, אבל לא למחוק ממנו או לשנות את הסדר דרך אפליקציה חיצונית.",
                        color = Palette.TextMuted,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                item { SpotifyAttribution(Modifier.fillMaxWidth().padding(24.dp)) }
            }
        }
    }
}

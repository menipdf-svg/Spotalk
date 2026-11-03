package com.example.spotalk.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.produceState
import androidx.compose.ui.text.style.TextOverflow
import com.example.spotalk.api.Item
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.spotalk.MainViewModel
import com.example.spotalk.Screen
import com.example.spotalk.Tab
import kotlinx.coroutines.delay

@Composable
fun AppRoot(vm: MainViewModel, onLogin: () -> Unit) {
    val loggedIn by vm.auth.loggedIn.collectAsState()
    if (!loggedIn) {
        LoginScreen(vm, onLogin)
        return
    }

    val stack by vm.stack.collectAsState()
    val tab by vm.tab.collectAsState()
    val now by vm.player.state.collectAsState()
    val addTarget by vm.addTarget.collectAsState()
    val message by vm.message.collectAsState()
    var showPlayer by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = showPlayer) { showPlayer = false }
    BackHandler(enabled = !showPlayer && stack.size > 1) { vm.back() }

    val trackActions = remember(vm) { TrackActions { item -> vm.queueTrack(item) } }
    // תמונת האלבום של השיר הנוכחי — בקשה אחת לכל שיר, כשההגדרה "הצג תמונות" פעילה
    val trackId = now.trackUri?.takeIf { it.startsWith("spotify:track:") }?.substringAfterLast(":")
    val artLoad = rememberLoad(trackId to Images.enabled, "art:$trackId") {
        if (trackId == null || !Images.enabled) null to emptyList() else vm.api.trackArt(trackId)
    }
    val nowArt = (artLoad as? Load.Done<Pair<String?, List<String>>>)?.value ?: (null to emptyList())
    val startVoice = rememberVoiceStarter(vm)
    CompositionLocalProvider(
        LocalTrackActions provides trackActions,
        LocalNowArt provides nowArt,
        LocalStartVoice provides startVoice
    ) {
    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        Scaffold(
            containerColor = Palette.Background,
            bottomBar = {
                Column(Modifier.background(Brush0)) {
                    message?.let { msg ->
                        LaunchedEffect(msg) {
                            delay(4000)
                            vm.clearMessage()
                        }
                        ConnectionBanner(msg, actionLabel = "סגור") { vm.clearMessage() }
                        Spacer(Modifier.height(6.dp))
                    }
                    now.notice?.let { msg ->
                        LaunchedEffect(msg) {
                            delay(6000)
                            vm.player.clearNotice()
                        }
                        ConnectionBanner(msg, actionLabel = "סגור") { vm.player.clearNotice() }
                        Spacer(Modifier.height(6.dp))
                    }
                    if (now.hasTrack) MiniPlayer(now, vm.player) { showPlayer = true }
                    else if (now.error != null) {
                        if (now.canWake) ConnectionBanner(now.error!!, actionLabel = "פתח Spotify") { vm.player.openSpotifyApp() }
                        else ConnectionBanner(now.error!!) { vm.player.connect() }
                    } else if (now.connecting) {
                        ConnectionBanner("מתחבר ל-Spotify…", actionLabel = "") {}
                    }
                    BottomBar(tab) { vm.selectTab(it) }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (val screen = stack.last()) {
                    is Screen.Root -> when (screen.tab) {
                        Tab.HOME -> HomeScreen(vm, now.trackUri)
                        Tab.SEARCH -> SearchScreen(vm, now.trackUri)
                        Tab.LIBRARY -> LibraryScreen(vm)
                    }
                    is Screen.Playlist, is Screen.Album, Screen.Liked, is Screen.ArtistLiked ->
                        CollectionScreen(vm, screen, now.trackUri)
                    is Screen.Artist -> ArtistScreen(vm, screen, now)
                    is Screen.Discography -> DiscographyScreen(vm, screen)
                    Screen.Settings -> SettingsScreen(vm)
                    Screen.Queue -> QueueScreen(vm, now)
                }
            }
        }

        AnimatedVisibility(
            visible = showPlayer && now.hasTrack,
            enter = slideInVertically { it },
            // אחרי החלקה הנגן כבר למטה — יציאה מהירה בלי קפיצה
            exit = slideOutVertically(tween(160)) { it }
        ) {
            FullPlayer(
                state = now,
                player = vm.player,
                onAddToPlaylist = { now.trackUri?.let { vm.requestAddToPlaylist(listOf(it)) } },
                onOpenArtist = { uri, name ->
                    showPlayer = false
                    vm.openArtistUri(uri, name)
                },
                onOpenQueue = {
                    showPlayer = false
                    vm.open(Screen.Queue)
                },
                onClose = { showPlayer = false }
            )
        }

        if (addTarget != null) {
            AddToPlaylistDialog(vm, addTarget!!)
        }

        val listenState by vm.voice.state.collectAsState()
        ListeningOverlay(listenState) { vm.cancelVoice() }
    }
    }
}

/** בחירת פלייליסט להוספה — רק פלייליסטים שלך או שיתופיים */
@Composable
private fun AddToPlaylistDialog(vm: MainViewModel, uris: List<String>) {
    val load = rememberLoad(Unit) { vm.api.editablePlaylists() }
    AlertDialog(
        onDismissRequest = vm::cancelAddToPlaylist,
        containerColor = Palette.Elevated,
        title = { Text("הוספה לפלייליסט") },
        text = {
            Box(Modifier.heightIn(min = 80.dp, max = 420.dp)) {
                when (load) {
                    is Load.Loading -> Loading()
                    is Load.Failed -> Message(load.message)
                    is Load.Done -> if (load.value.isEmpty()) {
                        Message("אין לך פלייליסטים שאפשר להוסיף אליהם")
                    } else {
                        LazyColumn {
                            items(load.value) { p ->
                                PlaylistChoiceRow(vm, p, uris)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = vm::cancelAddToPlaylist) { Text("ביטול", color = Palette.TextSecondary) }
        }
    )
}

private val Brush0 = androidx.compose.ui.graphics.Brush.verticalGradient(
    listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f), Color.Black)
)

@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
        listOf(
            Triple(Tab.HOME, Icons.Filled.Home, "בית"),
            Triple(Tab.SEARCH, Icons.Filled.Search, "חיפוש"),
            Triple(Tab.LIBRARY, Icons.Filled.LibraryMusic, "הספרייה שלך")
        ).forEach { (t, icon, label) ->
            NavigationBarItem(
                selected = t == selected,
                onClick = { onSelect(t) },
                icon = { Icon(icon, null) },
                label = { Text(label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Palette.TextPrimary,
                    selectedTextColor = Palette.TextPrimary,
                    unselectedIconColor = Palette.TextSecondary,
                    unselectedTextColor = Palette.TextSecondary,
                    indicatorColor = Color.Transparent
                )
            )
        }
    }
}

@Composable
private fun ConnectionBanner(error: String, actionLabel: String = "נסה שוב", onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            .background(Palette.Highlight, RoundedCornerShape(6.dp))
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            error,
            color = Palette.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        if (actionLabel.isNotEmpty()) {
            TextButton(onClick = onRetry) {
                Text(actionLabel, color = Palette.Green)
            }
        } else {
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun LoginScreen(vm: MainViewModel, onLogin: () -> Unit) {
    val error by vm.authError.collectAsState()
    val clientId by vm.settings.clientId.collectAsState()
    var editing by rememberSaveable { mutableStateOf(false) }
    val configured = clientId.isNotBlank()

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Text("TextPlay", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "שליטה ב-Spotify בממשק נקי — בלי תמונות",
            color = Palette.TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(40.dp))

        if (!configured || editing) {
            // הגדרה ראשונה (או עריכה): הזנת Client ID
            ClientIdSection(vm)
            if (configured) {
                TextButton(onClick = { editing = false }) { Text("סיום", color = Palette.TextSecondary) }
            }
        } else {
            Button(
                onClick = onLogin,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Green, contentColor = Color.Black),
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text("התחבר עם Spotify", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { editing = true }) {
                Text("הגדרות (Client ID)", color = Palette.TextSecondary)
            }
        }

        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = Color(0xFFFF8A80), textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(32.dp))
        LogsSection(Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
        SpotifyAttribution()
    }
}

/** שורה בחלון הבחירה: מסומנת ✓ אם השיר כבר נמצא בפלייליסט */
@Composable
private fun PlaylistChoiceRow(vm: MainViewModel, playlist: Item, uris: List<String>) {
    val contains by produceState<Boolean?>(null, playlist.id, uris) {
        value = runCatching { vm.playlistContains(playlist.id, uris) }.getOrNull()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { vm.addToPlaylist(playlist) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(iconFor(playlist.type), playlist.title, 44.dp, imageUrl = playlist.imageUrl)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(playlist.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (contains == true) {
                Text("כבר נמצא כאן", color = Palette.Green, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (contains == true) {
            Icon(Icons.Filled.CheckCircle, "כבר נמצא", tint = Palette.Green)
        }
    }
}

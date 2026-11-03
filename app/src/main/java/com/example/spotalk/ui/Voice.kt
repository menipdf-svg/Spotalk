package com.example.spotalk.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.spotalk.MainViewModel
import com.example.spotalk.voice.ListenState

/** מתחיל להקשיב לעוזר הקולי — מסופק מ-AppRoot */
val LocalStartVoice = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * עוזר קולי: בודק הרשאת מיקרופון (בפעם הראשונה מבקש), ואז מתחיל הקשבה מובנית.
 * ההקשבה מסתיימת לבד כשמפסיקים לדבר.
 */
@Composable
fun rememberVoiceStarter(vm: MainViewModel): () -> Unit {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startVoice() else vm.showMessage("צריך הרשאת מיקרופון כדי לדבר עם העוזר")
    }
    return remember(permission) {
        val start: () -> Unit = {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) vm.startVoice() else permission.launch(Manifest.permission.RECORD_AUDIO)
        }
        start
    }
}

/** כפתור מיקרופון. לא מוצג אם אין עוזר קולי זמין (למשל לפני התחברות) */
@Composable
fun MicButton(tint: Color, modifier: Modifier = Modifier) {
    val start = LocalStartVoice.current ?: return
    IconButton(onClick = start, modifier = modifier) {
        Icon(Icons.Filled.Mic, "עוזר קולי", tint = tint)
    }
}

/** חלון "מקשיב…": מיקרופון פועם, מה שזוהה עד עכשיו, וכפתור ביטול */
@Composable
fun ListeningOverlay(state: ListenState, onCancel: () -> Unit) {
    if (state is ListenState.Idle) return
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onCancel() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .padding(32.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Palette.Card)
                // לחיצה על הכרטיס עצמו לא מבטלת
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (state) {
                is ListenState.Failed -> {
                    Icon(Icons.Filled.ErrorOutline, null, tint = Color(0xFFFF8A80), modifier = Modifier.size(48.dp))
                    Text(state.message, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                }
                is ListenState.Listening -> {
                    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
                        initialValue = 1f,
                        targetValue = 1.25f,
                        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                        label = "scale"
                    )
                    Box(
                        Modifier
                            .size(72.dp)
                            .graphicsLayer { scaleX = pulse; scaleY = pulse }
                            .clip(CircleShape)
                            .background(Palette.Green),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Mic, null, tint = Color.Black, modifier = Modifier.size(36.dp))
                    }
                    Text(
                        state.partial.ifBlank { "מקשיב…" },
                        textAlign = TextAlign.Center,
                        color = if (state.partial.isBlank()) Palette.TextSecondary else Palette.TextPrimary,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                ListenState.Idle -> {}
            }
            TextButton(onClick = onCancel) {
                Text(if (state is ListenState.Failed) "סגור" else "ביטול", color = Palette.TextPrimary)
            }
        }
    }
}

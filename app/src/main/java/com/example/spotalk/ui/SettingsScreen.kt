package com.example.spotalk.ui

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.example.spotalk.log.AppLog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import com.example.spotalk.MainViewModel
import com.example.spotalk.SpotifyConfig
import com.example.spotalk.settings.AppSettings

/** מסך ההגדרות המלא (נפתח מגלגל השיניים במסך הבית) */
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val loggedIn by vm.auth.loggedIn.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = { vm.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה")
            }
            Text("הגדרות", style = MaterialTheme.typography.headlineSmall)
        }

        ClientIdSection(vm, Modifier.padding(horizontal = 16.dp))

        Spacer(Modifier.height(32.dp))
        AppearanceSection(vm, Modifier.padding(horizontal = 16.dp))

        Spacer(Modifier.height(32.dp))
        ArtistMarkingSection(vm, Modifier.padding(horizontal = 16.dp))

        Spacer(Modifier.height(32.dp))
        VoiceSection(vm, Modifier.padding(horizontal = 16.dp))

        Spacer(Modifier.height(32.dp))
        AssistantSection(Modifier.padding(horizontal = 16.dp))

        Spacer(Modifier.height(32.dp))
        GeminiSection(vm, Modifier.padding(horizontal = 16.dp))

        Spacer(Modifier.height(32.dp))
        LogsSection(Modifier.padding(horizontal = 16.dp))

        if (loggedIn) {
            Spacer(Modifier.height(32.dp))
            OutlinedButton(
                onClick = vm::logout,
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Text("התנתקות מ-Spotify", color = Palette.TextPrimary)
            }
        }
        SpotifyAttribution(Modifier.fillMaxWidth().padding(24.dp))
        val ctx = LocalContext.current
        val version = remember {
            runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
        }
        Text(
            "גרסה $version",
            color = Palette.TextMuted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

/**
 * הזנת Client ID + הפרטים שצריך להעתיק לדשבורד.
 * משמש גם במסך ההגדרות וגם במסך ההתחברות.
 */
@Composable
fun ClientIdSection(vm: MainViewModel, modifier: Modifier = Modifier) {
    val saved by vm.settings.clientId.collectAsState()
    var input by rememberSaveable(saved) { mutableStateOf(saved) }
    val valid = AppSettings.isValidClientId(input)
    val changed = input.trim() != saved

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Client ID", style = MaterialTheme.typography.titleMedium)
        Text(
            "מעתיקים אותו מהאפליקציה שיצרת ב-developer.spotify.com/dashboard",
            color = Palette.TextSecondary,
            style = MaterialTheme.typography.bodyMedium
        )

        // Client ID תמיד באנגלית — שדה משמאל לימין
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.trim() },
                singleLine = true,
                placeholder = { Text("32 תווים", color = Palette.TextMuted) },
                isError = input.isNotEmpty() && !valid,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Done
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Palette.Green,
                    unfocusedBorderColor = Palette.TextMuted,
                    cursorColor = Palette.Green
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (input.isNotEmpty() && !valid) {
            Text("Client ID צריך להיות 32 תווים (0-9, a-f)", color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodyMedium)
        }

        Button(
            onClick = { vm.saveClientId(input) },
            enabled = valid && changed,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Green, contentColor = Color.Black),
            shape = RoundedCornerShape(50),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (saved.isBlank()) "שמירה" else "שמירה והתחברות מחדש", fontWeight = FontWeight.Bold)
        }
        if (saved.isNotBlank() && changed) {
            Text("שינוי ה-Client ID ינתק אותך מהחשבון", color = Palette.TextMuted, style = MaterialTheme.typography.labelSmall)
        }

        Spacer(Modifier.height(8.dp))
        Text("להעתקה לדשבורד", style = MaterialTheme.typography.titleMedium)
        CopyRow("Redirect URI", SpotifyConfig.REDIRECT_URI)
        CopyRow("Package name", SpotifyConfig.PACKAGE_NAME)
    }
}

@Composable
private fun CopyRow(label: String, value: String) {
    val clipboard = LocalClipboardManager.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.Highlight, RoundedCornerShape(6.dp))
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(label, color = Palette.TextSecondary, style = MaterialTheme.typography.labelSmall)
            Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        }
        IconButton(onClick = { clipboard.setText(AnnotatedString(value)) }) {
            Icon(Icons.Filled.ContentCopy, "העתקה", tint = Palette.TextSecondary)
        }
    }
}

/** שיתוף / ניקוי קובץ הלוגים */
@Composable
fun LogsSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("לוגים", style = MaterialTheme.typography.titleMedium)
        Text(
            "קובץ עם כל מה שהאפליקציה ו-Spotify SDK עשו — לשליחה לצורך תיקון תקלות",
            color = Palette.TextSecondary,
            style = MaterialTheme.typography.bodyMedium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { shareLogs(context) },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Highlight, contentColor = Palette.TextPrimary),
                shape = RoundedCornerShape(50),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Share, null, modifier = Modifier.padding(end = 6.dp))
                Text("שתף קובץ לוגים")
            }
            OutlinedButton(
                onClick = {
                    AppLog.clear()
                    Toast.makeText(context, "היומן נוקה", Toast.LENGTH_SHORT).show()
                },
                shape = RoundedCornerShape(50)
            ) {
                Text("נקה", color = Palette.TextPrimary)
            }
        }
    }
}

private fun shareLogs(context: Context) {
    try {
        val file = AppLog.exportFile(context)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "שיתוף קובץ הלוגים"))
    } catch (e: Exception) {
        AppLog.e("Logs", "שיתוף הלוגים נכשל", e)
        Toast.makeText(context, "שיתוף נכשל: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

/** הגדרת האפליקציה כעוזר הדיגיטלי של המכשיר (לחיצה ארוכה על כפתור ההפעלה) */
@Composable
private fun AssistantSection(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("עוזר דיגיטלי של המכשיר", style = MaterialTheme.typography.titleMedium)
        Text(
            "אפשר להגדיר את Spotalk כעוזר הדיגיטלי, כך שלחיצה ארוכה על כפתור ההפעלה תפתח את ההקשבה: אומרים שיר והוא מושמע. " +
                "בהגדרות שייפתחו: \"עוזר דיגיטלי\" ← בחר Spotalk.",
            color = Palette.TextSecondary,
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedButton(
            onClick = {
                val intents = listOf(
                    Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
                    Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                )
                var opened = false
                for (i in intents) {
                    try {
                        ctx.startActivity(i)
                        opened = true
                        break
                    } catch (e: ActivityNotFoundException) {
                        // מנסים את הבא
                    }
                }
                if (!opened) Toast.makeText(ctx, "פתח ידנית: הגדרות ← אפליקציות ← אפליקציות ברירת מחדל", Toast.LENGTH_LONG).show()
            },
            shape = RoundedCornerShape(50),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("פתח הגדרות עוזר דיגיטלי", color = Palette.TextPrimary)
        }
    }
}

/** הבנת בקשות קוליות עם AI: מפתח Gemini (אופציונלי) */
@Composable
private fun GeminiSection(vm: MainViewModel, modifier: Modifier = Modifier) {
    val saved by vm.settings.geminiKey.collectAsState()
    var input by rememberSaveable(saved) { mutableStateOf(saved) }
    val changed = input.trim() != saved

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("הבנת הבקשה עם AI (Gemini)", style = MaterialTheme.typography.titleMedium)
        Text(
            "ה-AI מתקן טעויות זיהוי, מבין שמות באנגלית שנאמרו בעברית ומזהה שיר לפי תיאור. " +
                "מפתח API חינמי אפשר לקבל ב-aistudio.google.com/apikey. " +
                "כשיש מפתח, מה שנאמר נשלח ל-Google. בלי מפתח — החיפוש עובד כרגיל.",
            color = Palette.TextSecondary,
            style = MaterialTheme.typography.bodyMedium
        )
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.trim() },
                singleLine = true,
                placeholder = { Text("מפתח API", color = Palette.TextMuted) },
                visualTransformation = PasswordVisualTransformation(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Done
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Palette.Green,
                    unfocusedBorderColor = Palette.TextMuted,
                    cursorColor = Palette.Green
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Button(
            onClick = { vm.settings.setGeminiKey(input) },
            enabled = changed,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Green, contentColor = Color.Black),
            shape = RoundedCornerShape(50),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (input.isBlank() && saved.isNotBlank()) "הסרת המפתח" else "שמירה", fontWeight = FontWeight.Bold)
        }
        if (saved.isNotBlank() && !changed) {
            val status by vm.aiStatus.collectAsState()
            OutlinedButton(
                onClick = vm::testGemini,
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("בדיקת חיבור ל-AI", color = Palette.TextPrimary)
            }
            status?.let {
                Text(
                    it,
                    color = if (it.startsWith("✓")) Palette.Green else Color(0xFFFF8A80),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/** עוזר קולי: שפת הדיבור */
@Composable
private fun VoiceSection(vm: MainViewModel, modifier: Modifier = Modifier) {
    val lang by vm.settings.voiceLanguage.collectAsState()
    val english = lang.startsWith("en")
    val musicApp by vm.settings.musicApp.collectAsState()
    val openSpotify by vm.settings.openSpotify.collectAsState()
    val savedSeconds by vm.settings.listenSeconds.collectAsState()
    var seconds by remember(savedSeconds) { mutableStateOf(savedSeconds.toFloat()) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("עוזר קולי", style = MaterialTheme.typography.titleMedium)
        Text(
            "לחץ על המיקרופון ואמור שם של שיר או אמן, למשל: \"תחפש את השיר ...\". האפליקציה תציג תוצאות, תמתין שנייה ותנגן את הראשון.",
            color = Palette.TextSecondary,
            style = MaterialTheme.typography.bodyMedium
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { vm.settings.setVoiceLanguage(if (english) "he-IL" else "en-US") }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("זיהוי דיבור באנגלית", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "מתאים כשמחפשים שירים בשם באנגלית. כבוי = עברית.",
                    color = Palette.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Switch(
                checked = english,
                onCheckedChange = { vm.settings.setVoiceLanguage(if (it) "en-US" else "he-IL") },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Palette.Green)
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clickable { vm.settings.setMusicApp(if (musicApp == "ytm") "spotify" else "ytm") }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("נגן ב-YouTube Music", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "העוזר הקולי ישלח את הבקשה ל-YouTube Music במקום Spotify. כבוי = Spotify. הוספה לתור נתמכת רק ב-Spotify.",
                    color = Palette.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Switch(
                checked = musicApp == "ytm",
                onCheckedChange = { vm.settings.setMusicApp(if (it) "ytm" else "spotify") },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Palette.Green)
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clickable { vm.settings.setOpenSpotify(!openSpotify) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("פתח את Spotify אחרי הניגון", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "בניגון קולי מהווידג'ט או מכפתור ההפעלה, Spotify נפתחת על השיר. כבוי = הניגון נשאר ברקע.",
                    color = Palette.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Switch(
                checked = openSpotify,
                onCheckedChange = { vm.settings.setOpenSpotify(it) },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Palette.Green)
            )
        }

        Spacer(Modifier.height(8.dp))
        Text("משך ההקשבה: ${seconds.toInt()} שניות", style = MaterialTheme.typography.bodyLarge)
        Text(
            "אחרי הזמן הזה האפליקציה מפסיקה להקשיב ומחפשת, גם אם לא נאמר כלום. אם מפסיקים לדבר קודם — היא מחפשת מיד.",
            color = Palette.TextSecondary,
            style = MaterialTheme.typography.bodyMedium
        )
        Slider(
            value = seconds,
            onValueChange = { seconds = it },
            onValueChangeFinished = { vm.settings.setListenSeconds(seconds.toInt()) },
            valueRange = 1f..10f,
            steps = 8,
            colors = SliderDefaults.colors(
                thumbColor = Palette.Green,
                activeTrackColor = Palette.Green,
                inactiveTrackColor = Palette.Highlight
            )
        )
    }
}

/** תצוגה: כל העטיפות באותו צבע */
@Composable
private fun AppearanceSection(vm: MainViewModel, modifier: Modifier = Modifier) {
    val uniform by vm.settings.uniformTiles.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("תצוגה", style = MaterialTheme.typography.titleMedium)
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { vm.setUniformTiles(!uniform) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("כל העטיפות באותו צבע", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "במקום צבע שונה לכל אלבום, פלייליסט ואמן",
                    color = Palette.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Switch(
                checked = uniform,
                onCheckedChange = { vm.setUniformTiles(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Palette.Green
                )
            )
        }
    }
}

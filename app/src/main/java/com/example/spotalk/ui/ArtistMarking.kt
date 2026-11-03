package com.example.spotalk.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.spotalk.MainViewModel
import com.example.spotalk.api.Item
import com.example.spotalk.gender.Gender
import kotlinx.coroutines.delay

/**
 * סימון ידני של אמן כגבר או כאישה (קובע אם מציגים את התמונות שלו במצב \"רק זמרים גברים\").
 * נמצא רק בהגדרות, ונפתח רק אחרי הקשת קוד.
 */
@Composable
fun ArtistMarkingSection(vm: MainViewModel, modifier: Modifier = Modifier) {
    // נפתח רק לזמן שהמסך פתוח — ביציאה מההגדרות ננעל שוב
    var unlocked by remember { mutableStateOf(false) }
    var askCode by remember { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("תמונות וסימון אמנים", style = MaterialTheme.typography.titleMedium)
        if (!unlocked) {
            Text(
                "בחירה אם להציג תמונות, רק של זמרים גברים או בלי תמונות, וסימון ידני של אמנים כגברים או כנשים. מוגן בקוד.",
                color = Palette.TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedButton(onClick = { askCode = true }, shape = RoundedCornerShape(50)) {
                Icon(Icons.Filled.Lock, null, tint = Palette.TextPrimary, modifier = Modifier.padding(end = 6.dp))
                Text(if (vm.settings.hasCode) "פתח עם קוד" else "קבע קוד", color = Palette.TextPrimary)
            }
        } else {
            UnlockedMarking(vm, onLock = { unlocked = false })
        }
    }

    if (askCode) {
        CodeDialog(
            isNew = !vm.settings.hasCode,
            check = { vm.settings.checkCode(it) },
            onSet = { vm.settings.setCode(it) },
            onDone = { ok ->
                askCode = false
                if (ok) unlocked = true
            }
        )
    }
}

@Composable
private fun UnlockedMarking(vm: MainViewModel, onLock: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<Load<List<Item>>?>(null) }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            results = null
            return@LaunchedEffect
        }
        delay(600)
        results = Load.Loading
        results = try {
            Load.Done(vm.api.searchArtists(q))
        } catch (e: Exception) {
            Load.Failed(e.message ?: "שגיאה בחיפוש")
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.LockOpen, null, tint = Palette.Green)
        Spacer(Modifier.width(8.dp))
        Text("פתוח", color = Palette.Green, modifier = Modifier.weight(1f))
        TextButton(onClick = onLock) { Text("נעל", color = Palette.TextSecondary) }
    }

    ImageModePicker(vm)

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        singleLine = true,
        placeholder = { Text("חפש אמן לסימון", color = Palette.TextMuted) },
        leadingIcon = { Icon(Icons.Filled.Search, null, tint = Palette.TextSecondary) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.Green,
            unfocusedBorderColor = Palette.TextMuted,
            cursorColor = Palette.Green
        ),
        modifier = Modifier.fillMaxWidth()
    )

    when (val r = results) {
        null -> Unit
        is Load.Loading -> Text("מחפש…", color = Palette.TextSecondary)
        is Load.Failed -> Text(r.message, color = Color(0xFFFF8A80))
        is Load.Done -> r.value.forEach { a -> MarkRow(vm, a.id, a.title, a) }
    }

    val marked = vm.genders.manualList()
    if (marked.isNotEmpty()) {
        Text(
            "סומנו ידנית (${marked.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp)
        )
        marked.forEach { (id, name, _) -> MarkRow(vm, id, name, null) }
    }
}

/** בחירת מצב התמונות: כל התמונות / רק זמרים גברים / בלי תמונות (נמצא רק באזור המוגן בקוד) */
@Composable
private fun ImageModePicker(vm: MainViewModel) {
    val imageMode by vm.settings.imageMode.collectAsState()
    Text("הצגת תמונות", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
    listOf(
        Triple("all", "כל התמונות", "תמונות של אמנים, אלבומים ופלייליסטים."),
        Triple("male", "רק זמרים גברים", "תמונה מוצגת רק כשכל האמנים זוהו כגברים (אוטומטית או לפי הסימון הידני). זמרות, להקות מעורבות ולא ידוע — בלי תמונה."),
        Triple("none", "בלי תמונות בכלל", "משבצות צבע עם אייקון בלבד.")
    ).forEach { (mode, title, desc) ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { vm.setImageMode(mode) }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.RadioButton(
                selected = imageMode == mode,
                onClick = { vm.setImageMode(mode) },
                colors = androidx.compose.material3.RadioButtonDefaults.colors(
                    selectedColor = Palette.Green,
                    unselectedColor = Palette.TextMuted
                )
            )
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(desc, color = Palette.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    Text("סימון אמנים", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
}

/** שורה של אמן: מה זוהה, וכפתורי "גבר" / "אישה" / "בטל" */
@Composable
private fun MarkRow(vm: MainViewModel, id: String, name: String, artist: Item?) {
    if (artist != null) LaunchedEffect(id) { vm.genders.request(listOf(id)) }
    val manual = vm.genders.manualOf(id)
    val detected = vm.genders.genderOf(id)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(
                iconFor(com.example.spotalk.api.ItemType.ARTIST), name, 44.dp, CircleShape,
                imageUrl = artist?.imageUrl, artistIds = listOf(id)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val isMale = manual == Gender.MALE || (manual == null && detected == Gender.MALE)
                Text(
                    when {
                        manual == Gender.MALE -> "סומן ידנית כגבר"
                        manual == Gender.FEMALE -> "סומנה ידנית כאישה"
                        detected == Gender.MALE -> "זוהה כגבר"
                        detected == Gender.FEMALE -> "זוהתה כאישה"
                        detected == null -> "בבדיקה…"
                        else -> "לא זוהה"
                    },
                    color = if (isMale) Palette.Green else Palette.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GenderButton("גבר", selected = manual == Gender.MALE) { vm.genders.setManual(id, Gender.MALE, name) }
            GenderButton("אישה", selected = manual == Gender.FEMALE) { vm.genders.setManual(id, Gender.FEMALE, name) }
            if (manual != null) {
                TextButton(onClick = { vm.genders.setManual(id, null) }) {
                    Text("בטל סימון", color = Palette.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun GenderButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Palette.Green else Palette.Highlight,
            contentColor = if (selected) Color.Black else Palette.TextPrimary
        ),
        shape = RoundedCornerShape(50)
    ) { Text(label, fontWeight = FontWeight.Bold) }
}

/** חלון הקשת קוד. בפעם הראשונה — קביעת קוד חדש (פעמיים לאימות) */
@Composable
private fun CodeDialog(
    isNew: Boolean,
    check: (String) -> Boolean,
    onSet: (String) -> Unit,
    onDone: (Boolean) -> Unit
) {
    var code by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { onDone(false) },
        containerColor = Palette.Elevated,
        title = { Text(if (isNew) "קביעת קוד" else "הקש קוד") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isNew) {
                    Text(
                        "לפחות 4 ספרות. אם תשכח אותו, אין דרך לשחזר — רק מחיקת נתוני האפליקציה.",
                        color = Palette.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                CodeField(code, if (isNew) "קוד חדש" else "קוד") { code = it; error = null }
                if (isNew) CodeField(confirm, "שוב, לאימות") { confirm = it; error = null }
                error?.let { Text(it, color = Color(0xFFFF8A80)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    code.length < 4 -> error = "לפחות 4 ספרות"
                    isNew && code != confirm -> error = "הקודים לא זהים"
                    isNew -> {
                        onSet(code)
                        onDone(true)
                    }
                    check(code) -> onDone(true)
                    else -> {
                        error = "קוד שגוי"
                        code = ""
                    }
                }
            }) { Text("אישור", color = Palette.Green, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = { onDone(false) }) { Text("ביטול", color = Palette.TextSecondary) }
        }
    )
}

@Composable
private fun CodeField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(12)) },
        singleLine = true,
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.Green,
            unfocusedBorderColor = Palette.TextMuted,
            cursorColor = Palette.Green
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

package com.example.spotalk.widget

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.example.spotalk.TextPlayApp
import com.example.spotalk.ui.ListeningOverlay
import com.example.spotalk.ui.TextPlayTheme
import com.example.spotalk.voice.ListenState
import com.example.spotalk.voice.VoiceController
import kotlinx.coroutines.delay

/**
 * מסך שקוף — נפתח מהווידג'ט במסך הבית.
 * מציג חלון "מקשיב…" ומקשיב עד שמפסיקים לדבר (בלי ללחוץ על כלום),
 * ואז מחפש ומנגן מהרקע (BackgroundVoicePlayer) בלי לפתוח את האפליקציה.
 */
class VoiceActivity : ComponentActivity() {

    private lateinit var voice: VoiceController

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            begin()
        } else {
            Toast.makeText(this, "צריך הרשאת מיקרופון כדי לדבר עם העוזר", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // עובד גם במסך נעילה: מוצג מעל הנעילה בלי לדרוש פתיחה
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        voice = VoiceController(applicationContext)

        setContent {
            TextPlayTheme {
                val state by voice.state.collectAsState()
                // שגיאה (למשל "לא שמעתי") — מציגים רגע וסוגרים
                LaunchedEffect(state) {
                    if (state is ListenState.Failed) {
                        delay(2500)
                        finish()
                    }
                }
                ListeningOverlay(state) { finish() }
            }
        }

        if (savedInstanceState != null) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) begin() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun begin() {
        val settings = (application as TextPlayApp).settings
        voice.start(settings.voiceLanguage.value, settings.listenSeconds.value) { heard ->
            // מחפשים ומנגנים מהרקע — בלי לפתוח את האפליקציה
            // המסך נשאר פתוח (שקוף) עד הסיום — רק ממנו מותר לפתוח את Spotify
            BackgroundVoicePlayer.handle(applicationContext, heard, this) { finish() }
        }
    }

    override fun onStop() {
        super.onStop()
        // יצאו מהמסך באמצע הקשבה (למשל Home) — מפסיקים ולא משאירים מסך שקוף
        if (voice.state.value is ListenState.Listening) {
            voice.stop()
            finish()
        }
    }

    override fun onDestroy() {
        voice.stop()
        super.onDestroy()
    }
}

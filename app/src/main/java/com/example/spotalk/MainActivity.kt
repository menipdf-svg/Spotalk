package com.example.spotalk

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import com.example.spotalk.log.AppLog
import com.example.spotalk.ui.AppRoot
import com.example.spotalk.ui.TextPlayTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            TextPlayTheme {
                AppRoot(vm, onLogin = ::startLogin)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        AppLog.d("Activity", "onStart loggedIn=${vm.auth.loggedIn.value}")
        vm.player.attach(this)
        if (vm.auth.loggedIn.value) vm.player.connect()
    }

    override fun onStop() {
        super.onStop()
        AppLog.d("Activity", "onStop")
        vm.voice.stop()
        vm.player.onBackground()
    }

    private fun startLogin() {
        AppLog.i("Activity", "פותח דף התחברות")
        CustomTabsIntent.Builder().build().launchUrl(this, vm.auth.buildAuthorizeUri())
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        // פקודה קולית שהגיעה מהווידג'ט במסך הבית
        intent.getStringExtra(EXTRA_SPOKEN)?.let { spoken ->
            val alternatives = intent.getStringArrayListExtra(EXTRA_ALTERNATIVES).orEmpty()
            intent.removeExtra(EXTRA_SPOKEN)
            intent.removeExtra(EXTRA_ALTERNATIVES)
            vm.handleVoice(spoken, alternatives)
        }
        val data = intent.data ?: return
        if (data.scheme == "spotalk" && data.host == "callback") {
            vm.handleRedirect(data)
        }
    }

    companion object {
        /** הטקסט שנאמר בווידג'ט — מועבר לאפליקציה לחיפוש / ניגון */
        const val EXTRA_SPOKEN = "spoken_text"

        /** החלופות האחרות שזיהה מזהה הדיבור (עוזרות ל-AI) */
        const val EXTRA_ALTERNATIVES = "spoken_alternatives"
    }
}

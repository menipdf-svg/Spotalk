package com.example.spotalk.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.example.spotalk.log.AppLog
import com.example.spotalk.widget.VoiceActivity

class TextPlaySessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = TextPlaySession(this)
}

/**
 * נוצר כשהמשתמש מפעיל את העוזר (לחיצה ארוכה על כפתור ההפעלה).
 * אין חלון משלו — פותח את מסך ההקשבה (VoiceActivity), שמקשיב, מחפש ומנגן.
 */
class TextPlaySession(context: Context) : VoiceInteractionSession(context) {

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        AppLog.i("Assistant", "העוזר הופעל (flags=$showFlags)")
        startAssistantActivity(Intent(context, VoiceActivity::class.java))
    }
}

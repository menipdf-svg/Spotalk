package com.example.spotalk.assistant

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.example.spotalk.log.AppLog

/**
 * אנדרואיד דורשת שכל עוזר דיגיטלי יהיה גם שירות זיהוי דיבור, ובעת בחירת עוזר
 * המערכת עשויה להגדיר אותו כשירות הזיהוי ברירת המחדל של המכשיר.
 * כדי לא לשבור הקלדה קולית באפליקציות אחרות, השירות הזה פשוט מעביר
 * את הבקשות לשירות הדיבור של Google.
 */
class ForwardingRecognitionService : RecognitionService() {

    private var delegate: SpeechRecognizer? = null

    private val google = ComponentName(
        "com.google.android.googlequicksearchbox",
        "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
    )

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        release()
        val r = try {
            SpeechRecognizer.createSpeechRecognizer(applicationContext, google)
        } catch (e: Exception) {
            AppLog.w("Assistant", "שירות הדיבור של Google לא זמין: ${e.message}")
            listener.error(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        delegate = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.readyForSpeech(params ?: Bundle())
            override fun onBeginningOfSpeech() = listener.beginningOfSpeech()
            override fun onRmsChanged(rmsdB: Float) = listener.rmsChanged(rmsdB)
            override fun onBufferReceived(buffer: ByteArray?) = listener.bufferReceived(buffer ?: ByteArray(0))
            override fun onEndOfSpeech() = listener.endOfSpeech()
            override fun onError(error: Int) = listener.error(error)
            override fun onResults(results: Bundle?) = listener.results(results ?: Bundle())
            override fun onPartialResults(partialResults: Bundle?) = listener.partialResults(partialResults ?: Bundle())
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(recognizerIntent))
    }

    override fun onStopListening(listener: Callback) {
        delegate?.stopListening()
    }

    override fun onCancel(listener: Callback) {
        release()
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        delegate?.let {
            delegate = null
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
    }
}

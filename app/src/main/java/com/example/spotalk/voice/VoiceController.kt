package com.example.spotalk.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.spotalk.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** מצב ההקשבה, להצגה בחלון "מקשיב…" */
sealed interface ListenState {
    data object Idle : ListenState
    data class Listening(val partial: String = "") : ListenState
    data class Failed(val message: String) : ListenState
}

/**
 * הקשבה מובנית (SpeechRecognizer) במקום חלון Google:
 * ההקשבה מסתיימת לבד כשמפסיקים לדבר, בלי ללחוץ על כלום.
 * דורש הרשאת מיקרופון (RECORD_AUDIO). לקרוא מה-thread הראשי בלבד.
 */
class VoiceController(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    private val _state = MutableStateFlow<ListenState>(ListenState.Idle)
    val state: StateFlow<ListenState> = _state

    /** האם השירות הגיב בכלל (onReadyForSpeech) */
    private var ready = false

    private var callback: ((List<String>) -> Unit)? = null

    /** כמה זמן מקשיבים לכל היותר (מההגדרות) */
    private var listenMs = 3000L

    /** הטקסט האחרון שזוהה תוך כדי דיבור — משמש אם הסיום הכפוי לא מחזיר תוצאה סופית */
    private var lastPartial = ""

    /** סיום כפוי: אם ההקשבה לא נגמרה לבד, מנצלים את מה שזוהה או מדווחים "לא שמעתי" */
    private val forceResult = Runnable {
        if (recognizer == null) return@Runnable
        val partial = lastPartial
        if (partial.isNotBlank()) deliver(listOf(partial)) else fail("לא שמעתי — נסה שוב")
    }

    /** אחרי listenMs מסיימים הקשבה ומחפשים — גם אם לא נאמר כלום או שהמזהה לא סיים */
    private val hardTimeout = Runnable {
        val r = recognizer ?: return@Runnable
        if (_state.value !is ListenState.Listening) return@Runnable
        AppLog.d("Voice", "עברו ${listenMs / 1000} שניות — מסיימים הקשבה")
        runCatching { r.stopListening() }
        main.postDelayed(forceResult, 1500)
    }

    private fun deliver(heard: List<String>) {
        val cb = callback
        stop()
        cb?.invoke(heard)
    }

    /** אם השירות לא מגיב תוך 5 שניות — מפסיקים לחכות ומסבירים */
    private val readyTimeout = Runnable {
        if (!ready && _state.value is ListenState.Listening) {
            AppLog.w("Voice", "שירות הדיבור לא הגיב (אין onReadyForSpeech)")
            fail("שירות הזיהוי לא מגיב — נסה שוב")
        }
    }

    /**
     * מעדיפים במפורש את שירות הדיבור של Google (בטלפונים מסוימים ברירת המחדל
     * היא שירות אחר שלא מזהה עברית ופשוט "שותק"). אם הוא לא מותקן — ברירת המחדל.
     */
    private fun createRecognizer(): SpeechRecognizer {
        val google = ComponentName(
            "com.google.android.googlequicksearchbox",
            "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
        )
        val installed = runCatching { context.packageManager.getServiceInfo(google, 0) }.isSuccess
        if (installed) {
            try {
                AppLog.i("Voice", "מקשיב עם שירות הדיבור של Google")
                return SpeechRecognizer.createSpeechRecognizer(context, google)
            } catch (e: Exception) {
                AppLog.w("Voice", "יצירת זיהוי Google נכשלה: ${e.message}")
            }
        }
        AppLog.i("Voice", "מקשיב עם שירות הדיבור שברירת המחדל במכשיר")
        return SpeechRecognizer.createSpeechRecognizer(context)
    }

    private val resetFailure = Runnable {
        if (_state.value is ListenState.Failed) _state.value = ListenState.Idle
    }

    /** מתחיל להקשיב. onResult מקבל את החלופות שזוהו (הראשונה היא הסבירה ביותר) */
    fun start(language: String, listenSeconds: Int, onResult: (List<String>) -> Unit) {
        stop()
        callback = onResult
        lastPartial = ""
        listenMs = listenSeconds.coerceIn(1, 10) * 1000L
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            fail("אין במכשיר שירות זיהוי דיבור")
            return
        }
        val r = createRecognizer()
        recognizer = r
        ready = false
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (recognizer !== r) return
                ready = true
                main.removeCallbacks(readyTimeout)
                main.removeCallbacks(hardTimeout)
                main.postDelayed(hardTimeout, listenMs)
                AppLog.d("Voice", "השירות מוכן — אפשר לדבר")
                _state.value = ListenState.Listening()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotBlank() && recognizer === r) {
                    lastPartial = text
                    _state.value = ListenState.Listening(text)
                }
            }

            override fun onResults(results: Bundle?) {
                if (recognizer !== r) return
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty().filter { it.isNotBlank() }
                AppLog.d("Voice", "זוהה: $heard")
                when {
                    heard.isNotEmpty() -> deliver(heard)
                    lastPartial.isNotBlank() -> deliver(listOf(lastPartial))
                    else -> fail("לא שמעתי — נסה שוב")
                }
            }

            override fun onError(error: Int) {
                if (recognizer !== r) return
                AppLog.w("Voice", "שגיאת זיהוי דיבור: $error")
                val noSpeech = error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                if (noSpeech && lastPartial.isNotBlank()) {
                    deliver(listOf(lastPartial))
                    return
                }
                fail(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "לא שמעתי — נסה שוב (קוד $error)"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "אין הרשאת מיקרופון"
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "אין רשת לזיהוי הדיבור (קוד $error)"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "שירות הדיבור תפוס — נסה שוב (קוד $error)"
                        else -> "שגיאה בזיהוי הדיבור (קוד $error)"
                    }
                )
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        _state.value = ListenState.Listening()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        r.startListening(intent)
        main.postDelayed(readyTimeout, 5000)
    }

    /** מפסיק להקשיב ומנקה */
    fun stop() {
        main.removeCallbacks(resetFailure)
        main.removeCallbacks(readyTimeout)
        main.removeCallbacks(hardTimeout)
        main.removeCallbacks(forceResult)
        callback = null
        recognizer?.let {
            recognizer = null
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        _state.value = ListenState.Idle
    }

    private fun fail(message: String) {
        stop()
        _state.value = ListenState.Failed(message)
        main.postDelayed(resetFailure, 2500)
    }
}

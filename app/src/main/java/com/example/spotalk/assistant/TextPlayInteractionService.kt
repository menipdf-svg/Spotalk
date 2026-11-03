package com.example.spotalk.assistant

import android.service.voice.VoiceInteractionService

/**
 * הצהרה שהאפליקציה יכולה להיות "העוזר הדיגיטלי" של המכשיר.
 * אחרי שבוחרים אותה בהגדרות (אפליקציות ← ברירות מחדל ← עוזר דיגיטלי),
 * לחיצה ארוכה על כפתור ההפעלה / ההום פותחת את העוזר הקולי.
 */
class TextPlayInteractionService : VoiceInteractionService()

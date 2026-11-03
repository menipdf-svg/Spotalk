package com.example.spotalk.voice

/** מה לעשות עם מה שנאמר: רק לחפש ולהציג תוצאות, או גם לנגן את התוצאה הראשונה */
enum class VoiceAction { SEARCH, PLAY, QUEUE }

data class VoiceCommand(val action: VoiceAction, val query: String, val isPlaylist: Boolean = false)

/**
 * מפענח פקודה קולית פשוטה לעברית ולאנגלית:
 *
 *  - "תחפש את השיר X" / "חפש X" / "תמצא לי את X" / "search for X"  ← חיפוש והצגת תוצאות
 *  - "תנגן את X" / "נגן X" / "תשמיע לי X" / "play X"               ← חיפוש וניגון התוצאה הראשונה
 *  - בלי פועל ("X" בלבד)                                           ← חיפוש
 *
 * מילות קישור כמו "לי", "את", "השיר", "בשם", "של" ו-"בספוטיפיי" מוסרות.
 */
object VoiceCommandParser {

    private val ic = RegexOption.IGNORE_CASE

    private val playVerb = Regex(
        "^(?:בבקשה\\s+)?(?:ת?נגן|ת?שמיע|השמע|play|put\\s+on)(?:\\s+|$)", ic
    )
    private val searchVerb = Regex(
        "^(?:בבקשה\\s+)?(?:ת?חפש|ת?מצא|find|search(?:\\s+for)?|look\\s+up)(?:\\s+|$)", ic
    )

    /** "תוסיף לתור X" / "הוסף את X לתור" / "add X to queue" / "queue X" */
    private val queuePrefix = Regex(
        "^(?:בבקשה\\s+)?(?:(?:ת?וסיף|הוסף|שים|תשים)(?:\\s+לי)?\\s+(?:ל|ב)?תור(?:\\s+ההשמעה)?|add(?:\\s+to\\s+(?:the\\s+)?queue)?|queue(?:\\s+up)?)(?:\\s+|$)", ic
    )
    private val queueVerbOnly = Regex("^(?:בבקשה\\s+)?(?:ת?וסיף|הוסף|שים|תשים)(?:\\s+לי)?\\s+", ic)
    private val queueTail = Regex("\\s+(?:ל|ב)?תור(?:\\s+ההשמעה)?$|\\s+to\\s+(?:the\\s+)?queue$", ic)

    /** מילות קישור שבאות אחרי הפועל (פעם אחת מכל סוג, לפי הסדר) */
    private val hebrewLead = Regex("^(?:לי\\s+)?(?:את\\s+)?(?:ה?שיר\\s+)?(?:משהו\\s+)?(?:בשם\\s+)?(?:של\\s+)?")
    private val englishLead = Regex("^(?:me\\s+)?(?:the\\s+)?(?:song\\s+)?(?:called\\s+|named\\s+)?(?:by\\s+)?", ic)

    /** "פלייליסט X" / "רשימת השמעה X" / "playlist X" — מנגנים פלייליסט שלם */
    private val hebrewPlaylist = Regex("^ה?(?:פלייליסט|פליילסיט|פלי\\s?ליסט|פליליסט|רשימת\\s+השמעה)(?:\\s+)?(?:בשם\\s+)?(?:של\\s+)?")
    private val englishPlaylist = Regex("^(?:the\\s+)?(?:my\\s+)?playlist(?:\\s+)?(?:called\\s+|named\\s+)?", ic)

    private val tail = Regex("\\s+(?:בספוטיפיי|בספוטיפי|ב-?spotify|on\\s+spotify)$", ic)

    private val spaces = Regex("\\s+")

    fun parse(spoken: String): VoiceCommand? {
        var text = spoken.trim().trim('.', ',', '!', '?', '"', '\'').replace(spaces, " ").trim()
        if (text.isEmpty()) return null

        var action = VoiceAction.SEARCH

        // הוספה לתור: "תוסיף לתור X" או "תוסיף את X לתור"
        val q = queuePrefix.find(text)
        val qv = if (q == null && queueTail.containsMatchIn(text)) queueVerbOnly.find(text) else null
        if (q != null || qv != null) {
            val m = q ?: qv!!
            text = text.substring(m.range.last + 1).trimStart()
            text = queueTail.replaceFirst(text, "")
            text = hebrewLead.replaceFirst(text, "")
            text = englishLead.replaceFirst(text, "")
            text = tail.replaceFirst(text, "").trim()
            return if (text.isEmpty()) null else VoiceCommand(VoiceAction.QUEUE, text)
        }

        val play = playVerb.find(text)
        val search = if (play == null) searchVerb.find(text) else null

        if (play != null || search != null) {
            val verb = play ?: search!!
            if (play != null) action = VoiceAction.PLAY
            text = text.substring(verb.range.last + 1).trimStart()
            text = hebrewLead.replaceFirst(text, "")
            text = englishLead.replaceFirst(text, "")
        }
        text = tail.replaceFirst(text, "").trim()

        // "תנגן את הפלייליסט X" — אחרי הסרת "את" נשאר "הפלייליסט X"
        val pl = hebrewPlaylist.find(text) ?: englishPlaylist.find(text)
        if (pl != null && action == VoiceAction.PLAY) {
            val name = text.substring(pl.range.last + 1).trim()
            if (name.isNotEmpty()) return VoiceCommand(action, name, isPlaylist = true)
        }

        return if (text.isEmpty()) null else VoiceCommand(action, text)
    }
}

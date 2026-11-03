package com.example.spotalk

object SpotifyConfig {
    /** חייב להיות רשום בדיוק כך ב-Redirect URIs של האפליקציה בדשבורד */
    const val REDIRECT_URI = "spotalk://callback"

    /** שם החבילה שצריך לרשום בהגדרות ה-Android בדשבורד */
    const val PACKAGE_NAME = "com.example.spotalk"

    /**
     * app-remote-control: מאשר מראש ל-App Remote לשלוט בנגן.
     * בלעדיו Spotify צריכה להציג חלון אישור משלה — ובגרסאות החדשות החיבור נתקע שם.
     */
    const val REMOTE_SCOPE = "app-remote-control"

    val SCOPES = listOf(
        REMOTE_SCOPE,
        "user-library-read",
        "user-read-recently-played",
        "user-follow-read",
        "playlist-read-private",
        "playlist-read-collaborative",
        "playlist-modify-public",
        "playlist-modify-private",
        "user-follow-modify",
        "user-library-modify",
        "user-read-playback-state",
        "user-read-currently-playing",
        "user-modify-playback-state"
    )

    /**
     * מעלים את המספר כל פעם שמוסיפים הרשאה חדשה.
     * מי שהתחבר עם גרסה ישנה יותר יתבקש להתחבר מחדש פעם אחת.
     */
    const val SCOPE_VERSION = 5
}

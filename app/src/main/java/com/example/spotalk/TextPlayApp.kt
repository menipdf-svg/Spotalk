package com.example.spotalk

import android.app.Application
import com.example.spotalk.gender.GenderRepo
import com.example.spotalk.log.AppLog
import com.example.spotalk.remote.RemotePlayer
import com.example.spotalk.settings.AppSettings
import com.example.spotalk.ui.Images
import com.spotify.android.appremote.api.SpotifyAppRemote

class TextPlayApp : Application() {

    /** הגדרות ונגן משותפים — גם למסכים וגם לשירות של כרטיס "מתנגן עכשיו" */
    val settings by lazy { AppSettings(this) }
    val genders by lazy { GenderRepo(this) }
    val player by lazy { RemotePlayer(this, settings) }

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        Images.genders = genders
        Images.apply(settings.imageMode.value)
        // הודעות פנימיות מפורטות של Spotify SDK — נכנסות לקובץ הלוגים דרך logcat
        SpotifyAppRemote.setDebugMode(true)
    }
}

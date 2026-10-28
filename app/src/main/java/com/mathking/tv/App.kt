package com.mathking.tv

import android.app.Application
import android.content.Context
import com.mathking.tv.audio.Sfx
import com.mathking.tv.audio.Speaker
import com.mathking.tv.data.Repo
import com.mathking.tv.data.Settings

class App : Application() {

    lateinit var repo: Repo
        private set

    lateinit var settings: Settings
        private set

    lateinit var speaker: Speaker
        private set

    override fun onCreate() {
        super.onCreate()
        repo = Repo(this)
        settings = Settings(this)
        speaker = Speaker(this)
        speaker.init()
        Sfx.enabled = settings.soundOn
        speaker.enabled = settings.speakOn
    }

    override fun onTerminate() {
        speaker.shutdown()
        super.onTerminate()
    }

    companion object {
        fun of(context: Context): App = context.applicationContext as App
    }
}

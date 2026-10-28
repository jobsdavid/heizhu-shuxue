package com.mathking.tv.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 读题。优先用内置语音包（电视上基本没有中文 TTS 引擎），
 * 没有语音包时才回退系统 TTS，再不行就静默——屏幕大字照常显示，不影响答题。
 */
class Speaker(context: Context) {

    private val appContext = context.applicationContext
    private val pack = VoicePack(appContext)
    private val usePack: Boolean = pack.available

    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    @Volatile
    private var langOk = false

    @Volatile
    var enabled: Boolean = true

    private var pendingText: String? = null
    private var pendingRate: Float = 1f

    fun init() {
        if (usePack || tts != null) return
        tts = TextToSpeech(appContext) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            val engine = tts ?: return@TextToSpeech
            val r = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
            langOk = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            if (!langOk) {
                val r2 = engine.setLanguage(Locale.CHINA)
                langOk = r2 != TextToSpeech.LANG_MISSING_DATA && r2 != TextToSpeech.LANG_NOT_SUPPORTED
            }
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {}

                @Deprecated("deprecated in API 21")
                override fun onError(utteranceId: String?) {}
            })
            ready = true
            val text = pendingText
            if (text != null) {
                pendingText = null
                speak(text, pendingRate)
            }
        }
    }

    /** rate 已包含模式语速与家长设置的整体倍率 */
    fun speak(text: String, rate: Float) {
        if (!enabled || text.isBlank()) return
        if (usePack) {
            pack.speak(text, rate.coerceIn(0.5f, 2.0f))
            return
        }
        val engine = tts
        if (engine == null || !ready) {
            pendingText = text
            pendingRate = rate
            return
        }
        if (!langOk) return
        engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "q${System.currentTimeMillis()}")
    }

    fun stop() {
        pack.stop()
        try {
            tts?.stop()
        } catch (_: Throwable) {
        }
    }

    fun shutdown() {
        stop()
        try {
            tts?.shutdown()
        } catch (_: Throwable) {
        }
        tts = null
        ready = false
        langOk = false
    }
}

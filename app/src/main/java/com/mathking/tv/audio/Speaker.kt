package com.mathking.tv.audio

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 读题。
 *
 * 优先用内置语音包（电视上基本没有中文 TTS 引擎），没有语音包时才回退系统 TTS，
 * 再不行就静默——屏幕大字照常显示，不影响答题。
 *
 * [speak] 的第三个参数是「这段说完之后做什么」：答对/答错时界面要等语音播完再换题，
 * 否则「回答正确」永远读一半就被下一题的朗读打断（这是老版本听着别扭的主因之一）。
 * 回调保证只触发一次，被新语音顶掉或被 [stop] 打断时不再触发。
 */
class Speaker(context: Context) {

    private val appContext = context.applicationContext
    private val pack = VoicePack(appContext)
    private val usePack: Boolean = pack.available
    private val main = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    @Volatile
    private var langOk = false

    @Volatile
    var enabled: Boolean = true

    private var pendingText: String? = null
    private var pendingRate: Float = 1f

    private var doneAction: (() -> Unit)? = null
    private var doneTask: Runnable? = null

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
                override fun onDone(utteranceId: String?) {
                    main.post { fireDone() }
                }

                @Deprecated("deprecated in API 21")
                override fun onError(utteranceId: String?) {
                    main.post { fireDone() }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    main.post { fireDone() }
                }
            })
            ready = true
            val text = pendingText
            if (text != null) {
                pendingText = null
                speak(text, pendingRate)
            }
        }
    }

    val isSpeaking: Boolean get() = if (usePack) pack.isPlaying else false

    val isPaused: Boolean get() = if (usePack) pack.isPaused else false

    /** rate 已包含模式语速与家长设置的整体倍率 */
    fun speak(text: String, rate: Float) = speak(text, rate, null)

    fun speak(text: String, rate: Float, then: (() -> Unit)?) {
        cancelDone()
        if (text.isBlank() || !enabled) {
            // 没有语音也要让流程继续：立刻执行后继动作
            if (then != null) main.post(then)
            return
        }
        doneAction = then
        if (then != null) watchdog()
        if (usePack) {
            pack.onComplete = { main.post { fireDone() } }
            if (!pack.speak(text, rate.coerceIn(0.5f, 2.0f))) fireDone()
            return
        }
        val engine = tts
        if (engine == null || !ready) {
            pendingText = text
            pendingRate = rate
            return
        }
        if (!langOk) {
            fireDone()
            return
        }
        engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "q${System.currentTimeMillis()}")
    }

    /** 暂停：返回 true = 确实暂停了一段正在播的语音 */
    fun pause(): Boolean = if (usePack) pack.pause() else false

    fun resume(): Boolean = if (usePack) pack.resume() else false

    fun stop() {
        cancelDone()
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

    // ------------------------------------------------------------ 说完之后

    private fun watchdog() {
        val task = Runnable { fireDone() }
        doneTask = task
        main.postDelayed(task, WATCHDOG_MS)
    }

    private fun fireDone() {
        val action = doneAction ?: return
        cancelDone()
        action.invoke()
    }

    private fun cancelDone() {
        doneTask?.let { main.removeCallbacks(it) }
        doneTask = null
        doneAction = null
        pack.onComplete = null
    }

    companion object {
        /** 万一播放器没回调，最多等这么久也要让界面继续 */
        private const val WATCHDOG_MS = 12_000L
    }
}

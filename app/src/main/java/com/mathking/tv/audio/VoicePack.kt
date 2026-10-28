package com.mathking.tv.audio

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.util.Log

/**
 * 内置语音包：电视上基本都没有中文 TTS 引擎（实测小米电视 tts_default_synth 为空），
 * 所以用 assets 里的单字/短语音频拼读题目，不依赖系统语音、不依赖网络。
 *
 * 语速用 MediaPlayer 的 PlaybackParams 调（练习慢、限时快），音高保持不变。
 */
class VoicePack(context: Context) {

    private val appContext = context.applicationContext

    private val fileOf = mapOf(
        "零" to "ling", "一" to "yi", "二" to "er", "三" to "san", "四" to "si",
        "五" to "wu", "六" to "liu", "七" to "qi", "八" to "ba", "九" to "jiu",
        "十" to "shi", "百" to "bai",
        "加" to "jia", "减" to "jian", "乘" to "cheng", "除以" to "chuyi",
        "等于几" to "dengyu", "分之" to "fenzhi",
        "回答正确" to "huida_zq", "回答错误" to "huida_cw",
        "再算一遍" to "zaisuan", "这道题有错误" to "cuoti",
        "正确答案是几" to "zhengque", "时间到" to "shijian"
    )

    private val words = listOf(
        "这道题有错误", "正确答案是几", "回答正确", "回答错误",
        "再算一遍", "等于几", "除以", "分之"
    )

    private val queue = ArrayDeque<Pair<String, Float>>()
    private var current: MediaPlayer? = null

    val available: Boolean by lazy {
        try {
            (appContext.assets.list("voice")?.size ?: 0) > 10
        } catch (_: Throwable) {
            false
        }
    }

    fun speak(text: String, speed: Float) {
        if (!available) return
        stop()
        val tokens = tokensOf(text)
        if (tokens.isEmpty()) return
        tokens.forEach { queue.addLast(it to speed) }
        playNext()
    }

    fun stop() {
        queue.clear()
        current?.let {
            try {
                it.stop()
            } catch (_: Throwable) {
            }
            try {
                it.release()
            } catch (_: Throwable) {
            }
        }
        current = null
    }

    private fun playNext() {
        val item = queue.removeFirstOrNull() ?: return
        val file = fileOf[item.first] ?: return playNext()
        try {
            val afd = appContext.assets.openFd("voice/$file.mp3")
            val mp = MediaPlayer()
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.setPlaybackParams(PlaybackParams().setSpeed(item.second).setPitch(1f))
            mp.setOnCompletionListener { p ->
                try {
                    p.release()
                } catch (_: Throwable) {
                }
                if (current === p) current = null
                playNext()
            }
            mp.setOnErrorListener { p, _, _ ->
                try {
                    p.release()
                } catch (_: Throwable) {
                }
                playNext()
                true
            }
            mp.prepare()
            mp.start()
            current = mp
            Log.d("VoicePack", "play ${item.first} x${item.second}")
        } catch (_: Throwable) {
            playNext()
        }
    }

    /** 把「三十八 加 九 等于几」拆成音频片段 */
    private fun tokensOf(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == ' ' || ch == '　' || ch == '，' || ch == '。' || ch == '、') {
                i++
                continue
            }
            if (ch in "零一二三四五六七八九十百") {
                out.add(ch.toString())
                i++
                continue
            }
            val word = words.firstOrNull { text.startsWith(it, i) }
            if (word != null) {
                out.add(word)
                i += word.length
                continue
            }
            if (fileOf.containsKey(ch.toString())) {
                out.add(ch.toString())
            }
            i++
        }
        return out
    }
}

package com.mathking.tv.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 内置语音包：电视上基本都没有中文 TTS 引擎（实测小米电视 tts_default_synth 为空），
 * 所以用 assets 里的单字/短语音频拼读题目，不依赖系统语音、不依赖网络。
 *
 * 两个关键点：
 * 1. 变速必须在 prepare() 之后设置，否则部分电视会抛异常（表现为「有播放器但没声音」）
 * 2. 拼读不是匀速念：运算符前停一下、等于几前停得更久，数字之间尽量连读
 */
class VoicePack(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private data class Token(val text: String, val speed: Float, val gapBeforeMs: Int)

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

    private val wordList = listOf(
        "这道题有错误", "正确答案是几", "回答正确", "回答错误",
        "再算一遍", "等于几", "除以", "分之"
    )

    private val ops = setOf("加", "减", "乘", "除以", "等于几", "分之")

    private val queue = ArrayDeque<Token>()
    private var current: MediaPlayer? = null
    private var pending: Runnable? = null

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
        tokens.forEachIndexed { i, t ->
            queue.addLast(Token(t, speed, gapBefore(tokens, i)))
        }
        playNext()
    }

    fun stop() {
        queue.clear()
        pending?.let { handler.removeCallbacks(it) }
        pending = null
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

    /** 停顿设计：运算符前后各留一口气，等于几之前停得更明显，数字之间尽量连读 */
    private fun gapBefore(tokens: List<String>, i: Int): Int {
        val t = tokens[i]
        val prev = tokens.getOrNull(i - 1)
        return when {
            // 符号前明显停顿，让孩子听清「这是加还是减」
            t in ops -> 260
            t == "这道题有错误" -> 220
            t == "回答正确" || t == "回答错误" || t == "再算一遍" || t == "时间到" -> 0
            // 符号后面给一小口气，再读数字
            prev in ops -> 90
            else -> 0
        }
    }

    private fun playNext() {
        val item = queue.removeFirstOrNull() ?: return
        if (item.gapBeforeMs > 0) {
            val runnable = Runnable { startToken(item) }
            pending = runnable
            handler.postDelayed(runnable, item.gapBeforeMs.toLong())
        } else {
            startToken(item)
        }
    }

    private fun startToken(item: Token) {
        val file = fileOf[item.text] ?: return playNext()
        try {
            val afd = appContext.assets.openFd("voice/$file.mp3")
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.setVolume(1f, 1f)
            mp.setOnCompletionListener { p ->
                try {
                    p.release()
                } catch (_: Throwable) {
                }
                if (current === p) current = null
                playNext()
            }
            mp.setOnErrorListener { p, what, extra ->
                Log.w("VoicePack", "error what=$what extra=$extra file=$file")
                try {
                    p.release()
                } catch (_: Throwable) {
                }
                if (current === p) current = null
                playNext()
                true
            }
            mp.prepare()
            // 变速只能在 prepare 之后设置，提前设置会抛异常导致整句静默跳过
            try {
                mp.playbackParams = PlaybackParams().setSpeed(item.speed).setPitch(1f)
            } catch (t: Throwable) {
                Log.w("VoicePack", "speed not supported: ${t.message}")
            }
            mp.start()
            current = mp
            Log.i("VoicePack", "play $file x${item.speed} gap=${item.gapBeforeMs}")
        } catch (t: Throwable) {
            Log.w("VoicePack", "cannot play $file: ${t.message}")
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
            val word = wordList.firstOrNull { text.startsWith(it, i) }
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

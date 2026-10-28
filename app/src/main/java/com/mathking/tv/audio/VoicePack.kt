package com.mathking.tv.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 内置语音包（PCM 拼接版）。
 *
 * 之前每个字用一个 MediaPlayer，字与字之间被「release + prepare + start」的启动延迟
 * 硬生生隔开 50~120ms，「四十四」听起来是「四 十 四」。现在改成：
 *   直接把 assets 里的裸 PCM（22050Hz 单声道 16bit）拼成一整段，
 *   数字之间零间隙，只有一个 AudioTrack 在播，变速也由它一次完成。
 *
 * 停顿只保留在需要的地方：运算符前、等于几前。
 */
class VoicePack(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val pcmCache = HashMap<String, ShortArray>()

    private var track: AudioTrack? = null
    private var releaseTask: Runnable? = null

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

    val available: Boolean by lazy {
        try {
            (appContext.assets.list("voice/raw")?.size ?: 0) > 10
        } catch (_: Throwable) {
            false
        }
    }

    fun speak(text: String, speed: Float) {
        if (!available) return
        stop()
        val tokens = tokensOf(text)
        if (tokens.isEmpty()) return
        val buf = buildPcm(tokens)
        if (buf.isEmpty()) return
        play(buf, speed)
    }

    fun stop() {
        releaseTask?.let { handler.removeCallbacks(it) }
        releaseTask = null
        track?.let {
            try {
                it.pause()
            } catch (_: Throwable) {
            }
            try {
                it.flush()
            } catch (_: Throwable) {
            }
            try {
                it.release()
            } catch (_: Throwable) {
            }
        }
        track = null
    }

    // ------------------------------------------------------------ 拼 PCM

    private fun buildPcm(tokens: List<String>): ShortArray {
        val parts = ArrayList<ShortArray>(tokens.size * 2)
        var total = 0
        tokens.forEachIndexed { i, token ->
            val gap = gapBefore(tokens, i)
            if (gap > 0) {
                val silence = ShortArray(RATE * gap / 1000)
                parts.add(silence)
                total += silence.size
            }
            val pcm = pcmOf(token)
            if (pcm != null) {
                parts.add(pcm)
                total += pcm.size
            }
        }
        val out = ShortArray(total)
        var pos = 0
        for (part in parts) {
            System.arraycopy(part, 0, out, pos, part.size)
            pos += part.size
        }
        return out
    }

    /** 只有符号前、等于几前停顿；数字之间完全连读 */
    private fun gapBefore(tokens: List<String>, i: Int): Int {
        val t = tokens[i]
        val prev = tokens.getOrNull(i - 1)
        return when {
            t in ops -> 200
            t == "这道题有错误" -> 200
            t == "回答正确" || t == "回答错误" || t == "再算一遍" || t == "时间到" -> 0
            prev in ops -> 55
            else -> 0
        }
    }

    private fun pcmOf(token: String): ShortArray? {
        val file = fileOf[token] ?: return null
        pcmCache[file]?.let { return it }
        return try {
            val bytes = appContext.assets.open("voice/raw/$file.raw").readBytes()
            val out = ShortArray(bytes.size / 2)
            for (i in out.indices) {
                val lo = bytes[i * 2].toInt() and 0xFF
                val hi = bytes[i * 2 + 1].toInt() and 0xFF
                out[i] = ((hi shl 8) or lo).toShort()
            }
            pcmCache[file] = out
            out
        } catch (t: Throwable) {
            Log.w("VoicePack", "load $file failed: ${t.message}")
            null
        }
    }

    // ------------------------------------------------------------ 播放

    private fun play(buf: ShortArray, speed: Float) {
        val rate = speed.coerceIn(0.5f, 2.0f)
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(buf.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(buf, 0, buf.size)
            try {
                track.playbackParams = PlaybackParams().setSpeed(rate).setPitch(1f)
            } catch (t: Throwable) {
                Log.w("VoicePack", "speed not supported: ${t.message}")
            }
            track.play()
            this.track = track
            val durMs = (buf.size * 1000L / RATE / rate).toLong()
            Log.i("VoicePack", "play ${buf.size} samples ${durMs}ms x$rate")
            val task = Runnable {
                try {
                    track.stop()
                } catch (_: Throwable) {
                }
                try {
                    track.release()
                } catch (_: Throwable) {
                }
                if (this.track === track) this.track = null
            }
            releaseTask = task
            handler.postDelayed(task, durMs + 120)
        } catch (t: Throwable) {
            Log.w("VoicePack", "play failed: ${t.message}")
        }
    }

    // ------------------------------------------------------------ 分词

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

    companion object {
        private const val RATE = 22050
    }
}

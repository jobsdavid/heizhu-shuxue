package com.mathking.tv.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * 内置语音包（PCM 拼接版）。
 *
 * 之前每个字用一个 MediaPlayer，字与字之间被「release + prepare + start」的启动延迟
 * 硬生生隔开 50~120ms，「四十四」听起来是「四 十 四」。现在改成：
 *   直接把 assets 里的裸 PCM（22050Hz 单声道 16bit）拼成一整段，
 *   数字之间零间隙，只有一个 AudioTrack 在播，变速也由它一次完成。
 *
 * 停顿只保留在需要的地方：符号前、句子之间（见 [SpeechLexicon]）。
 *
 * 支持暂停/续播：整段 PCM 事先写进 MODE_STATIC 的 AudioTrack，
 * 暂停就是 track.pause()，续播是 track.play()，位置由播放器自己记住，
 * 不会像「停掉再重读」那样从头开始。
 */
class VoicePack(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val pcmCache = HashMap<String, ShortArray>()
    private val lexicon = SpeechLexicon.load(appContext)

    private var track: AudioTrack? = null
    private var releaseTask: Runnable? = null
    private var paused = false

    /** 一段话播完的回调（暂停中不算播完） */
    var onComplete: (() -> Unit)? = null

    /**
     * 语音包能不能用。
     *
     * 别用 `assets.list("voice/raw").size` 判断：某些构建产出的 APK 里没有目录条目，
     * list() 会返回空数组，于是整包语音被判定为「不存在」，静默退回系统 TTS
     * （电视上通常没有中文 TTS，结果是全程没声音）——这条坑在模拟器上实测会复现。
     * 现在直接尝试打开词表里第一个文件。
     */
    val available: Boolean by lazy {
        val first = lexicon.firstFile() ?: return@lazy false
        try {
            appContext.assets.open("voice/raw/$first.raw").close()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "voice pack missing: ${t.message}")
            false
        }
    }

    val isPlaying: Boolean get() = track != null
    val isPaused: Boolean get() = track != null && paused

    /** 返回 true = 已经开始播放；false = 没东西可播（语音包没覆盖 / 播放器建不起来） */
    fun speak(text: String, speed: Float): Boolean {
        if (!available) return false
        stop()
        val pieces = lexicon.tokenize(text)
        if (pieces.isEmpty()) return false
        val un = lexicon.uncovered(text)
        if (un.isNotEmpty()) {
            // 词表缺字会直接读漏，宁可吵一点也要在日志里留下证据
            Log.w(TAG, "词表没覆盖：'$un' in '$text'")
        }
        val buf = buildPcm(pieces)
        if (buf.isEmpty()) return false
        Log.i(TAG, "say=$text -> ${pieces.joinToString("|") { it.token }}")
        return play(buf, speed)
    }

    fun stop() {
        releaseTask?.let { handler.removeCallbacks(it) }
        releaseTask = null
        paused = false
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

    /** 暂停。返回 true 表示确实暂停了一段正在播的语音 */
    fun pause(): Boolean {
        val t = track ?: return false
        if (paused) return true
        return try {
            t.pause()
            paused = true
            releaseTask?.let { handler.removeCallbacks(it) }
            releaseTask = null
            Log.i(TAG, "pause at ${t.playbackHeadPosition}")
            true
        } catch (t2: Throwable) {
            Log.w(TAG, "pause failed: ${t2.message}")
            false
        }
    }

    /** 从暂停处继续 */
    fun resume(): Boolean {
        val t = track ?: return false
        if (!paused) return true
        return try {
            t.play()
            paused = false
            scheduleRelease(t, remainingMs(t))
            Log.i(TAG, "resume")
            true
        } catch (t2: Throwable) {
            Log.w(TAG, "resume failed: ${t2.message}")
            false
        }
    }

    private fun remainingMs(t: AudioTrack): Long {
        val played = try {
            t.playbackHeadPosition
        } catch (_: Throwable) {
            0
        }
        val rate = currentSpeed.coerceIn(0.5f, 2.0f)
        val left = (currentSamples - played).coerceAtLeast(0)
        return (left * 1000L / RATE / rate).toLong()
    }

    // ------------------------------------------------------------ 拼 PCM

    private fun buildPcm(pieces: List<SpeechLexicon.Piece>): ShortArray {
        val parts = ArrayList<ShortArray>(pieces.size * 2)
        var total = 0
        pieces.forEach { piece ->
            if (piece.gapBeforeMs > 0) {
                val silence = ShortArray(RATE * piece.gapBeforeMs / 1000)
                parts.add(silence)
                total += silence.size
            }
            val pcm = pcmOf(piece.file)
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

    private fun pcmOf(file: String): ShortArray? {
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
            Log.w(TAG, "load $file failed: ${t.message}")
            null
        }
    }

    // ------------------------------------------------------------ 播放

    private var currentSamples = 0
    private var currentSpeed = 1f

    private fun play(buf: ShortArray, speed: Float): Boolean {
        val rate = speed.coerceIn(0.5f, 2.0f)
        currentSamples = buf.size
        currentSpeed = rate
        try {
            val t = AudioTrack.Builder()
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
            t.write(buf, 0, buf.size)
            try {
                t.playbackParams = PlaybackParams().setSpeed(rate).setPitch(1f)
            } catch (t2: Throwable) {
                Log.w(TAG, "speed not supported: ${t2.message}")
            }
            t.play()
            track = t
            val ms = (buf.size * 1000L / RATE / rate).toLong()
            Log.i(TAG, "play ${buf.size} samples ${ms}ms x$rate")
            scheduleRelease(t, ms)
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "play failed: ${t.message}")
            return false
        }
    }

    private fun scheduleRelease(t: AudioTrack, durMs: Long) {
        releaseTask?.let { handler.removeCallbacks(it) }
        val task = Runnable {
            if (paused) return@Runnable
            try {
                t.stop()
            } catch (_: Throwable) {
            }
            try {
                t.release()
            } catch (_: Throwable) {
            }
            if (track === t) {
                track = null
                releaseTask = null
                onComplete?.invoke()
            }
        }
        releaseTask = task
        handler.postDelayed(task, durMs + 120)
    }

    companion object {
        private const val TAG = "VoicePack"
        private const val RATE = 22050
    }
}

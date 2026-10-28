package com.mathking.tv.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * 音效：全部用正弦波现场合成，不占安装包体积。
 * 单线程串行播放，避免几个音叠在一起。
 */
object Sfx {

    @Volatile
    var enabled: Boolean = true

    private val pool = Executors.newSingleThreadExecutor()
    private const val RATE = 22050

    fun correct() = play(listOf(880.0 to 60, 1174.66 to 110))

    fun wrong() = play(listOf(320.0 to 110, 220.0 to 170))

    fun combo(level: Int) {
        val base = 660.0 + level * 80
        play(listOf(base to 50, base * 1.25 to 50, base * 1.5 to 90))
    }

    fun tick() = play(listOf(1400.0 to 35))

    fun start() = play(listOf(523.25 to 70, 659.25 to 70, 783.99 to 110))

    fun finish() = play(listOf(523.25 to 90, 659.25 to 90, 783.99 to 90, 1046.5 to 180))

    fun levelUp() = play(listOf(783.99 to 80, 987.77 to 80, 1174.66 to 80, 1567.98 to 220))

    private fun play(tones: List<Pair<Double, Int>>) {
        if (!enabled) return
        pool.execute {
            var track: AudioTrack? = null
            try {
                val totalMs = tones.sumOf { it.second }
                val totalSamples = RATE * totalMs / 1000
                val buf = ShortArray(totalSamples)
                var pos = 0
                for ((freq, ms) in tones) {
                    val n = RATE * ms / 1000
                    for (i in 0 until n) {
                        if (pos + i >= buf.size) break
                        // 淡入淡出，避免爆音
                        val edge = min(n / 6, 90)
                        val env = when {
                            i < edge -> i.toDouble() / edge
                            i > n - edge -> (n - i).toDouble() / edge
                            else -> 1.0
                        }
                        val v = sin(2 * PI * freq * i / RATE) * 0.45 * env
                        buf[pos + i] = (v * Short.MAX_VALUE).toInt().toShort()
                    }
                    pos += n
                }
                val sizeBytes = buf.size * 2
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(sizeBytes)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(buf, 0, buf.size)
                track.play()
                Thread.sleep((totalMs + 80).toLong())
            } catch (_: Throwable) {
                // 没声音也不能影响答题
            } finally {
                try {
                    track?.release()
                } catch (_: Throwable) {
                }
            }
        }
    }
}

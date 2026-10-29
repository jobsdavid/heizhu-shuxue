package com.mathking.tv.audio

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * 语音词表。
 *
 * 朗读是「按词拼音频」：词表里每个 token 都对应 assets/voice/raw 里的一段 PCM。
 * 词表本身写在 `assets/voice/lexicon.json`，与 `tools/gen_voice.py` 共用一份 —— 
 * 这样「代码里加了新词、音频没生成」不会悄悄变成静音。
 *
 * 最坑的历史 bug：`tokensOf` 遇到词表里没有的字**直接跳过**，
 * 于是「等于」「括号」被吞掉，读出来的句子缺斤少两还听不出来。
 * 现在 [tokenize] 会把没覆盖的字符记到 [uncovered] 里，单测直接断言它为空。
 */
class SpeechLexicon private constructor(
    private val entries: List<Entry>
) {

    data class Entry(val token: String, val file: String, val text: String)

    /** 一个可播放片段：文件 + 播它之前要留的静音 */
    data class Piece(val token: String, val file: String, val gapBeforeMs: Int)

    /** 按长度倒序，保证「等于几」优先于「等于」、「括号里填几」优先于「括号」 */
    private val byLength: List<Entry> = entries.sortedByDescending { it.token.length }

    /** 只有单字的 token 直接当字读 */
    private val singles: Map<Char, Entry> =
        entries.filter { it.token.length == 1 }.associateBy { it.token[0] }

    fun fileOf(token: String): String? = entries.firstOrNull { it.token == token }?.file

    /** 词表里第一个音频文件，用来探测语音包是否打进 APK */
    fun firstFile(): String? = entries.firstOrNull()?.file

    fun tokens(): List<String> = entries.map { it.token }

    fun files(): List<String> = entries.map { it.file }

    /**
     * 把一句朗读文本切成片段。
     * 空格不算停顿；逗号/逗号类标点会留 180ms；句号 220ms。
     */
    fun tokenize(text: String): List<Piece> {
        val out = ArrayList<Piece>(text.length)
        var i = 0
        var pendingGap = 0
        while (i < text.length) {
            val ch = text[i]
            when (ch) {
                ' ', '\u3000', '\n' -> {
                    i++
                    continue
                }
                '，', ',', '、', '：', ':' -> {
                    pendingGap = maxOf(pendingGap, PUNCT_GAP)
                    i++
                    continue
                }
                '。', '！', '!', '？', '?', '；', ';' -> {
                    pendingGap = maxOf(pendingGap, SENTENCE_GAP)
                    i++
                    continue
                }
            }
            val multi = byLength.firstOrNull { it.token.length > 1 && text.startsWith(it.token, i) }
            val entry = multi ?: singles[ch]
            if (entry == null) {
                i++
                continue
            }
            val gap = maxOf(pendingGap, gapFor(entry.token, out.lastOrNull()?.token))
            pendingGap = 0
            out.add(Piece(entry.token, entry.file, gap))
            i += entry.token.length
        }
        return out
    }

    /** 文本里没法朗读（会被吞掉）的字符，上线前必须为空 */
    fun uncovered(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == ' ' || ch == '\u3000' || ch in PUNCTUATION) {
                i++
                continue
            }
            val entry = byLength.firstOrNull { it.token.length > 1 && text.startsWith(it.token, i) }
                ?: singles[ch]
            if (entry == null) {
                sb.append(ch)
                i++
            } else {
                i += entry.token.length
            }
        }
        return sb.toString()
    }

    private fun gapFor(token: String, prev: String?): Int = when {
        // 分数读作「五分之二」，分之前后不能停顿，否则听着像「五，分之二」
        token == "分之" -> 60
        prev == "分之" -> 0
        // 整句话/短语前留一点空，听着才像在说话而不是连读
        token.length >= 4 -> PHRASE_GAP
        token in OPS -> OPS_GAP
        prev in OPS -> AFTER_OPS_GAP
        else -> 0
    }

    companion object {
        private const val TAG = "SpeechLexicon"
        private const val PUNCT_GAP = 180
        private const val SENTENCE_GAP = 240
        private const val PHRASE_GAP = 200
        private const val OPS_GAP = 190
        private const val AFTER_OPS_GAP = 55

        private val PUNCTUATION = "，,、：:。！!？?；;".toSet()

        /** 符号类 token：前后要有停顿，不然「三八加九」会糊成一团 */
        private val OPS = setOf("加", "减", "乘", "除以", "等于", "等于几", "分之", "括号", "括号里填几")

        fun load(context: Context): SpeechLexicon = try {
            fromJson(context.assets.open("voice/lexicon.json").bufferedReader().use { it.readText() })
        } catch (t: Throwable) {
            Log.w(TAG, "lexicon load failed: ${t.message}")
            SpeechLexicon(emptyList())
        }

        /** 单测直接读磁盘上的 lexicon.json，不走 Android assets */
        fun fromJson(json: String): SpeechLexicon {
            val arr = JSONObject(json).getJSONArray("tokens")
            val list = ArrayList<Entry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Entry(o.getString("token"), o.getString("file"), o.optString("text", "")))
            }
            return SpeechLexicon(list)
        }
    }
}

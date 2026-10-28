package com.mathking.tv.data

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/** 本地设置：默认阶段、题数、模式、声音、解锁进度、玩家 id（对战用） */
class Settings(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("mathking", Context.MODE_PRIVATE)

    var stageId: String
        get() = sp.getString("stage_id", StageCatalog.default.id) ?: StageCatalog.default.id
        set(value) = sp.edit().putString("stage_id", value).apply()

    var questionCount: Int
        get() = sp.getInt("question_count", 10)
        set(value) = sp.edit().putInt("question_count", value).apply()

    var mode: PlayMode
        get() = PlayMode.of(sp.getString("mode", PlayMode.PRACTICE.id))
        set(value) = sp.edit().putString("mode", value.id).apply()

    /** 题型：calc / mix / find（找错题） */
    var questionStyle: String
        get() = sp.getString("question_style", QuestionStyle.MIX.id) ?: QuestionStyle.MIX.id
        set(value) = sp.edit().putString("question_style", value).apply()

    /** 读题语速倍率：1.05 慢 / 1.3 正常 / 1.55 快 / 1.85 超快 */
    var speechScale: Float
        get() = sp.getFloat("speech_scale", 1.3f)
        set(value) = sp.edit().putFloat("speech_scale", value).apply()

    var soundOn: Boolean
        get() = sp.getBoolean("sound_on", true)
        set(value) = sp.edit().putBoolean("sound_on", value).apply()

    var speakOn: Boolean
        get() = sp.getBoolean("speak_on", true)
        set(value) = sp.edit().putBoolean("speak_on", value).apply()

    var unlockedAll: Boolean
        get() = sp.getBoolean("unlocked_all", false)
        set(value) = sp.edit().putBoolean("unlocked_all", value).apply()

    private var unlockedCache: MutableSet<String>? = null

    val unlocked: MutableSet<String>
        get() {
            unlockedCache?.let { return it }
            val set = sp.getStringSet("unlocked_stages", setOf("s1", "s2", "s3"))
                ?.toMutableSet() ?: mutableSetOf("s1", "s2", "s3")
            unlockedCache = set
            return set
        }

    fun isUnlocked(stageId: String): Boolean =
        unlockedAll || unlocked.contains(stageId)

    fun unlock(stageId: String) {
        val set = unlocked
        if (set.add(stageId)) {
            sp.edit().putStringSet("unlocked_stages", set).apply()
        }
    }

    fun unlockAll() {
        unlockedAll = true
        val ids = StageCatalog.available.map { it.id }.toMutableSet()
        sp.edit().putStringSet("unlocked_stages", ids).apply()
        unlockedCache = ids
    }

    /**
     * 解锁下一阶段：看该阶段的「累计」表现，而不是某一局的手气
     * —— 累计至少 20 题、正确率至少 85%
     */
    fun checkUnlock(stage: StageConfig, questions: Int, accuracy: Double): StageConfig? {
        if (questions < UNLOCK_MIN_QUESTIONS || accuracy < UNLOCK_MIN_ACCURACY) return null
        val next = StageCatalog.next(stage) ?: return null
        if (isUnlocked(next.id)) return null
        unlock(next.id)
        return next
    }

    fun unlockProgress(stage: StageConfig, questions: Int, accuracy: Double): UnlockProgress? {
        val next = StageCatalog.next(stage) ?: return null
        if (isUnlocked(next.id)) return null
        return UnlockProgress(
            nextStageName = next.name,
            questions = questions,
            accuracy = accuracy
        )
    }

    val playerId: String
        get() {
            var id = sp.getString("player_id", null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                sp.edit().putString("player_id", id).apply()
            }
            return id
        }

    fun clearProgress() {
        sp.edit()
            .remove("unlocked_stages")
            .putBoolean("unlocked_all", false)
            .apply()
        unlockedCache = null
    }

    companion object {
        const val UNLOCK_MIN_QUESTIONS = 20
        const val UNLOCK_MIN_ACCURACY = 85.0
    }

    /** 关闭「解锁全部」时，回到默认的 s1~s3 */
    fun resetUnlockToDefault() {
        unlockedAll = false
        val set = mutableSetOf("s1", "s2", "s3")
        sp.edit().putStringSet("unlocked_stages", set).apply()
        unlockedCache = set
    }
}

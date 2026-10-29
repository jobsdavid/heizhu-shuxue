package com.mathking.tv.data

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/** 本地设置：默认阶段、题数、模式、声音、解锁进度、玩家 id（对战用） */
class Settings(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("mathking", Context.MODE_PRIVATE)

    init {
        // 阶段体系重构（2026-10）：老的 stage_id 换成新的。
        // 必须用标记位保证只搬一次——否则新版的 s8/s9/s10 每次启动都会被当成旧 id 再搬一遍。
        if (!sp.getBoolean(KEY_STAGE_REMAP, false)) {
            val raw = sp.getString("stage_id", null)
            val mapped = StageCatalog.legacyRemap[raw]
            val editor = sp.edit().putBoolean(KEY_STAGE_REMAP, true)
            if (mapped != null) editor.putString("stage_id", mapped)
            editor.apply()
        }
    }

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

    /** 小朋友的名字，空 = 显示「小朋友」 */
    var userName: String
        get() = sp.getString("user_name", "") ?: ""
        set(value) = sp.edit().putString("user_name", value).apply()

    /** 头像（emoji） */
    var avatar: String
        get() = sp.getString("avatar", "🐷") ?: "🐷"
        set(value) = sp.edit().putString("avatar", value).apply()

    /** 自定义头像文件名（在 filesDir 下），空 = 用 emoji */
    var avatarImage: String
        get() = sp.getString("avatar_image", "") ?: ""
        set(value) = sp.edit().putString("avatar_image", value).apply()

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
    fun checkUnlock(
        stage: StageConfig,
        questions: Int,
        accuracy: Double,
        power: Double
    ): StageConfig? {
        if (questions < UNLOCK_MIN_QUESTIONS) return null
        if (accuracy < UNLOCK_MIN_ACCURACY) return null
        if (power < powerTarget(stage)) return null
        val next = StageCatalog.next(stage) ?: return null
        if (isUnlocked(next.id)) return null
        unlock(next.id)
        return next
    }

    fun unlockProgress(
        stage: StageConfig,
        questions: Int,
        accuracy: Double,
        power: Double
    ): UnlockProgress? {
        val next = StageCatalog.next(stage) ?: return null
        if (isUnlocked(next.id)) return null
        return UnlockProgress(
            nextStageName = next.name,
            questions = questions,
            accuracy = accuracy,
            power = power,
            powerTarget = powerTarget(stage)
        )
    }

    /**
     * 解锁下一阶段要求的算力。
     *
     * 用户要求「门槛很高」：按该阶段理论上限的 90% 定。
     * 以 s1 为例，满分上限约 774（正确率 450 + 速度 150 + 题量×难度 174），
     * 目标 697 —— 意味着 300 题上下的练习量、正确率 95% 以上、速度贴近阶段目标，
     * 三条里掉一条都到不了。想放宽就调 [POWER_TARGET_RATIO]。
     */
    fun powerTarget(stage: StageConfig): Int =
        (maxPower(stage) * POWER_TARGET_RATIO).toInt()

    /** 该阶段算力的理论上限（正确率满分 + 速度满分 + 题量难度满分） */
    fun maxPower(stage: StageConfig): Double =
        450.0 + 150.0 + 400.0 * (stage.weight / 2.3).coerceAtMost(1.0)

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
        /** 阶段 id 重映射只做一次 */
        const val KEY_STAGE_REMAP = "stage_remap_v4"

        const val UNLOCK_MIN_QUESTIONS = 20
        const val UNLOCK_MIN_ACCURACY = 85.0

        /** 解锁要求的算力 = 阶段理论上限 × 这个比例（越高越难） */
        const val POWER_TARGET_RATIO = 0.9
    }

    /** 关闭「解锁全部」时，回到默认的 s1~s3 */
    fun resetUnlockToDefault() {
        unlockedAll = false
        val set = mutableSetOf("s1", "s2", "s3")
        sp.edit().putStringSet("unlocked_stages", set).apply()
        unlockedCache = set
    }
}

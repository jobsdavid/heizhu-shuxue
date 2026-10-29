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
     * 解锁下一阶段：看该阶段的「累计」表现，不是某一局的手气。
     *
     * 三条同时满足（2026-10 定稿，用户原话「基本上不需要思考，能答对 95% 以上的速度」）：
     * 1. 累计 ≥ [UNLOCK_MIN_QUESTIONS] 题；
     * 2. 累计正确率 ≥ [UNLOCK_MIN_ACCURACY]（95%）；
     * 3. 累计平均每题用时 ≤ 阶段目标秒数 × [UNLOCK_SPEED_RATIO] —— 到「条件反射」的程度，
     *    而不是「想一想能做对」。
     */
    fun checkUnlock(
        stage: StageConfig,
        questions: Int,
        accuracy: Double,
        avgSeconds: Double
    ): StageConfig? {
        if (!meetsUnlock(stage, questions, accuracy, avgSeconds)) return null
        val next = StageCatalog.next(stage) ?: return null
        if (isUnlocked(next.id)) return null
        unlock(next.id)
        return next
    }

    fun unlockProgress(
        stage: StageConfig,
        questions: Int,
        accuracy: Double,
        avgSeconds: Double
    ): UnlockProgress? {
        val next = StageCatalog.next(stage) ?: return null
        if (isUnlocked(next.id)) return null
        return UnlockProgress(
            nextStageName = next.name,
            questions = questions,
            accuracy = accuracy,
            avgSeconds = avgSeconds,
            targetSeconds = speedTargetSeconds(stage)
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
        /** 阶段 id 重映射只做一次 */
        const val KEY_STAGE_REMAP = "stage_remap_v4"

        const val UNLOCK_MIN_QUESTIONS = 20

        /** 累计正确率必须到 95%：会做还不够，要几乎不错 */
        const val UNLOCK_MIN_ACCURACY = 95.0

        /** 平均每题用时 ≤ 阶段目标 × 这个比例：1.0 = 必须达到「不用想」的速度 */
        const val UNLOCK_SPEED_RATIO = 1.0

        /** 解锁要求的速度：平均每题不超过这个秒数 */
        fun speedTargetSeconds(stage: StageConfig): Double =
            stage.targetSeconds * UNLOCK_SPEED_RATIO

        /** 解锁条件是否已经全部达到（不含「下一阶段是否已解锁」） */
        fun meetsUnlock(
            stage: StageConfig,
            questions: Int,
            accuracy: Double,
            avgSeconds: Double
        ): Boolean =
            questions >= UNLOCK_MIN_QUESTIONS &&
                accuracy >= UNLOCK_MIN_ACCURACY &&
                avgSeconds > 0 && avgSeconds <= speedTargetSeconds(stage)
    }

    /** 关闭「解锁全部」时，回到默认的 s1~s3 */
    fun resetUnlockToDefault() {
        unlockedAll = false
        val set = mutableSetOf("s1", "s2", "s3")
        sp.edit().putStringSet("unlocked_stages", set).apply()
        unlockedCache = set
    }
}

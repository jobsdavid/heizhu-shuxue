package com.mathking.tv.data

/** 四则运算 */
enum class Op(val symbol: String, val speak: String) {
    ADD("+", "加"),
    SUB("−", "减"),
    MUL("×", "乘"),
    DIV("÷", "除以")
}

/** 阶段的题型结构：决定出题器走哪条分支 */
enum class StageKind {
    ADD_SUB,        // 加减（一体）
    MUL,            // 乘法
    DIV,            // 除法
    MUL_DIV,        // 乘除混合
    FOUR_OPS,       // 四则混合
    FRACTION_SAME,  // 同分母分数加减
    FRACTION_DIFF   // 异分母分数加减（通分）
}

/** 进位/退位规则：一年级先练不进位，再专攻进位 */
enum class CarryRule {
    ANY,        // 都行
    REQUIRE,    // 必须进退位
    AVOID       // 不进位退位
}

/** 学习阶段：一条配置 = 一个年级台阶 */
data class StageConfig(
    val id: String,
    val name: String,
    val ops: List<Op>,
    val maxValue: Int,
    val targetSeconds: Int,
    val weight: Double,
    val kind: StageKind = StageKind.ADD_SUB,
    val carry: CarryRule = CarryRule.ANY,
    val minValue: Int = 1,
    /** 分数阶段的分母上限（kind 为分数时生效） */
    val maxDen: Int = 10,
    val tip: String = "",
    val available: Boolean = true
) {
    val fraction: Boolean get() = kind == StageKind.FRACTION_SAME || kind == StageKind.FRACTION_DIFF
}

/** 玩法 */
enum class PlayMode(val id: String, val label: String) {
    PRACTICE("practice", "练习"),
    TIMED("timed", "限时"),
    EXAM("exam", "考试"),
    WRONG("wrongbook", "错题重练"),
    CHALLENGE("challenge", "挑战电脑");

    companion object {
        fun of(id: String?): PlayMode = entries.firstOrNull { it.id == id } ?: PRACTICE
    }
}

/** 一道题 */
data class Question(
    val text: String,
    val answer: Int,
    val tag: String,
    val answerText: String = answer.toString(),
    val speak: String = "",
    val hint: String = "",
    /** true = 找错题：题面是一个算错的式子，要填正确答案 */
    val findError: Boolean = false,
    /** >0 表示答案是分数：孩子只填分子，分母固定显示（如 4 → 答案框显示 ?/4） */
    val answerDen: Int = 0
)

/** 题型：计算 / 找错 / 填空（求括号里的数） / 混合 */
enum class QuestionStyle(val id: String, val label: String) {
    CALC("calc", "只做计算"),
    FIND("find", "只找错题"),
    FILL("fill", "只做填空"),
    MIX("mix", "全都来一点");

    companion object {
        fun of(id: String?): QuestionStyle = entries.firstOrNull { it.id == id } ?: MIX
    }
}

/** 一道题的作答记录 */
data class AttemptRecord(
    val index: Int,
    val question: Question,
    val userAnswer: Int?,
    val firstOk: Boolean,
    val attempts: Int,
    val ms: Long,
    val timedOut: Boolean = false
)

/** 一局结束后的汇总 */
data class SessionSummary(
    val sessionId: Long,
    val stage: StageConfig,
    val mode: PlayMode,
    val total: Int,
    val correctFirst: Int,
    val wrong: Int,
    val durationMs: Long,
    val score: Double,
    val attempts: List<AttemptRecord>,
    val power: Double,
    val finished: Boolean
) {
    val accuracy: Double get() = if (total == 0) 0.0 else correctFirst * 100.0 / total
    val avgSeconds: Double get() = if (total == 0) 0.0 else durationMs / 1000.0 / total
    val stars: Int
        get() = when {
            accuracy >= 95 -> 3
            accuracy >= 80 -> 2
            accuracy >= 60 -> 1
            else -> 0
        }
}

/** 错题本条目 */
data class WrongEntry(
    val id: Long,
    val stageId: String,
    val expr: String,
    val answer: String,
    val wrongCount: Int,
    val streak: Int,
    val mastered: Boolean,
    val tag: String = "错题"
)

/** 每日统计点 */
data class DailyPoint(
    val day: String,
    val total: Int,
    val correct: Int,
    val msTotal: Long
) {
    val accuracy: Double get() = if (total == 0) 0.0 else correct * 100.0 / total
    val avgSeconds: Double get() = if (total == 0) 0.0 else msTotal / 1000.0 / total
}

/** 知识点统计 */
data class TagStat(val tag: String, val total: Int, val correct: Int) {
    val accuracy: Double get() = if (total == 0) 0.0 else correct * 100.0 / total
}

/** 首页今日概览 */
data class TodaySummary(
    val sessions: Int,
    val questions: Int,
    val correct: Int,
    val msTotal: Long
) {
    val accuracy: Double get() = if (questions == 0) 0.0 else correct * 100.0 / questions
}

/** 某个范围内的整体表现（用于首页、统计页顶部、能力分） */
data class Overall(
    val sessions: Int = 0,
    val questions: Int = 0,
    val correct: Int = 0,
    val msTotal: Long = 0
) {
    val accuracy: Double get() = if (questions == 0) 0.0 else correct * 100.0 / questions
    val avgSeconds: Double get() = if (questions == 0) 0.0 else msTotal / 1000.0 / questions
}

/** 距离解锁下一阶段的进度 */
data class UnlockProgress(
    val nextStageName: String,
    val questions: Int,
    val accuracy: Double
) {
    val needMoreQuestions: Int
        get() = (Settings.UNLOCK_MIN_QUESTIONS - questions).coerceAtLeast(0)
    val needMoreAccuracy: Double
        get() = (Settings.UNLOCK_MIN_ACCURACY - accuracy).coerceAtLeast(0.0)
}

/** 每日挑战记录 */
data class DailyRecord(
    val day: String,
    val stageId: String,
    val total: Int,
    val correct: Int
) {
    val full: Boolean get() = total > 0 && correct == total
}

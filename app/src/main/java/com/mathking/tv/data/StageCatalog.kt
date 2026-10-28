package com.mathking.tv.data

/**
 * 学习阶段目录 —— 按小学课标的知识点顺序排的台阶。
 *
 * 设计原则：
 * 1. 加和减是一体的（同一个阶段里混着出），乘法和除法各自独立成阶段；
 * 2. 每个阶段只改一个维度（数域 / 是否进退位 / 是否带余数 / 分数），
 *    避免「一次跳太远」导致孩子掉队；
 * 3. 阶段 id 用来串起历史记录、错题本、挑战基准和每日挑战，**不要随便改**；
 *    真的要改，得同时写 DbHelper 的迁移。
 *
 * id 变更记录（2026-10 重构）：
 *   旧 s8「100 以内四则混合」→ 新 s10
 *   旧 s9「分数初步」        → 新 s11
 *   旧 s10「分数进阶」       → 新 s12
 *   新 s8「两位数乘一位数」、s9「三位数除以一位数」是本次新增，没有历史数据。
 */
object StageCatalog {

    val all: List<StageConfig> = listOf(
        // ---------------------------------------------------------- 加减（一体）
        StageConfig(
            id = "s1", name = "10 以内加减", ops = listOf(Op.ADD, Op.SUB),
            maxValue = 10, minValue = 1, targetSeconds = 5, weight = 1.0,
            kind = StageKind.ADD_SUB, carry = CarryRule.AVOID,
            tip = "先练不进位、不退位，把数感建立起来"
        ),
        StageConfig(
            id = "s2", name = "20 以内加减", ops = listOf(Op.ADD, Op.SUB),
            maxValue = 20, minValue = 1, targetSeconds = 6, weight = 1.2,
            kind = StageKind.ADD_SUB, carry = CarryRule.ANY,
            tip = "进位加、退位减，一年级上册的重点"
        ),
        StageConfig(
            id = "s3", name = "50 以内加减", ops = listOf(Op.ADD, Op.SUB),
            maxValue = 50, minValue = 1, targetSeconds = 7, weight = 1.4,
            kind = StageKind.ADD_SUB, carry = CarryRule.ANY,
            tip = "两位数加减，练熟凑十法和破十法"
        ),
        StageConfig(
            id = "s4", name = "100 以内加减", ops = listOf(Op.ADD, Op.SUB),
            maxValue = 100, minValue = 1, targetSeconds = 8, weight = 1.6,
            kind = StageKind.ADD_SUB, carry = CarryRule.ANY,
            tip = "两位数加减综合，进位退位都要熟"
        ),
        // ---------------------------------------------------------- 乘法（独立）
        StageConfig(
            id = "s5", name = "表内乘法", ops = listOf(Op.MUL),
            maxValue = 9, minValue = 2, targetSeconds = 6, weight = 1.3,
            kind = StageKind.MUL,
            tip = "九九乘法表，背熟再追速度"
        ),
        StageConfig(
            id = "s6", name = "表内除法", ops = listOf(Op.DIV),
            maxValue = 9, minValue = 2, targetSeconds = 6, weight = 1.5,
            kind = StageKind.DIV,
            tip = "除法是乘法的逆运算，用口诀倒着想"
        ),
        StageConfig(
            id = "s7", name = "乘除混合", ops = listOf(Op.MUL, Op.DIV),
            maxValue = 9, minValue = 2, targetSeconds = 7, weight = 1.6,
            kind = StageKind.MUL_DIV,
            tip = "乘除一起出，看清符号再动手"
        ),
        StageConfig(
            id = "s8", name = "两位数乘一位数", ops = listOf(Op.MUL),
            maxValue = 99, minValue = 10, targetSeconds = 9, weight = 1.8,
            kind = StageKind.MUL,
            tip = "先拆成整十和个位，分别乘再相加"
        ),
        // ---------------------------------------------------------- 除法（独立）
        StageConfig(
            id = "s9", name = "三位数除以一位数", ops = listOf(Op.DIV),
            maxValue = 999, minValue = 100, targetSeconds = 11, weight = 2.0,
            kind = StageKind.DIV,
            tip = "从最高位开始除，除到哪一位商就写在哪一位"
        ),
        // ---------------------------------------------------------- 综合
        StageConfig(
            id = "s10", name = "100 以内四则混合", ops = listOf(Op.ADD, Op.SUB, Op.MUL, Op.DIV),
            maxValue = 100, minValue = 1, targetSeconds = 10, weight = 2.1,
            kind = StageKind.FOUR_OPS,
            tip = "加减乘除全上，先看符号再算"
        ),
        // ---------------------------------------------------------- 分数
        StageConfig(
            id = "s11", name = "分数初步", ops = listOf(Op.ADD, Op.SUB),
            maxValue = 10, minValue = 2, targetSeconds = 12, weight = 2.3,
            kind = StageKind.FRACTION_SAME, maxDen = 10,
            tip = "同分母分数相加减：分母不变，分子相加减"
        ),
        StageConfig(
            id = "s12", name = "分数进阶", ops = listOf(Op.ADD, Op.SUB),
            maxValue = 12, minValue = 2, targetSeconds = 14, weight = 2.6,
            kind = StageKind.FRACTION_DIFF, maxDen = 12,
            tip = "异分母要先通分：把一个分数变成同分母再算"
        )
    )

    val available: List<StageConfig> get() = all.filter { it.available }

    fun byId(id: String?): StageConfig = all.firstOrNull { it.id == id } ?: default

    /** 默认阶段：10 以内加减（新装的孩子从这里开始） */
    val default: StageConfig = all.first { it.id == "s1" }

    fun next(stage: StageConfig): StageConfig? {
        val list = available
        val i = list.indexOfFirst { it.id == stage.id }
        return if (i >= 0 && i + 1 < list.size) list[i + 1] else null
    }

    fun indexOf(stage: StageConfig): Int = available.indexOfFirst { it.id == stage.id }

    /** 旧版本 stage_id → 新 stage_id，用于数据库迁移 */
    val legacyRemap: Map<String, String> = mapOf(
        "s8" to "s10",   // 旧：100 以内四则混合
        "s9" to "s11",   // 旧：分数初步
        "s10" to "s12"   // 旧：分数进阶
    )
}

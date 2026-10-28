package com.mathking.tv.data

/**
 * 学习阶段目录。新增阶段只需要在这里加一条（并在 Generator 里支持对应题型），
 * 其余代码（解锁、统计、设置）自动生效。
 */
object StageCatalog {

    val all: List<StageConfig> = listOf(
        StageConfig(
            id = "s1", name = "10 以内加减", ops = listOf(Op.ADD, Op.SUB),
            maxValue = 10, targetSeconds = 5, weight = 1.0,
            tip = "刚开始学数数，慢慢来"
        ),
        StageConfig(
            id = "s2", name = "20 以内加减", ops = listOf(Op.ADD, Op.SUB),
            maxValue = 20, targetSeconds = 6, weight = 1.2,
            tip = "进位加、退位减，是小学一年的重点"
        ),
        StageConfig(
            id = "s3", name = "50 以内加减", ops = listOf(Op.ADD, Op.SUB),
            maxValue = 50, targetSeconds = 7, weight = 1.4,
            tip = "熟悉两位数，练熟凑十法"
        ),
        StageConfig(
            id = "s4", name = "100 以内加减", ops = listOf(Op.ADD, Op.SUB),
            maxValue = 100, targetSeconds = 8, weight = 1.6,
            tip = "两位数加减法的综合练习"
        ),
        StageConfig(
            id = "s5", name = "表内乘法", ops = listOf(Op.MUL),
            maxValue = 9, targetSeconds = 6, weight = 1.3,
            tip = "九九乘法表，背熟再练速度"
        ),
        StageConfig(
            id = "s6", name = "表内除法", ops = listOf(Op.DIV),
            maxValue = 9, targetSeconds = 6, weight = 1.5,
            tip = "除法是乘法的逆运算，用口诀想"
        ),
        StageConfig(
            id = "s7", name = "乘除混合", ops = listOf(Op.MUL, Op.DIV),
            maxValue = 9, targetSeconds = 7, weight = 1.6,
            tip = "乘除一起练，别被符号骗到"
        ),
        StageConfig(
            id = "s8", name = "100 以内四则混合", ops = listOf(Op.ADD, Op.SUB, Op.MUL, Op.DIV),
            maxValue = 100, targetSeconds = 9, weight = 1.9,
            tip = "加减乘除全上，看清符号再算"
        ),
        StageConfig(
            id = "s9", name = "分数初步", ops = listOf(Op.ADD, Op.SUB),
            maxValue = 10, targetSeconds = 10, weight = 2.0, fraction = true,
            tip = "同分母分数加减：分母不变，分子相加减"
        ),
        StageConfig(
            id = "s10", name = "分数进阶", ops = listOf(Op.ADD, Op.SUB),
            maxValue = 12, targetSeconds = 12, weight = 2.3, fraction = true,
            available = false, tip = "异分母、约分、比较大小（开发中）"
        )
    )

    val available: List<StageConfig> get() = all.filter { it.available }

    fun byId(id: String?): StageConfig = all.firstOrNull { it.id == id } ?: default

    /** 默认阶段：50 以内加减 */
    val default: StageConfig = all.first { it.id == "s3" }

    fun next(stage: StageConfig): StageConfig? {
        val list = available
        val i = list.indexOfFirst { it.id == stage.id }
        return if (i >= 0 && i + 1 < list.size) list[i + 1] else null
    }
}

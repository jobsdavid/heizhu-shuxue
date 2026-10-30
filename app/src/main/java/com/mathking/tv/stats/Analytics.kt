package com.mathking.tv.stats

import com.mathking.tv.R
import kotlin.math.max
import kotlin.math.min

/**
 * 统计口径：
 * - 正确率（首次答对率）衡量「会不会」
 * - 平均用时 / 阶段目标用时 衡量「熟不熟」
 * - 能力分把两者和做题量合成一个 0~1000 的分数，配段位给娃正反馈
 */
object Analytics {

    const val MAX_POWER = 1000.0

    fun speedScore(avgSeconds: Double, targetSeconds: Int): Double {
        if (avgSeconds <= 0.0 || targetSeconds <= 0) return 0.0
        return (targetSeconds / avgSeconds).coerceIn(0.2, 1.2) / 1.2
    }

    fun difficultyScore(questions: Int, weight: Double): Double {
        val progress = min(1.0, questions / 300.0)
        return (weight / 2.3).coerceIn(0.0, 1.0) * progress
    }

    fun power(
        accuracyPct: Double,
        avgSeconds: Double,
        targetSeconds: Int,
        questions: Int,
        weight: Double
    ): Double {
        if (questions <= 0) return 0.0
        val acc = (accuracyPct / 100.0).coerceIn(0.0, 1.0)
        // 正确率决定"会不会"，速度决定"熟不熟"，题量×阶段难度决定"练到哪一层"。
        // 权重把一半以上分给正确率，但不让刚练十道题的娃直接封顶。
        val p = 450 * acc + 150 * speedScore(avgSeconds, targetSeconds) +
            400 * difficultyScore(questions, weight)
        return min(MAX_POWER, max(0.0, p))
    }

    data class Rank(val level: Int, val name: String, val badge: String, val color: Int, val next: Double)

    /** 全局算力：最强的那一档难度 + 它的算力 */
    data class GlobalPower(val power: Double, val stage: com.mathking.tv.data.StageConfig?)

    /**
     * 全局等级/算力口径（2026-10 明确）：
     *
     * 每个难度用**自己的**目标秒数和难度权重各算一份算力，取最高的那档当作「总算力」。
     * 以前是「全局总题数 × 当前难度的目标秒数/权重」——两个口径混着算，
     * 练 S1 的人切到 S12 等级立刻掉一大截，家长也看不懂显示的是总分还是当前难度分。
     *
     * 取最大值而不是加权平均：等级只升不降，换难度不会把娃的等级打回原形；
     * 「最高难度」也能从 [GlobalPower.stage] 里说清楚。
     */
    fun overallPower(perStage: Map<String, com.mathking.tv.data.Overall>): GlobalPower {
        var best = 0.0
        var bestStage: com.mathking.tv.data.StageConfig? = null
        com.mathking.tv.data.StageCatalog.available.forEach { st ->
            val o = perStage[st.id] ?: return@forEach
            if (o.questions <= 0) return@forEach
            val p = power(o.accuracy, o.avgSeconds, st.targetSeconds, o.questions, st.weight)
            if (p > best) {
                best = p
                bestStage = st
            }
        }
        return GlobalPower(best, bestStage)
    }

    /**
     * 段位：猪头随段位换样式（🐷 小 → 🐽 → 🐖 → 🐗 → 👑 → 🏆），
     * 名字和算力数字都用同一档的颜色，家长一眼看出「到哪一级了」。
     */
    fun rank(power: Double): Rank = when {
        power >= 900 -> Rank(6, "数学大王", "", R.color.red, MAX_POWER)
        power >= 750 -> Rank(5, "数学大师", "", R.color.accent, 900.0)
        power >= 600 -> Rank(4, "数学高手", "", R.color.purple, 750.0)
        power >= 450 -> Rank(3, "数学能手", "", R.color.green, 600.0)
        power >= 300 -> Rank(2, "数学小将", "", R.color.blue, 450.0)
        else -> Rank(1, "数学小学徒", "", R.color.text_dim, 300.0)
    }
}

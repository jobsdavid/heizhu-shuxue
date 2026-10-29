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

    data class Rank(val name: String, val badge: String, val color: Int, val next: Double)

    /**
     * 段位：猪头随段位换样式（🐷 小 → 🐽 → 🐖 → 🐗 → 👑 → 🏆），
     * 名字和算力数字都用同一档的颜色，家长一眼看出「到哪一级了」。
     */
    fun rank(power: Double): Rank = when {
        power >= 900 -> Rank("数学大王", "🏆", R.color.accent, MAX_POWER)
        power >= 750 -> Rank("数学大师", "👑", R.color.purple, 900.0)
        power >= 600 -> Rank("数学高手", "🐗", R.color.blue, 750.0)
        power >= 450 -> Rank("数学能手", "🐖", R.color.green, 600.0)
        power >= 300 -> Rank("数学小将", "🐽", R.color.accent, 450.0)
        else -> Rank("数学小学徒", "🐷", R.color.text_dim, 300.0)
    }
}

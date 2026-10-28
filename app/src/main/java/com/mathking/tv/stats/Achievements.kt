package com.mathking.tv.stats

import com.mathking.tv.data.Repo

/** 成就：全部由已有数据实时判定，不单独存表 */
object Achievements {

    data class Stats(
        val sessions: Int = 0,
        val questions: Int = 0,
        val perfectSessions: Int = 0,
        val examPerfect: Int = 0,
        val challengeWins: Int = 0,
        val masteredWrong: Int = 0,
        val dailyStreak: Int = 0,
        val fastestMs: Long? = null
    )

    data class Achievement(
        val id: String,
        val icon: String,
        val title: String,
        val desc: String,
        val unlocked: Boolean,
        val progress: String
    )

    fun load(repo: Repo, power: Double): List<Achievement> {
        val overall = repo.overall()
        val stats = Stats(
            sessions = overall.sessions,
            questions = overall.questions,
            perfectSessions = repo.perfectSessionCount(),
            examPerfect = repo.examPerfectCount(),
            challengeWins = repo.challengeWinCount(),
            masteredWrong = repo.masteredCount(),
            dailyStreak = repo.dailyStreak(),
            fastestMs = repo.fastestQuestionMs()
        )
        return list(stats, power)
    }

    fun list(s: Stats, power: Double = 0.0): List<Achievement> {
        fun q(now: Int, need: Int) = "$now/$need"
        return listOf(
            Achievement(
                "first", "🌱", "初出茅庐", "完成第一轮练习",
                s.sessions >= 1, q(s.sessions.coerceAtMost(1), 1)
            ),
            Achievement(
                "q100", "📗", "百题斩", "累计做 100 道题",
                s.questions >= 100, q(s.questions.coerceAtMost(100), 100)
            ),
            Achievement(
                "q500", "📘", "五百题", "累计做 500 道题",
                s.questions >= 500, q(s.questions.coerceAtMost(500), 500)
            ),
            Achievement(
                "q1000", "📚", "千题斩", "累计做 1000 道题",
                s.questions >= 1000, q(s.questions.coerceAtMost(1000), 1000)
            ),
            Achievement(
                "perfect", "💯", "满分达人", "任意一轮全对",
                s.perfectSessions >= 1, q(s.perfectSessions.coerceAtMost(1), 1)
            ),
            Achievement(
                "exam", "📝", "考神附体", "考试模式全对一次",
                s.examPerfect >= 1, q(s.examPerfect.coerceAtMost(1), 1)
            ),
            Achievement(
                "beatpc", "⚔️", "电脑克星", "挑战电脑赢 3 次",
                s.challengeWins >= 3, q(s.challengeWins.coerceAtMost(3), 3)
            ),
            Achievement(
                "wrong10", "🧹", "改错高手", "错题本攻克 10 道",
                s.masteredWrong >= 10, q(s.masteredWrong.coerceAtMost(10), 10)
            ),
            Achievement(
                "daily3", "🔥", "三日不辍", "每日挑战连对 3 天",
                s.dailyStreak >= 3, q(s.dailyStreak.coerceAtMost(3), 3)
            ),
            Achievement(
                "daily7", "🌟", "七日不辍", "每日挑战连对 7 天",
                s.dailyStreak >= 7, q(s.dailyStreak.coerceAtMost(7), 7)
            ),
            Achievement(
                "speed3", "⚡", "闪电快手", "全对且平均 ≤ 3 秒/题",
                s.fastestMs != null && s.fastestMs <= 3000,
                if (s.fastestMs == null) "还没达标" else "${s.fastestMs / 1000.0} 秒"
            ),
            Achievement(
                "hero", "👑", "数学大王", "算力达到 900",
                power >= 900, "${power.toInt()}/900"
            )
        )
    }
}

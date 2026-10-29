package com.mathking.tv.data

/**
 * 挑战次数的换算规则（纯函数，方便单测）。
 *
 * 解锁时送 1 次；之后每考 [Repo.CHALLENGE_EXAMS_PER_CREDIT] 场考试（不要求满分）再攒 1 次；
 * 每开一局挑战花掉 1 次。次数可以攒着，不清零。
 */
object ChallengeRules {

    const val PER_CREDIT = Repo.CHALLENGE_PERFECT_PER_CREDIT

    /**
     * 挑战次数 = 满分考试场次 / 2 − 已花掉的次数
     *
     * 2 场满分 = 1 次挑战；攒着不清零，打多了也不会变负数。
     *
     * @param perfectExams 该阶段满分考试场次
     * @param startsAfter 解锁之后已经打了几局挑战
     */
    fun credits(perfectExams: Int, startsAfter: Int): Int =
        (perfectExams.coerceAtLeast(0) / PER_CREDIT - startsAfter.coerceAtLeast(0))
            .coerceAtLeast(0)

    /** 距离下一次攒到挑战次数还差几场满分考试 */
    fun perfectsToNextCredit(perfectExams: Int): Int =
        PER_CREDIT - (perfectExams.coerceAtLeast(0) % PER_CREDIT)
}

package com.mathking.tv.data

/**
 * 挑战次数的换算规则（纯函数，方便单测）。
 *
 * 解锁时送 1 次；之后每考 [Repo.CHALLENGE_EXAMS_PER_CREDIT] 场考试（不要求满分）再攒 1 次；
 * 每开一局挑战花掉 1 次。次数可以攒着，不清零。
 */
object ChallengeRules {

    const val PER_CREDIT = Repo.CHALLENGE_EXAMS_PER_CREDIT

    /**
     * @param examsAfter 解锁之后又考了几场
     * @param startsAfter 解锁之后已经打了几局挑战
     */
    fun credits(examsAfter: Int, startsAfter: Int): Int =
        (1 + examsAfter.coerceAtLeast(0) / PER_CREDIT - startsAfter.coerceAtLeast(0))
            .coerceAtLeast(0)

    /** 距离下一次攒到挑战次数还差几场考试 */
    fun examsToNextCredit(examsAfter: Int): Int =
        PER_CREDIT - (examsAfter.coerceAtLeast(0) % PER_CREDIT)
}

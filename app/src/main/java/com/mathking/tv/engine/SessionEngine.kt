package com.mathking.tv.engine

import android.os.SystemClock
import com.mathking.tv.data.AttemptRecord
import com.mathking.tv.data.Generator
import com.mathking.tv.data.PlayMode
import com.mathking.tv.data.Question
import com.mathking.tv.data.QuestionStyle
import com.mathking.tv.data.Repo
import com.mathking.tv.data.SessionSummary
import com.mathking.tv.data.StageConfig
import kotlin.random.Random

/**
 * 一局的答题状态机。三种模式的差异都在 submit() 里收口：
 * - 练习 / 错题重练：答错停留在本题，必须答对才继续
 * - 限时：答错或超时立即下一题
 * - 考试：答完立即下一题，但不告诉对错
 */
class SessionEngine(
    val stage: StageConfig,
    val mode: PlayMode,
    planned: Int,
    private val sessionId: Long,
    private val repo: Repo,
    preGenerated: List<Question>? = null,
    private val style: QuestionStyle = QuestionStyle.CALC,
    private val seed: Long = 0L
) {

    val questions: List<Question> = preGenerated ?: generate(planned)

    var index: Int = 0
        private set

    var correctFirst: Int = 0
        private set

    var combo: Int = 0
        private set

    val records = mutableListOf<AttemptRecord>()

    private var attemptCount = 0
    private var questionDone = false
    private var questionStart = SystemClock.elapsedRealtime()
    private val startedAt = SystemClock.elapsedRealtime()

    val total: Int get() = questions.size
    val question: Question? get() = questions.getOrNull(index)
    val isLast: Boolean get() = index >= questions.size - 1
    val progress: Int get() = index + if (questionDone) 1 else 0
    val elapsedMs: Long get() = SystemClock.elapsedRealtime() - startedAt

    data class SubmitResult(
        val correct: Boolean,
        val retry: Boolean,
        val timedOut: Boolean,
        val combo: Int,
        val question: Question,
        val last: Boolean,
        /** 连错到上限，这题被跳过（已进错题本） */
        val skipped: Boolean = false
    )

    private fun generate(count: Int): List<Question> {
        val rnd = if (seed != 0L) Random(seed) else Random.Default
        val out = mutableListOf<Question>()
        val seen = HashSet<String>()
        var guard = 0
        while (out.size < count && guard < count * 50) {
            guard++
            val q = Generator.make(stage, style, rnd)
            if (seen.add(q.text)) out.add(q)
        }
        while (out.size < count) out.add(Generator.make(stage, style, rnd))
        return out
    }

    fun submit(value: Int?, timedOut: Boolean = false): SubmitResult? {
        val q = question ?: return null
        if (questionDone) return null
        attemptCount++
        val ms = SystemClock.elapsedRealtime() - questionStart
        val ok = !timedOut && value != null && value == q.answer

        return if (ok) {
            val first = attemptCount == 1
            if (first) {
                correctFirst++
                combo++
            } else {
                combo = 0
            }
            val rec = AttemptRecord(index, q, value, first, attemptCount, ms)
            records.add(rec)
            repo.saveAttempt(sessionId, rec)
            // 用题目自己的阶段：错题重练里可能混着别的阶段的题
            if (mode == PlayMode.WRONG) repo.markWrongRight(q.stageId.ifBlank { stage.id }, q)
            questionDone = true
            SubmitResult(true, false, false, combo, q, isLast)
        } else {
            // 第一次没答对就记进错题本：一直卡着答不对的题，恰恰是最该复习的。
            // 之前只在「答错后又答对」时才记，导致卡死的题永远进不了错题本。
            if (attemptCount == 1) repo.upsertWrong(q.stageId.ifBlank { stage.id }, q)
            if (attemptCount >= MAX_ATTEMPTS) {
                // 同一个坑摔三次就别耗着了：记一笔、进错题本、跳过去，
                // 免得孩子卡在那里把整轮练习都放弃掉
                val rec = AttemptRecord(index, q, value, false, attemptCount, ms, timedOut)
                records.add(rec)
                repo.saveAttempt(sessionId, rec)
                questionDone = true
                return SubmitResult(false, false, timedOut, 0, q, isLast, skipped = true)
            }
            if (mode == PlayMode.PRACTICE || mode == PlayMode.WRONG) {
                combo = 0
                SubmitResult(false, true, timedOut, 0, q, isLast)
            } else {
                combo = 0
                val rec = AttemptRecord(index, q, value, false, attemptCount, ms, timedOut)
                records.add(rec)
                repo.saveAttempt(sessionId, rec)
                // 错题在第一次没答对时就已收进错题本（上面的 attemptCount==1 分支），
                // 这里再收一次会让 wrong_count 加两次、streak 反复清零——考试/限时错一题记成两题。
                questionDone = true
                SubmitResult(false, false, timedOut, 0, q, isLast)
            }
        }
    }

    fun advance() {
        index++
        attemptCount = 0
        questionDone = false
        questionStart = SystemClock.elapsedRealtime()
    }

    fun summary(finished: Boolean): SessionSummary {
        val dur = elapsedMs
        val n = if (records.isEmpty()) 0 else records.size
        val score = if (n == 0) 0.0 else correctFirst * 100.0 / n
        return SessionSummary(
            sessionId = sessionId,
            stage = stage,
            mode = mode,
            total = n,
            correctFirst = correctFirst,
            wrong = n - correctFirst,
            durationMs = dur,
            score = score,
            attempts = records.toList(),
            power = 0.0,
            finished = finished
        )
    }

    /** 限时模式每题秒数：阶段目标用时 × 2.2，最少 8 秒，最多 25 秒 */
    fun timeLimitSeconds(): Int = (stage.targetSeconds * 2.2).toInt().coerceIn(8, 25)
    companion object {
        /** 同一题最多答几次，超过就跳过 */
        const val MAX_ATTEMPTS = 3
    }

}

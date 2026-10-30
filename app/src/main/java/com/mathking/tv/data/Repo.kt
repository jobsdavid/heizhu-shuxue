package com.mathking.tv.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import com.mathking.tv.audio.Speech
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 数据访问层。所有读写都丢到单线程队列，UI 线程只处理回调。
 */
data class StageReport(
    val practiceRounds: Int,
    val examRounds: Int,
    val challengeRounds: Int,
    val bestScore: Double,
    val worstScore: Double,
    val avgScore: Double,
    /** 单题最快 / 最慢 / 平均毫秒，0 = 没有记录 */
    val fastestMs: Long,
    val slowestMs: Long,
    val avgMs: Long,
    val attempts: Int
)

data class ChallengeStatus(
    /** 满分考试场次（解锁条件） */
    val perfectRounds: Int,
    /** 该阶段考试总场次 */
    val examRounds: Int,
    /** 剩余挑战次数 */
    val credits: Int,
    /** 电脑每题毫秒（已含 ×0.8 与下限） */
    val computerMs: Long?
)

class Repo(context: Context, userId: Long = 0L) {

    /** 每个孩子一个独立的库文件：数据物理隔离，查询不用到处加 user_id 条件 */
    private val helper = DbHelper(
        context.applicationContext,
        if (userId > 0) "mathking_u$userId.db" else "mathking.db"
    )
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun <T> async(work: (SQLiteDatabase) -> T, done: ((T) -> Unit)? = null) {
        io.execute {
            val result = work(helper.writableDatabase)
            if (done != null) main.post { done(result) }
        }
    }

    // ------------------------------------------------------------ session

    fun openSession(stageId: String, mode: PlayMode, planned: Int, playerId: String): Long {
        val cv = ContentValues().apply {
            put("started_at", System.currentTimeMillis())
            put("stage_id", stageId)
            put("mode", mode.id)
            put("planned", planned)
            put("player_id", playerId)
        }
        return helper.writableDatabase.insert("session", null, cv)
    }

    fun saveAttempt(sessionId: Long, rec: AttemptRecord) {
        val cv = ContentValues().apply {
            put("session_id", sessionId)
            put("idx", rec.index)
            put("expr", rec.question.text)
            put("answer", rec.question.answerText)
            put("user_answer", rec.userAnswer?.toString())
            put("first_ok", if (rec.firstOk) 1 else 0)
            put("attempts", rec.attempts)
            put("ms", rec.ms)
            put("tag", rec.question.tag)
            put("timed_out", if (rec.timedOut) 1 else 0)
        }
        helper.writableDatabase.insert("attempt", null, cv)
    }

    fun closeSession(
        sessionId: Long,
        total: Int,
        correctFirst: Int,
        wrong: Int,
        durationMs: Long,
        score: Double,
        power: Double,
        finished: Boolean
    ) {
        val cv = ContentValues().apply {
            put("ended_at", System.currentTimeMillis())
            put("total", total)
            put("correct_first", correctFirst)
            put("wrong", wrong)
            put("duration_ms", durationMs)
            put("score", score)
            put("power", power)
            put("finished", if (finished) 1 else 0)
        }
        helper.writableDatabase.update("session", cv, "id=?", arrayOf(sessionId.toString()))
    }

    // ------------------------------------------------------------ 错题本

    fun upsertWrong(stageId: String, q: Question) {
        val db = helper.writableDatabase
        val key = q.text
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val cursor = db.rawQuery(
                "SELECT id FROM wrong_item WHERE stage_id=? AND expr_key=?",
                arrayOf(stageId, key)
            )
            val exists = cursor.moveToFirst()
            val id = if (exists) cursor.getLong(0) else -1L
            cursor.close()
            if (exists) {
                db.execSQL(
                    "UPDATE wrong_item SET wrong_count = wrong_count + 1, streak=0, mastered=0, last_wrong_at=?, answer=? WHERE id=?",
                    arrayOf<Any>(now, q.answerText, id)
                )
            } else {
                val cv = ContentValues().apply {
                    put("stage_id", stageId)
                    put("expr_key", key)
                    put("expr", q.text)
                    put("answer", q.answerText)
                    put("wrong_count", 1)
                    put("streak", 0)
                    put("last_wrong_at", now)
                    put("mastered", 0)
                    put("tag", q.tag)
                }
                db.insert("wrong_item", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 错题答对一次：连续答对 2 次就算「毕业」 */
    fun markWrongRight(stageId: String, q: Question) {
        val db = helper.writableDatabase
        db.execSQL(
            "UPDATE wrong_item SET streak = streak + 1, mastered = CASE WHEN streak + 1 >= 2 THEN 1 ELSE 0 END WHERE stage_id=? AND expr_key=?",
            arrayOf<Any>(stageId, q.text)
        )
    }

    fun wrongItems(stageId: String? = null, includeMastered: Boolean = false): List<WrongEntry> {
        val where = StringBuilder("WHERE 1=1")
        val args = mutableListOf<String>()
        if (stageId != null) {
            where.append(" AND stage_id=?")
            args.add(stageId)
        }
        if (!includeMastered) where.append(" AND mastered=0")
        val sql = "SELECT id, stage_id, expr, answer, wrong_count, streak, mastered, tag FROM wrong_item $where " +
            "ORDER BY mastered ASC, wrong_count DESC, last_wrong_at DESC LIMIT 80"
        val list = mutableListOf<WrongEntry>()
        helper.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                list.add(
                    WrongEntry(
                        id = c.getLong(0),
                        stageId = c.getString(1),
                        expr = c.getString(2),
                        answer = c.getString(3),
                        wrongCount = c.getInt(4),
                        streak = c.getInt(5),
                        mastered = c.getInt(6) == 1,
                        tag = c.getString(7) ?: "错题"
                    )
                )
            }
        }
        return list
    }

    fun wrongCount(stageId: String? = null): Int {
        val sql = if (stageId == null)
            "SELECT COUNT(*) FROM wrong_item WHERE mastered=0"
        else
            "SELECT COUNT(*) FROM wrong_item WHERE mastered=0 AND stage_id=?"
        val args = if (stageId == null) null else arrayOf(stageId)
        helper.readableDatabase.rawQuery(sql, args).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** 已经连续答对 2 次、从错题本毕业的数量 */
    fun masteredCount(): Int {
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM wrong_item WHERE mastered=1", null
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** 按 id 取一条错题（错题本里「只练这道」用） */
    fun wrongItem(id: Long): WrongEntry? {
        helper.readableDatabase.rawQuery(
            "SELECT id, stage_id, expr, answer, wrong_count, streak, mastered, tag " +
                "FROM wrong_item WHERE id=?",
            arrayOf(id.toString())
        ).use { c ->
            if (!c.moveToFirst()) return null
            return WrongEntry(
                id = c.getLong(0),
                stageId = c.getString(1),
                expr = c.getString(2),
                answer = c.getString(3),
                wrongCount = c.getInt(4),
                streak = c.getInt(5),
                mastered = c.getInt(6) == 1,
                tag = c.getString(7) ?: "错题"
            )
        }
    }

    fun wrongQuestionById(id: Long): Question? = wrongItem(id)?.let { questionOf(it) }

    /**
     * 考试 / 挑战的出题：先把这个阶段最常错的题塞进去（最多 [share] 比例），
     * 剩下的再正常出。考的和练的一样、错的还是那些，才叫「对症」。
     */
    fun mixedQuestions(
        stage: StageConfig,
        style: QuestionStyle,
        count: Int,
        share: Double = 0.4,
        rnd: kotlin.random.Random = kotlin.random.Random.Default
    ): List<Question> {
        val wrong = try {
            wrongQuestions(stage.id, count)
        } catch (_: Throwable) {
            emptyList()
        }
        val take = (count * share).toInt().coerceIn(0, wrong.size)
        val out = wrong.shuffled(rnd).take(take).toMutableList()
        val seen = out.map { it.text }.toHashSet()
        var guard = 0
        while (out.size < count && guard < count * 60) {
            guard++
            val q = Generator.make(stage, style, rnd)
            if (seen.add(q.text)) out.add(q)
        }
        while (out.size < count) {
            val q = Generator.make(stage, style, rnd)
            if (seen.add(q.text)) out.add(q)
        }
        // 打散：别让常错题全挤在开头，孩子一眼看穿
        return out.shuffled(rnd)
    }

    /**
     * 某阶段的战绩明细（家长要看的那种）：
     * 练了几次、考了几次、挑了几次；最好/最差/平均分；每题最快/最慢/平均毫秒。
     */
    fun stageReport(stageId: String): StageReport {
        val db = helper.readableDatabase
        var practice = 0
        var exam = 0
        var challenge = 0
        var best = 0.0
        var worst = 0.0
        var scoreSum = 0.0
        var scoreN = 0
        db.rawQuery(
            "SELECT mode, COUNT(*), MIN(score), MAX(score), AVG(score) FROM session " +
                "WHERE finished=1 AND stage_id=? AND total>0 GROUP BY mode",
            arrayOf(stageId)
        ).use { c ->
            while (c.moveToNext()) {
                when (c.getString(0)) {
                    "practice", "timed", "wrongbook" -> practice += c.getInt(1)
                    "exam" -> exam += c.getInt(1)
                    "challenge" -> challenge += c.getInt(1)
                }
                if (c.getInt(1) > 0) {
                    val lo = c.getDouble(2)   // 该模式最低分
                    val hi = c.getDouble(3)   // 该模式最高分
                    val avg = c.getDouble(4)
                    if (scoreN == 0 || hi > best) best = hi
                    if (scoreN == 0 || lo < worst) worst = lo
                    scoreSum += avg * c.getInt(1)
                    scoreN += c.getInt(1)
                }
            }
        }
        var fast = 0L
        var slow = 0L
        var msAvg = 0L
        var tries = 0
        db.rawQuery(
            "SELECT MIN(a.ms), MAX(a.ms), AVG(a.ms), COUNT(*) FROM attempt a " +
                "JOIN session s ON a.session_id = s.id WHERE s.stage_id=?",
            arrayOf(stageId)
        ).use { c ->
            if (c.moveToFirst() && c.getInt(3) > 0) {
                fast = c.getLong(0)
                slow = c.getLong(1)
                msAvg = c.getDouble(2).toLong()
                tries = c.getInt(3)
            }
        }
        return StageReport(
            practiceRounds = practice,
            examRounds = exam,
            challengeRounds = challenge,
            bestScore = if (scoreN == 0) 0.0 else best,
            worstScore = if (scoreN == 0) 0.0 else worst,
            avgScore = if (scoreN == 0) 0.0 else scoreSum / scoreN,
            fastestMs = fast,
            slowestMs = slow,
            avgMs = msAvg,
            attempts = tries
        )
    }

    /** 从错题本里抽题用于重练 */
    fun wrongQuestions(stageId: String? = null, limit: Int = 30): List<Question> {
        return wrongItems(stageId, includeMastered = false).take(limit).map { questionOf(it) }
    }

    /**
     * 错题条目 → 可直接出题的 Question。
     *
     * 分数题必须把分母带回来（答案存的是 "5/6"），否则答案框不会显示 ?/6，
     * 孩子不知道该填分子——这是错题重练「看着能点但没法答」的元凶。
     */
    fun questionOf(e: WrongEntry): Question {
        val den = e.answer.substringAfter("/", "").toIntOrNull() ?: 0
        val num = e.answer.substringBefore("/").toIntOrNull() ?: 0
        val isFraction = den > 0
        val speak = Speech.expression(e.expr) + if (isFraction) "，答案只填分子" else ""
        return Question(
            text = e.expr,
            answer = num,
            tag = e.tag,
            answerText = e.answer,
            speak = speak,
            hint = Speech.hintForTag(e.tag),
            speakHint = Speech.hintForTag(e.tag),
            // 填空题的题面也带等号（6 + □ = 9），但它不是找错题
            findError = e.expr.contains("=") && !e.expr.contains("□"),
            answerDen = if (isFraction) den else 0,
            stageId = e.stageId
        )
    }

    // ------------------------------------------------------------ 统计

    fun overall(
        stageId: String? = null,
        excludeChallenge: Boolean = true,
        excludeWrongBook: Boolean = false
    ): Overall {
        val conds = mutableListOf("finished=1")
        val args = mutableListOf<String>()
        if (stageId != null) {
            conds.add("stage_id=?")
            args.add(stageId)
        }
        // 挑战是抢时间，正确率天然偏低，混进算力会失真，所以默认排除
        if (excludeChallenge) conds.add("mode != 'challenge'")
        // 解锁判定用：错题重练反复刷同一批题，正确率虚高，不该拿来解锁下一阶段
        if (excludeWrongBook) conds.add("mode != 'wrongbook'")
        val where = "WHERE " + conds.joinToString(" AND ")
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*), IFNULL(SUM(total),0), IFNULL(SUM(correct_first),0), IFNULL(SUM(duration_ms),0) FROM session $where",
            args.toTypedArray()
        ).use { c ->
            if (c.moveToFirst()) {
                return Overall(c.getInt(0), c.getInt(1), c.getInt(2), c.getLong(3))
            }
        }
        return Overall()
    }

    /**
     * 一次查出所有难度的累计成绩（key = stage_id）。
     *
     * 首页等级、难度地图都要「每个难度各自的战绩」，以前是循环 12 次 [overall]，
     * 每次一趟 SQL；这里一次 GROUP BY 拿全，顺带避免在 UI 线程上反复查库。
     * 口径和 [overall] 完全一致：排除挑战（抢时间，正确率天然偏低）、排除错题重练（刷同一批题虚高）。
     */
    fun overallByStage(): Map<String, Overall> {
        val out = LinkedHashMap<String, Overall>()
        helper.readableDatabase.rawQuery(
            "SELECT stage_id, COUNT(*), IFNULL(SUM(total),0), " +
                "IFNULL(SUM(correct_first),0), IFNULL(SUM(duration_ms),0) " +
                "FROM session WHERE finished=1 AND mode != 'challenge' AND mode != 'wrongbook' " +
                "GROUP BY stage_id",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out[c.getString(0)] = Overall(c.getInt(1), c.getInt(2), c.getInt(3), c.getLong(4))
            }
        }
        return out
    }

    fun today(): TodaySummary {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        helper.readableDatabase.rawQuery(
            """
            SELECT COUNT(*), IFNULL(SUM(total),0), IFNULL(SUM(correct_first),0), IFNULL(SUM(duration_ms),0)
            FROM session
            WHERE finished=1 AND mode != 'challenge'
              AND date(started_at/1000, 'unixepoch', 'localtime') = ?
            """.trimIndent(),
            arrayOf(today)
        ).use { c ->
            if (c.moveToFirst()) return TodaySummary(c.getInt(0), c.getInt(1), c.getInt(2), c.getLong(3))
        }
        return TodaySummary(0, 0, 0, 0)
    }

    /** 最近 N 天的每日统计（没有练习的日子补 0，图表才连续） */
    fun dailyStats(days: Int): List<DailyPoint> {
        val since = System.currentTimeMillis() - days * 24L * 3600 * 1000
        val map = LinkedHashMap<String, DailyPoint>()
        helper.readableDatabase.rawQuery(
            """
            SELECT date(started_at/1000, 'unixepoch', 'localtime') AS day,
                   SUM(total), SUM(correct_first), SUM(duration_ms)
            FROM session
            WHERE finished=1 AND mode != 'challenge' AND started_at >= ?
            GROUP BY day ORDER BY day
            """.trimIndent(),
            arrayOf(since.toString())
        ).use { c ->
            while (c.moveToNext()) {
                map[c.getString(0)] = DailyPoint(c.getString(0), c.getInt(1), c.getInt(2), c.getLong(3))
            }
        }
        val out = mutableListOf<DailyPoint>()
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, -(days - 1))
        repeat(days) {
            val key = fmt.format(cal.time)
            out.add(map[key] ?: DailyPoint(key, 0, 0, 0))
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return out
    }

    fun tagStats(stageId: String? = null): List<TagStat> {
        val where = if (stageId == null) "" else "AND s.stage_id=?"
        val args = if (stageId == null) null else arrayOf(stageId)
        val list = mutableListOf<TagStat>()
        helper.readableDatabase.rawQuery(
            """
            SELECT a.tag, COUNT(*), SUM(a.first_ok)
            FROM attempt a JOIN session s ON a.session_id = s.id
            WHERE s.finished=1 AND s.mode != 'challenge' $where
            GROUP BY a.tag ORDER BY COUNT(*) DESC
            """.trimIndent(),
            args
        ).use { c ->
            while (c.moveToNext()) list.add(TagStat(c.getString(0), c.getInt(1), c.getInt(2)))
        }
        return list
    }

    
    /**
     * 挑战状态：解锁、剩余次数、电脑速度。
     *
     * 规则（2026-10 最终定稿）：
     * 1. 该阶段每 [CHALLENGE_PERFECT_PER_CREDIT] 场「满分考试」（每场 ≥5 题）换 1 次挑战；
     *    也就是 2 场满分 = 1 次，攒着不清零；
     * 2. 每开一局挑战消耗 1 次（挑战场次本身也落一条 session，天然计数），
     *    只统计「解锁那一刻之后」的挑战，老版本打的不会被倒扣；
     * 3. 电脑速度 = 该阶段「最快的 5 场考试」的平均每题用时 × 0.8，
     *    优先在正确率 ≥80% 的场次里挑（防止一口气乱点把基准拉飞），不够 5 场才放宽到全部考试。
     */
    fun challengeStatus(stage: StageConfig): ChallengeStatus {
        val db = helper.readableDatabase
        var exams = 0
        var perfect = 0
        db.rawQuery(
            "SELECT COUNT(*), SUM(CASE WHEN correct_first>=total THEN 1 ELSE 0 END) FROM session " +
                "WHERE finished=1 AND stage_id=? AND mode='exam' AND total>=5",
            arrayOf(stage.id)
        ).use { c ->
            if (c.moveToFirst()) {
                exams = c.getInt(0)
                perfect = c.getInt(1)
            }
        }
        // 「解锁时刻」= 凑够第一次兑换的那场满分考试。之后的挑战才算花掉次数，
        // 老版本打过的挑战不追究，否则升级后直接倒欠。
        val needed = CHALLENGE_PERFECT_PER_CREDIT
        val unlockAt = if (perfect < needed) null else db.rawQuery(
            "SELECT started_at FROM session WHERE finished=1 AND stage_id=? AND mode='exam' " +
                "AND total>=5 AND correct_first>=total ORDER BY started_at ASC LIMIT 1 OFFSET ?",
            arrayOf(stage.id, (needed - 1).toString())
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

        val startsAfter = if (unlockAt == null) 0 else db.rawQuery(
            "SELECT COUNT(*) FROM session WHERE stage_id=? AND mode='challenge' " +
                "AND started_at>? AND total>0",
            arrayOf(stage.id, unlockAt.toString())
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

        val unlocked = unlockAt != null
        val credits = if (!unlocked) 0 else ChallengeRules.credits(perfect, startsAfter)

        val rows = ArrayList<Triple<Long, Int, Int>>()   // duration, total, correct
        db.rawQuery(
            "SELECT duration_ms, total, correct_first FROM session " +
                "WHERE finished=1 AND stage_id=? AND mode='exam' AND total>=5 " +
                "ORDER BY (duration_ms * 1.0 / total) ASC LIMIT 60",
            arrayOf(stage.id)
        ).use { c ->
            while (c.moveToNext()) rows.add(Triple(c.getLong(0), c.getInt(1), c.getInt(2)))
        }
        val good = rows.filter { it.third * 100 >= it.second * 80 }
        val pick = (if (good.size >= 5) good else rows).take(5)
        val computerMs = if (pick.isEmpty()) null else {
            val totalMs = pick.sumOf { it.first }
            val totalQ = pick.sumOf { it.second }.coerceAtLeast(1)
            val avg = totalMs / totalQ
            val minMs = (stage.targetSeconds * 400L).coerceAtLeast(1500L)
            (avg * 0.8).toLong().coerceIn(minMs, 20000L)
        }
        return ChallengeStatus(perfect, exams, credits, computerMs)
    }

    
    // ------------------------------------------------------------ 成就用的聚合

    fun perfectSessionCount(): Int = singleInt(
        "SELECT COUNT(*) FROM session WHERE finished=1 AND mode!='challenge' AND total>=5 AND correct_first=total"
    )

    fun examPerfectCount(): Int = singleInt(
        "SELECT COUNT(*) FROM session WHERE finished=1 AND mode='exam' AND total>=10 AND correct_first=total"
    )

    fun challengeWinCount(): Int = singleInt(
        "SELECT COUNT(*) FROM session WHERE finished=1 AND mode='challenge' AND correct_first*2 > total"
    )

    /** 全对场次里最快的平均每题用时（毫秒） */
    fun fastestQuestionMs(): Long? {
        helper.readableDatabase.rawQuery(
            """
            SELECT MIN(duration_ms * 1.0 / total) FROM session
            WHERE finished=1 AND mode!='challenge' AND total>=5 AND correct_first=total
            """.trimIndent(), null
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) {
                val v = c.getDouble(0)
                if (v > 0) return v.toLong()
            }
        }
        return null
    }

    private fun singleInt(sql: String): Int {
        helper.readableDatabase.rawQuery(sql, null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    // ------------------------------------------------------------ 每日挑战

    fun saveDaily(day: String, stageId: String, total: Int, correct: Int) {
        val db = helper.writableDatabase
        val exists = db.rawQuery("SELECT day FROM daily WHERE day=?", arrayOf(day)).use { it.moveToFirst() }
        if (exists) {
            // 同一天重挑战：只保留更好的成绩
            db.execSQL(
                "UPDATE daily SET total=?, correct=?, stage_id=?, finished_at=? WHERE day=? AND correct < ?",
                arrayOf<Any>(total, correct, stageId, System.currentTimeMillis(), day, correct)
            )
        } else {
            val cv = ContentValues().apply {
                put("day", day)
                put("stage_id", stageId)
                put("total", total)
                put("correct", correct)
                put("finished_at", System.currentTimeMillis())
            }
            db.insert("daily", null, cv)
        }
    }

    fun dailyRecord(day: String): DailyRecord? {
        helper.readableDatabase.rawQuery(
            "SELECT day, stage_id, total, correct FROM daily WHERE day=?", arrayOf(day)
        ).use { c ->
            if (c.moveToFirst()) {
                return DailyRecord(c.getString(0), c.getString(1), c.getInt(2), c.getInt(3))
            }
        }
        return null
    }

    /** 连续「全对通关」的天数（今天还没通关不算断） */
    fun dailyStreak(): Int {
        val fullDays = mutableSetOf<String>()
        helper.readableDatabase.rawQuery(
            "SELECT day FROM daily WHERE total>0 AND correct=total ORDER BY day DESC LIMIT 90", null
        ).use { c ->
            while (c.moveToNext()) fullDays.add(c.getString(0))
        }
        if (fullDays.isEmpty()) return 0
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val cal = java.util.Calendar.getInstance()
        var streak = 0
        for (i in 0 until 90) {
            val key = fmt.format(cal.time)
            if (fullDays.contains(key)) {
                streak++
            } else if (i > 0) {
                break
            }
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        }
        return streak
    }

    companion object {
        /** 几场「满分考试」换 1 次挑战（2 场满分 = 1 次，2026-10 用户要求） */
        const val CHALLENGE_PERFECT_PER_CREDIT = 2

        /** 兼容旧叫法：解锁需要的满分考试场次 */
        const val CHALLENGE_FULL_ROUNDS = CHALLENGE_PERFECT_PER_CREDIT
    }

    fun clearAll() {
        val db = helper.writableDatabase
        listOf("attempt", "session", "wrong_item", "daily", "sync_queue").forEach {
            db.execSQL("DELETE FROM $it")
        }
    }
}

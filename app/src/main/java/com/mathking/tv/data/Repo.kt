package com.mathking.tv.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 数据访问层。所有读写都丢到单线程队列，UI 线程只处理回调。
 */
class Repo(context: Context) {

    private val helper = DbHelper(context.applicationContext)
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

    /** 从错题本里抽一道题用于重练 */
    fun wrongQuestions(stageId: String? = null, limit: Int = 30): List<Question> {
        return wrongItems(stageId, includeMastered = false).take(limit).map { e ->
            Question(
                text = e.expr,
                answer = e.answer.substringBefore("/").toIntOrNull() ?: 0,
                tag = e.tag,
                answerText = e.answer,
                speak = toSpeak(e.expr),
                findError = e.expr.contains("=")
            )
        }
    }

    private fun toSpeak(expr: String): String {
        // 把算式转成中文读法；找错题（含 =）读成「… 这道题有错误，正确答案是几」
        val hasEq = expr.contains("=")
        var s = expr.substringBefore("=")
            .replace("+", " 加 ").replace("−", " 减 ")
            .replace("×", " 乘 ").replace("÷", " 除以 ")
        s = Regex("(\\d+)/(\\d+)").replace(s) { m ->
            "${Generator.cn(m.groupValues[2].toInt())}分之${Generator.cn(m.groupValues[1].toInt())}"
        }
        s = s.replace(Regex("\\s+"), " ").trim()
        return if (hasEq) "$s 这道题有错误 正确答案是几" else "$s 等于几"
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
     * 挑战模式基准：最近 5 场「全对、≥5 题、非挑战」场次的加权平均每题用时。
     * 不足 5 场返回 null —— 也就是必须攒够 5 次满分才能挑战。
     * 用多场平均而不是单场最快，避免某一局抽到简单题把电脑拉得过强。
     */
    fun challengeBase(stageId: String): Pair<Long?, Int> {
        var totalMs = 0L
        var totalQ = 0
        var rounds = 0
        helper.readableDatabase.rawQuery(
            """
            SELECT duration_ms, total FROM session
            WHERE finished=1 AND stage_id=? AND total>=5
              AND correct_first = total AND mode != 'challenge'
            ORDER BY started_at DESC LIMIT ?
            """.trimIndent(),
            arrayOf(stageId, CHALLENGE_FULL_ROUNDS.toString())
        ).use { c ->
            while (c.moveToNext()) {
                totalMs += c.getLong(0)
                totalQ += c.getInt(1)
                rounds++
            }
        }
        if (rounds < CHALLENGE_FULL_ROUNDS || totalQ <= 0) return null to rounds
        return (totalMs / totalQ) to rounds
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
        /** 挑战模式解锁需要的满分场次数 */
        const val CHALLENGE_FULL_ROUNDS = 5
    }

    fun clearAll() {
        val db = helper.writableDatabase
        listOf("attempt", "session", "wrong_item", "daily", "sync_queue").forEach {
            db.execSQL("DELETE FROM $it")
        }
    }
}

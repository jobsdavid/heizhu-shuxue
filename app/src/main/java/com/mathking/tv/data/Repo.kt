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
        val sql = "SELECT id, stage_id, expr, answer, wrong_count, streak, mastered FROM wrong_item $where " +
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
                        mastered = c.getInt(6) == 1
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

    /** 从错题本里抽一道题用于重练 */
    fun wrongQuestions(stageId: String? = null, limit: Int = 30): List<Question> {
        return wrongItems(stageId, includeMastered = false).take(limit).map { e ->
            Question(
                text = e.expr,
                answer = e.answer.substringBefore("/").toIntOrNull() ?: 0,
                tag = "错题重练",
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

    fun overall(stageId: String? = null): Overall {
        val where = if (stageId == null) "WHERE finished=1" else "WHERE finished=1 AND stage_id=?"
        val args = if (stageId == null) null else arrayOf(stageId)
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*), IFNULL(SUM(total),0), IFNULL(SUM(correct_first),0), IFNULL(SUM(duration_ms),0) FROM session $where",
            args
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
            WHERE finished=1 AND date(started_at/1000, 'unixepoch', 'localtime') = ?
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
            WHERE finished=1 AND started_at >= ?
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
            WHERE s.finished=1 $where
            GROUP BY a.tag ORDER BY COUNT(*) DESC
            """.trimIndent(),
            args
        ).use { c ->
            while (c.moveToNext()) list.add(TagStat(c.getString(0), c.getInt(1), c.getInt(2)))
        }
        return list
    }

    fun recentSessions(limit: Int = 10): List<Pair<Long, Double>> {
        val list = mutableListOf<Pair<Long, Double>>()
        helper.readableDatabase.rawQuery(
            "SELECT started_at, score FROM session WHERE finished=1 ORDER BY started_at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) list.add(c.getLong(0) to c.getDouble(1))
        }
        return list.reversed()
    }

    /**
     * 挑战模式的基准：该阶段「正确率 ≥ 80% 且至少 5 题」的场次里，
     * 平均每题用时最快的一次（毫秒）。没有达标记录就返回 null，挑战模式不解锁。
     */
    fun bestPaceMs(stageId: String): Long? {
        helper.readableDatabase.rawQuery(
            """
            SELECT MIN(duration_ms * 1.0 / total)
            FROM session
            WHERE finished=1 AND stage_id=? AND total>=5
              AND correct_first * 1.0 / total >= 0.8
            """.trimIndent(),
            arrayOf(stageId)
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) {
                val v = c.getDouble(0)
                if (v > 0) return v.toLong()
            }
        }
        return null
    }

    fun bestScore(stageId: String): Double {
        helper.readableDatabase.rawQuery(
            "SELECT IFNULL(MAX(score),0) FROM session WHERE finished=1 AND stage_id=?",
            arrayOf(stageId)
        ).use { c -> return if (c.moveToFirst()) c.getDouble(0) else 0.0 }
    }

    fun clearAll() {
        val db = helper.writableDatabase
        listOf("attempt", "session", "wrong_item", "sync_queue").forEach {
            db.execSQL("DELETE FROM $it")
        }
    }
}

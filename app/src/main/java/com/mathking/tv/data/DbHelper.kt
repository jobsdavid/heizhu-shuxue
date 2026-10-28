package com.mathking.tv.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 本地数据库：全部练习记录、错题、对战同步队列。
 * 直接用 SQLiteOpenHelper 写 SQL，不加注解处理器，启动快、依赖少。
 */
class DbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE session (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                stage_id TEXT NOT NULL,
                mode TEXT NOT NULL,
                planned INTEGER NOT NULL,
                total INTEGER NOT NULL DEFAULT 0,
                correct_first INTEGER NOT NULL DEFAULT 0,
                wrong INTEGER NOT NULL DEFAULT 0,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                score REAL NOT NULL DEFAULT 0,
                power REAL NOT NULL DEFAULT 0,
                finished INTEGER NOT NULL DEFAULT 0,
                player_id TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_session_started ON session(started_at)")
        db.execSQL("CREATE INDEX idx_session_stage ON session(stage_id)")

        db.execSQL(
            """
            CREATE TABLE attempt (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                idx INTEGER NOT NULL,
                expr TEXT NOT NULL,
                answer TEXT NOT NULL,
                user_answer TEXT,
                first_ok INTEGER NOT NULL,
                attempts INTEGER NOT NULL,
                ms INTEGER NOT NULL,
                tag TEXT NOT NULL,
                timed_out INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_attempt_session ON attempt(session_id)")
        db.execSQL("CREATE INDEX idx_attempt_tag ON attempt(tag)")

        db.execSQL(
            """
            CREATE TABLE wrong_item (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                stage_id TEXT NOT NULL,
                expr_key TEXT NOT NULL,
                expr TEXT NOT NULL,
                answer TEXT NOT NULL,
                wrong_count INTEGER NOT NULL DEFAULT 1,
                streak INTEGER NOT NULL DEFAULT 0,
                last_wrong_at INTEGER NOT NULL,
                mastered INTEGER NOT NULL DEFAULT 0,
                tag TEXT NOT NULL DEFAULT '错题',
                UNIQUE(stage_id, expr_key)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE daily (
                day TEXT PRIMARY KEY,
                stage_id TEXT NOT NULL,
                total INTEGER NOT NULL,
                correct INTEGER NOT NULL,
                finished_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE sync_queue (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                payload TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                synced INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 逐级迁移，绝不 DROP，避免丢孩子的数据
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE wrong_item ADD COLUMN tag TEXT NOT NULL DEFAULT '错题'")
        }
        if (oldVersion < 4) {
            // 阶段体系重构（2026-10）：旧 s8/s9/s10 换了位置，历史记录跟着搬。
            // 从大到小改，避免搬完又被下一条重复搬走。
            for ((from, to) in StageCatalog.legacyRemap.entries.sortedByDescending { it.key }) {
                db.execSQL("UPDATE session SET stage_id=? WHERE stage_id=?", arrayOf(to, from))
                db.execSQL("UPDATE wrong_item SET stage_id=? WHERE stage_id=?", arrayOf(to, from))
                db.execSQL("UPDATE daily SET stage_id=? WHERE stage_id=?", arrayOf(to, from))
            }
        }
        if (oldVersion < 3) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS daily (
                    day TEXT PRIMARY KEY,
                    stage_id TEXT NOT NULL,
                    total INTEGER NOT NULL,
                    correct INTEGER NOT NULL,
                    finished_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    companion object {
        const val DB_NAME = "mathking.db"
        const val DB_VERSION = 4
    }
}

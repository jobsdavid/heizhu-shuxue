package com.mathking.tv

import android.app.Application
import android.content.Context
import com.mathking.tv.audio.Sfx
import com.mathking.tv.audio.Speaker
import com.mathking.tv.data.Repo
import com.mathking.tv.data.Settings
import com.mathking.tv.data.UserStore
import com.mathking.tv.data.prefsNameOf

/**
 * 全局单例。
 *
 * 多用户：一台电视可以建多个孩子，**每人一个 SQLite 库文件 + 一份 SharedPreferences**，
 * 数据物理隔离（切换用户 = 换一套 repo/settings），不会出现「你看到我的记录」。
 * 花名册在 [UserStore]，老版本的单用户数据会在首次启动时搬进 1 号用户。
 */
class App : Application() {

    lateinit var repo: Repo
        private set

    lateinit var settings: Settings
        private set

    lateinit var speaker: Speaker
        private set

    lateinit var users: UserStore
        private set

    override fun onCreate() {
        super.onCreate()
        users = UserStore(this)
        migrateSingleProfileIfNeeded()
        bindUser(users.currentId)
        speaker = Speaker(this)
        speaker.init()
        Sfx.enabled = settings.soundOn
        speaker.enabled = settings.speakOn
    }

    /** 切换孩子：换库、换偏好，语音先停掉（别念上一个孩子的题） */
    fun switchUser(id: Long) {
        if (users.users().none { it.id == id }) return
        users.currentId = id
        speaker.stop()
        bindUser(id)
        Sfx.enabled = settings.soundOn
        speaker.enabled = settings.speakOn
    }

    /** 新建一个孩子并切过去，返回新用户 id */
    fun addUser(name: String): Long {
        val u = users.add(name)
        switchUser(u.id)
        return u.id
    }

    private fun bindUser(id: Long) {
        val uid = if (id > 0) id else (users.users().firstOrNull()?.id ?: 1L)
        settings = Settings(this, prefsNameOf(uid))
        repo = Repo(this, uid)
    }

    /**
     * 老版本只有一份数据：把它当成 1 号用户，库文件复制一份（原文件保留做保险），
     * 偏好整份复制过去。只做一次——花名册非空就跳过。
     */
    private fun migrateSingleProfileIfNeeded() {
        if (users.users().isNotEmpty()) return
        val legacy = getSharedPreferences("mathking", MODE_PRIVATE)
        val name = legacy.getString("user_name", "").orEmpty()
        val avatar = legacy.getString("avatar", "🐷") ?: "🐷"
        val img = legacy.getString("avatar_image", "").orEmpty()
        users.add(name.ifBlank { "小朋友" }, avatar, img, id = 1L)

        try {
            val old = getDatabasePath("mathking.db")
            val new = getDatabasePath("mathking_u1.db")
            if (old.exists() && !new.exists()) old.copyTo(new, overwrite = false)
        } catch (_: Throwable) {
        }

        val editor = getSharedPreferences(prefsNameOf(1L), MODE_PRIVATE).edit()
        legacy.all.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
            }
        }
        editor.apply()
    }

    override fun onTerminate() {
        speaker.shutdown()
        super.onTerminate()
    }

    companion object {
        fun of(context: Context): App = context.applicationContext as App
    }
}

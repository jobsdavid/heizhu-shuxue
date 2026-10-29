package com.mathking.tv.data

import android.content.Context
import android.content.SharedPreferences

/** 电视上的一个孩子（一台电视可以建多个，数据完全隔离：每人一个库文件 + 一份偏好） */
data class TvUser(
    val id: Long,
    val name: String,
    val avatar: String = "🐷",
    val avatarImage: String = ""
) {
    val prefsName: String get() = prefsNameOf(id)
    val dbName: String get() = dbNameOf(id)
}

fun prefsNameOf(id: Long): String = "mathking_u$id"
fun dbNameOf(id: Long): String = "mathking_u$id.db"

/**
 * 用户花名册。只记「有谁、当前是谁、各自头像名字」，
 * 练习数据在各人的 `mathking_u<id>.db` 与 `mathking_u<id>` 偏好里，互不可见。
 */
class UserStore(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(KEYS, Context.MODE_PRIVATE)

    fun users(): List<TvUser> =
        (sp.getString(KEY_IDS, "") ?: "")
            .split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .map { id ->
                val parts = (sp.getString(keyOf(id), "") ?: "").split(SEP)
                TvUser(
                    id = id,
                    name = parts.getOrNull(0).orEmpty().ifBlank { "小朋友" },
                    avatar = parts.getOrNull(1).orEmpty().ifBlank { "🐷" },
                    avatarImage = parts.getOrNull(2).orEmpty()
                )
            }

    var currentId: Long
        get() {
            val saved = sp.getLong(KEY_CURRENT, 0L)
            val all = users()
            if (saved > 0 && all.any { it.id == saved }) return saved
            return all.firstOrNull()?.id ?: 0L
        }
        set(value) = sp.edit().putLong(KEY_CURRENT, value).apply()

    fun current(): TvUser? =
        users().firstOrNull { it.id == currentId } ?: users().firstOrNull()

    fun add(name: String, avatar: String = "🐷", avatarImage: String = "", id: Long = 0L): TvUser {
        val newId = if (id > 0) id else (users().maxOfOrNull { it.id } ?: 0L) + 1
        val user = TvUser(newId, name.ifBlank { "小朋友" }, avatar, avatarImage)
        val ids = (users().map { it.id } + newId).distinct().sorted()
        sp.edit()
            .putString(KEY_IDS, ids.joinToString(","))
            .putString(keyOf(newId), listOf(user.name, user.avatar, user.avatarImage).joinToString(SEP))
            .putLong(KEY_CURRENT, newId)
            .apply()
        return user
    }

    fun update(user: TvUser) {
        sp.edit()
            .putString(keyOf(user.id), listOf(user.name, user.avatar, user.avatarImage).joinToString(SEP))
            .apply()
    }

    private fun keyOf(id: Long) = "u$id"

    companion object {
        private const val KEYS = "mathking_users"
        private const val KEY_IDS = "ids"
        private const val KEY_CURRENT = "current"

        /** 分隔符用不可见字符，名字里出现逗号也不会串 */
        private const val SEP = "\u0001"
    }
}

package com.mathking.tv.util

import java.util.Locale

object Fmt {

    fun duration(ms: Long): String {
        val total = ms / 1000.0
        return when {
            total < 60 -> "${total.toInt()} 秒"
            total < 3600 -> {
                val m = (total / 60).toInt()
                val s = (total % 60).toInt()
                if (s == 0) "$m 分" else "$m 分 $s 秒"
            }
            else -> {
                val h = (total / 3600).toInt()
                val m = ((total % 3600) / 60).toInt()
                "$h 小时 $m 分"
            }
        }
    }

    fun sec(value: Double): String = String.format(Locale.US, "%.1f", value)

    fun pct(value: Double): String = String.format(Locale.US, "%.0f%%", value)

    fun pct1(value: Double): String = String.format(Locale.US, "%.1f%%", value)

    fun score(value: Double): String =
        if (value >= 99.95) "100" else String.format(Locale.US, "%.0f", value)
}

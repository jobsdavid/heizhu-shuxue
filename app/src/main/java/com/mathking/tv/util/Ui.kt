package com.mathking.tv.util

import android.content.Context
import android.view.View
import androidx.core.content.ContextCompat

object Ui {
    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    /** 给一组 View 加焦点放大效果，电视上看得出来选中了哪个 */
    fun focusZoom(vararg views: View, scale: Float = 1.05f) {
        views.forEach { v ->
            v.setOnFocusChangeListener { view, hasFocus ->
                // 只做轻微放大：不要 elevation，阴影层会让放大后的边框被父容器裁掉
                view.animate()
                    .scaleX(if (hasFocus) scale else 1f)
                    .scaleY(if (hasFocus) scale else 1f)
                    .setDuration(120)
                    .start()
            }
        }
    }

    fun color(context: Context, res: Int): Int = ContextCompat.getColor(context, res)
}

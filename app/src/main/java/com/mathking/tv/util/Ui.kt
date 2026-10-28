package com.mathking.tv.util

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat

object Ui {
    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    /** 给一组 View 加焦点放大效果，电视上看得出来选中了哪个 */
    fun focusZoom(vararg views: View, scale: Float = 1.07f) {
        views.forEach { v ->
            v.setOnFocusChangeListener { view, hasFocus ->
                view.animate()
                    .scaleX(if (hasFocus) scale else 1f)
                    .scaleY(if (hasFocus) scale else 1f)
                    .setDuration(120)
                    .start()
                // 抬升一点，电视上焦点框阴影更明显（API 21+）
                view.elevation = if (hasFocus) 16f * view.resources.displayMetrics.density else 0f
            }
        }
    }

    fun color(context: Context, res: Int): Int = ContextCompat.getColor(context, res)

    fun setTextColor(view: TextView, colorRes: Int) {
        view.setTextColor(ContextCompat.getColor(view.context, colorRes))
    }
}

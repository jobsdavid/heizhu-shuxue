package com.mathking.tv.util

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
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

    /**
     * 换题翻页：新内容从右边滑进来 + 淡入。
     * 只动被传入的几块，别整页平移，电视上看整页动会晕。
     */
    fun pageTurn(vararg views: View, fromDp: Float = 80f) {
        views.forEach { v ->
            v.animate().cancel()
            v.alpha = 0f
            v.translationX = dp(v.context, fromDp).toFloat()
            v.scaleX = 0.94f
            v.scaleY = 0.94f
            v.animate()
                .alpha(1f)
                .translationX(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(230)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /** 焦点放大时边框被父容器裁掉，页面根布局和按钮行都要关掉裁剪 */
    fun noClip(vararg views: View) {
        views.forEach { v ->
            if (v is ViewGroup) {
                v.clipChildren = false
                v.clipToPadding = false
            }
        }
    }

    /**
     * 答对了轻轻「弹」一下。
     * 用 bouncy 插值器从 0.92 弹回 1.0，孩子余光都能感觉到「这题对了」。
     */
    fun pop(vararg views: View) {
        views.forEach { v ->
            v.animate().cancel()
            v.scaleX = 0.92f
            v.scaleY = 0.92f
            v.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(240)
                .setInterpolator(OvershootInterpolator(2.6f))
                .start()
        }
    }

    /**
     * 答错了左右轻晃一下（配合红框）。
     * 只晃 8dp、320ms：电视上看是「抖了一下」，不是「整页在抖」。
     */
    fun shake(vararg views: View) {
        views.forEach { v ->
            val d = dp(v.context, 8f).toFloat()
            v.animate().cancel()
            v.translationX = 0f
            v.animate()
                .translationXBy(d)
                .setDuration(70)
                .withEndAction {
                    v.animate().translationXBy(-2 * d).setDuration(110).withEndAction {
                        v.animate().translationXBy(d).setDuration(80).withEndAction {
                            v.translationX = 0f
                        }.start()
                    }.start()
                }
                .start()
        }
    }
}

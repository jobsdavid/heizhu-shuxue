package com.mathking.tv.ui

import android.util.TypedValue
import android.view.Gravity
import android.widget.GridLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.mathking.tv.R
import com.mathking.tv.util.Ui

/** 数字键盘构造器，练习页和挑战页共用 */
object KeypadBuilder {

    val KEYS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓")

    fun build(grid: GridLayout, bottomFocusId: Int = 0, onKey: (String) -> Unit) {
        val ctx = grid.context
        grid.removeAllViews()
        val margin = Ui.dp(ctx, 4f)
        val colors = ContextCompat.getColorStateList(ctx, R.color.key_text)
        val template = ContextCompat.getDrawable(ctx, R.drawable.selector_key)
        KEYS.forEachIndexed { i, label ->
            val key = TextView(ctx).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(
                    TypedValue.COMPLEX_UNIT_SP,
                    if (label == "⌫" || label == "✓") 28f else 34f
                )
                setTextColor(colors)
                background = template?.constantState?.newDrawable()?.mutate()
                isFocusable = true
                isClickable = true
                isSoundEffectsEnabled = false
                setOnClickListener { onKey(label) }
                if (i / 3 == 3 && bottomFocusId != 0) nextFocusDownId = bottomFocusId
            }
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(i / 3, 1f),
                GridLayout.spec(i % 3, 1f)
            ).apply {
                width = 0
                height = 0
                setMargins(margin, margin, margin, margin)
            }
            grid.addView(key, lp)
        }
    }

    fun focusCenter(grid: GridLayout) {
        grid.getChildAt(4)?.requestFocus()
    }
}

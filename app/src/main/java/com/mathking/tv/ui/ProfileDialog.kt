package com.mathking.tv.ui

import android.app.Dialog
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.mathking.tv.App
import com.mathking.tv.R
import com.mathking.tv.data.ProfilePresets

/** 「我的资料」：换头像、改名字（预设昵称 + 屏幕字母键盘自定义） */
class ProfileDialog(
    private val activity: FragmentActivity,
    private val onChanged: () -> Unit
) {

    private val app = App.of(activity)

    private val avatars = ProfilePresets.avatars
    private val names = ProfilePresets.names

    private var avatar = app.settings.avatar
    private var name = app.settings.userName

    private var preview: TextView? = null
    private var avatarGrid: GridLayout? = null
    private var nameGrid: GridLayout? = null

    fun show() {
        val dialog = Dialog(activity, R.style.Theme_MathKing_Dialog)
        dialog.setContentView(R.layout.dialog_profile)
        dialog.setCancelable(true)

        preview = dialog.findViewById(R.id.tvPreview)
        avatarGrid = dialog.findViewById(R.id.avatarGrid)
        nameGrid = dialog.findViewById(R.id.nameGrid)

        buildAvatars()
        buildNames()

        dialog.findViewById<View>(R.id.btnQr)?.setOnClickListener {
            // 手机提交成功后，电视这边直接关掉资料页，回首页看结果
            ProfileQrDialog(activity, onSaved = { dialog.dismiss() }).show()
        }
        dialog.findViewById<View>(R.id.btnDone)?.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { onChanged() }

        dialog.setOnShowListener {
            dialog.window?.setLayout(dp(640f), ViewGroup.LayoutParams.WRAP_CONTENT)
            avatarGrid?.getChildAt(0)?.requestFocus()
        }
        refreshPreview()
        dialog.show()
    }

    private fun buildAvatars() {
        val grid = avatarGrid ?: return
        grid.removeAllViews()
        avatars.forEachIndexed { i, emoji ->
            val tv = TextView(activity).apply {
                text = emoji
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                isFocusable = true
                isClickable = true
                isSoundEffectsEnabled = false
                setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
                background = ContextCompat.getDrawable(activity, R.drawable.selector_key)
                setOnClickListener {
                    avatar = emoji
                    app.settings.avatar = emoji
                    // 选回表情头像 = 放弃自定义照片，两者只能有一个生效
                    app.settings.avatarImage = ""
                    refreshAvatarSelection()
                    refreshPreview()
                }
            }
            grid.addView(tv, cellParams(i, 6, 92f, 40f))
        }
        refreshAvatarSelection()
    }

    private fun buildNames() {
        val grid = nameGrid ?: return
        grid.removeAllViews()
        names.forEachIndexed { i, n ->
            val tv = TextView(activity).apply {
                text = n
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(ContextCompat.getColor(activity, R.color.text))
                isFocusable = true
                isClickable = true
                isSoundEffectsEnabled = false
                setPadding(dp(4f), dp(8f), dp(4f), dp(8f))
                background = ContextCompat.getDrawable(activity, R.drawable.selector_key)
                setOnClickListener {
                    name = n
                    app.settings.userName = n
                    refreshNameSelection()
                    refreshPreview()
                }
            }
            grid.addView(tv, cellParams(i, 6, 92f, 36f))
        }
        refreshNameSelection()
    }

    private fun refreshAvatarSelection() {
        val grid = avatarGrid ?: return
        for (i in 0 until grid.childCount) {
            val v = grid.getChildAt(i) as TextView
            v.background = ContextCompat.getDrawable(
                activity,
                if (v.text.toString() == avatar && app.settings.avatarImage.isBlank())
                    R.drawable.bg_key_focus else R.drawable.selector_key
            )
            v.setTextColor(
                ContextCompat.getColor(
                    activity,
                    if (v.text.toString() == avatar && app.settings.avatarImage.isBlank())
                        R.color.bg else R.color.text
                )
            )
        }
    }

    private fun refreshNameSelection() {
        val grid = nameGrid ?: return
        for (i in 0 until grid.childCount) {
            val v = grid.getChildAt(i) as TextView
            val selected = v.text.toString() == name
            v.background = ContextCompat.getDrawable(
                activity, if (selected) R.drawable.bg_key_focus else R.drawable.selector_key
            )
            v.setTextColor(
                ContextCompat.getColor(activity, if (selected) R.color.bg else R.color.text)
            )
        }
    }

    private fun refreshPreview() {
        val mark = if (app.settings.avatarImage.isNotBlank()) "📷" else avatar
        preview?.text = "$mark ${name.ifBlank { "小朋友" }}"
    }

    private fun cellParams(
        index: Int,
        columns: Int,
        w: Float = 92f,
        h: Float = 44f
    ): GridLayout.LayoutParams =
        GridLayout.LayoutParams(
            GridLayout.spec(index / columns),
            GridLayout.spec(index % columns)
        ).apply {
            width = dp(w)
            height = dp(h)
            setMargins(dp(3f), dp(3f), dp(3f), dp(3f))
        }

    private fun dp(v: Float): Int =
        (v * activity.resources.displayMetrics.density + 0.5f).toInt()
}

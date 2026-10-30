package com.mathking.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.mathking.tv.data.SessionSummary
import com.mathking.tv.data.StageConfig
import com.mathking.tv.data.UnlockProgress
import com.mathking.tv.ui.HomeFragment
import com.mathking.tv.ui.DigitInput
import com.mathking.tv.ui.ResultFragment

class MainActivity : AppCompatActivity() {

    /** 结果页要展示的数据（TV 固定横屏，不做复杂状态恢复） */
    var lastSummary: SessionSummary? = null
    var lastUnlockedStage: StageConfig? = null
    var lastUnlockProgress: UnlockProgress? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, HomeFragment())
                .commit()
        }
    }

    /**
     * 全局特效：满分撒彩带、考差了加油。
     *
     * 放在 Activity 而不是各页面里，是因为这套动效要在结果页、挑战胜负页都能用，
     * 每个 Fragment 各放一个 View 既费内存也容易叠错层级。
     */
    fun playEffect(mode: com.mathking.tv.ui.effect.ConfettiView.Mode) {
        findViewById<com.mathking.tv.ui.effect.ConfettiView>(R.id.effectLayer)?.play(mode)
    }

    fun navigate(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .addToBackStack(null)
            .commit()
    }

    fun goHome() {
        supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }

    fun showResult(
        summary: SessionSummary,
        unlocked: StageConfig?,
        progress: UnlockProgress? = null
    ) {
        // 把练习页从回退栈里摘掉：结果页按返回应该回首页，而不是重开一局
        supportFragmentManager.popBackStackImmediate(
            null, FragmentManager.POP_BACK_STACK_INCLUSIVE
        )
        lastSummary = summary
        lastUnlockedStage = unlocked
        lastUnlockProgress = progress
        navigate(ResultFragment())
    }

    /** 有些电视遥控器带数字键，直接当答案输入用 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val fragment = supportFragmentManager.findFragmentById(R.id.container)
            if (fragment is DigitInput) {
                digitOf(event.keyCode)?.let {
                    fragment.appendDigit(it)
                    return true
                }
                if (event.keyCode == KeyEvent.KEYCODE_DEL) {
                    fragment.backspace()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun digitOf(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> "0"
        KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> "1"
        KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> "2"
        KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> "3"
        KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> "4"
        KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> "5"
        KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> "6"
        KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> "7"
        KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> "8"
        KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> "9"
        else -> null
    }

    // 返回键交给 OnBackPressedDispatcher：练习页会先拦截并弹确认框，
    // 其余页面由 FragmentManager 默认回退栈处理。
}

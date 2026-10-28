package com.mathking.tv.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.fragment.app.Fragment
import com.mathking.tv.App
import com.mathking.tv.R
import com.mathking.tv.data.PlayMode
import com.mathking.tv.data.QuestionStyle
import com.mathking.tv.data.StageCatalog
import com.mathking.tv.data.StageConfig
import com.mathking.tv.databinding.FragmentSettingsBinding
import com.mathking.tv.util.Ui

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    private lateinit var app: App
    private var clearArmed = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentSettingsBinding.bind(view)
        app = App.of(requireContext())

        bind(b.rowStage) { changeStage(it) }
        bind(b.rowCount) { changeCount(it) }
        bind(b.rowMode) { changeMode(it) }
        bind(b.rowStyle) { changeStyle(it) }
        bind(b.rowSpeed) { changeSpeed(it) }
        bind(b.rowSound) { toggleSound() }
        bind(b.rowSpeak) { toggleSpeak() }
        bind(b.rowUnlock) { toggleUnlock() }
        bind(b.rowClear) { clearData() }

        b.tvTip.text = "方向键选择 · OK 确认或切换 · 返回键回首页"
        refresh()
        b.rowStage.requestFocus()
    }

    /** 点击 = 往前切一步；左右键 = 前后切 */
    private fun bind(row: View, onChange: (Int) -> Unit) {
        row.setOnClickListener { onChange(1) }
        row.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onChange(-1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onChange(1)
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }
    }

    private fun refresh() {
        val stage = StageCatalog.byId(app.settings.stageId)
        b.tvStage.text = stage.name
        b.tvCount.text = "${app.settings.questionCount} 题"
        b.tvMode.text = when (app.settings.mode) {
            PlayMode.PRACTICE -> "练习 · 不限时"
            PlayMode.TIMED -> "限时 · 每题倒计时"
            PlayMode.EXAM -> "考试 · 固定 20 题"
            else -> "练习 · 不限时"
        }
        b.tvStyle.text = QuestionStyle.of(app.settings.questionStyle).label
        b.tvSpeed.text = when {
            app.settings.speechScale < 1.15f -> "慢"
            app.settings.speechScale < 1.45f -> "正常"
            app.settings.speechScale < 1.7f -> "快"
            else -> "超快"
        }
        b.tvSound.text = if (app.settings.soundOn) "开" else "关"
        b.tvSpeak.text = if (app.settings.speakOn) "开" else "关"
        b.tvUnlock.text = if (app.settings.unlockedAll) "开（全部可选）" else "关（按成绩解锁）"
        if (!clearArmed) b.tvClear.text = "OK 键确认"
    }

    private fun unlockedStages(): List<StageConfig> =
        StageCatalog.available.filter { app.settings.isUnlocked(it.id) }

    private fun changeStage(step: Int) {
        val list = unlockedStages()
        if (list.isEmpty()) return
        val cur = StageCatalog.byId(app.settings.stageId)
        var idx = list.indexOfFirst { it.id == cur.id }
        if (idx < 0) idx = 0
        idx = ((idx + step) % list.size + list.size) % list.size
        val target = list[idx]
        app.settings.stageId = target.id
        refresh()
        b.tvTip.text = "${target.name}：${target.tip}"
    }

    private fun changeCount(step: Int) {
        val options = listOf(10, 15, 20, 30)
        val cur = options.indexOf(app.settings.questionCount).let { if (it < 0) 0 else it }
        val next = ((cur + step) % options.size + options.size) % options.size
        app.settings.questionCount = options[next]
        refresh()
        b.tvTip.text = "考试固定 20 题、每日挑战固定 10 题，这里只影响练习和挑战电脑"
    }

    private fun changeMode(step: Int) {
        val options = listOf(PlayMode.PRACTICE, PlayMode.TIMED, PlayMode.EXAM)
        val cur = options.indexOf(app.settings.mode).let { if (it < 0) 0 else it }
        val next = ((cur + step) % options.size + options.size) % options.size
        app.settings.mode = options[next]
        refresh()
        b.tvTip.text = when (options[next]) {
            PlayMode.TIMED -> "限时模式：每题倒计时，答错直接下一题"
            PlayMode.EXAM -> "考试模式：20 题，答完统一判卷"
            else -> "练习模式：答对才进入下一题"
        }
    }

    private fun changeStyle(step: Int) {
        val options = listOf(QuestionStyle.CALC, QuestionStyle.MIX, QuestionStyle.FIND)
        val cur = options.indexOf(QuestionStyle.of(app.settings.questionStyle)).let { if (it < 0) 1 else it }
        val next = ((cur + step) % options.size + options.size) % options.size
        app.settings.questionStyle = options[next].id
        refresh()
        b.tvTip.text = when (options[next]) {
            QuestionStyle.CALC -> "只出普通计算题"
            QuestionStyle.MIX -> "大部分是计算题，约三成是「这题算错了」的找错题"
            QuestionStyle.FIND -> "每题都给一个算错的式子，让娃找出正确答案"
        }
    }

    private fun changeSpeed(step: Int) {
        val options = listOf(1.05f, 1.3f, 1.55f, 1.85f)
        val cur = options.indexOfFirst { kotlin.math.abs(it - app.settings.speechScale) < 0.01f }
            .let { if (it < 0) 1 else it }
        val next = ((cur + step) % options.size + options.size) % options.size
        app.settings.speechScale = options[next]
        refresh()
        b.tvTip.text = "试听：" + when (next) {
            0 -> "慢速读题"
            1 -> "正常语速"
            2 -> "快速读题"
            else -> "超快（限时模式还会再快一点）"
        }
        app.speaker.speak("三十八 加 九 等于几", options[next] * 1.0f)
    }

    private fun toggleSound() {
        app.settings.soundOn = !app.settings.soundOn
        com.mathking.tv.audio.Sfx.enabled = app.settings.soundOn
        if (app.settings.soundOn) com.mathking.tv.audio.Sfx.correct()
        refresh()
    }

    private fun toggleSpeak() {
        app.settings.speakOn = !app.settings.speakOn
        app.speaker.enabled = app.settings.speakOn
        if (app.settings.speakOn) {
            app.speaker.speak("语音读题已打开", 1.0f)
        } else {
            app.speaker.stop()
        }
        refresh()
    }

    private fun toggleUnlock() {
        if (app.settings.unlockedAll) {
            app.settings.resetUnlockToDefault()
            // 当前阶段如果不在已解锁范围里，回退到默认阶段，避免停在「锁着」的阶段
            if (!app.settings.isUnlocked(app.settings.stageId)) {
                app.settings.stageId = StageCatalog.default.id
            }
            b.tvTip.text = "已关闭全部解锁，回到按成绩解锁"
        } else {
            app.settings.unlockAll()
            b.tvTip.text = "已解锁全部阶段（家长开关）"
        }
        refresh()
    }

    private fun clearData() {
        if (!clearArmed) {
            clearArmed = true
            b.tvClear.text = "再按一次，删除全部记录"
            b.tvClear.setTextColor(Ui.color(requireContext(), R.color.red))
            handler.postDelayed({
                clearArmed = false
                if (_b != null) {
                    b.tvClear.text = "OK 键确认"
                    b.tvClear.setTextColor(Ui.color(requireContext(), R.color.text))
                }
            }, 4000)
        } else {
            clearArmed = false
            app.repo.async({ app.repo.clearAll() }) {
                app.settings.clearProgress()
                if (!isAdded || _b == null) return@async
                b.tvClear.setTextColor(Ui.color(requireContext(), R.color.text))
                refresh()
                b.tvTip.text = "练习记录、错题已清空"
            }
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        _b = null
        super.onDestroyView()
    }
}

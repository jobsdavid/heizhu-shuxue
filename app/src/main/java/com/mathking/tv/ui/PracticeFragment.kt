package com.mathking.tv.ui

import android.app.Dialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import com.mathking.tv.App
import com.mathking.tv.MainActivity
import com.mathking.tv.R
import com.mathking.tv.audio.Sfx
import com.mathking.tv.data.PlayMode
import com.mathking.tv.data.Question
import com.mathking.tv.data.QuestionStyle
import com.mathking.tv.data.StageCatalog
import com.mathking.tv.data.StageConfig
import com.mathking.tv.databinding.FragmentPracticeBinding
import com.mathking.tv.engine.SessionEngine
import com.mathking.tv.stats.Analytics
import com.mathking.tv.util.Ui

class PracticeFragment : Fragment(R.layout.fragment_practice) {

    companion object {
        private const val ARG_STAGE = "stage"
        private const val ARG_MODE = "mode"
        private const val ARG_COUNT = "count"
        private const val ARG_WRONG = "wrong"

        fun newInstance(stageId: String, modeId: String, count: Int, wrongOnly: Boolean) =
            PracticeFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_STAGE, stageId)
                    putString(ARG_MODE, modeId)
                    putInt(ARG_COUNT, count)
                    putBoolean(ARG_WRONG, wrongOnly)
                }
            }
    }

    private var _b: FragmentPracticeBinding? = null
    private val b get() = _b!!

    private lateinit var app: App
    private lateinit var stage: StageConfig
    private lateinit var mode: PlayMode
    private var count = 10
    private var wrongOnly = false

    private var engine: SessionEngine? = null
    private val input = StringBuilder()
    private var sessionId = 0L
    private var finished = false

    private val handler = Handler(Looper.getMainLooper())
    private var remaining = 0
    private var tickRunnable: Runnable? = null
    private var examStartedAt = 0L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentPracticeBinding.bind(view)
        app = App.of(requireContext())
        stage = StageCatalog.byId(arguments?.getString(ARG_STAGE))
        mode = PlayMode.of(arguments?.getString(ARG_MODE))
        count = arguments?.getInt(ARG_COUNT, 10) ?: 10
        wrongOnly = arguments?.getBoolean(ARG_WRONG) ?: false

        buildKeypad()
        b.tvStage.text = "${stage.name} · ${mode.label}"
        b.tvTimer.visibility =
            if (mode == PlayMode.TIMED || mode == PlayMode.EXAM) View.VISIBLE else View.GONE
        b.btnRepeat.setOnClickListener { speakQuestion() }
        b.btnQuit.setOnClickListener { showQuitDialog() }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!finished) showQuitDialog()
                }
            }
        )
        b.tvKeyHint.text = when (mode) {
            PlayMode.EXAM -> "考试模式：答完统一判卷"
            PlayMode.TIMED -> "限时模式：答错或超时会直接下一题"
            else -> "答对了才进入下一题"
        }
        startSession()
    }

    // ------------------------------------------------------------ 建键盘

    private fun buildKeypad() {
        val grid = b.keypad
        grid.removeAllViews()
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓")
        val margin = Ui.dp(requireContext(), 4f)
        val colors = ContextCompat.getColorStateList(requireContext(), R.color.key_text)
        val bg = ContextCompat.getDrawable(requireContext(), R.drawable.selector_key)
        keys.forEachIndexed { i, label ->
            val key = TextView(requireContext()).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(
                    TypedValue.COMPLEX_UNIT_SP,
                    if (label == "⌫" || label == "✓") 28f else 34f
                )
                setTextColor(colors)
                background = bg?.constantState?.newDrawable()?.mutate()
                isFocusable = true
                isClickable = true
                setOnClickListener { onKey(label) }
                isSoundEffectsEnabled = false
                if (i / 3 == 3) nextFocusDownId = R.id.btnRepeat
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

    private fun onKey(label: String) {
        when (label) {
            "⌫" -> {
                if (input.isNotEmpty()) input.deleteCharAt(input.length - 1)
                updateAnswer()
            }
            "✓" -> submitAnswer()
            else -> appendDigit(label)
        }
    }

    fun backspace() {
        if (finished) return
        if (input.isNotEmpty()) input.deleteCharAt(input.length - 1)
        updateAnswer()
    }

    fun appendDigit(d: String) {
        if (finished || input.length >= 3) return
        input.append(d)
        updateAnswer()
        autoCheck()
    }

    /**
     * 少按一次确认键：
     * - 输入的数字正好等于答案 → 直接算作答对
     * - 位数已经超过答案位数 → 1.5 秒后按错误提交（期间按 ⌫ 还能改）
     */
    private fun autoCheck() {
        val q = engine?.question ?: return
        val text = input.toString()
        val value = text.toIntOrNull() ?: return
        when {
            value == q.answer -> handler.postDelayed({
                if (!finished && input.toString() == text) submitAnswer()
            }, 160)
            text.length > q.answer.toString().length -> handler.postDelayed({
                if (!finished && input.toString() == text) submitAnswer()
            }, 1500)
        }
    }

    private fun updateAnswer() {
        if (_b == null) return
        b.tvAnswer.text = if (input.isEmpty()) "?" else input.toString()
    }

    // ------------------------------------------------------------ 会话

    private fun startSession() {
        if (wrongOnly) {
            app.repo.async({ app.repo.wrongQuestions(null, count) }) { questions ->
                if (!isAdded) return@async
                if (questions.isEmpty()) {
                    b.tvQuestion.text = "没有错题"
                    b.tvKeyHint.text = "先去练几轮，错题会自动收进来"
                    return@async
                }
                openSession(questions)
            }
        } else {
            openSession(null)
        }
    }

    private fun openSession(pre: List<Question>?) {
        val planned = when {
            pre != null -> pre.size
            mode == PlayMode.EXAM -> 20
            else -> count
        }
        app.repo.async({
            app.repo.openSession(stage.id, mode, planned, app.settings.playerId)
        }) { id ->
            if (!isAdded || _b == null) return@async
            sessionId = id
            engine = SessionEngine(
                stage, mode, planned, id, app.repo, pre,
                QuestionStyle.of(app.settings.questionStyle)
            )
            render()
            speakQuestion()
            b.keypad.getChildAt(4)?.requestFocus()
        }
    }

    private fun render() {
        val e = engine ?: return
        val q = e.question ?: return
        b.tvProgress.text = "第 ${e.index + 1}/${e.total} 题"
        b.progressBar.max = e.total
        b.progressBar.progress = e.index
        b.tvQuestion.text = q.text
        b.tvQuestion.setTextSize(TypedValue.COMPLEX_UNIT_SP, when {
            q.text.length <= 5 -> 92f
            q.text.length <= 9 -> 72f
            else -> 56f
        })
        b.tvFeedback.text = ""
        b.tvHint.text = if (q.findError) "这道题有错误，填正确答案" else ""
        b.tvHint.setTextColor(Ui.color(requireContext(), if (q.findError) R.color.accent else R.color.text_dim))
        input.clear()
        updateAnswer()
        b.keypad.getChildAt(4)?.requestFocus()   // 每题都把焦点放回 5，少按几下方向键
        startTimerForQuestion()
    }

    private fun sayFeedback(text: String) {
        app.speaker.speak(text, modeRate() * app.settings.speechScale)
    }

    private fun speakQuestion() {
        val q = engine?.question ?: return
        if (q.speak.isBlank()) return
        app.speaker.speak(q.speak, modeRate() * app.settings.speechScale)
    }

    private fun modeRate(): Float = when (mode) {
        PlayMode.PRACTICE -> 0.95f
        PlayMode.TIMED -> 1.30f
        PlayMode.EXAM -> 1.10f
        PlayMode.WRONG -> 1.0f
    }

    // ------------------------------------------------------------ 计时

    private fun startTimerForQuestion() {
        stopTimer()
        when (mode) {
            PlayMode.TIMED -> {
                remaining = engine?.timeLimitSeconds() ?: 10
                b.tvTimer.text = "$remaining"
                b.tvTimer.setTextColor(Ui.color(requireContext(), R.color.accent))
                tick()
            }
            PlayMode.EXAM -> {
                if (examStartedAt == 0L) examStartedAt = SystemClock.elapsedRealtime()
                tick()
            }
            else -> Unit
        }
    }

    private fun tick() {
        val runnable = object : Runnable {
            override fun run() {
                if (!isAdded || finished || _b == null) return
                when (mode) {
                    PlayMode.TIMED -> {
                        remaining--
                        b.tvTimer.text = "$remaining"
                        if (remaining in 1..3) Sfx.tick()
                        if (remaining <= 3) {
                            b.tvTimer.setTextColor(Ui.color(requireContext(), R.color.red))
                        }
                        if (remaining <= 0) {
                            onTimeout()
                            return
                        }
                        handler.postDelayed(this, 1000)
                    }
                    PlayMode.EXAM -> {
                        val elapsed = (SystemClock.elapsedRealtime() - examStartedAt) / 1000
                        b.tvTimer.text = "${elapsed / 60}:${String.format("%02d", elapsed % 60)}"
                        handler.postDelayed(this, 1000)
                    }
                    else -> Unit
                }
            }
        }
        tickRunnable = runnable
        handler.postDelayed(runnable, 1000)
    }

    private fun stopTimer() {
        tickRunnable?.let { handler.removeCallbacks(it) }
        tickRunnable = null
    }

    private fun onTimeout() {
        val e = engine ?: return
        if (finished) return
        e.submit(null, timedOut = true) ?: return
        Sfx.wrong()
        sayFeedback("时间到")
        input.clear()
        updateAnswer()
        b.tvFeedback.text = "时间到，下一题"
        b.tvFeedback.setTextColor(Ui.color(requireContext(), R.color.red))
        nextOrFinish(modeDelay(700))
    }

    private fun submitAnswer() {
        val e = engine ?: return
        if (finished || e.question == null) return
        val text = input.toString()
        if (text.isEmpty()) return
        val value = text.toIntOrNull() ?: return
        val result = e.submit(value) ?: return
        input.clear()
        updateAnswer()

        if (mode == PlayMode.EXAM) {
            b.tvFeedback.text = if (result.last) "交卷中…" else "已记录"
            b.tvFeedback.setTextColor(Ui.color(requireContext(), R.color.text_dim))
            nextOrFinish(if (result.last) 300 else 220)
            return
        }

        if (result.correct) {
            Sfx.correct()
            sayFeedback("回答正确")
            if (result.combo >= 3) {
                Sfx.combo(result.combo)
                b.tvFeedback.text = "回答正确！${result.combo} 连击"
            } else {
                b.tvFeedback.text = "回答正确！"
            }
            b.tvFeedback.setTextColor(Ui.color(requireContext(), R.color.green))
            nextOrFinish(modeDelay(950))
        } else if (result.retry) {
            Sfx.wrong()
            sayFeedback("回答错误")
            b.tvFeedback.text = "回答错误，再算一遍"
            b.tvFeedback.setTextColor(Ui.color(requireContext(), R.color.red))
            if (result.question.hint.isNotBlank()) {
                b.tvHint.text = "小提示：${result.question.hint}"
            }
        } else {
            Sfx.wrong()
            sayFeedback(if (result.timedOut) "时间到" else "回答错误")
            b.tvFeedback.text =
                if (result.timedOut) "时间到，下一题" else "回答错误，正确答案是 ${result.question.answerText}"
            b.tvFeedback.setTextColor(Ui.color(requireContext(), R.color.red))
            nextOrFinish(modeDelay(1200))
        }
    }

    /** 考试模式节奏更快，限时模式留出看答案的时间 */
    private fun modeDelay(normal: Long): Long = normal

    private fun nextOrFinish(delay: Long) {
        val e = engine ?: return
        if (e.isLast) {
            handler.postDelayed({ finishSession(true) }, delay)
        } else {
            handler.postDelayed({
                if (!isAdded || _b == null) return@postDelayed
                e.advance()
                render()
                speakQuestion()
            }, delay)
        }
    }

    // ------------------------------------------------------------ 收尾

    private var quitDialog: Dialog? = null

    private fun showQuitDialog() {
        if (quitDialog?.isShowing == true || finished) return
        stopTimer()
        val done = engine?.records?.size ?: 0
        val dialog = Dialog(requireContext(), R.style.Theme_MathKing_Dialog)
        dialog.setContentView(R.layout.dialog_quit)
        dialog.setCancelable(true)
        dialog.findViewById<android.widget.TextView>(R.id.tvQuitHint)?.text = if (done == 0) {
            "这一轮还没做题，退出不会留下记录"
        } else {
            "已经做了 $done 题，退出后这一轮不计入成长曲线"
        }
        dialog.findViewById<View>(R.id.btnKeep)?.setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.btnQuit)?.setOnClickListener {
            dialog.dismiss()
            quit()
        }
        dialog.setOnDismissListener {
            quitDialog = null
            if (!finished && _b != null) resumeTimer()
        }
        dialog.setOnShowListener {
            dialog.findViewById<View>(R.id.btnKeep)?.requestFocus()
            dialog.window?.setLayout(
                Ui.dp(requireContext(), 560f),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        quitDialog = dialog
        app.speaker.stop()
        dialog.show()
    }

    private fun resumeTimer() {
        if (mode == PlayMode.TIMED && remaining > 0) tick()
        if (mode == PlayMode.EXAM) tick()
    }

    private fun quit() {
        savePartial()
        (activity as? MainActivity)?.goHome()
    }

    /** 中途退出：只保存已做的题，不跳结果页 */
    private fun savePartial() {
        if (finished) return
        val e = engine ?: return
        finished = true
        stopTimer()
        app.speaker.stop()
        val summary = e.summary(false)
        app.repo.async({
            app.repo.closeSession(
                sessionId, summary.total, summary.correctFirst, summary.wrong,
                summary.durationMs, summary.score, 0.0, false
            )
        })
    }

    private fun finishSession(finishedOk: Boolean) {
        if (finished) return
        finished = true
        stopTimer()
        app.speaker.stop()
        val e = engine ?: return
        val summary = e.summary(finishedOk)
        app.repo.async({
            val overall = app.repo.overall(stage.id)
            val power = Analytics.power(
                summary.score, summary.avgSeconds, stage.targetSeconds,
                overall.questions + summary.total, stage.weight
            )
            app.repo.closeSession(
                sessionId, summary.total, summary.correctFirst, summary.wrong,
                summary.durationMs, summary.score, power, finishedOk
            )
            val unlocked = if (finishedOk) {
                app.settings.checkUnlock(stage, summary.score, summary.total)
            } else null
            summary.copy(power = power) to unlocked
        }) { pair ->
            if (!isAdded) return@async
            if (finishedOk) {
                if (pair.second != null) Sfx.levelUp() else Sfx.finish()
            }
            (activity as? MainActivity)?.showResult(pair.first, pair.second)
        }
    }

    override fun onDestroyView() {
        stopTimer()
        handler.removeCallbacksAndMessages(null)
        savePartial()
        app.speaker.stop()
        _b = null
        super.onDestroyView()
    }
}

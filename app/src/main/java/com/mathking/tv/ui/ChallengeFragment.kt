package com.mathking.tv.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Dialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import com.mathking.tv.App
import com.mathking.tv.MainActivity
import com.mathking.tv.R
import com.mathking.tv.audio.Sfx
import com.mathking.tv.data.AttemptRecord
import com.mathking.tv.data.Generator
import com.mathking.tv.data.PlayMode
import com.mathking.tv.data.Question
import com.mathking.tv.data.QuestionStyle
import com.mathking.tv.data.StageCatalog
import com.mathking.tv.data.StageConfig
import com.mathking.tv.databinding.FragmentChallengeBinding
import com.mathking.tv.util.Ui
import kotlin.random.Random

/**
 * 挑战模式：和电脑比同一道题，看谁先算出正确答案。
 *
 * 电脑速度 = 孩子在该阶段「正确率 ≥ 80%」的最快平均每题用时 × 0.8（快 20%），
 * 每题再上下浮动 10%。所以只要孩子不进步，电脑永远比他快；
 * 没有达标战绩的阶段不解锁。
 */
class ChallengeFragment : Fragment(R.layout.fragment_challenge), DigitInput {

    companion object {
        private const val ARG_STAGE = "stage"

        fun newInstance(stageId: String) = ChallengeFragment().apply {
            arguments = Bundle().apply { putString(ARG_STAGE, stageId) }
        }
    }

    private var _b: FragmentChallengeBinding? = null
    private val b get() = _b!!

    private lateinit var app: App
    private lateinit var stage: StageConfig

    private var baselineMs = 0L      // 电脑每题基准（已含 ×0.8）
    private var total = 10
    private val questions = mutableListOf<Question>()
    private val records = mutableListOf<AttemptRecord>()
    private val times = mutableListOf<Long>()

    private var index = 0
    private var myScore = 0
    private var computerScore = 0
    private var sessionId = 0L
    private var answered = false
    private var finished = false

    private var current: Question? = null
    private var questionStart = 0L
    private var computerMs = 0L
    private var timer: ValueAnimator? = null

    private val input = StringBuilder()
    private val handler = Handler(Looper.getMainLooper())
    private var quitDialog: Dialog? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentChallengeBinding.bind(view)
        app = App.of(requireContext())
        stage = StageCatalog.byId(arguments?.getString(ARG_STAGE))
        total = app.settings.questionCount

        KeypadBuilder.build(b.keypad) { onKey(it) }
        Ui.focusZoom(b.btnAgain, b.btnHome)
        b.btnAgain.setOnClickListener { restart() }
        b.btnHome.setOnClickListener { (activity as? MainActivity)?.goHome() }
        b.tvPace.text = "正在读取你的最快纪录…"
        b.tvHint.text = "在电脑答完前算出正确答案，就能赢下这一题"

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        b.resultLayer.visibility == View.VISIBLE ->
                            (activity as? MainActivity)?.goHome()
                        finished -> (activity as? MainActivity)?.goHome()
                        else -> showQuitDialog()
                    }
                }
            }
        )

        loadBaseline()
    }

    // ------------------------------------------------------------ 启动

    private fun loadBaseline() {
        app.repo.async({ app.repo.bestPaceMs(stage.id) }) { best ->
            if (!isAdded || _b == null) return@async
            if (best == null) {
                b.tvPace.text = "还没有达标战绩"
                b.tvQuestion.text = "挑战未解锁"
                b.tvQuestion.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
                b.tvHint.text = "先去练一轮「${stage.name}」，正确率到 80% 以上就能来挑战"
                b.tvCountdown.text = ""
                b.progressComputer.visibility = View.GONE
                b.tvAnswer.visibility = View.GONE
                b.keypad.visibility = View.GONE
                return@async
            }
            baselineMs = (best * 0.8).toLong().coerceIn(1200L, 20000L)
            b.tvPace.text = "电脑 ${String.format("%.1f", baselineMs / 1000.0)} 秒/题"
            buildQuestions()
            openSession()
        }
    }

    private fun buildQuestions() {
        questions.clear()
        val style = QuestionStyle.of(app.settings.questionStyle)
        repeat(total) { questions.add(Generator.make(stage, style)) }
    }

    private fun openSession() {
        app.repo.async({
            app.repo.openSession(stage.id, PlayMode.CHALLENGE, total, app.settings.playerId)
        }) { id ->
            if (!isAdded || _b == null) return@async
            sessionId = id
            index = 0
            myScore = 0
            computerScore = 0
            records.clear()
            times.clear()
            b.tvScore.text = "你 0 : 0 电脑"
            startQuestion()
        }
    }

    // ------------------------------------------------------------ 每题

    private fun startQuestion() {
        if (index >= questions.size) {
            finishChallenge()
            return
        }
        val q = questions[index]
        current = q
        answered = false
        input.clear()
        b.tvProgress.text = "第 ${index + 1}/${questions.size} 题"
        b.tvQuestion.text = q.text
        b.tvQuestion.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            when {
                q.text.length <= 5 -> 84f
                q.text.length <= 9 -> 66f
                else -> 52f
            }
        )
        b.tvAnswer.visibility = View.VISIBLE
        b.tvAnswer.text = "?"
        b.tvFeedback.text = ""
        b.tvFeedback.setTextColor(Ui.color(requireContext(), R.color.green))

        computerMs = (baselineMs * (0.9 + Random.nextDouble() * 0.2)).toLong()
        questionStart = SystemClock.elapsedRealtime()
        KeypadBuilder.focusCenter(b.keypad)
        speakQuestion()
        runComputer()
    }

    private fun runComputer() {
        timer?.cancel()
        b.progressComputer.progress = 0
        b.tvCountdown.text = String.format("%.1f", computerMs / 1000.0)
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = computerMs
            interpolator = LinearInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                b.progressComputer.progress = (f * 100).toInt()
                if (!answered) {
                    b.tvCountdown.text = String.format("%.1f", computerMs * (1 - f) / 1000.0)
                }
            }
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (!answered && isAdded && _b != null) onComputerWin(true)
            }
        })
        timer = anim
        anim.start()
    }

    private fun onComputerWin(timeout: Boolean) {
        if (answered || finished) return
        answered = true
        computerScore++
        b.tvScore.text = "你 $myScore : $computerScore 电脑"
        b.tvFeedback.text = "电脑先答完了，这题算电脑赢"
        b.tvFeedback.setTextColor(Ui.color(requireContext(), R.color.red))
        Sfx.wrong()
        sayFeedback(if (timeout) "时间到" else "回答错误")
        recordAttempt(null, false, timeout)
        handler.postDelayed({ next() }, 1100)
    }

    override fun appendDigit(d: String) { onKey(d) }
    override fun backspace() { onKey("⌫") }

    private fun onKey(label: String) {
        if (finished || b.resultLayer.visibility == View.VISIBLE || answered) return
        when (label) {
            "⌫" -> {
                if (input.isNotEmpty()) input.deleteCharAt(input.length - 1)
                showInput()
            }
            "✓" -> submit()
            else -> {
                if (input.length < 3) {
                    input.append(label)
                    showInput()
                    autoCheck()
                }
            }
        }
    }

    private fun showInput() {
        if (_b == null) return
        b.tvAnswer.text = if (input.isEmpty()) "?" else input.toString()
    }

    private fun autoCheck() {
        val q = current ?: return
        val text = input.toString()
        val value = text.toIntOrNull() ?: return
        when {
            value == q.answer -> handler.postDelayed({
                if (!finished && !answered && input.toString() == text) submit()
            }, 120)
            text.length > q.answer.toString().length -> handler.postDelayed({
                if (!finished && !answered && input.toString() == text) submit()
            }, 1200)
        }
    }

    private fun submit() {
        if (answered || finished) return
        val q = current ?: return
        val value = input.toString().toIntOrNull() ?: return
        answered = true
        timer?.cancel()
        val ms = SystemClock.elapsedRealtime() - questionStart
        if (value == q.answer) {
            myScore++
            Sfx.correct()
            sayFeedback("回答正确")
            b.tvFeedback.text = "答对了！这题你赢（${String.format("%.1f", ms / 1000.0)} 秒）"
            b.tvFeedback.setTextColor(Ui.color(requireContext(), R.color.green))
            recordAttempt(value, true)
        } else {
            computerScore++
            Sfx.wrong()
            sayFeedback("回答错误")
            b.tvFeedback.text = "答错了，正确答案是 ${q.answerText}"
            b.tvFeedback.setTextColor(Ui.color(requireContext(), R.color.red))
            recordAttempt(value, false)
        }
        b.tvScore.text = "你 $myScore : $computerScore 电脑"
        handler.postDelayed({ next() }, 1100)
    }

    private fun recordAttempt(value: Int?, ok: Boolean, timedOut: Boolean = false) {
        val q = current ?: return
        val ms = SystemClock.elapsedRealtime() - questionStart
        val rec = AttemptRecord(index, q, value, ok, 1, ms, timedOut)
        records.add(rec)
        times.add(ms)
        app.repo.async({
            app.repo.saveAttempt(sessionId, rec)
            if (!ok) app.repo.upsertWrong(stage.id, q)
        })
    }

    private fun next() {
        if (!isAdded || _b == null) return
        index++
        if (index >= questions.size) finishChallenge() else startQuestion()
    }

    // ------------------------------------------------------------ 结算

    private fun finishChallenge() {
        if (finished) return
        finished = true
        timer?.cancel()
        app.speaker.stop()
        val totalMs = records.sumOf { it.ms }
        val score = if (records.isEmpty()) 0.0 else myScore * 100.0 / records.size
        app.repo.async({
            app.repo.closeSession(
                sessionId, records.size, myScore, records.size - myScore,
                totalMs, score, 0.0, true
            )
        })
        showResult(totalMs)
    }

    private fun showResult(totalMs: Long) {
        b.resultLayer.visibility = View.VISIBLE
        val win = myScore > computerScore
        val draw = myScore == computerScore
        b.tvResultTitle.text = when {
            win -> "你赢了！"
            draw -> "打平了！"
            else -> "电脑险胜"
        }
        b.tvResultTitle.setTextColor(
            Ui.color(requireContext(), if (win) R.color.accent else R.color.text)
        )
        b.tvResultScore.text = "你 $myScore : $computerScore 电脑"
        val winTimes = records.filter { it.firstOk }.map { it.ms }
        val avg = if (winTimes.isEmpty()) 0.0 else winTimes.average() / 1000.0
        val tip = when {
            win -> "电脑下次会更快，继续练才守得住"
            draw -> "就差一点点，再快一点就赢了"
            else -> "再快一点就能追上电脑"
        }
        val paceText = if (winTimes.isEmpty()) "这轮没赢下一题"
        else "你赢下的题平均 ${String.format("%.1f", avg)} 秒/题"
        b.tvResultDetail.text =
            "$paceText · 电脑 ${String.format("%.1f", baselineMs / 1000.0)} 秒/题（你的最快纪录 × 0.8）\n$tip"
        b.btnAgain.requestFocus()
        if (win) Sfx.levelUp() else Sfx.finish()
    }

    private fun restart() {
        b.resultLayer.visibility = View.GONE
        finished = false
        timer?.cancel()
        buildQuestions()
        openSession()
    }

    // ------------------------------------------------------------ 其它

    private fun speakQuestion() {
        val q = current ?: return
        if (q.speak.isBlank()) return
        app.speaker.speak(q.speak, 1.1f * app.settings.speechScale)
    }

    private fun sayFeedback(text: String) {
        app.speaker.speak(text, 1.2f * app.settings.speechScale)
    }

    private fun showQuitDialog() {
        if (quitDialog?.isShowing == true || finished) return
        timer?.pause()
        val dialog = Dialog(requireContext(), R.style.Theme_MathKing_Dialog)
        dialog.setContentView(R.layout.dialog_quit)
        dialog.setCancelable(true)
        dialog.findViewById<TextView>(R.id.tvQuitTitle)?.text = "要放弃这次挑战吗？"
        dialog.findViewById<TextView>(R.id.tvQuitHint)?.text =
            "现在是 你 $myScore : $computerScore 电脑，已做的题会计入不完整记录"
        dialog.findViewById<View>(R.id.btnKeep)?.setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.btnQuit)?.setOnClickListener {
            dialog.dismiss()
            savePartial()
            (activity as? MainActivity)?.goHome()
        }
        dialog.setOnDismissListener {
            quitDialog = null
            if (!finished && _b != null) timer?.resume()
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

    private fun savePartial() {
        if (finished) {
            return
        }
        finished = true
        timer?.cancel()
        app.speaker.stop()
        val totalMs = records.sumOf { it.ms }
        app.repo.async({
            app.repo.closeSession(
                sessionId, records.size, myScore, records.size - myScore,
                totalMs, if (records.isEmpty()) 0.0 else myScore * 100.0 / records.size,
                0.0, false
            )
        })
    }

    override fun onDestroyView() {
        timer?.cancel()
        handler.removeCallbacksAndMessages(null)
        savePartial()
        app.speaker.stop()
        _b = null
        super.onDestroyView()
    }
}

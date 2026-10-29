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
import com.mathking.tv.audio.Speech
import com.mathking.tv.data.AttemptRecord
import com.mathking.tv.data.Generator
import com.mathking.tv.data.PlayMode
import com.mathking.tv.data.Question
import com.mathking.tv.data.Repo
import com.mathking.tv.data.QuestionStyle
import com.mathking.tv.data.StageCatalog
import com.mathking.tv.data.StageConfig
import com.mathking.tv.databinding.FragmentChallengeBinding
import com.mathking.tv.util.Ui

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

    private var countingDown = false
    private var current: Question? = null
    private var questionStart = 0L
    private var computerMs = 0L
    private var timer: ValueAnimator? = null

    private val displayName: String
        get() = app.settings.userName.ifBlank { "你" }

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
        app.repo.async({ app.repo.challengeStatus(stage) }) { st ->
            if (!isAdded || _b == null) return@async
            fun blocked(title: String, tip: String, paceText: String) {
                b.tvPace.text = paceText
                b.tvQuestion.text = title
                b.tvQuestion.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
                b.tvHint.text = tip
                b.tvCountdown.text = ""
                b.progressComputer.visibility = View.GONE
                b.tvAnswer.visibility = View.GONE
                b.keypad.visibility = View.GONE
            }
            if (st.perfectRounds < Repo.CHALLENGE_FULL_ROUNDS) {
                blocked(
                    "挑战未解锁",
                    "在「${stage.name}」做满 ${Repo.CHALLENGE_FULL_ROUNDS} 场考试全对（每场至少 5 题）就能解锁",
                    "还差 ${Repo.CHALLENGE_FULL_ROUNDS - st.perfectRounds} 场满分"
                )
                return@async
            }
            if (st.credits <= 0) {
                // 挑战次数用完了：每考 5 场考试换 1 次（次数可以攒着不花）
                blocked(
                    "次数用完了",
                    "每考 ${Repo.CHALLENGE_EXAMS_PER_CREDIT} 场考试能换 1 次挑战，攒着不用也不会清零",
                    "先考几场试"
                )
                return@async
            }
            val pace = st.computerMs
            if (pace == null) {
                blocked("还没有考试记录", "先考一场试试，电脑要拿你的考试速度当对手", "没有基准")
                return@async
            }
            // 基准已经在 challengeStatus 里算好（最快 5 场考试 × 0.8，带下限）
            baselineMs = pace
            b.tvPace.text = "电脑 ${String.format("%.1f", baselineMs / 1000.0)} 秒/题 · 剩 ${st.credits} 次"
            buildQuestions { openSession() }
        }
    }

    /** 挑战的题：一半来自这个阶段最常错的题，一半新出——错的还是那些，才叫对症 */
    private fun buildQuestions(onReady: () -> Unit) {
        val style = QuestionStyle.of(app.settings.questionStyle)
        app.repo.async({
            app.repo.mixedQuestions(stage, style, total, share = 0.5)
        }) { list ->
            if (!isAdded || _b == null) return@async
            questions.clear()
            questions.addAll(list)
            onReady()
        }
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
            b.tvScore.text = "$displayName 0 : 0 电脑"
            startCountdown { startQuestion() }
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
        if (q.findError && q.text.contains("=")) {
            val sp = android.text.SpannableString(q.text)
            sp.setSpan(
                android.text.style.ForegroundColorSpan(Ui.color(requireContext(), R.color.red)),
                q.text.indexOf('=') + 1, q.text.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            b.tvQuestion.text = sp
        } else {
            b.tvQuestion.text = q.text
        }
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

        // 严格 = 历史最快每题用时 × 0.8：每一题电脑都比孩子的最好成绩快 20%
        computerMs = baselineMs
        questionStart = SystemClock.elapsedRealtime()
        KeypadBuilder.focusCenter(b.keypad)
        Ui.pageTurn(b.tvQuestion, b.tvAnswer)
        speakQuestion()
        runComputer()
    }

    /** 开局倒计时：准备好了吗 → 三 → 二 → 一 → 开始（电脑也等「开始」后才跑） */
    private fun startCountdown(then: () -> Unit) {
        countingDown = true
        b.keypad.alpha = 0.2f
        b.tvAnswer.alpha = 0.2f
        b.progressComputer.progress = 0
        b.tvCountdown.text = ""
        b.tvQuestion.text = Speech.READY
        b.tvQuestion.setTextSize(TypedValue.COMPLEX_UNIT_SP, 72f)
        b.tvAnswer.text = "?"
        val rate = 1.1f * app.settings.speechScale
        app.speaker.speak(Speech.READY, rate)
        listOf(3, 2, 1).forEachIndexed { i, n ->
            handler.postDelayed({
                if (!isAdded || _b == null) return@postDelayed
                b.tvQuestion.text = n.toString()
                b.tvQuestion.setTextSize(TypedValue.COMPLEX_UNIT_SP, 140f)
                Sfx.tick()
                app.speaker.speak(Speech.countdown(n), rate)
            }, 1100L + i * 950L)
        }
        handler.postDelayed({
            if (!isAdded || _b == null) return@postDelayed
            countingDown = false
            b.keypad.alpha = 1f
            b.tvAnswer.alpha = 1f
            app.speaker.speak(Speech.GO, rate)
            then()
        }, 1100L + 3 * 950L)
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
        quitDialog?.dismiss()
        answered = true
        computerScore++
        b.tvScore.text = "$displayName $myScore : $computerScore 电脑"
        b.tvFeedback.text = "电脑先答完了，这题算电脑赢"
        b.tvFeedback.setTextColor(Ui.color(requireContext(), R.color.red))
        Sfx.wrong()
        val q = current
        sayFeedback(
            if (q == null) (if (timeout) "时间到" else "回答错误")
            else Speech.timedOutWithAnswer(q)
        )
        recordAttempt(null, false, timeout)
        handler.postDelayed({ next() }, 1100)
    }

    override fun appendDigit(d: String) { onKey(d) }
    override fun backspace() { onKey("⌫") }

    private fun onKey(label: String) {
        if (finished || countingDown || b.resultLayer.visibility == View.VISIBLE || answered) return
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
            // 报出正确答案再给一句思路：挑战输了也不能白输
            sayFeedback(Speech.wrongWithAnswer(q))
            b.tvFeedback.text = "答错了，正确答案是 ${q.answerText}"
            b.tvFeedback.setTextColor(Ui.color(requireContext(), R.color.red))
            recordAttempt(value, false)
        }
        b.tvScore.text = "$displayName $myScore : $computerScore 电脑"
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
        b.tvResultScore.text = "$displayName $myScore : $computerScore 电脑"
        val winTimes = records.filter { it.firstOk }.map { it.ms }
        val avg = if (winTimes.isEmpty()) 0.0 else winTimes.average() / 1000.0
        val tip = when {
            win -> "赢了！电脑难度跟着你练习里的最快成绩走，想升级就去练得更快"
            draw -> "就差一点点，再快一点就赢了"
            else -> "再快一点就能追上电脑"
        }
        val paceText = if (winTimes.isEmpty()) "这轮没赢下一题"
        else "你赢下的题平均 ${String.format("%.1f", avg)} 秒/题"
        b.tvResultDetail.text =
            "$paceText · 电脑 ${String.format("%.1f", baselineMs / 1000.0)} 秒/题（你的最快纪录 × 0.8）\n$tip"
        b.btnAgain.requestFocus()
        if (win) Sfx.levelUp() else Sfx.finish()
        // 这一局已经花掉一次，看看还剩几次；没了就把「再来一轮」换成提示
        app.repo.async({ app.repo.challengeStatus(stage) }) { st ->
            if (!isAdded || _b == null) return@async
            if (st.credits <= 0) {
                b.btnAgain.text = "再考 ${Repo.CHALLENGE_EXAMS_PER_CREDIT} 场再来"
                b.btnAgain.setOnClickListener { (activity as? MainActivity)?.goHome() }
                b.tvResultDetail.text = b.tvResultDetail.text.toString() +
                    "\n挑战次数用完了：每考 ${Repo.CHALLENGE_EXAMS_PER_CREDIT} 场考试能再挑战一次"
            }
        }
    }

    private fun restart() {
        app.repo.async({ app.repo.challengeStatus(stage) }) { st ->
            if (!isAdded || _b == null) return@async
            if (st.credits <= 0) {
                b.tvResultDetail.text = "挑战次数用完了，先去考试吧：每考 ${Repo.CHALLENGE_EXAMS_PER_CREDIT} 场能再挑战一次"
                return@async
            }
            b.resultLayer.visibility = View.GONE
            finished = false
            timer?.cancel()
            buildQuestions { openSession() }
        }
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
        // 不暂停电脑：以前按返回会把电脑计时暂停，孩子拿它当「思考键」，
        // 现在弹窗照弹，电脑照跑——想赢就得真快
        val dialog = Dialog(requireContext(), R.style.Theme_MathKing_Dialog)
        dialog.setContentView(R.layout.dialog_quit)
        dialog.setCancelable(true)
        dialog.findViewById<TextView>(R.id.tvQuitTitle)?.text = "要放弃这次挑战吗？"
        dialog.findViewById<TextView>(R.id.tvQuitHint)?.text =
            "现在是 你 $myScore : $computerScore 电脑。" +
                (if (answered) "" else "注意：电脑还在跑，不会停下来等你。")
        dialog.findViewById<View>(R.id.btnKeep)?.setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.btnQuit)?.setOnClickListener {
            dialog.dismiss()
            savePartial()
            (activity as? MainActivity)?.goHome()
        }
        dialog.setOnDismissListener {
            quitDialog = null
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

package com.mathking.tv.ui

import android.animation.ValueAnimator
import android.app.Dialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import com.mathking.tv.App
import com.mathking.tv.MainActivity
import com.mathking.tv.R
import com.mathking.tv.audio.Sfx
import com.mathking.tv.audio.Speech
import com.mathking.tv.data.PlayMode
import com.mathking.tv.data.Question
import com.mathking.tv.data.QuestionStyle
import com.mathking.tv.data.StageCatalog
import com.mathking.tv.data.StageConfig
import com.mathking.tv.databinding.FragmentPracticeBinding
import com.mathking.tv.engine.SessionEngine
import com.mathking.tv.stats.Analytics
import com.mathking.tv.ui.text.MathText
import com.mathking.tv.util.Ui

/**
 * 答题页（练习 / 限时 / 考试 / 错题重练公用）。
 *
 * 交互只认三个状态，别再散着写：
 *   ANSWERING 可以输入 → LOCKED 刚提交、正在播反馈 → ADVANCING 翻页中
 *
 * 几个踩过的坑，改的时候别踩回去：
 * 1. **提交后别清空输入**：清了就会在播「回答正确」的时候把答案变回「?」，
 *    孩子以为自己的答案被吞了。数字留到下一题 render() 再清。
 * 2. **考试模式不能自动提交**：原来「填满正确答案就 160ms 自动交」，
 *    等于答对了立刻翻页、答错了反而停住——孩子一眼就看穿对错了。
 * 3. **反馈语音不播完不换题**：否则「回答正确」永远读半句就被下一题打断。
 * 4. **不要往页面上写程序状态**（比如「已记录」）：只给孩子看有意义的字。
 */
class PracticeFragment : Fragment(R.layout.fragment_practice), DigitInput {

    companion object {
        private const val ARG_STAGE = "stage"
        private const val ARG_MODE = "mode"
        private const val ARG_COUNT = "count"
        private const val ARG_WRONG = "wrong"
        private const val ARG_SEED = "seed"
        private const val ARG_DAILY = "daily"
        private const val ARG_WRONG_ID = "wrong_id"

        /** 一个答案最多 3 位（s8 两位数乘一位数最大 891，s9 三位数除法最大 499） */
        private const val MAX_DIGITS = 3

        /** 练习模式发呆多久重读一次题 */
        private const val IDLE_MS = 10_000L

        /** 一题最多主动重读几次，再多就变成骚扰了 */
        private const val IDLE_MAX = 2

        /**
         * wrongId > 0 时只练这一道错题（错题本里「只练这道」），
         * 不传就是按 wrongOnly 决定「练全部错题」还是正常出题。
         */
        fun newInstance(
            stageId: String,
            modeId: String,
            count: Int,
            wrongOnly: Boolean,
            seed: Long = 0L,
            dailyDay: String? = null,
            wrongId: Long = 0L
        ) = PracticeFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_STAGE, stageId)
                putString(ARG_MODE, modeId)
                putInt(ARG_COUNT, count)
                putBoolean(ARG_WRONG, wrongOnly)
                putLong(ARG_SEED, seed)
                putString(ARG_DAILY, dailyDay)
                putLong(ARG_WRONG_ID, wrongId)
            }
        }
    }

    private enum class Phase { ANSWERING, LOCKED, ADVANCING, DONE }

    private enum class AnswerState { NORMAL, OK, BAD }

    private var _b: FragmentPracticeBinding? = null
    private val b get() = _b!!

    private lateinit var app: App
    private lateinit var stage: StageConfig
    private lateinit var mode: PlayMode
    private var count = 10
    private var wrongOnly = false
    private var seed = 0L
    private var dailyDay: String? = null
    private var wrongId = 0L

    /** 上一题跳过后要留在下一题页面上的说明 */
    private var pendingTip: String? = null

    private var engine: SessionEngine? = null
    private val input = StringBuilder()
    private var sessionId = 0L
    private var finished = false

    private var phase = Phase.ANSWERING

    /** 上一题答错后输入框里还留着旧答案：下一个数字要重新开始，别拼在旧的后面 */
    private var freshEntry = false

    private val handler = Handler(Looper.getMainLooper())
    private var remaining = 0
    private var tickRunnable: Runnable? = null
    private var examStartedAt = 0L
    private var autoTask: Runnable? = null
    private var advanceTask: Runnable? = null
    private var idleTask: Runnable? = null
    private var idleCount = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentPracticeBinding.bind(view)
        app = App.of(requireContext())
        stage = StageCatalog.byId(arguments?.getString(ARG_STAGE))
        mode = PlayMode.of(arguments?.getString(ARG_MODE))
        count = arguments?.getInt(ARG_COUNT, 10) ?: 10
        wrongOnly = arguments?.getBoolean(ARG_WRONG) ?: false
        seed = arguments?.getLong(ARG_SEED, 0L) ?: 0L
        dailyDay = arguments?.getString(ARG_DAILY)
        wrongId = arguments?.getLong(ARG_WRONG_ID, 0L) ?: 0L

        KeypadBuilder.build(b.keypad, R.id.btnRepeat) { onKey(it) }
        b.tvStage.text = "S${StageCatalog.indexOf(stage) + 1} · ${stage.name} · " +
            (if (dailyDay != null) "每日挑战" else mode.label)
        b.tvTimer.visibility =
            if (mode == PlayMode.TIMED || mode == PlayMode.EXAM) View.VISIBLE else View.GONE
        b.btnRepeat.setOnClickListener { speakQuestion(manual = true) }
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
            PlayMode.EXAM -> "考试模式：每题按 ✓ 交卷，答完统一判卷"
            PlayMode.TIMED -> "限时模式：答错或超时会直接下一题"
            else -> "答对了才进入下一题"
        }
        startSession()
    }

    // ------------------------------------------------------------ 输入

    private fun onKey(label: String) {
        when (label) {
            "⌫" -> backspace()
            "✓" -> submitAnswer()
            else -> appendDigit(label)
        }
    }

    override fun appendDigit(d: String) {
        if (finished || phase != Phase.ANSWERING) return
        if (freshEntry) {
            input.clear()
            freshEntry = false
            setAnswerState(AnswerState.NORMAL)
            b.tvFeedback.text = ""
        }
        if (input.length >= MAX_DIGITS) return
        input.append(d)
        updateAnswer()
        resetIdle()
        scheduleAutoCheck()
    }

    override fun backspace() {
        if (finished || phase != Phase.ANSWERING) return
        if (input.isNotEmpty()) {
            input.deleteCharAt(input.length - 1)
            updateAnswer()
            resetIdle()
            scheduleAutoCheck()
        }
    }

    /**
     * 少按一次确认键（限时 / 练习 / 错题重练）：
     * - 数字正好等于答案 → 150ms 后自动交
     * - 位数已经比答案长 → 1.4 秒后按错误交（期间按 ⌫ 还能改）
     * 考试模式一律不自动交，否则「答对自动翻页」等于泄题。
     */
    private fun scheduleAutoCheck() {
        cancelAuto()
        if (mode == PlayMode.EXAM) return
        val q = engine?.question ?: return
        val text = input.toString()
        val value = text.toIntOrNull() ?: return
        val delay = when {
            value == q.answer -> 150L
            text.length > q.answer.toString().length -> 1400L
            else -> return
        }
        val task = Runnable {
            if (phase == Phase.ANSWERING && input.toString() == text) submitAnswer()
        }
        autoTask = task
        handler.postDelayed(task, delay)
    }

    private fun cancelAuto() {
        autoTask?.let { handler.removeCallbacks(it) }
        autoTask = null
    }

    private fun updateAnswer() {
        if (_b == null) return
        val q = engine?.question
        val shown = if (input.isEmpty()) "?" else input.toString()
        if (q != null && q.answerDen > 0) {
            // 分数题：只填分子，分母固定显示（上下结构用布局画，比 span 稳）
            b.tvAnswer.visibility = View.GONE
            b.answerFraction.visibility = View.VISIBLE
            b.tvFracNum.text = shown
            b.tvFracDen.text = q.answerDen.toString()
        } else {
            b.answerFraction.visibility = View.GONE
            b.tvAnswer.visibility = View.VISIBLE
            b.tvAnswer.text = shown
            b.tvAnswer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 62f)
        }
    }

    /** 答案框的三种状态：正常金色、答对绿色、答错红色 */
    private fun setAnswerState(state: AnswerState) {
        if (_b == null) return
        val ctx = requireContext()
        val (drawable, colorRes) = when (state) {
            AnswerState.OK -> R.drawable.bg_answer_box_ok to R.color.green
            AnswerState.BAD -> R.drawable.bg_answer_box_bad to R.color.red
            AnswerState.NORMAL -> R.drawable.bg_answer_box to R.color.accent
        }
        b.answerBox.setBackgroundResource(drawable)
        val color = Ui.color(ctx, colorRes)
        b.tvAnswer.setTextColor(color)
        b.tvFracNum.setTextColor(color)
        b.tvFracDen.setTextColor(color)
        b.fracBar.setBackgroundColor(color)
    }

    // ------------------------------------------------------------ 会话

    private fun startSession() {
        if (wrongId > 0) {
            app.repo.async({ app.repo.wrongQuestionById(wrongId)?.let { listOf(it) } }) { questions ->
                if (!isAdded || _b == null) return@async
                if (questions == null) {
                    b.tvQuestion.text = "这道错题不见了"
                    return@async
                }
                openSession(questions)
            }
        } else if (wrongOnly) {
            app.repo.async({ app.repo.wrongQuestions(null, count) }) { questions ->
                if (!isAdded || _b == null) return@async
                if (questions.isEmpty()) {
                    b.tvQuestion.text = "没有错题"
                    b.tvKeyHint.text = "先去练几轮，错题会自动收进来"
                    b.btnRepeat.alpha = 0.35f
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
        // 考试：四成是这个阶段最常错的题，剩下的正常出
        if (pre == null && mode == PlayMode.EXAM) {
            app.repo.async({
                app.repo.mixedQuestions(
                    stage, QuestionStyle.of(app.settings.questionStyle), planned, share = 0.4
                )
            }) { list ->
                if (!isAdded || _b == null) return@async
                openSession(list)
            }
            return
        }
        app.repo.async({
            app.repo.openSession(stage.id, mode, planned, app.settings.playerId)
        }) { id ->
            if (!isAdded || _b == null) return@async
            sessionId = id
            engine = SessionEngine(
                stage, mode, planned, id, app.repo, pre,
                QuestionStyle.of(app.settings.questionStyle), seed
            )
            startCountdown {
                render(animate = false)
                speakQuestion()
                b.keypad.getChildAt(4)?.requestFocus()
            }
        }
    }

    /**
     * 开局倒计时：准备好了吗 → 三 → 二 → 一 → 开始。
     * 倒计时期间锁住输入，考试/限时的计时也从「开始」之后才算。
     */
    private fun startCountdown(then: () -> Unit) {
        phase = Phase.LOCKED
        b.keypad.alpha = 0.2f
        b.answerBox.alpha = 0.2f
        b.tvFeedback.text = ""
        b.tvHint.text = ""
        b.tvQuestion.text = Speech.READY
        b.tvQuestion.setTextSize(TypedValue.COMPLEX_UNIT_SP, 72f)
        b.tvQuestion.minHeight = 0
        b.tvProgress.text = "准备开始"
        speakRaw(Speech.READY)
        val numbers = listOf(3, 2, 1)
        numbers.forEachIndexed { i, n ->
            handler.postDelayed({
                if (!isAdded || _b == null) return@postDelayed
                b.tvQuestion.text = n.toString()
                b.tvQuestion.setTextSize(TypedValue.COMPLEX_UNIT_SP, 140f)
                Sfx.tick()
                speakRaw(Speech.countdown(n))
            }, 1100L + i * 950L)
        }
        handler.postDelayed({
            if (!isAdded || _b == null) return@postDelayed
            b.keypad.alpha = 1f
            b.answerBox.alpha = 1f
            b.tvQuestion.setTextSize(TypedValue.COMPLEX_UNIT_SP, 88f)
            speakRaw(Speech.GO)
            then()
        }, 1100L + 3 * 950L)
    }

    private fun render(animate: Boolean) {
        val e = engine ?: return
        val q = e.question ?: return
        phase = Phase.ANSWERING
        freshEntry = false
        cancelAuto()
        b.tvProgress.text = "第 ${e.index + 1}/${e.total} 题"
        b.progressBar.max = e.total
        b.progressBar.progress = e.index
        // 找错题和填空题的题面里已经有等号了，别再补一个
        val expr = if (q.text.contains("=")) q.text else "${q.text} ="
        if (q.findError && expr.contains("=")) {
            // 改错题：等号后面那个错的结果标红，孩子一眼看到「哪儿不对」
            b.tvQuestion.setTextSize(TypedValue.COMPLEX_UNIT_SP, when {
                expr.length <= 6 -> 88f
                expr.length <= 10 -> 70f
                else -> 54f
            })
            b.tvQuestion.minHeight = 0
            val sp = android.text.SpannableString(expr)
            sp.setSpan(
                android.text.style.ForegroundColorSpan(Ui.color(requireContext(), R.color.red)),
                expr.indexOf('=') + 1, expr.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            b.tvQuestion.text = sp
        } else if (MathText.hasFraction(expr)) {
            // 分数用上下结构排版，字号压小一点给分数线留空间
            MathText.apply(b.tvQuestion, expr)
            b.tvQuestion.setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f)
            b.tvQuestion.minHeight = (150f * resources.displayMetrics.density).toInt()
        } else {
            b.tvQuestion.text = expr
            b.tvQuestion.minHeight = 0
            b.tvQuestion.setTextSize(TypedValue.COMPLEX_UNIT_SP, when {
                expr.length <= 6 -> 88f
                expr.length <= 10 -> 70f
                else -> 54f
            })
        }
        b.tvFeedback.text = ""
        b.tvHint.text = if (q.findError) "这道题有错误，填正确答案" else ""
        b.tvHint.setTextColor(
            Ui.color(requireContext(), if (q.findError) R.color.accent else R.color.text_dim)
        )
        pendingTip?.let { tip ->
            b.tvHint.text = tip
            b.tvHint.setTextColor(Ui.color(requireContext(), R.color.text_dim))
            val shown = tip
            handler.postDelayed({
                if (_b != null && b.tvHint.text == shown) b.tvHint.text = ""
            }, 2600)
            pendingTip = null
        }
        input.clear()
        setAnswerState(AnswerState.NORMAL)
        updateAnswer()
        b.keypad.getChildAt(4)?.requestFocus()   // 每题都把焦点放回 5，少按几下方向键
        startTimerForQuestion()
        resetIdle()
        if (animate) Ui.pageTurn(b.questionBox)
    }

    private fun rate(): Float = modeRate() * app.settings.speechScale

    private fun modeRate(): Float = when (mode) {
        PlayMode.PRACTICE -> 0.95f
        PlayMode.TIMED -> 1.30f
        PlayMode.EXAM -> 1.10f
        PlayMode.WRONG -> 1.0f
        PlayMode.CHALLENGE -> 1.1f
    }

    /** manual=true 是孩子主动按「再读一遍」，没语音时给个说明 */
    private fun speakQuestion(manual: Boolean = false) {
        val q = engine?.question ?: return
        if (q.speak.isBlank()) {
            if (manual) b.tvHint.text = "这道题没有朗读，直接看图算"
            return
        }
        speakRaw(q.speak)
    }

    // ------------------------------------------------------------ 语音 + 朗读按钮闪动

    private var pulse: ValueAnimator? = null

    /** 正在朗读时让「再读一遍」按钮呼吸式闪动，孩子一眼看出声音是从这儿来的 */
    private fun startSpeakPulse() {
        if (pulse != null || _b == null) return
        pulse = ValueAnimator.ofFloat(1f, 0.35f).apply {
            duration = 300
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { if (_b != null) b.btnRepeat.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun stopSpeakPulse() {
        pulse?.cancel()
        pulse = null
        if (_b != null) b.btnRepeat.alpha = 1f
    }

    /** 只播报，不管后继动作 */
    private fun speakRaw(text: String) {
        if (text.isBlank()) return
        startSpeakPulse()
        app.speaker.speak(text, rate()) { stopSpeakPulse() }
    }

    /** 播报 + 说完再做下一件事（换题、翻页都靠它对齐） */
    private fun say(text: String, then: (() -> Unit)? = null) {
        if (text.isBlank()) {
            then?.invoke()
            return
        }
        startSpeakPulse()
        app.speaker.speak(text, rate()) {
            stopSpeakPulse()
            then?.invoke()
        }
    }

    // ------------------------------------------------------------ 计时 / 发呆

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

    private fun idleEnabled(): Boolean = mode == PlayMode.PRACTICE || mode == PlayMode.WRONG

    private fun resetIdle() {
        stopIdle()
        idleCount = 0
        postIdle()
    }

    private fun postIdle() {
        if (!idleEnabled() || finished) return
        stopIdle()
        val task = Runnable { onIdle() }
        idleTask = task
        handler.postDelayed(task, IDLE_MS)
    }

    private fun stopIdle() {
        idleTask?.let { handler.removeCallbacks(it) }
        idleTask = null
    }

    /**
     * 10 秒没动静：再把题读一遍并给思路提示，最多 2 次。
     * 一年级孩子不会主动求助，卡住就发呆，得有人推一把。
     */
    private fun onIdle() {
        if (!isAdded || _b == null || finished || phase != Phase.ANSWERING) return
        if (idleCount >= IDLE_MAX) return
        val q = engine?.question ?: return
        idleCount++
        val prompt = Speech.idlePrompt(idleCount)
        val text = if (q.speak.isBlank()) prompt else "$prompt，${q.speak}"
        speakRaw(text)
        if (q.hint.isNotBlank()) {
            b.tvHint.text = "小提示：${q.hint}"
            b.tvHint.setTextColor(Ui.color(requireContext(), R.color.accent))
        }
        if (idleCount == 1) {
            b.tvFeedback.text = "还没想好？再听一遍题目"
            b.tvFeedback.setTextColor(Ui.color(requireContext(), R.color.text_dim))
        }
        if (idleCount < IDLE_MAX) postIdle()
    }

    private fun onTimeout() {
        val e = engine ?: return
        if (finished || phase != Phase.ANSWERING) return
        val r = e.submit(null, timedOut = true) ?: return
        cancelAuto()
        stopIdle()
        phase = Phase.LOCKED
        Sfx.wrong()
        setAnswerState(AnswerState.BAD)
        Ui.shake(b.answerBox)
        b.tvFeedback.text = "时间到，下一题"
        b.tvFeedback.setTextColor(Ui.color(requireContext(), R.color.red))
        say(Speech.timedOutWithAnswer(r.question)) { advanceAfter(360) }
    }

    // ------------------------------------------------------------ 提交

    private fun submitAnswer() {
        val e = engine ?: return
        if (finished || phase != Phase.ANSWERING) return
        val text = input.toString()
        if (text.isEmpty()) return
        val value = text.toIntOrNull() ?: return
        val r = e.submit(value) ?: return
        cancelAuto()
        stopIdle()
        phase = Phase.LOCKED
        when {
            mode == PlayMode.EXAM -> onExamSubmitted()
            r.correct -> onCorrect(r)
            r.skipped -> onSkipped(r)
            r.retry -> onRetry(r)
            else -> onWrongGoNext(r)
        }
    }

    /** 考试：不透露对错，收到就翻页（连「已记录」这种字都不写） */
    private fun onExamSubmitted() {
        Sfx.tick()
        setAnswerState(AnswerState.NORMAL)
        advanceAfter(280)
    }

    private fun onCorrect(r: SessionEngine.SubmitResult) {
        Sfx.correct()
        setAnswerState(AnswerState.OK)
        Ui.pop(b.answerBox)
        if (r.combo >= 3) {
            Sfx.combo(r.combo)
            b.tvFeedback.text = "回答正确！${r.combo} 连击"
        } else {
            b.tvFeedback.text = "回答正确！"
        }
        b.tvFeedback.setTextColor(Ui.color(requireContext(), R.color.green))
        say(Speech.CORRECT) { advanceAfter(340) }
    }

    /** 连错到上限：跳过这题，把思路留到下一题的提示行上 */
    private fun onSkipped(r: SessionEngine.SubmitResult) {
        Sfx.wrong()
        setAnswerState(AnswerState.BAD)
        Ui.shake(b.answerBox)
        b.tvFeedback.text = "这题先跳过，下一题"
        b.tvFeedback.setTextColor(Ui.color(requireContext(), R.color.accent))
        pendingTip = "上一题先跳过：${r.question.hint.ifBlank { "回头在错题本里再练" }}"
        say(Speech.skipped(r.question)) { advanceAfter(320) }
    }

    /**
     * 练习 / 错题重练答错：停在本题，语音给思路。
     * 输入框里的错答案先留着（红框），孩子一按数字就重新开始——别偷偷清空。
     */
    private fun onRetry(r: SessionEngine.SubmitResult) {
        Sfx.wrong()
        setAnswerState(AnswerState.BAD)
        Ui.shake(b.answerBox)
        b.tvFeedback.text = "回答错误，再算一遍"
        b.tvFeedback.setTextColor(Ui.color(requireContext(), R.color.red))
        if (r.question.hint.isNotBlank()) {
            b.tvHint.text = "小提示：${r.question.hint}"
            b.tvHint.setTextColor(Ui.color(requireContext(), R.color.accent))
        }
        phase = Phase.ANSWERING
        freshEntry = true
        resetIdle()
        speakRaw(Speech.retryWrong(r.question))
    }

    /** 限时 / 其它不重算的模式：报答案，然后下一题 */
    private fun onWrongGoNext(r: SessionEngine.SubmitResult) {
        Sfx.wrong()
        setAnswerState(AnswerState.BAD)
        Ui.shake(b.answerBox)
        b.tvFeedback.text =
            if (r.timedOut) "时间到，下一题" else "回答错误，正确答案是 ${r.question.answerText}"
        b.tvFeedback.setTextColor(Ui.color(requireContext(), R.color.red))
        val line = if (r.timedOut) Speech.timedOutWithAnswer(r.question)
        else Speech.wrongWithAnswer(r.question)
        say(line) { advanceAfter(360) }
    }

    private fun advanceAfter(delay: Long) {
        if (finished || _b == null) return
        phase = Phase.ADVANCING
        advanceTask?.let { handler.removeCallbacks(it) }
        val task = Runnable { advanceNow() }
        advanceTask = task
        handler.postDelayed(task, delay)
    }

    private fun advanceNow() {
        if (!isAdded || _b == null || finished) return
        val e = engine ?: return
        if (e.isLast) {
            finishSession(true)
        } else {
            e.advance()
            render(animate = true)
            speakQuestion()
            b.keypad.getChildAt(4)?.requestFocus()
        }
    }

    // ------------------------------------------------------------ 收尾

    private var quitDialog: Dialog? = null

    private fun showQuitDialog() {
        if (quitDialog?.isShowing == true || finished) return
        stopTimer()
        stopIdle()
        val done = engine?.records?.size ?: 0
        val dialog = Dialog(requireContext(), R.style.Theme_MathKing_Dialog)
        dialog.setContentView(R.layout.dialog_quit)
        dialog.setCancelable(true)
        dialog.findViewById<TextView>(R.id.tvQuitHint)?.text = if (done == 0) {
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
        if (finished) return
        if (mode == PlayMode.TIMED && remaining > 0) tick()
        if (mode == PlayMode.EXAM) tick()
        postIdle()
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
        phase = Phase.DONE
        stopTimer()
        stopIdle()
        cancelAuto()
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
        phase = Phase.DONE
        stopTimer()
        stopIdle()
        cancelAuto()
        app.speaker.stop()
        val e = engine ?: return
        val summary = e.summary(finishedOk)
        app.repo.async({
            // 解锁进度只认「正常练习 / 限时 / 考试 / 每日挑战」，错题重练不算
            val overall = app.repo.overall(stage.id, excludeWrongBook = true)
            val totalQuestions = overall.questions + summary.total
            val totalCorrect = overall.correct + summary.correctFirst
            val totalAccuracy = if (totalQuestions == 0) 0.0 else totalCorrect * 100.0 / totalQuestions
            // 解锁看的是「这个阶段的累计水平」，所以速度也用累计平均，不能只看这一局
            val totalAvgSeconds =
                if (totalQuestions == 0) summary.avgSeconds
                else (overall.msTotal + summary.durationMs) / 1000.0 / totalQuestions
            val power = Analytics.power(
                totalAccuracy, totalAvgSeconds, stage.targetSeconds,
                totalQuestions, stage.weight
            )
            app.repo.closeSession(
                sessionId, summary.total, summary.correctFirst, summary.wrong,
                summary.durationMs, summary.score, power, finishedOk
            )
            if (finishedOk && dailyDay != null) {
                app.repo.saveDaily(dailyDay!!, stage.id, summary.total, summary.correctFirst)
            }
            val unlocked = if (finishedOk) {
                app.settings.checkUnlock(stage, totalQuestions, totalAccuracy, totalAvgSeconds, power)
            } else null
            val progress = if (finishedOk) {
                app.settings.unlockProgress(stage, totalQuestions, totalAccuracy, totalAvgSeconds, power)
            } else null
            Triple(summary.copy(power = power), unlocked, progress)
        }) { result ->
            if (!isAdded) return@async
            if (finishedOk) {
                if (result.second != null) Sfx.levelUp() else Sfx.finish()
            }
            (activity as? MainActivity)?.showResult(result.first, result.second, result.third)
        }
    }

    override fun onDestroyView() {
        stopSpeakPulse()
        stopTimer()
        stopIdle()
        cancelAuto()
        advanceTask?.let { handler.removeCallbacks(it) }
        advanceTask = null
        handler.removeCallbacksAndMessages(null)
        savePartial()
        app.speaker.stop()
        _b = null
        super.onDestroyView()
    }
}

package com.mathking.tv.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.mathking.tv.App
import com.mathking.tv.MainActivity
import com.mathking.tv.R
import com.mathking.tv.audio.Speech
import com.mathking.tv.data.PlayMode
import com.mathking.tv.data.StageCatalog
import com.mathking.tv.data.WrongEntry
import com.mathking.tv.databinding.FragmentWrongbookBinding
import com.mathking.tv.ui.text.MathText
import com.mathking.tv.util.Ui

/**
 * 错题本：一道一道讲给孩子听。
 *
 * 交互（电视遥控器）：
 * - 聚焦某道题 → 停 [SPEAK_DELAY_MS] 毫秒自动讲解（题面 + 答案 + 思路），
 *   焦点移到别的题时立刻掐断上一条语音，不会两条声音叠在一起；
 * - 聚焦后按 ✓ → 暂停 / 继续朗读（孩子想停下来自己想）；
 * - 顶部「难度」行：左右键切难度（全部 / 有错题的每个难度），列表跟着过滤；
 * - 列表里按 ↑ → 直接落到「重练全部错题」；再按 ↑ 依次循环
 *   只练这道 → 返回 → 难度行 → 重练全部错题（一直按不会卡住）；
 * - 底部「只练这道」练当前选中的那一道，「重练全部错题」练当前难度下的全部错题。
 */
class WrongBookFragment : Fragment(R.layout.fragment_wrongbook) {

    companion object {
        /** 选中后等这么久再开口，免得快速划过时一路念下去 */
        private const val SPEAK_DELAY_MS = 800L

        private const val TIP_DEFAULT =
            "选中一道题停 0.8 秒自动讲解，按 ✓ 暂停/继续；按 ↑ 到「重练全部错题」"
    }

    private var _b: FragmentWrongbookBinding? = null
    private val b get() = _b!!

    private val handler = Handler(Looper.getMainLooper())
    private var speakTask: Runnable? = null

    /** 全量错题（一次查完，切难度只在内存里过滤，不再打库） */
    private var allItems: List<WrongEntry> = emptyList()
    private var mastered = 0

    /** 当前难度过滤：null = 全部 */
    private var stageFilter: String? = null

    private data class StageOption(val stageId: String?, val label: String)

    private var options: List<StageOption> = emptyList()
    private var optionIndex = 0

    private var items: List<WrongEntry> = emptyList()
    private var selected: WrongEntry? = null
    private val rows = LinkedHashMap<Long, View>()

    private var loadedOnce = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentWrongbookBinding.bind(view)
        val act = activity as? MainActivity
        b.btnBack.setOnClickListener {
            stopSpeak()
            act?.goHome()
        }
        b.btnPractice.setOnClickListener {
            stopSpeak()
            val s = App.of(requireContext()).settings
            // 选了某个难度就只练这个难度的错题，选「全部」才练整本
            act?.navigate(
                PracticeFragment.newInstance(
                    stageFilter ?: s.stageId,
                    PlayMode.WRONG.id,
                    20,
                    true,
                    wrongStageOnly = stageFilter != null
                )
            )
        }
        b.btnPracticeOne.setOnClickListener {
            val item = selected
            if (item == null) {
                b.tvTip.text = "先用方向键选中一道错题"
                return@setOnClickListener
            }
            stopSpeak()
            act?.navigate(
                PracticeFragment.newInstance(
                    item.stageId,
                    PlayMode.WRONG.id,
                    1,
                    true,
                    wrongId = item.id
                )
            )
        }
        Ui.focusZoom(b.btnPracticeOne, b.btnPractice, b.btnBack)
        Ui.noClip(view, b.btnPracticeOne.parent as View)

        setupStageRow()
        setupFocusLoop()
        load()
    }

    override fun onResume() {
        super.onResume()
        // onViewCreated 里已经读过一次，避免重复构建列表
        if (loadedOnce) load()
    }

    override fun onPause() {
        super.onPause()
        stopSpeak()
    }

    // ------------------------------------------------------------ 难度选择

    /** 顶部「难度」行：左右键在该孩子有错题的难度之间循环 */
    private fun setupStageRow() {
        b.stageRow.setOnClickListener { switchStage(1) }
        b.stageRow.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    switchStage(-1)
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    switchStage(1)
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!focusFirstRow()) b.btnPractice.takeIf { it.visibility == View.VISIBLE }
                        ?.requestFocus()
                    true
                }
                // 难度行的上键也归这里管：同一个 View 只能有一个 OnKeyListener，
                // 分成两处 set 会互相覆盖（本会话踩过：难度行上左右键直接失灵）
                KeyEvent.KEYCODE_DPAD_UP -> {
                    upTargetOf(b.btnPractice).requestFocus()
                    true
                }
                else -> false
            }
        }
    }

    private fun switchStage(delta: Int) {
        if (options.size <= 1) return
        optionIndex = (optionIndex + delta + options.size) % options.size
        stageFilter = options[optionIndex].stageId
        b.tvStageValue.text = options[optionIndex].label
        // 切难度时焦点留在这一行：不然每按一次右键焦点就跑去列表第一题，没法连着切
        renderList(focusFirst = false)
    }

    // ------------------------------------------------------------ 上键循环

    /**
     * 上键焦点环：列表任意一行 ↑ → 「重练全部错题」；
     * 按钮之间 ↑ 依次走 只练这道 → 返回 → 难度行 → 重练全部错题（循环），一直按不会卡死。
     */
    private fun setupFocusLoop() {
        val upTargets = mapOf(
            b.btnPractice to b.btnPracticeOne,
            b.btnPracticeOne to b.btnBack,
            b.btnBack to b.stageRow
        )
        upTargets.forEach { (from, to) ->
            from.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_UP) {
                    return@setOnKeyListener false
                }
                upTargetOf(to).requestFocus()
                true
            }
        }
    }

    /** 列表行：↑ 落到「重练全部错题」（用户要求的第一跳） */
    private fun hookRowUp(card: View) {
        card.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_UP) {
                return@setOnKeyListener false
            }
            val target = if (b.btnPractice.visibility == View.VISIBLE) b.btnPractice else b.stageRow
            target.requestFocus()
            true
        }
    }

    /** 上键的下一个落点：跳过被隐藏的控件（空列表时那两个按钮是 gone） */
    private fun upTargetOf(to: View): View {
        var next = to
        var guard = 0
        while (next.visibility != View.VISIBLE && guard < 5) {
            next = when (next) {
                b.btnPracticeOne -> b.btnBack
                b.btnPractice -> b.btnPracticeOne
                b.btnBack -> b.stageRow
                else -> b.btnPractice
            }
            guard++
        }
        return next
    }

    private fun focusFirstRow(): Boolean {
        val first = items.firstOrNull() ?: return false
        val v = rows[first.id] ?: return false
        v.requestFocus()
        return true
    }

    // ------------------------------------------------------------ 数据

    private fun load() {
        loadedOnce = true
        val app = App.of(requireContext())
        app.repo.async({ app.repo.wrongItems(null) to app.repo.masteredCount() }) { pair ->
            if (!isAdded || _b == null) return@async
            allItems = pair.first
            mastered = pair.second
            buildOptions()
            renderList()
        }
    }

    /** 选项 = 全部 + 有错题的每一个难度（按 S1…S12 顺序），带上各自的条数 */
    private fun buildOptions() {
        val byStage = allItems.groupBy { it.stageId }
        val list = mutableListOf(StageOption(null, "全部（${allItems.size} 道）"))
        StageCatalog.available.forEach { st ->
            val n = byStage[st.id]?.size ?: return@forEach
            val no = StageCatalog.indexOf(st) + 1
            list.add(StageOption(st.id, "S$no · ${st.name}（$n 道）"))
        }
        options = list
        // 换过难度后回来，尽量停在原难度上
        val idx = list.indexOfFirst { it.stageId == stageFilter }
        optionIndex = if (idx >= 0) idx else 0
        stageFilter = list[optionIndex].stageId
        b.tvStageValue.text = list[optionIndex].label
        b.stageRow.visibility = if (list.size > 1) View.VISIBLE else View.GONE
    }

    private fun renderList(focusFirst: Boolean = true) {
        stopSpeak()
        b.tvTip.text = TIP_DEFAULT
        items = if (stageFilter == null) allItems else allItems.filter { it.stageId == stageFilter }
        b.list.removeAllViews()
        rows.clear()
        selected = null

        val scope = options.getOrNull(optionIndex)?.stageId
        val scopeName = if (scope == null) "" else
            " · S${StageCatalog.indexOf(StageCatalog.byId(scope)) + 1}"

        if (items.isEmpty()) {
            b.tvWrongSub.text = "0 道待攻克$scopeName · 已攻克 $mastered 道"
            b.tvEmpty.visibility = View.VISIBLE
            b.scroll.visibility = View.GONE
            b.btnPractice.visibility = View.GONE
            b.btnPracticeOne.visibility = View.GONE
            b.stageRow.requestFocus()
            return
        }
        b.tvEmpty.visibility = View.GONE
        b.scroll.visibility = View.VISIBLE
        b.btnPractice.visibility = View.VISIBLE
        b.btnPracticeOne.visibility = View.VISIBLE
        b.tvWrongSub.text =
            "${items.size} 道待攻克$scopeName · 已攻克 $mastered 道 · 连对 2 次自动毕业"
        items.forEach { addRow(it) }
        // 等列表真正挂到窗口上再要焦点：addView 之后立刻 requestFocus 会失败，
        // 焦点落在底部按钮上，孩子进来就听不到讲解
        if (focusFirst) b.list.post { if (_b != null) focusFirstRow() }
    }

    // ------------------------------------------------------------ 每一行

    private fun addRow(item: WrongEntry) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val stageName = StageCatalog.byId(item.stageId).name

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.selector_row)
            setPadding(
                (18 * density).toInt(), (10 * density).toInt(),
                (18 * density).toInt(), (10 * density).toInt()
            )
            isFocusable = true
            isClickable = true
            setOnClickListener { togglePause(item) }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    select(item)
                    scheduleSpeak(item)
                } else {
                    cancelScheduledSpeak()
                    // 焦点离开列表（比如按 ↑ 去按钮行）就不要再挂着金色描边：
                    // selector_row 的 state_selected 和 state_focused 用的是同一张底，
                    // 不清掉的话孩子会以为这道题还被选中着
                    view?.post { clearHighlightIfLeftList() }
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (8 * density).toInt()
            layoutParams = lp
        }
        hookRowUp(card)

        val raw = if (item.expr.contains("=")) "${item.expr}（正确答案 ${item.answer}）"
        else "${item.expr} = ${item.answer}"
        val expr = TextView(ctx).apply {
            text = MathText.spans(raw)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTextColor(Ui.color(ctx, R.color.text))
        }
        card.addView(expr, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val status = TextView(ctx).apply {
            text = "错 ${item.wrongCount} 次 · 连对 ${item.streak}/2"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Ui.color(ctx, if (item.streak > 0) R.color.green else R.color.red))
        }
        card.addView(status)

        val stage = TextView(ctx).apply {
            text = stageName
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Ui.color(ctx, R.color.text_dim))
            setPadding((14 * density).toInt(), 0, 0, 0)
        }
        card.addView(stage)

        b.list.addView(card)
        rows[item.id] = card
    }

    private fun select(item: WrongEntry) {
        selected = item
        rows.forEach { (id, view) -> view.isSelected = id == item.id }
    }

    /** 焦点已经不在任何一行上了：把金色高亮收掉，提示行改成「刚选中的是哪道」 */
    private fun clearHighlightIfLeftList() {
        if (_b == null) return
        if (rows.values.any { it.hasFocus() }) return
        rows.values.forEach { it.isSelected = false }
        val cur = selected
        b.tvTip.text = if (cur == null) TIP_DEFAULT
        else "已选中「${cur.expr}」·「只练这道」就练它"
    }

    // ------------------------------------------------------------ 讲解

    private fun scheduleSpeak(item: WrongEntry) {
        cancelScheduledSpeak()
        // 换题先闭嘴：两条语音叠在一起孩子什么也听不清
        App.of(requireContext()).speaker.stop()
        b.tvTip.text = "正在准备讲解…"
        val task = Runnable {
            if (!isAdded) return@Runnable
            if (selected?.id != item.id) return@Runnable
            speak(item)
        }
        speakTask = task
        handler.postDelayed(task, SPEAK_DELAY_MS)
    }

    private fun cancelScheduledSpeak() {
        speakTask?.let { handler.removeCallbacks(it) }
        speakTask = null
    }

    private fun speak(item: WrongEntry) {
        val app = App.of(requireContext())
        val q = app.repo.questionOf(item)
        val text = Speech.explain(q)
        b.tvTip.text = "正在讲解，按 ✓ 可以暂停"
        app.speaker.speak(text, 0.95f * app.settings.speechScale) {
            if (isAdded && selected?.id == item.id) b.tvTip.text = "讲完了，按 ✓ 再听一遍"
        }
    }

    /** 聚焦时按 ✓：暂停 ↔ 继续；没在讲就重新讲一遍 */
    private fun togglePause(item: WrongEntry) {
        val app = App.of(requireContext())
        val speaker = app.speaker
        cancelScheduledSpeak()
        when {
            speaker.isPaused -> {
                val ok = speaker.resume()
                b.tvTip.text = if (ok) "继续讲解…" else "这台设备不支持续播，按 ✓ 重新讲"
            }
            speaker.isSpeaking -> {
                val ok = speaker.pause()
                b.tvTip.text = if (ok) "已暂停，再按 ✓ 继续" else "这台设备不支持暂停"
            }
            else -> speak(item)
        }
    }

    private fun stopSpeak() {
        cancelScheduledSpeak()
        if (!isAdded) return
        App.of(requireContext()).speaker.stop()
    }

    override fun onDestroyView() {
        cancelScheduledSpeak()
        handler.removeCallbacksAndMessages(null)
        if (isAdded) App.of(requireContext()).speaker.stop()
        _b = null
        super.onDestroyView()
    }
}

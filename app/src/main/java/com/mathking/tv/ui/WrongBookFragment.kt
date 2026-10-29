package com.mathking.tv.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
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
 * - 底部「只练这道」练当前选中的那一道，「重练全部错题」练整个错题本。
 */
class WrongBookFragment : Fragment(R.layout.fragment_wrongbook) {

    companion object {
        /** 选中后等这么久再开口，免得快速划过时一路念下去 */
        private const val SPEAK_DELAY_MS = 800L
    }

    private var _b: FragmentWrongbookBinding? = null
    private val b get() = _b!!

    private val handler = Handler(Looper.getMainLooper())
    private var speakTask: Runnable? = null

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
            act?.navigate(PracticeFragment.newInstance(s.stageId, PlayMode.WRONG.id, 20, true))
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

    // ------------------------------------------------------------ 数据

    private fun load() {
        loadedOnce = true
        val app = App.of(requireContext())
        app.repo.async({ app.repo.wrongItems(null) to app.repo.masteredCount() }) { pair ->
            val list = pair.first
            if (!isAdded || _b == null) return@async
            items = list
            b.list.removeAllViews()
            rows.clear()
            selected = null
            if (list.isEmpty()) {
                b.tvWrongSub.text = "0 道待攻克 · 已攻克 ${pair.second} 道"
                b.tvEmpty.visibility = View.VISIBLE
                b.scroll.visibility = View.GONE
                b.btnPractice.visibility = View.GONE
                b.btnPracticeOne.visibility = View.GONE
                b.btnBack.requestFocus()
                return@async
            }
            b.tvEmpty.visibility = View.GONE
            b.scroll.visibility = View.VISIBLE
            b.btnPractice.visibility = View.VISIBLE
            b.btnPracticeOne.visibility = View.VISIBLE
            b.tvWrongSub.text =
                "${list.size} 道待攻克 · 已攻克 ${pair.second} 道 · 连对 2 次自动毕业"
            list.forEach { addRow(it) }
            // 等列表真正挂到窗口上再要焦点：addView 之后立刻 requestFocus 会失败，
            // 焦点落在底部按钮上，孩子进来就听不到讲解
            b.list.post {
                if (_b != null) rows[list.first().id]?.requestFocus()
            }
        }
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
            setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    select(item)
                    scheduleSpeak(item)
                } else {
                    cancelScheduledSpeak()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (8 * density).toInt()
            layoutParams = lp
        }

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

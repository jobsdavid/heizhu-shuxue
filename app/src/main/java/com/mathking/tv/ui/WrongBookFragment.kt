package com.mathking.tv.ui

import android.os.Bundle
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
import com.mathking.tv.data.PlayMode
import com.mathking.tv.data.StageCatalog
import com.mathking.tv.data.WrongEntry
import com.mathking.tv.databinding.FragmentWrongbookBinding
import com.mathking.tv.util.Ui

class WrongBookFragment : Fragment(R.layout.fragment_wrongbook) {

    private var _b: FragmentWrongbookBinding? = null
    private val b get() = _b!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentWrongbookBinding.bind(view)
        val act = activity as? MainActivity
        b.btnBack.setOnClickListener { act?.goHome() }
        b.btnPractice.setOnClickListener {
            val s = App.of(requireContext()).settings
            act?.navigate(
                PracticeFragment.newInstance(s.stageId, PlayMode.WRONG.id, 20, true)
            )
        }
        Ui.focusZoom(b.btnPractice, b.btnBack)
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val app = App.of(requireContext())
        app.repo.async({ app.repo.wrongItems(null) to app.repo.masteredCount() }) { pair ->
            val items = pair.first
            if (!isAdded || _b == null) return@async
            b.list.removeAllViews()
            if (items.isEmpty()) {
                b.tvWrongSub.text = "0 道待攻克 · 已攻克 ${pair.second} 道"
                b.tvEmpty.visibility = View.VISIBLE
                b.scroll.visibility = View.GONE
                b.btnPractice.visibility = View.GONE
                b.btnBack.requestFocus()
                return@async
            }
            b.tvEmpty.visibility = View.GONE
            b.scroll.visibility = View.VISIBLE
            b.btnPractice.visibility = View.VISIBLE
            b.tvWrongSub.text = "${items.size} 道待攻克 · 已攻克 ${pair.second} 道 · 连对 2 次自动毕业"
            items.forEach { addRow(it) }
        }
    }

    private fun addRow(item: WrongEntry) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val stageName = StageCatalog.byId(item.stageId).name

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_card)
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            isFocusable = true
            isClickable = true
            setOnClickListener {
                App.of(ctx).speaker.speak("${item.expr} 等于 ${item.answer}", 0.95f)
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (6 * density).toInt()
            layoutParams = lp
        }

        val raw = if (item.expr.contains("=")) "${item.expr}（正确答案 ${item.answer}）"
        else "${item.expr} = ${item.answer}"
        val expr = TextView(ctx).apply {
            text = com.mathking.tv.ui.text.MathText.spans(raw)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(Ui.color(ctx, R.color.text))
        }
        val lp1 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        card.addView(expr, lp1)

        val status = TextView(ctx).apply {
            text = "错 ${item.wrongCount} 次 · 连续答对 ${item.streak}/2"
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
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }
}

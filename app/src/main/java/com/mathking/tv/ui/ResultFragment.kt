package com.mathking.tv.ui

import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.mathking.tv.App
import com.mathking.tv.MainActivity
import com.mathking.tv.R
import com.mathking.tv.data.PlayMode
import com.mathking.tv.data.StageCatalog
import com.mathking.tv.databinding.FragmentResultBinding
import com.mathking.tv.stats.Analytics
import com.mathking.tv.util.Fmt
import com.mathking.tv.util.Ui

class ResultFragment : Fragment(R.layout.fragment_result) {

    private var _b: FragmentResultBinding? = null
    private val b get() = _b!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentResultBinding.bind(view)
        val act = activity as? MainActivity ?: return
        val summary = act.lastSummary ?: run {
            act.goHome()
            return
        }
        val unlocked = act.lastUnlockedStage
        val app = App.of(requireContext())

        b.tvTitle.text = when (summary.mode) {
            PlayMode.EXAM -> "考试结束！"
            PlayMode.WRONG -> "错题重练完成！"
            else -> "练习完成！"
        }
        b.tvStars.text = buildString {
            repeat(3) { i ->
                if (i > 0) append("  ")
                append(if (i < summary.stars) "★" else "☆")
            }
        }
        b.tvScore.text = Fmt.score(summary.score)
        b.tvLine1.text = "一次答对 ${summary.correctFirst} / ${summary.total} 题" +
            if (summary.wrong > 0) "，错了 ${summary.wrong} 题" else "，全对！"
        b.tvLine2.text = "正确率 ${Fmt.pct1(summary.accuracy)} · 用时 ${Fmt.duration(summary.durationMs)}" +
            " · 平均 ${Fmt.sec(summary.avgSeconds)} 秒/题"

        val rank = Analytics.rank(summary.power)
        val progress = act.lastUnlockProgress
        b.tvLine3.text = buildString {
            if (summary.power > 0) append("算力 ${summary.power.toInt()}（${rank.name}）")
            when {
                unlocked != null -> append(" · 已解锁「${unlocked.name}」")
                progress != null -> {
                    append("\n距离解锁「${progress.nextStageName}」：")
                    val parts = mutableListOf<String>()
                    if (progress.needMoreQuestions > 0) parts.add("还差 ${progress.needMoreQuestions} 题")
                    if (progress.needMoreAccuracy > 0) {
                        parts.add("正确率差 ${String.format("%.1f", progress.needMoreAccuracy)}%")
                    }
                    if (parts.isEmpty()) parts.add("马上就能解锁")
                    append(parts.joinToString(" · "))
                }
            }
        }
        b.tvLine3.setTextColor(Ui.color(requireContext(), if (unlocked != null) R.color.accent else R.color.blue))

        // 解锁了就把话说明白，别只藏在第三行蓝字后面
        if (unlocked != null) {
            val idx = StageCatalog.indexOf(unlocked)
            b.unlockBox.visibility = android.view.View.VISIBLE
            b.tvUnlockName.text = unlocked.name
            b.tvUnlockTip.text = "第 ${idx + 1} 阶 · ${unlocked.tip}"
            b.btnGoNext.setOnClickListener {
                app.settings.stageId = unlocked.id
                act.goHome()
                act.navigate(
                    PracticeFragment.newInstance(
                        unlocked.id,
                        if (app.settings.mode == PlayMode.TIMED) PlayMode.TIMED.id else PlayMode.PRACTICE.id,
                        app.settings.questionCount,
                        false
                    )
                )
            }
            b.btnLater.setOnClickListener {
                b.unlockBox.visibility = android.view.View.GONE
                b.btnAgain.requestFocus()
            }
            b.btnGoNext.requestFocus()
        } else {
            b.unlockBox.visibility = android.view.View.GONE
        }

        val wrong = summary.attempts.filter { !it.firstOk }
        b.tvWrongTitle.text = if (wrong.isEmpty()) "错题回顾" else "错题回顾（${wrong.size} 道）"
        b.wrongList.removeAllViews()
        if (wrong.isEmpty()) {
            b.tvWrongTitle.setTextColor(Ui.color(requireContext(), R.color.green))
            b.tvWrongTitle.text = "这次全对，太棒了！"
            b.wrongScroll.visibility = View.GONE
        } else {
            b.tvWrongTitle.setTextColor(Ui.color(requireContext(), R.color.red))
            b.wrongScroll.visibility = View.VISIBLE
            val density = resources.displayMetrics.density
            wrong.take(6).forEachIndexed { i, a ->
                val q = a.question
                val mine = when {
                    a.userAnswer == null -> "—"
                    q.answerDen > 0 -> "${a.userAnswer}/${q.answerDen}"
                    else -> a.userAnswer.toString()
                }
                val tv = TextView(requireContext()).apply {
                    text = "${i + 1}. ${q.text} = ${q.answerText}" +
                        "    你答的是 $mine" +
                        if (a.timedOut) "（超时）" else ""
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    setTextColor(Ui.color(requireContext(), R.color.text))
                    setPadding(0, (5 * density).toInt(), 0, (5 * density).toInt())
                }
                b.wrongList.addView(tv)
            }
            if (wrong.size > 6) {
                val more = TextView(requireContext()).apply {
                    text = "…… 还有 ${wrong.size - 6} 道，已收进错题本"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(Ui.color(requireContext(), R.color.text_dim))
                }
                b.wrongList.addView(more)
            }
        }

        b.btnAgain.setOnClickListener {
            act.goHome()
            act.navigate(
                PracticeFragment.newInstance(
                    summary.stage.id,
                    summary.mode.id,
                    if (summary.mode == PlayMode.WRONG) app.settings.questionCount else summary.total,
                    summary.mode == PlayMode.WRONG
                )
            )
        }
        b.btnHome.setOnClickListener { act.goHome() }
        Ui.focusZoom(b.btnAgain, b.btnHome, b.btnGoNext, b.btnLater)
        if (unlocked == null) b.btnAgain.requestFocus()
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }
}

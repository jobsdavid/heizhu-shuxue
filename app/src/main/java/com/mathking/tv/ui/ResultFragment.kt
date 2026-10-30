package com.mathking.tv.ui

import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.mathking.tv.App
import com.mathking.tv.MainActivity
import com.mathking.tv.R
import com.mathking.tv.audio.Speech
import com.mathking.tv.data.PlayMode
import com.mathking.tv.data.StageCatalog
import com.mathking.tv.databinding.FragmentResultBinding
import com.mathking.tv.stats.Analytics
import com.mathking.tv.ui.effect.ConfettiView
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
            // 这行是「本难度」的算力（该难度累计），不是首页那个总算力，写清楚免得看混
            if (summary.power > 0) append("本难度算力 ${summary.power.toInt()}（${rank.name}）")
            when {
                unlocked != null -> append(" · 🎉 解锁「${unlocked.name}」")
                progress != null -> append(" · 解锁下一档：${progress.missingText}")
            }
        }
        b.tvLine3.setTextColor(Ui.color(requireContext(), if (unlocked != null) R.color.accent else R.color.blue))

        // 四条解锁条件逐条 ✓/✗：卡在哪一条，这一屏就能看见
        if (progress != null) {
            val items = UnlockHelp.items(
                summary.stage, progress.questions, progress.accuracy,
                progress.avgSeconds, progress.power
            )
            b.tvConditions.visibility = View.VISIBLE
            b.tvConditions.text = UnlockHelp.labeledSpan(requireContext(), items)
        } else {
            b.tvConditions.visibility = View.GONE
        }

        // 特效：满分或刚解锁 → 撒彩带；考得很差 → 加油（不给压力，但也别只留一个冷冰冰的分数）
        val perfect = summary.total >= 5 && summary.correctFirst == summary.total
        when {
            perfect || unlocked != null -> act.playEffect(ConfettiView.Mode.CELEBRATE)
            summary.accuracy < 60 -> act.playEffect(ConfettiView.Mode.CHEER)
        }

        // 解锁了就把话说明白，别只藏在第三行蓝字后面
        if (unlocked != null) {
            val idx = StageCatalog.indexOf(unlocked)
            b.unlockBox.visibility = android.view.View.VISIBLE
            b.tvUnlockName.text = unlocked.name
            b.tvUnlockTip.text = "S${idx + 1} · ${unlocked.tip}"
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

        // 一局结束时把成绩和建议念出来：孩子不一定会看这一屏的小字。
        // 同一句话也放一行小字在页面上，家长能看到娃听到了什么。
        val spoken = Speech.result(
            summary.mode, summary.total, summary.correctFirst, summary.stars, summary.accuracy
        )
        b.tvSpeakHint.text = spoken
        app.speaker.speak(spoken, 1.0f * app.settings.speechScale)
    }

    override fun onPause() {
        super.onPause()
        if (isAdded) App.of(requireContext()).speaker.stop()
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }
}

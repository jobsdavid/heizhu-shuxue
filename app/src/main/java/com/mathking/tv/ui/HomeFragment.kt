package com.mathking.tv.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.mathking.tv.App
import com.mathking.tv.MainActivity
import com.mathking.tv.R
import com.mathking.tv.data.DailyPoint
import com.mathking.tv.data.Overall
import com.mathking.tv.data.PlayMode
import com.mathking.tv.data.StageCatalog
import com.mathking.tv.data.TodaySummary
import com.mathking.tv.databinding.FragmentHomeBinding
import com.mathking.tv.stats.Analytics
import com.mathking.tv.util.Fmt
import com.mathking.tv.util.Ui

class HomeFragment : Fragment(R.layout.fragment_home) {

    private var _b: FragmentHomeBinding? = null
    private val b get() = _b!!

    private data class HomeData(
        val today: TodaySummary,
        val overall: Overall,
        val wrong: Int,
        val days: List<DailyPoint>
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentHomeBinding.bind(view)
        Ui.focusZoom(b.cardStart, b.cardWrong, b.cardStats, b.cardSettings)

        b.cardStart.setOnClickListener {
            val s = App.of(requireContext()).settings
            (activity as? MainActivity)?.navigate(
                PracticeFragment.newInstance(s.stageId, s.mode.id, s.questionCount, false)
            )
        }
        b.cardWrong.setOnClickListener {
            (activity as? MainActivity)?.navigate(WrongBookFragment())
        }
        b.cardStats.setOnClickListener {
            (activity as? MainActivity)?.navigate(StatsFragment())
        }
        b.cardSettings.setOnClickListener {
            (activity as? MainActivity)?.navigate(SettingsFragment())
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        b.cardStart.requestFocus()
    }

    private fun refresh() {
        val app = App.of(requireContext())
        val stage = StageCatalog.byId(app.settings.stageId)
        val planned = if (app.settings.mode == PlayMode.EXAM) 20 else app.settings.questionCount
        b.tvStartInfo.text = "${stage.name} · ${app.settings.mode.label} · $planned 题"
        b.tvSettingsInfo.text = "阶段与声音"

        app.repo.async({
            HomeData(app.repo.today(), app.repo.overall(), app.repo.wrongCount(), app.repo.dailyStats(30))
        }) { data ->
            if (!isAdded || _b == null) return@async
            val overall = data.overall
            val power = Analytics.power(
                overall.accuracy, overall.avgSeconds, stage.targetSeconds,
                overall.questions, stage.weight
            )
            val rank = Analytics.rank(power)

            b.tvRankBadge.text = rank.badge
            b.tvRankName.text = rank.name
            b.tvRankName.setTextColor(Ui.color(requireContext(), rank.color))
            b.tvPower.text = "算力 ${power.toInt()} / 1000"

            b.tvTodayQuestions.text = "${data.today.questions} 题"
            b.tvTodayAccuracy.text =
                if (data.today.questions == 0) "正确率 --" else "正确率 ${Fmt.pct1(data.today.accuracy)}"
            b.tvTodayTime.text =
                if (data.today.questions == 0) "用时 --" else "用时 ${Fmt.duration(data.today.msTotal)}"

            b.tvStreakDays.text = "连续 ${streakDays(data.days)} 天"
            b.tvWrongCount.text = "${data.wrong} 道待攻克"
            b.tvStatsInfo.text = "累计 ${overall.questions} 题"

            b.tvSubtitle.text = when {
                data.today.questions == 0 && overall.questions == 0 -> "今天也来练几道题吧"
                data.today.questions == 0 -> "今天还没练，来几道题热热身"
                else -> "今天已经练了 ${data.today.questions} 题，真棒"
            }
            b.tvStartHint.text = "按 OK 开始"
        }
    }

    private fun streakDays(days: List<DailyPoint>): Int {
        var streak = 0
        val reversed = days.reversed()
        for ((i, d) in reversed.withIndex()) {
            if (d.total > 0) {
                streak++
            } else if (i == 0) {
                continue  // 今天还没练，不算断
            } else {
                break
            }
        }
        return streak
    }
}

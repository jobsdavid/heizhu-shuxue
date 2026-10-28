package com.mathking.tv.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.mathking.tv.App
import com.mathking.tv.MainActivity
import com.mathking.tv.R
import com.mathking.tv.data.DailyPoint
import com.mathking.tv.data.DailyRecord
import com.mathking.tv.data.Overall
import com.mathking.tv.data.PlayMode
import com.mathking.tv.data.StageCatalog
import com.mathking.tv.data.TodaySummary
import com.mathking.tv.databinding.FragmentHomeBinding
import com.mathking.tv.stats.Analytics
import com.mathking.tv.util.Fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.mathking.tv.util.Ui

class HomeFragment : Fragment(R.layout.fragment_home) {

    private var _b: FragmentHomeBinding? = null
    private val b get() = _b!!

    /** 有达标战绩才有值：电脑每题用时（毫秒） */
    private var challengePace: Long? = null

    private var todayKey: String = ""
    private var todayDaily: DailyRecord? = null

    private data class HomeData(
        val today: TodaySummary,
        val overall: Overall,
        val wrong: Int,
        val days: List<DailyPoint>,
        val bestPaceMs: Long?,
        val daily: DailyRecord?,
        val dailyStreak: Int,
        val stageOverall: Overall
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentHomeBinding.bind(view)
        Ui.focusZoom(
            b.cardStart, b.cardDaily, b.cardChallenge,
            b.cardWrong, b.cardStats, b.cardSettings
        )

        b.cardStart.setOnClickListener {
            val s = App.of(requireContext()).settings
            (activity as? MainActivity)?.navigate(
                PracticeFragment.newInstance(s.stageId, s.mode.id, s.questionCount, false)
            )
        }
        b.cardDaily.setOnClickListener {
            val s = App.of(requireContext()).settings
            if (todayDaily?.full == true) {
                android.widget.Toast.makeText(
                    requireContext(), "今天已经通关了，明天再来", android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                (activity as? MainActivity)?.navigate(
                    PracticeFragment.newInstance(
                        s.stageId, PlayMode.PRACTICE.id, DAILY_COUNT, false,
                        seed = todayKey.hashCode().toLong(),
                        dailyDay = todayKey
                    )
                )
            }
        }
        b.cardChallenge.setOnClickListener {
            val s = App.of(requireContext()).settings
            val pace = challengePace
            if (pace == null) {
                android.widget.Toast.makeText(
                    requireContext(),
                    "先完成一轮正确率 80% 以上的练习，才能挑战电脑",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                (activity as? MainActivity)?.navigate(ChallengeFragment.newInstance(s.stageId))
            }
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
        b.tvStartInfo.text = "${stage.name} · ${app.settings.mode.label}\n$planned 题"
        b.tvSettingsInfo.text = "阶段与声音"

        todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        app.repo.async({
            HomeData(
                app.repo.today(), app.repo.overall(), app.repo.wrongCount(),
                app.repo.dailyStats(30), app.repo.bestPaceMs(stage.id),
                app.repo.dailyRecord(todayKey), app.repo.dailyStreak(),
                app.repo.overall(stage.id)
            )
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
            todayDaily = data.daily
            b.tvDailyInfo.text = when {
                data.daily == null -> "10 题全对点亮今天"
                data.daily.full -> "今天已通关 ✓ 连续打卡 ${data.dailyStreak} 天"
                else -> "今天最好 ${data.daily.correct}/${data.daily.total}，全对才算通关"
            }
            b.tvDailyAction.text = if (data.daily?.full == true) "明天再来" else "去挑战 ›"

            challengePace = data.bestPaceMs?.let { (it * 0.8).toLong().coerceIn(1200L, 20000L) }
            b.tvChallengeInfo.text = challengePace?.let {
                "对手 ${String.format("%.1f", it / 1000.0)} 秒/题"
            } ?: "先练一轮 80 分"
            b.tvChallengeInfo.setTextColor(
                Ui.color(requireContext(), if (challengePace == null) R.color.text_dim else R.color.accent)
            )

            b.tvWrongCount.text = "${data.wrong} 道待攻克"
            b.tvStatsInfo.text = "累计 ${overall.questions} 题"

            b.tvSubtitle.text = when {
                data.today.questions == 0 && overall.questions == 0 -> "今天也来练几道题吧"
                data.today.questions == 0 -> "今天还没练，来几道题热热身"
                else -> "今天已经练了 ${data.today.questions} 题，真棒"
            }
            val progress = app.settings.unlockProgress(
                stage, data.stageOverall.questions, data.stageOverall.accuracy
            )
            b.tvStartHint.text = when {
                progress == null -> "按 OK 开始"
                progress.needMoreQuestions > 0 && progress.needMoreAccuracy > 0 ->
                    "还差 ${progress.needMoreQuestions} 题 · 正确率 ${Fmt.pct(data.stageOverall.accuracy)}"
                progress.needMoreQuestions > 0 -> "还差 ${progress.needMoreQuestions} 题解锁下一阶段"
                progress.needMoreAccuracy > 0 -> "正确率到 85% 解锁下一阶段"
                else -> "按 OK 开始"
            }
        }
    }

    companion object {
        private const val DAILY_COUNT = 10
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

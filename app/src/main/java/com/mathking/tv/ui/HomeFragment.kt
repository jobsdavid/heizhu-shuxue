package com.mathking.tv.ui

import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.graphics.Outline
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.mathking.tv.App
import com.mathking.tv.MainActivity
import com.mathking.tv.audio.Sfx
import com.mathking.tv.R
import com.mathking.tv.data.DailyPoint
import com.mathking.tv.data.DailyRecord
import com.mathking.tv.data.Overall
import com.mathking.tv.data.ChallengeRules
import com.mathking.tv.data.ChallengeStatus
import com.mathking.tv.data.Repo
import com.mathking.tv.data.Settings
import com.mathking.tv.data.PlayMode
import com.mathking.tv.data.StageCatalog
import com.mathking.tv.data.TodaySummary
import com.mathking.tv.databinding.FragmentHomeBinding
import com.mathking.tv.stats.Analytics
import com.mathking.tv.util.Fmt
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.mathking.tv.util.Ui

class HomeFragment : Fragment(R.layout.fragment_home) {

    private var _b: FragmentHomeBinding? = null
    private val b get() = _b!!

    /** 有达标战绩才有值：电脑每题用时（毫秒） */
    private var challengeStatus: ChallengeStatus? = null

    private var todayKey: String = ""
    private var todayDaily: DailyRecord? = null

    private data class HomeData(
        val today: TodaySummary,
        val overall: Overall,
        val wrong: Int,
        val days: List<DailyPoint>,
        val challenge: ChallengeStatus,
        val daily: DailyRecord?,
        val dailyStreak: Int,
        val stageOverall: Overall
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentHomeBinding.bind(view)
        Ui.focusZoom(
            b.btnStage, b.cardPractice, b.cardExam, b.cardChallenge, b.cardDaily,
            b.btnWrong, b.btnStats, b.btnSettings, b.rankBox
        )

        // 换难度走独立入口：打开难度地图，点某一阶看详情/切过去
        b.btnStage.setOnClickListener {
            (activity as? MainActivity)?.navigate(StageMapFragment())
        }

        b.rankBox.setOnClickListener {
            ProfileDialog(requireActivity(), onChanged = { refresh() }).show()
        }

        b.cardPractice.setOnClickListener {
            val s = App.of(requireContext()).settings
            val mode = if (s.mode == PlayMode.TIMED) PlayMode.TIMED else PlayMode.PRACTICE
            (activity as? MainActivity)?.navigate(
                PracticeFragment.newInstance(s.stageId, mode.id, s.questionCount, false)
            )
        }

        b.cardExam.setOnClickListener {
            val s = App.of(requireContext()).settings
            (activity as? MainActivity)?.navigate(
                PracticeFragment.newInstance(s.stageId, PlayMode.EXAM.id, 20, false)
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
            val st = challengeStatus
            val msg = when {
                st == null -> "正在读取挑战状态…"
                st.perfectRounds < Repo.CHALLENGE_FULL_ROUNDS ->
                    "还要 ${Repo.CHALLENGE_FULL_ROUNDS - st.perfectRounds} 场满分考试（每场至少 5 题全对）才能解锁挑战"
                st.credits <= 0 ->
                    "挑战次数用完了，再考 ${ChallengeRules.perfectsToNextCredit(st.perfectRounds)} 场满分就能再挑战一次"
                else -> null
            }
            if (msg != null) {
                android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_LONG).show()
            } else {
                (activity as? MainActivity)?.navigate(ChallengeFragment.newInstance(s.stageId))
            }
        }
        b.btnWrong.setOnClickListener {
            (activity as? MainActivity)?.navigate(WrongBookFragment())
        }
        b.btnStats.setOnClickListener {
            (activity as? MainActivity)?.navigate(StatsFragment())
        }
        b.btnSettings.setOnClickListener {
            (activity as? MainActivity)?.navigate(SettingsFragment())
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        b.cardPractice.requestFocus()
    }

    /** 自定义头像优先，没有就用 emoji */
    private fun applyAvatar(settings: com.mathking.tv.data.Settings) {
        val file = if (settings.avatarImage.isNotBlank())
            File(requireContext().filesDir, settings.avatarImage) else null
        val bmp = file?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
        if (bmp != null) {
            b.imgAvatar.setImageBitmap(bmp)
            b.imgAvatar.visibility = View.VISIBLE
            b.imgAvatar.clipToOutline = true
            b.imgAvatar.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            b.tvRankBadge.visibility = View.GONE
        } else {
            b.imgAvatar.visibility = View.GONE
            b.tvRankBadge.visibility = View.VISIBLE
            b.tvRankBadge.text = settings.avatar
        }
    }

    private fun refresh() {
        val app = App.of(requireContext())
        val stage = StageCatalog.byId(app.settings.stageId)
        val stageNo = StageCatalog.indexOf(stage) + 1
        b.btnStage.text = "难度   S$stageNo · ${stage.name}   ›"
        b.tvPracticeInfo.text = "S$stageNo · ${stage.name}"
        b.tvExamInfo.text = "S$stageNo · 20 题"
        b.tvSettingsInfo.text = ""

        todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        app.repo.async({
            val stageOverall = app.repo.overall(stage.id)
            val base = app.repo.challengeStatus(stage)
            HomeData(
                app.repo.today(), app.repo.overall(), app.repo.wrongCount(),
                app.repo.dailyStats(30),
                base,
                app.repo.dailyRecord(todayKey), app.repo.dailyStreak(),
                stageOverall
            )
        }) { data ->
            if (!isAdded || _b == null) return@async
            val overall = data.overall
            val power = Analytics.power(
                overall.accuracy, overall.avgSeconds, stage.targetSeconds,
                overall.questions, stage.weight
            )
            val rank = Analytics.rank(power)

            applyAvatar(app.settings)
            b.tvRankName.text = app.settings.userName.ifBlank { "小朋友" }
            b.tvRankName.setTextColor(Ui.color(requireContext(), rank.color))
            // 不用猪头徽章了（丑）：改成给名字区描边，等级越高边框颜色不同
            b.tvRankBadge.visibility = View.GONE
            val rankColor = Ui.color(requireContext(), rank.color)
            b.rankBox.background = GradientDrawable().apply {
                cornerRadius = Ui.dp(requireContext(), 18f).toFloat()
                setColor(Ui.color(requireContext(), R.color.card))
                setStroke(Ui.dp(requireContext(), 2.5f), rankColor)
            }
            b.tvPower.text = "Lv.${rank.level} ${rank.name} · 算力 ${power.toInt()}"
            b.tvPower.setTextColor(rankColor)

            b.tvTodayQuestions.text = "${data.today.questions} 题"
            b.tvTodayAccuracy.text =
                if (data.today.questions == 0) "正确率 --" else "正确率 ${Fmt.pct1(data.today.accuracy)}"
            b.tvTodayTime.text =
                if (data.today.questions == 0) "用时 --" else "用时 ${Fmt.duration(data.today.msTotal)}"

            b.tvStreakDays.text = "连续 ${streakDays(data.days)} 天"
            todayDaily = data.daily
            b.tvDailyInfo.text = when {
                data.daily == null -> "10 题全对点亮今天"
                data.daily.full ->
                    "${StageCatalog.byId(data.daily.stageId).name} 全对通关 ✓ 连续打卡 ${data.dailyStreak} 天"
                else -> "今天最好 ${data.daily.correct}/${data.daily.total}，全对才算通关"
            }
            b.tvDailyAction.text = if (data.daily?.full == true) "明天再来" else "去挑战 ›"

            challengeStatus = data.challenge
            val pace = data.challenge.computerMs
            b.tvChallengeInfo.text = when {
                data.challenge.perfectRounds < Repo.CHALLENGE_FULL_ROUNDS ->
                    "还差 ${Repo.CHALLENGE_FULL_ROUNDS - data.challenge.perfectRounds} 场满分"
                data.challenge.credits <= 0 ->
                    "再考 ${ChallengeRules.perfectsToNextCredit(data.challenge.perfectRounds)} 场满分换 1 次挑战"
                pace == null -> "可挑战 ${data.challenge.credits} 次"
                else -> "可挑战 ${data.challenge.credits} 次 · 电脑 ${String.format("%.1f", pace / 1000.0)} 秒/题"
            }
            b.tvChallengeInfo.setTextColor(
                Ui.color(
                    requireContext(),
                    if (data.challenge.credits > 0 && pace != null) R.color.accent else R.color.text_dim
                )
            )

            b.tvWrongCount.text = "${data.wrong} 道"
            b.tvStatsInfo.text = "${overall.questions} 题"

            val who = app.settings.userName.ifBlank { "小朋友" }
            b.tvSubtitle.text = when {
                data.today.questions == 0 && overall.questions == 0 -> "$who，今天来练几道题吧"
                data.today.questions == 0 -> "$who，今天还没练，来热热身"
                else -> "$who，今天已经练了 ${data.today.questions} 题，真棒"
            }
            val stagePower = Analytics.power(
                data.stageOverall.accuracy, data.stageOverall.avgSeconds, stage.targetSeconds,
                data.stageOverall.questions, stage.weight
            )
            val progress = app.settings.unlockProgress(
                stage, data.stageOverall.questions, data.stageOverall.accuracy,
                data.stageOverall.avgSeconds, stagePower
            )
            b.tvPracticeMode.text = when {
                progress != null -> "解锁下一难度：${progress.missingText}"
                else -> "${app.settings.questionCount} 题 · " +
                    if (app.settings.mode == PlayMode.TIMED) "限时" else "不限时"
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

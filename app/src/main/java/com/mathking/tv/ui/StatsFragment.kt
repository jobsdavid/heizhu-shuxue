package com.mathking.tv.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.mathking.tv.App
import com.mathking.tv.R
import com.mathking.tv.data.DailyPoint
import com.mathking.tv.data.Overall
import com.mathking.tv.data.StageCatalog
import com.mathking.tv.data.TagStat
import com.mathking.tv.databinding.FragmentStatsBinding
import com.mathking.tv.stats.Analytics
import com.mathking.tv.util.Fmt
import com.mathking.tv.util.Ui
import java.util.Calendar
import kotlin.math.max

class StatsFragment : Fragment(R.layout.fragment_stats) {

    private var _b: FragmentStatsBinding? = null
    private val b get() = _b!!

    private data class StatsData(
        val overall: Overall,
        val daily30: List<DailyPoint>,
        val daily56: List<DailyPoint>,
        val tags: List<TagStat>
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentStatsBinding.bind(view)
        b.btnAchievements.setOnClickListener {
            (activity as? com.mathking.tv.MainActivity)?.navigate(AchievementsFragment())
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val app = App.of(requireContext())
        app.repo.async({
            StatsData(
                overall = app.repo.overall(),
                daily30 = app.repo.dailyStats(30),
                daily56 = app.repo.dailyStats(56),
                tags = app.repo.tagStats()
            )
        }) { data ->
            if (!isAdded || _b == null) return@async
            render(data)
        }
    }

    private fun render(data: StatsData) {
        val ctx = requireContext()
        val stage = StageCatalog.byId(App.of(ctx).settings.stageId)
        val o = data.overall
        val power = Analytics.power(o.accuracy, o.avgSeconds, stage.targetSeconds, o.questions, stage.weight)
        val rank = Analytics.rank(power)

        b.tvPower.text = "算力 ${power.toInt()}"
        b.tvRank.text = "${rank.badge} ${rank.name}"
        b.tvRank.setTextColor(Ui.color(ctx, rank.color))
        b.tvSummary.text = "累计 ${o.questions} 题 · 正确率 ${Fmt.pct(o.accuracy)} · 平均 ${Fmt.sec(o.avgSeconds)} 秒/题"

        // 正确率曲线（没有练习的天断点）
        val labels = data.daily30.map { it.day.substring(5).replace("-", "/") }
        val accuracyValues: List<Float?> = data.daily30.map {
            if (it.total > 0) it.accuracy.toFloat() else null
        }
        b.chartAccuracy.setData(
            "正确率 · 近 30 天",
            accuracyValues,
            labels,
            0f,
            100f,
            "%",
            R.color.green
        )

        // 速度曲线
        val speeds = data.daily30.filter { it.total > 0 }.map { it.avgSeconds.toFloat() }
        val maxSpeed = max(10f, (speeds.maxOrNull() ?: 10f) * 1.3f)
        val speedValues: List<Float?> = data.daily30.map {
            if (it.total > 0) it.avgSeconds.toFloat() else null
        }
        b.chartSpeed.setData(
            "平均每题用时（越短越熟）",
            speedValues,
            labels,
            0f,
            maxSpeed,
            "秒",
            R.color.blue
        )

        // 知识点
        b.chartTags.setData(
            "知识点正确率",
            data.tags.take(5).map { it.tag to it.accuracy.toFloat() }
        )

        // 日历热力图
        val counts = data.daily56.associate { it.day to it.total }
        b.heatmap.setData(counts, Calendar.getInstance())
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }
}

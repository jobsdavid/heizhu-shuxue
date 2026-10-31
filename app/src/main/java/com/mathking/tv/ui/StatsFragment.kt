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
        val tags: List<TagStat>,
        val perStage: Map<String, Overall>
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentStatsBinding.bind(view)
        // 记录入口：家长最想看的「每档练了几次、最好/最差、每题多快」都在这里
        b.btnRecords.setOnClickListener {
            (activity as? com.mathking.tv.MainActivity)?.navigate(StageMapFragment())
        }
        b.btnAchievements.setOnClickListener {
            (activity as? com.mathking.tv.MainActivity)?.navigate(AchievementsFragment())
        }
        b.btnRecords.requestFocus()
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
                // 累计题量/正确率/用时反映「实际做了多少」，半场退出但答过的题也算；
                // 算力/等级/解锁门槛仍只认完整场次（见 Repo.overall 的 includePartial）
                overall = app.repo.overall(includePartial = true),
                daily30 = app.repo.dailyStats(30),
                daily56 = app.repo.dailyStats(56),
                tags = app.repo.tagStats(),
                perStage = app.repo.overallByStage()
            )
        }) { data ->
            if (!isAdded || _b == null) return@async
            render(data)
        }
    }

    private fun render(data: StatsData) {
        val ctx = requireContext()
        val o = data.overall
        // 和首页同一口径：总算力 = 各难度分别算完取最强的一档
        val global = Analytics.overallPower(data.perStage)
        val rank = Analytics.rank(global.power)

        b.tvPower.text = "总算力 ${global.power.toInt()}"
        b.tvRank.text = rank.name
        b.tvRank.setTextColor(Ui.color(ctx, rank.color))
        // 顶栏字号紧，只留「最强 S4」；写全「S4 这一档最强」会折成两行把右端挤爆
        val best = global.stage?.let { " · 最强 S${StageCatalog.indexOf(it) + 1}" } ?: ""
        b.tvSummary.text = "累计 ${o.questions} 题 · ${Fmt.sec(o.avgSeconds)} 秒/题$best"

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

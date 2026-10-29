package com.mathking.tv.ui

import android.app.Dialog
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.mathking.tv.App
import com.mathking.tv.R
import com.mathking.tv.data.Settings
import com.mathking.tv.data.StageCatalog
import com.mathking.tv.stats.Analytics
import com.mathking.tv.data.StageConfig
import com.mathking.tv.data.StageKind
import com.mathking.tv.databinding.FragmentStagesBinding
import com.mathking.tv.util.Fmt
import com.mathking.tv.util.Ui

/**
 * 阶段地图：把 s1~s12 一次全列出来。
 *
 * 之前只能在设置页左右翻「已解锁」的阶段，家长看不到还有哪些阶段、也看不到解锁条件，
 * 这一页补的就是这个缺口：每阶练什么、练到哪了、下一阶还差多少，一屏看完。
 */
class StageMapFragment : Fragment(R.layout.fragment_stages) {

    private var _b: FragmentStagesBinding? = null
    private val b get() = _b!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentStagesBinding.bind(view)
        b.tvStageHint.text =
            "在任一难度练满 ${Settings.UNLOCK_MIN_QUESTIONS} 题、正确率 " +
                "${Settings.UNLOCK_MIN_ACCURACY.toInt()}%、平均每题达到目标速度、算力达标，" +
                "才会解锁下一个难度；家长也可以在设置里开「解锁全部难度」"
        render()
    }

    private fun render() {
        val app = App.of(requireContext())
        b.colLeft.removeAllViews()
        b.colRight.removeAllViews()

        val all = StageCatalog.available
        val half = (all.size + 1) / 2
        var focusRow: View? = null
        all.forEachIndexed { i, stage ->
            val row = buildRow(app, stage, i)
            val col = if (i < half) b.colLeft else b.colRight
            col.addView(row)
            // 进来就把焦点放在「正在练」的那一档：遥控器直接按 OK 就能看详情，
            // 不然孩子按 OK 没反应，以为坏了
            if (stage.id == app.settings.stageId) focusRow = row
        }
        val target = focusRow ?: b.colLeft.getChildAt(0)
        target?.post { target.requestFocus() }
    }

    private fun buildRow(app: App, stage: StageConfig, index: Int): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val settings = app.settings
        val unlocked = settings.isUnlocked(stage.id)
        val current = settings.stageId == stage.id

        val stats = app.repo.overall(stage.id)

        // 解锁条件看的是「上一个阶段」的累计成绩，不是本阶段的
        val prev = StageCatalog.available.getOrNull(index - 1)
        val prevStats = if (prev != null) app.repo.overall(prev.id) else null

        val status: String
        val statusColor: Int
        when {
            current -> {
                status = "✅ 正在练"
                statusColor = R.color.green
            }
            unlocked -> {
                status = "✓ ${stats.questions} 题"
                statusColor = R.color.text_dim
            }
            else -> {
                val need = if (prevStats == null) 0
                else (Settings.UNLOCK_MIN_QUESTIONS - prevStats.questions).coerceAtLeast(0)
                val acc = prevStats?.accuracy ?: 0.0
                val avg = prevStats?.avgSeconds ?: 0.0
                val speedCap = if (prev == null) 0.0 else Settings.speedTargetSeconds(prev)
                status = when {
                    need > 0 -> "🔒 还差 $need 题"
                    acc < Settings.UNLOCK_MIN_ACCURACY -> "🔒 正确率 ${Fmt.pct(acc)}"
                    avg <= 0 || (speedCap > 0 && avg > speedCap) ->
                        "🔒 再快 ${String.format("%.1f", (avg - speedCap).coerceAtLeast(0.0))} 秒/题"
                    else -> "🔒 未解锁"
                }
                statusColor = R.color.text_dim
            }
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.selector_card)
            isFocusable = true
            isClickable = true
            isSoundEffectsEnabled = false
            setPadding(
                (12 * density).toInt(), (5 * density).toInt(),
                (12 * density).toInt(), (5 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { bottomMargin = (5 * density).toInt() }
            setOnClickListener { showDetail(app, stage, index, unlocked, current) }
        }

        row.addView(TextView(ctx).apply {
            text = "S${index + 1}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Ui.color(ctx, R.color.accent))
            gravity = Gravity.CENTER
            width = (30 * density).toInt()
        })

        val mid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        mid.addView(TextView(ctx).apply {
            text = stage.name
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Ui.color(ctx, if (current) R.color.accent else R.color.text))
            typeface = Typeface.DEFAULT_BOLD
        })
        mid.addView(TextView(ctx).apply {
            text = subtip(stage)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Ui.color(ctx, R.color.text_dim))
            maxLines = 1
        })
        row.addView(mid)

        row.addView(TextView(ctx).apply {
            text = status
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Ui.color(ctx, statusColor))
        })

        Ui.focusZoom(row)
        return row
    }

    /** 一行小字：这个阶段到底练什么 */
    private fun subtip(stage: StageConfig): String = when (stage.kind) {
        StageKind.ADD_SUB -> "加减 · 0~${stage.maxValue}"
        StageKind.MUL -> if (stage.maxValue <= 9) "乘法 · 九九表" else "乘法 · 两位数×一位数"
        StageKind.DIV -> if (stage.maxValue <= 9) "除法 · 表内整除" else "除法 · 三位数÷一位数"
        StageKind.MUL_DIV -> "乘除混合 · 2~9"
        StageKind.FOUR_OPS -> "加减乘除全上"
        StageKind.FRACTION_SAME -> "分数 · 同分母加减"
        StageKind.FRACTION_DIFF -> "分数 · 通分后加减"
    }

    /** 阶段详情：练什么、自己的成绩、解锁条件，一屏讲清 */
    private fun showDetail(app: App, stage: StageConfig, index: Int, unlocked: Boolean, current: Boolean) {
        val dialog = Dialog(requireContext(), R.style.Theme_MathKing_Dialog)
        dialog.setContentView(R.layout.dialog_stage)

        val stats = app.repo.overall(stage.id)
        val prev = StageCatalog.available.getOrNull(index - 1)
        val prevStats = if (prev != null) app.repo.overall(prev.id) else null

        dialog.findViewById<TextView>(R.id.tvStageTitle).text = "S${index + 1} · ${stage.name}"
        dialog.findViewById<TextView>(R.id.tvStageSub).text = subtip(stage)
        dialog.findViewById<TextView>(R.id.tvStageDetail).text =
            stage.detail.ifBlank { stage.tip }

        val sb = StringBuilder()
        sb.append(
            if (stats.questions == 0) "这个阶段还没练过"
            else "练了 ${stats.questions} 题 · 正确率 ${Fmt.pct(stats.accuracy)} · " +
                "平均 ${"%.1f".format(stats.avgSeconds)} 秒/题"
        )

        // 家长要看细账：练了几次、考了几次、最好最差、单题最快最慢
        val rep = app.repo.stageReport(stage.id)
        if (rep.attempts > 0) {
            sb.append("\n\n练习 ${rep.practiceRounds} 次 · 考试 ${rep.examRounds} 次")
            if (rep.challengeRounds > 0) sb.append(" · 挑战 ${rep.challengeRounds} 次")
            if (rep.examRounds + rep.practiceRounds > 0) {
                sb.append("\n单场得分：最好 ${rep.bestScore.toInt()} · 最差 ${rep.worstScore.toInt()}")
                sb.append(" · 平均 ${rep.avgScore.toInt()}")
            }
            sb.append("\n每题用时：最快 ${rep.fastestMs}ms · 最慢 ${rep.slowestMs}ms")
            sb.append(" · 平均 ${rep.avgMs}ms（共 ${rep.attempts} 题）")
        }

        if (!unlocked && prev != null && prevStats != null) {
            val prevPower = Analytics.power(
                prevStats.accuracy, prevStats.avgSeconds, prev.targetSeconds,
                prevStats.questions, prev.weight
            )
            sb.append("\n\n解锁条件（在「${prev.name}」）：")
            sb.append("\n① 练满 ${Settings.UNLOCK_MIN_QUESTIONS} 题（现在 ${prevStats.questions} 题）")
            sb.append("\n② 正确率 ≥ ${Settings.UNLOCK_MIN_ACCURACY.toInt()}%（现在 ${Fmt.pct(prevStats.accuracy)}）")
            sb.append("\n③ 平均每题 ≤ ${"%.0f".format(Settings.speedTargetSeconds(prev))} 秒" +
                "（现在 ${"%.1f".format(prevStats.avgSeconds)} 秒）")
            sb.append("\n④ 算力 ≥ ${Settings.powerTarget(prev)}（现在 ${prevPower.toInt()}）")
        }
        dialog.findViewById<TextView>(R.id.tvStageStats).text = sb.toString()

        val start = dialog.findViewById<TextView>(R.id.btnStageStart)
        val close = dialog.findViewById<TextView>(R.id.btnStageClose)
        when {
            current -> {
                start.text = "正在练这个难度"
                start.isEnabled = false
                start.alpha = 0.45f
            }
            unlocked -> start.setOnClickListener {
                app.settings.stageId = stage.id
                dialog.dismiss()
                render()
                b.tvStageHint.text = "已切换到「${stage.name}」：${stage.tip}"
            }
            else -> {
                start.visibility = View.GONE
            }
        }
        close.setOnClickListener { dialog.dismiss() }
        close.requestFocus()

        dialog.setOnShowListener {
            dialog.window?.setLayout((660 * resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}

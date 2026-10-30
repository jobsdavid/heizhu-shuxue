package com.mathking.tv.ui

import android.app.Dialog
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.mathking.tv.App
import com.mathking.tv.R
import com.mathking.tv.data.Overall
import com.mathking.tv.data.Settings
import com.mathking.tv.data.StageCatalog
import com.mathking.tv.data.StageConfig
import com.mathking.tv.data.StageKind
import com.mathking.tv.databinding.FragmentStagesBinding
import com.mathking.tv.stats.Analytics
import com.mathking.tv.util.Fmt
import com.mathking.tv.util.Ui

/**
 * 阶段地图：把 s1~s12 一次全列出来。
 *
 * 之前只能在设置页左右翻「已解锁」的阶段，家长看不到还有哪些阶段、也看不到解锁条件，
 * 这一页补的就是这个缺口：每阶练什么、练到哪了、下一阶还差多少，一屏看完。
 *
 * 2026-10 加了一行「①②③④ ✓/✗」：四条解锁条件逐条对账，达标打勾、没达标打叉，
 * 不用点进详情也能看出卡在哪一条。
 */
class StageMapFragment : Fragment(R.layout.fragment_stages) {

    private var _b: FragmentStagesBinding? = null
    private val b get() = _b!!

    /** 一次查全：key = stage_id。以前是每行一次 overall()，12 趟 SQL 全在 UI 线程上。 */
    private var perStage: Map<String, Overall> = emptyMap()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentStagesBinding.bind(view)
        b.tvStageHint.text =
            "在任一难度练满 ${Settings.UNLOCK_MIN_QUESTIONS} 题、正确率 " +
                "${Settings.UNLOCK_MIN_ACCURACY.toInt()}%、平均每题达到目标速度、算力达标，" +
                "才会解锁下一个难度；①题量 ②正确率 ③速度 ④算力，✓ 达标 ✗ 没达标，" +
                "家长也可以在设置里开「解锁全部难度」"
        render()
    }

    private fun render() {
        val app = App.of(requireContext())
        perStage = app.repo.overallByStage()
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

        val prev = StageCatalog.available.getOrNull(index - 1)
        val prevStats = prev?.let { perStage[it.id] }
        val ownStats = perStage[stage.id] ?: Overall()
        val next = StageCatalog.next(stage)

        // 解锁条件看的是「上一个阶段」的累计成绩，不是本阶段的；
        // 已解锁的档则显示它自己距离「解锁下一档」还缺什么（逐条 ✓/✗）
        val gateStage = if (unlocked || prev == null) stage else prev
        val gateStats = if (unlocked || prev == null) ownStats else (prevStats ?: Overall())
        val gateItems = UnlockHelp.items(gateStage, gateStats, UnlockHelp.powerOf(gateStage, gateStats))

        // 「下一档也已经解锁」时不必再列条件：一支已解锁的档挂着红叉，家长会以为出问题了
        val showMarks = !unlocked || (next != null && !settings.isUnlocked(next.id))

        val status: String
        val statusColor: Int
        when {
            current -> {
                status = "✅ 正在练"
                statusColor = R.color.green
            }
            unlocked -> {
                status = "✓ 已解锁 · ${ownStats.questions} 题"
                statusColor = R.color.text_dim
            }
            else -> {
                val missing = gateItems.filter { !it.ok }
                status = if (missing.isEmpty()) "🔓 条件已达标"
                else "🔒 还差 ${missing.size} 条"
                statusColor = if (missing.isEmpty()) R.color.accent else R.color.text_dim
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
                (12 * density).toInt(), (4 * density).toInt(),
                (12 * density).toInt(), (4 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { bottomMargin = (5 * density).toInt() }
            setOnClickListener {
                showDetail(
                    app, stage, index, unlocked, current,
                    gateStage, gateStats, gateItems, showMarks
                )
            }
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

        // 右侧两行：状态 + 四条条件的 ✓/✗（颜色跟着达标情况走）
        val right = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        right.addView(TextView(ctx).apply {
            text = status
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Ui.color(ctx, statusColor))
        })
        right.addView(TextView(ctx).apply {
            if (showMarks) {
                text = UnlockHelp.marksSpan(ctx, gateItems)
                setTextColor(Ui.color(ctx, R.color.text_dim))
            } else {
                text = if (next == null) "最后一档" else "下一档已解锁 ✓"
                setTextColor(Ui.color(ctx, if (next == null) R.color.text_dim else R.color.green))
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            letterSpacing = 0.02f
        })
        row.addView(right)

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

    /** 阶段详情：练什么、自己的成绩、解锁条件（逐条 ✓/✗），一屏讲清 */
    private fun showDetail(
        app: App,
        stage: StageConfig,
        index: Int,
        unlocked: Boolean,
        current: Boolean,
        gateStage: StageConfig,
        gateStats: Overall,
        gateItems: List<UnlockHelp.Item>,
        showMarks: Boolean
    ) {
        val dialog = Dialog(requireContext(), R.style.Theme_MathKing_Dialog)
        dialog.setContentView(R.layout.dialog_stage)

        val stats = perStage[stage.id] ?: Overall()

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
            // 毫秒对家长没概念，统一换成秒（一位小数）
            fun sec(ms: Long) = String.format("%.1f", ms / 1000.0)
            sb.append("\n每题用时：最快 ${sec(rep.fastestMs)} 秒 · 最慢 ${sec(rep.slowestMs)} 秒")
            sb.append(" · 平均 ${sec(rep.avgMs)} 秒（共 ${rep.attempts} 题）")
        }

        val gateTitle = when {
            !unlocked && gateStage.id != stage.id -> "\n\n解锁条件（在「${gateStage.name}」）："
            showMarks -> "\n\n解锁下一难度的条件（本阶段累计）："
            else -> "\n\n"
        }
        sb.append(gateTitle)

        val tvStats = dialog.findViewById<TextView>(R.id.tvStageStats)
        tvStats.text = android.text.SpannableStringBuilder(sb.toString()).apply {
            when {
                showMarks -> append(UnlockHelp.detailSpan(requireContext(), gateItems))
                StageCatalog.next(stage) == null -> append("已经是最后一档，练到满算力就是天花板")
                else -> append("下一难度已经解锁了 —— 想再往上走，就去练更高的一档")
            }
        }

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
            dialog.window?.setLayout(
                (660 * resources.displayMetrics.density).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}

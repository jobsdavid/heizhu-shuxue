package com.mathking.tv.ui

import android.content.Context
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import com.mathking.tv.R
import com.mathking.tv.data.Overall
import com.mathking.tv.data.Settings
import com.mathking.tv.data.StageConfig
import com.mathking.tv.stats.Analytics
import com.mathking.tv.util.Fmt
import com.mathking.tv.util.Ui

/**
 * 解锁条件的「逐条对账」。
 *
 * 以前只写一句「还差 12 题」，孩子/家长看不出另外三条达标没有；现在四条条件各带 ✓ / ✗，
 * 列表行里给紧凑版（①②③④），详情弹窗里给完整版（条件 + 现在多少）。
 *
 * 口径：条件看的是**某一档难度自己的累计成绩**（[Settings.checkUnlock] 就是这么判的），
 * 所以「锁着的 S5」那一行的 ✓/✗ 实际是 S4 的成绩——调用方负责传对 stage。
 */
object UnlockHelp {

    data class Item(
        val no: Int,
        val ok: Boolean,
        /** 要求，如「练满 20 题」 */
        val need: String,
        /** 现状，如「现在 12 题」 */
        val now: String,
        /** 两个字的名字：题量 / 正确率 / 速度 / 算力 */
        val short: String
    )

    private val CIRCLED = listOf("①", "②", "③", "④", "⑤")

    fun items(stage: StageConfig, stats: Overall, power: Double): List<Item> =
        items(stage, stats.questions, stats.accuracy, stats.avgSeconds, power)

    fun items(
        stage: StageConfig,
        questions: Int,
        accuracyPct: Double,
        avgSeconds: Double,
        power: Double
    ): List<Item> {
        val needQ = Settings.UNLOCK_MIN_QUESTIONS
        val needAcc = Settings.UNLOCK_MIN_ACCURACY
        val speedMax = Settings.speedTargetSeconds(stage)
        val powerNeed = Settings.powerTarget(stage)
        return listOf(
            Item(
                1, questions >= needQ, "练满 $needQ 题", "现在 $questions 题", "题量"
            ),
            Item(
                2, questions > 0 && accuracyPct >= needAcc,
                "正确率 ≥ ${Fmt.pct(needAcc)}", "现在 ${Fmt.pct(accuracyPct)}", "正确率"
            ),
            Item(
                3, avgSeconds > 0 && avgSeconds <= speedMax,
                "平均 ≤ ${"%.0f".format(speedMax)} 秒/题",
                if (avgSeconds <= 0) "现在还没测到" else "现在 ${"%.1f".format(avgSeconds)} 秒",
                "速度"
            ),
            Item(
                4, power >= powerNeed, "算力 ≥ $powerNeed", "现在 ${power.toInt()}", "算力"
            )
        )
    }

    fun powerOf(stage: StageConfig, stats: Overall): Double =
        Analytics.power(stats.accuracy, stats.avgSeconds, stage.targetSeconds, stats.questions, stage.weight)

    /** 一行四个小方块用的符号：①✓ ②✓ ③✗ ④✗ */
    fun marks(items: List<Item>): String =
        items.joinToString(" ") { "${CIRCLED[it.no - 1]}${if (it.ok) "✓" else "✗"}" }

    /** 彩色版本：✓ 绿、✗ 红，给电视上一眼扫 */
    fun marksSpan(ctx: Context, items: List<Item>): CharSequence {
        val okColor = Ui.color(ctx, R.color.green)
        val badColor = Ui.color(ctx, R.color.red)
        val sep = "  "
        val sb = SpannableString(
            items.joinToString(sep) { "${CIRCLED[it.no - 1]} ${if (it.ok) "✓" else "✗"}" }
        )
        // 逐块上色：每块是「① ✓」三个字符，符号在第 2 位
        var pos = 0
        items.forEach { item ->
            val start = pos + 2
            sb.setSpan(
                ForegroundColorSpan(if (item.ok) okColor else badColor),
                start, start + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            pos += 3 + sep.length
        }
        return sb
    }

    /** 带名字的紧凑版：①题量 ✓  ②正确率 ✓  ③速度 ✗  ④算力 ✓（结果页用） */
    fun labeledSpan(ctx: Context, items: List<Item>): CharSequence {
        val okColor = Ui.color(ctx, R.color.green)
        val badColor = Ui.color(ctx, R.color.red)
        val sep = "  "
        val sb = SpannableString(
            items.joinToString(sep) {
                "${CIRCLED[it.no - 1]}${it.short} ${if (it.ok) "✓" else "✗"}"
            }
        )
        var pos = 0
        items.forEach { item ->
            val start = pos + 1 + item.short.length + 1
            sb.setSpan(
                ForegroundColorSpan(if (item.ok) okColor else badColor),
                start, start + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            pos += 1 + item.short.length + 1 + 1 + sep.length
        }
        return sb
    }

    /** 完整版：每行「① 练满 20 题 ✓ 现在 12 题」，颜色同上 */
    fun detailSpan(ctx: Context, items: List<Item>): CharSequence {
        val okColor = Ui.color(ctx, R.color.green)
        val badColor = Ui.color(ctx, R.color.red)
        val plain = items.joinToString("\n") {
            "${CIRCLED[it.no - 1]} ${it.need} ${if (it.ok) "✓" else "✗"} ${it.now}"
        }
        val sb = SpannableString(plain)
        var lineStart = 0
        items.forEach { item ->
            val markIdx = lineStart + CIRCLED[item.no - 1].length + 1 + item.need.length + 1
            if (markIdx < plain.length) {
                sb.setSpan(
                    ForegroundColorSpan(if (item.ok) okColor else badColor),
                    markIdx, markIdx + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            lineStart = plain.indexOf('\n', lineStart).let { if (it < 0) plain.length else it + 1 }
        }
        return sb
    }

    /** 还没达标的写成人话，例如「还差 12 题、正确率差 3.2%」 */
    fun missingText(items: List<Item>): String =
        items.filter { !it.ok }.joinToString("、") { it.need }.ifBlank { "全部达标" }
}

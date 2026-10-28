package com.mathking.tv.ui.text

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.style.ReplacementSpan
import android.widget.TextView

/**
 * 把 "3/4" 这种斜杠写法画成上下结构的真分数：
 *
 *      3
 *     ───
 *      4
 *
 * 实现方式是 ReplacementSpan：只影响这一段文字的宽度与绘制，不改变文本内容，
 * 所以数据库、错题本、统计里存的还是 "3/4" 这样的纯文本，展示时才变排版。
 */
class FractionSpan(
    private val numerator: String,
    private val denominator: String
) : ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        val w = maxOf(paint.measureText(numerator), paint.measureText(denominator))
        return (w * WIDTH_RATIO).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        baseline: Int,
        bottom: Int,
        paint: Paint
    ) {
        val p = Paint(paint)
        p.textSize = paint.textSize * NUM_SCALE
        p.isAntiAlias = true

        val nw = p.measureText(numerator)
        val dw = p.measureText(denominator)
        val slot = maxOf(nw, dw)
        val totalW = slot * WIDTH_RATIO
        val cx = x + totalW / 2f

        val fm = p.fontMetrics
        val gap = p.textSize * 0.10f
        val bar = maxOf(paint.textSize * 0.06f, 4f)
        // 分数线在文字基线之上，让整块（两行数字 + 线）视觉居中于原文本行
        val lineY = baseline - paint.textSize * 0.22f

        val numBaseline = lineY - bar / 2 - gap - fm.descent
        val denBaseline = lineY + bar / 2 + gap - fm.ascent

        val barW = slot * 1.18f
        canvas.drawText(numerator, cx - nw / 2f, numBaseline, p)
        canvas.drawRect(cx - barW / 2, lineY - bar / 2, cx + barW / 2, lineY + bar / 2, p)
        canvas.drawText(denominator, cx - dw / 2f, denBaseline, p)
    }

    private companion object {
        const val NUM_SCALE = 0.56f
        const val WIDTH_RATIO = 1.24f
    }
}

/**
 * 富文本工具：把算式里的 "a/b" 换成 [FractionSpan]。
 * 只在展示层用，存储一律保持纯文本。
 */
object MathText {

    /** 匹配 3/4、?/4 这种分数（分母必须是数字，分子可以是问号） */
    private val FRACTION = Regex("(\\d+|\\?)/(\\d+)")

    fun spans(raw: String): CharSequence {
        if (!raw.contains('/')) return raw
        val sb = SpannableStringBuilder(raw)
        val matches = FRACTION.findAll(raw).toList().asReversed()
        for (m in matches) {
            sb.setSpan(
                FractionSpan(m.groupValues[1], m.groupValues[2]),
                m.range.first,
                m.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return sb
    }

    fun apply(tv: TextView, raw: String) {
        tv.text = spans(raw)
    }

    /** 含分数时字号要压小，否则上下结构会顶到边界 */
    fun hasFraction(raw: String): Boolean = FRACTION.containsMatchIn(raw)
}

package com.mathking.tv.ui.chart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.mathking.tv.R
import kotlin.math.max

/**
 * 折线图（正确率 / 平均用时 / 成长趋势）。值为 null 表示那天没练，断线处理。
 */
class LineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity

    private var title: String = ""
    private var unit: String = ""
    private var values: List<Float?> = emptyList()
    private var labels: List<String> = emptyList()
    private var minY = 0f
    private var maxY = 100f
    private var lineColor = ContextCompat.getColor(context, R.color.accent)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.card)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.line)
        strokeWidth = 1f * density
        style = Paint.Style.STROKE
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_dim)
        textSize = 16f * scaled
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text)
        textSize = 16f * scaled
        isFakeBoldText = true
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f * scaled
        isFakeBoldText = true
    }

    fun setData(
        title: String,
        values: List<Float?>,
        labels: List<String>,
        minY: Float,
        maxY: Float,
        unit: String,
        colorRes: Int = R.color.accent
    ) {
        this.title = title
        this.values = values
        this.labels = labels
        this.minY = minY
        this.maxY = if (maxY <= minY) minY + 1 else maxY
        this.unit = unit
        this.lineColor = ContextCompat.getColor(context, colorRes)
        linePaint.color = lineColor
        dotPaint.color = lineColor
        valuePaint.color = lineColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 14f * density
        canvas.drawRoundRect(RectF(0f, 0f, w, h), 16f * density, 16f * density, bgPaint)

        canvas.drawText(title, pad, pad + 14f * scaled, titlePaint)
        val last = values.lastOrNull { it != null }
        if (last != null) {
            val text = formatValue(last)
            val tw = valuePaint.measureText(text)
            val unitText = unit
            val uw = if (unitText.isEmpty()) 0f else textPaint.measureText(unitText)
            // 数值靠右，单位紧跟在它右边（之前单位画在数字左边，显示成「% 100」）
            canvas.drawText(text, w - pad - tw - uw, pad + 26f * scaled, valuePaint)
            if (unitText.isNotEmpty()) {
                canvas.drawText(unitText, w - pad - uw, pad + 26f * scaled, textPaint)
            }
        }

        val left = pad + 4f * density
        val top = pad + 26f * scaled
        val right = w - pad
        val bottom = h - 22f * scaled
        if (right <= left || bottom <= top) return

        // 网格
        for (i in 0..3) {
            val y = top + (bottom - top) * i / 3f
            canvas.drawLine(left, y, right, y, gridPaint)
        }

        val n = values.size
        if (n == 0) {
            canvas.drawText("还没有数据，先练一轮吧", left, (top + bottom) / 2, textPaint)
            return
        }

        val span = max(1, n - 1)
        fun xOf(i: Int) = left + (right - left) * i / span
        fun yOf(v: Float): Float {
            val ratio = (v - minY) / (maxY - minY)
            return bottom - (bottom - top) * ratio.coerceIn(0f, 1f)
        }

        // 折线（跳过没有数据的日子）
        val path = Path()
        var started = false
        values.forEachIndexed { i, v ->
            if (v == null) {
                started = false
                return@forEachIndexed
            }
            val x = xOf(i)
            val y = yOf(v)
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }
        linePaint.alpha = 255
        canvas.drawPath(path, linePaint)

        // 数据点
        values.forEachIndexed { i, v ->
            if (v != null) {
                canvas.drawCircle(xOf(i), yOf(v), 2.6f * density, dotPaint)
            }
        }

        // X 轴标签：首、中、尾
        if (labels.isNotEmpty()) {
            val idx = listOf(0, labels.size / 2, labels.size - 1).distinct()
            idx.forEach { i ->
                val text = labels.getOrNull(i) ?: return@forEach
                val tw = textPaint.measureText(text)
                var x = xOf(i) - tw / 2
                x = x.coerceIn(left, right - tw)
                canvas.drawText(text, x, h - 6f * scaled, textPaint)
            }
        }
    }

    private fun formatValue(v: Float): String =
        if (v >= 100 || v <= -100) v.toInt().toString() else String.format("%.1f", v)
}

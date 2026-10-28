package com.mathking.tv.ui.chart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.mathking.tv.R
import kotlin.math.min

/** 横向条形图：各知识点正确率 */
class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity

    private var title = ""
    private var items: List<Pair<String, Float>> = emptyList()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.card)
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.line)
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_dim)
        textSize = 16f * scaled
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text)
        textSize = 14f * scaled
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text)
        textSize = 16f * scaled
        isFakeBoldText = true
    }
    private val green = ContextCompat.getColor(context, R.color.green)
    private val yellow = ContextCompat.getColor(context, R.color.accent)
    private val red = ContextCompat.getColor(context, R.color.red)

    fun setData(title: String, items: List<Pair<String, Float>>) {
        this.title = title
        this.items = items.take(5)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 14f * density
        canvas.drawRoundRect(RectF(0f, 0f, w, h), 16f * density, 16f * density, bgPaint)
        canvas.drawText(title, pad, pad + 14f * scaled, titlePaint)

        if (items.isEmpty()) {
            canvas.drawText("还没有数据", pad, h / 2, textPaint)
            return
        }

        val top = pad + 26f * scaled
        val bottom = h - pad
        val rowH = min(30f * density, (bottom - top) / items.size)
        val labelW = 104f * density
        val percentW = 40f * density
        val barLeft = pad + labelW
        val barRight = w - pad - percentW
        val barMax = (barRight - barLeft).coerceAtLeast(20f)

        items.forEachIndexed { i, (label, value) ->
            val cy = top + rowH * i + rowH / 2
            val shown = if (label.length > 8) label.take(7) + "…" else label
            canvas.drawText(shown, pad, cy + 4f * scaled, labelPaint)

            val barH = 9f * density
            val r = RectF(barLeft, cy - barH / 2, barLeft + barMax, cy + barH / 2)
            canvas.drawRoundRect(r, barH / 2, barH / 2, trackPaint)

            val ratio = (value / 100f).coerceIn(0f, 1f)
            barPaint.color = when {
                value >= 90 -> green
                value >= 70 -> yellow
                else -> red
            }
            val fill = RectF(barLeft, cy - barH / 2, barLeft + barMax * ratio, cy + barH / 2)
            canvas.drawRoundRect(fill, barH / 2, barH / 2, barPaint)

            val text = "${value.toInt()}%"
            canvas.drawText(text, barRight + 8f * density, cy + 4f * scaled, labelPaint)
        }
    }
}

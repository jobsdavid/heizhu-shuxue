package com.mathking.tv.ui.chart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.mathking.tv.R
import java.util.Calendar
import kotlin.math.min

/** 练习日历热力图：一眼看出哪些天练了、练了多少 */
class HeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private val weeks = 8

    /** day(yyyy-MM-dd) -> 题数 */
    private var counts: Map<String, Int> = emptyMap()
    private var endDay: Calendar = Calendar.getInstance()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.card)
    }
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_dim)
        textSize = 16f * scaled
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text)
        textSize = 16f * scaled
        isFakeBoldText = true
    }
    private val lv0 = ContextCompat.getColor(context, R.color.line)
    private val lv1 = ContextCompat.getColor(context, R.color.green).let { mix(it, 0.30f) }
    private val lv2 = mix(ContextCompat.getColor(context, R.color.green), 0.60f)
    private val lv3 = ContextCompat.getColor(context, R.color.green)

    private fun mix(color: Int, ratio: Float): Int {
        val bg = ContextCompat.getColor(context, R.color.card)
        val r = (android.graphics.Color.red(bg) + (android.graphics.Color.red(color) - android.graphics.Color.red(bg)) * ratio).toInt()
        val g = (android.graphics.Color.green(bg) + (android.graphics.Color.green(color) - android.graphics.Color.green(bg)) * ratio).toInt()
        val b = (android.graphics.Color.blue(bg) + (android.graphics.Color.blue(color) - android.graphics.Color.blue(bg)) * ratio).toInt()
        return android.graphics.Color.rgb(r, g, b)
    }

    fun setData(counts: Map<String, Int>, endDay: Calendar) {
        this.counts = counts
        this.endDay = endDay
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 14f * density
        canvas.drawRoundRect(RectF(0f, 0f, w, h), 16f * density, 16f * density, bgPaint)
        canvas.drawText("练习日历 · 近 8 周", pad, pad + 14f * scaled, titlePaint)

        val top = pad + 26f * scaled
        val bottom = h - pad - 12f * scaled
        val left = pad
        val right = w - pad
        if (right <= left || bottom <= top) return

        val cell = min((right - left) / weeks, (bottom - top) / 7)
        val gap = cell * 0.18f
        val gridW = cell * weeks
        val startX = left + ((right - left) - gridW) / 2

        // 从最后一天往回排，列是周
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val cal = endDay.clone() as Calendar
        val totalCells = weeks * 7
        val weekIndex = cal.get(Calendar.DAY_OF_WEEK) // 周日=1
        // 最后一列只画到"今天"为止，上面留空
        val startCol = weeks - 1
        val startRow = weekIndex - 1

        var cellsBack = startCol * 7 + startRow
        var cursor = cal.clone() as Calendar
        while (cellsBack >= 0) {
            val key = fmt.format(cursor.time)
            val count = counts[key] ?: 0
            val row = cellsBack % 7
            val col = cellsBack / 7
            val x = startX + col * cell
            val y = top + row * cell
            cellPaint.color = when {
                count <= 0 -> lv0
                count < 10 -> lv1
                count < 25 -> lv2
                else -> lv3
            }
            canvas.drawRoundRect(
                RectF(x + gap / 2, y + gap / 2, x + cell - gap / 2, y + cell - gap / 2),
                3f * density, 3f * density, cellPaint
            )
            cellsBack--
            cursor.add(Calendar.DAY_OF_YEAR, -1)
        }

        // 图例
        val legendY = h - pad - 2f * scaled
        canvas.drawText("少", left, legendY, textPaint)
        var lx = left + 18f * scaled
        listOf(lv0, lv1, lv2, lv3).forEach {
            cellPaint.color = it
            canvas.drawRect(RectF(lx, legendY - 8f * scaled, lx + 9f * scaled, legendY + 1f * scaled), cellPaint)
            lx += 16f * scaled
        }
        canvas.drawText("多", lx, legendY, textPaint)
    }
}

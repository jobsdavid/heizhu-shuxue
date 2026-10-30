package com.mathking.tv.ui.effect

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 结果页的特效层：满分散花 / 考差了加油。
 *
 * 纯 Canvas 画，不加第三方动画库：
 * - [Mode.CELEBRATE] 彩带：从上方两侧喷出，受重力下落、边落边转，2.8 秒淡出
 * - [Mode.CHEER] 加油：暖色小圆点从底部缓缓升起 + 一句「加油！」轻轻出现再退场
 *
 * 注意：这个 View 铺满整页，必须「不可聚焦、不可点击」，否则遥控器焦点会被它吃掉。
 */
class ConfettiView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { CELEBRATE, CHEER }

    private class P(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var rot: Float,
        var rotV: Float,
        var size: Float,
        var color: Int,
        var circle: Boolean
    )

    private val ps = ArrayList<P>(160)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** 「加油！」底下那块半透明牌子 */
    private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val rect = RectF()

    private var mode = Mode.CELEBRATE
    private var running = false
    private var startAt = 0L
    private var lastFrame = 0L
    private var durationMs = 2800L

    private val confettiColors = intArrayOf(
        Color.parseColor("#FFD166"), // 金
        Color.parseColor("#EF476F"), // 粉
        Color.parseColor("#06D6A0"), // 绿
        Color.parseColor("#4CC9F0"), // 蓝
        Color.parseColor("#F9C74F"), // 橙黄
        Color.parseColor("#B388FF")  // 紫
    )
    private val cheerColors = intArrayOf(
        Color.parseColor("#FFB703"),
        Color.parseColor("#FB8500"),
        Color.parseColor("#FFD166"),
        Color.parseColor("#FF8FA3")
    )

    init {
        isFocusable = false
        isClickable = false
        isSoundEffectsEnabled = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
    }

    private var layoutWait = 0

    /** 播一次特效；view 还没测量完也能调（会自动等布局） */
    fun play(m: Mode) {
        mode = m
        // 必须先可见：View 是 GONE 的时候根本不会被 measure，宽高永远是 0，
        // 以前这里直接 return + post，于是一直在「等布局」，特效永远画不出来。
        visibility = VISIBLE
        if (width == 0 || height == 0) {
            if (layoutWait < 20) {
                layoutWait++
                post { play(m) }
            }
            return
        }
        layoutWait = 0
        ps.clear()
        when (m) {
            Mode.CELEBRATE -> spawnConfetti()
            Mode.CHEER -> spawnCheer()
        }
        durationMs = if (m == Mode.CELEBRATE) 2800L else 2600L
        startAt = SystemClock.uptimeMillis()
        lastFrame = startAt
        running = true
        visibility = VISIBLE
        invalidate()
    }

    fun stop() {
        running = false
        ps.clear()
        visibility = GONE
    }

    private fun spawnConfetti() {
        val w = width.toFloat()
        // 两股从上方两侧斜着喷出来，铺满整个屏幕宽度
        data class Burst(val x: Float, val y: Float, val dir: Float)
        listOf(
            Burst(w * 0.12f, height * 0.05f, 1f),    // 左侧往右喷
            Burst(w * 0.88f, height * 0.05f, -1f)    // 右侧往左喷
        ).forEach { burst ->
            repeat(60) {
                val ang = Math.toRadians(rf(-25f, 35f).toDouble())
                val speed = rf(7f, 15f)
                ps.add(
                    P(
                        x = burst.x + rf(-30f, 30f),
                        y = burst.y + rf(0f, 20f),
                        vx = (cos(ang) * speed * burst.dir).toFloat() + rf(-1.5f, 1.5f),
                        vy = (sin(ang) * speed - 6f).toFloat() + rf(-1f, 1f),
                        rot = rf(0f, 360f),
                        rotV = rf(-14f, 14f),
                        size = rf(10f, 20f),
                        color = confettiColors[Random.nextInt(confettiColors.size)],
                        circle = false
                    )
                )
            }
        }
    }

    private fun spawnCheer() {
        val w = width.toFloat()
        repeat(48) {
            ps.add(
                P(
                    x = rf(0f, w),
                    y = height + rf(0f, height * 0.5f),
                    vx = rf(-0.6f, 0.6f),
                    vy = rf(-3.2f, -1.4f),
                    rot = 0f,
                    rotV = 0f,
                    size = rf(6f, 14f),
                    color = cheerColors[Random.nextInt(cheerColors.size)],
                    circle = true
                )
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!running) return
        val now = SystemClock.uptimeMillis()
        val elapsed = now - startAt
        // 以「帧」为单位积分；卡顿时最多补 3 帧，避免粒子瞬移
        val steps = ((now - lastFrame) / 16f).coerceIn(1f, 3f)
        lastFrame = now

        if (elapsed >= durationMs) {
            running = false
            ps.clear()
            visibility = GONE
            return
        }
        val fade = ((durationMs - elapsed).toFloat() / 600f).coerceIn(0f, 1f)
        val gravity = if (mode == Mode.CELEBRATE) 0.42f else 0f
        val w = width.toFloat()
        val h = height.toFloat()

        for (p in ps) {
            p.vy += gravity * steps
            if (mode == Mode.CELEBRATE) p.vx *= 0.995f
            p.x += p.vx * steps
            p.y += p.vy * steps
            p.rot += p.rotV * steps
            if (mode == Mode.CHEER && p.y < -20f) {
                // 升到顶就换个位置从底下再来，营造「源源不断」的鼓励感
                p.y = h + rf(0f, h * 0.25f)
                p.x = rf(0f, w)
            }

            paint.color = p.color
            paint.alpha = (255 * fade).toInt().coerceIn(0, 255)

            if (p.circle) {
                canvas.drawCircle(p.x, p.y, p.size / 2f, paint)
            } else {
                // 旋转的细长条 = 飘落的彩带
                canvas.save()
                canvas.rotate(p.rot, p.x, p.y)
                rect.set(p.x - p.size / 2f, p.y - p.size / 4f, p.x + p.size / 2f, p.y + p.size / 4f)
                canvas.drawRoundRect(rect, 3f, 3f, paint)
                canvas.restore()
            }
        }

        if (mode == Mode.CHEER) {
            // 「加油！」放上面一点，出现 0.3 秒后淡出，别挡着看错题。
            // 底下垫一块半透明圆角牌：字直接压在今日数据上会像「串行」bug。
            val inAlpha = ((elapsed - 200f) / 300f).coerceIn(0f, 1f)
            val outAlpha = ((2200f - elapsed) / 400f).coerceIn(0f, 1f)
            val a = (minOf(inAlpha, outAlpha) * 255).toInt().coerceIn(0, 255)
            if (a > 0) {
                val textSize = resources.displayMetrics.scaledDensity * 52f
                textPaint.textSize = textSize
                // 靠右侧放：结果页的分数、说明都在中左，右半边是空的，不挡字
                val cx = w * 0.80f
                val cy = h * 0.30f
                val halfW = textPaint.measureText("加油！") / 2f + textSize * 0.5f
                val halfH = textSize * 0.8f
                platePaint.color = Color.parseColor("#D9121726")
                platePaint.alpha = a
                rect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
                canvas.drawRoundRect(rect, textSize * 0.4f, textSize * 0.4f, platePaint)
                textPaint.color = Color.parseColor("#FFB703")
                textPaint.alpha = a
                // drawText 的基线在字的下沿，往下补 0.35 个字高让视觉居中
                canvas.drawText("加油！", cx, cy + textSize * 0.35f, textPaint)
            }
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun rf(min: Float, max: Float): Float = min + Random.nextFloat() * (max - min)
}

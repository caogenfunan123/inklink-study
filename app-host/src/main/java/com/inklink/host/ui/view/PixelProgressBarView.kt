package com.inklink.host.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.inklink.common.utils.MonoClock
import kotlin.math.max
import kotlin.math.min

/**
 * 分段像素胶囊进度条（design 9.2 / V1.1 裁决：废弃 Material 进度条）。
 * 10 格硬边分段；数值低于危险线整条红色闪烁。
 */
class PixelProgressBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var color: Int = 0xFFFF7043.toInt()
        set(v) { field = v; invalidate() }

    /** 低于此值红色闪烁（默认 30） */
    var dangerThreshold: Int = 30
    /** 0..100 */
    var progress: Int = 100
        set(v) {
            field = v.coerceIn(0, 100)
            invalidate()
        }

    private val paint = Paint().apply { isAntiAlias = false }
    private val rect = RectF()

    // 闪烁时钟
    private var blinkOn = true
    private var lastBlink = 0L
    private var blinking = false
    private val blinkRunnable = object : Runnable {
        override fun run() {
            if (!blinking) return
            val now = MonoClock.now()
            if (now - lastBlink > 500) { blinkOn = !blinkOn; lastBlink = now; invalidate() }
            postOnAnimationCompat()
        }
    }

    private fun postOnAnimationCompat() {
        if (blinking) postOnAnimationDelayed(blinkRunnable, 60)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncBlink()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(blinkRunnable)
        blinking = false
    }

    private fun syncBlink() {
        val shouldBlink = progress in 1 until dangerThreshold
        if (shouldBlink && !blinking) {
            blinking = true; lastBlink = MonoClock.now(); blinkOn = true
            postOnAnimationCompat()
        } else if (!shouldBlink && blinking) {
            blinking = false; blinkOn = true
            removeCallbacks(blinkRunnable)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val seg = 10
        val gap = max(1f, h * 0.18f)
        val cellW = (w - gap * (seg - 1)) / seg
        val filled = (progress / 10f).toInt()

        // 轨道底
        paint.color = 0x33FFFFFF
        rect.set(0f, 0f, w, h)
        canvas.drawRect(rect, paint)

        val flashRed = blinking && !blinkOn
        for (i in 0 until seg) {
            val left = i * (cellW + gap)
            paint.color = if (i < filled) {
                if (flashRed) Color.RED else color
            } else {
                0x22FFFFFF
            }
            rect.set(left, 0f, left + cellW, h)
            canvas.drawRect(rect, paint)
        }
        syncBlink()
    }
}

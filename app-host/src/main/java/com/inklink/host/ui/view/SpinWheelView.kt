package com.inklink.host.ui.view

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

/**
 * 幸运转盘（game-juice：Decelerate 长缓动落针，中奖庆祝由 Activity 回调触发）。
 * 四等分扇区、顶部固定三角指针；spinTo 至少转 4 圈后精确停在目标扇区中心线。
 */
class SpinWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val labels = listOf("10🪙", "30🪙", "60🪙", "200🪙")
    private val colors = listOf(0xFFFF7043.toInt(), 0xFFFFA726.toInt(), 0xFF4FC3F7.toInt(), 0xFFEC407A.toInt())

    private val paint = Paint().apply { isAntiAlias = true }
    private val path = Path()
    private var rotationDeg = 0f
    private var spinning = false

    /** 转到 [segment]（0..3，与 PetMiniGameManager.spinWheel 奖励档位一致）后回调。 */
    fun spinTo(segment: Int, onEnd: () -> Unit) {
        if (spinning) return
        spinning = true
        val full = 360f
        // 目标：扇区中心线停在顶部指针下 => 最终旋转角 ≡ -(seg*90+45)
        val want = ((-(segment * 90f + 45f)) % full + full) % full
        val base = ((rotationDeg % full) + full) % full
        var delta = want - base
        if (delta <= 0f) delta += full
        delta += full * 4f
        val from = rotationDeg
        ValueAnimator.ofFloat(0f, delta).apply {
            duration = 2600L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                rotationDeg = from + it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    rotationDeg = ((rotationDeg % full) + full) % full
                    spinning = false
                    invalidate()
                    onEnd()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 18f
        if (r <= 0f) return

        canvas.save()
        canvas.rotate(rotationDeg, cx, cy)
        // 扇区
        for (i in 0 until 4) {
            paint.style = Paint.Style.FILL
            paint.color = colors[i]
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -90f + i * 90f, 90f, true, paint)
        }
        // 白色分隔线
        paint.style = Paint.Style.STROKE
        paint.color = Color.WHITE
        paint.strokeWidth = 4f
        for (i in 0 until 4) {
            val a = Math.toRadians((-90.0 + i * 90.0))
            canvas.drawLine(cx, cy, cx + r * Math.cos(a).toFloat(), cy + r * Math.sin(a).toFloat(), paint)
        }
        // 扇区文字（沿半径方向）
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = r * 0.17f
        paint.isFakeBoldText = true
        for (i in 0 until 4) {
            canvas.save()
            canvas.rotate(-90f + i * 90f + 45f, cx, cy)
            canvas.drawText(labels[i], cx + r * 0.62f, cy + paint.textSize / 3f, paint)
            canvas.restore()
        }
        canvas.restore()

        // 中心轮毂
        paint.isFakeBoldText = false
        paint.color = Color.parseColor("#37474F")
        canvas.drawCircle(cx, cy, r * 0.10f, paint)
        paint.color = Color.parseColor("#B0BEC5")
        canvas.drawCircle(cx, cy, r * 0.05f, paint)

        // 顶部指针（固定在旋转之外）
        paint.color = Color.parseColor("#212121")
        path.reset()
        path.moveTo(cx - 14f, cy - r - 6f)
        path.lineTo(cx + 14f, cy - r - 6f)
        path.lineTo(cx, cy - r + 20f)
        path.close()
        canvas.drawPath(path, paint)
    }
}

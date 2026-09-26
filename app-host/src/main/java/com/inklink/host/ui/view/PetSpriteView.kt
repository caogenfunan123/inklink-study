package com.inklink.host.ui.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.util.LruCache
import android.view.View
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

/**
 * V1.1 部件化像素宠物渲染引擎（裁决 #4：程序化像素为 MAIN，无需任何 PNG 资产）。
 *
 * 一只宠物由独立可变换部件组成：身体 / 头 / 左右手 / 左右脚 / 眼睛 / 嘴巴，
 * 叠加 头饰槽 / 背饰槽 / 尾饰槽。每个部件有 position/rotation/scale/alpha。
 * 一套"姿态(Pose)"= 各部件的目标变换；切换姿态用 [Easing] 程序化补间，可随时打断。
 *
 * 渲染纪律（design 9.2）：整只宠物绘制在 96×96 虚拟画布再整数倍放大（NEAREST），
 * 硬边缘、不开抗锯齿；页面不可见立即停表零后台耗电。
 *
 * 交互（裁决触摸三手势）：单击=转头看向点击处+回弹；[GestureListener] 由外部装配。
 */
class PetSpriteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 虚拟像素画布边长（design：96×96），实际按视图尺寸整数倍放大
    private val grid = 96f

    private val paint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
        isDither = false
    }
    private val rect = RectF()
    private val path = Path()

    // ---------------- 外观设定 ----------------
    private var def: SpeciesDef = PixelSpecies.of("cat")
    private var mood: String = "NORMAL"
    private var lifeStage: String = "ADULT"
    private var sleeping = false
    private var decoId: String = PixelDecor.NONE
    private var finalForm: String = ""
    private var sceneId: String = "bedroom"

    /** 渲染外观模式（2026-08-30：矢量/像素双形态共存，业务零改动）。 */
    private var renderMode: com.inklink.host.state.PetRenderMode = com.inklink.host.state.PetRenderMode.PIXEL_PNG

    /** 渲染外观模式（矢量旧形象 / 复古像素 / PNG素材，design 附录A）。 */
    fun setRenderMode(mode: com.inklink.host.state.PetRenderMode) {
        if (this.renderMode != mode) {
            this.renderMode = mode
            invalidate()
        }
    }

    // 串门访客（10.3 碰头动画）：滑入->碰头->滑出的时间轴（秒）
    private var visitT = -1f
    private var visitPal: PetPalette? = null
    private var visitBumped = false

    // ---------------- 姿态部件 ----------------
    private data class Part(
        var x: Float, var y: Float,        // 相对画布中心偏移
        var rot: Float = 0f,
        var sx: Float = 1f, var sy: Float = 1f,
        var alpha: Int = 255
    )

    private val body = Part(0f, 6f)
    private val head = Part(0f, -18f)
    private val armL = Part(-16f, 4f)
    private val armR = Part(16f, 4f)
    private val footL = Part(-8f, 22f)
    private val footR = Part(8f, 22f)

    // 表情（非刚性部件）
    private var eyeOpen = 1f        // 1 睁眼, 0 闭眼
    private var targetEyeOpen = 1f
    private var sweat = false       // SICK 冷汗（保留位，随机触发）

    // 动作补间状态
    private var pose = Pose.IDLE
    private var phase = 0f          // 动作相位秒
    private var bounceV = 0f        // 跳跃速度（物理）
    private var offsetY = 0f        // 跳跃/受击垂直位移
    private var shakeT = 0f         // 剩余抖动时长
    private var scalePunch = 0f     // 点击缩放脉冲 0..1
    private var lookX = 0f          // 头部朝向（-1..1 看向点击处）
    private var targetLookX = 0f
    private var tailWag = 0f
    private var breath = 0f

    // 粒子（头顶符号/星星），简单自管理
    private val marks = ArrayList<FloatArray>() // x,y,life,vy,type
    private var zzzPhase = 0f

    private var running = false
    private var lastFrameNs = 0L

    enum class Pose { IDLE, EATING, PLAY, STUDY, CLEAN, SLEEP, HAPPY, WEAK, SICK, ANNOY, SCARED }

    // ---------------- 生命周期 ----------------
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!running) startLoop()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopLoop()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            if (!running) startLoop()
        } else {
            stopLoop()
        }
    }

    private fun startLoop() {
        if (running) return
        running = true
        lastFrameNs = 0L
        postInvalidateOnAnimation()
    }

    private fun stopLoop() {
        running = false
    }

    // ---------------- 对外 API ----------------
    /** 设置外观（物种/情绪/阶段/睡眠）。与旧签名兼容。 */
    fun setPetAppearance(petType: String, mood: String, lifeStage: String = "ADULT", isSleeping: Boolean = false) {
        def = PixelSpecies.of(petType)
        this.mood = mood
        this.lifeStage = lifeStage
        this.sleeping = isSleeping
        invalidate()
    }

    /** 像素房间场景背景（design 9.6：卧室/客厅/窗台）。 */
    fun setScene(sceneId: String) {
        if (this.sceneId != sceneId) {
            this.sceneId = sceneId
            invalidate()
        }
    }

    /** 好友/伙伴串门碰头动画（10.3）：访客从右侧滑入、轻碰、再滑出。 */
    fun startVisit(petType: String) {
        visitPal = PixelSpecies.of(petType).palette
        visitT = 0f
        visitBumped = false
        startLoop()
        invalidate()
    }

    /** 佩戴装饰（头饰槽）。 */
    fun setDecoration(decoId: String) {
        this.decoId = decoId
        invalidate()
    }

    fun setFinalForm(form: String) {
        this.finalForm = form
        invalidate()
    }

    /** 旧 API 兼容：Rive 时代叫 fireTrigger，语义等价 playPose。 */
    fun fireTrigger(triggerName: String) = playPose(triggerName)

    /** 触发一个动作（AI 行为 / 互动）。未知动作名安全忽略。 */
    fun playPose(name: String) {
        pose = when (name) {
            "EAT", "FEED" -> Pose.EATING
            "HOP", "EXCITED", "PLAY", "WAVE", "HAPPY" -> Pose.PLAY
            "STUDY", "READ" -> Pose.STUDY
            "SHAKE", "CLEAN", "SCRATCH" -> Pose.CLEAN
            "SLEEP", "SLEEP_MUMBLE", "RUB_EYES", "YAWN" -> Pose.SLEEP
            "WEAK_GROAN" -> Pose.WEAK
            "SICK" -> Pose.SICK
            "ANNOY", "REJECT" -> Pose.ANNOY
            "SCARED" -> Pose.SCARED
            "EGG_WOBBLE" -> Pose.HAPPY
            "HEAD_LOW" -> Pose.WEAK
            "RUB_BELLY" -> Pose.CLEAN
            "LOOK_AROUND" -> Pose.IDLE
            "BLINK", "STRETCH", "SIGH", "IDLE" -> Pose.IDLE
            else -> Pose.IDLE
        }
        phase = 0f
        when (pose) {
            Pose.PLAY, Pose.HAPPY -> { bounceV = -1.6f }
            Pose.CLEAN -> shakeT = 0.8f
            Pose.SCARED -> shakeT = 1.0f
            Pose.ANNOY -> shakeT = 0.5f
            else -> Unit
        }
        startLoop()
        invalidate()
    }

    /** 单击宠物：看向点击处（局部 x 归一 -1..1）+ 回弹脉冲。 */
    fun onTapAt(normalizedX: Float) {
        targetLookX = normalizedX.coerceIn(-1f, 1f)
        scalePunch = 1f
        spawnMark(1f)
        startLoop()
    }

    /** 抚摸（长按）：撒爱心，眯眼。 */
    fun onAffection() {
        pose = Pose.HAPPY; phase = 0f
        for (i in 0 until 4) spawnMark(3f)
        startLoop()
    }

    /** 烦躁（连续点）：摇头 + 汗滴。 */
    fun onAnnoy() {
        pose = Pose.ANNOY; phase = 0f
        shakeT = 0.6f
        spawnMark(2f)
        startLoop()
    }

    // 触摸手势（三手势）由 Activity 层 GestureDetector 统一装配，
    // 本视图仅暴露 onTapAt / onAffection / onAnnoy 与坐标换算。

    /** 供 Activity 用 GestureDetector 复用：把屏幕 x 转成宠物局部归一方向。 */
    fun normalizedXof(screenX: Float): Float {
        val cx = width / 2f
        return ((screenX - cx) / (width / 2f)).coerceIn(-1f, 1f)
    }

    private fun spawnMark(type: Float) {
        if (marks.size > 12) return
        marks.add(floatArrayOf(head.x, head.y - 20f, 1f, -0.6f, type))
    }

    // ---------------- 帧循环 ----------------
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = if (lastFrameNs == 0L) 0f else {
            ((System.nanoTime() - lastFrameNs) / 1_000_000_000.0).toFloat()
        }.coerceAtMost(0.1f)
        lastFrameNs = System.nanoTime()
        update(t)
        render(canvas)
        if (running) postInvalidateOnAnimation()
    }

    private fun update(dt: Float) {
        breath += dt * (if (sleeping || pose == Pose.SLEEP) 0.5f else 1.4f)
        pngSwap += dt  // PIXEL_PNG 呼吸样板帧节拍
        tailWag += dt * (if (pose == Pose.PLAY || pose == Pose.HAPPY) 8f else 2.5f)
        phase += dt

        // 头部朝向缓动
        lookX += (targetLookX - lookX) * min(1f, dt * 8f)
        targetLookX *= (1f - min(1f, dt * 1.5f)) // 慢慢回正

        // 眨眼（自主，约 4s 一次）+ 表情眨眼
        blinkTick(dt)

        // 跳跃物理（PLAY/HAPPY 弹跳）
        if (pose == Pose.PLAY || pose == Pose.HAPPY) {
            bounceV += dt * 6f // 重力
            offsetY += bounceV * dt * 20f
            if (offsetY >= 0f) { offsetY = 0f; bounceV = -if (pose == Pose.PLAY) 2.2f else 1.4f }
        } else {
            offsetY += (0f - offsetY) * min(1f, dt * 10f)
            bounceV = 0f
        }

        // 抖动衰减
        if (shakeT > 0) shakeT = max(0f, shakeT - dt)
        if (scalePunch > 0) scalePunch = max(0f, scalePunch - dt * 3f)

        // 动作自动回到 IDLE（一次性动作播完）
        val dur = when (pose) {
            Pose.EATING -> 2.2f
            Pose.STUDY -> 2.6f
            Pose.CLEAN -> 1.2f
            Pose.ANNOY -> 1.0f
            Pose.SCARED -> 1.2f
            else -> 0f // IDLE/PLAY/HAPPY/SLEEP/WEAK/SICK 循环
        }
        if (dur > 0f && phase > dur) pose = if (sleeping) Pose.SLEEP else Pose.IDLE

        // 粒子
        for (i in marks.indices.reversed()) {
            val m = marks[i]
            m[1] += m[3] * dt * 20f
            m[2] -= dt * 1.1f
            if (m[2] <= 0f) marks.removeAt(i)
        }
        if (sleeping || pose == Pose.SLEEP) zzzPhase += dt

        // 串门访客时间轴：0.4 滑入 / 0.4~0.9 停留碰头 / 0.9~1.3 滑出，>1.4 结束
        if (visitT >= 0f) {
            visitT += dt
            if (!visitBumped && visitT >= 0.55f) {
                visitBumped = true
                spawnMark(1f) // 碰头冒爱心
            }
            if (visitT > 1.5f) { visitT = -1f; visitPal = null }
        }
    }

    private var blinkTimer = 3f
    private var blinkHold = 0f
    private fun blinkTick(dt: Float) {
        if (sleeping || pose == Pose.SLEEP || pose == Pose.WEAK || pose == Pose.SICK) {
            targetEyeOpen = 0f
        } else targetEyeOpen = 1f
        blinkTimer -= dt
        if (blinkTimer <= 0f) { blinkHold = 0.14f; blinkTimer = 3f + (Math.random() * 3f).toFloat() }
        if (blinkHold > 0f) { blinkHold -= dt; targetEyeOpen = 0f }
        eyeOpen += (targetEyeOpen - eyeOpen) * min(1f, dt * 18f)
    }

    // ---------------- 渲染（像素网格坐标） ----------------
    private fun render(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        if (renderMode == com.inklink.host.state.PetRenderMode.PIXEL_PNG) {
            drawPngPet(canvas, w, h)
            return
        }

        if (renderMode == com.inklink.host.state.PetRenderMode.PIXEL_ARCADE_SPRITE) {
            drawArcadePet(canvas, w, h)
            return
        }

        if (renderMode == com.inklink.host.state.PetRenderMode.PIXEL_SCENE) {
            drawScenePet(canvas, w, h)
            return
        }

        if (renderMode == com.inklink.host.state.PetRenderMode.PIXEL_RETRO) {
            drawPixelPet(canvas, w, h)
            return
        }

        val scale = min(w, h) / grid
        // design 9.2：整数倍放大防脏边（四舍五入到整格，≥3）
        val effScale = max(3f, round(scale))
        val cx = w / 2f
        val cy = h / 2f

        // 场景背景铺满视图（不参与像素变换，单独按视图尺寸绘制）
        drawScene(canvas, w, h)

        val pal = currentPalette()
        // 全局挤压
        val sx = (1f + scalePunch * 0.12f)
        val sy = (1f - scalePunch * 0.10f)
        val shake = if (shakeT > 0) sin(shakeT * 60f) * 3f else 0f

        canvas.save()
        canvas.translate(cx + shake, cy)
        canvas.scale(effScale, effScale)
        canvas.scale(sx, sy)

        if (lifeStage == "EGG") {
            drawEgg(canvas, pal)
            canvas.restore()
            drawMarksAndStatus(canvas, effScale, cx, cy)
            drawVisitor(canvas, effScale, cx, cy)
            return
        }

        val stageScale = when (lifeStage) {
            "CUB" -> 0.62f
            "JUVENILE" -> 0.8f
            "ADOLESCENT" -> 0.9f
            else -> 1.0f
        }
        val breathS = 1f + 0.03f * sin(breath)

        // 尾饰（在身体后）
        drawTailBack(canvas, pal, stageScale)
        // 背饰（翅膀/刺，在身体后）
        drawBackSlot(canvas, pal, stageScale)

        // 四肢（身体后；蛇等无肢物种跳过——仅渲染形态差异，业务零改动）
        if (!def.noLimbs) {
            drawLimb(canvas, footL, pal, stageScale)
            drawLimb(canvas, footR, pal, stageScale)
        }

        // 身体
        canvas.save()
        canvas.translate(body.x, body.y + offsetY)
        canvas.scale(breathS * stageScale, breathS * stageScale)
        fillCircle(canvas, 0f, 0f, 20f, pal.body)
        if (bellyVisible()) fillOval(canvas, 0f, 6f, 13f, 11f, pal.belly) // 肚皮
        // 萎靡形态压暗 + 虚弱/生病苍白
        val pale = when {
            mood == "WEAK" -> 0.55f
            mood == "SICK" -> 0.4f
            finalForm == "DROOPY" -> 0.35f
            else -> 0f
        }
        if (pale > 0f) fillOval(canvas, 0f, 0f, 20f, 20f, 0xFFB0BEC5.toInt(), (pale * 255).toInt())
        canvas.restore()

        // 手臂（身体前；无肢物种跳过）
        if (!def.noLimbs) {
            drawArm(canvas, armL, pal, stageScale, true)
            drawArm(canvas, armR, pal, stageScale, false)
        }

        // 头（含朝向旋转）
        canvas.save()
        val headRot = lookX * 0.25f + poseHeadRot()
        canvas.translate(head.x + lookX * 2f, head.y + offsetY)
        canvas.rotate(deg(headRot))
        canvas.scale(stageScale, stageScale)
        fillCircle(canvas, 0f, 0f, 16f, pal.body)
        if (def.eyePatch) { // 熊猫眼斑
            fillCircle(canvas, -6f, -1f, 5f, pal.patch)
            fillCircle(canvas, 6f, -1f, 5f, pal.patch)
        }
        drawHeadSlot(canvas, pal, stageScale)
        drawFace(canvas, pal)
        canvas.restore()

        // 装饰（头饰槽，随头但独立绘制层）
        drawDecoration(canvas, pal, stageScale)
        // 成年形态装饰层（9.3：学霸帽/活力星；DROOPY 用身体压暗已实现）
        drawFormLayer(canvas, stageScale)

        canvas.restore()

        drawMarksAndStatus(canvas, effScale, cx, cy)
        drawVisitor(canvas, effScale, cx, cy)
    }

    /** 串门访客（10.3）：简化 Q 版小人，右侧滑入->轻碰->跳一下->滑出。 */
    private fun drawVisitor(canvas: Canvas, effScale: Float, cx: Float, cy: Float) {
        val pal = visitPal ?: return
        if (visitT < 0f) return
        val w = width.toFloat()
        val offscreen = w + 80f
        // 时间轴: [0,0.4) 滑入, [0.4,0.9) 停留(0.55 碰头), [0.9,1.3) 滑出
        val x = when {
            visitT < 0.4f -> {
                val k = visitT / 0.4f
                offscreen - (offscreen - (cx + w * 0.26f)) * k
            }
            visitT < 0.9f -> cx + w * 0.26f
            else -> {
                val k = ((visitT - 0.9f) / 0.5f).coerceAtMost(1f)
                cx + w * 0.26f + (offscreen - (cx + w * 0.26f)) * k
            }
        }
        val hop = if (visitT in 0.4f..0.9f) kotlin.math.abs(sin((visitT - 0.4f) * 10f)) * 8f else 0f
        val bump = if (visitBumped && visitT < 0.9f) -6f else 0f // 碰头时向主宠倾斜
        canvas.save()
        canvas.translate(x + bump, cy + 20f - hop * effScale * 0.5f)
        canvas.scale(effScale * 0.62f, effScale * 0.62f)
        // 简化身体 + 头
        fillCircle(canvas, 0f, 0f, 16f, pal.body)
        fillCircle(canvas, 0f, -18f, 11f, pal.body)
        fillOval(canvas, 0f, 4f, 10f, 8f, pal.belly)
        fillCircle(canvas, -4f, -19f, 1.8f, 0xFF212121.toInt())
        fillCircle(canvas, 4f, -19f, 1.8f, 0xFF212121.toInt())
        fillCircle(canvas, 0f, 14f, 4f, pal.shade) // 小脚
        canvas.restore()
    }

    private fun bellyVisible() = def.code in setOf(
        "penguin", "hamster", "sheep", "rabbit", "dog", "fox", "dragon", "hedgehog",
        "frog", "pig", "owl", "snake"
    )

    private fun poseHeadRot(): Float = when (pose) {
        Pose.EATING -> sin(phase * 10f) * 0.18f
        Pose.STUDY -> 0.35f + sin(phase * 2f) * 0.05f  // 低头看书
        Pose.ANNOY -> sin(phase * 20f) * 0.3f          // 摇头
        Pose.HAPPY, Pose.PLAY -> sin(phase * 8f) * 0.12f
        Pose.WEAK, Pose.SICK -> 0.25f                   // 垂头
        else -> sin(breath * 0.5f) * 0.04f
    }

    private fun drawEgg(canvas: Canvas, pal: PetPalette) {
        val wob = if (pose == Pose.HAPPY || lifeStage == "EGG") sin((System.nanoTime() / 1e8).toFloat()) * 0.06f else 0f
        canvas.save()
        canvas.rotate(deg(wob))
        fillOval(canvas, 0f, 2f, 15f, 19f, 0xFFFFF8E1.toInt())
        // 斑点（用物种 accent）
        fillCircle(canvas, -6f, -4f, 2.2f, pal.special, 120)
        fillCircle(canvas, 5f, 6f, 2.6f, pal.shade, 120)
        fillCircle(canvas, 2f, -8f, 1.8f, pal.special, 120)
        // 裂纹
        path.reset()
        path.moveTo(-6f, 0f); path.lineTo(-2f, 3f); path.lineTo(2f, -1f); path.lineTo(6f, 3f)
        paint.color = 0xFF8D6E63.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f
        canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
        canvas.restore()
    }

    private fun drawLimb(canvas: Canvas, p: Part, pal: PetPalette, s: Float) {
        canvas.save()
        canvas.translate(p.x * s, (p.y + offsetY) * s)
        canvas.scale(s, s)
        val kick = when (pose) {
            Pose.PLAY, Pose.HAPPY -> sin(phase * 12f + p.x) * 0.3f
            else -> 0f
        }
        canvas.rotate(deg(kick))
        fillOval(canvas, 0f, 2f, 4.5f, 5.5f, pal.shade)
        canvas.restore()
    }

    private fun drawArm(canvas: Canvas, p: Part, pal: PetPalette, s: Float, left: Boolean) {
        val swing = when (pose) {
            Pose.PLAY, Pose.HAPPY -> sin(phase * 12f + if (left) 0f else PI) * 0.9f
            Pose.STUDY -> if (left) -0.9f else 0.9f
            Pose.WEAK, Pose.SICK -> 0.15f
            else -> sin(breath + if (left) 0f else PI) * 0.08f
        }
        canvas.save()
        canvas.translate(p.x * s, (p.y + offsetY) * s)
        canvas.rotate(deg(swing))
        canvas.scale(s, s)
        fillOval(canvas, 0f, 5f, 3.5f, 7f, pal.body)
        canvas.restore()
    }

    private fun drawTailBack(canvas: Canvas, pal: PetPalette, s: Float) {
        // 蛇盘尾：身体下方一圈盘绕，无摆动
        if (def.tailSlot == "coil") {
            canvas.save()
            canvas.translate(0f, 18f * s + offsetY * s)
            canvas.scale(s, s)
            path.reset()
            path.addOval(-14f, -5f, 14f, 7f, android.graphics.Path.Direction.CW)
            fillPath(path, pal.shade, canvas)
            path.reset()
            path.addOval(-9f, -3f, 9f, 4f, android.graphics.Path.Direction.CW)
            fillPath(path, pal.body, canvas)
            canvas.restore()
            return
        }
        val base = when (def.tailSlot) {
            "curvy", "nub", "cotton", "fan", "bushy", "longtail" -> true
            else -> false
        }
        if (!base) return
        val wag = sin(tailWag) * 0.5f
        canvas.save()
        canvas.translate(18f * s, 8f * s + offsetY * s)
        canvas.rotate(deg(wag))
        canvas.scale(s, s)
        when (def.tailSlot) {
            "curvy" -> fillOval(canvas, 3f, -4f, 3f, 7f, pal.shade)
            "nub" -> fillCircle(canvas, 2f, 0f, 3f, pal.shade)
            "cotton" -> fillCircle(canvas, 3f, 0f, 4f, 0xFFFFFFFF.toInt())
            "fan" -> { path.reset(); path.moveTo(0f, 0f); path.lineTo(10f, -6f); path.lineTo(12f, 2f); path.lineTo(0f, 3f); path.close(); fillPath(path, pal.shade, canvas) }
            "bushy" -> { fillCircle(canvas, 4f, 0f, 5f, pal.shade); fillCircle(canvas, 9f, -1f, 3f, pal.belly) }
            "longtail" -> fillOval(canvas, 8f, 2f, 4f, 10f, pal.shade)
        }
        canvas.restore()
    }

    private fun drawBackSlot(canvas: Canvas, pal: PetPalette, s: Float) {
        when (def.backSlot) {
            "wings" -> {
                val flap = if (pose == Pose.PLAY || pose == Pose.HAPPY) sin(phase * 14f) * 0.5f else 0f
                for (dir in intArrayOf(-1, 1)) {
                    canvas.save()
                    canvas.translate(dir * 8f * s, 0f + offsetY * s)
                    canvas.rotate(deg(flap * dir))
                    canvas.scale(s, s)
                    path.reset(); path.moveTo(0f, 0f); path.lineTo(dir * 14f, -8f); path.lineTo(dir * 10f, 8f); path.close()
                    fillPath(path, pal.patch, canvas)
                    canvas.restore()
                }
            }
            "spikes" -> {
                canvas.save(); canvas.translate(0f, -6f * s + offsetY * s); canvas.scale(s, s)
                var x = -12f
                while (x <= 12f) {
                    path.reset(); path.moveTo(x, -12f); path.lineTo(x + 3f, -20f); path.lineTo(x + 6f, -12f); path.close()
                    fillPath(path, pal.patch, canvas); x += 6f
                }
                canvas.restore()
            }
            "frill" -> {
                canvas.save(); canvas.translate(0f, -10f * s + offsetY * s); canvas.scale(s, s)
                fillCircle(canvas, -8f, 0f, 5f, pal.body); fillCircle(canvas, 0f, -2f, 6f, pal.body); fillCircle(canvas, 8f, 0f, 5f, pal.body)
                canvas.restore()
            }
        }
    }

    private fun drawHeadSlot(canvas: Canvas, pal: PetPalette, s: Float) {
        when (def.headSlot) {
            "cat_ears", "big_ears", "tiny_ears" -> {
                val big = if (def.headSlot == "big_ears") 1.4f else if (def.headSlot == "tiny_ears") 0.6f else 1f
                for (dir in intArrayOf(-1, 1)) {
                    path.reset()
                    path.moveTo(dir * 6f, -12f)
                    path.lineTo(dir * (9f * big), -20f * big)
                    path.lineTo(dir * 2f, -15f)
                    path.close()
                    fillPath(path, pal.body, canvas)
                    path.reset()
                    path.moveTo(dir * 6f, -13f)
                    path.lineTo(dir * (8f * big), -18f * big)
                    path.lineTo(dir * 4f, -14f)
                    path.close()
                    fillPath(path, pal.cheek, canvas)
                }
            }
            "drop_ears" -> {
                for (dir in intArrayOf(-1, 1)) {
                    canvas.save(); canvas.rotate(dir * 15f, dir * 12f, -8f)
                    fillOval(canvas, dir * 13f, -2f, 4f, 12f, pal.shade)
                    canvas.restore()
                }
            }
            "long_ears" -> {
                val flop = if (pose == Pose.HAPPY) sin(phase * 10f) * 0.2f else 0f
                for (dir in intArrayOf(-1, 1)) {
                    canvas.save(); canvas.rotate(dir * (10f + flop * 10f), dir * 4f, -14f)
                    fillOval(canvas, dir * 6f, -24f, 3.5f, 12f, pal.body)
                    fillOval(canvas, dir * 6f, -24f, 1.6f, 8f, pal.cheek)
                    canvas.restore()
                }
            }
            "round_ears" -> {
                for (dir in intArrayOf(-1, 1)) fillCircle(canvas, dir * 10f, -12f, 5f, pal.body)
            }
            "horns" -> {
                for (dir in intArrayOf(-1, 1)) {
                    path.reset(); path.moveTo(dir * 6f, -12f); path.lineTo(dir * 10f, -22f); path.lineTo(dir * 3f, -14f); path.close()
                    fillPath(path, pal.patch, canvas)
                }
            }
            "wool_horns" -> {
                for (dir in intArrayOf(-1, 1)) {
                    fillCircle(canvas, dir * 11f, -10f, 4f, pal.patch)
                    fillCircle(canvas, dir * 11f, -10f, 2f, pal.shade)
                }
                fillCircle(canvas, 0f, -14f, 5f, pal.belly) // 羊毛帽
                fillCircle(canvas, -6f, -13f, 4f, pal.belly)
                fillCircle(canvas, 6f, -13f, 4f, pal.belly)
            }
            "frog_eyes" -> { // 青蛙鼓眼：头顶两颗大眼泡
                for (dir in intArrayOf(-1, 1)) {
                    fillCircle(canvas, dir * 7f, -14f, 5f, pal.body)
                    fillCircle(canvas, dir * 7f, -14f, 3f, 0xFFFFFFFF.toInt())
                    fillCircle(canvas, dir * 7f, -14f, 1.6f, 0xFF212121.toInt())
                }
            }
            "tufts" -> { // 猫头鹰耳羽簇
                for (dir in intArrayOf(-1, 1)) {
                    path.reset()
                    path.moveTo(dir * 7f, -13f); path.lineTo(dir * 11f, -21f); path.lineTo(dir * 4f, -15f); path.close()
                    fillPath(path, pal.shade, canvas)
                }
            }
        }
    }

    private fun drawDecoration(canvas: Canvas, pal: PetPalette, s: Float) {
        if (decoId == PixelDecor.NONE) return
        canvas.save()
        canvas.translate(head.x + lookX * 2f, head.y + offsetY)
        canvas.scale(s, s)
        when (decoId) {
            PixelDecor.BANDANA -> {
                path.reset(); path.moveTo(-14f, -8f); path.lineTo(14f, -8f); path.lineTo(0f, -3f); path.close()
                fillPath(path, 0xFFE53935.toInt(), canvas)
                fillRect(canvas, -14f, -9f, 28f, 3f, 0xFFE53935.toInt())
            }
            PixelDecor.GLASSES -> {
                paint.color = 0xFF212121.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.2f
                rect.set(-10f, -4f, -2f, 4f); canvas.drawRoundRect(rect, 1f, 1f, paint)
                rect.set(2f, -4f, 10f, 4f); canvas.drawRoundRect(rect, 1f, 1f, paint)
                canvas.drawLine(-2f, 0f, 2f, 0f, paint); paint.style = Paint.Style.FILL
            }
            PixelDecor.BOW -> {
                fillCircle(canvas, 0f, -14f, 3f, 0xFFEC407A.toInt())
                fillOval(canvas, -5f, -14f, 4f, 3f, 0xFFF06292.toInt())
                fillOval(canvas, 5f, -14f, 4f, 3f, 0xFFF06292.toInt())
            }
            PixelDecor.CROWN -> {
                path.reset(); path.moveTo(-8f, -14f); path.lineTo(-8f, -20f); path.lineTo(-3f, -16f); path.lineTo(0f, -22f)
                path.lineTo(3f, -16f); path.lineTo(8f, -20f); path.lineTo(8f, -14f); path.close()
                fillPath(path, 0xFFFFD54F.toInt(), canvas)
            }
        }
        canvas.restore()
    }

    /** 成年 4 形态的贴片区：BALANCED 无 / PLAYFUL 头顶星星 / STUDIOUS 学士帽 / DROOPY 仅压暗。 */
    private fun drawFormLayer(canvas: Canvas, s: Float) {
        if (finalForm == "BALANCED" || finalForm.isEmpty()) return
        paint.style = Paint.Style.FILL
        canvas.save()
        canvas.translate(head.x + lookX * 2f, head.y + offsetY)
        canvas.scale(s, s)
        when (finalForm) {
            "STUDIOUS" -> {
                // 学士帽：平板 + 流苏
                fillRect(canvas, -12f, -18f, 24f, 3f, 0xFF37474F.toInt())
                fillRect(canvas, -7f, -22f, 14f, 5f, 0xFF455A64.toInt())
                stroke(canvas, 10f, -17f, 13f, -8f, 0xFFFFD54F.toInt())
                fillCircle(canvas, 13f, -7f, 1.6f, 0xFFFFD54F.toInt())
            }
            "PLAYFUL" -> {
                // 活力星：随 tailWag 闪烁
                val a = (0.6f + 0.4f * sin(tailWag * 2f))
                star(canvas, 14f, -16f, 4.2f, 0xFFFFD54F.toInt(), (a * 255).toInt())
                star(canvas, -13f, -12f, 2.6f, 0xFFFFF176.toInt(), (a * 200).toInt())
            }
            "DROOPY" -> {
                // 头顶乌云
                fillCircle(canvas, -6f, -18f, 4f, 0xFF90A4AE.toInt())
                fillCircle(canvas, 0f, -20f, 5f, 0xFF78909C.toInt())
                fillCircle(canvas, 6f, -18f, 4f, 0xFF90A4AE.toInt())
            }
        }
        canvas.restore()
    }

    private fun drawFace(canvas: Canvas, pal: PetPalette) {
        val ex = 6f
        val ey = -1f
        val droop = pose == Pose.WEAK || pose == Pose.SICK || finalForm == "DROOPY"
        // 眼睛
        if (eyeOpen > 0.2f) {
            when (mood) {
                "SICK" -> { drawXEye(canvas, -ex, ey, pal); drawXEye(canvas, ex, ey, pal) }
                "HAPPY" -> { drawArcEye(canvas, -ex, ey, true, pal); drawArcEye(canvas, ex, ey, true, pal) }
                else -> {
                    val r = if (def.bigEyes) 3.4f else if (droop) 1.8f else 2.2f
                    fillCircle(canvas, -ex, ey + if (droop) 1f else 0f, r, 0xFF212121.toInt())
                    fillCircle(canvas, ex, ey + if (droop) 1f else 0f, r, 0xFF212121.toInt())
                    fillCircle(canvas, -ex + 0.6f, ey - 0.4f, 0.7f, 0xFFFFFFFF.toInt())
                    fillCircle(canvas, ex + 0.6f, ey - 0.4f, 0.7f, 0xFFFFFFFF.toInt())
                }
            }
        } else {
            // 闭眼线
            stroke(canvas, -ex - 2f, ey, -ex + 2f, ey, pal.line)
            stroke(canvas, ex - 2f, ey, ex + 2f, ey, pal.line)
        }
        // 腮红
        fillCircle(canvas, -ex - 4f, 3f, 2.5f, pal.cheek, 120)
        fillCircle(canvas, ex + 4f, 3f, 2.5f, pal.cheek, 120)
        // 嘴（猪物种用吻部圆盘替代）
        val my = 6f
        if (def.snout) {
            fillOval(canvas, 0f, my, 6.5f, 4.8f, pal.cheek)
            fillCircle(canvas, -2.2f, my, 1f, pal.line)
            fillCircle(canvas, 2.2f, my, 1f, pal.line)
        } else when {
            pose == Pose.EATING -> {
                val open = (sin(phase * 14f) + 1f) / 2f
                fillOval(canvas, 0f, my, 2.5f, 1f + open * 2.5f, 0xFF4E342E.toInt())
            }
            pose == Pose.SLEEP || sleeping -> stroke(canvas, -2f, my, 2f, my, pal.line)
            mood == "HUNGRY" || mood == "SICK" || droop -> {
                path.reset(); path.moveTo(-3f, my + 1.5f); path.quadTo(0f, my - 1f, 3f, my + 1.5f)
                paint.color = pal.line; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.1f; canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
            }
            mood == "HAPPY" || pose == Pose.HAPPY -> {
                path.reset(); path.moveTo(-3f, my - 1f); path.quadTo(0f, my + 2f, 3f, my - 1f)
                paint.color = 0xFFD33931.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.1f; canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
            }
            else -> stroke(canvas, -2.5f, my, 2.5f, my, pal.line)
        }
        if (def.beak) { // 企鹅喙
            path.reset(); path.moveTo(-3f, 3f); path.lineTo(3f, 3f); path.lineTo(0f, 7f); path.close()
            fillPath(path, 0xFFFFB300.toInt(), canvas)
        }
        if (sweat || pose == Pose.SICK || mood == "SICK") {
            fillOval(canvas, ex + 3f, -6f, 1.5f, 2.5f, 0xFF81D4FA.toInt())
        }
    }

    private fun drawXEye(canvas: Canvas, x: Float, y: Float, pal: PetPalette) {
        stroke(canvas, x - 2f, y - 2f, x + 2f, y + 2f, 0xFF212121.toInt())
        stroke(canvas, x + 2f, y - 2f, x - 2f, y + 2f, 0xFF212121.toInt())
    }

    private fun drawArcEye(canvas: Canvas, x: Float, y: Float, up: Boolean, pal: PetPalette) {
        path.reset()
        if (up) { path.moveTo(x - 2.5f, y + 1f); path.quadTo(x, y - 2f, x + 2.5f, y + 1f) }
        else { path.moveTo(x - 2.5f, y - 1f); path.quadTo(x, y + 2f, x + 2.5f, y - 1f) }
        paint.color = pal.line; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.2f
        canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
    }

    // ---------------- 头顶符号 + Zzz + 状态 ----------------
    private fun drawMarksAndStatus(canvas: Canvas, effScale: Float, cx: Float, cy: Float) {
        val s = effScale
        // 粒子（星星/爱心/汗）
        for (m in marks) {
            val mx = cx + m[0] * s
            val my = cy + m[1] * s
            val a = (m[2] * 255).toInt().coerceIn(0, 255)
            when (m[4].toInt()) {
                1 -> star(canvas, mx, my, 4f * s / 6, 0xFFFFD54F.toInt(), a)
                2 -> { paint.color = Color.argb(a, 0x81, 0xD4, 0xFA); canvas.drawRect(mx - 1.5f, my - 2f, mx + 1.5f, my + 2f, paint) }
                3 -> heart(canvas, mx, my, 5f * s / 6, 0xFFEC407A.toInt(), a)
            }
        }
        // 睡眠 Zzz
        if (sleeping || pose == Pose.SLEEP) {
            val bob = sin(zzzPhase * 2f) * 4f
            paint.color = 0xFF9FA8DA.toInt(); paint.textSize = 16f * (s / 6f); paint.isAntiAlias = false
            canvas.drawText("Z", cx + 18f * (s / 6) * 3, cy - 24f + bob, paint)
            paint.textSize = 11f * (s / 6f)
            canvas.drawText("z", cx + 30f * (s / 6) * 3, cy - 34f + bob - 6f, paint)
        }
        // 饥饿/脏 状态符号（裁决：不做专属身体动画，用头顶符号）
        if (!sleeping) {
            when (mood) {
                "HUNGRY" -> { paint.color = 0xFFFF7043.toInt(); paint.textSize = 20f * (s / 6f); canvas.drawText("❗", cx - 6f, cy - 46f, paint) }
                "DIRTY" -> { paint.color = 0xFF8D6E63.toInt(); paint.textSize = 18f * (s / 6f); canvas.drawText("✕", cx + 22f, cy - 40f, paint) }
            }
        }
    }

    private fun star(canvas: Canvas, x: Float, y: Float, r: Float, color: Int, a: Int) {
        paint.color = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawRect(x - r, y - 1f, x + r, y + 1f, paint)
        canvas.drawRect(x - 1f, y - r, x + 1f, y + r, paint)
    }

    private fun heart(canvas: Canvas, x: Float, y: Float, r: Float, color: Int, a: Int) {
        paint.color = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawCircle(x - r * 0.4f, y, r * 0.5f, paint)
        canvas.drawCircle(x + r * 0.4f, y, r * 0.5f, paint)
        path.reset(); path.moveTo(x - r * 0.9f, y + 0.2f); path.lineTo(x, y + r); path.lineTo(x + r * 0.9f, y + 0.2f); path.close()
        canvas.drawPath(path, paint)
    }

    // ---------------- 绘制原语（硬边，无 AA） ----------------
    private fun fillCircle(canvas: Canvas, x: Float, y: Float, r: Float, color: Int, a: Int = 255) {
        if (r <= 0f) return
        paint.color = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawCircle(x, y, r, paint)
    }

    private fun fillOval(canvas: Canvas, x: Float, y: Float, rx: Float, ry: Float, color: Int, a: Int = 255) {
        paint.color = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        rect.set(x - rx, y - ry, x + rx, y + ry); canvas.drawOval(rect, paint)
    }

    private fun fillRect(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int) {
        paint.color = color; canvas.drawRect(x, y, x + w, y + h, paint)
    }

    private fun fillPath(path: Path, color: Int, canvas: Canvas) {
        paint.color = color; paint.style = Paint.Style.FILL; canvas.drawPath(path, paint)
    }

    private fun stroke(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.1f
        canvas.drawLine(x1, y1, x2, y2, paint); paint.style = Paint.Style.FILL
    }

    private fun currentPalette(): PetPalette = def.palette

    // ---------------- 像素房间场景（程序化，硬边矩形） ----------------
    private fun drawScene(canvas: Canvas, w: Float, h: Float) {
        // 墙
        paint.color = when (sceneId) {
            "living_room" -> 0xFFEFE7D6.toInt()
            "windowsill" -> 0xFFE8F1F8.toInt()
            else -> 0xFFF3E5F5.toInt()
        }
        canvas.drawRect(0f, 0f, w, h * 0.72f, paint)
        // 地板（格纹）
        paint.color = when (sceneId) {
            "living_room" -> 0xFFBCAAA4.toInt()
            "windowsill" -> 0xFFB0BEC5.toInt()
            else -> 0xFFFFE0B2.toInt()
        }
        canvas.drawRect(0f, h * 0.72f, w, h, paint)
        paint.color = 0x22000000
        val tile = w / 8f
        var i = 0
        while (i < 8) {
            if (i % 2 == 0) canvas.drawRect(i * tile, h * 0.72f, (i + 1) * tile, h, paint)
            i++
        }
        when (sceneId) {
            "living_room" -> {
                // 沙发
                paint.color = 0xFF7986CB.toInt()
                canvas.drawRect(w * 0.08f, h * 0.50f, w * 0.45f, h * 0.70f, paint)
                canvas.drawRect(w * 0.10f, h * 0.42f, w * 0.43f, h * 0.52f, paint)
                // 茶几
                paint.color = 0xFF8D6E63.toInt()
                canvas.drawRect(w * 0.60f, h * 0.58f, w * 0.85f, h * 0.66f, paint)
            }
            "windowsill" -> {
                // 大窗 + 阳光
                paint.color = 0xFFFFFFFF.toInt()
                canvas.drawRect(w * 0.22f, h * 0.08f, w * 0.78f, h * 0.48f, paint)
                paint.color = 0xFF81D4FA.toInt()
                canvas.drawRect(w * 0.25f, h * 0.10f, w * 0.75f, h * 0.46f, paint)
                paint.color = 0xFFECEFF1.toInt()
                canvas.drawRect(w * 0.49f, h * 0.10f, w * 0.51f, h * 0.46f, paint)
                canvas.drawRect(w * 0.25f, h * 0.27f, w * 0.75f, h * 0.29f, paint)
                // 窗台
                paint.color = 0xFFA1887F.toInt()
                canvas.drawRect(w * 0.18f, h * 0.48f, w * 0.82f, h * 0.53f, paint)
            }
            else -> {
                // 卧室：床
                paint.color = 0xFF9FA8DA.toInt()
                canvas.drawRect(w * 0.55f, h * 0.50f, w * 0.95f, h * 0.70f, paint)
                paint.color = 0xFFE8EAF6.toInt()
                canvas.drawRect(w * 0.55f, h * 0.46f, w * 0.72f, h * 0.54f, paint)
                // 小窗
                paint.color = 0xFFB3E5FC.toInt()
                canvas.drawRect(w * 0.10f, h * 0.12f, w * 0.34f, h * 0.32f, paint)
                paint.color = 0xFF78909C.toInt()
                canvas.drawRect(w * 0.215f, h * 0.12f, w * 0.225f, h * 0.32f, paint)
            }
        }
    }

    private val PI = 3.14159265358979f

    /** 弧度→角度（Canvas.rotate 用角度制）。 */
    private fun deg(rad: Float): Float = rad * (180f / PI)

    // ================= 复古像素渲染模式（PIXEL_RETRO） =================
    //
    // 2026-08-30：与矢量模式共存的可切换渲染层，业务/状态机零改动。
    // 同一状态字段（mood/sleeping/pose/lifeStage）驱动，WristPet 式 drawRect 像素块。
    // 画布固定 96×96 虚拟格，整数倍放大锁颗粒（filterBitmap=false 语义，纯矩形无插值）。

    private val pxPaint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
        isDither = false
    }

    /** 像素宠物主体色板（24 色内，复古掌机感）。 */
    private val pxPalette = intArrayOf(
        0xFF66BB6A.toInt(), // 身体主绿
        0xFF43A047.toInt(), // 身体暗绿
        0xFFA5D6A7.toInt(), // 肚皮亮绿
        0xFFFFEB3B.toInt(), // 角/刺 黄
        0xFFFFA726.toInt(), // 翼/装饰 橙
        0xFF1A1A1A.toInt(), // 眼/线 黑
        0xFFFFFFFF.toInt(), // 眼白
        0xFFFF8A80.toInt(), // 腮红/心
        0xFF7986CB.toInt(), // 睡意紫（Zzz）
        0xFF9E9E9E.toInt()  // 病态灰
    )

    /** 像素状态推导：由业务状态字段映射 4 帧 + 附加帧。 */
    private fun pixelState(): String = when {
        !sleeping && (pose == Pose.SICK || mood == "SICK") -> "SICK"
        sleeping || pose == Pose.SLEEP -> "SLEEP"
        mood == "HUNGRY" -> "HUNGRY"
        mood == "SAD" || mood == "WEAK" || pose == Pose.WEAK -> "SAD"
        pose == Pose.PLAY || pose == Pose.HAPPY || mood == "HAPPY" -> "HAPPY"
        pose == Pose.ANNOY || mood == "ANNOYED" -> "ANNOY"
        else -> "IDLE"
    }

    // ==================== PIXEL_PNG 素材模式（design 附录A） ====================

    private val pngPaint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false   // 铁律：禁止双线性平滑，硬像素
        isDither = false
    }

    /** 按需解码缓存：只存展示过的物种帧，4MB 预算，禁全量预载。 */
    private val pngCache = object : LruCache<Int, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.allocationByteCount
    }

    /** 素材呼吸/眨眼节拍（附录A.3：600-800ms 交替，blink 借用矢量侧同一 blinkHold 窗口）。 */
    private var pngSwap = 0f

    private fun loadSpriteBitmap(resId: Int?): Bitmap? {
        if (resId == null) return null
        pngCache.get(resId)?.let { return it }
        return runCatching {
            val opts = BitmapFactory.Options().apply { inScaled = false } // 铁律：禁 DPI 缩放
            BitmapFactory.decodeResource(resources, resId, opts)?.also { bmp ->
                if (bmp.width != 96 || bmp.height != 96) {
                    Log.e("PetSpritePNG", "素材违反附录A契约: resId=$resId size=${bmp.width}x${bmp.height}")
                }
                pngCache.put(resId, bmp)
            }
        }.getOrNull()
    }

    /**
     * 三级回退链（附录A.3）：
     * 幼年(含EGG)/缺idle素材 → 整只程序化；
     * 精确状态PNG → 直接上屏；
     * sad/annoy/sick → idle 本体 + 头顶角标叠加；
     * 呼吸/眨眼帧仅狗/猫/兔样板物种，缺帧回退静态 idle。
     */
    private fun drawPngPet(canvas: Canvas, w: Float, h: Float) {
        // EGG 无 PNG 素材（蛋形态程序化像素）；CUB 及以上全生命周期直接渲染素材
        val res = if (lifeStage == "EGG") null else com.inklink.host.state.PetSpriteResMap.of(def.code)
        if (res == null) { drawPixelPet(canvas, w, h); return }

        val state = pixelState()
        val isIdleLike = state == "IDLE"
        // 呼吸样板：idle_a / idle_b 双帧齐全时按 0.7s 交替（0.7s 与程序化呼吸周期同拍）
        val breathRes = if (isIdleLike && !sleeping && res.idleA != null && res.idleB != null) {
            if (floor(pngSwap / 0.7f).toInt() % 2 == 0) res.idleA else res.idleB
        } else null
        val resId = when {
            state == "SLEEP" -> res.sleep ?: res.idle
            state == "HUNGRY" -> res.hungry ?: res.idle
            state == "HAPPY" -> res.happy ?: res.idle
            state == "SICK" || state == "SAD" || state == "ANNOY" -> res.idle  // 二级回退
            isIdleLike && res.blink != null && blinkHold > 0f -> res.blink     // 眨眼优先于呼吸
            breathRes != null -> breathRes
            else -> res.idle
        }
        val bmp = loadSpriteBitmap(resId) ?: run { drawPixelPet(canvas, w, h); return }

        drawRetroBackdrop(canvas, w, h)
        // 整数倍放大 + 余数居中（附录A.4 pixel-perfect）
        val s = max(1f, floor(min(w, h) / 96f))
        val left = (w - 96f * s) / 2f
        val top = (h - 96f * s) / 2f
        canvas.drawBitmap(bmp, null, RectF(left, top, left + 96f * s, top + 96f * s), pngPaint)

        // 三级角标叠加层（固定头顶槽位，非素材 offset 补丁）
        val px = s
        fun pRect(col: Int, row: Int, cols: Int, rows: Int, color: Int) {
            pxPaint.color = color
            canvas.drawRect(left + col * px, top + row * px, left + (col + cols) * px, top + (row + rows) * px, pxPaint)
        }
        when (state) {
            "SAD" -> { // 乌云：灰块 + 两滴雨
                pRect(60, 8, 14, 5, 0xFF90A4AE.toInt())
                pRect(63, 15, 2, 3, 0xFF81D4FA.toInt())
                pRect(70, 15, 2, 3, 0xFF81D4FA.toInt())
            }
            "ANNOY" -> { // 怒气十字
                pRect(67, 7, 2, 8, 0xFFE53935.toInt())
                pRect(64, 10, 8, 2, 0xFFE53935.toInt())
            }
            "SICK" -> { // 汗滴/病气
                pRect(69, 9, 3, 5, 0xFF81D4FA.toInt())
                pRect(70, 13, 1, 2, 0xFF81D4FA.toInt())
            }
        }
    }

    /** 复古掌机 LCD 深色背景 + 地面点线（PIXEL_RETRO / PIXEL_PNG 共用底衬）。 */
    private fun drawRetroBackdrop(canvas: Canvas, w: Float, h: Float) {
        pxPaint.color = 0xFF263238.toInt()
        canvas.drawRect(0f, 0f, w, h, pxPaint)
        pxPaint.color = 0xFF37474F.toInt()
        canvas.drawRect(0f, h * 0.62f, w, h, pxPaint)
        pxPaint.color = 0xFF4E5A62.toInt()
        val groundY = h * 0.68f
        val gw = w / 24f
        var gi = 0
        while (gi < 24) {
            canvas.drawRect(gi * gw, groundY, (gi + 1) * gw - gw / 3f, groundY + gw / 3f, pxPaint)
            gi++
        }
    }

    /** 复古像素渲染主入口：纯 drawRect 像素块，硬边缘无插值。 */
    private fun drawPixelPet(canvas: Canvas, w: Float, h: Float) {
        drawRetroBackdrop(canvas, w, h)

        // 96×96 虚拟格，整数倍放大
        val cell = max(3f, round(min(w, h) / 96f))
        val px = cell
        val originX = (w - 96f * px) / 2f
        val originY = (h - 96f * px) / 2f
        val bob = sin(breath * 1.2f) * px * 0.6f   // 呼吸上下浮动
        val state = pixelState()
        val asleep = state == "SLEEP"
        val sick = state == "SICK"
        val hungry = state == "HUNGRY"
        val sad = state == "SAD"
        val happy = state == "HAPPY"
        val annoy = state == "ANNOY"
        val isEgg = lifeStage == "EGG"

        // 阶段缩放（幼年小号）
        val stageScale = when (lifeStage) {
            "CUB" -> 0.62f; "JUVENILE" -> 0.8f; "ADOLESCENT" -> 0.9f; else -> 1.0f
        }
        val scale = stageScale
        val offX = originX + 48f * px * (1f - scale)
        val offY = originY + (86f - 70f * scale) * px

        // 主角颜色（体色取物种 LUT，PIXEL_RETRO 模式下 14 物种至少色彩可辨；造型统一，物种个性由 PIXEL_PNG 素材承载）
        val bodyColor = if (sick) pxPalette[9] else def.palette.body
        val bodyDark = if (sick) 0xFF757575.toInt() else def.palette.shade
        val bellyColor = def.palette.belly

        // 跳跳偏移（happy/play）
        val hopY = if (happy) (if (sin(breath * 6f) > 0.3f) -px * 1.5f else 0f) else 0f
        // 烦躁抖动
        val annoyX = if (annoy) if (sin(phase * 40f) > 0f) px * 0.6f else -px * 0.6f else 0f

        fun pxRect(col: Int, row: Int, cols: Int = 1, rows: Int = 1, color: Int, offsetY: Float = 0f) {
            pxPaint.color = color
            canvas.drawRect(
                offX + annoyX + col * px,
                offY + bob + hopY + offsetY + row * px,
                offX + annoyX + (col + cols) * px,
                offY + bob + hopY + offsetY + (row + rows) * px,
                pxPaint
            )
        }

        if (isEgg) {
            // ---- 像素蛋（带裂纹 + 高光 + 晃动） ----
            for (row in 0 until 10) {
                val startCol = when (row) {
                    0, 9 -> 44; 1, 8 -> 42; 2, 7 -> 40; else -> 38
                }
                val endCol = 96 - startCol - 20
                for (col in startCol until endCol) {
                    pxRect(col, row * 2 + 34, 2, 2, 0xFFECEFF1.toInt())
                }
            }
            pxRect(46, 44, 4, 4, 0xFFB0BEC5.toInt())
            pxRect(54, 52, 4, 4, 0xFFB0BEC5.toInt())
            pxRect(48, 56, 2, 4, pxPalette[5])
            pxRect(46, 58, 4, 2, pxPalette[5])
            pxRect(44, 38, 2, 2, 0xFFFFFFFF.toInt())
            pxRect(38, 30, 4, 4, pxPalette[3], offsetY = bob * 0.5f)
            return
        }

        // ---- 影子 ----
        pxRect(36, 84, 24, 3, 0x33000000, offsetY = 0f)

        // ---- 尾巴（身后） ----
        pxRect(62, 40, 10, 3, bodyColor, offsetY = 0f)
        pxRect(70, 38, 6, 4, bodyColor)
        pxRect(74, 36, 5, 3, pxPalette[4])

        // ---- 翅膀（身后，龙特征） ----
        pxRect(58, 26, 5, 6, pxPalette[4])
        pxRect(62, 24, 5, 5, pxPalette[4])

        // ---- 身体（圆润 10×10 块） ----
        for (row in 0 until 12) {
            val startCol = when (row) {
                0 -> 38; 1 -> 36; 2, 3 -> 34; else -> 32
            }
            val endCol = 96 - startCol - 20
            for (col in startCol until endCol) {
                pxRect(col, row * 2 + 44, 2, 2, bodyColor)
            }
        }
        // 肚皮亮块
        for (row in 0 until 6) {
            for (col in 0 until 5) {
                pxRect(41 + col * 2, 58 + row * 2, 2, 2, bellyColor)
            }
        }

        // ---- 头（叠于身体上方） ----
        for (row in 0 until 8) {
            val startCol = when (row) {
                0 -> 40; 1, 2 -> 38; else -> 36
            }
            val endCol = 96 - startCol - 20
            for (col in startCol until endCol) {
                pxRect(col, row * 2 + 20, 2, 2, bodyColor)
            }
        }
        // 角（黄）
        pxRect(38, 12, 4, 4, pxPalette[3])
        pxRect(54, 12, 4, 4, pxPalette[3])
        pxRect(40, 8, 3, 4, pxPalette[3])
        pxRect(53, 8, 3, 4, pxPalette[3])

        // ---- 眼睛 ----
        if (asleep) {
            // 横线闭眼
            pxRect(38, 34, 6, 2, pxPalette[5])
            pxRect(50, 34, 6, 2, pxPalette[5])
            // Zzz 像素字符（浮动）
            val zOff = sin(breath * 1.5f) * px
            pxRect(64, 22, 4, 2, pxPalette[7], offsetY = zOff * 0.6f)
            pxRect(68, 16, 3, 2, pxPalette[7], offsetY = zOff)
            pxRect(62, 12, 2, 2, pxPalette[7], offsetY = zOff * 1.3f)
        } else if (happy || annoy) {
            // 弯眼（^ ^）
            pxRect(38, 34, 2, 2, pxPalette[5])
            pxRect(36, 36, 2, 2, pxPalette[5])
            pxRect(52, 34, 2, 2, pxPalette[5])
            pxRect(54, 36, 2, 2, pxPalette[5])
        } else if (sick || sad) {
            // 半垂眼（沮丧）
            pxRect(38, 34, 6, 2, pxPalette[5])
            pxRect(50, 36, 6, 2, pxPalette[5])
            if (sick) pxRect(56, 24, 4, 4, pxPalette[4]) // 病气
        } else {
            // 正常黑点眼
            pxRect(38, 34, 4, 4, pxPalette[5])
            pxRect(50, 34, 4, 4, pxPalette[5])
            pxRect(40, 36, 2, 2, 0xFFFFFFFF.toInt()) // 高光
            pxRect(52, 36, 2, 2, 0xFFFFFFFF.toInt())
        }

        // ---- 嘴巴 ----
        if (hungry || sad) {
            pxRect(46, 46, 4, 2, pxPalette[5])          // 平嘴
            pxRect(44, 48, 2, 2, pxPalette[5])
            pxRect(50, 48, 2, 2, pxPalette[5])          // 嘴角下弯（难过）
        } else if (happy) {
            pxRect(44, 46, 8, 2, pxPalette[5])          // 微笑
            pxRect(42, 48, 4, 2, pxPalette[5])
            pxRect(50, 48, 4, 2, pxPalette[5])
        } else if (asleep) {
            pxRect(46, 46, 4, 2, pxPalette[5])          // 小 o
        } else if (annoy) {
            pxRect(44, 48, 8, 2, pxPalette[5])          // 撇嘴
        } else {
            pxRect(46, 46, 4, 2, pxPalette[5])          // 平静
        }

        // ---- 腮红 ----
        if (happy) {
            pxRect(32, 40, 4, 2, pxPalette[7])
            pxRect(60, 40, 4, 2, pxPalette[7])
        } else if (hungry || sad || sick) {
            pxRect(32, 42, 3, 2, 0xFF90A4AE.toInt())
            pxRect(61, 42, 3, 2, 0xFF90A4AE.toInt())
        }

        // ---- 手臂（情绪摆动） ----
        val armRaise = if (happy || annoy) -px * 2f else 0f
        pxRect(30, 56, 4, 6, bodyColor, offsetY = armRaise * 0.4f)
        pxRect(62, 56, 4, 6, bodyColor, offsetY = armRaise * 0.4f)

        // ---- 脚 ----
        pxRect(38, 74, 8, 4, bodyDark)
        pxRect(50, 74, 8, 4, bodyDark)

        // ---- 头顶状态符号（饿/脏/病，像素） ----
        if (hungry) pxRect(64, 8, 6, 6, pxPalette[3])
        if (sick) pxRect(64, 8, 6, 6, pxPalette[9])
    }

    // ==================== PIXEL_ARCADE_SPRITE 街机素材模式（dongwu 图集） ====================

    /** 每状态 3 帧动画：帧时长 400ms，01→02→03→01 循环（README 约定，2026-08-31 由 200ms 调慢）。 */
    private val arcadeCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    private fun loadArcadeBitmap(code: String, state: String, frame: Int): Bitmap? {
        // 素材文件命名两位数帧号（如 cat_idle_01.png），与 PetArcadeAssetContractTest 闭集严格一致
        val path = "pixel_arcade/$code/${code}_${state}_${frame.toString().padStart(2, '0')}.png"
        arcadeCache.get(path)?.let { return it }
        return runCatching {
            context.assets.open(path).use { ins ->
                val opts = BitmapFactory.Options().apply { inScaled = false }
                BitmapFactory.decodeStream(ins, null, opts)?.also { bmp ->
                    if (bmp.width != 256 || bmp.height != 256) {
                        Log.e("PetSpriteArcade", "素材违反256契约: $path size=${bmp.width}x${bmp.height}")
                    }
                    arcadeCache.put(path, bmp)
                }
            }
        }.getOrNull()
    }

    /**
     * 街机素材渲染：精确状态3帧循环 → idle 回退（sad/annoy/sick）→ 整只回退 PIXEL_PNG。
     * 物种无素材 / EGG（蛋形态程序化）→ 直接回退。素材为完整 256×256 角色，
     * CUB 及以上全生命周期直接渲染，不做幼年缩小（区别于 96×96 程序化像素的阶段缩放）。
     * 256×256 整数倍放大居中，硬边无插值。
     */
    private fun drawArcadePet(canvas: Canvas, w: Float, h: Float) {
        val code = def.code
        if (lifeStage == "EGG" || code !in com.inklink.host.state.PetArcadeMap.SUPPORTED) {
            drawPngPet(canvas, w, h)
            return
        }
        val state = pixelState()
        val fileState = com.inklink.host.state.PetArcadeMap.STATE_FILE[state]
            ?: com.inklink.host.state.PetArcadeMap.FALLBACK_STATE[state]
            ?: "idle"
        // 3 帧循环：400ms/帧，pngSwap 累积节拍（复用既有更新节拍，不在本模式额外计时）
        val frame = (floor(pngSwap / 0.4f).toInt() % com.inklink.host.state.PetArcadeMap.FRAMES_PER_STATE) + 1
        val bmp = loadArcadeBitmap(code, fileState, frame) ?: run { drawPngPet(canvas, w, h); return }

        drawRetroBackdrop(canvas, w, h)
        // 256×256 整数倍放大 + 余数居中（与 PIXEL_PNG 的 A.4 同规则）
        val s = max(1f, floor(min(w, h) / 256f))
        val left = (w - 256f * s) / 2f
        val top = (h - 256f * s) / 2f
        canvas.drawBitmap(bmp, null, RectF(left, top, left + 256f * s, top + 256f * s), pngPaint)
    }

    // ==================== PIXEL_SCENE 云朵 LCD 场景宠模式（附录C） ====================

    /** 250×250 场景底图按需解码缓存（3 张场景 × ~250KB，硬边无插值）。 */
    private val sceneCache = object : LruCache<Int, Bitmap>(3 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.allocationByteCount
    }

    private fun loadSceneBitmap(resId: Int): Bitmap? {
        sceneCache.get(resId)?.let { return it }
        return runCatching {
            val opts = BitmapFactory.Options().apply { inScaled = false }
            BitmapFactory.decodeResource(resources, resId, opts)?.also { bmp ->
                if (bmp.width != 250 || bmp.height != 250) {
                    Log.e("PetSpriteScene", "场景违反250契约: resId=$resId size=${bmp.width}x${bmp.height}")
                }
                sceneCache.put(resId, bmp)
            }
        }.getOrNull()
    }

    /** 云朵状态选择：业务状态（pose/mood/sleeping）→ 附录C 状态键（CLOUD 闭集）。 */
    private fun cloudStateKey(): String = when {
        sleeping || pose == Pose.SLEEP -> "SLEEP"
        pose == Pose.EATING -> "EAT"
        pose == Pose.STUDY -> "READ"
        pose == Pose.CLEAN -> "CLEAN"
        pose == Pose.ANNOY || mood == "ANNOYED" -> "ANNOY"
        pose == Pose.PLAY || pose == Pose.HAPPY || mood == "HAPPY" -> "HAPPY"
        mood == "HUNGRY" -> "HUNGRY"
        mood == "SAD" || mood == "WEAK" || pose == Pose.WEAK -> "SAD"
        mood == "SICK" || pose == Pose.SICK -> "SICK"
        else -> "IDLE"
    }

    /** 云朵状态每帧时长（秒）；多帧状态循环，单帧状态静态停留。 */
    private fun cloudFrameInterval(state: String): Float = when (state) {
        "IDLE" -> 0.7f
        "SLEEP" -> 0.6f
        "ANNOY" -> 0.35f
        "HUNGRY", "SAD", "SICK" -> 1f
        else -> 0.4f
    }

    /**
     * 场景宠渲染：250×250 场景底图 + 程序道具（pre/post 分层）+ 96×96 云朵角色帧。
     * 角色帧固定锚点（水平居中、静息脚底对齐地面线），动作帧的位移由素材自带（不补 offset）。
     * 缺场景/缺角色帧 → 整只回退 [drawPngPet]。
     */
    private fun drawScenePet(canvas: Canvas, w: Float, h: Float) {
        // EGG 无云朵场景角色（蛋形态回退 PNG/程序化）
        if (lifeStage == "EGG") { drawPngPet(canvas, w, h); return }

        val sceneMap = com.inklink.host.state.PetSceneMap
        val sceneRes = sceneMap.SCENES[sceneId] ?: sceneMap.SCENES["bedroom"]!!
        val scene = loadSceneBitmap(sceneRes)
        if (scene == null) { drawPngPet(canvas, w, h); return }

        val s = max(1f, floor(min(w, h) / sceneMap.SCENE_SIZE.toFloat()))
        val left = (w - sceneMap.SCENE_SIZE * s) / 2f
        val top = (h - sceneMap.SCENE_SIZE * s) / 2f
        canvas.drawBitmap(scene, null, RectF(left, top, left + sceneMap.SCENE_SIZE * s, top + sceneMap.SCENE_SIZE * s), pngPaint)

        canvas.save()
        canvas.translate(left, top)
        canvas.scale(s, s)

        val state = cloudStateKey()
        val frames = sceneMap.CLOUD[state] ?: sceneMap.CLOUD["IDLE"]!!
        val perFrame = cloudFrameInterval(state)
        val frameIdx = (floor(pngSwap / perFrame).toInt() % frames.size)
        val frameNo = frameIdx + 1

        // 睡眠垫子（pre：在角色身后）
        if (state == "SLEEP") drawSceneCushion(canvas)

        // 角色帧：静息脚底对齐地面线（FLOOR_Y），动作帧自带位移
        var resId = frames[frameIdx]
        if (state == "IDLE" && !sleeping && blinkHold > 0f) {
            resId = sceneMap.CLOUD["BLINK"]!![0]
        }
        val bmp = loadSpriteBitmap(resId)
        if (bmp != null) {
            val originY = sceneMap.FLOOR_Y - sceneMap.FEET_ROW
            canvas.drawBitmap(
                bmp, null,
                RectF(
                    sceneMap.PET_X.toFloat(), originY.toFloat(),
                    (sceneMap.PET_X + sceneMap.FRAME_SIZE).toFloat(), sceneMap.FLOOR_Y.toFloat()
                ),
                pngPaint
            )
        }

        // 道具（post：在角色身前）
        when (state) {
            "EAT" -> drawSceneBowl(canvas)
            "HAPPY" -> drawSceneBall(canvas)
            "ANNOY" -> drawSceneAngry(canvas, frameNo)
            "READ" -> drawSceneBook(canvas)
            "CLEAN" -> {
                drawSceneBubbles(canvas, frameNo)
                drawSceneBucket(canvas)
            }
        }

        canvas.restore()
    }

    // ---- 场景程序道具（250 坐标，硬边像素风，色彩与 tools/pet_blob/make_actions250.py 一致） ----

    /** 饭碗（eat，post）：暗外碗 + 内壁 + 食物 + 饭粒。 */
    private fun drawSceneBowl(canvas: Canvas) {
        val cx = 136f; val cy = 238f
        fillOval(canvas, cx, cy + 4f, 14f, 8f, 0xFF455563.toInt())
        fillOval(canvas, cx, cy - 1f, 11f, 5f, 0xFF78909C.toInt())
        fillOval(canvas, cx, cy - 4f, 8f, 4f, 0xFFFFB74D.toInt())
        for (i in intArrayOf(-4, 0, 4)) fillCircle(canvas, cx + i, cy - 4f, 2f, 0xFFFFE8C8.toInt())
    }

    /** 睡眠垫（sleep，pre）：蓝垫 + 白芯。 */
    private fun drawSceneCushion(canvas: Canvas) {
        fillOval(canvas, 131f, 232f, 35f, 10f, 0xFF7986CB.toInt())
        fillOval(canvas, 131f, 226f, 27f, 6f, 0xFFFDF4F7.toInt())
    }

    /** 玩具球（happy，post）：青球 + 高光弧。 */
    private fun drawSceneBall(canvas: Canvas) {
        fillCircle(canvas, 96f, 236f, 11f, 0xFF4FC3F7.toInt())
        fillCircle(canvas, 96f, 236f, 8f, 0xFF81D4FA.toInt())
        paint.color = 0xFFFDF4F7.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
        rect.set(88f, 228f, 104f, 244f)
        canvas.drawArc(rect, 40f, 180f, false, paint)
        canvas.drawArc(rect, 280f, 80f, false, paint)
        paint.style = Paint.Style.FILL
    }

    /** 故事书（read，post）：白书页 + 中缝 + 文字线。 */
    private fun drawSceneBook(canvas: Canvas) {
        val x0 = 98f; val y = 232f; val x1 = 156f
        fillRect(canvas, x0, y, x1 - x0, 16f, 0xFFFDF4F7.toInt())
        paint.color = 0xFF455563.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f
        rect.set(x0, y, x1, y + 16f); canvas.drawRect(rect, paint)
        canvas.drawLine(127f, y, 127f, y + 16f, paint)
        paint.color = 0xFF78909C.toInt()
        var x = x0 + 3f
        while (x <= 121f) { canvas.drawLine(x, y + 4f, x, y + 11f, paint); x += 10f }
        canvas.drawLine(130f, y + 4f, 137f, y + 4f, paint)
        paint.style = Paint.Style.FILL
    }

    /** 清洁泡泡（clean，post）：第 2 帧整体错位，模拟漂动。 */
    private fun drawSceneBubbles(canvas: Canvas, frameNo: Int) {
        val off = if (frameNo == 2) 3f else 0f
        val pts = arrayOf(132 to 128, 186 to 160, 84 to 182, 192 to 200, 168 to 112, 108 to 156)
        for ((i, p) in pts.withIndex()) {
            val r = (4 + (i % 4)).toFloat()
            val bx = p.first + (i % 2) * off
            val by = p.second + (i % 3) * off
            fillCircle(canvas, bx, by, r, 0xFF81D4FA.toInt())
            fillRect(canvas, bx - 1f, by - 2f, 1f, 1f, 0xFFFDF4F7.toInt())
        }
    }

    /** 清洁水桶（clean，post）：青桶 + 暗箍 + 提手。 */
    private fun drawSceneBucket(canvas: Canvas) {
        val ox = 206f; val oy = 230f
        fillRect(canvas, ox - 7f, oy, 14f, 16f, 0xFF4FC3F7.toInt())
        fillRect(canvas, ox - 7f, oy, 14f, 3f, 0xFF455563.toInt())
        paint.color = 0xFF455563.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
        rect.set(ox - 10f, oy - 8f, ox + 10f, oy + 6f)
        canvas.drawArc(rect, 180f, 180f, false, paint)
        paint.style = Paint.Style.FILL
    }

    /** 怒气十字（annoy，post）：第 2 帧右移 4px。 */
    private fun drawSceneAngry(canvas: Canvas, frameNo: Int) {
        val x = 184f + (if (frameNo == 2) 4f else 0f)
        val y = 118f
        paint.color = 0xFFFF8A80.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
        for (i in 0 until 3) {
            val yy = y + i * 6f
            canvas.drawLine(x, yy, x + 8f, yy + 3f, paint)
            canvas.drawLine(x + 8f, yy, x, yy + 3f, paint)
        }
        paint.style = Paint.Style.FILL
    }
}

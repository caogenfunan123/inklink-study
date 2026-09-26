package com.inklink.host.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.inklink.host.R
import com.google.android.material.snackbar.Snackbar

/**
 * 像素气泡提示（V1.1 裁决：废弃系统原生 Toast，全面自定义像素气泡）。
 *
 * 落地形态：Snackbar 挂到 Activity root，配像素圆角底板 bg_pixel_bubble + 硬边描边色；
 * 不新增窗口、不泄漏、自动队列合并，行为与 Toast 一一对应，替换成本最低。
 * API 保持与 Toast 使用习惯一致：show(msg[, length])。
 */
object PixelToast {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var last: Snackbar? = null

    /**
     * 显示像素气泡。
     * @param anchor 任意视图（取 root 作为 Snackbar 宿主）；Activity 直接传 contentView。
     *        找不到宿主时静默丢弃（对齐 Toast 尽力而为语义，绝不让提示崩主流程）。
     */
    fun show(anchor: Any?, msg: String, long: Boolean = false) {
        if (msg.isBlank()) return
        val root = resolveRoot(anchor) ?: return
        mainHandler.post {
            val snackbar = Snackbar.make(root, msg, if (long) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT)
            val sbView = snackbar.view
            sbView.background = sbView.context.getDrawable(R.drawable.bg_pixel_bubble)
            sbView.setPadding(28, 22, 28, 22)
            (sbView.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.setMargins(48, 0, 48, 160)
            }
            val tv = sbView.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
            tv.setTextColor(0xFF4E342E.toInt())
            tv.textSize = 14f
            tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            tv.maxLines = 3
            last?.dismiss()
            last = snackbar
            runCatching { snackbar.show() }
        }
    }

    fun showLong(anchor: Any?, msg: String) = show(anchor, msg, long = true)

    private fun resolveRoot(anchor: Any?): ViewGroup? = when (anchor) {
        is android.app.Activity -> anchor.findViewById<ViewGroup>(android.R.id.content)
        is ViewGroup -> anchor
        is View -> anchor.rootView as? ViewGroup
        else -> null
    }
}

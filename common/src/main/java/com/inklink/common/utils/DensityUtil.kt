package com.inklink.common.utils

import android.content.Context
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.WindowManager

/**
 * 屏幕分辨率适配工具。
 *
 * 统一 dp/px 互转与屏幕尺寸获取；提供低分辨率检测供投屏图片降采样。
 */
object DensityUtil {

    fun dp2px(context: Context, dp: Float): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }

    fun px2dp(context: Context, px: Float): Int {
        val density = context.resources.displayMetrics.density
        return (px / density + 0.5f).toInt()
    }

    fun sp2px(context: Context, sp: Float): Int {
        val scaledDensity = context.resources.displayMetrics.scaledDensity
        return (sp * scaledDensity + 0.5f).toInt()
    }

    fun getScreenWidth(context: Context): Int {
        val metrics = getDisplayMetrics(context)
        return metrics.widthPixels
    }

    fun getScreenHeight(context: Context): Int {
        val metrics = getDisplayMetrics(context)
        return metrics.heightPixels
    }

    /**
     * 低分辨率检测：最小边小于 480dp 视为小屏（手表/低端设备），
     * 投屏图片据此降采样以减少内存占用。
     */
    fun isLowResolution(context: Context): Boolean {
        val metrics = getDisplayMetrics(context)
        val minDp = px2dp(context, minOf(metrics.widthPixels, metrics.heightPixels).toFloat())
        return minDp < 480
    }

    /** 是否为圆形屏幕（部分安卓手表）。 */
    fun isRoundScreen(context: Context): Boolean =
        context.resources.configuration.isScreenRound

    private fun getDisplayMetrics(context: Context): DisplayMetrics {
        val metrics = DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        wm?.defaultDisplay?.getRealMetrics(metrics)
        if (metrics.widthPixels == 0 || metrics.heightPixels == 0) {
            context.resources.displayMetrics.let {
                metrics.setTo(it)
            }
        }
        return metrics
    }

    /** 备用：基于 TypedValue 的 dp 转 px（不依赖 WindowManager）。 */
    fun dp2pxCompat(context: Context, dp: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        ).toInt()
}

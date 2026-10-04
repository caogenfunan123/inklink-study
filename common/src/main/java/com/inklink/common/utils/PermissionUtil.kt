package com.inklink.common.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Android 6.0+ 动态权限统一封装。
 *
 * 权限缺失时对应功能模块自动降级禁用，而非崩溃。拒绝策略由调用方通过
 * [PermissionUtil.onRequestPermissionsResult] 分发结果后自行处理。
 */
object PermissionUtil {

    // 定位权限（GPS 模块必需）
    const val LOCATION = Manifest.permission.ACCESS_FINE_LOCATION
    const val LOCATION_COARSE = Manifest.permission.ACCESS_COARSE_LOCATION

    /** 定位权限组合（申请时 FINE + COARSE 一起要，任一授予即可用）。 */
    val LOCATION_PERMS = arrayOf(LOCATION, LOCATION_COARSE)

    // 录音权限（语音对讲必需）
    const val RECORD_AUDIO = Manifest.permission.RECORD_AUDIO

    // 相机权限（主控端扫码连接必需）
    const val CAMERA = Manifest.permission.CAMERA

    // 存储权限（日志、图片缓存，Android 10+ 建议改用分区存储）
    const val WRITE_STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE

    /** 检查是否已授予全部权限。 */
    fun hasPermissions(context: Context, vararg permissions: String): Boolean =
        permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /** 定位权限是否已授予（精细或粗略任一即可）。 */
    fun hasLocation(context: Context): Boolean =
        hasPermissions(context, LOCATION) || hasPermissions(context, LOCATION_COARSE)

    /** 是否存在任一未被授予的权限（用于首启弹窗引导）。 */
    fun needsRequest(context: Context, vararg permissions: String): Boolean =
        permissions.any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    /** 发起权限申请。结果通过 [onRequestPermissionsResult] 分发。 */
    fun request(activity: Activity, requestCode: Int, vararg permissions: String) {
        ActivityCompat.requestPermissions(activity, permissions, requestCode)
    }

    /**
     * 在 Activity#onRequestPermissionsResult 中调用，返回逐权限的授予结果。
     *
     * @return Map<权限名, 是否已授予>，包含所有被申请权限的最终状态。
     */
    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Map<String, Boolean> {
        return permissions.mapIndexed { index, perm ->
            val granted = index < grantResults.size &&
                grantResults[index] == PackageManager.PERMISSION_GRANTED
            perm to granted
        }.toMap()
    }
}

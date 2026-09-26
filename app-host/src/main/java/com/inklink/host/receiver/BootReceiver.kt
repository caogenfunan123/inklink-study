package com.inklink.host.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.inklink.host.service.InkForegroundService

/**
 * 开机广播接收器：开机后自启前台服务，实现后台常驻。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, InkForegroundService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}

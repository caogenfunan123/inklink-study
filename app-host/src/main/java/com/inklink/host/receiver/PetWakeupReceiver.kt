package com.inklink.host.receiver

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.inklink.host.R
import com.inklink.host.state.PetStateManager
import com.inklink.host.ui.PetMainActivity

/**
 * 宠物状态单次低频促活唤醒广播接收器 (AlarmManager.setAndAllowWhileIdle)
 */
class PetWakeupReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val petManager = PetStateManager(context)
        val pet = petManager.recalculateState()

        if (pet.hunger < 30 || pet.happiness < 30) {
            showHungerNotification(context, pet.hunger, pet.happiness)
        }

        // 仅在必要时单次调度下一次不精确检查 (4 小时后)
        scheduleNextCheck(context)
    }

    private fun showHungerNotification(context: Context, hunger: Int, happiness: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val openIntent = Intent(context, PetMainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context,
            3001,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, "inklink_foreground")
            .setSmallIcon(R.drawable.ic_stat_pet)
            .setContentTitle("🐾 宠物饿了或无聊啦")
            .setContentText("饱食度: $hunger% | 心情值: $happiness%，快来陪它玩吧！")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        nm.notify(3002, notification)
    }

    companion object {
        fun scheduleNextCheck(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, PetWakeupReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context,
                3003,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = System.currentTimeMillis() + 4 * 3600_000L // 4小时后单次触发
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }
    }
}

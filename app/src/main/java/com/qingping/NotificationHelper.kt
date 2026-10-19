package com.qingping

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * 开机/升级后兜底提醒：
 * 无障碍服务由用户在系统设置中启用后，系统会在开机完成后自动重新绑定；
 * 但部分 ROM 的激进清理可能让服务长期离线。此时提醒用户手动恢复——
 * 应用自己无法重新授予无障碍权限，这是系统的权限设计边界。
 */
object NotificationHelper {
    private const val CHANNEL_ID = "qingping_alerts"
    private const val NOTIF_ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "服务状态提醒", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    /** 读取系统设置中本服务是否处于启用状态 */
    fun isServiceEnabledInSettings(context: Context): Boolean {
        val setting = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return setting.lowercase().contains((context.packageName + "/").lowercase())
    }

    fun notifyServiceDown(context: Context) {
        ensureChannel(context)
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("清屏服务未在运行")
            .setContentText("点按前往无障碍设置，重新启用「清屏 · 广告拦截引擎」。")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, notification)
        } catch (_: SecurityException) {
            // 未授予通知权限时静默失败
        }
    }
}

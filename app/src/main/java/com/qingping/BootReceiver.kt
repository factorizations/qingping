package com.qingping

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启兜底（0.3）。
 *
 * 原理说明：无障碍服务一旦在系统设置中启用，由 system_server 绑定，
 * 开机完成后系统会自动重新绑定本服务（进程随之拉起），不需要也无法由应用
 * 主动启动后台服务。本接收器只做一件事：当系统设置显示服务应当启用、
 * 但实际未绑定时，发一条通知提醒用户，并顺带冲刷统计落盘。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        AppPrefs.init(context)
        StatsStore.init(context)
        StatsStore.flush()
        val enabledInSettings = NotificationHelper.isServiceEnabledInSettings(context)
        if (AppPrefs.serviceEverEnabled && !AdAccessibilityService.running && enabledInSettings) {
            // 设置显示已启用但服务尚未绑定：开机早期常见，等待 30 秒后再检查一次
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!AdAccessibilityService.running) {
                    NotificationHelper.notifyServiceDown(context)
                }
            }, 30_000L)
        }
    }
}

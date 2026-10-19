package com.qingping

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主界面（0.3）：状态卡、规则开关、按应用统计、事件摘要。
 * 延续 0.2 的纯代码 UI（零布局 XML 依赖），并新增规则开关与名单入口。
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var overview: TextView
    private lateinit var stats: TextView
    private lateinit var recent: TextView
    private lateinit var swSkip: Switch
    private lateinit var swClose: Switch
    private lateinit var swJump: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPrefs.init(this)
        StatsStore.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 24)
        }
        root.addView(TextView(this).apply { text = "清屏 0.3"; textSize = 30f })
        root.addView(TextView(this).apply {
            text = "开屏跳过 · 广告页关闭 · 误触跳转返回 · 本地统计"
            textSize = 13f; setPadding(0, 6, 0, 18)
        })

        status = TextView(this).apply { textSize = 15f }
        root.addView(status)
        root.addView(button("打开无障碍设置") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        overview = TextView(this).apply {
            textSize = 22f; setPadding(0, 18, 0, 0)
        }
        root.addView(overview)

        root.addView(TextView(this).apply {
            text = "拦截规则"; textSize = 16f; setPadding(0, 20, 0, 6)
        })
        swSkip = switch("开屏广告 · 自动跳过", AppPrefs.splashSkipEnabled) {
            AppPrefs.splashSkipEnabled = it
        }
        swClose = switch("广告 SDK 全屏页 · 自动关闭", AppPrefs.adActivityEnabled) {
            AppPrefs.adActivityEnabled = it
        }
        swJump = switch("误触跳转 · 自动返回（名单制）", AppPrefs.autoBackEnabled) {
            AppPrefs.autoBackEnabled = it
        }
        root.addView(swSkip)
        root.addView(swClose)
        root.addView(swJump)
        root.addView(button("管理跳转自动返回名单") {
            startActivity(Intent(this@MainActivity, AppPickerActivity::class.java))
        })

        root.addView(TextView(this).apply {
            text = "按 APP 统计"; textSize = 16f; setPadding(0, 20, 0, 6)
        })
        root.addView(button("清空统计") { confirmClear() })
        root.addView(button("导出统计（复制到剪贴板）") { exportStats() })
        stats = TextView(this).apply { textSize = 14f; setPadding(0, 6, 0, 0) }
        root.addView(stats)

        recent = TextView(this).apply { textSize = 12f; setPadding(0, 14, 0, 0) }
        root.addView(recent)

        root.addView(button("运行保障与限制说明") { showGuidance() })
        root.addView(TextView(this).apply {
            text = "统计口径：记录的是拦截动作已执行，不代表广告必然被关闭。所有数据仅保存在本机。"
            textSize = 12f; setPadding(0, 18, 0, 0)
        })

        setContentView(root)
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val enabled = NotificationHelper.isServiceEnabledInSettings(this)
        val running = AdAccessibilityService.running
        status.text = when {
            running -> "拦截引擎：运行中"
            enabled -> "无障碍已启用，等待系统绑定（可稍后回到本页确认）"
            else -> "未启用。请在系统设置中开启「清屏 · 广告拦截引擎」"
        }
        overview.text = "累计拦截 " + StatsStore.totalAll() + " 次 · 今日 " + StatsStore.totalToday() + " 次"

        val pm = packageManager
        val sb = StringBuilder()
        val rows = StatsStore.snapshot()
        if (rows.isEmpty()) {
            sb.append("暂无拦截记录\n")
        }
        for (s in rows) {
            val name = try {
                pm.getApplicationLabel(pm.getApplicationInfo(s.pkg, 0)).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                s.pkg
            }
            sb.append(name).append("  ").append(s.total).append(" 次（今日 ").append(s.today).append("）\n")
            sb.append("  ").append(s.pkg)
            if (s.lastFrom.isNotBlank()) sb.append("\n  最近来源: ").append(s.lastFrom)
            sb.append("\n")
        }
        stats.text = sb.toString()

        recent.text = "规则分布示例（按应用）:\n" + rows.take(5).joinToString("\n") { s ->
            "  " + s.pkg + " -> " + s.rules.entries.joinToString(", ") { (k, v) -> ruleName(k) + "×" + v }
        }
    }

    private fun ruleName(id: String): String = when (id) {
        "skip" -> "开屏跳过"
        "close" -> "广告页关闭"
        "jump" -> "跳转返回"
        else -> id
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("清空统计")
            .setMessage("将删除所有本地拦截记录，确定吗？")
            .setPositiveButton("清空") { _, _ ->
                StatsStore.clear()
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportStats() {
        val rows = StatsStore.snapshot()
        val text = buildString {
            append("清屏拦截统计 ").append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())).append("\n")
            append("累计 ").append(StatsStore.totalAll()).append(" · 今日 ").append(StatsStore.totalToday()).append("\n\n")
            for (s in rows) {
                append(s.pkg).append(" total=").append(s.total).append(" today=").append(s.today)
                    .append(" rules=").append(s.rules).append("\n")
            }
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("QingPing stats", text))
        Toast.makeText(this, "统计已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun showGuidance() {
        AlertDialog.Builder(this)
            .setTitle("运行保障与限制")
            .setMessage(
                "【为什么需要无障碍权限】\n" +
                    "清屏通过无障碍服务读取屏幕控件并执行模拟点击，这是无 Root 环境下拦截开屏广告的唯一可行方案。\n\n" +
                    "【开机自动运行】\n" +
                    "服务在系统设置中启用后由系统（system_server）绑定，开机后自动恢复，无需应用自启。若被 ROM 清理，开机时会尝试发通知提醒。\n\n" +
                    "【小米/澎湃 OS 建议】\n" +
                    "1. 设置 > 应用管理 > 清屏 > 省电策略：无限制\n" +
                    "2. 自启动权限：允许（用于开机提醒）\n" +
                    "3. 最近任务中锁定清屏卡片\n" +
                    "4. 神隐模式/省电优化中对清屏关闭限制\n\n" +
                    "【能力边界】\n" +
                    "1. 纯图片/自绘广告（无可访问性节点）无法识别\n" +
                    "2. 信息流/横幅等非全屏广告不做处理，避免误点\n" +
                    "3. 点击成功不等于广告必然关闭；统计记录的是动作执行\n" +
                    "4. 无 Root 无法阻止广告加载，只能识别后自动关闭\n" +
                    "5. 关闭系统通知权限不影响拦截，只影响开机提醒"
            )
            .setPositiveButton("打开应用信息") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("知道了", null)
            .show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        isChecked = checked
        setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        gravity = Gravity.START
    }
}

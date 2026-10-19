
package com.qingping

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主界面（0.5 重设计，0.6 沿用）：Material 风格三卡布局。
 * 1) 状态卡：服务运行状态大标识 + 一键授权
 * 2) 统计卡：累计/今日大数字 + 按应用排行（带图标）
 * 3) 规则卡：三条开关 + 名单入口
 * 参考业界同类（GKD/李跳跳）：首屏突出权限状态，其次统计。
 */
class MainActivity : Activity() {

    private lateinit var statusCard: LinearLayout
    private lateinit var statusDot: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var statusBtn: TextView
    private lateinit var statTotal: TextView
    private lateinit var statToday: TextView
    private lateinit var appList: LinearLayout
    private lateinit var emptyHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPrefs.init(this)
        StatsStore.init(this)

        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.SURFACE) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 16f), Ui.dp(this@MainActivity, 20f), Ui.dp(this@MainActivity, 16f), Ui.dp(this@MainActivity, 24f))
        }
        scroll.addView(root)

        // ===== 标题栏 =====
        val title = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_app)
            layoutParams = LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 30f), Ui.dp(this@MainActivity, 30f))
        })
        title.addView(Ui.text(this, "  清屏", 22f, Ui.ON_SURFACE, bold = true))
        title.addView(Ui.text(this, "  v0.7", 12f, Ui.ON_SURFACE_VAR).apply { gravity = Gravity.BOTTOM })
        root.addView(title)
        root.addView(Ui.text(this, "自动跳过开屏广告 · 拦截误触跳转 · 数据仅存本机", 12f, Ui.ON_SURFACE_VAR).apply {
            setPadding(0, Ui.dp(this@MainActivity, 4f), 0, Ui.dp(this@MainActivity, 14f))
        })

        // ===== 状态卡 =====
        statusCard = Ui.card(this)
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = TextView(this).apply {
            textSize = 26f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 44f), Ui.dp(this@MainActivity, 44f))
        }
        statusRow.addView(statusDot)
        val statusTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(Ui.dp(this@MainActivity, 14f), 0, 0, 0)
        }
        statusTitle = Ui.text(this, "", 16f, Ui.ON_SURFACE, bold = true)
        statusSub = Ui.text(this, "", 12f, Ui.ON_SURFACE_VAR)
        statusTexts.addView(statusTitle)
        statusTexts.addView(statusSub)
        statusRow.addView(statusTexts)
        statusCard.addView(statusRow)
        statusBtn = Ui.button(this, "") {}
        statusCard.addView(statusBtn)
        root.addView(statusCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = Ui.dp(this@MainActivity, 14f) })

        // ===== 统计卡 =====
        val statCard = Ui.card(this)
        statCard.addView(Ui.text(this, "拦截统计", 16f, Ui.ON_SURFACE, bold = true))
        val nums = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(this@MainActivity, 12f), 0, Ui.dp(this@MainActivity, 6f))
        }
        statTotal = bigNum("0")
        statToday = bigNum("0")
        nums.addView(statColumn(statTotal, "累计拦截"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nums.addView(vline())
        nums.addView(statColumn(statToday, "今日拦截"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statCard.addView(nums)
        emptyHint = Ui.text(this, "还没有拦截记录 — 打开一个带开屏广告的 App 试试", 12f, Ui.ON_SURFACE_VAR)
        emptyHint.setPadding(0, Ui.dp(this@MainActivity, 8f), 0, 0)
        statCard.addView(emptyHint)
        appList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statCard.addView(appList)
        val exportRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        exportRow.addView(Ui.button(this, "导出统计") { exportStats() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.rightMargin = Ui.dp(this@MainActivity, 6f) })
        exportRow.addView(Ui.button(this, "清空") { confirmClear() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.leftMargin = Ui.dp(this@MainActivity, 6f) })
        statCard.addView(exportRow)
        root.addView(statCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = Ui.dp(this@MainActivity, 14f) })

        // ===== 规则卡 =====
        val ruleCard = Ui.card(this)
        ruleCard.addView(Ui.text(this, "拦截规则", 16f, Ui.ON_SURFACE, bold = true))
        ruleCard.addView(Ui.switchRow(this, "开屏广告自动跳过", "识别到跳过按钮立即点击", AppPrefs.splashSkipEnabled) { AppPrefs.splashSkipEnabled = it })
        ruleCard.addView(divider())
        ruleCard.addView(Ui.switchRow(this, "广告 SDK 全屏页自动关闭", "穿山甲 / 优量汇 / AdMob 等约 20 家", AppPrefs.adActivityEnabled) { AppPrefs.adActivityEnabled = it })
        ruleCard.addView(divider())
        ruleCard.addView(Ui.switchRow(this, "误触跳转自动返回", "被广告拉到名单应用时自动返回（默认关）", AppPrefs.autoBackEnabled) { AppPrefs.autoBackEnabled = it })
        ruleCard.addView(Ui.button(this, "管理跳转返回名单") { startActivity(Intent(this, AppPickerActivity::class.java)) })
        root.addView(ruleCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = Ui.dp(this@MainActivity, 14f) })

        // ===== 诊断卡（0.7 新增）=====
        val diagCard = Ui.card(this)
        diagCard.addView(Ui.text(this, "识别诊断", 16f, Ui.ON_SURFACE, bold = true))
        diagCard.addView(Ui.text(this,
            "遇到某个 App 没跳过？打开下面的开关，再复现一次广告，然后点「查看界面快照」把结果反馈给开发者。",
            12f, Ui.ON_SURFACE_VAR).apply {
            setPadding(0, Ui.dp(this@MainActivity, 6f), 0, Ui.dp(this@MainActivity, 4f))
        })
        diagCard.addView(Ui.switchRow(this, "记录界面快照", "仅本机保存，最多 5 条，不含个人内容", AppPrefs.diagnosticsEnabled) {
            AppPrefs.diagnosticsEnabled = it
        })
        diagCard.addView(divider())
        diagCard.addView(Ui.button(this, "查看界面快照") { showDiag() })
        root.addView(diagCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = Ui.dp(this@MainActivity, 14f) })

        // ===== 关于/帮助 =====
        val aboutCard = Ui.card(this)
        aboutCard.addView(Ui.text(this, "运行保障与说明", 16f, Ui.ON_SURFACE, bold = true))
        aboutCard.addView(Ui.text(this,
            "· 拦截通过无障碍服务实现，需要保持后台运行\n" +
            "· 统计记录的是拦截动作已执行，不代表广告必然关闭\n" +
            "· 小米/澎湃 OS 建议将省电策略设为无限制并锁定后台\n" +
            "· 全程离线运行，不上传任何数据", 12f, Ui.ON_SURFACE_VAR).apply {
            setPadding(0, Ui.dp(this@MainActivity, 8f), 0, Ui.dp(this@MainActivity, 4f))
        })
        aboutCard.addView(Ui.button(this, "查看完整说明与澎湃 OS 设置指引") { showGuidance() })
        root.addView(aboutCard)

        setContentView(scroll)
        requestNotificationPermissionIfNeeded()
    }

    private fun bigNum(v: String) = TextView(this).apply {
        text = v; textSize = 34f; setTextColor(Ui.PRIMARY); typeface = Typeface.DEFAULT_BOLD
    }

    private fun statColumn(num: TextView, label: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(num)
            addView(Ui.text(this@MainActivity, label, 12f, Ui.ON_SURFACE_VAR))
        }
    }

    private fun vline(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 1f), Ui.dp(this@MainActivity, 40f)).also {
            it.gravity = Gravity.CENTER_VERTICAL
        }
        setBackgroundColor(Ui.OUTLINE)
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this@MainActivity, 1f))
        setBackgroundColor(Ui.SURFACE_CONTAINER)
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val enabled = NotificationHelper.isServiceEnabledInSettings(this)
        val running = AdAccessibilityService.running
        if (running) {
            statusDot.background = circle(Ui.GREEN_CONTAINER); statusDot.text = "✓"; statusDot.setTextColor(Ui.GREEN)
            statusTitle.text = "拦截引擎运行中"
            statusSub.text = "正在监听开屏广告与误触跳转"
            statusBtn.visibility = ViewGroup.GONE
        } else if (enabled) {
            statusDot.background = circle(Ui.PRIMARY_CONTAINER); statusDot.text = "…"; statusDot.setTextColor(Ui.PRIMARY)
            statusTitle.text = "等待系统启动服务"
            statusSub.text = "无障碍已授权，回到本页稍候即可确认"
            statusBtn.visibility = ViewGroup.GONE
        } else {
            statusDot.background = circle(Ui.RED_CONTAINER); statusDot.text = "!"; statusDot.setTextColor(Ui.RED)
            statusTitle.text = "尚未授权无障碍"
            statusSub.text = "这是拦截功能的唯一前提，点击下方按钮去开启"
            statusBtn.text = "前往无障碍设置"
            statusBtn.visibility = ViewGroup.VISIBLE
            statusBtn.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        val total = StatsStore.totalAll()
        val today = StatsStore.totalToday()
        statTotal.text = total.toString()
        statToday.text = today.toString()
        emptyHint.visibility = if (total == 0) ViewGroup.VISIBLE else ViewGroup.GONE

        val pm = packageManager
        appList.removeAllViews()
        val rows = StatsStore.snapshot()
        for ((idx, s) in rows.withIndex()) {
            if (idx >= 8) break
            appList.addView(appRow(pm, s))
            if (idx < 7 && idx < rows.size - 1) appList.addView(divider())
        }
    }

    private fun circle(color: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color); d.shape = GradientDrawable.OVAL
        return d
    }

    private fun appRow(pm: PackageManager, s: AppStat): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(this@MainActivity, 10f), 0, Ui.dp(this@MainActivity, 10f))
        }
        // 应用图标
        val icon = ImageView(this).apply {
            val pad = Ui.dp(this@MainActivity, 6f)
            setPadding(pad, pad, pad, pad)
            background = Ui.round(this@MainActivity, Ui.SURFACE_CONTAINER, 12f)
            try { setImageDrawable(pm.getApplicationIcon(s.pkg)) } catch (_: PackageManager.NameNotFoundException) {
                setImageResource(R.drawable.ic_app)
            }
            layoutParams = LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 40f), Ui.dp(this@MainActivity, 40f))
            scaleType = ImageView.ScaleType.FIT_CENTER
            clipToOutline = true
        }
        row.addView(icon)
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(Ui.dp(this@MainActivity, 12f), 0, Ui.dp(this@MainActivity, 8f), 0)
        }
        val name = try { pm.getApplicationLabel(pm.getApplicationInfo(s.pkg, 0)).toString() } catch (_: PackageManager.NameNotFoundException) { s.pkg }
        mid.addView(Ui.text(this, name, 14f, Ui.ON_SURFACE, bold = true))
        val ruleDesc = s.rules.entries.sortedByDescending { it.value }.joinToString(" · ") { (k, v) -> ruleName(k) + " " + v }
        mid.addView(Ui.text(this, ruleDesc, 11f, Ui.ON_SURFACE_VAR).apply { setPadding(0, Ui.dp(this@MainActivity, 2f), 0, 0) })
        row.addView(mid)
        // 右侧次数徽章
        row.addView(TextView(this).apply {
            text = s.total.toString()
            textSize = 15f
            setTextColor(Ui.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            background = Ui.pill(this@MainActivity, Ui.PRIMARY_CONTAINER)
            gravity = Gravity.CENTER
            minWidth = Ui.dp(this@MainActivity, 44f)
            setPadding(Ui.dp(this@MainActivity, 10f), Ui.dp(this@MainActivity, 4f), Ui.dp(this@MainActivity, 10f), Ui.dp(this@MainActivity, 4f))
        })
        return row
    }

    private fun ruleName(id: String) = when (id) {
        "skip" -> "开屏"; "close" -> "关闭"; "jump" -> "返回"; else -> id
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("清空统计")
            .setMessage("将删除所有本地拦截记录，确定吗？")
            .setPositiveButton("清空") { _, _ -> StatsStore.clear(); refresh() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportStats() {
        val rows = StatsStore.snapshot()
        val text = buildString {
            append("清屏拦截统计 ").append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())).append("\n")
            append("累计 ").append(StatsStore.totalAll()).append(" · 今日 ").append(StatsStore.totalToday()).append("\n\n")
            for (s in rows) append(s.pkg).append(" total=").append(s.total).append(" today=").append(s.today).append(" rules=").append(s.rules).append("\n")
        }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("QingPing stats", text))
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }


    private fun showDiag() {
        val rows = StatsStore.diagSnapshot()
        if (rows.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("界面快照")
                .setMessage("还没有快照。\n\n请先打开「记录界面快照」开关，然后打开那个没跳过广告的 App，等广告出现几秒，再回到这里查看。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val text = buildString {
            for (d in rows) {
                append("=== ").append(fmt.format(Date(d.at))).append("  ").append(d.pkg)
                append("  (").append(d.reason).append(")\n")
                for (n in d.nodes) append(n).append("\n")
                append("\n")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("最近界面快照")
            .setMessage(if (text.length > 8000) text.substring(0, 8000) + "\n…（已截断）" else text)
            .setPositiveButton("复制") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("QingPing diag", text))
                Toast.makeText(this, "已复制，可直接反馈给开发者", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("清空快照") { _, _ -> StatsStore.clearDiag(); Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show() }
            .setNegativeButton("关闭", null)
            .show()
    }
    private fun showGuidance() {
        AlertDialog.Builder(this)
            .setTitle("运行保障与限制")
            .setMessage(
                "【为什么需要无障碍权限】\n清屏通过无障碍服务读取屏幕控件并模拟点击，这是无 Root 环境下拦截开屏广告的唯一可行方案。\n\n" +
                "【开机自动运行】\n服务在系统设置中启用后由系统绑定，开机自动恢复。若被 ROM 清理，会尝试发通知提醒。\n\n" +
                "【小米/澎湃 OS 建议】\n1. 应用信息 > 省电策略：无限制\n2. 自启动权限：允许\n3. 最近任务中锁定清屏卡片\n4. 神隐模式/省电优化中关闭限制\n\n" +
                "【签名与升级】\n本项目使用固定签名密钥，后续版本可直接覆盖安装升级，无需卸载。\n\n" +
                "【能力边界】\n1. 纯图片/自绘广告无法识别\n2. 信息流/横幅广告不做处理，避免误点\n3. 点击成功不等于广告必然关闭\n4. 无 Root 无法阻止广告加载\n5. 通知权限不影响拦截，只影响掉线提醒"
            )
            .setPositiveButton("打开应用信息") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("知道了", null)
            .show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}

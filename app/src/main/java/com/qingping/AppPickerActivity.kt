package com.qingping

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 跳转自动返回名单编辑：列出已安装应用，勾选即加入名单（即时保存）。
 * 自身应用与系统核心包（无界面服务类）不展示。
 */
class AppPickerActivity : Activity() {

    private val selected get() = AppPrefs.autoBackPkgs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPrefs.init(this)

        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
            .filter { it.packageName != packageName }
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { (pm.getApplicationLabel(it) as? String)?.lowercase() ?: it.packageName }

        val root = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        box.addView(TextView(this).apply {
            text = "勾选的应用在被广告误触拉起时将自动返回。默认名单为各应用商店；请谨慎添加常用应用，避免影响正常使用。"
            textSize = 13f
            setPadding(0, 0, 0, 16)
        })

        for (app in apps) {
            val pkg = app.packageName
            val label = try { pm.getApplicationLabel(app).toString() } catch (_: PackageManager.NameNotFoundException) { pkg }
            box.addView(row(label, pkg, pkg in selected))
        }
        root.addView(box)
        setContentView(root)
    }

    private fun row(label: String, pkg: String, checked: Boolean) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 8, 0, 8) }
            addView(CheckBox(context).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, isChecked ->
                    val set = AppPrefs.autoBackPkgs
                    if (isChecked) set.add(pkg) else set.remove(pkg)
                    AppPrefs.autoBackPkgs = set
                }
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = label; textSize = 15f })
                addView(TextView(context).apply { text = pkg; textSize = 11f; alpha = 0.6f })
            })
        }

    private val ApplicationInfo.safeLabel: String
        get() = try { packageManager.getApplicationLabel(this).toString() } catch (_: Exception) { packageName }
}

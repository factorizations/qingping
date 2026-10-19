package com.qingping

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** Material 3 风格的纯代码 UI 组件。深浅色自适应跟随系统 WebView 主题之外，这里固定浅色。 */
object Ui {
    // Material 3 色板（浅色）
    val PRIMARY = Color.parseColor("#1A73E8")
    val PRIMARY_CONTAINER = Color.parseColor("#D8E7FF")
    val ON_PRIMARY_CONTAINER = Color.parseColor("#001D3D")
    val SURFACE = Color.parseColor("#F8F9FB")
    val SURFACE_CONTAINER = Color.parseColor("#F0F2F5")
    val SURFACE_HIGH = Color.parseColor("#E9EBEF")
    val ON_SURFACE = Color.parseColor("#1B1B1F")
    val ON_SURFACE_VAR = Color.parseColor("#5F6368")
    val OUTLINE = Color.parseColor("#DADCE0")
    val GREEN = Color.parseColor("#1E8E3E")
    val GREEN_CONTAINER = Color.parseColor("#E6F4EA")
    val RED = Color.parseColor("#D93025")
    val RED_CONTAINER = Color.parseColor("#FCE8E6")

    fun card(activity: Activity): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = round(activity, Color.WHITE, 20f)
            elevation = dp(activity, 1f)
            setPadding(dp(activity, 18f), dp(activity, 16f), dp(activity, 18f), dp(activity, 16f))
        }
    }

    fun round(activity: Activity, color: Int, radiusDp: Float): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(activity, radiusDp)
        return d
    }

    fun pill(activity: Activity, color: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = dp(activity, 999f)
        return d
    }

    fun dp(activity: Activity, v: Float): Int = (v * activity.resources.displayMetrics.density + 0.5f).toInt()

    fun text(activity: Activity, s: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView {
        return TextView(activity).apply {
            text = s
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    }

    fun button(activity: Activity, label: String, action: () -> Unit): TextView {
        return TextView(activity).apply {
            text = label
            textSize = 14f
            setTextColor(PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), round(activity, SURFACE_HIGH, 12f))
                addState(intArrayOf(), round(activity, SURFACE_CONTAINER, 12f))
            }
            setPadding(dp(activity, 16f), dp(activity, 11f), dp(activity, 16f), dp(activity, 11f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(activity, 8f) }
            setOnClickListener { action() }
        }
    }

    fun switchRow(activity: Activity, label: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 10f), 0, dp(activity, 10f))
        }
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(text(activity, label, 15f, ON_SURFACE, bold = true))
        if (sub.isNotBlank()) texts.addView(text(activity, sub, 12f, ON_SURFACE_VAR).apply {
            setPadding(0, dp(activity, 2f), 0, 0)
        })
        row.addView(texts)
        row.addView(Switch(activity).apply { isChecked = checked; setOnCheckedChangeListener { _, c -> onChange(c) } })
        return row
    }
}

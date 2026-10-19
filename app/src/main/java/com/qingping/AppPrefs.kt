package com.qingping

import android.content.Context
import android.content.SharedPreferences

/** 轻量配置存储（SharedPreferences），带内存缓存避免高频反序列化 */
object AppPrefs {
    private const val KEY_SPLASH = "splash_skip_enabled"
    private const val KEY_CLOSE = "ad_activity_enabled"
    private const val KEY_JUMP = "auto_back_enabled"
    private const val KEY_PKGS = "auto_back_pkgs"
    private const val KEY_EVER = "service_ever_enabled"
    private const val KEY_DIAG = "diagnostics_enabled"

    private lateinit var sp: SharedPreferences
    private var cachedPkgs: MutableSet<String>? = null

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.getSharedPreferences("qingping_prefs", Context.MODE_PRIVATE)
    }

    /** 开屏广告自动跳过（默认开） */
    var splashSkipEnabled: Boolean
        get() = sp.getBoolean(KEY_SPLASH, true)
        set(v) = sp.edit().putBoolean(KEY_SPLASH, v).apply()

    /** 广告 SDK 全屏页自动关闭（默认开） */
    var adActivityEnabled: Boolean
        get() = sp.getBoolean(KEY_CLOSE, true)
        set(v) = sp.edit().putBoolean(KEY_CLOSE, v).apply()

    /** 误触跳转自动返回（默认关，需用户显式开启） */
    var autoBackEnabled: Boolean
        get() = sp.getBoolean(KEY_JUMP, false)
        set(v) = sp.edit().putBoolean(KEY_JUMP, v).apply()

    /** 跳转自动返回名单 */
    var autoBackPkgs: MutableSet<String>
        get() {
            var v = cachedPkgs
            if (v == null) {
                v = HashSet(sp.getStringSet(KEY_PKGS, RuleEngine.DEFAULT_JUMP_PKGS) ?: emptySet())
                cachedPkgs = v
            }
            return v
        }
        set(v) {
            cachedPkgs = HashSet(v)
            sp.edit().putStringSet(KEY_PKGS, HashSet(v)).apply()
        }

    /** 诊断模式：记录界面控件快照，便于排查"为什么没跳过"（默认关） */
    var diagnosticsEnabled: Boolean
        get() = sp.getBoolean(KEY_DIAG, false)
        set(v) = sp.edit().putBoolean(KEY_DIAG, v).apply()

    /** 用户是否启用过无障碍服务（用于开机提醒判断） */
    var serviceEverEnabled: Boolean
        get() = sp.getBoolean(KEY_EVER, false)
        set(v) = sp.edit().putBoolean(KEY_EVER, v).apply()
}

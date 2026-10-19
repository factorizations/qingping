package com.qingping

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale

/** 单个应用的拦截统计 */
class AppStat(
    val pkg: String,
    var total: Int = 0,
    var today: Int = 0,
    var todayKey: String = "",
    var lastAt: Long = 0L,
    var lastFrom: String = "",
    val rules: MutableMap<String, Int> = LinkedHashMap()
)

/**
 * 按应用统计拦截次数。零依赖实现（org.json + JSON 文件持久化），
 * 替代 0.2 的 Room，避免 kapt/annotation processor 增加云构建失败面。
 *
 * 统计口径：记录的是"拦截动作已执行"，不代表广告必然被关闭
 * （界面变化验证结果见事件 reason 字段）。
 */
object StatsStore {
    private const val TAG = "QingPing"
    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    private lateinit var file: File

    private val apps = LinkedHashMap<String, AppStat>()
    @Volatile private var dirty = false
    private val handler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { flush() }

    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "stats.json")
        load()
    }

    @Synchronized
    fun record(pkg: String, ruleId: String, from: String = "") {
        if (pkg.isBlank()) return
        val today = fmt.format(Date())
        val s = apps.getOrPut(pkg) { AppStat(pkg) }
        if (s.todayKey != today) { s.todayKey = today; s.today = 0 }
        s.total += 1
        s.today += 1
        s.lastAt = System.currentTimeMillis()
        s.lastFrom = from
        s.rules.merge(ruleId, 1, Int::plus)
        dirty = true
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 2000)
    }

    @Synchronized
    fun snapshot(): List<AppStat> = apps.values.sortedByDescending { it.total }

    @Synchronized fun totalAll(): Int = apps.values.sumOf { it.total }
    @Synchronized fun totalToday(): Int = apps.values.sumOf { it.today }

    @Synchronized
    fun clear() {
        apps.clear()
        dirty = true
        flush()
    }

    @Synchronized
    fun flush() {
        if (!::file.isInitialized || !dirty) return
        try {
            val root = JSONObject()
            root.put("version", 1)
            val arr = JSONArray()
            for (s in apps.values) {
                val o = JSONObject()
                o.put("pkg", s.pkg)
                o.put("total", s.total)
                o.put("today", s.today)
                o.put("todayKey", s.todayKey)
                o.put("lastAt", s.lastAt)
                o.put("lastFrom", s.lastFrom)
                val r = JSONObject()
                for ((k, v) in s.rules) r.put(k, v)
                o.put("rules", r)
                arr.put(o)
            }
            root.put("apps", arr)
            file.writeText(root.toString())
            dirty = false
        } catch (t: Throwable) {
            Log.w(TAG, "save stats failed", t)
        }
    }

    @Synchronized
    private fun load() {
        try {
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("apps") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val s = AppStat(
                    pkg = o.getString("pkg"),
                    total = o.optInt("total"),
                    today = o.optInt("today"),
                    todayKey = o.optString("todayKey"),
                    lastAt = o.optLong("lastAt"),
                    lastFrom = o.optString("lastFrom")
                )
                val r = o.optJSONObject("rules")
                if (r != null) for (k in r.keys()) s.rules[k] = r.optInt(k)
                apps[s.pkg] = s
            }
        } catch (t: Throwable) {
            Log.w(TAG, "load stats failed", t)
        }
    }
}

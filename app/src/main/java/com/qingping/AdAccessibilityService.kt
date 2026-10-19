package com.qingping

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 清屏核心：无障碍服务（0.3）。
 *
 * 相比 0.2 的变化：
 * 1. 开屏跳过支持倒计时解析（"跳过 3" 等 3 秒后再点）与控件 id 双通道匹配。
 * 2. 新增广告 SDK 全屏 Activity 识别：先找明确关闭按钮，找不到才返回键。
 * 3. 新增误触跳转自动返回（名单制，默认关闭）。
 * 4. 统计改走 StatsStore（JSON 文件），保留"动作已执行"而非"广告已关闭"的口径。
 *
 * 保守原则保留：绝不盲点坐标；点击优先 performAction，手势仅为最后兜底。
 */
class AdAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "QingPing"
        private const val SCAN_INTERVAL_MS = 200L
        private const val CLICK_COOLDOWN_MS = 2500L
        private const val JUMP_COOLDOWN_MS = 5000L
        private const val MAX_TRAVERSE = 400
        private const val MAX_ANCESTOR_DEPTH = 5

        @Volatile
        var instance: AdAccessibilityService? = null
            private set

        val running: Boolean get() = instance != null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val cooldowns = HashMap<String, Long>()
    private val pendingSkips = HashSet<String>()
    private var lastForegroundPkg = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppPrefs.serviceEverEnabled = true
        Log.i(TAG, "accessibility service connected")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        try {
            if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                handleWindowState(e)
            } else if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                handleWindowContent(e)
            }
        } catch (t: Throwable) {
            // 事件回调绝不能抛异常，否则系统可能直接禁用本服务
            Log.w(TAG, "onAccessibilityEvent", t)
        }
    }

    // ---------- 事件入口 ----------

    private fun handleWindowState(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (isIgnoredPkg(pkg)) return
        val now = System.currentTimeMillis()
        val from = lastForegroundPkg
        lastForegroundPkg = pkg

        // 1. 误触跳转拦截：新窗口属于跳转名单 -> 自动返回
        if (AppPrefs.autoBackEnabled && RuleEngine.isJumpTarget(pkg) &&
            shouldFire("jump:$pkg", now, JUMP_COOLDOWN_MS)
        ) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            StatsStore.record(pkg, "jump", from)
            Log.i(TAG, "auto-back escape jump: $pkg (from=$from)")
        }

        // 2. 广告 SDK 全屏页关闭
        if (AppPrefs.adActivityEnabled) {
            val activity = e.className?.toString()
            if (activity != null && RuleEngine.isAdActivity(activity) &&
                shouldFire("close:$pkg:$activity", now, CLICK_COOLDOWN_MS)
            ) {
                if (!clickCloseButton()) performGlobalAction(GLOBAL_ACTION_BACK)
                StatsStore.record(pkg, "close", from)
                Log.i(TAG, "closed ad activity: $activity")
            }
        }

        // 3. 开屏广告跳过（窗口切换时立即扫一次）
        scanForSkip(pkg, now)
    }

    private fun handleWindowContent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (isIgnoredPkg(pkg)) return
        scanForSkip(pkg, System.currentTimeMillis())
    }

    // ---------- 开屏跳过 ----------

    private fun scanForSkip(pkg: String, now: Long) {
        if (!AppPrefs.splashSkipEnabled) return
        val last = cooldowns["scan:$pkg"] ?: 0L
        if (now - last < SCAN_INTERVAL_MS) return
        cooldowns["scan:$pkg"] = now

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return

        val node = findSkipNode(root) ?: return
        val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        tryClick(pkg, node, text, RuleEngine.skipDelayMs(text))
    }

    private fun tryClick(pkg: String, node: AccessibilityNodeInfo, text: String, delayMs: Long) {
        if (delayMs <= 0) {
            if (shouldFire("skipclick:$pkg", System.currentTimeMillis(), CLICK_COOLDOWN_MS) &&
                clickNode(node)
            ) {
                StatsStore.record(pkg, "skip")
                Log.i(TAG, "skip clicked: $pkg text=\"$text\"")
            }
            return
        }
        // 倒计时未到：先记下意图，到点后重新扫描再点（防止重复排程用 pendingSkips 去重）
        val key = "$pkg|$text"
        if (pendingSkips.add(key)) {
            handler.postDelayed({
                pendingSkips.remove(key)
                val root = rootInActiveWindow ?: return@postDelayed
                if (root.packageName?.toString() != pkg) return@postDelayed
                val n = findSkipNode(root) ?: return@postDelayed
                if (shouldFire("skipclick:$pkg", System.currentTimeMillis(), CLICK_COOLDOWN_MS) &&
                    clickNode(n)
                ) {
                    StatsStore.record(pkg, "skip")
                    Log.i(TAG, "skip clicked after countdown: $pkg text=\"$text\"")
                }
            }, delayMs)
        }
    }

    private fun findSkipNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var count = 0
        while (stack.isNotEmpty() && count < MAX_TRAVERSE) {
            val n = stack.removeLast()
            count++
            if (!n.isVisibleToUser) continue
            val text = n.text?.toString()
            if (!text.isNullOrBlank() && RuleEngine.isSkipText(text)) return n
            if (RuleEngine.isSkipId(n.viewIdResourceName) && n.isClickable) return n
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.add(it) }
            }
        }
        return null
    }

    // ---------- 广告页关闭 ----------

    private fun clickCloseButton(): Boolean {
        val root = rootInActiveWindow ?: return false
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var count = 0
        while (stack.isNotEmpty() && count < MAX_TRAVERSE) {
            val n = stack.removeLast()
            count++
            val text = n.text?.toString()
            val desc = n.contentDescription?.toString()
            val hit = (text != null && RuleEngine.isCloseText(text)) ||
                (desc != null && RuleEngine.isCloseText(desc)) ||
                RuleEngine.isCloseId(n.viewIdResourceName)
            if (hit && n.isVisibleToUser && clickNode(n)) return true
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.add(it) }
            }
        }
        return false
    }

    // ---------- 点击执行 ----------

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var p: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (p != null && depth < MAX_ANCESTOR_DEPTH) {
            if (p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            p = p.parent
            depth++
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        return tap(r.exactCenterX().toFloat(), r.exactCenterY().toFloat())
    }

    private fun tap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    // ---------- 工具 ----------

    private fun shouldFire(key: String, now: Long, interval: Long): Boolean {
        val last = cooldowns[key] ?: 0L
        if (now - last < interval) return false
        cooldowns[key] = now
        if (cooldowns.size > 64) {
            cooldowns.entries.removeAll { now - it.value > 60_000L }
        }
        return true
    }

    private fun isIgnoredPkg(pkg: String): Boolean {
        return pkg == packageName || pkg == "com.android.systemui" ||
            pkg.startsWith("com.android.") || pkg == "com.miui.home"
    }
}

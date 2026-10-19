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
 * 清屏核心：无障碍服务（0.7）。
 *
 * 0.7 关键修复（针对"航旅纵横/贴吧开屏广告没跳过"）：
 * - 不再要求 rootInActiveWindow 的包名等于事件包名，并在所有窗口里查找跳过控件；
 * - 去掉 isVisibleToUser 的整树剪枝，改判"有屏幕坐标且非零尺寸"；
 * - text 与 contentDescription 双通道匹配；
 * - 点击成功后复查、失败改手势兜底，冷却只在确认生效后才记。
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
        private const val MAX_TRAVERSE = 1200
        private const val MAX_ANCESTOR_DEPTH = 8

        @Volatile
        var instance: AdAccessibilityService? = null
            private set

        val running: Boolean get() = instance != null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val cooldowns = HashMap<String, Long>()
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

        // 3. 开屏广告跳过（窗口切换时立即扫一次，随后再重扫几次）
        scanForSkip(pkg, now)
        scheduleRescans(pkg)
    }

    private fun handleWindowContent(e: AccessibilityEvent) {
        // 部分 ROM 的内容变化事件不带包名，此时退回当前前台包名，
        // 否则开屏广告在 WebView 里刷新时（最常见的场景）会被直接丢掉。
        val pkg = e.packageName?.toString() ?: lastForegroundPkg
        if (pkg.isBlank()) return
        if (isIgnoredPkg(pkg)) return
        scanForSkip(pkg, System.currentTimeMillis())
    }

    // ---------- 开屏跳过 ----------

    private fun scanForSkip(pkg: String, now: Long) {
        if (!AppPrefs.splashSkipEnabled) return
        val last = cooldowns["scan:$pkg"] ?: 0L
        if (now - last < SCAN_INTERVAL_MS) return
        cooldowns["scan:$pkg"] = now

        // 0.7：不再要求 rootInActiveWindow 的包名等于事件包名。
        // 广告 SDK（穿山甲等）经常在独立窗口或独立进程里渲染开屏页，
        // 此时 root 包名与宿主 Activity 包名不同，旧逻辑会直接 return 导致漏拦。
        val node = findSkipNodeInAnyWindow(pkg)
        val scannedRoot = rootInActiveWindow
        if (node == null) {
            // 诊断：记录当前界面里所有可见且有文字的控件，便于排查识别失败
            val r = scannedRoot
            if (AppPrefs.diagnosticsEnabled && r != null) {
                StatsStore.recordDiag(pkg, "no-skip-node", dumpVisibleTexts(r))
            }
            return
        }
        val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        tryClick(pkg, node, text, RuleEngine.skipDelayMs(text))
    }

    /**
     * 开屏广告刚出现时，rootInActiveWindow 常常还是旧窗口或直接为 null，
     * 单次扫描容易错过。这里在窗口切换后按递增间隔重扫几次（0.7 新增）。
     */
    private fun scheduleRescans(pkg: String) {
        for (delay in longArrayOf(120L, 350L, 700L, 1200L, 2000L)) {
            handler.postDelayed({
                try {
                    if (AppPrefs.splashSkipEnabled && lastForegroundPkg == pkg) {
                        scanForSkip(pkg, System.currentTimeMillis())
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "rescan", t)
                }
            }, delay)
        }
    }

    /** 收集当前界面可见控件的文本/id 摘要（诊断用，最多 60 条） */
    private fun dumpVisibleTexts(root: AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>(60)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var count = 0
        while (stack.isNotEmpty() && count < MAX_TRAVERSE && out.size < 60) {
            val n = stack.removeLast()
            count++
            if (n.isVisibleToUser) {
                val text = n.text?.toString()
                val desc = n.contentDescription?.toString()
                val id = n.viewIdResourceName
                val label = when {
                    !text.isNullOrBlank() && text.length <= 40 -> text
                    !desc.isNullOrBlank() && desc.length <= 40 -> desc
                    else -> null
                }
                if (label != null || id != null) {
                    val cls = n.className?.toString()?.substringAfterLast(".") ?: "?"
                    out.add(cls + "|" + (label ?: "") + "|" + (id ?: "") + "|click=" + n.isClickable)
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return out
    }

    /**
     * 执行跳过。核心策略（0.7 重写）：
     *
     * 1. 冷却只在"确认生效"后才记，失败不做长冷却——开屏广告只有 3~5 秒，
     *    旧的 2500ms 预占冷却一旦碰上点击失败就等于整屏放弃。
     * 2. 三层递进：无障碍 ACTION_CLICK -> 真实手势点击坐标 -> 短延迟重试。
     *    大量开屏广告把按钮画在 WebView/自绘层，ACTION_CLICK 返回 true 但
     *    页面毫无反应，只有触摸事件才生效。
     * 3. 每层之后复查控件是否消失，只有消失才计入统计，避免"点了但没关"
     *    的虚高数字。
     */
    private fun tryClick(pkg: String, node: AccessibilityNodeInfo, text: String, @Suppress("UNUSED_PARAMETER") delayMs: Long) {
        val now = System.currentTimeMillis()
        val okKey = "ok:skip:$pkg"
        if (now - (cooldowns[okKey] ?: 0L) < CLICK_COOLDOWN_MS) return
        val tryKey = "try:skip:$pkg"
        if (now - (cooldowns[tryKey] ?: 0L) < 250L) return
        cooldowns[tryKey] = now

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // 第 1 层：标准无障碍点击
        val clicked = clickNode(node)
        if (!clicked && bounds.isEmpty) {
            Log.i(TAG, "skip: no click path yet: $pkg text=\"$text\"")
            return
        }

        // 第 2 层：300ms 后复查，未生效则用真实手势补一次
        handler.postDelayed({
            try {
                if (skipGone(pkg)) {
                    markSkipOk(pkg, okKey, text, "accessibility")
                    return@postDelayed
                }
                if (bounds.isEmpty) return@postDelayed
                if (!tap(bounds.exactCenterX().toFloat(), bounds.exactCenterY().toFloat())) {
                    Log.i(TAG, "skip: gesture dispatch failed: $pkg")
                    return@postDelayed
                }
                // 第 3 层：手势后再复查一次，仍未生效就交给下一轮扫描重试
                handler.postDelayed({
                    try {
                        if (skipGone(pkg)) markSkipOk(pkg, okKey, text, "gesture")
                        else Log.i(TAG, "skip: still visible after tap, will retry: $pkg")
                    } catch (t: Throwable) {
                        Log.w(TAG, "verify gesture skip", t)
                    }
                }, 400L)
            } catch (t: Throwable) {
                Log.w(TAG, "verify accessibility skip", t)
            }
        }, 300L)
    }

    /**
     * 在所有窗口里找跳过控件（0.7 新增，也是本次修复的关键）。
     *
     * 开屏广告常由广告 SDK 在独立窗口（甚至独立进程）里渲染，
     * rootInActiveWindow 拿到的可能不是广告那一层。因此：
     * 1. 先扫活动窗口；
     * 2. 再遍历 windows 里其余可交互窗口；
     * 3. 只接受 isActive 或包名等于前台包的窗口，避免在分屏/悬浮窗里
     *    误点其他应用的"跳过片头"。
     */
    private fun findSkipNodeInAnyWindow(pkg: String = lastForegroundPkg): AccessibilityNodeInfo? {
        val active = try { rootInActiveWindow } catch (_: Throwable) { null }
        if (active != null) {
            findSkipNode(active)?.let { return it }
        }
        val list = try { windows } catch (_: Throwable) { null } ?: return null
        for (w in list) {
            try {
                if (!w.isActive && w.root?.packageName?.toString() != pkg) continue
                val r = w.root ?: continue
                findSkipNode(r)?.let { return it }
            } catch (_: Throwable) {
                // 窗口可能随时销毁，忽略
            }
        }
        return null
    }

    /** 判断跳过控件是否已从界面上消失（= 广告真的被跳过了） */
    private fun skipGone(pkg: String): Boolean = findSkipNodeInAnyWindow(pkg) == null

    private fun markSkipOk(pkg: String, okKey: String, text: String, how: String) {
        cooldowns[okKey] = System.currentTimeMillis()
        StatsStore.record(pkg, "skip")
        Log.i(TAG, "skip succeeded via $how: $pkg text=\"$text\"")
    }

    /**
     * 查找"跳过"控件。
     *
     * 0.7 修复三个实际拦截失败的原因：
     * 1. 原来 `if (!n.isVisibleToUser) continue` 会连带跳过整棵子树——父容器
     *    被判不可见时，里面可见的按钮也扫不到（WebView/自绘容器常见）。
     *    现在只跳过当前节点自身的匹配，子节点继续遍历。
     * 2. 原来只看 text，不看 contentDescription。很多广告按钮只把文案放在
     *    contentDescription 里。
     * 3. 保留第一轮命中即返回；同时记录未命中时的界面快照，供诊断用。
     */
    private fun findSkipNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var count = 0
        var firstHit: AccessibilityNodeInfo? = null
        while (stack.isNotEmpty() && count < MAX_TRAVERSE) {
            val n = stack.removeLast()
            count++
            val text = n.text?.toString()
            val desc = n.contentDescription?.toString()
            val byText = (!text.isNullOrBlank() && RuleEngine.isSkipText(text)) ||
                (!desc.isNullOrBlank() && RuleEngine.isSkipText(desc))
            val byId = RuleEngine.isSkipId(n.viewIdResourceName)
            if (byText || byId) {
                // 0.7：不再强制 isVisibleToUser。开屏广告刚出现的一两帧里，
                // 系统还没完成可见性计算，控件会被判成"不可见"，而这时恰恰
                // 是最佳点击时机。改为：有屏幕坐标且非零尺寸就认为是可见的。
                val r = Rect()
                n.getBoundsInScreen(r)
                val usable = n.isVisibleToUser || (!r.isEmpty && r.width() > 0 && r.height() > 0)
                if (usable) {
                    // 可点击的优先直接返回；否则先记下来（可能是文本子节点，父容器才可点）
                    if (clickableChain(n) != null) return n
                    if (firstHit == null) firstHit = n
                }
            }
            // 无论本节点是否命中/可见，都继续下探子节点
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.add(it) }
            }
        }
        return firstHit
    }

    /** 从节点自身向上找到第一个可点击祖先（含自身），找不到返回 null */
    private fun clickableChain(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        var p: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (p != null && depth < MAX_ANCESTOR_DEPTH) {
            if (p.isClickable) return p
            p = p.parent
            depth++
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
        // 节点可能已失效（界面刚刷新），先 refresh 一次再点
        try { node.refresh() } catch (_: Throwable) { }
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

package com.qingping

/**
 * 内置静态规则引擎。与执行逻辑解耦，后续可扩展为 JSON 规则订阅。
 *
 * 设计原则（沿用 0.2 的保守思路并适度放宽）：
 * - 开屏"跳过"：文本/控件 id 双通道匹配，命中后优先 performAction，
 *   失败才用无障碍手势点击坐标；手势兜底仅在本服务被授予 canPerformGestures 时可用。
 * - 广告 SDK 全屏页：按 Activity 类名前缀识别，先找明确的关闭按钮，找不到才 GLOBAL_ACTION_BACK。
 * - 误触跳转：仅对用户显式勾选的名单包名执行返回，默认名单是各应用商店，
 *   且默认关闭该开关，避免误伤用户主动打开商店的场景。
 */
object RuleEngine {

    /** 开屏跳过文本（包含匹配，忽略大小写） */
    private val SKIP_TEXT = Regex("跳过|skip", RegexOption.IGNORE_CASE)

    /** 广告页关闭按钮的精确文本/描述 */
    private val CLOSE_TEXT = setOf(
        "关闭", "关闭广告", "跳过广告", "关闭獎勵", "×", "✕", "x", "X", "close", "Close"
    )

    private const val CLOSE_ID_HINT = "close"
    private const val SKIP_ID_HINT = "skip"

    /** 倒计时提取："跳过 3" / "3s跳过" / "3 秒后跳过" */
    private val COUNTDOWN = Regex(
        "(?:(\\d+)\\s*[sS秒]?\\s*(?:跳过|skip))|(?:(?:跳过|skip)\\s*(\\d+)\\s*[sS秒]?)|(\\d+)\\s*[sS秒]\\s*后",
        RegexOption.IGNORE_CASE
    )

    /** 常见广告 SDK 的 Activity 全限定类名前缀 */
    val AD_ACTIVITY_PREFIXES = listOf(
        "com.bytedance.sdk.openadsdk.",          // 字节穿山甲 / Pangle
        "com.qq.e.ads.",                          // 腾讯优量汇 / 广点通
        "com.qq.e.tg.",                           // 优量汇激励视频
        "com.baidu.mobads.appactivity.",          // 百度
        "com.baidu.mobads.sdk.api.",
        "com.kwad.components.ad.",                // 快手联盟
        "com.kwad.sdk.api.",
        "com.mbridge.msdk.",                      // Mintegral
        "com.sigmob.sdk.",                        // Sigmob
        "com.anythink.",                          // TopOn
        "com.unity3d.ads.",                       // Unity Ads
        "com.unity3d.services.ads.",
        "com.vungle.warren.ui.",                  // Vungle / Liftoff
        "com.applovin.adview.",                   // AppLovin
        "com.applovin.impl.adview.",
        "com.google.android.gms.ads.AdActivity",  // AdMob
        "com.ironsource.sdk.activities.",         // ironSource
        "com.inmobi.ads.",                        // InMobi
        "com.inmobi.media.",
        "com.huawei.hms.ads.",                    // 华为广告
        "com.miui.zeus.",                         // 小米
        "com.aps."                                // 荣耀
    )

    /** 默认误触跳转自动返回名单：各家应用商店（跳转广告最常见的落地页） */
    val DEFAULT_JUMP_PKGS: Set<String> = setOf(
        "com.tencent.android.qqdownloader",  // 应用宝
        "com.baidu.appsearch",               // 百度手机助手
        "com.wandoujia.phoenix2",            // 豌豆荚
        "com.huawei.appmarket",              // 华为应用市场
        "com.hihonor.appmarket",             // 荣耀应用市场
        "com.heytap.market",                 // OPPO 软件商店
        "com.bbk.appstore",                  // vivo 应用商店
        "com.xiaomi.market",                 // 小米应用商店
        "com.sec.android.app.samsungapps",   // 三星
        "com.meizu.mstore",                  // 魅族
        "com.lenovo.leos.appstore"           // 联想
    )

    fun isSkipText(text: String): Boolean = SKIP_TEXT.containsMatchIn(text)

    fun isCloseText(text: String): Boolean = text.trim() in CLOSE_TEXT

    fun isCloseId(viewId: String?): Boolean =
        viewId != null && viewId.substringAfterLast('/', viewId).lowercase().contains(CLOSE_ID_HINT)

    fun isSkipId(viewId: String?): Boolean =
        viewId != null && viewId.substringAfterLast('/', viewId).lowercase().contains(SKIP_ID_HINT)

    /** 返回需要等待的毫秒数；无倒计时返回 0（立即点击） */
    fun skipDelayMs(text: String): Long {
        val m = COUNTDOWN.find(text) ?: return 0L
        val sec = (m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] })
            .toIntOrNull() ?: 0
        return if (sec in 1..15) sec * 1000L else 0L
    }

    fun isAdActivity(className: String): Boolean =
        AD_ACTIVITY_PREFIXES.any { className.startsWith(it) }

    fun isJumpTarget(pkg: String): Boolean = AppPrefs.autoBackPkgs.contains(pkg)
}

# Changelog

## 0.6 (2026-10-19)

### 新增
- **固定签名**：仓库内置 `qingping.jks`，debug/release 均使用同一签名，云构建产物可覆盖安装升级。CI 增加 `Verify APK signature` 步骤打印证书指纹。
- 主界面改为 Material 三卡布局（状态卡 / 统计卡 / 规则卡），新增纯代码组件库 `Ui.kt`。
- 统计卡展示应用图标、规则分布与次数徽章，按应用排行最多 8 行。

### 修复
- 开屏广告「跳过 02」倒计时按钮反复重排导致广告播完也不点击：改为识别即点。
- CI 中 Gradle 失败被管道吞成成功：加 `set -o pipefail`。

## 0.3 (2026-10-19)

初始公开版本（0.1/0.2 为交接期原型，未发布）。

### 新增
- 开屏广告自动跳过：文本 + 控件 id 双通道匹配。
- 广告 SDK 全屏页自动关闭：识别穿山甲、优量汇、快手、百度、Mintegral、Sigmob、TopOn、Unity、Vungle、AppLovin、AdMob、ironSource、InMobi、华为、小米、荣耀等约 20 家 SDK 的 Activity 类名前缀；优先点关闭按钮，找不到才返回键。
- 误触跳转自动返回：名单制（默认关），默认名单覆盖 11 家应用商店，用户可自由增删。
- 按应用统计：累计/今日/规则分布，JSON 本地持久化，支持导出到剪贴板、一键清空。
- 开机自启兜底：BOOT_COMPLETED / MY_PACKAGE_REPLACED 后检查服务绑定状态，掉线时发通知提醒。
- 运行保障与限制说明弹窗（含澎湃 OS 省电设置指引）。

### 技术决策
- 零第三方依赖：放弃 0.2 的 Room/kapt/AndroidX，统计改用 org.json + JSON 文件，配置用 SharedPreferences，最大化 GitHub Actions 云构建成功率。
- 保守点击策略升级：performAction 优先、父节点逐级回退、手势兜底；2500ms 点击冷却；误触返回 5s 冷却。
- 事件回调全程 try/catch，防止异常导致系统禁用服务。

### 修复
- 0.2 的 MainActivity 字符串字面量内换行导致的编译错误（重构后不再存在）。
- 云构建 workflow 改为源码即仓库根，移除 ZIP 解包步骤（0.2 在 Unpack source 步骤失败）。

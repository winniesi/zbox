# ZBox — ZCode 多设备远程管理（Android）

管理多台运行 ZCode 桌面端的设备：扫码 / 粘贴绑定远程控制链接，一键打开官方
Web 远程控制页，并显示链接（临时钥匙）的生成时间。

## 架构

WebView 壳 + 原生设备管理层。所有与桌面端的协议通信都发生在官方网页端
（React SPA ⇄ `wss://…/ws/remote-control/window/<token>` 中继）内，本 App 不解析、
不重写该协议（唯一受控例外：远程任务监听，见下），因此 ZCode 升级不会破坏兼容性。

```
core/     纯 Kotlin 业务逻辑（可 JVM 单测，不依赖 Android）
  RemoteLink        链接解析 / 重建（sid/hash/t/mid/name/app_version）
  DeviceRepository  按 mid 去重、凭证加密落盘、customName 策略、最近使用排序
  AppLinks          zcode://device/add?url=… 与 zcode://device/open/<mid>
  DeviceWebProfiles / shortcutsFor   Profile 命名与快捷方式选取纯逻辑
platform/ Android 实现
  KeystoreAesGcmCipher  AndroidKeyStore AES-GCM（IV 前置，密钥不出安全硬件）
  DataStoreDeviceStore  DataStore(JSON)，损坏兜底为空
  DeviceWebViewFactory  每设备独立 Profile（MULTI_PROFILE，WebView≥115），
                        不可用时自动降级默认 profile；删除设备时清理
  AndroidShortcuts      最近 4 台设备 → 动态快捷方式
ui/       Compose M3（设备列表 / 扫码 / 粘贴添加 / WebView 远程页）
```

## 关键行为

- **钥匙安全**：链接里的 hash 视为敏感凭证，Keystore 加密后落盘；
  `allowBackup=false`；App 自身无任何网络上报。
- **深链**：`zcode://device/add?url=<编码后的远程链接>`（桌面快捷方式、扫码复用）。
- **钥匙没有已知的时间过期**：只有桌面端**刷新二维码**才会作废旧链接，链接
  龄期不构成失效信号（v0.1.0 的 24h 启发式标记因此误报，已移除）。是否失效
  以实际连接结果为准（官方页显示 AUTH_FAILED 等自带引导），App 内随时可
  一键重新扫码。
- **重新扫码更新**：列表卡片菜单 / 远程页失败引导 → 扫码 →
  直接更新该设备钥匙（mid 匹配校验）→ 远程页按 issuedAt 变化自动重载。
- **返回手势**：远程页内返回手势 / 顶栏 ← 先退网页自身历史（任务会话内部
  导航），退无可退才退出到设备列表。
- **失败分类**（ui/remote/WebViewFailure.kt）：401/403/410 与 WebView 认证错误 →
  「钥匙失效，重新扫码」；其余网络错误 → 「重试 / 检查桌面端在线」。
- **单连接限制（桌面端行为）**：同一时间只允许一个控制端接入。若官方页显示
  「已被其他设备接管 / KICKED」，关掉其他浏览器的远程页后点页面内
  「重新连接」即可；App 切换设备时会自动销毁其他设备的 WebView。
- **应用内检查更新**：列表页顶栏 ↻ 检查 GitHub Release（`winniesi/zbox`，tag 形如
  `v0.1.3`）；距上次检查超 24h 后冷启动静默检查一次。发现新版本弹窗确认 →
  跳转浏览器到 GitHub Release 页手动下载安装（不做应用内下载：DownloadManager
  直连 GitHub 资产在部分网络不可用，浏览器交给用户自己的下载手段）。
- **远程任务提醒（后台本地推送）**：远程页顶栏铃铛开关。开启后 document-start
  向页面注入只读 WebSocket 观察脚本（`WebViewCompat.addDocumentStartJavaScript`），
  深扫中继消息里的 `task_complete` / `task_error` / `permission_request` /
  `elicitation_request` 事件与 `usage.delta` 的模型/token 用量，在任务**完成 /
  出错 / 等待审批 / 等待回答**时发本地通知（完成通知带模型名与输出 token 数；
  `task_warning` 只记日志）。退后台时以前台服务（`specialUse`）保活 WebView，
  **进程被杀则监听自然终止**；协议解析失败一律静默忽略，只可能「收不到提醒」，
  不会影响页面本身。
- **WebView 诊断**：debug 下开启 `setWebContentsDebuggingEnabled(true)`，页面
  console / 加载事件写入 logcat tag `ZBoxWebView`。

## 构建 / 测试

```bash
# 依赖：JDK 17+，Android SDK（compileSdk 36）；local.properties 配 sdk.dir
./gradlew testDebugUnitTest          # 60 个 JVM 单元测试
./gradlew assembleDebug              # debug APK

# 模拟器 / 真机（6 个 instrumented 测试：Keystore 真实加解密 + 深链/启动 UI）
./gradlew connectedDebugAndroidTest
```

## 已知坑（已修复，留档）

**WebView 必须原生直挂，不能放进 Compose `AndroidView`**：部分 WebView 版本
（模拟器 133 复现、真机同症状）在 AndroidView 托管下 `vh`/`dvh` 视口单位解析为
0（`window.innerHeight` 却正常），整页依赖 `dvh` 的官方远程页会被裁剪成黑屏。
v0.1.2 起远程页改为原生 Activity（`RemoteActivity`）直挂 WebView；设备列表/
扫码/添加页无 WebView，仍用 Compose。诊断手段：`chrome://inspect` + logcat
tag `ZBoxWebView`。

## 正式签名构建

仓库不含签名密钥。构建脚本按「有无 `keystore.properties`」自动切换：
存在则 release 包用该密钥签名，否则回退 debug 签名（克隆后可直接出包）。

在本机配置：

```properties
# keystore.properties（已 gitignore，放在仓库根目录）
storeFile=/absolute/path/to/zbox-release.jks
storePassword=...
keyAlias=zbox
keyPassword=...
```

密钥库生成：`keytool -genkeypair -v -keystore zbox-release.jks -alias zbox -keyalg RSA -keysize 2048 -validity 10950`。
⚠️ 密钥库与口令请异地备份——丢失后无法对已有用户发布签名更新。

## 后续（M3 候选）

- 桌面小部件（Glance）、best-effort 在线探测、iOS（Compose Multiplatform 或
  Flutter 重写壳层，WebView 策略不变）。

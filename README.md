# ZBox — ZCode 多设备远程管理（Android）

管理多台运行 ZCode 桌面端的设备：扫码 / 粘贴绑定远程控制链接，一键打开官方
Web 远程控制页，并对链接（临时钥匙）的时效做启发式标记。

## 架构

WebView 壳 + 原生设备管理层。所有与桌面端的协议通信都发生在官方网页端
（React SPA ⇄ `wss://…/ws/remote-control/window/<token>` 中继）内，本 App 不解析、
不重写该协议，因此 ZCode 升级不会破坏兼容性。

```
core/     纯 Kotlin 业务逻辑（可 JVM 单测，不依赖 Android）
  RemoteLink        链接解析 / 重建（sid/hash/t/mid/name/app_version）
  freshnessOf       钥匙时效启发（>24h 标记"可能已失效"，阈值常量可调）
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
- **重新扫码更新**：列表卡片菜单 / 远程页失败引导 / 陈旧钥匙横幅 → 扫码 →
  直接更新该设备钥匙（mid 匹配校验）→ 远程页按 issuedAt 变化自动重载。
- **失败分类**（ui/remote/WebViewFailure.kt）：401/403/410 与 WebView 认证错误 →
  「钥匙失效，重新扫码」；其余网络错误 → 「重试 / 检查桌面端在线」。
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

## 已知环境限制（非 App bug）

在 **Android 36 模拟器（SwiftShader 软 GPU）** 上，官方远程页面（Chrome 同样）
只渲染背景不渲染网页文字——Chromium 在该模拟器 GPU 栈上的字形光栅化伪影。
已验证：HTTP/JS 资源全部 200、React 正常挂载、DOM 与桌面浏览器完全一致
（可通过 `chrome://inspect` 远程调试复核）。真机 GPU 不受此影响。

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

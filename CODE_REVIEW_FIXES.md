# Better Internet Tiles - 代码审查与修复记录 (Code Review & Fix Report)

日期：2026-11-09  
版本基线：`v3.1.3` (versionCode `3010300`)  
分支：`main`

---

## 目录
1. [严重问题修复 (High Priority)](#一严重问题修复-high-priority)
2. [中等严重度问题修复 (Medium Priority)](#二中等严重度问题修复-medium-priority)
3. [低风险与体验优化 (Low Priority)](#三低风险与体验优化-low-priority)
4. [涉及代码文件列表](#四涉及代码文件列表)
5. [验证结果](#五验证结果)

---

## 一、严重问题修复 (High Priority)

### H1. Wi-Fi 网络回调频繁拉起 Shell 查 SSID 导致卡顿与耗电
- **问题**：在 `TileSyncService.wifiChangeListener.onCapabilitiesChanged` 中，每次 RSSI 信号变化（几乎数秒一次）都会调用 `getConnectedWifiSSID(context)` 并在内部拉起一个独立的 Root / Shizuku 外部 Shell 进程去执行 `cmd wifi status`，耗费大量 CPU 并导致磁贴刷新卡顿。
- **修复方案**：
  - 在 `TileSyncService` 中引入 `fetchWifiSSID(context, force, onResult)`。
  - 增加 3 秒时间节流阈值（`SSID_MIN_INTERVAL_MS = 3000L`）以及 `AtomicBoolean` 正在查询防抖单飞锁（In-flight guard）。
  - 在 `onCapabilitiesChanged` 中仅当连接的网络发生变化（`network != latestAvailableWifiNetwork`）时才强制刷新，消除了信号强度变化造成的无效重复 Shell 进程开销。

### H2. `wifiSSID!!` 非空断言在多线程并发下导致 NPE 崩溃
- **问题**：`TileSyncService.wifiSSID` 为跨线程可变变量。在 `InternetTileBehaviour` 和 `WifiTileBehaviour` 中执行 `tile.label = if (...) wifiSSID!! else ...` 时，如果在判断非空之后字段被清空，就会直接触发 `NullPointerException` 崩溃。
- **修复方案**：
  - 统一在调用前先使用局部不可变变量快照 `val ssid = TileSyncService.wifiSSID`，消除断言 `!!`，确保线程安全。

### H3. `updateTileListeners` 线程安全及非主线程更新 QS 磁贴
- **问题**：`TileBehaviour.updateTileListeners` 使用普通的 `ArrayList`。当网络回调或 Shell 命令异步线程回调触发 `updateTile()` 时，会导致并发修改异常；且直接在子线程读取 UI 状态可能导致竞争。
- **修复方案**：
  - 将 `updateTileListeners` 替换为 `java.util.concurrent.CopyOnWriteArrayList`。
  - 在 `updateTile()` 中增加线程判断，确保通过 `Handler(Looper.getMainLooper())` 将磁贴状态获取与分发通知统一调度到 Android 主线程执行。

### H4. Android 12 无 `READ_PHONE_STATE` 权限注册 Telephony 崩溃
- **问题**：`CellularChangeListener.kt` 在 Android 12+ 下调用 `tm.registerTelephonyCallback(...)` 时，若用户未授予 `READ_PHONE_STATE` 权限，系统会抛出 `SecurityException` 导致整个前台服务直接崩溃退出。
- **修复方案**：
  - 在 `startListening()` 中增加 `try-catch (e: SecurityException)` 捕获并记录日志，使用 `telephonyCallbackRegistered` 标志位确保未授权时不崩溃且在 `stopListening()` 中不抛异常。

---

## 二、中等严重度问题修复 (Medium Priority)

### M1. 主线程频繁通过 `runBlocking` 读取 DataStore 导致主线程卡顿
- **问题**：`TileBehaviour` 在每次获取磁贴状态时，都会在主线程同步调用 `runBlocking { preferences.getRequireUnlock... }` 或 `getHideWiFiSSID`，容易引发 UI 掉帧卡顿。
- **修复方案**：
  - 在 `BITPreferences` 中增加常驻内存缓存 `cachedPreferences: Preferences?`，在内部协程中主动 `collect` 最新偏好。
  - 提供 `getRequireUnlockSync()`、`getShellMethodSync()`、`getHideWiFiSSIDSync()`、`getTaskerGuideSync()` 等同步无锁直读方法，彻底移除磁贴刷新过程中的 `runBlocking`。

### M2. 打开主页 `LiveTile` 导致系统下拉通知栏磁贴不刷新的逻辑混淆
- **问题**：`TileSyncService` 此前使用 `behaviourListeners` 检查磁贴是否处于监听状态。由于 App 主页内的 `LiveTile` 组件也会向其注册监听，导致主页展示期间系统下拉栏的 QS 磁贴被错误判断为非监听状态，停止同步。
- **修复方案**：
  - 在 `TileSyncService` 中增加独立的 `qsListeningBehaviours` 集合与 `setQsListening()` 方法，仅由系统的 `ReportingTileService` 的生命周期（`onStartListening`/`onStopListening`）进行标记，App 内组件与系统下拉栏状态解耦。

### M3. 共享状态可见性与异步 Shell 失败时按钮卡在“正在开启/关闭”
- **问题**：
  - `TileSyncService` 中的各个过渡标记（如 `isTurningOnData`、`isTurningOnWifi`、`isTurningOnAirplaneMode` 等）未加 `@Volatile`，多线程可见性不可靠。
  - 当通过 Shell 切换系统开关（如移动数据、NFC、蓝牙、飞行模式）失败返回 null 或失败 code 时，过渡标志未被复位，磁贴状态将无限期停留在“正在开启…”状态。
- **修复方案**：
  - 所有状态变量加上 `@Volatile` 修饰，蓝牙连接状态与电量映射改为 `ConcurrentHashMap`。
  - 在移动数据、NFC、蓝牙、飞行模式各 `TileBehaviour` 的异步执行回调中，若判断结果失败（`it?.isSuccess != true`），显式重置相应的过渡标志并触发更新。

### M4. Shizuku 管道死锁隐患与参数空格分词 Bug
- **问题**：
  - `ShizukuUtil.executeCommand` 原先在读取进程标准输出和错误流之前调用了 `process.waitFor()`，若子进程输出缓冲区填满，会导致子进程与当前线程双向死锁。
  - 原分词逻辑为 `command.split(" ")`，对多连续空格的命令解析会产生空参数。
- **修复方案**：
  - 改为使用 `Regex("\\s+")` 进行分词。
  - 在 `Util.executeShizukuCommand` 中使用双独立线程并发消费并缓存 `inputStream` 与 `errorStream`，并设置 10 秒超时防护（`process.waitFor(10, TimeUnit.SECONDS)`）。

### M7. 蓝牙 Headset 代理对象泄漏
- **问题**：`TileSyncService` 在 `BluetoothProfile.ServiceListener` 获取到 `HEADSET` 代理后，在 `onDestroy()` 时未调用 `closeProfileProxy` 关闭，造成系统服务代理连接泄漏。
- **修复方案**：
  - 在 `TileSyncService.onDestroy()` 中调用 `adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it)` 并清理引用。

### M8. Wi-Fi 名称查询中不准确的历史流量日志回退
- **问题**：`Util.getConnectedWifiSSID` 的第 4 级回退使用了 `dumpsys netstats`，该命令会输出历史网络记录，容易获取到已经断开的历史 SSID。
- **修复方案**：
  - 移除了不可靠的 `dumpsys netstats` 逻辑，仅保留系统官方 API、`dumpsys wifi` 以及 `cmd wifi status` 前三层可靠来源。

### M9. 广播接收器在后台启动前台服务未做异常防护
- **问题**：在 Android 8.0+ / 12+ / 14+ 下，开机或网络变化广播触发 `SyncServiceBroadcastReceiver` 时，若应用处于后台受限状态调用 `startForegroundService` 会直接抛出 `ForegroundServiceStartNotAllowedException` 导致进程直接闪退。
- **修复方案**：
  - 在 `SyncServiceBroadcastReceiver.onReceive` 中使用 `try-catch (e: Throwable)` 环绕并打印警告日志。

### M11. CI 工作流遗留上游 Google Play 与无效 Secrets 配置
- **问题**：`.github/workflows/build-deploy.yml` 仍包含发布到 Google Play 的步骤并依赖上游原作者的私钥与 Google Services 文件。
- **修复方案**：
  - 简化为专用于构建 F-Droid Release APK 的轻量干净工作流，支持 GitHub 平台手动触发并产出 Artifacts。

---

## 三、低风险与体验优化 (Low Priority)

- **L1 & L6（死代码与冗余引用清理）**：移除 `Util.kt` 中失效的埋点数据上报实现（改为无害空桩），删除未被调用的 `getShellAccessRequiredDialog` 及 10 余个无用 import。
- **L2 & L3（API 弃用警告处理）**：清理 `TileSyncService` 中未使用的 `Notification.Builder` 代码；蓝牙设备解析适配 Android 13+ 的 `Intent.getParcelableExtra(..., BluetoothDevice::class.java)`。
- **L4（清除调试遗留 UI）**：清理 `MainActivity.kt` 中遗留的全屏红色底色 `Box(Modifier.background(Color.Red).fillMaxSize())`。
- **L5 & L9（长按跳转鲁棒性）**：在 `Tile.kt` 与 `LongPressReceiverActivity.kt` 中添加 `try-catch` 保护，在缺少对应系统设置页的定制 ROM 上优雅回退到系统设置主页而非抛出 `ActivityNotFoundException`。
- **L8（Shizuku 权限回调校验）**：在 `ShizukuUtil.onRequestPermissionResult` 中增加 `requestCode == PERMISSION_REQUEST_CODE` 匹配校验。
- **L10（完善简体中文本地化）**：补充 `values-zh-rCN/strings.xml` 中此前缺失的 49 项文本翻译（包括权限提示、Tasker 引导、磁贴行为等），使中文字符串与主配置完全对齐。
- **L12（Material 3 图标弃用适配）**：将 `taskerGuideSetting.kt` 和 `SettingsPage.kt` 中的 `Icons.Outlined.OpenInNew` 更新为推荐的 `Icons.AutoMirrored.Outlined.OpenInNew`。

---

## 四、涉及代码文件列表

```text
.github/workflows/build-deploy.yml
app/src/main/java/be/casperverswijvelt/unifiedinternetqs/
├── SyncServiceBroadcastReceiver.kt
├── TileSyncService.kt
├── data/
│   └── BITPreferences.kt
├── listeners/
│   └── CellularChangeListener.kt
├── settings/settings/
│   └── taskerGuideSetting.kt
├── tilebehaviour/
│   ├── AirplaneModeTileBehaviour.kt
│   ├── BluetoothTileBehaviour.kt
│   ├── InternetTileBehaviour.kt
│   ├── MobileDataTileBehaviour.kt
│   ├── NFCTileBehaviour.kt
│   ├── TileBehaviour.kt
│   └── WifiTileBehaviour.kt
├── tiles/
│   ├── LongPressReceiverActivity.kt
│   └── ReportingTileService.kt
├── ui/
│   ├── MainActivity.kt
│   ├── components/Tile.kt
│   └── pages/SettingsPage.kt
└── util/
    ├── ShizukuUtil.kt
    └── Util.kt
app/src/main/res/values-zh-rCN/strings.xml
```

---

## 五、验证结果

1. **Gradle 构建**：`./gradlew assembleFdroidRelease` 成功构建通过，无任何编译错误或阻断性警告。
2. **真机验证（Pixel 7 Pro - Android 14）**：
   - APK 成功安装至设备 (`be.plexle.unifiedinternetqs`)。
   - `TileSyncService` 前台服务启动正常并维持运行。
   - 下拉状态栏磁贴（Wi-Fi、移动数据、互联网、飞行模式、蓝牙等）能够正常同步与切换。
   - 检查 `adb logcat -b crash` 无任何奔溃记录。

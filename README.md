# Smillet-Next

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![LSPosed API](https://img.shields.io/badge/LSPosed%20API-101%20%7C%20102-0088CC.svg)](https://github.com/libxposed/api)
[![Android SDK](https://img.shields.io/badge/Android-13%20~%2016%20(SDK%2033~37)-3DDC84.svg)](https://developer.android.com)
[![UI](https://img.shields.io/badge/UI-Miuix%20Compose-FF6900.svg)](https://github.com/compose-miuix-ui/miuix)

**Smillet-Next** 是一款面向小米 **Xiaomi HyperOS (澎湃 OS4.0)** 与 **MIUI** 的原生墓碑机制（`Greezer` / `Millet`）增强与深度省电（`DeepDoze`）控制模块。

此模块基于Gemini4编写，仅在hyperos4上进行测试！

本项目完全基于 **Kotlin + Jetpack Compose** 编写，采用 **[compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix)** 构建原生 HyperOS 风格界面。
---

## 核心特性

### 1. 原生墓碑 (`GreezeManagerService` & `MilletPolicy`) 深度增强
- **双作用域协同接管**：同时接管 `system_server` (`GreezeManagerService`、`AurogonImmobulusMode`、`ListAppsManager`、`PowerStrategyMode`) 与 `com.miui.powerkeeper` (`MilletPolicy`、`MilletHandler`、`MilletBrightHandler`)，实现策略层与执行层实时联动。
  - 兼容 SDK 37 全小写/下划线格式的冻结与解冻原因（如 `packet`、`check binder`、`sync binder`、`screen_on`、`from system`）。
  - 适配澎湃 OS 新版 `com.miui.server.greeze.power.ListAppsManager` 与 `PowerStrategyMode` 名单策略管控。
- **单应用 7 维策略矩阵**：
  - **墓碑白名单**：彻底豁免系统冻结，已在后台冻结的应用加入白名单后立即执行解冻。
  - **强制冻结（黑名单）**：移除 PowerKeeper 云端白名单、数据白名单及系统内置豁免，切入后台后强制纳入 Millet 冻结调度。
  - **强力压制（黑名单）**：拦截非白名单原因的后台唤醒解冻请求。
  - **消息应用**：允许通过网络唤醒包（`PACKET` / `packet`）与网络状态广播临时解冻接收推送。
  - **禁止同步 Binder**：阻断 `Sync Binder` / `Check Binder` 唤醒冻结应用，并在 PowerKeeper 层过滤查询队列。
  - **具有前台服务时不冻结**：自动检测应用活跃 Foreground Service (`FGS`) 状态，避免音乐播放、导航、录音等前台服务被误冻。
  - **电池优化控制**：按应用独立控制 Doze 电池未优化白名单（豁免/受控），并可拦截 PowerKeeper 自动篡改电池优化白名单。
- **冻结应用保护**：自动屏蔽已冻结应用的 `ANR` 弹窗（`AnrHelper` / `ProcessErrorStateRecord`）并跳过发往已冻结进程的普通广播（`BroadcastSkipPolicy`）。

### 2. 深度 Doze (`DeviceIdleController`) 控制
- **息屏自动进入 DeepDoze**：息屏满 3 分钟自动触发深度 Doze (`IDLE`)，亮屏即刻恢复。
- **跳过状态检查与运动检测屏蔽**：支持跳过 `LIGHT_IDLE` / `SENSING` / `LOCATING` 中间阶段直达 `IDLE`，并屏蔽 `motionLocked` 传感器唤醒导致退出 DeepDoze。
- **息屏耗电统计**：支持记录息屏期间的电量百分比与毫安时 (`mAh`) 变化并在亮屏时输出统计日志。

### 3. 实时进程监控、诊断与 Miuix 界面
- **主页总览**：
  - `1:1` 激活状态卡片与「白名单 / 黑名单」快捷入口卡片对齐展示，未激活或未授权 Root 时提供醒目横幅提醒。
  - 独立「已冻结」统计卡片，实时展示已冻结应用数与后台冻结进程数。
  - **当前运行内存 (RAM) 与 ZRAM 占用卡片**：实时解析 `/proc/meminfo`，图形化展示物理内存与 ZRAM 交换空间占用比例。
  - **当前日志查看与导出**：内置日志查看器，支持按「全部 / 调试 / 警告 / 错误」筛选、一键滚动到顶部/底部、导出日志文件至 `/sdcard/Download/` 或调用系统分享。
- **运行列表与一键诊断**：实时扫描进程 `PID`、`RSS` 内存、内核 `wchan` 冻结通道（区分 `Cgroup v2`、`Cgroup v1`、`SIGSTOP` 与运行中），长按任意运行中应用即可跨进程调取 `SystemServer + PowerKeeper` 双端未冻结原因诊断报告。

---

## 环境要求与激活说明

1. **系统环境**：Xiaomi HyperOS 4.0。
2. **框架要求**：支持 `libxposed` API 102 的 LSPosed 框架。
3. **LSPosed 作用域勾选（必选）**：
   - `系统框架` (`system` / `android`)
   - `电量与性能` (`com.miui.powerkeeper`)
4. **Root 权限**：首次打开管理器请授予 Root 权限（用于读取 `/proc` 进程状态、`/data/system/SMillet/log` 日志及辅助执行 Doze 命令）。
5. 勾选作用域后请**重启手机**使 `system_server` 与 `com.miui.powerkeeper` 注入生效。

---

## 项目结构

```text
Smillet-Next/
├── .github/
│   ├── ISSUE_TEMPLATE/
│   │   ├── bug_report.yml              # Bug 反馈模板
│   │   └── feature_request.yml         # 功能建议模板
│   └── workflows/
│       └── build.yml                   # GitHub Actions 自动构建与签名工作流
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── resources/META-INF/xposed/
│       │   ├── java_init.list          # Modern API 102 入口: com.sidesand.millet.hook.HandleHook
│       │   ├── module.prop             # minApiVersion=101, targetApiVersion=102
│       │   └── scope.list              # 默认作用域: system, android, com.miui.powerkeeper
│       ├── res/
│       │   ├── drawable/               # Adaptive Icon 矢量图标资源
│       │   ├── mipmap-anydpi-v26/      # 桌面图标定义
│       │   └── values/                 # 字符串、作用域数组与主题配置
│       └── java/com/sidesand/millet/
│           ├── data/
│           │   ├── AppModels.kt            # 运行中进程与已安装应用数据模型
│           │   ├── ConfigKeys.kt           # 配置键常量与忽略大小写的解冻原因匹配器
│           │   ├── MilletConfigSnapshot.kt # Hook 层不可变配置快照
│           │   └── PrefsManager.kt         # XposedService + DeviceProtected + Binder JSON 配置同步器
│           ├── ipc/
│           │   ├── BinderBridge.kt         # "SMillet-Binder" 匿名广播桥接器
│           │   ├── ISystemCallback.kt      # 跨进程回调接口 (PowerKeeper <-> SystemServer <-> UI)
│           │   └── SystemInterface.kt      # SystemServer Binder 服务端与客户端代理
│           ├── hook/
│           │   ├── HandleHook.kt           # XposedModule (API 102) 主入口
│           │   ├── XposedCompat.kt         # 基于 libxposed API 102 的反射与 Hook 封装层
│           │   ├── SMilletLogger.kt        # 内存环形缓冲 + 本地文件 + Xposed 日志系统
│           │   ├── system/
│           │   │   ├── DozeControllerHelper.kt # DeviceIdleController 深度 Doze 与电池优化白名单控制
│           │   │   ├── SystemServerHook.kt     # GreezeManagerService / Aurogon / GreezePower / ANR / 广播拦截
│           │   │   └── SystemServerService.kt  # SMillet-Binder 服务端实现
│           │   └── powerkeeper/
│           │       └── PowerKeeperHook.kt      # MilletPolicy / MilletHandler / 防杀后台与未冻结原因诊断
│           ├── util/
│           │   └── ProcessScanner.kt       # 进程冻结状态 (V1/V2/SIGSTOP)、日志聚合与 RAM/ZRAM 扫描器
│           └── ui/
│               ├── MainActivity.kt         # Compose 宿主 Activity
│               ├── MainScreen.kt           # Miuix Scaffold + TopAppBar + NavigationBar 主框架
│               ├── MainViewModel.kt        # 状态管理与 Binder IPC 通信 ViewModel
│               ├── components/AppIcon.kt   # 异步应用图标渲染组件
│               ├── theme/SMilletTheme.kt   # MiuixTheme HyperOS 主题封装
│               └── pages/
│                   ├── HomePage.kt         # 主页总览 (正方形激活卡片、黑白名单、已冻结、RAM/ZRAM、日志入口)
│                   ├── AppListPage.kt      # 应用列表 (白名单>黑名单>其他置顶排序 + 搜索筛选)
│                   ├── RunningListPage.kt  # 运行进程监视页 (单击配置 / 长按诊断冻结状态)
│                   ├── AppConfigDialog.kt  # 单应用 7 项墓碑与电池优化策略页
│                   ├── LogPage.kt          # 当前日志查看器 (按级别筛选、顶底跳转、文件导出与分享)
│                   └── SettingsPage.kt     # 全局冻结设置、PID 限制说明、深度 Doze 与 JSON 备份恢复页
├── build.gradle.kts
├── CHANGELOG.md
├── gradle/libs.versions.toml
├── gradle.properties
├── LICENSE
└── settings.gradle.kts
```

---

## 构建说明

### 方式一：使用 GitHub Actions 自动构建（推荐）
仓库已内置 `.github/workflows/build.yml`：
1. 将本项目代码推送到 GitHub 仓库的 `main` 或 `master` 分支（或直接在 Actions 页面手动触发 `Build Smillet-Next APK`）。
2. 工作流会自动配置 **JDK 17 + Gradle 9.7.1**，编译 Debug 与 Release 版本并自动完成签名，构建产物可在 Actions 页面的 `Smillet-Next-APKs` Artifact 中下载。
3. 当推送形如 `v1.0` 的 Git Tag 时，工作流会自动创建 GitHub Release 并上传已签名的安装包。

### 方式二：本地构建
确保本地已安装 **JDK 17+** 与 **Android SDK (API 37)**，在项目根目录执行：

```bash
gradle :app:assembleDebug :app:assembleRelease
```

---

## 致谢

- [libxposed/api](https://github.com/libxposed/api) & [libxposed/service](https://github.com/libxposed/service) — Modern Xposed API 102 标准接口与远程配置服务
- [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix) — Kotlin Multiplatform Xiaomi HyperOS 风格 Compose UI 组件库

---

## 开源协议

本项目基于 **[GNU General Public License v3.0 (GPL-3.0)](LICENSE)** 协议开源。
你可以自由地使用、研究、修改和分发本项目源码，但任何基于本项目的修改版本或衍生作品在分发时**必须同样以 GPL-3.0 协议开放完整源代码**，禁止将本项目闭源化或添加付费授权锁后二次分发。

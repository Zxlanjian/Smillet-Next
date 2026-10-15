# Smillet-Next (小米原生墓碑增强 LSPosed API 102 模块)

本项目是对 `671-for2.0_arm64-v8a.apk` (`com.sidesand.millet`) 的完整逆向还原与现代化重构版本 **Smillet-Next**，已全面迁移至 **LSPosed Modern API 102 (`io.github.libxposed:api:102.0.0` & `io.github.libxposed:service:102.0.0`)**，采用开源的 **[compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix)** (`top.yukonga.miuix.kmp:miuix-ui:0.9.4`) 构建原生 Xiaomi HyperOS 风格界面，并移除了原版 `libtestverify.so` 的联网设备序列号验证与内测限制。

## 目录结构

```text
Smillet-Next/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── resources/META-INF/xposed/
│       │   ├── java_init.list              # Modern API 102 入口: com.sidesand.millet.hook.HandleHook
│       │   ├── module.prop                 # minApiVersion=101, targetApiVersion=102
│       │   └── scope.list                  # 作用域: system, android, com.miui.powerkeeper
│       ├── res/
│       │   ├── drawable/                   # 扁平化 Adaptive Icon 前景/背景/单色矢量图
│       │   ├── mipmap-anydpi-v26/          # ic_launcher.xml / ic_launcher_round.xml
│       │   └── values/
│       │       ├── arrays.xml              # xposed_scope: system, android, com.miui.powerkeeper
│       │       ├── strings.xml             # Smillet-Next 应用名称与描述
│       │       └── themes.xml              # Android 原生 DayNight 主题
│       └── java/com/sidesand/millet/
│           ├── data/
│           │   ├── AppModels.kt            # 运行中进程与已安装应用数据模型
│           │   ├── ConfigKeys.kt           # SharedPreferences 键常量与允许解冻原因表
│           │   ├── MilletConfigSnapshot.kt # Hook 层配置快照
│           │   └── PrefsManager.kt         # XposedService + DeviceProtected + Binder JSON 配置同步器
│           ├── ipc/
│           │   ├── BinderBridge.kt         # "SMillet-Binder" 匿名广播桥接器
│           │   ├── ISystemCallback.kt      # 跨进程回调接口 (PowerKeeper <-> SystemServer <-> UI)
│           │   └── SystemInterface.kt      # SystemServer Binder 服务端与客户端代理
│           ├── hook/
│           │   ├── HandleHook.kt           # XposedModule (API 102) 主入口
│           │   ├── XposedCompat.kt         # 基于 libxposed API 102 的反射与 Hook 封装层
│           │   ├── SMilletLogger.kt        # Smillet-Next 日志系统
│           │   ├── system/
│           │   │   ├── DozeControllerHelper.kt # DeviceIdleController 深度 Doze 与电池优化白名单控制
│           │   │   ├── SystemServerHook.kt     # GreezeManagerService / Aurogon / ANR / 广播拦截
│           │   │   └── SystemServerService.kt  # SMillet-Binder 服务端实现
│           │   └── powerkeeper/
│           │       └── PowerKeeperHook.kt      # MilletPolicy / MilletHandler / 防杀后台与冻结状态诊断
│           ├── util/
│           │   └── ProcessScanner.kt       # 进程冻结状态 (V1/V2/SIGSTOP) 与内存占用扫描器
│           └── ui/
│               ├── MainActivity.kt         # Compose 宿主 Activity
│               ├── MainScreen.kt           # Miuix Scaffold + TopAppBar + NavigationBar 主框架
│               ├── MainViewModel.kt        # 状态管理与 Binder IPC 通信 ViewModel
│               ├── components/AppIcon.kt   # 图标渲染组件
│               ├── theme/SMilletTheme.kt   # MiuixTheme HyperOS 主题封装
│               └── pages/
│                   ├── HomePage.kt         # 主页状态总览 (Miuix 浅绿/浅红卡片 + 右下角圆框√/×图形 + 红色警告横幅)
│                   ├── AppListPage.kt      # 应用列表 (白名单>黑名单>其他置顶排序 + 单行回车搜索)
│                   ├── RunningListPage.kt  # 运行进程监视页 (单击配置 / 长按诊断冻结状态)
│                   ├── AppConfigDialog.kt  # 单应用 7 项墓碑与电池优化策略页
│                   └── SettingsPage.kt     # 全局冻结设置、深度 Doze、日志与 JSON 备份恢复页
├── build.gradle.kts
├── gradle/libs.versions.toml
├── gradle.properties
└── settings.gradle.kts
```

# Changelog

## [v1.0] - 2026-10-15

### Added
- 全面迁移至 **LSPosed Modern API 102 (`io.github.libxposed`)**，支持动态作用域与 `XposedService` 配置同步。
- 使用 **[compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix) v0.9.4** 重构全套 HyperOS 原生风格界面（启用平滑圆角 `smoothRounding = true`）。
- 适配 **HyperOS 2.0 / 3.0 (Android 16 / SDK 37)** 的 `com.miui.server.greeze.power.ListAppsManager` 与 `PowerStrategyMode` 名单管控。
- 主页运行状态卡片下方紧接「已冻结」统计卡片，「当前运行内存 (RAM) 与 ZRAM 当前占用」卡片支持灰色占用数据左对齐排列与每秒实时刷新。
- 主页「环境」区域直接展示当前 **LSPosed 框架版本**与 **Millet 运行状态**，状态角标 `✓` 严格居于绿色圆圈中心并完整显示。
- 应用列表「列表筛选」新增 **「未优化」** 筛选项，支持快速筛选电池未优化白名单应用。
- 新增内置「当前日志」页面（`LogPage`），支持右上角菜单按「全部 / 调试 / 警告 / 错误」筛选、滚动到顶部/底部、一键导出日志到 `/sdcard/Download/` 及系统分享。
- 在「设置 -> 冻结设置 -> 冻结模式」中点击 `PID` 选项时弹出底层机制说明弹窗，精简冗余提示文案。
- 补齐 `thawUid`、`thawUids`、`thawProcess` 与 `ProcessList.handleProcessStartedLocked` 解冻日志记录。

### Fixed
- 移除 `ConfigKeys.DEFAULT_MESSAGE_APPS` 中预置的微信 (`com.tencent.mm`) 默认配置，避免重新安装模块时默认勾选微信为消息应用。
- 修复 SDK 37 下冻结/解冻 `reason` 字符串变为小写或下划线格式（`packet`、`check binder`、`sync binder`、`screen_on`）导致部分策略漏判的问题。
- 修复 `GreezeManagerService$Lifecycle` 内部类包装导致的实例反射报错问题。
- 修复配置同步、电池优化白名单与 `from system` 周期巡检引起的重复日志刷屏问题。

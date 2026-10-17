package com.sidesand.millet.data

object ConfigKeys {
    const val PREFS_NAME = "Settings"
    const val BINDER_ACTION = "SMillet-Binder"
    const val BINDER_KEY_SYSTEM = "system"
    const val LOCAL_LOG_PATH = "/data/system/SMillet/log"

    // 1. MilletSettings (墓碑核心配置)
    const val DISABLE_KILL = "disableKill"
    const val DISABLE_VOLUME = "disableVolume"
    const val DISABLE_CHARGE = "disableCharge"
    const val BLOCK_ADD_POWERSAVE_WHITELIST = "blockAddPowerSaveWhitelistApps"
    const val BATTERY_CHANGE_OBSERVE = "batteryChangeObserve"

    // 2. DozeSettings (深度 Doze 配置)
    const val ENABLE_DOZE = "enableDoze"
    const val SKIP_STATE_CHECK = "skipStateCheck"
    const val DISABLE_MOTION_LISTENER = "disableMotionListener"

    // 3. BatteryOptSettings (系统电池优化白名单与 HyperOS 省电策略同步控制)
    const val BATTERY_OPT_WHITE_SET = "batteryOptWhiteSet"
    const val BATTERY_OPT_BLACK_SET = "batteryOptBlackSet"

    // 4. AppSettings (单应用墓碑策略集合)
    const val WHITE_APP_SET = "whiteAppSet"
    const val MESSAGE_APP_SET = "messageAppSet"
    const val FORCE_FREEZE_SET = "forceFreezeSet"
    const val BLOCK_THAW_SET = "blockThawSet"
    const val FORBID_SYNC_BINDER_SET = "forbidSyncBinderSet"
    const val NOT_FREEZE_WHEN_HAS_FGS_SET = "notFreezeWhenHasFgsSet"

    // 5. LogSettings (日志等级：debug / info / none)
    const val LOG_LEVEL = "logLevel"

    // 6. AppearanceSettings (外观与主题配置)
    const val THEME_COLOR_MODE = "themeColorMode" // 0: 跟随系统, 1: 浅色, 2: 深色
    const val MONET_ENABLED = "monetEnabled"
    const val BLUR_ENABLED = "blurEnabled"
    const val TOP_BAR_BLUR_MODE = "topBarBlurMode" // 0: 高斯, 1: 渐进
    const val PREDICTIVE_BACK_ENABLED = "predictiveBackEnabled"
    const val UI_SCALE = "uiScale" // 80, 90, 100, 110
    const val SWIPE_BACK_ENABLED = "swipeBackEnabled"
    const val FLOATING_BOTTOM_BAR = "floatingBottomBar"
    const val FLOATING_BOTTOM_BAR_STYLE = "floatingBottomBarStyle" // 0: Miuix, 1: iOS 液态玻璃
    const val BOTTOM_BAR_MODE = "bottomBarMode" // 0: 图标与文字, 1: 仅图标
    const val HIDE_TASK_CARD = "hideTaskCard"

    val ALLOWED_THAW_REASONS = listOf(
        "activity start",
        "notifi",
        "Activity Front",
        "FG activity",
        "tofront",
        "Sync Binder",
        "Check Binder",
        "Signal other",
        "Signal iso",
        "LAUNCH_MODE",
        "IMMOBULUS",
        "MediaButton",
        "receiver pkg",
        "PACKET",
        "cmd: thuid",
        "Net Change",
        "broadcast"
    )

    fun isAllowedThawReason(reason: String): Boolean {
        val trimmed = reason.trim()
        return ALLOWED_THAW_REASONS.any { it.equals(trimmed, ignoreCase = true) }
    }
}

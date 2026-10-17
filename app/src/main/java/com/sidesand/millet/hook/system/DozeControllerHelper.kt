package com.sidesand.millet.hook.system

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.sidesand.millet.hook.SMilletLogger
import com.sidesand.millet.hook.XposedCompat
import java.io.PrintWriter
import java.io.StringWriter

object DozeControllerHelper {

    private const val PK_USER_TABLE_URI = "content://com.miui.powerkeeper.configure/userTable"
    // HyperOS UserConfigure 真实数据库枚举常量（驼峰命名，切勿写成下划线格式）
    private const val BG_CONTROL_NO_RESTRICT = "noRestrict"
    private const val BG_CONTROL_MIUI_AUTO = "miuiAuto"

    @Volatile
    var deviceIdleController: Any? = null

    @Volatile
    var deviceIdleBinder: Any? = null

    @Volatile
    private var lastAppliedWhiteSet: Set<String>? = null

    @Volatile
    private var lastAppliedBlackSet: Set<String>? = null

    @Volatile
    private var hasRepairedLegacyRows: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private var isForcedIdle = false
    private var screenOffTimestamp = 0L
    private var screenOffBatteryPct = 0
    private var screenOffBatteryCounter = 0

    private val deepDozeAlarmListener = AlarmManager.OnAlarmListener {
        if (SMilletLogger.config.enableDoze) {
            forceDeepDoze()
        }
    }

    fun onDeviceIdleStart(controller: Any) {
        deviceIdleController = controller
        applyBatteryOptimizationSets(force = true)
    }

    fun resolveDeviceIdleController(): Any? {
        deviceIdleController?.let { return it }
        return runCatching {
            val smClass = Class.forName("android.os.ServiceManager")
            val binder = XposedCompat.callStaticMethod(smClass, "getService", "deviceidle")
            if (binder != null) {
                deviceIdleBinder = binder
                val outer = runCatching { XposedCompat.getObjectField(binder, "this$0") }.getOrNull()
                if (outer != null) {
                    deviceIdleController = outer
                    SystemServerHook.hookDeviceIdleControllerClass(outer.javaClass)
                    outer
                } else {
                    null
                }
            } else {
                null
            }
        }.getOrNull()
    }

    private fun resolveDeviceIdleBinder(): Any? {
        deviceIdleBinder?.let { return it }
        return runCatching {
            val smClass = Class.forName("android.os.ServiceManager")
            XposedCompat.callStaticMethod(smClass, "getService", "deviceidle")?.also {
                deviceIdleBinder = it
            }
        }.getOrNull()
    }

    private fun executeDeviceIdleShell(controller: Any, args: Array<String>): String? {
        return runCatching {
            val cl = controller.javaClass.classLoader
            val shellClass = XposedCompat.findClassIfExists(
                "com.android.server.DeviceIdleController\$Shell",
                cl
            ) ?: return null
            val shellObj = XposedCompat.newInstance(shellClass)
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            XposedCompat.setObjectField(shellObj, "mOutPrintWriter", pw)
            XposedCompat.setObjectField(shellObj, "mArgs", args)
            XposedCompat.setObjectField(shellObj, "mArgPos", 1)
            XposedCompat.callMethod(controller, "onShellCommand", shellObj, args[0])
            pw.flush()
            sw.toString().trim()
        }.getOrNull()
    }

    fun registerScreenReceiver(context: Context) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val alarmManager = ctx.getSystemService(AlarmManager::class.java)
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        if (SMilletLogger.config.enableDoze && alarmManager != null) {
                            val triggerAt = SystemClock.elapsedRealtime() + 180_000L
                            alarmManager.setExact(
                                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                                triggerAt,
                                "SMillet.Screen",
                                deepDozeAlarmListener,
                                handler
                            )
                            SMilletLogger.debug("[Doze] 屏幕关闭，已设定 3 分钟后触发深度 Doze")
                        }
                        if (SMilletLogger.config.batteryChangeObserve) {
                            recordBatteryOnScreenOff(ctx)
                        }
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        alarmManager?.cancel(deepDozeAlarmListener)
                        if (isForcedIdle) {
                            unforceDeepDoze()
                        }
                        if (SMilletLogger.config.batteryChangeObserve) {
                            logBatteryDrainOnScreenOn(ctx)
                        }
                    }
                }
            }
        }
        context.registerReceiver(receiver, filter)
        SMilletLogger.info("已注册屏幕状态广播监听 (Screen ON/OFF)")
    }

    private fun forceDeepDoze() {
        val controller = resolveDeviceIdleController() ?: return
        runCatching {
            executeDeviceIdleShell(controller, arrayOf("enable", "deep"))
            executeDeviceIdleShell(controller, arrayOf("force-idle", "deep"))
            runCatching { XposedCompat.callMethod(controller, "becomeInactiveIfAppropriateLocked") }
            runCatching { XposedCompat.callMethod(controller, "stepIdleStateLocked", "s:shell") }
            isForcedIdle = true
            SMilletLogger.info("[Doze] 已在息屏后强制进入深度 Doze (IDLE)")
        }.onFailure {
            SMilletLogger.error("[Doze] 触发深度 Doze 失败", it)
        }
    }

    private fun unforceDeepDoze() {
        val controller = resolveDeviceIdleController() ?: return
        runCatching {
            executeDeviceIdleShell(controller, arrayOf("unforce"))
            runCatching { XposedCompat.callMethod(controller, "exitForceIdleLocked") }
            isForcedIdle = false
            SMilletLogger.info("[Doze] 屏幕点亮，已退出强制深度 Doze")
        }
    }

    /**
     * 自动修复可能残留的下划线格式 bgControl (no_restrict -> noRestrict, miui_auto -> miuiAuto)，
     * 确保 HyperOS 手机管家与电量与性能 (PowerKeeper) 数据库 100% 正常解析。
     */
    private fun repairLegacyPowerKeeperRowsIfNeeded(context: Context?) {
        if (hasRepairedLegacyRows) return
        val ctx = context ?: return
        hasRepairedLegacyRows = true
        runCatching {
            val tableUri = Uri.parse(PK_USER_TABLE_URI)
            val resolver = ctx.contentResolver
            val fixNoRestrict = ContentValues().apply {
                put("bgControl", BG_CONTROL_NO_RESTRICT)
                put("bgDelayMin", -2)
            }
            resolver.update(tableUri, fixNoRestrict, "bgControl = ?", arrayOf("no_restrict"))

            val fixMiuiAuto = ContentValues().apply {
                put("bgControl", BG_CONTROL_MIUI_AUTO)
                put("bgDelayMin", -2)
            }
            resolver.update(tableUri, fixMiuiAuto, "bgControl = ?", arrayOf("miui_auto"))
        }
    }

    /**
     * 同步 HyperOS 电量与性能 (com.miui.powerkeeper) userTable：
     * - ignoreBatteryOpt == true  -> bgControl = "noRestrict", bgDelayMin = -2 (无限制)
     * - ignoreBatteryOpt == false -> bgControl = "miuiAuto",   bgDelayMin = -2 (智能限制后台)
     */
    private fun syncHyperOsPowerKeeperBgControl(context: Context?, pkg: String, ignoreBatteryOpt: Boolean) {
        val ctx = context ?: return
        repairLegacyPowerKeeperRowsIfNeeded(ctx)
        val dbMode = if (ignoreBatteryOpt) BG_CONTROL_NO_RESTRICT else BG_CONTROL_MIUI_AUTO
        val resolver = ctx.contentResolver

        runCatching {
            val tableUri = Uri.parse(PK_USER_TABLE_URI)
            val updateValues = ContentValues().apply {
                put("bgControl", dbMode)
                put("bgDelayMin", -2)
            }
            var updated = resolver.update(
                tableUri,
                updateValues,
                "pkgName = ? AND userId = ?",
                arrayOf(pkg, "0")
            )
            if (updated <= 0) {
                updated = resolver.update(tableUri, updateValues, "pkgName = ?", arrayOf(pkg))
            }
            if (updated <= 0) {
                val insertValues = ContentValues().apply {
                    put("userId", 0)
                    put("pkgName", pkg)
                    put("bgControl", dbMode)
                    put("bgDelayMin", -2)
                }
                resolver.insert(tableUri, insertValues)
            }
            resolver.notifyChange(tableUri, null)
        }

        runCatching {
            val raw = Settings.System.getString(resolver, "MILLET_NO_RESTRICT_APP").orEmpty()
            val set = raw.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableSet()
            val changed = if (ignoreBatteryOpt) set.add(pkg) else set.remove(pkg)
            if (changed) {
                Settings.System.putString(resolver, "MILLET_NO_RESTRICT_APP", set.joinToString(","))
            }
        }
    }

    fun applyBatteryOptimizationSets(force: Boolean = false) {
        val sysCtx = SystemServerHook.getSystemContext()
        repairLegacyPowerKeeperRowsIfNeeded(sysCtx)

        val cfg = SMilletLogger.config
        if (cfg.batteryOptWhiteSet.isEmpty() && cfg.batteryOptBlackSet.isEmpty()) {
            return
        }
        val whiteChanged = force || cfg.batteryOptWhiteSet != lastAppliedWhiteSet
        val blackChanged = force || cfg.batteryOptBlackSet != lastAppliedBlackSet
        if (!whiteChanged && !blackChanged) {
            return
        }

        val token = Binder.clearCallingIdentity()
        try {
            val controller = resolveDeviceIdleController()
            val binder = resolveDeviceIdleBinder()

            if (whiteChanged && cfg.batteryOptWhiteSet.isNotEmpty()) {
                if (controller != null) {
                    runCatching {
                        XposedCompat.callMethod(
                            controller,
                            "addPowerSaveWhitelistAppsInternal",
                            ArrayList(cfg.batteryOptWhiteSet)
                        )
                    }
                }
                for (pkg in cfg.batteryOptWhiteSet) {
                    if (controller != null) {
                        runCatching { XposedCompat.callMethod(controller, "addPowerSaveWhitelistAppInternal", pkg) }
                        runCatching { executeDeviceIdleShell(controller, arrayOf("whitelist", "+$pkg")) }
                    }
                    if (binder != null) {
                        runCatching { XposedCompat.callMethod(binder, "addPowerSaveWhitelistApp", pkg) }
                    }
                    syncHyperOsPowerKeeperBgControl(sysCtx, pkg, ignoreBatteryOpt = true)
                }
                lastAppliedWhiteSet = HashSet(cfg.batteryOptWhiteSet)
                SMilletLogger.info("已同步电池未优化白名单与 HyperOS「无限制 (noRestrict)」策略：${cfg.batteryOptWhiteSet.joinToString(", ")}")
            } else if (whiteChanged) {
                lastAppliedWhiteSet = emptySet()
            }

            if (blackChanged && cfg.batteryOptBlackSet.isNotEmpty()) {
                for (pkg in cfg.batteryOptBlackSet) {
                    if (controller != null) {
                        runCatching { XposedCompat.callMethod(controller, "removePowerSaveWhitelistAppInternal", pkg) }
                        runCatching { XposedCompat.callMethod(controller, "removeSystemPowerWhitelistAppInternal", pkg) }
                        runCatching { executeDeviceIdleShell(controller, arrayOf("whitelist", "-$pkg")) }
                        runCatching { executeDeviceIdleShell(controller, arrayOf("sys-whitelist", "-$pkg")) }
                        runCatching { executeDeviceIdleShell(controller, arrayOf("except-idle-whitelist", "-$pkg")) }
                        runCatching {
                            synchronized(controller) {
                                var removed = false
                                listOf(
                                    "mPowerSaveWhitelistUserApps",
                                    "mPowerSaveWhitelistApps",
                                    "mPowerSaveWhitelistAppsExceptIdle"
                                ).forEach { fieldName ->
                                    val map = runCatching {
                                        XposedCompat.getObjectField(controller, fieldName) as? MutableMap<*, *>
                                    }.getOrNull()
                                    if (map != null && map.remove(pkg) != null) {
                                        removed = true
                                    }
                                }
                                if (removed) {
                                    runCatching { XposedCompat.callMethod(controller, "updateWhitelistAppIdsLocked") }
                                    runCatching { XposedCompat.callMethod(controller, "writeConfigFileLocked") }
                                }
                            }
                        }
                    }
                    if (binder != null) {
                        runCatching { XposedCompat.callMethod(binder, "removePowerSaveWhitelistApp", pkg) }
                        runCatching { XposedCompat.callMethod(binder, "removeSystemPowerWhitelistApp", pkg) }
                    }
                    syncHyperOsPowerKeeperBgControl(sysCtx, pkg, ignoreBatteryOpt = false)
                }
                lastAppliedBlackSet = HashSet(cfg.batteryOptBlackSet)
                SMilletLogger.info("已恢复电池优化与 HyperOS「智能限制后台 (miuiAuto)」策略：${cfg.batteryOptBlackSet.joinToString(", ")}")
            } else if (blackChanged) {
                lastAppliedBlackSet = emptySet()
            }
        } catch (t: Throwable) {
            SMilletLogger.error("同步电池优化白名单与 HyperOS 省电策略失败", t)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun formatDurationSec(totalSec: Long): String {
        return when {
            totalSec < 60L -> "${totalSec}秒"
            totalSec < 3600L -> "${totalSec / 60L}分${totalSec % 60L}秒"
            else -> "${totalSec / 3600L}小时${(totalSec % 3600L) / 60L}分钟"
        }
    }

    private fun recordBatteryOnScreenOff(context: Context) {
        val bm = context.getSystemService(BatteryManager::class.java) ?: return
        screenOffTimestamp = System.currentTimeMillis()
        screenOffBatteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        screenOffBatteryCounter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) / 1000
        SMilletLogger.info(
            "息屏电量记录：当前电量 ${screenOffBatteryPct}% (${screenOffBatteryCounter} mAh)"
        )
    }

    private fun logBatteryDrainOnScreenOn(context: Context) {
        if (screenOffTimestamp <= 0L) return
        val bm = context.getSystemService(BatteryManager::class.java) ?: return
        val durationSec = ((System.currentTimeMillis() - screenOffTimestamp) / 1000L).coerceAtLeast(0L)
        val currentPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val currentMah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) / 1000
        val diffPct = screenOffBatteryPct - currentPct
        val diffMah = screenOffBatteryCounter - currentMah
        val durationStr = formatDurationSec(durationSec)
        val actionSummary = if (diffPct >= 0) {
            "消耗 ${screenOffBatteryPct}% -> ${currentPct}% (-${diffPct}% / -${diffMah} mAh)"
        } else {
            "充电 ${screenOffBatteryPct}% -> ${currentPct}% (+${-diffPct}% / +${-diffMah} mAh)"
        }
        SMilletLogger.info(
            "亮屏待机统计：当前电量 ${currentPct}% · $actionSummary · 待机时长 $durationStr"
        )
    }
}

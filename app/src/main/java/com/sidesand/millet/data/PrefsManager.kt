package com.sidesand.millet.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.sidesand.millet.hook.XposedCompat
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class PrefsManager(
    private val primaryPrefs: SharedPreferences?,
    private val mirrorPrefs: SharedPreferences? = null
) {

    companion object {
        const val SYSTEM_CONFIG_FILE = "/data/system/SMillet/config.json"

        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
        private val listenerRegistered = AtomicBoolean(false)
        private val serviceStateListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

        @Volatile
        var xposedService: XposedService? = null
            private set

        @Volatile
        var isLspServiceConnected: Boolean = false
            private set

        @Volatile
        var lspFrameworkVersion: String? = null
            private set

        @Volatile
        var cachedSystemSnapshot: MilletConfigSnapshot? = null

        @Volatile
        var cachedSystemJson: String? = null

        private fun normalizeLogLevel(raw: String?): String {
            return when (raw?.lowercase(Locale.ROOT)?.trim()) {
                "debug", "调试" -> "debug"
                "none", "off", "无" -> "none"
                else -> "info"
            }
        }

        private fun normalizeUiScale(raw: Int): Int {
            return when {
                raw <= 85 -> 80
                raw <= 95 -> 90
                raw <= 105 -> 100
                else -> 110
            }
        }

        fun ensureServiceListenerRegistered(onStateChanged: ((Boolean) -> Unit)? = null) {
            if (onStateChanged != null) {
                serviceStateListeners.add(onStateChanged)
            }
            if (listenerRegistered.compareAndSet(false, true)) {
                runCatching {
                    XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                        override fun onServiceBind(service: XposedService) {
                            xposedService = service
                            isLspServiceConnected = true
                            runCatching {
                                val fwName = service.frameworkName.ifBlank { "LSPosed" }
                                val fwVer = service.frameworkVersion
                                val fwCode = service.frameworkVersionCode
                                lspFrameworkVersion = "$fwName $fwVer ($fwCode)"
                            }
                            serviceStateListeners.forEach { it.invoke(true) }
                        }

                        override fun onServiceDied(service: XposedService) {
                            if (xposedService === service) {
                                xposedService = null
                            }
                            isLspServiceConnected = false
                            serviceStateListeners.forEach { it.invoke(false) }
                        }
                    })
                }
            }
        }

        fun isModuleActivatedInLsp(context: Context): Boolean {
            ensureServiceListenerRegistered()
            return isLspServiceConnected || xposedService != null
        }

        private fun openPrefs(ctx: Context): SharedPreferences {
            return ctx.getSharedPreferences(ConfigKeys.PREFS_NAME, Context.MODE_PRIVATE)
        }

        fun fromContext(context: Context): PrefsManager {
            ensureServiceListenerRegistered()
            val deCtx = runCatching { context.createDeviceProtectedStorageContext() }.getOrNull()
            val primary = if (deCtx != null) openPrefs(deCtx) else openPrefs(context)
            val mirror = if (deCtx != null) runCatching { openPrefs(context) }.getOrNull() else null
            if (primary.all.isEmpty() && mirror != null && mirror.all.isNotEmpty()) {
                val snap = readSnapshot(mirror)
                writeSnapshotToPrefs(primary, snap)
            }
            return PrefsManager(primary, mirror)
        }

        fun loadFromXposed(): MilletConfigSnapshot {
            cachedSystemSnapshot?.let { return it }
            runCatching {
                val sysFile = File(SYSTEM_CONFIG_FILE)
                if (sysFile.exists() && sysFile.canRead()) {
                    val text = sysFile.readText()
                    parseSnapshotFromJson(text)?.let {
                        cachedSystemSnapshot = it
                        cachedSystemJson = text
                        return it
                    }
                }
            }
            runCatching {
                val remotePrefs = XposedCompat.module?.getRemotePreferences(ConfigKeys.PREFS_NAME)
                if (remotePrefs != null && remotePrefs.all.isNotEmpty()) {
                    return readSnapshot(remotePrefs).also { cachedSystemSnapshot = it }
                }
            }
            return cachedSystemSnapshot ?: MilletConfigSnapshot()
        }

        fun saveSystemConfigFile(jsonText: String): MilletConfigSnapshot? {
            val parsed = parseSnapshotFromJson(jsonText) ?: return null
            cachedSystemSnapshot = parsed
            cachedSystemJson = jsonText
            runCatching {
                val file = File(SYSTEM_CONFIG_FILE)
                file.parentFile?.mkdirs()
                file.writeText(jsonText)
            }
            return parsed
        }

        fun readSystemConfigJson(): String? {
            cachedSystemJson?.takeIf { it.isNotBlank() }?.let { return it }
            return runCatching {
                val file = File(SYSTEM_CONFIG_FILE)
                if (file.exists() && file.canRead()) {
                    file.readText().also { cachedSystemJson = it }
                } else {
                    null
                }
            }.getOrNull()
        }

        fun serializeSnapshotToJson(s: MilletConfigSnapshot): String {
            val root = JsonObject().apply {
                add("MilletSettings", gson.toJsonTree(mapOf(
                    ConfigKeys.DISABLE_KILL to s.disableKill,
                    ConfigKeys.DISABLE_VOLUME to s.disableVolume,
                    ConfigKeys.DISABLE_CHARGE to s.disableCharge,
                    ConfigKeys.BLOCK_ADD_POWERSAVE_WHITELIST to s.blockAddPowerSaveWhitelistApps,
                    ConfigKeys.BATTERY_CHANGE_OBSERVE to s.batteryChangeObserve
                )))
                add("DozeSettings", gson.toJsonTree(mapOf(
                    ConfigKeys.ENABLE_DOZE to s.enableDoze,
                    ConfigKeys.SKIP_STATE_CHECK to s.skipStateCheck,
                    ConfigKeys.DISABLE_MOTION_LISTENER to s.disableMotionListener
                )))
                add("BatteryOptSettings", gson.toJsonTree(mapOf(
                    ConfigKeys.BATTERY_OPT_WHITE_SET to s.batteryOptWhiteSet.toSortedSet(),
                    ConfigKeys.BATTERY_OPT_BLACK_SET to s.batteryOptBlackSet.toSortedSet()
                )))
                add("AppSettings", gson.toJsonTree(mapOf(
                    ConfigKeys.WHITE_APP_SET to s.whiteAppSet.toSortedSet(),
                    ConfigKeys.MESSAGE_APP_SET to s.messageAppSet.toSortedSet(),
                    ConfigKeys.FORCE_FREEZE_SET to s.forceFreezeSet.toSortedSet(),
                    ConfigKeys.BLOCK_THAW_SET to s.blockThawSet.toSortedSet(),
                    ConfigKeys.FORBID_SYNC_BINDER_SET to s.forbidSyncBinderSet.toSortedSet(),
                    ConfigKeys.NOT_FREEZE_WHEN_HAS_FGS_SET to s.notFreezeWhenHasFgsSet.toSortedSet()
                )))
                add("LogSettings", gson.toJsonTree(mapOf(
                    ConfigKeys.LOG_LEVEL to normalizeLogLevel(s.logLevel)
                )))
                add("AppearanceSettings", gson.toJsonTree(mapOf(
                    ConfigKeys.THEME_COLOR_MODE to s.themeColorMode.coerceIn(0, 2),
                    ConfigKeys.MONET_ENABLED to s.monetEnabled,
                    ConfigKeys.BLUR_ENABLED to s.blurEnabled,
                    ConfigKeys.TOP_BAR_BLUR_MODE to s.topBarBlurMode.coerceIn(0, 1),
                    ConfigKeys.PREDICTIVE_BACK_ENABLED to s.predictiveBackEnabled,
                    ConfigKeys.UI_SCALE to normalizeUiScale(s.uiScale),
                    ConfigKeys.SWIPE_BACK_ENABLED to s.swipeBackEnabled,
                    ConfigKeys.FLOATING_BOTTOM_BAR to s.floatingBottomBar,
                    ConfigKeys.FLOATING_BOTTOM_BAR_STYLE to s.floatingBottomBarStyle.coerceIn(0, 1),
                    ConfigKeys.BOTTOM_BAR_MODE to s.bottomBarMode.coerceIn(0, 1),
                    ConfigKeys.HIDE_TASK_CARD to s.hideTaskCard
                )))
            }
            return gson.toJson(root)
        }

        fun parseSnapshotFromJson(jsonText: String): MilletConfigSnapshot? {
            val cleaned = jsonText.trim().removePrefix("\uFEFF")
            if (cleaned.isBlank()) return null
            return try {
                val startIdx = cleaned.indexOf('{')
                val endIdx = cleaned.lastIndexOf('}')
                val jsonSlice = if (startIdx >= 0 && endIdx > startIdx) {
                    cleaned.substring(startIdx, endIdx + 1)
                } else {
                    cleaned
                }
                val root = gson.fromJson(jsonSlice, JsonObject::class.java) ?: return null
                val s = MilletConfigSnapshot()

                root.getAsJsonObject("MilletSettings")?.let { obj ->
                    obj.get(ConfigKeys.DISABLE_KILL)?.asBoolean?.let { s.disableKill = it }
                    obj.get(ConfigKeys.DISABLE_VOLUME)?.asBoolean?.let { s.disableVolume = it }
                    obj.get(ConfigKeys.DISABLE_CHARGE)?.asBoolean?.let { s.disableCharge = it }
                    obj.get(ConfigKeys.BLOCK_ADD_POWERSAVE_WHITELIST)?.asBoolean?.let {
                        s.blockAddPowerSaveWhitelistApps = it
                    }
                    obj.get(ConfigKeys.BATTERY_CHANGE_OBSERVE)?.asBoolean?.let {
                        s.batteryChangeObserve = it
                    }
                }

                root.getAsJsonObject("DozeSettings")?.let { obj ->
                    obj.get(ConfigKeys.ENABLE_DOZE)?.asBoolean?.let { s.enableDoze = it }
                    obj.get(ConfigKeys.SKIP_STATE_CHECK)?.asBoolean?.let { s.skipStateCheck = it }
                    obj.get(ConfigKeys.DISABLE_MOTION_LISTENER)?.asBoolean?.let {
                        s.disableMotionListener = it
                    }
                }

                fun parseSet(parent: JsonObject?, key: String): Set<String>? {
                    val arr = parent?.getAsJsonArray(key) ?: return null
                    return arr.mapNotNull { it.asString }.toSet()
                }

                root.getAsJsonObject("BatteryOptSettings")?.let { obj ->
                    parseSet(obj, ConfigKeys.BATTERY_OPT_WHITE_SET)?.let { s.batteryOptWhiteSet = it }
                    parseSet(obj, ConfigKeys.BATTERY_OPT_BLACK_SET)?.let { s.batteryOptBlackSet = it }
                }

                root.getAsJsonObject("AppSettings")?.let { obj ->
                    parseSet(obj, ConfigKeys.WHITE_APP_SET)?.let { s.whiteAppSet = it }
                    parseSet(obj, ConfigKeys.MESSAGE_APP_SET)?.let { s.messageAppSet = it }
                    parseSet(obj, ConfigKeys.FORCE_FREEZE_SET)?.let { s.forceFreezeSet = it }
                    parseSet(obj, ConfigKeys.BLOCK_THAW_SET)?.let { s.blockThawSet = it }
                    parseSet(obj, ConfigKeys.FORBID_SYNC_BINDER_SET)?.let { s.forbidSyncBinderSet = it }
                    parseSet(obj, ConfigKeys.NOT_FREEZE_WHEN_HAS_FGS_SET)?.let { s.notFreezeWhenHasFgsSet = it }
                }

                root.getAsJsonObject("LogSettings")?.let { obj ->
                    obj.get(ConfigKeys.LOG_LEVEL)?.asString?.let { s.logLevel = normalizeLogLevel(it) }
                }

                root.getAsJsonObject("AppearanceSettings")?.let { obj ->
                    obj.get(ConfigKeys.THEME_COLOR_MODE)?.asInt?.let { s.themeColorMode = it.coerceIn(0, 2) }
                    obj.get(ConfigKeys.MONET_ENABLED)?.asBoolean?.let { s.monetEnabled = it }
                    obj.get(ConfigKeys.BLUR_ENABLED)?.asBoolean?.let { s.blurEnabled = it }
                    obj.get(ConfigKeys.TOP_BAR_BLUR_MODE)?.asInt?.let { s.topBarBlurMode = it.coerceIn(0, 1) }
                    obj.get(ConfigKeys.PREDICTIVE_BACK_ENABLED)?.asBoolean?.let { s.predictiveBackEnabled = it }
                    obj.get(ConfigKeys.UI_SCALE)?.asInt?.let { s.uiScale = normalizeUiScale(it) }
                    obj.get(ConfigKeys.SWIPE_BACK_ENABLED)?.asBoolean?.let { s.swipeBackEnabled = it }
                    obj.get(ConfigKeys.FLOATING_BOTTOM_BAR)?.asBoolean?.let { s.floatingBottomBar = it }
                    obj.get(ConfigKeys.FLOATING_BOTTOM_BAR_STYLE)?.asInt?.let { s.floatingBottomBarStyle = it.coerceIn(0, 1) }
                    obj.get(ConfigKeys.BOTTOM_BAR_MODE)?.asInt?.let { s.bottomBarMode = it.coerceIn(0, 1) }
                    obj.get(ConfigKeys.HIDE_TASK_CARD)?.asBoolean?.let { s.hideTaskCard = it }
                }

                // 兼容扁平 JSON 结构直接恢复
                root.get(ConfigKeys.DISABLE_KILL)?.takeIf { it.isJsonPrimitive }?.asBoolean?.let { s.disableKill = it }
                root.get(ConfigKeys.DISABLE_VOLUME)?.takeIf { it.isJsonPrimitive }?.asBoolean?.let { s.disableVolume = it }
                root.get(ConfigKeys.DISABLE_CHARGE)?.takeIf { it.isJsonPrimitive }?.asBoolean?.let { s.disableCharge = it }
                root.get(ConfigKeys.BLOCK_ADD_POWERSAVE_WHITELIST)?.takeIf { it.isJsonPrimitive }?.asBoolean?.let {
                    s.blockAddPowerSaveWhitelistApps = it
                }
                root.get(ConfigKeys.BATTERY_CHANGE_OBSERVE)?.takeIf { it.isJsonPrimitive }?.asBoolean?.let {
                    s.batteryChangeObserve = it
                }
                root.get(ConfigKeys.ENABLE_DOZE)?.takeIf { it.isJsonPrimitive }?.asBoolean?.let { s.enableDoze = it }
                root.get(ConfigKeys.SKIP_STATE_CHECK)?.takeIf { it.isJsonPrimitive }?.asBoolean?.let { s.skipStateCheck = it }
                root.get(ConfigKeys.DISABLE_MOTION_LISTENER)?.takeIf { it.isJsonPrimitive }?.asBoolean?.let {
                    s.disableMotionListener = it
                }
                parseSet(root, ConfigKeys.BATTERY_OPT_WHITE_SET)?.let { s.batteryOptWhiteSet = it }
                parseSet(root, ConfigKeys.BATTERY_OPT_BLACK_SET)?.let { s.batteryOptBlackSet = it }
                parseSet(root, ConfigKeys.WHITE_APP_SET)?.let { s.whiteAppSet = it }
                parseSet(root, ConfigKeys.MESSAGE_APP_SET)?.let { s.messageAppSet = it }
                parseSet(root, ConfigKeys.FORCE_FREEZE_SET)?.let { s.forceFreezeSet = it }
                parseSet(root, ConfigKeys.BLOCK_THAW_SET)?.let { s.blockThawSet = it }
                parseSet(root, ConfigKeys.FORBID_SYNC_BINDER_SET)?.let { s.forbidSyncBinderSet = it }
                parseSet(root, ConfigKeys.NOT_FREEZE_WHEN_HAS_FGS_SET)?.let { s.notFreezeWhenHasFgsSet = it }
                root.get(ConfigKeys.LOG_LEVEL)?.takeIf { it.isJsonPrimitive }?.asString?.let {
                    s.logLevel = normalizeLogLevel(it)
                }
                root.get(ConfigKeys.THEME_COLOR_MODE)?.takeIf { it.isJsonPrimitive }?.asInt?.let {
                    s.themeColorMode = it.coerceIn(0, 2)
                }
                root.get(ConfigKeys.MONET_ENABLED)?.takeIf { it.isJsonPrimitive }?.asBoolean?.let {
                    s.monetEnabled = it
                }
                root.get(ConfigKeys.BLUR_ENABLED)?.takeIf { it.isJsonPrimitive }?.asBoolean?.let {
                    s.blurEnabled = it
                }
                root.get(ConfigKeys.TOP_BAR_BLUR_MODE)?.takeIf { it.isJsonPrimitive }?.asInt?.let {
                    s.topBarBlurMode = it.coerceIn(0, 1)
                }
                root.get(ConfigKeys.PREDICTIVE_BACK_ENABLED)?.takeIf { it.isJsonPrimitive }?.asBoolean?.let {
                    s.predictiveBackEnabled = it
                }
                root.get(ConfigKeys.UI_SCALE)?.takeIf { it.isJsonPrimitive }?.asInt?.let {
                    s.uiScale = normalizeUiScale(it)
                }
                root.get(ConfigKeys.SWIPE_BACK_ENABLED)?.takeIf { it.isJsonPrimitive }?.asBoolean?.let {
                    s.swipeBackEnabled = it
                }
                root.get(ConfigKeys.FLOATING_BOTTOM_BAR)?.takeIf { it.isJsonPrimitive }?.asBoolean?.let {
                    s.floatingBottomBar = it
                }
                root.get(ConfigKeys.FLOATING_BOTTOM_BAR_STYLE)?.takeIf { it.isJsonPrimitive }?.asInt?.let {
                    s.floatingBottomBarStyle = it.coerceIn(0, 1)
                }
                root.get(ConfigKeys.BOTTOM_BAR_MODE)?.takeIf { it.isJsonPrimitive }?.asInt?.let {
                    s.bottomBarMode = it.coerceIn(0, 1)
                }
                root.get(ConfigKeys.HIDE_TASK_CARD)?.takeIf { it.isJsonPrimitive }?.asBoolean?.let {
                    s.hideTaskCard = it
                }
                s
            } catch (_: Throwable) {
                null
            }
        }

        fun readSnapshot(sp: SharedPreferences): MilletConfigSnapshot {
            return MilletConfigSnapshot(
                disableKill = sp.getBoolean(ConfigKeys.DISABLE_KILL, true),
                disableVolume = sp.getBoolean(ConfigKeys.DISABLE_VOLUME, true),
                disableCharge = sp.getBoolean(ConfigKeys.DISABLE_CHARGE, true),
                blockAddPowerSaveWhitelistApps = sp.getBoolean(ConfigKeys.BLOCK_ADD_POWERSAVE_WHITELIST, true),
                batteryChangeObserve = sp.getBoolean(ConfigKeys.BATTERY_CHANGE_OBSERVE, false),
                enableDoze = sp.getBoolean(ConfigKeys.ENABLE_DOZE, true),
                skipStateCheck = sp.getBoolean(ConfigKeys.SKIP_STATE_CHECK, true),
                disableMotionListener = sp.getBoolean(ConfigKeys.DISABLE_MOTION_LISTENER, true),
                batteryOptWhiteSet = sp.getStringSet(ConfigKeys.BATTERY_OPT_WHITE_SET, emptySet())?.toSet() ?: emptySet(),
                batteryOptBlackSet = sp.getStringSet(ConfigKeys.BATTERY_OPT_BLACK_SET, emptySet())?.toSet() ?: emptySet(),
                whiteAppSet = sp.getStringSet(ConfigKeys.WHITE_APP_SET, emptySet())?.toSet() ?: emptySet(),
                messageAppSet = sp.getStringSet(ConfigKeys.MESSAGE_APP_SET, emptySet())?.toSet() ?: emptySet(),
                forceFreezeSet = sp.getStringSet(ConfigKeys.FORCE_FREEZE_SET, emptySet())?.toSet() ?: emptySet(),
                blockThawSet = sp.getStringSet(ConfigKeys.BLOCK_THAW_SET, emptySet())?.toSet() ?: emptySet(),
                forbidSyncBinderSet = sp.getStringSet(ConfigKeys.FORBID_SYNC_BINDER_SET, emptySet())?.toSet() ?: emptySet(),
                notFreezeWhenHasFgsSet = sp.getStringSet(ConfigKeys.NOT_FREEZE_WHEN_HAS_FGS_SET, emptySet())?.toSet() ?: emptySet(),
                logLevel = normalizeLogLevel(sp.getString(ConfigKeys.LOG_LEVEL, "info")),
                themeColorMode = sp.getInt(ConfigKeys.THEME_COLOR_MODE, 0).coerceIn(0, 2),
                monetEnabled = sp.getBoolean(ConfigKeys.MONET_ENABLED, false),
                blurEnabled = sp.getBoolean(ConfigKeys.BLUR_ENABLED, true),
                topBarBlurMode = sp.getInt(ConfigKeys.TOP_BAR_BLUR_MODE, 0).coerceIn(0, 1),
                predictiveBackEnabled = sp.getBoolean(ConfigKeys.PREDICTIVE_BACK_ENABLED, true),
                uiScale = normalizeUiScale(sp.getInt(ConfigKeys.UI_SCALE, 100)),
                swipeBackEnabled = sp.getBoolean(ConfigKeys.SWIPE_BACK_ENABLED, true),
                floatingBottomBar = sp.getBoolean(ConfigKeys.FLOATING_BOTTOM_BAR, false),
                floatingBottomBarStyle = sp.getInt(ConfigKeys.FLOATING_BOTTOM_BAR_STYLE, 0).coerceIn(0, 1),
                bottomBarMode = sp.getInt(ConfigKeys.BOTTOM_BAR_MODE, 0).coerceIn(0, 1),
                hideTaskCard = sp.getBoolean(ConfigKeys.HIDE_TASK_CARD, false)
            )
        }

        private fun writeSnapshotToPrefs(sp: SharedPreferences, parsed: MilletConfigSnapshot) {
            sp.edit().apply {
                remove("isLog")
                remove("simplifyLog")
                remove("dozeLog")
                remove("pageSwitchGesture")
                remove("monetColorIndex")
                putBoolean(ConfigKeys.DISABLE_KILL, parsed.disableKill)
                putBoolean(ConfigKeys.DISABLE_VOLUME, parsed.disableVolume)
                putBoolean(ConfigKeys.DISABLE_CHARGE, parsed.disableCharge)
                putBoolean(ConfigKeys.BLOCK_ADD_POWERSAVE_WHITELIST, parsed.blockAddPowerSaveWhitelistApps)
                putBoolean(ConfigKeys.BATTERY_CHANGE_OBSERVE, parsed.batteryChangeObserve)
                putBoolean(ConfigKeys.ENABLE_DOZE, parsed.enableDoze)
                putBoolean(ConfigKeys.SKIP_STATE_CHECK, parsed.skipStateCheck)
                putBoolean(ConfigKeys.DISABLE_MOTION_LISTENER, parsed.disableMotionListener)
                putStringSet(ConfigKeys.BATTERY_OPT_WHITE_SET, HashSet(parsed.batteryOptWhiteSet))
                putStringSet(ConfigKeys.BATTERY_OPT_BLACK_SET, HashSet(parsed.batteryOptBlackSet))
                putStringSet(ConfigKeys.WHITE_APP_SET, HashSet(parsed.whiteAppSet))
                putStringSet(ConfigKeys.MESSAGE_APP_SET, HashSet(parsed.messageAppSet))
                putStringSet(ConfigKeys.FORCE_FREEZE_SET, HashSet(parsed.forceFreezeSet))
                putStringSet(ConfigKeys.BLOCK_THAW_SET, HashSet(parsed.blockThawSet))
                putStringSet(ConfigKeys.FORBID_SYNC_BINDER_SET, HashSet(parsed.forbidSyncBinderSet))
                putStringSet(ConfigKeys.NOT_FREEZE_WHEN_HAS_FGS_SET, HashSet(parsed.notFreezeWhenHasFgsSet))
                putString(ConfigKeys.LOG_LEVEL, normalizeLogLevel(parsed.logLevel))
                putInt(ConfigKeys.THEME_COLOR_MODE, parsed.themeColorMode.coerceIn(0, 2))
                putBoolean(ConfigKeys.MONET_ENABLED, parsed.monetEnabled)
                putBoolean(ConfigKeys.BLUR_ENABLED, parsed.blurEnabled)
                putInt(ConfigKeys.TOP_BAR_BLUR_MODE, parsed.topBarBlurMode.coerceIn(0, 1))
                putBoolean(ConfigKeys.PREDICTIVE_BACK_ENABLED, parsed.predictiveBackEnabled)
                putInt(ConfigKeys.UI_SCALE, normalizeUiScale(parsed.uiScale))
                putBoolean(ConfigKeys.SWIPE_BACK_ENABLED, parsed.swipeBackEnabled)
                putBoolean(ConfigKeys.FLOATING_BOTTOM_BAR, parsed.floatingBottomBar)
                putInt(ConfigKeys.FLOATING_BOTTOM_BAR_STYLE, parsed.floatingBottomBarStyle.coerceIn(0, 1))
                putInt(ConfigKeys.BOTTOM_BAR_MODE, parsed.bottomBarMode.coerceIn(0, 1))
                putBoolean(ConfigKeys.HIDE_TASK_CARD, parsed.hideTaskCard)
            }.commit()
        }
    }

    private fun editAll(block: SharedPreferences.Editor.() -> Unit) {
        primaryPrefs?.edit()?.apply(block)?.commit()
        mirrorPrefs?.edit()?.apply(block)?.commit()
        runCatching {
            xposedService?.getRemotePreferences(ConfigKeys.PREFS_NAME)?.edit()?.apply(block)?.apply()
        }
    }

    fun snapshot(): MilletConfigSnapshot {
        val sp = primaryPrefs ?: return MilletConfigSnapshot()
        return readSnapshot(sp)
    }

    fun putBoolean(key: String, value: Boolean) {
        editAll { putBoolean(key, value) }
    }

    fun putInt(key: String, value: Int) {
        editAll { putInt(key, value) }
    }

    fun putString(key: String, value: String) {
        editAll { putString(key, value) }
    }

    fun getStringSet(key: String, default: Set<String> = emptySet()): Set<String> =
        primaryPrefs?.getStringSet(key, default)?.toSet() ?: default

    fun putStringSet(key: String, value: Set<String>) {
        val copy = HashSet(value)
        editAll { putStringSet(key, copy) }
    }

    fun updateAppSet(key: String, packageName: String, add: Boolean): Set<String> {
        val current = getStringSet(key).toMutableSet()
        if (add) current.add(packageName) else current.remove(packageName)
        putStringSet(key, current)
        return current
    }

    fun setBatteryOptimization(packageName: String, ignoreBatteryOpt: Boolean) {
        val white = getStringSet(ConfigKeys.BATTERY_OPT_WHITE_SET).toMutableSet()
        val black = getStringSet(ConfigKeys.BATTERY_OPT_BLACK_SET).toMutableSet()
        if (ignoreBatteryOpt) {
            white.add(packageName)
            black.remove(packageName)
        } else {
            white.remove(packageName)
            black.add(packageName)
        }
        editAll {
            putStringSet(ConfigKeys.BATTERY_OPT_WHITE_SET, HashSet(white))
            putStringSet(ConfigKeys.BATTERY_OPT_BLACK_SET, HashSet(black))
        }
    }

    fun setWhiteListApp(packageName: String, enabled: Boolean) {
        val white = getStringSet(ConfigKeys.WHITE_APP_SET).toMutableSet()
        if (enabled) white.add(packageName) else white.remove(packageName)
        if (!enabled) {
            putStringSet(ConfigKeys.WHITE_APP_SET, white)
            return
        }
        val msg = getStringSet(ConfigKeys.MESSAGE_APP_SET).toMutableSet().apply { remove(packageName) }
        val force = getStringSet(ConfigKeys.FORCE_FREEZE_SET).toMutableSet().apply { remove(packageName) }
        val block = getStringSet(ConfigKeys.BLOCK_THAW_SET).toMutableSet().apply { remove(packageName) }
        val forbid = getStringSet(ConfigKeys.FORBID_SYNC_BINDER_SET).toMutableSet().apply { remove(packageName) }
        val fgs = getStringSet(ConfigKeys.NOT_FREEZE_WHEN_HAS_FGS_SET).toMutableSet().apply { remove(packageName) }
        editAll {
            putStringSet(ConfigKeys.WHITE_APP_SET, HashSet(white))
            putStringSet(ConfigKeys.MESSAGE_APP_SET, HashSet(msg))
            putStringSet(ConfigKeys.FORCE_FREEZE_SET, HashSet(force))
            putStringSet(ConfigKeys.BLOCK_THAW_SET, HashSet(block))
            putStringSet(ConfigKeys.FORBID_SYNC_BINDER_SET, HashSet(forbid))
            putStringSet(ConfigKeys.NOT_FREEZE_WHEN_HAS_FGS_SET, HashSet(fgs))
        }
    }

    fun exportBackupJson(): String = serializeSnapshotToJson(snapshot())

    fun importBackupJson(jsonText: String): Boolean {
        val parsed = parseSnapshotFromJson(jsonText) ?: return false
        primaryPrefs?.let { writeSnapshotToPrefs(it, parsed) }
        mirrorPrefs?.let { writeSnapshotToPrefs(it, parsed) }
        runCatching {
            xposedService?.getRemotePreferences(ConfigKeys.PREFS_NAME)?.let { writeSnapshotToPrefs(it, parsed) }
        }
        return true
    }
}

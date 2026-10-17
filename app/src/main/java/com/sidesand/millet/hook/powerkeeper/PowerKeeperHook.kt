package com.sidesand.millet.hook.powerkeeper

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.Settings
import com.sidesand.millet.data.PrefsManager
import com.sidesand.millet.hook.SMilletLogger
import com.sidesand.millet.hook.XposedCompat
import com.sidesand.millet.hook.XposedCompat.MethodHook
import com.sidesand.millet.hook.XposedCompat.MethodHookParam
import com.sidesand.millet.ipc.BinderBridge
import com.sidesand.millet.ipc.ISystemCallback
import com.sidesand.millet.ipc.SystemInterface

object PowerKeeperHook {

    private const val MODULE_PKG = "com.sidesand.millet"
    private const val LOG_FORWARD_PREFIX = "__LOG__:"
    private const val PK_USER_TABLE_URI = "content://com.miui.powerkeeper.configure/userTable"
    // HyperOS PowerKeeper userTable.bgControl 真实枚举常量（必须为驼峰格式 noRestrict / miuiAuto）
    private const val BG_CONTROL_NO_RESTRICT = "noRestrict"
    private const val BG_CONTROL_MIUI_AUTO = "miuiAuto"

    private val DEFAULT_BRIGHT_WHITE_APPS = listOf(
        "com.xiaomi.smarthome",
        "com.android.vending",
        "com.google.android.gms",
        "com.miui.gallery"
    )

    private val REMOVED_DATA_WHITE_APPS = listOf(
        "com.miui.notes",
        "com.flyersoft.moonreaderp",
        "com.sinovoice.voicebook",
        "com.wyfc.itingtxt2",
        "com.gedoor.monkeybook",
        "com.iflytek.vflynote",
        "com.flyersoft.seekbooks",
        "com.flyersoft.moonreader",
        "com.miui.themestore",
        "com.mipay.wallet",
        "com.tencent.wework"
    )

    @Volatile
    var appContext: Context? = null
        private set

    @Volatile
    var systemService: SystemInterface? = null
        private set

    @Volatile
    var pkClassLoader: ClassLoader? = null
        private set

    @Volatile
    var milletPolicyInstance: Any? = null
        private set

    @Volatile
    var brightScenarioInstance: Any? = null
        private set

    @Volatile
    private var lastAppliedPolicySignature: String? = null

    @Volatile
    private var lastAppliedBgControlSignature: String? = null

    @Volatile
    private var hasRepairedLegacyRows: Boolean = false

    @Volatile
    private var lastKillInterceptLogTime: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    private val pkCallback = object : ISystemCallback.Stub() {
        override fun onConfigChanged(keyOrJson: String) {
            val parsed = if (keyOrJson.trimStart().startsWith("{")) {
                PrefsManager.saveSystemConfigFile(keyOrJson)
                    ?: PrefsManager.parseSnapshotFromJson(keyOrJson)
            } else {
                runCatching {
                    val pulled = systemService?.pullConfigJson().orEmpty()
                    PrefsManager.parseSnapshotFromJson(pulled)
                }.getOrNull() ?: PrefsManager.loadFromXposed()
            }
            if (parsed != null) {
                SMilletLogger.config = parsed
            }
            applyMilletPolicyLists()
            applyBrightPreloadWhitelist()
            applyPowerKeeperBgControlSets()
        }

        override fun requestCheckFreezeReason(packageName: String) {
            val report = runCatching {
                diagnoseUnfreezeReason(packageName)
            }.getOrElse { err ->
                "[PowerKeeper 策略层]\n• 诊断过程发生异常: ${err.message}"
            }
            ensureSystemServiceConnected()
            runCatching {
                systemService?.reportUnfreezeReason(packageName, report)
            }
        }

        override fun onFreezeReasonResult(packageName: String, reason: String) {}
    }

    private fun resolveAppContext(): Context? {
        appContext?.let { return it }
        val fallback = runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            XposedCompat.callStaticMethod(atClass, "currentApplication") as? Context
        }.getOrNull()
        if (fallback != null) {
            appContext = fallback
        }
        return appContext
    }

    private fun isExemptWhitePkg(pkg: String): Boolean {
        return pkg == MODULE_PKG || SMilletLogger.config.whiteAppSet.contains(pkg)
    }

    fun init(classLoader: ClassLoader) {
        pkClassLoader = classLoader
        SMilletLogger.config = PrefsManager.loadFromXposed()
        SMilletLogger.externalLogForwarder = { formattedLine ->
            runCatching {
                systemService?.syncNoRestrictApps(LOG_FORWARD_PREFIX + formattedLine)
            }
        }

        hookPowerKeeperApplication(classLoader)
        hookMilletPolicy(classLoader)
        hookMilletHandlers(classLoader)
        hookPowerSaveWhitelistAdapter(classLoader)
        hookBackgroundKillers(classLoader)
        hookHelperInstances(classLoader)

        SMilletLogger.info("电量与性能 (com.miui.powerkeeper) Hook 初始化完成")
    }

    private fun ensureSystemServiceConnected() {
        val current = systemService
        if (current != null && runCatching { current.asBinder().isBinderAlive }.getOrDefault(false)) {
            return
        }
        val ctx = resolveAppContext() ?: return
        runCatching {
            val binder = BinderBridge.fetchSystemInterface(ctx)
            if (binder != null && binder.isSystemHookActive()) {
                systemService = binder
                binder.registerCallback(pkCallback, "PowerKeeper")
                val json = binder.pullConfigJson()
                PrefsManager.parseSnapshotFromJson(json)?.let {
                    SMilletLogger.config = it
                    applyMilletPolicyLists()
                    applyBrightPreloadWhitelist()
                    applyPowerKeeperBgControlSets()
                }
                val noRestrict = Settings.System.getString(ctx.contentResolver, "MILLET_NO_RESTRICT_APP")
                binder.syncNoRestrictApps(noRestrict)
                SMilletLogger.info("电量与性能 (PowerKeeper) 已成功连接 system_server 服务")
            } else {
                mainHandler.postDelayed({ ensureSystemServiceConnected() }, 3000L)
            }
        }
    }

    private fun hookPowerKeeperApplication(classLoader: ClassLoader) {
        val appClass = XposedCompat.findClassIfExists(
            "com.miui.powerkeeper.PowerKeeperApplication",
            classLoader
        ) ?: return

        XposedCompat.hookAllMethods(appClass, "onCreate", object : MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val app = param.thisObject as? Application ?: return
                appContext = app
                repairLegacyPowerKeeperRowsIfNeeded(app)
                ensureSystemServiceConnected()
                applyPowerKeeperBgControlSets()
            }
        })
    }

    private fun hookMilletPolicy(classLoader: ClassLoader) {
        val policyClass = XposedCompat.findClassIfExists(
            "com.miui.powerkeeper.millet.MilletPolicy",
            classLoader
        ) ?: return

        XposedCompat.hookAllConstructors(policyClass, object : MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                milletPolicyInstance = param.thisObject
                val ctx = param.args.firstOrNull { it is Context } as? Context
                if (ctx != null && appContext == null) {
                    val appCtx = ctx.applicationContext ?: ctx
                    appContext = appCtx
                    repairLegacyPowerKeeperRowsIfNeeded(appCtx)
                    ensureSystemServiceConnected()
                }
                runCatching {
                    val musicList = XposedCompat.getStaticObjectField(policyClass, "musicApp") as? MutableList<*>
                    musicList?.clear()
                }
                applyMilletPolicyLists()
                applyPowerKeeperBgControlSets()
            }
        })

        XposedCompat.hookAllMethods(policyClass, "dealCloudWhiteList", XposedCompat.DO_NOTHING)
        XposedCompat.hookAllMethods(policyClass, "onCloudWhiteListChanged", XposedCompat.DO_NOTHING)

        XposedCompat.hookAllMethods(policyClass, "dealSleepModeWhiteList", object : MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (milletPolicyInstance == null) milletPolicyInstance = param.thisObject
                param.result = null
            }
        })

        XposedCompat.hookAllMethods(policyClass, "dealNoRestrictApp", object : MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (milletPolicyInstance == null) milletPolicyInstance = param.thisObject
                ensureSystemServiceConnected()
                applyMilletPolicyLists()
                syncInMemoryNoRestrictSet()
                resolveAppContext()?.let { ctx ->
                    runCatching {
                        val noRestrict = Settings.System.getString(ctx.contentResolver, "MILLET_NO_RESTRICT_APP")
                        systemService?.syncNoRestrictApps(noRestrict)
                    }
                }
            }
        })

        XposedCompat.hookAllMethods(policyClass, "isInWhiteList", object : MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (milletPolicyInstance == null) milletPolicyInstance = param.thisObject
                val arg0 = param.args.getOrNull(0) ?: return
                val pkg = when (arg0) {
                    is String -> arg0
                    is Int -> getPackageNameForUid(arg0)
                    else -> null
                } ?: return
                val cfg = SMilletLogger.config
                if (isExemptWhitePkg(pkg)) {
                    param.result = true
                } else if (cfg.forceFreezeSet.contains(pkg)) {
                    param.result = false
                }
            }
        })

        XposedCompat.hookAllMethods(policyClass, "isAllowFreeze", object : MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (milletPolicyInstance == null) milletPolicyInstance = param.thisObject
                val uid = param.args.getOrNull(0) as? Int ?: return
                val pkg = getPackageNameForUid(uid) ?: return
                val cfg = SMilletLogger.config
                if (isExemptWhitePkg(pkg)) {
                    param.result = false
                } else if (cfg.forceFreezeSet.contains(pkg)) {
                    param.result = true
                }
            }
        })
    }

    /**
     * 自动修复旧版可能写入的下划线 bgControl 值（no_restrict -> noRestrict, miui_auto -> miuiAuto）
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
     * 在 com.miui.powerkeeper 进程内安全同步 HyperOS 应用「省电策略」：
     * - batteryOptWhiteSet -> userTable.bgControl = "noRestrict", bgDelayMin = -2（无限制）
     * - batteryOptBlackSet -> userTable.bgControl = "miuiAuto",   bgDelayMin = -2（智能限制后台）
     */
    @Suppress("UNCHECKED_CAST")
    fun applyPowerKeeperBgControlSets() {
        val ctx = resolveAppContext() ?: return
        repairLegacyPowerKeeperRowsIfNeeded(ctx)

        val cfg = SMilletLogger.config
        if (cfg.batteryOptWhiteSet.isEmpty() && cfg.batteryOptBlackSet.isEmpty()) {
            return
        }
        val sig = "${cfg.batteryOptWhiteSet}|${cfg.batteryOptBlackSet}"
        if (sig == lastAppliedBgControlSignature) {
            syncInMemoryNoRestrictSet()
            return
        }
        lastAppliedBgControlSignature = sig

        val resolver = ctx.contentResolver
        val tableUri = Uri.parse(PK_USER_TABLE_URI)

        fun applySinglePkgDbMode(pkg: String, dbMode: String) {
            runCatching {
                val updateValues = ContentValues().apply {
                    put("bgControl", dbMode)
                    put("bgDelayMin", -2)
                }
                var rows = resolver.update(
                    tableUri,
                    updateValues,
                    "pkgName = ? AND userId = ?",
                    arrayOf(pkg, "0")
                )
                if (rows <= 0) {
                    rows = resolver.update(tableUri, updateValues, "pkgName = ?", arrayOf(pkg))
                }
                if (rows <= 0) {
                    val insertValues = ContentValues().apply {
                        put("userId", 0)
                        put("pkgName", pkg)
                        put("bgControl", dbMode)
                        put("bgDelayMin", -2)
                    }
                    resolver.insert(tableUri, insertValues)
                }
            }
        }

        for (pkg in cfg.batteryOptWhiteSet) {
            applySinglePkgDbMode(pkg, BG_CONTROL_NO_RESTRICT)
        }
        for (pkg in cfg.batteryOptBlackSet) {
            applySinglePkgDbMode(pkg, BG_CONTROL_MIUI_AUTO)
        }

        runCatching {
            resolver.notifyChange(tableUri, null)
        }

        runCatching {
            val raw = Settings.System.getString(resolver, "MILLET_NO_RESTRICT_APP").orEmpty()
            val set = raw.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableSet()
            set.addAll(cfg.batteryOptWhiteSet)
            set.removeAll(cfg.batteryOptBlackSet)
            val updated = set.joinToString(",")
            if (updated != raw) {
                Settings.System.putString(resolver, "MILLET_NO_RESTRICT_APP", updated)
            }
            systemService?.syncNoRestrictApps(updated)
        }

        syncInMemoryNoRestrictSet()
    }

    @Suppress("UNCHECKED_CAST")
    private fun syncInMemoryNoRestrictSet() {
        val policy = milletPolicyInstance ?: return
        val cfg = SMilletLogger.config
        runCatching {
            val noRestrictSet = XposedCompat.getObjectField(policy, "mNoRestrictSet") as? MutableCollection<String>
            if (noRestrictSet != null) {
                noRestrictSet.addAll(cfg.batteryOptWhiteSet)
                noRestrictSet.removeAll(cfg.batteryOptBlackSet)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun applyMilletPolicyLists() {
        val policy = milletPolicyInstance
        val policyClass = policy?.javaClass ?: pkClassLoader?.let {
            XposedCompat.findClassIfExists("com.miui.powerkeeper.millet.MilletPolicy", it)
        } ?: return
        val cfg = SMilletLogger.config

        runCatching {
            val msgList = XposedCompat.getStaticObjectField(policyClass, "MESSAGE_APPS") as? MutableCollection<String>
            msgList?.apply {
                clear()
                addAll(cfg.messageAppSet)
            }
        }

        runCatching {
            val brightList = XposedCompat.getStaticObjectField(policyClass, "BRIGHT_WHITE_APPS") as? MutableCollection<String>
            brightList?.apply {
                val allowed = HashSet(DEFAULT_BRIGHT_WHITE_APPS)
                allowed.removeAll(cfg.forceFreezeSet)
                allowed.addAll(cfg.whiteAppSet)
                allowed.add(MODULE_PKG)
                clear()
                addAll(allowed)
            }
        }

        runCatching {
            val whiteList = XposedCompat.getStaticObjectField(policyClass, "whiteApps") as? MutableCollection<String>
            if (whiteList != null) {
                whiteList.remove("com.miui.systemAdSolution")
                whiteList.remove("com.xiaomi.market")
                whiteList.removeAll(cfg.forceFreezeSet)
                whiteList.add(MODULE_PKG)
                XposedCompat.setStaticObjectField(policyClass, "whiteApps", whiteList)
            }
        }

        runCatching {
            val dataWhiteList = XposedCompat.getStaticObjectField(policyClass, "mDataWhiteList") as? MutableCollection<String>
            if (dataWhiteList != null) {
                dataWhiteList.removeAll(REMOVED_DATA_WHITE_APPS.toSet())
                dataWhiteList.removeAll(cfg.forceFreezeSet)
                dataWhiteList.add(MODULE_PKG)
                XposedCompat.setStaticObjectField(policyClass, "mDataWhiteList", dataWhiteList)
            }
        }

        if (policy != null) {
            runCatching {
                val blackSet = HashSet<String>(cfg.forceFreezeSet)
                XposedCompat.setObjectField(policy, "mUseSystemBlackList", blackSet)
            }

            runCatching {
                val whiteSet = HashSet<String>(cfg.whiteAppSet + MODULE_PKG)
                XposedCompat.setObjectField(policy, "mUseDataWhiteList", whiteSet)
            }
        }

        syncInMemoryNoRestrictSet()

        val sig = "${cfg.whiteAppSet}|${cfg.forceFreezeSet}|${cfg.messageAppSet}|${policy != null}"
        if (sig != lastAppliedPolicySignature) {
            lastAppliedPolicySignature = sig
            if (SMilletLogger.isDebugEnabled()) {
                SMilletLogger.debug(
                    "[PowerKeeper] 策略明细同步完成：白名单=${cfg.whiteAppSet}, 强制冻结=${cfg.forceFreezeSet}, 消息应用=${cfg.messageAppSet}"
                )
            } else {
                SMilletLogger.info(
                    "PowerKeeper 策略已更新：白名单 ${cfg.whiteAppSet.size} 项 · 强制冻结 ${cfg.forceFreezeSet.size} 项 · 消息应用 ${cfg.messageAppSet.size} 项"
                )
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun hookMilletHandlers(classLoader: ClassLoader) {
        val milletHandlerClass = XposedCompat.findClassIfExists(
            "com.miui.powerkeeper.millet.MilletHandler",
            classLoader
        )
        if (milletHandlerClass != null) {
            XposedCompat.hookAllMethods(milletHandlerClass, "handleMessage", object : MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    ensureSystemServiceConnected()
                    val msg = param.args.getOrNull(0) as? Message ?: return
                    val cfg = SMilletLogger.config

                    if (msg.what == 13 && msg.arg1 == 16) {
                        if (cfg.disableCharge) {
                            SMilletLogger.debug("[PowerKeeper] 已屏蔽充电状态自动解冻事件")
                            param.result = null
                        }
                        return
                    }

                    val uid = msg.data?.getInt("uid", -1) ?: -1
                    if (uid > 0) {
                        val pkg = getPackageNameForUid(uid)
                        if (pkg != null && cfg.forbidSyncBinderSet.contains(pkg)) {
                            param.thisObject?.let { obj ->
                                runCatching {
                                    val qList = XposedCompat.getObjectField(obj, "queryList") as? MutableSet<Int>
                                    qList?.remove(uid)
                                }
                            }
                            SMilletLogger.debug("[PowerKeeper] 已移除 $pkg (UID: $uid) 的 MilletHandler Binder 查询队列")
                            param.result = null
                        }
                    }
                }
            })
        }

        val brightHandlerClass = XposedCompat.findClassIfExists(
            "com.miui.powerkeeper.millet.bright.MilletBrightHandler",
            classLoader
        )
        if (brightHandlerClass != null) {
            XposedCompat.hookAllMethods(brightHandlerClass, "handleMessage", object : MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val msg = param.args.getOrNull(0) as? Message ?: return
                    val cfg = SMilletLogger.config
                    val data = msg.data

                    val uid = data?.getInt("uid", -1) ?: -1
                    if (uid > 0 && msg.what != 20) {
                        val pkg = getPackageNameForUid(uid)
                        if (pkg != null && cfg.forbidSyncBinderSet.contains(pkg)) {
                            param.thisObject?.let { obj ->
                                runCatching {
                                    val qList = XposedCompat.getObjectField(obj, "queryList") as? MutableSet<Int>
                                    qList?.remove(uid)
                                }
                            }
                            param.result = null
                            return
                        }
                    }

                    val eventCode = data?.getInt("eventcode", -1) ?: -1
                    if ((eventCode == 24 || eventCode == 25) && cfg.disableVolume) {
                        SMilletLogger.debug("[PowerKeeper] 已屏蔽音量按键事件触发解冻 (eventCode=$eventCode)")
                        param.result = null
                    }
                }
            })
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun hookPowerSaveWhitelistAdapter(classLoader: ClassLoader) {
        val adapterClass = XposedCompat.findClassIfExists(
            "com.miui.powerkeeper.utils.CommonAdapter",
            classLoader
        ) ?: return

        XposedCompat.hookAllMethods(adapterClass, "addPowerSaveWhitelistApps", object : MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val cfg = SMilletLogger.config
                if (!cfg.blockAddPowerSaveWhitelistApps) return
                val arg0 = param.args.getOrNull(0)
                if (arg0 is MutableList<*>) {
                    val list = arg0 as MutableList<String>
                    list.removeAll(cfg.batteryOptBlackSet)
                    if (list.isEmpty()) {
                        param.result = null
                    }
                } else {
                    param.result = null
                }
            }
        })

        XposedCompat.hookAllMethods(adapterClass, "removePowerSaveWhitelistApps", object : MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val cfg = SMilletLogger.config
                if (!cfg.blockAddPowerSaveWhitelistApps) return
                val arg0 = param.args.getOrNull(0)
                if (arg0 is MutableList<*>) {
                    val list = arg0 as MutableList<String>
                    list.removeAll(cfg.batteryOptWhiteSet)
                    if (list.isEmpty()) {
                        param.result = null
                    }
                } else {
                    param.result = null
                }
            }
        })
    }

    private fun logKillIntercepted() {
        val now = System.currentTimeMillis()
        if (now - lastKillInterceptLogTime >= 30_000L) {
            lastKillInterceptLogTime = now
            SMilletLogger.warn("已拦截 PowerKeeper 后台杀进程动作 (保持墓碑冻结状态)")
        }
    }

    private fun hookBackgroundKillers(classLoader: ClassLoader) {
        val checkerClass = XposedCompat.findClassIfExists(
            "com.miui.powerkeeper.powerchecker.PowerCheckerController",
            classLoader
        )
        if (checkerClass != null) {
            XposedCompat.hookAllMethods(checkerClass, "clearApp", object : MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (SMilletLogger.config.disableKill) {
                        logKillIntercepted()
                        param.result = null
                    }
                }
            })
        }

        val stateMachineClass = XposedCompat.findClassIfExists(
            "com.miui.powerkeeper.statemachine.PowerStateMachine",
            classLoader
        )
        if (stateMachineClass != null) {
            val stopKillHook = object : MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (SMilletLogger.config.disableKill) {
                        logKillIntercepted()
                        param.result = null
                    }
                }
            }
            XposedCompat.hookAllMethods(stateMachineClass, "clearAppWhenScreenOffTimeOut", stopKillHook)
            XposedCompat.hookAllMethods(stateMachineClass, "clearAppWhenScreenOffTimeOutInNight", stopKillHook)
            XposedCompat.hookAllMethods(stateMachineClass, "clearUnactiveApps", stopKillHook)
        }
    }

    private fun hookHelperInstances(classLoader: ClassLoader) {
        XposedCompat.findClassIfExists(
            "com.miui.powerkeeper.millet.bright.BrightScenarioBase",
            classLoader
        )?.let { cls ->
            XposedCompat.hookAllConstructors(cls, object : MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    brightScenarioInstance = param.thisObject
                    applyBrightPreloadWhitelist()
                }
            })
        }

        XposedCompat.findClassIfExists(
            "com.miui.powerkeeper.millet.bright.BrightController",
            classLoader
        )?.let { cls ->
            XposedCompat.hookAllMethods(cls, "canFreezePkg", object : MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val pkg = param.args.getOrNull(1) as? String
                        ?: (param.args.getOrNull(0) as? Int)?.let { getPackageNameForUid(it) }
                        ?: return
                    val cfg = SMilletLogger.config
                    if (isExemptWhitePkg(pkg)) {
                        param.result = false
                    } else if (cfg.forceFreezeSet.contains(pkg)) {
                        param.result = true
                    }
                }
            })
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyBrightPreloadWhitelist() {
        val bright = brightScenarioInstance ?: return
        runCatching {
            val preloadSet = XposedCompat.getStaticObjectField(
                bright.javaClass,
                "preloadWhiteApps"
            ) as? MutableCollection<String>
            preloadSet?.removeAll(SMilletLogger.config.forceFreezeSet)
        }
    }

    private fun getPackageNameForUid(uid: Int): String? {
        val ctx = resolveAppContext() ?: return null
        return runCatching {
            ctx.packageManager.getPackagesForUid(uid)?.firstOrNull()
        }.getOrNull()
    }

    private fun diagnoseUnfreezeReason(packageName: String): String {
        val ctx = resolveAppContext()
            ?: return "[PowerKeeper 策略层]\n• PowerKeeper 上下文尚未就绪，请稍后重试"
        val uid = runCatching {
            ctx.packageManager.getApplicationInfo(
                packageName,
                PackageManager.MATCH_UNINSTALLED_PACKAGES
            ).uid
        }.getOrNull() ?: return "[PowerKeeper 策略层]\n• 找不到应用 UID: $packageName"

        val reasons = mutableListOf<String>()
        val cfg = SMilletLogger.config
        if (cfg.whiteAppSet.contains(packageName)) {
            reasons.add("处于 Smillet-Next 墓碑白名单中")
        }

        val policy = milletPolicyInstance
        if (policy != null) {
            runCatching {
                if (XposedCompat.callMethod(policy, "isCtsPackage", packageName) as? Boolean == true) {
                    reasons.add("属于 CTS 测试包名豁免")
                }
            }
            runCatching {
                val noRestrict = XposedCompat.getObjectField(policy, "mNoRestrictSet") as? Collection<*>
                if (noRestrict?.contains(packageName) == true) {
                    reasons.add("系统省电策略设为「无限制」")
                }
            }
            runCatching {
                if (XposedCompat.callMethod(policy, "getActiveVpnUid") as? Int == uid) {
                    reasons.add("当前正在作为活跃 VPN 运行")
                }
            }
            runCatching {
                val ime = XposedCompat.callMethod(policy, "getCurrentIME") as? String
                if (ime?.contains(packageName) == true) {
                    reasons.add("当前正在作为默认输入法 (IME) 使用")
                }
            }
            runCatching {
                val accUids = XposedCompat.getObjectField(policy, "mAccessibilityUids") as? Collection<*>
                if (accUids?.contains(uid) == true) {
                    reasons.add("已开启无障碍服务 (AccessibilityService)")
                }
            }
            runCatching {
                if (XposedCompat.callMethod(policy, "isInWhiteList", packageName) as? Boolean == true) {
                    reasons.add("位于 MilletPolicy 内置白名单（可在模块中开启「强制冻结」移出）")
                }
            }
            runCatching {
                val utilsCls = XposedCompat.findClassIfExists(
                    "com.miui.powerkeeper.millet.MilletUtils",
                    policy.javaClass.classLoader
                )
                if (utilsCls != null && XposedCompat.callStaticMethod(utilsCls, "isTopApp", uid) as? Boolean == true) {
                    reasons.add("当前处于前台活跃应用状态 (TopApp)")
                }
            }
            runCatching {
                if (XposedCompat.callMethod(policy, "isGameUid", uid) as? Boolean == true) {
                    reasons.add("被识别为游戏应用 (GameUid)")
                }
            }
            runCatching {
                if (XposedCompat.callMethod(policy, "isBrightWhite", uid) as? Boolean == true) {
                    reasons.add("位于亮屏白名单中 (BrightWhite)")
                }
            }
            runCatching {
                if (XposedCompat.callMethod(policy, "isUidActive", uid) as? Boolean == true) {
                    reasons.add("当前存在活跃音频/导航/传感器等状态 (UidActive)")
                }
            }
            runCatching {
                if (XposedCompat.callMethod(policy, "isAllowAutoStart", uid) as? Boolean == true) {
                    reasons.add("已开启系统自启动豁免 (AllowAutoStart)")
                }
            }
            runCatching {
                if (XposedCompat.callMethod(policy, "isAllowWallpaper", uid) as? Boolean == true) {
                    reasons.add("正在作为动态壁纸运行 (AllowWallpaper)")
                }
            }
        } else {
            runCatching {
                val raw = Settings.System.getString(ctx.contentResolver, "MILLET_NO_RESTRICT_APP").orEmpty()
                if (raw.split(",").map { it.trim() }.contains(packageName)) {
                    reasons.add("系统省电策略设为「无限制」")
                }
            }
        }

        return if (reasons.isEmpty()) {
            "[PowerKeeper 策略层]\n• 未发现白名单豁免项，该应用满足 Millet 冻结条件（切入后台静置后将被系统冻结）"
        } else {
            "[PowerKeeper 策略层 · 豁免/未冻结原因]\n• " + reasons.joinToString("\n• ")
        }
    }
}

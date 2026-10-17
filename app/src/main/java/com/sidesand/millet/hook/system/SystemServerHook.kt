package com.sidesand.millet.hook.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.util.ArraySet
import com.google.gson.Gson
import com.sidesand.millet.data.ConfigKeys
import com.sidesand.millet.data.MilletConfigSnapshot
import com.sidesand.millet.data.PrefsManager
import com.sidesand.millet.hook.SMilletLogger
import com.sidesand.millet.hook.XposedCompat
import com.sidesand.millet.hook.XposedCompat.MethodHook
import com.sidesand.millet.hook.XposedCompat.MethodHookParam
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object SystemServerHook {

    private const val GREEZE_SERVICE_CLASS = "com.miui.server.greeze.GreezeManagerService"
    private const val MODULE_PKG = "com.sidesand.millet"

    data class SysProcEntry(
        val pid: Int,
        val uid: Int,
        val processName: String,
        val rssKb: Long,
        val wchan: String,
        val state: String,
        val isFrozenByGreeze: Boolean
    )

    private val gson = Gson()
    private val hookedDicClasses = Collections.synchronizedSet(HashSet<Class<*>>())
    private val hookedGmsClasses = Collections.synchronizedSet(HashSet<Class<*>>())
    private val hookedAurogonClasses = Collections.synchronizedSet(HashSet<Class<*>>())
    private val hookedPowerListClasses = Collections.synchronizedSet(HashSet<Class<*>>())
    private val hookedProcessListClasses = Collections.synchronizedSet(HashSet<Class<*>>())
    private val trackedFrozenUids = Collections.synchronizedSet(HashSet<Int>())
    private val lastFreezeLogTimeByUid = ConcurrentHashMap<Int, Long>()
    private val lastWarnLogTimeByKey = ConcurrentHashMap<String, Long>()

    @Volatile
    var greezeManagerService: Any? = null
        private set

    @Volatile
    var aurogonImmobulusMode: Any? = null
        private set

    @Volatile
    var listAppsManagerInstance: Any? = null
        private set

    @Volatile
    var powerStrategyModeInstance: Any? = null
        private set

    @Volatile
    var amsInstance: Any? = null
        private set

    @Volatile
    private var cachedSystemContext: Context? = null

    @Volatile
    private var lastToFrontPkg: String? = null

    @Volatile
    private var screenReceiverRegistered: Boolean = false

    private fun shouldEmitThrottledWarn(key: String, windowMs: Long = 15_000L): Boolean {
        val now = System.currentTimeMillis()
        val prev = lastWarnLogTimeByKey.put(key, now) ?: 0L
        return (now - prev) >= windowMs
    }

    fun init(classLoader: ClassLoader) {
        reloadConfig("ALL")

        hookSystemServiceManager(classLoader)
        hookAmsBinderBridge(classLoader)
        hookGreezeManagerService(classLoader)
        hookAurogonImmobulusMode(classLoader)
        hookGreezePowerPolicy(classLoader)
        hookProcessList(classLoader)
        hookAnrSuppression(classLoader)
        hookBroadcastSkipForFrozenApp(classLoader)
        hookDeviceIdleController(classLoader)

        SMilletLogger.info("系统服务 (system_server) Hook 初始化完成 · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
    }

    private fun isExemptWhitePkg(pkg: String): Boolean {
        return pkg == MODULE_PKG || SMilletLogger.config.whiteAppSet.contains(pkg)
    }

    private fun updateGreezeManagerService(candidate: Any?) {
        if (candidate == null) return
        if (candidate.javaClass.name == GREEZE_SERVICE_CLASS) {
            greezeManagerService = candidate
            return
        }
        val unwrapped = unwrapServiceInstance(candidate)
        if (unwrapped.javaClass.name == GREEZE_SERVICE_CLASS) {
            greezeManagerService = unwrapped
        }
    }

    private fun hookSystemServiceManager(classLoader: ClassLoader) {
        val ssmClass = XposedCompat.findClassIfExists(
            "com.android.server.SystemServiceManager",
            classLoader
        ) ?: return

        XposedCompat.hookAllMethods(ssmClass, "startService", object : MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val arg0 = param.args.firstOrNull() ?: return
                val targetCls = when (arg0) {
                    is Class<*> -> arg0
                    else -> arg0.javaClass
                }
                val name = targetCls.name
                if (name.contains("DeviceIdleController")) {
                    val dicCls = if (name == "com.android.server.DeviceIdleController") {
                        targetCls
                    } else {
                        targetCls.classLoader?.let {
                            XposedCompat.findClassIfExists("com.android.server.DeviceIdleController", it)
                        }
                    }
                    if (dicCls != null) hookDeviceIdleControllerClass(dicCls)
                } else if (name.contains("GreezeManagerService")) {
                    targetCls.classLoader?.let { cl ->
                        hookGreezeManagerService(cl)
                        hookAurogonImmobulusMode(cl)
                        hookGreezePowerPolicy(cl)
                    }
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val service = param.result ?: param.args.firstOrNull() ?: return
                if (service is Class<*>) return
                val cls = service.javaClass
                val name = cls.name
                if (name.contains("DeviceIdleController")) {
                    val actualDic = unwrapServiceInstance(service)
                    if (actualDic.javaClass.name == "com.android.server.DeviceIdleController") {
                        hookDeviceIdleControllerClass(actualDic.javaClass)
                        DozeControllerHelper.onDeviceIdleStart(actualDic)
                    }
                } else if (name.contains("GreezeManagerService")) {
                    updateGreezeManagerService(service)
                    cls.classLoader?.let { cl ->
                        hookGreezeManagerService(cl)
                        hookAurogonImmobulusMode(cl)
                        hookGreezePowerPolicy(cl)
                    }
                    ensureRuntimeServicesResolved()
                }
            }
        })

        XposedCompat.hookAllMethods(ssmClass, "startBootPhase", object : MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val phase = param.args.firstOrNull { it is Int } as? Int ?: return
                if (phase == 500 || phase == 550 || phase == 1000) {
                    ensureRuntimeServicesResolved()
                    if (phase == 1000) {
                        applyConfigSnapshot(SMilletLogger.config)
                    }
                }
            }
        })
    }

    private fun unwrapServiceInstance(raw: Any): Any {
        if (!raw.javaClass.name.contains("$")) return raw
        for (fieldName in listOf("this$0", "mService", "sInstance", "mGreezeManagerService")) {
            val v = runCatching { XposedCompat.getObjectField(raw, fieldName) }.getOrNull()
            if (v != null && !v.javaClass.name.contains("$")) {
                return v
            }
        }
        for (field in raw.javaClass.declaredFields) {
            if (!field.type.name.contains("$") &&
                (field.type.name == GREEZE_SERVICE_CLASS || field.type.name == "com.android.server.DeviceIdleController")
            ) {
                val v = runCatching {
                    field.isAccessible = true
                    field.get(raw)
                }.getOrNull()
                if (v != null) return v
            }
        }
        return raw
    }

    private fun ensureRuntimeServicesResolved() {
        val currentGms = greezeManagerService
        if (currentGms == null || currentGms.javaClass.name != GREEZE_SERVICE_CLASS) {
            runCatching {
                val smClass = Class.forName("android.os.ServiceManager")
                val greezer = XposedCompat.callStaticMethod(smClass, "getService", "greezer")
                if (greezer != null) {
                    updateGreezeManagerService(greezer)
                    greezeManagerService?.let { actual ->
                        hookGreezeManagerServiceClass(actual.javaClass)
                        actual.javaClass.classLoader?.let { cl ->
                            hookAurogonImmobulusMode(cl)
                            hookGreezePowerPolicy(cl)
                        }
                        syncBroadcastTargetWhiteList()
                    }
                }
            }
        }
        if (aurogonImmobulusMode == null && greezeManagerService != null) {
            val gms = greezeManagerService!!
            for (fieldName in listOf("mImmobulusMode", "mAurogonImmobulusMode")) {
                val obj = runCatching { XposedCompat.getObjectField(gms, fieldName) }.getOrNull()
                if (obj != null) {
                    aurogonImmobulusMode = obj
                    hookAurogonClass(obj.javaClass)
                    syncMessageAppsToAurogon()
                    break
                }
            }
        }
        getSystemContext()
        DozeControllerHelper.resolveDeviceIdleController()
    }

    fun reloadConfig(changedKey: String) {
        val snapshot = PrefsManager.loadFromXposed()
        applyConfigSnapshot(snapshot)
    }

    fun applyConfigSnapshot(snapshot: MilletConfigSnapshot) {
        SMilletLogger.config = snapshot
        ensureRuntimeServicesResolved()
        syncMessageAppsToAurogon()
        syncGreezePowerLists()
        syncBroadcastTargetWhiteList()
        thawWhitelistedApps(snapshot.whiteAppSet + MODULE_PKG)
        DozeControllerHelper.applyBatteryOptimizationSets(force = false)
    }

    private fun thawWhitelistedApps(whiteApps: Set<String>) {
        if (whiteApps.isEmpty()) return
        val gms = greezeManagerService ?: return
        if (gms.javaClass.name != GREEZE_SERVICE_CLASS) return
        val ctx = getSystemContext() ?: return
        val pm = ctx.packageManager
        for (pkg in whiteApps) {
            runCatching {
                val uid = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES).uid
                if (uid >= 10000 && isUidFrozen(uid)) {
                    runCatching {
                        XposedCompat.callMethod(gms, "thawUid", uid, 1000, "SmilletWhite")
                    }
                    trackedFrozenUids.remove(uid)
                    if (pkg != MODULE_PKG) {
                        SMilletLogger.info("白名单应用解冻：$pkg (UID: $uid) 已移入白名单并立即解冻")
                    }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun syncBroadcastTargetWhiteList() {
        val gms = greezeManagerService ?: return
        if (gms.javaClass.name != GREEZE_SERVICE_CLASS) return
        val msgApps = SMilletLogger.config.messageAppSet
        runCatching {
            val map = XposedCompat.getObjectField(gms, "mBroadcastTargetWhiteList") as? MutableMap<String, MutableList<String>>
                ?: return
            val actions = arrayListOf(
                "android.net.wifi.STATE_CHANGE",
                "android.net.conn.CONNECTIVITY_CHANGE"
            )
            for (pkg in msgApps) {
                map[pkg] = ArrayList(actions)
            }
            map.keys.retainAll(msgApps)
        }
    }

    fun collectRunningProcessesJson(includeSystem: Boolean): String {
        val token = Binder.clearCallingIdentity()
        return try {
            ensureRuntimeServicesResolved()
            val ctx = getSystemContext() ?: return "[]"
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return "[]"
            val runningList = am.runningAppProcesses ?: emptyList()
            val entries = ArrayList<SysProcEntry>(runningList.size)

            val batchFrozenUids = getGreezeFrozenUidsSet()

            for (procInfo in runningList) {
                val pid = procInfo.pid
                val uid = procInfo.uid
                if (pid <= 0) continue
                val appId = uid % 100000
                if (!includeSystem && appId < 10000) continue

                val isFrozen = batchFrozenUids.contains(uid) || isUidFrozen(uid) || isPidCgroupFrozen(uid, pid)
                val wchan = readProcWchan(pid, isFrozen)
                val (state, rssKb) = readProcStateAndRss(pid, am)

                entries.add(
                    SysProcEntry(
                        pid = pid,
                        uid = uid,
                        processName = procInfo.processName ?: "pid:$pid",
                        rssKb = rssKb,
                        wchan = wchan,
                        state = state,
                        isFrozenByGreeze = isFrozen
                    )
                )
            }
            gson.toJson(entries)
        } catch (t: Throwable) {
            SMilletLogger.error("扫描运行进程状态异常", t)
            "[]"
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun getGreezeFrozenUidsSet(): Set<Int> {
        val result = HashSet<Int>(trackedFrozenUids)
        val gms = greezeManagerService
        if (gms != null && gms.javaClass.name == GREEZE_SERVICE_CLASS) {
            runCatching {
                val arr = XposedCompat.callMethod(gms, "getFrozenUids", 9999) as? IntArray
                if (arr != null) {
                    for (u in arr) {
                        if (u > 0) result.add(u)
                    }
                }
            }
        }
        return result
    }

    private fun isPidCgroupFrozen(uid: Int, pid: Int): Boolean {
        return runCatching {
            val f1 = File("/sys/fs/cgroup/uid_$uid/pid_$pid/cgroup.freeze")
            if (f1.exists() && f1.readText().trim() == "1") return@runCatching true
            val f2 = File("/sys/fs/cgroup/uid_$uid/cgroup.freeze")
            if (f2.exists() && f2.readText().trim() == "1") return@runCatching true
            false
        }.getOrDefault(false)
    }

    private fun isUidCgroupFrozen(uid: Int): Boolean {
        if (uid <= 0) return false
        return runCatching {
            val uidDir = File("/sys/fs/cgroup/uid_$uid")
            val freezeFile = File(uidDir, "cgroup.freeze")
            if (freezeFile.exists() && freezeFile.readText().trim() == "1") return@runCatching true
            val subDirs = uidDir.listFiles() ?: return@runCatching false
            subDirs.any { dir ->
                dir.isDirectory && dir.name.startsWith("pid_") &&
                    runCatching { File(dir, "cgroup.freeze").readText().trim() == "1" }.getOrDefault(false)
            }
        }.getOrDefault(false)
    }

    private fun readProcWchan(pid: Int, isFrozenByGreeze: Boolean): String {
        val raw = runCatching {
            File("/proc/$pid/wchan").readText().trim()
        }.getOrNull().orEmpty()
        if (isFrozenByGreeze) {
            if (raw.isEmpty() || raw == "0" || raw == "-" || !raw.contains("freezer")) {
                return "do_freezer_trap"
            }
        }
        if (raw.isNotEmpty() && raw != "0" && raw != "-") {
            return raw
        }
        return if (isFrozenByGreeze) "do_freezer_trap" else "ep_poll"
    }

    private fun readProcStateAndRss(pid: Int, am: ActivityManager): Pair<String, Long> {
        var state = "S"
        var rssKb = 0L
        runCatching {
            val statusFile = File("/proc/$pid/status")
            if (statusFile.exists()) {
                statusFile.forEachLine { line ->
                    when {
                        line.startsWith("State:") -> {
                            state = line.substringAfter("State:").trim().take(1)
                        }
                        line.startsWith("VmRSS:") -> {
                            val num = line.substringAfter("VmRSS:").trim().split(Regex("\\s+")).firstOrNull()
                            rssKb = num?.toLongOrNull() ?: 0L
                        }
                    }
                }
            }
        }
        if (rssKb <= 0L) {
            runCatching {
                val memInfo = am.getProcessMemoryInfo(intArrayOf(pid))
                if (memInfo != null && memInfo.isNotEmpty()) {
                    rssKb = memInfo[0].totalPss.toLong()
                }
            }
        }
        return state to rssKb
    }

    private fun hookAmsBinderBridge(classLoader: ClassLoader) {
        val amsClass = XposedCompat.findClassIfExists(
            "com.android.server.am.ActivityManagerService",
            classLoader
        ) ?: return

        XposedCompat.hookAllMethods(amsClass, "setSystemProcess", object : MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                amsInstance = param.thisObject
                reloadConfig("ALL")
            }
        })

        XposedCompat.hookAllMethods(amsClass, "registerReceiverWithFeature", object : MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val filter = param.args.firstOrNull { it is IntentFilter } as? IntentFilter ?: return
                if (filter.hasAction(ConfigKeys.BINDER_ACTION)) {
                    if (amsInstance == null) {
                        amsInstance = param.thisObject
                    }
                    ensureRuntimeServicesResolved()
                    val replyIntent = Intent()
                    val bundle = Bundle()
                    bundle.putBinder(ConfigKeys.BINDER_KEY_SYSTEM, SystemServerService)
                    replyIntent.putExtras(bundle)
                    param.result = replyIntent
                }
            }
        })
    }

    private fun hookGreezeManagerService(classLoader: ClassLoader) {
        val gmsClass = XposedCompat.findClassIfExists(
            GREEZE_SERVICE_CLASS,
            classLoader
        ) ?: return
        hookGreezeManagerServiceClass(gmsClass)
    }

    fun hookGreezeManagerServiceClass(gmsClass: Class<*>) {
        if (gmsClass.name != GREEZE_SERVICE_CLASS) return
        if (!hookedGmsClasses.add(gmsClass)) return

        XposedCompat.hookAllConstructors(gmsClass, object : MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                updateGreezeManagerService(param.thisObject)
                val ctx = param.args.firstOrNull { it is Context } as? Context
                if (ctx != null) {
                    cachedSystemContext = ctx
                    if (!screenReceiverRegistered) {
                        screenReceiverRegistered = true
                        runCatching { DozeControllerHelper.registerScreenReceiver(ctx) }
                    }
                }
                syncBroadcastTargetWhiteList()
            }
        })

        XposedCompat.hookAllMethods(gmsClass, "freezeAction", object : MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                updateGreezeManagerService(param.thisObject)
                val uid = param.args.getOrNull(0) as? Int ?: return
                val reason = param.args.getOrNull(2) as? String ?: ""
                val pkg = getPackageNameForUid(uid) ?: return
                val cfg = SMilletLogger.config

                if (isExemptWhitePkg(pkg)) {
                    if (pkg != MODULE_PKG) {
                        SMilletLogger.debug("[Greezer] 跳过白名单应用冻结：$pkg (UID: $uid, 阶段: freezeAction)")
                    }
                    param.result = false
                    return
                }

                if (!reason.equals("LAUNCH_MODE", ignoreCase = true) && cfg.notFreezeWhenHasFgsSet.contains(pkg)) {
                    val hasFgs = checkAppHasForegroundService(uid)
                    if (hasFgs) {
                        SMilletLogger.debug("[Greezer] 跳过活跃前台服务应用冻结：$pkg (UID: $uid, 原因: $reason)")
                        param.result = false
                    }
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val uid = param.args.getOrNull(0) as? Int ?: return
                val reason = param.args.getOrNull(2) as? String ?: ""
                if (param.result as? Boolean == true && uid > 0 && !reason.equals("LAUNCH_MODE", ignoreCase = true)) {
                    trackedFrozenUids.add(uid)
                }
            }
        })

        XposedCompat.hookAllMethods(gmsClass, "freezeProcess", object : MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                updateGreezeManagerService(param.thisObject)
                val proc = param.args.getOrNull(0) ?: return
                val uid = runCatching { XposedCompat.getIntField(proc, "uid") }.getOrNull() ?: return
                val pkg = getPackageNameForUid(uid) ?: return
                if (isExemptWhitePkg(pkg)) {
                    if (pkg != MODULE_PKG) {
                        SMilletLogger.debug("[Greezer] 跳过白名单单进程冻结：$pkg (UID: $uid)")
                    }
                    param.result = false
                }
            }
        })

        XposedCompat.hookAllMethods(gmsClass, "thawProcess", object : MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val pid = param.args.getOrNull(0) as? Int ?: return
                val reason = param.args.getOrNull(2) as? String ?: ""
                if (param.result as? Boolean == true && pid > 0) {
                    SMilletLogger.debug("[Greezer] 单进程解冻：PID=$pid · 触发原因：$reason")
                }
            }
        })

        XposedCompat.hookAllMethods(gmsClass, "freezeUids", object : MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                updateGreezeManagerService(param.thisObject)
                val uids = param.args.getOrNull(0) as? IntArray ?: return
                if (uids.isEmpty()) return
                val cfg = SMilletLogger.config

                val filtered = uids.filter { uid ->
                    val pkg = getPackageNameForUid(uid)
                    if (pkg != null && isExemptWhitePkg(pkg)) {
                        if (pkg != MODULE_PKG) {
                            SMilletLogger.debug("[Greezer] 批量冻结过滤白名单：$pkg (UID: $uid)")
                        }
                        false
                    } else if (pkg != null && cfg.notFreezeWhenHasFgsSet.contains(pkg) && checkAppHasForegroundService(uid)) {
                        SMilletLogger.debug("[Greezer] 批量冻结过滤前台服务：$pkg (UID: $uid)")
                        false
                    } else {
                        true
                    }
                }.toIntArray()

                if (filtered.isEmpty()) {
                    param.result = ArrayList<Int>()
                } else if (filtered.size != uids.size) {
                    param.args[0] = filtered
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val resultList = (param.result as? Collection<*>)
                    ?.mapNotNull { (it as? Number)?.toInt() }
                    ?.filter { it > 0 }
                    ?: return
                if (resultList.isEmpty()) return

                val now = System.currentTimeMillis()
                val newlyFrozen = resultList.filter { uid ->
                    val added = trackedFrozenUids.add(uid)
                    val prevTime = lastFreezeLogTimeByUid.put(uid, now) ?: 0L
                    added || (now - prevTime > 30_000L)
                }

                val reason = (param.args.getOrNull(3) as? String)?.trim().orEmpty().ifEmpty { "system" }
                val toLog = if (reason.equals("from system", ignoreCase = true)) newlyFrozen else resultList
                if (toLog.isEmpty()) return

                if (SMilletLogger.isDebugEnabled()) {
                    val detailed = toLog.joinToString(", ") { uid -> "${getPackageNameForUid(uid) ?: uid}@$uid" }
                    SMilletLogger.debug("[Greezer] 已冻结应用：[$detailed] · 触发源：$reason (共 ${toLog.size} 项)")
                } else {
                    val concise = toLog.joinToString(", ") { uid -> getPackageNameForUid(uid) ?: "uid:$uid" }
                    SMilletLogger.info("已冻结应用：$concise · 原因：$reason")
                }
            }
        })

        XposedCompat.hookAllMethods(gmsClass, "thawUid", object : MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                updateGreezeManagerService(param.thisObject)
                val uid = param.args.getOrNull(0) as? Int ?: return
                val reason = (param.args.getOrNull(2) as? String)?.trim() ?: return
                val pkg = getPackageNameForUid(uid) ?: return
                val cfg = SMilletLogger.config

                if (reason.equals("tofront", ignoreCase = true)) {
                    lastToFrontPkg = pkg
                    return
                }
                if ((reason.equals("screen on", ignoreCase = true) || reason.equals("screen_on", ignoreCase = true)) &&
                    pkg == lastToFrontPkg
                ) {
                    return
                }
                if (reason == "SmilletWhite" || isExemptWhitePkg(pkg)) {
                    return
                }

                if (isUidFrozen(uid) &&
                    pkg != "com.tencent.mobileqq" &&
                    reason.equals("broadcast", ignoreCase = true) &&
                    !isExemptWhitePkg(pkg)
                ) {
                    if (shouldEmitThrottledWarn("bcast_$uid")) {
                        SMilletLogger.warn("已拦截后台广播唤醒冻结应用：$pkg (UID: $uid)")
                    }
                    param.result = false
                    return
                }

                if ((reason.equals("Sync Binder", ignoreCase = true) || reason.equals("Check Binder", ignoreCase = true)) &&
                    cfg.forbidSyncBinderSet.contains(pkg)
                ) {
                    if (shouldEmitThrottledWarn("binder_$uid", 8_000L)) {
                        SMilletLogger.warn("已拦截同步 Binder 唤醒：$pkg (UID: $uid, 原因: $reason)")
                    }
                    param.result = false
                    return
                }

                if (cfg.blockThawSet.contains(pkg) && !ConfigKeys.isAllowedThawReason(reason)) {
                    if (shouldEmitThrottledWarn("block_$uid", 8_000L)) {
                        SMilletLogger.warn("强力压制已拦截解冻请求：$pkg (UID: $uid, 原因: $reason)")
                    }
                    param.result = false
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val uid = param.args.getOrNull(0) as? Int ?: return
                if (param.result as? Boolean != false) {
                    val reason = (param.args.getOrNull(2) as? String)?.trim().orEmpty().ifEmpty { "unknown" }
                    val wasFrozen = trackedFrozenUids.remove(uid)
                    lastFreezeLogTimeByUid.remove(uid)
                    if (wasFrozen && !reason.equals("launch_mode", ignoreCase = true)) {
                        val pkg = getPackageNameForUid(uid) ?: "uid:$uid"
                        if (SMilletLogger.isDebugEnabled()) {
                            SMilletLogger.debug("[Greezer] 已解冻应用：$pkg (UID: $uid) · 触发源：$reason")
                        } else {
                            SMilletLogger.info("已解冻应用：$pkg · 原因：$reason")
                        }
                    }
                }
            }
        })

        XposedCompat.hookAllMethods(gmsClass, "thawUids", object : MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val uids = param.args.getOrNull(0) as? IntArray ?: return
                val thawed = mutableListOf<Int>()
                for (u in uids) {
                    if (trackedFrozenUids.remove(u)) {
                        thawed.add(u)
                    }
                    lastFreezeLogTimeByUid.remove(u)
                }
                if (thawed.isNotEmpty()) {
                    val reason = (param.args.getOrNull(2) as? String)?.trim().orEmpty().ifEmpty { "batch" }
                    if (SMilletLogger.isDebugEnabled()) {
                        val names = thawed.joinToString(", ") { uid -> "${getPackageNameForUid(uid) ?: uid}@$uid" }
                        SMilletLogger.debug("[Greezer] 批量解冻应用：[$names] · 触发源：$reason")
                    } else {
                        val names = thawed.joinToString(", ") { uid -> getPackageNameForUid(uid) ?: "uid:$uid" }
                        SMilletLogger.info("批量解冻应用：$names · 原因：$reason")
                    }
                }
            }
        })

        XposedCompat.hookAllMethods(gmsClass, "thawAll", object : MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                trackedFrozenUids.clear()
                lastFreezeLogTimeByUid.clear()
            }
        })

        SMilletLogger.info("已挂载 GreezeManagerService 墓碑服务 Hook")
    }

    private fun hookAurogonImmobulusMode(classLoader: ClassLoader) {
        val aurogonClass = XposedCompat.findClassIfExists(
            "com.miui.server.greeze.AurogonImmobulusMode",
            classLoader
        ) ?: return
        hookAurogonClass(aurogonClass)
    }

    private fun hookAurogonClass(aurogonClass: Class<*>) {
        if (!hookedAurogonClasses.add(aurogonClass)) return

        XposedCompat.hookAllConstructors(aurogonClass, object : MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                aurogonImmobulusMode = param.thisObject
                syncMessageAppsToAurogon()
            }
        })

        XposedCompat.hookAllMethods(aurogonClass, "checkAppStatusForFreeze", object : MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (aurogonImmobulusMode == null) aurogonImmobulusMode = param.thisObject
                val appInfo = param.args.getOrNull(0) ?: return
                val pkg = runCatching {
                    XposedCompat.getObjectField(appInfo, "mPackageName") as? String
                }.getOrNull() ?: return
                if (isExemptWhitePkg(pkg)) {
                    param.result = false
                } else if (SMilletLogger.config.forceFreezeSet.contains(pkg)) {
                    param.result = true
                }
            }
        })
    }

    private fun hookGreezePowerPolicy(classLoader: ClassLoader) {
        for (clsName in listOf(
            "com.miui.server.greeze.power.ListAppsManager",
            "com.miui.server.greeze.power.PowerStrategyMode"
        )) {
            val cls = XposedCompat.findClassIfExists(clsName, classLoader) ?: continue
            if (!hookedPowerListClasses.add(cls)) continue

            XposedCompat.hookAllConstructors(cls, object : MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (clsName.endsWith("ListAppsManager")) {
                        listAppsManagerInstance = param.thisObject
                    } else {
                        powerStrategyModeInstance = param.thisObject
                    }
                    syncGreezePowerLists()
                }
            })

            XposedCompat.hookAllMethods(cls, "isInWhiteList", object : MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val arg0 = param.args.getOrNull(0) ?: return
                    val pkg = when (arg0) {
                        is String -> arg0
                        is Int -> getPackageNameForUid(arg0)
                        else -> null
                    } ?: return
                    if (isExemptWhitePkg(pkg)) {
                        param.result = true
                    } else if (SMilletLogger.config.forceFreezeSet.contains(pkg)) {
                        param.result = false
                    }
                }
            })

            XposedCompat.hookAllMethods(cls, "isAllowFreeze", object : MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val arg0 = param.args.getOrNull(0) ?: return
                    val pkg = when (arg0) {
                        is String -> arg0
                        is Int -> getPackageNameForUid(arg0)
                        else -> null
                    } ?: return
                    if (isExemptWhitePkg(pkg)) {
                        param.result = false
                    } else if (SMilletLogger.config.forceFreezeSet.contains(pkg)) {
                        param.result = true
                    }
                }
            })

            SMilletLogger.info("已挂载 GreezePowerPolicy 省电名单 Hook (${cls.simpleName})")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun syncGreezePowerLists() {
        val cfg = SMilletLogger.config
        for (holder in listOfNotNull(listAppsManagerInstance, powerStrategyModeInstance)) {
            for (field in holder.javaClass.declaredFields) {
                runCatching {
                    field.isAccessible = true
                    val fname = field.name.lowercase()
                    val v = field.get(holder)
                    if (v is MutableCollection<*>) {
                        val col = v as MutableCollection<String>
                        when {
                            fname.contains("message") -> {
                                col.clear()
                                col.addAll(cfg.messageAppSet)
                            }
                            fname.contains("black") -> {
                                col.addAll(cfg.forceFreezeSet)
                                col.removeAll(cfg.whiteAppSet)
                                col.remove(MODULE_PKG)
                            }
                            fname.contains("white") -> {
                                col.removeAll(cfg.forceFreezeSet)
                                col.addAll(cfg.whiteAppSet)
                                col.add(MODULE_PKG)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun hookProcessList(classLoader: ClassLoader) {
        val plClass = XposedCompat.findClassIfExists(
            "com.android.server.am.ProcessList",
            classLoader
        ) ?: return
        if (!hookedProcessListClasses.add(plClass)) return

        XposedCompat.hookAllMethods(plClass, "handleProcessStartedLocked", object : MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val proc = param.args.firstOrNull { it?.javaClass?.name?.endsWith("ProcessRecord") == true } ?: return
                val uid = runCatching { XposedCompat.getIntField(proc, "uid") }.getOrNull() ?: return
                if (uid < 10000) return
                val procName = runCatching { XposedCompat.getObjectField(proc, "processName") as? String }.getOrNull() ?: return
                if (trackedFrozenUids.remove(uid)) {
                    if (SMilletLogger.isDebugEnabled()) {
                        SMilletLogger.debug("[ProcessList] 新进程启动解冻：$procName (UID: $uid)")
                    } else {
                        SMilletLogger.info("新进程启动解冻：$procName")
                    }
                }
            }
        })
    }

    @Suppress("UNCHECKED_CAST")
    private fun syncMessageAppsToAurogon() {
        val instance = aurogonImmobulusMode ?: return
        val msgApps = SMilletLogger.config.messageAppSet
        runCatching {
            val staticField = runCatching {
                XposedCompat.getStaticObjectField(instance.javaClass, "mMessageApp")
            }.getOrNull()
            val fieldObj = staticField ?: runCatching {
                XposedCompat.getObjectField(instance, "mMessageApp")
            }.getOrNull()
            when (fieldObj) {
                is MutableList<*> -> {
                    val list = fieldObj as MutableList<String>
                    list.clear()
                    list.addAll(msgApps)
                }
                is ArraySet<*> -> {
                    val set = fieldObj as ArraySet<String>
                    set.clear()
                    set.addAll(msgApps)
                }
                else -> {
                    runCatching { XposedCompat.setStaticObjectField(instance.javaClass, "mMessageApp", ArrayList(msgApps)) }
                    runCatching { XposedCompat.setObjectField(instance, "mMessageApp", ArrayList(msgApps)) }
                }
            }
        }
    }

    private fun extractUidFromAnrArgs(args: Array<Any?>): Int? {
        for (arg in args) {
            if (arg == null) continue
            val clsName = arg.javaClass.name
            if (clsName.endsWith("ProcessRecord")) {
                val uid = runCatching { XposedCompat.getIntField(arg, "uid") }.getOrNull()
                if (uid != null) return uid
            } else if (clsName.contains("AnrRecord")) {
                val app = runCatching { XposedCompat.getObjectField(arg, "mApp") }.getOrNull()
                if (app != null) {
                    val uid = runCatching { XposedCompat.getIntField(app, "uid") }.getOrNull()
                    if (uid != null) return uid
                }
                val directUid = runCatching { XposedCompat.getIntField(arg, "mUid") }.getOrNull()
                    ?: runCatching { XposedCompat.getIntField(arg, "uid") }.getOrNull()
                if (directUid != null) return directUid
            }
        }
        return null
    }

    private fun hookAnrSuppression(classLoader: ClassLoader) {
        val anrHelperClass = XposedCompat.findClassIfExists(
            "com.android.server.am.AnrHelper",
            classLoader
        )
        if (anrHelperClass != null) {
            val anrHook = object : MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val uid = extractUidFromAnrArgs(param.args) ?: return
                    if (isUidFrozen(uid)) {
                        val pkg = getPackageNameForUid(uid) ?: "uid:$uid"
                        if (shouldEmitThrottledWarn("anr_$uid", 10_000L)) {
                            SMilletLogger.warn("已屏蔽冻结应用后台 ANR：$pkg (UID: $uid)")
                        }
                        param.result = null
                    }
                }
            }
            XposedCompat.hookAllMethods(anrHelperClass, "appNotResponding", anrHook)
            XposedCompat.hookAllMethods(anrHelperClass, "deferAppNotResponding", anrHook)
        }

        val errStateClass = XposedCompat.findClassIfExists(
            "com.android.server.am.ProcessErrorStateRecord",
            classLoader
        )
        if (errStateClass != null) {
            XposedCompat.hookAllMethods(errStateClass, "appNotResponding", object : MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val thisObj = param.thisObject ?: return
                    val app = runCatching { XposedCompat.getObjectField(thisObj, "mApp") }.getOrNull() ?: return
                    val uid = runCatching { XposedCompat.getIntField(app, "uid") }.getOrNull() ?: return
                    if (isUidFrozen(uid)) {
                        val pkg = getPackageNameForUid(uid) ?: "uid:$uid"
                        if (shouldEmitThrottledWarn("anr_err_$uid", 10_000L)) {
                            SMilletLogger.warn("已屏蔽冻结应用后台 ANR：$pkg (UID: $uid)")
                        }
                        param.result = null
                    }
                }
            })
        }
    }

    private fun hookBroadcastSkipForFrozenApp(classLoader: ClassLoader) {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.TIRAMISU) {
            val bqClass = XposedCompat.findClassIfExists(
                "com.android.server.am.BroadcastQueue",
                classLoader
            ) ?: return
            XposedCompat.hookAllMethods(bqClass, "deliverToRegisteredReceiverLocked", object : MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val filter = param.args.getOrNull(1) ?: return
                    val receiverList = runCatching { XposedCompat.getObjectField(filter, "receiverList") }.getOrNull() ?: return
                    val app = runCatching { XposedCompat.getObjectField(receiverList, "app") }.getOrNull() ?: return
                    if (shouldSkipFrozenProcess(app)) {
                        param.result = null
                    }
                }
            })
        } else {
            val skipPolicyClass = XposedCompat.findClassIfExists(
                "com.android.server.am.BroadcastSkipPolicy",
                classLoader
            ) ?: return
            XposedCompat.hookAllMethods(skipPolicyClass, "shouldSkipMessage", object : MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val filter = param.args.getOrNull(1) ?: return
                    val receiverList = runCatching { XposedCompat.getObjectField(filter, "receiverList") }.getOrNull() ?: return
                    val app = runCatching { XposedCompat.getObjectField(receiverList, "app") }.getOrNull() ?: return
                    if (shouldSkipFrozenProcess(app)) {
                        param.result = "Skip frozen process by Smillet-Next"
                    }
                }
            })
        }
    }

    private fun shouldSkipFrozenProcess(processRecord: Any): Boolean {
        val uid = runCatching { XposedCompat.getIntField(processRecord, "uid") }.getOrNull() ?: return false
        if (uid < 10000) return false
        if (!isUidFrozen(uid)) return false
        val pkg = getPackageNameForUid(uid) ?: return false
        return !isExemptWhitePkg(pkg)
    }

    private fun hookDeviceIdleController(classLoader: ClassLoader) {
        val dicClass = XposedCompat.findClassIfExists(
            "com.android.server.DeviceIdleController",
            classLoader
        )
        if (dicClass != null) {
            hookDeviceIdleControllerClass(dicClass)
        }
    }

    fun hookDeviceIdleControllerClass(dicClass: Class<*>) {
        if (!hookedDicClasses.add(dicClass)) return

        XposedCompat.hookAllConstructors(dicClass, object : MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.thisObject?.let { DozeControllerHelper.onDeviceIdleStart(it) }
            }
        })

        XposedCompat.hookAllMethods(dicClass, "onStart", object : MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.thisObject?.let { DozeControllerHelper.onDeviceIdleStart(it) }
            }
        })

        XposedCompat.hookAllMethods(dicClass, "motionLocked", object : MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val cfg = SMilletLogger.config
                if (cfg.disableMotionListener) {
                    SMilletLogger.debug("[Doze] 已拦截传感器运动唤醒检测 (motionLocked)")
                    param.result = null
                    param.thisObject?.let { obj ->
                        runCatching { XposedCompat.setBooleanField(obj, "mNotMoving", true) }
                    }
                }
            }
        })

        XposedCompat.hookAllMethods(dicClass, "moveToStateLocked", object : MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val cfg = SMilletLogger.config
                val targetState = param.args.getOrNull(0) as? Int ?: return
                if (cfg.enableDoze && cfg.skipStateCheck && targetState in 2..4) {
                    SMilletLogger.debug("[Doze] 跳过中间状态检查 (state=$targetState -> IDLE)，加速进入深度 Doze")
                    param.args[0] = 5
                    param.args[1] = "SMilletSkip"
                    param.thisObject?.let { obj ->
                        runCatching { XposedCompat.callMethod(obj, "resetIdleManagementLocked") }
                    }
                }
            }
        })

        SMilletLogger.info("已挂载 DeviceIdleController 深度 Doze Hook")
    }

    fun isUidFrozen(uid: Int): Boolean {
        if (uid <= 0) return false
        val current = greezeManagerService
        if (current == null || current.javaClass.name != GREEZE_SERVICE_CLASS) {
            ensureRuntimeServicesResolved()
        }
        val gms = greezeManagerService
        if (gms != null && gms.javaClass.name == GREEZE_SERVICE_CLASS) {
            val byMethod = runCatching {
                XposedCompat.callMethod(gms, "isUidFrozen", uid) as? Boolean
            }.getOrNull()
            if (byMethod != null) {
                if (byMethod) trackedFrozenUids.add(uid) else trackedFrozenUids.remove(uid)
                return byMethod
            }
            val byArray = runCatching {
                (XposedCompat.callMethod(gms, "getFrozenUids", 9999) as? IntArray)?.contains(uid)
            }.getOrNull()
            if (byArray != null) {
                if (byArray) trackedFrozenUids.add(uid) else trackedFrozenUids.remove(uid)
                return byArray
            }
        }
        if (isUidCgroupFrozen(uid)) {
            trackedFrozenUids.add(uid)
            return true
        }
        return trackedFrozenUids.contains(uid)
    }

    fun diagnoseFreezeStatus(packageName: String): String {
        ensureRuntimeServicesResolved()
        val ctx = getSystemContext()
        val uid = runCatching {
            ctx?.packageManager?.getApplicationInfo(
                packageName,
                PackageManager.MATCH_UNINSTALLED_PACKAGES
            )?.uid
        }.getOrNull() ?: -1

        val isGreezeFrozen = if (uid > 0) isUidFrozen(uid) else false
        val isCgroupFrozen = if (uid > 0) isUidCgroupFrozen(uid) else false
        val hasFgService = if (uid > 0) checkAppHasForegroundService(uid) else false
        val isDozeIgnored = runCatching {
            val pm = ctx?.getSystemService(android.os.PowerManager::class.java)
            pm?.isIgnoringBatteryOptimizations(packageName) == true
        }.getOrDefault(false)
        val noRestrictList = SystemServerService.noRestrictAppsRaw.orEmpty()
        val isNoRestrict = noRestrictList.split(",").map { it.trim() }.contains(packageName)

        var totalProcs = 0
        var frozenProcs = 0
        if (uid > 0) {
            runCatching {
                val json = collectRunningProcessesJson(includeSystem = true)
                if (json.startsWith("[")) {
                    val arr = org.json.JSONArray(json)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        if (obj.optInt("uid") == uid) {
                            totalProcs++
                            val wchan = obj.optString("wchan", "").lowercase()
                            val state = obj.optString("state", "")
                            val byGreeze = obj.optBoolean("isFrozenByGreeze", false)
                            if (byGreeze || wchan.contains("freezer") || wchan.contains("refrigerator") ||
                                wchan.contains("get_signal") || state == "T"
                            ) {
                                frozenProcs++
                            }
                        }
                    }
                }
            }
        }

        val stateText = when {
            isGreezeFrozen || isCgroupFrozen || (totalProcs > 0 && frozenProcs > 0) ->
                "已冻结 (Greezer=${if (isGreezeFrozen || isCgroupFrozen) "已冻结" else "未标记"}, 冻结进程 $frozenProcs/$totalProcs)"
            totalProcs > 0 ->
                "未冻结 · 正在运行 (活跃进程 $totalProcs 个)"
            else ->
                "未运行 (当前无后台进程)"
        }

        val cfg = SMilletLogger.config
        val rules = mutableListOf<String>()
        if (cfg.whiteAppSet.contains(packageName)) rules.add("墓碑白名单")
        if (cfg.forceFreezeSet.contains(packageName)) rules.add("强制冻结 (黑名单)")
        if (cfg.blockThawSet.contains(packageName)) rules.add("强力压制 (黑名单)")
        if (cfg.messageAppSet.contains(packageName)) rules.add("消息应用")
        if (cfg.forbidSyncBinderSet.contains(packageName)) rules.add("禁止同步 Binder")
        if (cfg.notFreezeWhenHasFgsSet.contains(packageName)) rules.add("前台服务不冻结")

        return buildString {
            appendLine("[SystemServer 状态]")
            appendLine("• 当前冻结状态：$stateText")
            appendLine("• 应用 UID：${if (uid > 0) uid else "未知"}")
            appendLine("• 电池优化状态：${if (isDozeIgnored) "未优化 (已豁免 Doze)" else "已优化 (受省电管控)"}")
            appendLine("• 系统省电无限制：${if (isNoRestrict) "是 (建议开启强制冻结或改为省电推荐)" else "否"}")
            appendLine("• 活跃前台服务 (FGS)：${if (hasFgService) "存在" else "无"}")
            append("• 模块已配策略：${if (rules.isEmpty()) "默认 (未配置特殊规则)" else rules.joinToString("、")}")
        }
    }

    private fun checkAppHasForegroundService(uid: Int): Boolean {
        val aurogon = aurogonImmobulusMode ?: return false
        return runCatching {
            XposedCompat.callMethod(aurogon, "checkAppFgService", uid) as? Boolean ?: false
        }.getOrDefault(false)
    }

    internal fun getSystemContext(): Context? {
        cachedSystemContext?.let { return it }
        val candidates = listOfNotNull(amsInstance, greezeManagerService)
        for (holder in candidates) {
            val ctx = runCatching { XposedCompat.getObjectField(holder, "mContext") as? Context }.getOrNull()
            if (ctx != null) {
                cachedSystemContext = ctx
                if (!screenReceiverRegistered) {
                    screenReceiverRegistered = true
                    runCatching { DozeControllerHelper.registerScreenReceiver(ctx) }
                }
                return ctx
            }
        }
        // Fallback: ActivityThread.currentActivityThread().getSystemContext() / currentApplication()
        val atCtx = runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAt = XposedCompat.callStaticMethod(atClass, "currentActivityThread")
            (if (currentAt != null) XposedCompat.callMethod(currentAt, "getSystemContext") as? Context else null)
                ?: (XposedCompat.callStaticMethod(atClass, "currentApplication") as? Context)
        }.getOrNull()
        if (atCtx != null) {
            cachedSystemContext = atCtx
            if (!screenReceiverRegistered) {
                screenReceiverRegistered = true
                runCatching { DozeControllerHelper.registerScreenReceiver(atCtx) }
            }
            return atCtx
        }
        return null
    }

    private fun getPackageNameForUid(uid: Int): String? {
        val ctx = getSystemContext() ?: return null
        return runCatching {
            ctx.packageManager.getPackagesForUid(uid)?.firstOrNull()
        }.getOrNull()
    }
}

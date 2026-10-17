package com.sidesand.millet.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sidesand.millet.data.ConfigKeys
import com.sidesand.millet.data.FreezeType
import com.sidesand.millet.data.MilletConfigSnapshot
import com.sidesand.millet.data.ProcessDetail
import com.sidesand.millet.data.RunningAppItem
import com.sidesand.millet.hook.SMilletLogger
import com.sidesand.millet.ipc.SystemInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Locale

object ProcessScanner {

    data class SysProcDto(
        val pid: Int = 0,
        val uid: Int = 0,
        val processName: String = "",
        val rssKb: Long = 0L,
        val wchan: String = "",
        val state: String = "",
        val isFrozenByGreeze: Boolean = false
    )

    data class MemoryStatus(
        val ramTotalGb: Float = 0f,
        val ramUsedGb: Float = 0f,
        val ramAvailGb: Float = 0f,
        val ramPercent: Int = 0,
        val zramTotalGb: Float = 0f,
        val zramUsedGb: Float = 0f,
        val zramFreeGb: Float = 0f,
        val zramPercent: Int = 0
    )

    private val gson = Gson()
    private val whitespaceRegex = Regex("\\s+")

    @Volatile
    private var cachedRootFallbackLogs: List<String> = emptyList()

    @Volatile
    private var lastRootLogFetchMs: Long = 0L

    /**
     * 通过 Root Shell 实时同步 Android DeviceIdle 白名单与 HyperOS 电量与性能 (com.miui.powerkeeper) userTable：
     * - 注意：PowerKeeper 的 userTable.bgControl 枚举值必须为驼峰格式 "noRestrict" / "miuiAuto"，且 bgDelayMin=-2；
     * - 同时自动修复可能残留的 "no_restrict" / "miui_auto" 错误记录，保障系统省电策略界面正常打开。
     */
    suspend fun syncBatteryStrategyViaRoot(
        packageName: String,
        ignoreBatteryOpt: Boolean
    ) = withContext(Dispatchers.IO) {
        val dbMode = if (ignoreBatteryOpt) "noRestrict" else "miuiAuto"
        val dozeCmd = if (ignoreBatteryOpt) {
            "cmd deviceidle whitelist +$packageName"
        } else {
            "cmd deviceidle whitelist -$packageName; cmd deviceidle sys-whitelist -$packageName; cmd deviceidle except-idle-whitelist -$packageName"
        }
        val repairCmd = "content update --uri content://com.miui.powerkeeper.configure/userTable --bind bgControl:s:noRestrict --bind bgDelayMin:i:-2 --where \"bgControl='no_restrict'\" >/dev/null 2>&1; " +
            "content update --uri content://com.miui.powerkeeper.configure/userTable --bind bgControl:s:miuiAuto --bind bgDelayMin:i:-2 --where \"bgControl='miui_auto'\" >/dev/null 2>&1"
        val pkUpdateCmd = "content update --uri content://com.miui.powerkeeper.configure/userTable " +
            "--bind bgControl:s:$dbMode --bind bgDelayMin:i:-2 --where \"pkgName='$packageName'\""
        val fullCmd = "$repairCmd; $dozeCmd >/dev/null 2>&1; $pkUpdateCmd >/dev/null 2>&1"
        runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", fullCmd))
            proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    /**
     * 本地 + Root + Cgroup 综合冻结状态诊断（当远程回调超时或补充进程明细时使用，确保诊断 100% 准确即时响应）
     */
    suspend fun diagnosePackageLocally(
        context: Context,
        packageName: String,
        config: MilletConfigSnapshot,
        runningItem: RunningAppItem?
    ): String = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val appInfo = runCatching {
            pm.getApplicationInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        }.getOrNull()
        val uid = appInfo?.uid ?: runningItem?.uid ?: -1

        val powerManager = context.getSystemService(PowerManager::class.java)
        val isDozeIgnored = runCatching {
            powerManager?.isIgnoringBatteryOptimizations(packageName) == true
        }.getOrDefault(false)
        val noRestrictRaw = runCatching {
            Settings.System.getString(context.contentResolver, "MILLET_NO_RESTRICT_APP")
        }.getOrNull().orEmpty()
        val isNoRestrict = noRestrictRaw.split(",").map { it.trim() }.contains(packageName) ||
            config.batteryOptWhiteSet.contains(packageName)

        // 通过 Root 检查内核 cgroup v2 冻结状态与进程实时状态
        var cgroupFrozen = false
        val rootProcs = mutableListOf<ProcessDetail>()
        if (uid > 0) {
            runCatching {
                val cmd = "cat /sys/fs/cgroup/uid_$uid/cgroup.freeze /sys/fs/cgroup/uid_$uid/pid_*/cgroup.freeze 2>/dev/null | head -n 5; " +
                    "echo '---PROC---'; ps -A -o USER,PID,RSS,WCHAN,S,ARGS | grep -E \"^u[0-9]+_a${uid % 10000}|^$uid |${packageName.replace(".", "\\.")}\""
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                val out = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
                proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                val parts = out.split("---PROC---")
                val cgPart = parts.getOrNull(0).orEmpty()
                if (cgPart.lineSequence().any { it.trim() == "1" }) {
                    cgroupFrozen = true
                }
                val psPart = parts.getOrNull(1).orEmpty()
                psPart.lineSequence().forEach { rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty() || line.startsWith("USER")) return@forEach
                    val cols = line.split(whitespaceRegex, limit = 6)
                    if (cols.size < 6) return@forEach
                    val parsedUid = parseAndroidUid(cols[0])
                    val pid = cols[1].toIntOrNull() ?: return@forEach
                    val rssKb = cols[2].toLongOrNull() ?: 0L
                    val wchan = cols[3]
                    val state = cols[4]
                    val procName = cols[5].trim().split(whitespaceRegex).firstOrNull().orEmpty()
                    if (parsedUid == uid || procName == packageName || procName.startsWith("$packageName:")) {
                        val fType = classifyFreezeState(wchan, state, cgroupFrozen)
                        rootProcs.add(
                            ProcessDetail(
                                pid = pid,
                                processName = procName,
                                rssKb = rssKb,
                                wchan = wchan,
                                state = state,
                                isFrozen = fType != FreezeType.RUNNING,
                                freezeType = fType
                            )
                        )
                    }
                }
            }
        }

        val processes = if (rootProcs.isNotEmpty()) rootProcs else (runningItem?.processes ?: emptyList())
        val totalCount = processes.size
        val frozenCount = processes.count { it.isFrozen }
        val totalRssMb = processes.sumOf { it.rssKb } / 1024f

        val stateText = when {
            cgroupFrozen || (totalCount > 0 && frozenCount > 0) -> {
                val fTypeLabel = processes.firstOrNull { it.isFrozen }?.freezeType?.label ?: "Cgroup V2 冻结"
                "已冻结 ($fTypeLabel · $frozenCount/$totalCount 进程已冻结 · ${String.format(Locale.US, "%.1f MB", totalRssMb)})"
            }
            totalCount > 0 ->
                "未冻结 · 正在运行 (活跃进程 $totalCount 个 · ${String.format(Locale.US, "%.1f MB", totalRssMb)})"
            else ->
                "未运行 (当前无后台进程)"
        }

        val rules = mutableListOf<String>()
        if (config.whiteAppSet.contains(packageName)) rules.add("墓碑白名单")
        if (config.forceFreezeSet.contains(packageName)) rules.add("强制冻结 (黑名单)")
        if (config.blockThawSet.contains(packageName)) rules.add("强力压制 (黑名单)")
        if (config.messageAppSet.contains(packageName)) rules.add("消息应用")
        if (config.forbidSyncBinderSet.contains(packageName)) rules.add("禁止同步 Binder")
        if (config.notFreezeWhenHasFgsSet.contains(packageName)) rules.add("前台服务不冻结")

        val reasons = mutableListOf<String>()
        if (config.whiteAppSet.contains(packageName)) {
            reasons.add("处于 Smillet-Next 墓碑白名单中")
        }
        if (isNoRestrict && !config.forceFreezeSet.contains(packageName)) {
            reasons.add("系统省电策略为「无限制」（若需冻结可关闭电池优化白名单或开启强制冻结）")
        }
        if (isDozeIgnored && !config.forceFreezeSet.contains(packageName) && !reasons.any { it.contains("无限制") }) {
            reasons.add("处于系统电池未优化名单中")
        }

        buildString {
            appendLine("[SystemServer 状态]")
            appendLine("• 当前冻结状态：$stateText")
            appendLine("• 应用 UID：${if (uid > 0) uid else "未知"}")
            appendLine("• 电池优化状态：${if (isDozeIgnored || isNoRestrict) "未优化 (无限制)" else "已优化 (智能限制后台)"}")
            appendLine("• 系统省电无限制：${if (isNoRestrict) "是" else "否"}")
            appendLine("• 模块已配策略：${if (rules.isEmpty()) "默认 (未配置特殊规则)" else rules.joinToString("、")}")
            if (processes.isNotEmpty()) {
                appendLine()
                appendLine("[进程冻结明细]")
                processes.take(6).forEach { p ->
                    val statusTag = if (p.isFrozen) p.freezeType.label else "运行中 (${p.wchan})"
                    appendLine("• [${p.pid}] ${p.processName} -> $statusTag")
                }
            }
            appendLine()
            if (reasons.isEmpty()) {
                append("[PowerKeeper 策略层]\n• 未发现白名单豁免项，该应用满足 Millet 冻结条件（切入后台静置后将被系统冻结）")
            } else {
                append("[PowerKeeper 策略层 · 豁免/未冻结原因]\n• " + reasons.joinToString("\n• "))
            }
        }
    }

    suspend fun checkRootAccess(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            val finished = proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                false
            } else {
                output.contains("uid=0")
            }
        }.getOrDefault(false)
    }

    suspend fun readLspVersionInfo(serviceInfo: String?, isLspActivated: Boolean): String = withContext(Dispatchers.IO) {
        if (!serviceInfo.isNullOrBlank()) {
            return@withContext serviceInfo
        }
        val fromRoot = runCatching {
            val cmd = "cat /data/adb/modules/*lsposed*/module.prop /data/adb/modules/*lspd*/module.prop 2>/dev/null"
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val text = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            var version = ""
            var versionCode = ""
            var name = "LSPosed"
            text.lineSequence().forEach { line ->
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("name=") -> {
                        val n = trimmed.substringAfter("name=").trim()
                        if (n.isNotEmpty()) name = n
                    }
                    trimmed.startsWith("version=") -> {
                        version = trimmed.substringAfter("version=").trim()
                    }
                    trimmed.startsWith("versionCode=") -> {
                        versionCode = trimmed.substringAfter("versionCode=").trim()
                    }
                }
            }
            when {
                version.isNotEmpty() && versionCode.isNotEmpty() -> "$name $version ($versionCode)"
                version.isNotEmpty() -> "$name $version"
                else -> null
            }
        }.getOrNull()

        when {
            !fromRoot.isNullOrBlank() -> fromRoot
            isLspActivated -> "LSPosed (已激活)"
            else -> "LSPosed (未激活)"
        }
    }

    suspend fun readMemoryStatus(): MemoryStatus = withContext(Dispatchers.IO) {
        var memTotalKb = 0L
        var memAvailKb = 0L
        var swapTotalKb = 0L
        var swapFreeKb = 0L

        runCatching {
            val lines = File("/proc/meminfo").readLines()
            for (line in lines) {
                when {
                    line.startsWith("MemTotal:") -> {
                        memTotalKb = line.substringAfter("MemTotal:").trim().split(whitespaceRegex).firstOrNull()?.toLongOrNull() ?: 0L
                    }
                    line.startsWith("MemAvailable:") -> {
                        memAvailKb = line.substringAfter("MemAvailable:").trim().split(whitespaceRegex).firstOrNull()?.toLongOrNull() ?: 0L
                    }
                    line.startsWith("SwapTotal:") -> {
                        swapTotalKb = line.substringAfter("SwapTotal:").trim().split(whitespaceRegex).firstOrNull()?.toLongOrNull() ?: 0L
                    }
                    line.startsWith("SwapFree:") -> {
                        swapFreeKb = line.substringAfter("SwapFree:").trim().split(whitespaceRegex).firstOrNull()?.toLongOrNull() ?: 0L
                    }
                }
            }
        }

        val memUsedKb = (memTotalKb - memAvailKb).coerceAtLeast(0L)
        val swapUsedKb = (swapTotalKb - swapFreeKb).coerceAtLeast(0L)

        val kbToGb = 1024f * 1024f
        val ramPct = if (memTotalKb > 0) ((memUsedKb * 100L) / memTotalKb).toInt().coerceIn(0, 100) else 0
        val zramPct = if (swapTotalKb > 0) ((swapUsedKb * 100L) / swapTotalKb).toInt().coerceIn(0, 100) else 0

        MemoryStatus(
            ramTotalGb = memTotalKb / kbToGb,
            ramUsedGb = memUsedKb / kbToGb,
            ramAvailGb = memAvailKb / kbToGb,
            ramPercent = ramPct,
            zramTotalGb = swapTotalKb / kbToGb,
            zramUsedGb = swapUsedKb / kbToGb,
            zramFreeGb = swapFreeKb / kbToGb,
            zramPercent = zramPct
        )
    }

    suspend fun readAllLogs(
        systemInterface: SystemInterface?,
        forceRootRefresh: Boolean = false
    ): List<String> = withContext(Dispatchers.IO) {
        val merged = LinkedHashSet<String>()

        // 1. Read via Binder IPC from system_server buffer & /data/system/SMillet/log
        runCatching {
            val ipcLogs = systemInterface?.getLogsText().orEmpty()
            if (ipcLogs.isNotBlank()) {
                ipcLogs.lineSequence()
                    .map { SMilletLogger.normalizeLogLine(it) }
                    .filter { it.isNotEmpty() }
                    .forEach { merged.add(it) }
            }
        }

        // 2. Supplement via Root from /data/system/SMillet/log and LSPosed module logs
        val now = System.currentTimeMillis()
        if (forceRootRefresh || cachedRootFallbackLogs.isEmpty() || merged.isEmpty() || (now - lastRootLogFetchMs >= 5_000L)) {
            runCatching {
                val rootLines = ArrayList<String>()
                val cmd = "cat ${ConfigKeys.LOCAL_LOG_PATH} 2>/dev/null; " +
                    "grep -h 'Smillet-Next' /data/adb/lspd/log/modules_*.log /data/adb/lspd/log.old/modules_*.log 2>/dev/null | tail -n 1500"
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines ->
                    lines.forEach { raw ->
                        val cleaned = SMilletLogger.normalizeLogLine(raw)
                        if (cleaned.isNotEmpty()) {
                            rootLines.add(cleaned)
                        }
                    }
                }
                proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                cachedRootFallbackLogs = rootLines
                lastRootLogFetchMs = now
            }
        }

        merged.addAll(cachedRootFallbackLogs)

        merged.toList().sortedBy { line ->
            if (line.length >= 23 && line.startsWith("20")) line.substring(0, 23) else line
        }
    }

    suspend fun scanRunningApps(
        context: Context,
        includeSystemApps: Boolean,
        systemInterface: SystemInterface? = null
    ): Result<List<RunningAppItem>> = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            val installedApps = pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
            val uidToAppMap = HashMap<Int, ApplicationInfo>()
            val pkgToAppMap = HashMap<String, ApplicationInfo>()

            for (info in installedApps) {
                pkgToAppMap[info.packageName] = info
                if (!uidToAppMap.containsKey(info.uid)) {
                    uidToAppMap[info.uid] = info
                }
            }

            val frozenUidsFromSystem = HashSet<Int>()
            val pidMap = LinkedHashMap<Int, Pair<Int, ProcessDetail>>()

            if (systemInterface != null) {
                runCatching {
                    val json = systemInterface.getRunningProcessesJson(includeSystemApps)
                    if (json.isNotBlank() && json.startsWith("[")) {
                        val type = object : TypeToken<List<SysProcDto>>() {}.type
                        val list: List<SysProcDto> = gson.fromJson(json, type) ?: emptyList()
                        for (dto in list) {
                            if (dto.isFrozenByGreeze) {
                                frozenUidsFromSystem.add(dto.uid)
                            }
                            val fType = classifyFreezeState(dto.wchan, dto.state, dto.isFrozenByGreeze)
                            val detail = ProcessDetail(
                                pid = dto.pid,
                                processName = dto.processName,
                                rssKb = dto.rssKb,
                                wchan = dto.wchan,
                                state = dto.state,
                                isFrozen = fType != FreezeType.RUNNING,
                                freezeType = fType
                            )
                            pidMap[dto.pid] = dto.uid to detail
                        }
                    }
                }
            }

            runCatching {
                val proc = Runtime.getRuntime().exec(
                    arrayOf("su", "-c", "ps -A -o USER,PID,RSS,WCHAN,S,ARGS")
                )
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                reader.useLines { lines ->
                    lines.forEach { rawLine ->
                        val line = rawLine.trim()
                        if (line.isEmpty() || line.startsWith("USER")) return@forEach

                        val parts = line.split(whitespaceRegex, limit = 6)
                        if (parts.size < 6) return@forEach

                        val userStr = parts[0]
                        val pid = parts[1].toIntOrNull() ?: return@forEach
                        val rssKb = parts[2].toLongOrNull() ?: 0L
                        val wchan = parts[3]
                        val state = parts[4]
                        val cmdArgs = parts[5].trim()

                        if (state == "Z" || cmdArgs.startsWith("[")) return@forEach

                        val uid = parseAndroidUid(userStr) ?: return@forEach
                        val appId = uid % 100000
                        if (!includeSystemApps && appId < 10000) return@forEach

                        val procName = cmdArgs.split(whitespaceRegex).firstOrNull() ?: cmdArgs
                        if (!procName.contains(".")) return@forEach

                        val isUidFrozen = frozenUidsFromSystem.contains(uid)
                        val freezeType = classifyFreezeState(wchan, state, isUidFrozen)
                        val detail = ProcessDetail(
                            pid = pid,
                            processName = procName,
                            rssKb = if (rssKb > 0) rssKb else (pidMap[pid]?.second?.rssKb ?: 0L),
                            wchan = if (wchan != "-" && wchan != "0") wchan else (pidMap[pid]?.second?.wchan ?: wchan),
                            state = state,
                            isFrozen = freezeType != FreezeType.RUNNING,
                            freezeType = freezeType
                        )
                        pidMap[pid] = uid to detail
                    }
                }
                proc.waitFor()
            }

            val grouped = HashMap<String, MutableList<ProcessDetail>>()
            val pkgUidMap = HashMap<String, Int>()

            for ((_, pair) in pidMap) {
                val (uid, detail) = pair
                val basePkgFromProc = detail.processName.substringBefore(":")
                val appInfo = pkgToAppMap[basePkgFromProc]
                    ?: resolveAppInfoForUid(pm, uid, uidToAppMap, pkgToAppMap)
                    ?: continue

                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (!includeSystemApps && isSystem) continue

                pkgUidMap[appInfo.packageName] = uid
                grouped.getOrPut(appInfo.packageName) { mutableListOf() }.add(detail)
            }

            val resultList = grouped.mapNotNull { (pkg, processes) ->
                val appInfo = pkgToAppMap[pkg] ?: return@mapNotNull null
                val appName = pm.getApplicationLabel(appInfo).toString().trim()
                val icon = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull()
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val totalRssMb = processes.sumOf { it.rssKb } / 1024f

                RunningAppItem(
                    uid = pkgUidMap[pkg] ?: appInfo.uid,
                    packageName = pkg,
                    appName = appName,
                    icon = icon,
                    isSystemApp = isSystem,
                    totalRssMb = totalRssMb,
                    processes = processes.sortedBy { it.processName }
                )
            }.sortedWith(
                compareByDescending<RunningAppItem> { it.frozenCount > 0 }
                    .thenByDescending { it.totalRssMb }
            )

            Result.success(resultList)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    private fun parseAndroidUid(user: String): Int? {
        user.toIntOrNull()?.let { return it }
        if (user == "system") return 1000
        if (user == "phone" || user == "radio") return 1001
        if (user == "shell") return 2000
        if (!user.startsWith("u")) return null

        val underscoreIdx = user.indexOf('_')
        if (underscoreIdx <= 1) return null
        val userId = user.substring(1, underscoreIdx).toIntOrNull() ?: return null
        val suffix = user.substring(underscoreIdx + 1)
        return when {
            suffix.startsWith("a") -> {
                val offset = suffix.substring(1).toIntOrNull() ?: return null
                userId * 100000 + 10000 + offset
            }
            suffix.startsWith("i") -> {
                val offset = suffix.substring(1).toIntOrNull() ?: return null
                userId * 100000 + 99000 + offset
            }
            else -> null
        }
    }

    private fun resolveAppInfoForUid(
        pm: PackageManager,
        uid: Int,
        uidToAppMap: Map<Int, ApplicationInfo>,
        pkgToAppMap: Map<String, ApplicationInfo>
    ): ApplicationInfo? {
        uidToAppMap[uid]?.let { return it }
        val normalizedUid = if (uid > 19999) {
            val s = uid.toString()
            ("1" + s.substring(s.length - 4)).toIntOrNull() ?: uid
        } else {
            uid
        }
        uidToAppMap[normalizedUid]?.let { return it }
        val pkgs = runCatching { pm.getPackagesForUid(normalizedUid) }.getOrNull()
        val firstPkg = pkgs?.firstOrNull() ?: return null
        return pkgToAppMap[firstPkg]
    }

    private fun classifyFreezeState(
        wchan: String,
        state: String,
        isFrozenByGreeze: Boolean
    ): FreezeType {
        val lower = wchan.lowercase()
        return when {
            lower.contains("do_freezer_trap") ||
                lower.contains("get_signal") ||
                lower.contains("freezable_schedule") ||
                lower.contains("freezer") -> FreezeType.V2
            lower.contains("__refrigerator") || lower.contains("refrigerator") -> FreezeType.V1
            lower.contains("do_signal_stop") || state.equals("T", ignoreCase = true) -> FreezeType.KILL
            isFrozenByGreeze -> FreezeType.V2
            else -> FreezeType.RUNNING
        }
    }
}

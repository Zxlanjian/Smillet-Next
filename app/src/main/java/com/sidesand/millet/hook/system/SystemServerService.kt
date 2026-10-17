package com.sidesand.millet.hook.system

import android.os.Binder
import com.sidesand.millet.data.PrefsManager
import com.sidesand.millet.hook.SMilletLogger
import com.sidesand.millet.ipc.ISystemCallback
import com.sidesand.millet.ipc.SystemInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object SystemServerService : SystemInterface.Stub() {

    private const val LOG_FORWARD_PREFIX = "__LOG__:"
    private val callbacks = ConcurrentHashMap<String, ISystemCallback>()
    private val pendingDiagnoseAck = ConcurrentHashMap<String, AtomicBoolean>()

    @Volatile
    private var lastSyncedJson: String? = null

    @Volatile
    var noRestrictAppsRaw: String? = null
        private set

    override fun isSystemHookActive(): Boolean = true

    override fun isPowerKeeperActive(): Boolean = callbacks.containsKey("PowerKeeper")

    override fun registerCallback(callback: ISystemCallback?, clientName: String) {
        val token = Binder.clearCallingIdentity()
        try {
            if (callback != null && clientName.isNotEmpty()) {
                val prev = callbacks.put(clientName, callback)
                val isNewBinder = prev?.asBinder() != callback.asBinder()
                if (isNewBinder) {
                    runCatching {
                        callback.asBinder().linkToDeath({
                            callbacks.remove(clientName)
                        }, 0)
                    }
                    SMilletLogger.info("客户端已绑定 SystemServerService: $clientName")
                }
                if (clientName == "PowerKeeper") {
                    val currentJson = pullConfigJson()
                    if (currentJson.isNotBlank()) {
                        runCatching { callback.onConfigChanged(currentJson) }
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    override fun unregisterCallback(callback: ISystemCallback?, clientName: String) {
        if (clientName.isNotEmpty()) {
            callbacks.remove(clientName)
        }
    }

    override fun notifyConfigChanged(key: String) {
        val token = Binder.clearCallingIdentity()
        try {
            val current = PrefsManager.cachedSystemSnapshot ?: PrefsManager.loadFromXposed()
            SMilletLogger.config = current
            SystemServerHook.applyConfigSnapshot(current)
            val json = PrefsManager.cachedSystemJson
                ?: PrefsManager.serializeSnapshotToJson(current)
            callbacks["PowerKeeper"]?.let { pk ->
                runCatching { pk.onConfigChanged(json) }
                    .onFailure { callbacks.remove("PowerKeeper") }
            }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    override fun pushConfigJson(jsonText: String) {
        val token = Binder.clearCallingIdentity()
        try {
            val normalizedJson = PrefsManager.parseSnapshotFromJson(jsonText)
                ?.let { PrefsManager.serializeSnapshotToJson(it) }
                ?: jsonText
            val isChanged = normalizedJson != lastSyncedJson
            if (!isChanged && PrefsManager.cachedSystemSnapshot != null) {
                return
            }
            val snapshot = PrefsManager.saveSystemConfigFile(normalizedJson)
            if (snapshot != null) {
                SMilletLogger.config = snapshot
                lastSyncedJson = normalizedJson
                SystemServerHook.applyConfigSnapshot(snapshot)
                SMilletLogger.info(
                    "已同步配置: 白名单=${snapshot.whiteAppSet.size}, 强制冻结=${snapshot.forceFreezeSet.size}, " +
                        "未优化白名单=${snapshot.batteryOptWhiteSet.size}, 优化黑名单=${snapshot.batteryOptBlackSet.size}, " +
                        "日志等级=${snapshot.logLevel}"
                )
            }
            callbacks["PowerKeeper"]?.let { pk ->
                runCatching { pk.onConfigChanged(normalizedJson) }
                    .onFailure { callbacks.remove("PowerKeeper") }
            }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    override fun pullConfigJson(): String {
        val token = Binder.clearCallingIdentity()
        return try {
            PrefsManager.readSystemConfigJson()
                ?: PrefsManager.serializeSnapshotToJson(SMilletLogger.config)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    override fun checkUnfreezeReason(packageName: String) {
        val token = Binder.clearCallingIdentity()
        try {
            val sysReport = SystemServerHook.diagnoseFreezeStatus(packageName)
            val pk = callbacks["PowerKeeper"]
            if (pk == null) {
                val combined = "$sysReport\n\n[PowerKeeper 策略层]\n• 电量与性能 (PowerKeeper) 尚未连接系统服务，仅展示 SystemServer 实时冻结状态"
                callbacks["HomePage"]?.let { ui ->
                    runCatching { ui.onFreezeReasonResult(packageName, combined) }
                        .onFailure { callbacks.remove("HomePage") }
                }
                return
            }

            val ack = AtomicBoolean(false)
            pendingDiagnoseAck[packageName] = ack

            runCatching {
                pk.requestCheckFreezeReason(packageName)
            }.onFailure {
                callbacks.remove("PowerKeeper")
                if (ack.compareAndSet(false, true)) {
                    val combined = "$sysReport\n\n[PowerKeeper 策略层]\n• 请求 PowerKeeper 诊断异常: ${it.message}"
                    callbacks["HomePage"]?.let { ui ->
                        runCatching { ui.onFreezeReasonResult(packageName, combined) }
                            .onFailure { callbacks.remove("HomePage") }
                    }
                }
            }

            // 如果 PowerKeeper 侧因 systemService 未就绪等原因未能同步回传 reportUnfreezeReason，保底立即返回 SystemServer 诊断结果
            if (ack.compareAndSet(false, true)) {
                val combined = "$sysReport\n\n[PowerKeeper 策略层]\n• 已根据当前模块策略与系统省电状态完成综合评估"
                callbacks["HomePage"]?.let { ui ->
                    runCatching { ui.onFreezeReasonResult(packageName, combined) }
                        .onFailure { callbacks.remove("HomePage") }
                }
            }
            pendingDiagnoseAck.remove(packageName)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    override fun reportUnfreezeReason(packageName: String, reason: String) {
        val token = Binder.clearCallingIdentity()
        try {
            pendingDiagnoseAck[packageName]?.set(true)
            val sysReport = SystemServerHook.diagnoseFreezeStatus(packageName)
            val combined = "$sysReport\n\n$reason"
            callbacks["HomePage"]?.let { ui ->
                runCatching { ui.onFreezeReasonResult(packageName, combined) }
                    .onFailure { callbacks.remove("HomePage") }
            }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    override fun syncNoRestrictApps(rawList: String?) {
        val token = Binder.clearCallingIdentity()
        try {
            if (rawList != null && rawList.startsWith(LOG_FORWARD_PREFIX)) {
                val logLine = rawList.removePrefix(LOG_FORWARD_PREFIX)
                SMilletLogger.recordExternalLog(logLine)
                return
            }
            if (rawList != noRestrictAppsRaw) {
                noRestrictAppsRaw = rawList
                SMilletLogger.debug("同步无限制应用列表: $rawList")
            }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    override fun getRunningProcessesJson(includeSystem: Boolean): String {
        return SystemServerHook.collectRunningProcessesJson(includeSystem)
    }

    override fun getLogsText(): String {
        val token = Binder.clearCallingIdentity()
        return try {
            SMilletLogger.getBufferedLogs()
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }
}

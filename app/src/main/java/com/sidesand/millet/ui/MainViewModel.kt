package com.sidesand.millet.ui

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sidesand.millet.data.InstalledAppItem
import com.sidesand.millet.data.MilletConfigSnapshot
import com.sidesand.millet.data.PrefsManager
import com.sidesand.millet.data.RunningAppItem
import com.sidesand.millet.ipc.BinderBridge
import com.sidesand.millet.ipc.ISystemCallback
import com.sidesand.millet.ipc.SystemInterface
import com.sidesand.millet.util.ProcessScanner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val prefsManager = PrefsManager.fromContext(application)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _config = MutableStateFlow(prefsManager.snapshot())
    val config: StateFlow<MilletConfigSnapshot> = _config.asStateFlow()

    private val _isLspActivated = MutableStateFlow(PrefsManager.isModuleActivatedInLsp(application))
    val isLspActivated: StateFlow<Boolean> = _isLspActivated.asStateFlow()

    private val _lspVersionInfo = MutableStateFlow(PrefsManager.lspFrameworkVersion ?: "LSPosed (检测中...)")
    val lspVersionInfo: StateFlow<String> = _lspVersionInfo.asStateFlow()

    private val _isRootGranted = MutableStateFlow(true)
    val isRootGranted: StateFlow<Boolean> = _isRootGranted.asStateFlow()

    private val _isSystemActive = MutableStateFlow(false)
    val isSystemActive: StateFlow<Boolean> = _isSystemActive.asStateFlow()

    private val _isPowerKeeperActive = MutableStateFlow(false)
    val isPowerKeeperActive: StateFlow<Boolean> = _isPowerKeeperActive.asStateFlow()

    private val _runningApps = MutableStateFlow<List<RunningAppItem>>(emptyList())
    val runningApps: StateFlow<List<RunningAppItem>> = _runningApps.asStateFlow()

    private val _isScanningRunning = MutableStateFlow(false)
    val isScanningRunning: StateFlow<Boolean> = _isScanningRunning.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppItem>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppItem>> = _installedApps.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    private val _memoryStatus = MutableStateFlow(ProcessScanner.MemoryStatus())
    val memoryStatus: StateFlow<ProcessScanner.MemoryStatus> = _memoryStatus.asStateFlow()

    private val _logsList = MutableStateFlow<List<String>>(emptyList())
    val logsList: StateFlow<List<String>> = _logsList.asStateFlow()

    private val _isLoadingLogs = MutableStateFlow(false)
    val isLoadingLogs: StateFlow<Boolean> = _isLoadingLogs.asStateFlow()

    private val _diagnosticDialogText = MutableStateFlow<Pair<String, String>?>(null)
    val diagnosticDialogText: StateFlow<Pair<String, String>?> = _diagnosticDialogText.asStateFlow()

    @Volatile
    private var systemInterface: SystemInterface? = null

    private var logRefreshJob: Job? = null

    @Volatile
    private var pendingDiagnoseDeferred: CompletableDeferred<String>? = null

    private val uiCallback = object : ISystemCallback.Stub() {
        override fun onConfigChanged(key: String) {}
        override fun requestCheckFreezeReason(packageName: String) {}
        override fun onFreezeReasonResult(packageName: String, reason: String) {
            pendingDiagnoseDeferred?.complete(reason)
        }
    }

    init {
        PrefsManager.ensureServiceListenerRegistered { bound ->
            _isLspActivated.value = bound || _isSystemActive.value
            PrefsManager.lspFrameworkVersion?.let { _lspVersionInfo.value = it }
        }
        connectAndRefresh(includeSystem = false)
        loadInstalledApps()
    }

    fun isModuleActiveNow(): Boolean {
        return _isLspActivated.value && _isSystemActive.value
    }

    private fun rejectIfModuleInactive(): Boolean {
        if (!isModuleActiveNow()) {
            Toast.makeText(
                getApplication(),
                "模块未激活，对应用配置的修改不会保存或生效",
                Toast.LENGTH_SHORT
            ).show()
            return true
        }
        return false
    }

    fun connectAndRefresh(includeSystem: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            _isScanningRunning.value = true
            val app = getApplication<Application>()
            val lspCheck = PrefsManager.isModuleActivatedInLsp(app)
            val rootOk = ProcessScanner.checkRootAccess()
            _isRootGranted.value = rootOk

            _memoryStatus.value = ProcessScanner.readMemoryStatus()

            val binder = BinderBridge.fetchSystemInterface(app)
            systemInterface = binder
            val sysOk = runCatching { binder?.isSystemHookActive() == true }.getOrDefault(false)
            val pkOk = runCatching { binder?.isPowerKeeperActive() == true }.getOrDefault(false)
            if (sysOk && binder != null) {
                runCatching { binder.registerCallback(uiCallback, "HomePage") }
                runCatching { binder.pushConfigJson(prefsManager.exportBackupJson()) }
            }
            val activated = lspCheck || sysOk
            _isLspActivated.value = activated
            _isSystemActive.value = sysOk
            _isPowerKeeperActive.value = pkOk
            _lspVersionInfo.value = ProcessScanner.readLspVersionInfo(
                serviceInfo = PrefsManager.lspFrameworkVersion,
                isLspActivated = activated
            )

            val res = ProcessScanner.scanRunningApps(
                context = app,
                includeSystemApps = includeSystem,
                systemInterface = binder
            )
            res.onSuccess { _runningApps.value = it }
            _isScanningRunning.value = false
        }
    }

    fun refreshMemoryStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val next = ProcessScanner.readMemoryStatus()
            if (next != _memoryStatus.value) {
                _memoryStatus.value = next
            }
        }
    }

    fun refreshLogs(silent: Boolean = false) {
        if (logRefreshJob?.isActive == true && silent) return
        logRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            if (!silent || _logsList.value.isEmpty()) {
                _isLoadingLogs.value = true
            }
            val binder = systemInterface ?: BinderBridge.fetchSystemInterface(getApplication()).also {
                systemInterface = it
            }
            _logsList.value = ProcessScanner.readAllLogs(
                systemInterface = binder,
                forceRootRefresh = !silent
            )
            _isLoadingLogs.value = false
        }
    }

    fun stopLogPolling() {
        logRefreshJob?.cancel()
        logRefreshJob = null
        _isLoadingLogs.value = false
    }

    fun refreshRunningApps(includeSystem: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            _isScanningRunning.value = true
            _memoryStatus.value = ProcessScanner.readMemoryStatus()
            if (systemInterface == null) {
                systemInterface = BinderBridge.fetchSystemInterface(getApplication())
            }
            val res = ProcessScanner.scanRunningApps(
                context = getApplication(),
                includeSystemApps = includeSystem,
                systemInterface = systemInterface
            )
            res.onSuccess { _runningApps.value = it }
            _isScanningRunning.value = false
        }
    }

    fun refreshBatteryOptimizationStates() {
        viewModelScope.launch(Dispatchers.IO) {
            val current = _installedApps.value
            if (current.isEmpty()) return@launch
            val context = getApplication<Application>()
            val powerManager = context.getSystemService(PowerManager::class.java)
            val noRestrictRaw = runCatching {
                Settings.System.getString(context.contentResolver, "MILLET_NO_RESTRICT_APP")
            }.getOrNull().orEmpty()
            val noRestrictSet = noRestrictRaw.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            val snap = _config.value
            var changed = false
            val updated = current.map { item ->
                val pkg = item.packageName
                val sysIgnored = (powerManager?.isIgnoringBatteryOptimizations(pkg) == true) ||
                    noRestrictSet.contains(pkg)
                val finalIgnored = when {
                    snap.batteryOptWhiteSet.contains(pkg) -> true
                    snap.batteryOptBlackSet.contains(pkg) -> false
                    else -> sysIgnored
                }
                if (item.isBatteryOptIgnored != finalIgnored) {
                    changed = true
                    item.copy(isBatteryOptIgnored = finalIgnored)
                } else {
                    item
                }
            }
            if (changed) {
                _installedApps.value = updated
            }
        }
    }

    fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoadingApps.value = true
            val context = getApplication<Application>()
            val pm = context.packageManager
            val powerManager = context.getSystemService(PowerManager::class.java)
            val noRestrictRaw = runCatching {
                Settings.System.getString(context.contentResolver, "MILLET_NO_RESTRICT_APP")
            }.getOrNull().orEmpty()
            val noRestrictSet = noRestrictRaw.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            val snap = _config.value
            val list = pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
                .map { info ->
                    val pkg = info.packageName
                    val isSys = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val sysIgnored = (powerManager?.isIgnoringBatteryOptimizations(pkg) == true) ||
                        noRestrictSet.contains(pkg)
                    val finalIgnored = when {
                        snap.batteryOptWhiteSet.contains(pkg) -> true
                        snap.batteryOptBlackSet.contains(pkg) -> false
                        else -> sysIgnored
                    }
                    InstalledAppItem(
                        uid = info.uid,
                        packageName = pkg,
                        appName = pm.getApplicationLabel(info).toString().trim(),
                        icon = runCatching { pm.getApplicationIcon(info) }.getOrNull(),
                        isSystemApp = isSys,
                        isBatteryOptIgnored = finalIgnored
                    )
                }
                .sortedBy { it.appName.lowercase() }
            _installedApps.value = list
            _isLoadingApps.value = false
        }
    }

    fun updateBoolean(key: String, value: Boolean) {
        prefsManager.putBoolean(key, value)
        _config.value = prefsManager.snapshot()
        pushConfigToSystem(key)
    }

    fun updateInt(key: String, value: Int) {
        prefsManager.putInt(key, value)
        _config.value = prefsManager.snapshot()
        pushConfigToSystem(key)
    }

    fun updateString(key: String, value: String) {
        prefsManager.putString(key, value)
        _config.value = prefsManager.snapshot()
        pushConfigToSystem(key)
    }

    fun updateAppSet(key: String, packageName: String, enabled: Boolean) {
        if (rejectIfModuleInactive()) return
        prefsManager.updateAppSet(key, packageName, enabled)
        _config.value = prefsManager.snapshot()
        pushConfigToSystem(key)
    }

    fun setWhiteListApp(packageName: String, enabled: Boolean) {
        if (rejectIfModuleInactive()) return
        prefsManager.setWhiteListApp(packageName, enabled)
        _config.value = prefsManager.snapshot()
        pushConfigToSystem("whiteAppSet")
    }

    fun setBatteryOptimization(packageName: String, ignoreBatteryOpt: Boolean) {
        if (rejectIfModuleInactive()) return
        prefsManager.setBatteryOptimization(packageName, ignoreBatteryOpt)
        _config.value = prefsManager.snapshot()
        _installedApps.value = _installedApps.value.map { item ->
            if (item.packageName == packageName) {
                item.copy(isBatteryOptIgnored = ignoreBatteryOpt)
            } else {
                item
            }
        }
        val json = prefsManager.exportBackupJson()
        viewModelScope.launch(Dispatchers.IO) {
            val binder = systemInterface ?: BinderBridge.fetchSystemInterface(getApplication()).also {
                systemInterface = it
            }
            runCatching {
                binder?.pushConfigJson(json)
            }
            ProcessScanner.syncBatteryStrategyViaRoot(packageName, ignoreBatteryOpt)
            val context = getApplication<Application>()
            val powerManager = context.getSystemService(PowerManager::class.java)
            val actualIgnored = runCatching {
                powerManager?.isIgnoringBatteryOptimizations(packageName) ?: ignoreBatteryOpt
            }.getOrDefault(ignoreBatteryOpt)
            _installedApps.value = _installedApps.value.map { item ->
                if (item.packageName == packageName) {
                    item.copy(isBatteryOptIgnored = actualIgnored)
                } else {
                    item
                }
            }
        }
    }

    /**
     * 诊断冻结状态：
     * - 先通过 Binder IPC 一步获取完整的 SystemServer + PowerKeeper 诊断文本（耗时 < 15ms），
     *   避免先弹半截占位提示再跳变高度的「闪现」现象；
     * - 若 Binder 未连接或返回信息缺失，则自动回退至本地 Cgroup + Root 进程诊断，最终一次性弹出完整诊断窗口。
     */
    fun requestDiagnoseApp(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val deferred = CompletableDeferred<String>()
            pendingDiagnoseDeferred = deferred

            val binder = systemInterface ?: BinderBridge.fetchSystemInterface(app).also {
                systemInterface = it
            }
            if (binder != null) {
                runCatching {
                    binder.registerCallback(uiCallback, "HomePage")
                    binder.checkUnfreezeReason(packageName)
                }
            }

            val remoteReport = if (binder != null) {
                withTimeoutOrNull(180L) { deferred.await() }
            } else {
                null
            }

            val finalReport = if (!remoteReport.isNullOrBlank() && !remoteReport.contains("应用 UID：未知")) {
                remoteReport
            } else {
                val runningItem = _runningApps.value.firstOrNull { it.packageName == packageName }
                ProcessScanner.diagnosePackageLocally(
                    context = app,
                    packageName = packageName,
                    config = _config.value,
                    runningItem = runningItem
                )
            }

            mainHandler.post {
                _diagnosticDialogText.value = packageName to finalReport
            }
        }
    }

    fun dismissDiagnosticDialog() {
        _diagnosticDialogText.value = null
    }

    fun restoreFromBackupJson(jsonText: String): Boolean {
        val ok = prefsManager.importBackupJson(jsonText)
        if (!ok) return false
        val newSnap = prefsManager.snapshot()
        _config.value = newSnap
        _installedApps.value = _installedApps.value.map { item ->
            val pkg = item.packageName
            val ignored = when {
                newSnap.batteryOptWhiteSet.contains(pkg) -> true
                newSnap.batteryOptBlackSet.contains(pkg) -> false
                else -> item.isBatteryOptIgnored
            }
            item.copy(isBatteryOptIgnored = ignored)
        }
        pushConfigToSystem("ALL")
        return true
    }

    fun reloadSnapshot() {
        _config.value = prefsManager.snapshot()
        pushConfigToSystem("ALL")
    }

    private fun pushConfigToSystem(key: String) {
        val json = prefsManager.exportBackupJson()
        viewModelScope.launch(Dispatchers.IO) {
            val binder = systemInterface ?: BinderBridge.fetchSystemInterface(getApplication()).also {
                systemInterface = it
            }
            runCatching {
                binder?.pushConfigJson(json)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopLogPolling()
        runCatching { systemInterface?.unregisterCallback(uiCallback, "HomePage") }
    }
}

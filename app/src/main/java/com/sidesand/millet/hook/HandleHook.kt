package com.sidesand.millet.hook

import com.sidesand.millet.hook.powerkeeper.PowerKeeperHook
import com.sidesand.millet.hook.system.SystemServerHook
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LSPosed Modern API 102 模块入口类
 * (META-INF/xposed/java_init.list -> com.sidesand.millet.hook.HandleHook)
 */
class HandleHook : XposedModule() {

    private val systemHookInitialized = AtomicBoolean(false)

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        super.onModuleLoaded(param)
        XposedCompat.init(this)
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        super.onSystemServerStarting(param)
        XposedCompat.init(this)
        if (systemHookInitialized.compareAndSet(false, true)) {
            SystemServerHook.init(param.classLoader)
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        XposedCompat.init(this)
        if (!param.isFirstPackage) return
        when (param.packageName) {
            "android", "system" -> {
                if (systemHookInitialized.compareAndSet(false, true)) {
                    SystemServerHook.init(param.classLoader)
                }
            }
            "com.miui.powerkeeper" -> {
                PowerKeeperHook.init(param.classLoader)
            }
        }
    }
}

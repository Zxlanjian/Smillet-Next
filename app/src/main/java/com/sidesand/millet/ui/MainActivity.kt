package com.sidesand.millet.ui

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sidesand.millet.ui.theme.SmilletNextTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val mainViewModel: MainViewModel = viewModel()
            val config by mainViewModel.config.collectAsState()

            // 1. 隐藏最近任务卡片（即时生效）
            LaunchedEffect(config.hideTaskCard) {
                runCatching {
                    val am = getSystemService(ActivityManager::class.java)
                    am?.appTasks?.firstOrNull()?.setExcludeFromRecents(config.hideTaskCard)
                }
            }

            // 2. 预测性返回手势（对齐 YuKongA/Mishka：反射调用 ApplicationInfo.setEnableOnBackInvokedCallback）
            LaunchedEffect(config.predictiveBackEnabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    runCatching {
                        val method = ApplicationInfo::class.java.getDeclaredMethod(
                            "setEnableOnBackInvokedCallback",
                            Boolean::class.javaPrimitiveType
                        )
                        method.isAccessible = true
                        method.invoke(applicationInfo, config.predictiveBackEnabled)
                    }
                }
            }

            // 3. 系统级深浅色模式同步
            LaunchedEffect(config.themeColorMode) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching {
                        val uiModeManager = getSystemService(UiModeManager::class.java)
                        val mode = when (config.themeColorMode) {
                            1 -> UiModeManager.MODE_NIGHT_NO
                            2 -> UiModeManager.MODE_NIGHT_YES
                            else -> UiModeManager.MODE_NIGHT_AUTO
                        }
                        uiModeManager?.setApplicationNightMode(mode)
                    }
                }
            }

            SmilletNextTheme(config = config) {
                MainScreen(viewModel = mainViewModel)
            }
        }
    }
}

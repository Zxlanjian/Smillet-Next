package com.sidesand.millet.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sidesand.millet.data.ConfigKeys
import com.sidesand.millet.data.MilletConfigSnapshot
import com.sidesand.millet.ui.MainViewModel
import com.sidesand.millet.ui.components.AppIcon
import com.sidesand.millet.ui.theme.LocalAppDarkMode
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AppConfigDialog(
    packageName: String,
    appName: String,
    config: MilletConfigSnapshot,
    viewModel: MainViewModel,
    scrollBehavior: ScrollBehavior,
    contentPadding: PaddingValues,
    onDismiss: () -> Unit
) {
    val installedApps by viewModel.installedApps.collectAsState()
    val diagnosticPair by viewModel.diagnosticDialogText.collectAsState()
    val isDark = LocalAppDarkMode.current

    val appItem = remember(installedApps, packageName) {
        installedApps.firstOrNull { it.packageName == packageName }
    }

    val isWhite = config.whiteAppSet.contains(packageName)
    val isMessage = config.messageAppSet.contains(packageName)
    val isForceFreeze = config.forceFreezeSet.contains(packageName)
    val isBlockThaw = config.blockThawSet.contains(packageName)
    val isForbidSyncBinder = config.forbidSyncBinderSet.contains(packageName)
    val isNotFreezeFgs = config.notFreezeWhenHasFgsSet.contains(packageName)
    val isBatteryOptWhite = config.batteryOptWhiteSet.contains(packageName) ||
        (appItem?.isBatteryOptIgnored == true && !config.batteryOptBlackSet.contains(packageName))

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(
                            drawable = appItem?.icon,
                            appName = appName,
                            size = 52.dp
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = appName,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 18.sp
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = packageName,
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            appItem?.let {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "UID: ${it.uid} · ${if (it.isSystemApp) "系统应用" else "用户应用"}",
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextButton(
                            text = "诊断冻结状态",
                            onClick = { viewModel.requestDiagnoseApp(packageName) },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            text = "返回列表",
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = "电池优化设置",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 6.dp, bottom = 2.dp)
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    title = "电池优化",
                    summary = if (isBatteryOptWhite) {
                        "当前状态：未优化 · HyperOS 省电策略「无限制」"
                    } else {
                        "当前状态：已优化 · HyperOS 省电策略「智能限制后台」"
                    },
                    checked = isBatteryOptWhite,
                    onCheckedChange = { enabled ->
                        viewModel.setBatteryOptimization(packageName, enabled)
                    }
                )
            }
        }

        item {
            Text(
                text = "基础墓碑策略",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 6.dp, bottom = 2.dp)
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SwitchPreference(
                        title = "墓碑白名单",
                        summary = "始终豁免 Millet 冻结并保持广播接收；开启后自动关闭下方冻结增强选项",
                        checked = isWhite,
                        onCheckedChange = { enabled ->
                            viewModel.setWhiteListApp(packageName, enabled)
                        }
                    )
                    SwitchPreference(
                        title = "设为消息应用",
                        summary = "冻结后收到网络数据包时短暂解冻数秒处理推送，完成后重新冻结（适合微信、QQ）",
                        checked = isMessage,
                        enabled = !isWhite,
                        onCheckedChange = { enabled ->
                            viewModel.updateAppSet(ConfigKeys.MESSAGE_APP_SET, packageName, enabled)
                        }
                    )
                }
            }
        }

        item {
            Text(
                text = "进阶冻结增强 (黑名单)",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 6.dp, bottom = 2.dp)
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SwitchPreference(
                        title = "强制冻结",
                        summary = "从电量与性能内置白名单及预加载白名单中移除，强制纳入冻结黑名单",
                        checked = isForceFreeze,
                        enabled = !isWhite,
                        onCheckedChange = { enabled ->
                            viewModel.updateAppSet(ConfigKeys.FORCE_FREEZE_SET, packageName, enabled)
                        }
                    )
                    SwitchPreference(
                        title = "强力压制",
                        summary = "拦截非核心解冻请求，仅允许前台启动、通知点击等必要场景解冻",
                        checked = isBlockThaw,
                        enabled = !isWhite,
                        onCheckedChange = { enabled ->
                            viewModel.updateAppSet(ConfigKeys.BLOCK_THAW_SET, packageName, enabled)
                        }
                    )
                    SwitchPreference(
                        title = "禁止同步 Binder 解冻",
                        summary = "拦截因系统或其他组件发起 Sync Binder / Check Binder 引起的连带唤醒",
                        checked = isForbidSyncBinder,
                        enabled = !isWhite,
                        onCheckedChange = { enabled ->
                            viewModel.updateAppSet(ConfigKeys.FORBID_SYNC_BINDER_SET, packageName, enabled)
                        }
                    )
                    SwitchPreference(
                        title = "前台服务不冻结",
                        summary = "检测到应用存在活跃前台服务 (ForegroundService) 时暂缓冻结（适合音乐、导航）",
                        checked = isNotFreezeFgs,
                        enabled = !isWhite,
                        onCheckedChange = { enabled ->
                            viewModel.updateAppSet(ConfigKeys.NOT_FREEZE_WHEN_HAS_FGS_SET, packageName, enabled)
                        }
                    )
                }
            }
        }
    }

    // 在 AppConfigDialog 所在的二级页 Scaffold 内部直接挂载 OverlayDialog，确保点击「诊断冻结状态」按钮 100% 弹出
    diagnosticPair?.let { (pkg, reason) ->
        DiagnosticFreezeOverlayDialog(
            packageName = pkg,
            reportText = reason,
            isDark = isDark,
            onDismiss = { viewModel.dismissDiagnosticDialog() }
        )
    }
}

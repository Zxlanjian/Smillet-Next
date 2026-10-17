package com.sidesand.millet.ui.pages

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sidesand.millet.data.FreezeType
import com.sidesand.millet.data.RunningAppItem
import com.sidesand.millet.ui.MainViewModel
import com.sidesand.millet.ui.components.AppIcon
import com.sidesand.millet.ui.theme.LocalAppDarkMode
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

@Composable
fun RunningListPage(
    viewModel: MainViewModel,
    scrollBehavior: ScrollBehavior,
    contentPadding: PaddingValues,
    onOpenAppConfig: (packageName: String, appName: String) -> Unit,
    onDiagnoseApp: (packageName: String) -> Unit
) {
    val runningApps by viewModel.runningApps.collectAsState()
    val isScanning by viewModel.isScanningRunning.collectAsState()
    val diagnosticPair by viewModel.diagnosticDialogText.collectAsState()
    val isDark = LocalAppDarkMode.current
    val cardBgColor = if (isDark) Color(0xFF242426) else Color(0xFFFFFFFF)
    val haptic = LocalHapticFeedback.current

    val frozenCount = runningApps.count { it.frozenCount > 0 }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                text = "进程监视 (${runningApps.size} 应用 · $frozenCount 已冻结 · 长按应用可诊断)",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 4.dp, bottom = 2.dp)
            )
        }

        if (runningApps.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isScanning) {
                            "正在扫描运行进程..."
                        } else {
                            "暂无监视器数据，请点击右上角刷新重试"
                        },
                        fontSize = 15.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        } else {
            items(runningApps, key = { it.packageName }) { item ->
                RunningAppRow(
                    item = item,
                    cardBgColor = cardBgColor,
                    isDark = isDark,
                    onClick = { onOpenAppConfig(item.packageName, item.appName) },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDiagnoseApp(item.packageName)
                    }
                )
            }
        }
    }

    // 在当前页面的 Scaffold 内部挂载 OverlayDialog，确保长按应用诊断弹窗 100% 正常显示
    diagnosticPair?.let { (pkg, reason) ->
        DiagnosticFreezeOverlayDialog(
            packageName = pkg,
            reportText = reason,
            isDark = isDark,
            onDismiss = { viewModel.dismissDiagnosticDialog() }
        )
    }
}

@Composable
internal fun DiagnosticFreezeOverlayDialog(
    packageName: String,
    reportText: String,
    isDark: Boolean,
    onDismiss: () -> Unit
) {
    val blockBg = if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f)
    val sections = reportText.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }

    OverlayDialog(
        show = true,
        title = "冻结状态诊断",
        summary = packageName,
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (sections.isEmpty()) {
                Text(
                    text = reportText,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            } else {
                sections.forEach { sec ->
                    val lines = sec.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    val header = lines.firstOrNull()?.takeIf { it.startsWith("[") && it.endsWith("]") }
                        ?.removeSurrounding("[", "]")
                    val bodyLines = if (header != null) lines.drop(1) else lines

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(blockBg)
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        if (header != null) {
                            Text(
                                text = header,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MiuixTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        Text(
                            text = bodyLines.joinToString("\n"),
                            fontSize = 13.5.sp,
                            lineHeight = 20.sp,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            TextButton(
                text = "我知道了",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RunningAppRow(
    item: RunningAppItem,
    cardBgColor: Color,
    isDark: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val (badgeText, badgeColor) = when (item.primaryFreezeType) {
        FreezeType.V2 -> "V2 冻结" to Color(0xFF2874F0)
        FreezeType.V1 -> "V1 冻结" to Color(0xFF169976)
        FreezeType.KILL -> "SIGSTOP" to Color(0xFFD94A4A)
        FreezeType.RUNNING -> "运行中" to if (isDark) Color(0xFF3A3A3C) else Color(0xFF2C2C2E)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(cardBgColor)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(
            drawable = item.icon,
            appName = item.appName,
            size = 46.dp
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${item.appName} (${item.frozenCount}/${item.totalCount})",
                fontSize = 17.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${item.packageName} · ${String.format(Locale.US, "%.1f MB", item.totalRssMb)}",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(badgeColor)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = badgeText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
        }
    }
}

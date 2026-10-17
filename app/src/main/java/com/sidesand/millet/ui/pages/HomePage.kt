package com.sidesand.millet.ui.pages

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sidesand.millet.data.MilletConfigSnapshot
import com.sidesand.millet.ui.MainViewModel
import com.sidesand.millet.ui.theme.LocalAppDarkMode
import com.sidesand.millet.ui.theme.LocalAppMonetEnabled
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

private val apiMidDotRegex = Regex("\\s*·\\s*API\\s*\\d+", RegexOption.IGNORE_CASE)
private val apiDotRegex = Regex("\\s*\\.\\s*api\\s*\\d+", RegexOption.IGNORE_CASE)
private val lspPrefixRegex = Regex("^LSPosed\\s*[:：]\\s*", RegexOption.IGNORE_CASE)

@Composable
fun HomePage(
    viewModel: MainViewModel,
    config: MilletConfigSnapshot,
    isSystemActive: Boolean,
    isPowerKeeperActive: Boolean,
    scrollBehavior: ScrollBehavior,
    contentPadding: PaddingValues,
    onNavigateToWhitelist: () -> Unit,
    onNavigateToBlacklist: () -> Unit,
    onNavigateToRunning: () -> Unit
) {
    val runningApps by viewModel.runningApps.collectAsState()
    val isLspActivated by viewModel.isLspActivated.collectAsState()
    val lspVersionInfo by viewModel.lspVersionInfo.collectAsState()
    val isRootGranted by viewModel.isRootGranted.collectAsState()
    val memoryStatus by viewModel.memoryStatus.collectAsState()

    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshMemoryStatus()
            delay(1000L)
        }
    }

    val frozenCount = runningApps.count { it.frozenCount > 0 }
    val frozenProcessCount = runningApps.sumOf { it.frozenCount }
    val blackCount = (config.forceFreezeSet + config.blockThawSet).size
    val isDark = LocalAppDarkMode.current
    val monetEnabled = LocalAppMonetEnabled.current
    val primaryColor = MiuixTheme.colorScheme.primary

    val isModuleActive = isLspActivated && isSystemActive

    val warnBgColor = if (isDark) Color(0xFF3E1D1E) else Color(0xFFFBE9E9)
    val warnTextColor = if (isDark) Color(0xFFFF7A7C) else Color(0xFFD9383A)

    val statusCardBgColor = if (isModuleActive) {
        if (monetEnabled) {
            primaryColor.copy(alpha = if (isDark) 0.24f else 0.16f)
        } else {
            if (isDark) Color(0xFF163324) else Color(0xFFDEFCE5)
        }
    } else {
        if (isDark) Color(0xFF3A1C1E) else Color(0xFFFDE8E8)
    }
    val statusTitleColor = if (isModuleActive) {
        if (monetEnabled) {
            primaryColor
        } else {
            if (isDark) Color(0xFF5CE09B) else Color(0xFF3EA346)
        }
    } else {
        if (isDark) Color(0xFFFF8080) else Color(0xFFD9383A)
    }
    val cornerBadgeColor = if (isModuleActive) {
        if (monetEnabled) {
            primaryColor.copy(alpha = if (isDark) 0.85f else 0.80f)
        } else {
            if (isDark) Color(0xFF38C96A) else Color(0xFF3EA346)
        }
    } else {
        if (isDark) Color(0xFFFF5252) else Color(0xFFE53935)
    }

    val cleanLspInfo = remember(lspVersionInfo) {
        lspVersionInfo
            .replace(apiMidDotRegex, "")
            .replace(apiDotRegex, "")
            .replaceFirst(lspPrefixRegex, "")
            .trim()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!isModuleActive) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(warnBgColor)
                        .clickable { viewModel.connectAndRefresh(includeSystem = false) }
                        .padding(horizontal = 18.dp, vertical = 16.dp)
                ) {
                    Text(
                        text = if (!isLspActivated) {
                            "模块未被 LSPosed 激活：请在 LSPosed 管理器中启用 Smillet-Next，勾选「系统框架 (system/android)」与「电量与性能 (com.miui.powerkeeper)」作用域后重启系统。"
                        } else {
                            "模块已在 LSPosed 启用但系统服务未生效：请确认已勾选「系统框架 (system/android)」与「电量与性能 (com.miui.powerkeeper)」作用域并重启手机。"
                        },
                        color = warnTextColor,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        if (!isRootGranted) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(warnBgColor)
                        .clickable { viewModel.connectAndRefresh(includeSystem = false) }
                        .padding(horizontal = 18.dp, vertical = 16.dp)
                ) {
                    Text(
                        text = "未授予 Root 权限：无法执行底层进程冻结状态扫描与 Doze 省电控制命令，请在 Root 管理器中授权 Smillet-Next 后点击此横幅重试。",
                        color = warnTextColor,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        if (isModuleActive && !isPowerKeeperActive) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(warnBgColor)
                        .clickable { viewModel.connectAndRefresh(includeSystem = false) }
                        .padding(horizontal = 18.dp, vertical = 16.dp)
                ) {
                    Text(
                        text = "电量与性能 (PowerKeeper) 尚未连接：请确认 LSPosed 已勾选「电量与性能 (com.miui.powerkeeper)」作用域，或点击此处重连刷新。",
                        color = warnTextColor,
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    )
                }
            }
        }

        item {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val squareSize = (maxWidth - 12.dp) / 2

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(squareSize),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(20.dp))
                            .background(statusCardBgColor)
                            .clickable {
                                viewModel.connectAndRefresh(includeSystem = false)
                                if (isModuleActive) {
                                    onNavigateToRunning()
                                }
                            }
                    ) {
                        StatusCornerBadge(
                            isActive = isModuleActive,
                            color = cornerBadgeColor,
                            modifier = Modifier.matchParentSize()
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 18.dp, vertical = 18.dp)
                        ) {
                            Text(
                                text = if (isModuleActive) "运行中" else "未激活",
                                fontSize = 21.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = statusTitleColor
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { onNavigateToWhitelist() }
                                    .padding(horizontal = 18.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "白名单",
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${config.whiteAppSet.size}",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { onNavigateToBlacklist() }
                                    .padding(horizontal = 18.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "黑名单",
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "$blackCount",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToRunning() }
                        .padding(horizontal = 20.dp, vertical = 18.dp)
                ) {
                    Text(
                        text = "已冻结",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "当前已冻结 $frozenCount 个应用 · 共 $frozenProcessCount 个后台进程",
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }

        item {
            val milletStatusText = when {
                isSystemActive && isPowerKeeperActive -> "运行中"
                isSystemActive -> "运行中 (PowerKeeper 未连接)"
                isLspActivated -> "未生效 (等待系统重启或服务连接)"
                else -> "未知 (模块未激活)"
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    InfoBlock(
                        title = "模块版本",
                        value = "v1.0"
                    )
                    InfoBlock(
                        title = "环境",
                        value = "Millet：$milletStatusText\n$cleanLspInfo"
                    )
                    InfoBlock(
                        title = "Android 版本",
                        value = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
                    )
                    InfoBlock(
                        title = "策略概览",
                        value = "消息应用 ${config.messageAppSet.size} · 禁同步Binder ${config.forbidSyncBinderSet.size} · 前台不冻结 ${config.notFreezeWhenHasFgsSet.size}"
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.refreshMemoryStatus() }
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    MemoryUsageBar(
                        title = "当前运行内存 (RAM)",
                        usedText = String.format(
                            Locale.US,
                            "已用 %.2f GB / %.2f GB (%d%%)",
                            memoryStatus.ramUsedGb,
                            memoryStatus.ramTotalGb,
                            memoryStatus.ramPercent
                        ),
                        subText = String.format(
                            Locale.US,
                            "可用运行内存：%.2f GB",
                            memoryStatus.ramAvailGb
                        ),
                        percent = memoryStatus.ramPercent,
                        barColor = MiuixTheme.colorScheme.primary
                    )

                    MemoryUsageBar(
                        title = "ZRAM 当前占用",
                        usedText = if (memoryStatus.zramTotalGb > 0.01f) {
                            String.format(
                                Locale.US,
                                "已用 %.2f GB / %.2f GB (%d%%)",
                                memoryStatus.zramUsedGb,
                                memoryStatus.zramTotalGb,
                                memoryStatus.zramPercent
                            )
                        } else {
                            "未启用 ZRAM (0.00 GB)"
                        },
                        subText = String.format(
                            Locale.US,
                            "剩余 ZRAM 交换空间：%.2f GB",
                            memoryStatus.zramFreeGb
                        ),
                        percent = memoryStatus.zramPercent,
                        barColor = Color(0xFF169976)
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryUsageBar(
    title: String,
    usedText: String,
    subText: String,
    percent: Int,
    barColor: Color
) {
    val isDark = LocalAppDarkMode.current
    val trackColor = if (isDark) Color(0xFF2C2C2E) else Color(0xFFE8E8ED)
    val fraction = (percent / 100f).coerceIn(0f, 1f)

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = usedText,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(50))
                .background(trackColor)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(barColor)
            )
        }
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = subText,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

@Composable
private fun StatusCornerBadge(
    isActive: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val baseDim = size.minDimension
        val radius = baseDim * 0.365f
        val strokeWidth = baseDim * 0.068f
        val cx = size.width * 0.72f
        val cy = size.height * 0.73f + 2.dp.toPx()
        val center = Offset(cx, cy)

        drawCircle(
            color = color,
            radius = radius,
            center = center,
            style = Stroke(width = strokeWidth)
        )

        if (isActive) {
            val checkPath = Path().apply {
                moveTo(cx - radius * 0.38f, cy + radius * 0.02f)
                lineTo(cx - radius * 0.08f, cy + radius * 0.30f)
                lineTo(cx + radius * 0.42f, cy - radius * 0.24f)
            }
            drawPath(
                path = checkPath,
                color = color,
                style = Stroke(
                    width = strokeWidth,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        } else {
            val offset = radius * 0.28f
            drawLine(
                color = color,
                start = Offset(cx - offset, cy - offset),
                end = Offset(cx + offset, cy + offset),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = color,
                start = Offset(cx + offset, cy - offset),
                end = Offset(cx - offset, cy + offset),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun InfoBlock(title: String, value: String) {
    Column {
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

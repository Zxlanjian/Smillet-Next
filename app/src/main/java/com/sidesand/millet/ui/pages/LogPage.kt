package com.sidesand.millet.ui.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sidesand.millet.data.MilletConfigSnapshot
import com.sidesand.millet.hook.SMilletLogger
import com.sidesand.millet.ui.MainViewModel
import com.sidesand.millet.ui.theme.LocalAppDarkMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

const val MAX_DISPLAY_LOG_COUNT = 100

enum class LogFilterLevel(val label: String) {
    ALL("全部日志"),
    DEBUG("调试"),
    INFO("信息")
}

fun filterLogLines(
    logs: List<String>,
    configLogLevel: String,
    filterLevel: LogFilterLevel
): List<String> {
    val minRank = SMilletLogger.levelRank(configLogLevel)
    if (minRank >= 99) return emptyList()
    return logs.filter { line ->
        val isDebug = line.contains("[DEBUG]", ignoreCase = true) ||
            line.contains(" D/", ignoreCase = true)
        val lineRank = if (isDebug) 1 else 2
        if (lineRank < minRank) return@filter false
        when (filterLevel) {
            LogFilterLevel.ALL -> true
            LogFilterLevel.DEBUG -> lineRank == 1
            LogFilterLevel.INFO -> lineRank == 2
        }
    }
}

@Composable
fun LogPage(
    viewModel: MainViewModel,
    config: MilletConfigSnapshot,
    listState: LazyListState,
    scrollBehavior: ScrollBehavior,
    contentPadding: PaddingValues,
    filterLevel: LogFilterLevel,
    onFilterLevelChange: (LogFilterLevel) -> Unit
) {
    val logs by viewModel.logsList.collectAsState()
    val isLoading by viewModel.isLoadingLogs.collectAsState()
    val isDark = LocalAppDarkMode.current
    val cardBgColor = if (isDark) Color(0xFF242426) else Color(0xFFFFFFFF)
    val overflowBgColor = if (isDark) Color(0xFF382914) else Color(0xFFFFF4E5)
    val overflowTextColor = if (isDark) Color(0xFFFFB74D) else Color(0xFFD97706)
    val isLogDisabled = SMilletLogger.levelRank(config.logLevel) >= 99

    // 仅在打开日志页面期间实时刷新，退出日志页面立即停止读取
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopLogPolling()
        }
    }

    LaunchedEffect(isLogDisabled) {
        if (!isLogDisabled) {
            viewModel.refreshLogs(silent = false)
            while (isActive) {
                delay(1000L)
                viewModel.refreshLogs(silent = true)
            }
        }
    }

    val allMatchedLogs = remember(logs, config.logLevel, filterLevel) {
        filterLogLines(logs, config.logLevel, filterLevel)
    }
    val totalLogCount = allMatchedLogs.size
    val isLogOverflow = totalLogCount > MAX_DISPLAY_LOG_COUNT
    val displayedLogs = remember(allMatchedLogs) {
        if (allMatchedLogs.size > MAX_DISPLAY_LOG_COUNT) {
            allMatchedLogs.takeLast(MAX_DISPLAY_LOG_COUNT)
        } else {
            allMatchedLogs
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val headerCountText = if (isLogOverflow) {
                            "${filterLevel.label} (${displayedLogs.size} / 共 $totalLogCount 条)"
                        } else {
                            "${filterLevel.label} ($totalLogCount)"
                        }
                        Text(
                            text = headerCountText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 2.dp)
                        )
                        if (filterLevel != LogFilterLevel.ALL) {
                            Text(
                                text = "显示全部",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onFilterLevelChange(LogFilterLevel.ALL) }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // 超过 100 条时显示提示横幅；未超过 100 条则不显示任何提示
                    if (isLogOverflow) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(overflowBgColor)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LogOverflowInfoIcon(color = overflowTextColor)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "日志过长（共 $totalLogCount 条），仅显示最新 100 条",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = overflowTextColor
                            )
                        }
                    }
                }
            }

            if (displayedLogs.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when {
                                isLogDisabled -> "当前日志输出已设为「无」"
                                isLoading -> "正在加载当前日志..."
                                else -> "当前筛选下暂无日志记录"
                            },
                            fontSize = 15.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            } else {
                itemsIndexed(displayedLogs) { _, line ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(cardBgColor)
                    ) {
                        LogSingleCardRow(line = line)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogOverflowInfoIcon(color: Color) {
    Canvas(modifier = Modifier.size(18.dp)) {
        val stroke = 1.8.dp.toPx()
        val r = size.minDimension * 0.42f
        val center = Offset(size.width * 0.5f, size.height * 0.5f)
        drawCircle(
            color = color,
            radius = r,
            center = center,
            style = Stroke(width = stroke)
        )
        drawLine(
            color = color,
            start = Offset(center.x, size.height * 0.28f),
            end = Offset(center.x, size.height * 0.54f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawCircle(
            color = color,
            radius = stroke * 0.62f,
            center = Offset(center.x, size.height * 0.70f)
        )
    }
}

@Composable
private fun LogSingleCardRow(line: String) {
    val isDebug = line.contains("[DEBUG]", ignoreCase = true) || line.contains(" D/", ignoreCase = true)

    val (badgeText, badgeColor) = if (isDebug) {
        "调试" to Color(0xFF2874F0)
    } else {
        "信息" to Color(0xFF169976)
    }

    val timePart = line.take(23).takeIf { it.startsWith("20") } ?: ""
    val bodyText = if (line.length > 24 && line.startsWith("20")) {
        line.substring(24)
            .replaceFirst("[INFO] ", "")
            .replaceFirst("[DEBUG] ", "")
            .replaceFirst("[WARN] ", "")
            .replaceFirst("[ERROR] ", "")
            .replaceFirst("[Smillet-Next] ", "")
            .trim()
    } else {
        line.trim()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = bodyText,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Normal,
                color = MiuixTheme.colorScheme.onSurface
            )
            if (timePart.isNotEmpty()) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = timePart,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(badgeColor)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
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

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sidesand.millet.data.InstalledAppItem
import com.sidesand.millet.data.MilletConfigSnapshot
import com.sidesand.millet.ui.MainViewModel
import com.sidesand.millet.ui.components.AppIcon
import com.sidesand.millet.ui.theme.LocalAppDarkMode
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

val APP_FILTER_OPTIONS = listOf(
    "全部应用",
    "已配置策略的应用",
    "白名单",
    "黑名单 (强制冻结 / 压制)",
    "消息应用",
    "禁止同步 Binder",
    "前台服务不冻结",
    "未优化"
)

@Composable
fun AppListPage(
    viewModel: MainViewModel,
    config: MilletConfigSnapshot,
    scrollBehavior: ScrollBehavior,
    contentPadding: PaddingValues,
    includeSystemApps: Boolean,
    filterIndex: Int,
    onFilterIndexChange: (Int) -> Unit,
    onOpenAppConfig: (packageName: String, appName: String) -> Unit
) {
    val allApps by viewModel.installedApps.collectAsState()
    val isLoadingApps by viewModel.isLoadingApps.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val isDark = LocalAppDarkMode.current

    val safeFilterIndex = filterIndex.coerceIn(0, APP_FILTER_OPTIONS.lastIndex)

    val filteredApps = remember(allApps, searchQuery, includeSystemApps, safeFilterIndex, config) {
        allApps.filter { app ->
            val pkg = app.packageName
            val isUnoptimized = config.batteryOptWhiteSet.contains(pkg) ||
                (app.isBatteryOptIgnored && !config.batteryOptBlackSet.contains(pkg))

            val isExplicitlyInTargetList = when (safeFilterIndex) {
                2 -> config.whiteAppSet.contains(pkg)
                3 -> config.forceFreezeSet.contains(pkg) || config.blockThawSet.contains(pkg)
                7 -> isUnoptimized
                else -> false
            }
            if (!includeSystemApps && app.isSystemApp && !isExplicitlyInTargetList) {
                return@filter false
            }
            if (searchQuery.isNotBlank() &&
                !app.appName.contains(searchQuery, ignoreCase = true) &&
                !app.packageName.contains(searchQuery, ignoreCase = true)
            ) {
                return@filter false
            }
            when (safeFilterIndex) {
                1 -> config.whiteAppSet.contains(pkg) ||
                    config.messageAppSet.contains(pkg) ||
                    config.forceFreezeSet.contains(pkg) ||
                    config.blockThawSet.contains(pkg) ||
                    config.forbidSyncBinderSet.contains(pkg) ||
                    config.notFreezeWhenHasFgsSet.contains(pkg) ||
                    config.batteryOptWhiteSet.contains(pkg)
                2 -> config.whiteAppSet.contains(pkg)
                3 -> config.forceFreezeSet.contains(pkg) || config.blockThawSet.contains(pkg)
                4 -> config.messageAppSet.contains(pkg)
                5 -> config.forbidSyncBinderSet.contains(pkg)
                6 -> config.notFreezeWhenHasFgsSet.contains(pkg)
                7 -> isUnoptimized
                else -> true
            }
        }.sortedWith(
            compareBy<InstalledAppItem> { app ->
                val pkg = app.packageName
                when {
                    config.whiteAppSet.contains(pkg) -> 0
                    config.forceFreezeSet.contains(pkg) || config.blockThawSet.contains(pkg) -> 1
                    else -> 2
                }
            }.thenBy { it.appName.lowercase() }
        )
    }

    val cardBgColor = if (isDark) Color(0xFF242426) else Color(0xFFFFFFFF)

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                MiuixCapsuleSearchBar(
                    query = searchQuery,
                    onQueryChange = { newText ->
                        if (newText.contains('\n') || newText.contains('\r')) {
                            searchQuery = newText.replace("\n", "").replace("\r", "")
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        } else {
                            searchQuery = newText
                        }
                    },
                    onSearchAction = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val sectionTitle = if (safeFilterIndex == 0) {
                        "应用信息 (${filteredApps.size})"
                    } else {
                        "${APP_FILTER_OPTIONS[safeFilterIndex]} (${filteredApps.size})"
                    }
                    Text(
                        text = sectionTitle,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 2.dp)
                    )
                    if (safeFilterIndex != 0) {
                        Text(
                            text = "显示全部",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onFilterIndexChange(0) }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            if (filteredApps.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isLoadingApps) {
                                "正在加载应用列表..."
                            } else {
                                "当前筛选下暂无应用"
                            },
                            fontSize = 15.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            } else {
                items(filteredApps, key = { it.packageName }) { app ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(cardBgColor)
                            .clickable { onOpenAppConfig(app.packageName, app.appName) }
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        InstalledAppRow(app = app, config = config)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiuixCapsuleSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = LocalAppDarkMode.current
    val pillBg = if (isDark) Color(0xFF242426) else Color(0xFFEEEEF2)
    val hintColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val textColor = MiuixTheme.colorScheme.onSurface

    Row(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(pillBg)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SearchMagnifierIcon(color = hintColor)
        Spacer(modifier = Modifier.width(10.dp))
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart
        ) {
            if (query.isEmpty()) {
                Text(
                    text = "搜索应用名称或包名",
                    fontSize = 16.sp,
                    color = hintColor
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = textColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal
                ),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { onSearchAction() },
                    onDone = { onSearchAction() }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter) {
                            if (keyEvent.type == KeyEventType.KeyUp) {
                                onSearchAction()
                            }
                            true
                        } else {
                            false
                        }
                    }
            )
        }
        if (query.isNotEmpty()) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(hintColor.copy(alpha = 0.25f))
                    .clickable { onQueryChange("") },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(10.dp)) {
                    val stroke = 1.6.dp.toPx()
                    drawLine(
                        color = textColor.copy(alpha = 0.75f),
                        start = Offset(size.width * 0.2f, size.height * 0.2f),
                        end = Offset(size.width * 0.8f, size.height * 0.8f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = textColor.copy(alpha = 0.75f),
                        start = Offset(size.width * 0.8f, size.height * 0.2f),
                        end = Offset(size.width * 0.2f, size.height * 0.8f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchMagnifierIcon(color: Color) {
    Canvas(modifier = Modifier.size(18.dp)) {
        val stroke = 1.85.dp.toPx()
        val r = size.minDimension * 0.34f
        val cx = size.width * 0.43f
        val cy = size.height * 0.43f
        drawCircle(
            color = color,
            radius = r,
            center = Offset(cx, cy),
            style = Stroke(width = stroke)
        )
        drawLine(
            color = color,
            start = Offset(cx + r * 0.72f, cy + r * 0.72f),
            end = Offset(size.width * 0.86f, size.height * 0.86f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun InstalledAppRow(
    app: InstalledAppItem,
    config: MilletConfigSnapshot
) {
    val pkg = app.packageName
    val badges = buildList {
        when {
            config.whiteAppSet.contains(pkg) -> add("白名单" to Color(0xFF169976))
            config.forceFreezeSet.contains(pkg) || config.blockThawSet.contains(pkg) -> add("黑名单" to Color(0xFFD94A4A))
        }
        if (config.messageAppSet.contains(pkg)) add("消息" to Color(0xFF2874F0))
        if (config.forbidSyncBinderSet.contains(pkg)) add("禁Binder" to Color(0xFF6B5AED))
        if (config.notFreezeWhenHasFgsSet.contains(pkg)) add("前台不冻" to Color(0xFF00897B))
        val isBatteryOptWhite = config.batteryOptWhiteSet.contains(pkg) ||
            (app.isBatteryOptIgnored && !config.batteryOptBlackSet.contains(pkg))
        if (isBatteryOptWhite) {
            add("未优化" to Color(0xFFE67E22))
        }
    }.take(3)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(
            drawable = app.icon,
            appName = app.appName,
            size = 46.dp
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.appName,
                fontSize = 17.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = app.packageName,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (badges.isNotEmpty()) {
            Spacer(modifier = Modifier.width(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                badges.forEach { (label, bgColor) ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(bgColor)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

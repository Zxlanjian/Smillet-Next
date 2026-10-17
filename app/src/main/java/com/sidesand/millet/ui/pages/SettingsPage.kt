package com.sidesand.millet.ui.pages

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sidesand.millet.data.ConfigKeys
import com.sidesand.millet.data.MilletConfigSnapshot
import com.sidesand.millet.ui.MainViewModel
import com.sidesand.millet.ui.theme.LocalAppDarkMode
import com.sidesand.millet.ui.theme.ThemeConfig
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsPage(
    viewModel: MainViewModel,
    config: MilletConfigSnapshot,
    scrollBehavior: ScrollBehavior,
    contentPadding: PaddingValues,
    onNavigateToLogs: () -> Unit,
    onNavigateToAppearance: () -> Unit,
    onNavigateToAbout: () -> Unit
) {
    var showBackupDialog by remember { mutableStateOf(false) }
    var showPidWhyDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val isDark = LocalAppDarkMode.current

    val exportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            runCatching {
                val json = viewModel.prefsManager.exportBackupJson()
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "备份文件已成功导出", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(context, "导出失败：${err.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                    it.readText()
                }.orEmpty()
                if (viewModel.restoreFromBackupJson(text)) {
                    Toast.makeText(context, "已从文件恢复全部配置并同步生效", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "文件格式无效或 JSON 解析失败", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { err ->
                Toast.makeText(context, "读取备份文件失败：${err.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val logLevelValues = listOf("debug", "info", "none")
    val logLevelLabels = listOf("调试", "信息", "无")
    val selectedLogLevelIdx = logLevelValues.indexOf(config.logLevel.lowercase(Locale.ROOT)).let {
        if (it >= 0) it else 1
    }
    val logLevelSummary = when (logLevelValues[selectedLogLevelIdx]) {
        "debug" -> "调试：输出完整调试日志、UID/PID 冻结明细与 Doze 状态流转"
        "info" -> "信息：输出常规冻结解冻状态与待机省电记录"
        else -> "无：关闭运行日志输出"
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SettingsSectionHeader(text = "冻结设置")
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    OverlayDropdownPreference(
                        title = "冻结模式",
                        items = listOf("UID", "PID"),
                        selectedIndex = 0,
                        onSelectedIndexChange = { idx ->
                            if (idx == 1) {
                                showPidWhyDialog = true
                            }
                        }
                    )
                    OverlayDropdownPreference(
                        title = "电池优化模式",
                        items = listOf("按 App 配置", "系统默认"),
                        selectedIndex = if (config.blockAddPowerSaveWhitelistApps) 0 else 1,
                        onSelectedIndexChange = { idx ->
                            viewModel.updateBoolean(ConfigKeys.BLOCK_ADD_POWERSAVE_WHITELIST, idx == 0)
                        }
                    )
                    SwitchPreference(
                        title = "屏蔽 Millet 杀后台",
                        checked = config.disableKill,
                        onCheckedChange = { viewModel.updateBoolean(ConfigKeys.DISABLE_KILL, it) }
                    )
                    SwitchPreference(
                        title = "屏蔽充电解冻",
                        checked = config.disableCharge,
                        onCheckedChange = { viewModel.updateBoolean(ConfigKeys.DISABLE_CHARGE, it) }
                    )
                    SwitchPreference(
                        title = "屏蔽音量解冻",
                        checked = config.disableVolume,
                        onCheckedChange = { viewModel.updateBoolean(ConfigKeys.DISABLE_VOLUME, it) }
                    )
                    SwitchPreference(
                        title = "记录电量变化",
                        checked = config.batteryChangeObserve,
                        onCheckedChange = {
                            viewModel.updateBoolean(ConfigKeys.BATTERY_CHANGE_OBSERVE, it)
                        }
                    )
                }
            }
        }

        item {
            SettingsSectionHeader(text = "深度 Doze")
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SwitchPreference(
                        title = "息屏进入 DeepDoze",
                        checked = config.enableDoze,
                        onCheckedChange = { viewModel.updateBoolean(ConfigKeys.ENABLE_DOZE, it) }
                    )
                    SwitchPreference(
                        title = "跳过状态检查进入 DeepDoze",
                        checked = config.skipStateCheck,
                        enabled = config.enableDoze,
                        onCheckedChange = { viewModel.updateBoolean(ConfigKeys.SKIP_STATE_CHECK, it) }
                    )
                    SwitchPreference(
                        title = "避免运动退出 DeepDoze",
                        checked = config.disableMotionListener,
                        onCheckedChange = {
                            viewModel.updateBoolean(ConfigKeys.DISABLE_MOTION_LISTENER, it)
                        }
                    )
                }
            }
        }

        item {
            SettingsSectionHeader(text = "日志与备份")
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ArrowPreference(
                        title = "日志",
                        summary = "查看实时运行日志、按级别筛选或导出日志",
                        onClick = onNavigateToLogs
                    )
                    OverlayDropdownPreference(
                        title = "日志级别",
                        summary = logLevelSummary,
                        items = logLevelLabels,
                        selectedIndex = selectedLogLevelIdx,
                        onSelectedIndexChange = { idx ->
                            viewModel.updateString(ConfigKeys.LOG_LEVEL, logLevelValues[idx])
                        }
                    )
                    ArrowPreference(
                        title = "备份与恢复",
                        summary = "备份配置到 JSON 文件或从文件恢复",
                        onClick = { showBackupDialog = true }
                    )
                }
            }
        }

        item {
            SettingsSectionHeader(text = "其他")
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ArrowPreference(
                        title = "外观与主题",
                        summary = "颜色、模糊效果与界面缩放，导航与手势选项",
                        onClick = onNavigateToAppearance
                    )
                    ArrowPreference(
                        title = "关于",
                        summary = "Smillet-Next v1.0.0",
                        onClick = onNavigateToAbout
                    )
                }
            }
        }
    }

    if (showPidWhyDialog) {
        val blockBg = if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f)
        OverlayDialog(
            show = true,
            title = "仅支持 UID 冻结模式",
            summary = "HyperOS 墓碑与内核 Freezer 调度机制说明",
            onDismissRequest = { showPidWhyDialog = false }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PidReasonSubCard(
                    title = "底层粒度限制",
                    body = "HyperOS 原生墓碑服务 (GreezeManagerService) 与内核 Cgroup v2 Freezer 均按应用 UID 进行统一冻结与解冻调度。",
                    bgColor = blockBg
                )
                PidReasonSubCard(
                    title = "防止进程死锁与 ANR",
                    body = "同一应用的主进程与推送、WebView 渲染等子进程共享文件锁与高频同步 Binder 调用。若单独冻结单个 PID，会导致主进程等待响应超时并引发系统无响应 (ANR)。",
                    bgColor = blockBg
                )
                PidReasonSubCard(
                    title = "最佳稳定性策略",
                    body = "固定采用 UID 模式可原子化冻结应用名下全部子进程，兼顾零后台耗电与系统稳定性。",
                    bgColor = blockBg
                )
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    text = "我知道了",
                    onClick = { showPidWhyDialog = false },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }

    if (showBackupDialog) {
        OverlayDialog(
            show = true,
            title = "本地备份",
            onDismissRequest = { showBackupDialog = false }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                BackupActionRow(
                    title = "备份到文件",
                    icon = { IconBackupLayers(color = MiuixTheme.colorScheme.onSurface) },
                    onClick = {
                        showBackupDialog = false
                        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        exportFileLauncher.launch("Smillet-Next_backup_$ts.json")
                    }
                )

                Spacer(modifier = Modifier.height(6.dp))

                BackupActionRow(
                    title = "从文件恢复",
                    icon = { IconRestoreFromFile(color = MiuixTheme.colorScheme.onSurface) },
                    onClick = {
                        showBackupDialog = false
                        importFileLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                TextButton(
                    text = "取消",
                    onClick = { showBackupDialog = false },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun PidReasonSubCard(
    title: String,
    body: String,
    bgColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = body,
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
            color = MiuixTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun AppearancePage(
    viewModel: MainViewModel,
    config: MilletConfigSnapshot,
    scrollBehavior: ScrollBehavior,
    contentPadding: PaddingValues
) {
    val colorModes = ThemeConfig.COLOR_MODE_LABELS
    val blurModes = ThemeConfig.TOP_BAR_BLUR_LABELS
    val blurSummaries = ThemeConfig.TOP_BAR_BLUR_SUMMARIES
    val floatingStyles = ThemeConfig.FLOATING_BOTTOM_BAR_STYLE_LABELS
    val bottomBarModes = ThemeConfig.BOTTOM_BAR_MODE_LABELS

    val selectedColorModeIdx = config.themeColorMode.coerceIn(0, colorModes.lastIndex)
    val selectedBlurModeIdx = config.topBarBlurMode.coerceIn(0, blurModes.lastIndex)
    val selectedFloatingStyleIdx = config.floatingBottomBarStyle.coerceIn(0, floatingStyles.lastIndex)
    val selectedBottomModeIdx = config.bottomBarMode.coerceIn(0, bottomBarModes.lastIndex)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SettingsSectionHeader(text = "颜色与主题")
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    OverlayDropdownPreference(
                        title = "主题模式",
                        summary = colorModes[selectedColorModeIdx],
                        items = colorModes,
                        selectedIndex = selectedColorModeIdx,
                        onSelectedIndexChange = { idx ->
                            viewModel.updateInt(ConfigKeys.THEME_COLOR_MODE, idx)
                        }
                    )
                    SwitchPreference(
                        title = "Monet 取色",
                        summary = "可用时使用系统动态取色",
                        checked = config.monetEnabled,
                        onCheckedChange = {
                            viewModel.updateBoolean(ConfigKeys.MONET_ENABLED, it)
                        }
                    )
                }
            }
        }

        item {
            SettingsSectionHeader(text = "界面与效果")
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SwitchPreference(
                        title = "模糊效果",
                        summary = "启用顶栏、底栏等表面的模糊效果",
                        checked = config.blurEnabled,
                        onCheckedChange = {
                            viewModel.updateBoolean(ConfigKeys.BLUR_ENABLED, it)
                        }
                    )
                    AnimatedVisibility(
                        visible = config.blurEnabled,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        OverlayDropdownPreference(
                            title = "顶栏模糊样式",
                            summary = blurSummaries[selectedBlurModeIdx],
                            items = blurModes,
                            selectedIndex = selectedBlurModeIdx,
                            onSelectedIndexChange = { idx ->
                                viewModel.updateInt(ConfigKeys.TOP_BAR_BLUR_MODE, idx)
                            }
                        )
                    }
                    SwitchPreference(
                        title = "预测性返回手势",
                        summary = "启用预测性返回动画效果",
                        checked = config.predictiveBackEnabled,
                        onCheckedChange = {
                            viewModel.updateBoolean(ConfigKeys.PREDICTIVE_BACK_ENABLED, it)
                        }
                    )
                    UiScalePreferenceItem(
                        currentScale = config.uiScale,
                        onScaleChange = { newScale ->
                            viewModel.updateInt(ConfigKeys.UI_SCALE, newScale)
                        }
                    )
                }
            }
        }

        item {
            SettingsSectionHeader(text = "导航")
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SwitchPreference(
                        title = "横移返回手势",
                        summary = "在页面任意位置横移即可返回上一页",
                        checked = config.swipeBackEnabled,
                        onCheckedChange = {
                            viewModel.updateBoolean(ConfigKeys.SWIPE_BACK_ENABLED, it)
                        }
                    )
                    SwitchPreference(
                        title = "悬浮底栏",
                        summary = "在手机布局中使用悬浮底部导航栏",
                        checked = config.floatingBottomBar,
                        onCheckedChange = {
                            viewModel.updateBoolean(ConfigKeys.FLOATING_BOTTOM_BAR, it)
                        }
                    )
                    AnimatedVisibility(
                        visible = config.floatingBottomBar,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        OverlayDropdownPreference(
                            title = "悬浮底栏样式",
                            summary = floatingStyles[selectedFloatingStyleIdx],
                            items = floatingStyles,
                            selectedIndex = selectedFloatingStyleIdx,
                            onSelectedIndexChange = { idx ->
                                viewModel.updateInt(ConfigKeys.FLOATING_BOTTOM_BAR_STYLE, idx)
                            }
                        )
                    }
                    OverlayDropdownPreference(
                        title = "底栏选项",
                        summary = bottomBarModes[selectedBottomModeIdx],
                        items = bottomBarModes,
                        selectedIndex = selectedBottomModeIdx,
                        onSelectedIndexChange = { idx ->
                            viewModel.updateInt(ConfigKeys.BOTTOM_BAR_MODE, idx)
                        }
                    )
                    SwitchPreference(
                        title = "隐藏最近任务卡片",
                        summary = "在系统最近任务切换视图中隐藏本应用卡片",
                        checked = config.hideTaskCard,
                        onCheckedChange = {
                            viewModel.updateBoolean(ConfigKeys.HIDE_TASK_CARD, it)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun UiScalePreferenceItem(
    currentScale: Int,
    onScaleChange: (Int) -> Unit
) {
    val isDark = LocalAppDarkMode.current
    val steps = ThemeConfig.UI_SCALE_STEPS
    val currentIdx = steps.indexOf(currentScale).let { if (it >= 0) it else 2 }
    val animatedFraction by animateFloatAsState(
        targetValue = currentIdx.toFloat() / (steps.size - 1).toFloat(),
        animationSpec = tween(durationMillis = 180),
        label = "UiScaleFraction"
    )
    val primaryColor = MiuixTheme.colorScheme.primary
    val trackBgColor = if (isDark) Color(0xFF2C2C2E) else Color(0xFFF0F0F3)
    val inactiveDotColor = if (isDark) Color.White.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.14f)
    val activeDotColor = Color.White.copy(alpha = 0.45f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onScaleChange(100)
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "界面缩放",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "调整全局显示比例 (80% - 110%)",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
            Text(
                text = "${steps[currentIdx]}%",
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(modifier = Modifier.width(6.dp))
            ChevronRightIcon(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 滑轨高度 28.5.dp、白圆半径 10.25.dp (直径 20.5.dp)，与 Miuix Switch 开关的白圆尺寸 1:1 完全一致
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.5.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val r = size.height / 2f
                        val usableW = (w - 2 * r).coerceAtLeast(1f)
                        val rel = ((offset.x - r) / usableW).coerceIn(0f, 1f)
                        val idx = (rel * (steps.size - 1)).roundToInt().coerceIn(0, steps.lastIndex)
                        onScaleChange(steps[idx])
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val r = size.height / 2f
                        val usableW = (w - 2 * r).coerceAtLeast(1f)
                        val rel = ((change.position.x - r) / usableW).coerceIn(0f, 1f)
                        val idx = (rel * (steps.size - 1)).roundToInt().coerceIn(0, steps.lastIndex)
                        if (steps[idx] != currentScale) {
                            onScaleChange(steps[idx])
                        }
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            val radius = h / 2f
            val thumbRadius = 10.25.dp.toPx()
            val usableW = (w - 2f * radius).coerceAtLeast(1f)
            val thumbCenterX = radius + usableW * animatedFraction

            drawRoundRect(
                color = trackBgColor,
                topLeft = Offset.Zero,
                size = Size(w, h),
                cornerRadius = CornerRadius(radius, radius)
            )

            val activeWidth = (thumbCenterX + radius).coerceIn(h, w)
            drawRoundRect(
                color = primaryColor,
                topLeft = Offset.Zero,
                size = Size(activeWidth, h),
                cornerRadius = CornerRadius(radius, radius)
            )

            for (i in steps.indices) {
                val frac = i.toFloat() / (steps.size - 1).toFloat()
                val dotX = radius + usableW * frac
                if (kotlin.math.abs(dotX - thumbCenterX) > thumbRadius * 0.65f) {
                    drawCircle(
                        color = if (dotX < thumbCenterX) activeDotColor else inactiveDotColor,
                        radius = 2.8.dp.toPx(),
                        center = Offset(dotX, h / 2f)
                    )
                }
            }

            drawCircle(
                color = Color.White,
                radius = thumbRadius,
                center = Offset(thumbCenterX, h / 2f)
            )
        }
    }
}

@Composable
fun AboutPage(
    scrollBehavior: ScrollBehavior,
    contentPadding: PaddingValues
) {
    val context = LocalContext.current
    val isDark = LocalAppDarkMode.current

    fun openExternalUrl(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            Toast.makeText(context, "无法打开链接：$url", Toast.LENGTH_SHORT).show()
        }
    }

    val titleColor = if (isDark) Color(0xFFF0D0E6) else Color(0xFF6B2058)
    val versionColor = if (isDark) Color(0xFFB5A4B3) else Color(0xFF6E5D6B)
    val sectionTitleColor = if (isDark) Color(0xFFA89EB5) else Color(0xFF8C8299)
    val cardBgColor = if (isDark) {
        Color(0xFF201E29).copy(alpha = 0.72f)
    } else {
        Color.White.copy(alpha = 0.68f)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AboutOs3FullBackground(isDark = isDark)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 84.dp, bottom = 86.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AboutMishkaStyleEmblem(isDark = isDark)

                    Spacer(modifier = Modifier.height(26.dp))

                    Text(
                        text = "Smillet-Next",
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Normal,
                        color = titleColor
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "v1.0.0 (1)",
                        fontSize = 15.sp,
                        color = versionColor
                    )
                }
            }

            item {
                Text(
                    text = "开放源代码许可",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = sectionTitleColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, bottom = 2.dp)
                )
            }

            item {
                AboutOpenSourceRow(
                    title = "Smillet-Next",
                    urlSummary = "github.com/Zxlanjian/Smillet-Next",
                    cardBgColor = cardBgColor,
                    onClick = { openExternalUrl("https://github.com/Zxlanjian/Smillet-Next") }
                )
            }
            item {
                AboutOpenSourceRow(
                    title = "miuix",
                    urlSummary = "github.com/compose-miuix-ui/miuix",
                    cardBgColor = cardBgColor,
                    onClick = { openExternalUrl("https://github.com/compose-miuix-ui/miuix") }
                )
            }
            item {
                AboutOpenSourceRow(
                    title = "libxposed",
                    urlSummary = "github.com/libxposed/api",
                    cardBgColor = cardBgColor,
                    onClick = { openExternalUrl("https://github.com/libxposed/api") }
                )
            }
        }
    }
}

@Composable
private fun AboutOs3FullBackground(isDark: Boolean) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (isDark) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF2B1726),
                        Color(0xFF211832),
                        Color(0xFF171726),
                        Color(0xFF12121A)
                    )
                )
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF542244).copy(alpha = 0.55f),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.25f, h * 0.22f),
                    radius = w * 0.85f
                ),
                radius = w * 0.85f,
                center = Offset(w * 0.25f, h * 0.22f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF2D2556).copy(alpha = 0.50f),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.20f, h * 0.62f),
                    radius = w * 0.90f
                ),
                radius = w * 0.90f,
                center = Offset(w * 0.20f, h * 0.62f)
            )
        } else {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFF9DFEC),
                        Color(0xFFF3D4E8),
                        Color(0xFFDCD0F8),
                        Color(0xFFE6E6FB)
                    )
                )
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFBD8E9),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.25f, h * 0.18f),
                    radius = w * 0.95f
                ),
                radius = w * 0.95f,
                center = Offset(w * 0.25f, h * 0.18f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFCCC9FA).copy(alpha = 0.85f),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.15f, h * 0.58f),
                    radius = w * 0.95f
                ),
                radius = w * 0.95f,
                center = Offset(w * 0.15f, h * 0.58f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFDF2F8).copy(alpha = 0.80f),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.92f, h * 0.45f),
                    radius = w * 0.80f
                ),
                radius = w * 0.80f,
                center = Offset(w * 0.92f, h * 0.45f)
            )
        }
    }
}

@Composable
private fun AboutMishkaStyleEmblem(isDark: Boolean) {
    val brush = remember(isDark) {
        Brush.verticalGradient(
            colors = if (isDark) {
                listOf(Color(0xFFD664B4), Color(0xFF9B68E8))
            } else {
                listOf(Color(0xFF942973), Color(0xFF63267E))
            }
        )
    }

    Canvas(modifier = Modifier.size(104.dp)) {
        val w = size.width
        val h = size.height
        val strokeWidth = 10.5.dp.toPx()
        val nodeRadius = 11.5.dp.toPx()

        val pTopLeft = Offset(w * 0.16f, h * 0.18f)
        val pBotLeft = Offset(w * 0.16f, h * 0.82f)
        val pMid = Offset(w * 0.50f, h * 0.52f)
        val pTopRight = Offset(w * 0.84f, h * 0.18f)
        val pBotRight = Offset(w * 0.84f, h * 0.82f)

        val path = Path().apply {
            moveTo(pBotLeft.x, pBotLeft.y)
            lineTo(pTopLeft.x, pTopLeft.y)
            lineTo(pMid.x, pMid.y)
            lineTo(pTopRight.x, pTopRight.y)
            lineTo(pBotRight.x, pBotRight.y)
        }

        drawPath(
            path = path,
            brush = brush,
            style = Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        for (pt in listOf(pTopLeft, pBotLeft, pMid, pTopRight, pBotRight)) {
            drawCircle(
                brush = brush,
                radius = nodeRadius,
                center = pt
            )
        }
    }
}

@Composable
private fun AboutOpenSourceRow(
    title: String,
    urlSummary: String,
    cardBgColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(cardBgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = urlSummary,
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        ChevronRightIcon(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun BackupActionRow(
    title: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(modifier = Modifier.width(18.dp))
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        ChevronRightIcon(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun IconBackupLayers(color: Color) {
    Canvas(modifier = Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(
            width = 1.95.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
        val topLayer = Path().apply {
            moveTo(w * 0.50f, h * 0.14f)
            lineTo(w * 0.12f, h * 0.34f)
            lineTo(w * 0.50f, h * 0.54f)
            lineTo(w * 0.88f, h * 0.34f)
            close()
        }
        val midLayer = Path().apply {
            moveTo(w * 0.12f, h * 0.52f)
            lineTo(w * 0.50f, h * 0.72f)
            lineTo(w * 0.88f, h * 0.52f)
        }
        val botLayer = Path().apply {
            moveTo(w * 0.12f, h * 0.69f)
            lineTo(w * 0.50f, h * 0.89f)
            lineTo(w * 0.88f, h * 0.69f)
        }
        drawPath(topLayer, color = color, style = stroke)
        drawPath(midLayer, color = color, style = stroke)
        drawPath(botLayer, color = color, style = stroke)
    }
}

@Composable
private fun IconRestoreFromFile(color: Color) {
    Canvas(modifier = Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(
            width = 1.95.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
        val boxPath = Path().apply {
            moveTo(w * 0.36f, h * 0.28f)
            lineTo(w * 0.36f, h * 0.24f)
            cubicTo(w * 0.36f, h * 0.16f, w * 0.42f, h * 0.12f, w * 0.50f, h * 0.12f)
            lineTo(w * 0.74f, h * 0.12f)
            cubicTo(w * 0.82f, h * 0.12f, w * 0.88f, h * 0.18f, w * 0.88f, h * 0.26f)
            lineTo(w * 0.88f, h * 0.74f)
            cubicTo(w * 0.88f, h * 0.82f, w * 0.82f, h * 0.88f, w * 0.74f, h * 0.88f)
            lineTo(w * 0.50f, h * 0.88f)
            cubicTo(w * 0.42f, h * 0.88f, w * 0.36f, h * 0.84f, w * 0.36f, h * 0.76f)
            lineTo(w * 0.36f, h * 0.72f)
        }
        val arrowPath = Path().apply {
            moveTo(w * 0.12f, h * 0.50f)
            lineTo(w * 0.64f, h * 0.50f)
            moveTo(w * 0.50f, h * 0.35f)
            lineTo(w * 0.65f, h * 0.50f)
            lineTo(w * 0.50f, h * 0.65f)
        }
        drawPath(boxPath, color = color, style = stroke)
        drawPath(arrowPath, color = color, style = stroke)
    }
}

@Composable
private fun ChevronRightIcon(color: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.35f, h * 0.20f)
            lineTo(w * 0.68f, h * 0.50f)
            lineTo(w * 0.35f, h * 0.80f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = 1.9.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

@Composable
private fun SettingsSectionHeader(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 6.dp, bottom = 2.dp)
    )
}

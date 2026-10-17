package com.sidesand.millet.ui

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sidesand.millet.ui.components.MiuixPopupActionRow
import com.sidesand.millet.ui.components.MiuixPopupOptionRow
import com.sidesand.millet.ui.components.MiuixTopPopupMenu
import com.sidesand.millet.ui.pages.APP_FILTER_OPTIONS
import com.sidesand.millet.ui.pages.AboutPage
import com.sidesand.millet.ui.pages.AppConfigDialog
import com.sidesand.millet.ui.pages.AppListPage
import com.sidesand.millet.ui.pages.AppearancePage
import com.sidesand.millet.ui.pages.HomePage
import com.sidesand.millet.ui.pages.LogFilterLevel
import com.sidesand.millet.ui.pages.LogPage
import com.sidesand.millet.ui.pages.MAX_DISPLAY_LOG_COUNT
import com.sidesand.millet.ui.pages.RunningListPage
import com.sidesand.millet.ui.pages.SettingsPage
import com.sidesand.millet.ui.pages.filterLogLines
import com.sidesand.millet.ui.theme.LocalAppBlurEnabled
import com.sidesand.millet.ui.theme.LocalAppDarkMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 顶栏高效双通道高斯毛玻璃着色器（大幅降低 GPU 采样开销，确保页面左右滑动 120fps 丝滑无卡顿）：
 */
private const val TOP_BAR_BLUR_H_AGSL = """
    uniform shader content;
    uniform float topBarBottom;
    uniform float stepSize;
    uniform float progressive;

    half4 main(float2 fragCoord) {
        float effTopBottom = progressive > 0.5 ? (topBarBottom * 1.25) : topBarBottom;
        if (topBarBottom <= 1.0 || fragCoord.y >= effTopBottom) {
            return content.eval(fragCoord);
        }

        float factor = 1.0;
        if (progressive > 0.5) {
            float t = clamp(1.0 - (fragCoord.y / max(1.0, effTopBottom)), 0.0, 1.0);
            factor = t * t;
        }

        float effStep = stepSize * factor;
        if (effStep < 0.2) {
            return content.eval(fragCoord);
        }

        half4 accum = half4(0.0);
        float weightSum = 0.0;
        for (int i = -6; i <= 6; i++) {
            float fi = float(i);
            float w = exp(-(fi * fi) / 14.0);
            float2 samplePos = float2(max(1.0, fragCoord.x + fi * effStep), fragCoord.y);
            accum += content.eval(samplePos) * w;
            weightSum += w;
        }
        return accum / weightSum;
    }
"""

private const val TOP_BAR_BLUR_V_TINT_AGSL = """
    uniform shader content;
    uniform float topBarBottom;
    uniform float stepSize;
    uniform float progressive;
    uniform float4 surfaceTint;

    half4 main(float2 fragCoord) {
        float effTopBottom = progressive > 0.5 ? (topBarBottom * 1.25) : topBarBottom;
        if (topBarBottom <= 1.0 || fragCoord.y >= effTopBottom) {
            return content.eval(fragCoord);
        }

        float factor = 1.0;
        if (progressive > 0.5) {
            float t = clamp(1.0 - (fragCoord.y / max(1.0, effTopBottom)), 0.0, 1.0);
            factor = t * t;
        }

        float effStep = stepSize * factor;
        half4 blurred;
        if (effStep < 0.2) {
            blurred = content.eval(fragCoord);
        } else {
            float maxY = max(1.0, effTopBottom - 1.0);
            half4 accum = half4(0.0);
            float weightSum = 0.0;
            for (int i = -6; i <= 6; i++) {
                float fi = float(i);
                float w = exp(-(fi * fi) / 14.0);
                float sy = clamp(fragCoord.y + fi * effStep, 1.0, maxY);
                accum += content.eval(float2(fragCoord.x, sy)) * w;
                weightSum += w;
            }
            blurred = accum / weightSum;
        }

        if (surfaceTint.a > 0.0) {
            float alpha = progressive > 0.5 ? (surfaceTint.a * 0.72 * factor) : surfaceTint.a;
            return mix(blurred, half4(surfaceTint.rgb, 1.0), alpha);
        }
        return blurred;
    }
"""

/**
 * 底部悬浮胶囊专用着色器：
 * - 图一 Miuix 样式 (isLiquidGlass == 0.0)：强雾化磨砂白底（大步长柔化背景文字为淡柔光影 + 高浓度白底混合）；
 * - 图二 iOS 液态玻璃样式 (isLiquidGlass == 1.0)：通透液态玻璃（中等高斯模糊保留底层文字柔和轮廓 + 胶囊边缘透镜折射 + 极高透光率）。
 */
private const val BOTTOM_CAPSULE_BLUR_H_AGSL = """
    uniform shader content;
    uniform float bLeft;
    uniform float bTop;
    uniform float bRight;
    uniform float bBottom;
    uniform float stepSize;

    half4 main(float2 fragCoord) {
        if (bRight <= bLeft + 4.0 || bBottom <= bTop + 4.0) {
            return content.eval(fragCoord);
        }
        if (fragCoord.x < bLeft || fragCoord.x > bRight || fragCoord.y < bTop || fragCoord.y > bBottom) {
            return content.eval(fragCoord);
        }

        float r = (bBottom - bTop) * 0.5;
        float cy = (bTop + bBottom) * 0.5;
        float minCx = bLeft + r;
        float maxCx = max(minCx, bRight - r);
        float cx = clamp(fragCoord.x, minCx, maxCx);
        float dx = fragCoord.x - cx;
        float dy = fragCoord.y - cy;
        float dist = sqrt(dx * dx + dy * dy) - r;
        if (dist > 0.0) {
            return content.eval(fragCoord);
        }

        half4 accum = half4(0.0);
        float weightSum = 0.0;
        for (int i = -6; i <= 6; i++) {
            float fi = float(i);
            float w = exp(-(fi * fi) / 14.0);
            float sx = clamp(fragCoord.x + fi * stepSize, bLeft + 1.0, bRight - 1.0);
            accum += content.eval(float2(sx, fragCoord.y)) * w;
            weightSum += w;
        }
        return accum / weightSum;
    }
"""

private const val BOTTOM_CAPSULE_BLUR_V_AGSL = """
    uniform shader content;
    uniform float bLeft;
    uniform float bTop;
    uniform float bRight;
    uniform float bBottom;
    uniform float stepSize;
    uniform float isLiquidGlass;
    uniform float4 surfaceTint;

    half4 main(float2 fragCoord) {
        if (bRight <= bLeft + 4.0 || bBottom <= bTop + 4.0) {
            return content.eval(fragCoord);
        }
        if (fragCoord.x < bLeft || fragCoord.x > bRight || fragCoord.y < bTop || fragCoord.y > bBottom) {
            return content.eval(fragCoord);
        }

        float r = (bBottom - bTop) * 0.5;
        float cy = (bTop + bBottom) * 0.5;
        float minCx = bLeft + r;
        float maxCx = max(minCx, bRight - r);
        float cx = clamp(fragCoord.x, minCx, maxCx);
        float dx = fragCoord.x - cx;
        float dy = fragCoord.y - cy;
        float dist = sqrt(dx * dx + dy * dy) - r;
        if (dist > 0.0) {
            return content.eval(fragCoord);
        }

        float2 sampleCenter = fragCoord;
        if (isLiquidGlass > 0.5 && dist > -r * 0.42) {
            float edgeT = clamp((dist + r * 0.42) / max(1.0, r * 0.42), 0.0, 1.0);
            float curve = edgeT * edgeT;
            float len = sqrt(dx * dx + dy * dy);
            if (len > 0.001) {
                float2 normal = float2(dx / len, dy / len);
                sampleCenter = fragCoord - normal * (curve * 8.0);
            }
        }

        half4 accum = half4(0.0);
        float weightSum = 0.0;
        float sx = clamp(sampleCenter.x, bLeft + 1.0, bRight - 1.0);
        for (int i = -6; i <= 6; i++) {
            float fi = float(i);
            float w = exp(-(fi * fi) / 14.0);
            float sy = clamp(sampleCenter.y + fi * stepSize, bTop + 1.0, bBottom - 1.0);
            accum += content.eval(float2(sx, sy)) * w;
            weightSum += w;
        }
        half4 blurred = accum / weightSum;

        return mix(blurred, half4(surfaceTint.rgb, 1.0), surfaceTint.a);
    }
"""

private sealed interface SecondaryRoute {
    data object None : SecondaryRoute
    data class AppDetail(val packageName: String, val appName: String) : SecondaryRoute
    data object Logs : SecondaryRoute
    data object Appearance : SecondaryRoute
    data object About : SecondaryRoute
}

private val MiuixNavEasing = CubicBezierEasing(0.22f, 1.0f, 0.36f, 1.0f)

/**
 * 1:1 复刻图一与图二中的「主页」实心圆顶小屋 + 拱门镂空图标
 */
private val IconHome: ImageVector by lazy {
    ImageVector.Builder("Home", 24.dp, 24.dp, 24f, 24f).apply {
        path(
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.EvenOdd
        ) {
            moveTo(10.65f, 3.35f)
            curveTo(11.42f, 2.70f, 12.58f, 2.70f, 13.35f, 3.35f)
            lineTo(19.25f, 8.35f)
            curveTo(19.88f, 8.88f, 20.25f, 9.68f, 20.25f, 10.52f)
            verticalLineTo(18.20f)
            curveTo(20.25f, 19.75f, 19.00f, 21.00f, 17.45f, 21.00f)
            horizontalLineTo(6.55f)
            curveTo(5.00f, 21.00f, 3.75f, 19.75f, 3.75f, 18.20f)
            verticalLineTo(10.52f)
            curveTo(3.75f, 9.68f, 4.12f, 8.88f, 4.75f, 8.35f)
            close()

            moveTo(9.75f, 21.00f)
            verticalLineTo(15.20f)
            curveTo(9.75f, 13.95f, 10.76f, 12.95f, 12.00f, 12.95f)
            curveTo(13.24f, 12.95f, 14.25f, 13.95f, 14.25f, 15.20f)
            verticalLineTo(21.00f)
            close()
        }
    }.build()
}

private val IconAppList: ImageVector by lazy {
    ImageVector.Builder("AppList", 24.dp, 24.dp, 24f, 24f).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.95f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(5.6f, 3.6f)
            horizontalLineTo(8.6f)
            curveTo(9.8f, 3.6f, 10.6f, 4.4f, 10.6f, 5.6f)
            verticalLineTo(8.6f)
            curveTo(10.6f, 9.8f, 9.8f, 10.6f, 8.6f, 10.6f)
            horizontalLineTo(5.6f)
            curveTo(4.4f, 10.6f, 3.6f, 9.8f, 3.6f, 8.6f)
            verticalLineTo(5.6f)
            curveTo(3.6f, 4.4f, 4.4f, 3.6f, 5.6f, 3.6f)
            close()

            val cx = 17.0f
            val cy = 7.1f
            val r = 3.5f
            moveTo(cx + r, cy)
            curveTo(cx + r, cy + r * 0.552f, cx + r * 0.552f, cy + r, cx, cy + r)
            curveTo(cx - r * 0.552f, cy + r, cx - r, cy + r * 0.552f, cx - r, cy)
            curveTo(cx - r, cy - r * 0.552f, cx - r * 0.552f, cy - r, cx, cy - r)
            curveTo(cx + r * 0.552f, cy - r, cx + r, cy - r * 0.552f, cx + r, cy)
            close()

            moveTo(5.6f, 13.4f)
            horizontalLineTo(8.6f)
            curveTo(9.8f, 13.4f, 10.6f, 14.2f, 10.6f, 15.4f)
            verticalLineTo(18.4f)
            curveTo(10.6f, 19.6f, 9.8f, 20.4f, 8.6f, 20.4f)
            horizontalLineTo(5.6f)
            curveTo(4.4f, 20.4f, 3.6f, 19.6f, 3.6f, 18.4f)
            verticalLineTo(15.4f)
            curveTo(3.6f, 14.2f, 4.4f, 13.4f, 5.6f, 13.4f)
            close()

            moveTo(15.4f, 13.4f)
            horizontalLineTo(18.4f)
            curveTo(19.6f, 13.4f, 20.4f, 14.2f, 20.4f, 15.4f)
            verticalLineTo(18.4f)
            curveTo(20.4f, 19.6f, 19.6f, 20.4f, 18.4f, 20.4f)
            horizontalLineTo(15.4f)
            curveTo(14.2f, 20.4f, 13.4f, 19.6f, 13.4f, 18.4f)
            verticalLineTo(15.4f)
            curveTo(13.4f, 14.2f, 14.2f, 13.4f, 15.4f, 13.4f)
            close()
        }
    }.build()
}

private val IconRunningCheck: ImageVector by lazy {
    ImageVector.Builder("RunningCheck", 24.dp, 24.dp, 24f, 24f).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.95f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(7.2f, 3.4f)
            horizontalLineTo(16.8f)
            curveTo(19.0f, 3.4f, 20.6f, 5.0f, 20.6f, 7.2f)
            verticalLineTo(16.8f)
            curveTo(20.6f, 19.0f, 19.0f, 20.6f, 16.8f, 20.6f)
            horizontalLineTo(7.2f)
            curveTo(5.0f, 20.6f, 3.4f, 19.0f, 3.4f, 16.8f)
            verticalLineTo(7.2f)
            curveTo(3.4f, 5.0f, 5.0f, 3.4f, 7.2f, 3.4f)
            close()

            moveTo(6.6f, 12.2f)
            horizontalLineTo(9.0f)
            lineTo(10.6f, 8.6f)
            lineTo(13.2f, 15.4f)
            lineTo(14.9f, 12.2f)
            horizontalLineTo(17.4f)
        }
    }.build()
}

/**
 * 1:1 复刻图一与图二中的「设置」正六边形螺母图标
 */
private val IconHexSettings: ImageVector by lazy {
    ImageVector.Builder("HexSettings", 24.dp, 24.dp, 24f, 24f).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12.0f, 3.0f)
            lineTo(19.6f, 7.4f)
            verticalLineTo(16.6f)
            lineTo(12.0f, 21.0f)
            lineTo(4.4f, 16.6f)
            verticalLineTo(7.4f)
            close()
            val r = 3.1f
            moveTo(12f + r, 12f)
            curveTo(12f + r, 12f + r * 0.552f, 12f + r * 0.552f, 12f + r, 12f, 12f + r)
            curveTo(12f - r * 0.552f, 12f + r, 12f - r, 12f + r * 0.552f, 12f - r, 12f)
            curveTo(12f - r, 12f - r * 0.552f, 12f - r * 0.552f, 12f - r, 12f, 12f - r)
            curveTo(12f + r * 0.552f, 12f - r, 12f + r, 12f - r * 0.552f, 12f + r, 12f)
            close()
        }
    }.build()
}

private val IconMoreCircle: ImageVector by lazy {
    ImageVector.Builder("MoreCircle", 24.dp, 24.dp, 24f, 24f).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.85f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            val r = 9.0f
            moveTo(12f + r, 12f)
            curveTo(12f + r, 12f + r * 0.552f, 12f + r * 0.552f, 12f + r, 12f, 12f + r)
            curveTo(12f - r * 0.552f, 12f + r, 12f - r, 12f + r * 0.552f, 12f - r, 12f)
            curveTo(12f - r, 12f - r * 0.552f, 12f - r * 0.552f, 12f - r, 12f, 12f - r)
            curveTo(12f + r * 0.552f, 12f - r, 12f + r, 12f - r * 0.552f, 12f + r, 12f)
            close()
        }
        path(fill = SolidColor(Color.Black)) {
            val xs = floatArrayOf(8.2f, 12.0f, 15.8f)
            val dotR = 1.15f
            for (cx in xs) {
                moveTo(cx + dotR, 12f)
                curveTo(cx + dotR, 12f + dotR * 0.55f, cx + dotR * 0.55f, 12f + dotR, cx, 12f + dotR)
                curveTo(cx - dotR * 0.55f, 12f + dotR, cx - dotR, 12f + dotR * 0.55f, cx - dotR, 12f)
                curveTo(cx - dotR, 12f - dotR * 0.55f, cx - dotR * 0.55f, 12f - dotR, cx, 12f - dotR)
                curveTo(cx + dotR * 0.55f, 12f - dotR, cx + dotR, 12f - dotR * 0.55f, cx + dotR, 12f)
                close()
            }
        }
    }.build()
}

private val IconRefresh: ImageVector by lazy {
    ImageVector.Builder("Refresh", 24.dp, 24.dp, 24f, 24f).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(18.2f, 7.6f)
            curveTo(16.8f, 5.6f, 14.5f, 4.4f, 12.0f, 4.4f)
            curveTo(7.8f, 4.4f, 4.4f, 7.8f, 4.4f, 12.0f)
            curveTo(4.4f, 16.2f, 7.8f, 19.6f, 12.0f, 19.6f)
            curveTo(15.6f, 19.6f, 18.6f, 17.1f, 19.4f, 13.7f)
            moveTo(18.6f, 4.4f)
            verticalLineTo(8.2f)
            horizontalLineTo(14.8f)
        }
    }.build()
}

private val IconBack: ImageVector by lazy {
    ImageVector.Builder("BackArrow", 24.dp, 24.dp, 24f, 24f).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.95f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(19.5f, 12.0f)
            lineTo(4.8f, 12.0f)
            moveTo(11.0f, 5.8f)
            lineTo(4.8f, 12.0f)
            lineTo(11.0f, 18.2f)
        }
    }.build()
}

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 4 })
    var secondaryRoute by remember { mutableStateOf<SecondaryRoute>(SecondaryRoute.None) }

    var showAppFilterSheet by remember { mutableStateOf(false) }
    var includeSystemApps by remember { mutableStateOf(false) }
    var appFilterIndex by remember { mutableIntStateOf(0) }

    var logFilterLevel by remember { mutableStateOf(LogFilterLevel.ALL) }
    var showLogTopMenu by remember { mutableStateOf(false) }
    val logListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val config by viewModel.config.collectAsState()
    val logs by viewModel.logsList.collectAsState()
    val isSystemActive by viewModel.isSystemActive.collectAsState()
    val isPkActive by viewModel.isPowerKeeperActive.collectAsState()

    val tabTitles = listOf("Smillet-Next", "应用列表", "运行列表", "设置")
    val navLabels = listOf("主页", "应用列表", "运行列表", "设置")
    val navIcons = listOf(IconHome, IconAppList, IconRunningCheck, IconHexSettings)

    val isDark = LocalAppDarkMode.current
    val blurEnabled = LocalAppBlurEnabled.current
    val surfaceBg = MiuixTheme.colorScheme.surface
    val topBarColor = if (blurEnabled) Color.Transparent else surfaceBg

    var appListTopBarHeight by remember { mutableStateOf(96.dp) }
    var logPageTopBarHeight by remember { mutableStateOf(96.dp) }
    var floatingBarBounds by remember { mutableStateOf(Rect.Zero) }
    var bottomBarDragFraction by remember { mutableStateOf<Float?>(null) }

    // 预测性返回手势实时进度 (0f..1f) 与边缘方向 (0=Left, 1=Right)
    var predictiveBackProgress by remember { mutableFloatStateOf(0f) }
    var predictiveBackEdge by remember { mutableIntStateOf(0) }

    // 仅在页面滑动完全静止后 (settledPage) 才触发后台状态刷新，彻底消除滑动翻页途中的重组卡顿
    LaunchedEffect(pagerState, secondaryRoute) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { settled ->
                if (settled != 1) {
                    showAppFilterSheet = false
                }
                if (settled == 1 && secondaryRoute is SecondaryRoute.None) {
                    viewModel.refreshBatteryOptimizationStates()
                }
                if (settled == 2 && secondaryRoute is SecondaryRoute.None) {
                    viewModel.refreshRunningApps(includeSystemApps)
                }
            }
    }

    val exportLogFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            runCatching {
                val currentFiltered = filterLogLines(logs, config.logLevel, logFilterLevel)
                    .takeLast(MAX_DISPLAY_LOG_COUNT)
                val content = if (currentFiltered.isEmpty()) {
                    "暂无日志记录\n"
                } else {
                    currentFiltered.joinToString("\n") + "\n"
                }
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "日志文件已成功导出", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(context, "导出日志失败：${err.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 1. 弹出菜单优先拦截返回
    BackHandler(enabled = showAppFilterSheet || showLogTopMenu) {
        when {
            showAppFilterSheet -> showAppFilterSheet = false
            showLogTopMenu -> showLogTopMenu = false
        }
    }

    // 2. 二级页返回处理：当开启「预测性返回手势」时使用 PredictiveBackHandler 驱动实时缩放/视差返回动画
    PredictiveBackHandler(
        enabled = !showAppFilterSheet && !showLogTopMenu && secondaryRoute !is SecondaryRoute.None && config.predictiveBackEnabled
    ) { progressFlow: Flow<BackEventCompat> ->
        try {
            progressFlow.collect { backEvent ->
                predictiveBackEdge = backEvent.swipeEdge
                predictiveBackProgress = backEvent.progress
            }
            predictiveBackProgress = 0f
            viewModel.dismissDiagnosticDialog()
            secondaryRoute = SecondaryRoute.None
        } catch (_: CancellationException) {
            predictiveBackProgress = 0f
        }
    }

    // 当关闭「预测性返回手势」时使用常规 BackHandler
    BackHandler(
        enabled = !showAppFilterSheet && !showLogTopMenu && secondaryRoute !is SecondaryRoute.None && !config.predictiveBackEnabled
    ) {
        viewModel.dismissDiagnosticDialog()
        secondaryRoute = SecondaryRoute.None
    }

    fun navigateToTab(targetPage: Int) {
        showAppFilterSheet = false
        val target = targetPage.coerceIn(0, 3)
        scope.launch {
            pagerState.animateScrollToPage(
                page = target,
                animationSpec = spring(
                    dampingRatio = 0.92f,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
    }

    /**
     * 底栏横向滑动 1:1 实时跟手同步函数：
     * - 拖动途中将连续位置 f (0f..3f) 拆解为 (targetPage, offsetFraction in -0.5f..0.5f)，
     *   直接驱动 pagerState.scrollToPage(targetPage, offsetFraction) 实时跟手；
     * - 松手时平滑归位到最近的 Tab 页。
     */
    fun onBottomBarDragUpdate(continuousFraction: Float?) {
        bottomBarDragFraction = continuousFraction
        if (continuousFraction != null) {
            showAppFilterSheet = false
            val targetPage = continuousFraction.roundToInt().coerceIn(0, 3)
            val offsetFraction = (continuousFraction - targetPage).coerceIn(-0.5f, 0.5f)
            scope.launch {
                pagerState.scrollToPage(page = targetPage, pageOffsetFraction = offsetFraction)
            }
        }
    }

    fun onBottomBarDragFinish() {
        val lastF = bottomBarDragFraction
        bottomBarDragFraction = null
        val settlePage = (lastF ?: (pagerState.currentPage + pagerState.currentPageOffsetFraction))
            .roundToInt()
            .coerceIn(0, 3)
        scope.launch {
            pagerState.animateScrollToPage(
                page = settlePage,
                animationSpec = spring(
                    dampingRatio = 0.92f,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
    }

    val hasSecondaryPage = secondaryRoute !is SecondaryRoute.None
    val baseParallax by animateFloatAsState(
        targetValue = if (hasSecondaryPage) 1f else 0f,
        animationSpec = tween(durationMillis = 320, easing = MiuixNavEasing),
        label = "MainParallaxProgress"
    )
    // 预测性返回手势拖动期间，底层主页随手指进度实时向回推进
    val effectiveMainParallax = (baseParallax * (1f - predictiveBackProgress * 0.85f)).coerceIn(0f, 1f)

    val navInsetsBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(surfaceBg)
    ) {
        // 1. 底层常驻主界面：4 Tab HorizontalPager + 底部导航栏（支持点击与 1:1 跟手左右滑动切换页面）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = -size.width * 0.24f * effectiveMainParallax
                }
        ) {
            Scaffold(
                bottomBar = {
                    if (!config.floatingBottomBar) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(Unit) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { offset ->
                                            val w = size.width.toFloat().coerceAtLeast(1f)
                                            val f = ((offset.x / w) * 4f - 0.5f).coerceIn(0f, 3f)
                                            onBottomBarDragUpdate(f)
                                        },
                                        onDragEnd = {
                                            onBottomBarDragFinish()
                                        },
                                        onDragCancel = {
                                            onBottomBarDragFinish()
                                        }
                                    ) { change, _ ->
                                        change.consume()
                                        val w = size.width.toFloat().coerceAtLeast(1f)
                                        val f = ((change.position.x / w) * 4f - 0.5f).coerceIn(0f, 3f)
                                        onBottomBarDragUpdate(f)
                                    }
                                }
                        ) {
                            if (config.bottomBarMode == 0) {
                                NavigationBar {
                                    for (i in 0..3) {
                                        NavigationBarItem(
                                            selected = pagerState.currentPage == i,
                                            onClick = { navigateToTab(i) },
                                            icon = navIcons[i],
                                            label = navLabels[i]
                                        )
                                    }
                                }
                            } else {
                                StandardIconOnlyBottomBar(
                                    selectedIndex = pagerState.currentPage,
                                    icons = navIcons,
                                    labels = navLabels,
                                    onSelect = { navigateToTab(it) }
                                )
                            }
                        }
                    }
                }
            ) { outerPadding ->
                val bottomBarHeight = if (config.floatingBottomBar) {
                    navInsetsBottom + if (config.bottomBarMode == 1) 76.dp else 88.dp
                } else {
                    outerPadding.calculateBottomPadding()
                }

                val bottomStyleFlag = when {
                    !config.floatingBottomBar -> 0
                    config.floatingBottomBarStyle == 1 -> 2 // 图二：iOS 液态玻璃
                    blurEnabled -> 1 // 图一：Miuix 悬浮胶囊
                    else -> 0
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    // 静止外层容器承载底部胶囊区域模糊
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .bottomCapsuleFrostedBlur(
                                enabled = bottomStyleFlag > 0,
                                bottomRect = floatingBarBounds,
                                isLiquidGlass = bottomStyleFlag == 2,
                                surfaceColor = surfaceBg,
                                isDark = isDark
                            )
                    ) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize()
                        ) { page ->
                            val tabScrollBehavior = MiuixScrollBehavior()

                            Scaffold(
                                topBar = {
                                    TopAppBar(
                                        title = tabTitles[page],
                                        scrollBehavior = tabScrollBehavior,
                                        color = topBarColor,
                                        actions = {
                                            when (page) {
                                                1 -> {
                                                    TopMenuActionButton(
                                                        active = showAppFilterSheet,
                                                        isDark = isDark,
                                                        contentDescription = "筛选",
                                                        onClick = { showAppFilterSheet = !showAppFilterSheet }
                                                    )
                                                }
                                                2 -> {
                                                    IconButton(onClick = { viewModel.connectAndRefresh(includeSystemApps) }) {
                                                        Icon(
                                                            imageVector = IconRefresh,
                                                            contentDescription = "刷新",
                                                            tint = MiuixTheme.colorScheme.onSurface
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                            ) { tabInnerPadding ->
                                val topBarHeight = tabInnerPadding.calculateTopPadding()
                                if (page == 1 && topBarHeight > 0.dp) {
                                    appListTopBarHeight = topBarHeight
                                }
                                val pageContentPadding = PaddingValues(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = topBarHeight + 10.dp,
                                    bottom = bottomBarHeight + 16.dp
                                )

                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .topBarFrostedBlur(
                                            enabled = blurEnabled,
                                            topBarHeight = topBarHeight,
                                            surfaceColor = surfaceBg,
                                            isDark = isDark,
                                            progressive = config.topBarBlurMode == 1
                                        )
                                        .background(surfaceBg)
                                ) {
                                    when (page) {
                                        0 -> HomePage(
                                            viewModel = viewModel,
                                            config = config,
                                            isSystemActive = isSystemActive,
                                            isPowerKeeperActive = isPkActive,
                                            scrollBehavior = tabScrollBehavior,
                                            contentPadding = pageContentPadding,
                                            onNavigateToWhitelist = {
                                                appFilterIndex = 2
                                                navigateToTab(1)
                                            },
                                            onNavigateToBlacklist = {
                                                appFilterIndex = 3
                                                navigateToTab(1)
                                            },
                                            onNavigateToRunning = { navigateToTab(2) }
                                        )
                                        1 -> AppListPage(
                                            viewModel = viewModel,
                                            config = config,
                                            scrollBehavior = tabScrollBehavior,
                                            contentPadding = pageContentPadding,
                                            includeSystemApps = includeSystemApps,
                                            filterIndex = appFilterIndex,
                                            onFilterIndexChange = { appFilterIndex = it },
                                            onOpenAppConfig = { pkg, name ->
                                                secondaryRoute = SecondaryRoute.AppDetail(pkg, name)
                                            }
                                        )
                                        2 -> RunningListPage(
                                            viewModel = viewModel,
                                            scrollBehavior = tabScrollBehavior,
                                            contentPadding = pageContentPadding,
                                            onOpenAppConfig = { pkg, name ->
                                                secondaryRoute = SecondaryRoute.AppDetail(pkg, name)
                                            },
                                            onDiagnoseApp = { pkg -> viewModel.requestDiagnoseApp(pkg) }
                                        )
                                        3 -> SettingsPage(
                                            viewModel = viewModel,
                                            config = config,
                                            scrollBehavior = tabScrollBehavior,
                                            contentPadding = pageContentPadding,
                                            onNavigateToLogs = {
                                                showLogTopMenu = false
                                                secondaryRoute = SecondaryRoute.Logs
                                            },
                                            onNavigateToAppearance = {
                                                secondaryRoute = SecondaryRoute.Appearance
                                            },
                                            onNavigateToAbout = {
                                                secondaryRoute = SecondaryRoute.About
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 悬浮底栏叠放层（支持点击切页 + 1:1 实时跟手左右滑动切页）
                    if (config.floatingBottomBar) {
                        val updateFloatingBounds: (Rect) -> Unit = remember {
                            { newRect ->
                                if (abs(newRect.left - floatingBarBounds.left) > 0.5f ||
                                    abs(newRect.top - floatingBarBounds.top) > 0.5f ||
                                    abs(newRect.right - floatingBarBounds.right) > 0.5f ||
                                    abs(newRect.bottom - floatingBarBounds.bottom) > 0.5f
                                ) {
                                    floatingBarBounds = newRect
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(bottom = navInsetsBottom + 14.dp, start = 24.dp, end = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (config.floatingBottomBarStyle == 1) {
                                LiquidGlassFloatingNavigationBar(
                                    pagerState = pagerState,
                                    dragFraction = bottomBarDragFraction,
                                    onDragUpdate = ::onBottomBarDragUpdate,
                                    onDragFinish = ::onBottomBarDragFinish,
                                    iconOnly = config.bottomBarMode == 1,
                                    isDark = isDark,
                                    icons = navIcons,
                                    labels = navLabels,
                                    onSelect = { navigateToTab(it) },
                                    onBoundsChanged = updateFloatingBounds
                                )
                            } else {
                                MiuixFloatingNavigationBar(
                                    pagerState = pagerState,
                                    dragFraction = bottomBarDragFraction,
                                    onDragUpdate = ::onBottomBarDragUpdate,
                                    onDragFinish = ::onBottomBarDragFinish,
                                    iconOnly = config.bottomBarMode == 1,
                                    isDark = isDark,
                                    blurEnabled = blurEnabled,
                                    icons = navIcons,
                                    labels = navLabels,
                                    onSelect = { navigateToTab(it) },
                                    onBoundsChanged = updateFloatingBounds
                                )
                            }
                        }
                    }
                }
            }

            if (effectiveMainParallax > 0.005f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = (if (isDark) 0.28f else 0.12f) * effectiveMainParallax))
                )
            }
        }

        // 2. 顶层二级页面导航容器（支持预测性返回手势缩放/圆角/视差预览 & 横移返回手势）
        AnimatedContent(
            targetState = secondaryRoute,
            transitionSpec = {
                val isPushing = targetState !is SecondaryRoute.None
                if (isPushing) {
                    (slideInHorizontally(
                        animationSpec = tween(durationMillis = 320, easing = MiuixNavEasing),
                        initialOffsetX = { fullWidth -> fullWidth }
                    ) togetherWith (slideOutHorizontally(
                        animationSpec = tween(durationMillis = 320, easing = MiuixNavEasing),
                        targetOffsetX = { fullWidth -> -fullWidth / 4 }
                    ) + fadeOut(animationSpec = tween(durationMillis = 320), targetAlpha = 0.9f))).apply {
                        targetContentZIndex = 1f
                    }
                } else {
                    (fadeIn(animationSpec = tween(durationMillis = 280)) togetherWith slideOutHorizontally(
                        animationSpec = tween(durationMillis = 300, easing = MiuixNavEasing),
                        targetOffsetX = { fullWidth -> fullWidth }
                    )).apply {
                        targetContentZIndex = 0f
                    }
                } using SizeTransform(clip = false)
            },
            label = "SecondaryNavDisplay"
        ) { subRoute ->
            when (subRoute) {
                is SecondaryRoute.None -> {
                    Box(modifier = Modifier.fillMaxSize())
                }

                is SecondaryRoute.AppDetail -> {
                    val subScrollBehavior = MiuixScrollBehavior()
                    SwipeAndPredictiveBackContainer(
                        swipeBackEnabled = config.swipeBackEnabled,
                        predictiveProgress = predictiveBackProgress,
                        predictiveEdge = predictiveBackEdge,
                        backgroundColor = surfaceBg,
                        onBack = {
                            viewModel.dismissDiagnosticDialog()
                            viewModel.refreshBatteryOptimizationStates()
                            secondaryRoute = SecondaryRoute.None
                        }
                    ) {
                        Scaffold(
                            topBar = {
                                TopAppBar(
                                    title = subRoute.appName,
                                    scrollBehavior = subScrollBehavior,
                                    color = topBarColor,
                                    navigationIcon = {
                                        IconButton(onClick = {
                                            viewModel.dismissDiagnosticDialog()
                                            viewModel.refreshBatteryOptimizationStates()
                                            secondaryRoute = SecondaryRoute.None
                                        }) {
                                            Icon(
                                                imageVector = IconBack,
                                                contentDescription = "返回",
                                                tint = MiuixTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                )
                            }
                        ) { subPadding ->
                            val topBarHeight = subPadding.calculateTopPadding()
                            val bottomBarHeight = subPadding.calculateBottomPadding()
                            val pageContentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = topBarHeight + 10.dp,
                                bottom = bottomBarHeight + 12.dp
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .topBarFrostedBlur(
                                        enabled = blurEnabled,
                                        topBarHeight = topBarHeight,
                                        surfaceColor = surfaceBg,
                                        isDark = isDark,
                                        progressive = config.topBarBlurMode == 1
                                    )
                                    .background(surfaceBg)
                            ) {
                                AppConfigDialog(
                                    packageName = subRoute.packageName,
                                    appName = subRoute.appName,
                                    config = config,
                                    viewModel = viewModel,
                                    scrollBehavior = subScrollBehavior,
                                    contentPadding = pageContentPadding,
                                    onDismiss = {
                                        viewModel.dismissDiagnosticDialog()
                                        viewModel.refreshBatteryOptimizationStates()
                                        secondaryRoute = SecondaryRoute.None
                                    }
                                )
                            }
                        }
                    }
                }

                is SecondaryRoute.Logs -> {
                    val subScrollBehavior = MiuixScrollBehavior()
                    SwipeAndPredictiveBackContainer(
                        swipeBackEnabled = config.swipeBackEnabled,
                        predictiveProgress = predictiveBackProgress,
                        predictiveEdge = predictiveBackEdge,
                        backgroundColor = surfaceBg,
                        onBack = {
                            showLogTopMenu = false
                            viewModel.stopLogPolling()
                            secondaryRoute = SecondaryRoute.None
                        }
                    ) {
                        Scaffold(
                            topBar = {
                                TopAppBar(
                                    title = "日志",
                                    scrollBehavior = subScrollBehavior,
                                    color = topBarColor,
                                    navigationIcon = {
                                        IconButton(onClick = {
                                            showLogTopMenu = false
                                            viewModel.stopLogPolling()
                                            secondaryRoute = SecondaryRoute.None
                                        }) {
                                            Icon(
                                                imageVector = IconBack,
                                                contentDescription = "返回",
                                                tint = MiuixTheme.colorScheme.onSurface
                                            )
                                        }
                                    },
                                    actions = {
                                        TopMenuActionButton(
                                            active = showLogTopMenu,
                                            isDark = isDark,
                                            contentDescription = "日志选项",
                                            onClick = { showLogTopMenu = !showLogTopMenu }
                                        )
                                    }
                                )
                            }
                        ) { subPadding ->
                            val topBarHeight = subPadding.calculateTopPadding()
                            if (topBarHeight > 0.dp) {
                                logPageTopBarHeight = topBarHeight
                            }
                            val bottomBarHeight = subPadding.calculateBottomPadding()
                            val pageContentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = topBarHeight + 10.dp,
                                bottom = bottomBarHeight + 12.dp
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .topBarFrostedBlur(
                                        enabled = blurEnabled,
                                        topBarHeight = topBarHeight,
                                        surfaceColor = surfaceBg,
                                        isDark = isDark,
                                        progressive = config.topBarBlurMode == 1
                                    )
                                    .background(surfaceBg)
                            ) {
                                LogPage(
                                    viewModel = viewModel,
                                    config = config,
                                    listState = logListState,
                                    scrollBehavior = subScrollBehavior,
                                    contentPadding = pageContentPadding,
                                    filterLevel = logFilterLevel,
                                    onFilterLevelChange = { logFilterLevel = it }
                                )
                            }
                        }
                    }
                }

                is SecondaryRoute.Appearance -> {
                    val subScrollBehavior = MiuixScrollBehavior()
                    SwipeAndPredictiveBackContainer(
                        swipeBackEnabled = config.swipeBackEnabled,
                        predictiveProgress = predictiveBackProgress,
                        predictiveEdge = predictiveBackEdge,
                        backgroundColor = surfaceBg,
                        onBack = { secondaryRoute = SecondaryRoute.None }
                    ) {
                        Scaffold(
                            topBar = {
                                TopAppBar(
                                    title = "外观与主题",
                                    scrollBehavior = subScrollBehavior,
                                    color = topBarColor,
                                    navigationIcon = {
                                        IconButton(onClick = { secondaryRoute = SecondaryRoute.None }) {
                                            Icon(
                                                imageVector = IconBack,
                                                contentDescription = "返回",
                                                tint = MiuixTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                )
                            }
                        ) { subPadding ->
                            val topBarHeight = subPadding.calculateTopPadding()
                            val bottomBarHeight = subPadding.calculateBottomPadding()
                            val pageContentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = topBarHeight + 10.dp,
                                bottom = bottomBarHeight + 12.dp
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .topBarFrostedBlur(
                                        enabled = blurEnabled,
                                        topBarHeight = topBarHeight,
                                        surfaceColor = surfaceBg,
                                        isDark = isDark,
                                        progressive = config.topBarBlurMode == 1
                                    )
                                    .background(surfaceBg)
                            ) {
                                AppearancePage(
                                    viewModel = viewModel,
                                    config = config,
                                    scrollBehavior = subScrollBehavior,
                                    contentPadding = pageContentPadding
                                )
                            }
                        }
                    }
                }

                is SecondaryRoute.About -> {
                    val subScrollBehavior = MiuixScrollBehavior()
                    SwipeAndPredictiveBackContainer(
                        swipeBackEnabled = config.swipeBackEnabled,
                        predictiveProgress = predictiveBackProgress,
                        predictiveEdge = predictiveBackEdge,
                        backgroundColor = surfaceBg,
                        onBack = { secondaryRoute = SecondaryRoute.None }
                    ) {
                        Scaffold(
                            topBar = {
                                SmallTopAppBar(
                                    title = "",
                                    scrollBehavior = subScrollBehavior,
                                    color = Color.Transparent,
                                    navigationIcon = {
                                        IconButton(onClick = { secondaryRoute = SecondaryRoute.None }) {
                                            Icon(
                                                imageVector = IconBack,
                                                contentDescription = "返回",
                                                tint = MiuixTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                )
                            }
                        ) { subPadding ->
                            val topBarHeight = subPadding.calculateTopPadding()
                            val bottomBarHeight = subPadding.calculateBottomPadding()
                            val pageContentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = topBarHeight,
                                bottom = bottomBarHeight + 20.dp
                            )
                            AboutPage(
                                scrollBehavior = subScrollBehavior,
                                contentPadding = pageContentPadding
                            )
                        }
                    }
                }
            }
        }

        // 应用列表右上角弹出菜单
        val safeFilterIndex = appFilterIndex.coerceIn(0, APP_FILTER_OPTIONS.lastIndex)
        MiuixTopPopupMenu(
            visible = showAppFilterSheet && secondaryRoute is SecondaryRoute.None && pagerState.currentPage == 1,
            onDismiss = { showAppFilterSheet = false },
            topOffset = appListTopBarHeight + 4.dp,
            width = 186.dp
        ) {
            APP_FILTER_OPTIONS.forEachIndexed { idx, label ->
                MiuixPopupOptionRow(
                    text = label,
                    selected = idx == safeFilterIndex,
                    onClick = {
                        appFilterIndex = idx
                        if (idx == 7) {
                            viewModel.refreshBatteryOptimizationStates()
                        }
                        showAppFilterSheet = false
                    }
                )
            }

            MiuixPopupOptionRow(
                text = "显示系统应用",
                selected = includeSystemApps,
                onClick = {
                    includeSystemApps = !includeSystemApps
                    viewModel.refreshBatteryOptimizationStates()
                    showAppFilterSheet = false
                }
            )
        }

        // 日志页右上角弹出菜单
        MiuixTopPopupMenu(
            visible = showLogTopMenu && secondaryRoute is SecondaryRoute.Logs,
            onDismiss = { showLogTopMenu = false },
            topOffset = logPageTopBarHeight + 4.dp,
            width = 176.dp
        ) {
            LogFilterLevel.entries.forEach { level ->
                MiuixPopupOptionRow(
                    text = level.label,
                    selected = level == logFilterLevel,
                    onClick = {
                        logFilterLevel = level
                        showLogTopMenu = false
                    }
                )
            }

            val currentFilteredLogs = filterLogLines(logs, config.logLevel, logFilterLevel)
            val displayedLogCount = currentFilteredLogs.size.coerceAtMost(MAX_DISPLAY_LOG_COUNT)

            MiuixPopupActionRow(
                text = "滚动到顶部",
                onClick = {
                    showLogTopMenu = false
                    if (displayedLogCount > 0) {
                        scope.launch { logListState.animateScrollToItem(0) }
                    }
                }
            )
            MiuixPopupActionRow(
                text = "滚动到底部",
                onClick = {
                    showLogTopMenu = false
                    if (displayedLogCount > 0) {
                        scope.launch { logListState.animateScrollToItem(displayedLogCount) }
                    }
                }
            )
            MiuixPopupActionRow(
                text = "导出日志文件",
                onClick = {
                    showLogTopMenu = false
                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    exportLogFileLauncher.launch("Smillet-Next_log_$ts.txt")
                }
            )
        }
    }
}

/**
 * 二级页预测性返回动画 + 任意位置横移返回容器
 */
@Composable
private fun SwipeAndPredictiveBackContainer(
    swipeBackEnabled: Boolean,
    predictiveProgress: Float,
    predictiveEdge: Int,
    backgroundColor: Color,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    val animatedDragX by animateFloatAsState(
        targetValue = dragOffsetX,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "SwipeBackOffsetX"
    )

    val predScale = 1f - (predictiveProgress * 0.085f).coerceIn(0f, 0.12f)
    val predCorner = (predictiveProgress * 28f).coerceIn(0f, 28f).dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val predShiftX = if (predictiveProgress > 0.001f) {
                    val dir = if (predictiveEdge == 0) 1f else -1f
                    dir * size.width * 0.18f * predictiveProgress + size.width * 0.16f * predictiveProgress
                } else {
                    0f
                }
                translationX = animatedDragX + predShiftX
                scaleX = predScale
                scaleY = predScale
                shape = RoundedCornerShape(predCorner)
                clip = predictiveProgress > 0.005f || animatedDragX > 1f
            }
            .background(backgroundColor)
            .then(
                if (swipeBackEnabled) {
                    Modifier.pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (dragOffsetX > size.width * 0.20f) {
                                    dragOffsetX = 0f
                                    onBack()
                                } else {
                                    dragOffsetX = 0f
                                }
                            },
                            onDragCancel = {
                                dragOffsetX = 0f
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                val next = (dragOffsetX + dragAmount).coerceAtLeast(0f)
                                if (next > 0f) {
                                    change.consume()
                                }
                                dragOffsetX = next
                            }
                        )
                    }
                } else {
                    Modifier
                }
            )
    ) {
        content()
    }
}

/**
 * 1:1 复刻图一：Miuix 样式悬浮底栏 (MiuixFloatingNavigationBar)
 * - 无内部小胶囊背景；
 * - 高浓度磨砂白胶囊表面 + 柔和外圈阴影 + 1.5.dp 纯白内描边；
 * - 未选中项为深灰色 (#606060)，选中项为纯黑色 (#000000) / 深色模式纯白 (#FFFFFF)；
 * - 支持点击与 1:1 跟手左右滑动切换页面。
 */
@Composable
private fun MiuixFloatingNavigationBar(
    pagerState: PagerState,
    dragFraction: Float?,
    onDragUpdate: (Float?) -> Unit,
    onDragFinish: () -> Unit,
    iconOnly: Boolean,
    isDark: Boolean,
    blurEnabled: Boolean,
    icons: List<ImageVector>,
    labels: List<String>,
    onSelect: (Int) -> Unit,
    onBoundsChanged: (Rect) -> Unit
) {
    val barHeight = if (iconOnly) 54.dp else 64.dp
    val maxBarWidth = if (iconOnly) 296.dp else 342.dp
    val selectedColor = if (isDark) Color.White else Color.Black
    val unselectedColor = if (isDark) Color(0xFF98989E) else Color(0xFF606060)

    val containerColor = if (blurEnabled) {
        if (isDark) Color(0xFF242428).copy(alpha = 0.84f) else Color(0xFFFAFAFA).copy(alpha = 0.82f)
    } else {
        if (isDark) Color(0xFF242428) else Color(0xFFFAFAFA)
    }
    val innerRimColor = if (isDark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.96f)
    val outerHairlineColor = if (isDark) Color.Black.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.05f)

    val activeTabIndex by remember(dragFraction) {
        derivedStateOf {
            val pos = dragFraction ?: (pagerState.currentPage + pagerState.currentPageOffsetFraction)
            pos.roundToInt().coerceIn(0, icons.lastIndex)
        }
    }

    val capsuleShape = RoundedCornerShape(50)

    Box(
        modifier = Modifier
            .widthIn(max = maxBarWidth)
            .fillMaxWidth()
            .height(barHeight)
            .onGloballyPositioned { coords ->
                onBoundsChanged(coords.boundsInWindow())
            }
            .then(
                if (!blurEnabled) {
                    Modifier.shadow(
                        elevation = 8.dp,
                        shape = capsuleShape,
                        ambientColor = Color.Black.copy(alpha = 0.12f),
                        spotColor = Color.Black.copy(alpha = 0.12f)
                    )
                } else {
                    Modifier
                }
            )
            .clip(capsuleShape)
            .background(containerColor)
            .border(width = 0.5.dp, color = outerHairlineColor, shape = capsuleShape)
            .padding(0.5.dp)
            .border(width = 1.5.dp, color = innerRimColor, shape = capsuleShape)
            .pointerInput(icons.size) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val f = ((offset.x / w) * icons.size - 0.5f).coerceIn(0f, (icons.size - 1).toFloat())
                        onDragUpdate(f)
                    },
                    onDragEnd = {
                        onDragFinish()
                    },
                    onDragCancel = {
                        onDragFinish()
                    }
                ) { change, _ ->
                    change.consume()
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val f = ((change.position.x / w) * icons.size - 0.5f).coerceIn(0f, (icons.size - 1).toFloat())
                    onDragUpdate(f)
                }
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in icons.indices) {
                val selected = activeTabIndex == i
                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = if (isPressed) 0.92f else 1.0f,
                    animationSpec = spring(stiffness = Spring.StiffnessMedium),
                    label = "MiuixFloatItemScale"
                )
                val tint = if (selected) selectedColor else unselectedColor

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { onSelect(i) }
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = icons[i],
                        contentDescription = labels[i],
                        tint = tint,
                        modifier = Modifier.size(if (iconOnly) 24.dp else 23.dp)
                    )
                    if (!iconOnly) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = labels[i],
                            fontSize = 11.5.sp,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            color = tint
                        )
                    }
                }
            }
        }
    }
}

/**
 * 1:1 复刻图二：iOS 液态玻璃样式悬浮底栏 (LiquidGlassFloatingNavigationBar)
 * - 高透光率液态玻璃胶囊背景（底层文字轮廓柔和透出）+ 1.6.dp 纯白高光外圈折射边；
 * - 内部选中指示器为浅灰半透明圆角胶囊 (#E5E5E7 / Color.Black.copy(alpha = 0.095f))，内缩 4.dp，无边框，1:1 实时跟手滑动；
 * - 未选中项图标与文字为纯黑色 (#000000)，选中项图标与文字为主题蓝色 (#3482FF)。
 */
@Composable
private fun LiquidGlassFloatingNavigationBar(
    pagerState: PagerState,
    dragFraction: Float?,
    onDragUpdate: (Float?) -> Unit,
    onDragFinish: () -> Unit,
    iconOnly: Boolean,
    isDark: Boolean,
    icons: List<ImageVector>,
    labels: List<String>,
    onSelect: (Int) -> Unit,
    onBoundsChanged: (Rect) -> Unit
) {
    val barHeight = if (iconOnly) 56.dp else 68.dp
    val maxBarWidth = if (iconOnly) 304.dp else 356.dp
    val primaryColor = MiuixTheme.colorScheme.primary
    val unselectedColor = if (isDark) Color.White else Color.Black

    val activeTabIndex by remember(dragFraction) {
        derivedStateOf {
            val pos = dragFraction ?: (pagerState.currentPage + pagerState.currentPageOffsetFraction)
            pos.roundToInt().coerceIn(0, icons.lastIndex)
        }
    }

    val capsuleShape = RoundedCornerShape(50)

    Box(
        modifier = Modifier
            .widthIn(max = maxBarWidth)
            .fillMaxWidth()
            .height(barHeight)
            .onGloballyPositioned { coords ->
                onBoundsChanged(coords.boundsInWindow())
            }
            .clip(capsuleShape)
            .pointerInput(icons.size) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val f = ((offset.x / w) * icons.size - 0.5f).coerceIn(0f, (icons.size - 1).toFloat())
                        onDragUpdate(f)
                    },
                    onDragEnd = {
                        onDragFinish()
                    },
                    onDragCancel = {
                        onDragFinish()
                    }
                ) { change, _ ->
                    change.consume()
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val f = ((change.position.x / w) * icons.size - 0.5f).coerceIn(0f, (icons.size - 1).toFloat())
                    onDragUpdate(f)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val outerR = size.height / 2f

            // 1. 液态玻璃通透底色（保持高透光率，使底层模糊文字清晰透出，与图二完全一致）
            val glassFillColor = if (isDark) {
                Color(0xFF1C1C1E).copy(alpha = 0.34f)
            } else {
                Color.White.copy(alpha = 0.26f)
            }
            drawRoundRect(
                color = glassFillColor,
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = CornerRadius(outerR, outerR)
            )

            // 2. 内部 1/4 槽位浅灰圆角胶囊指示器（直接在 Draw 阶段读取位置，零重组延迟，100% 跟手）
            val currentPos = (dragFraction ?: (pagerState.currentPage + pagerState.currentPageOffsetFraction))
                .coerceIn(0f, (icons.size - 1).toFloat())
            val padH = 4.dp.toPx()
            val padV = 4.dp.toPx()
            val slotW = (size.width - padH * 2f) / icons.size.coerceAtLeast(1)
            val pillH = size.height - padV * 2f
            val pillR = pillH / 2f
            val pillLeft = padH + slotW * currentPos

            val pillGrayColor = if (isDark) {
                Color.White.copy(alpha = 0.16f)
            } else {
                Color.Black.copy(alpha = 0.095f)
            }
            drawRoundRect(
                color = pillGrayColor,
                topLeft = Offset(pillLeft, padV),
                size = Size(slotW, pillH),
                cornerRadius = CornerRadius(pillR, pillR)
            )

            // 3. 外圈微弱内阴影与 1.6.dp 纯白高光折射描边（复刻图二右上方与四周的玻璃边缘折射感）
            val outerSubtleBorder = if (isDark) {
                Color.Black.copy(alpha = 0.32f)
            } else {
                Color.Black.copy(alpha = 0.045f)
            }
            val outerHairlinePx = 0.6.dp.toPx()
            drawRoundRect(
                color = outerSubtleBorder,
                topLeft = Offset(outerHairlinePx * 0.5f, outerHairlinePx * 0.5f),
                size = Size(size.width - outerHairlinePx, size.height - outerHairlinePx),
                cornerRadius = CornerRadius(outerR, outerR),
                style = Stroke(width = outerHairlinePx)
            )

            val whiteRimColor = if (isDark) {
                Color.White.copy(alpha = 0.22f)
            } else {
                Color.White.copy(alpha = 0.94f)
            }
            val rimStrokePx = 1.6.dp.toPx()
            val inset = outerHairlinePx + rimStrokePx * 0.5f
            drawRoundRect(
                color = whiteRimColor,
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2f, size.height - inset * 2f),
                cornerRadius = CornerRadius(outerR - outerHairlinePx, outerR - outerHairlinePx),
                style = Stroke(width = rimStrokePx)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in icons.indices) {
                val selected = activeTabIndex == i
                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val itemScale by animateFloatAsState(
                    targetValue = if (isPressed) 0.92f else 1.0f,
                    animationSpec = spring(stiffness = Spring.StiffnessMedium),
                    label = "LiquidItemScale"
                )
                val tint = if (selected) primaryColor else unselectedColor

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .graphicsLayer {
                            scaleX = itemScale
                            scaleY = itemScale
                        }
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { onSelect(i) }
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = icons[i],
                        contentDescription = labels[i],
                        tint = tint,
                        modifier = Modifier.size(if (iconOnly) 24.dp else 23.dp)
                    )
                    if (!iconOnly) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = labels[i],
                            fontSize = 11.5.sp,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            color = tint
                        )
                    }
                }
            }
        }
    }
}

/**
 * 标准非悬浮底栏的「仅图标 (BottomBarMode.IconOnly)」模式
 */
@Composable
private fun StandardIconOnlyBottomBar(
    selectedIndex: Int,
    icons: List<ImageVector>,
    labels: List<String>,
    onSelect: (Int) -> Unit
) {
    val navInsetsPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val barBg = MiuixTheme.colorScheme.surface
    val primaryColor = MiuixTheme.colorScheme.primary
    val unselectedColor = MiuixTheme.colorScheme.onSurfaceVariantSummary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(barBg)
            .padding(bottom = navInsetsPadding, top = 6.dp)
            .height(50.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in icons.indices) {
            val selected = selectedIndex == i
            val tint = if (selected) primaryColor else unselectedColor
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(i) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icons[i],
                    contentDescription = labels[i],
                    tint = tint,
                    modifier = Modifier.size(25.dp)
                )
            }
        }
    }
}

@Composable
private fun Modifier.topBarFrostedBlur(
    enabled: Boolean,
    topBarHeight: Dp,
    surfaceColor: Color,
    isDark: Boolean,
    progressive: Boolean
): Modifier {
    if (!enabled) return this
    val shaders = remember {
        runCatching {
            arrayOf(
                RuntimeShader(TOP_BAR_BLUR_H_AGSL),
                RuntimeShader(TOP_BAR_BLUR_V_TINT_AGSL)
            )
        }.getOrNull()
    }
    if (shaders == null) return this

    val progFlag = if (progressive) 1f else 0f

    return this.graphicsLayer {
        val topPx = topBarHeight.toPx()
        if (topPx > 0f) {
            compositingStrategy = CompositingStrategy.Offscreen

            val step = 5.5f
            val tintAlpha = if (isDark) 0.78f else 0.76f
            val sH = shaders[0]
            val sV = shaders[1]

            sH.setFloatUniform("topBarBottom", topPx)
            sH.setFloatUniform("stepSize", step)
            sH.setFloatUniform("progressive", progFlag)

            sV.setFloatUniform("topBarBottom", topPx)
            sV.setFloatUniform("stepSize", step)
            sV.setFloatUniform("progressive", progFlag)
            sV.setFloatUniform(
                "surfaceTint",
                surfaceColor.red,
                surfaceColor.green,
                surfaceColor.blue,
                tintAlpha
            )

            val pH = AndroidRenderEffect.createRuntimeShaderEffect(sH, "content")
            val pV = AndroidRenderEffect.createRuntimeShaderEffect(sV, "content")
            renderEffect = AndroidRenderEffect.createChainEffect(pV, pH).asComposeRenderEffect()
        }
    }
}

@Composable
private fun Modifier.bottomCapsuleFrostedBlur(
    enabled: Boolean,
    bottomRect: Rect,
    isLiquidGlass: Boolean,
    surfaceColor: Color,
    isDark: Boolean
): Modifier {
    if (!enabled) return this
    val shaders = remember {
        runCatching {
            arrayOf(
                RuntimeShader(BOTTOM_CAPSULE_BLUR_H_AGSL),
                RuntimeShader(BOTTOM_CAPSULE_BLUR_V_AGSL)
            )
        }.getOrNull()
    } ?: return this

    return this.graphicsLayer {
        if (bottomRect.width > 8f && bottomRect.height > 8f) {
            compositingStrategy = CompositingStrategy.Offscreen
            val stepPx = if (isLiquidGlass) 2.2f else 5.2f
            val tintAlpha = if (isLiquidGlass) {
                if (isDark) 0.32f else 0.24f
            } else {
                if (isDark) 0.76f else 0.82f
            }
            val sH = shaders[0]
            val sV = shaders[1]

            sH.setFloatUniform("bLeft", bottomRect.left)
            sH.setFloatUniform("bTop", bottomRect.top)
            sH.setFloatUniform("bRight", bottomRect.right)
            sH.setFloatUniform("bBottom", bottomRect.bottom)
            sH.setFloatUniform("stepSize", stepPx)

            sV.setFloatUniform("bLeft", bottomRect.left)
            sV.setFloatUniform("bTop", bottomRect.top)
            sV.setFloatUniform("bRight", bottomRect.right)
            sV.setFloatUniform("bBottom", bottomRect.bottom)
            sV.setFloatUniform("stepSize", stepPx)
            sV.setFloatUniform("isLiquidGlass", if (isLiquidGlass) 1f else 0f)
            sV.setFloatUniform(
                "surfaceTint",
                surfaceColor.red,
                surfaceColor.green,
                surfaceColor.blue,
                tintAlpha
            )

            val pH = AndroidRenderEffect.createRuntimeShaderEffect(sH, "content")
            val pV = AndroidRenderEffect.createRuntimeShaderEffect(sV, "content")
            renderEffect = AndroidRenderEffect
                .createChainEffect(pV, pH)
                .asComposeRenderEffect()
        }
    }
}

@Composable
private fun TopMenuActionButton(
    active: Boolean,
    isDark: Boolean,
    contentDescription: String,
    onClick: () -> Unit
) {
    val activeCircleColor = if (isDark) {
        Color.White.copy(alpha = 0.14f)
    } else {
        Color.Black.copy(alpha = 0.08f)
    }

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (active) activeCircleColor else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = IconMoreCircle,
                contentDescription = contentDescription,
                tint = MiuixTheme.colorScheme.onSurface
            )
        }
    }
}

package com.sidesand.millet.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.sidesand.millet.data.MilletConfigSnapshot
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

val LocalAppDarkMode = compositionLocalOf { false }
val LocalAppMonetEnabled = compositionLocalOf { false }
val LocalAppBlurEnabled = compositionLocalOf { true }

object ThemeConfig {
    val COLOR_MODE_LABELS = listOf(
        "跟随系统",
        "浅色",
        "深色"
    )

    val TOP_BAR_BLUR_LABELS = listOf(
        "高斯",
        "渐进"
    )

    val TOP_BAR_BLUR_SUMMARIES = listOf(
        "顶栏整体均匀模糊",
        "顶栏边缘向内渐变模糊"
    )

    val FLOATING_BOTTOM_BAR_STYLE_LABELS = listOf(
        "Miuix",
        "iOS 液态玻璃"
    )

    val BOTTOM_BAR_MODE_LABELS = listOf(
        "图标与文字",
        "仅图标"
    )

    val UI_SCALE_STEPS = listOf(80, 90, 100, 110)

    fun resolveIsDark(colorMode: Int, systemDark: Boolean): Boolean {
        return when (colorMode) {
            1 -> false
            2 -> true
            else -> systemDark
        }
    }

    fun resolveColorSchemeMode(colorMode: Int, monetEnabled: Boolean): ColorSchemeMode {
        return if (monetEnabled) {
            when (colorMode) {
                1 -> ColorSchemeMode.MonetLight
                2 -> ColorSchemeMode.MonetDark
                else -> ColorSchemeMode.MonetSystem
            }
        } else {
            when (colorMode) {
                1 -> ColorSchemeMode.Light
                2 -> ColorSchemeMode.Dark
                else -> ColorSchemeMode.System
            }
        }
    }
}

@Composable
fun SmilletNextTheme(
    config: MilletConfigSnapshot = MilletConfigSnapshot(),
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = ThemeConfig.resolveIsDark(config.themeColorMode, systemDark)
    val schemeMode = ThemeConfig.resolveColorSchemeMode(config.themeColorMode, config.monetEnabled)

    val baseDensity = LocalDensity.current
    val scaleFactor = (config.uiScale.coerceIn(80, 110)) / 100f
    val scaledDensity = remember(baseDensity, scaleFactor) {
        Density(
            density = baseDensity.density * scaleFactor,
            fontScale = baseDensity.fontScale
        )
    }

    val controller = remember(schemeMode) {
        ThemeController(schemeMode)
    }

    CompositionLocalProvider(
        LocalAppDarkMode provides isDark,
        LocalAppMonetEnabled provides config.monetEnabled,
        LocalAppBlurEnabled provides config.blurEnabled,
        LocalDensity provides scaledDensity
    ) {
        MiuixTheme(
            controller = controller,
            content = content
        )
    }
}

package com.sidesand.millet.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sidesand.millet.ui.theme.LocalAppDarkMode
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MiuixTopPopupMenu(
    visible: Boolean,
    onDismiss: () -> Unit,
    topOffset: Dp = 56.dp,
    width: Dp = 184.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = LocalAppDarkMode.current
    val dimAlpha = if (isDark) 0.42f else 0.20f
    val menuBg = if (isDark) Color(0xFF242426) else Color(0xFFFFFFFF)
    val popupShape = RoundedCornerShape(18.dp)

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 150)),
        exit = fadeOut(animationSpec = tween(durationMillis = 130))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = dimAlpha))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopEnd
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = scaleIn(
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                initialScale = 0.86f,
                transformOrigin = TransformOrigin(1f, 0f)
            ) + fadeIn(animationSpec = tween(durationMillis = 140)),
            exit = scaleOut(
                animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
                targetScale = 0.86f,
                transformOrigin = TransformOrigin(1f, 0f)
            ) + fadeOut(animationSpec = tween(durationMillis = 110)),
            modifier = Modifier.padding(top = topOffset, end = 14.dp)
        ) {
            Column(
                modifier = Modifier
                    .width(width)
                    .clip(popupShape)
                    .background(menuBg)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .padding(vertical = 6.dp),
                content = content
            )
        }
    }
}

@Composable
fun MiuixPopupOptionRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val textColor = if (selected) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = textColor
        )
        if (selected) {
            MiuixPopupCheckIcon(color = MiuixTheme.colorScheme.primary)
        }
    }
}

@Composable
fun MiuixPopupActionRow(
    text: String,
    textColor: Color = MiuixTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = textColor
        )
    }
}

@Composable
fun MiuixPopupCheckIcon(color: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.16f, h * 0.52f)
            lineTo(w * 0.42f, h * 0.78f)
            lineTo(w * 0.86f, h * 0.24f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = 2.0.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

package com.lelemusic.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// 平台主题色（与 model/Platform.seedColor 保持一致，供 Compose 侧直接使用）
// ---------------------------------------------------------------------------

val QqGreen = Color(0xFF31C27C)
val NeteaseRed = Color(0xFFC20C0C)
val KugouBlue = Color(0xFF0092FF)

/** 试听片段标记的橙色胶囊（架构文档 §2.11 明确要求） */
val TrialOrange = Color(0xFFFF8A3D)

/** 试听胶囊上的文字色，保证在橙色底上对比度达标 */
val TrialOrangeOn = Color(0xFF3B1D00)

// ---------------------------------------------------------------------------
// Material3 配色方案
// ---------------------------------------------------------------------------

val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1DB954),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC8F0D6),
    onPrimaryContainer = Color(0xFF00351A),
    secondary = Color(0xFF4F6354),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD2E5D6),
    onSecondaryContainer = Color(0xFF0D1F14),
    tertiary = Color(0xFF3B6472),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF7FAF8),
    onBackground = Color(0xFF191C1A),
    surface = Color(0xFFF7FAF8),
    onSurface = Color(0xFF191C1A),
    surfaceVariant = Color(0xFFDCE5DD),
    onSurfaceVariant = Color(0xFF414941),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    outline = Color(0xFF707970)
)

val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF6BDB9C),
    onPrimary = Color(0xFF00391C),
    primaryContainer = Color(0xFF00532B),
    onPrimaryContainer = Color(0xFFC8F0D6),
    secondary = Color(0xFFB7C9BA),
    onSecondary = Color(0xFF223428),
    secondaryContainer = Color(0xFF384B3E),
    onSecondaryContainer = Color(0xFFD2E5D6),
    tertiary = Color(0xFFA3CBD9),
    onTertiary = Color(0xFF07333F),
    background = Color(0xFF101412),
    onBackground = Color(0xFFE1E3E0),
    surface = Color(0xFF101412),
    onSurface = Color(0xFFE1E3E0),
    surfaceVariant = Color(0xFF414941),
    onSurfaceVariant = Color(0xFFC0C9C1),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    outline = Color(0xFF8A938C)
)

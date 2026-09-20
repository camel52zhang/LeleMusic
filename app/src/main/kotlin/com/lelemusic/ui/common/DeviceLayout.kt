package com.lelemusic.ui.common

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 平板/大屏适配（2026-09-12）：当前窗口是否达到 Expanded 档。
 *
 * 判定标准：`screenWidthDp >= 840`（对齐 Material WindowSizeClass 的 Expanded 档）。
 * 小米平板 5 Pro：横屏约 1483dp、竖屏约 927dp，两个方向都落在 Expanded。
 *
 * - MainActivity 在 `setContent` 时按 `resources.configuration` 计算并 provide；
 * - 旋转会重建 Activity，重建时重新计算——不需要额外监听 Configuration；
 * - 手机（锁竖屏）恒为 false，所有原有竖屏布局零改动。
 */
val LocalExpandedScreen = staticCompositionLocalOf { false }

/** Expanded 档最小窗口宽度（dp） */
const val EXPANDED_MIN_WIDTH_DP = 840

/** 手机/平板分界：最短边小于该值锁竖屏（Material 的 Compact 上限） */
const val PHONE_MAX_SMALLEST_WIDTH_DP = 600

/**
 * 当前窗口宽度（dp），MainActivity 在 setContent 时按 `resources.configuration` 提供，
 * 旋转重建后刷新。限宽策略（≥600dp 限宽居中）用它判断。
 */
val LocalWindowWidthDp = compositionLocalOf { 0 }

package com.lelemusic.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 骨架屏（PRD 5.1：加载中显示骨架屏，而不是空白）。
 *
 * 实现思路：**只做 alpha 呼吸动画，不做渐变扫光**。
 * 扫光需要拿到组件实际像素宽度才能构造合适的 `Brush.linearGradient`，
 * 在 `LazyColumn` 的每一行里都得额外做一次 `onSizeChanged`，代码量与出错面都翻倍；
 * 而 alpha 呼吸在视觉上同样传达「正在加载」，且不依赖任何尺寸测量。
 */

/** 呼吸动画单程时长（ms） */
private const val SHIMMER_DURATION_MS = 900

/**
 * 骨架闪烁背景。
 *
 * 颜色直接取 `colorScheme.onSurface`，深色模式自动跟随主题。
 */
@Composable
fun Modifier.shimmer(alphaRange: ClosedFloatingPointRange<Float> = 0.12f..0.34f): Modifier {
    val transition = rememberInfiniteTransition()
    val alpha by transition.animateFloat(
        initialValue = alphaRange.start,
        targetValue = alphaRange.endInclusive,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SHIMMER_DURATION_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    return this.background(color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
}

/** 单个骨架色块 */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 4.dp
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .shimmer()
    )
}

/** 一行歌曲骨架：排名 + 封面 + 两行文字 + 右侧时长（高度与 `SongRow` 对齐） */
@Composable
fun SongRowSkeleton(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SkeletonBlock(
            modifier = Modifier.size(width = 24.dp, height = 16.dp),
            cornerRadius = 2.dp
        )
        Spacer(modifier = Modifier.width(8.dp))
        SkeletonBlock(
            modifier = Modifier.size(56.dp),
            cornerRadius = 8.dp
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.62f)
                    .height(14.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.40f)
                    .height(11.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        SkeletonBlock(
            modifier = Modifier.size(width = 32.dp, height = 11.dp)
        )
    }
}

/**
 * 榜单骨架列表。
 *
 * @param count 骨架行数；取 12 是首屏大致可见的行数，不必与 Top50 一致
 */
@Composable
fun ChartSkeletonList(
    modifier: Modifier = Modifier,
    count: Int = 12
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        userScrollEnabled = false
    ) {
        items(count = count) { index ->
            SongRowSkeleton()
            if (index < count - 1) {
                Divider(
                    modifier = Modifier.padding(start = 120.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                )
            }
        }
    }
}

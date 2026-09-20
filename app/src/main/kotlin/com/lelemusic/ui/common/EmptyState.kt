package com.lelemusic.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 空态。
 *
 * 与错误态分开是刻意的：空榜是**正常的业务结果**（接口返回了 0 首），
 * 不该用红色的警告图标吓用户，因此这里用中性的占位图形 + 灰色文案。
 *
 * @param message 主文案
 * @param hint    次要说明；空串时不显示
 */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    hint: String = ""
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Column(
            modifier = Modifier.size(width = 96.dp, height = 60.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PlaceholderLine(endPadding = 0.dp)
            PlaceholderLine(endPadding = 28.dp)
            PlaceholderLine(endPadding = 12.dp)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        if (hint.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 占位横线：像「空荡荡的播放列表」。
 *
 * 不用 emoji（不同厂商渲染差异大），也不用 Material Icon（`material-icons-core` 里没有合适的空态图标）。
 */
@Composable
private fun PlaceholderLine(endPadding: Dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = endPadding)
            .height(8.dp)
            .background(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f),
                shape = RoundedCornerShape(4.dp)
            )
    )
}

package com.lelemusic.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lelemusic.R
import com.lelemusic.core.common.AppError

/**
 * [AppError] → 用户可读文案。
 *
 * 只按枚举分支，**不解析 message 文本**（架构文档 §7.3 铁律 2：文本是给人看的，不是给程序分支的）。
 * `when` 对密封类穷尽，新增错误枚举时编译器会直接报缺失分支。
 */
@Composable
fun appErrorText(error: AppError): String = when (error) {
    AppError.Network -> stringResource(id = R.string.error_network)
    AppError.Timeout -> stringResource(id = R.string.error_timeout)
    AppError.Parse -> stringResource(id = R.string.error_parse)
    AppError.PlaySourceUnavailable -> stringResource(id = R.string.error_no_source)
    AppError.EmptyData -> stringResource(id = R.string.error_empty)
    AppError.Unknown -> stringResource(id = R.string.error_unknown)
}

/**
 * 通用错误态（榜单加载失败、取链失败等场景复用）。
 *
 * @param message  已经本地化好的主文案；空串时回落到 [fallbackMessage]
 * @param onRetry  「重试」回调；为 null 时隐藏重试按钮
 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    title: String = "",
    onRetry: (() -> Unit)? = null,
    fallbackMessage: String = ""
) {
    val resolvedTitle = title.ifBlank { stringResource(id = R.string.label_error_title) }
    val resolvedMessage = message.ifBlank { fallbackMessage }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = resolvedTitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        if (resolvedMessage.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = resolvedMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRetry) {
                Text(text = stringResource(id = R.string.action_retry))
            }
        }
    }
}

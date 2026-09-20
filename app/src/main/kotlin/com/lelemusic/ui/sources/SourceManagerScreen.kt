package com.lelemusic.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lelemusic.R
import com.lelemusic.core.di.AppGraph
import com.lelemusic.data.plugin.PluginSource
import com.lelemusic.ui.common.glyphContentDescription

/**
 * 音源管理页（入口：音源自检台顶栏「音源」）。
 *
 * 这一版只做「加进来 + 看清楚」：贴清单 → 解析入库 → 抓取元数据（脚本大小 / 契约类型 / 能否抓到）。
 * 真正的执行（用 JS 引擎跑插件搜索/取链）是下一轮的事，但数据层已经按 `runtime` 分派好位置。
 */
@Composable
fun SourceManagerScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val appContext = LocalContext.current.applicationContext
    val factory = androidx.compose.runtime.remember(appContext) {
        sourceManagerViewModelFactory(repository = AppGraph.pluginSourceRepository)
    }
    val viewModel: SourceManagerViewModel = viewModel(factory = factory)
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(color = MaterialTheme.colorScheme.surface)
    ) {
        TopBar(
            onBack = onBack,
            refreshing = state.refreshing,
            onRefresh = { viewModel.refreshAll() }
        )

        AddPanel(
            text = state.input,
            enabled = !state.adding,
            onTextChange = { viewModel.onInputChange(it) },
            onAdd = { viewModel.addFromInput() }
        )

        if (state.refreshing) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
            )
        }

        if (state.message.isNotBlank()) {
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        Text(
            text = stringResource(id = R.string.sources_section_list, state.sources.size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
        )

        if (state.sources.isEmpty()) {
            EmptyHint()
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(items = state.sources, key = { it.id }) { source ->
                    SourceCard(
                        source = source,
                        onToggle = { viewModel.toggle(source.id, it) },
                        onRefresh = { viewModel.refreshOne(source.id) },
                        onRemove = { viewModel.remove(source.id) },
                        onExpand = { viewModel.expand(source.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    onBack: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(48.dp)
                .glyphContentDescription(stringResource(id = R.string.cd_back))
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = stringResource(id = R.string.sources_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRefresh, enabled = !refreshing) {
            Text(text = stringResource(id = R.string.action_refresh))
        }
    }
}

@Composable
private fun AddPanel(
    text: String,
    enabled: Boolean,
    onTextChange: (String) -> Unit,
    onAdd: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = stringResource(id = R.string.sources_add_hint)) },
            maxLines = 6,
            enabled = enabled
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onAdd, enabled = enabled) {
                Text(text = stringResource(id = R.string.sources_add))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(id = R.string.sources_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SourceCard(
    source: PluginSource,
    onToggle: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    onExpand: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = source.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!source.version.isNullOrBlank()) {
                        Text(
                            text = source.version,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = source.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.glyphContentDescription(
                        stringResource(id = R.string.cd_toggle, source.name)
                    )
                )
            }

            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = source.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(text = typeText(source))
                if (source.type != PluginSource.TYPE_REGISTRY) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Badge(text = runtimeText(source.runtime))
                }
                if (source.bytes > 0L) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Badge(text = stringResource(id = R.string.sources_size, source.bytes / 1024L))
                }
                if (source.type == PluginSource.TYPE_REGISTRY && source.childCount > 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Badge(text = stringResource(id = R.string.sources_children, source.childCount))
                }
            }

            if (!source.lastError.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = source.lastError,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row {
                if (source.type == PluginSource.TYPE_REGISTRY) {
                    TextButton(onClick = onExpand) {
                        Text(text = stringResource(id = R.string.action_expand))
                    }
                }
                TextButton(onClick = onRefresh) {
                    Text(text = stringResource(id = R.string.action_refresh))
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onRemove) {
                    Text(
                        text = stringResource(id = R.string.action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun typeText(source: PluginSource): String = when (source.type) {
    PluginSource.TYPE_REGISTRY -> stringResource(id = R.string.sources_type_registry)
    else -> stringResource(id = R.string.sources_type_single)
}

@Composable
private fun runtimeText(runtime: String): String = when (runtime) {
    PluginSource.RUNTIME_LX -> stringResource(id = R.string.sources_runtime_lx)
    PluginSource.RUNTIME_MUSICFREE -> stringResource(id = R.string.sources_runtime_musicfree)
    else -> stringResource(id = R.string.sources_runtime_unknown)
}

@Composable
private fun EmptyHint() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(id = R.string.sources_empty),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(id = R.string.sources_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

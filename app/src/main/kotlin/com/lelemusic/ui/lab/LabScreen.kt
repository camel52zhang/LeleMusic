package com.lelemusic.ui.lab

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lelemusic.R
import com.lelemusic.core.di.AppGraph
import com.lelemusic.data.source.ResolverEndpoints
import com.lelemusic.ui.common.glyphContentDescription
import com.lelemusic.ui.theme.TrialOrange

/**
 * 音源自检台（架构文档 §4.3 / T05）。
 *
 * **这是整个项目的验收工具**——每个平台到底通不通，只有真机跑一次这一屏才能定论。
 * 因此布局上刻意把「结论」放在最上面：**看一眼就知道哪些平台是好的**。
 *
 * 结构：结论横幅 → 运行按钮 + 进度 → 运行期开关 → 逐行结果表（平台/策略/结果/错误码/
 * 声明时长/实测时长/差值/判定/耗时/URL）→ 怎么看结果的说明。
 *
 * @param onBack 返回榜单页
 * @param onOpenSources 打开音源管理页（用户自行添加音源）
 */
@Composable
fun LabScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenSources: () -> Unit
) {
    val appContext = LocalContext.current.applicationContext
    val factory = remember(appContext) {
        labViewModelFactory(
            appContext = appContext,
            chartRepository = AppGraph.chartRepository,
            registry = AppGraph.resolverRegistry,
            probe = AppGraph.probe,
            settings = AppGraph.settings
        )
    }
    val viewModel: LabViewModel = viewModel(factory = factory)
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(color = MaterialTheme.colorScheme.surface)
    ) {
        TopBar(
            onBack = onBack,
            onCopy = { viewModel.copyLog() },
            onOpenSources = onOpenSources
        )

        HeadlineBanner(state = state)

        RunRow(
            running = state.running,
            progress = state.progress,
            rowCount = state.rows.size,
            onRun = { viewModel.runCheck() },
            onCancel = { viewModel.cancelCheck() },
            onCopy = { viewModel.copyLog() }
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                SectionTitle(text = stringResource(id = R.string.lab_section_display))
            }
            item {
                DisplayPrefsCard()
            }
            item {
                SectionTitle(text = stringResource(id = R.string.lab_section_switches))
            }
            item {
                SwitchCard(viewModel = viewModel, state = state)
            }
            item {
                SectionTitle(
                    text = stringResource(id = R.string.lab_section_results, state.rows.size)
                )
            }
            if (state.rows.isEmpty()) {
                item {
                    Text(
                        text = stringResource(id = R.string.lab_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
                    )
                }
            } else {
                items(items = state.rows) { row ->
                    LabRowCard(row = row)
                }
            }
            item {
                HintFooter()
            }
            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 顶部返回条
// ---------------------------------------------------------------------------

@Composable
private fun TopBar(
    onBack: () -> Unit,
    onCopy: () -> Unit,
    onOpenSources: () -> Unit
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
            text = stringResource(id = R.string.lab_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onOpenSources) {
            Text(text = stringResource(id = R.string.action_sources))
        }
        TextButton(onClick = onCopy) {
            Text(text = stringResource(id = R.string.lab_copy_log))
        }
    }
}

// ---------------------------------------------------------------------------
// 结论横幅：平台判定汇总 + 有无失败
// ---------------------------------------------------------------------------

@Composable
private fun HeadlineBanner(state: LabUiState) {
    val containerColor: Color
    val contentColor: Color
    when (state.ok) {
        true -> {
            containerColor = MaterialTheme.colorScheme.primaryContainer
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        }

        false -> {
            containerColor = MaterialTheme.colorScheme.errorContainer
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        }

        null -> {
            containerColor = MaterialTheme.colorScheme.surfaceVariant
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }

    val text = if (state.headline.isBlank()) {
        stringResource(id = R.string.lab_never_run)
    } else {
        state.headline
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        contentColor = contentColor
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(id = R.string.lab_conclusion),
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 运行按钮 + 进度
// ---------------------------------------------------------------------------

@Composable
private fun RunRow(
    running: Boolean,
    progress: String,
    rowCount: Int,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onCopy: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onRun,
                enabled = !running,
                modifier = Modifier.glyphContentDescription(
                    stringResource(id = R.string.cd_run_check)
                )
            ) {
                Text(text = stringResource(id = R.string.lab_run))
            }
            if (running) {
                Spacer(modifier = Modifier.width(12.dp))
                OutlinedButton(onClick = onCancel) {
                    Text(text = stringResource(id = R.string.lab_cancel))
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = if (running) {
                    stringResource(id = R.string.lab_running, progress)
                } else if (rowCount > 0) {
                    stringResource(id = R.string.lab_done, rowCount.toString())
                } else {
                    ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        if (running) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = progressFraction(progress),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
            )
        }
        if (!running && rowCount > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(onClick = onCopy) {
                Text(text = stringResource(id = R.string.lab_copy_log))
            }
        }
    }
}

/** 把 "3/12" 解析成 0..1 的进度；解析不出来退回一个「在动」的近似值 */
private fun progressFraction(progress: String): Float {
    val parts = progress.split('/')
    if (parts.size != 2) return 0.5f
    val done = parts[0].trim().toFloatOrNull() ?: return 0.5f
    val total = parts[1].trim().toFloatOrNull() ?: return 0.5f
    if (total <= 0f) return 0.5f
    return (done / total).coerceIn(0f, 1f)
}

// ---------------------------------------------------------------------------
// 开关区
// ---------------------------------------------------------------------------

/**
 * 显示设置卡（2026-09-12）：目前只有「大屏内容铺满到边」一项。
 * 平板默认关闭（限宽居中观感最佳）；车机横屏建议打开，内容满屏不浪费两侧空间。
 */
@Composable
private fun DisplayPrefsCard() {
    val fillWide by AppGraph.displayPrefs.fillWideScreen.collectAsState()
    val autoPlay by AppGraph.displayPrefs.autoPlayOnLaunch.collectAsState()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        SwitchRow(
            title = stringResource(id = R.string.lab_fill_wide_title),
            subtitle = stringResource(id = R.string.lab_fill_wide_subtitle),
            checked = fillWide,
            onCheckedChange = { enabled ->
                AppGraph.displayPrefs.setFillWideScreen(AppGraph.settings, enabled)
            },
            onReset = { }
        )
        SwitchRow(
            title = stringResource(id = R.string.lab_auto_play_title),
            subtitle = stringResource(id = R.string.lab_auto_play_subtitle),
            checked = autoPlay,
            onCheckedChange = { enabled ->
                AppGraph.displayPrefs.setAutoPlayOnLaunch(AppGraph.settings, enabled)
            },
            onReset = { }
        )
    }
}

@Composable
private fun SwitchCard(viewModel: LabViewModel, state: LabUiState) {    val toggles = state.toggles
    val appContext = LocalContext.current.applicationContext
    // 「编辑音源地址」对话框当前编辑的策略（null = 不显示）
    var editingStrategy by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            for (resolver in toggles.resolvers) {
                SwitchRow(
                    title = resolver.strategyId,
                    subtitle = stringResource(
                        id = R.string.lab_switch_resolver_subtitle,
                        resolver.platform.displayName,
                        resolver.priority
                    ),
                    checked = resolver.enabled,
                    onCheckedChange = { enabled ->
                        viewModel.setResolverEnabled(resolver.strategyId, enabled)
                    },
                    // 2026-09-12：每个音源策略加「编辑地址」入口（改反代/镜像立即生效）
                    showEdit = ResolverEndpoints.defaultOf(resolver.strategyId) != null,
                    onEdit = { editingStrategy = resolver.strategyId },
                    showReset = resolver.overridden,
                    onReset = { viewModel.resetResolver(resolver.strategyId) }
                )
            }
        }
    }

    editingStrategy?.let { strategyId ->
        EndpointEditDialog(
            strategyId = strategyId,
            currentUrl = viewModel.currentEndpoint(strategyId).orEmpty(),
            isOverridden = viewModel.isEndpointOverridden(strategyId),
            onDismiss = { editingStrategy = null },
            onSave = { url ->
                if (viewModel.setEndpoint(strategyId, url)) {
                    editingStrategy = null
                } else {
                    Toast.makeText(
                        appContext,
                        R.string.lab_endpoint_invalid,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onReset = {
                viewModel.resetEndpoint(strategyId)
                editingStrategy = null
            }
        )
    }
}

/**
 * 「编辑音源地址」对话框：展示当前生效地址（覆盖 > 默认），可改、可恢复默认。
 * 保存后下一次请求立即生效（resolver 每次请求前实时读 ResolverEndpoints）。
 */
@Composable
private fun EndpointEditDialog(
    strategyId: String,
    currentUrl: String,
    isOverridden: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onReset: () -> Unit
) {
    var value by remember(strategyId) { mutableStateOf(currentUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(id = R.string.lab_endpoint_dialog_title, strategyId),
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column {
                if (isOverridden) {
                    Text(
                        text = stringResource(id = R.string.lab_endpoint_customized),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text(stringResource(id = R.string.lab_endpoint_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(id = R.string.lab_endpoint_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(value) }) {
                Text(stringResource(id = R.string.lab_endpoint_save))
            }
        },
        dismissButton = {
            Row {
                if (isOverridden) {
                    TextButton(onClick = onReset) {
                        Text(stringResource(id = R.string.lab_endpoint_reset))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(id = R.string.lab_endpoint_cancel))
                }
            }
        }
    )
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    showEdit: Boolean = false,
    onEdit: () -> Unit = {},
    showReset: Boolean = false,
    onReset: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        if (showEdit) {
            IconButton(
                onClick = onEdit,
                modifier = Modifier.glyphContentDescription(
                    stringResource(id = R.string.lab_endpoint_edit_cd, title)
                )
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        if (showReset) {
            TextButton(onClick = onReset) {
                Text(text = stringResource(id = R.string.lab_reset))
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.glyphContentDescription(
                stringResource(id = R.string.cd_toggle, title)
            )
        )
    }
}

// ---------------------------------------------------------------------------
// 结果行
// ---------------------------------------------------------------------------

@Composable
private fun LabRowCard(row: LabRow) {
    val verdictColor = verdictColor(row.verdict)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 第一行：平台 · 策略           判定
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        id = R.string.lab_row_header,
                        row.platform.displayName,
                        row.strategyId
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = verdictColor.copy(alpha = 0.16f),
                    contentColor = verdictColor
                ) {
                    Text(
                        text = verdictText(row.verdict),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            if (row.songTitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${stringResource(id = R.string.label_rank, row.sampleRank)}  ${row.songTitle}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (row.errorCode.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(id = R.string.lab_error_code, row.errorCode),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // 播放探针：把「链有效」和「真的能播」分开显示。
            // 网易云的典型症状正是这里——取链 OK、时长也测得出，但真机一点播就报网络异常，
            // 不单独显示的话这一行会是全绿的，看不出问题。
            if (row.playOk != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (row.playOk == true) {
                        stringResource(id = R.string.lab_play_ok)
                    } else {
                        stringResource(id = R.string.lab_play_error, row.playError)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (row.playOk == true) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 时长三件套：声明 / 实测 / 差值 —— 判定全曲 vs 试听片段的核心证据
            val declareText = stringResource(id = R.string.lab_declare, row.declareMs / 1000L)
            val realText = if (row.realMs == null) {
                stringResource(id = R.string.lab_real_unknown)
            } else {
                stringResource(id = R.string.lab_real, row.realMs / 1000L)
            }
            val diffText = if (row.diffMs == null) {
                stringResource(id = R.string.lab_diff_unknown)
            } else {
                stringResource(id = R.string.lab_diff, row.diffMs / 1000L)
            }
            Text(
                text = "$declareText  ·  $realText  ·  $diffText",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(2.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(id = R.string.lab_elapsed, row.elapsedMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val quality = row.quality
                if (!quality.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(id = R.string.lab_quality, quality),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (row.url.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = row.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (row.message.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = row.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun verdictText(verdict: LabVerdict): String = when (verdict) {
    LabVerdict.FULL -> stringResource(id = R.string.lab_verdict_full)
    LabVerdict.TRIAL -> stringResource(id = R.string.lab_verdict_trial)
    LabVerdict.FAILED -> stringResource(id = R.string.lab_verdict_failed)
    LabVerdict.UNKNOWN -> stringResource(id = R.string.lab_verdict_unknown)
}

/**
 * 判定色。
 *
 * 用固定的中绿 / 橙色而不是 `colorScheme`：判定色是**语义色**（成功 / 警告 / 失败），
 * 跟随主题会让它和「试听片段」胶囊的橙色对不上，也会在动态取色下失去辨识度。
 */
@Composable
private fun verdictColor(verdict: LabVerdict): Color = when (verdict) {
    LabVerdict.FULL -> Color(0xFF2E7D32)
    LabVerdict.TRIAL -> TrialOrange
    LabVerdict.FAILED -> MaterialTheme.colorScheme.error
    LabVerdict.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

// ---------------------------------------------------------------------------
// 说明
// ---------------------------------------------------------------------------

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun HintFooter() {
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
                text = stringResource(id = R.string.lab_hint_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(id = R.string.lab_hint_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

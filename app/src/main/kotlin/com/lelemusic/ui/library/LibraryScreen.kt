package com.lelemusic.ui.library

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lelemusic.R
import com.lelemusic.data.local.LocalScanner
import com.lelemusic.model.Playlist
import com.lelemusic.model.Playlist.Companion.ORIGIN_IMPORT
import com.lelemusic.model.Playlist.Companion.ORIGIN_MOUNT
import com.lelemusic.model.Song
import com.lelemusic.ui.common.EmptyState
import com.lelemusic.ui.common.NoteGlyph
import kotlinx.coroutines.launch

/**
 * 「我的歌单」列表页。
 *
 * 每个歌单一张卡片：点卡片进详情；右上角「⋯」菜单可重命名 / 删除；
 * 顶栏 + 新建歌单。歌单删除只移走「引用」，歌单内的歌不会消失。
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenPlaylist: (String) -> Unit
) {
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val playlists by viewModel.playlists.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    // 本地导入 / 文件夹挂载后待命名的歌曲（2026-09-12 起：导入即建「本地歌单」，
    // 命名后存入我的歌单（带「导入」/「挂载」徽标），并同时挂在首页「本地音乐」Tab 下一级）
    var pendingImport by remember { mutableStateOf<List<Song>?>(null) }
    // 待创建本地歌单的来源标记（导入 = ORIGIN_IMPORT / 挂载 = ORIGIN_MOUNT）
    var pendingOrigin by remember { mutableStateOf(ORIGIN_IMPORT) }
    // 挂载空扫诊断：Toast 太短装不下完整统计，弹对话框 + 一键复制给开发者定位
    var scanDiagnostic by remember { mutableStateOf<String?>(null) }

    // ---- 导入本地音乐（文件多选，audio/*）----
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        for (uri in uris) {
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        scope.launch {
            val songs = LocalScanner.readPickedAudios(appContext, uris)
            if (songs.isEmpty()) {
                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.msg_import_empty),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                pendingOrigin = ORIGIN_IMPORT
                pendingImport = songs
            }
        }
    }

    // ---- 挂载本地文件夹（SAF 树目录，权限持久化）----
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        val granted = runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            true
        }.getOrDefault(false)
        if (!granted) {
            Toast.makeText(
                appContext,
                appContext.getString(R.string.msg_folder_permission_denied),
                Toast.LENGTH_SHORT
            ).show()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            when (val result = LocalScanner.scanTree(appContext, treeUri)) {
                is LocalScanner.ScanResult.Success -> {
                    pendingOrigin = ORIGIN_MOUNT
                    pendingImport = result.songs
                }
                is LocalScanner.ScanResult.Error -> {
                    if (result.code == "no_audio") {
                        // 诊断放到对话框里完整展示（Toast 长度不够）
                        val base = appContext.getString(R.string.msg_no_music_in_folder)
                        val detail = result.message.take(800)
                        scanDiagnostic = if (detail.isBlank()) base else "$base\n\n$detail"
                    } else {
                        Toast.makeText(
                            appContext,
                            appContext.getString(R.string.msg_scan_error, result.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        LibraryTopBar(
            title = stringResource(id = R.string.library_title),
            onBack = onBack,
            actionIcon = Icons.Default.Add,
            actionDesc = stringResource(id = R.string.cd_new_playlist),
            onAction = { showCreateDialog = true }
        )

        // 本地能力入口：导入音乐 / 挂载文件夹
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("audio/*")) },
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(id = R.string.action_import_local),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedButton(
                onClick = { folderLauncher.launch(null) },
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(id = R.string.action_mount_folder),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (playlists.isEmpty()) {
            EmptyState(
                message = stringResource(id = R.string.label_playlist_empty),
                hint = stringResource(id = R.string.label_playlist_empty_hint)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Top
            ) {
                items(items = playlists, key = { it.id }) { playlist ->
                    PlaylistCard(
                        playlist = playlist,
                        onClick = { onOpenPlaylist(playlist.id) },
                        onRename = { name -> viewModel.renamePlaylist(playlist.id, name) },
                        onDelete = { viewModel.deletePlaylist(playlist.id) }
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        PlaylistNameDialog(
            title = stringResource(id = R.string.dialog_title_new_playlist),
            initial = "",
            confirmLabel = stringResource(id = R.string.action_create),
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                showCreateDialog = false
                viewModel.createPlaylist(name)
            }
        )
    }

    pendingImport?.let { songs ->
        // 2026-09-12 需求：导入 / 挂载后先要求命名本地歌单；确认后创建到我的歌单
        // （带「导入」/「挂载」徽标，可重命名/删除），同时挂在首页「本地音乐」Tab 下一级。
        PlaylistNameDialog(
            title = stringResource(id = R.string.dialog_title_name_local_chart),
            initial = "",
            confirmLabel = stringResource(id = R.string.action_confirm),
            onDismiss = { pendingImport = null },
            onConfirm = { name ->
                pendingImport = null
                viewModel.createLocalChart(name = name, songs = songs, origin = pendingOrigin)
                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.msg_local_chart_added, name),
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    scanDiagnostic?.let { text ->
        AlertDialog(
            onDismissRequest = { scanDiagnostic = null },
            title = { Text(stringResource(id = R.string.diag_title_scan)) },
            text = {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("scan_diagnostic", text))
                    Toast.makeText(
                        appContext,
                        appContext.getString(R.string.diag_copied),
                        Toast.LENGTH_SHORT
                    ).show()
                }) {
                    Text(stringResource(id = R.string.diag_copy))
                }
            },
            dismissButton = {
                TextButton(onClick = { scanDiagnostic = null }) {
                    Text(stringResource(id = R.string.action_cancel))
                }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// 顶栏（列表页 / 详情页共用）
// ---------------------------------------------------------------------------

@Composable
fun LibraryTopBar(
    title: String,
    onBack: () -> Unit,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector?,
    actionDesc: String?,
    onAction: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = stringResource(id = R.string.cd_back),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
        )
        if (actionIcon != null && onAction != null) {
            IconButton(onClick = onAction, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = actionIcon,
                    contentDescription = actionDesc,
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 歌单卡片
// ---------------------------------------------------------------------------

@Composable
private fun PlaylistCard(
    playlist: Playlist,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clickable(onClickLabel = playlist.name) { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            NoteGlyph(
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 2.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                // 来源徽标（2026-09-12）：导入 →「导入」、挂载 →「挂载」；手动新建不显示
                when (playlist.origin) {
                    Playlist.ORIGIN_IMPORT -> OriginBadge(label = stringResource(id = R.string.label_origin_import))
                    Playlist.ORIGIN_MOUNT -> OriginBadge(label = stringResource(id = R.string.label_origin_mount))
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(id = R.string.label_song_count, playlist.songs.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }

        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(id = R.string.action_rename)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    onClick = {
                        menuOpen = false
                        renaming = true
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(id = R.string.action_delete)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        menuOpen = false
                        deleting = true
                    }
                )
            }
        }
    }

    if (renaming) {
        PlaylistNameDialog(
            title = stringResource(id = R.string.dialog_title_rename_playlist),
            initial = playlist.name,
            confirmLabel = stringResource(id = R.string.action_confirm),
            onDismiss = { renaming = false },
            onConfirm = { name ->
                renaming = false
                onRename(name)
            }
        )
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(id = R.string.dialog_title_delete_playlist)) },
            text = {
                Text(stringResource(id = R.string.dialog_message_delete_playlist, playlist.name))
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    onDelete()
                }) {
                    Text(
                        text = stringResource(id = R.string.action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = false }) {
                    Text(stringResource(id = R.string.action_cancel))
                }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// 来源徽标（导入 / 挂载）
// ---------------------------------------------------------------------------

/** 歌单名右侧的小圆角徽标：「导入」/「挂载」；手动新建的歌单不显示 */
@Composable
private fun OriginBadge(label: String) {
    Spacer(modifier = Modifier.width(6.dp))
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

// ---------------------------------------------------------------------------
// 歌单命名对话框（新建 / 重命名共用）
// ---------------------------------------------------------------------------

@Composable
fun PlaylistNameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    var showEmptyHint by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { input ->
                    name = input
                    showEmptyHint = false
                },
                label = {
                    Text(
                        text = stringResource(id = R.string.hint_playlist_name),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                isError = showEmptyHint,
                supportingText = if (showEmptyHint) {
                    { Text(stringResource(id = R.string.msg_playlist_name_empty)) }
                } else {
                    null
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = name.trim()
                if (trimmed.isEmpty()) {
                    showEmptyHint = true
                } else {
                    onConfirm(trimmed)
                }
            }) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.action_cancel))
            }
        }
    )
}

package com.lelemusic.ui.library

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.lelemusic.model.Song
import com.lelemusic.model.toSong
import com.lelemusic.ui.common.EmptyState
import com.lelemusic.ui.common.SongRow
import com.lelemusic.ui.player.PlayerViewModel
import kotlinx.coroutines.launch

/** 分隔线左缩进：与 SongRow 文字起始对齐（数值同 ChartScreen） */
private const val DIVIDER_INSET = 120

/**
 * 歌单详情页。
 *
 * - 点行：以「整份歌单」为队列从该行开始连续播（播完自动下一首，支持行删除/切歌）；
 * - 行右侧 − 号：把歌移出歌单（只移引用，不删任何文件/云端内容）；
 * - 顶栏：改名（铅笔） / 删除歌单（垃圾桶）；
 * - 工具条「＋」：向本歌单追加本地音频（SAF 文件多选，与「我的歌单」导入同链路）。
 *
 * @param playlistId 路由参数；歌单被删后自动返回上一页
 */
@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit
) {
    val playlists by libraryViewModel.playlists.collectAsState()
    val playlist = remember(playlists, playlistId) {
        playlists.firstOrNull { it.id == playlistId }
    }
    val playerUi by playerViewModel.uiState.collectAsState()
    val currentUid = playerUi.song?.uid.orEmpty()

    // 歌单已被删除 → 回列表
    LaunchedEffect(playlist == null) {
        if (playlist == null) onBack()
    }

    if (playlist == null) {
        // 兜底：避免删除瞬间闪一帧空态再 pop
        EmptyState(
            message = stringResource(id = R.string.label_playlist_detail_empty),
            hint = ""
        )
        return
    }

    val songs: List<Song> = remember(playlist.songs) {
        playlist.songs.mapNotNull { storable ->
            storable.toSong(libraryViewModel.platformById)
        }
    }

    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    // 2026-09-12 用户需求（图 006/007）：歌单详情页支持往当前歌单里导入新歌曲。
    // 复用「我的歌单」页的导入链路：SAF 文件多选(audio/*) → LocalScanner 解析 → addSongs 追加。
    val addSongsLauncher = rememberLauncherForActivityResult(
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
            val picked = LocalScanner.readPickedAudios(appContext, uris)
            if (picked.isEmpty()) {
                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.msg_import_empty),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            val added = libraryViewModel.addSongs(playlistId, picked)
            val message = when {
                added <= 0 -> appContext.getString(R.string.msg_songs_all_dup)
                else -> appContext.getString(R.string.msg_songs_added, added)
            }
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LibraryTopBar(
            title = playlist.name,
            onBack = onBack,
            actionIcon = Icons.Default.Edit,
            actionDesc = stringResource(id = R.string.action_rename),
            onAction = { renaming = true }
        )

        // 歌单工具条：数量 + 播放全部 + 添加歌曲 + 删除歌单
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(id = R.string.label_song_count, songs.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            // 添加歌曲入口（2026-09-12）：向本歌单追加本地音频，空歌单也可用
            IconButton(
                onClick = { addSongsLauncher.launch(arrayOf("audio/*")) },
                modifier = Modifier.width(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(id = R.string.action_add_songs),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (songs.isNotEmpty()) {
                TextButton(onClick = {
                    val first = songs.first()
                    playerViewModel.playSong(song = first, queue = songs)
                }) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.height(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(id = R.string.action_play_all))
                }
                // 一键按标题自然序重排歌单（存量乱序歌单的自愈入口：早期挂载/导入没排序，
                // 再挂载同文件夹会因 uid 去重跳过、顺序永远不变——点这里原地排好）
                TextButton(onClick = {
                    libraryViewModel.sortSongsByTitle(playlist.id)
                    Toast.makeText(
                        appContext,
                        appContext.getString(R.string.msg_sorted_by_name),
                        Toast.LENGTH_SHORT
                    ).show()
                }) {
                    Text(stringResource(id = R.string.action_sort_by_name))
                }
            }
            IconButton(onClick = { deleting = true }, modifier = Modifier.width(48.dp)) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(id = R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }

        if (songs.isEmpty()) {
            EmptyState(
                message = stringResource(id = R.string.label_playlist_detail_empty),
                hint = stringResource(id = R.string.label_playlist_detail_empty_hint)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Top
            ) {
                itemsIndexed(items = songs) { index, stored ->
                    // 榜单行排名展示 1..n；歌单里手动顺序就用它在歌单里的位置
                    val display = stored.copy(rank = index + 1)
                    SongRow(
                        song = display,
                        isCurrent = stored.uid == currentUid,
                        onClick = {
                            playerViewModel.playSong(song = display, queue = songs)
                        },
                        trailing = {
                            IconButton(
                                onClick = {
                                    libraryViewModel.removeSong(playlist.id, stored.uid)
                                },
                                modifier = Modifier.width(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(
                                        id = R.string.cd_remove_song,
                                        stored.title
                                    ),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    )
                    if (index < songs.lastIndex) {
                        Divider(
                            modifier = Modifier.padding(start = DIVIDER_INSET.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                        )
                    }
                }
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
                libraryViewModel.renamePlaylist(playlist.id, name)
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
                    libraryViewModel.deletePlaylist(playlist.id)
                    // 歌单消失后 LaunchedEffect 会触发 onBack()
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

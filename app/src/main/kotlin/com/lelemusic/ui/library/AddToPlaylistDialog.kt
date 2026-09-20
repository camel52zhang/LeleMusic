package com.lelemusic.ui.library

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.lelemusic.model.Song
import kotlinx.coroutines.launch

/**
 * 「加入歌单」对话框：把一批 [songs] 加入某个歌单（榜单行快捷加歌 = 1 首；
 * 本地导入 / 文件夹挂载 = N 首）。
 *
 * - 点歌单 → 整批加入（uid 去重，重复自动跳过），结果 Toast 汇总；
 * - 底部「新建并加入」→ 先命名新建，再把整批加进去。
 */
@Composable
fun AddToPlaylistDialog(
    songs: List<Song>,
    viewModel: LibraryViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val playlists by viewModel.playlists.collectAsState()

    var showCreate by remember { mutableStateOf(false) }
    val batch = songs.size > 1
    val leadSong = songs.firstOrNull()

    fun toastOf(added: Int, playlistName: String) {
        val skipped = songs.size - added
        val text = when {
            added == 0 && skipped > 0 ->
                if (songs.size == 1) {
                    context.getString(R.string.msg_already_in_playlist, playlistName)
                } else {
                    // 整批已在歌单里 → 已触发镜像刷新（按本次顺序重排），提示一并说明
                    context.getString(R.string.msg_batch_dup_refreshed, songs.size, playlistName)
                }

            batch && skipped > 0 ->
                context.getString(R.string.msg_batch_added_partial, added, skipped, playlistName)

            batch ->
                context.getString(R.string.msg_batch_added, added, playlistName)

            else ->
                context.getString(R.string.msg_added_to_playlist, playlistName)
        }
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    fun addTo(playlistId: String, playlistName: String) {
        scope.launch {
            val (added, allDup) = viewModel.addSongsOrAllDup(playlistId, songs)
            // 整批全部已存在 = 重挂载/重复导入同一批 → 按本次顺序刷新歌单（镜像语义）
            if (allDup && songs.size > 1) {
                viewModel.reorderBatch(playlistId, songs)
            }
            toastOf(added, playlistName)
            onDismiss()
        }
    }

    if (showCreate) {
        PlaylistNameDialog(
            title = stringResource(id = R.string.dialog_title_new_playlist),
            initial = "",
            confirmLabel = stringResource(id = R.string.action_create),
            onDismiss = { showCreate = false },
            onConfirm = { name ->
                showCreate = false
                scope.launch {
                    val result = viewModel.createPlaylistAndAddAll(name, songs)
                    if (result.playlist != null) toastOf(result.added, result.playlist.name)
                    onDismiss()
                }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.dialog_title_add_to_playlist)) },
        text = {
            Column {
                leadSong?.let { song ->
                    Text(
                        text = if (batch) {
                            stringResource(R.string.label_songs_summary, song.title, songs.size - 1)
                        } else {
                            song.title
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (playlists.isEmpty()) {
                    Text(
                        text = stringResource(id = R.string.label_no_playlist_for_add),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    val listHeight = if (playlists.size > 6) {
                        260.dp
                    } else {
                        (playlists.size * 48).dp
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(listHeight),
                        verticalArrangement = Arrangement.Top
                    ) {
                        items(items = playlists, key = { it.id }) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .clickable { addTo(playlist.id, playlist.name) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = playlist.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = stringResource(
                                        id = R.string.label_song_count,
                                        playlist.songs.size
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showCreate = true }) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(id = R.string.action_new_and_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.action_cancel))
            }
        }
    )
}

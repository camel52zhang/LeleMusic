package com.lelemusic.ui.player

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lelemusic.R
import com.lelemusic.core.common.normalizeToHttps
import com.lelemusic.core.common.orUnknown
import com.lelemusic.model.Song
import com.lelemusic.ui.common.NoteGlyph
import com.lelemusic.ui.common.PlayGlyph
import com.lelemusic.ui.common.PauseGlyph
import com.lelemusic.ui.common.SkipNextGlyph
import com.lelemusic.ui.common.TrialChip
import com.lelemusic.ui.common.glyphContentDescription

/**
 * 底部迷你播放条（PRD 5.1 底部区域）。
 *
 * 三块内容：左侧 48dp 封面 + 标题 / 歌手，中间点击区（进全屏播放器），
 * 右侧「播放暂停」+「下一首」两个 48dp 图标按钮。
 *
 * 只负责展示与转发点击，**不持有** `PlaybackController`：所有指令由调用方（[PlayerViewModel]）下发。
 *
 * @param song      当前曲目；null 时整条不渲染（由调用方决定是否显示）
 * @param isPlaying 是否正在播放，决定中间按钮画 ▶ 还是 ❙❙
 * @param isTrial   是否试听片段，决定是否挂橙色胶囊
 * @param onOpen    点击封面 / 文字区域 → 进全屏播放器
 * @param onToggle  播放 / 暂停
 * @param onNext    下一首
 */
@Composable
fun MiniPlayerBar(
    song: Song?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    isTrial: Boolean = false,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit
) {
    val current = song
    if (current == null) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
        shadowElevation = 0.dp,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ---- 封面（点击区域的一部分，点它也能进全屏播放器）----
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(color = MaterialTheme.colorScheme.surface)
                    .clickable(
                        onClickLabel = stringResource(id = R.string.cd_open_player)
                    ) { onOpen() },
                contentAlignment = Alignment.Center
            ) {
                val cover = current.coverUrl
                if (cover.isNullOrBlank()) {
                    NoteGlyph(
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                } else {
                    AsyncImage(
                        model = normalizeToHttps(cover),
                        contentDescription = stringResource(id = R.string.cd_album_cover),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // ---- 标题 + 歌手（点击 → 进全屏播放器）----
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        onClickLabel = stringResource(id = R.string.cd_open_player)
                    ) { onOpen() }
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = current.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isTrial) {
                        Spacer(modifier = Modifier.width(6.dp))
                        TrialChip()
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = current.artist.orUnknown(stringResource(id = R.string.label_unknown_artist)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // ---- 播放 / 暂停 ----
            IconButton(
                onClick = onToggle,
                modifier = Modifier
                    .size(48.dp)
                    .glyphContentDescription(stringResource(id = R.string.cd_play_pause))
            ) {
                if (isPlaying) {
                    PauseGlyph(
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    PlayGlyph(
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---- 下一首 ----
            IconButton(
                onClick = onNext,
                modifier = Modifier
                    .size(48.dp)
                    .glyphContentDescription(stringResource(id = R.string.cd_skip_next))
            ) {
                SkipNextGlyph(
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

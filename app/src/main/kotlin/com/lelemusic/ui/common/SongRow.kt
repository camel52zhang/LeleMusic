package com.lelemusic.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lelemusic.R
import com.lelemusic.core.common.formatDuration
import com.lelemusic.core.common.normalizeToHttps
import com.lelemusic.core.common.orUnknown
import com.lelemusic.model.PlayableStatus
import com.lelemusic.model.Song
import com.lelemusic.ui.theme.TrialOrange
import com.lelemusic.ui.theme.TrialOrangeOn

/**
 * 榜单里的一行歌曲（PRD 5.1）。
 *
 * 布局：`[排名] [封面 56dp] [歌名 / 歌手·专辑] [时长]`
 *
 * 关键约束：
 * - 行高 72dp、点击区域整行，满足 ≥48dp 触摸目标；
 * - 时长/专辑缺失显示 `--:--` / `未知专辑`，**禁止显示 `null`**；
 * - [PlayableStatus.UNAVAILABLE] 整行降到 55% 透明度（PRD：预判不可播时灰显）。
 *
 * @param song     当前行的歌曲
 * @param isCurrent 是否是「正在播放」的那首；true 时整行加主色浅底高亮
 * @param onClick  整行点击回调（进入播放器并播放）
 * @param trailing 行尾附加操作插槽（在时长右侧）；榜单快捷加歌 / 歌单移除按钮用。null = 不显示
 */
@Composable
fun SongRow(
    song: Song,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    onClick: () -> Unit,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val dimmed = song.playable == PlayableStatus.UNAVAILABLE
    val rowAlpha = if (dimmed) 0.55f else 1f

    val rowBackground = if (isCurrent) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(color = rowBackground)
            .clickable(
                onClickLabel = stringResource(id = R.string.cd_song_row, song.title)
            ) { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(id = R.string.label_rank, song.rank),
            style = MaterialTheme.typography.labelSmall,
            color = rankColor(song.rank),
            modifier = Modifier.width(32.dp)
        )

        Spacer(modifier = Modifier.width(4.dp))

        AlbumCover(song = song, dimmed = dimmed)

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 2.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = song.title.ifBlank { song.uid },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = rowAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        id = R.string.label_artist_album,
                        song.artist.orUnknown(stringResource(id = R.string.label_unknown_artist)),
                        song.album.orUnknown(stringResource(id = R.string.label_unknown_album))
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = rowAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (song.playable == PlayableStatus.TRIAL_ONLY) {
                    Spacer(modifier = Modifier.width(6.dp))
                    TrialChip()
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = formatDuration(song.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = rowAlpha)
        )

        if (trailing != null) {
            Spacer(modifier = Modifier.width(4.dp))
            trailing()
        }
    }
}

/** 56dp 圆角封面；无封面时显示自绘音符占位 */
@Composable
private fun AlbumCover(
    song: Song,
    dimmed: Boolean
) {
    val cover = song.coverUrl
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (cover.isNullOrBlank()) {
            NoteGlyph(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        } else {
            AsyncImage(
                model = normalizeToHttps(cover),
                contentDescription = stringResource(id = R.string.cd_album_cover),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = if (dimmed) 0.55f else 1f
            )
        }
    }
}

/** 橙色「试听片段」小胶囊（PRD REQ-P0-10，也在 `PlayerScreen` 里复用） */
@Composable
fun TrialChip(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = TrialOrange,
        contentColor = TrialOrangeOn
    ) {
        Text(
            text = stringResource(id = R.string.label_trial),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * 排名颜色：前三名金 / 银 / 铜（PRD 5.1），其余用次级文字色。
 *
 * 三色都经过对比度挑选，深色/浅色主题下都直接可见（不跟随主题变化，避免金银铜在山寨主题下失真）。
 */
@Composable
private fun rankColor(rank: Int): Color = when (rank) {
    1 -> Color(0xFFD4A017)
    2 -> Color(0xFF8E9AA6)
    3 -> Color(0xFFB06A3B)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

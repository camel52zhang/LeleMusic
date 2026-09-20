package com.lelemusic.model

/**
 * 本地音乐（LocalPlatform）的全局键常量。
 *
 * 本地歌曲的「真实文件地址 / 旁路字幕地址」放进 [Song.extras]，用这些键存取；
 * `model.Song.toMediaItem()` 与 `data.local.*` 都要引用，因此放在 model 层避免依赖反向。
 */
object LocalMedia {

    /** 本地音乐平台 id（`Song.uid = "local:<uriHash>"`，**不可更改**，历史 uid 依赖它） */
    const val PLATFORM_ID = "local"

    /** extras 键：真实媒体文件 uri（`content://` / `file://` 字符串） */
    const val EXTRA_FILE_URI = "fileUri"

    /** extras 键：旁路歌词文件 uri（同目录同名 .lrc/.srt，可能缺失） */
    const val EXTRA_LYRIC_URI = "lyricUri"
}

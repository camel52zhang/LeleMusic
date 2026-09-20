package com.lelemusic.data.local

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import com.lelemusic.model.LocalMedia
import com.lelemusic.model.Song
import com.lelemusic.util.NaturalOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地音乐扫描器（SAF 树目录 → 歌曲列表）。
 *
 * 只依赖 [Context.getContentResolver] 读 content uri，不需要存储权限；uri 权限在调用方
 * （Activity 的 ActivityResult）里用 `takePersistableUriPermission` 持久化，重启后仍可读。
 *
 * **为什么完全不用 `DocumentsContract.getDocumentId / buildDocumentUriUsingTree / 
 * buildChildDocumentsUriUsingTree`**（重要教训，build52~55 真机踩坑）：
 * 这些函数内部用 `uri.getPathSegments()` 解析，而小米 DocumentsUI 返回的多级目录树 uri
 * 形如 `content://…/tree/primary%3ADownload%2Fmp3`，`%2F` 的解码语义在不同 Android 版本 /
 * ROM 上不一致，会直接抛 `IllegalArgumentException: Invalid URI`。
 * 本实现改为**自己确定性解析**：从 `encodedPath` 按字面 `/` 切出 tree 段（保持原样编码），
 * 之后所有 document uri 都用「字符串 + Uri.encode(docId)」手工拼接，语义完全可控。
 *
 * **旁路字幕配对**：对每个音频文件，在同一目录里找同名 `.lrc` / `.srt`
 * （小写 / 大写各试一次），找到就把它的 content uri 写进 `Song.extras[EXTRA_LYRIC_URI]`，
 * 播放页歌词面板会自动加载——这就是「挂载的文件夹里音乐和字幕一起自动进来」的实现。
 */
object LocalScanner {

    private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "ape")
    private val LRC_EXTENSIONS = setOf("lrc", "srt")
    private const val DIR_MIME = DocumentsContract.Document.MIME_TYPE_DIR
    private const val MAX_DEPTH = 6
    private const val MAX_FILES = 500

    /** 扫描结果：成功返回歌曲列表；[Error.code] 为 `no_audio` 表示没找到可播音频 */
    sealed interface ScanResult {
        data class Success(val songs: List<Song>) : ScanResult
        data class Error(val code: String, val message: String) : ScanResult
    }

    /**
     * 递归扫描一个 SAF 树目录 uri，收集其中全部可播音频并配对旁路字幕。
     * 在 IO 线程执行（MediaMetadataRetriever 逐个取时长较慢）。
     */
    suspend fun scanTree(context: Context, treeUri: Uri): ScanResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val ref = parseTreeRef(treeUri)
        if (ref == null) {
            return@withContext ScanResult.Error("no_audio", "bad tree uri: $treeUri")
        }
        try {
            val found = ArrayList<FoundAudio>()
            val stats = ScanStats().apply { rootDocId = Uri.decode(ref.treeEncoded) }
            runCatching { walk(resolver, ref, dirDocId = stats.rootDocId, out = found, depth = 0, stats = stats) }
                .onFailure { error -> stats.addIssue("walk-crash: ${error.message}") }
            if (found.isEmpty()) {
                val detail = buildString {
                    append("authority=${ref.authority}")
                    append(" root=${stats.rootDocId}")
                    append(" rows=${stats.rows} dirs=${stats.dirs} audio=${stats.audioCandidates}")
                    if (stats.firstChildIdSample.isNotEmpty()) {
                        append(" sampleId=${stats.firstChildIdSample}")
                    }
                    if (stats.issues.isNotEmpty()) {
                        append(" issues[")
                        append(stats.issues.take(3).joinToString("; "))
                        append("]")
                    }
                }
                Log.w(TAG, "scan empty: $detail")
                return@withContext ScanResult.Error("no_audio", detail)
            }
            val songs = found.mapNotNull { audio ->
                runCatching { audio.toSong(context) }.getOrNull()
            }
            // 挂载结果按文件名自然序排序（"track 2" < "track 10"，中文按拼音），
            // 否则就是 provider cursor 的文件系统原始顺序，乱且不可预期
            val ordered = songs.sortedWith(Comparator.comparing({ it.title }, NaturalOrder.comparator))
            Log.i(TAG, "scan tree=$treeUri audios=${found.size} songs=${ordered.size}")
            ScanResult.Success(ordered)
        } catch (throwable: Throwable) {
            Log.w(TAG, "scan tree=$treeUri failed: ${throwable.message}", throwable)
            ScanResult.Error("scan_failed", throwable.message.orEmpty())
        }
    }

    /** 文件多选挑中的 audio uri → 歌曲列表（单选文件场景没有同目录配对，字幕可手动补） */
    suspend fun readPickedAudios(context: Context, uris: List<Uri>): List<Song> =
        withContext(Dispatchers.IO) {
            uris.mapNotNull { uri ->
                runCatching {
                    FoundAudio(uri, displayNameOf(context, uri)).toSong(context)
                }.getOrNull()
            }
            // 与挂载一致：按标题自然序排序，避免文件选择器返回的任意顺序进歌单
            .sortedWith(Comparator.comparing({ it.title }, NaturalOrder.comparator))
        }

    // ------------------------------------------------------------------
    // uri 解析与拼接（不依赖 DocumentsContract 的 getDocumentId / build*）
    // ------------------------------------------------------------------

    /** tree uri 的确定性拆分：authority + 原样编码的 tree id 段 */
    private data class TreeRef(val authority: String, val treeEncoded: String)

    /**
     * 从 `encodedPath`（如 `/tree/primary%3ADownload%2Fmp3`）按字面 `/` 切段取 tree 值，
     * 保持其原始百分号编码，杜绝 ROM 对 `%2F` 解码不一致导致的 Invalid URI。
     */
    private fun parseTreeRef(treeUri: Uri): TreeRef? {
        val path = treeUri.encodedPath ?: return null
        val parts = path.split('/').filter { it.isNotEmpty() }
        val idx = parts.indexOf("tree")
        if (idx < 0 || idx + 1 >= parts.size) return null
        return TreeRef(
            authority = treeUri.authority.orEmpty(),
            treeEncoded = parts[idx + 1]
        )
    }

    /**
     * tree 下某个 docId 的 content uri：
     * `content://<authority>/tree/<treeEncoded>/document/<Uri.encode(docId)>`
     * docId 多级（如 `primary:Download/mp3/xxx.mp3`）必须整段 [Uri.encode]，
     * 与 AOSP `buildDocumentUriUsingTree`（appendPath 会编码）产出完全一致。
     */
    private fun docUri(ref: TreeRef, docId: String): Uri = Uri.parse(
        "content://${ref.authority}/tree/${ref.treeEncoded}/document/${Uri.encode(docId)}"
    )

    /**
     * tree 下列出 dirDocId 子项的 uri：
     * `content://<authority>/tree/<treeEncoded>/document/<Uri.encode(dirDocId)>/children`
     *
     * 与 AOSP `buildChildDocumentsUriUsingTree` 结构逐段一致：
     *  - tree 段保持原样编码（parseTreeRef 已提取）；
     *  - 父 docId 经 appendPath 语义**整段编码**（`primary:Download/mp3` → `primary%3ADownload%2Fmp3`），
     *    Provider 端 `getDocumentId` 会 `Uri.decode` 还原成完整父 id；若原样插入带 `/` 的 docId，
     *    路径会被切段，只解析到 `primary:Download`，多级目录全部错位（build53~55 同类坑）；
     *  - 尾段 `/children` 让 provider 分发到 queryChildDocuments。
     */
    private fun childListUri(ref: TreeRef, dirDocId: String): Uri = Uri.parse(
        "content://${ref.authority}/tree/${ref.treeEncoded}/document/${Uri.encode(dirDocId)}/children"
    )

    // ------------------------------------------------------------------
    // 目录行走
    // ------------------------------------------------------------------

    private data class FoundAudio(
        val uri: Uri,
        val displayName: String,
        /** 旁路字幕 content uri 字符串（walk 期间已探测存在性；null = 没有同名 .lrc/.srt） */
        val lyricUriText: String? = null
    )

    /** 空扫诊断统计：无 adb 环境下把关键中间量带到 Toast/对话框，一次性定位失败层 */
    private class ScanStats {
        var rows = 0
        var dirs = 0
        var audioCandidates = 0
        var rootDocId: String = ""
        /** children 首行原始 COLUMN_DOCUMENT_ID 样例：判断 provider 返回的是绝对还是相对 id */
        var firstChildIdSample: String = ""
        private val issueList = ArrayList<String>()

        fun addIssue(text: String) {
            if (issueList.size < 8) issueList.add(text)
        }

        val issues: List<String> get() = issueList
    }

    /** 单次 query 列出 dirDocId 的 children：目录下钻、音频直接收集 */
    private fun walk(
        resolver: ContentResolver,
        ref: TreeRef,
        dirDocId: String,
        out: MutableList<FoundAudio>,
        depth: Int,
        stats: ScanStats
    ) {
        if (depth > MAX_DEPTH || out.size >= MAX_FILES) return
        // 列子项 uri 按 AOSP 格式：/tree/<encTreeId>/document/<enc父docId>/children
        // 父 docId 整段 Uri.encode（多级目录才不会被 `/` 切段），尾随 /children 分发 queryChildDocuments
        val childrenUri = childListUri(ref, dirDocId)
        val subDirs = ArrayList<String>()

        runCatching {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null, null, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (out.size >= MAX_FILES) break
                    runCatching scanRow@{
                        val id = if (idCol >= 0) cursor.getString(idCol) else null
                        val name = if (nameCol >= 0) cursor.getString(nameCol) else ""
                        val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else null
                        if (id.isNullOrEmpty()) {
                            stats.addIssue("row w/o docId name=${name.take(30)}")
                            return@scanRow
                        }
                        if (stats.firstChildIdSample.isEmpty()) {
                            stats.firstChildIdSample = id.take(80)
                        }
                        val safeName = name.ifBlank { id.substringAfterLast('/').substringAfterLast(':') }
                        if (safeName.startsWith('.')) return@scanRow // 隐藏文件 / Android 目录
                        stats.rows++

                        val isDir = mime == DIR_MIME
                        if (isDir) {
                            stats.dirs++
                            subDirs.add(id)
                            return@scanRow
                        }
                        // 判型：mime / 扩展名命中即收；两者都无时按 uri getType 探测
                        val audioish = isAudio(safeName, mime) ||
                            (mime == null && extensionOf(safeName) !in AUDIO_EXTENSIONS &&
                                probeAudioByUri(resolver, ref, id))
                        if (!audioish) return@scanRow
                        stats.audioCandidates++

                        val audioUri = docUri(ref, id)
                        // 旁路字幕配对失败不影响音频入库——失败就当没字幕
                        val lyricUriText = runCatching { pairLyric(resolver, ref, id) }.getOrNull()

                        out.add(
                            FoundAudio(
                                uri = audioUri,
                                displayName = safeName,
                                lyricUriText = lyricUriText
                            )
                        )
                    }.onFailure { error ->
                        stats.addIssue("row: ${error.javaClass.simpleName}: ${error.message}")
                    }
                }
            } ?: stats.addIssue("children query null at ${stats.rootDocId}")
        }.onFailure { error ->
            stats.addIssue("list ${stats.rootDocId}: ${error.javaClass.simpleName}: ${error.message}")
        }

        for (sub in subDirs) {
            runCatching { walk(resolver, ref, sub, out, depth + 1, stats) }
                .onFailure { error -> stats.addIssue("subdir: ${error.message}") }
        }
    }

    // ------------------------------------------------------------------
    // 判型 / 旁路字幕 / 元数据
    // ------------------------------------------------------------------

    private fun isAudio(name: String, mime: String?): Boolean {
        if (mime != null) {
            if (mime.startsWith("audio/")) return true
            if (mime == "application/ogg") return true // 部分 .ogg 标成 application/ogg
        }
        return extensionOf(name) in AUDIO_EXTENSIONS
    }

    /** 边界场景：mime 为空且扩展名也不在音频清单时按 uri getType 探测 */
    private fun probeAudioByUri(resolver: ContentResolver, ref: TreeRef, id: String): Boolean =
        runCatching { resolver.getType(docUri(ref, id))?.startsWith("audio/") == true }
            .getOrDefault(false)

    /**
     * 在 tree 下找同目录同名字幕：docId 形如 `primary:Download/mp3/xxx.mp3`，
     * 替换扩展名后探测存在性（buildDocumentUriUsingTree 不用了，改纯字符串拼接）。
     */
    private fun pairLyric(resolver: ContentResolver, ref: TreeRef, audioDocId: String): String? {
        val dot = audioDocId.lastIndexOf('.')
        if (dot <= 0) return null
        val stem = audioDocId.substring(0, dot)
        for (ext in LRC_EXTENSIONS) {
            for (candidate in listOf("$stem.$ext", "$stem.${ext.uppercase()}")) {
                if (documentExists(resolver, ref, candidate)) return docUri(ref, candidate).toString()
            }
        }
        return null
    }

    private fun documentExists(resolver: ContentResolver, ref: TreeRef, docId: String): Boolean =
        runCatching {
            resolver.query(docUri(ref, docId), null, null, null, null)?.use { it.moveToFirst() } == true
        }.getOrDefault(false)

    private fun FoundAudio.toSong(context: Context): Song {
        val fileUriText = uri.toString()
        val durationMs = readDurationMs(context, uri)
        return Song(
            uid = "${LocalMedia.PLATFORM_ID}:$fileUriText",
            platform = LocalPlatform,
            platformSongId = fileUriText,
            extras = mapOf(
                LocalMedia.EXTRA_FILE_URI to fileUriText,
                LocalMedia.EXTRA_LYRIC_URI to (lyricUriText.orEmpty())
            ),
            title = displayName.substringBeforeLast('.').ifBlank { displayName },
            artist = "",
            album = "",
            durationMs = durationMs,
            coverUrl = null,
            rank = 0
        )
    }

    private fun readDurationMs(context: Context, uri: Uri): Long = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrDefault(0L)

    private fun displayNameOf(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    private fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    private const val TAG = "LocalScanner"
}

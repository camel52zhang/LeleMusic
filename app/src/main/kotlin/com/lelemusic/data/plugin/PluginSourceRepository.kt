package com.lelemusic.data.plugin

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID

/**
 * 用户音源仓库（`filesDir/plugins.json` 持久化）。
 *
 * **这一层刻意不含任何 JS 引擎逻辑**：它只负责「用户贴进来的东西 → 识别 → 抓取 → 记录元数据 → 持久化」，
 * 因此不依赖引擎选型，引擎接上后只需在 `runtime` 分派处加执行分支即可，这里不用改。
 *
 * 抓取阶段能立刻给出可用信息：
 * - 是注册表（plugins.json，含 `plugins` 数组）还是单源脚本
 * - 脚本大概是什么契约（LX-Music 的 `globalThis.lx` / MusicFree 风格）
 * - 抓不抓得到（HTTP 错误、超时都会落到 [PluginSource.lastError]）
 */
class PluginSourceRepository(
    private val file: File,
    private val gson: Gson,
    private val client: OkHttpClient
) {

    private val lock = Any()

    private val _sources = MutableStateFlow<List<PluginSource>>(emptyList())
    val sources: StateFlow<List<PluginSource>> = _sources

    init {
        _sources.value = readDisk()
    }

    // -----------------------------------------------------------------------
    // 持久化
    // -----------------------------------------------------------------------

    private fun readDisk(): List<PluginSource> {
        return try {
            if (!file.exists()) return emptyList()
            val container = gson.fromJson(file.readText(), SourceFile::class.java)
            container?.sources?.filter { it.url.isNotBlank() } ?: emptyList()
        } catch (t: Throwable) {
            // 与 playlists.json 同款处理：坏文件改名留档，不让用户卡在崩溃里
            runCatching { file.renameTo(File(file.parentFile, "plugins.json.corrupt")) }
            emptyList()
        }
    }

    private fun persist(list: List<PluginSource>) {
        try {
            val payload = gson.toJson(SourceFile(version = 1, sources = list))
            val tmp = File(file.parentFile, "plugins.json.tmp")
            tmp.writeText(payload)
            if (!tmp.renameTo(file)) {
                file.writeText(payload)
            }
        } catch (t: Throwable) {
            // 持久化失败不应让 UI 崩：内存态仍然可用
        }
    }

    private fun commit(list: List<PluginSource>) {
        synchronized(lock) {
            persist(list)
            _sources.value = list
        }
    }

    // -----------------------------------------------------------------------
    // 增删改
    // -----------------------------------------------------------------------

    /** 解析并添加一段清单文本（可多行）；已存在的 URL 跳过 */
    fun addFromText(text: String): AddSummary {
        val result = SourceImportParser.parse(text)
        if (result.entries.isEmpty()) return AddSummary(invalid = result.invalidLines)

        val current = _sources.value
        val known = current.map { it.url }.toHashSet()
        val fresh = mutableListOf<PluginSource>()
        var skipped = 0

        for (entry in result.entries) {
            if (!known.add(entry.url)) {
                skipped++
                continue
            }
            fresh += PluginSource(
                id = stableId(entry.url),
                name = entry.name ?: SourceImportParser.nameFromUrl(entry.url),
                url = entry.url
            )
        }

        if (fresh.isNotEmpty()) commit(current + fresh)
        return AddSummary(added = fresh.size, skipped = skipped, invalid = result.invalidLines)
    }

    fun remove(id: String) {
        val current = _sources.value
        // 注册表被删时，它展开出来的子源一起删，避免留下孤儿
        val next = current.filter { it.id != id && it.parentId != id }
        if (next.size != current.size) commit(next)
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val current = _sources.value
        val next = current.map { if (it.id == id) it.copy(enabled = enabled) else it }
        commit(next)
    }

    // -----------------------------------------------------------------------
    // 抓取元数据
    // -----------------------------------------------------------------------

    /** 抓取全部音源的元数据（脚本大小 / 契约类型 / 错误） */
    suspend fun refreshAll() = withContext(Dispatchers.IO) {
        val current = _sources.value
        val updated = current.map { probe(it) }
        commit(updated)
    }

    suspend fun refreshOne(id: String) = withContext(Dispatchers.IO) {
        val current = _sources.value
        val updated = current.map { if (it.id == id) probe(it) else it }
        commit(updated)
    }

    /**
     * 展开注册表：把 `plugins` 数组里的每一条装成子源（父源保留，可重复展开）。
     *
     * @return 新增的插件条数
     */
    suspend fun expandRegistry(id: String): Int = withContext(Dispatchers.IO) {
        val current = _sources.value
        val target = current.firstOrNull { it.id == id } ?: return@withContext 0

        val text = fetchText(target.url)
        if (text == null) {
            commit(current.map { if (it.id == id) it.copy(lastError = ERROR_FETCH) else it })
            return@withContext 0
        }

        val items = parseRegistry(text)
        val known = current.map { it.url }.toHashSet()
        val children = mutableListOf<PluginSource>()
        for (item in items) {
            val url = item.url?.trim().orEmpty()
            if (url.isBlank() || !known.add(url)) continue
            children += PluginSource(
                id = stableId(url),
                name = item.name?.takeIf { it.isNotBlank() } ?: SourceImportParser.nameFromUrl(url),
                url = url,
                version = item.version,
                // 注册表展开的子源默认关闭：一次展开可能装进几十个插件，
                // 全部默认启用会把主页 Tab 区淹没；用户按需逐个打开。
                enabled = false,
                parentId = id
            )
        }

        val updated = current.map {
            if (it.id == id) it.copy(childCount = items.size, lastError = null, bytes = text.length.toLong())
            else it
        } + children
        commit(updated)
        children.size
    }

    // -----------------------------------------------------------------------
    // 内部实现
    // -----------------------------------------------------------------------

    private fun probe(source: PluginSource): PluginSource {
        val text = fetchText(source.url) ?: return source.copy(lastError = ERROR_FETCH)
        return if (isRegistry(source.url, text)) {
            source.copy(
                type = PluginSource.TYPE_REGISTRY,
                bytes = text.length.toLong(),
                childCount = parseRegistry(text).size,
                lastError = null
            )
        } else {
            source.copy(
                type = PluginSource.TYPE_SINGLE,
                runtime = detectRuntime(text),
                bytes = text.length.toLong(),
                lastError = null
            )
        }
    }

    private fun fetchText(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", UA)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                body.string()
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun isRegistry(url: String, text: String): Boolean {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("{")) return false
        return url.endsWith(".json", ignoreCase = true) || trimmed.contains("\"plugins\"")
    }

    private fun parseRegistry(text: String): List<RegistryItem> {
        return try {
            gson.fromJson(text, RegistryFile::class.java)?.plugins.orEmpty()
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /**
     * 粗略判定脚本契约。
     *
     * 判据都是脚本里的硬特征字符串：LX-Music 系插件必然依赖宿主注入的 `globalThis.lx`，
     * MusicFree 系插件以 `module.exports` 导出带 `getMusicUrl`/`search` 的对象。
     * 判定只用于 UI 展示与后续执行分派，误判不会损坏数据。
     */
    private fun detectRuntime(text: String): String = when {
        text.contains("globalThis.lx") || text.contains("lx.EVENT_NAMES") -> PluginSource.RUNTIME_LX
        text.contains("getMusicUrl") || text.contains("musicfree", ignoreCase = true) ->
            PluginSource.RUNTIME_MUSICFREE

        else -> PluginSource.RUNTIME_UNKNOWN
    }

    private fun stableId(url: String): String =
        UUID.nameUUIDFromBytes(url.toByteArray()).toString().take(12)

    private data class SourceFile(
        val version: Int = 1,
        val sources: List<PluginSource> = emptyList()
    )

    private data class RegistryFile(
        val plugins: List<RegistryItem>? = null
    )

    private data class RegistryItem(
        val name: String? = null,
        val url: String? = null,
        val version: String? = null
    )

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 LeLeMusic"
        const val ERROR_FETCH = "抓取失败（网络错误或地址不可达）"
    }
}

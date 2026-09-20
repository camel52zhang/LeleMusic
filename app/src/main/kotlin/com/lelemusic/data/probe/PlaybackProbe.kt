package com.lelemusic.data.probe

import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.lelemusic.BuildConfig
import com.lelemusic.core.net.HttpStack
import java.io.IOException

/**
 * 播放探针：用**与 ExoPlayer 完全相同**的 `OkHttpDataSource`（复用取链同款 OkHttpClient，
 * 自动带上 `UserAgentInterceptor` + `RefererInterceptor`）去真实拉流直链的前 [PROBE_BYTES]
 * 字节，捕获设备上的真实 IO 异常。
 *
 * **为什么需要它**：自检台原本用 `MediaMetadataRetriever` 探测时长，它走系统媒体栈（stagefright），
 * 与 ExoPlayer 的 OkHttp 请求行为不同——同一首歌 `MediaMetadataRetriever` 能读时长，
 * ExoPlayer 却可能在真机上抛 `ERROR_CODE_IO_*`（UI 表现为「网络异常，无法播放」）。
 * 本探针把 ExoPlayer 的**网络路径**在自检台里原样复现一遍：用户重跑自检、贴回日志，
 * 就能直接看到设备真实播放错误，无需手动 `adb logcat`。
 *
 * 与真实播放链路逐项对齐：
 * - 数据源 = `OkHttpDataSource.Factory(HttpStack.okHttpClient(BuildConfig.DEBUG))`
 *   （与 `ResolvingMediaSourceFactory` 完全一致）；
 * - `track.headers`（网易云在此带 `Referer: https://music.163.com/`）逐条 `setRequestProperty`，
 *   与 `ResolveDataSpecResolver.setHttpRequestHeaders` 等价；
 * - 拦截器在重定向后重新注入 Referer，与播放器一致；
 * - 超时由 `HttpStack` 的 connect/read/call（各 8s）兜底，不会永久卡死。
 */
object PlaybackProbe {

    private const val TAG = "PlaybackProbe"

    /**
     * 读多少字节就收手（不是 Range 长度——见 [probe] 里 DataSpec 的注释）。
     *
     * 256KB 足够验证「能建连并读到数据」，又不会把整首歌拉下来拖慢自检。
     */
    private const val PROBE_BYTES = 256 * 1024

    private const val HEADER_CONTENT_TYPE = "Content-Type"

    data class Result(
        val ok: Boolean,
        val bytesRead: Int,
        val responseCode: Int,
        val errorMessage: String,
        val finalUrl: String,
        val contentType: String
    ) {
        /**
         * 供日志 / UI 直接消费的诊断串。
         *
         * 成功时为空；失败时把**定位所需的关键信息一次给全**——
         * 异常、HTTP 码、Content-Type、实际请求的最终 URL（看是否发生 http→https 跳转）。
         */
        val error: String
            get() = if (ok) "" else buildString {
                append(errorMessage)
                if (responseCode > 0) append(" | http=$responseCode")
                if (contentType.isNotBlank()) append(" | ct=$contentType")
                if (finalUrl.isNotBlank()) append(" | url=$finalUrl")
            }
    }

    /**
     * @param url     已解析的真实直链（可能是 http 明文，遵循 network_security_config 白名单）
     * @param headers resolver 返回的逐曲请求头（如网易云的 Referer）
     * @return 拉流结果；任何异常都被收敛进 [Result]，不会向上抛
     */
    fun probe(url: String, headers: Map<String, String>): Result {
        val factory = OkHttpDataSource.Factory(HttpStack.okHttpClient(BuildConfig.DEBUG))
        val dataSource = factory.createDataSource()
        headers.forEach { (k, v) -> runCatching { dataSource.setRequestProperty(k, v) } }

        // 刻意**不设 length**：ExoPlayer 首次 open progressive 源时用的是
        // position=0 + length=C.LENGTH_UNSET，此时 OkHttpDataSource **不会发 Range 头**。
        // 一旦设了 length，它就会发出 `Range: bytes=0-N`，与播放器真实行为不一致——
        // 部分 CDN 对 Range 请求返回 416/403，会让探针给出误导性结论（前面几轮误判正是栽在这类
        // 「看起来等价其实不等价」的差异上）。读到 PROBE_BYTES 就主动收手即可。
        val spec = DataSpec.Builder()
            .setUri(url)
            .setPosition(0L)
            .build()

        return try {
            dataSource.open(spec)

            val buffer = ByteArray(8192)
            var total = 0
            while (total < PROBE_BYTES) {
                val n = dataSource.read(buffer, 0, buffer.size)
                if (n == C.RESULT_END_OF_INPUT) break
                if (n <= 0) break
                total += n
            }

            Result(
                ok = true,
                bytesRead = total,
                responseCode = runCatching { dataSource.responseCode }.getOrDefault(-1),
                errorMessage = "",
                // 重定向后的最终地址：用来判断 http 是否被 302 到了 https
                finalUrl = runCatching { dataSource.uri?.toString().orEmpty() }.getOrDefault(""),
                contentType = runCatching { contentTypeOf(dataSource) }.getOrDefault("")
            )
        } catch (badCode: HttpDataSource.InvalidResponseCodeException) {
            // CDN 风控 / 直链过期最常见的落点：403 / 404 / 410
            Log.w(TAG, "playback probe http ${badCode.responseCode}: $url", badCode)
            Result(
                ok = false,
                bytesRead = 0,
                responseCode = badCode.responseCode,
                errorMessage = "InvalidResponseCodeException: ${badCode.responseMessage ?: "no message"}",
                finalUrl = runCatching { badCode.dataSpec.uri.toString() }.getOrDefault(url),
                contentType = badCode.headerFields[HEADER_CONTENT_TYPE]?.firstOrNull()
                    ?: badCode.headerFields.keys
                        .firstOrNull { it.equals(HEADER_CONTENT_TYPE, ignoreCase = true) }
                        ?.let { badCode.headerFields[it]?.firstOrNull() }
                        .orEmpty()
            )
        } catch (cleartext: HttpDataSource.CleartextNotPermittedException) {
            // 明文被系统拦：说明 network_security_config 白名单没覆盖到这个 host
            Log.w(TAG, "playback probe cleartext blocked: $url", cleartext)
            Result(
                ok = false,
                bytesRead = 0,
                responseCode = -1,
                errorMessage = "CleartextNotPermitted: ${cleartext.message ?: "http not allowed for this host"}",
                finalUrl = url,
                contentType = ""
            )
        } catch (io: IOException) {
            // 超时 / DNS / TLS / 连接重置等，都在这里露原形
            Log.w(TAG, "playback probe IO failed: $url", io)
            Result(
                ok = false,
                bytesRead = 0,
                responseCode = -1,
                errorMessage = "${io.javaClass.simpleName}: ${io.message ?: "no message"}",
                finalUrl = url,
                contentType = ""
            )
        } catch (throwable: Throwable) {
            Log.w(TAG, "playback probe failed: $url", throwable)
            Result(
                ok = false,
                bytesRead = 0,
                responseCode = -1,
                errorMessage = "${throwable.javaClass.simpleName}: ${throwable.message ?: "no message"}",
                finalUrl = url,
                contentType = ""
            )
        } finally {
            runCatching { dataSource.close() }
        }
    }

    private fun contentTypeOf(dataSource: HttpDataSource): String =
        dataSource.responseHeaders
            .entries
            .firstOrNull { (key, _) -> key.equals(HEADER_CONTENT_TYPE, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            .orEmpty()
}

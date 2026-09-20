package com.lelemusic.data.netease

import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.lelemusic.core.common.AppError
import com.lelemusic.core.common.AppException
import com.lelemusic.core.common.NETEASE_REFERER
import com.lelemusic.core.common.normalizePlayUrl
import com.lelemusic.data.source.PlayUrlResolver
import com.lelemusic.data.source.ResolverEndpoints
import com.lelemusic.model.Platform
import com.lelemusic.model.ResolvedTrack
import com.lelemusic.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** 策略 ID（自检台展示与开关覆盖的 key；**不可更改**，否则已保存的开关覆盖失效） */
private const val STRATEGY_ID = "netease.eapi"

/**
 * 网易云 eapi 原生取链（配方源自 GitHub `Suxiaoqinx/Netease_url` 2788★，2026-09-11 源码级分析）。
 *
 * 与旧 `netease.320`（`music.163.com/api/song/enhance/player/url`）同源不同门：
 * 走桌面客户端加密通道 `interface3.music.163.com/eapi/song/enhance/player/url/v1`，
 * 匿名 Cookie 只需 `os=pc; deviceId=pyncm!`，免费歌可直接给 exhigh(320k)。
 *
 * ## eapi 加密配方（纯 Kotlin，零第三方依赖）
 * 1. `payload` = 固定键序 JSON（ids/level/encodeType/header）；
 * 2. `digest` = md5hex(`"nobody" + path + "use" + payload + "md5forencrypt"`)，
 *    其中 path 是把 `/eapi/` 替换回 `/api/` 的请求路径；
 * 3. `text` = `path-36cd479b6b5-payload-36cd479b6b5-digest`；
 * 4. AES-ECB/PKCS5 加密 text（key=`e82ckenh8dichen8`）→ hex → 作为表单字段 `params` POST。
 *
 * lossless/hires 需黑胶 Cookie（后续扫码登录可解锁，本版先做匿名 exhigh）。
 */
class NeteaseEapiResolver(
    private val client: OkHttpClient
) : PlayUrlResolver {

    override val platform: Platform = NeteasePlatform
    override val strategyId: String = STRATEGY_ID
    override val priority: Int = 9
    override val defaultEnabled: Boolean = true

    init {
        ResolverEndpoints.register(STRATEGY_ID, URL_EAPI)
    }

    override suspend fun resolve(song: Song): ResolvedTrack = withContext(Dispatchers.IO) {
        val songId = song.platformSongId.toLongOrNull()
        if (songId == null) {
            throw AppException(
                AppError.Parse,
                "eapi song id invalid uid=${song.uid} strategy=$strategyId"
            )
        }

        val raw = requestUrl(songId) ?: throw AppException(
            AppError.PlaySourceUnavailable,
            "eapi url empty id=$songId level=$LEVEL strategy=$strategyId"
        )

        Log.i(TAG, "eapi resolved id=$songId level=$LEVEL")
        ResolvedTrack(
            url = normalizePlayUrl(raw),
            isFull = true,
            headers = mapOf("Referer" to NETEASE_REFERER),
            strategyId = strategyId,
            quality = QUALITY_LABEL
        )
    }

    /** 发起 eapi 请求，成功返回直链，失败返回 null（交降级链继续） */
    private fun requestUrl(songId: Long): String? {
        val payload = buildPayload(songId)
        val form = "params=" + aesEcbHex(eapiText(URL_PATH_FOR_DIGEST, payload))
        val request = Request.Builder()
            .url(ResolverEndpoints.current(STRATEGY_ID) ?: URL_EAPI)
            .post(form.toRequestBody(FORM_MEDIA_TYPE))
            .header("User-Agent", DESKTOP_UA)
            .header("Referer", NETEASE_REFERER)
            .header("Cookie", ANONYMOUS_COOKIE)
            .build()

        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val json = JSON_PARSER.fromJson(body, JsonObject::class.java) ?: return null
                if (json.get("code")?.asIntOrNull() != 200) return null
                val data = json.getAsJsonArray("data")?.firstOrNull()?.asJsonObjectOrNull()
                    ?: return null
                data.get("url")?.takeIf { it.isJsonPrimitive }?.asString
                    ?.takeIf { it.isNotBlank() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "eapi request failed: ${t.message}")
            null
        }
    }

    /**
     * 构造固定键序的 payload JSON（digest 必须与加密用的是**同一个字符串**，
     * 因此手工拼接而非 Gson 序列化）。
     */
    private fun buildPayload(songId: Long): String {
        val headerJson =
            "{\"os\":\"pc\",\"appver\":\"\",\"osver\":\"\",\"deviceId\":\"pyncm!\"," +
                "\"requestId\":\"${(10000000..99999999).random()}\"}"
        val escapedHeader = headerJson.replace("\"", "\\\"")
        return "{\"ids\":[$songId],\"level\":\"$LEVEL\",\"encodeType\":\"flac\"," +
            "\"header\":\"$escapedHeader\"}"
    }

    /** `nobody{path}use{payload}md5forencrypt` 的整体加密输入（digest 在内，见配方） */
    private fun eapiText(path: String, payload: String): String {
        val digest = md5Hex("nobody${path}use$payload" + "md5forencrypt")
        return "$path-36cd479b6b5-$payload-36cd479b6b5-$digest"
    }

    private fun aesEcbHex(text: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(AES_KEY, "AES"))
        return cipher.doFinal(text.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun JsonElement.asIntOrNull(): Int? =
        if (isJsonPrimitive) runCatching { asInt }.getOrNull() else null

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
        if (isJsonObject) asJsonObject else null

    private companion object {
        const val TAG = "NeteaseEapiResolver"

        const val URL_EAPI = "https://interface3.music.163.com/eapi/song/enhance/player/url/v1"
        const val URL_PATH_FOR_DIGEST = "/api/song/enhance/player/url/v1"
        const val LEVEL = "exhigh"
        const val QUALITY_LABEL = "320k"

        /** eapi 公开 AES 密钥（网易全客户端通用，非机密） */
        val AES_KEY = "e82ckenh8dichen8".toByteArray(Charsets.UTF_8)

        val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()

        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/2.10.2.200154"

        const val ANONYMOUS_COOKIE = "os=pc; appver=; osver=; deviceId=pyncm!"

        val JSON_PARSER = com.google.gson.Gson()
    }
}

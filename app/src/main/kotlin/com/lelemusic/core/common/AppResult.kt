package com.lelemusic.core.common

import com.google.gson.JsonSyntaxException
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * 全 App 统一错误码枚举。
 *
 * UI 层只认这 6 个枚举，禁止解析 message 文本来决定 UI 分支（见架构文档 §7.3 铁律 2）。
 */
sealed class AppError(val code: String) {

    /** 网络不可达 / IO 异常 */
    object Network : AppError("E_NET")

    /** 请求超时 */
    object Timeout : AppError("E_TIMEOUT")

    /** 响应结构异常 / 反序列化失败 */
    object Parse : AppError("E_PARSE")

    /** 三级降级全部失败，拿不到任何可播地址 */
    object PlaySourceUnavailable : AppError("E_NO_SOURCE")

    /** 接口正常但返回空列表 */
    object EmptyData : AppError("E_EMPTY")

    /** 兜底 */
    object Unknown : AppError("E_UNKNOWN")
}

/**
 * data 层统一抛出的异常类型。
 *
 * @param error  错误枚举
 * @param detail 供日志与自检台回溯的细节文本（可含上游错误码，如 `104009`）
 * @param cause  原始异常
 */
class AppException(
    val error: AppError,
    val detail: String = "",
    cause: Throwable? = null
) : Exception("${error.code}: $detail", cause)

/**
 * 所有 Repository / Resolver / DataSource 的对外方法统一用它包裹。
 *
 * - 已经是 [AppException] 的直接透传；
 * - 其余按类型收敛为 [AppError]，保证 data 层绝不向 UI 抛原始 Exception（§7.3 铁律 1）。
 */
suspend fun <T> safeCall(block: suspend () -> T): T =
    try {
        block()
    } catch (e: AppException) {
        throw e
    } catch (e: SocketTimeoutException) {
        throw AppException(AppError.Timeout, e.message.orEmpty(), e)
    } catch (e: IOException) {
        throw AppException(AppError.Network, e.message.orEmpty(), e)
    } catch (e: JsonSyntaxException) {
        throw AppException(AppError.Parse, e.message.orEmpty(), e)
    } catch (e: Exception) {
        throw AppException(AppError.Unknown, e.message.orEmpty(), e)
    }

/**
 * 从任意 Throwable 中提取一个稳定的错误码标识，供自检台表格展示。
 *
 * - [AppException] → 其 [AppError.code]（如 `E_NO_SOURCE`）
 * - 其余 → 异常类名（如 `TimeoutCancellationException`）
 */
fun errorCodeOf(throwable: Throwable): String =
    (throwable as? AppException)?.error?.code ?: (throwable.javaClass.simpleName ?: "Throwable")

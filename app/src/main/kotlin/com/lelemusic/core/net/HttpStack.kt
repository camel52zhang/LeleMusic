package com.lelemusic.core.net

import com.google.gson.Gson
import com.lelemusic.core.common.KUGOU_REFERER
import com.lelemusic.core.common.MOBILE_UA
import com.lelemusic.core.common.NETEASE_REFERER
import com.lelemusic.core.common.TIMEOUT_CALL_MS
import com.lelemusic.core.common.TIMEOUT_CONNECT_MS
import com.lelemusic.core.common.TIMEOUT_READ_MS
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * OkHttp + Retrofit + Gson 的构建工厂。
 *
 * 三个拦截器（架构文档 T01 要点 2）：
 * 1. [UserAgentInterceptor] —— 全局注入移动端 UA；
 * 2. [RefererInterceptor] —— 按 host 注入 Referer（部分平台 CDN 风控判断的关键变量）；
 * 3. [HttpLoggingInterceptor] —— 仅 debug 构建添加，Level.BASIC。
 *
 * Retrofit 的 baseUrl 是占位地址，所有接口都用 `@Url` 传全路径（架构文档 §7.5）。
 */
object HttpStack {

    private const val PLACEHOLDER_BASE_URL = "https://placeholder.invalid/"

    /**
     * @param debug 是否添加日志拦截器；由调用方传 `BuildConfig.DEBUG`，
     *              避免本类直接依赖 BuildConfig（便于未来移到独立模块）。
     */
    fun okHttpClient(debug: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_CONNECT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_READ_MS, TimeUnit.MILLISECONDS)
            .callTimeout(TIMEOUT_CALL_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(UserAgentInterceptor())
            .addInterceptor(RefererInterceptor())

        if (debug) {
            val logging = HttpLoggingInterceptor()
            logging.level = HttpLoggingInterceptor.Level.BASIC
            builder.addInterceptor(logging)
        }

        return builder.build()
    }

    /**
     * @param client [okHttpClient] 的产物
     * @param gson   反序列化器；Gson 用 Unsafe 绕开构造器，因此所有 DTO 字段必须可空（见 §7.4）
     */
    fun retrofit(client: OkHttpClient, gson: Gson = Gson()): Retrofit =
        Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

    /** 全局伪装 UA：OkHttp 默认 UA（`okhttp/4.12.0`）会被一眼识别为爬虫 */
    private class UserAgentInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
                .newBuilder()
                .header("User-Agent", MOBILE_UA)
                .build()
            return chain.proceed(request)
        }
    }

    /**
     * 按 host 注入 Referer。
     *
     * 若调用方（Resolver）已通过 `@Header` 显式设置过 Referer，则不再覆盖。
     */
    private class RefererInterceptor : Interceptor {

        override fun intercept(chain: Interceptor.Chain): Response {
            val origin = chain.request()
            if (origin.header(HEADER_REFERER) != null) {
                return chain.proceed(origin)
            }
            val referer = refererFor(origin.url.host)
                ?: return chain.proceed(origin)
            val withReferer = origin.newBuilder()
                .header(HEADER_REFERER, referer)
                .build()
            return chain.proceed(withReferer)
        }

        private fun refererFor(host: String): String? = when {
            host.endsWith("music.163.com") || host.endsWith("music.126.net") -> NETEASE_REFERER
            host.endsWith("kugou.com") -> KUGOU_REFERER
            else -> null
        }

        private companion object {
            const val HEADER_REFERER = "Referer"
        }
    }
}

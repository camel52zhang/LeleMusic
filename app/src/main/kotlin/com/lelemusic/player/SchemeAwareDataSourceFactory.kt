package com.lelemusic.player

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.lelemusic.core.net.HttpStack
import java.io.IOException

/**
 * **按 uri scheme 分流**的 [DataSource.Factory]。
 *
 * 之前播放链路的上游**只有 OkHttpDataSource**——云平台 CDN 直链没问题，
 * 但无法播 `content://` / `file://` 的本地音乐。改成按 scheme 懒切换：
 * - `http/https` → OkHttpDataSource（与取链同栈：同一 UA / Referer 拦截器，行为一致）；
 * - 其余（`content` / `file` / `asset` / …）→ [DefaultDataSource]（Android 系统栈，支持 SAF）。
 *
 * 切换发生在真正 `open(dataSpec)` 时：那时 `ResolvingDataSource` 已把占位 uri
 * 换成真实直链（云）或原样放行（本地），scheme 已是最终形态。
 *
 * @param context 建 [DefaultDataSource] 用（取 applicationContext，不持有 Activity）
 * @param debug   传给 OkHttp 的日志拦截器开关（`BuildConfig.DEBUG`）
 */
class SchemeAwareDataSourceFactory(
    context: Context,
    debug: Boolean
) : DataSource.Factory {

    private val remoteFactory: DataSource.Factory =
        OkHttpDataSource.Factory(HttpStack.okHttpClient(debug))

    private val localFactory: DataSource.Factory =
        DefaultDataSource.Factory(context.applicationContext)

    override fun createDataSource(): DataSource =
        SwitchedDataSource(remoteFactory = remoteFactory, localFactory = localFactory)

    /**
     * 代理 DataSource：`open()` 时按 scheme 选真正实现，并把已登记的
     * [TransferListener] 转发给实际实现（Media3 的进度/统计回调依赖它）。
     */
    private class SwitchedDataSource(
        private val remoteFactory: DataSource.Factory,
        private val localFactory: DataSource.Factory
    ) : DataSource {

        private val pendingListeners = ArrayList<TransferListener>(2)
        private var delegate: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            pendingListeners.add(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val scheme = dataSpec.uri.scheme?.lowercase()
            val isRemote = scheme == "http" || scheme == "https"
            val factory = if (isRemote) remoteFactory else localFactory
            val dataSource = factory.createDataSource()
            for (listener in pendingListeners) {
                dataSource.addTransferListener(listener)
            }
            delegate = dataSource
            return dataSource.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
            val current = delegate ?: throw IOException("SwitchedDataSource.open() not called")
            return current.read(buffer, offset, readLength)
        }

        override fun getUri(): Uri? = delegate?.uri

        override fun close() {
            delegate?.close()
            delegate = null
        }
    }
}

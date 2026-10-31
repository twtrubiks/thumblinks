package com.twtrubiks.thumblinks

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient
import java.io.File

/** Coil 載入縮圖也走 [AppContainer.http]，跟抓網頁共用連線池與瀏覽器的 User-Agent。 */
class ThumbLinksApplication : Application(), SingletonImageLoader.Factory {
    val container by lazy { AppContainer(this) }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
        .build()
}

val Context.container: AppContainer get() = (applicationContext as ThumbLinksApplication).container

/** 整個 App 共用的東西：連結清單、上傳縮圖的資料夾、網路。 */
class AppContainer(context: Context) {
    val thumbsDir = File(context.filesDir, "thumbs")

    val links: LinkStore by lazy { LinkStore(AndroidSql(context), thumbsDir) }

    /**
     * 整個 App 一個 [OkHttpClient]。有些圖床擋 `okhttp/…` 這種 User-Agent，
     * 所以 Coil 的請求也帶跟抓網頁一樣的手機瀏覽器 User-Agent（[MetaFetcher] 自己的請求本來就有帶）。
     */
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(
                    if (request.header("User-Agent") != null) {
                        request
                    } else {
                        request.newBuilder()
                            .header("User-Agent", MetaFetcher.USER_AGENT)
                            .header("Accept-Language", MetaFetcher.ACCEPT_LANGUAGE)
                            .build()
                    },
                )
            }
            .build()
    }

    val fetcher: MetaFetcher by lazy { MetaFetcher(http) }
}

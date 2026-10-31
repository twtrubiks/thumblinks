package com.twtrubiks.thumblinks

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import java.io.File

/** Coil 載入縮圖也走 [AppContainer.http]，跟抓網頁共用連線池與瀏覽器的 User-Agent。 */
class ThumbLinksApplication : Application(), SingletonImageLoader.Factory {
    val container by lazy { AppContainer(this) }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
        .build()
}

val Context.container: AppContainer get() = (applicationContext as ThumbLinksApplication).container

/** 整個 App 共用的東西：連結清單、上傳縮圖的資料夾、網路、內建瀏覽器。 */
class AppContainer(private val context: Context) {
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

    /** 深色模式（清單右上角切換）。 */
    val theme: ThemeSetting by lazy { ThemeSetting(context.getSharedPreferences("settings", Context.MODE_PRIVATE)) }

    /**
     * 內建瀏覽器的引擎，一個行程只能有一個，第一次打開瀏覽器時才建立（要在主執行緒）。
     * GeckoView 另外開的網頁行程也會跑 [ThumbLinksApplication]，所以不能在 `onCreate` 就建立。
     * 網頁的深淺（`prefers-color-scheme`）跟著 App 的深色模式，切換時馬上換。
     */
    val gecko: GeckoRuntime by lazy {
        val settings = GeckoRuntimeSettings.Builder().preferredColorScheme(theme.mode.gecko).build()
        GeckoRuntime.create(context, settings).also { runtime ->
            theme.onChange = { runtime.settings.preferredColorScheme = it.gecko }
        }
    }

    val ublock: Ublock by lazy { Ublock(gecko, context.getSharedPreferences("browser", Context.MODE_PRIVATE)) }

    /** 開著的分頁，key 是那一頁瀏覽器在導覽 stack 裡的 key；關掉那一頁時 [closeTab]。 */
    private val tabs = mutableMapOf<String, BrowserTab>()

    fun tab(key: String, initialUrl: () -> String?): BrowserTab = tabs.getOrPut(key) { BrowserTab(context, gecko, ublock, initialUrl()) }

    fun closeTab(key: String) {
        tabs.remove(key)?.close()
    }
}

private val ThemeMode.gecko: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
        ThemeMode.LIGHT -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
        ThemeMode.DARK -> GeckoRuntimeSettings.COLOR_SCHEME_DARK
    }

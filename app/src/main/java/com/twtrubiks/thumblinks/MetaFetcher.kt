package com.twtrubiks.thumblinks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 抓一個網頁的結果。 */
sealed interface Fetched {
    /** 抓到了網頁（或網址本身就是圖），縮圖、標題可能沒有。 */
    data class Page(val meta: PageMeta) : Fetched

    /** 連不上、逾時、HTTP 4xx/5xx。可以重試。 */
    data object Unreachable : Fetched
}

/**
 * 抓網頁讀縮圖與標題：GET、跟著轉址、整體 [timeoutMillis] 逾時、最多讀 [MAX_BYTES]（`<meta>` 都在 `<head>`），
 * 帶一般手機瀏覽器的 User-Agent。整個 App 共用一個 [OkHttpClient]。
 */
class MetaFetcher(client: OkHttpClient, timeoutMillis: Long = 15_000) {
    private val client = client.newBuilder()
        .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun fetch(url: String): Fetched = withContext(Dispatchers.IO) { fetchBlocking(url) }

    fun fetchBlocking(url: String): Fetched {
        val request = try {
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", ACCEPT_LANGUAGE)
                .header("Accept", "text/html,application/xhtml+xml,image/*;q=0.9,*/*;q=0.8")
                .build()
        } catch (e: IllegalArgumentException) {
            return Fetched.Unreachable
        }
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return Fetched.Unreachable
                val contentType = response.header("Content-Type")
                if (contentType?.trim()?.startsWith("image/", ignoreCase = true) == true) {
                    // 網址本身就是一張圖：拿它當縮圖，沒有標題
                    return Fetched.Page(PageMeta(imageUrl = url, title = null))
                }
                val bytes = readAtMost(response.body.source(), MAX_BYTES)
                Fetched.Page(PageMeta.parse(bytes, contentType, response.request.url.toString()))
            }
        } catch (e: IOException) {
            Fetched.Unreachable
        }
    }

    private fun readAtMost(source: okio.BufferedSource, limit: Long): ByteArray {
        val buffer = Buffer()
        while (buffer.size < limit) {
            if (source.read(buffer, limit - buffer.size) == -1L) break
        }
        return buffer.readByteArray()
    }

    companion object {
        const val MAX_BYTES = 1024L * 1024
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"
        const val ACCEPT_LANGUAGE = "zh-TW,zh;q=0.9,en;q=0.8"
    }
}

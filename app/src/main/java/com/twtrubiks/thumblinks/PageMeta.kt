package com.twtrubiks.thumblinks

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.nio.charset.Charset

/** 從一個網頁讀到的縮圖網址與標題，兩個都可能沒有。 */
data class PageMeta(val imageUrl: String?, val title: String?) {
    companion object {
        /** 縮圖依序找這些 `<meta>`，`property` 和 `name` 都認；都沒有再找 `<link rel="image_src">`。 */
        private val IMAGE_KEYS = listOf("og:image:secure_url", "og:image", "og:image:url", "twitter:image", "twitter:image:src")
        private val TITLE_KEYS = listOf("og:title", "twitter:title")
        private val CHARSET = Regex("""(?i)charset\s*=\s*"?([^";\s]+)""")
        private val SPACES = Regex("""[\s\p{Z}]+""")

        /**
         * 解析一份 HTML。[finalUrl] 是轉址之後的網址，相對網址以它為準（網頁有 `<base href>` 的話以 base 為準），
         * 只收 `http`／`https` 的圖。編碼交給 jsoup 從 bytes 判斷：先看 [contentType] 的 charset，再看 `<meta charset>`。
         */
        fun parse(bytes: ByteArray, contentType: String?, finalUrl: String): PageMeta {
            val page = requireNotNull(finalUrl.toHttpUrlOrNull()) { "不是網址：$finalUrl" }
            val charset = contentType?.let { CHARSET.find(it)?.groupValues?.get(1) }?.takeIf(::supported)
            val doc = Jsoup.parse(ByteArrayInputStream(bytes), charset, finalUrl)
            val base = doc.selectFirst("base[href]")?.attr("href")?.trim()?.let(page::resolve) ?: page
            return PageMeta(imageUrl = image(doc, base), title = title(doc))
        }

        private fun image(doc: Document, base: HttpUrl): String? {
            val candidates = IMAGE_KEYS.flatMap { metaContents(doc, it) } +
                doc.select("link[href]").filter { it.attr("rel").equals("image_src", ignoreCase = true) }.map { it.attr("href") }
            return candidates.firstNotNullOfOrNull { value ->
                value.trim().takeIf { it.isNotEmpty() }?.let(base::resolve)?.toString()
            }
        }

        private fun title(doc: Document): String? =
            (TITLE_KEYS.flatMap { metaContents(doc, it) } + doc.title())
                .firstNotNullOfOrNull { clean(it).ifEmpty { null } }

        /** 去頭尾空白、連續空白（含換行、全形空白）壓成一個。 */
        fun clean(text: String): String = text.replace(SPACES, " ").trim()

        private fun metaContents(doc: Document, key: String): List<String> =
            doc.select("meta[content]")
                .filter { it.attr("property").equals(key, ignoreCase = true) || it.attr("name").equals(key, ignoreCase = true) }
                .map { it.attr("content") }

        private fun supported(name: String) = try {
            Charset.isSupported(name)
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}

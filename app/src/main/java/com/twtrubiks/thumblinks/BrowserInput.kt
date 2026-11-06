package com.twtrubiks.thumblinks

import java.net.URLEncoder
import java.util.Base64

/** 內建瀏覽器的網址列與連結的規則。純 Kotlin，有 JVM 測試。 */
object BrowserInput {
    /** 網址列輸入的不是網址時用這個搜尋。要換 Google 就改成 `https://www.google.com/search?q=` */
    const val SEARCH = "https://duckduckgo.com/?q="

    private val SCHEME = Regex("""(?i)^(https?)://\S+$""")

    // localhost、IPv4，或是至少有一個點、最後一段是英文字母的網域；後面可以接 :port 與路徑
    private val HOST_LIKE = Regex(
        """(?i)^(localhost|\d{1,3}(\.\d{1,3}){3}|([a-z0-9¡-￿-]+\.)+[a-z¡-￿]{2,})(:\d{1,5})?([/?#]\S*)?$""",
    )

    /**
     * 網址列按下「前往」時要載入的網址：
     * `http(s)://` 開頭的照用、`about:` 照用、看起來像網域的補 `https://`，其他都當搜尋字詞。空白回 null。
     */
    fun resolve(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (SCHEME.matches(t)) return t
        if (t.startsWith("about:", ignoreCase = true) && t.none(Char::isWhitespace)) return t
        if (HOST_LIKE.matches(t)) return "https://$t"
        return SEARCH + URLEncoder.encode(t, Charsets.UTF_8.name())
    }

    /**
     * 瀏覽器自己載得了的網址。其他的（`mailto:`、`tel:`、`intent:`、`market:`…）交給手機上別的 App 開。
     * 空的是 `about:blank`：`window.open()` 不帶網址（之後再設 location）時 GeckoView 給的就是空字串。
     */
    fun isWeb(uri: String): Boolean = uri.isEmpty() || uri.substringBefore(':').lowercase() in WEB_SCHEMES

    /** 可以加到清單的網址（`about:blank`、`moz-extension:` 之類的不行）。 */
    fun canAdd(uri: String): Boolean = uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true)

    /**
     * 網頁載不出來（找不到網站、連不上、憑證有問題、一直轉址…）時顯示的錯誤頁，[reason] 是給使用者看的原因。
     * GeckoView 不帶錯誤頁，`onLoadError` 不給它一頁的話畫面就停在一片白。
     */
    fun errorPage(uri: String, reason: String): String {
        val html = """<!doctype html><html lang="zh-Hant"><head><meta charset="utf-8">
            |<meta name="viewport" content="width=device-width, initial-scale=1"><title>打不開這個網頁</title>
            |<style>:root{color-scheme:light dark}body{font-family:sans-serif;margin:48px 24px;line-height:1.7}
            |h1{font-size:22px}.url{word-break:break-all;opacity:.7;font-size:14px}</style></head>
            |<body><h1>打不開這個網頁</h1><p>${escape(reason)}</p><p class="url">${escape(uri)}</p>
            |<p>按下面的「重新整理」再試一次。</p></body></html>
        """.trimMargin()
        return "data:text/html;charset=utf-8;base64," + Base64.getEncoder().encodeToString(html.toByteArray())
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript", "moz-extension", "resource")
}

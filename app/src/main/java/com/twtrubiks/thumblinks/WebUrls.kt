package com.twtrubiks.thumblinks

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 從使用者貼上或別的 App 分享過來的文字裡找網址。純 Kotlin，有 JVM 測試。 */
object WebUrls {
    // 網址停在空白、引號、角括號，以及中文的標點（分享過來常是「標題」https://… 或 https://…，很好看）
    private val CANDIDATE = Regex("""(?i)https?://[^\s<>"'`「」『』（）【】《》〈〉，。、！？；：…　]+""")

    /** 句尾的英文標點多半不是網址的一部分（「看這個 https://a.example/x.」）。 */
    private const val TRAILING = ".,;:!?"

    /**
     * 取出第一個 `http://` 或 `https://` 開頭的網址，原樣回傳（不正規化，清單裡照字串比對）；找不到回 null。
     * 不會自動補 `https://`。
     */
    fun extract(text: String): String? = CANDIDATE.findAll(text).firstNotNullOfOrNull { match ->
        trimTrailing(match.value).takeIf { it.toHttpUrlOrNull() != null && hasHost(it) }
    }

    /** 清單上顯示的網域：去掉開頭的 `www.`。 */
    fun domain(url: String): String = url.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: url

    private fun hasHost(url: String) = url.substringAfter("://").firstOrNull()?.let { it != '/' } == true

    private fun trimTrailing(url: String): String {
        var end = url.length
        while (end > 0) {
            val c = url[end - 1]
            end = when {
                c in TRAILING -> end - 1
                // 括號裡的網址（例如「（見 https://a.example/x)」）：右括號比左括號多才去掉
                c == ')' && url.take(end).count { it == ')' } > url.take(end).count { it == '(' } -> end - 1
                else -> break
            }
        }
        return url.take(end)
    }
}

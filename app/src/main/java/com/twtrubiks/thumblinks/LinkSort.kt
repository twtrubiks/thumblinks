package com.twtrubiks.thumblinks

import java.text.Collator
import java.util.Locale

/** 清單的排序（清單右上角選，[SortSetting] 記著）。匯出的順序跟畫面一樣。 */
enum class SortOrder(val label: String) {
    NEWEST("新的在上"),
    OLDEST("舊的在上"),
    TITLE("標題"),
    DOMAIN("網域"),
}

/**
 * 把 [links]（[LinkStore.all] 的順序：新的在上）排成 [order]。標題、網域一樣的照新的在上。
 * 標題不分大小寫、中文照語系的順序（Collator）；沒有標題的用網址（畫面上顯示的就是網址）。
 */
fun sortLinks(links: List<Link>, order: SortOrder): List<Link> {
    // Collator 不能多執行緒共用（畫面在主執行緒排、匯出在 IO 執行緒排），每次拿一個新的
    val collator = Collator.getInstance(Locale.TRADITIONAL_CHINESE).apply { strength = Collator.SECONDARY }
    return when (order) {
        SortOrder.NEWEST -> links
        SortOrder.OLDEST -> links.reversed()
        SortOrder.TITLE -> links.sortedWith(compareBy(collator) { it.title ?: it.url })
        SortOrder.DOMAIN -> links.sortedWith(compareBy(collator) { WebUrls.domain(it.url) })
    }
}

/**
 * 清單上看得到的：[favoritesOnly]（右上角的「只看愛心」）時只留按了愛心的、[query]（清單上面的搜尋框）有字時只留找得到的，
 * 再照 [order] 排。
 */
fun visibleLinks(links: List<Link>, order: SortOrder, favoritesOnly: Boolean, query: String = ""): List<Link> {
    val terms = searchTerms(query)
    return sortLinks(links.filter { (!favoritesOnly || it.favorite) && matchesSearch(it, terms) }, order)
}

/** 搜尋框裡的字用空白（含全形空白）切開，每一段都要找得到。 */
fun searchTerms(query: String): List<String> = query.split(SPACES).filter { it.isNotEmpty() }

/** 每一段都出現在標題或網址裡（網域是網址的一部分），不分大小寫。[terms] 是空的就都算找得到。 */
fun matchesSearch(link: Link, terms: List<String>): Boolean = terms.all { term ->
    link.title?.contains(term, ignoreCase = true) == true || link.url.contains(term, ignoreCase = true)
}

private val SPACES = Regex("[\\s\\u3000]+")

package com.twtrubiks.thumblinks

import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 清單上的一筆。縮圖一定有，標題可以沒有。[favorite] 是使用者按了愛心的。 */
data class Link(
    val id: String,
    val url: String,
    val title: String?,
    val thumbnail: Thumbnail,
    val createdAt: OffsetDateTime,
    val favorite: Boolean = false,
)

sealed interface Thumbnail {
    /** 網路上的圖：抓到的 og:image / twitter:image，或使用者自己貼的圖片網址。匯出時直接寫網址。 */
    data class Remote(val url: String) : Thumbnail

    /** 使用者從手機選的圖，縮好存在 `filesDir/thumbs/<fileName>`。匯出時打包進 zip 的 `images/`。 */
    data class Uploaded(val fileName: String) : Thumbnail
}

class DuplicateLinkException(val existing: Link) : Exception("這個網址已經在清單裡了")

/**
 * 連結清單的規則與 SQL，純 Kotlin：手機上接 [AndroidSql]，JVM 測試接 sqlite-jdbc。
 * [thumbsDir] 放使用者上傳的縮圖，刪掉一筆時連圖一起刪。
 */
class LinkStore(
    private val sql: Sql,
    private val thumbsDir: File,
    private val now: () -> OffsetDateTime = OffsetDateTime::now,
) {
    init {
        // 縮圖二選一、一定要有一個：資料庫自己擋，畫面或匯入寫錯也存不進來
        sql.execute(
            """
            CREATE TABLE IF NOT EXISTS links (
                id TEXT PRIMARY KEY,
                url TEXT NOT NULL UNIQUE,
                title TEXT,
                thumbnail_url TEXT,
                thumbnail_file TEXT,
                created_at TEXT NOT NULL,
                CHECK ((thumbnail_url IS NULL) <> (thumbnail_file IS NULL))
            )
            """.trimIndent(),
        )
        // 後來加的欄位：手機上已經有的表用 ALTER TABLE 補上（舊的每一筆都不是愛心），新裝的也走這裡，只有一條路
        val columns = sql.query("PRAGMA table_info(links)").map { it.string("name") }
        if ("favorite" !in columns) sql.execute("ALTER TABLE links ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
    }

    /** 新加的在上面，匯出也照這個順序。 */
    fun all(): List<Link> = sql.query("SELECT * FROM links ORDER BY rowid DESC").map(::toLink)

    fun find(id: String): Link? = sql.query("SELECT * FROM links WHERE id = ?", id).firstOrNull()?.let(::toLink)

    /** 網址照字串比對，不做正規化。 */
    fun findByUrl(url: String): Link? = sql.query("SELECT * FROM links WHERE url = ?", url).firstOrNull()?.let(::toLink)

    /** 標題前後的空白去掉，剩下空字串就當作沒有標題。同一個網址已經在清單裡的話丟 [DuplicateLinkException]。 */
    fun add(url: String, title: String?, thumbnail: Thumbnail): Link = sql.transaction {
        findByUrl(url)?.let { throw DuplicateLinkException(it) }
        val link = Link(
            id = UUID.randomUUID().toString(),
            url = url,
            title = title?.trim()?.ifEmpty { null },
            thumbnail = thumbnail,
            createdAt = now().truncatedTo(ChronoUnit.SECONDS),
        )
        sql.execute(
            "INSERT INTO links (id, url, title, thumbnail_url, thumbnail_file, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            link.id,
            link.url,
            link.title,
            (thumbnail as? Thumbnail.Remote)?.url,
            (thumbnail as? Thumbnail.Uploaded)?.fileName,
            link.createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        )
        link
    }

    /**
     * 改標題、換縮圖（網址不能改）。標題的規則同 [add]。換掉的縮圖如果是上傳的，舊的檔案一起刪掉。
     * 那一筆已經不在了回 null。
     */
    fun update(id: String, title: String?, thumbnail: Thumbnail): Link? {
        val old = find(id) ?: return null
        val link = old.copy(title = title?.trim()?.ifEmpty { null }, thumbnail = thumbnail)
        sql.execute(
            "UPDATE links SET title = ?, thumbnail_url = ?, thumbnail_file = ? WHERE id = ?",
            link.title,
            (thumbnail as? Thumbnail.Remote)?.url,
            (thumbnail as? Thumbnail.Uploaded)?.fileName,
            id,
        )
        val oldFile = (old.thumbnail as? Thumbnail.Uploaded)?.fileName
        if (oldFile != null && oldFile != (thumbnail as? Thumbnail.Uploaded)?.fileName) File(thumbsDir, oldFile).delete()
        return link
    }

    /** 按愛心、取消愛心。只改這一個欄位，不會蓋掉同時在編輯頁改的標題、縮圖。 */
    fun setFavorite(id: String, favorite: Boolean) {
        sql.execute("UPDATE links SET favorite = ? WHERE id = ?", if (favorite) 1 else 0, id)
    }

    fun delete(id: String) {
        val link = find(id) ?: return
        sql.execute("DELETE FROM links WHERE id = ?", id)
        (link.thumbnail as? Thumbnail.Uploaded)?.let { File(thumbsDir, it.fileName).delete() }
    }

    private fun toLink(row: Row) = Link(
        id = row.string("id"),
        url = row.string("url"),
        title = row.stringOrNull("title"),
        thumbnail = row.stringOrNull("thumbnail_url")?.let { Thumbnail.Remote(it) }
            ?: Thumbnail.Uploaded(row.string("thumbnail_file")),
        createdAt = OffsetDateTime.parse(row.string("created_at")),
        favorite = row.long("favorite") != 0L,
    )
}

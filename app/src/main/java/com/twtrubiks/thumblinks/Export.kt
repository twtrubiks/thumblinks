package com.twtrubiks.thumblinks

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStream
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportFormat(val extension: String, val mimeType: String, val label: String) {
    MARKDOWN("md", "text/markdown", "Markdown"),
    JSON("json", "application/json", "JSON"),
}

/**
 * 整份清單匯出成 Markdown 或 JSON，順序照 [links]（畫面上新的在上面）。純 Kotlin，有 JVM 測試。
 * 都是網路上的縮圖就是單一檔案；有任何一筆是上傳的縮圖，就打包成 zip：`links.md`／`links.json` ＋ `images/<id>.jpg`。
 * 匯出的檔案可以再匯入回來（[Import]）：JSON 有時間與愛心，Markdown 只有網址、標題、縮圖。
 */
object Export {
    const val ZIP_MIME = "application/zip"

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun needsZip(links: List<Link>) = links.any { it.thumbnail is Thumbnail.Uploaded }

    fun mimeType(links: List<Link>, format: ExportFormat) = if (needsZip(links)) ZIP_MIME else format.mimeType

    /** 預設檔名：`thumblinks-<YYYY-MM-DD>.<md|json|zip>`。 */
    fun fileName(links: List<Link>, format: ExportFormat, date: LocalDate): String =
        "thumblinks-$date.${if (needsZip(links)) "zip" else format.extension}"

    /** 上傳的圖在 zip 裡的位置，Markdown 與 JSON 都用這個相對路徑引用。 */
    fun imagePath(link: Link) = "images/${link.id}.jpg"

    private fun thumbnailRef(link: Link) = when (val t = link.thumbnail) {
        is Thumbnail.Remote -> t.url
        is Thumbnail.Uploaded -> imagePath(link)
    }

    /** 一筆一行、筆與筆之間空一行。沒有標題時替代文字用網址。 */
    fun markdown(links: List<Link>): String = links.joinToString("\n") { link ->
        "[![${altText(link.title ?: link.url)}](${linkTarget(thumbnailRef(link))})](${linkTarget(link.url)})\n"
    }

    /** 替代文字裡的 `\`、`[`、`]` 前面加 `\`，換行換成空白。 */
    fun altText(text: String): String = text
        .replace(Regex("\r\n|\r|\n"), " ")
        .replace(Regex("""[\\\[\]]""")) { "\\" + it.value }

    /** 網址裡的空白、`(`、`)`、`<`、`>` 用百分比編碼，其餘照原樣。 */
    fun linkTarget(url: String): String = buildString {
        for (c in url) {
            when (c) {
                ' ' -> append("%20")
                '(' -> append("%28")
                ')' -> append("%29")
                '<' -> append("%3C")
                '>' -> append("%3E")
                else -> append(c)
            }
        }
    }

    fun json(links: List<Link>, exportedAt: OffsetDateTime): String = json.encodeToString(
        ExportFile.serializer(),
        ExportFile(
            exportedAt = time(exportedAt),
            links = links.map { ExportLink(it.url, it.title, thumbnailRef(it), time(it.createdAt), it.favorite) },
        ),
    ) + "\n"

    private fun time(t: OffsetDateTime) = t.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun text(links: List<Link>, format: ExportFormat, exportedAt: OffsetDateTime) = when (format) {
        ExportFormat.MARKDOWN -> markdown(links)
        ExportFormat.JSON -> json(links, exportedAt)
    }

    /**
     * 寫進 [out]（不會關掉它）。上傳的圖從 [thumbsDir] 讀；檔案不見了丟 [java.io.FileNotFoundException]，
     * 不匯出一份引用了不存在的圖的 zip。
     */
    fun write(links: List<Link>, format: ExportFormat, exportedAt: OffsetDateTime, thumbsDir: File, out: OutputStream) {
        val text = text(links, format, exportedAt).toByteArray(Charsets.UTF_8)
        if (!needsZip(links)) {
            out.write(text)
            out.flush()
            return
        }
        val zip = ZipOutputStream(out)
        zip.putNextEntry(ZipEntry("links.${format.extension}"))
        zip.write(text)
        zip.closeEntry()
        for (link in links) {
            val uploaded = link.thumbnail as? Thumbnail.Uploaded ?: continue
            val bytes = File(thumbsDir, uploaded.fileName).readBytes()
            zip.putNextEntry(ZipEntry(imagePath(link)))
            zip.write(bytes)
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
    }
}

@Serializable
private data class ExportFile(
    @SerialName("exported_at") val exportedAt: String,
    val links: List<ExportLink>,
)

@Serializable
private data class ExportLink(
    val url: String,
    val title: String?,
    val thumbnail: String,
    @SerialName("created_at") val createdAt: String,
    val favorite: Boolean,
)

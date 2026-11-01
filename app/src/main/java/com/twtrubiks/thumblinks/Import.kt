package com.twtrubiks.thumblinks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * 檔案裡的一筆。[thumbnail] 是網址，或 zip 裡的相對路徑（`images/<id>.jpg`）。
 * Markdown 沒有時間與愛心：[createdAt] 是 null（加的時候用現在）、[favorite] 是 false。
 */
data class ImportedLink(
    val url: String,
    val title: String?,
    val thumbnail: String,
    val createdAt: OffsetDateTime?,
    val favorite: Boolean,
)

/** 整個檔案讀不了：訊息直接給使用者看。 */
class ImportException(message: String) : Exception(message)

/**
 * 讀進來的檔案（[Import.read]）：一筆一筆，與 zip 裡的圖。zip 是開著的，用完要 [close]。
 * [broken] 是看得出是一筆、但缺網址或縮圖的（JSON 少了欄位），算進「讀不出來」。
 */
class ImportFile internal constructor(
    val links: List<ImportedLink>,
    val broken: Int,
    private val zip: ZipFile?,
) : Closeable {
    /** zip 裡的圖；不是 zip、沒有這個檔、太大都是 null。 */
    fun image(path: String): ByteArray? {
        val zip = zip ?: return null
        val entry = zip.getEntry(path)?.takeUnless { it.isDirectory } ?: return null
        return zip.getInputStream(entry).use { it.readAtMost(Import.MAX_IMAGE) }
    }

    fun hasImage(path: String): Boolean = zip?.getEntry(path)?.let { !it.isDirectory && it.size <= Import.MAX_IMAGE } == true

    override fun close() {
        zip?.close()
    }
}

/** 匯入前先算好：要加的（照加進去的順序）、已經在清單裡的、讀不出來的。 */
data class ImportPlan(val total: Int, val toAdd: List<ImportedLink>, val duplicates: Int, val broken: Int)

/** 匯入完的結果。圖解不開的那幾筆算進 [broken]。 */
data class ImportResult(val added: Int, val duplicates: Int, val broken: Int)

/**
 * 把 [Export] 匯出的檔案讀回來：Markdown、JSON，或打包了上傳縮圖的 zip。純 Kotlin，有 JVM 測試。
 * 不看副檔名，看內容：`PK` 開頭是 zip，`{` 開頭是 JSON，其他當 Markdown。
 * 網址已經在清單裡的跳過（手機上那筆不動）；JSON 的時間與愛心照檔案，Markdown 沒有這兩樣。
 */
object Import {
    /** 文字檔（links.md／links.json）最大多少：一筆不到 1 KB，幾萬筆也夠。 */
    const val MAX_TEXT = 20L * 1024 * 1024

    /** zip 裡的一張圖最大多少。App 自己匯出的都在 1 MB 以內（長邊 1024 的 JPEG）。 */
    const val MAX_IMAGE = 20L * 1024 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        // "favorite": null 之類的當作沒寫
        coerceInputValues = true
    }

    /** 讀 [file]。看不懂、太大、zip 壞掉丟 [ImportException]；讀到了就要記得 [ImportFile.close]。 */
    fun read(file: File): ImportFile {
        val isZip = file.inputStream().use { it.readNBytesCompat(2) }.contentEquals(byteArrayOf('P'.code.toByte(), 'K'.code.toByte()))
        val result = if (isZip) readZip(file) else parse(text(file.inputStream()), zip = null)
        if (result.links.isEmpty() && result.broken == 0) {
            result.close()
            throw ImportException("檔案裡找不到連結（要是 ThumbLinks 匯出的 Markdown、JSON 或 zip）")
        }
        return result
    }

    private fun readZip(file: File): ImportFile {
        val zip = try {
            ZipFile(file)
        } catch (e: ZipException) {
            throw ImportException("這個 zip 壞掉了，打不開")
        }
        try {
            val entry = zip.getEntry("links.json") ?: zip.getEntry("links.md")
                ?: throw ImportException("zip 裡沒有 links.json 或 links.md，不是 ThumbLinks 匯出的")
            return parse(zip.getInputStream(entry).use(::text), zip)
        } catch (e: Exception) {
            zip.close()
            throw e
        }
    }

    private fun parse(text: String, zip: ZipFile?): ImportFile =
        if (text.trimStart().startsWith("{")) json(text, zip) else markdown(text, zip)

    private fun text(input: InputStream): String {
        val bytes = input.use { it.readAtMost(MAX_TEXT) } ?: throw ImportException("檔案太大了，不像是 ThumbLinks 匯出的")
        return bytes.toString(Charsets.UTF_8).removePrefix("﻿")
    }

    private fun json(text: String, zip: ZipFile?): ImportFile {
        val file = try {
            json.decodeFromString(JsonFile.serializer(), text)
        } catch (e: SerializationException) {
            throw ImportException("看不懂這個 JSON 檔")
        } catch (e: IllegalArgumentException) {
            throw ImportException("看不懂這個 JSON 檔")
        }
        val links = file.links.mapNotNull { link ->
            ImportedLink(
                url = link.url?.trim() ?: return@mapNotNull null,
                title = cleanTitle(link.title),
                thumbnail = link.thumbnail?.trim() ?: return@mapNotNull null,
                createdAt = link.createdAt?.let(::parseTime),
                favorite = link.favorite,
            )
        }
        return ImportFile(links, file.links.size - links.size, zip)
    }

    /**
     * [Export.markdown] 的一行：`[![替代文字](縮圖)](網址)`。替代文字跟網址一樣就是沒有標題。
     * 其他的行（空行、別的內容）不理。
     */
    fun parseMarkdown(text: String): List<ImportedLink> = text.lineSequence().mapNotNull { line ->
        val (alt, thumbnail, url) = MARKDOWN_LINE.matchEntire(line.trim())?.destructured ?: return@mapNotNull null
        val realUrl = decodeTarget(url)
        val title = ESCAPED.replace(alt) { it.groupValues[1] }
        ImportedLink(realUrl, cleanTitle(title.takeIf { it != realUrl }), decodeTarget(thumbnail), createdAt = null, favorite = false)
    }.toList()

    private fun markdown(text: String, zip: ZipFile?) = ImportFile(parseMarkdown(text), broken = 0, zip)

    // 替代文字裡的 \、[、] 前面都加了 \（Export.altText），網址裡沒有空白與括號（Export.linkTarget）
    private val MARKDOWN_LINE = Regex("""\[!\[((?:\\.|[^\\\[\]])*)]\(([^\s()]+)\)]\(([^\s()]+)\)""")
    private val ESCAPED = Regex("""\\(.)""")

    /** [Export.linkTarget] 反過來：只解它編的那五個，其他的 `%xx` 照原樣。 */
    fun decodeTarget(url: String): String = url
        .replace("%20", " ")
        .replace("%28", "(")
        .replace("%29", ")")
        .replace("%3C", "<")
        .replace("%3E", ">")

    private fun cleanTitle(title: String?) = title?.replace(Regex("\\s+"), " ")?.trim()?.ifEmpty { null }

    private fun parseTime(text: String) = try {
        OffsetDateTime.parse(text.trim())
    } catch (e: DateTimeParseException) {
        null
    }

    /**
     * 算出要加的、跳過的。[exists] 問網址是不是已經在清單裡（照字串比對，跟新增一樣）；同一個檔案裡重複的只留第一筆。
     * 網址要是 `http(s)://`，縮圖要是 `http(s)://` 的網址或 zip 裡有的圖，不然算讀不出來。
     *
     * 加的順序決定清單上的位置（新的在上用的是加入的順序）：照檔案裡的時間從舊到新加，檔案的排序是什麼都一樣；
     * 時間一樣（或沒有時間，例如 Markdown）的，檔案裡下面的先加——匯出預設新的在上，這樣加完順序跟原本一樣。
     */
    fun plan(file: ImportFile, exists: (String) -> Boolean): ImportPlan {
        val seen = HashSet<String>()
        var duplicates = 0
        var broken = file.broken
        val valid = file.links.filter { link ->
            when {
                !isWebUrl(link.url) || !(isWebUrl(link.thumbnail) || file.hasImage(link.thumbnail)) -> {
                    broken++
                    false
                }
                !seen.add(link.url) || exists(link.url) -> {
                    duplicates++
                    false
                }
                else -> true
            }
        }
        val ordered = valid.withIndex()
            .sortedWith(compareBy<IndexedValue<ImportedLink>, OffsetDateTime?>(nullsLast()) { it.value.createdAt }.thenByDescending { it.index })
            .map { it.value }
        return ImportPlan(file.links.size + file.broken, ordered, duplicates, broken)
    }

    /**
     * 照 [plan] 一筆一筆加進 [store]。zip 裡的圖交給 [saveImage] 存進 [thumbsDir]（手機上是 [ThumbPrep.save]：
     * 轉成 JPEG、長邊 1024 以內），解不開回 null 的那筆算讀不出來。匯入到一半使用者剛好加了同一個網址，算重複。
     */
    fun run(
        plan: ImportPlan,
        file: ImportFile,
        store: LinkStore,
        thumbsDir: File,
        saveImage: (ByteArray, File) -> String?,
    ): ImportResult {
        var added = 0
        var duplicates = plan.duplicates
        var broken = plan.broken
        for (link in plan.toAdd) {
            if (store.findByUrl(link.url) != null) {
                duplicates++
                continue
            }
            val thumbnail = if (isWebUrl(link.thumbnail)) {
                Thumbnail.Remote(link.thumbnail)
            } else {
                val name = file.image(link.thumbnail)?.let { saveImage(it, thumbsDir) }
                if (name == null) {
                    broken++
                    continue
                }
                Thumbnail.Uploaded(name)
            }
            try {
                store.add(link.url, link.title, thumbnail, link.createdAt, link.favorite)
                added++
            } catch (e: DuplicateLinkException) {
                (thumbnail as? Thumbnail.Uploaded)?.let { File(thumbsDir, it.fileName).delete() }
                duplicates++
            }
        }
        return ImportResult(added, duplicates, broken)
    }

    private fun isWebUrl(text: String) =
        (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)) && text.toHttpUrlOrNull() != null
}

/** 最多讀 [max] bytes，超過回 null（zip 裡寫的大小不一定可信，邊讀邊數）。 */
internal fun InputStream.readAtMost(max: Long): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = read(buffer)
        if (n < 0) return out.toByteArray()
        total += n
        if (total > max) return null
        out.write(buffer, 0, n)
    }
}

/** `InputStream.readNBytes` 要 Android 13，自己讀。 */
private fun InputStream.readNBytesCompat(n: Int): ByteArray {
    val buffer = ByteArray(n)
    var read = 0
    while (read < n) {
        val count = read(buffer, read, n - read)
        if (count < 0) break
        read += count
    }
    return buffer.copyOf(read)
}

/** 匯入時讀的 JSON：欄位都可以少（少了網址或縮圖的那筆算讀不出來），多的欄位不理。 */
@Serializable
private data class JsonFile(val links: List<JsonLink> = emptyList())

@Serializable
private data class JsonLink(
    val url: String? = null,
    val title: String? = null,
    val thumbnail: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val favorite: Boolean = false,
)

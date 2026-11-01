package com.twtrubiks.thumblinks

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.regex.Pattern
import java.util.zip.ZipInputStream

/**
 * 系統的畫面：照片挑選器、存檔的檔案畫面。這些不是 App 畫的，每個 Android 版本長得不一樣
 * （Android 9、10 是檔案畫面，Android 11 以後是照片挑選器），只能在模擬器上點一次才知道接不接得上。
 * 不錄影（`record.sh` 在錄影之後、先清掉 App 資料再跑），截圖存成 `system-*.png`。
 */
@RunWith(AndroidJUnit4::class)
class SystemUiTest : UiTest() {
    private val photoName = "thumblinks-picker.jpg"
    private val site = FakeSite().start()

    @After
    fun stopSite() = site.close()

    /**
     * 沒有縮圖的網頁 → 從系統的挑選器選一張相簿裡的照片 → 加入；
     * 再匯出成 Markdown：有上傳的圖，存到 Download 的會是 zip，解開核對 `links.md` 與 `images/`。
     */
    @Test
    fun photoPickerAndExportZip() {
        clearLinks()
        // 同名的檔案在的話，系統會存成「… (1).zip」
        val exportName = "thumblinks-${LocalDate.now()}.zip"
        device.executeShellCommand("rm -f /sdcard/Download/$exportName")
        putPhotoInGallery()
        launchApp()
        waitFor(By.text("還沒有連結"), timeout = 30_000)

        setClipboard(site.url("/plain"))
        find(By.res("add_button")).click()
        find(By.text("貼上")).click()
        waitFor(By.text("這個網頁沒有提供縮圖"), timeout = 20_000)
        find(By.text("從手機選圖")).click()
        pickPhoto()
        // 挑好之後回到新增頁，縮圖已經縮好、轉成 JPEG 存進 App 裡
        waitFor(By.res("thumb_ready"), timeout = 20_000)
        pause(1000)
        screenshot("system-02-photo-picked")
        find(By.text("加入")).click()
        backOnList()

        val link = container.links.all().single()
        val thumb = File(container.thumbsDir, (link.thumbnail as Thumbnail.Uploaded).fileName)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(thumb.path, bounds)
        // 相簿裡的是 1600x1200：長邊縮到 1024
        assertEquals("1024x768", "${bounds.outWidth}x${bounds.outHeight}")
        assertEquals("image/jpeg", bounds.outMimeType)

        // 匯出：選 Markdown，系統的存檔畫面存到 Download
        find(By.text("匯出")).click()
        find(By.text("Markdown")).click()
        openDownloads()
        pause(1000)
        screenshot("system-03-save-screen")
        find(By.text(Pattern.compile("(?i)save|儲存"))).click()
        waitFor(By.text("匯出好了"), timeout = 20_000)
        pause(1000)
        screenshot("system-04-exported")

        val entries = unzip(readDownload(exportName))
        assertEquals(listOf("links.md", "images/${link.id}.jpg"), entries.keys.toList())
        assertEquals(
            "[![${FakeSite.PLAIN_TITLE}](images/${link.id}.jpg)](${site.url("/plain")})\n",
            entries.getValue("links.md").toString(Charsets.UTF_8),
        )
        assertArrayEquals(thumb.readBytes(), entries.getValue("images/${link.id}.jpg"))
    }

    /** 放一張照片進相簿。Android 10 以後 App 可以直接寫進 MediaStore；Android 9 要權限，交給 shell 複製再掃描。 */
    private fun putPhotoInGallery() {
        val bitmap = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(140, 178, 217))
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, photoName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ThumbLinks")
            }
            val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        } else {
            val staged = File(context.getExternalFilesDir(null), photoName).apply { writeBytes(bytes) }
            device.executeShellCommand("cp ${staged.absolutePath} /sdcard/Pictures/$photoName")
            device.executeShellCommand(
                "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/$photoName",
            )
            staged.delete()
            SystemClock.sleep(2000)
        }
    }

    /**
     * 系統的挑選器：Android 11 以後是照片挑選器（格子、只有縮圖），Android 9、10 是檔案畫面（看得到檔名）。
     * 剛放進去的那張是最新的，排在第一個。
     */
    private fun pickPhoto() {
        val choices: List<BySelector> = listOf(
            By.text(photoName),
            By.res(Pattern.compile(".*:id/icon_thumbnail")),
            By.descStartsWith("Photo taken"),
        )
        val picked = poll(20_000) { choices.firstNotNullOfOrNull { device.findObject(it) } }
        pause(1000)
        screenshot("system-01-picker")
        requireNotNull(picked) { "挑選器裡找不到剛放進相簿的照片" }.click()
    }

    /**
     * 檔案畫面切到 Download（左上角的選單）。存檔畫面一打開可能停在「最近」，那裡不能存。
     * 標題列上可能也寫著 Downloads（被選單蓋住，點不到），選單裡的那個在它下面；選單收起來之後下面的按鈕才點得到。
     */
    private fun openDownloads() {
        val roots = poll(15_000) { device.findObject(By.desc("Show roots")) } ?: return
        roots.click()
        waitFor(By.text("Downloads"))
        pause(800)
        device.findObjects(By.text("Downloads")).maxBy { it.visibleCenter.y }.click()
        waitGone(By.text(Pattern.compile("Save to|Open from")))
        pause(500)
    }

    /**
     * 用 shell 讀出 Download 裡那份檔案的原始 bytes（zip 不能當字串讀）。
     * 這裡的指令不經過 sh，不能用引號或萬用字元，所以檔名要確定。
     */
    private fun readDownload(name: String): ByteArray {
        val pfd = instrumentation.uiAutomation.executeShellCommand("cat /sdcard/Download/$name")
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes())
            }
        }
    }
}

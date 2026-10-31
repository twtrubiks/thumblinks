package com.twtrubiks.thumblinks

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 只有模擬器才測得到的事，`record.sh` 在錄影前先跑、不錄影：
 * [LinkStore] 的 SQL 在 Android 9 內建的 SQLite（3.22）上跑一輪——JVM 測試用的新版 SQLite 不會擋下新語法；
 * 上傳的縮圖用真的 Bitmap 與 ExifInterface 縮圖轉正（[ThumbPrep]）。
 */
@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sql = AndroidSql(context, DB)
    private val thumbs = File(context.cacheDir, "device-test-thumbs").apply { mkdirs() }

    @After
    fun cleanUp() {
        sql.close()
        context.deleteDatabase(DB)
        thumbs.deleteRecursively()
    }

    @Test
    fun linkStoreOnAndroidSqlite() {
        val store = LinkStore(sql, thumbs)
        val remote = store.add("https://a.example/", "  A  ", Thumbnail.Remote("https://a.example/og.jpg"))
        File(thumbs, "b.jpg").writeText("jpeg")
        val uploaded = store.add("https://b.example/", "", Thumbnail.Uploaded("b.jpg"))

        assertEquals(listOf(uploaded, remote), store.all())
        assertEquals("A", remote.title)
        assertNull(uploaded.title)
        assertThrows(DuplicateLinkException::class.java) {
            store.add("https://a.example/", null, Thumbnail.Uploaded("x.jpg"))
        }
        // 縮圖兩個都沒有的資料，Android 的 SQLite 一樣擋得下來
        assertThrows(Exception::class.java) {
            sql.execute("INSERT INTO links (id, url, created_at) VALUES ('x', 'https://x/', '')")
        }

        File(thumbs, "c.jpg").writeText("jpeg")
        val updated = store.update(uploaded.id, "改過的標題", Thumbnail.Uploaded("c.jpg"))
        assertEquals(updated, store.findByUrl("https://b.example/"))
        assertFalse("換掉的上傳縮圖要刪", File(thumbs, "b.jpg").exists())

        store.delete(uploaded.id)
        assertEquals(listOf(remote), store.all())
        assertFalse(File(thumbs, "c.jpg").exists())
    }

    /** 直拍的照片：像素是橫的、方向記在 EXIF。存下來的要轉正、長邊縮到 1024px 以內、是 JPEG。 */
    @Test
    fun portraitPhotoIsTurnedUprightAndScaledDown() {
        val file = File(context.cacheDir, "portrait.jpg")
        file.writeBytes(jpeg(4000, 3000, topHalfRed = true))
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val name = requireNotNull(ThumbPrep.save(file.readBytes(), thumbs))
        file.delete()

        val saved = File(thumbs, name)
        assertTrue("存成 <uuid>.jpg：$name", Regex("[0-9a-f-]{36}\\.jpg").matches(name))
        assertJpeg(saved.readBytes())
        val bitmap = BitmapFactory.decodeFile(saved.path)
        assertEquals(768, bitmap.width)
        assertEquals(1024, bitmap.height)
        // 順時針轉 90 度：原本在上面的紅色到了右邊——只看長寬的話，轉錯方向也會過
        assertRedOnTheRight(bitmap)
    }

    /**
     * 手機拍的 HEIC：轉成 JPEG；方向跟手機拍的一樣記在 HEIF 的 irot 裡（不是 EXIF），像素是橫的。
     * `assets/portrait.heic` 取自 treesay（pillow-heif 產生）：400x300、上半紅下半藍、要順時針轉 90 度才正。
     */
    @Test
    fun heicPhotoBecomesAnUprightJpeg() {
        val heic = InstrumentationRegistry.getInstrumentation().context.assets.open("portrait.heic").use { it.readBytes() }
        val name = requireNotNull(ThumbPrep.save(heic, thumbs)) { "這台的 Android 解不開 HEIC" }

        val bytes = File(thumbs, name).readBytes()
        assertJpeg(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(300, bitmap.width)
        assertEquals(400, bitmap.height)
        assertRedOnTheRight(bitmap)
    }

    /** 小圖不放大；PNG 的透明處鋪白底（JPEG 沒有透明，不鋪會變黑）。 */
    @Test
    fun smallTransparentPngStaysSmallOnWhite() {
        val png = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        Canvas(png).drawRect(0f, 0f, 100f, 100f, Paint().apply { color = Color.rgb(40, 80, 200) })
        val bytes = ByteArrayOutputStream().also { png.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

        val saved = File(thumbs, requireNotNull(ThumbPrep.save(bytes, thumbs)))

        assertJpeg(saved.readBytes())
        val bitmap = BitmapFactory.decodeFile(saved.path)
        assertEquals(200, bitmap.width)
        assertEquals(100, bitmap.height)
        val transparent = bitmap.getPixel(150, 50)
        assertTrue("透明的地方要是白的：${Integer.toHexString(transparent)}", Color.red(transparent) > 240 && Color.blue(transparent) > 240)
        assertTrue(Color.blue(bitmap.getPixel(50, 50)) > 150)
    }

    @Test
    fun brokenImageIsRejected() {
        assertNull(ThumbPrep.save(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3), thumbs))
        assertNull(ThumbPrep.save("<html>不是圖</html>".toByteArray(), thumbs))
        assertEquals(0, thumbs.listFiles()!!.size)
    }

    private fun assertJpeg(bytes: ByteArray) {
        assertTrue("要是 JPEG", bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte())
    }

    private fun assertRedOnTheRight(bitmap: Bitmap) {
        val left = bitmap.getPixel(bitmap.width / 4, bitmap.height / 2)
        val right = bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height / 2)
        assertTrue("左邊應該是藍的：${Integer.toHexString(left)}", Color.blue(left) > Color.red(left))
        assertTrue("右邊應該是紅的：${Integer.toHexString(right)}", Color.red(right) > Color.blue(right))
    }

    private fun jpeg(width: Int, height: Int, topHalfRed: Boolean): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(40, 80, 200))
            // 上半紅、下半藍：轉正之後看得出轉的方向對不對
            if (topHalfRed) drawRect(0f, 0f, width.toFloat(), height / 2f, Paint().apply { color = Color.rgb(220, 60, 40) })
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private companion object {
        const val DB = "device-test.db"
    }
}

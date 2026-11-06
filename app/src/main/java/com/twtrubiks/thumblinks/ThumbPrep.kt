package com.twtrubiks.thumblinks

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 使用者從手機選的縮圖：依 EXIF 轉正、長邊縮到 [MAX_EDGE] 以內、存成 JPEG（HEIC、PNG 一樣轉成 JPEG）。
 * 只留整理好的這一份，不留挑選器給的 URI（那個讀取權限是暫時的）。DeviceTest 用真的 Bitmap 測。
 */
object ThumbPrep {
    const val MAX_EDGE = 1024
    const val JPEG_QUALITY = 85

    /** 存成 `dir/<uuid>.jpg`，回檔名；解不開（不是圖、壞掉）回 null。 */
    fun save(bytes: ByteArray, dir: File): String? {
        val bitmap = prepare(bytes) ?: return null
        dir.mkdirs()
        val name = "${UUID.randomUUID()}.jpg"
        val file = File(dir, name)
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        } catch (e: java.io.IOException) {
            file.delete()
            throw e
        }
        return name
    }

    /**
     * 匯入的 zip 裡的圖：已經是整理好的（JPEG、長邊 [MAX_EDGE] 以內、不用轉）就原樣存，
     * 不然每搬一次手機就重新壓一次 JPEG、越來越糊；其他的（別人做的 zip）照 [save] 整理。
     */
    fun saveImported(bytes: ByteArray, dir: File): String? {
        val isJpeg = bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        // 沒有 EXIF 的 JPEG（App 自己存的就是）ExifInterface 給的是 UNDEFINED（0），不是 NORMAL
        val ready = isJpeg && bounds.outWidth in 1..MAX_EDGE && bounds.outHeight in 1..MAX_EDGE &&
            orientation(bytes) in setOf(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL)
        if (!ready) return save(bytes, dir)
        dir.mkdirs()
        val name = "${UUID.randomUUID()}.jpg"
        File(dir, name).writeBytes(bytes)
        return name
    }

    /** 解圖、縮到長邊 [MAX_EDGE] 以內、轉正。透明的地方鋪白底（JPEG 沒有透明，不鋪會變黑）。 */
    fun prepare(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val scale = minOf(1.0, MAX_EDGE.toDouble() / max(bounds.outWidth, bounds.outHeight))
        val width = max(1, (bounds.outWidth * scale).roundToInt())
        val height = max(1, (bounds.outHeight * scale).roundToInt())
        val bitmap = decodeScaled(bytes, bounds.outWidth, bounds.outHeight, width, height) ?: return null
        val upright = rotate(bitmap, orientation(bytes))
        if (!upright.hasAlpha()) return upright
        val opaque = createBitmap(upright.width, upright.height)
        Canvas(opaque).apply {
            drawColor(Color.WHITE)
            drawBitmap(upright, 0f, 0f, null)
        }
        return opaque
    }

    /** 先用 inSampleSize 粗縮（省記憶體，手機拍的照片動輒 4000px），再精確縮到目標尺寸。 */
    private fun decodeScaled(bytes: ByteArray, srcWidth: Int, srcHeight: Int, width: Int, height: Int): Bitmap? {
        var sample = 1
        while (srcWidth / (sample * 2) >= width && srcHeight / (sample * 2) >= height) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return if (decoded.width == width && decoded.height == height) decoded else decoded.scale(width, height)
    }

    /** 手機拍的 HEIC 方向記在 HEIF 的 irot 裡，ExifInterface 一樣讀得到。 */
    private fun orientation(bytes: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** EXIF 的八種方向轉回正向（對照 Glide 的 TransformationUtils）。 */
    private fun rotate(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}

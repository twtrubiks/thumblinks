package com.twtrubiks.thumblinks

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.net.InetAddress

/**
 * 模擬器上的假網站（抓縮圖的 OkHttp：debug 版只對 127.0.0.1 放行明碼連線；內建瀏覽器的 GeckoView 不受這個限制），測試不必連外：
 * - `/hiking`：有 og:image 與 og:title 的網頁（也是內建瀏覽器裡看得到內容的文章）
 * - `/autumn`：另一篇，在瀏覽器裡用網址列打開、按「加到清單」
 * - `/plain`：只有 `<title>`、沒有縮圖的網頁
 * - `/ads`：載入 Google 廣告 script 的網頁，uBlock Origin（EasyList）會擋掉它
 * - `/og.png`、`/photo.png`：圖片本身（用 Bitmap 畫的 PNG）
 */
class FakeSite : AutoCloseable {
    private val server = MockWebServer()
    private val ogImage by lazy { png(sky = Color.rgb(140, 190, 235), ground = Color.rgb(196, 168, 92)) }
    private val photo by lazy { png(sky = Color.rgb(250, 190, 140), ground = Color.rgb(70, 120, 170)) }

    fun start(): FakeSite {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/hiking" -> html(
                    """
                    <meta property="og:title" content="$HIKING_TITLE">
                    <meta property="og:image" content="/og.png">
                    <title>$HIKING_TITLE - 部落格</title>
                    """,
                    article(HIKING_TITLE, "/og.png", "十月底的陽明山，擎天崗一路到冷水坑，整片芒草被風吹成銀白色。"),
                )
                "/autumn" -> html(
                    """
                    <meta property="og:title" content="$AUTUMN_TITLE">
                    <meta property="og:image" content="/photo.png">
                    <title>$AUTUMN_TITLE</title>
                    """,
                    article(AUTUMN_TITLE, "/photo.png", "傍晚的步道換上橘色的光，走到山頂剛好看見夕陽。"),
                )
                "/plain" -> html("<title>$PLAIN_TITLE</title>")
                "/ads" -> html(
                    """
                    <title>有廣告的網頁</title>
                    <script async src="https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"></script>
                    """,
                    article("有廣告的網頁", "/og.png", "這一頁載入了 Google 的廣告 script，uBlock Origin 會把它擋掉。"),
                )
                "/og.png" -> image(ogImage)
                "/photo.png" -> image(photo)
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        return this
    }

    fun url(path: String) = "http://127.0.0.1:${server.port}$path"

    override fun close() = server.close()

    private fun html(head: String, body: String = "<p>內文</p>") = MockResponse.Builder()
        .addHeader("Content-Type", "text/html; charset=utf-8")
        .body(
            """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            |$head</head><body style="font-family: sans-serif; margin: 0 20px; line-height: 1.7">$body</body></html>
            """.trimMargin(),
        )
        .build()

    private fun article(title: String, image: String, text: String) =
        """<h1 style="font-size: 26px">$title</h1><img src="$image" style="width: 100%; border-radius: 12px">
        |<p>$text</p><p>山行筆記・2026 年 10 月</p>
        """.trimMargin()

    private fun image(bytes: ByteArray) = MockResponse.Builder()
        .addHeader("Content-Type", "image/png")
        .body(Buffer().write(bytes))
        .build()

    /** 一張 1200x630 的風景：漸層的天空、太陽、兩座山。 */
    private fun png(sky: Int, ground: Int): ByteArray {
        val w = 1200f
        val h = 630f
        val bitmap = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawRect(0f, 0f, w, h, Paint().apply { shader = LinearGradient(0f, 0f, 0f, h, sky, Color.WHITE, Shader.TileMode.CLAMP) })
            drawCircle(w * 0.75f, h * 0.3f, 70f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(250, 215, 90) })
            val mountain = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ground }
            drawPath(Path().apply { moveTo(-100f, h); lineTo(380f, h * 0.35f); lineTo(860f, h); close() }, mountain)
            mountain.alpha = 200
            drawPath(Path().apply { moveTo(420f, h); lineTo(860f, h * 0.5f); lineTo(1320f, h); close() }, mountain)
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    companion object {
        const val HIKING_TITLE = "週末去陽明山看芒草｜山行筆記"
        const val PLAIN_TITLE = "沒有縮圖的網頁"
        const val AUTUMN_TITLE = "秋天的步道"
    }
}

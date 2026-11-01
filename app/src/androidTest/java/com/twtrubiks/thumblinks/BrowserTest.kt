package com.twtrubiks.thumblinks

import android.content.Context
import android.graphics.Color
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import kotlin.math.abs
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoResult
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 內建瀏覽器：uBlock Origin 從 AMO 安裝（要連外，CI 的模擬器連得到）、真的擋掉廣告、popup 打得開；
 * 網頁開的新視窗疊在同一個分頁上、`window.close()` 回到原本那頁；載不出來時有錯誤頁；
 * uBO 自己檢查更新、從舊版更新成新版。
 * 網頁的內容 UiAutomator 不一定讀得到，只看我們自己畫的網址列，點網頁時用座標。
 * 不錄影，`record.sh` 在 SystemUiTest 之後先 `pm clear` 再跑，所以每次都是第一次安裝 uBO。截圖存成 `browser-*.png`。
 */
@RunWith(AndroidJUnit4::class)
class BrowserTest : UiTest() {
    private val site = FakeSite().start()

    // 瀏覽器開在畫面上時 App 被砍掉，模擬器（軟體繪圖）曾經整個當掉：測完先回桌面，讓 GeckoView 停下來
    @After
    fun leaveBrowser() {
        device.pressHome()
        pause(2000)
        site.close()
    }

    @Test
    fun ublockFromAmoBlocksAds() {
        clearLinks()
        launchApp()
        waitFor(By.text("還沒有連結"), timeout = 30_000)

        // 右上角的「瀏覽」：空白頁，網址列等著輸入
        find(By.text("瀏覽")).click()
        find(By.res("url_bar")).click()
        find(By.res("url_bar")).text = site.url("/ads")
        device.pressEnter()

        // 第一次打開瀏覽器時從 AMO 下載安裝，裝好之後工具列才出現 uBO 的按鈕
        waitFor(By.res("ublock_button"), timeout = 120_000)
        screenshot("browser-01-ublock-installed")
        // 網頁可能在 uBO 準備好之前就載完了，uBO 剛裝好、還在讀過濾清單時 badge 是「!」：
        // 重新整理，直到 badge 是這一頁擋下的數量
        val blocked = poll(90_000) {
            waitFor(By.desc("重新整理"), timeout = 30_000).click()
            poll(10_000) { badge()?.toIntOrNull()?.takeIf { it >= 1 } }
        }
        assertTrue("uBO 沒有擋下任何東西，badge：${badge()}", (blocked ?: 0) >= 1)
        // uBO 換頁時會先清掉 badge 再重算，截圖前再等它出現一次
        poll(10_000) { badge()?.toIntOrNull() }
        screenshot("browser-02-ublock-badge")

        // uBO 的 popup（電源鈕那一頁）放在 BottomSheet 裡
        // BottomSheet 是另一個視窗：看它的「關閉」（popup 的內容在 GeckoView 裡讀不到）
        find(By.res("ublock_button")).click()
        waitFor(By.text("關閉"), timeout = 20_000)
        // popup 的內容讀不到，看截圖：BottomSheet 裡 GeckoView 那一塊要畫出東西，不能整片同一個顏色
        // （用 TextureView 時整片黑；模擬器上要好幾秒才畫出來，還沒畫之前是整片灰）
        // 截圖不要太密（每次截圖都要等 GeckoView 畫完一張，模擬器很吃力）
        val painted = (1..8).firstNotNullOfOrNull { if (popupPainted()) Unit else null.also { pause(3000) } }
        pause(1000)
        screenshot("browser-03-ublock-popup")
        assertTrue("uBO 的 popup 沒有畫出東西", painted != null)
        find(By.text("關閉")).click()
        waitGone(By.text("關閉"))
    }

    @Test
    fun newWindowsStackOnTheSameTab() {
        clearLinks()
        launchApp()
        waitFor(By.text("還沒有連結"), timeout = 30_000)
        find(By.text("瀏覽")).click()
        loadPage(site.url("/opener")) { go(site.url("/opener")) }
        // uBO 正在安裝時 App 會卡一下，點網頁的那一下可能變不成 click：等它裝好（每次 pm clear 後都是第一次裝）
        waitFor(By.res("ublock_button"), timeout = 120_000)
        pause(2000)

        // var w = window.open(); w.location = …：疊一頁新的。以前會在同一頁打開 about:blank，一片白
        loadPage(site.url("/autumn")) { tapPage(0.23f) }
        waitForUrl(site.url("/autumn"))
        screenshot("browser-04-new-window")
        // 新視窗沒有上一頁：關掉它，回到開它的那頁
        find(By.desc("上一頁")).click()
        waitForUrl(site.url("/opener"))
        pause(1500)

        // 登入的小視窗：載完之後改了開它的那頁的網址（#done）再 window.close()，要回到開它的那頁
        tapPage(0.69f)
        waitForUrl(site.url("/popup"))
        waitForUrl(site.url("/opener#done"), timeout = 20_000)
        pause(1000)
        screenshot("browser-05-popup-closed")

        // 找不到的網站：顯示錯誤頁（GeckoView 沒有內建的，以前一片白）
        go("http://thumblinks.invalid/")
        poll(30_000) {
            device.executeShellCommand("logcat -d -s ThumbLinks:W").takeIf { "網頁載入失敗" in it && "thumblinks.invalid" in it }
        } ?: fail("沒有呼叫 onLoadError")
        pause(2000)
        screenshot("browser-06-error-page")
    }

    /**
     * uBO 自己更新：換成 AMO 上的前一版、把上次檢查的時間改成很久以前，當作 App 重新打開（[Ublock.restart]，
     * 走的是 App 啟動時同一條路），要從舊版更新成新版；瀏覽器右上角的選單顯示新的版本與上次檢查的時間。
     */
    @Test
    fun ublockUpdatesItself() {
        clearLinks()
        launchApp()
        waitFor(By.text("還沒有連結"), timeout = 30_000)
        val ublock = container.ublock
        // 清單出現 2 秒後在背景裝 uBO（每次 pm clear 後都是第一次裝）
        val latest = checkNotNull(poll(120_000) { ublock.version }) { "uBO 沒有裝好" }
        val (old, oldUrl) = previousOnAmo(latest)

        val controller = container.gecko.webExtensionController
        await { controller.uninstall(checkNotNull(ublock.extension)) }
        assertEquals(old, await { controller.install(oldUrl) }?.metaData?.version)
        context.getSharedPreferences("browser", Context.MODE_PRIVATE).edit { putLong(Ublock.CHECKED_AT, 0) }
        instrumentation.runOnMainSync { ublock.restart() }

        val updated = poll(120_000) {
            Regex("uBlock Origin 從 ${Regex.escape(old)} 更新到 (\\S+)")
                .find(device.executeShellCommand("logcat -d -s ThumbLinks:I"))?.groupValues?.get(1)
        } ?: fail("uBO 沒有從 $old 更新（logcat 沒有「從 $old 更新到」）")
        assertEquals(updated, ublock.version)
        assertTrue("更新後應該比 $old 新：$updated", updated != old)

        // 瀏覽器右上角的選單：新的版本、上次檢查更新的時間
        find(By.text("瀏覽")).click()
        waitFor(By.res("ublock_button"), timeout = 60_000)
        find(By.desc("更多")).click()
        waitFor(By.textContains("uBlock Origin $updated"))
        waitFor(By.textContains("上次檢查更新："))
        pause(1500)
        screenshot("browser-07-ublock-updated")
        device.pressBack()
    }

    /** AMO 上 [current] 的前一個版本（版本號、下載網址）。AMO 的 API 新的在前面。 */
    private fun previousOnAmo(current: String): Pair<String, String> {
        val results = JSONObject(URL(AMO_VERSIONS).readText()).getJSONArray("results")
        val versions = (0 until results.length()).map(results::getJSONObject)
        val index = versions.indexOfFirst { it.getString("version") == current }
        val previous = versions[if (index >= 0) index + 1 else 1]
        return previous.getString("version") to previous.getJSONObject("file").getString("url")
    }

    /** 在主執行緒叫 GeckoView 做 [action]，等它的結果（GeckoResult 的 callback 回到主執行緒）。 */
    private fun <T> await(timeout: Long = 120_000, action: () -> GeckoResult<T>): T? {
        val done = CountDownLatch(1)
        var value: T? = null
        var error: Throwable? = null
        instrumentation.runOnMainSync {
            action().accept({
                value = it
                done.countDown()
            }, {
                error = it
                done.countDown()
            })
        }
        check(done.await(timeout, TimeUnit.MILLISECONDS)) { "等不到 GeckoView" }
        error?.let { throw AssertionError("GeckoView 做不到", it) }
        return value
    }

    /** 螢幕高度 30%～80%（BottomSheet 裡 GeckoView 那一塊）有沒有明顯不同的顏色。 */
    private fun popupPainted(): Boolean {
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return false
        val colors = (30..80 step 5).flatMap { y -> (5..95 step 10).map { x -> shot.getPixel(shot.width * x / 100, shot.height * y / 100) } }
        shot.recycle()
        fun differ(a: Int, b: Int) =
            abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b)) > 60
        return colors.any { a -> colors.any { b -> differ(a, b) } }
    }

    /** uBO 擋了幾個（badge 的字）。badge 一直在更新，拿到的元件可能馬上就舊了，舊了就當作還沒有。 */
    private fun badge(): String? = try {
        device.findObject(By.res("ublock_badge"))?.text
    } catch (e: StaleObjectException) {
        null
    }

    /** 網址列打字、按「前往」。點下去會整個選起來，`text =` 會整個換掉。 */
    private fun go(url: String) {
        find(By.res("url_bar")).click()
        find(By.res("url_bar")).text = url
        device.pressEnter()
    }

    private fun waitForUrl(url: String, timeout: Long = 30_000) = waitUntil(timeout) { device.findObject(By.res("url_bar"))?.text == url }

    /**
     * 點網頁上 [fraction]（由上往下）高度的地方。GeckoView 外面那層的 testTag UiAutomator 讀不到，
     * 網頁的範圍用上面的網址列與下面的「上一頁」推出來。
     */
    private fun tapPage(fraction: Float) {
        val top = find(By.res("url_bar")).visibleBounds.bottom
        val bottom = find(By.desc("上一頁")).visibleBounds.top
        device.click(device.displayWidth / 2, top + ((bottom - top) * fraction).toInt())
    }

    private companion object {
        // AMO 的 API：uBlock Origin 上架過的版本，新的在前面，每一版有簽章過的 xpi 下載網址
        const val AMO_VERSIONS = "https://addons.mozilla.org/api/v5/addons/addon/ublock-origin/versions/?page_size=5"
    }
}

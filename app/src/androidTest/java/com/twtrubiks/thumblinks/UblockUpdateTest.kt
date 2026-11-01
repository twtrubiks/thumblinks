package com.twtrubiks.thumblinks

import android.content.Context
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoResult
import java.io.File
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * uBO 自己更新。要跨兩次 App 啟動：`record.sh` 在 BrowserTest 之後分兩次 `am instrument` 各跑一個
 * （每次 `am instrument` 都是新的行程，等於重新打開 App），單獨跑其中一個沒有意義。
 * 1. [downloadsTheNewVersion]：換成 AMO 上的前一版、把上次檢查的時間改成 0，走 App 打開時同一條路（[Ublock.restart]）。
 *    uBO 正在用的時候不換（它有聽 `runtime.onUpdateAvailable`），GeckoView 回 `ERROR_POSTPONED`：新版下載好、等引擎下次啟動
 * 2. [appliesItAfterRestart]：重新打開 App，uBO 換成新版；瀏覽器右上角的選單顯示新版與上次檢查的時間
 * 舊版的版本號記在測試 APK 自己的檔案裡，交給第二次。
 */
@RunWith(AndroidJUnit4::class)
class UblockUpdateTest : UiTest() {
    private val oldVersionFile = File(instrumentation.context.filesDir, "ublock-old-version")

    // 瀏覽器開在畫面上時 App 被砍掉，模擬器（軟體繪圖）曾經整個當掉：測完先回桌面
    @After
    fun leaveBrowser() {
        device.pressHome()
        pause(2000)
    }

    @Test
    fun downloadsTheNewVersion() {
        clearLinks()
        launchApp()
        waitFor(By.text("還沒有連結"), timeout = 30_000)
        val ublock = container.ublock
        // 清單出現 2 秒後在背景準備 uBO（BrowserTest 已經裝好了，這裡只是找到它）
        val latest = checkNotNull(poll(120_000) { ublock.version }) { "uBO 沒有裝好" }
        val (old, oldUrl) = previousOnAmo(latest)

        val controller = container.gecko.webExtensionController
        await { controller.uninstall(checkNotNull(ublock.extension)) }
        assertEquals(old, await { controller.install(oldUrl) }?.metaData?.version)
        oldVersionFile.writeText(old)
        context.getSharedPreferences("browser", Context.MODE_PRIVATE).edit { putLong(Ublock.CHECKED_AT, 0) }
        instrumentation.runOnMainSync { ublock.restart() }

        // uBO 會要求延後（下載好、下次啟動換上）；哪天 uBO 不延後、直接換上也算
        poll(120_000) {
            device.executeShellCommand("logcat -d -s ThumbLinks:I").lineSequence()
                .firstOrNull { "下次打開 App 時換上（現在是 $old）" in it || "從 $old 更新到" in it }
        } ?: fail("uBO 沒有從 $old 更新（logcat 看「檢查 uBlock Origin」之後那幾行）")
    }

    @Test
    fun appliesItAfterRestart() {
        val old = oldVersionFile.readText()
        clearLinks()
        launchApp()
        waitFor(By.text("還沒有連結"), timeout = 30_000)
        val ublock = container.ublock
        val now = checkNotNull(poll(120_000) { ublock.version }) { "uBO 沒有裝好" }
        assertNotEquals("重新打開 App 之後 uBO 還是舊版", old, now)
        assertNull(ublock.pendingVersion)

        // 瀏覽器右上角的選單：新的版本、上次檢查更新的時間
        find(By.text("瀏覽")).click()
        waitFor(By.res("ublock_button"), timeout = 60_000)
        find(By.desc("更多")).click()
        waitFor(By.textContains("uBlock Origin $now"))
        waitFor(By.textContains("上次檢查更新："))
        pause(1500)
        screenshot("browser-07-ublock-updated")
        device.pressBack()
        oldVersionFile.delete()
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

    private companion object {
        // AMO 的 API：uBlock Origin 上架過的版本，新的在前面，每一版有簽章過的 xpi 下載網址
        const val AMO_VERSIONS = "https://addons.mozilla.org/api/v5/addons/addon/ublock-origin/versions/?page_size=5"
    }
}

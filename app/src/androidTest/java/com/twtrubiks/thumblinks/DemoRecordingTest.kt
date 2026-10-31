package com.twtrubiks.thumblinks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 錄影用的展示流程，CI 只錄這個 class，`scripts/ci/record.sh` 會在執行期間錄下整個螢幕。
 *
 * 目前只有骨架：打開 App、看到空清單。功能做出來之後照 CLAUDE.md 的「錄影測試」把流程補上
 * （貼網址抓縮圖、抓不到時貼圖片網址、上傳圖片、改標題、刪除、匯出），
 * 網頁由模擬器上的假網站（MockWebServer）提供，不連外。
 */
@RunWith(AndroidJUnit4::class)
class DemoRecordingTest : UiTest() {
    @Test
    fun demo() {
        container.links.all().forEach { container.links.delete(it.id) }

        launchApp()
        waitFor(By.text("ThumbLinks"), timeout = 30_000)
        pause(2500)
        screenshot("01-empty")
        pause(1500)
    }
}

# 測試與錄影

模擬器上的測試、錄影流程、變異測試。寫或改 `app/src/androidTest/`、`scripts/ci/record.sh` 之前先讀；
每次 push 前要跑的指令在 `CLAUDE.md` 的「在 VM 內驗證」。

## 變異測試（PIT，手動跑）

故意把程式改錯一點（`<` 換 `<=`、拿掉一個判斷…）看 JVM 測試抓不抓得到，找測試沒保護到的地方。不進 Gradle、不進 CI，要的時候在 VM 手動跑：
PIT（`pitest-command-line`、`pitest-entry`、`pitest`，加上 `commons-text`、`commons-lang3`）從 Maven Central 下載到 `/tmp`，
classpath 用 init script 印出 `testDebugUnitTest` 的 `classpath.files`，把 `runtime_app_classes_jar/.../classes.jar` 換成
`app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes`（PIT 只改目錄裡的 class，也用 `--mutableCodePaths` 指它）。
- 只挑純 Kotlin 的規則（`--targetClasses` 列 `WebUrls*`、`PageMeta*`、`Import*`…）；`PageMeta.parse` 在 companion 裡，不要排除 `$Companion`
- OkHttp 的 Android 版一碰 `android.util.Log` 就丟例外（mockable android.jar），在 classpath 最前面放一個什麼都不做的 `Log` 替身
- 看結果時先濾掉雜訊：Kotlin 自動產生的 null 檢查（描述有 `Intrinsics`）、多餘的 `?.` 判斷、`close`／`flush`、
  `object` 裡 `Json { }` 的設定（靜態初始化，PIT 改了也不會生效）
- 活下來的不一定是 bug：寫成一次性的測試對真的程式跑一次才知道；確定危險的才補成正式的測試

## 錄影測試：DemoRecordingTest

`app/src/androidTest/java/com/twtrubiks/thumblinks/DemoRecordingTest.kt`，CI 只錄這個 class，
`record.sh` 在它執行期間用 `screenrecord` 錄整個螢幕。找元件、輪詢、截圖、剪貼簿、分享這些放在 `UiTest`，跟 `SystemUiTest` 共用。

- 用 UiAutomator 操作（真實時間驅動，動畫會正常播放），不要用 Compose test rule
- 不要用 `ActivityScenario` 開 App，用 `UiTest.launchApp()`（`context.startActivity`）；找元件用 `waitFor` 每 0.1 秒輪詢，
  不要用 `device.wait(Until...)` 或 `device.waitForIdle()`。元件找到之後畫面又重組了，按之前要重新找一次，否則會拿到 `StaleObjectException`
- 一個 `@Test` 跑完整的展示流程，每個操作之間停 1～1.5 秒讓影片看得清楚；全長控制在 150 秒內（`screenrecord` 上限 180 秒）
- 用 `By.text(...)` 找元件；要用 testTag 時用 `By.res(...)`（`ThumbLinksApp` 的 root 已經開了 `testTagsAsResourceId`）。
  Material3 的 `ExtendedFloatingActionButton` 把 text 包在 `clearAndSetSemantics {}` 裡，`By.text` 與 `uiautomator dump` 都看不到「新增」，
  所以首頁的 FAB 用 testTag `add_button` 找（圖示的 contentDescription 給 TalkBack 念）
- 要確認細節的畫面用 `screenshot("名稱")` 另存截圖
- **網頁由模擬器上的假網站提供**（`mockwebserver3`，`http://127.0.0.1`），不連外：
  有 og:image 與 og:title 的網頁、沒有縮圖的網頁、圖片本身（測試裡用 `Bitmap` 畫一張 PNG），都在 `FakeSite`。
  展示流程寫在 `DemoRecordingTest` 的 class 註解。uBO 要連 AMO，不放在錄影裡
- 改了深色模式、排序這些存在 SharedPreferences 的設定，測試結束前要改回來，不然會留給下一個測試
- 每一筆都有一個 `favorite_button`：挑跟標題同一列（上下最近）的那個。長按選單（DropdownMenu）是另一個視窗，用 `By.text` 找。
  複製之後讀剪貼簿核對（`UiTest.clipboardText`，App 在前景才讀得到）
- 內建瀏覽器的網頁內容在 GeckoView 裡，UiAutomator 不保證讀得到；只操作我們自己畫的部分（`url_bar`、`add_to_list`、`in_list`、
  `ublock_button`、`ublock_badge`、`ublock_popup` 這些 testTag）。網址列打字：先點一下拿到焦點、`text = …`、`device.pressEnter()`
- 網頁載完了沒用 `UiTest.loadPage(url) { 動作 }`：看 `BrowserTab` 寫進 logcat 的「網頁載入完成」。
  工具列的「重新整理」在開始載入之前也是它，拿來等會在網頁還一片白的時候就截圖（模擬器第一次開 GeckoView 要好幾秒）
  新的 GeckoSession 會先載入一次 `about:blank`（在我們要的網址開始之前就結束），`BrowserTab.Progress` 跳過它不寫，
  不然還沒開始載就有「網頁載入完成」
- 包著 GeckoView 的 `AndroidView` 加 testTag UiAutomator 也讀不到；要點網頁時用座標（網址列下緣到「上一頁」上緣之間）
- BottomSheet、Dialog 是另一個視窗，App 根部開的 `testTagsAsResourceId` 管不到：要在裡面再開一次（uBO 的 popup 有開），或用 `By.text` 找
- uBO 的 badge 一直在更新（換頁時先清掉再重算），讀字時元件可能已經舊了（`StaleObjectException`）
- 開始前清空清單（`container.links`），要用到的資料都在測試裡建
- 清單的搜尋框是 `search_input`（清除鈕 `search_clear`）。搜尋找不到時清單（`link_list`）不在畫面上，`backOnList()` 會等不到，
  改等「找不到「…」」。確認刪除的對話框按鈕跟長按選單的「刪除」同字：先等對話框的標題出來再按
- 鍵盤開著才按返回鍵收起（`hideKeyboard()`）——沒開的時候按返回鍵會離開那一頁

## 模擬器上的測試：DeviceTest

`app/src/androidTest/java/com/twtrubiks/thumblinks/DeviceTest.kt`，`record.sh` 在錄影前先跑、不錄影，輸出在 `device-test.txt`。
只放模擬器才測得到的事：`LinkStore` 在 Android 9 的 SQLite 上跑一輪（含舊的表補上 `favorite` 欄位）；做了上傳之後，`ThumbPrep` 用真的 Bitmap 與 ExifInterface 縮圖轉正
（直拍的 JPEG、手機拍的 HEIC；只看長寬的話轉錯方向也會過，要用上下兩色的圖核對轉正後顏色的位置）；
匯入的圖（`ThumbPrep.saveImported`）整理好的原樣存、太大或要轉的照上傳的規則整理。

## 系統畫面的測試：SystemUiTest

`app/src/androidTest/java/com/twtrubiks/thumblinks/SystemUiTest.kt`，不錄影。`record.sh` 在錄影結束、截圖 pull 回來之後先 `pm clear` 再跑，
輸出在 `system-ui-test.txt`、`logcat-system-ui.txt`，截圖是 `system-*.png`。
只放不是 App 畫的畫面，每個 Android 版本長得不一樣，只能點一次才知道接不接得上：
- 系統的照片挑選器：Android 9、10 是檔案畫面（找得到檔名），Android 11 以後是照片挑選器（只有縮圖，挑第一張）。
  照片先放進相簿：Android 10 以後 App 直接寫 MediaStore，Android 9 要權限，交給 shell 複製再廣播掃描
- 存檔的檔案畫面：有上傳的圖，匯出 Markdown 存到 Download 是 zip（先從左上角的選單切到 Downloads，停在「最近」的話不能存），
  用 `uiAutomation.executeShellCommand("cat …")` 讀出原始 bytes 解開，核對 `links.md` 與 `images/<id>.jpg`。
  這個指令不經過 sh：不能用引號、萬用字元，檔名要確定（同名的會被系統存成「… (1).zip」，所以開始前先刪掉）
- 開檔的檔案畫面：接著從畫面刪掉那一筆，清單空了 →「從檔案匯入」→ 一樣切到 Downloads、點剛匯出的 zip →
  確認對話框「匯入 1 筆」（`system-05-import-confirm.png`）→ 加回來（`system-06-imported.png`），上傳的圖跟 zip 裡的一模一樣

## 內建瀏覽器的測試：BrowserTest

`app/src/androidTest/java/com/twtrubiks/thumblinks/BrowserTest.kt`，不錄影。`record.sh` 最後才跑（release 冒煙測試之後，換回 debug 版、`pm clear`）。
模擬器（軟體繪圖）曾經在測完瀏覽器、關掉 App 時整個當掉（`device offline`，之後的 adb 全部失敗），所以放最後、
logcat 邊跑邊存、測完先回桌面（`@After`）讓 GeckoView 停下來；uBO popup 的截圖也不要太密
（每次都是第一次安裝 uBO），輸出在 `browser-test.txt`、`logcat-browser.txt`，截圖是 `browser-*.png`。
- uBO：右上角「瀏覽」→ 網址列打開 `FakeSite` 的 `/ads`（載入 `pagead2.googlesyndication.com` 的廣告 script）→
  等 uBO 從 AMO 裝好（工具列出現 `ublock_button`，最多 2 分鐘；裝的時候 App 會卡一下，另一個測試也要先等它裝好才點網頁）→ 重新整理，等 badge（`ublock_badge`）至少擋下 1 個（剛裝好、還在讀過濾清單時 badge 是「!」，要一直重新整理到變成數字）→ 打開 popup
- 新視窗與錯誤頁：`/opener` 上點（用座標點網頁）`window.open()` 再設 location 的按鈕 → 網址列變成 `/autumn` → 上一頁回到 `/opener`；
  點登入小視窗（`/popup` 改了開它的那頁的網址再 `window.close()`）→ 網址列變成 `/opener#done`；
  打開 `http://thumblinks.invalid/` → logcat 有 `onLoadError` 寫的「網頁載入失敗」（錯誤頁的樣子看 `browser-06-error-page.png`）

uBO 自己更新在 `UblockUpdateTest`，要跨兩次 App 啟動（新版要等引擎重新啟動才換上），`record.sh` 在 BrowserTest 之後
分兩次 `am instrument` 各跑一個方法（每次都是新的行程，等於重新打開 App），輸出在 `ublock-update-test.txt`：
1. `downloadsTheNewVersion`：從 AMO 的 API（`/api/v5/addons/addon/ublock-origin/versions/`，新的在前面）找前一版，
   移除現在的、裝那一版（`WebExtensionController` 直接做，`await` 等 GeckoResult）→ `Ublock.CHECKED_AT` 改成 0 →
   `Ublock.restart()`（跟 App 打開時同一條路）→ logcat 出現「下次打開 App 時換上」。前一版的版本號寫在 App 的 `files/` 交給第二次（測試跑在 App 的行程，寫不進測試 APK 自己的資料夾）
2. `appliesItAfterRestart`：重新打開 App，uBO 不是前一版了，右上角選單顯示新版與上次檢查的時間（`browser-07-ublock-updated.png`）

## release 冒煙測試

`record.sh` 在 SystemUiTest 之後覆蓋安裝 release 版（同一把 debug 金鑰，可以直接覆蓋），打開 App 截圖（`screenshots/release-1-launch.png`），
再用 `uiautomator dump` 找元件：按「新增」、輸入 `https://github.com/git/git`、按「抓縮圖」，等到預覽的 testTag 變成 `thumb_ready`
（Coil 真的把縮圖載出來了，`release-2-fetched.png`），按「加入」，清單上出現 `github.com`（`release-3-added.png`）。
R8 壓過的 OkHttp、jsoup、Coil 只有真的抓一次才跑得到；假網站跑在測試的行程裡，這一段沒有測試 APK，所以抓真的網址。
接著點那一筆，用內建瀏覽器打開它，等 logcat 出現 App 寫的兩行（`BrowserTab` 的「網頁載入完成 success=true https://github.com/git/git」、
`Ublock` 的「uBlock Origin … 可以用了」）：壓過的 GeckoView 要真的載入網頁、從 AMO 裝好 uBO。
網頁一直在動，`uiautomator dump` 等不到畫面靜止，所以瀏覽器這一段看 logcat 不用 dump。
**不用 github.com 首頁**：首頁有自動播放的靜音影片，模擬器（軟體解碼、軟體繪圖）在影片播放時離開 App，SurfaceFlinger 卡死，
system_server 被 watchdog 殺掉（`WATCHDOG KILLING SYSTEM PROCESS`、`DeadSystemException`），後面的 BrowserTest 一個都沒跑。
repo 頁面沒有影片，一樣有 og:image 與標題。
**這一段不截圖**：開著 GeckoView 時 adb 的 screencap 曾經卡住（`exec-out` 卡了 30 分鐘、整個 job 逾時；存在模擬器上的也逾時過），
瀏覽器的樣子看 BrowserTest 的截圖（UiAutomation 截圖沒事）。這一段的 adb 指令都包 `timeout`，等完先回桌面再往下走，進度（含時間）寫進 `release-smoke.txt`。
等不到縮圖、加不進清單、瀏覽器沒載完或 uBO 沒裝好，或 `logcat-release.txt` 有 `FATAL EXCEPTION` 時 job 會失敗。
輸入框拿到焦點後游標會閃、鍵盤會打開，dump 等不到畫面靜止，所以新增頁先在沒有人拿到焦點時 dump 一次記下座標；
按「抓縮圖」時 App 會把焦點拿掉，之後的 dump 才等得到。

## 錄影流程（GitHub Actions）

1. push 到目前的 branch；改到 `app/**`、`gradle/**`、`*.gradle.kts`、`gradle.properties`、`scripts/ci/**`、`record.yml` 會自動觸發。
   只改文件（`CLAUDE.md`、`README.md`、`docs/**`）不會觸發。要手動觸發：
   `gh api -X POST 'repos/{owner}/{repo}/actions/workflows/record.yml/dispatches' -f ref=<branch>`
   錄影一律用 API 28（最低支援的 Android 9）的模擬器；使用者明確要求其他版本時才加 `-f "inputs[api-level]=<level>"`（例如 Android 14 是 34），
   不同 API level 都寫到同一個 `media` branch，用 `info.txt` 的 `api level` 分辨
2. 查進度：`gh api "repos/{owner}/{repo}/actions/runs?branch=<branch>&per_page=1" --jq '.workflow_runs[0] | {id, status, conclusion, head_sha}'`
   （雲端 session 的 GitHub proxy 擋 GraphQL，`gh pr` / `gh issue` 不能用，一律用 `gh api` 打 REST）
3. 看哪一步失敗：`gh api 'repos/{owner}/{repo}/actions/runs/<id>/jobs' --jq '.jobs[] | {name, conclusion, steps: [.steps[] | select(.conclusion=="failure") | .name]}'`
4. 不論成功或失敗，run 結束後都讀 `media` branch（每次會被覆蓋，先核對 `info.txt` 的 commit 是不是自己這次的）：

   ```bash
   git fetch origin media
   rm -rf /tmp/media && mkdir -p /tmp/media
   git archive origin/media | tar -x -C /tmp/media
   ```

   - `/tmp/media/frames/*.png`：每 2 秒一張影格，用 Read 看圖確認畫面
   - `/tmp/media/screenshots/*.png`：測試在關鍵畫面存的截圖（半解析度），`release-*.png` 是 release 冒煙測試的截圖
   - `/tmp/media/info.txt`：branch、commit、api level、run 網址、job status
   - `/tmp/media/progress.txt`：`record.sh` 每一段開始的時間，卡住（job 逾時、被取消）時看卡在哪一段。
     `record.sh` 的 adb 指令都限時（GeckoView 用過之後 adb 曾經整個卡住），測完瀏覽器先 `am force-stop`
   - `/tmp/media/gradle-build.txt`：CI 的 Gradle build 輸出
   - `/tmp/media/device-test.txt`：DeviceTest 的輸出
   - `/tmp/media/instrument.txt`、`logcat.txt`：DemoRecordingTest 的輸出與 log
   - `/tmp/media/system-ui-test.txt`、`logcat-system-ui.txt`：SystemUiTest 的輸出與 log
   - `/tmp/media/browser-test.txt`、`ublock-update-test.txt`、`logcat-browser.txt`：BrowserTest、UblockUpdateTest 的輸出與 log
   - `/tmp/media/release-smoke.txt`、`logcat-release.txt`：release 冒煙測試的啟動時間與 log
   - `/tmp/media/demo.mp4`：壓縮後的影片（給使用者看）

README 的截圖在 `docs/screenshots/`，是從 `media` 的 `screenshots/` 複製的（`list` ← `16-long-press-menu`、`add` ← `02-fetched`、
`export` ← `12-export`、`browser` ← `08-browser`、`ublock` ← `browser-03-ublock-popup`、`dark` ← `13-dark`）。畫面改了要重新複製；
README 照事實寫，不誇大（做不到的、有限制的照寫）。

看完 frames 確認畫面正常後，把 `/tmp/media/demo.mp4` 複製到 session 的 scratchpad 目錄，
用 SendUserFile（`display: render`）直接傳給使用者，讓使用者不用 pull 就能播放。傳之前先核對 `info.txt` 的 commit 是這次的。

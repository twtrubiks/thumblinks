# ThumbLinks

收集網址的 Android App：貼上網址 → 抓縮圖（必填）與標題（選填）→ 整份清單一次匯出成 Markdown 或 JSON。
內建一個會擋廣告的瀏覽器（GeckoView ＋ uBlock Origin），看到喜歡的網頁直接「加到清單」。
Kotlin + Jetpack Compose 原生 App，在 claude.ai/code 雲端 session 開發。
雲端 VM 沒有模擬器，模擬器操作與錄影交給 GitHub Actions（`.github/workflows/record.yml`）。

架構（Gradle 設定、`Sql` 分層、CI 錄影、發版）沿用 treesay 的 Android 版（`twtrubiks/treesay` 的 `android/`）。
那邊踩過的坑都寫在它的 `android/CLAUDE.md`，這裡遇到同樣的問題（UiAutomator、模擬器、R8、系統畫面的測試）可以去翻。

## 目前的進度

骨架在 2026-10-31 的 session 第一次在 VM 上 build 過（版本都不用改），規格裡的功能都做了：

- 規則（純 Kotlin、JVM 測試）：`WebUrls`（取網址、網域）、`PageMeta`（解析 meta、Big5）、`MetaFetcher`（OkHttp）、
  `Export`（Markdown／JSON／zip）、`LinkStore`（新增、查重複、改、刪）
- 畫面：清單（`ListScreen`，含匯出）、新增（`AddScreen`）、編輯（`EditScreen`）、縮圖預覽與兩個補救（`Thumbnails.kt` 的
  `ThumbnailEditor`／`ThumbDraft`）、上傳的圖（`ThumbPrep`）、分享進來（`MainActivity`，`singleTask` ＋ `onNewIntent`）
- 內建瀏覽器（`claude/browser-geckoview` 分支，2026-10-31 開始）：`BrowserScreen`、`BrowserTab`（一個 GeckoSession 與它的 delegate）、
  `Ublock`（從 AMO 安裝、更新、badge、popup）、`BrowserInput`（網址列的規則，JVM 測試）。還沒做：多分頁
- 模擬器：`DeviceTest`（SQLite、`ThumbPrep` 轉正）、`DemoRecordingTest`（錄影）、`SystemUiTest`（挑選器、存檔畫面）、
  `BrowserTest`（uBO 從 AMO 安裝、擋廣告、popup）、release 冒煙測試（真的抓 `https://github.com/git/git`、用內建瀏覽器打開）。假網站在 `FakeSite`

改東西之前先跑「在 VM 內驗證」，改完看 CI 的錄影。

## 規格

### 加入連結

- 首頁右下角「新增」→ 新增頁：一個網址輸入框，旁邊有「貼上」按鈕（讀剪貼簿）
- 也接受別的 App「分享」過來的文字（`ACTION_SEND`、`text/plain`）：直接打開新增頁、帶入網址、開始抓。
  要在 `AndroidManifest.xml` 加 intent-filter，`MainActivity` 用 `onNewIntent` 接住 App 已經開著時分享進來的
- **取網址**（`WebUrls`）：從輸入或分享的文字裡取出第一個 `http://` 或 `https://` 開頭的網址（分享過來常是「標題＋網址」）。
  找不到就提示「找不到網址」，不自動補 `https://`
- **先查重複**：網址已經在清單裡就不抓，提示「已經在清單裡了」並可以跳到那一筆（`DuplicateLinkException.existing`）。
  網址照字串比對，不做正規化（去追蹤參數之類的先不做）
- **抓網頁**（`MetaFetcher`，OkHttp，在 `Dispatchers.IO`）：GET、跟著轉址、整體逾時 15 秒、最多讀 1 MB（`<meta>` 都在 `<head>`），
  帶一般手機瀏覽器的 User-Agent 與 `Accept-Language: zh-TW,zh;q=0.9,en;q=0.8`
  - 回應的 `Content-Type` 是 `image/*`：網址本身就是縮圖，標題留空
  - 是 HTML：交給 `PageMeta` 解析
  - 連不上、逾時、HTTP 4xx/5xx：當作抓不到縮圖，原因寫「連不上這個網頁」，可以重試
- **解析**（`PageMeta`，純 Kotlin＋jsoup）：
  - 縮圖依序找 `og:image:secure_url`、`og:image`、`og:image:url`、`twitter:image`、`twitter:image:src`、`<link rel="image_src">`，
    `<meta>` 的 `property` 和 `name` 屬性都認
  - 相對網址以「轉址後的最終網址」為基準解析（也要認 `<base href>`）；只收 `http`／`https`
  - 標題依序找 `og:title`、`twitter:title`、`<title>`；去頭尾空白、連續空白壓成一個
  - 編碼交給 jsoup 從 bytes 判斷（`Content-Type` 的 charset → `<meta charset>`），Big5 的網頁也要讀得出中文標題
- **預覽**：抓到之後顯示縮圖（Coil）＋標題欄。標題欄預填抓到的標題，可以改、可以清空（清空就是沒有標題）。
  縮圖載入失敗（404、不是圖）視同沒抓到
- **抓不到縮圖**（網頁沒提供、連不上、圖載不出來）：寫出原因（「這個網頁沒有提供縮圖」／「連不上這個網頁」），給兩個補救：
  1. 貼圖片網址：一樣用 Coil 預覽，載得出來才算
  2. 從手機選一張圖：Photo Picker（`ActivityResultContracts.PickVisualMedia`，Android 9、10 沒有挑選器時會自動退回系統的檔案畫面）

  抓到了也可以用這兩個方法換掉。**沒有縮圖就不能存**（「加入」按鈕停用）
- **上傳的圖**（`ThumbPrep`，`Bitmap` + `ExifInterface`）：複製進 App 的 `filesDir/thumbs/<uuid>.jpg`，依 EXIF 轉正、長邊縮到 1024px 以內、
  JPEG 品質 85，HEIC 一樣轉成 JPEG。不留原圖的 URI（挑選器給的讀取權限是暫時的）。
  使用者放棄新增或又換了別的縮圖，已經寫出去的檔案要刪掉
- 輸入框（網址、圖片網址、標題）長的會換行顯示（單行的框貼上長網址後，要拖回前面很難），內容還是一行：
  換行換成空白（`oneLine()`）；網址欄收到換行（貼上多行文字）當作按了「前往」。實體鍵盤的 Enter 用 `Modifier.onEnter` 先攔下來
  （換行顯示的框按 Enter 會插入換行；在 onValueChange 裡才處理的話 `clearFocus()` 不會生效）。新增頁的網址欄右邊有清除鈕。
  **`singleLine` 不能中途切換**（例如打字時才多行）：TextField 的捲動方向對不上會閃退，要多行就一直是多行、只改 `maxLines`
- 轉螢幕不能丟東西：輸入到一半的網址、抓到的結果、選好的縮圖、改到一半的標題都放 `rememberSaveable`

### 清單

- 右上角：深色模式（跟系統／淺色／深色）、排序、「瀏覽」、「匯出」
- 首頁就是清單，預設新加的在上面。**排序**（右上角，`SortOrder`，`sortLinks` 純 Kotlin、JVM 測）：新的在上／舊的在上／標題／網域，
  記在 SharedPreferences `settings`；標題、網域一樣的照新的在上，標題不分大小寫、中文照語系（Collator），沒有標題的用網址。
  匯出的順序跟畫面一樣（`export()` 也照目前的排序）。換排序時捲回最上面每筆顯示縮圖、標題（沒有標題就顯示網址）、網域。
  回到清單時多了新的一筆就捲到它（`LazyColumn` 會跟著原本的第一筆走，新的那筆會被擠到畫面外看不到）
- 點一筆進編輯頁：改標題、換縮圖（重新抓、貼圖片網址、上傳）、用瀏覽器開、刪除（要確認）。
  換掉或刪掉上傳的縮圖時，舊的檔案要刪（`LinkStore.update`、`LinkStore.delete` 會刪）。
  在這一頁上傳、還沒存的圖由 `ThumbDraft` 記著，放棄這一頁（返回鍵）或又換一張時刪掉
- 空清單時說明怎麼加：貼網址，或從瀏覽器「分享」過來
- 右上角「匯出」，清單是空的時候停用

### 匯出

- 一次匯出清單上全部，順序跟畫面一樣（新的在上面）；匯出完清單留著，不清空
- 先選格式：Markdown 或 JSON
- 存到哪裡讓使用者選（`ActivityResultContracts.CreateDocument`），預設檔名 `thumblinks-<YYYY-MM-DD>.<md|json|zip>`
- **都是網路上的縮圖 → 單一檔案**（`.md` / `.json`）
- **有任何一筆是上傳的縮圖 → 一個 zip**：`links.md` 或 `links.json` ＋ `images/<id>.jpg`，上傳的圖用相對路徑 `images/<id>.jpg` 引用；
  網路上的縮圖照樣寫網址，不下載
- 組字與打包（`Export`）是純 Kotlin，zip 用 `java.util.zip.ZipOutputStream`，都在 JVM 測

Markdown（UTF-8，一筆一行，筆與筆之間空一行）：

```markdown
[![標題](https://example.com/og.jpg)](https://example.com/article)

[![https://example.org/page](images/0b6c1c1e-4f1a-4b1e-9a0e-3f0f2d7c9a11.jpg)](https://example.org/page)
```

- 沒有標題時，替代文字用網址
- 標題裡的 `\`、`[`、`]` 前面加 `\`；換行換成空白
- 網址裡的空白、`(`、`)`、`<`、`>` 用百分比編碼，其餘照原樣

JSON（UTF-8、縮排兩格、中文不跳脫；用 kotlinx.serialization 的 `@Serializable` data class）：

```json
{
  "exported_at": "2026-10-31T21:05:00+08:00",
  "links": [
    {
      "url": "https://example.com/article",
      "title": "標題",
      "thumbnail": "https://example.com/og.jpg",
      "created_at": "2026-10-31T20:00:00+08:00"
    },
    {
      "url": "https://example.org/page",
      "title": null,
      "thumbnail": "images/0b6c1c1e-4f1a-4b1e-9a0e-3f0f2d7c9a11.jpg",
      "created_at": "2026-10-30T09:12:00+08:00"
    }
  ]
}
```

- 沒有標題寫 `null`，不省略欄位
- `thumbnail` 是字串：`http(s)://` 開頭是網路上的圖，其餘是 zip 裡的相對路徑
- 時間是當地時間＋時差，到秒（`LinkStore` 存的就是這個格式）

### 內建瀏覽器

使用者自己用、不上架。GeckoView 只負責顯示網頁，外殼（網址列、按鈕、對話框）都是自己寫的。

- 入口在清單：**點一筆用內建瀏覽器打開**（長按進編輯頁）、右上角「瀏覽」開空白頁（網址列等著輸入）、編輯頁的「用瀏覽器開」。
  「用其他 App 開」放在瀏覽器右上角的選單
- 一頁瀏覽器就是一個分頁（`Route.Browser`），先不做分頁列。網頁開的新視窗（`target=_blank`、`window.open`）
  疊在同一個分頁上（`BrowserTab.pages`，新的 GeckoSession，`window.opener` 接得上，登入用的小視窗才能用）：
  返回鍵／上一頁在新視窗沒有上一頁時關掉它、回到開它的那頁，網頁 `window.close()` 也是。
  不能用「拒絕新視窗、在原本的 session 載入那個網址」：`window.open()` 之後再設 location 的網頁會變成 `about:blank` 一片白。
  沒有使用者點擊就跳出來的彈出視窗交給 Gecko 自己擋（`PromptDelegate.onPopupPrompt` 預設拒絕）。
  `LoadRequest.hasUserGesture` 不可靠（點了之後非同步才 `window.open` 的登入小視窗是 false），不要拿它擋新視窗
- `mailto:`、`tel:`、`intent:`、`line:` 這類交給手機上別的 App：使用者點的、或點了之後伺服器轉址過去的（例如 `line.me`）直接開；
  網頁自己跳的跳一個提示（「這個網頁要用其他 App 打開」＋「打開」），不自動開
- 載不出來（找不到網站、連不上、憑證有問題、一直轉址…）顯示自己的錯誤頁（`BrowserInput.errorPage`，寫原因與網址）：
  GeckoView 沒有內建錯誤頁，`onLoadError` 回 null 的話畫面就停在一片白
- 網址列：`http(s)://` 照用、看起來像網域的補 `https://`、其他用 DuckDuckGo 搜尋（`BrowserInput.SEARCH`）。
  點下去時整個選起來（貼上就是換掉，不會貼進原本的網址中間），打字時展開成多行（長網址整串看得到）
- 下面的工具列：上一頁、下一頁、重新整理／停止，右邊是「加到清單」（走一般的新增流程，加完回到瀏覽器）；
  正在看的網頁已經在清單裡（網址照字串比對）就顯示「已在清單」，點了進編輯頁
- 返回鍵：全螢幕時先退出全螢幕，能回上一頁就回上一頁，不能才關掉瀏覽器
- 網頁的對話框（alert、confirm、prompt、`<select>`、離開頁面與重新送出表單的確認）用 Compose 的 AlertDialog；
  長按連結、圖片有選單（開啟、加到清單、複製網址）；影片全螢幕時系統列也藏起來
- **不做**：下載（跳一句「不支援下載檔案」）、上傳檔案、歷史紀錄、無痕、登入資訊的儲存。
  權限一律拒絕（定位、相機、麥克風、通知、DRM、有聲的自動播放），只放行靜音的自動播放
- **先開好引擎**（`AppContainer.warmUpBrowser`）：清單出現 2 秒、主執行緒閒下來之後建立 GeckoRuntime、`Ublock.start()`，
  點進瀏覽器時不用等引擎啟動與 uBO 讀過濾清單（第一次安裝 uBO 也在這時候）。代價是 App 開著時多佔記憶體，`isLowRamDevice` 的手機不做
- **uBlock Origin 不打包進 APK**，跟 Firefox 一樣從 AMO 裝有 Mozilla 簽章的版本
  （`WebExtensionController.install`，`Ublock.AMO_URL`）：第一次打開瀏覽器時裝，`PromptDelegate` 只同意 uBO 自己的權限；
  裝好的留在 GeckoView 的設定檔，之後用 `list()` 找到就好；一天最多 `update()` 一次。
  不用 `ensureBuiltIn` 載入自己打包的 uBO：有人回報 `menus` 權限不被接受、最後 "Extension is invalid"
- uBO 的按鈕在網址列右邊：它自己的圖示（關掉過濾時變灰）＋這一頁擋了幾個（每個分頁一份 badge）。
  按下去的 popup（`ActionDelegate.onTogglePopup` 回傳的 GeckoSession）放在 BottomSheet 裡的 GeckoView（預設的 SurfaceView；
  TextureView 在模擬器上整塊是黑的）。BrowserTest 一直截圖到 popup 那一塊畫出東西（不是整片同一個顏色，模擬器上要好幾秒）。
  看得到的那一頁要 `setTabActive(session, true)`，popup 才知道要顯示哪一頁的統計

### 先不做（要做之前先問使用者）

匯入、雲端同步、帳號、分類／標籤、搜尋、手動拖曳排順序、網址正規化、匯出時把網路上的縮圖也下載進 zip。
瀏覽器的多分頁、歷史紀錄、無痕、下載、上傳。

### 之後可能會做（2026-10-31 跟使用者討論過，使用者說之後可能實作；開工前先跟使用者確認）

起因：GeckoView 約每 4 週一版、帶安全修補，不升級的話會有這些問題：
- 引擎的已知漏洞（惡意網頁、廣告就能利用）
- 內建憑證清單變舊，新網站被當成憑證有問題
- 新版 uBO 要求較新的引擎而更新不了，或擴充功能簽章的憑證到期被停用
- 新網站排版跑掉

升級本身只是改 `gradle/libs.versions.toml` 一行，麻煩的是發版與使用者手動下載安裝，所以想把這兩步自動化：

1. **自動檢查 GeckoView 新版**：GitHub Actions 每週排程一次（`schedule` cron），讀
   `https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/maven-metadata.xml` 的 `<release>`，
   比目前的新就開一個升級的 branch 改版本號、開 PR（record.yml 會跑完全部測試），使用者看 CI 綠了按合併。
   要注意：
   - GitHub Actions 用預設的 `GITHUB_TOKEN` 開的 PR 不會觸發其他 workflow，要用 PAT 或 GitHub App 的 token，
     或在同一個 workflow 裡直接呼叫 record.yml
   - 合併後要發版，仍然是改 `release-version` 那個檔案
2. **App 內提醒更新**：App 打開時（一天最多一次）查 GitHub 的 `releases/latest`
   （`https://api.github.com/repos/twtrubiks/thumblinks/releases/latest`），
   比對 versionCode（或 tag 的版本），有新版就在清單上方顯示「有新版本」＋下載按鈕
   （打開 `releases/latest/download/thumblinks.apk`，交給系統的下載與安裝畫面）。
   要注意：
   - 不要自己在背景下載安裝（要 `REQUEST_INSTALL_PACKAGES` 權限，也比較危險）
   - 查不到（沒網路、API 限流）就安靜跳過

**前提**：兩件都要先有正式的發版流程，也就是使用者在自己的電腦照 `docs/release-key.md` 產生發版金鑰、設好 Secrets。
現在手上的試用版是雲端 session 的臨時金鑰簽的，跟正式版簽章對不上：第一次換正式版要先移除試用版，清單會一起刪掉，
所以最好先做「匯入」，或先匯出備份。

同一次也討論過、還沒決定要不要做的：
- Baseline Profile：啟動與第一次捲動更順，要加一個在模擬器上產生 profile 的測試
- APK 的 native library 要不要壓縮：目前壓縮（`useLegacyPackaging`），下載約 96 MB，但安裝時會解壓出來，
  手機上約佔 260 MB（推算）；不壓縮的話下載與佔用都約 198 MB
- 瀏覽器：捲動時收起工具列＋下拉重新整理；選單加網頁內搜尋、分享、桌面版網站、文字大小；多分頁
- 讓 App 更小更快（使用者問過，先不動）：容量幾乎全是 GeckoView（約 90 MB），我們的程式加函式庫只有約 5 MB，
  拿掉一般功能省不到空間。真的要變小只能換掉瀏覽器引擎（Android WebView 或交給手機的瀏覽器開，APK 約 7 MB，但不能用 uBO）

使用者說「都不要做、記下來」的兩項（討論過的做法，開工前再跟使用者確認）：

- **拖拉排序**：容量、效能都沒影響（自己寫約 200 行或小函式庫約 100 KB；拖完只改一筆的順序）。建議的做法：
  - 每一列右邊加「≡」把手，按住把手才拖。長按其他地方維持「編輯」
  - 排序選單多一個「自訂」，只有自訂時能拖；新加的放最上面；匯出照畫面順序
  - `links` 加順序欄位（`ALTER TABLE ADD COLUMN`，舊資料照 rowid 排好；可以用介於前後兩筆之間的小數，拖一次只改一筆）
  - 拖到畫面邊緣要自動捲動。CI 用 UiAutomator 拖把手測
- **瀏覽紀錄**：App 大小不變。資料一筆幾百 bytes，看一萬頁約幾 MB；寫入在背景，感覺不到。
  GeckoView 的 `HistoryDelegate.onVisited` 會在每看完一頁時通知，寫進自己的資料表。
  `getVisited`（讓看過的連結變色）查詢很頻繁，要做就加索引，或乾脆不做。建議的基本版：
  - 記網址、標題、時間，同一頁只留最後一次；自動刪掉 90 天前的
  - 新的「瀏覽紀錄」頁，照日期分組；點了打開、長按「加到清單」；可以刪單筆或全部清掉
  - 網址列提示、紀錄內搜尋、連結變色之後再加
  - 要決定：要不要「暫時不記錄」的開關；要不要跟清單一起走系統備份（`allowBackup`），或用 backup rules 排除

## 程式的分層

- 規則都在純 Kotlin、有 JVM 測試：`LinkStore`（已有）、`WebUrls`、`PageMeta`、`MetaFetcher`、`Export`、`BrowserInput`（含錯誤頁）。
  `MetaFetcher` 的測試用 `mockwebserver3`（轉址、`image/*`、逾時、超過 1 MB、Big5）
- `LinkStore` 透過 `Sql` 介面存取資料庫：手機上是 `AndroidSql`（`databases/thumblinks.db`），
  JVM 測試是 `JdbcSql`（sqlite-jdbc 的記憶體資料庫，SQL 跑在真的 SQLite 上）。
  **Android 9 的 SQLite 是 3.22**：不要用 UPSERT、window function、`VACUUM INTO`、`RETURNING` 這些新語法，
  JVM 測試擋不下來，要靠 `DeviceTest` 在模擬器上跑
- 資料表 `links`：主鍵是 UUID（也拿來當上傳圖的檔名與 zip 裡的檔名）、`url` UNIQUE、
  `thumbnail_url` 與 `thumbnail_file` 二選一（CHECK 擋在資料庫層）。排序用 `rowid`（加入的順序）。
  改 schema 時用 `ALTER TABLE ... ADD COLUMN` 或在 `LinkStore` 的 init 裡判斷，手機上已經有的資料不能丟
- 要碰 Android 的放在薄薄一層：`AndroidSql`、`ThumbPrep`（DeviceTest 測）、畫面 `*Screen.kt`、
  整個 App 的導覽在 `ThumbLinksApp`（照 treesay 的 `TreeSayApp`：sealed interface 的 Route ＋ `rememberSaveable` 的 stack，
  不另外引入 Navigation 套件），共用的東西在 `AppContainer`（`ThumbLinksApplication.container`）
- 資料庫與網路一律在 `Dispatchers.IO`，不要在組畫面時讀資料庫
- 整個 App 共用一個 `OkHttpClient`（放在 `AppContainer`）；Coil 也用它：
  `ThumbLinksApplication` 實作 `SingletonImageLoader.Factory`，網路層用 `OkHttpNetworkFetcherFactory` 接同一個 client
- 內建瀏覽器：`GeckoRuntime` 一個行程只能有一個，放在 `AppContainer.gecko`，第一次打開瀏覽器時才建立（主執行緒）。
  GeckoView 會另外開網頁內容的子行程，子行程也跑 `ThumbLinksApplication`，所以 `onCreate` 裡不能碰 `container`。
  分頁（`BrowserTab`）也放在 `AppContainer`（key 是那一頁在導覽 stack 裡的 key，關掉那一頁時 `closeTab`），
  不放在組合（composition）裡：轉螢幕、進出新增頁都不會重新載入。GeckoView 的 delegate 都在主執行緒被呼叫，直接改 Compose 的狀態。
  GeckoView 自己的網路層不看 Android 的 network security config，release 版一樣載得了 `http://127.0.0.1`

## 專案約定

- Gradle 專案根目錄就是 repo 根目錄，`./gradlew` 在這裡跑
- applicationId / namespace：`com.twtrubiks.thumblinks`（`scripts/ci/record.sh` 依賴這個值，改了要一起改）。
  不要改：applicationId 一變，手機會當成另一個 App，原本的清單就接不回來
- 單一 module `app`，主畫面 `MainActivity`
- **minSdk 28（Android 9）**，compileSdk 37.2（GeckoView 159 要求，`compileSdk { version = release(37) { minorApiLevel = 2 } }`；
  缺 `platforms;android-37.2` 時 AGP 會自動下載），targetSdk 36，Java / Kotlin target 17
  （`java.time` 在 API 26 就內建，不需要 core library desugaring；新增 API 29+ 的 API 前先跑 `./gradlew lintDebug` 看 NewApi）
- 依賴版本集中在 `gradle/libs.versions.toml`
- AGP 9.x 使用 built-in Kotlin：**不要**套 `org.jetbrains.kotlin.android`；Compose 套 `org.jetbrains.kotlin.plugin.compose`，
  JSON 套 `org.jetbrains.kotlin.plugin.serialization`，版本都跟 Kotlin 一樣
- 版本起點（2026-10 查的最新穩定版；Android 相關的照 treesay，2026-10-31 在 VM 上 build 過，都不用改）：
  AGP 9.4.1、Kotlin 2.4.21、Gradle wrapper 9.8.2、Compose BOM 2026.09.00、activity-compose 1.13.0、core-ktx 1.19.1、
  lifecycle-runtime-ktx 2.11.0、OkHttp / mockwebserver3 5.5.1、jsoup 1.23.2、Coil 3.6.3、kotlinx-serialization-json 1.12.0、
  exifinterface 1.4.2、sqlite-jdbc 3.53.4.0（只給 JVM 測試）、androidx.test runner 1.7.0、core 1.7.0、ext:junit 1.3.0、uiautomator 2.4.0。
  若 build 報版本相容問題，以錯誤訊息為準調整，並更新這段
- **GeckoView**（`org.mozilla.geckoview:geckoview`，目前 159.0.20261022150525）只放在 `maven.mozilla.org`（`settings.gradle.kts` 只讓這個 group 從那裡拿）。
  它就是瀏覽器的引擎，約每 4 週一版、帶安全修補：**要跟著升**。最新版看
  `https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/maven-metadata.xml` 的 `<release>`，改 `libs.versions.toml` 再發一版
- **APK 按 CPU 拆開**（`splits.abi`）：GeckoView 每種 CPU 一份引擎，整包 500 MB 以上，只出 `arm64-v8a`（手機，release.yml 發這份）
  與 `x86_64`（CI 的模擬器，record.sh 裝這份）。輸出是 `app-<abi>-<debug|release>.apk`。
  native library 用壓縮的打包（`useLegacyPackaging`）：arm64 的 release 版約 95 MB（不壓縮是 197 MB，光 `libxul.so` 就 150 MB）
- `MainActivity` 的 `configChanges` 包含轉螢幕：不重建 Activity，GeckoView 不用重接畫面（Compose 自己會重排）
- OkHttp、jsoup、Coil、kotlinx.serialization、GeckoView 都自帶 R8 規則，`app/proguard-rules.pro` 目前是空的
- `android:allowBackup="true"`：清單只是公開網頁的連結，交給系統備份，換手機、重裝時接得回來。
  只備份資料庫、SharedPreferences 的 `settings`、上傳的縮圖（`res/xml/backup_rules.xml` 給 Android 11 以前、
  `data_extraction_rules.xml` 給 Android 12 以後，兩份要一樣）。內建瀏覽器的資料（GeckoView 的設定檔在 `files/mozilla/`，
  有 uBO 的過濾清單、cookie）不備份：自動備份每個 App 只有 25 MB，超過就整份不備份，清單也跟著沒了。
  上傳的縮圖一張約 100～300 KB，上傳的圖多到上百張時一樣會超過，到時候再考慮不備份縮圖（還原後那幾筆要重新選圖）
- debug 版的 network security config（`app/src/debug/`）只對 127.0.0.1 放行明碼連線，給測試的假網站用；release 版不帶
- 畫面用 Material 3，Android 12 以上用桌布取色（`Theme.kt`）。深淺預設跟系統，清單右上角的按鈕可以改成固定淺色／深色
  （`ThemeSetting`，存在 SharedPreferences `settings`）：系統列圖示的深淺跟著 App（`enableEdgeToEdge` 帶自己的判斷），
  內建瀏覽器的網頁也跟著（GeckoRuntime 的 `preferredColorScheme`，切換時馬上套用）。
  啟動那一瞬間的視窗底色（`values-night`）還是跟系統
- release 版開 R8（minify + shrinkResources）。簽名看環境變數：有 `THUMBLINKS_KEYSTORE`、`THUMBLINKS_KEYSTORE_PASSWORD`
  就用發版金鑰（PKCS12，alias `thumblinks`）；沒有就用這台機器的 debug 金鑰（錄影的 CI、自己 build）
- **給別人（包括使用者）裝的 APK 一律從 GitHub Release 來**，不要把 VM 裡 build 的 APK 傳給使用者：
  VM 的 debug 金鑰每個 session 都不一樣，簽章對不上的 APK 不能直接更新，只能移除重裝——**移除就會刪掉手機上的清單和上傳的圖**
  - 發版：`.github/workflows/release.yml`。把 `release-version` 改成新版本（例如 `2026.11.01`，同一天第二版寫 `2026.11.01.2`）推上去，
    就會 build、簽名、建 `v<版本>` 的 tag 並發 Release；那個版本已經發過的話跳過。
    雲端 session 的 git 只能推自己的 branch，推 tag 會斷線，所以用版本檔
  - **還沒有發版金鑰**：第一次發版前，使用者要在自己的電腦照 `docs/release-key.md` 產生金鑰、設 Secrets、
    把憑證 SHA-256 填進 `release.yml` 的 `EXPECTED_CERT`，再新增 `release-version`。不要在 VM 裡產生金鑰
  - **2026-10-31 使用者要求先直接給一個 APK 試用**：給的是那個 session 的 VM debug 金鑰簽的 release 版（commit `884bc36`，
    versionCode 1，憑證 SHA-256 `9adf6eb0…4ea897`）。那把金鑰已經跟著 session 消失，之後的 Release 簽章對不上，
    使用者要移除這一版才能裝正式版，清單會一起刪掉（還沒有匯入）。發第一版 Release 時提醒使用者這件事。
    同一個 session 後來又給了內建瀏覽器的試用版（`claude/browser-geckoview` 的 `534151d`，arm64，versionCode 2、
    versionName `0.2-browser`），同一把金鑰，覆蓋安裝在上面那一版上；
    再來是修了清單與瀏覽器一片白的 `97b3770`（versionCode 3、`0.3-browser`）、
    修了 `window.open()` 不帶網址的 `1662901`（versionCode 4、`0.4-browser`）、
    修了 uBO popup 整塊黑與網址列閃 `about:blank` 的 `74c64e1`（versionCode 5、`0.5-browser`）、
    加了深色模式、排序、輸入框換行、瀏覽器預熱的 `9198703`（versionCode 6、`0.6-browser`）
  - versionCode 是 2026-01-01 起算的分鐘數（workflow 算好用環境變數帶進來），不管從哪個 branch 發都只會變大
  - README 的下載連結是 `releases/latest/download/thumblinks.apk`

## 雲端 VM 環境

- Android SDK 由環境的 setup script（`scripts/cloud-setup.sh`）裝在 `/opt/android-sdk`，`ANDROID_HOME` 由環境變數提供。
  跟 treesay 用同一個環境就可以
- SDK 不存在時先看 `/var/log/android-sdk-setup.log`
- 新 session 第一次 build 可能遇到 Maven Central 回 `429 Too Many Requests`（共用出口 IP 被限流），
  隔 20 秒左右重跑 `./gradlew` 即可，依賴進快取後就不會再遇到；不要為此改 repository 設定（CI 不受影響）。
  錯誤訊息通常不會直接寫 429，而是 `Plugin [id: '...'] was not found in any of the following sources`、
  `Could not resolve <group>:<artifact>:<version>` 或 `Configuration cache state could not be cached`，
  加 `--info` 才看得到底下的 `HTTP 429`。新 session 第一次 build 看到這幾種，先重跑，不要去改版本
- VM 的網路只放行套件庫，抓不到一般網頁：抓縮圖的邏輯靠 JVM 測試的 MockWebServer，真的連外交給 CI 的模擬器。
  `maven.mozilla.org`（GeckoView）連得到；AMO（uBO）連不到，只能在 CI 測（`BrowserTest`）

## 在 VM 內驗證（每次 push 前）

```bash
./gradlew assembleDebug assembleRelease testDebugUnitTest assembleDebugAndroidTest
```

`assembleRelease` 會跑 R8，可以先抓到缺 class 之類的錯誤；執行時才會出現的問題要看 CI 的 release 冒煙測試。
和 Android 無關的邏輯（取網址、解析 meta、匯出格式）寫成純 Kotlin，放在 JVM 單元測試裡測，幾秒就有結果。
CI 錄影前也會跑 `testDebugUnitTest`，沒過就不會錄影（失敗原因在 `gradle-build.txt`）。

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
- **網頁由模擬器上的假網站提供**（`mockwebserver3`，debug 版允許 127.0.0.1 明碼連線），不連外：
  有 og:image 與 og:title 的網頁、沒有縮圖的網頁、圖片本身（測試裡用 `Bitmap` 畫一張 PNG），都在 `FakeSite`。
  展示流程：貼網址 → 抓到縮圖與標題 → 改標題 → 加入；從「瀏覽器」分享同一個網址進來 → 已經在清單裡了 → 打開那一筆；
  貼沒有縮圖的網址 → 貼圖片網址 → 加入；點一筆用內建瀏覽器打開（已在清單）→ 網址列換一篇 → 加到清單；長按刪除一筆；打開匯出；
  右上角切成深色模式再切回跟系統、排序換成舊的在上再換回來（設定存在 SharedPreferences，不切回來會留給下一個測試）。
  uBO 要連 AMO，不放在錄影裡
- 內建瀏覽器的網頁內容在 GeckoView 裡，UiAutomator 不保證讀得到；只操作我們自己畫的部分（`url_bar`、`add_to_list`、`in_list`、
  `ublock_button`、`ublock_badge`、`ublock_popup` 這些 testTag）。網址列打字：先點一下拿到焦點、`text = …`、`device.pressEnter()`
- 網頁載完了沒用 `UiTest.loadPage(url) { 動作 }`：看 `BrowserTab` 寫進 logcat 的「網頁載入完成」。
  工具列的「重新整理」在開始載入之前也是它，拿來等會在網頁還一片白的時候就截圖（模擬器第一次開 GeckoView 要好幾秒）
  新的 GeckoSession 會先載入一次 `about:blank`（在我們要的網址開始之前就結束），`BrowserTab.Progress` 跳過它不寫，
  不然還沒開始載就有「網頁載入完成」（release 冒煙測試曾經因此截到一片白）。
- 包著 GeckoView 的 `AndroidView` 加 testTag UiAutomator 也讀不到；要點網頁時用座標（網址列下緣到「上一頁」上緣之間）
- BottomSheet、Dialog 是另一個視窗，App 根部開的 `testTagsAsResourceId` 管不到：要在裡面再開一次（uBO 的 popup 有開），或用 `By.text` 找
- uBO 的 badge 一直在更新（換頁時先清掉再重算），讀字時元件可能已經舊了（`StaleObjectException`）
- 開始前清空清單（`container.links`），要用到的資料都在測試裡建
- 鍵盤開著才按返回鍵收起（`hideKeyboard()`）——沒開的時候按返回鍵會離開那一頁

## 模擬器上的測試：DeviceTest

`app/src/androidTest/java/com/twtrubiks/thumblinks/DeviceTest.kt`，`record.sh` 在錄影前先跑、不錄影，輸出在 `device-test.txt`。
只放模擬器才測得到的事：`LinkStore` 在 Android 9 的 SQLite 上跑一輪；做了上傳之後，`ThumbPrep` 用真的 Bitmap 與 ExifInterface 縮圖轉正
（直拍的 JPEG、手機拍的 HEIC；只看長寬的話轉錯方向也會過，要用上下兩色的圖核對轉正後顏色的位置，做法見 treesay 的 `DeviceTest`）。

## 系統畫面的測試：SystemUiTest

`app/src/androidTest/java/com/twtrubiks/thumblinks/SystemUiTest.kt`，不錄影。`record.sh` 在錄影結束、截圖 pull 回來之後先 `pm clear` 再跑，
輸出在 `system-ui-test.txt`、`logcat-system-ui.txt`，截圖是 `system-*.png`。
只放不是 App 畫的畫面，每個 Android 版本長得不一樣，只能點一次才知道接不接得上：
- 系統的照片挑選器：Android 9、10 是檔案畫面（找得到檔名），Android 11 以後是照片挑選器（只有縮圖，挑第一張）。
  照片先放進相簿：Android 10 以後 App 直接寫 MediaStore，Android 9 要權限，交給 shell 複製再廣播掃描
- 存檔的檔案畫面：有上傳的圖，匯出 Markdown 存到 Download 是 zip（先從左上角的選單切到 Downloads，停在「最近」的話不能存），
  用 `uiAutomation.executeShellCommand("cat …")` 讀出原始 bytes 解開，核對 `links.md` 與 `images/<id>.jpg`。
  這個指令不經過 sh：不能用引號、萬用字元，檔名要確定（同名的會被系統存成「… (1).zip」，所以開始前先刪掉）

寫法照 treesay 的 `SystemUiTest` 與 `android/scripts/ci/record.sh`。

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

## release 冒煙測試

`record.sh` 在 SystemUiTest 之後覆蓋安裝 release 版（同一把 debug 金鑰，可以直接覆蓋），打開 App 截圖（`screenshots/release-1-launch.png`），
再用 `uiautomator dump` 找元件：按「新增」、輸入 `https://github.com/git/git`、按「抓縮圖」，等到預覽的 testTag 變成 `thumb_ready`
（Coil 真的把縮圖載出來了，`release-2-fetched.png`），按「加入」，清單上出現 `github.com`（`release-3-added.png`）。
R8 壓過的 OkHttp、jsoup、Coil 只有真的抓一次才跑得到；release 版不認 127.0.0.1 的明碼連線，不能用假網站。
接著點那一筆，用內建瀏覽器打開它，等 logcat 出現 App 寫的兩行（`BrowserTab` 的「網頁載入完成 success=true https://github.com/git/git」、
`Ublock` 的「uBlock Origin … 可以用了」）：壓過的 GeckoView 要真的載入網頁、從 AMO 裝好 uBO。
網頁一直在動，`uiautomator dump` 等不到畫面靜止，所以瀏覽器這一段看 logcat 不用 dump。
**不用 github.com 首頁**：首頁有自動播放的靜音影片，模擬器（軟體解碼、軟體繪圖）在影片播放時離開 App，SurfaceFlinger 卡死，
system_server 被 watchdog 殺掉（`WATCHDOG KILLING SYSTEM PROCESS`、`DeadSystemException`），後面的 BrowserTest 一個都沒跑。
以前沒事只是因為「網頁載入完成」太早出現（`about:blank` 那次），影片還沒開始播就離開了。repo 頁面沒有影片，一樣有 og:image 與標題。
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
   - `/tmp/media/browser-test.txt`、`logcat-browser.txt`：BrowserTest（uBO）的輸出與 log
   - `/tmp/media/release-smoke.txt`、`logcat-release.txt`：release 冒煙測試的啟動時間與 log
   - `/tmp/media/demo.mp4`：壓縮後的影片（給使用者看）

看完 frames 確認畫面正常後，把 `/tmp/media/demo.mp4` 複製到 session 的 scratchpad 目錄，
用 SendUserFile（`display: render`）直接傳給使用者，讓使用者不用 pull 就能播放。傳之前先核對 `info.txt` 的 commit 是這次的。

## 環境設定

claude.ai/code 的環境設定步驟寫在 `docs/cloud-setup.md`，改了 `scripts/cloud-setup.sh` 或網路設定要一起更新。

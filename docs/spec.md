# 規格

App 每個功能該怎麼運作。改功能之前先讀對應的段落，改了行為要一起更新這裡。
先不做、之後可能做的在 `CLAUDE.md` 與 `docs/ideas.md`。

## 加入連結

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

## 清單

- 右上角：深色模式（跟系統／淺色／深色）、排序、只看愛心、「瀏覽」、「更多」（`more_button`：匯入、匯出）
- **愛心**（標記比較喜歡的）：每一筆右邊有愛心（`HeartToggle`，自己的 48dp 點擊範圍，點了不會打開網頁），
  編輯頁右上角也有，按了就存（不用按「儲存」）。清單上按的時候畫面先換、資料庫在背景寫（`ThumbLinksApp.setFavorite`）。
  右上角的愛心是「只看愛心」（`visibleLinks`，純 Kotlin、JVM 測），排序照用；一筆都沒有時說明怎麼按。
  「只看愛心」不存起來（`rememberSaveable`）：下次打開 App 看到的是全部，不會以為清單少了東西。
  JSON 匯出帶愛心、匯入時接回來；Markdown 不帶
- 首頁就是清單，預設新加的在上面。**排序**（右上角，`SortOrder`，`sortLinks` 純 Kotlin、JVM 測）：新的在上／舊的在上／標題／網域，
  記在 SharedPreferences `settings`；標題、網域一樣的照新的在上，標題不分大小寫、中文照語系（Collator），沒有標題的用網址。
  匯出的順序跟畫面一樣（`export()` 也照目前的排序）。換排序時捲回最上面每筆顯示縮圖、標題（沒有標題就顯示網址）、網域。
  回到清單時多了新的一筆就捲到它（`LazyColumn` 會跟著原本的第一筆走，新的那筆會被擠到畫面外看不到）
- **搜尋**：清單上面有搜尋框（`search_input`，固定在上面不跟著捲），打字時就篩（`visibleLinks` 的 `query`，純 Kotlin、JVM 測）：
  找標題與網址（網域在網址裡），不分大小寫；用空白（含全形）切開的每一段都要找得到。跟排序、只看愛心一起用。
  有字時右邊有清除鈕、返回鍵先清掉搜尋；找不到時說「找不到「…」」。搜尋的字不存（`rememberSaveable`：進編輯頁再回來還在，重開 App 清掉）。
  清單是空的時候不顯示搜尋框，搜尋的字也一起清掉。加了新的一筆、它被搜尋篩掉時清掉搜尋（才看得到新的那筆；「只看愛心」不動）。匯出的還是整份清單，不是搜尋的結果
- **長按一筆跳選單**（`LinkRow` 的 DropdownMenu）：「編輯」、「複製網址」、「刪除」（要確認，`ConfirmDelete`；
  畫面上先拿掉、資料庫與上傳的圖在背景刪，`ThumbLinksApp.delete`）。之後要加的動作（分享…）也放這裡
- **複製網址**：清單的長按選單、編輯頁、內建瀏覽器（右上角選單、長按連結或圖片）都用 `copyUrl`，只複製網址本身。
  複製後 Android 12 以前 App 自己跳「已複製網址」；Android 13 以後系統自己會在畫面下方提示，不再跳一次
- 編輯頁（長按選單的「編輯」）：改標題、換縮圖（重新抓、貼圖片網址、上傳）、用瀏覽器開、複製網址、
  刪除（右上角的垃圾桶圖示，`delete_button`，contentDescription「刪除這一筆」，要確認）。
  換掉或刪掉上傳的縮圖時，舊的檔案要刪（`LinkStore.update`、`LinkStore.delete` 會刪）。
  在這一頁上傳、還沒存的圖由 `ThumbDraft` 記著，放棄這一頁（返回鍵）或又換一張時刪掉
- 空清單時說明怎麼加：貼網址，或從瀏覽器「分享」過來；也有「從檔案匯入」（`import_button`，換手機、重裝後最常用）
- 「更多」裡的「匯出」在清單是空的時候停用

## 匯出

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
      "created_at": "2026-10-31T20:00:00+08:00",
      "favorite": true
    },
    {
      "url": "https://example.org/page",
      "title": null,
      "thumbnail": "images/0b6c1c1e-4f1a-4b1e-9a0e-3f0f2d7c9a11.jpg",
      "created_at": "2026-10-30T09:12:00+08:00",
      "favorite": false
    }
  ]
}
```

- 沒有標題寫 `null`，不省略欄位
- `thumbnail` 是字串：`http(s)://` 開頭是網路上的圖，其餘是 zip 裡的相對路徑
- 時間是當地時間＋時差，到秒（`LinkStore` 存的就是這個格式）
- `favorite`（愛心）`true`／`false` 都寫

## 匯入

把自己匯出的檔案讀回來（換手機、重裝、從試用版換正式版）。規則在 `Import`（純 Kotlin、JVM 測），畫面在 `ListScreen`。

- 入口：右上角「更多」→「匯入」，清單是空的時候畫面中間也有「從檔案匯入」
- 選檔用系統的開檔畫面（`ActivityResultContracts.OpenDocument`），類型不限（`*/*`：Markdown 在 Android 9 常是 `application/octet-stream`）；
  **看內容不看副檔名**：`PK` 開頭是 zip（找 `links.json`，沒有再找 `links.md`），`{` 開頭是 JSON，其他當 Markdown。
  都不是（一筆都讀不到）就說「找不到連結」
- 選的檔案先複製進 `cacheDir/import/`（`ZipFile` 要能隨機讀），最大 1 GB；文字檔最大 20 MB，zip 裡一張圖最大 20 MB
- 先讀一遍、算好再問（`Import.plan`）：「匯入 N 筆」＋檔案裡有幾筆、幾筆已經在清單裡、幾筆讀不出來，按「匯入」才加。
  加完 snackbar 寫「加入 N 筆，M 筆已經在清單裡，X 筆讀不出來」
- **網址已經在清單裡就跳過**（照字串比對），手機上那筆的標題、縮圖、愛心都不動；同一個檔案裡重複的只算第一筆。再匯入同一個檔案不會多東西
- 讀不出來（跳過、其他照加）：網址不是 `http(s)://`、少了網址或縮圖、縮圖指到 zip 裡沒有的圖、圖解不開
- JSON：時間、愛心照檔案；少了時間（或寫錯）用匯入的時間，少了愛心當作沒有；多的欄位不理
- Markdown：一行 `[![替代文字](縮圖)](網址)`，其他的行不理。替代文字去掉 `\` 跳脫，跟網址一樣就是沒有標題；
  網址只解 `Export.linkTarget` 編的那五個（`%20`、`%28`、`%29`、`%3C`、`%3E`）。沒有時間與愛心：用匯入的時間、沒有愛心。
  原本的網址就有這五種 `%xx` 的話分不出來（會解成字元），所以查重複時解碼前的寫法（`ImportedLink.writtenUrl`）也查，
  不然匯回同一支手機會多一筆。匯出時不能把 `%` 也編掉：Markdown 的連結點下去，瀏覽器會照字送出 `%2528`，打開的不是原本那一頁
- 加的順序（清單「新的在上」用加入的順序）：照檔案裡的時間從舊到新加，匯出時用的是什麼排序都一樣；
  時間一樣或沒有時間的，檔案裡下面的先加（匯出預設新的在上，加完順序跟原本一樣）
- zip 裡的圖存進 `thumbs/<新的 uuid>.jpg`（`ThumbPrep.saveImported`）：已經是整理好的（JPEG、長邊 1024 以內、不用轉）原樣存，
  不然每搬一次手機就重壓一次 JPEG；其他的照上傳的規則整理

## 內建瀏覽器

使用者自己用、不上架。GeckoView 只負責顯示網頁，外殼（網址列、按鈕、對話框）都是自己寫的。

- 入口在清單：**點一筆用內建瀏覽器打開**（長按跳選單：編輯、複製網址、刪除）、右上角「瀏覽」開空白頁（網址列等著輸入）、編輯頁的「用瀏覽器開」。
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
  裝好的留在 GeckoView 的設定檔，之後用 `list()` 找到就好，不會再下載。
  不用 `ensureBuiltIn` 載入自己打包的 uBO：有人回報 `menus` 權限不被接受、最後 "Extension is invalid"
- **uBO 自己更新**（`Ublock.maybeUpdate`）：App 打開時（`start()`），距離上次問 AMO 超過 24 小時（跟 Firefox 桌面版一樣）才 `update()`，
  有新版才下載。沒有背景排程：App 一直開著不會再檢查。失敗（沒網路）不記時間，下次打開 App 再試。
  **uBO 會要求延後**（它有聽 `runtime.onUpdateAvailable`，正在用的時候不換）：`update()` 回 `InstallException` 的 `ERROR_POSTPONED`（-101），
  這不是失敗，新版已經下載好，引擎下次啟動（下次打開 App）時才換上，所以實際上是「隔一次打開 App」才用到新版。
  結果寫進 logcat（「已經是最新」「有新版 x，已經下載好，下次打開 App 時換上」「從 x 更新到 y」「檢查更新失敗」）；
  瀏覽器右上角選單的最下面顯示 uBO 的版本、下載好還沒換上的新版、上次檢查更新的時間。
  過濾清單（廣告規則）是 uBO 自己每隔幾天更新，跟 Firefox 上的 uBO 一樣
- uBO 的按鈕在網址列右邊：它自己的圖示（關掉過濾時變灰）＋這一頁擋了幾個（每個分頁一份 badge）。
  按下去的 popup（`ActionDelegate.onTogglePopup` 回傳的 GeckoSession）放在 BottomSheet 裡的 GeckoView（預設的 SurfaceView；
  TextureView 在模擬器上整塊是黑的）。BrowserTest 一直截圖到 popup 那一塊畫出東西（不是整片同一個顏色，模擬器上要好幾秒）。
  看得到的那一頁要 `setTabActive(session, true)`，popup 才知道要顯示哪一頁的統計

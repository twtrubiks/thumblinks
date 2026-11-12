# ThumbLinks

收集網址的 Android App：貼上網址 → 抓縮圖（必填）與標題（選填）→ 整份清單一次匯出成 Markdown 或 JSON（也能再匯入回來）。
內建一個會擋廣告的瀏覽器（GeckoView ＋ uBlock Origin），看到喜歡的網頁直接「加到清單」。
Kotlin + Jetpack Compose 原生 App，在 claude.ai/code 雲端 session 開發。
雲端 VM 沒有模擬器，模擬器操作與錄影交給 GitHub Actions（`.github/workflows/record.yml`）。

## 目前的進度

規格裡的功能都做了：

- 規則（純 Kotlin、JVM 測試）：`WebUrls`（取網址、網域）、`PageMeta`（解析 meta、Big5）、`MetaFetcher`（OkHttp）、
  `Export`（Markdown／JSON／zip）、`Import`（讀回匯出的檔案）、`LinkStore`（新增、查重複、改、刪、愛心）、
  `sortLinks`／`visibleLinks`（排序、只看愛心、搜尋）
- 畫面：清單（`ListScreen`，含搜尋、匯入、匯出）、新增（`AddScreen`）、編輯（`EditScreen`）、縮圖預覽與兩個補救（`Thumbnails.kt` 的
  `ThumbnailEditor`／`ThumbDraft`）、上傳的圖（`ThumbPrep`）、分享進來（`MainActivity`，`singleTask` ＋ `onNewIntent`）
- 內建瀏覽器：`BrowserScreen`、`BrowserTab`（一個 GeckoSession 與它的 delegate）、
  `Ublock`（從 AMO 安裝、更新、badge、popup）、`BrowserInput`（網址列的規則，JVM 測試）。還沒做：多分頁
- 模擬器上的測試：`DeviceTest`、`DemoRecordingTest`（錄影）、`SystemUiTest`、`BrowserTest`／`UblockUpdateTest`、release 冒煙測試（見 `docs/testing.md`）。假網站在 `FakeSite`

改東西之前先跑「在 VM 內驗證」，改完看 CI 的錄影。


## 規格

每個功能該怎麼運作寫在 `docs/spec.md`（加入連結、清單、匯出、匯入、內建瀏覽器）。**改功能之前先讀對應的段落**，改了行為要一起更新。

### 先不做（要做之前先問使用者）

雲端同步、帳號、分類／標籤、手動拖曳排順序、網址正規化、匯出時把網路上的縮圖也下載進 zip、匯入時用檔案裡的覆蓋清單上的。
瀏覽器的多分頁、歷史紀錄、無痕、下載、上傳。

### 之後可能會做

跟使用者討論過、記下來的想法在 `docs/ideas.md`（GeckoView 自動升級、App 內提醒更新、Baseline Profile、APK 壓縮、瀏覽器的功能、讓 App 更小、拖拉排序、瀏覽紀錄）。開工前先跟使用者確認。

## 程式的分層

- 規則都在純 Kotlin、有 JVM 測試：`LinkStore`、`WebUrls`、`PageMeta`、`MetaFetcher`、`Export`、`Import`、`BrowserInput`（含錯誤頁）。
  `MetaFetcher` 的測試用 `mockwebserver3`（轉址、`image/*`、逾時、超過 1 MB、Big5）
- `LinkStore` 透過 `Sql` 介面存取資料庫：手機上是 `AndroidSql`（`databases/thumblinks.db`），
  JVM 測試是 `JdbcSql`（sqlite-jdbc 的記憶體資料庫，SQL 跑在真的 SQLite 上）。
  **Android 9 的 SQLite 是 3.22**：不要用 UPSERT、window function、`VACUUM INTO`、`RETURNING` 這些新語法，
  JVM 測試擋不下來，要靠 `DeviceTest` 在模擬器上跑
- 資料表 `links`：主鍵是 UUID（也拿來當上傳圖的檔名與 zip 裡的檔名）、`url` UNIQUE、
  `thumbnail_url` 與 `thumbnail_file` 二選一（CHECK 擋在資料庫層）。排序用 `rowid`（加入的順序）。
  `favorite`（0／1）是後來加的：`LinkStore` 的 init 用 `PRAGMA table_info` 看有沒有，沒有就 `ALTER TABLE ADD COLUMN`（新裝的也走這條路）。
  改 schema 時用 `ALTER TABLE ... ADD COLUMN` 或在 `LinkStore` 的 init 裡判斷，手機上已經有的資料不能丟
- 要碰 Android 的放在薄薄一層：`AndroidSql`、`ThumbPrep`（DeviceTest 測）、畫面 `*Screen.kt`、
  整個 App 的導覽在 `ThumbLinksApp`（sealed interface 的 Route ＋ `rememberSaveable` 的 stack，
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
- **minSdk 28（Android 9）**，compileSdk 37.2（GeckoView 要求，`compileSdk { version = release(37) { minorApiLevel = 2 } }`；
  缺 `platforms;android-37.2` 時 AGP 會自動下載），targetSdk 36，Java / Kotlin target 17
  （`java.time` 在 API 26 就內建，不需要 core library desugaring；新增 API 29+ 的 API 前先跑 `./gradlew lintDebug` 看 NewApi）
- 依賴版本集中在 `gradle/libs.versions.toml`（GeckoView 也是）；build 報版本相容問題時，以錯誤訊息為準調整
- AGP 9.x 使用 built-in Kotlin：**不要**套 `org.jetbrains.kotlin.android`；Compose 套 `org.jetbrains.kotlin.plugin.compose`，
  JSON 套 `org.jetbrains.kotlin.plugin.serialization`，版本都跟 Kotlin 一樣
- **GeckoView**（`org.mozilla.geckoview:geckoview`）只放在 `maven.mozilla.org`（`settings.gradle.kts` 只讓這個 group 從那裡拿）。
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
- 明碼連線（`http://`）放行（`res/xml/network_security_config.xml`）：使用者貼的 `http://` 網址、`http://` 的縮圖都要抓得到；
  測試的假網站（`http://127.0.0.1`）也靠它
- 畫面用 Material 3，Android 12 以上用桌布取色（`Theme.kt`）。深淺預設跟系統，清單右上角的按鈕可以改成固定淺色／深色
  （`EnumSetting`，存在 SharedPreferences `settings`）：系統列圖示的深淺跟著 App（`enableEdgeToEdge` 帶自己的判斷），
  內建瀏覽器的網頁也跟著（GeckoRuntime 的 `preferredColorScheme`，切換時馬上套用）。
  啟動那一瞬間的視窗底色（`values-night`）還是跟系統
- release 版開 R8（minify + shrinkResources）。簽名看環境變數：有 `THUMBLINKS_KEYSTORE`、`THUMBLINKS_KEYSTORE_PASSWORD`
  就用發版金鑰（PKCS12，alias `thumblinks`）；沒有就用這台機器的 debug 金鑰（錄影的 CI、自己 build）
- **給使用者裝的 APK**：正式版從 GitHub Release 來。VM 的 debug 金鑰每個 session 都不一樣，
  簽章對不上的 APK 不能直接更新，只能移除重裝——**移除就會刪掉手機上的清單和上傳的圖**
  - 發版：`.github/workflows/release.yml`。版本號用語意化版本（`1.0.0`、`1.0.1`…）。
    推 tag `v<版本>`，或把 `release-version` 改成新版本（例如 `1.0.1`）推上去，
    就會 build、簽名、建 `v<版本>` 的 tag 並發 Release；那個版本已經發過的話跳過。
    雲端 session 的 git 只能推自己的 branch，推 tag 會斷線，所以用版本檔
  - **發版金鑰**在 GitHub Secrets（`ANDROID_SIGNING_KEYSTORE`、`ANDROID_SIGNING_PASSWORD`），金鑰檔與密碼由使用者保管；
    憑證 SHA-256 在 `release.yml` 的 `EXPECTED_CERT`。**不要再產生新的**：換了金鑰，手機上的正式版就不能直接更新
  - 不是發版金鑰簽的 APK（自己 build 的、試用版）跟正式版簽章對不上：換成正式版前先匯出（JSON 或 zip，帶愛心）、
    移除舊的、裝正式版後再匯入
  - versionCode 是 2026-01-01 起算的分鐘數（workflow 算好用環境變數帶進來），不管從哪個 branch 發都只會變大
  - README 的下載連結是 `releases/latest/download/thumblinks.apk`

## 雲端 VM 環境

- Android SDK 由環境的 setup script（`scripts/cloud-setup.sh`）裝在 `/opt/android-sdk`，`ANDROID_HOME` 由環境變數提供。
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

## 分支與 PR

- 推到哪個分支照使用者說的（現在直接在 `main` 上做）。雲端 session 的 git 只能推自己的 `claude/…` 分支，
  使用者要求才開 PR，CI 過了才合併
- 合併 PR 用 **Rebase and merge**（使用者的選擇）：`main` 上的 commit 會換成新的 hash，跟分支上的對不上。
  合併後還要在同一個分支繼續做，先從最新的 `main` 重開（`git checkout -B <分支> origin/main`），
  再用 `git push --force-with-lease` 蓋掉遠端的分支。force push、改寫 `main` 的歷史都要先問使用者

## 測試與錄影

模擬器上的測試（`DeviceTest`、`DemoRecordingTest`、`SystemUiTest`、`BrowserTest`／`UblockUpdateTest`、release 冒煙測試）怎麼寫、
CI 的錄影怎麼觸發與怎麼看結果（`media` branch）、變異測試怎麼跑，都在 `docs/testing.md`。**改測試或 `record.sh` 之前、看 CI 結果之前先讀**。

## 環境設定

claude.ai/code 的環境設定步驟寫在 `docs/cloud-setup.md`，改了 `scripts/cloud-setup.sh` 或網路設定要一起更新。

# 之後可能會做

跟使用者討論過、使用者說之後可能實作的想法。開工前先跟使用者確認。

## GeckoView 自動升級、App 內提醒更新

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
所以要先匯出（JSON 或 zip），裝好正式版再匯入。

## 也討論過、還沒決定要不要做的

- Baseline Profile：啟動與第一次捲動更順，要加一個在模擬器上產生 profile 的測試
- APK 的 native library 要不要壓縮：目前壓縮（`useLegacyPackaging`），下載約 96 MB，但安裝時會解壓出來，
  手機上約佔 260 MB（推算）；不壓縮的話下載與佔用都約 198 MB
- 瀏覽器：捲動時收起工具列＋下拉重新整理；選單加網頁內搜尋、分享、桌面版網站、文字大小；多分頁
- 讓 App 更小更快（使用者問過，先不動）：容量幾乎全是 GeckoView（約 90 MB），我們的程式加函式庫只有約 5 MB，
  拿掉一般功能省不到空間。真的要變小只能換掉瀏覽器引擎（Android WebView 或交給手機的瀏覽器開，APK 約 7 MB，但不能用 uBO）

## 使用者說「都不要做、記下來」的兩項

討論過的做法，開工前再跟使用者確認。


- **拖拉排序**：容量、效能都沒影響（自己寫約 200 行或小函式庫約 100 KB；拖完只改一筆的順序）。建議的做法：
  - 每一列右邊加「≡」把手，按住把手才拖。長按其他地方維持原本的選單（編輯、複製網址）
  - 排序選單多一個「自訂」，只有自訂時能拖；新加的放最上面；匯出照畫面順序
  - `links` 加順序欄位（`ALTER TABLE ADD COLUMN`，舊資料照 rowid 排好；可以用介於前後兩筆之間的小數，拖一次只改一筆）
  - 拖到畫面邊緣要自動捲動。CI 用 UiAutomator 拖把手測
- **瀏覽紀錄**：App 大小不變。資料一筆幾百 bytes，看一萬頁約幾 MB；寫入在背景，感覺不到。
  GeckoView 的 `HistoryDelegate.onVisited` 會在每看完一頁時通知，寫進自己的資料表。
  `getVisited`（讓看過的連結變色）查詢很頻繁，要做就加索引，或乾脆不做。建議的基本版：
  - 記網址、標題、時間，同一頁只留最後一次；自動刪掉 90 天前的
  - 新的「瀏覽紀錄」頁，照日期分組；點了打開、長按「加到清單」；可以刪單筆或全部清掉
  - 網址列提示、紀錄內搜尋、連結變色之後再加
  - 要決定：要不要「暫時不記錄」的開關；要不要跟著系統備份（backup rules 備份整個 `databases/`，
    放在 `thumblinks.db` 就會跟著備份；不要的話另開一個資料庫檔、從 rules 排除）

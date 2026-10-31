# ThumbLinks

收集網址的 Android App：貼上網址就幫你抓縮圖（必填）和標題（選填），
整份清單一次匯出成 Markdown 或 JSON。

```markdown
[![標題](https://example.com/og.jpg)](https://example.com/article)
```

- 縮圖抓網頁的 `og:image` / `twitter:image`；抓不到時可以自己貼圖片網址，或從手機上傳一張
- 有上傳的圖時，匯出成 zip（`links.md` 或 `links.json` ＋ `images/`）
- 內建會擋廣告的瀏覽器（GeckoView ＋ 從 Mozilla 附加元件網站安裝的 uBlock Origin），看到喜歡的網頁直接「加到清單」
- Android 9 以上（arm64 手機），Kotlin + Jetpack Compose

## 下載

[最新版 APK](https://github.com/twtrubiks/thumblinks/releases/latest/download/thumblinks.apk)（還沒發第一版前這個連結是空的）

## 開發

在 [claude.ai/code](https://claude.ai/code) 雲端 session 開發，畫面靠 GitHub Actions 的模擬器錄影確認。

- 規格、架構、約定：[`CLAUDE.md`](CLAUDE.md)
- 雲端環境設定：[`docs/cloud-setup.md`](docs/cloud-setup.md)
- 發版金鑰：[`docs/release-key.md`](docs/release-key.md)

架構沿用 [treesay](https://github.com/twtrubiks/treesay) 的 Android 版。

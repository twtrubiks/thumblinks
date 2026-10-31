# 產生發版金鑰（只做一次）

給別人裝的 APK 一律從 GitHub Release 來，每一版都要用**同一把**金鑰簽：
換了金鑰，手機上的舊版就不能直接更新，只能移除重裝，清單會一起刪掉。
所以這把金鑰在自己的電腦上產生一次，備份好，之後永遠用它。

**不要在雲端 session 裡產生**：VM 每個 session 都是新的，金鑰檔留不住。

## 1. 產生 PKCS12 金鑰

在自己的電腦上（需要 JDK 的 `keytool`），放在 repo 外面：

```bash
cd ~/work/experiments
keytool -genkeypair -v -storetype PKCS12 -keystore thumblinks-release.p12 \
  -alias thumblinks -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=ThumbLinks"
```

密碼問一次就好（PKCS12 的金鑰檔與金鑰共用同一個密碼）。
`thumblinks-release.p12` 與密碼要備份（密碼管理器、另一顆硬碟），弄丟了就再也發不出能直接更新的版本。
`.gitignore` 已經擋掉 `*.p12`，但還是不要放進 repo 的資料夾裡。

## 2. 設定 GitHub Secrets

```bash
base64 -w0 thumblinks-release.p12 | gh secret set ANDROID_SIGNING_KEYSTORE --repo twtrubiks/thumblinks
gh secret set ANDROID_SIGNING_PASSWORD --repo twtrubiks/thumblinks   # 貼上密碼
```

## 3. 把憑證的 SHA-256 填進 release.yml

```bash
keytool -list -v -keystore thumblinks-release.p12 -alias thumblinks \
  | sed -n 's/.*SHA256: //p' | tr -d ':' | tr 'A-F' 'a-f'
```

印出來的 64 碼填進 `.github/workflows/release.yml` 的 `EXPECTED_CERT`。
workflow 簽完會核對這個值，Secret 被換成別把金鑰的話就不發版。

## 4. 發第一版

在 repo 根目錄新增 `release-version`，內容是版本（例如 `2026.11.01`），推上去就會 build、簽名、發 Release（tag `v2026.11.01`）。
之後要發新版就改這個檔案（同一天第二版寫 `2026.11.01.2`）。

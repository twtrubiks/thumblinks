#!/usr/bin/env bash
# 在模擬器上先跑 DeviceTest（Android 9 的 SQLite、Bitmap 縮圖轉正，不錄影），再一邊錄影一邊跑 DemoRecordingTest，
# 錄完清掉 App 資料跑 SystemUiTest（系統的照片挑選器、存檔的檔案畫面，不錄影），再覆蓋安裝 release 版做冒煙測試
# （真的抓一次 https 網頁的縮圖、用內建瀏覽器打開它），最後換回 debug 版跑 BrowserTest（uBO 從 AMO 安裝、擋廣告、自己更新；新視窗、錯誤頁）。
# APK 按 CPU 拆開（GeckoView），模擬器裝 x86_64 那一份。
# 輸出到 $1（預設 out/）：device-test.txt、demo.raw.mp4、instrument.txt、logcat.txt、system-ui-test.txt、
# logcat-system-ui.txt、browser-test.txt、ublock-update-test.txt、logcat-browser.txt、screenshots/，以及 release 版冒煙測試的 release-smoke.txt、logcat-release.txt、screenshots/release-*.png
# 由 .github/workflows/record.yml 在 android-emulator-runner 裡呼叫，工作目錄是 repo 根目錄。
set -uo pipefail

OUT=${1:-out}
APP_ID=com.twtrubiks.thumblinks
TEST_CLASS=$APP_ID.DemoRecordingTest
DEVICE_TEST_CLASS=$APP_ID.DeviceTest
SYSTEM_UI_TEST_CLASS=$APP_ID.SystemUiTest
BROWSER_TEST_CLASS=$APP_ID.BrowserTest
mkdir -p "$OUT"

# adb 指令都限時：GeckoView 用過之後 adb 曾經整個卡住（screencap、logcat -d 卡了 20～30 分鐘，整個 job 逾時）。
# 預設 120 秒，要更久的在前面加 ADB_TIMEOUT=…。已經自己包 timeout 的（am instrument）呼叫的是執行檔，不經過這裡
ADB_BIN=$(command -v adb)
adb() { timeout "${ADB_TIMEOUT:-120}" "$ADB_BIN" "$@"; }

# 每一段開始時記一行（含時間）：卡住時看 progress.txt 就知道卡在哪
step() { echo "$(date +%T) $*" | tee -a "$OUT/progress.txt"; }

# GeckoView 的 APK 按 CPU 拆開，模擬器是 x86_64
step "安裝 debug 版與測試 APK"
ADB_TIMEOUT=300 adb install -r -t app/build/outputs/apk/debug/app-x86_64-debug.apk || exit 1
ADB_TIMEOUT=300 adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || exit 1

# 喚醒並解鎖螢幕、在影片裡顯示點擊位置
# （不用 keyevent 82：MENU 鍵在桌面會叫出長按選單，被錄進影片開頭）
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
adb shell settings put system show_touches 1 || true

# 剛開機時系統服務還很忙：System UI 會 ANR、測試要幾十秒才啟動。
# 等 1 分鐘 load average 降到 2.5 以下再開始，最多等 3 分鐘。
for _ in $(seq 1 36); do
  load=$(adb shell cat /proc/loadavg | cut -d' ' -f1)
  echo "loadavg: $load"
  awk -v l="$load" 'BEGIN { exit !(l < 2.5) }' && break
  sleep 5
done

# 開機時卡死的系統程式（Quickstep、System UI 等）會一直跳 ANR 對話框，按 Wait 5 秒後又會再跳，
# 對話框在的時候 UiAutomator 也讀不到 App 畫面。錄影前直接重新啟動它們（系統會自動重開）。
anr_pkgs=$(adb shell dumpsys window windows | grep -o 'Application Not Responding: [A-Za-z0-9_.]*' | sed 's/.*: //' | sort -u)
for pkg in $anr_pkgs; do
  echo "重新啟動 ANR 的程式：$pkg"
  adb shell am force-stop "$pkg" || true
done
if [ -n "$anr_pkgs" ]; then
  sleep 5
fi

# 只有真機才測得到的事：Android 9 內建的 SQLite 跑不跑得動 LinkStore 的 SQL、Bitmap 縮圖轉正。不錄影
# 測試都設時間上限：卡住的話早點停下來，後面的步驟還能把 log 與截圖收回來，不用等到整個 job 逾時
step "DeviceTest"
timeout 300 adb shell am instrument -w -e class "$DEVICE_TEST_CLASS" \
  "$APP_ID.test/androidx.test.runner.AndroidJUnitRunner" | tee "$OUT/device-test.txt"

adb logcat -c || true

# pixel_7_pro 是 1440x3120，直接錄可能超過模擬器編碼器上限，所以錄一半尺寸
# screenrecord 單次最長 180 秒
step "DemoRecordingTest（錄影）"
ADB_TIMEOUT=200 adb shell screenrecord --size 720x1560 --bit-rate 8000000 --time-limit 180 /sdcard/demo.mp4 &
REC_PID=$!
sleep 2

timeout 600 adb shell am instrument -w -e class "$TEST_CLASS" \
  "$APP_ID.test/androidx.test.runner.AndroidJUnitRunner" | tee "$OUT/instrument.txt"

sleep 1
adb shell am force-stop "$APP_ID" || true
adb shell 'pkill -INT screenrecord 2>/dev/null || pkill -l 2 screenrecord' || true
wait "$REC_PID" || true
# 等 screenrecord 把檔案寫完
sleep 2

step "收回錄影"
adb pull /sdcard/demo.mp4 "$OUT/demo.raw.mp4" || true
# DemoRecordingTest 在關鍵畫面存的截圖
adb pull "/sdcard/Android/data/$APP_ID/files/screenshots" "$OUT/" || true
adb logcat -d > "$OUT/logcat.txt" || true

# 系統的畫面（照片挑選器、存檔的檔案畫面）：不錄影。
# 先清掉 App 資料，從空的清單開始（錄影的截圖已經 pull 回來了）
step "SystemUiTest"
adb shell pm clear "$APP_ID" || true
adb logcat -c || true
timeout 300 adb shell am instrument -w -e class "$SYSTEM_UI_TEST_CLASS" \
  "$APP_ID.test/androidx.test.runner.AndroidJUnitRunner" | tee "$OUT/system-ui-test.txt"
adb shell am force-stop "$APP_ID" || true
adb pull "/sdcard/Android/data/$APP_ID/files/screenshots" "$OUT/" || true
adb logcat -d > "$OUT/logcat-system-ui.txt" || true

# 給 release 版冒煙測試用：沒有 UiAutomator 測試 APK 可用，靠 uiautomator dump 找元件
# 剛跑完 UiAutomator 測試時，前一個 UiAutomation 連線可能還沒放掉，dump 會回 null root node：隔兩秒再試
ui_dump() {
  local out
  for _ in 1 2 3 4 5; do
    out=$(adb shell uiautomator dump /sdcard/ui.xml 2>&1)
    case "$out" in
      *dumped*) adb shell cat /sdcard/ui.xml; return 0 ;;
    esac
    echo "uiautomator dump：$out" >&2
    sleep 2
  done
  return 1
}

# 鍵盤開著才按返回鍵收起來；沒開的時候按返回鍵會離開那一頁
hide_keyboard() {
  if adb shell dumpsys input_method | grep -q 'mInputShown=true'; then
    adb shell input keyevent KEYCODE_BACK
    sleep 1
  fi
}

# 從一份 dump（$1）裡找第一個符合的元件（$2 是 dump 裡的屬性，例如 'resource-id="url_input"'），印出中心座標
center() {
  local xy
  xy=$(printf '%s' "$1" | tr '>' '\n' | grep -F "$2" | head -1 \
    | sed -nE 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/p' \
    | awk '{ print int(($1 + $3) / 2), int(($2 + $4) / 2) }')
  if [ -z "$xy" ]; then
    echo "畫面上找不到 $2" >&2
    return 1
  fi
  echo "$xy"
}

status=0
# 測試失敗時 am instrument 的 exit code 仍是 0，要看輸出判斷
if ! grep -q '^OK (' "$OUT/device-test.txt"; then
  echo "DeviceTest 沒有通過，詳見 $OUT/device-test.txt"
  status=1
fi
if ! grep -q '^OK (' "$OUT/instrument.txt"; then
  echo "DemoRecordingTest 沒有通過，詳見 $OUT/instrument.txt 與 $OUT/logcat.txt"
  status=1
fi
if ! grep -q '^OK (' "$OUT/system-ui-test.txt"; then
  echo "SystemUiTest 沒有通過，詳見 $OUT/system-ui-test.txt、$OUT/logcat-system-ui.txt 與 screenshots/system-*.png"
  status=1
fi

# release 版冒煙測試：R8 壓縮過的程式在 debug 版測不到，覆蓋安裝後打開 App，確認沒有閃退，
# 並且真的抓一次網頁：壓過的 OkHttp、jsoup、Coil 只有真的抓一次才跑得到。
# release 版不認 127.0.0.1 的明碼連線，不能用假網站，抓一個真的 https 網址。
# 兩個版本都用 debug 金鑰簽，可以直接覆蓋。先清掉 App 資料，從第一次打開的樣子開始。
# 不用 github.com 首頁：首頁有自動播放的影片，模擬器（軟體解碼、軟體繪圖）在影片播放時離開 App，
# SurfaceFlinger 會卡死、system_server 被 watchdog 殺掉，後面的 BrowserTest 都跑不了。repo 頁面沒有影片，一樣有 og:image
SMOKE_URL=https://github.com/git/git
RELEASE_APK=app/build/outputs/apk/release/app-x86_64-release.apk
if [ -f "$RELEASE_APK" ]; then
  mkdir -p "$OUT/screenshots"
  step "release 冒煙測試"
  adb shell pm clear "$APP_ID" || true
  ADB_TIMEOUT=300 adb install -r "$RELEASE_APK" || exit 1
  # 系統的轉場與鍵盤動畫停掉，dump 少等一點。錄影已經結束，不影響影片
  for scale in animator_duration_scale window_animation_scale transition_animation_scale; do
    adb shell settings put global "$scale" 0 || true
  done
  adb logcat -c || true
  adb shell am start -W -n "$APP_ID/.MainActivity" | tee "$OUT/release-smoke.txt"
  sleep 3
  adb exec-out screencap -p > "$OUT/screenshots/release-1-launch.png"

  # 新增 → 輸入網址 → 抓縮圖 → 等到縮圖真的載出來（thumb_ready）→ 加入 → 清單上看得到。
  # 輸入框一拿到焦點，游標就開始閃、鍵盤也打開，dump 會等不到畫面靜止（could not get idle state）。
  # 所以新增頁先在還沒有人拿到焦點時 dump 一次、記下座標；按「抓縮圖」時 App 會把焦點拿掉
  fetched=0
  added=0
  if home=$(ui_dump) \
    && add=$(center "$home" 'resource-id="add_button"') \
    && adb shell input tap $add && sleep 2 \
    && add_screen=$(ui_dump) \
    && url_input=$(center "$add_screen" 'resource-id="url_input"') \
    && fetch=$(center "$add_screen" 'text="抓縮圖"') \
    && adb shell input tap $url_input && adb shell input text "$SMOKE_URL" && hide_keyboard \
    && adb shell input tap $fetch; then
    for _ in $(seq 1 20); do
      sleep 2
      if preview=$(ui_dump) && printf '%s' "$preview" | grep -q 'resource-id="thumb_ready"'; then
        fetched=1
        break
      fi
    done
  fi
  adb exec-out screencap -p > "$OUT/screenshots/release-2-fetched.png"
  if [ "$fetched" -eq 1 ] && save=$(center "$preview" 'text="加入"') && adb shell input tap $save; then
    for _ in $(seq 1 10); do
      sleep 1
      if list=$(ui_dump) && printf '%s' "$list" | grep -q 'text="github.com"'; then
        added=1
        break
      fi
    done
  fi
  adb exec-out screencap -p > "$OUT/screenshots/release-3-added.png"

  # 點那一筆：用內建瀏覽器打開 $SMOKE_URL。壓過的 GeckoView 要真的載入網頁、從 AMO 裝好 uBO。
  # 網頁一直在動，uiautomator dump 等不到畫面靜止，改看 App 寫進 logcat 的兩行（BrowserTab、Ublock）。
  # 開著 GeckoView 時 adb 曾經整個卡住（screencap 卡了 30 分鐘，整個 job 逾時），每個 adb 指令都限時，進度寫進 release-smoke.txt
  browsed=0
  if [ "$added" -eq 1 ] && row=$(center "$list" 'text="github.com"') && timeout 30 adb shell input tap $row; then
    echo "$(date +%T) 用內建瀏覽器打開 $SMOKE_URL" | tee -a "$OUT/release-smoke.txt"
    end=$((SECONDS + 150))
    while [ $SECONDS -lt $end ]; do
      sleep 2
      log=$(timeout 20 adb logcat -d -s ThumbLinks:I)
      if printf '%s' "$log" | grep -qF "網頁載入完成 success=true $SMOKE_URL" \
        && printf '%s' "$log" | grep -q 'uBlock Origin .* 可以用了'; then
        browsed=1
        break
      fi
    done
    echo "$(date +%T) 瀏覽器等完了 browsed=$browsed" | tee -a "$OUT/release-smoke.txt"
  fi
  # 這一段不截圖：開著 GeckoView 時 adb 的 screencap 曾經卡住（exec-out 卡了 30 分鐘；存在模擬器上的也逾時過），
  # 瀏覽器的樣子看 BrowserTest 的截圖（UiAutomation 截圖沒事）。先回桌面讓 GeckoView 停下來，再讓下一段關掉 App
  timeout 30 adb shell input keyevent KEYCODE_HOME || true
  sleep 3

  timeout 60 adb logcat -d > "$OUT/logcat-release.txt" || echo "$(date +%T) logcat 逾時" | tee -a "$OUT/release-smoke.txt"
  if grep -q 'FATAL EXCEPTION' "$OUT/logcat-release.txt"; then
    echo "release 版閃退，詳見 $OUT/logcat-release.txt"
    status=1
  fi
  if [ "$fetched" -ne 1 ]; then
    echo "release 版抓 $SMOKE_URL 沒等到縮圖載出來，詳見 $OUT/screenshots/release-2-fetched.png 與 $OUT/logcat-release.txt"
    status=1
  elif [ "$added" -ne 1 ]; then
    echo "release 版按「加入」之後清單上沒看到 github.com，詳見 $OUT/screenshots/release-3-added.png"
    status=1
  elif [ "$browsed" -ne 1 ]; then
    echo "release 版的內建瀏覽器沒等到 $SMOKE_URL 載完或 uBO 裝好，詳見 $OUT/release-smoke.txt 與 $OUT/logcat-release.txt"
    status=1
  fi
fi

# 內建瀏覽器：uBO 從 AMO 安裝（要連外）、擋廣告、popup；新視窗、錯誤頁；uBO 自己更新。不錄影，放在最後：
# 模擬器（軟體繪圖）曾經在測完瀏覽器、關掉 App 時整個當掉（device offline），之後的步驟都做不了。
# release 版裝在上面了，換回 debug 版（同一把金鑰）；先清掉 App 資料，每次都從第一次安裝 uBO 開始
step "BrowserTest"
adb shell pm clear "$APP_ID" || true
ADB_TIMEOUT=300 adb install -r -t app/build/outputs/apk/debug/app-x86_64-debug.apk || true
adb logcat -c || true
# logcat 邊跑邊存：模擬器在測試結束後當掉的話，事後就拿不到了
"$ADB_BIN" logcat > "$OUT/logcat-browser.txt" 2>/dev/null &
LOGCAT_PID=$!
timeout 600 adb shell am instrument -w -e class "$BROWSER_TEST_CLASS" \
  "$APP_ID.test/androidx.test.runner.AndroidJUnitRunner" | tee "$OUT/browser-test.txt"
# uBO 自己更新要跨兩次 App 啟動：分兩次 am instrument（每次都是新的行程，等於重新打開 App），
# 第一次換成前一版、檢查更新、新版下載好；第二次確認重新打開後換上了新版
step "UblockUpdateTest"
for method in downloadsTheNewVersion appliesItAfterRestart; do
  timeout 300 adb shell am instrument -w -e class "$APP_ID.UblockUpdateTest#$method" \
    "$APP_ID.test/androidx.test.runner.AndroidJUnitRunner" | tee -a "$OUT/ublock-update-test.txt"
done
step "瀏覽器的測試結束，收回截圖"
adb pull "/sdcard/Android/data/$APP_ID/files/screenshots" "$OUT/" || true
kill "$LOGCAT_PID" 2>/dev/null || true
if ! grep -q '^OK (' "$OUT/browser-test.txt"; then
  echo "BrowserTest 沒有通過，詳見 $OUT/browser-test.txt、$OUT/logcat-browser.txt 與 screenshots/browser-*.png"
  status=1
fi
if [ "$(grep -c '^OK (1 test)' "$OUT/ublock-update-test.txt" 2>/dev/null)" != 2 ]; then
  echo "UblockUpdateTest 沒有通過（uBO 自己更新），詳見 $OUT/ublock-update-test.txt 與 $OUT/logcat-browser.txt"
  status=1
fi

exit $status

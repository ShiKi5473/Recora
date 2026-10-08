# MUFU 記錄器（Android App）

連上 MUFU V60P 行車記錄器的 Wi-Fi，瀏覽、播放、下載 SD 卡上的影片和照片，並在手機上剪輯。
自用、側載安裝，目標 Android 16（minSdk 30）。
API 規格見 [MUFU_V60P_API.md](MUFU_V60P_API.md)（已在實機上驗證），電腦版下載工具見 [dashcam_download.py](dashcam_download.py)。

## 功能

- **連線**：按「連線到記錄器」後，在系統視窗選擇 `MUFU_` 開頭的 Wi-Fi。App 只把自己綁到記錄器的網路，手機的行動網路照常可用。
  如果已經在系統設定手動連上，App 會自動偵測（只在記錄器位址位於該網路的網段時才探測）。
  - 沒綁定記錄器網路時，App 不會送出任何請求：`192.72.1.1` 是公網位址，請求不能退回行動網路。
  - 每次連上都會把手機時間寫入記錄器，按「中斷連線」時會通知記錄器（同官方 App）。
  - 手機開著 VPN（例如 AdGuard）時，要在 VPN App 裡把本 App 排除，否則連不上。
- **檔案**：一般、緊急、停車、照片四個分頁，依日期分組，前後鏡頭自動配對，已下載的檔案會打勾。
  手機連著時記錄器不會錄影，所以每個資料夾在一次連線中只讀一次，切換分頁不必重讀；右上角的重新整理會強制重讀。
- **下載**：長按進入多選，可下載「前+後」或「只下載前鏡頭」。
  - 影片存到 `Movies/MufuDashcam`，照片存到 `Pictures/MufuDashcam`，相簿看得到。
  - 一次下載一個檔案，連線中斷時從中斷處續傳。
  - 與記錄器的連線斷掉時，沒下載完的檔案會留在佇列等待，重新連上後自動接著下載。
  - App 被關掉後，佇列會清空；重新下載同一個檔案時，會從上次中斷處接著下載。
  - 從記錄器串流播放時會先暫停下載，離開播放畫面後繼續（記錄器一次只服務一個連線）。
- **播放**：直接從記錄器串流播放，已下載的則播手機上的檔案。左右滑動切換同一清單的上一段、下一段。
  播放畫面可以截圖；也可以刪除正在播的檔案：串流中的從記錄器刪，手機上的從相簿刪，刪完接著播下一段。
- **即時畫面**：RTSP，前鏡頭 `av4`（含聲音）、後鏡頭 `av5`（先送 `setcamid` 切換），可全螢幕。下載進行中時停用。
- **相簿**：只列 App 的資料夾，分成未剪輯、已剪輯、照片三類，可依前後鏡頭篩選。
- **剪輯**：選取範圍、可去除聲音，匯出成 H.264 MP4（檔名帶來源與片段時間，相簿依此顯示）。
- **裝置**：
  - 錄影狀態、記憶卡容量與各類錄影佔用、格式化記憶卡。
  - 同步時間。
  - 修改記錄器設定：改完會讀回確認；官方 App 沒有提供的項目放在「進階」。
  - 診斷紀錄（可複製）。
  - App 連線用的 SSID 開頭與密碼：只改 App 自己存的值，不會改到記錄器本身的密碼。

## 建置與安裝

PATH 上的 JDK 25 無法執行 Gradle，所以要改用 Android Studio 內附的 JDK 21：

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio1/jbr"
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

也可以直接用 Android Studio 開啟專案。APK 也能複製到手機上點開安裝（需允許「安裝不明應用程式」）。

release 版也是用 debug 金鑰（`%USERPROFILE%\.android\debug.keystore`）簽署。換電腦或金鑰重建後，
新版會無法覆蓋安裝，所以請備份這個檔案。

## 沒有記錄器時的開發測試

`tools/mock_dashcam.py` 是假記錄器，模擬 Config.cgi（含 `dir` / `reardir`）和支援 Range 的檔案下載：

```bash
python tools/mock_dashcam.py --throttle 800                 # 127.0.0.1:8080，限速 800 KB/s
python tools/mock_dashcam.py --drop-every 1000              # 每個回應送 1 MB 就斷線，測續傳
python tools/mock_dashcam.py --path-style root              # 只接受 /Video/... 路徑，測備用路徑
```

在模擬器上測試：

```bash
adb reverse tcp:8080 tcp:8080
adb shell am start -n tw.com.mufudashcam/.MainActivity --es host_override 127.0.0.1:8080
# 傳空字串 --es host_override "''" 就回到連真實記錄器
```

注意事項：

- 請用 `adb reverse` 加 `127.0.0.1`，不要用 `10.0.2.2`。這台電腦上的模擬器虛擬網路，在傳輸結尾偶爾會掉資料（用 `nc` 直接下載也會重現，不是 App 的問題）。
- adb 重新連線時 reverse 會失效，需要重新下指令。
- debug build 的「裝置」頁也可以手動輸入記錄器位址覆寫。

## 安全性

記錄器的 HTTP 介面沒有任何驗證：連上 Wi-Fi 的人都能刪除影片、格式化記憶卡。
請把記錄器的 Wi-Fi 密碼從出廠的 `12345678` 改掉（目前要用官方 App 改，見下方「待辦」）。

## 尚待實機確認 / 待辦

已確認的行為整理在 [MUFU_V60P_API.md](MUFU_V60P_API.md)（標 **【實測】** 的段落）。以下還沒確認：

- **格式化記憶卡**：從沒在實機上執行過。App 送 `set&property=SD0&value=format`，官方 App 對同系列機型用 `Camera.Menu.SD0`，哪個有效還不知道。
- **斷線後恢復錄影**：App 已不再送 `recordoff`。中斷連線後記錄器應會自己恢復錄影，可以觀察指示燈或最新檔案是否繼續變大來確認。
- **修改記錄器的 Wi-Fi 密碼**：官方 App「連線密碼」送的指令還沒抓到。
  抓法：手機接電腦、連上記錄器，在官方 App 改密碼，同時執行
  `adb logcat --pid=$(adb shell pidof com.vidure.mufuvideov2) | grep "full url"`。
  抓到之後就能在 App 裡加上修改功能。
- `TimeSettings` 是否真的改到記錄器時間。

# MUFU 記錄器（Android App）

連上 MUFU V60P 行車記錄器的 Wi-Fi，瀏覽並下載 SD 卡上的影片和照片。自用、側載安裝，目標 Android 16（minSdk 30）。
API 規格見 [MUFU_V60P_API.md](MUFU_V60P_API.md)，電腦版下載工具見 [dashcam_download.py](dashcam_download.py)。

## 功能

- **連線**：按「連線到記錄器」後，在系統視窗選擇 `MUFU_` 開頭的 Wi-Fi。App 只把自己綁到記錄器的網路，手機的行動網路照常可用。如果已經在系統設定手動連上，App 會自動偵測。
- **瀏覽**：一般、緊急、照片三個分頁，依日期分組，前後鏡頭自動配對，已下載的檔案會打勾。
- **下載**：長按進入多選，可下載「前+後」或「只下載前鏡頭」。
  - 影片存到 `Movies/MufuDashcam`，照片存到 `Pictures/MufuDashcam`，相簿看得到。
  - 一次下載一個檔案，連線中斷時從中斷處續傳。App 被關掉後重新下載，也會接著下載。
  - 下載前會停止錄影、結束後恢復（可在「裝置」頁關閉）。如果 App 沒能恢復錄影，下次連上時會自動補送。
- **播放**：直接從記錄器串流播放，已下載的則播手機上的檔案。
- **即時畫面**：RTSP `av5`（前鏡頭 + 音訊），可切換前後鏡頭。下載進行中時停用。
- **刪除**：從記錄器刪除，會先跳確認視窗，可選擇一併刪除後鏡頭檔。
  播放畫面上也有刪除鈕（除了截圖、剪輯），刪的是正在播的那一個檔案：串流中的從記錄器刪、手機上的從相簿刪，
  刪完接著播清單裡的下一段。
- **裝置**：同步手機時間到記錄器、查看記錄器設定、修改 Wi-Fi 密碼和 SSID 前綴。

## 建置與安裝

PATH 上的 JDK 25 無法執行 Gradle 8.14，所以要改用 Android Studio 內附的 JDK 21：

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio1/jbr"
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

也可以直接用 Android Studio 開啟專案。APK 也能複製到手機上點開安裝（需允許「安裝不明應用程式」）。

## 沒有記錄器時的開發測試

`tools/mock_dashcam.py` 是假記錄器，模擬 Config.cgi 和支援 Range 的檔案下載：

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

## 尚待實機確認

- **下載路徑**：App 會先試 `/mnt/mmc/Video/F/...`，回 404 再改用 `/Video/F/...`，兩種都支援。可以用 `curl.exe -r 0-1023` 確認實機是哪一種。
- **列表內容**：`dir` 是否也列出後鏡頭檔。沒有列出時，App 會用前鏡頭路徑推算後鏡頭路徑。
- **即時畫面**：RTSP 能否播放，以及 `Camera.Preview.Source.1.Camid` 是否真的能切換前後鏡頭。
- **回應判斷**：`set` 和 `del` 的成功判斷目前是看回應內容有沒有 `OK`。

# 錄刻 Recora（Android App）

<img src="docs/logo.png" alt="Recora logo" width="160">

連上行車記錄器（MUFU V60P、Astro LEO3 等）的 Wi-Fi，瀏覽、播放、下載 SD 卡上的影片和照片，並在手機上剪輯。
自用、側載安裝，目標 Android 16（minSdk 30）。
API 規格見 [MUFU_V60P_API.md](MUFU_V60P_API.md) 與 [ASTRO_LEO3_API.md](ASTRO_LEO3_API.md)，電腦版下載工具見 [dashcam_download.py](dashcam_download.py)。

## 功能

- **連線**：按「連線到記錄器」後，在系統視窗選擇連線卡片選的記錄器的 Wi-Fi（MUFU 為 `MUFU_`、Astro 為 `Astrofamily_` 開頭）。App 只把自己綁到記錄器的網路，手機的行動網路照常可用。
  如果已經在系統設定手動連上，App 會自動偵測（只在記錄器位址位於該網路的網段時才探測）。
  - 沒綁定記錄器網路時，App 不會送出任何請求：`192.72.1.1` 是公網位址，請求不能退回行動網路。
  - 每次連上都會把手機時間寫入記錄器，按「中斷連線」時會通知記錄器（同官方 App）。
  - 手機開著 VPN（例如 AdGuard）時，要在 VPN App 裡把本 App 排除，否則連不上。
- **檔案**：一般、緊急、停車、照片四個分頁，依日期分組，前後鏡頭自動配對，已下載的檔案會打勾。
  手機連著時記錄器不會錄影，所以每個資料夾在一次連線中只讀一次，切換分頁不必重讀；右上角的重新整理會強制重讀。
  - 可以只看還沒下載的片段、跳到某一天；多選時「選取範圍」會把頭尾之間全部選起來。
  - 右上選單：選取未下載的、選取已下載的（接著可以從記錄器刪除，釋放記憶卡空間）、備份所有未下載的緊急錄影。
- **下載**：長按進入多選，可下載「前+後」、「只下載前鏡頭」或「只下載後鏡頭」。
  - 影片存到 `Movies/Recora`，照片存到 `Pictures/Recora`（舊版的 `Dashcam`、`MufuDashcam` 資料夾照樣讀得到），相簿看得到；可在設定裡改成依類型或依日期分子資料夾。
  - 一次下載一個檔案，連線中斷時從中斷處續傳。
  - 與記錄器的連線斷掉時，沒下載完的檔案會留在佇列等待，重新連上後自動接著下載。
  - 按「中斷連線」或連線中斷時，正在下載的檔案會先停在目前位置（回到等待連線），再通知記錄器，重新連上後續傳。
  - App 被關掉後，佇列會保留，下次連上記錄器時從上次中斷處接著下載。
  - 可設定連上記錄器時自動備份還沒下載的緊急錄影。
  - 記錄器一次只服務一個連線，下載讓路給其他操作：串流播放、看即時畫面時暫停下載，離開畫面（或 App 進背景）後從中斷處繼續；
    讀列表、看照片、改設定等指令會讓下載短暫停一下，指令一個接一個送，結束約 1.5 秒後自動繼續。
  - 下載頁標題下顯示還剩幾個檔案、多少容量，以及依目前速度估計的剩餘時間。
- **播放**：直接從記錄器串流播放，已下載的則播手機上的檔案。左右滑動（或控制列的上一段／下一段）依錄影時間切換同一清單的其他片段
  （由右往左滑 = 較新的一段），切到已下載的片段時改播手機上的檔案。去剪輯再回來，仍停在剛才那一段、那個時間點。
  - 標題下顯示實際錄影時間；可以在同一個時間點切換前／後鏡頭；暫停時可以逐格前進、後退。
  - 可設定播完自動播錄影時間的下一段。
  - 截圖（可設定放大時只存看到的範圍）；串流中可以直接加入下載，手機上的檔案可以分享。
  - 也可以刪除正在播的檔案：串流中的從記錄器刪，手機上的從相簿刪，刪完接著播下一段。
  - 看照片時也能分享、下載或刪除。
  - Astro 的原始檔（手機上的）：標題下顯示影片裡的 GPS（時速、座標），「更多」選單可以在地圖 App 開啟目前位置、分享整段軌跡（GPX）；
    「App 設定」可關掉顯示。剪輯匯出的檔沒有 GPS。
- **即時畫面**：RTSP，前鏡頭 `av4`（含聲音）、後鏡頭 `av5`（先送 `setcamid` 切換），可全螢幕。看的時候暫停下載。App 退到背景時停止串流，回來自動重新連線；播放錯誤會自動重連 2 次。
- **相簿**：列 App 的資料夾，以及在「相簿資料夾」勾選的其他資料夾（預設含 Astro App 的 `Movies/astroFamily`；相簿右上選單或「App 設定」都能改），
  依檔名分成未剪輯、已剪輯、照片三類（也認得官方 App 存的 `…F_P1.mp4`／`…R_P2.mp4` 與 `video_…_E_O_N_….mp4`），
  可依記錄器種類、前後鏡頭篩選、跳到某一天，可切換列表／格狀。
  長按可多選，整批刪除或分享（標題顯示選取的總大小）。
  縮圖用系統的縮圖快取。「整理空間」可以清掉已剪輯過的原始檔、幾天前錄的原始檔、沒下載完的暫存檔。
- **剪輯**：選取範圍（可用 ±1 秒、±1 格微調）、可去除聲音，匯出成 MP4（檔名帶來源與片段時間，相簿依此顯示）。
  - 編碼（H.264／H.265）、解析度、畫質在設定裡選。
  - 事件跨到前後一段時，可以接上手機上已下載、錄影時間緊接的片段一起剪。
  - 另一個鏡頭的同一段也已下載時，可以用同一個時間範圍一起匯出。
  - 匯出中離開畫面會先確認；取消匯出時會刪掉轉檔的暫存檔。
- **裝置**：
  - 錄影狀態、記憶卡容量與各類錄影佔用、格式化記憶卡。
  - 同步時間。
  - 修改記錄器設定：改完會讀回確認；官方 App 沒有提供的項目放在「進階」。
  - 診斷紀錄（可複製）。
- **App 設定**（「裝置」頁的「App 設定」）：下載、瀏覽、播放、剪輯匯出、外觀（主題、動態色彩）。
  記錄器 Wi-Fi 的 SSID 開頭與密碼也在這裡（MUFU、Astro 各一組）：只改 App 自己存的值，不會改到記錄器本身的密碼。

## Astro LEO3

連線卡片可以切換 **MUFU／Astro**（記住上次的選擇）；在系統設定手動連上任一台，App 也會自動偵測是哪一種。
Astro 預設 SSID 開頭 `Astrofamily_`、密碼 `12345678`（改過密碼要到「App 設定」更新）。協定與待實測項目見 [ASTRO_LEO3_API.md](ASTRO_LEO3_API.md)。

- 功能與 MUFU 相同：瀏覽、下載（續傳）、串流播放、刪除、即時畫面、裝置狀態與對時、修改設定、格式化。
- **與 MUFU 不同**：手機連著時 Astro 會繼續錄影，只有用檔案（檔案頁、下載、串流播放、看照片、刪除）或改設定時才停錄；
  最後一個使用結束約 10 秒後自動切回錄影模式並恢復錄影，中斷連線時一定先恢復。
  App 退到背景（按 Home、切到別的 App、關螢幕）時，佇列裡沒有這台記錄器的下載就立刻恢復錄影，不等 10 秒；串流播放與看照片的停錄會跟著畫面歸還，回來再重新停錄。
- 對時送 UTC，記錄器再套自己的時區設定（8103）。
- 緊急錄影（ATTR 33，唯讀）記錄器拒絕刪除（官方 App 送 `4003` 也是回 Status -4），本 App 略過不送並提示；要清除只能格式化記憶卡。

### 車上測試與拉診斷紀錄

在車上連著記錄器時沒辦法同時接電腦，所以 App 會把診斷紀錄（每個請求與回應）寫成檔案，保留 7 天。回來接 USB：

```bash
adb pull /sdcard/Android/data/tw.com.recora/files/diag/
adb pull /sdcard/Android/data/com.ligo.aAstroCam/files/ZyLog/    # 官方 App 的紀錄（若同時用它對照）
```

## 建置與安裝

PATH 上的 JDK 25 無法執行 Gradle，所以要改用 Android Studio 內附的 JDK 21：

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio1/jbr"
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

也可以直接用 Android Studio 開啟專案。APK 也能複製到手機上點開安裝（需允許「安裝不明應用程式」）。

release 版用 R8 縮減程式與資源，原生庫只打包 `arm64-v8a`（約 20 MB）；debug 版不縮減，原生庫含 `arm64-v8a` 與模擬器用的 `x86_64`。
release 版有地方閃退（多半是 `ClassNotFoundException`／`NoSuchMethodError`）時，在 `app/proguard-rules.pro` 補 keep 規則。

debug 與 release 版統一使用專案專用的 `app/mufu-debug.keystore` 簽署（不與系統全域 `~/.android/debug.keystore` 共用，也不進 Git 版控），
確保本機開發直裝與 GitHub Release 的 APK 可在手機上直接覆蓋升級。

### GitHub 自動 Release

推送 `v*` 標籤（例如 `git tag v1.0.1 && git push origin v1.0.1`）或在 GitHub Actions 手動觸發 **Release APK** workflow，
會自動執行單元測試、編譯 `release` APK 並發佈到 GitHub Releases。需先在 GitHub Repository **Settings > Secrets and variables > Actions**
設定 `KEYSTORE_BASE64`（`app/mufu-debug.keystore` 的 Base64 編碼內容）。

## 從舊版（tw.com.mufudashcam「MUFU 記錄器」或 tw.com.dashcam「行車記錄器」）升級

改名後 applicationId 變成 `tw.com.recora`，系統會把它當成另一個 App：

1. 先記下舊 App 的 Wi-Fi 密碼等設定（設定、下載佇列不會帶過來），再**移除所有舊版**，否則兩個 App 都會自動偵測並綁定記錄器、互搶連線。
2. 安裝新 App，重新設定。
3. 舊版下載的檔案（`Movies`／`Pictures` 底下的 `MufuDashcam`、`Dashcam`）相簿與「已下載」判斷仍然讀得到；刪除舊 App 建立的檔案時系統會要求確認。

Astro：記錄器會在用檔案（檔案頁、下載、串流播放）時停錄，App 退到背景後立刻自動恢復。若離開 App 後 1–2 秒內就把它從最近工作清單滑掉，恢復指令可能來不及送完，記錄器會停在回放模式，下次連上時 App 會補上錄影；沒有再連的話請手動重啟記錄器。正在下載 Astro 的檔案時退到背景，會等下載結束約 10 秒後才恢復。

## 沒有記錄器時的開發測試

`tools/mock_dashcam.py` 是假記錄器，模擬 Config.cgi（含 `dir` / `reardir`）和支援 Range 的檔案下載：

```bash
python tools/mock_dashcam.py --throttle 800                 # 127.0.0.1:8080，限速 800 KB/s
python tools/mock_dashcam.py --drop-every 1000              # 每個回應送 1 MB 就斷線，測續傳
python tools/mock_dashcam.py --path-style root              # 只接受 /Video/... 路徑，測備用路徑
python tools/mock_dashcam.py --brand astro                  # 模擬 Astro LEO3：連線卡片先切到 Astro；會印出收到的指令順序（停錄／恢復時序）
```

在模擬器上測試：

```bash
adb reverse tcp:8080 tcp:8080
adb shell am start -n tw.com.recora/.MainActivity --es host_override 127.0.0.1:8080
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
- **剪輯匯出**（要用實機下載的 TS，假記錄器的檔案不是真的 TS）：
  - 起點不在 0 秒的匯出：Transformer 原本用自己的 TS 設定，檔案會變成不可 seek 而失敗；已改用與播放器相同的設定，待確認。
  - H.265、跨段剪輯、前後鏡頭一起匯出。

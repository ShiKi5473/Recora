# MUFU V60P 行車記錄器 — 非官方 API 參考

> 目的：給自製 App 使用。初版由官方韌體 `V60P.bin`（v1.04.05）逆向整理，
> 涵蓋 Wi-Fi 連線、HTTP 控制介面（CGI）、檔案列表與下載、RTSP 即時串流。
> 僅適用於連上「本機自己的」記錄器 AP。此為社群整理，非原廠文件，欄位可能因韌體版本而異。
>
> **2026-09-23/24 已在實機上逐項驗證，並對照官方 App（`com.vidure.mufuvideov2`）的 logcat。**
> 標 **【實測】** 的是實機確認過的行為；逆向結果與實機不符的地方已直接改正，
> 沒有標記的內容仍未驗證。與實機不一致時以實測為準。

---

## 0. 硬體 / 系統摘要

| 項目 | 值 |
|---|---|
| SoC | SigmaStar SSC33x（I6E），單核 ARM ~1 GHz |
| 前/後鏡頭 | Sony IMX335（雙鏡頭，各自獨立錄影） |
| 錄影編碼 | **【實測】** PID 256 H.265 2560×1440 主串流、PID 257 H.264 子串流、PID 258 AAC、PID 259–261 私有資料（疑為 GPS） |
| 容器格式 | MPEG-TS（副檔名 `.TS`），每段 1 或 3 分鐘；1440P30 約 11.8 Mbps（每分鐘約 88 MB） |
| PCR | **【實測】** 每秒只有一個 PCR（約每 1.5 MB 一個）。ExoPlayer 要把 TsExtractor 的 timestamp search bytes 調大才能 seek |
| Wi-Fi | RTL8189FS（2.4 GHz、單天線、802.11n、20 MHz），AP 模式 |
| 網頁伺服器 | GoAhead（支援 HTTP Range）。**【實測】** 一次只能好好服務一個連線，串流和下載同時進行會互相拖垮 |
| 即時串流 | live555（RTSP） |

---

## 1. 連線基本資訊

| 項目 | 預設值 |
|---|---|
| 記錄器 IP | `192.72.1.1` |
| Wi-Fi SSID | `MUFU_V60P_xxxx`（xxxx 為 MAC 後幾碼） |
| Wi-Fi 密碼 | `12345678`（WPA2-PSK / CCMP） |
| HTTP 埠 | `80` |
| RTSP 埠 | `554` |
| DHCP | 記錄器發放，用戶端會拿到 `192.72.1.x` |

> App 端注意：
> - 此 AP 沒有對外網路，Android/iOS 會判定「無網際網路」而自動切回行動網路。
>   連線後要呼叫系統 API 綁定此網路（Android `bindProcessToNetwork` / `requestNetwork`
>   指定該 SSID），否則所有請求都會走行動網路。
> - **`192.72.1.1` 不是私有位址。** 沒綁定時送出的請求會到網際網路上一台不相干的主機，
>   甚至可能收到回應。斷線後一定要擋下所有請求，不能讓它們退回預設網路。
>   在其他 Wi-Fi 上自動探測前，也要先確認 `192.72.1.1` 位於該網路的直連網段。
> - **【實測】** 手機若開著 VPN App（例如 AdGuard），綁定網路會失敗並回 `EPERM`。
>   必須在 VPN App 裡把本 App 排除。

---

## 2. HTTP 控制介面（CGI）

所有控制都走同一支：

```
GET http://192.72.1.1/cgi-bin/Config.cgi?action=<ACTION>&property=<PROP>&value=<VAL>
```

- `action`：`get` | `set` | `dir` | `reardir` | `del` | `setcamid`
- 多組參數可串接：`...&property=A&value=1&property=B&value=2`（未驗證）
- 沒有登入驗證（GoAhead `auth.txt` 未設任何使用者）。
- **【實測】CGI 不會做 URL 解碼。** 值裡的 `$` 一定要原樣送出，送 `%24` 會回 `no key found!`。
  `*` 也要原樣送出。
- **【實測】每個回應都含 `0 OK`**，那是 `CGI_PROCESS.sh` 自己的輸出。不認得的 `set` 也照樣回 `0 OK`，
  所以不能從回應判斷指令是否被支援、有沒有生效，**改完一定要讀回確認**。
  判斷失敗要看內文有沒有 `no key found`、`no this key`、`no this parameter`、`fail`。

### 2.1 `action=get`（讀設定 / 狀態）

**【實測】** 回應每行一組 `鍵=值`，**沒有**逆向時以為的 `;;` 結尾，中間還會夾雜 `0`、`OK`、
`Usage ./CGI_PROCESS.sh ...` 之類的雜訊行，只取符合 `鍵=值` 格式的行。支援萬用字元 `*`。

```
# 讀全部選單設定（唯一可靠的讀法）
GET ...?action=get&property=Camera.Menu.*
# 回應範例
0
OK
Camera.Menu.VideoRes=1440P30FPS
Camera.Menu.SoundRecord=ON
...

# 錄影狀態【實測】：手機連著時是 Standby
GET ...?action=get&property=Camera.Preview.MJPEG.status.*
→ Camera.Preview.MJPEG.status.record=Standby

# 記憶卡容量【實測】：LifeTimeTotal = 總容量、RemainLifeTime = 剩餘（例如 117.0G / 2.6G）
GET ...?action=get&property=Camera.Menu.CardInfo.*

# 記憶卡狀態（READY / FORMAT_ING / NO_CARD…）
GET ...?action=get&property=Camera.Menu.SD0
```

- **【實測】** `devinfo.*`（fwver、macaddr…）此韌體不支援，回
  `0 OK Usage ./CGI_PROCESS.sh {no this parameter} 0 OK no this key`。
  所以確認對方是記錄器時，只能看 Config.cgi 有沒有 HTTP 回應。
- **【實測】** 單項讀取 `get&property=Camera.Menu.X` 不可靠：可能回空，也可能回 `nvconf get 0 ...` 之類的雜訊。
  一律讀 `Camera.Menu.*` 再自己取值。
- **【實測】** `get Camera.Preview.Source.1.Camid` 永遠回 front，不能拿來確認目前的預覽鏡頭。

### 2.2 `action=set`（改設定 / 下指令）

底層是把值寫進主程式的控制管線 `/tmp/cardv_fifo`，再由 `CGI_PROCESS.sh` 轉譯。

**錄影控制**

```
GET ...?action=set&property=Video&value=recordoff
GET ...?action=set&property=Video&value=recordon
GET ...?action=set&property=Video&value=record     # 切換（toggle）
GET ...?action=set&property=Video&value=capture    # 拍照
```

**【實測】** 手機連上 Wi-Fi 期間，記錄器本來就停在 Standby。送 `recordon` 會回 `0 OK`，但不會開始錄影
（最新的檔案不會再變大），官方 App 首頁在連線時也顯示「錄影已停止」。
所以**下載前不需要、也不應該送 `recordoff`**：萬一 `recordon` 送出前就斷線，記錄器可能停在不錄的狀態。
官方 App 只有在改設定時才送 recordoff / recordon（見下方設定流程）。

**連線 / 離線**【實測，取自官方 App】

```
# 每次連上都對時：YYYY$MM$DD$hh$mm$ss（$ 原樣送出，結尾沒有 $）
GET ...?action=set&property=TimeSettings&value=2026$09$23$14$30$00
# 離開時通知記錄器
GET ...?action=set&property=UserDisconnect&value=ON
```

**串流控制**

```
GET ...?action=set&property=StreamStatus&value=ON   # 官方 App 開始預覽前會送
GET ...?action=set&property=streamer&value=start
GET ...?action=set&property=streamer&value=stop
GET ...?action=set&property=forceiframe              # 立刻送一張 I-frame（加速起播）
GET ...?action=set&property=setbitrate&value=2000000 # 預覽碼率 (bps)
GET ...?action=set&property=setframerate&value=30
GET ...?action=set&property=setvideogop&value=30
# 切換預覽鏡頭【實測，取自官方 App】：用 setcamid（不是 set），切完要重新建立 RTSP 連線
GET ...?action=setcamid&property=Camera.Preview.Source.1.Camid&value=rear   # front / rear
```

**系統**

```
GET ...?action=set&property=reboot
GET ...?action=set&property=reset_to_default        # 回原廠設定
GET ...?action=set&property=Camera.System.Power&value=Off
```

**修改設定**【實測】

- 寫入時用**不含** `Camera.Menu.` 前綴的名稱：`set&property=SoundRecord&value=OFF`。
  加了前綴的寫法回 `0 OK`，但不會生效。讀取仍然用 `Camera.Menu.*`。
- 官方 App 的流程：`Video=recordoff` → `Setting=enter` → 修改 → `Setting=exit` → `Video=recordon`。
- **絕對不要把讀不到的值寫回去。** 曾因此把 GSensor / Led 寫成空值，事後才補回來。
- 改 `VideoRes` 時記錄器會重新啟動錄影，約 1–2 分鐘沒有回應。

| 名稱（寫入用） | 值 | 說明 | 官方 App 有提供 |
|---|---|---|---|
| `ParkingGuard` | `ON` / `OFF` | 動作感測開關機 | ✓ |
| `SoundRecord` | `ON` / `OFF` | 麥克風 | ✓ |
| `VideoClipTime` | `1MIN` / `3MIN` | 錄影間隔（每段長度） | ✓ |
| `EmerRec` | `ON` / `OFF` | 緊急錄影 | ✓ |
| `VideoRes` | `1440P30FPS`（2560×1440 30fps WDR）/ `1080P60FPS`（1920×1080 60fps WDR）/ `1080P30FPS`（1920×1080 30fps HDR） | 影像解析度 | ✓ |
| `TimeStamp` | `ON` / `OFF` | 浮水印 | ✓ |
| `Led` | `ON`（綠燈開啟）/ `OFF` | 主機指示燈 | ✓ |
| `FrontEV` / `RearEV` | `0`–`6`（= -3 … +3） | 前 / 後鏡頭 EV | ✓ |
| `ParkingMonitor` | `ENABLE` / `DISABLE` | 停車監控 | |
| `GSensor` | `LEVEL0`–`LEVEL4`（0 = 關） | 碰撞感應靈敏度 | |
| `VoiceSwitch` | `ON` / `OFF` | 語音提示 | |
| `AutoCloseWifi` | `ON` / `OFF` | Wi-Fi 閒置自動關 | |

**【實測】寫入會被忽略的項目**：`GpsStamp`、`SpeedStamp`、`TiltCorrection`、`UpsideDown`、`HDR`、`NightMode`、
`PowerSaving`、`SpeechRecognition`、`Beep`。

官方 App 另有「傾倒偵測校正」（`TiltCorrection`，是一個動作）、「連線密碼」、「回復原廠」。
**「連線密碼」的指令還沒有抓到**，抓法見 §6。

**格式化記憶卡**（未在實機上執行過）

```
# 自製 App 目前送的（與其他設定一致，不含前綴）
GET ...?action=set&property=SD0&value=format
# 官方 App 對 OULI/MStar 機型用的寫法
GET ...?action=set&property=Camera.Menu.SD0&value=format
# 送出後輪詢 Camera.Menu.SD0，直到回到 READY
```

> 兩種寫法哪一種在 V60P 上有效，還沒驗證。

---

## 3. 檔案列表與下載

### 3.1 列表 `action=dir` / `action=reardir`

```
GET http://192.72.1.1/cgi-bin/Config.cgi?action=dir&property=<FOLDER>&format=all&count=16&from=<OFFSET>
GET http://192.72.1.1/cgi-bin/Config.cgi?action=reardir&property=<FOLDER>&format=all&count=16&from=<OFFSET>
```

- `property`（資料夾）：`Normal`（一般錄影）、`Event`（緊急錄影）、`Parking`（停車）、`Photo`（拍照）
- **【實測】`dir` 只列前鏡頭，後鏡頭要用 `reardir` 列**（大小也是實際值），官方 App 也是這樣做。
- `count`：每頁筆數，官方 App 用 `16`，建議照做（一次要太多，記錄器容易卡住）
- `from`：起始位移，做分頁，每次加上本頁回傳的筆數。**【實測】** 分頁正常（128 段緊急錄影分成 8 頁）。
  讀到空頁、或整頁都是已經看過的檔案，就停止。
- `format`：`all`

**回應 XML【實測】**

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<Video>
  <amount>0</amount>
  <file>
     <name>/mnt/mmc/Video/F/FILE260923-131459F.TS</name>
     <format>TS</format>
     <size>88493104</size>
     <attr>…</attr>
     <time>2026-09-23 13:14:59</time>
  </file>
  ...
</Video>
```

- 根元素名稱不固定：`Normal` 回的是 `<Video>`，不要依賴它。
- `<name>`：SD 卡上的**完整路徑**（解析時保險起見仍把 `$` 換成 `/`，只有檔名時再自己補目錄）。
- **`<amount>` 固定是 `0`**，不是總數，不能拿來判斷要不要翻頁。

### 3.2 SD 卡實際路徑（下載 URL 的來源）

檔案掛在 `/mnt/mmc`：

| 資料夾 | 前鏡頭 | 後鏡頭 |
|---|---|---|
| 一般 | `/mnt/mmc/Video/F/` | `/mnt/mmc/Video/R/` |
| 緊急 | `/mnt/mmc/Emergency/F/` | `/mnt/mmc/Emergency/R/` |
| 停車 | `/mnt/mmc/Parking/F/` | `/mnt/mmc/Parking/R/` |
| 拍照 | `/mnt/mmc/Picture/F/` | `/mnt/mmc/Picture/R/` |

**下載 URL【實測】**：直接用列表回傳的完整路徑，`Range` 請求回 HTTP 206。
```
http://192.72.1.1/mnt/mmc/Video/F/FILE260923-131459F.TS
```
（逆向時以為的 `/Video/F/...`（去掉 `/mnt/mmc`）沒有驗證過，自製 App 只在前者回 404 時才拿來試。）

**檔名規則：** `FILE<yymmdd>-<hhmmss><F|R>.TS`，緊急錄影前綴 `EMER`。
前鏡頭 `...F.TS` ↔ 後鏡頭 `...R.TS` 一一對應（同時間戳），只差 `/F/`↔`/R/` 與 `F`↔`R`。

### 3.3 分段 / 續傳下載

GoAhead 支援 HTTP Range：

```
GET /mnt/mmc/Video/F/FILE....TS
Range: bytes=1048576-
→ 206 Partial Content
   Content-Range: bytes 1048576-88493103/88493104
```

自製 App 的穩定策略：
1. 一次只下載一個檔案，下載時不要同時從記錄器串流播放或看即時畫面（記錄器一次只服務一個連線）。
2. 連線中斷就用 `Range: bytes=<已下載>-` 接續，不必整檔重來。
3. 寫進暫存檔（Android 可用 MediaStore 的 `IS_PENDING`），完成後再公開；用 `<size>` 核對長度。
4. 續傳時如果收到 200（而不是 206），代表對方忽略了 Range，或回應根本不是記錄器發的。
   大小對不上就不要覆寫已下載的部分。
5. 不要送 `recordoff` / `recordon`（見 §2.2）。

### 3.4 刪除 `action=del`

```
GET ...?action=del&property=$mnt$mmc$Video$F$FILE260923-131459F.TS
#            路徑用 $ 取代 /，$ 原樣送出
```

**【實測】** 送 `%24mnt%24mmc…` 會回 `no key found!`，送原始的 `$` 才刪得掉。

> 刪除不可復原，建議自製 App 加二次確認。

---

## 4. RTSP 即時串流

```
rtsp://192.72.1.1:554/liveRTSP/<PATH>
```

**【實測】V60P 只有兩條路徑**（從官方 App 的 logcat 確認）：

| 路徑 | 內容 |
|---|---|
| `av4` | 前鏡頭（視訊 + 音訊） |
| `av5` | 後鏡頭（只有視訊） |

切到後鏡頭的流程（同官方 App）：`setcamid … value=rear`（§2.2）→ 播 `av5`；切回前鏡頭：`value=front` → 播 `av4`。
`av1`、`av2`、`av3`、`av6`、`av7` 與 `v1`–`v3` 都回 404。逆向推出的 av 位元表不適用於此機型，
`Camera.Preview.RTSP.av` 雖然回 `7`，也沒有意義。

起播慢可先送 `set&property=forceiframe` 逼一張 I-frame。
播放端：ExoPlayer / VLC / ffmpeg 皆可，建議 RTSP over TCP（車內 Wi-Fi 抖動時比 UDP 穩）。

---

## 5. 自製 App 建議架構

```
┌─ 網路層 ──────────────────────────────────────┐
│ 綁定到記錄器 SSID 的 Network（避免被切回 4G）        │
│ 沒綁定時擋下所有請求（192.72.1.1 是公網位址）        │
│ HTTP client：逐檔逾時、Range 續傳                     │
└────────────────────────────────────────────────┘
        │ 控制                    │ 資料
        ▼                         ▼
  Config.cgi (§2)          靜態檔案 /mnt/mmc/…(§3)   RTSP (§4)
   get/set/dir/del          續傳下載                  即時預覽
```

**典型流程**
1. 連上 AP → 綁定該 Network → 確認 Config.cgi 有 HTTP 回應。
2. `TimeSettings` 對時（官方 App 每次連上都送）。
3. 列表：`dir` + `reardir` 逐頁讀取，前後鏡頭依時間戳配對。
   手機連著時記錄器不會錄影，所以整段連線期間列表都不會變，可以快取。
4. 預覽：`StreamStatus=ON` → `streamer=start` → `setcamid` → RTSP `av4`（前）/ `av5`（後）。
5. 下載：逐檔 Range 下載（不用停錄影），期間不做預覽或串流播放。
6. 離開：`UserDisconnect=ON`，再放掉網路。

**穩定度重點**
- 一定要綁定網路；沒綁定時一個請求都不要送。
- 下載、即時預覽、串流播放三選一，不要同時吃頻寬。
- 續傳，斷線後從中斷處接著下載。
- 大量匯出仍建議提示使用者可改用讀卡機（USB 直讀遠快於 2.4 GHz Wi-Fi）。

---

## 6. 快速驗證（curl）

```bash
# 列出一般錄影（前鏡頭 / 後鏡頭）
curl "http://192.72.1.1/cgi-bin/Config.cgi?action=dir&property=Normal&format=all&count=16&from=0"
curl "http://192.72.1.1/cgi-bin/Config.cgi?action=reardir&property=Normal&format=all&count=16&from=0"
# 讀全部設定
curl "http://192.72.1.1/cgi-bin/Config.cgi?action=get&property=Camera.Menu.*"
# 下載一段（含續傳）
curl -C - -O "http://192.72.1.1/mnt/mmc/Video/F/FILE260923-131459F.TS"
# 確認支援 Range
curl -r 0-1023 -o NUL -w "%{http_code}\n" "http://192.72.1.1/mnt/mmc/Video/F/FILE260923-131459F.TS"
# 預覽前鏡頭（需 ffmpeg）
ffplay -rtsp_transport tcp "rtsp://192.72.1.1:554/liveRTSP/av4"
```

**從手機直接測【實測】**：手機連在記錄器的 Wi-Fi 上時，可以透過 USB 偵錯用手機內建的 curl：

```bash
adb shell curl --interface wlan0 "http://192.72.1.1/cgi-bin/Config.cgi?action=get&property=Camera.Menu.*"
```

**抓官方 App 送的指令**：官方 App 會把每個請求以 `full url:` 印到 logcat。
在官方 App 操作要找的功能（例如「連線密碼」），同時執行：

```bash
adb logcat --pid=$(adb shell pidof com.vidure.mufuvideov2) | grep "full url"
```

---

## 7. 注意事項

- 未驗證的介面：任何連上此 AP 的裝置都能存取，不需驗證就能刪檔、格式化。
  請務必把 Wi-Fi 密碼從出廠的 `12345678` 改掉（目前要用官方 App 改）。
- `reboot`、`reset_to_default`、`del`、`SD0 format` 會影響機器狀態，操作前後務必確認。
- 不認得的 `set` 也回 `0 OK`，改完設定一定要用 `Camera.Menu.*` 讀回確認。
- **仍未驗證**：格式化指令（§2.2）、斷線後記錄器是否一定自己恢復錄影、`TimeSettings` 是否真的改到記錄器時間、
  `/Video/F/...` 這種下載路徑、多組 property 串接。

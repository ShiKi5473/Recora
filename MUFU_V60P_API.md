# MUFU V60P 行車記錄器 — 非官方 API 參考

> 目的：給自製 App 使用。內容由官方韌體 `V60P.bin`（v1.04.05）逆向整理，
> 涵蓋 Wi-Fi 連線、HTTP 控制介面（CGI）、檔案列表與下載、RTSP 即時串流。
> 僅適用於連上「本機自己的」記錄器 AP。此為社群整理，非原廠文件，欄位可能因韌體版本而異。

---

## 0. 硬體 / 系統摘要

| 項目 | 值 |
|---|---|
| SoC | SigmaStar SSC33x（I6E），單核 ARM ~1 GHz |
| 前/後鏡頭 | Sony IMX335（雙鏡頭，各自獨立錄影） |
| 錄影編碼 | H.265/HEVC 主檔（PID 256）、H.264 子串流（PID 257）、AAC 音訊（PID 258） |
| 容器格式 | MPEG-TS（副檔名 `.TS`），每段預設 1 分鐘、約 88 MB |
| Wi-Fi | RTL8189FS（2.4 GHz、單天線、802.11n、20 MHz），AP 模式 |
| 網頁伺服器 | GoAhead（支援 HTTP Range，可續傳/分段下載） |
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

> App 端注意：此 AP 沒有對外網路，Android/iOS 會判定「無網際網路」而自動切回行動網路。
> 連線後要呼叫系統 API 綁定此網路（Android `bindProcessToNetwork` / `requestNetwork`
> 指定該 SSID），否則所有請求會被送到行動網路而失敗。這是官方 App 最常見的不穩來源。

---

## 2. HTTP 控制介面（CGI）

所有控制都走同一支：

```
GET http://192.72.1.1/cgi-bin/Config.cgi?action=<ACTION>&property=<PROP>&value=<VAL>
```

- `action`：`get` | `set` | `dir` | `del`
- 多組參數可串接：`...&property=A&value=1&property=B&value=2`
- 沒有登入驗證（GoAhead `auth.txt` 未設任何使用者）。
- 回應是純文字或 XML（見下）。

### 2.1 `action=get`（讀設定 / 狀態）

回應格式為每行 `鍵=值;;`。支援萬用字元 `*`。

```
# 讀全部選單設定
GET ...?action=get&property=Camera.Menu.*
# 回應範例
Camera.Menu.VideoRes=1440P30FPS;;
Camera.Menu.GpsStamp=ON;;
...

# 裝置資訊
GET ...?action=get&property=devinfo.fwver
GET ...?action=get&property=devinfo.macaddr
GET ...?action=get&property=devinfo.linuxkernelver

# 串流狀態（回 0=停 / 1=串流中）
GET ...?action=get&property=streamerstatus

# 預覽狀態
GET ...?action=get&property=Camera.Preview.MJPEG.status.*
GET ...?action=get&property=Camera.Preview.RTSP.av
```

### 2.2 `action=set`（改設定 / 下指令）

底層是把值寫進主程式的控制管線 `/tmp/cardv_fifo`，再由 `CGI_PROCESS.sh` 轉譯。

**錄影控制（自製 App 下載前必用）**

```
# 停止錄影（下載前建議先停，釋放 CPU/SD 頻寬）
GET ...?action=set&property=Video&value=recordoff
# 恢復錄影（下載完務必還原，否則不會再錄）
GET ...?action=set&property=Video&value=recordon
# 切換（toggle）
GET ...?action=set&property=Video&value=record
# 拍照
GET ...?action=set&property=Video&value=capture
```

**串流控制**

```
GET ...?action=set&property=streamer&value=start
GET ...?action=set&property=streamer&value=stop
GET ...?action=set&property=forceiframe          # 立刻送一張 I-frame（加速起播）
GET ...?action=set&property=setbitrate&value=2000000     # 預覽碼率 (bps)
GET ...?action=set&property=setframerate&value=30
GET ...?action=set&property=setvideogop&value=30
GET ...?action=set&property=Camera.Preview.Source.1.Camid&value=front   # front / rear
GET ...?action=set&property=Camera.Preview.RTSP.av&value=7   # 見 §4 av 位元
```

**系統**

```
GET ...?action=set&property=reboot
GET ...?action=set&property=reset_to_default        # 回原廠設定
GET ...?action=set&property=Camera.System.Power&value=Off
```

**對時**（值為 `YYYY$MM$DD$hh$mm$ss$`，`$` 需 URL 編碼成 `%24`）

```
GET ...?action=set&property=TimeSettings&value=2026%2409%2423%2414%2430%2400%24
#                                              2026 $ 09 $ 23 $ 14 $ 30 $ 00 $
```

**常用選單設定**（`property=Camera.Menu.<名稱>`）

| property | 範例 value | 說明 |
|---|---|---|
| `Camera.Menu.VideoRes` | `1440P30FPS` / `1080P30` | 錄影解析度 |
| `Camera.Menu.GpsStamp` | `ON` / `OFF` | GPS 浮水印 |
| `Camera.Menu.SpeedStamp` | `ON` / `OFF` | 時速浮水印 |
| `Camera.Menu.EmerRec` | `ON` / `OFF` | 緊急錄影 |
| `Camera.Menu.GSensor` | `LEVEL0..3` | 碰撞感度（0=關） |
| `Camera.Menu.ParkingMonitor` | `ENABLE` / `DISABLE` | 停車監控 |
| `Camera.Menu.RecordWithAudio` | `ON` / `OFF` | 錄音 |
| `Camera.Menu.LoopingVideo` | `1MIN` / `3MIN` / `OFF` | 循環錄影每段長度 |
| `Camera.Menu.WiFi` | `ON` / `OFF` | Wi-Fi |
| `Camera.Menu.AutoCloseWifi` | `3MIN` / ... | Wi-Fi 閒置自動關 |

（完整清單可用 `get&property=Camera.Menu.*` 動態取得，不要寫死。）

### 2.3 錯誤 / 回傳判讀

- 成功通常回一段含 `<p>OK</p>` 的簡單 HTML，或 `get` 的 `鍵=值;;`。
- `set Video recordon/off` 在無 SD 卡時 `CGI_PROCESS.sh` 回非 0（HTTP 仍 200），需靠內容判斷。
- 建議每個關鍵操作後再 `get` 一次對應狀態確認（例如改完 `Video` 後讀 `streamerstatus` 或錄影狀態）。

---

## 3. 檔案列表與下載

### 3.1 列表 `action=dir`

```
GET http://192.72.1.1/cgi-bin/Config.cgi?action=dir&property=<FOLDER>&format=all&count=16&from=<OFFSET>
```

- `property`（資料夾）：`Normal`（一般錄影）、`Event`（緊急錄影）、`Photo`（拍照）
- `count`：每頁筆數，官方 App 用 `16`，建議照做（一次太多記錄器易卡）
- `from`：起始位移，做分頁；每次加上本頁回傳筆數，直到回空為止
- `format`：`all`

**回應 XML**

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<Normal>
  <amount>123</amount>
  <file>
     <name>FILE260923-131459F.TS</name>
     <format>TS</format>
     <size>88493104</size>
     <attr>0</attr>
     <time>2026-09-23 13:14:59</time>
  </file>
  ...
</Normal>
```

- `<amount>`：該資料夾總數（可用來判斷是否還要翻頁）
- `<name>`：檔名，可能是純檔名，也可能是完整/`$` 分隔路徑（解析時把 `$`→`/`）

### 3.2 SD 卡實際路徑（下載 URL 的來源）

檔案掛在 `/mnt/mmc`，GoAhead 直接把它當靜態根目錄，可直接 HTTP GET：

| 資料夾 | 前鏡頭 | 後鏡頭 | 縮圖(如有) |
|---|---|---|---|
| 一般 | `/mnt/mmc/Video/F/` | `/mnt/mmc/Video/R/` | `/mnt/mmc/Video/I/` |
| 緊急 | `/mnt/mmc/Emergency/F/` | `/mnt/mmc/Emergency/R/` | |
| 拍照 | `/mnt/mmc/Picture/F/` | `/mnt/mmc/Picture/R/` | |

**下載 URL：**
```
http://192.72.1.1/Video/F/FILE260923-131459F.TS
```

**檔名規則：** `FILE<yymmdd>-<hhmmss><F|R>.TS`，緊急錄影前綴 `EMER`。
前鏡頭 `...F.TS` ↔ 後鏡頭 `...R.TS` 一一對應（同時間戳），只差 `/F/`↔`/R/` 與 `F`↔`R`。

### 3.3 分段 / 續傳下載（穩定性關鍵）

GoAhead 支援 HTTP Range：

```
GET /Video/F/FILE....TS
Range: bytes=1048576-2097151
→ 206 Partial Content
   Content-Range: bytes 1048576-2097151/88493104
```

自製 App 的穩定策略建議：
1. 下載前 `Video=recordoff` 停錄影、`streamer=stop` 停預覽 → 把整台機器頻寬讓給下載。
2. 用 Range 分段（例如每 1–4 MB 一段），單段逾時就只重抓該段，不整檔重來。
3. 寫入暫存檔（`.part`），完成才改名；用 `<size>` 核對長度。
4. 全部完成後 `Video=recordon` 還原錄影（**務必**，否則機器停在停錄狀態）。

### 3.4 刪除 `action=del`

```
GET ...?action=del&property=$mnt$mmc$Video$F$FILE260923-131459F.TS
#            路徑用 $ 取代 /
```

> 刪除不可復原，建議自製 App 預設不開放或加二次確認。

---

## 4. RTSP 即時串流

```
rtsp://192.72.1.1:554/liveRTSP/av<N>
```

`<N>` = `Camera.Preview.RTSP.av` 的值（也可直接放在 URL）。它是位元旗標，
控制包含哪些軌（track）：

| bit | 值 | 內容 |
|---|---|---|
| bit0 | 1 | 視訊軌 A（主/前） |
| bit1 | 2 | 視訊軌 B（子串流/後） |
| bit2 | 4 | 音訊軌（AAC） |

- `av7`＝視訊 A + 視訊 B + 音訊（預設）
- `av1`＝只要主視訊
- `av5`＝主視訊 + 音訊

視訊為 H.264/H.265（依 `streamer.video.pixelfmt`），音訊 AAC。
起播慢可先 `set&property=forceiframe` 逼一張 I-frame。
只顯示一路可用 `Camera.Preview.Source.1.Camid=front|rear` 切換來源，
或用 `setbitrate` 降碼率換取流暢。

播放端：ExoPlayer / VLC / ffmpeg 皆可，建議 RTSP over TCP（車內 Wi-Fi 抖動時比 UDP 穩）。

---

## 5. 自製 App 建議架構

```
┌─ 網路層 ────────────────────────────────┐
│ 綁定到記錄器 SSID 的 Network（避免被切回 4G）  │
│ HTTP client：keep-alive、逐檔逾時、Range 續傳  │
└──────────────────────────────────────────┘
        │ 控制                    │ 資料
        ▼                         ▼
  Config.cgi (§2)          靜態檔案 /Video…(§3)   RTSP (§4)
   get/set/dir/del          分段下載               即時預覽
```

**典型流程**
1. 連上 AP → 綁定該 Network。
2. `get&property=devinfo.*` 確認連到的是記錄器、拿韌體版本。
3. 預覽：起 RTSP `av5`（前鏡+音訊）。
4. 要下載時：`streamer=stop` → `Video=recordoff` → 逐檔 Range 下載
   （用 `dir` 取清單、前後鏡頭配對）→ 完成 → `Video=recordon`（→ 需要再 `streamer=start`）。
5. 對時：進 App 時比對並 `TimeSettings` 校正。

**穩定度重點（比官方 App 好的關鍵）**
- 一定要綁定網路，否則請求外流到行動網路 → 這是最大宗的「連不上/很慢」。
- 下載與即時預覽二選一，不要同時吃頻寬。
- 分段 + 續傳，單段失敗只補該段。
- 大量匯出仍建議提示使用者可改用讀卡機（USB 直讀遠快於 2.4 GHz Wi-Fi）。

---

## 6. 快速驗證（curl）

```bash
# 裝置資訊
curl "http://192.72.1.1/cgi-bin/Config.cgi?action=get&property=devinfo.fwver"
# 列出一般錄影
curl "http://192.72.1.1/cgi-bin/Config.cgi?action=dir&property=Normal&format=all&count=16&from=0"
# 下載一段（含續傳）
curl -C - -O "http://192.72.1.1/Video/F/FILE260923-131459F.TS"
# 預覽（需 ffmpeg）
ffplay -rtsp_transport tcp "rtsp://192.72.1.1:554/liveRTSP/av5"
```

---

## 7. 注意事項

- 未驗證的介面：任何連上此 AP 的裝置都能存取。自製 App 上線給他人用前，
  建議至少把 Wi-Fi 密碼改掉（`Camera.Menu` / `net_config` 的 AP PSK）。
- `reboot`、`reset_to_default`、`del`、`SD Format`、`recordoff` 會影響機器狀態，
  操作前後務必確認，並在 `recordoff` 後負責還原 `recordon`。
- 以上端點來自韌體逆向，不同韌體版本可能有出入；正式使用前用 §6 逐項實測。

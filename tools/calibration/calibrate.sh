#!/usr/bin/env bash
# 批次剪輯匯出，收集「預估 vs 實際」檔案大小（debug build 的 logcat tag RecoraExport）。
# 用法：bash tools/calibration/calibrate.sh jobs.txt
#   jobs.txt 每行：編碼|解析度|畫質|檔名|起點秒|長度秒|去除聲音(0/1)，# 開頭是註解
#     編碼：H.264 / H.265 / 不重新編碼；解析度：原始解析度 / 1080p / 720p；畫質：高 / 標準 / 省空間
#     檔名是相簿裡的檔名，例如 20261013090104_004646F.MP4、EMER261014-085504F.TS
#   環境變數：RESTORE="H.265|原始解析度|標準"（跑完改回的匯出設定，空字串 = 不改回）
# 結果：每支一行 append 到 tools/calibration/out/results.log；過程在 out/progress.log。
# 用 debug build 的 intent 直接開剪輯畫面並匯出（見 MainActivity.handleCalibrationIntent），不模擬點擊；
# 跑的時候畫面要亮著、App 在前景（腳本會設定 USB 充電時不關螢幕）。
set -u
ADB="${ADB:-C:/Users/qz547/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
OUT="$(cd "$(dirname "$0")" && pwd)/out"
mkdir -p "$OUT"
JOBS_FILE=${1:?用法：calibrate.sh jobs.txt}
RESTORE=${RESTORE-"H.265|原始解析度|標準"}
LOG="$OUT/progress.log"
say() { echo "$(date '+%F %T') $*" | tee -a "$LOG"; }
# adb 斷線時（USB 常常跳一下）等它回來；不讀 stdin，免得吃掉 jobs 檔
adbw() { timeout 30 "$ADB" wait-for-device </dev/null 2>/dev/null; "$ADB" "$@" </dev/null; }

# 畫面上的名稱 → App 的 enum 名稱
codec_of() { case $1 in H.264) echo H264 ;; H.265) echo H265 ;; 不重新編碼) echo Original ;; *) return 1 ;; esac; }
res_of() { case $1 in 原始解析度) echo Original ;; 1080p) echo P1080 ;; 720p) echo P720 ;; *) return 1 ;; esac; }
quality_of() { case $1 in 高) echo High ;; 標準) echo Standard ;; 省空間) echo Small ;; *) return 1 ;; esac; }

start_app() { adbw shell am start -n tw.com.recora/.MainActivity "$@" >/dev/null; }
# 等 RecoraExport 的 done（匯出並存檔完成，或失敗），最多 $1 秒。用 logcat -d 每秒查一次：
# 跟著 logcat 串流的話，done 之後沒有新的 log 行，logcat 不會結束
wait_done() {
  local dump
  for _ in $(seq "$1"); do
    dump=$(adbw logcat -d -s RecoraExport:I)
    grep -q "done saved=" <<< "$dump" && { echo "$dump"; return 0; }
    sleep 1
  done
  return 1
}

adbw shell svc power stayon true
adbw logcat -G 4M 2>/dev/null   # 預設 256 KB，App 的 log 很快被擠掉
adbw shell input keyevent KEYCODE_WAKEUP
start_app; sleep 2

n=0
total=$(grep -cv '^\s*\(#\|$\)' "$JOBS_FILE")
while IFS='|' read -r codec res quality name start len mute; do
  case "$codec" in ''|\#*) continue ;; esac
  n=$((n + 1))
  say "[$n/$total] $codec $res $quality $name 起點${start}s 長${len}s 靜音$mute"
  c=$(codec_of "$codec") && r=$(res_of "$res") && q=$(quality_of "$quality") || { say "設定寫錯，跳過"; continue; }
  adbw logcat -c
  adbw shell input keyevent KEYCODE_WAKEUP   # stayon 在小米上不一定有效，螢幕關了播放器讀不到規格
  start_app --es cal_codec "$c" --es cal_res "$r" --es cal_quality "$q" --es cal_file "$name" \
    --el cal_start_ms $((start * 1000)) --el cal_len_ms $((len * 1000)) --ez cal_mute "$([ "$mute" = 1 ] && echo true || echo false)"
  dump=$(wait_done 180)
  done_line=$(grep -m 1 "done saved=" <<< "$dump")
  case "$done_line" in
    *"failure=null"*)
      line=$(grep "name=" <<< "$dump" | tail -1 | grep -o "name=.*")
      [ -n "$line" ] || { say "有 done 但沒有 name= 那一行，跳過"; continue; }
      echo "$(date '+%F %T') $line" | tee -a "$OUT/results.log" >> "$LOG" ;;
    "") adbw exec-out screencap -p > "$OUT/timeout$n.png"; say "等不到匯出完成，跳過" ;;
    *) say "失敗，跳過：${done_line#*failure=}" ;;
  esac
done < "$JOBS_FILE"
say "全部完成"

if [ -n "$RESTORE" ]; then
  IFS='|' read -r rc rr rq <<< "$RESTORE"
  adbw logcat -c
  start_app --es cal_codec "$(codec_of "$rc")" --es cal_res "$(res_of "$rr")" --es cal_quality "$(quality_of "$rq")"
  sleep 2
  adbw logcat -d -s RecoraExport:I | grep "settings " | tail -1 | grep -q "codec=$(codec_of "$rc") res=$(res_of "$rr") quality=$(quality_of "$rq")" \
    && say "已改回 $rc・$rr・$rq" || say "改回 $rc・$rr・$rq 失敗，請手動確認"
fi
adbw shell svc power stayon false

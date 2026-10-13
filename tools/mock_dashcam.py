"""開發用的假 MUFU V60P 記錄器（只用標準函式庫）。

模擬 Config.cgi 的 get / set / dir / del，以及支援 HTTP Range 的靜態檔案下載，
讓 App 在沒有實機時也能在模擬器上測試（先 adb reverse tcp:8080 tcp:8080，App 的 debug host 覆寫設成 127.0.0.1:8080）。

  python tools/mock_dashcam.py                       # 預設 127.0.0.1:8080
  python tools/mock_dashcam.py --throttle 400        # 限速 400 KB/s，方便觀察進度 / 取消
  python tools/mock_dashcam.py --drop-every 700      # 每個回應送 700 KB 就斷線，測續傳
  python tools/mock_dashcam.py --path-style root     # 只接受 /Video/F/... 路徑，測備用路徑

檔案內容是依位移產生的固定位元組（不是真的影片），大小與實機同級可調。
"""
import argparse
import datetime as dt
import re
import struct
import sys
import threading
import time
import urllib.parse
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import mock_astro  # 同資料夾：--brand astro 的 Novatek 指令（python tools/mock_dashcam.py --brand astro）

SD_ROOT = "/mnt/mmc"
FOLDERS = {"Normal": "Video", "Event": "Emergency", "Parking": "Parking", "Photo": "Picture"}
CYCLE = bytes(range(256)) * 4096  # 1 MB 的循環樣式

try:
    sys.stdout.reconfigure(errors="replace")
except AttributeError:
    pass


def png(width, height, seed):
    """產生一張漸層 PNG（檔名是 .JPG，但 Android 的解碼器會依內容判斷格式）。"""
    rows = []
    for y in range(height):
        row = bytearray([0])
        for x in range(width):
            row += bytes(((x * 255 // width + seed * 40) & 0xFF, (y * 255 // height) & 0xFF, (seed * 70) & 0xFF))
        rows.append(bytes(row))
    raw = b"".join(rows)

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))


class Store:
    """虛擬 SD 卡：path → (size, time, photo_bytes 或 None)。"""

    def __init__(self, clips, size_kb, events, photos):
        self.lock = threading.Lock()
        self.files = {}
        now = dt.datetime.now().replace(microsecond=0)
        start = now - dt.timedelta(minutes=clips)
        # 一般錄影：前一天一半、今天一半，每分鐘一段
        for i in range(clips):
            t = start + dt.timedelta(minutes=i) - (dt.timedelta(days=1) if i < clips // 2 else dt.timedelta())
            self._add_clip("Video", "FILE", t, size_kb * 1024 + i * 1111)
        for i in range(events):
            self._add_clip("Emergency", "EMER", now - dt.timedelta(hours=2, minutes=i * 7), size_kb * 1024 // 2 + i * 777)
        for i in range(photos):
            t = now - dt.timedelta(hours=1, minutes=i * 5)
            data = png(320, 180, i)
            for cam in ("F",):
                name = f"IMG{t:%y%m%d-%H%M%S}{cam}.JPG"
                self.files[f"{SD_ROOT}/Picture/{cam}/{name}"] = (len(data), t, data)

    def _add_clip(self, dirname, prefix, t, size):
        for cam, delta in (("F", 0), ("R", -4321)):
            name = f"{prefix}{t:%y%m%d-%H%M%S}{cam}.TS"
            self.files[f"{SD_ROOT}/{dirname}/{cam}/{name}"] = (size + delta, t, None)

    def listing(self, dirname, list_rear):
        with self.lock:
            items = [(p, v) for p, v in self.files.items()
                     if p.startswith(f"{SD_ROOT}/{dirname}/") and (list_rear or "/F/" in p)]
        items.sort(key=lambda kv: (kv[1][1], kv[0]), reverse=True)  # 最新在前
        return items

    def get(self, path):
        with self.lock:
            return self.files.get(path)

    def delete(self, path):
        with self.lock:
            return self.files.pop(path, None) is not None


class Handler(BaseHTTPRequestHandler):
    store: Store = None
    opts = None
    recording = True
    menu = {
        "VideoRes": "1440P30FPS", "VideoClipTime": "1MIN", "SoundRecord": "ON", "EmerRec": "ON",
        "GSensor": "LEVEL0", "ParkingMonitor": "ENABLE", "ParkingGuard": "ON", "TimeStamp": "ON",
        "GpsStamp": "ON", "SpeedStamp": "OFF", "TiltCorrection": "ON", "UpsideDown": "Normal",
        "FrontEV": "3", "RearEV": "3", "HDR": "OFF", "NightMode": "OFF", "PowerSaving": "1MIN",
        "VoiceSwitch": "ON", "SpeechRecognition": "ON", "Led": "ON", "AutoCloseWifi": "ON", "Beep": "ON",
        "SD0": "READY", "DeviceType": "V60P",
    }

    def log_message(self, fmt, *args):
        rng = self.headers.get("Range") if self.headers else None
        extra = f"  [Range: {rng}]" if rng else ""
        sys.stdout.write(f"{time.strftime('%H:%M:%S')} {self.address_string()} {fmt % args}{extra}\n")
        sys.stdout.flush()

    # ---- CGI ----------------------------------------------------------------
    def do_GET(self):
        self._handle(send_body=True)

    def do_HEAD(self):
        self._handle(send_body=False)

    def _handle(self, send_body):
        url = urllib.parse.urlsplit(self.path)
        if self.opts.brand == "astro" and url.path in ("/", ""):
            return self._text(mock_astro.dispatch(self, url.query), ctype="text/xml")
        if url.path == "/cgi-bin/Config.cgi":
            # 實機的 CGI 不會把 %24 解回 $：del / TimeSettings 送 %24 會找不到
            if "%24" in url.query:
                return self._text("no key found!\n0\nOK\n")
            return self._cgi(urllib.parse.parse_qsl(url.query, keep_blank_values=True))
        return self._static(urllib.parse.unquote(url.path), send_body)

    def _text(self, body, status=200, ctype="text/html"):
        data = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _cgi(self, params):
        action = next((v for k, v in params if k == "action"), "")
        props = [v for k, v in params if k == "property"]
        values = [v for k, v in params if k == "value"]
        prop = props[0] if props else ""

        if action == "get":
            if prop.startswith("Camera.Menu"):
                # 實機格式：每行 key=value，沒有 API 文件寫的 ;;
                items = {k: v for k, v in Handler.menu.items() if prop == "Camera.Menu.*" or prop == f"Camera.Menu.{k}"}
                return self._text("0\nOK\n" + "".join(f"Camera.Menu.{k}={v}\n" for k, v in items.items()) + "0\nOK\n")
            if prop.startswith("Camera.Preview.MJPEG.status"):
                rec = "Recording" if Handler.recording else "Standby"
                return self._text("Camera.Preview.MJPEG.status.mode=Videomode;;\n"
                                  f"Camera.Preview.MJPEG.status.record={rec};;\n")
            # 實機對 devinfo.* 等不存在的 key 就是回這串
            return self._text("0\nOK\nUsage ./CGI_PROCESS.sh {no this parameter}\n0 OK no this key\n")

        if action == "set":
            for i, p in enumerate(props):
                v = values[i] if i < len(values) else ""
                if p == "Video" and v in ("recordon", "recordoff"):
                    Handler.recording = v == "recordon"
                # 實機：用不含 Camera.Menu. 前綴的名稱才會生效（Beep 在此機型改不動）
                if p in Handler.menu and p != "Beep":
                    Handler.menu[p] = v
                state = "錄影中" if Handler.recording else "*** 已停止錄影 ***"
                print(f"    >>> SET {p}={v}   ({state})")
            return self._text("0\nOK\n")

        if action in ("dir", "reardir"):
            folder = FOLDERS.get(prop)
            if folder is None:
                return self._text("0\nOK\nno this key\n")
            count = int(next((v for k, v in params if k == "count"), "16"))
            start = int(next((v for k, v in params if k == "from"), "0"))
            # 實機：dir 只列前鏡頭，reardir 列後鏡頭
            cam = "/R/" if action == "reardir" else "/F/"
            items = [kv for kv in self.store.listing(folder, True) if cam in kv[0]][start:start + count]
            parts = ['<?xml version="1.0" encoding="UTF-8" ?>', f"<{folder}>"]
            for path, (size, t, _) in items:
                parts += ["   <file>", f"      <name>{path}</name>", f"      <format>{path.rsplit('.', 1)[-1]}</format>",
                          f"      <size>{size}</size>", "      <attr>RW</attr>", f"      <time>{t:%Y-%m-%d %H:%M:%S}</time>",
                          "   </file>"]
            # 實機的 <amount> 固定是 0（不能拿來判斷總數）
            parts.append("<amount>0</amount>")
            parts.append(f"</{folder}>")
            return self._text("\n".join(parts) + "\n")

        if action == "del":
            path = prop.replace("$", "/")
            ok = self.store.delete(path)
            print(f"    >>> DEL {path}  {'OK' if ok else '(不存在)'}")
            return self._text("0\nOK\n" if ok else "0\nno such file\n")

        return self._text("0\nOK\nUsage ./CGI_PROCESS.sh {no this parameter}\n")

    # ---- 靜態檔案（支援 Range） ------------------------------------------------
    def _static(self, path, send_body):
        style = self.opts.path_style
        if self.opts.brand == "astro":
            sd = path if path.startswith("/DCIM/") else None
        elif path.startswith(SD_ROOT + "/"):
            sd = path if style in ("mnt", "both") else None
        else:
            sd = SD_ROOT + path if style in ("root", "both") else None
        entry = self.store.get(sd) if sd else None
        if entry is None:
            return self._text("404 Not Found", status=404)
        size, _, photo = entry

        start, end, status = 0, size - 1, 200
        rng = self.headers.get("Range")
        if rng:
            m = re.fullmatch(r"bytes=(\d*)-(\d*)", rng.strip())
            if m and m.group(1):
                start = int(m.group(1))
                if m.group(2):
                    end = min(int(m.group(2)), size - 1)
                if start >= size:
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{size}")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                status = 206
        length = end - start + 1
        self.send_response(status)
        self.send_header("Content-Type", "image/jpeg" if photo else "video/mp2t")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if not send_body:
            return

        seed = sum(map(ord, sd)) & 0xFF
        sent = 0
        pos = start
        drop = self.opts.drop_every * 1024 if self.opts.drop_every else None
        chunk = 16 * 1024
        rate = self.opts.throttle * 1024 if self.opts.throttle else None
        t0 = time.time()
        try:
            while pos <= end:
                n = min(chunk, end - pos + 1)
                if photo is not None:
                    data = photo[pos:pos + n]
                else:
                    k = (pos + seed) & 0xFF
                    data = CYCLE[k:k + n]
                if drop is not None and sent + n > drop and length > drop:
                    data = data[:max(0, drop - sent)]
                    self.wfile.write(data)
                    print(f"    >>> 模擬斷線：{sd} 送了 {sent + len(data)} bytes 後中斷")
                    self.close_connection = True
                    return
                self.wfile.write(data)
                sent += n
                pos += n
                if rate:
                    ahead = sent / rate - (time.time() - t0)
                    if ahead > 0:
                        time.sleep(ahead)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            print(f"    >>> 用戶端中斷：{sd} 已送 {sent} bytes")


def main():
    ap = argparse.ArgumentParser(description="假 MUFU V60P 記錄器")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--clips", type=int, default=40, help="一般錄影段數（預設 40，超過 16 才測得到分頁）")
    ap.add_argument("--events", type=int, default=4)
    ap.add_argument("--photos", type=int, default=6)
    ap.add_argument("--size-kb", type=int, default=3072, help="每段影片大約大小（KB）")
    ap.add_argument("--throttle", type=int, default=0, help="限速 KB/s（0 = 不限）")
    ap.add_argument("--drop-every", type=int, default=0, help="每個回應送這麼多 KB 就斷線（0 = 不斷）")
    ap.add_argument("--path-style", choices=("both", "mnt", "root"), default="both")
    ap.add_argument("--brand", choices=("mufu", "astro"), default="mufu", help="模擬哪一種記錄器（astro = Novatek LEO3）")
    opts = ap.parse_args()

    if opts.brand == "astro":
        Handler.store = Store(0, 0, 0, 0)
        mock_astro.fill(Handler.store, opts.clips, opts.size_kb, opts.events)
    else:
        Handler.store = Store(opts.clips, opts.size_kb, opts.events, opts.photos)
    Handler.opts = opts
    server = ThreadingHTTPServer((opts.host, opts.port), Handler)
    print(f"假記錄器（{opts.brand}）：http://{opts.host}:{opts.port}  （{len(Handler.store.files)} 個檔案，"
          f"路徑樣式 {opts.path_style}，限速 {opts.throttle or '無'} KB/s）")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()

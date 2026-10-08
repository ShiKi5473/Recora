"""MUFU V60P dashcam footage downloader (own device, over its Wi-Fi AP).

Uses the camera's own documented CGI API (the same one the phone app uses)
to list clips and copy them to this PC. Standard library only.

  python dashcam_download.py list
  python dashcam_download.py list --folder Event
  python dashcam_download.py speed
  python dashcam_download.py get --date today --out D:/Dashcam
  python dashcam_download.py get --date 260923 --folder Event --out D:/Dashcam
"""
import argparse
import os
import re
import sys
import time
import urllib.parse
import urllib.request

HOST = "192.72.1.1"
CGI = f"http://{HOST}/cgi-bin/Config.cgi"
PAGE = 16
CHUNK = 256 * 1024
TIMEOUT = 30
FOLDER_DIR = {"Normal": "Video", "Event": "Emergency", "Photo": "Picture"}

try:
    sys.stdout.reconfigure(errors="replace")
except AttributeError:
    pass


def get(url, timeout=TIMEOUT):
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return r.read()


def cgi(params):
    return get(CGI + "?" + urllib.parse.urlencode(params)).decode("utf-8", "replace")


FILE_RE = re.compile(r"<file>(.*?)</file>", re.S)
TAG_RE = re.compile(r"<(\w+)>(.*?)</\1>", re.S)


def to_url(name, folder):
    name = name.replace("$", "/")
    if not name.startswith("/"):
        name = f"/mnt/mmc/{FOLDER_DIR.get(folder, folder)}/F/{name}"
    return f"http://{HOST}" + urllib.parse.quote(name)


def rear_of(url):
    """Front clip URL -> matching rear clip URL, or None."""
    r = re.sub(r"/F/([^/]+)F\.(\w+)$", r"/R/\1R.\2", url)
    return r if r != url else None


def list_files(folder="Normal"):
    """All clips in a folder, oldest first: [{name, url, size, time}]."""
    out, seen, start = [], set(), 0
    while True:
        xml = cgi([("action", "dir"), ("property", folder),
                   ("format", "all"), ("count", PAGE), ("from", start)])
        page = []
        for block in FILE_RE.findall(xml):
            f = dict(TAG_RE.findall(block))
            name = os.path.basename(f.get("name", "").strip())
            if not name or name in seen:
                continue
            seen.add(name)
            page.append({"name": name, "url": to_url(f["name"].strip(), folder),
                         "size": int(f.get("size", "0") or 0),
                         "time": f.get("time", "").strip()})
        if not page:
            break
        out += page
        start += len(page)
    out.sort(key=lambda f: f["name"])
    return out


def mb(n):
    return f"{n / 1048576:.1f} MB"


def download(url, dest):
    tmp = dest + ".part"
    got = 0
    t0 = time.time()
    with urllib.request.urlopen(url, timeout=TIMEOUT) as r, open(tmp, "wb") as fh:
        total = int(r.headers.get("Content-Length", 0))
        while True:
            buf = r.read(CHUNK)
            if not buf:
                break
            fh.write(buf)
            got += len(buf)
            if total:
                pct = got * 100 // total
                sys.stdout.write(f"\r    {pct:3d}%  {mb(got)}")
                sys.stdout.flush()
    os.replace(tmp, dest)
    dt = max(time.time() - t0, 1e-6)
    speed = got / 1048576 / dt
    sys.stdout.write(f"\r    done {mb(got)} in {dt:.1f}s  ({speed:.1f} MB/s)\n")
    return got, dt


def date_prefix(s):
    if s in ("today", "今天"):
        return time.strftime("%y%m%d")
    if not re.fullmatch(r"\d{6}", s or ""):
        sys.exit("--date 需為 today 或 6 碼 yymmdd（例如 260923）")
    return s


def cmd_list(a):
    files = list_files(a.folder)
    print(f"{a.folder}: {len(files)} 個檔案，合計 {mb(sum(f['size'] for f in files))}")
    for f in files:
        print(f"  {f['name']:28} {mb(f['size']):>10}  {f['time']}")


def cmd_speed(a):
    files = list_files(a.folder)
    if not files:
        sys.exit("沒有檔案可測")
    f = files[-1]
    print(f"測速：下載 {f['name']} ({mb(f['size'])})")
    dst = os.path.join(a.out or ".", "_speedtest_" + f["name"])
    os.makedirs(os.path.dirname(dst) or ".", exist_ok=True)
    got, dt = download(f["url"], dst)
    os.remove(dst)
    print(f"平均下載速度：{got / 1048576 / dt:.1f} MB/s "
          f"（≈ {got * 8 / 1e6 / dt:.0f} Mbps）")


def cmd_get(a):
    prefix = date_prefix(a.date)
    files = [f for f in list_files(a.folder) if prefix in f["name"]]
    if not files:
        sys.exit(f"找不到 {prefix} 的檔案")
    jobs = []
    for f in files:
        jobs.append((f["name"], f["url"]))
        if a.rear:
            ru = rear_of(f["url"])
            if ru:
                jobs.append((os.path.basename(urllib.parse.unquote(ru)), ru))
    out = a.out or "."
    os.makedirs(out, exist_ok=True)
    print(f"{a.folder} {prefix}：{len(jobs)} 個檔案 → {out}")
    total_bytes = total_time = 0
    for i, (name, url) in enumerate(jobs, 1):
        dest = os.path.join(out, name)
        if os.path.exists(dest):
            print(f"[{i}/{len(jobs)}] {name} 已存在，跳過")
            continue
        print(f"[{i}/{len(jobs)}] {name}")
        try:
            g, dt = download(url, dest)
            total_bytes += g
            total_time += dt
        except Exception as e:
            print(f"    失敗：{e}")
    if total_time:
        print(f"完成：{mb(total_bytes)}，平均 {total_bytes / 1048576 / total_time:.1f} MB/s")


def main():
    p = argparse.ArgumentParser(description="MUFU V60P 影片下載工具")
    sub = p.add_subparsers(dest="cmd", required=True)
    common = dict(folder="Normal")

    pl = sub.add_parser("list", help="列出檔案")
    pl.add_argument("--folder", default="Normal", choices=list(FOLDER_DIR))
    pl.set_defaults(func=cmd_list)

    ps = sub.add_parser("speed", help="下載一段影片測速")
    ps.add_argument("--folder", default="Normal", choices=list(FOLDER_DIR))
    ps.add_argument("--out", default=None)
    ps.set_defaults(func=cmd_speed)

    pg = sub.add_parser("get", help="下載某天的影片")
    pg.add_argument("--date", required=True, help="today 或 yymmdd")
    pg.add_argument("--folder", default="Normal", choices=list(FOLDER_DIR))
    pg.add_argument("--out", default=None)
    pg.add_argument("--no-rear", dest="rear", action="store_false",
                    help="只下載前鏡頭")
    pg.set_defaults(func=cmd_get, rear=True)

    a = p.parse_args()
    try:
        a.func(a)
    except urllib.error.URLError as e:
        sys.exit(f"連不到記錄器 {HOST}：{e}\n請確認電腦已連上 MUFU_V60P Wi-Fi。")


if __name__ == "__main__":
    main()

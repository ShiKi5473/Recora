"""mock_dashcam.py 的 Astro LEO3（Novatek）模式（--brand astro）。

指令格式：GET /?custom=1&cmd=N[&par=][&str=]，回應 <Function><Cmd/><Status/>…</Function>（ASTRO_LEO3_API.md）。
收到的每個指令都印出來（含目前的模式與錄影狀態），用來檢查 App 停錄／恢復錄影的時序。
"""
import datetime as dt
import threading
import time
import urllib.parse

SETTINGS = {
    "2002": "0", "2003": "1", "2004": "1", "2005": "6", "2006": "0", "2007": "1", "2008": "1", "2011": "1",
    "2012": "0", "2023": "0", "8101": "0", "8103": "6", "8104": "1", "8105": "1", "8113": "0",
}
MENU = {
    "2003": ("MOVIE_CYCLIC_REC", ["1MIN", "3MIN", "5MIN"]),
    "8101": ("FREQUENCY", ["50HZ", "60HZ"]),
}
SIZES = [("2560x1440P30", 0), ("2560x1440P30 HDR", 1), ("1920x1080P60", 2)]
HEADER = '<?xml version="1.0" encoding="UTF-8" ?>\n'


class State:
    mode = 1  # 1 = 錄影模式、2 = 回放模式
    recording = True
    live = False
    settings = dict(SETTINGS)
    started = time.time()
    lock = threading.Lock()


def fill(store, clips, size_kb, events):
    """Astro 的檔案：/DCIM/VIDEO_F|R、EMERG_F|R；NAME 是開始時間，TIME 是結束時間（再加 1 分鐘）。"""
    now = dt.datetime.now().replace(microsecond=0)
    seq = 100

    def add(kind, start, size):
        nonlocal seq
        seq += 1
        for cam, delta in (("F", 0), ("R", -4321)):
            name = f"{start:%Y%m%d%H%M%S}_{seq:06d}{cam}.MP4"
            store.files[f"/DCIM/{kind}_{cam}/{name}"] = (size + delta, start + dt.timedelta(minutes=1), None)

    for i in range(clips):
        t = now - dt.timedelta(minutes=(clips - i) * 3) - (dt.timedelta(days=1) if i < clips // 2 else dt.timedelta())
        add("VIDEO", t, size_kb * 1024 + i * 1111)
    for i in range(events):
        add("EMERG", now - dt.timedelta(hours=2, minutes=i * 7), size_kb * 1024 // 2 + i * 777)


def fn(cmd, status=0, value=None, string=None):
    body = f"<Function><Cmd>{cmd}</Cmd><Status>{status}</Status>"
    if value is not None:
        body += f"<Value>{value}</Value>"
    if string is not None:
        body += f"<String>{string}</String>"
    return HEADER + body + "</Function>\n"


def dispatch(handler, query):
    """回傳 XML 字串。查詢裡的 : 與 \\ 原樣收到（官方 App 與本 App 都不編碼）。"""
    q = dict(urllib.parse.parse_qsl(query, keep_blank_values=True))
    cmd = int(q.get("cmd", "0") or 0)
    par, s = q.get("par"), q.get("str")
    st = State
    store = handler.store
    state = f"mode={'回放' if st.mode == 2 else '錄影'}, {'錄影中' if st.recording else '*** 已停錄 ***'}"
    if cmd not in (8107, 2016, 3014):
        print(f"    >>> ASTRO cmd={cmd}" + (f" par={par}" if par is not None else "")
              + (f" str={s}" if s else "") + f"   ({state})")

    if cmd == 3012:
        return fn(cmd, string="MB:V1.00.11,CameraA:V1.00.01,CameraB:V1.00.01")
    if cmd == 8567:
        return fn(cmd, string="NT;NT96560;KH02;X106;00;AFM19;02;MB:V1.00.11;58:e4:eb:ab:03:a8")
    if cmd == 8107:
        return fn(cmd, value=3)
    if cmd == 3014:
        pairs = {"2001": "1" if st.recording else "0", **st.settings}
        return HEADER + "<Function><Cmd>3014</Cmd><Status>0</Status></Function>" + "".join(
            f"<Function><Cmd>{k}</Cmd><Status>{v}</Status></Function>" for k, v in pairs.items())
    if cmd == 3031:
        items = "".join(
            f"<Item><Cmd>{k}</Cmd><Name>{name}</Name><MenuList>" + "".join(
                f"<Option><Index>{i}</Index><Id>{o}</Id></Option>" for i, o in enumerate(opts)) + "</MenuList></Item>"
            for k, (name, opts) in MENU.items())
        return HEADER + f"<Function><Cmd>3031</Cmd><Status>0</Status><MenuList>{items}</MenuList></Function>"
    if cmd == 3030:
        items = "".join(f"<Item><Name>{n}</Name><Index>{i}</Index><Type>1</Type></Item>" for n, i in SIZES)
        return HEADER + f"<Function><Cmd>3030</Cmd><Status>0</Status><List>{items}</List></Function>"
    if cmd == 2001:
        if par == "1" and st.mode == 2:
            return fn(cmd, -13)  # 回放模式不能開始錄影
        st.recording = par == "1"
        return fn(cmd)
    if cmd == 2016:
        return fn(cmd, value=int(time.time() - st.started) % 180 + 1 if st.recording else 0)
    if cmd == 3001:
        st.mode = 2 if par == "2" else 1
        return fn(cmd)
    if cmd == 3015:
        if st.mode != 2:
            print("    !!! 警告：3015 不在回放模式（實機行為待實測）")
        with store.lock:
            items = sorted(((p, v) for p, v in store.files.items() if p.startswith("/DCIM/")),
                           key=lambda kv: (kv[1][1], kv[0]))
        parts = [HEADER + "<LIST>"]
        for path, (size, t, _) in items:
            name = path.rsplit("/", 1)[-1]
            attr = 33 if "/EMERG_" in path else 32
            fpath = "A:" + path.replace("/", "\\")
            parts.append(f"<ALLFile><File><NAME>{name}</NAME><FPATH>{fpath}</FPATH><SIZE>{size}</SIZE>"
                         f"<TIMECODE>0</TIMECODE><TIME>{t:%Y/%m/%d %H:%M:%S}</TIME><ATTR>{attr}</ATTR></File></ALLFile>")
        parts.append("</LIST>")
        return "\n".join(parts)
    if cmd == 4003:
        path = (s or "")[2:].replace("\\", "/")
        if st.mode != 2:
            print("    !!! 警告：刪除時不在回放模式")
        if "/EMERG_" in path:
            return fn(cmd, -4)  # 實機：ATTR 33 一律拒絕
        return fn(cmd, 0 if store.delete(path) else -1)
    if cmd in (3005, 3006):
        return fn(cmd)
    if cmd == 3024:
        return fn(cmd, value=1)
    if cmd == 3017:
        used = sum(v[0] for v in store.files.values())
        return fn(cmd, value=max(0, 64 * 1024 ** 3 - used))
    if cmd == 3010:
        with store.lock:
            store.files.clear()
        return fn(cmd)
    if cmd == 2015:
        st.live = par == "1"
        return fn(cmd)
    if cmd == 2019:
        host = (handler.headers.get("Host") or "127.0.0.1").split(":")[0]
        return (HEADER + "<LIST>"
                f"<MovieLiveViewLink>rtsp://{host}/xxx.mov</MovieLiveViewLink>"
                f"<PhotoLiveViewLink>rtsp://{host}/xxx.mov</PhotoLiveViewLink></LIST>")
    if cmd == 3028:
        return fn(cmd)
    if str(cmd) in st.settings and par is not None:
        st.settings[str(cmd)] = par
        return fn(cmd)
    return fn(cmd, -256)

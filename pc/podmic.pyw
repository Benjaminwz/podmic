"""錄音台（電腦端）：跟手機的錄音台連線 → 看音量、遙控錄音、寫提詞稿（大螢幕提詞）、收檔、一鍵後製成 podcast。"""
import ctypes
import json
import math
import re
import os
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import tkinter as tk
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from tkinter import font as tkfont
from tkinter import messagebox, ttk
from urllib.parse import parse_qs, urlparse

FROZEN = getattr(sys, "frozen", False)
ROOT = os.path.dirname(sys.executable) if FROZEN else os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SAVE = os.path.join(ROOT, "錄音")
DONE = os.path.join(SAVE, "成品")
CFG = os.path.join(ROOT, "config.json" if FROZEN else os.path.join("pc", "config.json"))
PC_VERSION = 11


def battery():
    """筆電沒插電時回傳剩幾 %；有插電（或桌機）回傳 None。"""
    class S(ctypes.Structure):
        _fields_ = [("ac", ctypes.c_byte), ("flag", ctypes.c_byte), ("pct", ctypes.c_byte), ("r", ctypes.c_byte),
                    ("life", ctypes.c_ulong), ("full", ctypes.c_ulong)]
    st = S()
    try:
        ctypes.windll.kernel32.GetSystemPowerStatus(ctypes.byref(st))
        return st.pct if st.ac == 0 and 0 <= st.pct <= 100 else None
    except Exception:
        return None


def backup_new(folder):
    """把還沒備份過的錄音原始檔複製一份到備份資料夾（隨身碟、另一顆硬碟）。"""
    try:
        os.makedirs(folder, exist_ok=True)
        for f in os.listdir(SAVE):
            a, b = os.path.join(SAVE, f), os.path.join(folder, f)
            if os.path.isfile(a) and f.lower().endswith((".wav", ".json")) and not (os.path.exists(b) and os.path.getsize(b) == os.path.getsize(a)):
                shutil.copy2(a, b)
                if f.lower().endswith(".wav"):
                    log("已備份 " + f)
    except OSError as e:
        log("備份失敗：" + str(e)[:40])
PCZIP = os.path.join(ROOT, "PodMic-laptop.zip")
QS = {"gpu": None}


def served_pc():
    """這台電腦手上有沒有可以分給別台的筆電版（桌機打包完會有）。"""
    try:
        with open(os.path.join(ROOT, "PodMic-laptop.version")) as f:
            return int(f.read().strip()) if os.path.exists(PCZIP) else 0
    except (OSError, ValueError):
        return 0


def find_update():
    """打包版開啟時問一下區域網路：有沒有哪台電腦有比較新的版本。回傳 (位址, 版本) 或 None。"""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        s.settimeout(1.5)
        s.sendto(b"PODMIC?", ("255.255.255.255", FIND_PORT))
        end = time.time() + 1.5
        while time.time() < end:
            d, addr = s.recvfrom(512)
            v = json.loads(d).get("pcv", 0)
            if v > PC_VERSION:
                return addr[0], v
    except (OSError, ValueError):
        pass
    return None


def apply_update(host):
    import urllib.request
    import zipfile
    td = tempfile.mkdtemp(prefix="podmic_up")
    zp = os.path.join(td, "new.zip")
    urllib.request.urlretrieve(f"http://{host}:{PORT}/pczip", zp)
    with zipfile.ZipFile(zp) as z:
        z.extractall(td)
    src = next(os.path.join(td, d) for d in os.listdir(td) if os.path.isdir(os.path.join(td, d)))
    bat = os.path.join(td, "update.bat")
    with open(bat, "w", encoding="mbcs") as f:   # 等這個程式關掉 → 蓋上新檔（設定和錄音不動）→ 重開
        f.write(f'@echo off\r\ntimeout /t 2 >nul\r\nrobocopy "{src}" "{ROOT}" /E /XF config.json /XD "{SAVE}" >nul\r\n'
                f'start "" "{os.path.join(ROOT, os.path.basename(sys.executable))}"\r\n')
    subprocess.Popen(["cmd", "/c", bat], creationflags=0x08000008)   # 快字幕能不能用顯示卡（筆電通常不行 → 改用小模型，不然要等很久）


def ffmpeg_path():
    import glob
    for c in [os.path.join(ROOT, "ffmpeg", "ffmpeg.exe"), shutil.which("ffmpeg")] + glob.glob(os.path.expandvars(r"%LOCALAPPDATA%\QuickSub\**\ffmpeg.exe"), recursive=True):
        if c and os.path.exists(c):
            return c
    return None


def ensure_shortcut():
    """打包版：桌面放一個「錄音台」捷徑（zip 解壓縮到哪都可以，之後點桌面的就好）。指到這裡的已經建過就不動。"""
    if not FROZEN or cfg.get("lnk") == sys.executable:
        return
    import base64
    q = lambda v: v.replace("'", "''")
    ps = ("$s=(New-Object -ComObject WScript.Shell).CreateShortcut([Environment]::GetFolderPath('Desktop')+'\\錄音台.lnk');"
          f"$s.TargetPath='{q(sys.executable)}';$s.WorkingDirectory='{q(ROOT)}';$s.IconLocation='{q(sys.executable)},0';$s.Save()")
    try:
        r = subprocess.run(["powershell", "-NoProfile", "-EncodedCommand", base64.b64encode(ps.encode("utf-16-le")).decode()],
                           capture_output=True, timeout=30, creationflags=0x08000000)
        if r.returncode == 0:
            cfg["lnk"] = sys.executable
            save_cfg()
    except Exception:
        pass


def keep_awake(rec, screen):
    """錄音中不讓筆電自己睡著；提詞在捲的時候也不讓螢幕關掉。"""
    try:
        ctypes.windll.kernel32.SetThreadExecutionState(0x80000000 | (0x1 if rec or screen else 0) | (0x2 if screen else 0))
    except Exception:
        pass


def local_ip():
    """這台電腦在區域網路的位址（手機找不到時讓使用者手動輸入）。不會真的送出封包。"""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ip = s.getsockname()[0]
        s.close()
        return "" if ip.startswith("127.") else ip
    except OSError:
        return ""


# ---- USB 線連（手機開「USB 網路共用」）：舊版手機也能用，手機那邊不用改 ----
# 手機找電腦時會對每張網卡廣播，USB 共用的網卡也是一張 → 電腦從 USB 那張先回 → 手機走線。
# 從 USB 進來的手機不用按「允許」（只有插著線的人做得到）。網卡名稱照口袋快傳：USB 轉乙太網路不算。
USB_ADAPTER = re.compile(r"Remote NDIS|RNDIS|UsbNcm|NCM Host|Android", re.I)
_net = {"ips": None, "usb": []}
_net_lock = threading.Lock()


def local_addrs():
    try:
        return {ai[4][0] for ai in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)}
    except OSError:
        return set()


def refresh_nets():
    """網卡有變（剛開 USB 網路共用）才查哪些是 USB 共用：要開 PowerShell，文書筆電要 1～3 秒，所以平常只比對位址清單。
    同時只會有一個在查，其他的等它查完再回答。"""
    import ipaddress
    with _net_lock:
        ips = local_addrs()
        if ips == _net["ips"]:
            return
        usb = []
        cmd = ("[Console]::OutputEncoding=[Text.Encoding]::UTF8; Get-CimInstance Win32_NetworkAdapterConfiguration "
               "-Filter 'IPEnabled=true' | Select-Object Description,IPAddress,IPSubnet | ConvertTo-Json -Compress")
        try:
            out = subprocess.run(["powershell", "-NoProfile", "-Command", cmd], capture_output=True, timeout=15, creationflags=0x08000000).stdout
            data = json.loads(out.decode("utf-8", "replace").strip() or "[]")
            for a in data if isinstance(data, list) else [data]:
                if not USB_ADAPTER.search(a.get("Description") or ""):
                    continue
                ad, ms = a.get("IPAddress") or [], a.get("IPSubnet") or []
                for ip, mask in zip([ad] if isinstance(ad, str) else ad, [ms] if isinstance(ms, str) else ms):
                    if "." in ip and "." in mask:
                        usb.append(ipaddress.ip_network(f"{ip}/{mask}", strict=False))
        except Exception:
            pass
        _net.update(ips=ips, usb=usb)


def net_watch():
    while True:
        refresh_nets()
        time.sleep(3)


def usb_networks():
    return _net["usb"]


def on_usb(ip, wait=False):
    """這個連線是不是從 USB 線進來的。wait：網卡剛變、還沒查完就先查完再回答（只有陌生手機打招呼時用）。"""
    import ipaddress
    try:
        a = ipaddress.ip_address(ip)
    except ValueError:
        return False
    if wait:
        refresh_nets()
    return any(a in n for n in _net["usb"])


def firewall_blocked():
    """Windows 防火牆有沒有擋這個程式（新解壓縮的資料夾 = 新程式；第一次跳出的詢問被取消，Windows 會自動建「封鎖」規則）。
    也算「只放行私人網路」：USB 網路共用通常被 Windows 當成公用網路，線連會被擋。查不到就當作沒問題。"""
    exe = sys.executable.replace("'", "''")
    cmd = (f"$r = Get-NetFirewallApplicationFilter -Program '{exe}' -ErrorAction SilentlyContinue | Get-NetFirewallRule | "
           "Where-Object { $_.Direction -eq 'Inbound' -and $_.Enabled -eq 'True' }; "
           "if (-not $r) { 'none' } elseif ($r | Where-Object { $_.Action -eq 'Block' }) { 'block' } "
           "elseif (-not ($r | Where-Object { [string]$_.Profile -match 'Public|Any' })) { 'none' } else { 'ok' }")
    try:
        out = subprocess.run(["powershell", "-NoProfile", "-Command", cmd], capture_output=True, timeout=40,
                             creationflags=0x08000000).stdout.decode("utf-8", "replace").strip()
    except Exception:
        return False
    return out.endswith("none") or out.endswith("block")
APK = os.path.join(ROOT, "PodMic.apk")
VER = os.path.join(ROOT, "PodMic.version")
PORT, FIND_PORT = 47870, 47871
LOWPRI = 0x08000000 | 0x00004000   # 不開黑色視窗＋低於正常的優先權（文書筆電後製時畫面還點得動）
FONT = "Microsoft JhengHei UI"
GOLD, RED, GREEN, DIM, LINE = "#B8913F", "#D9383D", "#2E9E63", "#7A7A7A", "#E4E2DC"

cfg = {"phones": {}, "title": "", "script": "", "rev": 0, "speed": 4.0, "font": 44, "denoise": False, "level": True, "mp3": True}
try:
    with open(CFG, encoding="utf-8") as f:
        cfg.update(json.load(f))
except (OSError, ValueError):
    pass
lock = threading.Lock()
state = {"phones": {}, "pending": {}, "cmds": {}, "log": [], "files": 0}
prompt = {"seq": 0, "pos": 0.0, "run": False, "speed": float(cfg["speed"])}


def save_cfg():
    try:
        with open(CFG, "w", encoding="utf-8") as f:
            json.dump(cfg, f, ensure_ascii=False, indent=1)
    except OSError:
        pass


def apk_code():
    try:
        with open(VER) as f:
            return int(f.read().strip()) if os.path.exists(APK) else 0
    except (OSError, ValueError):
        return 0


def log(s):
    with lock:
        state["log"].append(time.strftime("%H:%M ") + s)
        del state["log"][:-5]


def send(cmd):
    """把指令排給所有連著的手機（下一次同步時帶走，最慢 0.2 秒）。"""
    with lock:
        for pid in state["phones"]:
            state["cmds"].setdefault(pid, []).append(cmd)


def touch(**kw):
    with lock:
        prompt.update(kw)
        prompt["seq"] += 1


# ---------------- 跟手機連線 ----------------

class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def reply(self, obj, code=200):
        b = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if urlparse(self.path).path == "/pczip" and served_pc():
            self.send_response(200)
            self.send_header("Content-Type", "application/zip")
            self.send_header("Content-Length", str(os.path.getsize(PCZIP)))
            self.end_headers()
            with open(PCZIP, "rb") as f:
                shutil.copyfileobj(f, self.wfile)
            return
        if urlparse(self.path).path == "/apk" and self.headers.get("X-Id", "") in cfg["phones"] and os.path.exists(APK):
            with open(APK, "rb") as f:
                b = f.read()
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(len(b)))
            self.end_headers()
            self.wfile.write(b)
            log("手機下載了新版 App")
        else:
            self.reply({"ok": False}, 404)

    def do_POST(self):
        u = urlparse(self.path)
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        pid = self.headers.get("X-Id", "")
        if u.path == "/hello":
            j = json.loads(body)
            pid = j.get("id", "")
            if pid not in cfg["phones"] and pid and on_usb(self.client_address[0], True):   # 插著線 = 本人：不用按允許
                cfg["phones"][pid] = j.get("name", "手機")
                save_cfg()
                log("用 USB 線連上 " + cfg["phones"][pid])
            if pid in cfg["phones"]:
                return self.reply({"ok": True, "name": socket.gethostname()})
            with lock:
                state["pending"][pid] = j.get("name", "手機")
            return self.reply({"ok": False, "wait": True, "name": socket.gethostname()})
        if pid not in cfg["phones"]:
            return self.reply({"ok": False}, 403)
        if u.path == "/sync":
            j = json.loads(body)
            ip = self.client_address[0]
            usb = on_usb(ip)
            if not usb and nudge_to_usb(pid, j):   # 503（不是 403：403 舊版手機只會重新打招呼，不會重找電腦）
                return self.reply({"ok": False}, 503)
            out = {"rev": cfg["rev"], "apk": apk_code()}
            with lock:
                state["phones"][pid] = {"name": cfg["phones"][pid], "seen": time.time(), "s": j, "usb": usb}
                out["cmds"] = state["cmds"].pop(pid, [])
                s = j.get("script")
                if s and s.get("rev", 0) > cfg["rev"]:       # 手機上改過稿子 → 收下來
                    cfg.update(rev=s["rev"], title=s.get("title", ""), script=s.get("text", ""))
                    state["script_in"] = True
                elif cfg["rev"] > j.get("rev", 0):
                    out["script"] = {"rev": cfg["rev"], "title": cfg["title"], "text": cfg["script"]}
                p = j.get("p") or {}
                if p.get("seq", 0) > prompt["seq"]:
                    prompt.update(seq=p["seq"], pos=float(p.get("pos", 0)), run=bool(p.get("run")), speed=float(p.get("speed", 4)))
                elif prompt["seq"] > p.get("seq", 0):
                    out["p"] = dict(prompt)
            return self.reply(out)
        if u.path == "/upload":
            q = parse_qs(u.query)
            name = os.path.basename(q["name"][0])
            off, total = int(q["off"][0]), int(q["total"][0])
            os.makedirs(SAVE, exist_ok=True)
            with open(os.path.join(SAVE, name), "wb" if off == 0 else "r+b") as f:
                f.seek(off)
                f.write(body)
            if off + len(body) >= total and name.lower().endswith(".wav"):
                log("收到 " + name)
                state["files"] += 1
            return self.reply({"ok": True})
        self.reply({"ok": False}, 404)


def nudge_to_usb(pid, j):
    """手機從 Wi-Fi 連著、但電腦上有 USB 網路共用：讓手機重新找一次電腦（同步失敗 3 次 → 舊版手機也會重找），
    這次 USB 的回覆先到 → 改走線。錄音中、傳檔中不動；同一次插線只試一次，USB 不通就留在 Wi-Fi。"""
    if j.get("rec") or (j.get("up") or {}).get("name"):
        return False
    nets = tuple(str(n) for n in usb_networks())
    if not nets:
        return False
    with lock:
        nd = state.setdefault("nudge", {})
        if nd.get("_nets") != nets:
            nd.clear()
            nd["_nets"] = nets
        t0 = nd.setdefault(pid, time.time())
    return time.time() - t0 < 6


def finder():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("", FIND_PORT))
    while True:
        try:
            d, addr = s.recvfrom(64)
            if d.startswith(b"PODMIC?"):
                msg = json.dumps({"podmic": 1, "name": socket.gethostname(), "port": PORT, "pcv": served_pc()}).encode()
                if usb_networks() and not on_usb(addr[0]):   # 手機同時插著線：Wi-Fi 的回覆晚一點，手機會先收到 USB 的
                    threading.Timer(0.35, lambda m=msg, a=addr: s.sendto(m, a)).start()
                else:
                    s.sendto(msg, addr)
        except OSError:
            pass


# ---------------- 後製 ----------------

def wav_info(path):
    with open(path, "rb") as f:
        f.seek(12)
        info = {}
        while True:
            h = f.read(8)
            if len(h) < 8:
                raise ValueError("讀不到聲音資料")
            cid, sz = h[:4], struct.unpack("<I", h[4:])[0]
            if cid == b"fmt ":
                fmt = f.read(sz + (sz & 1))
                tag, ch, rate, _, _, bits = struct.unpack("<HHIIHH", fmt[:16])
                if tag == 0xFFFE and len(fmt) >= 26:   # 擴充格式（很多軟體存的 WAV）：真正的格式寫在後面
                    tag = struct.unpack("<H", fmt[24:26])[0]
                info.update(flt=tag == 3, ch=ch, rate=rate, bits=bits)
            elif cid == b"data":
                left = os.path.getsize(path) - f.tell()
                sz = left if sz == 0 or sz > left else sz
                info.update(off=f.tell(), frames=sz // (info["ch"] * info["bits"] // 8))
                return info
            else:
                f.seek(sz + (sz & 1), 1)


def read_frames(f, info, n):
    """從檔案目前的位置讀 n 個樣本 → float32 陣列（樣本數, 聲道數）。檔尾不夠就回傳比較少。"""
    import numpy as np
    bps, ch = info["bits"] // 8, info["ch"]
    raw = f.read(n * ch * bps)
    raw = raw[:len(raw) // (ch * bps) * ch * bps]
    if info["flt"]:
        x = np.frombuffer(raw, "<f8" if bps == 8 else "<f4").astype(np.float32)
    elif bps == 3:
        b = np.frombuffer(raw, np.uint8).reshape(-1, 3).astype(np.int32)
        v = b[:, 0] | (b[:, 1] << 8) | (b[:, 2] << 16)
        x = ((v ^ 0x800000) - 0x800000).astype(np.float32) / 8388608
    elif bps == 4:
        x = np.frombuffer(raw, "<i4").astype(np.float32) / 2147483648
    elif bps == 1:
        x = (np.frombuffer(raw, np.uint8).astype(np.float32) - 128) / 128
    else:
        x = np.frombuffer(raw, "<i2").astype(np.float32) / 32768
    return x.reshape(-1, ch)


def load_meta(path):
    try:
        with open(os.path.splitext(path)[0] + ".json", encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_meta(path, meta):
    p = os.path.splitext(path)[0] + ".json"
    with open(p + ".tmp", "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False)
    os.replace(p + ".tmp", p)


def merge_ranges(rs, end=None):
    """整理要刪的時間段：排好、重疊的併在一起、超過檔尾的切掉。"""
    out = []
    for a, b in sorted((max(0.0, float(a)), float(b)) for a, b in rs):
        if end is not None:
            b = min(b, end)
        if b - a < 0.005:
            continue
        if out and a <= out[-1][1]:
            out[-1][1] = max(out[-1][1], b)
        else:
            out.append([a, b])
    return out


def peek_frames(f, info, n):
    """畫波形用的快速讀法：16、24-bit 只拿最高的兩個位元組（int16，不轉成浮點數），文書筆電上快好幾倍。回傳 (陣列, 滿格值)。"""
    import numpy as np
    bps, ch = info["bits"] // 8, info["ch"]
    if info["flt"] or bps not in (2, 3):
        return read_frames(f, info, n), 1.0
    raw = f.read(n * ch * bps)
    raw = raw[:len(raw) // (ch * bps) * ch * bps]
    if bps == 2:
        return np.frombuffer(raw, "<i2").reshape(-1, ch), 32768.0
    b = np.frombuffer(raw, np.uint8).reshape(-1, 3)[:, 1:]
    return np.ascontiguousarray(b).view("<i2").reshape(-1, ch), 32768.0


def envelope(path, prog=None):
    """波形：每 10 毫秒最大的音量（剪輯畫面用）。算過的存在暫存資料夾，下次直接拿。"""
    import hashlib
    import numpy as np
    st = os.stat(path)
    key = f"{os.path.abspath(path)}|{st.st_size}|{int(st.st_mtime)}"
    cache = os.path.join(tempfile.gettempdir(), "podmic_env", hashlib.md5(key.encode()).hexdigest() + ".npy")
    try:
        return np.load(cache)
    except (OSError, ValueError):
        pass
    info = wav_info(path)
    hop = max(1, info["rate"] // ENV_HZ)
    out, done = [], 0
    with open(path, "rb") as f:
        f.seek(info["off"])
        while done < info["frames"]:
            x, sc = peek_frames(f, info, min(info["frames"] - done, hop * 1000))
            if not len(x):
                break
            m = len(x) // hop * hop
            if m:
                v = x[:m].reshape(-1, hop * x.shape[1])
                out.append(np.maximum(v.max(1).astype(np.float32), -v.min(1).astype(np.float32)) / sc)
            if len(x) > m:
                out.append(np.array([max(float(x[m:].max()), -float(x[m:].min()))], np.float32) / sc)
            done += len(x)
            if prog:
                prog(done / max(1, info["frames"]))
    e = (np.concatenate(out) if out else np.zeros(1)).astype(np.float32)
    try:
        os.makedirs(os.path.dirname(cache), exist_ok=True)
        np.save(cache, e)
    except OSError:
        pass
    return e


ENV_HZ = 100


def repair_safety(path, info, out):
    """安全音軌模式：第 1 軌破音的地方，換成第 2 軌（小 6 dB 的備份）放大兩倍。回傳修了幾秒。"""
    import numpy as np
    rate = info["rate"]
    fixed = 0
    with open(path, "rb") as f, open(out, "wb") as o:
        f.seek(info["off"])
        o.write(b"RIFF" + struct.pack("<I", 36 + info["frames"] * 4) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 3, 1, rate, rate * 4, 4, 32)
                + b"data" + struct.pack("<I", info["frames"] * 4))
        left = info["frames"]
        while left > 0:
            x = read_frames(f, info, min(left, rate * 10))
            if not len(x):
                break
            left -= len(x)
            a, b2 = x[:, 0], x[:, 1] * 2
            w = np.clip(np.convolve((np.abs(a) >= 0.985).astype(np.float32), np.ones(192, np.float32), "same"), 0, 1)
            w = np.convolve(w, np.ones(96, np.float32) / 96, "same")
            fixed += float(w.sum()) / rate
            o.write((a * (1 - w) + b2 * w).astype("<f4").tobytes())
    return fixed


def loudness(ff, path, chan, nch):
    """量其中一軌的整體響度（LUFS）。"""
    af = (f"pan=mono|c0=c{chan}," if nch > 1 else "") + "ebur128"
    r = subprocess.run([ff, "-hide_banner", "-nostats", "-i", path, "-af", af, "-f", "null", "-"], capture_output=True,
                       creationflags=LOWPRI)
    val = None
    for line in r.stderr.decode("utf-8", "replace").splitlines():
        if line.strip().startswith("I:"):
            val = float(line.split()[1])
    return val


def run_ff(args):
    r = subprocess.run(args, capture_output=True, creationflags=LOWPRI)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.decode("utf-8", "replace")[-400:])
    return r


def cut_silence(src, dst, marks):
    """把超過 1.5 秒的空白縮成 0.7 秒；標記的時間跟著往前移。回傳剪掉幾秒。"""
    import numpy as np
    info = wav_info(src)
    ch, rate = info["ch"], info["rate"]
    x = np.memmap(src, "<i2", "r", info["off"], (info["frames"], ch))
    blk = rate // 50
    nb = info["frames"] // blk
    quiet = np.empty(nb, bool)
    for i in range(0, nb, 5000):   # 分段算，長檔也不吃記憶體
        seg = np.abs(x[i * blk:min(nb, i + 5000) * blk].astype(np.int32)).max(1).reshape(-1, blk)
        quiet[i:i + len(seg)] = seg.max(1) < 32768 * 10 ** (-42 / 20)
    keep, start, i, pad, removed = [], 0, 0, int(0.35 * 50), []
    while i < nb:
        if quiet[i]:
            j = i
            while j < nb and quiet[j]:
                j += 1
            if j - i > 75 and i > 0 and j < nb:
                keep.append((start, (i + pad) * blk))
                removed.append(((i + pad) * blk / rate, (j - pad) * blk / rate))
                start = (j - pad) * blk
            i = j
        else:
            i += 1
    keep.append((start, info["frames"]))
    total = sum(b - a for a, b in keep)
    fade = np.linspace(0, 1, 220, dtype=np.float32)[:, None]
    with open(dst, "wb") as o:
        o.write(b"RIFF" + struct.pack("<I", 36 + total * ch * 2) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, ch, rate, rate * ch * 2, ch * 2, 16)
                + b"data" + struct.pack("<I", total * ch * 2))
        for k, (a, b) in enumerate(keep):
            seg = np.array(x[a:b], np.float32)
            if k > 0 and len(seg) > 440:
                seg[:220] *= fade
            if k < len(keep) - 1 and len(seg) > 440:
                seg[-220:] *= fade[::-1]
            o.write(seg.astype("<i2").tobytes())
    del x
    for m in marks:
        m["t"] = max(0.0, m["t"] - sum(min(m["t"], b) - a for a, b in removed if a < m["t"]))
    return (info["frames"] - total) / rate


FILLERS = ("就是說", "然後呢", "那個", "就是", "嗯", "呃", "啊", "欸", "誒", "ㄜ", "額", "唉", "喔", "哦", "齁", "痾")


def is_filler(text):
    """整句只有語助詞（嗯、呃、那個…）才算贅字；夾在句子裡的不動，免得剪壞。"""
    import re
    t = re.sub(r"[\s，。、！？,.!?…~～—-]", "", text)
    if not t or len(t) > 6:
        return False
    for f in FILLERS:
        t = t.replace(f, "")
    return t == ""


def cut_ranges(src, dst, ranges, marks, segs):
    """把幾段時間（秒）挖掉，接縫做很短的淡入淡出；標記和字幕的時間跟著往前移。"""
    import numpy as np
    info = wav_info(src)
    ch, rate = info["ch"], info["rate"]
    ranges = merge_ranges(ranges, info["frames"] / rate)   # 重疊的先併起來，不然時間會被扣兩次
    x = np.memmap(src, "<i2", "r", info["off"], (info["frames"], ch))
    keep, at = [], 0
    for a, b in ranges:
        a, b = int(a * rate), min(info["frames"], int(b * rate))
        if a > at:
            keep.append((at, a))
        at = max(at, b)
    keep.append((at, info["frames"]))
    total = sum(b - a for a, b in keep)
    n = rate // 120
    fade = np.linspace(0, 1, n, dtype=np.float32)[:, None]
    with open(dst, "wb") as o:
        o.write(b"RIFF" + struct.pack("<I", 36 + total * ch * 2) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, ch, rate, rate * ch * 2, ch * 2, 16)
                + b"data" + struct.pack("<I", total * ch * 2))
        for k, (a, b) in enumerate(keep):
            seg = np.array(x[a:b], np.float32)
            if k > 0 and len(seg) > 2 * n:
                seg[:n] *= fade
            if k < len(keep) - 1 and len(seg) > 2 * n:
                seg[-n:] *= fade[::-1]
            o.write(seg.astype("<i2").tobytes())
    del x
    shift = lambda t: max(0.0, t - sum(min(t, b) - a for a, b in ranges if a < t))
    for m in marks:
        m["t"] = shift(m["t"])
    for g in segs:
        g["start"], g["end"] = shift(g["start"]), shift(g["end"])


def srt_time(t):
    return f"{int(t) // 3600:02d}:{int(t) // 60 % 60:02d}:{int(t) % 60:02d},{int(t * 1000) % 1000:03d}"


def find_quicksub():
    for p in (os.environ.get("QUICKSUB_CLI", ""), os.path.expandvars(r"%LOCALAPPDATA%\Programs\QuickSub\quicksub-cli.exe"),
              os.path.expanduser(r"~\Downloads\quicksub\dist\QuickSub\quicksub-cli.exe")):
        if p and os.path.exists(p):
            return p
    return None


def ask_ai(text):
    """電腦上有開 Ollama 就請它寫節目說明；沒有就跳過。"""
    import urllib.request
    try:
        tags = json.loads(urllib.request.urlopen("http://127.0.0.1:11434/api/tags", timeout=2).read())
        model = max(tags["models"], key=lambda m: m.get("size", 0))["name"]
        body = {"model": model, "stream": False, "think": False, "prompt":
                "以下是一集 podcast 的逐字稿。請用繁體中文寫：1. 三個吸引人的標題 2. 80 字以內的節目介紹 3. 五個重點（條列）。只輸出這三項。\n\n" + text[:12000]}
        rq = urllib.request.Request("http://127.0.0.1:11434/api/generate", json.dumps(body).encode(), {"Content-Type": "application/json"})
        out = json.loads(urllib.request.urlopen(rq, timeout=300).read()).get("response", "")
        return out.split("</think>")[-1].strip()
    except Exception:
        return ""


def make_cover(title, out):
    from PIL import Image, ImageDraw, ImageFont
    n = 3000
    im = Image.new("RGB", (n, n), "#141416")
    d = ImageDraw.Draw(im)
    for r, c in ((1150, "#1D1D21"), (900, "#26262B")):
        d.ellipse((n / 2 - r, 1150 - r, n / 2 + r, 1150 + r), fill=c)
    g = "#D0AC62"   # 麥克風
    d.rounded_rectangle((1290, 560, 1710, 1300), 210, fill=g)
    d.arc((1120, 800, 1880, 1520), 0, 180, fill=g, width=70)
    d.rectangle((1465, 1520, 1535, 1720), fill=g)
    d.rounded_rectangle((1250, 1700, 1750, 1770), 35, fill=g)
    try:
        font = lambda s: ImageFont.truetype(r"C:\Windows\Fonts\msjhbd.ttc", s)
        size = 330
        while size > 120 and d.textlength(title, font=font(size)) > n - 400:
            size -= 20
        d.text((n / 2, 2330), title, font=font(size), fill="#F3F1EB", anchor="mm")
    except OSError:
        pass
    im.save(out, quality=88)


def postprocess(path, opt, say):
    denoise, level, mp3 = opt["denoise"], opt["level"], opt["mp3"]
    pre = ["-t", "45"] if opt.get("preview") else []   # 試聽：只處理開頭 45 秒
    ff = ffmpeg_path()
    if not ff:
        raise RuntimeError("這台電腦找不到 ffmpeg，沒辦法後製")
    info = wav_info(path)
    base = os.path.splitext(os.path.basename(path))[0]
    meta = load_meta(path)
    os.makedirs(DONE, exist_ok=True)
    notes, tmp = [], None
    src, nch = path, info["ch"]
    if meta.get("mode") == 3 and nch == 2:
        say("修破音…")
        tmp = os.path.join(tempfile.gettempdir(), "podmic_fix.wav")
        sec = repair_safety(path, info, tmp)
        notes.append(f"安全音軌：補回 {sec:.1f} 秒破音" if sec > 0.01 else "安全音軌：沒有破音")
        src, nch = tmp, 1
    per = "highpass=f=80," + ("afftdn=nf=-25," if denoise else "") + "acompressor=threshold=-21dB:ratio=3:attack=10:release=200"
    if nch == 2:
        gains = [0.0, 0.0]
        if level:
            say("量兩個人的音量…")
            lu = [loudness(ff, src, 0, 2), loudness(ff, src, 1, 2)]
            ok = [v is not None and v > -50 for v in lu]
            if all(ok):
                mid = (lu[0] + lu[1]) / 2
                gains = [max(-12, min(12, mid - v)) for v in lu]
                names = meta.get("names", ["第 1 軌", "第 2 軌"])
                notes.append(f"音量拉齊：{names[0]} {gains[0]:+.1f} dB、{names[1]} {gains[1]:+.1f} dB")
        fc = (f"[0:a]channelsplit=channel_layout=stereo[a][b];[a]volume={gains[0]}dB,{per}[a2];[b]volume={gains[1]}dB,{per}[b2];"
              "[a2][b2]join=inputs=2:channel_layout=stereo,pan=stereo|c0=0.68*c0+0.32*c1|c1=0.32*c0+0.68*c1,"
              "loudnorm=I=-16:TP=-1.5:LRA=11[o]")
    else:
        fc = f"[0:a]{per},loudnorm=I=-16:TP=-1.5:LRA=11[o]"
    marks = [dict(m) for m in meta.get("markers", []) if m.get("label") not in ("暫停",)]
    td = tempfile.gettempdir()
    mid, mid2, metaf = (os.path.join(td, n) for n in ("podmic_mid.wav", "podmic_mid2.wav", "podmic_meta.txt"))
    say("處理聲音…")
    run_ff([ff, "-hide_banner", "-y", *pre, "-i", src, "-filter_complex", fc, "-map", "[o]", "-ar", "44100", "-c:a", "pcm_s16le", mid])
    if tmp and os.path.exists(tmp):
        os.remove(tmp)
    cuts = meta.get("cuts") or []
    if cuts:   # 剪輯畫面刪掉的段落（時間是原始檔的；上一步沒改變時間，直接剪）
        say("剪掉你刪的段落…")
        cut_ranges(mid, mid2, cuts, marks, [])
        os.replace(mid2, mid)
        notes.append(f"照你的剪輯刪掉 {len(cuts)} 段（{sum(b - a for a, b in cuts):.0f} 秒）")
    if opt.get("cut"):
        say("剪掉空白…")
        sec = cut_silence(mid, mid2, marks)
        os.replace(mid2, mid)
        notes.append(f"剪掉空白 {sec:.0f} 秒" if sec >= 1 else "沒有太長的空白")
    segs = []
    if opt.get("text") or opt.get("filler"):
        qs = find_quicksub()
        if qs:
            say("聽寫中…（第一次會比較久）")
            jp = os.path.join(td, "podmic_qs.json")
            subprocess.run([qs, "transcribe", mid, "--segments", "-f", "txt", "-o", td, "-l", "zh", "-q", "--max-chars", "12", "--json", jp,
                            "--hotwords", "嗯, 呃, 那個, 就是說"] + (["-m", "small", "--cpu"] if QS["gpu"] is False else []), capture_output=True, creationflags=LOWPRI)
            try:
                with open(jp, encoding="utf-8") as f:
                    segs = json.load(f)["results"][0]["segments"]
                os.remove(jp)
            except (OSError, ValueError, KeyError, IndexError):
                notes.append("聽寫失敗（快字幕沒有成功），逐字稿和贅字這兩項跳過")
        else:
            notes.append("找不到快字幕，逐字稿和贅字這兩項跳過")
    if opt.get("filler") and segs:
        rng = [(max(0.0, g["start"] - 0.03), g["end"] + 0.03) for g in segs if is_filler(g["text"])]
        segs = [g for g in segs if not is_filler(g["text"])]
        if rng:
            cut_ranges(mid, mid2, rng, marks, segs)
            os.replace(mid2, mid)
        notes.append(f"剪掉贅字 {len(rng)} 個" if rng else "沒有找到單獨的贅字")
    if opt.get("pick") and segs:
        say("等你選要刪的句子…")
        drop = set(opt["pick"](segs))
        if drop:
            cut_ranges(mid, mid2, [(segs[i]["start"], segs[i]["end"]) for i in drop], marks, segs)
            os.replace(mid2, mid)
            segs = [g for i, g in enumerate(segs) if i not in drop]
            notes.append(f"刪掉 {len(drop)} 句")
    mi = wav_info(mid)
    dur = mi["frames"] / mi["rate"]
    # 片頭：音樂先響 5 秒，人聲進來時慢慢退到底下；片尾：最後 4 秒音樂淡入，講完再留 12 秒
    intro = opt.get("intro") if opt.get("intro") and os.path.exists(opt["intro"]) else None
    outro = opt.get("outro") if opt.get("outro") and os.path.exists(opt["outro"]) else None
    lead = 5.0 if intro else 0.0
    ins, parts, mix = ["-i", mid], [], "[v]"
    st = "aresample=44100,aformat=channel_layouts=stereo"
    parts.append(f"[0:a]{st},adelay={int(lead * 1000)}:all=1[v]")
    k = 1
    if intro:
        ins += ["-i", intro]
        parts.append(f"[{k}:a]{st},atrim=0:14,afade=t=out:st=4:d=10,volume=0.55[i]")
        mix += "[i]"
        k += 1
    if outro:
        ins += ["-i", outro]
        at = int((lead + max(0, dur - 4)) * 1000)
        parts.append(f"[{k}:a]{st},atrim=0:16,afade=t=in:d=4,afade=t=out:st=11:d=5,volume=0.55,adelay={at}:all=1[u]")
        mix += "[u]"
        k += 1
    if intro or outro:
        parts.append(f"{mix}amix=inputs={k}:normalize=0:duration=longest,alimiter=limit=0.84[o]")
        notes.append("加了" + "、".join(n for n, v in (("片頭", intro), ("片尾", outro)) if v) + "音樂")
        nch = 2
    else:
        parts = ["[0:a]anull[o]"]
    for m in marks:
        m["t"] += lead
    total = dur + lead + (12 if outro else 0)
    with open(metaf, "w", encoding="utf-8") as f:
        f.write(";FFMETADATA1\ntitle=" + base + "\n")
        for i, m in enumerate(marks):
            end = marks[i + 1]["t"] if i + 1 < len(marks) else total
            f.write(f"[CHAPTER]\nTIMEBASE=1/1000\nSTART={int(m['t'] * 1000)}\nEND={int(end * 1000)}\ntitle={m['label']}\n")
    out = os.path.join(DONE, base + (".mp3" if mp3 else ".wav"))
    if opt.get("preview"):
        out = os.path.join(td, "錄音台試聽.mp3")
    codec = ["-c:a", "libmp3lame", "-b:a", "160k" if nch == 2 else "112k"] if mp3 else ["-c:a", "pcm_s24le"]
    say("轉檔中…")
    run_ff([ff, "-hide_banner", "-y", *ins, "-i", metaf, "-filter_complex", ";".join(parts), "-map", "[o]", "-map_metadata", str(k), *codec, out])
    os.remove(mid)
    text = ""
    if opt.get("text") and segs:
        text = "\n".join(g["text"] for g in segs)
        with open(os.path.join(DONE, base + ".txt"), "w", encoding="utf-8") as f:
            f.write(text + "\n")
        with open(os.path.join(DONE, base + ".srt"), "w", encoding="utf-8") as f:
            for i, g in enumerate(segs):
                f.write(f"{i + 1}\n{srt_time(g['start'] + lead)} --> {srt_time(g['end'] + lead)}\n{g['text']}\n\n")
        notes.append("逐字稿和字幕檔")
    title = opt.get("title") or re.sub(r"^\d{4}-\d{2}-\d{2}_\d{4,6}_?", "", base) or base   # 檔名開頭的日期不放進標題
    if opt.get("cover"):
        make_cover(title, os.path.join(DONE, base + "_封面.jpg"))
        with open(os.path.join(DONE, base + "_上架資料.txt"), "w", encoding="utf-8") as f:
            f.write("標題：" + title + f"\n長度：{int(total) // 60} 分 {int(total) % 60} 秒\n\n")
            if marks:
                f.write("章節\n" + "".join(f"{int(m['t']) // 60:02d}:{int(m['t']) % 60:02d} {m['label']}\n" for m in marks) + "\n")
            if text:
                say("請 AI 寫節目說明…")
                ai = ask_ai(text)
                f.write(("AI 建議的標題、介紹、重點\n" + ai + "\n\n") if ai else "")
                f.write("逐字稿開頭\n" + text[:600] + "\n")
        notes.append("封面和上架資料")
    if marks:
        with open(os.path.join(DONE, base + "_章節.txt"), "w", encoding="utf-8") as f:
            for m in marks:
                t = int(m["t"])
                f.write(f"{t // 3600:d}:{t // 60 % 60:02d}:{t % 60:02d} {m['label']}\n")
        notes.append(f"章節 {len(marks)} 個")
    notes.append("響度 −16 LUFS（podcast 標準）")
    return out, notes


def merge_guest(host, guest, offset, say):
    """遠端來賓：把來賓自己錄的檔案併成第 2 軌。offset=None → 自動對時間（主持人那軌要聽得到一點來賓的聲音才對得準）。"""
    import numpy as np
    ff = ffmpeg_path()
    if not ff:
        raise RuntimeError("找不到 ffmpeg")
    note = f"手動對齊 {offset:+.2f} 秒" if offset is not None else ""
    if offset is None:
        say("對時間…")
        env = []
        for p in (host, guest):
            raw = run_ff([ff, "-hide_banner", "-t", "900", "-i", p, "-af", "pan=mono|c0=c0,highpass=f=200", "-ar", "8000", "-f", "s16le", "-"]).stdout
            a = np.abs(np.frombuffer(raw, "<i2").astype(np.float32))
            a = a[:len(a) // 80 * 80].reshape(-1, 80).mean(1)   # 每 10 毫秒一格的音量
            env.append(a - a.mean())
        n = 1 << int(np.ceil(np.log2(len(env[0]) + len(env[1]))))
        c = np.fft.irfft(np.fft.rfft(env[0], n) * np.conj(np.fft.rfft(env[1], n)), n)
        k = int(np.argmax(c))
        conf = c[k] / (np.std(c) + 1e-9)
        offset = (k if k < n // 2 else k - n) / 100
        note = f"自動對齊 {offset:+.2f} 秒（把握度 {conf:.0f}，低於 8 請自己聽一下，不準就改用手動）"
    out = os.path.splitext(host)[0] + "_含來賓.wav"
    g = f"adelay={int(offset * 1000)}:all=1" if offset >= 0 else f"atrim=start={-offset},asetpts=PTS-STARTPTS"
    say("合併…")
    run_ff([ff, "-hide_banner", "-y", "-i", host, "-i", guest, "-filter_complex",
            f"[0:a]pan=mono|c0=c0[a];[1:a]aresample=48000,pan=mono|c0=c0,{g}[b];[a][b]amerge=inputs=2[o]", "-map", "[o]", "-ar", "48000", "-c:a", "pcm_s24le", out])
    try:
        with open(os.path.splitext(host)[0] + ".json", encoding="utf-8") as f:
            meta = json.load(f)
    except (OSError, ValueError):
        meta = {}
    meta.update(mode=4, channels=2)
    with open(os.path.splitext(out)[0] + ".json", "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False)
    return out, note


class LocalRec:
    """接收器直接插電腦時，用電腦錄（不需要手機）。對外的樣子跟一支手機一樣。"""

    def __init__(self):
        import queue
        self.q = queue.Queue()
        self.stream = None
        self.rec, self.frames, self.marks, self.file, self.err, self.dev = 0, 0, [], None, "", ""
        self.peak, self.sums, self.mode, self.clip, self.ch = [0.0, 0.0], [1e-12, 1e-12, 0.0], 0, 0.0, 2

    def start(self):
        import sounddevice as sd
        try:
            pick = None
            api = next((i for i, a in enumerate(sd.query_hostapis()) if "WASAPI" in a["name"]), None)
            for i, d in enumerate(sd.query_devices()):
                if d["max_input_channels"] > 0 and d["hostapi"] == api and any(k in d["name"].upper() for k in ("DJI", "WIRELESS MIC")):
                    pick = i
            info = sd.query_devices(pick if pick is not None else sd.default.device[0])
            self.dev = info["name"] + ("" if pick is not None else "（沒找到 DJI 接收器，先用預設麥克風）")
            self.ch = min(2, info["max_input_channels"])
            self.stream = sd.InputStream(samplerate=48000, channels=self.ch, dtype="float32", device=pick, callback=self.cb, blocksize=2400)
            self.stream.start()
            self.err = ""
        except Exception as e:
            self.err = "麥克風打不開：" + str(e)[:80]
            self.stream = None
        if not self.wt:
            self.wt = threading.Thread(target=self.writer, daemon=True)
            self.wt.start()

    def stop(self):
        if self.rec:
            self.cmd({"c": "stop"})
        if self.stream:
            self.stream.close()
            self.stream = None

    def cb(self, data, n, t, status):
        import numpy as np
        a = np.abs(data)
        for i in range(self.ch):
            self.peak[i] = max(self.peak[i], float(a[:, i].max()))
        if self.ch == 2:
            l, r = data[:, 0], data[:, 1]
            s = self.sums
            s[0], s[1], s[2] = s[0] * 0.95 + float(l @ l), s[1] * 0.95 + float(r @ r), s[2] * 0.95 + float(l @ r)
            if s[1] < s[0] * 1e-4 or s[0] < s[1] * 1e-4:
                self.mode = 1
            else:
                corr, ratio = s[2] / (s[0] * s[1]) ** 0.5, 10 * __import__("math").log10(s[1] / s[0])
                self.mode = 3 if corr > 0.985 and abs(ratio + 6) < 1.2 else 2 if corr > 0.985 and abs(ratio) < 0.7 else 4
        else:
            self.mode = 1
        if float(a.max()) >= 0.999:
            self.clip = time.time()
        if self.rec == 1:
            self.q.put(data.copy())

    def writer(self):
        import numpy as np
        while True:
            d = self.q.get()
            if isinstance(d, threading.Event):   # 停止錄音：前面排著的都寫完了
                d.set()
                continue
            f = self.fh
            if d is None or f is None:
                continue
            if self.nch == 1:
                d = d[:, :1]
            v = np.clip(np.round(d * 8388608), -8388608, 8388607).astype(np.int32)
            f.write(np.stack([v & 255, (v >> 8) & 255, (v >> 16) & 255], -1).astype(np.uint8).tobytes())
            self.frames += len(d)

    fh, nch, wt = None, 2, None

    def cmd(self, c):
        k = c.get("c")
        if k == "start" and self.rec == 0 and self.stream:
            os.makedirs(SAVE, exist_ok=True)
            t = "".join(ch for ch in c.get("title", "") if ch not in '\\/:*?"<>|').strip()
            self.file = os.path.join(SAVE, time.strftime("%Y-%m-%d_%H%M") + ("_" + t if t else "") + ".wav")
            self.nch = 2 if self.ch == 2 and self.mode in (3, 4) else 1
            self.mode0 = self.mode if self.nch == 2 else 1   # 開始時的模式（錄到一半有人不講話，偵測會變，不能用結束時的）
            self.frames, self.marks, self.t0 = 0, [], time.time()
            f = open(self.file, "wb")
            f.write(b"\0" * 44)
            self.fh, self.rec = f, 1
        elif k == "pause" and self.rec:
            if self.rec == 1:
                self.marks.append({"t": self.frames / 48000, "label": "暫停"})
            self.rec = 3 - self.rec
        elif k == "mark" and self.rec:
            self.marks.append({"t": self.frames / 48000, "label": c.get("label") or f"標記 {len(self.marks) + 1}"})
        elif k == "stop" and self.rec:
            self.rec = 0
            done = threading.Event()   # 等排著的聲音都寫完再寫檔頭（以前只等 0.25 秒，可能寫到一半就改檔頭）
            self.q.put(done)
            done.wait(5)
            f, self.fh = self.fh, None
            n = self.frames * self.nch * 3
            f.seek(0)
            f.write(b"RIFF" + struct.pack("<I", 36 + n) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, self.nch, 48000, 48000 * self.nch * 3, self.nch * 3, 24)
                    + b"data" + struct.pack("<I", n))
            f.close()
            with open(os.path.splitext(self.file)[0] + ".json", "w", encoding="utf-8") as j:
                json.dump({"device": self.dev, "mode": self.mode0, "names": cfg.get("names", ["主持人", "來賓"]), "markers": self.marks}, j, ensure_ascii=False)
            log("錄好了 " + os.path.basename(self.file))
            state["files"] += 1

    def status(self):
        import math
        pk = [round(20 * math.log10(max(p, 1e-6)), 1) for p in self.peak]
        self.peak = [0.0, 0.0]
        free = shutil.disk_usage(ROOT).free // (48000 * 6)
        return {"name": "這台電腦", "seen": time.time(), "local": True,
                "s": {"rec": self.rec, "t": self.frames / 48000 if self.rec else 0, "peak": pk, "clip": time.time() - self.clip < 1.5, "dev": self.dev,
                      "mode": self.mode, "free": free, "marks": len(self.marks), "lost": False, "err": self.err,
                      "names": cfg.get("names", ["主持人", "來賓"]), "up": {}}}


# ---------------- 畫面 ----------------

class App:
    def __init__(self):
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(1)
        except Exception:
            pass
        self.w = w = tk.Tk()
        w.title("錄音台")
        w.configure(bg="#F4F2EC")
        self.S = w.winfo_fpixels("1i") / 96
        self.pw = None
        self.last_tick = time.perf_counter()
        self.files_seen = -1
        self.busy = False
        px = self.px
        st = ttk.Style()
        st.theme_use("clam")
        st.configure("TButton", font=(FONT, 10), padding=(px(10), px(7)), background="#F6F4EE", bordercolor=LINE, lightcolor="#F6F4EE",
                     darkcolor="#F6F4EE", focuscolor="#F6F4EE", relief="flat")
        st.map("TButton", background=[("pressed", "#E6E2D6"), ("active", "#EEEBE2")])
        st.configure("Go.TButton", font=(FONT, 11, "bold"), padding=(px(14), px(10)), background="#C39A45", foreground="white", bordercolor="#C39A45",
                     lightcolor="#C39A45", darkcolor="#C39A45", focuscolor="#C39A45")
        st.map("Go.TButton", background=[("disabled", "#DDD8CB"), ("pressed", "#A47F32"), ("active", "#B48C3A")])
        st.configure("TCheckbutton", font=(FONT, 10), background="white", focuscolor="white", indicatorsize=px(15), padding=(0, px(2)))
        st.map("TCheckbutton", background=[("active", "white")], indicatorcolor=[("selected", "#C39A45")])

        self.local, self.did_rec, self.did_post = None, False, False
        bar = tk.Frame(w, bg="white", highlightbackground=LINE, highlightthickness=1, padx=px(16), pady=px(12))
        bar.pack(fill="x", padx=px(16), pady=(px(14), 0))
        steps = tk.Frame(bar, bg="white")
        steps.pack(anchor="w")
        self.chips = []
        for i, name in enumerate(("接麥克風", "準備", "錄音", "收檔", "後製", "完成")):
            if i:
                tk.Label(steps, text="›", font=(FONT, 12), bg="white", fg="#C8C4B8").pack(side="left", padx=px(6))
            c = tk.Label(steps, text=f" {i + 1}  {name} ", font=(FONT, 10, "bold"), bg="#F1EEE6", fg="#9A968B", padx=px(8), pady=px(4))
            c.pack(side="left")
            self.chips.append(c)
        self.guide = tk.Label(bar, text="", font=(FONT, 11), bg="white", fg="#3A3528", anchor="w", justify="left", wraplength=px(960))
        self.guide.pack(fill="x", pady=(px(10), 0))
        self.upd, self.upd_btn, self.post_msg, self.step_t = None, None, "", 0
        self.steps_row = steps
        body = tk.Frame(w, bg="#F4F2EC")
        body.pack(fill="both", expand=True, padx=px(16), pady=px(12))
        left, mid, right = self.card(body, 0, 262), self.card(body, 1, 380), self.card(body, 2, 322)
        body.columnconfigure(1, weight=1)
        body.rowconfigure(0, weight=1)

        # 左：手機、錄音
        self.h1(left, "手機")
        self.phone = tk.Label(left, text="", font=(FONT, 10), bg="white", fg=DIM, justify="left", anchor="w", wraplength=px(232))
        self.phone.pack(fill="x")
        self.ip, self.fw_bad = "", False
        self.fw_btn = ttk.Button(left, text="手機找不到電腦？按這裡讓防火牆放行", command=self.fix_firewall)   # 有需要才出現
        self.v_local = tk.BooleanVar(value=False)
        ttk.Checkbutton(left, text="用這台電腦錄（接收器插電腦）", variable=self.v_local, command=self.toggle_local).pack(anchor="w", pady=(px(6), 0))
        self.timer = tk.Label(left, text="00:00", font=("Segoe UI Light", 30), bg="white")
        self.timer.pack(pady=(px(2), 0))
        self.recst = tk.Label(left, text="", font=(FONT, 10, "bold"), bg="white", fg=DIM)
        self.recst.pack()
        self.meters = []
        for i in range(2):
            lab = tk.Label(left, text="", font=(FONT, 9), bg="white", fg="#333", anchor="w")
            lab.pack(fill="x", pady=(px(10), px(3)))
            cv = tk.Canvas(left, height=px(12), bg="#ECEAE4", highlightthickness=0)
            cv.pack(fill="x")
            self.meters.append((lab, cv, [-120.0]))
        self.warn = tk.Label(left, text="", font=(FONT, 9), bg="white", fg=RED, wraplength=px(232), justify="left", anchor="w")
        self.warn.pack(fill="x", pady=(px(8), 0))
        tk.Label(left, text="這一集的名稱", font=(FONT, 9), bg="white", fg=DIM, anchor="w").pack(fill="x", pady=(px(10), 0))
        self.ep = tk.Entry(left, font=(FONT, 11), relief="solid", bd=1)
        self.ep.pack(fill="x", ipady=px(3))
        self.rec_btn = ttk.Button(left, text="開始錄音", style="Go.TButton", command=self.rec)
        self.rec_btn.pack(fill="x", pady=(px(8), px(4)))
        row = tk.Frame(left, bg="white")
        row.pack(fill="x")
        ttk.Button(row, text="暫停／繼續", command=lambda: self.do({"c": "pause"})).pack(side="left", expand=True, fill="x", padx=(0, px(3)))
        ttk.Button(row, text="標記 (F8)", command=self.mark).pack(side="left", expand=True, fill="x", padx=(px(3), 0))
        self.logl = tk.Label(left, text="", font=(FONT, 9), bg="white", fg=DIM, justify="left", anchor="sw", wraplength=px(232))
        self.logl.pack(fill="both", expand=True, pady=(px(10), 0))

        # 中：提詞稿
        self.h1(mid, "提詞稿")
        self.title_e = tk.Entry(mid, font=(FONT, 11), relief="solid", bd=1)
        self.title_e.insert(0, cfg["title"])
        self.title_e.pack(fill="x", ipady=px(3))
        self.ed = tk.Text(mid, font=(FONT, 12), wrap="word", relief="solid", bd=1, undo=True, height=6, width=24, spacing3=px(4), padx=px(8), pady=px(6))
        self.ed.insert("1.0", cfg["script"])
        self.ed.pack(fill="both", expand=True, pady=(px(8), px(8)))
        self.ed.edit_modified(False)
        self.ed.bind("<<Modified>>", self.edited)
        self.title_e.bind("<KeyRelease>", lambda e: self.edited(None, True))
        crow = tk.Frame(mid, bg="white")   # 字數＋捲動速度同一行（以前速度跟按鈕擠在一起，窄的時候會疊在一起）
        crow.pack(fill="x")
        ttk.Button(crow, text="快", width=3, command=lambda: self.speed(0.25)).pack(side="right")
        self.speed_l = tk.Label(crow, text="", font=(FONT, 10), bg="white", width=8)
        self.speed_l.pack(side="right")
        ttk.Button(crow, text="慢", width=3, command=lambda: self.speed(-0.25)).pack(side="right")
        self.count = tk.Label(crow, text="", font=(FONT, 9), bg="white", fg=DIM, anchor="w")
        self.count.pack(side="left", fill="x", expand=True)
        row = tk.Frame(mid, bg="white")
        row.pack(fill="x", pady=(px(8), 0))
        ttk.Button(row, text="提詞視窗", style="Go.TButton", command=self.open_prompter).pack(side="left")
        self.play_btn = ttk.Button(row, text="開始捲", command=self.play)
        self.play_btn.pack(side="left", padx=px(6))
        ttk.Button(row, text="回開頭", command=lambda: touch(pos=0.0, run=False)).pack(side="left")

        # 右：錄音檔、後製
        self.h1(right, "錄音檔")
        # 下面的東西從底部往上排：視窗矮的時候縮的是清單，不會把按鈕擠掉
        self.post_l = tk.Label(right, text="", font=(FONT, 9), bg="white", fg=DIM, justify="left", anchor="w", wraplength=px(290))
        self.post_l.pack(side="bottom", fill="x", pady=(px(8), 0))
        row = tk.Frame(right, bg="white")
        row.pack(side="bottom", fill="x")
        ttk.Button(row, text="錄音資料夾", command=lambda: self.folder(SAVE)).pack(side="left", expand=True, fill="x", padx=(0, px(3)))
        ttk.Button(row, text="成品資料夾", command=lambda: self.folder(DONE)).pack(side="left", expand=True, fill="x", padx=px(3))
        self.bk_btn = ttk.Button(row, command=self.pick_backup)
        self.bk_btn.pack(side="left", expand=True, fill="x", padx=(px(3), 0))
        self.bk_btn.config(text="備份：開" if cfg.get("backup") else "備份…")
        self.batt, self.n = None, 0
        self.v_dn, self.v_lv, self.v_mp3, self.v_cut, self.v_txt, self.v_cov = (
            tk.BooleanVar(value=cfg.get(k, d)) for k, d in (("denoise", False), ("level", True), ("mp3", True), ("cut", True), ("text", True), ("cover", True)))
        self.v_fil = tk.BooleanVar(value=cfg.get("filler", True))
        self.music, self.optw = {}, None
        srow = tk.Frame(right, bg="white")
        srow.pack(side="bottom", fill="x", pady=(0, px(6)))
        self.opt_sum = tk.Label(srow, text="", font=(FONT, 9), bg="white", fg=DIM, justify="left", anchor="w", wraplength=px(200))
        self.opt_sum.pack(side="left", fill="x", expand=True)
        ttk.Button(srow, text="改選項…", command=self.options).pack(side="right")
        self.post_btn = ttk.Button(right, text="一鍵後製成 podcast", style="Go.TButton", command=self.post)
        self.post_btn.pack(side="bottom", fill="x", pady=(px(6), px(4)))
        row = tk.Frame(right, bg="white")
        row.pack(side="bottom", fill="x", pady=(px(6), 0))
        ttk.Button(row, text="✂ 剪輯（刪掉不要的段落）", command=self.open_editor).pack(side="left", expand=True, fill="x", padx=(0, px(3)))
        ttk.Button(row, text="加入檔案…", command=self.import_files).pack(side="left", padx=(px(3), 0))
        self.files = []
        self.lst = tk.Listbox(right, font=(FONT, 10), relief="solid", bd=1, activestyle="none", height=3, selectmode="extended", exportselection=False)
        self.lst.pack(fill="both", expand=True)
        self.lst.bind("<Double-Button-1>", lambda e: self.open_editor())
        self.show_opts()
        self.ed_win = None

        w.bind("<F8>", lambda e: self.mark())
        w.protocol("WM_DELETE_WINDOW", self.close)
        w.minsize(px(1100), px(640))
        self.awake = None
        self.t_start = time.time()
        threading.Thread(target=self.probe, daemon=True).start()
        self.tick()

    def probe(self):
        """開機時在背景看兩件事：接收器有沒有插在這台電腦、快字幕能不能用顯示卡。"""
        try:
            import sounddevice as sd
            if any(d["max_input_channels"] > 0 and "DJI" in d["name"].upper() for d in sd.query_devices()):
                self.w.after(0, lambda: (self.v_local.set(True), self.toggle_local()))
        except Exception:
            pass
        if FROZEN:
            ensure_shortcut()
            self.upd = find_update()
        self.ip = local_ip()
        qs = find_quicksub()
        if qs:
            try:
                key = f"{qs}|{int(os.path.getmtime(qs))}"
                if cfg.get("qs_info", {}).get("key") == key:
                    QS["gpu"] = cfg["qs_info"]["gpu"]
                else:
                    r = subprocess.run([qs, "info"], capture_output=True, creationflags=LOWPRI, timeout=90)
                    QS["gpu"] = bool(json.loads(r.stdout.decode("utf-8", "replace")).get("gpu"))
                    cfg["qs_info"] = {"key": key, "gpu": QS["gpu"]}
            except Exception:
                pass
            self.w.after(0, self.show_opts)   # 知道有沒有顯示卡了：選項摘要補上「聽寫比較久」
        time.sleep(max(0.0, 10 - (time.time() - self.t_start)))
        with lock:
            seen = any(time.time() - q["seen"] < 3 for q in state["phones"].values())
        if not seen and not self.local:
            self.fw_bad = firewall_blocked()

    def fix_firewall(self):
        """使用者按的：跳出 Windows 的管理員詢問 → 拿掉擋這個程式的規則，加一條放行（區域網路收手機的連線）。"""
        exe = sys.executable
        args = (f'/c netsh advfirewall firewall delete rule name=all program="{exe}" & '
                f'netsh advfirewall firewall add rule name="PodMic" dir=in action=allow program="{exe}" enable=yes profile=any')
        r = ctypes.windll.shell32.ShellExecuteW(None, "runas", "cmd.exe", args, None, 0)
        if r <= 32:
            messagebox.showinfo("錄音台", "沒有放行（Windows 的詢問被取消了）。要讓手機連上，需要按「是」。", parent=self.w)
            return
        self.fw_bad = False
        self.fw_btn.pack_forget()
        messagebox.showinfo("錄音台", "好了。手機幾秒內應該就會找到電腦（電腦會再問一次要不要允許這支手機）。\n"
                                     "還是找不到：確認手機和電腦連的是同一個 Wi-Fi。", parent=self.w)

    def px(self, v):
        return int(round(v * self.S))

    def card(self, parent, col, width):
        f = tk.Frame(parent, bg="white", padx=self.px(14), pady=self.px(12), highlightbackground=LINE, highlightthickness=1)
        f.grid(row=0, column=col, sticky="nsew", padx=(0 if col == 0 else self.px(12), 0))
        f.configure(width=self.px(width))
        if col != 1:
            f.pack_propagate(False)
        return f

    def h1(self, parent, text):
        tk.Label(parent, text=text, font=(FONT, 13, "bold"), bg="white", anchor="w").pack(fill="x", pady=(0, self.px(8)))

    def folder(self, p):
        os.makedirs(p, exist_ok=True)
        os.startfile(p)

    def close(self):
        keep_awake(False, False)
        cfg.update(denoise=self.v_dn.get(), level=self.v_lv.get(), mp3=self.v_mp3.get(), cut=self.v_cut.get(), text=self.v_txt.get(),
                   filler=self.v_fil.get(), cover=self.v_cov.get(), speed=prompt["speed"])
        if self.ed_win:
            self.ed_win.close()
        if self.local:
            self.local.stop()
        save_cfg()
        self.w.destroy()

    # ----- 錄音遙控 -----
    def first(self):
        with lock:
            live = [p for p in state["phones"].values() if time.time() - p["seen"] < 3]
        if live:
            return live[0]
        return self.local.status() if self.local else None

    def do(self, cmd):
        """指令送給手機；沒有手機、用電腦錄的時候就交給電腦自己。"""
        with lock:
            phone = any(time.time() - p["seen"] < 3 for p in state["phones"].values())
        if phone:
            send(cmd)
        elif self.local:
            threading.Thread(target=self.local.cmd, args=(cmd,), daemon=True).start()

    def toggle_local(self):
        if self.v_local.get():
            self.local = LocalRec()
            self.local.start()
        elif self.local:
            self.local.stop()
            self.local = None

    def rec(self):
        p = self.first()
        if not p:
            return
        if p["s"].get("rec", 0) == 0:
            self.do({"c": "start", "title": self.ep.get().strip()})
            self.did_rec, self.did_post = True, False
            if cfg["script"].strip() and self.pw:
                touch(run=True)
        else:
            self.do({"c": "stop"})

    def mark(self):
        self.do({"c": "mark", "label": ""})

    def pick_backup(self):
        from tkinter import filedialog
        if cfg.get("backup"):
            cfg["backup"] = ""
        else:
            cfg["backup"] = filedialog.askdirectory(parent=self.w, title="選一個資料夾放備份（建議選隨身碟或另一顆硬碟）") or ""
            if cfg["backup"]:
                threading.Thread(target=backup_new, args=(cfg["backup"],), daemon=True).start()
        self.bk_btn.config(text="備份：開" if cfg["backup"] else "備份…")
        save_cfg()

    def show_music(self):
        for key, (b, name) in self.music.items():
            p = cfg.get(key, "")
            try:
                b.config(text=f"{name}：{os.path.basename(p)[:14]}（點一下拿掉）" if p else f"選{name}音樂…")
            except tk.TclError:
                pass
        self.show_opts()

    def pick_music(self, key):
        from tkinter import filedialog
        cfg[key] = "" if cfg.get(key) else filedialog.askopenfilename(parent=self.optw or self.w, title="選音樂",
                                                                       filetypes=[("音樂", "*.mp3 *.wav *.m4a *.flac *.ogg")])
        self.show_music()

    def show_opts(self):
        """一鍵後製下面那一行：現在會做哪些事。"""
        on = [n for v, n in ((self.v_lv, "音量拉齊"), (self.v_dn, "降噪"), (self.v_cut, "剪長空白"), (self.v_fil, "剪贅字"),
                             (self.v_txt, "逐字稿"), (self.v_cov, "封面")) if v.get()]
        on += [n for k, n in (("intro", "片頭音樂"), ("outro", "片尾音樂")) if cfg.get(k)]
        slow = QS["gpu"] is False and (self.v_txt.get() or self.v_fil.get())   # 文書筆電：聽寫只能用處理器
        self.opt_sum.config(text="會做：" + "、".join(on + ["響度標準化", "MP3" if self.v_mp3.get() else "WAV"])
                            + ("\n這台沒有可用的顯示卡：逐字稿、剪贅字要聽寫，大約要錄音長度的一半到一樣久。趕時間就到「改選項」關掉。" if slow else ""))

    def options(self):
        """後製選項（小視窗）：平常不用動，預設就是常用的組合。"""
        if self.optw:
            self.optw.lift()
            return
        px = self.px
        t = self.optw = tk.Toplevel(self.w)
        t.title("後製選項")
        t.configure(bg="white", padx=px(18), pady=px(14))
        t.resizable(False, False)
        t.transient(self.w)
        tk.Label(t, text="後製會做的事", font=(FONT, 12, "bold"), bg="white", anchor="w").pack(fill="x")
        for v, text in ((self.v_lv, "兩個人的音量拉齊"), (self.v_dn, "降噪（房間有冷氣、風扇聲再開）"), (self.v_cut, "把太長的空白剪短"),
                        (self.v_fil, "剪掉單獨的贅字（嗯、呃、那個…）"), (self.v_txt, "逐字稿和字幕"), (self.v_cov, "封面和上架資料"),
                        (self.v_mp3, "輸出 MP3（不勾 = WAV）")):
            ttk.Checkbutton(t, text=text, variable=v, command=self.show_opts).pack(anchor="w", pady=px(1))
        tk.Label(t, text="片頭、片尾音樂", font=(FONT, 10, "bold"), bg="white", anchor="w").pack(fill="x", pady=(px(10), px(4)))
        self.music = {}
        for key, name in (("intro", "片頭"), ("outro", "片尾")):
            b = ttk.Button(t, command=lambda k=key: self.pick_music(k))
            b.pack(fill="x", pady=px(2))
            self.music[key] = (b, name)
        self.show_music()
        tk.Label(t, text="其他", font=(FONT, 10, "bold"), bg="white", anchor="w").pack(fill="x", pady=(px(10), px(4)))
        ttk.Button(t, text="先試聽開頭 45 秒（很快）", command=lambda: self.post(True)).pack(fill="x", pady=px(2))
        ttk.Button(t, text="併入來賓自己錄的檔案…", command=self.guest).pack(fill="x", pady=px(2))

        def done():
            self.optw = None
            self.music = {}
            t.destroy()
        ttk.Button(t, text="好了", style="Go.TButton", command=done).pack(fill="x", pady=(px(12), 0))
        t.protocol("WM_DELETE_WINDOW", done)

    def chosen(self, quiet=False):
        """右邊清單選到的檔案（完整路徑）；沒選但有檔案就用最新的那個。"""
        sel = self.lst.curselection()
        if not sel and self.files:
            self.lst.selection_set(0)
            sel = (0,)
        if not sel and not quiet:
            self.post_l.config(text="還沒有錄音檔。先錄一段，或按「加入檔案…」把別的地方的錄音加進來。", fg=RED)
        return [os.path.join(SAVE, self.files[i]) for i in sel]

    def refresh_files(self):
        keep = {self.files[i] for i in self.lst.curselection()} if self.files else set()
        fs = []
        if os.path.isdir(SAVE):
            fs = sorted((f for f in os.listdir(SAVE) if f.lower().endswith(".wav")), key=lambda f: os.path.getmtime(os.path.join(SAVE, f)), reverse=True)
        self.files = fs
        self.lst.delete(0, "end")
        for i, f in enumerate(fs):
            p = os.path.join(SAVE, f)
            try:
                inf = wav_info(p)
                d = int(inf["frames"] / inf["rate"])
                dur = f"{d // 3600}:{d // 60 % 60:02d}:{d % 60:02d}" if d >= 3600 else f"{d // 60}:{d % 60:02d}"
            except (OSError, ValueError, struct.error, ZeroDivisionError):
                dur = "?"
            n = len(load_meta(p).get("cuts") or [])
            self.lst.insert("end", f"{os.path.splitext(f)[0]}　{dur}" + (f"　✂{n}" if n else ""))
            if f in keep:
                self.lst.selection_set(i)
        if fs and not self.lst.curselection():
            self.lst.selection_set(0)

    def open_editor(self):
        p = self.chosen()
        if not p:
            return
        if self.ed_win:
            if self.ed_win.path == p[0]:
                self.ed_win.top.lift()
                return
            self.ed_win.close()
        try:
            self.ed_win = Editor(self, p[0])
        except Exception as e:
            self.ed_win = None
            self.post_l.config(text="打不開這個檔案：" + str(e)[:80], fg=RED)

    def import_files(self):
        """把別的地方的錄音（手機用 USB 拷的、別的軟體錄的）加進來；不是 WAV 的先轉成 WAV。"""
        from tkinter import filedialog
        ps = filedialog.askopenfilenames(parent=self.w, title="選要加進來的錄音",
                                         filetypes=[("聲音", "*.wav *.mp3 *.m4a *.aac *.flac *.ogg *.opus *.wma *.mp4 *.mov"), ("全部", "*.*")])
        if not ps:
            return
        say = lambda s, c="#333": self.w.after(0, lambda: self.post_l.config(text=s, fg=c))

        def run():
            os.makedirs(SAVE, exist_ok=True)
            ok, bad = 0, []
            for p in ps:
                stem = os.path.splitext(os.path.basename(p))[0]
                dst, k = os.path.join(SAVE, stem + ".wav"), 2
                while os.path.exists(dst):
                    dst, k = os.path.join(SAVE, f"{stem}_{k}.wav"), k + 1
                say(f"加入中：{os.path.basename(p)}…")
                try:
                    try:
                        if not p.lower().endswith(".wav"):
                            raise ValueError
                        wav_info(p)
                        shutil.copy2(p, dst)
                    except (ValueError, struct.error):
                        ff = ffmpeg_path()
                        if not ff:
                            raise RuntimeError("找不到 ffmpeg")
                        run_ff([ff, "-hide_banner", "-y", "-i", p, "-vn", "-ar", "48000", "-c:a", "pcm_s24le", dst])
                    js = os.path.splitext(p)[0] + ".json"
                    if os.path.exists(js):
                        shutil.copy2(js, os.path.splitext(dst)[0] + ".json")
                    ok += 1
                except Exception as e:
                    bad.append(os.path.basename(p))
                    if os.path.exists(dst):
                        os.remove(dst)
            state["files"] += 1
            say(f"加進來 {ok} 個檔案。" + (f"\n這些不行：{'、'.join(bad)}" if bad else "") + "\n選它按「剪輯」或「一鍵後製」。", RED if bad else "#333")

        threading.Thread(target=run, daemon=True).start()

    def guest(self):
        from tkinter import filedialog, simpledialog
        sel = self.lst.curselection()
        if self.busy or not sel:
            self.post_l.config(text="先在上面選主持人那一份錄音。", fg=RED)
            return
        host = os.path.join(SAVE, self.files[sel[0]])
        g = filedialog.askopenfilename(parent=self.w, title="選來賓錄的檔案", filetypes=[("聲音", "*.wav *.mp3 *.m4a *.flac *.aac *.ogg")])
        if not g:
            return
        auto = messagebox.askyesno("錄音台", "要自動對時間嗎？\n\n「是」：自動（錄的時候主持人這邊有用喇叭放出來賓的聲音才對得準）\n「否」：自己輸入來賓的檔案要晚幾秒開始", parent=self.w)
        off = None if auto else simpledialog.askfloat("錄音台", "來賓的檔案要晚幾秒開始？（要提早就填負的）", parent=self.w, initialvalue=0.0)
        if not auto and off is None:
            return
        self.busy = True
        say = lambda s: self.w.after(0, lambda: self.post_l.config(text=s, fg="#333"))

        def run():
            try:
                out, note = merge_guest(host, g, off, say)
                state["files"] += 1
                say("合併好了：" + os.path.basename(out) + "\n" + note + "\n接著選它按「一鍵後製」。")
            except Exception as e:
                say("合併失敗：" + str(e))
            self.busy = False

        threading.Thread(target=run, daemon=True).start()

    # ----- 提詞 -----
    def edited(self, _e, force=False):
        if not force and not self.ed.edit_modified():
            return
        self.ed.edit_modified(False)
        cfg.update(script=self.ed.get("1.0", "end-1c"), title=self.title_e.get().strip(), rev=int(time.time() * 1000))
        if self.pw:
            self.pw.load()

    def play(self):
        touch(run=not prompt["run"] and bool(cfg["script"].strip()))

    def speed(self, d):
        touch(speed=max(1.0, min(10.0, prompt["speed"] + d)))

    def open_prompter(self):
        if self.pw:
            self.pw.top.lift()
        else:
            self.pw = Prompter(self)

    # ----- 後製 -----
    def post(self, preview=False):
        if self.busy:
            return
        paths = self.chosen()
        if not paths:
            return
        paths = paths[:1] if preview else paths   # 選好幾個 = 排隊一個一個做
        if self.ed_win and self.ed_win.play:
            self.ed_win.stop()
        self.busy = True
        self.pre = ""
        self.post_btn.state(["disabled"])
        opt = dict(denoise=self.v_dn.get(), level=self.v_lv.get(), mp3=self.v_mp3.get(), cut=self.v_cut.get(), text=self.v_txt.get(),
                   filler=self.v_fil.get(), cover=self.v_cov.get(), intro=cfg.get("intro"), outro=cfg.get("outro"), title=self.ep.get().strip() if len(paths) == 1 else "")
        if preview:   # 試聽不做聽寫（最花時間的部分），幾秒就好
            opt.update(preview=True, text=False, filler=False, cover=False, mp3=True)
        self.step, self.step_t = 0, time.time()

        def say(s):
            self.step += 1
            self.step_t = time.time()
            self.post_msg = f"{self.pre}第 {self.step} 步　{s}"

        def run():
            try:
                outs = []
                for i, path in enumerate(paths):
                    self.step = 0
                    self.pre = f"（第 {i + 1} 個，共 {len(paths)} 個）" if len(paths) > 1 else ""
                    say("準備…")
                    out, notes = postprocess(path, opt, say)
                    outs.append(os.path.basename(out))
                if preview:
                    self.post_msg = "試聽檔已經打開（只有開頭 45 秒）。滿意再按「一鍵後製」。"
                    os.startfile(out)
                else:
                    self.did_post = True
                    self.post_msg = ("完成：" + "、".join(outs) + "\n" + ("、".join(notes) if len(outs) == 1 else "")
                                     + "\n按「成品資料夾」拿檔案。").replace("\n\n", "\n")
                fg = "#333"
            except Exception as e:
                self.post_msg = f"後製失敗（{os.path.basename(path)}）：" + str(e)[-300:]
                fg = RED
            self.busy = False
            self.w.after(0, lambda: (self.post_btn.state(["!disabled"]), self.post_l.config(text=self.post_msg, fg=fg)))

        threading.Thread(target=run, daemon=True).start()

    def do_update(self):
        try:
            self.guide.config(text="下載新版中…")
            self.w.update()
            apply_update(self.upd[0])
            self.close()
        except Exception as e:
            messagebox.showerror("錄音台", "更新失敗：" + str(e), parent=self.w)

    # ----- 每 50 毫秒 -----
    def tick(self):
        now = time.perf_counter()
        with lock:
            if prompt["run"]:
                prompt["pos"] += (now - self.last_tick) * prompt["speed"]
                if prompt["pos"] >= len(cfg["script"]):
                    prompt.update(pos=float(len(cfg["script"])), run=False)
            pend = dict(state["pending"])
            state["pending"].clear()
            logs = list(state["log"])
            script_in = state.pop("script_in", False)
        self.last_tick = now
        for pid, name in pend.items():
            if pid not in cfg["phones"] and messagebox.askyesno("錄音台", f"允許手機「{name}」連線嗎？", parent=self.w):
                cfg["phones"][pid] = name
                save_cfg()
        if script_in:
            self.ed.delete("1.0", "end")
            self.ed.insert("1.0", cfg["script"])
            self.ed.edit_modified(False)
            self.title_e.delete(0, "end")
            self.title_e.insert(0, cfg["title"])
            if self.pw:
                self.pw.load()
        p = self.first()
        if p:
            s = p["s"]
            mode = {1: "一支發射器", 2: "單聲道", 3: "安全音軌", 4: "兩支發射器各一軌"}.get(s.get("mode"), "")
            via = "" if p.get("local") else "（USB 線）" if p.get("usb") else "（Wi-Fi）"
            self.phone.config(text=f"{p['name']}　已連線{via}\n{s.get('dev', '')}　{mode}\n還能錄 {s.get('free', 0) // 60} 分鐘", fg="#333")
            rec = s.get("rec", 0)
            self.timer.config(text=time.strftime("%H:%M:%S" if s.get("t", 0) >= 3600 else "%M:%S", time.gmtime(s.get("t", 0))))
            self.recst.config(text=["待命", "● 錄音中", "暫停中"][rec] + (f"　標記 {s.get('marks')}" if rec and s.get("marks") else ""),
                              fg=[DIM, RED, GOLD][rec])
            self.rec_btn.config(text="開始錄音" if rec == 0 else "停止錄音")
            names = s.get("names", ["主持人", "來賓"])
            two = s.get("mode") in (3, 4)
            for i, (lab, cv, hold) in enumerate(self.meters):
                db = s.get("peak", [-120, -120])[i]
                hold[0] = max(db, hold[0] - 1.5)
                lab.config(text=(names[i] if s.get("mode") == 4 else ["麥克風", "備用音軌"][i]) + (f"　{hold[0]:.0f} dB" if hold[0] > -90 else ""),
                           fg="#333" if i == 0 or two else "#BBB")
                wd = cv.winfo_width()
                cv.delete("all")
                cv.create_rectangle(wd * 0.8, 0, wd * 0.9, 99, fill="#E9DDBF", width=0)   # 建議範圍 −12～−6
                x = max(0.0, min(1.0, (hold[0] + 60) / 60)) * wd
                cv.create_rectangle(0, 0, x, 99, fill=RED if hold[0] > -3 else GOLD if hold[0] > -12 else GREEN, width=0)
            up = s.get("up", {})
            wn = ("麥克風斷線過，手機已改用自己的麥克風續錄" if s.get("lost") and rec else "破音了：把接收器的增益調低" if s.get("clip") else
                  s.get("err") or (f"正在收檔 {up['name']} {up.get('prog', 0) * 100:.0f}%" if up.get("name") else ""))
            if not wn and self.batt is not None and self.batt < 30:
                wn = f"筆電沒插電，電量剩 {self.batt}%：長時間錄音請先插上電源"
            self.warn.config(text=wn, fg=DIM if wn.startswith("正在收檔") else RED)
        else:
            self.phone.config(text="還沒有手機連上。\n最快：USB 線接手機，手機設定打開「USB 網路共用」，自動連上。"
                                   + (f"\nWi-Fi 找不到：手機上「手動輸入位址」填 {self.ip}" if self.ip else ""), fg=DIM)
            self.recst.config(text="")
            self.warn.config(text="")
        self.rec_btn.state(["!disabled"] if p else ["disabled"])
        show_fw = self.fw_bad and not p
        if show_fw != bool(self.fw_btn.winfo_manager()):
            if show_fw:
                self.fw_btn.pack(fill="x", pady=(self.px(6), 0), after=self.phone)
            else:
                self.fw_btn.pack_forget()
        # 上面那條指引：現在做到哪一步、下一步做什麼
        rec = p["s"].get("rec", 0) if p else 0
        if self.busy:
            g = "⑤ 處理中… 好了會寫在右下角。"
        elif not p:
            g = "① 接上麥克風：手機打開「錄音台」App（跟電腦同一個 Wi-Fi，接收器插手機）；或把接收器插這台電腦，勾左邊的「用這台電腦錄」。"
        elif rec:
            g = "③ 錄音中：講錯或想記住的地方按 F8 打標記（之後會變成章節）。講完按「停止錄音」。"
        elif p["s"].get("up", {}).get("name"):
            g = "④ 手機正在把檔案傳過來，等它跑完。"
        elif self.did_post:
            g = "⑥ 完成：按「成品資料夾」，裡面有 MP3、逐字稿、字幕、封面、上架資料，拿去上傳到 podcast 平台就可以了。要錄下一集直接按「開始錄音」。"
        elif self.did_rec and self.lst.size():
            g = "⑤ 後製：右邊選剛錄好的檔案（最上面那個）。想刪掉講錯的地方先按「✂ 剪輯」；不用剪就直接按「一鍵後製成 podcast」。"
        else:
            g = "② 準備：幫這一集取名；要提詞就在中間寫稿，按「提詞視窗」。先講幾句話看音量條，最大聲落在淺色那一段最好。準備好按「開始錄音」。"
        if g[2:] != self.guide.cget("text"):
            self.guide.config(text=g[2:])
            step = "①②③④⑤⑥".index(g[0])
            for i, c in enumerate(self.chips):
                c.config(bg="#C39A45" if i == step else "#EFE7D2" if i < step else "#F1EEE6", fg="white" if i == step else "#8A7440" if i < step else "#9A968B")
        self.logl.config(text="\n".join(logs))
        n = len(cfg["script"])
        self.count.config(text=f"{n} 字，約 {n / prompt['speed'] / 60:.1f} 分鐘（手機會同步這份稿子）")
        self.speed_l.config(text=f"{prompt['speed'] * 60:.0f} 字/分")
        self.play_btn.config(text="暫停" if prompt["run"] else "開始捲")
        if state["files"] != self.files_seen:
            if self.files_seen >= 0:      # 有新檔進來（手機傳來或剛錄完）→ 指引跳到「後製」
                self.did_rec, self.did_post = True, False
                if cfg.get("backup"):
                    threading.Thread(target=backup_new, args=(cfg["backup"],), daemon=True).start()
            self.files_seen = state["files"]
            self.refresh_files()
            self.lst.selection_clear(0, "end")
            if self.files:
                self.lst.selection_set(0)
        self.n += 1
        if self.n % 50 == 1:
            self.batt = battery()
        if self.busy:
            sec = int(time.time() - self.step_t)
            self.post_l.config(text=f"{self.post_msg}　（{sec // 60}:{sec % 60:02d}）", fg="#333")
        if self.upd and not self.upd_btn:
            self.upd_btn = ttk.Button(self.steps_row, text=f"有新版，點這裡更新", style="Go.TButton", command=self.do_update)
            self.upd_btn.pack(side="left", padx=(self.px(24), 0))
        if self.pw:
            self.pw.tick()
        if self.ed_win:
            self.ed_win.tick()
        on = (rec != 0, bool(self.pw and prompt["run"]))
        if on != self.awake:
            self.awake = on
            keep_awake(*on)
        self.w.after(16 if on[1] else 30 if (self.ed_win and self.ed_win.play) else 60 if (p or self.pw) else 200, self.tick)


def fmt_t(t, frac=False):
    t = max(0.0, t)
    s = f"{int(t) // 3600}:{int(t) // 60 % 60:02d}:{int(t) % 60:02d}" if t >= 3600 else f"{int(t) // 60}:{int(t) % 60:02d}"
    return s + (f".{int(t * 10) % 10}" if frac else "")


class Playback:
    """剪輯畫面的播放：從某個時間開始播，刪掉的段落直接跳過（聽起來就是剪完的樣子）。"""

    def __init__(self, path, info, gain, solo):
        self.info, self.gain, self.solo = info, gain, solo
        self.f = open(path, "rb")
        self.pos, self.skip, self.stream, self.done = 0, [], None, False

    def set_skip(self, cuts):
        r = self.info["rate"]
        self.skip = [(int(a * r), int(b * r)) for a, b in cuts]

    def start(self, t, cuts):
        import sounddevice as sd
        self.pos = int(t * self.info["rate"])
        self.set_skip(cuts)
        self.stream = sd.OutputStream(samplerate=self.info["rate"], channels=2, dtype="float32", callback=self.cb, blocksize=2048, latency="high")
        self.stream.start()

    def cb(self, out, n, _t, _s):
        import numpy as np
        import sounddevice as sd
        info, i, end = self.info, 0, self.info["frames"]
        try:
            while i < n:
                p, skip = self.pos, self.skip
                for a, b in skip:
                    if a <= p < b:
                        p = b
                nb = min([a for a, b in skip if a > p] + [end])
                x = None
                if p < end:
                    self.f.seek(info["off"] + p * info["ch"] * (info["bits"] // 8))
                    x = read_frames(self.f, info, min(n - i, nb - p))
                if x is None or not len(x):
                    out[i:] = 0
                    self.done = True
                    raise sd.CallbackStop
                k = len(x)
                y = np.clip(x * self.gain, -1, 1)
                if info["ch"] == 1 or self.solo:
                    out[i:i + k, 0] = y[:, 0]
                    out[i:i + k, 1] = y[:, 0]
                else:
                    out[i:i + k] = y[:, :2]
                i += k
                self.pos = p + k
        except sd.CallbackStop:
            raise
        except Exception:
            out[i:] = 0
            self.done = True
            raise sd.CallbackStop

    def close(self):
        try:
            if self.stream:
                self.stream.stop()
                self.stream.close()
        except Exception:
            pass
        self.stream = None
        self.f.close()


class Editor:
    """✂ 剪輯：看波形、拖曳選一段、刪掉；播放時直接跳過刪掉的地方（聽起來就是剪完的樣子）。
    刪掉的段落存在錄音旁邊的 .json（cuts，原始檔的秒數），一鍵後製時照著剪；原始錄音本身不會被改。"""

    def __init__(self, app, path):
        self.app, self.path = app, path
        self.info = wav_info(path)
        self.dur = self.info["frames"] / self.info["rate"]
        if self.dur < 0.5:
            raise ValueError("檔案太短")
        meta = load_meta(path)
        self.cuts = merge_ranges(meta.get("cuts") or [], self.dur)
        self.marks = [m for m in meta.get("markers", []) if isinstance(m, dict) and "t" in m]
        self.segs = meta.get("segs_raw") or []
        self.solo = meta.get("mode") == 3 and self.info["ch"] == 2   # 安全音軌：只聽第 1 軌（第 2 軌是小聲的備份）
        self.hist, self.env, self.prog, self.err = [], None, 0.0, ""
        self.norm, self.gain = 1.0, 1.0
        self.v0, self.span = 0.0, min(self.dur, 90.0)
        self.sel, self.cur, self.drag, self.until = None, 0.0, None, None
        self.play, self.dirty, self.lists_dirty, self.txt_msg = None, False, False, None
        self.ph = self.ov_ph = self.sel_id = self.ov_box = None
        self.pending, self.ov_dirty, self.ov_size, self.last_prog = False, True, None, 0.0
        px = self.px = app.px
        t = self.top = tk.Toplevel(app.w)
        t.title("剪輯 — " + os.path.basename(path))
        t.configure(bg="#F4F2EC")
        t.geometry(f"{px(1180)}x{px(660)}")
        t.minsize(px(860), px(520))
        B = lambda parent, **kw: ttk.Button(parent, takefocus=False, **kw)   # 按鈕不搶焦點：空白鍵永遠是播放／暫停

        head = tk.Frame(t, bg="white", highlightbackground=LINE, highlightthickness=1, padx=px(16), pady=px(10))
        head.pack(fill="x", padx=px(14), pady=(px(12), 0))
        tk.Label(head, text="✂ " + os.path.splitext(os.path.basename(path))[0], font=(FONT, 13, "bold"), bg="white", anchor="w").pack(fill="x")
        self.sum = tk.Label(head, text="", font=(FONT, 10), bg="white", fg="#333", anchor="w")
        self.sum.pack(fill="x")
        tk.Label(head, text="① 在波形上按住滑鼠往旁邊拖，選出要刪的地方　② 按「刪掉選取」（或 Delete 鍵）。播放時會跳過刪掉的地方，聽到的就是剪完的樣子。\n"
                            "點一下波形＝從那裡開始聽；空白鍵＝播放／暫停；滾輪＝左右捲動；Ctrl＋滾輪＝放大縮小。改動會自動存好，原始錄音不會被改。",
                 font=(FONT, 9), bg="white", fg=DIM, anchor="w", justify="left").pack(fill="x", pady=(px(4), 0))

        body = tk.Frame(t, bg="#F4F2EC")
        body.pack(fill="both", expand=True, padx=px(14), pady=px(10))
        side = tk.Frame(body, bg="white", highlightbackground=LINE, highlightthickness=1, padx=px(12), pady=px(10), width=px(300))
        side.pack(side="right", fill="y", padx=(px(10), 0))
        side.pack_propagate(False)
        main = tk.Frame(body, bg="white", highlightbackground=LINE, highlightthickness=1, padx=px(12), pady=px(10))
        main.pack(side="left", fill="both", expand=True)

        bar = tk.Frame(main, bg="white")
        bar.pack(side="bottom", fill="x", pady=(px(8), 0))
        self.play_b = B(bar, text="▶ 播放", style="Go.TButton", command=self.toggle, width=8)
        self.play_b.pack(side="left")
        B(bar, text="刪掉選取", command=self.delete).pack(side="left", padx=(px(8), 0))
        B(bar, text="救回選取", command=self.restore).pack(side="left", padx=(px(6), 0))
        B(bar, text="↶ 復原", command=self.undo).pack(side="left", padx=(px(6), 0))
        B(bar, text="看全部", command=lambda: self.zoom(None)).pack(side="right")
        B(bar, text="＋", width=3, command=lambda: self.zoom(0.5)).pack(side="right", padx=(0, px(6)))
        B(bar, text="－", width=3, command=lambda: self.zoom(2)).pack(side="right", padx=(0, px(4)))
        self.pos_l = tk.Label(main, text="", font=(FONT, 10), bg="white", fg="#333", anchor="w")
        self.pos_l.pack(side="bottom", fill="x", pady=(px(6), 0))
        tk.Label(main, text="整段（點一下跳過去）", font=(FONT, 8), bg="white", fg=DIM, anchor="w").pack(fill="x")
        self.ov = tk.Canvas(main, height=px(38), bg="#F5F3EE", highlightthickness=0, cursor="hand2")
        self.ov.pack(fill="x")
        self.cv = tk.Canvas(main, bg="white", highlightthickness=0, cursor="xterm")
        self.cv.pack(fill="both", expand=True, pady=(px(8), 0))

        tk.Label(side, text="刪掉的段落", font=(FONT, 11, "bold"), bg="white", anchor="w").pack(fill="x")
        self.cut_lb = tk.Listbox(side, font=(FONT, 10), height=6, relief="solid", bd=1, activestyle="none", exportselection=False,
                                 selectbackground="#F3D6D6", selectforeground="#7A1F1F")
        self.cut_lb.pack(fill="x", pady=(px(4), px(4)))
        self.cut_lb.bind("<<ListboxSelect>>", self.cut_pick)
        B(side, text="救回這一段", command=self.cut_restore).pack(fill="x")
        tk.Label(side, text="文字（點一句＝選取那一句）", font=(FONT, 11, "bold"), bg="white", anchor="w").pack(fill="x", pady=(px(14), 0))
        self.txt_b = B(side, text="聽寫出文字（用快字幕，找段落更快）", command=self.transcribe)
        self.txt_b.pack(side="bottom", fill="x", pady=(px(4), 0))
        self.txt_l = tk.Label(side, text="", font=(FONT, 9), bg="white", fg=DIM, anchor="w", justify="left", wraplength=px(270))
        self.txt_l.pack(side="bottom", fill="x")
        self.txt_lb = tk.Listbox(side, font=(FONT, 10), relief="solid", bd=1, activestyle="none", exportselection=False,
                                 selectbackground="#F6E7C1", selectforeground="#3A3528")
        self.txt_lb.pack(fill="both", expand=True, pady=(px(4), 0))
        self.txt_lb.bind("<<ListboxSelect>>", self.seg_pick)

        cv = self.cv
        cv.bind("<Configure>", lambda e: self.request_draw())
        self.ov.bind("<Configure>", lambda e: self.request_draw(True))
        cv.bind("<Button-1>", self.press)
        cv.bind("<B1-Motion>", self.motion)
        cv.bind("<ButtonRelease-1>", self.release)
        for wd in (cv, self.ov):
            wd.bind("<MouseWheel>", self.wheel)
        self.ov.bind("<Button-1>", self.ov_click)
        self.ov.bind("<B1-Motion>", self.ov_click)
        for k, f in (("<space>", self.toggle), ("<Delete>", self.delete), ("<BackSpace>", self.delete), ("<Control-z>", self.undo),
                     ("<Left>", lambda: self.seek(self.cur - 5)), ("<Right>", lambda: self.seek(self.cur + 5)), ("<Home>", lambda: self.seek(0)),
                     ("<plus>", lambda: self.zoom(0.5)), ("<equal>", lambda: self.zoom(0.5)), ("<minus>", lambda: self.zoom(2)),
                     ("<Escape>", lambda: setattr(self, "sel", None) or self.draw())):
            t.bind(k, lambda e, f=f: (f(), "break")[1])
        t.protocol("WM_DELETE_WINDOW", self.close)
        t.focus_force()
        self.fill_lists()
        self.update_sum()
        threading.Thread(target=self.load_env, daemon=True).start()

    # ----- 座標 -----
    def W(self):
        return max(1, self.cv.winfo_width())

    def x2t(self, x):
        return self.v0 + x / self.W() * self.span

    def t2x(self, t):
        return (t - self.v0) / self.span * self.W()

    def clamp_view(self):
        self.span = max(min(2.0, self.dur), min(self.dur, self.span))
        self.v0 = max(0.0, min(self.dur - self.span, self.v0))

    # ----- 讀波形 -----
    def load_env(self):
        import numpy as np
        try:
            e = envelope(self.path, lambda p: setattr(self, "prog", p))
            self.norm = max(float(np.percentile(e, 99.5)) if len(e) else 0.0, 1e-4)
            self.gain = max(1.0, min(10.0, 0.5 / self.norm))   # 錄得小聲的檔案試聽時放大一點（最多 20 dB）
            self.env = e
        except Exception as ex:
            self.err = str(ex)[:80]
        self.dirty = True

    # ----- 畫 -----
    def request_draw(self, ov=False):
        """滾輪、拖拉視窗這類一次來很多個的事件：合併成一次重畫。"""
        self.ov_dirty |= ov
        if not self.pending:
            self.pending = True
            self.top.after(25, self._draw_now)

    def _draw_now(self):
        self.pending = False
        self.draw()

    @staticmethod
    def poly(cv, xs, h, mid, color):
        """整段波形畫成一個填滿的多邊形（一個物件）。"""
        import numpy as np
        if len(xs) < 2:
            return
        pts = np.concatenate([np.column_stack([xs, mid - h]).ravel(), np.column_stack([xs[::-1], (mid + h)[::-1]]).ravel()])
        cv.create_polygon(*pts.tolist(), fill=color, outline="")

    def columns(self, W, t0, span):
        """每 2 個像素取一點：該點那一段時間裡最大的音量（0～1，開根號讓小聲的也看得到）。"""
        import numpy as np
        e = self.env
        xs = np.arange(0, W, 2, dtype=np.float64)
        edges = np.clip(((t0 + np.append(xs, W) * span / W) * ENV_HZ).astype(np.int64), 0, len(e) - 1)
        v = np.maximum.reduceat(e, edges)[:-1]
        tt = t0 + (xs + 1) * span / W
        v[tt > self.dur] = 0
        return xs, tt, np.sqrt(np.minimum(1.0, v / self.norm))

    def draw(self):
        import numpy as np
        cv, px = self.cv, self.px
        W, H = cv.winfo_width(), cv.winfo_height()
        if W < 20 or H < 40:
            return
        self.clamp_view()
        cv.delete("all")
        R = px(22)
        t0, t1 = self.v0, self.v0 + self.span
        step = next((s for s in (0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800) if s / self.span * W >= px(72)), 3600)
        k = math.floor(t0 / step)
        while k * step <= t1:
            x = self.t2x(k * step)
            cv.create_line(x, R - px(6), x, R, fill="#BBB")
            cv.create_text(x + px(3), R - px(5), text=fmt_t(k * step, step < 1), anchor="sw", font=(FONT, 8), fill="#888")
            k += 1
        cv.create_line(0, R, W, R, fill=LINE)
        mid, hh = R + (H - R) / 2, (H - R) / 2 - px(8)
        for a, b in self.cuts:
            if b > t0 and a < t1:
                xa, xb = max(0, self.t2x(a)), min(W, self.t2x(b))
                cv.create_rectangle(xa, R + 1, xb, H, fill="#FBE4E4", width=0)
                if xb - xa > px(44):
                    cv.create_text((xa + xb) / 2, R + px(12), text="已刪掉", font=(FONT, 9, "bold"), fill="#C24B4B")
        self.sel_id = cv.create_rectangle(-10, R + 1, -10, H, fill="#F6E7C1", outline="#C39A45")
        cv.create_line(0, mid, W, mid, fill="#E6E1D3")
        if self.env is None:
            cv.create_text(W / 2, mid, text=("讀不到波形：" + self.err) if self.err else f"讀取波形中… {self.prog * 100:.0f}%", font=(FONT, 11), fill=DIM)
        else:
            xs, tt, v = self.columns(W, t0, self.span)
            h = v * hh + 0.5
            self.poly(cv, xs, h, mid, "#B8913F")
            for a, b in self.cuts:   # 刪掉的那幾段：同一個波形再蓋一層淡紅色
                idx = np.flatnonzero((tt >= a) & (tt < b))
                if len(idx):
                    self.poly(cv, xs[idx[0]:idx[-1] + 1], h[idx[0]:idx[-1] + 1], mid, "#E7B3B3")
        for m in self.marks:
            if t0 <= m["t"] <= t1:
                x = self.t2x(m["t"])
                cv.create_line(x, R, x, H, fill="#C39A45", dash=(3, 3))
                cv.create_text(x + px(4), H - px(6), text="⚑ " + str(m.get("label", "")), anchor="sw", font=(FONT, 8), fill="#8A7440")
        x = self.t2x(self.cur)
        self.ph = cv.create_line(x, R, x, H, fill="#D9383D", width=max(2, px(2)))
        self.place_sel()
        ow, oh = self.ov.winfo_width(), self.ov.winfo_height()
        if self.ov_dirty or self.ov_size != (ow, oh) or self.ov_box is None:
            self.draw_ov()
        else:   # 只是捲動：整段縮圖不用重畫，移動框框就好
            sx = ow / self.dur
            self.ov.coords(self.ov_box, self.v0 * sx, 1, (self.v0 + self.span) * sx, oh - 1)
            self.move_ph()
        self.update_pos()

    def draw_ov(self):
        import numpy as np
        ov = self.ov
        W, H = ov.winfo_width(), ov.winfo_height()
        if W < 20:
            return
        self.ov_dirty, self.ov_size = False, (W, H)
        ov.delete("all")
        sx = W / self.dur
        for a, b in self.cuts:
            ov.create_rectangle(a * sx, 0, max(a * sx + 1, b * sx), H, fill="#F0C4C4", width=0)
        if self.env is not None:
            xs, _, v = self.columns(W, 0.0, self.dur)
            self.poly(ov, xs, v * (H / 2 - 2) + 0.5, H / 2, "#CDBF98")
        self.ov_box = ov.create_rectangle(self.v0 * sx, 1, (self.v0 + self.span) * sx, H - 1, outline="#C39A45", width=2)
        self.ov_ph = ov.create_line(self.cur * sx, 0, self.cur * sx, H, fill="#D9383D")

    def place_sel(self):
        if self.sel_id is None:
            return
        R, H = self.px(22), self.cv.winfo_height()
        if self.sel:
            self.cv.coords(self.sel_id, self.t2x(self.sel[0]), R + 1, self.t2x(self.sel[1]), H)
        else:
            self.cv.coords(self.sel_id, -10, R + 1, -10, H)

    def move_ph(self):
        if self.ph is not None:
            x = self.t2x(self.cur)
            self.cv.coords(self.ph, x, self.px(22), x, self.cv.winfo_height())
        if self.ov_ph is not None:
            x = self.cur * self.ov.winfo_width() / self.dur
            self.ov.coords(self.ov_ph, x, 0, x, self.ov.winfo_height())

    def update_pos(self):
        s = f"播放位置 {fmt_t(self.cur, True)}"
        if self.sel:
            s += f"　　選取 {fmt_t(self.sel[0], True)}～{fmt_t(self.sel[1], True)}（{self.sel[1] - self.sel[0]:.1f} 秒）"
        self.pos_l.config(text=s, fg="#333")

    def update_sum(self):
        cut = sum(b - a for a, b in self.cuts)
        self.sum.config(text=f"原本 {fmt_t(self.dur)}　→　剪完 {fmt_t(self.dur - cut)}　（刪掉 {len(self.cuts)} 段，共 {cut:.1f} 秒）"
                        if self.cuts else f"長度 {fmt_t(self.dur)}　還沒有刪掉任何段落")

    def fill_lists(self):
        self.cut_lb.delete(0, "end")
        for a, b in self.cuts:
            self.cut_lb.insert("end", f"{fmt_t(a, True)}～{fmt_t(b, True)}　{b - a:.1f} 秒")
        if not self.cuts:
            self.cut_lb.insert("end", "（還沒有）")
        self.txt_lb.delete(0, "end")
        for i, g in enumerate(self.segs):
            gone = any(a <= g["start"] and g["end"] <= b + 0.05 for a, b in self.cuts)
            self.txt_lb.insert("end", ("✂ " if gone else "") + f"{fmt_t(g['start'])}　{g['text']}")
            if gone:
                self.txt_lb.itemconfig(i, fg="#C24B4B")
        if not self.segs and not self.txt_l.cget("text"):
            self.txt_l.config(text="還沒有文字。不聽寫也可以直接看波形剪：講話的地方波形比較高，空白的地方是平的。")

    # ----- 滑鼠 -----
    def press(self, e):
        self.cv.focus_set()
        self.drag = (e.x, self.x2t(e.x))

    def motion(self, e):
        if not self.drag or abs(e.x - self.drag[0]) < 4:
            return
        tb = self.x2t(max(0, min(self.W(), e.x)))
        a, b = sorted((self.drag[1], tb))
        self.sel = (max(0.0, a), min(self.dur, b))
        self.place_sel()
        self.update_pos()

    def release(self, e):
        if self.drag and abs(e.x - self.drag[0]) < 4:   # 只是點一下：移動播放位置
            self.sel = None
            self.place_sel()
            self.seek(self.drag[1])
        self.drag = None

    def wheel(self, e):
        if e.state & 0x4:   # Ctrl：以滑鼠的位置為中心放大縮小
            t = self.x2t(e.x) if e.widget is self.cv else self.v0 + self.span / 2
            f = 0.8 if e.delta > 0 else 1.25
            self.span *= f
            self.v0 = t - (e.x / self.W() if e.widget is self.cv else 0.5) * self.span
        else:
            self.v0 -= e.delta / 120 * self.span * 0.15
        self.request_draw()

    def ov_click(self, e):
        self.v0 = e.x / max(1, self.ov.winfo_width()) * self.dur - self.span / 2
        self.request_draw()

    def zoom(self, f):
        if f is None:
            self.v0, self.span = 0.0, self.dur
        else:
            c = self.cur if self.v0 <= self.cur <= self.v0 + self.span else self.v0 + self.span / 2
            self.span *= f
            self.v0 = c - self.span / 2
        self.draw()

    def show(self, a, b=None):
        b = a if b is None else b
        if a < self.v0 or b > self.v0 + self.span:
            if b - a > self.span * 0.8:
                self.span = min(self.dur, (b - a) * 1.4)
            self.v0 = a - self.span * 0.2

    def seek(self, t):
        self.cur = max(0.0, min(self.dur, t))
        if self.play:
            self.start_play(self.cur)
        self.show(self.cur)
        self.draw()

    # ----- 播放 -----
    def toggle(self):
        if self.play:
            self.stop()
        elif self.sel:   # 有選取：只播選到的那段（刪之前先聽一下）
            self.start_play(self.sel[0], self.sel[1])
        else:
            self.start_play(self.cur if self.cur < self.dur - 0.2 else 0.0)

    def start_play(self, t, until=None):
        if self.play:
            self.play.close()
            self.play = None
        try:
            self.play = Playback(self.path, self.info, self.gain, self.solo)
            self.play.start(t, [] if until else self.cuts)
        except Exception as ex:
            if self.play:
                self.play.close()
            self.play = None
            self.pos_l.config(text="沒辦法播放：" + str(ex)[:60], fg=RED)
            return
        self.until = until
        self.play_b.config(text="⏸ 暫停")

    def stop(self):
        if self.play:
            self.cur = min(self.dur, self.play.pos / self.info["rate"])
            self.play.close()
            self.play = None
        self.play_b.config(text="▶ 播放")
        self.move_ph()
        self.update_pos()

    def tick(self):
        if self.dirty:   # 波形讀好了
            self.dirty, self.ov_dirty = False, True
            self.draw()
        elif self.env is None and not self.err and time.time() - self.last_prog > 0.3:   # 讀取中：進度最多每 0.3 秒更新
            self.last_prog = time.time()
            self.draw()
        if self.txt_msg:
            self.txt_l.config(text=self.txt_msg, fg=RED if "失敗" in self.txt_msg else DIM)
            self.txt_msg = None
            self.txt_b.state(["!disabled"])
            self.fill_lists()
        if self.play:
            self.cur = min(self.dur, self.play.pos / self.info["rate"])
            if self.play.done or (self.until and self.cur >= self.until):
                if self.until:
                    self.cur = self.until
                self.stop()
            if self.cur < self.v0 or self.cur > self.v0 + self.span:   # 播到畫面外：翻到下一頁
                self.v0 = self.cur - self.span * 0.05
                self.draw()
            else:
                self.move_ph()
                self.update_pos()

    # ----- 刪、救回、復原 -----
    def push(self):
        self.hist.append([list(c) for c in self.cuts])
        del self.hist[:-200]

    def changed(self):
        meta = load_meta(self.path)
        meta["cuts"] = [[round(a, 3), round(b, 3)] for a, b in self.cuts]
        try:
            save_meta(self.path, meta)
        except OSError as ex:
            self.pos_l.config(text="存不起來：" + str(ex)[:60], fg=RED)
        if self.play and not self.until:
            self.play.set_skip(self.cuts)
        self.update_sum()
        self.fill_lists()
        self.ov_dirty = True
        self.draw()
        self.app.refresh_files()

    def delete(self):
        if not self.sel or self.sel[1] - self.sel[0] < 0.02:
            self.pos_l.config(text="先在波形上按住滑鼠拖曳，選出要刪的地方。", fg=RED)
            return
        if self.play and self.until:
            self.stop()
        self.push()
        self.cuts = merge_ranges(self.cuts + [list(self.sel)], self.dur)
        self.cur, self.sel = self.sel[1], None
        self.changed()

    def restore(self):
        if self.sel:
            a, b = self.sel
        else:
            hit = [c for c in self.cuts if c[0] <= self.cur <= c[1]]
            if not hit:
                self.pos_l.config(text="把要救回的範圍選起來（或點在紅色的已刪段落上），再按「救回選取」。", fg=RED)
                return
            a, b = hit[0]
        out = []
        for x, y in self.cuts:
            if y <= a or x >= b:
                out.append([x, y])
            else:
                if x < a:
                    out.append([x, a])
                if y > b:
                    out.append([b, y])
        if out != self.cuts:
            self.push()
            self.cuts, self.sel = merge_ranges(out, self.dur), None
            self.changed()

    def undo(self):
        if self.hist:
            self.cuts = self.hist.pop()
            self.changed()

    def cut_pick(self, _e):
        s = self.cut_lb.curselection()
        if s and s[0] < len(self.cuts):
            a, b = self.cuts[s[0]]
            self.sel, self.cur = None, a
            self.show(max(0.0, a - 1), b + 1)
            self.draw()

    def cut_restore(self):
        s = self.cut_lb.curselection()
        if not s or s[0] >= len(self.cuts):
            self.pos_l.config(text="先在上面點一個刪掉的段落。", fg=RED)
            return
        self.push()
        del self.cuts[s[0]]
        self.changed()

    def seg_pick(self, _e):
        s = self.txt_lb.curselection()
        if s and s[0] < len(self.segs):
            g = self.segs[s[0]]
            self.sel = (max(0.0, g["start"]), min(self.dur, g["end"]))
            self.cur = self.sel[0]
            self.show(self.sel[0], self.sel[1])
            self.draw()

    def transcribe(self):
        qs = find_quicksub()
        if not qs:
            self.txt_l.config(text="這台電腦找不到快字幕，沒辦法聽寫。不聽寫也可以直接看波形剪。", fg=RED)
            return
        self.txt_b.state(["disabled"])
        self.txt_l.config(text="聽寫中…（第一次會比較久）" + ("\n這台電腦沒有可用的顯示卡：用小模型，大約要錄音長度的一半到一樣久。"
                                                         if QS["gpu"] is False else ""), fg=DIM)

        def run():
            td = tempfile.mkdtemp(prefix="podmic_qs")
            jp = os.path.join(td, "segs.json")
            try:
                subprocess.run([qs, "transcribe", self.path, "--segments", "-f", "txt", "-o", td, "-l", "zh", "-q", "--max-chars", "16", "--json", jp]
                               + (["-m", "small", "--cpu"] if QS["gpu"] is False else []), capture_output=True, creationflags=LOWPRI)
                with open(jp, encoding="utf-8") as f:
                    segs = [{"start": round(g["start"], 2), "end": round(g["end"], 2), "text": g["text"].strip()}
                            for g in json.load(f)["results"][0]["segments"]]
                meta = load_meta(self.path)
                meta["segs_raw"] = segs
                save_meta(self.path, meta)
                self.segs = segs
                msg = f"聽寫好了，共 {len(segs)} 句。點一句就會選取那一句，再按「刪掉選取」。"
            except Exception as ex:
                msg = "聽寫失敗：" + str(ex)[:60]
            shutil.rmtree(td, ignore_errors=True)
            self.txt_msg = msg

        threading.Thread(target=run, daemon=True).start()

    def close(self):
        if self.play:
            self.play.close()
            self.play = None
        self.app.ed_win = None
        self.top.destroy()
        self.app.refresh_files()


class Prompter:
    """大螢幕提詞。空白鍵＝開始／暫停、↑↓＝速度、←→＝前後跳、＋−＝字體、F11＝全螢幕、Home＝回開頭、Esc＝關。"""

    def __init__(self, app):
        self.app = app
        self.top = t = tk.Toplevel(app.w)
        t.title("提詞")
        t.configure(bg="black")
        t.geometry(f"{app.px(1100)}x{app.px(700)}")
        self.txt = tk.Text(t, bg="black", fg="#F3F1EB", wrap="char", bd=0, highlightthickness=0, cursor="arrow",
                           padx=app.px(110), insertwidth=0, spacing3=app.px(14))
        self.txt.pack(fill="both", expand=True)
        self.arrow = tk.Canvas(t, width=app.px(40), height=app.px(40), bg="black", highlightthickness=0)
        self.arrow.create_polygon(app.px(6), app.px(4), app.px(34), app.px(20), app.px(6), app.px(36), fill="#D0AC62")
        self.hint = tk.Label(t, text="空白鍵 開始／暫停　↑↓ 速度　←→ 前後跳　＋− 字體　F11 全螢幕　F8 標記　Esc 關閉", bg="black", fg="#666", font=(FONT, 9))
        self.hint.place(relx=0.5, rely=1.0, anchor="s", y=-app.px(8))
        self.full = False
        self.h = 0
        for k, f in (("<space>", lambda e: app.play()), ("<Up>", lambda e: app.speed(0.25)), ("<Down>", lambda e: app.speed(-0.25)),
                     ("<Left>", lambda e: self.jump(-40)), ("<Right>", lambda e: self.jump(40)), ("<Home>", lambda e: touch(pos=0.0, run=False)),
                     ("<plus>", lambda e: self.font(4)), ("<equal>", lambda e: self.font(4)), ("<minus>", lambda e: self.font(-4)),
                     ("<F11>", lambda e: self.fs()), ("<Escape>", lambda e: self.close()), ("<F8>", lambda e: app.mark()),
                     ("<MouseWheel>", lambda e: self.jump(-e.delta / 6))):
            t.bind(k, f)
        self.txt.bind("<Key>", lambda e: "break")
        self.txt.bind("<Button-1>", lambda e: (app.play(), "break")[1])
        t.protocol("WM_DELETE_WINDOW", self.close)
        t.focus_force()
        self.load()

    def close(self):
        self.app.pw = None
        self.top.destroy()

    def fs(self):
        self.full = not self.full
        self.top.attributes("-fullscreen", self.full)

    def jump(self, d):
        touch(pos=max(0.0, min(float(len(cfg["script"])), prompt["pos"] + d)))

    def font(self, d):
        cfg["font"] = max(20, min(120, cfg["font"] + d))
        self.load()

    def load(self):
        self.txt.config(state="normal", font=(FONT, cfg["font"], "bold"))
        self.txt.delete("1.0", "end")
        self.txt.insert("1.0", cfg["script"])
        self.txt.config(state="disabled")
        self.lh = tkfont.Font(font=(FONT, cfg["font"], "bold")).metrics("linespace")
        self.h = 0

    def tick(self):
        h = self.txt.winfo_height()
        if h < 50:
            return
        guide = int(h * 0.32)
        if h != self.h:   # 上下留白：第一行從箭頭開始、最後一行能捲到箭頭
            self.h = h
            self.txt.tag_configure("top", spacing1=guide)
            self.txt.tag_configure("bot", spacing3=h)
            self.txt.tag_add("top", "1.0", "1.end+1c")
            self.txt.tag_add("bot", "end-1c linestart", "end")
            self.arrow.place(x=self.app.px(30), y=guide + self.app.px(8))
        pos = prompt["pos"]
        i = int(pos)
        try:
            # 這個字所在那一行的頂端 + 行內比例 → 平順地捲
            y0 = self.txt.count("1.0", f"1.0+{i}c", "ypixels")[0] if i > 0 else 0
            ls, le = self.txt.index(f"1.0+{i}c display linestart"), self.txt.index(f"1.0+{i}c display lineend")
            n = max(1, len(self.txt.get(ls, le)))
            k = len(self.txt.get(ls, f"1.0+{i}c")) + (pos - i)
            lh = self.lh
            total = self.txt.count("1.0", "end", "ypixels")[0]
            # 第一行上面有 guide 的留白，所以第二行以後要扣掉
            self.txt.yview_moveto(max(0.0, (max(0, y0 - guide) + lh * k / n) / max(1, total)))
        except (tk.TclError, TypeError):
            pass


def main():
    try:
        srv = ThreadingHTTPServer(("0.0.0.0", PORT), H)
    except OSError:
        # 已經開著了（以前直接默默關掉：舊版還開著時，新版看起來像打不開、手機連到的其實是舊版）
        r = tk.Tk()
        r.withdraw()
        messagebox.showinfo("錄音台", "錄音台已經開著了（可能是舊版，或藏在別的視窗後面）。\n\n要用新版：先把舊的錄音台關掉，再打開這個。", parent=r)
        sys.exit(0)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    threading.Thread(target=finder, daemon=True).start()
    threading.Thread(target=net_watch, daemon=True).start()
    App().w.mainloop()


if __name__ == "__main__":
    main()

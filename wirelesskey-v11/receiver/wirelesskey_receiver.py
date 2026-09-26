import asyncio
import ctypes
import datetime
import hashlib
import json
import os
import random
import secrets
import socket
import ssl
import sys
import threading
import time
from collections import defaultdict, deque
from pathlib import Path

import psutil
import pystray
from PIL import Image, ImageDraw
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from websockets.legacy.server import serve

APP_NAME = "WirelessKey"
VERSION = "3.0"
DISCOVERY_PORT = 8766
PORT_START = 8765
PORT_END = 8775
PAIR_TTL_SECONDS = 1800

if sys.platform != "win32":
    raise SystemExit("WirelessKeyReceiver is for Windows only.")

user32 = ctypes.windll.user32
ULONG_PTR = ctypes.POINTER(ctypes.c_ulong)

class KEYBDINPUT(ctypes.Structure):
    _fields_ = [
        ("wVk", ctypes.c_ushort),
        ("wScan", ctypes.c_ushort),
        ("dwFlags", ctypes.c_ulong),
        ("time", ctypes.c_ulong),
        ("dwExtraInfo", ULONG_PTR),
    ]

class MOUSEINPUT(ctypes.Structure):
    _fields_ = [
        ("dx", ctypes.c_long),
        ("dy", ctypes.c_long),
        ("mouseData", ctypes.c_ulong),
        ("dwFlags", ctypes.c_ulong),
        ("time", ctypes.c_ulong),
        ("dwExtraInfo", ULONG_PTR),
    ]

class INPUT_I(ctypes.Union):
    _fields_ = [("ki", KEYBDINPUT), ("mi", MOUSEINPUT)]

class INPUT(ctypes.Structure):
    _fields_ = [("type", ctypes.c_ulong), ("ii", INPUT_I)]

INPUT_MOUSE = 0
INPUT_KEYBOARD = 1
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004
MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_MIDDLEDOWN = 0x0020
MOUSEEVENTF_MIDDLEUP = 0x0040
MOUSEEVENTF_WHEEL = 0x0800
MOUSEEVENTF_HWHEEL = 0x01000

VK = {
    "BACKSPACE": 0x08, "TAB": 0x09, "ENTER": 0x0D, "SHIFT": 0x10,
    "CTRL": 0x11, "ALT": 0x12, "PAUSE": 0x13, "CAPSLOCK": 0x14,
    "ESC": 0x1B, "SPACE": 0x20, "PAGEUP": 0x21, "PAGEDOWN": 0x22,
    "END": 0x23, "HOME": 0x24, "LEFT": 0x25, "UP": 0x26,
    "RIGHT": 0x27, "DOWN": 0x28, "PRINTSCREEN": 0x2C,
    "INSERT": 0x2D, "DELETE": 0x2E, "WIN": 0x5B, "MENU": 0x5D,
    "F1": 0x70, "F2": 0x71, "F3": 0x72, "F4": 0x73,
    "F5": 0x74, "F6": 0x75, "F7": 0x76, "F8": 0x77,
    "F9": 0x78, "F10": 0x79, "F11": 0x7A, "F12": 0x7B,
    "VOLUME_MUTE": 0xAD, "VOLUME_DOWN": 0xAE, "VOLUME_UP": 0xAF,
    "MEDIA_NEXT": 0xB0, "MEDIA_PREV": 0xB1, "MEDIA_STOP": 0xB2,
    "MEDIA_PLAY": 0xB3,
}
MOD_VK = {"CTRL": 0x11, "ALT": 0x12, "SHIFT": 0x10, "WIN": 0x5B}

state_lock = threading.RLock()
shutdown_event = threading.Event()
server_ready = threading.Event()
failed_attempts = defaultdict(deque)
connected_devices = {}
ACTIVE_PORT = PORT_START
PAIR_CODE = ""
PAIR_CREATED = 0.0
CERT_FINGERPRINT = ""
ui = None
tray_icon = None

def app_data_dir():
    base = os.environ.get("APPDATA") or str(Path.home())
    p = Path(base) / APP_NAME
    p.mkdir(parents=True, exist_ok=True)
    return p

DATA_DIR = app_data_dir()
TRUST_FILE = DATA_DIR / "trusted_devices.json"
CERT_FILE = DATA_DIR / "receiver_cert.pem"
KEY_FILE = DATA_DIR / "receiver_key.pem"

def load_trusted():
    try:
        data = json.loads(TRUST_FILE.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}

trusted_devices = load_trusted()

def save_trusted():
    try:
        tmp = TRUST_FILE.with_suffix(".tmp")
        tmp.write_text(json.dumps(trusted_devices, indent=2), encoding="utf-8")
        tmp.replace(TRUST_FILE)
    except Exception:
        pass

def ensure_certificate():
    global CERT_FINGERPRINT
    if not CERT_FILE.exists() or not KEY_FILE.exists():
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, socket.gethostname())])
        now = datetime.datetime.now(datetime.timezone.utc)
        cert = (
            x509.CertificateBuilder()
            .subject_name(name)
            .issuer_name(name)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=3650))
            .add_extension(
                x509.SubjectAlternativeName([
                    x509.DNSName(socket.gethostname()),
                    x509.DNSName("localhost"),
                ]),
                critical=False,
            )
            .sign(key, hashes.SHA256())
        )
        KEY_FILE.write_bytes(
            key.private_bytes(
                serialization.Encoding.PEM,
                serialization.PrivateFormat.PKCS8,
                serialization.NoEncryption(),
            )
        )
        CERT_FILE.write_bytes(cert.public_bytes(serialization.Encoding.PEM))

    cert = x509.load_pem_x509_certificate(CERT_FILE.read_bytes())
    CERT_FINGERPRINT = hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest()
    return CERT_FINGERPRINT

def rotate_pair_code(reason=""):
    global PAIR_CODE, PAIR_CREATED
    with state_lock:
        PAIR_CODE = f"{random.randint(0, 999999):06d}"
        PAIR_CREATED = time.time()
        code = PAIR_CODE
    if ui:
        ui.safe_refresh()
    return code

def current_pair_code():
    with state_lock:
        if not PAIR_CODE or time.time() - PAIR_CREATED > PAIR_TTL_SECONDS:
            return rotate_pair_code("expired")
        return PAIR_CODE

def pair_code_valid(code):
    with state_lock:
        return bool(
            PAIR_CODE
            and time.time() - PAIR_CREATED <= PAIR_TTL_SECONDS
            and secrets.compare_digest(str(code), PAIR_CODE)
        )

def _send_key(vk, up=False):
    ii = INPUT_I()
    ii.ki = KEYBDINPUT(vk, 0, KEYEVENTF_KEYUP if up else 0, 0, None)
    inp = INPUT(INPUT_KEYBOARD, ii)
    user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(inp))

def _send_unicode_unit(unit):
    for up in (False, True):
        ii = INPUT_I()
        flags = KEYEVENTF_UNICODE | (KEYEVENTF_KEYUP if up else 0)
        ii.ki = KEYBDINPUT(0, unit, flags, 0, None)
        inp = INPUT(INPUT_KEYBOARD, ii)
        user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(inp))

def type_text(text):
    for ch in str(text):
        if ord(ch) <= 0xFFFF:
            _send_unicode_unit(ord(ch))
        else:
            encoded = ch.encode("utf-16-le")
            for i in range(0, len(encoded), 2):
                _send_unicode_unit(int.from_bytes(encoded[i:i+2], "little"))

def press_key(name, modifiers=None):
    modifiers = [str(m).upper() for m in (modifiers or [])]
    pressed = []
    for mod in modifiers:
        vk = MOD_VK.get(mod)
        if vk:
            _send_key(vk)
            pressed.append(vk)

    key_name = str(name)
    upper = key_name.upper()
    vk = VK.get(upper)
    implicit_shift = False

    if vk is None and len(key_name) == 1:
        scan = user32.VkKeyScanW(ord(key_name))
        if scan != -1:
            vk = scan & 0xFF
            shift_state = (scan >> 8) & 0xFF
            if shift_state & 1 and "SHIFT" not in modifiers:
                _send_key(MOD_VK["SHIFT"])
                implicit_shift = True

    if vk is not None:
        _send_key(vk)
        _send_key(vk, True)

    if implicit_shift:
        _send_key(MOD_VK["SHIFT"], True)

    for vk in reversed(pressed):
        _send_key(vk, True)

def mouse_move(dx, dy):
    ii = INPUT_I()
    ii.mi = MOUSEINPUT(int(round(dx)), int(round(dy)), 0, MOUSEEVENTF_MOVE, 0, None)
    inp = INPUT(INPUT_MOUSE, ii)
    user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(inp))

def _mouse_flag(flag):
    ii = INPUT_I()
    ii.mi = MOUSEINPUT(0, 0, 0, flag, 0, None)
    inp = INPUT(INPUT_MOUSE, ii)
    user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(inp))

def mouse_button(button, action="click"):
    mapping = {
        "left": (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP),
        "right": (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP),
        "middle": (MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP),
    }
    down_flag, up_flag = mapping.get(str(button).lower(), mapping["left"])

    if action == "down":
        _mouse_flag(down_flag)
    elif action == "up":
        _mouse_flag(up_flag)
    elif action == "double":
        for _ in range(2):
            _mouse_flag(down_flag)
            _mouse_flag(up_flag)
    else:
        _mouse_flag(down_flag)
        _mouse_flag(up_flag)

def mouse_wheel(delta):
    ii = INPUT_I()
    ii.mi = MOUSEINPUT(0, 0, ctypes.c_ulong(int(delta)).value, MOUSEEVENTF_WHEEL, 0, None)
    inp = INPUT(INPUT_MOUSE, ii)
    user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(inp))

def mouse_hwheel(delta):
    ii = INPUT_I()
    ii.mi = MOUSEINPUT(0, 0, ctypes.c_ulong(int(delta)).value, MOUSEEVENTF_HWHEEL, 0, None)
    inp = INPUT(INPUT_MOUSE, ii)
    user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(inp))

def process_event(event):
    etype = event.get("type")
    if etype == "text":
        type_text(str(event.get("text", ""))[:500])
    elif etype == "key":
        press_key(str(event.get("key", "")), event.get("modifiers", []))
    elif etype == "move":
        mouse_move(event.get("dx", 0), event.get("dy", 0))
    elif etype == "mouse":
        mouse_button(str(event.get("button", "left")), str(event.get("action", "click")))
    elif etype == "wheel":
        mouse_wheel(event.get("delta", 0))
    elif etype == "hwheel":
        mouse_hwheel(event.get("delta", 0))
    else:
        raise ValueError("Unknown event")

def get_local_ip_for(remote_ip=None):
    targets = []
    if remote_ip:
        targets.append((remote_ip, 9))
    targets += [("8.8.8.8", 80), ("1.1.1.1", 80)]
    for target in targets:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(target)
            ip = s.getsockname()[0]
            s.close()
            if ip and not ip.startswith("127."):
                return ip
        except Exception:
            pass
    try:
        return socket.gethostbyname(socket.gethostname())
    except Exception:
        return "127.0.0.1"

def is_rate_limited(ip):
    now = time.time()
    q = failed_attempts[ip]
    while q and now - q[0] > 60:
        q.popleft()
    return len(q) >= 5

def mark_failed(ip):
    failed_attempts[ip].append(time.time())

def validate_auth(obj, client_ip):
    token = str(obj.get("token", "") or "")
    code = str(obj.get("code", "") or "")
    device = str(obj.get("device", "Android"))[:80]

    if token and token in trusted_devices:
        trusted_devices[token]["lastSeen"] = int(time.time())
        trusted_devices[token]["device"] = device
        save_trusted()
        return True, token, device, ""

    if is_rate_limited(client_ip):
        return False, "", device, "Too many incorrect attempts. Try again in a minute."

    if not pair_code_valid(code):
        mark_failed(client_ip)
        return False, "", device, "Wrong or expired pairing code"

    new_token = secrets.token_urlsafe(32)
    trusted_devices[new_token] = {
        "device": device,
        "created": int(time.time()),
        "lastSeen": int(time.time()),
    }
    save_trusted()
    return True, new_token, device, ""

def get_foreground_context():
    try:
        hwnd = user32.GetForegroundWindow()
        if not hwnd:
            return {"activeApp": "Desktop", "profile": "standard", "title": ""}

        length = user32.GetWindowTextLengthW(hwnd)
        buf = ctypes.create_unicode_buffer(length + 1)
        user32.GetWindowTextW(hwnd, buf, length + 1)
        title = buf.value[:140]

        pid = ctypes.c_ulong()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        process_name = ""
        try:
            process_name = psutil.Process(pid.value).name().lower()
        except Exception:
            pass

        blob = (process_name + " " + title).lower()
        profile = "standard"
        app_name = process_name or "Desktop"

        rules = [
            (("acad.exe", "autocad"), "AutoCAD", "autocad"),
            (("revit.exe", "autodesk revit"), "Revit", "revit"),
            (("excel.exe", "microsoft excel"), "Excel", "excel"),
            (("powerpnt.exe", "powerpoint"), "PowerPoint", "powerpoint"),
            (("winword.exe", "microsoft word"), "Word", "word"),
            (("code.exe", "visual studio code"), "VS Code", "vscode"),
            (("chrome.exe", "msedge.exe", "firefox.exe"), "Browser", "browser"),
        ]
        for needles, label, prof in rules:
            if any(n in blob for n in needles):
                app_name = label
                profile = prof
                break

        return {"activeApp": app_name, "profile": profile, "title": title}
    except Exception:
        return {"activeApp": "Desktop", "profile": "standard", "title": ""}

async def context_sender(ws):
    last = None
    while True:
        await asyncio.sleep(1.0)
        ctx = get_foreground_context()
        signature = (ctx["activeApp"], ctx["profile"], ctx["title"])
        if signature != last:
            last = signature
            payload = {"type": "context", **ctx}
            await ws.send(json.dumps(payload))

async def websocket_handler(ws, path):
    client_ip = "unknown"
    try:
        if ws.remote_address:
            client_ip = str(ws.remote_address[0])
    except Exception:
        pass

    authenticated = False
    device_name = "Android"
    context_task = None
    conn_id = id(ws)

    try:
        async for message in ws:
            try:
                obj = json.loads(message)
            except Exception:
                await ws.send(json.dumps({"type": "error", "error": "Invalid JSON"}))
                continue

            msg_type = obj.get("type")

            if not authenticated:
                if msg_type != "auth":
                    await ws.close(code=1008, reason="Authentication required")
                    return

                ok, token, device_name, error = validate_auth(obj, client_ip)
                if not ok:
                    await ws.send(json.dumps({"type": "auth", "ok": False, "error": error}))
                    await ws.close(code=1008, reason=error)
                    return

                authenticated = True
                connected_devices[conn_id] = {
                    "device": device_name,
                    "ip": client_ip,
                    "since": int(time.time()),
                }
                if ui:
                    ui.safe_refresh()

                await ws.send(json.dumps({
                    "type": "auth",
                    "ok": True,
                    "token": token,
                    "pcName": socket.gethostname(),
                    "version": VERSION,
                    "secure": True,
                }))

                context_task = asyncio.create_task(context_sender(ws))
                continue

            if msg_type == "event":
                try:
                    process_event(obj.get("event") or {})
                except Exception as exc:
                    await ws.send(json.dumps({"type": "status", "message": f"Input error: {exc}"}))
            elif msg_type == "ping":
                await ws.send(json.dumps({"type": "pong", "ts": obj.get("ts", 0)}))

    except Exception:
        pass
    finally:
        if context_task:
            context_task.cancel()
        connected_devices.pop(conn_id, None)
        if ui:
            ui.safe_refresh()

def discovery_loop():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("0.0.0.0", DISCOVERY_PORT))
        sock.settimeout(1.0)

        while not shutdown_event.is_set():
            try:
                data, addr = sock.recvfrom(4096)
            except socket.timeout:
                continue
            except Exception:
                break

            if data.strip() != b"WIRELESSKEY_DISCOVER_V3":
                continue

            payload = json.dumps({
                "name": socket.gethostname(),
                "pcName": socket.gethostname(),
                "ip": get_local_ip_for(addr[0]),
                "port": ACTIVE_PORT,
                "version": VERSION,
                "secure": True,
                "fingerprint": CERT_FINGERPRINT,
            }).encode("utf-8")

            try:
                sock.sendto(payload, addr)
            except Exception:
                pass
    finally:
        sock.close()

async def server_main():
    global ACTIVE_PORT
    ensure_certificate()

    ssl_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ssl_context.minimum_version = ssl.TLSVersion.TLSv1_2
    ssl_context.load_cert_chain(str(CERT_FILE), str(KEY_FILE))

    server = None
    last_error = None
    for port in range(PORT_START, PORT_END + 1):
        try:
            server = await serve(
                websocket_handler,
                "0.0.0.0",
                port,
                ssl=ssl_context,
                ping_interval=10,
                ping_timeout=10,
                max_size=1024 * 1024,
                compression=None,
            )
            ACTIVE_PORT = port
            break
        except OSError as exc:
            last_error = exc

    if server is None:
        raise OSError(f"No free port between {PORT_START} and {PORT_END}: {last_error}")

    threading.Thread(target=discovery_loop, daemon=True).start()
    server_ready.set()
    if ui:
        ui.safe_refresh()

    try:
        while not shutdown_event.is_set():
            await asyncio.sleep(0.25)
    finally:
        server.close()
        await server.wait_closed()

def run_server():
    try:
        asyncio.run(server_main())
    except Exception as exc:
        server_ready.set()
        if ui:
            ui.safe_error(str(exc))

def create_tray_image():
    img = Image.new("RGB", (64, 64), "#0b1220")
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((7, 12, 57, 50), radius=8, fill="#1d4ed8")
    for x in (14, 24, 34, 44):
        d.rectangle((x, 20, x + 6, 26), fill="white")
        d.rectangle((x, 32, x + 6, 38), fill="white")
    d.rectangle((20, 42, 44, 46), fill="white")
    return img

class ReceiverUI:
    def __init__(self):
        import tkinter as tk
        from tkinter import messagebox
        self.tk = tk
        self.messagebox = messagebox
        self.root = tk.Tk()
        self.root.title("WirelessKey Receiver 3.0")
        self.root.geometry("440x430")
        self.root.minsize(420, 400)
        self.root.configure(bg="#0b1220")
        self.root.protocol("WM_DELETE_WINDOW", self.hide)

        self.code_var = tk.StringVar()
        self.ip_var = tk.StringVar()
        self.port_var = tk.StringVar()
        self.device_var = tk.StringVar()
        self.status_var = tk.StringVar()
        self.fingerprint_var = tk.StringVar()

        self._build()
        self.safe_refresh()

    def _label(self, parent, text, size=10, color="#94a3b8"):
        return self.tk.Label(parent, text=text, font=("Segoe UI", size), fg=color, bg="#0b1220")

    def _build(self):
        tk = self.tk
        title = tk.Label(
            self.root,
            text="WirelessKey 3.0",
            font=("Segoe UI", 22, "bold"),
            fg="white",
            bg="#0b1220",
        )
        title.pack(pady=(20, 4))

        sub = tk.Label(
            self.root,
            text="Encrypted local keyboard & precision touchpad receiver",
            font=("Segoe UI", 9),
            fg="#60a5fa",
            bg="#0b1220",
        )
        sub.pack(pady=(0, 18))

        card = tk.Frame(self.root, bg="#111827", padx=18, pady=14)
        card.pack(fill="x", padx=20)

        def row(label, var, big=False):
            r = tk.Frame(card, bg="#111827")
            r.pack(fill="x", pady=5)
            tk.Label(r, text=label, width=15, anchor="w", fg="#94a3b8", bg="#111827", font=("Segoe UI", 9)).pack(side="left")
            tk.Label(
                r,
                textvariable=var,
                anchor="w",
                fg="white",
                bg="#111827",
                font=("Consolas", 18 if big else 10, "bold" if big else "normal"),
            ).pack(side="left", fill="x", expand=True)

        row("Status", self.status_var)
        row("PC name", tk.StringVar(value=socket.gethostname()))
        row("PC IP", self.ip_var)
        row("Secure port", self.port_var)
        row("Pairing code", self.code_var, True)
        row("Connected", self.device_var)

        fp = tk.Label(
            card,
            textvariable=self.fingerprint_var,
            anchor="w",
            justify="left",
            fg="#64748b",
            bg="#111827",
            font=("Consolas", 7),
            wraplength=380,
        )
        fp.pack(fill="x", pady=(8, 0))

        buttons = tk.Frame(self.root, bg="#0b1220")
        buttons.pack(fill="x", padx=20, pady=16)

        tk.Button(
            buttons,
            text="New Pairing Code",
            command=lambda: rotate_pair_code("manual"),
            bg="#1d4ed8",
            fg="white",
            activebackground="#2563eb",
            activeforeground="white",
            relief="flat",
            padx=12,
            pady=8,
        ).pack(side="left", expand=True, fill="x", padx=(0, 5))

        tk.Button(
            buttons,
            text="Revoke All Phones",
            command=self.revoke_all,
            bg="#7f1d1d",
            fg="white",
            activebackground="#991b1b",
            activeforeground="white",
            relief="flat",
            padx=12,
            pady=8,
        ).pack(side="left", expand=True, fill="x", padx=(5, 0))

        note = tk.Label(
            self.root,
            text="On Android: Find PC → select this PC → enter pairing code → Connect\n"
                 "Traffic is encrypted and the receiver certificate is pinned on the phone.",
            fg="#94a3b8",
            bg="#0b1220",
            justify="left",
            font=("Segoe UI", 9),
        )
        note.pack(fill="x", padx=22, pady=(2, 8))

        tk.Button(
            self.root,
            text="Hide to tray",
            command=self.hide,
            bg="#172033",
            fg="#cbd5e1",
            activebackground="#1f2937",
            activeforeground="white",
            relief="flat",
            pady=6,
        ).pack(fill="x", padx=20, pady=(4, 18))

    def revoke_all(self):
        if self.messagebox.askyesno("WirelessKey", "Revoke all trusted phones? They will need to pair again."):
            trusted_devices.clear()
            save_trusted()
            self.safe_refresh()

    def safe_refresh(self):
        try:
            self.root.after(0, self.refresh)
        except Exception:
            pass

    def refresh(self):
        ip = get_local_ip_for()
        code = current_pair_code()
        devices = list(connected_devices.values())
        device_text = ", ".join(d["device"] for d in devices) if devices else "None"
        status = "Running securely" if server_ready.is_set() else "Starting..."
        self.ip_var.set(ip)
        self.port_var.set(str(ACTIVE_PORT))
        self.code_var.set(code)
        self.device_var.set(device_text)
        self.status_var.set(status)
        self.fingerprint_var.set("Certificate SHA-256: " + (CERT_FINGERPRINT or "initializing..."))

    def safe_error(self, message):
        try:
            self.root.after(0, lambda: self.messagebox.showerror("WirelessKey Receiver", message))
        except Exception:
            pass

    def hide(self):
        self.root.withdraw()

    def show(self):
        self.root.after(0, self.root.deiconify)
        self.root.after(0, self.root.lift)

    def run(self):
        self.root.mainloop()

def tray_show(icon, item):
    if ui:
        ui.show()

def tray_new_code(icon, item):
    rotate_pair_code("tray")

def tray_exit(icon, item):
    shutdown_event.set()
    try:
        icon.stop()
    except Exception:
        pass
    if ui:
        try:
            ui.root.after(0, ui.root.destroy)
        except Exception:
            pass

def start_tray():
    global tray_icon
    menu = pystray.Menu(
        pystray.MenuItem("Show WirelessKey", tray_show, default=True),
        pystray.MenuItem("New Pairing Code", tray_new_code),
        pystray.MenuItem("Exit", tray_exit),
    )
    tray_icon = pystray.Icon("WirelessKey", create_tray_image(), "WirelessKey 3.0", menu)
    tray_icon.run()

def main():
    global ui
    ensure_certificate()
    rotate_pair_code()

    ui = ReceiverUI()
    threading.Thread(target=run_server, daemon=True).start()
    threading.Thread(target=start_tray, daemon=True).start()

    ui.run()
    shutdown_event.set()

if __name__ == "__main__":
    main()

import asyncio
import ctypes
import json
import os
import random
import secrets
import socket
import sys
import threading
import time
from collections import defaultdict, deque
from pathlib import Path

from websockets.legacy.server import serve

APP_NAME = "WirelessKey"
VERSION = "2.0"
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

state_lock = threading.Lock()
ACTIVE_PORT = PORT_START
PAIR_CODE = ""
PAIR_CREATED = 0.0
failed_attempts = defaultdict(deque)

def app_data_dir():
    base = os.environ.get("APPDATA") or str(Path.home())
    p = Path(base) / APP_NAME
    p.mkdir(parents=True, exist_ok=True)
    return p

TRUST_FILE = app_data_dir() / "trusted_devices.json"

def load_trusted():
    try:
        data = json.loads(TRUST_FILE.read_text(encoding="utf-8"))
        if isinstance(data, dict):
            return data
    except Exception:
        pass
    return {}

trusted_devices = load_trusted()

def save_trusted():
    try:
        tmp = TRUST_FILE.with_suffix(".tmp")
        tmp.write_text(json.dumps(trusted_devices, indent=2), encoding="utf-8")
        tmp.replace(TRUST_FILE)
    except Exception:
        pass

def rotate_pair_code(reason=""):
    global PAIR_CODE, PAIR_CREATED
    with state_lock:
        PAIR_CODE = f"{random.randint(0, 999999):06d}"
        PAIR_CREATED = time.time()
    if reason:
        print(f"[Pairing] New code generated ({reason}): {PAIR_CODE}")
    return PAIR_CODE

def current_pair_code():
    with state_lock:
        if not PAIR_CODE or time.time() - PAIR_CREATED > PAIR_TTL_SECONDS:
            return rotate_pair_code("previous code expired")
        return PAIR_CODE

def pair_code_valid(code):
    with state_lock:
        if not PAIR_CODE:
            return False
        if time.time() - PAIR_CREATED > PAIR_TTL_SECONDS:
            return False
        return secrets.compare_digest(str(code), PAIR_CODE)

def _send_key(vk, up=False):
    flags = KEYEVENTF_KEYUP if up else 0
    ii = INPUT_I()
    ii.ki = KEYBDINPUT(vk, 0, flags, 0, None)
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
    ii.mi = MOUSEINPUT(
        0, 0, ctypes.c_ulong(int(delta)).value, MOUSEEVENTF_WHEEL, 0, None
    )
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
        mouse_button(
            str(event.get("button", "left")),
            str(event.get("action", "click"))
        )
    elif etype == "wheel":
        mouse_wheel(event.get("delta", 0))
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
    q = failed_attempts[ip]
    q.append(time.time())

def validate_auth(obj, client_ip):
    token = str(obj.get("token", "") or "")
    code = str(obj.get("code", "") or "")
    device = str(obj.get("device", "Android"))[:80]

    if token and token in trusted_devices:
        trusted_devices[token]["lastSeen"] = int(time.time())
        save_trusted()
        return True, token, device, ""

    if is_rate_limited(client_ip):
        return False, "", device, "Too many incorrect attempts. Try again in a minute."

    if not pair_code_valid(code):
        mark_failed(client_ip)
        if time.time() - PAIR_CREATED > PAIR_TTL_SECONDS:
            rotate_pair_code("expired")
            return False, "", device, "Pairing code expired. Use the new code shown on PC."
        return False, "", device, "Wrong pairing code"

    new_token = secrets.token_urlsafe(32)
    trusted_devices[new_token] = {
        "device": device,
        "created": int(time.time()),
        "lastSeen": int(time.time()),
    }
    save_trusted()
    return True, new_token, device, ""

async def websocket_handler(ws, path):
    client_ip = "unknown"
    try:
        if ws.remote_address:
            client_ip = str(ws.remote_address[0])
    except Exception:
        pass

    authenticated = False
    device_name = "Android"

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
                    await ws.send(json.dumps({
                        "type": "auth",
                        "ok": False,
                        "error": error
                    }))
                    await ws.close(code=1008, reason=error)
                    return

                authenticated = True
                await ws.send(json.dumps({
                    "type": "auth",
                    "ok": True,
                    "token": token,
                    "pcName": socket.gethostname(),
                    "version": VERSION
                }))

                print(f"[Connected] {device_name} from {client_ip}")
                continue

            if msg_type == "event":
                try:
                    event = obj.get("event") or {}
                    process_event(event)
                except Exception as exc:
                    await ws.send(json.dumps({
                        "type": "status",
                        "message": f"Input error: {exc}"
                    }))

    except Exception:
        pass
    finally:
        if authenticated:
            print(f"[Disconnected] {device_name} from {client_ip}")

def discovery_loop(stop_event):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("0.0.0.0", DISCOVERY_PORT))
        sock.settimeout(1.0)

        while not stop_event.is_set():
            try:
                data, addr = sock.recvfrom(2048)
            except socket.timeout:
                continue
            except Exception:
                break

            if data.strip() != b"WIRELESSKEY_DISCOVER_V2":
                continue

            ip = get_local_ip_for(addr[0])
            payload = json.dumps({
                "name": socket.gethostname(),
                "pcName": socket.gethostname(),
                "ip": ip,
                "port": ACTIVE_PORT,
                "version": VERSION
            }).encode("utf-8")

            try:
                sock.sendto(payload, addr)
            except Exception:
                pass
    finally:
        sock.close()

async def start_server_with_fallback():
    global ACTIVE_PORT
    last_error = None

    for port in range(PORT_START, PORT_END + 1):
        try:
            server = await serve(
                websocket_handler,
                "0.0.0.0",
                port,
                ping_interval=10,
                ping_timeout=10,
                max_size=1024 * 1024,
                compression=None,
            )
            ACTIVE_PORT = port
            return server
        except OSError as exc:
            last_error = exc

    raise OSError(f"No free port between {PORT_START} and {PORT_END}: {last_error}")

def print_banner():
    code = current_pair_code()
    ip = get_local_ip_for()

    os.system("title WirelessKey Receiver 2.0")

    print("")
    print("====================================================")
    print("              WirelessKey Receiver 2.0")
    print("====================================================")
    print(f"PC name        : {socket.gethostname()}")
    print(f"PC IP          : {ip}")
    print(f"WebSocket port : {ACTIVE_PORT}")
    print(f"Pairing code   : {code}")
    print("Code validity  : 30 minutes")
    print("")
    print("Android:")
    print("  1. Open WirelessKey 2.0")
    print("  2. Tap Find PC (recommended)")
    print("  3. Select this computer")
    print(f"  4. Enter pairing code {code}")
    print("  5. Tap Connect")
    print("")
    print("Trusted phones reconnect automatically with a saved token.")
    print("Keep this window open while using WirelessKey.")
    print("Press Ctrl+C to stop.")
    print("====================================================")
    print("")

async def main():
    rotate_pair_code()
    server = await start_server_with_fallback()

    stop_event = threading.Event()
    thread = threading.Thread(
        target=discovery_loop,
        args=(stop_event,),
        daemon=True
    )
    thread.start()

    print_banner()

    try:
        await asyncio.Future()
    except asyncio.CancelledError:
        pass
    finally:
        stop_event.set()
        server.close()
        await server.wait_closed()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nWirelessKey Receiver stopped.")
    except Exception as exc:
        print(f"\nReceiver failed: {exc}")
        input("Press Enter to close...")

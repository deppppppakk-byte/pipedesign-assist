import ctypes
import json
import random
import socket
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 8765
PAIR_CODE = f"{random.randint(0, 999999):06d}"

if sys.platform != "win32":
    raise SystemExit("WirelessKeyReceiver is for Windows only.")

user32 = ctypes.windll.user32
ULONG_PTR = ctypes.POINTER(ctypes.c_ulong)

class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", ctypes.c_ushort), ("wScan", ctypes.c_ushort),
                ("dwFlags", ctypes.c_ulong), ("time", ctypes.c_ulong),
                ("dwExtraInfo", ULONG_PTR)]

class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", ctypes.c_long), ("dy", ctypes.c_long),
                ("mouseData", ctypes.c_ulong), ("dwFlags", ctypes.c_ulong),
                ("time", ctypes.c_ulong), ("dwExtraInfo", ULONG_PTR)]

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
    "RIGHT": 0x27, "DOWN": 0x28, "PRINTSCREEN": 0x2C, "INSERT": 0x2D,
    "DELETE": 0x2E, "WIN": 0x5B, "MENU": 0x5D,
    "F1": 0x70, "F2": 0x71, "F3": 0x72, "F4": 0x73,
    "F5": 0x74, "F6": 0x75, "F7": 0x76, "F8": 0x77,
    "F9": 0x78, "F10": 0x79, "F11": 0x7A, "F12": 0x7B,
    "VOLUME_MUTE": 0xAD, "VOLUME_DOWN": 0xAE, "VOLUME_UP": 0xAF,
    "MEDIA_NEXT": 0xB0, "MEDIA_PREV": 0xB1, "MEDIA_STOP": 0xB2,
    "MEDIA_PLAY": 0xB3,
}

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
    for ch in text:
        if ord(ch) <= 0xFFFF:
            _send_unicode_unit(ord(ch))
        else:
            encoded = ch.encode("utf-16-le")
            for i in range(0, len(encoded), 2):
                _send_unicode_unit(int.from_bytes(encoded[i:i+2], "little"))

def press_key(name, modifiers=None):
    modifiers = modifiers or []
    pressed = []
    mod_map = {"CTRL": 0x11, "ALT": 0x12, "SHIFT": 0x10, "WIN": 0x5B}
    for mod in modifiers:
        vk = mod_map.get(str(mod).upper())
        if vk:
            pressed.append(vk)
            _send_key(vk)

    upper = str(name).upper()
    vk = VK.get(upper)
    if vk is None and len(str(name)) == 1:
        scan = user32.VkKeyScanW(ord(str(name)))
        if scan != -1:
            vk = scan & 0xFF
    if vk is not None:
        _send_key(vk)
        _send_key(vk, True)

    for vk in reversed(pressed):
        _send_key(vk, True)

def mouse_move(dx, dy):
    ii = INPUT_I()
    ii.mi = MOUSEINPUT(int(dx), int(dy), 0, MOUSEEVENTF_MOVE, 0, None)
    inp = INPUT(INPUT_MOUSE, ii)
    user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(inp))

def mouse_button(button, action="click"):
    mapping = {
        "left": (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP),
        "right": (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP),
        "middle": (MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP),
    }
    down_flag, up_flag = mapping.get(button, mapping["left"])
    flags = []
    if action in ("down", "click"):
        flags.append(down_flag)
    if action in ("up", "click"):
        flags.append(up_flag)
    for flag in flags:
        ii = INPUT_I()
        ii.mi = MOUSEINPUT(0, 0, 0, flag, 0, None)
        inp = INPUT(INPUT_MOUSE, ii)
        user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(inp))

def mouse_wheel(delta):
    ii = INPUT_I()
    ii.mi = MOUSEINPUT(0, 0, ctypes.c_ulong(int(delta)).value, MOUSEEVENTF_WHEEL, 0, None)
    inp = INPUT(INPUT_MOUSE, ii)
    user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(inp))

def local_ip():
    for probe in (("8.8.8.8", 80), ("1.1.1.1", 80)):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(probe)
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

class Handler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        return

    def _json(self, status, payload):
        data = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/health":
            self._json(200, {"ok": True, "name": "WirelessKeyReceiver", "version": "1.1"})
        else:
            self._json(404, {"ok": False})

    def do_POST(self):
        if self.path != "/event":
            self._json(404, {"ok": False})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            data = json.loads(self.rfile.read(length) or b"{}")
            if str(data.get("code", "")) != PAIR_CODE:
                self._json(403, {"ok": False, "error": "Invalid pairing code"})
                return

            event = data.get("event") or {}
            etype = event.get("type")

            if etype == "text":
                type_text(str(event.get("text", ""))[:200])
            elif etype == "key":
                press_key(str(event.get("key", "")), event.get("modifiers", []))
            elif etype == "move":
                mouse_move(event.get("dx", 0), event.get("dy", 0))
            elif etype == "mouse":
                mouse_button(str(event.get("button", "left")), str(event.get("action", "click")))
            elif etype == "wheel":
                mouse_wheel(event.get("delta", 0))
            else:
                self._json(400, {"ok": False, "error": "Unknown event"})
                return

            self._json(200, {"ok": True})
        except Exception as exc:
            self._json(500, {"ok": False, "error": str(exc)})

def main():
    ip = local_ip()
    print("")
    print("==========================================")
    print("          WirelessKey Receiver v1.1")
    print("==========================================")
    print(f"Laptop IP      : {ip}")
    print(f"Port           : {PORT}")
    print(f"Pairing code   : {PAIR_CODE}")
    print("")
    print("On the Android app:")
    print(f"  Laptop IP = {ip}")
    print(f"  Code      = {PAIR_CODE}")
    print("")
    print("IMPORTANT: phone and laptop must be on the same Wi-Fi.")
    print("If Windows Firewall asks, allow PRIVATE networks.")
    print("Keep this window open while using WirelessKey.")
    print("Press Ctrl+C to stop.")
    print("==========================================")
    print("")

    try:
        server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    except OSError as exc:
        print(f"Could not start receiver on port {PORT}: {exc}")
        input("Press Enter to close...")
        return

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()

if __name__ == "__main__":
    main()

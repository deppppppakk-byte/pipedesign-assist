using System.Runtime.InteropServices;

namespace WirelessKey.NativeReceiver;

internal static class InputInjector
{
    private const uint INPUT_MOUSE = 0;
    private const uint INPUT_KEYBOARD = 1;
    private const uint KEYEVENTF_KEYUP = 0x0002;
    private const uint KEYEVENTF_UNICODE = 0x0004;

    private const uint MOUSEEVENTF_MOVE = 0x0001;
    private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    private const uint MOUSEEVENTF_LEFTUP = 0x0004;
    private const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
    private const uint MOUSEEVENTF_RIGHTUP = 0x0010;
    private const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
    private const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
    private const uint MOUSEEVENTF_WHEEL = 0x0800;
    private const uint MOUSEEVENTF_HWHEEL = 0x01000;

    private static readonly Dictionary<string, ushort> Vk = new(StringComparer.OrdinalIgnoreCase)
    {
        ["BACKSPACE"]=0x08, ["TAB"]=0x09, ["ENTER"]=0x0D, ["SHIFT"]=0x10,
        ["CTRL"]=0x11, ["ALT"]=0x12, ["CAPSLOCK"]=0x14, ["ESC"]=0x1B,
        ["SPACE"]=0x20, ["PAGEUP"]=0x21, ["PAGEDOWN"]=0x22, ["END"]=0x23,
        ["HOME"]=0x24, ["LEFT"]=0x25, ["UP"]=0x26, ["RIGHT"]=0x27,
        ["DOWN"]=0x28, ["PRINTSCREEN"]=0x2C, ["INSERT"]=0x2D, ["DELETE"]=0x2E,
        ["WIN"]=0x5B, ["MENU"]=0x5D,
        ["F1"]=0x70, ["F2"]=0x71, ["F3"]=0x72, ["F4"]=0x73, ["F5"]=0x74,
        ["F6"]=0x75, ["F7"]=0x76, ["F8"]=0x77, ["F9"]=0x78, ["F10"]=0x79,
        ["F11"]=0x7A, ["F12"]=0x7B,
        ["VOLUME_MUTE"]=0xAD, ["VOLUME_DOWN"]=0xAE, ["VOLUME_UP"]=0xAF,
        ["MEDIA_NEXT"]=0xB0, ["MEDIA_PREV"]=0xB1, ["MEDIA_STOP"]=0xB2,
        ["MEDIA_PLAY"]=0xB3,
    };

    private static readonly Dictionary<string, ushort> ModifierVk = new(StringComparer.OrdinalIgnoreCase)
    {
        ["CTRL"]=0x11, ["ALT"]=0x12, ["SHIFT"]=0x10, ["WIN"]=0x5B
    };

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public uint type;
        public INPUTUNION U;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct INPUTUNION
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int dx;
        public int dy;
        public uint mouseData;
        public uint dwFlags;
        public uint time;
        public nint dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT
    {
        public ushort wVk;
        public ushort wScan;
        public uint dwFlags;
        public uint time;
        public nint dwExtraInfo;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern short VkKeyScan(char ch);

    private static void SendKey(ushort vk, bool up = false)
    {
        var input = new INPUT
        {
            type = INPUT_KEYBOARD,
            U = new INPUTUNION
            {
                ki = new KEYBDINPUT
                {
                    wVk = vk,
                    dwFlags = up ? KEYEVENTF_KEYUP : 0
                }
            }
        };
        SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>());
    }

    private static void SendUnicodeUnit(ushort unit)
    {
        var down = new INPUT
        {
            type = INPUT_KEYBOARD,
            U = new INPUTUNION { ki = new KEYBDINPUT { wScan = unit, dwFlags = KEYEVENTF_UNICODE } }
        };
        var up = down;
        up.U.ki.dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP;
        SendInput(2, new[] { down, up }, Marshal.SizeOf<INPUT>());
    }

    public static void TypeText(string text)
    {
        foreach (var ch in text ?? string.Empty)
            SendUnicodeUnit(ch);
    }

    public static void PressKey(string key, IEnumerable<string>? modifiers)
    {
        var pressed = new List<ushort>();
        var modifierSet = new HashSet<string>(modifiers ?? Array.Empty<string>(), StringComparer.OrdinalIgnoreCase);

        foreach (var mod in modifierSet)
        {
            if (ModifierVk.TryGetValue(mod, out var mvk))
            {
                SendKey(mvk);
                pressed.Add(mvk);
            }
        }

        ushort vk = 0;
        var implicitShift = false;

        if (!Vk.TryGetValue(key, out vk) && !string.IsNullOrEmpty(key) && key.Length == 1)
        {
            var ch = key[0];

            if (char.IsLetter(ch))
            {
                vk = (ushort)char.ToUpperInvariant(ch);
            }
            else if (char.IsDigit(ch))
            {
                vk = ch;
            }
            else
            {
                var scan = VkKeyScan(ch);
                if (scan != -1)
                {
                    vk = (ushort)(scan & 0xff);
                    var shiftState = (scan >> 8) & 0xff;
                    if ((shiftState & 1) != 0 && !modifierSet.Contains("SHIFT"))
                    {
                        SendKey(ModifierVk["SHIFT"]);
                        implicitShift = true;
                    }
                }
            }
        }

        if (vk != 0)
        {
            SendKey(vk);
            SendKey(vk, true);
        }

        if (implicitShift)
            SendKey(ModifierVk["SHIFT"], true);

        for (var i = pressed.Count - 1; i >= 0; i--)
            SendKey(pressed[i], true);
    }

    private static void SendMouse(uint flags, int dx = 0, int dy = 0, uint data = 0)
    {
        var input = new INPUT
        {
            type = INPUT_MOUSE,
            U = new INPUTUNION
            {
                mi = new MOUSEINPUT { dx = dx, dy = dy, mouseData = data, dwFlags = flags }
            }
        };
        SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>());
    }

    public static void Move(double dx, double dy)
        => SendMouse(MOUSEEVENTF_MOVE, (int)Math.Round(dx), (int)Math.Round(dy));

    public static void Wheel(int delta) => SendMouse(MOUSEEVENTF_WHEEL, data: unchecked((uint)delta));
    public static void HWheel(int delta) => SendMouse(MOUSEEVENTF_HWHEEL, data: unchecked((uint)delta));

    public static void MouseButton(string button, string action)
    {
        var pair = button.ToLowerInvariant() switch
        {
            "right" => (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP),
            "middle" => (MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP),
            _ => (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP)
        };

        switch (action.ToLowerInvariant())
        {
            case "down":
                SendMouse(pair.Item1);
                break;
            case "up":
                SendMouse(pair.Item2);
                break;
            case "double":
                SendMouse(pair.Item1); SendMouse(pair.Item2);
                SendMouse(pair.Item1); SendMouse(pair.Item2);
                break;
            default:
                SendMouse(pair.Item1); SendMouse(pair.Item2);
                break;
        }
    }
}

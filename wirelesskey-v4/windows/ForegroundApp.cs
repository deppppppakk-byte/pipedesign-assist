using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace WirelessKey.NativeReceiver;

internal sealed record ForegroundInfo(string ActiveApp, string Profile, string Title);

internal static class ForegroundApp
{
    [DllImport("user32.dll")]
    private static extern nint GetForegroundWindow();

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetWindowText(nint hWnd, StringBuilder lpString, int nMaxCount);

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(nint hWnd, out uint processId);

    public static ForegroundInfo Read()
    {
        try
        {
            var hwnd = GetForegroundWindow();
            if (hwnd == 0)
                return new("Desktop", "standard", "");

            var titleBuffer = new StringBuilder(256);
            GetWindowText(hwnd, titleBuffer, titleBuffer.Capacity);
            var title = titleBuffer.ToString();

            GetWindowThreadProcessId(hwnd, out var pid);
            var process = pid == 0 ? "" : Process.GetProcessById((int)pid).ProcessName.ToLowerInvariant();
            var blob = (process + " " + title).ToLowerInvariant();

            if (blob.Contains("acad") || blob.Contains("autocad"))
                return new("AutoCAD", "autocad", title);
            if (blob.Contains("revit"))
                return new("Revit", "revit", title);
            if (blob.Contains("excel"))
                return new("Excel", "excel", title);
            if (blob.Contains("powerpnt") || blob.Contains("powerpoint"))
                return new("PowerPoint", "powerpoint", title);
            if (blob.Contains("winword") || blob.Contains("microsoft word"))
                return new("Word", "word", title);
            if (blob.Contains("code") || blob.Contains("visual studio code"))
                return new("VS Code", "vscode", title);
            if (blob.Contains("chrome") || blob.Contains("msedge") || blob.Contains("firefox"))
                return new("Browser", "browser", title);

            return new(string.IsNullOrWhiteSpace(process) ? "Desktop" : process, "standard", title);
        }
        catch
        {
            return new("Desktop", "standard", "");
        }
    }
}

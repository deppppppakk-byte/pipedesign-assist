namespace WirelessKey.NativeReceiver;

internal static class ClipboardBridge
{
    public static string GetText()
    {
        string result = "";
        Exception? error = null;

        var thread = new Thread(() =>
        {
            try
            {
                if (Clipboard.ContainsText())
                    result = Clipboard.GetText(TextDataFormat.UnicodeText);
            }
            catch (Exception ex)
            {
                error = ex;
            }
        });

        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        thread.Join(TimeSpan.FromSeconds(2));

        if (error != null) return "";
        return result.Length > 20000 ? result[..20000] : result;
    }

    public static void SetText(string? text)
    {
        var value = text ?? "";
        if (value.Length > 20000) value = value[..20000];

        var thread = new Thread(() =>
        {
            try
            {
                Clipboard.SetText(value, TextDataFormat.UnicodeText);
            }
            catch
            {
            }
        });

        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        thread.Join(TimeSpan.FromSeconds(2));
    }
}

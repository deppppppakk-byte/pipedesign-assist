namespace WirelessKey.NativeReceiver;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        if (args.Any(a => string.Equals(a, "--self-test", StringComparison.OrdinalIgnoreCase)))
        {
            try
            {
                NativeSelfTest.RunAsync().GetAwaiter().GetResult();
                Environment.ExitCode = 0;
            }
            catch (Exception ex)
            {
                try
                {
                    File.WriteAllText(Path.Combine(Path.GetTempPath(), "wirelesskey-v4-selftest-error.txt"), ex.ToString());
                }
                catch { }
                Environment.ExitCode = 1;
            }
            return;
        }

        ApplicationConfiguration.Initialize();

        using var startupCts = new CancellationTokenSource(TimeSpan.FromSeconds(12));
        var host = new ReceiverHost();

        try
        {
            host.StartAsync(startupCts.Token).GetAwaiter().GetResult();
        }
        catch (Exception ex)
        {
            MessageBox.Show(
                "WirelessKey could not start its secure receiver.\n\n" + ex.Message,
                "WirelessKey 4.1.1",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);
            return;
        }

        var form = new MainForm(host);
        if (args.Any(a => string.Equals(a, "--hidden", StringComparison.OrdinalIgnoreCase)))
            form.StartHidden = true;

        Application.Run(form);
        host.DisposeAsync().AsTask().GetAwaiter().GetResult();
    }
}

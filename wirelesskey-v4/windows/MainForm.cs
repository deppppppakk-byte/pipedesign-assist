using Microsoft.Win32;
using QRCoder;

namespace WirelessKey.NativeReceiver;

internal sealed class MainForm : Form
{
    private readonly ReceiverHost _host;
    private readonly NotifyIcon _tray = null!;
    private readonly Label _statusValue = new();
    private readonly Label _pcValue = new();
    private readonly Label _ipValue = new();
    private readonly Label _portValue = new();
    private readonly Label _codeValue = new();
    private readonly Label _devicesValue = new();
    private readonly Label _fingerprintValue = new();
    private readonly Button _startupButton = new();
    private bool _allowExit;

    public bool StartHidden { get; set; }

    public MainForm(ReceiverHost host)
    {
        _host = host;
        Text = "WirelessKey Receiver 4.0";
        Width = 520;
        Height = 500;
        MinimumSize = new Size(500, 460);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.FromArgb(8, 17, 31);
        ForeColor = Color.White;
        Font = new Font("Segoe UI", 9F);

        _host.StateChanged += HostStateChanged;
        _host.Error += HostError;

        BuildUi();

        var menu = new ContextMenuStrip();
        menu.Items.Add("Show WirelessKey", null, (_, _) => ShowFromTray());
        menu.Items.Add("New Pairing Code", null, (_, _) => _host.RotatePairCode());
        menu.Items.Add("Show Pairing QR", null, (_, _) => ShowPairingQr());
        menu.Items.Add("Start with Windows", null, (_, _) => ToggleStartup());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Exit", null, (_, _) =>
        {
            _allowExit = true;
            _tray.Visible = false;
            Close();
        });

        _tray = new NotifyIcon
        {
            Icon = SystemIcons.Application,
            Text = "WirelessKey 4.1.1",
            Visible = true,
            ContextMenuStrip = menu
        };
        _tray.DoubleClick += (_, _) => ShowFromTray();
        RefreshState();

        Shown += (_, _) =>
        {
            if (StartHidden) Hide();
        };
        FormClosing += OnFormClosing;
    }

    private void BuildUi()
    {
        var title = new Label
        {
            Text = "WirelessKey 4.1.1",
            Font = new Font("Segoe UI", 24F, FontStyle.Bold),
            ForeColor = Color.White,
            AutoSize = true,
            Left = 24,
            Top = 20
        };
        Controls.Add(title);

        var subtitle = new Label
        {
            Text = "Native encrypted keyboard & precision touchpad receiver",
            Font = new Font("Segoe UI", 9.5F),
            ForeColor = Color.FromArgb(96, 165, 250),
            AutoSize = true,
            Left = 26,
            Top = 63
        };
        Controls.Add(subtitle);

        var card = new Panel
        {
            Left = 22,
            Top = 95,
            Width = ClientSize.Width - 44,
            Height = 235,
            BackColor = Color.FromArgb(17, 24, 39),
            Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top
        };
        Controls.Add(card);

        AddRow(card, "Status", _statusValue, 16);
        AddRow(card, "PC name", _pcValue, 48);
        AddRow(card, "PC IP", _ipValue, 80);
        AddRow(card, "Secure port", _portValue, 112);
        AddRow(card, "Pairing code", _codeValue, 144, true);
        AddRow(card, "Connected", _devicesValue, 184);

        _fingerprintValue.Left = 16;
        _fingerprintValue.Top = 214;
        _fingerprintValue.Width = card.Width - 32;
        _fingerprintValue.Height = 18;
        _fingerprintValue.Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Bottom;
        _fingerprintValue.ForeColor = Color.FromArgb(100, 116, 139);
        _fingerprintValue.Font = new Font("Consolas", 7.5F);
        card.Controls.Add(_fingerprintValue);

        var newCode = MakeButton("New Code", Color.FromArgb(29, 78, 216));
        newCode.Left = 22;
        newCode.Top = 348;
        newCode.Width = 105;
        newCode.Click += (_, _) => _host.RotatePairCode();
        Controls.Add(newCode);

        var qrPair = MakeButton("QR Pair", Color.FromArgb(6, 95, 70));
        qrPair.Left = 135;
        qrPair.Top = 348;
        qrPair.Width = 90;
        qrPair.Click += (_, _) => ShowPairingQr();
        Controls.Add(qrPair);

        var revoke = MakeButton("Revoke Phones", Color.FromArgb(127, 29, 29));
        revoke.Left = 233;
        revoke.Top = 348;
        revoke.Width = 115;
        revoke.Click += (_, _) =>
        {
            var result = MessageBox.Show(
                "Revoke every trusted phone? They will need to pair again.",
                "WirelessKey 4.1.1",
                MessageBoxButtons.YesNo,
                MessageBoxIcon.Question);
            if (result == DialogResult.Yes) _host.RevokeAll();
        };
        Controls.Add(revoke);

        _startupButton.Text = "";
        StyleButton(_startupButton, Color.FromArgb(22, 32, 51));
        _startupButton.Left = 356;
        _startupButton.Top = 348;
        _startupButton.Width = 134;
        _startupButton.Click += (_, _) => ToggleStartup();
        Controls.Add(_startupButton);
        RefreshStartupButton();

        var note = new Label
        {
            Left = 24,
            Top = 399,
            Width = ClientSize.Width - 48,
            Height = 48,
            Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top,
            ForeColor = Color.FromArgb(148, 163, 184),
            Text = "Android: Find PC → select this PC → enter pairing code → Connect.\r\n" +
                   "Traffic is encrypted with TLS; the phone pins this PC's certificate identity."
        };
        Controls.Add(note);

        Resize += (_, _) =>
        {
            card.Width = ClientSize.Width - 44;
            note.Width = ClientSize.Width - 48;
            _startupButton.Left = Math.Max(356, ClientSize.Width - 156);
        };
    }

    private static Button MakeButton(string text, Color color)
    {
        var b = new Button { Text = text };
        StyleButton(b, color);
        return b;
    }

    private static void StyleButton(Button b, Color color)
    {
        b.Height = 38;
        b.FlatStyle = FlatStyle.Flat;
        b.FlatAppearance.BorderSize = 0;
        b.BackColor = color;
        b.ForeColor = Color.White;
        b.Font = new Font("Segoe UI", 9F, FontStyle.Bold);
        b.Cursor = Cursors.Hand;
    }

    private static void AddRow(Control parent, string name, Label value, int top, bool large = false)
    {
        var nameLabel = new Label
        {
            Text = name,
            Left = 16,
            Top = top,
            Width = 105,
            Height = large ? 30 : 22,
            ForeColor = Color.FromArgb(148, 163, 184),
            TextAlign = ContentAlignment.MiddleLeft
        };
        parent.Controls.Add(nameLabel);

        value.Left = 125;
        value.Top = top;
        value.Width = parent.Width - 140;
        value.Height = large ? 32 : 22;
        value.Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top;
        value.ForeColor = Color.White;
        value.TextAlign = ContentAlignment.MiddleLeft;
        value.Font = large
            ? new Font("Consolas", 19F, FontStyle.Bold)
            : new Font("Consolas", 9.5F);
        parent.Controls.Add(value);
    }

    private void HostStateChanged()
    {
        if (IsDisposed) return;
        if (InvokeRequired) BeginInvoke(RefreshState);
        else RefreshState();
    }

    private void HostError(string message)
    {
        if (IsDisposed) return;
        if (InvokeRequired)
        {
            BeginInvoke(() => HostError(message));
            return;
        }
        _statusValue.Text = "Error: " + message;
        _statusValue.ForeColor = Color.FromArgb(251, 113, 133);
    }

    private void RefreshState()
    {
        _statusValue.Text = "Running securely";
        _statusValue.ForeColor = Color.FromArgb(52, 211, 153);
        _pcValue.Text = Environment.MachineName;
        _ipValue.Text = _host.LocalIp;
        _portValue.Text = _host.Port.ToString();
        _codeValue.Text = _host.PairCode;

        var devices = _host.ConnectedDevices.Select(d => d.Device).Distinct().ToArray();
        _devicesValue.Text = devices.Length == 0 ? "None" : string.Join(", ", devices);

        var fp = _host.Fingerprint;
        if (fp.Length > 32)
            fp = fp[..16] + "…" + fp[^16..];
        _fingerprintValue.Text = "Certificate SHA-256: " + fp;

        _tray.Text = devices.Length == 0
            ? "WirelessKey 4.1.1 · waiting for phone"
            : $"WirelessKey 4.1.1 · {devices.Length} connected";
    }

    private void ShowPairingQr()
    {
        try
        {
            using var generator = new QRCodeGenerator();
            using var qrData = generator.CreateQrCode(_host.PairingPayload, QRCodeGenerator.ECCLevel.M);
            var pngQr = new PngByteQRCode(qrData);
            var bytes = pngQr.GetGraphic(10);

            using var stream = new MemoryStream(bytes);
            using var source = Image.FromStream(stream);
            var bitmap = new Bitmap(source);

            using var dialog = new Form
            {
                Text = "WirelessKey 4.1.1 · QR Pair",
                Width = 430,
                Height = 520,
                StartPosition = FormStartPosition.CenterParent,
                BackColor = Color.FromArgb(8,17,31),
                ForeColor = Color.White,
                FormBorderStyle = FormBorderStyle.FixedDialog,
                MaximizeBox = false,
                MinimizeBox = false
            };

            var title = new Label
            {
                Text = "Scan with WirelessKey on Android",
                Left = 30,
                Top = 18,
                Width = 350,
                Height = 26,
                ForeColor = Color.White,
                Font = new Font("Segoe UI", 12F, FontStyle.Bold),
                TextAlign = ContentAlignment.MiddleCenter
            };

            var picture = new PictureBox
            {
                Image = bitmap,
                SizeMode = PictureBoxSizeMode.Zoom,
                Left = 45,
                Top = 58,
                Width = 320,
                Height = 320,
                BackColor = Color.White
            };

            var code = new Label
            {
                Text = "Pairing code: " + _host.PairCode,
                Left = 40,
                Top = 392,
                Width = 340,
                Height = 32,
                ForeColor = Color.FromArgb(96,165,250),
                Font = new Font("Consolas", 16F, FontStyle.Bold),
                TextAlign = ContentAlignment.MiddleCenter
            };

            var note = new Label
            {
                Text = "The QR contains this PC's local address, certificate identity and temporary pairing code.",
                Left = 34,
                Top = 432,
                Width = 352,
                Height = 50,
                ForeColor = Color.FromArgb(148,163,184),
                TextAlign = ContentAlignment.MiddleCenter
            };

            dialog.Controls.Add(title);
            dialog.Controls.Add(picture);
            dialog.Controls.Add(code);
            dialog.Controls.Add(note);
            dialog.ShowDialog(this);
            picture.Image = null;
            bitmap.Dispose();
        }
        catch (Exception ex)
        {
            MessageBox.Show(ex.Message, "WirelessKey QR pairing", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    private void ShowFromTray()
    {
        Show();
        WindowState = FormWindowState.Normal;
        Activate();
    }

    private static string StartupPath => @"Software\Microsoft\Windows\CurrentVersion\Run";
    private static string StartupName => "WirelessKeyReceiver";

    private static bool IsStartupEnabled()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(StartupPath);
            var value = key?.GetValue(StartupName)?.ToString();
            return !string.IsNullOrWhiteSpace(value);
        }
        catch
        {
            return false;
        }
    }

    private void ToggleStartup()
    {
        try
        {
            using var key = Registry.CurrentUser.CreateSubKey(StartupPath);
            if (IsStartupEnabled())
                key.DeleteValue(StartupName, false);
            else
                key.SetValue(StartupName, $"\"{Application.ExecutablePath}\" --hidden");
            RefreshStartupButton();
        }
        catch (Exception ex)
        {
            MessageBox.Show(ex.Message, "WirelessKey startup", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    private void RefreshStartupButton()
    {
        _startupButton.Text = IsStartupEnabled() ? "Startup: ON" : "Startup: OFF";
        _startupButton.BackColor = IsStartupEnabled()
            ? Color.FromArgb(22, 101, 52)
            : Color.FromArgb(22, 32, 51);
    }

    private void OnFormClosing(object? sender, FormClosingEventArgs e)
    {
        if (_allowExit) return;
        e.Cancel = true;
        Hide();
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _host.StateChanged -= HostStateChanged;
            _host.Error -= HostError;
            _tray.Visible = false;
            _tray.Dispose();
        }
        base.Dispose(disposing);
    }
}

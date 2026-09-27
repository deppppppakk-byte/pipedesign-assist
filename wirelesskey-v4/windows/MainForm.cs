using System.Runtime.InteropServices;
using Microsoft.Win32;
using QRCoder;

namespace WirelessKey.NativeReceiver;

internal sealed class MainForm : Form
{
    private readonly ReceiverHost _host;
    private readonly NotifyIcon _tray = null!;
    private readonly Icon _appIcon;

    private readonly Label _statusValue = new();
    private readonly Label _pcValue = new();
    private readonly Label _endpointValue = new();
    private readonly Label _codeValue = new();
    private readonly Label _connectedValue = new();
    private readonly Label _fingerprintValue = new();
    private readonly ListView _trustedList = new();
    private readonly Button _startupButton = new();
    private readonly Button _renameButton = new();
    private readonly Button _revokeButton = new();

    private bool _allowExit;

    private static readonly Color Bg = Color.FromArgb(9, 12, 17);
    private static readonly Color Surface = Color.FromArgb(17, 22, 29);
    private static readonly Color Surface2 = Color.FromArgb(23, 29, 38);
    private static readonly Color Border = Color.FromArgb(41, 49, 60);
    private static readonly Color TextPrimary = Color.FromArgb(241, 245, 249);
    private static readonly Color TextMuted = Color.FromArgb(148, 163, 184);
    private static readonly Color Accent = Color.FromArgb(76, 132, 255);
    private static readonly Color Green = Color.FromArgb(69, 196, 139);
    private static readonly Color Red = Color.FromArgb(239, 104, 118);

    public bool StartHidden { get; set; }

    public MainForm(ReceiverHost host)
    {
        _host = host;
        _appIcon = CreateAppIcon();

        Text = "WirelessKey Receiver 4.4";
        Icon = _appIcon;
        Width = 850;
        Height = 570;
        MinimumSize = new Size(760, 510);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Bg;
        ForeColor = TextPrimary;
        Font = new Font("Segoe UI", 9F);
        FormBorderStyle = FormBorderStyle.Sizable;
        MaximizeBox = true;

        _host.StateChanged += HostStateChanged;
        _host.Error += HostError;

        BuildUi();

        var menu = new ContextMenuStrip
        {
            BackColor = Surface2,
            ForeColor = TextPrimary,
            Font = new Font("Segoe UI", 9F)
        };
        menu.Items.Add("Open WirelessKey", null, (_, _) => ShowFromTray());
        menu.Items.Add("Pair a phone…", null, (_, _) => ShowPairingQr());
        menu.Items.Add("New pairing code", null, (_, _) => _host.RotatePairCode());
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
            Icon = _appIcon,
            Text = "WirelessKey 4.4",
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
        SuspendLayout();

        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            BackColor = Bg,
            ColumnCount = 1,
            RowCount = 3,
            Padding = new Padding(18, 16, 18, 16)
        };
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 72));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 46));
        Controls.Add(root);

        root.Controls.Add(BuildHeader(), 0, 0);

        var content = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            BackColor = Bg,
            ColumnCount = 2,
            RowCount = 1,
            Margin = new Padding(0, 4, 0, 8)
        };
        content.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 300));
        content.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        root.Controls.Add(content, 0, 1);

        content.Controls.Add(BuildStatusCard(), 0, 0);
        content.Controls.Add(BuildDevicesCard(), 1, 0);

        var footer = new Panel { Dock = DockStyle.Fill, BackColor = Bg };
        var note = new Label
        {
            Dock = DockStyle.Fill,
            Text = "Encrypted local connection · certificate pinned · no cloud required",
            ForeColor = Color.FromArgb(104, 118, 136),
            TextAlign = ContentAlignment.MiddleLeft,
            Font = new Font("Segoe UI", 8.5F)
        };
        footer.Controls.Add(note);
        root.Controls.Add(footer, 0, 2);

        ResumeLayout(true);
    }

    private Control BuildHeader()
    {
        var header = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            BackColor = Bg,
            ColumnCount = 2,
            RowCount = 1
        };
        header.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        header.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 170));

        var copy = new Panel { Dock = DockStyle.Fill, BackColor = Bg };
        var title = new Label
        {
            Text = "WirelessKey",
            Font = new Font("Segoe UI", 24F, FontStyle.Bold),
            ForeColor = TextPrimary,
            AutoSize = true,
            Left = 0,
            Top = 4
        };
        var subtitle = new Label
        {
            Text = "RECEIVER 4.4  ·  PRECISION EDITION",
            Font = new Font("Segoe UI", 8F, FontStyle.Bold),
            ForeColor = Color.FromArgb(112, 164, 238),
            AutoSize = true,
            Left = 2,
            Top = 45
        };
        copy.Controls.Add(title);
        copy.Controls.Add(subtitle);
        header.Controls.Add(copy, 0, 0);

        var pair = MakeButton("Pair phone", Color.FromArgb(31, 59, 96), Accent);
        pair.Dock = DockStyle.Fill;
        pair.Margin = new Padding(8, 12, 0, 14);
        pair.Click += (_, _) => ShowPairingQr();
        header.Controls.Add(pair, 1, 0);

        return header;
    }

    private Control BuildStatusCard()
    {
        var card = CreateCard();
        card.Margin = new Padding(0, 0, 8, 0);

        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            BackColor = Surface,
            ColumnCount = 1,
            RowCount = 10,
            Padding = new Padding(16, 14, 16, 14)
        };
        card.Controls.Add(layout);

        layout.Controls.Add(Eyebrow("THIS PC"), 0, 0);
        layout.Controls.Add(MakeValue(_pcValue, 14F, true), 0, 1);
        layout.Controls.Add(MakeValue(_statusValue, 9.5F, true), 0, 2);

        layout.Controls.Add(Eyebrow("SECURE ENDPOINT"), 0, 3);
        layout.Controls.Add(MakeValue(_endpointValue, 9.2F, false), 0, 4);

        layout.Controls.Add(Eyebrow("PAIRING CODE"), 0, 5);
        _codeValue.Font = new Font("Consolas", 22F, FontStyle.Bold);
        _codeValue.ForeColor = Color.FromArgb(194, 220, 255);
        _codeValue.Dock = DockStyle.Fill;
        _codeValue.TextAlign = ContentAlignment.MiddleLeft;
        layout.Controls.Add(_codeValue, 0, 6);

        var actionRow = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 2,
            BackColor = Surface,
            Margin = new Padding(0, 5, 0, 2)
        };
        actionRow.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        actionRow.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));

        var newCode = MakeButton("New code", Surface2, Border);
        newCode.Margin = new Padding(0, 0, 4, 0);
        newCode.Click += (_, _) => _host.RotatePairCode();

        _startupButton.Margin = new Padding(4, 0, 0, 0);
        StyleButton(_startupButton, Surface2, Border);
        _startupButton.Click += (_, _) => ToggleStartup();

        actionRow.Controls.Add(newCode, 0, 0);
        actionRow.Controls.Add(_startupButton, 1, 0);
        layout.Controls.Add(actionRow, 0, 7);

        var connectionPanel = new Panel
        {
            Dock = DockStyle.Fill,
            BackColor = Color.FromArgb(21, 27, 34),
            Margin = new Padding(0, 8, 0, 0)
        };
        _connectedValue.Dock = DockStyle.Fill;
        _connectedValue.TextAlign = ContentAlignment.MiddleLeft;
        _connectedValue.ForeColor = TextMuted;
        _connectedValue.Font = new Font("Segoe UI", 8.5F, FontStyle.Bold);
        _connectedValue.Padding = new Padding(10, 0, 6, 0);
        connectionPanel.Controls.Add(_connectedValue);
        layout.Controls.Add(connectionPanel, 0, 8);

        _fingerprintValue.Dock = DockStyle.Fill;
        _fingerprintValue.ForeColor = Color.FromArgb(93, 108, 127);
        _fingerprintValue.Font = new Font("Consolas", 7.2F);
        _fingerprintValue.TextAlign = ContentAlignment.MiddleLeft;
        layout.Controls.Add(_fingerprintValue, 0, 9);

        for (var i = 0; i < layout.RowCount; i++)
            layout.RowStyles.Add(new RowStyle(SizeType.Absolute,
                i switch
                {
                    0 or 3 or 5 => 22,
                    1 => 30,
                    2 => 28,
                    4 => 26,
                    6 => 48,
                    7 => 40,
                    8 => 36,
                    _ => 36
                }));

        return card;
    }

    private Control BuildDevicesCard()
    {
        var card = CreateCard();
        card.Margin = new Padding(8, 0, 0, 0);

        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            BackColor = Surface,
            ColumnCount = 1,
            RowCount = 4,
            Padding = new Padding(16, 14, 16, 14)
        };
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 30));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 44));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 30));
        card.Controls.Add(layout);

        var header = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 2,
            BackColor = Surface
        };
        header.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        header.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 130));

        var title = new Label
        {
            Text = "Trusted devices",
            Dock = DockStyle.Fill,
            ForeColor = TextPrimary,
            Font = new Font("Segoe UI", 12F, FontStyle.Bold),
            TextAlign = ContentAlignment.MiddleLeft
        };
        var help = new Label
        {
            Text = "select a device",
            Dock = DockStyle.Fill,
            ForeColor = Color.FromArgb(104, 118, 136),
            Font = new Font("Segoe UI", 8F),
            TextAlign = ContentAlignment.MiddleRight
        };
        header.Controls.Add(title, 0, 0);
        header.Controls.Add(help, 1, 0);
        layout.Controls.Add(header, 0, 0);

        _trustedList.Dock = DockStyle.Fill;
        _trustedList.View = View.Details;
        _trustedList.FullRowSelect = true;
        _trustedList.MultiSelect = false;
        _trustedList.HideSelection = false;
        _trustedList.BackColor = Color.FromArgb(13, 17, 23);
        _trustedList.ForeColor = TextPrimary;
        _trustedList.BorderStyle = BorderStyle.FixedSingle;
        _trustedList.HeaderStyle = ColumnHeaderStyle.Nonclickable;
        _trustedList.Columns.Add("Device", 190);
        _trustedList.Columns.Add("State", 90);
        _trustedList.Columns.Add("Last seen", 145);
        _trustedList.Columns.Add("ID", 82);
        _trustedList.SelectedIndexChanged += (_, _) => RefreshDeviceButtons();
        layout.Controls.Add(_trustedList, 0, 1);

        var actions = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 3,
            BackColor = Surface,
            Margin = new Padding(0, 7, 0, 0)
        };
        actions.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.34F));
        actions.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.33F));
        actions.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.33F));

        StyleButton(_renameButton, Surface2, Border);
        _renameButton.Text = "Rename";
        _renameButton.Enabled = false;
        _renameButton.Margin = new Padding(0, 0, 4, 0);
        _renameButton.Click += (_, _) => RenameSelectedDevice();

        StyleButton(_revokeButton, Color.FromArgb(63, 31, 38), Color.FromArgb(98, 48, 58));
        _revokeButton.Text = "Revoke";
        _revokeButton.Enabled = false;
        _revokeButton.Margin = new Padding(4, 0, 4, 0);
        _revokeButton.Click += (_, _) => RevokeSelectedDevice();

        var revokeAll = MakeButton("Revoke all", Color.FromArgb(48, 27, 32), Color.FromArgb(78, 43, 51));
        revokeAll.Margin = new Padding(4, 0, 0, 0);
        revokeAll.Click += (_, _) => RevokeAllDevices();

        actions.Controls.Add(_renameButton, 0, 0);
        actions.Controls.Add(_revokeButton, 1, 0);
        actions.Controls.Add(revokeAll, 2, 0);
        layout.Controls.Add(actions, 0, 2);

        var caption = new Label
        {
            Dock = DockStyle.Fill,
            Text = "Revoking a connected device closes its session immediately.",
            ForeColor = Color.FromArgb(105, 119, 137),
            Font = new Font("Segoe UI", 8F),
            TextAlign = ContentAlignment.MiddleLeft
        };
        layout.Controls.Add(caption, 0, 3);

        return card;
    }

    private Panel CreateCard()
        => new()
        {
            Dock = DockStyle.Fill,
            BackColor = Surface,
            BorderStyle = BorderStyle.FixedSingle
        };

    private static Label Eyebrow(string text)
        => new()
        {
            Text = text,
            Dock = DockStyle.Fill,
            ForeColor = Color.FromArgb(103, 151, 220),
            Font = new Font("Segoe UI", 7.3F, FontStyle.Bold),
            TextAlign = ContentAlignment.BottomLeft
        };

    private static Control MakeValue(Label target, float size, bool bold)
    {
        target.Dock = DockStyle.Fill;
        target.ForeColor = TextPrimary;
        target.Font = new Font("Segoe UI", size, bold ? FontStyle.Bold : FontStyle.Regular);
        target.TextAlign = ContentAlignment.MiddleLeft;
        return target;
    }

    private static Button MakeButton(string text, Color color, Color border)
    {
        var button = new Button { Text = text };
        StyleButton(button, color, border);
        return button;
    }

    private static void StyleButton(Button button, Color color, Color border)
    {
        button.Height = 36;
        button.FlatStyle = FlatStyle.Flat;
        button.FlatAppearance.BorderSize = 1;
        button.FlatAppearance.BorderColor = border;
        button.FlatAppearance.MouseOverBackColor = ControlPaint.Light(color, 0.08F);
        button.FlatAppearance.MouseDownBackColor = ControlPaint.Dark(color, 0.08F);
        button.BackColor = color;
        button.ForeColor = TextPrimary;
        button.Font = new Font("Segoe UI", 8.7F, FontStyle.Bold);
        button.Cursor = Cursors.Hand;
        button.UseVisualStyleBackColor = false;
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

        _statusValue.Text = "Receiver error";
        _statusValue.ForeColor = Red;
        _connectedValue.Text = message;
    }

    private void RefreshState()
    {
        _statusValue.Text = "●  Ready for secure input";
        _statusValue.ForeColor = Green;
        _pcValue.Text = Environment.MachineName;
        _endpointValue.Text = $"{_host.LocalIp}:{_host.Port}";
        _codeValue.Text = _host.PairCode;

        var connected = _host.ConnectedDevices.ToArray();
        _connectedValue.Text = connected.Length == 0
            ? "No phone connected"
            : connected.Length == 1
                ? $"1 connected · {connected[0].Device}"
                : $"{connected.Length} phones connected";

        var fp = _host.Fingerprint;
        if (fp.Length > 28) fp = fp[..12] + "…" + fp[^12..];
        _fingerprintValue.Text = "Certificate  " + fp;

        RefreshTrustedList();
        RefreshStartupButton();

        _tray.Text = connected.Length == 0
            ? "WirelessKey 4.4 · ready"
            : $"WirelessKey 4.4 · {connected.Length} connected";
    }

    private void RefreshTrustedList()
    {
        var selectedToken = SelectedTrustedToken();
        _trustedList.BeginUpdate();
        try
        {
            _trustedList.Items.Clear();
            foreach (var device in _host.TrustedDevices)
            {
                var created = DateTimeOffset.FromUnixTimeSeconds(device.Created).LocalDateTime;
                var last = DateTimeOffset.FromUnixTimeSeconds(device.LastSeen).LocalDateTime;
                var id = device.Token.Length > 8 ? device.Token[..8] : device.Token;

                var item = new ListViewItem(device.Device)
                {
                    Tag = device.Token,
                    ForeColor = device.Connected ? Color.FromArgb(205, 241, 226) : TextPrimary
                };
                item.SubItems.Add(device.Connected ? "● Connected" : "Trusted");
                item.SubItems.Add(FormatLastSeen(last));
                item.SubItems.Add(id);
                item.ToolTipText = $"Paired {created:g}";
                _trustedList.Items.Add(item);

                if (selectedToken == device.Token)
                    item.Selected = true;
            }
        }
        finally
        {
            _trustedList.EndUpdate();
        }
        RefreshDeviceButtons();
    }

    private static string FormatLastSeen(DateTime value)
    {
        var age = DateTime.Now - value;
        if (age.TotalMinutes < 1) return "just now";
        if (age.TotalHours < 1) return $"{Math.Max(1, (int)age.TotalMinutes)} min ago";
        if (age.TotalDays < 1) return $"{Math.Max(1, (int)age.TotalHours)} hr ago";
        if (age.TotalDays < 7) return $"{Math.Max(1, (int)age.TotalDays)} d ago";
        return value.ToString("dd MMM yyyy");
    }

    private string? SelectedTrustedToken()
        => _trustedList.SelectedItems.Count == 0
            ? null
            : _trustedList.SelectedItems[0].Tag as string;

    private string? SelectedTrustedName()
        => _trustedList.SelectedItems.Count == 0
            ? null
            : _trustedList.SelectedItems[0].Text;

    private void RefreshDeviceButtons()
    {
        var enabled = SelectedTrustedToken() != null;
        _renameButton.Enabled = enabled;
        _revokeButton.Enabled = enabled;
    }

    private void RenameSelectedDevice()
    {
        var token = SelectedTrustedToken();
        var current = SelectedTrustedName();
        if (token == null || current == null) return;

        using var dialog = new Form
        {
            Text = "Rename trusted device",
            Width = 390,
            Height = 190,
            StartPosition = FormStartPosition.CenterParent,
            BackColor = Bg,
            ForeColor = TextPrimary,
            FormBorderStyle = FormBorderStyle.FixedDialog,
            MaximizeBox = false,
            MinimizeBox = false,
            Icon = _appIcon
        };

        var label = new Label
        {
            Text = "Device name",
            Left = 18,
            Top = 18,
            Width = 330,
            Height = 22,
            ForeColor = TextMuted
        };
        var input = new TextBox
        {
            Text = current,
            Left = 18,
            Top = 45,
            Width = 336,
            Height = 30,
            BackColor = Surface2,
            ForeColor = TextPrimary,
            BorderStyle = BorderStyle.FixedSingle
        };
        var save = MakeButton("Save", Color.FromArgb(31, 59, 96), Accent);
        save.Left = 264;
        save.Top = 92;
        save.Width = 90;
        save.DialogResult = DialogResult.OK;
        dialog.AcceptButton = save;
        dialog.Controls.Add(label);
        dialog.Controls.Add(input);
        dialog.Controls.Add(save);

        if (dialog.ShowDialog(this) == DialogResult.OK)
            _host.RenameTrusted(token, input.Text);
    }

    private void RevokeSelectedDevice()
    {
        var token = SelectedTrustedToken();
        var name = SelectedTrustedName();
        if (token == null || name == null) return;

        var result = MessageBox.Show(
            $"Revoke trust for \"{name}\"?\n\nIt will be disconnected and must pair again.",
            "WirelessKey 4.4",
            MessageBoxButtons.YesNo,
            MessageBoxIcon.Question);

        if (result == DialogResult.Yes)
            _host.RevokeTrusted(token);
    }

    private void RevokeAllDevices()
    {
        if (_host.TrustedDevices.Count == 0) return;

        var result = MessageBox.Show(
            "Revoke every trusted phone?\n\nConnected phones will be disconnected immediately.",
            "WirelessKey 4.4",
            MessageBoxButtons.YesNo,
            MessageBoxIcon.Warning);

        if (result == DialogResult.Yes)
            _host.RevokeAll();
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
                Text = "WirelessKey 4.4 · Pair phone",
                Width = 440,
                Height = 540,
                StartPosition = FormStartPosition.CenterParent,
                BackColor = Bg,
                ForeColor = TextPrimary,
                FormBorderStyle = FormBorderStyle.FixedDialog,
                MaximizeBox = false,
                MinimizeBox = false,
                Icon = _appIcon
            };

            var title = new Label
            {
                Text = "Pair your phone",
                Left = 30,
                Top = 18,
                Width = 365,
                Height = 30,
                ForeColor = TextPrimary,
                Font = new Font("Segoe UI", 15F, FontStyle.Bold),
                TextAlign = ContentAlignment.MiddleCenter
            };

            var subtitle = new Label
            {
                Text = "Open WirelessKey on Android and scan this code",
                Left = 28,
                Top = 50,
                Width = 370,
                Height = 24,
                ForeColor = TextMuted,
                Font = new Font("Segoe UI", 8.5F),
                TextAlign = ContentAlignment.MiddleCenter
            };

            var picture = new PictureBox
            {
                Image = bitmap,
                SizeMode = PictureBoxSizeMode.Zoom,
                Left = 60,
                Top = 82,
                Width = 300,
                Height = 300,
                BackColor = Color.White
            };

            var code = new Label
            {
                Text = _host.PairCode,
                Left = 40,
                Top = 394,
                Width = 340,
                Height = 42,
                ForeColor = Color.FromArgb(194, 220, 255),
                Font = new Font("Consolas", 22F, FontStyle.Bold),
                TextAlign = ContentAlignment.MiddleCenter
            };

            var note = new Label
            {
                Text = "Temporary pairing code · expires automatically",
                Left = 34,
                Top = 440,
                Width = 352,
                Height = 28,
                ForeColor = TextMuted,
                Font = new Font("Segoe UI", 8F),
                TextAlign = ContentAlignment.MiddleCenter
            };

            var endpoint = new Label
            {
                Text = $"{Environment.MachineName}  ·  {_host.LocalIp}:{_host.Port}",
                Left = 34,
                Top = 474,
                Width = 352,
                Height = 24,
                ForeColor = Color.FromArgb(101, 126, 158),
                Font = new Font("Consolas", 7.8F),
                TextAlign = ContentAlignment.MiddleCenter
            };

            dialog.Controls.Add(title);
            dialog.Controls.Add(subtitle);
            dialog.Controls.Add(picture);
            dialog.Controls.Add(code);
            dialog.Controls.Add(note);
            dialog.Controls.Add(endpoint);
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
        var enabled = IsStartupEnabled();
        _startupButton.Text = enabled ? "Startup  ON" : "Startup  OFF";
        _startupButton.BackColor = enabled
            ? Color.FromArgb(28, 76, 58)
            : Surface2;
    }

    private void OnFormClosing(object? sender, FormClosingEventArgs e)
    {
        if (_allowExit) return;
        e.Cancel = true;
        Hide();
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool DestroyIcon(nint hIcon);

    private static Icon CreateAppIcon()
    {
        using var bitmap = new Bitmap(64, 64);
        using (var g = Graphics.FromImage(bitmap))
        {
            g.Clear(Color.FromArgb(9, 12, 17));
            using var deck = new SolidBrush(Color.FromArgb(28, 35, 44));
            using var key = new SolidBrush(Color.FromArgb(72, 84, 100));
            using var accent = new SolidBrush(Accent);

            g.FillRoundedRectangle(deck, new RectangleF(5, 9, 54, 46), 9);

            for (var row = 0; row < 3; row++)
            for (var col = 0; col < 5; col++)
                g.FillRectangle(key, 11 + col * 9, 16 + row * 9, 6, 5);

            g.FillRectangle(accent, 16, 43, 32, 6);
        }

        var handle = bitmap.GetHicon();
        try
        {
            using var icon = Icon.FromHandle(handle);
            return (Icon)icon.Clone();
        }
        finally
        {
            DestroyIcon(handle);
        }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _host.StateChanged -= HostStateChanged;
            _host.Error -= HostError;
            _tray.Visible = false;
            _tray.Dispose();
            _appIcon.Dispose();
        }
        base.Dispose(disposing);
    }
}

internal static class GraphicsExtensions
{
    public static void FillRoundedRectangle(this Graphics graphics, Brush brush, RectangleF rect, float radius)
    {
        using var path = new System.Drawing.Drawing2D.GraphicsPath();
        var diameter = radius * 2;
        path.AddArc(rect.X, rect.Y, diameter, diameter, 180, 90);
        path.AddArc(rect.Right - diameter, rect.Y, diameter, diameter, 270, 90);
        path.AddArc(rect.Right - diameter, rect.Bottom - diameter, diameter, diameter, 0, 90);
        path.AddArc(rect.X, rect.Bottom - diameter, diameter, diameter, 90, 90);
        path.CloseFigure();
        graphics.FillPath(brush, path);
    }
}

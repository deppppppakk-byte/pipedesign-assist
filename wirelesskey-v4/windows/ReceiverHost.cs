using System.Collections.Concurrent;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.Extensions.Hosting;

namespace WirelessKey.NativeReceiver;

internal sealed record TrustedDevice(string Device, long Created, long LastSeen);
internal sealed record ConnectedDevice(Guid Id, string Device, string Ip, DateTimeOffset Since);

internal sealed class ReceiverHost : IAsyncDisposable
{
    private const int DiscoveryPort = 8766;
    private const int FirstPort = 8765;
    private const int LastPort = 8775;
    private static readonly TimeSpan PairTtl = TimeSpan.FromMinutes(30);

    private readonly string _dataDir;
    private readonly string _trustPath;
    private readonly string _certPath;
    private readonly object _stateGate = new();

    private Dictionary<string, TrustedDevice> _trusted = new();
    private readonly ConcurrentDictionary<Guid, ConnectedDevice> _connected = new();
    private readonly ConcurrentDictionary<string, ConcurrentQueue<DateTimeOffset>> _failures = new();
    private CancellationTokenSource? _cts;
    private WebApplication? _app;
    private Task? _discoveryTask;
    private X509Certificate2? _certificate;
    private string _pairCode = "000000";
    private DateTimeOffset _pairCreated;

    public event Action? StateChanged;
    public event Action<string>? Error;

    public int Port { get; private set; } = FirstPort;
    public string PairCode { get { lock (_stateGate) return EnsurePairCode(); } }
    public string Fingerprint { get; private set; } = "";
    public string LocalIp => ResolveLocalIp();
    public string PairingPayload => JsonSerializer.Serialize(new
    {
        type = "wirelesskey_pair",
        version = "4.1.1",
        name = Environment.MachineName,
        ip = LocalIp,
        port = Port,
        fingerprint = Fingerprint,
        code = PairCode
    });
    public IReadOnlyCollection<ConnectedDevice> ConnectedDevices => _connected.Values.ToArray();

    public ReceiverHost()
    {
        _dataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "WirelessKey");
        Directory.CreateDirectory(_dataDir);
        _trustPath = Path.Combine(_dataDir, "trusted_devices_v4.json");
        _certPath = Path.Combine(_dataDir, "receiver_v4.pfx");
        LoadTrusted();
        RotatePairCode();
    }

    public void RotatePairCode()
    {
        lock (_stateGate)
        {
            _pairCode = RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6");
            _pairCreated = DateTimeOffset.UtcNow;
        }
        StateChanged?.Invoke();
    }

    public void RevokeAll()
    {
        lock (_stateGate)
        {
            _trusted.Clear();
            SaveTrusted();
        }
        StateChanged?.Invoke();
    }

    private string EnsurePairCode()
    {
        if (DateTimeOffset.UtcNow - _pairCreated > PairTtl)
        {
            _pairCode = RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6");
            _pairCreated = DateTimeOffset.UtcNow;
        }
        return _pairCode;
    }

    private void LoadTrusted()
    {
        try
        {
            if (File.Exists(_trustPath))
            {
                _trusted = JsonSerializer.Deserialize<Dictionary<string, TrustedDevice>>(File.ReadAllText(_trustPath))
                           ?? new();
            }
        }
        catch
        {
            _trusted = new();
        }
    }

    private void SaveTrusted()
    {
        try
        {
            var tmp = _trustPath + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(_trusted, new JsonSerializerOptions { WriteIndented = true }));
            File.Move(tmp, _trustPath, true);
        }
        catch
        {
        }
    }

    private X509Certificate2 EnsureCertificate()
    {
        if (File.Exists(_certPath))
        {
            try
            {
                var loaded = new X509Certificate2(_certPath, (string?)null,
                    X509KeyStorageFlags.Exportable | X509KeyStorageFlags.UserKeySet);
                Fingerprint = Convert.ToHexString(SHA256.HashData(loaded.RawData)).ToLowerInvariant();
                return loaded;
            }
            catch
            {
            }
        }

        using var rsa = RSA.Create(2048);
        var request = new CertificateRequest(
            $"CN={Environment.MachineName}",
            rsa,
            HashAlgorithmName.SHA256,
            RSASignaturePadding.Pkcs1);

        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, false));
        request.CertificateExtensions.Add(new X509SubjectKeyIdentifierExtension(request.PublicKey, false));
        var san = new SubjectAlternativeNameBuilder();
        san.AddDnsName(Environment.MachineName);
        san.AddDnsName("localhost");
        san.AddIpAddress(IPAddress.Loopback);
        request.CertificateExtensions.Add(san.Build());

        using var cert = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(10));
        var pfx = cert.Export(X509ContentType.Pfx);
        File.WriteAllBytes(_certPath, pfx);

        var result = new X509Certificate2(pfx, (string?)null,
            X509KeyStorageFlags.Exportable | X509KeyStorageFlags.UserKeySet);
        Fingerprint = Convert.ToHexString(SHA256.HashData(result.RawData)).ToLowerInvariant();
        return result;
    }

    public async Task StartAsync(CancellationToken externalToken = default)
    {
        if (_app != null) return;

        _certificate = EnsureCertificate();
        Port = FindAvailablePort();
        _cts = CancellationTokenSource.CreateLinkedTokenSource(externalToken);

        var builder = WebApplication.CreateBuilder(new WebApplicationOptions
        {
            Args = Array.Empty<string>(),
            ApplicationName = typeof(ReceiverHost).Assembly.FullName
        });

        builder.WebHost.ConfigureKestrel(options =>
        {
            options.ListenAnyIP(Port, listen =>
            {
                listen.Protocols = HttpProtocols.Http1;
                listen.UseHttps(_certificate);
            });
        });

        var app = builder.Build();
        app.UseWebSockets(new WebSocketOptions { KeepAliveInterval = TimeSpan.FromSeconds(10) });
        app.Map("/ws", HandleWebSocketAsync);
        _app = app;

        await app.StartAsync(_cts.Token);
        _discoveryTask = Task.Run(() => DiscoveryLoopAsync(_cts.Token), _cts.Token);
        StateChanged?.Invoke();
    }

    public async Task StopAsync()
    {
        if (_cts == null) return;
        try { _cts.Cancel(); } catch { }

        if (_app != null)
        {
            try { await _app.StopAsync(TimeSpan.FromSeconds(2)); } catch { }
            await _app.DisposeAsync();
            _app = null;
        }

        if (_discoveryTask != null)
        {
            try { await _discoveryTask; } catch { }
            _discoveryTask = null;
        }

        _cts.Dispose();
        _cts = null;
    }

    private int FindAvailablePort()
    {
        for (var port = FirstPort; port <= LastPort; port++)
        {
            try
            {
                var listener = new TcpListener(IPAddress.Any, port);
                listener.Start();
                listener.Stop();
                return port;
            }
            catch
            {
            }
        }
        throw new InvalidOperationException($"No available WirelessKey port between {FirstPort} and {LastPort}.");
    }

    private async Task HandleWebSocketAsync(HttpContext context)
    {
        if (!context.WebSockets.IsWebSocketRequest)
        {
            context.Response.StatusCode = 400;
            return;
        }

        using var ws = await context.WebSockets.AcceptWebSocketAsync();
        var clientIp = context.Connection.RemoteIpAddress?.ToString() ?? "unknown";
        var sendLock = new SemaphoreSlim(1, 1);
        var id = Guid.NewGuid();
        string deviceName = "Android";
        CancellationTokenSource? contextCts = null;
        Task? contextTask = null;

        try
        {
            var authText = await ReceiveTextAsync(ws, context.RequestAborted);
            if (authText == null) return;

            using var authDoc = JsonDocument.Parse(authText);
            var root = authDoc.RootElement;
            if (!root.TryGetProperty("type", out var typeEl) || typeEl.GetString() != "auth")
            {
                await SendJsonAsync(ws, new { type = "auth", ok = false, error = "Authentication required" }, sendLock, context.RequestAborted);
                await ws.CloseAsync(WebSocketCloseStatus.PolicyViolation, "Authentication required", context.RequestAborted);
                return;
            }

            var token = root.TryGetProperty("token", out var tokenEl) ? tokenEl.GetString() ?? "" : "";
            var code = root.TryGetProperty("code", out var codeEl) ? codeEl.GetString() ?? "" : "";
            deviceName = root.TryGetProperty("device", out var devEl) ? devEl.GetString() ?? "Android" : "Android";

            var auth = ValidateAuth(clientIp, token, code, deviceName);
            if (!auth.Ok)
            {
                await SendJsonAsync(ws, new { type = "auth", ok = false, error = auth.Error }, sendLock, context.RequestAborted);
                await ws.CloseAsync(WebSocketCloseStatus.PolicyViolation, auth.Error, context.RequestAborted);
                return;
            }

            _connected[id] = new ConnectedDevice(id, deviceName, clientIp, DateTimeOffset.Now);
            StateChanged?.Invoke();

            await SendJsonAsync(ws, new
            {
                type = "auth",
                ok = true,
                token = auth.Token,
                pcName = Environment.MachineName,
                version = "4.1.1",
                secure = true,
                native = true
            }, sendLock, context.RequestAborted);

            contextCts = CancellationTokenSource.CreateLinkedTokenSource(context.RequestAborted);
            contextTask = Task.Run(() => ContextLoopAsync(ws, sendLock, contextCts.Token), contextCts.Token);

            while (ws.State == WebSocketState.Open && !context.RequestAborted.IsCancellationRequested)
            {
                var text = await ReceiveTextAsync(ws, context.RequestAborted);
                if (text == null) break;

                using var doc = JsonDocument.Parse(text);
                var obj = doc.RootElement;
                var messageType = obj.TryGetProperty("type", out var mt) ? mt.GetString() : null;

                if (messageType == "event" && obj.TryGetProperty("event", out var ev))
                    ProcessEvent(ev);
                else if (messageType == "clipboard_get")
                {
                    var clipboard = ClipboardBridge.GetText();
                    await SendJsonAsync(ws, new { type = "clipboard", text = clipboard }, sendLock, context.RequestAborted);
                }
                else if (messageType == "ping")
                {
                    var ts = obj.TryGetProperty("ts", out var tsEl) ? tsEl.GetInt64() : 0;
                    await SendJsonAsync(ws, new { type = "pong", ts }, sendLock, context.RequestAborted);
                }
            }

            if (ws.State == WebSocketState.CloseReceived)
            {
                try
                {
                    await ws.CloseOutputAsync(
                        WebSocketCloseStatus.NormalClosure,
                        "WirelessKey connection closed",
                        CancellationToken.None);
                }
                catch { }
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception ex)
        {
            Error?.Invoke(ex.Message);
        }
        finally
        {
            if (contextCts != null)
            {
                contextCts.Cancel();
                contextCts.Dispose();
            }
            if (contextTask != null)
            {
                try { await contextTask; } catch { }
            }
            _connected.TryRemove(id, out _);
            StateChanged?.Invoke();
        }
    }

    private sealed record AuthResult(bool Ok, string Token, string Error);

    private AuthResult ValidateAuth(string ip, string token, string code, string device)
    {
        lock (_stateGate)
        {
            if (!string.IsNullOrWhiteSpace(token) && _trusted.TryGetValue(token, out var known))
            {
                _trusted[token] = known with { Device = device, LastSeen = DateTimeOffset.UtcNow.ToUnixTimeSeconds() };
                SaveTrusted();
                return new(true, token, "");
            }

            if (IsRateLimited(ip))
                return new(false, "", "Too many incorrect attempts. Try again in a minute.");

            var validCode = DateTimeOffset.UtcNow - _pairCreated <= PairTtl
                            && CryptographicOperations.FixedTimeEquals(
                                Encoding.UTF8.GetBytes(code),
                                Encoding.UTF8.GetBytes(_pairCode));

            if (!validCode)
            {
                MarkFailed(ip);
                return new(false, "", "Wrong or expired pairing code");
            }

            var newToken = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32))
                .TrimEnd('=').Replace('+', '-').Replace('/', '_');

            var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            _trusted[newToken] = new TrustedDevice(device, now, now);
            SaveTrusted();
            return new(true, newToken, "");
        }
    }

    private bool IsRateLimited(string ip)
    {
        var queue = _failures.GetOrAdd(ip, _ => new ConcurrentQueue<DateTimeOffset>());
        var cutoff = DateTimeOffset.UtcNow.AddMinutes(-1);
        while (queue.TryPeek(out var oldest) && oldest < cutoff)
            queue.TryDequeue(out _);
        return queue.Count >= 5;
    }

    private void MarkFailed(string ip)
        => _failures.GetOrAdd(ip, _ => new ConcurrentQueue<DateTimeOffset>()).Enqueue(DateTimeOffset.UtcNow);

    private static async Task<string?> ReceiveTextAsync(WebSocket ws, CancellationToken ct)
    {
        var buffer = new byte[8192];
        using var ms = new MemoryStream();

        while (true)
        {
            var result = await ws.ReceiveAsync(buffer, ct);
            if (result.MessageType == WebSocketMessageType.Close) return null;
            if (result.MessageType != WebSocketMessageType.Text) continue;

            ms.Write(buffer, 0, result.Count);
            if (result.EndOfMessage) return Encoding.UTF8.GetString(ms.ToArray());
            if (ms.Length > 1_048_576) throw new InvalidDataException("Message too large.");
        }
    }

    private static async Task SendJsonAsync(WebSocket ws, object payload, SemaphoreSlim gate, CancellationToken ct)
    {
        var bytes = JsonSerializer.SerializeToUtf8Bytes(payload);
        await gate.WaitAsync(ct);
        try
        {
            if (ws.State == WebSocketState.Open)
                await ws.SendAsync(bytes, WebSocketMessageType.Text, true, ct);
        }
        finally
        {
            gate.Release();
        }
    }

    private async Task ContextLoopAsync(WebSocket ws, SemaphoreSlim gate, CancellationToken ct)
    {
        ForegroundInfo? last = null;
        while (!ct.IsCancellationRequested && ws.State == WebSocketState.Open)
        {
            var current = ForegroundApp.Read();
            if (last != current)
            {
                last = current;
                await SendJsonAsync(ws, new
                {
                    type = "context",
                    activeApp = current.ActiveApp,
                    profile = current.Profile,
                    title = current.Title
                }, gate, ct);
            }
            await Task.Delay(1000, ct);
        }
    }

    private static void ProcessEvent(JsonElement ev)
    {
        var type = ev.TryGetProperty("type", out var t) ? t.GetString() : null;
        switch (type)
        {
            case "text":
                InputInjector.TypeText(ev.TryGetProperty("text", out var text) ? text.GetString() ?? "" : "");
                break;
            case "key":
                var key = ev.TryGetProperty("key", out var keyEl) ? keyEl.GetString() ?? "" : "";
                var mods = new List<string>();
                if (ev.TryGetProperty("modifiers", out var modsEl) && modsEl.ValueKind == JsonValueKind.Array)
                    foreach (var item in modsEl.EnumerateArray())
                        if (item.ValueKind == JsonValueKind.String) mods.Add(item.GetString() ?? "");
                InputInjector.PressKey(key, mods);
                break;
            case "move":
                InputInjector.Move(
                    ev.TryGetProperty("dx", out var dx) ? dx.GetDouble() : 0,
                    ev.TryGetProperty("dy", out var dy) ? dy.GetDouble() : 0);
                break;
            case "mouse":
                InputInjector.MouseButton(
                    ev.TryGetProperty("button", out var b) ? b.GetString() ?? "left" : "left",
                    ev.TryGetProperty("action", out var a) ? a.GetString() ?? "click" : "click");
                break;
            case "wheel":
                InputInjector.Wheel(ev.TryGetProperty("delta", out var w) ? w.GetInt32() : 0);
                break;
            case "hwheel":
                InputInjector.HWheel(ev.TryGetProperty("delta", out var hw) ? hw.GetInt32() : 0);
                break;
            case "clipboard_set":
                ClipboardBridge.SetText(ev.TryGetProperty("text", out var clip) ? clip.GetString() ?? "" : "");
                break;
        }
    }

    private async Task DiscoveryLoopAsync(CancellationToken ct)
    {
        using var udp = new UdpClient(new IPEndPoint(IPAddress.Any, DiscoveryPort));
        while (!ct.IsCancellationRequested)
        {
            UdpReceiveResult result;
            try
            {
                result = await udp.ReceiveAsync(ct);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch
            {
                continue;
            }

            var query = Encoding.UTF8.GetString(result.Buffer).Trim();
            if (query != "WIRELESSKEY_DISCOVER_V4")
                continue;

            var response = JsonSerializer.SerializeToUtf8Bytes(new
            {
                name = Environment.MachineName,
                pcName = Environment.MachineName,
                ip = ResolveLocalIp(result.RemoteEndPoint.Address),
                port = Port,
                version = "4.1.1",
                secure = true,
                native = true,
                fingerprint = Fingerprint
            });

            try { await udp.SendAsync(response, result.RemoteEndPoint, ct); } catch { }
        }
    }

    private static string ResolveLocalIp(IPAddress? remote = null)
    {
        try
        {
            using var socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, 0);
            socket.Connect(remote ?? IPAddress.Parse("8.8.8.8"), 9);
            return ((IPEndPoint)socket.LocalEndPoint!).Address.ToString();
        }
        catch
        {
            try
            {
                return Dns.GetHostEntry(Dns.GetHostName()).AddressList
                    .FirstOrDefault(a => a.AddressFamily == AddressFamily.InterNetwork)?.ToString() ?? "127.0.0.1";
            }
            catch
            {
                return "127.0.0.1";
            }
        }
    }

    public async ValueTask DisposeAsync()
    {
        await StopAsync();
        _certificate?.Dispose();
    }
}

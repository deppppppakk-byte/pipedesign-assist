using System.Net.WebSockets;
using System.Text;
using System.Text.Json;

namespace WirelessKey.NativeReceiver;

internal static class NativeSelfTest
{
    public static async Task RunAsync()
    {
        await using var host = new ReceiverHost();
        await host.StartAsync();

        using (var qr = JsonDocument.Parse(host.PairingPayload))
        {
            var root = qr.RootElement;
            if (root.GetProperty("type").GetString() != "wirelesskey_pair")
                throw new InvalidOperationException("Invalid QR pairing type.");
            if (root.GetProperty("code").GetString() != host.PairCode)
                throw new InvalidOperationException("QR pairing code mismatch.");
            if (root.GetProperty("fingerprint").GetString() != host.Fingerprint)
                throw new InvalidOperationException("QR certificate identity mismatch.");
            if (root.GetProperty("port").GetInt32() != host.Port)
                throw new InvalidOperationException("QR receiver port mismatch.");
        }

        using var ws = new ClientWebSocket();
        ws.Options.RemoteCertificateValidationCallback = (_, _, _, _) => true;
        await ws.ConnectAsync(new Uri($"wss://127.0.0.1:{host.Port}/ws"), CancellationToken.None);

        var auth = JsonSerializer.SerializeToUtf8Bytes(new
        {
            type = "auth",
            code = host.PairCode,
            device = "WirelessKey Native Self Test",
            appVersion = "4.2"
        });
        await ws.SendAsync(auth, WebSocketMessageType.Text, true, CancellationToken.None);

        using var authDoc = JsonDocument.Parse(await ReceiveTextAsync(ws));
        if (!authDoc.RootElement.GetProperty("ok").GetBoolean())
            throw new InvalidOperationException("Native receiver authentication self-test failed.");

        var stamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var ping = JsonSerializer.SerializeToUtf8Bytes(new { type = "ping", ts = stamp });
        await ws.SendAsync(ping, WebSocketMessageType.Text, true, CancellationToken.None);

        var deadline = DateTime.UtcNow.AddSeconds(5);
        var gotPong = false;
        while (DateTime.UtcNow < deadline)
        {
            using var doc = JsonDocument.Parse(await ReceiveTextAsync(ws));
            if (doc.RootElement.TryGetProperty("type", out var type)
                && type.GetString() == "pong"
                && doc.RootElement.GetProperty("ts").GetInt64() == stamp)
            {
                gotPong = true;
                break;
            }
        }

        if (!gotPong)
            throw new InvalidOperationException("Native receiver ping/pong self-test failed.");

        var pointer = new byte[9];
        pointer[0] = 1;
        BitConverter.GetBytes(0f).CopyTo(pointer, 1);
        BitConverter.GetBytes(0f).CopyTo(pointer, 5);
        await ws.SendAsync(pointer, WebSocketMessageType.Binary, true, CancellationToken.None);

        var stamp2 = stamp + 1;
        var ping2 = JsonSerializer.SerializeToUtf8Bytes(new { type = "ping", ts = stamp2 });
        await ws.SendAsync(ping2, WebSocketMessageType.Text, true, CancellationToken.None);

        var binaryPathHealthy = false;
        var secondDeadline = DateTime.UtcNow.AddSeconds(5);
        while (DateTime.UtcNow < secondDeadline)
        {
            using var doc = JsonDocument.Parse(await ReceiveTextAsync(ws));
            if (doc.RootElement.TryGetProperty("type", out var type)
                && type.GetString() == "pong"
                && doc.RootElement.GetProperty("ts").GetInt64() == stamp2)
            {
                binaryPathHealthy = true;
                break;
            }
        }
        if (!binaryPathHealthy)
            throw new InvalidOperationException("Binary pointer fast-path disrupted the connection.");

        await ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "self-test complete", CancellationToken.None);
        await host.StopAsync();
    }

    private static async Task<string> ReceiveTextAsync(ClientWebSocket ws)
    {
        var buffer = new byte[8192];
        using var ms = new MemoryStream();

        while (true)
        {
            var result = await ws.ReceiveAsync(buffer, CancellationToken.None);
            if (result.MessageType == WebSocketMessageType.Close)
                throw new InvalidOperationException("Socket closed during self-test.");
            ms.Write(buffer, 0, result.Count);
            if (result.EndOfMessage)
                return Encoding.UTF8.GetString(ms.ToArray());
        }
    }
}

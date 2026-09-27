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

        using var ws = new ClientWebSocket();
        ws.Options.RemoteCertificateValidationCallback = (_, _, _, _) => true;
        await ws.ConnectAsync(new Uri($"wss://127.0.0.1:{host.Port}/ws"), CancellationToken.None);

        var auth = JsonSerializer.SerializeToUtf8Bytes(new
        {
            type = "auth",
            code = host.PairCode,
            device = "WirelessKey Native Self Test",
            appVersion = "4.0"
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

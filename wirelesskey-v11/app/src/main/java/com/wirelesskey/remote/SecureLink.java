package com.wirelesskey.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;
import org.json.JSONArray;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public final class SecureLink {
    public interface Listener {
        void onStatus(String kind, String detail);
        void onDiscovery(JSONObject device);
        void onDiscoveryDone();
        void onContext(JSONObject context);
        void onLatency(long ms);
        void onClipboard(String text);
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, String> discoveredFingerprints = new ConcurrentHashMap<>();

    private Listener listener;
    private volatile WebSocket socket;
    private volatile boolean authenticated;
    private volatile boolean shouldReconnect;
    private String currentHost = "";
    private String currentCode = "";
    private int reconnectAttempt;
    private long lastPingAt;

    private final Runnable reconnectTask = () -> {
        if (shouldReconnect && !authenticated && !currentHost.isEmpty()) connectSocket(false);
    };

    private final Runnable latencyTask = new Runnable() {
        @Override
        public void run() {
            WebSocket ws = socket;
            if (!authenticated || ws == null) return;
            try {
                lastPingAt = System.currentTimeMillis();
                JSONObject ping = new JSONObject();
                ping.put("type", "ping");
                ping.put("ts", lastPingAt);
                ws.send(ping.toString());
            } catch (Exception ignored) {
            }
            main.postDelayed(this, 5000);
        }
    };

    public SecureLink(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences("wirelesskey", Context.MODE_PRIVATE);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public String loadHost() {
        return prefs.getString("host", "");
    }

    public String loadCode() {
        return prefs.getString("code", "");
    }

    public boolean loadHaptics() {
        return prefs.getBoolean("haptics", true);
    }

    public void setHaptics(boolean enabled) {
        prefs.edit().putBoolean("haptics", enabled).apply();
    }

    public void save(String host, String code) {
        prefs.edit().putString("host", host == null ? "" : host.trim())
                .putString("code", code == null ? "" : code.trim()).apply();
    }

    public String loadKnownPeersJson() {
        return prefs.getString("known_peers", "[]");
    }

    public synchronized void rememberPeer(String name, String rawHost, String fingerprint) {
        String host = normalizeHost(rawHost);
        if (host.isEmpty() || fingerprint == null || fingerprint.trim().isEmpty()) return;

        String fp = fingerprint.trim().toLowerCase(Locale.US);
        discoveredFingerprints.put(host, fp);
        prefs.edit().putString(certKey(host), fp).apply();

        try {
            JSONArray old = new JSONArray(loadKnownPeersJson());
            JSONArray fresh = new JSONArray();
            boolean inserted = false;

            for (int i = 0; i < old.length(); i++) {
                JSONObject peer = old.optJSONObject(i);
                if (peer == null) continue;
                String peerFp = peer.optString("fingerprint", "");
                String peerHost = normalizeHost(peer.optString("host", ""));
                if (peerFp.equalsIgnoreCase(fp) || peerHost.equalsIgnoreCase(host)) {
                    if (!inserted) {
                        JSONObject updated = new JSONObject();
                        updated.put("name", name == null || name.trim().isEmpty() ? "WirelessKey PC" : name.trim());
                        updated.put("host", host);
                        updated.put("fingerprint", fp);
                        updated.put("lastSeen", System.currentTimeMillis());
                        fresh.put(updated);
                        inserted = true;
                    }
                } else {
                    fresh.put(peer);
                }
            }

            if (!inserted) {
                JSONObject peer = new JSONObject();
                peer.put("name", name == null || name.trim().isEmpty() ? "WirelessKey PC" : name.trim());
                peer.put("host", host);
                peer.put("fingerprint", fp);
                peer.put("lastSeen", System.currentTimeMillis());
                fresh.put(peer);
            }

            prefs.edit().putString("known_peers", fresh.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public JSONObject importPairingQr(String payload) throws Exception {
        JSONObject obj = new JSONObject(payload);
        if (!"wirelesskey_pair".equals(obj.optString("type", ""))) {
            throw new IllegalArgumentException("Not a WirelessKey pairing QR");
        }

        String ip = obj.optString("ip", "").trim();
        int port = obj.optInt("port", 8765);
        String host = normalizeHost(ip + ":" + port);
        String fingerprint = obj.optString("fingerprint", "").trim().toLowerCase(Locale.US);
        String code = obj.optString("code", "").trim();
        String name = obj.optString("name", obj.optString("pcName", "WirelessKey PC"));

        if (ip.isEmpty() || fingerprint.length() < 32 || code.length() != 6) {
            throw new IllegalArgumentException("Incomplete WirelessKey pairing QR");
        }

        rememberPeer(name, host, fingerprint);
        obj.put("host", host);
        return obj;
    }

    private void postStatus(String kind, String detail) {
        Listener l = listener;
        if (l != null) main.post(() -> l.onStatus(kind, detail));
    }

    private String normalizeHost(String raw) {
        String host = raw == null ? "" : raw.trim();
        for (String prefix : new String[]{"http://", "https://", "ws://", "wss://"}) {
            if (host.startsWith(prefix)) host = host.substring(prefix.length());
        }
        while (host.endsWith("/")) host = host.substring(0, host.length() - 1);
        if (!host.isEmpty() && !host.contains(":")) host += ":8765";
        return host;
    }

    private String tokenKey(String fingerprint) {
        String id = fingerprint == null ? "" : fingerprint.trim().toLowerCase(Locale.US);
        return "trusted_cert_" + id.replaceAll("[^A-Za-z0-9]", "_");
    }

    private String certKey(String host) {
        return "cert_" + host.replaceAll("[^A-Za-z0-9]", "_");
    }

    private static String sha256Fingerprint(X509Certificate cert) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    private OkHttpClient secureClientFor(final String expectedFingerprint) throws Exception {
        final String expected = expectedFingerprint.replace(":", "").trim().toLowerCase(Locale.US);
        X509TrustManager tm = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("Missing receiver certificate");
                try {
                    String actual = sha256Fingerprint(chain[0]);
                    if (!actual.equalsIgnoreCase(expected)) throw new CertificateException("Receiver identity changed");
                } catch (CertificateException e) {
                    throw e;
                } catch (Exception e) {
                    throw new CertificateException("Certificate verification failed", e);
                }
            }
        };

        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, new X509TrustManager[]{tm}, null);
        SSLSocketFactory factory = ssl.getSocketFactory();

        return new OkHttpClient.Builder()
                .sslSocketFactory(factory, tm)
                .hostnameVerifier((hostname, session) -> true)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(4, TimeUnit.SECONDS)
                .pingInterval(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public void connect(String rawHost, String code) {
        currentHost = normalizeHost(rawHost);
        currentCode = code == null ? "" : code.trim();
        save(currentHost, currentCode);
        shouldReconnect = true;
        reconnectAttempt = 0;
        connectSocket(true);
    }

    public void disconnect() {
        shouldReconnect = false;
        main.removeCallbacks(reconnectTask);
        main.removeCallbacks(latencyTask);
        closeSocket();
        postStatus("offline", "Disconnected");
    }

    private void closeSocket() {
        WebSocket old = socket;
        socket = null;
        authenticated = false;
        if (old != null) {
            try { old.close(1000, "client reconnect"); } catch (Exception ignored) {}
        }
    }

    private void connectSocket(boolean userInitiated) {
        final String host = normalizeHost(currentHost);
        if (host.isEmpty()) {
            postStatus("error", "Find or enter a PC");
            return;
        }

        String fp = discoveredFingerprints.get(host);
        if (fp != null && !fp.isEmpty()) {
            prefs.edit().putString(certKey(host), fp).apply();
        } else {
            fp = prefs.getString(certKey(host), "");
        }
        if (fp.isEmpty()) {
            shouldReconnect = false;
            postStatus("error", "Tap Find PC once for secure setup");
            return;
        }

        if (userInitiated) postStatus("connecting", "Secure connecting...");
        main.removeCallbacks(reconnectTask);
        main.removeCallbacks(latencyTask);
        closeSocket();

        final String finalFingerprint = fp;
        final OkHttpClient client;
        try {
            client = secureClientFor(finalFingerprint);
        } catch (Exception e) {
            postStatus("error", "Secure client could not start");
            return;
        }

        Request request = new Request.Builder().url("wss://" + host + "/ws").build();
        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                if (socket != ws) return;
                try {
                    JSONObject auth = new JSONObject();
                    auth.put("type", "auth");
                    auth.put("code", currentCode);
                    String token = prefs.getString(tokenKey(finalFingerprint), "");
                    if (!token.isEmpty()) auth.put("token", token);
                    auth.put("device", android.os.Build.MODEL == null ? "Android" : android.os.Build.MODEL);
                    auth.put("appVersion", "4.1");
                    ws.send(auth.toString());
                    postStatus("authenticating", "Authenticating...");
                } catch (Exception e) {
                    postStatus("error", "Authentication failed");
                }
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                if (socket != ws) return;
                try {
                    JSONObject obj = new JSONObject(text);
                    String type = obj.optString("type", "");
                    if ("auth".equals(type)) {
                        if (obj.optBoolean("ok", false)) {
                            authenticated = true;
                            reconnectAttempt = 0;
                            String token = obj.optString("token", "");
                            if (!token.isEmpty()) prefs.edit().putString(tokenKey(finalFingerprint), token).apply();
                            String pcName = obj.optString("pcName", "PC");
                            rememberPeer(pcName, host, finalFingerprint);
                            postStatus("connected", "Secure · " + pcName);
                            main.removeCallbacks(latencyTask);
                            main.post(latencyTask);
                        } else {
                            authenticated = false;
                            shouldReconnect = false;
                            String err = obj.optString("error", "Pairing failed");
                            postStatus("error", err);
                            ws.close(1008, err);
                        }
                    } else if ("context".equals(type)) {
                        Listener l = listener;
                        if (l != null) main.post(() -> l.onContext(obj));
                    } else if ("pong".equals(type)) {
                        long sent = obj.optLong("ts", lastPingAt);
                        long ms = Math.max(0, System.currentTimeMillis() - sent);
                        Listener l = listener;
                        if (l != null) main.post(() -> l.onLatency(ms));
                    } else if ("clipboard".equals(type)) {
                        String clipboardText = obj.optString("text", "");
                        Listener l = listener;
                        if (l != null) main.post(() -> l.onClipboard(clipboardText));
                    }
                } catch (Exception ignored) {
                }
            }

            @Override public void onMessage(WebSocket ws, ByteString bytes) { onMessage(ws, bytes.utf8()); }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                if (socket != ws) return;
                socket = null;
                authenticated = false;
                main.removeCallbacks(latencyTask);
                if (shouldReconnect) {
                    postStatus("reconnecting", "Reconnecting...");
                    scheduleReconnect();
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                if (socket != ws) return;
                socket = null;
                authenticated = false;
                main.removeCallbacks(latencyTask);
                String msg = t == null ? "" : String.valueOf(t.getMessage()).toLowerCase(Locale.US);
                if (msg.contains("certificate") || msg.contains("identity")) {
                    shouldReconnect = false;
                    postStatus("error", "PC identity changed · Find PC again");
                } else if (shouldReconnect) {
                    postStatus("reconnecting", "PC offline · retrying");
                    scheduleReconnect();
                } else {
                    postStatus("error", "Connection failed");
                }
            }
        });
    }

    private void scheduleReconnect() {
        main.removeCallbacks(reconnectTask);
        reconnectAttempt = Math.min(reconnectAttempt + 1, 6);
        long delay = Math.min(8000L, 600L * (1L << Math.min(reconnectAttempt - 1, 4)));
        main.postDelayed(reconnectTask, delay);
    }

    public void send(JSONObject event) {
        WebSocket ws = socket;
        if (!authenticated || ws == null || event == null) return;
        try {
            JSONObject wrap = new JSONObject();
            wrap.put("type", "event");
            wrap.put("event", event);
            ws.send(wrap.toString());
        } catch (Exception ignored) {
        }
    }

    public void discover() {
        io.execute(() -> {
            Set<String> seen = new HashSet<>();
            DatagramSocket ds = null;
            try {
                ds = new DatagramSocket();
                ds.setBroadcast(true);
                ds.setSoTimeout(400);
                byte[] query = "WIRELESSKEY_DISCOVER_V4".getBytes(StandardCharsets.UTF_8);
                DatagramPacket out = new DatagramPacket(query, query.length, InetAddress.getByName("255.255.255.255"), 8766);
                ds.send(out);

                long end = System.currentTimeMillis() + 2400;
                byte[] buf = new byte[4096];
                while (System.currentTimeMillis() < end) {
                    try {
                        DatagramPacket in = new DatagramPacket(buf, buf.length);
                        ds.receive(in);
                        String payload = new String(in.getData(), in.getOffset(), in.getLength(), StandardCharsets.UTF_8);
                        JSONObject obj = new JSONObject(payload);
                        String ip = obj.optString("ip", in.getAddress().getHostAddress());
                        int port = obj.optInt("port", 8765);
                        String host = ip + ":" + port;
                        String fp = obj.optString("fingerprint", "");
                        if (!fp.isEmpty()) discoveredFingerprints.put(host, fp);
                        if (seen.add(host)) {
                            obj.put("ip", ip);
                            obj.put("port", port);
                            Listener l = listener;
                            if (l != null) main.post(() -> l.onDiscovery(obj));
                        }
                    } catch (SocketTimeoutException ignored) {
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                postStatus("error", "PC discovery unavailable");
            } finally {
                if (ds != null) ds.close();
                Listener l = listener;
                if (l != null) main.post(l::onDiscoveryDone);
            }
        });
    }

    public void requestClipboard() {
        WebSocket ws = socket;
        if (!authenticated || ws == null) {
            postStatus("error", "Connect to a PC first");
            return;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("type", "clipboard_get");
            ws.send(o.toString());
        } catch (Exception ignored) {
        }
    }

    public void setRemoteClipboard(String text) {
        if (text == null) text = "";
        if (text.length() > 20000) text = text.substring(0, 20000);
        try {
            JSONObject event = new JSONObject();
            event.put("type", "clipboard_set");
            event.put("text", text);
            send(event);
        } catch (Exception ignored) {
        }
    }

    public void shutdown() {
        shouldReconnect = false;
        main.removeCallbacks(reconnectTask);
        main.removeCallbacks(latencyTask);
        closeSocket();
        io.shutdownNow();
    }
}

package com.wirelesskey.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;
import org.json.JSONArray;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final ExecutorService pointerIo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WirelessKey-Pointer");
        t.setPriority(Thread.MAX_PRIORITY);
        return t;
    });
    private final ConcurrentHashMap<String, String> discoveredFingerprints = new ConcurrentHashMap<>();

    private final Object pointerLock = new Object();
    private final AtomicBoolean pointerDrainScheduled = new AtomicBoolean(false);
    private float pendingPointerDx;
    private float pendingPointerDy;
    private float pendingVScroll;
    private float pendingHScroll;
    private long newestPointerEventNanos;
    private long coalescedPointerEvents;
    private long droppedPointerEvents;
    private static final long MAX_POINTER_AGE_NANOS = 55_000_000L;
    private static final long MAX_POINTER_QUEUE_BYTES = 64 * 1024L;

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

    public boolean hasTrustedTokenForHost(String rawHost) {
        String host = normalizeHost(rawHost);
        if (host.isEmpty()) return false;

        String fingerprint = discoveredFingerprints.get(host);
        if (fingerprint == null || fingerprint.isEmpty()) {
            fingerprint = prefs.getString(certKey(host), "");
        }
        if (fingerprint.isEmpty()) return false;

        return !prefs.getString(tokenKey(fingerprint), "").isEmpty();
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
        clearPointerBacklog();
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
                    auth.put("appVersion", "4.3");
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

    public void sendPointerMove(float dx, float dy, long eventNanos) {
        if (Math.abs(dx) + Math.abs(dy) < 0.001f) return;
        synchronized (pointerLock) {
            if (Math.abs(pendingPointerDx) + Math.abs(pendingPointerDy) > 0.001f) {
                coalescedPointerEvents++;
            }
            pendingPointerDx += dx;
            pendingPointerDy += dy;
            newestPointerEventNanos = Math.max(newestPointerEventNanos, eventNanos);
        }
        schedulePointerDrain();
    }

    public void sendPointerScroll(boolean horizontal, float delta, long eventNanos) {
        if (Math.abs(delta) < 0.001f) return;
        synchronized (pointerLock) {
            if (horizontal) pendingHScroll += delta;
            else pendingVScroll += delta;
            newestPointerEventNanos = Math.max(newestPointerEventNanos, eventNanos);
        }
        schedulePointerDrain();
    }

    public void cancelPointerMotion() {
        clearPointerBacklog();
    }

    public long getCoalescedPointerEvents() {
        synchronized (pointerLock) { return coalescedPointerEvents; }
    }

    public long getDroppedPointerEvents() {
        synchronized (pointerLock) { return droppedPointerEvents; }
    }

    private void schedulePointerDrain() {
        if (!pointerDrainScheduled.compareAndSet(false, true)) return;
        pointerIo.execute(this::drainPointerQueue);
    }

    private void drainPointerQueue() {
        try {
            while (true) {
                final float dx;
                final float dy;
                final float vScroll;
                final float hScroll;
                final long eventNanos;

                synchronized (pointerLock) {
                    dx = pendingPointerDx;
                    dy = pendingPointerDy;
                    vScroll = pendingVScroll;
                    hScroll = pendingHScroll;
                    eventNanos = newestPointerEventNanos;
                    pendingPointerDx = 0f;
                    pendingPointerDy = 0f;
                    pendingVScroll = 0f;
                    pendingHScroll = 0f;
                    newestPointerEventNanos = 0L;
                }

                if (Math.abs(dx) + Math.abs(dy) + Math.abs(vScroll) + Math.abs(hScroll) < 0.001f) {
                    pointerDrainScheduled.set(false);
                    synchronized (pointerLock) {
                        if (Math.abs(pendingPointerDx) + Math.abs(pendingPointerDy)
                                + Math.abs(pendingVScroll) + Math.abs(pendingHScroll) > 0.001f
                                && pointerDrainScheduled.compareAndSet(false, true)) {
                            continue;
                        }
                    }
                    return;
                }

                WebSocket ws = socket;
                if (!authenticated || ws == null) {
                    synchronized (pointerLock) { droppedPointerEvents++; }
                    continue;
                }

                long age = eventNanos == 0L ? 0L : SystemClock.elapsedRealtimeNanos() - eventNanos;
                if (age > MAX_POINTER_AGE_NANOS || ws.queueSize() > MAX_POINTER_QUEUE_BYTES) {
                    synchronized (pointerLock) { droppedPointerEvents++; }
                    continue;
                }

                if (Math.abs(dx) + Math.abs(dy) > 0.001f) {
                    sendPointerPacket(ws, (byte)1, dx, dy);
                }
                if (Math.abs(vScroll) > 0.001f) {
                    sendPointerPacket(ws, (byte)2, vScroll, 0f);
                }
                if (Math.abs(hScroll) > 0.001f) {
                    sendPointerPacket(ws, (byte)3, hScroll, 0f);
                }
            }
        } catch (Exception ignored) {
            pointerDrainScheduled.set(false);
        }
    }

    private void sendPointerPacket(WebSocket ws, byte kind, float a, float b) {
        ByteBuffer buf = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(kind);
        buf.putFloat(a);
        buf.putFloat(b);
        ws.send(ByteString.of(buf.array()));
    }

    private void clearPointerBacklog() {
        synchronized (pointerLock) {
            pendingPointerDx = pendingPointerDy = pendingVScroll = pendingHScroll = 0f;
            newestPointerEventNanos = 0L;
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

    public void connectPairingQr(String payload) {
        final JSONObject qr;
        try {
            qr = importPairingQr(payload);
        } catch (Exception e) {
            postStatus("error", "Invalid WirelessKey QR");
            return;
        }

        final String expectedFingerprint = qr.optString("fingerprint", "").trim().toLowerCase(Locale.US);
        final String pairingCode = qr.optString("code", "").trim();
        final String pcName = qr.optString("name", qr.optString("pcName", "WirelessKey PC"));
        final String fallbackHost = normalizeHost(qr.optString("host", ""));

        postStatus("discovering", "QR verified · locating PC...");

        io.execute(() -> {
            DatagramSocket ds = null;
            String matchedHost = "";

            try {
                ds = new DatagramSocket();
                ds.setBroadcast(true);
                ds.setSoTimeout(350);

                byte[] query = "WIRELESSKEY_DISCOVER_V4".getBytes(StandardCharsets.UTF_8);
                DatagramPacket out = new DatagramPacket(
                        query, query.length, InetAddress.getByName("255.255.255.255"), 8766);
                ds.send(out);

                long end = System.currentTimeMillis() + 2200;
                byte[] buf = new byte[4096];

                while (System.currentTimeMillis() < end && matchedHost.isEmpty()) {
                    try {
                        DatagramPacket in = new DatagramPacket(buf, buf.length);
                        ds.receive(in);
                        String response = new String(
                                in.getData(), in.getOffset(), in.getLength(), StandardCharsets.UTF_8);
                        JSONObject obj = new JSONObject(response);
                        String fp = obj.optString("fingerprint", "").trim().toLowerCase(Locale.US);

                        if (fp.equalsIgnoreCase(expectedFingerprint)) {
                            String ip = obj.optString("ip", in.getAddress().getHostAddress());
                            int port = obj.optInt("port", 8765);
                            matchedHost = normalizeHost(ip + ":" + port);
                            discoveredFingerprints.put(matchedHost, expectedFingerprint);
                            prefs.edit().putString(certKey(matchedHost), expectedFingerprint).apply();
                        }
                    } catch (SocketTimeoutException ignored) {
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (ds != null) ds.close();
            }

            final boolean discoveredLive = !matchedHost.isEmpty();
            final String targetHost = discoveredLive ? matchedHost : fallbackHost;
            if (targetHost.isEmpty()) {
                postStatus("error", "QR scanned, but PC address is unavailable");
                return;
            }

            rememberPeer(pcName, targetHost, expectedFingerprint);
            prefs.edit()
                    .putString("host", targetHost)
                    .putString("code", pairingCode)
                    .apply();

            currentHost = targetHost;
            currentCode = pairingCode;
            shouldReconnect = true;
            reconnectAttempt = 0;

            main.post(() -> {
                postStatus("connecting", discoveredLive
                        ? "PC located · secure connecting..."
                        : "Connecting using QR address...");
                connectSocket(true);
            });
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
        clearPointerBacklog();
        closeSocket();
        io.shutdownNow();
        pointerIo.shutdownNow();
    }
}

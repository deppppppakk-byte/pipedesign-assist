package com.wirelesskey.remote;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class MainActivity extends Activity {
    private WebView webView;
    private SharedPreferences prefs;

    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private OkHttpClient client;
    private volatile WebSocket socket;
    private volatile boolean authenticated = false;
    private volatile boolean shouldReconnect = false;

    private String currentHost = "";
    private String currentCode = "";
    private int reconnectAttempt = 0;

    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (shouldReconnect && !authenticated && !currentHost.isEmpty()) {
                connectSocket(false);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.rgb(11, 18, 32));
        getWindow().setNavigationBarColor(Color.rgb(11, 18, 32));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersiveMode();

        prefs = getSharedPreferences("wirelesskey", MODE_PRIVATE);

        client = new OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(4, TimeUnit.SECONDS)
                .pingInterval(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(11, 18, 32));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.addJavascriptInterface(new Bridge(), "Android");
        webView.setOnLongClickListener(v -> true);
        webView.setLongClickable(false);

        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void applyImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersiveMode();
    }

    private String normalizeHost(String raw) {
        String host = raw == null ? "" : raw.trim();
        if (host.startsWith("http://")) host = host.substring(7);
        if (host.startsWith("https://")) host = host.substring(8);
        if (host.startsWith("ws://")) host = host.substring(5);
        if (host.startsWith("wss://")) host = host.substring(6);
        while (host.endsWith("/")) host = host.substring(0, host.length() - 1);
        if (!host.isEmpty() && !host.contains(":")) host += ":8765";
        return host;
    }

    private String tokenKey(String host) {
        return "trusted_" + host.replaceAll("[^A-Za-z0-9]", "_");
    }

    private void callback(String status, String detail) {
        final String js = "window.onNativeStatus("
                + JSONObject.quote(status) + ","
                + JSONObject.quote(detail == null ? "" : detail) + ")";
        mainHandler.post(() -> {
            if (webView != null) webView.evaluateJavascript(js, null);
        });
    }

    private void discoveryCallback(String json) {
        final String js = "window.onDiscoveryResult(" + json + ")";
        mainHandler.post(() -> {
            if (webView != null) webView.evaluateJavascript(js, null);
        });
    }

    private void discoveryDone() {
        mainHandler.post(() -> {
            if (webView != null) webView.evaluateJavascript("window.onDiscoveryDone()", null);
        });
    }

    private void closeSocket() {
        WebSocket old = socket;
        socket = null;
        authenticated = false;
        if (old != null) {
            try {
                old.close(1000, "client reconnect");
            } catch (Exception ignored) {
            }
        }
    }

    private void connectSocket(boolean userInitiated) {
        final String host = normalizeHost(currentHost);
        if (host.isEmpty()) {
            callback("error", "Enter or find a PC");
            return;
        }

        if (userInitiated) {
            reconnectAttempt = 0;
            callback("connecting", "Connecting...");
        }

        mainHandler.removeCallbacks(reconnectRunnable);
        closeSocket();

        final String wsUrl = "ws://" + host + "/ws";
        Request request = new Request.Builder().url(wsUrl).build();

        final WebSocket[] holder = new WebSocket[1];

        WebSocketListener listener = new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                if (socket != webSocket) return;

                try {
                    JSONObject auth = new JSONObject();
                    auth.put("type", "auth");
                    auth.put("code", currentCode == null ? "" : currentCode.trim());

                    String savedToken = prefs.getString(tokenKey(host), "");
                    if (!savedToken.isEmpty()) auth.put("token", savedToken);

                    auth.put("device", android.os.Build.MODEL == null ? "Android" : android.os.Build.MODEL);
                    auth.put("appVersion", "2.0");

                    webSocket.send(auth.toString());
                    callback("authenticating", "Authenticating...");
                } catch (Exception e) {
                    callback("error", "Authentication failed");
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                if (socket != webSocket) return;

                try {
                    JSONObject obj = new JSONObject(text);
                    String type = obj.optString("type", "");

                    if ("auth".equals(type)) {
                        boolean ok = obj.optBoolean("ok", false);
                        if (ok) {
                            authenticated = true;
                            reconnectAttempt = 0;

                            String token = obj.optString("token", "");
                            if (!token.isEmpty()) {
                                prefs.edit().putString(tokenKey(host), token).apply();
                            }

                            String pcName = obj.optString("pcName", "PC");
                            callback("connected", "Connected · " + pcName);
                        } else {
                            authenticated = false;
                            shouldReconnect = false;
                            String reason = obj.optString("error", "Wrong pairing code");
                            callback("error", reason);
                            webSocket.close(1008, reason);
                        }
                    } else if ("status".equals(type)) {
                        callback("connected", obj.optString("message", "Connected"));
                    }
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                onMessage(webSocket, bytes.utf8());
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                if (socket == webSocket) {
                    webSocket.close(code, reason);
                }
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                if (socket != webSocket) return;
                socket = null;
                authenticated = false;

                if (shouldReconnect) {
                    callback("reconnecting", "Reconnecting...");
                    scheduleReconnect();
                } else {
                    callback("offline", "Disconnected");
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                if (socket != webSocket) return;
                socket = null;
                authenticated = false;

                if (shouldReconnect) {
                    callback("reconnecting", "PC offline · retrying");
                    scheduleReconnect();
                } else {
                    callback("error", "Connection failed");
                }
            }
        };

        holder[0] = client.newWebSocket(request, listener);
        socket = holder[0];
    }

    private void scheduleReconnect() {
        mainHandler.removeCallbacks(reconnectRunnable);
        reconnectAttempt = Math.min(reconnectAttempt + 1, 6);
        long delay = Math.min(8000L, 600L * (1L << Math.min(reconnectAttempt - 1, 4)));
        mainHandler.postDelayed(reconnectRunnable, delay);
    }

    private void sendEventInternal(String eventJson) {
        WebSocket ws = socket;

        if (!authenticated || ws == null) {
            if (shouldReconnect && !currentHost.isEmpty()) {
                scheduleReconnect();
            }
            return;
        }

        try {
            JSONObject wrapper = new JSONObject();
            wrapper.put("type", "event");
            wrapper.put("event", new JSONObject(eventJson));
            ws.send(wrapper.toString());
        } catch (Exception ignored) {
        }
    }

    private void discoverReceivers() {
        io.execute(() -> {
            Set<String> seen = new HashSet<>();
            DatagramSocket ds = null;

            try {
                ds = new DatagramSocket();
                ds.setBroadcast(true);
                ds.setSoTimeout(400);

                byte[] query = "WIRELESSKEY_DISCOVER_V2".getBytes(StandardCharsets.UTF_8);
                DatagramPacket out = new DatagramPacket(
                        query,
                        query.length,
                        InetAddress.getByName("255.255.255.255"),
                        8766
                );
                ds.send(out);

                long end = System.currentTimeMillis() + 2200;
                byte[] buffer = new byte[2048];

                while (System.currentTimeMillis() < end) {
                    try {
                        DatagramPacket incoming = new DatagramPacket(buffer, buffer.length);
                        ds.receive(incoming);

                        String payload = new String(
                                incoming.getData(),
                                incoming.getOffset(),
                                incoming.getLength(),
                                StandardCharsets.UTF_8
                        );

                        JSONObject obj = new JSONObject(payload);
                        String ip = obj.optString("ip", incoming.getAddress().getHostAddress());
                        int port = obj.optInt("port", 8765);
                        String key = ip + ":" + port;

                        if (seen.add(key)) {
                            obj.put("ip", ip);
                            obj.put("port", port);
                            discoveryCallback(obj.toString());
                        }
                    } catch (SocketTimeoutException ignored) {
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                callback("error", "PC discovery unavailable");
            } finally {
                if (ds != null) ds.close();
                discoveryDone();
            }
        });
    }

    public class Bridge {
        @JavascriptInterface
        public String loadHost() {
            return prefs.getString("host", "");
        }

        @JavascriptInterface
        public String loadCode() {
            return prefs.getString("code", "");
        }

        @JavascriptInterface
        public boolean loadHaptics() {
            return prefs.getBoolean("haptics", true);
        }

        @JavascriptInterface
        public void saveSettings(String host, String code) {
            prefs.edit()
                    .putString("host", host == null ? "" : host.trim())
                    .putString("code", code == null ? "" : code.trim())
                    .apply();
        }

        @JavascriptInterface
        public void setHaptics(boolean enabled) {
            prefs.edit().putBoolean("haptics", enabled).apply();
        }

        @JavascriptInterface
        public void haptic() {
            if (!prefs.getBoolean("haptics", true)) return;

            mainHandler.post(() -> {
                if (webView != null) {
                    webView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                }
            });
        }

        @JavascriptInterface
        public void connect(String rawHost, String code) {
            currentHost = normalizeHost(rawHost);
            currentCode = code == null ? "" : code.trim();

            prefs.edit()
                    .putString("host", currentHost)
                    .putString("code", currentCode)
                    .apply();

            shouldReconnect = true;
            connectSocket(true);
        }

        @JavascriptInterface
        public void disconnect() {
            shouldReconnect = false;
            mainHandler.removeCallbacks(reconnectRunnable);
            closeSocket();
            callback("offline", "Disconnected");
        }

        @JavascriptInterface
        public void discover() {
            mainHandler.post(() -> {
                if (webView != null) webView.evaluateJavascript("window.onDiscoveryStart()", null);
            });
            discoverReceivers();
        }

        @JavascriptInterface
        public void sendEvent(String eventJson) {
            sendEventInternal(eventJson);
        }
    }

    @Override
    protected void onDestroy() {
        shouldReconnect = false;
        mainHandler.removeCallbacks(reconnectRunnable);
        closeSocket();

        try {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        } catch (Exception ignored) {
        }

        io.shutdownNow();

        if (webView != null) {
            webView.destroy();
            webView = null;
        }

        super.onDestroy();
    }
}

package com.wirelesskey.remote;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.graphics.Color;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private WebView webView;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.rgb(11,18,32));
        getWindow().setNavigationBarColor(Color.rgb(11,18,32));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersiveMode();

        prefs = getSharedPreferences("wirelesskey", MODE_PRIVATE);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(11,18,32));
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);

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
        while (host.endsWith("/")) host = host.substring(0, host.length() - 1);
        if (!host.contains(":")) host += ":8765";
        return host;
    }

    private void callback(String status, String detail) {
        final String js = "window.onNativeStatus(" + JSONObject.quote(status) + "," + JSONObject.quote(detail == null ? "" : detail) + ")";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
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
        public void saveSettings(String host, String code) {
            prefs.edit()
                    .putString("host", host == null ? "" : host.trim())
                    .putString("code", code == null ? "" : code.trim())
                    .apply();
        }

        @JavascriptInterface
        public void testConnection(String rawHost) {
            final String host = normalizeHost(rawHost);
            io.execute(() -> {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL("http://" + host + "/health").openConnection();
                    c.setConnectTimeout(2500);
                    c.setReadTimeout(2500);
                    c.setRequestMethod("GET");
                    int code = c.getResponseCode();
                    if (code == 200) callback("ready", "PC receiver found");
                    else callback("error", "Receiver returned HTTP " + code);
                } catch (Exception e) {
                    callback("error", "PC unreachable");
                } finally {
                    if (c != null) c.disconnect();
                }
            });
        }

        @JavascriptInterface
        public void sendEvent(String rawHost, String pairCode, String eventJson) {
            final String host = normalizeHost(rawHost);
            final String code = pairCode == null ? "" : pairCode.trim();

            if (host.equals(":8765") || code.length() != 6) {
                callback("error", "Enter laptop IP and 6-digit code");
                return;
            }

            io.execute(() -> {
                HttpURLConnection c = null;
                try {
                    JSONObject body = new JSONObject();
                    body.put("code", code);
                    body.put("event", new JSONObject(eventJson));

                    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                    c = (HttpURLConnection) new URL("http://" + host + "/event").openConnection();
                    c.setConnectTimeout(1800);
                    c.setReadTimeout(1800);
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setFixedLengthStreamingMode(bytes.length);

                    try (OutputStream os = c.getOutputStream()) {
                        os.write(bytes);
                    }

                    int rc = c.getResponseCode();
                    if (rc == 200) callback("connected", "Connected");
                    else if (rc == 403) callback("error", "Wrong pairing code");
                    else callback("error", "Receiver error " + rc);
                } catch (Exception e) {
                    callback("error", "Disconnected");
                } finally {
                    if (c != null) c.disconnect();
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}

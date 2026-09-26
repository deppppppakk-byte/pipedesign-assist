package com.wirelesskey.remote;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.content.SharedPreferences;

public class MainActivity extends Activity {
    private static final String PREFS = "wirelesskey";
    private static final String PREF_HOST = "host";
    private WebView webView;
    private LinearLayout connectionBar;
    private EditText hostInput;

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(11, 18, 32));
        getWindow().setNavigationBarColor(Color.rgb(11, 18, 32));

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(11, 18, 32));

        connectionBar = new LinearLayout(this);
        connectionBar.setOrientation(LinearLayout.HORIZONTAL);
        connectionBar.setGravity(Gravity.CENTER_VERTICAL);
        connectionBar.setPadding(dp(8), dp(7), dp(8), dp(7));
        connectionBar.setBackgroundColor(Color.rgb(17, 24, 39));

        TextView title = new TextView(this);
        title.setText("WirelessKey");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        connectionBar.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        hostInput = new EditText(this);
        hostInput.setSingleLine(true);
        hostInput.setHint("Laptop IP: 192.168.1.10");
        hostInput.setText(prefs.getString(PREF_HOST, ""));
        hostInput.setTextColor(Color.WHITE);
        hostInput.setHintTextColor(Color.rgb(148, 163, 184));
        hostInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        hostInput.setTextSize(14);
        LinearLayout.LayoutParams hostParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        hostParams.setMargins(dp(10), 0, dp(7), 0);
        connectionBar.addView(hostInput, hostParams);

        Button connect = new Button(this);
        connect.setText("Connect");
        connect.setAllCaps(false);
        connect.setOnClickListener(v -> connectToLaptop());
        connectionBar.addView(connect, new LinearLayout.LayoutParams(dp(94), dp(42)));

        Button hide = new Button(this);
        hide.setText("×");
        hide.setTextSize(20);
        hide.setOnClickListener(v -> connectionBar.setVisibility(View.GONE));
        LinearLayout.LayoutParams hideParams = new LinearLayout.LayoutParams(dp(50), dp(42));
        hideParams.setMargins(dp(5), 0, 0, 0);
        connectionBar.addView(hide, hideParams);

        root.addView(connectionBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(11, 18, 32));
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                connectionBar.setVisibility(View.GONE);
                Toast.makeText(MainActivity.this, "Connected to laptop", Toast.LENGTH_SHORT).show();
            }
        });

        webView.setOnLongClickListener(v -> true);
        webView.setLongClickable(false);

        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button settings = new Button(this);
        settings.setText("Connection");
        settings.setAllCaps(false);
        settings.setOnClickListener(v -> connectionBar.setVisibility(View.VISIBLE));
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        root.addView(settings, settingsParams);

        setContentView(root);

        if (!hostInput.getText().toString().trim().isEmpty()) {
            connectToLaptop();
        }
    }

    private void connectToLaptop() {
        String raw = hostInput.getText().toString().trim();
        if (raw.isEmpty()) {
            Toast.makeText(this, "Enter your laptop IP address", Toast.LENGTH_SHORT).show();
            return;
        }

        String host = raw;
        if (host.startsWith("http://")) host = host.substring(7);
        if (host.startsWith("https://")) host = host.substring(8);
        while (host.endsWith("/")) host = host.substring(0, host.length() - 1);
        if (!host.contains(":")) host += ":8765";

        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_HOST, raw).apply();
        webView.loadUrl("http://" + host);
    }

    @Override
    public void onBackPressed() {
        if (connectionBar.getVisibility() != View.VISIBLE) {
            connectionBar.setVisibility(View.VISIBLE);
        } else if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}

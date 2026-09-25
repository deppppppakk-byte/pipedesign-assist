package com.tamishra.mobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    companion object {
        private const val HOME_URL = "https://www.tamishra.in/mobile"
    }

    private lateinit var webView: WebView

    @Volatile
    private var trustedPage = false

    @Volatile
    private var screenRoom: Room? = null

    private var pendingToken: String? = null
    private var pendingServerUrl: String? = null
    private var pendingRoomName: String? = null

    private val mediaPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val screenCaptureIntentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val resultData = result.data
            if (result.resultCode != Activity.RESULT_OK || resultData == null) {
                emitNativeScreenState(false, "Screen capture permission was cancelled.")
                return@registerForActivityResult
            }

            val token = pendingToken
            val serverUrl = pendingServerUrl
            if (token.isNullOrBlank() || serverUrl.isNullOrBlank()) {
                emitNativeScreenState(false, "Screen-share authorization expired. Tap Share screen again.")
                return@registerForActivityResult
            }

            lifecycleScope.launch(Dispatchers.IO) {
                startNativeScreenPublisher(serverUrl, token, resultData)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestMediaPermissions()

        webView = WebView(this)
        setContentView(webView)

        configureWebView()
        configureBackNavigation()

        webView.loadUrl(resolveStartupUrl(intent))
    }

    private fun requestMediaPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        mediaPermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun configureWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        WebView.setWebContentsDebuggingEnabled(false)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "${userAgentString} TamishraAndroid/0.3"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }

        webView.addJavascriptInterface(TamishraBridge(), "TamishraNative")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                trustedPage = isTrustedUrl(url)
                super.onPageStarted(view, url, favicon)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                if (isTrustedUri(url)) return false

                if (url.scheme == "https" || url.scheme == "http") {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    return true
                }
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                val req = request ?: return
                if (!isTrustedUri(req.origin)) {
                    req.deny()
                    return
                }

                val granted = req.resources.filter { resource ->
                    when (resource) {
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                            ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED

                        PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                            ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.CAMERA,
                            ) == PackageManager.PERMISSION_GRANTED

                        else -> false
                    }
                }

                if (granted.isEmpty()) req.deny() else req.grant(granted.toTypedArray())
            }
        }
    }

    private fun configureBackNavigation() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        finish()
                    }
                }
            },
        )
    }

    private fun resolveStartupUrl(sourceIntent: Intent?): String {
        val uri = sourceIntent?.data ?: return HOME_URL
        if (isTrustedUri(uri)) return uri.toString()

        if (uri.scheme == "tamishra" && uri.host == "live") {
            val room = uri.getQueryParameter("room")?.trim().orEmpty()
            return if (room.isNotBlank()) {
                "https://www.tamishra.in/live/${Uri.encode(room)}?nativeShare=1"
            } else {
                "https://www.tamishra.in/live"
            }
        }

        if (uri.scheme == "tamishra" && uri.host == "meet") {
            val room = uri.getQueryParameter("room")?.trim().orEmpty()
            val code = uri.getQueryParameter("code")?.trim().orEmpty()
            return when {
                room.isNotBlank() -> "https://www.tamishra.in/meet/${Uri.encode(room)}?nativeShare=1"
                code.isNotBlank() -> "https://www.tamishra.in/meet?code=${Uri.encode(code)}"
                else -> "https://www.tamishra.in/meet"
            }
        }

        return HOME_URL
    }

    private fun isTrustedUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return runCatching { isTrustedUri(Uri.parse(url)) }.getOrDefault(false)
    }

    private fun isTrustedUri(uri: Uri?): Boolean {
        if (uri == null || uri.scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        return host == "tamishra.in" || host == "www.tamishra.in"
    }

    private inner class TamishraBridge {

        @JavascriptInterface
        fun startScreenShare(token: String, serverUrl: String, roomName: String) {
            if (!trustedPage) {
                emitNativeScreenState(false, "Screen sharing is only available on Tamishra pages.")
                return
            }
            if (token.length < 20 || !serverUrl.startsWith("wss://") || roomName.isBlank()) {
                emitNativeScreenState(false, "Invalid screen-share authorization.")
                return
            }
            if (screenRoom != null) {
                emitNativeScreenState(true, null)
                return
            }

            pendingToken = token
            pendingServerUrl = serverUrl
            pendingRoomName = roomName

            runOnUiThread {
                val manager =
                    getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                screenCaptureIntentLauncher.launch(manager.createScreenCaptureIntent())
            }
        }

        @JavascriptInterface
        fun stopScreenShare() {
            if (!trustedPage) return
            lifecycleScope.launch(Dispatchers.IO) {
                stopNativeScreenPublisher()
            }
        }
    }

    private suspend fun startNativeScreenPublisher(
        serverUrl: String,
        token: String,
        permissionData: Intent,
    ) {
        try {
            val room = LiveKit.create(applicationContext)
            screenRoom = room
            room.connect(serverUrl, token)

            val started = room.localParticipant.setScreenShareEnabled(
                true,
                ScreenCaptureParams(
                    mediaProjectionPermissionResultData = permissionData,
                    onStop = {
                        lifecycleScope.launch(Dispatchers.IO) {
                            stopNativeScreenPublisher()
                        }
                        Unit
                    },
                ),
            )

            if (!started) {
                stopNativeScreenPublisher("LiveKit could not start Android screen capture.")
                return
            }

            emitNativeScreenState(true, null)
        } catch (error: Throwable) {
            stopNativeScreenPublisher(error.message ?: "Unable to start Android screen sharing.")
        }
    }

    private suspend fun stopNativeScreenPublisher(error: String? = null) {
        val room = screenRoom
        screenRoom = null

        runCatching {
            room?.localParticipant?.setScreenShareEnabled(false)
        }
        runCatching {
            room?.disconnect()
        }
        runCatching {
            room?.release()
        }

        pendingToken = null
        pendingServerUrl = null
        pendingRoomName = null

        emitNativeScreenState(false, error)
    }

    private fun emitNativeScreenState(active: Boolean, error: String?) {
        if (!::webView.isInitialized) return

        val detail = JSONObject()
            .put("active", active)
            .apply {
                if (!error.isNullOrBlank()) put("error", error)
            }
            .toString()

        runOnUiThread {
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('tamishra:native-screen-share',{detail:$detail}));",
                null,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::webView.isInitialized) {
            webView.loadUrl(resolveStartupUrl(intent))
        }
    }

    override fun onDestroy() {
        val room = screenRoom
        screenRoom = null
        runCatching { room?.disconnect() }
        runCatching { room?.release() }
        webView.removeJavascriptInterface("TamishraNative")
        webView.destroy()
        super.onDestroy()
    }
}

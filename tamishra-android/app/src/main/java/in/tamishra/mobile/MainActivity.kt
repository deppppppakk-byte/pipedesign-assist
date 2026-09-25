package com.tamishra.mobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
        private const val FALLBACK_URL = "https://www.tamishra.in/mobile"
        private const val LIVE_URL = "https://www.tamishra.in/live"
        private const val MEET_URL = "https://www.tamishra.in/meet"
        private const val ACCOUNT_URL = "https://www.tamishra.in/dashboard"
    }

    private lateinit var webView: WebView
    private lateinit var contentFrame: FrameLayout
    private lateinit var homeView: ScrollView
    private lateinit var sectionTitle: TextView
    private lateinit var sectionSubtitle: TextView

    @Volatile private var trustedPage = false
    @Volatile private var screenRoom: Room? = null

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
        buildNativeShell()
        configureWebView()
        configureBackNavigation()

        val startup = resolveStartupUrl(intent)
        if (startup == null) showNativeHome() else openWeb(startup, sectionForUrl(startup))
    }

    private fun buildNativeShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(244, 247, 251))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(12))
            setBackgroundColor(Color.rgb(7, 24, 45))
        }

        val mark = TextView(this).apply {
            text = "T"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(18, 91, 170))
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(Color.WHITE, 16f)
        }
        topBar.addView(mark, LinearLayout.LayoutParams(dp(48), dp(48)))

        val brandStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        sectionTitle = TextView(this).apply {
            text = "Tamishra"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        sectionSubtitle = TextView(this).apply {
            text = "Native app"
            textSize = 10f
            setTextColor(Color.rgb(151, 180, 210))
        }
        brandStack.addView(sectionTitle)
        brandStack.addView(sectionSubtitle)
        topBar.addView(brandStack, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(topBar)

        contentFrame = FrameLayout(this)
        root.addView(contentFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        webView = WebView(this).apply { visibility = View.GONE }
        homeView = buildHomeView()

        contentFrame.addView(homeView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        contentFrame.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        root.addView(buildBottomBar())
        setContentView(root)
    }

    private fun buildHomeView(): ScrollView {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(244, 247, 251))
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(28))
        }

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(22))
            background = rounded(Color.rgb(10, 49, 94), 24f)
        }
        hero.addView(TextView(this).apply {
            text = "TAMISHRA"
            textSize = 10f
            letterSpacing = .18f
            setTextColor(Color.rgb(143, 190, 237))
            typeface = Typeface.DEFAULT_BOLD
        })
        hero.addView(TextView(this).apply {
            text = "Learn. Meet. Build."
            textSize = 30f
            setPadding(0, dp(8), 0, dp(8))
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        hero.addView(TextView(this).apply {
            text = "One native app for Tamishra Live Classroom, Tamishra Meet and your account."
            textSize = 13f
            setTextColor(Color.rgb(196, 216, 236))
        })
        body.addView(hero, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(18) })

        body.addView(sectionHeading("Your workspaces"))

        body.addView(nativeCard(
            "LIVE", "Live Classroom",
            "Training, webinars, attendance and instructor-led sessions."
        ) { openWeb(LIVE_URL, "live") })

        body.addView(nativeCard(
            "MEET", "Tamishra Meet",
            "Start, schedule or join a private general meeting."
        ) { openWeb(MEET_URL, "meet") })

        body.addView(nativeCard(
            "AC", "My Account",
            "Dashboard, webinars, payments, certificates and profile."
        ) { openWeb(ACCOUNT_URL, "account") })

        body.addView(sectionHeading("Quick actions").apply {
            setPadding(0, dp(10), 0, dp(8))
        })

        val quick = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        quick.addView(quickButton("Join Live") { openWeb(LIVE_URL, "live") },
            LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(8) })
        quick.addView(quickButton("New Meeting") { openWeb(MEET_URL, "meet") },
            LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(8) })
        body.addView(quick)

        scroll.addView(body)
        return scroll
    }

    private fun nativeCard(badge: String, title: String, subtitle: String, action: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(Color.WHITE, 20f)
            elevation = dp(2).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }

            addView(TextView(this@MainActivity).apply {
                text = badge
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(18, 91, 170))
                typeface = Typeface.DEFAULT_BOLD
                background = rounded(Color.rgb(234, 243, 255), 14f)
            }, LinearLayout.LayoutParams(dp(52), dp(52)))

            val textStack = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(10), 0)
            }
            textStack.addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 17f
                setTextColor(Color.rgb(20, 48, 78))
                typeface = Typeface.DEFAULT_BOLD
            })
            textStack.addView(TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 11f
                setTextColor(Color.rgb(106, 126, 147))
                setPadding(0, dp(4), 0, 0)
            })
            addView(textStack, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            addView(TextView(this@MainActivity).apply {
                text = "›"
                textSize = 28f
                setTextColor(Color.rgb(18, 91, 170))
            })
        }.also {
            (it.layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = dp(12)
        }
    }

    private fun sectionHeading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.rgb(73, 96, 121))
        typeface = Typeface.DEFAULT_BOLD
        setPadding(2, 0, 0, dp(10))
    }

    private fun quickButton(text: String, action: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(Color.rgb(18, 91, 170), 16f)
        isClickable = true
        setOnClickListener { action() }
    }

    private fun buildBottomBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(10))
            setBackgroundColor(Color.WHITE)
            elevation = dp(12).toFloat()
        }
        listOf(
            "Home" to { showNativeHome() },
            "Live" to { openWeb(LIVE_URL, "live") },
            "Meet" to { openWeb(MEET_URL, "meet") },
            "Account" to { openWeb(ACCOUNT_URL, "account") },
        ).forEach { (label, action) ->
            bar.addView(TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 11f
                setTextColor(Color.rgb(33, 66, 99))
                typeface = Typeface.DEFAULT_BOLD
                isClickable = true
                setPadding(dp(4), dp(10), dp(4), dp(10))
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        return bar
    }

    private fun showNativeHome() {
        webView.visibility = View.GONE
        homeView.visibility = View.VISIBLE
        sectionTitle.text = "Tamishra"
        sectionSubtitle.text = "Home"
    }

    private fun openWeb(url: String, section: String) {
        homeView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        sectionTitle.text = when (section) {
            "live" -> "Live Classroom"
            "meet" -> "Tamishra Meet"
            "account" -> "My Account"
            else -> "Tamishra"
        }
        sectionSubtitle.text = "Tamishra app"
        if (webView.url != url) webView.loadUrl(url)
    }

    private fun requestMediaPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
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
            userAgentString = "${userAgentString} TamishraAndroid/0.4"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }

        webView.addJavascriptInterface(TamishraBridge(), "TamishraNative")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                trustedPage = isTrustedUrl(url)
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (trustedPage) injectNativeAppCss()
                super.onPageFinished(view, url)
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
                            ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                            ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        else -> false
                    }
                }
                if (granted.isEmpty()) req.deny() else req.grant(granted.toTypedArray())
            }
        }
    }

    private fun injectNativeAppCss() {
        val js = """
            (function(){
              if(document.getElementById('tamishra-native-app-style')) return;
              var s=document.createElement('style');
              s.id='tamishra-native-app-style';
              s.textContent='.header,.footer,.footer-v40{display:none!important} body{padding-top:0!important} main{min-height:100vh!important}';
              document.head.appendChild(s);
              document.documentElement.setAttribute('data-tamishra-native','true');
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun configureBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.visibility == View.VISIBLE && webView.canGoBack()) {
                    webView.goBack()
                } else if (webView.visibility == View.VISIBLE) {
                    showNativeHome()
                } else {
                    finish()
                }
            }
        })
    }

    private fun resolveStartupUrl(sourceIntent: Intent?): String? {
        val uri = sourceIntent?.data ?: return null
        if (isTrustedUri(uri)) return uri.toString()

        if (uri.scheme == "tamishra" && uri.host == "live") {
            val room = uri.getQueryParameter("room")?.trim().orEmpty()
            return if (room.isNotBlank()) {
                "https://www.tamishra.in/live/${Uri.encode(room)}?nativeShare=1"
            } else LIVE_URL
        }

        if (uri.scheme == "tamishra" && uri.host == "meet") {
            val room = uri.getQueryParameter("room")?.trim().orEmpty()
            val code = uri.getQueryParameter("code")?.trim().orEmpty()
            return when {
                room.isNotBlank() -> "https://www.tamishra.in/meet/${Uri.encode(room)}?nativeShare=1"
                code.isNotBlank() -> "https://www.tamishra.in/meet?code=${Uri.encode(code)}"
                else -> MEET_URL
            }
        }
        return FALLBACK_URL
    }

    private fun sectionForUrl(url: String): String = when {
        url.contains("/meet") -> "meet"
        url.contains("/live") -> "live"
        url.contains("/dashboard") || url.contains("/sign-in") -> "account"
        else -> "home"
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

    private fun rounded(color: Int, radiusDp: Float) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                screenCaptureIntentLauncher.launch(manager.createScreenCaptureIntent())
            }
        }

        @JavascriptInterface
        fun stopScreenShare() {
            if (!trustedPage) return
            lifecycleScope.launch(Dispatchers.IO) { stopNativeScreenPublisher() }
        }
    }

    private suspend fun startNativeScreenPublisher(serverUrl: String, token: String, permissionData: Intent) {
        try {
            val room = LiveKit.create(applicationContext)
            screenRoom = room
            room.connect(serverUrl, token)
            val started = room.localParticipant.setScreenShareEnabled(
                true,
                ScreenCaptureParams(
                    mediaProjectionPermissionResultData = permissionData,
                    onStop = {
                        lifecycleScope.launch(Dispatchers.IO) { stopNativeScreenPublisher() }
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
        runCatching { room?.localParticipant?.setScreenShareEnabled(false) }
        runCatching { room?.disconnect() }
        runCatching { room?.release() }
        pendingToken = null
        pendingServerUrl = null
        pendingRoomName = null
        emitNativeScreenState(false, error)
    }

    private fun emitNativeScreenState(active: Boolean, error: String?) {
        if (!::webView.isInitialized) return
        val detail = JSONObject().put("active", active).apply {
            if (!error.isNullOrBlank()) put("error", error)
        }.toString()
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
        val target = resolveStartupUrl(intent)
        if (target == null) showNativeHome() else openWeb(target, sectionForUrl(target))
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

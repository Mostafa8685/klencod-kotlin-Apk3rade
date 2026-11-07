package com.apk3rde.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : Activity() {

    companion object {
        @Volatile
        var inForeground = false

        const val EXTRA_URL = "open_url"

        private const val REQ_FILE = 1001
        private const val REQ_STORAGE = 1002
        private const val REQ_NOTIF = 1003
        private const val JOB_ID = 4201
        private const val BAR_COLOR = "#071914"
        private const val GREEN = "#1DB954"

        private val NO_REFRESH_PAGES = listOf("messages.php", "admin.php", "developer.php", "rf3.php", "auth.php")
        private val INAPP_EXTERNAL_HOSTS = listOf("archive.org", "mediafire.com")

        // سكربت يتحقن في كل صفحة: يخفي رسالة تثبيت التطبيق، ويضيف مشاركة أصلية
        private const val APP_SCRIPT =
            "(function(){try{" +
                "var d=document,h=d.documentElement;" +
                "if(!d.getElementById('apk3-app-style')){var s=d.createElement('style');s.id='apk3-app-style';" +
                "s.textContent='#pwaInstallBanner,#pwaGuideModal,#apk3PushPermBtn{display:none !important}';" +
                "(d.head||h).appendChild(s);}" +
                "h.classList.add('apk3-native-app');" +
                "try{sessionStorage.setItem('apk3_pwa_dismissed','1');}catch(e){}" +
                "function fix(){if(d.body&&d.body.classList.contains('has-pwa-banner')){d.body.classList.remove('has-pwa-banner');}}" +
                "fix();" +
                "if(!window.__apk3Obs){window.__apk3Obs=true;new MutationObserver(fix).observe(h,{attributes:true,subtree:true,attributeFilter:['class']});}" +
                "if(window.AndroidApp&&!navigator.share){" +
                "navigator.share=function(o){o=o||{};AndroidApp.share(o.title||'',o.text||'',o.url||'');return Promise.resolve();};" +
                "navigator.canShare=function(){return true;};}" +
                "}catch(e){}})();"
    }

    private class PendingDownload(
        val url: String,
        val userAgent: String?,
        val contentDisposition: String?,
        val mimeType: String?
    )

    private lateinit var root: FrameLayout
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: LinearLayout
    private lateinit var splash: FrameLayout

    private val handler = Handler(Looper.getMainLooper())
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingDownload: PendingDownload? = null
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var hasError = false
    private var renderGone = false
    private var splashHidden = false
    private var lastBackTime = 0L
    private var currentBarColor = Color.parseColor(BAR_COLOR)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyBarColor(Color.parseColor(BAR_COLOR))

        root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor(BAR_COLOR))

        webView = WebView(this)
        webView.setBackgroundColor(Color.parseColor(BAR_COLOR))
        webView.overScrollMode = View.OVER_SCROLL_NEVER

        swipe = SwipeRefreshLayout(this)
        swipe.setColorSchemeColors(Color.parseColor(GREEN))
        swipe.setProgressBackgroundColorSchemeColor(Color.parseColor("#123128"))
        swipe.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
        swipe.setOnRefreshListener { webView.reload() }
        swipe.addView(
            webView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addView(
            swipe,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = 100
        progressBar.visibility = View.GONE
        root.addView(
            progressBar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP)
        )

        errorView = buildErrorView()
        root.addView(
            errorView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        splash = buildSplash()
        root.addView(
            splash,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        setContentView(root)
        setupWebView()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(resolveStartUrl(intent) ?: AppConfig.START_URL)
        }

        // لو الصفحة اتأخرت، نشيل شاشة البداية بعد 6 ثواني
        handler.postDelayed({ hideSplash() }, 6000)

        scheduleNotifyJob()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = resolveStartUrl(intent)
        if (url != null) webView.loadUrl(url)
    }

    private fun resolveStartUrl(i: Intent?): String? {
        if (i == null) return null
        val raw = i.getStringExtra(EXTRA_URL) ?: i.dataString ?: return null
        val uri = Uri.parse(raw)
        if (uri.scheme != "https") return null
        return if (AppConfig.isInternalHost(uri.host)) raw else null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = true
        s.mediaPlaybackRequiresUserGesture = false
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.userAgentString = s.userAgentString + " Apk3radeApp/1.0"

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(AppBridge(), "AndroidApp")

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(PendingDownload(url, userAgent, contentDisposition, mimeType))
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme ?: return false
                val host = uri.host ?: ""

                if (scheme == "http" || scheme == "https") {
                    if (AppConfig.isInternalHost(host)) return false

                    // تحويلات التحميل (مثل archive.org / mediafire) تكمل جوه التطبيق
                    if (request.isRedirect) return false
                    if (INAPP_EXTERNAL_HOSTS.any { host == it || host.endsWith(".$it") }) return false

                    val path = uri.path ?: ""
                    if (path.endsWith(".apk", true) || path.endsWith(".xapk", true) || path.endsWith(".apks", true)) {
                        startDownload(PendingDownload(uri.toString(), null, null, null))
                        return true
                    }
                    openExternal(uri)
                    return true
                }

                if (scheme == "intent") {
                    openIntentUri(uri.toString())
                    return true
                }

                openExternal(uri)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                hasError = false
                errorView.visibility = View.GONE
                progressBar.visibility = View.VISIBLE
                updateRefreshEnabled(url)
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                view.evaluateJavascript(APP_SCRIPT, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progressBar.visibility = View.GONE
                swipe.isRefreshing = false
                updateRefreshEnabled(url)
                if (!hasError) {
                    errorView.visibility = View.GONE
                    view.evaluateJavascript(APP_SCRIPT, null)
                    readThemeColor()
                    checkLoggedIn()
                }
                hideSplash()
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    hasError = true
                    errorView.visibility = View.VISIBLE
                    progressBar.visibility = View.GONE
                    swipe.isRefreshing = false
                    hideSplash()
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                renderGone = true
                recreate()
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    startActivityForResult(fileChooserParams.createIntent(), REQ_FILE)
                    true
                } catch (e: Exception) {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = null
                    toast("تعذر فتح مدير الملفات")
                    true
                }
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                root.addView(
                    view,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                swipe.visibility = View.INVISIBLE
                setImmersive(true)
            }

            override fun onHideCustomView() {
                exitCustomView()
            }
        }
    }

    private fun exitCustomView() {
        val v = customView ?: return
        root.removeView(v)
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        swipe.visibility = View.VISIBLE
        setImmersive(false)
    }

    @Suppress("DEPRECATION")
    private fun setImmersive(on: Boolean) {
        if (on) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            window.decorView.systemUiVisibility = 0
            applyBarColor(currentBarColor)
        }
    }

    inner class AppBridge {
        @JavascriptInterface
        fun share(title: String?, text: String?, url: String?) {
            runOnUiThread {
                val body = listOf(text, url).filter { !it.isNullOrBlank() }.joinToString("\n")
                if (body.isBlank()) return@runOnUiThread
                val send = Intent(Intent.ACTION_SEND)
                send.type = "text/plain"
                if (!title.isNullOrBlank()) send.putExtra(Intent.EXTRA_SUBJECT, title)
                send.putExtra(Intent.EXTRA_TEXT, body)
                startActivity(Intent.createChooser(send, "مشاركة"))
            }
        }

        @JavascriptInterface
        fun isApp(): Boolean = true
    }

    private fun buildSplash(): FrameLayout {
        val layout = FrameLayout(this)
        layout.setBackgroundColor(Color.parseColor(BAR_COLOR))
        layout.isClickable = true
        val icon = ImageView(this)
        icon.setImageResource(R.drawable.app_icon)
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        layout.addView(icon, FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER))
        return layout
    }

    private fun hideSplash() {
        if (splashHidden) return
        splashHidden = true
        splash.animate().alpha(0f).setDuration(250).withEndAction {
            splash.visibility = View.GONE
        }.start()
    }

    private fun buildErrorView(): LinearLayout {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.gravity = Gravity.CENTER
        layout.setBackgroundColor(Color.parseColor(BAR_COLOR))
        layout.setPadding(dp(32), dp(32), dp(32), dp(32))
        layout.visibility = View.GONE
        layout.isClickable = true

        val title = TextView(this)
        title.text = "تعذر الاتصال بالمتجر"
        title.textSize = 20f
        title.setTextColor(Color.parseColor("#F1F5F9"))
        title.gravity = Gravity.CENTER

        val msg = TextView(this)
        msg.text = "تأكد من اتصالك بالإنترنت ثم حاول مرة أخرى"
        msg.textSize = 15f
        msg.setTextColor(Color.parseColor("#94A3B8"))
        msg.gravity = Gravity.CENTER
        msg.setPadding(0, dp(8), 0, dp(24))

        val retry = Button(this)
        retry.text = "إعادة المحاولة"
        retry.setOnClickListener {
            errorView.visibility = View.GONE
            if (webView.url == null) webView.loadUrl(AppConfig.START_URL) else webView.reload()
        }

        layout.addView(title)
        layout.addView(msg)
        layout.addView(retry)
        return layout
    }

    private fun updateRefreshEnabled(url: String?) {
        val path = (if (url != null) Uri.parse(url).path else null) ?: ""
        val lower = path.lowercase()
        swipe.isEnabled = NO_REFRESH_PAGES.none { lower.contains(it) }
    }

    private fun readThemeColor() {
        webView.evaluateJavascript(
            "(function(){var m=document.querySelector('meta[name=theme-color]');return m?m.content:'';})()"
        ) { result ->
            val value = result?.trim('"') ?: ""
            if (value.isNotEmpty()) {
                try {
                    applyBarColor(Color.parseColor(value))
                } catch (e: IllegalArgumentException) {
                    // لون غير مدعوم
                }
            }
        }
    }

    private fun checkLoggedIn() {
        webView.evaluateJavascript(
            "(window.APK3_IS_LOGGED_IN===true||window.APK3_IS_LOGGED_IN===1||window.APK3_IS_LOGGED_IN==='1')?'1':'0'"
        ) { result ->
            if (result != null && result.contains("1")) askNotificationPermissionOnce()
        }
    }

    private fun askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        val prefs = getSharedPreferences("apk3_app", Context.MODE_PRIVATE)
        if (prefs.getBoolean("notif_asked", false)) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        prefs.edit().putBoolean("notif_asked", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
    }

    private fun scheduleNotifyJob() {
        try {
            val js = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (js.getPendingJob(JOB_ID) != null) return
            val info = JobInfo.Builder(JOB_ID, ComponentName(this, NotifyJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build()
            js.schedule(info)
        } catch (e: Exception) {
            // الإشعارات اختيارية، التطبيق يكمل عادي
        }
    }

    @Suppress("DEPRECATION")
    private fun applyBarColor(color: Int) {
        currentBarColor = color
        window.statusBarColor = color
        window.navigationBarColor = color
        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0
        var flags = window.decorView.systemUiVisibility
        flags = if (luminance > 0.6) {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        } else {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
        window.decorView.systemUiVisibility = flags
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            toast("لا يوجد تطبيق لفتح هذا الرابط")
        }
    }

    private fun openIntentUri(raw: String) {
        try {
            val i = Intent.parseUri(raw, Intent.URI_INTENT_SCHEME)
            i.addCategory(Intent.CATEGORY_BROWSABLE)
            i.component = null
            i.selector = null
            startActivity(i)
        } catch (e: Exception) {
            toast("لا يوجد تطبيق لفتح هذا الرابط")
        }
    }

    private fun startDownload(d: PendingDownload) {
        if (d.url.startsWith("blob:") || d.url.startsWith("data:")) {
            toast("هذا النوع من التحميل غير مدعوم")
            return
        }

        // Android 9 وأقل يحتاج إذن التخزين
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = d
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            return
        }

        try {
            val name = URLUtil.guessFileName(d.url, d.contentDisposition, d.mimeType)
            val request = DownloadManager.Request(Uri.parse(d.url))
            if (!d.mimeType.isNullOrEmpty()) request.setMimeType(d.mimeType)
            CookieManager.getInstance().getCookie(d.url)?.let { request.addRequestHeader("Cookie", it) }
            d.userAgent?.let { request.addRequestHeader("User-Agent", it) }
            request.setTitle(name)
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            toast("بدأ التحميل: $name")
        } catch (e: Exception) {
            toast("فشل بدء التحميل")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) {
            val d = pendingDownload
            pendingDownload = null
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED && d != null) {
                startDownload(d)
            } else {
                toast("مطلوب إذن التخزين للتحميل")
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE) {
            val result = if (resultCode == RESULT_OK) {
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            } else {
                null
            }
            fileCallback?.onReceiveValue(result)
            fileCallback = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (customView != null) {
            exitCustomView()
            return
        }
        if (errorView.visibility != View.VISIBLE && webView.canGoBack()) {
            webView.goBack()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackTime < 2000) {
            super.onBackPressed()
        } else {
            lastBackTime = now
            toast("اضغط مرة أخرى للخروج")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!renderGone) webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        inForeground = false
        webView.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
        webView.onResume()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
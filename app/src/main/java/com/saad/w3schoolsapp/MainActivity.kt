package com.saad.w3schoolsapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.saad.w3schoolsapp.databinding.ActivityMainBinding

/**
 * Single-Activity WebView shell around https://www.w3schools.com.
 *
 * The two rules that matter most, and that are easy to accidentally violate
 * while "improving" this file later:
 *
 *  1. Nothing here ever calls CookieManager.removeAll/removeSessionCookies,
 *     WebStorage.deleteAllData, WebView.clearCache/clearHistory/clearFormData,
 *     or deletes the WebView data directory. That omission - not some special
 *     persistence trick - is what keeps a login alive indefinitely from the
 *     app's side. The actual cookie lifetime is still whatever W3Schools'
 *     server issued; this app just never shortens it.
 *  2. onProgressChanged() is intentionally empty. That's what keeps a loading
 *     bar from ever appearing during navigation.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var minSplashTimeElapsed = false
    private var pageHasLoaded = false
    private val splashHandler = Handler(Looper.getMainLooper())

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris: Array<Uri>? = if (result.resultCode == RESULT_OK) {
                result.data?.let { data ->
                    WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
                }
            } else {
                null
            }
            pendingFileCallback?.onReceiveValue(uris)
            pendingFileCallback = null
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Downloads still work either way; this only affects whether the
            // "download complete" system notification is shown.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyImmersiveMode()
        requestNotificationPermissionIfNeeded()
        setupWebView()
        setupDownloadListener()
        setupBackNavigation()
        setupSwipeToRefresh()
        setupRetryButton()

        scheduleSplashMinimumTime()

        if (savedInstanceState != null) {
            // Returning from a process death / config change: the page is
            // already rendered, so don't make the user wait on a fresh load.
            binding.webView.restoreState(savedInstanceState)
            pageHasLoaded = true
            tryDismissSplash()
        } else {
            binding.webView.loadUrl(getString(R.string.start_url))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        // Write cookies to disk now, so a swipe-kill (not just a clean exit)
        // still preserves the session. This is a flush, never a clear.
        CookieManager.getInstance().flush()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyWebViewDarkMode()
    }

    override fun onDestroy() {
        splashHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // WebView setup
    // ------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView
        val settings: WebSettings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true // localStorage - part of the "stay logged in" story
        settings.cacheMode = WebSettings.LOAD_DEFAULT // let the site's own caching/service worker behave normally
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        // Cookies: accepted, first- and third-party (the latter covers
        // Google-based sign-in flows). No clearing call exists anywhere in
        // this app - see the class-level doc comment.
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        applyWebViewDarkMode()

        webView.webViewClient = buildWebViewClient()
        webView.webChromeClient = buildWebChromeClient()
    }

    private fun buildWebViewClient(): WebViewClient = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val uri = request?.url ?: return false
            return if (isAllowedHost(uri.host)) {
                false // keep it inside this WebView
            } else {
                // Something outside the site (or an auth redirect to an
                // unrelated domain) - hand it to the system rather than
                // dead-ending inside the app.
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: Exception) {
                    // No app can handle it - nothing sensible to do but ignore.
                }
                true
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            binding.swipeRefresh.isRefreshing = false
            if (binding.offlineLayout.visibility == View.VISIBLE) {
                binding.offlineLayout.visibility = View.GONE
            }
            pageHasLoaded = true
            tryDismissSplash()
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame == true) {
                binding.swipeRefresh.isRefreshing = false
                binding.offlineLayout.visibility = View.VISIBLE
            }
            // Even a failed load counts as "loading is done" for splash purposes -
            // the splash must never get stuck waiting on a page that will never finish.
            pageHasLoaded = true
            tryDismissSplash()
        }
    }

    private fun buildWebChromeClient(): WebChromeClient = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            // Intentionally empty - no loading bar, ever, during navigation.
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = filePathCallback
            val intent = fileChooserParams?.createIntent()
            return if (intent != null) {
                try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (_: Exception) {
                    pendingFileCallback = null
                    false
                }
            } else {
                false
            }
        }

        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            // Without this, target="_blank" links and window.open() calls
            // silently do nothing (a common WebView-wrapper bug). Instead,
            // re-attach the request to the same WebView.
            val msg = resultMsg ?: return false
            val transport = msg.obj as? WebView.WebViewTransport ?: return false
            transport.webView = binding.webView
            msg.sendToTarget()
            return true
        }
    }

    private fun isAllowedHost(host: String?): Boolean {
        if (host == null) return false
        return ALLOWED_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }
    }

    private fun applyWebViewDarkMode() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(binding.webView.settings, isNightMode)
        }
        // If this device's WebView is too old to support algorithmic darkening,
        // the page simply renders in its normal (light) styling - there is no
        // safe way to force a site's own colors from outside it.
    }

    // ------------------------------------------------------------------
    // Downloads
    // ------------------------------------------------------------------

    private fun setupDownloadListener() {
        binding.webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val cookie = CookieManager.getInstance().getCookie(url)

                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    if (!cookie.isNullOrEmpty()) addRequestHeader("cookie", cookie)
                    addRequestHeader("User-Agent", userAgent)
                    setMimeType(mimeType)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    setTitle(fileName)
                    setAllowedOverMetered(true)
                }

                val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                downloadManager.enqueue(request)
                Toast.makeText(this, getString(R.string.download_started, fileName), Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // ------------------------------------------------------------------
    // Offline handling / pull-to-refresh
    // ------------------------------------------------------------------

    private fun setupSwipeToRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            binding.webView.reload()
        }
    }

    private fun setupRetryButton() {
        binding.retryButton.setOnClickListener {
            if (isOnline()) {
                binding.offlineLayout.visibility = View.GONE
                binding.webView.reload()
            } else {
                Toast.makeText(this, R.string.still_offline, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isOnline(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ------------------------------------------------------------------
    // Full-screen / immersive chrome
    // ------------------------------------------------------------------

    private fun applyImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // ------------------------------------------------------------------
    // Splash screen
    // ------------------------------------------------------------------

    private fun scheduleSplashMinimumTime() {
        splashHandler.postDelayed({
            minSplashTimeElapsed = true
            tryDismissSplash()
        }, SPLASH_MIN_DISPLAY_MS)
    }

    private fun tryDismissSplash() {
        if (minSplashTimeElapsed && pageHasLoaded && binding.splashLayout.visibility != View.GONE) {
            binding.splashLayout.animate()
                .alpha(0f)
                .setDuration(SPLASH_FADE_MS)
                .withEndAction { binding.splashLayout.visibility = View.GONE }
                .start()
        }
    }

    // ------------------------------------------------------------------
    // Back navigation
    // ------------------------------------------------------------------

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this) {
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            } else {
                showExitConfirmation()
            }
        }
    }

    private fun showExitConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.exit_dialog_title)
            .setMessage(R.string.exit_dialog_message)
            .setNegativeButton(R.string.exit_dialog_cancel, null)
            .setPositiveButton(R.string.exit_dialog_exit) { _, _ -> finish() }
            .show()
    }

    companion object {
        private val ALLOWED_HOST_SUFFIXES = listOf("w3schools.com", "google.com", "gstatic.com")
        private const val SPLASH_MIN_DISPLAY_MS = 1200L
        private const val SPLASH_FADE_MS = 300L
    }
}

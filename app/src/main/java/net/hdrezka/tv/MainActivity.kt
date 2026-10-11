package net.hdrezka.tv

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var cursorLayout: CursorLayout
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var errorView: TextView
    private lateinit var fullscreenContainer: FrameLayout

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var lastBackPress = 0L

    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }
    private val siteHost: String = Uri.parse(BuildConfig.START_URL).host.orEmpty()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        cursorLayout = findViewById(R.id.cursorLayout)
        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.errorView)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)

        cursorLayout.cursorEnabled = prefs.getBoolean(KEY_CURSOR, true)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            // Новые окна (target=_blank, window.open) перехватываем в onCreateWindow
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            // Убираем маркер WebView ("; wv"), чтобы сайт отдавал обычную версию
            userAgentString = userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                isBlockedScheme(request.url)

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                isBlockedScheme(Uri.parse(url)) // API 21–23

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                errorView.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView, url: String) {
                // Запоминаем только страницы самого сайта, чтобы не открывать рекламу при запуске
                if (isSameSite(Uri.parse(url))) prefs.edit().putString(KEY_LAST_URL, url).apply()
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) errorView.visibility = View.VISIBLE
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                // API 21–22: вызывается только для основного фрейма
                errorView.visibility = View.VISIBLE
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture) return false
                // Временный WebView узнаёт адрес нового окна; ссылки сайта открываем здесь же,
                // всё остальное (всплывающая реклама) отбрасываем
                val popup = WebView(this@MainActivity)
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean =
                        openPopupUrl(v, request.url)

                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(v: WebView, url: String): Boolean =
                        openPopupUrl(v, Uri.parse(url))
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = popup
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(view, FrameLayout.LayoutParams(-1, -1))
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.INVISIBLE
                hideSystemBars()
                view.isFocusable = true
                view.requestFocus()
            }

            override fun onHideCustomView() = exitFullscreen()
        }

        onBackPressedDispatcher.addCallback(this) {
            when {
                customView != null -> exitFullscreen()
                webView.canGoBack() -> webView.goBack()
                System.currentTimeMillis() - lastBackPress < 2000 -> finish()
                else -> {
                    lastBackPress = System.currentTimeMillis()
                    Toast.makeText(this@MainActivity, R.string.press_back_again, Toast.LENGTH_SHORT).show()
                }
            }
        }

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(prefs.getString(KEY_LAST_URL, null) ?: BuildConfig.START_URL)
        }
        webView.requestFocus()
    }

    private fun isBlockedScheme(url: Uri): Boolean {
        val scheme = url.scheme?.lowercase()
        // Блокируем intent://, market:// и прочие внешние схемы (рекламные редиректы)
        return scheme != "http" && scheme != "https"
    }

    private fun isSameSite(url: Uri): Boolean {
        val host = url.host ?: return false
        return host == siteHost || host.endsWith(".$siteHost")
    }

    private fun openPopupUrl(popup: WebView, url: Uri): Boolean {
        if (isSameSite(url)) webView.loadUrl(url.toString())
        popup.post { popup.destroy() }
        return true
    }

    private fun exitFullscreen() {
        val view = customView ?: return
        fullscreenContainer.removeView(view)
        fullscreenContainer.visibility = View.GONE
        webView.visibility = View.VISIBLE
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        webView.requestFocus()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                // Меню / кнопка «Настройки» — переключение курсор ↔ обычная навигация стрелками
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                    toggleCursor(); return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { mediaJs("v.paused ? v.play() : v.pause()"); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { mediaJs("v.play()"); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { mediaJs("v.pause()"); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { mediaJs("v.currentTime += 15"); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { mediaJs("v.currentTime -= 15"); return true }
                KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    cursorLayout.scrollAtCursor(0f, cursorLayout.height * 0.8f); return true
                }
                KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    cursorLayout.scrollAtCursor(0f, -cursorLayout.height * 0.8f); return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (errorView.visibility == View.VISIBLE) {
                        webView.reload(); return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Управление основным <video> на странице — самым большим по площади,
     * чтобы не попасть в маленькое рекламное превью. Не работает, если плеер в чужом iframe.
     */
    private fun mediaJs(action: String) {
        webView.evaluateJavascript(
            "(function(){var v=Array.prototype.slice.call(document.querySelectorAll('video'))" +
                ".sort(function(a,b){return b.clientWidth*b.clientHeight-a.clientWidth*a.clientHeight})[0];" +
                "if(v){$action;}})()",
            null
        )
    }

    private fun toggleCursor() {
        val enabled = !cursorLayout.cursorEnabled
        cursorLayout.cursorEnabled = enabled
        prefs.edit().putBoolean(KEY_CURSOR, enabled).apply()
        Toast.makeText(
            this, if (enabled) R.string.mode_cursor else R.string.mode_dpad, Toast.LENGTH_SHORT
        ).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        hideSystemBars()
    }

    override fun onDestroy() {
        exitFullscreen()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_CURSOR = "cursor_enabled"
    }
}

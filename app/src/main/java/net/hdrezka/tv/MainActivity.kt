package net.hdrezka.tv

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.InputDevice
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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
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
    private lateinit var menuPanel: MenuPanel
    private var sections: List<Section> = emptyList()
    private var swallowOk = false // долгое OK открыло меню — гасим остаток нажатия
    private lateinit var spatialNav: SpatialNav
    private lateinit var player: PlayerController

    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }
    private val siteHost: String = Uri.parse(BuildConfig.START_URL).host.orEmpty()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG) // chrome://inspect для отладки
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        cursorLayout = findViewById(R.id.cursorLayout)
        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.errorView)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        menuPanel = MenuPanel(findViewById<ScrollView>(R.id.menuPanel), findViewById<LinearLayout>(R.id.menuItems))
        sections = MenuPanel.parse(prefs.getString(KEY_SECTIONS, null))

        // По умолчанию стрелки ходят по элементам, а курсором управляет air mouse;
        // экранный курсор стрелками — запасной режим для пультов без гироскопа
        cursorLayout.cursorEnabled = prefs.getBoolean(KEY_CURSOR, false)
        spatialNav = SpatialNav(webView, { x, y -> cursorLayout.tapAt(x, y) }) { player.play() }
        player = PlayerController(webView, cursorLayout, { x, y -> cursorLayout.tapAt(x, y) }) {
            // Вернулись из полноэкранного режима на страницу сериала
            webView.requestFocus()
        }

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
                player.onPageStarted()
            }

            override fun onPageFinished(view: WebView, url: String) {
                // Запоминаем только страницы самого сайта, чтобы не открывать рекламу при запуске
                if (isSameSite(Uri.parse(url))) {
                    prefs.edit().putString(KEY_LAST_URL, url).apply()
                    collectSections()
                    player.onPageFinished()
                }
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
                // Реклама могла не перейти ни по какой ссылке — не оставляем WebView висеть
                webView.postDelayed({ popup.destroy() }, 5000)
                return true
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                closeMenu()
                player.externalFullscreen = true
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
                menuPanel.isOpen -> closeMenu()
                customView != null -> exitFullscreen()
                player.handleBack() -> Unit
                // Сначала закрываем открытый на странице выпадающий список, потом уходим назад
                !cursorLayout.cursorEnabled -> spatialNav.escape { closed -> if (!closed) navigateBack() }
                else -> navigateBack()
            }
        }

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(prefs.getString(KEY_LAST_URL, null) ?: BuildConfig.START_URL)
        }
        webView.requestFocus()
    }

    private fun navigateBack() {
        when {
            webView.canGoBack() -> webView.goBack()
            System.currentTimeMillis() - lastBackPress < 2000 -> finish()
            else -> {
                lastBackPress = System.currentTimeMillis()
                Toast.makeText(this, R.string.press_back_again, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Обновить список разделов из меню текущей страницы сайта. */
    private fun collectSections() {
        webView.evaluateJavascript(MenuPanel.EXTRACT_JS) { result ->
            val found = MenuPanel.parse(result)
            if (found.isNotEmpty()) {
                sections = found
                prefs.edit().putString(KEY_SECTIONS, MenuPanel.toJson(found)).apply()
            }
        }
    }

    private fun openMenu() {
        if (customView != null || menuPanel.isOpen) return
        cursorLayout.suspended = true
        val home = Section(getString(R.string.menu_home), BuildConfig.START_URL, 0)
        val list = listOf(home) + sections.filterNot { it.url.trimEnd('/') == home.url.trimEnd('/') }
        val actions = buildList {
            player.state.let { s ->
                if (s.hasPrev) add(MenuPanel.Action(getString(R.string.menu_episode_prev)) { closeMenu(); player.episode(-1) })
                if (s.hasNext) add(MenuPanel.Action(getString(R.string.menu_episode_next)) { closeMenu(); player.episode(1) })
            }
            if (webView.canGoBack()) add(MenuPanel.Action(getString(R.string.menu_back)) { closeMenu(); webView.goBack() })
            add(MenuPanel.Action(getString(R.string.menu_reload)) { closeMenu(); webView.reload() })
            val modeTitle = if (cursorLayout.cursorEnabled) R.string.menu_cursor_on else R.string.menu_cursor_off
            add(MenuPanel.Action(getString(modeTitle)) { closeMenu(); toggleCursor() })
        }
        menuPanel.open(list, { closeMenu(); webView.loadUrl(it.url) }, actions)
    }

    private fun closeMenu() {
        if (!menuPanel.isOpen) return
        menuPanel.close()
        cursorLayout.suspended = false
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
        popup.stopLoading()
        return true
    }

    private fun exitFullscreen() {
        val view = customView ?: return
        fullscreenContainer.removeView(view)
        fullscreenContainer.visibility = View.GONE
        webView.visibility = View.VISIBLE
        customView = null
        player.externalFullscreen = false
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
        // Видео на весь экран: все кнопки обрабатывает обёртка плеера
        if (player.handleKey(event)) return true
        if (player.isFullscreen) return super.dispatchKeyEvent(event)
        if (handleLongOk(event)) return true
        // Отпускание обработанных медиакнопок тоже поглощаем, иначе система отдаст его активному медиаприложению
        if (event.action == KeyEvent.ACTION_UP && event.keyCode in HANDLED_MEDIA_KEYS && !menuPanel.isOpen) return true
        if (handleSpatialNav(event)) return true
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                // Меню / «Настройки» — панель разделов
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                    if (menuPanel.isOpen) closeMenu() else openMenu()
                    return true
                }
            }
            // Пока открыта панель, остальные кнопки — обычная навигация по её пунктам
            if (menuPanel.isOpen) return super.dispatchKeyEvent(event)
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { player.togglePlay(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { mediaJs("v.play()"); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { mediaJs("v.pause()"); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { player.seek(10); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { player.seek(-10); return true }
                KeyEvent.KEYCODE_MEDIA_NEXT -> { player.episode(1); return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { player.episode(-1); return true }
                // CH+/CH−: на странице серии — переключение серий, иначе прокрутка
                KeyEvent.KEYCODE_CHANNEL_UP -> {
                    if (!player.episode(1)) cursorLayout.scrollAtCursor(0f, -cursorLayout.height * 0.8f)
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if (!player.episode(-1)) cursorLayout.scrollAtCursor(0f, cursorLayout.height * 0.8f)
                    return true
                }
                KeyEvent.KEYCODE_PAGE_DOWN -> { cursorLayout.scrollAtCursor(0f, cursorLayout.height * 0.8f); return true }
                KeyEvent.KEYCODE_PAGE_UP -> { cursorLayout.scrollAtCursor(0f, -cursorLayout.height * 0.8f); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (errorView.visibility == View.VISIBLE) {
                        webView.reload(); return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Режим стрелок: стрелки выбирают элементы страницы, OK нажимает выбранный (на отпускании). */
    private fun handleSpatialNav(event: KeyEvent): Boolean {
        if (menuPanel.isOpen || errorView.visibility == View.VISIBLE) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        val dir = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            else -> null
        }

        if (cursorLayout.cursorEnabled || customView != null) return false
        if (dir != null) {
            if (down) spatialNav.move(dir)
            return true
        }
        val isOk = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            ((event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) &&
                event.device?.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC)
        if (isOk) {
            // Долгое нажатие перехватывает handleLongOk — сюда доходит только короткое
            if (!down) spatialNav.activate()
            return true
        }
        return false
    }

    /** Долгое нажатие OK открывает панель разделов (на многих пультах нет кнопки Menu). */
    private fun handleLongOk(event: KeyEvent): Boolean {
        val isOk = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            ((event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) &&
                event.device?.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC) // удержание Enter при наборе текста
        if (!isOk) return false
        if (swallowOk) {
            if (event.action == KeyEvent.ACTION_UP) swallowOk = false
            return true
        }
        val longPress = event.isLongPress || event.repeatCount >= LONG_PRESS_REPEATS
        if (event.action == KeyEvent.ACTION_DOWN && longPress && !menuPanel.isOpen && customView == null) {
            swallowOk = true
            openMenu()
            return true
        }
        return false
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
        if (enabled) spatialNav.reset()
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
        player.onBackground()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        player.onPageFinished() // возобновить опрос плеера
        hideSystemBars()
    }

    override fun onDestroy() {
        player.release() // снять отложенные задачи, пока WebView ещё прикреплён
        exitFullscreen()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_CURSOR = "cursor_enabled"
        private const val KEY_SECTIONS = "sections"
        private const val LONG_PRESS_REPEATS = 10
        private val HANDLED_MEDIA_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
        )
    }
}

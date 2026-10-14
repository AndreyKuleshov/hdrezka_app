package net.hdrezka.tv

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
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
import androidx.annotation.RequiresApi
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
    private lateinit var loadingOverlay: View

    // Экраны поверх страницы разворачиваются при первом обращении, поэтому держим их отдельно
    // от проверок «открыт ли»: иначе первая же кнопка пульта создавала бы их все
    private var libraryOrNull: LibraryScreen? = null
    private var confirmOrNull: ConfirmOverlay? = null
    private var hintView: View? = null
    private val postersLazy = lazy { PosterCache(this) }
    private val posters by postersLazy

    private val library: LibraryScreen
        get() = libraryOrNull ?: LibraryScreen(
            findViewById<ViewStub>(R.id.libraryStub).inflate(), store, posters,
            onOpen = { url, play -> openFromLibrary(url, play) },
            // Долгое OK везде открывает панель, а тут стирало прогресс: спрашиваем
            onConfirmRemove = { name, remove ->
                confirm.ask(
                    getString(R.string.confirm_remove_title),
                    getString(R.string.confirm_remove_text, name),
                    getString(R.string.confirm_remove_yes),
                    getString(R.string.confirm_cancel),
                    remove,
                )
            },
        ).also { libraryOrNull = it }

    private val libraryOpen get() = libraryOrNull?.isOpen == true

    private val confirm: ConfirmOverlay
        get() = confirmOrNull
            ?: ConfirmOverlay(findViewById<ViewStub>(R.id.confirmStub).inflate())
                .also { confirmOrNull = it }

    private val confirmOpen get() = confirmOrNull?.isOpen == true

    private val hintVisible get() = hintView?.visibility == View.VISIBLE

    private fun showHint() {
        val view = hintView ?: findViewById<ViewStub>(R.id.hintStub).inflate().also { hintView = it }
        view.visibility = View.VISIBLE
    }
    private var loadingSince = 0L
    /** Номер загрузки: ответы опроса готовности от прежней страницы отбрасываются. */
    private var loadGeneration = 0
    /** Серию открыли из библиотеки — ждём не страницу, а плеер, как при выборе серии на сайте. */
    private var autoplayRequested = false
    private var autoplayPending = false // загрузка начата выбором серии — ждём не страницу, а плеер

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var lastBackPress = 0L
    private var clearHistoryOnLoad = false
    private lateinit var menuPanel: MenuPanel
    private var sections: List<Section> = emptyList()
    private var swallowOk = false // долгое OK открыло меню — гасим остаток нажатия
    /** До этого момента OK не доходит до панели: иначе отпускание нажимает её первый пункт. */
    private var menuGuardUntil = 0L
    private lateinit var bridge: FoxBridge
    /** Код последней нажатой кнопки пульта — показывается в панели для разбора проблем с «OK». */
    private var lastKey = 0
    private lateinit var spatialNav: SpatialNav
    private lateinit var player: PlayerController

    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }
    private val store by lazy { WatchStore(this) }
    private val updater by lazy { UpdateChecker(this) }
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
        loadingOverlay = findViewById(R.id.loadingOverlay)
        // Прошлый сеанс оборвался из-за нехватки памяти — объясняем, почему видео встало
        if (prefs.getBoolean(KEY_RENDERER_GONE, false)) {
            prefs.edit().putBoolean(KEY_RENDERER_GONE, false).apply()
            toast(R.string.renderer_restarted)
        }
        // Первый запуск: показываем, какие кнопки что делают, — иначе о долгом OK не узнать
        if (!prefs.getBoolean(KEY_HINT_SHOWN, false)) showHint()
        menuPanel = MenuPanel(findViewById<ScrollView>(R.id.menuPanel), findViewById<LinearLayout>(R.id.menuItems))
        sections = MenuPanel.parse(prefs.getString(KEY_SECTIONS, null))

        // По умолчанию стрелки ходят по элементам, а курсором управляет air mouse;
        // экранный курсор стрелками — запасной режим для пультов без гироскопа
        cursorLayout.cursorEnabled = prefs.getBoolean(KEY_CURSOR, false)
        spatialNav = SpatialNav(webView, { x, y -> cursorLayout.tapAt(x, y) }) { player.play() }
        player = PlayerController(webView, cursorLayout, { x, y -> cursorLayout.tapAt(x, y) }, store) {
            // Вернулись из полноэкранного режима на страницу сериала
            webView.requestFocus()
            // Позиция просмотра изменилась: обновляем подпись «Continue» и галочки просмотренных серий
            webView.evaluateJavascript(FoxBridge.libraryJs(bridge.currentKey()), null)
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
            // Сайту незачем читать файлы устройства и content://
            allowFileAccess = false
            allowContentAccess = false
            // Убираем маркер WebView ("; wv"), чтобы сайт отдавал обычную версию
            userAgentString = userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        // Страница спрашивает у приложения, что просмотрено и отложено, и рисует это у себя.
        // Мост вызывается из потока WebView, поэтому открытие экрана переводим в UI-поток
        bridge = FoxBridge(
            store,
            onOpenLibrary = { runOnUiThread { openLibrary() } },
            onOpenMenu = { runOnUiThread { openMenu() } },
        )
        webView.addJavascriptInterface(bridge, FoxBridge.NAME)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                isBlockedScheme(request.url)

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                isBlockedScheme(Uri.parse(url)) // API 21–23

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                errorView.visibility = View.GONE
                // Ключ моста — один на документ: новая загрузка получает новый, а повторные
                // внедрения скрипта на той же странице берут его же. Иначе кнопки, созданные
                // в прошлый раз, остались бы со старым ключом в замыкании и перестали работать
                bridge.newKey()
                player.onPageStarted()
                loadGeneration++
                showLoading()
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (clearHistoryOnLoad && isHomePage(url)) {
                    clearHistoryOnLoad = false
                    view.clearHistory()
                }
                // Запоминаем только страницы самого сайта, чтобы не открывать рекламу при запуске
                awaitContentReady()
                if (isSameSite(Uri.parse(url))) {
                    prefs.edit().putString(KEY_LAST_URL, url).apply()
                    collectSections()
                    player.onPageFinished()
                    // Ключ выдан в onPageStarted и попадает только в наш скрипт
                    view.evaluateJavascript(FoxBridge.libraryJs(bridge.currentKey()), null)
                    // Скрипт плеера прячет родную панель сам; в режиме курсора возвращаем её
                    if (cursorLayout.cursorEnabled) player.setNativeControls(true)
                    // Рамка появляется сама, не дожидаясь первой стрелки: иначе страница
                    // открывается без видимого фокуса, а первая стрелка уводит его в шапку.
                    // Вторая попытка — для блоков, которые догружаются после onPageFinished
                    // (проверка «я не робот» появляется в iframe через секунду с лишним)
                    if (!cursorLayout.cursorEnabled) AUTOFOCUS_DELAYS_MS.forEach { delay ->
                        view.postDelayed({
                            if (!player.isFullscreen && !menuPanel.isOpen) spatialNav.autofocus()
                        }, delay)
                    }
                }
                CookieManager.getInstance().flush()
            }

            /**
             * Процесс отрисовки WebView система может убить сама — на приставке памяти мало,
             * особенно пока идёт видео. Если этого не перехватить, приложение умирает вместе
             * с ним, и остаётся только запускать его заново руками. Поэтому сохраняем место
             * в серии и поднимаем экран сами: страница и позиция восстановятся.
             */
            @RequiresApi(Build.VERSION_CODES.O)
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // Чтобы причина была видна и без отладчика: при следующем запуске скажем об этом
                prefs.edit().putBoolean(KEY_RENDERER_GONE, true).apply()
                try {
                    player.onBackground() // позиция известна из последнего опроса — пишем её на диск
                    player.release()
                } catch (e: Exception) {
                    // WebView уже мёртв: записать позицию не вышло, но экран всё равно поднимаем
                }
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                if (!isFinishing && !isDestroyed) recreate()
                return true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    errorView.visibility = View.VISIBLE
                    hideLoading() // иначе заставка осталась бы поверх сообщения об ошибке
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                // API 21–22: вызывается только для основного фрейма
                errorView.visibility = View.VISIBLE
                hideLoading()
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
                confirmOpen -> confirmOrNull?.close()
                libraryOpen -> { libraryOrNull?.close(); webView.requestFocus() }
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
        // Молча смотрим, не вышла ли новая сборка — не чаще раза в сутки
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_UPDATE_CHECKED, 0) > UPDATE_INTERVAL_MS) {
            prefs.edit().putLong(KEY_UPDATE_CHECKED, now).apply()
            // Не в момент загрузки сайта: пусть страница встанет первой
            webView.postDelayed({ if (!isFinishing) checkUpdates(manual = false) }, UPDATE_DELAY_MS)
        }
    }

    private fun navigateBack() {
        when {
            webView.canGoBack() -> webView.goBack()
            // Истории нет (например, приложение открылось сразу на сериале) — на главную, а не выход
            !isHomePage(webView.url) -> {
                // После загрузки главной очищаем историю, иначе «Назад» с неё вернул бы сюда же
                clearHistoryOnLoad = true
                webView.loadUrl(BuildConfig.START_URL)
            }
            // Выход — только с главной, двойным «Назад»
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
        // Панель открывают долгим OK, и фокус сразу встаёт на первый пункт: отпускание той же
        // кнопки нажимало его, панель только мелькала. Короткое время OK до панели не пускаем
        menuGuardUntil = System.currentTimeMillis() + MENU_GUARD_MS
        cursorLayout.suspended = true
        val home = Section(getString(R.string.menu_home), BuildConfig.START_URL, 0)
        val list = listOf(home) + sections.filterNot { it.url.trimEnd('/') == home.url.trimEnd('/') }
        val actions = buildList {
            player.state.let { s ->
                if (s.hasPrev) add(MenuPanel.Action(getString(R.string.menu_episode_prev)) { closeMenu(); player.episode(-1) })
                if (s.hasNext) add(MenuPanel.Action(getString(R.string.menu_episode_next)) { closeMenu(); player.episode(1) })
            }
            // Вход в библиотеку нужен всегда: глаз живёт в шапке сайта, а она есть не на каждой
            // странице — на капче и экране ошибки попасть туда было бы нечем
            add(MenuPanel.Action(getString(R.string.menu_library)) { openLibrary() })
            if (webView.canGoBack()) add(MenuPanel.Action(getString(R.string.menu_back)) { closeMenu(); webView.goBack() })
            add(MenuPanel.Action(getString(R.string.menu_reload)) { closeMenu(); webView.reload() })
            add(MenuPanel.Action(getString(R.string.menu_hint)) { closeMenu(); showHint() })
            add(MenuPanel.Action(getString(R.string.menu_update)) { closeMenu(); checkUpdates(manual = true) })
            // Копия библиотеки переживает удаление приложения: только так историю и отложенные
            // можно вернуть после переустановки
            add(MenuPanel.Action(getString(R.string.menu_export)) { closeMenu(); exportLibrary() })
            add(MenuPanel.Action(getString(R.string.menu_import)) { closeMenu(); importLibrary() })
            // Справочные строки перед настройкой режима: их не выбрать, они просто сообщают
            add(MenuPanel.Action(getString(R.string.menu_version, BuildConfig.VERSION_NAME)))
            if (lastKey != 0) {
                add(MenuPanel.Action(getString(R.string.menu_last_key, KeyEvent.keyCodeToString(lastKey), lastKey)))
            }
            val modeTitle = if (cursorLayout.cursorEnabled) R.string.menu_cursor_on else R.string.menu_cursor_off
            add(MenuPanel.Action(getString(modeTitle)) { closeMenu(); toggleCursor() })
        }
        menuPanel.open(list, { closeMenu(); webView.loadUrl(it.url) }, actions)
    }

    /**
     * Проверить, не вышла ли новая сборка. [manual] — проверку запросили из панели,
     * тогда сообщаем и когда обновления нет; фоновую проверку молчим, чтобы не мешать.
     */
    private fun checkUpdates(manual: Boolean) {
        if (manual) toast(R.string.update_checking)
        updater.check { release ->
            when {
                isFinishing || isDestroyed -> Unit
                release != null -> offerUpdate(release)
                manual -> toast(R.string.update_none)
            }
        }
    }

    private fun offerUpdate(release: UpdateChecker.Release) {
        confirm.ask(
            getString(R.string.update_title),
            getString(R.string.update_text, release.version),
            getString(R.string.update_action),
            getString(R.string.update_later),
        ) { startUpdate(release) }
    }

    /** Скачать APK и отдать системному установщику: подпись совпадает, поэтому встанет поверх. */
    private fun startUpdate(release: UpdateChecker.Release) {
        toast(R.string.update_downloading)
        updater.download(release) { file ->
            when {
                isFinishing || isDestroyed -> Unit
                file == null -> toast(R.string.update_download_failed)
                else -> when (updater.install(file)) {
                    // Подпись не совпала: система отказала бы невнятным «конфликтом пакетов»
                    UpdateChecker.InstallResult.SIGNATURE_MISMATCH -> toast(R.string.update_signature)
                    // Нужный экран настроек уже открыт, остаётся вернуться и повторить
                    UpdateChecker.InstallResult.NEED_PERMISSION -> toast(R.string.update_permission)
                    UpdateChecker.InstallResult.FAILED -> toast(R.string.update_download_failed)
                    UpdateChecker.InstallResult.STARTED -> Unit
                }
            }
        }
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    /** Выгрузить историю и отложенные в «Загрузки» — папку, которая переживёт переустановку. */
    private fun exportLibrary() {
        if (!ensureStorageAccess(REQUEST_EXPORT)) return
        store.flush()
        val path = LibraryBackup.export(this, store)
        if (path != null) toast(getString(R.string.backup_saved, path)) else toast(R.string.backup_failed)
    }

    /** Влить копию из «Загрузок». Записи сливаются по сериалу, ничего не стирается. */
    private fun importLibrary() {
        if (!ensureStorageAccess(REQUEST_IMPORT)) return
        when (val loaded = LibraryBackup.import(this, store)) {
            0 -> toast(getString(R.string.backup_none, LibraryBackup.FILE_NAME))
            -1 -> toast(R.string.backup_failed)
            else -> {
                toast(getString(R.string.backup_loaded, loaded))
                webView.evaluateJavascript(FoxBridge.libraryJs(bridge.currentKey()), null)
            }
        }
    }

    /**
     * До Android 10 запись в «Загрузки» требует разрешения; на 10 и новее доступ даёт MediaStore.
     * Возвращает false, если разрешение ещё спрашивается, — действие повторится в ответе.
     */
    private fun ensureStorageAccess(code: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return true
        requestPermissions(arrayOf(permission), code)
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            if (requestCode == REQUEST_EXPORT || requestCode == REQUEST_IMPORT) toast(R.string.backup_failed)
            return
        }
        when (requestCode) {
            REQUEST_EXPORT -> exportLibrary()
            REQUEST_IMPORT -> importLibrary()
        }
    }

    /**
     * Открыть из библиотеки. Недосмотренная серия ([play]) запускается сразу с сохранённого места,
     * отложенный сериал — просто открывает свою страницу, где выбирают сезон и серию.
     */
    private fun openFromLibrary(url: String, play: Boolean) {
        // В библиотеку могла попасть запись с чужим адресом — открываем только страницы сайта
        if (!isSameSite(Uri.parse(url))) return
        libraryOrNull?.close()
        if (play) {
            autoplayRequested = true
            player.playOnNextPage()
        }
        webView.requestFocus()
        webView.loadUrl(url)
    }

    private fun openLibrary() {
        closeMenu()
        library.open()
    }

    /** Закрыть страницу заставкой на время загрузки (в полноэкранном видео не мешаем). */
    private fun showLoading() {
        // Сбрасываем до ранних выходов: иначе флаг застрянет и следующая страница
        // будет ждать плеер вместо своей готовности
        autoplayRequested = false
        autoplayPending = false
        if (player.isFullscreen || customView != null) return
        loadingSince = System.currentTimeMillis()
        loadingOverlay.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        autoplayPending = false
        autoplayRequested = false
        loadingOverlay.visibility = View.GONE
    }

    /**
     * Снять заставку, когда сайт дорисовал содержимое: до этого момента фильтры видны пустыми,
     * без надписей. Если страница так и не «устаканилась», показываем её по таймауту.
     */
    private fun awaitContentReady() {
        if (loadingOverlay.visibility != View.VISIBLE) return
        val generation = loadGeneration
        webView.evaluateJavascript(CONTENT_READY_JS) { result ->
            if (generation != loadGeneration) return@evaluateJavascript // ответ от прежней страницы
            val state = result?.trim('"')
            // «autoplay» приходит при выборе серии на сайте; из библиотеки серию открыли мы сами
            if (state == "autoplay" || autoplayRequested) autoplayPending = true
            // При запуске серии ждём не готовности страницы, а раскрытия плеера на весь экран:
            // между «PlayerJS сообщил, что играет» и фуллскрином проходит ещё один опрос,
            // и в этот промежуток из-под заставки показывался постер
            val ready = if (autoplayPending) player.isFullscreen else state == "ready"
            val limit = if (autoplayPending) AUTOPLAY_TIMEOUT_MS else LOADING_TIMEOUT_MS
            when {
                ready && autoplayPending -> {
                    // Режим переключается раньше, чем WebView дорисует растянутый плеер,
                    // поэтому снимаем заставку на кадр позже — иначе мелькает шапка сайта
                    val since = loadingSince
                    webView.postDelayed({ if (loadingSince == since) hideLoading() }, FULLSCREEN_RENDER_MS)
                }
                ready -> hideLoading()
                System.currentTimeMillis() - loadingSince > limit -> hideLoading()
                else -> webView.postDelayed({
                    if (generation == loadGeneration) awaitContentReady()
                }, LOADING_POLL_MS)
            }
        }
    }

    /** Убрать подсказку об управлении; больше она сама не появится. */
    private fun hideHint() {
        hintView?.visibility = View.GONE
        prefs.edit().putBoolean(KEY_HINT_SHOWN, true).apply()
        webView.requestFocus()
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

    private fun isHomePage(url: String?): Boolean {
        val u = Uri.parse(url ?: return true)
        return isSameSite(u) && (u.path.isNullOrEmpty() || u.path == "/")
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
        // Кнопками панели пульты не отличаются, а вот «OK» шлют по-разному: запоминаем код,
        // чтобы его можно было увидеть в панели, когда нажатие не срабатывает
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode != KeyEvent.KEYCODE_MENU && event.keyCode != KeyEvent.KEYCODE_SETTINGS
        ) lastKey = event.keyCode
        // Подсказка об управлении гасится любой кнопкой и эту кнопку поглощает
        if (hintVisible) {
            if (event.action == KeyEvent.ACTION_UP) hideHint()
            return true
        }
        // Остаток того самого долгого нажатия, которым открыли панель, до её пунктов не доходит.
        // Флаг долгого OK сбрасываем здесь же: иначе он застрянет и съест следующее нажатие
        if (menuPanel.isOpen && isOkKey(event) && System.currentTimeMillis() < menuGuardUntil) {
            if (event.action == KeyEvent.ACTION_UP) swallowOk = false
            return true
        }
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
        // Пока открыты библиотека или вопрос, кнопками распоряжаются они сами (обычный фокус)
        if (confirmOpen || libraryOpen || menuPanel.isOpen ||
            errorView.visibility == View.VISIBLE
        ) return false
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
        if (isOkKey(event)) {
            // Долгое нажатие перехватывает handleLongOk — сюда доходит только короткое
            if (!down) spatialNav.activate()
            return true
        }
        return false
    }

    /**
     * OK с пульта. Enter с полноценной клавиатуры сюда не относится — его отдаём странице,
     * иначе ломается набор текста.
     */
    private fun isOkKey(event: KeyEvent) = event.keyCode in OK_KEYS ||
        ((event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) &&
            !bridge.editing)

    /** Долгое нажатие OK открывает панель разделов (на многих пультах нет кнопки Menu). */
    private fun handleLongOk(event: KeyEvent): Boolean {
        if (!isOkKey(event)) return false
        if (swallowOk) {
            if (event.action == KeyEvent.ACTION_UP) swallowOk = false
            return true
        }
        val longPress = event.isLongPress || event.repeatCount >= LONG_PRESS_REPEATS
        // В библиотеке долгое OK убирает карточку из списка, панель разделов туда не вмешивается
        if (libraryOpen || confirmOpen) return false
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
        player.setNativeControls(enabled) // мышью нажимают по панели самого плеера
        if (enabled) spatialNav.reset()
        Toast.makeText(
            this, if (enabled) R.string.mode_cursor else R.string.mode_dpad, Toast.LENGTH_SHORT
        ).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    /** Память кончается — первыми отдаём обложки: их недолго скачать заново. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW && postersLazy.isInitialized()) posters.trimMemory()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        webView.pauseTimers() // onPause не останавливает JS: таймеры сайта крутились бы в фоне
        player.onBackground()
        store.flush() // история и отложенные копятся в памяти — на диск их сбрасываем здесь
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
        player.onPageFinished() // возобновить опрос плеера
        hideSystemBars()
    }

    override fun onDestroy() {
        posters.shutdown()
        updater.shutdown()
        player.release() // снять отложенные задачи, пока WebView ещё прикреплён
        store.flush()
        exitFullscreen()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        /**
         * Что считается кнопкой «OK». Enter добавляется отдельно: раньше он отбрасывался,
         * если пульт объявлял себя полноценной клавиатурой, — но так объявляются и ТВ-пульты
         * с голосовым вводом, и «OK» у них переставал работать совсем.
         */
        private val OK_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_SELECT,
        )
        private const val REQUEST_EXPORT = 1
        private const val REQUEST_IMPORT = 2
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_CURSOR = "cursor_enabled"
        private const val KEY_SECTIONS = "sections"
        private const val KEY_RENDERER_GONE = "renderer_gone"
        private const val KEY_HINT_SHOWN = "hint_shown"
        private const val KEY_UPDATE_CHECKED = "update_checked"
        private const val UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L // раз в сутки
        private const val UPDATE_DELAY_MS = 20_000L // даём странице загрузиться первой
        private const val LOADING_POLL_MS = 120L
        private const val LOADING_TIMEOUT_MS = 6000L
        private const val AUTOPLAY_TIMEOUT_MS = 15_000L // столько же ждёт автозапуск в PLAYER_JS
        // Запас на отрисовку: мало дождаться смены режима — WebView ещё скрывает соседние блоки
        // страницы и рисует оверлей, и без запаса поверх плеера на кадр мелькает шапка сайта
        private const val FULLSCREEN_RENDER_MS = 700L

        /**
         * Что показывать вместо страницы: `ready` — всё готово; `autoplay` — выбрана серия,
         * ждём начала воспроизведения (иначе мелькнёт постер, и лишь потом откроется плеер);
         * `loading` — документ, шрифты или виджеты фильтров ещё не готовы.
         */
        private const val CONTENT_READY_JS = """
(function(){
  try { if (sessionStorage.getItem('__foxautoplay') === '1') return 'autoplay'; } catch (e) {}
  if (document.readyState !== 'complete') return 'loading';
  if (document.fonts && document.fonts.status !== 'loaded') return 'loading';
  var selects = document.querySelectorAll('select').length;
  if (selects > 0 && document.querySelectorAll('.ss-main').length === 0) return 'loading';
  return 'ready';
})()
"""
        private const val LONG_PRESS_REPEATS = 10
        private const val MENU_GUARD_MS = 600L // окно, в котором OK не доходит до пунктов панели
        // Даём странице дорисоваться после onPageFinished; вторая попытка — для блоков,
        // которые подгружаются позже (iframe проверки «я не робот»)
        private val AUTOFOCUS_DELAYS_MS = listOf(350L, 1800L)
        private val HANDLED_MEDIA_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
        )
    }
}

package net.hdrezka.tv

import android.view.KeyEvent
import android.view.View
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject

/**
 * Обёртка над плеером сайта (PlayerJS): своё полноэкранное воспроизведение
 * и управление с пульта поверх него.
 *
 * Состояния: [Mode.PAGE] — обычная страница; [Mode.FULLSCREEN] — видео на весь экран,
 * контролы скрыты; [Mode.CONTROLS] — контролы открыты.
 * Видео по-прежнему играет плеер сайта; команды идут через его API ([PLAYER_JS]).
 */
class PlayerController(
    private val webView: WebView,
    root: View,
    private val tap: (x: Float, y: Float) -> Unit,
    /** Вернулись на страницу — отдать фокус навигации по странице. */
    private val onExitToPage: () -> Unit,
) {
    enum class Mode { PAGE, FULLSCREEN, CONTROLS }

    data class State(
        val hasPlayer: Boolean = false,
        val playing: Boolean = false,
        val ended: Boolean = false,
        val time: Double = 0.0,
        val duration: Double = 0.0,
        val adActive: Boolean = false,
        val adLabel: String = "",
        val skipIn: Int = -1,
        val canSkip: Boolean = false,
        val quality: String = "",
        val qualities: List<String> = emptyList(),
        val title: String = "",
        val season: String = "",
        val episode: String = "",
        val count: Int = 0,
        val hasPrev: Boolean = false,
        val hasNext: Boolean = false,
    )

    var mode = Mode.PAGE
        private set
    var state = State()
        private set

    val isFullscreen get() = mode != Mode.PAGE

    private val overlay = root.findViewById<View>(R.id.playerOverlay)
    private val top = root.findViewById<View>(R.id.playerTop)
    private val title = root.findViewById<TextView>(R.id.playerTitle)
    private val episodeLabel = root.findViewById<TextView>(R.id.playerEpisode)
    private val adBadge = root.findViewById<TextView>(R.id.playerAdBadge)
    private val controls = root.findViewById<View>(R.id.playerControls)
    private val timeLabel = root.findViewById<TextView>(R.id.playerTime)
    private val durationLabel = root.findViewById<TextView>(R.id.playerDuration)
    private val progress = root.findViewById<ProgressBar>(R.id.playerProgress)
    private val btnPrev = root.findViewById<TextView>(R.id.btnPrevEpisode)
    private val btnBack = root.findViewById<TextView>(R.id.btnBack10)
    private val btnPlay = root.findViewById<TextView>(R.id.btnPlay)
    private val btnForward = root.findViewById<TextView>(R.id.btnForward10)
    private val btnNext = root.findViewById<TextView>(R.id.btnNextEpisode)
    private val btnQuality = root.findViewById<TextView>(R.id.btnQuality)
    private val btnSkip = root.findViewById<TextView>(R.id.btnSkip)
    private val qualityMenu = root.findViewById<LinearLayout>(R.id.qualityMenu)
    private val nextCard = root.findViewById<View>(R.id.nextCard)
    private val nextText = root.findViewById<TextView>(R.id.nextText)
    private val btnNextNow = root.findViewById<TextView>(R.id.btnNextNow)
    private val btnNextCancel = root.findViewById<TextView>(R.id.btnNextCancel)

    private val buttons = listOf(btnPrev, btnBack, btnPlay, btnForward, btnNext, btnQuality, btnSkip)

    private val cursor = root as? CursorLayout
    private var polling = false
    private var released = false
    /** Номер документа: ответы опроса от предыдущей страницы игнорируются. */
    private var generation = 0
    /** Смена серии из полноэкранного режима: запросили (до загрузки) → ждём готовности плеера в новом документе. */
    private var resumeRequested = false
    private var resumeUntil = 0L
    private var countdown = 0

    /** Режим перемотки по полосе: точка на полосе, влево/вправо — по минуте, удержание — непрерывно. */
    private var scrubbing = false
    private var scrubPos = 0.0
    private var scrubDirty = false
    private var lastScrubStep = 0L

    /** Открыт полноэкранный режим самого сайта (onShowCustomView) — обёртка не вмешивается. */
    var externalFullscreen = false
        set(value) {
            field = value
            if (value && mode != Mode.PAGE) setMode(Mode.PAGE, notify = false)
        }

    private val poll = object : Runnable {
        override fun run() {
            if (!polling) return
            refresh()
            webView.postDelayed(this, POLL_MS)
        }
    }
    private val autoHide = Runnable { if (!released && mode == Mode.CONTROLS && canAutoHide()) hideControls() }
    private val tick = object : Runnable {
        override fun run() {
            if (released || mode == Mode.PAGE || nextCard.visibility != View.VISIBLE) return
            countdown--
            if (countdown <= 0) {
                hideNextCard()
                episode(1)
            } else {
                nextText.text = nextText.context.getString(R.string.next_in, countdown)
                webView.postDelayed(this, 1000)
            }
        }
    }

    init {
        btnPrev.setOnClickListener { episode(-1) }
        btnNext.setOnClickListener { episode(1) }
        btnBack.setOnClickListener { js("seek(-10)") }
        btnForward.setOnClickListener { js("seek(10)") }
        btnPlay.setOnClickListener { togglePlay() }
        btnQuality.setOnClickListener { openQualityMenu() }
        btnSkip.setOnClickListener { skipAd() }
        btnNextNow.setOnClickListener { hideNextCard(); episode(1) }
        btnNextCancel.setOnClickListener { hideNextCard(); showControls() }
    }

    // --- Жизненный цикл страницы ---

    fun onPageStarted() {
        stopPolling()
        generation++
        resetTransient()
        if (mode != Mode.PAGE) setMode(Mode.PAGE, notify = false)
        state = State()
        if (resumeRequested) {
            resumeRequested = false
            resumeUntil = System.currentTimeMillis() + RESUME_TIMEOUT_MS
        }
    }

    /** Приложение ушло в фон: пауза, без автоперехода и автоскрытия. */
    fun onBackground() {
        stopPolling()
        resetTransient()
        if (mode != Mode.PAGE) js("pause()")
    }

    /** Перед уничтожением WebView: снять все отложенные задачи, пока view ещё прикреплён. */
    fun release() {
        released = true
        stopPolling()
        resetTransient()
    }

    private fun resetTransient() {
        stopScrub(commit = false)
        hideNextCard()
        closeQualityMenu()
        webView.removeCallbacks(autoHide)
    }

    fun onPageFinished() {
        if (released) return
        webView.evaluateJavascript(REORDER_JS, null)
        polling = true
        webView.removeCallbacks(poll)
        webView.post(poll)
    }

    fun stopPolling() {
        polling = false
        webView.removeCallbacks(poll)
    }

    // --- Команды ---

    /** Запустить воспроизведение (OK на плеере): дальше сработает автопереход на весь экран. */
    fun play() = js("play()")

    fun togglePlay() {
        js("toggle()")
        if (mode == Mode.CONTROLS) scheduleAutoHide()
    }

    fun seek(seconds: Int) {
        if (!state.adActive) js("seek($seconds)")
    }

    /** dir = 1 — следующая серия, -1 — предыдущая. false — переключать некуда. */
    fun episode(dir: Int): Boolean {
        val s = state
        if (s.adActive || (dir > 0 && !s.hasNext) || (dir < 0 && !s.hasPrev)) return false
        resetTransient()
        resumeRequested = isFullscreen
        js("episode($dir)")
        return true
    }

    // --- Клавиши и «Назад» ---

    /** Обработка пульта в полноэкранном режиме. true — нажатие поглощено. */
    fun handleKey(event: KeyEvent): Boolean {
        if (mode == Mode.PAGE || externalFullscreen) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (first) togglePlay(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (first) js("play()"); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (first) js("pause()"); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) seek(10); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) seek(-10); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> { if (first) episode(1); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN -> { if (first) episode(-1); return true }
        }
        val isNav = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> true
            else -> false
        }
        if (!isNav) return false

        // Карточка «следующая серия» — обычная навигация по её двум кнопкам
        if (nextCard.visibility == View.VISIBLE) {
            if (!nextCard.hasFocus()) btnNextNow.requestFocusFromTouch()
            return isVertical(event)
        }
        if (mode == Mode.FULLSCREEN) {
            // Любая стрелка или OK открывает контролы
            if (first) showControls()
            return true
        }
        // Контролы открыты
        scheduleAutoHide()
        if (qualityMenu.visibility == View.VISIBLE) {
            if (!qualityMenu.hasFocus()) focusQualityItem()
            return !isVertical(event) && !isOk(event) // влево/вправо в меню качества не нужны
        }
        if (scrubbing) return handleScrubKey(event)
        if (!controls.hasFocus()) {
            if (first) btnPlay.requestFocusFromTouch()
            return true
        }
        // Вверх — на полосу прогресса (перемотка); вниз ничего не делает, чтобы фокус не ушёл на страницу
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (first) startScrub()
            return true
        }
        return isVertical(event)
    }

    /** «Назад»: контролы → выход на страницу; видео без контролов → открыть контролы. */
    fun handleBack(): Boolean {
        when {
            mode == Mode.PAGE -> return false
            nextCard.visibility == View.VISIBLE -> { hideNextCard(); showControls() }
            qualityMenu.visibility == View.VISIBLE -> { closeQualityMenu(); btnQuality.requestFocusFromTouch() }
            scrubbing -> { stopScrub(commit = false); btnPlay.requestFocusFromTouch(); render() }
            mode == Mode.FULLSCREEN -> showControls()
            else -> exitToPage()
        }
        return true
    }

    // --- Перемотка по полосе ---

    private fun startScrub() {
        val s = state
        if (!s.hasPlayer || s.adActive || s.duration <= 0) return
        scrubbing = true
        scrubDirty = false
        scrubPos = s.time
        webView.removeCallbacks(autoHide)
        progress.requestFocusFromTouch()
        render()
    }

    private fun stopScrub(commit: Boolean) {
        if (!scrubbing) return
        if (commit && scrubDirty) js("seekTo(${scrubPos.toLong()})")
        scrubbing = false
        scrubDirty = false
    }

    private fun handleScrubKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) {
                    // Удержание: шаг не чаще раза в SCRUB_REPEAT_MS, чтобы точка шла ровно, а не улетала
                    if (event.repeatCount == 0 || event.eventTime - lastScrubStep >= SCRUB_REPEAT_MS) {
                        lastScrubStep = event.eventTime
                        val dir = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                        scrubPos = (scrubPos + dir * SCRUB_STEP_S).coerceIn(0.0, (state.duration - 1).coerceAtLeast(0.0))
                        scrubDirty = true
                        render()
                    }
                } else if (scrubDirty) {
                    // Отпустили стрелку — перематываем, точка остаётся на полосе
                    js("seekTo(${scrubPos.toLong()})")
                    scrubDirty = false
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down && event.repeatCount == 0) {
                stopScrub(commit = true)
                btnPlay.requestFocusFromTouch()
                scheduleAutoHide()
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (!down && scrubDirty) { js("seekTo(${scrubPos.toLong()})"); scrubDirty = false }
        }
        return true
    }

    private fun isVertical(e: KeyEvent) = e.keyCode == KeyEvent.KEYCODE_DPAD_UP || e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
    private fun isOk(e: KeyEvent) = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        e.keyCode == KeyEvent.KEYCODE_ENTER || e.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    // --- Переходы между режимами ---

    private fun enterFullscreen() {
        webView.evalInstalled("__foxplayer", PLAYER_JS, "window.__foxplayer.fs(true)") { result ->
            // Плеер не нашёлся (растягивать нечего) — остаёмся на странице
            if (result == "true" && mode == Mode.PAGE && !externalFullscreen) setMode(Mode.FULLSCREEN)
        }
    }

    fun exitToPage() {
        hideNextCard()
        closeQualityMenu()
        js("exitFs()")
        setMode(Mode.PAGE)
    }

    private fun showControls(focus: View = btnPlay) {
        setMode(Mode.CONTROLS)
        render()
        val target = if (focus.isFocusable && focus.visibility == View.VISIBLE) focus else btnPlay
        target.requestFocusFromTouch()
        scheduleAutoHide()
    }

    private fun hideControls() {
        closeQualityMenu()
        setMode(Mode.FULLSCREEN)
        webView.requestFocus()
    }

    private fun setMode(m: Mode, notify: Boolean = true) {
        val wasPage = mode == Mode.PAGE
        mode = m
        // В полноэкранном режиме экранный курсор не перехватывает стрелки и OK у контролов
        cursor?.playerActive = m != Mode.PAGE
        overlay.visibility = if (m == Mode.PAGE) View.GONE else View.VISIBLE
        val shown = m == Mode.CONTROLS
        if (!shown) stopScrub(commit = false)
        top.visibility = if (shown) View.VISIBLE else View.GONE
        controls.visibility = if (shown) View.VISIBLE else View.GONE
        if (!shown) webView.removeCallbacks(autoHide)
        if (m == Mode.PAGE && !wasPage && notify) onExitToPage()
    }

    private fun canAutoHide() = state.playing && !state.canSkip && !scrubbing && qualityMenu.visibility != View.VISIBLE

    private fun scheduleAutoHide() {
        webView.removeCallbacks(autoHide)
        webView.postDelayed(autoHide, AUTO_HIDE_MS)
    }

    // --- Опрос плеера ---

    private fun refresh() {
        if (released) return
        val gen = generation
        webView.evalInstalled("__foxplayer", PLAYER_JS, "window.__foxplayer.state()") { result ->
            if (released || gen != generation) return@evalInstalled // ответ от прежней страницы
            val prev = state
            val s = parse(result) ?: State()
            state = s
            onStateChanged(prev, s)
            render()
        }
    }

    private fun onStateChanged(prev: State, s: State) {
        if (!s.hasPlayer || externalFullscreen) return
        val wasActive = prev.playing || prev.adActive
        val active = s.playing || s.adActive
        // Пункт 1: запуск серии → сразу на весь экран
        if (mode == Mode.PAGE && active && !wasActive) enterFullscreen()
        // После смены серии из полноэкранного режима — запускать, пока плеер не заиграет (не дольше RESUME_TIMEOUT_MS)
        if (resumeUntil > 0) {
            when {
                active || System.currentTimeMillis() > resumeUntil -> resumeUntil = 0
                s.duration > 0 -> js("play()")
            }
        }
        // Стал доступен пропуск рекламы — показать контролы с фокусом на «Пропустить»
        if (isFullscreen && s.canSkip && !prev.canSkip) showControls(btnSkip)
        // Конец серии — отсчёт до следующей
        if (isFullscreen && s.ended && !prev.ended && s.hasNext) showNextCard()
    }

    private fun render() {
        val s = state
        val ctx = title.context
        title.text = s.title
        episodeLabel.text = if (s.count > 0) ctx.getString(R.string.episode_label, s.season, s.episode, s.count) else ""
        adBadge.visibility = if (isFullscreen && s.adActive) View.VISIBLE else View.GONE
        // Метку рекламы берём у плеера («Реклама 1/2») и переводим
        adBadge.text = s.adLabel.replace("Реклама", ctx.getString(R.string.player_ad)).ifBlank { ctx.getString(R.string.player_ad) }

        if (scrubbing && (s.adActive || s.duration <= 0)) stopScrub(commit = false)
        val shownTime = if (scrubbing) scrubPos else s.time
        timeLabel.text = fmt(shownTime)
        durationLabel.text = fmt(s.duration)
        progress.progress = if (s.duration > 0) (shownTime / s.duration * 1000).toInt() else 0

        btnPlay.setText(if (s.playing) R.string.player_pause else R.string.player_play)
        btnQuality.text = if (s.quality.isNotBlank()) ctx.getString(R.string.player_quality_value, s.quality) else ctx.getString(R.string.player_quality)

        val noAd = !s.adActive
        setEnabled(btnPrev, noAd && s.hasPrev)
        setEnabled(btnNext, noAd && s.hasNext)
        setEnabled(btnBack, noAd)
        setEnabled(btnForward, noAd)
        setEnabled(btnQuality, noAd && s.qualities.size > 1)
        btnPrev.visibility = if (s.count > 0) View.VISIBLE else View.GONE
        btnNext.visibility = if (s.count > 0) View.VISIBLE else View.GONE

        btnSkip.visibility = if (s.adActive && (s.canSkip || s.skipIn >= 0)) View.VISIBLE else View.GONE
        setEnabled(btnSkip, s.canSkip)
        btnSkip.text = if (s.canSkip) ctx.getString(R.string.player_skip) else ctx.getString(R.string.player_skip_in, s.skipIn.coerceAtLeast(0))
    }

    /** Неактивная кнопка видна, но затемнена и пропускается при переходе стрелками. */
    private fun setEnabled(v: View, enabled: Boolean) {
        if (!enabled && v.hasFocus()) btnPlay.requestFocusFromTouch()
        v.isEnabled = enabled
        v.isFocusable = enabled
        v.alpha = if (enabled) 1f else 0.35f
    }

    // --- Качество ---

    private fun openQualityMenu() {
        val list = state.qualities
        if (list.size < 2) return
        qualityMenu.removeAllViews()
        list.forEachIndexed { i, q ->
            val item = TextView(qualityMenu.context, null, 0, R.style.EpisodeButton).apply {
                text = if (q == state.quality) "✓ $q" else q
                tag = q
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                setBackgroundResource(R.drawable.menu_item_bg)
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    closeQualityMenu()
                    js("setQuality($i)")
                    btnQuality.requestFocusFromTouch()
                }
            }
            qualityMenu.addView(item)
        }
        qualityMenu.visibility = View.VISIBLE
        webView.removeCallbacks(autoHide)
        focusQualityItem()
    }

    private fun focusQualityItem() {
        val items = (0 until qualityMenu.childCount).map(qualityMenu::getChildAt)
        (items.firstOrNull { it.tag == state.quality } ?: items.firstOrNull())?.requestFocusFromTouch()
    }

    private fun closeQualityMenu() {
        qualityMenu.visibility = View.GONE
        qualityMenu.removeAllViews()
    }

    // --- Реклама и следующая серия ---

    private fun skipAd() {
        webView.evalInstalled("__foxplayer", PLAYER_JS, "window.__foxplayer.skipTarget()") { result ->
            try {
                val o = JSONObject(result)
                tap(o.getDouble("x").toFloat(), o.getDouble("y").toFloat())
            } catch (_: Exception) {
            }
        }
        btnPlay.requestFocusFromTouch()
    }

    private fun showNextCard() {
        closeQualityMenu()
        setMode(Mode.FULLSCREEN)
        countdown = NEXT_COUNTDOWN
        nextText.text = nextText.context.getString(R.string.next_in, countdown)
        nextCard.visibility = View.VISIBLE
        btnNextNow.requestFocusFromTouch()
        webView.removeCallbacks(tick)
        webView.postDelayed(tick, 1000)
    }

    private fun hideNextCard() {
        nextCard.visibility = View.GONE
        webView.removeCallbacks(tick)
    }

    // --- Вспомогательное ---

    private fun js(call: String) {
        if (released) return
        webView.evalInstalled("__foxplayer", PLAYER_JS, "window.__foxplayer.$call") { refresh() }
    }

    private fun fmt(sec: Double): String {
        val t = sec.toLong().coerceAtLeast(0)
        val h = t / 3600
        val m = t % 3600 / 60
        val s = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private fun parse(json: String?): State? {
        if (json.isNullOrBlank() || json == "null") return null
        return try {
            val o = JSONObject(json)
            val q = o.optJSONArray("qualities")
            State(
                hasPlayer = o.optBoolean("player"),
                playing = o.optBoolean("playing"),
                ended = o.optBoolean("ended"),
                time = o.optDouble("time", 0.0),
                duration = o.optDouble("duration", 0.0),
                adActive = o.optBoolean("ad"),
                adLabel = o.optString("adLabel"),
                skipIn = o.optInt("skipIn", -1),
                canSkip = o.optBoolean("canSkip"),
                quality = o.optString("quality"),
                qualities = if (q == null) emptyList() else (0 until q.length()).map { q.getString(it) },
                title = o.optString("title"),
                season = o.optString("season"),
                episode = o.optString("episode"),
                count = o.optInt("count"),
                hasPrev = o.optBoolean("prev"),
                hasNext = o.optBoolean("next"),
            )
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val POLL_MS = 500L
        private const val AUTO_HIDE_MS = 5000L
        private const val NEXT_COUNTDOWN = 10
        private const val RESUME_TIMEOUT_MS = 15_000L
        private const val SCRUB_STEP_S = 60.0
        private const val SCRUB_REPEAT_MS = 150L

        /**
         * Страница сериала: плеер с сериями — сразу под постером и заголовком,
         * а информация, актёры, рейтинги и описание — ниже плеера.
         * Блоки из CSS-сетки .full_grid переносятся в отдельный контейнер после плеера.
         */
        private const val REORDER_JS = """
(function(){
  var grid = document.querySelector('.full_grid'), player = document.querySelector('.full_player');
  if (!grid || !player || grid.getAttribute('data-fox-reordered')) return;
  var moved = ['.full_content-info', '.full_content-actions', '.full_story']
    .map(function(s) { return grid.querySelector(s); })
    .filter(function(e) { return e && e.parentElement === grid; });
  if (!moved.length) return;
  grid.setAttribute('data-fox-reordered', '1');
  var style = document.createElement('style');
  style.textContent =
    '.full_grid[data-fox-reordered]{min-height:0 !important;height:auto !important;grid-template-rows:auto !important;}' +
    '.__foxinfo{display:flex;flex-wrap:wrap;gap:30px;margin-top:30px;}' +
    '.__foxinfo>.full_content-info{flex:1 1 500px;}' +
    '.__foxinfo>.full_content-actions{flex:0 0 260px;}' +
    '.__foxinfo>.full_story{flex:1 1 100%;}';
  (document.head || document.documentElement).appendChild(style);
  var box = document.createElement('div');
  box.className = '__foxinfo';
  moved.forEach(function(e) { box.appendChild(e); });
  player.parentElement.insertBefore(box, player.nextSibling);
})()
"""

        /**
         * window.__foxplayer — состояние и команды плеера: PlayerJS API (pljssglobal[0].api),
         * запасной вариант — самый большой <video>. «Весь экран» — растягивание плеера CSS-классом.
         */
        const val PLAYER_JS = """
if (!window.__foxplayer) (function(){
  var style = document.createElement('style');
  style.textContent =
    '.__foxfs{position:fixed !important;left:0 !important;top:0 !important;right:0 !important;bottom:0 !important;' +
    'width:100vw !important;height:100vh !important;max-width:none !important;max-height:none !important;' +
    'margin:0 !important;padding:0 !important;z-index:2147483646 !important;background:#000 !important;transform:none !important;}' +
    '.__foxfs video{width:100% !important;height:100% !important;object-fit:contain !important;}' +
    'html.__foxfs_on,html.__foxfs_on body{overflow:hidden !important;}' +
    '.__foxfs_hide{visibility:hidden !important;}';
  (document.head || document.documentElement).appendChild(style);

  var ended = false;
  // Перехватываем события PlayerJS: конец серии
  var orig = window.PlayerjsEvents;
  window.PlayerjsEvents = function(ev) {
    if (ev === 'end' || ev === 'finish' || ev === 'fileend') ended = true;
    else if (ev === 'play' || ev === 'start' || ev === 'seek') ended = false;
    if (orig) return orig.apply(this, arguments);
  };

  function pj() { try { return window.pljssglobal && window.pljssglobal[0]; } catch (e) { return null; } }
  function api(cmd, arg) { var p = pj(); if (!p) return undefined; try { return arg === undefined ? p.api(cmd) : p.api(cmd, arg); } catch (e) { return undefined; } }
  function video() {
    var vs = [].slice.call(document.querySelectorAll('video'));
    vs.sort(function(a, b) { return b.clientWidth * b.clientHeight - a.clientWidth * a.clientHeight; });
    return vs[0] || null;
  }
  function root() {
    var r = document.getElementById('player-container');
    if (r) return r;
    var v = video();
    if (!v || v.clientWidth < 200) return null;
    var e = v;
    while (e.parentElement && e.parentElement !== document.body &&
           e.parentElement.clientHeight <= v.clientHeight * 1.3) e = e.parentElement;
    return e;
  }
  function anyPlaying() {
    return [].some.call(document.querySelectorAll('video'), function(v) { return !v.paused && !v.ended && v.readyState > 2; });
  }
  // «Смотреть онлайн» на странице сериала — сразу запуск (обёртка уйдёт на весь экран), без прокрутки к плееру
  document.addEventListener('click', function(ev) {
    var b = ev.target && ev.target.closest ? ev.target.closest('.to_view') : null;
    if (!b || !root()) return;
    ev.preventDefault();
    ev.stopPropagation();
    window.__foxplayer.play();
  }, true);
  function eps() { return [].slice.call(document.querySelectorAll('.episodes_list a[data-episode]')); }
  function seasons() { return [].slice.call(document.querySelectorAll('.seasons_list a[data-season]')); }
  function activeIndex(list) { for (var i = 0; i < list.length; i++) if (list[i].classList.contains('active')) return i; return -1; }
  function shown(e) { var r = e.getBoundingClientRect(); return r.width > 2 && r.height > 2 && getComputedStyle(e).visibility !== 'hidden'; }
  // Надписи рекламы в плеере: «Реклама 1/2», «Пропустить через 15», «Пропустить»
  function adInfo() {
    var r = root(), info = { ad: false, label: '', skipIn: -1, skipEl: null };
    if (!r) return info;
    var all = r.querySelectorAll('*');
    for (var i = 0; i < all.length; i++) {
      var e = all[i];
      if (e.children.length > 0 || !shown(e)) continue;
      var t = (e.textContent || '').replace(/\s+/g, ' ').trim();
      var m;
      if (/^Реклама(\s|$)/i.test(t)) /* \b не работает с кириллицей */ { info.ad = true; info.label = t; }
      else if ((m = /^Пропустить через (\d+)/i.exec(t))) { info.ad = true; info.skipIn = +m[1]; }
      else if (/^(Пропустить( рекламу)?|Skip( ad)?)$/i.test(t)) { info.ad = true; info.skipEl = e; }
    }
    return info;
  }

  window.__foxplayer = {
    root: root,
    state: function() {
      var r = root(), v = video(), e = eps(), s = seasons(), i = activeIndex(e), j = activeIndex(s);
      if (!r && !e.length) return null;
      var playing = api('playing'), time = api('time'), dur = api('duration');
      if (playing === undefined && v) { playing = !v.paused; time = v.currentTime; dur = v.duration; }
      var ad = adInfo(), q = api('qualities'), cur = e[i] || e[0], h = document.querySelector('h1');
      // Во время рекламы PlayerJS сообщает состояние серии (на паузе), а играет рекламное видео
      if (anyPlaying()) playing = true;
      return {
        // Конец серии: по событию PlayerJS или (если хук не сработал) по времени
        player: !!r, playing: !!playing,
        ended: ended || (!playing && +dur > 0 && +time >= +dur - 1) || !!(v && v.ended && !ad.ad),
        time: +time || 0, duration: isFinite(dur) ? +dur || 0 : 0,
        ad: ad.ad, adLabel: ad.label, skipIn: ad.skipIn, canSkip: !!ad.skipEl,
        quality: api('quality') || '', qualities: Array.isArray(q) ? q : [],
        title: h ? h.textContent.replace(/\s+/g, ' ').trim().slice(0, 120) : document.title,
        count: e.length,
        season: cur ? (cur.getAttribute('data-season') || '1') : '',
        episode: i >= 0 ? cur.getAttribute('data-episode') : '—',
        prev: i > 0 || j > 0,
        next: (i >= 0 && i < e.length - 1) || (j >= 0 && j < s.length - 1)
      };
    },
    play: function() { if (pj()) api('play'); else { var v = video(); if (v) v.play(); } },
    // Пауза и для рекламного <video>, который PlayerJS api('pause') не останавливает
    pause: function() {
      if (pj()) api('pause');
      [].forEach.call(document.querySelectorAll('video'), function(v) { try { v.pause(); } catch (e) {} });
      return true;
    },
    exitFs: function() { this.pause(); return this.fs(false); },
    toggle: function() {
      if (anyPlaying()) { this.pause(); return; }
      if (pj()) api('play');
      var v = video(); if (v && v.paused) { try { v.play(); } catch (e) {} } // рекламное видео
    },
    seekTo: function(t) {
      if (pj()) { api('seek', Math.max(0, t)); return; }
      var v = video(); if (v) v.currentTime = Math.max(0, t);
    },
    seek: function(d) {
      if (pj()) { api('seek', Math.max(0, (+api('time') || 0) + d)); return; }
      var v = video(); if (v) v.currentTime = Math.max(0, v.currentTime + d);
    },
    // Смена качества сбрасывает позицию на 0 — возвращаем её, когда новый поток загрузится
    setQuality: function(idx) {
      var t = +api('time') || 0, tries = 0;
      api('quality', idx);
      var iv = setInterval(function() {
        tries++;
        if ((+api('duration') || 0) > 0 && (+api('time') || 0) < t - 5) { api('seek', t); api('play'); clearInterval(iv); }
        else if (tries > 60) clearInterval(iv);
      }, 250);
    },
    fs: function(on) {
      var r = root(); if (!r) return false;
      r.classList.toggle('__foxfs', on);
      document.documentElement.classList.toggle('__foxfs_on', on);
      // Прячем всё остальное на странице (шапка сайта может быть выше плеера по z-index)
      [].forEach.call(document.querySelectorAll('.__foxfs_hide'), function(e) { e.classList.remove('__foxfs_hide'); });
      if (on) for (var e = r; e && e !== document.body; e = e.parentElement) {
        var p = e.parentElement; if (!p) break;
        [].forEach.call(p.children, function(c) { if (c !== e && c.tagName !== 'SCRIPT' && c.tagName !== 'STYLE') c.classList.add('__foxfs_hide'); });
      }
      if (!on) r.scrollIntoView({ block: 'center' });
      return on;
    },
    episode: function(dir) {
      var e = eps(), i = activeIndex(e);
      if (i >= 0 && e[i + dir]) { e[i + dir].click(); return; }
      var s = seasons(), j = activeIndex(s);
      if (j >= 0 && s[j + dir]) s[j + dir].click();
    },
    skipTarget: function() {
      var e = adInfo().skipEl; if (!e) return null;
      var r = e.getBoundingClientRect(), k = devicePixelRatio * (visualViewport ? visualViewport.scale : 1);
      var ox = visualViewport ? visualViewport.offsetLeft : 0, oy = visualViewport ? visualViewport.offsetTop : 0;
      return { x: ((r.left + r.right) / 2 - ox) * k, y: ((r.top + r.bottom) / 2 - oy) * k };
    }
  };
})()
"""
    }
}

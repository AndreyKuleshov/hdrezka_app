package net.hdrezka.tv

import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

/**
 * Мост между страницей сайта и библиотекой приложения: страница спрашивает, что уже просмотрено
 * и отложено, и сама рисует это поверх своей вёрстки ([LIBRARY_JS]).
 * Методы вызываются из потока WebView, поэтому только читают и пишут [WatchStore].
 */
class FoxBridge(
    private val store: WatchStore,
    /** Открыть библиотеку: вызывается из потока WebView, поэтому переводится в UI-поток. */
    private val onOpenLibrary: () -> Unit,
) {

    @JavascriptInterface
    fun openLibrary() = onOpenLibrary()

    @JavascriptInterface
    fun isFavorite(url: String): Boolean = store.isFavorite(url)

    /** Отложить или убрать из отложенных; возвращает новое состояние для перерисовки кнопки. */
    @JavascriptInterface
    fun toggleFavorite(url: String, title: String, poster: String): Boolean =
        store.toggleFavorite(url, title, poster)

    /** Просмотренные серии текущего сериала: массив «сезон:серия». */
    @JavascriptInterface
    fun seenEpisodes(url: String): String = JSONArray(store.seenEpisodes(url).toList()).toString()

    /** Прогресс по сериалам: адрес → доля просмотра, для полосок на карточках каталога. */
    @JavascriptInterface
    fun progressMap(): String {
        val o = JSONObject()
        store.progressByShow().forEach { (url, fraction) -> o.put(url, fraction.toDouble()) }
        return o.toString()
    }

    companion object {
        const val NAME = "FoxApp"

        /**
         * Рисует на страницах сайта то, что знает приложение: кнопку «отложить» на странице
         * сериала, полоску прогресса на карточках каталога и галочки у просмотренных серий.
         * Если вёрстка сайта изменится, скрипт просто ничего не найдёт — остальное продолжит работать.
         */
        const val LIBRARY_JS = """
(function(){
  if (!window.FoxApp) return;
  var FAV = '__foxfav', MARK = '__foxseen', BAR = '__foxbar', EYE = '__foxeye';
  if (!document.getElementById('__foxlibstyle')) {
    var st = document.createElement('style');
    st.id = '__foxlibstyle';
    st.textContent =
      '.' + FAV + '{display:inline-flex;align-items:center;justify-content:center;gap:8px;' +
      'padding:12px 20px;margin-left:12px;border:0;border-radius:8px;cursor:pointer;' +
      'font:inherit;font-size:15px;color:#fff;background:#2b2b31;}' +
      '.' + FAV + '[data-on="1"]{background:#FF6A00;color:#141418;}' +
      '.' + MARK + '{position:relative;}' +
      '.' + MARK + ':after{content:"";position:absolute;right:6px;top:6px;width:10px;height:10px;' +
      'border-radius:50%;background:#FF6A00;}' +
      '.' + BAR + '{position:absolute;left:0;right:0;bottom:0;height:6px;background:rgba(0,0,0,.55);z-index:5;}' +
      '.' + BAR + ' i{display:block;height:100%;background:#FF6A00;}' +
      '.' + EYE + '{display:inline-flex;align-items:center;justify-content:center;width:60px;height:60px;' +
      'padding:0;border:0;background:transparent;color:#fff;cursor:pointer;}' +
      '.' + EYE + '[data-has="1"]{color:#FF6A00;}';
    (document.head || document.documentElement).appendChild(st);
  }

  // Кнопка библиотеки в шапке: между поиском и меню — поиск остаётся первым по важности.
  // Иконка-глаз рисуется прямо здесь, чтобы не тащить картинку в ресурсы.
  var head = document.querySelector('.header_in');
  if (head && !head.querySelector('.' + EYE)) {
    var eye = document.createElement('button');
    eye.type = 'button';
    eye.className = EYE;
    eye.setAttribute('aria-label', 'Library');
    eye.innerHTML = '<svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" ' +
      'stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
      '<path d="M2 12c2.7-4.2 6-6.3 10-6.3s7.3 2.1 10 6.3c-2.7 4.2-6 6.3-10 6.3S4.7 16.2 2 12z"/>' +
      '<circle cx="12" cy="12" r="3.2"/></svg>';
    eye.addEventListener('click', function(ev) {
      ev.preventDefault();
      ev.stopPropagation();
      try { FoxApp.openLibrary(); } catch (e) {}
    }, true);
    // Прямые кнопки шапки: первая — поиск, последняя — меню; встаём между ними
    var own = [].filter.call(head.children, function(c) { return c.tagName === 'BUTTON'; });
    head.insertBefore(eye, own.length > 1 ? own[own.length - 1] : null);
    // Подсвечиваем оранжевым, когда есть что продолжить или отложено
    try {
      var any = Object.keys(JSON.parse(FoxApp.progressMap() || '{}')).length > 0;
      eye.setAttribute('data-has', any ? '1' : '0');
    } catch (e) {}
  }

  function poster() {
    var im = document.querySelector('.full_content-poster img');
    return im ? (im.currentSrc || im.src || '') : '';
  }
  function title() {
    var h = document.querySelector('h1');
    return h ? h.textContent.replace(/\s+/g, ' ').trim() : document.title;
  }

  // Кнопка «отложить» рядом со «Смотреть онлайн» — только на странице сериала
  var view = document.querySelector('button.to_view, .to_view');
  if (view && !document.querySelector('.' + FAV)) {
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = FAV;
    function paint(on) {
      btn.setAttribute('data-on', on ? '1' : '0');
      btn.textContent = on ? '★ Saved' : '★ Save for later';
    }
    btn.addEventListener('click', function(ev) {
      ev.preventDefault();
      ev.stopPropagation();
      try { paint(FoxApp.toggleFavorite(location.href, title(), poster())); } catch (e) {}
    }, true);
    try { paint(FoxApp.isFavorite(location.href)); } catch (e) { paint(false); }
    (view.parentElement || document.body).insertBefore(btn, view.nextSibling);
  }

  // Галочки у просмотренных серий
  try {
    var seen = JSON.parse(FoxApp.seenEpisodes(location.href) || '[]');
    if (seen.length) {
      [].forEach.call(document.querySelectorAll('.episodes_list a[data-episode]'), function(a) {
        var key = (a.getAttribute('data-season') || '1') + ':' + a.getAttribute('data-episode');
        if (seen.indexOf(key) >= 0) a.classList.add(MARK);
      });
    }
  } catch (e) {}

  // Полоска прогресса на карточках каталога: ключ — адрес сериала, как его хранит приложение
  try {
    var prog = JSON.parse(FoxApp.progressMap() || '{}');
    var keys = Object.keys(prog);
    if (keys.length) {
      [].forEach.call(document.querySelectorAll('a[href]'), function(a) {
        var href = a.href.split('?')[0].replace(/\/+$/, '');
        if (!(href in prog)) return;
        var box = a.querySelector('img') ? a : a.parentElement;
        if (!box || box.querySelector('.' + BAR)) return;
        if (getComputedStyle(box).position === 'static') box.style.position = 'relative';
        var bar = document.createElement('div');
        bar.className = BAR;
        bar.innerHTML = '<i style="width:' + Math.round(prog[href] * 100) + '%"></i>';
        box.appendChild(bar);
      });
    }
  } catch (e) {}
})()
"""
    }
}

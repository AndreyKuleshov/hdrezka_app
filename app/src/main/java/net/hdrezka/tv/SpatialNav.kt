package net.hdrezka.tv

import android.webkit.WebView
import org.json.JSONObject

/**
 * Навигация стрелками по элементам страницы: ссылки, кнопки, поля, фильтры,
 * карточки — всё, что кликабельно. Выбранный элемент подсвечивается,
 * OK — настоящее касание в его центр (открывает выпадающие списки, клавиатуру и т.п.).
 */
class SpatialNav(
    private val webView: WebView,
    private val tap: (x: Float, y: Float) -> Unit,
    /** OK на плеере: запустить воспроизведение через API, а не касанием (оно может попасть в рекламу). */
    private val onPlayer: () -> Unit,
) {
    /**
     * Сдвинуть выделение. [onResult]: "player" — выделение на плеере,
     * "ok" — сдвинулось, "none" — некуда.
     */
    fun move(direction: String, onResult: (String) -> Unit = {}) {
        // Пока предыдущий шаг не выполнился, повторы (удержание стрелки) отбрасываем —
        // иначе на медленной приставке очередь шагов продолжает «ехать» после отпускания
        if (moving) return
        moving = true
        webView.evalInstalled("__foxnav", INSTALL_JS, "window.__foxnav.move('$direction')") {
            moving = false
            onResult(it?.trim('"') ?: "none")
        }
    }

    private var moving = false


    /** Нажать выбранный элемент. */
    fun activate() {
        webView.evalInstalled("__foxnav", INSTALL_JS, "window.__foxnav.target()") { result ->
            if (result.isNullOrBlank() || result == "null") return@evalInstalled
            try {
                val o = JSONObject(result)
                if (o.optBoolean("player")) {
                    onPlayer()
                    return@evalInstalled
                }
                // Координаты из JS уже в пикселях view
                tap(o.getDouble("x").toFloat(), o.getDouble("y").toFloat())
            } catch (_: Exception) {
            }
        }
    }

    /** Закрыть открытый выпадающий список/окно; [onResult] получает true, если было что закрывать. */
    fun escape(onResult: (Boolean) -> Unit) {
        webView.evaluateJavascript("window.__foxnav ? window.__foxnav.escape() : false") { onResult(it == "true") }
    }

    /** Убрать подсветку (например, при переключении в режим курсора). */
    fun reset() {
        webView.evaluateJavascript("window.__foxnav && window.__foxnav.reset()", null)
    }

    companion object {
        private val INSTALL_JS get() = "${PlayerController.PLAYER_JS};$NAV_JS"

        private const val NAV_JS = """
if (!window.__foxnav) (function(){
  var CLS = '__foxfocus';
  var style = document.createElement('style');
  style.textContent = '.' + CLS + '{outline:4px solid #FF6A00 !important;outline-offset:2px !important;' +
    'box-shadow:0 0 0 8px rgba(255,106,0,.35) !important;border-radius:6px;}';
  (document.head || document.documentElement).appendChild(style);

  var SEL = 'a[href],button,input:not([type=hidden]),select,textarea,summary,[role=button],[role=link],' +
    '[role=option],[role=tab],[role=menuitem],[onclick],[tabindex]:not([tabindex="-1"])';
  // Элементы, которые стрелками не выделяются (как будто их нет): логотипы, обложка на странице
  // сериала, служебные ссылки в подвале. Мышью/air mouse они по-прежнему нажимаются.
  var SKIP = 'a.logotype, .footer-menu a, a.menu-item'; // a.menu-item — заголовки разделов в меню сайта
  var SKIP_AREAS = '.full_content-poster';
  function skipped(e) { return e.matches(SKIP) || !!e.closest(SKIP_AREAS); }
  var cur = null, owner = null; // owner — элемент, открывший выпадающий список/окно
  var opened = null;             // список/окно, появившееся после нашего нажатия OK
  function closeButton(popup) {
    var pr = popup.getBoundingClientRect(), best = null;
    [].forEach.call(popup.querySelectorAll('button,[class*=close],[aria-label*=close i],[aria-label*=закры i]'), function(b) {
      var r = b.getBoundingClientRect();
      if (r.width < 8 || r.width > 100 || r.height > 100) return;
      var named = /close|закры/i.test(String(b.className) + ' ' + (b.getAttribute('aria-label') || ''));
      var corner = r.top < pr.top + Math.max(100, pr.height * 0.2) && r.right > pr.right - Math.max(100, pr.width * 0.2);
      if (named || corner) best = best || b;
    });
    return best;
  }
    function visiblePopups() { return [].filter.call(document.querySelectorAll(POPUP), function(p) { return visible(p); }); }
  // Открытые выпадающие списки и окна: стрелки не выходят за их пределы
  var POPUP = '.ss-content,#main_menu,[role=listbox],[role=dialog],[role=menu],[aria-modal=true],.modal,.dropdown-menu,.popup'; // #main_menu — меню сайта (☰)

  function popupOf(e) {
    var p = e && e.closest ? e.closest(POPUP) : null;
    return p && visible(p) ? p : null;
  }

  // Элемент не перекрыт другим (например, открытым списком поверх карточек)
  function onTop(e) {
    var r = e.getBoundingClientRect();
    var x = Math.min(Math.max((r.left + r.right) / 2, 0), innerWidth - 1);
    var y = Math.min(Math.max((r.top + r.bottom) / 2, 0), innerHeight - 1);
    if ((r.top + r.bottom) / 2 < 0 || (r.top + r.bottom) / 2 > innerHeight) return true; // вне экрана не проверить
    var hit = document.elementFromPoint(x, y);
    return !hit || hit === e || e.contains(hit) || hit.contains(e);
  }

  function visible(e) {
    var r = e.getBoundingClientRect();
    if (r.width < 4 || r.height < 4) return false;
    var cs = getComputedStyle(e);
    return cs.visibility !== 'hidden' && cs.opacity !== '0' && !e.disabled;
  }
  function pointer(e) { return getComputedStyle(e).cursor === 'pointer'; }

  // Кликабельные элементы: по селектору или с курсором-«рукой» (самодельные виджеты).
  // Из вложенных оставляем внешний, если он не огромный контейнер.
  function candidates(scope) {
    var root = scope || document.body;
    var all = root ? root.getElementsByTagName('*') : [];
    var vpArea = innerWidth * innerHeight, set = new Set(), out = [];
    for (var i = 0; i < all.length; i++) {
      var e = all[i];
      if (skipped(e)) continue;
      if (e.matches(SEL) || (pointer(e) && !(e.parentElement && pointer(e.parentElement)))) set.add(e);
    }
    // Плеер — одна точка навигации; его внутренние элементы не обходим (ими управляет панель плеера)
    var player = window.__foxplayer && window.__foxplayer.root();
    if (player && visible(player)) set.add(player);
    set.forEach(function(e) {
      if (player && e !== player && player.contains(e)) return;
      // Внутри открытого списка пункты могут быть скрыты прокруткой — их не проверяем на перекрытие
      if (!visible(e) || (!scope && !onTop(e))) return;
      for (var p = e.parentElement; p && p !== root; p = p.parentElement) {
        if (set.has(p)) {
          var r = p.getBoundingClientRect();
          if (r.width * r.height < vpArea * 0.5) return;
        }
      }
      out.push(e);
    });
    return mergeCards(out, vpArea);
  }

  // Карточка: несколько ссылок на одну и ту же страницу (обложка и название) — одна точка обхода.
  // Рамка — на всю карточку (общий предок ссылок), OK — касание первой ссылки (обложки).
  function mergeCards(list, vpArea) {
    var groups = {};
    list.forEach(function(e) {
      if (e.tagName !== 'A' || !e.href) return;
      (groups[e.href] = groups[e.href] || []).push(e);
    });
    var replace = new Map();
    Object.keys(groups).forEach(function(href) {
      var g = groups[href];
      if (g.length < 2) return;
      var box = g[0].parentElement;
      while (box && !g.every(function(e) { return box.contains(e); })) box = box.parentElement;
      if (!box || box === document.body) return;
      var r = box.getBoundingClientRect();
      if (r.width * r.height > vpArea * 0.25) return;
      // В карточке не должно быть других кликабельных элементов, иначе они стали бы недоступны
      var others = list.some(function(e) { return g.indexOf(e) < 0 && box.contains(e); });
      if (others) return;
      box.__foxTarget = g[0];
      g.forEach(function(e, i) { replace.set(e, i === 0 ? box : null); });
    });
    if (!replace.size) return list;
    var out = [];
    list.forEach(function(e) {
      if (!replace.has(e)) out.push(e);
      else if (replace.get(e)) out.push(replace.get(e));
    });
    return out;
  }

  function isPlayer(e) { return !!(window.__foxplayer && e === window.__foxplayer.root()); }

  function rect(e) { var r = e.getBoundingClientRect(); return { l: r.left, t: r.top, r: r.right, b: r.bottom }; }

  var ROW_TOL = 24; // CSS px: элементы, чей верх в пределах этого от ближайшего, считаются одним рядом
  function pick(dir, list) {
    var c = rect(cur), best = null, bestScore = Infinity, scored = [];
    var cx = (c.l + c.r) / 2, cy = (c.t + c.b) / 2;
    list.forEach(function(e) {
      if (e === cur || cur.contains(e) || e.contains(cur)) return;
      var r = rect(e), main, gap;
      // Кандидат должен лежать именно в направлении движения: его ближний край — за дальним краем
      // текущего (допускаем перекрытие до четверти размера). Иначе соседний ряд, сдвинутый на пару
      // пикселей, считался бы «справа» — и вправо шло бы лесенкой вниз.
      var cw = c.r - c.l, ch = c.b - c.t, rw = r.r - r.l, rh = r.b - r.t;
      var tolX = Math.min(cw, rw) * 0.25, tolY = Math.min(ch, rh) * 0.25;
      var ex = (r.l + r.r) / 2, ey = (r.t + r.b) / 2, cross;
      // Со широкого элемента (например, плеера) вниз/вверх — к началу ряда, а не к его середине
      var crossV = cw > rw * 2 ? Math.abs(r.l - c.l) : Math.abs(ex - cx);
      var crossH = ch > rh * 2 ? Math.abs(r.t - c.t) : Math.abs(ey - cy);
      if (dir === 'down') { main = r.t - c.b; if (main < -tolY) return; gap = Math.max(0, r.l - c.r, c.l - r.r); cross = crossV; }
      if (dir === 'up') { main = c.t - r.b; if (main < -tolY) return; gap = Math.max(0, r.l - c.r, c.l - r.r); cross = crossV; }
      if (dir === 'right') { main = r.l - c.r; if (main < -tolX) return; gap = Math.max(0, r.t - c.b, c.t - r.b); cross = crossH; }
      if (dir === 'left') { main = c.l - r.r; if (main < -tolX) return; gap = Math.max(0, r.t - c.b, c.t - r.b); cross = crossH; }
      // Влево/вправо — только в пределах ряда (элементы пересекаются по высоте). Переход на соседний
      // ряд делает wrap() внутри того же списка; иначе с конца ряда рамка прыгала бы по диагонали
      // в соседний ряд или на далёкие кнопки ниже.
      if ((dir === 'left' || dir === 'right') && gap > 0) return;
      // Ближе по направлению, без зазора поперёк, при равенстве — ровнее по центру
      var score = Math.max(main, 0) + gap * 3 + cross * 0.1;
      scored.push({ e: e, main: Math.max(main, 0), score: score });
    });
    // Вверх/вниз: сначала ближайший ряд, затем лучший элемент в нём. Иначе широкий элемент прямо
    // под текущим (плеер) обгонял бы ближний ряд, сдвинутый вбок (озвучки под «Смотреть трейлер»).
    if ((dir === 'up' || dir === 'down') && scored.length) {
      var nearest = Math.min.apply(null, scored.map(function(x) { return x.main; }));
      scored = scored.filter(function(x) { return x.main <= nearest + ROW_TOL; });
    }
    scored.forEach(function(x) { if (x.score < bestScore) { bestScore = x.score; best = x.e; } });
    return best;
  }

  // Перенос по рядам внутри одного списка (например, серии): вправо с конца ряда — на начало
  // следующего, влево с начала ряда — на конец предыдущего. Список — ближайший предок текущего
  // элемента, в котором есть другие кандидаты.
  function wrap(dir, list) {
    var group = null;
    for (var p = cur.parentElement; p && p !== document.body; p = p.parentElement) {
      if (list.some(function(e) { return e !== cur && p.contains(e); })) { group = p; break; }
    }
    if (!group) return null;
    var c = rect(cur), ch = c.b - c.t, best = null;
    list.forEach(function(e) {
      if (e === cur || !group.contains(e)) return;
      var r = rect(e), tol = Math.min(ch, r.b - r.t) * 0.5;
      if (dir === 'right') {
        if (r.t < c.b - tol) return; // только ряды ниже
        // Ближайший ряд ниже, в нём — самый левый
        if (!best || r.t < best.r.t - tol || (Math.abs(r.t - best.r.t) <= tol && r.l < best.r.l)) best = { e: e, r: r };
      } else {
        if (r.b > c.t + tol) return; // только ряды выше
        // Ближайший ряд выше, в нём — самый правый
        if (!best || r.b > best.r.b + tol || (Math.abs(r.b - best.r.b) <= tol && r.r > best.r.r)) best = { e: e, r: r };
      }
    });
    return best ? best.e : null;
  }

  function first(list) {
    // Ближайший к левому верхнему углу видимой области
    var best = null, bestScore = Infinity;
    list.forEach(function(e) {
      var r = rect(e);
      if (r.b < 0 || r.t > innerHeight) return;
      var score = Math.max(r.t, 0) * 2 + Math.max(r.l, 0);
      if (score < bestScore) { bestScore = score; best = e; }
    });
    return best || list[0] || null;
  }

  function set(e) {
    if (cur) cur.classList.remove(CLS);
    cur = e;
    if (!e) return;
    e.classList.add(CLS);
    reveal(e);
  }

  // Прокрутка к выбранному элементу без «дёрганья»: страницу двигаем, только если элемент у края
  // экрана или за ним, и ровно настолько, чтобы он был виден с отступом. Раньше элемент всегда
  // ставился в центр — у карточек разной высоты центр «прыгал» даже при переходе по ряду.
  function reveal(e) {
    // Внутри прокручиваемых списков (выпадающий список, меню) — минимальная прокрутка самого списка
    if (popupOf(e)) e.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    var r = e.getBoundingClientRect(), m = innerHeight * 0.12, dy = 0;
    if (r.height > innerHeight - 2 * m) dy = r.top - m;           // выше экрана — к верхнему краю
    else if (r.top < m) dy = r.top - m;                           // у верхнего края — чуть вниз
    else if (r.bottom > innerHeight - m) dy = r.bottom - (innerHeight - m); // у нижнего — чуть вверх
    if (Math.abs(dy) > 1) scrollBy({ top: dy, behavior: 'smooth' });
  }

  window.__foxnav = {
    move: function(dir) {
      if (!cur || !cur.isConnected || !visible(cur)) {
        // Список закрылся — возвращаемся к элементу, который его открыл
        var back = owner && owner.isConnected && visible(owner) ? owner : null;
        owner = null;
        var f = back || first(candidates());
        set(f);
        return f && isPlayer(f) ? 'player' : 'ok';
      }
      var popup = popupOf(cur);
      var list = candidates(popup);
      var next = pick(dir, list) || ((dir === 'left' || dir === 'right') ? wrap(dir, list) : null);
      if (next) { set(next); return isPlayer(next) ? 'player' : 'ok'; }
      if (!popup && (dir === 'down' || dir === 'up')) scrollBy(0, (dir === 'down' ? 1 : -1) * innerHeight * 0.6);
      return 'none';
    },
    fromPlayer: function(dir) {
      var p = window.__foxplayer && window.__foxplayer.root();
      if (p) cur = p;
      return this.move(dir);
    },
    target: function() {
      if (!cur || !cur.isConnected || !visible(cur)) return null;
      if (isPlayer(cur)) return { player: true };
      if (!popupOf(cur)) owner = cur;
      // Объединённая карточка: нажимаем её ссылку (обложку), а не центр всей карточки
      var hit = cur.__foxTarget && cur.__foxTarget.isConnected ? cur.__foxTarget : cur;
      var before = visiblePopups();
      // Запоминаем окно, которое открылось именно от этого нажатия, — только его закрывает «Назад»
      setTimeout(function() {
        var now = visiblePopups().filter(function(p) { return before.indexOf(p) < 0; });
        if (now.length) opened = now[now.length - 1];
      }, 400);
      // Если нажатие закрыло список (выбран пункт) — сразу вернуть рамку на его владельца
      setTimeout(function() {
        if ((!cur || !cur.isConnected || !visible(cur) || !popupOf(cur) && cur !== owner) && owner && owner.isConnected && visible(owner)) {
          var o = owner; owner = null; set(o);
        }
      }, 400);
      var r = hit.getBoundingClientRect(), k = devicePixelRatio * (visualViewport ? visualViewport.scale : 1);
      var ox = visualViewport ? visualViewport.offsetLeft : 0, oy = visualViewport ? visualViewport.offsetTop : 0;
      return { x: ((r.left + r.right) / 2 - ox) * k, y: ((r.top + r.bottom) / 2 - oy) * k };
    },
    reset: function() { set(null); },
    // «Назад»: закрыть открытый список/окно. true — закрыли, обычный «Назад» не нужен
    escape: function() {
      var popup = popupOf(cur) || (opened && opened.isConnected && visible(opened) ? opened : null);
      opened = null;
      if (!popup || !visible(popup)) return false;
      // SlimSelect (фильтры сайта) закрывается своим API
      [].forEach.call(document.querySelectorAll('select'), function(sel) {
        if (sel.slim && typeof sel.slim.close === 'function') try { sel.slim.close(); } catch (e) {}
      });
      var t = document.activeElement || document.body;
      ['keydown', 'keyup'].forEach(function(type) {
        t.dispatchEvent(new KeyboardEvent(type, { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true }));
      });
      var o = owner;
      owner = null;
      if (o && o.isConnected && visible(o)) set(o);
      // Если Escape не помог — повторный клик по владельцу (так закрывается SlimSelect)
      // (SlimSelect уже закрыт через API, а клик во время анимации закрытия открыл бы его снова)
      if (!popup.classList.contains('ss-content')) {
        setTimeout(function() {
          if (!visible(popup)) return;
          // Кнопка закрытия окна («×» в правом верхнем углу), иначе — повторный клик по открывшему
          var close = closeButton(popup);
          if (close) close.click(); else if (o && o.isConnected) o.click();
        }, 600);
      }
      return true;
    }
  };
})()
"""
    }
}

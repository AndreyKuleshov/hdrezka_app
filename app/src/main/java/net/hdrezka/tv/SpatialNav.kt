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
  var cur = null, owner = null; // owner — элемент, открывший выпадающий список/окно
  var opened = null;             // список/окно, появившееся после нашего нажатия OK
  function visiblePopups() { return [].filter.call(document.querySelectorAll(POPUP), function(p) { return visible(p); }); }
  // Открытые выпадающие списки и окна: стрелки не выходят за их пределы
  var POPUP = '.ss-content,[role=listbox],[role=dialog],[role=menu],[aria-modal=true],.modal,.dropdown-menu,.popup';

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
    return out;
  }

  function isPlayer(e) { return !!(window.__foxplayer && e === window.__foxplayer.root()); }

  function rect(e) { var r = e.getBoundingClientRect(); return { l: r.left, t: r.top, r: r.right, b: r.bottom }; }

  function pick(dir, list) {
    var c = rect(cur), best = null, bestScore = Infinity;
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
      if (dir === 'down') { main = r.t - c.b; if (main < -tolY) return; gap = Math.max(0, r.l - c.r, c.l - r.r); cross = Math.abs(ex - cx); }
      if (dir === 'up') { main = c.t - r.b; if (main < -tolY) return; gap = Math.max(0, r.l - c.r, c.l - r.r); cross = Math.abs(ex - cx); }
      if (dir === 'right') { main = r.l - c.r; if (main < -tolX) return; gap = Math.max(0, r.t - c.b, c.t - r.b); cross = Math.abs(ey - cy); }
      if (dir === 'left') { main = c.l - r.r; if (main < -tolX) return; gap = Math.max(0, r.t - c.b, c.t - r.b); cross = Math.abs(ey - cy); }
      // Влево/вправо — только в пределах «ряда»: в конце ряда рамка остаётся на месте,
      // а не прыгает на далёкий элемент ниже (например, кнопки «Поделиться» под списком серий)
      if ((dir === 'left' || dir === 'right') && gap > Math.max(ch, rh) * 1.5) return;
      // Ближе по направлению, без зазора поперёк, при равенстве — ровнее по центру
      var score = Math.max(main, 0) + gap * 3 + cross * 0.1;
      if (score < bestScore) { bestScore = score; best = e; }
    });
    return best;
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
    e.scrollIntoView({ block: 'center', inline: 'nearest' });
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
      var next = pick(dir, candidates(popup));
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
      var r = cur.getBoundingClientRect(), k = devicePixelRatio * (visualViewport ? visualViewport.scale : 1);
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
        setTimeout(function() { if (visible(popup) && o && o.isConnected) o.click(); }, 600);
      }
      return true;
    }
  };
})()
"""
    }
}

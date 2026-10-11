package net.hdrezka.tv

import android.view.View
import android.webkit.WebView
import android.widget.TextView
import org.json.JSONObject

/**
 * Переключение серий в плеере сайта: нажимает соседнюю серию в списке
 * `.episodes_list a[data-episode]` (текущая помечена классом active),
 * а на краю сезона — соседний сезон из `.seasons_list`.
 *
 * Плашка с кнопками «◀ / ▶» видна, пока на странице есть список серий,
 * и прячется через несколько секунд без нажатий.
 */
class EpisodeNav(
    private val webView: WebView,
    private val bar: View,
    private val prevButton: View,
    private val nextButton: View,
    private val label: TextView,
) {
    data class State(val season: String, val episode: String, val index: Int, val count: Int, val hasPrev: Boolean, val hasNext: Boolean)

    var state: State? = null
        private set

    private val hide = Runnable { bar.visibility = View.GONE }

    init {
        prevButton.setOnClickListener { go(-1) }
        nextButton.setOnClickListener { go(1) }
    }

    /** Перечитать список серий на текущей странице. */
    fun refresh() {
        webView.evaluateJavascript(STATE_JS) { result ->
            state = parse(result)
            render()
        }
    }

    fun clear() {
        state = null
        bar.removeCallbacks(hide)
        bar.visibility = View.GONE
    }

    /** Показать плашку на время (вызывается при любом нажатии пульта). */
    fun poke() {
        val s = state ?: return
        render(s)
        bar.visibility = View.VISIBLE
        bar.removeCallbacks(hide)
        bar.postDelayed(hide, HIDE_DELAY_MS)
    }

    /** dir = 1 — следующая серия, -1 — предыдущая. */
    fun go(dir: Int): Boolean {
        val s = state ?: return false
        if ((dir > 0 && !s.hasNext) || (dir < 0 && !s.hasPrev)) return false
        webView.evaluateJavascript("($GO_JS)($dir)", null)
        return true
    }

    private fun render(s: State? = state) {
        if (s == null) {
            clear()
            return
        }
        label.text = label.context.getString(R.string.episode_label, s.season, s.episode, s.count)
        prevButton.isEnabled = s.hasPrev
        prevButton.alpha = if (s.hasPrev) 1f else 0.4f
        nextButton.isEnabled = s.hasNext
        nextButton.alpha = if (s.hasNext) 1f else 0.4f
    }

    private fun parse(json: String?): State? {
        if (json.isNullOrBlank() || json == "null") return null
        return try {
            val o = JSONObject(json)
            State(
                o.getString("season"), o.getString("episode"),
                o.getInt("index"), o.getInt("count"),
                o.getBoolean("prev"), o.getBoolean("next"),
            )
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val HIDE_DELAY_MS = 5000L

        private const val STATE_JS = """
(function(){
  var eps = [].slice.call(document.querySelectorAll('.episodes_list a[data-episode]'));
  if (!eps.length) return null;
  var i = -1;
  for (var k = 0; k < eps.length; k++) if (eps[k].classList.contains('active')) { i = k; break; }
  var ss = [].slice.call(document.querySelectorAll('.seasons_list a[data-season]'));
  var j = -1;
  for (var k = 0; k < ss.length; k++) if (ss[k].classList.contains('active')) { j = k; break; }
  var cur = eps[i] || eps[0];
  return {
    season: cur.getAttribute('data-season') || '1',
    episode: i >= 0 ? cur.getAttribute('data-episode') : '—',
    index: i, count: eps.length,
    prev: i > 0 || j > 0,
    next: (i >= 0 && i < eps.length - 1) || (j >= 0 && j < ss.length - 1)
  };
})()
"""

        private const val GO_JS = """
function(dir){
  var eps = [].slice.call(document.querySelectorAll('.episodes_list a[data-episode]'));
  var i = -1;
  for (var k = 0; k < eps.length; k++) if (eps[k].classList.contains('active')) { i = k; break; }
  if (i >= 0 && eps[i + dir]) { eps[i + dir].click(); return; }
  var ss = [].slice.call(document.querySelectorAll('.seasons_list a[data-season]'));
  var j = -1;
  for (var k = 0; k < ss.length; k++) if (ss[k].classList.contains('active')) { j = k; break; }
  if (j >= 0 && ss[j + dir]) ss[j + dir].click();
}
"""
    }
}

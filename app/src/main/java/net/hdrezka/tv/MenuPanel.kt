package net.hdrezka.tv

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray

/** Раздел сайта: название, адрес и уровень вложенности (0 — пункт меню, 1 — подпункт). */
data class Section(val title: String, val url: String, val level: Int)

/**
 * Боковая панель разделов. Пункты — ссылки из меню самого сайта
 * (собираются со страницы через [EXTRACT_JS]) плюс служебные действия.
 */
class MenuPanel(
    private val panel: ScrollView,
    private val items: LinearLayout,
) {
    class Action(val title: String, val run: () -> Unit)

    val isOpen get() = panel.visibility == View.VISIBLE

    private val density = panel.resources.displayMetrics.density

    fun open(sections: List<Section>, onSection: (Section) -> Unit, actions: List<Action>) {
        items.removeAllViews()
        items.addView(header(panel.context.getString(R.string.menu_title)))
        if (sections.isEmpty()) {
            items.addView(note(panel.context.getString(R.string.menu_empty)))
        }
        sections.forEach { s -> items.addView(item(s.title, s.level) { onSection(s) }) }
        items.addView(divider())
        actions.forEach { a -> items.addView(item(a.title, 0, a.run)) }

        panel.visibility = View.VISIBLE
        panel.scrollTo(0, 0)
        // Фокус на первый пункт после заголовка
        items.post { (0 until items.childCount).map(items::getChildAt).firstOrNull { it.isFocusable }?.requestFocusFromTouch() }
    }

    fun close() {
        panel.visibility = View.GONE
        items.removeAllViews()
    }

    private fun dp(v: Int) = (v * density).toInt()

    private fun header(text: String) = TextView(panel.context).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(16), 0, dp(16), dp(16))
    }

    private fun note(text: String) = TextView(panel.context).apply {
        this.text = text
        setTextColor(Color.LTGRAY)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(dp(16), dp(8), dp(16), dp(8))
    }

    private fun divider() = View(panel.context).apply {
        setBackgroundColor(0x33FFFFFF)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            setMargins(dp(16), dp(12), dp(16), dp(12))
        }
    }

    private fun item(text: String, level: Int, onClick: () -> Unit) = TextView(panel.context).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (level == 0) 20f else 17f)
        if (level > 0) alpha = 0.85f
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(48)
        setPadding(dp(16 + 20 * level), dp(8), dp(16), dp(8))
        setBackgroundResource(R.drawable.menu_item_bg)
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
    }

    companion object {
        /**
         * Собирает ссылки навигации сайта: элементы nav/header/меню, только тот же домен,
         * без дублей. Уровень — вложенность списков (подменю жанров и т.п.).
         */
        const val EXTRACT_JS = """
(function(){
  var host = location.hostname, seen = {}, out = [];
  var sel = 'nav a[href], header a[href], [class*="menu"] a[href], [class*="nav"] a[href], [id*="menu"] a[href], [id*="nav"] a[href]';
  var links = document.querySelectorAll(sel);
  for (var i = 0; i < links.length && out.length < 60; i++) {
    var a = links[i];
    var t = (a.textContent || '').replace(/\s+/g, ' ').trim();
    if (t.length < 2 || t.length > 40) continue;
    var u;
    try { u = new URL(a.getAttribute('href'), location.href); } catch (e) { continue; }
    if (u.protocol !== 'http:' && u.protocol !== 'https:') continue;
    if (u.hostname !== host && u.hostname.slice(-host.length - 1) !== '.' + host) continue;
    u.hash = '';
    if (seen[u.href]) continue;
    seen[u.href] = 1;
    var depth = 0;
    for (var p = a.parentElement; p; p = p.parentElement) if (p.tagName === 'UL' || p.tagName === 'OL') depth++;
    out.push({ t: t, u: u.href, l: depth > 1 ? 1 : 0 });
  }
  return out;
})()
"""

        fun parse(json: String?): List<Section> {
            if (json.isNullOrBlank() || json == "null") return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Section(o.getString("t"), o.getString("u"), o.optInt("l"))
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun toJson(sections: List<Section>): String = JSONArray().apply {
            sections.forEach { s ->
                put(org.json.JSONObject().put("t", s.title).put("u", s.url).put("l", s.level))
            }
        }.toString()
    }
}

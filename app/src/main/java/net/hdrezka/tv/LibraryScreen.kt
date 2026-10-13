package net.hdrezka.tv

import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Экран библиотеки поверх страницы: ряд «Continue watching» (недосмотренные серии)
 * и ряд «Saved» (отложенные сериалы). Навигация обычная, по фокусу:
 * стрелки ходят по карточкам, OK открывает, долгое OK убирает из списка.
 */
class LibraryScreen(
    private val root: View,
    private val store: WatchStore,
    private val posters: PosterCache,
    /**
     * Открыть адрес. [play] = true — это недосмотренная серия, её надо сразу запустить
     * с сохранённого места; false — страница сериала, где выбирают серию сами.
     */
    private val onOpen: (url: String, play: Boolean) -> Unit,
    /** Спросить подтверждение перед удалением карточки из списка. */
    private val onConfirmRemove: (name: String, remove: () -> Unit) -> Unit,
) {
    private val historyRow = root.findViewById<LinearLayout>(R.id.historyRow)
    private val savedRow = root.findViewById<LinearLayout>(R.id.savedRow)
    private val savedTitle = root.findViewById<TextView>(R.id.savedTitle)
    private val savedScroll = root.findViewById<View>(R.id.savedScroll)
    private val hint = root.findViewById<TextView>(R.id.libraryHint)

    val isOpen get() = root.visibility == View.VISIBLE

    fun open() {
        build()
        root.visibility = View.VISIBLE
        root.post {
            val first = (0 until historyRow.childCount).map(historyRow::getChildAt)
                .firstOrNull { it.isFocusable }
                ?: (0 until savedRow.childCount).map(savedRow::getChildAt).firstOrNull { it.isFocusable }
            first?.requestFocusFromTouch()
        }
    }

    fun close() {
        root.visibility = View.GONE
        historyRow.removeAllViews()
        savedRow.removeAllViews()
    }

    private fun build() {
        // Рядов на экране всё равно не видно больше десятка: строить сотню карточек разом
        // значит на секунду подвесить открытие экрана
        val history = store.history().filter { it.last != null }.take(ROW_LIMIT)
        val saved = store.favorites().take(ROW_LIMIT)

        historyRow.removeAllViews()
        if (history.isEmpty()) historyRow.addView(emptyLabel()) else history.forEach { card(historyRow, it, true) }

        savedRow.removeAllViews()
        saved.forEach { card(savedRow, it, false) }
        val hasSaved = saved.isNotEmpty()
        savedTitle.visibility = if (hasSaved) View.VISIBLE else View.GONE
        savedScroll.visibility = if (hasSaved) View.VISIBLE else View.GONE
        hint.visibility = if (history.isEmpty() && !hasSaved) View.GONE else View.VISIBLE
    }

    private fun emptyLabel() = TextView(root.context).apply {
        text = context.getString(R.string.library_empty)
        setTextColor(0xCCFFFFFF.toInt())
        textSize = 18f
    }

    private companion object {
        const val ROW_LIMIT = 25
    }

    /** [fromHistory] различает, что делать по долгому OK: забыть просмотр или снять отметку «отложено». */
    private fun card(row: LinearLayout, show: Show, fromHistory: Boolean) {
        val v = LayoutInflater.from(root.context).inflate(R.layout.library_card, row, false)
        val title = v.findViewById<TextView>(R.id.cardTitle)
        val subtitle = v.findViewById<TextView>(R.id.cardSubtitle)
        val progress = v.findViewById<ProgressBar>(R.id.cardProgress)

        title.text = show.title.ifBlank { show.url.substringAfterLast('/') }
        val last = show.last
        subtitle.text = if (last != null) "S${last.season}E${last.episode} · ${formatTime(last.pos)}" else ""
        progress.visibility = if (last != null && last.fraction > 0) View.VISIBLE else View.GONE
        progress.progress = ((last?.fraction ?: 0f) * 1000).toInt()
        posters.load(show.poster, v.findViewById(R.id.cardPoster))

        // Из истории открываем саму серию и запускаем её; из отложенных — страницу сериала,
        // где сезон и серию выбирают заново
        val target = if (fromHistory) last?.url?.ifBlank { show.url } ?: show.url else show.url
        v.setOnClickListener { onOpen(target, fromHistory && last != null) }
        v.setOnLongClickListener {
            onConfirmRemove(title.text.toString()) {
                if (fromHistory) store.forget(show.url)
                else store.toggleFavorite(show.url, show.title, show.poster)
                build()
            }
            true
        }
        row.addView(v)
    }

}

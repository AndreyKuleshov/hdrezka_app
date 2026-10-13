package net.hdrezka.tv

import android.view.View
import android.widget.TextView

/**
 * Вопрос «да/нет» в языке приложения: карточка, крупный текст и кнопки с той же
 * оранжевой заливкой фокуса, что везде. Системный диалог на телевизоре показывает
 * выбранную кнопку еле заметным серым, и с трёх метров непонятно, что нажимаешь.
 */
class ConfirmOverlay(private val root: View) {

    private val title = root.findViewById<TextView>(R.id.confirmTitle)
    private val text = root.findViewById<TextView>(R.id.confirmText)
    private val yes = root.findViewById<TextView>(R.id.confirmYes)
    private val no = root.findViewById<TextView>(R.id.confirmNo)

    val isOpen get() = root.visibility == View.VISIBLE

    /** Фокус стартует на отказе: случайное нажатие ничего не сделает. */
    fun ask(titleText: String, message: String, yesLabel: String, noLabel: String, onYes: () -> Unit) {
        title.text = titleText
        text.text = message
        yes.text = yesLabel
        no.text = noLabel
        yes.setOnClickListener { close(); onYes() }
        no.setOnClickListener { close() }
        root.visibility = View.VISIBLE
        root.post { no.requestFocusFromTouch() }
    }

    fun close() {
        root.visibility = View.GONE
    }
}

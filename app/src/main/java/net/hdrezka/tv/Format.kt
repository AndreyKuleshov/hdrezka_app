package net.hdrezka.tv

/**
 * Время в «ч:мм:сс» или «мм:сс» — одинаково в плеере и на карточках библиотеки.
 * Раньше формат был свой в каждом месте, и одна и та же позиция выглядела как «05:30» и «5:30».
 */
fun formatTime(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

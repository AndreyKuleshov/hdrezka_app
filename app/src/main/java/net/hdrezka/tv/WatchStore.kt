package net.hdrezka.tv

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Серия, на которой остановились: номер, адрес и позиция внутри неё. */
data class LastEpisode(
    val season: String,
    val episode: String,
    val url: String,
    val pos: Double,
    val dur: Double,
) {
    /** Доля просмотренного — для полоски на карточке. */
    val fraction: Float get() = if (dur > 0) (pos / dur).toFloat().coerceIn(0f, 1f) else 0f
}

/**
 * Сериал в истории или в отложенных. История ведётся по сериалу, а не по серии:
 * иначе список превращается в перечисление всех просмотренных эпизодов.
 */
data class Show(
    val url: String,
    val title: String,
    val poster: String,
    val last: LastEpisode? = null,
    val seen: Set<String> = emptySet(), // «сезон:серия», например «1:2»
    val at: Long = 0,
)

/**
 * История просмотра и отложенные сериалы в [android.content.SharedPreferences].
 * Данных немного (десятки записей), поэтому хранится всё одним JSON — как разделы меню.
 */
class WatchStore(context: Context) {

    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    // --- Чтение ---

    /** Недосмотренные и недавние сериалы, свежие сверху. */
    fun history(): List<Show> = read(KEY_HISTORY) // порядок задаётся при записи

    /** Отложенные «на потом», свежие сверху. */
    fun favorites(): List<Show> = read(KEY_FAVORITES)

    fun isFavorite(showUrl: String): Boolean =
        read(KEY_FAVORITES).any { it.url == canonical(showUrl) }

    /** Сохранённая позиция конкретной серии, 0 — если её не смотрели. */
    fun positionOf(episodeUrl: String): Double {
        val show = read(KEY_HISTORY).firstOrNull { it.url == canonical(episodeUrl) } ?: return 0.0
        val last = show.last ?: return 0.0
        return if (last.url == episodeUrl) last.pos else 0.0
    }

    /** Серия, на которой остановились в этом сериале, — для кнопки «Continue». */
    fun lastOf(showUrl: String): LastEpisode? =
        read(KEY_HISTORY).firstOrNull { it.url == canonical(showUrl) }?.last

    /** Просмотренные серии сериала в виде «сезон:серия» — для галочек в списке серий. */
    fun seenEpisodes(showUrl: String): Set<String> =
        read(KEY_HISTORY).firstOrNull { it.url == canonical(showUrl) }?.seen ?: emptySet()

    /** Прогресс по всем сериалам: адрес сериала → доля просмотра последней серии. */
    fun progressByShow(): Map<String, Float> =
        read(KEY_HISTORY).filter { it.last != null }.associate { it.url to it.last!!.fraction }

    // --- Запись ---

    /**
     * Запомнить, где остановились. Вызывается по ходу воспроизведения, поэтому пишет
     * только при осмысленных данных: нужен адрес сериала и ненулевая длительность.
     */
    fun saveProgress(
        showUrl: String,
        title: String,
        poster: String,
        season: String,
        episode: String,
        episodeUrl: String,
        pos: Double,
        dur: Double,
    ) {
        val key = canonical(showUrl)
        if (key.isBlank() || dur <= 0) return
        val list = read(KEY_HISTORY).toMutableList()
        val old = list.firstOrNull { it.url == key }
        list.remove(old)
        val seen = old?.seen.orEmpty().toMutableSet()
        if (pos > dur * SEEN_FRACTION) seen += "$season:$episode"
        list += Show(
            url = key,
            title = title.ifBlank { old?.title.orEmpty() },
            poster = poster.ifBlank { old?.poster.orEmpty() },
            last = LastEpisode(season, episode, episodeUrl, pos, dur),
            seen = seen,
            at = System.currentTimeMillis(),
        )
        write(KEY_HISTORY, list, MAX_HISTORY)
    }

    /** Отметить серию просмотренной целиком (конец серии или автопереход к следующей). */
    fun markSeen(showUrl: String, season: String, episode: String) {
        val key = canonical(showUrl)
        val list = read(KEY_HISTORY).toMutableList()
        val old = list.firstOrNull { it.url == key } ?: return
        list.remove(old)
        list += old.copy(seen = old.seen + "$season:$episode", at = System.currentTimeMillis())
        write(KEY_HISTORY, list, MAX_HISTORY)
    }

    /** Отложить сериал или убрать из отложенных; возвращает новое состояние. */
    fun toggleFavorite(showUrl: String, title: String, poster: String): Boolean {
        val key = canonical(showUrl)
        if (key.isBlank()) return false
        val list = read(KEY_FAVORITES).toMutableList()
        val old = list.firstOrNull { it.url == key }
        return if (old != null) {
            list.remove(old)
            write(KEY_FAVORITES, list, MAX_FAVORITES)
            false
        } else {
            list += Show(key, title, poster, at = System.currentTimeMillis())
            write(KEY_FAVORITES, list, MAX_FAVORITES)
            true
        }
    }

    /** Убрать сериал из истории (пункт «забыть» на карточке). */
    fun forget(showUrl: String) {
        val key = canonical(showUrl)
        write(KEY_HISTORY, read(KEY_HISTORY).filterNot { it.url == key }, MAX_HISTORY)
    }

    // --- Хранение ---

    /**
     * Списки держим в памяти: позиция пишется раз в 10 секунд, и каждый раз заново
     * разбирать и собирать весь JSON — лишняя работа в главном потоке.
     * Мост вызывается из потока WebView, плеер и экран — из UI, поэтому всё под замком.
     */
    private val cache = HashMap<String, List<Show>>()
    private val dirty = HashSet<String>()
    private var lastSaveAt = 0L

    @Synchronized
    private fun read(key: String): List<Show> = cache.getOrPut(key) {
        val raw = prefs.getString(key, null) ?: return@getOrPut emptyList()
        try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> parse(arr.optJSONObject(i)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Изменения живут в памяти, на диск уходят не чаще раза в [SAVE_INTERVAL_MS].
     * За 45-минутную серию это 5 записей вместо ~270 — меньше износ флеш-памяти приставки
     * и нет очереди, которой `onPause` дожидается перед выходом.
     */
    @Synchronized
    private fun write(key: String, list: List<Show>, limit: Int) {
        cache[key] = list.sortedByDescending { it.at }.take(limit)
        dirty += key
        val now = System.currentTimeMillis()
        if (now - lastSaveAt >= SAVE_INTERVAL_MS) persist()
    }

    /** Сбросить накопленное на диск: при уходе в фон и закрытии приложения. */
    @Synchronized
    fun flush() = persist()

    private fun persist() {
        if (dirty.isEmpty()) return
        lastSaveAt = System.currentTimeMillis()
        val edit = prefs.edit()
        dirty.forEach { key -> edit.putString(key, toArray(cache[key].orEmpty()).toString()) }
        dirty.clear()
        edit.apply()
    }

    private fun toJson(s: Show): JSONObject {
        val o = JSONObject().put("u", s.url).put("t", s.title).put("p", s.poster).put("at", s.at)
        s.last?.let { e ->
            o.put(
                "ep", JSONObject().put("s", e.season).put("e", e.episode)
                    .put("url", e.url).put("pos", e.pos).put("dur", e.dur)
            )
        }
        if (s.seen.isNotEmpty()) o.put("seen", JSONArray(s.seen.toList()))
        return o
    }

    private fun toArray(list: List<Show>): JSONArray =
        JSONArray().also { arr -> list.forEach { arr.put(toJson(it)) } }

    // --- Резервная копия ---

    /** Вся библиотека одним JSON — её [LibraryBackup] кладёт в файл. */
    @Synchronized
    fun exportJson(): String = JSONObject()
        .put("v", BACKUP_VERSION)
        .put(KEY_HISTORY, toArray(read(KEY_HISTORY)))
        .put(KEY_FAVORITES, toArray(read(KEY_FAVORITES)))
        .toString()

    /**
     * Влить резервную копию. Сериалы сливаются по адресу, побеждает более свежая запись:
     * импорт на устройстве, где уже смотрели, не должен откатывать прогресс назад.
     * Просмотренные серии объединяются из обеих записей. Возвращает число записей в копии
     * или -1, если файл не похож на нашу копию.
     */
    @Synchronized
    fun importJson(json: String): Int {
        val o = try { JSONObject(json) } catch (e: Exception) { return -1 }
        if (!o.has(KEY_HISTORY) && !o.has(KEY_FAVORITES)) return -1
        var count = 0
        listOf(KEY_HISTORY to MAX_HISTORY, KEY_FAVORITES to MAX_FAVORITES).forEach { (key, limit) ->
            val arr = o.optJSONArray(key) ?: JSONArray()
            val incoming = (0 until arr.length()).mapNotNull { parse(arr.optJSONObject(it)) }
            count += incoming.size
            val merged = LinkedHashMap<String, Show>()
            read(key).forEach { merged[it.url] = it }
            incoming.forEach { s ->
                val old = merged[s.url]
                val seen = s.seen + old?.seen.orEmpty()
                merged[s.url] = if (old != null && old.at > s.at) old.copy(seen = seen) else s.copy(seen = seen)
            }
            write(key, merged.values.toList(), limit)
        }
        persist() // импорт — явное действие, его результат на диск кладём сразу
        return count
    }

    private fun parse(o: JSONObject?): Show? {
        val url = o?.optString("u").orEmpty()
        if (url.isBlank()) return null
        val ep = o?.optJSONObject("ep")?.let {
            LastEpisode(
                season = it.optString("s"),
                episode = it.optString("e"),
                url = it.optString("url"),
                pos = it.optDouble("pos", 0.0),
                dur = it.optDouble("dur", 0.0),
            )
        }
        val seenArr = o?.optJSONArray("seen")
        val seen = (0 until (seenArr?.length() ?: 0)).mapNotNull { seenArr?.optString(it) }.toSet()
        return Show(url, o?.optString("t").orEmpty(), o?.optString("p").orEmpty(), ep, seen, o?.optLong("at") ?: 0)
    }

    companion object {
        private const val KEY_HISTORY = "history"
        private const val KEY_FAVORITES = "favorites"
        private const val MAX_HISTORY = 50
        private const val MAX_FAVORITES = 100
        private const val SAVE_INTERVAL_MS = 60_000L // чаще раза в минуту на диск не ходим
        private const val SEEN_FRACTION = 0.9 // дальше этого считаем серию просмотренной
        private const val BACKUP_VERSION = 1

        /**
         * Адрес сериала из адреса серии: у сайта это первые три сегмента пути
         * (`/serial/uzhasy/16101-posledovateli-2013`), дальше идут озвучка, сезон и серия.
         */
        fun canonical(url: String): String {
            if (url.isBlank()) return ""
            val uri = Uri.parse(url)
            // Только обычные веб-адреса: иначе в библиотеку можно записать «javascript:…»
            // или «file://…», и она же потом откроет это в WebView
            if (uri.scheme != "http" && uri.scheme != "https" || uri.host.isNullOrBlank()) return ""
            val parts = uri.pathSegments ?: return ""
            if (parts.size < 3) return url.substringBefore('?').trimEnd('/')
            return "${uri.scheme}://${uri.host}/${parts[0]}/${parts[1]}/${parts[2]}"
        }
    }
}

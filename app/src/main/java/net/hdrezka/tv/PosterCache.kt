package net.hdrezka.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Обложки для экрана библиотеки: качает и кэширует их сам, без Glide и Coil —
 * ради одного экрана тянуть зависимость незачем.
 * Кэш двойной: разжатые картинки в памяти и файлы в `filesDir/posters`.
 */
class PosterCache(context: Context) {

    private val dir = File(context.filesDir, "posters").apply { mkdirs() }
    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2)

    /**
     * Кто ждёт обложку, пока она качается. Один сериал может стоять и в истории, и в отложенных —
     * без этого списка его обложка скачалась бы дважды. Трогаем только из главного потока.
     */
    private val pending = HashMap<String, MutableList<ImageView>>()

    init {
        pool.execute { trim() }
    }

    private val memory = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(2 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /**
     * Показать обложку в [view]. Пока она грузится, остаётся плашка-заглушка;
     * если адрес вью успел смениться (карточки переиспользуются), результат отбрасывается.
     */
    fun load(url: String, view: ImageView) {
        view.tag = url
        // Адрес обложки приходит со страницы: качаем только по HTTPS и ничего больше
        if (url.isBlank() || !url.startsWith("https://")) {
            view.setImageDrawable(null)
            return
        }
        memory.get(url)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        pending[url]?.let { waiting ->
            waiting += view // уже качается — просто встанем в очередь за готовой картинкой
            return
        }
        pending[url] = mutableListOf(view)
        pool.execute {
            val bitmap = fromDisk(url) ?: download(url)
            if (bitmap != null) memory.put(url, bitmap)
            ui.post {
                val waiting = pending.remove(url) ?: return@post
                if (bitmap == null) return@post
                // Карточки переиспользуются: показываем только там, где ждут этот же адрес
                waiting.forEach { v -> if (v.tag == url) v.setImageBitmap(bitmap) }
            }
        }
    }

    fun shutdown() = pool.shutdownNow()

    /** Отдать память под картинки: на диске они останутся, обратно разожмутся быстро. */
    fun trimMemory() = memory.evictAll()

    /**
     * Подрезать кэш на диске: обложки копятся от всех сериалов, которые когда-либо попадали
     * в библиотеку, а помнить их дольше самой библиотеки незачем. Оставляем свежие,
     * пока не упрёмся в число файлов или в общий размер.
     */
    private fun trim() {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var used = 0L
        files.forEachIndexed { i, f ->
            used += f.length()
            if (i >= MAX_FILES || used > MAX_BYTES) f.delete()
        }
    }

    private fun fileOf(url: String) = File(dir, url.hashCode().toString())

    private fun fromDisk(url: String): Bitmap? {
        val f = fileOf(url)
        if (!f.exists()) return null
        return try {
            BitmapFactory.decodeFile(f.absolutePath, options())
        } catch (e: Exception) {
            null
        }
    }

    private fun download(url: String): Bitmap? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
        }
        val bytes = conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        // Сохраняем только то, что действительно разобралось в картинку: вместо постера
        // сайт может отдать страницу с ошибкой, и такой файл кэшировать нечего
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options())
            ?.also { fileOf(url).writeBytes(bytes) }
    } catch (e: Exception) {
        null // нет сети или картинка не отдалась — карточка останется с плашкой
    }

    /** Постеры сайта заметно крупнее карточки, поэтому ужимаем их при декодировании. */
    private fun options() = BitmapFactory.Options().apply {
        inSampleSize = 2
        // Постеры непрозрачные: RGB_565 вдвое экономит память против ARGB_8888
        inPreferredConfig = Bitmap.Config.RGB_565
    }

    companion object {
        private const val TIMEOUT_MS = 8000
        private const val MAX_FILES = 150   // столько сериалов максимум помещается в библиотеку
        private const val MAX_BYTES = 16L * 1024 * 1024
    }
}

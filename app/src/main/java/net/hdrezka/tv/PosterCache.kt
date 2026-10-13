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
        if (url.isBlank()) {
            view.setImageDrawable(null)
            return
        }
        memory.get(url)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        pool.execute {
            val bitmap = fromDisk(url) ?: download(url)
            if (bitmap != null) memory.put(url, bitmap)
            ui.post { if (view.tag == url && bitmap != null) view.setImageBitmap(bitmap) }
        }
    }

    fun shutdown() = pool.shutdownNow()

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
        fileOf(url).writeBytes(bytes)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options())
    } catch (e: Exception) {
        null // нет сети или картинка не отдалась — карточка останется с плашкой
    }

    /** Постеры сайта заметно крупнее карточки, поэтому ужимаем их при декодировании. */
    private fun options() = BitmapFactory.Options().apply { inSampleSize = 2 }

    companion object {
        private const val TIMEOUT_MS = 8000
    }
}

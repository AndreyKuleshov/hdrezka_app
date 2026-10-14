package net.hdrezka.tv

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File

/**
 * Резервная копия библиотеки в общей папке «Загрузки». Папка переживает удаление приложения,
 * поэтому историю и отложенные можно вернуть после переустановки — например, когда обновление
 * не встаёт поверх из-за другого ключа подписи.
 *
 * Системного выборщика файлов на Android TV нет (`ACTION_OPEN_DOCUMENT` некому обработать),
 * поэтому имя файла фиксированное: экспорт перезаписывает прежнюю копию, импорт читает её же.
 */
object LibraryBackup {

    const val FILE_NAME = "foxtv-library.json"
    private const val MIME = "application/json"

    /** Записать копию; возвращает путь для сообщения пользователю или null, если не вышло. */
    fun export(context: Context, store: WatchStore): String? = try {
        val json = store.exportJson()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = Q.find(context) ?: Q.create(context)
            uri?.let {
                // «wt» — усечь прежнее содержимое: без него короткая копия осталась бы
                // с хвостом длинной и перестала разбираться
                context.contentResolver.openOutputStream(it, "wt")
                    ?.use { out -> out.write(json.toByteArray()) }
                shownPath()
            }
        } else {
            legacyFile().apply { parentFile?.mkdirs() }.writeText(json)
            shownPath()
        }
    } catch (e: Exception) {
        null
    }

    /** Прочитать копию: число записей в ней, 0 — файла нет, -1 — файл не наш или недоступен. */
    fun import(context: Context, store: WatchStore): Int = try {
        val json = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Q.find(context)?.let { uri ->
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }
        } else {
            legacyFile().takeIf { it.exists() }?.readText()
        }
        if (json.isNullOrBlank()) 0 else store.importJson(json)
    } catch (e: Exception) {
        -1
    }

    private fun shownPath() = "${Environment.DIRECTORY_DOWNLOADS}/$FILE_NAME"

    private fun legacyFile() =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FILE_NAME)

    /**
     * Android 10 и новее: прямого доступа к «Загрузкам» нет, файл заводится через MediaStore.
     * Свою запись приложение видит по имени пакета, поэтому копия остаётся читаемой
     * и после переустановки — ради этого всё и затевалось.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private object Q {
        fun find(context: Context): Uri? {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(FILE_NAME),
                null,
            )?.use { c ->
                if (c.moveToFirst()) return Uri.withAppendedPath(collection, c.getLong(0).toString())
            }
            return null
        }

        fun create(context: Context): Uri? = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, MIME)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            },
        )
    }
}

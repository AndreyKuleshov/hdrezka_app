package net.hdrezka.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Обновление приложения из него самого: релизы лежат в GitHub Releases, собранные по тегу,
 * и APK оттуда подписан тем же постоянным ключом — поэтому встаёт поверх установленной версии.
 * Репозиторий публичный, так что токен не нужен.
 */
class UpdateChecker(context: Context) {

    // Держим контекст приложения: поток загрузки живёт дольше экрана
    private val context = context.applicationContext

    /** Доступная версия и ссылка на её APK. */
    data class Release(val version: String, val apkUrl: String)

    private val io = Executors.newSingleThreadExecutor()

    fun shutdown() {
        io.shutdownNow()
    }
    private val ui = Handler(Looper.getMainLooper())

    /** Спросить GitHub о последнем релизе. [onResult] получает null, если обновления нет. */
    fun check(onResult: (Release?) -> Unit) {
        io.execute {
            val release = runCatching { fetchLatest() }.getOrNull()
            val newer = release?.takeIf { isNewer(it.version) }
            ui.post { onResult(newer) }
        }
    }

    /** Скачать APK во внутренний каталог приложения; [onDone] получает null при ошибке. */
    fun download(release: Release, onDone: (File?) -> Unit) {
        io.execute {
            val file = runCatching { fetchApk(release) }.getOrNull()
            ui.post { onDone(file) }
        }
    }

    enum class InstallResult {
        /** Установщик открыт. */
        STARTED,
        /** Нет разрешения «устанавливать неизвестные приложения», открыт экран настроек. */
        NEED_PERMISSION,
        /** Подпись не совпадает: Android откажет с «package conflicts with an existing package». */
        SIGNATURE_MISMATCH,
        FAILED,
    }

    /**
     * Отдать APK системному установщику. Перед этим сверяем подпись: релизы подписаны
     * постоянным ключом, а локальная debug-сборка — своим, и система такую замену отклонит.
     * Лучше сказать об этом понятно, чем показать системную ошибку про конфликт пакетов.
     */
    fun install(file: File): InstallResult {
        if (!signatureMatches(file)) return InstallResult.SIGNATURE_MISMATCH
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
            return InstallResult.NEED_PERMISSION
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); InstallResult.STARTED }
            .getOrDefault(InstallResult.FAILED)
    }

    /** Сравниваем подпись установленного приложения и скачанного APK. */
    @Suppress("DEPRECATION") // GET_SIGNATURES — единственный вариант на minSdk 21
    private fun signatureMatches(file: File): Boolean = try {
        val pm = context.packageManager
        val mine = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        val theirs = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)?.signatures
        // Не смогли прочитать — не мешаем, пусть решает система
        if (mine == null || theirs == null) true
        else mine.map { it.toCharsString() }.toSet() == theirs.map { it.toCharsString() }.toSet()
    } catch (e: Exception) {
        true
    }

    private fun fetchLatest(): Release? {
        val json = open("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        val o = JSONObject(json)
        val tag = o.optString("tag_name").removePrefix("v")
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                val url = a.optString("browser_download_url")
                // Качаем только с GitHub и только по HTTPS, даже если JSON говорит иное
                return if (trusted(url)) Release(tag, url) else null
            }
        }
        return null
    }

    private fun fetchApk(release: Release): File {
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // прошлые загрузки не копим
        // Имя собираем из цифр версии: тег приходит извне, а в имени файла не должно быть путей
        val safe = release.version.filter { it.isDigit() || it == '.' }.ifBlank { "update" }
        val file = File(dir, "FoxTV-v$safe.apk")
        open(release.apkUrl).inputStream.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }

    /** Адрес ведёт на GitHub по HTTPS. */
    private fun trusted(url: String): Boolean {
        val uri = Uri.parse(url)
        val host = uri.host ?: return false
        return uri.scheme == "https" &&
            (host == "github.com" || host.endsWith(".github.com") || host.endsWith(".githubusercontent.com"))
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "FoxTV") // GitHub отвергает запросы без User-Agent
        }

    /** Сравнение вида «1.2» и «1.10» по числам, а не по строкам. */
    private fun isNewer(version: String): Boolean {
        val new = parts(version)
        val cur = parts(BuildConfig.VERSION_NAME)
        for (i in 0 until maxOf(new.size, cur.size)) {
            val a = new.getOrElse(i) { 0 }
            val b = cur.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parts(v: String) = v.trim().split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }

    companion object {
        private const val TIMEOUT_MS = 15000
    }
}

package com.pixel.lockdock

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseUpdateInfo(
    val tagName: String,
    val remoteVersionCode: Int,
    val title: String,
    val changelog: String,
    val apkDownloadUrl: String,
    val sizeBytes: Long
)

/**
 * Built-in OTA self-updater connected to GitHub Releases (kamaJe122/LockDock).
 * Allows checking, downloading, and installing updates in 1 tap right inside LockDock!
 */
object AppUpdater {

    private const val PREF_UPDATE_REPO = "github_update_repo"
    const val DEFAULT_GITHUB_REPO = "kamaJe122/LockDock"

    fun getConfiguredRepo(context: Context): String {
        val prefs = context.getSharedPreferences("lockdock_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getString(PREF_UPDATE_REPO, DEFAULT_GITHUB_REPO).orEmpty().trim()
        return saved.ifBlank { DEFAULT_GITHUB_REPO }
    }

    fun setConfiguredRepo(context: Context, repoSlug: String) {
        val clean = repoSlug.trim()
            .removePrefix("https://github.com/")
            .trim('/')
            .ifBlank { DEFAULT_GITHUB_REPO }
        context.getSharedPreferences("lockdock_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_UPDATE_REPO, clean)
            .apply()
    }

    fun getCurrentVersionInfo(context: Context): Pair<Int, String> {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.longVersionCode.toInt() to (pInfo.versionName ?: "1.9.0")
        } catch (_: Exception) {
            9 to "1.9.0"
        }
    }

    suspend fun checkLatestRelease(context: Context): Result<ReleaseUpdateInfo> = withContext(Dispatchers.IO) {
        val repo = getConfiguredRepo(context)
        if (repo.isBlank() || !repo.contains("/")) {
            return@withContext Result.failure(IllegalStateException("GitHub 倉庫格式錯誤（應為 帳號/倉庫名）"))
        }

        runCatching {
            val apiUrl = "https://api.github.com/repos/$repo/releases/latest?t=${System.currentTimeMillis()}"
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                useCaches = false
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("User-Agent", "LockDock-Android-Updater")
            }

            try {
                val code = conn.responseCode
                if (code != 200) {
                    throw IllegalStateException("無法取得更新資訊 (HTTP $code)")
                }
                val bodyText = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(bodyText)
                val tag = json.optString("tag_name", "")
                val name = json.optString("name", tag)
                val notes = json.optString("body", "無更新說明")

                val buildFromTag = Regex("""-b(\d+)$""").find(tag)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val buildFromNotes = Regex("""\[build:(\d+)]""").find(notes)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val parsedBuild = buildFromTag ?: buildFromNotes ?: 0

                val assets = json.optJSONArray("assets")
                var apkUrl = ""
                var apkSize = 0L
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        val assetName = a.optString("name", "")
                        if (assetName.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = a.optString("browser_download_url", "")
                            apkSize = a.optLong("size", 0L)
                            break
                        }
                    }
                }
                if (apkUrl.isBlank()) {
                    throw IllegalStateException("最新 Release ($tag) 中找不到 .apk 安裝檔")
                }

                ReleaseUpdateInfo(
                    tagName = tag.replace(Regex("""-b\d+$"""), ""),
                    remoteVersionCode = parsedBuild,
                    title = name,
                    changelog = notes.replace(Regex("""\[build:\d+]"""), "").trim(),
                    apkDownloadUrl = apkUrl,
                    sizeBytes = apkSize
                )
            } finally {
                conn.disconnect()
            }
        }
    }

    suspend fun downloadAndInstallApk(
        context: Context,
        apkUrl: String,
        onProgress: (Int) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val targetFile = File(updatesDir, "LockDock-latest.apk")
            if (targetFile.exists()) targetFile.delete()

            var currentUrl = apkUrl
            var redirects = 0
            var conn: HttpURLConnection
            while (true) {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    useCaches = false
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("User-Agent", "LockDock-Android-Updater")
                }
                val status = conn.responseCode
                if (status in 301..308) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrBlank() || ++redirects > 6) {
                        throw IllegalStateException("下載重新導向失敗")
                    }
                    currentUrl = loc
                } else if (status == 200) {
                    break
                } else {
                    conn.disconnect()
                    throw IllegalStateException("下載 APK 失敗 (HTTP $status)")
                }
            }

            try {
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buf = ByteArray(32 * 1024)
                        var downloaded = 0L
                        var lastPct = -1
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    withContext(Dispatchers.Main) { onProgress(pct) }
                                }
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            withContext(Dispatchers.Main) {
                onProgress(100)
                promptInstallApk(context, targetFile)
            }
        }
    }

    fun promptInstallApk(context: Context, apkFile: File) {
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            // Android 17 inline PackageInstaller handles unknown-sources prompt + update seamlessly
            context.startActivity(installIntent)
        } catch (_: Throwable) {
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(permIntent) }
        }
    }
}

package com.jumpropeai.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppReleaseInfo(
    val versionName: String,
    val apkDownloadUrl: String,
    val releasePageUrl: String,
    val releaseNotes: String
)

sealed interface UpdateCheckResult {
    data class UpdateAvailable(val release: AppReleaseInfo) : UpdateCheckResult
    data class UpToDate(val currentVersion: String) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

/**
 * Lightweight, zero-dependency GitHub Releases update checker for Jumpvex.
 * Checks https://api.github.com/repos/iganramdani/Jumpvex/releases/latest
 * on a background IO thread and returns direct APK download info when a newer version exists.
 */
object AppUpdateChecker {

    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/iganramdani/Jumpvex/releases/latest"
    const val RELEASES_PAGE_URL =
        "https://github.com/iganramdani/Jumpvex/releases/latest"

    suspend fun checkForUpdate(currentVersion: String): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(LATEST_RELEASE_API)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6_000
                    readTimeout = 6_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "Jumpvex-Android/$currentVersion")
                }

                val code = connection.responseCode
                if (code !in 200..299) {
                    return@withContext UpdateCheckResult.Error("Server returned HTTP $code")
                }

                val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseBody)

                val rawTag = json.optString("tag_name", "").trim()
                val latestVersion = rawTag.removePrefix("v").removePrefix("V")
                val releasePageUrl = json.optString("html_url", RELEASES_PAGE_URL)
                    .ifBlank { RELEASES_PAGE_URL }
                val rawNotes = json.optString("body", "").trim()

                var apkUrl = releasePageUrl
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name", "")
                        val downloadUrl = asset.optString("browser_download_url", "")
                        if (name.endsWith(".apk", ignoreCase = true) && downloadUrl.isNotBlank()) {
                            apkUrl = downloadUrl
                            break
                        }
                    }
                }

                if (latestVersion.isNotBlank() && isNewerVersion(latestVersion, currentVersion)) {
                    UpdateCheckResult.UpdateAvailable(
                        AppReleaseInfo(
                            versionName = latestVersion,
                            apkDownloadUrl = apkUrl,
                            releasePageUrl = releasePageUrl,
                            releaseNotes = cleanReleaseNotes(rawNotes)
                        )
                    )
                } else {
                    UpdateCheckResult.UpToDate(currentVersion)
                }
            } catch (e: Exception) {
                UpdateCheckResult.Error(e.localizedMessage ?: "No internet connection")
            } finally {
                connection?.disconnect()
            }
        }

    internal fun isNewerVersion(candidate: String, current: String): Boolean {
        val candParts = parseSemVer(candidate)
        val currParts = parseSemVer(current)
        val maxLen = maxOf(candParts.size, currParts.size)
        for (i in 0 until maxLen) {
            val c = candParts.getOrElse(i) { 0 }
            val cur = currParts.getOrElse(i) { 0 }
            if (c != cur) return c > cur
        }
        return false
    }

    private fun parseSemVer(version: String): List<Int> {
        return version
            .trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore("-")
            .split(".")
            .map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
    }

    private fun cleanReleaseNotes(raw: String): String {
        if (raw.isBlank()) return "New features, improvements, and bug fixes."
        val lines = raw.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("![") && !it.startsWith("---") }
            .take(5)
        return lines.joinToString("\n").take(320)
    }
}

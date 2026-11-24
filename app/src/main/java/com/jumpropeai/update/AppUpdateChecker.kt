package com.jumpropeai.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppReleaseInfo(
    val versionName: String,
    val apkDownloadUrl: String,
    val releasePageUrl: String,
    val releaseNotes: String,
    val apkSizeMb: Float = 0f,
    val sha256Digest: String? = null
)

sealed interface UpdateCheckResult {
    data class UpdateAvailable(val release: AppReleaseInfo) : UpdateCheckResult
    data class UpToDate(val currentVersion: String) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

/**
 * Lightweight, zero-dependency GitHub Releases update checker for Jumpvex.
 * Strictly prioritizes `*-release.apk` over debug APKs and extracts size + SHA-256 metadata.
 */
object AppUpdateChecker {

    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/iganramdani/Jumpvex/releases/latest"
    const val RELEASES_PAGE_URL =
        "https://github.com/iganramdani/Jumpvex/releases/latest"

    internal data class SelectedApkAsset(
        val downloadUrl: String,
        val sizeMb: Float,
        val sha256: String?
    )

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

                val selectedAsset = selectBestApkAsset(json.optJSONArray("assets"), releasePageUrl)

                if (latestVersion.isNotBlank() && isNewerVersion(latestVersion, currentVersion)) {
                    UpdateCheckResult.UpdateAvailable(
                        AppReleaseInfo(
                            versionName = latestVersion,
                            apkDownloadUrl = selectedAsset.downloadUrl,
                            releasePageUrl = releasePageUrl,
                            releaseNotes = cleanReleaseNotes(rawNotes),
                            apkSizeMb = selectedAsset.sizeMb,
                            sha256Digest = selectedAsset.sha256
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

    internal fun selectBestApkAsset(assets: JSONArray?, fallbackUrl: String): SelectedApkAsset {
        if (assets == null || assets.length() == 0) {
            return SelectedApkAsset(fallbackUrl, 0f, null)
        }
        var releaseMatch: JSONObject? = null
        var nonDebugMatch: JSONObject? = null
        var anyApkMatch: JSONObject? = null

        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "")
            val url = asset.optString("browser_download_url", "")
            if (!name.endsWith(".apk", ignoreCase = true) || url.isBlank()) continue

            if (anyApkMatch == null) anyApkMatch = asset
            val isDebug = name.contains("debug", ignoreCase = true)
            val isRelease = name.contains("release", ignoreCase = true)
            if (isRelease && !isDebug && releaseMatch == null) {
                releaseMatch = asset
            }
            if (!isDebug && nonDebugMatch == null) {
                nonDebugMatch = asset
            }
        }

        val chosen = releaseMatch ?: nonDebugMatch ?: anyApkMatch
            ?: return SelectedApkAsset(fallbackUrl, 0f, null)

        val sizeBytes = chosen.optLong("size", 0L)
        val sizeMb = if (sizeBytes > 0L) (sizeBytes / (1024f * 1024f)) else 0f
        val rawDigest = chosen.optString("digest", "").removePrefix("sha256:").takeIf { it.length >= 16 }
        return SelectedApkAsset(
            downloadUrl = chosen.optString("browser_download_url", fallbackUrl).ifBlank { fallbackUrl },
            sizeMb = sizeMb,
            sha256 = rawDigest
        )
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
            .take(6)
        return lines.joinToString("\n").take(360)
    }
}

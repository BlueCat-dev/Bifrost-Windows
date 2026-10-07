package com.bifrost.twp.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class UpdateResult(
    val hasUpdate: Boolean,
    val latestVersion: String,
    val releaseUrl: String,
    val releaseNotes: String?
)

object UpdateChecker {

    private const val GITHUB_LATEST_RELEASE_URL =
        "https://api.github.com/repos/Qorvhex/Bifrost/releases/latest"

    private const val GITHUB_FALLBACK_URL =
        "https://github.com/Qorvhex/Bifrost/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun checkUpdate(currentVersionName: String): Result<UpdateResult> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(GITHUB_LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "Bifrost-Android-App")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("خطا در ارتباط با سرور گیت‌هاب (${response.code})")
                    )
                }

                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val tagName = json.optString("tag_name", "").trim()
                val releaseUrl = json.optString("html_url", GITHUB_FALLBACK_URL).ifBlank { GITHUB_FALLBACK_URL }
                val releaseNotes = json.optString("body", "").trim().takeIf { it.isNotBlank() }

                val remoteVersionClean = tagName.removePrefix("v").removePrefix("V").trim()
                val currentVersionClean = currentVersionName.removePrefix("v").removePrefix("V").trim()

                val hasUpdate = isVersionGreater(remoteVersionClean, currentVersionClean)

                Result.success(
                    UpdateResult(
                        hasUpdate = hasUpdate,
                        latestVersion = tagName.ifBlank { "v$remoteVersionClean" },
                        releaseUrl = releaseUrl,
                        releaseNotes = releaseNotes
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isVersionGreater(remote: String, current: String): Boolean {
        if (remote.isBlank() || current.isBlank()) return false
        val remoteParts = remote.split(".").mapNotNull { it.takeWhile { char -> char.isDigit() }.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.takeWhile { char -> char.isDigit() }.toIntOrNull() }

        val maxLength = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLength) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }
}

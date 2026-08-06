// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ml.container

import android.util.Log
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks whether a newer version of the free model container is available,
 * using the GitHub Releases API. We deliberately read release tags rather than
 * a hand-maintained version file, so the check reflects exactly what's published
 * — there's no second source to keep in sync.
 *
 * Free container releases are tagged `container-free-v<N>` and carry the
 * `eidora-free.eidoramodel` asset. The trailing integer N is the container
 * version, compared against the installed manifest's `container.version`.
 *
 * Everything here is best-effort and defensive: any network error, rate-limit,
 * or unexpected JSON yields "no update" rather than throwing, so a failed check
 * never disrupts the app. Callers must run this off the main thread.
 */
object ContainerUpdateChecker {
    private const val TAG = "ContainerUpdate"

    private const val RELEASES_API =
        "https://api.github.com/repos/sludgefeast/eidora/releases"

    private const val TAG_PREFIX = "container-free-v"
    private const val ASSET_NAME = "eidora-free.eidoramodel"
    private const val CHECKSUM_ASSET_NAME = "container-sha256.txt"

    /** Result of a successful check that found a newer version. */
    data class Available(
        val version: Int,
        val tag: String,
        val downloadUrl: String,
        // URL of the release's sha256 checksum file, if attached. Used to verify
        // the download instead of a hard-coded hash. Null when the release has
        // no checksum asset (then the caller can't verify and should decide).
        val checksumUrl: String?,
    )

    /**
     * Returns an [Available] if a container version higher than [installedVersion]
     * is published, or null otherwise (already current, offline, rate-limited,
     * or anything unexpected). Never throws.
     */
    fun checkForUpdate(installedVersion: Int): Available? {
        return try {
            val json = fetch(RELEASES_API) ?: return null
            val releases = JSONArray(json)
            var best: Available? = null
            for (i in 0 until releases.length()) {
                val release = releases.optJSONObject(i) ?: continue
                if (release.optBoolean("draft", false)) continue
                if (release.optBoolean("prerelease", false)) continue
                val tag = release.optString("tag_name").orEmpty()
                if (!tag.startsWith(TAG_PREFIX)) continue
                val version = tag.removePrefix(TAG_PREFIX).toIntOrNull() ?: continue
                if (version <= installedVersion) continue
                // Find the container asset's download URL (and the checksum's).
                val assets = release.optJSONArray("assets") ?: continue
                var url: String? = null
                var checksumUrl: String? = null
                for (j in 0 until assets.length()) {
                    val asset = assets.optJSONObject(j) ?: continue
                    when (asset.optString("name")) {
                        ASSET_NAME ->
                            url = asset.optString("browser_download_url")
                                .takeIf { it.isNotBlank() }
                        CHECKSUM_ASSET_NAME ->
                            checksumUrl = asset.optString("browser_download_url")
                                .takeIf { it.isNotBlank() }
                    }
                }
                if (url == null) continue
                // Keep the highest version across all releases.
                if (best == null || version > best.version) {
                    best = Available(
                        version = version,
                        tag = tag,
                        downloadUrl = url,
                        checksumUrl = checksumUrl,
                    )
                }
            }
            best
        } catch (t: Throwable) {
            Log.w(TAG, "Update check failed", t)
            null
        }
    }

    /**
     * Returns the latest published free container release, regardless of any
     * installed version — used for first-run install, where there's nothing
     * installed to compare against. Null on any failure.
     */
    fun latestRelease(): Available? = checkForUpdate(installedVersion = -1)

    /**
     * Downloads the sha256 checksum file at [checksumUrl] and returns the hex
     * hash for the container asset. The file is standard `sha256sum` output —
     * lines of `<hex>  <filename>`. Returns null on any failure or if no line
     * matches the container asset, so the caller can decide how to proceed.
     * Must run off the main thread.
     */
    fun fetchExpectedSha256(checksumUrl: String): String? {
        val body = fetch(checksumUrl) ?: return null
        return body.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                // "<hex>  <filename>" — split on whitespace.
                val parts = trimmed.split(Regex("\\s+"), limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val (hash, name) = parts
                // Match the container asset; tolerate a leading "*" (binary mode).
                if (name.removePrefix("*") == ASSET_NAME) hash.lowercase() else null
            }
            .firstOrNull()
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    }

    private fun fetch(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                // GitHub asks for a User-Agent; without it the API returns 403.
                setRequestProperty("User-Agent", "Eidora")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "Releases API HTTP ${conn.responseCode}")
                return null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            Log.w(TAG, "Releases API fetch failed", t)
            null
        } finally {
            conn?.disconnect()
        }
    }
}

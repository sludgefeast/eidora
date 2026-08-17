// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ml.container

import android.content.Context
import org.eidora.util.EidoraLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Downloads a model container (.eidoramodel — a zip of manifest.yml + .tflite
 * files), verifies its SHA-256, unpacks it into app storage, and parses the
 * manifest.
 *
 * This is the first step of "bring your own model": fetching the free container
 * shipped in the repo on first run. It does not yet wire the models into the
 * detection/embedding pipeline — it only makes a verified, parsed container
 * available on disk.
 *
 * Unpacked layout:
 *
 *     filesDir/containers/<container-id>/manifest.yml
 *     filesDir/containers/<container-id>/<model>.tflite
 */
object ContainerDownloader {
    private const val TAG = "ContainerDownloader"

    /**
     * Downloads and unpacks the free container for first-run install.
     *
     * Resolves the latest release (URL + checksum) from the GitHub Releases API,
     * then downloads and verifies against the release's own sha256 checksum file.
     * No hash is hard-coded; integrity rests on HTTPS to GitHub plus the checksum
     * within the same release. Must run off the main thread.
     */
    fun downloadFreeContainer(
        context: Context,
        onProgress: ((Int) -> Unit)? = null,
    ): Result {
        val release =
            ContainerUpdateChecker.latestRelease()
                ?: return Result.NetworkError("could not resolve latest release")
        val expectedSha =
            if (release.checksumUrl != null) {
                ContainerUpdateChecker.fetchExpectedSha256(release.checksumUrl)
                    ?: return Result.Invalid("checksum unavailable")
            } else {
                null
            }
        return downloadAndUnpack(context, release.downloadUrl, expectedSha, onProgress)
    }

    /** Outcome of a free-container update. */
    sealed interface UpdateResult {
        /**
         * The container was replaced. [embeddingSpaceChanged] is true when the
         * new embedder produces a different vector space, meaning all stored
         * embeddings are now invalid and must be recomputed. When false, existing
         * embeddings stay valid (e.g. a detector-only update).
         */
        data class Success(
            val manifest: ContainerManifest,
            val embeddingSpaceChanged: Boolean,
        ) : UpdateResult

        data class NetworkError(val detail: String) : UpdateResult

        data class Invalid(val detail: String) : UpdateResult
    }

    /**
     * Updates the free container from [url] (an asset URL from the update check).
     *
     * Reads the currently-installed embedding_space BEFORE the download
     * overwrites the manifest, then compares it to the new one so the caller
     * knows whether a full re-embed is required.
     *
     * If [checksumUrl] is given, the container's expected SHA-256 is fetched from
     * the release's checksum file and verified — no hash is hard-coded, since a
     * future release's hash isn't known at build time. If [checksumUrl] is null
     * (release has no checksum asset), the download proceeds unverified and
     * integrity rests on successful manifest parsing and unpacking.
     *
     * Must run off the main thread.
     */
    fun updateFreeContainer(
        context: Context,
        url: String,
        checksumUrl: String?,
        onProgress: ((Int) -> Unit)? = null,
    ): UpdateResult {
        // Capture the old embedding space before anything is overwritten.
        val oldSpace = readInstalledEmbeddingSpace(context)
        // Fetch the expected hash from the release, if a checksum file is present.
        val expectedSha: String?
        if (checksumUrl != null) {
            val fetched = ContainerUpdateChecker.fetchExpectedSha256(checksumUrl)
            if (fetched == null) {
                // A checksum was advertised but we couldn't read it — refuse to
                // install unverified rather than silently skipping the check.
                return UpdateResult.Invalid("checksum unavailable")
            }
            expectedSha = fetched
        } else {
            // No checksum asset on the release: proceed unverified (integrity
            // rests on manifest parsing + unpacking).
            expectedSha = null
        }
        return when (val result = downloadAndUnpack(context, url, expectedSha256 = expectedSha, onProgress)) {
            is Result.Success -> {
                val newSpace = result.manifest.container.embeddingSpace
                // If either side didn't declare a space, be conservative and treat
                // it as changed (forces recompute) — silent incompatibility is the
                // worse failure.
                val changed = oldSpace == null || newSpace == null || oldSpace != newSpace
                UpdateResult.Success(result.manifest, embeddingSpaceChanged = changed)
            }
            is Result.NetworkError -> UpdateResult.NetworkError(result.detail)
            is Result.HashMismatch -> UpdateResult.Invalid("hash mismatch")
            is Result.Invalid -> UpdateResult.Invalid(result.detail)
        }
    }

    /** Reads the installed free container's embedding_space, or null if absent. */
    private fun readInstalledEmbeddingSpace(context: Context): String? {
        return try {
            val manifestFile = File(containerDir(context, FREE_CONTAINER_ID), "manifest.yml")
            if (!manifestFile.isFile) return null
            manifestFile.inputStream().use {
                ContainerManifestParser.parse(it).container.embeddingSpace
            }
        } catch (t: Throwable) {
            EidoraLog.w(TAG, "fallback after error: ${t.message}")
            null
        }
    }

    /**
     * Fetches the free container's download size in bytes via an HTTP HEAD
     * request, so the UI can show it before downloading. Returns null on any
     * failure (offline, redirect without a length, etc.) — the caller decides
     * how to present an unknown size. This is a cheap, header-only request.
     */
    fun fetchFreeContainerSize(): Long? {
        return try {
            val release = ContainerUpdateChecker.latestRelease() ?: return null
            val connection = (URL(release.downloadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = true
            }
            connection.connect()
            val size =
                if (connection.responseCode in 200..299) {
                    connection.contentLengthLong
                } else {
                    -1L
                }
            connection.disconnect()
            size.takeIf { it > 0 }
        } catch (t: Throwable) {
            EidoraLog.w(TAG, "size.takeIfit>0 failed: ${t.message}")
            null
        }
    }

    /** The free container's id (matches docs/containers/free-models/manifest.yml). */
    const val FREE_CONTAINER_ID = "eidora-free"

    /**
     * True if the free container is already unpacked on disk (its dir exists
     * and contains a manifest.yml). Used to decide whether first-run download is
     * still needed.
     */
    fun isFreeContainerReady(context: Context): Boolean {
        val dir = containerDir(context, FREE_CONTAINER_ID)
        return dir.isDirectory && File(dir, "manifest.yml").isFile
    }

    /** Where unpacked containers live. */
    fun containersRoot(context: Context): File = File(context.filesDir, "containers")

    fun containerDir(context: Context, containerId: String): File =
        File(containersRoot(context), containerId)

    sealed interface Result {
        /** Container downloaded, verified, unpacked and parsed. */
        data class Success(val manifest: ContainerManifest, val dir: File) : Result

        /** Transient network problem — caller may retry. */
        data class NetworkError(val detail: String) : Result

        /** Downloaded bytes didn't match the expected SHA-256. */
        data object HashMismatch : Result

        /** The zip was malformed, or its manifest failed Class-1 validation. */
        data class Invalid(val detail: String) : Result
    }

    /**
     * Fetches [url], checks it against [expectedSha256] (hex, lowercase; pass
     * null to skip), unpacks into filesDir/containers/<id>, and parses the
     * manifest. Blocking — call off the main thread.
     */
    private fun downloadAndUnpack(
        context: Context,
        url: String,
        expectedSha256: String?,
        onProgress: ((Int) -> Unit)? = null,
    ): Result {
        val tmpZip = File(context.filesDir, "container-download.part")
        try {
            when (val dl = fetch(url, tmpZip, onProgress)) {
                is FetchOutcome.Ok -> Unit
                is FetchOutcome.Network -> return Result.NetworkError(dl.detail)
            }

            if (expectedSha256 != null) {
                val actual = sha256Hex(tmpZip)
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    EidoraLog.e(TAG, "Container hash mismatch: expected $expectedSha256, got $actual")
                    return Result.HashMismatch
                }
            }

            // Peek the manifest to learn the container id, so we can name the dir.
            val manifest =
                try {
                    readManifestFromZip(tmpZip)
                } catch (e: ContainerManifestParser.ManifestException) {
                    EidoraLog.w("ContainerDownloader", "invalid container: ${e.message}", e)
                    return Result.Invalid(e.message ?: "invalid manifest")
                } catch (e: Throwable) {
                    EidoraLog.w(TAG, "Result.Invalid(e.message?invalidmanife failed: ${e.message}")
                    return Result.Invalid("could not read manifest: ${e.message}")
                }

            val dir = containerDir(context, manifest.container.id)
            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()

            try {
                unpackInto(tmpZip, dir, manifest)
            } catch (e: Throwable) {
                EidoraLog.w("ContainerDownloader", "invalid container: ${e.message}", e)
                dir.deleteRecursively()
                return Result.Invalid("could not unpack container: ${e.message}")
            }

            EidoraLog.i(TAG, "Container '${manifest.container.id}' unpacked to $dir")
            return Result.Success(manifest, dir)
        } finally {
            tmpZip.delete()
        }
    }

    // --- internals ---

    private sealed interface FetchOutcome {
        data object Ok : FetchOutcome

        data class Network(val detail: String) : FetchOutcome
    }

    private fun fetch(url: String, target: File, onProgress: ((Int) -> Unit)?): FetchOutcome {
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }
            connection.connect()
            if (connection.responseCode !in 200..299) {
                return FetchOutcome.Network("HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            var done = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0 && onProgress != null) {
                            onProgress(((done * 100) / total).toInt())
                        }
                    }
                }
            }
            FetchOutcome.Ok
        } catch (t: Throwable) {
            EidoraLog.e(TAG, "Container download failed", t)
            FetchOutcome.Network(t.message ?: "network error")
        }
    }

    /** Reads and parses just manifest.yml from the zip without extracting. */
    private fun readManifestFromZip(zip: File): ContainerManifest {
        zip.inputStream().use { fileIn ->
            ZipInputStream(fileIn).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    if (entry.name == "manifest.yml") {
                        // snakeyaml consumes the stream; hand it the entry bytes.
                        val bytes = zin.readBytes()
                        return ContainerManifestParser.parse(bytes.inputStream())
                    }
                    zin.closeEntry()
                }
            }
        }
        throw ContainerManifestParser.ManifestException("manifest.yml not found in container")
    }

    /**
     * Extracts manifest.yml and every model file the manifest references into
     * [dir]. Guards against zip-slip (entry names escaping the target dir) and
     * ignores entries the manifest doesn't reference.
     */
    private fun unpackInto(zip: File, dir: File, manifest: ContainerManifest) {
        val wanted = manifest.models.map { it.file }.toSet() + "manifest.yml"
        val canonicalDir = dir.canonicalPath
        zip.inputStream().use { fileIn ->
            ZipInputStream(fileIn).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory || name !in wanted) {
                        zin.closeEntry()
                        continue
                    }
                    val out = File(dir, name)
                    if (!out.canonicalPath.startsWith(canonicalDir + File.separator)) {
                        throw SecurityException("zip entry escapes target dir: $name")
                    }
                    out.outputStream().use { zin.copyTo(it) }
                    zin.closeEntry()
                }
            }
        }
        // Confirm every referenced model file actually landed.
        val missing = manifest.models.map { it.file }.filter { !File(dir, it).exists() }
        if (missing.isNotEmpty()) {
            throw IllegalStateException("container missing model file(s): $missing")
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

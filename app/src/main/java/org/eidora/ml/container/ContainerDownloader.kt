// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ml.container

import android.content.Context
import android.util.Log
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
     * The free model container (YuNet detector + SFace embedder, both
     * Apache-2.0), built and published by the build-free-container workflow.
     * This is what Eidora downloads on first run.
     */
    const val FREE_CONTAINER_URL =
        "https://github.com/sludgefeast/eidora/releases/download/" +
            "container-free-v1/eidora-free.eidoramodel"
    const val FREE_CONTAINER_SHA256 =
        "4fd7c2842772d9ee615216d6d01192271d03528337eb4c74d7c0365657b83de5"

    /** Downloads and unpacks the bundled free container. */
    fun downloadFreeContainer(
        context: Context,
        onProgress: ((Int) -> Unit)? = null,
    ): Result = downloadAndUnpack(context, FREE_CONTAINER_URL, FREE_CONTAINER_SHA256, onProgress)

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
    fun downloadAndUnpack(
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
                    Log.e(TAG, "Container hash mismatch: expected $expectedSha256, got $actual")
                    return Result.HashMismatch
                }
            }

            // Peek the manifest to learn the container id, so we can name the dir.
            val manifest =
                try {
                    readManifestFromZip(tmpZip)
                } catch (e: ContainerManifestParser.ManifestException) {
                    return Result.Invalid(e.message ?: "invalid manifest")
                } catch (e: Throwable) {
                    return Result.Invalid("could not read manifest: ${e.message}")
                }

            val dir = containerDir(context, manifest.container.id)
            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()

            try {
                unpackInto(tmpZip, dir, manifest)
            } catch (e: Throwable) {
                dir.deleteRecursively()
                return Result.Invalid("could not unpack container: ${e.message}")
            }

            Log.i(TAG, "Container '${manifest.container.id}' unpacked to $dir")
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
            Log.e(TAG, "Container download failed", t)
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
